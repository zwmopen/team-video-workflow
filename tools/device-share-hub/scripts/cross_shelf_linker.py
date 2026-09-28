#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Cross Shelf Linker (跨城市/多主题货架软链接与目录联接中枢)
======================================================
实现作品在多货架间的无损复用：
1. 采用 Windows NTFS 原生 Junction (目录联接)，无需管理员权限，零物理存储消耗；
2. 双向畅读：任何货架均可正常浏览原图与文案；
3. 联动安全自愈：
   - 操作软链接镜像：仅解绑副货架软链接，本体原封不动保留在主货架；
   - 操作物理本体：移走/删除本体的同时，自动扫描并物理清除所有指向该本体的软链接，防止死链；
   - 自愈巡检：定期或按需自动扫描自愈死链（Dangling Junctions）；
4. 内部台账追踪：维护 `_内部台账与历史数据/junction_links_registry.json`。
"""

import os
import sys
import json
import time
import shutil
import argparse
import subprocess
from typing import List, Dict, Any, Optional, Tuple

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# 默认成品库路径
DEFAULT_GALLERY_ROOT = os.path.normpath(r"D:\AICode\项目推进\projects\江湖有旅人\主项目\成品库（GPT+本地脚本制作）")
REGISTRY_REL_PATH = os.path.join("_内部台账与历史数据", "junction_links_registry.json")

# Windows Reparse Tag 常量
IO_REPARSE_TAG_MOUNT_POINT = 0xA0000003  # Junction / Mount point
IO_REPARSE_TAG_SYMLINK = 0xA000000C      # Symbolic link


def is_junction_or_symlink(path: str) -> bool:
    """判断指定路径是否为 Junction (目录联接) 或符号链接。
    
    即便目标不存在（死链），此函数亦能准确识别。
    """
    if not os.path.lexists(path):
        return False
    # 标准符号链接（Unix 或 Windows 开发者模式启用下）
    if os.path.islink(path):
        return True
    # Windows NTFS Junction 重新分析点检测
    try:
        st = os.lstat(path)
        tag = getattr(st, "st_reparse_tag", 0)
        if tag in (IO_REPARSE_TAG_MOUNT_POINT, IO_REPARSE_TAG_SYMLINK):
            return True
    except Exception:
        pass
    # 尝试 os.readlink 探测
    try:
        os.readlink(path)
        return True
    except (OSError, ValueError):
        pass
    return False


def resolve_junction_source(path: str) -> Optional[str]:
    """解析 Junction 或符号链接所指向的源目标物理绝对路径。
    
    返回标准 Windows 路径（清洗掉 \\?\\ 前缀）；若无法解析则返回 None。
    """
    if not is_junction_or_symlink(path):
        return None
    try:
        raw_target = os.readlink(path)
        clean = raw_target.replace("\\\\?\\", "").replace("/?/", "")
        if not os.path.isabs(clean):
            clean = os.path.normpath(os.path.join(os.path.dirname(path), clean))
        return os.path.abspath(clean)
    except Exception:
        pass
    return None


def get_registry_path(root_dir: Optional[str] = None) -> str:
    root = os.path.abspath(root_dir or DEFAULT_GALLERY_ROOT)
    return os.path.join(root, REGISTRY_REL_PATH)


def load_registry(root_dir: Optional[str] = None) -> Dict[str, Any]:
    reg_file = get_registry_path(root_dir)
    if os.path.exists(reg_file):
        try:
            with open(reg_file, "r", encoding="utf-8") as fp:
                data = json.load(fp)
                if isinstance(data, dict) and "links" in data:
                    return data
        except Exception as e:
            print(f"[Warn] 读取软链接台账失败: {e}")
    return {
        "version": "1.0",
        "description": "成品库跨城市/多主题货架软链接(Junction)映射台账",
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S+08:00"),
        "links": []
    }


def save_registry(registry: Dict[str, Any], root_dir: Optional[str] = None) -> None:
    reg_file = get_registry_path(root_dir)
    os.makedirs(os.path.dirname(reg_file), exist_ok=True)
    registry["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S+08:00")
    try:
        with open(reg_file, "w", encoding="utf-8") as fp:
            json.dump(registry, fp, ensure_ascii=False, indent=2)
    except Exception as e:
        print(f"[Error] 保存软链接台账失败: {e}")


def create_junction(source_path: str, target_shelf_or_dir: str, link_name: Optional[str] = None, root_dir: Optional[str] = None) -> Tuple[bool, str, str]:
    """在副货架为源作品建立 Windows NTFS 目录联接 (Junction)。
    
    无需管理员权限，支持跨货架双向读取。
    """
    root = os.path.abspath(root_dir or DEFAULT_GALLERY_ROOT)
    source_path = os.path.abspath(source_path)

    if not os.path.isdir(source_path):
        return False, "", f"源作品目录不存在: {source_path}"

    if is_junction_or_symlink(source_path):
        return False, "", f"源路径自身已是软链接/Junction，禁止多层嵌套: {source_path}"

    # 判断 target_shelf_or_dir 是否为绝对路径或货架名
    if os.path.isabs(target_shelf_or_dir):
        shelf_dir = os.path.abspath(target_shelf_or_dir)
    else:
        shelf_dir = os.path.join(root, target_shelf_or_dir)

    os.makedirs(shelf_dir, exist_ok=True)

    folder_name = link_name or os.path.basename(source_path)
    link_path = os.path.join(shelf_dir, folder_name)

    if os.path.lexists(link_path):
        if is_junction_or_symlink(link_path):
            existing_src = resolve_junction_source(link_path)
            if existing_src and os.path.abspath(existing_src) == source_path:
                return True, link_path, "软链接已存在且指向正确"
            return False, link_path, f"目标路径已存在其他软链接: 指向 {existing_src}"
        return False, link_path, f"目标路径已存在同名物理文件或目录: {link_path}"

    # 创建 Junction
    created = False
    err_msg = ""
    # 策略 1：Python 标准库 _winapi (Windows 原生，零依赖，免特权)
    try:
        import _winapi
        _winapi.CreateJunction(source_path, link_path)
        created = True
    except Exception as e:
        err_msg = f"_winapi 失败: {e}"

    # 策略 2：cmd /c mklink /J (Windows 原生命令兜底)
    if not created and sys.platform == "win32":
        try:
            res = subprocess.run(
                ["cmd", "/c", "mklink", "/J", link_path, source_path],
                capture_output=True, text=True, check=True
            )
            created = True
        except Exception as e:
            err_msg += f"; cmd mklink 失败: {e}"

    # 策略 3：非 Windows 环境或兜底符号链接
    if not created:
        try:
            os.symlink(source_path, link_path, target_is_directory=True)
            created = True
        except Exception as e:
            err_msg += f"; os.symlink 失败: {e}"

    if not created:
        return False, "", f"创建 Junction 失败: {err_msg}"

    # 更新台账
    reg = load_registry(root)
    source_shelf = os.path.basename(os.path.dirname(source_path))
    target_shelf = os.path.basename(shelf_dir)
    # 移除旧项（如果有）
    reg["links"] = [item for item in reg.get("links", []) if os.path.abspath(item.get("link_path", "")) != os.path.abspath(link_path)]
    reg["links"].append({
        "link_path": link_path,
        "source_path": source_path,
        "link_shelf": target_shelf,
        "source_shelf": source_shelf,
        "title": folder_name,
        "created_at": time.strftime("%Y-%m-%d %H:%M:%S")
    })
    save_registry(reg, root)

    return True, link_path, f"成功在「{target_shelf}」建立软链接镜像"


def unlink_junction(link_path: str, root_dir: Optional[str] = None) -> Tuple[bool, str]:
    """安全解绑/移除 Junction 软链接。
    
    【核心安全防护】：
    1. 必须首先确认目标确实是 Junction 或符号链接；
    2. 绝对严禁用递归 rmtree，必须使用 os.rmdir 或 Windows rmdir，仅解绑重新分析点，
       绝对不破坏源物理目录里的任何原图、文案或标签文件！
    """
    link_path = os.path.abspath(link_path)
    if not os.path.lexists(link_path):
        # 链接已不存在，同步清理台账
        _remove_from_registry(link_path, root_dir)
        return True, "软链接路径已不存在"

    if not is_junction_or_symlink(link_path):
        return False, f"拒绝操作：目标并非软链接/Junction，防止误删物理真实作品: {link_path}"

    success = False
    err_msg = ""
    # 优先 os.rmdir（在 Windows 上对 Junction 仅解绑重新分析点，零伤本体）
    try:
        os.rmdir(link_path)
        success = True
    except Exception as e:
        err_msg = str(e)

    # 兜底：cmd /c rmdir (Windows 原生解绑)
    if not success and sys.platform == "win32":
        try:
            subprocess.run(["cmd", "/c", "rmdir", link_path], capture_output=True, check=True)
            success = True
        except Exception as e:
            err_msg += f"; cmd rmdir 失败: {e}"

    if success:
        _remove_from_registry(link_path, root_dir)
        return True, "成功解除软链接"
    return False, f"解除软链接失败: {err_msg}"


def _remove_from_registry(link_path: str, root_dir: Optional[str] = None) -> None:
    root = os.path.abspath(root_dir or DEFAULT_GALLERY_ROOT)
    reg = load_registry(root)
    norm_link = os.path.normcase(os.path.abspath(link_path))
    new_links = [item for item in reg.get("links", []) if os.path.normcase(os.path.abspath(item.get("link_path", ""))) != norm_link]
    if len(new_links) != len(reg.get("links", [])):
        reg["links"] = new_links
        save_registry(reg, root)


def cleanup_junctions_for_source(source_path: str, root_dir: Optional[str] = None) -> List[str]:
    """当物理本体被移动（如移入已发送1次、垃圾库）或删除时，自动扫描并清除所有指向它的软链接镜像。
    
    返回被成功解除的软链接列表。杜绝遗留失效死链。
    """
    root = os.path.abspath(root_dir or DEFAULT_GALLERY_ROOT)
    norm_src = os.path.normcase(os.path.abspath(source_path))
    src_folder_name = os.path.basename(source_path)
    removed_links = []

    # 1. 查阅台账
    reg = load_registry(root)
    links_to_check = []
    for item in reg.get("links", []):
        link_p = item.get("link_path", "")
        item_src = item.get("source_path", "")
        if os.path.normcase(os.path.abspath(item_src)) == norm_src or os.path.basename(link_p) == src_folder_name:
            links_to_check.append(link_p)

    # 2. 全货架扫描（双保险，避免台账未记录的遗漏 Junction）
    try:
        for shelf in os.listdir(root):
            if shelf.startswith(".") or shelf.startswith("_"):
                continue
            shelf_p = os.path.join(root, shelf)
            if not os.path.isdir(shelf_p):
                continue
            cand = os.path.join(shelf_p, src_folder_name)
            if cand not in links_to_check and is_junction_or_symlink(cand):
                links_to_check.append(cand)
    except Exception:
        pass

    # 3. 执行解除
    for lp in set(links_to_check):
        target = resolve_junction_source(lp)
        # 如果指向此源，或者指向一个已不存在的路径但名字相同
        if (target and os.path.normcase(os.path.abspath(target)) == norm_src) or not os.path.exists(lp):
            ok, _ = unlink_junction(lp, root)
            if ok:
                removed_links.append(lp)
                print(f"[JunctionHealer] 已安全自动清除本体关联软链接: {lp} -> {source_path}")

    return removed_links


def prune_dangling_junctions(root_dir: Optional[str] = None) -> List[str]:
    """扫描所有货架目录，自愈清理所有源物理路径已失效的死链软链接 (Dangling Junctions)。
    
    返回被清理的死链路径列表。
    """
    root = os.path.abspath(root_dir or DEFAULT_GALLERY_ROOT)
    cleaned = []
    if not os.path.isdir(root):
        return cleaned

    try:
        shelves = os.listdir(root)
    except Exception:
        return cleaned

    for shelf in shelves:
        if shelf.startswith(".") or shelf.startswith("_"):
            continue
        shelf_p = os.path.join(root, shelf)
        if not os.path.isdir(shelf_p):
            continue

        try:
            items = os.listdir(shelf_p)
        except Exception:
            continue

        for item in items:
            item_p = os.path.join(shelf_p, item)
            if is_junction_or_symlink(item_p):
                # 检查所指目标是否存在
                target = resolve_junction_source(item_p)
                if not target or not os.path.exists(target):
                    ok, _ = unlink_junction(item_p, root)
                    if ok:
                        cleaned.append(item_p)
                        print(f"[JunctionHealer] 自愈清理死链: {item_p} (原指向: {target})")

    # 顺便清理台账中指向已不存在链接的条目
    reg = load_registry(root)
    active_links = []
    for item in reg.get("links", []):
        lp = item.get("link_path", "")
        sp = item.get("source_path", "")
        if is_junction_or_symlink(lp) and os.path.exists(sp):
            active_links.append(item)
    if len(active_links) != len(reg.get("links", [])):
        reg["links"] = active_links
        save_registry(reg, root)

    return cleaned


def list_junctions(root_dir: Optional[str] = None) -> List[Dict[str, Any]]:
    """列出当前所有货架中生效的软链接及其状态"""
    root = os.path.abspath(root_dir or DEFAULT_GALLERY_ROOT)
    reg = load_registry(root)
    result = []
    for item in reg.get("links", []):
        lp = item.get("link_path", "")
        sp = item.get("source_path", "")
        is_link = is_junction_or_symlink(lp)
        src_exists = os.path.exists(sp)
        status = "正常" if (is_link and src_exists) else ("死链(源不存在)" if is_link else "链接不存在")
        res_item = dict(item)
        res_item["status"] = status
        res_item["active"] = bool(is_link and src_exists)
        result.append(res_item)
    return result


def main():
    parser = argparse.ArgumentParser(description="跨城市/多主题货架软链接 (Junction) 管理工具")
    subparsers = parser.add_subparsers(dest="command", help="子命令")

    # create
    p_create = subparsers.add_parser("create", help="为源作品建立副货架软链接")
    p_create.add_argument("source", help="源作品目录物理绝对路径")
    p_create.add_argument("target_shelf", help="目标货架目录名或路径（如 中秋国庆成品）")
    p_create.add_argument("--name", help="软链接目录名（默认同源目录名）")
    p_create.add_argument("--root", default=DEFAULT_GALLERY_ROOT, help="成品库根目录")

    # remove
    p_remove = subparsers.add_parser("remove", help="安全解绑/移除指定的软链接")
    p_remove.add_argument("link_path", help="要解除的软链接目录路径")
    p_remove.add_argument("--root", default=DEFAULT_GALLERY_ROOT, help="成品库根目录")

    # list
    p_list = subparsers.add_parser("list", help="查看所有登记在册与生效的软链接")
    p_list.add_argument("--root", default=DEFAULT_GALLERY_ROOT, help="成品库根目录")

    # prune
    p_prune = subparsers.add_parser("prune", help="扫描所有货架自愈清理失效死链")
    p_prune.add_argument("--root", default=DEFAULT_GALLERY_ROOT, help="成品库根目录")

    args = parser.parse_args()

    if args.command == "create":
        ok, path, msg = create_junction(args.source, args.target_shelf, args.name, args.root)
        print(f"[{'OK' if ok else 'FAIL'}] {msg}: {path}")
        sys.exit(0 if ok else 1)
    elif args.command == "remove":
        ok, msg = unlink_junction(args.link_path, args.root)
        print(f"[{'OK' if ok else 'FAIL'}] {msg}")
        sys.exit(0 if ok else 1)
    elif args.command == "list":
        links = list_junctions(args.root)
        print(f"=== 软链接(Junction)台账清单 ({len(links)} 项) ===")
        for i, item in enumerate(links, 1):
            print(f"{i:2d}. [{item.get('status')}] {item.get('title')}")
            print(f"    源路径: {item.get('source_path')} ({item.get('source_shelf')})")
            print(f"    软链接: {item.get('link_path')} ({item.get('link_shelf')})")
    elif args.command == "prune":
        cleaned = prune_dangling_junctions(args.root)
        print(f"自愈扫描完成，已清理 {len(cleaned)} 个死链: {cleaned}")
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
