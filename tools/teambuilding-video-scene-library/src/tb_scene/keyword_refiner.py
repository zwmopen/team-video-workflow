from __future__ import annotations

from pathlib import Path
import csv
import json
import re
import shutil
import sqlite3
from dataclasses import dataclass

from .path_utils import ensure_unique_path
from .record_store import RecordStore
from .transcriber import transcript_text_for_scene


REFINE_ROOTS = {
    "01_环境空镜",
    "02_出发抵达",
    "03_住宿空间",
    "04_餐饮美食",
    "05_项目活动",
    "06_团队互动",
    "07_烧烤露营夜场",
    "08_人物反应",
    "09_细节特写",
    "10_收尾返程",
    "90_待人工分类",
}


KEYWORD_RULES: list[tuple[str, str, str, int]] = [
    (r"风景空镜转场|转场过渡|收尾候选", "01_环境空镜", "风景空镜转场", 103),
    (r"千岛湖风景俯拍|风景俯拍|俯拍|航拍", "01_环境空镜", "{location}风景俯拍", 102),
    (r"千岛湖风景|湖景|湖面|山水|山景|风景|日落|空镜", "01_环境空镜", "{location}风景", 101),
    (r"大巴|上车|下车|车内|车上|出发|抵达|集合", "02_出发抵达", "大巴集合出发", 106),
    (r"鱼头|鱼宴|鱼锅|湖鲜", "04_餐饮美食", "千岛湖鱼宴", 100),
    (r"菜品|上菜|餐桌|桌餐|农家菜|美食|土鸡|黄牛肉|午餐|晚餐|中餐|用餐", "04_餐饮美食", "菜品餐桌", 90),
    (r"碰杯|干杯|举杯", "04_餐饮美食", "碰杯互动", 95),
    (r"烧烤|烤串|烤肉|炭火|烤炉", "07_烧烤露营夜场", "烧烤", 105),
    (r"露营吃东西|营地吃东西|露营餐食|营地餐食", "07_烧烤露营夜场", "露营吃东西", 106),
    (r"露营|营地|帐篷|天幕|草坪营地|湖边露营", "07_烧烤露营夜场", "露营", 104),
    (r"篝火|火焰|营火|围炉", "07_烧烤露营夜场", "篝火", 104),
    (r"烟花|烟火", "07_烧烤露营夜场", "烟花", 100),
    (r"KTV|唱歌|麻将|狼人杀", "07_烧烤露营夜场", "夜间娱乐", 90),
    (r"皮划艇|划艇|桨|划船|kayak", "05_项目活动", "皮划艇", 110),
    (r"摩托艇|水上摩托|动力艇", "05_项目活动", "摩托艇", 109),
    (r"水上乐园|水乐园|水上游乐|水上娱乐", "05_项目活动", "水上乐园", 106),
    (r"游艇|游船|快艇|船上|坐船|游湖|船头|船舷|甲板|湖水承包", "05_项目活动", "游艇游湖", 108),
    (r"桨板|浆板|paddle", "05_项目活动", "桨板", 100),
    (r"水上拓展|水上闯关|水上项目|闯关|玩水|打水仗|溯溪|水枪", "05_项目活动", "水上拓展", 100),
    (r"漂流|峡谷漂流", "05_项目活动", "漂流", 100),
    (r"骑行|自行车|环湖骑|环湖骑行|单车", "05_项目活动", "湖边骑行", 106),
    (r"真人CS|镭战|水弹|丛林战", "05_项目活动", "真人CS", 100),
    (r"草坪|飞盘|团建游戏|小游戏|拓展|挑战|分组|竞速|协作", "06_团队互动", "团队游戏挑战", 85),
    (r"加油|欢呼|鼓掌|击掌|团队互动", "06_团队互动", "加油欢呼互动", 88),
    (r"合影|合照|大合照|团队照|团队合照", "06_团队互动", "团队合照", 98),
    (r"笑|开心|比耶|挥手|尖叫|表情|抓拍", "08_人物反应", "开心反应", 80),
    (r"水花|浪花|船桨|桨叶", "09_细节特写", "水花船桨特写", 82),
    (r"烤串|食材|菜品特写|火焰|装备|手部", "09_细节特写", "细节特写", 82),
    (r"酒店|民宿|别墅|住宿|房间|床|湖景房|阳台|窗|公共区域|入住|办理入住", "03_住宿空间", "酒店民宿房间", 90),
    (r"早餐|返程|回程|告别|结束|收尾", "10_收尾返程", "早餐返程收尾", 92),
]

