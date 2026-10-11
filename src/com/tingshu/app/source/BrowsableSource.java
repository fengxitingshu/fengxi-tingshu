package com.tingshu.app.source;

import com.tingshu.app.data.Models.Book;

import java.util.List;

/**
 * 可浏览（分类发现）的源。
 *
 * <p>刻意做成 {@link Source} 的**子接口**而不是往 Source 里加方法：
 * 老的 jar 插件只实现 Source，若 Source 新增抽象方法，这些插件运行时会
 * AbstractMethodError。子接口方案下，旧插件完全不受影响，
 * 想支持发现页的源（内置 / 配置 / 新插件）才实现它。
 */
public interface BrowsableSource extends Source {

    /** 是否支持分类浏览 */
    boolean canBrowse();

    /** 可浏览的分类清单（发现页的标签） */
    List<Category> categories();

    /**
     * 拉某个分类的一页列表。
     * @param catId 来自 {@link #categories()} 的 Category.id
     * @param page  从 1 开始
     */
    List<Book> browse(String catId, int page) throws Exception;
}
