package com.zwm.gallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.content.pm.PackageManager;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private com.zwm.livecore.LiveQuickMenu liveQuickMenu;
    private static final String PREFS = "device_share";
    private static final int REQUEST_TREE = 71;
    private static final int REQUEST_NOTIFICATIONS = 72;
    private EditText deviceName;
    private TextView pathText;
    private SettingSwitchRow soundSwitch;
    private TextView moveAfterText;
    private TextView deleteAfterText;
    private SettingSwitchRow autoReceiveSwitch;
    private TextView engineValueText;
    private TextView statsValueText;
    private SettingSwitchRow autoLiveSwitchRef;
    private LinearLayout strategyRowView;
    private TextView strategyValueText;
    private LinearLayout moveRowView;
    private LinearLayout deleteRowView;
    private LinearLayout styleRowView;
    private LinearLayout themeRowView;
    private volatile OnlineGalleryClient.LiveEngineStatus cachedEngineStatus = OnlineGalleryClient.LiveEngineStatus.offline();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        liveQuickMenu = new com.zwm.livecore.LiveQuickMenu(this);
        ThemePalette.applySystemBarAppearance(this);
        setContentView(ScreenInsets.protect(buildUi()));
        refreshLiveEngineStatus(false);
        refreshTransferStatsAlertBadge();
    }

    private SpringScrollView buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(36));
        root.setBackgroundColor(ThemePalette.getBgPrimary(this));

        TextView title = text("设置", 26, true);
        root.addView(title);
        TextView subtitle = text("常用选项集中在这里，修改后立即生效。", 12, false);
        subtitle.setTextColor(ThemePalette.getTextSecondary(this));
        root.addView(subtitle, margins(0, dp(4), 0, dp(20)));

        root.addView(label("手机名称（电脑端显示）"));
        deviceName = new EditText(this);
        deviceName.setSingleLine(true);
        deviceName.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("deviceName", "我的手机"));
        deviceName.setBackground(round(ThemePalette.getCardBg(this), 14));
        deviceName.setTextColor(ThemePalette.getTextPrimary(this));
        deviceName.setPadding(dp(14), 0, dp(14), 0);
        root.addView(deviceName, new LinearLayout.LayoutParams(-1, dp(52)));
        Button saveName = button("保存名称", true);
        saveName.setOnClickListener(v -> saveName());
        root.addView(saveName, margins(0, dp(10), 0, dp(20)));

        root.addView(label("作品文件夹"));
        pathText = text(currentPathName(), 16, true);
        pathText.setBackground(round(ThemePalette.getCardBg(this), 14));
        pathText.setTextColor(ThemePalette.getTextPrimary(this));
        pathText.setPadding(dp(14), dp(16), dp(14), dp(16));
        root.addView(pathText);
        Button choose = button("更改作品文件夹", false);
        choose.setOnClickListener(v -> chooseFolder());
        root.addView(choose, margins(0, dp(10), 0, dp(20)));

        root.addView(label("接收"));
        autoReceiveSwitch = settingSwitch(
                "自动接收",
                "开启时允许电脑和其他设备投送内容；关闭后只保留在线发现和本机查看",
                OnlineService.isAutoReceiveEnabled(this));
        autoReceiveSwitch.setOnCheckedChangeListener((button, enabled) -> {
            OnlineService.setAutoReceiveEnabled(this, enabled);
            toast(enabled ? "自动接收已打开" : "自动接收已关闭");
        });
        root.addView(autoReceiveSwitch, settingMargins(dp(10)));

        SettingSwitchRow clipboardSyncSwitch = settingSwitch(
                "跨设备剪贴板同步",
                "电脑复制后自动同步至手机系统剪贴板，长按直接粘贴；阅后即焚防残留",
                OnlineService.isClipboardSyncEnabled(this));
        clipboardSyncSwitch.setOnCheckedChangeListener((button, enabled) -> {
            OnlineService.setClipboardSyncEnabled(this, enabled);
            toast(enabled ? "剪贴板同步已打开" : "剪贴板同步已关闭");
        });
        root.addView(clipboardSyncSwitch, settingMargins(dp(10)));

        SettingSwitchRow autoLiveSwitch = settingSwitch(
                "分享时静图自动转实况",
                "",
                OnlineService.isAutoLiveOnShareEnabled(this));
        autoLiveSwitchRef = autoLiveSwitch;
        updateAutoLiveRowDetail(autoLiveSwitch);
        autoLiveSwitch.setOnCheckedChangeListener((button, enabled) -> {
            OnlineService.setAutoLiveOnShareEnabled(this, enabled);
            toast(enabled ? "已开启：分享静图时自动转为实况图" : "已关闭分享自动转实况");
        });
        autoLiveSwitch.setOnRowBodyClickListener(v -> showAutoLiveModeDialog(autoLiveSwitch));
        root.addView(autoLiveSwitch, settingMargins(dp(10)));
        LinearLayout engineRow = choiceRow("处理引擎偏好", formatEngineSummaryText());
        engineValueText = (TextView) engineRow.getChildAt(1);
        liveQuickMenu.attachTapAndHold(engineRow, () -> showLiveEngineMenu(engineRow));
        root.addView(engineRow, settingMargins(dp(20)));

        root.addView(label("跨网传输与统计"));
        strategyRowView = choiceRow(
                "传输策略",
                RemoteRelayProfile.getStrategy(this).title);
        strategyValueText = (TextView) strategyRowView.getChildAt(1);
        liveQuickMenu.attachTapAndHold(strategyRowView, this::showTransportStrategyDialog);
        root.addView(strategyRowView, settingMargins(dp(10)));
        LinearLayout statsRow = choiceRow(
                "云端额度与流量",
                RemoteRelayProfile.isEnrolled(this) ? "已授权 · 查看 R2 额度" : "查看本机与 R2 估算");
        statsValueText = (TextView) statsRow.getChildAt(1);
        statsRow.setOnClickListener(v -> showTransferStatsDialog());
        root.addView(statsRow, settingMargins(dp(20)));

        root.addView(label("自动整理"));
        CleanupSettings.Values cleanup = CleanupSettings.read(this);
        moveRowView = cleanupRow("自动移入回收站", cleanup.moveHours());
        moveAfterText = (TextView) moveRowView.getChildAt(1);
        liveQuickMenu.attachTapAndHold(moveRowView, () -> showCleanupPicker(true));
        root.addView(moveRowView, settingMargins(dp(8)));
        deleteRowView = cleanupRow("自动彻底删除", cleanup.deleteHours());
        deleteAfterText = (TextView) deleteRowView.getChildAt(1);
        liveQuickMenu.attachTapAndHold(deleteRowView, () -> showCleanupPicker(false));
        root.addView(deleteRowView, settingMargins(dp(20)));

        root.addView(label("提醒"));
        soundSwitch = settingSwitch("声音通知", "收到文件时显示通知并响铃",
                getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("soundNotificationsEnabled", false));
        soundSwitch.setOnCheckedChangeListener((button, enabled) -> changeSoundNotifications(enabled));
        root.addView(soundSwitch, settingMargins(dp(10)));
        SettingSwitchRow vibrationSwitch = settingSwitch("震动提醒", "开始、完成或失败时震动",
                getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("vibrationEnabled", false));
        vibrationSwitch.setOnCheckedChangeListener((button, enabled) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("vibrationEnabled", enabled).apply();
            if (enabled) vibrateOnce();
        });
        root.addView(vibrationSwitch, settingMargins(dp(20)));

        // ---- 外观风格（深色模式 + 全局玻璃视觉风格） ----
        root.addView(label("外观"));
        String currentStyleText = ThemePalette.isFlClashGlassStyle(this) ? "通透玻璃（推荐）" : "经典纯色";
        styleRowView = choiceRow("主题风格", currentStyleText);
        liveQuickMenu.attachTapAndHold(styleRowView, this::showThemeStyleDialog);
        root.addView(styleRowView, settingMargins(dp(10)));

        int currentThemeMode = ThemePalette.getThemeMode(this);
        String currentModeText = currentThemeMode == ThemePalette.THEME_MODE_LIGHT ? "浅色模式"
                : (currentThemeMode == ThemePalette.THEME_MODE_DARK ? "深色模式" : "跟随系统");
        themeRowView = choiceRow("外观风格", currentModeText);
        liveQuickMenu.attachTapAndHold(themeRowView, this::showThemeModeDialog);
        root.addView(themeRowView, settingMargins(dp(20)));

        root.addView(label("软件"));
        SettingSwitchRow autoUpdateSwitch = settingSwitch(
                "自动检查更新",
                "每次打开相册时检查；确认后应用内校验下载，再点通知安装",
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean(UpdateChecker.PREF_AUTO_UPDATE_ENABLED, true));
        autoUpdateSwitch.setOnCheckedChangeListener((button, enabled) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(UpdateChecker.PREF_AUTO_UPDATE_ENABLED, enabled).apply());
        root.addView(autoUpdateSwitch, settingMargins(dp(10)));
        String lan = UpdateChecker.getLanServerUrl(this);
        String sourceLabel = lan.isEmpty() ? "更新源  优先电脑局域网 / 备用云端" : "更新源  电脑局域网 (" + lan.replace("http://", "") + ")";
        TextView updateEntry = text(sourceLabel, 15, true);
        updateEntry.setPadding(dp(14), dp(16), dp(14), dp(16));
        updateEntry.setBackground(round(ThemePalette.getCardBg(this), 14));
        updateEntry.setTextColor(ThemePalette.getTextPrimary(this));
        root.addView(updateEntry, settingMargins(dp(20)));
        TextView version = text("当前版本  " + UpdateChecker.currentVersion(this), 16, true);
        version.setPadding(dp(14), dp(16), dp(14), dp(16));
        version.setBackground(round(ThemePalette.getCardBg(this), 14));
        version.setTextColor(ThemePalette.getTextPrimary(this));
        root.addView(version);
        Button update = button("检查更新", false);
        String updateLabel = update.getText().toString();
        update.setOnClickListener(v -> {
            update.setEnabled(false);
            update.setText("正在检查…");
            UpdateChecker.check(this, false, true, () -> {
                update.setText(updateLabel);
                update.setEnabled(true);
            });
        });
        root.addView(update, margins(0, dp(10), 0, dp(10)));
        Button about = button("软件说明", false);
        about.setOnClickListener(v -> showAbout());
        root.addView(about, margins(0, 0, 0, dp(10)));
        Button diagnostics = button("复制诊断信息", false);
        diagnostics.setOnClickListener(v -> copyDiagnostics());
        root.addView(diagnostics);

        Button back = button("完成", true);
        back.setOnClickListener(v -> finish());
        root.addView(back, margins(0, dp(28), 0, 0));

        SpringScrollView scroll = new SpringScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void showThemeStyleDialog() {
        String[] options = {"经典纯色", "通透玻璃（推荐）"};
        int current = ThemePalette.getThemeStyle(this) == ThemePalette.THEME_STYLE_FLCLASH_GLASS ? 1 : 0;
        liveQuickMenu.show(styleRowView, options, current, which -> {
            ThemePalette.setThemeStyle(this, which == 1
                    ? ThemePalette.THEME_STYLE_FLCLASH_GLASS
                    : ThemePalette.THEME_STYLE_CLASSIC);
            toast("已切换为：" + options[which]);
            recreate();
        });
    }

    private void showThemeModeDialog() {
        String[] options = {"跟随系统", "浅色模式", "深色模式"};
        int current = ThemePalette.getThemeMode(this);
        liveQuickMenu.show(themeRowView, options, current, which -> {
            ThemePalette.setThemeMode(this, which);
            ThemePalette.applySystemBarAppearance(this);
            recreate();
        });
    }

    @Override public void onBackPressed() {
        if (liveQuickMenu != null && liveQuickMenu.close()) return;
        super.onBackPressed();
    }

    @Override protected void onStop() {
        if (liveQuickMenu != null) liveQuickMenu.close();
        super.onStop();
    }

    private void showAutoLiveModeDialog(SettingSwitchRow row) {
        String current = OnlineService.getAutoLiveMotionMode(this);
        int count = OnlineService.AUTO_LIVE_MODE_IDS.length;
        String[] labels = new String[count + 1];
        int checked = 0;
        for (int i=0;i<count;i++) {
            labels[i]=OnlineService.AUTO_LIVE_MODE_TITLES[i];
            if (OnlineService.AUTO_LIVE_MODE_IDS[i].equals(current)) checked=i;
        }
        labels[count]="幅度、画质与选项…";
        liveQuickMenu.show(row, labels, checked, which -> {
            if (which==count) { showAutoLiveStrengthDialog(row); return; }
            String chosen=OnlineService.AUTO_LIVE_MODE_IDS[which];
            OnlineService.setAutoLiveMotionMode(this,chosen);
            updateAutoLiveRowDetail(row);
            new OnlineGalleryClient(this).syncLiveMotionModeAsync(chosen);
            toast("已切换为："+OnlineService.getAutoLiveMotionTitle(chosen));
        });
    }

    private void showAutoLiveStrengthDialog(SettingSwitchRow row) {
        boolean push = "handheld_push_drift_v1".equals(OnlineService.getAutoLiveMotionMode(this));
        String[] items = {
                push ? "柔和（5.8% · 2.0 秒）" : "极微自然（1.0% · 1.3 秒）",
                push ? "参考原版（11.6% · 2.0 秒）" : "标准推荐（1.8% · 1.6 秒）",
                push ? "明显（12.0% · 2.2 秒）" : "轻柔明显（3.2% · 2.2 秒）",
                "跳过已是实况的照片：" + (OnlineService.isAutoLiveSkipExisting(this) ? "已开启" : "已关闭"),
                "动态视频画质：" + com.zwm.livecore.LiveQualityTier.fromId(OnlineService.getAutoLiveQualityTier(this)).title
        };
        float r = OnlineService.getAutoLiveRatio(this);
        int checked = push ? (r < 1.087f ? 0 : (r > 1.118f ? 2 : 1)) : (r < 1.013f ? 0 : (r > 1.024f ? 2 : 1));
        liveQuickMenu.show(row, items, checked, which -> {
            if (which == 3) {
                boolean next = !OnlineService.isAutoLiveSkipExisting(this);
                OnlineService.setAutoLiveSkipExisting(this, next);
                toast(next ? "已开启：已有实况照片直接直通" : "已关闭：统一按当前效果重新生成");
            } else if (which == 4) {
                showAutoLiveQualityTierDialog(row);
            } else {
                float newRatio = push ? (which == 0 ? 1.058f : (which == 2 ? 1.120f : 1.116f)) : (which == 0 ? 1.010f : (which == 2 ? 1.032f : 1.018f));
                float newDur = push ? (which == 2 ? 2.2f : 2.0f) : (which == 0 ? 1.3f : (which == 2 ? 2.2f : 1.6f));
                OnlineService.setAutoLiveRatio(this, newRatio);
                OnlineService.setAutoLiveDuration(this, newDur);
                updateAutoLiveRowDetail(row);
                toast("已调整为：" + items[which]);
            }
        });
    }

    private void showTransportStrategyDialog() {
        RemoteRelayProfile.TransportStrategy current = RemoteRelayProfile.getStrategy(this);
        RemoteRelayProfile.TransportStrategy[] all = RemoteRelayProfile.TransportStrategy.values();
        String[] labels = new String[all.length];
        int checked = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == current) checked = i;
            labels[i] = all[i].title;
        }
        liveQuickMenu.show(strategyRowView, labels, checked, which -> {
            RemoteRelayProfile.TransportStrategy chosen = all[which];
            RemoteRelayProfile.setStrategy(this, chosen);
            OnlineGalleryClient.getInstance(this).invalidateBaseUrl();
            String mappedChannel = chosen == RemoteRelayProfile.TransportStrategy.USB_FIRST ? "usb"
                    : (chosen == RemoteRelayProfile.TransportStrategy.LAN_FIRST ? "lan" : "auto");
            getSharedPreferences("album_prefs", MODE_PRIVATE).edit().putString("transferChannel", mappedChannel).apply();
            if (strategyValueText != null) strategyValueText.setText(chosen.title);
            toast("传输策略已设为：" + chosen.title + "（" + chosen.detail + "）");
        });
    }

    private void refreshTransferStatsAlertBadge() {
        OnlineGalleryClient.getInstance(this).fetchPcTransferStats(new OnlineGalleryClient.Callback<org.json.JSONObject>() {
            @Override public void onSuccess(org.json.JSONObject pcStats) {
                if (isFinishing() || statsValueText == null || pcStats == null) return;
                boolean alert80 = pcStats.optBoolean("quotaAlert80", false);
                org.json.JSONArray devs = pcStats.optJSONArray("devices");
                int devCount = devs != null ? devs.length() : 0;
                if (alert80) {
                    statsValueText.setText("⚠️ 免费额度已达 80%！点击查看");
                    statsValueText.setTextColor(Color.rgb(217, 72, 15));
                } else if (devCount > 0) {
                    statsValueText.setText("已记录 " + devCount + " 台设备 · 点击看明细");
                }
            }
            @Override public void onError(Exception error) {}
        });
    }

    private void showTransferStatsDialog() {
        String initial = TransferStatsStore.formatHumanSummary(this, null, null);
        AlertDialog dialog = new AlertDialog.Builder(this, ThemePalette.dialogThemeRes(this))
                .setTitle("各设备流量与 R2 额度统计")
                .setMessage(initial + "\n正在向电脑与云端刷新各设备统计…")
                .setPositiveButton("确定", null)
                .show();
        new Thread(() -> {
            org.json.JSONObject pcStats = OnlineGalleryClient.getInstance(this).fetchPcTransferStatsSync();
            org.json.JSONObject remoteStats = null;
            try {
                if (RemoteRelayProfile.isEnrolled(this)) {
                    RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(this);
                    remoteStats = RemoteRelayClient.fetchStats(session);
                }
            } catch (Exception ignored) {
            }
            final String updated = TransferStatsStore.formatHumanSummary(this, remoteStats, pcStats);
            runOnUiThread(() -> {
                if (dialog.isShowing()) dialog.setMessage(updated);
            });
        }, "fetch-transfer-stats").start();
    }

    private void updateLiveEngineSummary() {
        if (engineValueText != null) {
            engineValueText.setText(formatEngineSummaryText());
        }
    }

    private String formatEngineSummaryText() {
        com.zwm.livecore.LiveEnginePreference pref = com.zwm.livecore.LiveEngineSettings.get(this);
        OnlineGalleryClient.LiveEngineStatus st = cachedEngineStatus;
        if (pref == com.zwm.livecore.LiveEnginePreference.LOCAL_ONLY) {
            return "仅本地硬件无损渲染";
        }
        if (pref == com.zwm.livecore.LiveEnginePreference.FLOW_FIRST) {
            if (!st.reachable) return "Google Flow · 电脑离线将自动切本地";
            return st.flowReady ? ("Google Flow · 已就绪（" + st.flowCredits + " 积分）")
                    : "Google Flow · 暂不可用（将走本地）";
        }
        if (!st.reachable) return "智谱免费优先 · 电脑离线将自动切本地";
        return st.zhipuReady ? "智谱 CogVideoX-Flash · 已就绪" : "智谱优先 · 待配置免费 Key";
    }

    private void refreshLiveEngineStatus(boolean forceRefresh) {
        OnlineGalleryClient.getInstance(this).fetchLiveEngineStatus(forceRefresh, new OnlineGalleryClient.Callback<OnlineGalleryClient.LiveEngineStatus>() {
            @Override public void onSuccess(OnlineGalleryClient.LiveEngineStatus data) {
                cachedEngineStatus = data != null ? data : OnlineGalleryClient.LiveEngineStatus.offline();
                if (!isFinishing()) updateLiveEngineSummary();
            }
            @Override public void onError(Exception error) {}
        });
    }

    private void showLiveEngineMenu(LinearLayout anchor) {
        OnlineGalleryClient.LiveEngineStatus st = cachedEngineStatus;
        com.zwm.livecore.LiveEnginePreference cur = com.zwm.livecore.LiveEngineSettings.get(this);
        String zhipuLabel = st.reachable
                ? (st.zhipuReady ? "智谱 CogVideoX-Flash（免费 AI · 已就绪）" : "智谱 CogVideoX-Flash（免费 AI · 待配 Key）")
                : "智谱 CogVideoX-Flash（免费 AI · 连电脑可用）";
        String localLabel = "仅手机本地无损渲染（0 流量 · 极速）";
        String flowLabel = st.reachable
                ? (st.flowReady ? ("Google Flow 电脑联动（已就绪 · " + st.flowCredits + " 积分）") : "Google Flow 电脑联动（未就绪）")
                : "Google Flow 电脑联动（连电脑可用）";
        String[] labels = new String[]{zhipuLabel, localLabel, flowLabel, "配置电脑端智谱免费 API Key…"};
        int checked = cur == com.zwm.livecore.LiveEnginePreference.LOCAL_ONLY ? 1
                : (cur == com.zwm.livecore.LiveEnginePreference.FLOW_FIRST ? 2 : 0);
        com.zwm.livecore.LiveEnginePreference[] prefs = new com.zwm.livecore.LiveEnginePreference[]{
                com.zwm.livecore.LiveEnginePreference.ZHIPU_FIRST,
                com.zwm.livecore.LiveEnginePreference.LOCAL_ONLY,
                com.zwm.livecore.LiveEnginePreference.FLOW_FIRST
        };
        liveQuickMenu.show(anchor, labels, checked, which -> {
            if (which == 3) {
                showZhipuKeyConfigDialog();
                return;
            }
            com.zwm.livecore.LiveEnginePreference chosen = prefs[which];
            getSharedPreferences("live_engine_preferences", MODE_PRIVATE).edit()
                    .putString("preferred_engine_v1", chosen.id).apply();
            updateLiveEngineSummary();
            if (chosen == com.zwm.livecore.LiveEnginePreference.ZHIPU_FIRST && st.reachable && !st.zhipuReady) {
                toast("已设为智谱优先；请点下一项填入智谱免费 API Key，未配前将自动使用本地无损渲染");
            } else {
                toast("处理引擎已设为：" + formatEngineSummaryText());
            }
            refreshLiveEngineStatus(true);
        });
    }

    private void showZhipuKeyConfigDialog() {
        EditText input = new EditText(this);
        input.setHint("粘贴智谱开放平台 API Key（cogvideox-flash 免费）");
        input.setSingleLine(true);
        input.setTextColor(ThemePalette.getTextPrimary(this));
        input.setHintTextColor(ThemePalette.getTextMuted(this));
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(22), dp(8), dp(22), 0);
        holder.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        String existingHint = cachedEngineStatus.zhipuReady
                ? ("当前电脑已配置 Key（" + cachedEngineStatus.zhipuMasked + "）；留空保存可清除。")
                : "前往 bigmodel.cn 免费注册并创建 API Key，粘贴后直接存入电脑本机，无需重装手机端。";
        new AlertDialog.Builder(this, ThemePalette.dialogThemeRes(this))
                .setTitle("配置电脑端智谱免费 API Key")
                .setMessage(existingHint)
                .setView(holder)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存到电脑", (d, w) -> {
                    String key = input.getText().toString().trim();
                    OnlineGalleryClient.getInstance(this).saveZhipuApiKey(key, new OnlineGalleryClient.Callback<OnlineGalleryClient.LiveEngineStatus>() {
                        @Override public void onSuccess(OnlineGalleryClient.LiveEngineStatus data) {
                            cachedEngineStatus = data;
                            updateLiveEngineSummary();
                            toast(data.zhipuReady ? "智谱免费 Key 已保存到电脑并生效" : "已清除电脑端智谱 Key");
                        }
                        @Override public void onError(Exception error) {
                            toast("保存失败：请先确保手机与电脑已连通（" + error.getMessage() + "）");
                        }
                    });
                })
                .show();
    }

    private void showAutoLiveQualityTierDialog(SettingSwitchRow row) {
        com.zwm.livecore.LiveQualityTier cur =
                com.zwm.livecore.LiveQualityTier.fromId(OnlineService.getAutoLiveQualityTier(this));
        com.zwm.livecore.LiveQualityTier[] tiers = com.zwm.livecore.LiveQualityTier.values();
        String[] labels = new String[tiers.length];
        int checked = 0;
        for (int i = 0; i < tiers.length; i++) {
            if (tiers[i] == cur) checked = i;
            labels[i] = tiers[i].title + " · " + tiers[i].summary;
        }
        liveQuickMenu.show(row, labels, checked, which -> {
            com.zwm.livecore.LiveQualityTier chosen = tiers[which];
            OnlineService.setAutoLiveQualityTier(this, chosen.id);
            updateAutoLiveRowDetail(row);
            toast("动态画质已设为：" + chosen.title + "（静态封面保持原图画质）");
        });
    }

    private void updateAutoLiveRowDetail(SettingSwitchRow row) {
        String title = OnlineService.getAutoLiveMotionTitle(OnlineService.getAutoLiveMotionMode(this));
        com.zwm.livecore.LiveQualityTier tier =
                com.zwm.livecore.LiveQualityTier.fromId(OnlineService.getAutoLiveQualityTier(this));
        float pct = (OnlineService.getAutoLiveRatio(this) - 1.0f) * 100f;
        row.setDetail(String.format(java.util.Locale.US,
                "%s（%.1f%% · %.1f秒）· %s · 静态无损",
                title, pct, OnlineService.getAutoLiveDuration(this), tier.title));
    }

    private void saveName() {
        String value = deviceName.getText().toString().trim();
        if (value.isEmpty()) { toast("名称不能为空"); return; }
        if (value.length() > 30) { toast("名称最多 30 个字"); return; }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("deviceName", value).apply();
        DiagnosticLog.write(this, "device_name_saved", value);
        toast("已保存，电脑端会自动刷新");
    }

    private void showCleanupPicker(boolean editingMove) {
        CleanupSettings.Values current = CleanupSettings.read(this);
        int currentHours = editingMove ? current.moveHours() : current.deleteHours();
        int[] hours = {CleanupSettings.MANUAL, 0, 1, 3, 6, 24};
        String[] labels = {"手动", "立刻", "1 小时后", "3 小时后", "6 小时后", "24 小时后", "自定义…"};
        int checked = -1;
        for (int index = 0; index < hours.length; index++) {
            if (hours[index] == currentHours) checked = index;
        }
        View anchor = editingMove ? moveRowView : deleteRowView;
        liveQuickMenu.show(anchor, labels, checked, position -> {
            if (position == hours.length) showCustomCleanupHours(editingMove, currentHours);
            else saveCleanupHours(editingMove, hours[position]);
        });
    }

    private void showCustomCleanupHours(boolean editingMove, int currentHours) {
        EditText input = numberField(Integer.toString(currentHours >= 0 ? currentHours : 1));
        input.setSelectAllOnFocus(true);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(22), dp(4), dp(22), 0);
        holder.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        new AlertDialog.Builder(this, ThemePalette.dialogThemeRes(this))
                .setTitle("自定义小时数")
                .setMessage("请输入 0～720 的整数；0 表示立刻。")
                .setView(holder)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        saveCleanupHours(editingMove,
                                Integer.parseInt(input.getText().toString().trim()));
                    } catch (NumberFormatException error) {
                        toast("请输入 0～720 的整数小时");
                    }
                }).show();
    }

    private void saveCleanupHours(boolean editingMove, int hours) {
        if (hours < CleanupSettings.MANUAL || hours > 720) {
            toast("请输入 0～720 的整数小时");
            return;
        }
        CleanupSettings.Values current = CleanupSettings.read(this);
        int moveHours = editingMove ? hours : current.moveHours();
        int deleteHours = editingMove
                ? (hours >= 0 && current.deleteHours() >= 0
                    ? Math.max(hours, current.deleteHours()) : current.deleteHours())
                : hours;
        if (moveHours >= 0 && deleteHours >= 0 && deleteHours < moveHours) {
            toast("彻底删除时间不能早于移入回收站");
            return;
        }
        if (!CleanupSettings.save(this, toStoredMinutes(moveHours), toStoredMinutes(deleteHours))) {
            toast("自动整理时间没有保存");
            return;
        }
        moveAfterText.setText(cleanupTimingText(moveHours));
        deleteAfterText.setText(cleanupTimingText(deleteHours));
        DiagnosticLog.write(this, "cleanup_settings_saved",
                "moveHours=" + moveHours + " deleteHours=" + deleteHours);
        toast("已保存");
    }

    private EditText numberField(String value) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(value);
        field.setBackground(round(ThemePalette.getCardBg(this), 14));
        field.setTextColor(ThemePalette.getTextPrimary(this));
        field.setPadding(dp(14), 0, dp(14), 0);
        return field;
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateChecker.reportDownloadProblem(this);
        startService(new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_REFRESH_STATUS));
    }

    private void changeSoundNotifications(boolean enabled) {
        if (!enabled) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("soundNotificationsEnabled", false).apply();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("soundNotificationsEnabled", true).apply();
        toast("声音通知已打开");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("soundNotificationsEnabled", granted).apply();
        soundSwitch.setChecked(granted);
        toast(granted ? "声音通知已打开" : "系统没有允许通知");
    }

    private void vibrateOnce() {
        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(120);
        }
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE
                || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String name = treeName(tree);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("libraryTreeUri", tree.toString()).putString("libraryTreeName", name).apply();
            pathText.setText(name);
            toast("路径已保存");
        } catch (Exception error) {
            toast("保存路径失败：" + error.getMessage());
        }
    }

    private void showAbout() {
        String description =
                "把分散在电脑和手机里的素材，整理成随手可用的作品库。\n\n"
                + "核心场景\n"
                + "从电脑拖入文件、ZIP 或整个文件夹，手机自动接收并保留原目录结构；含图片和 TXT 的目录会被识别为作品，普通文件也可以继续传送、预览和分享。\n\n"
                + "作品工作流\n"
                + "点一次“复制并分享”，应用会立即记录一次，文案自动进入剪贴板，作品图片交给系统分享面板。再次分享会先提醒，避免误发同一个作品。自动移入回收站和彻底删除默认均为手动，可在设置中选择处理时间。\n\n"
                + "跨设备传送\n"
                + "电脑、Android 和 iPhone 在同一 Wi‑Fi 下自动发现。选择设备和文件即可传送，过程带有进度、完整性校验和结果提示。\n\n"
                + "隐私与权限\n"
                + "应用不再读取系统剪切板、不再监测系统截图、不再使用悬浮窗权限；只在你主动选择文件并点击传送，或主动点击复制分享时访问对应内容。\n\n"
                + "系统分享\n"
                + "其他应用的分享面板里可以选择“相册”，再选择在线设备。普通文件按真实文件夹存放，只有图片和文字一起直接分享时才进入分享准备。\n\n"
                + "设计思路\n"
                + "内容优先、操作尽量少、状态一眼可见。目录授权、作品记录、分享次数和回收站都保存在本机；覆盖升级会延续现有数据。坚果云只属于电脑端，不进入手机版。";

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), 0, dp(24), 0);

        TextView copy = text(description, 14, false);
        copy.setTextColor(ThemePalette.getTextSecondary(this));
        copy.setLineSpacing(dp(2), 1f);
        content.addView(copy, new LinearLayout.LayoutParams(-1, -2));

        TextView download = text(
                "手动下载最新版安装包\n" + UpdateEndpoint.RELEASE_PAGE, 14, true);
        download.setTextColor(ThemePalette.getLinkColor(SettingsActivity.this));
        download.setPaintFlags(download.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        download.setContentDescription("打开发布页手动下载最新版安装包");
        download.setClickable(true);
        download.setFocusable(true);
        download.setOnClickListener(v -> openReleasePage());
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(-1, -2);
        downloadParams.setMargins(0, dp(20), 0, dp(8));
        content.addView(download, downloadParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this, ThemePalette.dialogThemeRes(this))
                .setTitle("相册 · 作品与文件中控")
                .setView(scroll)
                .setPositiveButton("知道了", null).show();
    }

    private void openReleasePage() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateEndpoint.RELEASE_PAGE));
        if (intent.resolveActivity(getPackageManager()) == null) {
            toast("没有可用的浏览器，无法打开发布页");
            return;
        }
        try {
            startActivity(intent);
            DiagnosticLog.write(this, "manual_download_page_opened", UpdateEndpoint.RELEASE_PAGE);
        } catch (ActivityNotFoundException error) {
            toast("没有可用的浏览器，无法打开发布页");
        }
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("相册诊断信息", DiagnosticLog.snapshot(this)));
        toast("诊断信息已复制");
    }

    private String currentPathName() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String name = prefs.getString("libraryTreeName", "");
        if (!name.isEmpty()) return name;
        String uri = prefs.getString("libraryTreeUri", "");
        return uri.isEmpty() ? "未设置" : treeName(Uri.parse(uri));
    }

    private String treeName(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int slash = id.lastIndexOf('/');
            String name = slash >= 0 ? id.substring(slash + 1) : id.substring(id.lastIndexOf(':') + 1);
            return name.isEmpty() ? "已设置" : name;
        } catch (Exception ignored) { return "已设置"; }
    }

    private TextView label(String value) {
        TextView v = text(value, 14, true);
        v.setTextColor(ThemePalette.getTextSecondary(this));
        v.setPadding(0, 0, 0, dp(7));
        return v;
    }

    private LinearLayout cleanupRow(String title, int hours) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.setBackground(round(ThemePalette.getCardBg(this), 14));
        row.setClickable(true);
        row.setFocusable(true);
        TextView titleView = text(title, 15, false);
        TextView timingView = text(cleanupTimingText(hours), 15, false);
        timingView.setTextColor(ThemePalette.getTextSecondary(this));
        timingView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(titleView, new LinearLayout.LayoutParams(0, -1, 1));
        row.addView(timingView, new LinearLayout.LayoutParams(-2, -1));
        return row;
    }

    private LinearLayout choiceRow(String title, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setMinimumHeight(dp(58));
        row.setBackground(round(ThemePalette.getCardBg(this), 16));
        row.setClickable(true);
        row.setFocusable(true);
        TextView titleView = text(title, 15, false);
        titleView.setSingleLine(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.rightMargin = dp(12);
        TextView valueView = text(value, 13, false);
        valueView.setTextColor(ThemePalette.getTextSecondary(this));
        valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        valueView.setMaxLines(2);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(titleView, titleLp);
        row.addView(valueView, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private String cleanupTimingText(int hours) {
        String timing = hours == CleanupSettings.MANUAL ? "手动"
                : hours == 0 ? "立刻" : hours + " 小时后";
        return timing + "  ›";
    }

    private int toStoredMinutes(int hours) {
        return hours == CleanupSettings.MANUAL ? CleanupSettings.MANUAL : hours * 60;
    }

    private SettingSwitchRow settingSwitch(String title, String detail, boolean checked) {
        return new SettingSwitchRow(title, detail, checked);
    }

    private final class SettingSwitchRow extends LinearLayout {
        private final LinearLayout copy;
        private final TextView detailView;
        private final IOSSwitch control;

        SettingSwitchRow(String title, String detail, boolean checked) {
            super(SettingsActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(10), dp(14), dp(10));
            setMinimumHeight(dp(72));
            setBackground(round(ThemePalette.getCardBg(SettingsActivity.this), 16));
            copy = new LinearLayout(SettingsActivity.this);
            copy.setOrientation(VERTICAL);
            TextView titleView = text(title, 15, false);
            detailView = text(detail, 12, false);
            detailView.setTextColor(ThemePalette.getTextSecondary(SettingsActivity.this));
            detailView.setLineSpacing(dp(1), 1f);
            detailView.setMaxLines(2);
            copy.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.setMargins(0, dp(3), 0, 0);
            copy.addView(detailView, detailParams);
            addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

            control = new IOSSwitch();
            control.setChecked(checked);
            LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(dp(56), dp(40));
            controlParams.setMargins(dp(12), 0, 0, 0);
            addView(control, controlParams);
            setOnClickListener(v -> control.toggle());
        }

        void setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener listener) {
            control.setOnCheckedChangeListener(listener);
        }

        void setDetail(String detail) {
            detailView.setText(detail);
        }

        void setOnRowBodyClickListener(View.OnClickListener bodyListener) {
            if (bodyListener == null) {
                copy.setClickable(false);
                copy.setOnClickListener(null);
                setOnClickListener(v -> control.toggle());
                return;
            }
            setOnClickListener(null);
            setClickable(false);
            copy.setClickable(true);
            copy.setOnClickListener(bodyListener);
        }

        void setChecked(boolean checked) {
            control.setChecked(checked);
        }
    }

    /** A complete, vendor-independent iOS-style capsule switch. */
    private final class IOSSwitch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean checked;
        private float position;
        private CompoundButton.OnCheckedChangeListener listener;
        private ValueAnimator animator;

        IOSSwitch() {
            super(SettingsActivity.this);
            setClickable(true);
            setFocusable(true);
            setContentDescription("开关");
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setOnClickListener(v -> toggle());
        }

        void setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener value) {
            listener = value;
        }

        void setChecked(boolean value) {
            if (checked == value && position == (value ? 1f : 0f)) return;
            checked = value;
            position = value ? 1f : 0f;
            invalidate();
        }

        void toggle() {
            checked = !checked;
            animateTo(checked ? 1f : 0f);
            if (listener != null) listener.onCheckedChanged(null, checked);
            announceForAccessibility(checked ? "已打开" : "已关闭");
        }

        private void animateTo(float target) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(position, target);
            animator.setDuration(180);
            animator.addUpdateListener(value -> {
                position = (float) value.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float left = 2f * density;
            float top = 4f * density;
            float right = getWidth() - 2f * density;
            float bottom = getHeight() - 4f * density;
            float radius = (bottom - top) / 2f;
            track.set(left, top, right, bottom);

            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.FILL);
            int inactiveTrack = ThemePalette.isDarkMode(SettingsActivity.this) ? Color.rgb(58, 58, 60) : Color.rgb(209, 209, 214);
            paint.setColor(blend(inactiveTrack, Color.rgb(52, 199, 89), position));
            canvas.drawRoundRect(track, radius, radius, paint);

            float inset = 2f * density;
            float thumbRadius = radius - inset;
            float startX = left + radius;
            float endX = right - radius;
            float centerX = startX + (endX - startX) * position;
            float centerY = (top + bottom) / 2f;
            paint.setColor(Color.WHITE);
            paint.setShadowLayer(2.5f * density, 0, 1f * density, 0x55000000);
            canvas.drawCircle(centerX, centerY, thumbRadius, paint);
            paint.clearShadowLayer();
        }

        private int blend(int from, int to, float amount) {
            int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
            int green = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
            int blue = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
            return Color.rgb(red, green, blue);
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(ThemePalette.getTextPrimary(this));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String value, boolean primary) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? Color.WHITE : ThemePalette.getTextPrimary(this));
        b.setBackground(round(primary ? ThemePalette.getBrandPrimary() : ThemePalette.getCardBg(this), 14));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        return b;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.setMargins(left, top, right, bottom);
        return p;
    }

    private LinearLayout.LayoutParams settingMargins(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, bottom);
        return p;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
