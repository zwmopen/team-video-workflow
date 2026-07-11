from __future__ import annotations

from pathlib import Path
import argparse
import json
import sys

from .ffmpeg_utils import find_ffmpeg, find_ffprobe
from .pipeline import PipelineOptions, run_pipeline
from .keyword_refiner import refine_library_keywords
from .script_matcher import build_edit_pack
from .reference_recomposer import RecomposeOptions, recompose_reference_video
from .delivery_checker import check_delivery
from .transcriber import transcribe_sources
from .source_collector import clean_location_sources, collect_location_sources
from .clip_renamer import rename_library_clips
from .visual_corrector import apply_visual_corrections, corrections_from_csv
from .visual_audit import build_visual_audit_contact_sheets
from .clean_materials import clean_materials
from .clean_selector import select_clean_materials
from .overlay_audit import audit_overlays
from .reference_learner import DEFAULT_FEISHU_DOC, ReferenceLearnOptions, learn_reference_videos
from .material_demand import MaterialDemandOptions, analyze_material_demand
from .shot_match_board import MatchBoardOptions, build_shot_match_board
from .rough_cut_from_board import RoughCutFromBoardOptions, build_rough_cut_from_board
from .audio_library import AudioLibraryOptions, build_audio_library
from .smart_match_workflow import (
    SmartMatchWorkflowOptions,
    default_location_audio_library,
    run_smart_match_workflow,
    summarize_audio_library,
)


