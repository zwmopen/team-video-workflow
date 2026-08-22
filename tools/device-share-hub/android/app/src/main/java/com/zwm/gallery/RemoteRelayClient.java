package com.zwm.gallery;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** HTTPS control-plane client for relay login, presence and remote task inbox polling. */
final class RemoteRelayClient {
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int OBJECT_READ_TIMEOUT_MS = 120_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private RemoteRelayClient() { }

    static Session createSession(Context context, String endpoint, JSONObject certificate,
                                 String certificateSignature) throws Exception {
        String base = normalizeEndpoint(endpoint);
        String workspaceId = certificate.getString("workspaceId");
        String deviceId = certificate.getString("deviceId");
        JSONObject challenge = requestJson(base + "/v1/challenges", "POST", null,
                new JSONObject().put("workspaceId", workspaceId).put("deviceId", deviceId), workspaceId);
        JSONObject body = new JSONObject()
                .put("certificate", certificate)
                .put("certificateSignature", certificateSignature)
                .put("challengeId", challenge.getString("challengeId"))
                .put("challengeSignature", RemoteIdentity.sign(context, challenge));
        JSONObject response = requestJson(base + "/v1/sessions", "POST", null, body, workspaceId);
        return new Session(base, response.getString("token"),
                response.optLong("expiresAt", 0L), workspaceId, deviceId);
    }

    static void heartbeat(Session session) throws Exception {
        heartbeat(session, new JSONObject());
    }

    static void heartbeat(Session session, JSONObject inventory) throws Exception {
        requestJson(session.endpoint + "/v1/presence", "POST", session.token,
                inventory == null ? new JSONObject() : inventory, session.workspaceId);
    }

    static JSONArray inbox(Session session) throws Exception {
        JSONArray transfers = requestJson(session.endpoint + "/v1/inbox", "GET", session.token, null,
                        session.workspaceId)
                .optJSONArray("transfers");
        return transfers == null ? new JSONArray() : transfers;
    }

    /** Creates the WebRTC signaling session; file bytes never go through these calls. */
    static JSONObject createP2PSession(Session session, String recipientDeviceId,
                                        String transferId) throws Exception {
        JSONObject body = new JSONObject()
                .put("recipientDeviceId", safeId(recipientDeviceId))
                .put("protocol", "webrtc-datachannel-v1");
        if (transferId != null && !transferId.isEmpty()) body.put("transferId", safeId(transferId));
        return requestJson(session.endpoint + "/v1/p2p/sessions", "POST", session.token,
                body, session.workspaceId).getJSONObject("p2p");
    }

    static JSONObject p2pSession(Session session, String sessionId) throws Exception {
        return requestJson(session.endpoint + "/v1/p2p/sessions/" + safeId(sessionId), "GET",
                session.token, null, session.workspaceId).getJSONObject("p2p");
    }

    static JSONArray p2pSessions(Session session) throws Exception {
        JSONArray sessions = requestJson(session.endpoint + "/v1/p2p/sessions", "GET",
                session.token, null, session.workspaceId).optJSONArray("sessions");
        return sessions == null ? new JSONArray() : sessions;
    }

    static JSONObject sendP2PSignal(Session session, String sessionId, String type,
                                     JSONObject data) throws Exception {
        if (type == null || type.isEmpty() || data == null) throw new IOException("直连信令无效");
        return requestJson(session.endpoint + "/v1/p2p/sessions/" + safeId(sessionId) + "/signals",
                "POST", session.token, new JSONObject().put("type", type).put("data", data),
                session.workspaceId);
    }

    static void closeP2PSession(Session session, String sessionId) throws Exception {
        requestJson(session.endpoint + "/v1/p2p/sessions/" + safeId(sessionId) + "/close",
                "POST", session.token, new JSONObject(), session.workspaceId);
    }

    static JSONObject transfer(Session session, String transferId) throws Exception {
        return requestJson(session.endpoint + "/v1/transfers/" + safeId(transferId), "GET",
                session.token, null, session.workspaceId).getJSONObject("transfer");
    }

