from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from collections import Counter, defaultdict
import csv
import hashlib
import html
import json
import math
import os
import re
import shutil
import subprocess
from datetime import datetime

from .ffmpeg_utils import find_ffmpeg, find_ffprobe, probe_video, run_command
from .models import VIDEO_EXTENSIONS, SceneCut
from .path_utils import sanitize_name, seconds_to_timestamp
from .scene_detector import detect_scenes


DEFAULT_SOURCE_ROOTS = [
    Path(r"D:\Download\素材下载\团建视频\安吉-原视频素材"),
    Path(r"D:\Download\素材下载\团建视频\千岛湖-原视频素材"),
    Path(r"D:\Download\素材下载\团建视频\莫干山-原视频素材"),
]

DEFAULT_OUTPUT_ROOT = Path(r"D:\Download\素材下载\团建视频\00-模板库\参考成片学习库")
DEFAULT_FEISHU_DOC = "https://my.feishu.cn/docx/YanldTyg5oPwgnxahn6cCIGdnbd"


@dataclass(slots=True)
class ReferenceLearnOptions:
    source_roots: list[Path]
    output_root: Path | None = None
    run_name: str = ""
    max_videos: int = 5
    max_beats_per_video: int = 10
    orientation: str = "vertical"
    detector: str = "adaptive"
    min_scene_len: int = 12
    transcribe_audio: bool = False
    transcribe_missing: bool = False
    publish_feishu: bool = False
    feishu_doc: str = DEFAULT_FEISHU_DOC
    feishu_image_limit: int = 30


@dataclass(slots=True)
class ReferenceCandidate:
    path: Path
    location: str
    transcript_path: Path | None
    transcript_chars: int
    duration: float
    width: int
    height: int
    fps: float
    orientation: str
    letterbox_ratio: float
    score: float
    reasons: list[str]


KEYWORD_RULES: list[tuple[str, str, str, str, str]] = [
    (r"大巴|上车|下车|集合|出发|车程|路上|返程|抵达|目的地", "02_出发抵达", "大巴集合出发", "direct", "出发/抵达镜头"),
    (r"航拍|俯拍|风景|山水|湖景|山景|竹海|竹林|翠竹|山林|山野|云海|空镜|湖光|山色|避暑|治愈|千岛湖|莫干山|安吉|目的地|转场", "01_环境空镜", "风景空镜俯拍", "transition", "环境交代/转场"),
    (r"民宿|酒店|住宿|房间|床|别墅|包栋|窗|阳台|庭院|泳池", "03_住宿空间", "酒店民宿房间", "direct", "住宿空间"),
    (r"吃|饭|菜|鱼宴|船头鱼|土鸡|黄牛肉|农家菜|餐桌|聚餐|碰杯|早餐|湖鲜|烤全羊", "04_餐饮美食", "餐饮美食", "direct", "吃饭/菜品"),
    (r"烧烤|烤肉|烤串|稍烤|BBQ|炭火|围炉|烤全羊", "07_烧烤露营夜场", "烧烤烤全羊", "direct", "夜场吃喝"),
    (r"露营|营地|天幕|帐篷|草坪|围坐|晚风", "07_烧烤露营夜场", "露营营地", "atmosphere", "露营氛围"),
    (r"篝火|火光|围着火|烟花|仙女棒|夜场|音乐|晚会|KTV|唱歌|轰趴", "07_烧烤露营夜场", "篝火烟花夜场", "direct", "夜间氛围高潮"),
    (r"温泉|泡汤|汤池", "05_项目活动", "温泉泡汤", "direct", "休闲项目"),
    (r"皮划艇|皮划挺|皮花艇|皮花挺|划艇|划船|桨板|船桨|划桨", "05_项目活动", "皮划艇", "direct", "水上项目"),
    (r"游艇|油艇|油挺|游船|坐船|游湖|油湖|船上|湖面|快艇|摩托艇", "05_项目活动", "游艇游湖", "direct", "湖面船类项目"),
    (r"骑行|自行车|环湖骑行|湖边骑行", "05_项目活动", "湖边骑行", "direct", "骑行项目"),
    (r"漂流|龙王山|水花四溅|尖叫|刺激", "05_项目活动", "漂流", "direct", "刺激项目"),
    (r"水上乐园|水乐园|玩水|水球|打水仗|溯溪|溪谷|峡谷|水上拓展|水上挑战", "05_项目活动", "峡谷玩水水上拓展", "direct", "玩水项目"),
    (r"真人CS|镭战|水弹|射击|对抗", "05_项目活动", "真人CS镭战", "direct", "对抗项目"),
    (r"越野|ATV|UTV|山地车|山地滑车|滑道|滑草|高空|滑索", "05_项目活动", "山地游乐项目", "direct", "山地项目"),
    (r"飞盘|棒球|拔河|破冰|团队游戏|挑战|分组|协作|竞速|趣味运动会", "06_团队互动", "团队游戏挑战", "direct", "团队互动"),
    (r"合照|合影|大合照|团队照|拍照", "06_团队互动", "团队合照", "direct", "团队记录"),
    (r"笑|开心|欢呼|尖叫|氛围|热闹|快乐|爆笑|比耶", "08_人物反应", "开心人物反应", "atmosphere", "情绪反应"),
    (r"特写|细节|水花|手部|装备|火焰|食材|烤串|船桨", "09_细节特写", "细节特写", "transition", "细节补画面"),
    (r"结束|收尾|告别|最后|回程|第二天|早餐返程", "10_收尾返程", "收尾返程", "transition", "收尾镜头"),
]


