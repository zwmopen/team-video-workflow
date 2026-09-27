from __future__ import annotations

from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Iterable
import hashlib
import re
import unicodedata

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".webp"}
TEXT_PRIORITY = ("文案.txt", "copywriting.txt", "content.txt")
COMMON_TITLE_NOISE = (
    "快码住", "赶紧码住", "建议收藏", "超详细", "保姆级", "完整版", "来了",
    "真的绝了", "直接抄作业", "HR直接抄作业", "HR宝子们", "不允许你还不知道",
)


@dataclass(slots=True)
class PostContent:
    id: str
    folder: str
    name: str
    text_file: str | None
    original_title: str
    suggested_title: str
    body: str
    title_length: int
    suggested_title_length: int
    body_length: int
    images: list[str]
    image_names: list[str]
    warnings: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)

    @property
    def valid(self) -> bool:
        return not self.errors

    def to_dict(self) -> dict:
        payload = asdict(self)
        payload["valid"] = self.valid
        return payload


@dataclass(slots=True)
class TreeNode:
    id: str
    name: str
    path: str
    kind: str
    children: list["TreeNode"] = field(default_factory=list)
    post: dict | None = None

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "path": self.path,
            "kind": self.kind,
            "children": [child.to_dict() for child in self.children],
            "post": self.post,
        }


def stable_id(path: Path) -> str:
    normalized = str(path.expanduser().resolve(strict=False)).casefold()
    return hashlib.sha256(normalized.encode("utf-8", errors="ignore")).hexdigest()[:24]


def visible_length(text: str) -> int:
    """Approximate user-visible Unicode character count without splitting combining marks.

    This is intentionally dependency-free. It counts base code points, regional-indicator
    pairs and ZWJ emoji sequences as one visible character where practical.
    """
    count = 0
    in_zwj_sequence = False
    regional_pending = False
    for char in text:
        code = ord(char)
        if char == "\u200d":
            in_zwj_sequence = True
            continue
        if unicodedata.combining(char) or 0xFE00 <= code <= 0xFE0F or 0x1F3FB <= code <= 0x1F3FF:
            continue
        if 0x1F1E6 <= code <= 0x1F1FF:
            if regional_pending:
                regional_pending = False
            else:
                count += 1
                regional_pending = True
            continue
        regional_pending = False
        if in_zwj_sequence:
            in_zwj_sequence = False
            continue
        count += 1
    return count


def truncate_visible(text: str, limit: int) -> str:
    if limit <= 0:
        return ""
    output: list[str] = []
    count = 0
    i = 0
    while i < len(text):
        char = text[i]
        cluster = [char]
        i += 1
        while i < len(text):
            nxt = text[i]
            code = ord(nxt)
            if unicodedata.combining(nxt) or 0xFE00 <= code <= 0xFE0F or 0x1F3FB <= code <= 0x1F3FF:
                cluster.append(nxt)
                i += 1
                continue
            if nxt == "\u200d" and i + 1 < len(text):
                cluster.extend([nxt, text[i + 1]])
                i += 2
                continue
            break
        if count >= limit:
            break
        output.extend(cluster)
        count += 1
    return "".join(output).rstrip(" -—_|，。！？!?:：")


def suggest_title(title: str, trigger: int = 24, target: int = 20) -> str:
    clean = re.sub(r"\s+", "", title.strip())
    clean = re.sub(r"[｜|]+", "｜", clean)
    if visible_length(clean) <= trigger:
        return clean
    candidate = clean
    for phrase in COMMON_TITLE_NOISE:
        candidate = candidate.replace(phrase, "")
    candidate = re.sub(r"([!！?？])\1+", r"\1", candidate)
    candidate = re.sub(r"[🔥✨👏🏻👏👍‼️❗️💥⚡]+$", "", candidate)
    candidate = candidate.strip(" -—_|，。！？!?:：")
    if visible_length(candidate) > target:
        candidate = truncate_visible(candidate, target)
    return candidate or truncate_visible(clean, target)


def natural_key(path_or_name: Path | str) -> tuple:
    name = Path(path_or_name).name if isinstance(path_or_name, Path) else str(path_or_name)
    parts = re.split(r"(\d+)", name.casefold())
    return tuple(int(part) if part.isdigit() else part for part in parts)


def choose_text_file(folder: Path) -> tuple[Path | None, list[str]]:
    txt_files = [p for p in folder.iterdir() if p.is_file() and p.suffix.casefold() == ".txt"]
    by_name = {p.name.casefold(): p for p in txt_files}
    for preferred in TEXT_PRIORITY:
        found = by_name.get(preferred.casefold())
        if found:
            return found, []
    named = [p for p in txt_files if "文案" in p.stem]
    if len(named) == 1:
        return named[0], []
    if len(txt_files) == 1:
        return txt_files[0], []
    if not txt_files:
        return None, ["文件夹中没有找到 TXT 文案文件"]
    return None, [f"发现 {len(txt_files)} 个 TXT 文件，无法确定哪一个是发布文案"]


