package com.zwm.deviceshare;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public final class ShareActivity extends Activity {
    private static final int REQUEST_SHARE = 501;
    private JSONObject pending;
    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            pending = PendingTaskStore.read(this);
            DiagnosticLog.write(this, "share_activity_open", pending.optString("id", ""));
            launchShare();
        } catch (Exception error) {
            DiagnosticLog.write(this, "share_activity_failed", error.getMessage());
            Toast.makeText(this, "没有可分享的素材：" + error.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void launchShare() throws Exception {
        if (launched) return;
        launched = true;
        String taskId = pending.getString("id");
        String text = pending.optString("text", "");
        JSONArray files = pending.getJSONArray("files");
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> mimes = new ArrayList<>();

        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            Uri uri = new Uri.Builder()
                    .scheme("content")
                    .authority(getPackageName() + ".files")
                    .appendPath(taskId)
                    .appendPath(file.getString("storedName"))
                    .build();
            uris.add(uri);
            mimes.add(file.optString("mime", "application/octet-stream"));
        }
        if (uris.isEmpty()) throw new IllegalStateException("任务中没有文件");

        if (!text.trim().isEmpty()) {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("投送文案", text));
            Toast.makeText(this, "文案已复制到剪贴板", Toast.LENGTH_SHORT).show();
        }

        DiagnosticLog.write(this, "share_sheet_launch", taskId + " files=" + uris.size() + " mime=" + commonMime(mimes));
        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        send.setType(commonMime(mimes));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!text.trim().isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
        if (uris.size() == 1) {
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        ClipData clipData = ClipData.newUri(getContentResolver(), "素材", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clipData.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clipData);

        startService(new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_SHARE_OPENED));
        startActivityForResult(Intent.createChooser(send, "分享到抖音、小红书或其他应用"), REQUEST_SHARE);
    }

    private String commonMime(ArrayList<String> mimes) {
        boolean allImages = true;
        boolean allVideos = true;
        String exact = mimes.get(0);
        boolean allExact = true;
        for (String mime : mimes) {
            allImages &= mime.startsWith("image/");
            allVideos &= mime.startsWith("video/");
            allExact &= exact.equals(mime);
        }
        if (allExact) return exact;
        if (allImages) return "image/*";
        if (allVideos) return "video/*";
        return "*/*";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SHARE) {
            DiagnosticLog.write(this, "share_result", "request=" + requestCode + " result=" + resultCode);
            PendingTaskStore.clear(this);
            startService(new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_SHARE_FINISHED));
            finish();
        }
    }
}
