package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Comparator;
import java.util.List;

/**
 * 负责在线作品在手机端的生命周期管理（完全遵循手机端设置的时间规则）：
 * 1. 首次点击分享并使用时记录 firstSharedAtMs；
 * 2. 达到手机设置的 moveAfterMs（例如 1 小时）后，自动从在线活跃列表移入手机本地回收站；
 * 3. 达到手机设置的 deleteAfterMs（例如 24 小时）后，从手机回收站彻底清除；
 * 4. 手动在手机端点击【删除】立即直接移入手机本地回收站（不用等 1 小时）；
 * 5. 在线版没有单独的垃圾箱，共用手机本地回收站，支持恢复与清空。
 */
public final class OnlineWorkLifecycle {
    private static final String PREF_NAME = "online_work_lifecycle";
    private static final String KEY_RECORDS = "lifecycle_records";

    public static final class Item {
        public final String id;
        public final String title;
        public final String destination;
        public long firstSharedAtMs;
        public long trashedAtMs;
        public int useCount;
        public final List<String> images;
        public final String copyText;

        public Item(String id, String title, String destination, long firstSharedAtMs,
                    long trashedAtMs, int useCount, List<String> images, String copyText) {
            this.id = id;
            this.title = title != null ? title : "";
            this.destination = destination != null ? destination : "其他";
            this.firstSharedAtMs = firstSharedAtMs;
            this.trashedAtMs = trashedAtMs;
            this.useCount = useCount;
            this.images = images != null ? images : new ArrayList<>();
            this.copyText = copyText != null ? copyText : "";
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("title", title);
                obj.put("destination", destination);
                obj.put("firstSharedAtMs", firstSharedAtMs);
                obj.put("trashedAtMs", trashedAtMs);
                obj.put("useCount", useCount);
                JSONArray imgArr = new JSONArray();
                for (String img : images) imgArr.put(img);
                obj.put("images", imgArr);
                obj.put("copyText", copyText);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static Item fromJson(JSONObject obj) {
            if (obj == null) return null;
            String id = obj.optString("id", "");
            if (id.isEmpty()) return null;
            String title = obj.optString("title", "");
            String destination = obj.optString("destination", "其他");
            long firstSharedAtMs = obj.optLong("firstSharedAtMs", 0);
            long trashedAtMs = obj.optLong("trashedAtMs", 0);
            int useCount = obj.optInt("useCount", 0);
            String copyText = obj.optString("copyText", "");
            List<String> images = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("images");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    images.add(arr.optString(i));
                }
            }
            return new Item(id, title, destination, firstSharedAtMs, trashedAtMs, useCount, images, copyText);
        }
    }

    private OnlineWorkLifecycle() {}

    private static synchronized List<Item> loadAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_RECORDS, "[]");
        List<Item> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Item item = Item.fromJson(arr.optJSONObject(i));
                if (item != null) list.add(item);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static synchronized void saveAll(Context context, List<Item> list) {
        JSONArray arr = new JSONArray();
        for (Item it : list) {
            arr.put(it.toJson());
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECORDS, arr.toString()).apply();
    }

    /** 记录使用：首次使用打标并启动移入垃圾箱倒计时 */
    public static synchronized Item markUsed(Context context, OnlineWorkEntry work, long nowMs) {
        List<Item> list = loadAll(context);
        Item existing = null;
        for (Item it : list) {
            if (it.id.equals(work.id)) {
                existing = it;
                break;
            }
        }
        if (existing == null) {
            existing = new Item(work.id, work.title, work.destination, nowMs, 0,
                    Math.max(1, work.useCount + 1), work.images, work.copyText);
            list.add(existing);
        } else {
            if (existing.firstSharedAtMs <= 0) {
                existing.firstSharedAtMs = nowMs;
            }
            existing.useCount++;
        }
        saveAll(context, list);
        return existing;
    }

    /** 手动点击【删除】立即移入本地回收站 */
    public static synchronized Item moveToTrash(Context context, OnlineWorkEntry work, long nowMs) {
        List<Item> list = loadAll(context);
        Item existing = null;
        for (Item it : list) {
            if (it.id.equals(work.id)) {
                existing = it;
                break;
            }
        }
        if (existing == null) {
            existing = new Item(work.id, work.title, work.destination,
                    nowMs, nowMs, work.useCount, work.images, work.copyText);
            list.add(existing);
        } else {
            existing.trashedAtMs = nowMs;
            if (existing.firstSharedAtMs <= 0) existing.firstSharedAtMs = nowMs;
        }
        saveAll(context, list);
        return existing;
    }

    /** 判断某作品在手机端是否应被移入回收站（包含 1 小时自动到期或手动删除） */
    public static synchronized boolean shouldBeInTrash(Context context, String workId, long nowMs, long moveAfterMs) {
        List<Item> list = loadAll(context);
        for (Item it : list) {
            if (it.id.equals(workId)) {
                if (it.trashedAtMs > 0) return true;
                if (it.firstSharedAtMs > 0 && moveAfterMs >= 0 && nowMs >= it.firstSharedAtMs + moveAfterMs) {
                    // 自动到期移入回收站
                    it.trashedAtMs = it.firstSharedAtMs + moveAfterMs;
                    saveAll(context, list);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /** 获取某个作品的使用时间记录（如果存在） */
    public static synchronized Item getItem(Context context, String workId) {
        List<Item> list = loadAll(context);
        for (Item it : list) {
            if (it.id.equals(workId)) return it;
        }
        return null;
    }

    /**
     * 获取「已发送过但仍需继续保留在在线相册顶部」的本地副本：
     * 电脑端会把用过的作品物理移走到对应文件夹，但手机端已同步到本地，
     * 因此必须继续展示并置顶（标记「已发送N次」），直到倒计时结束才自动移入回收站。
     */
    public static synchronized List<Item> getActiveUsedItems(Context context, long nowMs, long moveAfterMs) {
        List<Item> list = loadAll(context);
        List<Item> active = new ArrayList<>();
        boolean modified = false;
        for (Item item : list) {
            if (item.useCount <= 0) continue;
            if (item.trashedAtMs > 0) continue;
            if (item.firstSharedAtMs > 0 && moveAfterMs >= 0 && nowMs >= item.firstSharedAtMs + moveAfterMs) {
                // 倒计时结束：自动转入回收站（与本地作品同一种清理策略）
                item.trashedAtMs = item.firstSharedAtMs + moveAfterMs;
                modified = true;
                continue;
            }
            active.add(item);
        }
        if (modified) {
            saveAll(context, list);
        }
        // 最近使用的排在最前
        Collections.sort(active, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.firstSharedAtMs, a.firstSharedAtMs);
            }
        });
        return active;
    }

    /** 获取所有当前在手机本地回收站中的在线作品 */
    public static synchronized List<Item> getTrashItems(Context context, long nowMs, long deleteAfterMs) {
        List<Item> list = loadAll(context);
        List<Item> trash = new ArrayList<>();
        boolean modified = false;
        Iterator<Item> it = list.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (item.trashedAtMs > 0) {
                // 检查是否超过彻底删除时间（必须显式配置了大于0的有效期才自动清理，避免移入瞬间被直接清空）
                if (deleteAfterMs > 0 && nowMs >= item.trashedAtMs + deleteAfterMs) {
                    it.remove();
                    modified = true;
                } else {
                    trash.add(item);
                }
            }
        }
        if (modified) {
            saveAll(context, list);
        }
        return trash;
    }

    /** 从回收站恢复作品回到活跃列表 */
    public static synchronized boolean restoreFromTrash(Context context, String workId) {
        List<Item> list = loadAll(context);
        boolean found = false;
        for (Item it : list) {
            if (it.id.equals(workId)) {
                it.trashedAtMs = 0;
                it.firstSharedAtMs = 0;
                it.useCount = 0;
                found = true;
                break;
            }
        }
        if (found) {
            saveAll(context, list);
        }
        return found;
    }

    /** 从手机端彻底删除该在线作品的回收站记录与缓存 */
    public static synchronized boolean deletePermanently(Context context, String workId) {
        List<Item> list = loadAll(context);
        boolean removed = false;
        Iterator<Item> it = list.iterator();
        while (it.hasNext()) {
            if (it.next().id.equals(workId)) {
                it.remove();
                removed = true;
                break;
            }
        }
        if (removed) {
            saveAll(context, list);
        }
        return removed;
    }

    /** 一键清空所有在线回收站作品 */
    public static synchronized int clearAllTrash(Context context) {
        List<Item> list = loadAll(context);
        int count = 0;
        Iterator<Item> it = list.iterator();
        while (it.hasNext()) {
            if (it.next().trashedAtMs > 0) {
                it.remove();
                count++;
            }
        }
        if (count > 0) {
            saveAll(context, list);
        }
        return count;
    }
}
