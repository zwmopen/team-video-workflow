package com.zwm.livestudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int REQ_MEDIA_PERMS = 101;
    private static final int REQ_PICK_IMAGES = 102;
    private static final int MAX_SELECT = 18;

    private static final class GalleryPhoto {
        final long id;
        final Uri uri;
        final String bucket;

        GalleryPhoto(long id, Uri uri, String bucket) {
            this.id = id;
            this.uri = uri;
            this.bucket = bucket;
        }
    }

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<Long, Bitmap> thumbCache = new LruCache<Long, Bitmap>(16 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private final List<GalleryPhoto> allPhotos = new ArrayList<>();
    private final List<GalleryPhoto> visiblePhotos = new ArrayList<>();
    private final List<Uri> selectedUris = new ArrayList<>();
    private String activeBucket = "";

    private boolean isDark;
    private TextView albumChip;
    private TextView effectTitleView;
    private TextView selectedCountView;
    private Button saveOnlyBtn;
    private Button shareBtn;
    private LinearLayout permissionCard;
    private TextView permissionTitle;
    private GridView gridView;
    private PhotoGridAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        isDark = night == Configuration.UI_MODE_NIGHT_YES;
        applySystemBars();
        setContentView(buildUi());
        refreshEffectLabel();
        loadGalleryIfPermitted();
        handleIncomingShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShareIntent(intent);
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        thumbExecutor.shutdownNow();
        super.onDestroy();
    }

    private void applySystemBars() {
        int bg = isDark ? Color.rgb(16, 18, 22) : Color.rgb(245, 247, 250);
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

        // Top Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(12), dp(14), dp(8));

        TextView title = label("实况工坊", 22, true, textPrimary());
        topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        albumChip = chipButton("全部照片 ▾");
        albumChip.setOnClickListener(v -> showBucketDialog());
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
        chipLp.setMargins(0, 0, dp(8), 0);
        topBar.addView(albumChip, chipLp);

        TextView sysPick = chipButton("系统选图");
        sysPick.setOnClickListener(v -> openSystemPicker());
        topBar.addView(sysPick);
        root.addView(topBar);

        // Effect Pill Bar
        LinearLayout effectCard = new LinearLayout(this);
        effectCard.setOrientation(LinearLayout.HORIZONTAL);
        effectCard.setGravity(Gravity.CENTER_VERTICAL);
        effectCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        effectCard.setBackground(roundRect(cardBg(), 12));
        effectCard.setOnClickListener(v -> showEffectPickerDialog());

        effectTitleView = label("", 13, false, textPrimary());
        effectTitleView.setSingleLine(true);
        effectTitleView.setEllipsize(TextUtils.TruncateAt.END);
        effectCard.addView(effectTitleView, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView switchHint = label("切换 ›", 13, true, brandGreen());
        switchHint.setPadding(dp(10), 0, 0, 0);
        effectCard.addView(switchHint);

        LinearLayout.LayoutParams effectLp = new LinearLayout.LayoutParams(-1, -2);
        effectLp.setMargins(dp(14), dp(2), dp(14), dp(8));
        root.addView(effectCard, effectLp);

        // Permission Card (hidden once granted)
        permissionCard = new LinearLayout(this);
        permissionCard.setOrientation(LinearLayout.VERTICAL);
        permissionCard.setPadding(dp(18), dp(22), dp(18), dp(22));
        permissionCard.setBackground(roundRect(cardBg(), 16));
        permissionTitle = label("授权读取手机相册，像相册一样直接选图", 15, true, textPrimary());
        TextView permDesc = label("纯手机离线 GPU 生成动态实况图，不上传云端。也可不授权直接点右上角「系统选图」。", 12, false, textSecondary());
        LinearLayout permBtns = new LinearLayout(this);
        permBtns.setOrientation(LinearLayout.HORIZONTAL);

        Button grantBtn = actionBtn("读取本地相册", true);
        grantBtn.setOnClickListener(v -> requestGalleryPermission());
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        b1.setMargins(0, 0, dp(8), 0);
        permBtns.addView(grantBtn, b1);

        Button pickBtn = actionBtn("系统选图", false);
        pickBtn.setOnClickListener(v -> openSystemPicker());
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        permBtns.addView(pickBtn, b2);

        permissionCard.addView(permissionTitle);
        LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(-1, -2);
        pdLp.setMargins(0, dp(6), 0, dp(14));
        permissionCard.addView(permDesc, pdLp);
        permissionCard.addView(permBtns);

        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(-1, -2);
        pcLp.setMargins(dp(14), dp(8), dp(14), dp(8));
        root.addView(permissionCard, pcLp);

        // Photo Grid
        gridView = new GridView(this);
        gridView.setNumColumns(3);
        gridView.setHorizontalSpacing(dp(3));
        gridView.setVerticalSpacing(dp(3));
        gridView.setPadding(dp(3), 0, dp(3), dp(3));
        gridView.setClipToPadding(false);
        adapter = new PhotoGridAdapter();
        gridView.setAdapter(adapter);
        gridView.setOnItemClickListener((parent, view, position, id) -> toggleSelection(visiblePhotos.get(position).uri));
        gridView.setOnItemLongClickListener((parent, view, position, id) -> {
            showPhotoPreviewDialog(visiblePhotos.get(position).uri);
            return true;
        });
        root.addView(gridView, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Sticky Bottom Bar
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(cardBg());
        bottomBar.setPadding(dp(14), dp(10), dp(14), dp(12));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        selectedCountView = label("点击上方照片多选，转换后直接分享就是动态实况图", 12, false, textSecondary());
        statusRow.addView(selectedCountView, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView clearBtn = label("清空", 12, true, brandGreen());
        clearBtn.setPadding(dp(12), dp(4), dp(4), dp(4));
        clearBtn.setOnClickListener(v -> {
            selectedUris.clear();
            updateBottomBar();
            adapter.notifyDataSetChanged();
        });
        statusRow.addView(clearBtn);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(-1, -2);
        srLp.setMargins(0, 0, 0, dp(8));
        bottomBar.addView(statusRow, srLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        saveOnlyBtn = actionBtn("仅存相册", false);
        saveOnlyBtn.setOnClickListener(v -> startBatchConvert(new ArrayList<>(selectedUris), false));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        sLp.setMargins(0, 0, dp(8), 0);
        btnRow.addView(saveOnlyBtn, sLp);

        shareBtn = actionBtn("转实况并分享", true);
        shareBtn.setOnClickListener(v -> startBatchConvert(new ArrayList<>(selectedUris), true));
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(0, dp(46), 1.6f);
        btnRow.addView(shareBtn, shLp);

        bottomBar.addView(btnRow);
        root.addView(bottomBar);
        updateBottomBar();
        return root;
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

    private boolean isPartialPermissionOnly() {
        return Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
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
        if (isPartialPermissionOnly()) {
            permissionTitle.setText("当前仅授权部分照片（点击下方按钮可随时增选照片）");
            permissionCard.setVisibility(View.VISIBLE);
        } else {
            permissionCard.setVisibility(View.GONE);
        }
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
                MediaStore.Images.Media.MIME_TYPE
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
            int limit = 600;
            while (cursor.moveToNext() && list.size() < limit) {
                long id = cursor.getLong(idCol);
                String bucket = bucketCol >= 0 ? cursor.getString(bucketCol) : "";
                if (bucket == null || bucket.trim().isEmpty()) bucket = "其他";
                Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                list.add(new GalleryPhoto(id, contentUri, bucket));
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
        albumChip.setText((activeBucket.isEmpty() ? "全部照片" : activeBucket) + " ▾");
        adapter.notifyDataSetChanged();
    }

    private void showBucketDialog() {
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
                .setTitle("选择本地相册目录")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    activeBucket = keys.get(which);
                    applyBucketFilter();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openSystemPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择图片转为实况"), REQ_PICK_IMAGES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGES && resultCode == RESULT_OK && data != null) {
            List<Uri> picked = extractUrisFromIntent(data);
            if (!picked.isEmpty()) {
                promptPickedConversion(picked);
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
            promptPickedConversion(incoming);
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
        if (single != null && !uris.contains(single)) {
            uris.add(single);
        }
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

    private void promptPickedConversion(List<Uri> uris) {
        MotionPreset.PresetInfo info = MotionPreset.findById(currentMode());
        new AlertDialog.Builder(this)
                .setTitle("已选择 " + uris.size() + " 张图片")
                .setMessage("当前动态效果：" + info.title + "\n转换后将写入系统相册「实况工坊」文件夹，可直接分享为动态图。")
                .setNeutralButton("换效果", (d, w) -> {
                    showEffectPickerDialog();
                })
                .setNegativeButton("仅存相册", (d, w) -> startBatchConvert(uris, false))
                .setPositiveButton("转实况并分享", (d, w) -> startBatchConvert(uris, true))
                .show();
    }

    private void toggleSelection(Uri uri) {
        if (selectedUris.contains(uri)) {
            selectedUris.remove(uri);
        } else {
            if (selectedUris.size() >= MAX_SELECT) {
                toast("单次最多选择 " + MAX_SELECT + " 张");
                return;
            }
            selectedUris.add(uri);
        }
        updateBottomBar();
        adapter.notifyDataSetChanged();
    }

    private void updateBottomBar() {
        int count = selectedUris.size();
        if (count == 0) {
            selectedCountView.setText("点击照片多选，转成实况后可直接发小红书 / 抖音 / 微信");
            saveOnlyBtn.setEnabled(false);
            shareBtn.setEnabled(false);
            shareBtn.setText("转实况并分享");
            saveOnlyBtn.setAlpha(0.45f);
            shareBtn.setAlpha(0.45f);
        } else {
            selectedCountView.setText("已选 " + count + " 张静图（离线转换，不走云端）");
            saveOnlyBtn.setEnabled(true);
            shareBtn.setEnabled(true);
            shareBtn.setText("转实况并分享 (" + count + ")");
            saveOnlyBtn.setAlpha(1f);
            shareBtn.setAlpha(1f);
        }
    }

    private void startBatchConvert(List<Uri> inputs, boolean shareAfter) {
        if (inputs.isEmpty()) return;
        final String mode = currentMode();
        final float ratio = currentRatio();
        final float duration = currentDuration();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(18), dp(20), dp(16));
        TextView title = label("正在手机本地生成动态实况图…", 15, true, textPrimary());
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
                    Uri saved = LivePhotoConverter.convertAndSave(
                            MainActivity.this, inputs.get(i), mode, ratio, duration, step
                    );
                    outputs.add(saved);
                } catch (Exception e) {
                    failure = e;
                    break;
                }
            }
            final Exception err = failure;
            mainHandler.post(() -> {
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (err != null) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("转换中断")
                            .setMessage("失败原因：" + err.getMessage())
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                selectedUris.clear();
                updateBottomBar();
                loadGalleryIfPermitted();
                if (shareAfter) {
                    launchSystemShare(outputs);
                } else {
                    toast("已将 " + outputs.size() + " 张实况图保存到相册「实况工坊」");
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

    private void showEffectPickerDialog() {
        String[] titles = new String[MotionPreset.PRESETS.length];
        String curMode = currentMode();
        int checked = 0;
        for (int i = 0; i < MotionPreset.PRESETS.length; i++) {
            if (MotionPreset.PRESETS[i].id.equals(curMode)) checked = i;
            titles[i] = MotionPreset.PRESETS[i].title + "\n" + MotionPreset.PRESETS[i].desc;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择实况动态效果")
                .setSingleChoiceItems(titles, checked, (d, which) -> {
                    prefs().edit().putString(KEY_MODE, MotionPreset.PRESETS[which].id).apply();
                    refreshEffectLabel();
                    d.dismiss();
                })
                .setNeutralButton("调幅度与时长", (d, w) -> showStrengthDialog())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showStrengthDialog() {
        String[] items = {
                "极微自然（1.0% 幅度 · 1.3 秒）",
                "标准推荐（1.8% 幅度 · 1.6 秒）",
                "轻柔明显（3.2% 幅度 · 2.2 秒）"
        };
        float r = currentRatio();
        int checked = r < 1.013f ? 0 : (r > 1.024f ? 2 : 1);
        new AlertDialog.Builder(this)
                .setTitle("调整微动幅度与时长")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    float newRatio = which == 0 ? 1.010f : (which == 2 ? 1.032f : 1.018f);
                    float newDur = which == 0 ? 1.3f : (which == 2 ? 2.2f : 1.6f);
                    prefs().edit().putFloat(KEY_RATIO, newRatio).putFloat(KEY_DURATION, newDur).apply();
                    refreshEffectLabel();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPhotoPreviewDialog(Uri uri) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setMinimumHeight(dp(260));
        ioExecutor.execute(() -> {
            Bitmap bmp = decodeScaledBitmap(getContentResolver(), uri, 1200);
            if (bmp != null) {
                mainHandler.post(() -> iv.setImageBitmap(bmp));
            }
        });
        new AlertDialog.Builder(this)
                .setView(iv)
                .setNegativeButton("关闭", null)
                .setPositiveButton("选中这张", (d, w) -> {
                    if (!selectedUris.contains(uri)) toggleSelection(uri);
                })
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
            try (InputStream is2 = resolver.openInputStream(uri)) {
                if (is2 == null) return null;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                return BitmapFactory.decodeStream(is2, null, o);
            }
        } catch (Exception ignored) {
            return null;
        }
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

    private void refreshEffectLabel() {
        MotionPreset.PresetInfo info = MotionPreset.findById(currentMode());
        float pct = (currentRatio() - 1.0f) * 100f;
        effectTitleView.setText(String.format(Locale.US, "效果：%s · %.1f%% · %.1f秒",
                info.title, pct, currentDuration()));
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
            View border;
            if (convertView == null) {
                int cellSize = Math.max(100, (getResources().getDisplayMetrics().widthPixels - dp(12)) / 3);
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
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(brandGreen());
                badge.setBackground(bg);
                FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
                bLp.setMargins(0, dp(6), dp(6), 0);
                cell.addView(badge, bLp);

                cell.setTag(new ViewHolder(img, badge, border));
            } else {
                cell = (FrameLayout) convertView;
            }
            ViewHolder vh = (ViewHolder) cell.getTag();
            GalleryPhoto photo = visiblePhotos.get(position);
            int selIdx = selectedUris.indexOf(photo.uri);
            if (selIdx >= 0) {
                vh.badge.setVisibility(View.VISIBLE);
                vh.badge.setText(String.valueOf(selIdx + 1));
                vh.border.setVisibility(View.VISIBLE);
            } else {
                vh.badge.setVisibility(View.GONE);
                vh.border.setVisibility(View.GONE);
            }

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

    private static final class ViewHolder {
        final ImageView img;
        final TextView badge;
        final View border;
        long photoId;

        ViewHolder(ImageView img, TextView badge, View border) {
            this.img = img;
            this.badge = badge;
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
        return isDark ? Color.rgb(16, 18, 22) : Color.rgb(245, 247, 250);
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
