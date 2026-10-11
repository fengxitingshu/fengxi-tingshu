package com.tingshu.app.plugin;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.BrowsableSource;
import com.tingshu.app.source.Category;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 适配器基类：给 {@link com.tingshu.app.source.Source} 提供一组"安全默认实现"，
 * 并默认关闭分类浏览。
 *
 * <p>为什么不直接继承 {@code AbsSource}：
 * AbsSource 里带着一堆 HTML 正则工具与 TTL 缓存，对「把外部对象包一层」的适配器
 * 来说是多余的负担；更重要的是适配器不该被继承链绑到某个具体基类上，
 * 免得将来 AbsSource 变方法时连累插件加载。
 *
 * <p>方法全部是 public 且非 final，子类按需 override。
 */
public abstract class AbsSourceProxy implements BrowsableSource {

    @Override
    public boolean canSearch() {
        return true;
    }

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        return new ArrayList<Book>();
    }

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        d.total = 0;
        return d;
    }

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        return null;
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        return null;
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        return null;
    }

    // ---- BrowsableSource：默认不支持，需要浏览的适配器自己打开 ----

    @Override
    public boolean canBrowse() {
        return false;
    }

    @Override
    public List<Category> categories() {
        return new ArrayList<Category>();
    }

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        return new ArrayList<Book>();
    }
}
