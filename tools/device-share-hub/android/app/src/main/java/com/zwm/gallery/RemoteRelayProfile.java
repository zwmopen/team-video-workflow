package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Persists enrolled Cloudflare workspace certificate profile & selectable cross-network TransportStrategy.
 */
public final class RemoteRelayProfile {
    private static final String PREFS = "album_prefs";
    private static final String KEY_PROFILE_JSON = "remoteRelayProfileJson";
    private static final String KEY_STRATEGY = "transportStrategy";
    public static final String DEFAULT_ENDPOINT = "https://zwm-device-share-relay.zwmrpg.workers.dev";

    public enum TransportStrategy {
        AUTO("auto", "自动选择（推荐）", "按网络情况自动选择连接方式"),
        USB_FIRST("usb_first", "数据线优先", "优先用数据线，无法连接时自动换其他方式"),
        LAN_FIRST("lan_first", "同一 Wi-Fi 优先", "在同一 Wi-Fi 下优先直接传输"),
        P2P_FIRST("p2p_first", "设备直连优先", "不同网络时优先直接传输，失败后自动通过云端转发"),
        R2_FIRST("r2_first", "云端转发优先", "不同网络下优先通过云端转发");

        public final String id;
        public final String title;
        public final String detail;

        TransportStrategy(String id, String title, String detail) {
            this.id = id;
            this.title = title;
            this.detail = detail;
        }

        public static TransportStrategy fromId(String id) {
            if (id != null) {
                for (TransportStrategy s : values()) {
                    if (s.id.equalsIgnoreCase(id.trim())) return s;
                }
            }
            return AUTO;
        }
    }

    public static final class Stored {
        public final String endpoint;
        public final String workspaceId;
        public final String deviceId;
        public final JSONObject certificate;
        public final String certificateSignature;

        Stored(String endpoint, String workspaceId, String deviceId,
               JSONObject certificate, String certificateSignature) {
            this.endpoint = endpoint;
            this.workspaceId = workspaceId;
            this.deviceId = deviceId;
            this.certificate = certificate;
            this.certificateSignature = certificateSignature;
        }
    }

    private RemoteRelayProfile() {}

    public static boolean isEnrolled(Context context) {
        return load(context) != null;
    }

    public static synchronized void save(Context context, String rawJson) throws Exception {
        JSONObject obj = new JSONObject(rawJson);
        String endpoint = normalizeEndpoint(obj.optString("endpoint", DEFAULT_ENDPOINT));
        JSONObject cert = obj.getJSONObject("certificate");
        String sig = obj.getString("certificateSignature").trim();
        String wsId = cert.getString("workspaceId").trim();
        String devId = cert.getString("deviceId").trim();
        if (wsId.isEmpty() || devId.isEmpty() || sig.isEmpty()) {
            throw new IllegalArgumentException("远程证书字段不完整");
        }
        JSONObject normalized = new JSONObject();
        normalized.put("endpoint", endpoint);
        normalized.put("certificate", cert);
        normalized.put("certificateSignature", sig);
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROFILE_JSON, normalized.toString())
                .apply();
    }

    public static synchronized Stored load(Context context) {
        try {
            String raw = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PROFILE_JSON, "");
            if (raw == null || raw.isEmpty()) return null;
            JSONObject obj = new JSONObject(raw);
            String endpoint = normalizeEndpoint(obj.optString("endpoint", DEFAULT_ENDPOINT));
            JSONObject cert = obj.getJSONObject("certificate");
            String sig = obj.getString("certificateSignature").trim();
            String wsId = cert.getString("workspaceId").trim();
            String devId = cert.getString("deviceId").trim();
            if (wsId.isEmpty() || devId.isEmpty() || sig.isEmpty()) return null;
            return new Stored(endpoint, wsId, devId, cert, sig);
        } catch (Exception e) {
            return null;
        }
    }

    public static TransportStrategy getStrategy(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return TransportStrategy.fromId(prefs.getString(KEY_STRATEGY, TransportStrategy.AUTO.id));
    }

    public static void setStrategy(Context context, TransportStrategy strategy) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STRATEGY, strategy == null ? TransportStrategy.AUTO.id : strategy.id)
                .apply();
    }

    public static String normalizeEndpoint(String raw) {
        String s = raw == null ? "" : raw.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("https://")) {
            throw new IllegalArgumentException("远程中继地址必须使用 HTTPS");
        }
        return s;
    }
}
