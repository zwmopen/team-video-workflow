package com.zwm.deviceshare;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

public final class MainActivity extends Activity {
    static volatile boolean isVisible = false;
    private static final String PREFS = "device_share";

    private EditText deviceNameInput;
    private TextView statusText;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (OnlineService.ACTION_TASK_READY.equals(action)) {
                statusText.setText("素材已接收，正在打开系统分享…");
                startActivity(new Intent(MainActivity.this, ShareActivity.class));
            } else if (OnlineService.ACTION_STATUS.equals(action)) {
                String message = intent.getStringExtra("message");
                if (message != null) statusText.setText(message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureDeviceId();
        DiagnosticLog.write(this, "app_open", "MainActivity opened");
        setContentView(buildUi());
        loadSettings();
        requestNotificationPermission();
        startReceiver();
    }

    @Override
    protected void onStart() {
        super.onStart();
        isVisible = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(OnlineService.ACTION_TASK_READY);
        filter.addAction(OnlineService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerLegacyReceiver(filter);
        }
        if (PendingTaskStore.exists(this)) {
            statusText.setText("有一批素材等待分享");
        } else {
            statusText.setText("局域网接收已开启");
        }
    }

    @SuppressWarnings("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) {
        registerReceiver(receiver, filter);
    }

    @Override
    protected void onStop() {
        isVisible = false;
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onStop();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        TextView title = text("素材投送接收端", 25, true);
        root.addView(title);

        TextView intro = text("手机与电脑在同一 Wi‑Fi 时会自动出现在电脑面板。无需填写地址、无需配对令牌。电脑把图片或视频拖到本机卡片后，本机会接收并调起安卓系统分享。", 14, false);
        intro.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(-1, -2);
        introParams.setMargins(0, dp(8), 0, dp(22));
        root.addView(intro, introParams);

        root.addView(label("设备名称"));
        deviceNameInput = input("例如：红米团建号01");
        root.addView(deviceNameInput);

        Button save = button("保存设备名称", true);
        save.setOnClickListener(v -> saveDeviceName());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(50));
        buttonParams.setMargins(0, dp(18), 0, dp(10));
        root.addView(save, buttonParams);

        Button sharePending = button("打开待分享素材", false);
        sharePending.setOnClickListener(v -> {
            if (PendingTaskStore.exists(this)) {
                startActivity(new Intent(this, ShareActivity.class));
            } else {
                toast("当前没有待分享素材");
            }
        });
        root.addView(sharePending, new LinearLayout.LayoutParams(-1, dp(48)));

        Button diagnostics = button("复制诊断信息", false);
        diagnostics.setOnClickListener(v -> copyDiagnostics());
        LinearLayout.LayoutParams diagnosticsParams = new LinearLayout.LayoutParams(-1, dp(48));
        diagnosticsParams.setMargins(0, dp(10), 0, 0);
        root.addView(diagnostics, diagnosticsParams);

        Button stop = button("停止局域网接收", false);
        stop.setOnClickListener(v -> stopReceiver());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, dp(48));
        stopParams.setMargins(0, dp(10), 0, 0);
        root.addView(stop, stopParams);

        statusText = text("正在开启局域网接收…", 14, false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setBackgroundColor(Color.rgb(244, 246, 248));
        statusText.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(18), 0, 0);
        root.addView(statusText, statusParams);

        TextView note = text("建议在小米/红米系统中允许通知、自启动、后台运行，并把电池策略设为“不限制”。素材只保存在 App 私有缓存，不写入相册。", 13, false);
        note.setTextColor(Color.GRAY);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.setMargins(0, dp(18), 0, 0);
        root.addView(note, noteParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView label(String value) {
        TextView view = text(value, 13, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(13), 0, dp(6));
        view.setLayoutParams(params);
        return view;
    }

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextSize(15);
        editText.setPadding(dp(12), 0, dp(12), 0);
        editText.setBackgroundResource(android.R.drawable.edit_text);
        editText.setMinHeight(dp(48));
        return editText;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(20, 22, 24));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(30, 32, 34));
        button.setBackgroundColor(primary ? Color.rgb(20, 22, 24) : Color.rgb(235, 238, 241));
        return button;
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String defaultName = Build.MANUFACTURER + " " + Build.MODEL;
        deviceNameInput.setText(prefs.getString("deviceName", defaultName));
    }

    private void saveDeviceName() {
        String name = deviceNameInput.getText().toString().trim();
        if (name.isEmpty()) {
            toast("设备名称不能为空");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("deviceName", name).apply();
        DiagnosticLog.write(this, "device_name_saved", name);
        startReceiver();
        statusText.setText("名称已保存，电脑会自动刷新设备卡片");
    }

    private void startReceiver() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("serviceEnabled", true).apply();
        DiagnosticLog.write(this, "receiver_start_requested", "foreground service");
        Intent intent = new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_START);
        startForegroundService(intent);
    }

    private void stopReceiver() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("serviceEnabled", false).apply();
        DiagnosticLog.write(this, "receiver_stop_requested", "user tapped stop");
        startService(new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_STOP));
        statusText.setText("局域网接收已停止；重新打开 App 会再次开启");
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("素材投送诊断信息", DiagnosticLog.snapshot(this)));
        toast("诊断信息已复制");
    }

    private void ensureDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getString("deviceId", "").isEmpty()) {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String id = (androidId == null || androidId.isEmpty()) ? UUID.randomUUID().toString() : androidId;
            prefs.edit().putString("deviceId", "android-" + id).apply();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
