from __future__ import annotations

from pathlib import Path
import json
import sqlite3
from typing import Any


def transcribe_sources(
    library_root: Path,
    model_name: str = "tiny",
    language: str = "zh",
    max_sources: int | None = None,
    force: bool = False,
) -> dict[str, object]:
    library_root = library_root.expanduser().resolve()
    system_dir = library_root / "._系统记录"
    db_path = system_dir / "project.sqlite"
    transcript_dir = system_dir / "transcripts"
    transcript_dir.mkdir(parents=True, exist_ok=True)
    sources = load_sources(db_path)
    sources = [source for source in sources if source.get("status") in {"processed", "interrupted"}]
    if max_sources:
        sources = sources[:max_sources]

    import whisper

    model = whisper.load_model(model_name)
    written = 0
    skipped = 0
    failed: list[dict[str, str]] = []
    for source in sources:
        source_id = str(source["source_video_id"])
        source_path = Path(str(source["source_video_path"]))
        output_path = transcript_dir / f"{source_id}.json"
        if output_path.exists() and not force:
            skipped += 1
            continue
        if not source_path.exists():
            failed.append({"source_video_id": source_id, "error": "source missing"})
            continue
        try:
            result = model.transcribe(str(source_path), language=language, fp16=False, verbose=False)
            payload = {
                "source_video_id": source_id,
                "source_video_name": source.get("source_video_name", source_path.name),
                "source_video_path": str(source_path),
                "language": language,
                "model": model_name,
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
            output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
            written += 1
        except Exception as exc:
            failed.append({"source_video_id": source_id, "error": str(exc)})

    summary = {"library_root": str(library_root), "written": written, "skipped": skipped, "failed": failed}
    (transcript_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def load_sources(db_path: Path) -> list[dict[str, Any]]:
    if not db_path.exists():
        return []
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    try:
        return [dict(row) for row in con.execute("select * from source_videos order by source_video_id")]
    finally:
        con.close()


def transcript_text_for_scene(library_root: Path, source_video_id: str, start: float, end: float) -> str:
    transcript_path = library_root / "._系统记录" / "transcripts" / f"{source_video_id}.json"
    if not transcript_path.exists():
        return ""
    try:
        payload = json.loads(transcript_path.read_text(encoding="utf-8"))
    except Exception:
        return ""
    pieces: list[str] = []
    center = (start + end) / 2
    for segment in payload.get("segments", []):
        seg_start = float(segment.get("start", 0.0))
        seg_end = float(segment.get("end", 0.0))
        # Add a small window because Douyin narration often leads the picture slightly.
        if seg_end >= start - 1.5 and seg_start <= end + 1.5:
            pieces.append(str(segment.get("text", "")))
        elif seg_start <= center <= seg_end:
            pieces.append(str(segment.get("text", "")))
    return "\n".join(piece.strip() for piece in pieces if piece.strip())
