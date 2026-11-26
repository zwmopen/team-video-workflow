import contextlib
import hashlib
import io
import os
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

from split_24_shelves import (
    PORTFOLIO_ROOT,
    build_move_plan,
    execute_move_plan,
    is_reparse_point,
    main,
)


class TestSplit24ShelvesSafety(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory(prefix="test_split_24_shelves_")
        self.root = self.temp_dir.name
        self.general = os.path.join(self.root, "综合与其它城市")
        self.festival = os.path.join(self.root, "中秋国庆成品")
        os.makedirs(self.general)
        os.makedirs(self.festival)

    def tearDown(self):
        self.temp_dir.cleanup()

    def _work(self, shelf, name, contents=b"sample-work"):
        path = os.path.join(shelf, name)
        os.makedirs(path)
        with open(os.path.join(path, "文案.txt"), "wb") as handle:
            handle.write(contents)
        return path

    def _sha256(self, path):
        with open(os.path.join(path, "文案.txt"), "rb") as handle:
            return hashlib.sha256(handle.read()).hexdigest()

    def test_default_root_is_the_current_project_finished_shelf(self):
        expected = os.path.normpath(r"D:\江湖有旅人\03-生产车间\05-成品库")
        self.assertEqual(os.path.normcase(PORTFOLIO_ROOT), os.path.normcase(expected))

    def test_default_run_is_read_only_and_keeps_original_classification(self):
        wuzhen = self._work(self.general, "乌镇江浙沪路线")
        jiangzhe = self._work(self.general, "杭州自驾团建")
        mid_autumn = self._work(self.festival, "纯中秋活动")
        national_day = self._work(self.festival, "国庆十一活动")
        mixed = self._work(self.festival, "中秋国庆双节活动")
        before = {path: self._sha256(path) for path in (wuzhen, jiangzhe, mid_autumn, national_day, mixed)}

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = main(["--root", self.root])

        self.assertEqual(result, 0)
        self.assertIn("乌镇成品", output.getvalue())
        self.assertIn("江浙沪成品", output.getvalue())
        self.assertIn("预演结束", output.getvalue())
        self.assertTrue(os.path.isdir(wuzhen))
        self.assertTrue(os.path.isdir(jiangzhe))
        self.assertFalse(os.path.exists(os.path.join(self.root, "乌镇成品")))
        self.assertFalse(os.path.exists(os.path.join(self.root, "江浙沪成品")))
        plan = build_move_plan(self.root)
        destinations = {os.path.basename(move["source"]): os.path.basename(move["target_dir"]) for move in plan["moves"]}
        self.assertEqual(destinations["乌镇江浙沪路线"], "乌镇成品")
        self.assertEqual(destinations["杭州自驾团建"], "江浙沪成品")
        self.assertEqual(destinations["纯中秋活动"], "中秋成品")
        self.assertEqual(destinations["国庆十一活动"], "国庆成品")
        self.assertNotIn("中秋国庆双节活动", destinations)
        self.assertEqual({path: self._sha256(path) for path in before}, before)

    def test_new_collision_blocks_entire_plan_before_any_move(self):
        first = self._work(self.general, "乌镇活动")
        second = self._work(self.general, "无锡活动")
        plan = build_move_plan(self.root)
        collision = os.path.join(self.root, "乌镇成品", "乌镇活动")
        os.makedirs(collision)

        result = execute_move_plan(plan, confirmation="APPLY")

        self.assertEqual(result, 2)
        self.assertTrue(os.path.isdir(first))
        self.assertTrue(os.path.isdir(second))
        self.assertFalse(os.path.exists(os.path.join(self.root, "无锡成品")))

    def test_existing_collision_blocks_apply_before_confirmation(self):
        source = self._work(self.general, "乌镇活动")
        collision = os.path.join(self.root, "乌镇成品", "乌镇活动")
        os.makedirs(collision)
        output = io.StringIO()

        with contextlib.redirect_stdout(output):
            result = main(["--root", self.root, "--apply"])

        self.assertEqual(result, 2)
        self.assertIn("冲突", output.getvalue())
        self.assertTrue(os.path.isdir(source))

    def test_unexpected_confirmation_text_cancels_without_moving(self):
        source = self._work(self.general, "乌镇活动")
        output = io.StringIO()

        with mock.patch("builtins.input", return_value="yes"), contextlib.redirect_stdout(output):
            result = main(["--root", self.root, "--apply"])

        self.assertEqual(result, 2)
        self.assertIn("已取消", output.getvalue())
        self.assertTrue(os.path.isdir(source))

    def test_direct_apply_api_requires_explicit_confirmation(self):
        source = self._work(self.general, "乌镇活动")
        plan = build_move_plan(self.root)

        result = execute_move_plan(plan)

        self.assertEqual(result, 2)
        self.assertTrue(os.path.isdir(source))

    def test_reparse_check_fails_closed_when_attributes_cannot_be_read(self):
        with mock.patch("split_24_shelves.os.lstat", side_effect=PermissionError("denied")):
            with self.assertRaises(OSError):
                is_reparse_point(self.root)

    def test_nested_reparse_point_blocks_the_entire_plan(self):
        source = self._work(self.general, "乌镇活动")
        real_check = is_reparse_point

        def mark_source_as_reparse(path):
            if os.path.normcase(os.path.abspath(path)) == os.path.normcase(os.path.abspath(source)):
                return True
            return real_check(path)

        with mock.patch("split_24_shelves.is_reparse_point", side_effect=mark_source_as_reparse):
            plan = build_move_plan(self.root)

        self.assertEqual(plan["moves"], [])
        self.assertTrue(any("联接/重解析点" in item for item in plan["conflicts"]))

    def test_file_change_after_preview_invalidates_plan(self):
        source = self._work(self.general, "乌镇活动", b"stable")
        plan = build_move_plan(self.root)
        with open(os.path.join(source, "新增文件.txt"), "wb") as handle:
            handle.write(b"changed tree")

        result = execute_move_plan(plan, confirmation="APPLY")

        self.assertEqual(result, 2)
        self.assertTrue(os.path.isdir(source))
        self.assertFalse(os.path.exists(os.path.join(self.root, "乌镇成品", "乌镇活动")))

    def test_same_size_content_change_with_restored_mtime_invalidates_plan(self):
        source = self._work(self.general, "乌镇活动", b"AAAA")
        plan = build_move_plan(self.root)
        file_path = os.path.join(source, "文案.txt")
        before = os.stat(file_path)
        with open(file_path, "wb") as handle:
            handle.write(b"BBBB")
        os.utime(file_path, ns=(before.st_atime_ns, before.st_mtime_ns))

        result = execute_move_plan(plan, confirmation="APPLY")

        self.assertEqual(result, 2)
        self.assertTrue(os.path.isdir(source))
        self.assertFalse(os.path.exists(os.path.join(self.root, "乌镇成品", "乌镇活动")))

    def test_post_move_content_hash_mismatch_rolls_back(self):
        source = self._work(self.general, "乌镇活动", b"checked-content")
        plan = build_move_plan(self.root)
        real_digest = __import__("split_24_shelves").tree_content_digest
        calls = 0

        def change_digest_after_move(path):
            nonlocal calls
            calls += 1
            return real_digest(path) if calls <= 2 else "after"

        with mock.patch("split_24_shelves.tree_content_digest", side_effect=change_digest_after_move):
            result = execute_move_plan(plan, confirmation="APPLY")

        self.assertEqual(result, 1)
        self.assertTrue(os.path.isdir(source))
        self.assertFalse(os.path.exists(os.path.join(self.root, "乌镇成品", "乌镇活动")))

    def test_cross_volume_move_is_refused_before_any_move(self):
        source = self._work(self.general, "乌镇活动")
        plan = build_move_plan(self.root)
        real_path_device = __import__("split_24_shelves").path_device

        def report_other_volume(path):
            device = real_path_device(path)
            if os.path.normcase(os.path.abspath(path)) == os.path.normcase(os.path.abspath(source)):
                return device + 1
            return device

        with mock.patch("split_24_shelves.path_device", side_effect=report_other_volume):
            result = execute_move_plan(plan, confirmation="APPLY")

        self.assertEqual(result, 2)
        self.assertTrue(os.path.isdir(source))
        self.assertFalse(os.path.exists(os.path.join(self.root, "乌镇成品")))

    def test_target_directory_identity_is_rechecked_before_rename(self):
        source = self._work(self.general, "乌镇活动")
        plan = build_move_plan(self.root)
        real_identity = __import__("split_24_shelves").directory_identity
        calls = 0

        def change_target_identity(path):
            nonlocal calls
            calls += 1
            identity = real_identity(path)
            if calls == 2:
                return (identity[0], identity[1] + 1, *identity[2:])
            return identity

        with mock.patch("split_24_shelves.directory_identity", side_effect=change_target_identity):
            result = execute_move_plan(plan, confirmation="APPLY")

        self.assertEqual(result, 1)
        self.assertTrue(os.path.isdir(source))
        self.assertFalse(os.path.exists(os.path.join(self.root, "乌镇成品")))

    def test_apply_moves_inside_temp_root_and_preserves_file_hash(self):
        source = self._work(self.general, "乌镇样本", b"checked-content")
        digest = self._sha256(source)
        plan = build_move_plan(self.root)

        result = execute_move_plan(plan, confirmation="APPLY")

        target = os.path.join(self.root, "乌镇成品", "乌镇样本")
        self.assertEqual(result, 0)
        self.assertFalse(os.path.exists(source))
        self.assertTrue(os.path.isdir(target))
        self.assertEqual(self._sha256(target), digest)

    def test_failed_move_rolls_back_prior_moves_and_empty_dirs(self):
        first = self._work(self.general, "乌镇样本")
        second = self._work(self.general, "无锡样本")
        plan = build_move_plan(self.root)
        real_rename = os.rename
        call_count = 0

        def fail_second_move(source, target):
            nonlocal call_count
            call_count += 1
            if call_count == 2:
                raise OSError("injected test failure")
            return real_rename(source, target)

        with mock.patch("split_24_shelves.os.rename", side_effect=fail_second_move):
            result = execute_move_plan(plan, confirmation="APPLY")

        self.assertEqual(result, 1)
        self.assertTrue(os.path.isdir(first))
        self.assertTrue(os.path.isdir(second))
        self.assertFalse(os.path.exists(os.path.join(self.root, "乌镇成品")))
        self.assertFalse(os.path.exists(os.path.join(self.root, "无锡成品")))

    def test_keyboard_interrupt_rolls_back_prior_moves(self):
        first = self._work(self.general, "乌镇样本")
        second = self._work(self.general, "无锡样本")
        plan = build_move_plan(self.root)
        real_rename = os.rename
        call_count = 0

        def interrupt_second_move(source, target):
            nonlocal call_count
            call_count += 1
            if call_count == 2:
                raise KeyboardInterrupt()
            return real_rename(source, target)

        output = io.StringIO()
        with mock.patch("split_24_shelves.os.rename", side_effect=interrupt_second_move):
            with contextlib.redirect_stdout(output):
                result = execute_move_plan(plan, confirmation="APPLY")

        self.assertEqual(result, 130)
        self.assertIn("移动中断，已尽力逆序回退", output.getvalue())
        self.assertTrue(os.path.isdir(first))
        self.assertTrue(os.path.isdir(second))
        self.assertFalse(os.path.exists(os.path.join(self.root, "乌镇成品")))
        self.assertFalse(os.path.exists(os.path.join(self.root, "无锡成品")))

    def test_failed_empty_directory_cleanup_is_reported(self):
        self._work(self.general, "乌镇样本")
        self._work(self.general, "无锡样本")
        plan = build_move_plan(self.root)
        real_rename = os.rename
        call_count = 0

        def fail_second_move(source, target):
            nonlocal call_count
            call_count += 1
            if call_count == 2:
                raise OSError("injected test failure")
            return real_rename(source, target)

        output = io.StringIO()
        with mock.patch("split_24_shelves.os.rename", side_effect=fail_second_move):
            with mock.patch("split_24_shelves.os.rmdir", side_effect=PermissionError("injected cleanup failure")):
                with contextlib.redirect_stdout(output):
                    result = execute_move_plan(plan, confirmation="APPLY")

        self.assertEqual(result, 1)
        self.assertIn("新建空货架未能清理", output.getvalue())
        self.assertTrue(os.path.isdir(os.path.join(self.root, "乌镇成品")))


if __name__ == "__main__":
    unittest.main()
