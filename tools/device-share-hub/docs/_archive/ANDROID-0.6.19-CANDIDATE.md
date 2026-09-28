# Android 0.6.19 candidate

## Why

The PC keeps its 10-second status poll and the Android receiver keeps its 2.5-second beacon fallback. Before this candidate, refreshing the phone library only updated local inventory preferences; it did not request an immediate status beacon, so an automatic replenishment decision could wait for the next poll.

## Change

`OnlineService.publishWorkInventory()` now requests a best-effort immediate status beacon. The discovery thread owns the UDP socket and consumes a volatile request flag, so no socket is shared across threads. If Android rejects a background service start, a diagnostic event is recorded and the normal beacon plus PC polling remain authoritative.

## Safety boundary

This does not change approval, category-inventory-unknown protection, threshold, retry, or de-duplication rules. A legacy client that reports only `workCount` can still be discovered, but precise/traffic inventory remains unknown and is intentionally not used for automatic replenishment.

## Candidate

- Android version: `0.6.19`
- versionCode: `57`
- Status: source candidate only; not a public release.
- Before release: run Android unit tests, Release/Lint, install on a real device, and verify `/v2/info.workCounts` plus the refresh-to-beacon path.
