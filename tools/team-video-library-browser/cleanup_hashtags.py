from __future__ import annotations

import argparse
import csv
import json
import re
from datetime import datetime
from pathlib import Path


TEXT_EXTS = {
    ".txt", ".md", ".csv", ".json", ".html", ".htm", ".srt", ".vtt", ".lrc",
    ".log", ".yaml", ".yml",
}
EXCEL_EXTS = {".xlsx"}
SKIP_DIR_NAMES = {
    ".git", "__pycache__", "cache", "preview_cache", "node_modules",
}


def clean_stem(stem: str) -> str:
    positions = [p for p in (stem.find("#"), stem.find("＃")) if p >= 0]
    if positions:
        stem = stem[: min(positions)]
    stem = re.sub(r"\s+", " ", stem).strip()
    stem = re.sub(r"[\s_\-｜|,，、。;；~～!！]+$", "", stem).strip()
    return stem or "未命名素材"


def unique_target(path: Path, wanted_name: str, reserved: set[str] | None = None) -> Path:
    reserved = reserved or set()
    target = path.with_name(wanted_name)
    target_key = str(target).casefold()
    if (not target.exists() or target == path) and target_key not in reserved:
        return target
    stem = target.stem
    suffix = target.suffix
    for index in range(1, 1000):
        candidate = target.with_name(f"{stem}_去话题{index:02d}{suffix}")
        candidate_key = str(candidate).casefold()
        if not candidate.exists() and candidate_key not in reserved:
            return candidate
    raise RuntimeError(f"Cannot find unique name for {target}")


def iter_files(root: Path):
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        parts = set(path.parts)
        if parts & SKIP_DIR_NAMES:
            continue
        yield path


def build_rename_plan(root: Path):
    plan = []
    reserved = {str(path).casefold() for path in iter_files(root)}
    for path in iter_files(root):
        if "#" not in path.stem and "＃" not in path.stem:
            continue
        new_stem = clean_stem(path.stem)
        wanted_name = f"{new_stem}{path.suffix}"
        if wanted_name == path.name:
            continue
        reserved.discard(str(path).casefold())
        target = unique_target(path, wanted_name, reserved)
        reserved.add(str(target).casefold())
        plan.append((path, target))
    return plan


def clean_hashtags_in_line(line: str) -> str:
    stripped = line.lstrip()
    # Keep Markdown headings such as "# 标题" and "## 小节".
    if re.match(r"^#{1,6}\s+", stripped):
        return line
    line = re.sub(r"[#＃]+[^\s#＃,，。；;、\]\[）)（(]+", "", line)
    line = re.sub(r"[ \t]{2,}", " ", line)
    line = re.sub(r"\s+([,，。；;、])", r"\1", line)
    return line


def clean_text(text: str, replacements: list[tuple[str, str]]) -> str:
    for old, new in replacements:
        text = text.replace(old, new)
    lines = [clean_hashtags_in_line(line) for line in text.splitlines(keepends=True)]
    return "".join(lines)


def read_text(path: Path):
    data = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return data.decode(encoding), encoding
        except UnicodeDecodeError:
            continue
    return None, None


def update_text_files(root: Path, replacements: list[tuple[str, str]], dry_run: bool):
    changed = []
    for path in iter_files(root):
        if path.suffix.lower() not in TEXT_EXTS:
            continue
        text, encoding = read_text(path)
        if text is None:
            continue
        updated = clean_text(text, replacements)
        if updated == text:
            continue
        changed.append(path)
        if not dry_run:
            path.write_text(updated, encoding="utf-8")
    return changed


def update_excel_files(root: Path, replacements: list[tuple[str, str]], dry_run: bool):
    try:
        import openpyxl  # type: ignore
    except Exception:
        return [], "openpyxl unavailable"
    changed = []
    skipped = []
    for path in iter_files(root):
        if path.suffix.lower() not in EXCEL_EXTS:
            continue
        try:
            workbook = openpyxl.load_workbook(path)
        except Exception:
            continue
        touched = False
        for sheet in workbook.worksheets:
            for row in sheet.iter_rows():
                for cell in row:
                    if not isinstance(cell.value, str):
                        continue
                    updated = clean_text(cell.value, replacements)
                    if updated != cell.value:
                        cell.value = updated
                        touched = True
        if touched:
            if not dry_run:
                try:
                    workbook.save(path)
                except PermissionError:
                    skipped.append(path)
                    continue
            changed.append(path)
    note = None
    if skipped:
        note = "Skipped locked Excel files: " + "; ".join(str(p) for p in skipped)
    return changed, note


def apply_renames(plan: list[tuple[Path, Path]], dry_run: bool):
    applied = []
    for old, new in plan:
        applied.append((old, new))
        if not dry_run:
            new.parent.mkdir(parents=True, exist_ok=True)
            old.rename(new)
    return applied


def write_report(report_dir: Path, plan, text_changed, excel_changed, dry_run: bool, excel_note: str | None):
    report_dir.mkdir(parents=True, exist_ok=True)
    mapping_csv = report_dir / "hashtag_cleanup_rename_map.csv"
    with mapping_csv.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        writer.writerow(["old_path", "new_path"])
        for old, new in plan:
            writer.writerow([str(old), str(new)])
    report = {
        "dry_run": dry_run,
        "renamed_files": len(plan),
        "updated_text_records": len(text_changed),
        "updated_excel_records": len(excel_changed),
        "excel_note": excel_note,
        "report_dir": str(report_dir),
    }
    (report_dir / "hashtag_cleanup_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (report_dir / "hashtag_cleanup_updated_records.txt").write_text(
        "\n".join(str(p) for p in [*text_changed, *excel_changed]),
        encoding="utf-8",
    )
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    root = Path(args.root)
    if not root.exists():
        raise SystemExit(f"Root not found: {root}")

    dry_run = not args.apply
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    report_dir = root / "._采集记录" / f"井号话题清理_{stamp}"

    plan = build_rename_plan(root)
    replacements: list[tuple[str, str]] = []
    for old, new in plan:
        replacements.extend([
            (str(old), str(new)),
            (old.name, new.name),
            (old.stem, new.stem),
        ])
    replacements.sort(key=lambda pair: len(pair[0]), reverse=True)

    applied = apply_renames(plan, dry_run=dry_run)
    text_changed = update_text_files(root, replacements, dry_run=dry_run)
    excel_changed, excel_note = update_excel_files(root, replacements, dry_run=dry_run)
    report = write_report(report_dir, applied, text_changed, excel_changed, dry_run, excel_note)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
