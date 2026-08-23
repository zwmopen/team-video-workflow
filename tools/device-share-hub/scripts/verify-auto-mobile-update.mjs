import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const sourcePath = path.resolve(here, "../windows-native/src/main.cpp");
const relaySourcePath = path.resolve(here, "../windows-native/src/remote_relay.cpp");
const storePath = path.resolve(here, "../windows-native/src/content_store.cpp");
const source = fs.readFileSync(sourcePath, "utf8");
const relaySource = fs.readFileSync(relaySourcePath, "utf8");
const storeSource = fs.readFileSync(storePath, "utf8");
const uploadStart = source.indexOf("bool UploadToDevice(");
const uploadEnd = source.indexOf("// Shell SendTo is a background entry point", uploadStart);
if (uploadStart < 0 || uploadEnd < 0) throw new Error("UploadToDevice function boundary missing");
const upload = source.slice(uploadStart, uploadEnd);
if (!upload.includes("return transferSucceeded;")) {
  throw new Error("UploadToDevice must return an explicit success result");
}

const updateStart = source.indexOf("void MaybeAutoMobileUpdate()");
const updateEnd = source.indexOf("void RefreshDeviceList()", updateStart);
if (updateStart < 0 || updateEnd < 0) throw new Error("MaybeAutoMobileUpdate function boundary missing");
const update = source.slice(updateStart, updateEnd);
const successBranch = update.indexOf("if (succeeded)");
const deliveredWrite = update.indexOf("MobileUpdateDeliveredSettingKey(selected.id)");
if (successBranch < 0 || deliveredWrite < 0 || successBranch > deliveredWrite) {
  throw new Error("durable mobile-update delivery state must be written only after success");
}
if (!update.includes("auto_mobile_update_failed_retryable")) {
  throw new Error("failed mobile updates must emit a retryable diagnostic");
}
if (update.includes("MobileUpdateSentSettingKey")) {
  throw new Error("legacy pre-transfer sent marker must not suppress retries");
}

const beaconStart = source.indexOf('if (parts.size() >= 15)');
if (beaconStart < 0 || !source.slice(beaconStart, beaconStart + 700).includes("conversionCount")) {
  throw new Error("Windows discovery must parse optional precise inventory beacon fields");
}

const relayStart = source.indexOf("void RelayLoop()");
const relayEnd = source.indexOf("bool RelayIncomingToNext", relayStart);
if (relayStart < 0 || relayEnd < 0) throw new Error("RelayLoop function boundary missing");
const relayLoop = source.slice(relayStart, relayEnd);
if (!relayLoop.includes("if (relay.conversionCount >= 0)")
    || !relayLoop.includes("if (!relay.appVersion.empty())")) {
  throw new Error("remote inventory must not erase a more complete local inventory when fields are missing");
}

if (!source.includes("std::chrono::steady_clock::time_point wifiLastSeen")
    || !source.includes("bool IsLiveWifiDevice(const Device& device")
    || !source.includes("device.wifiLastSeen = device.lastSeen")
    || (source.match(/MergeLocalDeviceLocked\(std::move\(device\)\)/g) || []).length < 2) {
  throw new Error("LAN discovery must preserve a separate Wi-Fi timestamp and merge with relay state");
}
if (!upload.includes("const bool liveWifi = IsLiveWifiDevice(device")
    || !upload.includes("if (!liveWifi && device.remoteAllowed && device.remoteConnected)")
    || !upload.includes("if (!liveWifi)")) {
  throw new Error("stale Wi-Fi addresses must not block P2P/HTTPS remote fallback");
}
if (!upload.includes("usb_upload_failed_fallback")
    || upload.includes("为避免重复，不自动改用 Wi‑Fi")) {
  throw new Error("USB failures must be cleaned up and continue through Wi-Fi/P2P/relay fallback");
}
if (!source.includes("DeviceShareHub/4.3.29") || source.includes("DeviceShareHub/4.3.28")) {
  throw new Error("Windows network User-Agent must match the current desktop version");
}
if (!relaySource.includes("DeviceShareHub/4.3.29")
    || relaySource.includes("DeviceShareHub/4.3.28")
    || !relaySource.includes("relay-proxy.txt")
    || !relaySource.includes("WINHTTP_ACCESS_TYPE_NAMED_PROXY")) {
  throw new Error("Cloudflare relay must use the current User-Agent and optional HTTPS proxy configuration");
}

if (!storeSource.includes("INSERT OR IGNORE INTO settings")
    || !storeSource.includes("('auto_restock_enabled', '1')")
    || !storeSource.includes("('auto_restock_threshold', '5')")) {
  throw new Error("fresh content databases must default to enabled precise restock at threshold 5");
}

const restockStart = source.indexOf("std::optional<std::filesystem::path> PickAutoRestockSource()");
const restockEnd = source.indexOf("int AutoRestockThreshold()", restockStart);
if (restockStart < 0 || restockEnd < 0) throw new Error("PickAutoRestockSource function boundary missing");
const restock = source.slice(restockStart, restockEnd);
if (!restock.includes("recursive_directory_iterator")
    || !restock.includes("name.find(L\"[转]\")")
    || !restock.includes("name.find(L\"【转】\")")) {
  throw new Error("automatic precise restock must recursively find tagged [转]/【转】 work folders");
}

console.log("Automatic mobile update retry and inventory beacon checks passed.");
