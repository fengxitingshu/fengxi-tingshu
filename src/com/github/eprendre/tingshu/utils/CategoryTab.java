package com.github.eprendre.tingshu.utils;

/**
 * 兼容原版插件的分类子标签（一个可点击的分类入口）。
 *
 * <pre>
 *   data class CategoryTab(val title: String, val url: String) : IMenu {
 *       override fun getType(): Int
 *   }
 * </pre>
 *
 * <p>getType 原版返回 0（表示「标签」类型）。
 */
public class CategoryTab implements IMenu {

    public final String title;
    public final String url;

    public CategoryTab(String title, String url) {
        this.title = title;
        this.url = url;
    }

    public String getTitle() { return title; }
    public String getUrl() { return url; }

    public String component1() { return title; }
    public String component2() { return url; }

    @Override
    public int getType() { return 0; }

    @Override
    public String toString() {
        return "CategoryTab(title=" + title + ", url=" + url + ")";
    }
}
