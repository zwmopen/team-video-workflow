from tb_scene.rough_cut_from_board import allocate_row_durations, apply_feedback_to_rows


def test_allocate_row_durations_uses_timecodes() -> None:
    rows = [
        {"start": 0.0, "end": 2.5, "script_text": "出发"},
        {"start": 2.5, "end": 7.0, "script_text": "下午安排皮划艇，湖面玩水更有参与感"},
    ]

    assert allocate_row_durations(rows, audio_duration=0) == [2.5, 4.5]


def test_allocate_row_durations_scales_to_audio_duration() -> None:
    rows = [
        {"start": 0.0, "end": 2.0, "script_text": "出发"},
        {"start": 2.0, "end": 6.0, "script_text": "皮划艇"},
    ]

    assert allocate_row_durations(rows, audio_duration=12) == [4.0, 8.0]


def test_apply_feedback_prefers_selected_candidate() -> None:
    rows = [
        {
            "index": 1,
            "candidates": [
                {"rank": 1, "clip_path": "a.mp4"},
                {"rank": 2, "clip_path": "b.mp4"},
            ],
        }
    ]
    feedback = {1: {"选中素材序号": "2", "确认结果": "通过"}}

    updated = apply_feedback_to_rows(rows, feedback)

    assert updated[0]["candidates"][0]["clip_path"] == "b.mp4"


def test_apply_feedback_skips_waste_row() -> None:
    rows = [{"index": 1, "candidates": [{"rank": 1, "clip_path": "a.mp4"}]}]
    feedback = {1: {"确认结果": "废料"}}

    assert apply_feedback_to_rows(rows, feedback) == []
