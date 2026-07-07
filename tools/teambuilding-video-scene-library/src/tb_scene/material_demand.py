from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from collections import Counter, defaultdict
import csv
import json
import os
import re
import subprocess
from datetime import datetime

from .models import VIDEO_EXTENSIONS
from .path_utils import sanitize_name
from .reference_learner import (
    DEFAULT_FEISHU_DOC,
    find_lark_cli,
    lark_argv,
    normalize_keyword_text,
    parse_lark_json,
    transcribe_video_with_segments,
    xml_text,
)


DEFAULT_TEAMBUILDING_ROOT = Path(r"D:\Download\素材下载\团建视频")
DEFAULT_OUTPUT_ROOT = DEFAULT_TEAMBUILDING_ROOT / "90_待整理与记录" / "00-模板库" / "素材需求雷达"


@dataclass(slots=True)
class MaterialDemandOptions:
    source_root: Path = DEFAULT_TEAMBUILDING_ROOT
    output_root: Path = DEFAULT_OUTPUT_ROOT
    locations: list[str] | None = None
    run_name: str = ""
    transcribe_audio: bool = False
    force_transcribe: bool = False
    max_videos_per_location: int | None = None
    min_existing_clips: int = 8
    publish_feishu: bool = False
    feishu_doc: str = DEFAULT_FEISHU_DOC


@dataclass(frozen=True, slots=True)
class DemandRule:
    keyword: str
    category: str
    patterns: tuple[str, ...]
    search_terms: tuple[str, ...]
    note: str


