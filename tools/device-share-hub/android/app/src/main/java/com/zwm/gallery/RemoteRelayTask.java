package com.zwm.gallery;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Processes committed Cloudflare R2 transfers into local WorkLibrary (deduplicated)
 * with resumable SHA-256 verified downloads, and ACKs only after durable local import.
 */
public final class RemoteRelayTask {
    private static final String TAG = "RemoteRelayTask";
    private static final String PREFS = "album_prefs";

    private RemoteRelayTask() {}

    public static void processInbox(OnlineService service, RemoteRelayClient.Session session) throws Exception {
        JSONArray transfers = RemoteRelayClient.listInbox(session);
        if (transfers.length() == 0) return;
        File stagingRoot = new File(service.getCacheDir(), "remote-inbox");
        if (!stagingRoot.exists()) stagingRoot.mkdirs();

        for (int i = 0; i < transfers.length(); i++) {
            JSONObject transfer = transfers.optJSONObject(i);
            if (transfer == null) continue;
            if (!"ready".equalsIgnoreCase(transfer.optString("status", ""))) continue;
            String transferId = transfer.optString("transferId", "");
            if (transferId.isEmpty()) continue;

            // Deduplication check: if already imported, safely ACK & clean staging
            if (service.hasCommittedTaskReceipt("remote_" + transferId)) {
                try {
                    RemoteRelayClient.ackTransfer(session, transferId);
                } catch (Exception ignored) {}
                continue;
            }

            File taskDir = new File(stagingRoot, transferId);
            if (!taskDir.exists()) taskDir.mkdirs();
            try {
                service.updateRemoteStatus("已切换至云端中转，正在接收…");
                JSONArray objects = transfer.optJSONArray("objects");
                if (objects == null || objects.length() == 0) continue;
                String contentKind = transfer.optString("contentKind", "work");

                long totalBytesDownloaded = 0L;
                List<File> downloadedFiles = new ArrayList<>();
                List<String> names = new ArrayList<>();
                List<String> mimes = new ArrayList<>();

                for (int idx = 0; idx < objects.length(); idx++) {
                    JSONObject obj = objects.getJSONObject(idx);
                    int objIndex = obj.getInt("index");
                    long expectedBytes = obj.optLong("bytes", obj.optLong("cipherBytes", -1L));
                    String expectedSha = obj.optString("sha256", obj.optString("cipherSha256", ""));
                    String rawName = obj.optString("name", "item-" + objIndex);
                    String safeName = new File(rawName).getName();
                    if (safeName.isEmpty() || ".".equals(safeName) || "..".equals(safeName)) {
                        safeName = "file-" + objIndex;
                    }
                    File dest = new File(taskDir, safeName);
                    RemoteRelayClient.downloadTransferObject(
                            session, transferId, objIndex, expectedBytes, expectedSha, dest);
                    downloadedFiles.add(dest);
                    names.add(safeName);
                    mimes.add(obj.optString("mime", "application/octet-stream"));
                    totalBytesDownloaded += dest.length();
                }

                commitReceived(service, transferId, contentKind, downloadedFiles, names, mimes, "r2");
                RemoteRelayClient.ackTransfer(session, transferId);
                service.updateRemoteStatus("已收到云端作品（" + downloadedFiles.size() + " 个文件）");
                DiagnosticLog.write(service, "remote_relay_received",
                        transferId + " files=" + downloadedFiles.size() + " bytes=" + totalBytesDownloaded);
                service.sendBroadcast(new Intent(OnlineService.ACTION_TASK_READY).setPackage(service.getPackageName()));
            } catch (Exception e) {
                Log.w(TAG, "Remote transfer failed: " + transferId + " " + e.getMessage(), e);
                DiagnosticLog.write(service, "remote_relay_failed", transferId + " " + e.getMessage());
                service.updateRemoteStatus("网络中断，正在重连…（" + e.getMessage() + "）");
            } finally {
                deleteDir(taskDir);
            }
        }
    }

    static synchronized void commitReceived(OnlineService service, String transferId, String contentKind,
                                            List<File> downloadedFiles, List<String> names, List<String> mimes,
                                            String channel) throws Exception {
        if (service.hasCommittedTaskReceipt("remote_" + transferId)) return;
        List<File> imageFiles = new ArrayList<>();
        long totalBytesDownloaded = 0;
        for (int i = 0; i < downloadedFiles.size(); i++) {
            File file = downloadedFiles.get(i);
            totalBytesDownloaded += file.length();
            if (WorkRules.isSupportedImage(names.get(i))) imageFiles.add(file);
        }
        WorkLibrary library = new WorkLibrary(new File(service.getFilesDir(), "work-library"));
        int imported = 0;
        int exported = 0;
        if ("android-update".equals(contentKind) && downloadedFiles.size() == 1
                && downloadedFiles.get(0).getName().toLowerCase(Locale.US).endsWith(".apk")) {
            service.stageExternalUpdateApk(downloadedFiles.get(0));
            imported = 1;
        } else {
            String treeValue = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("libraryTreeUri", "");
            if (!treeValue.isEmpty()) {
                Uri tree = Uri.parse(treeValue);
                ArrayList<File> directFiles = new ArrayList<>();
                ArrayList<String> directNames = new ArrayList<>();
                ArrayList<String> directMimes = new ArrayList<>();
                for (int k = 0; k < downloadedFiles.size(); k++) {
                    File f = downloadedFiles.get(k);
                    if (f.getName().toLowerCase(Locale.US).endsWith(".zip")) {
                        exported += DocumentTreeExporter.exportZip(
                                service.getContentResolver(), tree, f, "remote-" + transferId + "-" + k).files;
                    } else {
                        directFiles.add(f);
                        directNames.add(names.get(k));
                        directMimes.add(mimes.get(k));
                    }
                }
                if (!directFiles.isEmpty()) {
                    exported += DocumentTreeExporter.exportBundle(
                            service.getContentResolver(), tree, "云端接收-" + transferId,
                            directFiles, directNames, directMimes, "").files;
                }
                DocumentTreeImporter.ImportResult res = DocumentTreeImporter.importTree(
                        service.getContentResolver(), tree, library,
                        new File(service.getCacheDir(), "tree-import-remote"));
                imported = res.imported;
            } else if (downloadedFiles.size() == 1
                    && downloadedFiles.get(0).getName().toLowerCase(Locale.US).endsWith(".zip")) {
                imported = WorkArchiveImporter.importZip(downloadedFiles.get(0), library, "remote_" + transferId);
            } else if (!imageFiles.isEmpty()) {
                library.importWork("remote_" + transferId, "云端中转作品", "", imageFiles, "");
                imported = 1;
            }
        }

        // Durable local import succeeded -> record stats, mark committed, ACK & clean staging
        if ("p2p".equals(channel) && imported == 0 && exported == 0) {
            throw new IllegalStateException("P2P 未保存任何文件");
        }
        TransferStatsStore.record(service, channel, totalBytesDownloaded, downloadedFiles.size());
        JSONObject receipt = new JSONObject()
                .put("taskId", "remote_" + transferId)
                .put("committed", true)
                .put("imported", imported);
        service.rememberExternalCommittedTask("remote_" + transferId, receipt);
        OnlineService.publishWorkInventory(service, library.listActive());
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) deleteDir(c);
                else c.delete();
            }
        }
        dir.delete();
    }
}
