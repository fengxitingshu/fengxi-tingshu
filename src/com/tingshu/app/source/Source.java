package com.tingshu.app.source;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;

import java.util.List;
import java.util.Map;

/**
 * 听书源接口（SPI）。新增一个源 = 实现这个接口 + 在 SourceRegistry 里 register 一行。
 * 与具体站点无关，上层（Aggregator / UI）只认这套方法。
 */
public interface Source {

    /** 稳定 id，存库用，永不变（如 "ting15"） */
    String id();

    /** 显示名（如 "有听网"） */
    String name();

    /** 简介，源管理页展示 */
    String desc();

    /** 是否支持关键词搜索（部分源只有分类） */
    boolean canSearch();

    /**
     * 搜索。返回 null 表示请求失败，返回空 list 表示确实无结果。
     * @param page 从 1 开始
     */
    List<Book> search(String kw, int page) throws Exception;

    /**
     * 详情 + 章节列表。
     * @param bookUrl 搜索返回的 Book.url
     */
    BookDetail detail(String bookUrl) throws Exception;

    /**
     * 取音频直链。可能耗时（多次往返）。
     * 返回 null 表示失败。
     */
    String audioUrl(Chapter ch, Book book) throws Exception;

    /**
     * 播放该音频需要附加的请求头（Referer 等）。
     * 返回 null 表示不需要。
     * 注意：酷我 CDN 带 Referer 会 403，必须返回 null。
     */
    Map<String, String> audioHeaders(String audioUrl);

    /** 封面加载需要的请求头，null 表示不需要 */
    Map<String, String> coverHeaders(String coverUrl);
}
