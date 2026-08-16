package com.zwm.gallery;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps the selected source tree and the app-private recoverable trash in sync. */
final class ExternalTrashManager {
    static final String TRASH_NAME = "相册回收站";

    private ExternalTrashManager() {
    }

    static Result moveTrashedSources(ContentResolver resolver, Uri tree, File legacyRoot, WorkLibrary library)
            throws IOException {
        Result result = new Result();
        for (WorkLibrary.WorkEntry entry : library.listTrash()) {
            if (!entry.trashDocumentId.isEmpty() || !entry.externalTrashName.isEmpty()) continue;
            result.add(moveTrashedSource(resolver, tree, legacyRoot, library, entry));
        }
        return result;
    }

    static WorkLibrary.ReconcileResult reconcileMissingExternalSources(
            ContentResolver resolver,
            Uri tree,
            WorkLibrary library,
            Set<String> detectedActiveDocumentIds) throws IOException {
        HashSet<String> existing = new HashSet<>(detectedActiveDocumentIds);
        for (WorkLibrary.WorkEntry entry : library.listActive()) {
            if (entry.sourceDocumentId.isEmpty()
                    || existing.contains(entry.sourceDocumentId)) {
                continue;
            }
            // Some vendor document providers briefly return an empty child list while they
            // rebuild their index after an app update. Never treat that one frame as deletion.
            if (documentExists(resolver, tree, entry.sourceDocumentId)) {
                existing.add(entry.sourceDocumentId);
            }
        }
        for (WorkLibrary.WorkEntry entry : library.listTrash()) {
            String locator = entry.trashDocumentId.isEmpty()
                    ? entry.sourceDocumentId : entry.trashDocumentId;
            if (locator.isEmpty() || existing.contains(locator)) continue;
            if (documentExists(resolver, tree, locator)) existing.add(locator);
        }
        return library.reconcileExternalDocumentIds(
                existing, existing, System.currentTimeMillis());
    }

    private static boolean documentExists(ContentResolver resolver, Uri tree, String documentId)
            throws IOException {
        if (tree == null || documentId == null || documentId.isEmpty()) return false;
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        try (Cursor cursor = resolver.query(document,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cursor == null) throw new IOException("系统未返回文件状态");
            return cursor.moveToFirst();
        } catch (FileNotFoundException missing) {
            return false;
        } catch (SecurityException denied) {
            throw new IOException("作品文件夹权限已失效，请重新选择", denied);
        } catch (IllegalArgumentException missing) {
            String message = missing.getMessage() == null ? "" : missing.getMessage();
            if (message.contains("Missing file") || message.contains("Failed to determine")) {
                return false;
            }
            throw new IOException("无法核对回收站原文件", missing);
        }
    }

