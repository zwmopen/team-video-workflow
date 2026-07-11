from __future__ import annotations

from pathlib import Path
import csv
import hashlib
import json
import re
import shutil
from dataclasses import dataclass

from .path_utils import sanitize_name


VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm"}
SIDECAR_EXTENSIONS = {".txt", ".json", ".srt", ".vtt", ".jpg", ".jpeg", ".png", ".webp"}
MIXED_HINTS = re.compile(r"(合集|盘点|整理好了|照抄|推荐地|目的地|神仙团建地|八大|8大|TOP\s*\d+|\d+个目的地)", re.I)
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


@dataclass(slots=True)
class BundleMove:
    source: Path
    destination: Path


def collect_location_sources(
    source_root: Path,
    output_root: Path,
    location: str,
    move_files: bool = False,
) -> dict[str, object]:
    source_root = source_root.expanduser().resolve()
    output_root = output_root.expanduser().resolve()
    validate_source_and_output_roots(source_root, output_root)

    destination_dir = output_root / "01_原片素材库" / f"{sanitize_name(location)}-原视频素材"
    duplicate_quarantine = (
        output_root
        / "90_待整理与记录"
        / "._采集记录"
        / f"{sanitize_name(location)}_非单地点或重复原视频"
        / "重复源视频"
    )
    if move_files:
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
                reason = f"already exists in destination as {existing_hashes[digest].name}"
                destination_path, sidecar_destinations = plan_bundle_destination(
                    video_path,
                    duplicate_quarantine,
                )
                destination = str(destination_path)
                sidecars = [str(path) for path in sidecar_destinations]
                if move_files:
                    move_bundle_transactionally(
                        video_path,
                        destination_path,
                        sidecar_destinations,
                    )
                    decision = "quarantined_source_duplicate"
                else:
                    decision = "would_quarantine_source_duplicate"
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

            destination_path, sidecar_destinations = plan_bundle_destination(
                video_path,
                destination_dir,
            )
            destination = str(destination_path)
            sidecars = [str(path) for path in sidecar_destinations]
            if move_files:
                move_bundle_transactionally(
                    video_path,
                    destination_path,
                    sidecar_destinations,
                )
                existing_hashes[digest] = destination_path
                decision = "selected"
            else:
                decision = "would_select"

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
    if move_files:
        report_dir.mkdir(parents=True, exist_ok=True)
    safe_location = sanitize_name(location)
    report_csv = report_dir / f"{safe_location}_source_collect.csv"
    summary_path = report_dir / f"{safe_location}_source_collect_summary.json"
    if move_files:
        write_collect_csv(report_csv, decisions)

    summary = {
        "source_root": str(source_root),
        "output_root": str(output_root),
        "location": location,
        "destination_dir": str(destination_dir),
        "move_files": move_files,
        "mode": "apply" if move_files else "preview",
        "total_videos_scanned": len(decisions),
        "selected": sum(1 for item in decisions if item.decision == "selected"),
        "would_select": sum(1 for item in decisions if item.decision == "would_select"),
        "quarantined_source_duplicates": sum(
            1 for item in decisions if item.decision == "quarantined_source_duplicate"
        ),
        "would_quarantine_source_duplicates": sum(
            1 for item in decisions if item.decision == "would_quarantine_source_duplicate"
        ),
        "rejected_hashtag_only": sum(
            1 for item in decisions if item.reason == "keyword only appears after hashtag"
        ),
        "rejected_mixed": sum(
            1 for item in decisions if item.reason.startswith("mixed source")
        ),
        "report_csv": str(report_csv) if move_files else "",
    }
    if move_files:
        summary_path.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    return summary


def clean_location_sources(
    output_root: Path,
    location: str,
    move_files: bool = False,
) -> dict[str, object]:
    output_root = output_root.expanduser().resolve()
    source_dir = output_root / "01_原片素材库" / f"{sanitize_name(location)}-原视频素材"
    if not source_dir.exists():
        source_dir = output_root / f"{sanitize_name(location)}-原视频素材"
    quarantine_dir = (
        output_root
        / "90_待整理与记录"
        / "._采集记录"
        / f"{sanitize_name(location)}_非单地点或重复原视频"
    )
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
            action = "quarantined" if move_files else "would_quarantine"
            destination, sidecars = move_to_quarantine(
                video_path,
                quarantine_dir / "非单地点或话题误选",
                move_files,
            )
        else:
            digest = sha256_file(video_path)
            if digest in seen_hashes:
                action = "quarantined" if move_files else "would_quarantine"
                reason = f"exact duplicate of {seen_hashes[digest].name}"
                destination, sidecars = move_to_quarantine(
                    video_path,
                    quarantine_dir / "重复源视频",
                    move_files,
                )
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
    safe_location = sanitize_name(location)
    report_csv = report_dir / f"{safe_location}_source_clean.csv"
    summary_path = report_dir / f"{safe_location}_source_clean_summary.json"
    if move_files:
        report_dir.mkdir(parents=True, exist_ok=True)
        write_clean_csv(report_csv, decisions)

    summary = {
        "output_root": str(output_root),
        "location": location,
        "source_dir": str(source_dir),
        "quarantine_dir": str(quarantine_dir),
        "move_files": move_files,
        "mode": "apply" if move_files else "preview",
        "total_videos_scanned": len(decisions),
        "kept": sum(1 for item in decisions if item.action == "kept"),
        "quarantined": sum(1 for item in decisions if item.action == "quarantined"),
        "would_quarantine": sum(
            1 for item in decisions if item.action == "would_quarantine"
        ),
        "report_csv": str(report_csv) if move_files else "",
    }
    if move_files:
        summary_path.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    return summary


