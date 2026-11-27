package com.zwm.gallery;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.AbsListView;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native Phone Gallery mode embedded inside Album:
 * tap for full-screen native-gesture viewer (swipe, pinch/double-tap zoom, swipe-down dismiss, live preview,
 * edit/delete/share original/save live/share live), long-press or swipe to multi-select with edge auto-scroll,
 * powered by on-device 1440px GPU rendering.
 */
final class PhoneGalleryPane {
    static final int REQ_MEDIA_PERMS = 6101;
    static final int REQ_DELETE_MEDIA = 6102;
    private static final int MAX_SHARE_LIVE = 18;

    private interface UriListCallback {
        void run(List<Uri> chosen);
    }

    private static final class GalleryPhoto {
        final long id;
        final Uri uri;
        final String bucket;
        final boolean looksLive;
        final long dateMillis;

        GalleryPhoto(long id, Uri uri, String bucket, boolean looksLive, long dateMillis) {
            this.id = id;
            this.uri = uri;
            this.bucket = bucket;
            this.looksLive = looksLive;
            this.dateMillis = dateMillis;
        }
    }

    private final Activity activity;
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

    // Swipe-to-multiselect state
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
    private final int touchSlop;

    private final LinearLayout root;
    private final TextView leftCtrlBtn;
    private final TextView bucketChip;
    private final TextView effectChip;
    private final TextView rightCtrlBtn;
    private final LinearLayout permissionCard;
    private final GridView gridView;
    private final TextView scrollDatePill;
    private final PhotoGridAdapter adapter = new PhotoGridAdapter();
    private final LinearLayout bottomBar;
    private final Button deleteBtn;
    private final Button shareRawBtn;
    private final Button saveLiveBtn;
    private final Button shareLiveBtn;

