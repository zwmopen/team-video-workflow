"""Regression coverage for the online work video route."""
import json
from pathlib import Path
import sys
import threading
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from online_gallery_service import OnlineGalleryHandler, ThreadingHTTPServer, WorkScanner


def test_online_video_keeps_images_and_serves_only_registered_mp4(tmp_path):
    filename = "团建原片.mp4"
    payload = b"video payload for transport verification"
    shelf = tmp_path / "主题合集成品"
    for folder, manifest in (
        ("视频作品", {"workType": "video", "videoFilename": filename}),
        ("普通图文", {}),
        ("越界作品", {"workType": "video", "videoFilename": "../" + filename}),
    ):
        work_dir = shelf / folder
        work_dir.mkdir(parents=True)
        (work_dir / "01_封面.png").write_bytes(b"preview image placeholder")
        (work_dir / filename).write_bytes(payload)
        (work_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    (shelf / filename).write_bytes(payload)

    class Handler(OnlineGalleryHandler):
        scanner = WorkScanner(str(tmp_path))

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()

        def fetch(work_id):
            query = urlencode({"id": work_id})
            return urlopen(f"http://127.0.0.1:{server.server_port}/api/online/video?{query}", timeout=5)

        try:
            works = {Path(work["path"]).name: work for work in Handler.scanner.scan(force=True)}
            video = works["视频作品"]
            assert [Path(image).name for image in video["images"]] == ["01_封面.png"]
            assert video["videoFilename"] == filename
            with fetch(video["id"]) as response:
                assert response.headers.get_content_type() == "video/mp4"
                assert int(response.headers["Content-Length"]) == len(payload)
                assert response.read() == payload

            for work_id in (works["普通图文"]["id"], works["越界作品"]["id"], "未知作品"):
                with pytest.raises(HTTPError) as error:
                    fetch(work_id)
                assert error.value.code == 404
        finally:
            server.shutdown()


def test_local_update_manifest_is_available_before_github_refresh(monkeypatch):
    import online_gallery_service as gallery

    local = {
        "version_code": 243,
        "apk_url": "/download/apk",
        "sha256": "local-sha",
        "local_apk_path": "private.apk",
    }
    starts = []
    monkeypatch.setattr(gallery, "_read_local_update_manifest", lambda: local)
    monkeypatch.setattr(gallery, "_UPDATE_MANIFEST_CACHE", {"at": 0.0, "data": None, "refreshStartedAt": 0.0})
    monkeypatch.setattr(gallery, "get_local_ip", lambda: "127.0.0.1")
    monkeypatch.setattr(gallery, "_update_fetch", lambda *args: pytest.fail("GitHub must not block the request"))

    class DeferredRefresh:
        def __init__(self, *, target, kwargs, daemon):
            assert target is gallery.fetch_update_manifest
            assert kwargs == {"force": True}
            assert daemon

        def start(self):
            starts.append(True)

    monkeypatch.setattr(gallery.threading, "Thread", DeferredRefresh)
    for _ in range(2):
        result = gallery.fetch_update_manifest(prefer_local=True)
        assert result["version_code"] == 243
        assert result["sha256"] == "local-sha"
        assert "local_apk_path" not in result
    assert starts == [True]

    gallery._UPDATE_MANIFEST_CACHE["data"] = {"version_code": 244, "apk_url": "/download/apk"}
    assert gallery.fetch_update_manifest(prefer_local=True)["version_code"] == 244
