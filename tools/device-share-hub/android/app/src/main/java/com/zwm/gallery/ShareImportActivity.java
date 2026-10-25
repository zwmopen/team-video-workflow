package com.zwm.gallery;

import android.app.Activity;
import android.content.ClipData;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android system Share target：从外部应用（相册/文件管理器）分享内容到本 App。
 *
 * DSH-196（视觉与交互重做）：
 * - 旧版是整屏 Activity + 系统默认白底 Button，深色模式下「一黑一白」且质感与主界面脱节；
 * - 新版改为「居中模态卡片」：遮罩 + 圆角卡片，两步流程 —— ① 选设备 ② 确认「是否发送到该设备」，
 *   发送过程有进度反馈，全部使用 ThemePalette 运行时主题（跟随系统/手动浅色/手动深色）。
 */
public final class ShareImportActivity extends Activity {
    /** 卡片三步状态：选设备 → 确认 → 发送中。 */
    private static final int STEP_PICK = 0;
    private static final int STEP_CONFIRM = 1;
    private static final int STEP_SENDING = 2;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> uris = new ArrayList<>();
    private String sharedText = "";
    private PeerDevice selectedPeer;

    private int step = STEP_PICK;
    private View cardView;
    private FrameLayout contentArea;
    private ProgressBar progress;
    private TextView status;
    private TextView hint;
    private boolean receiverRegistered;
    private boolean finished;
    /** DSH-196：信标监听是否由本界面拉起（退出时只停自己拉的，别误杀主界面的）。 */
    private boolean beaconListenerStartedByUs;
    private OnlineGalleryClient beaconClient;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshPeers = new Runnable() {
        @Override public void run() {
            renderStep();
            refreshHandler.postDelayed(this, 2000);
        }
    };
    private final BroadcastReceiver peerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            renderStep();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        readSharedContent(getIntent());
        // DSH-193/DSH-196：系统栏与窗口背景都跟随主题，杜绝深色模式下的白边/露白
        ThemePalette.applySystemBarAppearance(this);
        setContentView(ScreenInsets.protect(buildUi()));
        startService(new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_START));
        // DSH-195：一进界面就主动刷新（含拉取电脑服务端设备目录，广播被拦时靠它兜底）
        OnlineService.requestImmediateDiscovery(this);
        // DSH-196：信标监听本来只有 MainActivity 拉。从系统分享面板冷启动（重装后/杀进程后）
        // 学不到电脑地址 ⇒ pcServerBaseUrl 为空 ⇒ 服务端目录兜底没有 URL，列表永远空。
        // 这里补上：用本界面自己的 client 拉信标（端口被主界面占用时会静默失败，不崩），
        // 学到地址后立刻重拉一次设备目录；退出时只停自己的监听。
        beaconClient = new OnlineGalleryClient(this);
        beaconListenerStartedByUs = true;
        beaconClient.startBeaconListener(() -> OnlineService.requestImmediateDiscovery(this));
        renderStep();
        animateCardIn();
    }

    @Override protected void onResume() {
        super.onResume();
        renderStep();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(OnlineService.ACTION_PEERS_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(peerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerLegacyReceiver(filter);
        }
        receiverRegistered = true;
        refreshHandler.postDelayed(refreshPeers, 1000);
    }

    @SuppressWarnings("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) {
        registerReceiver(peerReceiver, filter);
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(peerReceiver);
            receiverRegistered = false;
        }
        refreshHandler.removeCallbacks(refreshPeers);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (beaconListenerStartedByUs && beaconClient != null) {
            beaconClient.stopBeaconListener();
            beaconListenerStartedByUs = false;
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (step == STEP_CONFIRM) {
            step = STEP_PICK;
            renderStep();
            return;
        }
        finishWithAnimation();
    }

    // ---------------------------------------------------------------- UI 构建

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        // 遮罩：点外部取消（确认步先返回选择步）
        View backdrop = new View(this);
        backdrop.setBackgroundColor(ThemePalette.getBackdropColor());
        backdrop.setOnClickListener(v -> {
            if (step == STEP_CONFIRM) {
                step = STEP_PICK;
                renderStep();
            } else if (step == STEP_PICK) {
                finishWithAnimation();
            }
        });
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setElevation(dp(8));
        card.setBackground(ThemePalette.roundWithStroke(
                ThemePalette.getBgSecondary(this), 20,
                ThemePalette.getCardStroke(this), 1,
                getResources().getDisplayMetrics().density));
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);

        // 头部：图标 + 标题/副标题 + 关闭
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = new TextView(this);
        badge.setText("↑");
        badge.setTextSize(16);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(Color.WHITE);
        badge.setBackground(ThemePalette.round(ThemePalette.getBrandPrimary(), 9,
                getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        header.addView(badge, badgeParams);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleBoxParams =
                new LinearLayout.LayoutParams(0, -2, 1f);
        titleBoxParams.setMargins(dp(10), 0, dp(6), 0);
        header.addView(titleBox, titleBoxParams);
        TextView title = text("发送到在线设备", 16, true);
        titleBox.addView(title);
        TextView subtitle = text(fileSummary(), 11, false);
        subtitle.setTextColor(ThemePalette.getTextSecondary(this));
        titleBox.addView(subtitle);

        TextView close = text("✕", 13, true);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(ThemePalette.getTextSecondary(this));
        close.setBackground(ThemePalette.round(ThemePalette.getPillBg(this), 14,
                getResources().getDisplayMetrics().density));
        close.setOnClickListener(v -> finishWithAnimation());
        header.addView(close, new LinearLayout.LayoutParams(dp(28), dp(28)));
        card.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(ThemePalette.getDividerColor(this));
        card.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        // 内容区：三种状态切换
        contentArea = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, -2);
        contentParams.topMargin = dp(10);
        card.addView(contentArea, contentParams);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                ThemePalette.getBrandPrimary()));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ThemePalette.getDividerColor(this)));
        progress.setVisibility(View.GONE);
        card.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));

        status = text("", 13, false);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(10);
        card.addView(status, statusParams);

        hint = text("未找到设备？请保持电脑端「在线相册服务」运行，列表会经电脑中转发现",
                11, false);
        hint.setTextColor(ThemePalette.getTextMuted(this));
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(10);
        card.addView(hint, hintParams);

        // 小弹窗：固定窄宽居中（用户要「小小的小卡片」，不是占满屏幕的宽面板）
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dp(300), -2);
        cardParams.gravity = Gravity.CENTER;
        root.addView(card, cardParams);
        cardView = card;
        return root;
    }

    /** 根据当前 step 渲染内容区。 */
    private void renderStep() {
        if (contentArea == null || finished) return;
        contentArea.removeAllViews();
        switch (step) {
            case STEP_PICK:
            default:
                progress.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                hint.setVisibility(View.VISIBLE);
                contentArea.addView(buildPickView());
                break;
            case STEP_CONFIRM:
                hint.setVisibility(View.GONE);
                contentArea.addView(buildConfirmView());
                break;
            case STEP_SENDING:
                hint.setVisibility(View.GONE);
                contentArea.addView(buildSendingView());
                break;
        }
    }

    private View buildPickView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        List<PeerDevice> peers = OnlineService.peers();
        // 兜底提示只在真的没设备时才出现，避免找到设备还问「未找到设备？」
        hint.setVisibility(peers.isEmpty() ? View.VISIBLE : View.GONE);
        if (peers.isEmpty()) {
            TextView empty = text("暂未发现在线设备，正在补位探测…", 13, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(ThemePalette.getTextSecondary(this));
            empty.setPadding(dp(8), dp(22), dp(8), dp(22));
            empty.setBackground(ThemePalette.round(ThemePalette.getCardBg(this), 14,
                    getResources().getDisplayMetrics().density));
            box.addView(empty);
            return box;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (PeerDevice peer : peers) {
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(8);
            list.addView(deviceRow(peer, false), rowParams);
        }
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        // 限制列表高度，避免设备过多时卡片顶出屏幕
        scroll.post(() -> {
            int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.38);
            if (scroll.getHeight() > maxHeight) {
                ViewGroup.LayoutParams params = scroll.getLayoutParams();
                params.height = maxHeight;
                scroll.setLayoutParams(params);
            }
        });
        return box;
    }

    private View buildConfirmView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView question = text("发送到以下设备？", 13, true);
        box.addView(question, new LinearLayout.LayoutParams(-1, -2));
        if (selectedPeer != null) {
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.topMargin = dp(8);
            box.addView(deviceRow(selectedPeer, true), rowParams);
        }
        TextView files = text("将发送 " + fileSummary(), 11, false);
        files.setTextColor(ThemePalette.getTextSecondary(this));
        LinearLayout.LayoutParams filesParams = new LinearLayout.LayoutParams(-1, -2);
        filesParams.topMargin = dp(8);
        box.addView(files, filesParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.topMargin = dp(12);
        box.addView(buttons, buttonsParams);
        Button cancel = pillButton("取消", ThemePalette.getPillBg(this),
                ThemePalette.getPillText(this));
        cancel.setOnClickListener(v -> {
            step = STEP_PICK;
            renderStep();
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button send = pillButton("发送", ThemePalette.getBrandPrimary(), Color.WHITE);
        send.setOnClickListener(v -> startSend());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        sendParams.leftMargin = dp(8);
        buttons.addView(send, sendParams);
        return box;
    }

    private View buildSendingView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView preparing = text("正在发送…", 14, true);
        preparing.setGravity(Gravity.CENTER);
        box.addView(preparing);
        return box;
    }

    /** 设备行：头像 + 名称/型号 + 选中高亮。 */
    private View deviceRow(PeerDevice peer, boolean chosen) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int stroke = chosen ? ThemePalette.getBrandPrimary()
                : ThemePalette.getCardStroke(this);
        row.setBackground(ThemePalette.roundWithStroke(
                ThemePalette.getCardBg(this), 14, stroke, chosen ? 2 : 1,
                getResources().getDisplayMetrics().density));
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (chosen) {
            row.setClickable(false);
            row.setFocusable(false);
        } else {
            row.setOnClickListener(v -> {
                selectedPeer = peer;
                step = STEP_CONFIRM;
                renderStep();
            });
        }

        TextView avatar = new TextView(this);
        avatar.setText(avatarLetter(peer.name));
        avatar.setTextSize(14);
        avatar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(ThemePalette.getBrandLightText(this));
        avatar.setBackground(ThemePalette.round(ThemePalette.getBrandLightBg(this), 18,
                getResources().getDisplayMetrics().density));
        row.addView(avatar, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1f);
        textsParams.setMargins(dp(10), 0, dp(4), 0);
        row.addView(texts, textsParams);
        TextView name = text(peer.name, 14, true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView model = text(peer.model + " · 在线", 11, false);
        model.setTextColor(ThemePalette.getTextSecondary(this));
        model.setMaxLines(1);
        model.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(model);

        TextView chevron = text("›", 16, true);
        chevron.setTextColor(ThemePalette.getTextMuted(this));
        row.addView(chevron, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private Button pillButton(String label, int bg, int fg) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(fg);
        button.setBackground(ThemePalette.round(bg, 12,
                getResources().getDisplayMetrics().density));
        return button;
    }

    private void animateCardIn() {
        if (cardView == null) return;
        cardView.setAlpha(0f);
        cardView.setScaleX(0.92f);
        cardView.setScaleY(0.92f);
        cardView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
    }

    private void finishWithAnimation() {
        if (finished) return;
        finished = true;
        if (cardView == null) {
            finish();
            return;
        }
        cardView.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(140)
                .withEndAction(this::finish).start();
    }

    // ---------------------------------------------------------------- 发送逻辑

    private void startSend() {
        if (selectedPeer == null) return;
        step = STEP_SENDING;
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setVisibility(View.VISIBLE);
        status.setText("准备发送到“" + selectedPeer.name + "”…");
        status.setTextColor(ThemePalette.getAccentText(this));
        renderStep();
        worker.execute(() -> {
            try {
                TransferClient client = new TransferClient(getContentResolver(), getCacheDir());
                if (uris.isEmpty()) {
                    File textFile = new File(getCacheDir(), "分享文字.txt");
                    try (FileOutputStream output = new FileOutputStream(textFile, false)) {
                        output.write(sharedText.getBytes(StandardCharsets.UTF_8));
                    }
                    client.sendLocalFiles(selectedPeer, java.util.Collections.singletonList(textFile),
                            sharedText, false, this::updateProgress);
                } else {
                    client.sendFiles(selectedPeer, uris, sharedText, shouldAutoShare(),
                            this::updateProgress);
                }
                OperationLog.add(this, "系统分享发送完成", selectedPeer.name);
                runOnUiThread(() -> {
                    status.setText("✓ 已发送到“" + selectedPeer.name + "”");
                    status.setTextColor(ThemePalette.getBrandLightText(this));
                    progress.setProgress(100);
                    refreshHandler.postDelayed(this::finishWithAnimation, 900);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "请检查两台设备的 Wi‑Fi" : error.getMessage();
                OperationLog.add(this, "系统分享发送失败", selectedPeer.name + "：" + message);
                runOnUiThread(() -> {
                    step = STEP_PICK;
                    renderStep();
                    progress.setVisibility(View.GONE);
                    status.setVisibility(View.VISIBLE);
                    status.setText("发送失败：" + message);
                    status.setTextColor(ThemePalette.getDangerColor(this));
                });
            }
        });
    }

    private boolean shouldAutoShare() {
        if (sharedText.isEmpty() || uris.isEmpty()) return false;
        for (Uri uri : uris) {
            String mime = getContentResolver().getType(uri);
            if (mime == null || !mime.startsWith("image/")) return false;
        }
        return true;
    }

    private void updateProgress(int value, String message) {
        runOnUiThread(() -> {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(value);
            if (message != null && !message.isEmpty()) status.setText(message);
        });
    }

    // ---------------------------------------------------------------- 内容读取

    private void readSharedContent(Intent intent) {
        LinkedHashSet<Uri> values = new LinkedHashSet<>();
        if (intent != null) {
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (streams != null) values.addAll(streams);
            } else {
                Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream != null) values.add(stream);
            }
            ClipData clip = intent.getClipData();
            if (clip != null) {
                for (int index = 0; index < clip.getItemCount(); index++) {
                    Uri uri = clip.getItemAt(index).getUri();
                    if (uri != null) values.add(uri);
                }
            }
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            sharedText = text == null ? "" : text.toString().trim();
        }
        uris.addAll(values);
    }

    private String fileSummary() {
        StringBuilder builder = new StringBuilder();
        if (!uris.isEmpty()) {
            builder.append(uris.size()).append(" 个文件");
            String firstName = queryName(uris.get(0));
            if (firstName != null) builder.append("（").append(firstName).append(" 等）");
        }
        if (!sharedText.isEmpty()) {
            if (builder.length() > 0) builder.append("＋文字");
            else builder.append("分享文字");
        }
        if (builder.length() == 0) builder.append("空内容");
        return builder.toString();
    }

    private String queryName(Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) {
            String path = uri.getLastPathSegment();
            return path == null ? null : new File(path).getName();
        }
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? null : last;
    }

    private static String avatarLetter(String name) {
        if (name == null || name.trim().isEmpty()) return "📱";
        String trimmed = name.trim();
        return String.valueOf(trimmed.charAt(0)).toUpperCase();
    }

    // ---------------------------------------------------------------- 小工具

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(ThemePalette.getTextPrimary(this));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
