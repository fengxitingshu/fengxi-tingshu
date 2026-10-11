package com.github.eprendre.tingshu.utils;

/**
 * 兼容原版插件的分类菜单项接口。
 *
 * <pre>
 *   interface IMenu { fun getType(): Int }
 * </pre>
 *
 * <p>原版实现里 CategoryTab / CategoryMenu 分别返回 0 / 1，用于在 RecyclerView
 * 里区分多类型 item。本 App 不渲染原版界面，但插件可能调用它，所以保留。
 */
public interface IMenu {
    int getType();
}