GENERATED_KEYWORDS = {keyword for _, _, keyword, _ in KEYWORD_RULES} | {"待复核关键词"}


@dataclass(slots=True)
class RefineResult:
    clip_path: Path
    new_path: Path
    category: str
    keyword: str
    confidence: float
    evidence: str


def refine_library_keywords(
    library_root: Path,
    use_ocr: bool = True,
    move_files: bool = True,
    use_transcript: bool = True,
) -> dict[str, object]:
    library_root = library_root.expanduser().resolve()
    location_name = infer_location_name(library_root)
    records = load_records(library_root)
    ocr_reader = None
    if use_ocr:
        try:
            import easyocr

            ocr_reader = easyocr.Reader(["ch_sim", "en"], gpu=False, verbose=False)
        except Exception:
            ocr_reader = None

    results: list[RefineResult] = []
    actual_moved = 0
    would_move = 0
    ocr_attempted = 0
    for record in records:
        if is_manual_locked(record):
            continue
        clip_path = Path(str(record.get("output_path") or ""))
        if not clip_path.exists():
            continue
        if not _is_refine_target(library_root, clip_path):
            continue
        evidence_parts = [
            clip_path.name,
            str(record.get("source_video_name", "")),
            cleaned_semantic_tags(record),
            sidecar_source_text(record),
        ]
        if use_transcript:
            evidence_parts.append(scene_transcript_text(library_root, record))
        evidence = "\n".join(part for part in evidence_parts if part)
        category, keyword, confidence = route_keyword(evidence, str(record.get("primary_category", "")), location_name)
        if ocr_reader is not None and should_try_ocr(category, keyword, confidence):
            ocr_text = ocr_text_for_record(library_root, record, ocr_reader)
            if ocr_text:
                ocr_attempted += 1
                evidence = "\n".join(part for part in [evidence, ocr_text] if part)
                category, keyword, confidence = route_keyword(evidence, str(record.get("primary_category", "")), location_name)
        destination_dir = library_root / category / keyword
        destination_dir.mkdir(parents=True, exist_ok=True)
        candidate_path = destination_dir / clip_path.name
        if clip_path.resolve() == candidate_path.resolve():
            new_path = clip_path
        else:
            new_path = ensure_unique_path(candidate_path)
        needs_move = clip_path.resolve() != new_path.resolve()
        if needs_move:
            would_move += 1
        if move_files and needs_move:
            shutil.move(str(clip_path), str(new_path))
            record["output_path"] = str(new_path)
            update_record_output_path(library_root, record, new_path, category, keyword, confidence)
            actual_moved += 1
        results.append(
            RefineResult(
                clip_path=clip_path,
                new_path=new_path,
                category=category,
                keyword=keyword,
                confidence=confidence,
                evidence=evidence[:500],
            )
        )

    write_refine_report(library_root, results, move_files=move_files, actual_moved=actual_moved, would_move=would_move)
    if move_files and actual_moved:
        export_updated_project_files(library_root, actual_moved=actual_moved, results=results)
        remove_empty_refine_dirs(library_root)
    return {
        "library_root": str(library_root),
        "processed": len(results),
        "moved": actual_moved,
        "would_move": would_move,
        "category_counts": count_results(results),
        "ocr_enabled": ocr_reader is not None,
        "ocr_attempted": ocr_attempted,
        "transcript_enabled": use_transcript,
    }


