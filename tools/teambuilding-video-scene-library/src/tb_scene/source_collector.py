from __future__ import annotations

from pathlib import Path
import csv
import hashlib
import json
import re
import shutil
from dataclasses import dataclass

from .path_utils import ensure_unique_path, sanitize_name


VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm"}
SIDECAR_EXTENSIONS = {".txt", ".json", ".srt", ".vtt", ".jpg", ".jpeg", ".png", ".webp"}
MIXED_HINTS = re.compile(r"(合集|盘点|整理好了|照抄|推荐地|目的地|神仙团建地|八大|8大|TOP\\s*\\d+|\\d+个目的地)", re.I)
KNOWN_LOCATIONS = [
    "千岛湖",
    "安吉",
    "莫干山",
    "舟山",
    "阳澄湖",
    "天目湖",
    "溧阳",
    "南山竹海",
    "云上草原",
    "宁波",
    "西山岛",
    "湖州",
    "桐庐",
    "象山",
    "绍兴",
    "越城",
    "衢州",
    "嵊泗岛",
    "朱家尖",
    "杭州",
]


@dataclass(slots=True)
class CollectDecision:
    video_path: Path
    title_text: str
    decision: str
    reason: str
    destination: str = ""
    sidecars: str = ""


@dataclass(slots=True)
class CleanDecision:
    video_path: Path
    title_text: str
    action: str
    reason: str
    destination: str = ""
    sidecars: str = ""