def validate_source_and_output_roots(source_root: Path, output_root: Path) -> None:
    if not source_root.exists() or not source_root.is_dir():
        raise FileNotFoundError(f"Source folder does not exist: {source_root}")
    if source_root == output_root:
        raise ValueError("Source and output roots must be different folders.")
    if is_path_inside(source_root, output_root) or is_path_inside(output_root, source_root):
        raise ValueError("Source and output roots must not be nested.")


def is_path_inside(parent: Path, child: Path) -> bool:
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


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
    numbered_list = bool(re.search(r"(?:^|[。\s])\d+[\.、｜|]", title_text))
    return bool(other_locations and (MIXED_HINTS.search(title_text) or numbered_list))


def find_sidecars(video_path: Path) -> list[Path]:
    return sorted(
        [
            candidate
            for candidate in video_path.parent.glob(video_path.stem + ".*")
            if candidate.suffix.lower() in SIDECAR_EXTENSIONS
        ],
        key=lambda path: path.suffix.lower(),
    )


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


def plan_bundle_destination(video_path: Path, destination_dir: Path) -> tuple[Path, list[Path]]:
    sidecars = find_sidecars(video_path)
    stem = video_path.stem
    suffix = video_path.suffix
    for index in range(1, 10000):
        candidate_stem = stem if index == 1 else f"{stem}_{index:02d}"
        video_destination = destination_dir / f"{candidate_stem}{suffix}"
        sidecar_destinations = [
            destination_dir / f"{candidate_stem}{sidecar.suffix}"
            for sidecar in sidecars
        ]
        all_destinations = [video_destination, *sidecar_destinations]
        if len({path.name.casefold() for path in all_destinations}) != len(all_destinations):
            raise RuntimeError(f"Sidecar destination collision for {video_path.name}")
        if not any(path.exists() for path in all_destinations):
            return video_destination, sidecar_destinations
    raise RuntimeError(f"Cannot allocate a unique bundle name under {destination_dir}")


def move_bundle_transactionally(
    video_path: Path,
    destination_path: Path,
    sidecar_destinations: list[Path],
) -> tuple[str, list[str]]:
    sidecars = find_sidecars(video_path)
    if len(sidecars) != len(sidecar_destinations):
        raise RuntimeError("Sidecar plan changed before apply; run preview again.")

    destination_path.parent.mkdir(parents=True, exist_ok=True)
    moves = [
        BundleMove(source=video_path, destination=destination_path),
        *[
            BundleMove(source=source, destination=destination)
            for source, destination in zip(sidecars, sidecar_destinations, strict=True)
        ],
    ]
    for move in moves:
        if not move.source.exists() or not move.source.is_file():
            raise FileNotFoundError(f"Bundle source is missing: {move.source}")
        if move.destination.exists():
            raise FileExistsError(f"Bundle destination already exists: {move.destination}")

    completed: list[BundleMove] = []
    try:
        for move in moves:
            shutil.move(str(move.source), str(move.destination))
            completed.append(move)
    except Exception:
        rollback_errors: list[str] = []
        for move in reversed(completed):
            try:
                if move.destination.exists() and not move.source.exists():
                    move.source.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(str(move.destination), str(move.source))
            except Exception as rollback_error:  # pragma: no cover - defensive logging path
                rollback_errors.append(f"{move.destination} -> {move.source}: {rollback_error}")
        if rollback_errors:
            raise RuntimeError(
                "Bundle move failed and rollback was incomplete:\n"
                + "\n".join(rollback_errors)
            )
        raise

    return str(destination_path), [str(path) for path in sidecar_destinations]


def move_to_quarantine(
    video_path: Path,
    quarantine_dir: Path,
    move_files: bool,
) -> tuple[str, list[str]]:
    destination, sidecar_destinations = plan_bundle_destination(video_path, quarantine_dir)
    if move_files:
        return move_bundle_transactionally(
            video_path,
            destination,
            sidecar_destinations,
        )
    return str(destination), [str(path) for path in sidecar_destinations]


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