DEMAND_RULES: list[DemandRule] = [
    DemandRule("大巴集合出发", "02_出发抵达", ("大巴", "上车", "出发", "集合", "车程", "路上", "抵达"), ("大巴出发", "团队上大巴", "公司团建出发", "集合出发"), "出发段常用铺画面"),
    DemandRule("风景空镜俯拍", "01_环境空镜", ("航拍", "俯拍", "风景", "山水", "湖景", "山景", "竹海", "山林", "云海", "避暑", "转场"), ("风景航拍", "风景空镜", "山水转场", "目的地航拍"), "开场、转场、收尾都能用"),
    DemandRule("酒店民宿房间", "03_住宿空间", ("民宿", "酒店", "住宿", "房间", "床", "别墅", "包栋", "阳台", "庭院", "泳池"), ("民宿房间", "团建民宿", "包栋别墅", "酒店房间", "民宿外观"), "吃住行里的住宿画面"),
    DemandRule("农家菜聚餐", "04_餐饮美食", ("农家菜", "土鸡", "黄牛肉", "饭桌", "聚餐", "吃饭", "餐桌", "碰杯", "早餐", "湖鲜"), ("农家菜", "团建聚餐", "餐桌吃饭", "菜品特写", "碰杯聚餐"), "餐饮段主素材"),
    DemandRule("烤全羊", "04_餐饮美食", ("烤全羊",), ("烤全羊", "团建烤全羊", "户外烤全羊"), "高识别度餐饮卖点"),
    DemandRule("烧烤", "07_烧烤露营夜场", ("烧烤", "烤肉", "烤串", "BBQ", "炭火", "围炉"), ("烧烤", "户外烧烤", "露营烧烤", "烧烤烤串"), "夜场氛围核心"),
    DemandRule("露营营地", "07_烧烤露营夜场", ("露营", "营地", "天幕", "帐篷", "草坪", "围坐", "晚风"), ("露营", "营地", "天幕露营", "草坪露营"), "松弛感和氛围补画面"),
    DemandRule("篝火烟花夜场", "07_烧烤露营夜场", ("篝火", "火光", "烟花", "仙女棒", "夜场", "音乐", "晚会"), ("篝火", "烟花", "篝火晚会", "仙女棒", "夜场团建"), "情绪高潮素材"),
    DemandRule("KTV唱歌轰趴", "07_烧烤露营夜场", ("KTV", "唱歌", "轰趴", "棋牌", "麻将"), ("KTV唱歌", "民宿轰趴", "团建轰趴", "麻将棋牌"), "雨天/夜间室内备选"),
    DemandRule("皮划艇", "05_项目活动", ("皮划艇", "划艇", "划船", "桨板", "船桨", "划桨"), ("皮划艇", "团建皮划艇", "水上皮划艇", "桨板"), "水上项目直给画面"),
    DemandRule("游艇游湖", "05_项目活动", ("游艇", "游船", "坐船", "游湖", "船上", "湖面", "快艇"), ("游艇", "游艇游湖", "游船", "湖面游艇", "坐船游湖"), "千岛湖/湖区高频素材"),
    DemandRule("摩托艇", "05_项目活动", ("摩托艇", "水上摩托"), ("摩托艇", "水上摩托艇", "湖面摩托艇"), "刺激型水上项目"),
    DemandRule("湖边骑行", "05_项目活动", ("骑行", "自行车", "环湖骑行", "湖边骑行"), ("骑行", "环湖骑行", "湖边骑行", "团建骑行"), "轻运动项目"),
    DemandRule("漂流", "05_项目活动", ("漂流", "龙王山", "水花四溅", "尖叫", "刺激"), ("漂流", "峡谷漂流", "龙王山漂流", "夏季漂流"), "夏季高潮项目"),
    DemandRule("峡谷玩水水上拓展", "05_项目活动", ("玩水", "水球", "打水仗", "溯溪", "溪谷", "峡谷", "水上拓展", "水上挑战", "水上乐园"), ("峡谷玩水", "溯溪", "打水仗", "水上拓展", "水上乐园"), "夏季破冰/玩水段"),
    DemandRule("真人CS镭战", "05_项目活动", ("真人CS", "镭战", "水弹", "射击", "对抗"), ("真人CS", "镭战", "水弹CS", "团建真人CS"), "对抗类项目"),
    DemandRule("竹海徒步", "05_项目活动", ("竹海", "竹林", "徒步", "爬山", "山路"), ("竹海徒步", "竹林徒步", "莫干山竹海", "安吉竹海"), "山野线常用项目"),
    DemandRule("山地越野车", "05_项目活动", ("越野", "ATV", "UTV", "山地车"), ("山地越野车", "ATV越野", "UTV越野", "团建越野车"), "莫干山/山地项目"),
    DemandRule("彩虹滑道高山滑道", "05_项目活动", ("滑道", "滑草", "彩虹滑道", "高山滑道"), ("彩虹滑道", "高山滑道", "滑草", "山地滑道"), "游乐场项目"),
    DemandRule("高空滑索", "05_项目活动", ("高空", "滑索", "飞拉达", "攀岩"), ("高空滑索", "飞拉达", "攀岩", "高空项目"), "刺激项目补充"),
    DemandRule("飞盘棒球", "06_团队互动", ("飞盘", "棒球", "躲避球"), ("飞盘", "棒球团建", "草坪飞盘", "团队飞盘"), "草坪互动"),
    DemandRule("拔河", "06_团队互动", ("拔河",), ("拔河", "团队拔河", "户外拔河"), "团队对抗经典画面"),
    DemandRule("团队游戏挑战", "06_团队互动", ("破冰", "团队游戏", "挑战", "分组", "协作", "竞速", "趣味运动会"), ("团队游戏", "破冰游戏", "趣味运动会", "团队挑战"), "通用团建互动"),
    DemandRule("团队合照", "06_团队互动", ("合照", "合影", "大合照", "团队照", "拍照"), ("团队合照", "团建合影", "大合照"), "结尾/成果展示"),
    DemandRule("开心人物反应", "08_人物反应", ("开心", "欢呼", "尖叫", "热闹", "快乐", "爆笑", "比耶", "氛围"), ("开心欢呼", "团建开心", "人物笑脸", "尖叫欢呼"), "情绪补镜"),
    DemandRule("细节特写", "09_细节特写", ("特写", "细节", "水花", "手部", "装备", "火焰", "食材", "烤串"), ("水花特写", "烤串特写", "装备特写", "火焰特写", "手部特写"), "转场和节奏填充"),
    DemandRule("收尾返程", "10_收尾返程", ("结束", "收尾", "告别", "最后", "回程", "第二天", "返程"), ("返程", "团建收尾", "告别合照", "第二天返程"), "尾段素材"),
]


