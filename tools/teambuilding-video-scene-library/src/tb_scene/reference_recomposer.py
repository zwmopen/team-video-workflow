from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import csv
import json
import math
import shutil

from .ffmpeg_utils import find_ffmpeg, find_ffprobe, probe_video, run_command
from .path_utils import ensure_unique_path, sanitize_name
from .script_matcher import ScriptBeat, load_clip_records, make_beat, rank_candidates, split_script


@dataclass(slots=True)
class RecomposeOptions:
    reference_video: Path
    library_root: Path
    title: str
    output: Path | None = None
    script_text: str = ""
    script_file: Path | None = None
    transcribe: bool = False
    width: int = 1080
    height: int = 1920
    fps: int = 30
    max_beats: int | None = None
    keep_temp: bool = False


RELATED_FALLBACK_CATEGORIES: dict[str, tuple[str, ...]] = {
    "01_环境空镜": ("01_环境空镜", "10_收尾返程"),
    "02_出发抵达": ("02_出发抵达", "01_环境空镜"),
    "03_住宿空间": ("03_住宿空间", "01_环境空镜"),
    "04_餐饮美食": ("04_餐饮美食", "07_烧烤露营夜场"),
    "05_项目活动": ("05_项目活动", "06_团队互动", "08_人物反应"),
    "06_团队互动": ("06_团队互动", "08_人物反应", "05_项目活动"),
    "07_烧烤露营夜场": ("07_烧烤露营夜场", "04_餐饮美食", "08_人物反应"),
    "08_人物反应": ("08_人物反应", "06_团队互动", "05_项目活动"),
    "09_细节特写": ("09_细节特写", "05_项目活动", "04_餐饮美食", "07_烧烤露营夜场"),
    "10_收尾返程": ("10_收尾返程", "01_环境空镜", "02_出发抵达"),
}


SUPPORT_FALLBACK_CATEGORIES: tuple[str, ...] = (
    "01_环境空镜",
    "06_团队互动",
    "08_人物反应",
    "09_细节特写",
)


