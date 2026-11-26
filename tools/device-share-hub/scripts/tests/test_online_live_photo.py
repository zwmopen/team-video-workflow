"""Regression coverage for cross-platform Live Photo / Motion Photo support."""
import base64
import io
import json
import zipfile
from pathlib import Path
import sys
import threading
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from PIL import Image
import pytest

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
HUB_ROOT = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

import motion_photo
from online_gallery_service import OnlineGalleryHandler, ThreadingHTTPServer, WorkScanner


def _make_jpeg_bytes(color=(180, 120, 90)) -> bytes:
    buf = io.BytesIO()
    img = Image.new("RGB", (64, 64), color)
    img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()


def _make_fake_mp4_bytes(tag: bytes = b"TESTMP4DATA") -> bytes:
    ftyp = b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isomiso2"
    moov = b"\x00\x00\x00\x10moov" + b"mdat" + tag * 8
    return ftyp + moov


def test_motion_photo_pack_and_detect(tmp_path):
    jpg_bytes = _make_jpeg_bytes()
    mp4_bytes = _make_fake_mp4_bytes()

    assert motion_photo.is_motion_photo_jpeg(jpg_bytes) is False

    merged = motion_photo.build_motion_photo_bytes(jpg_bytes, mp4_bytes)
    assert merged.startswith(b"\xFF\xD8")
    assert merged.endswith(mp4_bytes)
    assert motion_photo.is_motion_photo_jpeg(merged) is True
    assert b'GCamera:MotionPhoto="1"' in merged[:8192]
    assert f'GCamera:MicroVideoOffset="{len(mp4_bytes)}"'.encode("ascii") in merged[:8192]
    assert b'Container:Directory' in merged[:8192]

    # Pillow can still decode the merged Motion Photo cleanly
    with Image.open(io.BytesIO(merged)) as decoded:
        assert decoded.size == (64, 64)

    # Writing to disk works and is O(1) detected
    out_file = tmp_path / "sample_motion.jpg"
    out_file.write_bytes(merged)
    assert motion_photo.is_motion_photo_jpeg(out_file) is True

    # Double packing is idempotent
    assert motion_photo.build_motion_photo_bytes(merged, mp4_bytes) == merged


