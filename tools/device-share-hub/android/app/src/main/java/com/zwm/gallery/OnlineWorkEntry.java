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
    /** 在线回收站专用：是否已被标记为垃圾样本 */
    public final boolean garbage;
    /** 在线回收站专用：人工垃圾备注（来自 quality_tag.json / manifest.json） */
    public final String garbageRemark;
    /** 电脑端作品文件夹的绝对路径（用于「复制路径」按钮） */
    public final String path;
    /** DSH-135: 首次分享时间戳（毫秒） */
    public final long firstSharedAtMs;
    /** DSH-135: 移入回收站倒计时过期时间戳（毫秒） */
    public final long expireAtMs;
    /** DSH-135: 首发设备名称 */
    public final String originDevice;
    /** DSH-137: 已分发的版本标签列表（如 xhs_grass、douyin 等） */
    public final List<String> dispatchedVersions;

    public OnlineWorkEntry(String id, String title, String destination, String stage,
                           int useCount, int maxUses, boolean used, int remainingUses,
                           String statusLabel, List<String> images, int imageCount,
                           String copyText, boolean hasCopyText, List<String> dispatchedTo,
                           long updatedAt) {
        this(id, title, destination, stage, useCount, maxUses, used, remainingUses,
                statusLabel, images, imageCount, copyText, hasCopyText, dispatchedTo,
                updatedAt, false, "", "", 0L, 0L, "", Collections.emptyList());
    }

    public OnlineWorkEntry(String id, String title, String destination, String stage,
                           int useCount, int maxUses, boolean used, int remainingUses,
                           String statusLabel, List<String> images, int imageCount,
                           String copyText, boolean hasCopyText, List<String> dispatchedTo,
                           long updatedAt, boolean garbage, String garbageRemark) {
        this(id, title, destination, stage, useCount, maxUses, used, remainingUses,
                statusLabel, images, imageCount, copyText, hasCopyText, dispatchedTo,
                updatedAt, garbage, garbageRemark, "", 0L, 0L, "", Collections.emptyList());
    }

    public OnlineWorkEntry(String id, String title, String destination, String stage,
                           int useCount, int maxUses, boolean used, int remainingUses,
                           String statusLabel, List<String> images, int imageCount,
                           String copyText, boolean hasCopyText, List<String> dispatchedTo,
                           long updatedAt, boolean garbage, String garbageRemark, String path) {
        this(id, title, destination, stage, useCount, maxUses, used, remainingUses,
                statusLabel, images, imageCount, copyText, hasCopyText, dispatchedTo,
                updatedAt, garbage, garbageRemark, path, 0L, 0L, "", Collections.emptyList());
    }

    public OnlineWorkEntry(String id, String title, String destination, String stage,
                           int useCount, int maxUses, boolean used, int remainingUses,
                           String statusLabel, List<String> images, int imageCount,
                           String copyText, boolean hasCopyText, List<String> dispatchedTo,
                           long updatedAt, boolean garbage, String garbageRemark, String path,
                           long firstSharedAtMs, long expireAtMs, String originDevice, List<String> dispatchedVersions) {
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
        this.garbage = garbage;
        this.garbageRemark = garbageRemark == null ? "" : garbageRemark;
        this.path = path == null ? "" : path;
        this.firstSharedAtMs = firstSharedAtMs;
        this.expireAtMs = expireAtMs;
        this.originDevice = originDevice == null ? "" : originDevice;
        this.dispatchedVersions = dispatchedVersions == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(dispatchedVersions));
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

        // 在线回收站接口会在每套作品上挂一个 garbage 对象：
        // {"marked": bool, "remark": str, "markedBy": str, "markedAt": str}
        boolean garbage = false;
        String garbageRemark = "";
        JSONObject garbageObj = json.optJSONObject("garbage");
        if (garbageObj != null) {
            garbage = garbageObj.optBoolean("marked", false);
            garbageRemark = garbageObj.optString("remark", "").trim();
        }

        long firstSharedAtMs = json.optLong("firstSharedAtMs", 0L);
        long expireAtMs = json.optLong("expireAtMs", 0L);
        String originDevice = json.optString("originDevice", "");
        List<String> dispatchedVersions = new ArrayList<>();
        JSONArray versArr = json.optJSONArray("dispatchedVersions");
        if (versArr != null) {
            for (int i = 0; i < versArr.length(); i++) {
                String v = versArr.optString(i, "").trim();
                if (!v.isEmpty()) dispatchedVersions.add(v);
            }
        }

        return new OnlineWorkEntry(id, title, destination, stage, useCount, maxUses,
                used, remainingUses, statusLabel, images, imageCount, copyText,
                hasCopyText, dispatchedTo, updatedAt, garbage, garbageRemark,
                json.optString("path", ""), firstSharedAtMs, expireAtMs, originDevice, dispatchedVersions);
    }
}
