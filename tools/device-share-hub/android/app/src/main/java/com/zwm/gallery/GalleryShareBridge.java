package com.zwm.gallery;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Publishes short-lived copies through the system media library. OEM app-clone
 * profiles handle MediaStore URIs more reliably than an app-private provider.
 */
final class GalleryShareBridge {
    private static final String PREFS = "gallery_share_cache";
    private static final String KEY_ITEMS = "items";
    private static final String SEPARATOR = "\u001f";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/相册分享缓存";

    private GalleryShareBridge() {
    }

    static PreparedShare prepare(Context context, WorkLibrary.WorkEntry work) {
        cleanupPreviousDays(context, LocalDate.now());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new PreparedShare(privateUris(context, work), "private_provider", "");
        }

        ArrayList<Uri> published = new ArrayList<>();
        try {
            for (String image : work.images) {
                published.add(publish(context, new File(work.directory, image), image));
            }
            remember(context, published, LocalDate.now());
            return new PreparedShare(published, "media_store", "");
        } catch (Exception error) {
            deletePublished(context, published);
            DiagnosticLog.write(context, "share_media_prepare_failed", error.getClass().getSimpleName()
                    + ": " + error.getMessage());
            return new PreparedShare(privateUris(context, work), "private_provider_fallback",
                    "系统媒体分享准备失败，已改用普通分享；应用分身可能无法读取图片");
        }
    }

    static void cleanupPreviousDays(Context context, LocalDate today) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> stored = preferences.getStringSet(KEY_ITEMS, Collections.emptySet());
        if (stored.isEmpty()) return;
        HashSet<String> retained = new HashSet<>();
        int removed = 0;
        for (String item : stored) {
            int split = item.lastIndexOf(SEPARATOR);
            if (split <= 0 || split >= item.length() - 1) {
                retained.add(item);
                continue;
            }
            try {
                LocalDate created = LocalDate.parse(item.substring(split + SEPARATOR.length()));
                if (!today.isAfter(created)) {
                    retained.add(item);
                    continue;
                }
                Uri uri = Uri.parse(item.substring(0, split));
                if (!"media".equals(uri.getAuthority())) {
                    retained.add(item);
                    continue;
                }
                context.getContentResolver().delete(uri, null, null);
                removed++;
            } catch (Exception error) {
                retained.add(item);
                DiagnosticLog.write(context, "share_media_cleanup_failed", error.getMessage());
            }
        }
        if (!retained.equals(stored)) preferences.edit().putStringSet(KEY_ITEMS, retained).apply();
        if (removed > 0) DiagnosticLog.write(context, "share_media_cleanup", "removed=" + removed);
    }

    private static Uri publish(Context context, File source, String requestedName) throws Exception {
        if (!source.isFile()) throw new IllegalStateException("图片不存在：" + requestedName);
        ContentResolver resolver = context.getContentResolver();
        String mime = mimeType(requestedName);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, requestedName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        values.put(MediaStore.Images.Media.DATE_TAKEN, source.lastModified());
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri destination = resolver.insert(collection, values);
        if (destination == null) throw new IllegalStateException("系统媒体库没有返回文件地址");
        boolean completed = false;
        try (FileInputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(destination, "w")) {
            if (output == null) throw new IllegalStateException("系统媒体库无法写入图片");
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            output.flush();
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(destination, values, null, null) != 1) {
                throw new IllegalStateException("系统媒体库无法发布图片");
            }
            completed = true;
            return destination;
        } finally {
            if (!completed) resolver.delete(destination, null, null);
        }
    }

    private static ArrayList<Uri> privateUris(Context context, WorkLibrary.WorkEntry work) {
        ArrayList<Uri> result = new ArrayList<>();
        for (String image : work.images) {
            result.add(new Uri.Builder()
                    .scheme("content")
                    .authority(context.getPackageName() + ".files")
                    .appendPath("active")
                    .appendPath(work.id)
                    .appendPath(image)
                    .build());
        }
        return result;
    }

    private static void remember(Context context, List<Uri> uris, LocalDate date) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HashSet<String> stored = new HashSet<>(preferences.getStringSet(KEY_ITEMS, Collections.emptySet()));
        for (Uri uri : uris) stored.add(uri + SEPARATOR + date);
        if (!preferences.edit().putStringSet(KEY_ITEMS, stored).commit()) {
            DiagnosticLog.write(context, "share_media_cache_record_failed", "count=" + uris.size());
        }
    }

    private static void deletePublished(Context context, List<Uri> uris) {
        for (Uri uri : uris) {
            try {
                if ("media".equals(uri.getAuthority())) context.getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {
            }
        }
    }

    private static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null || !mime.startsWith("image/") ? "image/jpeg" : mime;
    }

    static final class PreparedShare {
        final ArrayList<Uri> uris;
        final String strategy;
        final String warning;

        PreparedShare(ArrayList<Uri> uris, String strategy, String warning) {
            this.uris = uris;
            this.strategy = strategy;
            this.warning = warning;
        }
    }
}
