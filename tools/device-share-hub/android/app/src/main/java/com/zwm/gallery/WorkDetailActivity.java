package com.zwm.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;

/** Folder-like preview for one work. Long press an image to start multi-select. */
public final class WorkDetailActivity extends Activity {
    public static final String EXTRA_WORK_ID = "workId";

    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private WorkLibrary library;
    private WorkLibrary.WorkEntry work;
    private LinearLayout content;
    private LinearLayout actionBar;
    private TextView title;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            library = new WorkLibrary(new File(getFilesDir(), "work-library"));
            work = library.getActive(getIntent().getStringExtra(EXTRA_WORK_ID));
            if (work == null) throw new IllegalStateException("作品不存在或已在回收站");
            setContentView(ScreenInsets.protect(buildUi()));
            render();
        } catch (Exception error) {
            Toast.makeText(this, "无法打开作品：" + error.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));
        root.setBackgroundColor(Color.rgb(246, 244, 240));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", Color.WHITE, Color.rgb(45, 45, 42));
        back.setTextSize(26);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        title = text("作品", 20, true);
        title.setMaxLines(2);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.setMargins(dp(12), 0, 0, 0);
        top.addView(title, tp);
        root.addView(top);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        actionBar = new LinearLayout(this);
        actionBar.setGravity(Gravity.CENTER);
        actionBar.setPadding(0, dp(10), 0, 0);
        root.addView(actionBar);
        return root;
    }

    private void render() {
        if (work == null || content == null) return;
        selected.retainAll(work.images);
        title.setText(work.name + "  ·  " + work.images.size());
        content.removeAllViews();

        LinearLayout textCard = new LinearLayout(this);
        textCard.setOrientation(LinearLayout.VERTICAL);
        textCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        textCard.setBackground(round(Color.WHITE, 18));
        TextView fileName = text("文案.txt", 15, true);
        fileName.setTextColor(Color.rgb(53, 105, 82));
        textCard.addView(fileName);
        TextView preview = text(work.text.trim().isEmpty() ? "没有文案" : work.text.trim(), 13, false);
        preview.setTextColor(Color.rgb(92, 89, 84));
        preview.setMaxLines(3);
        preview.setPadding(0, dp(8), 0, 0);
        textCard.addView(preview);
        textCard.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("作品文案", work.text));
            Toast.makeText(this, "复制成功", Toast.LENGTH_SHORT).show();
        });
        content.addView(textCard, margins(0, dp(16), 0, dp(14)));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        for (String name : work.images) grid.addView(imageCard(name), gridParams());
        content.addView(grid);

        ArrayList<Attachment> attachments = loadAttachments();
        if (!attachments.isEmpty()) {
            TextView heading = text("其他文件  " + attachments.size(), 15, true);
            heading.setTextColor(Color.rgb(54, 86, 72));
            content.addView(heading, margins(dp(4), dp(18), dp(4), dp(6)));
            for (Attachment attachment : attachments) {
                content.addView(fileCard(attachment), margins(0, dp(4), 0, dp(4)));
            }
        }

        try {
            int trashCount = library.imageTrashCount(work.id);
            if (trashCount > 0) {
                Button restore = button("图片回收站  " + trashCount + "  ·  全部恢复",
                        Color.WHITE, Color.rgb(71, 104, 87));
                restore.setOnClickListener(v -> restoreImages());
                content.addView(restore, margins(0, dp(12), 0, dp(18)));
            }
        } catch (Exception ignored) { }
        renderActions();
    }

    private View imageCard(String name) {
        boolean chosen = selected.contains(name);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(5), dp(5), dp(5), dp(5));
        frame.setBackground(round(chosen ? Color.rgb(38, 145, 94) : Color.WHITE, 18));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(loadThumbnail(new File(work.directory, name), 420));
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = text(chosen ? "✓" : "", 16, true);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(Color.WHITE);
        badge.setBackground(round(Color.rgb(38, 145, 94), 16));
        badge.setVisibility(chosen ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.END | Gravity.TOP);
        bp.setMargins(0, dp(8), dp(8), 0);
        frame.addView(badge, bp);
        frame.setOnClickListener(v -> {
            if (selected.isEmpty()) showLargeImage(name);
            else toggle(name);
        });
        frame.setOnLongClickListener(v -> { toggle(name); return true; });
        return frame;
    }

    private View fileCard(Attachment attachment) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(round(Color.WHITE, 16));
        TextView type = text(fileType(attachment.name), 12, true);
        type.setGravity(Gravity.CENTER);
        type.setTextColor(Color.rgb(54, 105, 82));
        type.setBackground(round(Color.rgb(231, 239, 233), 12));
        row.addView(type, new LinearLayout.LayoutParams(dp(48), dp(42)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(attachment.name, 14, true);
        name.setMaxLines(2);
        info.addView(name);
        String detail = attachment.size > 0 ? Formatter.formatShortFileSize(this, attachment.size) : "点按打开";
        TextView size = text(detail, 12, false);
        size.setTextColor(Color.GRAY);
        info.addView(size);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        ip.setMargins(dp(12), 0, 0, 0);
        row.addView(info, ip);
        row.setOnClickListener(v -> openAttachment(attachment));
        return row;
    }

    private ArrayList<Attachment> loadAttachments() {
        ArrayList<Attachment> result = new ArrayList<>();
        if (work.sourceDocumentId.isEmpty()) return result;
        String stored = getSharedPreferences("device_share", MODE_PRIVATE).getString("libraryTreeUri", "");
        if (stored.isEmpty()) return result;
        try {
            Uri tree = Uri.parse(stored);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, work.sourceDocumentId);
            String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE};
            try (Cursor cursor = getContentResolver().query(children, columns, null, null, null)) {
                if (cursor == null) return result;
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                    if (name == null || name.startsWith(".zwm-")
                            || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)
                            || (mime != null && mime.startsWith("image/"))
                            || WorkRules.isSupportedImage(name)) continue;
                    result.add(new Attachment(name, mime == null ? "application/octet-stream" : mime,
                            size, DocumentsContract.buildDocumentUriUsingTree(tree, documentId)));
                }
            }
            result.sort((left, right) -> WorkRules.compareNatural(left.name, right.name));
        } catch (Exception error) {
            DiagnosticLog.write(this, "detail_files_unavailable", error.getClass().getSimpleName());
        }
        return result;
    }

    private void openAttachment(Attachment attachment) {
        Intent open = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(attachment.uri, attachment.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        open.setClipData(ClipData.newRawUri(attachment.name, attachment.uri));
        try { startActivity(Intent.createChooser(open, "打开“" + attachment.name + "”")); }
        catch (Exception error) { Toast.makeText(this, "手机里没有可打开此文件的应用", Toast.LENGTH_LONG).show(); }
    }

    private String fileType(String name) {
        int dot = name.lastIndexOf('.');
        String value = dot >= 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "文件";
        return value.length() > 5 ? "文件" : value;
    }

    private void renderActions() {
        actionBar.removeAllViews();
        if (selected.isEmpty()) {
            TextView hint = text("点图片预览，长按可多选", 12, false);
            hint.setTextColor(Color.GRAY);
            actionBar.addView(hint);
            return;
        }
        ImageButton delete = imageActionButton(R.drawable.ic_album_trash, "移到回收站",
                Color.rgb(255, 241, 239), Color.rgb(188, 66, 60));
        ImageButton share = imageActionButton(R.drawable.ic_album_share, "分享到其他应用",
                Color.WHITE, Color.rgb(54, 86, 72));
        ImageButton send = imageActionButton(R.drawable.ic_album_transfer, "传送到其他设备",
                Color.rgb(38, 145, 94), Color.WHITE);
        delete.setOnClickListener(v -> confirmDelete());
        share.setOnClickListener(v -> shareSelected());
        send.setOnClickListener(v -> sendSelected());
        actionBar.addView(delete, iconActionParams(0));
        actionBar.addView(share, iconActionParams(dp(18)));
        actionBar.addView(send, iconActionParams(dp(18)));
    }

    private void toggle(String name) {
        if (selected.contains(name)) selected.remove(name); else selected.add(name);
        render();
    }

    private void showLargeImage(String name) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        image.setImageBitmap(loadThumbnail(new File(work.directory, name), 1800));
        new AlertDialog.Builder(this).setView(image).setPositiveButton("关闭", null).show();
    }

    private void shareSelected() {
        startActivity(new Intent(this, ShareActivity.class)
                .putExtra(ShareActivity.EXTRA_WORK_ID, work.id)
                .putStringArrayListExtra(ShareActivity.EXTRA_IMAGE_NAMES, new ArrayList<>(selected)));
    }

    private void sendSelected() {
        ArrayList<String> paths = new ArrayList<>();
        for (String name : selected) paths.add(new File(work.directory, name).getAbsolutePath());
        startActivity(new Intent(this, TransferActivity.class)
                .putStringArrayListExtra(TransferActivity.EXTRA_LOCAL_PATHS, paths));
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("移除 " + selected.size() + " 张图片？")
                .setMessage("图片会进入本作品的图片回收站，保留 7 天；不会改动你原来选择的作品文件夹。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移到回收站", (dialog, which) -> {
                    try {
                        int count = library.moveImagesToTrash(work.id, new ArrayList<>(selected), LocalDate.now());
                        selected.clear();
                        reload();
                        Toast.makeText(this, "已移到回收站 " + count + " 张", Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        Toast.makeText(this, "删除失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void restoreImages() {
        try {
            int count = library.restoreAllImages(work.id);
            reload();
            Toast.makeText(this, "已恢复 " + count + " 张", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "恢复失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void reload() {
        if (library == null || work == null) return;
        try { work = library.getActive(work.id); render(); }
        catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private Bitmap loadThumbnail(File file, int target) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private GridLayout.LayoutParams gridParams() {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        int available = getResources().getDisplayMetrics().widthPixels - dp(36) - dp(16);
        int cardWidth = Math.max(dp(120), available / 2);
        p.width = cardWidth; p.height = cardWidth * 4 / 3;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
        p.setMargins(dp(4), dp(4), dp(4), dp(4)); return p;
    }
    private LinearLayout.LayoutParams iconActionParams(int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(58), dp(52));
        p.setMargins(left, 0, 0, 0); return p;
    }
    private ImageButton imageActionButton(int image, String description, int background, int tint) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(image);
        button.setImageTintList(ColorStateList.valueOf(tint));
        button.setBackground(round(background, 17));
        button.setPadding(dp(15), dp(12), dp(15), dp(12));
        button.setContentDescription(description);
        return button;
    }
    private Button button(String label, int background, int foreground) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(foreground);
        b.setBackground(round(background, 16)); return b;
    }
    private TextView text(String value, int size, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(l, t, r, b); return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class Attachment {
        final String name;
        final String mime;
        final long size;
        final Uri uri;
        Attachment(String name, String mime, long size, Uri uri) {
            this.name = name; this.mime = mime; this.size = size; this.uri = uri;
        }
    }
}
