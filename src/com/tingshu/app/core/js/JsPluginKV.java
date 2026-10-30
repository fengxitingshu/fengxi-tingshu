package com.tingshu.app.core.js;

import com.tingshu.app.core.App;
import com.tingshu.app.core.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/**
 * ★ v3.0：JS 插件的持久化键值缓存，对应契约里的
 * {@code host.readCache / host.writeCache / host.cacheAge}。
 *
 * <h3>为什么要它</h3>
 * 「相声随身听」「评书随身听」这类源要做**全站索引**：把整站几百个页面抓一遍
 * 抽成索引文件，之后 7 天内的搜索都直接查本地索引（否则每次搜索都要重新抓站，
 * 几十秒起步）。契约里因此有三件套：
 * <ul>
 *   <li>{@code cacheAge(name)} —— 缓存多老了，返回 -1 表示没缓存</li>
 *   <li>{@code readCache(name)} —— 同步读字符串</li>
 *   <li>{@code writeCache(name, data)} —— 同步写字符串</li>
 * </ul>
 *
 * <h3>落盘位置</h3>
 * {@code filesDir/plugin_cache/{pluginId}/{name}} —— 插件之间互相隔离，
 * 卸载插件时整个目录一起删。
 *
 * <h3>为什么用 RandomAccessFile 不用 FileOutputStream</h3>
 * 索引文件可能上百 KB 且会反复重写，RandomAccessFile 的 {@code setLength(0)}
 * 截断语义比"覆盖写"更可靠（避免旧的尾巴残留导致解析出脏数据）。
 */
public final class JsPluginKV {

    private JsPluginKV() {}

    private static File root() {
        File dir = new File(App.ctx().getFilesDir(), "plugin_cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 某个插件的缓存目录 */
    private static File dirOf(String pluginId) {
        File d = new File(root(), Utils.safe(pluginId == null ? "unknown" : pluginId));
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileOf(String pluginId, String name) {
        return new File(dirOf(pluginId), Utils.safe(name == null ? "cache" : name));
    }

    /**
     * 缓存年龄（毫秒）。**没有缓存返回 -1** —— 这是契约约定的哨兵值，
     * 脚本里写作 {@code age >= 0 && age < LIFETIME}。
     */
    public static long age(String pluginId, String name) {
        try {
            File f = fileOf(pluginId, name);
            if (!f.exists()) return -1L;
            long t = f.lastModified();
            if (t <= 0) return -1L;
            return Math.max(0L, System.currentTimeMillis() - t);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 读缓存字符串，没有返回 null */
    public static String read(String pluginId, String name) {
        File f;
        try {
            f = fileOf(pluginId, name);
            if (!f.exists() || f.length() <= 0) return null;
        } catch (Throwable t) {
            return null;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) Math.min(raf.length(), Integer.MAX_VALUE)];
            raf.readFully(buf);
            return new String(buf, Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) try { raf.close(); } catch (IOException ignored) {}
        }
    }

    /** 写缓存（覆盖），顺带刷新 mtime 供 age() 使用 */
    public static void write(String pluginId, String name, String data) {
        FileOutputStream fos = null;
        try {
            File f = fileOf(pluginId, name);
            // 先建一个空文件再写，保证 mtime 是本次
            if (!f.exists()) f.createNewFile();
            fos = new FileOutputStream(f, false);
            fos.write((data == null ? "" : data).getBytes(Charset.forName("UTF-8")));
            fos.flush();
            f.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ignored) {}
        }
    }

    /** 删除单个缓存 */
    public static void remove(String pluginId, String name) {
        try {
            File f = fileOf(pluginId, name);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {}
    }

    /** 清掉某个插件的全部缓存（卸载插件时调用） */
    public static void clear(String pluginId) {
        try {
            File d = dirOf(pluginId);
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            d.delete();
        } catch (Throwable ignored) {}
    }

    /** 全部插件缓存占用的字节数（设置页展示用） */
    public static long totalSize() {
        long n = 0;
        try {
            File[] ds = root().listFiles();
            if (ds == null) return 0;
            for (File d : ds) {
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) n += f.length();
            }
        } catch (Throwable ignored) {}
        return n;
    }
}
