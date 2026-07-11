from __future__ import annotations

from pathlib import Path
import csv
import json
import re
import shutil
import sqlite3
from dataclasses import dataclass
from datetime import datetime

from .clip_renamer import rename_library_clips
from .path_utils import ensure_unique_path
from .record_store import RecordStore


CATEGORY_ALIASES = {
    "env": "01_环境空镜",
    "departure": "02_出发抵达",
    "stay": "03_住宿空间",
    "food": "04_餐饮美食",
    "activity": "05_项目活动",
    "team": "06_团队互动",
    "night": "07_烧烤露营夜场",
    "reaction": "08_人物反应",
    "detail": "09_细节特写",
    "ending": "10_收尾返程",
    "review": "90_待人工分类",
}

KEYWORD_ALIASES = {
    "anji_scenery": "安吉风景",
    "anji_aerial": "安吉风景俯拍",
    "anji_gate": "安吉景区入口",
    "qiandao_scenery": "千岛湖风景",
    "qiandao_aerial": "千岛湖风景俯拍",
    "lake_scenery": "湖景山水空镜",
    "moganshan_scenery": "莫干山风景",
    "moganshan_aerial": "莫干山风景俯拍",
    "moganshan_yucun": "庾村小镇",
    "moganshan_sky_tower": "莫干山天际塔",
    "moganshan_cloud": "云海山景",
    "tea_mountain": "茶山风景",
    "forest_scenery": "竹林山景",
    "bamboo_scenery": "竹林风景",
    "canyon_waterfall": "峡谷瀑布空镜",
    "bus_departure": "大巴集合出发",
    "mountain_arrival": "山路抵达",
    "homestay_room": "酒店民宿房间",
    "homestay_yard": "民宿庭院",
    "homestay_pool": "民宿泳池",
    "homestay_exterior": "民宿外观",
    "hot_spring_villa": "温泉别墅",
    "villa_ktv_room": "别墅KTV棋牌室",
    "lake_terrace": "湖景露台",
    "table_food": "菜品餐桌",
    "farm_food": "农家菜",
    "food_buffet": "自助餐",
    "breakfast": "早餐",
    "qiandao_fish": "千岛湖鱼宴",
    "food_closeup": "菜品特写",
    "fruit_drinks": "水果饮品",
    "cheers": "碰杯互动",
    "afternoon_tea": "下午茶点",
    "kayak": "皮划艇",
    "yacht": "游艇游湖",
    "cycling": "湖边骑行",
    "motorboat": "摩托艇",
    "rafting": "漂流",
    "water_play": "水上拓展",
    "creek_play": "溯溪玩水",
    "water_park": "水上乐园",
    "mountain_fun": "高山游乐",
    "atv": "山地越野车",
    "tea_atv": "茶山越野车",
    "bamboo_walk": "竹林徒步",
    "culture_visit": "文化参观",
    "cs": "真人CS",
    "script_murder": "剧本杀",
    "rainbow_slide": "彩虹滑道",
    "mountain_slide": "高山滑道",
    "scenic_train": "田园小火车",
    "grass_ski": "草地滑草",
    "zipline": "高空滑索",
    "high_rope": "高空拓展",
    "archery": "射箭",
    "bumper_ball": "碰碰球",
    "billiards": "台球",
    "mahjong": "麻将",
    "ktv": "KTV唱歌",
    "esports": "电竞游戏",
    "board_game": "桌游",
    "table_game": "棋牌桌游",
    "hot_spring": "温泉泡池",
    "winter_ski": "滑雪",
    "winter_new_year": "冬季跨年",
    "farm_experience": "农事体验",
    "lake_tour": "游船游湖",
    "fruit_picking": "采摘体验",
    "tea_picking": "采茶体验",
    "bamboo_shoot": "挖笋农事体验",
    "team_game": "团队游戏挑战",
    "team_photo": "团队合照",
    "group_photo": "团队合照",
    "team_cheer": "加油欢呼互动",
    "lawn_game": "草坪团建游戏",
    "night_team": "夜间团队互动",
    "team_meeting": "团队会议",
    "camping": "露营",
    "camp_food": "露营吃东西",
    "bbq": "烧烤",
    "roast_lamb": "烤全羊",
    "bonfire": "篝火",
    "fireworks": "烟花",
    "night_fun": "夜间娱乐",
    "happy_reaction": "开心反应",
    "water_ball_detail": "水球装备特写",
    "drink_detail": "饮品特写",
    "breakfast_return": "早餐返程收尾",
    "return_bus": "返程大巴",
    "talking_head": "口播讲解",
    "plan_explainer": "方案讲解",
    "poster_text": "图文海报",
    "traffic_jam": "堵车路况",
    "map_screenshot": "地图截图",
    "shop_screenshot": "店铺截图",
    "schedule_screenshot": "行程表截图",
    "unclear_visual": "待复核画面",
}


