package com.github.eprendre.tingshu.sources;

import com.github.eprendre.tingshu.utils.Book;
import com.github.eprendre.tingshu.utils.BookDetail;
import com.github.eprendre.tingshu.utils.Category;
import com.github.eprendre.tingshu.utils.CategoryMenu;

import java.util.Collections;
import java.util.List;

/**
 * 兼容原版「我的听书」(com.github.eprendre.tingshu) 的源基类。
 *
 * <p>第三方 jar 插件继承本类并实现抽象方法。宿主通过
 * {@code com.tingshu.app.plugin.TingShuAdapter} 把它包装成
 * {@link com.tingshu.app.source.Source} 使用。
 *
 * <h3>签名对齐要点</h3>
 * <ol>
 *   <li>所有抽象方法名/参数/返回值必须与 Kotlin 编译产物一致，否则插件里的
 *       {@code invoke-virtual} 解析不到会抛 {@code NoSuchMethodError}。</li>
 *   <li>{@code getBookDetailInfo} 的 Kotlin 默认参数会生成
 *       {@code getBookDetailInfo$default(...)} 静态桥接，必须原样提供。</li>
 *   <li>非抽象的 open 方法（isSearchable 等）插件可能不 override，
 *       宿主直接调基类实现即可。</li>
 * </ol>
 *
 * <h3>实现建议</h3>
 * 本类刻意保持「纯声明」，不含任何 Android / 网络逻辑，方便插件在
 * 任意环境（含单元测试）里链接。
 */
public abstract class TingShu {

    public TingShu() {}

    // ==================== 抽象方法（插件必须实现） ====================

    /** 源唯一 id，不能与其它源重复 */
    public abstract String getSourceId();

    /** 站点根地址，用于判断链接归属 */
    public abstract String getUrl();

    /** 站点名称 */
    public abstract String getName();

    /**
     * 搜索。
     * @return Pair&lt;List&lt;Book&gt;, Int&gt;，第二个 Int 是总页数（用于判断是否还有下一页）
     */
    public abstract Object search(String keywords, int page);

    /** 返回该源使用的取链器 */
    public abstract AudioUrlExtractor getAudioUrlExtractor();

    /** 分类菜单（发现页用） */
    public abstract List<CategoryMenu> getCategoryMenus();

    /** 拉某个分类 url 的一页列表 */
    public abstract Category getCategoryList(String url);

    /**
     * 书本详情 + 章节列表。
     * 默认参数用 {@code $default} 桥接暴露。
     */
    public abstract BookDetail getBookDetailInfo(String bookUrl, boolean loadEpisodes, boolean loadFullPages);

    /** Kotlin 默认参数的桥接方法（掩码 bit2/bit4 表示第 2/3 个参数缺省） */
    public static BookDetail getBookDetailInfo$default(TingShu self, String bookUrl,
                                                       boolean loadEpisodes, boolean loadFullPages,
                                                       int mask, Object marker) {
        if ((mask & 2) != 0) loadEpisodes = true;
        if ((mask & 4) != 0) loadFullPages = true;
        return self.getBookDetailInfo(bookUrl, loadEpisodes, loadFullPages);
    }

    // ==================== open 方法（有默认实现） ====================

    public String getDesc() {
        return "";
    }

    public boolean isMultipleEpisodePages() {
        return false;
    }

    /** 取消翻页加载 */
    public void reset() {}

    /** 是否可搜索 */
    public boolean isSearchable() {
        return true;
    }

    /** 是否有发现分类 */
    public boolean isDiscoverable() {
        return true;
    }

    /** 音频是否可缓存 */
    public boolean isCacheable() {
        return true;
    }

    /** 解析是否不依赖 WebView（手表等无 WebView 设备也能用） */
    public boolean isWebViewNotRequired() {
        return false;
    }

    public boolean isWebViewCompatible() {
        return false;
    }

    public boolean method238() {
        return false;
    }

    public boolean c24e329b36b542f4adde9694d4b28a4r() {
        return false;
    }

    // ==================== 给适配层的小工具 ====================

    /** 安全的搜索包装：返回的 Pair 结构由适配层解析 */
    protected static <A, B> Object pair(A a, B b) {
        return new Pair<A, B>(a, b);
    }

    /**
     * 极简 Pair 替身。
     *
     * <p>原版 search 返回的是 {@code kotlin.Pair}。为了让插件「构造自己返回的 Pair」
     * 这一边（在插件 dex 内）能工作，插件必须有 kotlin-stdlib；宿主这边不需要
     * kotlin-stdlib 也不该去 new kotlin.Pair。
     *
     * <p>适配层解析返回值时用**反射读 getFirst/getSecond**，所以无论插件返回的是
     * 真的 {@code kotlin.Pair} 还是别的实现，只要方法名对就能取到值。
     * 本类只在「宿主需要造一个 Pair 给插件」时兜底，正常路径用不到。
     */
    public static final class Pair<A, B> {
        private final A first;
        private final B second;

        public Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }

        public A getFirst() { return first; }
        public B getSecond() { return second; }

        @Override
        public String toString() {
            return "Pair(" + first + ", " + second + ")";
        }
    }

    /** 空分类，避免插件返回 null 时适配层 NPE */
    protected static Category emptyCategory(int page) {
        return new Category(Collections.<Book>emptyList(), page, page, "", "");
    }
}