def analyze_material_demand(options: MaterialDemandOptions) -> dict[str, object]:
    source_root = options.source_root.expanduser().resolve()
    output_root = options.output_root.expanduser().resolve()
    run_name = options.run_name or datetime.now().strftime("%Y%m%d_%H%M%S_素材需求雷达")
    output_dir = output_root / sanitize_name(run_name)
    transcript_dir = output_dir / "transcripts"
    output_dir.mkdir(parents=True, exist_ok=True)
    transcript_dir.mkdir(parents=True, exist_ok=True)

    location_roots = discover_location_roots(source_root, options.locations)
    video_rows: list[dict[str, object]] = []
    keyword_rows: list[dict[str, object]] = []
    transcript_rows: list[dict[str, object]] = []
    location_keyword_counts: dict[str, Counter[str]] = defaultdict(Counter)
    location_category_counts: dict[str, Counter[str]] = defaultdict(Counter)
    location_search_terms: dict[str, Counter[str]] = defaultdict(Counter)

    for location, root in location_roots:
        videos = sorted(path for path in root.rglob("*") if path.is_file() and path.suffix.lower() in VIDEO_EXTENSIONS)
        if options.max_videos_per_location:
            videos = videos[: options.max_videos_per_location]
        existing_counts = scan_existing_library_counts(source_root, location)
        for index, video in enumerate(videos, start=1):
            payload = load_video_text(video, transcript_dir, location, index, options)
            text = payload["text"]
            matches = match_demands(text + "\n" + video.stem)
            if not matches:
                matches = [{"rule": None, "count": 0, "evidence": ""}]
            matched_keywords = [item["rule"].keyword for item in matches if item["rule"]]
            for item in matches:
                rule = item["rule"]
                if not rule:
                    continue
                demand_count = int(item["count"])
                existing = existing_counts.get(rule.keyword, 0)
                status = gap_status(existing, demand_count, options.min_existing_clips)
                search_terms = [f"{location}{term}" for term in rule.search_terms]
                location_keyword_counts[location][rule.keyword] += demand_count
                location_category_counts[location][rule.category] += demand_count
                for term in search_terms:
                    location_search_terms[location][term] += max(1, demand_count)
                keyword_rows.append(
                    {
                        "location": location,
                        "keyword": rule.keyword,
                        "category": rule.category,
                        "demand_hits": demand_count,
                        "existing_clips_estimate": existing,
                        "gap_status": status,
                        "suggested_searches": "；".join(search_terms[:4]),
                        "note": rule.note,
                        "source_title": clean_title(video.stem),
                        "evidence": item["evidence"],
                    }
                )
            video_rows.append(
                {
                    "location": location,
                    "source_title": clean_title(video.stem),
                    "text_source": payload["source"],
                    "text_chars": len(text),
                    "keywords": "；".join(sorted(set(matched_keywords))) or "待人工看文案",
                    "summary": compact_text(text, 220),
                    "local_path": str(video),
                }
            )
            transcript_rows.append(
                {
                    "location": location,
                    "source_title": clean_title(video.stem),
                    "text_source": payload["source"],
                    "transcript": text,
                    "local_path": str(video),
                }
            )

    search_rows = build_search_rows(location_roots, location_keyword_counts, location_search_terms, source_root, options.min_existing_clips)
    stats = build_stats(video_rows, keyword_rows, search_rows, location_keyword_counts, location_category_counts)

    paths = {
        "videos_csv": output_dir / "原视频文案索引.csv",
        "keywords_csv": output_dir / "素材需求关键词明细.csv",
        "search_csv": output_dir / "建议采集搜索词.csv",
        "transcripts_csv": output_dir / "原视频完整文案.csv",
        "json": output_dir / "material_demand.json",
        "report": output_dir / "素材需求雷达报告.md",
        "feishu_xml": output_dir / "feishu_material_demand.xml",
    }
    write_csv(paths["videos_csv"], video_rows)
    write_csv(paths["keywords_csv"], keyword_rows)
    write_csv(paths["search_csv"], search_rows)
    write_csv(paths["transcripts_csv"], transcript_rows)
    paths["json"].write_text(
        json.dumps(
            {
                "stats": stats,
                "videos": video_rows,
                "keywords": keyword_rows,
                "searches": search_rows,
                "transcripts": transcript_rows,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    paths["report"].write_text(build_markdown_report(stats, search_rows, video_rows), encoding="utf-8")
    paths["feishu_xml"].write_text(build_feishu_xml(stats, search_rows, video_rows), encoding="utf-8")

    feishu_result = {"published": False}
    if options.publish_feishu:
        feishu_result = publish_material_demand_to_feishu(paths["feishu_xml"], output_dir, options.feishu_doc)

    summary = {
        "output_dir": str(output_dir),
        "locations": [location for location, _ in location_roots],
        "videos": len(video_rows),
        "keyword_rows": len(keyword_rows),
        "search_rows": len(search_rows),
        "paths": {key: str(value) for key, value in paths.items()},
        "stats": stats,
        "feishu": feishu_result,
    }
    (output_dir / "run_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def discover_location_roots(source_root: Path, locations: list[str] | None) -> list[tuple[str, Path]]:
    requested = [item.strip() for item in (locations or []) if item.strip()]
    roots: list[tuple[str, Path]] = []
    if requested:
        for location in requested:
            root = source_root / "01_原片素材库" / f"{location}-原视频素材"
            if not root.exists():
                root = source_root / f"{location}-原视频素材"
            if root.exists():
                roots.append((location, root))
        return roots
    candidates = list((source_root / "01_原片素材库").glob("*-原视频素材")) if (source_root / "01_原片素材库").exists() else []
    candidates.extend(source_root.glob("*-原视频素材"))
    for root in sorted(candidates):
        if root.is_dir():
            roots.append((root.name.replace("-原视频素材", ""), root))
    return roots


def load_video_text(video: Path, transcript_dir: Path, location: str, index: int, options: MaterialDemandOptions) -> dict[str, str]:
    sidecar = video.with_suffix(".txt")
    cache = transcript_dir / f"{sanitize_name(location)}_{index:03d}_{sanitize_name(video.stem, 'video')}.json"
    if options.force_transcribe or (options.transcribe_audio and not sidecar.exists()):
        if cache.exists() and not options.force_transcribe:
            payload = json.loads(cache.read_text(encoding="utf-8"))
        else:
            payload = transcribe_video_with_segments(video)
            cache.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return {"source": "audio_whisper_tiny", "text": str(payload.get("text") or "")}
    if sidecar.exists():
        return {"source": "same_stem_txt", "text": sidecar.read_text(encoding="utf-8", errors="ignore")}
    if options.transcribe_audio:
        payload = transcribe_video_with_segments(video)
        cache.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return {"source": "audio_whisper_tiny", "text": str(payload.get("text") or "")}
    return {"source": "title_only", "text": video.stem}


def match_demands(text: str) -> list[dict[str, object]]:
    normalized = normalize_keyword_text(text)
    results: list[dict[str, object]] = []
    for rule in DEMAND_RULES:
        hits: list[str] = []
        for pattern in rule.patterns:
            if re.search(re.escape(pattern), normalized, flags=re.IGNORECASE):
                hits.append(pattern)
        if hits:
            results.append({"rule": rule, "count": len(hits), "evidence": "、".join(hits[:8])})
    return results


def scan_existing_library_counts(source_root: Path, location: str) -> dict[str, int]:
    library = source_root / "02_分镜素材库" / f"{location}智能镜头分类"
    if not library.exists():
        library = source_root / f"{location}智能镜头分类"
    counts: dict[str, int] = {}
    if not library.exists():
        return counts
    for rule in DEMAND_RULES:
        total = 0
        for path in library.rglob("*.mp4"):
            hay = normalize_keyword_text(str(path.relative_to(library)))
            if rule.keyword in hay or any(pattern in hay for pattern in rule.patterns):
                total += 1
        counts[rule.keyword] = total
    return counts


def gap_status(existing: int, demand_hits: int, min_existing: int) -> str:
    if existing <= 0:
        return "缺素材"
    if existing < min_existing or existing < demand_hits * 2:
        return "偏少"
    return "够用"


def build_search_rows(
    location_roots: list[tuple[str, Path]],
    keyword_counts: dict[str, Counter[str]],
    search_terms: dict[str, Counter[str]],
    source_root: Path,
    min_existing: int,
) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for location, _ in location_roots:
        existing_counts = scan_existing_library_counts(source_root, location)
        for rule in DEMAND_RULES:
            demand = keyword_counts[location].get(rule.keyword, 0)
            if demand == 0:
                continue
            existing = existing_counts.get(rule.keyword, 0)
            status = gap_status(existing, demand, min_existing)
            priority = priority_score(status, demand, existing)
            rows.append(
                {
                    "priority": priority,
                    "location": location,
                    "keyword": rule.keyword,
                    "category": rule.category,
                    "demand_hits": demand,
                    "existing_clips_estimate": existing,
                    "gap_status": status,
                    "recommended_search": f"{location}{rule.search_terms[0]}",
                    "search_bundle": "；".join(f"{location}{term}" for term in rule.search_terms),
                    "why_collect": rule.note,
                }
            )
        for term, count in search_terms[location].most_common():
            _ = term, count
    rows.sort(key=lambda row: (int(row["priority"]), -int(row["demand_hits"]), int(row["existing_clips_estimate"])))
    return rows


def priority_score(status: str, demand: int, existing: int) -> int:
    if status == "缺素材":
        return 1
    if status == "偏少" and demand >= 2:
        return 2
    if status == "偏少":
        return 3
    if demand >= 4:
        return 4
    return 5


def build_stats(
    videos: list[dict[str, object]],
    keyword_rows: list[dict[str, object]],
    search_rows: list[dict[str, object]],
    keyword_counts: dict[str, Counter[str]],
    category_counts: dict[str, Counter[str]],
) -> dict[str, object]:
    by_location = {}
    for location in sorted({str(row["location"]) for row in videos}):
        by_location[location] = {
            "videos": sum(1 for row in videos if row["location"] == location),
            "top_keywords": dict(keyword_counts[location].most_common(12)),
            "top_categories": dict(category_counts[location].most_common()),
            "missing_or_weak": sum(1 for row in search_rows if row["location"] == location and row["gap_status"] in {"缺素材", "偏少"}),
        }
    return {
        "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "videos": len(videos),
        "keyword_rows": len(keyword_rows),
        "search_rows": len(search_rows),
        "priority_search_rows": sum(1 for row in search_rows if int(row["priority"]) <= 2),
        "by_location": by_location,
    }


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("", encoding="utf-8-sig")
        return
    with path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def build_markdown_report(stats: dict[str, object], search_rows: list[dict[str, object]], videos: list[dict[str, object]]) -> str:
    lines = [
        "# 素材需求雷达报告",
        "",
        f"- 生成时间: {stats['generated_at']}",
        f"- 原视频文案数: {stats['videos']}",
        f"- 关键词命中行: {stats['keyword_rows']}",
        f"- 建议采集关键词: {stats['search_rows']}",
        f"- 优先采集项: {stats['priority_search_rows']}",
        "",
        "## 优先采集关键词",
        "",
    ]
    for row in search_rows[:80]:
        lines.append(
            f"- P{row['priority']}｜{row['location']}｜{row['keyword']}｜{row['gap_status']}｜"
            f"建议搜: {row['search_bundle']}"
        )
    lines.extend(["", "## 原视频文案摘要", ""])
    for row in videos[:120]:
        lines.append(f"- {row['location']}｜{row['source_title']}｜{row['keywords']}｜{row['summary']}")
    return "\n".join(lines)


def build_feishu_xml(stats: dict[str, object], search_rows: list[dict[str, object]], videos: list[dict[str, object]]) -> str:
    now = xml_text(str(stats["generated_at"]))
    lines = [
        f"<h2>素材需求雷达｜原视频文案关键词｜{now}</h2>",
        "<p>用途：先从原视频文案/音频里反推“该采集什么素材”。飞书里不放本地地址，完整路径留在本地 CSV/JSON。</p>",
        "<h3>一、优先采集搜索词</h3>",
        "<table>",
        '<colgroup><col width="55"/><col width="70"/><col width="130"/><col width="90"/><col width="70"/><col width="70"/><col width="300"/><col width="180"/></colgroup>',
        "<thead><tr><th>优先级</th><th>地点</th><th>关键词</th><th>缺口</th><th>文案命中</th><th>已有估算</th><th>建议搜索词</th><th>为什么要采</th></tr></thead>",
        "<tbody>",
    ]
    for row in search_rows[:100]:
        lines.append(
            "<tr>"
            f"<td>P{xml_text(str(row['priority']))}</td>"
            f"<td>{xml_text(str(row['location']))}</td>"
            f"<td>{xml_text(str(row['keyword']))}</td>"
            f"<td>{xml_text(str(row['gap_status']))}</td>"
            f"<td>{xml_text(str(row['demand_hits']))}</td>"
            f"<td>{xml_text(str(row['existing_clips_estimate']))}</td>"
            f"<td>{xml_text(str(row['search_bundle']))}</td>"
            f"<td>{xml_text(str(row['why_collect']))}</td>"
            "</tr>"
        )
    lines.extend(["</tbody>", "</table>", "<h3>二、原视频文案摘要</h3>", "<table>"])
    lines.append('<colgroup><col width="70"/><col width="260"/><col width="240"/><col width="430"/></colgroup>')
    lines.append("<thead><tr><th>地点</th><th>原视频标题</th><th>命中关键词</th><th>文案摘要</th></tr></thead><tbody>")
    for row in videos[:120]:
        lines.append(
            "<tr>"
            f"<td>{xml_text(str(row['location']))}</td>"
            f"<td>{xml_text(str(row['source_title']))}</td>"
            f"<td>{xml_text(str(row['keywords']))}</td>"
            f"<td>{xml_text(str(row['summary']))}</td>"
            "</tr>"
        )
    lines.extend(["</tbody>", "</table>"])
    return "\n".join(lines)


def publish_material_demand_to_feishu(xml_path: Path, cwd: Path, doc: str) -> dict[str, object]:
    lark_cli = find_lark_cli()
    if not lark_cli:
        return {"published": False, "error": "lark-cli not found"}
    env = os.environ.copy()
    env["LARKSUITE_CLI_NO_UPDATE_NOTIFIER"] = "1"
    env["LARKSUITE_CLI_NO_SKILLS_NOTIFIER"] = "1"
    result = subprocess.run(
        lark_argv(
            lark_cli,
            [
                "docs",
                "+update",
                "--as",
                "user",
                "--doc",
                doc,
                "--command",
                "append",
                "--doc-format",
                "xml",
                "--content",
                f"@{xml_path.name}",
            ],
        ),
        cwd=str(cwd),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=180,
        env=env,
        check=False,
    )
    trace = {
        "doc": doc,
        "xml": str(xml_path),
        "returncode": result.returncode,
        "stdout": result.stdout[-1500:],
        "stderr": result.stderr[-1500:],
        "parsed": parse_lark_json(result.stdout),
    }
    (cwd / "feishu_material_demand_trace.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2), encoding="utf-8")
    return {
        "published": result.returncode == 0,
        "doc": doc,
        "returncode": result.returncode,
        "stdout": result.stdout[-1200:],
        "stderr": result.stderr[-1200:],
        "xml": str(xml_path),
    }


def clean_title(value: str) -> str:
    text = re.sub(r"\s+", " ", value).strip()
    return text[:180]


def compact_text(value: str, limit: int) -> str:
    text = re.sub(r"\s+", " ", value).strip()
    return text if len(text) <= limit else text[: limit - 1] + "…"