def test_online_live_photo_scanner_and_routes(tmp_path):
    shelf = tmp_path / "主题合集成品"
    live_dir = shelf / "实况作品"
    embedded_dir = shelf / "内嵌实况"
    video_dir = shelf / "视频作品"
    for d in (live_dir, embedded_dir, video_dir):
        d.mkdir(parents=True)

    raw_jpg = _make_jpeg_bytes((20, 140, 200))
    mp4_payload = _make_fake_mp4_bytes(b"LIVE_MOV_BYTES")
    (live_dir / "P1.jpg").write_bytes(raw_jpg)
    (live_dir / "P1.mp4").write_bytes(mp4_payload)
    (live_dir / "P2.jpg").write_bytes(_make_jpeg_bytes((210, 60, 60)))
    (live_dir / "文案.txt").write_text("这是一条用于测试跨端实况图分发链路的完整合法测试文案，包含足够字数通过守卫校验。", encoding="utf-8")

    embedded_jpg = motion_photo.build_motion_photo_bytes(raw_jpg, mp4_payload)
    (embedded_dir / "cover.jpg").write_bytes(embedded_jpg)
    (embedded_dir / "文案.txt").write_text("这是一条测试已封装 MotionPhoto 识别链路的完整测试文案，字数满足最小实质要求。", encoding="utf-8")

    (video_dir / "01_封面.jpg").write_bytes(raw_jpg)
    (video_dir / "01_封面.mp4").write_bytes(mp4_payload)
    (video_dir / "manifest.json").write_text(json.dumps({"workType": "video", "videoFilename": "01_封面.mp4"}), encoding="utf-8")

    class Handler(OnlineGalleryHandler):
        scanner = WorkScanner(str(tmp_path))

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{server.server_port}"

        try:
            works = {Path(w["path"]).name: w for w in Handler.scanner.scan(force=True)}

            live_work = works["实况作品"]
            assert live_work["livePhotos"] == {f"{live_work['images'][0]}": "P1.mp4"}
            assert live_work["hasLivePhotos"] is True
            assert live_work["liveCount"] == 1
            assert live_work["imageCountText"] == "2图(1实况)"

            embedded_work = works["内嵌实况"]
            assert embedded_work["livePhotos"] == {f"{embedded_work['images'][0]}": "__embedded__"}
            assert embedded_work["imageCountText"] == "1图(1实况)"

            # Regular whole-work videoFilename must NOT be treated as a per-image live companion
            vid_work = works["视频作品"]
            assert vid_work["livePhotos"] == {}
            assert vid_work["hasLivePhotos"] is False

            # /api/online/status advertises version 1.2.6 and live/autoLive/studio capabilities
            with urlopen(f"{base}/api/online/status", timeout=5) as resp:
                status_json = json.loads(resp.read().decode("utf-8"))
                assert status_json["version"] == "1.2.6"
                assert "livePhoto" in status_json["features"]
                assert "autoLiveOnShare" in status_json["features"]
                assert "liveStudio" in status_json["features"]

            # Thumbnail never injects MP4
            thumb_q = urlencode({"id": live_work["id"], "file": "P1.jpg", "thumb": "1"})
            with urlopen(f"{base}/api/online/image?{thumb_q}", timeout=5) as resp:
                thumb_data = resp.read()
                assert resp.headers.get("X-Live-Format") is None
                assert motion_photo.is_motion_photo_jpeg(thumb_data) is False

            # Android / ?live=motion receives standard Motion Photo JPEG
            motion_q = urlencode({"id": live_work["id"], "file": "P1.jpg", "live": "motion"})
            with urlopen(f"{base}/api/online/image?{motion_q}", timeout=5) as resp:
                motion_data = resp.read()
                assert resp.headers.get("X-Live-Format") == "motion-photo"
                assert motion_data.endswith(mp4_payload)
                assert motion_photo.is_motion_photo_jpeg(motion_data) is True

            # Android User-Agent auto-negotiates Motion Photo even without ?live=motion
            auto_q = urlencode({"id": live_work["id"], "file": "P1.jpg"})
            req_android = Request(f"{base}/api/online/image?{auto_q}", headers={"User-Agent": "Dalvik/2.1.0 (Linux; Android 14)"})
            with urlopen(req_android, timeout=5) as resp:
                assert resp.headers.get("X-Live-Format") == "motion-photo"
                assert motion_photo.is_motion_photo_jpeg(resp.read()) is True

            # iOS / ?live=raw receives untouched raw JPG bytes
            raw_q = urlencode({"id": live_work["id"], "file": "P1.jpg", "live": "raw"})
            with urlopen(f"{base}/api/online/image?{raw_q}", timeout=5) as resp:
                assert resp.headers.get("X-Live-Format") is None
                assert resp.read() == raw_jpg

            # /api/online/live-video serves companion video stream and blocks traversal / non-live works
            lv_q = urlencode({"id": live_work["id"], "file": "P1.jpg"})
            with urlopen(f"{base}/api/online/live-video?{lv_q}", timeout=5) as resp:
                assert resp.headers.get_content_type() == "video/mp4"
                assert int(resp.headers["Content-Length"]) == len(mp4_payload)
                assert resp.read() == mp4_payload

            for bad_q in (
                urlencode({"id": live_work["id"], "file": "P2.jpg"}),
                urlencode({"id": live_work["id"], "file": "../P1.jpg"}),
                urlencode({"id": embedded_work["id"], "file": "cover.jpg"}),
                urlencode({"id": "unknown-work", "file": "P1.jpg"}),
            ):
                with pytest.raises(HTTPError) as exc:
                    urlopen(f"{base}/api/online/live-video?{bad_q}", timeout=5)
                assert exc.value.code == 404
        finally:
            server.shutdown()


