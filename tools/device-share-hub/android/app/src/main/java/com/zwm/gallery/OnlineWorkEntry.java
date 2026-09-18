package com.zwm.gallery;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a work item residing on the PC workspace (Online Mode).
 */
public final class OnlineWorkEntry {
    public final String id;
    public final String title;
    public final String destination;
    public final String stage;
    public final int useCount;
    public final int maxUses;
    public final boolean used;
    public final int remainingUses;
    public final String statusLabel;
    public final List<String> images;
    public final int imageCount;
    public final String copyText;
    public final boolean hasCopyText;
    public final List<String> dispatchedTo;
    public final long updatedAt;

    public OnlineWorkEntry(String id, String title, String destination, String stage,
                           int useCount, int maxUses, boolean used, int remainingUses,
                           String statusLabel, List<String> images, int imageCount,
                           String copyText, boolean hasCopyText, List<String> dispatchedTo,
                           long updatedAt) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.destination = destination == null ? "其他" : destination;
        this.stage = stage == null ? "" : stage;
        this.useCount = useCount;
        this.maxUses = maxUses <= 0 ? 2 : maxUses;
        this.used = used || useCount > 0;
        this.remainingUses = Math.max(0, remainingUses);
        this.statusLabel = statusLabel == null ? "" : statusLabel;
        this.images = images == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(images));
        this.imageCount = imageCount > 0 ? imageCount : this.images.size();
        this.copyText = copyText == null ? "" : copyText;
        this.hasCopyText = hasCopyText || !this.copyText.trim().isEmpty();
        this.dispatchedTo = dispatchedTo == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(dispatchedTo));
        this.updatedAt = updatedAt;
    }

    public static OnlineWorkEntry fromJson(JSONObject json) {
        if (json == null) return null;
        String id = json.optString("id", "");
        String title = json.optString("title", id);
        String destination = json.optString("destination", "其他");
        String stage = json.optString("stage", "");
        int useCount = json.optInt("useCount", 0);
        int maxUses = json.optInt("maxUses", 2);
        boolean used = json.optBoolean("used", useCount > 0);
        int remainingUses = json.optInt("remainingUses", Math.max(0, maxUses - useCount));
        String statusLabel = json.optString("statusLabel", "");

        List<String> images = new ArrayList<>();
        JSONArray imgArr = json.optJSONArray("images");
        if (imgArr != null) {
            for (int i = 0; i < imgArr.length(); i++) {
                String img = imgArr.optString(i, "").trim();
                if (!img.isEmpty()) images.add(img);
            }
        }
        int imageCount = json.optInt("imageCount", images.size());
        String copyText = json.optString("copyText", "");
        boolean hasCopyText = json.optBoolean("hasCopyText", !copyText.trim().isEmpty());

        List<String> dispatchedTo = new ArrayList<>();
        JSONArray dispArr = json.optJSONArray("dispatchedTo");
        if (dispArr != null) {
            for (int i = 0; i < dispArr.length(); i++) {
                String d = dispArr.optString(i, "").trim();
                if (!d.isEmpty()) dispatchedTo.add(d);
            }
        }
        long updatedAt = json.optLong("updatedAt", System.currentTimeMillis());

        return new OnlineWorkEntry(id, title, destination, stage, useCount, maxUses,
                used, remainingUses, statusLabel, images, imageCount, copyText,
                hasCopyText, dispatchedTo, updatedAt);
    }
}
