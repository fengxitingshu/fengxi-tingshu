package com.tingshu.app.core.js;

/**
 * ★ v4.20.4 JS 语法降级器。
 *
 * <p>背景：Rhino（全版本，含 1.8.1）都不支持 async/await 与 class 声明；
 * 1.8.1 起才支持可选链（?.）、空值合并（??）、rest 参数（仅 function 形式）等。
 * 而第三方生态（如洛雪/LX 音乐源脚本）大量使用现代语法。
 *
 * <p>本类在**加载进内存时**对脚本做等价降级（源文件一个字节不动）：
 * <ol>
 *   <li>{@code async fn(params) => { … await … }} →
 *       {@code __lxAsync(function*(params) { … yield … })}（生成器 + Promise 驱动）；
 *   <li>{@code const} → {@code let}（Rhino 的 const 是函数级作用域的老语义，
 *       兄弟块同名 const 会误报 redeclaration；let 是真块级，且脚本不会依赖
 *       const 的防重写保护）；for-of 头的解构 / const 也随之解决；
 *   <li>{@code (...args) => {}} → {@code function(...args) {}}（Rhino 箭头不支持 rest 参数）；
 *   <li>{@code console.log(a, ...b)} → {@code console.log(a, [].concat(b).join(' '))}
 *       （Rhino 不支持调用展开）。
 * </ol>
 *
 * <p>实现是字符串/模板串/注释/正则感知的扫描器，不做完整解析 —— 对不认识的
 * 构造一律原样拷贝，因此对老插件（纯 ES5/基础 ES6）零影响。
 */
public final class JsCompat {

    private JsCompat() {}

    /** async→生成器的运行时 shim（转换后的脚本依赖它），每次脚本加载前预置 */
    public static final String RUNTIME_SHIM =
            "if (typeof __callG === 'undefined') {" +
            "  var __callG = function (g, args) { switch (args.length) {" +
            "    case 0: return g();" +
            "    case 1: return g(args[0]);" +
            "    case 2: return g(args[0], args[1]);" +
            "    case 3: return g(args[0], args[1], args[2]);" +
            "    case 4: return g(args[0], args[1], args[2], args[3]);" +
            "    default: return g(args[0], args[1], args[2], args[3], args[4]);" +
            "  } };" +
            "  var __lxAsync = function (g) { return function () { var args = arguments;" +
            "    return new Promise(function (res, rej) {" +
            "      var it = __callG(g, args);" +
            "      function step(v) { try {" +
            "        var r = it.next(v);" +
            "        if (r.done) { res(r.value); return; }" +
            "        Promise.resolve(r.value).then(function (x) { step(x) }, function (e) { rej(e) });" +
            "      } catch (e) { rej(e); } }" +
            "      step(undefined);" +
            "    }); }; };" +
            // ★ v4.21.4：调用参数展开辅助（参数个数分发，避免生成器 .apply 坑）
            "  var __lxs = function (fn, self, fixed) {" +
            "    var args = [];" +
            "    if (fixed) for (var i = 0; i < fixed.length; i++) args.push(fixed[i]);" +
            "    for (var i = 3; i < arguments.length; i++) {" +
            "      var s = arguments[i];" +
            "      if (s) for (var j = 0; j < s.length; j++) args.push(s[j]);" +
            "    }" +
            "    switch (args.length) {" +
            "      case 0: return fn.call(self);" +
            "      case 1: return fn.call(self, args[0]);" +
            "      case 2: return fn.call(self, args[0], args[1]);" +
            "      case 3: return fn.call(self, args[0], args[1], args[2]);" +
            "      case 4: return fn.call(self, args[0], args[1], args[2], args[3]);" +
            "      case 5: return fn.call(self, args[0], args[1], args[2], args[3], args[4]);" +
            "      default: return fn.apply(self, args);" +
            "    }" +
            "  };" +
            // ★ v4.21.4：对象展开辅助（Rhino 未必有 Object.assign）
            "  var __oassign = Object.assign || function (t) {" +
            "    for (var i = 1; i < arguments.length; i++) {" +
            "      var s = arguments[i];" +
            "      if (s) for (var k in s) if (Object.prototype.hasOwnProperty.call(s, k)) t[k] = s[k];" +
            "    }" +
            "    return t;" +
            "  };" +
            "}";