def recompose_reference_video(options: RecomposeOptions) -> dict[str, object]:
    reference_video = options.reference_video.expanduser().resolve()
    library_root = options.library_root.expanduser().resolve()
    if not reference_video.exists():
        raise FileNotFoundError(f"Reference video does not exist: {reference_video}")
    if not library_root.exists():
        raise FileNotFoundError(f"Library root does not exist: {library_root}")

    output = options.output or (library_root.parent / f"{sanitize_name(options.title)}_reference_recompose")
    output = output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)

    ffmpeg = find_ffmpeg()
    ffprobe = find_ffprobe()
    reference_info = probe_video(reference_video, ffmpeg, ffprobe)
    reference_duration = max(0.0, float(reference_info.get("duration") or 0.0))

    script_text = resolve_script_text(options, reference_video, output)
    beats = split_script(script_text)
    if not beats:
        beats = [make_beat(1, reference_video.stem)]
    if options.max_beats:
        beats = beats[: options.max_beats]
    durations = allocate_beat_durations(beats, reference_duration)

    audio_path = output / "reference_audio.m4a"
    extract_reference_audio(reference_video, audio_path, ffmpeg)

    records = load_clip_records(library_root)
    if not records:
        raise RuntimeError(f"No written clip records found in library: {library_root}")

    segments_dir = output / "segments"
    pack_dir = output / "jianying_pack"
    temp_dir = output / "_tmp"
    for directory in [segments_dir, pack_dir, temp_dir]:
        directory.mkdir(parents=True, exist_ok=True)

    rows: list[dict[str, object]] = []
    used_outputs: set[str] = set()
    last_source = ""
    segment_paths: list[Path] = []

    for beat, duration in zip(beats, durations):
        chosen_clips = choose_clips(records, beat, used_outputs, last_source, duration)
        if not chosen_clips:
            rows.append(plan_row(beat, duration, "", "", "", "", "unmatched"))
            continue

        label = sanitize_name(beat.target_subcategory or beat.target_category.split("_", 1)[-1] or "clip")
        pack_paths: list[str] = []
        beat_segment_paths: list[str] = []
        matched_categories: list[str] = []
        source_videos: list[str] = []
        remaining = duration
        for clip_index, chosen in enumerate(chosen_clips, start=1):
            source_path = Path(str(chosen.get("output_path", "")))
            if not source_path.exists():
                continue
            clip_duration = max(0.4, record_duration(chosen))
            render_duration = min(max(0.4, remaining), min(clip_duration, 2.4))

            pack_path = ensure_unique_path(pack_dir / f"{beat.index:03d}_{clip_index:02d}_{label}_{source_path.name}")
            shutil.copy2(source_path, pack_path)
            pack_paths.append(str(pack_path))

            segment_path = segments_dir / f"{beat.index:03d}_{clip_index:02d}_{label}.mp4"
            render_video_segment(source_path, segment_path, render_duration, options.width, options.height, options.fps, ffmpeg)
            segment_paths.append(segment_path)
            beat_segment_paths.append(str(segment_path))

            used_outputs.add(str(chosen.get("output_path", "")))
            last_source = str(chosen.get("source_video_path", ""))
            matched_categories.append(str(chosen.get("primary_category", "")))
            source_videos.append(str(chosen.get("source_video_name", "")))
            remaining -= render_duration
            if remaining <= 0.25:
                break

        if not beat_segment_paths:
            rows.append(plan_row(beat, duration, "", "", "", "", "missing_source_clip"))
            continue
        rows.append(
            plan_row(
                beat,
                duration,
                ";".join(pack_paths),
                ";".join(beat_segment_paths),
                ";".join(dict.fromkeys(item for item in matched_categories if item)),
                ";".join(dict.fromkeys(item for item in source_videos if item)),
                "matched",
            )
        )

    if not segment_paths:
        raise RuntimeError("No visual segments were rendered; the library may not contain usable clips.")

    concat_path = temp_dir / "concat.txt"
    write_concat_file(concat_path, segment_paths)
    visual_track = output / "visual_track.mp4"
    concat_segments(concat_path, visual_track, ffmpeg)
    visual_track = pad_visual_track_if_short(visual_track, reference_duration, temp_dir, ffmpeg, ffprobe)

    final_path = output / "rough_cut.mp4"
    mux_audio(visual_track, audio_path, final_path, ffmpeg)

    (output / "script.txt").write_text(script_text, encoding="utf-8")
    write_plan_csv(output / "recompose_plan.csv", rows)
    write_plan_md(output / "README.md", options.title, reference_video, final_path, rows)

    if not options.keep_temp and temp_dir.exists():
        shutil.rmtree(temp_dir, ignore_errors=True)

    matched = sum(1 for row in rows if row["status"] == "matched")
    summary = {
        "reference_video": str(reference_video),
        "library_root": str(library_root),
        "output": str(output),
        "rough_cut": str(final_path),
        "reference_audio": str(audio_path),
        "script_path": str(output / "script.txt"),
        "plan_csv": str(output / "recompose_plan.csv"),
        "jianying_pack": str(pack_dir),
        "beats": len(rows),
        "matched": matched,
        "unmatched": len(rows) - matched,
        "duration": reference_duration,
    }
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def resolve_script_text(options: RecomposeOptions, reference_video: Path, output: Path) -> str:
    if options.script_file:
        return options.script_file.expanduser().read_text(encoding="utf-8").strip()
    if options.script_text.strip():
        return options.script_text.strip()
    sidecar = reference_video.with_suffix(".txt")
    if sidecar.exists():
        return sidecar.read_text(encoding="utf-8", errors="replace").strip()
    if options.transcribe:
        return transcribe_reference_video(reference_video, output).strip()
    return reference_video.stem


def transcribe_reference_video(reference_video: Path, output: Path) -> str:
    import whisper

    model = whisper.load_model("tiny")
    result = model.transcribe(str(reference_video), language="zh", fp16=False, verbose=False)
    payload = {
        "reference_video": str(reference_video),
        "text": result.get("text", ""),
        "segments": [
            {
                "start": float(segment.get("start", 0.0)),
                "end": float(segment.get("end", 0.0)),
                "text": str(segment.get("text", "")).strip(),
            }
            for segment in result.get("segments", [])
        ],
    }
    transcript_path = output / "reference_transcript.json"
    transcript_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return str(result.get("text", ""))


def allocate_beat_durations(beats: list[ScriptBeat], reference_duration: float) -> list[float]:
    weights = [max(0.8, float(beat.suggested_duration)) for beat in beats]
    total_weight = sum(weights) or float(len(beats))
    if reference_duration <= 0:
        return weights
    return [max(0.4, reference_duration * weight / total_weight) for weight in weights]


def choose_clip(records: list[dict[str, object]], beat: ScriptBeat, used_outputs: set[str], last_source: str) -> dict[str, object] | None:
    chosen = choose_clips(records, beat, used_outputs, last_source, 1.0)
    return chosen[0] if chosen else None


