from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import json
import logging
import shutil

from .category_router import classify_from_metadata
from .ffmpeg_utils import find_ffmpeg, find_ffprobe
from .filename_builder import build_clip_filename
from .keyframe_extractor import extract_keyframes
from .models import ClipRecord, MAIN_CATEGORIES, QualityResult, SceneCut
from .path_utils import ensure_unique_path, output_root_for
from .quality_analyzer import analyze_quality
from .record_store import RecordStore
from .scanner import scan_videos
from .scene_detector import detect_scenes
from .scene_splitter import split_scene


@dataclass(slots=True)
class PipelineOptions:
    input_dir: Path
    output: str | None = None
    orientation: str = "vertical"
    detector: str = "adaptive"
    threshold: float | None = None
    min_scene_len: int = 15
    split_mode: str = "accurate"
    classify: bool = True
    classification_threshold: float = 0.45
    quality_check: bool = True
    deduplicate: bool = True
    resume: bool = True
    force_reprocess: bool = False
    keep_low_quality: bool = False
    keep_duplicates: bool = False
    dry_run: bool = False
    workers: int = 1
    device: str = "cpu"
    max_videos: int | None = None
    max_scenes_per_video: int | None = None
    crf: int = 18
    preset: str = "fast"


def run_pipeline(options: PipelineOptions) -> dict[str, object]:
    input_dir = options.input_dir.expanduser().resolve()
    if not input_dir.exists() or not input_dir.is_dir():
        raise NotADirectoryError(f"Input folder does not exist: {input_dir}")

    output_root = output_root_for(input_dir, options.output)
    system_dir = output_root / "._系统记录"
    log_dir = system_dir
    log_dir.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(
        filename=log_dir / "processing.log",
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        encoding="utf-8",
    )

    ffmpeg = find_ffmpeg()
    ffprobe = find_ffprobe()
    videos = scan_videos(input_dir, ffmpeg, ffprobe)
    location = input_dir.name

    summary: dict[str, object] = {
        "input_dir": str(input_dir),
        "output_root": str(output_root),
        "dry_run": options.dry_run,
        "ffmpeg": str(ffmpeg),
        "ffprobe": str(ffprobe) if ffprobe else "",
        "total_videos": len(videos),
        "selected_videos": 0,
        "skipped_horizontal": 0,
        "skipped_duplicate_sources": 0,
        "skipped_resume": 0,
        "skipped_resume_scenes": 0,
        "processed_videos": 0,
        "scenes_detected": 0,
        "clips_written": 0,
        "low_quality_skipped": 0,
        "failed_scenes": 0,
        "category_counts": {},
        "known_limits": [
            "MVP uses metadata keyword routing; visual AI/OpenCLIP classification is the next stage.",
            "Near-duplicate clip detection is reserved for the next stage; exact duplicate source files are recorded now.",
        ],
    }

    if options.dry_run:
        selected = _filter_videos(videos, options.orientation)
        if options.max_videos:
            selected = selected[: options.max_videos]
        summary["selected_videos"] = len(selected)
        summary["skipped_horizontal"] = len([item for item in videos if item.orientation == "horizontal"])
        summary["dry_run_videos"] = [
            {
                "id": item.video_id,
                "name": item.path.name,
                "orientation": item.orientation,
                "width": item.width,
                "height": item.height,
                "duration": round(item.duration, 3),
                "sha256": item.sha256,
            }
            for item in selected
        ]
        return summary

    _create_category_dirs(output_root)
    store = RecordStore(system_dir)
    seen_hashes: dict[str, str] = {}
    serial = 1

    try:
        for source in videos:
            if options.orientation == "vertical" and source.orientation != "vertical":
                source.status = "skipped_orientation"
                source.skip_reason = f"orientation={source.orientation}"
                summary["skipped_horizontal"] = int(summary["skipped_horizontal"]) + 1
                store.record_source(source)
                continue

            if options.max_videos and int(summary["selected_videos"]) >= options.max_videos:
                source.status = "skipped_limit"
                source.skip_reason = "max_videos reached"
                store.record_source(source)
                continue

            summary["selected_videos"] = int(summary["selected_videos"]) + 1

            if options.resume and not options.force_reprocess and store.source_processed(source):
                source.status = "skipped_resume"
                source.skip_reason = "unchanged source already processed"
                summary["skipped_resume"] = int(summary["skipped_resume"]) + 1
                store.record_source(source)
                continue

            known_owner = store.known_hash_owner(source.sha256) or seen_hashes.get(source.sha256)
            if options.deduplicate and known_owner and not options.keep_duplicates:
                source.status = "skipped_duplicate_source"
                source.skip_reason = f"duplicate of {known_owner}"
                summary["skipped_duplicate_sources"] = int(summary["skipped_duplicate_sources"]) + 1
                store.record_source(source)
                continue

            seen_hashes[source.sha256] = str(source.path)
            source.status = "processing"
            store.record_source(source)

            try:
                cuts = detect_scenes(
                    source.path,
                    source.duration,
                    options.detector,
                    options.threshold,
                    options.min_scene_len,
                )
                if options.max_scenes_per_video:
                    cuts = cuts[: options.max_scenes_per_video]
                summary["scenes_detected"] = int(summary["scenes_detected"]) + len(cuts)
                for scene in cuts:
                    if options.resume and not options.force_reprocess and store.scene_completed(source, scene.scene_id):
                        summary["skipped_resume_scenes"] = int(summary["skipped_resume_scenes"]) + 1
                        continue
                    result = _process_scene(
                        ffmpeg=ffmpeg,
                        output_root=output_root,
                        system_dir=system_dir,
                        location=location,
                        source=source,
                        scene=scene,
                        serial=serial,
                        options=options,
                    )
                    store.record_scene(result)
                    if result.processing_status == "written" and result.output_path:
                        serial += 1
                        summary["clips_written"] = int(summary["clips_written"]) + 1
                        category_key = result.classification.primary_category
                        if result.classification.subcategory:
                            category_key = f"{category_key}/{result.classification.subcategory}"
                        counts = dict(summary["category_counts"])
                        counts[category_key] = counts.get(category_key, 0) + 1
                        summary["category_counts"] = counts
                    elif result.processing_status == "skipped_low_quality":
                        summary["low_quality_skipped"] = int(summary["low_quality_skipped"]) + 1
                    elif result.processing_status == "failed":
                        summary["failed_scenes"] = int(summary["failed_scenes"]) + 1
            except Exception as exc:
                logging.exception("Failed processing source %s", source.path)
                source.status = "failed"
                source.skip_reason = str(exc)
                store.record_source(source)
                continue

            source.status = "processed"
            source.skip_reason = ""
            summary["processed_videos"] = int(summary["processed_videos"]) + 1
            store.record_source(source)
            store.export_files(summary)
            _write_report(system_dir, summary)

        store.export_files(summary)
        _write_report(system_dir, summary)
    finally:
        store.close()

    return summary