def load_records(library_root: Path) -> list[dict[str, object]]:
    db = library_root / "._系统记录" / "project.sqlite"
    if db.exists():
        con = sqlite3.connect(db)
        con.row_factory = sqlite3.Row
        try:
            rows = []
            for row in con.execute("select record_json from scenes where processing_status='written'"):
                if row["record_json"]:
                    rows.append(json.loads(row["record_json"]))
            return rows
        finally:
            con.close()
    csv_path = library_root / "._系统记录" / "scenes.csv"
    if csv_path.exists():
        with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
            return [dict(row) for row in csv.DictReader(handle)]
    return []


def _is_refine_target(library_root: Path, clip_path: Path) -> bool:
    try:
        relative = clip_path.relative_to(library_root)
    except ValueError:
        return False
    if not relative.parts:
        return False
    return relative.parts[0] in REFINE_ROOTS


def sidecar_source_text(record: dict[str, object]) -> str:
    source_path = Path(str(record.get("source_video_path") or ""))
    if not source_path.exists():
        return ""
    candidates = [source_path.with_suffix(".txt")]
    if " - 副本" in source_path.stem:
        candidates.append(source_path.with_name(source_path.stem.replace(" - 副本", "") + ".txt"))
    for candidate in candidates:
        if candidate.exists():
            return candidate.read_text(encoding="utf-8", errors="ignore")
    return ""


def cleaned_semantic_tags(record: dict[str, object]) -> str:
    raw_tags = re.split(r"[;,\s]+", str(record.get("semantic_tags", "")))
    tags = [tag for tag in raw_tags if tag and tag not in GENERATED_KEYWORDS]
    return ";".join(tags)


def is_manual_locked(record: dict[str, object]) -> bool:
    value = str(record.get("manual_locked", "")).strip().lower()
    return value in {"1", "true", "yes", "y", "locked"}


def ocr_text_for_record(library_root: Path, record: dict[str, object], reader) -> str:
    source_id = str(record.get("source_video_id") or "")
    scene_id = str(record.get("scene_id") or "")
    keyframe_dir = library_root / "._系统记录" / "keyframes"
    texts: list[str] = []
    for suffix in ["50", "20", "80"]:
        frame = keyframe_dir / f"{source_id}_{scene_id}_{suffix}.jpg"
        if not frame.exists():
            continue
        try:
            image = read_image_unicode(frame)
            if image is None:
                continue
            result = reader.readtext(image, detail=0, paragraph=True)
            texts.extend(str(item) for item in result)
        except Exception:
            continue
        if texts:
            break
    return "\n".join(texts)


def read_image_unicode(path: Path):
    try:
        import cv2
        import numpy as np

        data = np.fromfile(str(path), dtype=np.uint8)
        if data.size == 0:
            return None
        return cv2.imdecode(data, cv2.IMREAD_COLOR)
    except Exception:
        return None


def scene_transcript_text(library_root: Path, record: dict[str, object]) -> str:
    source_id = str(record.get("source_video_id") or "")
    start = timestamp_to_seconds(str(record.get("source_start_time") or "0"))
    end = timestamp_to_seconds(str(record.get("source_end_time") or "0"))
    return transcript_text_for_scene(library_root, source_id, start, end)


def timestamp_to_seconds(value: str) -> float:
    try:
        if ":" not in value:
            return float(value)
        hour, minute, second = value.split(":")
        return int(hour) * 3600 + int(minute) * 60 + float(second)
    except Exception:
        return 0.0


def infer_location_name(library_root: Path) -> str:
    name = library_root.name
    name = re.sub(r"智能镜头分类$", "", name)
    name = re.sub(r"-原视频素材$", "", name)
    return name.strip() or "地点"