def choose_clips(
    records: list[dict[str, object]],
    beat: ScriptBeat,
    used_outputs: set[str],
    last_source: str,
    target_duration: float,
) -> list[dict[str, object]]:
    ranked = existing_records(rank_candidates(records, beat, used_outputs, last_source), used_outputs)
    direct_candidates = [record for record in ranked if record_matches_subcategory(record, beat)] if is_concrete_beat(beat) else ranked
    candidates = list(direct_candidates)
    concrete = is_concrete_beat(beat)
    allowed_categories = RELATED_FALLBACK_CATEGORIES.get(beat.target_category, (beat.target_category,))
    if beat.target_category != "90_待人工分类" and not concrete:
        related = [
            record
            for record in records
            if str(record.get("primary_category", "")) in allowed_categories
            or any(category in str(record.get("output_path", "")) for category in allowed_categories)
        ]
        candidates.extend(record for record in rank_generic_fallback(related, used_outputs, last_source) if record not in candidates)

    if not concrete or direct_candidates:
        filler_categories = SUPPORT_FALLBACK_CATEGORIES
        filler = [
            record
            for record in records
            if str(record.get("primary_category", "")) in filler_categories
            or any(category in str(record.get("output_path", "")) for category in filler_categories)
        ]
        candidates.extend(record for record in rank_generic_fallback(filler, used_outputs, last_source) if record not in candidates)

    if concrete and not direct_candidates:
        return []

    selected: list[dict[str, object]] = []
    total_duration = 0.0
    max_clips = max(2, min(8, math.ceil(max(1.0, target_duration) / 1.0)))
    seen_sources: set[str] = set()
    for record in candidates:
        output_path = str(record.get("output_path", ""))
        if not output_path or output_path in used_outputs or not Path(output_path).exists():
            continue
        source = str(record.get("source_video_path", ""))
        if source and source in seen_sources and len(selected) < 3:
            continue
        selected.append(record)
        if source:
            seen_sources.add(source)
        total_duration += min(max(0.4, record_duration(record)), 2.4)
        if total_duration >= target_duration * 0.9 or len(selected) >= max_clips:
            break
    if total_duration < target_duration * 0.9:
        for record in candidates:
            output_path = str(record.get("output_path", ""))
            if not output_path or output_path in used_outputs or any(str(item.get("output_path", "")) == output_path for item in selected):
                continue
            if not Path(output_path).exists():
                continue
            selected.append(record)
            total_duration += min(max(0.4, record_duration(record)), 2.4)
            if total_duration >= target_duration * 0.95 or len(selected) >= max_clips:
                break
    return selected


def is_concrete_beat(beat: ScriptBeat) -> bool:
    if beat.target_category.startswith("90_"):
        return False
    return bool(beat.target_subcategory.strip())


def record_matches_subcategory(record: dict[str, object], beat: ScriptBeat) -> bool:
    subcategory = beat.target_subcategory.strip()
    if not subcategory:
        return True
    evidence = " ".join(
        [
            str(record.get("category_top1", "")),
            str(record.get("semantic_tags", "")),
            str(record.get("output_path", "")),
        ]
    )
    return subcategory in evidence


def existing_records(records: list[dict[str, object]], used_outputs: set[str]) -> list[dict[str, object]]:
    return [
        record
        for record in records
        if str(record.get("output_path", ""))
        and str(record.get("output_path", "")) not in used_outputs
        and Path(str(record.get("output_path", ""))).exists()
    ]


def record_duration(record: dict[str, object]) -> float:
    try:
        return float(record.get("duration") or 0.0)
    except (TypeError, ValueError):
        return 0.0


def rank_generic_fallback(
    records: list[dict[str, object]],
    used_outputs: set[str],
    last_source: str,
) -> list[dict[str, object]]:
    fallback: list[tuple[int, dict[str, object]]] = []
    for record in records:
        output_path = str(record.get("output_path", ""))
        if not output_path or output_path in used_outputs or not Path(output_path).exists():
            continue
        score = 1
        quality = str(record.get("quality_level", "B"))
        primary = str(record.get("primary_category", ""))
        source = str(record.get("source_video_path", ""))
        if primary and not primary.startswith("90_"):
            score += 10
        if source and source != last_source:
            score += 5
        if quality == "S":
            score += 4
        elif quality == "A":
            score += 3
        elif quality == "B":
            score += 1
        fallback.append((score, record))
    fallback.sort(key=lambda item: item[0], reverse=True)
    return [record for _, record in fallback]


