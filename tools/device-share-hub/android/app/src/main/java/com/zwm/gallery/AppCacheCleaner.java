package com.zwm.gallery;

import android.content.Context;

import java.io.File;

final class AppCacheCleaner {
    private static final long TTL_MS = 60L * 60L * 1000L;

    private AppCacheCleaner() {
    }

    static int clean(Context context, long nowMs) {
        int removed = 0;
        File cache = context.getCacheDir();
        removed += cleanChildren(new File(cache, "share"), nowMs - TTL_MS);
        removed += cleanChildren(new File(cache, "tree-import"), nowMs - TTL_MS);
        removed += cleanChildren(new File(cache, "tree-import-service"), nowMs - TTL_MS);
        File[] generatedArchives = cache.listFiles(file ->
                file.isFile() && file.getName().startsWith("album-folder-")
                        && file.getName().endsWith(".zip"));
        if (generatedArchives != null) {
            for (File file : generatedArchives) {
                if (file.lastModified() <= nowMs - TTL_MS && file.delete()) removed++;
            }
        }
        return removed;
    }

    private static int cleanChildren(File root, long cutoff) {
        File[] children = root.listFiles();
        if (children == null) return 0;
        int removed = 0;
        for (File child : children) {
            if (newestModified(child) > cutoff) continue;
            if (deleteTree(child)) removed++;
        }
        return removed;
    }

    private static long newestModified(File file) {
        long newest = file.lastModified();
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) newest = Math.max(newest, newestModified(child));
        }
        return newest;
    }

    private static boolean deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) if (!deleteTree(child)) return false;
        }
        return !file.exists() || file.delete();
    }
}