def parse_bool(value: str) -> bool:
    lowered = value.lower()
    if lowered in {"1", "true", "yes", "y", "on"}:
        return True
    if lowered in {"0", "false", "no", "n", "off"}:
        return False
    raise argparse.ArgumentTypeError(f"Invalid boolean value: {value}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Team-building video scene library MVP")
    subparsers = parser.add_subparsers(dest="command", required=True)

    check = subparsers.add_parser("check-env", help="Check local runtime dependencies")
    check.set_defaults(func=check_env)

    process = subparsers.add_parser("process-location", help="Process a location folder")
    process.add_argument("input_dir")
    process.add_argument("--output")
    process.add_argument("--orientation", choices=["vertical", "all"], default="vertical")
    process.add_argument("--detector", choices=["adaptive", "content", "transnet"], default="adaptive")
    process.add_argument("--threshold", type=float)
    process.add_argument("--min-scene-len", type=int, default=15)
    process.add_argument("--split-mode", choices=["copy", "accurate"], default="accurate")
    process.add_argument("--classify", type=parse_bool, default=True)
    process.add_argument("--classification-threshold", type=float, default=0.45)
    process.add_argument("--quality-check", type=parse_bool, default=True)
    process.add_argument("--deduplicate", type=parse_bool, default=True)
    process.add_argument("--workers", type=int, default=1)
    process.add_argument("--device", choices=["cpu", "cuda"], default="cpu")
    process.add_argument("--resume", action="store_true", default=True)
    process.add_argument("--force-reprocess", action="store_true")
    process.add_argument("--keep-low-quality", action="store_true")
    process.add_argument("--keep-duplicates", action="store_true")
    process.add_argument("--dry-run", action="store_true")
    process.add_argument("--max-videos", type=int)
    process.add_argument("--max-scenes-per-video", type=int)
    process.add_argument("--crf", type=int, default=18)
    process.add_argument("--preset", default="fast")
    process.set_defaults(func=process_location)

    pack = subparsers.add_parser("build-edit-pack", help="Build a numbered edit material pack from a script")
    pack.add_argument("library_root")
    pack.add_argument("--script-file")
    pack.add_argument("--script-text")
    pack.add_argument("--title", required=True)
    pack.add_argument("--output")
    pack.set_defaults(func=build_pack_command)

    audio_library = subparsers.add_parser(
        "extract-audio-library",
        help="Extract an audio material library from unsplit original source videos",
    )
    audio_library.add_argument("source_dir")
    audio_library.add_argument("--output")
    audio_library.add_argument("--transcribe", action="store_true")
    audio_library.add_argument("--model", default="tiny")
    audio_library.add_argument("--language", default="zh")
    audio_library.add_argument("--max-videos", type=int)
    audio_library.add_argument("--force", action="store_true")
    audio_library.set_defaults(func=extract_audio_library_command)

    list_audio = subparsers.add_parser(
        "list-audio-library",
        help="List selectable audio files and transcript previews for a location audio library",
    )
    list_audio.add_argument("path", help="Audio library folder or a location scene library folder")
    list_audio.set_defaults(func=list_audio_library_command)

    match_board = subparsers.add_parser(
        "build-match-board",
        help="Build a reviewable script-to-shot candidate board with thumbnails before rough cutting",
    )
    match_board.add_argument("library_root")
    match_board.add_argument("--title", required=True)
    match_board.add_argument("--script-file")
    match_board.add_argument("--script-text")
    match_board.add_argument("--audio-file")
    match_board.add_argument("--reference-video")
    match_board.add_argument("--feedback-file")
    match_board.add_argument("--output")
    match_board.add_argument("--max-candidates", type=int, default=5)
    match_board.set_defaults(func=build_match_board_command)

    rough_from_board = subparsers.add_parser(
        "build-rough-cut-from-board",
        help="Render a rough cut and Jianying pack from a reviewed shot match board JSON",
    )
    rough_from_board.add_argument("board_json")
    rough_from_board.add_argument("--output")
    rough_from_board.add_argument("--audio-file")
    rough_from_board.add_argument("--feedback-file")
    rough_from_board.add_argument("--reference-video")
    rough_from_board.add_argument("--width", type=int, default=1080)
    rough_from_board.add_argument("--height", type=int, default=1920)
    rough_from_board.add_argument("--fps", type=int, default=30)
    rough_from_board.add_argument("--max-clips-per-beat", type=int, default=5)
    rough_from_board.add_argument("--keep-temp", action="store_true")
    rough_from_board.set_defaults(func=build_rough_cut_from_board_command)

    smart_match = subparsers.add_parser(
        "smart-match-workflow",
        help="Run audio/script to shot board to rough cut as one production workflow",
    )
    smart_match.add_argument("library_root")
    smart_match.add_argument("--title", required=True)
    smart_match.add_argument("--output")
    smart_match.add_argument("--audio-file")
    smart_match.add_argument("--audio-index", type=int)
    smart_match.add_argument("--audio-query")
    smart_match.add_argument("--source-dir")
    smart_match.add_argument("--feedback-file")
    smart_match.add_argument("--transcribe", action="store_true")
    smart_match.add_argument("--model", default="tiny")
    smart_match.add_argument("--language", default="zh")
    smart_match.add_argument("--max-source-videos", type=int)
    smart_match.add_argument("--max-candidates", type=int, default=5)
    smart_match.add_argument("--render-rough-cut", type=parse_bool, default=True)
    smart_match.add_argument("--max-clips-per-beat", type=int, default=5)
    smart_match.add_argument("--width", type=int, default=1080)
    smart_match.add_argument("--height", type=int, default=1920)
    smart_match.add_argument("--fps", type=int, default=30)
    smart_match.set_defaults(func=smart_match_workflow_command)

    recompose = subparsers.add_parser(
        "recompose-reference",
        help="Use a reference video's audio/script and replace visuals with vertical library clips",
    )
    recompose.add_argument("reference_video")
    recompose.add_argument("library_root")
    recompose.add_argument("--title", required=True)
    recompose.add_argument("--output")
    recompose.add_argument("--script-file")
    recompose.add_argument("--script-text")
    recompose.add_argument("--transcribe", action="store_true")
    recompose.add_argument("--width", type=int, default=1080)
    recompose.add_argument("--height", type=int, default=1920)
    recompose.add_argument("--fps", type=int, default=30)
    recompose.add_argument("--max-beats", type=int)
    recompose.add_argument("--keep-temp", action="store_true")
    recompose.set_defaults(func=recompose_reference_command)

    learn = subparsers.add_parser(
        "learn-reference-videos",
        help="Learn transcript-to-visual patterns from 3-5 high-quality vertical reference videos",
    )
    learn.add_argument("source_roots", nargs="*")
    learn.add_argument("--output-root")
    learn.add_argument("--run-name")
    learn.add_argument("--max-videos", type=int, default=5)
    learn.add_argument("--max-beats-per-video", type=int, default=10)
    learn.add_argument("--orientation", choices=["vertical", "all"], default="vertical")
    learn.add_argument("--detector", choices=["adaptive", "content"], default="adaptive")
    learn.add_argument("--min-scene-len", type=int, default=12)
    learn.add_argument("--transcribe-audio", action="store_true")
    learn.add_argument("--transcribe-missing", action="store_true")
    learn.add_argument("--publish-feishu", type=parse_bool, default=False)
    learn.add_argument("--feishu-doc", default=DEFAULT_FEISHU_DOC)
    learn.add_argument("--feishu-image-limit", type=int, default=30)
    learn.set_defaults(func=learn_reference_videos_command)

    demand = subparsers.add_parser(
        "analyze-material-demand",
        help="Extract raw-video copy/audio keywords and build a material collection demand list",
    )
    demand.add_argument("--source-root", default=r"D:\Download\素材下载\团建视频")
    demand.add_argument("--output-root")
    demand.add_argument("--locations", nargs="*")
    demand.add_argument("--run-name")
    demand.add_argument("--transcribe-audio", action="store_true")
    demand.add_argument("--force-transcribe", action="store_true")
    demand.add_argument("--max-videos-per-location", type=int)
    demand.add_argument("--min-existing-clips", type=int, default=8)
    demand.add_argument("--publish-feishu", type=parse_bool, default=False)
    demand.add_argument("--feishu-doc", default=DEFAULT_FEISHU_DOC)
    demand.set_defaults(func=analyze_material_demand_command)

    refine = subparsers.add_parser("refine-keywords", help="Move existing clips into more specific keyword subfolders")
    refine.add_argument("library_root")
    refine.add_argument("--ocr", type=parse_bool, default=True)
    refine.add_argument("--transcript", type=parse_bool, default=True)
    refine.add_argument("--move", type=parse_bool, default=False, help="Apply file moves. Default is preview only.")
    refine.set_defaults(func=refine_keywords_command)

    transcript = subparsers.add_parser("transcribe-sources", help="Transcribe original source audio for timecode keyword refinement")
    transcript.add_argument("library_root")
    transcript.add_argument("--model", default="tiny")
    transcript.add_argument("--language", default="zh")
    transcript.add_argument("--max-sources", type=int)
    transcript.add_argument("--force", action="store_true")
    transcript.set_defaults(func=transcribe_sources_command)

    collect = subparsers.add_parser("collect-location-sources", help="Collect raw videos for a location from a large downloaded source library")
    collect.add_argument("source_root")
    collect.add_argument("--output-root", required=True)
    collect.add_argument("--location", required=True)
    collect.add_argument("--move", type=parse_bool, default=False, help="Move selected files. Default is preview only.")
    collect.set_defaults(func=collect_location_sources_command)

    clean = subparsers.add_parser("clean-location-sources", help="Clean an existing location raw-video folder")
    clean.add_argument("--output-root", required=True)
    clean.add_argument("--location", required=True)
    clean.add_argument("--move", type=parse_bool, default=False, help="Move rejected files to quarantine. Default is preview only.")
    clean.set_defaults(func=clean_location_sources_command)

    rename = subparsers.add_parser("rename-clips", help="Rename library clips with serial, location, and keyword")
    rename.add_argument("library_root")
    rename.add_argument("--move", type=parse_bool, default=False, help="Apply renames. Default is preview only.")
    rename.add_argument("--confirm-token", default="", help="Required value: RENAME when --move true.")
    rename.set_defaults(func=rename_clips_command)

    visual_fix = subparsers.add_parser("apply-visual-corrections", help="Preview or apply visual review corrections transactionally")
    visual_fix.add_argument("library_root")
    visual_fix.add_argument("--corrections-csv", required=True)
    visual_fix.add_argument("--report-name", default="visual_corrections.csv")
    visual_fix.add_argument("--apply", type=parse_bool, default=False, help="Apply corrections. Default is preview only.")
    visual_fix.add_argument("--confirm-token", default="", help="Required value: APPLY when --apply true.")
    visual_fix.add_argument("--rename-after", type=parse_bool, default=False, help="Run transactional clip renaming after corrections.")
    visual_fix.set_defaults(func=apply_visual_corrections_command)

    visual_audit = subparsers.add_parser("visual-audit", help="Generate contact sheets and a correction CSV template")
    visual_audit.add_argument("library_root")
    visual_audit.add_argument("--output")
    visual_audit.add_argument("--group-by", choices=["folder", "category", "source"], default="folder")
    visual_audit.add_argument("--clips-per-sheet", type=int, default=12)
    visual_audit.add_argument("--max-clips", type=int)
    visual_audit.set_defaults(func=visual_audit_command)

    clean_material = subparsers.add_parser("clean-materials", help="Create non-destructive clean copies of video materials")
    clean_material.add_argument("input_dir")
    clean_material.add_argument("--output")
    clean_material.add_argument(
        "--mode",
        choices=[
            "crop-bottom",
            "above-subtitle-crop",
            "above-subtitle-fixed",
            "blur-bottom",
            "adaptive-crop",
            "opencv-inpaint-text",
            "bottom-subtitle-inpaint",
            "subtitle-watermark-inpaint",
        ],
        default="crop-bottom",
    )
    clean_material.add_argument("--bottom-pct", type=float, default=0.16)
    clean_material.add_argument("--top-pct", type=float, default=0.0)
    clean_material.add_argument("--max-files", type=int)
    clean_material.add_argument("--crf", type=int, default=20)
    clean_material.set_defaults(func=clean_materials_command)

    select_clean = subparsers.add_parser("select-clean-materials", help="Build a usable clean library by replacing dirty clips when possible")
    select_clean.add_argument("clean_root")
    select_clean.add_argument("--output-root", required=True)
    select_clean.add_argument("--threshold", type=float, default=0.018)
    select_clean.set_defaults(func=select_clean_materials_command)

    overlay_audit = subparsers.add_parser("audit-overlays", help="Audit clips for visible subtitles, stickers, and watermarks")
    overlay_audit.add_argument("input_dir")
    overlay_audit.add_argument("--output")
    overlay_audit.add_argument("--max-files", type=int)
    overlay_audit.add_argument("--dirty-threshold", type=float, default=0.006)
    overlay_audit.add_argument("--contact-sheet-limit", type=int, default=160)
    overlay_audit.set_defaults(func=audit_overlays_command)

    delivery = subparsers.add_parser("check-delivery", help="Check a rough-cut or edit-pack output folder")
    delivery.add_argument("output_dir")
    delivery.add_argument("--expect-vertical", type=parse_bool, default=True)
    delivery.add_argument("--min-pack-clips", type=int, default=1)
    delivery.set_defaults(func=check_delivery_command)
    return parser


