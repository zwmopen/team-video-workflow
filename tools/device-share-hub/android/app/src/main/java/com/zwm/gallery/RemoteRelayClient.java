package com.zwm.gallery;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTPS control-plane client for relay login, presence and remote task inbox polling. */
final class RemoteRelayClient {
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private RemoteRelayClient() { }

    static Session createSession(Context context, String endpoint, JSONObject certificate,
                                 String certificateSignature) throws Exception {
        String base = normalizeEndpoint(endpoint);
        String workspaceId = certificate.getString("workspaceId");
        String deviceId = certificate.getString("deviceId");
        JSONObject challenge = requestJson(base + "/v1/challenges", "POST", null,
                new JSONObject().put("workspaceId", workspaceId).put("deviceId", deviceId));
        JSONObject body = new JSONObject()
                .put("certificate", certificate)
                .put("certificateSignature", certificateSignature)
                .put("challengeId", challenge.getString("challengeId"))
                .put("challengeSignature", RemoteIdentity.sign(context, challenge));
        JSONObject response = requestJson(base + "/v1/sessions", "POST", null, body);
        return new Session(base, response.getString("token"),
                response.optLong("expiresAt", 0L), deviceId);
    }

    static void heartbeat(Session session) throws Exception {
        requestJson(session.endpoint + "/v1/presence", "POST", session.token, new JSONObject());
    }

    static JSONArray inbox(Session session) throws Exception {
        JSONArray transfers = requestJson(session.endpoint + "/v1/inbox", "GET", session.token, null)
                .optJSONArray("transfers");
        return transfers == null ? new JSONArray() : transfers;
    }

    static JSONObject transfer(Session session, String transferId) throws Exception {
        return requestJson(session.endpoint + "/v1/transfers/" + safeId(transferId), "GET",
                session.token, null).getJSONObject("transfer");
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
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()) {
                throw new IOException("远程服务地址无效");
            }
        } catch (IOException error) {
            throw new IOException("远程服务地址无效", error);
        }
        return value;
    }

    private static JSONObject requestJson(String url, String method, String token,
                                          JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
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
        final String deviceId;

        Session(String endpoint, String token, long expiresAt, String deviceId) {
            this.endpoint = endpoint;
            this.token = token;
            this.expiresAt = expiresAt;
            this.deviceId = deviceId;
        }

        boolean expired(long nowMs) { return expiresAt > 0 && expiresAt <= nowMs; }
    }
}
