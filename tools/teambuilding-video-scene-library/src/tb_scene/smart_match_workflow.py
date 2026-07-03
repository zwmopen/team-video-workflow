from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import json

from .audio_library import AudioLibraryOptions, build_audio_library, default_audio_library_output
from .delivery_checker import check_delivery
from .rough_cut_from_board import RoughCutFromBoardOptions, build_rough_cut_from_board
from .shot_match_board import MatchBoardOptions, build_shot_match_board
from .path_utils import sanitize_name


@dataclass(slots=True)
class SmartMatchWorkflowOptions:
    library_root: Path
    title: str
    output: Path | None = None
    audio_file: Path | None = None
    audio_index: int | None = None
    audio_query: str = ""
    source_dir: Path | None = None
    feedback_file: Path | None = None
    transcribe: bool = False
    model: str = "tiny"
    language: str = "zh"
    max_source_videos: int | None = None
    max_candidates: int = 5
    render_rough_cut: bool = True
    max_clips_per_beat: int = 5
    width: int = 1080
    height: int = 1920
    fps: int = 30


def run_smart_match_workflow(options: SmartMatchWorkflowOptions) -> dict[str, object]:
    library_root = options.library_root.expanduser().resolve()
    if not library_root.exists():
        raise FileNotFoundError(f"Scene library does not exist: {library_root}")
    output = options.output or (library_root.parent / f"{sanitize_name(options.title)}_智能匹配工作流")
    output = output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)

    audio_summary: dict[str, object] = {}
    audio_file = resolve_workflow_audio(options, output, audio_summary)

    board_dir = output / "01_审片板"
    board_summary = build_shot_match_board(
        MatchBoardOptions(
            library_root=library_root,
            title=options.title,
            output=board_dir,
            audio_file=audio_file,
            feedback_file=options.feedback_file,
            max_candidates=options.max_candidates,
        )
    )

    rough_summary: dict[str, object] = {}
    delivery_summary: dict[str, object] = {}
    if options.render_rough_cut:
        rough_dir = output / "02_粗剪成品"
        rough_summary = build_rough_cut_from_board(
            RoughCutFromBoardOptions(
                board_json=Path(str(board_summary["json"])),
                output=rough_dir,
                audio_file=audio_file,
                feedback_file=options.feedback_file,
                width=options.width,
                height=options.height,
                fps=options.fps,
                max_clips_per_beat=options.max_clips_per_beat,
            )
        )
        delivery_summary = check_delivery(rough_dir, expect_vertical=True, min_pack_clips=1)

    summary = {
        "title": options.title,
        "output": str(output),
        "library_root": str(library_root),
        "audio_file": str(audio_file) if audio_file else "",
        "audio_library": audio_summary,
        "board": board_summary,
        "rough_cut": rough_summary,
        "delivery_check": delivery_summary,
    }
    (output / "smart_match_workflow_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    write_workflow_readme(output / "README.md", summary)
    return summary


def resolve_workflow_audio(
    options: SmartMatchWorkflowOptions,
    output: Path,
    audio_summary: dict[str, object],
) -> Path | None:
    library_root = options.library_root.expanduser().resolve()
    if options.audio_file:
        audio = options.audio_file.expanduser().resolve()
        if not audio.exists():
            raise FileNotFoundError(f"Audio file does not exist: {audio}")
        audio_summary.update({"mode": "explicit_audio_file", "audio_library": str(audio.parent)})
        return audio

    existing_audio_dir = default_location_audio_library(library_root)
    if existing_audio_dir.exists() and any(existing_audio_dir.glob("*.m4a")):
        audio_summary.update({"mode": "existing_location_audio_library", "audio_library": str(existing_audio_dir)})
        return pick_workflow_audio(existing_audio_dir, index=options.audio_index, query=options.audio_query)

    source_dir = options.source_dir.expanduser().resolve() if options.source_dir else infer_location_source_dir(library_root)
    if not source_dir or not source_dir.exists():
        raise ValueError(
            "Provide --audio-file or --source-dir, or create the same-level location source folder/audio library"
        )
    audio_output = default_audio_library_output(source_dir)
    audio_summary.update(
        build_audio_library(
            AudioLibraryOptions(
                source_dir=source_dir,
                output=audio_output,
                transcribe=options.transcribe,
                model=options.model,
                language=options.language,
                max_videos=options.max_source_videos,
            )
        )
    )
    audio_summary["mode"] = "built_location_audio_library"
    return pick_workflow_audio(audio_output, index=options.audio_index, query=options.audio_query)


def infer_location_name(library_root: Path) -> str:
    name = library_root.name
    for suffix in ("智能镜头分类", "智能分镜分类", "分镜分类"):
        if name.endswith(suffix):
            return name[: -len(suffix)].strip("-_ ")
    return name.strip("-_ ")


def default_location_audio_library(library_root: Path) -> Path:
    location = infer_location_name(library_root)
    return library_root.parent / f"{location}音频素材库"


def infer_location_source_dir(library_root: Path) -> Path | None:
    location = infer_location_name(library_root)
    parent = library_root.parent
    candidates = [
        parent / f"{location}-原视频素材",
        parent / f"{location}原视频素材",
        parent / f"{location}-原片素材",
        parent / f"{location}原片素材",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return candidates[0] if location else None


def pick_workflow_audio(audio_dir: Path, index: int | None = None, query: str = "") -> Path:
    if index is not None:
        return pick_audio_by_index(audio_dir, index)
    if query.strip():
        return pick_audio_by_query(audio_dir, query)
    return pick_first_ready_audio(audio_dir)


def list_ready_audios(audio_dir: Path) -> list[Path]:
    candidates = sorted(audio_dir.glob("*.m4a"))
    ready = [
        audio
        for audio in candidates
        if audio.with_suffix(".txt").exists()
        and audio.with_suffix(".txt").read_text(encoding="utf-8", errors="replace").strip()
    ]
    return ready or candidates


def pick_audio_by_index(audio_dir: Path, index: int) -> Path:
    if index < 1:
        raise ValueError("--audio-index starts from 1")
    candidates = list_ready_audios(audio_dir)
    if index > len(candidates):
        raise ValueError(f"--audio-index {index} is out of range; audio library has {len(candidates)} usable audios")
    return candidates[index - 1]


def pick_audio_by_query(audio_dir: Path, query: str) -> Path:
    query_text = query.strip().lower()
    candidates = list_ready_audios(audio_dir)
    matches = [audio for audio in candidates if query_text in audio.stem.lower()]
    if not matches:
        raise ValueError(f"No audio filename under {audio_dir} matched query: {query}")
    return matches[0]


def pick_first_ready_audio(audio_dir: Path) -> Path:
    candidates = sorted(audio_dir.glob("*.m4a"))
    for audio in candidates:
        transcript = audio.with_suffix(".txt")
        if transcript.exists() and transcript.read_text(encoding="utf-8", errors="replace").strip():
            return audio
    if candidates:
        return candidates[0]
    raise RuntimeError(f"No audio files were created under: {audio_dir}")


def summarize_audio_library(audio_dir: Path) -> list[dict[str, object]]:
    audio_dir = audio_dir.expanduser().resolve()
    rows: list[dict[str, object]] = []
    for index, audio in enumerate(sorted(audio_dir.glob("*.m4a")), start=1):
        transcript = audio.with_suffix(".txt")
        text = transcript.read_text(encoding="utf-8", errors="replace").strip() if transcript.exists() else ""
        rows.append(
            {
                "index": index,
                "audio_file": str(audio),
                "name": audio.stem,
                "has_transcript": bool(text),
                "transcript_file": str(transcript) if transcript.exists() else "",
                "preview": first_transcript_preview(text),
            }
        )
    return rows


def first_transcript_preview(text: str, limit: int = 80) -> str:
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        return line[:limit]
    return ""


def write_workflow_readme(path: Path, summary: dict[str, object]) -> None:
    board = dict(summary.get("board") or {})
    rough = dict(summary.get("rough_cut") or {})
    delivery = dict(summary.get("delivery_check") or {})
    lines = [
        f"# {summary.get('title')} 智能匹配工作流",
        "",
        "## 审片",
        "",
        f"- HTML: `{board.get('html', '')}`",
        f"- Excel: `{board.get('xlsx', '')}`",
        f"- 反馈表: `{board.get('feedback_csv', '')}`",
        "",
        "## 粗剪",
        "",
        f"- Rough cut: `{rough.get('rough_cut', '')}`",
        f"- Visual track: `{rough.get('visual_track', '')}`",
        f"- Jianying pack: `{rough.get('jianying_pack', '')}`",
        f"- Plan: `{rough.get('plan_csv', '')}`",
        "",
        "## 检查",
        "",
        f"- OK: `{delivery.get('ok', '')}`",
        f"- Issues: `{delivery.get('issues', [])}`",
        f"- Warnings: `{delivery.get('warnings', [])}`",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")