def learn_reference_videos(options: ReferenceLearnOptions) -> dict[str, object]:
    source_roots = [root.expanduser().resolve() for root in (options.source_roots or DEFAULT_SOURCE_ROOTS)]
    output_root = (options.output_root or DEFAULT_OUTPUT_ROOT).expanduser().resolve()
    run_name = options.run_name or datetime.now().strftime("%Y%m%d_%H%M%S_成片学习")
    output_dir = output_root / sanitize_name(run_name)
    keyframe_dir = output_dir / "keyframes"
    sheets_dir = output_dir / "contact_sheets"
    output_dir.mkdir(parents=True, exist_ok=True)
    keyframe_dir.mkdir(parents=True, exist_ok=True)
    sheets_dir.mkdir(parents=True, exist_ok=True)

    ffmpeg = find_ffmpeg()
    ffprobe = find_ffprobe()
    candidates = discover_reference_candidates(source_roots, ffmpeg, ffprobe, options.transcribe_missing)
    selected = select_reference_candidates(candidates, options.max_videos, options.orientation)

    rows: list[dict[str, object]] = []
    video_summaries: list[dict[str, object]] = []
    for video_index, candidate in enumerate(selected, start=1):
        transcript_payload = load_reference_transcript(
            candidate,
            output_dir,
            video_index,
            force_audio=options.transcribe_audio,
            transcribe_missing=options.transcribe_missing,
        )
        beats = [item["text"] for item in transcript_payload["segments"][: options.max_beats_per_video]]
        ranges = [(float(item["start"]), float(item["end"])) for item in transcript_payload["segments"][: options.max_beats_per_video]]
        if not beats:
            beats = [candidate.path.stem]
            ranges = allocate_ranges(beats, candidate.duration)
        cuts = detect_scenes(candidate.path, candidate.duration, options.detector, None, options.min_scene_len)
        cut_rows: list[dict[str, object]] = []

        for beat_index, (line, (line_start, line_end)) in enumerate(zip(beats, ranges), start=1):
            scene = choose_scene_for_range(cuts, line_start, line_end, candidate.duration)
            keyword = infer_visual_keyword(line, candidate.path.stem)
            frame_time = choose_frame_time(scene, line_start, line_end, candidate.duration)
            frame_path = keyframe_dir / f"V{video_index:03d}_B{beat_index:03d}_{sanitize_name(keyword['visual_keyword'], 'frame')}.jpg"
            extract_frame(ffmpeg, candidate.path, frame_time, frame_path)
            row = build_learning_row(
                candidate=candidate,
                video_index=video_index,
                beat_index=beat_index,
                line=line,
                line_start=line_start,
                line_end=line_end,
                scene=scene,
                keyword=keyword,
                frame_path=frame_path,
            )
            rows.append(row)
            cut_rows.append(row)

        sheet_path = sheets_dir / f"V{video_index:03d}_{sanitize_name(candidate.location)}_contact_sheet.jpg"
        build_contact_sheet([Path(str(row["keyframe_path"])) for row in cut_rows], sheet_path)
        video_summaries.append(
            {
                "video_index": video_index,
                "location": candidate.location,
                "path": str(candidate.path),
                "transcript_path": str(candidate.transcript_path or ""),
                "duration": candidate.duration,
                "width": candidate.width,
                "height": candidate.height,
                "orientation": candidate.orientation,
                "letterbox_ratio": round(candidate.letterbox_ratio, 3),
                "beats": len(cut_rows),
                "contact_sheet": str(sheet_path) if sheet_path.exists() else "",
                "selection_reasons": "; ".join(candidate.reasons),
                "transcript_source": transcript_payload["source"],
                "transcript_path": transcript_payload["path"],
            }
        )

    stats = build_statistics(rows, selected, candidates)
    csv_path = output_dir / "成片学习表.csv"
    json_path = output_dir / "reference_learning.json"
    md_path = output_dir / "成片学习报告.md"
    feishu_md_path = output_dir / "feishu_reference_learning.md"
    write_learning_csv(csv_path, rows)
    write_learning_json(json_path, rows, video_summaries, stats)
    write_learning_report(md_path, rows, video_summaries, stats)
    write_feishu_markdown(feishu_md_path, rows, video_summaries, stats)

    feishu_result: dict[str, object] = {"published": False}
    if options.publish_feishu:
        feishu_result = publish_to_feishu(
            output_dir=output_dir,
            rows=rows,
            videos=video_summaries,
            stats=stats,
            doc=options.feishu_doc,
            image_limit=options.feishu_image_limit,
        )

    summary = {
        "output_dir": str(output_dir),
        "source_roots": [str(root) for root in source_roots],
        "candidates_found": len(candidates),
        "selected_videos": len(selected),
        "rows": len(rows),
        "csv": str(csv_path),
        "json": str(json_path),
        "report": str(md_path),
        "feishu_markdown": str(feishu_md_path),
        "feishu": feishu_result,
        "statistics": stats,
        "videos": video_summaries,
    }
    (output_dir / "run_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def discover_reference_candidates(
    source_roots: list[Path],
    ffmpeg: Path,
    ffprobe: Path | None,
    transcribe_missing: bool,
) -> list[ReferenceCandidate]:
    candidates: list[ReferenceCandidate] = []
    for root in source_roots:
        if not root.exists():
            continue
        location = infer_location_from_root(root)
        for path in sorted(root.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in VIDEO_EXTENSIONS:
                continue
            transcript_path = path.with_suffix(".txt")
            transcript_chars = 0
            if transcript_path.exists():
                transcript_chars = len(read_text(transcript_path))
            elif not transcribe_missing:
                transcript_path = None
            try:
                info = probe_video(path, ffmpeg, ffprobe)
            except Exception:
                continue
            width = int(info.get("width") or 0)
            height = int(info.get("height") or 0)
            duration = float(info.get("duration") or 0.0)
            if width <= 0 or height <= 0 or duration <= 0:
                continue
            orientation = classify_orientation(width, height)
            letterbox_ratio = estimate_letterbox_ratio(ffmpeg, path, duration) if orientation in {"vertical", "near_vertical"} else 0.0
            score, reasons = score_candidate(path, location, transcript_chars, duration, width, height, orientation, letterbox_ratio)
            candidates.append(
                ReferenceCandidate(
                    path=path,
                    location=location,
                    transcript_path=transcript_path if transcript_path and transcript_path.exists() else None,
                    transcript_chars=transcript_chars,
                    duration=duration,
                    width=width,
                    height=height,
                    fps=float(info.get("fps") or 0.0),
                    orientation=orientation,
                    letterbox_ratio=letterbox_ratio,
                    score=score,
                    reasons=reasons,
                )
            )
    return sorted(candidates, key=lambda item: item.score, reverse=True)


def score_candidate(
    path: Path,
    location: str,
    transcript_chars: int,
    duration: float,
    width: int,
    height: int,
    orientation: str,
    letterbox_ratio: float,
) -> tuple[float, list[str]]:
    score = 0.0
    reasons: list[str] = []
    title = path.stem
    if orientation == "vertical":
        score += 50
        reasons.append("竖屏优先")
    elif orientation == "near_vertical":
        score += 35
        reasons.append("偏竖屏可学习")
    else:
        score -= 50
        reasons.append("横屏只作音频/文案参考")
    if transcript_chars >= 180:
        score += min(35, transcript_chars / 18)
        reasons.append(f"有较完整同名文案 {transcript_chars} 字")
    elif transcript_chars >= 100:
        score += 14
        reasons.append(f"有同名文案 {transcript_chars} 字")
    elif transcript_chars > 0:
        score -= 6
        reasons.append(f"短文案 {transcript_chars} 字")
    else:
        score -= 20
        reasons.append("暂无同名文案")
    if 18 <= duration <= 120:
        score += 15
        reasons.append("时长适合口播/混剪学习")
    elif duration < 18:
        score -= 8
        reasons.append("时长偏短")
    else:
        score -= 5
        reasons.append("时长偏长")
    if width >= 720 or height >= 1280:
        score += 8
        reasons.append("分辨率可用")
    if letterbox_ratio >= 0.62:
        score -= 90
        reasons.append(f"疑似横屏套竖屏黑边 {letterbox_ratio:.2f}")
    elif letterbox_ratio >= 0.45:
        score -= 35
        reasons.append(f"可能存在明显黑边 {letterbox_ratio:.2f}")
    if re.search(r"vlog|两天一夜|2天1夜|攻略|方案|夏|玩水|漂流|烧烤|皮划艇|团建", title, re.IGNORECASE):
        score += 8
        reasons.append("标题含典型团建结构")
    if location and location in title:
        score += 6
        reasons.append("标题地点明确")
    if re.search(r"合集|8大|照抄|收藏|神仙团建地|目的地整理", title):
        score -= 35
        reasons.append("疑似混合地点合集，降权")
    return score, reasons


def select_reference_candidates(
    candidates: list[ReferenceCandidate],
    max_videos: int,
    orientation: str,
) -> list[ReferenceCandidate]:
    if orientation == "vertical":
        pool = [item for item in candidates if item.orientation in {"vertical", "near_vertical"}]
    else:
        pool = list(candidates)
    complete_script_pool = [item for item in pool if item.transcript_chars >= 100]
    if len(complete_script_pool) >= min(max_videos, 3):
        pool = complete_script_pool
    selected: list[ReferenceCandidate] = []
    seen_paths: set[Path] = set()
    by_location: dict[str, list[ReferenceCandidate]] = defaultdict(list)
    for item in pool:
        by_location[item.location].append(item)
    max_per_location = max(1, math.ceil(max_videos / max(1, len(by_location))))
    location_counts: Counter[str] = Counter()
    for location in sorted(by_location):
        if len(selected) >= max_videos:
            break
        first = by_location[location][0]
        selected.append(first)
        seen_paths.add(first.path)
        location_counts[location] += 1
    for item in pool:
        if len(selected) >= max_videos:
            break
        if item.path in seen_paths:
            continue
        if location_counts[item.location] >= max_per_location and any(
            location_counts[location] < max_per_location for location in by_location
        ):
            continue
        selected.append(item)
        seen_paths.add(item.path)
        location_counts[item.location] += 1
    return selected


def load_reference_transcript(
    candidate: ReferenceCandidate,
    output_dir: Path,
    video_index: int,
    force_audio: bool,
    transcribe_missing: bool,
) -> dict[str, object]:
    transcript_dir = output_dir / "transcripts"
    transcript_dir.mkdir(parents=True, exist_ok=True)
    json_path = transcript_dir / f"V{video_index:03d}_{sanitize_name(candidate.location)}_whisper.json"
    if force_audio:
        if not json_path.exists():
            payload = transcribe_video_with_segments(candidate.path)
            json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        else:
            payload = json.loads(json_path.read_text(encoding="utf-8"))
        segments = normalize_timed_segments(payload.get("segments") or [], candidate.duration)
        return {"source": "audio_whisper_tiny", "path": str(json_path), "segments": segments}

    if candidate.transcript_path and candidate.transcript_path.exists():
        text = read_text(candidate.transcript_path)
        beats = split_reference_text(text, 999)
        ranges = allocate_ranges(beats, candidate.duration)
        return {
            "source": "same_stem_txt_estimated_time",
            "path": str(candidate.transcript_path),
            "segments": [
                {"start": start, "end": end, "text": beat}
                for beat, (start, end) in zip(beats, ranges)
            ],
        }

    if transcribe_missing:
        if not json_path.exists():
            payload = transcribe_video_with_segments(candidate.path)
            json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        else:
            payload = json.loads(json_path.read_text(encoding="utf-8"))
        segments = normalize_timed_segments(payload.get("segments") or [], candidate.duration)
        return {"source": "audio_whisper_tiny_missing_txt", "path": str(json_path), "segments": segments}

    return {
        "source": "filename_estimated_time",
        "path": "",
        "segments": [{"start": 0.0, "end": candidate.duration, "text": candidate.path.stem}],
    }


def transcribe_video(path: Path) -> str:
    import whisper

    model = whisper.load_model("tiny")
    result = model.transcribe(str(path), language="zh", fp16=False, verbose=False)
    return str(result.get("text") or "").strip()


def transcribe_video_with_segments(path: Path) -> dict[str, object]:
    import whisper

    model = whisper.load_model("tiny")
    result = model.transcribe(str(path), language="zh", fp16=False, verbose=False)
    return {
        "text": str(result.get("text") or "").strip(),
        "segments": [
            {
                "start": float(segment.get("start") or 0.0),
                "end": float(segment.get("end") or 0.0),
                "text": str(segment.get("text") or "").strip(),
            }
            for segment in result.get("segments", [])
            if str(segment.get("text") or "").strip()
        ],
    }


def normalize_timed_segments(raw_segments: list[dict[str, object]], duration: float) -> list[dict[str, object]]:
    segments: list[dict[str, object]] = []
    for segment in raw_segments:
        text = strip_social_tail(str(segment.get("text") or "").strip())
        if not text:
            continue
        start = max(0.0, float(segment.get("start") or 0.0))
        end = min(duration, max(start + 0.4, float(segment.get("end") or start + 0.4)))
        if end - start > 8.0:
            for chunk in split_reference_text(text, 999):
                chunk_start = start
                chunk_end = min(end, chunk_start + max(0.8, (end - start) / max(1, len(text) / max(1, len(chunk)))))
                segments.append({"start": chunk_start, "end": chunk_end, "text": chunk})
                start = chunk_end
        else:
            segments.append({"start": start, "end": end, "text": text})
    return segments


def split_reference_text(text: str, max_beats: int) -> list[str]:
    cleaned = re.sub(r"#\S+", " ", text.replace("\ufeff", " "))
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    if not cleaned:
        return []
    cleaned = re.sub(r"(?<!\d)([01]?\d{3}|2[0-3]\d{2})", r"。\1", cleaned)
    cleaned = re.sub(r"(Day\s*\d+|DAY\s*\d+)", r"。\1", cleaned)
    raw_chunks = [chunk.strip() for chunk in re.split(r"[。！？!?；;｜|,\n，×*]+", cleaned) if chunk.strip()]
    chunks: list[str] = []
    buffer = ""
    for chunk in raw_chunks:
        chunk = strip_social_tail(chunk)
        if not chunk:
            continue
        if not buffer:
            buffer = chunk
        elif len(buffer) < 18 and len(buffer) + len(chunk) <= 36:
            buffer = f"{buffer}，{chunk}"
        else:
            chunks.append(buffer)
            buffer = chunk
    if buffer:
        chunks.append(buffer)
    return chunks[:max_beats]


def strip_social_tail(text: str) -> str:
    return re.sub(r"(点击头像|私信|评论区|领取方案|拿方案|关注我|主页).*", "", text).strip()


def allocate_ranges(beats: list[str], duration: float) -> list[tuple[float, float]]:
    if duration <= 0:
        duration = max(1.0, len(beats) * 2.0)
    weights = [max(8, len(beat)) for beat in beats]
    total = sum(weights) or len(beats)
    ranges: list[tuple[float, float]] = []
    cursor = 0.0
    for index, weight in enumerate(weights, start=1):
        if index == len(weights):
            end = duration
        else:
            end = min(duration, cursor + duration * weight / total)
        if end - cursor < 0.7:
            end = min(duration, cursor + 0.7)
        ranges.append((cursor, end))
        cursor = end
    return ranges


def choose_scene_for_range(cuts: list[SceneCut], start: float, end: float, duration: float) -> SceneCut:
    midpoint = (start + end) / 2.0
    best = None
    best_overlap = -1.0
    for cut in cuts:
        overlap = max(0.0, min(end, cut.end_time) - max(start, cut.start_time))
        if cut.start_time <= midpoint <= cut.end_time:
            overlap += 999
        if overlap > best_overlap:
            best = cut
            best_overlap = overlap
    return best or SceneCut("S001", 0.0, duration)


def choose_frame_time(scene: SceneCut, line_start: float, line_end: float, duration: float) -> float:
    start = max(scene.start_time, line_start, 0.0)
    end = min(scene.end_time, line_end, duration)
    if end <= start:
        start = max(0.0, scene.start_time)
        end = min(duration, scene.end_time)
    return max(0.0, min(duration, (start + end) / 2.0))


def infer_visual_keyword(line: str, title: str) -> dict[str, str]:
    normalized_line = normalize_keyword_text(line)
    for pattern, category, keyword, match_type, role in KEYWORD_RULES:
        if re.search(pattern, normalized_line, flags=re.IGNORECASE):
            return {
                "primary_category": category,
                "visual_keyword": keyword,
                "match_type": match_type,
                "shot_role": role,
                "matched_pattern": pattern,
            }
    return {
        "primary_category": "90_待人工分类",
        "visual_keyword": "待画面复核",
        "match_type": "fallback",
        "shot_role": "待复核",
        "matched_pattern": "",
    }


def normalize_keyword_text(text: str) -> str:
    replacements = {
        "島": "岛",
        "來": "来",
        "體": "体",
        "驗": "验",
        "燒": "烧",
        "烤": "烤",
        "夥": "伙",
        "電": "电",
        "說": "说",
        "這": "这",
        "麼": "么",
        "號": "号",
        "與": "与",
        "臺": "台",
        "台": "台",
        "劃": "划",
        "槳": "桨",
        "會": "会",
        "間": "间",
        "龍": "龙",
        "灣": "湾",
        "稍烤": "烧烤",
    }
    normalized = text
    for source, target in replacements.items():
        normalized = normalized.replace(source, target)
    return normalized


def build_learning_row(
    candidate: ReferenceCandidate,
    video_index: int,
    beat_index: int,
    line: str,
    line_start: float,
    line_end: float,
    scene: SceneCut,
    keyword: dict[str, str],
    frame_path: Path,
) -> dict[str, object]:
    reason = (
        f"台词/文案命中“{keyword['visual_keyword']}”，已抽取该时间段关键帧；"
        "最终以关键帧画面复核为准，字幕和口播只作辅助证据。"
    )
    return {
        "reference_video": candidate.path.name,
        "location_or_theme": candidate.location,
        "video_index": video_index,
        "line_index": beat_index,
        "spoken_line": line,
        "line_start": seconds_to_timestamp(line_start),
        "line_end": seconds_to_timestamp(line_end),
        "shot_id": scene.scene_id,
        "shot_start": seconds_to_timestamp(scene.start_time),
        "shot_end": seconds_to_timestamp(scene.end_time),
        "duration": round(max(0.0, line_end - line_start), 3),
        "visual_keyword": keyword["visual_keyword"],
        "primary_category": keyword["primary_category"],
        "shot_role": keyword["shot_role"],
        "match_type": keyword["match_type"],
        "matched_pattern": keyword["matched_pattern"],
        "keyframe_path": str(frame_path),
        "source_video_path": str(candidate.path),
        "why_this_picture": reason,
        "quality_note": "待视觉复核",
        "visual_review_status": "keyframe_extracted_needs_ai_or_human_review",
    }


def extract_frame(ffmpeg: Path, video_path: Path, timestamp: float, output: Path) -> bool:
    output.parent.mkdir(parents=True, exist_ok=True)
    result = run_command(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{timestamp:.3f}",
            "-i",
            video_path,
            "-frames:v",
            "1",
            "-vf",
            "scale=540:-1",
            "-q:v",
            "2",
            output,
        ],
        timeout=90,
    )
    return result.returncode == 0 and output.exists()


