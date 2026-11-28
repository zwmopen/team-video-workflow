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
        StringBuilder sb = new StringBuilder("云端服务：Cloudflare R2（标准存储）\n上传、下载流量均免费\n\n");
        JSONObject r2 = remoteStats == null ? null : remoteStats.optJSONObject("r2");
        JSONObject month = remoteStats == null ? null : remoteStats.optJSONObject("month");
        if (month != null && month.has("r2Bytes")) {
            sb.append("本月云端转发：").append(fmtBytes(month.optLong("r2Bytes"))).append("\n");
        }
        if (r2 != null && r2.has("activeBytes")) {
            sb.append("当前暂存：").append(fmtBytes(r2.optLong("activeBytes"))).append("\n");
        } else sb.append("当前暂存：暂未获取\n");
        sb.append("每月免费存储：10 GB-month（按存放时长计）\n");
        appendOperation(sb, month, "classAOpsEst", 1000000, "写入等操作");
        appendOperation(sb, month, "classBOpsEst", 10000000, "读取等操作");
        sb.append("\n以上为本应用统计及估算，不含账户内其他项目和 Workers 等服务。实际账单以 Cloudflare 为准。");
        return sb.toString();
    }

    private static void appendOperation(StringBuilder sb, JSONObject bucket, String key, long limit, String label) {
        sb.append(label).append("：");
        if (bucket != null && bucket.has(key) && !bucket.isNull(key)) {
            long used = Math.max(0, bucket.optLong(key));
            sb.append(String.format(Locale.US, "%,d / %,d 次（估算）", used, limit));
        } else sb.append("暂未获取 / ").append(String.format(Locale.US, "%,d 次", limit));
        sb.append("\n");
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
