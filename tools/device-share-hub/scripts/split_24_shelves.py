# -*- coding: utf-8 -*-
"""
成品库 24 大货架物理拆分工具。

默认只输出按作品目录名生成的移动预演；只有显式传入 --apply 并手动输入
APPLY 后才会尝试移动。此脚本不替代迁移台账、哈希核验或业务验收。
"""

import argparse
import hashlib
import os
import stat
import sys

if sys.stdout and hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

PORTFOLIO_ROOT = os.path.normpath(r"D:\江湖有旅人\03-生产车间\05-成品库")
FILE_ATTRIBUTE_REPARSE_POINT = 0x400


def is_reparse_point(path):
    try:
        info = os.lstat(path)
    except OSError as error:
        raise OSError(f"无法核实路径属性，按重解析点风险拒绝: {path}") from error
    attributes = getattr(info, "st_file_attributes", None)
    if attributes is None:
        raise OSError(f"无法读取重解析点属性，按风险拒绝: {path}")
    return bool(attributes & FILE_ATTRIBUTE_REPARSE_POINT)


def entry_identity(path):
    info = os.lstat(path)
    return (info.st_dev, info.st_ino, info.st_mtime_ns, info.st_size, info.st_mode)


def directory_identity(path):
    info = os.lstat(path)
    attributes = getattr(info, "st_file_attributes", None)
    if attributes is None:
        raise OSError(f"无法读取货架属性，按风险拒绝: {path}")
    return (info.st_dev, info.st_ino, info.st_mode, attributes)


def path_device(path):
    return os.stat(path).st_dev


def tree_identity(root):
    """Return a metadata snapshot for every entry, rejecting nested reparse points."""
    root = os.path.abspath(os.fspath(root))
    pending = [root]
    snapshot = []
    while pending:
        current = pending.pop()
        if is_reparse_point(current):
            raise OSError(f"作品树包含联接/重解析点，需人工核实: {current}")
        info = os.lstat(current)
        attributes = getattr(info, "st_file_attributes", None)
        if attributes is None:
            raise OSError(f"无法读取重解析点属性，按风险拒绝: {current}")
        relative = os.path.relpath(current, root)
        snapshot.append(
            (
                relative,
                info.st_dev,
                info.st_ino,
                info.st_mtime_ns,
                info.st_size,
                info.st_mode,
                attributes,
            )
        )
        if stat.S_ISDIR(info.st_mode):
            with os.scandir(current) as entries:
                children = sorted(entries, key=lambda entry: entry.name, reverse=True)
            for child in children:
                child_info = os.lstat(child.path)
                if stat.S_ISDIR(child_info.st_mode):
                    pending.append(child.path)
                elif stat.S_ISREG(child_info.st_mode):
                    if is_reparse_point(child.path):
                        raise OSError(f"作品树包含联接/重解析点，需人工核实: {child.path}")
                    snapshot.append(
                        (
                            os.path.relpath(child.path, root),
                            child_info.st_dev,
                            child_info.st_ino,
                            child_info.st_mtime_ns,
                            child_info.st_size,
                            child_info.st_mode,
                            getattr(child_info, "st_file_attributes", None),
                        )
                    )
                else:
                    raise OSError(f"作品树含非普通文件类型，需人工核实: {child.path}")
    return tuple(sorted(snapshot))


def tree_content_digest(root):
    """Hash relative paths and file bytes for explicit apply-time verification."""
    root = os.path.abspath(os.fspath(root))
    digest = hashlib.sha256()

    def raise_walk_error(error):
        raise error

    for current, dirnames, filenames in os.walk(
        root, topdown=True, onerror=raise_walk_error, followlinks=False
    ):
        dirnames.sort()
        filenames.sort()
        for name in dirnames:
            path = os.path.join(current, name)
            if is_reparse_point(path):
                raise OSError(f"作品树包含联接/重解析点，需人工核实: {path}")
        for name in filenames:
            path = os.path.join(current, name)
            if is_reparse_point(path):
                raise OSError(f"作品树包含联接/重解析点，需人工核实: {path}")
            relative = os.path.relpath(path, root).replace(os.sep, "/")
            digest.update(relative.encode("utf-8", errors="surrogatepass"))
            digest.update(b"\0")
            with open(path, "rb") as handle:
                before = os.fstat(handle.fileno())
                while True:
                    chunk = handle.read(1024 * 1024)
                    if not chunk:
                        break
                    digest.update(chunk)
                after = os.fstat(handle.fileno())
            before_identity = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
            after_identity = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
            if before_identity != after_identity:
                raise OSError(f"计算哈希时文件发生变化: {path}")
            digest.update(b"\0")
    return digest.hexdigest()