@dataclass(slots=True)
class VisualCorrection:
    source_video_id: str
    scene_id: str
    category: str
    keyword: str
    reason: str
    clip_path: str = ""


@dataclass(slots=True)
class VisualCorrectionPlan:
    correction: VisualCorrection
    record: dict[str, object]
    source_video_path: str
    scene_id: str
    current_path: Path | None
    destination: Path | None
    action: str
    has_database_record: bool


def apply_visual_corrections(
    library_root: Path,
    corrections: list[VisualCorrection],
    report_name: str = "visual_corrections.csv",
    apply: bool = False,
    confirm_token: str = "",
    rename_after: bool = False,
    _test_fail_after_moves: int = 0,
) -> dict[str, object]:
    library_root = library_root.expanduser().resolve()
    if not library_root.exists() or not library_root.is_dir():
        raise NotADirectoryError(str(library_root))
    if apply and confirm_token != "APPLY":
        raise ValueError("Applying visual corrections requires confirm_token='APPLY'.")

    db_path = library_root / "._系统记录" / "project.sqlite"
    plans = build_correction_plans(library_root, corrections, db_path)
    moved_rows = preview_rows(plans)
    post_commit_warnings: list[str] = []

    if apply:
        moved_rows = apply_correction_transaction(
            library_root,
            db_path,
            plans,
            fail_after_moves=_test_fail_after_moves,
        )
        if db_path.exists():
            try:
                export_project_files(library_root, moved_rows)
            except Exception as error:  # derivative export must not disguise a committed transaction
                post_commit_warnings.append(f"project export failed: {error}")
        try:
            write_report(library_root, report_name, moved_rows)
        except Exception as error:  # derivative report must not disguise a committed transaction
            post_commit_warnings.append(f"correction report failed: {error}")

    rename_summary: dict[str, object] = {"skipped": "rename_after_not_requested"}
    if rename_after:
        if not db_path.exists():
            rename_summary = {"skipped": "missing_project_database"}
        else:
            try:
                rename_summary = rename_library_clips(
                    library_root,
                    move_files=apply,
                    confirm_token="RENAME" if apply else "",
                )
            except Exception as error:
                rename_summary = {"error": str(error), "rolled_back": True}
                post_commit_warnings.append(f"post-correction clip rename failed: {error}")

    report_path = library_root / "._系统记录" / report_name
    statuses = [row["status"] for row in moved_rows]
    return {
        "library_root": str(library_root),
        "mode": "apply" if apply else "preview",
        "corrections": len(corrections),
        "moved": sum(1 for status in statuses if status in {"moved", "moved_filesystem"}),
        "renamed": sum(1 for status in statuses if status in {"renamed", "renamed_filesystem"}),
        "would_move": sum(1 for status in statuses if status == "would_move"),
        "would_rename": sum(1 for status in statuses if status == "would_rename"),
        "already_correct": sum(1 for status in statuses if status == "already_correct"),
        "missing_file": sum(1 for status in statuses if status == "missing_file"),
        "report_csv": str(report_path) if apply else "",
        "rename_summary": rename_summary,
        "warnings": post_commit_warnings,
    }


