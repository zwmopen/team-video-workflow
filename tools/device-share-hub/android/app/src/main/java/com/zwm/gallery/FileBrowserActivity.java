package com.zwm.gallery;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** System-like file browser backed by the already selected SAF folder. */
public final class FileBrowserActivity extends Activity {
    private static final String PREFS = "device_share";
    private static final String PREF_TREE_URI = "libraryTreeUri";

    private final ArrayDeque<String> path = new ArrayDeque<>();
    private LinearLayout list;
    private TextView heading;
    private TextView status;
    private Uri tree;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(ScreenInsets.protect(buildUi()));
        if (android.os.Build.VERSION.SDK_INT >= 33) Api33Back.register(this);
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, "");
        if (stored.isEmpty()) {
            status.setText("还没有设置文件夹，请先到设置中选择作品文件夹");
            return;
        }
        tree = Uri.parse(stored);
        path.push(DocumentsContract.getTreeDocumentId(tree));
        refresh();
    }

    private View buildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(18), dp(14), dp(18), dp(28));
        screen.setBackgroundColor(Color.rgb(246, 244, 240));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        heading = text("文件", 26, true);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton noteMode = iconButton(R.drawable.ic_album_share, "切换到小红书笔记");
        noteMode.setOnClickListener(v -> finish());
        top.addView(noteMode, iconParams());
        ImageButton send = iconButton(R.drawable.ic_album_transfer, "发送文件");
        send.setOnClickListener(v -> startActivity(new Intent(this, TransferActivity.class)));
        top.addView(send, iconParams());
        ImageButton refresh = iconButton(R.drawable.ic_album_refresh, "刷新文件");
        refresh.setOnClickListener(v -> { refresh(); toast("已刷新"); });
        top.addView(refresh, iconParams());
        ImageButton settings = iconButton(R.drawable.ic_album_settings, "设置");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings, iconParams());
        screen.addView(top);

        status = text("正在读取…", 13, false);
        status.setTextColor(Color.rgb(83, 99, 89));
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        status.setBackground(round(Color.rgb(231, 239, 233), 14));
        screen.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.setMargins(0, dp(10), 0, 0);
        screen.addView(scroll, scrollParams);
        return screen;
    }

    private void refresh() {
        if (tree == null || path.isEmpty()) return;
        String current = path.peek();
        new Thread(() -> {
            try {
                List<Entry> entries = readChildren(current);
                runOnUiThread(() -> render(entries));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("读取失败：" + error.getMessage()));
            }
        }, "file-browser").start();
    }

    private List<Entry> readChildren(String parentId) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        ArrayList<Entry> result = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("系统没有返回文件列表");
            while (cursor.moveToNext()) {
                result.add(new Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.isNull(3) ? 0 : cursor.getLong(3)));
            }
        }
        result.sort((left, right) -> {
            if (left.directory != right.directory) return left.directory ? -1 : 1;
            return WorkRules.compareNatural(left.name, right.name);
        });
        return result;
    }

    private void render(List<Entry> entries) {
        list.removeAllViews();
        heading.setText(path.size() > 1 ? "文件夹" : "文件");
        status.setText(entries.size() + " 项");
        if (path.size() > 1) {
            list.addView(row(R.drawable.ic_file_up, Color.rgb(91, 107, 98),
                    "返回上一级", "当前目录", true, v -> {
                path.pop();
                refresh();
            }), rowParams());
        }
        for (Entry entry : entries) {
            String detail = entry.directory ? "文件夹" : fileDetail(entry);
            list.addView(row(fileIcon(entry), fileIconColor(entry), entry.name,
                    detail, entry.directory, v -> open(entry)), rowParams());
        }
        if (entries.isEmpty()) {
            TextView empty = text("这个文件夹是空的", 15, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(0, dp(50), 0, 0);
            list.addView(empty);
        }
    }

    private View row(int iconResource, int iconColor, String title, String detail,
                     boolean folder, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(round(Color.WHITE, 15));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setColorFilter(iconColor);
        icon.setContentDescription(folder ? "文件夹" : "文件");
        LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(dp(30), dp(30));
        iconLayout.setMargins(0, 0, dp(13), 0);
        row.addView(icon, iconLayout);
        LinearLayout textStack = new LinearLayout(this);
        textStack.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 16, folder);
        name.setMaxLines(2);
        textStack.addView(name);
        TextView meta = text(detail, 12, false);
        meta.setTextColor(Color.GRAY);
        meta.setPadding(0, dp(4), 0, 0);
        textStack.addView(meta);
        row.addView(textStack, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(click);
        return row;
    }

    private void open(Entry entry) {
        if (entry.directory) {
            path.push(entry.id);
            refresh();
            return;
        }
        try {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id);
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, entry.mime == null ? "*/*" : entry.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, "打开文件"));
        } catch (Exception error) {
            toast("没有能打开这个文件的应用");
        }
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (path.size() > 1) {
            path.pop();
            refresh();
        } else super.onBackPressed();
    }

    private static final class Api33Back {
        @TargetApi(33)
        static void register(FileBrowserActivity activity) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, activity::handleBack);
        }
    }

    private String fileDetail(Entry entry) {
        if (entry.size <= 0) return entry.mime == null ? "文件" : entry.mime;
        double size = entry.size;
        String unit = "B";
        if (size >= 1024) { size /= 1024; unit = "KB"; }
        if (size >= 1024) { size /= 1024; unit = "MB"; }
        if (size >= 1024) { size /= 1024; unit = "GB"; }
        return String.format(java.util.Locale.CHINA, size >= 10 ? "%.0f %s" : "%.1f %s", size, unit);
    }

    private int fileIcon(Entry entry) {
        if (entry.directory) return R.drawable.ic_file_folder;
        String mime = entry.mime == null ? "" : entry.mime.toLowerCase(java.util.Locale.ROOT);
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

    private int fileIconColor(Entry entry) {
        int icon = fileIcon(entry);
        if (icon == R.drawable.ic_file_folder || icon == R.drawable.ic_file_up) {
            return Color.rgb(222, 164, 64);
        }
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
                ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(8));
        return params;
    }

    private LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        params.setMargins(dp(2), 0, 0, 0);
        return params;
    }

    private ImageButton iconButton(int resource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setColorFilter(Color.rgb(54, 86, 72));
        button.setBackground(round(Color.rgb(231, 239, 233), 22));
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setContentDescription(description);
        return button;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.rgb(47, 48, 46));
        button.setBackground(round(Color.rgb(232, 230, 225), 14));
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

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class Entry {
        final String id;
        final String name;
        final String mime;
        final boolean directory;
        final long size;

        Entry(String id, String name, String mime, long size) {
            this.id = id;
            this.name = name == null ? "未命名" : name;
            this.mime = mime;
            this.directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
            this.size = size;
        }
    }
}