def _append_group(root, source_dir, target_name, names, moves, conflicts):
    target_dir = os.path.join(root, target_name)
    target_dir_identity = None
    if os.path.lexists(target_dir):
        if is_reparse_point(target_dir):
            conflicts.append(f"目标货架是联接/重解析点，拒绝跟随写入: {target_dir}")
        elif not os.path.isdir(target_dir):
            conflicts.append(f"目标货架已存在但不是目录: {target_dir}")
        else:
            target_dir_identity = directory_identity(target_dir)

    for name in sorted(set(names)):
        source = os.path.join(source_dir, name)
        target = os.path.join(target_dir, name)
        if not os.path.lexists(source):
            continue
        if is_reparse_point(source):
            conflicts.append(f"来源作品是联接/重解析点，需人工核实: {source}")
            continue
        if not os.path.isdir(source):
            conflicts.append(f"来源匹配分类规则但不是作品目录: {source}")
            continue
        if os.path.lexists(target):
            conflicts.append(f"目标同名冲突，拒绝整批移动: {target}")
            continue
        moves.append(
            {
                "source": source,
                "target": target,
                "target_dir": target_dir,
                "target_dir_identity": target_dir_identity,
                "identity": entry_identity(source),
                "tree_identity": tree_identity(source),
                "content_digest": tree_content_digest(source),
            }
        )


def build_move_plan(root):
    root = os.path.abspath(os.fspath(root))
    if not os.path.isdir(root):
        raise FileNotFoundError(f"成品库根目录不存在: {root}")
    if is_reparse_point(root):
        raise ValueError(f"成品库根目录不能是联接/重解析点: {root}")

    moves = []
    conflicts = []
    warnings = []

    # 1. 拆分「综合与其它城市」；保留原分类顺序，专属城市优先于江浙沪。
    source = os.path.join(root, "综合与其它城市")
    if os.path.lexists(source):
        if is_reparse_point(source) or not os.path.isdir(source):
            conflicts.append(f"来源货架不是可直接检查的普通目录: {source}")
        else:
            works = sorted(os.listdir(source))
            wuzhen = [name for name in works if "乌镇" in name]
            wuxi = [name for name in works if "无锡" in name]
            huzhou = [name for name in works if "湖州" in name]
            taizhou = [name for name in works if any(key in name for key in ("台州", "仙居", "临海"))]
            jinhua = [name for name in works if any(key in name for key in ("金华", "武义"))]
            yiwu = [name for name in works if "义乌" in name]
            dedicated = set(wuzhen + wuxi + huzhou + taizhou + jinhua + yiwu)
            jiangzhehu = [
                name
                for name in works
                if any(key in name for key in ("江浙沪", "华东", "长三角", "自驾", "徒步", "赏秋", "路线"))
                and name not in dedicated
            ]
            for target_name, names in (
                ("江浙沪成品", jiangzhehu),
                ("乌镇成品", wuzhen),
                ("无锡成品", wuxi),
                ("湖州成品", huzhou),
                ("台州成品", taizhou),
                ("金华成品", jinhua),
                ("义乌成品", yiwu),
            ):
                _append_group(root, source, target_name, names, moves, conflicts)
    else:
        warnings.append(f"来源货架不存在，跳过: {source}")

    # 2. 纯中秋与纯国庆作品分流，双节作品保留在原货架。
    source = os.path.join(root, "中秋国庆成品")
    if os.path.lexists(source):
        if is_reparse_point(source) or not os.path.isdir(source):
            conflicts.append(f"来源货架不是可直接检查的普通目录: {source}")
        else:
            works = sorted(os.listdir(source))
            mid_autumn = [name for name in works if "中秋" in name and "国庆" not in name and "十一" not in name]
            national_day = [name for name in works if ("国庆" in name or "十一" in name) and "中秋" not in name]
            _append_group(root, source, "中秋成品", mid_autumn, moves, conflicts)
            _append_group(root, source, "国庆成品", national_day, moves, conflicts)
    else:
        warnings.append(f"来源货架不存在，跳过: {source}")

    target_paths = [os.path.normcase(os.path.normpath(move["target"])) for move in moves]
    if len(target_paths) != len(set(target_paths)):
        conflicts.append("分类规则生成了重复目标路径，拒绝整批移动")

    return {"root": root, "moves": moves, "conflicts": conflicts, "warnings": warnings}


