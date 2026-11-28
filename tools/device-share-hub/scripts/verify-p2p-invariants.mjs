import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const read = relative => fs.readFileSync(path.join(root, relative), "utf8");

const android = read("android/app/src/main/java/com/zwm/gallery/AndroidP2pReceiver.java");
const androidPresence = read("android/app/src/main/java/com/zwm/gallery/RemoteRelayPresence.java");
const ios = read("ios/Album/P2PTransferEngine.swift");
const windows = read("windows-native/src/remote_relay.cpp");
const windowsP2P = read("windows-native/src/p2p_transport.cpp");
const windowsMain = read("windows-native/src/main.cpp");
const protocol = read("docs/REMOTE_PROTOCOL_V1.md");
const iosShutdown = ios.slice(ios.indexOf("private func shutdown"));

const requireText = (source, text, label) => {
  if (!source.includes(text)) throw new Error(`${label}: missing ${text}`);
};

// Both mobile responders must serialize the SDP/ICE handoff. This is the
// boundary where a candidate can otherwise be lost between callback threads.
requireText(android, "new ScheduledThreadPoolExecutor(1)", "Android serial SDP and channel worker");
requireText(android, "if (remoteReady) peer.addIceCandidate(ice)", "Android SDP/ICE handoff");
requireText(android, "for (IceCandidate candidate : pending)", "Android ICE queue drain");
requireText(androidPresence, "new AndroidP2pReceiver", "Android foreground service responder");
requireText(ios, "private let iceLock = NSLock()", "iOS ICE lock");
requireText(ios, "self.iceLock.lock()", "iOS SDP queue drain lock");
requireText(ios, "iceLock.lock()", "iOS candidate enqueue lock");

// A successful import must leave time for the ACK to cross SCTP before the
// responder closes the peer; otherwise Windows intentionally falls back to a
// duplicate relay transfer.
requireText(android, '"/" + id + "/close"', "Android signaling session cleanup");
requireText(android, "worker.schedule(this::close, ACK_FLUSH_DELAY_MS", "Android ACK flush delay");
const commit = android.indexOf("RemoteRelayTask.commitReceived(");
const ack = android.indexOf("sendAck(true)");
if (commit === -1 || ack <= commit) throw new Error("Android must commit before acknowledging");
requireText(ios, "ackFlushDelay", "iOS ACK flush delay");
requireText(iosShutdown, "transport.close()", "iOS signaling session cleanup");
requireText(windows, "closeSession", "Windows P2P session cleanup");
requireText(windowsMain, "TryP2PTransfer", "Windows P2P route");
requireText(windowsMain, "SendPlainTransfer", "Windows HTTPS fallback route");
requireText(windowsMain, "if (!sent)", "Windows fallback failure gate");
requireText(windowsMain, "wifi_upload_failed_fallback", "Windows Wi-Fi failure fallback");
requireText(windowsMain, "usb_upload_failed_fallback", "Windows USB failure fallback");
if (windowsMain.includes("为避免重复，不自动改用 Wi‑Fi")) {
  throw new Error("USB failures must continue through the automatic fallback chain");
}
requireText(windowsMain, "!attemptedRemote && !gCancelRequested && device.remoteAllowed && device.remoteConnected",
  "Windows direct Wi-Fi to remote fallback gate");
requireText(windowsP2P, "channel.reset()", "Windows P2P channel cleanup");
requireText(windowsP2P, "peer.reset()", "Windows P2P peer cleanup");
requireText(windowsP2P, "std::memory_order_release", "Windows P2P closing callback guard");
requireText(protocol, "中继对象下载 → 本地大小/SHA-256 校验", "relay download/hash order");
requireText(protocol, "作品库导入后才 ACK", "relay library-before-ACK order");

console.log("P2P ICE, ACK flush, session cleanup and relay-order invariants passed.");
