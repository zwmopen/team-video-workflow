package com.zwm.gallery;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.animation.LayoutTransition;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import java.util.Collections;
import java.util.HashSet;
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
    private static final int THUMBNAIL_CACHE_SIZE = (int) Math.max(16 * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
    private static final LruCache<String, Bitmap> THUMBNAIL_CACHE = new LruCache<String, Bitmap>(THUMBNAIL_CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private static final ExecutorService THUMBNAIL_EXECUTOR = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "thumb-decode");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final Set<String> pendingTrashIds = Collections.synchronizedSet(new HashSet<>());
    private static final String PREFS = "device_share";
    private static final String PREF_TREE_URI = "libraryTreeUri";
    private static final String PREF_TREE_NAME = "libraryTreeName";
    private static final String PREF_IS_ONLINE_MODE = "is_online_mode";
    private static final int REQUEST_TREE = 61;
    private static final int REQUEST_LEGACY_STORAGE = 62;
    public static volatile boolean isVisible;

    static String formatDisplayTitle(String rawTitle) {
        if (rawTitle == null) return "";
        String clean = rawTitle.trim();
        boolean changed = true;
        while (changed) {
            String prev = clean;
            clean = clean.replaceFirst("^\\d{8}[_\\-]\\d{6}[_\\-]?", "");
            clean = clean.replaceFirst("^\\d{8}[_\\-]?", "");
            clean = clean.replaceFirst("^(网页CDP|CodexAPI|Codex|CDP|制作中|待补全|成品)[_\\-]?", "");
            clean = clean.replaceFirst("^[（\\(\\[【\\_\\-\\s]+", "");
            clean = clean.replaceFirst("[\\)\\]】\\_\\-\\s]+$", "");
            clean = clean.replace("[转]", "");
            clean = clean.replaceAll("[\\(（]?_{0,3}COPY_FORMAT_\\d+_{0,3}[\\)）]?", "");
            clean = clean.replaceAll("<{1,3}COPY_FORMAT:\\d+>{1,3}", "");
            clean = clean.trim();
            changed = !clean.equals(prev);
        }
        return clean.isEmpty() ? rawTitle : clean;
    }

    static String extractTimestampBadge(String rawTitle) {
        if (rawTitle == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})").matcher(rawTitle);
        if (m.find()) {
            return m.group(2) + "-" + m.group(3) + " " + m.group(4) + ":" + m.group(5);
        }
        java.util.regex.Matcher mDate = java.util.regex.Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})").matcher(rawTitle);
        if (mDate.find()) {
            return mDate.group(2) + "-" + mDate.group(3);
        }
        return "";
    }

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
    /** Last complete list rendered on the UI; selection changes must not rescan the library. */
    private List<WorkLibrary.WorkEntry> renderedWorks = new ArrayList<>();
    private String selectedCategory = WorkCategory.ALL;
    private LinearLayout categoryBar;
    private FrameLayout categorySelector;
    private HorizontalScrollView categoryScrollView;
    private SpringScrollView contentScroll;
    private LinearLayout refreshIndicator;
    private ProgressBar refreshSpinner;
    private TextView refreshIndicatorText;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean updateReadyPromptShown;
    private final Map<String, Button> categoryButtons = new LinkedHashMap<>();
    private final Map<String, String> categoryLabels = new LinkedHashMap<>();
    private LinearLayout searchBar;
    private EditText searchInput;
    private ImageView clearSearchButton;
    private String searchQuery = "";
    private OnlineGalleryClient onlineClient;
    private boolean isOnlineMode = false;
    private ImageButton sourceModeButton;
    private OnlineGalleryClient.CategoriesResult lastCategoriesResult;
    private final List<OnlineWorkEntry> onlineWorks = new ArrayList<>();
    private String selectedOnlineCategory = WorkCategory.ALL;
    private final Map<String, Button> onlineCategoryButtons = new LinkedHashMap<>();
    private final Map<String, String> onlineCategoryLabels = new LinkedHashMap<>();
    private int onlinePageLimit = 25;
    private final List<OnlineWorkEntry> currentOnlineFilteredEntries = new ArrayList<>();

    static boolean matchesSearchTokens(String name, String folder, String[] tokens) {
        if (tokens == null || tokens.length == 0) return true;
        String cleanName = name != null ? name.toLowerCase(Locale.ROOT) : "";
        String cleanFolder = folder != null ? folder.toLowerCase(Locale.ROOT) : "";
        for (String token : tokens) {
            if (token == null || token.isEmpty()) continue;
            String lowerToken = token.toLowerCase(Locale.ROOT);
            boolean inName = cleanName.contains(lowerToken);
            boolean inFolder = cleanFolder.contains(lowerToken);
            if (!inName && !inFolder) {
                return false;
            }
        }
        return true;
    }

    private void hideKeyboard(View view) {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

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
        isOnlineMode = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_IS_ONLINE_MODE, false);
        onlineClient = new OnlineGalleryClient(this);
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
        if (isOnlineMode) {
            refreshOnlineWorks(false);
        } else if (fileMode) {
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
        if (searchQuery != null && !searchQuery.isEmpty()) {
            if (searchInput != null) {
                searchInput.setText("");
            }
            searchQuery = "";
            hideKeyboard(searchInput);
            if (searchInput != null) searchInput.clearFocus();
            if (isOnlineMode) {
                applyOnlineCategoryFilter(selectedOnlineCategory);
            } else {
                applyCategoryFilter(selectedCategory);
            }
            return;
        }
        if (!selectedWorkIds.isEmpty()) {
            selectedWorkIds.clear();
            quickTrashButton.setVisibility(View.GONE);
            refreshWorks();
            return;
        }
        if (isOnlineMode) {
            switchToLocalMode();
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
        root.setClipChildren(false);
        root.setClipToPadding(false);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(12), dp(18), dp(12), dp(10));
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
        if (isOnlineMode) {
            modeButton.setVisibility(View.GONE);
        }
        titleRow.addView(modeButton, iconParams(false));
        titleRow.addView(titleCluster, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton transfer = iconButton(R.drawable.ic_album_transfer, "传送文件");
        transfer.setOnClickListener(v -> {
            toast("文件传输");
            startActivity(new Intent(this, TransferActivity.class));
        });
        titleRow.addView(transfer, iconParams(true));

        sourceModeButton = iconButton(R.drawable.ic_mode_phone, "当前：手机本地作品 (点击切换到电脑在线)");
        sourceModeButton.setOnClickListener(v -> toggleSourceMode());
        sourceModeButton.setOnLongClickListener(v -> {
            showOnlineStatusOrConfigDialog();
            return true;
        });
        updateSourceModeButtonStyle();
        titleRow.addView(sourceModeButton, iconParams(true));

        leftModeButton = iconButton(R.drawable.ic_album_refresh, isOnlineMode ? "刷新电脑作品" : "刷新作品");
        leftModeButton.setVisibility(isOnlineMode ? View.VISIBLE : View.GONE);
        leftModeButton.setOnClickListener(v -> {
            if (fileMode) {
                refreshFiles();
                toast("正在刷新文件");
            } else if (showingTrash) {
                showWorks();
            } else if (isOnlineMode) {
                toast("正在刷新电脑作品…");
                refreshOnlineWorks(true);
            } else {
                toast("正在刷新作品");
                importSelectedTree(true);
            }
        });
        titleRow.addView(leftModeButton, iconParams(true));
        rightModeButton = iconButton(R.drawable.ic_album_trash, "回收站");
        rightModeButton.setVisibility(isOnlineMode ? View.GONE : View.VISIBLE);
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
        categoryBar.setPadding(dp(3), dp(3), dp(3), dp(3));

        categoryScrollView = new HorizontalScrollView(this);
        categoryScrollView.setHorizontalScrollBarEnabled(false);
        categoryScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        categoryScrollView.addView(categoryBar, new FrameLayout.LayoutParams(-2, -1));

        categorySelector = new FrameLayout(this);
        categorySelector.setBackground(round(Color.rgb(232, 234, 233), 11));
        categorySelector.addView(categoryScrollView, new FrameLayout.LayoutParams(-1, dp(40)));

        searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(roundWithStroke(Color.WHITE, 12, Color.rgb(220, 224, 222)));
        searchBar.setPadding(dp(10), 0, dp(6), 0);

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(R.drawable.ic_album_search);
        searchIcon.setImageTintList(ColorStateList.valueOf(Color.rgb(130, 136, 133)));
        LinearLayout.LayoutParams sIconParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        sIconParams.setMargins(0, 0, dp(6), 0);
        searchBar.addView(searchIcon, sIconParams);

        searchInput = new EditText(this);
        searchInput.setHint("搜索作品标题或文件夹/目的地...");
        searchInput.setHintTextColor(Color.rgb(155, 160, 158));
        searchInput.setTextColor(Color.rgb(32, 34, 33));
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        searchInput.setBackground(null);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(0, dp(4), 0, dp(4));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String newQuery = s != null ? s.toString().trim() : "";
                clearSearchButton.setVisibility(newQuery.isEmpty() ? View.GONE : View.VISIBLE);
                if (!newQuery.equals(searchQuery)) {
                    searchQuery = newQuery;
                    if (isOnlineMode) {
                        applyOnlineCategoryFilter(selectedOnlineCategory);
                    } else {
                        applyCategoryFilter(selectedCategory);
                    }
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(searchInput);
                searchInput.clearFocus();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams sInputParams = new LinearLayout.LayoutParams(0, -2, 1);
        searchBar.addView(searchInput, sInputParams);

        clearSearchButton = new ImageView(this);
        clearSearchButton.setImageResource(R.drawable.ic_album_clear);
        clearSearchButton.setImageTintList(ColorStateList.valueOf(Color.rgb(150, 155, 153)));
        clearSearchButton.setVisibility(View.GONE);
        clearSearchButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        clearSearchButton.setContentDescription("清空搜索");
        clearSearchButton.setOnClickListener(v -> {
            searchInput.setText("");
            searchQuery = "";
            hideKeyboard(searchInput);
            searchInput.clearFocus();
            if (isOnlineMode) {
                applyOnlineCategoryFilter(selectedOnlineCategory);
            } else {
                applyCategoryFilter(selectedCategory);
            }
        });
        LinearLayout.LayoutParams sClearParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        searchBar.addView(clearSearchButton, sClearParams);

        worksContainer = new LinearLayout(this);
        worksContainer.setClipChildren(false);
        worksContainer.setClipToPadding(false);
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
        contentScroll.setSwipeListener(new SpringScrollView.SwipeListener() {
            @Override public void onSwipeLeft() {
                if (isOnlineMode) switchToNextOnlineCategory();
                else switchToNextCategory();
            }
            @Override public void onSwipeRight() {
                if (isOnlineMode) switchToPreviousOnlineCategory();
                else switchToPreviousCategory();
            }
        });
        contentScroll.setPullRefreshListener(new SpringScrollView.PullRefreshListener() {
            @Override public void onPull(float progress, boolean ready) {
                showPullProgress(progress, ready);
            }

            @Override public void onRefresh() {
                beginVisibleRefresh();
                if (fileMode) refreshFiles();
                else if (showingTrash) refreshWorks();
                else if (isOnlineMode) refreshOnlineWorks(true);
                else importSelectedTree(true);
            }

            @Override public void onReset() {
                if (refreshIndicator != null && refreshSpinner.getVisibility() != View.VISIBLE) {
                    refreshIndicator.animate().alpha(0f).setDuration(140).start();
                }
            }
        });
        contentScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (searchInput != null && searchInput.isFocused()) {
                hideKeyboard(searchInput);
                searchInput.clearFocus();
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
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(38));
        searchParams.setMargins(dp(12), 0, dp(12), dp(8));
        frozenLayout.addView(searchBar, searchParams);
        LinearLayout.LayoutParams categoryParams = new LinearLayout.LayoutParams(-1, dp(40));
        categoryParams.setMargins(dp(12), 0, dp(12), dp(4));
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
        if (isOnlineMode) {
            refreshOnlineWorks(false);
            return;
        }
        worker.execute(() -> {
            if (isOnlineMode) return;
            try {
                WorkLibrary library = library();
                if (library.reconciledDuplicates() > 0) {
                    DiagnosticLog.write(this, "duplicate_works_merged",
                            "count=" + library.reconciledDuplicates());
                }
                // Fast-path: render visible items immediately so UI appears instantly without waiting for cleanup
                List<WorkLibrary.WorkEntry> initialActive = library.listActive();
                if (!showingTrash && !pendingTrashIds.isEmpty()) {
                    initialActive = new ArrayList<>(initialActive);
                    initialActive.removeIf(entry -> pendingTrashIds.contains(entry.id));
                }
                List<WorkLibrary.WorkEntry> initialEntries = showingTrash ? library.listTrash() : initialActive;
                final List<WorkLibrary.WorkEntry> finalInitial = initialEntries;
                runOnUiThread(() -> {
                    if (isOnlineMode) return;
                    renderWorks(finalInitial);
                    finishVisibleRefresh(showingTrash ? "回收站已刷新" : "已刷新，共 " + finalInitial.size() + " 个");
                });

                CleanupCoordinator.Result cleanup = CleanupCoordinator.run(this);
                if (!cleanup.failure.isEmpty()) {
                    DiagnosticLog.write(this, "external_trash_purge_failed", cleanup.failure);
                }
                List<WorkLibrary.WorkEntry> activeEntries = library.listActive();
                if (!showingTrash && !pendingTrashIds.isEmpty()) {
                    activeEntries = new ArrayList<>(activeEntries);
                    activeEntries.removeIf(entry -> pendingTrashIds.contains(entry.id));
                }
                List<WorkLibrary.WorkEntry> entries = showingTrash ? library.listTrash() : activeEntries;
                OnlineService.publishWorkInventory(this, activeEntries);
                if (cleanup.moved > 0 || cleanup.deleted > 0 || entries.size() != finalInitial.size()) {
                    final List<WorkLibrary.WorkEntry> finalEntries = entries;
                    runOnUiThread(() -> {
                        if (isOnlineMode) return;
                        renderWorks(finalEntries, false);
                    });
                }
            } catch (Exception error) {
                DiagnosticLog.write(this, "library_refresh_failed", error.getMessage());
                runOnUiThread(() -> {
                    if (isOnlineMode) return;
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
        if (searchBar != null) searchBar.setVisibility(View.VISIBLE);
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
        if (searchBar != null) searchBar.setVisibility(View.GONE);
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
        renderedWorks.clear();
        selectedWorkIds.clear();
        quickTrashButton.setVisibility(View.GONE);
        worksContainer.removeAllViews();
        TextView empty = text("回收站是空的", 14, false);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Color.GRAY);
        empty.setPadding(dp(14), dp(28), dp(14), dp(28));
        empty.setBackground(round(Color.WHITE, 18));
        worksContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        scannedCountText.setText("0");
        statusText.setText("回收站已清空");
        toast("回收站已清空");

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
                try {
                    OnlineService.publishWorkInventory(this, library.listActive());
                } catch (Exception ignored) { }
            } catch (Exception error) {
                DiagnosticLog.write(this, "trash_clear_failed",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                runOnUiThread(() -> {
                    toast("清空失败：" + error.getMessage());
                    refreshWorks();
                });
            }
        });
    }

    private void renderWorks(List<WorkLibrary.WorkEntry> entries) {
        renderWorks(entries, false);
    }

    private void renderWorks(List<WorkLibrary.WorkEntry> entries, boolean animate) {
        if (fileMode) return;
        if (isOnlineMode) return;
        List<WorkLibrary.WorkEntry> cleanEntries = entries;
        if (!showingTrash && !pendingTrashIds.isEmpty()) {
            cleanEntries = new ArrayList<>(entries);
            cleanEntries.removeIf(entry -> pendingTrashIds.contains(entry.id));
        }
        renderedWorks = new ArrayList<>(cleanEntries);
        if (!showingTrash) updateCategoryCounts(cleanEntries);
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        String[] tokens = query.isEmpty() ? new String[0] : query.split("\\s+");
        List<WorkLibrary.WorkEntry> displayEntries = new ArrayList<>();
        for (WorkLibrary.WorkEntry entry : cleanEntries) {
            if (!showingTrash && !WorkCategory.ALL.equals(selectedCategory) && !selectedCategory.equals(entry.getFolderName())) {
                continue;
            }
            if (!showingTrash && tokens.length > 0 && !matchesSearchTokens(entry.name, entry.getFolderName(), tokens)) {
                continue;
            }
            displayEntries.add(entry);
        }
        renderWorksCards(displayEntries, animate);
    }

    private void renderWorksCards(List<WorkLibrary.WorkEntry> entries, boolean animate) {
        LayoutTransition transition = worksContainer.getLayoutTransition();
        if (!animate) worksContainer.setLayoutTransition(null);
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
            String emptyMsg;
            if (showingTrash) {
                emptyMsg = "回收站是空的";
            } else if (searchQuery != null && !searchQuery.trim().isEmpty()) {
                emptyMsg = "未找到匹配「" + searchQuery.trim() + "」的作品\n请尝试搜索其他标题或文件夹关键字";
            } else {
                emptyMsg = "还没有作品\n从电脑拖入 ZIP，或选择手机里的 Lark 文件夹";
            }
            TextView empty = text(emptyMsg, 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(14), dp(28), dp(14), dp(28));
            empty.setBackground(round(Color.WHITE, 18));
            worksContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            if (!animate) worksContainer.setLayoutTransition(transition);
            return;
        }
        for (WorkLibrary.WorkEntry entry : entries) {
            worksContainer.addView(workCard(entry), margins(0, 0, 0, dp(10)));
        }
        if (!animate) worksContainer.setLayoutTransition(transition);
    }

    private void applyCategoryFilter(String folderKey) {
        if (fileMode || showingTrash) return;
        if (renderedWorks == null || renderedWorks.isEmpty()) {
            refreshWorks();
            return;
        }
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        String[] tokens = query.isEmpty() ? new String[0] : query.split("\\s+");

        // If a specific folder is selected, check if user searched for something outside this folder
        // If current folder has 0 matches for this search query, but ALL works has matches,
        // automatically switch to ALL so user sees the results!
        if (!WorkCategory.ALL.equals(folderKey) && tokens.length > 0) {
            boolean hasMatchesInCurrent = false;
            boolean hasMatchesInAll = false;
            for (WorkLibrary.WorkEntry entry : renderedWorks) {
                if (pendingTrashIds.contains(entry.id)) continue;
                if (matchesSearchTokens(entry.name, entry.getFolderName(), tokens)) {
                    hasMatchesInAll = true;
                    if (folderKey.equals(entry.getFolderName())) {
                        hasMatchesInCurrent = true;
                        break;
                    }
                }
            }
            if (!hasMatchesInCurrent && hasMatchesInAll) {
                selectedCategory = WorkCategory.ALL;
                folderKey = WorkCategory.ALL;
                for (Map.Entry<String, Button> item : categoryButtons.entrySet()) {
                    applyCategoryButtonStyle(item.getValue(), item.getKey().equals(selectedCategory));
                }
            }
        }

        List<WorkLibrary.WorkEntry> filtered = new ArrayList<>();
        for (WorkLibrary.WorkEntry entry : renderedWorks) {
            if (pendingTrashIds.contains(entry.id)) continue;
            if (!WorkCategory.ALL.equals(folderKey) && !folderKey.equals(entry.getFolderName())) {
                continue;
            }
            if (tokens.length > 0 && !matchesSearchTokens(entry.name, entry.getFolderName(), tokens)) {
                continue;
            }
            filtered.add(entry);
        }

        renderWorksCards(filtered, false);
        if (contentScroll != null) {
            contentScroll.scrollTo(0, 0);
        }
    }

    private View workCard(WorkLibrary.WorkEntry work) {
        LinearLayout card = card();
        card.setTag(work.id);
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
        String displayTitle = formatDisplayTitle(work.name);
        String title = work.used ? "📌 " + displayTitle : displayTitle;
        TextView name = text(title, 14, true);
        name.setMaxLines(1);
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

        card.addView(previewStrip(work), margins(0, dp(2), 0, dp(2)));
        String detail = work.images.size() + " 张图片";
        if (work.used) {
            detail += "\n✓ 小红书 " + work.xhsShareCount + " · 抖音 " + work.douyinShareCount;
            detail += "\n" + deleteCountdown(work);
        }
        else if (work.trashedDate != null) detail += "\n等待自动清理";
        else if (!work.warning.isEmpty()) detail += "\n请检查多个 TXT";
        TextView meta = text(detail, 12, false);
        meta.setTextColor(work.sharedDate == null ? Color.GRAY : Color.rgb(78, 78, 75));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
        metaParams.setMargins(0, dp(3), 0, dp(5));
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
            else if (!showingTrash) openPreview(work);
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
            FlowLayout platformRow = new FlowLayout(this);
            platformRow.setClipChildren(false);
            platformRow.setClipToPadding(false);
            platformRow.setHorizontalSpacing(dp(8));
            platformRow.setVerticalSpacing(dp(8));
            List<PlatformCopyParser.AvailableItem> rawPlatforms =
                    PlatformCopyParser.parseAvailablePlatforms(work.text);
            List<PlatformCopyParser.AvailableItem> availablePlatforms =
                    enrichPlatformSuite(rawPlatforms, work.text, work.name);
            for (PlatformCopyParser.AvailableItem item : availablePlatforms) {
                int clickCount = 0;
                if (item.platform == PlatformCopyParser.Platform.DOUYIN) {
                    clickCount = work.douyinShareCount;
                } else if (item.platform == PlatformCopyParser.Platform.XHS
                        || item.platform == PlatformCopyParser.Platform.XHS_2
                        || item.platform == PlatformCopyParser.Platform.XHS_3) {
                    clickCount = work.xhsShareCount;
                }
                Button btn = compactButton(item.buttonLabel, clickCount == 0);
                btn.setContentDescription(item.buttonLabel + "，已点击 " + clickCount + " 次");
                final int finalClickCount = clickCount;
                btn.setOnClickListener(v -> {
                    markPlatformButtonClicked(btn, item.buttonLabel, finalClickCount);
                    openShare(work, item.platform.code);
                });
                final String copyForPlatform = (item.copyText != null && !item.copyText.isEmpty())
                        ? item.copyText : PlatformCopyParser.extractPlatformCopy(work.text, item.platform);
                btn.setOnLongClickListener(v -> {
                    showCopyPreviewDialog(work.name, item.buttonLabel, copyForPlatform, () -> {
                        markPlatformButtonClicked(btn, item.buttonLabel, finalClickCount);
                        openShare(work, item.platform.code);
                    });
                    return true;
                });
                platformRow.addView(btn, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            }
            if (work.shareCount > 0) {
                Button reset = new Button(this);
                reset.setText("重置");
                styleNeumorphicButton(reset, STYLE_MUTED_GRAY);
                reset.setContentDescription("重置作品状态为未发布");
                reset.setOnClickListener(v -> confirmResetWork(work.id));
                platformRow.addView(reset, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            }
            Button delete = new Button(this);
            delete.setText("删除");
            styleNeumorphicButton(delete, STYLE_DANGER_WHITE);
            delete.setContentDescription("删除作品，移到回收站");
            delete.setOnClickListener(v -> confirmMoveWorkToTrash(work.id));
            platformRow.addView(delete, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            LinearLayout.LayoutParams platformRowParams = new LinearLayout.LayoutParams(-1, -2);
            platformRowParams.setMargins(0, dp(8), 0, dp(2));
            card.addView(platformRow, platformRowParams);
        }
        return card;
    }

    private View previewStrip(WorkLibrary.WorkEntry work) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(0, 0, dp(2), 0);
        strip.setClipChildren(true);
        strip.setClipToPadding(true);
        final List<Runnable> deferredLoads = new ArrayList<>();
        for (int index = 0; index < work.images.size(); index++) {
            String imageName = work.images.get(index);
            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackground(roundWithStroke(
                    Color.rgb(239, 242, 240), 10, Color.rgb(216, 222, 218)));
            thumbnail.setClipToOutline(true);
            thumbnail.setContentDescription("预览第 " + (index + 1) + " 张图片");
            File imageFile = new File(work.directory, imageName);
            if (index < 4) {
                loadThumbnailAsync(imageFile, dp(68), thumbnail);
            } else {
                String path = imageFile.getAbsolutePath();
                Bitmap cached = THUMBNAIL_CACHE.get(path);
                if (cached != null && !cached.isRecycled()) {
                    thumbnail.setImageBitmap(cached);
                } else {
                    deferredLoads.add(() -> loadThumbnailAsync(imageFile, dp(68), thumbnail));
                }
            }
            final int imageIndex = index;
            thumbnail.setOnClickListener(v -> openPreview(work, imageIndex));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(68), dp(68));
            if (index > 0) params.setMargins(dp(7), 0, 0, 0);
            strip.addView(thumbnail, params);
        }
        if (!deferredLoads.isEmpty()) {
            scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (scrollX > dp(20)) {
                    scroll.setOnScrollChangeListener(null);
                    for (Runnable task : deferredLoads) {
                        task.run();
                    }
                }
            });
        }
        scroll.addView(strip, new HorizontalScrollView.LayoutParams(-2, dp(68)));
        return scroll;
    }

    private void loadThumbnailAsync(File image, int targetPx, ImageView targetView) {
        if (!image.isFile()) {
            targetView.setImageDrawable(null);
            return;
        }
        String path = image.getAbsolutePath();
        targetView.setTag(path);
        Bitmap cached = THUMBNAIL_CACHE.get(path);
        if (cached != null && !cached.isRecycled()) {
            targetView.setImageBitmap(cached);
            return;
        }
        targetView.setImageDrawable(null);
        THUMBNAIL_EXECUTOR.execute(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) { }
            if (!path.equals(targetView.getTag())) return;
            Bitmap decoded = decodeThumbnail(image, targetPx);
            if (decoded != null) {
                THUMBNAIL_CACHE.put(path, decoded);
                runOnUiThread(() -> {
                    if (path.equals(targetView.getTag())) {
                        targetView.setImageBitmap(decoded);
                    }
                });
            }
        });
    }

    private Bitmap decodeThumbnail(File image, int targetPx) {
        try {
            if (!image.isFile()) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > targetPx * 2 || bounds.outHeight / sample > targetPx * 2) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(image.getAbsolutePath(), options);
        } catch (Throwable t) {
            return null;
        }
    }

    static String formatFolderLabel(String name) {
        if (name == null || name.trim().isEmpty() || WorkCategory.ALL.equals(name)) {
            return "全部";
        }
        String trimmed = name.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > 5) {
            int offset = trimmed.offsetByCodePoints(0, 5);
            return trimmed.substring(0, offset) + "...";
        }
        return trimmed;
    }

    private void updateCategoryCounts(List<WorkLibrary.WorkEntry> entries) {
        if (isOnlineMode) return;
        Map<String, Integer> folderCounts = new LinkedHashMap<>();
        for (WorkLibrary.WorkEntry entry : entries) {
            String folder = entry.getFolderName();
            if (folder != null && !folder.trim().isEmpty()) {
                folder = folder.trim();
                folderCounts.put(folder, folderCounts.getOrDefault(folder, 0) + 1);
            }
        }
        if (!WorkCategory.ALL.equals(selectedCategory) && !folderCounts.containsKey(selectedCategory)) {
            selectedCategory = WorkCategory.ALL;
        }

        categoryBar.removeAllViews();
        categoryButtons.clear();
        categoryLabels.clear();

        String allLabel = "全部 " + entries.size();
        Button allButton = createCategoryButton(WorkCategory.ALL, "全部", allLabel, WorkCategory.ALL.equals(selectedCategory));
        categoryBar.addView(allButton);
        categoryButtons.put(WorkCategory.ALL, allButton);
        categoryLabels.put(WorkCategory.ALL, "全部");

        for (Map.Entry<String, Integer> item : folderCounts.entrySet()) {
            String folderName = item.getKey();
            int count = item.getValue();
            String formatted = formatFolderLabel(folderName);
            String fullLabel = formatted + " " + count;
            boolean isSelected = folderName.equals(selectedCategory);
            Button folderButton = createCategoryButton(folderName, formatted, fullLabel, isSelected);
            categoryBar.addView(folderButton);
            categoryButtons.put(folderName, folderButton);
            categoryLabels.put(folderName, formatted);
        }
    }

    public void switchToNextCategory() {
        if (categoryButtons.size() <= 1 || fileMode || showingTrash) return;
        List<String> keys = new ArrayList<>(categoryButtons.keySet());
        int currentIndex = keys.indexOf(selectedCategory);
        if (currentIndex < 0) currentIndex = 0;
        int nextIndex = (currentIndex + 1) % keys.size();
        selectCategory(keys.get(nextIndex), true);
    }

    public void switchToPreviousCategory() {
        if (categoryButtons.size() <= 1 || fileMode || showingTrash) return;
        List<String> keys = new ArrayList<>(categoryButtons.keySet());
        int currentIndex = keys.indexOf(selectedCategory);
        if (currentIndex < 0) currentIndex = 0;
        int prevIndex = (currentIndex - 1 + keys.size()) % keys.size();
        selectCategory(keys.get(prevIndex), true);
    }

    private void selectCategory(String folderKey, boolean showToast) {
        if (folderKey == null || !categoryButtons.containsKey(folderKey)) return;
        if (folderKey.equals(selectedCategory)) return;
        selectedCategory = folderKey;
        for (Map.Entry<String, Button> item : categoryButtons.entrySet()) {
            applyCategoryButtonStyle(item.getValue(), item.getKey().equals(selectedCategory));
        }
        applyCategoryFilter(selectedCategory);
        Button activeBtn = categoryButtons.get(folderKey);
        if (activeBtn != null && categoryScrollView != null) {
            int scrollX = activeBtn.getLeft() - (categoryScrollView.getWidth() - activeBtn.getWidth()) / 2;
            categoryScrollView.smoothScrollTo(Math.max(0, scrollX), 0);
        }
        if (showToast) {
            String displayBase = categoryLabels.get(folderKey);
            if (displayBase == null) displayBase = folderKey;
            toast("已显示 " + displayBase);
        }
    }

    private Button createCategoryButton(String folderKey, String displayBase, String buttonText, boolean isSelected) {
        Button button = new Button(this);
        button.setText(buttonText);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinHeight(dp(34));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setElevation(0);
        button.setGravity(Gravity.CENTER);
        applyCategoryButtonStyle(button, isSelected);

        button.setOnClickListener(v -> selectCategory(folderKey, true));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(34));
        params.setMargins(dp(2), 0, dp(2), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void applyCategoryButtonStyle(Button button, boolean isSelected) {
        if (isSelected) {
            button.setBackground(round(Color.WHITE, 10));
            button.setTextColor(Color.rgb(24, 25, 24));
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            button.setElevation(dp(1));
        } else {
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setTextColor(Color.rgb(104, 108, 106));
            button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            button.setElevation(0);
        }
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

    private void openPreview(WorkLibrary.WorkEntry work) {
        openPreview(work, -1);
    }

    private void openPreview(WorkLibrary.WorkEntry work, int imageIndex) {
        startActivity(new Intent(this, WorkDetailActivity.class)
                .putExtra(WorkDetailActivity.EXTRA_WORK_ID, work.id)
                .putExtra(WorkDetailActivity.EXTRA_IMAGE_INDEX, imageIndex));
    }

    private void confirmMoveWorkToTrash(String id) {
        new AlertDialog.Builder(this)
                .setTitle("移到回收站？")
                .setMessage("作品会从当前列表消失，并移动到“相册回收站”；分享次数会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移到回收站", (dialog, which) -> {
                    LinkedHashSet<String> ids = new LinkedHashSet<>();
                    ids.add(id);
                    moveSelectedToTrash(ids);
                })
                .show();
    }

    private void confirmResetWork(String id) {
        new AlertDialog.Builder(this)
                .setTitle("重置为未发布？")
                .setMessage("确定要重置为未发布状态吗？\n将清零分享点击记录、取消 1 小时自动清理排期，并恢复为未发作品。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (dialog, which) -> resetWork(id))
                .show();
    }

    private void resetWork(String id) {
        worker.execute(() -> {
            try {
                library().resetShare(id);
                uiHandler.post(() -> {
                    Toast.makeText(this, "已重置为未发布", Toast.LENGTH_SHORT).show();
                    refreshWorks();
                });
            } catch (Exception error) {
                uiHandler.post(() -> Toast.makeText(this, "重置失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void toggleWorkSelection(String id) {
        if (selectedWorkIds.contains(id)) selectedWorkIds.remove(id);
        else selectedWorkIds.add(id);
        quickTrashButton.setVisibility(selectedWorkIds.isEmpty() ? View.GONE : View.VISIBLE);
        // Long-press selection is a local UI state change. Re-running the full
        // scanner here made one tap perform storage I/O and caused visible jank.
        if (!renderedWorks.isEmpty()) renderWorks(renderedWorks, false);
        else refreshWorks();
    }

    private void showWorksMode() {
        leaveFileMode();
        toast("作品分发模式");
        showWorks();
    }

    private void leaveFileMode() {
        fileMode = false;
        if (searchBar != null) searchBar.setVisibility(View.VISIBLE);
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
        if (searchBar != null) searchBar.setVisibility(View.GONE);
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

    private void optimisticRemoveWorks(Set<String> ids, String toastMessage) {
        if (ids == null || ids.isEmpty() || fileMode) return;
        selectedWorkIds.removeAll(ids);
        quickTrashButton.setEnabled(true);
        quickTrashButton.setVisibility(selectedWorkIds.isEmpty() ? View.GONE : View.VISIBLE);

        renderedWorks.removeIf(entry -> ids.contains(entry.id));

        for (int i = worksContainer.getChildCount() - 1; i >= 0; i--) {
            View child = worksContainer.getChildAt(i);
            if (child != null && ids.contains(child.getTag())) {
                worksContainer.removeViewAt(i);
            }
        }

        if (renderedWorks.isEmpty()) {
            TextView empty = text(showingTrash ? "回收站是空的" : "还没有作品\n从电脑拖入 ZIP，或选择手机里的 Lark 文件夹", 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(14), dp(28), dp(14), dp(28));
            empty.setBackground(round(Color.WHITE, 18));
            worksContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }

        if (!showingTrash) {
            String prevCategory = selectedCategory;
            updateCategoryCounts(renderedWorks);
            if (!prevCategory.equals(selectedCategory)) {
                renderWorksCards(renderedWorks, true);
            }
        }

        boolean selecting = !selectedWorkIds.isEmpty() && !showingTrash;
        headingText.setText(selecting ? "已选 " + selectedWorkIds.size() + " 个" : (showingTrash ? "回收站" : ""));
        scannedCountText.setText(String.valueOf(renderedWorks.size()));

        if (toastMessage != null && !toastMessage.isEmpty()) {
            statusText.setText(toastMessage);
            toast(toastMessage);
        }
    }

    private void moveSelectedToTrash(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        final LinkedHashSet<String> targets = new LinkedHashSet<>(ids);
        pendingTrashIds.addAll(targets);
        String msg = targets.size() > 1 ? "已移到回收站 " + targets.size() + " 个" : "已移到回收站";

        optimisticRemoveWorks(targets, msg);

        worker.execute(() -> {
            ArrayList<String> failures = new ArrayList<>();
            for (String id : targets) {
                try {
                    WorkLibrary library = library();
                    WorkLibrary.WorkEntry entry = library.moveToTrash(id, LocalDate.now());
                    Uri tree = selectedTree();
                    ExternalTrashManager.Result moved = ExternalTrashManager.moveTrashedSource(
                            getContentResolver(), tree, tree == null ? null : legacyRoot(tree), library, entry);
                    if (!moved.succeeded()) {
                        if (moved.moved == 0 && moved.alreadyMissing == 0) {
                            library.rollbackTrashMove(id);
                            pendingTrashIds.remove(id);
                        }
                        throw new IOException(moved.firstFailure());
                    }
                    DiagnosticLog.write(this, "manual_trash_move", id);
                } catch (Exception error) {
                    pendingTrashIds.remove(id);
                    failures.add(error.getMessage() == null ? "移动失败" : error.getMessage());
                }
            }

            try {
                OnlineService.publishWorkInventory(this, library().listActive());
            } catch (Exception ignored) { }

            pendingTrashIds.removeAll(targets);

            if (!failures.isEmpty()) {
                runOnUiThread(() -> {
                    toast("移到回收站失败 " + failures.size() + " 个，正在刷新");
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
        if (isOnlineMode) {
            refreshOnlineWorks(notifyWhenFinished);
            return;
        }
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
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(id);
        optimisticRemoveWorks(ids, "已恢复");

        worker.execute(() -> {
            try {
                WorkLibrary library = library();
                WorkLibrary.WorkEntry entry = library.getTrash(id);
                if (entry == null) throw new IOException("回收站作品不存在");
                Uri tree = selectedTree();
                ExternalTrashManager.restoreSource(
                        getContentResolver(), tree, legacyRoot(tree), library, entry);
                library.restore(id);
                try {
                    OnlineService.publishWorkInventory(this, library.listActive());
                } catch (Exception ignored) { }
            } catch (Exception error) {
                runOnUiThread(() -> {
                    toast("恢复失败：" + error.getMessage());
                    refreshWorks();
                });
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
        UpdateChecker.checkOnResume(this);
        UpdateChecker.reportDownloadProblem(this);
        if (!updateReadyPromptShown) updateReadyPromptShown = UpdateChecker.showReadyInstallPrompt(this);
        refreshOnlineServiceSafely();
        if (isVisible && !fileMode) refreshWorks();
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
    private void refreshOnlineServiceSafely() {
        Intent intent = new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_REFRESH_STATUS);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (IllegalStateException | SecurityException error) {
            DiagnosticLog.write(this, "status_refresh_deferred",
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
        Intent intent = new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_START);
        try {
            startForegroundService(intent);
        } catch (IllegalStateException | SecurityException error) {
            // MIUI/Android may defer a foreground-service start during the
            // activity launch transition. Do not crash the receiver UI; the
            // onResume refresh path retries once the activity is foreground.
            DiagnosticLog.write(this, "service_start_deferred",
                    error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage()));
        }
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
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.setClipChildren(true);
        card.setClipToPadding(true);
        card.setBackground(roundWithStroke(
                Color.WHITE, 16, Color.rgb(224, 228, 226)));
        card.setElevation(dp(1));
        return card;
    }

    private static final int STYLE_PRIMARY_GREEN = 0;
    private static final int STYLE_MUTED_GRAY = 1;
    private static final int STYLE_DANGER_WHITE = 2;

    @SuppressLint("ClickableViewAccessibility")
    private void applyFloatingSpringTouchEffect(View view, float normalElevationDp,
                                                Drawable normalBg, Drawable pressedBg) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate()
                            .scaleX(0.94f)
                            .scaleY(0.94f)
                            .translationZ(-dp(Math.max(1f, normalElevationDp - 0.5f)))
                            .setDuration(90)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    if (pressedBg != null) v.setBackground(pressedBg);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .translationZ(0f)
                            .setDuration(200)
                            .setInterpolator(new OvershootInterpolator(1.4f))
                            .start();
                    if (normalBg != null) v.setBackground(normalBg);
                    break;
            }
            return false;
        });
    }

    private void styleNeumorphicButton(Button button, int style) {
        button.setStateListAnimator(null);
        button.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        button.setClipToOutline(false);
        button.setAllCaps(false);
        button.setTextSize(12.5f);
        button.setMinHeight(dp(36));
        button.setMinimumHeight(dp(36));
        button.setPadding(dp(15), 0, dp(15), 0);
        button.setGravity(Gravity.CENTER);
        button.setMaxLines(1);

        Drawable normalBg;
        Drawable pressedBg;
        int shadowColor;
        float elevationDp = 2.5f;

        if (style == STYLE_PRIMARY_GREEN) {
            button.setTextColor(Color.WHITE);
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            normalBg = round(Color.rgb(16, 151, 99), 14);
            pressedBg = round(Color.rgb(11, 122, 80), 14);
            shadowColor = Color.argb(55, 16, 151, 99);
        } else if (style == STYLE_DANGER_WHITE) {
            button.setTextColor(Color.rgb(205, 58, 48));
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            normalBg = roundWithStroke(Color.rgb(255, 255, 255), 14, Color.rgb(243, 208, 204));
            pressedBg = roundWithStroke(Color.rgb(255, 242, 240), 14, Color.rgb(235, 185, 180));
            shadowColor = Color.argb(45, 205, 58, 48);
        } else { // STYLE_MUTED_GRAY
            button.setTextColor(Color.rgb(72, 80, 76));
            button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            normalBg = roundWithStroke(Color.rgb(240, 243, 241), 14, Color.rgb(222, 226, 224));
            pressedBg = roundWithStroke(Color.rgb(226, 230, 228), 14, Color.rgb(212, 216, 214));
            shadowColor = Color.argb(30, 60, 70, 65);
            elevationDp = 1.5f;
        }

        button.setElevation(dp(elevationDp));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            button.setOutlineAmbientShadowColor(shadowColor);
            button.setOutlineSpotShadowColor(shadowColor);
        }
        button.setBackground(normalBg);
        applyFloatingSpringTouchEffect(button, elevationDp, normalBg, pressedBg);
    }

    private Button smallButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        styleNeumorphicButton(button, primary ? STYLE_PRIMARY_GREEN : STYLE_MUTED_GRAY);
        button.setTextSize(13);
        return button;
    }

    private Button compactButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        styleNeumorphicButton(button, primary ? STYLE_PRIMARY_GREEN : STYLE_MUTED_GRAY);
        return button;
    }

    private void markPlatformButtonClicked(Button button, String label, int previousCount) {
        styleNeumorphicButton(button, STYLE_MUTED_GRAY);
        button.setContentDescription(label + "，已点击 " + Math.max(1, previousCount + 1) + " 次");
        button.setEnabled(true);
    }

    private LinearLayout.LayoutParams compactButtonRowParams(boolean withLeftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(36));
        if (withLeftMargin) params.setMargins(dp(8), 0, 0, 0);
        return params;
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
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    // ==========================================
    // Online Gallery & Copy Preview Features
    // ==========================================

    private void updateSourceModeButtonStyle() {
        if (sourceModeButton == null) return;
        if (!isOnlineMode) {
            sourceModeButton.setImageResource(R.drawable.ic_mode_phone);
            sourceModeButton.setImageTintList(ColorStateList.valueOf(Color.rgb(15, 135, 88)));
            sourceModeButton.setBackground(round(Color.rgb(226, 244, 236), 21));
            sourceModeButton.setContentDescription("当前：手机本地作品 (点击切换到电脑在线)");
        } else {
            sourceModeButton.setImageResource(R.drawable.ic_mode_pc);
            sourceModeButton.setImageTintList(ColorStateList.valueOf(Color.rgb(2, 132, 199)));
            sourceModeButton.setBackground(round(Color.rgb(224, 242, 254), 21));
            sourceModeButton.setContentDescription("当前：电脑在线作品 (点击切换到手机本地)");
        }
    }

    private void toggleSourceMode() {
        if (fileMode) leaveFileMode();
        if (showingTrash) showingTrash = false;
        if (isOnlineMode) {
            switchToLocalMode();
        } else {
            switchToOnlineMode();
        }
    }

    private void switchToLocalMode() {
        isOnlineMode = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_IS_ONLINE_MODE, false).apply();
        updateSourceModeButtonStyle();
        selectedWorkIds.clear();
        quickTrashButton.setVisibility(View.GONE);
        if (modeButton != null) modeButton.setVisibility(View.VISIBLE);
        leftModeButton.setVisibility(View.GONE);
        rightModeButton.setVisibility(View.VISIBLE);
        footerNote.setText("📱 手机本地作品：点击文案复制并唤起分享；长按文案按钮可全屏预览。");
        worksContainer.removeAllViews();
        showWorks();
        toast("已切换至：📱 手机本地作品");
    }

    private void switchToOnlineMode() {
        isOnlineMode = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_IS_ONLINE_MODE, true).apply();
        updateSourceModeButtonStyle();
        selectedWorkIds.clear();
        quickTrashButton.setVisibility(View.GONE);
        if (modeButton != null) modeButton.setVisibility(View.GONE);
        leftModeButton.setImageResource(R.drawable.ic_album_refresh);
        leftModeButton.setContentDescription("刷新电脑作品");
        leftModeButton.setVisibility(View.VISIBLE);
        rightModeButton.setVisibility(View.GONE);
        footerNote.setText("💻 电脑在线作品：实时读取电脑首发成品，点击文案复制并唤起分享；长按文案按钮可全屏预览。");
        worksContainer.removeAllViews();
        toast("已切换至：💻 电脑在线相册");

        if (!onlineWorks.isEmpty()) {
            if (lastCategoriesResult != null) {
                updateOnlineCategoryCounts(lastCategoriesResult, onlineWorks);
            }
            applyOnlineCategoryFilter(selectedOnlineCategory);
            statusText.setText("💻 电脑在线相册 (" + onlineClient.resolveBaseUrl() + ") · 共 " + onlineWorks.size() + " 套");
            refreshOnlineWorks(false);
        } else {
            refreshOnlineWorks(true);
        }
    }

    private void showOnlineStatusOrConfigDialog() {
        String modeStr = isOnlineMode ? "💻 电脑在线模式" : "📱 手机本地模式";
        String serverUrl = onlineClient.resolveBaseUrl();
        int count = onlineWorks.size();
        new AlertDialog.Builder(this)
                .setTitle("在线相册网络状态")
                .setMessage("当前状态：" + modeStr + "\n电脑服务：" + serverUrl + "\n在线作品缓存：" + count + " 套\n\n提示：点击小图标可直接在手机与电脑之间秒切。")
                .setNegativeButton("关闭", null)
                .setNeutralButton("修改电脑 IP", (dialog, which) -> showEditPcIpDialog())
                .setPositiveButton("重测连接", (dialog, which) -> {
                    toast("正在探测电脑相册服务…");
                    onlineClient.checkConnection(new OnlineGalleryClient.Callback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean ok) {
                            if (ok) {
                                toast("✅ 电脑相册连接正常！");
                                if (isOnlineMode) refreshOnlineWorks(true);
                            } else {
                                toast("⚠️ 电脑端口响应异常");
                            }
                        }

                        @Override
                        public void onError(Exception error) {
                            toast("❌ 连接失败：" + error.getMessage());
                        }
                    });
                })
                .show();
    }

    private void refreshOnlineWorks(boolean userInitiated) {
        statusText.setText("正在连接电脑在线相册…");
        onlineClient.fetchCategories(new OnlineGalleryClient.Callback<OnlineGalleryClient.CategoriesResult>() {
            @Override
            public void onSuccess(OnlineGalleryClient.CategoriesResult catResult) {
                lastCategoriesResult = catResult;
                onlineClient.fetchWorks(null, null, new OnlineGalleryClient.Callback<List<OnlineWorkEntry>>() {
                    @Override
                    public void onSuccess(List<OnlineWorkEntry> works) {
                        if (!isOnlineMode) return;
                        onlineWorks.clear();
                        if (works != null) {
                            onlineWorks.addAll(works);
                        }
                        updateOnlineCategoryCounts(catResult, onlineWorks);
                        applyOnlineCategoryFilter(selectedOnlineCategory);
                        statusText.setText("💻 已连接电脑在线相册 (" + onlineClient.resolveBaseUrl() + ") · 共 " + onlineWorks.size() + " 套");
                        finishVisibleRefresh("已刷新电脑在线作品 " + onlineWorks.size() + " 套");
                    }

                    @Override
                    public void onError(Exception error) {
                        if (!isOnlineMode) return;
                        handleOnlineError("读取作品列表失败", error);
                    }
                });
            }

            @Override
            public void onError(Exception error) {
                if (!isOnlineMode) return;
                handleOnlineError("连接电脑相册服务失败", error);
            }
        });
    }

    private void handleOnlineError(String prefix, Exception error) {
        finishVisibleRefresh("连接失败");
        String msg = error != null && error.getMessage() != null ? error.getMessage() : "网络超时";
        statusText.setText(prefix + " (" + msg + ")");
        worksContainer.removeAllViews();
        scannedCountText.setText("0");

        LinearLayout errorCard = new LinearLayout(this);
        errorCard.setOrientation(LinearLayout.VERTICAL);
        errorCard.setPadding(dp(18), dp(24), dp(18), dp(24));
        errorCard.setBackground(round(Color.WHITE, 16));
        errorCard.setGravity(Gravity.CENTER);

        TextView title = text("未连接到电脑在线相册", 16, true);
        title.setTextColor(Color.rgb(180, 50, 40));
        errorCard.addView(title);

        TextView hint = text("当前地址：" + onlineClient.resolveBaseUrl() + "\n\n请确认：\n1. 电脑端已启动 online_gallery_service.py（端口 45835）\n2. 手机与电脑连接同一 Wi-Fi 网络\n3. 电脑防火墙已放行 45835 端口", 13, false);
        hint.setTextColor(Color.rgb(90, 95, 92));
        hint.setLineSpacing(dp(3), 1.15f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.setMargins(0, dp(12), 0, dp(16));
        errorCard.addView(hint, hintParams);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button retryBtn = smallButton("重试连接", true);
        retryBtn.setOnClickListener(v -> refreshOnlineWorks(true));
        btnRow.addView(retryBtn, new LinearLayout.LayoutParams(-2, dp(38)));

        Button setIpBtn = smallButton("修改电脑 IP", false);
        setIpBtn.setOnClickListener(v -> showEditPcIpDialog());
        LinearLayout.LayoutParams ipParams = new LinearLayout.LayoutParams(-2, dp(38));
        ipParams.setMargins(dp(12), 0, 0, 0);
        btnRow.addView(setIpBtn, ipParams);

        errorCard.addView(btnRow);
        worksContainer.addView(errorCard, new LinearLayout.LayoutParams(-1, -2));
    }

    private void updateOnlineCategoryCounts(OnlineGalleryClient.CategoriesResult catResult, List<OnlineWorkEntry> entries) {
        categoryBar.removeAllViews();
        onlineCategoryButtons.clear();
        onlineCategoryLabels.clear();

        int totalCount = catResult != null ? catResult.total : entries.size();
        String allLabel = "全部 " + totalCount;
        Button allBtn = createOnlineCategoryButton(WorkCategory.ALL, "全部", allLabel, WorkCategory.ALL.equals(selectedOnlineCategory));
        categoryBar.addView(allBtn);
        onlineCategoryButtons.put(WorkCategory.ALL, allBtn);
        onlineCategoryLabels.put(WorkCategory.ALL, "全部");

        if (catResult != null && catResult.categories != null) {
            for (OnlineGalleryClient.CategoryItem cat : catResult.categories) {
                if (cat.count <= 0) continue;
                if ("全部".equals(cat.name) || WorkCategory.ALL.equals(cat.name) || onlineCategoryButtons.containsKey(cat.name)) continue;
                if ("待首发".equals(cat.name) || "已发1次".equals(cat.name) || "已发2次".equals(cat.name)) continue;
                String displayBase = formatFolderLabel(cat.name);
                String fullLabel = displayBase + " " + cat.count;
                Button btn = createOnlineCategoryButton(cat.name, displayBase, fullLabel, cat.name.equals(selectedOnlineCategory));
                categoryBar.addView(btn);
                onlineCategoryButtons.put(cat.name, btn);
                onlineCategoryLabels.put(cat.name, displayBase);
            }
        }
    }

    private Button createOnlineCategoryButton(String key, String displayBase, String buttonText, boolean isSelected) {
        Button button = new Button(this);
        button.setText(buttonText);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinHeight(dp(34));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setElevation(0);
        button.setGravity(Gravity.CENTER);
        applyOnlineCategoryButtonStyle(button, isSelected);

        button.setOnClickListener(v -> selectOnlineCategory(key, true));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(34));
        params.setMargins(dp(2), 0, dp(2), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void applyOnlineCategoryButtonStyle(Button button, boolean isSelected) {
        if (isSelected) {
            button.setBackground(round(Color.WHITE, 10));
            button.setTextColor(Color.rgb(24, 25, 24));
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            button.setElevation(dp(1));
        } else {
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setTextColor(Color.rgb(104, 108, 106));
            button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            button.setElevation(0);
        }
    }

    private void selectOnlineCategory(String catKey, boolean showToast) {
        if (catKey == null || !onlineCategoryButtons.containsKey(catKey)) return;
        selectedOnlineCategory = catKey;
        for (Map.Entry<String, Button> item : onlineCategoryButtons.entrySet()) {
            applyOnlineCategoryButtonStyle(item.getValue(), item.getKey().equals(selectedOnlineCategory));
        }
        applyOnlineCategoryFilter(selectedOnlineCategory);
        Button activeBtn = onlineCategoryButtons.get(catKey);
        if (activeBtn != null && categoryScrollView != null) {
            int scrollX = activeBtn.getLeft() - (categoryScrollView.getWidth() - activeBtn.getWidth()) / 2;
            categoryScrollView.smoothScrollTo(Math.max(0, scrollX), 0);
        }
        if (showToast) {
            String displayBase = onlineCategoryLabels.get(catKey);
            if (displayBase == null) displayBase = catKey;
            toast("已显示 " + displayBase);
        }
    }

    private void applyOnlineCategoryFilter(String catKey) {
        if (!isOnlineMode) return;
        onlinePageLimit = 25;
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        String[] tokens = query.isEmpty() ? new String[0] : query.split("\\s+");

        currentOnlineFilteredEntries.clear();
        for (OnlineWorkEntry work : onlineWorks) {
            if (!WorkCategory.ALL.equals(catKey) && !"全部".equals(catKey)) {
                boolean matchesCategory;
                if ("待首发".equals(catKey)) {
                    matchesCategory = (work.useCount == 0);
                } else if ("已发1次".equals(catKey) || "已发1".equals(catKey)) {
                    matchesCategory = (work.useCount == 1);
                } else if ("已发2次".equals(catKey) || "已发2".equals(catKey) || "已用满".equals(catKey)) {
                    matchesCategory = (work.useCount >= 2);
                } else {
                    matchesCategory = catKey.equals(work.destination) || catKey.equals(work.stage)
                            || (work.title != null && work.title.contains(catKey))
                            || (work.stage != null && work.stage.contains(catKey));
                }
                if (!matchesCategory) continue;
            }
            if (tokens.length > 0 && !matchesSearchTokens(work.title, work.destination, tokens)) {
                continue;
            }
            currentOnlineFilteredEntries.add(work);
        }
        renderOnlineWorksCards(currentOnlineFilteredEntries, false);
        if (contentScroll != null) {
            contentScroll.scrollTo(0, 0);
        }
    }

    public void switchToNextOnlineCategory() {
        if (onlineCategoryButtons.size() <= 1 || !isOnlineMode) return;
        List<String> keys = new ArrayList<>(onlineCategoryButtons.keySet());
        int currentIndex = keys.indexOf(selectedOnlineCategory);
        if (currentIndex < 0) currentIndex = 0;
        int nextIndex = (currentIndex + 1) % keys.size();
        selectOnlineCategory(keys.get(nextIndex), true);
    }

    public void switchToPreviousOnlineCategory() {
        if (onlineCategoryButtons.size() <= 1 || !isOnlineMode) return;
        List<String> keys = new ArrayList<>(onlineCategoryButtons.keySet());
        int currentIndex = keys.indexOf(selectedOnlineCategory);
        if (currentIndex < 0) currentIndex = 0;
        int prevIndex = (currentIndex - 1 + keys.size()) % keys.size();
        selectOnlineCategory(keys.get(prevIndex), true);
    }

    private void renderOnlineWorksCards(List<OnlineWorkEntry> entries, boolean animate) {
        LayoutTransition transition = worksContainer.getLayoutTransition();
        if (!animate) worksContainer.setLayoutTransition(null);
        worksContainer.removeAllViews();
        scannedCountText.setText(String.valueOf(entries.size()));
        headingText.setText("");

        if (entries.isEmpty()) {
            String emptyMsg;
            if (searchQuery != null && !searchQuery.trim().isEmpty()) {
                emptyMsg = "电脑在线相册未找到匹配「" + searchQuery.trim() + "」的作品\n请尝试切换分类或搜索其他目的地";
            } else {
                emptyMsg = "当前电脑分类暂无作品";
            }
            TextView empty = text(emptyMsg, 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(14), dp(28), dp(14), dp(28));
            empty.setBackground(round(Color.WHITE, 18));
            worksContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            if (!animate) worksContainer.setLayoutTransition(transition);
            return;
        }

        int showCount = Math.min(entries.size(), onlinePageLimit);
        for (int i = 0; i < showCount; i++) {
            worksContainer.addView(onlineWorkCard(entries.get(i)), margins(0, 0, 0, dp(10)));
        }

        if (entries.size() > showCount) {
            Button loadMoreBtn = new Button(this);
            loadMoreBtn.setText("加载更多作品 (已显示 " + showCount + " / " + entries.size() + " 套)");
            loadMoreBtn.setAllCaps(false);
            loadMoreBtn.setTextSize(13);
            loadMoreBtn.setTextColor(Color.rgb(15, 135, 88));
            loadMoreBtn.setBackground(roundWithStroke(Color.WHITE, 14, Color.rgb(200, 230, 215)));
            loadMoreBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
            loadMoreBtn.setOnClickListener(v -> {
                onlinePageLimit += 30;
                renderOnlineWorksCards(currentOnlineFilteredEntries, false);
            });
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(-1, dp(44));
            btnParams.setMargins(dp(12), dp(8), dp(12), dp(24));
            worksContainer.addView(loadMoreBtn, btnParams);
        }

        if (!animate) worksContainer.setLayoutTransition(transition);
    }

    private View onlineWorkCard(OnlineWorkEntry work) {
        LinearLayout card = card();
        card.setTag("online:" + work.id);
        card.setOrientation(LinearLayout.VERTICAL);

        if (work.used) {
            card.setBackground(roundWithStroke(
                    Color.rgb(243, 245, 243), 16, Color.rgb(218, 224, 220)));
            card.setElevation(0);
        }

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        // Destination badge
        if (work.destination != null && !work.destination.isEmpty() && !"其他".equals(work.destination)) {
            TextView destBadge = text(work.destination, 11, true);
            destBadge.setTextColor(Color.rgb(25, 120, 80));
            destBadge.setBackground(round(Color.rgb(228, 244, 235), 8));
            destBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams destParams = new LinearLayout.LayoutParams(-2, -2);
            destParams.setMargins(0, 0, dp(6), 0);
            titleRow.addView(destBadge, destParams);
        }

        // Usage protection status badge (clean)
        if (work.useCount > 0) {
            String statusText = "已使用 " + work.useCount + " 次";
            TextView useBadge = text(statusText, 11, true);
            useBadge.setTextColor(Color.rgb(90, 95, 92));
            useBadge.setBackground(round(Color.rgb(235, 238, 236), 8));
            useBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams useBadgeParams = new LinearLayout.LayoutParams(-2, -2);
            useBadgeParams.setMargins(0, 0, dp(6), 0);
            titleRow.addView(useBadge, useBadgeParams);
        }

        String displayTitle = formatDisplayTitle(work.title);
        TextView name = text(displayTitle, 14, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(titleRow);

        if (work.images != null && !work.images.isEmpty()) {
            card.addView(onlinePreviewStrip(work), margins(0, dp(4), 0, dp(2)));
        }

        StringBuilder detail = new StringBuilder();
        detail.append(work.imageCount).append(" 张图片 · 电脑真源");
        String timeBadge = extractTimestampBadge(work.id);
        if (timeBadge.isEmpty()) {
            timeBadge = extractTimestampBadge(work.title);
        }
        if (!timeBadge.isEmpty()) {
            detail.append(" · ").append(timeBadge);
        }
        if (work.useCount > 0 && work.dispatchedTo != null && !work.dispatchedTo.isEmpty()) {
            detail.append("\n记录：").append(String.join("、", work.dispatchedTo));
        }
        TextView meta = text(detail.toString(), 12, false);
        meta.setTextColor(work.useCount == 0 ? Color.rgb(75, 82, 78) : Color.rgb(115, 120, 118));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
        metaParams.setMargins(0, dp(3), 0, dp(5));
        card.addView(meta, metaParams);

        FlowLayout platformRow = new FlowLayout(this);
        platformRow.setClipChildren(false);
        platformRow.setClipToPadding(false);
        platformRow.setHorizontalSpacing(dp(8));
        platformRow.setVerticalSpacing(dp(8));

        List<PlatformCopyParser.AvailableItem> rawPlatforms = PlatformCopyParser.parseAvailablePlatforms(work.copyText);
        List<PlatformCopyParser.AvailableItem> platforms = enrichPlatformSuite(rawPlatforms, work.copyText, work.title);
        for (PlatformCopyParser.AvailableItem item : platforms) {
            String extracted = (item.copyText != null && !item.copyText.isEmpty())
                    ? item.copyText : PlatformCopyParser.extractPlatformCopy(work.copyText, item.platform);
            Button btn = compactButton(item.buttonLabel, work.useCount == 0);
            btn.setOnClickListener(v -> handleOnlineWorkUse(work, item.platform.code, item.buttonLabel, extracted));
            btn.setOnLongClickListener(v -> {
                showCopyPreviewDialog(work.title, item.buttonLabel, extracted,
                        () -> handleOnlineWorkUse(work, item.platform.code, item.buttonLabel, extracted));
                return true;
            });
            platformRow.addView(btn, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        }

        if (work.useCount > 0) {
            Button reset = new Button(this);
            reset.setText("重置");
            styleNeumorphicButton(reset, STYLE_MUTED_GRAY);
            reset.setContentDescription("重置电脑在线作品使用记录");
            reset.setOnClickListener(v -> confirmResetOnlineWork(work.id));
            platformRow.addView(reset, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        }

        LinearLayout.LayoutParams platformRowParams = new LinearLayout.LayoutParams(-1, -2);
        platformRowParams.setMargins(0, dp(8), 0, dp(2));
        card.addView(platformRow, platformRowParams);

        return card;
    }

    private List<PlatformCopyParser.AvailableItem> enrichPlatformSuite(
            List<PlatformCopyParser.AvailableItem> rawPlatforms, String rawText, String title) {
        List<PlatformCopyParser.AvailableItem> result = new ArrayList<>();
        PlatformCopyParser.AvailableItem douyinItem = null;
        PlatformCopyParser.AvailableItem xhsItem = null;
        PlatformCopyParser.AvailableItem xhs2Item = null;

        if (rawPlatforms != null) {
            for (PlatformCopyParser.AvailableItem item : rawPlatforms) {
                if (item.platform == PlatformCopyParser.Platform.DOUYIN || "规避营销版".equals(item.buttonLabel)) {
                    douyinItem = item;
                } else if (item.platform == PlatformCopyParser.Platform.XHS || "种草版".equals(item.buttonLabel) || "发布".equals(item.buttonLabel)) {
                    xhsItem = item;
                } else if (item.platform == PlatformCopyParser.Platform.XHS_2 || "大纲方案版".equals(item.buttonLabel)) {
                    xhs2Item = item;
                } else {
                    result.add(item);
                }
            }
        }

        String fallback = (rawText != null && !rawText.trim().isEmpty()) ? rawText.trim() : (title != null ? title : "");

        // 1. 规避营销版
        if (douyinItem != null && douyinItem.copyText != null && !douyinItem.copyText.trim().isEmpty()) {
            result.add(new PlatformCopyParser.AvailableItem(PlatformCopyParser.Platform.DOUYIN, "规避营销版", douyinItem.copyText));
        } else {
            String douyinCopy = PlatformCopyParser.synthesizeDouyinCopy(fallback);
            result.add(new PlatformCopyParser.AvailableItem(PlatformCopyParser.Platform.DOUYIN, "规避营销版", douyinCopy));
        }

        // 2. 种草版
        if (xhsItem != null && xhsItem.copyText != null && !xhsItem.copyText.trim().isEmpty()) {
            result.add(new PlatformCopyParser.AvailableItem(PlatformCopyParser.Platform.XHS, "种草版", xhsItem.copyText));
        } else {
            result.add(new PlatformCopyParser.AvailableItem(PlatformCopyParser.Platform.XHS, "种草版", fallback));
        }

        // 3. 大纲方案版
        if (xhs2Item != null && xhs2Item.copyText != null && !xhs2Item.copyText.trim().isEmpty()) {
            result.add(new PlatformCopyParser.AvailableItem(PlatformCopyParser.Platform.XHS_2, "大纲方案版", xhs2Item.copyText));
        } else {
            String outlineCopy = PlatformCopyParser.synthesizeOutlineCopy(fallback);
            result.add(new PlatformCopyParser.AvailableItem(PlatformCopyParser.Platform.XHS_2, "大纲方案版", outlineCopy));
        }

        result.sort((a, b) -> {
            int rankA = getButtonRank(a.buttonLabel);
            int rankB = getButtonRank(b.buttonLabel);
            return Integer.compare(rankA, rankB);
        });
        return result;
    }

    private static int getButtonRank(String label) {
        if ("规避营销版".equals(label)) return 1;
        if ("种草版".equals(label)) return 2;
        if ("大纲方案版".equals(label)) return 3;
        return 10;
    }

    private void confirmResetOnlineWork(String workId) {
        new AlertDialog.Builder(this)
                .setTitle("重置使用状态")
                .setMessage("是否重置该电脑在线作品为待首发状态？")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (dialog, which) -> {
                    onlineClient.resetWork(workId, new OnlineGalleryClient.Callback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean ok) {
                            if (ok) {
                                toast("已重置为待首发状态");
                                for (int i = 0; i < onlineWorks.size(); i++) {
                                    OnlineWorkEntry old = onlineWorks.get(i);
                                    if (old.id.equals(workId)) {
                                        OnlineWorkEntry updated = new OnlineWorkEntry(
                                                old.id, old.title, old.destination, old.stage,
                                                0, old.maxUses, false, 2, "",
                                                old.images, old.imageCount, old.copyText, old.hasCopyText,
                                                new ArrayList<>(), System.currentTimeMillis()
                                        );
                                        onlineWorks.set(i, updated);
                                        break;
                                    }
                                }
                                applyOnlineCategoryFilter(selectedOnlineCategory);
                            } else {
                                toast("重置失败");
                            }
                        }

                        @Override
                        public void onError(Exception error) {
                            toast("重置失败: " + error.getMessage());
                        }
                    });
                })
                .show();
    }

    private View onlinePreviewStrip(OnlineWorkEntry work) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(0, 0, dp(2), 0);

        int thumbW = dp(84);
        int thumbH = dp(112);
        List<Runnable> deferredLoads = new ArrayList<>();

        for (int i = 0; i < work.images.size(); i++) {
            String imageName = work.images.get(i);
            ImageView thumbView = new ImageView(this);
            thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbView.setBackground(round(Color.rgb(230, 235, 232), 8));
            thumbView.setClipToOutline(true);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(thumbW, thumbH);
            params.setMargins(0, 0, dp(6), 0);

            String cacheKey = "online:" + work.id + ":" + imageName;
            Bitmap cached = THUMBNAIL_CACHE.get(cacheKey);
            if (cached != null && !cached.isRecycled()) {
                thumbView.setImageBitmap(cached);
            } else {
                thumbView.setTag(cacheKey);
                final String finalCacheKey = cacheKey;
                Runnable task = () -> onlineClient.loadThumbnail(work.id, imageName, new OnlineGalleryClient.Callback<Bitmap>() {
                    @Override
                    public void onSuccess(Bitmap result) {
                        if (result != null && !result.isRecycled()) {
                            THUMBNAIL_CACHE.put(finalCacheKey, result);
                            if (finalCacheKey.equals(thumbView.getTag())) {
                                thumbView.setImageBitmap(result);
                            }
                        }
                    }

                    @Override
                    public void onError(Exception error) {}
                });

                if (i < 4) {
                    task.run();
                } else {
                    deferredLoads.add(task);
                }
            }

            final int imgIndex = i;
            thumbView.setOnClickListener(v -> showOnlineImageDialog(work, imgIndex));
            strip.addView(thumbView, params);
        }

        if (!deferredLoads.isEmpty()) {
            scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (scrollX > dp(20)) {
                    scroll.setOnScrollChangeListener(null);
                    for (Runnable task : deferredLoads) {
                        task.run();
                    }
                }
            });
        }

        scroll.addView(strip);
        return scroll;
    }

    private void showOnlineImageDialog(OnlineWorkEntry work, int imageIndex) {
        if (work.images.isEmpty() || imageIndex < 0 || imageIndex >= work.images.size()) return;
        String imageName = work.images.get(imageIndex);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = text(work.title + " (" + (imageIndex + 1) + "/" + work.images.size() + ")", 14, true);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, margins(0, 0, 0, dp(8)));

        ImageView fullView = new ImageView(this);
        fullView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullView.setAdjustViewBounds(true);
        fullView.setBackground(round(Color.rgb(20, 20, 20), 12));

        int maxImgHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.65f);
        fullView.setMaxHeight(maxImgHeight);

        ProgressBar spinner = new ProgressBar(this);
        layout.addView(spinner, new LinearLayout.LayoutParams(dp(40), dp(40)));
        layout.addView(fullView, new LinearLayout.LayoutParams(-1, -2));

        builder.setView(layout);
        builder.setPositiveButton("关闭", null);
        AlertDialog dialog = builder.create();
        dialog.show();

        String cacheKey = "online:" + work.id + ":" + imageName;
        Bitmap cached = THUMBNAIL_CACHE.get(cacheKey);
        if (cached != null) {
            fullView.setImageBitmap(cached);
            spinner.setVisibility(View.GONE);
        }
        onlineClient.loadThumbnail(work.id, imageName, new OnlineGalleryClient.Callback<Bitmap>() {
            @Override
            public void onSuccess(Bitmap result) {
                spinner.setVisibility(View.GONE);
                if (result != null) {
                    THUMBNAIL_CACHE.put(cacheKey, result);
                    fullView.setImageBitmap(result);
                }
            }

            @Override
            public void onError(Exception error) {
                spinner.setVisibility(View.GONE);
                toast("加载大图失败");
            }
        });
    }

    private void handleOnlineWorkUse(OnlineWorkEntry work, String platformCode, String label, String copyText) {
        copyToClipboard(label, copyText);
        toast("已复制 " + label + "，正在准备图片并打开…");

        if (work.images != null && !work.images.isEmpty()) {
            onlineClient.downloadWorkImages(work.id, work.images, new OnlineGalleryClient.Callback<List<java.io.File>>() {
                @Override
                public void onSuccess(List<java.io.File> files) {
                    launchOnlineShare(work, files, copyText, platformCode);
                }

                @Override
                public void onError(Exception error) {
                    toast("下载图片失败: " + error.getMessage() + "，文案已在剪贴板");
                }
            });
        }

        onlineClient.recordUse(work.id, getDeviceName(), platformCode, new OnlineGalleryClient.Callback<OnlineGalleryClient.UseResult>() {
            @Override
            public void onSuccess(OnlineGalleryClient.UseResult result) {
                if (result.ok) {
                    updateOnlineWorkUseCount(work.id, result.useCount, result.remainingUses,
                            getDeviceName() + "(" + platformCode + ")");
                }
            }

            @Override
            public void onError(Exception error) {
                // Background tag update error
            }
        });
    }

    private void launchOnlineShare(OnlineWorkEntry work, List<java.io.File> files, String copyText, String platformCode) {
        if (files == null || files.isEmpty()) return;
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (java.io.File f : files) {
                Uri uri = Uri.parse("content://" + getPackageName() + ".files/online/" + work.id + "/" + f.getName());
                uris.add(uri);
            }
            Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
            send.setType("image/*");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (copyText != null && !copyText.trim().isEmpty()) {
                send.putExtra(Intent.EXTRA_TEXT, copyText);
            }
            if (uris.size() == 1) {
                send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            ClipData clipData = ClipData.newUri(getContentResolver(), "作品图片", uris.get(0));
            for (int i = 1; i < uris.size(); i++) {
                clipData.addItem(new ClipData.Item(uris.get(i)));
            }
            send.setClipData(clipData);

            String targetPkg = null;
            if ("douyin".equalsIgnoreCase(platformCode)) {
                targetPkg = "com.ss.android.ugc.aweme";
            } else if ("xhs".equalsIgnoreCase(platformCode) || "xhs2".equalsIgnoreCase(platformCode) || "xhs3".equalsIgnoreCase(platformCode)) {
                targetPkg = "com.xingin.xhs";
            }
            if (targetPkg != null && isAppInstalled(targetPkg)) {
                send.setPackage(targetPkg);
                for (Uri u : uris) {
                    grantUriPermission(targetPkg, u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                startActivity(send);
            } else {
                Intent chooser = Intent.createChooser(send, "分享作品图片");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chooser);
            }
        } catch (Exception e) {
            toast("打开分享失败: " + e.getMessage());
        }
    }

    private boolean isAppInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateOnlineWorkUseCount(String workId, int newCount, int remainingUses, String dispatchTag) {
        for (int i = 0; i < onlineWorks.size(); i++) {
            OnlineWorkEntry old = onlineWorks.get(i);
            if (old.id.equals(workId)) {
                List<String> newDispatched = new ArrayList<>(old.dispatchedTo);
                newDispatched.add(dispatchTag);
                OnlineWorkEntry updated = new OnlineWorkEntry(
                        old.id, old.title, old.destination, old.stage,
                        newCount, old.maxUses, true, remainingUses,
                        newCount >= 2 ? "已发送" : "已发1次",
                        old.images, old.imageCount, old.copyText, old.hasCopyText,
                        newDispatched, System.currentTimeMillis()
                );
                onlineWorks.set(i, updated);
                break;
            }
        }
        applyOnlineCategoryFilter(selectedOnlineCategory);
    }

    private void showCopyPreviewDialog(String workTitle, String versionLabel, String copyText, Runnable onShareAction) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(18), dp(20), dp(14));

        TextView titleView = text(workTitle, 16, true);
        titleView.setMaxLines(2);
        container.addView(titleView);

        int charCount = copyText != null ? copyText.length() : 0;
        TextView subView = text("【" + versionLabel + "】 共 " + charCount + " 字", 12, false);
        subView.setTextColor(Color.rgb(16, 151, 99));
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.setMargins(0, dp(4), 0, dp(12));
        container.addView(subView, subParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(round(Color.rgb(244, 246, 245), 10));
        scroll.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView contentText = new TextView(this);
        contentText.setText(copyText != null && !copyText.trim().isEmpty() ? copyText : "（暂无该版本文案）");
        contentText.setTextSize(13.5f);
        contentText.setTextColor(Color.rgb(40, 42, 41));
        contentText.setLineSpacing(dp(3), 1.15f);
        contentText.setTextIsSelectable(true);
        scroll.addView(contentText, new FrameLayout.LayoutParams(-1, -2));

        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.45f);
        container.addView(scroll, new LinearLayout.LayoutParams(-1, maxHeight));

        builder.setView(container);
        builder.setNegativeButton("关闭", null);
        builder.setNeutralButton("复制全文", (dialog, which) -> {
            copyToClipboard(versionLabel, copyText);
            toast("已复制 " + versionLabel + " 全文 (" + charCount + "字)");
        });
        builder.setPositiveButton("前往使用", (dialog, which) -> {
            copyToClipboard(versionLabel, copyText);
            toast("已复制并准备使用");
            if (onShareAction != null) {
                onShareAction.run();
            }
        });

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(round(Color.WHITE, 16));
        }
        dialog.show();
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && text != null) {
            ClipData clip = ClipData.newPlainText(label, text);
            clipboard.setPrimaryClip(clip);
        }
    }

    private String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model != null && manufacturer != null && model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            return capitalize(model);
        } else {
            return capitalize(manufacturer) + " " + (model != null ? model : "Device");
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) return s;
        return Character.toUpperCase(first) + s.substring(1);
    }

    private void showEditPcIpDialog() {
        EditText input = new EditText(this);
        input.setHint("例如: 192.168.1.27");
        String current = onlineClient.resolveBaseUrl().replace("http://", "").replace(":" + OnlineGalleryClient.DEFAULT_PC_PORT, "");
        input.setText(current);
        new AlertDialog.Builder(this)
                .setTitle("设置电脑在线相册 IP")
                .setMessage("请输入运行 online_gallery_service.py 的电脑局域网 IP 地址：")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并连接", (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) {
                        String url = "http://" + ip + ":" + OnlineGalleryClient.DEFAULT_PC_PORT;
                        onlineClient.setCustomBaseUrl(url);
                        toast("已设置电脑地址: " + url);
                        refreshOnlineWorks(true);
                    }
                })
                .show();
    }

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
