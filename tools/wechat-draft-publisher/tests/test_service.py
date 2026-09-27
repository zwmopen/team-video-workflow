from pathlib import Path
import tempfile
import unittest

from wechat_draft_publisher.service import publish_folder
from wechat_draft_publisher.store import DraftStore


class ServiceTests(unittest.TestCase):
    def test_dry_run_creates_history_without_network(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            folder = root / "帖子"
            folder.mkdir()
            (folder / "文案.txt").write_text("测试标题\n测试正文", encoding="utf-8")
            (folder / "01.jpg").write_bytes(b"fake-image")
            settings = {
                "accounts": {
                    "main": {
                        "name": "测试号",
                        "app_id": "dry-run-app",
                        "app_secret": "dry-run-secret",
                    }
                }
            }
            store = DraftStore(root / "history.db")
            result = publish_folder(
                folder=folder,
                settings=settings,
                store=store,
                account_id="main",
                dry_run=True,
            )
            self.assertTrue(result["ok"])
            self.assertEqual(result["draft_media_id"], "dry-run-draft-media-id")
            self.assertEqual(store.history()[0]["status"], "DRAFTED_WITH_WARNING")


if __name__ == "__main__":
    unittest.main()