    private AlertDialog activePreviewDialog;
    private ValueAnimator activeLiveAnimator;
    private Runnable pendingDeleteCallback;
    private int pendingDeleteCount = 0;
    private final Runnable hideDatePillRunnable = new Runnable() {
        @Override
        public void run() {
            if (scrollDatePill.getVisibility() == View.VISIBLE) {
                scrollDatePill.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    if (scrollDatePill.getAlpha() < 0.05f) {
                        scrollDatePill.setVisibility(View.GONE);
                    }
                }).start();
            }
        }
    };

    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isDragSelecting || !dragAutoScrollActive) {
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

    PhoneGalleryPane(Activity activity) {
        this.activity = activity;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        // Sub-header strip (album filter + effect preset / multi-select controls)
        LinearLayout subHeader = new LinearLayout(activity);
        subHeader.setOrientation(LinearLayout.HORIZONTAL);
        subHeader.setGravity(Gravity.CENTER_VERTICAL);
        subHeader.setPadding(dp(12), dp(2), dp(12), dp(8));

        leftCtrlBtn = chip("取消");
        leftCtrlBtn.setTextColor(ThemePalette.getBrandLightText(activity));
        leftCtrlBtn.setVisibility(View.GONE);
        leftCtrlBtn.setOnClickListener(v -> exitSelectionMode());
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(-2, -2);
        lcLp.setMargins(0, 0, dp(8), 0);
        subHeader.addView(leftCtrlBtn, lcLp);

        bucketChip = chip("全部照片 ▾");
        bucketChip.setOnClickListener(v -> showBucketDialog());
        subHeader.addView(bucketChip, new LinearLayout.LayoutParams(-2, -2));

        subHeader.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));

        effectChip = chip("效果：" + shortModeName(OnlineService.getAutoLiveMotionMode(activity)));
        effectChip.setOnClickListener(v -> showEffectDialog());
        LinearLayout.LayoutParams ecLp = new LinearLayout.LayoutParams(-2, -2);
        ecLp.setMargins(0, 0, dp(6), 0);
        subHeader.addView(effectChip, ecLp);

        rightCtrlBtn = chip("多选");
        rightCtrlBtn.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelectAll();
            } else if (!visiblePhotos.isEmpty()) {
                selectionMode = true;
                updateChrome();
                adapter.notifyDataSetChanged();
            }
        });
        subHeader.addView(rightCtrlBtn, new LinearLayout.LayoutParams(-2, -2));
        root.addView(subHeader);

        // Permission prompt card
        permissionCard = new LinearLayout(activity);
        permissionCard.setOrientation(LinearLayout.VERTICAL);
        permissionCard.setPadding(dp(18), dp(20), dp(18), dp(20));
        permissionCard.setBackground(roundWithStroke(ThemePalette.getCardBg(activity), 16, ThemePalette.getCardStroke(activity)));

        TextView permTitle = text("授权读取本机相册", 16, true, ThemePalette.getTextPrimary(activity));
        TextView permDesc = text(
                "在「团队工作相册」内直接浏览本机照片：点击看大图、系统编辑/删除、长按并滑动连选，手机本地 1440P 硬件直出实况图并分享。",
                13, false, ThemePalette.getTextSecondary(activity));
        Button grantBtn = actionBtn("允许读取本机相册", true);
        grantBtn.setOnClickListener(v -> requestGalleryPermission());

        permissionCard.addView(permTitle);
        LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(-1, -2);
        pdLp.setMargins(0, dp(6), 0, dp(14));
        permissionCard.addView(permDesc, pdLp);
        permissionCard.addView(grantBtn, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(-1, -2);
        pcLp.setMargins(dp(14), dp(16), dp(14), dp(12));
        root.addView(permissionCard, pcLp);

        // 3-column thumbnail grid inside FrameLayout with floating scroll-date pill
        FrameLayout gridContainer = new FrameLayout(activity);
        gridView = new GridView(activity);
        gridView.setNumColumns(3);
        gridView.setHorizontalSpacing(dp(2));
        gridView.setVerticalSpacing(dp(2));
        gridView.setPadding(dp(2), 0, dp(2), dp(4));
        gridView.setClipToPadding(false);
        gridView.setAdapter(adapter);
        setupGridInteractions();
        gridContainer.addView(gridView, new FrameLayout.LayoutParams(-1, -1));

        scrollDatePill = text("", 12, true, Color.WHITE);
        scrollDatePill.setPadding(dp(12), dp(5), dp(12), dp(5));
        scrollDatePill.setBackground(roundRect(0xCC181A1F, 14));
        scrollDatePill.setAlpha(0f);
        scrollDatePill.setVisibility(View.GONE);
        FrameLayout.LayoutParams dpLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dpLp.setMargins(0, dp(8), 0, 0);
        gridContainer.addView(scrollDatePill, dpLp);

        gridView.setOnScrollListener(new AbsListView.OnScrollListener() {
            private boolean scrolling = false;

            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                scrolling = scrollState != SCROLL_STATE_IDLE;
                if (!scrolling) {
                    mainHandler.removeCallbacks(hideDatePillRunnable);
                    mainHandler.postDelayed(hideDatePillRunnable, 850);
                } else {
                    updateFloatingDate(view.getFirstVisiblePosition());
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (scrolling && visibleItemCount > 0) {
                    updateFloatingDate(firstVisibleItem);
                }
            }
        });
        root.addView(gridContainer, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Sticky bottom multi-select bar
        bottomBar = new LinearLayout(activity);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(ThemePalette.getCardBg(activity));
        bottomBar.setPadding(dp(10), dp(10), dp(10), dp(10));
        bottomBar.setVisibility(View.GONE);

        deleteBtn = actionBtn("删除", false);
        deleteBtn.setTextColor(Color.rgb(220, 38, 38));
        deleteBtn.setOnClickListener(v -> requestDeletePhotos(new ArrayList<>(selectedUris), this::exitSelectionMode));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, dp(44), 0.78f);
        dLp.setMargins(0, 0, dp(6), 0);
        bottomBar.addView(deleteBtn, dLp);

        shareRawBtn = actionBtn("原图", false);
        shareRawBtn.setOnClickListener(v -> confirmWithinShareLimit(new ArrayList<>(selectedUris), "分享原图",
                chosen -> launchSystemShare(chosen, "分享原图")));
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, dp(44), 0.78f);
        rLp.setMargins(0, 0, dp(6), 0);
        bottomBar.addView(shareRawBtn, rLp);

        saveLiveBtn = actionBtn("存实况", false);
        saveLiveBtn.setOnClickListener(v -> confirmWithinShareLimit(new ArrayList<>(selectedUris), "保存实况",
                chosen -> startBatchConvert(chosen, false)));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(44), 0.92f);
        sLp.setMargins(0, 0, dp(6), 0);
        bottomBar.addView(saveLiveBtn, sLp);

        shareLiveBtn = actionBtn("分享实况", true);
        shareLiveBtn.setOnClickListener(v -> confirmWithinShareLimit(new ArrayList<>(selectedUris), "分享实况",
                chosen -> startBatchConvert(chosen, true)));
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(0, dp(44), 1.32f);
        bottomBar.addView(shareLiveBtn, shLp);

        root.addView(bottomBar);
        updateChrome();
    }

    View getView() {
        return root;
    }

    void onShown() {
        effectChip.setText("效果：" + shortModeName(OnlineService.getAutoLiveMotionMode(activity)));
        loadGalleryIfPermitted();
    }

    void refresh() {
        effectChip.setText("效果：" + shortModeName(OnlineService.getAutoLiveMotionMode(activity)));
        loadGalleryIfPermitted();
    }

    boolean handleBack() {
        if (selectionMode) {
            exitSelectionMode();
            return true;
        }
        return false;
    }

    void onRequestPermissionsResult(int requestCode) {
        if (requestCode == REQ_MEDIA_PERMS) {
            loadGalleryIfPermitted();
        }
    }

    void onActivityResult(int requestCode, int resultCode) {
        if (requestCode == REQ_DELETE_MEDIA) {
            Runnable cb = pendingDeleteCallback;
            pendingDeleteCallback = null;
            if (resultCode == Activity.RESULT_OK) {
                toast("已删除 " + pendingDeleteCount + " 张照片");
                if (cb != null) cb.run();
                loadGalleryIfPermitted();
            }
        }
    }

    void destroy() {
        mainHandler.removeCallbacks(autoScrollRunnable);
        mainHandler.removeCallbacks(hideDatePillRunnable);
        if (activeLiveAnimator != null) {
            activeLiveAnimator.cancel();
            activeLiveAnimator = null;
        }
        ioExecutor.shutdownNow();
        thumbExecutor.shutdownNow();
    }

    private void updateFloatingDate(int firstPos) {
        if (firstPos < 0 || firstPos >= visiblePhotos.size()) return;
        String label = formatPhotoDate(visiblePhotos.get(firstPos).dateMillis);
        if (label.isEmpty()) return;
        mainHandler.removeCallbacks(hideDatePillRunnable);
        scrollDatePill.setText(label);
        if (scrollDatePill.getVisibility() != View.VISIBLE || scrollDatePill.getAlpha() < 0.95f) {
            scrollDatePill.animate().cancel();
            scrollDatePill.setVisibility(View.VISIBLE);
            scrollDatePill.animate().alpha(1f).setDuration(120).start();
        }
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
        if (sentCancelToGrid) return;
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
        if (visiblePhotos.isEmpty()) return;
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
        gridView.requestDisallowInterceptTouchEvent(false);
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
            selectedUris.add(firstUri);
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
            if (selectedUris.isEmpty()) selectionMode = false;
        } else {
            selectionMode = true;
            selectedUris.add(uri);
        }
        updateChrome();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelectAll() {
        if (selectedUris.size() >= visiblePhotos.size() && !visiblePhotos.isEmpty()) {
            selectedUris.clear();
        } else {
            selectedUris.clear();
            for (GalleryPhoto p : visiblePhotos) {
                selectedUris.add(p.uri);
            }
        }
        updateChrome();
        adapter.notifyDataSetChanged();
    }

    private void updateChrome() {
        if (selectionMode) {
            int count = selectedUris.size();
            leftCtrlBtn.setVisibility(View.VISIBLE);
            bucketChip.setBackground(null);
            bucketChip.setText("已选 " + count + " 张");
            rightCtrlBtn.setText(count >= visiblePhotos.size() && !visiblePhotos.isEmpty() ? "取消全选" : "全选");

            bottomBar.setVisibility(View.VISIBLE);
            boolean hasAny = count > 0;
            float alpha = hasAny ? 1f : 0.45f;
            for (Button b : new Button[]{deleteBtn, shareRawBtn, saveLiveBtn, shareLiveBtn}) {
                b.setEnabled(hasAny);
                b.setAlpha(alpha);
            }
            deleteBtn.setText(hasAny ? "删除 (" + count + ")" : "删除");
            shareLiveBtn.setText(hasAny ? "分享实况 (" + count + ")" : "分享实况");
        } else {
            leftCtrlBtn.setVisibility(View.GONE);
            bucketChip.setBackground(roundWithStroke(ThemePalette.getCardBg(activity), 16, ThemePalette.getCardStroke(activity)));
            bucketChip.setText((activeBucket.isEmpty() ? "全部照片" : activeBucket) + " ▾");
            rightCtrlBtn.setText("多选");
            bottomBar.setVisibility(View.GONE);
        }
    }

    private void confirmWithinShareLimit(List<Uri> inputs, String actionName, UriListCallback callback) {
        if (inputs == null || inputs.isEmpty()) return;
        if (inputs.size() <= MAX_SHARE_LIVE) {
            callback.run(inputs);
            return;
        }
        new AlertDialog.Builder(activity, ThemePalette.dialogThemeRes(activity))
                .setTitle("单次" + actionName + "上限 " + MAX_SHARE_LIVE + " 张")
                .setMessage("当前已选 " + inputs.size() + " 张（批量删除无张数限制）。" + actionName + "单次建议最多 " + MAX_SHARE_LIVE + " 张，是否只处理前 " + MAX_SHARE_LIVE + " 张？")
                .setPositiveButton("仅处理前 " + MAX_SHARE_LIVE + " 张", (d, w) ->
                        callback.run(new ArrayList<>(inputs.subList(0, MAX_SHARE_LIVE))))
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean hasFullOrPartialGalleryPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            boolean full = activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean partial = activity.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
            return full || partial;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        }
        return activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestGalleryPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }, REQ_MEDIA_PERMS);
        } else if (Build.VERSION.SDK_INT >= 33) {
            activity.requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQ_MEDIA_PERMS);
        } else {
            activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_MEDIA_PERMS);
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
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED
        };
        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " DESC";
        try (Cursor cursor = activity.getContentResolver().query(
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
            int takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
            int modCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED);
            int limit = 800;
            while (cursor.moveToNext() && list.size() < limit) {
                long id = cursor.getLong(idCol);
                String bucket = bucketCol >= 0 ? cursor.getString(bucketCol) : "";
                if (bucket == null || bucket.trim().isEmpty()) bucket = "其他";
                String disp = nameCol >= 0 ? cursor.getString(nameCol) : "";
                if (disp == null) disp = "";
                long takenMs = takenCol >= 0 ? cursor.getLong(takenCol) : 0L;
                long modSec = modCol >= 0 ? cursor.getLong(modCol) : 0L;
                long dateMs = takenMs > 0L ? takenMs : (modSec > 0L ? modSec * 1000L : 0L);
                boolean liveHint = disp.startsWith("MVIMG_") || disp.startsWith("LIVE_") || "实况工坊".equals(bucket);
                Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                list.add(new GalleryPhoto(id, contentUri, bucket, liveHint, dateMs));
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
        new AlertDialog.Builder(activity, ThemePalette.dialogThemeRes(activity))
                .setTitle("选择手机相册")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    activeBucket = keys.get(which);
                    applyBucketFilter();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEffectDialog() {
        String current = OnlineService.getAutoLiveMotionMode(activity);
        int checked = 0;
        String[] labels = new String[OnlineService.AUTO_LIVE_MODE_IDS.length];
        for (int i = 0; i < OnlineService.AUTO_LIVE_MODE_IDS.length; i++) {
            if (OnlineService.AUTO_LIVE_MODE_IDS[i].equals(current)) checked = i;
            labels[i] = OnlineService.AUTO_LIVE_MODE_TITLES[i] + "\n" + OnlineService.AUTO_LIVE_MODE_DESCS[i];
        }
        new AlertDialog.Builder(activity, ThemePalette.dialogThemeRes(activity))
                .setTitle("实况效果（与工作区共用）")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    String chosenId = OnlineService.AUTO_LIVE_MODE_IDS[which];
                    OnlineService.setAutoLiveMotionMode(activity, chosenId);
                    effectChip.setText("效果：" + shortModeName(chosenId));
                    toast("已设为：" + OnlineService.AUTO_LIVE_MODE_TITLES[which]);
                    d.dismiss();
                })
                .setNeutralButton("幅度与选项", (d, w) -> showStrengthDialog())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showStrengthDialog() {
        com.zwm.livecore.LiveQualityTier curTier =
                com.zwm.livecore.LiveQualityTier.fromId(OnlineService.getAutoLiveQualityTier(activity));
        String[] items = {
                "极微自然（1.0% · 1.3 秒）",
                "标准推荐（1.8% · 1.6 秒）",
                "轻柔明显（3.2% · 2.2 秒）",
                "跳过已是实况的照片：" + (OnlineService.isAutoLiveSkipExisting(activity) ? "已开启" : "已关闭"),
                "动态视频画质：" + curTier.title + "（静态无损）"
        };
        float r = OnlineService.getAutoLiveRatio(activity);
        int checked = r < 1.013f ? 0 : (r > 1.024f ? 2 : 1);
        new AlertDialog.Builder(activity, ThemePalette.dialogThemeRes(activity))
                .setTitle("微动幅度、画质与选项")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (which == 3) {
                        boolean next = !OnlineService.isAutoLiveSkipExisting(activity);
                        OnlineService.setAutoLiveSkipExisting(activity, next);
                        toast(next ? "已开启：已有实况照片直接直通" : "已关闭：统一重新生成");
                    } else if (which == 4) {
                        showQualityTierDialog();
                    } else {
                        float newRatio = which == 0 ? 1.010f : (which == 2 ? 1.032f : 1.018f);
                        float newDur = which == 0 ? 1.3f : (which == 2 ? 2.2f : 1.6f);
                        OnlineService.setAutoLiveRatio(activity, newRatio);
                        OnlineService.setAutoLiveDuration(activity, newDur);
                        toast("已调整为：" + items[which]);
                    }
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showQualityTierDialog() {
        com.zwm.livecore.LiveQualityTier cur =
                com.zwm.livecore.LiveQualityTier.fromId(OnlineService.getAutoLiveQualityTier(activity));
        com.zwm.livecore.LiveQualityTier[] tiers = com.zwm.livecore.LiveQualityTier.values();
        String[] labels = new String[tiers.length];
        int checked = 0;
        for (int i = 0; i < tiers.length; i++) {
            if (tiers[i] == cur) checked = i;
            labels[i] = tiers[i].title + "\n" + tiers[i].summary;
        }
        new AlertDialog.Builder(activity, ThemePalette.dialogThemeRes(activity))
                .setTitle("动态视频画质档位（静态原画无损）")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    com.zwm.livecore.LiveQualityTier chosen = tiers[which];
                    OnlineService.setAutoLiveQualityTier(activity, chosen.id);
                    toast("动态画质已设为：" + chosen.title + "（静态封面保持原图无损）");
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPhotoPreviewDialog(int initialPos) {
        if (visiblePhotos.isEmpty()) return;
        final int[] pos = {Math.max(0, Math.min(initialPos, visiblePhotos.size() - 1))};
        final boolean[] userToggledLive = {false};
        final boolean[] livePreviewOn = {false};

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.BLACK);

        LinearLayout vTop = new LinearLayout(activity);
        vTop.setOrientation(LinearLayout.HORIZONTAL);
        vTop.setGravity(Gravity.CENTER_VERTICAL);
        vTop.setPadding(dp(14), dp(12), dp(14), dp(10));

        TextView counterView = text("", 14, true, Color.WHITE);
        counterView.setSingleLine(true);
        vTop.addView(counterView, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView liveTogglePill = text("", 11, true, Color.WHITE);
        liveTogglePill.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(-2, -2);
        ltLp.setMargins(dp(6), 0, dp(4), 0);
        vTop.addView(liveTogglePill, ltLp);

        TextView editBtn = text("编辑", 14, true, Color.rgb(134, 239, 172));
        editBtn.setPadding(dp(10), dp(4), dp(12), dp(4));
        vTop.addView(editBtn);

        TextView closeBtn = text("✕", 18, true, Color.WHITE);
        closeBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        vTop.addView(closeBtn);
        container.addView(vTop);

        FrameLayout stage = new FrameLayout(activity);
        ZoomablePhotoView bigImage = new ZoomablePhotoView(activity, dp(1));
        stage.addView(bigImage, new FrameLayout.LayoutParams(-1, -1));

        TextView prevBtn = navArrow("‹", Gravity.START | Gravity.CENTER_VERTICAL);
        TextView nextBtn = navArrow("›", Gravity.END | Gravity.CENTER_VERTICAL);
        stage.addView(prevBtn);
        stage.addView(nextBtn);
        container.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout vBottom = new LinearLayout(activity);
        vBottom.setOrientation(LinearLayout.HORIZONTAL);
        vBottom.setBackgroundColor(Color.rgb(20, 22, 26));
        vBottom.setPadding(dp(10), dp(10), dp(10), dp(14));

        Button vDelete = actionBtn("删除", false);
        vDelete.setBackground(roundRect(Color.rgb(42, 46, 54), 10));
        vDelete.setTextColor(Color.rgb(248, 113, 113));
        LinearLayout.LayoutParams vdLp = new LinearLayout.LayoutParams(0, dp(44), 0.78f);
        vdLp.setMargins(0, 0, dp(6), 0);
        vBottom.addView(vDelete, vdLp);

        Button vRaw = actionBtn("原图", false);
        vRaw.setBackground(roundRect(Color.rgb(42, 46, 54), 10));
        vRaw.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams vrLp = new LinearLayout.LayoutParams(0, dp(44), 0.78f);
        vrLp.setMargins(0, 0, dp(6), 0);
        vBottom.addView(vRaw, vrLp);

        Button vSave = actionBtn("存实况", false);
        vSave.setBackground(roundRect(Color.rgb(42, 46, 54), 10));
        vSave.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams vsLp = new LinearLayout.LayoutParams(0, dp(44), 0.92f);
        vsLp.setMargins(0, 0, dp(6), 0);
        vBottom.addView(vSave, vsLp);

        Button vShare = actionBtn("分享实况", true);
        LinearLayout.LayoutParams vshLp = new LinearLayout.LayoutParams(0, dp(44), 1.32f);
        vBottom.addView(vShare, vshLp);

        container.addView(vBottom);

        AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                .setView(container)
                .create();
        activePreviewDialog = dialog;

        Runnable stopLiveAnim = () -> {
            if (activeLiveAnimator != null) {
                activeLiveAnimator.cancel();
                activeLiveAnimator = null;
            }
            bigImage.clearLiveOverlay();
        };

        Runnable syncLiveAnim = () -> {
            stopLiveAnim.run();
            if (livePreviewOn[0]) {
                liveTogglePill.setText("● LIVE 动图");
                liveTogglePill.setTextColor(Color.rgb(167, 243, 208));
                liveTogglePill.setBackground(roundRect(0x99065F46, 12));
                final String mode = OnlineService.getAutoLiveMotionMode(activity);
                final float baseRatio = OnlineService.getAutoLiveRatio(activity);
                final float previewRatio = baseRatio < 1.028f ? 1.028f : baseRatio;
                final long durMs = Math.max(1000L, Math.round(OnlineService.getAutoLiveDuration(activity) * 1000f));
                ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
                anim.setDuration(durMs);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setRepeatMode(ValueAnimator.REVERSE);
                anim.setInterpolator(new LinearInterpolator());
                anim.addUpdateListener(va -> {
                    float f = (float) va.getAnimatedValue();
                    int frame = Math.round(f * 59f);
                    LiveMotionPreset.FrameTransform tf = LiveMotionPreset.computeFrame(mode, previewRatio, frame, 60);
                    bigImage.setLiveOverlay(tf.zoom, tf.offsetX, tf.offsetY);
                });
                anim.start();
                activeLiveAnimator = anim;
            } else {
                liveTogglePill.setText("LIVE 静图");
                liveTogglePill.setTextColor(Color.rgb(203, 213, 225));
                liveTogglePill.setBackground(roundRect(0x66334155, 12));
            }
        };

        dialog.setOnDismissListener(d -> {
            stopLiveAnim.run();
            if (activePreviewDialog == dialog) activePreviewDialog = null;
        });

        Runnable renderCurrent = () -> {
            if (visiblePhotos.isEmpty()) {
                dialog.dismiss();
                return;
            }
            if (pos[0] >= visiblePhotos.size()) pos[0] = visiblePhotos.size() - 1;
            GalleryPhoto cur = visiblePhotos.get(pos[0]);
            bigImage.resetZoom();
            String dateStr = formatPhotoDate(cur.dateMillis);
            String title = (pos[0] + 1) + " / " + visiblePhotos.size()
                    + (dateStr.isEmpty() ? "" : " · " + dateStr);
            counterView.setText(title);
            prevBtn.setVisibility(pos[0] > 0 ? View.VISIBLE : View.INVISIBLE);
            nextBtn.setVisibility(pos[0] < visiblePhotos.size() - 1 ? View.VISIBLE : View.INVISIBLE);
            if (!userToggledLive[0]) {
                livePreviewOn[0] = cur.looksLive || OnlineService.isAutoLiveOnShareEnabled(activity);
            }
            syncLiveAnim.run();

            final Uri targetUri = cur.uri;
            ioExecutor.execute(() -> {
                Bitmap bmp = decodeScaledBitmap(activity.getContentResolver(), targetUri, 1600);
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

        liveTogglePill.setOnClickListener(v -> {
            userToggledLive[0] = true;
            livePreviewOn[0] = !livePreviewOn[0];
            syncLiveAnim.run();
        });
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        editBtn.setOnClickListener(v -> {
            if (pos[0] < visiblePhotos.size()) {
                openSystemEditor(visiblePhotos.get(pos[0]).uri);
            }
        });
        Runnable goPrev = () -> {
            if (pos[0] > 0) {
                pos[0]--;
                renderCurrent.run();
            }
        };
        Runnable goNext = () -> {
            if (pos[0] < visiblePhotos.size() - 1) {
                pos[0]++;
                renderCurrent.run();
            }
        };
        prevBtn.setOnClickListener(v -> goPrev.run());
        nextBtn.setOnClickListener(v -> goNext.run());
        bigImage.setCallbacks(goPrev, goNext, dialog::dismiss, () -> {
            if (pos[0] < visiblePhotos.size()) {
                Uri u = visiblePhotos.get(pos[0]).uri;
                bigImage.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                dialog.dismiss();
                enterSelectionWith(u);
            }
        });

        vRaw.setOnClickListener(v -> {
            if (pos[0] < visiblePhotos.size()) {
                List<Uri> single = new ArrayList<>();
                single.add(visiblePhotos.get(pos[0]).uri);
                dialog.dismiss();
                launchSystemShare(single, "分享原图");
            }
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

    private void openSystemEditor(Uri uri) {
        try {
            Intent edit = new Intent(Intent.ACTION_EDIT);
            edit.setDataAndType(uri, "image/*");
            edit.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(edit, "选择修图应用"));
        } catch (Exception e) {
            toast("未找到可用的图片编辑器");
        }
    }

    private TextView navArrow(String symbol, int gravity) {
        TextView tv = text(symbol, 24, true, 0xCCFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(roundRect(0x36000000, 18));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(34), dp(54), gravity);
        lp.setMargins(dp(8), 0, dp(8), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static String formatPhotoDate(long millis) {
        if (millis <= 0L) return "";
        Calendar photoCal = Calendar.getInstance();
        photoCal.setTimeInMillis(millis);
        Calendar today = Calendar.getInstance();
        if (photoCal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && photoCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return "今天";
        }
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (photoCal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR)
                && photoCal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)) {
            return "昨天";
        }
        String pattern = photoCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ? "M月d日" : "yyyy年M月d日";
        return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(millis));
    }

    @SuppressLint("AppCompatCustomView")
    private static final class ZoomablePhotoView extends ImageView {
        private final float density;
        private final Matrix drawMatrix = new Matrix();
        private final Matrix baseMatrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;

        private float userScale = 1f;
        private float panX = 0f;
        private float panY = 0f;
        private float liveZoom = 1f;
        private float liveOffsetX = 0.5f;
        private float liveOffsetY = 0.5f;
        private boolean liveActive = false;

        private float downX = 0f;
        private float downY = 0f;
        private boolean consumedSwipe = false;

        private Runnable onPrev;
        private Runnable onNext;
        private Runnable onDismiss;
        private Runnable onLongPress;

        ZoomablePhotoView(Context ctx, float density) {
            super(ctx);
            this.density = Math.max(1f, density);
            setScaleType(ScaleType.MATRIX);

            this.scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float nextScale = Math.max(1f, Math.min(4.0f, userScale * d.getScaleFactor()));
                    float factor = nextScale / userScale;
                    if (Math.abs(factor - 1f) > 0.0005f) {
                        panX = (panX - (d.getFocusX() - getWidth() * 0.5f)) * factor + (d.getFocusX() - getWidth() * 0.5f);
                        panY = (panY - (d.getFocusY() - getHeight() * 0.5f)) * factor + (d.getFocusY() - getHeight() * 0.5f);
                        userScale = nextScale;
                        if (userScale <= 1.01f) {
                            userScale = 1f;
                            panX = 0f;
                            panY = 0f;
                        }
                        applyMatrix();
                    }
                    return true;
                }
            });

            this.gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (userScale > 1.05f) {
                        resetZoom();
                    } else {
                        userScale = 2.5f;
                        panX = (getWidth() * 0.5f - e.getX()) * 1.5f;
                        panY = (getHeight() * 0.5f - e.getY()) * 1.5f;
                        clampPan();
                        applyMatrix();
                    }
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                    if (userScale > 1.02f) {
                        panX -= distanceX;
                        panY -= distanceY;
                        clampPan();
                        applyMatrix();
                        return true;
                    }
                    return false;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    if (userScale <= 1.02f && onLongPress != null) {
                        consumedSwipe = true;
                        onLongPress.run();
                    }
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (userScale > 1.02f || e1 == null || e2 == null) return false;
                    float dx = e2.getX() - e1.getX();
                    float dy = e2.getY() - e1.getY();
                    if (Math.abs(vx) > Math.abs(vy) * 1.1f && Math.abs(vx) > 550f && Math.abs(dx) > 28f * ZoomablePhotoView.this.density) {
                        consumedSwipe = true;
                        setTranslationX(0f);
                        setTranslationY(0f);
                        if (dx < 0 && onNext != null) onNext.run();
                        else if (dx > 0 && onPrev != null) onPrev.run();
                        return true;
                    }
                    if (dy > Math.abs(dx) * 1.25f && vy > 650f && dy > 36f * ZoomablePhotoView.this.density) {
                        consumedSwipe = true;
                        if (onDismiss != null) onDismiss.run();
                        return true;
                    }
                    return false;
                }
            });
        }

        void setCallbacks(Runnable onPrev, Runnable onNext, Runnable onDismiss, Runnable onLongPress) {
            this.onPrev = onPrev;
            this.onNext = onNext;
            this.onDismiss = onDismiss;
            this.onLongPress = onLongPress;
        }

        void resetZoom() {
            userScale = 1f;
            panX = 0f;
            panY = 0f;
            setTranslationX(0f);
            setTranslationY(0f);
            applyMatrix();
        }

        void setLiveOverlay(float zoom, float offsetX, float offsetY) {
            this.liveZoom = zoom;
            this.liveOffsetX = offsetX;
            this.liveOffsetY = offsetY;
            this.liveActive = true;
            if (userScale <= 1.02f) {
                applyMatrix();
            }
        }

        void clearLiveOverlay() {
            this.liveActive = false;
            this.liveZoom = 1f;
            this.liveOffsetX = 0.5f;
            this.liveOffsetY = 0.5f;
            applyMatrix();
        }

        @Override
        public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            recomputeBaseMatrix();
            applyMatrix();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            recomputeBaseMatrix();
            applyMatrix();
        }

        private void recomputeBaseMatrix() {
            Drawable d = getDrawable();
            int vw = getWidth();
            int vh = getHeight();
            if (d == null || vw <= 0 || vh <= 0) return;
            int dw = d.getIntrinsicWidth();
            int dh = d.getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;
            RectF src = new RectF(0, 0, dw, dh);
            RectF dst = new RectF(0, 0, vw, vh);
            baseMatrix.reset();
            baseMatrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER);
        }

        private void clampPan() {
            int vw = getWidth();
            int vh = getHeight();
            float maxX = Math.max(0f, vw * (userScale - 1f) * 0.5f);
            float maxY = Math.max(0f, vh * (userScale - 1f) * 0.5f);
            panX = Math.max(-maxX, Math.min(maxX, panX));
            panY = Math.max(-maxY, Math.min(maxY, panY));
        }

        private void applyMatrix() {
            if (getDrawable() == null || getWidth() <= 0 || getHeight() <= 0) return;
            drawMatrix.set(baseMatrix);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            if (userScale > 1.02f) {
                clampPan();
                drawMatrix.postScale(userScale, userScale, cx, cy);
                drawMatrix.postTranslate(panX, panY);
            } else if (liveActive && liveZoom > 1.0001f) {
                drawMatrix.postScale(liveZoom, liveZoom, cx, cy);
                float maxShiftX = getWidth() * (liveZoom - 1f) * 0.5f;
                float maxShiftY = getHeight() * (liveZoom - 1f) * 0.5f;
                drawMatrix.postTranslate((0.5f - liveOffsetX) * 2f * maxShiftX, (0.5f - liveOffsetY) * 2f * maxShiftY);
            }
            setImageMatrix(drawMatrix);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean scaleHandled = scaleDetector.onTouchEvent(event);
            boolean gestHandled = gestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    consumedSwipe = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (userScale <= 1.02f && !scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        if (Math.abs(dx) > Math.abs(dy) * 1.15f && Math.abs(dx) > 12f * density) {
                            setTranslationX(dx * 0.35f);
                            setTranslationY(0f);
                        } else if (dy > Math.abs(dx) * 1.25f && dy > 12f * density) {
                            setTranslationX(0f);
                            setTranslationY(dy * 0.55f);
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!consumedSwipe && userScale <= 1.02f) {
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        if (Math.abs(dx) > Math.max(56f * density, getWidth() * 0.22f) && Math.abs(dx) > Math.abs(dy) * 1.1f) {
                            setTranslationX(0f);
                            setTranslationY(0f);
                            if (dx < 0 && onNext != null) onNext.run();
                            else if (dx > 0 && onPrev != null) onPrev.run();
                        } else if (dy > 90f * density && dy > Math.abs(dx) * 1.25f) {
                            if (onDismiss != null) onDismiss.run();
                        } else {
                            animate().translationX(0f).translationY(0f).setDuration(140).start();
                        }
                    } else {
                        setTranslationX(0f);
                        setTranslationY(0f);
                    }
                    break;
            }
            return scaleHandled || gestHandled || true;
        }
    }

    private void requestDeletePhotos(List<Uri> targets, Runnable onDone) {
        if (targets == null || targets.isEmpty()) return;
        pendingDeleteCallback = onDone;
        pendingDeleteCount = targets.size();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(activity.getContentResolver(), targets);
                activity.startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE_MEDIA, null, 0, 0, 0);
            } catch (Exception e) {
                pendingDeleteCallback = null;
                toast("无法发起删除：" + e.getMessage());
            }
            return;
        }

        new AlertDialog.Builder(activity, ThemePalette.dialogThemeRes(activity))
                .setTitle("删除确认")
                .setMessage("确定从手机相册删除选中的 " + targets.size() + " 张照片吗？")
                .setNegativeButton("取消", (d, w) -> pendingDeleteCallback = null)
                .setPositiveButton("删除", (d, w) -> ioExecutor.execute(() -> {
                    int deleted = 0;
                    for (Uri u : targets) {
                        try {
                            deleted += activity.getContentResolver().delete(u, null, null);
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
                }))
                .show();
    }

    private void startBatchConvert(List<Uri> inputs, boolean shareAfter) {
        if (inputs == null || inputs.isEmpty()) return;
        isConverting = true;
        final String mode = OnlineService.getAutoLiveMotionMode(activity);
        final com.zwm.livecore.LiveQualityTier tier =
                com.zwm.livecore.LiveQualityTier.fromId(OnlineService.getAutoLiveQualityTier(activity));
        final float ratio = OnlineService.getAutoLiveRatio(activity);
        final float duration = OnlineService.getAutoLiveDuration(activity);
        final boolean skipExisting = OnlineService.isAutoLiveSkipExisting(activity);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(18), dp(20), dp(16));
        TextView title = text("正在手机本地生成实况（静态无损 · " + tier.title + "）", 14, true, ThemePalette.getTextPrimary(activity));
        TextView status = text("准备处理第 1 / " + inputs.size() + " 张", 12, false, ThemePalette.getTextSecondary(activity));
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(inputs.size());
        bar.setProgress(0);
        layout.addView(title);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.setMargins(0, dp(6), 0, dp(10));
        layout.addView(status, stLp);
        layout.addView(bar, new LinearLayout.LayoutParams(-1, dp(8)));

        AlertDialog progressDialog = new AlertDialog.Builder(activity, ThemePalette.dialogThemeRes(activity))
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
                    com.zwm.livecore.LiveConvertResult res = LiveLocalConverter.convertUriDetailed(
                            activity, src, mode, tier, ratio, duration, step, skipExisting
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
                    AlertDialog.Builder b = new AlertDialog.Builder(activity, ThemePalette.dialogThemeRes(activity))
                            .setTitle("部分照片转换失败")
                            .setMessage(summary)
                            .setPositiveButton("重试失败的 " + failedInputs.size() + " 张",
                                    (d, w) -> startBatchConvert(failedInputs, shareAfter))
                            .setNegativeButton("取消", null);
                    if (shareAfter && !outputs.isEmpty()) {
                        b.setNeutralButton("先分享已成功 " + outputs.size() + " 张",
                                (d, w) -> launchSystemShare(outputs, "分享动态实况图"));
                    }
                    b.show();
                    return;
                }
                exitSelectionMode();
                if (shareAfter) {
                    launchSystemShare(outputs, "分享动态实况图");
                } else {
                    String tag = finalLossless == outputs.size() ? "静态原画无损" : "高质量保留";
                    String tierMsg = finalTier != null ? finalTier.title : tier.title;
                    toast("已保存 " + outputs.size() + " 张实况（" + tag + " · " + tierMsg + "）");
                }
            });
        });
    }

    private void launchSystemShare(List<Uri> uris, String chooserTitle) {
        if (uris.isEmpty()) return;
        Intent share;
        if (uris.size() == 1) {
            share = new Intent(Intent.ACTION_SEND);
            share.setType("image/*");
            share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            share = new Intent(Intent.ACTION_SEND_MULTIPLE);
            share.setType("image/*");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        }
        ClipData clip = ClipData.newUri(activity.getContentResolver(), "手机相册", uris.get(0));
        for (int i = 1; i < uris.size(); i++) {
            clip.addItem(new ClipData.Item(uris.get(i)));
        }
        share.setClipData(clip);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(share, chooserTitle));
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
            int orient = LiveExifOrientation.readJpegOrientation(raw);
            return LiveExifOrientation.applyOrientation(bmp, orient);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readStreamBytes(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = is.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
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
            if (convertView == null) {
                int cellSize = Math.max(100, (activity.getResources().getDisplayMetrics().widthPixels - dp(8)) / 3);
                cell = new FrameLayout(activity);
                cell.setLayoutParams(new GridView.LayoutParams(cellSize, cellSize));

                ImageView img = new ImageView(activity);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackgroundColor(ThemePalette.getCardBg(activity));
                cell.addView(img, new FrameLayout.LayoutParams(-1, -1));

                View border = new View(activity);
                GradientDrawable bd = new GradientDrawable();
                bd.setColor(Color.TRANSPARENT);
                bd.setStroke(dp(3), ThemePalette.getBrandLightText(activity));
                border.setBackground(bd);
                cell.addView(border, new FrameLayout.LayoutParams(-1, -1));

                TextView badge = new TextView(activity);
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(11);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                badge.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
                bLp.setMargins(0, dp(5), dp(5), 0);
                cell.addView(badge, bLp);

                TextView liveBadge = new TextView(activity);
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
            filled.setColor(ThemePalette.getBrandLightText(activity));
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
                return activity.getContentResolver().loadThumbnail(photo.uri, new Size(320, 320), null);
            }
            @SuppressWarnings("deprecation")
            Bitmap legacy = MediaStore.Images.Thumbnails.getThumbnail(
                    activity.getContentResolver(), photo.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
            return legacy;
        } catch (Exception ignored) {
            return decodeScaledBitmap(activity.getContentResolver(), photo.uri, 320);
        }
    }

    private static String shortModeName(String id) {
        String title = OnlineService.getAutoLiveMotionTitle(id);
        int idx = title.indexOf('（');
        return idx > 0 ? title.substring(0, idx) : title;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView tv = new TextView(activity);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView chip(String s) {
        TextView tv = new TextView(activity);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(ThemePalette.getTextPrimary(activity));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(11), dp(6), dp(11), dp(6));
        tv.setBackground(roundWithStroke(ThemePalette.getCardBg(activity), 16, ThemePalette.getCardStroke(activity)));
        return tv;
    }

    private Button actionBtn(String s, boolean primary) {
        Button b = new Button(activity);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : ThemePalette.getTextPrimary(activity));
        int bg = primary ? ThemePalette.getBrandLightText(activity) : ThemePalette.getPillBg(activity);
        b.setBackground(roundRect(bg, 10));
        return b;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable roundWithStroke(int fillColor, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private void toast(String msg) {
        try {
            Toast t = Toast.makeText(activity, msg, Toast.LENGTH_SHORT);
            t.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(96));
            t.show();
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
