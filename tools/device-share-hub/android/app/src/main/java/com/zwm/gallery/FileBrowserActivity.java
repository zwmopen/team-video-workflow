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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Simple file-transfer browser backed by the already selected SAF folder. */
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

        Button mode = button("文件浏览  ·  切换到小红书笔记");
        mode.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(-1, dp(40));
        modeParams.setMargins(dp(12), dp(6), dp(12), dp(10));
        screen.addView(mode, modeParams);

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
            list.addView(row("‹  返回上一级", "当前目录", true, v -> {
                path.pop();
                refresh();
            }), rowParams());
        }
        for (Entry entry : entries) {
            String detail = entry.directory ? "文件夹" : fileDetail(entry);
            list.addView(row((entry.directory ? "▰  " : fileSymbol(entry.mime)) + entry.name,
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

    private View row(String title, String detail, boolean folder, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(round(Color.WHITE, 15));
        TextView name = text(title, 16, folder);
        name.setMaxLines(2);
        row.addView(name);
        TextView meta = text(detail, 12, false);
        meta.setTextColor(Color.GRAY);
        meta.setPadding(0, dp(4), 0, 0);
        row.addView(meta);
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

    private String fileSymbol(String mime) {
        if (mime == null) return "□  ";
        if (mime.startsWith("image/")) return "▧  ";
        if (mime.startsWith("text/")) return "≡  ";
        if (mime.contains("zip") || mime.contains("archive")) return "▦  ";
        return "□  ";
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(8));
        return params;
    }

    private LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMargins(dp(4), 0, 0, 0);
        return params;
    }

    private ImageButton iconButton(int resource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setColorFilter(Color.rgb(54, 86, 72));
        button.setBackground(round(Color.rgb(231, 239, 233), 24));
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
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
