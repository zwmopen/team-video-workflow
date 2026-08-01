package com.zwm.gallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.content.pm.PackageManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private static final String PREFS = "device_share";
    private static final int REQUEST_TREE = 71;
    private static final int REQUEST_NOTIFICATIONS = 72;
    private static final int REQUEST_SCREENSHOTS = 73;
    private EditText deviceName;
    private TextView pathText;
    private Switch soundSwitch;
    private TextView moveAfterText;
    private TextView deleteAfterText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(ScreenInsets.protect(buildUi()));
    }

    private SpringScrollView buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        root.setBackgroundColor(Color.rgb(242, 242, 247));

        TextView title = text("设置", 28, true);
        root.addView(title);
        root.addView(text("只保留真正有用的设置。", 14, false), margins(0, dp(4), 0, dp(20)));

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
        Switch vibrationSwitch = settingSwitch("震动提醒", "开始、完成或失败时震动",
                getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("vibrationEnabled", false));
        vibrationSwitch.setOnCheckedChangeListener((button, enabled) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("vibrationEnabled", enabled).apply();
            if (enabled) vibrateOnce();
        });
        root.addView(vibrationSwitch, settingMargins(dp(20)));

        root.addView(label("剪切板与截图"));
        Switch overlaySwitch = settingSwitch(
                "悬浮剪切板",
                "在其他应用上方显示“贴”按钮；默认开启，可随时关闭",
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean("clipboardOverlayEnabled", true));
        overlaySwitch.setOnCheckedChangeListener((button, enabled) ->
                changeClipboardOverlay(enabled));
        root.addView(overlaySwitch, settingMargins(dp(10)));
        Switch screenshotSwitch = settingSwitch(
                "自动识别新截图",
                "识别系统相册里的新截图；关闭后不读取也不发送",
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean("screenshotSyncEnabled", false));
        screenshotSwitch.setOnCheckedChangeListener((button, enabled) ->
                changeScreenshotSync(enabled));
        root.addView(screenshotSwitch, settingMargins(dp(20)));
        Switch screenshotAutoSwitch = settingSwitch(
                "自动发送到主设备",
                "已设置主设备时直接发送；关闭后进入相册再逐张选择",
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean("screenshotAutoSendEnabled", true));
        screenshotAutoSwitch.setOnCheckedChangeListener((button, enabled) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("screenshotAutoSendEnabled", enabled).apply();
            startService(new Intent(this, OnlineService.class)
                    .setAction(OnlineService.ACTION_REFRESH_SCREENSHOTS));
        });
        root.addView(screenshotAutoSwitch, settingMargins(dp(10)));
        Switch screenshotReceiveSwitch = settingSwitch(
                "允许作为主设备接收",
                "关闭后拒绝其他设备自动发送的截图，普通文件传送不受影响",
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean("screenshotReceiveEnabled", true));
        screenshotReceiveSwitch.setOnCheckedChangeListener((button, enabled) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("screenshotReceiveEnabled", enabled).apply());
        root.addView(screenshotReceiveSwitch, settingMargins(dp(20)));

        root.addView(label("软件"));
        Switch autoUpdateSwitch = settingSwitch(
                "自动检查并下载更新",
                "每次打开相册时检查；发现新版后自动交给系统下载，安装仍需系统确认一次",
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean(UpdateChecker.PREF_AUTO_UPDATE_ENABLED, true));
        autoUpdateSwitch.setOnCheckedChangeListener((button, enabled) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(UpdateChecker.PREF_AUTO_UPDATE_ENABLED, enabled).apply());
        root.addView(autoUpdateSwitch, settingMargins(dp(10)));
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
                .setAction(OnlineService.ACTION_REFRESH_OVERLAY));
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
        if (requestCode == REQUEST_SCREENSHOTS) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) {
                granted &= result == PackageManager.PERMISSION_GRANTED;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("screenshotSyncEnabled", granted).apply();
            startService(new Intent(this, OnlineService.class)
                    .setAction(OnlineService.ACTION_REFRESH_SCREENSHOTS));
            toast(granted ? "截图提醒已开启" : "需要允许读取图片才能识别新截图");
            return;
        }
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("soundNotificationsEnabled", granted).apply();
        soundSwitch.setChecked(granted);
        toast(granted ? "声音通知已打开" : "系统没有允许通知");
    }

    private void changeClipboardOverlay(boolean enabled) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("clipboardOverlayEnabled", enabled)
                .remove(OnlineService.PREF_OVERLAY_HIDDEN_UNTIL)
                .apply();
        if (enabled && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else {
            startService(new Intent(this, OnlineService.class)
                    .setAction(OnlineService.ACTION_REFRESH_OVERLAY));
        }
    }

    private void changeScreenshotSync(boolean enabled) {
        if (!enabled) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("screenshotSyncEnabled", false).apply();
            startService(new Intent(this, OnlineService.class)
                    .setAction(OnlineService.ACTION_REFRESH_SCREENSHOTS));
            return;
        }
        String target = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("screenshotTargetPeerId", "");
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        boolean needsImages = checkSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED;
        boolean needsNotifications = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        if (needsImages || needsNotifications) {
            requestPermissions(Build.VERSION.SDK_INT >= 33
                            ? new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                                    Manifest.permission.POST_NOTIFICATIONS}
                            : new String[]{permission},
                    REQUEST_SCREENSHOTS);
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("screenshotSyncEnabled", true).apply();
        startService(new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_REFRESH_SCREENSHOTS));
        toast("截图提醒已开启");
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
        new AlertDialog.Builder(this)
                .setTitle("相册 · 作品与文件中控")
                .setMessage(
                        "把分散在电脑和手机里的素材，整理成随手可用的作品库。\n\n"
                        + "核心场景\n"
                        + "从电脑拖入文件、ZIP 或整个文件夹，手机自动接收并保留原目录结构；含图片和 TXT 的目录会被识别为作品，普通文件也可以继续传送、预览和分享。\n\n"
                        + "作品工作流\n"
                        + "点一次“复制并分享”，应用会立即记录一次，文案自动进入剪贴板，作品图片交给系统分享面板。再次分享会先提醒，避免误发同一个作品。默认 1 小时后进入回收站并从文件管理中彻底删除，时间可在设置中调整。\n\n"
                        + "跨设备传送\n"
                        + "电脑、Android 和 iPhone 在同一 Wi‑Fi 下自动发现。选择设备和文件即可传送，过程带有进度、完整性校验和结果提示。\n\n"
                        + "共享剪切板与截图\n"
                        + "左侧是剪切板记录，右侧是常用语；同组在线手机会同步新增、修改和删除。允许悬浮窗后可在其他应用点“贴”快速打开；还可指定截图接收设备，截图后由系统通知询问是否发送。\n\n"
                        + "系统分享\n"
                        + "其他应用的分享面板里可以选择“相册”，再选择在线设备。普通文件按真实文件夹存放，只有图片和文字一起直接分享时才进入分享准备。\n\n"
                        + "设计思路\n"
                        + "内容优先、操作尽量少、状态一眼可见。目录授权、作品记录、分享次数和回收站都保存在本机；覆盖升级会延续现有数据。坚果云只属于电脑端，不进入手机版。")
                .setPositiveButton("知道了", null).show();
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
    private Switch settingSwitch(String title, String detail, boolean checked) {
        Switch value = new Switch(this);
        String content = title + "\n" + detail;
        SpannableString styled = new SpannableString(content);
        int detailStart = title.length() + 1;
        styled.setSpan(new RelativeSizeSpan(0.78f), detailStart, content.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(Color.rgb(115, 115, 111)),
                detailStart, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        value.setText(styled);
        value.setTextSize(16);
        value.setTextColor(Color.rgb(35, 35, 33));
        value.setChecked(checked);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setLineSpacing(dp(2), 1f);
        value.setMinHeight(dp(78));
        value.setPadding(dp(14), dp(10), dp(14), dp(10));
        value.setBackground(round(Color.WHITE, 14));
        return value;
    }
    private TextView text(String value, int sp, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(Color.rgb(35, 35, 33)); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private Button button(String value, boolean primary) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(primary ? Color.WHITE : Color.rgb(45, 45, 42)); b.setBackground(round(primary ? Color.rgb(15, 155, 99) : Color.rgb(229, 231, 230), 14)); b.setGravity(Gravity.CENTER); b.setMinHeight(dp(48)); return b; }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48)); p.setMargins(left, top, right, bottom); return p; }
    private LinearLayout.LayoutParams settingMargins(int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, bottom); return p; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
