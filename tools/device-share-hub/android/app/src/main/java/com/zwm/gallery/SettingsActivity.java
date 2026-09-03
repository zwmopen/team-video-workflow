package com.zwm.gallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.content.pm.PackageManager;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private static final String PREFS = "device_share";
    private static final int REQUEST_TREE = 71;
    private static final int REQUEST_NOTIFICATIONS = 72;
    private EditText deviceName;
    private TextView pathText;
    private SettingSwitchRow soundSwitch;
    private TextView moveAfterText;
    private TextView deleteAfterText;
    private SettingSwitchRow autoReceiveSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(ScreenInsets.protect(buildUi()));
    }

    private SpringScrollView buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(36));
        root.setBackgroundColor(Color.rgb(242, 242, 247));

        TextView title = text("设置", 26, true);
        root.addView(title);
        TextView subtitle = text("常用选项集中在这里，修改后立即生效。", 12, false);
        subtitle.setTextColor(Color.rgb(118, 118, 123));
        root.addView(subtitle, margins(0, dp(4), 0, dp(20)));

        root.addView(label("手机名称（电脑端显示）"));
        deviceName = new EditText(this);
        deviceName.setSingleLine(true);
        deviceName.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("deviceName", "我的手机"));
        deviceName.setBackground(round(Color.WHITE, 14));
        deviceName.setPadding(dp(14), 0, dp(14), 0);
        root.addView(deviceName, new LinearLayout.LayoutParams(-1, dp(52)));
        Button saveName = button("保存名称", true);
        saveName.setOnClickListener(v -> saveName());
        root.addView(saveName, margins(0, dp(10), 0, dp(20)));

        root.addView(label("作品文件夹"));
        pathText = text(currentPathName(), 16, true);
        pathText.setBackground(round(Color.WHITE, 14));
        pathText.setPadding(dp(14), dp(16), dp(14), dp(16));
        root.addView(pathText);
        Button choose = button("更改作品文件夹", false);
        choose.setOnClickListener(v -> chooseFolder());
        root.addView(choose, margins(0, dp(10), 0, dp(20)));

        root.addView(label("接收"));
        autoReceiveSwitch = settingSwitch(
                "自动接收",
                "开启时允许电脑和其他设备投送内容；关闭后只保留在线发现和本机查看",
                OnlineService.isAutoReceiveEnabled(this));
        autoReceiveSwitch.setOnCheckedChangeListener((button, enabled) -> {
            OnlineService.setAutoReceiveEnabled(this, enabled);
            toast(enabled ? "自动接收已打开" : "自动接收已关闭");
        });
        root.addView(autoReceiveSwitch, settingMargins(dp(20)));

        root.addView(label("自动整理"));
        CleanupSettings.Values cleanup = CleanupSettings.read(this);
        LinearLayout moveRow = cleanupRow("自动移入回收站", cleanup.moveMinutes / 60);
        moveAfterText = (TextView) moveRow.getChildAt(1);
        moveRow.setOnClickListener(v -> showCleanupPicker(true));
        root.addView(moveRow, settingMargins(dp(8)));
        LinearLayout deleteRow = cleanupRow("自动彻底删除", cleanup.deleteMinutes / 60);
        deleteAfterText = (TextView) deleteRow.getChildAt(1);
        deleteRow.setOnClickListener(v -> showCleanupPicker(false));
        root.addView(deleteRow, settingMargins(dp(20)));

        root.addView(label("提醒"));
        soundSwitch = settingSwitch("声音通知", "收到文件时显示通知并响铃",
                getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("soundNotificationsEnabled", false));
        soundSwitch.setOnCheckedChangeListener((button, enabled) -> changeSoundNotifications(enabled));
        root.addView(soundSwitch, settingMargins(dp(10)));
        SettingSwitchRow vibrationSwitch = settingSwitch("震动提醒", "开始、完成或失败时震动",
                getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("vibrationEnabled", false));
        vibrationSwitch.setOnCheckedChangeListener((button, enabled) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("vibrationEnabled", enabled).apply();
            if (enabled) vibrateOnce();
        });
        root.addView(vibrationSwitch, settingMargins(dp(20)));

        root.addView(label("软件"));
        SettingSwitchRow autoUpdateSwitch = settingSwitch(
                "自动检查更新",
                "每次打开相册时检查；确认后应用内校验下载，再点通知安装",
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean(UpdateChecker.PREF_AUTO_UPDATE_ENABLED, true));
        autoUpdateSwitch.setOnCheckedChangeListener((button, enabled) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(UpdateChecker.PREF_AUTO_UPDATE_ENABLED, enabled).apply());
        root.addView(autoUpdateSwitch, settingMargins(dp(10)));
        String lan = UpdateChecker.getLanServerUrl(this);
        String sourceLabel = lan.isEmpty() ? "更新源  优先电脑局域网 / 备用云端" : "更新源  电脑局域网 (" + lan.replace("http://", "") + ")";
        TextView updateEntry = text(sourceLabel, 15, true);
        updateEntry.setPadding(dp(14), dp(16), dp(14), dp(16));
        updateEntry.setBackground(round(Color.WHITE, 14));
        root.addView(updateEntry, settingMargins(dp(20)));
        TextView version = text("当前版本  " + UpdateChecker.currentVersion(this), 16, true);
        version.setPadding(dp(14), dp(16), dp(14), dp(16));
        version.setBackground(round(Color.WHITE, 14));
        root.addView(version);
        Button update = button("检查更新", false);
        update.setOnClickListener(v -> UpdateChecker.check(this, false));
        root.addView(update, margins(0, dp(10), 0, dp(10)));
        Button about = button("软件说明", false);
        about.setOnClickListener(v -> showAbout());
        root.addView(about, margins(0, 0, 0, dp(10)));
        Button diagnostics = button("复制诊断信息", false);
        diagnostics.setOnClickListener(v -> copyDiagnostics());
        root.addView(diagnostics);

        Button back = button("完成", true);
        back.setOnClickListener(v -> finish());
        root.addView(back, margins(0, dp(28), 0, 0));

        SpringScrollView scroll = new SpringScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void saveName() {
        String value = deviceName.getText().toString().trim();
        if (value.isEmpty()) { toast("名称不能为空"); return; }
        if (value.length() > 30) { toast("名称最多 30 个字"); return; }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("deviceName", value).apply();
        DiagnosticLog.write(this, "device_name_saved", value);
        toast("已保存，电脑端会自动刷新");
    }

    private void showCleanupPicker(boolean editingMove) {
        CleanupSettings.Values current = CleanupSettings.read(this);
        int currentHours = (editingMove ? current.moveMinutes : current.deleteMinutes) / 60;
        int[] hours = {0, 1, 3, 6, 24};
        String[] labels = {"立刻", "1 小时后", "3 小时后", "6 小时后", "24 小时后", "自定义…"};
        int checked = -1;
        for (int index = 0; index < hours.length; index++) {
            if (hours[index] == currentHours) checked = index;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editingMove ? "自动移入回收站" : "自动彻底删除")
                .setSingleChoiceItems(labels, checked, null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            if (position == hours.length) showCustomCleanupHours(editingMove, currentHours);
            else saveCleanupHours(editingMove, hours[position]);
        }));
        dialog.show();
    }

    private void showCustomCleanupHours(boolean editingMove, int currentHours) {
        EditText input = numberField(Integer.toString(currentHours));
        input.setSelectAllOnFocus(true);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(22), dp(4), dp(22), 0);
        holder.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        new AlertDialog.Builder(this)
                .setTitle("自定义小时数")
                .setMessage("请输入 0～720 的整数；0 表示立刻。")
                .setView(holder)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        saveCleanupHours(editingMove,
                                Integer.parseInt(input.getText().toString().trim()));
                    } catch (NumberFormatException error) {
                        toast("请输入 0～720 的整数小时");
                    }
                }).show();
    }

    private void saveCleanupHours(boolean editingMove, int hours) {
        if (hours < 0 || hours > 720) {
            toast("请输入 0～720 的整数小时");
            return;
        }
        CleanupSettings.Values current = CleanupSettings.read(this);
        int moveHours = editingMove ? hours : current.moveMinutes / 60;
        int deleteHours = editingMove ? Math.max(hours, current.deleteMinutes / 60) : hours;
        if (deleteHours < moveHours) {
            toast("彻底删除时间不能早于移入回收站");
            return;
        }
        if (!CleanupSettings.save(this, moveHours * 60, deleteHours * 60)) {
            toast("自动整理时间没有保存");
            return;
        }
        moveAfterText.setText(cleanupTimingText(moveHours));
        deleteAfterText.setText(cleanupTimingText(deleteHours));
        DiagnosticLog.write(this, "cleanup_settings_saved",
                "moveHours=" + moveHours + " deleteHours=" + deleteHours);
        toast("已保存");
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateChecker.reportDownloadProblem(this);
        startService(new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_REFRESH_STATUS));
    }

    private void changeSoundNotifications(boolean enabled) {
        if (!enabled) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("soundNotificationsEnabled", false).apply();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("soundNotificationsEnabled", true).apply();
        toast("声音通知已打开");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("soundNotificationsEnabled", granted).apply();
        soundSwitch.setChecked(granted);
        toast(granted ? "声音通知已打开" : "系统没有允许通知");
    }

    private void vibrateOnce() {
        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(120);
        }
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
        if (requestCode != REQUEST_TREE
                || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String name = treeName(tree);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("libraryTreeUri", tree.toString()).putString("libraryTreeName", name).apply();
            pathText.setText(name);
            toast("路径已保存");
        } catch (Exception error) {
            toast("保存路径失败：" + error.getMessage());
        }
    }

    private void showAbout() {
        String description =
                "把分散在电脑和手机里的素材，整理成随手可用的作品库。\n\n"
                + "核心场景\n"
                + "从电脑拖入文件、ZIP 或整个文件夹，手机自动接收并保留原目录结构；含图片和 TXT 的目录会被识别为作品，普通文件也可以继续传送、预览和分享。\n\n"
                + "作品工作流\n"
                + "点一次“复制并分享”，应用会立即记录一次，文案自动进入剪贴板，作品图片交给系统分享面板。再次分享会先提醒，避免误发同一个作品。默认 1 小时后进入回收站并从文件管理中彻底删除，时间可在设置中调整。\n\n"
                + "跨设备传送\n"
                + "电脑、Android 和 iPhone 在同一 Wi‑Fi 下自动发现。选择设备和文件即可传送，过程带有进度、完整性校验和结果提示。\n\n"
                + "隐私与权限\n"
                + "应用不再读取系统剪切板、不再监测系统截图、不再使用悬浮窗权限；只在你主动选择文件并点击传送，或主动点击复制分享时访问对应内容。\n\n"
                + "系统分享\n"
                + "其他应用的分享面板里可以选择“相册”，再选择在线设备。普通文件按真实文件夹存放，只有图片和文字一起直接分享时才进入分享准备。\n\n"
                + "设计思路\n"
                + "内容优先、操作尽量少、状态一眼可见。目录授权、作品记录、分享次数和回收站都保存在本机；覆盖升级会延续现有数据。坚果云只属于电脑端，不进入手机版。";

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), 0, dp(24), 0);

        TextView copy = text(description, 14, false);
        copy.setTextColor(Color.rgb(70, 70, 67));
        copy.setLineSpacing(dp(2), 1f);
        content.addView(copy, new LinearLayout.LayoutParams(-1, -2));

        TextView download = text(
                "手动下载最新版安装包\n" + UpdateEndpoint.RELEASE_PAGE, 14, true);
        download.setTextColor(Color.rgb(0, 122, 255));
        download.setPaintFlags(download.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        download.setContentDescription("打开发布页手动下载最新版安装包");
        download.setClickable(true);
        download.setFocusable(true);
        download.setOnClickListener(v -> openReleasePage());
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(-1, -2);
        downloadParams.setMargins(0, dp(20), 0, dp(8));
        content.addView(download, downloadParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("相册 · 作品与文件中控")
                .setView(scroll)
                .setPositiveButton("知道了", null).show();
    }

    private void openReleasePage() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateEndpoint.RELEASE_PAGE));
        if (intent.resolveActivity(getPackageManager()) == null) {
            toast("没有可用的浏览器，无法打开发布页");
            return;
        }
        try {
            startActivity(intent);
            DiagnosticLog.write(this, "manual_download_page_opened", UpdateEndpoint.RELEASE_PAGE);
        } catch (ActivityNotFoundException error) {
            toast("没有可用的浏览器，无法打开发布页");
        }
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("相册诊断信息", DiagnosticLog.snapshot(this)));
        toast("诊断信息已复制");
    }

    private String currentPathName() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String name = prefs.getString("libraryTreeName", "");
        if (!name.isEmpty()) return name;
        String uri = prefs.getString("libraryTreeUri", "");
        return uri.isEmpty() ? "未设置" : treeName(Uri.parse(uri));
    }

    private String treeName(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int slash = id.lastIndexOf('/');
            String name = slash >= 0 ? id.substring(slash + 1) : id.substring(id.lastIndexOf(':') + 1);
            return name.isEmpty() ? "已设置" : name;
        } catch (Exception ignored) { return "已设置"; }
    }

    private TextView label(String value) { TextView v = text(value, 14, true); v.setPadding(0, 0, 0, dp(7)); return v; }
    private TextView settingCaption(String title, String detail) {
        TextView value = text(title + "\n" + detail, 14, false);
        value.setTextColor(Color.rgb(70, 70, 67));
        value.setPadding(0, 0, 0, dp(7));
        return value;
    }
    private LinearLayout cleanupRow(String title, int hours) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.setBackground(round(Color.WHITE, 14));
        row.setClickable(true);
        row.setFocusable(true);
        TextView titleView = text(title, 15, false);
        TextView timingView = text(cleanupTimingText(hours), 15, false);
        timingView.setTextColor(Color.rgb(85, 85, 82));
        timingView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(titleView, new LinearLayout.LayoutParams(0, -1, 1));
        row.addView(timingView, new LinearLayout.LayoutParams(-2, -1));
        return row;
    }
    private LinearLayout choiceRow(String title, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setMinimumHeight(dp(58));
        row.setBackground(round(Color.WHITE, 16));
        row.setClickable(true);
        row.setFocusable(true);
        TextView titleView = text(title, 15, false);
        TextView valueView = text(value, 13, false);
        valueView.setTextColor(Color.rgb(118, 118, 123));
        valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(valueView, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }
    private String cleanupTimingText(int hours) {
        String timing = hours == 0 ? "立刻" : hours + " 小时后";
        return timing + "  ›";
    }
    private EditText numberField(String value) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(value);
        field.setBackground(round(Color.WHITE, 14));
        field.setPadding(dp(14), 0, dp(14), 0);
        return field;
    }
    private SettingSwitchRow settingSwitch(String title, String detail, boolean checked) {
        return new SettingSwitchRow(title, detail, checked);
    }

    private final class SettingSwitchRow extends LinearLayout {
        private final IOSSwitch control;

        SettingSwitchRow(String title, String detail, boolean checked) {
            super(SettingsActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(10), dp(14), dp(10));
            setMinimumHeight(dp(72));
            setBackground(round(Color.WHITE, 16));
            LinearLayout copy = new LinearLayout(SettingsActivity.this);
            copy.setOrientation(VERTICAL);
            TextView titleView = text(title, 15, false);
            TextView detailView = text(detail, 12, false);
            detailView.setTextColor(Color.rgb(118, 118, 123));
            detailView.setLineSpacing(dp(1), 1f);
            detailView.setMaxLines(2);
            copy.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.setMargins(0, dp(3), 0, 0);
            copy.addView(detailView, detailParams);
            addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

            control = new IOSSwitch();
            control.setChecked(checked);
            LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(dp(56), dp(40));
            controlParams.setMargins(dp(12), 0, 0, 0);
            addView(control, controlParams);
            setOnClickListener(v -> control.toggle());
        }

        void setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener listener) {
            control.setOnCheckedChangeListener(listener);
        }

        void setChecked(boolean checked) {
            control.setChecked(checked);
        }
    }

    /** A complete, vendor-independent iOS-style capsule switch. */
    private final class IOSSwitch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean checked;
        private float position;
        private CompoundButton.OnCheckedChangeListener listener;
        private ValueAnimator animator;

        IOSSwitch() {
            super(SettingsActivity.this);
            setClickable(true);
            setFocusable(true);
            setContentDescription("开关");
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setOnClickListener(v -> toggle());
        }

        void setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener value) {
            listener = value;
        }

        void setChecked(boolean value) {
            if (checked == value && position == (value ? 1f : 0f)) return;
            checked = value;
            position = value ? 1f : 0f;
            invalidate();
        }

        void toggle() {
            checked = !checked;
            animateTo(checked ? 1f : 0f);
            if (listener != null) listener.onCheckedChanged(null, checked);
            announceForAccessibility(checked ? "已打开" : "已关闭");
        }

        private void animateTo(float target) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(position, target);
            animator.setDuration(180);
            animator.addUpdateListener(value -> {
                position = (float) value.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float left = 2f * density;
            float top = 4f * density;
            float right = getWidth() - 2f * density;
            float bottom = getHeight() - 4f * density;
            float radius = (bottom - top) / 2f;
            track.set(left, top, right, bottom);

            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(Color.rgb(209, 209, 214), Color.rgb(52, 199, 89), position));
            canvas.drawRoundRect(track, radius, radius, paint);

            float inset = 2f * density;
            float thumbRadius = radius - inset;
            float startX = left + radius;
            float endX = right - radius;
            float centerX = startX + (endX - startX) * position;
            float centerY = (top + bottom) / 2f;
            paint.setColor(Color.WHITE);
            paint.setShadowLayer(2.5f * density, 0, 1f * density, 0x55000000);
            canvas.drawCircle(centerX, centerY, thumbRadius, paint);
            paint.clearShadowLayer();
        }

        private int blend(int from, int to, float amount) {
            int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
            int green = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
            int blue = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
            return Color.rgb(red, green, blue);
        }
    }
    private TextView text(String value, int sp, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(Color.rgb(35, 35, 33)); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private Button button(String value, boolean primary) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(primary ? Color.WHITE : Color.rgb(45, 45, 42)); b.setBackground(round(primary ? Color.rgb(15, 155, 99) : Color.rgb(229, 231, 230), 14)); b.setGravity(Gravity.CENTER); b.setMinHeight(dp(48)); return b; }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48)); p.setMargins(left, top, right, bottom); return p; }
    private LinearLayout.LayoutParams settingMargins(int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, bottom); return p; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
