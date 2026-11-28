from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from online_gallery_service import WorkScanner, project_work_labels


def test_projection_prefers_explicit_fields_and_keeps_dimensions_independent():
    work = {"canonical": {
        "production": {"engineId": "trueapi-image-direct", "channelId": "Codex",
                       "account": "ignored", "generatedAt": "2026-11-27T08:00:00+08:00"},
        "classification": {"seasons": ["秋季", "冬季"], "applicableMonths": [12, "11月", 13, 11],
                           "flowType": "泛流量", "contentType": "个人攻略"},
    }}
    assert project_work_labels(work, {"generationEngine": "magpie-http"}) == {
        "generationEngine": ["True API"], "productionChannel": ["Codex"],
        "seasons": ["秋季", "冬季"], "applicableMonths": [11, 12],
        "flowType": ["泛流量"], "contentType": ["个人攻略"], "producedAt": "2026-11-27T08:00:00+08:00",
    }


def test_no_inferred_or_credential_fields_in_historical_projection():
    assert project_work_labels({"title": "冬季 Codex Magpie",
                                "pipeline": "Codex-API", "createdAt": "2026-11-27",
                                "generationEngine": "https://example.test/secret",
                                "productionChannel": "sk-fixture", "accountName": "fixture"}) == {}


def test_scanner_reads_sidecar_and_leaves_historical_work_unknown(tmp_path):
    import json
    for name in ("20261127_120000_样本", "20261127_120100_旧作品"):
        folder = tmp_path / name
        folder.mkdir()
        (folder / "P1.jpg").write_bytes(b"test")
        (folder / "文案.txt").write_text("这是隔离测试作品使用的文案验证内容。" * 6, encoding="utf-8")
    new = tmp_path / "20261127_120000_样本"
    (new / "metadata.json").write_text(json.dumps({"canonical": {
        "production": {"engineId": "magpie-http", "channelId": "Codex"},
        "classification": {"season": "冬季", "applicableMonths": [12]}}}), encoding="utf-8")
    scanner = WorkScanner(str(tmp_path))
    results = {Path(item["path"]).name: item["workLabels"] for item in scanner.scan()}
    assert results[new.name] == {"generationEngine": ["Magpie"], "productionChannel": ["Codex"],
                                 "seasons": ["冬季"], "applicableMonths": [12]}
    assert results["20261127_120100_旧作品"] == {}