def build_correction_plans(
    library_root: Path,
    corrections: list[VisualCorrection],
    db_path: Path,
) -> list[VisualCorrectionPlan]:
    con = sqlite3.connect(db_path) if db_path.exists() else None
    if con is not None:
        con.row_factory = sqlite3.Row
    reserved_targets: set[str] = set()
    plans: list[VisualCorrectionPlan] = []
    try:
        for correction in corrections:
            validate_path_component(correction.category, "category")
            validate_path_component(correction.keyword, "keyword")
            row = find_database_row(con, correction) if con is not None else None
            record = json.loads(row["record_json"]) if row and row["record_json"] else {}
            current_path = resolve_correction_clip(
                library_root,
                correction,
                record,
                str(row["output_path"] or "") if row else "",
            )
            if current_path is None:
                plans.append(
                    VisualCorrectionPlan(
                        correction=correction,
                        record=record,
                        source_video_path=str(row["source_video_path"] or "") if row else "",
                        scene_id=str(row["scene_id"] or correction.scene_id) if row else correction.scene_id,
                        current_path=None,
                        destination=None,
                        action="missing_file",
                        has_database_record=row is not None,
                    )
                )
                continue

            destination_dir = (library_root / correction.category / correction.keyword).resolve()
            ensure_inside_library(library_root, destination_dir)
            candidate = destination_dir / corrected_clip_name(current_path, correction.keyword)
            if current_path.resolve() == candidate.resolve():
                destination = current_path
                action = "already_correct"
            else:
                destination = ensure_unique_planned_path(candidate, current_path, reserved_targets)
                action = "rename" if current_path.parent.resolve() == destination_dir else "move"
            reserved_targets.add(str(destination).casefold())
            plans.append(
                VisualCorrectionPlan(
                    correction=correction,
                    record=record,
                    source_video_path=str(row["source_video_path"] or "") if row else "",
                    scene_id=str(row["scene_id"] or correction.scene_id) if row else correction.scene_id,
                    current_path=current_path,
                    destination=destination,
                    action=action,
                    has_database_record=row is not None,
                )
            )
    finally:
        if con is not None:
            con.close()
    return plans


def apply_correction_transaction(
    library_root: Path,
    db_path: Path,
    plans: list[VisualCorrectionPlan],
    fail_after_moves: int = 0,
) -> list[dict[str, str]]:
    for plan in plans:
        if plan.action in {"move", "rename"}:
            assert plan.current_path is not None and plan.destination is not None
            if not plan.current_path.exists() or not plan.current_path.is_file():
                raise FileNotFoundError(f"Correction source is missing: {plan.current_path}")
            if plan.destination.exists():
                raise FileExistsError(f"Correction target already exists: {plan.destination}")

    con = sqlite3.connect(db_path) if db_path.exists() else None
    completed: list[VisualCorrectionPlan] = []
    created_dirs: list[Path] = []
    moved_count = 0
    try:
        if con is not None:
            con.execute("BEGIN IMMEDIATE")
        for plan in plans:
            if plan.action in {"move", "rename"}:
                assert plan.current_path is not None and plan.destination is not None
                created_dirs.extend(create_destination_directories(library_root, plan.destination.parent))
                plan.current_path.rename(plan.destination)
                completed.append(plan)
                moved_count += 1
                if fail_after_moves and moved_count >= fail_after_moves:
                    raise RuntimeError(f"simulated failure after {moved_count} correction move(s)")
            if plan.has_database_record and plan.current_path is not None and plan.destination is not None:
                if con is None:
                    raise RuntimeError("Database record was planned but the database is unavailable.")
                update_visual_record(con, plan)
        if con is not None:
            con.commit()
    except Exception as original_error:
        if con is not None:
            con.rollback()
        rollback_errors: list[str] = []
        for plan in reversed(completed):
            try:
                assert plan.current_path is not None and plan.destination is not None
                if plan.destination.exists() and not plan.current_path.exists():
                    plan.destination.rename(plan.current_path)
            except Exception as rollback_error:  # pragma: no cover - defensive path
                rollback_errors.append(
                    f"{plan.destination} -> {plan.current_path}: {rollback_error}"
                )
        cleanup_created_directories(created_dirs)
        if rollback_errors:
            raise RuntimeError(
                "Visual correction failed and filesystem rollback was incomplete:\n"
                + "\n".join(rollback_errors)
            ) from original_error
        raise
    finally:
        if con is not None:
            con.close()

    return applied_rows(plans)


