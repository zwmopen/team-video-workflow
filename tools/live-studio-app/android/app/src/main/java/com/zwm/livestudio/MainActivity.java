package com.zwm.livestudio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String PREFS = "live_studio_prefs";
    private static final String KEY_MODE = "motion_mode";
    private static final String KEY_RATIO = "motion_ratio";
    private static final String KEY_DURATION = "motion_duration";
    private static final String KEY_SKIP_EXISTING_LIVE = "skip_existing_live";
    private static final String KEY_QUALITY_TIER = "quality_tier";
    private static final int REQ_MEDIA_PERMS = 101;
    private static final int REQ_DELETE_MEDIA = 103;
    private static final int MAX_SELECT = 18;

    private static final class GalleryPhoto {
        final long id;
        final Uri uri;
        final String bucket;
        final boolean looksLive;

        GalleryPhoto(long id, Uri uri, String bucket, boolean looksLive) {
            this.id = id;
            this.uri = uri;
            this.bucket = bucket;
            this.looksLive = looksLive;
        }
    }

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<Long, Bitmap> thumbCache = new LruCache<Long, Bitmap>(20 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private final List<GalleryPhoto> allPhotos = new ArrayList<>();
    private final List<GalleryPhoto> visiblePhotos = new ArrayList<>();
    private final List<Uri> selectedUris = new ArrayList<>();
    private String activeBucket = "";
    private boolean selectionMode = false;
    private volatile boolean isConverting = false;

    // Swipe / drag-to-multiselect state
    private boolean isDragSelecting = false;
    private boolean dragSelectTargetState = true;
    private int dragStartPos = -1;
    private int dragLastPos = -1;
    private final List<Uri> dragBaselineSelected = new ArrayList<>();
    private float dragDownX = 0f;
    private float dragDownY = 0f;
    private float dragLastX = 0f;
    private float dragLastY = 0f;
    private int dragDownPos = AdapterView.INVALID_POSITION;
    private boolean horizontalDragArmed = false;
    private boolean dragAutoScrollActive = false;
    private boolean sentCancelToGrid = false;
    private int touchSlop;

    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isDragSelecting || !dragAutoScrollActive || gridView == null) {
                dragAutoScrollActive = false;
                return;
            }
            int h = gridView.getHeight();
            int edge = dp(56);
            int delta = 0;
            if (dragLastY < edge && edge > 0) {
                float factor = Math.min(1f, (edge - dragLastY) / edge);
                delta = -Math.max(dp(4), Math.round(dp(18) * factor));
            } else if (dragLastY > h - edge && edge > 0) {
                float factor = Math.min(1f, (dragLastY - (h - edge)) / edge);
                delta = Math.max(dp(4), Math.round(dp(18) * factor));
            }
            if (delta == 0) {
                dragAutoScrollActive = false;
                return;
            }
            gridView.scrollListBy(delta);
            updateDragSelectionAt(dragLastX, dragLastY);
            mainHandler.postDelayed(this, 16);
        }
    };

    private boolean isDark;
    private TextView titleView;
    private TextView albumChip;
    private TextView rightActionBtn;
    private TextView liveModeChip;
    private com.zwm.livecore.LiveQuickMenu liveQuickMenu;
    private LinearLayout permissionCard;
    private TextView permissionTitle;
    private GridView gridView;
    private PhotoGridAdapter adapter;
    private LinearLayout bottomBar;
    private Button deleteBtn;
    private Button saveOnlyBtn;
    private Button shareBtn;

    private AlertDialog activePreviewDialog;
    private Runnable pendingDeleteCallback;
    private int pendingDeleteCount = 0;
    private Object backInvokedCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        isDark = night == Configuration.UI_MODE_NIGHT_YES;
        applySystemBars();
        setContentView(buildUi());
        updateChrome();
        loadGalleryIfPermitted();
        handleIncomingShareIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isConverting && hasFullOrPartialGalleryPermission()) {
            loadGalleryIfPermitted();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShareIntent(intent);
    }

    @Override
    @SuppressWarnings("deprecation")
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        if (liveQuickMenu != null && liveQuickMenu.close()) return;
        if (selectionMode) {
            exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    private void syncBackGestureCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (selectionMode) {
            if (backInvokedCallback == null) {
                android.window.OnBackInvokedCallback cb = this::exitSelectionMode;
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
                backInvokedCallback = cb;
            }
        } else if (backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backInvokedCallback);
            backInvokedCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (liveQuickMenu != null) liveQuickMenu.close();
        mainHandler.removeCallbacks(autoScrollRunnable);
        ioExecutor.shutdownNow();
        thumbExecutor.shutdownNow();
        super.onDestroy();
    }

    private void applySystemBars() {
        int bg = isDark ? Color.rgb(14, 16, 20) : Color.rgb(246, 248, 250);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!isDark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bgPrimary());

        // Top Bar: clean native gallery header
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(10), dp(14), dp(10));

        titleView = label("实况工坊", 22, true, textPrimary());
        titleView.setOnClickListener(v -> {
            if (selectionMode) exitSelectionMode();
        });
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.setMargins(0, 0, dp(10), 0);
        topBar.addView(titleView, titleLp);

        albumChip = chipButton("全部照片 ▾");
        albumChip.setOnClickListener(v -> showBucketDialog());
        topBar.addView(albumChip, new LinearLayout.LayoutParams(-2, -2));

        topBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        liveModeChip = chipButton("LIVE ▾");
        liveModeChip.setContentDescription("实况效果，点击或长按滑动选择");
        liveQuickMenu = new com.zwm.livecore.LiveQuickMenu(this);
        liveModeChip.setOnClickListener(v -> showEffectPickerDialog());
        liveQuickMenu.attach(liveModeChip, this::showEffectPickerDialog);
        LinearLayout.LayoutParams liveChipLp = new LinearLayout.LayoutParams(-2, -2);
        liveChipLp.setMargins(dp(4), 0, dp(4), 0);
        topBar.addView(liveModeChip, liveChipLp);

        rightActionBtn = chipButton("设置");
        rightActionBtn.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelectAll();
            } else {
                showSettingsDialog();
            }
        });
        topBar.addView(rightActionBtn, new LinearLayout.LayoutParams(-2, -2));
        root.addView(topBar);

        // Permission Card (hidden once granted)
        permissionCard = new LinearLayout(this);
        permissionCard.setOrientation(LinearLayout.VERTICAL);
        permissionCard.setPadding(dp(18), dp(22), dp(18), dp(22));
        permissionCard.setBackground(roundRect(cardBg(), 16));
        permissionTitle = label("授权读取手机相册", 16, true, textPrimary());
        TextView permDesc = label("像系统相册一样浏览本地照片，点开看大图、长按并滑动连选，离线转为动态实况并分享或删除。", 13, false, textSecondary());
        Button grantBtn = actionBtn("允许读取相册", true);
        grantBtn.setOnClickListener(v -> requestGalleryPermission());

        permissionCard.addView(permissionTitle);
        LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(-1, -2);
        pdLp.setMargins(0, dp(6), 0, dp(14));
        permissionCard.addView(permDesc, pdLp);
        permissionCard.addView(grantBtn, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(-1, -2);
        pcLp.setMargins(dp(16), dp(24), dp(16), dp(8));
        root.addView(permissionCard, pcLp);

        // 3-column Local Photo Grid
        gridView = new GridView(this);
        gridView.setNumColumns(3);
        gridView.setHorizontalSpacing(dp(2));
        gridView.setVerticalSpacing(dp(2));
        gridView.setPadding(dp(2), 0, dp(2), dp(2));
        gridView.setClipToPadding(false);
        adapter = new PhotoGridAdapter();
        gridView.setAdapter(adapter);
        setupGridInteractions();
        root.addView(gridView, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Sticky Bottom Action Bar (shown ONLY during multi-select mode)
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(cardBg());
        bottomBar.setPadding(dp(12), dp(10), dp(12), dp(12));
        bottomBar.setVisibility(View.GONE);

        deleteBtn = actionBtn("删除", false);
        deleteBtn.setTextColor(Color.rgb(220, 38, 38));
        deleteBtn.setOnClickListener(v -> requestDeletePhotos(new ArrayList<>(selectedUris), this::exitSelectionMode));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, dp(46), 0.9f);
        dLp.setMargins(0, 0, dp(8), 0);
        bottomBar.addView(deleteBtn, dLp);

        saveOnlyBtn = actionBtn("存实况", false);
        saveOnlyBtn.setOnClickListener(v -> startBatchConvert(new ArrayList<>(selectedUris), false));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(46), 1.0f);
        sLp.setMargins(0, 0, dp(8), 0);
        bottomBar.addView(saveOnlyBtn, sLp);

        shareBtn = actionBtn("分享实况", true);
        shareBtn.setOnClickListener(v -> startBatchConvert(new ArrayList<>(selectedUris), true));
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(0, dp(46), 1.4f);
        bottomBar.addView(shareBtn, shLp);

        root.addView(bottomBar);
        return root;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupGridInteractions() {
        gridView.setOnItemClickListener((parent, view, position, id) -> {
            if (isDragSelecting) return;
            if (position < 0 || position >= visiblePhotos.size()) return;
            GalleryPhoto photo = visiblePhotos.get(position);
            if (selectionMode) {
                toggleSelection(photo.uri);
            } else {
                showPhotoPreviewDialog(position);
            }
        });
        gridView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visiblePhotos.size()) return false;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            boolean alreadySel = selectedUris.contains(visiblePhotos.get(position).uri);
            startDragSelect(position, !alreadySel);
            return true;
        });
        gridView.setOnTouchListener((v, e) -> handleGridTouch(e));
    }

    private boolean handleGridTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragDownX = dragLastX = e.getX();
                dragDownY = dragLastY = e.getY();
                dragDownPos = gridView.pointToPosition((int) dragDownX, (int) dragDownY);
                horizontalDragArmed = selectionMode
                        && dragDownPos != AdapterView.INVALID_POSITION
                        && dragDownPos < visiblePhotos.size();
                sentCancelToGrid = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                dragLastX = e.getX();
                dragLastY = e.getY();
                if (!isDragSelecting && selectionMode && horizontalDragArmed) {
                    float dx = Math.abs(dragLastX - dragDownX);
                    float dy = Math.abs(dragLastY - dragDownY);
                    if (Math.max(dx, dy) > touchSlop) {
                        horizontalDragArmed = false;
                        if (dx > dy * 1.15f && dragDownPos >= 0 && dragDownPos < visiblePhotos.size()) {
                            gridView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            boolean alreadySel = selectedUris.contains(visiblePhotos.get(dragDownPos).uri);
                            startDragSelect(dragDownPos, !alreadySel);
                            cancelPendingGridTouch(e);
                            updateDragSelectionAt(dragLastX, dragLastY);
                            return true;
                        }
                    }
                }
                if (isDragSelecting) {
                    cancelPendingGridTouch(e);
                    updateDragSelectionAt(dragLastX, dragLastY);
                    int h = gridView.getHeight();
                    int edge = dp(56);
                    boolean nearEdge = h > edge * 2 && (dragLastY < edge || dragLastY > h - edge);
                    if (nearEdge && !dragAutoScrollActive) {
                        dragAutoScrollActive = true;
                        mainHandler.post(autoScrollRunnable);
                    } else if (!nearEdge && dragAutoScrollActive) {
                        dragAutoScrollActive = false;
                        mainHandler.removeCallbacks(autoScrollRunnable);
                    }
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                horizontalDragArmed = false;
                if (isDragSelecting) {
                    cancelPendingGridTouch(e);
                    stopDragSelect();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void cancelPendingGridTouch(MotionEvent src) {
        if (sentCancelToGrid || gridView == null) return;
        sentCancelToGrid = true;
        MotionEvent cancel = MotionEvent.obtain(src);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        gridView.onTouchEvent(cancel);
        cancel.recycle();
    }

    private void startDragSelect(int startPos, boolean targetSelect) {
        if (startPos < 0 || startPos >= visiblePhotos.size()) return;
        selectionMode = true;
        isDragSelecting = true;
        dragSelectTargetState = targetSelect;
        dragStartPos = startPos;
        dragLastPos = -1;
        dragBaselineSelected.clear();
        dragBaselineSelected.addAll(selectedUris);
        gridView.requestDisallowInterceptTouchEvent(true);
        applyDragRange(startPos);
        updateChrome();
    }

    private void applyDragRange(int endPos) {
        if (endPos < 0 || endPos >= visiblePhotos.size() || endPos == dragLastPos) return;
        dragLastPos = endPos;
        selectedUris.clear();
        selectedUris.addAll(dragBaselineSelected);
        if (dragSelectTargetState) {
            int step = endPos >= dragStartPos ? 1 : -1;
            for (int i = dragStartPos; ; i += step) {
                Uri uri = visiblePhotos.get(i).uri;
                if (!selectedUris.contains(uri)) {
                    if (selectedUris.size() >= MAX_SELECT) break;
                    selectedUris.add(uri);
                }
                if (i == endPos) break;
            }
        } else {
            int lo = Math.min(dragStartPos, endPos);
            int hi = Math.max(dragStartPos, endPos);
            for (int i = lo; i <= hi; i++) {
                selectedUris.remove(visiblePhotos.get(i).uri);
            }
        }
        updateChrome();
        refreshVisibleCellSelections();
    }

    private void updateDragSelectionAt(float x, float y) {
        if (gridView == null || visiblePhotos.isEmpty()) return;
        int w = gridView.getWidth();
        int h = gridView.getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = Math.max(dp(4), Math.min(x, Math.max(dp(4), w - dp(4))));
        float cy = Math.max(dp(4), Math.min(y, Math.max(dp(4), h - dp(4))));
        int pos = gridView.pointToPosition((int) cx, (int) cy);
        if (pos == AdapterView.INVALID_POSITION) {
            int probe = dp(4);
            int[][] offsets = {{-probe, 0}, {probe, 0}, {0, -probe}, {0, probe}};
            for (int[] off : offsets) {
                pos = gridView.pointToPosition((int) cx + off[0], (int) cy + off[1]);
                if (pos != AdapterView.INVALID_POSITION) break;
            }
        }
        if (pos != AdapterView.INVALID_POSITION && pos < visiblePhotos.size()) {
            applyDragRange(pos);
        }
    }

    private void refreshVisibleCellSelections() {
        if (gridView == null) return;
        int first = gridView.getFirstVisiblePosition();
        int count = gridView.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = gridView.getChildAt(i);
            Object tag = child.getTag();
            int pos = first + i;
            if (tag instanceof ViewHolder && pos >= 0 && pos < visiblePhotos.size()) {
                bindSelectionVisual((ViewHolder) tag, visiblePhotos.get(pos).uri);
            }
        }
    }

    private void stopDragSelect() {
        isDragSelecting = false;
        dragAutoScrollActive = false;
        horizontalDragArmed = false;
        sentCancelToGrid = false;
        dragBaselineSelected.clear();
        mainHandler.removeCallbacks(autoScrollRunnable);
        if (gridView != null) {
            gridView.requestDisallowInterceptTouchEvent(false);
        }
        if (selectedUris.isEmpty()) {
            exitSelectionMode();
        } else {
            updateChrome();
            adapter.notifyDataSetChanged();
        }
    }

    private void enterSelectionWith(Uri firstUri) {
        selectionMode = true;
        if (firstUri != null && !selectedUris.contains(firstUri)) {
            if (selectedUris.size() < MAX_SELECT) {
                selectedUris.add(firstUri);
            }
        }
        updateChrome();
        adapter.notifyDataSetChanged();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedUris.clear();
        updateChrome();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelection(Uri uri) {
        if (uri == null) return;
        if (selectedUris.contains(uri)) {
            selectedUris.remove(uri);
            if (selectedUris.isEmpty()) {
                selectionMode = false;
            }
        } else {
            if (selectedUris.size() >= MAX_SELECT) {
                toast("单次最多选择 " + MAX_SELECT + " 张");
                return;
            }
            selectionMode = true;
            selectedUris.add(uri);
        }
        updateChrome();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelectAll() {
        int cap = Math.min(MAX_SELECT, visiblePhotos.size());
        if (selectedUris.size() >= cap && cap > 0) {
            selectedUris.clear();
        } else {
            selectedUris.clear();
            for (int i = 0; i < cap; i++) {
                selectedUris.add(visiblePhotos.get(i).uri);
            }
            if (visiblePhotos.size() > MAX_SELECT) {
                toast("单次最多全选前 " + MAX_SELECT + " 张");
            }
        }
        updateChrome();
        adapter.notifyDataSetChanged();
    }

    private void updateChrome() {
        syncBackGestureCallback();
        if (selectionMode) {
            int count = selectedUris.size();
            titleView.setText("取消");
            titleView.setTextSize(16);
            titleView.setTextColor(brandGreen());
            albumChip.setVisibility(View.VISIBLE);
            albumChip.setBackground(null);
            albumChip.setText("已选 " + count + " 张");
            int cap = Math.min(MAX_SELECT, visiblePhotos.size());
            rightActionBtn.setText(count >= cap && cap > 0 ? "取消全选" : "全选");

            bottomBar.setVisibility(View.VISIBLE);
            boolean hasAny = count > 0;
            deleteBtn.setEnabled(hasAny);
            saveOnlyBtn.setEnabled(hasAny);
            shareBtn.setEnabled(hasAny);
            float alpha = hasAny ? 1f : 0.45f;
            deleteBtn.setAlpha(alpha);
            saveOnlyBtn.setAlpha(alpha);
            shareBtn.setAlpha(alpha);
            shareBtn.setText(hasAny ? "分享实况 (" + count + ")" : "分享实况");
        } else {
            titleView.setText("实况工坊");
            titleView.setTextSize(22);
            titleView.setTextColor(textPrimary());
            albumChip.setVisibility(View.VISIBLE);
            albumChip.setBackground(roundRect(cardBg(), 18));
            albumChip.setText((activeBucket.isEmpty() ? "全部照片" : activeBucket) + " ▾");
            rightActionBtn.setText("设置");
            bottomBar.setVisibility(View.GONE);
        }
    }

    private boolean hasFullOrPartialGalleryPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            boolean full = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean partial = checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
            return full || partial;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestGalleryPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }, REQ_MEDIA_PERMS);
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQ_MEDIA_PERMS);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_MEDIA_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MEDIA_PERMS) {
            loadGalleryIfPermitted();
        }
    }

    private void loadGalleryIfPermitted() {
        if (!hasFullOrPartialGalleryPermission()) {
            permissionCard.setVisibility(View.VISIBLE);
            gridView.setVisibility(View.GONE);
            return;
        }
        permissionCard.setVisibility(View.GONE);
        gridView.setVisibility(View.VISIBLE);

        ioExecutor.execute(() -> {
            List<GalleryPhoto> loaded = queryGalleryPhotos();
            mainHandler.post(() -> {
                allPhotos.clear();
                allPhotos.addAll(loaded);
                applyBucketFilter();
            });
        });
    }

    private List<GalleryPhoto> queryGalleryPhotos() {
        List<GalleryPhoto> list = new ArrayList<>();
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DISPLAY_NAME
        };
        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " DESC";
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
        )) {
            if (cursor == null) return list;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            int limit = 800;
            while (cursor.moveToNext() && list.size() < limit) {
                long id = cursor.getLong(idCol);
                String bucket = bucketCol >= 0 ? cursor.getString(bucketCol) : "";
                if (bucket == null || bucket.trim().isEmpty()) bucket = "其他";
                String disp = nameCol >= 0 ? cursor.getString(nameCol) : "";
                if (disp == null) disp = "";
                boolean liveHint = disp.startsWith("MVIMG_") || disp.startsWith("LIVE_") || "实况工坊".equals(bucket);
                Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                list.add(new GalleryPhoto(id, contentUri, bucket, liveHint));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private void applyBucketFilter() {
        visiblePhotos.clear();
        for (GalleryPhoto p : allPhotos) {
            if (activeBucket.isEmpty() || activeBucket.equals(p.bucket)) {
                visiblePhotos.add(p);
            }
        }
        updateChrome();
        adapter.notifyDataSetChanged();
    }

    private void showBucketDialog() {
        if (selectionMode) return;
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("", allPhotos.size());
        for (GalleryPhoto p : allPhotos) {
            Integer c = counts.get(p.bucket);
            counts.put(p.bucket, c == null ? 1 : c + 1);
        }
        List<String> keys = new ArrayList<>(counts.keySet());
        String[] labels = new String[keys.size()];
        int checked = 0;
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (k.equals(activeBucket)) checked = i;
            labels[i] = (k.isEmpty() ? "全部照片" : k) + "（" + counts.get(k) + "）";
        }
        new AlertDialog.Builder(this)
                .setTitle("选择相册")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    activeBucket = keys.get(which);
                    applyBucketFilter();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPhotoPreviewDialog(int initialPos) {
        if (visiblePhotos.isEmpty()) return;
        final int[] pos = {Math.max(0, Math.min(initialPos, visiblePhotos.size() - 1))};

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.BLACK);

        // Viewer top bar
        LinearLayout vTop = new LinearLayout(this);
        vTop.setOrientation(LinearLayout.HORIZONTAL);
        vTop.setGravity(Gravity.CENTER_VERTICAL);
        vTop.setPadding(dp(14), dp(12), dp(14), dp(10));

        TextView counterView = label("", 14, true, Color.WHITE);
        vTop.addView(counterView, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView closeBtn = label("✕", 18, true, Color.WHITE);
        closeBtn.setPadding(dp(12), dp(4), dp(4), dp(4));
        vTop.addView(closeBtn);
        container.addView(vTop);

        // Large image area + floating prev/next controls
        FrameLayout stage = new FrameLayout(this);
        ImageView bigImage = new ImageView(this);
        bigImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(bigImage, new FrameLayout.LayoutParams(-1, -1));

        TextView prevBtn = navArrow("‹", Gravity.START | Gravity.CENTER_VERTICAL);
        TextView nextBtn = navArrow("›", Gravity.END | Gravity.CENTER_VERTICAL);
        stage.addView(prevBtn);
        stage.addView(nextBtn);
        container.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Viewer bottom bar: 删除 | 存实况 | 分享实况
        LinearLayout vBottom = new LinearLayout(this);
        vBottom.setOrientation(LinearLayout.HORIZONTAL);
        vBottom.setBackgroundColor(Color.rgb(20, 22, 26));
        vBottom.setPadding(dp(12), dp(10), dp(12), dp(14));

        Button vDelete = actionBtn("删除", false);
        vDelete.setBackground(roundRect(Color.rgb(42, 46, 54), 10));
        vDelete.setTextColor(Color.rgb(248, 113, 113));
        LinearLayout.LayoutParams vdLp = new LinearLayout.LayoutParams(0, dp(44), 0.9f);
        vdLp.setMargins(0, 0, dp(8), 0);
        vBottom.addView(vDelete, vdLp);

        Button vSave = actionBtn("存实况", false);
        vSave.setBackground(roundRect(Color.rgb(42, 46, 54), 10));
        vSave.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams vsLp = new LinearLayout.LayoutParams(0, dp(44), 1.0f);
        vsLp.setMargins(0, 0, dp(8), 0);
        vBottom.addView(vSave, vsLp);

        Button vShare = actionBtn("分享实况", true);
        LinearLayout.LayoutParams vshLp = new LinearLayout.LayoutParams(0, dp(44), 1.4f);
        vBottom.addView(vShare, vshLp);

        container.addView(vBottom);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                .setView(container)
                .create();
        activePreviewDialog = dialog;

        Runnable renderCurrent = () -> {
            if (visiblePhotos.isEmpty()) {
                dialog.dismiss();
                return;
            }
            if (pos[0] >= visiblePhotos.size()) pos[0] = visiblePhotos.size() - 1;
            GalleryPhoto cur = visiblePhotos.get(pos[0]);
            counterView.setText((pos[0] + 1) + " / " + visiblePhotos.size() + (cur.looksLive ? " · 实况" : ""));
            prevBtn.setVisibility(pos[0] > 0 ? View.VISIBLE : View.INVISIBLE);
            nextBtn.setVisibility(pos[0] < visiblePhotos.size() - 1 ? View.VISIBLE : View.INVISIBLE);
            final Uri targetUri = cur.uri;
            ioExecutor.execute(() -> {
                Bitmap bmp = decodeScaledBitmap(getContentResolver(), targetUri, 1600);
                if (bmp != null) {
                    mainHandler.post(() -> {
                        if (dialog.isShowing() && pos[0] < visiblePhotos.size()
                                && targetUri.equals(visiblePhotos.get(pos[0]).uri)) {
                            bigImage.setImageBitmap(bmp);
                        }
                    });
                }
            });
        };

        closeBtn.setOnClickListener(v -> dialog.dismiss());
        prevBtn.setOnClickListener(v -> {
            if (pos[0] > 0) {
                pos[0]--;
                renderCurrent.run();
            }
        });
        nextBtn.setOnClickListener(v -> {
            if (pos[0] < visiblePhotos.size() - 1) {
                pos[0]++;
                renderCurrent.run();
            }
        });
        bigImage.setOnLongClickListener(v -> {
            if (pos[0] < visiblePhotos.size()) {
                Uri u = visiblePhotos.get(pos[0]).uri;
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                dialog.dismiss();
                enterSelectionWith(u);
                return true;
            }
            return false;
        });

        vSave.setOnClickListener(v -> {
            if (pos[0] < visiblePhotos.size()) {
                List<Uri> single = new ArrayList<>();
                single.add(visiblePhotos.get(pos[0]).uri);
                dialog.dismiss();
                startBatchConvert(single, false);
            }
        });
        vShare.setOnClickListener(v -> {
            if (pos[0] < visiblePhotos.size()) {
                List<Uri> single = new ArrayList<>();
                single.add(visiblePhotos.get(pos[0]).uri);
                dialog.dismiss();
                startBatchConvert(single, true);
            }
        });
        vDelete.setOnClickListener(v -> {
            if (pos[0] < visiblePhotos.size()) {
                List<Uri> single = new ArrayList<>();
                single.add(visiblePhotos.get(pos[0]).uri);
                requestDeletePhotos(single, () -> {
                    if (activePreviewDialog != null && activePreviewDialog.isShowing()) {
                        activePreviewDialog.dismiss();
                    }
                });
            }
        });

        renderCurrent.run();
        dialog.show();
    }

    private TextView navArrow(String symbol, int gravity) {
        TextView tv = label(symbol, 28, true, Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(roundRect(0x55000000, 20));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(40), dp(64), gravity);
        lp.setMargins(dp(8), 0, dp(8), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void requestDeletePhotos(List<Uri> targets, Runnable onDone) {
        if (targets == null || targets.isEmpty()) return;
        pendingDeleteCallback = onDone;
        pendingDeleteCount = targets.size();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), targets);
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE_MEDIA, null, 0, 0, 0);
            } catch (Exception e) {
                pendingDeleteCallback = null;
                toast("无法发起删除：" + e.getMessage());
            }
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("删除确认")
                .setMessage("确定从手机相册删除选中的 " + targets.size() + " 张照片吗？")
                .setNegativeButton("取消", (d, w) -> pendingDeleteCallback = null)
                .setPositiveButton("删除", (d, w) -> {
                    ioExecutor.execute(() -> {
                        int deleted = 0;
                        for (Uri u : targets) {
                            try {
                                deleted += getContentResolver().delete(u, null, null);
                            } catch (Exception ignored) {
                            }
                        }
                        final int finalDeleted = deleted;
                        mainHandler.post(() -> {
                            toast("已删除 " + finalDeleted + " 张照片");
                            Runnable cb = pendingDeleteCallback;
                            pendingDeleteCallback = null;
                            if (cb != null) cb.run();
                            loadGalleryIfPermitted();
                        });
                    });
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE_MEDIA) {
            Runnable cb = pendingDeleteCallback;
            pendingDeleteCallback = null;
            if (resultCode == RESULT_OK) {
                toast("已删除 " + pendingDeleteCount + " 张照片");
                if (cb != null) cb.run();
                loadGalleryIfPermitted();
            }
        }
    }

    private void handleIncomingShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        List<Uri> incoming = extractUrisFromIntent(intent);
        if (!incoming.isEmpty()) {
            intent.setAction("");
            MotionPreset.PresetInfo info = MotionPreset.findById(currentMode());
            new AlertDialog.Builder(this)
                    .setTitle("已收到 " + incoming.size() + " 张图片")
                    .setMessage("当前实况效果：" + info.title + "\n转换后存入系统相册「实况工坊」，可直接发给其他应用。")
                    .setNeutralButton("换效果", (d, w) -> showEffectPickerDialog())
                    .setNegativeButton("仅存实况", (d, w) -> startBatchConvert(incoming, false))
                    .setPositiveButton("分享实况", (d, w) -> startBatchConvert(incoming, true))
                    .show();
        }
    }

    private List<Uri> extractUrisFromIntent(Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount() && uris.size() < MAX_SELECT; i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null && !uris.contains(u)) uris.add(u);
            }
        }
        Uri single = data.getData();
        if (single != null && !uris.contains(single)) uris.add(single);
        if (Build.VERSION.SDK_INT >= 33) {
            ArrayList<Uri> extras = data.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            if (extras != null) {
                for (Uri u : extras) {
                    if (u != null && !uris.contains(u) && uris.size() < MAX_SELECT) uris.add(u);
                }
            }
            Uri one = data.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (one != null && !uris.contains(one)) uris.add(one);
        } else {
            @SuppressWarnings("deprecation")
            ArrayList<Uri> extras = data.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (extras != null) {
                for (Uri u : extras) {
                    if (u != null && !uris.contains(u) && uris.size() < MAX_SELECT) uris.add(u);
                }
            }
            @SuppressWarnings("deprecation")
            Uri one = data.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null && !uris.contains(one)) uris.add(one);
        }
        return uris;
    }

    private void startBatchConvert(List<Uri> inputs, boolean shareAfter) {
        if (inputs == null || inputs.isEmpty()) return;
        isConverting = true;
        final String mode = currentMode();
        final com.zwm.livecore.LiveQualityTier tier = currentQualityTier();
        final float ratio = currentRatio();
        final float duration = currentDuration();
        final boolean skipExisting = isSkipExistingLive();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(18), dp(20), dp(16));
        TextView title = label("正在手机本地生成实况（静态无损 · " + tier.title + "）", 14, true, textPrimary());
        TextView status = label("准备处理第 1 / " + inputs.size() + " 张", 12, false, textSecondary());
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(inputs.size());
        bar.setProgress(0);
        layout.addView(title);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.setMargins(0, dp(6), 0, dp(10));
        layout.addView(status, stLp);
        layout.addView(bar, new LinearLayout.LayoutParams(-1, dp(8)));

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setCancelable(false)
                .setView(layout)
                .create();
        progressDialog.show();

        ioExecutor.execute(() -> {
            List<Uri> outputs = new ArrayList<>();
            List<Uri> failedInputs = new ArrayList<>();
            int losslessCount = 0;
            String firstErrMsg = null;
            com.zwm.livecore.LiveQualityTier effectiveTierSeen = tier;
            for (int i = 0; i < inputs.size(); i++) {
                final int step = i + 1;
                final int doneIdx = i;
                mainHandler.post(() -> {
                    if (progressDialog.isShowing()) {
                        status.setText("正在离线硬件渲染第 " + step + " / " + inputs.size() + " 张…");
                        bar.setProgress(doneIdx);
                    }
                });
                Uri src = inputs.get(i);
                try {
                    com.zwm.livecore.LiveConvertResult res = LivePhotoConverter.convertOrPassthroughDetailed(
                            MainActivity.this, src, mode, tier, ratio, duration, step, skipExisting
                    );
                    outputs.add(res.outputUri);
                    if (res.isLosslessCover()) losslessCount++;
                    if (res.effectiveTier != null) effectiveTierSeen = res.effectiveTier;
                } catch (Exception e) {
                    failedInputs.add(src);
                    if (firstErrMsg == null) firstErrMsg = e.getMessage();
                }
            }
            final int finalLossless = losslessCount;
            final String errText = firstErrMsg;
            final com.zwm.livecore.LiveQualityTier finalTier = effectiveTierSeen;
            mainHandler.post(() -> {
                isConverting = false;
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (!outputs.isEmpty()) {
                    loadGalleryIfPermitted();
                }
                if (!failedInputs.isEmpty()) {
                    String summary = "成功 " + outputs.size() + " 张，失败 " + failedInputs.size() + " 张"
                            + (errText != null ? ("\n原因：" + errText) : "");
                    AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this)
                            .setTitle("部分照片转换失败")
                            .setMessage(summary)
                            .setPositiveButton("重试失败的 " + failedInputs.size() + " 张",
                                    (d, w) -> startBatchConvert(failedInputs, shareAfter))
                            .setNegativeButton("取消", null);
                    if (shareAfter && !outputs.isEmpty()) {
                        b.setNeutralButton("先分享已成功 " + outputs.size() + " 张",
                                (d, w) -> launchSystemShare(outputs));
                    }
                    b.show();
                    return;
                }
                exitSelectionMode();
                if (shareAfter) {
                    launchSystemShare(outputs);
                } else {
                    String tag = finalLossless == outputs.size() ? "静态原画无损" : "高质量保留";
                    String tierMsg = finalTier != null ? finalTier.title : tier.title;
                    toast("已保存 " + outputs.size() + " 张实况（" + tag + " · " + tierMsg + "）");
                }
            });
        });
    }

    private void launchSystemShare(List<Uri> uris) {
        if (uris.isEmpty()) return;
        Intent share;
        if (uris.size() == 1) {
            share = new Intent(Intent.ACTION_SEND);
            share.setType("image/jpeg");
            share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            share = new Intent(Intent.ACTION_SEND_MULTIPLE);
            share.setType("image/jpeg");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        }
        ClipData clip = ClipData.newUri(getContentResolver(), "实况工坊", uris.get(0));
        for (int i = 1; i < uris.size(); i++) {
            clip.addItem(new ClipData.Item(uris.get(i)));
        }
        share.setClipData(clip);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "分享动态实况图"));
    }

    private void showSettingsDialog() {
        MotionPreset.PresetInfo cur = MotionPreset.findById(currentMode());
        com.zwm.livecore.LiveQualityTier curTier = currentQualityTier();
        float pct = (currentRatio() - 1.0f) * 100f;
        String[] rows = {
                "实况动态效果：" + cur.title,
                "动态视频画质：" + curTier.title + "（静态原画无损）",
                String.format(Locale.US, "微动幅度与时长：%.1f%% · %.1f 秒", pct, currentDuration()),
                "跳过已是实况的照片：" + (isSkipExistingLive() ? "已开启" : "已关闭"),
                "清理临时缓存",
                "关于实况工坊 (v1.1.0)"
        };
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(rows, (d, which) -> {
                    if (which == 0) {
                        showEffectPickerDialog();
                    } else if (which == 1) {
                        showQualityTierDialog();
                    } else if (which == 2) {
                        showStrengthDialog();
                    } else if (which == 3) {
                        boolean next = !isSkipExistingLive();
                        prefs().edit().putBoolean(KEY_SKIP_EXISTING_LIVE, next).apply();
                        toast(next ? "已开启：已有实况照片直接直通分享" : "已关闭：统一按当前效果重新生成");
                    } else if (which == 4) {
                        long freedKb = clearScratchCacheKb();
                        toast("已清理临时缓存 " + freedKb + " KB");
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("实况工坊 v1.1.0")
                                .setMessage("· 静态封面直接保留 JPEG 原图数据、分辨率与 EXIF/ICC 色彩，无二次有损压缩\n"
                                        + "· 动态视频独立渲染，支持 流畅 1080P / 高清 1440P / 原画 2048P 三档切换\n"
                                        + "· 离线硬件 GPU + MediaCodec 渲染，长按/横划连选，失败项支持单批重试\n"
                                        + "· 生成结果统一保存在系统相册「Pictures/实况工坊」")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showQualityTierDialog() {
        com.zwm.livecore.LiveQualityTier cur = currentQualityTier();
        com.zwm.livecore.LiveQualityTier[] tiers = com.zwm.livecore.LiveQualityTier.values();
        String[] labels = new String[tiers.length];
        int checked = 0;
        for (int i = 0; i < tiers.length; i++) {
            if (tiers[i] == cur) checked = i;
            labels[i] = tiers[i].title + "\n" + tiers[i].summary;
        }
        new AlertDialog.Builder(this)
                .setTitle("动态视频画质档位（静态封面无损）")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    com.zwm.livecore.LiveQualityTier chosen = tiers[which];
                    prefs().edit().putString(KEY_QUALITY_TIER, chosen.id).apply();
                    toast("动态画质已设为：" + chosen.title + "（静态原画无损）");
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private long clearScratchCacheKb() {
        long bytes = 0;
        File[] files = getCacheDir().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    long len = f.length();
                    if (f.delete()) bytes += len;
                }
            }
        }
        return bytes / 1024;
    }

    private void showEffectPickerDialog() {
        if (liveQuickMenu == null || liveModeChip == null) return;
        String[] labels = new String[MotionPreset.PRESETS.length];
        String curMode = currentMode();
        int checked = 0;
        for (int i=0;i<labels.length;i++) {
            labels[i] = MotionPreset.PRESETS[i].title;
            if (MotionPreset.PRESETS[i].id.equals(curMode)) checked = i;
        }
        liveQuickMenu.show(liveModeChip, labels, checked, which -> {
            String selected = MotionPreset.PRESETS[which].id;
            android.content.SharedPreferences.Editor editor = prefs().edit().putString(KEY_MODE, selected);
            if (!curMode.equals(selected) && ("handheld_push_drift_v1".equals(selected) || "handheld_push_drift_v1".equals(curMode))) {
                editor.putFloat(KEY_RATIO, "handheld_push_drift_v1".equals(selected) ? 1.116f : MotionPreset.DEFAULT_RATIO)
                      .putFloat(KEY_DURATION, "handheld_push_drift_v1".equals(selected) ? 2.0f : MotionPreset.DEFAULT_DURATION_SEC);
            }
            editor.apply();
            toast("已设为：" + MotionPreset.PRESETS[which].title);
        });
    }

    private void showStrengthDialog() {
        boolean push = "handheld_push_drift_v1".equals(currentMode());
        String[] items = push ? new String[] {
                "柔和（5.8% 幅度 · 2.0 秒）",
                "参考原版（11.6% 幅度 · 2.0 秒）",
                "明显（12.0% 幅度 · 2.2 秒）"
        } : new String[] {
                "极微自然（1.0% 幅度 · 1.3 秒）",
                "标准推荐（1.8% 幅度 · 1.6 秒）",
                "轻柔明显（3.2% 幅度 · 2.2 秒）"
        };
        float r = currentRatio();
        int checked = push ? (r < 1.087f ? 0 : (r > 1.118f ? 2 : 1)) : (r < 1.013f ? 0 : (r > 1.024f ? 2 : 1));
        new AlertDialog.Builder(this)
                .setTitle("微动幅度与时长")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    float newRatio = push ? (which == 0 ? 1.058f : (which == 2 ? 1.120f : 1.116f)) : (which == 0 ? 1.010f : (which == 2 ? 1.032f : 1.018f));
                    float newDur = push ? (which == 2 ? 2.2f : 2.0f) : (which == 0 ? 1.3f : (which == 2 ? 2.2f : 1.6f));
                    prefs().edit().putFloat(KEY_RATIO, newRatio).putFloat(KEY_DURATION, newDur).apply();
                    toast("已调整为：" + items[which]);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static Bitmap decodeScaledBitmap(ContentResolver resolver, Uri uri, int maxSide) {
        try (InputStream is1 = resolver.openInputStream(uri)) {
            if (is1 == null) return null;
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is1, null, bo);
            int sample = 1;
            int max = Math.max(bo.outWidth, bo.outHeight);
            while (max / sample > maxSide) sample *= 2;
            byte[] raw;
            try (InputStream is2 = resolver.openInputStream(uri)) {
                if (is2 == null) return null;
                raw = readStreamBytes(is2);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            if (bmp == null) return null;
            int orient = ExifOrientationReader.readJpegOrientation(raw);
            return ExifOrientationReader.applyOrientation(bmp, orient);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readStreamBytes(InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = is.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String currentMode() {
        return MotionPreset.normalizeMode(prefs().getString(KEY_MODE, MotionPreset.DEFAULT_MODE));
    }

    private float currentRatio() {
        return MotionPreset.clampRatio(prefs().getFloat(KEY_RATIO, MotionPreset.DEFAULT_RATIO));
    }

    private float currentDuration() {
        return MotionPreset.clampDuration(prefs().getFloat(KEY_DURATION, MotionPreset.DEFAULT_DURATION_SEC));
    }

    private boolean isSkipExistingLive() {
        return prefs().getBoolean(KEY_SKIP_EXISTING_LIVE, true);
    }

    private com.zwm.livecore.LiveQualityTier currentQualityTier() {
        return com.zwm.livecore.LiveQualityTier.fromId(
                prefs().getString(KEY_QUALITY_TIER, com.zwm.livecore.LiveQualityTier.HD.id));
    }

    private final class PhotoGridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visiblePhotos.size();
        }

        @Override
        public Object getItem(int position) {
            return visiblePhotos.get(position);
        }

        @Override
        public long getItemId(int position) {
            return visiblePhotos.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout cell;
            ImageView img;
            TextView badge;
            TextView liveBadge;
            View border;
            if (convertView == null) {
                int cellSize = Math.max(100, (getResources().getDisplayMetrics().widthPixels - dp(8)) / 3);
                cell = new FrameLayout(MainActivity.this);
                cell.setLayoutParams(new GridView.LayoutParams(cellSize, cellSize));

                img = new ImageView(MainActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackgroundColor(cardBg());
                cell.addView(img, new FrameLayout.LayoutParams(-1, -1));

                border = new View(MainActivity.this);
                GradientDrawable bd = new GradientDrawable();
                bd.setColor(Color.TRANSPARENT);
                bd.setStroke(dp(3), brandGreen());
                border.setBackground(bd);
                cell.addView(border, new FrameLayout.LayoutParams(-1, -1));

                badge = new TextView(MainActivity.this);
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(11);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                badge.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
                bLp.setMargins(0, dp(5), dp(5), 0);
                cell.addView(badge, bLp);

                liveBadge = new TextView(MainActivity.this);
                liveBadge.setText("LIVE");
                liveBadge.setTextColor(Color.WHITE);
                liveBadge.setTextSize(9);
                liveBadge.setTypeface(Typeface.DEFAULT_BOLD);
                liveBadge.setPadding(dp(5), dp(2), dp(5), dp(2));
                liveBadge.setBackground(roundRect(0x88000000, 6));
                FrameLayout.LayoutParams lLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
                lLp.setMargins(dp(5), 0, 0, dp(5));
                cell.addView(liveBadge, lLp);

                cell.setTag(new ViewHolder(img, badge, liveBadge, border));
            } else {
                cell = (FrameLayout) convertView;
            }
            ViewHolder vh = (ViewHolder) cell.getTag();
            GalleryPhoto photo = visiblePhotos.get(position);
            bindSelectionVisual(vh, photo.uri);
            vh.badge.setOnClickListener(v -> toggleSelection(photo.uri));
            vh.liveBadge.setVisibility(photo.looksLive ? View.VISIBLE : View.GONE);

            vh.photoId = photo.id;
            Bitmap cached = thumbCache.get(photo.id);
            if (cached != null && !cached.isRecycled()) {
                vh.img.setImageBitmap(cached);
            } else {
                vh.img.setImageDrawable(null);
                final long targetId = photo.id;
                thumbExecutor.execute(() -> {
                    Bitmap thumb = loadThumbnail(photo);
                    if (thumb != null) {
                        thumbCache.put(targetId, thumb);
                        mainHandler.post(() -> {
                            if (vh.photoId == targetId) {
                                vh.img.setImageBitmap(thumb);
                            }
                        });
                    }
                });
            }
            return cell;
        }
    }

    private void bindSelectionVisual(ViewHolder vh, Uri uri) {
        int selIdx = selectedUris.indexOf(uri);
        vh.badge.setVisibility(View.VISIBLE);
        if (selIdx >= 0) {
            vh.badge.setText(String.valueOf(selIdx + 1));
            GradientDrawable filled = new GradientDrawable();
            filled.setShape(GradientDrawable.OVAL);
            filled.setColor(brandGreen());
            vh.badge.setBackground(filled);
            vh.border.setVisibility(View.VISIBLE);
        } else {
            vh.badge.setText("");
            GradientDrawable hollow = new GradientDrawable();
            hollow.setShape(GradientDrawable.OVAL);
            hollow.setColor(0x38000000);
            hollow.setStroke(dp(1), 0xCCFFFFFF);
            vh.badge.setBackground(hollow);
            vh.border.setVisibility(View.GONE);
        }
    }

    private static final class ViewHolder {
        final ImageView img;
        final TextView badge;
        final TextView liveBadge;
        final View border;
        long photoId;

        ViewHolder(ImageView img, TextView badge, TextView liveBadge, View border) {
            this.img = img;
            this.badge = badge;
            this.liveBadge = liveBadge;
            this.border = border;
        }
    }

    private Bitmap loadThumbnail(GalleryPhoto photo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return getContentResolver().loadThumbnail(photo.uri, new Size(320, 320), null);
            }
            @SuppressWarnings("deprecation")
            Bitmap legacy = MediaStore.Images.Thumbnails.getThumbnail(
                    getContentResolver(), photo.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
            return legacy;
        } catch (Exception ignored) {
            return decodeScaledBitmap(getContentResolver(), photo.uri, 320);
        }
    }

    private int bgPrimary() {
        return isDark ? Color.rgb(14, 16, 20) : Color.rgb(246, 248, 250);
    }

    private int cardBg() {
        return isDark ? Color.rgb(28, 31, 38) : Color.WHITE;
    }

    private int textPrimary() {
        return isDark ? Color.rgb(242, 244, 248) : Color.rgb(20, 24, 31);
    }

    private int textSecondary() {
        return isDark ? Color.rgb(156, 163, 175) : Color.rgb(100, 110, 125);
    }

    private int brandGreen() {
        return Color.rgb(22, 163, 74);
    }

    private TextView label(String text, int sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView chipButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(textPrimary());
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        tv.setBackground(roundRect(cardBg(), 18));
        return tv;
    }

    private Button actionBtn(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : textPrimary());
        b.setBackground(roundRect(primary ? brandGreen() : (isDark ? Color.rgb(44, 48, 58) : Color.rgb(226, 232, 240)), 12));
        return b;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
