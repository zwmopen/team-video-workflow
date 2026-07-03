from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import csv
import json
import re
import shutil

from .ffmpeg_utils import find_ffmpeg, find_ffprobe, probe_video, run_command
from .path_utils import ensure_unique_path, sanitize_name
from .reference_recomposer import concat_segments, extract_reference_audio, mux_audio, render_video_segment, write_concat_file


@dataclass(slots=True)
class RoughCutFromBoardOptions:
    board_json: Path
    output: Path | None = None
    audio_file: Path | None = None
    feedback_file: Path | None = None
    reference_video: Path | None = None
    width: int = 1080
    height: int = 1920
    fps: int = 30
    max_clips_per_beat: int = 5
    keep_temp: bool = False


def build_rough_cut_from_board(options: RoughCutFromBoardOptions) -> dict[str, object]:
    board_json = options.board_json.expanduser().resolve()
    if not board_json.exists():
        raise FileNotFoundError(f"Board JSON does not exist: {board_json}")
    board = json.loads(board_json.read_text(encoding="utf-8"))
    title = str(board.get("title") or board_json.parent.name or "rough_cut")
    output = options.output or (board_json.parent / "成品区_自动粗剪")
    output = output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)

    ffmpeg = find_ffmpeg()
    ffprobe = find_ffprobe()
    segments_dir = output / "segments"
    pack_dir = output / "jianying_pack"
    temp_dir = output / "_tmp"
    for directory in [segments_dir, pack_dir, temp_dir]:
        directory.mkdir(parents=True, exist_ok=True)

    audio_path = resolve_audio_path(options, board, output, ffmpeg)
    audio_duration = probe_duration(audio_path, ffmpeg, ffprobe) if audio_path else 0.0
    rows = list(board.get("rows") or [])
    feedback = load_feedback(options.feedback_file)
    rows = apply_feedback_to_rows(rows, feedback)
    planned_durations = allocate_row_durations(rows, audio_duration)

    segment_paths: list[Path] = []
    plan_rows: list[dict[str, object]] = []
    used_pack_names: set[str] = set()
    for row, target_duration in zip(rows, planned_durations):
        rendered, plan = render_row_segments(
            row=row,
            target_duration=target_duration,
            output=output,
            segments_dir=segments_dir,
            pack_dir=pack_dir,
            used_pack_names=used_pack_names,
            max_clips=options.max_clips_per_beat,
            width=options.width,
            height=options.height,
            fps=options.fps,
            ffmpeg=ffmpeg,
            ffprobe=ffprobe,
        )
        segment_paths.extend(rendered)
        plan_rows.extend(plan)

    if not segment_paths:
        raise RuntimeError("No visual segments were rendered from the board candidates.")

    concat_path = temp_dir / "concat.txt"
    visual_track = output / "visual_track.mp4"
    write_concat_file(concat_path, segment_paths)
    concat_segments(concat_path, visual_track, ffmpeg)

    rough_cut = ""
    if audio_path:
        final_path = output / "rough_cut.mp4"
        mux_audio(visual_track, audio_path, final_path, ffmpeg)
        rough_cut = str(final_path)

    write_rough_cut_plan_csv(output / "rough_cut_plan.csv", plan_rows)
    write_rough_cut_script(output / "script.txt", rows)
    summary = {
        "title": title,
        "board_json": str(board_json),
        "output": str(output),
        "audio_path": str(audio_path) if audio_path else "",
        "feedback_file": str(options.feedback_file or ""),
        "visual_track": str(visual_track),
        "rough_cut": rough_cut,
        "jianying_pack": str(pack_dir),
        "beats": len(rows),
        "rendered_segments": len(segment_paths),
        "plan_csv": str(output / "rough_cut_plan.csv"),
        "script_file": str(output / "script.txt"),
    }
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    write_rough_cut_readme(output / "README.md", summary, plan_rows)
    if not options.keep_temp:
        shutil.rmtree(temp_dir, ignore_errors=True)
    return summary