def _plan_signature(plan):
    return tuple(
        (
            move["source"],
            move["target"],
            move["identity"],
            move["tree_identity"],
            move["content_digest"],
            move["target_dir_identity"],
        )
        for move in plan["moves"]
    ), tuple(plan["conflicts"])


def print_plan(plan):
    print("=== 成品货架移动预演（默认只读）===")
    print(f"成品库根目录: {plan['root']}")
    print(f"拟移动作品目录: {len(plan['moves'])}")
    print(f"冲突数: {len(plan['conflicts'])}")
    for warning in plan["warnings"]:
        print(f"提示: {warning}")
    for move in plan["moves"]:
        print(f"拟移动: {move['source']} -> {move['target']} (SHA-256: {move['content_digest']})")
    for conflict in plan["conflicts"]:
        print(f"冲突: {conflict}")


def execute_move_plan(plan, confirmation=None):
    if confirmation != "APPLY":
        print("拒绝执行：移动函数需要显式 APPLY 确认；请从命令行主入口执行。")
        return 2
    if plan["conflicts"]:
        print("拒绝执行：计划包含冲突；请先人工核实并重新预演。")
        return 2
    if not plan["moves"]:
        print("没有符合当前分类规则的移动项。")
        return 0

    try:
        current = build_move_plan(plan["root"])
    except (OSError, ValueError) as error:
        print(f"拒绝执行：无法重建当前计划: {error}")
        return 2
    if _plan_signature(current) != _plan_signature(plan):
        print("拒绝执行：预演后来源、目标或目录身份发生变化；请重新检查计划。")
        print_plan(current)
        return 2
    if current["conflicts"]:
        print("拒绝执行：最新计划包含冲突。")
        print_plan(current)
        return 2

    root_device = path_device(plan["root"])
    for move in current["moves"]:
        if path_device(move["source"]) != root_device:
            print(f"拒绝跨卷移动，避免复制后删源: {move['source']}")
            return 2

    created_dirs = []
    created_dir_identities = {}
    target_dir_expectations = {}
    for move in current["moves"]:
        target_dir = move["target_dir"]
        expected = move["target_dir_identity"]
        if target_dir in target_dir_expectations and target_dir_expectations[target_dir] != expected:
            print(f"拒绝执行：同一货架的预演身份不一致: {target_dir}")
            return 2
        target_dir_expectations[target_dir] = expected
    moved = []
    try:
        for target_dir in sorted({move["target_dir"] for move in current["moves"]}):
            expected_identity = target_dir_expectations[target_dir]
            if os.path.lexists(target_dir):
                if is_reparse_point(target_dir) or not os.path.isdir(target_dir):
                    raise OSError(f"目标货架状态变化: {target_dir}")
                if expected_identity is None or directory_identity(target_dir) != expected_identity:
                    raise OSError(f"目标货架身份在预演后发生变化: {target_dir}")
                created_dir_identities[target_dir] = expected_identity
            else:
                if expected_identity is not None:
                    raise OSError(f"预演时存在的目标货架已消失: {target_dir}")
                os.mkdir(target_dir)
                created_dirs.append(target_dir)
                created_dir_identities[target_dir] = directory_identity(target_dir)

        for move in current["moves"]:
            if not os.path.lexists(move["source"]) or os.path.lexists(move["target"]):
                raise OSError(f"移动前来源/目标状态变化: {move['source']} -> {move['target']}")
            target_dir = move["target_dir"]
            if (
                not os.path.lexists(target_dir)
                or is_reparse_point(target_dir)
                or directory_identity(target_dir) != created_dir_identities[target_dir]
                or path_device(target_dir) != root_device
            ):
                raise OSError(f"移动前目标货架身份或卷号变化: {target_dir}")
            if (
                is_reparse_point(move["source"])
                or entry_identity(move["source"]) != move["identity"]
                or tree_identity(move["source"]) != move["tree_identity"]
            ):
                raise OSError(f"移动前来源目录身份变化: {move['source']}")
            actual_content_digest = tree_content_digest(move["source"])
            if (
                actual_content_digest != move["content_digest"]
                or tree_identity(move["source"]) != move["tree_identity"]
            ):
                raise OSError(f"预演后作品树或内容哈希发生变化: {move['source']}")
            if path_device(move["source"]) != root_device:
                raise OSError(f"拒绝跨卷移动: {move['source']}")
            try:
                os.rename(move["source"], move["target"])
            except OSError:
                if (
                    not os.path.lexists(move["source"])
                    and os.path.lexists(move["target"])
                    and os.stat(move["target"]).st_dev == move["identity"][0]
                    and os.stat(move["target"]).st_ino == move["identity"][1]
                ):
                    moved.append(move)
                raise
            moved.append(move)
            target_info = os.stat(move["target"])
            if (target_info.st_dev, target_info.st_ino) != move["identity"][:2]:
                raise OSError(f"移动后目录身份不符: {move['target']}")
            if tree_content_digest(move["target"]) != move["content_digest"]:
                raise OSError(f"移动后文件内容哈希不符: {move['target']}")
    except BaseException as error:
        rollback_errors = []
        cleanup_errors = []
        for move in reversed(moved):
            try:
                if (
                    os.path.lexists(move["target"])
                    and not os.path.lexists(move["source"])
                    and (os.stat(move["target"]).st_dev, os.stat(move["target"]).st_ino) == move["identity"][:2]
                ):
                    os.rename(move["target"], move["source"])
                else:
                    rollback_errors.append(f"无法安全回退: {move['target']} -> {move['source']}")
            except BaseException as rollback_error:
                rollback_errors.append(f"回退失败: {move['target']} -> {move['source']}: {rollback_error}")
        for target_dir in reversed(created_dirs):
            try:
                os.rmdir(target_dir)
            except BaseException as cleanup_error:
                cleanup_errors.append(f"新建空货架未能清理: {target_dir}: {cleanup_error}")
        print(f"移动中断，已尽力逆序回退: {error}")
        for rollback_error in rollback_errors:
            print(rollback_error)
        for cleanup_error in cleanup_errors:
            print(cleanup_error)
        if isinstance(error, KeyboardInterrupt):
            return 130
        if not isinstance(error, OSError):
            raise
        return 1

    print(f"移动完成: {len(moved)} 个作品目录。")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=PORTFOLIO_ROOT, help="成品库根目录，默认使用正式项目成品库")
    parser.add_argument("--apply", action="store_true", help="在预演后输入 APPLY 才执行移动")
    args = parser.parse_args(argv)

    try:
        plan = build_move_plan(args.root)
    except (OSError, ValueError) as error:
        print(f"错误: {error}")
        return 1
    print_plan(plan)
    if not args.apply:
        print("预演结束；未创建目录或移动作品。若要执行，需复核整份清单后显式传入 --apply。")
        return 0
    if plan["conflicts"]:
        print("拒绝执行：先解决全部目标冲突，再重新预演。")
        return 2
    try:
        confirmation = input("确认将按以上计划移动作品？请输入 APPLY：").strip()
    except EOFError:
        confirmation = ""
    if confirmation != "APPLY":
        print("已取消；未移动作品。")
        return 2
    return execute_move_plan(plan, confirmation=confirmation)


if __name__ == "__main__":
    sys.exit(main())
