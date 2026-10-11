package com.tingshu.app.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 统一数据模型。所有源产出的都归一成这几个类，UI 只认一套 JSON。 */
public final class Models {

    /** 书籍 */
    public static class Book {
        public String source;    // 源 id，如 "ting15"
        public String bookId;    // 源内唯一 id
        public String title;
        public String author;
        public String artist;    // 播音
        public String cover;
        public String url;       // 详情页 url（或源自定义的定位串）
        public String intro;
        public String category;  // 分类名（部分源有）

        /** 聚合搜索时记录该书命中的其它源 */
        public List<Book> variants = new ArrayList<Book>();
        /** 命中的源 id 列表（含自己） */
        public List<String> sources = new ArrayList<String>();

        public Book() {}

        public Book(String source, String bookId, String title) {
            this.source = source;
            this.bookId = bookId;
            this.title = title;
        }

        /** 全局唯一键 */
        public String key() {
            return source + ":" + (bookId == null ? "" : bookId);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("source", nz(source));
                o.put("bookId", nz(bookId));
                o.put("title", nz(title));
                o.put("author", nz(author));
                o.put("artist", nz(artist));
                o.put("cover", nz(cover));
                o.put("url", nz(url));
                o.put("intro", nz(intro));
                o.put("category", nz(category));
                o.put("key", key());
                JSONArray sa = new JSONArray();
                for (String s : sources) sa.put(s);
                o.put("sources", sa);
                o.put("sourceCount", Math.max(1, sources.isEmpty() ? 1 : sources.size()));
                // variants 也要回传：自动换源靠它。
                // 只传一层，避免 variants 里再递归套 variants 造成爆炸。
                JSONArray va = new JSONArray();
                if (variants != null) {
                    for (Book v : variants) {
                        if (v == null) continue;
                        JSONObject vo = new JSONObject();
                        vo.put("source", nz(v.source));
                        vo.put("bookId", nz(v.bookId));
                        vo.put("title", nz(v.title));
                        vo.put("author", nz(v.author));
                        vo.put("artist", nz(v.artist));
                        vo.put("cover", nz(v.cover));
                        vo.put("url", nz(v.url));
                        vo.put("intro", nz(v.intro));
                        vo.put("category", nz(v.category));
                        va.put(vo);
                    }
                }
                o.put("variants", va);
            } catch (Throwable ignored) {}
            return o;
        }

        public static Book fromJson(JSONObject o) {
            if (o == null) return null;
            Book b = new Book();
            b.source = o.optString("source", "");
            b.bookId = o.optString("bookId", o.optString("id", ""));
            b.title = o.optString("title", "");
            b.author = o.optString("author", "");
            b.artist = o.optString("artist", "");
            b.cover = o.optString("cover", "");
            b.url = o.optString("url", "");
            b.intro = o.optString("intro", "");
            b.category = o.optString("category", "");

            JSONArray sa = o.optJSONArray("sources");
            if (sa != null) {
                for (int i = 0; i < sa.length(); i++) {
                    String s = sa.optString(i, "");
                    if (s.length() > 0) b.sources.add(s);
                }
            }
            if (b.sources.isEmpty() && b.source.length() > 0) b.sources.add(b.source);

            JSONArray va = o.optJSONArray("variants");
            if (va != null) {
                for (int i = 0; i < va.length(); i++) {
                    JSONObject vo = va.optJSONObject(i);
                    if (vo == null) continue;
                    // 复用自身解析，但 variants 内部不再递归（传进去的 vo 没有 variants 字段）
                    Book v = Book.fromJson(vo);
                    if (v != null) b.variants.add(v);
                }
            }
            if (b.variants.isEmpty()) b.variants.add(b);
            return b;
        }
    }

    /** 章节 */
    public static class Chapter {
        public String title;
        public String url;    // 取链定位串（一般章节页 url，也可能是直链）
        public int index;     // 第几集，1 基
        /** 有些源详情页就把直链给了，这里存着省一次请求 */
        public String directUrl;

        public Chapter() {}

        public Chapter(int index, String title, String url) {
            this.index = index;
            this.title = title;
            this.url = url;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("title", nz(title));
                o.put("url", nz(url));
                o.put("index", index);
                o.put("directUrl", nz(directUrl));
            } catch (Throwable ignored) {}
            return o;
        }
    }

    /** 书籍详情 */
    public static class BookDetail {
        public Book book;
        public String intro;
        public List<Chapter> chapters = new ArrayList<Chapter>();
        public int total;

        public BookDetail() {}

        public BookDetail(Book b) {
            this.book = b;
            this.intro = b == null ? "" : b.intro;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("book", book == null ? new JSONObject() : book.toJson());
                o.put("intro", nz(intro));
                JSONArray ca = new JSONArray();
                for (Chapter c : chapters) ca.put(c.toJson());
                o.put("chapters", ca);
                o.put("total", chapters.size());
            } catch (Throwable ignored) {}
            return o;
        }
    }

    /** 收藏分组 */
    public static class FavGroup {
        public long id;
        public String name;
        public int sort;

        public FavGroup(long id, String name, int sort) {
            this.id = id;
            this.name = name;
            this.sort = sort;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", nz(name));
                o.put("sort", sort);
            } catch (Throwable ignored) {}
            return o;
        }
    }

    /** 播放历史条目（书 + 进度） */
    public static class History {
        public Book book;
        public int lastIndex = 1;        // 最后收听集号
        public long lastPosMs = 0;       // 集内偏移
        public String lastChapUrl = "";
        public String lastChapTitle = "";
        public int chapterCount = 0;
        public long updatedAt = 0;

        public History() {}

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("book", book == null ? new JSONObject() : book.toJson());
                o.put("lastIndex", lastIndex);
                o.put("lastPosMs", lastPosMs);
                o.put("lastChapUrl", nz(lastChapUrl));
                o.put("lastChapTitle", nz(lastChapTitle));
                o.put("chapterCount", chapterCount);
                o.put("updatedAt", updatedAt);
                o.put("updatedText", com.tingshu.app.core.Utils.fmtRelative(updatedAt));
            } catch (Throwable ignored) {}
            return o;
        }
    }

    /** 缓存条目 */
    public static class CacheItem {
        public String key;          // source:bookId
        public int chapterIdx;
        public String path;
        public long size;
        public int status;          // 0待下载 1下载中 2完成 3失败
        public String sourceUrl;
        public long updatedAt;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