    /** 判断脚本是否需要降级 */
    public static boolean needs(String src) {
        return src.contains("async ") || src.contains("async(") || src.contains("await ")
                || src.contains("const ") || src.contains(", ...") || src.contains("...");
    }

    /** 语法降级主入口 */
    public static String transform(String src) {
        if (!needs(src)) return src;

        // ---- ★ v4.21.4 前置：展开语法降级（调用参数/对象/数组展开）----
        src = rewriteSpreads(src);

        // ---- 行级预处理：console.log 展开 + 箭头 rest 参数 ----
        StringBuilder pre = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            if (line.contains("console.log") && line.contains(", ...")) {
                line = line.replaceAll(",\\s*\\.\\.\\.(\\w+)\\s*\\)", ", [].concat($1).join(' '))");
            }
            if (line.contains("...") && line.contains("=>")) {
                line = line.replaceAll("\\(\\.\\.\\.(\\w+)\\)\\s*=>\\s*\\{", "function(...$1) {");
            }
            pre.append(line).append('\n');
        }
        src = pre.toString();
        if (!needs(src)) return src;

        StringBuilder out = new StringBuilder(src.length() + 256);
        int n = src.length();
        int depth = 0;                                    // 代码块大括号深度
        java.util.Deque<Integer> asyncBodies = new java.util.ArrayDeque<>();
        java.util.List<Integer> pendingBodies = new java.util.ArrayList<>(); // 1=等" => "吃掉 0=等 `{`
        int prevSig = -1;                                 // 上一个有效字符（正则 vs 除号）

