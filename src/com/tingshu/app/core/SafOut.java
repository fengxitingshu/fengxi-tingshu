package com.tingshu.app.core;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * ★ v2.9 SAF（Storage Access Framework）写出工具。
 *
 * <p><b>为什么要单独一个类</b>：
 * {@link Net#download} 是围绕 {@link File} 写的 —— 用 {@code RandomAccessFile}
 * 顺序写盘、靠 {@code seek} 实现断点续传。而 SAF 目录树给的是 {@code content://} URI，
 * 通过 {@link android.content.ContentResolver} 只能拿到 {@link OutputStream}，
 * <b>不可 seek、没有等价于 renameTo 的原子改名</b>。
 *
 * <p>所以策略是：<b>下载仍走私有目录（那里有完整的续传能力），下完再移动过来</b>。
 * 本类只负责「移动」的后半段：建目录、建文件、流式复制、删源。
 *
 * <p><b>关于「移动」</b>：Android 的 {@link DocumentsContract#moveDocument} 只在
 * 同一 DocumentsProvider 内部有效，跨 provider（App 私有目录 → SD 卡/外部存储）
 * 在多数 ROM 上会抛 {@code UnsupportedOperationException}，不可靠。
 * 因此这里实现为「复制 → 调用方校验字节数 → 调用方删源」，
 * 对磁盘占用的最终效果与真移动一致（复制完立刻删源，不会长期双倍）。
 */
public final class SafOut {

    private SafOut() {}

    /**
     * 在目录树里找到（或创建）一个子目录。
     *
     * @param treeUri 用户选的目录树 URI
     * @param name    子目录名（会被安全化）
     * @return 子目录 URI；失败返回 null
     */
    public static Uri ensureDir(Context c, Uri treeUri, String name) {
        if (c == null || treeUri == null) return null;
        try {
            String safe = safeName(name);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));

            // 先找找有没有同名的
            Uri existed = findChild(c, treeUri, parent, safe, true);
            if (existed != null) return existed;

            return DocumentsContract.createDocument(
                    c.getContentResolver(), parent, DocumentsContract.Document.MIME_TYPE_DIR, safe);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在指定目录下创建（或复用）一个文件。
     *
     * @param mime 如 "audio/mp4"；不确定用 "application/octet-stream"
     * @return 可写文件的 URI；失败返回 null
     */
    public static Uri createFile(Context c, Uri dirUri, String fileName, String mime) {
        if (c == null || dirUri == null) return null;
        try {
            String safe = safeName(fileName);
            Uri existed = findChild(c, dirUri, dirUri, safe, false);
            if (existed != null) return existed;
            return DocumentsContract.createDocument(
                    c.getContentResolver(), dirUri,
                    mime == null ? "application/octet-stream" : mime, safe);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 该目录下是否已有同名文件 */
    public static boolean exists(Context c, Uri dirUri, String fileName) {
        try {
            return findChild(c, dirUri, dirUri, safeName(fileName), false) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把本地文件流式复制到 SAF URI（64KB buffer）。
     *
     * <p>调用方必须在校验「目标文件字节数 == 源文件字节数」之后才删源文件。
     *
     * @return 写出的字节数；-1 表示失败
     */
    public static long copyInto(Context c, File src, Uri target) {
        if (c == null || src == null || target == null || !src.exists()) return -1;
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            // "wt" = write + truncate，避免复用已有文件时残留旧内容
            out = c.getContentResolver().openOutputStream(target, "wt");
            if (out == null) return -1;
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            return total;
        } catch (Throwable t) {
            return -1;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /**
     * 查 SAF 文件的实际大小（用于复制后校验）。
     * 拿不到返回 -1。
     */
    public static long sizeOf(Context c, Uri fileUri) {
        if (c == null || fileUri == null) return -1;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(fileUri,
                    new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                long v = cur.getLong(0);
                return cur.isNull(0) ? -1 : v;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return -1;
    }

    /** 删除 SAF 文件 */
    public static boolean delete(Context c, Uri fileUri) {
        if (c == null || fileUri == null) return false;
        try {
            return DocumentsContract.deleteDocument(c.getContentResolver(), fileUri);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从目录树 URI 里取一个人类可读的名字（用于设置页显示） */
    public static String displayName(Context c, Uri treeUri) {
        if (c == null || treeUri == null) return "";
        Cursor cur = null;
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            cur = c.getContentResolver().query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                return n == null ? "" : n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return "";
    }

    // ==================== 内部 ====================

    /**
     * 在 parent 下按名字找子项。
     *
     * @param wantDir true = 只要目录；false = 只要文件
     */
    private static Uri findChild(Context c, Uri treeUri, Uri parent, String name, boolean wantDir) {
        Cursor cur = null;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getDocumentId(parent));
            cur = c.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String docId = cur.getString(0);
                String dName = cur.getString(1);
                String mime = cur.getString(2);
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (dName != null && dName.equals(name) && isDir == wantDir) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return null;
    }

    /** 文件名/目录名安全化：去掉路径分隔与非法字符 */
    public static String safeName(String raw) {
        if (raw == null) return "unnamed";
        String s = raw.replace('\\', '_').replace('/', '_')
                .replace(':', '_').replace('*', '_').replace('?', '_')
                .replace('"', '_').replace('<', '_').replace('>', '_')
                .replace('|', '_').replace('\n', ' ').replace('\r', ' ')
                .trim();
        if (s.length() == 0) s = "unnamed";
        if (s.length() > 40) s = s.substring(0, 40);   // 太长会被某些 ROM 拒绝
        return s;
    }

    private static void closeQuietly(java.io.Closeable x) {
        if (x != null) {
            try { x.close(); } catch (Throwable ignored) {}
        }
    }
}
