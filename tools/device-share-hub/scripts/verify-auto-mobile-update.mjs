import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const sourcePath = path.resolve(here, "../windows-native/src/main.cpp");
const storePath = path.resolve(here, "../windows-native/src/content_store.cpp");
const source = fs.readFileSync(sourcePath, "utf8");
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

if (!storeSource.includes("INSERT OR IGNORE INTO settings")
    || !storeSource.includes("('auto_restock_enabled', '1')")
    || !storeSource.includes("('auto_restock_threshold', '5')")) {
  throw new Error("fresh content databases must default to enabled precise restock at threshold 5");
}

console.log("Automatic mobile update retry and inventory beacon checks passed.");
