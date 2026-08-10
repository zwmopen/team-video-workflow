package com.zwm.gallery;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;

final class CleanupCoordinator {
    private static final String PREF_TREE_URI = "libraryTreeUri";

    private CleanupCoordinator() {
    }

    static Result run(Context context) throws Exception {
        long now = System.currentTimeMillis();
        CleanupSettings.Values settings = CleanupSettings.read(context);
        Result result = new Result();
        GalleryShareBridge.cleanupExpired(context, now);
        result.cacheEntriesDeleted = AppCacheCleaner.clean(context, now);
        WorkLibrary library = new WorkLibrary(new File(context.getFilesDir(), "work-library"));
        result.legacyTimestampsMigrated = library.migrateLegacyCleanupTimestamps(now);
        result.moved = library.maintain(now, settings.moveAfterMs()).size();
        Uri tree = selectedTree(context);
        File legacy = legacyRoot(context, tree);
        ExternalTrashManager.Result external = ExternalTrashManager.moveTrashedSources(
                context.getContentResolver(), tree, legacy, library);
        ExternalTrashManager.Result purged = ExternalTrashManager.purgeExpired(
                context.getContentResolver(), tree, legacy, library, now, settings.deleteAfterMs());
        result.deleted = purged.deleted;
        result.failure = !external.succeeded() ? external.firstFailure()
                : !purged.succeeded() ? purged.firstFailure() : "";
        OnlineService.publishWorkInventory(context, library.listActive());
        return result;
    }

    private static Uri selectedTree(Context context) {
        String value = context.getSharedPreferences(CleanupSettings.PREFS, Context.MODE_PRIVATE)
                .getString(PREF_TREE_URI, "");
        return value.isEmpty() ? null : Uri.parse(value);
    }

    private static File legacyRoot(Context context, Uri tree) throws Exception {
        if (tree == null || Build.VERSION.SDK_INT != 29
                || context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) return null;
        String id = DocumentsContract.getTreeDocumentId(tree);
        int slash = id.lastIndexOf('/');
        String name = slash >= 0 ? id.substring(slash + 1) : id.substring(id.lastIndexOf(':') + 1);
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .getCanonicalFile();
        File selected = new File(downloads, name).getCanonicalFile();
        return selected.toPath().startsWith(downloads.toPath()) && selected.isDirectory() ? selected : null;
    }

    static final class Result {
        int moved;
        int deleted;
        int cacheEntriesDeleted;
        int legacyTimestampsMigrated;
        String failure = "";
    }
}