def estimate_letterbox_ratio(ffmpeg: Path, video_path: Path, duration: float) -> float:
    try:
        temp_root = Path(os.environ.get("TEMP") or os.environ.get("TMP") or r"C:\Windows\Temp")
        temp_dir = temp_root / "tb_scene_reference_probe"
        temp_dir.mkdir(parents=True, exist_ok=True)
        digest = hashlib.md5(str(video_path).encode("utf-8", errors="ignore")).hexdigest()
        frame = temp_dir / f"{digest}.jpg"
        timestamp = min(max(0.8, duration * 0.48), max(0.1, duration - 0.2))
        ok = extract_frame(ffmpeg, video_path, timestamp, frame)
        if not ok:
            return 0.0
        from PIL import Image

        with Image.open(frame) as image:
            gray = image.convert("L")
            width, height = gray.size
            band = max(1, int(height * 0.18))
            top = gray.crop((0, 0, width, band))
            bottom = gray.crop((0, height - band, width, height))
            pixels = list(top.getdata()) + list(bottom.getdata())
            if not pixels:
                return 0.0
            return sum(1 for value in pixels if value <= 14) / len(pixels)
    except Exception:
        return 0.0


def build_contact_sheet(frame_paths: list[Path], output: Path) -> bool:
    existing = [path for path in frame_paths if path.exists()]
    if not existing:
        return False
    try:
        from PIL import Image, ImageDraw
    except Exception:
        return False
    thumbs = []
    for index, path in enumerate(existing, start=1):
        with Image.open(path) as image:
            image = image.convert("RGB")
            image.thumbnail((220, 390))
            canvas = Image.new("RGB", (240, 430), "white")
            x = (240 - image.width) // 2
            canvas.paste(image, (x, 10))
            draw = ImageDraw.Draw(canvas)
            draw.text((10, 400), f"B{index:03d}", fill=(0, 0, 0))
            thumbs.append(canvas)
    columns = min(4, len(thumbs))
    rows = int(math.ceil(len(thumbs) / columns))
    sheet = Image.new("RGB", (columns * 240, rows * 430), "white")
    for index, thumb in enumerate(thumbs):
        x = (index % columns) * 240
        y = (index // columns) * 430
        sheet.paste(thumb, (x, y))
    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output, quality=92)
    return True


