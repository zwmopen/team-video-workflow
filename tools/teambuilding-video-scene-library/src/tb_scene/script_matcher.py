from __future__ import annotations

from pathlib import Path
import csv
import re
import shutil
import sqlite3
from dataclasses import dataclass

from .path_utils import ensure_unique_path, sanitize_name


@dataclass(slots=True)
class ScriptBeat:
    index: int
    text: str
    target_category: str
    target_subcategory: str
    suggested_duration: float


KEYWORD_TARGETS: list[tuple[str, str, str]] = [
    (r"大巴|上车|下车|集合|出发|抵达|车程|返程大巴|路上", "02_出发抵达", "大巴集合出发"),
    (r"山路|盘山|到达营地|到达目的地", "02_出发抵达", "山路抵达"),
    (r"民宿|酒店|住宿|房间|卧室|床|推窗|阳台|湖景房|包栋|别墅", "03_住宿空间", "酒店民宿房间"),
    (r"庭院|院子|草坪民宿", "03_住宿空间", "民宿庭院"),
    (r"泳池|泳池民宿|泳池别墅", "03_住宿空间", "民宿泳池"),
    (r"露台|湖景露台|阳台看景", "03_住宿空间", "湖景露台"),
    (r"鱼宴|鱼头|船头鱼|土鸡汤|农家菜|菜品|吃饭|吃一顿|聚餐|餐桌|热菜", "04_餐饮美食", "农家菜"),
    (r"早餐|早饭", "04_餐饮美食", "早餐"),
    (r"下午茶|茶点|水果|饮品|咖啡", "04_餐饮美食", "下午茶点"),
    (r"皮划艇|划艇|桨板|划船|水上皮划艇", "05_项目活动", "皮划艇"),
    (r"游艇|游船|游湖|坐船|船上|湖面包船", "05_项目活动", "游艇游湖"),
    (r"摩托艇|水上摩托|快艇", "05_项目活动", "摩托艇"),
    (r"漂流|龙王山|尖叫|水花四溅|刺激", "05_项目活动", "漂流"),
    (r"水上乐园|水乐园|水上闯关|水上游戏|碰碰船|香蕉船", "05_项目活动", "水上乐园"),
    (r"水上拓展|水上挑战|水上团建", "05_项目活动", "水上拓展"),
    (r"溯溪|溪谷|峡谷玩水|溪流|打水仗", "05_项目活动", "溯溪玩水"),
    (r"骑行|环湖骑行|湖边骑行|自行车", "05_项目活动", "湖边骑行"),
    (r"真人CS|镭战|水弹|射击|对抗", "05_项目活动", "真人CS"),
    (r"越野车|ATV|UTV|山地越野|茶山越野", "05_项目活动", "山地越野车"),
    (r"彩虹滑道|滑草|高山滑道|滑道", "05_项目活动", "高山滑道"),
    (r"高空滑索|滑索|飞拉达|高空拓展|玻璃栈道", "05_项目活动", "高空滑索"),
    (r"射箭|弓箭", "05_项目活动", "射箭"),
    (r"麻将|棋牌", "05_项目活动", "麻将"),
    (r"KTV|唱歌|轰趴|台球|桌游|剧本杀|狼人杀|电竞", "05_项目活动", "KTV唱歌"),
    (r"团队游戏|破冰|挑战|分组|协作|趣味运动会|草坪团建|团建游戏", "06_团队互动", "团队游戏挑战"),
    (r"合影|合照|大合照|团队照", "06_团队互动", "团队合照"),
    (r"欢呼|加油|击掌|笑|开心|爆笑|氛围", "08_人物反应", "开心反应"),
    (r"烧烤|烤肉|烤串|BBQ|烤全羊|炭火", "07_烧烤露营夜场", "烧烤"),
    (r"露营|天幕|营地|帐篷|围炉", "07_烧烤露营夜场", "露营"),
    (r"篝火|火光|围着火", "07_烧烤露营夜场", "篝火"),
    (r"烟花|仙女棒|跨年", "07_烧烤露营夜场", "烟花"),
    (r"夜场|夜间|音乐|晚会|夜间娱乐", "07_烧烤露营夜场", "夜间娱乐"),
    (r"返程|回程|结束|收尾|告别|最后", "10_收尾返程", "返程大巴"),
    (r"航拍|俯拍|云海|山景|湖景|风景|空镜|转场|开场|目的地|千岛湖|莫干山|安吉|竹林|瀑布", "01_环境空镜", ""),
]


