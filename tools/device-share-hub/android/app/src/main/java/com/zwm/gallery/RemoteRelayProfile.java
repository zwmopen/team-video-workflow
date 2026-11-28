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
        AUTO("auto", "自动选择（推荐）", "USB -> 局域网 -> 远程中继/在线相册"),
        USB_FIRST("usb_first", "USB 优先", "适合插线传输，失败自动切换 WiFi/云端"),
        LAN_FIRST("lan_first", "局域网优先", "优先走同一 WiFi 局域网高速通道"),
        P2P_FIRST("p2p_first", "P2P 优先（失败切 R2）", "跨网优先尝试 P2P 直连，不通自动切 R2"),
        R2_FIRST("r2_first", "R2 云端优先", "适合跨网/P2P 不稳定环境，直接走 Cloudflare R2");

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
        public final String backupEndpoint;
        public final String workspaceId;
        public final String deviceId;
        public final JSONObject certificate;
        public final String certificateSignature;

        Stored(String endpoint, String backupEndpoint, String workspaceId, String deviceId,
               JSONObject certificate, String certificateSignature) {
            this.endpoint = endpoint;
            this.backupEndpoint = backupEndpoint;
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
        if (!backupEndpoint(obj, endpoint, wsId).isEmpty()) {
            normalized.put("relayRoutes", obj.getJSONObject("relayRoutes"));
            normalized.put("relayRoutesSignature", obj.getString("relayRoutesSignature"));
            normalized.put("relayRoutesIssuerKey", obj.getJSONObject("relayRoutesIssuerKey"));
        }
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
            return new Stored(endpoint, backupEndpoint(obj, endpoint, wsId), wsId, devId, cert, sig);
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
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(s);
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || !"/".equals(url.encodedPath()) || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("远程中继地址必须使用 HTTPS");
        }
        return url.toString().substring(0, url.toString().length() - 1);
    }

    static String backupEndpoint(JSONObject profile, String primary, String workspaceId) {
        try {
            JSONObject routes = profile.getJSONObject("relayRoutes");
            JSONObject issuer = profile.getJSONObject("relayRoutesIssuerKey");
            if (!"EC".equals(issuer.getString("kty")) || !"P-256".equals(issuer.getString("crv"))
                    || !workspaceId.equals("ws_" + RemoteProtocol.sha256Hex(
                            RemoteProtocol.canonicalBytes(issuer)).substring(0, 32))
                    || !workspaceId.equals(routes.getString("workspaceId"))
                    || !primary.equals(normalizeEndpoint("https://" + routes.getString("primaryAuthority")))
                    || !RemoteProtocol.verifyEs256P1363(issuer.getString("x"), issuer.getString("y"),
                            routes, profile.getString("relayRoutesSignature"))) return "";
            String backup = normalizeEndpoint("https://" + routes.getString("backupAuthority"));
            return backup.equals(primary) ? "" : backup;
        } catch (Exception invalid) {
            return "";
        }
    }
}
