package com.zwm.gallery;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.res.ColorStateList;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
import java.io.IOException;
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
    private static final int REQUEST_LEGACY_STORAGE = 62;
    public static volatile boolean isVisible;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout worksContainer;
    private TextView statusText;
    private ImageButton leftModeButton;
    private ImageButton rightModeButton;
    private TextView headingText;
    private TextView scannedCountText;
    private ImageButton quickTrashButton;
    private boolean showingTrash;
    private boolean initialFolderPromptShown;
    private String selectedWorkId;

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
        startReceiver();
        requestLegacyStoragePermission();
        if (Build.VERSION.SDK_INT >= 33) Api33Back.register(this);
        UpdateChecker.checkDaily(this);
        DiagnosticLog.write(this, "app_open", "album main opened");
        worker.execute(() -> GalleryShareBridge.cleanupPreviousDays(this, LocalDate.now()));
        getWindow().getDecorView().post(this::showInitialFolderPromptIfNeeded);
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
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, "").isEmpty()) refreshWorks();
        else importSelectedTree(false);
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

    @Override
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (selectedWorkId != null) {
            selectedWorkId = null;
            quickTrashButton.setVisibility(View.GONE);
            refreshWorks();
            return;
        }
        if (showingTrash) {
            showWorks();
            return;
        }
        finish();
    }

    private static final class Api33Back {
        @TargetApi(33)
        static void register(MainActivity activity) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, activity::handleBack);
        }
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(8), pad, dp(36));
        root.setBackgroundColor(Color.rgb(246, 244, 240));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(pad, dp(14), pad, dp(12));
        titleRow.setBackgroundColor(Color.rgb(246, 244, 240));
        headingText = text("作品", 28, true);
        titleRow.addView(headingText);
        scannedCountText = text("0", 12, true);
        scannedCountText.setTextColor(Color.rgb(53, 105, 82));
        scannedCountText.setGravity(Gravity.CENTER);
        scannedCountText.setBackground(round(Color.rgb(226, 239, 232), 14));
        scannedCountText.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-2, -2);
        countParams.setMargins(dp(8), 0, 0, 0);
        titleRow.addView(scannedCountText, countParams);

        View titleSpacer = new View(this);
        titleRow.addView(titleSpacer, new LinearLayout.LayoutParams(0, 1, 1));
        ImageButton transfer = iconButton(R.drawable.ic_album_transfer, "传送文件");
        transfer.setOnClickListener(v -> startActivity(new Intent(this, TransferActivity.class)));
        titleRow.addView(transfer, iconParams(false));
        leftModeButton = iconButton(R.drawable.ic_album_refresh, "刷新作品");
        leftModeButton.setOnClickListener(v -> {
            if (showingTrash) showWorks();
            else importSelectedTree(true);
        });
        titleRow.addView(leftModeButton, iconParams(false));
        rightModeButton = iconButton(R.drawable.ic_album_trash, "回收站");
        rightModeButton.setOnClickListener(v -> {
            if (showingTrash) confirmClearTrash();
            else showTrash();
        });
        titleRow.addView(rightModeButton, iconParams(true));
        ImageButton settings = iconButton(R.drawable.ic_album_settings, "设置");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        titleRow.addView(settings, iconParams(true));
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
        LinearLayout frozenLayout = new LinearLayout(this);
        frozenLayout.setOrientation(LinearLayout.VERTICAL);
        frozenLayout.setBackgroundColor(Color.rgb(246, 244, 240));
        frozenLayout.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
        frozenLayout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.addView(frozenLayout, new FrameLayout.LayoutParams(-1, -1));
        quickTrashButton = iconButton(R.drawable.ic_album_trash, "把选中的作品移到回收站");
        quickTrashButton.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        quickTrashButton.setBackground(round(Color.rgb(188, 66, 60), 26));
        quickTrashButton.setVisibility(View.GONE);
        quickTrashButton.setOnClickListener(v -> confirmMoveSelectedToTrash());
        FrameLayout.LayoutParams quickParams = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.END | Gravity.BOTTOM);
        quickParams.setMargins(0, 0, dp(22), dp(24));
        frame.addView(quickTrashButton, quickParams);
        return frame;
    }

    private void refreshWorks() {
        worker.execute(() -> {
            try {
                WorkLibrary library = library();
                if (library.reconciledDuplicates() > 0) {
                    DiagnosticLog.write(this, "duplicate_works_merged",
                            "count=" + library.reconciledDuplicates());
                }
                library.maintain(LocalDate.now());
                syncExternalTrash(library);
                ExternalTrashManager.Result expired = purgeExpiredTrash(library);
                if (!expired.succeeded()) {
                    DiagnosticLog.write(this, "external_trash_purge_failed", expired.firstFailure());
                }
                List<WorkLibrary.WorkEntry> activeEntries = library.listActive();
                List<WorkLibrary.WorkEntry> entries = showingTrash ? library.listTrash() : activeEntries;
                OnlineService.publishWorkCount(this, activeEntries.size());
                runOnUiThread(() -> renderWorks(entries));
            } catch (Exception error) {
                DiagnosticLog.write(this, "library_refresh_failed", error.getMessage());
                runOnUiThread(() -> statusText.setText("读取作品失败：" + error.getMessage()));
            }
        });
    }

    private void showWorks() {
        selectedWorkId = null;
        quickTrashButton.setVisibility(View.GONE);
        showingTrash = false;
        leftModeButton.setImageResource(R.drawable.ic_album_refresh);
        leftModeButton.setContentDescription("刷新作品");
        rightModeButton.setImageResource(R.drawable.ic_album_trash);
        rightModeButton.setContentDescription("回收站");
        headingText.setText("作品");
        refreshWorks();
    }

    private void showTrash() {
        selectedWorkId = null;
        quickTrashButton.setVisibility(View.GONE);
        showingTrash = true;
        leftModeButton.setImageResource(R.drawable.ic_album_back);
        leftModeButton.setContentDescription("返回作品");
        rightModeButton.setImageResource(R.drawable.ic_album_trash);
        rightModeButton.setContentDescription("清空回收站");
        headingText.setText("回收站");
        refreshWorks();
    }

    private void confirmClearTrash() {
        new AlertDialog.Builder(this)
                .setTitle("清空回收站？")
                .setMessage("将同时删除“相册回收站”里的原文件夹和 App 缓存，清空后不能恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认清空", (dialog, which) -> clearTrash())
                .show();
    }

    private void clearTrash() {
        worker.execute(() -> {
            try {
                WorkLibrary library = library();
                ExternalTrashManager.Result result = clearExternalTrash(library);
                if (!result.succeeded()) {
                    throw new IOException("原文件夹未删，已保留回收站数据：" + result.firstFailure());
                }
                library.clearTrash();
                DiagnosticLog.write(this, "trash_cleared", "user confirmed");
                runOnUiThread(() -> { toast("回收站已清空"); refreshWorks(); });
            } catch (Exception error) {
                runOnUiThread(() -> toast("清空失败：" + error.getMessage()));
            }
        });
    }

    private void renderWorks(List<WorkLibrary.WorkEntry> entries) {
        worksContainer.removeAllViews();
        boolean selectionStillExists = false;
        for (WorkLibrary.WorkEntry entry : entries) {
            if (entry.id.equals(selectedWorkId)) { selectionStillExists = true; break; }
        }
        if (!selectionStillExists) selectedWorkId = null;
        quickTrashButton.setVisibility(selectedWorkId == null || showingTrash ? View.GONE : View.VISIBLE);
        scannedCountText.setText(String.valueOf(entries.size()));
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
        if (work.id.equals(selectedWorkId)) {
            card.setBackground(round(Color.rgb(250, 224, 220), 20));
            card.setElevation(dp(5));
        } else if (!showingTrash && work.sharedDate != null) {
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
        View.OnLongClickListener select = v -> {
            if (showingTrash) return false;
            selectedWorkId = work.id.equals(selectedWorkId) ? null : work.id;
            quickTrashButton.setVisibility(selectedWorkId == null ? View.GONE : View.VISIBLE);
            refreshWorks();
            if (selectedWorkId != null) toast("已选中，点右下角垃圾桶移入回收站");
            return true;
        };
        card.setOnLongClickListener(select);
        name.setOnLongClickListener(select);
        meta.setOnLongClickListener(select);
        action.setOnLongClickListener(select);
        card.addView(action, new LinearLayout.LayoutParams(-1, dp(46)));
        return card;
    }

    private void confirmMoveSelectedToTrash() {
        String id = selectedWorkId;
        if (id == null) return;
        new AlertDialog.Builder(this)
                .setTitle("移到回收站？")
                .setMessage("作品会从当前列表消失，并移动到“相册回收站”；分享次数会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移到回收站", (dialog, which) -> moveSelectedToTrash(id))
                .show();
    }

    private void moveSelectedToTrash(String id) {
        quickTrashButton.setEnabled(false);
        worker.execute(() -> {
            try {
                WorkLibrary library = library();
                WorkLibrary.WorkEntry entry = library.moveToTrash(id, LocalDate.now());
                Uri tree = selectedTree();
                ExternalTrashManager.Result moved = ExternalTrashManager.moveTrashedSource(
                        getContentResolver(), tree, tree == null ? null : legacyRoot(tree), library, entry);
                if (!moved.succeeded()) {
                    if (moved.moved == 0 && moved.alreadyMissing == 0) library.rollbackTrashMove(id);
                    throw new IOException("原文件夹没有移动：" + moved.firstFailure());
                }
                DiagnosticLog.write(this, "manual_trash_move", id);
                runOnUiThread(() -> {
                    selectedWorkId = null;
                    quickTrashButton.setEnabled(true);
                    quickTrashButton.setVisibility(View.GONE);
                    toast("已移到回收站");
                    refreshWorks();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    quickTrashButton.setEnabled(true);
                    toast("移动失败：" + error.getMessage());
                    refreshWorks();
                });
            }
        });
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
            importSelectedTree(true);
        } catch (Exception error) {
            statusText.setText("无法保存文件夹权限：" + error.getMessage());
        }
    }

    private void importSelectedTree(boolean notifyWhenFinished) {
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
                LegacyHiddenFolderImporter.Result hidden = importLegacyHiddenFolders(Uri.parse(stored));
                ExternalTrashManager.Result trashSync = syncExternalTrash(library());
                int activeCount = library().listActive().size();
                OnlineService.publishWorkCount(this, activeCount);
                DiagnosticLog.write(this, "tree_import",
                        "detected=" + result.detected
                                + " imported=" + result.imported
                                + " skipped=" + result.skipped
                                + " scannedFolders=" + result.scannedFolders
                                + " aggregateFolders=" + result.aggregateFolders
                                + " hiddenDetected=" + hidden.detected
                                + " hiddenImported=" + hidden.imported
                                + " hiddenSkipped=" + hidden.skipped
                                + " trashMoved=" + trashSync.moved
                                + " trashMoveFailures=" + trashSync.failures.size()
                                + " notes=" + result.scanNotes);
                runOnUiThread(() -> {
                    int imported = result.imported + hidden.imported;
                    String message = imported > 0
                            ? "新增 " + imported + " 个作品"
                            : activeCount > 0
                            ? "已是最新，共识别 " + activeCount + " 个作品"
                            : "没识别到作品：请选择包含“图片 + TXT”的作品文件夹";
                    if (result.aggregateFolders > 0) message += "，已优先使用子文件夹";
                    statusText.setText(message);
                    if (notifyWhenFinished) toast("已刷新，共 " + activeCount + " 个作品");
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
                WorkLibrary library = library();
                WorkLibrary.WorkEntry entry = library.getTrash(id);
                if (entry == null) throw new IOException("回收站作品不存在");
                Uri tree = selectedTree();
                ExternalTrashManager.restoreSource(
                        getContentResolver(), tree, legacyRoot(tree), library, entry);
                library.restore(id);
                runOnUiThread(() -> { toast("已恢复"); refreshWorks(); });
            } catch (Exception error) {
                runOnUiThread(() -> toast("恢复失败：" + error.getMessage()));
            }
        });
    }

    private WorkLibrary library() throws Exception { return new WorkLibrary(new File(getFilesDir(), "work-library")); }

    private void showInitialFolderPromptIfNeeded() {
        if (initialFolderPromptShown || isFinishing()) return;
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, "");
        if (!stored.isEmpty()) return;
        initialFolderPromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle("先选择作品文件夹")
                .setMessage("只需设置一次。相册会递归识别里面包含图片和 TXT 的作品文件夹。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("选择文件夹", (dialog, which) -> chooseFolder())
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateChecker.resumePendingInstall(this);
    }

    private String treeName(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            try (Cursor cursor = getContentResolver().query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String displayName = cursor.getString(0);
                    if (displayName != null && !displayName.trim().isEmpty()) return displayName.trim();
                }
            }
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

    private void requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT == 29 && (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_LEGACY_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LEGACY_STORAGE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            DiagnosticLog.write(this, "legacy_storage_granted", "Android 10 Huawei hidden-folder fallback enabled");
            importSelectedTree(false);
        } else {
            DiagnosticLog.write(this, "legacy_storage_denied", "hidden dot folders remain unavailable");
            toast("未允许读取存储，点开头的作品文件夹可能无法显示");
        }
    }

    private LegacyHiddenFolderImporter.Result importLegacyHiddenFolders(Uri tree) throws Exception {
        File selected = legacyRoot(tree);
        return selected == null ? new LegacyHiddenFolderImporter.Result()
                : LegacyHiddenFolderImporter.importFrom(selected, library());
    }

    private ExternalTrashManager.Result syncExternalTrash(WorkLibrary library) throws IOException {
        Uri tree = selectedTree();
        if (tree == null) return new ExternalTrashManager.Result();
        ExternalTrashManager.Result result = ExternalTrashManager.moveTrashedSources(
                getContentResolver(), tree, legacyRoot(tree), library);
        if (!result.succeeded()) {
            DiagnosticLog.write(this, "external_trash_move_failed", result.firstFailure());
        }
        return result;
    }

    private ExternalTrashManager.Result clearExternalTrash(WorkLibrary library) throws IOException {
        Uri tree = selectedTree();
        return ExternalTrashManager.clearTrackedTrash(
                getContentResolver(), tree, tree == null ? null : legacyRoot(tree), library);
    }

    private ExternalTrashManager.Result purgeExpiredTrash(WorkLibrary library) throws IOException {
        Uri tree = selectedTree();
        return ExternalTrashManager.purgeExpired(
                getContentResolver(), tree, tree == null ? null : legacyRoot(tree),
                library, LocalDate.now());
    }

    private Uri selectedTree() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, "");
        return stored.isEmpty() ? null : Uri.parse(stored);
    }

    private File legacyRoot(Uri tree) throws IOException {
        if (tree == null || Build.VERSION.SDK_INT != 29
                || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        String selectedName = treeName(tree);
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .getCanonicalFile();
        File selected = new File(downloads, selectedName).getCanonicalFile();
        if (!selected.toPath().startsWith(downloads.toPath()) || !selected.isDirectory()) {
            DiagnosticLog.write(this, "legacy_storage_unmapped", "selected=" + selectedName);
            return null;
        }
        return selected;
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

    private ImageButton iconButton(int imageResource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(imageResource);
        button.setImageTintList(ColorStateList.valueOf(Color.rgb(54, 86, 72)));
        button.setBackground(round(Color.rgb(231, 239, 233), 15));
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setContentDescription(description);
        return button;
    }

    private LinearLayout.LayoutParams iconParams(boolean withLeftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(42), dp(42));
        if (withLeftMargin) params.setMargins(dp(7), 0, 0, 0);
        return params;
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