def extract_reference_audio(reference_video: Path, audio_path: Path, ffmpeg: Path) -> None:
    result = run_command(
        [
            ffmpeg,
            "-y",
            "-i",
            reference_video,
            "-vn",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            audio_path,
        ],
        timeout=300,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Failed to extract reference audio: {result.stderr[-1000:]}")


def render_video_segment(
    source_path: Path,
    output_path: Path,
    duration: float,
    width: int,
    height: int,
    fps: int,
    ffmpeg: Path,
) -> None:
    vf = f"scale={width}:{height}:force_original_aspect_ratio=increase,crop={width}:{height},setsar=1,fps={fps}"
    result = run_command(
        [
            ffmpeg,
            "-y",
            "-i",
            source_path,
            "-t",
            f"{max(0.4, duration):.3f}",
            "-vf",
            vf,
            "-an",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            output_path,
        ],
        timeout=300,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Failed to render visual segment {source_path}: {result.stderr[-1000:]}")


def write_concat_file(path: Path, segment_paths: list[Path]) -> None:
    lines = []
    for segment in segment_paths:
        safe = str(segment.resolve()).replace("'", "'\\''")
        lines.append(f"file '{safe}'")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def concat_segments(concat_path: Path, visual_track: Path, ffmpeg: Path) -> None:
    result = run_command(
        [
            ffmpeg,
            "-y",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            concat_path,
            "-c",
            "copy",
            visual_track,
        ],
        timeout=300,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Failed to concatenate visual track: {result.stderr[-1000:]}")


def pad_visual_track_if_short(
    visual_track: Path,
    target_duration: float,
    temp_dir: Path,
    ffmpeg: Path,
    ffprobe: Path | None,
) -> Path:
    if target_duration <= 0:
        return visual_track
    info = probe_video(visual_track, ffmpeg, ffprobe)
    duration = float(info.get("duration") or 0.0)
    gap = target_duration - duration
    if gap <= 0.25:
        return visual_track
    padded = temp_dir / "visual_track_padded.mp4"
    result = run_command(
        [
            ffmpeg,
            "-y",
            "-i",
            visual_track,
            "-vf",
            f"tpad=stop_mode=clone:stop_duration={gap:.3f}",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            padded,
        ],
        timeout=300,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Failed to pad visual track: {result.stderr[-1000:]}")
    return padded


def mux_audio(visual_track: Path, audio_path: Path, final_path: Path, ffmpeg: Path) -> None:
    result = run_command(
        [
            ffmpeg,
            "-y",
            "-i",
            visual_track,
            "-i",
            audio_path,
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-shortest",
            final_path,
        ],
        timeout=300,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Failed to mux final rough cut: {result.stderr[-1000:]}")


def plan_row(
    beat: ScriptBeat,
    duration: float,
    pack_clip: str,
    rendered_segment: str,
    matched_category: str,
    source_video: str,
    status: str,
) -> dict[str, object]:
    return {
        "index": beat.index,
        "script_text": beat.text,
        "target_category": beat.target_category,
        "target_subcategory": beat.target_subcategory,
        "duration": round(duration, 3),
        "pack_clip": pack_clip,
        "rendered_segment": rendered_segment,
        "matched_category": matched_category,
        "source_video": source_video,
        "status": status,
    }


def write_plan_csv(path: Path, rows: list[dict[str, object]]) -> None:
    fields = [
        "index",
        "script_text",
        "target_category",
        "target_subcategory",
        "duration",
        "pack_clip",
        "rendered_segment",
        "matched_category",
        "source_video",
        "status",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def write_plan_md(path: Path, title: str, reference_video: Path, final_path: Path, rows: list[dict[str, object]]) -> None:
    lines = [
        f"# {title}",
        "",
        "## Output",
        "",
        f"- Reference: `{reference_video}`",
        f"- Rough cut: `{final_path}`",
        "- Pack folder: `jianying_pack`",
        "- Plan table: `recompose_plan.csv`",
        "",
        "## Plan",
        "",
        "| # | Duration | Target | Status | Text |",
        "| --- | ---: | --- | --- | --- |",
    ]
    for row in rows:
        target = str(row["target_category"])
        if row["target_subcategory"]:
            target = f"{target}/{row['target_subcategory']}"
        text = str(row["script_text"]).replace("|", " ")
        lines.append(f"| {int(row['index']):03d} | {row['duration']} | {target} | {row['status']} | {text} |")
    path.write_text("\n".join(lines), encoding="utf-8")
