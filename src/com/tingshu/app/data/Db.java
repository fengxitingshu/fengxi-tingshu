package com.tingshu.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.tingshu.app.core.App;

/**
 * SQLite 封装。单例 + 写操作串行化，避免 database locked。
 * 所有表结构集中在这里，方便后续迁移。
 */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "tingshu.db";
    /** v5：custom_source 增加 origin 列（记录导入方式，供源管理分组） */
    private static final int VERSION = 7;

    private static Db INSTANCE;
    /** 写锁：SQLite 在多线程并发写时会 locked，统一串行 */
    private final Object writeLock = new Object();

    private Db(Context c) {
        super(c, NAME, null, VERSION);
    }

    public static synchronized Db get(Context c) {
        if (INSTANCE == null) INSTANCE = new Db(c.getApplicationContext());
        return INSTANCE;
    }

    /**
     * 全局单例。App.onCreate 里已经 init 过，正常不会为 null。
     * 但为了防御"静态初始化顺序 / 多进程 / 极端时序"下的 NPE，
     * 这里兜底用 Application Context 补建一次，而不是直接返回 null。
     */
    public static Db get() {
        if (INSTANCE == null) {
            Context c = App.ctx();
            if (c == null) throw new IllegalStateException("Db 未初始化：Application 尚未创建");
            synchronized (Db.class) {
                if (INSTANCE == null) INSTANCE = new Db(c.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    /** App.onCreate 里显式初始化，避免首次访问踩空 */
    public static synchronized void init(Context c) {
        if (INSTANCE == null) INSTANCE = new Db(c.getApplicationContext());
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS fav ("
                + "key TEXT PRIMARY KEY,"
                + "source TEXT, book_id TEXT,"
                + "title TEXT, author TEXT, artist TEXT, cover TEXT, url TEXT, intro TEXT,"
                + "group_id INTEGER DEFAULT 0,"
                + "added_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fav_group ON fav(group_id, added_at DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS fav_group ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "sort INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE IF NOT EXISTS history ("
                + "key TEXT PRIMARY KEY,"
                + "source TEXT, book_id TEXT,"
                + "title TEXT, author TEXT, artist TEXT, cover TEXT, url TEXT, intro TEXT,"
                + "last_index INTEGER DEFAULT 1,"
                + "last_pos_ms INTEGER DEFAULT 0,"
                + "last_chap_url TEXT, last_chap_title TEXT,"
                + "chapter_count INTEGER DEFAULT 0,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON history(updated_at DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS progress ("
                + "book_key TEXT, chapter_idx INTEGER, pos_ms INTEGER,"
                + "PRIMARY KEY(book_key, chapter_idx))");

        db.execSQL("CREATE TABLE IF NOT EXISTS source_conf ("
                + "source_id TEXT PRIMARY KEY,"
                + "enabled INTEGER DEFAULT 1,"
                + "sort INTEGER DEFAULT 0,"
                + "alias TEXT)");

        db.execSQL("CREATE TABLE IF NOT EXISTS cache ("
                + "key TEXT, chapter_idx INTEGER,"
                + "path TEXT, size INTEGER DEFAULT 0,"
                + "status INTEGER DEFAULT 0,"
                + "source_url TEXT, updated_at INTEGER,"
                + "PRIMARY KEY(key, chapter_idx))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cache_book ON cache(key)");

        // ★ v2.9：下载表 —— 与 cache 彻底分家。
        //   区别：cache 是自动预读的临时副本（受容量上限淘汰），
        //        download 是用户主动要的文件（永不自动删除）。
        //   多两列：saved_uri / saved_path —— 用户选了外部文件夹后，
        //   文件会「移动」过去（复制→校验→删源），这里记下它落在哪。
        db.execSQL("CREATE TABLE IF NOT EXISTS download ("
                + "key TEXT, chapter_idx INTEGER,"
                + "path TEXT, size INTEGER DEFAULT 0,"
                + "status INTEGER DEFAULT 0,"
                + "source_url TEXT,"
                + "saved_uri TEXT, saved_path TEXT,"
                + "updated_at INTEGER,"
                + "PRIMARY KEY(key, chapter_idx))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_download_book ON download(key)");

        // 自定义源：config 存原始 JSON（导出时原样吐出，不丢字段），
        // name/desc 冗余一列方便列表排序展示不用反复解析 JSON。
        db.execSQL("CREATE TABLE IF NOT EXISTS custom_source ("
                + "source_id TEXT PRIMARY KEY,"
                + "name TEXT,"
                + "desc TEXT,"
                + "config TEXT NOT NULL,"
                + "origin TEXT DEFAULT '',"
                + "sort INTEGER DEFAULT 100,"
                + "added_at INTEGER)");

        // 插件书源：jar 插件走 DexClassLoader，JS 插件走 Rhino 引擎。
        // ★ v3.0 加 kind 列区分（"jar" / "js"），老数据默认 jar。
        // file_name 冗余一列给源管理页展示；enabled 停用后不再加载。
        db.execSQL("CREATE TABLE IF NOT EXISTS plugin_jar ("
                + "file_path TEXT PRIMARY KEY,"
                + "file_name TEXT,"
                + "source_ids TEXT,"
                + "enabled INTEGER DEFAULT 1,"
                + "kind TEXT DEFAULT 'jar',"
                + "added_at INTEGER)");

        // 搜索历史：kw 主键（同名去重，时间刷新），方便「点一下重新搜」
        db.execSQL("CREATE TABLE IF NOT EXISTS search_hist ("
                + "kw TEXT PRIMARY KEY,"
                + "hits INTEGER DEFAULT 0,"
                + "used_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_search_time ON search_hist(used_at DESC)");

        db.execSQL("INSERT OR IGNORE INTO fav_group(id, name, sort) VALUES(0, '默认分组', 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v1 -> v2：只加了一张新表，老数据不动
        if (oldV < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS custom_source ("
                    + "source_id TEXT PRIMARY KEY,"
                    + "name TEXT,"
                    + "desc TEXT,"
                    + "config TEXT NOT NULL,"
                    + "sort INTEGER DEFAULT 100,"
                    + "added_at INTEGER)");
        }
        // v2 -> v3：jar 插件表
        if (oldV < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS plugin_jar ("
                    + "file_path TEXT PRIMARY KEY,"
                    + "file_name TEXT,"
                    + "source_ids TEXT,"
                    + "enabled INTEGER DEFAULT 1,"
                    + "added_at INTEGER)");
        }
        // v4 -> v5：老数据补 origin 列（老源统一标为「本地导入」）
        if (oldV < 5) {
            try {
                db.execSQL("ALTER TABLE custom_source ADD COLUMN origin TEXT DEFAULT ''");
            } catch (Throwable ignored) {}
        }
        // v3 -> v4：搜索历史表
        if (oldV < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS search_hist ("
                    + "kw TEXT PRIMARY KEY,"
                    + "hits INTEGER DEFAULT 0,"
                    + "used_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_search_time ON search_hist(used_at DESC)");
        }
        // v5 -> v6：★ v2.9 下载表（与 cache 分家）。
        //   老 cache 表数据一律不动 —— 按约定归入「缓存」池，
        //   继续受 cacheLimitMb 上限管理（用户已确认选 A）。
        if (oldV < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS download ("
                    + "key TEXT, chapter_idx INTEGER,"
                    + "path TEXT, size INTEGER DEFAULT 0,"
                    + "status INTEGER DEFAULT 0,"
                    + "source_url TEXT,"
                    + "saved_uri TEXT, saved_path TEXT,"
                    + "updated_at INTEGER,"
                    + "PRIMARY KEY(key, chapter_idx))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_download_book ON download(key)");
        }
        // v6 -> v7：★ v3.0 插件表加 kind 列（区分 jar / js 插件）。
        //   老记录一律补 'jar' —— 它们确实都是 jar，语义不丢。
        if (oldV < 7) {
            try {
                db.execSQL("ALTER TABLE plugin_jar ADD COLUMN kind TEXT DEFAULT 'jar'");
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        try {
            db.enableWriteAheadLogging();
        } catch (Throwable ignored) {}
    }

    // ==================== 通用读写 ====================

    public Cursor rawQuery(String sql, String[] args) {
        return getReadableDatabase().rawQuery(sql, args);
    }

    public void exec(String sql) {
        synchronized (writeLock) {
            getWritableDatabase().execSQL(sql);
        }
    }

    public long insert(String table, ContentValues v) {
        synchronized (writeLock) {
            return getWritableDatabase().insertWithOnConflict(
                    table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    public int update(String table, ContentValues v, String where, String[] args) {
        synchronized (writeLock) {
            return getWritableDatabase().update(table, v, where, args);
        }
    }

    public int delete(String table, String where, String[] args) {
        synchronized (writeLock) {
            return getWritableDatabase().delete(table, where, args);
        }
    }

    /** 事务内批量写；失败不抛，返回是否成功 */
    public boolean transaction(Runnable r) {
        synchronized (writeLock) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                r.run();
                db.setTransactionSuccessful();
                return true;
            } catch (Throwable t) {
                return false;
            } finally {
                try {
                    db.endTransaction();
                } catch (Throwable ignored) {}
            }
        }
    }

    // ==================== Cursor 取值工具 ====================

    public static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0) return "";
        String v = c.getString(i);
        return v == null ? "" : v;
    }

    public static int i(Cursor c, String col, int def) {
        int i = c.getColumnIndex(col);
        if (i < 0) return def;
        return c.getInt(i);
    }

    public static long l(Cursor c, String col, long def) {
        int i = c.getColumnIndex(col);
        if (i < 0) return def;
        return c.getLong(i);
    }

    public static void close(Cursor c) {
        if (c != null) {
            try { c.close(); } catch (Throwable ignored) {}
        }
    }
}
