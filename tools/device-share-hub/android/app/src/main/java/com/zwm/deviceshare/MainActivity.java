package com.zwm.deviceshare;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

    private EditText serverUrlInput;
    private EditText tokenInput;
    private EditText deviceNameInput;
    private TextView statusText;

    private final BroadcastReceiver taskReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (OnlineService.ACTION_TASK_READY.equals(intent.getAction())) {
                statusText.setText("素材已接收，正在打开系统分享…");
                startActivity(new Intent(MainActivity.this, ShareActivity.class));
            } else if (OnlineService.ACTION_STATUS.equals(intent.getAction())) {
                statusText.setText(intent.getStringExtra("message"));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureDeviceId();
        setContentView(buildUi());
        loadSettings();
        requestNotificationPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        isVisible = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(OnlineService.ACTION_TASK_READY);
        filter.addAction(OnlineService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(taskReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(taskReceiver, filter);
        }
        if (PendingTaskStore.exists(this)) {
            statusText.setText("有一批素材等待分享");
        }
    }

    @Override
    protected void onStop() {
        isVisible = false;
        try {
            unregisterReceiver(taskReceiver);
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

        TextView intro = text("与电脑连接同一 Wi‑Fi。电脑拖入图片或视频后，本机接收并调起安卓系统分享面板。文件只保存在 App 私有缓存中，不进入相册。", 14, false);
        intro.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(-1, -2);
        introParams.setMargins(0, dp(8), 0, dp(22));
        root.addView(intro, introParams);

        root.addView(label("电脑地址"));
        serverUrlInput = input("例如：http://192.168.1.20:45832");
        root.addView(serverUrlInput);

        root.addView(label("配对令牌"));
        tokenInput = input("从电脑面板复制");
        root.addView(tokenInput);

        root.addView(label("设备名称"));
        deviceNameInput = input("例如：红米团建号01");
        root.addView(deviceNameInput);

        Button start = button("保存并保持在线", true);
        start.setOnClickListener(v -> startOnline());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(50));
        buttonParams.setMargins(0, dp(18), 0, dp(10));
        root.addView(start, buttonParams);

        Button stop = button("停止在线服务", false);
        stop.setOnClickListener(v -> stopOnline());
        root.addView(stop, new LinearLayout.LayoutParams(-1, dp(48)));

        Button sharePending = button("打开待分享素材", false);
        sharePending.setOnClickListener(v -> {
            if (PendingTaskStore.exists(this)) {
                startActivity(new Intent(this, ShareActivity.class));
            } else {
                toast("当前没有待分享素材");
            }
        });
        LinearLayout.LayoutParams pendingParams = new LinearLayout.LayoutParams(-1, dp(48));
        pendingParams.setMargins(0, dp(10), 0, 0);
        root.addView(sharePending, pendingParams);

        statusText = text("尚未连接", 14, false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setBackgroundColor(Color.rgb(244, 246, 248));
        statusText.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(18), 0, 0);
        root.addView(statusText, statusParams);

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
        serverUrlInput.setText(prefs.getString("serverUrl", ""));
        tokenInput.setText(prefs.getString("token", ""));
        String defaultName = Build.MANUFACTURER + " " + Build.MODEL;
        deviceNameInput.setText(prefs.getString("deviceName", defaultName));
        if (prefs.getBoolean("serviceEnabled", false)) {
            statusText.setText("在线服务已配置；如被系统关闭，请再次点击“保持在线”");
        }
    }

    private void startOnline() {
        String serverUrl = normalizeServerUrl(serverUrlInput.getText().toString());
        String token = tokenInput.getText().toString().trim();
        String deviceName = deviceNameInput.getText().toString().trim();
        if (serverUrl.isEmpty() || token.isEmpty() || deviceName.isEmpty()) {
            toast("请填写电脑地址、配对令牌和设备名称");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("serverUrl", serverUrl)
                .putString("token", token)
                .putString("deviceName", deviceName)
                .putBoolean("serviceEnabled", true)
                .apply();
        Intent intent = new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_START);
        startForegroundService(intent);
        statusText.setText("正在连接电脑…");
    }

    private void stopOnline() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("serviceEnabled", false).apply();
        startService(new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_STOP));
        statusText.setText("在线服务已停止");
    }

    private String normalizeServerUrl(String value) {
        String text = value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        if (!text.isEmpty() && !text.startsWith("http://") && !text.startsWith("https://")) {
            text = "http://" + text;
        }
        return text;
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