    /** Streams one ordinary public object to a temporary file and verifies it before rename. */
    static void downloadObject(Session session, String transferId, int index, File destination,
                               long expectedBytes, String expectedSha256) throws Exception {
        if (index < 0 || expectedBytes <= 0L || expectedSha256 == null
                || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("远程对象校验参数无效");
        }
        String path = session.endpoint + "/v1/transfers/" + safeId(transferId)
                + "/objects/" + index;
        HttpURLConnection connection = (HttpURLConnection) new URL(path).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(OBJECT_READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("Authorization", "Bearer " + session.token);
        connection.setRequestProperty("X-Workspace-Id", session.workspaceId);
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            connection.disconnect();
            throw new IOException("无法创建远程接收缓存目录");
        }
        File temporary = new File(destination.getPath() + ".part");
        if (temporary.exists() && !temporary.delete()) {
            connection.disconnect();
            throw new IOException("无法清理未完成的远程文件");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long received = 0L;
        boolean moved = false;
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream error = connection.getErrorStream();
                String detail = error == null ? "" : new String(readLimited(error), StandardCharsets.UTF_8);
                throw new IOException(detail.trim().isEmpty()
                        ? "远程文件下载失败 " + status : detail.trim());
            }
            try (InputStream input = new java.io.BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[128 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    received += count;
                    if (received > expectedBytes) throw new IOException("远程文件大小超出声明");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            String actual = hex(digest.digest());
            if (received != expectedBytes || !actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("远程文件 SHA-256 校验失败");
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException("无法替换旧的远程文件");
            }
            if (!temporary.renameTo(destination)) throw new IOException("无法保存远程文件");
            moved = true;
        } finally {
            connection.disconnect();
            if (!moved && temporary.exists()) temporary.delete();
        }
    }

    static void ack(Session session, String transferId) throws Exception {
        requestJson(session.endpoint + "/v1/transfers/" + safeId(transferId) + "/ack",
                "POST", session.token, new JSONObject(), session.workspaceId);
    }

    static String normalizeEndpoint(String endpoint) throws IOException {
        if (endpoint == null) throw new IOException("远程服务地址为空");
        String value = endpoint.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (!value.startsWith("https://") || value.length() <= "https://".length()) {
            throw new IOException("远程服务必须使用 HTTPS");
        }
        try {
            URL url = new URL(value);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                    || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
                    || (url.getPath() != null && !url.getPath().isEmpty()
                    && !"/".equals(url.getPath()))) {
                throw new IOException("远程服务地址无效");
            }
        } catch (IOException error) {
            throw new IOException("远程服务地址无效", error);
        }
        return value;
    }

    private static JSONObject requestJson(String url, String method, String token,
                                          JSONObject body, String workspaceId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (workspaceId != null && !workspaceId.isEmpty()) {
            connection.setRequestProperty("X-Workspace-Id", workspaceId);
        }
        if (body != null) {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = stream == null ? "" : new String(readLimited(stream), StandardCharsets.UTF_8);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            String detail = response.trim();
            throw new IOException(detail.isEmpty() ? "远程服务返回错误 " + status : detail);
        }
        try {
            return response.isEmpty() ? new JSONObject() : new JSONObject(response);
        } catch (JSONException error) {
            throw new IOException("远程服务响应格式无效", error);
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("远程服务响应过大");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static String safeId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IOException("远程任务标识无效");
        }
        return value;
    }

    static final class Session {
        final String endpoint;
        final String token;
        final long expiresAt;
        final String workspaceId;
        final String deviceId;

        Session(String endpoint, String token, long expiresAt, String workspaceId, String deviceId) {
            this.endpoint = endpoint;
            this.token = token;
            this.expiresAt = expiresAt;
            this.workspaceId = workspaceId;
            this.deviceId = deviceId;
        }

        boolean expired(long nowMs) { return expiresAt > 0 && expiresAt <= nowMs; }
    }
}
