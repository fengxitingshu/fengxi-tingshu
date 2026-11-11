package com.tingshu.app.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;

/**
 * ★ v4.21.36 自动更新：把下载好的 APK 以 content:// 提供给系统安装器。
 *
 * <p>工程没引 support-v4 / androidx，用不了现成的 FileProvider；
 * 自己实现一个极简版只够覆盖「安装器读 APK」这一种场景：
 * <ul>
 *   <li>openFile —— 安装器读文件本体；</li>
 *   <li>query —— 安装器会查 _size / _display_name（OpenableColumns）；</li>
 *   <li>getType —— 固定 APK 的 MIME。</li>
 * </ul>
 *
 * <p>provider 是 exported=false，只有本应用 + 被显式授权的接收方
 * （FLAG_GRANT_READ_URI_PERMISSION）能访问，安全性等同 FileProvider。
 */
public class UpdateProvider extends ContentProvider {

    public static final String AUTHORITY = "com.tingshu.app.update";

    /** 当前要安装的 APK 文件（installApk 前设置） */
    private static volatile File apkFile;

    public static void setApk(File f) {
        apkFile = f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = apkFile;
        if (f == null || !f.exists()) return null;
        // 安装器只关心这两列；projection 为 null 时全量返回
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = apkFile;
        if (f == null || !f.exists()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
