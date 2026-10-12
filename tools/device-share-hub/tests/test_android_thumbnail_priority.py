# -*- coding: utf-8 -*-
"""Guard Android online thumbnails against stale-category queue starvation."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "zwm" / "gallery" / "MainActivity.java"
CLIENT = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "zwm" / "gallery" / "OnlineGalleryClient.java"


def method_body(source: str, signature: str) -> str:
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
    raise AssertionError(f"Unclosed method body: {signature}")


class AndroidThumbnailPriorityTests(unittest.TestCase):
    def test_list_rerender_discards_stale_pending_client_and_ui_tasks(self):
        main = MAIN.read_text(encoding="utf-8")
        reset = method_body(main, "private void resetOnlineThumbScheduler()")

        self.assertIn("onlineThumbPending.clear()", reset)
        self.assertIn("onlineClient.cancelPendingThumbnails()", reset)

    def test_current_list_is_submitted_top_to_bottom_in_small_batches(self):
        main = MAIN.read_text(encoding="utf-8")
        self.assertIn("ONLINE_THUMB_BURST_BUDGET = 8", main)
        self.assertIn("ONLINE_THUMB_DRAIN_STEP = 8", main)
        self.assertIn("ONLINE_THUMB_DRAIN_INTERVAL_MS = 100L", main)

        schedule = method_body(main, "private void scheduleOnlineThumb(Runnable task)")
        drain = method_body(main, "private void drainOnlineThumbs()")
        self.assertIn("onlineThumbPending.addLast(task)", schedule)
        self.assertIn("onlineThumbPending.pollFirst()", drain)

    def test_client_can_remove_queued_thumbnail_tasks_without_dropping_running_work(self):
        client = CLIENT.read_text(encoding="utf-8")
        cancel = method_body(client, "public int cancelPendingThumbnails()")

        self.assertIn("new ArrayList<>(thumbExecutor.getQueue())", cancel)
        self.assertIn("instanceof ThumbnailRequestTask", cancel)
        self.assertIn("thumbExecutor.getQueue().remove(pending)", cancel)
        self.assertIn("inflightThumbs.remove(task.key)", cancel)
        self.assertNotIn("shutdownNow", cancel)

    def test_deduplicated_request_task_is_registered_before_it_can_be_discarded(self):
        client = CLIENT.read_text(encoding="utf-8")
        load = method_body(client, "public void loadThumbnail(final String workId, final String fileName, final Callback<Bitmap> callback)")

        self.assertLess(load.index("inflightThumbs.put(key, list)"), load.index("thumbExecutor.execute("))
        self.assertIn("new ThumbnailRequestTask(key, workId, fileName)", load)


if __name__ == "__main__":
    unittest.main()
