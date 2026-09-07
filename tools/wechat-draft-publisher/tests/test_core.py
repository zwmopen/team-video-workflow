from pathlib import Path
import tempfile
import unittest

from wechat_draft_publisher.core import (
    parse_post_folder,
    scan_library,
    visible_length,
)


class CoreTests(unittest.TestCase):
    def test_single_post_and_collection_are_discovered(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            direct = root / "单独帖子"
            direct.mkdir()
            (direct / "文案.txt").write_text("杭州团建攻略\n正文第一段", encoding="utf-8")
            (direct / "01.jpg").write_bytes(b"x")

            nested = root / "作品集" / "帖子A"
            nested.mkdir(parents=True)
            (nested / "copywriting.txt").write_text("桐庐团建\n正文", encoding="utf-8")
            (nested / "1.png").write_bytes(b"x")

            tree = scan_library(root)
            self.assertEqual(tree.kind, "root")
            names = {child.name for child in tree.children}
            self.assertEqual(names, {"单独帖子", "作品集"})
            collection = next(child for child in tree.children if child.name == "作品集")
            self.assertEqual(collection.children[0].kind, "post")

    def test_txt_first_non_empty_line_is_title_and_images_natural_sort(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / "文案.txt").write_text("\n\n标题一\n正文一\n\n正文二", encoding="utf-8")
            for name in ("10.jpg", "2.jpg", "1.jpg"):
                (folder / name).write_bytes(b"x")
            post = parse_post_folder(folder)
            self.assertEqual(post.original_title, "标题一")
            self.assertEqual(post.body, "正文一\n\n正文二")
            self.assertEqual(post.image_names, ["1.jpg", "2.jpg", "10.jpg"])

    def test_long_title_gets_suggestion_and_body_only_warns(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            title = "杭州公司团建两天一夜超详细保姆级完整攻略快码住直接抄作业"
            body = "文" * 1001
            (folder / "文案.txt").write_text(title + "\n" + body, encoding="utf-8")
            (folder / "01.jpg").write_bytes(b"x")
            post = parse_post_folder(folder)
            self.assertTrue(post.valid)
            self.assertGreater(post.title_length, 24)
            self.assertLessEqual(visible_length(post.suggested_title), 20)
            self.assertTrue(any("超过 1000 字软限制" in item for item in post.warnings))


if __name__ == "__main__":
    unittest.main()
