import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from remote_catalog_bridge import (
    LocalTransferStatsStore,
    RemoteCatalogBridge,
    verify_es256_p1363,
)
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
from remote_catalog_bridge import jwk_from_public_key


def test_mobile_enrollment_issues_valid_es256_certificate(tmp_path: Path):
    bridge = RemoteCatalogBridge(state_dir=tmp_path)
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
