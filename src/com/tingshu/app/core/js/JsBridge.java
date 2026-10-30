package com.tingshu.app.core.js;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ v3.0：JS 对象 ↔ 宿主数据模型的双向转换。
 *
 * <p>插件脚本产出/消费的是 JS 原生对象（{@code NativeObject} / {@code NativeArray}），
 * 宿主内部用的是 {@link Book} / {@link Chapter} / {@link BookDetail}。
 * 这个类把两边的字段映射起来，避免在适配器里到处写 {@code Context.toString(...)}。
 *
 * <h3>字段映射（对齐插件契约）</h3>
 * <pre>
 *   JS Book    { coverUrl, bookUrl, title, author, artist, intro, status }
 *   ↕
 *   Models.Book{ cover,    url,     title, author, artist, intro, ...      }
 *
 *   JS Episode { title, url, isFree }
 *   ↕
 *   Models.Chapter { title, url, index }
 * </pre>
 */
public final class JsBridge {

    private JsBridge() {}

    // ==================== JS → Java ====================

    /** 把 JS 的 books 数组转成 List&lt;Book&gt; */
    public static List<Book> toBooks(String sourceId, Object jsArr) {
        List<Book> out = new ArrayList<Book>();
        int n = len(jsArr);
        for (int i = 0; i < n; i++) {
            Object o = at(jsArr, i);
            if (!(o instanceof Scriptable)) continue;
            Book b = toBook(sourceId, (Scriptable) o);
            if (b != null) out.add(b);
        }
        return out;
    }

    /** 单个 JS Book → Models.Book */
    public static Book toBook(String sourceId, Scriptable o) {
        String title = str(o, "title");
        String url = str(o, "bookUrl");
        // 两者都没有基本是脏数据，丢掉（比如选择器没命中产生的空壳）
        if (title.length() == 0 && url.length() == 0) return null;

        Book b = new Book();
        b.source = sourceId;
        b.title = title;
        b.url = url;
        // bookId 用 url 兜底：聚合去重 / 历史记录都要一个稳定键
        b.bookId = url.length() > 0 ? url : title;
        b.author = str(o, "author");
        b.artist = str(o, "artist");
        b.cover = str(o, "coverUrl");
        b.intro = str(o, "intro");
        String status = str(o, "status");
        b.category = status;      // 暂存到 category（UI 会在书名下展示，语义够用）
        return b;
    }

    /** 把 JS 的 episodes 数组转成 List&lt;Chapter&gt;（index 从 1 开始） */
    public static List<Chapter> toChapters(Object jsArr) {
        List<Chapter> out = new ArrayList<Chapter>();
        int n = len(jsArr);
        for (int i = 0; i < n; i++) {
            Object o = at(jsArr, i);
            if (!(o instanceof Scriptable)) continue;
            Scriptable e = (Scriptable) o;
            String title = str(e, "title");
            String url = str(e, "url");
            if (title.length() == 0 && url.length() == 0) continue;
            Chapter c = new Chapter();
            c.index = out.size() + 1;      // ★ 1 基，与全 App 一致
            c.title = title.length() > 0 ? title : ("第 " + c.index + " 集");
            // 伪 scheme（bili:aid:cid / xm:trackId / kuwo:musicrid）原样保留，
            // 取链时再交回给 JS 的 audio() 处理
            c.url = url;
            out.add(c);
        }
        return out;
    }

    /** 把 JS 的 bookDetail 返回值合并进 BookDetail */
    public static void fillDetail(BookDetail d, Scriptable js) {
        if (js == null) return;
        String intro = str(js, "intro");
        if (intro.length() > 0) d.intro = intro;

        if (d.book != null) {
            String artist = str(js, "artist");
            if (artist.length() > 0) d.book.artist = artist;
            String author = str(js, "author");
            if (author.length() > 0) d.book.author = author;
            String cover = str(js, "coverUrl");
            if (cover.length() > 0) d.book.cover = cover;
            String title = str(js, "title");
            if (title.length() > 0) d.book.title = title;
            String status = str(js, "status");
            if (status.length() > 0) d.book.category = status;
        }

        Object eps = ScriptableObject.getProperty(js, "episodes");
        d.chapters = toChapters(eps);
        d.total = d.chapters.size();
    }

    /** JS 函数返回值 → Map&lt;String,String&gt;（audioHeaders / coverHeaders） */
    public static Map<String, String> toHeaderMap(Object o) {
        if (!(o instanceof Scriptable)) return null;
        Scriptable s = (Scriptable) o;
        Map<String, String> m = new HashMap<String, String>();
        for (Object k : s.getIds()) {
            if (!(k instanceof String)) continue;
            Object v = ScriptableObject.getProperty(s, (String) k);
            if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) continue;
            m.put((String) k, Context.toString(v));
        }
        return m.isEmpty() ? null : m;
    }

    // ==================== Java → JS ====================

    /** 新建一个空 JS 对象 */
    public static Scriptable newObject(Context cx, Scriptable scope) {
        return cx.newObject(scope);
    }

    public static void put(Scriptable o, String k, Object v) {
        ScriptableObject.putProperty(o, k, v);
    }

    // ==================== 底层取值 ====================

    /** 读 JS 对象字符串属性（不存在 / 非字符串都返回 ""） */
    public static String str(Scriptable o, String k) {
        if (o == null) return "";
        Object v = ScriptableObject.getProperty(o, k);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return "";
        if (v instanceof Scriptable) return "";
        String s = Context.toString(v);
        return s == null || "null".equals(s) || "undefined".equals(s) ? "" : s;
    }

    /** 读 JS 布尔属性 */
    public static boolean bool(Scriptable o, String k) {
        if (o == null) return false;
        Object v = ScriptableObject.getProperty(o, k);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return false;
        return Context.toBoolean(v);
    }

    /** 读 int 属性 */
    public static int i(Scriptable o, String k, int def) {
        if (o == null) return def;
        Object v = ScriptableObject.getProperty(o, k);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return def;
        double d = Context.toNumber(v);
        return Double.isNaN(d) ? def : (int) d;
    }

    /** 数组/类数组长度 */
    public static int len(Object o) {
        if (o == null || o == Undefined.instance) return 0;
        if (o instanceof NativeArray) return (int) ((NativeArray) o).size();
        if (o instanceof Scriptable) {
            Object n = ScriptableObject.getProperty((Scriptable) o, "length");
            if (n == null || n == Undefined.instance || n == Scriptable.NOT_FOUND) return 0;
            double d = Context.toNumber(n);
            return Double.isNaN(d) ? 0 : (int) d;
        }
        return 0;
    }

    /** 取下标元素 */
    public static Object at(Object o, int i) {
        if (!(o instanceof Scriptable)) return Undefined.instance;
        Scriptable s = (Scriptable) o;
        if (s instanceof NativeArray) return ((NativeArray) s).get(i, s);
        Object v = s.get(i, s);
        return v == null ? Undefined.instance : v;
    }

    /** JS 对象上是否有同名函数 */
    public static boolean hasFn(Scriptable o, String k) {
        if (o == null) return false;
        return ScriptableObject.getProperty(o, k) instanceof org.mozilla.javascript.Function;
    }
}
