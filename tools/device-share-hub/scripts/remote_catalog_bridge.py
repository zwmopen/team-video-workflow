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
import queue
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import (
    decode_dss_signature,
    encode_dss_signature,
)

try:
    import websocket
except ImportError:
    websocket = None

DEFAULT_RELAY_ENDPOINT = os.environ.get(
    "ALBUM_RELAY_ENDPOINT",
    "https://zwm-device-share-relay.zwmrpg.workers.dev",
).rstrip("/")
SOURCE_TOKEN_PREFIX = "__src__:"
SOURCE_PREVIEW_PREFIX = "__preview__:"


def catalog_source_images(work: Dict[str, Any]) -> List[str]:
    result = []
    source_images = work.get("sourceImages") or []
    for index, image in enumerate(work.get("images") or []):
        token = SOURCE_TOKEN_PREFIX + str(image).replace("\\", "/").rsplit("/", 1)[-1]
        result.append(token if len(token) <= 248 and ".." not in token and index < len(source_images)
                      and source_images[index] == token else "")
    return result


def normalize_backup_endpoint(raw: str) -> str:
    if not raw.strip():
        return ""
    endpoint = urllib.parse.urlsplit(raw.strip().rstrip("/"))
    if (endpoint.scheme != "https" or not endpoint.hostname or endpoint.username is not None
            or endpoint.password is not None or endpoint.path or endpoint.query or endpoint.fragment
            or any(char.isspace() for char in endpoint.netloc)):
        raise ValueError("备用中继入口必须是 HTTPS 源站地址")
    endpoint.port  # Validate the port if explicitly present.
    return endpoint.geturl()


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
                loaded = json.loads(self.stats_path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    loaded.setdefault("days", {})
                    loaded.setdefault("months", {})
                    loaded.setdefault("deviceDays", {})
                    loaded.setdefault("deviceMonths", {})
                    return loaded
            except Exception:
                pass
        return {"days": {}, "months": {}, "deviceDays": {}, "deviceMonths": {}}

    def _save_locked(self) -> None:
        self.stats_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.stats_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(self.stats_path)

    def record(
        self,
        channel: str,
        bytes_count: int = 0,
        objects: int = 1,
        works: int = 0,
        success: bool = True,
        duration_ms: int = 0,
        device_id: str = "",
        device_name: str = "",
    ) -> None:
        ch = channel.lower().strip()
        if ch not in ("usb", "lan", "p2p", "r2"):
            ch = "lan"
        day_k = time.strftime("%Y-%m-%d", time.gmtime())
        month_k = time.strftime("%Y-%m", time.gmtime())
        dev_key = (device_id or device_name or "").strip()
        with self._lock:
            for scope_name, key in (("days", day_k), ("months", month_k)):
                bucket = self._data.setdefault(scope_name, {}).setdefault(key, {})
                bucket[f"{ch}Bytes"] = int(bucket.get(f"{ch}Bytes", 0)) + max(0, int(bytes_count))
                bucket[f"{ch}Ms"] = int(bucket.get(f"{ch}Ms", 0)) + max(0, int(duration_ms))
                bucket[f"{ch}Ok" if success else f"{ch}Fail"] = int(bucket.get(f"{ch}Ok" if success else f"{ch}Fail", 0)) + 1
                bucket["totalBytes"] = int(bucket.get("totalBytes", 0)) + max(0, int(bytes_count))
                bucket["totalObjects"] = int(bucket.get("totalObjects", 0)) + max(0, int(objects))
                bucket["totalWorks"] = int(bucket.get("totalWorks", 0)) + max(0, int(works))
            if dev_key:
                for scope_name, key in (("deviceDays", day_k), ("deviceMonths", month_k)):
                    dev_bucket = self._data.setdefault(scope_name, {}).setdefault(key, {}).setdefault(dev_key, {"deviceId": dev_key})
                    if device_name and device_name.strip():
                        dev_bucket["deviceName"] = device_name.strip()
                    else:
                        dev_bucket.setdefault("deviceName", dev_key)
                    dev_bucket[f"{ch}Bytes"] = int(dev_bucket.get(f"{ch}Bytes", 0)) + max(0, int(bytes_count))
                    dev_bucket["bytes"] = int(dev_bucket.get("bytes", 0)) + max(0, int(bytes_count))
                    dev_bucket["objects"] = int(dev_bucket.get("objects", 0)) + max(0, int(objects))
                    dev_bucket["lastActiveAt"] = int(time.time() * 1000)
            # Prune old daily buckets (>45)
            for daily_key in ("days", "deviceDays"):
                days = self._data.get(daily_key, {})
                if isinstance(days, dict) and len(days) > 45:
                    for old_k in sorted(days.keys())[:-45]:
                        days.pop(old_k, None)
            self._save_locked()

    def snapshot(self) -> Dict[str, Any]:
        day_k = time.strftime("%Y-%m-%d", time.gmtime())
        month_k = time.strftime("%Y-%m", time.gmtime())
        with self._lock:
            d_devs = self._data.get("deviceDays", {}).get(day_k, {}) or {}
            m_devs = self._data.get("deviceMonths", {}).get(month_k, {}) or {}
            dev_keys = sorted(set(d_devs.keys()) | set(m_devs.keys()))
            devices: List[Dict[str, Any]] = []
            for dk in dev_keys:
                d = d_devs.get(dk, {})
                m = m_devs.get(dk, {})
                devices.append({
                    "deviceId": dk,
                    "deviceName": str(m.get("deviceName") or d.get("deviceName") or dk),
                    "todayBytes": int(d.get("bytes", 0)),
                    "todayObjects": int(d.get("objects", 0)),
                    "todayR2Bytes": int(d.get("r2Bytes", 0)),
                    "todayLanBytes": int(d.get("lanBytes", 0)),
                    "monthBytes": int(m.get("bytes", 0)),
                    "monthObjects": int(m.get("objects", 0)),
                    "monthR2Bytes": int(m.get("r2Bytes", 0)),
                    "monthLanBytes": int(m.get("lanBytes", 0)),
                    "monthUsbBytes": int(m.get("usbBytes", 0)),
                    "monthP2pBytes": int(m.get("p2pBytes", 0)),
                    "lastActiveAt": int(m.get("lastActiveAt") or d.get("lastActiveAt") or 0),
                })
            devices.sort(key=lambda x: x["monthBytes"], reverse=True)
            return {
                "dayKey": day_k,
                "monthKey": month_k,
                "today": dict(self._data.get("days", {}).get(day_k, {})),
                "month": dict(self._data.get("months", {}).get(month_k, {})),
                "devices": devices,
            }


class RemoteCatalogBridge:
    def __init__(
        self,
        state_dir: Path,
        endpoint: str = DEFAULT_RELAY_ENDPOINT,
        get_works_fn: Optional[Callable[[], Tuple[int, List[Dict[str, Any]], str]]] = None,
        get_thumb_fn: Optional[Callable[[str, str], Optional[bytes]]] = None,
        mark_work_used_fn: Optional[Callable[[str, str], None]] = None,
        get_source_file_fn: Optional[Callable[[str, str], Optional[str]]] = None,
    ):
        self.state_dir = state_dir
        self.endpoint = endpoint.rstrip("/")
        self.backup_endpoint = normalize_backup_endpoint(os.environ.get("ALBUM_RELAY_BACKUP_ENDPOINT", ""))
        self.admin_path = state_dir / "remote_catalog_admin.json"
        self.stats_store = LocalTransferStatsStore(state_dir / "transfer_stats.json")
        self.get_works_fn = get_works_fn
        self.get_thumb_fn = get_thumb_fn
        self.mark_work_used_fn = mark_work_used_fn
        self.get_source_file_fn = get_source_file_fn

        self._lock = threading.Lock()
        self._priv, self._pub_jwk, self._device_id, self._workspace_id, self._next_serial = self._load_or_init_identity()
        self._session_lock = threading.Lock()
        self._registered = False
        self._token: Optional[str] = None
        self._token_expires_at: float = 0.0
        self._last_synced_revision: int = -1
        self._uploaded_thumbs: set[str] = set()
        self._thumb_retry_after: Dict[str, float] = {}
        self._cached_cloud_stats: Optional[Dict[str, Any]] = None
        self._cached_cloud_stats_at: float = 0.0
        self._last_error: str = ""
        self._stop_event = threading.Event()
        self._pull_event = threading.Event()
        self._notified_pulls = queue.Queue(maxsize=64)
        self._served_pulls: Dict[str, float] = {}
        self._thread: Optional[threading.Thread] = None
        self._pull_thread: Optional[threading.Thread] = None
        self._socket_thread: Optional[threading.Thread] = None

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
        profile = {
            "ok": True,
            "endpoint": self.endpoint,
            "workspaceId": self._workspace_id,
            "certificate": cert,
            "certificateSignature": cert_sig,
        }
        if self.backup_endpoint and self.backup_endpoint != self.endpoint:
            routes = {
                "workspaceId": self._workspace_id,
                "primaryAuthority": urllib.parse.urlsplit(self.endpoint).netloc,
                "backupAuthority": urllib.parse.urlsplit(self.backup_endpoint).netloc,
            }
            profile["relayRoutes"] = routes
            profile["relayRoutesSignature"] = sign_es256_p1363(self._priv, routes)
            profile["relayRoutesIssuerKey"] = self._pub_jwk
        return profile

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
        with self._session_lock:
            return self._ensure_session_locked()

    def _ensure_session_locked(self) -> str:
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

    def resolve_catalog_pull(self, library_root: str, work: Dict[str, Any], filename: str) -> Optional[Path]:
        if not filename or (filename not in (work.get("images") or []) and filename != work.get("videoFilename")):
            return None
        folder = work.get("path") or work.get("relativePath") or work.get("folderName")
        if not folder:
            return None
        work_dir = Path(library_root, folder).resolve()
        candidate = self.resolve_safe_work_file(library_root, "", filename)
        if candidate is not None and candidate.is_relative_to(work_dir):
            return candidate
        candidate = self.resolve_safe_work_file(library_root, folder, filename)
        return candidate if candidate is not None and candidate.is_relative_to(work_dir) else None

    def sync_once(self, *, serve_pulls: bool = True) -> None:
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
                sources = catalog_source_images(w)
                compact_works.append({
                    "id": wid,
                    "name": w.get("title") or w.get("name") or wid,
                    "category": w.get("shelf") or w.get("destination") or w.get("category") or "其他",
                    "pipelineBadge": w.get("pipelineBadge") or "",
                    "tags": w.get("tags") or [],
                    "workType": w.get("workType", "image"),
                    "videoFilename": w.get("videoFilename", ""),
                    "copyText": w.get("copyText", ""),
                    "workLabels": w.get("workLabels") or {},
                    "dispatchedVersions": w.get("dispatchedVersions") or [],
                    "dispatchedTo": w.get("dispatchedTo") or [],
                    "images": imgs,
                    "sourceImages": sources,
                    "hasSourceCompare": any(sources),
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
            st, _, _ = self._http(
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
            else:
                raise RuntimeError(f"Remote catalog upload failed (HTTP {st})")

        if serve_pulls:
            self._fulfill_catalog_pulls(token, works, library_root)

        if self.get_thumb_fn:
            self._prewarm_catalog_covers(token, works)

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

    def _prewarm_catalog_covers(self, token: str, works: List[Dict[str, Any]]) -> None:
        batch = []
        seen = set()
        now = time.monotonic()
        for work in works:
            images = work.get("images") or []
            if not images:
                continue
            work_id = work.get("id", "")
            sources = catalog_source_images(work)
            for image in (sources[0], images[0]):
                if not image:
                    continue
                key = catalog_thumb_key(work_id, image)
                if (key not in self._uploaded_thumbs and key not in seen
                        and self._thumb_retry_after.get(key, 0) <= now):
                    batch.append((work_id, image, key))
                    seen.add(key)
            if len(batch) >= 8:
                break
        if not batch:
            return

        def upload(item):
            work_id, image, key = item
            try:
                jpg = self.get_thumb_fn(work_id, image)
                if jpg:
                    status, _, _ = self._http(f"/v1/catalog/thumbs/{key}", method="PUT",
                                              raw_body=jpg, headers={"Content-Type": "image/jpeg"},
                                              token=token, timeout=15)
                    return key, status in (200, 201)
            except (OSError, ValueError):
                pass
            return key, False

        with ThreadPoolExecutor(max_workers=4) as pool:
            for key, success in pool.map(upload, batch):
                if success:
                    self._uploaded_thumbs.add(key)
                    self._thumb_retry_after.pop(key, None)
                else:
                    self._thumb_retry_after[key] = now + 60

    def _fulfill_catalog_pulls(self, token: str, works: List[Dict[str, Any]], library_root: str) -> None:
        st, _, p_raw = self._http("/v1/catalog/pulls/pending", method="GET", token=token)
        if st == 200:
            pulls = json.loads(p_raw.decode("utf-8")).get("pulls") or []
            self._serve_catalog_pulls(token, works, library_root, pulls)

    def _serve_catalog_pulls(self, token: str, works: List[Dict[str, Any]],
                             library_root: str, pulls: List[Dict[str, Any]]) -> None:
        work_by_id = {work.get("id"): work for work in works}
        now = time.monotonic()
        self._served_pulls = {key: expiry for key, expiry in self._served_pulls.items() if expiry > now}
        for p in pulls:
            try:
                pull_id = p.get("pullId", "")
                if not isinstance(pull_id, str) or not pull_id or not all(
                        char.isascii() and (char.isalnum() or char in "_-") for char in pull_id):
                    continue
                if pull_id in self._served_pulls:
                    continue
                wid = p.get("workId", "")
                fname = p.get("fileName") or p.get("filename") or ""
                w_item = work_by_id.get(wid)
                preview = fname.startswith(SOURCE_PREVIEW_PREFIX)
                selector = fname[len(SOURCE_PREVIEW_PREFIX):] if preview else fname
                if w_item and selector.startswith(SOURCE_TOKEN_PREFIX):
                    if selector not in catalog_source_images(w_item):
                        continue
                    if preview and self.get_thumb_fn:
                        file_bytes = self.get_thumb_fn(wid, selector)
                    elif not preview and self.get_source_file_fn:
                        source = self.get_source_file_fn(wid, selector)
                        target = Path(source) if source else None
                        file_bytes = target.read_bytes() if target and target.is_file() else None
                    else:
                        file_bytes = None
                elif w_item and not preview:
                    target = self.resolve_catalog_pull(library_root, w_item, fname)
                    file_bytes = target.read_bytes() if target else None
                else:
                    file_bytes = None
                if not file_bytes:
                    continue
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
                    self._served_pulls[pull_id] = time.monotonic() + 90
                    self.stats_store.record(
                        "r2",
                        bytes_count=len(file_bytes),
                        objects=1,
                        duration_ms=int((time.time() - t_start) * 1000),
                    )
            except (OSError, ValueError):
                continue

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
        local_snap = self.stats_store.snapshot()
        cloud = self.fetch_cloud_stats()
        merged_by_id: Dict[str, Dict[str, Any]] = {}
        for row in local_snap.get("devices") or []:
            did = str(row.get("deviceId") or "")
            if did:
                merged_by_id[did] = dict(row)
        for row in (cloud or {}).get("devices") or []:
            if not isinstance(row, dict):
                continue
            did = str(row.get("deviceId") or "")
            if not did:
                continue
            cur = merged_by_id.get(did)
            if cur is None:
                merged_by_id[did] = dict(row)
            else:
                cur["deviceName"] = str(row.get("deviceName") or cur.get("deviceName") or did)
                cur["todayR2Bytes"] = max(int(cur.get("todayR2Bytes", 0)), int(row.get("todayR2Bytes", 0)))
                cur["monthR2Bytes"] = max(int(cur.get("monthR2Bytes", 0)), int(row.get("monthR2Bytes", 0)))
                cur["todayBytes"] = max(int(cur.get("todayBytes", 0)), int(row.get("todayBytes", 0)))
                cur["monthBytes"] = max(int(cur.get("monthBytes", 0)), int(row.get("monthBytes", 0)))
                cur["todayObjects"] = max(int(cur.get("todayObjects", 0)), int(row.get("todayObjects", 0)))
                cur["monthObjects"] = max(int(cur.get("monthObjects", 0)), int(row.get("monthObjects", 0)))
                cur["lastActiveAt"] = max(int(cur.get("lastActiveAt", 0)), int(row.get("lastActiveAt", 0)))
        devices = sorted(merged_by_id.values(), key=lambda x: int(x.get("monthBytes", 0)), reverse=True)
        r2_info = (cloud or {}).get("r2") if isinstance(cloud, dict) else {}
        warnings = list((r2_info or {}).get("warnings") or [])
        quota_alert_80 = bool((r2_info or {}).get("quotaAlert80") or warnings)
        return {
            "ok": True,
            "workspaceId": self._workspace_id,
            "endpoint": self.endpoint,
            "local": local_snap,
            "cloud": cloud,
            "devices": devices,
            "warnings": warnings,
            "quotaAlert80": quota_alert_80,
            "lastError": self._last_error,
        }

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, name="RemoteCatalogBridge", daemon=True)
        self._pull_thread = threading.Thread(target=self._run_pulls, name="RemoteCatalogPulls", daemon=True)
        self._thread.start()
        self._pull_thread.start()
        if websocket is not None:
            self._socket_thread = threading.Thread(target=self._listen_for_pulls,
                                                   name="RemoteCatalogEvents", daemon=True)
            self._socket_thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        self._pull_event.set()

    def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self.sync_once(serve_pulls=False)
                self._last_error = ""
            except Exception as exc:
                self._last_error = str(exc)
            self._stop_event.wait(4.0)

    def _run_pulls(self) -> None:
        next_poll = 0.0
        while not self._stop_event.is_set():
            self._pull_event.clear()
            pending = []
            while True:
                try:
                    pending.append(self._notified_pulls.get_nowait())
                except queue.Empty:
                    break
            try:
                token = self.ensure_session()
                _, works, root = self.get_works_fn()
                if pending:
                    self._serve_catalog_pulls(token, works, root, pending)
                if time.monotonic() >= next_poll:
                    next_poll = time.monotonic() + 3.0
                    self._fulfill_catalog_pulls(token, works, root)
            except Exception:
                pass  # The fallback poll picks up requests left pending during a disconnect.
            self._pull_event.wait(max(0.1, min(3.0, next_poll - time.monotonic())) if next_poll else 3.0)

    def _listen_for_pulls(self) -> None:
        while not self._stop_event.is_set():
            connection = None
            try:
                token = self.ensure_session()
                url = "wss://" + self.endpoint.removeprefix("https://") + "/v1/socket"
                connection = websocket.create_connection(
                    url, timeout=8, redirect_limit=0,
                    header={"Authorization": "Bearer " + token, "X-Workspace-Id": self.workspace_id},
                )
                self._pull_event.set()
                expires_at = self._token_expires_at - 120
                while not self._stop_event.is_set() and time.time() < expires_at:
                    try:
                        raw = connection.recv()
                    except websocket.WebSocketTimeoutException:
                        connection.ping()
                        continue
                    if not raw:
                        break
                    event = json.loads(raw)
                    pull = event.get("pull")
                    if event.get("type") == "catalog-pull-requested" and isinstance(pull, dict):
                        try:
                            self._notified_pulls.put_nowait(pull)
                        except queue.Full:
                            pass  # The HTTPS poll will recover outstanding requests.
                        self._pull_event.set()
            except Exception:
                pass  # Socket availability is independent of the HTTPS fallback.
            finally:
                if connection is not None:
                    connection.close(timeout=0)
            self._stop_event.wait(3.0)