def build_edit_pack(library_root: Path, script_text: str, title: str, output: Path | None = None) -> dict[str, object]:
    library_root = library_root.expanduser().resolve()
    if output is None:
        output = library_root.parent / f"{sanitize_name(title)}_智能配镜"
    output = output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)

    records = load_clip_records(library_root)
    beats = split_script(script_text)
    rows: list[dict[str, str | int | float]] = []
    used_outputs: set[str] = set()
    last_source = ""

    for beat in beats:
        candidates = rank_candidates(records, beat, used_outputs, last_source)
        if not candidates:
            rows.append(plan_row(beat, "", "", "", "未匹配"))
            continue
        chosen = candidates[0]
        source_path = Path(str(chosen["output_path"]))
        safe_label = sanitize_name(beat.target_subcategory or beat.target_category.split("_", 1)[-1])
        dest = ensure_unique_path(output / f"{beat.index:03d}_{safe_label}_{source_path.name}")
        shutil.copy2(source_path, dest)
        used_outputs.add(str(chosen["output_path"]))
        last_source = str(chosen.get("source_video_path", ""))
        rows.append(plan_row(beat, str(dest), str(chosen.get("primary_category", "")), str(chosen.get("source_video_name", "")), "已匹配"))

    (output / "文案.txt").write_text(script_text, encoding="utf-8")
    write_plan_csv(output / "配镜表.csv", rows)
    write_plan_md(output / "配镜说明.md", title, rows)
    return {
        "library_root": str(library_root),
        "output": str(output),
        "beats": len(beats),
        "matched": len([row for row in rows if row["status"] == "已匹配"]),
        "unmatched": len([row for row in rows if row["status"] != "已匹配"]),
    }


def load_clip_records(library_root: Path) -> list[dict[str, object]]:
    db_path = library_root / "._系统记录" / "project.sqlite"
    if db_path.exists():
        con = sqlite3.connect(db_path)
        con.row_factory = sqlite3.Row
        try:
            rows = []
            for row in con.execute("SELECT record_json FROM scenes WHERE processing_status = 'written'"):
                if row["record_json"]:
                    import json

                    data = json.loads(row["record_json"])
                    if data.get("output_path") and Path(data["output_path"]).exists():
                        rows.append(data)
            return rows
        finally:
            con.close()

    csv_path = library_root / "._系统记录" / "scenes.csv"
    if csv_path.exists():
        with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
            return [row for row in csv.DictReader(handle) if row.get("processing_status") == "written" and row.get("output_path")]
    return scan_clip_records_from_files(library_root)


def scan_clip_records_from_files(library_root: Path) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    for path in sorted(library_root.rglob("*.mp4")):
        if any(part.startswith("._") or part in {"segments", "jianying_pack", "_tmp"} for part in path.parts):
            continue
        try:
            rel = path.relative_to(library_root)
        except ValueError:
            rel = Path(path.name)
        parts = rel.parts
        category = infer_category_from_parts(parts)
        subcategory = infer_subcategory_from_parts(parts)
        quality = infer_quality_from_filename(path.name)
        source_id = infer_source_id(path.name)
        records.append(
            {
                "output_path": str(path),
                "primary_category": category,
                "category_top1": subcategory,
                "semantic_tags": " ".join([path.stem, subcategory, category]),
                "quality_level": quality,
                "source_video_path": source_id,
                "source_video_name": source_id or path.name,
                "duration": 1.2,
                "processing_status": "written",
            }
        )
    return records


def infer_category_from_parts(parts: tuple[str, ...]) -> str:
    for part in parts:
        if re.match(r"^\d{2}_", part):
            return part
    return ""


def infer_subcategory_from_parts(parts: tuple[str, ...]) -> str:
    for index, part in enumerate(parts):
        if re.match(r"^\d{2}_", part) and index + 1 < len(parts) - 1:
            return parts[index + 1]
    if len(parts) >= 2:
        return parts[-2]
    return ""


def infer_quality_from_filename(name: str) -> str:
    match = re.search(r"__(S|A|B|C)_", name)
    return match.group(1) if match else "B"


def infer_source_id(name: str) -> str:
    match = re.search(r"__(?:S|A|B|C)_(V\d+)_S\d+", name)
    return match.group(1) if match else ""


def split_script(script_text: str) -> list[ScriptBeat]:
    clean_text = normalize_script_text(script_text)
    chunks = [item.strip() for item in re.split(r"[\n。！？!?；;，,、｜|]+", clean_text) if item.strip()]
    chunks = expand_sparse_script(clean_text, chunks)
    beats: list[ScriptBeat] = []
    index = 1
    buffer = ""
    for chunk in chunks:
        if not buffer:
            buffer = chunk
        elif should_merge_chunks(buffer, chunk):
            buffer = f"{buffer}，{chunk}"
        else:
            beats.append(make_beat(index, buffer))
            index += 1
            buffer = chunk
    if buffer:
        beats.append(make_beat(index, buffer))
    return beats


