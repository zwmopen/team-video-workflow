package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Communicates with the PC-side Online Gallery Service (port 45835).
 */
public final class OnlineGalleryClient {
    public static final int DEFAULT_PC_PORT = 45835;
    private static final int TIMEOUT_MS = 15000;
    private final Context context;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String cachedBaseUrl = null;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception error);
    }

    public static final class CategoriesResult {
        public final List<CategoryItem> categories;
        public final List<CategoryItem> stages;
        public final int total;

        public CategoriesResult(List<CategoryItem> categories, List<CategoryItem> stages, int total) {
            this.categories = categories;
            this.stages = stages;
            this.total = total;
        }
    }

    public static final class CategoryItem {
        public final String name;
        public final int count;

        public CategoryItem(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    public static final class UseResult {
        public final boolean ok;
        public final String workId;
        public final int useCount;
        public final int remainingUses;
        public final boolean moved;
        public final String message;

        public UseResult(boolean ok, String workId, int useCount, int remainingUses, boolean moved, String message) {
            this.ok = ok;
            this.workId = workId;
            this.useCount = useCount;
            this.remainingUses = remainingUses;
            this.moved = moved;
            this.message = message;
        }
    }

    public OnlineGalleryClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public String resolveBaseUrl() {
        if (cachedBaseUrl != null && !cachedBaseUrl.isEmpty()) {
            return cachedBaseUrl;
        }

        SharedPreferences prefs = context.getSharedPreferences("device_share", Context.MODE_PRIVATE);
        String custom = prefs.getString("customPcServerUrl", "").trim();
        if (!custom.isEmpty()) {
            cachedBaseUrl = custom;
            return custom;
        }

        // 默认优先使用本地回环 127.0.0.1:45835 (针对 ADB reverse 极速通道，零延迟免 Wi-Fi 防火墙阻拦)
        String loopback = "http://127.0.0.1:" + DEFAULT_PC_PORT;
        cachedBaseUrl = loopback;
        return loopback;
    }

    public void setCustomBaseUrl(String url) {
        this.cachedBaseUrl = url;
        context.getSharedPreferences("device_share", Context.MODE_PRIVATE).edit()
                .putString("customPcServerUrl", url == null ? "" : url).apply();
    }

    public void checkConnection(Callback<Boolean> callback) {
        executor.execute(() -> {
            String baseUrl = resolveBaseUrl();
            if (ping(baseUrl)) {
                mainHandler.post(() -> callback.onSuccess(true));
                return;
            }
            // 自动回退探测 ADB reverse 回环端口 (127.0.0.1:45835)
            String loopback = "http://127.0.0.1:" + DEFAULT_PC_PORT;
            if (!loopback.equals(baseUrl) && ping(loopback)) {
                setCustomBaseUrl(loopback);
                mainHandler.post(() -> callback.onSuccess(true));
                return;
            }
            mainHandler.post(() -> callback.onSuccess(false));
        });
    }

    private boolean ping(String baseUrl) {
        try {
            URL url = new URL(baseUrl + "/api/online/status");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public void fetchCategories(Callback<CategoriesResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/categories");
                String resp = httpGet(url);
                JSONObject json = new JSONObject(resp);
                List<CategoryItem> categories = new ArrayList<>();
                JSONArray catArr = json.optJSONArray("categories");
                if (catArr != null) {
                    for (int i = 0; i < catArr.length(); i++) {
                        JSONObject obj = catArr.optJSONObject(i);
                        if (obj != null) {
                            categories.add(new CategoryItem(obj.optString("name", ""), obj.optInt("count", 0)));
                        }
                    }
                }
                List<CategoryItem> stages = new ArrayList<>();
                JSONArray stageArr = json.optJSONArray("stages");
                if (stageArr != null) {
                    for (int i = 0; i < stageArr.length(); i++) {
                        JSONObject obj = stageArr.optJSONObject(i);
                        if (obj != null) {
                            stages.add(new CategoryItem(obj.optString("name", ""), obj.optInt("count", 0)));
                        }
                    }
                }
                int total = json.optInt("total", 0);
                CategoriesResult result = new CategoriesResult(categories, stages, total);
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void fetchWorks(String category, String query, Callback<List<OnlineWorkEntry>> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                StringBuilder sb = new StringBuilder(baseUrl).append("/api/online/works?");
                if (category != null && !category.isEmpty()) {
                    sb.append("category=").append(URLEncoder.encode(category, "UTF-8")).append("&");
                }
                if (query != null && !query.isEmpty()) {
                    sb.append("query=").append(URLEncoder.encode(query, "UTF-8")).append("&");
                }
                URL url = new URL(sb.toString());
                String resp = httpGet(url);
                JSONObject json = new JSONObject(resp);
                List<OnlineWorkEntry> list = new ArrayList<>();
                JSONArray arr = json.optJSONArray("works");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject wObj = arr.optJSONObject(i);
                        if (wObj != null) {
                            list.add(OnlineWorkEntry.fromJson(wObj));
                        }
                    }
                }
                mainHandler.post(() -> callback.onSuccess(list));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void loadThumbnail(String workId, String fileName, Callback<Bitmap> callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String baseUrl = resolveBaseUrl();
                String uStr = baseUrl + "/api/online/image?id=" + URLEncoder.encode(workId, "UTF-8")
                        + "&file=" + URLEncoder.encode(fileName, "UTF-8") + "&thumb=1";
                URL url = new URL(uStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Connection", "close");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new Exception("HTTP " + code);
                }
                InputStream in = new BufferedInputStream(conn.getInputStream());
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                in.close();
                byte[] imgBytes = baos.toByteArray();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length, opts);
                if (bmp == null) throw new Exception("Failed to decode bitmap bytes: " + imgBytes.length);
                mainHandler.post(() -> callback.onSuccess(bmp));
            } catch (Exception e) {
                Log.w("OnlineGalleryClient", "loadThumbnail failed for " + workId + " / " + fileName + ": " + e.getMessage());
                mainHandler.post(() -> callback.onError(e));
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    public static String getDiskCacheKey(String workId, String fileName) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest((workId + "::" + fileName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString() + ".jpg";
        } catch (Exception e) {
            return Math.abs((workId + "_" + fileName).hashCode()) + ".jpg";
        }
    }

    public boolean hasFullImageCached(String workId, String fileName) {
        File cacheDir = new File(context.getCacheDir(), "online_full_images");
        File diskFile = new File(cacheDir, getDiskCacheKey(workId, fileName));
        return diskFile.exists() && diskFile.length() > 1024;
    }

    public void loadFullImage(String workId, String fileName, Callback<Bitmap> callback) {
        executor.execute(() -> {
            // 1. 优先从本地磁盘持久化缓存读取（秒开原画，绝不重复拉取）
            File cacheDir = new File(context.getCacheDir(), "online_full_images");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File diskFile = new File(cacheDir, getDiskCacheKey(workId, fileName));

            if (diskFile.exists() && diskFile.length() > 1024) {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Bitmap bmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath(), opts);
                    if (bmp != null) {
                        mainHandler.post(() -> callback.onSuccess(bmp));
                        return;
                    }
                } catch (Throwable ignored) {}
            }

            // 2. 本地无缓存或损坏，从电脑在线服务拉取并原子安全落盘
            HttpURLConnection conn = null;
            File tempFile = new File(cacheDir, getDiskCacheKey(workId, fileName) + ".tmp");
            try {
                String baseUrl = resolveBaseUrl();
                String uStr = baseUrl + "/api/online/image?id=" + URLEncoder.encode(workId, "UTF-8")
                        + "&file=" + URLEncoder.encode(fileName, "UTF-8") + "&thumb=0";
                URL url = new URL(uStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Connection", "close");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(20000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new Exception("HTTP " + code);
                }
                InputStream in = new BufferedInputStream(conn.getInputStream());
                FileOutputStream fos = new FileOutputStream(tempFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                fos.flush();
                fos.close();
                in.close();

                if (tempFile.length() > 1024) {
                    if (diskFile.exists()) diskFile.delete();
                    tempFile.renameTo(diskFile);
                }

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath(), opts);
                if (bmp == null) throw new Exception("Failed to decode saved bitmap");
                mainHandler.post(() -> callback.onSuccess(bmp));
            } catch (Exception e) {
                if (tempFile.exists()) tempFile.delete();
                Log.w("OnlineGalleryClient", "loadFullImage failed for " + workId + " / " + fileName + ": " + e.getMessage());
                mainHandler.post(() -> callback.onError(e));
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    public void recordUse(String workId, String deviceName, String platform, Callback<UseResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/use-work");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                body.put("device", deviceName);
                body.put("platform", platform);
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                UseResult res = new UseResult(
                        json.optBoolean("ok", false),
                        json.optString("workId", workId),
                        json.optInt("useCount", 1),
                        json.optInt("remainingUses", 1),
                        json.optBoolean("moved", false),
                        json.optString("message", "")
                );
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void resetWork(String workId, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/reset-work");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                boolean ok = json.optBoolean("ok", false);
                mainHandler.post(() -> callback.onSuccess(ok));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public static class DeleteResult {
        public final boolean ok;
        public final String workId;
        public final String action;
        public final String message;
        public final String targetPath;
        public final int remainingWorks;

        public DeleteResult(boolean ok, String workId, String action, String message, String targetPath, int remainingWorks) {
            this.ok = ok;
            this.workId = workId;
            this.action = action;
            this.message = message;
            this.targetPath = targetPath;
            this.remainingWorks = remainingWorks;
        }
    }

    public void deleteWork(String workId, Callback<DeleteResult> callback) {
        deleteWork(workId, "", callback);
    }

    public void deleteWork(String workId, String remark, Callback<DeleteResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/delete-work");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                body.put("deviceName", android.os.Build.MODEL != null ? android.os.Build.MODEL : "移动端");
                if (remark != null && !remark.trim().isEmpty()) {
                    body.put("remark", remark.trim());
                }
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                DeleteResult res = new DeleteResult(
                        json.optBoolean("ok", false),
                        json.optString("workId", workId),
                        json.optString("action", ""),
                        json.optString("message", ""),
                        json.optString("targetPath", ""),
                        json.optInt("remainingWorks", -1)
                );
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public interface DownloadProgressCallback {
        void onProgress(int downloaded, int total, String currentFileName);
        void onSuccess(List<java.io.File> files);
        void onError(Exception error);
    }

    public void downloadWorkImages(String workId, List<String> fileNames, Callback<List<java.io.File>> callback) {
        downloadWorkImages(workId, fileNames, new DownloadProgressCallback() {
            @Override public void onProgress(int downloaded, int total, String currentFileName) {}
            @Override public void onSuccess(List<java.io.File> files) { callback.onSuccess(files); }
            @Override public void onError(Exception error) { callback.onError(error); }
        });
    }

    public void downloadWorkImages(String workId, List<String> fileNames, DownloadProgressCallback callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                java.io.File targetDir = new java.io.File(context.getFilesDir(), "work-library/online/" + workId);
                if (!targetDir.exists()) targetDir.mkdirs();

                List<java.io.File> result = new ArrayList<>();
                int total = fileNames != null ? fileNames.size() : 0;
                int count = 0;
                if (fileNames != null) {
                    for (String fileName : fileNames) {
                        final int progStart = count;
                        final String curName = fileName;
                        mainHandler.post(() -> callback.onProgress(progStart, total, curName));

                        java.io.File localFile = new java.io.File(targetDir, fileName);
                        if (localFile.exists() && localFile.length() > 0) {
                            result.add(localFile);
                            count++;
                            final int progDone = count;
                            mainHandler.post(() -> callback.onProgress(progDone, total, curName));
                            continue;
                        }
                        String uStr = baseUrl + "/api/online/image?id=" + URLEncoder.encode(workId, "UTF-8")
                                + "&file=" + URLEncoder.encode(fileName, "UTF-8");
                        URL url = new URL(uStr);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(TIMEOUT_MS);
                        conn.setReadTimeout(TIMEOUT_MS * 3);
                        if (conn.getResponseCode() != 200) {
                            throw new Exception("HTTP " + conn.getResponseCode() + " 下载 " + fileName + " 失败");
                        }
                        InputStream in = new BufferedInputStream(conn.getInputStream());
                        java.io.FileOutputStream out = new java.io.FileOutputStream(localFile);
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                        out.flush();
                        out.close();
                        in.close();
                        conn.disconnect();
                        result.add(localFile);
                        count++;
                        final int progDone = count;
                        mainHandler.post(() -> callback.onProgress(progDone, total, curName));
                    }
                }
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    private String httpGet(URL url) throws Exception {
        try {
            return rawHttpGet(url);
        } catch (Exception e) {
            String uStr = url.toString();
            String loopback = "http://127.0.0.1:" + DEFAULT_PC_PORT;
            if (!uStr.startsWith(loopback)) {
                try {
                    String fallback = uStr.replaceFirst("^https?://[^/]+", loopback);
                    String res = rawHttpGet(new URL(fallback));
                    cachedBaseUrl = loopback;
                    return res;
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    private static String rawHttpGet(URL url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " " + conn.getResponseMessage());
        }
        InputStream in = new BufferedInputStream(conn.getInputStream());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        in.close();
        conn.disconnect();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private String httpPost(URL url, String jsonBody) throws Exception {
        try {
            return rawHttpPost(url, jsonBody);
        } catch (Exception e) {
            String uStr = url.toString();
            String loopback = "http://127.0.0.1:" + DEFAULT_PC_PORT;
            if (!uStr.startsWith(loopback)) {
                try {
                    String fallback = uStr.replaceFirst("^https?://[^/]+", loopback);
                    String res = rawHttpPost(new URL(fallback), jsonBody);
                    cachedBaseUrl = loopback;
                    return res;
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    private static String rawHttpPost(URL url, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(data.length);
        OutputStream out = conn.getOutputStream();
        out.write(data);
        out.flush();
        out.close();

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " " + conn.getResponseMessage());
        }
        InputStream in = new BufferedInputStream(conn.getInputStream());
        ByteArrayOutputStream respOut = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            respOut.write(buf, 0, len);
        }
        in.close();
        conn.disconnect();
        return respOut.toString(StandardCharsets.UTF_8.name());
    }
}
