package com.tingshu.app.core.js;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ v3.0：jsoup 的 JS 薄壳。
 *
 * <p>JS 插件契约里 {@code host.parseHtml(html, base)} 返回的文档对象需要支持
 * 一整套链式调用：{@code select / first / byId / text / ownText / attr / absUrl /
 * exists / map / filter / forEach / children / parent / tag / length}。
 *
 * <h3>最关键的设计：「空元素」语义</h3>
 * 插件脚本（见 common.js 的 {@code Shun.none}）大量依赖「取不到就是空对象」的假设，
 * 链式调用必须**永不返回 null**：
 * <pre>
 *   doc.first('.x').first('a').absUrl('href')   // 任何一层没命中都不能崩
 * </pre>
 * 而 jsoup 的 {@code select()} 返回空 {@code Elements}、{@code first()} 返回 {@code null}。
 * 这里统一包成 {@link JsElement}：内部 {@code el} 为 null 时所有取值方法返回 {@code ''}，
 * {@code exists()} 返回 false，{@code select()} 返回空数组，{@code first()} 返回自身。
 *
 * <h3>数组 vs 元素</h3>
 * 契约里 {@code select()} 的返回值既有 {@code .length} 又能 {@code .map()}，
 * 所以【列表】和【单个元素】都用同一个壳类表示，靠 {@code list != null} 区分。
 */
public class JsElement extends ScriptableObject {

    private static final long serialVersionUID = 1L;

    /** 空元素单例语义：el == null 且 list == null */
    private Element el;
    /** 列表语义（select 的返回值） */
    private Elements list;

    public JsElement() {}

    // ==================== 数组下标支持 ====================

    /**
     * ★ 必须实现：脚本里到处是 {@code var list = node.select('a'); list[i].text()}。
     *
     * <p>{@code ScriptableObject} 默认**不支持整数下标取值**，不覆写这里的话
     * {@code list[0]} 会拿到 {@code undefined}，紧接着 {@code .text()} 就
     * 抛 "Cannot call method "text" of undefined"（common.js:27 那个错误）。
     *
     * <p>越界同样返回「空元素」而不是 undefined，呼应 common.js 里
     * {@code Shun.none} 的设计 —— 脚本会在拿到结果后直接链式取值。
     */
    @Override
    public Object get(int index, Scriptable start) {
        if (list != null) {
            if (index < 0 || index >= list.size()) return none();
            return of(list.get(index));
        }
        // 单元素壳用 [0] 取自己也说得通（脚本里偶尔这么写）
        if (index == 0 && el != null) return this;
        return none();
    }

    @Override
    public boolean has(int index, Scriptable start) {
        if (list != null) return index >= 0 && index < list.size();
        return index == 0 && el != null;
    }

    /**
     * 让 {@code for (var k in list)} / {@code Object.keys(list)} 能枚举出下标 + length。
     * 不返回下标的话，脚本里 {@code for...in} 遍历会一个都拿不到。
     */
    @Override
    public Object[] getIds() {
        int n = length();
        Object[] ids = new Object[n + 1];
        for (int i = 0; i < n; i++) ids[i] = Integer.valueOf(i);
        ids[n] = "length";
        return ids;
    }

    /**
     * 数组形态的壳在 JS 侧要能被当作「类数组」：
     * {@code Array.isArray} 仍是 false（Rhino 认的是 NativeArray），
     * 但 {@code length} 属性 + 数字下标已经够插件里那些写法用了。
     */
    @Override
    public String getClassName() { return list != null ? "Elements" : "Element"; }

    /**
     * ★ 必须覆写：Rhino 在「拿它当字符串用」时会调 {@code getDefaultValue}，
     * 而 {@link ScriptableObject} 的默认实现会去找同名原型类，找不到就抛
     * {@code TypeError: Cannot find default value for object}。
     *
     * <p>典型触发场景：脚本写 {@code list[i].select('a') + ''}，或者我们对某处
     * 用了隐式拼接。这里退化成「元素的 text」，符合 jsoup / DOM 的直觉。
     */
    @Override
    public Object getDefaultValue(Class<?> hint) {
        if (hint == Number.class) return Integer.valueOf(length());
        return textOfThis();
    }

    // ==================== 工厂 ====================

    static JsElement of(Element e) {
        JsElement j = new JsElement();
        j.el = e;
        return j.init();
    }