def _filter_videos(videos: list, orientation: str) -> list:
    if orientation == "vertical":
        return [item for item in videos if item.orientation == "vertical"]
    return videos


def _create_category_dirs(output_root: Path) -> None:
    for category in MAIN_CATEGORIES:
        (output_root / category).mkdir(parents=True, exist_ok=True)
    (output_root / "._系统记录" / "keyframes").mkdir(parents=True, exist_ok=True)
    (output_root / "._系统记录" / "work").mkdir(parents=True, exist_ok=True)
    (output_root / "._系统记录" / "configs").mkdir(parents=True, exist_ok=True)


def _process_scene(
    ffmpeg: Path,
    output_root: Path,
    system_dir: Path,
    location: str,
    source,
    scene: SceneCut,
    serial: int,
    options: PipelineOptions,
) -> ClipRecord:
    classification = classify_from_metadata(source.path) if options.classify else classify_from_metadata(Path("unknown"))
    temp_path = system_dir / "work" / f"{source.video_id}_{scene.scene_id}.mp4"
    ok, error = split_scene(ffmpeg, source, scene, temp_path, options.split_mode, options.crf, options.preset)
    if not ok:
        return ClipRecord(
            location=location,
            source=source,
            scene=scene,
            classification=classification,
            quality=_empty_quality("C", ["分镜输出失败"]),
            output_path=None,
            keyframes=[],
            processing_status="failed",
            skip_reason=error,
        )

    keyframes = extract_keyframes(ffmpeg, temp_path, scene, system_dir / "keyframes")
    quality = analyze_quality(source, scene, keyframes) if options.quality_check else _empty_quality("A", [])

    if quality.quality_level == "C" and not options.keep_low_quality:
        temp_path.unlink(missing_ok=True)
        return ClipRecord(
            location=location,
            source=source,
            scene=scene,
            classification=classification,
            quality=quality,
            output_path=None,
            keyframes=keyframes,
            processing_status="skipped_low_quality",
            skip_reason=";".join(quality.quality_reasons),
        )

    destination_dir = output_root / classification.primary_category
    if classification.primary_category == "05_项目活动" and classification.subcategory:
        destination_dir = destination_dir / classification.subcategory
    elif classification.primary_category == "90_待人工分类":
        destination_dir = output_root / "90_待人工分类"
    destination_dir.mkdir(parents=True, exist_ok=True)
    filename = build_clip_filename(location, quality.quality_level, classification, source, scene, serial)
    final_path = ensure_unique_path(destination_dir / filename)
    shutil.move(str(temp_path), str(final_path))

    return ClipRecord(
        location=location,
        source=source,
        scene=scene,
        classification=classification,
        quality=quality,
        output_path=final_path,
        keyframes=keyframes,
        processing_status="written",
    )


def _empty_quality(level: str, reasons: list[str]) -> QualityResult:
    return QualityResult(
        quality_level=level,
        quality_reasons=reasons,
        sharpness_average=0.0,
        brightness_average=0.0,
        black_ratio=0.0,
        overexposure_ratio=0.0,
    )


def _write_report(system_dir: Path, summary: dict[str, object]) -> None:
    lines = [
        "# 处理报告",
        "",
        f"- 输入目录: {summary['input_dir']}",
        f"- 输出目录: {summary['output_root']}",
        f"- 总视频数: {summary['total_videos']}",
        f"- 选中视频数: {summary['selected_videos']}",
        f"- 跳过横屏数: {summary['skipped_horizontal']}",
        f"- 跳过重复源视频数: {summary['skipped_duplicate_sources']}",
        f"- 续跑跳过分镜数: {summary['skipped_resume_scenes']}",
        f"- 处理视频数: {summary['processed_videos']}",
        f"- 检测分镜数: {summary['scenes_detected']}",
        f"- 输出片段数: {summary['clips_written']}",
        f"- 低质量跳过数: {summary['low_quality_skipped']}",
        f"- 失败分镜数: {summary['failed_scenes']}",
        "",
        "## 分类数量",
    ]
    for category, count in sorted(dict(summary["category_counts"]).items()):
        lines.append(f"- {category}: {count}")
    lines.extend(["", "## 已知限制"])
    for item in summary["known_limits"]:
        lines.append(f"- {item}")
    (system_dir / "processing_report.md").write_text("\n".join(lines), encoding="utf-8")
    (system_dir / "run_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
