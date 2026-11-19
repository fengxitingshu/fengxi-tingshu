package com.tingshu.app.source;

import com.tingshu.app.data.Models.Book;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ v4.21.44：分类翻页的 URL 规则与「重复页自愈」候选集。
 *
 * <p>JS 插件源（{@link JsSourceAdapter}）与「我的听书」jar 插件源
 * （{@link com.tingshu.app.plugin.TingShuAdapter}）共用这一套规则，
 * 避免两条链路各写一份、改了一边漏了另一边。
 *
 * <h3>为什么需要候选集</h3>
 * 插件的分页地址写法并不统一：有的在尾部 {@code #N}，有的用
 * {@code ?page=N}，有的用路径 {@code /page/N}。宿主光凭猜一种写法，
 * 猜错时每一页都会请求同一个地址，前端就会连翻 5 页全是同一批书，
 * 最后提示「后面几页都是重复内容」。所以只在确认「本页与上一页完全相同」
 * 时才逐个试其它写法，命中后把模板记下来，后续页直接套用。
 */
public final class PagePaging {

    private PagePaging() {}

    /** 缓存键分隔符，用控制字符避免和分类地址本身的字符撞车 */
    public static final String SEP = String.valueOf((char) 1);
    /** 分页模板里的页码占位符 */
    public static final String TPL = "{page}";

    public static String key(String catId, int page) {
        return catId + SEP + page;
    }

    /** 返回分类地址尾部的 {@code #纯数字} 页码；没有或不是纯数字返回 -1。 */
    public static int tailPage(String s) {
        if (s == null) return -1;
        int h = s.lastIndexOf('#');
        if (h < 0) return -1;
        String t = s.substring(h + 1);
        if (t.length() == 0) return -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        try {
            return Integer.parseInt(t);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 按契约把页号写进分类地址：尾部已有 #N 就替换，没有就追加。 */
    public static String withPage(String catId, int page) {
        int h = catId.lastIndexOf('#');
        if (tailPage(catId) >= 0) return catId.substring(0, h) + "#" + page;
        return catId + "#" + page;
    }

    /** 首屏归一：尾部带 {@code #N}(N&gt;1) 时回到第 1 页，避免第 2 页撞车。 */
    public static String firstPageUrl(String catId) {
        return tailPage(catId) > 1 ? withPage(catId, 1) : catId;
    }

    /** 一页书的指纹（书名 + 地址），用于判断是否与上一页完全相同。 */
    public static String sig(List<Book> books) {
        StringBuilder sb = new StringBuilder();
        if (books == null) return "";
        int n = Math.min(books.size(), 40);
        for (int i = 0; i < n; i++) {
            Book b = books.get(i);
            if (b == null) continue;
            sb.append(b.title == null ? "" : b.title).append('\u0002')
              .append(b.url == null ? "" : b.url).append('\u0001');
        }
        return sb.toString();
    }

    public static String applyTpl(String tpl, int page) {
        return tpl.replace(TPL, String.valueOf(page));
    }

    /**
     * 常见分页写法候选集，每项 = {@code { 本页实际地址, 带 {page} 的模板 }}。
     * 顺序按命中概率排：地址里本来就有页码参数的最优先，其次契约 {@code #N}。
     */
    public static List<String[]> candidates(String catId, int page) {
        List<String[]> out = new ArrayList<String[]>();
        String base = catId;
        int h = base.lastIndexOf('#');
        if (tailPage(base) >= 0) base = base.substring(0, h);
        String p = String.valueOf(page);

        // 地址里本来就有 page=/p= 参数：优先替换数值
        if (base.matches("(?i).*[?&](?:page|p)=\\d+.*")) {
            String tpl = base.replaceAll("(?i)([?&](?:page|p)=)\\d+", "$1" + TPL);
            out.add(new String[]{applyTpl(tpl, page), tpl});
        }
        // 契约兜底：尾部 #页码
        out.add(new String[]{base + "#" + p, base + "#" + TPL});
        String q = base.indexOf('?') >= 0 ? "&" : "?";
        out.add(new String[]{base + q + "page=" + p, base + q + "page=" + TPL});
        out.add(new String[]{base + q + "p=" + p, base + q + "p=" + TPL});
        String trim = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        out.add(new String[]{trim + "/page/" + p, trim + "/page/" + TPL});
        out.add(new String[]{trim + "/" + p, trim + "/" + TPL});
        int dot = base.lastIndexOf('.');
        int slash = base.lastIndexOf('/');
        if (dot > slash && (base.endsWith(".html") || base.endsWith(".htm"))) {
            out.add(new String[]{base.substring(0, dot) + "_" + p + base.substring(dot),
                    base.substring(0, dot) + "_" + TPL + base.substring(dot)});
        }
        return out;
    }
}
