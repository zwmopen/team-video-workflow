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
import java.util.List;

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
            } else if ((!entry.sourceDocumentId.isEmpty() && tree == null)
                    || (!entry.sourceRelativePath.isEmpty() && legacyRoot == null)) {
                throw new IOException("请重新选择作品文件夹后再清理");
            }
        } catch (FileNotFoundException missing) {
            // The user already removed the source folder; the private trash copy remains recoverable.
            result.alreadyMissing++;
        } catch (Exception error) {
            result.failures.add(entry.name + "：" + safeMessage(error));
        }
        return result;
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
            if (!RetentionPolicy.shouldPurge(entry.firstSharedAtMs, nowMs, deleteAfterMs)) continue;
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
            } catch (FileNotFoundException missing) {
                library.clearExternalTrashLocation(entry.id);
                result.alreadyMissing++;
            } catch (Exception error) {
                result.failures.add(entry.name + "：" + safeMessage(error));
            }
        }
        return result;
    }

    private static void deleteExternalEntry(
            ContentResolver resolver, Uri tree, File legacyRoot, WorkLibrary library,
            WorkLibrary.WorkEntry entry, Result result) throws IOException {
        if (!entry.trashDocumentId.isEmpty() && tree != null) {
            Uri target = DocumentsContract.buildDocumentUriUsingTree(tree, entry.trashDocumentId);
            if (!DocumentsContract.deleteDocument(resolver, target)) {
                throw new IOException("系统没有删除回收站原文件夹");
            }
            result.deleted++;
        } else if (!entry.externalTrashName.isEmpty() && legacyRoot != null) {
            File trashRoot = safeChild(legacyRoot, TRASH_NAME);
            deleteTree(safeChild(trashRoot, entry.externalTrashName));
            result.deleted++;
        } else if ((!entry.sourceDocumentId.isEmpty() && tree == null)
                || (!entry.sourceRelativePath.isEmpty() && legacyRoot == null)) {
            throw new IOException("请重新选择作品文件夹后再清理");
        }
        library.clearExternalTrashLocation(entry.id);
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
        if (target.exists() && !target.delete()) throw new IOException("无法删除“" + target.getName() + "”");
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