    static Result moveTrashedSource(ContentResolver resolver, Uri tree, File legacyRoot,
                                    WorkLibrary library, WorkLibrary.WorkEntry entry) {
        Result result = new Result();
        try {
            if (!entry.sourceDocumentId.isEmpty() && tree != null) {
                Uri trash = ensureSafTrash(resolver, tree);
                Uri source = DocumentsContract.buildDocumentUriUsingTree(tree, entry.sourceDocumentId);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, entry.sourceParentDocumentId);
                Uri moved = DocumentsContract.moveDocument(resolver, source, parent, trash);
                if (moved == null) throw new IOException("系统没有完成原文件夹移动");
                // Count the external move before persisting its location. If metadata persistence fails,
                // callers must not roll the private entry back to the now-empty original location.
                result.moved++;
                library.updateExternalTrashLocation(
                        entry.id, DocumentsContract.getDocumentId(moved), "");
            } else if (!entry.sourceRelativePath.isEmpty() && legacyRoot != null) {
                moveLegacyToTrash(entry, legacyRoot, library);
                result.moved++;
            } else if (legacyRoot != null) {
                File existingTrash = findLegacyTrashEntry(legacyRoot, entry);
                if (existingTrash != null) {
                    library.updateExternalTrashLocation(entry.id, "", existingTrash.getName());
                    result.alreadyMissing++;
                }
            } else if ((!entry.sourceDocumentId.isEmpty() && tree == null)
                    || (!entry.sourceRelativePath.isEmpty() && legacyRoot == null)) {
                throw new IOException("请重新选择作品文件夹后再清理");
            }
        } catch (FileNotFoundException missing) {
            recoverMissingHuaweiSource(legacyRoot, library, entry, result);
        } catch (Exception error) {
            // HarmonyOS sometimes transports FileNotFoundException through Binder as a
            // runtime ParcelableException, so checking only the Java exception type is
            // insufficient.
            if (isMissingDocument(error)) {
                recoverMissingHuaweiSource(legacyRoot, library, entry, result);
            } else {
                result.failures.add(entry.name + "：" + safeMessage(error));
            }
        }
        return result;
    }

    private static void recoverMissingHuaweiSource(
            File legacyRoot, WorkLibrary library, WorkLibrary.WorkEntry entry, Result result) {
        try {
            File existingTrash = legacyRoot == null ? null : findLegacyTrashEntry(legacyRoot, entry);
            if (existingTrash != null) {
                library.updateExternalTrashLocation(entry.id, "", existingTrash.getName());
                result.alreadyMissing++;
            } else if (legacyRoot != null && !entry.sourceRelativePath.isEmpty()
                    && safeRelative(legacyRoot, entry.sourceRelativePath).exists()) {
                moveLegacyToTrash(entry, legacyRoot, library);
                result.moved++;
            } else {
                library.deleteTrash(entry.id);
                result.alreadyMissing++;
            }
        } catch (IOException cleanupFailure) {
            result.failures.add(entry.name + "：" + safeMessage(cleanupFailure));
        }
    }

    static Result purgeExpired(ContentResolver resolver, Uri tree, File legacyRoot,
                               WorkLibrary library, LocalDate today) throws IOException {
        Result result = moveTrashedSources(resolver, tree, legacyRoot, library);
        if (!result.failures.isEmpty()) return result;
        for (WorkLibrary.WorkEntry entry : library.listTrash()) {
            if (!RetentionPolicy.shouldPurge(entry.trashedDate, today)) continue;
            try {
                deleteExternalEntry(resolver, tree, legacyRoot, library, entry, result);
                library.deleteTrash(entry.id);
            } catch (Exception error) {
                result.failures.add(entry.name + "：" + safeMessage(error));
            }
        }
        return result;
    }

    static Result purgeExpired(ContentResolver resolver, Uri tree, File legacyRoot,
                               WorkLibrary library, long nowMs, long deleteAfterMs) throws IOException {
        Result result = moveTrashedSources(resolver, tree, legacyRoot, library);
        if (!result.failures.isEmpty()) return result;
        for (WorkLibrary.WorkEntry entry : library.listTrash()) {
            boolean due = entry.deleteScheduledAtMs > 0
                    ? nowMs >= entry.deleteScheduledAtMs
                    : RetentionPolicy.shouldPurge(entry.firstSharedAtMs, nowMs, deleteAfterMs);
            if (!due) continue;
            try {
                deleteExternalEntry(resolver, tree, legacyRoot, library, entry, result);
                library.deleteTrash(entry.id);
            } catch (Exception error) {
                result.failures.add(entry.name + "：" + safeMessage(error));
            }
        }
        return result;
    }

    static Result clearTrackedTrash(ContentResolver resolver, Uri tree, File legacyRoot, WorkLibrary library)
            throws IOException {
        Result result = moveTrashedSources(resolver, tree, legacyRoot, library);
        if (!result.failures.isEmpty()) return result;
        for (WorkLibrary.WorkEntry entry : library.listTrash()) {
            try {
                deleteExternalEntry(resolver, tree, legacyRoot, library, entry, result);
                library.deleteTrash(entry.id);
            } catch (FileNotFoundException missing) {
                library.deleteTrash(entry.id);
                result.alreadyMissing++;
            } catch (Exception error) {
                result.failures.add(entry.name + "：" + safeMessage(error));
            }
        }
        // Old Huawei builds could lose the private locator while leaving the real folder
        // behind. "Clear trash" means clearing the dedicated external trash directory too,
        // including those orphaned children.
        // On Android 10 Huawei devices the direct, user-approved legacy view is more
        // reliable than MediaProvider for folders that were created before the upgrade.
        if (legacyRoot != null) {
            int failuresBeforeOrphanCleanup = result.failures.size();
            clearLegacyTrashChildren(legacyRoot, result);
            File[] remaining = safeChild(legacyRoot, TRASH_NAME).listFiles();
            if (remaining == null || remaining.length > 0) {
                result.failures.add("仍有 " + (remaining == null ? "未知数量" : remaining.length)
                        + " 个原文件夹未删除");
            } else {
                while (result.failures.size() > failuresBeforeOrphanCleanup) {
                    result.failures.remove(result.failures.size() - 1);
                }
            }
        } else if (tree != null) {
            clearSafTrashChildren(resolver, tree, result);
        }
        return result;
    }

    private static void clearSafTrashChildren(
            ContentResolver resolver, Uri tree, Result result) throws IOException {
        Uri trash = ensureSafTrash(resolver, tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(trash));
        ArrayList<Uri> targets = new ArrayList<>();
        try (Cursor cursor = resolver.query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cursor == null) throw new IOException("系统没有返回回收站内容");
            while (cursor.moveToNext()) {
                targets.add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)));
            }
        }
        for (Uri target : targets) {
            try {
                if (DocumentsContract.deleteDocument(resolver, target)) result.deleted++;
                else result.failures.add("系统没有删除一个回收站项目");
            } catch (Exception error) {
                if (isMissingDocument(error)) result.alreadyMissing++;
                else result.failures.add("回收站项目：" + safeMessage(error));
            }
        }
    }

    private static void clearLegacyTrashChildren(File legacyRoot, Result result) throws IOException {
        File trashRoot = safeChild(legacyRoot, TRASH_NAME);
        File[] children = trashRoot.listFiles();
        if (children == null) return;
        for (File child : children) {
            try {
                deleteTreeWithVendorRetries(child);
                result.deleted++;
            } catch (IOException error) {
                result.failures.add(child.getName() + "：" + safeMessage(error));
            }
        }
    }

    private static void deleteExternalEntry(
            ContentResolver resolver, Uri tree, File legacyRoot, WorkLibrary library,
            WorkLibrary.WorkEntry entry, Result result) throws IOException {
        if (!entry.trashDocumentId.isEmpty() && tree != null) {
            Uri target = DocumentsContract.buildDocumentUriUsingTree(tree, entry.trashDocumentId);
            try {
                if (!DocumentsContract.deleteDocument(resolver, target)) {
                    throw new IOException("系统没有删除回收站原文件夹");
                }
                result.deleted++;
            } catch (Exception staleHuaweiIndex) {
                if (!isMissingDocument(staleHuaweiIndex)) {
                    if (staleHuaweiIndex instanceof IOException) {
                        throw (IOException) staleHuaweiIndex;
                    }
                    throw new IOException("系统无法删除回收站原文件夹", staleHuaweiIndex);
                }
                deleteLegacyFallback(legacyRoot, entry, result);
            }
        } else if (!entry.externalTrashName.isEmpty() && tree != null && legacyRoot == null) {
            Uri trash = ensureSafTrash(resolver, tree);
            Uri target = findSafChildByName(resolver, tree, trash, entry.externalTrashName);
            if (target == null) {
                result.alreadyMissing++;
            } else if (!DocumentsContract.deleteDocument(resolver, target)) {
                throw new IOException("系统没有删除回收站原文件夹");
            } else {
                result.deleted++;
            }
        } else if (legacyRoot != null && (!entry.externalTrashName.isEmpty()
                || !entry.trashDocumentId.isEmpty() || !entry.sourceDocumentId.isEmpty())) {
            File trashRoot = safeChild(legacyRoot, TRASH_NAME);
            File target = !entry.externalTrashName.isEmpty()
                    ? safeChild(trashRoot, entry.externalTrashName)
                    : findLegacyTrashEntry(legacyRoot, entry);
            if (target != null && target.exists()) {
                deleteTreeWithVendorRetries(target);
                result.deleted++;
            } else {
                result.alreadyMissing++;
            }
        } else if ((!entry.sourceDocumentId.isEmpty() && tree == null)
                || (!entry.sourceRelativePath.isEmpty() && legacyRoot == null)) {
            throw new IOException("请重新选择作品文件夹后再清理");
        }
        library.clearExternalTrashLocation(entry.id);
    }

    private static Uri findSafChildByName(
            ContentResolver resolver, Uri tree, Uri parent, String displayName) throws IOException {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("系统没有返回回收站内容");
            Uri match = null;
            while (cursor.moveToNext()) {
                if (!displayName.equals(cursor.getString(1))) continue;
                if (match != null) throw new IOException("回收站存在重复同名文件夹");
                match = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
            }
            return match;
        }
    }

    private static void deleteLegacyFallback(
            File legacyRoot, WorkLibrary.WorkEntry entry, Result result) throws IOException {
        File fallback = legacyRoot == null ? null : findLegacyTrashEntry(legacyRoot, entry);
        if (fallback != null && fallback.exists()) {
            deleteTreeWithVendorRetries(fallback);
            result.deleted++;
        } else {
            result.alreadyMissing++;
        }
    }

    private static boolean isMissingDocument(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof FileNotFoundException) return true;
            String message = current.getMessage();
            if (message != null && (message.contains("No item at content://")
                    || message.contains("Missing file")
                    || message.contains("Failed to determine"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static File findLegacyTrashEntry(File legacyRoot, WorkLibrary.WorkEntry entry)
            throws IOException {
        File trashRoot = safeChild(legacyRoot, TRASH_NAME);
        if (!trashRoot.isDirectory()) return null;
        File[] children = trashRoot.listFiles();
        if (children == null) throw new IOException("无法读取相册回收站");
        String prefix = entry.id + "--";
        File match = null;
        for (File child : children) {
            if (!child.getName().startsWith(prefix)) continue;
            if (match != null) throw new IOException("回收站存在重复作品，请重新连接文件夹");
            match = child;
        }
        return match;
    }

    static void restoreSource(ContentResolver resolver, Uri tree, File legacyRoot,
                              WorkLibrary library, WorkLibrary.WorkEntry entry) throws IOException {
        try {
            if (!entry.trashDocumentId.isEmpty() && tree != null) {
                Uri trashFolder = ensureSafTrash(resolver, tree);
                Uri source = DocumentsContract.buildDocumentUriUsingTree(tree, entry.trashDocumentId);
                Uri destination = DocumentsContract.buildDocumentUriUsingTree(tree, entry.sourceParentDocumentId);
                Uri restored = DocumentsContract.moveDocument(resolver, source, trashFolder, destination);
                if (restored == null) throw new IOException("系统没有完成原文件夹恢复");
            } else if (!entry.externalTrashName.isEmpty() && legacyRoot != null) {
                File source = safeChild(safeChild(legacyRoot, TRASH_NAME), entry.externalTrashName);
                File destination = safeRelative(legacyRoot, entry.sourceRelativePath);
                File parent = destination.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法重建原目录");
                }
                if (destination.exists()) throw new IOException("原位置已有同名文件夹");
                moveDirectory(source, destination);
            }
            library.clearExternalTrashLocation(entry.id);
        } catch (FileNotFoundException missing) {
            library.clearExternalTrashLocation(entry.id);
        }
    }

    private static Uri ensureSafTrash(ContentResolver resolver, Uri tree) throws IOException {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取作品总文件夹");
            while (cursor.moveToNext()) {
                if (TRASH_NAME.equals(cursor.getString(1))
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                }
            }
        }
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        Uri created = DocumentsContract.createDocument(
                resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, TRASH_NAME);
        if (created == null) throw new IOException("无法创建“" + TRASH_NAME + "”");
        return created;
    }

    private static void moveLegacyToTrash(
            WorkLibrary.WorkEntry entry, File legacyRoot, WorkLibrary library) throws IOException {
        File source = safeRelative(legacyRoot, entry.sourceRelativePath);
        if (!source.exists()) throw new FileNotFoundException(source.getName());
        File trashRoot = safeChild(legacyRoot, TRASH_NAME);
        if (!trashRoot.isDirectory() && !trashRoot.mkdirs()) throw new IOException("无法创建相册回收站");
        String trashName = entry.id + "--" + source.getName();
        File destination = safeChild(trashRoot, trashName);
        if (destination.exists()) throw new IOException("回收站已有同名作品");
        moveDirectory(source, destination);
        library.updateExternalTrashLocation(entry.id, "", trashName);
    }

    private static File safeRelative(File root, String relative) throws IOException {
        File candidate = new File(root, relative).getCanonicalFile();
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!candidate.getPath().startsWith(rootPath)) throw new IOException("非法来源路径");
        return candidate;
    }

    private static File safeChild(File parent, String name) throws IOException {
        File child = new File(parent, name).getCanonicalFile();
        String parentPath = parent.getCanonicalPath() + File.separator;
        if (!child.getPath().startsWith(parentPath)) throw new IOException("非法回收站路径");
        return child;
    }

    private static void moveDirectory(File source, File destination) throws IOException {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(source.toPath(), destination.toPath());
        }
    }

    private static void deleteTree(File target) throws IOException {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("无法读取回收站目录");
            for (File child : children) deleteTree(child);
        }
        if (target.exists() && !target.delete()) {
            // Huawei Gallery protects freshly generated .hwbk sidecars by suffix. Renaming
            // the sidecar inside the already-authorized trash folder releases that special
            // handling and lets the normal delete complete.
            if (target.isFile() && target.getName().endsWith(".hwbk")) {
                File renamed = new File(target.getParentFile(),
                        ".zwm-delete-" + Long.toHexString(System.nanoTime()));
                if (target.renameTo(renamed) && renamed.delete()) return;
            }
            throw new IOException("无法删除“" + target.getName() + "”");
        }
    }

    private static void deleteTreeWithVendorRetries(File target) throws IOException {
        IOException last = null;
        int consecutiveMissingChecks = 0;
        // Huawei Gallery may create .hwbk sidecars while the original image is being
        // deleted, and can recreate the directory shortly after it first disappears.
        // Require two delayed "still missing" checks before reporting success.
        for (int attempt = 0; attempt < 12; attempt++) {
            if (target.exists()) {
                consecutiveMissingChecks = 0;
                try {
                    deleteTree(target);
                } catch (IOException error) {
                    last = error;
                }
            }
            try {
                Thread.sleep(150L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("清空回收站已中断", interrupted);
            }
            if (!target.exists()) {
                consecutiveMissingChecks++;
                if (consecutiveMissingChecks >= 2) return;
            }
        }
        throw last == null ? new IOException("无法删除“" + target.getName() + "”") : last;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    static final class Result {
        int moved;
        int deleted;
        int alreadyMissing;
        final List<String> failures = new ArrayList<>();

        boolean succeeded() {
            return failures.isEmpty();
        }

        String firstFailure() {
            return failures.isEmpty() ? "" : failures.get(0);
        }

        void add(Result other) {
            moved += other.moved;
            deleted += other.deleted;
            alreadyMissing += other.alreadyMissing;
            failures.addAll(other.failures);
        }
    }
}
