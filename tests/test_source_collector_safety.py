from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "tools" / "teambuilding-video-scene-library" / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from tb_scene import source_collector


class SourceCollectorSafetyTests(unittest.TestCase):
    def test_preview_is_default_and_does_not_modify_filesystem(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "downloads"
            output = root / "library"
            source.mkdir()
            video = source / "千岛湖公司团建.mp4"
            sidecar = source / "千岛湖公司团建.txt"
            video.write_bytes(b"video")
            sidecar.write_text("copy", encoding="utf-8")

            summary = source_collector.collect_location_sources(
                source_root=source,
                output_root=output,
                location="千岛湖",
            )

            self.assertEqual(summary["mode"], "preview")
            self.assertEqual(summary["would_select"], 1)
            self.assertTrue(video.exists())
            self.assertTrue(sidecar.exists())
            self.assertFalse(output.exists())

    def test_duplicate_source_is_quarantined_instead_of_deleted(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "downloads"
            output = root / "library"
            destination = output / "01_原片素材库" / "千岛湖-原视频素材"
            source.mkdir()
            destination.mkdir(parents=True)

            existing = destination / "已有千岛湖.mp4"
            existing.write_bytes(b"same-video")
            video = source / "千岛湖重复素材.mp4"
            sidecar = source / "千岛湖重复素材.txt"
            video.write_bytes(b"same-video")
            sidecar.write_text("sidecar", encoding="utf-8")

            summary = source_collector.collect_location_sources(
                source_root=source,
                output_root=output,
                location="千岛湖",
                move_files=True,
            )

            self.assertEqual(summary["quarantined_source_duplicates"], 1)
            self.assertFalse(video.exists())
            self.assertFalse(sidecar.exists())
            quarantine = (
                output
                / "90_待整理与记录"
                / "._采集记录"
                / "千岛湖_非单地点或重复原视频"
                / "重复源视频"
            )
            quarantined_video = quarantine / video.name
            quarantined_sidecar = quarantine / sidecar.name
            self.assertTrue(quarantined_video.exists())
            self.assertTrue(quarantined_sidecar.exists())
            self.assertEqual(quarantined_video.read_bytes(), b"same-video")
            self.assertEqual(quarantined_sidecar.read_text(encoding="utf-8"), "sidecar")

    def test_bundle_move_rolls_back_when_sidecar_move_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source"
            destination = root / "destination"
            source.mkdir()
            video = source / "千岛湖素材.mp4"
            sidecar = source / "千岛湖素材.txt"
            video.write_bytes(b"video")
            sidecar.write_text("sidecar", encoding="utf-8")
            video_destination, sidecar_destinations = source_collector.plan_bundle_destination(
                video,
                destination,
            )

            original_move = shutil.move
            call_count = 0

            def flaky_move(src: str, dst: str):
                nonlocal call_count
                call_count += 1
                if call_count == 2:
                    raise OSError("simulated sidecar move failure")
                return original_move(src, dst)

            with mock.patch.object(source_collector.shutil, "move", side_effect=flaky_move):
                with self.assertRaisesRegex(OSError, "simulated sidecar"):
                    source_collector.move_bundle_transactionally(
                        video,
                        video_destination,
                        sidecar_destinations,
                    )

            self.assertTrue(video.exists())
            self.assertTrue(sidecar.exists())
            self.assertFalse(video_destination.exists())
            self.assertFalse(sidecar_destinations[0].exists())

    def test_nested_source_and_output_roots_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "source"
            output = source / "generated"
            source.mkdir()

            with self.assertRaisesRegex(ValueError, "must not be nested"):
                source_collector.validate_source_and_output_roots(source, output)


if __name__ == "__main__":
    unittest.main()