def resolve_audio_path(options: RoughCutFromBoardOptions, board: dict[str, object], output: Path, ffmpeg: Path) -> Path | None:
    if options.audio_file:
        audio = options.audio_file.expanduser().resolve()
        if not audio.exists():
            raise FileNotFoundError(f"Audio file does not exist: {audio}")
        return audio
    if options.reference_video:
        reference_video = options.reference_video.expanduser().resolve()
        if not reference_video.exists():
            raise FileNotFoundError(f"Reference video does not exist: {reference_video}")
        audio_path = output / "reference_audio.m4a"
        extract_reference_audio(reference_video, audio_path, ffmpeg)
        return audio_path
    board_audio = str(board.get("audio_path") or "").strip()
    if board_audio:
        audio = Path(board_audio).expanduser()
        if audio.exists():
            return audio.resolve()
    return None


def load_feedback(path: Path | None) -> dict[int, dict[str, str]]:
    if not path:
        return {}
    feedback_path = path.expanduser().resolve()
    if not feedback_path.exists():
        raise FileNotFoundError(f"Feedback file does not exist: {feedback_path}")
    rows: list[dict[str, object]]
    if feedback_path.suffix.lower() == ".json":
        payload = json.loads(feedback_path.read_text(encoding="utf-8"))
        if isinstance(payload, dict):
            rows = list(payload.get("rows") or [])
        elif isinstance(payload, list):
            rows = payload
        else:
            rows = []
    else:
        rows = read_feedback_csv(feedback_path)
    result: dict[int, dict[str, str]] = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        index_text = str(first_value(row, ["镜号", "row", "index", "镜头", "序号"]) or "").strip()
        try:
            index = int(index_text)
        except ValueError:
            continue
        result[index] = {str(key): str(value or "") for key, value in row.items()}
    return result


