package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Public relay enrollment data; private keys stay in Android Keystore and sessions stay in memory. */
final class RemoteRelayProfile {
    private static final String PREFS = "remote_relay_profile_v1";
    private static final String PROFILE = "profile";

    private RemoteRelayProfile() { }

    static void save(Context context, String endpoint, JSONObject certificate,
                     String certificateSignature) throws Exception {
        String normalized = RemoteRelayClient.normalizeEndpoint(endpoint);
        validateCertificate(certificate);
        if (certificateSignature == null || certificateSignature.trim().isEmpty()) {
            throw new IllegalArgumentException("远程设备凭证签名为空");
        }
        JSONObject profile = new JSONObject()
                .put("endpoint", normalized)
                .put("certificate", certificate)
                .put("certificateSignature", certificateSignature.trim());
        prefs(context).edit().putString(PROFILE, profile.toString()).apply();
    }

    static Profile load(Context context) {
        String raw = prefs(context).getString(PROFILE, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject profile = new JSONObject(raw);
            String endpoint = profile.getString("endpoint");
            JSONObject certificate = profile.getJSONObject("certificate");
            String signature = profile.getString("certificateSignature");
            RemoteRelayClient.normalizeEndpoint(endpoint);
            validateCertificate(certificate);
            if (signature.trim().isEmpty()) return null;
            return new Profile(endpoint, certificate, signature);
        } catch (Exception ignored) {
            return null;
        }
    }

    static void clear(Context context) {
        prefs(context).edit().remove(PROFILE).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void validateCertificate(JSONObject certificate) throws Exception {
        if (certificate == null) throw new IllegalArgumentException("远程设备凭证为空");
        String workspaceId = certificate.getString("workspaceId");
        String deviceId = certificate.getString("deviceId");
        if (workspaceId.trim().isEmpty() || deviceId.trim().isEmpty()) {
            throw new IllegalArgumentException("远程设备凭证身份不完整");
        }
        if (!certificate.has("signingPublicKey") || !certificate.has("agreementPublicKey")) {
            throw new IllegalArgumentException("远程设备凭证缺少公钥");
        }
    }

    static final class Profile {
        final String endpoint;
        final JSONObject certificate;
        final String certificateSignature;

        Profile(String endpoint, JSONObject certificate, String certificateSignature) {
            this.endpoint = endpoint;
            this.certificate = certificate;
            this.certificateSignature = certificateSignature;
        }
    }
}