def check_env(_: argparse.Namespace) -> int:
    import importlib.util
    import sys

    modules = [
        "cv2",
        "scenedetect",
        "numpy",
        "pandas",
        "PIL",
        "yaml",
        "rich",
        "open_clip",
        "imagehash",
        "moviepy",
        "whisper",
        "whisperx",
        "opentimelineio",
        "auto_editor",
    ]
    result = {
        "python": sys.version.split()[0],
        "ffmpeg": str(find_ffmpeg()),
        "ffprobe": str(find_ffprobe() or ""),
        "modules": {name: bool(importlib.util.find_spec(name)) for name in modules},
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    missing_required = [name for name in ["cv2", "scenedetect", "numpy"] if not result["modules"][name]]
    return 1 if missing_required else 0


def process_location(args: argparse.Namespace) -> int:
    options = PipelineOptions(
        input_dir=Path(args.input_dir),
        output=args.output,
        orientation=args.orientation,
        detector=args.detector,
        threshold=args.threshold,
        min_scene_len=args.min_scene_len,
        split_mode=args.split_mode,
        classify=args.classify,
        classification_threshold=args.classification_threshold,
        quality_check=args.quality_check,
        deduplicate=args.deduplicate,
        workers=args.workers,
        device=args.device,
        resume=args.resume,
        force_reprocess=args.force_reprocess,
        keep_low_quality=args.keep_low_quality,
        keep_duplicates=args.keep_duplicates,
        dry_run=args.dry_run,
        max_videos=args.max_videos,
        max_scenes_per_video=args.max_scenes_per_video,
        crf=args.crf,
        preset=args.preset,
    )
    summary = run_pipeline(options)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def build_pack_command(args: argparse.Namespace) -> int:
    if args.script_file:
        script_text = Path(args.script_file).read_text(encoding="utf-8")
    elif args.script_text:
        script_text = args.script_text
    else:
        raise SystemExit("Provide --script-file or --script-text")
    summary = build_edit_pack(
        library_root=Path(args.library_root),
        script_text=script_text,
        title=args.title,
        output=Path(args.output) if args.output else None,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def extract_audio_library_command(args: argparse.Namespace) -> int:
    options = AudioLibraryOptions(
        source_dir=Path(args.source_dir),
        output=Path(args.output) if args.output else None,
        transcribe=args.transcribe,
        model=args.model,
        language=args.language,
        max_videos=args.max_videos,
        force=args.force,
    )
    summary = build_audio_library(options)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def list_audio_library_command(args: argparse.Namespace) -> int:
    path = Path(args.path)
    audio_dir = path if path.name.endswith("音频素材库") else default_location_audio_library(path)
    rows = summarize_audio_library(audio_dir)
    print(json.dumps({"audio_library": str(audio_dir), "count": len(rows), "items": rows}, ensure_ascii=False, indent=2))
    return 0


def build_match_board_command(args: argparse.Namespace) -> int:
    options = MatchBoardOptions(
        library_root=Path(args.library_root),
        title=args.title,
        output=Path(args.output) if args.output else None,
        script_file=Path(args.script_file) if args.script_file else None,
        script_text=args.script_text or "",
        audio_file=Path(args.audio_file) if args.audio_file else None,
        reference_video=Path(args.reference_video) if args.reference_video else None,
        feedback_file=Path(args.feedback_file) if args.feedback_file else None,
        max_candidates=args.max_candidates,
    )
    summary = build_shot_match_board(options)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def build_rough_cut_from_board_command(args: argparse.Namespace) -> int:
    options = RoughCutFromBoardOptions(
        board_json=Path(args.board_json),
        output=Path(args.output) if args.output else None,
        audio_file=Path(args.audio_file) if args.audio_file else None,
        feedback_file=Path(args.feedback_file) if args.feedback_file else None,
        reference_video=Path(args.reference_video) if args.reference_video else None,
        width=args.width,
        height=args.height,
        fps=args.fps,
        max_clips_per_beat=args.max_clips_per_beat,
        keep_temp=args.keep_temp,
    )
    summary = build_rough_cut_from_board(options)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def smart_match_workflow_command(args: argparse.Namespace) -> int:
    options = SmartMatchWorkflowOptions(
        library_root=Path(args.library_root),
        title=args.title,
        output=Path(args.output) if args.output else None,
        audio_file=Path(args.audio_file) if args.audio_file else None,
        audio_index=args.audio_index,
        audio_query=args.audio_query or "",
        source_dir=Path(args.source_dir) if args.source_dir else None,
        feedback_file=Path(args.feedback_file) if args.feedback_file else None,
        transcribe=args.transcribe,
        model=args.model,
        language=args.language,
        max_source_videos=args.max_source_videos,
        max_candidates=args.max_candidates,
        render_rough_cut=args.render_rough_cut,
        max_clips_per_beat=args.max_clips_per_beat,
        width=args.width,
        height=args.height,
        fps=args.fps,
    )
    summary = run_smart_match_workflow(options)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def recompose_reference_command(args: argparse.Namespace) -> int:
    options = RecomposeOptions(
        reference_video=Path(args.reference_video),
        library_root=Path(args.library_root),
        title=args.title,
        output=Path(args.output) if args.output else None,
        script_text=args.script_text or "",
        script_file=Path(args.script_file) if args.script_file else None,
        transcribe=args.transcribe,
        width=args.width,
        height=args.height,
        fps=args.fps,
        max_beats=args.max_beats,
        keep_temp=args.keep_temp,
    )
    summary = recompose_reference_video(options)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def learn_reference_videos_command(args: argparse.Namespace) -> int:
    options = ReferenceLearnOptions(
        source_roots=[Path(item) for item in args.source_roots],
        output_root=Path(args.output_root) if args.output_root else None,
        run_name=args.run_name or "",
        max_videos=args.max_videos,
        max_beats_per_video=args.max_beats_per_video,
        orientation=args.orientation,
        detector=args.detector,
        min_scene_len=args.min_scene_len,
        transcribe_audio=args.transcribe_audio,
        transcribe_missing=args.transcribe_missing,
        publish_feishu=args.publish_feishu,
        feishu_doc=args.feishu_doc,
        feishu_image_limit=args.feishu_image_limit,
    )
    summary = learn_reference_videos(options)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def analyze_material_demand_command(args: argparse.Namespace) -> int:
    options = MaterialDemandOptions(
        source_root=Path(args.source_root),
        output_root=Path(args.output_root) if args.output_root else Path(r"D:\Download\素材下载\团建视频\00-模板库\素材需求雷达"),
        locations=args.locations,
        run_name=args.run_name or "",
        transcribe_audio=args.transcribe_audio,
        force_transcribe=args.force_transcribe,
        max_videos_per_location=args.max_videos_per_location,
        min_existing_clips=args.min_existing_clips,
        publish_feishu=args.publish_feishu,
        feishu_doc=args.feishu_doc,
    )
    summary = analyze_material_demand(options)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def refine_keywords_command(args: argparse.Namespace) -> int:
    summary = refine_library_keywords(
        library_root=Path(args.library_root),
        use_ocr=args.ocr,
        use_transcript=args.transcript,
        move_files=args.move,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def transcribe_sources_command(args: argparse.Namespace) -> int:
    summary = transcribe_sources(
        library_root=Path(args.library_root),
        model_name=args.model,
        language=args.language,
        max_sources=args.max_sources,
        force=args.force,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def collect_location_sources_command(args: argparse.Namespace) -> int:
    summary = collect_location_sources(
        source_root=Path(args.source_root),
        output_root=Path(args.output_root),
        location=args.location,
        move_files=args.move,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def clean_location_sources_command(args: argparse.Namespace) -> int:
    summary = clean_location_sources(
        output_root=Path(args.output_root),
        location=args.location,
        move_files=args.move,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def rename_clips_command(args: argparse.Namespace) -> int:
    summary = rename_library_clips(
        library_root=Path(args.library_root),
        move_files=args.move,
        confirm_token=args.confirm_token,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def apply_visual_corrections_command(args: argparse.Namespace) -> int:
    corrections = corrections_from_csv(Path(args.corrections_csv))
    summary = apply_visual_corrections(
        library_root=Path(args.library_root),
        corrections=corrections,
        report_name=args.report_name,
        apply=args.apply,
        confirm_token=args.confirm_token,
        rename_after=args.rename_after,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def visual_audit_command(args: argparse.Namespace) -> int:
    summary = build_visual_audit_contact_sheets(
        library_root=Path(args.library_root),
        output_dir=Path(args.output) if args.output else None,
        group_by=args.group_by,
        clips_per_sheet=args.clips_per_sheet,
        max_clips=args.max_clips,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def clean_materials_command(args: argparse.Namespace) -> int:
    summary = clean_materials(
        input_dir=Path(args.input_dir),
        output_dir=Path(args.output) if args.output else None,
        mode=args.mode,
        bottom_pct=args.bottom_pct,
        top_pct=args.top_pct,
        max_files=args.max_files,
        crf=args.crf,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary.get("failed") == 0 else 1


def select_clean_materials_command(args: argparse.Namespace) -> int:
    summary = select_clean_materials(
        clean_root=Path(args.clean_root),
        output_root=Path(args.output_root),
        threshold=args.threshold,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def audit_overlays_command(args: argparse.Namespace) -> int:
    summary = audit_overlays(
        input_dir=Path(args.input_dir),
        output_dir=Path(args.output) if args.output else None,
        max_files=args.max_files,
        dirty_threshold=args.dirty_threshold,
        contact_sheet_limit=args.contact_sheet_limit,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def check_delivery_command(args: argparse.Namespace) -> int:
    summary = check_delivery(
        output_dir=Path(args.output_dir),
        expect_vertical=args.expect_vertical,
        min_pack_clips=args.min_pack_clips,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary.get("ok") else 1


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args))
