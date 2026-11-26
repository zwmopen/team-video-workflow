package com.zwm.gallery;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native Phone Gallery mode embedded inside Album:
 * tap for full-screen viewer (edit/delete/share original/save live/share live),
 * long-press or swipe to multi-select with edge auto-scroll, powered by on-device 1440px GPU rendering.
 */
final class PhoneGalleryPane {
    static final int REQ_MEDIA_PERMS = 6101;
    static final int REQ_DELETE_MEDIA = 6102;
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
    private final PhotoGridAdapter adapter = new PhotoGridAdapter();
    private final LinearLayout bottomBar;
    private final Button deleteBtn;
    private final Button shareRawBtn;
    private final Button saveLiveBtn;
    private final Button shareLiveBtn;

    private AlertDialog activePreviewDialog;
    private Runnable pendingDeleteCallback;
    private int pendingDeleteCount = 0;

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

        // 3-column thumbnail grid
        gridView = new GridView(activity);
        gridView.setNumColumns(3);
        gridView.setHorizontalSpacing(dp(2));
        gridView.setVerticalSpacing(dp(2));
        gridView.setPadding(dp(2), 0, dp(2), dp(4));
        gridView.setClipToPadding(false);
        gridView.setAdapter(adapter);
        setupGridInteractions();
        root.addView(gridView, new LinearLayout.LayoutParams(-1, 0, 1f));

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
        shareRawBtn.setOnClickListener(v -> {
            if (!selectedUris.isEmpty()) launchSystemShare(new ArrayList<>(selectedUris), "分享原图");
        });
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, dp(44), 0.78f);
        rLp.setMargins(0, 0, dp(6), 0);
        bottomBar.addView(shareRawBtn, rLp);

        saveLiveBtn = actionBtn("存实况", false);
        saveLiveBtn.setOnClickListener(v -> startBatchConvert(new ArrayList<>(selectedUris), false));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(44), 0.92f);
        sLp.setMargins(0, 0, dp(6), 0);
        bottomBar.addView(saveLiveBtn, sLp);

        shareLiveBtn = actionBtn("分享实况", true);
        shareLiveBtn.setOnClickListener(v -> startBatchConvert(new ArrayList<>(selectedUris), true));
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
        ioExecutor.shutdownNow();
        thumbExecutor.shutdownNow();
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
        if (firstUri != null && !selectedUris.contains(firstUri) && selectedUris.size() < MAX_SELECT) {
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
        if (selectionMode) {
            int count = selectedUris.size();
            leftCtrlBtn.setVisibility(View.VISIBLE);
            bucketChip.setBackground(null);
            bucketChip.setText("已选 " + count + " 张");
            int cap = Math.min(MAX_SELECT, visiblePhotos.size());
            rightCtrlBtn.setText(count >= cap && cap > 0 ? "取消全选" : "全选");

            bottomBar.setVisibility(View.VISIBLE);
            boolean hasAny = count > 0;
            float alpha = hasAny ? 1f : 0.45f;
            for (Button b : new Button[]{deleteBtn, shareRawBtn, saveLiveBtn, shareLiveBtn}) {
                b.setEnabled(hasAny);
                b.setAlpha(alpha);
            }
            shareLiveBtn.setText(hasAny ? "分享实况 (" + count + ")" : "分享实况");
        } else {
            leftCtrlBtn.setVisibility(View.GONE);
            bucketChip.setBackground(roundWithStroke(ThemePalette.getCardBg(activity), 16, ThemePalette.getCardStroke(activity)));
            bucketChip.setText((activeBucket.isEmpty() ? "全部照片" : activeBucket) + " ▾");
            rightCtrlBtn.setText("多选");
            bottomBar.setVisibility(View.GONE);
        }
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
                MediaStore.Images.Media.DISPLAY_NAME
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
        String[] items = {
                "极微自然（1.0% · 1.3 秒）",
                "标准推荐（1.8% · 1.6 秒）",
                "轻柔明显（3.2% · 2.2 秒）",
                "跳过已是实况的照片：" + (OnlineService.isAutoLiveSkipExisting(activity) ? "已开启" : "已关闭")
        };
        float r = OnlineService.getAutoLiveRatio(activity);
        int checked = r < 1.013f ? 0 : (r > 1.024f ? 2 : 1);
        new AlertDialog.Builder(activity, ThemePalette.dialogThemeRes(activity))
                .setTitle("微动幅度与选项")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (which == 3) {
                        boolean next = !OnlineService.isAutoLiveSkipExisting(activity);
                        OnlineService.setAutoLiveSkipExisting(activity, next);
                        toast(next ? "已开启：已有实况照片直接直通" : "已关闭：统一重新生成");
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

    private void showPhotoPreviewDialog(int initialPos) {
        if (visiblePhotos.isEmpty()) return;
        final int[] pos = {Math.max(0, Math.min(initialPos, visiblePhotos.size() - 1))};

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.BLACK);

        LinearLayout vTop = new LinearLayout(activity);
        vTop.setOrientation(LinearLayout.HORIZONTAL);
        vTop.setGravity(Gravity.CENTER_VERTICAL);
        vTop.setPadding(dp(14), dp(12), dp(14), dp(10));

        TextView counterView = text("", 14, true, Color.WHITE);
        vTop.addView(counterView, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView editBtn = text("编辑", 14, true, Color.rgb(134, 239, 172));
        editBtn.setPadding(dp(12), dp(4), dp(14), dp(4));
        vTop.addView(editBtn);

        TextView closeBtn = text("✕", 18, true, Color.WHITE);
        closeBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        vTop.addView(closeBtn);
        container.addView(vTop);

        FrameLayout stage = new FrameLayout(activity);
        ImageView bigImage = new ImageView(activity);
        bigImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
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

        closeBtn.setOnClickListener(v -> dialog.dismiss());
        editBtn.setOnClickListener(v -> {
            if (pos[0] < visiblePhotos.size()) {
                openSystemEditor(visiblePhotos.get(pos[0]).uri);
            }
        });
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
        TextView tv = text(symbol, 28, true, Color.WHITE);
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
        final float ratio = OnlineService.getAutoLiveRatio(activity);
        final float duration = OnlineService.getAutoLiveDuration(activity);
        final boolean skipExisting = OnlineService.isAutoLiveSkipExisting(activity);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(18), dp(20), dp(16));
        TextView title = text("正在手机本地 1440P 渲染动态实况图…", 15, true, ThemePalette.getTextPrimary(activity));
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
            Exception failure = null;
            for (int i = 0; i < inputs.size(); i++) {
                final int step = i + 1;
                final int doneIdx = i;
                mainHandler.post(() -> {
                    if (progressDialog.isShowing()) {
                        status.setText("正在离线硬件渲染第 " + step + " / " + inputs.size() + " 张…");
                        bar.setProgress(doneIdx);
                    }
                });
                try {
                    Uri out = LiveLocalConverter.convertUriOrPassthrough(
                            activity, inputs.get(i), mode, ratio, duration, step, skipExisting
                    );
                    outputs.add(out);
                } catch (Exception e) {
                    failure = e;
                    break;
                }
            }
            final Exception err = failure;
            mainHandler.post(() -> {
                isConverting = false;
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (err != null) {
                    new AlertDialog.Builder(activity, ThemePalette.dialogThemeRes(activity))
                            .setTitle("转换中断")
                            .setMessage("失败原因：" + err.getMessage())
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                exitSelectionMode();
                loadGalleryIfPermitted();
                if (shareAfter) {
                    launchSystemShare(outputs, "分享动态实况图");
                } else {
                    toast("已保存 " + outputs.size() + " 张实况图到相册「实况工坊」");
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
