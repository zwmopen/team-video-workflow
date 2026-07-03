from pathlib import Path

from tb_scene.smart_match_workflow import (
    default_location_audio_library,
    infer_location_name,
    infer_location_source_dir,
    pick_audio_by_index,
    pick_audio_by_query,
    pick_first_ready_audio,
)


def test_pick_first_ready_audio_prefers_audio_with_text(tmp_path: Path) -> None:
    first = tmp_path / "001_empty.m4a"
    second = tmp_path / "002_ready.m4a"
    first.write_bytes(b"audio")
    second.write_bytes(b"audio")
    first.with_suffix(".txt").write_text("", encoding="utf-8")
    second.with_suffix(".txt").write_text("00:00.000 --> 00:01.000 hello", encoding="utf-8")

    assert pick_first_ready_audio(tmp_path) == second


def test_infers_location_assets_from_scene_library(tmp_path: Path) -> None:
    library = tmp_path / "千岛湖智能镜头分类"
    source = tmp_path / "千岛湖-原视频素材"
    library.mkdir()
    source.mkdir()

    assert infer_location_name(library) == "千岛湖"
    assert default_location_audio_library(library) == tmp_path / "千岛湖音频素材库"
    assert infer_location_source_dir(library) == source


def test_picks_audio_by_index_and_query(tmp_path: Path) -> None:
    first = tmp_path / "001_皮划艇.m4a"
    second = tmp_path / "002_小橘千岛湖.m4a"
    first.write_bytes(b"audio")
    second.write_bytes(b"audio")
    first.with_suffix(".txt").write_text("00:00.000 --> 00:01.000 皮划艇", encoding="utf-8")
    second.with_suffix(".txt").write_text("00:00.000 --> 00:01.000 小橘", encoding="utf-8")

    assert pick_audio_by_index(tmp_path, 2) == second
    assert pick_audio_by_query(tmp_path, "小橘") == second
