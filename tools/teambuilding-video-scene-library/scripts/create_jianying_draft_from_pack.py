from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path


PYJY_ROOT = Path(r"D:\AICode\AI\tools\external-video-reference\pyJianYingDraft")
if str(PYJY_ROOT) not in sys.path:
    sys.path.insert(0, str(PYJY_ROOT))

import pyJianYingDraft as draft  # noqa: E402
from pyJianYingDraft import SEC, trange  # noqa: E402


DEFAULT_DRAFT_ROOT = Path.home() / "AppData" / "Local" / "JianyingPro" / "User Data" / "Draft"
VIDEO_EXTS = {".mp4", ".mov", ".m4v", ".avi", ".mkv"}
AUDIO_EXTS = {".m4a", ".mp3", ".wav", ".aac", ".flac"}


def safe_name(value: str, limit: int = 60) -> str:
    value = re.sub(r"[\\/:*?\"<>|]+", "_", value).strip(" ._")
    value = re.sub(r"\s+", "_", value)
    return value[:limit] or "Codex_draft"


def read_csv_rows(csv_path: Path) -> list[dict[str, str]]:
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            with csv_path.open("r", encoding=encoding, errors="strict", newline="") as handle:
                return [dict(row) for row in csv.DictReader(handle)]
        except UnicodeError:
            continue
    with csv_path.open("r", encoding="utf-8", errors="ignore", newline="") as handle:
        return [dict(row) for row in csv.DictReader(handle)]


def find_match_csv(pack_dir: Path) -> Path:
    candidates = sorted(pack_dir.glob("*.csv"), key=lambda path: path.stat().st_mtime, reverse=True)
    for csv_path in candidates:
        rows = read_csv_rows(csv_path)
        if rows and any("selected_clip" in row for row in rows):
            return csv_path
    raise FileNotFoundError(f"No match CSV with selected_clip found in: {pack_dir}")


def seconds(value: str | float | int | None, fallback: float = 0.0) -> float:
    try:
        return float(value) if value not in (None, "") else fallback
    except Exception:
        return fallback


def material_duration_us(path: Path, is_audio: bool = False) -> int:
    material = draft.AudioMaterial(str(path)) if is_audio else draft.VideoMaterial(str(path))
    return int(material.duration)


def build_clip_plan(rows: list[dict[str, str]]) -> list[dict[str, object]]:
    beat_groups: dict[str, list[dict[str, str]]] = {}
    for row in rows:
        beat = row.get("beat") or row.get("beat_index") or row.get("seq") or str(len(beat_groups) + 1)
        beat_groups.setdefault(str(beat), []).append(row)

    plan: list[dict[str, object]] = []
    for beat, group in beat_groups.items():
        first = group[0]
        start = seconds(first.get("start"), 0.0)
        end = seconds(first.get("end"), start + 2.5)
        beat_duration = max(0.5, end - start)
        part_duration = beat_duration / max(1, len(group))
        for part_index, row in enumerate(group):
            clip_path = Path(row.get("selected_clip") or "")
            if not clip_path.exists() or clip_path.suffix.lower() not in VIDEO_EXTS:
                continue
            target_start = start + part_duration * part_index
            plan.append(
                {
                    "seq": int(seconds(row.get("seq"), len(plan) + 1)),
                    "beat": beat,
                    "line": row.get("line") or row.get("text") or "",
                    "visual_need": row.get("visual_need") or "",
                    "reason": row.get("reason") or "",
                    "clip": clip_path,
                    "target_start": target_start,
                    "target_duration": part_duration,
                }
            )
    return sorted(plan, key=lambda item: (float(item["target_start"]), int(item["seq"])))


def find_audio(pack_dir: Path) -> Path | None:
    candidates = sorted(
        [p for p in pack_dir.iterdir() if p.is_file() and p.suffix.lower() in AUDIO_EXTS],
        key=lambda path: path.stat().st_size,
        reverse=True,
    )
    return candidates[0] if candidates else None