def route_keyword(evidence: str, fallback_category: str, location_name: str) -> tuple[str, str, float]:
    normalized = evidence.lower()
    best: tuple[int, str, str] | None = None
    for pattern, category, keyword, score in KEYWORD_RULES:
        if re.search(pattern, normalized, flags=re.IGNORECASE):
            if best is None or score > best[0]:
                best = (score, category, keyword)
    if best:
        return best[1], best[2].format(location=location_name), min(0.98, best[0] / 110)
    return "90_待人工分类", "待复核关键词", 0.25


def remove_empty_refine_dirs(library_root: Path) -> None:
    for root in REFINE_ROOTS:
        root_path = library_root / root
        if not root_path.exists():
            continue
        for path in sorted(root_path.rglob("*"), key=lambda item: len(item.parts), reverse=True):
            if path.is_dir():
                try:
                    path.rmdir()
                except OSError:
                    pass


def should_try_ocr(category: str, keyword: str, confidence: float) -> bool:
    if confidence < 0.85:
        return True
    if keyword == "待复核关键词":
        return True
    return category in {"01_环境空镜", "90_待人工分类"}


def update_record_output_path(
    library_root: Path,
    record: dict[str, object],
    new_path: Path,
    category: str,
    keyword: str,
    confidence: float,
) -> None:
    db = library_root / "._系统记录" / "project.sqlite"
    if not db.exists():
        return
    record["output_path"] = str(new_path)
    record["primary_category"] = category
    record["category_top1"] = f"{category}/{keyword}"
    record["confidence_top1"] = confidence
    tags = str(record.get("semantic_tags", ""))
    if keyword not in tags:
        record["semantic_tags"] = f"{tags};{keyword}".strip(";")
    con = sqlite3.connect(db)
    try:
        con.execute(
            """
            update scenes
            set output_path=?, primary_category=?, confidence_top1=?, record_json=?, updated_at=datetime('now')
            where source_video_path=? and scene_id=?
            """,
            (
                str(new_path),
                category,
                confidence,
                json.dumps(record, ensure_ascii=False),
                str(record.get("source_video_path", "")),
                str(record.get("scene_id", "")),
            ),
        )
        con.commit()
    finally:
        con.close()


def export_updated_project_files(library_root: Path, actual_moved: int, results: list[RefineResult]) -> None:
    store = RecordStore(library_root / "._系统记录")
    try:
        store.export_files(
            {
                "library_root": str(library_root),
                "refine_processed": len(results),
                "refine_moved": actual_moved,
                "category_counts": count_results(results),
            }
        )
    finally:
        store.close()


def write_refine_report(
    library_root: Path,
    results: list[RefineResult],
    move_files: bool,
    actual_moved: int,
    would_move: int,
) -> None:
    system_dir = library_root / "._系统记录"
    system_dir.mkdir(parents=True, exist_ok=True)
    csv_name = "keyword_refine.csv" if move_files else "keyword_refine_preview.csv"
    summary_name = "keyword_refine_summary.json" if move_files else "keyword_refine_preview_summary.json"
    csv_path = system_dir / csv_name
    with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["old_path", "new_path", "category", "keyword", "confidence", "evidence"],
        )
        writer.writeheader()
        for result in results:
            writer.writerow(
                {
                    "old_path": str(result.clip_path),
                    "new_path": str(result.new_path),
                    "category": result.category,
                    "keyword": result.keyword,
                    "confidence": result.confidence,
                    "evidence": result.evidence,
                }
            )
    summary = {
        "processed": len(results),
        "moved": actual_moved,
        "would_move": would_move,
        "move_files": move_files,
        "category_counts": count_results(results),
    }
    (system_dir / summary_name).write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")


def count_results(results: list[RefineResult]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for result in results:
        key = f"{result.category}/{result.keyword}"
        counts[key] = counts.get(key, 0) + 1
    return dict(sorted(counts.items(), key=lambda item: item[0]))
