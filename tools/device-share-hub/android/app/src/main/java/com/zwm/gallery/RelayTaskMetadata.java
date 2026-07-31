package com.zwm.gallery;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class RelayTaskMetadata {
    static final long MAX_LIFETIME_MS = TimeUnit.HOURS.toMillis(1);
    static final int DEFAULT_HOPS = 4;

    final String messageId;
    final String originId;
    final String destinationId;
    final String previousHopId;
    final String contentKind;
    final long expiresAt;
    final int hopLimit;

    RelayTaskMetadata(String messageId, String originId, String destinationId,
                      String previousHopId, String contentKind, long expiresAt,
                      int hopLimit) {
        this.messageId = safeId(messageId, "msg-" + UUID.randomUUID());
        this.originId = safeId(originId, "");
        this.destinationId = safeId(destinationId, "");
        this.previousHopId = safeId(previousHopId, "");
        this.contentKind = "screenshot".equals(contentKind) ? "screenshot" : "file";
        long now = System.currentTimeMillis();
        this.expiresAt = Math.min(expiresAt <= 0 ? now + MAX_LIFETIME_MS : expiresAt,
                now + MAX_LIFETIME_MS);
        this.hopLimit = Math.max(0, Math.min(8, hopLimit));
    }

    static RelayTaskMetadata screenshot(String ownId, String destinationId) {
        return new RelayTaskMetadata("shot-" + UUID.randomUUID(), ownId, destinationId,
                ownId, "screenshot", System.currentTimeMillis() + MAX_LIFETIME_MS,
                DEFAULT_HOPS);
    }

    static RelayTaskMetadata fromJson(JSONObject body) {
        String destination = body.optString("destinationId", "");
        if (destination.isEmpty()) return null;
        return new RelayTaskMetadata(
                body.optString("messageId", ""),
                body.optString("originId", ""),
                destination,
                body.optString("previousHopId", body.optString("senderId", "")),
                body.optString("contentKind", "file"),
                body.optLong("expiresAt", 0L),
                body.optInt("hopLimit", DEFAULT_HOPS));
    }

    RelayTaskMetadata forwardedBy(String ownId) {
        return new RelayTaskMetadata(messageId, originId, destinationId, ownId,
                contentKind, expiresAt, hopLimit - 1);
    }

    void addTo(JSONObject body, String senderId) throws Exception {
        body.put("messageId", messageId)
                .put("originId", originId)
                .put("destinationId", destinationId)
                .put("previousHopId", previousHopId)
                .put("senderId", senderId)
                .put("contentKind", contentKind)
                .put("expiresAt", expiresAt)
                .put("hopLimit", hopLimit);
    }

    private static String safeId(String value, String fallback) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("[A-Za-z0-9._-]{6,160}") ? clean : fallback;
    }
}
