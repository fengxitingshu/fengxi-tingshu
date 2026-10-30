package com.github.eprendre.tingshu.utils;

import java.util.Collections;
import java.util.List;

/**
 * 兼容原版插件的分类大组（一组子标签）。
 *
 * <pre>
 *   data class CategoryMenu(val title: String, val tabs: List&lt;CategoryTab&gt;) : IMenu {
 *       override fun getType(): Int
 *   }
 * </pre>
 *
 * <p>getType 原版返回 1（表示「大分类」类型）。
 */
public class CategoryMenu implements IMenu {

    public final String title;
    public final List<CategoryTab> tabs;

    public CategoryMenu(String title, List<CategoryTab> tabs) {
        this.title = title;
        this.tabs = tabs == null ? Collections.<CategoryTab>emptyList() : tabs;
    }

    public String getTitle() { return title; }
    public List<CategoryTab> getTabs() { return tabs; }

    public String component1() { return title; }
    public List<CategoryTab> component2() { return tabs; }

    @Override
    public int getType() { return 1; }

    /** data class 的 equals 按 title + tabs 比较 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CategoryMenu)) return false;
        CategoryMenu m = (CategoryMenu) o;
        if (title == null ? m.title != null : !title.equals(m.title)) return false;
        return tabs == null ? m.tabs == null : tabs.equals(m.tabs);
    }

    @Override
    public int hashCode() {
        int h = title == null ? 0 : title.hashCode();
        h = 31 * h + (tabs == null ? 0 : tabs.hashCode());
        return h;
    }

    @Override
    public String toString() {
        return "CategoryMenu(title=" + title + ", tabs=" + tabs.size() + ")";
    }
}
