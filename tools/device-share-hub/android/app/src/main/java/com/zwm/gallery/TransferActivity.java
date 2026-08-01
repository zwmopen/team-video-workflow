package com.zwm.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TransferActivity extends Activity {
    public static final String EXTRA_LOCAL_PATHS = "localPaths";
    private static final int PICK_FILES = 81;
    private static final int PICK_FOLDER = 82;
    private static final String PREFS = "device_share";
    private static final String PREF_TRANSFER_CHANNEL = "outgoingTransferChannel";
    private static final String CHANNEL_AUTO = "auto";
    private static final String CHANNEL_USB = "usb";
    private static final String CHANNEL_WIFI = "wifi";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout peersContainer;
    private TextView status;
    private ProgressBar progress;
    private Button channelButton;
    private PeerDevice selected;
    private String selectedChannel;
    private boolean receiverRegistered;
    private ArrayList<String> pendingLocalPaths;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshPeers = new Runnable() {
        @Override public void run() { renderPeers(); refreshHandler.postDelayed(this, 2000); }
    };

    private final BroadcastReceiver peerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { renderPeers(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        pendingLocalPaths = getIntent().getStringArrayListExtra(EXTRA_LOCAL_PATHS);
        selectedChannel = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_TRANSFER_CHANNEL, CHANNEL_AUTO);
        setTitle("传送");
        setContentView(ScreenInsets.protect(buildUi()));
        startService(new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_START));
        renderPeers();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(OnlineService.ACTION_PEERS_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(peerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacyReceiver(filter);
        receiverRegistered = true;
        renderPeers();
        refreshHandler.postDelayed(refreshPeers, 2000);
    }

    @SuppressWarnings("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) { registerReceiver(peerReceiver, filter); }

    @Override protected void onStop() {
        if (receiverRegistered) { unregisterReceiver(peerReceiver); receiverRegistered = false; }
        refreshHandler.removeCallbacks(refreshPeers);
        super.onStop();
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(Color.rgb(246, 244, 240));
        TextView title = text("传送到", 28, true);
        root.addView(title);
        TextView hint = text("先选传送方式和设备，再选文件或文件夹。同一 Wi‑Fi 可直连；数据线需要打开 USB 网络共享。", 14, false);
        hint.setTextColor(Color.rgb(103, 100, 95));
        root.addView(hint, margins(0, dp(8), 0, dp(14)));
        channelButton = new Button(this);
        channelButton.setAllCaps(false);
        channelButton.setTextSize(15);
        channelButton.setTextColor(Color.rgb(38, 115, 77));
        channelButton.setBackground(round(Color.WHITE, 14));
        channelButton.setOnClickListener(v -> chooseChannel());
        updateChannelButton();
        root.addView(channelButton, margins(0, 0, 0, dp(12)));
        peersContainer = new LinearLayout(this);
        peersContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(peersContainer);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button files = action("选择文件");
        files.setOnClickListener(v -> chooseFiles());
        Button folder = action("选择文件夹");
        folder.setOnClickListener(v -> chooseFolder());
        actions.addView(files, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        folderParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(folder, folderParams);
        root.addView(actions, margins(0, dp(18), 0, 0));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, margins(0, dp(18), 0, 0));
        status = text("正在发现附近设备…", 13, false);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.rgb(71, 104, 87));
        root.addView(status, margins(0, dp(12), 0, 0));
        TextView recordsTitle = text("操作记录", 16, true);
        root.addView(recordsTitle, margins(0, dp(24), 0, dp(8)));
        List<String> records = OperationLog.recent(this, 12);
        if (records.isEmpty()) {
            root.addView(text("暂无传送记录", 13, false));
        } else {
            for (String record : records) {
                TextView item = text(record, 13, false);
                item.setPadding(dp(12), dp(10), dp(12), dp(10));
                item.setBackground(round(Color.WHITE, 12));
                root.addView(item, margins(0, 0, 0, dp(7)));
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private void renderPeers() {
        if (peersContainer == null) return;
        List<PeerDevice> peers = filteredPeers(OnlineService.peers());
        peersContainer.removeAllViews();
        if (selected != null && !matchesSelectedChannel(selected)) selected = null;
        if (peers.isEmpty()) {
            String emptyMessage = CHANNEL_USB.equals(selectedChannel)
                    ? "暂未发现 USB 电脑\n请连接数据线，并打开手机的 USB 网络共享"
                    : "暂未发现设备\n请在另一台设备上打开“相册”或电脑中控";
            TextView empty = text(emptyMessage, 15, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            peersContainer.addView(empty);
            if (CHANNEL_USB.equals(selectedChannel)) {
                Button tether = action("打开 USB 网络共享设置");
                tether.setOnClickListener(v -> openUsbTetherSettings());
                peersContainer.addView(tether, margins(0, 0, 0, dp(9)));
            }
            return;
        }
        if (selected == null) status.setText("已发现 " + peers.size() + " 台设备，请选择");
        for (PeerDevice peer : peers) {
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            item.setPadding(dp(16), dp(10), dp(16), dp(10));
            String displayName = peer.name;
            if (peer.workCount >= 0) displayName += "（作品数 " + peer.workCount + "）";
            item.setText(displayName + "\n" + peer.model + "  ·  " + peer.transport);
            item.setTextSize(15);
            boolean chosen = selected != null && selected.id.equals(peer.id);
            item.setTextColor(chosen ? Color.WHITE : Color.rgb(38, 39, 38));
            item.setBackground(round(chosen ? Color.rgb(38, 145, 94) : Color.WHITE, 16));
            item.setOnClickListener(v -> {
                selected = peer;
                status.setText("已选择“" + peer.name + "”");
                renderPeers();
                if (pendingLocalPaths != null && !pendingLocalPaths.isEmpty()) sendPendingLocalFiles();
            });
            peersContainer.addView(item, margins(0, 0, 0, dp(9)));
        }
    }

    private List<PeerDevice> filteredPeers(List<PeerDevice> peers) {
        if (CHANNEL_AUTO.equals(selectedChannel)) return peers;
        ArrayList<PeerDevice> filtered = new ArrayList<>();
        for (PeerDevice peer : peers) if (matchesSelectedChannel(peer)) filtered.add(peer);
        return filtered;
    }

    private boolean matchesSelectedChannel(PeerDevice peer) {
        if (peer == null || CHANNEL_AUTO.equals(selectedChannel)) return true;
        return CHANNEL_USB.equals(selectedChannel)
                ? "USB".equals(peer.transport) : "WiFi".equals(peer.transport);
    }

    private void chooseChannel() {
        String[] labels = {"自动选择", "USB（需开启 USB 网络共享）", "Wi‑Fi"};
        String[] values = {CHANNEL_AUTO, CHANNEL_USB, CHANNEL_WIFI};
        int checked = CHANNEL_USB.equals(selectedChannel) ? 1
                : CHANNEL_WIFI.equals(selectedChannel) ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle("传送方式")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    selectedChannel = values[which];
                    selected = null;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(PREF_TRANSFER_CHANNEL, selectedChannel).apply();
                    updateChannelButton();
                    renderPeers();
                    dialog.dismiss();
                    if (CHANNEL_USB.equals(selectedChannel)
                            && filteredPeers(OnlineService.peers()).isEmpty()) {
                        Toast.makeText(this, "请打开 USB 网络共享，电脑会自动出现",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateChannelButton() {
        if (channelButton == null) return;
        String label = CHANNEL_USB.equals(selectedChannel) ? "USB"
                : CHANNEL_WIFI.equals(selectedChannel) ? "Wi‑Fi" : "自动";
        channelButton.setText("传送方式：" + label + "  ›");
    }

    private void openUsbTetherSettings() {
        try {
            startActivity(new Intent("android.settings.TETHER_SETTINGS"));
        } catch (Exception error) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "请在系统设置中打开“USB 网络共享”",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void chooseFiles() {
        if (!ensurePeer()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_FILES);
    }

    private void chooseFolder() {
        if (!ensurePeer()) return;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK_FOLDER);
    }

    private boolean ensurePeer() {
        if (selected != null) return true;
        Toast.makeText(this, "请先选择接收设备", Toast.LENGTH_SHORT).show();
        return false;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || selected == null) return;
        if (request == PICK_FOLDER && data.getData() != null) sendFolder(data.getData());
        if (request == PICK_FILES) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) sendFiles(uris);
        }
    }

    private void sendFiles(List<Uri> uris) { runTransfer(client -> client.sendFiles(selected, uris, this::updateProgress)); }
    private void sendFolder(Uri tree) { runTransfer(client -> client.sendFolder(selected, tree, this::updateProgress)); }

    private void sendPendingLocalFiles() {
        ArrayList<java.io.File> files = new ArrayList<>();
        for (String path : pendingLocalPaths) files.add(new java.io.File(path));
        pendingLocalPaths = null;
        runTransfer(client -> client.sendLocalFiles(selected, files, this::updateProgress));
    }

    private void runTransfer(TransferWork work) {
        progress.setProgress(0); progress.setVisibility(View.VISIBLE);
        PeerDevice target = selected;
        worker.execute(() -> {
            try {
                work.run(new TransferClient(getContentResolver(), getCacheDir()));
                DiagnosticLog.write(this, "outgoing_done", target.name + " " + target.ip);
                OperationLog.add(this, "发送完成", target.name);
            } catch (Exception error) {
                DiagnosticLog.write(this, "outgoing_failed", target.name + " " + error.getMessage());
                updateProgress(0, "传送失败：" + (error.getMessage() == null ? "请检查两台设备的 Wi‑Fi" : error.getMessage()));
                OperationLog.add(this, "发送失败",
                        target.name + "：" + (error.getMessage() == null ? "网络异常" : error.getMessage()));
            }
        });
    }

    private void updateProgress(int value, String message) {
        runOnUiThread(() -> { progress.setProgress(value); progress.setVisibility(View.VISIBLE); status.setText(message); });
    }

    private Button action(String title) {
        Button button = new Button(this);
        button.setText(title); button.setTextSize(16); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE); button.setAllCaps(false);
        button.setBackground(round(Color.rgb(38, 145, 94), 14));
        return button;
    }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(l, t, r, b); return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface TransferWork { void run(TransferClient client) throws Exception; }
}