def update_visual_record(con: sqlite3.Connection, plan: VisualCorrectionPlan) -> None:
    assert plan.destination is not None
    record = dict(plan.record)
    update_record(record, plan.correction, plan.destination)
    cursor = con.execute(
        """
        update scenes
        set output_path=?,
            primary_category=?,
            confidence_top1=1.0,
            record_json=?,
            updated_at=datetime('now')
        where source_video_path=? and scene_id=?
        """,
        (
            str(plan.destination),
            plan.correction.category,
            json.dumps(record, ensure_ascii=False),
            plan.source_video_path,
            plan.scene_id,
        ),
    )
    if cursor.rowcount != 1:
        raise RuntimeError(
            f"Expected one scene row for {plan.source_video_path} / {plan.scene_id}, got {cursor.rowcount}"
        )


def preview_rows(plans: list[VisualCorrectionPlan]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for plan in plans:
        status = {
            "move": "would_move",
            "rename": "would_rename",
        }.get(plan.action, plan.action)
        rows.append(
            report_row(
                plan.correction,
                str(plan.current_path or plan.correction.clip_path),
                str(plan.destination or ""),
                status,
            )
        )
    return rows


def applied_rows(plans: list[VisualCorrectionPlan]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for plan in plans:
        if plan.action == "move":
            status = "moved" if plan.has_database_record else "moved_filesystem"
        elif plan.action == "rename":
            status = "renamed" if plan.has_database_record else "renamed_filesystem"
        else:
            status = plan.action
        rows.append(
            report_row(
                plan.correction,
                str(plan.current_path or plan.correction.clip_path),
                str(plan.destination or ""),
                status,
            )
        )
    return rows


def validate_path_component(value: str, label: str) -> None:
    if not value or value in {".", ".."} or Path(value).name != value or "/" in value or "\\" in value:
        raise ValueError(f"Invalid correction {label}: {value!r}")


def ensure_inside_library(library_root: Path, candidate: Path) -> None:
    try:
        candidate.relative_to(library_root)
    except ValueError as error:
        raise ValueError(f"Correction target escapes library root: {candidate}") from error


def ensure_unique_planned_path(candidate: Path, current_path: Path, reserved: set[str]) -> Path:
    if not candidate.exists() and str(candidate).casefold() not in reserved:
        return candidate
    counter = 2
    while True:
        next_path = candidate.with_name(f"{candidate.stem}_{counter}{candidate.suffix}")
        if (
            (not next_path.exists() or next_path.resolve() == current_path.resolve())
            and str(next_path).casefold() not in reserved
        ):
            return next_path
        counter += 1


def create_destination_directories(library_root: Path, destination_dir: Path) -> list[Path]:
    ensure_inside_library(library_root, destination_dir)
    missing: list[Path] = []
    current = destination_dir
    while current != library_root and not current.exists():
        missing.append(current)
        current = current.parent
    destination_dir.mkdir(parents=True, exist_ok=True)
    return missing


def cleanup_created_directories(paths: list[Path]) -> None:
    for path in sorted(set(paths), key=lambda item: len(item.parts), reverse=True):
        try:
            path.rmdir()
        except OSError:
            pass


def find_database_row(con: sqlite3.Connection, correction: VisualCorrection) -> sqlite3.Row | None:
    if correction.source_video_id and correction.scene_id:
        row = con.execute(
            """
            select source_video_id, source_video_path, scene_id, record_json, output_path
            from scenes where source_video_id=? and scene_id=?
            """,
            (correction.source_video_id, correction.scene_id),
        ).fetchone()
        if row:
            return row
    if correction.clip_path:
        target = str(Path(correction.clip_path).resolve())
        row = con.execute(
            """
            select source_video_id, source_video_path, scene_id, record_json, output_path
            from scenes where output_path=?
            """,
            (target,),
        ).fetchone()
        if row:
            return row
        return con.execute(
            """
            select source_video_id, source_video_path, scene_id, record_json, output_path
            from scenes where output_path like ? limit 1
            """,
            (f"%{Path(target).name}",),
        ).fetchone()
    return None


def resolve_correction_clip(
    library_root: Path,
    correction: VisualCorrection,
    record: dict[str, object],
    output_path: str,
) -> Path | None:
    if correction.clip_path:
        path = Path(correction.clip_path).expanduser()
        if path.exists() and path.is_file():
            try:
                path.resolve().relative_to(library_root)
            except ValueError:
                return None
            return path.resolve()
        moved_candidate = library_root / correction.category / correction.keyword / path.name
        if moved_candidate.exists() and moved_candidate.is_file():
            return moved_candidate.resolve()
    return resolve_current_clip(library_root, record, output_path)


def corrected_clip_name(path: Path, keyword: str) -> str:
    """Keep source and scene suffixes while making the visible keyword match its folder."""
    stem, separator, source_suffix = path.stem.partition("__")
    parts = stem.split("_")
    if len(parts) < 3 or not parts[0].isdigit():
        return path.name
    safe_keyword = re.sub(r'[<>:"/\\|?*]', "_", keyword).strip() or parts[2]
    rebuilt = "_".join([parts[0], parts[1], safe_keyword, *parts[3:]])
    return f"{rebuilt}{separator}{source_suffix}{path.suffix}"


def resolve_current_clip(library_root: Path, record: dict[str, object], output_path: str) -> Path | None:
    path = Path(output_path or str(record.get("output_path") or ""))
    if path.exists():
        return path
    source_id = str(record.get("source_video_id") or "")
    scene_id = str(record.get("scene_id") or "")
    if not source_id or not scene_id:
        return None
    patterns = [f"*__*_{source_id}_{scene_id}.mp4", f"*__{source_id}_{scene_id}.mp4", f"*_{source_id}_{scene_id}.mp4"]
    for pattern in patterns:
        matches = [item for item in library_root.rglob(pattern) if "._系统记录" not in item.parts]
        if len(matches) == 1:
            return matches[0]
    return None


def update_record(record: dict[str, object], correction: VisualCorrection, destination: Path) -> None:
    previous_path = str(record.get("output_path") or "")
    record["output_path"] = str(destination)
    record["primary_category"] = correction.category
    record["category_top1"] = f"{correction.category}/{correction.keyword}"
    record["confidence_top1"] = 1.0
    record["manual_category"] = f"{correction.category}/{correction.keyword}"
    record["manual_output_path"] = str(destination)
    record["manual_locked"] = "true"
    record["review_status"] = "visual_corrected"
    record["visual_correction_reason"] = correction.reason
    record["visual_correction_previous_path"] = previous_path
    record["updated_at"] = datetime.now().isoformat(timespec="seconds")
    tags = [tag for tag in re.split(r"[;,\s]+", str(record.get("semantic_tags") or "")) if tag]
    for tag in [correction.keyword, "视觉复核"]:
        if tag not in tags:
            tags.append(tag)
    record["semantic_tags"] = ";".join(tags)


def export_project_files(library_root: Path, moved_rows: list[dict[str, str]]) -> None:
    store = RecordStore(library_root / "._系统记录")
    try:
        store.export_files({"library_root": str(library_root), "visual_corrections": len(moved_rows)})
    finally:
        store.close()


def write_report(library_root: Path, report_name: str, rows: list[dict[str, str]]) -> None:
    path = library_root / "._系统记录" / report_name
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["source_video_id", "scene_id", "category", "keyword", "old_path", "new_path", "status", "reason"]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def report_row(correction: VisualCorrection, old_path: str, new_path: str, status: str) -> dict[str, str]:
    return {
        "source_video_id": correction.source_video_id,
        "scene_id": correction.scene_id,
        "category": correction.category,
        "keyword": correction.keyword,
        "old_path": old_path,
        "new_path": new_path,
        "status": status,
        "reason": correction.reason,
    }


def corrections_from_csv(path: Path) -> list[VisualCorrection]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = csv.DictReader(handle)
        corrections: list[VisualCorrection] = []
        for row in rows:
            category = str(row.get("category") or row.get("suggested_category") or "").strip()
            keyword = str(row.get("keyword") or row.get("suggested_keyword") or "").strip()
            if not category or not keyword:
                continue
            corrections.append(
                VisualCorrection(
                    source_video_id=str(row.get("source_video_id") or "").strip(),
                    scene_id=str(row.get("scene_id") or "").strip(),
                    category=normalize_category(category),
                    keyword=normalize_keyword(keyword),
                    reason=str(row.get("reason") or "visual correction"),
                    clip_path=str(row.get("clip_path") or "").strip(),
                )
            )
        return corrections


def normalize_category(value: str) -> str:
    return CATEGORY_ALIASES.get(value.strip(), value.strip())


def normalize_keyword(value: str) -> str:
    return KEYWORD_ALIASES.get(value.strip(), value.strip())