def read_text(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def split_title_body(text: str) -> tuple[str, str]:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = normalized.split("\n")
    title_index = next((index for index, line in enumerate(lines) if line.strip()), None)
    if title_index is None:
        return "", ""
    title = lines[title_index].strip()
    body = "\n".join(lines[title_index + 1 :]).strip()
    body = re.sub(r"\n{3,}", "\n\n", body)
    return title, body


def list_images(folder: Path) -> list[Path]:
    return sorted(
        [p for p in folder.iterdir() if p.is_file() and p.suffix.casefold() in IMAGE_EXTENSIONS],
        key=natural_key,
    )


def is_post_folder(folder: Path) -> bool:
    if not folder.is_dir():
        return False
    try:
        has_image = any(p.is_file() and p.suffix.casefold() in IMAGE_EXTENSIONS for p in folder.iterdir())
        has_txt = any(p.is_file() and p.suffix.casefold() == ".txt" for p in folder.iterdir())
        return has_image and has_txt
    except OSError:
        return False


def parse_post_folder(
    folder: Path,
    *,
    title_trigger: int = 24,
    title_target: int = 20,
    body_soft_limit: int = 1000,
    max_images: int = 10,
) -> PostContent:
    folder = folder.expanduser().resolve(strict=False)
    warnings: list[str] = []
    errors: list[str] = []
    text_file, text_errors = choose_text_file(folder)
    errors.extend(text_errors)
    title = ""
    body = ""
    if text_file:
        try:
            title, body = split_title_body(read_text(text_file))
        except OSError as exc:
            errors.append(f"读取文案失败：{exc}")
    if not title:
        errors.append("TXT 第一条非空行没有可用标题")
    if not body:
        warnings.append("正文为空，发布前需要补充")

    images = list_images(folder)
    if not images:
        errors.append("文件夹中没有可发布图片")
    if len(images) > max_images:
        errors.append(f"图片数量为 {len(images)} 张，超过当前业务上限 {max_images} 张")

    title_length = visible_length(title)
    suggested = suggest_title(title, title_trigger, title_target) if title else ""
    if title_length > title_trigger:
        warnings.append(
            f"原标题 {title_length} 字，已生成 {visible_length(suggested)} 字建议标题；发布前可继续人工调整"
        )
    body_length = visible_length(body)
    if body_length > body_soft_limit:
        warnings.append(
            f"正文约 {body_length} 字，超过 {body_soft_limit} 字软限制；系统不会截断，将尝试保存草稿"
        )

    return PostContent(
        id=stable_id(folder),
        folder=str(folder),
        name=folder.name,
        text_file=str(text_file) if text_file else None,
        original_title=title,
        suggested_title=suggested,
        body=body,
        title_length=title_length,
        suggested_title_length=visible_length(suggested),
        body_length=body_length,
        images=[str(path) for path in images],
        image_names=[path.name for path in images],
        warnings=warnings,
        errors=errors,
    )


def _walk_directory(folder: Path, depth: int, max_depth: int) -> TreeNode | None:
    if depth > max_depth or not folder.is_dir():
        return None
    if is_post_folder(folder):
        post = parse_post_folder(folder)
        return TreeNode(
            id=post.id,
            name=folder.name,
            path=str(folder),
            kind="post",
            post={
                "valid": post.valid,
                "title": post.suggested_title or post.original_title,
                "image_count": len(post.images),
                "body_length": post.body_length,
                "warnings": post.warnings,
                "errors": post.errors,
            },
        )

    children: list[TreeNode] = []
    try:
        directories = sorted((p for p in folder.iterdir() if p.is_dir()), key=natural_key)
    except OSError:
        directories = []
    for child in directories:
        node = _walk_directory(child, depth + 1, max_depth)
        if node is not None:
            children.append(node)
    if not children and depth > 0:
        return None
    return TreeNode(
        id=stable_id(folder),
        name=folder.name,
        path=str(folder),
        kind="root" if depth == 0 else "collection",
        children=children,
    )


def scan_library(root: Path, max_depth: int = 5) -> TreeNode:
    root = root.expanduser().resolve(strict=False)
    if not root.exists() or not root.is_dir():
        raise FileNotFoundError(f"成品库目录不存在：{root}")
    node = _walk_directory(root, 0, max_depth)
    if node is None:
        return TreeNode(id=stable_id(root), name=root.name, path=str(root), kind="root")
    return node


def flatten_posts(node: TreeNode) -> Iterable[TreeNode]:
    if node.kind == "post":
        yield node
    for child in node.children:
        yield from flatten_posts(child)
