import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from remote_catalog_bridge import (
    catalog_thumb_key,
    LocalTransferStatsStore,
    RemoteCatalogBridge,
    verify_es256_p1363,
)
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
from remote_catalog_bridge import jwk_from_public_key


def test_mobile_enrollment_issues_valid_es256_certificate(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("ALBUM_RELAY_BACKUP_ENDPOINT", "https://backup.example/")
    bridge = RemoteCatalogBridge(state_dir=tmp_path)
    monkeypatch.setattr(bridge, "ensure_session", lambda: "local-test")
    mobile_priv = ec.generate_private_key(ec.SECP256R1())
    mobile_jwk = jwk_from_public_key(mobile_priv.public_key())

    enrolled = bridge.enroll_mobile_device("phone_test_001", "小米15", mobile_jwk, mobile_jwk)
    assert enrolled["ok"] is True
    assert enrolled["workspaceId"] == bridge.workspace_id
    cert = enrolled["certificate"]
    assert cert["role"] == "member"
    assert cert["deviceId"] == "phone_test_001"
    assert "d" not in cert["signingPublicKey"]

    admin_cert, _ = bridge.admin_certificate()
    assert verify_es256_p1363(admin_cert["signingPublicKey"], cert, enrolled["certificateSignature"])
    routes = enrolled["relayRoutes"]
    assert routes["backupAuthority"] == "backup.example"
    assert routes["workspaceId"] == bridge.workspace_id
    assert verify_es256_p1363(enrolled["relayRoutesIssuerKey"], routes, enrolled["relayRoutesSignature"])
    assert not verify_es256_p1363(enrolled["relayRoutesIssuerKey"], dict(routes, backupAuthority="modified.example"),
                                  enrolled["relayRoutesSignature"])


def test_resolve_safe_work_file_rejects_traversal(tmp_path: Path):
    lib = tmp_path / "library"
    work_dir = lib / "A手动制作" / "work_01"
    work_dir.mkdir(parents=True)
    img = work_dir / "P1-封面.jpg"
    img.write_bytes(b"original-hd-bytes")
    secret = tmp_path / "secret.txt"
    secret.write_text("nope", encoding="utf-8")

    bridge = RemoteCatalogBridge(state_dir=tmp_path / "state")
    assert bridge.resolve_safe_work_file(str(lib), "A手动制作/work_01", "P1-封面.jpg") == img.resolve()
    assert bridge.resolve_safe_work_file(str(lib), "A手动制作/work_01", "../../secret.txt") is None
    assert bridge.resolve_safe_work_file(str(lib), "../../", "secret.txt") is None


def test_local_transfer_stats_store_records_channels(tmp_path: Path):
    store = LocalTransferStatsStore(tmp_path / "stats.json")
    store.record("lan", bytes_count=1024 * 1024, objects=2, works=1, duration_ms=500)
    store.record("r2", bytes_count=2048 * 1024, objects=3, works=1, duration_ms=1200)
    snap = store.snapshot()
    assert snap["today"]["lanBytes"] == 1024 * 1024
    assert snap["today"]["r2Bytes"] == 2048 * 1024
    assert snap["month"]["totalObjects"] == 5
    assert snap["month"]["totalWorks"] == 2


def test_catalog_publishes_page_aligned_sources_and_serves_only_advertised_selectors(tmp_path, monkeypatch):
    source = tmp_path / "original-outside-library.jpg"
    source.write_bytes(b"original source bytes")
    work = {"id": "work_a", "images": ["P1.jpg", "P2.jpg", "P3.jpg"],
            "sourceImages": ["__src__:P1.jpg", "", str(source)]}
    bridge = RemoteCatalogBridge(
        state_dir=tmp_path / "state",
        get_works_fn=lambda: (1, [work], str(tmp_path / "library")),
        get_source_file_fn=lambda work_id, token: str(source),
        get_thumb_fn=lambda work_id, token: {
            "__src__:P1.jpg": b"source preview", "P1.jpg": b"output preview",
        }.get(token),
    )
    monkeypatch.setattr(bridge, "ensure_session", lambda: "offline-test")
    requests = []

    def http(path, method="GET", raw_body=None, headers=None, **kwargs):
        requests.append((path, raw_body, headers))
        if path.endswith("/pending"):
            selectors = {"original": "__src__:P1.jpg", "preview": "__preview__:__src__:P1.jpg",
                         "missing": "__src__:P2.jpg", "unlisted": "__src__:P3.jpg"}
            pulls = [{"pullId": pull_id, "workId": "work_a", "fileName": name}
                     for pull_id, name in selectors.items()]
            return 200, {}, json.dumps({"pulls": pulls}).encode()
        return 200, {}, b"{}"

    monkeypatch.setattr(bridge, "_http", http)
    bridge.sync_once()
    manifest = json.loads(requests[0][1])
    assert manifest["works"][0]["sourceImages"] == ["__src__:P1.jpg", "", ""]
    assert manifest["works"][0]["hasSourceCompare"] is True
    assert str(source) not in str(manifest)
    sent = {path.split("/")[-2]: (data, headers["X-Object-Sha256"])
            for path, data, headers in requests if path.endswith("/file")}
    assert set(sent) == {"original", "preview"}
    for name, data in {"original": source.read_bytes(), "preview": b"source preview"}.items():
        assert sent[name] == (data, hashlib.sha256(data).hexdigest())
    cached = {path.rsplit("/", 1)[-1]: data for path, data, _ in requests if "/thumbs/" in path}
    assert cached == {catalog_thumb_key("work_a", "__src__:P1.jpg"): b"source preview",
                      catalog_thumb_key("work_a", "P1.jpg"): b"output preview"}
    bridge._serve_catalog_pulls("offline-test", [work], str(tmp_path / "library"),
                                [{"pullId": "original", "workId": "work_a", "fileName": "__src__:P1.jpg"}])
    assert sum(path.endswith("/original/file") for path, _, _ in requests) == 1


def test_direct_original_reader_accepts_only_the_registered_image(tmp_path):
    import pytest
    pytest.importorskip("aiortc")
    from catalog_direct import CatalogDirect

    folder = tmp_path / "works" / "work-one"
    folder.mkdir(parents=True)
    original = folder / "P1.jpg"
    original.write_bytes(b"untouched output bytes")
    (folder / "private.txt").write_bytes(b"unlisted")
    source = tmp_path / "source.jpg"
    source.write_bytes(b"untouched source bytes")
    work = {"id": "work-one", "path": str(folder), "images": ["P1.jpg"],
            "sourceImages": ["__src__:P1.jpg"]}
    bridge = RemoteCatalogBridge(
        state_dir=tmp_path / "state",
        get_works_fn=lambda: (1, [work], str(folder.parent)),
        get_source_file_fn=lambda *args: str(source),
    )
    direct = CatalogDirect.__new__(CatalogDirect)
    direct.bridge = bridge
    assert direct._read_original({"workId": "work-one", "fileName": "P1.jpg"}) == original.read_bytes()
    assert direct._read_original({"workId": "work-one", "fileName": "__src__:P1.jpg"}) == source.read_bytes()
    assert direct._read_original({"workId": "work-one", "fileName": "private.txt"}) is None
    assert direct._read_original({"workId": "work-one", "fileName": "__src__:unknown.jpg"}) is None
