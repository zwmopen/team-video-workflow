package com.zwm.gallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
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
    private static final String PREF_TREE_NAME = "libraryTreeName";
    private static final int REQUEST_TREE = 61;
    public static volatile boolean isVisible;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout worksContainer;
    private TextView sourceText;
    private TextView statusText;
    private Button leftModeButton;
    private Button rightModeButton;
    private TextView headingText;
    private boolean showingTrash;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (OnlineService.ACTION_TASK_READY.equals(intent.getAction())) {
                refreshWorks();
                String workId = intent.getStringExtra(OnlineService.EXTRA_AUTO_SHARE_WORK_ID);
                if (workId != null && !workId.isEmpty()) {
                    startActivity(new Intent(MainActivity.this, ShareActivity.class)
                            .putExtra(ShareActivity.EXTRA_WORK_ID, workId));
                }
            }
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
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, "").isEmpty()) refreshWorks();
        else importSelectedTree();
        UpdateChecker.checkDaily(this);
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
        updateSourceLabel();
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

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("相册", 30, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = smallButton("设置", false);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        titleRow.addView(settings, new LinearLayout.LayoutParams(dp(86), dp(42)));
        root.addView(titleRow);
        TextView intro = text("电脑拖入一个压缩包，作品会自动排好。点一下作品：复制文案并打开分享。", 14, false);
        intro.setTextColor(Color.rgb(91, 91, 88));
        root.addView(intro, margins(0, dp(6), 0, dp(18)));

        LinearLayout sourceCard = card();
        sourceText = text("正在读取…", 15, true);
        sourceCard.addView(sourceText, new LinearLayout.LayoutParams(0, -2, 1));
        Button choose = smallButton("设置路径", true);
        choose.setOnClickListener(v -> chooseFolder());
        sourceCard.addView(choose);
        root.addView(sourceCard, margins(0, 0, 0, dp(12)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        leftModeButton = smallButton("刷新作品", false);
        leftModeButton.setOnClickListener(v -> {
            if (showingTrash) showWorks();
            else importSelectedTree();
        });
        controls.addView(leftModeButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        rightModeButton = smallButton("回收站", false);
        rightModeButton.setOnClickListener(v -> {
            if (showingTrash) confirmClearTrash();
            else showTrash();
        });
        LinearLayout.LayoutParams trashParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        trashParams.setMargins(dp(10), 0, 0, 0);
        controls.addView(rightModeButton, trashParams);
        root.addView(controls, margins(0, 0, 0, dp(18)));

        headingText = text("作品（点一下分享）", 20, true);
        root.addView(headingText, margins(0, 0, 0, dp(10)));
        worksContainer = new LinearLayout(this);
        worksContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(worksContainer);

        statusText = text("局域网接收已开启", 13, false);
        statusText.setTextColor(Color.rgb(90, 105, 96));
        statusText.setGravity(Gravity.CENTER);
        statusText.setBackground(round(Color.rgb(231, 239, 233), 16));
        statusText.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(statusText, margins(0, dp(18), 0, dp(8)));

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

    private void showWorks() {
        showingTrash = false;
        leftModeButton.setText("刷新作品");
        rightModeButton.setText("回收站");
        headingText.setText("作品（点一下分享）");
        refreshWorks();
    }

    private void showTrash() {
        showingTrash = true;
        leftModeButton.setText("返回作品");
        rightModeButton.setText("清空回收站");
        headingText.setText("回收站");
        refreshWorks();
    }

    private void confirmClearTrash() {
        new AlertDialog.Builder(this)
                .setTitle("清空回收站？")
                .setMessage("清空后不能恢复。只会删除回收站里的作品。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认清空", (dialog, which) -> clearTrash())
                .show();
    }

    private void clearTrash() {
        worker.execute(() -> {
            try {
                library().clearTrash();
                DiagnosticLog.write(this, "trash_cleared", "user confirmed");
                runOnUiThread(() -> { toast("回收站已清空"); refreshWorks(); });
            } catch (Exception error) {
                runOnUiThread(() -> toast("清空失败：" + error.getMessage()));
            }
        });
    }

    private void renderWorks(List<WorkLibrary.WorkEntry> entries) {
        worksContainer.removeAllViews();
        rightModeButton.setEnabled(!showingTrash || !entries.isEmpty());
        rightModeButton.setAlpha(rightModeButton.isEnabled() ? 1f : 0.45f);
        if (entries.isEmpty()) {
            TextView empty = text(showingTrash ? "回收站是空的" : "还没有作品\n从电脑拖入 ZIP，或选择手机里的 Lark 文件夹", 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(14), dp(28), dp(14), dp(28));
            empty.setBackground(round(Color.WHITE, 18));
            worksContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int index = 0; index < entries.size(); index += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            addGridCard(row, entries.get(index), false);
            if (index + 1 < entries.size()) addGridCard(row, entries.get(index + 1), true);
            else {
                View spacer = new View(this);
                LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 1, 1);
                spacerParams.setMargins(dp(6), 0, 0, 0);
                row.addView(spacer, spacerParams);
            }
            worksContainer.addView(row, margins(0, 0, 0, dp(12)));
        }
    }

    private void addGridCard(LinearLayout row, WorkLibrary.WorkEntry work, boolean right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(190), 1);
        params.setMargins(right ? dp(6) : 0, 0, right ? 0 : dp(6), 0);
        row.addView(workCard(work), params);
    }

    private View workCard(WorkLibrary.WorkEntry work) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        if (!showingTrash && work.sharedDate != null) {
            card.setBackground(round(Color.rgb(225, 225, 222), 20));
            card.setElevation(0);
        }
        TextView name = text(work.name, 16, true);
        name.setMaxLines(2);
        card.addView(name);
        String detail = work.images.size() + " 张图片";
        if (work.shareCount > 0) detail += "\n✓ 已打开分享 " + work.shareCount + " 次";
        else if (work.trashedDate != null) detail += "\n回收站保留 7 天";
        else if (!work.warning.isEmpty()) detail += "\n请检查多个 TXT";
        TextView meta = text(detail, 12, false);
        meta.setTextColor(work.sharedDate == null ? Color.GRAY : Color.rgb(78, 78, 75));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, 0, 1);
        metaParams.setMargins(0, dp(4), 0, dp(8));
        card.addView(meta, metaParams);
        Button action = smallButton(showingTrash ? "恢复" : (work.sharedDate == null ? "复制并分享" : "再次分享"),
                !showingTrash && work.sharedDate == null);
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
            String treeName = treeName(tree);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_TREE_URI, tree.toString())
                    .putString(PREF_TREE_NAME, treeName)
                    .apply();
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
            toast("请先设置作品文件夹");
            return;
        }
        statusText.setText("正在读取 Lark 文件夹…");
        worker.execute(() -> {
            try {
                DocumentTreeImporter.ImportResult result = DocumentTreeImporter.importTree(
                        getContentResolver(), Uri.parse(stored), library(), new File(getCacheDir(), "tree-import"));
                DiagnosticLog.write(this, "tree_import",
                        "detected=" + result.detected
                                + " imported=" + result.imported
                                + " skipped=" + result.skipped
                                + " scannedFolders=" + result.scannedFolders
                                + " aggregateFolders=" + result.aggregateFolders
                                + " notes=" + result.scanNotes);
                runOnUiThread(() -> {
                    String message = result.imported > 0
                            ? "新增 " + result.imported + " 个作品"
                            : result.detected > 0
                            ? "已是最新，共识别 " + result.detected + " 个作品"
                            : "没识别到作品：请选择包含“图片 + TXT”的作品文件夹";
                    if (result.aggregateFolders > 0) message += "，已优先使用子文件夹";
                    statusText.setText(message);
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
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String stored = prefs.getString(PREF_TREE_URI, "");
        String name = prefs.getString(PREF_TREE_NAME, "");
        if (!stored.isEmpty() && name.isEmpty()) name = treeName(Uri.parse(stored));
        sourceText.setText(stored.isEmpty() ? "作品文件夹：未设置" : "作品文件夹：" + name);
    }

    private String treeName(Uri tree) {
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
            int slash = id.lastIndexOf('/');
            String name = slash >= 0 ? id.substring(slash + 1) : id.substring(id.lastIndexOf(':') + 1);
            return name.isEmpty() ? "已设置" : name;
        } catch (Exception ignored) {
            return "已设置";
        }
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
