from __future__ import annotations

from pathlib import Path
import csv
import json

from .ffmpeg_utils import find_ffmpeg, find_ffprobe, probe_video, run_command


def check_delivery(output_dir: Path, expect_vertical: bool = True, min_pack_clips: int = 1) -> dict[str, object]:
    output_dir = output_dir.expanduser().resolve()
    issues: list[str] = []
    warnings: list[str] = []
    if not output_dir.exists():
        return {
            "output_dir": str(output_dir),
            "ok": False,
            "issues": [f"Output folder does not exist: {output_dir}"],
            "warnings": warnings,
        }

    rough_cut = output_dir / "rough_cut.mp4"
    final_mp4 = rough_cut if rough_cut.exists() else first_mp4(output_dir)
    if not final_mp4:
        issues.append("No mp4 output found.")

    video_info: dict[str, object] = {}
    has_audio = False
    if final_mp4:
        ffmpeg = find_ffmpeg()
        ffprobe = find_ffprobe()
        video_info = dict(probe_video(final_mp4, ffmpeg, ffprobe))
        width = int(video_info.get("width") or 0)
        height = int(video_info.get("height") or 0)
        duration = float(video_info.get("duration") or 0.0)
        if duration <= 0:
            issues.append("Output video duration is zero or unreadable.")
        if expect_vertical and not (height > width and width > 0):
            issues.append(f"Output video is not vertical: {width}x{height}.")
        has_audio = probe_has_audio(final_mp4, ffprobe or ffmpeg)
        if not has_audio:
            issues.append("Output video has no audio stream.")

    pack_dir = output_dir / "jianying_pack"
    pack_clips = list(pack_dir.glob("*.mp4")) if pack_dir.exists() else []
    if not pack_dir.exists():
        warnings.append("jianying_pack folder is missing.")
    elif len(pack_clips) < min_pack_clips:
        issues.append(f"jianying_pack has too few clips: {len(pack_clips)}.")

    plan_csv = find_first_existing(output_dir, ["rough_cut_plan.csv", "recompose_plan.csv", "配镜表.csv", "edit_plan.csv"])
    plan_data = read_csv_rows(plan_csv) if plan_csv else []
    plan_rows = len(plan_data)
    if not plan_csv:
        warnings.append("No plan CSV found.")
    elif plan_rows <= 0:
        issues.append(f"Plan CSV has no data rows: {plan_csv.name}.")
    elif plan_csv.name == "recompose_plan.csv":
        quality = analyze_recompose_plan(plan_data)
        warnings.extend(quality["warnings"])
        issues.extend(quality["issues"])

    script_file = find_first_existing(output_dir, ["script.txt", "文案.txt"])
    if not script_file:
        warnings.append("No script text found.")

    summary = {
        "output_dir": str(output_dir),
        "ok": not issues,
        "issues": issues,
        "warnings": warnings,
        "video_path": str(final_mp4) if final_mp4 else "",
        "video_info": video_info,
        "has_audio": has_audio,
        "jianying_pack": str(pack_dir) if pack_dir.exists() else "",
        "pack_clip_count": len(pack_clips),
        "plan_csv": str(plan_csv) if plan_csv else "",
        "plan_rows": plan_rows,
        "script_file": str(script_file) if script_file else "",
    }
    (output_dir / "delivery_check.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    write_delivery_md(output_dir / "delivery_check.md", summary)
    return summary


def first_mp4(output_dir: Path) -> Path | None:
    candidates = sorted(output_dir.glob("*.mp4"), key=lambda path: path.name.lower())
    return candidates[0] if candidates else None


def find_first_existing(output_dir: Path, names: list[str]) -> Path | None:
    for name in names:
        candidate = output_dir / name
        if candidate.exists():
            return candidate
    return None


def count_csv_rows(path: Path | None) -> int:
    if not path or not path.exists():
        return 0
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return sum(1 for _ in csv.DictReader(handle))


def read_csv_rows(path: Path | None) -> list[dict[str, str]]:
    if not path or not path.exists():
        return []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def analyze_recompose_plan(rows: list[dict[str, str]]) -> dict[str, list[str]]:
    issues: list[str] = []
    warnings: list[str] = []
    unmatched = [row for row in rows if row.get("status") != "matched"]
    if unmatched:
        warnings.append(f"Plan has unmatched or weak beats: {len(unmatched)}.")

    all_pack_clips: list[str] = []
    single_clip_long_beats = 0
    manual_or_unknown = 0
    for row in rows:
        pack_clips = [item for item in str(row.get("pack_clip", "")).split(";") if item]
        all_pack_clips.extend(pack_clips)
        try:
            duration = float(row.get("duration") or 0)
        except ValueError:
            duration = 0.0
        if duration >= 2.6 and len(pack_clips) <= 1 and row.get("status") == "matched":
            single_clip_long_beats += 1
        joined = " ".join(str(row.get(key, "")) for key in ["target_category", "target_subcategory", "matched_category", "pack_clip"])
        if "待人工分类" in joined:
            manual_or_unknown += 1

    duplicated = len(all_pack_clips) - len(set(all_pack_clips))
    if duplicated:
        issues.append(f"Plan repeats exact same material clips: {duplicated}.")
    if manual_or_unknown:
        warnings.append(f"Plan uses 待人工分类 material or targets: {manual_or_unknown}.")
    if single_clip_long_beats:
        warnings.append(f"Long beats using only one clip: {single_clip_long_beats}.")
    return {"issues": issues, "warnings": warnings}


def probe_has_audio(path: Path, probe_tool: Path) -> bool:
    result = run_command(
        [
            probe_tool,
            "-v",
            "error",
            "-select_streams",
            "a",
            "-show_entries",
            "stream=index",
            "-of",
            "json",
            path,
        ],
        timeout=60,
    )
    if result.returncode != 0:
        return False
    try:
        data = json.loads(result.stdout or "{}")
    except json.JSONDecodeError:
        return False
    return bool(data.get("streams"))


def write_delivery_md(path: Path, summary: dict[str, object]) -> None:
    lines = [
        "# Delivery Check",
        "",
        f"- Status: {'OK' if summary['ok'] else 'FAILED'}",
        f"- Video: `{summary.get('video_path', '')}`",
        f"- Has audio: {summary.get('has_audio')}",
        f"- Pack clips: {summary.get('pack_clip_count')}",
        f"- Plan rows: {summary.get('plan_rows')}",
        "",
        "## Issues",
        "",
    ]
    issues = list(summary.get("issues", []))
    warnings = list(summary.get("warnings", []))
    if issues:
        lines.extend(f"- {issue}" for issue in issues)
    else:
        lines.append("- None")
    lines.extend(["", "## Warnings", ""])
    if warnings:
        lines.extend(f"- {warning}" for warning in warnings)
    else:
        lines.append("- None")
    path.write_text("\n".join(lines), encoding="utf-8")