def collect_location_sources(
    source_root: Path,
    output_root: Path,
    location: str,
    move_files: bool = True,
) -> dict[str, object]:
    source_root = source_root.expanduser().resolve()
    output_root = output_root.expanduser().resolve()
    destination_dir = output_root / "01_原片素材库" / f"{sanitize_name(location)}-原视频素材"
    destination_dir.mkdir(parents=True, exist_ok=True)

    decisions: list[CollectDecision] = []
    existing_hashes = hash_existing_videos(destination_dir)
    for video_path in iter_videos(source_root):
        title_text = title_before_hashtags(video_path.stem)
        decision, reason = decide_location_match(title_text, location)
        destination = ""
        sidecars: list[str] = []
        if decision == "selected":
            digest = sha256_file(video_path)
            if digest in existing_hashes:
                decision = "deleted_source_duplicate" if move_files else "skipped"
                reason = f"already exists in destination as {existing_hashes[digest].name}"
                if move_files:
                    destination, sidecars = delete_source_duplicate(video_path)
                decisions.append(
                    CollectDecision(
                        video_path=video_path,
                        title_text=title_text,
                        decision=decision,
                        reason=reason,
                        destination=destination,
                        sidecars=";".join(sidecars),
                    )
                )
                continue
            destination_path = ensure_unique_path(destination_dir / video_path.name)
            destination = str(destination_path)
            for sidecar in find_sidecars(video_path):
                sidecars.append(str(destination_dir / sidecar.name))
            if move_files:
                shutil.move(str(video_path), str(destination_path))
                existing_hashes[digest] = destination_path
                moved_sidecars: list[str] = []
                for sidecar in find_sidecars_for_destination(destination_path, source_root, video_path):
                    sidecar_dest = ensure_unique_path(destination_dir / sidecar.name)
                    shutil.move(str(sidecar), str(sidecar_dest))
                    moved_sidecars.append(str(sidecar_dest))
                sidecars = moved_sidecars
        decisions.append(
            CollectDecision(
                video_path=video_path,
                title_text=title_text,
                decision=decision,
                reason=reason,
                destination=destination,
                sidecars=";".join(sidecars),
            )
        )

    report_dir = output_root / "90_待整理与记录" / "._采集记录"
    report_dir.mkdir(parents=True, exist_ok=True)
    safe_location = sanitize_name(location)
    write_collect_csv(report_dir / f"{safe_location}_source_collect.csv", decisions)
    summary = {
        "source_root": str(source_root),
        "output_root": str(output_root),
        "location": location,
        "destination_dir": str(destination_dir),
        "move_files": move_files,
        "total_videos_scanned": len(decisions),
        "selected": sum(1 for item in decisions if item.decision == "selected"),
        "deleted_source_duplicates": sum(1 for item in decisions if item.decision == "deleted_source_duplicate"),
        "removed_source_duplicates": sum(1 for item in decisions if item.decision == "deleted_source_duplicate"),
        "rejected_hashtag_only": sum(1 for item in decisions if item.reason == "keyword only appears after hashtag"),
        "rejected_mixed": sum(1 for item in decisions if item.reason.startswith("mixed source")),
        "report_csv": str(report_dir / f"{safe_location}_source_collect.csv"),
    }
    (report_dir / f"{safe_location}_source_collect_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return summary


def clean_location_sources(
    output_root: Path,
    location: str,
    move_files: bool = True,
) -> dict[str, object]:
    output_root = output_root.expanduser().resolve()
    source_dir = output_root / "01_原片素材库" / f"{sanitize_name(location)}-原视频素材"
    if not source_dir.exists():
        source_dir = output_root / f"{sanitize_name(location)}-原视频素材"
    quarantine_dir = output_root / "90_待整理与记录" / "._采集记录" / f"{sanitize_name(location)}_非单地点或重复原视频"
    decisions: list[CleanDecision] = []
    seen_hashes: dict[str, Path] = {}

    if not source_dir.exists():
        raise FileNotFoundError(f"Location source folder does not exist: {source_dir}")

    for video_path in iter_videos(source_dir):
        title_text = normalize_existing_title(title_before_hashtags(video_path.stem))
        decision, reason = decide_location_match(title_text, location)
        action = "kept"
        destination = ""
        sidecars: list[str] = []

        if decision != "selected":
            action = "quarantined"
            destination, sidecars = move_to_quarantine(video_path, quarantine_dir / "非单地点或话题误选", move_files)
        else:
            digest = sha256_file(video_path)
            if digest in seen_hashes:
                action = "quarantined"
                reason = f"exact duplicate of {seen_hashes[digest].name}"
                destination, sidecars = move_to_quarantine(video_path, quarantine_dir / "重复源视频", move_files)
            else:
                seen_hashes[digest] = video_path

        decisions.append(
            CleanDecision(
                video_path=video_path,
                title_text=title_text,
                action=action,
                reason=reason,
                destination=destination,
                sidecars=";".join(sidecars),
            )
        )

    report_dir = output_root / "90_待整理与记录" / "._采集记录"
    report_dir.mkdir(parents=True, exist_ok=True)
    safe_location = sanitize_name(location)
    write_clean_csv(report_dir / f"{safe_location}_source_clean.csv", decisions)
    summary = {
        "output_root": str(output_root),
        "location": location,
        "source_dir": str(source_dir),
        "quarantine_dir": str(quarantine_dir),
        "move_files": move_files,
        "total_videos_scanned": len(decisions),
        "kept": sum(1 for item in decisions if item.action == "kept"),
        "quarantined": sum(1 for item in decisions if item.action == "quarantined"),
        "report_csv": str(report_dir / f"{safe_location}_source_clean.csv"),
    }
    (report_dir / f"{safe_location}_source_clean_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return summary


def iter_videos(source_root: Path):
    for path in sorted(source_root.rglob("*"), key=video_sort_key):
        if path.is_file() and path.suffix.lower() in VIDEO_EXTENSIONS:
            yield path


def video_sort_key(path: Path) -> tuple[int, str]:
    copy_penalty = 1 if re.search(r"(\s+-\s+副本|_副本| copy)$", path.stem, re.I) else 0
    return (copy_penalty, path.name.lower())


def title_before_hashtags(stem: str) -> str:
    return re.split(r"[#＃]", stem, maxsplit=1)[0].strip()


def decide_location_match(title_text: str, location: str) -> tuple[str, str]:
    if location not in title_text:
        return "skipped", "keyword absent before hashtag"
    if is_mixed_source(title_text, location):
        return "skipped", "mixed source contains multiple locations or collection wording"
    return "selected", "keyword present before hashtag"


def is_mixed_source(title_text: str, location: str) -> bool:
    other_locations = [item for item in KNOWN_LOCATIONS if item != location and item in title_text]
    numbered_list = bool(re.search(r"(?:^|[。\\s])\\d+[\\.、｜|]", title_text))
    return bool(other_locations and (MIXED_HINTS.search(title_text) or numbered_list))


def find_sidecars(video_path: Path) -> list[Path]:
    return [candidate for candidate in video_path.parent.glob(video_path.stem + ".*") if candidate.suffix.lower() in SIDECAR_EXTENSIONS]


def find_sidecars_for_destination(_: Path, __: Path, original_video_path: Path) -> list[Path]:
    return find_sidecars(original_video_path)


def normalize_existing_title(title_text: str) -> str:
    text = re.sub(r"\s+-\s+副本$", "", title_text)
    text = re.sub(r"_\d{2}$", "", text)
    return text.strip()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def hash_existing_videos(destination_dir: Path) -> dict[str, Path]:
    hashes: dict[str, Path] = {}
    if not destination_dir.exists():
        return hashes
    for video_path in iter_videos(destination_dir):
        hashes.setdefault(sha256_file(video_path), video_path)
    return hashes


def move_to_quarantine(video_path: Path, quarantine_dir: Path, move_files: bool) -> tuple[str, list[str]]:
    destination = quarantine_dir / video_path.name
    sidecar_paths = find_sidecars(video_path)
    if move_files:
        quarantine_dir.mkdir(parents=True, exist_ok=True)
        destination = ensure_unique_path(destination)
        shutil.move(str(video_path), str(destination))
        moved_sidecars: list[str] = []
        for sidecar in sidecar_paths:
            sidecar_dest = ensure_unique_path(quarantine_dir / sidecar.name)
            shutil.move(str(sidecar), str(sidecar_dest))
            moved_sidecars.append(str(sidecar_dest))
        return str(destination), moved_sidecars
    return str(destination), [str(quarantine_dir / sidecar.name) for sidecar in sidecar_paths]


def delete_source_duplicate(video_path: Path) -> tuple[str, list[str]]:
    sidecar_paths = find_sidecars(video_path)
    deleted_sidecars: list[str] = []
    for sidecar in sidecar_paths:
        if sidecar.exists():
            deleted_sidecars.append(str(sidecar))
            sidecar.unlink()
    deleted_video = str(video_path)
    if video_path.exists():
        video_path.unlink()
    return f"DELETED:{deleted_video}", deleted_sidecars


def write_collect_csv(path: Path, decisions: list[CollectDecision]) -> None:
    fields = ["video_path", "title_text", "decision", "reason", "destination", "sidecars"]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for item in decisions:
            writer.writerow(
                {
                    "video_path": str(item.video_path),
                    "title_text": item.title_text,
                    "decision": item.decision,
                    "reason": item.reason,
                    "destination": item.destination,
                    "sidecars": item.sidecars,
                }
            )


def write_clean_csv(path: Path, decisions: list[CleanDecision]) -> None:
    fields = ["video_path", "title_text", "action", "reason", "destination", "sidecars"]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for item in decisions:
            writer.writerow(
                {
                    "video_path": str(item.video_path),
                    "title_text": item.title_text,
                    "action": item.action,
                    "reason": item.reason,
                    "destination": item.destination,
                    "sidecars": item.sidecars,
                }
            )
