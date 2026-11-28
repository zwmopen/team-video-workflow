package com.zwm.gallery;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.Locale;

/** Read-only, full-screen transfer overview. No changes to transport or permissions. */
public final class TransferStatsActivity extends Activity {
    private LinearLayout cards;
    private TextView status;
    private Button refresh;
    private JSONObject remote;
    private boolean loading;
    private boolean details;
    private String connection = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ThemePalette.applySystemBarAppearance(this);
        LinearLayout page = column();
        page.setBackgroundColor(ThemePalette.getBgPrimary(this));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        Button back = action("‹ 返回", () -> finish());
        header.addView(back);
        TextView title = text("传输统计", 22, true);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        refresh = action("刷新", this::load);
        header.addView(refresh);
        page.addView(header);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        cards = column();
        cards.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(cards);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(ScreenInsets.protect(page));
        load();
    }

    private void load() {
        if (loading) return;
        remote = null;
        if (!RemoteRelayProfile.isEnrolled(this)) {
            connection = "尚未建立云端连接";
            render();
            return;
        }
        loading = true;
        connection = "正在更新团队统计…";
        render();
        new Thread(() -> {
            JSONObject value = null;
            try {
                value = RemoteRelayClient.fetchStats(RemoteRelayClient.ensureSession(this));
            } catch (Exception ignored) {}
            final JSONObject result = value;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                remote = result;
                loading = false;
                connection = result == null ? "暂时无法获取团队统计，点击刷新重试" : "团队统计已更新";
                render();
            });
        }, "transfer-overview").start();
    }

    private void render() {
        refresh.setEnabled(!loading);
        cards.removeAllViews();
        JSONObject local = TransferStatsStore.localSnapshot(this);
        JSONObject today = local.optJSONObject("today");
        JSONObject month = local.optJSONObject("month");
        LinearLayout localCard = card("这台手机 · 今日接收");
        localCard.addView(text(bytes(today, "totalBytes"), 32, true));
        secondary(localCard, today == null ? "接收记录暂未获取" : "已收到 " + today.optInt("totalObjects") + " 个文件");
        String[] keys = {"usbBytes", "lanBytes", "p2pBytes", "r2Bytes"};
        String[] names = {"数据线", "同一 Wi-Fi", "设备直连", "云端转发"};
        int[] colors = {0xff8b9bb7, 0xff739bd0, 0xffb4a1d3, 0xff44977d};
        long total = 0;
        for (String key : keys) total += today == null ? 0 : Math.max(0, today.optLong(key));
        if (total > 0) {
            LinearLayout bar = new LinearLayout(this);
            bar.setBackground(round(ThemePalette.getBgPrimary(this), 5));
            bar.setClipToOutline(true);
            for (int i=0; i<keys.length; i++) {
                long n = Math.max(0, today.optLong(keys[i]));
                if (n == 0) continue;
                android.view.View segment = new android.view.View(this);
                segment.setBackgroundColor(colors[i]);
                bar.addView(segment, new LinearLayout.LayoutParams(0, dp(8), (float)n / total));
            }
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(8));
            bp.topMargin = dp(12); bp.bottomMargin = dp(8);
            localCard.addView(bar, bp);
        }
        for (int i=0; i<keys.length; i++) {
            if (today != null && today.optLong(keys[i]) > 0) row(localCard, names[i], bytes(today, keys[i]));
        }
        row(localCard, "本月接收", bytes(month, "totalBytes"));

        JSONObject cm = child(remote, "month");
        JSONObject r2 = child(remote, "r2");
        LinearLayout cloud = card("云端用量 · 团队");
        row(cloud, "本月上传", "—");
        row(cloud, "本月下载", "—");
        secondary(cloud, "上传和下载不收流量费。现有统计尚未分别记录上传与下载。");
        row(cloud, "本月云端转发", bytes(cm, "r2Bytes"));
        row(cloud, "当前暂存", bytes(r2, "activeBytes"));
        row(cloud, "每月免费存储", "10 GB-month");
        secondary(cloud, "按文件存放时长计费，不是下载流量上限。");
        quota(cloud, cm, "classAOpsEst", "写入等操作", 1000000);
        quota(cloud, cm, "classBOpsEst", "读取等操作", 10000000);

        LinearLayout cost = card("本月预估额外费用");
        double usd = r2 == null ? Double.NaN : r2.optDouble("estimatedCostUsd", Double.NaN);
        cost.addView(text(!Double.isNaN(usd) && !Double.isInfinite(usd) && usd >= 0
                ? String.format(Locale.US, "$%.4f", usd) : "暂未获取", 26, true));
        secondary(cost, "仅为本应用的 R2 估算，不含其他项目及服务；实际费用以账单为准。");

        LinearLayout info = card("连接与详情");
        status = text(connection, 14, false);
        status.setTextColor(ThemePalette.getTextSecondary(this));
        info.addView(status);
        info.addView(action(details ? "收起技术详情" : "查看技术详情", () -> {
            details = !details; render();
        }));
        if (details) secondary(info, TransferStatsStore.formatTechnicalDetails(remote));
    }

    private void quota(LinearLayout card, JSONObject m, String key, String label, long limit) {
        boolean known = m != null && m.has(key) && !m.isNull(key);
        long n = known ? Math.max(0, m.optLong(key)) : 0;
        row(card, label, (known ? String.format(Locale.US, "%,d", n) : "—") + " / " + String.format(Locale.US, "%,d", limit));
        if (known) {
            android.widget.ProgressBar p = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            p.setMax(1000);
            p.setProgress((int)Math.min(1000, 1000.0*n/limit));
            card.addView(p, new LinearLayout.LayoutParams(-1, dp(6)));
        }
    }

    private JSONObject child(JSONObject parent, String key) {
        return parent == null ? null : parent.optJSONObject(key);
    }
    private String bytes(JSONObject o, String key) {
        return o == null || o.isNull(key) || !o.has(key) ? "—" : TransferStatsStore.fmtBytes(o.optLong(key));
    }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    private LinearLayout card(String label) {
        LinearLayout l = column();
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(round(ThemePalette.getCardBg(this), 20));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(12); cards.addView(l, p);
        secondary(l, label);
        return l;
    }
    private void row(LinearLayout card, String label, String value) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, dp(8), 0, dp(8));
        l.addView(text(label, 14, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text(value, 14, true);
        v.setGravity(Gravity.END);
        l.addView(v, new LinearLayout.LayoutParams(-2, -2));
        card.addView(l);
    }
    private void secondary(LinearLayout card, String s) {
        TextView v = text(s, 13, false);
        v.setTextColor(ThemePalette.getTextSecondary(this));
        v.setPadding(0, dp(3), 0, dp(5)); card.addView(v);
    }
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp);
        t.setTextColor(ThemePalette.getTextPrimary(this));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button action(String label, Runnable run) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false);
        b.setTextColor(ThemePalette.getTextPrimary(this));
        b.setBackground(round(ThemePalette.getCardBg(this), 14));
        b.setMinHeight(dp(44)); b.setOnClickListener(v -> run.run());
        return b;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