    static JsElement ofList(Elements es) {
        JsElement j = new JsElement();
        j.list = es == null ? new Elements() : es;
        return j.init();
    }

    /** 空元素：什么都能调，什么都取不到 */
    static JsElement none() {
        return new JsElement().init();
    }

    // ==================== 注册实例方法 ====================

    /**
     * 把方法挂到对象上。Rhino 里用 {@code put} 挂 BaseFunction 最直接，
     * 因为壳对象不需要原型链。
     */
    JsElement init() {
        bind("select", 1);
        bind("first", 1);
        bind("byId", 1);
        bind("text", 0);
        bind("ownText", 0);
        bind("attr", 1);
        bind("absUrl", 1);
        bind("exists", 0);
        bind("size", 0);
        bind("map", 1);
        bind("filter", 1);
        bind("forEach", 1);
        // 列表壳的数组方法：插件里 .map().join(',') 这种链式写法很常见
        bind("join", 1);
        bind("indexOf", 1);
        bind("slice", 2);
        // 元素方法里 html / next 也都有用到
        bind("html", 0);
        bind("next", 1);
        bind("children", 0);
        bind("parent", 0);
        bind("tag", 0);
        return this;
    }

    /**
     * {@code length} 要**动态**读 —— 不能在建对象时固化成一个 int 属性，
     * 否则 {@code for (var i = 0; i < list.length; i++)} 里若 list 后来变了就对不上。
     * 用 getter 的方式挂到对象上。
     */
    @Override
    public Object get(String name, Scriptable start) {
        if ("length".equals(name)) return Integer.valueOf(length());
        return super.get(name, start);
    }

    @Override
    public boolean has(String name, Scriptable start) {
        if ("length".equals(name)) return true;
        return super.has(name, start);
    }

    private int length() {
        if (list != null) return list.size();
        return el == null ? 0 : 1;
    }