def normalize_script_text(script_text: str) -> str:
    text = script_text.replace("\ufeff", "").strip()
    text = re.sub(r"#\S+", " ", text)
    text = re.sub(r"(?i)(Day\s*\d+)", r"。\1", text)
    text = re.sub(r"(?<!\d)([01]?\d{3}|2[0-3]\d{2})[｜|:：]", r"。\1：", text)
    text = re.sub(r"[✅📅💦🔥🍖🏡🌿🥐🎋🍲🎢🥏🚗💡🌧️🚌🥘🛶📍⭐️❗️‼️~～]+", "，", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def expand_sparse_script(text: str, chunks: list[str]) -> list[str]:
    meaningful = [chunk for chunk in chunks if len(chunk) >= 2]
    if len(meaningful) >= 4:
        return meaningful

    location = infer_location(text)
    expanded = [f"{location}团建，两天一夜可以这样玩"] if location else ["团建两天一夜可以这样玩"]
    expanded.append("公司集合出发，路上先把期待感拉起来")
    expanded.append(f"抵达{location or '目的地'}，先看一波风景空镜")
    if re.search(r"民宿|住宿|酒店|包栋|房间|别墅", text):
        expanded.append("入住民宿酒店，住宿空间和环境都安排好")
    expanded.append("中午安排本地特色餐饮，大家先吃好喝好")

    activity_lines = []
    for pattern, line in [
        (r"皮划艇|划艇|桨板", "下午安排皮划艇，湖面玩水更有参与感"),
        (r"游艇|游船|游湖|坐船", "坐船游湖，看湖景也能拍出氛围感"),
        (r"漂流", "漂流项目直接把刺激感拉满"),
        (r"水球|玩水|溯溪|水上", "玩水互动很适合夏季团建破冰"),
        (r"骑行", "环湖骑行适合轻松一点的团队"),
        (r"越野|ATV|山地", "山地越野适合想要刺激项目的团队"),
        (r"滑车|滑道|云上草原|高空", "高山游乐项目适合做团建高潮段"),
        (r"真人CS|镭战|射击", "真人CS适合团队分组对抗"),
        (r"KTV|轰趴|麻将|桌游|剧本杀", "室内轰趴项目适合晚上继续热场"),
    ]:
        if re.search(pattern, text, flags=re.IGNORECASE):
            activity_lines.append(line)
    expanded.extend(activity_lines or ["下午安排团建项目，大家一起玩起来"])

    expanded.append("团队互动和合照，把氛围感留下来")
    if re.search(r"烧烤|烤全羊|露营|篝火|烟花|夜", text):
        expanded.append("晚上安排露营烧烤，篝火夜场把情绪推高")
    expanded.append("第二天轻松收尾返程，适合公司团建直接照着安排")
    return expanded


def infer_location(text: str) -> str:
    for location in ["千岛湖", "安吉", "莫干山", "舟山", "阳澄湖", "天目湖", "溧阳"]:
        if location in text:
            return location
    return ""


def should_merge_chunks(buffer: str, chunk: str) -> bool:
    if len(buffer) >= 18:
        return False
    current = make_beat(0, buffer)
    incoming = make_beat(0, chunk)
    current_target = (current.target_category, current.target_subcategory)
    incoming_target = (incoming.target_category, incoming.target_subcategory)
    return current_target == incoming_target


def make_beat(index: int, text: str) -> ScriptBeat:
    category = "90_待人工分类"
    subcategory = ""
    duration = 1.2 if len(text) < 16 else 1.8 if len(text) < 32 else 2.6
    concrete_first_targets = {
        "皮划艇",
        "游艇游湖",
        "摩托艇",
        "漂流",
        "水上乐园",
        "水上拓展",
        "溯溪玩水",
        "湖边骑行",
        "真人CS",
        "山地越野车",
        "高山滑道",
        "高空滑索",
        "射箭",
        "麻将",
        "KTV唱歌",
        "烧烤",
        "露营",
        "篝火",
        "烟花",
    }
    for pattern, target_category, target_subcategory in KEYWORD_TARGETS:
        if target_subcategory in concrete_first_targets and re.search(pattern, text, flags=re.IGNORECASE):
            return ScriptBeat(
                index=index,
                text=text,
                target_category=target_category,
                target_subcategory=target_subcategory,
                suggested_duration=duration,
            )
    priority_targets = [
        (r"赏景|看景|湖景|山景|风景|目的地", "01_环境空镜", ""),
        (r"适合.*团建|团建.*适合|杭州周边团建|上海周边团建|公司团建", "06_团队互动", "团队合照"),
        (r"餐饮|本地特色|吃好喝好|吃住行|逛吃|吃好玩好住好|中午安排.*吃|午餐|晚餐", "04_餐饮美食", "菜品餐桌"),
        (r"玩水|水上项目|水上活动|夏日玩水|玩水安排", "05_项目活动", "水上乐园"),
        (r"丛林越野|山地越野|越野车|越野", "05_项目活动", "山地越野车"),
        (r"篝火|火光|围着火", "07_烧烤露营夜场", "篝火"),
        (r"烧烤|烤肉|烤串|BBQ|烤全羊|炭火", "07_烧烤露营夜场", "烧烤"),
        (r"露营|天幕|营地|帐篷|围炉", "07_烧烤露营夜场", "露营"),
        (r"团队合照|团队照|大合照|合影|合照", "06_团队互动", "团队合照"),
        (r"团队氛围|团队互动|一起玩|一起闹|一起笑", "06_团队互动", "团队游戏挑战"),
    ]
    for pattern, target_category, target_subcategory in priority_targets:
        if re.search(pattern, text, flags=re.IGNORECASE):
            return ScriptBeat(
                index=index,
                text=text,
                target_category=target_category,
                target_subcategory=target_subcategory,
                suggested_duration=duration,
            )
    if re.search(r"不想只吃饭|不是只吃饭|不只是吃饭|不止吃饭|不想.*吃饭聚餐", text):
        return ScriptBeat(
            index=index,
            text=text,
            target_category="06_团队互动",
            target_subcategory="团队游戏挑战",
            suggested_duration=duration,
        )
    for pattern, target_category, target_subcategory in KEYWORD_TARGETS:
        if re.search(pattern, text, flags=re.IGNORECASE):
            category = target_category
            subcategory = target_subcategory
            break
    if category == "90_待人工分类" and re.search(r"团建|江浙沪|目的地|适合|好玩|不累|烦恼|公司|HR|人均|安排|攻略|两天一夜|两天一晚", text, flags=re.IGNORECASE):
        category = "01_环境空镜"
    return ScriptBeat(index=index, text=text, target_category=category, target_subcategory=subcategory, suggested_duration=duration)


def rank_candidates(
    records: list[dict[str, object]],
    beat: ScriptBeat,
    used_outputs: set[str],
    last_source: str,
) -> list[dict[str, object]]:
    scored: list[tuple[int, dict[str, object]]] = []
    for record in records:
        output_path = str(record.get("output_path", ""))
        if not output_path or output_path in used_outputs:
            continue
        score = 0
        relevance = 0
        primary = str(record.get("primary_category", ""))
        category_top = str(record.get("category_top1", ""))
        semantic = str(record.get("semantic_tags", ""))
        source = str(record.get("source_video_path", ""))
        quality = str(record.get("quality_level", "B"))
        if primary == beat.target_category:
            relevance += 40
        if beat.target_subcategory and beat.target_subcategory in category_top:
            relevance += 45
        if beat.target_subcategory and beat.target_subcategory in output_path:
            relevance += 25
        if any(token and token in semantic for token in [beat.target_subcategory, beat.target_category.split("_", 1)[-1]]):
            relevance += 10
        if beat.target_category != "90_待人工分类" and relevance <= 0:
            continue
        score += relevance
        if source and source != last_source:
            score += 8
        if quality == "S":
            score += 8
        elif quality == "A":
            score += 5
        elif quality == "B":
            score += 2
        if beat.target_category == "90_待人工分类":
            score -= 20
        if score > 0:
            scored.append((score, record))
    scored.sort(key=lambda item: item[0], reverse=True)
    return [record for _, record in scored]


def plan_row(beat: ScriptBeat, output_path: str, category: str, source_video: str, status: str) -> dict[str, str | int | float]:
    return {
        "index": beat.index,
        "script_text": beat.text,
        "target_category": beat.target_category,
        "target_subcategory": beat.target_subcategory,
        "suggested_duration": beat.suggested_duration,
        "clip_path": output_path,
        "matched_category": category,
        "source_video": source_video,
        "status": status,
    }


def write_plan_csv(path: Path, rows: list[dict[str, str | int | float]]) -> None:
    fields = [
        "index",
        "script_text",
        "target_category",
        "target_subcategory",
        "suggested_duration",
        "clip_path",
        "matched_category",
        "source_video",
        "status",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def write_plan_md(path: Path, title: str, rows: list[dict[str, str | int | float]]) -> None:
    lines = [f"# {title} 配镜说明", "", "| 编号 | 口播内容 | 目标分类 | 匹配状态 |", "| --- | --- | --- | --- |"]
    for row in rows:
        target = str(row["target_category"])
        if row["target_subcategory"]:
            target = f"{target}/{row['target_subcategory']}"
        lines.append(f"| {row['index']:03d} | {row['script_text']} | {target} | {row['status']} |")
    path.write_text("\n".join(lines), encoding="utf-8")
