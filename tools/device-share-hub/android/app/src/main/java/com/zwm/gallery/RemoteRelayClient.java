package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java Cloudflare Relay HTTP client for Android:
 * - ES256 challenge/session authentication (matches Worker `verifyEs256`)
 * - `/v1/presence` heartbeat + `/v1/inbox` polling
 * - HTTP Range 206 resumable object download + SHA-256 verification before ACK
 * - Authenticated Remote Online Album (`/v1/catalog/*`, on-demand HD pull + immediate R2 cleanup)
 * - LAN auto-enrollment (`/api/online/remote-enroll`) & `/v1/stats`
 */
public final class RemoteRelayClient {
    private static final String TAG = "RemoteRelayClient";
    private static final String PREFS = "album_prefs";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int OBJECT_READ_TIMEOUT_MS = 90_000;
    private static final long ENROLL_COOLDOWN_MS = 10 * 60 * 1000L;
    private static final AtomicLong lastLanEnrollMs = new AtomicLong(0L);
    private static volatile Session cachedSession;
    private static String unavailableEndpoint = "";
    private static long unavailableUntil;
    private static final Object SESSION_LOCK = new Object();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .dns(new RelayDns())
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private static final OkHttpClient OBJECT_HTTP = HTTP.newBuilder()
            .readTimeout(OBJECT_READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build();

    private static final OkHttpClient LOCAL_PROXY = HTTP.newBuilder()
            .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress("127.0.0.1", 7890)))
            .build();
    private static final OkHttpClient LOCAL_PROXY_OBJECT = LOCAL_PROXY.newBuilder()
            .readTimeout(OBJECT_READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build();
    private static final OkHttpClient DIRECT_PROBE = HTTP.newBuilder()
            .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build();
    private static final OkHttpClient PROXY_PROBE = LOCAL_PROXY.newBuilder()
            .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build();
    private static final Object ROUTE_LOCK = new Object();
    private static long routeExpiresAt;
    private static boolean useExistingLocalProxy;

    private static OkHttpClient relayHttp(Request request, boolean object) {
        String defaultHost = okhttp3.HttpUrl.get(RemoteRelayProfile.DEFAULT_ENDPOINT).host();
        if (!request.url().isHttps() || !defaultHost.equals(request.url().host())) {
            return object ? OBJECT_HTTP : HTTP;
        }
        synchronized (ROUTE_LOCK) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now >= routeExpiresAt) {
                Request health = new Request.Builder().url(RemoteRelayProfile.DEFAULT_ENDPOINT + "/v1/health").build();
                boolean direct = probeRelay(DIRECT_PROBE, health);
                useExistingLocalProxy = !direct && probeRelay(PROXY_PROBE, health);
                routeExpiresAt = now + (direct || useExistingLocalProxy ? 60_000L : 10_000L);
            }
            if (useExistingLocalProxy) return object ? LOCAL_PROXY_OBJECT : LOCAL_PROXY;
            return object ? OBJECT_HTTP : HTTP;
        }
    }

    private static boolean probeRelay(OkHttpClient client, Request health) {
        try (Response response = client.newCall(health).execute()) {
            return response.isSuccessful();
        } catch (java.io.IOException unavailable) {
            return false;
        }
    }

    private static Request.Builder relayRequest(String url, String token, String workspaceId) {
        Request.Builder request = new Request.Builder().url(url)
                .header("User-Agent", "DeviceShareHub-Android");
        if (workspaceId != null && !workspaceId.isEmpty()) request.header("X-Workspace-Id", workspaceId);
        if (token != null && !token.isEmpty()) request.header("Authorization", "Bearer " + token);
        return request;
    }

    public static final class Session {
        public final String endpoint;
        public final String workspaceId;
        public final String deviceId;
        public final String token;
        public final long expiresAtMs;

        Session(String endpoint, String workspaceId, String deviceId, String token, long expiresAtMs) {
            this.endpoint = endpoint;
            this.workspaceId = workspaceId;
            this.deviceId = deviceId;
            this.token = token;
            this.expiresAtMs = expiresAtMs;
        }

        public boolean isExpired() {
            return expiresAtMs > 0 && System.currentTimeMillis() >= expiresAtMs - 120_000L;
        }
    }

    private RemoteRelayClient() {}

    public static Session ensureSession(Context context) throws Exception {
        RemoteRelayProfile.Stored profile = RemoteRelayProfile.load(context);
        if (profile == null) {
            throw new IllegalStateException("设备尚未获取云端工作区凭证，请连接一次电脑 Wi-Fi 自动授权");
        }
        synchronized (SESSION_LOCK) {
            Session s = cachedSession;
            if (s != null && !s.isExpired()
                    && s.workspaceId.equals(profile.workspaceId)
                    && s.deviceId.equals(profile.deviceId)
                    && (s.endpoint.equals(profile.endpoint) || s.endpoint.equals(profile.backupEndpoint))) {
                return s;
            }
            boolean backupFirst = !profile.backupEndpoint.isEmpty()
                    && profile.endpoint.equals(unavailableEndpoint)
                    && android.os.SystemClock.elapsedRealtime() < unavailableUntil;
            String first = backupFirst ? profile.backupEndpoint : profile.endpoint;
            String second = backupFirst ? profile.endpoint : profile.backupEndpoint;
            cachedSession = authenticateWithFallback(first, second,
                    endpoint -> createSession(context, profile, endpoint));
            return cachedSession;
        }
    }

    interface SessionAuthenticator {
        Session authenticate(String endpoint) throws Exception;
    }

    static Session authenticateWithFallback(String first, String second, SessionAuthenticator authenticator)
            throws Exception {
        try {
            return authenticator.authenticate(first);
        } catch (Exception failure) {
            if (second.isEmpty() || !endpointUnavailable(failure)) throw failure;
            return authenticator.authenticate(second);
        }
    }

    private static Session createSession(Context context, RemoteRelayProfile.Stored profile, String endpoint)
            throws Exception {
            RemoteIdentity id = RemoteIdentity.loadOrCreate(context);
            JSONObject chReq = new JSONObject();
            chReq.put("workspaceId", profile.workspaceId);
            chReq.put("deviceId", profile.deviceId);
            JSONObject challenge = requestJson(
                    endpoint + "/v1/challenges",
                    "POST",
                    null,
                    profile.workspaceId,
                    chReq.toString());

            String chId = challenge.getString("challengeId");
            String chSig = RemoteProtocol.signEs256P1363(id.signingPrivateKey, challenge);

            JSONObject sesReq = new JSONObject();
            sesReq.put("certificate", profile.certificate);
            sesReq.put("certificateSignature", profile.certificateSignature);
            sesReq.put("challengeId", chId);
            sesReq.put("challengeSignature", chSig);
            JSONObject sesJson = requestJson(
                    endpoint + "/v1/sessions",
                    "POST",
                    null,
                    profile.workspaceId,
                    sesReq.toString());
            String token = sesJson.getString("token");
            long exp = sesJson.optLong("expiresAt", System.currentTimeMillis() + 3600_000L);
            return new Session(endpoint, profile.workspaceId, profile.deviceId, token, exp);
    }

    private static boolean endpointUnavailable(Exception failure) {
        return failure instanceof java.io.IOException
                || (failure instanceof HttpStatusException && ((HttpStatusException) failure).statusCode >= 500);
    }

    private static void markEndpointUnavailable(String token) {
        Session current = cachedSession;
        if (current == null || token == null || !token.equals(current.token)) return;
        synchronized (SESSION_LOCK) {
            if (cachedSession != current) return;
            unavailableUntil = android.os.SystemClock.elapsedRealtime() + 45_000L;
            unavailableEndpoint = current.endpoint;
            cachedSession = null;
        }
    }

    public static void invalidateSession() {
        cachedSession = null;
    }

    /** Calls PC LAN `/api/online/remote-enroll` silently so connected phones auto-receive ES256 workspace certs. */
    public static void enrollViaLanBestEffort(Context context, String pcBaseUrl) {
        if (pcBaseUrl == null || !pcBaseUrl.startsWith("http")) return;
        long now = System.currentTimeMillis();
        long prev = lastLanEnrollMs.get();
        if (RemoteRelayProfile.isEnrolled(context) && (now - prev) < ENROLL_COOLDOWN_MS) return;
        if (!lastLanEnrollMs.compareAndSet(prev, now)) return;

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                RemoteIdentity identity = RemoteIdentity.loadOrCreate(context);
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String deviceId = prefs.getString("deviceId", "").trim();
                if (deviceId.isEmpty()) return;
                String deviceName = prefs.getString("deviceName", Build.MANUFACTURER + " " + Build.MODEL).trim();

                JSONObject body = new JSONObject();
                body.put("deviceId", deviceId);
                body.put("deviceName", deviceName);
                body.put("signingPublicKey", identity.signingPublicJwk);
                body.put("agreementPublicKey", identity.signingPublicJwk);

                conn = (HttpURLConnection) new URL(pcBaseUrl + "/api/online/remote-enroll").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(12000);
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", "DeviceShareHub-Android/0.8.139");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(out.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String resp = readStreamText(conn.getInputStream());
                    JSONObject parsed = new JSONObject(resp);
                    if (parsed.optBoolean("ok", false)) {
                        RemoteRelayProfile.save(context, resp);
                        cachedSession = null;
                        DiagnosticLog.write(context, "remote_auto_enroll_ok",
                                parsed.optString("workspaceId", ""));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "LAN auto-enroll skipped: " + e.getMessage());
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }, "remote-lan-enroll").start();
    }

    public static void heartbeat(Session session, JSONObject inventory) throws Exception {
        JSONObject payload = new JSONObject();
        if (inventory != null) {
            payload.put("inventory", inventory);
        }
        requestJson(
                session.endpoint + "/v1/presence",
                "POST",
                session.token,
                session.workspaceId,
                payload.toString());
    }

    public static JSONArray listInbox(Session session) throws Exception {
        JSONObject res = requestJson(
                session.endpoint + "/v1/inbox",
                "GET",
                session.token,
                session.workspaceId,
                null);
        JSONArray arr = res.optJSONArray("transfers");
        return arr != null ? arr : new JSONArray();
    }

    public static void ackTransfer(Session session, String transferId) throws Exception {
        requestJson(
                session.endpoint + "/v1/transfers/" + transferId + "/ack",
                "POST",
                session.token,
                session.workspaceId,
                "{}");
    }

    public static JSONObject fetchStats(Session session) throws Exception {
        return requestJson(
                session.endpoint + "/v1/stats",
                "GET",
                session.token,
                session.workspaceId,
                null);
    }

    public static JSONObject fetchCatalogManifest(Session session) throws Exception {
        return requestJson(
                session.endpoint + "/v1/catalog",
                "GET",
                session.token,
                session.workspaceId,
                null);
    }

    public static byte[] downloadCatalogThumb(Session session, String thumbKey) throws Exception {
        return downloadSmallBytes(
                session.endpoint + "/v1/catalog/thumbs/" + thumbKey,
                session.token,
                session.workspaceId,
                768 * 1024);
    }

    public static void recordCatalogWorkUsed(Session session, String workId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("workId", workId);
        requestJson(
                session.endpoint + "/v1/catalog/use-work",
                "POST",
                session.token,
                session.workspaceId,
                body.toString());
    }

    /**
     * Pulls an untouched HD original from the PC catalog on-demand via R2,
     * with Range resume, SHA-256 verification, and immediate ACK cleanup.
     */
    public static void pullCatalogOriginal(Session session, String workId, String fileName, File destFile) throws Exception {
        JSONObject body = new JSONObject();
        body.put("workId", workId);
        body.put("fileName", fileName);
        JSONObject created = requestJson(
                session.endpoint + "/v1/catalog/pull",
                "POST",
                session.token,
                session.workspaceId,
                body.toString());
        JSONObject pull = created.getJSONObject("pull");
        String pullId = pull.getString("pullId");
        String fileUrl = session.endpoint + "/v1/catalog/pulls/" + pullId + "/file";

        // Poll up to 45s for PC bridge to upload original into R2
        long deadline = System.currentTimeMillis() + 45_000L;
        Exception lastErr = null;
        boolean downloaded = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                downloadWithResumeAndVerify(
                        fileUrl,
                        session.token,
                        session.workspaceId,
                        -1L,
                        null,
                        destFile);
                downloaded = true;
                break;
            } catch (HttpStatusException hse) {
                lastErr = hse;
                if (hse.statusCode == 404 || hse.statusCode == 409) {
                    Thread.sleep(650L);
                    continue;
                }
                throw hse;
            }
        }
        if (!downloaded) {
            throw lastErr != null ? lastErr : new IllegalStateException("等待电脑上传原图超时（电脑可能不在线）");
        }
        // Immediately ACK so R2 deletes temporary original blob cleanly
        requestJson(
                session.endpoint + "/v1/catalog/pulls/" + pullId + "/ack",
                "POST",
                session.token,
                session.workspaceId,
                "{}");
    }

    public static void downloadTransferObject(Session session, String transferId, int index,
                                              long expectedBytes, String expectedSha256,
                                              File destFile) throws Exception {
        String url = session.endpoint + "/v1/transfers/" + transferId + "/objects/" + index;
        downloadWithResumeAndVerify(url, session.token, session.workspaceId, expectedBytes, expectedSha256, destFile);
    }

    public static void downloadWithResumeAndVerify(String urlStr, String token, String workspaceId,
                                                   long expectedBytes, String expectedSha256,
                                                   File destFile) throws Exception {
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File partFile = new File(parent, destFile.getName() + ".part");

        for (int attempt = 1; attempt <= 3; attempt++) {
            long existingLen = partFile.isFile() ? partFile.length() : 0L;
            if (expectedBytes > 0 && existingLen > expectedBytes) {
                partFile.delete();
                existingLen = 0L;
            }
            Request.Builder request = relayRequest(urlStr, token, workspaceId);
            if (existingLen > 0) request.header("Range", "bytes=" + existingLen + "-");
            Request call = request.build();
            try (Response response = relayHttp(call, true).newCall(call).execute()) {
                int code = response.code();
                if (code == 416) {
                    partFile.delete();
                    continue;
                }
                if (code != 200 && code != 206) {
                    throw new HttpStatusException(code, "HTTP " + code);
                }
                String headerSha = response.header("X-Object-Sha256");
                String wantSha = (expectedSha256 != null && !expectedSha256.isEmpty())
                        ? expectedSha256
                        : (headerSha != null ? headerSha.trim() : "");
                boolean append = (code == 206 && existingLen > 0);
                try (InputStream in = new BufferedInputStream(response.body().byteStream());
                     FileOutputStream out = new FileOutputStream(partFile, append)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                }
                if (expectedBytes > 0 && partFile.length() != expectedBytes) {
                    if (attempt < 3) continue;
                    partFile.delete();
                    throw new IllegalStateException("远程文件大小校验失败");
                }
                if (!wantSha.isEmpty()) {
                    String actualSha = RemoteProtocol.sha256FileHex(partFile);
                    if (!actualSha.equalsIgnoreCase(wantSha)) {
                        partFile.delete();
                        throw new IllegalStateException("远程文件 SHA-256 校验失败");
                    }
                }
                if (destFile.exists()) destFile.delete();
                if (!partFile.renameTo(destFile)) {
                    throw new IllegalStateException("保存下载文件失败");
                }
                return;
            } catch (HttpStatusException hse) {
                throw hse;
            } catch (Exception e) {
                if (attempt >= 3) throw e;
                Thread.sleep(600L * attempt);
            }
        }
    }

    private static byte[] downloadSmallBytes(String urlStr, String token, String workspaceId, int maxBytes) throws Exception {
        Request request = relayRequest(urlStr, token, workspaceId).build();
        try (Response response = relayHttp(request, false).newCall(request).execute()) {
            int code = response.code();
            if (code < 200 || code >= 300) {
                throw new HttpStatusException(code, "HTTP " + code);
            }
            try (InputStream in = new BufferedInputStream(response.body().byteStream());
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new IllegalStateException("响应超出大小限制");
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        }
    }

    static JSONObject p2pRequest(Session session, String suffix, JSONObject body) throws Exception {
        return requestJson(session.endpoint + "/v1/p2p/sessions" + suffix,
                body == null ? "GET" : "POST", session.token, session.workspaceId,
                body == null ? null : body.toString());
    }

    private static JSONObject requestJson(String urlStr, String method, String token,
                                          String workspaceId, String jsonBody) throws Exception {
        Request.Builder request = relayRequest(urlStr, token, workspaceId).header("Accept", "application/json");
        RequestBody body = jsonBody == null ? null : RequestBody.create(jsonBody, JSON);
        Request call = request.method(method, body).build();
        okhttp3.Call exchange = relayHttp(call, false).newCall(call);
        if (token == null) exchange.timeout().timeout(8, java.util.concurrent.TimeUnit.SECONDS);
        try (Response response = exchange.execute()) {
            int code = response.code();
            String respText = response.body() == null ? "{}" : response.body().string();
            if (code == 401) {
                cachedSession = null;
            }
            if (code < 200 || code >= 300) {
                if (code >= 500) markEndpointUnavailable(token);
                String msg = respText;
                try {
                    msg = new JSONObject(respText).optString("message", msg);
                } catch (Exception ignored) {}
                throw new HttpStatusException(code, msg);
            }
            if (respText.trim().isEmpty()) return new JSONObject();
            return new JSONObject(respText);
        } catch (java.io.IOException failure) {
            markEndpointUnavailable(token);
            throw failure;
        }
    }

    private static String readStreamText(InputStream in) throws Exception {
        try (BufferedInputStream bis = new BufferedInputStream(in);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = bis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static final class HttpStatusException extends Exception {
        public final int statusCode;

        public HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
