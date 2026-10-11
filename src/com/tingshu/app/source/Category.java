package com.tingshu.app.source;

/**
 * 一个可浏览的分类（发现页的筛选标签）。
 * id 是传给 {@link BrowsableSource#browse(String, int)} 的定位串，
 * 对内置源可能是站点路径段（如 "pbook"），对配置源可能是模板渲染出的 URL。
 */
public final class Category {

    public final String id;
    public final String name;

    public Category(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