def test_mobile_clients_live_photo_contracts():
    and_entry = (HUB_ROOT / "android/app/src/main/java/com/zwm/gallery/OnlineWorkEntry.java").read_text(encoding="utf-8")
    and_client = (HUB_ROOT / "android/app/src/main/java/com/zwm/gallery/OnlineGalleryClient.java").read_text(encoding="utf-8")
    and_main = (HUB_ROOT / "android/app/src/main/java/com/zwm/gallery/MainActivity.java").read_text(encoding="utf-8")
    assert "livePhotos" in and_entry
    assert "&live=motion" in and_client
    assert ".live.jpg" in and_client
    assert "work.livePhotos" in and_main

    ios_yml = (HUB_ROOT / "ios/project.yml").read_text(encoding="utf-8")
    ios_client = (HUB_ROOT / "ios/Album/OnlineGalleryClient.swift").read_text(encoding="utf-8")
    ios_cv = (HUB_ROOT / "ios/Album/ContentView.swift").read_text(encoding="utf-8")
    assert "NSPhotoLibraryAddUsageDescription" in ios_yml
    assert "downloadWorkLiveAssets" in ios_client
    assert "/api/online/live-video" in ios_client
    assert "PHAssetCreationRequest" in ios_cv
    assert "@available(iOS 14" in ios_cv or "#available(iOS 14" in ios_cv

    and_svc = (HUB_ROOT / "android/app/src/main/java/com/zwm/gallery/OnlineService.java").read_text(encoding="utf-8")
    and_set = (HUB_ROOT / "android/app/src/main/java/com/zwm/gallery/SettingsActivity.java").read_text(encoding="utf-8")
    assert "isAutoLiveOnShareEnabled" in and_svc
    assert "分享时静图自动转实况" in and_set
    assert "&auto_live=1" in and_client
    assert "isAutoLiveOnShareEnabled" in and_main

    ios_pref = (HUB_ROOT / "ios/Album/AutoLivePreferences.swift").read_text(encoding="utf-8")
    ios_set = (HUB_ROOT / "ios/Album/SettingsView.swift").read_text(encoding="utf-8")
    assert "album.autoLiveOnShareEnabled.v1" in ios_pref
    assert "分享时静图自动转实况" in ios_set
    assert "auto_live" in ios_client
    assert "AutoLivePreferences.isEnabled" in ios_cv


