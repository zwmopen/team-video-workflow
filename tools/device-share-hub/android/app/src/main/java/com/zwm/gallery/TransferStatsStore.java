package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
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
            if (bytes > 0 || objects > 0) {
                try {
                    OnlineGalleryClient.getInstance(context)
                            .reportTransferStatsAsync(normalizeChannel(channel), Math.max(0L, bytes), Math.max(0, objects));
                } catch (Exception ignored) {
                }
            }
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
        return formatHumanSummary(context, remoteStats, null);
    }

    public static String formatHumanSummary(Context context, JSONObject remoteStats, JSONObject pcStats) {
        JSONObject local = localSnapshot(context);
        JSONObject lt = local.optJSONObject("today");
        JSONObject lm = local.optJSONObject("month");
        JSONObject activeRemote = remoteStats;
        if (activeRemote == null && pcStats != null) {
            JSONObject cloud = pcStats.optJSONObject("cloud");
            if (cloud != null) activeRemote = cloud;
        }
        StringBuilder sb = new StringBuilder();

        JSONArray warnings = null;
        if (activeRemote != null && activeRemote.optJSONObject("r2") != null) {
            warnings = activeRemote.optJSONObject("r2").optJSONArray("warnings");
        }
        if ((warnings == null || warnings.length() == 0) && pcStats != null) {
            warnings = pcStats.optJSONArray("warnings");
        }
        if (warnings != null && warnings.length() > 0) {
            sb.append("⚠️ 【R2 免费额度预警（≥ 80%）】\n");
            for (int i = 0; i < warnings.length(); i++) {
                String w = warnings.optString(i, "").trim();
                if (!w.isEmpty()) sb.append("• ").append(w).append("\n");
            }
            sb.append("\n");
        }

        sb.append("【本机今日接收统计】\n");
        sb.append("• 合计：").append(fmtBytes(lt != null ? lt.optLong("totalBytes", 0) : 0))
                .append("（").append(lt != null ? lt.optInt("totalObjects", 0) : 0).append(" 个文件）\n");
        sb.append("• 局域网 Wi-Fi：").append(fmtBytes(lt != null ? lt.optLong("lanBytes", 0) : 0))
                .append(" | 云端 R2：").append(fmtBytes(lt != null ? lt.optLong("r2Bytes", 0) : 0)).append("\n");
        sb.append("• 本机本月累计：").append(fmtBytes(lm != null ? lm.optLong("totalBytes", 0) : 0)).append("\n\n");

        JSONArray devs = null;
        if (pcStats != null) {
            JSONArray pcDevs = pcStats.optJSONArray("devices");
            if (pcDevs != null && pcDevs.length() > 0) devs = pcDevs;
        }
        if ((devs == null || devs.length() == 0) && activeRemote != null) {
            devs = activeRemote.optJSONArray("devices");
        }
        if (devs != null && devs.length() > 0) {
            sb.append("【各手机设备流量统计（共 ").append(devs.length()).append(" 台）】\n");
            for (int i = 0; i < devs.length(); i++) {
                JSONObject d = devs.optJSONObject(i);
                if (d == null) continue;
                String name = d.optString("deviceName", "").trim();
                if (name.isEmpty()) name = d.optString("deviceId", "未知设备");
                sb.append("• ").append(name)
                        .append("：今日 ").append(fmtBytes(d.optLong("todayBytes", 0)))
                        .append("（").append(d.optInt("todayObjects", 0)).append("个）")
                        .append(" | 本月 ").append(fmtBytes(d.optLong("monthBytes", 0)))
                        .append("（LAN ").append(fmtBytes(d.optLong("monthLanBytes", 0)))
                        .append(" / R2 ").append(fmtBytes(d.optLong("monthR2Bytes", 0)))
                        .append("）\n");
            }
            sb.append("\n");
        }

        if (activeRemote != null) {
            JSONObject ct = activeRemote.optJSONObject("today");
            JSONObject cm = activeRemote.optJSONObject("month");
            JSONObject r2 = activeRemote.optJSONObject("r2");
            sb.append("【团队云端工作区统计】\n");
            if (ct != null) {
                sb.append("• 今日总流量：").append(fmtBytes(ct.optLong("bytes", 0)))
                        .append("（R2 ").append(fmtBytes(ct.optLong("r2Bytes", 0)))
                        .append(" / LAN ").append(fmtBytes(ct.optLong("lanBytes", 0)))
                        .append(" / P2P ").append(fmtBytes(ct.optLong("p2pBytes", 0)))
                        .append("）\n");
            }
            if (cm != null) {
                sb.append("• 本月总流量：").append(fmtBytes(cm.optLong("bytes", 0)))
                        .append("（Class A 约 ").append(cm.optLong("classAOpsEst", 0))
                        .append(" / Class B 约 ").append(cm.optLong("classBOpsEst", 0)).append("）\n");
            }
            if (r2 != null) {
                JSONObject q = r2.optJSONObject("quotas");
                sb.append("• R2 当前驻留对象：").append(r2.optInt("activeObjects", 0))
                        .append(" 个（").append(fmtBytes(r2.optLong("activeBytes", 0))).append("）\n");
                if (q != null) {
                    sb.append(String.format(Locale.US, "• 免费额度使用占比：存储 %.2f%% | A类 %.2f%% | B类 %.2f%%\n",
                            q.optDouble("storageUsageRatio", 0) * 100.0,
                            q.optDouble("classAUsageRatio", 0) * 100.0,
                            q.optDouble("classBUsageRatio", 0) * 100.0));
                }
                sb.append("• 预估超额费用：$").append(r2.optDouble("estimatedCostUsd", 0))
                        .append(" USD（").append(r2.optString("egressFreeNote", "R2 出站下载流量免费")).append("）\n");
                sb.append("说明：").append(r2.optString("billingNote", "预估数值仅供参考，实际账单以 Cloudflare 控制台为准。")).append("\n");
            }
        } else {
            sb.append("【团队云端统计】当前未授权或暂未连上云端工作区（与电脑同一 Wi-Fi 后将自动授权并同步）。\n");
            sb.append("提示：Cloudflare R2 标准存储出站下载流量免费，且临时中转对象在所有接收端确认后会自动删除。\n");
        }
        return sb.toString();
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
