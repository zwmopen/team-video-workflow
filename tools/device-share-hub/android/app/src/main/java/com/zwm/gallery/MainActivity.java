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
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.animation.LayoutTransition;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private ImageButton modeButton;
    private TextView headingText;
    private TextView scannedCountText;
    private TextView footerNote;
    private ImageButton quickTrashButton;
    private boolean showingTrash;
    private boolean fileMode;
    private Uri fileTree;
    private final ArrayDeque<String> filePath = new ArrayDeque<>();
    private boolean initialFolderPromptShown;
    private final LinkedHashSet<String> selectedWorkIds = new LinkedHashSet<>();
    private String selectedCategory = WorkCategory.ALL;
    private LinearLayout categoryBar;
    private FrameLayout categorySelector;
    private View categoryIndicator;
    private SpringScrollView contentScroll;
    private LinearLayout refreshIndicator;
    private ProgressBar refreshSpinner;
    private TextView refreshIndicatorText;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean updateReadyPromptShown;
    private final Map<String, Button> categoryButtons = new LinkedHashMap<>();
    private final Map<String, String> categoryLabels = new LinkedHashMap<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (OnlineService.ACTION_TASK_READY.equals(intent.getAction())) {
                if (fileMode) refreshFiles();
                else refreshWorks();
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
            if (UpdateDownloadReceiver.ACTION_UPDATE_READY.equals(intent.getAction())
                    && !updateReadyPromptShown) {
                updateReadyPromptShown = UpdateChecker.showReadyInstallPrompt(MainActivity.this);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureDeviceId();
        setContentView(ScreenInsets.protect(buildUi()));
        startReceiver();
        requestLegacyStoragePermission();
        if (Build.VERSION.SDK_INT >= 33) Api33Back.register(this);
        UpdateChecker.checkOnLaunch(this);
        DiagnosticLog.write(this, "app_open", "album main opened");
        worker.execute(() -> GalleryShareBridge.cleanupPreviousDays(this, LocalDate.now()));
        getWindow().getDecorView().post(() -> {
            showInitialFolderPromptIfNeeded();
            maybeOfferClipboardOverlay();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        isVisible = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(OnlineService.ACTION_TASK_READY);
        filter.addAction(OnlineService.ACTION_STATUS);
        filter.addAction(UpdateDownloadReceiver.ACTION_UPDATE_READY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacyReceiver(filter);
        if (fileMode) {
            openSelectedTreeForBrowsing(false);
        } else if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, "").isEmpty()) {
            refreshWorks();
        } else {
            importSelectedTree(false);
        }
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
        if (!selectedWorkIds.isEmpty()) {
            selectedWorkIds.clear();
            quickTrashButton.setVisibility(View.GONE);
            refreshWorks();
            return;
        }
        if (fileMode) {
            if (filePath.size() > 1) {
                filePath.pop();
                refreshFiles();
            } else {
                showWorksMode();
            }
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
        root.setBackgroundColor(Color.rgb(248, 249, 248));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(12), dp(18), dp(12), dp(14));
        titleRow.setBackgroundColor(Color.rgb(248, 249, 248));
        LinearLayout titleCluster = new LinearLayout(this);
        titleCluster.setOrientation(LinearLayout.HORIZONTAL);
        titleCluster.setGravity(Gravity.CENTER_VERTICAL);
        headingText = text("", 20, true);
        headingText.setSingleLine(true);
        headingText.setAutoSizeTextTypeUniformWithConfiguration(18, 23, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        titleCluster.addView(headingText, new LinearLayout.LayoutParams(-2, -2));
        scannedCountText = text("", 12, true);
        scannedCountText.setSingleLine(true);
        scannedCountText.setTextColor(Color.rgb(53, 105, 82));
        scannedCountText.setGravity(Gravity.CENTER);
        scannedCountText.setBackground(round(Color.rgb(226, 239, 232), 14));
        scannedCountText.setPadding(dp(5), dp(5), dp(5), dp(5));
        scannedCountText.setVisibility(View.GONE);
        modeButton = iconButton(R.drawable.ic_file_folder, "切换到文件浏览");
        modeButton.setOnClickListener(v -> {
            if (fileMode) showWorksMode();
            else showFileMode();
        });
        titleRow.addView(modeButton, iconParams(false));
        titleRow.addView(titleCluster, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton transfer = iconButton(R.drawable.ic_album_transfer, "传送文件");
        transfer.setOnClickListener(v -> {
            toast("文件传输");
            startActivity(new Intent(this, TransferActivity.class));
        });
        titleRow.addView(transfer, iconParams(true));
        leftModeButton = iconButton(R.drawable.ic_album_refresh, "刷新作品");
        leftModeButton.setVisibility(View.GONE);
        leftModeButton.setOnClickListener(v -> {
            if (fileMode) {
                refreshFiles();
                toast("正在刷新文件");
            } else if (showingTrash) showWorks();
            else {
                toast("正在刷新作品");
                importSelectedTree(true);
            }
        });
        titleRow.addView(leftModeButton, iconParams(true));
        rightModeButton = iconButton(R.drawable.ic_album_trash, "回收站");
        rightModeButton.setOnClickListener(v -> {
            if (fileMode) {
                leaveFileMode();
                toast("回收站");
                showTrash();
            } else if (showingTrash) confirmClearTrash();
            else {
                toast("回收站");
                showTrash();
            }
        });
        titleRow.addView(rightModeButton, iconParams(true));
        ImageButton settings = iconButton(R.drawable.ic_album_settings, "设置");
        settings.setOnClickListener(v -> {
            toast("设置");
            startActivity(new Intent(this, SettingsActivity.class));
        });
        titleRow.addView(settings, iconParams(true));
        categoryBar = new LinearLayout(this);
        categoryBar.setOrientation(LinearLayout.HORIZONTAL);
        categoryBar.setGravity(Gravity.CENTER_VERTICAL);
        addCategoryButton("全部", WorkCategory.ALL);
        addCategoryButton("转化", WorkCategory.CONVERSION);
        addCategoryButton("泛流量", WorkCategory.TRAFFIC);
        addCategoryButton("未分类", WorkCategory.UNCATEGORIZED);
        categoryIndicator = new View(this);
        categoryIndicator.setBackground(round(Color.WHITE, 10));
        categoryIndicator.setElevation(dp(1));
        categorySelector = new FrameLayout(this);
        categorySelector.setPadding(dp(3), dp(3), dp(3), dp(3));
        categorySelector.setBackground(round(Color.rgb(232, 234, 233), 11));
        categorySelector.addView(categoryIndicator, new FrameLayout.LayoutParams(0, dp(38)));
        categoryBar.setElevation(dp(2));
        categorySelector.addView(categoryBar, new FrameLayout.LayoutParams(-1, dp(38)));
        categorySelector.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> animateCategoryIndicator(false));
        worksContainer = new LinearLayout(this);
        worksContainer.setOrientation(LinearLayout.VERTICAL);
        LayoutTransition contentTransition = new LayoutTransition();
        contentTransition.setDuration(180);
        worksContainer.setLayoutTransition(contentTransition);
        root.addView(worksContainer);

        statusText = text("局域网接收已开启", 13, false);
        statusText.setTextColor(Color.rgb(90, 105, 96));
        statusText.setGravity(Gravity.CENTER);
        statusText.setBackground(round(Color.rgb(231, 239, 233), 16));
        statusText.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(statusText, margins(0, dp(18), 0, dp(8)));

        footerNote = text("点击平台按钮会复制对应文案并打开图片分享。首次使用后按现有清理设置自动回收；两个平台共用一个作品生命周期。", 12, false);
        footerNote.setTextColor(Color.GRAY);
        root.addView(footerNote, margins(0, dp(12), 0, 0));

        contentScroll = new SpringScrollView(this);
        contentScroll.setPullRefreshListener(new SpringScrollView.PullRefreshListener() {
            @Override public void onPull(float progress, boolean ready) {
                showPullProgress(progress, ready);
            }

            @Override public void onRefresh() {
                beginVisibleRefresh();
                if (fileMode) refreshFiles();
                else if (showingTrash) refreshWorks();
                else importSelectedTree(true);
            }

            @Override public void onReset() {
                if (refreshIndicator != null && refreshSpinner.getVisibility() != View.VISIBLE) {
                    refreshIndicator.animate().alpha(0f).setDuration(140).start();
                }
            }
        });
        contentScroll.addView(root);
        FrameLayout contentFrame = new FrameLayout(this);
        refreshIndicator = new LinearLayout(this);
        refreshIndicator.setOrientation(LinearLayout.HORIZONTAL);
        refreshIndicator.setGravity(Gravity.CENTER);
        refreshIndicator.setAlpha(0f);
        refreshIndicator.setPadding(dp(12), dp(7), dp(12), dp(7));
        refreshIndicator.setBackground(round(Color.WHITE, 16));
        refreshSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        refreshSpinner.setIndeterminateTintList(ColorStateList.valueOf(Color.rgb(16, 151, 99)));
        refreshSpinner.setVisibility(View.GONE);
        refreshIndicatorText = text("下拉刷新", 12, false);
        refreshIndicatorText.setTextColor(Color.rgb(80, 86, 82));
        refreshIndicator.addView(refreshSpinner, new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout.LayoutParams refreshTextParams = new LinearLayout.LayoutParams(-2, -2);
        refreshTextParams.setMargins(dp(7), 0, 0, 0);
        refreshIndicator.addView(refreshIndicatorText, refreshTextParams);
        FrameLayout.LayoutParams refreshParams = new FrameLayout.LayoutParams(-2, dp(38),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        refreshParams.setMargins(0, dp(6), 0, 0);
        contentFrame.addView(refreshIndicator, refreshParams);
        contentFrame.addView(contentScroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout frozenLayout = new LinearLayout(this);
        frozenLayout.setOrientation(LinearLayout.VERTICAL);
        frozenLayout.setBackgroundColor(Color.rgb(248, 249, 248));
        frozenLayout.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams categoryParams = new LinearLayout.LayoutParams(-1, dp(44));
        categoryParams.setMargins(dp(12), 0, dp(12), 0);
        frozenLayout.addView(categorySelector, categoryParams);
        frozenLayout.addView(contentFrame, new LinearLayout.LayoutParams(-1, 0, 1));
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
                CleanupCoordinator.Result cleanup = CleanupCoordinator.run(this);
                if (!cleanup.failure.isEmpty()) {
                    DiagnosticLog.write(this, "external_trash_purge_failed", cleanup.failure);
                }
                List<WorkLibrary.WorkEntry> activeEntries = library.listActive();
                List<WorkLibrary.WorkEntry> entries = showingTrash ? library.listTrash() : activeEntries;
                OnlineService.publishWorkInventory(this, activeEntries);
                runOnUiThread(() -> {
                    renderWorks(entries);
                    finishVisibleRefresh(showingTrash ? "回收站已刷新" : "已刷新，共 " + entries.size() + " 个");
                });
            } catch (Exception error) {
                DiagnosticLog.write(this, "library_refresh_failed", error.getMessage());
                runOnUiThread(() -> {
                    statusText.setText("读取作品失败：" + error.getMessage());
                    finishVisibleRefresh("刷新失败");
                });
            }
        });
    }

    private void showWorks() {
        selectedWorkIds.clear();
        quickTrashButton.setVisibility(View.GONE);
        showingTrash = false;
        categorySelector.setVisibility(View.VISIBLE);
        leftModeButton.setImageResource(R.drawable.ic_album_refresh);
        leftModeButton.setContentDescription("刷新作品");
        leftModeButton.setVisibility(View.GONE);
        rightModeButton.setImageResource(R.drawable.ic_album_trash);
        rightModeButton.setContentDescription("回收站");
        headingText.setText("");
        refreshWorks();
    }

    private void showTrash() {
        selectedWorkIds.clear();
        quickTrashButton.setVisibility(View.GONE);
        showingTrash = true;
        categorySelector.setVisibility(View.GONE);
        leftModeButton.setImageResource(R.drawable.ic_album_back);
        leftModeButton.setContentDescription("返回作品");
        leftModeButton.setVisibility(View.VISIBLE);
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
                DiagnosticLog.write(this, "trash_clear_started", "user confirmed");
                WorkLibrary library = library();
                ExternalTrashManager.Result result = clearExternalTrash(library);
                if (!result.succeeded()) {
                    throw new IOException("原文件夹未删，已保留回收站数据：" + result.firstFailure());
                }
                library.clearTrash();
                DiagnosticLog.write(this, "trash_cleared", "user confirmed");
                runOnUiThread(() -> { toast("回收站已清空"); refreshWorks(); });
            } catch (Exception error) {
                DiagnosticLog.write(this, "trash_clear_failed",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                runOnUiThread(() -> toast("清空失败：" + error.getMessage()));
            }
        });
    }

    private void renderWorks(List<WorkLibrary.WorkEntry> entries) {
        if (fileMode) return;
        if (!showingTrash) updateCategoryCounts(entries);
        if (!showingTrash && !WorkCategory.ALL.equals(selectedCategory)) {
            ArrayList<WorkLibrary.WorkEntry> filtered = new ArrayList<>();
            for (WorkLibrary.WorkEntry entry : entries) {
                if (selectedCategory.equals(entry.category)) filtered.add(entry);
            }
            entries = filtered;
        }
        worksContainer.removeAllViews();
        LinkedHashSet<String> visibleIds = new LinkedHashSet<>();
        for (WorkLibrary.WorkEntry entry : entries) {
            visibleIds.add(entry.id);
        }
        selectedWorkIds.retainAll(visibleIds);
        boolean selecting = !selectedWorkIds.isEmpty() && !showingTrash;
        quickTrashButton.setVisibility(selecting ? View.VISIBLE : View.GONE);
        headingText.setText(selecting ? "已选 " + selectedWorkIds.size() + " 个" : (showingTrash ? "回收站" : ""));
        scannedCountText.setText(String.valueOf(entries.size()));
        // Huawei can leave real folders in the external trash after its document index
        // forgets the corresponding app record, so clearing must remain available even
        // when the private list already looks empty.
        rightModeButton.setEnabled(true);
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
            worksContainer.addView(row, margins(0, 0, 0, dp(10)));
        }
    }

    private void addGridCard(LinearLayout row, WorkLibrary.WorkEntry work, boolean right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(174), 1);
        params.setMargins(right ? dp(5) : 0, 0, right ? 0 : dp(5), 0);
        row.addView(workCard(work), params);
    }

    private View workCard(WorkLibrary.WorkEntry work) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        boolean selected = selectedWorkIds.contains(work.id);
        boolean selecting = !selectedWorkIds.isEmpty() && !showingTrash;
        if (selected) {
            card.setBackground(roundWithStroke(
                    Color.rgb(250, 230, 226), 16, Color.rgb(209, 126, 116)));
            card.setElevation(dp(2));
        } else if (!showingTrash && work.used) {
            card.setBackground(roundWithStroke(
                    Color.rgb(231, 231, 228), 16, Color.rgb(211, 211, 207)));
            card.setElevation(0);
        }
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.TOP);
        TextView name = text(work.name, 16, true);
        name.setMaxLines(2);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        CheckBox checkBox = new CheckBox(this);
        checkBox.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.rgb(188, 66, 60), Color.rgb(145, 145, 140)}));
        checkBox.setChecked(selected);
        checkBox.setVisibility(selecting ? View.VISIBLE : View.GONE);
        checkBox.setContentDescription(selected ? "取消选择" : "选择作品");
        nameRow.addView(checkBox, new LinearLayout.LayoutParams(dp(42), dp(42)));
        card.addView(nameRow);
        String detail = work.images.size() + " 张图片";
        if (work.used) {
            detail += "\n✓ 小红书 " + work.xhsShareCount + " · 抖音 " + work.douyinShareCount;
            detail += "\n" + deleteCountdown(work);
        }
        else if (work.trashedDate != null) detail += "\n等待自动清理";
        else if (!work.warning.isEmpty()) detail += "\n请检查多个 TXT";
        TextView meta = text(detail, 12, false);
        meta.setTextColor(work.sharedDate == null ? Color.GRAY : Color.rgb(78, 78, 75));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, 0, 1);
        metaParams.setMargins(0, dp(3), 0, dp(6));
        card.addView(meta, metaParams);
        String actionText = selecting ? (selected ? "已选择" : "选择") : "恢复";
        Button action = smallButton(actionText, false);
        action.setOnClickListener(v -> {
            if (!selectedWorkIds.isEmpty() && !showingTrash) toggleWorkSelection(work.id);
            else if (showingTrash) restore(work.id);
        });
        View.OnLongClickListener select = v -> {
            if (showingTrash) return false;
            toggleWorkSelection(work.id);
            if (!selectedWorkIds.isEmpty()) toast("已进入多选，可继续勾选作品");
            return true;
        };
        checkBox.setOnClickListener(v -> toggleWorkSelection(work.id));
        View.OnClickListener openOrSelect = v -> {
            if (!selectedWorkIds.isEmpty()) toggleWorkSelection(work.id);
            else if (!showingTrash) startActivity(new Intent(this, WorkDetailActivity.class)
                    .putExtra(WorkDetailActivity.EXTRA_WORK_ID, work.id));
        };
        card.setOnClickListener(openOrSelect);
        nameRow.setOnClickListener(openOrSelect);
        name.setOnClickListener(openOrSelect);
        meta.setOnClickListener(openOrSelect);
        card.setOnLongClickListener(select);
        name.setOnLongClickListener(select);
        meta.setOnLongClickListener(select);
        action.setOnLongClickListener(select);
        if (selecting || showingTrash) {
            card.addView(action, new LinearLayout.LayoutParams(-1, dp(44)));
        } else {
            LinearLayout platformRow = new LinearLayout(this);
            platformRow.setOrientation(LinearLayout.HORIZONTAL);
            platformRow.setGravity(Gravity.CENTER_VERTICAL);
            Button xhs = smallButton("小红书 " + work.xhsShareCount, work.xhsShareCount == 0);
            Button douyin = smallButton("抖音 " + work.douyinShareCount, work.douyinShareCount == 0);
            xhs.setContentDescription("小红书，已点击 " + work.xhsShareCount + " 次");
            douyin.setContentDescription("抖音，已点击 " + work.douyinShareCount + " 次");
            xhs.setOnClickListener(v -> openShare(work, "xhs"));
            douyin.setOnClickListener(v -> openShare(work, "douyin"));
            platformRow.addView(xhs, new LinearLayout.LayoutParams(0, dp(44), 1));
            LinearLayout.LayoutParams douyinParams = new LinearLayout.LayoutParams(0, dp(44), 1);
            douyinParams.setMargins(dp(6), 0, 0, 0);
            platformRow.addView(douyin, douyinParams);
            card.addView(platformRow);
        }
        return card;
    }

    private void addCategoryButton(String label, String category) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinHeight(dp(40));
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setElevation(0);
        button.setOnClickListener(v -> {
            selectedCategory = category;
            animateCategoryIndicator(true);
            refreshWorks();
            toast("已显示" + label);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
        categoryBar.addView(button, params);
        categoryButtons.put(category, button);
        categoryLabels.put(category, label);
    }

    private void updateCategoryCounts(List<WorkLibrary.WorkEntry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(WorkCategory.ALL, entries.size());
        counts.put(WorkCategory.CONVERSION, 0);
        counts.put(WorkCategory.TRAFFIC, 0);
        counts.put(WorkCategory.UNCATEGORIZED, 0);
        for (WorkLibrary.WorkEntry entry : entries) {
            if (counts.containsKey(entry.category)) {
                counts.put(entry.category, counts.get(entry.category) + 1);
            }
        }
        for (Map.Entry<String, Button> item : categoryButtons.entrySet()) {
            int count = counts.containsKey(item.getKey()) ? counts.get(item.getKey()) : 0;
            item.getValue().setText(categoryLabels.get(item.getKey()) + " " + count);
            boolean selected = item.getKey().equals(selectedCategory);
            item.getValue().setTextColor(selected ? Color.rgb(24, 25, 24) : Color.rgb(104, 108, 106));
            item.getValue().setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            item.getValue().animate().scaleX(selected ? 1f : 0.98f)
                    .scaleY(selected ? 1f : 0.98f).alpha(selected ? 1f : 0.78f)
                    .setDuration(180).start();
        }
        animateCategoryIndicator(true);
    }

    private void animateCategoryIndicator(boolean animated) {
        if (categorySelector == null || categoryIndicator == null || categorySelector.getWidth() <= 0) return;
        int available = categorySelector.getWidth()
                - categorySelector.getPaddingLeft() - categorySelector.getPaddingRight();
        int width = available / Math.max(1, categoryButtons.size());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) categoryIndicator.getLayoutParams();
        if (params.width != width || params.height != dp(38)) {
            params.width = width;
            params.height = dp(38);
            categoryIndicator.setLayoutParams(params);
        }
        int index = 0;
        for (String category : categoryButtons.keySet()) {
            if (category.equals(selectedCategory)) break;
            index++;
        }
        float target = index * width;
        if (!animated) categoryIndicator.setTranslationX(target);
        else categoryIndicator.animate().translationX(target).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.7f)).start();
    }

    private void showPullProgress(float progress, boolean ready) {
        if (refreshIndicator == null) return;
        refreshSpinner.setVisibility(View.GONE);
        refreshIndicatorText.setText(ready ? "松开刷新" : "下拉刷新");
        refreshIndicator.setAlpha(Math.max(0.18f, progress));
        refreshIndicator.setScaleX(0.92f + 0.08f * progress);
        refreshIndicator.setScaleY(0.92f + 0.08f * progress);
    }

    private void beginVisibleRefresh() {
        refreshIndicator.animate().cancel();
        refreshIndicator.setAlpha(1f);
        refreshIndicator.setScaleX(1f);
        refreshIndicator.setScaleY(1f);
        refreshSpinner.setVisibility(View.VISIBLE);
        refreshIndicatorText.setText(fileMode ? "正在刷新文件…"
                : showingTrash ? "正在刷新回收站…" : "正在刷新作品…");
    }

    private void finishVisibleRefresh(String result) {
        if (contentScroll == null || refreshIndicator == null
                || refreshSpinner.getVisibility() != View.VISIBLE) return;
        refreshSpinner.setVisibility(View.GONE);
        refreshIndicatorText.setText(result);
        uiHandler.postDelayed(() -> {
            contentScroll.finishRefresh();
            refreshIndicator.animate().alpha(0f).setDuration(180).start();
        }, 520);
    }

    private void maybeOfferClipboardOverlay() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!preferences.getBoolean("clipboardOverlayEnabled", true)
                || preferences.getBoolean("clipboardOverlayPermissionAsked", false)
                || Settings.canDrawOverlays(this)) {
            return;
        }
        preferences.edit().putBoolean("clipboardOverlayPermissionAsked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("开启悬浮剪切板？")
                .setMessage("开启后，在其他应用中也能点“剪”打开共享剪切板。可随时在设置中关闭。")
                .setNegativeButton("暂不", null)
                .setPositiveButton("去开启", (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()))))
                .show();
    }

    private String deleteCountdown(WorkLibrary.WorkEntry work) {
        long remaining = CleanupSettings.read(this).deleteAfterMs()
                - Math.max(0, System.currentTimeMillis() - work.firstSharedAtMs);
        if (remaining <= 0) return "即将自动删除";
        long minutes = Math.max(1, (remaining + 59_999L) / 60_000L);
        if (minutes < 60) return minutes + " 分钟后自动删除";
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " 小时后自动删除"
                : hours + " 小时 " + rest + " 分钟后自动删除";
    }

    private void openShare(WorkLibrary.WorkEntry work, String platform) {
        startActivity(new Intent(this, ShareActivity.class)
                .putExtra(ShareActivity.EXTRA_WORK_ID, work.id)
                .putExtra(ShareActivity.EXTRA_PLATFORM, platform));
    }

    private void toggleWorkSelection(String id) {
        if (selectedWorkIds.contains(id)) selectedWorkIds.remove(id);
        else selectedWorkIds.add(id);
        quickTrashButton.setVisibility(selectedWorkIds.isEmpty() ? View.GONE : View.VISIBLE);
        refreshWorks();
    }

    private void showWorksMode() {
        leaveFileMode();
        toast("作品分发模式");
        showWorks();
    }

    private void leaveFileMode() {
        fileMode = false;
        categorySelector.setVisibility(View.VISIBLE);
        fileTree = null;
        filePath.clear();
        modeButton.setImageResource(R.drawable.ic_file_folder);
        modeButton.setContentDescription("切换到文件浏览");
        footerNote.setVisibility(View.VISIBLE);
    }

    private void showFileMode() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, "");
        if (stored.isEmpty()) {
            toast("请先选择作品文件夹");
            chooseFolder();
            return;
        }
        selectedWorkIds.clear();
        quickTrashButton.setVisibility(View.GONE);
        showingTrash = false;
        fileMode = true;
        categorySelector.setVisibility(View.GONE);
        modeButton.setImageResource(R.drawable.ic_album_share);
        modeButton.setContentDescription("切换到作品分享");
        leftModeButton.setImageResource(R.drawable.ic_album_refresh);
        leftModeButton.setContentDescription("刷新文件");
        leftModeButton.setVisibility(View.GONE);
        rightModeButton.setImageResource(R.drawable.ic_album_trash);
        rightModeButton.setContentDescription("回收站");
        footerNote.setVisibility(View.GONE);
        toast("文件浏览模式");
        openSelectedTreeForBrowsing(true);
    }

    private void openSelectedTreeForBrowsing(boolean resetPath) {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, "");
        if (stored.isEmpty()) return;
        fileTree = Uri.parse(stored);
        if (resetPath || filePath.isEmpty()) {
            filePath.clear();
            filePath.push(DocumentsContract.getTreeDocumentId(fileTree));
        }
        refreshFiles();
    }

    private void refreshFiles() {
        if (!fileMode || fileTree == null || filePath.isEmpty()) return;
        String current = filePath.peek();
        statusText.setText("正在读取文件…");
        worker.execute(() -> {
            try {
                List<FileEntry> entries = readFileChildren(current);
                runOnUiThread(() -> {
                    renderFiles(entries);
                    statusText.setText("已刷新，共 " + entries.size() + " 项");
                    finishVisibleRefresh("已刷新，共 " + entries.size() + " 项");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    statusText.setText("读取文件失败：" + error.getMessage());
                    finishVisibleRefresh("刷新失败");
                });
            }
        });
    }

    private List<FileEntry> readFileChildren(String parentId) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(fileTree, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        ArrayList<FileEntry> result = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("系统没有返回文件列表");
            while (cursor.moveToNext()) {
                result.add(new FileEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.isNull(3) ? 0 : cursor.getLong(3)));
            }
        }
        result.sort((left, right) -> {
            if (left.directory != right.directory) return left.directory ? -1 : 1;
            return WorkRules.compareNatural(left.name, right.name);
        });
        return result;
    }

    private void renderFiles(List<FileEntry> entries) {
        if (!fileMode) return;
        worksContainer.removeAllViews();
        headingText.setText(filePath.size() > 1 ? "文件夹" : "文件");
        scannedCountText.setText(String.valueOf(entries.size()));
        statusText.setText(entries.size() + " 项");
        rightModeButton.setEnabled(true);
        rightModeButton.setAlpha(1f);
        if (filePath.size() > 1) {
            worksContainer.addView(fileRow(R.drawable.ic_file_up, Color.rgb(91, 107, 98),
                    "返回上一级", "当前目录", true, v -> {
                        filePath.pop();
                        refreshFiles();
                    }), fileRowParams());
        }
        for (FileEntry entry : entries) {
            String detail = entry.directory ? "文件夹" : fileDetail(entry);
            worksContainer.addView(fileRow(fileIcon(entry), fileIconColor(entry), entry.name,
                    detail, entry.directory, v -> openFileEntry(entry)), fileRowParams());
        }
        if (entries.isEmpty()) {
            TextView empty = text("这个文件夹是空的", 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(0, dp(42), 0, dp(18));
            worksContainer.addView(empty);
        }
    }

    private View fileRow(int iconResource, int iconColor, String title, String detail,
                         boolean folder, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackground(round(Color.WHITE, 15));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setColorFilter(iconColor);
        icon.setContentDescription(folder ? "文件夹" : "文件");
        LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconLayout.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconLayout);
        LinearLayout textStack = new LinearLayout(this);
        textStack.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 15, folder);
        name.setMaxLines(2);
        textStack.addView(name);
        TextView meta = text(detail, 12, false);
        meta.setTextColor(Color.GRAY);
        meta.setPadding(0, dp(3), 0, 0);
        textStack.addView(meta);
        row.addView(textStack, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(click);
        return row;
    }

    private void openFileEntry(FileEntry entry) {
        if (entry.directory) {
            filePath.push(entry.id);
            refreshFiles();
            return;
        }
        try {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(fileTree, entry.id);
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, entry.mime == null ? "*/*" : entry.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, "打开文件"));
        } catch (Exception error) {
            toast("没有能打开这个文件的应用");
        }
    }

    private String fileDetail(FileEntry entry) {
        if (entry.size <= 0) return entry.mime == null ? "文件" : entry.mime;
        double size = entry.size;
        String unit = "B";
        if (size >= 1024) { size /= 1024; unit = "KB"; }
        if (size >= 1024) { size /= 1024; unit = "MB"; }
        if (size >= 1024) { size /= 1024; unit = "GB"; }
        return String.format(Locale.CHINA, size >= 10 ? "%.0f %s" : "%.1f %s", size, unit);
    }

    private int fileIcon(FileEntry entry) {
        if (entry.directory) return R.drawable.ic_file_folder;
        String mime = entry.mime == null ? "" : entry.mime.toLowerCase(Locale.ROOT);
        String extension = extension(entry.name);
        if (mime.startsWith("image/")) return R.drawable.ic_file_image;
        if (mime.startsWith("video/")) return R.drawable.ic_file_video;
        if (mime.startsWith("audio/")) return R.drawable.ic_file_audio;
        if (mime.contains("pdf") || "pdf".equals(extension)) return R.drawable.ic_file_pdf;
        if (mime.contains("zip") || mime.contains("archive") || mime.contains("compressed")
                || "zip".equals(extension) || "rar".equals(extension) || "7z".equals(extension)) {
            return R.drawable.ic_file_archive;
        }
        if (mime.startsWith("text/") || "txt".equals(extension) || "md".equals(extension)
                || "json".equals(extension) || "xml".equals(extension) || "csv".equals(extension)) {
            return R.drawable.ic_file_text;
        }
        return R.drawable.ic_file_generic;
    }

    private int fileIconColor(FileEntry entry) {
        int icon = fileIcon(entry);
        if (icon == R.drawable.ic_file_folder || icon == R.drawable.ic_file_up) return Color.rgb(222, 164, 64);
        if (icon == R.drawable.ic_file_image) return Color.rgb(65, 145, 99);
        if (icon == R.drawable.ic_file_video) return Color.rgb(124, 96, 164);
        if (icon == R.drawable.ic_file_audio) return Color.rgb(202, 105, 84);
        if (icon == R.drawable.ic_file_pdf) return Color.rgb(190, 76, 72);
        if (icon == R.drawable.ic_file_archive) return Color.rgb(166, 119, 52);
        if (icon == R.drawable.ic_file_text) return Color.rgb(76, 119, 164);
        return Color.rgb(113, 122, 118);
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length()
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private LinearLayout.LayoutParams fileRowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(8));
        return params;
    }

    private void confirmMoveSelectedToTrash() {
        if (selectedWorkIds.isEmpty()) return;
        LinkedHashSet<String> ids = new LinkedHashSet<>(selectedWorkIds);
        new AlertDialog.Builder(this)
                .setTitle("将 " + ids.size() + " 个作品移到回收站？")
                .setMessage("这些作品会从当前列表消失，并移动到“相册回收站”；分享次数会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移到回收站", (dialog, which) -> moveSelectedToTrash(ids))
                .show();
    }

    private void moveSelectedToTrash(Set<String> ids) {
        quickTrashButton.setEnabled(false);
        worker.execute(() -> {
            ArrayList<String> completed = new ArrayList<>();
            ArrayList<String> failures = new ArrayList<>();
            for (String id : ids) {
                try {
                    WorkLibrary library = library();
                    WorkLibrary.WorkEntry entry = library.moveToTrash(id, LocalDate.now());
                    Uri tree = selectedTree();
                    ExternalTrashManager.Result moved = ExternalTrashManager.moveTrashedSource(
                            getContentResolver(), tree, tree == null ? null : legacyRoot(tree), library, entry);
                    if (!moved.succeeded()) {
                        if (moved.moved == 0 && moved.alreadyMissing == 0) library.rollbackTrashMove(id);
                        throw new IOException(moved.firstFailure());
                    }
                    completed.add(id);
                    DiagnosticLog.write(this, "manual_trash_move", id);
                } catch (Exception error) {
                    failures.add(error.getMessage() == null ? "移动失败" : error.getMessage());
                }
            }
            runOnUiThread(() -> {
                selectedWorkIds.removeAll(completed);
                quickTrashButton.setEnabled(true);
                quickTrashButton.setVisibility(selectedWorkIds.isEmpty() ? View.GONE : View.VISIBLE);
                String message = failures.isEmpty() ? "已移到回收站 " + completed.size() + " 个"
                        : "已移动 " + completed.size() + " 个，失败 " + failures.size() + " 个";
                toast(message);
                refreshWorks();
            });
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
                Uri selected = Uri.parse(stored);
                File legacy = legacyRoot(selected);
                if (!isTreeReadable(selected)) {
                    if (legacy == null) {
                        DiagnosticLog.write(this, "tree_permission_stale", "reselect required");
                        runOnUiThread(() -> showFolderPermissionRecovery());
                        return;
                    }
                    LegacyHiddenFolderImporter.Result fallback =
                            LegacyHiddenFolderImporter.importAllFrom(legacy, library());
                    WorkLibrary.ReconcileResult reconciled =
                            library().reconcileExternalRelativePaths(
                                    fallback.detectedRelativePaths, System.currentTimeMillis());
                    if (reconciled.pendingConfirmation > 0) {
                        Thread.sleep(2_200L);
                        reconciled = library().reconcileExternalRelativePaths(
                                fallback.detectedRelativePaths, System.currentTimeMillis());
                    }
                    ExternalTrashManager.Result trashSync =
                            ExternalTrashManager.moveTrashedSources(
                                    getContentResolver(), null, legacy, library());
                    int activeCount = library().listActive().size();
                    OnlineService.publishWorkInventory(this, library().listActive());
                    DiagnosticLog.write(this, "tree_legacy_recovered",
                            "detected=" + fallback.detected + " imported=" + fallback.imported
                                    + " removed=" + reconciled.activeRemoved
                                    + " trashMoved=" + trashSync.moved);
                    runOnUiThread(() -> {
                        statusText.setText("已自动恢复 Lark 文件夹，共识别 " + activeCount + " 个作品");
                        if (notifyWhenFinished) toast("已刷新，共 " + activeCount + " 个作品");
                        refreshWorks();
                    });
                    return;
                }
                DocumentTreeImporter.ImportResult result;
                String scanWarning = "";
                boolean legacyFallbackUsed = false;
                try {
                    result = DocumentTreeImporter.importTree(
                            getContentResolver(), Uri.parse(stored), library(),
                            new File(getCacheDir(), "tree-import"));
                } catch (Exception scanError) {
                    // A few vendor document providers can leave one stale database row after
                    // the user deletes a folder in the system file manager. Reconciliation of
                    // already imported works must still run instead of leaving shareable ghosts.
                    scanWarning = scanError.getMessage() == null
                            ? scanError.getClass().getSimpleName() : scanError.getMessage();
                    result = new DocumentTreeImporter.ImportResult(
                            0, 0, 0, 0, 0, "scanError=" + scanWarning,
                            java.util.Collections.emptySet());
                    DiagnosticLog.write(this, "tree_scan_partial", scanWarning);
                }
                LegacyHiddenFolderImporter.Result hidden;
                WorkLibrary.ReconcileResult reconciled;
                if (legacy != null) {
                    // Android 10 Huawei/HarmonyOS exposes the real selected directory but its
                    // DocumentsProvider index can be stale in both directions: dead rows remain
                    // and newly created folders arrive late. Use the real directory as source of
                    // truth whenever it is safely resolvable.
                    hidden = LegacyHiddenFolderImporter.importAllFrom(legacy, library());
                    reconciled = library().reconcileExternalRelativePaths(
                            hidden.detectedRelativePaths, System.currentTimeMillis());
                    legacyFallbackUsed = true;
                } else {
                    hidden = importLegacyHiddenFolders(Uri.parse(stored));
                    reconciled = ExternalTrashManager.reconcileMissingExternalSources(
                            getContentResolver(), Uri.parse(stored), library(),
                            result.detectedDocumentIds);
                }
                if (reconciled.pendingConfirmation > 0) {
                    Thread.sleep(2_200L);
                    WorkLibrary.ReconcileResult confirmed = legacyFallbackUsed
                            ? library().reconcileExternalRelativePaths(
                                    hidden.detectedRelativePaths, System.currentTimeMillis())
                            : ExternalTrashManager.reconcileMissingExternalSources(
                                    getContentResolver(), Uri.parse(stored), library(),
                                    result.detectedDocumentIds);
                    reconciled.activeRemoved += confirmed.activeRemoved;
                    reconciled.trashRemoved += confirmed.trashRemoved;
                    reconciled.pendingConfirmation = confirmed.pendingConfirmation;
                }
                ExternalTrashManager.Result trashSync = syncExternalTrash(library());
                int activeCount = library().listActive().size();
                OnlineService.publishWorkInventory(this, library().listActive());
                DiagnosticLog.write(this, "tree_import",
                        "detected=" + result.detected
                                + " imported=" + result.imported
                                + " skipped=" + result.skipped
                                + " scannedFolders=" + result.scannedFolders
                                + " aggregateFolders=" + result.aggregateFolders
                                + " hiddenDetected=" + hidden.detected
                                + " hiddenImported=" + hidden.imported
                                + " hiddenSkipped=" + hidden.skipped
                              + " sourceRemoved=" + reconciled.activeRemoved
                              + " trashRemoved=" + reconciled.trashRemoved
                              + " sourcePending=" + reconciled.pendingConfirmation
                              + " trashMoved=" + trashSync.moved
                              + " trashMoveFailures=" + trashSync.failures.size()
                              + " notes=" + result.scanNotes);
                final String scanWarningMessage = scanWarning;
                final DocumentTreeImporter.ImportResult completedScan = result;
                runOnUiThread(() -> {
                    int imported = completedScan.imported + hidden.imported;
                    String message = imported > 0
                            ? "新增 " + imported + " 个作品"
                            : activeCount > 0
                            ? "已是最新，共识别 " + activeCount + " 个作品"
                            : "没识别到作品：请选择包含“图片 + TXT”的作品文件夹";
                    if (completedScan.aggregateFolders > 0) message += "，已优先使用子文件夹";
                    if (!scanWarningMessage.isEmpty()) message += "，已跳过失效目录";
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
        UpdateChecker.reportDownloadProblem(this);
        if (!updateReadyPromptShown) updateReadyPromptShown = UpdateChecker.showReadyInstallPrompt(this);
        refreshOnlineOverlaySafely();
        // Let the PC refresh version and inventory information as soon as the app
        // becomes visible; the receiver's regular beacon remains the fallback.
        OnlineService.requestImmediateBeacon(this);
    }

    /**
     * Android 10 can reject a plain background startService call during the
     * resume transition.  The old implementation let that exception escape
     * from onResume, which made a valid update package look like an install or
     * parse failure because the app crashed immediately after installation.
     * OnlineService already promotes itself to a foreground service for every
     * command, so use the foreground-start API on Android O+ and keep the UI
     * recoverable if the OS still defers the request.
     */
    private void refreshOnlineOverlaySafely() {
        Intent intent = new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_REFRESH_OVERLAY);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (IllegalStateException | SecurityException error) {
            DiagnosticLog.write(this, "overlay_refresh_deferred",
                    error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage()));
        }
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
        File legacy = tree == null ? null : legacyRoot(tree);
        Uri usableTree = isTreeReadable(tree) ? tree : null;
        if (usableTree == null && legacy == null) return new ExternalTrashManager.Result();
        ExternalTrashManager.Result result = ExternalTrashManager.moveTrashedSources(
                getContentResolver(), usableTree, legacy, library);
        if (!result.succeeded()) {
            DiagnosticLog.write(this, "external_trash_move_failed", result.firstFailure());
        }
        return result;
    }

    private ExternalTrashManager.Result clearExternalTrash(WorkLibrary library) throws IOException {
        Uri tree = selectedTree();
        File legacy = tree == null ? null : legacyRoot(tree);
        Uri usableTree = isTreeReadable(tree) ? tree : null;
        return ExternalTrashManager.clearTrackedTrash(
                getContentResolver(), usableTree, legacy, library);
    }

    private ExternalTrashManager.Result purgeExpiredTrash(WorkLibrary library) throws IOException {
        Uri tree = selectedTree();
        File legacy = tree == null ? null : legacyRoot(tree);
        Uri usableTree = isTreeReadable(tree) ? tree : null;
        return ExternalTrashManager.purgeExpired(
                getContentResolver(), usableTree, legacy,
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
        String selectedName = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_TREE_NAME, "");
        if (selectedName == null || selectedName.trim().isEmpty()
                || "已设置".equals(selectedName)) selectedName = treeName(tree);
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .getCanonicalFile();
        File selected = new File(downloads, selectedName).getCanonicalFile();
        if (!selected.toPath().startsWith(downloads.toPath()) || !selected.isDirectory()) {
            DiagnosticLog.write(this, "legacy_storage_unmapped", "selected=" + selectedName);
            return null;
        }
        return selected;
    }

    private boolean isTreeReadable(Uri tree) {
        if (tree == null) return false;
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
            try (Cursor cursor = getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                if (cursor == null) return false;
                cursor.getCount();
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showFolderPermissionRecovery() {
        statusText.setText("作品文件夹权限已失效，请重新选择 Lark 文件夹");
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("重新连接作品文件夹")
                .setMessage("华为系统更新了文件夹地址。重新选择一次 Lark 后，作品、回收站和文件管理器会继续双向同步，现有记录不会丢失。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("重新选择", (dialog, which) -> chooseFolder())
                .show();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(11), dp(12), dp(11));
        card.setBackground(roundWithStroke(
                Color.WHITE, 16, Color.rgb(224, 228, 226)));
        card.setElevation(dp(1));
        return card;
    }

    private Button smallButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(47, 48, 46));
        button.setBackground(round(primary ? Color.rgb(15, 155, 99) : Color.rgb(232, 234, 233), 14));
        button.setPadding(dp(13), 0, dp(13), 0);
        return button;
    }

    private ImageButton iconButton(int imageResource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(imageResource);
        button.setImageTintList(ColorStateList.valueOf(Color.rgb(15, 135, 88)));
        button.setBackground(round(Color.rgb(226, 244, 236), 21));
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setContentDescription(description);
        return button;
    }

    private LinearLayout.LayoutParams iconParams(boolean withLeftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(42), dp(42));
        if (withLeftMargin) params.setMargins(dp(3), 0, 0, 0);
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

    private GradientDrawable roundWithStroke(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = round(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class FileEntry {
        final String id;
        final String name;
        final String mime;
        final boolean directory;
        final long size;

        FileEntry(String id, String name, String mime, long size) {
            this.id = id;
            this.name = name == null ? "未命名" : name;
            this.mime = mime;
            this.directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
            this.size = size;
        }
    }
}
