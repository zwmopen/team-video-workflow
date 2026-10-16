from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

TOOL_ROOT = Path(__file__).resolve().parents[1] / "tools" / "wechat-draft-publisher"
if TOOL_ROOT.exists():
    sys.path.insert(0, str(TOOL_ROOT))
else:
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from wechat_draft_publisher.core import parse_post_folder, scan_library, visible_length
from wechat_draft_publisher.service import publish_folder
from wechat_draft_publisher.store import DraftStore
from wechat_draft_publisher.wechat_api import WechatNewspicClient


class WechatDraftPublisherContractTests(unittest.TestCase):
    def test_library_supports_direct_posts_and_collections(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            direct = root / "单独帖子"
            direct.mkdir()
            (direct / "文案.txt").write_text("杭州团建攻略\n正文", encoding="utf-8")
            (direct / "01.jpg").write_bytes(b"x")
            nested = root / "作品集" / "帖子A"
            nested.mkdir(parents=True)
            (nested / "copywriting.txt").write_text("桐庐团建\n正文", encoding="utf-8")
            (nested / "1.png").write_bytes(b"x")
            tree = scan_library(root)
            self.assertEqual({child.name for child in tree.children}, {"单独帖子", "作品集"})

    def test_long_body_warns_without_blocking(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            title = "杭州公司团建两天一夜超详细保姆级完整攻略快码住直接抄作业"
            (folder / "文案.txt").write_text(title + "\n" + "文" * 1001, encoding="utf-8")
            (folder / "01.jpg").write_bytes(b"x")
            post = parse_post_folder(folder)
            self.assertTrue(post.valid)
            self.assertLessEqual(visible_length(post.suggested_title), 20)
            self.assertTrue(any("软限制" in warning for warning in post.warnings))

    def test_official_newspic_payload_shape(self) -> None:
        payload = WechatNewspicClient.build_draft_payload(
            title="标题", content="正文", image_media_ids=["a", "b"]
        )
        article = payload["articles"][0]
        self.assertEqual(article["article_type"], "newspic")
        self.assertEqual(article["image_info"]["image_list"][0]["image_media_id"], "a")

    def test_dry_run_records_a_draft_without_network(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            folder = root / "帖子"
            folder.mkdir()
            (folder / "文案.txt").write_text("测试标题\n测试正文", encoding="utf-8")
            (folder / "01.jpg").write_bytes(b"x")
            store = DraftStore(root / "history.db")
            result = publish_folder(
                folder=folder,
                settings={"accounts": {}},
                store=store,
                account_id="dry-run",
                dry_run=True,
            )
            self.assertEqual(result["draft_media_id"], "dry-run-draft-media-id")
            self.assertEqual(len(store.history()), 1)


if __name__ == "__main__":
    unittest.main()
