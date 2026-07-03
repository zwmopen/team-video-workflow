from tb_scene.shot_match_board import (
    MatchBoardRow,
    apply_feedback_replacement_to_beat,
    feedback_replacement_keyword,
    make_timed_beat,
    parse_timecoded_beats,
    score_record_for_beat,
    write_match_board_xlsx,
)


def test_parse_inline_timecoded_transcript() -> None:
    text = """
00:00-00:03 六七月团建还没定
00:03.500 --> 00:06.000 安吉漂流直接把刺激感拉满
"""

    beats = parse_timecoded_beats(text)

    assert len(beats) == 2
    assert beats[0].start == 0
    assert beats[0].end == 3
    assert beats[1].start == 3.5
    assert beats[1].end == 6
    assert beats[1].target_subcategory == "漂流"


def test_concrete_keyword_rejects_wrong_activity_candidate() -> None:
    beat = make_timed_beat(1, "下午安排皮划艇，湖面玩水更有参与感", 0, 3)
    wrong = {
        "output_path": r"D:\fake\05_项目活动\湖边骑行\cycling.mp4",
        "primary_category": "05_项目活动",
        "category_top1": "湖边骑行",
        "semantic_tags": "05_项目活动 湖边骑行",
        "quality_level": "A",
        "source_video_name": "V001",
    }
    right = {
        "output_path": r"D:\fake\05_项目活动\皮划艇\kayak.mp4",
        "primary_category": "05_项目活动",
        "category_top1": "皮划艇",
        "semantic_tags": "05_项目活动 皮划艇",
        "quality_level": "A",
        "source_video_name": "V002",
    }

    wrong_score, _, wrong_direct = score_record_for_beat(wrong, beat, set(), "")
    right_score, _, right_direct = score_record_for_beat(right, beat, set(), "")

    assert wrong_score <= 0
    assert not wrong_direct
    assert right_score > 0
    assert right_direct


def test_parse_short_arrow_timecodes() -> None:
    text = "00:00.000 --> 00:03.000 kayak\n00:03.000 --> 00:06.000 boat"

    beats = parse_timecoded_beats(text)

    assert len(beats) == 2
    assert beats[0].start == 0
    assert beats[0].end == 3
    assert beats[1].text == "boat"


def test_write_match_board_xlsx(tmp_path) -> None:
    path = tmp_path / "review.xlsx"
    rows = [
        MatchBoardRow(
            index=1,
            start=0,
            end=3,
            script_text="皮划艇很好玩",
            visual_need="皮划艇",
            target_category="05_项目活动",
            target_keyword="皮划艇",
            candidates=[],
            status="缺直接素材",
        )
    ]

    write_match_board_xlsx(path, rows, 5, tmp_path)

    assert path.exists()
    assert path.stat().st_size > 0


def test_feedback_replacement_keyword_accepts_aliases() -> None:
    assert feedback_replacement_keyword({"replacement_keyword": "kayak"}) == "kayak"


def test_feedback_replacement_retargets_beat() -> None:
    beat = make_timed_beat(1, "下午先吃一顿农家菜", 0, 3)

    updated = apply_feedback_replacement_to_beat(beat, {"replacement_keyword": "皮划艇"})

    assert updated.text == beat.text
    assert updated.start == beat.start
    assert updated.end == beat.end
    assert updated.target_category == "05_项目活动"
    assert updated.target_subcategory == "皮划艇"
