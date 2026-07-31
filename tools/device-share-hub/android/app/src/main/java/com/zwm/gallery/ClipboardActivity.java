package com.zwm.gallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared clipboard and reusable phrases for discovered devices in the same trusted group. */
public final class ClipboardActivity extends Activity {
    static final String EXTRA_ADD_PHRASE = "addPhrase";
    private static final String PREFS = "device_share";
    private static final int REQUEST_SCREENSHOT_IMAGES = 91;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout historyContainer;
    private LinearLayout phraseContainer;
    private TextView status;
    private Button screenshotTarget;
    private Button latestToggle;
    private boolean latestExpanded;
    private String latestRenderedText = "";
    private boolean receiverRegistered;

    private final BroadcastReceiver clipboardReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("共享剪切板");
        setContentView(ScreenInsets.protect(buildUi()));
        startService(new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_START));
        captureAndSyncCurrentClipboard(false);
        render();
        if (getIntent().getBooleanExtra(EXTRA_ADD_PHRASE, false)) {
            getWindow().getDecorView().post(() -> editPhrase(null));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(OnlineService.ACTION_CLIPBOARD_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(clipboardReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerLegacyReceiver(filter);
        }
        receiverRegistered = true;
        render();
        getWindow().getDecorView().post(this::promptPendingScreenshot);
    }

    @SuppressWarnings("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) {
        registerReceiver(clipboardReceiver, filter);
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(clipboardReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(Color.rgb(246, 244, 240));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("共享剪切板", 25, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button sync = button("读取并同步", true);
        sync.setOnClickListener(v -> captureAndSyncCurrentClipboard(true));
        titleRow.addView(sync, new LinearLayout.LayoutParams(dp(128), dp(48)));
        root.addView(titleRow);

        TextView hint = text(
                "点悬浮按钮后会读取当前剪切板，并同步到同一 Wi‑Fi 下已登记的在线手机。"
                        + "Android 不允许普通应用在后台静默读取其他 App 的剪切板。",
                12, false);
        hint.setTextColor(Color.rgb(100, 97, 92));
        root.addView(hint, margins(0, dp(6), 0, dp(12)));

        screenshotTarget = button(screenshotTargetText(), false);
        screenshotTarget.setOnClickListener(v -> chooseScreenshotTarget());
        root.addView(screenshotTarget, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.VERTICAL);
        columns.setPadding(0, dp(12), 0, 0);

        LinearLayout historyColumn = new LinearLayout(this);
        historyColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout latestTitle = new LinearLayout(this);
        latestTitle.setOrientation(LinearLayout.HORIZONTAL);
        latestTitle.setGravity(Gravity.CENTER_VERTICAL);
        latestTitle.addView(sectionTitle("最新剪切"),
                new LinearLayout.LayoutParams(0, -2, 1));
        latestToggle = button("展开", false);
        latestToggle.setContentDescription("展开或收起最新剪切内容");
        latestToggle.setVisibility(View.GONE);
        latestToggle.setOnClickListener(v -> {
            latestExpanded = !latestExpanded;
            render();
        });
        latestTitle.addView(latestToggle, new LinearLayout.LayoutParams(dp(68), dp(40)));
        historyColumn.addView(latestTitle);
        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        historyColumn.addView(historyContainer, new LinearLayout.LayoutParams(-1, -2));
        columns.addView(historyColumn, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout phraseColumn = new LinearLayout(this);
        phraseColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout phraseTitle = new LinearLayout(this);
        phraseTitle.setOrientation(LinearLayout.HORIZONTAL);
        phraseTitle.setGravity(Gravity.CENTER_VERTICAL);
        phraseTitle.addView(sectionTitle("固定常用语"), new LinearLayout.LayoutParams(0, -2, 1));
        Button add = button("＋", true);
        add.setContentDescription("新增常用语");
        add.setOnClickListener(v -> editPhrase(null));
        phraseTitle.addView(add, new LinearLayout.LayoutParams(dp(44), dp(40)));
        phraseColumn.addView(phraseTitle);
        phraseContainer = new LinearLayout(this);
        phraseContainer.setOrientation(LinearLayout.VERTICAL);
        phraseColumn.addView(phraseContainer, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams phraseParams = new LinearLayout.LayoutParams(-1, -2);
        phraseParams.setMargins(0, dp(8), 0, 0);
        columns.addView(phraseColumn, phraseParams);
        ScrollView contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        contentScroll.addView(columns, new ScrollView.LayoutParams(-1, -2));
        root.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        status = text("正在读取…", 12, false);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.rgb(58, 104, 78));
        status.setPadding(dp(10), dp(9), dp(10), dp(9));
        status.setBackground(round(Color.rgb(229, 239, 232), 13));
        root.addView(status, margins(0, dp(10), 0, 0));
        return root;
    }

    private void captureAndSyncCurrentClipboard(boolean showResult) {
        ClipboardManager manager = getSystemService(ClipboardManager.class);
        ClipData clip = manager == null ? null : manager.getPrimaryClip();
        CharSequence value = clip == null || clip.getItemCount() == 0
                ? null : clip.getItemAt(0).coerceToText(this);
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) {
            if (showResult) toast("当前剪切板没有文字");
            return;
        }
        syncClipboardText(text, showResult);
    }

    private void syncClipboardText(String text, boolean showResult) {
        status.setText("正在同步当前剪切板…");
        worker.execute(() -> {
            try {
                SharedClipboardStore store = store();
                SharedClipboardStore.Item newest = store.newestClipboard();
                if (newest == null || !text.equals(newest.text)) {
                    store.add(deviceId(), SharedClipboardStore.KIND_CLIPBOARD,
                            text, store.nextTimestamp(System.currentTimeMillis()));
                }
                store.retainNewestClipboardOnly();
                int synced = syncAll(store.syncSnapshot());
                notifyClipboardChanged();
                runOnUiThread(() -> {
                    render();
                    status.setText(synced == 0
                            ? "已保存；暂无其他在线手机"
                            : "已同步到 " + synced + " 台在线手机");
                    if (showResult) toast("剪切板已同步");
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("同步失败：" + message(error)));
            }
        });
    }

    private int syncAll(List<SharedClipboardStore.Item> items) {
        int synced = 0;
        for (PeerDevice peer : OnlineService.peers()) {
            if (peer.id.startsWith("windows-") || "Windows PC".equals(peer.model)) continue;
            try {
                new TransferClient(getContentResolver(), getCacheDir())
                        .syncClipboard(peer, deviceId(), items);
                synced++;
            } catch (Exception error) {
                DiagnosticLog.write(this, "clipboard_sync_failed",
                        peer.name + " " + message(error));
            }
        }
        return synced;
    }

    private void render() {
        if (historyContainer == null || phraseContainer == null) return;
        worker.execute(() -> {
            try {
                List<SharedClipboardStore.Item> history =
                        store().visible(SharedClipboardStore.KIND_CLIPBOARD);
                List<SharedClipboardStore.Item> phrases =
                        store().visible(SharedClipboardStore.KIND_PHRASE);
                runOnUiThread(() -> renderLists(history, phrases));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("读取失败：" + message(error)));
            }
        });
    }

    private void renderLists(
            List<SharedClipboardStore.Item> history,
            List<SharedClipboardStore.Item> phrases) {
        historyContainer.removeAllViews();
        phraseContainer.removeAllViews();
        String latestText = history.isEmpty() ? "" : history.get(0).text;
        if (!latestText.equals(latestRenderedText)) {
            latestRenderedText = latestText;
            latestExpanded = false;
        }
        boolean collapsible = ClipboardDisplayPolicy.shouldCollapse(latestText);
        latestToggle.setVisibility(collapsible ? View.VISIBLE : View.GONE);
        latestToggle.setText(latestExpanded ? "收起" : "展开");
        if (history.isEmpty()) historyContainer.addView(empty("暂无记录"));
        else {
            Button latest = itemButton(history.get(0), false);
            latest.setMaxLines(latestExpanded ? Integer.MAX_VALUE
                    : ClipboardDisplayPolicy.COLLAPSED_LINES);
            latest.setEllipsize(latestExpanded
                    ? null : android.text.TextUtils.TruncateAt.END);
            historyContainer.addView(latest, itemMargins());
        }
        if (phrases.isEmpty()) phraseContainer.addView(empty("点右上角＋添加"));
        else for (SharedClipboardStore.Item item : phrases) {
            phraseContainer.addView(itemButton(item, true), itemMargins());
        }
        status.setText("最新剪切 " + (history.isEmpty() ? 0 : 1)
                + " 条 · 固定常用语 " + phrases.size() + " 条");
        screenshotTarget.setText(screenshotTargetText());
    }

    private Button itemButton(SharedClipboardStore.Item item, boolean phrase) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setText(item.text);
        button.setTextSize(13);
        button.setTextColor(Color.rgb(42, 42, 39));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setBackground(round(Color.WHITE, 12));
        button.setMinHeight(dp(54));
        button.setOnClickListener(v -> copy(item.text));
        button.setOnLongClickListener(v -> {
            if (phrase) editPhrase(item);
            else confirmDelete(item);
            return true;
        });
        return button;
    }

    private void editPhrase(SharedClipboardStore.Item item) {
        EditText input = new EditText(this);
        input.setMinLines(3);
        input.setText(item == null ? "" : item.text);
        input.setSelection(input.length());
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(item == null ? "新增常用语" : "修改常用语")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) {
                        toast("常用语不能为空");
                        return;
                    }
                    worker.execute(() -> {
                        try {
                            SharedClipboardStore store = store();
                            if (item == null) {
                                store.add(deviceId(), SharedClipboardStore.KIND_PHRASE,
                                        value, store.nextTimestamp(System.currentTimeMillis()));
                            } else {
                                store.put(item.id, item.kind, value, System.currentTimeMillis());
                            }
                            int synced = syncAll(store.syncSnapshot());
                            notifyClipboardChanged();
                            runOnUiThread(() -> {
                                render();
                                status.setText("常用语已保存，同步 " + synced + " 台");
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> status.setText("保存失败：" + message(error)));
                        }
                    });
                });
        if (item != null) builder.setNeutralButton("删除", (dialog, which) -> delete(item));
        builder.show();
    }

    private void confirmDelete(SharedClipboardStore.Item item) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条剪切板记录？")
                .setMessage(item.text)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> delete(item))
                .show();
    }

    private void delete(SharedClipboardStore.Item item) {
        worker.execute(() -> {
            try {
                SharedClipboardStore store = store();
                store.delete(item.id, System.currentTimeMillis());
                int synced = syncAll(store.syncSnapshot());
                notifyClipboardChanged();
                runOnUiThread(() -> {
                    render();
                    status.setText("已删除并同步到 " + synced + " 台");
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("删除失败：" + message(error)));
            }
        });
    }

    private void chooseScreenshotTarget() {
        List<PeerDevice> peers = OnlineService.peers();
        if (peers.isEmpty()) {
            toast("暂无在线设备");
            return;
        }
        String[] names = new String[peers.size()];
        for (int index = 0; index < peers.size(); index++) {
            names[index] = peers.get(index).name + " · " + peers.get(index).model;
        }
        new AlertDialog.Builder(this)
                .setTitle("截图发送到")
                .setItems(names, (dialog, which) -> {
                    PeerDevice peer = peers.get(which);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("screenshotTargetPeerId", peer.id)
                            .putString("screenshotTargetPeerName", peer.name)
                            .putBoolean("screenshotSyncEnabled", true)
                            .putBoolean("screenshotAutoSendEnabled", true)
                            .apply();
                    screenshotTarget.setText(screenshotTargetText());
                    ensureScreenshotPermission();
                    toast("截图提醒将发送到“" + peer.name + "”");
                })
                .show();
    }

    private void promptPendingScreenshot() {
        String uri = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("pendingScreenshotUri", "");
        if (uri.isEmpty() || isFinishing()) return;
        List<PeerDevice> peers = OnlineService.peers();
        if (peers.isEmpty()) {
            status.setText("有一张截图待发送；暂无在线设备");
            return;
        }
        String[] names = new String[peers.size()];
        for (int index = 0; index < peers.size(); index++) {
            names[index] = peers.get(index).name + " · " + peers.get(index).model;
        }
        new AlertDialog.Builder(this)
                .setTitle("发送刚才的截图")
                .setItems(names, (dialog, which) -> {
                    PeerDevice peer = peers.get(which);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .remove("pendingScreenshotUri").apply();
                    sendBroadcast(new Intent(this, ScreenshotSendReceiver.class)
                            .setAction(ScreenshotSendReceiver.ACTION_SEND)
                            .putExtra(ScreenshotSendReceiver.EXTRA_URI, uri)
                            .putExtra(ScreenshotSendReceiver.EXTRA_PEER_ID, peer.id));
                })
                .setNegativeButton("暂不发送", null)
                .setNeutralButton("忽略这张", (dialog, which) ->
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .remove("pendingScreenshotUri").apply())
                .show();
    }

    private void ensureScreenshotPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        boolean needsImages = checkSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED;
        boolean needsNotifications = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        if (needsImages || needsNotifications) {
            requestPermissions(Build.VERSION.SDK_INT >= 33
                            ? new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                                    Manifest.permission.POST_NOTIFICATIONS}
                            : new String[]{permission},
                    REQUEST_SCREENSHOT_IMAGES);
            return;
        }
        startService(new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_REFRESH_SCREENSHOTS));
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_SCREENSHOT_IMAGES) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            granted &= result == PackageManager.PERMISSION_GRANTED;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("screenshotSyncEnabled", granted).apply();
        if (granted) {
            startService(new Intent(this, OnlineService.class)
                    .setAction(OnlineService.ACTION_REFRESH_SCREENSHOTS));
        }
        toast(granted ? "截图提醒已开启" : "未允许读取图片，暂时无法识别截图");
    }

    private String screenshotTargetText() {
        String name = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("screenshotTargetPeerName", "");
        return name.isEmpty() ? "设置截图接收设备" : "截图接收设备：" + name;
    }

    private void copy(String value) {
        ClipboardManager manager = getSystemService(ClipboardManager.class);
        if (manager != null) manager.setPrimaryClip(
                ClipData.newPlainText("共享剪切板", value));
        toast("已复制，正在同步");
        syncClipboardText(value == null ? "" : value.trim(), false);
    }

    private SharedClipboardStore store() throws Exception {
        SharedClipboardStore store = new SharedClipboardStore(
                new File(getFilesDir(), "shared-clipboard"));
        ClipboardDefaults.ensure(store);
        return store;
    }

    private void notifyClipboardChanged() {
        sendBroadcast(new Intent(OnlineService.ACTION_CLIPBOARD_CHANGED)
                .setPackage(getPackageName()));
        startService(new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_REFRESH_OVERLAY));
    }

    private String deviceId() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("deviceId", "device");
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 16, true);
        title.setPadding(dp(4), dp(8), dp(4), dp(8));
        return title;
    }

    private TextView empty(String value) {
        TextView view = text(value, 12, false);
        view.setTextColor(Color.GRAY);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(24), dp(8), dp(24));
        return view;
    }

    private LinearLayout.LayoutParams itemMargins() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(7));
        return params;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(45, 45, 42));
        button.setBackground(round(primary ? Color.rgb(54, 105, 72)
                : Color.rgb(232, 230, 225), 13));
        return button;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(35, 35, 33));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private String message(Exception error) {
        return error.getMessage() == null ? "未知错误" : error.getMessage();
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
