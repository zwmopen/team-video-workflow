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
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
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
import java.util.concurrent.RejectedExecutionException;

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

    // ── 在线缩略图「整页调度器」────────────────────────────────────────────
    // 2026-09-20：在线列表一屏几百张卡片，旧实现每张卡片前 4 张图立刻发请求
    // （382 作品 → 1500+ 请求同时压进 4 线程池），手机端直接卡在「正在读取…」。
    // 现在整页只给固定「首发预算」，其余入队按节拍渐进放行，UI 先出骨架再补图。
    private static final int ONLINE_THUMB_BURST_BUDGET = 18;
    private static final int ONLINE_THUMB_DRAIN_STEP = 6;
    private static final long ONLINE_THUMB_DRAIN_INTERVAL_MS = 260L;
    private final ArrayDeque<Runnable> onlineThumbPending = new ArrayDeque<>();
    private int onlineThumbBurst = ONLINE_THUMB_BURST_BUDGET;
    private boolean onlineThumbDraining = false;
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
    private boolean enteredTrashFromOnline = false;
    private ImageButton sourceModeButton;
    private OnlineGalleryClient.CategoriesResult lastCategoriesResult;
    /** 当前 onlineWorks 是否来自磁盘快照（用于状态栏标注「本地快照」） */
    private boolean onlineListFromSnapshot = false;
    /** 磁盘快照的落盘时间（0 = 非快照来源） */
    private long onlineSnapshotAtMs = 0L;
    private final List<OnlineWorkEntry> onlineWorks = new ArrayList<>();
    private String selectedOnlineCategory = WorkCategory.ALL;
    private final Map<String, Button> onlineCategoryButtons = new LinkedHashMap<>();
    private final Map<String, String> onlineCategoryLabels = new LinkedHashMap<>();
    private int onlinePageLimit = 30;
    private final List<OnlineWorkEntry> currentOnlineFilteredEntries = new ArrayList<>();

    // ---- 在线回收站（电脑端 45835）：已使用 / 已标记垃圾 双 Tab ----
    /** 是否正停靠在「在线回收站」页面（与本地回收站 showingTrash 互斥） */
    private boolean showingOnlineRecycle = false;
    /** 当前 Tab：sent=已使用（_已发送1次） / garbage=已标记垃圾（_垃圾作品） */
    private String onlineRecycleTab = "sent";
    private int onlineRecycleSentCount = 0;
    private int onlineRecycleGarbageCount = 0;
    private final List<OnlineWorkEntry> onlineRecycleWorks = new ArrayList<>();
    private LinearLayout onlineRecycleTabBar;
    private Button onlineRecycleSentTabButton;
    private Button onlineRecycleGarbageTabButton;
    private int onlineRecyclePageLimit = 30;

    // ---- 手机本地回收站双 Tab（已删除 / 已标记垃圾）：与在线回收站双 Tab 结构 1:1 对齐 ----
    /** 本地回收站当前 Tab：deleted=已删除（未写垃圾备注） / garbage=已标记垃圾（garbageRemark 非空） */
    private String localTrashTab = "deleted";
    private int localTrashDeletedCount = 0;
    private int localTrashGarbageCount = 0;
    private LinearLayout localTrashTabBar;
    private Button localTrashDeletedTabButton;
    private Button localTrashGarbageTabButton;

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
        android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder().permitAll().build());
        isOnlineMode = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_IS_ONLINE_MODE, false);
        onlineClient = new OnlineGalleryClient(this);
        ensureDeviceId();
        setContentView(ScreenInsets.protect(buildUi()));
        // 恢复上次的在线模式时，立刻开始接收电脑端信标（无需扫描即可拿到地址）
        if (isOnlineMode) startOnlineBeaconListener();
        startReceiver();
        requestLegacyStoragePermission();
        if (Build.VERSION.SDK_INT >= 33) Api33Back.register(this);
        UpdateChecker.checkOnLaunch(this);
        DiagnosticLog.write(this, "app_open", "album main opened");
        submitToWorker(() -> GalleryShareBridge.cleanupPreviousDays(this, LocalDate.now()));
        // 【体感加速】用户点进「电脑在线相册」是必然动作，所以开机就预热：
        // 先读本地快照（本地读，几十毫秒级），再后台静默拉最新。
        // 这样点进去大概率是瞬时渲染，而不是先看到「正在连接电脑在线相册…」。
        primeOnlineWorksInBackground();
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
            startOnlineBeaconListener();   // 回到前台重新开始接收电脑信标
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
        onlineClient.stopBeaconListener();   // 退到后台停掉信标监听，回到前台会重新开启
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        onlineClient.stopBeaconListener();
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
        if (showingOnlineRecycle) {
            showWorks();
            return;
        }
        if (showingTrash) {
            showWorks();
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

        leftModeButton = iconButton(R.drawable.ic_album_refresh, "刷新作品");
        leftModeButton.setVisibility(View.GONE);
        leftModeButton.setOnClickListener(v -> {
            if (fileMode) {
                refreshFiles();
                toast("正在刷新文件");
            } else if (showingOnlineRecycle || showingTrash) {
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
        rightModeButton.setVisibility(View.VISIBLE);
        rightModeButton.setOnClickListener(v -> {
            if (fileMode) {
                leaveFileMode();
                toast("回收站");
                showTrash();
            } else if (showingOnlineRecycle) {
                toast("正在刷新在线回收站…");
                loadOnlineRecycle(onlineRecycleTab, true);
            } else if (showingTrash) confirmClearTrash();
            else if (isOnlineMode) {
                toast("在线回收站");
                showOnlineRecycle("sent");
            } else {
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

        // ---- 在线回收站双 Tab（已使用 / 已标记垃圾）：默认隐藏，点顶栏「回收站」才出现 ----
        onlineRecycleTabBar = new LinearLayout(this);
        onlineRecycleTabBar.setOrientation(LinearLayout.HORIZONTAL);
        onlineRecycleTabBar.setGravity(Gravity.CENTER_VERTICAL);
        onlineRecycleTabBar.setVisibility(View.GONE);
        onlineRecycleSentTabButton = recycleTabButton("已使用");
        onlineRecycleSentTabButton.setOnClickListener(v -> selectOnlineRecycleTab("sent"));
        onlineRecycleTabBar.addView(onlineRecycleSentTabButton, new LinearLayout.LayoutParams(0, dp(36), 1));
        onlineRecycleGarbageTabButton = recycleTabButton("已标记垃圾");
        onlineRecycleGarbageTabButton.setOnClickListener(v -> selectOnlineRecycleTab("garbage"));
        LinearLayout.LayoutParams garbageTabParams = new LinearLayout.LayoutParams(0, dp(36), 1);
        garbageTabParams.setMargins(dp(8), 0, 0, 0);
        onlineRecycleTabBar.addView(onlineRecycleGarbageTabButton, garbageTabParams);

        // ---- 手机本地回收站双 Tab（已删除 / 已标记垃圾）：与在线回收站同结构，默认隐藏 ----
        localTrashTabBar = new LinearLayout(this);
        localTrashTabBar.setOrientation(LinearLayout.HORIZONTAL);
        localTrashTabBar.setGravity(Gravity.CENTER_VERTICAL);
        localTrashTabBar.setVisibility(View.GONE);
        localTrashDeletedTabButton = recycleTabButton("已删除");
        localTrashDeletedTabButton.setOnClickListener(v -> selectLocalTrashTab("deleted"));
        localTrashTabBar.addView(localTrashDeletedTabButton, new LinearLayout.LayoutParams(0, dp(36), 1));
        localTrashGarbageTabButton = recycleTabButton("已标记垃圾");
        localTrashGarbageTabButton.setOnClickListener(v -> selectLocalTrashTab("garbage"));
        LinearLayout.LayoutParams localGarbageParams = new LinearLayout.LayoutParams(0, dp(36), 1);
        localGarbageParams.setMargins(dp(8), 0, 0, 0);
        localTrashTabBar.addView(localTrashGarbageTabButton, localGarbageParams);

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
        // 【0.8.43 修】statusText 不再 add 到滚动 root 末尾（之前会被作品列表推到底部屏幕外），
        // 改为由 buildUi() 末尾的 frozenLayout.addView 统一加到 titleRow 之下，
        // 做真正的 sticky 顶部状态栏。

        footerNote = text("点击平台按钮会复制对应文案并打开图片分享。首次使用后按现有清理设置自动回收；两个平台共用一个作品生命周期。", 12, false);
        footerNote.setTextColor(Color.GRAY);
        root.addView(footerNote, margins(0, dp(12), 0, 0));

        contentScroll = new SpringScrollView(this);
        contentScroll.setSwipeListener(new SpringScrollView.SwipeListener() {
            @Override public void onSwipeLeft() {
                if (showingOnlineRecycle) selectOnlineRecycleTab("garbage");
                else if (isOnlineMode) switchToNextOnlineCategory();
                else switchToNextCategory();
            }
            @Override public void onSwipeRight() {
                if (showingOnlineRecycle) selectOnlineRecycleTab("sent");
                else if (isOnlineMode) switchToPreviousOnlineCategory();
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
                else if (showingOnlineRecycle) loadOnlineRecycle(onlineRecycleTab, true);
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
        // 【0.8.43 修】把 statusText 提到顶栏下方做 sticky 状态栏：不再被作品列表推到底部屏幕外。
        // 文案里始终带「💻 电脑在线相册 (url) · 共 N 套（本地快照 · X 分钟前）」，离线时也保留 snapshot 年龄。
        LinearLayout.LayoutParams statusBarParams = new LinearLayout.LayoutParams(-1, -2);
        statusBarParams.setMargins(dp(12), dp(4), dp(12), dp(4));
        frozenLayout.addView(statusText, statusBarParams);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(38));
        searchParams.setMargins(dp(12), 0, dp(12), dp(8));
        frozenLayout.addView(searchBar, searchParams);
        LinearLayout.LayoutParams recycleTabParams = new LinearLayout.LayoutParams(-1, dp(40));
        recycleTabParams.setMargins(dp(12), 0, dp(12), dp(4));
        frozenLayout.addView(onlineRecycleTabBar, recycleTabParams);
        LinearLayout.LayoutParams localTrashTabParams = new LinearLayout.LayoutParams(-1, dp(40));
        localTrashTabParams.setMargins(dp(12), 0, dp(12), dp(4));
        frozenLayout.addView(localTrashTabBar, localTrashTabParams);
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
        if (showingOnlineRecycle) {
            loadOnlineRecycle(onlineRecycleTab, false);
            return;
        }
        if (isOnlineMode && !showingTrash) {
            refreshOnlineWorks(false);
            return;
        }
        submitToWorker(() -> {
            if (isOnlineMode && !showingTrash) return;
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
                    if (isOnlineMode && !showingTrash) return;
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
                        if (isOnlineMode && !showingTrash) return;
                        renderWorks(finalEntries, false);
                    });
                }
            } catch (Exception error) {
                DiagnosticLog.write(this, "library_refresh_failed", error.getMessage());
                runOnUiThread(() -> {
                    if (isOnlineMode && !showingTrash) return;
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
        showingOnlineRecycle = false;
        onlineRecycleWorks.clear();
        if (onlineRecycleTabBar != null) onlineRecycleTabBar.setVisibility(View.GONE);
        if (localTrashTabBar != null) localTrashTabBar.setVisibility(View.GONE);
        if (searchBar != null) searchBar.setVisibility(View.VISIBLE);
        categorySelector.setVisibility(View.VISIBLE);
        leftModeButton.setImageResource(R.drawable.ic_album_refresh);
        leftModeButton.setContentDescription("刷新作品");
        leftModeButton.setVisibility(View.GONE);
        rightModeButton.setImageResource(R.drawable.ic_album_trash);
        rightModeButton.setContentDescription("回收站");
        headingText.setText("");
        if (enteredTrashFromOnline) {
            enteredTrashFromOnline = false;
            switchToOnlineMode();
        } else {
            refreshWorks();
        }
    }

    private void showTrash() {
        selectedWorkIds.clear();
        quickTrashButton.setVisibility(View.GONE);
        showingOnlineRecycle = false;
        if (onlineRecycleTabBar != null) onlineRecycleTabBar.setVisibility(View.GONE);
        // 本地回收站与在线回收站同构：进入即展示「已删除 / 已标记垃圾」双 Tab
        if (localTrashTabBar != null) localTrashTabBar.setVisibility(View.VISIBLE);
        if (isOnlineMode) {
            enteredTrashFromOnline = true;
        }
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
        localTrashDeletedCount = 0;
        localTrashGarbageCount = 0;
        refreshLocalTrashTabStyles();
        TextView empty = text("回收站是空的", 14, false);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Color.GRAY);
        empty.setPadding(dp(14), dp(28), dp(14), dp(28));
        empty.setBackground(round(Color.WHITE, 18));
        worksContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        scannedCountText.setText("0");
        statusText.setText("回收站已清空");

        int onlineCleared = OnlineWorkLifecycle.clearAllTrash(this);
        toast("回收站已清空" + (onlineCleared > 0 ? "（含 " + onlineCleared + " 套在线记录）" : ""));

        submitToWorker(() -> {
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
        if (isOnlineMode && !showingTrash) return;
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

        // 本地回收站双 Tab 过滤（已删除 / 已标记垃圾），与在线回收站双 Tab 结构 1:1 对齐。
        // 判据即「是否写过垃圾备注」—— 与在线版 quality_tag.json 的垃圾标记语义同一套。
        List<WorkLibrary.WorkEntry> shownEntries = entries;
        if (showingTrash) {
            int deleted = 0;
            int garbage = 0;
            for (WorkLibrary.WorkEntry entry : entries) {
                if (entry.isGarbage()) garbage++;
                else deleted++;
            }
            localTrashDeletedCount = deleted;
            localTrashGarbageCount = garbage;
            refreshLocalTrashTabStyles();
            boolean wantGarbage = "garbage".equals(localTrashTab);
            List<WorkLibrary.WorkEntry> picked = new ArrayList<>();
            for (WorkLibrary.WorkEntry entry : entries) {
                if (entry.isGarbage() == wantGarbage) picked.add(entry);
            }
            shownEntries = picked;
        }

        LinkedHashSet<String> visibleIds = new LinkedHashSet<>();
        for (WorkLibrary.WorkEntry entry : entries) {
            visibleIds.add(entry.id);
        }
        selectedWorkIds.retainAll(visibleIds);
        boolean selecting = !selectedWorkIds.isEmpty() && !showingTrash;
        quickTrashButton.setVisibility(selecting ? View.VISIBLE : View.GONE);
        headingText.setText(selecting ? "已选 " + selectedWorkIds.size() + " 个" : (showingTrash ? "回收站" : ""));

        List<OnlineWorkLifecycle.Item> onlineTrashItems = new ArrayList<>();
        if (showingTrash && !"garbage".equals(localTrashTab)) {
            CleanupSettings.Values cleanup = CleanupSettings.read(this);
            onlineTrashItems = OnlineWorkLifecycle.getTrashItems(this, System.currentTimeMillis(), cleanup.deleteAfterMs());
        }

        int totalCount = shownEntries.size() + onlineTrashItems.size();
        scannedCountText.setText(String.valueOf(showingTrash ? totalCount : entries.size()));

        // Huawei can leave real folders in the external trash after its document index
        // forgets the corresponding app record, so clearing must remain available even
        // when the private list already looks empty.
        rightModeButton.setEnabled(true);
        rightModeButton.setAlpha(rightModeButton.isEnabled() ? 1f : 0.45f);
        if (totalCount == 0) {
            String emptyMsg;
            if (showingTrash) {
                emptyMsg = "garbage".equals(localTrashTab)
                        ? "本地「已标记垃圾」暂为空\n左滑或点卡片「备注」填写垃圾原因的作品会归到这里。"
                        : "回收站是空的";
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
        if (showingTrash) {
            for (OnlineWorkLifecycle.Item item : onlineTrashItems) {
                worksContainer.addView(onlineTrashCard(item), margins(0, 0, 0, dp(10)));
            }
        }
        for (WorkLibrary.WorkEntry entry : shownEntries) {
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
        int localUsedCount = work.xhsShareCount + work.douyinShareCount;
        // 元信息统一口径（与 iOS `configure` 逐字一致）：
        // `📱 手机本地 · N 图 · 使用态` + 日期后缀。
        String detail = "📱 手机本地 · " + work.images.size() + " 图 · "
                + (localUsedCount > 0 ? "已使用 " + localUsedCount + " 次" : "未使用");
        String localDate = extractTimestampBadge(work.name);
        if (!localDate.isEmpty()) detail += " · " + localDate;
        if (showingTrash && work.isGarbage()) {
            // 与在线回收站垃圾条目对齐：展示已写入手机本地元数据的垃圾备注
            detail += " · 🗑️ 垃圾样本\n垃圾备注：" + work.garbageRemark;
        }
        // DSH-093 A2：这里原本判 work.used，与上面状态行的 localUsedCount > 0 不是一个口径，
        // 会出现「未使用」下面挂着「✓ 小红书 0 · 抖音 0」+ 自动删除倒计时的自相矛盾显示。
        if (localUsedCount > 0) {
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
        if (showingTrash) {
            // 与在线回收站卡片严格对齐：恢复 / 备注 / 复制路径 三项按钮
            FlowLayout trashRow = new FlowLayout(this);
            trashRow.setClipChildren(false);
            trashRow.setClipToPadding(false);
            trashRow.setHorizontalSpacing(dp(8));
            trashRow.setVerticalSpacing(dp(8));

            Button restoreBtn = compactButton("恢复", true);
            restoreBtn.setContentDescription("恢复作品到列表");
            restoreBtn.setOnClickListener(v -> restore(work.id));
            trashRow.addView(restoreBtn, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));

            Button remarkBtn = new Button(this);
            remarkBtn.setText(work.isGarbage() ? "改备注" : "备注");
            styleNeumorphicButton(remarkBtn, STYLE_MUTED_GRAY);
            remarkBtn.setContentDescription("填写垃圾备注，写入手机本地元数据");
            remarkBtn.setOnClickListener(v -> promptRemarkLocalTrash(work));
            trashRow.addView(remarkBtn, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));

            String trashPath = work.directory != null ? work.directory.getAbsolutePath() : "";
            trashRow.addView(copyPathButton(trashPath, "手机本地作品"),
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));

            LinearLayout.LayoutParams trashRowParams = new LinearLayout.LayoutParams(-1, -2);
            trashRowParams.setMargins(0, dp(8), 0, dp(2));
            card.addView(trashRow, trashRowParams);
        } else if (selecting) {
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
                btn.setContentDescription(item.buttonLabel + "，已使用 " + clickCount + " 次");
                final int finalClickCount = clickCount;
                final String copyForPlatform = (item.copyText != null && !item.copyText.isEmpty())
                        ? item.copyText : PlatformCopyParser.extractPlatformCopy(work.text, item.platform);
                btn.setOnClickListener(v -> {
                    markPlatformButtonClicked(btn, item.buttonLabel, finalClickCount);
                    openShare(work, item.platform.code, copyForPlatform);
                });
                btn.setOnLongClickListener(v -> {
                    showCopyPreviewDialog(work.name, item.buttonLabel, copyForPlatform, () -> {
                        markPlatformButtonClicked(btn, item.buttonLabel, finalClickCount);
                        openShare(work, item.platform.code, copyForPlatform);
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

            // 「复制路径」紧跟在「删除」之后：本地作品复制手机上的作品文件夹路径
            String localPath = work.directory != null ? work.directory.getAbsolutePath() : "";
            platformRow.addView(copyPathButton(localPath, "手机本地作品"),
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
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
        openShare(work, platform, null);
    }

    /** 带上调用方按按钮已抽好的文案，保证「按钮标签 == 剪贴板内容」。
     *
     * <p>多版本文案下 `ShareActivity` 无法自己判断用户点的是哪一版（10 个版本的 platform
     * 都是 GENERAL），只能回退成整篇原文 ⇒ 必须由这里把成品文案直传过去。
     */
    private void openShare(WorkLibrary.WorkEntry work, String platform, String copyText) {
        Intent intent = new Intent(this, ShareActivity.class)
                .putExtra(ShareActivity.EXTRA_WORK_ID, work.id)
                .putExtra(ShareActivity.EXTRA_PLATFORM, platform);
        if (copyText != null && !copyText.trim().isEmpty()) {
            intent.putExtra(ShareActivity.EXTRA_COPY_TEXT, copyText);
        }
        startActivity(intent);
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
                .setMessage("作品会从当前列表消失，并移动到“相册回收站”；分享次数会保留。\n\n选择「备注并删除」可先填写垃圾原因备注（随作品写入手机本地元数据）。")
                .setNegativeButton("取消", null)
                .setNeutralButton("备注并删除", (dialog, which) -> promptRemarkThenMoveToTrash(id))
                .setPositiveButton("移到回收站", (dialog, which) -> {
                    LinkedHashSet<String> ids = new LinkedHashSet<>();
                    ids.add(id);
                    moveSelectedToTrash(ids);
                })
                .show();
    }

    /** 「备注并删除」（本地）：与在线 `promptRemarkThenDeleteOnlineWork` 交互 1:1 对齐。 */
    private void promptRemarkThenMoveToTrash(String id) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("例如：文案公文味重 / 图片 AI 味浓 / 选题不合适");
        input.setSingleLine(false);
        input.setMaxLines(3);
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        lp.setMargins(pad, pad, pad, 0);
        holder.addView(input, lp);

        AlertDialog protectedInputDialog = new AlertDialog.Builder(this)
                .setTitle("垃圾备注（随作品写入元数据）")
                .setView(holder)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", (dialog, which) -> {
                    LinkedHashSet<String> ids = new LinkedHashSet<>();
                    ids.add(id);
                    moveSelectedToTrash(ids, input.getText().toString());
                })
                .create();
            // 带输入框的弹窗：禁止点击背景关闭 —— 误触一次就把打好的字全丢了（BUG_LEDGER DSH-084）。
            protectedInputDialog.setCanceledOnTouchOutside(false);
            protectedInputDialog.show();
    }

    /**
     * 本地回收站「备注」：写入手机本地元数据（与在线回收站 promptRemarkOnlineRecycle 同构）。
     * 直接复用已删除作品的 id，不需要电脑端 workId —— 本地作品备注落在手机本地 .meta。
     */
    private void promptRemarkLocalTrash(WorkLibrary.WorkEntry work) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("例如：文案公文味重 / 图片 AI 味浓 / 选题不合适");
        input.setText(work.garbageRemark == null ? "" : work.garbageRemark);
        input.setSingleLine(false);
        input.setMaxLines(3);
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        lp.setMargins(pad, pad, pad, 0);
        holder.addView(input, lp);

        AlertDialog protectedInputDialog = new AlertDialog.Builder(this)
                .setTitle("垃圾备注（写入手机本地元数据）")
                .setView(holder)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存备注", (dialog, which) -> {
                    String remark = input.getText().toString();
                    final String workId = work.id;
                    submitToWorker(() -> {
                        try {
                            library().updateTrashRemark(workId, remark);
                            runOnUiThread(() -> {
                                toast(remark.trim().isEmpty() ? "已清除垃圾备注" : "✅ 已写入垃圾备注");
                                refreshWorks();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> toast("写入失败：" + error.getMessage()));
                        }
                    });
                })
                .create();
            // 带输入框的弹窗：禁止点击背景关闭 —— 误触一次就把打好的字全丢了（BUG_LEDGER DSH-084）。
            protectedInputDialog.setCanceledOnTouchOutside(false);
            protectedInputDialog.show();
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
        submitToWorker(() -> {
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
        submitToWorker(() -> {
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
        moveSelectedToTrash(ids, null);
    }

    /// 带垃圾备注的「备注并删除」（本地）：与在线作品流程 1:1 对齐。
    private void moveSelectedToTrash(Set<String> ids, String garbageRemark) {
        if (ids == null || ids.isEmpty()) return;
        final LinkedHashSet<String> targets = new LinkedHashSet<>(ids);
        final String remark = garbageRemark == null ? "" : garbageRemark.trim();
        pendingTrashIds.addAll(targets);
        String suffix = targets.size() > 1 ? " " + targets.size() + " 个" : "";
        String msg = remark.isEmpty() ? "已移到回收站" + suffix : "已移到回收站并备注" + suffix;

        optimisticRemoveWorks(targets, msg);

        submitToWorker(() -> {
            ArrayList<String> failures = new ArrayList<>();
            for (String id : targets) {
                try {
                    WorkLibrary library = library();
                    WorkLibrary.WorkEntry entry = library.moveToTrash(id, System.currentTimeMillis(), remark);
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
        submitToWorker(() -> {
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

        submitToWorker(() -> {
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

    /**
     * 线程池兜底提交：onDestroy 会 shutdownNow 掉 worker，此时**已经排进主线程队列**的回调
     * （典型：后台扫描完成后 runOnUiThread 里再调 refreshWorks）仍会执行，
     * 直接 worker.execute(...) 会在 UI 线程抛 RejectedExecutionException 造成 FATAL 崩溃。
     * 真机复现：0.8.36 红米13，切换来源模式后刷新作品，崩于 refreshWorks 内的 worker.execute。
     */
    private boolean submitToWorker(Runnable task) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed()) || worker.isShutdown()) {
            return false;
        }
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException error) {
            DiagnosticLog.write(this, "worker_task_rejected", error.getClass().getSimpleName());
            return false;
        }
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
        button.setContentDescription(label + "，已使用 " + Math.max(1, previousCount + 1) + " 次");
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
        onlineClient.stopBeaconListener();   // 退出在线模式即停掉信标监听，避免后台耗电
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
        startOnlineBeaconListener();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_IS_ONLINE_MODE, true).apply();
        updateSourceModeButtonStyle();
        selectedWorkIds.clear();
        quickTrashButton.setVisibility(View.GONE);
        showingTrash = false;
        showingOnlineRecycle = false;
        if (onlineRecycleTabBar != null) onlineRecycleTabBar.setVisibility(View.GONE);
        if (localTrashTabBar != null) localTrashTabBar.setVisibility(View.GONE);
        if (modeButton != null) modeButton.setVisibility(View.GONE);
        leftModeButton.setVisibility(View.GONE);
        rightModeButton.setVisibility(View.VISIBLE);
        footerNote.setText("💻 电脑在线作品：实时读取电脑首发成品，点击文案复制并唤起分享；长按文案按钮可全屏预览。");
        worksContainer.removeAllViews();
        toast("已切换至：💻 电脑在线相册");

        if (!onlineWorks.isEmpty()) {
            if (lastCategoriesResult != null) {
                updateOnlineCategoryCounts(lastCategoriesResult, onlineWorks);
            }
            // 秒开：先把已有数据（内存或开机预热好的磁盘快照）直接铺满屏幕，
            // 状态栏只说「正在后台刷新…」，不再出现阻塞感的「正在连接…」
            applyOnlineCategoryFilter(selectedOnlineCategory);
            statusText.setText(onlineStatusLine("正在刷新…"));
            refreshOnlineWorks(false);
        } else {
            refreshOnlineWorks(true);
        }
    }

    /** 在线模式下状态栏左侧的统一前缀（「💻 电脑在线相册 (url)」）。 */
    private String onlineSourceLabel() {
        return "💻 电脑在线相册 (" + onlineClient.resolveBaseUrl() + ")";
    }

    /**
     * 状态栏里的「数据来源后缀」。
     * 当前展示的是本地快照时，明确告诉用户数据新鲜度 ——
     * 否则「秒开」会被误读成「电脑已经连上了」，电脑真关机时反而更像故障。
     */
    private String onlineDataSuffix() {
        if (!onlineListFromSnapshot) return "";
        String age = snapshotAgeText();
        return age != null ? "（本地快照 · " + age + "）" : "（本地快照）";
    }

    /**
     * 在线列表状态栏的统一起草：`💻 电脑在线相册 (url) · 共 N 套（本地快照 · X 分钟前）`。
     * @param tail 追加在末尾的短语（如「正在刷新…」），可为 null。
     */
    private String onlineStatusLine(String tail) {
        StringBuilder sb = new StringBuilder(onlineSourceLabel())
                .append(" · 共 ").append(onlineWorks.size()).append(" 套")
                .append(onlineDataSuffix());
        if (tail != null) sb.append(" · ").append(tail);
        return sb.toString();
    }

    /**
     * 【体感加速】开机预热：不做任何 UI 阻塞。
     *
     * 两段式：
     *   1) 读磁盘快照 → 立刻进内存（离线也能秒开，且能顶住「电脑关机 / 换网」）；
     *   2) 后台静默拉一次最新列表 + 分类，成功即覆盖快照。
     *
     * 为什么值得在开机就做：在线相册的入口是用户**必然**要点的一次操作，
     * 与其等他点完再等网络，不如在他还没点的时候就把这段时间花掉。
     * 代价是开机后台多一次几 KB~几百 KB 的请求，失败也不打扰用户。
     */
    private void primeOnlineWorksInBackground() {
        submitToWorker(() -> {
            final String cachedWorks = OnlineListCache.loadWorks(this);
            final String cachedCats = OnlineListCache.loadCategories(this);
            final long snapAt = OnlineListCache.snapshotAtMs(this);
            if (cachedWorks == null) return;
            List<OnlineWorkEntry> parsed;
            OnlineGalleryClient.CategoriesResult cats = null;
            try {
                parsed = OnlineGalleryClient.parseWorks(cachedWorks);
                if (cachedCats != null) cats = OnlineGalleryClient.parseCategories(cachedCats);
            } catch (Exception e) {
                // 快照损坏：直接丢掉，下次成功请求会重写。
                // 注意这里不能只 log 不清理，否则每个冷启动都要白解析一次坏文件。
                Log.w("MainActivity", "在线列表快照解析失败，已丢弃: " + e.getMessage());
                OnlineListCache.clear(this);
                return;
            }
            if (parsed == null || parsed.isEmpty()) return;
            final List<OnlineWorkEntry> list = parsed;
            final OnlineGalleryClient.CategoriesResult catsFinal = cats;
            uiHandler.post(() -> {
                // 已有更新的数据就别用旧快照盖掉（例如 onStart 里刚刷完）
                if (!onlineWorks.isEmpty()) return;
                onlineWorks.addAll(list);
                mergeLocalSentWorks();
                if (catsFinal != null) lastCategoriesResult = catsFinal;
                onlineListFromSnapshot = true;
                onlineSnapshotAtMs = snapAt;
                if (isOnlineMode) {
                    // 【体感加速 bugfix】applyOnlineCategoryFilter 只筛作品，
                    // 不重建分类条；如果不显式 rebuild，分类条会一直空着，
                    // 离线或 categories 快照缺失时用户连「全部 N」都看不到。
                    updateOnlineCategoryCounts(lastCategoriesResult, onlineWorks);
                    applyOnlineCategoryFilter(selectedOnlineCategory);
                    statusText.setText(onlineStatusLine("正在后台刷新…"));
                }
            });
        });
        silentPrefetchOnlineWorks();
    }

    /**
     * 后台静默预热：拉一次最新的全量列表与分类。
     * 失败**不打扰用户**（此时用户可能还在手机本地相册里，弹错只会莫名其妙）。
     */
    private void silentPrefetchOnlineWorks() {
        onlineClient.fetchCategories(new OnlineGalleryClient.Callback<OnlineGalleryClient.CategoriesResult>() {
            @Override
            public void onSuccess(OnlineGalleryClient.CategoriesResult result) {
                lastCategoriesResult = result;
                if (isOnlineMode) updateOnlineCategoryCounts(result, onlineWorks);
            }

            @Override
            public void onError(Exception error) {
                Log.i("MainActivity", "在线分类预热失败（静默）: " + error.getMessage());
            }
        });
        onlineClient.fetchWorks(null, null, new OnlineGalleryClient.Callback<List<OnlineWorkEntry>>() {
            @Override
            public void onSuccess(List<OnlineWorkEntry> works) {
                if (works == null) return;
                // 在线模式下由 refreshOnlineWorks 统一负责，避免两条路径同时写 onlineWorks
                if (isOnlineMode) return;
                onlineWorks.clear();
                onlineWorks.addAll(works);
                mergeLocalSentWorks();
                onlineListFromSnapshot = false;
            }

            @Override
            public void onError(Exception error) {
                Log.i("MainActivity", "在线列表预热失败（静默）: " + error.getMessage());
            }
        });
    }

    /**
     * 本地快照年龄的人话描述。
     * @return 例：「3 分钟前」；无快照时返回 null（调用方自行省略该行）。
     */
    private String snapshotAgeText() {
        long at = onlineSnapshotAtMs > 0 ? onlineSnapshotAtMs : OnlineListCache.snapshotAtMs(this);
        if (at <= 0) return null;
        long min = Math.max(0, (System.currentTimeMillis() - at) / 60_000L);
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long hour = min / 60;
        if (hour < 48) return hour + " 小时前";
        return (hour / 24) + " 天前";
    }

    private void showOnlineStatusOrConfigDialog() {
        String modeStr = isOnlineMode ? "💻 电脑在线模式" : "📱 手机本地模式";
        String serverUrl = onlineClient.resolveBaseUrl();
        int count = onlineWorks.size();
        String age = snapshotAgeText();
        String snapLine = age != null ? "\n本地快照：" + age + "（离线也能秒开）" : "";
        new AlertDialog.Builder(this)
                .setTitle("在线相册网络状态")
                .setMessage("当前状态：" + modeStr + "\n电脑服务：" + serverUrl + "\n在线作品缓存：" + count + " 套" + snapLine + "\n\n提示：点击小图标可直接在手机与电脑之间秒切。")
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

    private boolean autoDiscovering = false;
    /** 上次自动发现的时间戳：用「冷却」代替「一次性开关」，保证失败后可反复重试 */
    private long lastAutoDiscoverAtMs = 0L;

    /**
     * 电脑端会把已使用的作品物理移走到对应次数文件夹，但手机端已同步到本地，
     * 因此这里把本地仍存活的「已发送N次」副本合并回列表并置顶，展示到倒计时结束为止。
     */
    private void mergeLocalSentWorks() {
        long nowMs = System.currentTimeMillis();
        CleanupSettings.Values cleanup = CleanupSettings.read(this);
        List<OnlineWorkLifecycle.Item> usedItems =
                OnlineWorkLifecycle.getActiveUsedItems(this, nowMs, cleanup.moveAfterMs());
        if (usedItems == null || usedItems.isEmpty()) return;

        for (OnlineWorkLifecycle.Item item : usedItems) {
            boolean present = false;
            for (OnlineWorkEntry w : onlineWorks) {
                if (w.id.equals(item.id)) {
                    present = true;
                    break;
                }
            }
            if (present) continue;
            List<String> images = item.images == null ? new ArrayList<String>() : new ArrayList<>(item.images);
            String copyText = item.copyText == null ? "" : item.copyText;
            String stage = "已发送" + item.useCount + "次";
            onlineWorks.add(new OnlineWorkEntry(
                    item.id, item.title, item.destination, stage,
                    item.useCount, 2, true, Math.max(0, 2 - item.useCount), stage,
                    images, images.size(), copyText, !copyText.trim().isEmpty(),
                    new ArrayList<String>(), item.firstSharedAtMs));
        }

        // 置顶：已发送过的排在最前（按最近使用时间倒序），未使用的保持原顺序
        final java.util.Map<String, Long> usedAt = new java.util.HashMap<>();
        for (OnlineWorkLifecycle.Item item : usedItems) {
            usedAt.put(item.id, item.firstSharedAtMs);
        }
        java.util.Collections.sort(onlineWorks, new java.util.Comparator<OnlineWorkEntry>() {
            @Override
            public int compare(OnlineWorkEntry a, OnlineWorkEntry b) {
                boolean au = usedAt.containsKey(a.id);
                boolean bu = usedAt.containsKey(b.id);
                if (au && bu) return Long.compare(usedAt.get(b.id), usedAt.get(a.id));
                if (au) return -1;
                if (bu) return 1;
                return 0;
            }
        });
    }

    /**
     * 刷新在线作品列表。
     *
     * 2026-09-20 体感改造三点：
     * 1. **两个请求并行**。原来是 `fetchCategories` 成功之后才发 `fetchWorks`，
     *    白白多一个网络往返（手机 Wi-Fi 下每个往返都是实打实的几百毫秒）。
     *    现在分类和列表同时发出，列表先到就先渲染。
     * 2. **有数据就不阻塞**。已经有列表（内存或本地快照）时，状态栏只说「正在刷新…」，
     *    而不是把「正在连接电脑在线相册…」摆在用户面前。
     * 3. **失败不清屏**。已经有快照数据时，刷新失败只做软提示并保留列表；
     *    原来无论有没有数据都会 `handleOnlineError` 把容器清空换成错误卡片 ——
     *    电脑关机时用户连上次的内容都看不到。
     */
    private void refreshOnlineWorks(boolean userInitiated) {
        final boolean hadData = !onlineWorks.isEmpty();
        if (hadData) {
            statusText.setText(onlineStatusLine("正在刷新…"));
        } else {
            statusText.setText("正在连接电脑在线相册…");
        }

        onlineClient.fetchCategories(new OnlineGalleryClient.Callback<OnlineGalleryClient.CategoriesResult>() {
            @Override
            public void onSuccess(OnlineGalleryClient.CategoriesResult catResult) {
                if (!isOnlineMode || showingTrash) return;
                lastCategoriesResult = catResult;
                updateOnlineCategoryCounts(catResult, onlineWorks);
                if (!onlineWorks.isEmpty()) applyOnlineCategoryFilter(selectedOnlineCategory);
            }

            @Override
            public void onError(Exception error) {
                // 分类失败不致命：列表照样能看，只有分类数字会缺
                Log.i("MainActivity", "在线分类刷新失败: " + error.getMessage());
            }
        });

        onlineClient.fetchWorks(null, null, new OnlineGalleryClient.Callback<List<OnlineWorkEntry>>() {
            @Override
            public void onSuccess(List<OnlineWorkEntry> works) {
                // 连通成功：记住这条可用地址，下次直接复用
                onlineClient.markBaseUrlGood(onlineClient.resolveBaseUrl());
                if (!isOnlineMode || showingTrash) return;
                onlineWorks.clear();
                if (works != null) {
                    onlineWorks.addAll(works);
                }
                mergeLocalSentWorks();
                onlineListFromSnapshot = false;
                onlineSnapshotAtMs = 0L;
                if (lastCategoriesResult != null) {
                    updateOnlineCategoryCounts(lastCategoriesResult, onlineWorks);
                }
                applyOnlineCategoryFilter(selectedOnlineCategory);
                statusText.setText("💻 已连接电脑在线相册 (" + onlineClient.resolveBaseUrl() + ") · 共 " + onlineWorks.size() + " 套");
                finishVisibleRefresh("已刷新电脑在线作品 " + onlineWorks.size() + " 套");
            }

            @Override
            public void onError(Exception error) {
                if (!isOnlineMode) return;
                if (!onlineWorks.isEmpty()) {
                    // 有快照/旧数据：保留用户已经看到的列表，只做软提示
                    String msg = error != null && error.getMessage() != null ? error.getMessage() : "网络超时";
                    statusText.setText("💻 离线快照 · 共 " + onlineWorks.size() + " 套 · 刷新失败：" + msg);
                    finishVisibleRefresh("已保留本地快照（刷新失败）");
                } else {
                    handleOnlineError("读取作品列表失败", error);
                }
                tryAutoDiscoverPc();
            }
        });
    }

    /**
     * 启动局域网信标监听：电脑端每 2 秒广播一次自身地址，手机被动接收即可，
     * 不必自己扫描。收到「新地址」时自动切过去并刷新 —— 在线相册稳定可读的关键。
     */
    private void startOnlineBeaconListener() {
        onlineClient.startBeaconListener(() -> {
            if (!isOnlineMode || statusText == null) return;
            statusText.setText("📡 已同步电脑新地址 " + onlineClient.resolveBaseUrl());
            refreshOnlineWorks(false);
        });
    }

    /**
     * 连接失败时自动在局域网搜索电脑在线相册服务。
     * 修复要点：早期版本用「一次性开关」，一旦首次搜索失败就永久放弃，
     * 导致纯 Wi-Fi 设备（vivo/华为）整个进程都卡在 127.0.0.1 读不到作品。
     * 现在改为 15 秒冷却，失败后可反复重试。
     */
    private void tryAutoDiscoverPc() {
        if (autoDiscovering) return;
        // 【0.8.43 修】已有本地快照就别打断用户：snapshot 秒开体验 > auto-discover 的修复率。
        // 用户手动点「重试连接」依然会走自动搜索（不经过 tryAutoDiscoverPc）。
        if (onlineListFromSnapshot && onlineWorks != null && !onlineWorks.isEmpty()) {
            String age = snapshotAgeText();
            if (age != null) return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAutoDiscoverAtMs < 15000L) return;
        lastAutoDiscoverAtMs = now;
        autoDiscovering = true;
        statusText.setText("正在自动搜索局域网内的电脑在线相册…");
        onlineClient.discoverPcServer(new OnlineGalleryClient.Callback<String>() {
            @Override
            public void onSuccess(String baseUrl) {
                autoDiscovering = false;
                if (!isOnlineMode) return;
                toast("✅ 已自动发现电脑相册服务 " + baseUrl);
                refreshOnlineWorks(false);
            }

            @Override
            public void onError(Exception error) {
                autoDiscovering = false;
                if (!isOnlineMode) return;
                // 【0.8.43 修】不再覆盖 snapshot 状态条；改成在底部悄悄提示，让顶部状态栏继续显示「本地快照 · X 分钟前」。
                String snapNote = snapshotAgeText();
                if (snapNote != null) {
                    toast("⚠️ 暂时连不上电脑（本地快照 · " + snapNote + " 仍可秒开）");
                } else {
                    statusText.setText("暂未搜索到电脑在线相册，可稍后点「重试连接」再次搜索");
                }
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
            return;
        }

        // 【体感加速 fallback】没有 categories 快照（首次冷启动 / 快照里只有作品没分类），
        // 从 onlineWorks 派生目的地计数。这样用户哪怕离线、没拿到服务端分类，
        // 也能点「全部 N」之外的常用目的地按钮做筛选。
        java.util.Map<String, Integer> destCounts = new java.util.LinkedHashMap<>();
        for (OnlineWorkEntry w : entries) {
            String dest = w.destination;
            if (dest == null || dest.trim().isEmpty()) continue;
            dest = dest.trim();
            if (WorkCategory.ALL.equals(dest) || "全部".equals(dest)) continue;
            if ("待首发".equals(dest) || "已发1次".equals(dest) || "已发2次".equals(dest)) continue;
            destCounts.put(dest, destCounts.getOrDefault(dest, 0) + 1);
        }
        for (java.util.Map.Entry<String, Integer> item : destCounts.entrySet()) {
            if (item.getValue() <= 0) continue;
            if (onlineCategoryButtons.containsKey(item.getKey())) continue;
            String displayBase = formatFolderLabel(item.getKey());
            String fullLabel = displayBase + " " + item.getValue();
            Button btn = createOnlineCategoryButton(item.getKey(), displayBase, fullLabel, item.getKey().equals(selectedOnlineCategory));
            categoryBar.addView(btn);
            onlineCategoryButtons.put(item.getKey(), btn);
            onlineCategoryLabels.put(item.getKey(), displayBase);
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

    // ==================================================================
    // 在线回收站（电脑端 45835）：已使用 / 已标记垃圾 双 Tab
    // ------------------------------------------------------------------
    // 电脑端阶段库：
    //   已使用   -> _已发送1次（微信公众号可发）  ：点过平台按钮、useCount>=1
    //   已标记垃圾 -> _垃圾作品（后续参考分析）   ：手机端点删除判定为垃圾，永久保留
    // 「恢复」两个 Tab 通用：移回「已发送0次」+ 次数归零 + 撤销垃圾标记。
    // ==================================================================

    private Button recycleTabButton(String label) {
        Button btn = new Button(this);
        styleOnlineRecycleTab(btn, label, false, 0);
        return btn;
    }

    private void styleOnlineRecycleTab(Button btn, String label, boolean selected, int count) {
        if (btn == null) return;
        String text = (label == null ? "" : label) + (count > 0 ? " " + count : "");
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        btn.setPadding(dp(8), 0, dp(8), 0);
        if (selected) {
            btn.setTextColor(Color.WHITE);
            btn.setBackground(round(Color.rgb(15, 135, 88), 11));
        } else {
            btn.setTextColor(Color.rgb(70, 78, 74));
            btn.setBackground(round(Color.rgb(232, 234, 233), 11));
        }
    }

    private void refreshOnlineRecycleTabStyles() {
        styleOnlineRecycleTab(onlineRecycleSentTabButton, "已使用",
                "sent".equals(onlineRecycleTab), onlineRecycleSentCount);
        styleOnlineRecycleTab(onlineRecycleGarbageTabButton, "已标记垃圾",
                "garbage".equals(onlineRecycleTab), onlineRecycleGarbageCount);
    }

    private void refreshLocalTrashTabStyles() {
        styleOnlineRecycleTab(localTrashDeletedTabButton, "已删除",
                "deleted".equals(localTrashTab), localTrashDeletedCount);
        styleOnlineRecycleTab(localTrashGarbageTabButton, "已标记垃圾",
                "garbage".equals(localTrashTab), localTrashGarbageCount);
    }

    /** 切换手机本地回收站 Tab（已删除 / 已标记垃圾），与在线回收站的 selectOnlineRecycleTab 同构。 */
    private void selectLocalTrashTab(String tab) {
        localTrashTab = "garbage".equals(tab) ? "garbage" : "deleted";
        refreshLocalTrashTabStyles();
        refreshWorks();
    }

    private void selectOnlineRecycleTab(String tab) {
        String want = "garbage".equals(tab) ? "garbage" : "sent";
        onlineRecycleTab = want;
        onlineRecyclePageLimit = 30;
        refreshOnlineRecycleTabStyles();
        loadOnlineRecycle(want, true);
    }

    /** 进入在线回收站（顶栏「回收站」按钮在在线模式下走这里，不再错进本地回收站）。 */
    private void showOnlineRecycle(String tab) {
        selectedWorkIds.clear();
        quickTrashButton.setVisibility(View.GONE);
        showingTrash = false;
        enteredTrashFromOnline = false;
        showingOnlineRecycle = true;
        onlineRecycleTab = "garbage".equals(tab) ? "garbage" : "sent";
        onlineRecyclePageLimit = 30;
        onlineRecycleWorks.clear();

        if (searchBar != null) searchBar.setVisibility(View.GONE);
        categorySelector.setVisibility(View.GONE);
        if (onlineRecycleTabBar != null) onlineRecycleTabBar.setVisibility(View.VISIBLE);
        if (localTrashTabBar != null) localTrashTabBar.setVisibility(View.GONE);
        refreshOnlineRecycleTabStyles();

        leftModeButton.setImageResource(R.drawable.ic_album_back);
        leftModeButton.setContentDescription("返回在线作品");
        leftModeButton.setVisibility(View.VISIBLE);
        rightModeButton.setImageResource(R.drawable.ic_album_refresh);
        rightModeButton.setContentDescription("刷新在线回收站");
        headingText.setText("在线回收站");
        footerNote.setText("♻️ 在线回收站：读取电脑端「_已发送1次」与「_垃圾作品」两个阶段库。"
                + "点「恢复」即移回「已发送0次」并归零次数、撤销垃圾标记；垃圾样本库电脑端永久保留，不会自动清理。");
        worksContainer.removeAllViews();
        scannedCountText.setText("0");

        loadOnlineRecycle(onlineRecycleTab, false);
    }

    private void loadOnlineRecycle(String tab, boolean userInitiated) {
        final String want = "garbage".equals(tab) ? "garbage" : "sent";
        statusText.setText("正在读取电脑在线回收站…");
        onlineClient.fetchRecycle(want, new OnlineGalleryClient.Callback<OnlineGalleryClient.RecycleResult>() {
            @Override
            public void onSuccess(OnlineGalleryClient.RecycleResult result) {
                if (!isOnlineMode || !showingOnlineRecycle) return;
                // 两个 Tab 的角标数字用同一份 counts，保证与各自列表 total 严格一致
                onlineRecycleSentCount = result.sentCount;
                onlineRecycleGarbageCount = result.garbageCount;
                refreshOnlineRecycleTabStyles();
                if (!want.equals(onlineRecycleTab)) return; // 期间用户切了 Tab，丢弃过期结果
                onlineRecycleWorks.clear();
                onlineRecycleWorks.addAll(result.works);
                renderOnlineRecycleCards();
                statusText.setText("♻️ 在线回收站 · 已使用 " + onlineRecycleSentCount
                        + " · 已标记垃圾 " + onlineRecycleGarbageCount);
                finishVisibleRefresh("在线回收站已刷新");
            }

            @Override
            public void onError(Exception error) {
                if (!isOnlineMode || !showingOnlineRecycle) return;
                String msg = error != null && error.getMessage() != null ? error.getMessage() : "网络超时";
                statusText.setText("读取在线回收站失败 (" + msg + ")");
                finishVisibleRefresh("读取失败");
            }
        });
    }

    private void renderOnlineRecycleCards() {
        if (!isOnlineMode || !showingOnlineRecycle) return;
        resetOnlineThumbScheduler();
        worksContainer.removeAllViews();
        scannedCountText.setText(String.valueOf(onlineRecycleWorks.size()));

        if (onlineRecycleWorks.isEmpty()) {
            String emptyMsg = "garbage".equals(onlineRecycleTab)
                    ? "电脑端「_垃圾作品」暂为空\n手机端判定删除的作品会永久保留在这里，供后续参考分析"
                    : "电脑端「_已发送1次」暂为空\n点过平台按钮的作品会自动进入这里";
            TextView empty = text(emptyMsg, 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(14), dp(28), dp(14), dp(28));
            empty.setBackground(round(Color.WHITE, 18));
            worksContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            worksContainer.addView(recycleLocalTrashEntry(), margins(0, dp(10), 0, dp(18)));
            return;
        }

        int showCount = Math.min(onlineRecycleWorks.size(), onlineRecyclePageLimit);
        for (int i = 0; i < showCount; i++) {
            worksContainer.addView(onlineRecycleCard(onlineRecycleWorks.get(i)), margins(0, 0, 0, dp(10)));
        }

        if (onlineRecycleWorks.size() > showCount) {
            Button loadMoreBtn = new Button(this);
            loadMoreBtn.setText("加载更多 (已显示 " + showCount + " / " + onlineRecycleWorks.size() + " 套)");
            loadMoreBtn.setAllCaps(false);
            loadMoreBtn.setTextSize(13);
            loadMoreBtn.setTextColor(Color.rgb(15, 135, 88));
            loadMoreBtn.setBackground(roundWithStroke(Color.WHITE, 14, Color.rgb(200, 230, 215)));
            loadMoreBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
            loadMoreBtn.setOnClickListener(v -> {
                onlineRecyclePageLimit += 30;
                renderOnlineRecycleCards();
            });
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(-1, dp(44));
            btnParams.setMargins(dp(12), dp(8), dp(12), dp(12));
            worksContainer.addView(loadMoreBtn, btnParams);
        }

        worksContainer.addView(recycleLocalTrashEntry(), margins(0, dp(6), 0, dp(18)));
    }

    /** 在线回收站底部保留「手机本地回收站」入口，避免在线模式再也进不去本地回收站。 */
    private View recycleLocalTrashEntry() {
        Button btn = new Button(this);
        btn.setText("📱 打开手机本地回收站");
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTextColor(Color.rgb(90, 96, 93));
        btn.setBackground(roundWithStroke(Color.WHITE, 14, Color.rgb(214, 219, 216)));
        btn.setPadding(dp(14), dp(10), dp(14), dp(10));
        btn.setContentDescription("打开手机本地回收站");
        btn.setOnClickListener(v -> {
            toast("手机本地回收站");
            showTrash();
        });
        return btn;
    }

    private View onlineRecycleCard(OnlineWorkEntry work) {
        LinearLayout card = card();
        card.setTag("online_recycle:" + work.id);
        card.setOrientation(LinearLayout.VERTICAL);
        if (work.garbage) {
            card.setBackground(roundWithStroke(Color.rgb(250, 246, 245), 16, Color.rgb(233, 216, 212)));
            card.setElevation(0);
        }

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        String badgeText = work.garbage ? "🗑️ 垃圾样本" : ("✅ 已使用 " + Math.max(1, work.useCount) + " 次");
        TextView stageBadge = text(badgeText, 11, true);
        stageBadge.setTextColor(work.garbage ? Color.rgb(168, 62, 52) : Color.rgb(180, 85, 20));
        stageBadge.setBackground(round(work.garbage ? Color.rgb(250, 232, 229) : Color.rgb(254, 243, 235), 8));
        stageBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(-2, -2);
        stageParams.setMargins(0, 0, dp(6), 0);
        titleRow.addView(stageBadge, stageParams);

        if (work.destination != null && !work.destination.isEmpty() && !"其他".equals(work.destination)) {
            TextView destBadge = text(work.destination, 11, true);
            destBadge.setTextColor(Color.rgb(25, 120, 80));
            destBadge.setBackground(round(Color.rgb(228, 244, 235), 8));
            destBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams destParams = new LinearLayout.LayoutParams(-2, -2);
            destParams.setMargins(0, 0, dp(6), 0);
            titleRow.addView(destBadge, destParams);
        }

        TextView name = text(formatDisplayTitle(work.title), 14, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(titleRow);

        if (work.images != null && !work.images.isEmpty()) {
            card.addView(onlinePreviewStrip(work), margins(0, dp(4), 0, dp(2)));
        }

        StringBuilder detail = new StringBuilder();
        // 元信息统一口径（与 iOS `configureOnline` 逐字一致）：
        // `💻 电脑在线 · N 图 · 使用态` + 日期后缀（日期格式见 extractTimestampBadge）。
        detail.append("💻 电脑在线 · ").append(work.imageCount).append(" 图 · ")
              .append(work.useCount > 0 ? "已使用 " + work.useCount + " 次" : "未使用");
        String timeBadge = extractTimestampBadge(work.id);
        if (timeBadge.isEmpty()) timeBadge = extractTimestampBadge(work.title);
        if (!timeBadge.isEmpty()) detail.append(" · ").append(timeBadge);
        if (work.dispatchedTo != null && !work.dispatchedTo.isEmpty()) {
            detail.append("\n记录：").append(String.join("、", work.dispatchedTo));
        }
        if (work.garbage) {
            // DSH-093 A3：与本地回收站同一格式 —— 原本在线卡少了「🗑️ 垃圾样本」前缀
            detail.append(" · 🗑️ 垃圾样本\n垃圾备注：").append(work.garbageRemark.isEmpty() ? "（未填写）" : work.garbageRemark);
        }
        TextView meta = text(detail.toString(), 12, false);
        meta.setTextColor(Color.rgb(75, 82, 78));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
        metaParams.setMargins(0, dp(3), 0, dp(5));
        card.addView(meta, metaParams);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        Button restore = compactButton("恢复", true);
        restore.setContentDescription("恢复回已发送0次");
        restore.setOnClickListener(v -> confirmRestoreOnlineRecycleWork(work));
        actionRow.addView(restore, new LinearLayout.LayoutParams(-2, dp(36)));

        Button second = new Button(this);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(-2, dp(36));
        secondParams.setMargins(dp(8), 0, 0, 0);
        if (work.garbage) {
            second.setText("备注");
            styleNeumorphicButton(second, STYLE_MUTED_GRAY);
            second.setContentDescription("填写垃圾备注");
            second.setOnClickListener(v -> promptRemarkOnlineRecycle(work));
        } else {
            second.setText("删除");
            styleNeumorphicButton(second, STYLE_DANGER_WHITE);
            second.setContentDescription("判定为垃圾并移入垃圾样本库");
            second.setOnClickListener(v -> confirmDeleteOnlineWork(work));
        }
        actionRow.addView(second, secondParams);

        // 「复制路径」紧跟在第二个按钮之后：回收站里的作品同样复制电脑成品库路径
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(-2, dp(36));
        copyParams.setMargins(dp(8), 0, 0, 0);
        actionRow.addView(copyPathButton(work.path, work.garbage ? "垃圾样本" : "电脑在线作品"), copyParams);

        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.setMargins(0, dp(8), 0, dp(2));
        card.addView(actionRow, actionParams);
        return card;
    }

    private void confirmRestoreOnlineRecycleWork(OnlineWorkEntry work) {
        String tail = "garbage".equals(onlineRecycleTab)
                ? "，并撤销垃圾标记。" : "，使用次数归零。";
        new AlertDialog.Builder(this)
                .setTitle("恢复作品")
                .setMessage("将把该作品移回电脑端「已发送0次（抖音小红书可发）」" + tail
                        + "\n\n恢复后它会重新出现在在线相册里。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (dialog, which) ->
                        onlineClient.restoreWork(work.id, new OnlineGalleryClient.Callback<OnlineGalleryClient.ActionResult>() {
                            @Override
                            public void onSuccess(OnlineGalleryClient.ActionResult result) {
                                if (!showingOnlineRecycle) return;
                                toast(result.ok
                                        ? "♻️ 已恢复：" + (result.message.isEmpty() ? "回到已发送0次" : result.message)
                                        : "恢复失败：" + result.message);
                                if (result.ok) {
                                    removeFromOnlineRecycle(work.id);
                                    loadOnlineRecycle(onlineRecycleTab, false);
                                }
                            }

                            @Override
                            public void onError(Exception error) {
                                toast("恢复失败：" + (error != null ? error.getMessage() : "网络异常"));
                            }
                        }))
                .show();
    }

    private void promptRemarkOnlineRecycle(OnlineWorkEntry work) {
        final EditText input = new EditText(this);
        input.setHint("例如：文案公文味重 / 图片 AI 味浓 / 选题不合适");
        input.setText(work.garbageRemark);
        input.setSingleLine(false);
        input.setMaxLines(3);
        FrameLayout holder = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);
        lp.setMargins(pad, pad, pad, 0);
        holder.addView(input, lp);

        AlertDialog protectedInputDialog = new AlertDialog.Builder(this)
                .setTitle("垃圾备注（写入作品元数据）")
                .setView(holder)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存备注", (dialog, which) -> {
                    String remark = input.getText().toString().trim();
                    onlineClient.remarkGarbage(work.id, remark,
                            new OnlineGalleryClient.Callback<OnlineGalleryClient.ActionResult>() {
                                @Override
                                public void onSuccess(OnlineGalleryClient.ActionResult result) {
                                    toast(result.ok ? "✅ 已写入垃圾备注" : "写入失败：" + result.message);
                                    if (result.ok && showingOnlineRecycle) {
                                        loadOnlineRecycle(onlineRecycleTab, false);
                                    }
                                }

                                @Override
                                public void onError(Exception error) {
                                    toast("写入失败：" + (error != null ? error.getMessage() : "网络异常"));
                                }
                            });
                })
                .create();
            // 带输入框的弹窗：禁止点击背景关闭 —— 误触一次就把打好的字全丢了（BUG_LEDGER DSH-084）。
            protectedInputDialog.setCanceledOnTouchOutside(false);
            protectedInputDialog.show();
    }

    private void removeFromOnlineRecycle(String workId) {
        for (int i = 0; i < onlineRecycleWorks.size(); i++) {
            if (onlineRecycleWorks.get(i).id.equals(workId)) {
                onlineRecycleWorks.remove(i);
                break;
            }
        }
        renderOnlineRecycleCards();
    }

    private void applyOnlineCategoryFilter(String catKey) {
        if (!isOnlineMode || showingTrash || showingOnlineRecycle) return;
        onlinePageLimit = 30;
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        String[] tokens = query.isEmpty() ? new String[0] : query.split("\\s+");

        long nowMs = System.currentTimeMillis();
        CleanupSettings.Values cleanup = CleanupSettings.read(this);
        long moveAfterMs = cleanup.moveAfterMs();

        currentOnlineFilteredEntries.clear();
        for (OnlineWorkEntry work : onlineWorks) {
            // 核心生命周期：若已被移入手机回收站或已达设置时间（如1小时），在在线活跃相册中隐藏
            if (OnlineWorkLifecycle.shouldBeInTrash(this, work.id, nowMs, moveAfterMs)) {
                continue;
            }
            if (!WorkCategory.ALL.equals(catKey) && !"全部".equals(catKey)) {
                boolean matchesCategory;
                if ("待首发".equals(catKey)) {
                    matchesCategory = (work.useCount == 0);
                } else if ("已发1次".equals(catKey) || "已发1".equals(catKey)) {
                    matchesCategory = (work.useCount == 1);
                } else if ("已发2次".equals(catKey) || "已发2".equals(catKey) || "已用满".equals(catKey)) {
                    matchesCategory = (work.useCount >= 2);
                } else {
                    String cleanCat = catKey.replace("🌕", "").replace("🇨🇳", "").replace("🎮", "").replace("🏷️", "").trim();
                    if ("游戏".equals(cleanCat)) {
                        matchesCategory = "游戏".equals(work.destination)
                                || (work.id != null && work.id.contains("游戏"))
                                || (work.title != null && (work.title.contains("游戏") || work.title.contains("破冰") || work.title.contains("桌游")));
                    } else if ("中秋".equals(cleanCat)) {
                        matchesCategory = "中秋".equals(work.destination)
                                || (work.title != null && work.title.contains("中秋"))
                                || (work.copyText != null && work.copyText.contains("中秋"));
                    } else if ("国庆".equals(cleanCat)) {
                        matchesCategory = "国庆".equals(work.destination)
                                || (work.title != null && (work.title.contains("国庆") || work.title.contains("十一")))
                                || (work.copyText != null && (work.copyText.contains("国庆") || work.copyText.contains("十一")));
                    } else {
                        matchesCategory = catKey.equals(work.destination) || cleanCat.equals(work.destination)
                                || (work.title != null && (work.title.contains(catKey) || (!cleanCat.isEmpty() && work.title.contains(cleanCat))));
                    }
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
        if (!isOnlineMode || showingTrash || showingOnlineRecycle) return;
        resetOnlineThumbScheduler();
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

        // Usage protection status badge & lifecycle countdown
        if (work.useCount > 0) {
            String statusText = "已使用 " + work.useCount + " 次";
            OnlineWorkLifecycle.Item lifeItem = OnlineWorkLifecycle.getItem(this, work.id);
            if (lifeItem != null && lifeItem.firstSharedAtMs > 0) {
                CleanupSettings.Values cleanup = CleanupSettings.read(this);
                long remainMs = (lifeItem.firstSharedAtMs + cleanup.moveAfterMs()) - System.currentTimeMillis();
                if (remainMs > 0) {
                    int remainMin = Math.max(1, (int) (remainMs / 60000L));
                    statusText = "已使用 · 剩 " + remainMin + " 分钟入回收站";
                }
            }
            TextView useBadge = text(statusText, 11, true);
            useBadge.setTextColor(Color.rgb(180, 85, 20));
            useBadge.setBackground(round(Color.rgb(254, 243, 235), 8));
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
        // 元信息统一口径（与 iOS `configureOnline` 逐字一致）：
        // `💻 电脑在线 · N 图 · 使用态` + 日期后缀（日期格式见 extractTimestampBadge）。
        detail.append("💻 电脑在线 · ").append(work.imageCount).append(" 图 · ")
              .append(work.useCount > 0 ? "已使用 " + work.useCount + " 次" : "未使用");
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
        // 【文案缺失守卫】剔除 <<<...>>> 标记后实质字数不足 = 空壳作品：
        // 不渲染任何可点击文案按钮（防止合成兜底冒充真实文案），置灰标红提示。
        boolean copyMissing = isCopySubstanceMissing(work.copyText);
        if (copyMissing) {
            Button missing = new Button(this);
            missing.setText("⚠️ 文案缺失（空壳作品，不可分发）");
            missing.setEnabled(false);
            missing.setAlpha(0.55f);
            missing.setTextSize(12);
            styleNeumorphicButton(missing, STYLE_MUTED_GRAY);
            missing.setTextColor(Color.rgb(178, 34, 34));
            missing.setContentDescription("该在线作品文案缺失，已禁止分发");
            platformRow.addView(missing, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        }
        for (PlatformCopyParser.AvailableItem item : (copyMissing
                ? new ArrayList<PlatformCopyParser.AvailableItem>() : platforms)) {
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

        Button delete = new Button(this);
        delete.setText("删除");
        styleNeumorphicButton(delete, STYLE_DANGER_WHITE);
        delete.setContentDescription("删除电脑在线作品");
        delete.setOnClickListener(v -> confirmDeleteOnlineWork(work));
        platformRow.addView(delete, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));

        // 「复制路径」紧跟在「删除」之后：在线作品复制电脑成品库里的作品文件夹路径
        platformRow.addView(copyPathButton(work.path, "电脑在线作品"),
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));

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

        // 【2026-09-21 修复】V4.5 多版本文案（MULTI）已逐版本出按钮，不再合成这 3 个旧版兜底按钮。
        // 它们的兜底内容取的是整篇原文，点一下就把全部 `<<<…>>>` 标记复制进剪贴板
        // （实测 111 份「已发送0次」作品命中）。多版本时只保留各版本自身按钮 + 「抖音避坑」。
        boolean multiVersion = PlatformCopyParser.hasMultiVersionBlocks(rawText) && !result.isEmpty();
        if (multiVersion) {
            if (douyinItem != null && douyinItem.copyText != null && !douyinItem.copyText.trim().isEmpty()) {
                result.add(douyinItem);
            }
        } else {
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
            result.add(new PlatformCopyParser.AvailableItem(PlatformCopyParser.Platform.XHS, "种草版",
                    PlatformCopyParser.stripProtocolMarkers(fallback)));
        }

        // 3. 大纲方案版
        if (xhs2Item != null && xhs2Item.copyText != null && !xhs2Item.copyText.trim().isEmpty()) {
            result.add(new PlatformCopyParser.AvailableItem(PlatformCopyParser.Platform.XHS_2, "大纲方案版", xhs2Item.copyText));
        } else {
            String outlineCopy = PlatformCopyParser.synthesizeOutlineCopy(fallback);
            result.add(new PlatformCopyParser.AvailableItem(PlatformCopyParser.Platform.XHS_2, "大纲方案版", outlineCopy));
        }
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

    /** 剔除 <<<...>>> 协议标记、空白与盲文空格（U+2800）后的文案实质内容。 */
    private static String copySubstance(String text) {
        if (text == null) return "";
        // 【2026-09-21 DSH-088】正则与 iOS `PlatformCopyParser.copySubstance` **逐字一致**：
        // 用 `<<+[^<>]*>>+` 连畸形标记（如只有两个 `>` 的 `<<<DOUYIN_END>>`）一起剥掉。
        // 取证：全库 642 份 `文案.txt` 新旧正则各算一次实质字数，跨过 30 字判线的 = 0 份。
        String s = text.replaceAll("<<+[^<>]*>>+", "");
        return s.replaceAll("[\\s\\u2800]", "");
    }

    /** 空壳作品判定：文案实质字数不足 30 字视为缺失，禁止合成兜底冒充真实文案。 */
    private static boolean isCopySubstanceMissing(String copyText) {
        return copySubstance(copyText).length() < 30;
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

    private void confirmDeleteOnlineWork(OnlineWorkEntry work) {
        String msg = "手机端：移入回收站（右上角垃圾箱可随时恢复）\n\n电脑端：移入垃圾样本库并在元数据标记为垃圾（全渠道硬拦截）\n\n选择「备注并删除」可先填写垃圾原因备注。";

        new AlertDialog.Builder(this)
                .setTitle("删除未发送作品")
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setNeutralButton("备注并删除", (dialog, which) -> promptRemarkThenDeleteOnlineWork(work))
                .setPositiveButton("删除", (dialog, which) -> doDeleteOnlineWork(work, ""))
                .show();
    }

    private void promptRemarkThenDeleteOnlineWork(OnlineWorkEntry work) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("例如：文案公文味重 / 图片 AI 味浓 / 选题不合适");
        input.setSingleLine(false);
        input.setMaxLines(3);
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        lp.setMargins(pad, pad, pad, 0);
        holder.addView(input, lp);

        AlertDialog protectedInputDialog = new AlertDialog.Builder(this)
                .setTitle("垃圾备注（随作品写入元数据）")
                .setView(holder)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", (dialog, which) -> doDeleteOnlineWork(work, input.getText().toString()))
                .create();
            // 带输入框的弹窗：禁止点击背景关闭 —— 误触一次就把打好的字全丢了（BUG_LEDGER DSH-084）。
            protectedInputDialog.setCanceledOnTouchOutside(false);
            protectedInputDialog.show();
    }

    private void doDeleteOnlineWork(OnlineWorkEntry work, String remark) {
        // 1. 立即记入手机本地回收站
        OnlineWorkLifecycle.moveToTrash(this, work, System.currentTimeMillis());

        // 2. 异步通知电脑端移入垃圾样本库并标记垃圾元数据
        onlineClient.deleteWork(work.id, remark, new OnlineGalleryClient.Callback<OnlineGalleryClient.DeleteResult>() {
            @Override
            public void onSuccess(OnlineGalleryClient.DeleteResult result) {
                // 电脑端移动完成
            }

            @Override
            public void onError(Exception error) {
                // 网络异常日志已记录，手机端回收站照常生效
            }
        });

        toast(remark == null || remark.trim().isEmpty()
                ? "🗑️ 已删除：手机回收站 + 电脑垃圾样本库"
                : "🗑️ 已删除并备注：手机回收站 + 电脑垃圾样本库");

        for (int i = 0; i < onlineWorks.size(); i++) {
            if (onlineWorks.get(i).id.equals(work.id)) {
                onlineWorks.remove(i);
                break;
            }
        }
        for (int i = 0; i < currentOnlineFilteredEntries.size(); i++) {
            if (currentOnlineFilteredEntries.get(i).id.equals(work.id)) {
                currentOnlineFilteredEntries.remove(i);
                break;
            }
        }

        java.io.File localDl = new java.io.File(getFilesDir(), "work-library/online/" + work.id);
        if (localDl.exists() && localDl.isDirectory()) {
            java.io.File[] sub = localDl.listFiles();
            if (sub != null) for (java.io.File sf : sub) sf.delete();
            localDl.delete();
        }

        applyOnlineCategoryFilter(selectedOnlineCategory);
    }

    private View onlineTrashCard(OnlineWorkLifecycle.Item item) {
        LinearLayout card = card();
        card.setTag("trash_online:" + item.id);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView originBadge = text("💻 在线作品", 11, true);
        originBadge.setTextColor(Color.rgb(2, 132, 199));
        originBadge.setBackground(round(Color.rgb(224, 242, 254), 8));
        originBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams originParams = new LinearLayout.LayoutParams(-2, -2);
        originParams.setMargins(0, 0, dp(6), 0);
        titleRow.addView(originBadge, originParams);

        if (item.destination != null && !item.destination.isEmpty() && !"其他".equals(item.destination)) {
            TextView destBadge = text(item.destination, 11, true);
            destBadge.setTextColor(Color.rgb(25, 120, 80));
            destBadge.setBackground(round(Color.rgb(228, 244, 235), 8));
            destBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams destParams = new LinearLayout.LayoutParams(-2, -2);
            destParams.setMargins(0, 0, dp(6), 0);
            titleRow.addView(destBadge, destParams);
        }

        String displayTitle = formatDisplayTitle(item.title);
        TextView name = text(displayTitle, 14, true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(titleRow);

        if (item.images != null && !item.images.isEmpty()) {
            card.addView(onlineImagesPreviewStrip(item.id, item.title, item.images), margins(0, dp(4), 0, dp(2)));
        }

        String timeStr = "";
        if (item.trashedAtMs > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            timeStr = " · 移入时间 " + sdf.format(new java.util.Date(item.trashedAtMs));
        }
        TextView meta = text(item.images.size() + " 张图片" + timeStr + " · 遵循手机端设置保留", 12, false);
        meta.setTextColor(Color.rgb(115, 120, 118));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
        metaParams.setMargins(0, dp(3), 0, dp(8));
        card.addView(meta, metaParams);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        Button restoreBtn = smallButton("恢复", false);
        restoreBtn.setOnClickListener(v -> {
            boolean ok = OnlineWorkLifecycle.restoreFromTrash(this, item.id);
            if (ok) {
                toast("已恢复到电脑在线相册");
                refreshWorks();
            } else {
                toast("恢复失败");
            }
        });
        btnRow.addView(restoreBtn, new LinearLayout.LayoutParams(0, dp(40), 1));

        Button delBtn = smallButton("彻底删除", true);
        delBtn.setTextColor(Color.rgb(220, 38, 38));
        delBtn.setBackground(roundWithStroke(Color.WHITE, 12, Color.rgb(254, 202, 202)));
        LinearLayout.LayoutParams delParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        delParams.setMargins(dp(10), 0, 0, 0);
        delBtn.setLayoutParams(delParams);
        delBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("彻底删除")
                    .setMessage("彻底删除后无法恢复，确定删除？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("彻底删除", (dialog, which) -> {
                        OnlineWorkLifecycle.deletePermanently(this, item.id);
                        toast("已彻底删除");
                        refreshWorks();
                    })
                    .show();
        });
        btnRow.addView(delBtn);

        card.addView(btnRow, margins(0, dp(4), 0, dp(2)));
        return card;
    }

    private View onlinePreviewStrip(OnlineWorkEntry work) {
        return onlineImagesPreviewStrip(work.id, work.title, work.images);
    }

    private View onlineImagesPreviewStrip(String workId, String workTitle, List<String> images) {
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

        for (int i = 0; i < images.size(); i++) {
            String imageName = images.get(i);
            ImageView thumbView = new ImageView(this);
            thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbView.setBackground(round(Color.rgb(230, 235, 232), 8));
            thumbView.setClipToOutline(true);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(thumbW, thumbH);
            params.setMargins(0, 0, dp(6), 0);

            String cacheKey = "online:" + workId + ":" + imageName;
            Bitmap cached = THUMBNAIL_CACHE.get(cacheKey);
            if (cached != null && !cached.isRecycled()) {
                thumbView.setImageBitmap(cached);
            } else {
                thumbView.setTag(cacheKey);
                final String finalCacheKey = cacheKey;
                scheduleOnlineThumb(() -> onlineClient.loadThumbnail(workId, imageName, new OnlineGalleryClient.Callback<Bitmap>() {
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
                }));
            }

            final int imgIndex = i;
            thumbView.setOnClickListener(v -> showOnlineImageDialog(workId, workTitle, images, imgIndex));
            strip.addView(thumbView, params);
        }

        scroll.addView(strip);
        return scroll;
    }

    /** 整页渲染开始时重置首发预算：只让最先出现的少量缩略图立刻加载。 */
    private void resetOnlineThumbScheduler() {
        onlineThumbBurst = ONLINE_THUMB_BURST_BUDGET;
    }

    /** 提交一个在线缩略图加载任务：预算内立即执行，超出则入队按节拍渐进放行。 */
    private void scheduleOnlineThumb(Runnable task) {
        if (onlineThumbBurst > 0) {
            onlineThumbBurst--;
            task.run();
            return;
        }
        onlineThumbPending.add(task);
        if (!onlineThumbDraining) {
            onlineThumbDraining = true;
            uiHandler.postDelayed(this::drainOnlineThumbs, ONLINE_THUMB_DRAIN_INTERVAL_MS);
        }
    }

    /** 每拍放行少量任务，既不压死线程池，也能在不滚动时把整页图片补完。 */
    private void drainOnlineThumbs() {
        int n = 0;
        while (n < ONLINE_THUMB_DRAIN_STEP && !onlineThumbPending.isEmpty()) {
            Runnable task = onlineThumbPending.poll();
            if (task == null) break;
            task.run();
            n++;
        }
        if (!onlineThumbPending.isEmpty()) {
            uiHandler.postDelayed(this::drainOnlineThumbs, ONLINE_THUMB_DRAIN_INTERVAL_MS);
        } else {
            onlineThumbDraining = false;
        }
    }

    private void showOnlineImageDialog(OnlineWorkEntry work, int imageIndex) {
        showOnlineImageDialog(work.id, work.title, work.images, imageIndex);
    }

    private void showOnlineImageDialog(String workId, String workTitle, List<String> images, int imageIndex) {
        if (images.isEmpty() || imageIndex < 0 || imageIndex >= images.size()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("", 14, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        headerRow.addView(title, titleParams);

        TextView badge = text("", 11, false);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        headerRow.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        layout.addView(headerRow, margins(0, 0, 0, dp(8)));

        ImageView fullView = new ImageView(this);
        fullView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullView.setAdjustViewBounds(true);
        fullView.setBackground(round(Color.rgb(20, 20, 20), 12));

        int maxImgHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.68f);
        fullView.setMaxHeight(maxImgHeight);

        ProgressBar spinner = new ProgressBar(this);
        layout.addView(spinner, new LinearLayout.LayoutParams(dp(36), dp(36)));
        layout.addView(fullView, new LinearLayout.LayoutParams(-1, -2));

        // 左右切图控制条：支持点击按钮或直接左右滑动整张图
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER);
        Button prevBtn = smallButton("‹ 上一张", false);
        Button nextBtn = smallButton("下一张 ›", true);
        navRow.addView(prevBtn, new LinearLayout.LayoutParams(-2, dp(38)));
        navRow.addView(nextBtn, new LinearLayout.LayoutParams(-2, dp(38)));
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, -2);
        navParams.setMargins(0, dp(10), 0, 0);
        layout.addView(navRow, navParams);

        TextView navHint = text("提示：左右滑动图片即可切换上一张 / 下一张", 11, false);
        navHint.setTextColor(Color.rgb(120, 125, 122));
        navHint.setGravity(Gravity.CENTER);
        layout.addView(navHint, margins(0, dp(6), 0, 0));

        builder.setView(layout);
        builder.setPositiveButton("关闭", null);
        AlertDialog dialog = builder.create();
        dialog.show();

        final int[] currentIndex = new int[]{imageIndex};

        final Runnable[] renderHolder = new Runnable[1];
        renderHolder[0] = () -> {
            String imageName = images.get(currentIndex[0]);
            title.setText(workTitle + " (" + (currentIndex[0] + 1) + "/" + images.size() + ")");
            boolean isFullCached = onlineClient.hasFullImageCached(workId, imageName);
            badge.setText(isFullCached ? "✅ 100% 原画" : "⏳ 拉取原画中…");
            badge.setTextColor(isFullCached ? Color.rgb(15, 135, 88) : Color.rgb(180, 120, 20));
            badge.setBackground(round(isFullCached ? Color.rgb(235, 247, 240) : Color.rgb(255, 246, 230), 8));
            if (!isFullCached) spinner.setVisibility(View.VISIBLE);

            prevBtn.setEnabled(currentIndex[0] > 0);
            prevBtn.setAlpha(currentIndex[0] > 0 ? 1f : 0.4f);
            nextBtn.setEnabled(currentIndex[0] < images.size() - 1);
            nextBtn.setAlpha(currentIndex[0] < images.size() - 1 ? 1f : 0.4f);

            // 1. 先展示缩略图占位（0 秒白屏）
            String cacheKeyThumb = "online:" + workId + ":" + imageName;
            Bitmap cachedThumb = THUMBNAIL_CACHE.get(cacheKeyThumb);
            fullView.setImageBitmap(cachedThumb != null && !cachedThumb.isRecycled() ? cachedThumb : null);

            // 2. 高清原画内存缓存
            String cacheKeyFull = "online:full:" + workId + ":" + imageName;
            Bitmap cachedFull = THUMBNAIL_CACHE.get(cacheKeyFull);
            if (cachedFull != null && !cachedFull.isRecycled()) {
                fullView.setImageBitmap(cachedFull);
                spinner.setVisibility(View.GONE);
                badge.setText("✅ 100% 原画");
                badge.setTextColor(Color.rgb(15, 135, 88));
                badge.setBackground(round(Color.rgb(235, 247, 240), 8));
                return;
            }

            // 3. 异步拉取 100% 原始画质（本地磁盘秒开，无本地磁盘则向电脑拉取）
            onlineClient.loadFullImage(workId, imageName, new OnlineGalleryClient.Callback<Bitmap>() {
                @Override
                public void onSuccess(Bitmap result) {
                    if (currentIndex[0] >= images.size() || !images.get(currentIndex[0]).equals(imageName)) return;
                    spinner.setVisibility(View.GONE);
                    if (result != null && !result.isRecycled()) {
                        THUMBNAIL_CACHE.put(cacheKeyFull, result);
                        fullView.setImageBitmap(result);
                        fullView.requestLayout();
                        fullView.invalidate();
                        badge.setText("✅ 100% 原画");
                        badge.setTextColor(Color.rgb(15, 135, 88));
                        badge.setBackground(round(Color.rgb(235, 247, 240), 8));
                    }
                }

                @Override
                public void onError(Exception error) {
                    spinner.setVisibility(View.GONE);
                    badge.setText("缩略图预览 (点此重拉)");
                    badge.setTextColor(Color.GRAY);
                    badge.setBackground(round(Color.rgb(240, 240, 240), 8));
                }
            });

            // 点击 badge 支持随时手动重新拉取刷新
            badge.setOnClickListener(v -> {
                badge.setText("⏳ 重新拉取中…");
                badge.setTextColor(Color.rgb(180, 120, 20));
                badge.setBackground(round(Color.rgb(255, 246, 230), 8));
                spinner.setVisibility(View.VISIBLE);
                THUMBNAIL_CACHE.remove(cacheKeyFull);
                java.io.File cf = new java.io.File(new java.io.File(getCacheDir(), "online_full_images"), OnlineGalleryClient.getDiskCacheKey(workId, imageName));
                if (cf.exists()) cf.delete();
                onlineClient.loadFullImage(workId, imageName, new OnlineGalleryClient.Callback<Bitmap>() {
                    @Override
                    public void onSuccess(Bitmap result) {
                        if (currentIndex[0] >= images.size() || !images.get(currentIndex[0]).equals(imageName)) return;
                        spinner.setVisibility(View.GONE);
                        if (result != null && !result.isRecycled()) {
                            THUMBNAIL_CACHE.put(cacheKeyFull, result);
                            fullView.setImageBitmap(result);
                            badge.setText("✅ 100% 原画");
                            badge.setTextColor(Color.rgb(15, 135, 88));
                            badge.setBackground(round(Color.rgb(235, 247, 240), 8));
                        }
                    }

                    @Override
                    public void onError(Exception error) {
                        spinner.setVisibility(View.GONE);
                        badge.setText("拉取失败 (点此重试)");
                    }
                });
            });
        };

        Runnable stepPrev = () -> {
            if (currentIndex[0] > 0) {
                currentIndex[0]--;
                renderHolder[0].run();
            }
        };
        Runnable stepNext = () -> {
            if (currentIndex[0] < images.size() - 1) {
                currentIndex[0]++;
                renderHolder[0].run();
            }
        };

        prevBtn.setOnClickListener(v -> stepPrev.run());
        nextBtn.setOnClickListener(v -> stepNext.run());

        // 图片上左右滑动切图（右滑看下一张，与手机相册习惯一致）
        final float[] downX = new float[]{0f};
        final float[] downY = new float[]{0f};
        fullView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX[0] = event.getX();
                    downY[0] = event.getY();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    float dx = event.getX() - downX[0];
                    float dy = event.getY() - downY[0];
                    float threshold = dp(45);
                    if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy)) {
                        if (dx < 0) stepNext.run();   // 左滑 → 下一张
                        else stepPrev.run();          // 右滑 → 上一张
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });

        renderHolder[0].run();
    }

    private void handleOnlineWorkUse(OnlineWorkEntry work, String platformCode, String label, String copyText) {
        // 深度防御：即便被绕过，也绝不复制空壳作品的合成文案
        if (isCopySubstanceMissing(copyText)) {
            toast("⚠️ 该作品文案缺失（空壳作品），已阻止分发");
            return;
        }
        copyToClipboard(label, copyText);

        if (work.images == null || work.images.isEmpty()) {
            toast("已复制 " + label + "（无图片作品）");
            return;
        }

        // 创建并显示动态下载进度弹窗
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);

        LinearLayout dlLayout = new LinearLayout(this);
        dlLayout.setOrientation(LinearLayout.VERTICAL);
        dlLayout.setPadding(dp(20), dp(20), dp(20), dp(18));

        TextView dlTitle = text("正在从电脑同步原图到手机…", 15, true);
        dlTitle.setTextColor(Color.rgb(24, 25, 24));
        dlLayout.addView(dlTitle);

        TextView dlStatus = text("准备连接电脑拉取 " + work.images.size() + " 张原图…", 12, false);
        dlStatus.setTextColor(Color.rgb(104, 108, 106));
        dlLayout.addView(dlStatus, margins(0, dp(6), 0, dp(12)));

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(false);
        pb.setMax(work.images.size());
        pb.setProgress(0);
        dlLayout.addView(pb, new LinearLayout.LayoutParams(-1, dp(8)));

        TextView dlCounter = text("0 / " + work.images.size() + " 张", 12, true);
        dlCounter.setTextColor(Color.rgb(15, 135, 88));
        dlCounter.setGravity(Gravity.END);
        dlLayout.addView(dlCounter, margins(0, dp(6), 0, 0));

        builder.setView(dlLayout);
        builder.setNegativeButton("取消", (d, w) -> d.dismiss());
        AlertDialog dlDialog = builder.create();
        dlDialog.show();

        onlineClient.downloadWorkImages(work.id, work.images, new OnlineGalleryClient.DownloadProgressCallback() {
            @Override
            public void onProgress(int downloaded, int total, String currentFileName) {
                if (dlDialog.isShowing()) {
                    pb.setMax(total);
                    pb.setProgress(downloaded);
                    dlCounter.setText(downloaded + " / " + total + " 张");
                    if (currentFileName != null && !currentFileName.isEmpty()) {
                        dlStatus.setText("正在下载: " + currentFileName);
                    }
                }
            }

            @Override
            public void onSuccess(List<java.io.File> files) {
                if (dlDialog.isShowing()) {
                    try { dlDialog.dismiss(); } catch (Exception ignored) {}
                }
                toast("✅ 原图已全部同步到手机，正在唤起分享…");
                launchOnlineShare(work, files, copyText, platformCode);
            }

            @Override
            public void onError(Exception error) {
                if (dlDialog.isShowing()) {
                    try { dlDialog.dismiss(); } catch (Exception ignored) {}
                }
                toast("下载图片失败: " + (error != null ? error.getMessage() : "网络超时") + "，文案已在剪贴板");
            }
        });

        // 手机端本地生命周期记录（启动遵循手机端设置的时间规则倒计时）
        OnlineWorkLifecycle.markUsed(this, work, System.currentTimeMillis());

        onlineClient.recordUse(work.id, getDeviceName(), platformCode, new OnlineGalleryClient.Callback<OnlineGalleryClient.UseResult>() {
            @Override
            public void onSuccess(OnlineGalleryClient.UseResult result) {
                if (result != null && result.ok) {
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
            // 将从电脑同步的原图，通过 GalleryShareBridge 发布到系统媒体库
            // 既彻底解决微信分身/小红书无法读取私有文件的问题，又自动享有 1 小时 TTL 自动清理生命周期治理！
            for (java.io.File f : files) {
                try {
                    Uri pubUri = GalleryShareBridge.publish(this, f, f.getName());
                    if (pubUri != null) {
                        uris.add(pubUri);
                    }
                } catch (Exception e) {
                    Uri privUri = Uri.parse("content://" + getPackageName() + ".files/online/" + work.id + "/" + f.getName());
                    uris.add(privUri);
                }
            }
            if (uris.isEmpty()) return;
            GalleryShareBridge.remember(this, uris, System.currentTimeMillis());

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

    /**
     * 「复制路径」：复制该作品文件夹的绝对路径。
     * 本地模式复制手机上的文件夹；在线模式复制电脑成品库里的文件夹 —— 每个作品独一无二。
     */
    private void copyWorkFolderPath(String path, String originLabel) {
        String trimmed = path == null ? "" : path.trim();
        if (trimmed.isEmpty()) {
            toast("⚠️ 该作品没有可复制的文件夹路径");
            return;
        }
        copyToClipboard("作品文件夹路径", trimmed);
        toast("📋 已复制" + originLabel + "文件夹路径");
    }

    /** 统一的「复制路径」按钮（放在「删除」按钮之后）。 */
    private Button copyPathButton(String path, String originLabel) {
        Button copy = new Button(this);
        copy.setText("复制路径");
        styleNeumorphicButton(copy, STYLE_MUTED_GRAY);
        copy.setContentDescription("复制" + originLabel + "文件夹路径");
        copy.setOnClickListener(v -> copyWorkFolderPath(path, originLabel));
        return copy;
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
        AlertDialog protectedInputDialog = new AlertDialog.Builder(this)
                .setTitle("设置电脑在线相册 IP")
                .setMessage("请输入运行 online_gallery_service.py 的电脑局域网 IP 地址：")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并连接", (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) {
                        String url = "http://" + ip + ":" + OnlineGalleryClient.DEFAULT_PC_PORT;
                        // 走「手工指定」通道：明确表达意图，不会被自动信标悄悄改掉
                        onlineClient.setManualBaseUrl(url);
                        toast("已设置电脑地址: " + url);
                        refreshOnlineWorks(true);
                    }
                })
                .create();
            // 带输入框的弹窗：禁止点击背景关闭 —— 误触一次就把打好的字全丢了（BUG_LEDGER DSH-084）。
            protectedInputDialog.setCanceledOnTouchOutside(false);
            protectedInputDialog.show();
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
