package com.zwm.gallery;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String PREFS = "device_share";
    private static final String PREF_TREE_URI = "libraryTreeUri";
    private static final int REQUEST_TREE = 61;
    public static volatile boolean isVisible;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout worksContainer;
    private TextView sourceText;
    private TextView statusText;
    private Button trashButton;
    private boolean showingTrash;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (OnlineService.ACTION_TASK_READY.equals(intent.getAction())) refreshWorks();
            if (OnlineService.ACTION_STATUS.equals(intent.getAction())) {
                String message = intent.getStringExtra("message");
                if (message != null) statusText.setText(message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureDeviceId();
        setContentView(buildUi());
        updateSourceLabel();
        requestNotificationPermission();
        startReceiver();
        refreshWorks();
        DiagnosticLog.write(this, "app_open", "album main opened");
    }

    @Override
    protected void onStart() {
        super.onStart();
        isVisible = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(OnlineService.ACTION_TASK_READY);
        filter.addAction(OnlineService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacyReceiver(filter);
        refreshWorks();
    }

    @SuppressWarnings("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) { registerReceiver(receiver, filter); }

    @Override
    protected void onStop() {
        isVisible = false;
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(20), pad, dp(36));
        root.setBackgroundColor(Color.rgb(246, 244, 240));

        TextView title = text("相册", 30, true);
        root.addView(title);
        TextView intro = text("电脑拖入一个压缩包，作品会自动排好。点一下作品：复制文案并打开分享。", 14, false);
        intro.setTextColor(Color.rgb(91, 91, 88));
        root.addView(intro, margins(0, dp(6), 0, dp(18)));

        LinearLayout sourceCard = card();
        sourceText = text("正在读取…", 15, true);
        sourceCard.addView(sourceText, new LinearLayout.LayoutParams(0, -2, 1));
        Button choose = smallButton("选择 Lark 文件夹", true);
        choose.setOnClickListener(v -> chooseFolder());
        sourceCard.addView(choose);
        root.addView(sourceCard, margins(0, 0, 0, dp(12)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = smallButton("刷新", false);
        refresh.setOnClickListener(v -> importSelectedTree());
        controls.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1));
        trashButton = smallButton("回收站", false);
        trashButton.setOnClickListener(v -> {
            showingTrash = !showingTrash;
            trashButton.setText(showingTrash ? "返回作品" : "回收站");
            refreshWorks();
        });
        LinearLayout.LayoutParams trashParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        trashParams.setMargins(dp(10), 0, 0, 0);
        controls.addView(trashButton, trashParams);
        root.addView(controls, margins(0, 0, 0, dp(18)));

        TextView heading = text("作品", 20, true);
        root.addView(heading, margins(0, 0, 0, dp(10)));
        worksContainer = new LinearLayout(this);
        worksContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(worksContainer);

        statusText = text("局域网接收已开启", 13, false);
        statusText.setTextColor(Color.rgb(90, 105, 96));
        statusText.setGravity(Gravity.CENTER);
        statusText.setBackground(round(Color.rgb(231, 239, 233), 16));
        statusText.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(statusText, margins(0, dp(18), 0, dp(8)));

        Button diagnostics = smallButton("复制诊断信息", false);
        diagnostics.setOnClickListener(v -> copyDiagnostics());
        root.addView(diagnostics, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView note = text("“已打开分享”只表示平台页面已打开；系统不会告诉相册是否真正发布成功。当天保留，次日进入回收站，7 天后清理。", 12, false);
        note.setTextColor(Color.GRAY);
        root.addView(note, margins(0, dp(12), 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private void refreshWorks() {
        worker.execute(() -> {
            try {
                WorkLibrary library = library();
                library.maintain(LocalDate.now());
                List<WorkLibrary.WorkEntry> entries = showingTrash ? library.listTrash() : library.listActive();
                runOnUiThread(() -> renderWorks(entries));
            } catch (Exception error) {
                DiagnosticLog.write(this, "library_refresh_failed", error.getMessage());
                runOnUiThread(() -> statusText.setText("读取作品失败：" + error.getMessage()));
            }
        });
    }

    private void renderWorks(List<WorkLibrary.WorkEntry> entries) {
        worksContainer.removeAllViews();
        if (entries.isEmpty()) {
            TextView empty = text(showingTrash ? "回收站是空的" : "还没有作品\n从电脑拖入 ZIP，或选择手机里的 Lark 文件夹", 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(14), dp(28), dp(14), dp(28));
            empty.setBackground(round(Color.WHITE, 18));
            worksContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (WorkLibrary.WorkEntry work : entries) worksContainer.addView(workCard(work), margins(0, 0, 0, dp(12)));
    }

    private View workCard(WorkLibrary.WorkEntry work) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(work.name, 18, true);
        card.addView(name);
        String detail = work.images.size() + " 张图片";
        if (!work.warning.isEmpty()) detail += " · " + work.warning;
        if (work.sharedDate != null) detail += " · 已打开分享";
        if (work.trashedDate != null) detail += " · 回收站保留 7 天";
        TextView meta = text(detail, 13, false);
        meta.setTextColor(work.sharedDate == null ? Color.GRAY : Color.rgb(67, 125, 84));
        card.addView(meta, margins(0, dp(5), 0, dp(12)));
        Button action = smallButton(showingTrash ? "恢复" : "复制文案并分享", !showingTrash);
        action.setOnClickListener(v -> {
            if (showingTrash) restore(work.id);
            else startActivity(new Intent(this, ShareActivity.class).putExtra(ShareActivity.EXTRA_WORK_ID, work.id));
        });
        card.addView(action, new LinearLayout.LayoutParams(-1, dp(46)));
        return card;
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
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_TREE_URI, tree.toString()).apply();
            updateSourceLabel();
            importSelectedTree();
        } catch (Exception error) {
            statusText.setText("无法保存文件夹权限：" + error.getMessage());
        }
    }

    private void importSelectedTree() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, "");
        if (stored.isEmpty()) {
            refreshWorks();
            toast("请先选择 Lark 文件夹");
            return;
        }
        statusText.setText("正在读取 Lark 文件夹…");
        worker.execute(() -> {
            try {
                DocumentTreeImporter.ImportResult result = DocumentTreeImporter.importTree(
                        getContentResolver(), Uri.parse(stored), library(), new File(getCacheDir(), "tree-import"));
                DiagnosticLog.write(this, "tree_import", "detected=" + result.detected + " imported=" + result.imported + " skipped=" + result.skipped);
                runOnUiThread(() -> {
                    statusText.setText(result.imported > 0
                            ? "新增 " + result.imported + " 个作品"
                            : "已是最新，共识别 " + result.detected + " 个作品");
                    refreshWorks();
                });
            } catch (Exception error) {
                DiagnosticLog.write(this, "tree_import_failed", error.getMessage());
                runOnUiThread(() -> statusText.setText("读取失败：" + error.getMessage()));
            }
        });
    }

    private void restore(String id) {
        worker.execute(() -> {
            try {
                library().restore(id);
                runOnUiThread(() -> { toast("已恢复"); refreshWorks(); });
            } catch (Exception error) {
                runOnUiThread(() -> toast("恢复失败：" + error.getMessage()));
            }
        });
    }

    private WorkLibrary library() throws Exception { return new WorkLibrary(new File(getFilesDir(), "work-library")); }

    private void updateSourceLabel() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, "");
        sourceText.setText(stored.isEmpty() ? "电脑接收库" : "电脑接收库 + Lark");
    }

    private void startReceiver() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("serviceEnabled", true).apply();
        startForegroundService(new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_START));
    }

    private void ensureDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getString("deviceId", "").isEmpty()) {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String id = androidId == null || androidId.isEmpty() ? UUID.randomUUID().toString() : androidId;
            prefs.edit().putString("deviceId", "android-" + id)
                    .putString("deviceName", Build.MANUFACTURER + " " + Build.MODEL).apply();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("相册诊断信息", DiagnosticLog.snapshot(this)));
        toast("诊断信息已复制");
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(round(Color.WHITE, 20));
        card.setElevation(dp(3));
        return card;
    }

    private Button smallButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(47, 48, 46));
        button.setBackground(round(primary ? Color.rgb(54, 105, 72) : Color.rgb(232, 230, 225), 14));
        button.setPadding(dp(13), 0, dp(13), 0);
        return button;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(33, 34, 32));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
