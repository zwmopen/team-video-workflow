# -*- coding: utf-8 -*-
"""Guard against duplicate online list requests during Android cold startup."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "zwm" / "gallery" / "MainActivity.java"
ONLINE_CLIENT = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "zwm" / "gallery" / "OnlineGalleryClient.java"
SETTINGS_ACTIVITY = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "zwm" / "gallery" / "SettingsActivity.java"


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


class StartupPrefetchTests(unittest.TestCase):
    def test_cold_start_uses_cached_snapshot_without_background_network_prefetch(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        prime = method_body(source, "private void primeOnlineWorksInBackground()")
        start = method_body(source, "protected void onStart()")
        resume = method_body(source, "protected void onResume()")

        self.assertNotIn("silentPrefetchOnlineWorks", source)
        self.assertNotIn("onlineClient.fetchWorks", prime)
        self.assertNotIn("onlineClient.fetchCategories", prime)
        self.assertIn("refreshOnlineWorks(false);", start)
        self.assertNotIn("refreshOnlineWorks(", resume)
        self.assertNotIn("refreshWorks();", resume)

    def test_online_categories_start_only_after_work_list_succeeds(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        refresh = method_body(source, "private void refreshOnlineWorks(boolean userInitiated)")
        categories = method_body(source, "private void refreshOnlineCategoriesAfterWorks()")

        self.assertLess(refresh.index("onlineClient.fetchWorks("), refresh.index("refreshOnlineCategoriesAfterWorks();"))
        self.assertIn("onlineClient.fetchCategories(", categories)

    def test_online_list_logs_network_status_bytes_and_elapsed_time(self):
        source = ONLINE_CLIENT.read_text(encoding="utf-8")
        request = method_body(
            source,
            "private static String rawHttpGet(URL url, int connectTimeoutMs, int readTimeoutMs)",
        )

        self.assertIn("responseBytes=", request)
        self.assertIn("elapsedMs=", request)
        self.assertIn("status=", request)
        self.assertIn("finally", request)
        self.assertIn("conn.disconnect();", request)

    def test_online_list_logs_executor_queue_delay_without_request_url(self):
        source = ONLINE_CLIENT.read_text(encoding="utf-8")
        works = method_body(
            source,
            "public void fetchWorks(String category, String query, String sortKey, Callback<List<OnlineWorkEntry>> callback)",
        )

        self.assertIn("queueMs=", works)
        self.assertIn("elapsedMs=", works)
        self.assertIn("fetchWorks failed", works)
        self.assertNotIn("url.toString()", works)

    def test_no_snapshot_error_card_does_not_render_lan_server_address(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        error = method_body(source, "private void handleOnlineError(String prefix, Exception error)")

        self.assertNotIn("resolveBaseUrl()", error)
        self.assertNotIn("当前地址：", error)

    def test_manual_update_check_shows_progress_and_reenables_button(self):
        source = SETTINGS_ACTIVITY.read_text(encoding="utf-8")
        update_click = source[source.index('Button update = button("检查更新"'):]
        update_click = update_click[:update_click.index("Button about =")]

        self.assertIn("setEnabled(false)", update_click)
        self.assertIn('update.setText("正在检查…")', update_click)
        self.assertIn("update.setText(updateLabel)", update_click)
        self.assertIn("update.setEnabled(true)", update_click)


if __name__ == "__main__":
    unittest.main()