    /** 挂一个方法到当前对象 */
    private void bind(final String name, final int argc) {
        put(name, this, new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                // scope 是 Rhino 传进来的（全局作用域），
                // cx.newArray(scope, n) 与回调 JS 函数都靠它 —— 实测可用。
                return invoke(cx, scope, name, args);
            }
        });
    }

    // ==================== 取当前对象里的元素 ====================

    /** 本壳代表的所有 jsoup 元素（单元素或列表归一化） */
    private List<Element> elems() {
        List<Element> out = new ArrayList<Element>();
        if (list != null) {
            out.addAll(list);
        } else if (el != null) {
            out.add(el);
        }
        return out;
    }

    /** 第一个元素，没有则 null */
    private Element firstEl() {
        if (list != null) return list.size() > 0 ? list.first() : null;
        return el;
    }

    /** 当前壳首个元素的 text()（join 在单元素壳上的兜底） */
    private String textOfThis() {
        Element e = firstEl();
        return e == null ? "" : e.text();
    }

    // ==================== 方法实现 ====================

    private Object invoke(Context cx, Scriptable scope, String name, Object[] args) {
        // ---- 数组通用：length / map / filter / forEach ----
        if ("length".equals(name)) return Integer.valueOf(length());

        switch (name) {
            case "exists":
                return el != null || (list != null && list.size() > 0);
            case "size":
                return Integer.valueOf(length());

            case "text": {
                Element e = firstEl();
                return e == null ? "" : e.text();
            }
            case "ownText": {
                Element e = firstEl();
                return e == null ? "" : e.ownText();
            }
            case "tag": {
                Element e = firstEl();
                return e == null ? "" : e.tagName().toLowerCase();
            }
            case "html": {
                Element e = firstEl();
                return e == null ? "" : e.html();
            }
            case "attr": {
                Element e = firstEl();
                if (e == null || args.length < 1) return "";
                String v = e.attr(Context.toString(args[0]));
                return v == null ? "" : v;
            }
            case "absUrl": {
                Element e = firstEl();
                if (e == null || args.length < 1) return "";
                String v = e.absUrl(Context.toString(args[0]));
                return v == null ? "" : v;
            }

            case "first": {
                if (args.length < 1) return this;
                String sel = Context.toString(args[0]);
                if (list != null) {
                    return list.isEmpty() ? none() : of(list.first().select(sel).first());
                }
                if (el == null) return none();
                return of(el.select(sel).first());
            }
            case "byId": {
                if (args.length < 1) return none();
                String id = Context.toString(args[0]);
                Element root = firstEl();
                if (root == null) return none();
                return of(root.getElementById(id));
            }
            case "select": {
                if (args.length < 1) return ofList(new Elements());
                String sel = Context.toString(args[0]);
                Elements acc = new Elements();
                for (Element e : elems()) acc.addAll(e.select(sel));
                return ofList(acc);
            }
            case "children": {
                Elements acc = new Elements();
                for (Element e : elems()) acc.addAll(e.children());
                return ofList(acc);
            }
            case "parent": {
                Element e = firstEl();
                if (e == null) return none();
                Element p = e.parent();
                return of(p);
            }
            case "next": {
                Element e = firstEl();
                if (e == null) return none();
                // 无参 → 下一个兄弟；带选择器 → 同名兄弟里按选择器过滤
                if (args.length < 1) return of(e.nextElementSibling());
                return of(e.nextElementSibling() != null
                        ? e.nextElementSibling().select(Context.toString(args[0])).first() : null);
            }

            // ---- 列表壳的数组方法（让 .map().join() 这类链式写法成立） ----
            case "join": {
                if (list == null) return textOfThis();
                String sep = args.length > 0 ? Context.toString(args[0]) : ",";
                StringBuilder sb = new StringBuilder();
                for (Element e : list) {
                    if (sb.length() > 0) sb.append(sep);
                    sb.append(e.text());
                }
                return sb.toString();
            }
            case "indexOf": {
                if (args.length < 1) return -1;
                Object needle = args[0];
                List<Element> all = elems();
                for (int i = 0; i < all.size(); i++) {
                    // 支持 indexOf('文本') —— 按 text() 比对，插件里就是这么用的
                    if (all.get(i).text().indexOf(Context.toString(needle)) >= 0) return i;
                }
                return -1;
            }
            case "slice": {
                List<Element> all = elems();
                int n = all.size();
                int from = args.length > 0 ? (int) Context.toNumber(args[0]) : 0;
                int to = args.length > 1 ? (int) Context.toNumber(args[1]) : n;
                if (from < 0) from = Math.max(0, n + from);
                if (to < 0) to = Math.max(0, n + to);
                from = Math.max(0, Math.min(from, n));
                to = Math.max(from, Math.min(to, n));
                Elements sub = new Elements();
                for (int i = from; i < to; i++) sub.add(all.get(i));
                return ofList(sub);
            }

            // ---- 需要回调 JS 函数 ----
            case "map": {
                // ★ 必须用 cx.newArray(scope, n) 建数组。
                //   `new NativeArray(n)` 造出来的数组 **没有 prototype**，
                //   于是 .join / .indexOf 全都不存在，脚本里
                //   常见的 `.map(...).join(',')` 会直接抛
                //   "TypeError: Cannot find default value for object"。
                if (args.length < 1 || !(args[0] instanceof Function)) return cx.newArray(scope, 0);
                Function fn = (Function) args[0];
                List<Element> all = elems();
                Scriptable out = cx.newArray(scope, all.size());
                for (int i = 0; i < all.size(); i++) {
                    Object r = fn.call(cx, scope, this,
                            new Object[]{of(all.get(i)), Integer.valueOf(i), this});
                    out.put(i, out, r);
                }
                return out;
            }
            case "filter": {
                if (args.length < 1 || !(args[0] instanceof Function)) return ofList(new Elements());
                Function fn = (Function) args[0];
                List<Element> all = elems();
                Elements keep = new Elements();
                for (int i = 0; i < all.size(); i++) {
                    Object r = fn.call(cx, scope, this,
                            new Object[]{of(all.get(i)), Integer.valueOf(i), this});
                    if (Context.toBoolean(r)) keep.add(all.get(i));
                }
                return ofList(keep);
            }
            case "forEach": {
                if (args.length < 1 || !(args[0] instanceof Function)) return Undefined.instance;
                Function fn = (Function) args[0];
                List<Element> all = elems();
                for (int i = 0; i < all.size(); i++) {
                    fn.call(cx, scope, this, new Object[]{of(all.get(i)), Integer.valueOf(i), this});
                }
                return Undefined.instance;
            }
            default:
                return Undefined.instance;
        }
    }
}
