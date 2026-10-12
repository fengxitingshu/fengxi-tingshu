/**
 * JS 桥协议适配层。
 * 把原生回调统一收敛，UI 各页面只订阅这些钩子。
 */
(function () {
    'use strict';

    var H = function (id) { return document.getElementById(id); };
    var esc = function (s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
        });
    };
    var fmt = function (sec) {
        sec = Math.max(0, Math.floor(sec || 0));
        var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
        var r = '';
        if (h > 0) { r += h + ':'; if (m < 10) r += '0'; }
        r += m + ':';
        if (s < 10) r += '0';
        return r + s;
    };

    window.TS = {
        esc: esc,
        fmt: fmt,
        $: H,
        toast: function (m) { if (window.onToast) window.onToast(m); },
        /** 调用原生，做了存在性检查（网页里预览时不会报错） */
        call: function (fn) {
            if (!window.Android || typeof Android[fn] !== 'function') return false;
            try {
                Android[fn].apply(Android, Array.prototype.slice.call(arguments, 1));
                return true;
            } catch (e) { return false; }
        },
        /** 深拷贝（避免引用共享） */
        clone: function (o) { try { return JSON.parse(JSON.stringify(o)); } catch (e) { return o; } }
    };

    /**
     * ★ v2.2 封面懒入：解码完成后才淡入，未完成时保持占位底色。
     *
     * <p>两种触发方式都覆盖：
     * 1) 已在缓存里的图，插入 DOM 时 complete 就为 true → 立刻标记；
     * 2) 还在下载的图，等 load 事件。
     *
     * <p>用事件委托挂在 document 上（capture 阶段监听 load/error），
     * 不需要给每张图单独绑处理函数，长列表（几百张）也没有额外开销。
     */
    function markLoaded(el) {
        if (el && el.tagName === 'IMG' && !el.classList.contains('loaded')) {
            el.classList.add('loaded');
        }
    }
    // 捕获阶段监听：img 的 load/error 不冒泡，必须用 capture
    document.addEventListener('load', function (e) { markLoaded(e.target); }, true);
    document.addEventListener('error', function (e) { markLoaded(e.target); }, true);

    /** 把当前已在缓存、不会触发 load 事件的图补标一次 */
    function sweepImgs(root) {
        var imgs = (root || document).getElementsByTagName('img');
        for (var i = 0; i < imgs.length; i++) {
            if (imgs[i].complete) markLoaded(imgs[i]);
        }
    }

    /**
     * ★ 用 MutationObserver 自动兜住**所有** innerHTML 渲染路径。
     *
     * <p>为什么不手工在每个渲染函数后面调一次：全项目有 30+ 处
     * `c.innerHTML = h`，漏掉任何一处就会出现"某页封面一直是透明的"。
     * 一个全局观察者一劳永逸，新增页面也自动生效。
     *
     * <p>性能：只在新增节点是/含 img 时动手，且 complete 的图才有开销，
     * 不会因为长列表渲染而卡顿。
     */
    function bootImgObserver() {
        if (!window.MutationObserver) { sweepImgs(); return; }
        var mo = new MutationObserver(function (muts) {
            for (var i = 0; i < muts.length; i++) {
                var added = muts[i].addedNodes;
                if (!added || !added.length) continue;
                for (var j = 0; j < added.length; j++) {
                    var n = added[j];
                    if (n.nodeType !== 1) continue;
                    if (n.tagName === 'IMG') markLoaded(n);
                    else if (n.getElementsByTagName) sweepImgs(n);
                }
            }
        });
        mo.observe(document.body, { childList: true, subtree: true });
    }
    if (document.body) bootImgObserver();
    else document.addEventListener('DOMContentLoaded', bootImgObserver);

    window.TS.markImages = sweepImgs;

    // ================================================================
    // ★ v2.4 超长书名跑马灯
    // ----------------------------------------------------------------
    // 设计要点：
    //  1) 只在「文字实际宽度 > 容器宽度」时才加 .mq —— 短书名保持静态，
    //     绝不会出现「两个字也在那晃」的廉价感。
    //  2) 用 scrollWidth vs clientWidth 判定，而不是字数 —— 因为中英文、
    //     字号、字体、屏幕宽度都会影响实际占用宽度，数不了字数。
    //  3) 滚动速度按「每 100px 用 3.4 秒」换算，长书名不会滚得飞快。
    //     动画时长写在 style 上，由 CSS 的 @keyframes mqScroll 驱动。
    //  4) 内容重复两遍 + translate(-50%) 实现无缝循环；
    //     重复段加 .mq-dup 便于「减弱动态效果」时隐藏。
    // ================================================================

    var MQ_PX_PER_SEC = 30;   // 每秒滚过的像素（越小越慢）
    var MQ_MIN_SEC = 6;       // 动画最短时长，防止短句闪得很快
    var MQ_INIT_GAP = 42;     // 两段之间的间隔（与 CSS .mq-gap 保持一致）

    /**
     * 给单个元素套/撤跑马灯。可重复调用（第二次会先还原再判断）。
     * @param {HTMLElement} el  承载书名的元素（其直接文本会被搬进 .mq-in）
     * @param {string=} text    新的原始文本；不传则沿用上次缓存的原文。
     *                          传空字符串表示"清空"（例如切到无书状态）。
     */
    function marquee(el, text) {
        if (!el || el.nodeType !== 1) return;
        // 幂等：先还原到原始状态。text 有传就以它为准（数据源变了），
        // 否则用上一次缓存的原文（只是重排/尺寸变化，文本没变）。
        if (text !== undefined && text !== null) el.__mqRaw = String(text);
        if (el.__mqRaw === undefined) el.__mqRaw = el.textContent;
        var raw = el.__mqRaw || '';

        // 先按"单行 nowrap"测量自然宽度
        el.classList.remove('mq');
        el.innerHTML = '';
        el.textContent = raw;
        el.style.removeProperty('animation-duration');
        var oldInner = el.querySelector('.mq-in');
        if (oldInner) oldInner.style.removeProperty('animation-duration');

        if (!raw.trim()) return;

        // clientWidth 可能在元素隐藏时为 0（切页动画中）→ 跳过，等下次渲染再算
        var avail = el.clientWidth;
        if (!avail) return;

        // 测量：把文字放进 nowrap 的行内盒量真实宽度
        var probe = document.createElement('span');
        probe.style.cssText = 'position:absolute;visibility:hidden;white-space:nowrap;' +
            'left:-9999px;top:0;pointer-events:none;';
        probe.textContent = raw;
        el.appendChild(probe);
        var w = probe.offsetWidth || probe.scrollWidth;
        el.removeChild(probe);

        // 没超出 → 保持普通省略号显示（短书名完全静止）
        if (w <= avail + 1) return;

        // 超出 → 组装「原文+间隔 | 原文+间隔」两个等宽单元，
        //   位移 -50% 正好跨过一个单元，视觉上完全无缝。
        //   ★ 结构必须是两个完全相同的 span.mq-seg（内含 .mq-txt + .mq-gap），
        //     不能靠 padding 造间隔 —— padding 会让两段测量宽度不等，
        //     滚动到一个周期末尾时会"咯噔"跳一下。
        var safe = (window.TS && TS.esc) ? TS.esc(raw) : raw;
        var unit = '<span class="mq-txt">' + safe + '</span>' +
                   '<span class="mq-gap"></span>';
        el.innerHTML =
            '<span class="mq-in">' +
              '<span class="mq-seg">' + unit + '</span>' +
              '<span class="mq-seg mq-dup">' + unit + '</span>' +
            '</span>';
        el.classList.add('mq');
        var sec = Math.max(MQ_MIN_SEC, (w + MQ_INIT_GAP) / MQ_PX_PER_SEC);
        var inner = el.querySelector('.mq-in');
        if (inner) inner.style.animationDuration = sec.toFixed(2) + 's';
    }

    /**
     * 批量处理：把 root 子树内所有 [data-mq] 元素过一遍跑马灯判定。
     * 渲染函数只要在书名元素上写 data-mq 属性，这里就会自动接管。
     */
    function markMarquee(root) {
        var scope = root || document;
        if (scope.nodeType === 1 && scope.hasAttribute && scope.hasAttribute('data-mq')) {
            marquee(scope);
        }
        if (!scope.querySelectorAll) return;
        var list = scope.querySelectorAll('[data-mq]');
        for (var i = 0; i < list.length; i++) marquee(list[i]);
    }

    // MutationObserver 兜底：任何 innerHTML 渲染路径新增的 data-mq 都会自动生效，
    // 免去在 30+ 处渲染函数里逐个补调用（与封面懒加载同一套思路）。
    function bootMqObserver() {
        if (!window.MutationObserver) { markMarquee(); return; }
        var mo = new MutationObserver(function (muts) {
            for (var i = 0; i < muts.length; i++) {
                var added = muts[i].addedNodes;
                if (!added || !added.length) continue;
                for (var j = 0; j < added.length; j++) {
                    var n = added[j];
                    if (n.nodeType !== 1) continue;
                    if (n.hasAttribute && n.hasAttribute('data-mq')) marquee(n);
                    else if (n.querySelectorAll) {
                        var hits = n.querySelectorAll('[data-mq]');
                        for (var k = 0; k < hits.length; k++) marquee(hits[k]);
                    }
                }
            }
        });
        mo.observe(document.body, { childList: true, subtree: true });

        // 旋转 / 窗口尺寸变化后重新判定（横竖屏切换、分屏等）
        var rt = null;
        function recheck() {
            clearTimeout(rt);
            rt = setTimeout(function () { markMarquee(); }, 260);
        }
        window.addEventListener('resize', recheck);
        window.addEventListener('orientationchange', recheck);
    }
    if (document.body) bootMqObserver();
    else document.addEventListener('DOMContentLoaded', bootMqObserver);

    /** 供手动调用（例如切页后强制重算） */
    window.TS.marquee = marquee;
    window.TS.markMarquee = markMarquee;

    /**
     * 封面加载失败：标两个 class —— imgfail（变淡占位）+ loaded（取消透明，
     * 让 imgfail 的淡色生效）。写成全局函数是为了能在 HTML 的 onerror 属性里
     * 安全调用，避开内联字符串里的引号嵌套问题。
     */
    window.TS.imgFail = function (el) {
        if (!el) return;
        el.classList.add('imgfail');
        el.classList.add('loaded');
    };

    // ---------- 原生回调占位，由各页面按需覆盖 ----------
    var noop = function () {};
    window.onSearchResult = noop;
    window.onSearchPartial = noop;
    window.onDetail = noop;
    window.onPlaying = noop;
    window.onPlayError = noop;
    window.onProgress = noop;
    window.onEnded = noop;
    window.onPlayState = noop;
    // ★ v2.6：native 表示"没有可复用的播放器"，前端需改走完整取链
    window.onPlayNeedFetch = noop;
    window.onToast = noop;
    window.onSourceList = noop;
    window.onSourceChanged = noop;
    window.onSourceTested = noop;
    window.onSourceImported = noop;
    window.onSourceExported = noop;
    window.onSourceFilePicked = noop;
    window.onCustomList = noop;
    window.onCustomChanged = noop;
    window.onBrowse = noop;
    window.onCacheProgress = noop;
    window.onCacheDone = noop;
    window.onCacheList = noop;
    window.onDeviceInfo = noop;
    window.onSkipIntro = noop;          // ★ v3.8.1 跳过片头设置
    // ★ v3.8.0 备份与恢复（13 个回调，由备份页按需覆盖）
    window.onBackupStatus = noop;
    window.onBackupDir = noop;
    window.onBackupFilePicked = noop;
    window.onBackupDone = noop;
    window.onRestoreDone = noop;
    window.onRestoreLocalStorage = noop;
    window.onDavConfig = noop;
    window.onDavSaved = noop;
    window.onDavTested = noop;
    window.onDavBackupDone = noop;
    window.onDavList = noop;
    window.onDavDeleted = noop;
    window.onAutoBackupList = noop;
    window.onBackPressed = function () { return false; };

    /** 全局 Toast */
    window.onToast = function (m) {
        var t = H('toast');
        if (!t) return;
        t.textContent = m;
        t.classList.add('show');
        clearTimeout(t._h);
        t._h = setTimeout(function () { t.classList.remove('show'); }, 2000);
    };
})();
