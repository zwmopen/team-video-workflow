from __future__ import annotations

from pathlib import Path
import csv
import json
import shutil


def select_clean_materials(
    clean_root: Path,
    output_root: Path,
    threshold: float = 0.018,
) -> dict[str, object]:
    clean_root = normalize_path(clean_root).expanduser().resolve()
    output_root = normalize_path(output_root).expanduser().resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    rows = load_clean_rows(clean_root)
    good_rows = [
        row
        for row in rows
        if row_status(row) == "written" and row_score(row) <= threshold and normalize_path(row["output_path"]).exists()
    ]
    good_by_folder: dict[str, list[dict[str, str]]] = {}
    good_by_category: dict[str, list[dict[str, str]]] = {}
    for row in good_rows:
        folder = relative_folder(clean_root, normalize_path(row["output_path"]))
        good_by_folder.setdefault(folder, []).append(row)
        good_by_category.setdefault(first_folder(clean_root, normalize_path(row["output_path"])), []).append(row)

    used_replacements: set[str] = set()
    report_rows: list[dict[str, str]] = []
    for row in rows:
        src = normalize_path(row.get("output_path", ""))
        if row_status(row) != "written" or not src.exists():
            report_rows.append(report_row(row, "", "missing_or_failed", ""))
            continue
        rel = safe_relative(clean_root, src)
        dest = output_root / rel
        score = row_score(row)
        if score <= threshold:
            copy_file(src, dest)
            report_rows.append(report_row(row, str(dest), "kept", ""))
            continue

        replacement = pick_replacement(good_by_folder.get(relative_folder(clean_root, src), []), used_replacements)
        if replacement is None:
            replacement = pick_replacement(good_by_category.get(first_folder(clean_root, src), []), used_replacements)
        if replacement is None:
            replacement = pick_replacement(good_rows, set())
        if replacement is None:
            repair_dest = output_root / "90_待AI修补" / rel
            copy_file(src, repair_dest)
            report_rows.append(report_row(row, str(repair_dest), "needs_ai_repair", ""))
            continue

        replacement_path = normalize_path(replacement["output_path"])
        copy_file(replacement_path, dest)
        used_replacements.add(str(replacement_path))
        report_rows.append(report_row(row, str(dest), "replaced", str(replacement_path)))

    report_dir = output_root / "._clean_report"
    report_dir.mkdir(parents=True, exist_ok=True)
    report_csv = report_dir / "clean_selection.csv"
    write_selection_report(report_csv, report_rows)
    summary = {
        "clean_root": str(clean_root),
        "output_root": str(output_root),
        "threshold": threshold,
        "input_rows": len(rows),
        "kept": sum(1 for row in report_rows if row["action"] == "kept"),
        "replaced": sum(1 for row in report_rows if row["action"] == "replaced"),
        "needs_ai_repair": sum(1 for row in report_rows if row["action"] == "needs_ai_repair"),
        "missing_or_failed": sum(1 for row in report_rows if row["action"] == "missing_or_failed"),
        "report_csv": str(report_csv),
    }
    (report_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def load_clean_rows(clean_root: Path) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for report in clean_root.rglob("._clean_report/clean_materials.csv"):
        with report.open("r", encoding="utf-8-sig", newline="") as handle:
            rows.extend(dict(row) for row in csv.DictReader(handle))
    return rows


def normalize_path(value: str | Path) -> Path:
    text = str(value or "")
    if text.startswith("\\\\?\\"):
        text = text[4:]
    if text.startswith("//?/"):
        text = text[4:]
    return Path(text)


def row_status(row: dict[str, str]) -> str:
    return str(row.get("status") or "")


def row_score(row: dict[str, str]) -> float:
    try:
        return float(row.get("output_text_score") or 0.0)
    except ValueError:
        return 1.0


def safe_relative(root: Path, path: Path) -> Path:
    root = normalize_path(root)
    path = normalize_path(path)
    try:
        return path.resolve().relative_to(root.resolve())
    except ValueError:
        return Path(path.name)


def relative_folder(root: Path, path: Path) -> str:
    rel = safe_relative(root, path)
    if len(rel.parts) <= 1:
        return ""
    return str(Path(*rel.parts[:-1]))


def first_folder(root: Path, path: Path) -> str:
    rel = safe_relative(root, path)
    return rel.parts[0] if rel.parts else ""


def pick_replacement(rows: list[dict[str, str]], used: set[str]) -> dict[str, str] | None:
    for row in sorted(rows, key=row_score):
        output_path = str(row.get("output_path") or "")
        normalized = normalize_path(output_path)
        if output_path and str(normalized) not in used and normalized.exists():
            return row
    return None


def copy_file(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def report_row(row: dict[str, str], selected_path: str, action: str, replacement_path: str) -> dict[str, str]:
    return {
        "source_path": str(normalize_path(row.get("source_path", ""))),
        "clean_path": str(normalize_path(row.get("output_path", ""))),
        "selected_path": selected_path,
        "action": action,
        "replacement_path": replacement_path,
        "output_text_score": row.get("output_text_score", ""),
        "chosen_bottom_pct": row.get("chosen_bottom_pct", ""),
        "mode": row.get("mode", ""),
    }


def write_selection_report(path: Path, rows: list[dict[str, str]]) -> None:
    fields = [
        "source_path",
        "clean_path",
        "selected_path",
        "action",
        "replacement_path",
        "output_text_score",
        "chosen_bottom_pct",
        "mode",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
