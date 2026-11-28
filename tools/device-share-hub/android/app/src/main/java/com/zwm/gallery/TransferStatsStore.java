package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tracks local transfer traffic across USB/LAN/P2P/R2 channels and merges it with
 * Cloudflare `/v1/stats` estimates for transparent team cost/quota visibility.
 */
public final class TransferStatsStore {
    private static final String PREFS = "album_transfer_stats";

    private TransferStatsStore() {}

    public static synchronized void record(Context context, String channel, long bytes, int objects) {
        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String day = utcKey("yyyy-MM-dd");
            String month = utcKey("yyyy-MM");
            String raw = prefs.getString("data", "{}");
            JSONObject root = new JSONObject(raw);
            for (String scope : new String[]{"day:" + day, "month:" + month}) {
                JSONObject b = root.optJSONObject(scope);
                if (b == null) b = new JSONObject();
                String ch = normalizeChannel(channel);
                b.put(ch + "Bytes", Math.max(0L, b.optLong(ch + "Bytes", 0L)) + Math.max(0L, bytes));
                b.put("totalBytes", Math.max(0L, b.optLong("totalBytes", 0L)) + Math.max(0L, bytes));
                b.put("totalObjects", Math.max(0, b.optInt("totalObjects", 0)) + Math.max(0, objects));
                root.put(scope, b);
            }
            prefs.edit().putString("data", root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static synchronized JSONObject localSnapshot(Context context) {
        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject root = new JSONObject(prefs.getString("data", "{}"));
            JSONObject out = new JSONObject();
            String dayKey = utcKey("yyyy-MM-dd");
            String monthKey = utcKey("yyyy-MM");
            JSONObject today = root.optJSONObject("day:" + dayKey);
            JSONObject month = root.optJSONObject("month:" + monthKey);
            out.put("today", today == null ? new JSONObject() : today);
            out.put("month", month == null ? new JSONObject() : month);
            return out;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static String formatHumanSummary(Context context, JSONObject remoteStats) {
        return formatHumanSummary(context, remoteStats, "暂未获取团队统计");
    }

    public static String formatHumanSummary(Context context, JSONObject remoteStats, String teamStatus) {
        JSONObject local = localSnapshot(context);
        JSONObject lt = local.optJSONObject("today");
        JSONObject lm = local.optJSONObject("month");
        StringBuilder sb = new StringBuilder();
        sb.append("这台手机 · 今日接收\n");
        sb.append(fmtBytes(lt != null ? lt.optLong("totalBytes", 0) : 0))
                .append("  ·  ").append(lt != null ? lt.optInt("totalObjects", 0) : 0).append(" 个文件\n");
        appendChannel(sb, lt, "usbBytes", "USB 数据线");
        appendChannel(sb, lt, "lanBytes", "同一 Wi-Fi");
        appendChannel(sb, lt, "p2pBytes", "设备直接传输");
        appendChannel(sb, lt, "r2Bytes", "云端转发");
        sb.append("\n本月接收：").append(fmtBytes(lm != null ? lm.optLong("totalBytes", 0) : 0)).append("\n\n");
        sb.append("团队传输与费用\n");
        JSONObject ct = remoteStats == null ? null : remoteStats.optJSONObject("today");
        JSONObject cm = remoteStats == null ? null : remoteStats.optJSONObject("month");
        JSONObject r2 = remoteStats == null ? null : remoteStats.optJSONObject("r2");
        if (ct != null && ct.has("bytes")) sb.append("今日传输：").append(fmtBytes(ct.optLong("bytes"))).append("\n");
        if (cm != null && cm.has("bytes")) sb.append("本月传输：").append(fmtBytes(cm.optLong("bytes"))).append("\n");
        if (ct == null && cm == null) sb.append(teamStatus).append("\n");
        if (r2 != null && r2.has("estimatedCostUsd") && !r2.isNull("estimatedCostUsd")) {
            double cost = r2.optDouble("estimatedCostUsd", Double.NaN);
            if (!Double.isNaN(cost) && !Double.isInfinite(cost) && cost >= 0) {
                sb.append(String.format(Locale.US, "预估额外费用：$%.4f（美元）\n", cost));
            } else sb.append("预估额外费用：暂未获取\n");
        } else sb.append("预估额外费用：暂未获取\n");
        sb.append("\n云端下载不收流量费；其他费用以实际账单为准。");
        return sb.toString();
    }

    private static void appendChannel(StringBuilder sb, JSONObject bucket, String key, String label) {
        long bytes = bucket == null ? 0 : bucket.optLong(key, 0);
        if (bytes > 0) sb.append(label).append("：").append(fmtBytes(bytes)).append("\n");
    }

    public static String formatTechnicalDetails(JSONObject remoteStats) {
        JSONObject r2 = remoteStats == null ? null : remoteStats.optJSONObject("r2");
        if (r2 == null) return "云端服务：Cloudflare R2\n存储与请求额度：暂未获取";
        StringBuilder sb = new StringBuilder("云端服务：Cloudflare R2\n");
        if (r2.has("activeBytes")) sb.append("当前暂存：").append(fmtBytes(r2.optLong("activeBytes"))).append("\n");
        if (r2.has("activeObjects")) sb.append("暂存对象：").append(r2.optInt("activeObjects")).append(" 个\n");
        JSONObject q = r2.optJSONObject("quotas");
        if (q != null) {
            appendQuota(sb, q, "storageUsageRatio", "存储额度");
            appendQuota(sb, q, "classAUsageRatio", "写入等操作额度（Class A）");
            appendQuota(sb, q, "classBUsageRatio", "读取等操作额度（Class B）");
        }
        sb.append("\n统计为估算，实际费用以 Cloudflare 账单为准。");
        return sb.toString();
    }

    private static void appendQuota(StringBuilder sb, JSONObject q, String key, String label) {
        double ratio = q.optDouble(key, Double.NaN);
        if (!Double.isNaN(ratio) && !Double.isInfinite(ratio) && ratio >= 0) {
            sb.append(String.format(Locale.US, "%s：%.2f%%\n", label, ratio * 100.0));
        }
    }

    public static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0));
    }

    private static String normalizeChannel(String c) {
        if (c == null) return "lan";
        String lc = c.trim().toLowerCase(Locale.US);
        if ("usb".equals(lc) || "lan".equals(lc) || "p2p".equals(lc) || "r2".equals(lc)) return lc;
        return "lan";
    }

    private static String utcKey(String fmt) {
        SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }
}