def test_seven_motion_presets_ffmpeg_studio_and_auto_live(tmp_path, monkeypatch):
    cfg_path = tmp_path / "live_motion_config.json"
    monkeypatch.setattr(motion_photo, "DEFAULT_LIVE_CONFIG_FILE", str(cfg_path))

    # All 7 modes produce valid zoompan filters and clamp ratio/duration safely
    assert len(motion_photo.MOTION_PRESETS) == 7
    for preset in motion_photo.MOTION_PRESETS:
        flt = motion_photo._build_zoompan_filter(preset["id"], 1.06, 45, 320, 426, 30)
        assert "zoompan=" in flt
        assert "320x426" in flt

    norm = motion_photo.normalize_live_config({"mode": "unknown", "ratio": 9.9, "duration": 0.2, "fps": 99})
    assert norm == {"mode": "zoom_in", "ratio": 1.15, "duration": 1.5, "fps": 30}

    assert motion_photo.find_ffmpeg() is not None, "ffmpeg must be available for local motion synthesis"

    src_img = tmp_path / "sample.jpg"
    src_img.write_bytes(_make_jpeg_bytes((40, 110, 180)))
    cache_dir = tmp_path / "cache"

    res1 = motion_photo.synthesize_live_pair(
        src_img,
        config={"mode": "handheld", "ratio": 1.06, "duration": 1.5, "fps": 24},
        cache_dir=str(cache_dir),
    )
    assert res1["cached"] is False
    assert Path(res1["apple_jpg"]).is_file()
    assert Path(res1["apple_mov"]).is_file()
    assert motion_photo.is_motion_photo_jpeg(res1["motion_jpg"]) is True
    with Image.open(res1["apple_jpg"]) as im:
        assert im.size[0] >= 320

    # Cache hit on identical config
    res2 = motion_photo.synthesize_live_pair(
        src_img,
        config={"mode": "handheld", "ratio": 1.06, "duration": 1.5, "fps": 24},
        cache_dir=str(cache_dir),
    )
    assert res2["cached"] is True

    # Test /live-studio UI, /api/online/live-config, /api/online/live-process, and ?auto_live=1
    shelf = tmp_path / "lib" / "主题合集成品" / "纯静图测试作品"
    shelf.mkdir(parents=True)
    (shelf / "static_cover.jpg").write_bytes(_make_jpeg_bytes((150, 90, 60)))
    (shelf / "文案.txt").write_text("纯静图开启开关后由电脑本地实时现烤微动实况测试文案，满足字数检查。", encoding="utf-8")

    class Handler(OnlineGalleryHandler):
        scanner = WorkScanner(str(tmp_path / "lib"))

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            works = {Path(w["path"]).name: w for w in Handler.scanner.scan(force=True)}
            static_w = works["纯静图测试作品"]
            assert static_w["livePhotos"] == {}

            # /live-studio HTML page
            with urlopen(f"{base}/live-studio", timeout=5) as resp:
                html = resp.read().decode("utf-8")
                assert "实况图加工工坊" in html

            # POST /api/online/live-config persists shared style
            cfg_req = Request(
                f"{base}/api/online/live-config",
                data=json.dumps({"mode": "pan_up", "ratio": 1.07, "duration": 1.5}).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urlopen(cfg_req, timeout=5) as resp:
                cfg_res = json.loads(resp.read().decode("utf-8"))
                assert cfg_res["ok"] is True
                assert cfg_res["config"]["mode"] == "pan_up"
                assert cfg_res["config"]["ratio"] == 1.07

            # POST /api/online/live-process processes uploaded image and exports ZIP
            b64_img = base64.b64encode(_make_jpeg_bytes((90, 160, 120))).decode("ascii")
            proc_req = Request(
                f"{base}/api/online/live-process",
                data=json.dumps({
                    "mode": "zoom_in",
                    "ratio": 1.05,
                    "duration": 1.5,
                    "saveToDisk": True,
                    "images": [{"name": "test_view.jpg", "dataBase64": b64_img}],
                }).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urlopen(proc_req, timeout=60) as resp:
                proc_res = json.loads(resp.read().decode("utf-8"))
                assert proc_res["ok"] is True
                assert len(proc_res["items"]) == 1
                item = proc_res["items"][0]
                job_id = proc_res["jobId"]
                assert Path(proc_res["exportDir"], item["motionJpgName"]).is_file()

            with urlopen(f"{base}/api/online/live-export?job={job_id}", timeout=10) as resp:
                zdata = resp.read()
                with zipfile.ZipFile(io.BytesIO(zdata), "r") as zf:
                    names = set(zf.namelist())
                    assert {item["motionJpgName"], item["appleJpgName"], item["appleMovName"]}.issubset(names)

            # Android ?auto_live=1 synthesizes Motion Photo on the fly
            q_and = urlencode({"id": static_w["id"], "file": "static_cover.jpg", "live": "motion", "auto_live": "1"})
            with urlopen(f"{base}/api/online/image?{q_and}", timeout=60) as resp:
                assert resp.headers.get("X-Live-Format") == "motion-photo"
                assert resp.headers.get("X-Live-Synthesized") == "1"
                assert motion_photo.is_motion_photo_jpeg(resp.read()) is True

            # iOS ?live=raw&auto_live=1 + /api/online/live-video?auto_live=1
            q_ios_img = urlencode({"id": static_w["id"], "file": "static_cover.jpg", "live": "raw", "auto_live": "1"})
            with urlopen(f"{base}/api/online/image?{q_ios_img}", timeout=60) as resp:
                assert resp.headers.get("X-Live-Format") is None
                assert resp.headers.get("X-Live-Synthesized") == "1"

            q_ios_vid = urlencode({"id": static_w["id"], "file": "static_cover.jpg", "auto_live": "1"})
            with urlopen(f"{base}/api/online/live-video?{q_ios_vid}", timeout=60) as resp:
                assert resp.headers.get_content_type() == "video/quicktime"
                assert resp.headers.get("X-Live-Synthesized") == "1"
                assert len(resp.read()) > 1000

            with pytest.raises(HTTPError) as exc:
                q_bad = urlencode({"id": static_w["id"], "file": "../static_cover.jpg", "auto_live": "1"})
                urlopen(f"{base}/api/online/live-video?{q_bad}", timeout=5)
            assert exc.value.code == 404
        finally:
            server.shutdown()
