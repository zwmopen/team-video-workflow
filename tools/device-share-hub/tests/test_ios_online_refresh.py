"""Static guards for iOS online refresh freshness and snapshot behavior."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTENT = (ROOT / "ios" / "Album" / "ContentView.swift").read_text(encoding="utf-8-sig")
CLIENT = (ROOT / "ios" / "Album" / "OnlineGalleryClient.swift").read_text(encoding="utf-8-sig")


def check(name: str, condition: bool) -> None:
    if not condition:
        raise AssertionError(name)
    print(f"PASS {name}")


def section(source: str, start_marker: str, end_marker: str) -> str:
    start = source.index(start_marker)
    end = source.index(end_marker, start)
    return source[start:end]


def main() -> None:
    toggle = section(CONTENT, "@objc private func toggleMode()", "@objc private func refreshCurrent()")
    loader = section(CONTENT, "private func loadOnlineData(silent: Bool = false)", "private func tryAutoDiscoverPc(")
    categories = section(CLIENT, "public func fetchCategories(", "public func fetchWorks(")
    works = section(CLIENT, "public func fetchWorks(", "public func loadImage(path:")

    check("switching back to online primes the saved list before fetching", toggle.index("primeOnlineDataFromSnapshot()") < toggle.index("loadOnlineData()"))
    check("online list is rendered immediately when switching modes", "renderOnlineUI()" in toggle)
    check("leaving online invalidates any pending refresh", "onlineLoadGeneration += 1" in toggle)
    check("refreshes carry a generation and reject stale UI callbacks", "let requestGeneration = onlineLoadGeneration" in loader and loader.count("self.onlineLoadGeneration == requestGeneration") >= 2)
    check("works render independently from slow categories", "DispatchGroup" not in loader and loader.index("fetchCategories") < loader.index("fetchWorks") and "self.onlineCategories = categories.categories" in loader)
    check("category snapshots are saved only by the newest category request", "beginCategoriesRequest()" in categories and "saveCategoriesSnapshotIfLatest(data, requestID: requestID)" in categories)
    check("works snapshots and remote view state reject stale requests", "beginWorksRequest()" in works and "saveWorksSnapshotIfLatest(data, requestID: requestID)" in works and "isLatestWorksRequest(requestID)" in works)


if __name__ == "__main__":
    main()