        int i = 0;
        while (i < n) {
            char c = src.charAt(i);

            // ---- 注释 ----
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int j = src.indexOf('\n', i);
                if (j < 0) j = n;
                out.append(src, i, j);
                i = j;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int j = src.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                out.append(src, i, j);
                i = j;
                continue;
            }
            // ---- 字符串 ----
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (d == c) { j++; break; }
                    j++;
                }
                out.append(src, i, Math.min(j, n));
                i = Math.min(j, n);
                prevSig = c;
                continue;
            }
            // ---- 模板串 ----
            if (c == '`') {
                i = scanTemplate(src, i, out, asyncBodies);
                prevSig = '`';
                continue;
            }
            // ---- 正则 vs 除号 ----
            if (c == '/') {
                if (regexAllowed(prevSig)) {
                    int j = scanRegex(src, i);
                    out.append(src, i, j);
                    i = j;
                    prevSig = '/';
                    continue;
                }
                out.append(c);
                i++;
                prevSig = '/';
                continue;
            }
            // ---- 标识符 / 关键字 ----
            if (Character.isJavaIdentifierStart(c) && !Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && (Character.isJavaIdentifierPart(src.charAt(j)) || src.charAt(j) == '$')) j++;
                String w = src.substring(i, j);

                // await → yield（仅在 async 体内）
                if (w.equals("await") && !asyncBodies.isEmpty()) {
                    out.append("yield");
                    i = j;
                    prevSig = 'w';
                    continue;
                }
                // async → 生成器包装
                if (w.equals("async")) {
                    int k = skipWs(src, j);
                    if (k < n && (src.charAt(k) == '(' || lookWord(src, k).equals("function"))) {
                        out.append("__lxAsync(function*");
                        if (src.charAt(k) != '(') {
                            i = k + "function".length();
                            pendingBodies.add(0);   // async function：无箭头，等 `{`
                        } else {
                            i = j;                  // "(" 与参数、"=>" 交给主扫描
                            pendingBodies.add(1);   // 先吃掉 `=>` 再等 `{`
                        }
                        prevSig = '*';
                        continue;
                    }
                    out.append(w);
                    i = j;
                    prevSig = 'w';
                    continue;
                }
                // const → let（Rhino const 是函数级作用域，兄弟块同名会误撞）
                if (w.equals("const")) {
                    out.append("let");
                    i = j;
                    prevSig = 'w';
                    continue;
                }
                out.append(src, i, j);
                i = j;
                prevSig = 'w';
                continue;
            }
            // ---- async 箭头的 `=>` 吃掉 ----
            if (c == '=' && i + 1 < n && src.charAt(i + 1) == '>'
                    && !pendingBodies.isEmpty()
                    && pendingBodies.get(pendingBodies.size() - 1) == 1) {
                pendingBodies.set(pendingBodies.size() - 1, 0);
                i += 2;
                prevSig = '*';
                continue;
            }
            // ---- 花括号 ----
            if (c == '{') {
                depth++;
                if (!pendingBodies.isEmpty()) {
                    pendingBodies.remove(pendingBodies.size() - 1);
                    asyncBodies.push(depth);
                }
                out.append(c);
                i++;
                prevSig = c;
                continue;
            }
            if (c == '}') {
                if (!asyncBodies.isEmpty() && asyncBodies.peek() == depth) {
                    asyncBodies.pop();
                    out.append("})");       // 收口生成器包装
                } else {
                    out.append(c);
                }
                depth--;
                i++;
                prevSig = c;
                continue;
            }
            // ---- 其它 ----
            out.append(c);
            if (!Character.isWhitespace(c)) prevSig = c;
            i++;
        }
        return out.toString();
    }

    /** 模板串扫描：原样拷贝；${} 内嵌代码按代码处理（await→yield、const→let 也生效） */
    // ==================== ★ v4.21.4：展开语法降级 ====================
    // Rhino 1.8.1 不支持调用参数展开 f(...xs)，也不支持对象/数组展开。
    //   f(a, ...xs)     → __lxs(f, null, [a], xs)
    //   obj.m(a, ...xs) → __lxs(obj.m, obj, [a], xs)
    //   { ...x, k: v }  → __oassign({}, x, { k: v })
    //   [a, ...b]       → [].concat([a], b)
    // 在「开括号」处触发改写（参数表内可含嵌套展开，逐段递归）；
    // 函数 rest 参数（function(...a)）Rhino 原生支持，原样保留。

    private static String rewriteSpreads(String src) {
        if (!src.contains("...")) return src;
        boolean[] mask = codeMask(src);
        StringBuilder out = new StringBuilder(src.length() + 128);
        rewriteRange(src, mask, 0, src.length(), out);
        return out.toString();
    }

    /** 处理 [start,end) 代码段：开括号处判断并改写，其余原样拷贝。
     *  标识符链先进 pend 暂存 —— 命中「可改写的展开调用」时直接丢弃
     *  （替换文本里已含 callee），未命中则冲刷后原样输出。 */
    private static void rewriteRange(String src, boolean[] mask, int start, int end, StringBuilder out) {
        int i = start;
        StringBuilder pend = new StringBuilder();
        while (i < end) {
            char c = src.charAt(i);
            // ---- "..."：rest 参数等，原样保留（真正的展开在开括号处已被整体改写）----
            if (!mask[i] && c == '.' && c3(src, i)) {
                if (pend.length() > 0) { out.append(pend); pend.setLength(0); }
                out.append("...");
                i += 3;
                continue;
            }
            // ---- 标识符链字符：暂存 ----
            if (!mask[i] && (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.')) {
                pend.append(c);
                i++;
                continue;
            }
            // ---- '('：可能是可改写的展开调用 ----
            if (!mask[i] && c == '(' && pend.length() > 0) {
                int q = matchCloser(src, mask, i, '(', ')');
                if (q > 0 && q < end && hasTopSpread(src, mask, i + 1, q)
                        && callableCall(src, mask, i, q)) {
                    String r = rewriteCallSpread(src, mask, i, q);
                    if (r != null) {
                        pend.setLength(0);          // callee 已含在替换文本里
                        out.append(r);
                        i = q + 1;
                        continue;
                    }
                }
            }
            // ---- 其它：先冲刷暂存，再处理对象/数组展开与普通字符 ----
            if (pend.length() > 0) { out.append(pend); pend.setLength(0); }
            if (!mask[i] && (c == '{' || c == '[')) {
                char ck = c == '{' ? '}' : ']';
                int q = matchCloser(src, mask, i, c, ck);
                if (q > 0 && q < end && hasTopSpread(src, mask, i + 1, q)) {
                    String r = c == '{' ? rewriteObjectSpread(src, mask, i, q)
                            : rewriteArraySpread(src, mask, i, q);
                    if (r != null) { out.append(r); i = q + 1; continue; }
                }
            }
            out.append(c);
            i++;
        }
        out.append(pend);
    }

    private static boolean c3(String s, int i) {
        return i + 2 < s.length() && s.charAt(i + 1) == '.' && s.charAt(i + 2) == '.';
    }

    /** [from,to) 内是否有顶层（未进更深括号层）的 "..." */
    private static boolean hasTopSpread(String src, boolean[] mask, int from, int to) {
        int depth = 0;
        for (int i = from; i < to && i < src.length(); i++) {
            if (mask[i]) continue;
            char c = src.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == '.' && depth == 0 && c3(src, i)) return true;
        }
        return false;
    }

    /** 标记字符串/模板串/注释/正则覆盖区（true = 非代码） */
    private static boolean[] codeMask(String src) {
        int n = src.length();
        boolean[] mask = new boolean[n];
        int i = 0;
        int prevSig = -1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int j = src.indexOf('\n', i); if (j < 0) j = n;
                fill(mask, i, j); i = j; continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int j = src.indexOf("*/", i + 2); j = j < 0 ? n : j + 2;
                fill(mask, i, j); i = j; continue;
            }
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n) { char d = src.charAt(j); if (d == '\\') { j += 2; continue; } if (d == c) { j++; break; } j++; }
                int e = Math.min(j, n); fill(mask, i, e); i = e; prevSig = c; continue;
            }
            if (c == '`') {
                int j = i + 1;
                while (j < n) { char d = src.charAt(j); if (d == '\\') { j += 2; continue; } if (d == '`') { j++; break; } j++; }
                int e = Math.min(j, n); fill(mask, i, e); i = e; prevSig = '`'; continue;
            }
            if (c == '/' && regexAllowed(prevSig)) {
                int j = scanRegex(src, i);
                fill(mask, i, Math.min(j, n)); i = Math.min(j, n); prevSig = '/'; continue;
            }
            if (!Character.isWhitespace(c)) prevSig = c;
            i++;
        }
        return mask;
    }

    private static void fill(boolean[] mask, int from, int to) {
        for (int k = from; k < to && k < mask.length; k++) mask[k] = true;
    }

    /** 从 open 起配对闭合符 */
    private static int matchCloser(String src, boolean[] mask, int open, char ok, char ck) {
        int depth = 0;
        int i = open;
        while (i < src.length()) {
            if (!mask[i]) {
                char c = src.charAt(i);
                if (c == ok) depth++;
                else if (c == ck) { depth--; if (depth == 0) return i; }
            }
            i++;
        }
        return -1;
    }

    /** 顶层逗号切段（括号/字符串感知） */
    private static java.util.List<String> splitTop(String src, boolean[] mask, int from, int to) {
        java.util.List<String> segs = new java.util.ArrayList<String>();
        int depth = 0, start = from;
        for (int i = from; i < to; i++) {
            if (mask[i]) continue;
            char c = src.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                segs.add(src.substring(start, i));
                start = i + 1;
            }
        }
        segs.add(src.substring(start, to));
        return segs;
    }

    /**
     * '(' 是否为函数调用（而非参数表/控制语句/方法定义）。
     * 规则：'(' 前是标识符链；排除 if/while/for/switch/catch/new/function 等；
     * ')' 后是 '{'（函数/方法体签名）或 '=>'（箭头参数）则不算调用。
     */
    private static boolean callableCall(String src, boolean[] mask, int openIdx, int closeIdx) {
        String callee = calleeOf(src, mask, openIdx);
        if (callee == null) return false;
        String head = callee;
        int dot = callee.lastIndexOf('.');
        if (dot >= 0) head = callee.substring(dot + 1);
        if (head.equals("if") || head.equals("while") || head.equals("for")
                || head.equals("switch") || head.equals("catch") || head.equals("new")
                || head.equals("function") || head.equals("typeof") || head.equals("return"))
            return false;
        int k = closeIdx + 1;
        while (k < src.length() && (Character.isWhitespace(src.charAt(k)) || mask[k])) k++;
        if (k < src.length()) {
            char d = src.charAt(k);
            if (d == '{') return false;                       // 函数/方法签名
            if (d == '=' && k + 1 < src.length() && src.charAt(k + 1) == '>') return false;
        }
        return true;
    }

    /** '(' 前的标识符链（a.b.c / fn / obj.$m），没有则 null */
    private static String calleeOf(String src, boolean[] mask, int openIdx) {
        int j = openIdx - 1;
        while (j >= 0 && (Character.isWhitespace(src.charAt(j)) || mask[j])) j--;
        int end = j + 1;
        while (j >= 0) {
            if (mask[j]) break;
            char c = src.charAt(j);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') { j--; continue; }
            break;
        }
        if (end == 0) return null;
        String callee = src.substring(j + 1, end);
        return callee.length() == 0 ? null : callee;
    }

    /** 段内递归降级（嵌套展开调用等） */
    private static String subRewrite(String text) {
        if (text.indexOf("...") < 0 && text.indexOf('(') < 0
                && text.indexOf('[') < 0 && text.indexOf('{') < 0) return text;
        boolean[] m2 = codeMask(text);
        StringBuilder sb2 = new StringBuilder(text.length() + 32);
        rewriteRange(text, m2, 0, text.length(), sb2);
        return sb2.toString();
    }

    /** f(a, ...xs) → __lxs(f, self, [a], xs)，返回整个调用的替换文本 */
    private static String rewriteCallSpread(String src, boolean[] mask, int openIdx, int closeIdx) {
        String callee = calleeOf(src, mask, openIdx);
        if (callee == null) return null;
        String self = "null";
        int dot = callee.lastIndexOf('.');
        if (dot > 0) self = "(" + callee.substring(0, dot) + ")";

        java.util.List<String> segs = splitTop(src, mask, openIdx + 1, closeIdx);
        StringBuilder fixed = new StringBuilder();
        StringBuilder spreads = new StringBuilder();
        boolean hasFixed = false;
        boolean hasSpread = false;
        for (String s : segs) {
            String t = s.trim();
            if (t.startsWith("...")) {
                hasSpread = true;
                spreads.append(", ").append(subRewrite(t.substring(3).trim()));
            } else if (t.length() > 0) {
                if (hasFixed) fixed.append(", ");
                fixed.append(subRewrite(t));
                hasFixed = true;
            }
        }
        if (!hasSpread) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("__lxs(").append(callee).append(", ").append(self).append(", ");
        sb.append(hasFixed ? "[" + fixed + "]" : "null");
        sb.append(spreads).append(')');
        return sb.toString();
    }

    /** { ...x, k: v } → __oassign({}, x, { k: v }) */
    private static String rewriteObjectSpread(String src, boolean[] mask, int openIdx, int closeIdx) {
        java.util.List<String> segs = splitTop(src, mask, openIdx + 1, closeIdx);
        java.util.List<String> args = new java.util.ArrayList<String>();
        boolean hasSpread = false;
        StringBuilder nonSpread = new StringBuilder();
        for (String s : segs) {
            String t = s.trim();
            if (t.startsWith("...")) {
                hasSpread = true;
                if (nonSpread.length() > 0) { args.add("{" + nonSpread + "}"); nonSpread.setLength(0); }
                args.add(subRewrite(t.substring(3).trim()));
            } else if (t.length() > 0) {
                if (nonSpread.length() > 0) nonSpread.append(", ");
                nonSpread.append(subRewrite(t));
            }
        }
        if (!hasSpread) return null;
        if (nonSpread.length() > 0) args.add("{" + nonSpread + "}");
        StringBuilder sb = new StringBuilder("__oassign({}, ");
        for (int k = 0; k < args.size(); k++) {
            if (k > 0) sb.append(", ");
            sb.append(args.get(k));
        }
        sb.append(')');
        return sb.toString();
    }

    /** [a, ...b] → [].concat([a], b) */
    private static String rewriteArraySpread(String src, boolean[] mask, int openIdx, int closeIdx) {
        java.util.List<String> segs = splitTop(src, mask, openIdx + 1, closeIdx);
        StringBuilder sb = new StringBuilder("[].concat(");
        boolean first = true;
        boolean hasSpread = false;
        for (String s : segs) {
            String t = s.trim();
            if (t.length() == 0) continue;
            String arg;
            if (t.startsWith("...")) { hasSpread = true; arg = subRewrite(t.substring(3).trim()); }
            else arg = "[" + subRewrite(t) + "]";
            if (arg.length() == 0) continue;
            if (!first) sb.append(", ");
            sb.append(arg);
            first = false;
        }
        if (!hasSpread) return null;
        sb.append(')');
        return sb.toString();
    }

    private static int scanTemplate(String src, int start, StringBuilder out,
                                    java.util.Deque<Integer> asyncBodies) {
        int n = src.length();
        out.append('`');
        int i = start + 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') { out.append(src, i, Math.min(i + 2, n)); i += 2; continue; }
            if (c == '`') { out.append('`'); return i + 1; }
            if (c == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
                out.append("${");
                i += 2;
                int exprDepth = 1;      // ${ } 层
                int localBrace = 0;     // 表达式内的对象字面量大括号
                while (i < n && exprDepth > 0) {
                    char d = src.charAt(i);
                    if (d == '\\') { out.append(src, i, Math.min(i + 2, n)); i += 2; continue; }
                    if (d == '\'' || d == '"') {
                        int j = i + 1;
                        while (j < n) {
                            char e = src.charAt(j);
                            if (e == '\\') { j += 2; continue; }
                            if (e == d) { j++; break; }
                            j++;
                        }
                        out.append(src, i, Math.min(j, n));
                        i = Math.min(j, n);
                        continue;
                    }
                    if (d == '`') { i = scanTemplate(src, i, out, asyncBodies); continue; }
                    if (d == '{') localBrace++;
                    if (d == '}') {
                        if (localBrace == 0) { exprDepth--; out.append('}'); i++; continue; }
                        localBrace--;
                    }
                    if (Character.isJavaIdentifierStart(d) && !Character.isDigit(d)) {
                        int j = i + 1;
                        while (j < n && (Character.isJavaIdentifierPart(src.charAt(j)) || src.charAt(j) == '$')) j++;
                        String w = src.substring(i, j);
                        // 模板表达式内出现 await 必属于最近的 async 函数（普通函数体内
                        // 有 await 本来就是语法错误），保守替换
                        if (w.equals("await") && !asyncBodies.isEmpty()) out.append("yield");
                        else if (w.equals("const")) out.append("let");
                        else out.append(w);
                        i = j;
                        continue;
                    }
                    out.append(d);
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return i;
    }

    /** 正则字面量扫描：返回结束位置 */
    private static int scanRegex(String src, int start) {
        int n = src.length();
        int i = start + 1;
        boolean inClass = false;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            else if (c == '/' && !inClass) {
                i++;
                while (i < n && Character.isLetter(src.charAt(i))) i++;   // flags
                return i;
            }
            i++;
        }
        return n;
    }

    /** 前一个有效字符能否构成表达式结尾（能 → `/` 是除号） */
    private static boolean regexAllowed(int prevSig) {
        if (prevSig < 0) return true;
        char c = (char) prevSig;
        if (c == ')' || c == ']' || c == '}' || c == '`' || c == '"' || c == '\'') return false;
        if (Character.isLetterOrDigit(c) || c == '_' || c == '$') return false;
        if (c == '.') return false;
        return true;
    }

    private static int skipWs(String s, int i) {
        int n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String lookWord(String s, int i) {
        int n = s.length(), j = i;
        while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) j++;
        return s.substring(i, j);
    }
}