def build_statistics(
    rows: list[dict[str, object]],
    selected: list[ReferenceCandidate],
    candidates: list[ReferenceCandidate],
) -> dict[str, object]:
    keyword_counts = Counter(str(row["visual_keyword"]) for row in rows)
    category_counts = Counter(str(row["primary_category"]) for row in rows)
    match_counts = Counter(str(row["match_type"]) for row in rows)
    durations = [float(row["duration"]) for row in rows if float(row["duration"]) > 0]
    return {
        "candidate_videos": len(candidates),
        "selected_videos": len(selected),
        "learned_rows": len(rows),
        "keyword_counts": dict(keyword_counts.most_common()),
        "category_counts": dict(category_counts.most_common()),
        "match_type_counts": dict(match_counts.most_common()),
        "avg_beat_duration": round(sum(durations) / len(durations), 3) if durations else 0.0,
        "direct_match_ratio": round(match_counts.get("direct", 0) / len(rows), 3) if rows else 0.0,
        "learning_warning": "当前为MVP：时间对齐按文案权重估算，关键帧已抽取；画面关键词需要批量视觉模型或飞书复核回写。",
    }


def write_learning_csv(path: Path, rows: list[dict[str, object]]) -> None:
    fieldnames = [
        "reference_video",
        "location_or_theme",
        "video_index",
        "line_index",
        "spoken_line",
        "line_start",
        "line_end",
        "shot_id",
        "shot_start",
        "shot_end",
        "duration",
        "visual_keyword",
        "primary_category",
        "shot_role",
        "match_type",
        "keyframe_path",
        "source_video_path",
        "why_this_picture",
        "quality_note",
        "visual_review_status",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def write_learning_json(
    path: Path,
    rows: list[dict[str, object]],
    videos: list[dict[str, object]],
    stats: dict[str, object],
) -> None:
    payload = {"videos": videos, "rows": rows, "statistics": stats}
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def write_learning_report(
    path: Path,
    rows: list[dict[str, object]],
    videos: list[dict[str, object]],
    stats: dict[str, object],
) -> None:
    lines = [
        "# 成片学习报告",
        "",
        "## 本轮结论",
        f"- 候选视频：{stats['candidate_videos']} 条",
        f"- 入选竖屏参考：{stats['selected_videos']} 条",
        f"- 生成台词-画面学习行：{stats['learned_rows']} 行",
        f"- 平均每句估算时长：{stats['avg_beat_duration']} 秒",
        f"- 直接关键词匹配比例：{stats['direct_match_ratio']}",
        "",
        "## 已选参考成片",
    ]
    for video in videos:
        lines.append(f"- V{video['video_index']:03d}｜{video['location']}｜{video['beats']} 行｜{video['path']}")
    lines.extend(["", "## 关键词分布"])
    for keyword, count in dict(stats["keyword_counts"]).items():
        lines.append(f"- {keyword}: {count}")
    lines.extend(["", "## 前 20 行样例"])
    for row in rows[:20]:
        lines.append(
            f"- V{row['video_index']:03d}-B{row['line_index']:03d}｜{row['spoken_line']} "
            f"=> {row['visual_keyword']}｜{row['keyframe_path']}"
        )
    lines.extend(
        [
            "",
            "## 已知限制",
            "- 当前先用同名文案或转文字结果做台词切句；没有逐字时间戳时，时间范围按文案长度估算。",
            "- 画面关键帧已经抽出，但批量视觉模型识别仍需下一步接入；本轮表格明确标注待视觉复核。",
            "- 学习结果用于升级配镜规则，不移动、不覆盖原视频。",
        ]
    )
    path.write_text("\n".join(lines), encoding="utf-8")


def write_feishu_markdown(
    path: Path,
    rows: list[dict[str, object]],
    videos: list[dict[str, object]],
    stats: dict[str, object],
) -> None:
    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    lines = [
        f"## 成片学习表｜{now}",
        "",
        "这是一轮 MVP 学习：先挑 3-5 条竖屏参考成片，拆成台词/画面行，抽关键帧，后续按飞书批注回写规则。",
        "",
        "| 镜号 | 地点 | 台词/文案 | 画面关键词 | 分类 | 截图路径 | 素材路径 | 选择理由 | 状态 |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for row in rows[:30]:
        lines.append(
            "| "
            + " | ".join(
                [
                    md_cell(f"V{row['video_index']:03d}-B{row['line_index']:03d}"),
                    md_cell(str(row["location_or_theme"])),
                    md_cell(str(row["spoken_line"])),
                    md_cell(str(row["visual_keyword"])),
                    md_cell(str(row["primary_category"])),
                    md_cell(str(row["keyframe_path"])),
                    md_cell(str(row["source_video_path"])),
                    md_cell(str(row["why_this_picture"])),
                    md_cell("待视觉复核"),
                ]
            )
            + " |"
        )
    lines.extend(["", "### 本轮统计", ""])
    lines.append(f"- 入选参考成片：{stats['selected_videos']} 条")
    lines.append(f"- 学习行：{stats['learned_rows']} 行")
    lines.append(f"- 直接关键词比例：{stats['direct_match_ratio']}")
    lines.append(f"- 平均每句估算时长：{stats['avg_beat_duration']} 秒")
    lines.append("")
    lines.append("### 已选视频")
    for video in videos:
        lines.append(f"- V{video['video_index']:03d}｜{video['location']}｜{video['path']}")
    lines.append("")
    lines.append("### 注意")
    lines.append("画面分类以关键帧为准。台词、字幕和文件名只是辅助证据，发现不准就在飞书状态列标：通过 / 替换 / 废料 / 重配。")
    path.write_text("\n".join(lines), encoding="utf-8")


def publish_to_feishu(
    output_dir: Path,
    rows: list[dict[str, object]],
    videos: list[dict[str, object]],
    stats: dict[str, object],
    doc: str,
    image_limit: int,
) -> dict[str, object]:
    lark_cli = find_lark_cli()
    if not lark_cli:
        return {"published": False, "error": "lark-cli not found"}
    env = os.environ.copy()
    env["LARKSUITE_CLI_NO_UPDATE_NOTIFIER"] = "1"
    env["LARKSUITE_CLI_NO_SKILLS_NOTIFIER"] = "1"
    visual_rows = rows[: max(0, image_limit)]
    media_results: list[dict[str, object]] = []
    for row in visual_rows:
        frame = Path(str(row.get("keyframe_path") or ""))
        if not frame.exists():
            media_results.append({"file": str(frame), "ok": False, "error": "missing keyframe"})
            continue
        try:
            rel = frame.relative_to(output_dir)
        except ValueError:
            continue
        media = run_lark(
            lark_cli,
            [
                "docs",
                "+media-insert",
                "--as",
                "user",
                "--doc",
                doc,
                "--file",
                str(rel),
                "--type",
                "image",
                "--width",
                "180",
                "--align",
                "center",
                "--caption",
                f"tmp_{row.get('video_index')}_{row.get('line_index')}",
            ],
            output_dir,
            env,
            timeout=180,
        )
        parsed = parse_lark_json(media.stdout)
        data = parsed.get("data", {}) if isinstance(parsed, dict) else {}
        media_results.append(
            {
                "file": str(frame),
                "ok": media.returncode == 0 and bool(data.get("file_token")),
                "file_token": str(data.get("file_token") or ""),
                "block_id": str(data.get("block_id") or ""),
                "stdout": media.stdout[-1000:],
                "stderr": media.stderr[-1000:],
            }
        )

    token_by_frame = {
        str(item.get("file")): str(item.get("file_token"))
        for item in media_results
        if item.get("ok") and item.get("file_token")
    }
    xml_path = output_dir / "feishu_visual_table.xml"
    xml_path.write_text(
        build_feishu_visual_table_xml(visual_rows, videos, stats, token_by_frame),
        encoding="utf-8",
    )
    update = run_lark(
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
        output_dir,
        env,
        timeout=180,
    )
    temp_block_ids = [str(item.get("block_id")) for item in media_results if item.get("block_id")]
    delete = None
    if temp_block_ids and update.returncode == 0:
        delete = run_lark(
            lark_cli,
            [
                "docs",
                "+update",
                "--as",
                "user",
                "--doc",
                doc,
                "--command",
                "block_delete",
                "--block-id",
                ",".join(temp_block_ids),
            ],
            output_dir,
            env,
            timeout=180,
        )
    trace = {
        "doc": doc,
        "xml": str(xml_path),
        "rows": len(visual_rows),
        "media": media_results,
        "update_returncode": update.returncode,
        "update_stdout": update.stdout[-1200:],
        "update_stderr": update.stderr[-1200:],
        "delete_returncode": delete.returncode if delete else None,
        "delete_stdout": delete.stdout[-1200:] if delete else "",
        "delete_stderr": delete.stderr[-1200:] if delete else "",
    }
    (output_dir / "feishu_visual_table_trace.json").write_text(
        json.dumps(trace, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return {
        "published": update.returncode == 0,
        "doc": doc,
        "update_returncode": update.returncode,
        "update_stdout": update.stdout[-1200:],
        "update_stderr": update.stderr[-1200:],
        "xml": str(xml_path),
        "media": media_results,
        "temp_blocks_deleted": bool(delete and delete.returncode == 0),
    }


def run_lark(
    lark_cli: Path,
    args: list[str],
    cwd: Path,
    env: dict[str, str],
    timeout: int,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        lark_argv(lark_cli, args),
        cwd=str(cwd),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        env=env,
        check=False,
    )


def parse_lark_json(text: str) -> dict[str, object]:
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"(\{[\s\S]*\})", text)
        if match:
            try:
                return json.loads(match.group(1))
            except json.JSONDecodeError:
                return {}
    return {}


def build_feishu_visual_table_xml(
    rows: list[dict[str, object]],
    videos: list[dict[str, object]],
    stats: dict[str, object],
    token_by_frame: dict[str, str],
) -> str:
    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    lines = [
        f"<h2>成片学习表｜纯图版｜{xml_text(now)}</h2>",
        "<p>表格里直接放截图。素材地址保留在本地 CSV/JSON 机器记录里，飞书里不展示地址。</p>",
        "<table>",
        '<colgroup><col width="80"/><col width="220"/><col width="190"/><col width="110"/><col width="110"/><col width="190"/><col width="80"/></colgroup>',
        "<thead><tr>"
        '<th background-color="light-gray">镜号</th>'
        '<th background-color="light-gray">台词/口播</th>'
        '<th background-color="light-gray">画面截图</th>'
        '<th background-color="light-gray">关键词</th>'
        '<th background-color="light-gray">分类</th>'
        '<th background-color="light-gray">判断理由</th>'
        '<th background-color="light-gray">状态</th>'
        "</tr></thead>",
        "<tbody>",
    ]
    for row in rows:
        frame = str(row.get("keyframe_path") or "")
        token = token_by_frame.get(frame, "")
        image_xml = (
            f'<img src="{xml_text(token)}" width="170" name="{xml_text(Path(frame).name)}"/>'
            if token
            else "<span text-color=\"red\">缺图</span>"
        )
        lines.append(
            "<tr>"
            f"<td>V{int(row.get('video_index') or 0):03d}-B{int(row.get('line_index') or 0):03d}</td>"
            f"<td>{xml_text(str(row.get('spoken_line') or ''))}</td>"
            f"<td>{image_xml}</td>"
            f"<td>{xml_text(str(row.get('visual_keyword') or ''))}</td>"
            f"<td>{xml_text(str(row.get('primary_category') or ''))}</td>"
            f"<td>{xml_text(str(row.get('why_this_picture') or ''))}</td>"
            "<td>待审</td>"
            "</tr>"
        )
    lines.extend(
        [
            "</tbody>",
            "</table>",
            "<p><b>本轮统计：</b>"
            f"参考成片 {xml_text(str(stats.get('selected_videos', len(videos))))} 条，"
            f"学习行 {xml_text(str(stats.get('learned_rows', len(rows))))} 行，"
            f"直接关键词比例 {xml_text(str(stats.get('direct_match_ratio', '')))}。"
            "</p>",
        ]
    )
    return "\n".join(lines)


def xml_text(value: str) -> str:
    return html.escape(value.replace("\n", " ").strip(), quote=True)


def find_lark_cli() -> Path | None:
    found = shutil.which("lark-cli")
    if found:
        return Path(found)
    for candidate in [Path(r"D:\AICode\npm-global\lark-cli.ps1"), Path(r"D:\AICode\npm-global\lark-cli.cmd")]:
        if candidate.exists():
            return candidate
    return None


def lark_argv(lark_cli: Path, args: list[str]) -> list[str]:
    if lark_cli.suffix.lower() == ".ps1":
        return ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(lark_cli), *args]
    return [str(lark_cli), *args]


def md_cell(value: str) -> str:
    return value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").strip()


def read_text(path: Path) -> str:
    for encoding in ["utf-8-sig", "utf-8", "gb18030"]:
        try:
            return path.read_text(encoding=encoding, errors="strict")
        except UnicodeDecodeError:
            continue
    return path.read_text(encoding="utf-8", errors="replace")


def classify_orientation(width: int, height: int) -> str:
    if height >= width * 1.18:
        return "vertical"
    if height > width:
        return "near_vertical"
    if width > height:
        return "horizontal"
    return "square"


def infer_location_from_root(root: Path) -> str:
    name = root.name
    for location in ["安吉", "千岛湖", "莫干山", "舟山", "阳澄湖", "天目湖", "溧阳"]:
        if location in name:
            return location
    return re.sub(r"[-_]?原视频素材.*", "", name) or name
