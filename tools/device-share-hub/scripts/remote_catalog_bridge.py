"""Authenticated Cloudflare Remote Catalog, LAN Auto-Enrollment & Traffic Stats Bridge.

Runs inside `online_gallery_service.py` (port 45835) to:
1. Maintain an ES256 P-256 admin workspace identity and short-lived Bearer session with
   `zwm-device-share-relay` (Cloudflare Workers + DO + R2).
2. Issue ES256 member certificates to authorized LAN phones via `POST /api/online/remote-enroll`
   so they automatically gain 4G/5G/different-WiFi browsing and transfer access without manual tokens.
3. Sync compact online catalog metadata + lazy thumbnails (R2 LRU capped) when the PC work shelf changes.
4. Fulfill authenticated on-demand HD original file pulls strictly within `libraryRoot` without exposing
   the library publicly or resizing/recompressing originals.
5. Aggregate local USB/LAN/P2P/R2 transfer stats alongside Cloudflare `/v1/stats` estimates.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import (
    decode_dss_signature,
    encode_dss_signature,
)

DEFAULT_RELAY_ENDPOINT = os.environ.get(
    "ALBUM_RELAY_ENDPOINT",
    "https://zwm-device-share-relay.zwmrpg.workers.dev",
).rstrip("/")


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def b64url_decode(val: str) -> bytes:
    padded = val + "=" * ((4 - len(val) % 4) % 4)
    return base64.urlsafe_b64decode(padded.encode("ascii"))


def _int_to_32bytes(n: int) -> bytes:
    return n.to_bytes(32, byteorder="big")


def jwk_from_public_key(pub: ec.EllipticCurvePublicKey) -> Dict[str, str]:
    nums = pub.public_numbers()
    return {
        "kty": "EC",
        "crv": "P-256",
        "x": b64url_encode(_int_to_32bytes(nums.x)),
        "y": b64url_encode(_int_to_32bytes(nums.y)),
    }


def workspace_id_for_jwk(pub_jwk: Dict[str, str]) -> str:
    digest = hashlib.sha256(canonical_json_bytes(pub_jwk)).hexdigest()
    return f"ws_{digest[:32]}"


def sign_es256_p1363(priv: ec.EllipticCurvePrivateKey, payload: Any) -> str:
    """Signs canonical JSON bytes and outputs IEEE P1363 (r||s, 64B) base64url matching WebCrypto ES256."""
    der_sig = priv.sign(canonical_json_bytes(payload), ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der_sig)
    raw = _int_to_32bytes(r) + _int_to_32bytes(s)
    return b64url_encode(raw)


def verify_es256_p1363(pub_jwk: Dict[str, str], payload: Any, sig_b64: str) -> bool:
    try:
        x = int.from_bytes(b64url_decode(pub_jwk["x"]), "big")
        y = int.from_bytes(b64url_decode(pub_jwk["y"]), "big")
        pub = ec.EllipticCurvePublicNumbers(x, y, ec.SECP256R1()).public_key()
        raw = b64url_decode(sig_b64)
        if len(raw) != 64:
            return False
        r = int.from_bytes(raw[:32], "big")
        s = int.from_bytes(raw[32:], "big")
        pub.verify(encode_dss_signature(r, s), canonical_json_bytes(payload), ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


def catalog_thumb_key(work_id: str, image_name: str) -> str:
    h = hashlib.sha256(f"{work_id}/{image_name}".encode("utf-8")).hexdigest()[:20]
    return f"th_{h}"


class LocalTransferStatsStore:
    """Persists daily and monthly channel byte/count metrics locally for PC + mobile visibility."""

    def __init__(self, stats_path: Path):
        self.stats_path = stats_path
        self._lock = threading.Lock()
        self._data: Dict[str, Any] = self._load()

    def _load(self) -> Dict[str, Any]:
        if self.stats_path.is_file():
            try:
                return json.loads(self.stats_path.read_text(encoding="utf-8"))
            except Exception:
                pass
        return {"days": {}, "months": {}}

    def _save_locked(self) -> None:
        self.stats_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.stats_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(self.stats_path)

    def record(self, channel: str, bytes_count: int = 0, objects: int = 1, works: int = 0, success: bool = True, duration_ms: int = 0) -> None:
        ch = channel.lower().strip()
        if ch not in ("usb", "lan", "p2p", "r2"):
            ch = "lan"
        day_k = time.strftime("%Y-%m-%d", time.gmtime())
        month_k = time.strftime("%Y-%m", time.gmtime())
        with self._lock:
            for scope_name, key in (("days", day_k), ("months", month_k)):
                bucket = self._data.setdefault(scope_name, {}).setdefault(key, {})
                bucket[f"{ch}Bytes"] = int(bucket.get(f"{ch}Bytes", 0)) + max(0, int(bytes_count))
                bucket[f"{ch}Ms"] = int(bucket.get(f"{ch}Ms", 0)) + max(0, int(duration_ms))
                bucket[f"{ch}Ok" if success else f"{ch}Fail"] = int(bucket.get(f"{ch}Ok" if success else f"{ch}Fail", 0)) + 1
                bucket["totalBytes"] = int(bucket.get("totalBytes", 0)) + max(0, int(bytes_count))
                bucket["totalObjects"] = int(bucket.get("totalObjects", 0)) + max(0, int(objects))
                bucket["totalWorks"] = int(bucket.get("totalWorks", 0)) + max(0, int(works))
            # Prune old daily buckets (>45)
            days = self._data.get("days", {})
            if len(days) > 45:
                for old_k in sorted(days.keys())[:-45]:
                    days.pop(old_k, None)
            self._save_locked()

    def snapshot(self) -> Dict[str, Any]:
        day_k = time.strftime("%Y-%m-%d", time.gmtime())
        month_k = time.strftime("%Y-%m", time.gmtime())
        with self._lock:
            return {
                "dayKey": day_k,
                "monthKey": month_k,
                "today": dict(self._data.get("days", {}).get(day_k, {})),
                "month": dict(self._data.get("months", {}).get(month_k, {})),
            }


class RemoteCatalogBridge:
    def __init__(
        self,
        state_dir: Path,
        endpoint: str = DEFAULT_RELAY_ENDPOINT,
        get_works_fn: Optional[Callable[[], Tuple[int, List[Dict[str, Any]], str]]] = None,
        get_thumb_fn: Optional[Callable[[str, str], Optional[bytes]]] = None,
        mark_work_used_fn: Optional[Callable[[str, str], None]] = None,
    ):
        self.state_dir = state_dir
        self.endpoint = endpoint.rstrip("/")
        self.admin_path = state_dir / "remote_catalog_admin.json"
        self.stats_store = LocalTransferStatsStore(state_dir / "transfer_stats.json")
        self.get_works_fn = get_works_fn
        self.get_thumb_fn = get_thumb_fn
        self.mark_work_used_fn = mark_work_used_fn

        self._lock = threading.Lock()
        self._priv, self._pub_jwk, self._device_id, self._workspace_id, self._next_serial = self._load_or_init_identity()
        self._registered = False
        self._token: Optional[str] = None
        self._token_expires_at: float = 0.0
        self._last_synced_revision: int = -1
        self._uploaded_thumbs: set[str] = set()
        self._cached_cloud_stats: Optional[Dict[str, Any]] = None
        self._cached_cloud_stats_at: float = 0.0
        self._last_error: str = ""
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def _load_or_init_identity(self) -> Tuple[ec.EllipticCurvePrivateKey, Dict[str, str], str, str, int]:
        if self.admin_path.is_file():
            try:
                saved = json.loads(self.admin_path.read_text(encoding="utf-8"))
                pem = saved["privatePem"].encode("utf-8")
                priv = serialization.load_pem_private_key(pem, password=None)
                if isinstance(priv, ec.EllipticCurvePrivateKey):
                    pub_jwk = jwk_from_public_key(priv.public_key())
                    return (
                        priv,
                        pub_jwk,
                        saved["deviceId"],
                        workspace_id_for_jwk(pub_jwk),
                        int(saved.get("nextSerial", 2)),
                    )
            except Exception:
                pass

        priv = ec.generate_private_key(ec.SECP256R1())
        pub_jwk = jwk_from_public_key(priv.public_key())
        ws_id = workspace_id_for_jwk(pub_jwk)
        device_id = f"pc_{hashlib.sha256(ws_id.encode()).hexdigest()[:16]}"
        self._save_identity(priv, device_id, ws_id, 2)
        return priv, pub_jwk, device_id, ws_id, 2

    def _save_identity(self, priv: ec.EllipticCurvePrivateKey, device_id: str, ws_id: str, next_serial: int) -> None:
        self.state_dir.mkdir(parents=True, exist_ok=True)
        pem = priv.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        ).decode("utf-8")
        payload = {
            "version": 1,
            "deviceId": device_id,
            "workspaceId": ws_id,
            "nextSerial": next_serial,
            "privatePem": pem,
        }
        tmp = self.admin_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        tmp.replace(self.admin_path)

    @property
    def workspace_id(self) -> str:
        return self._workspace_id

    def admin_certificate(self) -> Tuple[Dict[str, Any], str]:
        now_ms = int(time.time() * 1000)
        cert = {
            "version": 1,
            "workspaceId": self._workspace_id,
            "deviceId": self._device_id,
            "deviceName": "团队主控电脑",
            "role": "admin",
            "signingPublicKey": self._pub_jwk,
            "agreementPublicKey": self._pub_jwk,
            "serial": 1,
            "issuedAt": now_ms,
            "expiresAt": now_ms + 365 * 24 * 60 * 60 * 1000,
        }
        sig = sign_es256_p1363(self._priv, cert)
        return cert, sig

    def enroll_mobile_device(
        self,
        device_id: str,
        device_name: str,
        signing_pub_jwk: Dict[str, Any],
        agreement_pub_jwk: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """Issues an ES256 member certificate for a trusted LAN phone without sending any private keys."""
        if not device_id or len(device_id) < 8 or len(device_id) > 128:
            raise ValueError("设备 ID 无效")
        if not isinstance(signing_pub_jwk, dict) or signing_pub_jwk.get("kty") != "EC" or signing_pub_jwk.get("crv") != "P-256" or "d" in signing_pub_jwk:
            raise ValueError("设备公钥格式无效")
        agr = agreement_pub_jwk if (isinstance(agreement_pub_jwk, dict) and agreement_pub_jwk.get("kty") == "EC") else signing_pub_jwk
        with self._lock:
            serial = self._next_serial
            self._next_serial += 1
            self._save_identity(self._priv, self._device_id, self._workspace_id, self._next_serial)

        now_ms = int(time.time() * 1000)
        cert = {
            "version": 1,
            "workspaceId": self._workspace_id,
            "deviceId": device_id,
            "deviceName": (device_name or "手机相册")[:48],
            "role": "member",
            "signingPublicKey": {
                "kty": "EC",
                "crv": "P-256",
                "x": str(signing_pub_jwk["x"]),
                "y": str(signing_pub_jwk["y"]),
            },
            "agreementPublicKey": {
                "kty": "EC",
                "crv": "P-256",
                "x": str(agr["x"]),
                "y": str(agr["y"]),
            },
            "serial": serial,
            "issuedAt": now_ms,
            "expiresAt": now_ms + 365 * 24 * 60 * 60 * 1000,
        }
        cert_sig = sign_es256_p1363(self._priv, cert)
        # Ensure workspace exists on relay (best effort; non-blocking if proxy offline)
        try:
            self.ensure_session()
        except Exception:
            pass
        return {
            "ok": True,
            "endpoint": self.endpoint,
            "workspaceId": self._workspace_id,
            "certificate": cert,
            "certificateSignature": cert_sig,
        }

    def _http(
        self,
        path: str,
        method: str = "GET",
        json_body: Any = None,
        raw_body: Optional[bytes] = None,
        headers: Optional[Dict[str, str]] = None,
        token: Optional[str] = None,
        timeout: int = 20,
    ) -> Tuple[int, Dict[str, str], bytes]:
        hdrs: Dict[str, str] = {
            "X-Workspace-Id": self._workspace_id,
            "Accept": "application/json",
            "User-Agent": "DeviceShareHub-PC/1.2.8",
        }
        if token:
            hdrs["Authorization"] = f"Bearer {token}"
        if headers:
            hdrs.update(headers)
        body_bytes: Optional[bytes] = None
        if json_body is not None:
            hdrs["Content-Type"] = "application/json; charset=utf-8"
            body_bytes = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
        elif raw_body is not None:
            body_bytes = raw_body
            hdrs.setdefault("Content-Type", "application/octet-stream")
            hdrs["Content-Length"] = str(len(raw_body))

        req = urllib.request.Request(f"{self.endpoint}{path}", data=body_bytes, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except urllib.error.HTTPError as he:
            return he.code, dict(he.headers), he.read()

    def ensure_session(self) -> str:
        now = time.time()
        if self._token and now < self._token_expires_at - 120:
            return self._token

        cert, cert_sig = self.admin_certificate()
        if not self._registered:
            st, _, raw = self._http(
                "/v1/workspaces/register",
                method="POST",
                json_body={"certificate": cert, "certificateSignature": cert_sig},
            )
            if st in (200, 201, 409):
                self._registered = True

        st, _, ch_raw = self._http(
            "/v1/challenges",
            method="POST",
            json_body={"workspaceId": self._workspace_id, "deviceId": self._device_id},
        )
        if st not in (200, 201):
            raise RuntimeError(f"Relay challenge failed HTTP {st}: {ch_raw.decode('utf-8', 'ignore')}")
        challenge = json.loads(ch_raw.decode("utf-8"))
        ch_sig = sign_es256_p1363(self._priv, challenge)

        st, _, ses_raw = self._http(
            "/v1/sessions",
            method="POST",
            json_body={
                "certificate": cert,
                "certificateSignature": cert_sig,
                "challengeId": challenge["challengeId"],
                "challengeSignature": ch_sig,
            },
        )
        if st not in (200, 201):
            raise RuntimeError(f"Relay session failed HTTP {st}: {ses_raw.decode('utf-8', 'ignore')}")
        ses = json.loads(ses_raw.decode("utf-8"))
        self._token = ses["token"]
        self._token_expires_at = float(ses.get("expiresAt", (now + 3600) * 1000)) / 1000.0
        return self._token

    def resolve_safe_work_file(self, library_root: str, work_folder: str, filename: str) -> Optional[Path]:
        if not library_root or not filename or ".." in filename or "\0" in filename:
            return None
        root_real = os.path.realpath(library_root)
        work_dir = os.path.realpath(os.path.join(root_real, work_folder)) if work_folder else root_real
        if work_dir != root_real and not work_dir.startswith(root_real + os.sep):
            return None
        cand = os.path.realpath(os.path.join(work_dir, filename))
        if not cand.startswith(root_real + os.sep):
            return None
        p = Path(cand)
        return p if p.is_file() else None

    def sync_once(self) -> None:
        if not self.get_works_fn:
            return
        token = self.ensure_session()
        rev, works, library_root = self.get_works_fn()

        # 1. Upload catalog if scan revision changed
        if rev != self._last_synced_revision:
            compact_works = []
            for w in works:
                wid = w.get("id", "")
                imgs = w.get("images") or []
                cover = imgs[0] if imgs else ""
                compact_works.append({
                    "id": wid,
                    "name": w.get("name", ""),
                    "folderName": w.get("folderName", ""),
                    "category": w.get("category", ""),
                    "workType": w.get("workType", "image"),
                    "videoFilename": w.get("videoFilename", ""),
                    "copyText": w.get("copyText", ""),
                    "images": imgs,
                    "livePhotos": w.get("livePhotos") or [],
                    "useCount": int(w.get("useCount", 0)),
                    "updatedAt": int(w.get("updatedAt", 0)),
                    "coverThumbKey": catalog_thumb_key(wid, cover) if cover else "",
                })
            manifest = {
                "version": 1,
                "revision": rev,
                "updatedAt": int(time.time() * 1000),
                "works": compact_works,
            }
            payload = json.dumps(manifest, ensure_ascii=False).encode("utf-8")
            st, _, resp = self._http(
                "/v1/catalog",
                method="PUT",
                raw_body=payload,
                headers={
                    "Content-Type": "application/json; charset=utf-8",
                    "X-Catalog-Revision": str(rev),
                    "X-Catalog-Work-Count": str(len(compact_works)),
                },
                token=token,
                timeout=30,
            )
            if st in (200, 201):
                self._last_synced_revision = rev

        # 2. Lazily upload up to 4 missing cover thumbnails per tick (non-blocking)
        if self.get_thumb_fn:
            uploaded_this_tick = 0
            for w in works:
                if uploaded_this_tick >= 4:
                    break
                imgs = w.get("images") or []
                if not imgs:
                    continue
                wid = w.get("id", "")
                tkey = catalog_thumb_key(wid, imgs[0])
                if tkey in self._uploaded_thumbs:
                    continue
                jpg = self.get_thumb_fn(wid, imgs[0])
                if jpg:
                    st, _, _ = self._http(
                        f"/v1/catalog/thumbs/{tkey}",
                        method="PUT",
                        raw_body=jpg,
                        headers={"Content-Type": "image/jpeg"},
                        token=token,
                        timeout=15,
                    )
                    if st in (200, 201):
                        self._uploaded_thumbs.add(tkey)
                        uploaded_this_tick += 1

        # 3. Fulfill pending on-demand original HD file pulls
        st, _, p_raw = self._http("/v1/catalog/pulls/pending", method="GET", token=token)
        if st == 200:
            pulls = json.loads(p_raw.decode("utf-8")).get("pulls") or []
            work_by_id = {w.get("id"): w for w in works}
            for p in pulls:
                pull_id = p.get("pullId", "")
                wid = p.get("workId", "")
                fname = p.get("fileName") or p.get("filename") or ""
                w_item = work_by_id.get(wid)
                target: Optional[Path] = None
                if w_item:
                    rel_dir = w_item.get("relativePath") or w_item.get("folderName") or ""
                    target = self.resolve_safe_work_file(library_root, rel_dir, fname)
                if not target:
                    continue
                file_bytes = target.read_bytes()
                sha = hashlib.sha256(file_bytes).hexdigest()
                t_start = time.time()
                ust, _, _ = self._http(
                    f"/v1/catalog/pulls/{pull_id}/file",
                    method="PUT",
                    raw_body=file_bytes,
                    headers={
                        "Content-Type": "application/octet-stream",
                        "X-Object-Sha256": sha,
                    },
                    token=token,
                    timeout=60,
                )
                if ust in (200, 201):
                    self.stats_store.record(
                        "r2",
                        bytes_count=len(file_bytes),
                        objects=1,
                        duration_ms=int((time.time() - t_start) * 1000),
                    )

        # 4. Consume remote work usage events
        if self.mark_work_used_fn:
            st, _, ev_raw = self._http("/v1/catalog/events", method="GET", token=token)
            if st == 200:
                events = json.loads(ev_raw.decode("utf-8")).get("events") or []
                for ev in events:
                    if ev.get("workId"):
                        try:
                            self.mark_work_used_fn(str(ev["workId"]), str(ev.get("platform", "remote")))
                        except Exception:
                            pass

    def fetch_cloud_stats(self, max_age_sec: float = 20.0) -> Optional[Dict[str, Any]]:
        now = time.time()
        if self._cached_cloud_stats and (now - self._cached_cloud_stats_at) < max_age_sec:
            return self._cached_cloud_stats
        try:
            token = self.ensure_session()
            st, _, raw = self._http("/v1/stats", method="GET", token=token, timeout=10)
            if st == 200:
                parsed = json.loads(raw.decode("utf-8"))
                self._cached_cloud_stats = parsed.get("stats") or parsed
                self._cached_cloud_stats_at = now
                return self._cached_cloud_stats
        except Exception as exc:
            self._last_error = str(exc)
        return self._cached_cloud_stats

    def get_combined_transfer_stats(self) -> Dict[str, Any]:
        return {
            "ok": True,
            "workspaceId": self._workspace_id,
            "endpoint": self.endpoint,
            "local": self.stats_store.snapshot(),
            "cloud": self.fetch_cloud_stats(),
            "lastError": self._last_error,
        }

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, name="RemoteCatalogBridge", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()

    def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self.sync_once()
                self._last_error = ""
            except Exception as exc:
                self._last_error = str(exc)
            self._stop_event.wait(4.0)
