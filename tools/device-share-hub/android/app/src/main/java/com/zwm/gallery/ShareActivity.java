package com.zwm.gallery;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.ArrayList;

public final class ShareActivity extends Activity {
    public static final String EXTRA_WORK_ID = "workId";
    private static final int REQUEST_SHARE = 501;

    private WorkLibrary library;
    private String workId;
    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            workId = getIntent().getStringExtra(EXTRA_WORK_ID);
            if (workId == null || workId.isEmpty()) throw new IllegalStateException("没有指定作品");
            library = new WorkLibrary(new java.io.File(getFilesDir(), "work-library"));
            WorkLibrary.WorkEntry work = library.getActive(workId);
            if (work == null) throw new IllegalStateException("作品不存在或已进入回收站");
            DiagnosticLog.write(this, "share_activity_open", workId);
            launchShare(work);
        } catch (Exception error) {
            DiagnosticLog.write(this, "share_activity_failed", error.getMessage());
            Toast.makeText(this, "无法打开作品：" + error.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void launchShare(WorkLibrary.WorkEntry work) {
        if (launched) return;
        launched = true;
        ArrayList<Uri> uris = new ArrayList<>();
        for (String image : work.images) {
            uris.add(new Uri.Builder()
                    .scheme("content")
                    .authority(getPackageName() + ".files")
                    .appendPath("active")
                    .appendPath(work.id)
                    .appendPath(image)
                    .build());
        }
        if (uris.isEmpty()) throw new IllegalStateException("作品中没有图片");

        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("作品文案", work.text));
        Toast.makeText(this, "文案复制成功", Toast.LENGTH_SHORT).show();

        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        send.setType("image/*");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!work.text.trim().isEmpty()) send.putExtra(Intent.EXTRA_TEXT, work.text);
        if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        ClipData clipData = ClipData.newUri(getContentResolver(), "作品图片", uris.get(0));
        for (int index = 1; index < uris.size(); index++) clipData.addItem(new ClipData.Item(uris.get(index)));
        send.setClipData(clipData);

        DiagnosticLog.write(this, "share_sheet_launch", workId + " images=" + uris.size());
        startService(new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_SHARE_OPENED)
                .putExtra(EXTRA_WORK_ID, workId));
        startActivityForResult(Intent.createChooser(send, "选择要发布的平台"), REQUEST_SHARE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SHARE) return;
        try {
            // Android only reports that the share target was opened, not whether publishing succeeded.
            library.markShared(workId, LocalDate.now());
            DiagnosticLog.write(this, "share_target_opened", workId + " result=" + resultCode);
        } catch (Exception error) {
            DiagnosticLog.write(this, "share_mark_failed", error.getMessage());
        }
        startService(new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_SHARE_FINISHED)
                .putExtra(EXTRA_WORK_ID, workId));
        sendBroadcast(new Intent(OnlineService.ACTION_TASK_READY).setPackage(getPackageName()));
        finish();
    }
}
