package com.github.eprendre.tingshu.utils;

import java.util.Collections;
import java.util.List;

/**
 * 兼容原版插件的分类列表结果。
 *
 * <pre>
 *   data class Category(
 *       val list: List&lt;Book&gt;,
 *       val currentPage: Int,
 *       val totalPage: Int,
 *       val currentUrl: String,
 *       val nextUrl: String
 *   )
 * </pre>
 */
public class Category {

    public final List<Book> list;
    public final int currentPage;
    public final int totalPage;
    public final String currentUrl;
    public final String nextUrl;

    public Category(List<Book> list, int currentPage, int totalPage,
                    String currentUrl, String nextUrl) {
        this.list = list == null ? Collections.<Book>emptyList() : list;
        this.currentPage = currentPage;
        this.totalPage = totalPage;
        this.currentUrl = currentUrl == null ? "" : currentUrl;
        this.nextUrl = nextUrl == null ? "" : nextUrl;
    }

    public List<Book> getList() { return list; }
    public int getCurrentPage() { return currentPage; }
    public int getTotalPage() { return totalPage; }
    public String getCurrentUrl() { return currentUrl; }
    public String getNextUrl() { return nextUrl; }

    /** 组件访问器（Kotlin data class 会生成 componentN） */
    public List<Book> component1() { return list; }
    public int component2() { return currentPage; }
    public int component3() { return totalPage; }
    public String component4() { return currentUrl; }
    public String component5() { return nextUrl; }

    @Override
    public String toString() {
        return "Category(currentPage=" + currentPage + ", totalPage=" + totalPage
                + ", size=" + list.size() + ")";
    }
}