def read_feedback_csv(path: Path) -> list[dict[str, object]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def apply_feedback_to_rows(rows: list[dict[str, object]], feedback: dict[int, dict[str, str]]) -> list[dict[str, object]]:
    if not feedback:
        return rows
    output_rows: list[dict[str, object]] = []
    for row in rows:
        index = int(row.get("index") or 0)
        item = dict(row)
        feedback_row = feedback.get(index, {})
        decision = first_value(feedback_row, ["确认结果", "status", "decision", "结果"]) or ""
        note = first_value(feedback_row, ["备注", "note"]) or ""
        selected = first_value(feedback_row, ["选中素材序号", "selected_candidate", "candidate", "素材序号"]) or ""
        if any(word in decision for word in ["废料", "跳过", "不用", "删除", "不需要"]):
            continue
        candidates = list(item.get("candidates") or [])
        selected_rank = parse_selected_rank(selected)
        if selected_rank:
            preferred = [candidate for candidate in candidates if int(candidate.get("rank") or 0) == selected_rank]
            rest = [candidate for candidate in candidates if int(candidate.get("rank") or 0) != selected_rank]
            if preferred:
                item["candidates"] = preferred + rest
        if note:
            item["review_note"] = note
        if decision:
            item["review_status"] = decision
        output_rows.append(item)
    return output_rows


def first_value(row: dict[str, object], keys: list[str]) -> str:
    for key in keys:
        if key in row and str(row[key]).strip():
            return str(row[key]).strip()
    return ""


def parse_selected_rank(value: str) -> int | None:
    match = re.search(r"\d+", value)
    if not match:
        return None
    rank = int(match.group(0))
    return rank if rank > 0 else None


def allocate_row_durations(rows: list[dict[str, object]], audio_duration: float) -> list[float]:
    durations: list[float] = []
    for row in rows:
        start = maybe_float(row.get("start"))
        end = maybe_float(row.get("end"))
        if start is not None and end is not None and end > start:
            durations.append(max(0.4, end - start))
        else:
            text = str(row.get("script_text") or "")
            durations.append(1.2 if len(text) < 16 else 1.8 if len(text) < 32 else 2.6)
    if audio_duration > 0 and durations:
        total = sum(durations)
        if total > 0:
            return [max(0.4, item * audio_duration / total) for item in durations]
    return durations


def render_row_segments(
    row: dict[str, object],
    target_duration: float,
    output: Path,
    segments_dir: Path,
    pack_dir: Path,
    used_pack_names: set[str],
    max_clips: int,
    width: int,
    height: int,
    fps: int,
    ffmpeg: Path,
    ffprobe: Path | None,
) -> tuple[list[Path], list[dict[str, object]]]:
    index = int(row.get("index") or 0)
    script_text = str(row.get("script_text") or "")
    candidates = [item for item in list(row.get("candidates") or []) if Path(str(item.get("clip_path") or "")).exists()]
    if not candidates:
        return [], [
            {
                "index": index,
                "clip_index": "",
                "script_text": script_text,
                "target_duration": round(target_duration, 3),
                "render_duration": 0,
                "clip_path": "",
                "pack_path": "",
                "segment_path": "",
                "status": "missing_candidate",
            }
        ]
    rendered: list[Path] = []
    plan_rows: list[dict[str, object]] = []
    remaining = max(0.4, target_duration)
    selected = candidates[: max(1, max_clips)]
    for clip_index, candidate in enumerate(selected, start=1):
        source_path = Path(str(candidate.get("clip_path"))).resolve()
        duration = probe_duration(source_path, ffmpeg, ffprobe) or 1.2
        render_duration = min(max(0.4, remaining), max(0.4, min(duration, 2.4)))
        label = sanitize_name(str(row.get("visual_need") or row.get("target_keyword") or "clip"))

        pack_name = f"{index:03d}_{clip_index:02d}_{label}_{source_path.name}"
        if pack_name in used_pack_names:
            pack_path = ensure_unique_path(pack_dir / pack_name)
        else:
            pack_path = pack_dir / pack_name
        used_pack_names.add(pack_path.name)
        shutil.copy2(source_path, pack_path)

        segment_path = ensure_unique_path(segments_dir / f"{index:03d}_{clip_index:02d}_{label}.mp4")
        render_video_segment(source_path, segment_path, render_duration, width, height, fps, ffmpeg)
        rendered.append(segment_path)
        plan_rows.append(
            {
                "index": index,
                "clip_index": clip_index,
                "script_text": script_text,
                "target_duration": round(target_duration, 3),
                "render_duration": round(render_duration, 3),
                "clip_path": str(source_path),
                "pack_path": str(pack_path),
                "segment_path": str(segment_path),
                "status": "rendered",
            }
        )
        remaining -= render_duration
        if remaining <= 0.2:
            break
    return rendered, plan_rows


def probe_duration(path: Path | None, ffmpeg: Path, ffprobe: Path | None) -> float:
    if not path or not path.exists():
        return 0.0
    try:
        return float(probe_video(path, ffmpeg, ffprobe).get("duration") or 0.0)
    except Exception:
        return 0.0


def maybe_float(value: object) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def write_rough_cut_plan_csv(path: Path, rows: list[dict[str, object]]) -> None:
    fields = [
        "index",
        "clip_index",
        "script_text",
        "target_duration",
        "render_duration",
        "clip_path",
        "pack_path",
        "segment_path",
        "status",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def write_rough_cut_script(path: Path, rows: list[dict[str, object]]) -> None:
    lines = []
    for row in rows:
        index = int(row.get("index") or len(lines) + 1)
        start = row.get("start")
        end = row.get("end")
        text = str(row.get("script_text") or "").strip()
        if start not in (None, "") and end not in (None, ""):
            lines.append(f"{index:03d} {start} --> {end} {text}")
        else:
            lines.append(f"{index:03d} {text}")
    path.write_text("\n".join(lines), encoding="utf-8")


def write_rough_cut_readme(path: Path, summary: dict[str, object], rows: list[dict[str, object]]) -> None:
    lines = [
        f"# {summary['title']} 自动粗剪",
        "",
        f"- Visual track: `{summary['visual_track']}`",
        f"- Rough cut: `{summary['rough_cut'] or '未合成音频，仅输出 visual_track.mp4'}`",
        f"- Jianying pack: `{summary['jianying_pack']}`",
        f"- Plan CSV: `{summary['plan_csv']}`",
        "",
        "## Segments",
        "",
        "| 镜号 | 素材序 | 时长 | 状态 | 台词 |",
        "| --- | --- | ---: | --- | --- |",
    ]
    for row in rows:
        text = str(row.get("script_text") or "").replace("|", " ")
        lines.append(f"| {row.get('index')} | {row.get('clip_index')} | {row.get('render_duration')} | {row.get('status')} | {text} |")
    path.write_text("\n".join(lines), encoding="utf-8")