def add_video_segment(script, item: dict[str, object], track_name: str) -> None:
    clip_path = Path(item["clip"])
    target_start = max(0.0, float(item["target_start"]))
    target_duration = max(0.5, float(item["target_duration"]))
    source_duration = material_duration_us(clip_path, is_audio=False) / SEC
    source_duration = max(0.1, min(source_duration, target_duration))
    segment = draft.VideoSegment(
        str(clip_path),
        trange(f"{target_start}s", f"{target_duration}s"),
        source_timerange=trange("0s", f"{source_duration}s"),
    )
    script.add_segment(segment, track_name)


def add_text_segment(script, item: dict[str, object], track_name: str) -> None:
    line = str(item.get("line") or "").strip()
    if not line:
        return
    target_start = max(0.0, float(item["target_start"]))
    target_duration = max(0.5, float(item["target_duration"]))
    segment = draft.TextSegment(
        line,
        trange(f"{target_start}s", f"{target_duration}s"),
        style=draft.TextStyle(size=5.0, color=(1.0, 1.0, 1.0), auto_wrapping=True),
        border=draft.TextBorder(color=(0.0, 0.0, 0.0), width=18.0),
        clip_settings=draft.ClipSettings(transform_y=-0.82),
    )
    script.add_segment(segment, track_name)


def create_draft(pack_dir: Path, draft_root: Path, draft_name: str | None = None, allow_replace: bool = True) -> Path:
    match_csv = find_match_csv(pack_dir)
    rows = read_csv_rows(match_csv)
    plan = build_clip_plan(rows)
    if not plan:
        raise RuntimeError("The match CSV has no usable selected clips.")

    audio_path = find_audio(pack_dir)
    draft_root.mkdir(parents=True, exist_ok=True)
    title = safe_name(draft_name or ("Codex_" + pack_dir.name), 80)

    folder = draft.DraftFolder(str(draft_root))
    script = folder.create_draft(title, 1080, 1920, fps=30, allow_replace=allow_replace)
    script.add_track(draft.TrackType.video, "AI Visual Track")
    if audio_path:
        script.add_track(draft.TrackType.audio, "Original Audio")
    script.add_track(draft.TrackType.text, "Transcript Reference")

    for item in plan:
        add_video_segment(script, item, "AI Visual Track")
        add_text_segment(script, item, "Transcript Reference")

    if audio_path:
        audio_duration = material_duration_us(audio_path, is_audio=True) / SEC
        timeline_duration = max(float(item["target_start"]) + float(item["target_duration"]) for item in plan)
        duration = max(audio_duration, timeline_duration)
        segment = draft.AudioSegment(str(audio_path), trange("0s", f"{duration}s"), volume=1.0)
        script.add_segment(segment, "Original Audio")

    script.save()

    report = {
        "draft_name": title,
        "draft_root": str(draft_root),
        "draft_path": str(draft_root / title),
        "pack_dir": str(pack_dir),
        "match_csv": str(match_csv),
        "clips": len(plan),
        "audio": str(audio_path) if audio_path else "",
        "usage": "Open Jianying/CapCut desktop and look for this draft name. Restart Jianying if the draft list is not refreshed.",
    }
    (pack_dir / "jianying_draft_import.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return draft_root / title


def main() -> int:
    parser = argparse.ArgumentParser(description="Create a Jianying draft from a Codex rough-cut material pack.")
    parser.add_argument("pack_dir", type=Path)
    parser.add_argument("--draft-root", type=Path, default=DEFAULT_DRAFT_ROOT)
    parser.add_argument("--draft-name", default="")
    parser.add_argument("--no-replace", action="store_true")
    args = parser.parse_args()
    out = create_draft(args.pack_dir, args.draft_root, args.draft_name or None, allow_replace=not args.no_replace)
    print(out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
