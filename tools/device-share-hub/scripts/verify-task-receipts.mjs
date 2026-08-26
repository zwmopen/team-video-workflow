import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const read = relative => fs.readFileSync(path.join(root, relative), "utf8");

const android = read("android/app/src/main/java/com/zwm/gallery/OnlineService.java");
const ios = read("ios/Album/IncomingTransferService.swift");
const androidResetStart = android.indexOf("private void resetFailedCommitTask");
const androidResetEnd = android.indexOf("    private void handleHttp", androidResetStart);
if (androidResetStart < 0 || androidResetEnd < 0) {
  throw new Error("Android commit recovery function boundary missing");
}
const androidReset = android.slice(androidResetStart, androidResetEnd);

const requireText = (source, text, label) => {
  if (!source.includes(text)) throw new Error(`${label}: missing ${text}`);
};

// A transient commit failure must retain the same task and its files. The
// sender's stable task ID is the idempotency key for the next commit retry.
requireText(android, "PREF_COMMITTED_TASKS", "Android committed-task store");
requireText(androidReset, "commit_task_retained", "Android retained-task diagnostic");
requireText(androidReset, "persistTaskManifest(failed)", "Android retry manifest");
if (androidReset.includes("deleteRecursively(failed.dir)") || androidReset.includes("activeTask = null")) {
  throw new Error("Android failed commit must not delete or clear the retryable task");
}
requireText(android, "private synchronized void commitTask", "Android serialized commit");
requireText(android, "task_commit_replay", "Android replay receipt");
requireText(android, ".put(\"committed\", true)", "Android committed receipt");
requireText(android, "isFailedCommitTaskStale", "Android failed-commit expiry");

// After import, both mobile receivers must answer a repeated commit/status
// request with the durable receipt instead of claiming the task disappeared.
requireText(ios, "committedTaskReceiptsKey", "iOS committed-task store");
requireText(ios, "request.method == \"GET\", pieces.count == 3", "iOS task status route");
requireText(ios, "private func taskStatus(taskID: String)", "iOS task status handler");
requireText(ios, "private func saveCommittedTaskReceipt", "iOS replay receipt writer");
requireText(ios, "if let receipt = committedTaskReceipt(taskID)", "iOS replay receipt");
requireText(ios, "result[\"state\"] = \"committed\"", "iOS committed receipt");
requireText(ios, "size: size", "iOS persisted file size");
requireText(ios, '"receivedBytes": file.size', "iOS replayable received bytes");

console.log("Mobile task retry, status and committed-receipt invariants passed.");
