from pathlib import Path

from tb_scene.reference_recomposer import choose_clips
from tb_scene.script_matcher import ScriptBeat


def touch_clip(tmp_path: Path, name: str) -> str:
    path = tmp_path / name
    path.write_bytes(b"")
    return str(path)


def record(path: str, category: str, subcategory: str, source: str = "V001", duration: float = 1.2) -> dict[str, object]:
    return {
        "output_path": path,
        "primary_category": category,
        "category_top1": subcategory,
        "semantic_tags": f"{category} {subcategory}",
        "quality_level": "A",
        "source_video_path": source,
        "source_video_name": source,
        "duration": duration,
        "processing_status": "written",
    }


def beat(category: str = "05_项目活动", subcategory: str = "皮划艇") -> ScriptBeat:
    return ScriptBeat(
        index=1,
        text="下午安排皮划艇，湖面玩水更有参与感",
        target_category=category,
        target_subcategory=subcategory,
        suggested_duration=2.6,
    )


def test_concrete_activity_does_not_fallback_to_wrong_activity(tmp_path: Path) -> None:
    records = [
        record(touch_clip(tmp_path, "cycling.mp4"), "05_项目活动", "湖边骑行", "V001"),
        record(touch_clip(tmp_path, "food.mp4"), "04_餐饮美食", "菜品餐桌", "V002"),
        record(touch_clip(tmp_path, "scenery.mp4"), "01_环境空镜", "千岛湖风景", "V003"),
    ]

    chosen = choose_clips(records, beat(), used_outputs=set(), last_source="", target_duration=3.0)

    assert chosen == []


def test_concrete_activity_can_use_direct_match_plus_support_shots(tmp_path: Path) -> None:
    direct = record(touch_clip(tmp_path, "kayak.mp4"), "05_项目活动", "皮划艇", "V001", duration=1.0)
    support = record(touch_clip(tmp_path, "reaction.mp4"), "08_人物反应", "开心反应", "V002", duration=1.0)
    wrong = record(touch_clip(tmp_path, "cycling.mp4"), "05_项目活动", "湖边骑行", "V003", duration=1.0)
    records = [wrong, support, direct]

    chosen = choose_clips(records, beat(), used_outputs=set(), last_source="", target_duration=2.5)
    chosen_names = [Path(str(item["output_path"])).name for item in chosen]

    assert "kayak.mp4" in chosen_names
    assert "reaction.mp4" in chosen_names
    assert "cycling.mp4" not in chosen_names
