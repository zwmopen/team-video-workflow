from pathlib import Path

from tb_scene.audio_library import default_audio_library_output, format_whisper_segments
from tb_scene.shot_match_board import clean_script_text


def test_default_audio_library_output_strips_source_suffix() -> None:
    source = Path(r"D:\Download\素材下载\团建视频\千岛湖-原视频素材")

    assert default_audio_library_output(source).name == "千岛湖音频素材库"


def test_clean_script_text_skips_placeholder_comments() -> None:
    text = "# 待转写\n# source_video: demo.mp4\n00:00.000 --> 00:03.000 皮划艇很好玩\n"

    assert clean_script_text(text) == "00:00.000 --> 00:03.000 皮划艇很好玩"


def test_format_whisper_segments_keeps_timecodes() -> None:
    result = {"segments": [{"start": 0, "end": 3, "text": "  出发去千岛湖  "}]}

    assert format_whisper_segments(result) == "00:00:00.000 --> 00:00:03.000 出发去千岛湖"
