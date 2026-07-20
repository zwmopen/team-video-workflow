package com.zwm.gallery;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public final class ShareFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) {
        File file = resolve(uri);
        String extension = MimeTypeMap.getFileExtensionFromUrl(Uri.encode(file.getName()));
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        return mime == null ? "application/octet-stream" : mime;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(file.getName());
            else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
            else row.add(null);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("只允许读取");
        File file = resolve(uri);
        if (!file.isFile()) throw new FileNotFoundException("图片不存在");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("只读"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("只读"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("只读"); }

    private File resolve(Uri uri) {
        if (getContext() == null) throw new IllegalStateException("Provider 未初始化");
        List<String> segments = uri.getPathSegments();
        if (segments.size() == 2 && "updates".equals(segments.get(0))) {
            String storedName = segments.get(1);
            if (!storedName.matches("[A-Za-z0-9._-]+")) throw new SecurityException("非法更新文件名");
            return checked(new File(getContext().getFilesDir(), "updates"), storedName);
        }
        if (segments.size() != 3 || !"active".equals(segments.get(0))) {
            throw new IllegalArgumentException("URI 格式无效");
        }
        String workId = segments.get(1);
        String storedName = segments.get(2);
        if (!workId.matches("[A-Za-z0-9._-]+") || storedName.contains("/") || storedName.contains("\\")) {
            throw new SecurityException("非法文件路径");
        }
        File root = new File(getContext().getFilesDir(), "work-library/active");
        return checked(new File(root, workId), storedName);
    }

    private File checked(File root, String storedName) {
        File candidate = new File(root, storedName);
        try {
            String rootPath = root.getCanonicalPath() + File.separator;
            String candidatePath = candidate.getCanonicalPath();
            if (!candidatePath.startsWith(rootPath)) throw new SecurityException("路径越界");
            return candidate;
        } catch (IOException error) {
            throw new IllegalArgumentException("无法解析文件路径", error);
        }
    }
}
