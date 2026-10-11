/**
 * 听书 App 前端（完整版）
 * 页面：首页 / 搜索 / 收藏 / 历史 / 我的(源管理+设置) / 详情 / 全屏播放
 * 纯 ES5，兼容 Android 5.0+ WebView
 */
(function () {
    'use strict';

    var TS = window.TS;
    var $ = TS.$;

    var App = {
        page: 'pageHome',
        stack: [],              // 页面栈（返回键）
        tab: 'home',            // 当前底部 tab

        books: [],              // 搜索结果
        chapters: [],
        book: null,
        chapIdx: -1,
        detailIntro: '',
        // ★ v3.7.9：章节列表视图偏好 —— 分列数（1/2/3）与当前区间段号
        chapView: { cols: 1, seg: 0 },

        playing: false,
        cur: 0,
        dur: 0,
        speed: 1.0,
        // ★ v2.8：倍速改为「滑杆 + 0.1 步进」。
        //   滑杆内部用整数档位（10 = 1.0x），彻底规避浮点累加误差。
        canSpeed: true,
        // ★ v3.1：音量增益百分比（100 = 原始音量，50~300）
        gain: 100,
        // ★ v3.1：其他声音打断策略（0 不处理 / 1 降低音量 / 2 暂停播放）
        audioFocusMode: 0,
        // ★ v3.1：批量验证的状态
        lastVerifySummary: '',  // 上次验证的摘要（启动自动验证会写它）
        verifyProg: null,       // { done, total } —— 进度面板用
        verifyScope: 'all',     // 面板里选中的范围
        verifyDedup: true,      // 面板里「删除重复源」勾选态
        verifyAutoClose: true,  // 面板里「自动停用失效」勾选态

        groups: [],
        curGroup: -1,           // -1 = 全部
        favKeys: {},            // key -> true
        sources: [],

        // ★ v2.8：集号(1基) -> 下载状态（0 未下载 / 1 下载中 / 2 完成 / 3 失败）
        //   ★ v2.9：语义从「缓存」改为「下载」（查的是 download 表）
        cacheStatus: {},

        // ★ v2.9：多选下载。null = 普通模式；否则 { sel: {集号: true} }
        multi: null,
        // ★ v2.9：当前下载目录（设置页显示用）
        dlDirName: '',
        dlDirUri: '',

        // 定时关闭
        sleepMin: 0,
        sleepAt: 0,
        sleepTimer: null,

        fullShow: false,
        _homeContinue: null,
        _histCache: null,
        sessionResume: false,
        playOnFull: true,

        // ★ v2.6 冷启动续播相关状态
        //   needFetch  : native 侧没有可复用的播放器，必须重新取链（不是 resume）
        //   pendingAutoPlay : 自动播放已排队，等章节表（onDetail）回来再兑现
        //   pendingPlayIdx  : 排队时想播的集号（章节表未就绪时用它记住意图）
        needFetch: false,
        pendingAutoPlay: false,
        pendingPlayIdx: -1,
        _autoPlayTimer: null,
        /** 用户是否已主动操作过（点过悬浮球/播过歌）。
         *  用于阻止迟到的 resumeSession 回调覆盖用户当前状态。 */
        userActed: false
    };
    window.App = App;
    var ui = App.ui = {};

    // ==================== 页面路由 ====================

    var TABS = {
        home: 'pageHome', search: 'pageSearch',
        fav: 'pageFav', history: 'pageHistory', mine: 'pageMine'
    };
    var SUBS = { detail: 'pageDetail', full: 'pageFull', group: 'pageGroup',
        discover: 'pageDiscover', cache: 'pageCache', sources: 'pageSources',
        srcGroup: 'pageSrcGroup', reward: 'pageReward',
        settings: 'pageSettings', about: 'pageAbout',
        changelog: 'pageChangelog',   // ★ v3.7.3 更新公告
        dlmanage: 'pageDlManage' };   // ★ v2.9 下载管理

    function allPages() {
        var a = [], k;
        for (k in TABS) a.push(TABS[k]);
        for (k in SUBS) a.push(SUBS[k]);
        return a;
    }

    /** 正在播放入场动画的页面，避免快速连点堆叠多个动画 */
    var _animEl = null;

    /**
     * 切页。dir: 'in' 前进 / 'back' 返回。
     * 用一段淡出(90ms) + 淡入(260ms)的两段式过渡，比直接 display 切换柔和得多，
     * 又比双页交叉淡入简单可靠（WebView 里交叉淡入容易掉帧）。
     */
    var _switching = false;
    function showOne(id, dir) {
        var from = App.page ? $(App.page) : null;
        var to = $(id);
        if (!to) { App.page = id; return; }
        if (from === to) { App.page = id; return; }

        var ps = allPages();
        for (var i = 0; i < ps.length; i++) {
            var el = $(ps[i]);
            if (!el || el === to || el === from) continue;
            el.classList.add('hide');
            el.classList.remove('entering', 'entering-back', 'leaving');
        }

        function enter() {
            to.classList.remove('hide');
            to.classList.remove('entering', 'entering-back');
            var back = (dir === 'back');
            to.classList.add(back ? 'entering-back' : 'entering');
            _animEl = to;
            setTimeout(function () {
                if (_animEl === to) {
                    to.classList.remove('entering', 'entering-back');
                    _animEl = null;
                }
            }, 320);
        }

        // ★ v2.1：新旧两页**同时**过渡 —— 新页立即淡入，旧页延迟一点点淡出，
        // 两者在时间轴上交叠。原来要等旧页淡完（90ms）才 enter()，中间有空白帧，
        // 看起来就是"界面抖一下"。现在没有等待，过渡连续。
        if (from && !from.classList.contains('hide') && !_switching) {
            _switching = true;
            from.classList.add('leaving');
            enter();                          // 立刻开始淡入，不等 from
            setTimeout(function () {
                from.classList.add('hide');
                from.classList.remove('leaving');
                _switching = false;
            }, 240);
        } else {
            if (from) from.classList.add('hide');
            enter();
        }
        App.page = id;
    }

    function goTab(tab) {
        App.tab = tab;
        App.stack = [];
        App.fullShow = false;
        document.body.classList.remove('full-show');
        if (ui.closeSrcPop) ui.closeSrcPop();
        showOne(TABS[tab], 'in');
        updateTabbar();
        onTabEnter(tab);
    }
    ui.goTab = goTab;

    function goSub(key) {
        App.stack.push(App.page);
        showOne(SUBS[key], 'in');
        updateTabbar();
    }
    ui.goSub = goSub;

    function backToSub(prev) {
        showOne(prev, 'back');
        updateTabbar();
    }

    ui.back = function () {
        // ★ v3.2：离开当前页面前先收起播放页的「更多」下拉，
        //   否则它会悬空留在新页面上
        ui.closeMore();
        // ★ v3.7.4：源分组页处于多选态时，返回键先退出多选（与章节多选一致）
        if (App.multiSrc) { exitSrcMulti(false); return true; }
        if (App.fullShow) { ui.closeFull(); return true; }
        if (App.stack.length > 0) {
            backToSub(App.stack.pop());
            return true;
        }
        if (App.tab !== 'home') { goTab('home'); return true; }
        return false;
    };

    function updateTabbar() {
        var bar = $('tabbar');
        if (!bar) return;
        // 二级页（详情/全屏/分组/书源/搜索/赞赏/设置/关于）隐藏底栏
        var isSub = (App.page === SUBS.detail || App.page === SUBS.full
            || App.page === SUBS.group || App.page === SUBS.sources
            || App.page === SUBS.reward || App.page === SUBS.settings
            || App.page === SUBS.about || App.page === SUBS.changelog
            || App.page === TABS.search);
        bar.classList[isSub ? 'add' : 'remove']('hide');
        var items = bar.querySelectorAll('.tabitem');
        for (var i = 0; i < items.length; i++) {
            var t = items[i].getAttribute('data-tab');
            items[i].classList[t === App.tab ? 'add' : 'remove']('on');
        }
    }

    function onTabEnter(tab) {
        if (tab === 'home') loadHome();
        else if (tab === 'search') {
            var box = $('kw');
            if (box && (box.value || '').trim() && (App.books || []).length) {
                // 上次搜的词还在、结果也在 → 原样保留，只刷新历史
                loadSearchHist();
            } else {
                ui.showSearchHist();
            }
        }
        else if (tab === 'fav') { loadGroups(); loadFavs(); }
        else if (tab === 'history') loadHistory();
        else if (tab === 'mine') { loadSources(); syncSettings(); }
    }

    // ==================== 首页（发现好书） ====================

    // 首页 = 分类浏览。源与分类清单复用「发现」那套（原生 discoverSources 动态给）。
    App.home = { src: '', cat: '', page: 1 };
    App.homeSources = null;         // null = 还没拿到
    App._homeMeta = { favCount: 0, historyCount: 0 };
    // 横滑分组：等待中的分类 + 每个分类已加载到第几页
    App.homePending = {};
    App.homePage = {};
    // ★ v3.7.5：首页"加载更多"状态
    App.homeHasMore = false;        // 当前分类是否还有下一页
    App.homeMoreBusy = false;       // 是否正在拉下一页（防重复触发）
    App.homeMoreSince = 0;          // ★ v3.7.9：本次加载开始时间戳（用于超时兜底复位）
    App.homeMoreWait = null;        // 正在等哪一页（{src,cat,page}）
    App.homePageSize = 0;           // ★ v3.7.6：满页基准（首屏这一页的条数）
    // ★ v3.7.11：整页重复时自动往后连翻的计数（最多 5 次，见 renderHomeList）
    App._homeEmptyStreak = 0;
    // ★ v3.7.9：Python 运行时状态（冷启动时它会在后台慢慢起）
    App.pyState = 'idle';           // idle | loading | ready | failed
    App.pyBootToast = false;        // 就绪后是否要提示"已就绪 N 个 Python 书源"
    App._pyPollTimer = null;        // ★ v3.7.9：Python 启动兜底轮询定时器

    function loadHome() {
        // 继续收听 / 收藏 / 历史统计仍走原生，用于底部「继续收听」和空态提示
        TS.call('homeInfo');
        if (!App.homeSources) TS.call('discoverSources');
        else paintHomeSources();
    }

    /** 首页顶栏左侧的源下拉显示名 */
    function paintHomeSourceName() {
        var el = $('homeSrcName');
        if (!el) return;
        var list = App.homeSources || [];
        if (!list.length) { el.textContent = '发现好书'; return; }
        var cfg = null;
        for (var i = 0; i < list.length; i++) if (list[i].id === App.home.src) cfg = list[i];
        el.textContent = cfg ? (cfg.name || '发现好书') : '发现好书';
    }

    /** ★ v2.4：从首页空态一键跳到「书源管理」并自动展开导入面板 */
    ui.goImportSource = function () {
        ui.goTab('mine');
        // 等 mine 页渲染完再进二级页；书源列表可能还没拉回来，
        // 导入面板里会自己提示"导入后自动刷新"，不依赖列表先到。
        setTimeout(function () {
            try { ui.openImport(); } catch (e) { ui.openSources(); }
        }, 80);
    };

    window.onDiscoverSources = function (arr) {
        App.discoverSources = arr || [];
        App.homeSources = App.discoverSources;
        // ★ v3.7.6：源列表到手的第一时间恢复"上次用的源"，
        //   然后才渲染 —— 这样冷启动第一屏直接是上次那个源的内容，不再是空白。
        if (!App.home.src) restoreLastHomeSource(App.homeSources);
        if (App.page === SUBS.discover) renderDiscover();
        if (App.page === TABS.home) { paintHomeSources(); }
    };

    /**
     * 首页 = 顶部分类横滑条 + 当前分类的书籍列表。
     * 分类本身是一行可左右滑动的 chips（不是竖着平铺），点/划即切换；
     * 内容区只显示当前选中分类的书，走 browse 接口（带缓存）实时抓取。
     */
    function paintHomeSources(force) {
        var cd = $('discoverCats');
        var cl = $('homeContent');
        if (!cd || !cl) return;

        // ★ v3.7.6：这里原来**无条件**复位「加载更多」状态。
        //   但 paintHomeSources 会在很多"非换源"场景被调到（discoverSources 回调、
        //   从别的页面回首页、详情返回等），每次都会把 homeHasMore 清成 false、
        //   并把累积的 App.homeBooks 状态留在那儿 → 表现就是"点了加载更多，
        //   加载到 20 本之后再点就没反应了"。
        //   现在只在**真正换源/换分类/强制刷新**时复位（见下面 resetHomePaging 调用点）。

        var list = App.homeSources || [];
        if (!list.length) {
            // ★ v2.4：本 App 已是纯空壳工具 —— 不再内置任何源。
            //   首次安装后这里就是用户看到的第一屏，所以提示要能直接
            //   把人送到「书源管理」的导入入口，而不是只说一句"没有源"。
            cd.innerHTML = '';
            cl.innerHTML =
                '<div class="empty nosrc">' +
                  '<div class="big">📚</div>' +
                  '<div class="nosrc-t">还没有可用的书源</div>' +
                  '<div class="nosrc-s">本应用不含内置源，需要你自己导入书源后才可使用</div>' +
                  '<div class="nosrc-btns">' +
                    '<button class="mini" onclick="App.ui.goImportSource()">导入书源</button>' +
                    '<button class="mini ghost" onclick="App.ui.goTab(\'mine\')">书源管理</button>' +
                  '</div>' +
                  '<div class="nosrc-tip">支持粘贴 JSON 配置、导入 jar 插件、或填写订阅链接</div>' +
                '</div>';
            paintHomeSourceName();
            return;
        }

        var cfg = homeCurrentSrc();
        paintHomeSourceName();
        var cats = (cfg && cfg.cats) || [];

        // 分类条：横向可滑的一行 chips，选中项高亮
        var h = '';
        for (var j = 0; j < cats.length; j++) {
            var on = (App.home.cat === cats[j].k);
            h += '<div class="chip' + (on ? ' on' : '') +
                 '" data-cat="' + escAttr(cats[j].k) +
                 '" onclick="App.ui.homeCat(\'' + escAttr(cats[j].k) + '\')">' +
                 TS.esc(cats[j].n) + '</div>';
        }
        cd.innerHTML = h;

        if (!cfg || !cfg.id || !cats.length) {
            cl.innerHTML = errBlock('这个源暂时没有分类，换个源试试');
            return;
        }

        // 选中项滚到中间，方便看到前后可滑的分类
        centerChip(cd, App.home.cat);
        bindCatSwipe(cd);

        // 内容区：当前分类的列表
        // ★ v3.7.6：如果这个分类已经累积过书（用户在"加载更多"里翻过几页），
        //   重画时直接重放累积结果，不重新联网、也不把已加载的书丢掉。
        //   这正是"点加载更多后又被 paintHomeSources 冲回第 1 页"的修复点。
        if (!force) {
            var acc = App.homeBooks[App.home.cat];
            if (acc && acc.length) {
                replayHomeList(acc, cfg.id, App.home.cat);
                return;
            }
        }

        var cached = browseLoad(cfg.id, App.home.cat, App.home.page, force);
        if (cached) {
            renderHomeList(cached, cfg.id, App.home.cat, App.home.page);
            return;
        }
        cl.innerHTML = '<div class="loading"><span class="spinner"></span>加载中…</div>';
        App.homePending[App.home.cat] = true;
        TS.call('browse', cfg.id, App.home.cat, App.home.page);
    }

    /**
     * ★ v3.7.6：把已累积的列表原样重画一遍（不发请求，不丢分页状态）。
     * 与 renderHomeList 的首屏分支差别只在于"数据从哪来"，
     * 渲染结果完全一致（含底部「加载更多」）。
     */
    function replayHomeList(all, cfgId, catId) {
        renderHomeList(all, cfgId, catId, App.homePage[catId] || 1);
    }

    /**
     * ★ v3.7.6：显式复位首页分页状态。
     * 只在**真正换源 / 换分类 / 强制刷新**时调，不要放进 paintHomeSources ——
     * 它会被很多无关场景（回调、切页返回）调到，放进去就会把加载更多的进度冲掉。
     */
    function resetHomePaging() {
        App.homeHasMore = false;
        App.homeMoreBusy = false;
        App.homeMoreSince = 0;
        App.homeMoreWait = null;
        App.homePageSize = 0;
        // ★ v3.7.11：连同"整页重复自动续翻"的计数一起清掉。
        //   否则换源/换分类后残留的 streak 会让新源第一次遇到重复页就直接判"到底"。
        App._resetHomeEmpty();
    }

    /** ★ v3.7.11：清空"自动跳过重复页"连翻计数（换源/换分类/强制刷新时调） */
    App._resetHomeEmpty = function () {
        App._homeEmptyStreak = 0;
        App._homeLastFresh = 0;
        App._homeLastPage = 0;
    };

    // ==================== ★ v3.7.6：首页默认源（记住上次用的） ====================
    // 之前每次启动 App，首页都要用户手动点一下源才出内容，冷启动第一屏是空白的。
    // 现在把「上次用的源 + 分类」存进 localStorage，冷启动自动选中并直接加载。

    var LAST_SRC_KEY = 'fx_last_home_src_v1';

    /** 记住当前首页选中的源与分类 */
    function saveLastHomeSource(sid) {
        try {
            localStorage.setItem(LAST_SRC_KEY, JSON.stringify({
                src: sid || '',
                cat: App.home.cat || ''
            }));
        } catch (e) {}
    }

    /** 读回上次用的源与分类；没有则返回 null */
    function loadLastHomeSource() {
        try {
            var o = JSON.parse(localStorage.getItem(LAST_SRC_KEY) || 'null');
            if (o && o.src) return o;
        } catch (e) {}
        return null;
    }

    /**
     * ★ v3.7.6：在源列表到手后恢复上次选中的源。
     * 原来的逻辑是 App.home.src 恒为 ''，只能靠 homeCurrentSrc() 回落到第一个源；
     * 但列表顺序会随启用状态变化，"第一个"并不等于"上次用的那个"。
     * 这里显式恢复，并在恢复失败（源被删/被停用）时回落到第一个。
     */
    function restoreLastHomeSource(list) {
        if (!list || !list.length) return false;
        var saved = loadLastHomeSource();
        if (!saved || !saved.src) return false;
        var hit = null;
        for (var i = 0; i < list.length; i++) if (list[i].id === saved.src) hit = list[i];
        if (!hit) return false;                  // 上次的源没了 → 交给 homeCurrentSrc 回落
        App.home.src = hit.id;
        App.home.page = 1;
        // 上次的分类若还在这个源里就沿用，否则回到第一个分类
        var cats = hit.cats || [];
        var catOk = false;
        for (var j = 0; j < cats.length; j++) if (cats[j].k === saved.cat) catOk = true;
        App.home.cat = catOk ? saved.cat : (cats.length ? cats[0].k : '');
        return true;
    }

    /**
     * 分类条左右轻扫 = 切上一个/下一个分类。
     * 只在"扫动"时触发（位移够大且够快），不影响正常的横向滚动浏览。
     */
    var _swipeBound = false;
    function bindCatSwipe(box) {
        if (_swipeBound || !box) return;
        _swipeBound = true;
        var x0 = 0, y0 = 0, t0 = 0, moved = false;
        box.addEventListener('touchstart', function (e) {
            if (!e.touches || e.touches.length !== 1) return;
            x0 = e.touches[0].clientX; y0 = e.touches[0].clientY;
            t0 = Date.now(); moved = false;
        }, { passive: true });
        box.addEventListener('touchmove', function (e) {
            if (!e.touches || e.touches.length !== 1) return;
            var dx = e.touches[0].clientX - x0;
            var dy = e.touches[0].clientY - y0;
            if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) * 1.6) moved = true;
        }, { passive: true });
        box.addEventListener('touchend', function (e) {
            if (!moved) return;
            var t = e.changedTouches && e.changedTouches[0];
            if (!t) return;
            var dx = t.clientX - x0;
            var dt = Date.now() - t0;
            // 快速横扫（120ms 内 <600ms 且横向位移 > 46px）才切分类
            if (dt < 600 && Math.abs(dx) > 46) {
                ui.homeCatStep(dx < 0 ? 1 : -1);
            }
        }, { passive: true });
    }

    /**
     * 把某个 chip 滚到横向容器中间。
     *
     * <p>★ v2.5：
     *  · 支持自定义属性名（首页用 data-cat，发现页同一行里混排源与分类）；
     *  · 首尾自动贴边 —— 第一个滚到 0，最后一个滚到最右，
     *    否则会出现「点第一个却留一大片空白」的怪样子；
     *  · clamp 到 [0, max]，中间项严格居中在容器可见区域正中。
     */
    function centerChip(box, catId, attr) {
        if (!box || !catId) return;
        attr = attr || '[data-cat]';
        requestAnimationFrame(function () {
            var items = box.querySelectorAll(attr);
            var target = null;
            for (var i = 0; i < items.length; i++) {
                var v = items[i].getAttribute('data-cat');
                if (v === catId) { target = items[i]; break; }
            }
            if (!target) return;
            var max = box.scrollWidth - box.clientWidth;
            if (max <= 0) return;                       // 没得滚，全是可见的
            // 目标中心对齐容器中心；首尾由 clamp 自动贴边
            var want = target.offsetLeft + target.offsetWidth / 2 - box.clientWidth / 2;
            box.scrollTo({ left: Math.max(0, Math.min(max, want)), behavior: 'smooth' });
        });
    }

    /**
     * ★ v3.7.10：把首页滚动到「本次新加载的第一张卡」。
     *
     * <p>为什么要滚：新卡是插在「加载更多」按钮**上方**的，用户点按钮时
     * 视线停在按钮处，不滚的话他会以为"只变了数字、内容没变"。
     *
     * <p>★ v3.7.10 修复「加载更多只变页码、内容没更新」：
     *   原来只滚到新卡 `.offsetTop - 12`，也就是"让新内容的第一张刚好贴住
     *   视口顶部"——但用户上一眼看到的最后一张（上一批的末位）正好还占着
     *   视口第一屏，主观感受就是"还是原来那些书"。实测 scrollTop 滚到位后
     *   视野首卡仍是「第一页书20」，第二页的书全在屏幕下方看不见。
     *   现在改为：把新内容的第一张滚到视口**上沿往下 1/4 处**，让新书有
     *   3~4 张完整落进视野，用户一眼就能看出"下面接着来了新的一批"。
     *   同时给这批新卡加一次入场高亮，强化"这里新增了"的感知。
     */
    function scrollHomeToAnchor(anchorId) {
        var wrap = $('homeContentWrap');
        var el = $(anchorId);
        if (!wrap || !el) return;
        requestAnimationFrame(function () {
            // ★ v3.7.10：先把新内容的第一张滚到**视口顶部**（留 8px 余量），
            //   这样上一批的尾巴最多只占屏幕最上面一点点，新书几乎占满一屏，
            //   用户不可能再看漏。原来用 1/4 视口做 lead 仍然会让"上一批最后
            //   一张 + 它的卡片高度"把屏幕顶部占满，实测仍看不到新书。
            var top = el.offsetTop - 8;
            wrap.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
            // ★ v3.7.10：给本批新卡打一次"刚插入"高亮，滑过去时能看见
            markFreshCards(anchorId);
        });
    }

    /**
     * ★ v3.7.10：把从 anchorId 开始、连续的一批 .bookcard 标记为"新增"，
     * 触发一次入场高亮动画（CSS .just-added），动画结束自动摘掉类名。
     * 这样即使滚动动画还没结束，用户也能从高亮看出哪几张是新来的。
     */
    function markFreshCards(anchorId) {
        var anchor = $(anchorId);
        if (!anchor) return;
        // 从锚点开始，收集紧随其后的兄弟卡片（到「加载更多」为止）
        var nodes = [];
        var n = anchor;
        while (n && n.classList && n.classList.contains('bookcard')) {
            nodes.push(n);
            n = n.nextElementSibling;
        }
        if (!nodes.length) return;
        for (var i = 0; i < nodes.length; i++) nodes[i].classList.add('just-added');
        // 动画 1.2s 后摘掉，避免长期保留（下次再来还能重新触发）
        setTimeout(function () {
            for (var j = 0; j < nodes.length; j++) nodes[j].classList.remove('just-added');
        }, 1300);
    }

    /**
     * 首页单分类列表（竖排卡片 + 继续收听）。
     *
     * <p>★ v3.7.5：从「只渲染第 1 页」改为「累积 + 追加 + 加载更多」。
     * 原来一个分类只取一页（约 20 本），滑到底就是「— 第 1 页 —」的提示，
     * 用户反馈"首页的书只有几本，下滑就没了"。现在：
     *  · `App.homeBooks[cat]` 累积该分类已加载的所有书；
     *  · `App.homePage[cat]` 记住已加载到第几页；
     *  · 底部放「加载更多」，并在滚到距底 320px 时自动触发一次；
     *  · 追加而不是重写整块 —— 已渲染的封面不会被重新插入（去掉闪白/重排）。
     *
     * @param {Array} books    本次这一页返回的书
     * @param {string} srcId   源 id
     * @param {string} catId   分类 id
     * @param {number} page    页码（1 起）
     * @param {boolean=} append true=追加到已有列表（加载更多），false/缺省=首屏
     */
    function renderHomeList(books, srcId, catId, page, append) {
        var cl = $('homeContent');
        if (!cl) return;
        page = page || 1;

        // 首屏：清空累积；追加：在已有基础上累加
        if (!append) {
            App.homeBooks[catId] = [];
            App.homePage[catId] = 0;
        }
        var all = App.homeBooks[catId] || (App.homeBooks[catId] = []);
        // ★ v3.7.11：去重指纹。
        //   历史：只取 `key || url || title` 第一个非空字段（v3.7.9 前）→ 不少源的
        //   key 是页面级固定值，第二页的书全被判重复丢掉；
        //   v3.7.10 改成 title+url 组合。
        //   现在保持组合指纹，但**把 key 也纳入**：三者任一不同即视为新书。
        var fp = function (b) {
            if (!b) return '';
            var t = (b.title || '').trim();
            var u = (b.url || '').trim();
            var k = (b.key || '').trim();
            return (t || k || u) + '@@' + (u || k) + '@@' + k;
        };
        var seen = {};
        for (var s = 0; s < all.length; s++) seen[fp(all[s])] = true;
        var fresh = [];
        for (var i = 0; i < (books || []).length; i++) {
            var bk = books[i];
            var kk = fp(bk);
            if (seen[kk]) continue;
            seen[kk] = true;
            all.push(bk);
            fresh.push(bk);
        }

        // ★ v3.7.11：★ 关键修复 —— 页码只在"确实追加到了内容"时才前进 ★
        //   这是「加载更多只变页码、内容不更新」的根因：
        //   源的第二页返回的是与上一页重复的书（站点翻页参数没生效 / 返回同批热门书），
        //   fresh=0，但原来照样 `App.homePage[catId] = page` → 页码前进一格，
        //   底部文案变成「第 N 页」，而列表一本书都没多 —— 用户看到的就是
        //   "页码变了、内容没更新"。
        //   现在：追加模式下若零新增，**不推进页码**，并在下方给出明确提示，
        //   同时由调用方（onBrowse / homeMore）决定是否自动往后再翻一页。
        var gotFresh = fresh.length > 0;
        if (!append || gotFresh) {
            App.homePage[catId] = page;
        }
        App._homeLastFresh = fresh.length;
        App._homeLastPage = page;

        if (!all.length) {
            cl.innerHTML = errBlock('这个分类暂时没有内容（可能是站点改版，或该栏目暂无资源）');
            App.homeHasMore = false;
            return;
        }

        var h = '';
        for (var j = 0; j < fresh.length; j++) h += bookCard(fresh[j], all.length - fresh.length + j);

        // 首页只保留当前分类这一份 DOM；首屏整块替换，追加时插到「加载更多」之前
        if (!append) {
            var head = '';
            head += h;
            head += '<div class="home-more" id="homeMore"></div>';
            head += '<div id="homeContinue"></div>';
            cl.innerHTML = head;
        } else {
            var more = $('homeMore');
            if (more && fresh.length) {
                // ★ v3.7.7：新卡插在「加载更多」**之前**，所以用户滑到底、
                //   点完加载更多后，新内容出现在他视野的上方 —— 他停在按钮处，
                //   看到的是"数目变成 40 本了，但下面的书好像没变"。
                //   给新插入的第一张卡打个锚点，插完把视口滚过去（只滚到"新内容起点"，
                //   不跳到底，这样用户能顺着往下滑，而不是被甩到页面末尾）。
                var anchorId = 'freshAnchor' + Date.now();
                h = h.replace('<div class="bookcard"',
                              '<div class="bookcard" id="' + anchorId + '"', 1);
                more.insertAdjacentHTML('beforebegin', h);
                scrollHomeToAnchor(anchorId);
            } else if (more) {
                more.insertAdjacentHTML('beforebegin', h);
            }
        }

        // ★ v3.7.6：是否还有下一页的判定
        //   原来只看"本次返回非空"（pageSize > 0），有两个毛病：
        //     1) 拿不到"这一页是不是满的"，站点最后一页返回 3 本也会显示"加载更多"，
        //        再点一次拿到空数组才收掉，用户点了个寂寞；
        //     2) 追加时若整页都是重复书（站点翻页重叠），freshCount=0 会被
        //        paintHomeMore 判成"到底"，但其实站点还有下一页。
        //   现在：录一个"满页基准"（首屏这一页的条数），本次返回 >= 基准 就认为还能翻；
        //   返回 0 条才认为到底。
        var pageSize = (books || []).length;
        if (!append && pageSize > 0) App.homePageSize = pageSize;
        var bench = App.homePageSize || 0;
        if (pageSize <= 0) {
            App.homeHasMore = false;                       // 站点返回空 → 真到底
        } else if (bench > 0 && pageSize < bench) {
            App.homeHasMore = false;                       // 不满一页 → 这是最后一页
        } else {
            App.homeHasMore = true;
        }

        // ★ v3.7.11：整页无新增（全是重复书）时，自动往后再翻一页。
        //   场景：源的翻页参数不生效 / 返回同批热门书，直接表现为"点了一次没反应"。
        //   这里最多连翻 5 页，避免源彻底失效时无限请求；翻的过程中底部显示
        //   「正在跳过重复内容…」，让用户知道 App 在工作而不是卡死。
        //   真的连续 5 页都没有新书 → 认为到底，收掉按钮并说明原因。
        if (append && !gotFresh) {
            var streak = (App._homeEmptyStreak || 0) + 1;
            App._homeEmptyStreak = streak;
            if (pageSize > 0 && App.homeHasMore && streak <= 5) {
                paintHomeMoreSkipping(streak);
                var cfg0 = homeCurrentSrc();
                if (cfg0 && cfg0.id) {
                    var nxt = page + 1;
                    App.homeMoreBusy = true;
                    App.homeMoreSince = Date.now();
                    App.homeMoreWait = { src: cfg0.id, cat: catId, page: nxt };
                    TS.call('browse', cfg0.id, catId, nxt);
                    return;                                   // 等下一页回来继续判
                }
            } else if (pageSize > 0 && App.homeHasMore) {
                // 连翻 5 页都没有新书 → 停止，并给出解释
                App.homeHasMore = false;
                App.homeMoreBusy = false;
                App.homeMoreWait = null;
                paintHomeMoreEmptyDone();
                return;
            }
        }
        if (gotFresh) App._homeEmptyStreak = 0;

        paintHomeMore(fresh.length, App.homePage[catId] || page, pageSize);

        // ★ v3.7.5：首页书卡没有 .fav-btn（收藏按钮只在收藏/搜索/详情里），
        // 原来每次都调 favStates 白白走一趟 SQLite —— 首屏真正的"卡"点之一。
        // 首页不需要打标，直接跳过。
        // markFavs(fresh);
        if (App._homeMeta) paintHomeContinue(App._homeMeta);
    }

    /** ★ v3.7.11：正在跳过重复页时的底部提示（替代「加载更多」按钮） */
    function paintHomeMoreSkipping(streak) {
        var more = $('homeMore');
        if (!more) return;
        more.className = 'home-more';
        more.innerHTML = '<span class="hm-tip"><span class="spinner"></span>' +
            '正在跳过重复内容…（第 ' + streak + ' 次）</span>';
    }

    /** ★ v3.7.11：连翻多页都没有新书 —— 明确告知用户，而不是静默"到底" */
    function paintHomeMoreEmptyDone() {
        var more = $('homeMore');
        if (!more) return;
        var n = (App.homeBooks[App.home.cat] || []).length;
        more.className = 'home-more done';
        more.innerHTML = '<span class="hm-tip">— 后面几页都是重复内容，先到这 · 共 ' +
            n + ' 本 —</span>';
    }

    /** 底部「加载更多」的文案/可用态 + 绑定滚到底自动加载（只绑一次） */
    function paintHomeMore(freshCount, page, pageSize) {
        var more = $('homeMore');
        if (!more) return;
        if (!App.homeHasMore) {
            more.className = 'home-more done';
            more.innerHTML = '<span class="hm-tip">— 已经到底了 · 共 ' +
                ((App.homeBooks[App.home.cat] || []).length) + ' 本 —</span>';
            return;
        }
        // ★ v3.7.6：这里原来还有一个 `|| !freshCount` 条件 —— 一旦本次追加
        //   0 条新增（整页重复）就显示"到底"，但站点其实还有下一页。
        //   现在只看 homeHasMore（已在 renderHomeList 里按"满页基准"算好）。
        more.className = 'home-more';
        more.innerHTML = '<button class="mini ghost hm-btn" id="homeMoreBtn" ' +
            'onclick="App.ui.homeMore()">加载更多</button>' +
            '<span class="hm-tip">已加载 ' +
            ((App.homeBooks[App.home.cat] || []).length) + ' 本 · 第 ' + page + ' 页</span>';
        bindHomeMoreScroll();
    }

    /**
     * ★ v3.7.10：把「加载更多」按钮从「加载中…」恢复成可点状态。
     *
     * <p>用于 onBrowse 收到"对不上号"的迟到结果时兜底 —— 保证按钮永远不会
     * 卡在禁用态。文案与是否显示按当前 homeHasMore 决定。
     */
    function fixHomeMoreBtn() {
        var more = $('homeMore');
        if (!more) return;
        if (!App.homeHasMore) { paintHomeMore(0, App.home.page || 1, 0); return; }
        var b = $('homeMoreBtn');
        if (b) { b.disabled = false; b.textContent = '加载更多'; }
    }

    /**
     * ★ v3.7.5：滚到距底 320px 时自动加载下一页（只绑一次）。
     * 用 .content 这个滚动容器（#homeContentWrap）而不是 window。
     */
    var _hmScrollBound = false;
    function bindHomeMoreScroll() {
        if (_hmScrollBound) return;
        var wrap = $('homeContentWrap');
        if (!wrap) return;
        _hmScrollBound = true;
        wrap.addEventListener('scroll', function () {
            if (!App.homeHasMore || App.homeMoreBusy) return;
            if (App.page !== TABS.home) return;
            var near = wrap.scrollHeight - wrap.scrollTop - wrap.clientHeight;
            if (near < 320) ui.homeMore();
        }, { passive: true });
    }

    /** 加载当前分类的下一页 */
    ui.homeMore = function () {
        // ★ v3.7.9：修复「加载更多按钮点了没反应」。
        //   根因：滚到底部时会**自动**触发一次 ui.homeMore（near < 320），
        //   把 homeMoreBusy 置 true；用户随后点按钮，被这个守卫静默 return 掉，
        //   而按钮又没被置灰（disabled 在守卫之后才设），看起来就是"点了没作用"。
        //   现在：正在加载时给按钮明确的"加载中"反馈；并且只在真的卡住（>15s）
        //   时才拦，其余情况允许用户再点一次（重复页会被 onBrowse 的页号匹配丢弃，
        //   不会造成错乱）。
        var now = Date.now();
        if (App.homeMoreBusy) {
            var stuck = App.homeMoreSince && (now - App.homeMoreSince) > 15000;
            if (!stuck) {
                var b0 = $('homeMoreBtn');
                if (b0) { b0.disabled = true; b0.textContent = '加载中…'; }
                return;
            }
            // 卡太久（源超时/网络异常）→ 复位，让这次点击真正生效
            App.homeMoreBusy = false;
            App.homeMoreWait = null;
        }
        if (!App.homeHasMore) return;
        var cfg = homeCurrentSrc();
        if (!cfg || !cfg.id) return;
        var cat = App.home.cat;
        var next = (App.homePage[cat] || 1) + 1;
        // ★ v3.7.11：缓存命中也要走 renderHomeList —— 由它统一判断"这一页有没有新书"，
        //   零新增时它会自动接着往后翻（见 renderHomeList 里的 _homeEmptyStreak 分支）。
        //   若恰好缓存的这一页也是空的，会自动续翻网络页，不会卡在"页码空转"。
        var cached = browseLoad(cfg.id, cat, next, false);
        if (cached) {
            App.homeMoreBusy = true;
            App.homeMoreSince = now;
            renderHomeList(cached, cfg.id, cat, next, true);
            App.homeMoreBusy = false;
            App.homeMoreSince = 0;
            return;
        }
        App.homeMoreBusy = true;
        App.homeMoreSince = now;
        var btn = $('homeMoreBtn');
        if (btn) { btn.disabled = true; btn.textContent = '加载中…'; }
        // 谁在等这一页（首页 onBrowse 用它判断该追加还是整块渲染）
        App.homeMoreWait = { src: cfg.id, cat: cat, page: next };
        TS.call('browse', cfg.id, cat, next);
    };

    /** 当前选中的首页源，必要时回落到列表第一个并把分类校正到合法值 */
    function homeCurrentSrc() {
        var list = App.homeSources || [];
        if (!list.length) return null;
        var cfg = null;
        for (var i = 0; i < list.length; i++) if (list[i].id === App.home.src) cfg = list[i];
        if (!cfg) {
            cfg = list[0];
            App.home.src = cfg.id;
            App.home.page = 1;
            saveLastHomeSource(cfg.id);          // ★ v3.7.6：回落也记住，下次直接命中
        }
        var catOk = false;
        for (var j = 0; cfg.cats && j < cfg.cats.length; j++) {
            if (cfg.cats[j].k === App.home.cat) catOk = true;
        }
        if (!catOk) {
            App.home.cat = (cfg.cats && cfg.cats.length) ? cfg.cats[0].k : '';
            App.home.page = 1;
        }
        return cfg;
    }

    /** 左上角源名称 → 在按钮正下方拉出悬浮下拉 */
    ui.homeSrcToggle = function (ev) {
        if (ev && ev.stopPropagation) ev.stopPropagation();
        var pop = $('homeSrcPop');
        if (pop && !pop.classList.contains('hide')) { ui.closeSrcPop(); return; }

        var list = App.homeSources || [];
        if (!list.length) {
            TS.call('discoverSources');
            TS.toast('正在获取可用书源…');
            return;
        }
        var h = '';
        for (var i = 0; i < list.length; i++) {
            var on = App.home.src === list[i].id;
            h += '<div class="srcdd-item' + (on ? ' on' : '') +
                 '" onclick="App.ui.homePickSrc(\'' + escAttr(list[i].id) + '\')">' +
                 '<span>' + TS.esc(list[i].name) + '</span>' +
                 '<span class="tick">✓</span></div>';
        }
        $('homeSrcList').innerHTML = h;
        pop.classList.remove('hide');
        $('homeSrcBtn').classList.add('open');
        // 点页面任意其它地方收起（用捕获阶段，避免被 stopPropagation 挡掉）
        setTimeout(function () {
            document.addEventListener('click', _ddOutside, true);
        }, 0);
    };

    function _ddOutside(e) {
        var pop = $('homeSrcPop');
        if (!pop || pop.classList.contains('hide')) {
            document.removeEventListener('click', _ddOutside, true);
            return;
        }
        var btn = $('homeSrcBtn');
        if (pop.contains(e.target) || (btn && btn.contains(e.target))) return;
        ui.closeSrcPop();
    }

    ui.closeSrcPop = function () {
        var pop = $('homeSrcPop');
        if (pop) pop.classList.add('hide');
        if ($('homeSrcBtn')) $('homeSrcBtn').classList.remove('open');
        if ($('ddMask')) $('ddMask').classList.add('hide');
        document.removeEventListener('click', _ddOutside, true);
    };

    ui.homePickSrc = function (sid) {
        ui.closeSrcPop();
        if (App.home.src === sid) return;
        App.home.src = sid;
        App.home.page = 1;
        App.homePending = {};
        App.homePage = {};
        App.homeBooks = {};
        resetHomePaging();                       // ★ v3.7.6：换源后重新开始分页
        var list = App.homeSources || [];
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === sid) {
                App.home.cat = (list[i].cats && list[i].cats.length) ? list[i].cats[0].k : '';
            }
        }
        saveLastHomeSource(sid);                 // ★ v3.7.6：记住这次选的源
        paintHomeSources();
    };

    /** 分类 chip：切换当前分类（内容区换成该分类的书） */
    ui.homeCat = function (k) {
        if (App.home.cat === k) return;
        App.home.cat = k;
        App.home.page = 1;
        resetHomePaging();                       // ★ v3.7.6：换分类后重新开始分页
        saveLastHomeSource(App.home.src);        // ★ v3.7.6：记住这次选的分类
        paintHomeSources();
        centerHomeCat(k);
    };

    /**
     * 点分类后把它滚到屏幕中间（★ v2.5）。
     * 首页的分类条是"渲染完就重画整条"，所以居中要在重画之后做，
     * 这里单独抽一个方法，homeCat / homeCatStep 都调它。
     */
    function centerHomeCat(k) {
        setTimeout(function () {
            centerChip($('discoverCats'), k);
        }, 30);
    }

    /** 左右滑动切换分类（在分类条上横向扫动即切换上一个/下一个） */
    ui.homeCatStep = function (dir) {
        var cfg = homeCurrentSrc();
        var cats = (cfg && cfg.cats) || [];
        if (!cats.length) return;
        var idx = -1;
        for (var i = 0; i < cats.length; i++) if (cats[i].k === App.home.cat) idx = i;
        if (idx < 0) idx = 0;
        var n = Math.max(0, Math.min(cats.length - 1, idx + dir));
        if (n === idx) return;
        ui.homeCat(cats[n].k);
    };

    /** 首页底部保留「继续收听」一块，信息密度够但不喧宾夺主 */
    window.onHomeInfo = function (o) {
        o = o || {};
        App._homeMeta = { favCount: o.favCount || 0, historyCount: o.historyCount || 0 };
        paintHomeContinue(o);
    };

    function paintHomeContinue(o) {
        var holder = $('homeContinue');
        if (!holder) return;
        if (!(o && o.continue && o.continue.book && o.continue.book.title)) {
            holder.innerHTML = '';
            App._homeContinue = null;
            return;
        }
        var hb = o.continue.book;
        var cur = Math.round((o.continue.lastPosMs || 0) / 1000);
        App._homeContinue = o.continue;
        var h = '<div class="chapter-hd"><span>继续收听</span></div>';
        h += '<div class="hero" onclick="App.ui.openHistoryItem(\'' +
             escAttr(hb.key || '') + '\')">';
        h += '<img src="' + TS.esc(hb.cover) + '" onerror="TS.imgFail(this)">';
        h += '<div class="hmeta">';
        h += '<div class="hname" data-mq>' + TS.esc(hb.title) + '</div>';
        h += '<div class="hsub">' + TS.esc(o.continue.lastChapTitle || '') +
             (cur ? ' · ' + TS.fmt(cur) : '') + '</div>';
        h += '<div class="hbtn">▶ 接着听</div>';
        h += '</div></div>';
        holder.innerHTML = h;
    }

    /** 发现页的浏览结果（首页已改为横滑分组，走 fillHomeRow） */
    function paintBrowseResult(o) {
        var cl = $('discoverList');
        if (!cl) return;
        if (o && o.error) { cl.innerHTML = errBlock(o.error); return; }
        var books = (o && o.books) || [];
        if (!books.length) {
            cl.innerHTML = errBlock('这个分类暂时没有内容（可能是站点改版，或该栏目暂无资源）');
            return;
        }
        var h = '';
        for (var i = 0; i < books.length; i++) h += bookCard(books[i], i);
        h += '<div class="tip" style="text-align:center;padding:14px">— 第 ' +
             (o.page || 1) + ' 页 —</div>';
        cl.innerHTML = h;
        App.books = books;
        markFavs(books);
    }

    /**
     * 书籍卡片（搜索/收藏/历史/发现/首页通用）。
     * 点击后把整本书当 JSON 传给 openBookJson，与其它列表页行为一致。
     */
    function bookCard(b, i) {
        var h = '<div class="bookcard" onclick="App.ui.openBookJson(\'' +
                escAttr(JSON.stringify(b)) + '\')">';
        // ★ v3.7.5：加 decoding="async" —— 首页一次插入几十张封面时，
        // 同步解码会阻塞主线程造成"加载卡顿"，异步解码把解码挪到别的线程。
        h += '<img src="' + TS.esc(b.cover) + '" onerror="TS.imgFail(this)" ' +
             'loading="lazy" decoding="async">';
        h += '<div class="info">';
        h += '<div class="t" data-mq>' + TS.esc(b.title) + '</div>';
        if (b.author) h += '<div class="s">作者：' + TS.esc(b.author) + '</div>';
        if (b.artist) h += '<div class="s">播音：' + TS.esc(b.artist) + '</div>';
        h += '<div class="tagline">';
        if (b.source) h += '<span class="tag src">' + TS.esc(sourceName(b.source)) + '</span>';
        if (b.sources && b.sources.length > 1) {
            h += '<span class="tag">' + b.sources.length + ' 个来源</span>';
        }
        h += '</div></div></div>';
        return h;
    }

    function quick(ico, name, onclick) {
        return '<div class="qitem" onclick="' + onclick + '">' +
               '<div class="qico">' + ico + '</div>' +
               '<div class="qname">' + name + '</div></div>';
    }

    function escAttr(s) {
        return String(s == null ? '' : s)
            .replace(/\\/g, '\\\\')
            .replace(/'/g, "\\'")
            .replace(/"/g, '&quot;')
            .replace(/\n/g, ' ')
            .replace(/\r/g, ' ');
    }
    ui.escAttr = escAttr;

    // ==================== 搜索 ====================

    // ---------- 搜索历史 ----------

    App.searchHist = [];

    function loadSearchHist() { TS.call('searchHist'); }

    window.onSearchHist = function (arr) {
        App.searchHist = arr || [];
        paintSearchHist();
    };

    /** 只在「还没搜过」的空态下展示历史，避免和结果区抢位 */
    function paintSearchHist() {
        var box = $('searchHist');
        if (!box) return;
        var inIdle = !(App.books && App.books.length) && !App.searchRunning;
        if (!inIdle || !App.searchHist.length) { box.innerHTML = ''; return; }

        var h = '<div class="histhd"><span>搜索历史</span>' +
                '<span class="histclear" onclick="App.ui.clearSearchHist()">清空</span></div>' +
                '<div class="chips histchips">';
        for (var i = 0; i < App.searchHist.length; i++) {
            var it = App.searchHist[i];
            var lit = it.hits > 1 ? ' <b class="hitcnt">' + it.hits + '</b>' : '';
            h += '<div class="chip histchip">' +
                 '<span class="hw" onclick="App.ui.pickSearchHist(\'' +
                 escAttr(it.kw) + '\')">' + TS.esc(it.kw) + lit + '</span>' +
                 '<span class="hd" onclick="App.ui.delSearchHist(event,\'' +
                 escAttr(it.kw) + '\')">&times;</span></div>';
        }
        h += '</div>';
        box.innerHTML = h;
    }

    ui.pickSearchHist = function (kw) {
        $('kw').value = kw;
        ui.doSearch();
    };

    ui.delSearchHist = function (ev, kw) {
        if (ev && ev.stopPropagation) ev.stopPropagation();
        TS.call('searchHistDel', kw);
    };

    ui.clearSearchHist = function () {
        TS.call('searchHistClear');
        TS.toast('已清空搜索历史');
    };

    /** 结果区顶部的「返回历史」：搜完想再点别的历史词 */
    ui.showSearchHist = function () {
        App.books = [];
        App.searchFilter = 'all';
        App.searchRunning = false;
        if ($('searchChips')) $('searchChips').classList.add('hide');
        if ($('searchResult')) $('searchResult').innerHTML = '';
        if ($('searchEmpty')) $('searchEmpty').style.display = '';
        if ($('searchHist')) $('searchHist').style.display = '';
        loadSearchHist();
    };

    ui.doSearch = function () {
        var kw = ($('kw').value || '').trim();
        if (!kw) { TS.toast('请输入关键词'); return; }
        App.searchFilter = 'all';
        App.searchBySource = {};
        App.searchDiag = [];
        App.searchExpanded = false;
        App.searchRunning = true;
        App.books = [];   // ★ v2.2：清空，增量渲染从空列表往上长
        $('searchChips').classList.add('hide');
        // 有搜索动作就收起历史/空态，让结果区独占
        if ($('searchHist')) $('searchHist').innerHTML = '';
        if ($('searchEmpty')) $('searchEmpty').style.display = 'none';
        if ($('searchResult')) {
            $('searchResult').innerHTML =
                '<div class="loading"><div class="spinner"></div>正在多源搜索…</div>';
        }
        TS.call('search', kw, 1);
    };

    /** 原生回传：按源分组的原始结果（未合并），供「按来源筛选」本地过滤 */
    window.onSearchBySource = function (grp) {
        App.searchBySource = grp || {};
        paintSearchChips();
        if (App.searchFilter && App.searchFilter !== 'all') paintSearch();
    };

    /**
     * ★ v2.2 增量渲染：某个源先返回就先画出来，不等其它慢源。
     *
     * <p>之前是「等所有源都回来才 onSearchResult 一次性渲染」，
     * 于是只要有一个源卡到 12 秒超时，用户就瞪着转圈 12 秒才看到第一个字——
     * 而其实有三四个源 500ms 就返回了。现在慢源不再拖累快源。
     *
     * @param o { source, sourceName, books: [...] }
     */
    window.onSearchPartial = function (o) {
        if (!o || !o.books || !o.books.length) return;
        // 只认"当前这一轮搜索"的增量更新：
        // 用户可能已经切走页面或发起了第二次搜索，晚到的旧批次不能污染新结果。
        if (App.page !== 'pageSearch') return;

        App.searchBySource = App.searchBySource || {};
        if (o.source) App.searchBySource[o.source] = o.books;

        // 合并进总表：同 key 去重（和 native 的 merge 同口径）
        var seen = {};
        for (var i = 0; i < App.books.length; i++) {
            var eb = App.books[i];
            seen[tsMergeKey(eb)] = true;
        }
        var added = [];
        for (var j = 0; j < o.books.length; j++) {
            var b = o.books[j];
            var k = tsMergeKey(b);
            if (seen[k]) {
                // 已存在 → 补一下来源数，不重复插入
                for (var m = 0; m < App.books.length; m++) {
                    if (tsMergeKey(App.books[m]) === k) {
                        App.books[m].sourceCount = (App.books[m].sourceCount || 1) + 1;
                        break;
                    }
                }
                continue;
            }
            seen[k] = true;
            App.books.push(b);
            added.push(b);
        }

        // 第一批到达 = 结束"正在多源搜索…"骨架，正式进入结果列表
        if (App.searchRunning) {
            App.searchRunning = false;
            if ($('searchResult')) $('searchResult').innerHTML = '';
        }
        paintSearchChips();
        if (added.length && (!App.searchFilter || App.searchFilter === 'all')) {
            // 追加渲染（保住滚动位置与已加载的图）
            appendSearchCards(added);
        }
    };

    /** 与 native Aggregator.normKey 同口径的归并 key：书名 + (作者||播音) */
    function tsMergeKey(b) {
        if (!b) return '';
        var t = String(b.title || '').replace(
            /[\s《》〈〉()（）\[\]【】\-_.·、,，:：!！?？"'“”‘’]/g, '').toLowerCase();
        var a = (b.author && String(b.author).length) ? b.author : (b.artist || '');
        return t + '|' + String(a).toLowerCase();
    }

    /**
     * 往搜索结果列表**末尾追加**卡片（不整表重绘）。
     *
     * <p>注意：容器里可能还是"正在多源搜索…"的骨架（没有 .bookcard），
     * 这时**不能**直接 bail —— 那正是"边出边渲染"最该生效的时刻。
     * 先清掉骨架再把本批卡片铺上去。
     */
    function appendSearchCards(books) {
        var c = $('searchResult');
        if (!c || !books || !books.length) return;
        // 容器里不是书籍卡片（骨架/空态/历史）→ 先清空，让本批结果独占
        if (!c.querySelector('.bookcard')) c.innerHTML = '';
        var frag = document.createDocumentFragment();
        for (var i = 0; i < books.length; i++) {
            var d = document.createElement('div');
            d.innerHTML = searchCardHtml(books[i], -1);
            while (d.firstChild) frag.appendChild(d.firstChild);
        }
        c.appendChild(frag);
        // 新追加的卡片也要补上收藏态和封面淡入
        markFavs(books);
        if (TS.markImages) TS.markImages(c);
    }

    /** 搜索结果卡片 HTML（供整绘与增量追加共用，保证两处样式一致） */
    function searchCardHtml(b, i) {
        var n = b.sourceCount || 1;
        var click = i >= 0 ? ('App.ui.openSearchBook(' + i + ')')
                           : ("App.ui.openBookJson('" + escAttr(JSON.stringify(b)) + "')");
        var h = '<div class="bookcard" onclick="' + click + '">';
        h += '<img src="' + TS.esc(b.cover) + '" onerror="TS.imgFail(this)" loading="lazy" decoding="async">';
        h += '<div class="info">';
        h += '<div class="t" data-mq>' + TS.esc(b.title) + '</div>';
        if (b.author) h += '<div class="s">作者：' + TS.esc(b.author) + '</div>';
        if (b.artist) h += '<div class="s">播音：' + TS.esc(b.artist) + '</div>';
        h += '<div class="tagline">';
        if (n > 1) h += '<span class="tag">' + n + ' 源</span>';
        if (b.source) h += '<span class="tag src">' + TS.esc(sourceName(b.source)) + '</span>';
        if (b.category) h += '<span class="tag src">' + TS.esc(b.category) + '</span>';
        h += '</div></div></div>';
        return h;
    }

    window.onSearchResult = function (arr) {
        App.books = arr || [];
        App.searchRunning = false;
        paintSearchChips();
        paintSearch();
        // 顺手刷新历史（刚搜的词已入库）
        loadSearchHist();
    };

    /** 搜索诊断：每个源是命中 / 无结果 / 超时 / 失败了 */
    window.onSearchDiagnostics = function (arr) {
        App.searchDiag = arr || [];
        paintSearchChips();
        // 空结果时把「为什么没结果」补进内容区
        if (!App.books.length) paintSearch();
    };

    var DIAG_TEXT = {
        0: '命中', 1: '无结果', 2: '超时', 3: '失败'
    };

    function diagSummary() {
        var d = App.searchDiag || [];
        if (!d.length) return '';
        var hit = 0, empty = 0, slow = 0, bad = 0;
        for (var i = 0; i < d.length; i++) {
            var s = d[i].status;
            if (s === 0) hit++;
            else if (s === 1) empty++;
            else if (s === 2) slow++;
            else bad++;
        }
        var bits = ['已搜 ' + d.length + ' 源', '命中 ' + hit];
        if (empty) bits.push('无结果 ' + empty);
        if (slow) bits.push('超时 ' + slow);
        if (bad) bits.push('失败 ' + bad);
        return bits.join(' · ');
    }

    ui.toggleDiag = function () {
        App.searchExpanded = !App.searchExpanded;
        paintSearchChips();
    };

    /** 来源筛选条：全部 + 各源（带命中数，0 条的源不显示）+ 诊断行 */
    function paintSearchChips() {
        var el = $('searchChips');
        if (!el) return;
        var grp = App.searchBySource || {};
        var ids = [];
        for (var k in grp) if (grp[k] && grp[k].length) ids.push(k);
        var sum = diagSummary();
        if (!App.books.length && !ids.length && !sum) { el.classList.add('hide'); return; }
        el.classList.remove('hide');
        var h = '<div class="chiprow">';
        h += '<div class="chip' + (App.searchFilter === 'all' ? ' on' : '') +
                '" onclick="App.ui.filterSearch(\'all\')">全部 ' + App.books.length + '</div>';
        for (var i = 0; i < ids.length; i++) {
            var id = ids[i];
            h += '<div class="chip' + (App.searchFilter === id ? ' on' : '') +
                 '" onclick="App.ui.filterSearch(\'' + id + '\')">' +
                 TS.esc(sourceName(id)) + ' ' + grp[id].length + '</div>';
        }
        h += '</div>';

        if (sum) {
            h += '<div class="diagline" onclick="App.ui.toggleDiag()">' +
                 '<span class="dtxt">' + TS.esc(sum) + '</span>' +
                 '<span class="darr">' + (App.searchExpanded ? '⌃' : '⌄') + '</span></div>';
            if (App.searchExpanded) {
                h += '<div class="diaglist">';
                var d = App.searchDiag || [];
                for (var j = 0; j < d.length; j++) {
                    var it = d[j];
                    var cls = it.status === 0 ? 'ok'
                            : (it.status === 1 ? 'mut' : 'bad');
                    var right = it.status === 0 ? (it.count + ' 条')
                            : DIAG_TEXT[it.status];
                    h += '<div class="diagrow">' +
                         '<span class="dn">' + TS.esc(it.name || it.id) + '</span>' +
                         '<span class="ds ' + cls + '">' + TS.esc(right) +
                         (it.error ? (' · ' + TS.esc(it.error)) : '') + '</span></div>';
                }
                h += '</div>';
            }
        }
        el.innerHTML = h;
    }

    ui.filterSearch = function (id) {
        App.searchFilter = id;
        paintSearchChips();
        paintSearch();
        // ★ 让被选中的源滚到可视区中间，而不是整条滚回最左
        centerSearchChip(id);
    };

    /**
     * 把指定 chip 滚动到横向容器正中。
     * 首尾两端长度不够时自然贴边（scrollLeft 会被浏览器夹在 [0, max] 内），
     * 这就是"前后长度不够按合理方式排布"的含义。
     */
    function centerSearchChip(id) {
        var el = $('searchChips');
        if (!el) return;
        var row = el.querySelector('.chiprow');
        if (!row) return;
        var items = row.querySelectorAll('.chip');
        var target = null;
        // 约定：chip 的 onclick 里带 data-src 更稳，但为兼容旧结构用索引匹配
        for (var i = 0; i < items.length; i++) {
            var oc = items[i].getAttribute('onclick') || '';
            if (oc.indexOf("'" + id + "'") >= 0) { target = items[i]; break; }
        }
        if (!target) return;
        requestAnimationFrame(function () {
            var rowW = row.clientWidth;
            var max = row.scrollWidth - rowW;
            if (max <= 0) return;
            var want = target.offsetLeft - (rowW - target.offsetWidth) / 2;
            row.scrollTo({ left: Math.max(0, Math.min(max, want)), behavior: 'smooth' });
        });
    }

    function paintSearch() {
        var c = $('searchResult');
        if (!c) return;
        var filter = App.searchFilter || 'all';
        var list;
        if (filter === 'all') {
            list = App.books;
        } else {
            list = (App.searchBySource || {})[filter] || [];
        }
        if (!list || !list.length) {
            var extra = '换个关键词，或在「书源管理」里启用更多源';
            if (filter === 'all' && (App.searchDiag || []).length) {
                var idm = 0, slw = 0, bad = 0;
                for (var z = 0; z < App.searchDiag.length; z++) {
                    var st = App.searchDiag[z].status;
                    if (st === 1 || st === 0) idm++;
                    else if (st === 2) slw++;
                    else bad++;
                }
                var why = [];
                if (idm) why.push(idm + ' 个源确实没这本');
                if (slw) why.push(slw + ' 个源超时');
                if (bad) why.push(bad + ' 个源请求失败');
                if (why.length) extra = why.join('，') + '。展开上方诊断可看每个源的状态';
            }
            c.innerHTML = '<div class="empty"><div class="big">🔍</div>没有找到相关书籍<br>' +
                '<span style="font-size:12.5px">' + TS.esc(extra) + '</span></div>';
            return;
        }
        var h = '';
        if ((App.searchHist || []).length) {
            h += '<div class="histback" onclick="App.ui.showSearchHist()">' +
                 '&#8592; 搜索历史</div>';
        }
        for (var i = 0; i < list.length; i++) {
            var b = list[i];
            var n = b.sourceCount || 1;
            h += '<div class="bookcard" onclick="App.ui.openSearchBook(' + i + ')">';
            h += '<img src="' + TS.esc(b.cover) + '" onerror="TS.imgFail(this)" loading="lazy">';
            h += '<div class="info">';
            h += '<div class="t" data-mq>' + TS.esc(b.title) + '</div>';
            if (b.author) h += '<div class="s">作者：' + TS.esc(b.author) + '</div>';
            if (b.artist) h += '<div class="s">播音：' + TS.esc(b.artist) + '</div>';
            h += '<div class="tagline">';
            // 来源标签：筛选时显示该条的实际来源；全部视图显示多源标记
            if (filter !== 'all') {
                h += '<span class="tag src">' + TS.esc(sourceName(b.source)) + '</span>';
            } else if (n > 1) {
                h += '<span class="tag">' + n + ' 源</span>';
            }
            if (b.category) h += '<span class="tag src">' + TS.esc(b.category) + '</span>';
            h += '</div></div>';
            h += '<div class="fav-btn" data-key="' + TS.esc(b.key || '') + '" ' +
                 'onclick="event.stopPropagation();App.ui.toggleFavJson(\'' +
                 escAttr(JSON.stringify(b)) + '\')">☆</div>';
            h += '</div>';
        }
        c.innerHTML = h;
        c.scrollTop = 0;
        markFavs(list);
    }

    /** 打开搜索结果：优先用「全部」合并视图的索引；筛选视图时先换数据源 */
    ui.openSearchBook = function (i) {
        var filter = App.searchFilter || 'all';
        var list = filter === 'all' ? App.books : ((App.searchBySource || {})[filter] || []);
        var b = list[i];
        if (b) openBookObj(b);
    };

    function markFavs(arr) {
        var keys = [];
        for (var i = 0; i < arr.length; i++) {
            if (arr[i] && arr[i].key) keys.push(arr[i].key);
        }
        if (keys.length) TS.call('favStates', JSON.stringify(keys));
        else paintFavIcons();
    }

    window.onFavStates = function (keys) {
        App.favKeys = {};
        keys = keys || [];
        for (var i = 0; i < keys.length; i++) App.favKeys[keys[i]] = true;
        paintFavIcons();
    };

    function paintFavIcons() {
        var els = document.querySelectorAll('.fav-btn[data-key]');
        for (var i = 0; i < els.length; i++) {
            var on = !!App.favKeys[els[i].getAttribute('data-key')];
            els[i].textContent = on ? '★' : '☆';
            els[i].classList[on ? 'add' : 'remove']('on');
        }
    }

    // ==================== 详情 ====================

    ui.openBook = function (i) {
        var b = App.books[i];
        if (b) openBookObj(b);
    };

    ui.openBookJson = function (json) {
        try { openBookObj(JSON.parse(json)); } catch (e) { TS.toast('数据异常'); }
    };

    function openBookObj(b) {
        App.book = b;
        App.chapters = [];
        App.chapIdx = -1;
        App.detailIntro = '';
        App._lastHistory = null;
        goSub('detail');
        $('detailTitle').textContent = b.title || '详情';
        // 先用书名/封面把骨架撑起来，让页面"立刻有内容"，
        // 章节表回来再补 —— 比干等一个转圈舒服得多。
        renderDetailSkeleton(b);
        TS.call('detailFromJson', JSON.stringify(b));
        if (b.key) TS.call('historyGet', b.key);
    }

    /** 详情页骨架屏：封面 + 书名 + 来源，章节位先占住 */
    function renderDetailSkeleton(b) {
        var c = $('detailContent');
        if (!c || !b) return;
        var h = '<div class="detail-head">';
        h += '<img src="' + TS.esc(b.cover || '') + '" onerror="TS.imgFail(this)" loading="lazy">';
        h += '<div class="meta">';
        h += '<div class="t" data-mq>' + TS.esc(b.title || '') + '</div>';
        if (b.author) h += '<div class="row">作者：' + TS.esc(b.author) + '</div>';
        if (b.artist) h += '<div class="row">播音：' + TS.esc(b.artist) + '</div>';
        if (b.source) h += '<div class="row">来源：<span class="src-tag">' +
                          TS.esc(sourceName(b.source)) + '</span></div>';
        h += '</div></div>';
        // 章节位先用几条"假行"占住高度，视觉上像内容已经在路上，
        // 比一个孤零零的转圈要让人安心，也不会在章节回来时整页跳动。
        h += '<div class="chapter-hd"><span>章节列表</span>' +
             '<span class="rt" id="skelTip"><span class="skel-dot"></span>正在获取…</span></div>';
        h += '<div class="skel-list">';
        for (var i = 0; i < 8; i++) {
            h += '<div class="chapter skel"><div class="no skel-bar" style="width:18px"></div>' +
                 '<div class="tt"><div class="skel-bar" style="width:' +
                 (46 + (i % 4) * 13) + '%"></div></div></div>';
        }
        h += '</div>';
        c.innerHTML = h;
        c.scrollTop = 0;
    }

    window.onHistoryGet = function (h) {
        App._lastHistory = (h && h.book && h.book.title) ? h : null;
        // ★ 只在章节已经到位时才重绘。
        //   历史是另一个异步回调，通常比章节先回来；此时 App.chapters 还是空的，
        //   直接 renderDetail() 会把骨架屏覆盖成"没读到章节"，
        //   章节稍后回来虽然会重绘，但用户会先看到一次刺眼的失败态。
        if (App.page === SUBS.detail && App.chapters.length) renderDetail();
    };

    window.onDetail = function (d) {
        if (!d) {
            if (App.page === SUBS.detail) {
                $('detailContent').innerHTML =
                    '<div class="empty"><div class="big">😢</div>加载失败，请返回重试</div>';
            }
            return;
        }
        var b = (d.book && d.book.title) ? d.book : App.book;
        if (b) App.book = b;
        App.chapters = d.chapters || [];
        App.detailIntro = d.intro || '';
        // ★ v3.7.9：一批新章节到手，区间段号要按新总数收敛（上一本可能只有 1 段，
        //   这本有 8 段，用户上次停在第 3 段 → 保持不变；反之超出就夹回最后一段）。
        loadChapView();
        if (App.chapters.length) {
            var w0 = chapWindow(App.chapters.length);
            if (App.chapView.seg !== w0.seg) {
                App.chapView.seg = w0.seg;
                saveChapView();
            }
        }

        // 会话恢复场景：native 只回了集号，这里拿到完整章节表后校正 index
        if (App.sessionResume && App.chapters.length) {
            var want = App.chapIdx;
            if (want < 0 || want >= App.chapters.length) want = 0;
            App.chapIdx = want;
            var ch = App.chapters[want];
            if (ch) setPTitle(ch.title || '');
            showPlayerBar();
            App.sessionResume = false;
            saveSession();
        }

        // ★ v2.6：冷启动点悬浮球时章节表可能还没回来，自动播放被挂起了。
        //   现在章节表到了，兑现这次播放（走完整的取链流程）。
        if (App.pendingAutoPlay && App.chapters.length) {
            App.pendingAutoPlay = false;
            clearTimeout(App._autoPlayTimer);
            var pi = (typeof App.pendingPlayIdx === 'number' && App.pendingPlayIdx >= 0)
                ? App.pendingPlayIdx : App.chapIdx;
            App.pendingPlayIdx = -1;
            if (pi < 0 || pi >= App.chapters.length) pi = 0;
            ui.playChapter(pi);
        } else if (App.pendingAutoPlay) {
            // 详情回来了但没有章节（源失效）：明确告知，别让用户看着转圈
            App.pendingAutoPlay = false;
            App.pendingPlayIdx = -1;
            clearTimeout(App._autoPlayTimer);
            setBuffering(false);
            TS.toast('这本书当前源已失效，换一本试试');
        }

        if (App.page === SUBS.detail) {
            $('detailTitle').textContent = (b && b.title) || '详情';
            renderDetail();
        }
    };

    // ==================== ★ v3.7.9：章节列表 分列 / 区间 ====================
    //
    // 长列表（几千集）的痛点：一列得滑很久，而且想跳到第 1500 集基本靠拖。
    // 给两个控件：
    //   ① 分列 1 / 2 / 3 —— 手机上 2 列通常最舒服，3 列适合集名很短的源；
    //   ② 区间切换 —— 每 200 集一段，直接点段号跳过去（第 3 段 = 401~600）。
    //
    // 状态存在 App.chapView，从详情页返回再进来会保持，不会每次都重置成 1 列第 1 段。

    /** 每段集数（区间切换的步长） */
    var CHAP_SEG = 200;

    /** 取当前分列数（1/2/3，默认 1） */
    function chapCols() {
        var n = App.chapView && App.chapView.cols;
        return (n === 2 || n === 3) ? n : 1;
    }

    /** 取当前区间下标（0 基，默认 0） */
    function chapSeg() {
        var n = App.chapView && App.chapView.seg;
        return (typeof n === 'number' && n >= 0) ? n : 0;
    }

    /** 当前区间覆盖的下标范围 [start, end) */
    function chapWindow(total) {
        var segCount = Math.max(1, Math.ceil(total / CHAP_SEG));
        var seg = chapSeg();
        if (seg >= segCount) seg = segCount - 1;
        if (seg < 0) seg = 0;
        var start = seg * CHAP_SEG;
        var end = Math.min(total, start + CHAP_SEG);
        return { start: start, end: end, seg: seg, segCount: segCount };
    }

    /** 区间段的中文标签：第 1 段 → 1~200 */
    function chapSegLabel(i, total) {
        var a = i * CHAP_SEG + 1;
        var b = Math.min(total, (i + 1) * CHAP_SEG);
        return a + '~' + b;
    }

    /**
     * 渲染章节工具条：分列切换 + 区间切换。
     * 集数不多（<= 一屏能忍）时只出分列，不出现区间（省得界面啰嗦）。
     */
    function renderChapTools(total) {
        var cols = chapCols();
        var win = chapWindow(total);

        var h = '<div class="chap-tools" id="chapTools">';

        // 列数切换
        var names = { 1: '1 列', 2: '2 列', 3: '3 列' };
        h += '<div class="ct-group"><span class="ct-lb">显示</span>';
        for (var n = 1; n <= 3; n++) {
            h += '<span class="ct-btn' + (n === cols ? ' on' : '') +
                 '" onclick="App.ui.chapSetCols(' + n + ')">' + names[n] + '</span>';
        }
        h += '</div>';

        // 区间切换：只在真的需要分段时才出现
        if (win.segCount > 1) {
            h += '<div class="ct-group"><span class="ct-lb">区间</span>';
            h += '<span class="ct-btn ct-nav" onclick="App.ui.chapSegStep(-1)"' +
                 (win.seg <= 0 ? ' disabled' : '') + '>‹</span>';
            for (var i = 0; i < win.segCount; i++) {
                // 段太多（>12 段）只显示当前段附近，避免一排按钮横到天边
                if (win.segCount > 12 && Math.abs(i - win.seg) > 2 && i !== 0
                        && i !== win.segCount - 1) {
                    if (Math.abs(i - win.seg) === 3) h += '<span class="ct-dots">…</span>';
                    continue;
                }
                h += '<span class="ct-btn' + (i === win.seg ? ' on' : '') +
                     '" onclick="App.ui.chapSetSeg(' + i + ')">' +
                     chapSegLabel(i, total) + '</span>';
            }
            h += '<span class="ct-btn ct-nav" onclick="App.ui.chapSegStep(1)"' +
                 (win.seg >= win.segCount - 1 ? ' disabled' : '') + '>›</span>';
            h += '</div>';
        }

        h += '</div>';
        return h;
    }

    /** 切换分列（1/2/3）：只改容器 class + 重画工具条，不整页重绘 */
    ui.chapSetCols = function (n) {
        if (n !== 1 && n !== 2 && n !== 3) return;
        if (!App.chapView) App.chapView = { cols: 1, seg: 0 };
        App.chapView.cols = n;
        saveChapView();
        var list = $('chapList');
        if (list) {
            list.className = 'chapter-list cols-' + n;
        }
        var tools = $('chapTools');
        if (tools) tools.outerHTML = renderChapTools(App.chapters.length);
    };

    /** 跳到指定区间段：需要重画列表（只画这一段） */
    ui.chapSetSeg = function (i) {
        if (typeof i !== 'number' || i < 0) return;
        if (!App.chapView) App.chapView = { cols: 1, seg: 0 };
        App.chapView.seg = i;
        saveChapView();
        renderDetail();
        // 换段后回到列表顶部，别停在上一段的滚动位置
        var c = $('detailContent');
        if (c) c.scrollTop = 0;
    };

    /** 上一段 / 下一段 */
    ui.chapSegStep = function (d) {
        var win = chapWindow(App.chapters.length);
        ui.chapSetSeg(win.seg + d);
    };

    /** 章节视图偏好持久化（下次进详情页还是这个列数/段号） */
    function saveChapView() {
        try {
            localStorage.setItem('fx_chap_view_v1', JSON.stringify(App.chapView || {}));
        } catch (e) {}
    }

    function loadChapView() {
        try {
            var s = localStorage.getItem('fx_chap_view_v1');
            if (s) {
                var o = JSON.parse(s);
                App.chapView = {
                    cols: (o && (o.cols === 2 || o.cols === 3)) ? o.cols : 1,
                    seg: (o && typeof o.seg === 'number' && o.seg >= 0) ? o.seg : 0
                };
            }
        } catch (e) {}
        if (!App.chapView) App.chapView = { cols: 1, seg: 0 };
    }

    function renderDetail() {
        var c = $('detailContent');
        var b = App.book || {};
        var chs = App.chapters;

        if (!chs.length) {
            c.innerHTML = '<div class="empty"><div class="big">😢</div>没读到章节<br>' +
                '<span style="font-size:12.5px">这本书在当前源可能已失效，返回换同名书试试</span></div>';
            return;
        }

        var key = b.key || '';
        var isFav = !!App.favKeys[key];
        var lh = App._lastHistory;
        var hasHist = lh && lh.book && lh.book.key === key;

        var h = '<div class="detail-head">';
        h += '<img src="' + TS.esc(b.cover) + '" onerror="TS.imgFail(this)">';
        h += '<div class="meta">';
        h += '<div class="t" data-mq>' + TS.esc(b.title) + '</div>';
        if (b.author) h += '<div class="row">作者：' + TS.esc(b.author) + '</div>';
        if (b.artist) h += '<div class="row">播音：' + TS.esc(b.artist) + '</div>';
        if (b.source) {
            h += '<div class="row">来源：<span class="src-tag">' +
                 TS.esc(sourceName(b.source)) + '</span></div>';
        }
        if (b.sources && b.sources.length > 1) {
            var others = [];
            for (var si = 0; si < b.sources.length; si++) {
                if (b.sources[si] !== b.source) others.push(sourceName(b.sources[si]));
            }
            if (others.length) {
                h += '<div class="row">其他版本：' + TS.esc(others.join('、')) + '</div>';
            }
        }
        h += '<div class="row">共 ' + chs.length + ' 集</div>';
        h += '<div class="detail-actions">';

        if (hasHist) {
            h += '<button class="primary" onclick="App.ui.resumePlay()">▶ 续听 第' +
                 (lh.lastIndex || 1) + '集</button>';
            h += '<button onclick="App.ui.playChapter(0)">从头</button>';
        } else {
            h += '<button class="primary" onclick="App.ui.playChapter(0)">▶ 播放</button>';
        }
        h += '<button id="favBtnDetail" class="' + (isFav ? 'on' : '') + '" onclick="App.ui.toggleFavJson(\'' +
             escAttr(JSON.stringify(b)) + '\')">' + (isFav ? '★ 已收藏' : '☆ 收藏') + '</button>';
        // ★ v2.9：文案从「缓存」改为「下载全部」——
        //   缓存已改为纯自动（播放时预读），不再有手动入口；
        //   用户手点的这一下，语义就是「下载」。
        h += '<button onclick="App.ui.downloadAll()">⬇ 下载全部</button>';
        h += '<button onclick="App.ui.shareBook()">↗ 分享</button>';
        h += '</div></div></div>';

        if (App.detailIntro) {
            h += '<div class="intro"><div class="h">简介</div>' +
                 TS.esc(App.detailIntro).replace(/\n/g, '<br>') + '</div>';
        }

        // ★ v2.9：多选吸顶条。平时 display:none，进入多选才出现。
        //   放在章节列表上方，用 position:sticky 吸顶 —— 长列表滚动到
        //   下面时操作按钮依然可见（不然要滚回顶部才能点「下载」）。
        h += '<div class="multi-bar" id="multiBar" style="display:none">';
        h += '<span class="mb-cnt" id="multiCount"></span>';
        h += '<span class="mb-acts">';
        h += '<button class="mb-btn" onclick="App.ui.selAll()">全选</button>';
        h += '<button class="mb-btn" onclick="App.ui.selInvert()">反选</button>';
        h += '<button class="mb-btn primary" id="multiDl" onclick="App.ui.selDownload()">下载</button>';
        h += '<button class="mb-btn" onclick="App.ui.exitMulti()">完成</button>';
        h += '</span></div>';

        h += '<div class="chapter-hd"><span>章节列表</span>' +
             '<span class="rt">共 ' + chs.length + ' 集</span></div>';

        // ★ v3.7.9：章节列表太长（几百上千集）时的两个控件 ——
        //   ① 分列：1 / 2 / 3 列，长列表一屏能看更多；
        //   ② 区间：每 200 集切一段，直接跳段，不用滚到底。
        //   两者都存进 App.chapView，切页面/重进详情时保持用户的选择。
        h += renderChapTools(chs.length);

        // ★ v3.7.9：只渲染「当前区间」那一段（每段 200 集），
        //   不然上千集一次性建几千个 DOM 节点，进详情页会明显卡顿。
        var win = chapWindow(chs.length);
        h += '<div class="chapter-list cols-' + chapCols() + '" id="chapList">';
        for (var i = win.start; i < win.end; i++) {
            var ch = chs[i];
            var no = ch.index || (i + 1);
            // ★ v2.9：长按进入多选（600ms）。
            //   oncontextmenu 返回 false 抑制系统菜单 —— 否则部分 ROM 会
            //   在长按后弹出「复制链接」之类的原生菜单，把我们的手势顶掉。
            h += '<div class="chapter" id="ch' + i + '" data-idx="' + no +
                 '" onclick="App.ui.onChapClick(' + i + ')"' +
                 ' ontouchstart="App.ui.chapPressStart(event,' + no + ')"' +
                 ' ontouchend="App.ui.chapPressEnd()" ontouchmove="App.ui.chapPressEnd()"' +
                 ' oncontextmenu="return false">';
            // 多选时才显示的勾选框（CSS 控制显隐，避免重排）
            h += '<div class="chk"></div>';
            h += '<div class="no">' + no + '</div>';
            h += '<div class="tt">' + TS.esc(ch.title || ('第' + no + '集')) + '</div>';
            // ★ v2.8 单集下载按钮。
            //   data-dl 绑的是「集号 no」（1 基），不是循环下标 i ——
            //   原生 CacheRepo/DownloadRepo 全用 1 基 humanIdx 作键，必须对齐。
            //   stopPropagation 防止冒泡到行上的点击处理。
            h += '<div class="dl" data-dl="' + no +
                 '" onclick="event.stopPropagation();App.ui.dlChapter(' + no + ')"></div>';
            h += '</div>';
        }
        h += '</div>';
        c.innerHTML = h;
        c.scrollTop = 0;
        if (App.chapIdx >= 0) highlight(App.chapIdx);
        // 章节行建好后按下载状态刷一遍图标
        TS.call('cacheStatus', JSON.stringify(b));
        paintAllDlIcons();
        paintMultiUI();
    }

    // ---- ★ v2.9 长按手势 ----

    var _pressTimer = null;

    /** 按下：600ms 后被判定为长按（不用 click 是因为移动端 click 有 300ms 延迟） */
    ui.chapPressStart = function (ev, no) {
        ui.chapPressEnd();
        _pressTimer = setTimeout(function () {
            _pressTimer = null;
            if (App.multi) {
                ui.exitMulti();          // 已多选 → 长按退出
            } else {
                ui.enterMulti(no);       // 未多选 → 长按进入并勾中该行
            }
            if (window.navigator && navigator.vibrate) {
                try { navigator.vibrate(15); } catch (e) {}
            }
        }, 600);
    };

    ui.chapPressEnd = function () {
        if (_pressTimer) { clearTimeout(_pressTimer); _pressTimer = null; }
    };

    /**
     * 章节行点击。
     * 多选模式下 = 切换勾选；平时 = 正常播放。
     */
    ui.onChapClick = function (i) {
        if (App.multi) { ui.toggleSel(chapNo(i)); return; }
        ui.playChapter(i);
    };

    ui.resumePlay = function () {
        var lh = App._lastHistory;
        if (!lh) return;
        var idx = (lh.lastIndex || 1) - 1;
        if (idx < 0) idx = 0;
        if (idx >= App.chapters.length) idx = App.chapters.length - 1;
        ui.playChapter(idx);
    };

    // ==================== 收藏 ====================

    ui.toggleFavJson = function (json) {
        var b;
        try { b = JSON.parse(json); } catch (e) { return; }
        TS.call('favToggle', JSON.stringify(b), App.curGroup < 0 ? 0 : App.curGroup);
    };

    window.onFavChanged = function (o) {
        o = o || {};
        if (!o.ok) { TS.toast('操作失败'); return; }
        if (o.fav) App.favKeys[o.key] = true; else delete App.favKeys[o.key];
        TS.toast(o.fav ? '已加入收藏' : '已取消收藏');
        paintFavIcons();
        if (App.page === SUBS.detail) renderDetail();
        if (App.page === SUBS.full) paintFullFav();
        if (App.page === TABS.fav) loadFavs();
        if (App.page === TABS.home) loadHome();
    };

    function loadFavs() { TS.call('favList', App.curGroup); }

    window.onFavList = function (arr) {
        var c = $('favContent');
        arr = arr || [];
        App._favCache = arr;
        var h = '';

        h += '<div class="chips">';
        h += chip('全部', -1, null);
        for (var g = 0; g < App.groups.length; g++) {
            var grp = App.groups[g];
            h += chip(grp.name + (grp.count ? ' ' + grp.count : ''), grp.id, grp.id);
        }
        h += '<div class="chip add" onclick="App.ui.newGroup()">+ 新分组</div>';
        h += '</div>';

        if (!arr.length) {
            h += '<div class="empty"><div class="big">⭐</div>还没有收藏<br>' +
                 '<span style="font-size:12.5px">搜索到喜欢的书点 ☆ 即可收藏</span></div>';
        } else {
            // ★ v3.7.11：收藏页从「横向卡片列表」换成了「书本封面陈列」。
            //   每本书做成一册竖立的书：带书脊、书页裁切、轻投影与倾斜光泽，
            //   下方配书名/主播/来源。观感更像书架，也符合整体暖橙主题。
            //   分割沿用同一套「仿制模板」：书脊 + 页边 + 底部书托。
            h += '<div class="shelf">';
            for (var i = 0; i < arr.length; i++) {
                var b = arr[i];
                h += '<div class="bk" data-fav-key="' + escAttr(b.key || '') + '" ' +
                     'data-fav-json="' + escAttr(JSON.stringify(b)) + '" ' +
                     'onclick="App.ui.openBookJson(this.getAttribute(\'data-fav-json\'))">';
                h += '<div class="bk-cover">';
                if (b.cover) {
                    h += '<img src="' + TS.esc(b.cover) + '" onerror="TS.imgFail(this)" loading="lazy">';
                } else {
                    h += '<div class="bk-ph">' + TS.esc((b.title || '书').slice(0, 1)) + '</div>';
                }
                // 书脊（左侧竖条）+ 顶部光泽
                h += '<span class="bk-spine"></span>';
                h += '<span class="bk-gloss"></span>';
                // 收藏星标（右上角），点它可直接取消收藏（保留快捷方式）
                h += '<span class="bk-star on" onclick="event.stopPropagation();' +
                     'App.ui.favRemove(\'' + escAttr(b.key || '') + '\')">★</span>';
                h += '</div>';
                h += '<div class="bk-info">';
                h += '<div class="bk-t" data-mq>' + TS.esc(b.title || '') + '</div>';
                if (b.artist) h += '<div class="bk-s">' + TS.esc(b.artist) + '</div>';
                else if (b.author) h += '<div class="bk-s">' + TS.esc(b.author) + '</div>';
                h += '</div>';
                h += '</div>';
            }
            h += '</div>';
            h += '<div class="shelf-hint">长按书本可删除 / 换源 / 搜索</div>';
        }
        c.innerHTML = h;
        bindFavMenu(c);
    };

    /** ★ v3.7.11：收藏书本长按菜单（与历史页同一套交互） */
    function bindFavMenu(c) {
        App._bindLongPress(c, '.bk', function (el) {
            var key = el.getAttribute('data-fav-key') || '';
            var bk = null;
            try { bk = JSON.parse(el.getAttribute('data-fav-json') || 'null'); } catch (e) { bk = null; }
            var items = [];
            items.push({
                label: '取消收藏', icon: '🗑', danger: true,
                pick: function () { ui.favRemove(key); }
            });
            if (bk) {
                items.push({
                    label: '换源播放', icon: '🔀',
                    pick: function () { if (ui.openSwap) ui.openSwap(bk); }
                });
                items.push({
                    label: '搜索这本书', icon: '🔍',
                    pick: function () { if (ui.searchBook) ui.searchBook(bk); }
                });
            }
            return items;
        });
    }

    function chip(name, id) {
        return '<div class="chip' + (App.curGroup === id ? ' on' : '') +
               '" onclick="App.ui.setGroup(' + id + ')">' + TS.esc(name) + '</div>';
    }

    ui.setGroup = function (id) {
        App.curGroup = id;
        loadFavs();
    };

    ui.favRemove = function (key) { TS.call('favRemove', key); };

    ui.newGroup = function () {
        ui.inputSheet('新分组名称', '', function (name) {
            if (name && name.trim()) TS.call('groupAdd', name.trim());
        });
    };

    window.onGroupChanged = function (o) {
        if (o && o.ok) TS.toast('已更新');
        loadGroups();
        if (App.page === TABS.fav) loadFavs();
    };

    function loadGroups() { TS.call('groupList'); }

    window.onGroupList = function (arr) {
        App.groups = arr || [];
        if (App.page === TABS.fav) loadFavs();
        if (App.page === SUBS.group) renderGroupManage();
    };

    // ==================== 分组管理 ====================

    ui.manageGroups = function () {
        goSub('group');
        TS.call('groupList');
    };

    function renderGroupManage() {
        var h = '<div class="chapter-hd"><span>全部分组</span>' +
                '<span class="rt" onclick="App.ui.newGroup()">+ 新建</span></div>';
        for (var i = 0; i < App.groups.length; i++) {
            var g = App.groups[i];
            h += '<div class="chapter" style="padding:14px">';
            h += '<div class="tt">' + TS.esc(g.name) + '</div>';
            h += '<div class="no" style="width:auto;margin-right:8px">' + (g.count || 0) + ' 本</div>';
            h += '<div class="dl" onclick="App.ui.renameGroup(' + g.id + ',\'' +
                 escAttr(g.name) + '\')">改名</div>';
            if (Number(g.id) !== 0) {
                h += '<div class="dl danger" onclick="App.ui.delGroup(' + g.id + ')">删除</div>';
            }
            h += '</div>';
        }
        h += '<div class="tip">删除分组不会删除收藏，组内书籍会自动移回默认分组。</div>';
        $('groupContent').innerHTML = h;
    }

    ui.renameGroup = function (id, old) {
        ui.inputSheet('新名称', old, function (n) {
            if (n && n.trim()) TS.call('groupRename', id, n.trim());
        });
    };

    ui.delGroup = function (id) {
        ui.confirmSheet('确定删除这个分组？组内收藏会移回默认分组。', function () {
            TS.call('groupDelete', id);
        });
    };

    // ==================== 历史 ====================

    function loadHistory() { TS.call('historyList', 300); }

    window.onHistoryList = function (arr) {
        var c = $('historyContent');
        arr = arr || [];
        App._histCache = arr;
        if (!arr.length) {
            c.innerHTML = '<div class="empty"><div class="big">🕘</div>还没有收听记录</div>';
            return;
        }
        var h = '<div class="chapter-hd"><span>收听历史</span>' +
                '<span class="rt" onclick="App.ui.clearHistory()">清空</span></div>';
        for (var i = 0; i < arr.length; i++) {
            var it = arr[i];
            var b = it.book || {};
            var cur = Math.round((it.lastPosMs || 0) / 1000);
            // ★ v3.7.11：去掉右上角的删除「✕」——改为**长按卡片**在按点弹菜单
            //   （删除 / 换源 / 搜索），触屏上更干净、也更不容易误删。
            h += '<div class="bookcard" data-hist-key="' + escAttr(b.key || '') +
                 '" onclick="App.ui.openHistoryItem(\'' + escAttr(b.key || '') + '\')">';
            h += '<img src="' + TS.esc(b.cover) + '" onerror="TS.imgFail(this)" loading="lazy">';
            h += '<div class="info">';
            h += '<div class="t" data-mq>' + TS.esc(b.title) + '</div>';
            h += '<div class="s accent">' + TS.esc(it.lastChapTitle || ('第' + it.lastIndex + '集')) +
                 (cur ? ' · 听到 ' + TS.fmt(cur) : '') + '</div>';
            h += '<div class="s">' + TS.esc(it.updatedText || '') +
                 (it.chapterCount ? ' · 共' + it.chapterCount + '集' : '') + '</div>';
            h += '</div>';
            h += '</div>';
        }
        c.innerHTML = h;
        bindHistMenu(c);
    };

    /** ★ v3.7.11：历史卡片长按菜单（删除 / 换源 / 搜索），与收藏页共用同一套 */
    function bindHistMenu(c) {
        App._bindLongPress(c, '.bookcard', function (el) {
            var key = el.getAttribute('data-hist-key') || '';
            var bk = histBookOf(key);
            var items = [];
            items.push({
                label: '删除这条记录', icon: '🗑', danger: true,
                pick: function () { ui.delHistory(key); }
            });
            if (bk) {
                items.push({
                    label: '换源播放', icon: '🔀',
                    pick: function () { if (ui.openSwap) ui.openSwap(bk); }
                });
                items.push({
                    label: '搜索这本书', icon: '🔍',
                    pick: function () { if (ui.searchBook) ui.searchBook(bk); }
                });
            }
            return items;
        });
    }

    /** 按 key 找历史里的书对象 */
    function histBookOf(key) {
        var arr = App._histCache || [];
        for (var i = 0; i < arr.length; i++) {
            if (arr[i] && arr[i].book && arr[i].book.key === key) return arr[i].book;
        }
        return null;
    }

    /**
     * ★ v3.7.11：用书名跳到搜索页并自动发起一次多源搜索。
     * 目标：在历史/收藏里长按一本书，能"搜同名书"来换源。
     */
    ui.searchBook = function (bk) {
        if (!bk || !bk.title) { TS.toast('没有可搜索的书名'); return; }
        goTab('search');
        var inp = $('kw');
        if (inp) inp.value = bk.title;
        App.searchFilter = 'all';
        App.searchBySource = {};
        App.searchDiag = [];
        App.searchExpanded = false;
        App.searchRunning = true;
        App.books = [];
        if ($('searchChips')) $('searchChips').classList.add('hide');
        if ($('searchHist')) $('searchHist').innerHTML = '';
        if ($('searchEmpty')) $('searchEmpty').style.display = 'none';
        if ($('searchResult')) {
            $('searchResult').innerHTML =
                '<div class="loading"><div class="spinner"></div>正在多源搜索…</div>';
        }
        TS.call('search', bk.title, 1);
    };

    /**
     * ★ v3.7.11：为一本书打开"换源"列表（同名书/其它源的候选）。
     *
     * <p>数据来自 {@code App.book.variants}（聚合搜索时同书不同源会挂在这里）；
     * 若没有 variants，就退化成"按书名再搜一次"。
     */
    ui.openSwap = function (bk) {
        if (!bk) return;
        var vars = bk.variants || [];
        var items = [];
        for (var i = 0; i < vars.length; i++) {
            var v = vars[i];
            if (!v || !v.source) continue;
            if (bk.source && v.source === bk.source) continue;
            items.push({
                label: (v.sourceName || v.source) + ' · ' + (v.artist || '未知主播'),
                icon: '🔀',
                pick: (function (vv) {
                    return function () { openBookObj(vv); };
                })(v)
            });
        }
        if (!items.length) {
            // 没有候选 → 直接按书名搜，用户从结果里挑一个源
            TS.toast('没有已记录的同名源，改为搜索同名书');
            ui.searchBook(bk);
            return;
        }
        items.unshift({
            label: '搜索同名书（更多来源）', icon: '🔍',
            pick: function () { ui.searchBook(bk); }
        });
        // 菜单挂在屏幕中央偏上（没有"按点"上下文时）
        var x = Math.round(window.innerWidth * 0.16);
        var y = Math.round(window.innerHeight * 0.22);
        ctxMenu(x, y, items);
    };

    ui.openHistoryItem = function (key) {
        var found = null, i, it;
        if (App._histCache) {
            for (i = 0; i < App._histCache.length; i++) {
                it = App._histCache[i];
                if (it.book && it.book.key === key) { found = it.book; break; }
            }
        }
        if (!found && App._homeContinue && App._homeContinue.book &&
            App._homeContinue.book.key === key) {
            found = App._homeContinue.book;
        }
        if (!found) { TS.toast('找不到这本书'); return; }
        openBookObj(found);
    };

    ui.delHistory = function (key) { TS.call('historyRemove', key); };

    window.onHistoryChanged = function () {
        TS.toast('已更新');
        if (App.page === TABS.history) loadHistory();
        if (App.page === TABS.home) loadHome();
    };

    ui.clearHistory = function () {
        ui.confirmSheet('确定清空全部收听历史？此操作不可恢复。', function () {
            TS.call('historyClear');
        });
    };

    // ==================== 播放 ====================

    ui.playChapter = function (i) {
        // ★ v2.6：冷启动竞态兜底 —— 章节表可能还没从 native 拉回来。
        //   原来这里直接 toast「章节数据无效」并 return，用户点了悬浮球
        //   只看到一个红字提示，播放永远起不来。现在改成挂起等 onDetail。
        if (!App.chapters || !App.chapters.length) {
            if (App.book) {
                App.pendingAutoPlay = true;
                App.pendingPlayIdx = i;
                setBuffering(true);
                TS.call('detailFromJson', JSON.stringify(App.book));
                clearTimeout(App._autoPlayTimer);
                App._autoPlayTimer = setTimeout(function () {
                    if (App.pendingAutoPlay) {
                        App.pendingAutoPlay = false;
                        setBuffering(false);
                        TS.toast('章节加载失败，请回首页重新选书');
                    }
                }, 2500);
            } else {
                TS.toast('章节数据无效');
            }
            return;
        }
        var ch = App.chapters[i];
        if (!ch || !App.book) { TS.toast('章节数据无效'); return; }

        App.chapIdx = i;
        showPlayerBar();
        setPTitle(ch.title || ('第' + (ch.index || i + 1) + '集'));
        setTime(0, 0);
        highlight(i);
        saveSession();

        // ★ 连整张章节表一起交给 native：
        //   它需要这份队列才能写播放历史、播完自动下一集。
        //   只传集号的话 native 列表是空的，两者都做不了。
        App.playing = true;
        // 取链要时间，先把"加载中"摆出来，用户知道点到了
        setBuffering(true);
        paintPlayIcon(true);
        TS.call('playQueue', JSON.stringify(App.book), JSON.stringify(App.chapters), i);
    };

    /**
     * 加载态总开关：主按钮外圈转圈 + 悬浮球图标脉冲。
     * 取链（playQueue/play）时开，onPlaying 或 onPlayError 时关。
     * 这两个入口必须成对，否则转圈会一直留着。
     */
    function setBuffering(on) {
        App.buffering = !!on;
        var btn = $('fPlayBtn');
        if (btn) btn.classList[on ? 'add' : 'remove']('buffering');
        var fab = $('pFabIcon');
        if (fab) fab.classList[on ? 'add' : 'remove']('buffering');
        syncPlayBtnAria();
    }
    ui.setBuffering = setBuffering;

    // ==================== 首页浏览结果缓存 ====================
    // 目的：切源 / 切分类 / 从别的页面回首页，都不要重新请求网络。
    // 两级：内存 Map（本次运行内秒回）+ localStorage（重启 App 后仍在，带 TTL）。

    var BROWSE_KEY = 'fx_browse_v1';
    var BROWSE_TTL = 6 * 60 * 60 * 1000;   // 6 小时过期
    var browseMem = {};                     // key -> {t, books}

    function bkey(sid, cat, page) {
        return (sid || '') + '|' + (cat || '') + '|' + (page || 1);
    }

    function browseSave(sid, cat, page, books) {
        var k = bkey(sid, cat, page);
        browseMem[k] = { t: Date.now(), books: books };
        try {
            var all = JSON.parse(localStorage.getItem(BROWSE_KEY) || '{}');
            all[k] = { t: Date.now(), books: books };
            // 只留最近 40 条，防止 localStorage 膨胀
            var keys = Object.keys(all);
            if (keys.length > 40) {
                keys.sort(function (a, b) { return (all[a].t || 0) - (all[b].t || 0); });
                for (var i = 0; i < keys.length - 40; i++) delete all[keys[i]];
            }
            localStorage.setItem(BROWSE_KEY, JSON.stringify(all));
        } catch (e) {}
    }

    /** force=true 时忽略缓存强制走网络 */
    function browseLoad(sid, cat, page, force) {
        if (force) return null;
        var k = bkey(sid, cat, page);
        var m = browseMem[k];
        if (m && m.books) return m.books;
        try {
            var all = JSON.parse(localStorage.getItem(BROWSE_KEY) || '{}');
            var e = all[k];
            if (e && e.books && (Date.now() - (e.t || 0)) < BROWSE_TTL) {
                browseMem[k] = e;
                return e.books;
            }
        } catch (er) {}
        return null;
    }

    function browseClearAll() {
        browseMem = {};
        try { localStorage.removeItem(BROWSE_KEY); } catch (e) {}
    }
    ui.browseClearAll = browseClearAll;

    /** 首页刷新：清缓存后重新拉取所有分类行（下拉刷新 / 设置里都会走这里） */
    ui.homeRefresh = function (silent) {
        browseClearAll();
        // 详情缓存一并清掉，保证"刷新"是真的重新联网取
        try { TS.call('clearDetailCache'); } catch (e) {}
        App.homePending = {};
        App.homePage = {};
        App.homeBooks = {};
        resetHomePaging();                       // ★ v3.7.6：刷新 = 从第 1 页重新开始
        if (!silent) TS.toast('已刷新');
        paintHomeSources(true);
    };

    // ---------- 下拉刷新（替换右上角刷新按钮） ----------

    var PR_THRESHOLD = 64;      // 拉过这个距离松手才触发
    var PR_MAX = 96;            // 最大下拉距离，再拉也不动（有阻尼感）
    var _prBound = false;

    function bindPullRefresh() {
        if (_prBound) return;
        var wrap = $('homeContentWrap');
        var tip = $('pullRefresh');
        var txt = $('pullText');
        if (!wrap || !tip) return;
        _prBound = true;

        var y0 = 0, dy = 0, tracking = false, busy = false;

        function reset(animate) {
            tip.classList.remove('pulling');
            tip.style.height = '0px';
            if (animate) {
                tip.classList.remove('ready', 'refreshing');
                if (txt) txt.textContent = '下拉刷新';
            }
            dy = 0;
        }

        wrap.addEventListener('touchstart', function (e) {
            // 只有内容滚到顶才允许下拉，否则会和正常滚动打架
            if (busy || e.touches.length !== 1) return;
            if (wrap.scrollTop > 0) return;
            y0 = e.touches[0].clientY;
            dy = 0;
            tracking = true;
            tip.classList.add('pulling');
        }, { passive: true });

        wrap.addEventListener('touchmove', function (e) {
            if (!tracking || busy) return;
            dy = e.touches[0].clientY - y0;
            if (dy <= 0) {   // 往上推，交回正常滚动
                tip.style.height = '0px';
                return;
            }
            // 阻尼：越拉越沉，最大不超过 PR_MAX
            var pulled = Math.min(PR_MAX, dy * 0.5);
            tip.style.height = pulled + 'px';
            var ready = pulled >= PR_THRESHOLD;
            tip.classList[ready ? 'add' : 'remove']('ready');
            if (txt) txt.textContent = ready ? '松手刷新' : '下拉刷新';
            // 阻止页面跟着一起滚（仅在下拉方向）
            if (e.cancelable) e.preventDefault();
        }, { passive: false });

        wrap.addEventListener('touchend', function () {
            if (!tracking || busy) return;
            tracking = false;
            var pulled = Math.min(PR_MAX, dy * 0.5);
            tip.classList.remove('pulling');
            if (pulled >= PR_THRESHOLD) {
                // 触发刷新：指示器回弹到 44px 并持续转圈
                busy = true;
                tip.classList.add('refreshing');
                tip.classList.remove('ready');
                tip.style.height = '44px';
                if (txt) txt.textContent = '正在刷新…';
                ui.homeRefresh(true);
                setTimeout(function () {
                    busy = false;
                    tip.classList.remove('refreshing');
                    tip.style.height = '0px';
                    if (txt) txt.textContent = '下拉刷新';
                    TS.toast('已刷新');
                }, 900);
            } else {
                reset(true);
            }
        }, { passive: true });

        // 触摸被系统打断（来电等）也要复位
        wrap.addEventListener('touchcancel', function () {
            tracking = false;
            if (!busy) reset(true);
        }, { passive: true });
    }
    ui.bindPullRefresh = bindPullRefresh;

    // ==================== 会话保持 ====================
    // 让「关掉 App 再打开」「点悬浮球」都能回到上次听的地方。
    // localStorage 存一份轻量会话（书 + 集号 + 进度），native 侧 resumeSession
    // 也有一份（更权威，含完整播放列表）。

    var SESSION_KEY = 'fx_session_v1';

    function saveSession() {
        try {
            if (!App.book || App.chapIdx < 0) return;
            var ch = App.chapters[App.chapIdx] || {};
            localStorage.setItem(SESSION_KEY, JSON.stringify({
                book: App.book,
                index: App.chapIdx,
                chapNo: ch.index || (App.chapIdx + 1),
                posMs: Math.round((App.cur || 0) * 1000),
                chapTitle: ch.title || '',
                sources: App.chapters.length ? App.chapters.map(function (c) {
                    return { title: c.title, url: c.url, index: c.index };
                }) : []
            }));
        } catch (e) {}
    }

    function loadSession() {
        try {
            var raw = localStorage.getItem(SESSION_KEY);
            if (!raw) return null;
            var o = JSON.parse(raw);
            return (o && o.book && o.book.title) ? o : null;
        } catch (e) { return null; }
    }
    ui.saveSession = saveSession;

    /**
     * 把会话灌回 App 状态，并让悬浮球出现。
     * ★ v2.6：新增 `needFetch` 标记 —— 冷启动时本地会话只有书 + 集号 +
     *   章节表（可能为空），native 侧播放器是全新的、没有队列也没取链。
     *   后面 openFull 会据此决定是「resume 一个已有 player」还是「重新取链播放」。
     */
    function applySession(o) {
        if (!o || !o.book) return false;
        App.book = o.book;
        App.chapters = o.sources && o.sources.length ? o.sources : [];
        App.chapIdx = Math.max(0, o.index || 0);
        App.sessionResume = true;
        // ★ v2.6：本地恢复的会话一律需要重新取链（native player 是空的）
        App.needFetch = true;
        var ch = App.chapters[App.chapIdx];
        setTime(0, 0);
        if (ch) setPTitle(ch.title || '');
        else if (o.chapTitle) setPTitle(o.chapTitle);
        if ($('pCover') && o.book.cover) $('pCover').src = o.book.cover;
        showPlayerBar();
        return true;
    }

    /**
     * native 回传的上次会话。优先用它（含完整章节表），
     * 没拿到就退回本地 localStorage 那份。
     */
    window.onResumeSession = function (o) {
        if (o && o.has && o.book) {
            // ★ v2.6 时序保护：`resumeSession` 是异步的（native 跑在单线程池里），
            //   冷启动时它可能比用户点击悬浮球**更晚**返回。若用户已经点开
            //   播放页在等播放了，就别再用会话数据覆盖他当前的状态，
            //   否则会把 needFetch/pendingAutoPlay 冲掉 → 播放又起不来。
            if (App.userActed) return;

            var idx = Math.max(0, (o.index || 1) - 1);
            App.book = o.book;
            App.chapIdx = idx;
            App.sessionResume = true;
            // ★ v2.6 关键：native 侧播放器里**还有活的这本书**（比如 Activity 重建、
            //   退到后台又回来），此时 player 非空、直链也在，resume 即可。
            //   反之（进程被杀、冷启动）native 只从历史里捞到书的元信息，
            //   player 是 null、没有队列 —— 必须重新取链，否则 play 会静默失败。
            App.needFetch = !o.live;
            showPlayerBar();
            // 章节表要单独拉（native 只回书和集号）
            $('pTitle').textContent = o.chapTitle || ('第' + (o.index || 1) + '集');
            if ($('pCover') && o.book.cover) $('pCover').src = o.book.cover;
            // 拉完整章节表，这样升降集/选集才可用
            TS.call('detailFromJson', JSON.stringify(o.book));
            return;
        }
        // 退回本地会话
        var s = loadSession();
        if (s && !App.userActed) applySession(s);
    };

    /** 悬浮球点击：有会话就续播，没会话给明确提示 */
    ui.tapBall = function () {
        // ★ v2.6：标记用户已主动操作，阻止迟到的 resumeSession 覆盖当前状态
        App.userActed = true;
        if (App.chapIdx < 0 || !App.book) {
            var s = loadSession();
            if (s && applySession(s)) { /* 已恢复，继续走下面 */ }
        }
        if (App.chapIdx < 0 || !App.book) {
            // 置灰态：明确告诉用户为什么点了没反应
            showPlayerBar();
            TS.toast('还没有播放记录，去首页选一本吧');
            return;
        }
        // 没有章节表就先补上，否则全屏页的选集/升降集不能用
        if (!App.chapters.length) {
            TS.call('detailFromJson', JSON.stringify(App.book));
        }
        ui.openFull();
    };

    /** 有内容 → 高亮常驻；没内容 → 灰色常驻（球本身始终显示） */
    function showPlayerBar() {
        var el = $('player');
        if (!el) return;
        el.classList.add('show');
        // 有书 + 有集号才算"有内容"，否则置灰
        if (App.book && App.chapIdx >= 0) el.classList.remove('empty');
        else el.classList.add('empty');
        document.body.classList.add('has-player');
    }

    /** 初始/兜底：把球设成常驻并同步灰态 */
    function syncBall() {
        var el = $('player');
        if (!el) return;
        el.classList.add('show');
        el.classList[(!App.book || App.chapIdx < 0) ? 'add' : 'remove']('empty');
    }
    ui.syncBall = syncBall;

    function setPTitle(t) {
        if ($('pTitle')) $('pTitle').textContent = t;
        if ($('fullTitle')) $('fullTitle').textContent = t;
        if ($('pCover') && App.book) $('pCover').src = App.book.cover || '';
        // 换书时背景跟着换（同一本书不会重复算主色）
        if (App.book) paintFullBg(App.book);
        // 集数/主播/书名跟着当前集刷新（切集时也走这里）
        paintFullInfo();
    }

    /** 播放条 / 全屏页的「书源名称」渲染 */
    function paintPlaySource() {
        var nm = App.playSourceName || '';
        if ($('pSrc')) $('pSrc').textContent = nm ? ('来源：' + nm) : '';
        if ($('player')) $('player').title = nm ? ('正在播放 · 来源：' + nm) : '正在播放';
        // ★ v2.0：书源名改到右上角胶囊展示（原来是封面下方一行小字）
        var tag = $('fullSourceTag');
        if (tag) {
            tag.textContent = nm;
            tag.classList[nm ? 'add' : 'remove']('show');
        }
    }

    /** 全屏页信息区：书名 / 集数「(N/M)」/ 主播名 */
    function paintFullInfo() {
        var b = App.book || {};
        // ★ v2.4：书名超长时跑马灯。传 b.title 是权威文本（数据源变了），
        //   不能只依赖 DOM 缓存，否则切书后仍滚上一本的书名。
        if (window.TS && TS.marquee) TS.marquee($('fullBook'), b.title || '');
        else if ($('fullBook')) $('fullBook').textContent = b.title || '';

        // 集数：以章节总数为总数，当前下标 +1 为当前集号
        var total = App.chapters ? App.chapters.length : 0;
        var idx = App.chapIdx >= 0 ? App.chapIdx : 0;
        var ch = (App.chapters && App.chapters[idx]) || {};
        var ep = $('fullEp');
        if (ep) {
            if (total > 0) {
                // 章节自带 index（有些源集号不连续）时优先用它
                var no = ch.index || (idx + 1);
                // ★ v3.7.11：集数「括号」重做。
                //
                //   用户反馈："当前集数比和外边括号不协调，调整下分布"。
                //   原写法 <i>（</i><b>no</b><s>/</s><em>total</em><i>）</i> 有两点别扭：
                //     ① 全角括号（.）自带左右各半个字宽的空隙，数字被"推开"，
                //        整体显得松散、还比右侧主播 pill 高一截；
                //     ② 括号、数字、斜杠共 5 个节点挤在一行，基线对齐后
                //        括号偏下、数字偏上，看着"没坐齐"。
                //
                //   现在改成：**细排版紧凑结构** —— 括号用半角并降低字重/透明度，
                //   当前集与总数用同一字号（数字视觉等宽），斜杠居中做分隔，
                //   再配一个"进度比例"的底色填充（当前/总数），
                //   信息层级清晰、分布也均匀： [ 12 / 340 ]（左对齐数字块）。
                var pct = Math.max(0, Math.min(100, Math.round((idx + 1) * 100 / total)));
                ep.innerHTML =
                    '<span class="fep-par">(</span>' +
                    '<span class="fep-no">' + no + '</span>' +
                    '<span class="fep-sep">/</span>' +
                    '<span class="fep-tot">' + total + '</span>' +
                    '<span class="fep-par">)</span>' +
                    '<span class="fep-bar" style="--p:' + pct + '%"></span>';
                ep.style.display = '';
                ep.title = '第 ' + no + ' 集 / 共 ' + total + ' 集（' + pct + '%）';
            } else {
                ep.textContent = '';
                ep.style.display = 'none';
            }
        }
        var ar = $('fullArtist');
        if (ar) ar.textContent = b.artist || '';
    }
    ui.paintFullInfo = paintFullInfo;
    ui.paintPlaySource = paintPlaySource;

    /** 圆球外圈进度：周长 2π×46 ≈ 289 */
    var RING_LEN = 289;

    /**
     * ★ v3.7.5：把「当前时长」气泡移到进度条对应位置。
     *
     * <p>原来 #fCur 是 .ftimes 里左侧的一个静态 span，无论播到哪儿都钉在左边，
     * 跟进度条完全脱钩。现在改成跟着播放百分比水平移动：
     *  - left 直接用百分比，但做 8% 边距收敛 —— 否则 0%/100% 时气泡会有一半
     *    被容器裁掉（气泡自身还有 translateX(-50%) 居中）。
     *  - 拖动期间给 .dragging 去掉 transition，做到跟手不拖影。
     */
    var CUR_PAD = 8;   // 百分比边距（%）
    function paintCurPos(p, draggingNow) {
        var el = $('fCur');
        if (!el) return;
        p = Math.max(0, Math.min(1, p || 0));
        var pct = CUR_PAD + p * (100 - CUR_PAD * 2);
        el.style.left = pct.toFixed(2) + '%';
        el.classList[draggingNow ? 'add' : 'remove']('dragging');
    }
    ui.paintCurPos = paintCurPos;

    function setTime(cur, dur) {
        App.cur = cur; App.dur = dur;
        // 悬浮球：环形进度
        if (dur > 0 && $('ballRing')) {
            var pct = Math.max(0, Math.min(1, cur / dur));
            $('ballRing').style.strokeDashoffset = (RING_LEN * (1 - pct)).toFixed(1);
        }
        if (!draggingFull && $('fCur')) {
            $('fCur').textContent = TS.fmt(cur);
            $('fDur').textContent = TS.fmt(dur);
            // ★ v3.7.5：时长气泡跟着进度移动
            paintCurPos(dur > 0 ? (cur / dur) : 0, false);
            if (dur > 0 && $('fFill')) {
                // ★ v2.0：与拖动逻辑统一，用 scaleX 驱动填充（纯合成，不重排）
                $('fFill').style.transform =
                    'scaleX(' + Math.max(0, Math.min(1, cur / dur)).toFixed(4) + ')';
            }
        }
    }

    function highlight(i) {
        var all = document.querySelectorAll('.chapter');
        for (var k = 0; k < all.length; k++) all[k].classList.remove('playing');
        var el = $('ch' + i);
        if (el) el.classList.add('playing');
    }

    window.onPlaying = function (o) {
        // 取链成功 → 收掉加载转圈
        setBuffering(false);
        // ★ 图标真源只有一个：native 说在播就是在播
        App.playing = true;
        paintPlayIcon(true);
        if (o && o.title) setPTitle(o.title);
        // 连播时同步集号，避免高亮/上下一集按钮指向旧的集
        if (o && typeof o.index === 'number' && o.index > 0) {
            var want = o.index - 1;
            if (want >= 0 && want < App.chapters.length) {
                App.chapIdx = want;
                highlight(want);
                var ch = App.chapters[want];
                if (ch) setPTitle(ch.title || ('第' + o.index + '集'));
            }
        }
        // 实际使用的源（可能已自动换源）
        if (o) {
            App.playSource = o.source || (App.book && App.book.source) || '';
            App.playSourceName = o.sourceName || sourceName(App.playSource);
            paintPlaySource();
        }
        if (o && o.startMs > 5000) {
            TS.toast('从 ' + TS.fmt(Math.round(o.startMs / 1000)) + ' 处继续');
        }
        saveSession();
    };

    window.onPlayError = function () {
        setBuffering(false);
        App.playing = false;
        paintPlayIcon(false);
        TS.toast('取链失败，换一集或换个源试试');
    };

    window.onPlayState = function (p) {
        // native 推来的真值，直接采用，不做任何本地推断
        App.playing = !!p;
        // 拿到真值就说明 native 那边已经有结论了（在播 or 已停），
        // 不管哪种都不该再转圈。true 时顺带收掉加载态。
        if (App.playing) setBuffering(false);
        paintPlayIcon(App.playing);
    };

    /**
     * ★ v2.6：native 说「我没有可复用的播放器，resume 不了」。
     *
     * <p>触发场景：App 被系统杀掉后重新打开，点悬浮球直接播放 ——
     * 此时原生 PlayerCore 是全新的（player == null、没有队列、没取链），
     * `play()` 只能失败。这里统一改走 `playChapter` 做一次完整取链，
     * 把这次播放救回来。如果章节表还没就绪，就挂起等 onDetail。
     */
    window.onPlayNeedFetch = function () {
        if (!App.book || App.chapIdx < 0) return;
        App.needFetch = true;
        App.pendingAutoPlay = true;
        resumeAndPlay();
    };

    window.onProgress = function (cur, dur) { setTime(cur || 0, dur || 0); saveSession(); };

    window.onEnded = function () {
        // 只有 native 判定"整本最后一集也播完了"才会走到这里
        setBuffering(false);
        App.playing = false;
        paintPlayIcon(false);
    };

    /** 图标是播放/暂停的唯一绘制口：双竖条=在播，三角=暂停/未播 */
    function paintPlayIcon(p) {
        var path = p ? '<path d="M6 5h4v14H6zM14 5h4v14h-4z"/>' : '<path d="M8 5v14l11-7z"/>';
        if ($('playIcon')) $('playIcon').innerHTML = path;
        if ($('fPlayIcon')) $('fPlayIcon').innerHTML = path;
        // ★ v3.7.5：封面旋转统一在这里切。
        //   圆盘用 animation-play-state 控制（见 style.css），
        //   增删 .playing 只是"恢复/暂停"，角度不会重置 —— 暂停就停在当前角度。
        if ($('fDisc')) $('fDisc').classList[p ? 'add' : 'remove']('playing');
        //   悬浮球同理：球体加 .playing，由 CSS 驱动 #pCover 转动
        if ($('player')) $('player').classList[p ? 'add' : 'remove']('playing');
        // 顺带同步 aria，方便无障碍与调试
        var bar = $('playIcon');
        if (bar && bar.parentNode) bar.parentNode.setAttribute('aria-label', p ? '暂停' : '播放');
    }

    /** 主播放按钮的 aria 也标一下加载态，读屏用户能听出"正在缓冲" */
    function syncPlayBtnAria() {
        var btn = $('fPlayBtn');
        if (btn) {
            btn.setAttribute('aria-label',
                App.buffering ? '正在加载' : (App.playing ? '暂停' : '播放'));
        }
    }

    /** 切页/回前台时主动拉一次真值，防止错过事件导致图标残留 */
    ui.syncPlayState = function () { TS.call('playState'); };

    ui.toggle = function () {
        if (App.chapIdx < 0) { TS.toast('还没有播放内容'); return; }
        TS.call('toggle');
    };

    ui.next = function () {
        if (!App.chapters.length) return;
        if (App.chapIdx + 1 >= App.chapters.length) { TS.toast('已经是最后一集'); return; }
        ui.playChapter(App.chapIdx + 1);
    };

    ui.prev = function () {
        if (!App.chapters.length) return;
        if (App.chapIdx <= 0) { TS.toast('已经是第一集'); return; }
        ui.playChapter(App.chapIdx - 1);
    };

    // ==================== 全屏播放页 ====================

    /**
     * 背景自动吸取封面虚化填充。
     *
     * 两层做法：
     * 1) 把封面同图丢给 .fbg-blur（CSS 里放大 + 高斯模糊），
     *    这样背景和封面永远同一张图、同一套色调，不需要额外请求。
     * 2) 再用 canvas 把封面缩到 1×1 采样**平均色**，铺成渐变叠加，
     *    模糊图的色偏 / 纯色封面都能得到稳定的背景色（不会出现一片灰）。
     *
     * 跨域或本地图失败时静默降级：只留模糊图（或纯主题底色），不报错。
     */
    var _bgToneCache = {};

    function paintFullBg(b) {
        var img = $('fbgImg');
        var url = (b && b.cover) || '';
        if (img) {
            if (img.getAttribute('src') !== url) {
                img.classList.remove('fail');
                if (url) img.src = url;
                else { img.removeAttribute('src'); img.classList.add('fail'); }
            }
        }
        paintBgTone(url);
    }

    /** 采样封面主色 → 铺一层很淡的同色渐变，让背景"有那本书的味道" */
    function paintBgTone(url) {
        var tint = $('fbgTint');
        if (!tint) return;
        if (!url) { tint.style.background = ''; return; }

        // 同一个封面只算一次
        if (_bgToneCache[url]) { applyTone(tint, _bgToneCache[url]); return; }

        var im = new Image();
        // 允许跨域才读得到像素；不允许就走 onerror 降级
        im.crossOrigin = 'anonymous';
        im.onload = function () {
            var rgb = null;
            try {
                var cv = document.createElement('canvas');
                cv.width = cv.height = 1;
                var ctx = cv.getContext('2d');
                ctx.drawImage(im, 0, 0, 1, 1);
                var d = ctx.getImageData(0, 0, 1, 1).data;
                rgb = [d[0], d[1], d[2]];
            } catch (e) {
                rgb = null;   // 跨域被拦 / canvas 不可用
            }
            if (rgb) {
                _bgToneCache[url] = rgb;
                applyTone(tint, rgb);
            }
        };
        im.onerror = function () { /* 降级：保留 CSS 默认叠色 */ };
        im.src = url;
    }

    function applyTone(tint, rgb) {
        var dark = document.documentElement.classList.contains('dark');
        var r = rgb[0], g = rgb[1], b2 = rgb[2];
        // 浅色模式：把主色**提亮**（向白混合），保证深色封面上标题仍可读；
        // 深色模式：把主色**压暗**（向黑混合），避免背景比正文还亮。
        // ★ v3.7.4：alpha 整体加厚（原来浅色 .5/.7/.9、深色 .55/.74/.9 太薄），
        //   必须与 style.css 里 .fbg-tint 的默认值同步，否则这里一算就把加厚覆盖回去。
        function lift(v) { return Math.round(v + (255 - v) * 0.66); }
        function sink(v) { return Math.round(v * 0.42); }
        var a1, a2, a3;
        if (dark) {
            r = sink(r); g = sink(g); b2 = sink(b2);
            a1 = .64; a2 = .80; a3 = .92;
        } else {
            r = lift(r); g = lift(g); b2 = lift(b2);
            a1 = .68; a2 = .82; a3 = .95;
        }
        tint.style.background = 'linear-gradient(180deg,' +
            'rgba(' + r + ',' + g + ',' + b2 + ',' + a1 + ') 0%,' +
            'rgba(' + r + ',' + g + ',' + b2 + ',' + a2 + ') 46%,' +
            'rgba(' + r + ',' + g + ',' + b2 + ',' + a3 + ') 100%)';
    }

    ui.openFull = function () {
        if (App.chapIdx < 0 || !App.book) { TS.toast('还没有播放内容'); return; }
        App.fullShow = true;
        document.body.classList.add('full-show');
        goSub('full');
        var b = App.book || {};
        if ($('fullCover')) {
            $('fullCover').classList.remove('fail');
            $('fullCover').src = b.cover || '';
        }
        paintFullBg(b);
        if (window.TS && TS.marquee) TS.marquee($('fullBook'), b.title || '');
        else if ($('fullBook')) $('fullBook').textContent = b.title || '';
        paintPlaySource();
        paintFullInfo();
        var ch = App.chapters[App.chapIdx];
        setPTitle(ch ? (ch.title || '') : '');
        setTime(App.cur, App.dur);
        paintSpeed();
        paintGain();
        paintFullFav();
        paintSleepBtn();

        // 每次进播放页，倍速/音量面板与「更多」下拉都回到收起态
        // （避免上次展开的状态残留）
        ui.closeMore();
        var sp = $('speedPanel');
        if (sp) sp.classList.add('hide');
        var gp = $('gainPanel');
        if (gp) gp.classList.add('hide');

        // ★ v3.1：向原生要一次真实增益值，校正滑杆位置
        //   （进程被杀后重进播放页时，本地 App.gain 可能是默认值）
        TS.call('getGain');

        // 转圈状态跟着真实状态走：进来时若还在取链，转圈继续
        setBuffering(!!App.buffering);

        // ★ v2.6 修复「冷启动后点悬浮球无法播放」：
        //   原来这里无条件 TS.call('play') → 原生 playIfPaused()，
        //   而冷启动时 native 的 player 是 null（进程被杀过），
        //   playIfPaused 里 `if (player == null) return;` 会**静默返回** ——
        //   播放不启动、转圈永远转，且没有任何错误提示。
        //
        //   正确的判定是问一句：native 侧到底有没有「活的、已取链的」播放器？
        //   · 有（Activity 重建 / 后台切回）→ resume 即可，秒续播
        //   · 没有（冷启动）→ 必须走 playChapter 完整取链，否则必失败
        //     注意冷启动时章节表可能还没回来，所以要么等 onDetail，
        //     要么用 native 的队列兜底（playQueue 会自己拉章节表）。
        if (App.playOnFull !== false && !App.playing && App.chapIdx >= 0) {
            if (App.needFetch) {
                // 需要重新取链：走统一的播放入口（内部会处理章节表未就绪的情况）
                App.pendingAutoPlay = true;
                resumeAndPlay();
            } else {
                setBuffering(true);
                TS.call('play');
            }
        }
        // 进播放页主动校正一次图标（同时会同步封面旋转/停止）
        ui.syncPlayState();
    };

    /**
     * 冷启动续播：确保拿到章节表后走完整的「取链 → 播放」流程。
     *
     * <p>为什么要单独一个函数：冷启动时 `onResumeSession` 与 `onDetail` 都是
     * 异步的，时序不确定。如果章节表还没回来就调 playChapter，会因为
     * `App.chapters[i]` 为空直接 toast「章节数据无效」。
     * 这里统一挂到 `App.pendingAutoPlay` 上，由 onDetail 回调来兑现。
     */
    function resumeAndPlay() {
        if (!App.book) return;
        if (App.chapters && App.chapters.length) {
            // 章节表已就绪：直接按当前集号取链播放
            App.pendingAutoPlay = false;
            ui.playChapter(App.chapIdx);
            return;
        }
        // 章节表还没到：拉一次详情，onDetail 里会检查 pendingAutoPlay 再兑现
        setBuffering(true);
        TS.call('detailFromJson', JSON.stringify(App.book));
        // 兜底：若详情拉取异常缓慢（比如源失效），2.5 秒后仍没接到章节表，
        //       就把待播标记清掉并给用户明确提示，避免"转圈永远转"。
        clearTimeout(App._autoPlayTimer);
        App._autoPlayTimer = setTimeout(function () {
            if (App.pendingAutoPlay) {
                App.pendingAutoPlay = false;
                setBuffering(false);
                TS.toast('续播失败，请回首页重新选书');
            }
        }, 2500);
    }

    ui.closeFull = function () {
        App.fullShow = false;
        document.body.classList.remove('full-show');
        if (App.stack.length > 0) backToSub(App.stack.pop());
        else { showOne(TABS[App.tab], 'back'); updateTabbar(); }
    };

    function paintFullFav() {
        var el = $('fullFav');
        if (!el || !App.book) return;
        var on = !!App.favKeys[App.book.key];
        el.textContent = on ? '★' : '☆';
        el.classList[on ? 'add' : 'remove']('on');
    }

    ui.toggleFavCurrent = function () {
        if (App.book) ui.toggleFavJson(JSON.stringify(App.book));
    };

    ui.jump = function (delta) {
        var t = Math.min(App.dur, Math.max(0, App.cur + delta));
        TS.call('seek', t);
        setTime(t, App.dur);
        TS.toast((delta > 0 ? '快进 ' : '快退 ') + Math.abs(delta) + ' 秒');
    };

    // ==================== 选集（播放页/全屏页通用） ====================

    /**
     * 底部弹出章节列表。全屏页里也能用，不用退回详情页。
     * 打开时自动把当前集滚到可视区中间。
     *
     * ★ v3.7.10：头部加两个「下拉按钮」—— 列数（1/2/3 列）与区间（200 集/段）。
     *   与详情页共用同一份偏好（App.chapView / fx_chap_view_v1），在这边改了、
     *   回详情页也是一样的列数/段号。按钮不常驻展开，点一下弹出菜单选择，
     *   选完自动收起 —— 不占弹层空间。
     */
    ui.openChapterList = function () {
        var chs = App.chapters || [];
        if (!chs.length) { TS.toast('还没有章节数据'); return; }
        loadChapView();                          // 与详情页同一份偏好
        // 当前集若不在所选区间内，自动切到它所在的段（否则用户打开选集找不到正在播的集）
        var curIdx = (typeof App.chapIdx === 'number') ? App.chapIdx : 0;
        var win0 = chapWindow(chs.length);
        if (curIdx < win0.start || curIdx >= win0.end) {
            App.chapView.seg = Math.floor(curIdx / CHAP_SEG);
        }
        ui.closeChapterList();

        var h = '<div class="sheet-mask" onclick="App.ui.closeChapterList()">';
        h += '<div class="chsheet" onclick="event.stopPropagation()">';
        h += '<div class="chsheet-hd">' +
             '<span>选集</span>' +
             '<span class="chsheet-sub">共 ' + chs.length + ' 集</span>' +
             '<span class="chsheet-tools">' +
             '<button class="chs-tool" id="chsColsBtn" onclick="App.ui.chsToggleCols(event)"></button>' +
             (chapWindow(chs.length).segCount > 1
                 ? '<button class="chs-tool" id="chsSegBtn" onclick="App.ui.chsToggleSeg(event)"></button>'
                 : '') +
             '<span class="chsheet-x" onclick="App.ui.closeChapterList()">&#10005;</span>' +
             '</span>' +
             '</div>';
        h += '<div class="chsheet-list" id="chList">';
        h += chsListHtml(chs);
        h += '</div></div></div>';

        var d = document.createElement('div');
        d.id = 'chSheetWrap';
        d.innerHTML = h;
        document.body.appendChild(d);

        paintChsToolBtns();                      // 按钮上显示当前列数/段号

        // 打开时按缓存状态刷一遍图标
        if (App.book) TS.call('cacheStatus', JSON.stringify(App.book));
        paintAllDlIcons();

        // 当前集滚到中间，方便看前后文
        setTimeout(function () {
            var cur = document.querySelector('#chList .chitem.on');
            var list = $('chList');
            if (cur && list) {
                list.scrollTop = Math.max(0, cur.offsetTop - list.clientHeight / 2 + cur.clientHeight / 2);
            }
        }, 30);
    };

    /** ★ v3.7.10：选集弹层的章节条目 HTML（含当前区间裁剪与分列容器） */
    function chsListHtml(chs) {
        var win = chapWindow(chs.length);
        var h = '<div class="chlist-inner cols-' + chapCols() + '" id="chListInner">';
        for (var i = win.start; i < win.end; i++) {
            var ch = chs[i];
            var no = ch.index || (i + 1);
            var on = (i === App.chapIdx);
            h += '<div class="chitem' + (on ? ' on' : '') +
                 '" onclick="App.ui.pickChapter(' + i + ')">' +
                 '<span class="chno">' + no + '</span>' +
                 '<span class="chtt">' + TS.esc(ch.title || ('第' + no + '集')) + '</span>' +
                 '<span class="chdl" data-dl="' + no +
                 '" onclick="event.stopPropagation();App.ui.dlChapter(' + no + ')"></span>' +
                 (on ? '<span class="chplay">▶</span>' : '') +
                 '</div>';
        }
        h += '</div>';
        return h;
    }

    /** ★ v3.7.10：重画选集列表（切列数/切段后调用），并保持滚动位置策略 */
    function repaintChsList(keepCur) {
        var list = $('chList');
        if (!list) return;
        list.innerHTML = chsListHtml(App.chapters || []);
        paintAllDlIcons();
        // 切段后把当前集（或该段第一集）滚到顶部可见处；切列数保持原滚动
        if (!keepCur) {
            list.scrollTop = 0;
            var cur = document.querySelector('#chList .chitem.on');
            if (cur) list.scrollTop = Math.max(0, cur.offsetTop - 60);
        }
    }

    /** ★ v3.7.10：刷新两个工具按钮上的文字（当前列数 / 当前段范围） */
    function paintChsToolBtns() {
        var chs = App.chapters || [];
        var cb = $('chsColsBtn'), sb = $('chsSegBtn');
        if (cb) cb.textContent = chapCols() + ' 列 ▾';
        if (sb) sb.textContent = chapSegLabel(chapSeg(), chs.length) + ' ▾';
    }

    /** ★ v3.7.10：通用下拉菜单（挂在指定按钮正下方，点其它处自动收起） */
    function chsDropdown(anchorEl, items) {
        chsCloseDropdown();
        var m = document.createElement('div');
        m.id = 'chsDropdown';
        m.className = 'chs-dd';
        var h = '';
        for (var i = 0; i < items.length; i++) {
            var it = items[i];
            h += '<div class="chs-dd-item' + (it.on ? ' on' : '') +
                 '" data-i="' + i + '">' + it.label + (it.on ? ' ✓' : '') + '</div>';
        }
        m.innerHTML = h;
        document.body.appendChild(m);
        // 定位：按钮正下方，右对齐（按钮都在弹层右上角）
        var r = anchorEl.getBoundingClientRect();
        m.style.top = (r.bottom + 6) + 'px';
        m.style.right = Math.max(6, window.innerWidth - r.right) + 'px';
        // 点选
        m.addEventListener('click', function (e) {
            var t = e.target.closest('.chs-dd-item');
            if (!t) return;
            var idx = parseInt(t.getAttribute('data-i'), 10);
            chsCloseDropdown();
            if (items[idx] && items[idx].pick) items[idx].pick();
        });
        // 点别处收起
        setTimeout(function () {
            document.addEventListener('click', chsCloseDropdown, { once: true });
        }, 0);
    }

    function chsCloseDropdown() {
        var old = $('chsDropdown');
        if (old && old.parentNode) old.parentNode.removeChild(old);
    }

    // ==================== ★ v3.7.11：长按弹出的上下文菜单 ====================
    // 需求：历史/收藏的删除不再用卡片右上角的「✕」，改成**长按卡片**
    //       后在**长按的位置**弹出一个菜单（删除 / 换源 / 搜索 …）。
    // 这里做成通用件：传 (x, y, items)，items=[{label, icon, danger, pick}]。

    var _ctxTimer = null;      // 长按计时器
    var _ctxFired = false;     // 本次是否已触发长按（用于吞掉随后的 click）
    var _ctxStart = null;      // 按下位置

    /**
     * 在屏幕坐标 (x, y) 处弹出菜单，自动贴边避免超出视口。
     */
    function ctxMenu(x, y, items) {
        ctxClose();
        if (!items || !items.length) return;
        var m = document.createElement('div');
        m.id = 'ctxMenu';
        m.className = 'ctx-menu';
        var h = '';
        for (var i = 0; i < items.length; i++) {
            var it = items[i];
            h += '<div class="ctx-item' + (it.danger ? ' danger' : '') +
                 '" data-i="' + i + '">' +
                 (it.icon ? '<span class="ci-ico">' + it.icon + '</span>' : '') +
                 '<span class="ci-tx">' + it.label + '</span></div>';
        }
        m.innerHTML = h;
        document.body.appendChild(m);

        // 定位：以按点为左上角；超出视口则往回收
        var r = m.getBoundingClientRect();
        var vw = window.innerWidth, vh = window.innerHeight;
        var left = x, top = y;
        if (left + r.width + 8 > vw) left = Math.max(8, vw - r.width - 8);
        if (top + r.height + 8 > vh) top = Math.max(8, vh - r.height - 8);
        m.style.left = left + 'px';
        m.style.top = top + 'px';

        // 点选
        m.addEventListener('click', function (e) {
            var t = e.target.closest ? e.target.closest('.ctx-item') : null;
            if (!t) return;
            var idx = parseInt(t.getAttribute('data-i'), 10);
            ctxClose();
            if (items[idx] && items[idx].pick) items[idx].pick();
        });
        // 点别处 / 滚动 → 收起
        setTimeout(function () {
            document.addEventListener('click', ctxClose, { once: true });
            document.addEventListener('touchstart', ctxClose, { once: true, passive: true });
        }, 0);
    }

    function ctxClose() {
        var old = $('ctxMenu');
        if (old && old.parentNode) old.parentNode.removeChild(old);
    }
    App._ctxClose = ctxClose;

    /**
     * 给一批元素挂"长按 → 在按点弹菜单"。用**事件委托**，只绑一次。
     *
     * @param {Element} root    容器（在其上委托）
     * @param {string}  sel     触发元素选择器（如 '.bookcard'）
     * @param {function} build  回调 (el, ev) → items[]；返回空数组则不弹
     */
    function bindLongPress(root, sel, build) {
        if (!root || root._lpBound) return;
        root._lpBound = true;
        var LONG = 480;   // 长按判定阈值（ms）

        var getEl = function (e) {
            var t = e.target;
            while (t && t !== root) {
                if (t.matches && t.matches(sel)) return t;
                t = t.parentNode;
            }
            return null;
        };
        var pt = function (e) {
            if (e.touches && e.touches[0]) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
            return { x: e.clientX, y: e.clientY };
        };

        var start = function (e) {
            var el = getEl(e);
            if (!el) return;
            var p = pt(e);
            _ctxFired = false;
            _ctxStart = p;
            clearTimeout(_ctxTimer);
            _ctxTimer = setTimeout(function () {
                var items = build(el, e) || [];
                if (items.length) {
                    _ctxFired = true;
                    // 轻微震动反馈（WebView 支持时有，没有就静默）
                    try { if (navigator.vibrate) navigator.vibrate(12); } catch (ignored) {}
                    ctxMenu(_ctxStart.x, _ctxStart.y, items);
                }
            }, LONG);
        };
        var cancel = function () { clearTimeout(_ctxTimer); };
        // 移动超过 10px 视为滚动，取消长按
        var move = function (e) {
            if (!_ctxStart) return;
            var p = pt(e);
            if (Math.abs(p.x - _ctxStart.x) > 10 || Math.abs(p.y - _ctxStart.y) > 10) cancel();
        };
        // 长按已触发 → 吞掉随后的 click，避免同时打开详情
        var swallow = function (e) {
            if (_ctxFired) {
                _ctxFired = false;
                e.preventDefault();
                e.stopPropagation();
                return false;
            }
        };

        root.addEventListener('touchstart', start, { passive: true });
        root.addEventListener('touchmove', move, { passive: true });
        root.addEventListener('touchend', cancel, { passive: true });
        root.addEventListener('touchcancel', cancel, { passive: true });
        // 鼠标也能长按（桌面/调试）
        root.addEventListener('mousedown', function (e) { if (e.button === 0) start(e); });
        root.addEventListener('mousemove', move);
        root.addEventListener('mouseup', cancel);
        root.addEventListener('mouseleave', cancel);
        // capture 阶段吞 click，保证在 onclick 之前
        root.addEventListener('click', swallow, true);
        // 右键 = 直接弹菜单（桌面友好）
        root.addEventListener('contextmenu', function (e) {
            var el = getEl(e);
            if (!el) return;
            var items = build(el, e) || [];
            if (!items.length) return;
            e.preventDefault();
            ctxMenu(e.clientX, e.clientY, items);
        });
    }
    App._bindLongPress = bindLongPress;

    /** ★ v3.7.10：列数下拉 */
    ui.chsToggleCols = function (ev) {
        if (ev) ev.stopPropagation();
        var btn = $('chsColsBtn');
        if (!btn) return;
        var cur = chapCols();
        chsDropdown(btn, [
            { label: '1 列', on: cur === 1, pick: function () { chsApplyCols(1); } },
            { label: '2 列', on: cur === 2, pick: function () { chsApplyCols(2); } },
            { label: '3 列', on: cur === 3, pick: function () { chsApplyCols(3); } }
        ]);
    };

    function chsApplyCols(n) {
        if (n !== 1 && n !== 2 && n !== 3) return;
        if (!App.chapView) App.chapView = { cols: 1, seg: 0 };
        App.chapView.cols = n;
        saveChapView();
        repaintChsList(true);                    // 切列数保持滚动位置
        paintChsToolBtns();
    }

    /** ★ v3.7.10：区间下拉（200 集/段，>12 段时折叠中间） */
    ui.chsToggleSeg = function (ev) {
        if (ev) ev.stopPropagation();
        var btn = $('chsSegBtn');
        if (!btn) return;
        var chs = App.chapters || [];
        var win = chapWindow(chs.length);
        var items = [];
        for (var i = 0; i < win.segCount; i++) {
            items.push({
                label: chapSegLabel(i, chs.length),
                on: i === win.seg,
                pick: (function (seg) {
                    return function () { chsApplySeg(seg); };
                })(i)
            });
        }
        chsDropdown(btn, items);
    };

    function chsApplySeg(i) {
        if (typeof i !== 'number' || i < 0) return;
        if (!App.chapView) App.chapView = { cols: 1, seg: 0 };
        App.chapView.seg = i;
        saveChapView();
        repaintChsList(false);                   // 切段后滚到当前集/段首
        paintChsToolBtns();
    }

    ui.closeChapterList = function () {
        var w = $('chSheetWrap');
        if (w && w.parentNode) w.parentNode.removeChild(w);
        // ★ v3.7.10：弹层关了，还开着的小下拉也一并收起（不然会飘在页面上）
        chsCloseDropdown();
    };

    // ---- v2.8 单集下载 ----

    // 四态图标：0 未下载 / 1 下载中 / 2 已完成 / 3 失败（可重试）
    var DL_ICON = { 0: '\u2B07', 1: '\u23F3', 2: '\u2713', 3: '\u21BB' };

    /**
     * 刷新某一集在所有列表里的下载按钮图标。
     * 同一个集号可能同时出现在详情页和选集弹层，所以要按 data-dl 全选。
     */
    function paintDlIcon(no, st) {
        st = st || 0;
        var els = document.querySelectorAll('[data-dl="' + no + '"]');
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            el.textContent = DL_ICON[st] || DL_ICON[0];
            el.className = el.className.replace(/\s*dl-st\d+/g, '') + ' dl-st' + st;
        }
    }

    /** 按 App.cacheStatus 整体重刷（渲染完列表后调） */
    function paintAllDlIcons() {
        var m = App.cacheStatus || {};
        for (var k in m) {
            if (m.hasOwnProperty(k)) paintDlIcon(k, m[k]);
        }
    }

    /**
     * 点某集的下载按钮。
     * ★ v2.9：改走 downloadChapter（独立于缓存的「下载」通道）。
     * 单集数据量小，直接下，不弹确认框（整本下载才需要确认）。
     *
     * ★ v2.9 多选模式：正在多选时，点 ⬇ 不再触发下载，
     *   而是切换该集的勾选状态（否则用户会误以为"我在选，怎么开始下了"）。
     */
    ui.dlChapter = function (no) {
        if (!App.book) return;
        if (App.multi) { ui.toggleSel(no); return; }
        var st = App.cacheStatus && App.cacheStatus[no];
        if (st === 2) { TS.toast('第' + no + '集已下载'); return; }
        if (st === 1) return;                       // 正在下，忽略重复点
        App.cacheStatus = App.cacheStatus || {};
        App.cacheStatus[no] = 1;
        paintDlIcon(no, 1);                          // 乐观置为「下载中」
        TS.call('downloadChapter', JSON.stringify(App.book), no);
    };

    // ==================== ★ v2.9 多选下载 ====================
    //
    // 长按章节行进入多选（用户确认的交互），进入后：
    //   · 每行左侧出现勾选框
    //   · 点行 = 切换勾选（不再播放 —— 多选时误触发播放体验很差）
    //   · 顶部吸顶条显示「已选 N 集」+ 全选 / 反选 / 下载(N) / 完成
    //   · 再长按任意行 或 点「完成」或 按返回键 → 退出

    /** 章节总数（用 App.chapters 长度即可） */
    function chapterTotal() {
        return (App.chapters && App.chapters.length) || 0;
    }

    /** 第 i 行（0 基下标）对应的 1 基集号 */
    function chapNo(i) {
        var ch = App.chapters && App.chapters[i];
        return (ch && ch.index) || (i + 1);
    }

    /** 进入多选模式，并勾中第 no 集 */
    ui.enterMulti = function (no) {
        if (!App.book || !App.chapters || !App.chapters.length) return;
        App.multi = { sel: {} };
        if (no > 0) App.multi.sel[no] = true;
        paintMultiUI();
    };

    /** 退出多选 */
    ui.exitMulti = function () {
        App.multi = null;
        paintMultiUI();
    };

    /** 切换某集勾选 */
    ui.toggleSel = function (no) {
        if (!App.multi) return;
        if (App.multi.sel[no]) delete App.multi.sel[no];
        else App.multi.sel[no] = true;
        paintMultiUI();
    };

    /** 当前选中的集号数组（升序） */
    function selList() {
        var out = [];
        if (App.multi) {
            for (var k in App.multi.sel) {
                if (App.multi.sel[k]) out.push(parseInt(k, 10));
            }
        }
        out.sort(function (a, b) { return a - b; });
        return out;
    }

    /**
     * 全选：只选「未下载」的集。
     * 已下载的默认不勾 —— 否则用户点「下载」会做一堆无用功，
     * 进度条还在那刷，体验很怪。
     */
    ui.selAll = function () {
        if (!App.multi) return;
        var n = chapterTotal();
        for (var i = 0; i < n; i++) {
            var no = chapNo(i);
            if ((App.cacheStatus && App.cacheStatus[no]) !== 2) App.multi.sel[no] = true;
        }
        paintMultiUI();
    };

    /** 反选：在当前选中集合上取反（同样跳过已下载的） */
    ui.selInvert = function () {
        if (!App.multi) return;
        var old = App.multi.sel;
        var next = {};
        var n = chapterTotal();
        for (var i = 0; i < n; i++) {
            var no = chapNo(i);
            if ((App.cacheStatus && App.cacheStatus[no]) === 2) continue;  // 已下载不参与
            if (!old[no]) next[no] = true;
        }
        App.multi.sel = next;
        paintMultiUI();
    };

    /** 下载选中的集 */
    ui.selDownload = function () {
        if (!App.multi) return;
        var list = selList();
        if (!list.length) { TS.toast('还没有勾选任何集'); return; }
        // 乐观置为「下载中」
        App.cacheStatus = App.cacheStatus || {};
        for (var i = 0; i < list.length; i++) {
            App.cacheStatus[list[i]] = 1;
        }
        paintAllDlIcons();
        TS.call('downloadChapters', JSON.stringify(App.book), JSON.stringify(list));
        TS.toast('已加入下载队列 ' + list.length + ' 集');
        ui.exitMulti();       // 下完就退出，回到干净列表
    };

    /** 重刷多选相关 UI（勾选框 / 吸顶条） */
    function paintMultiUI() {
        var bar = $('multiBar');
        var on = !!App.multi;
        if (bar) bar.style.display = on ? '' : 'none';
        // 勾选框的显隐由 body 上的一个类统一控制（CSS 里 .chk 默认 display:none），
        // 比逐个元素改 style 快，也不会在切换时引发布局抖动。
        try {
            if (on) document.body.className += ' multi-on';
            else document.body.className = document.body.className.replace(/\s*\bmulti-on\b/g, '');
        } catch (e) {}

        // 章节行：加/去 .sel 类
        var rows = document.querySelectorAll('#detailContent .chapter');
        for (var i = 0; i < rows.length; i++) {
            var el = rows[i];
            // 行上的 data-idx 就是 1 基集号（v2.8 已确认）
            var no = parseInt(el.getAttribute('data-idx'), 10) || 0;
            var sel = on && App.multi.sel[no];
            if (sel) {
                if (el.className.indexOf('sel') < 0) el.className += ' sel';
            } else {
                el.className = el.className.replace(/\s*\bsel\b/g, '');
            }
        }
        // 吸顶条上的计数
        var cnt = $('multiCount');
        if (cnt) cnt.textContent = on ? ('已选 ' + selList().length + ' 集') : '';
        var dlBtn = $('multiDl');
        if (dlBtn) dlBtn.textContent = on ? ('下载(' + selList().length + ')') : '下载';
    }

    /** 原生：点按后确认「已入队下载中」 */
    window.onChapterCacheStatus = function (o) {
        if (!o) return;
        App.cacheStatus = App.cacheStatus || {};
        App.cacheStatus[o.index] = 1;
        paintDlIcon(o.index, 1);
        if (App.multi) paintMultiUI();
    };

    /** 原生：整本书的下载状态快照 */
    window.onChapterCacheStatusMap = function (o) {
        if (!o || !o.map) return;
        var m = {};
        for (var k in o.map) {
            if (o.map.hasOwnProperty(k)) m[k] = o.map[k];
        }
        App.cacheStatus = m;
        paintAllDlIcons();
    };

    /** 原生：单集下载结束（成功 / 失败） */
    window.onChapterCacheDone = function (o) {
        if (!o) return;
        var st = o.ok ? 2 : 3;
        App.cacheStatus = App.cacheStatus || {};
        App.cacheStatus[o.index] = st;
        paintDlIcon(o.index, st);
    };

    ui.pickChapter = function (i) {
        ui.closeChapterList();
        ui.playChapter(i);
        // 全屏页里直接换集，停留原页更顺手
        if (!App.fullShow && App.page === TABS.detail) {
            var el = $('ch' + i);
            if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' });
        }
    };

    // ==================== 倍速 ====================
    //
    // ★ v2.8：从「固定档位按钮」改为「滑杆 + 0.1 步进」（0.5x ~ 3.0x）。
    //
    // 浮点误差防治：滑杆内部一律用「整数档位」——
    //   档位 5 = 0.5x，档位 10 = 1.0x，档位 30 = 3.0x。
    //   <input type="range"> 的 min/max/step 全用整数，value 永远不会有
    //   0.1 累加漂移（若直接用 0.1 做 step，WebView 里可能出现 1.2999999）。
    //   只在「显示」和「交给原生」时才 /10。
    var SP_STEP_MIN = 5;      // 0.5x
    var SP_STEP_MAX = 30;     // 3.0x

    /** 当前倍速 → 整数档位 */
    function speedToStep(s) {
        var n = Math.round((s || 1.0) * 10);
        if (n < SP_STEP_MIN) n = SP_STEP_MIN;
        if (n > SP_STEP_MAX) n = SP_STEP_MAX;
        return n;
    }

    /** 渲染一个倍速滑杆（设置页与播放页共用同一份 HTML） */
    function speedSliderHtml(cid) {
        var step = speedToStep(App.speed);
        // 低版本（API < 23）没有 setPlaybackParams，滑杆没意义 → 降级为只读提示
        if (!App.canSpeed) {
            return '<div class="sp-na">当前系统版本不支持倍速播放</div>';
        }
        return '<div class="speed-slider">' +
            '<input type="range" class="sp-range" min="' + SP_STEP_MIN +
            '" max="' + SP_STEP_MAX + '" step="1" value="' + step +
            '" id="' + cid + '_r" oninput="App.ui.onSpeedInput(this.value)">' +
            '<div class="sp-val" id="' + cid + '_v">' +
            (step / 10).toFixed(1) + 'x</div></div>';
    }

    function paintSpeed() {
        if ($('speedList')) $('speedList').innerHTML = speedSliderHtml('speedList');
        if ($('fullSpeed')) $('fullSpeed').innerHTML = speedSliderHtml('fullSpeed');
    }

    /**
     * 拖动中：立刻更新两处显示值，但「提交给原生」做节流。
     * 拖动时 input 事件触发非常密集，每次都 setPlaybackParams 会卡顿。
     */
    var _spTimer = null;
    ui.onSpeedInput = function (stepStr) {
        var step = parseInt(stepStr, 10);
        if (isNaN(step)) return;
        if (step < SP_STEP_MIN) step = SP_STEP_MIN;
        if (step > SP_STEP_MAX) step = SP_STEP_MAX;

        var s = step / 10;

        // 两处滑杆/数值保持同步（设置页与播放页可能同时存在）
        var r1 = $('speedList_r'), r2 = $('fullSpeed_r');
        if (r1 && r1.value !== String(step)) r1.value = step;
        if (r2 && r2.value !== String(step)) r2.value = step;
        if ($('speedList_v')) $('speedList_v').textContent = s.toFixed(1) + 'x';
        if ($('fullSpeed_v')) $('fullSpeed_v').textContent = s.toFixed(1) + 'x';

        App.speed = s;

        // 节流提交：停手约 120ms 后才真正落到播放器
        if (_spTimer) clearTimeout(_spTimer);
        _spTimer = setTimeout(function () {
            _spTimer = null;
            TS.call('setSpeed', s);
        }, 120);
    };

    ui.setSpeed = function (s) {
        App.speed = s;
        TS.call('setSpeed', s);
        var step = speedToStep(s);
        var r1 = $('speedList_r'), r2 = $('fullSpeed_r');
        if (r1) r1.value = step;
        if (r2) r2.value = step;
        if ($('speedList_v')) $('speedList_v').textContent = (step / 10).toFixed(1) + 'x';
        if ($('fullSpeed_v')) $('fullSpeed_v').textContent = (step / 10).toFixed(1) + 'x';
        TS.toast('倍速 ' + (step / 10).toFixed(1) + 'x');
    };

    /** ★ v2.0：系统媒体控件（iQOO 原子播放器/锁屏）改了倍速，回传到这里同步 UI */
    window.onSysSpeed = function (rate) {
        if (!rate || rate <= 0) return;
        // 系统回传的可能是任意值（含旧档位 0.75），夹到范围内并对齐到 0.1 档
        var step = speedToStep(rate);
        App.speed = step / 10;
        var r1 = $('speedList_r'), r2 = $('fullSpeed_r');
        if (r1) r1.value = step;
        if (r2) r2.value = step;
        if ($('speedList_v')) $('speedList_v').textContent = (step / 10).toFixed(1) + 'x';
        if ($('fullSpeed_v')) $('fullSpeed_v').textContent = (step / 10).toFixed(1) + 'x';
        TS.toast('倍速 ' + (step / 10).toFixed(1) + 'x');
    };

    /**
     * ★ v3.1：「更多」下拉菜单。
     *
     * <p>为什么把「倍速」「音量」从按钮行里收进来：
     * 播放页底部那一行原本挤了 4 个按钮（选集/定时/倍速/音量），
     * 小屏上要么换行、要么每个都被压得很窄。这两项都属于「调一次就不常动」，
     * 收进二级菜单后主行只留 3 个高频操作，不再拥挤也不换行。
     *
     * <p>★ v3.2：从底部 sheet 改成**贴在按钮上的下拉小气泡**。
     * sheet 是从屏幕底下滑上来的大面板，在播放页这种满屏内容上用起来很突兀
     * （一大片遮罩压住整个播放界面）。下拉气泡只占按钮上方一小块，
     * 关掉也只需点别处，跟"点按钮弹个菜单"的直觉一致。
     */
    ui.moreMenu = function () {
        var btn = $('moreToggle');
        if (!btn) return;
        // 已经开着就关掉（再点一次 = 收起）
        if ($('moreDrop')) { ui.closeMore(); return; }

        var sp = App.speed || 1.0;
        var d = document.createElement('div');
        d.id = 'moreDrop';
        d.className = 'more-drop';
        d.innerHTML =
            '<div class="more-item" onclick="App.ui.moreSpeed()">' +
            '&#9193; 倍速<span class="more-val">' + sp.toFixed(1) + 'x</span></div>' +
            '<div class="more-item" onclick="App.ui.moreGain()">' +
            '&#128266; 音量增益<span class="more-val">' + gainLabel(App.gain) + '</span></div>';
        document.body.appendChild(d);

        // 定位到按钮右上方（按钮本身是右对齐的，气泡也跟着右对齐）
        var r = btn.getBoundingClientRect();
        d.style.left = 'auto';
        d.style.right = Math.max(8, (window.innerWidth - r.right)) + 'px';
        d.style.bottom = (window.innerHeight - r.top + 8) + 'px';

        if (btn) btn.classList.add('on');

        // 点页面任意别处收起（捕获阶段，免得被里层的 stopPropagation 挡掉）
        setTimeout(function () {
            document.addEventListener('click', _moreOutside, true);
        }, 0);
    };

    function _moreOutside(e) {
        var d = $('moreDrop');
        if (!d) { document.removeEventListener('click', _moreOutside, true); return; }
        // 点在气泡内 或 点在「更多」按钮上 → 不关（按钮自己有切换逻辑）
        if (d.contains(e.target)) return;
        var btn = $('moreToggle');
        if (btn && btn.contains(e.target)) return;
        ui.closeMore();
        document.removeEventListener('click', _moreOutside, true);
    }

    /** 收起「更多」下拉（点项、点按钮、点别处都走这里） */
    ui.closeMore = function () {
        var d = $('moreDrop');
        if (d) d.parentNode.removeChild(d);
        var btn = $('moreToggle');
        if (btn) btn.classList.remove('on');
    };

    /** 菜单里选「倍速」→ 收起下拉、展开倍速滑杆面板 */
    ui.moreSpeed = function () {
        ui.closeMore();
        // 已经在展开态时再点一次不该收起，用户点菜单就是为了调它，
        // 所以这里直接强制展开，不能沿用 toggle 的反转语义
        var panel = $('speedPanel');
        if (panel && panel.classList.contains('hide')) ui.toggleSpeed();
    };

    /** 菜单里选「音量增益」→ 收起下拉、展开增益滑杆面板 */
    ui.moreGain = function () {
        ui.closeMore();
        var panel = $('gainPanel');
        if (panel && panel.classList.contains('hide')) ui.toggleGain();
    };

    /** 倍速面板折叠/展开（默认收起，从「更多」菜单里点开） */
    ui.toggleSpeed = function () {
        var panel = $('speedPanel');
        if (!panel) return;
        var willOpen = panel.classList.contains('hide');
        panel.classList[willOpen ? 'remove' : 'add']('hide');
        // ★ v3.1：两个面板互斥，同时展开会把播放页顶上去
        if (willOpen) {
            var gp = $('gainPanel');
            if (gp && !gp.classList.contains('hide')) gp.classList.add('hide');
        }
    };

    // ==================== ★ v3.1 音量增益（声音增幅/降幅） ====================
    //
    // 单位用「百分比」而不是 dB：用户对这个滑杆的心智是「音量调到多少」。
    //   50  = 降到一半（原生走 MediaPlayer.setVolume 衰减）
    //   100 = 原始音量（默认）
    //   200 = 放大到 200%（原生走 LoudnessEnhancer 响度增强）
    // 滑杆内部同样用整数档位（步进 10），避免浮点漂移。
    var GAIN_MIN = 50;    // 50%
    var GAIN_MAX = 300;   // 300%
    var GAIN_STEP = 10;   // 每档 10%

    /** 与倍速一致的整数档位思路：档位 = 百分比本身（都是整数，无需换算） */
    function gainToStep(pct) {
        var n = Math.round((pct || 100) / GAIN_STEP) * GAIN_STEP;
        if (n < GAIN_MIN) n = GAIN_MIN;
        if (n > GAIN_MAX) n = GAIN_MAX;
        return n;
    }

    /** 增益文案：0 基准用「原始」而不是 +100%，用户更好理解 */
    function gainLabel(pct) {
        if (!pct || pct === 100) return '原始';
        if (pct < 100) return '-' + (100 - pct) + '%';
        return '+' + (pct - 100) + '%';
    }

    /** 渲染增益滑杆（播放页与设置页共用） */
    function gainSliderHtml(cid) {
        var step = gainToStep(App.gain);
        return '<div class="speed-slider gain-slider">' +
            '<input type="range" class="sp-range" min="' + GAIN_MIN +
            '" max="' + GAIN_MAX + '" step="' + GAIN_STEP + '" value="' + step +
            '" id="' + cid + '_r" oninput="App.ui.onGainInput(this.value)">' +
            '<div class="sp-val" id="' + cid + '_v">' + gainLabel(step) + '</div>' +
            '<div class="gain-ticks"><span>50%</span><span>100%</span>' +
            '<span>200%</span><span>300%</span></div>' +
            '</div>';
    }

    function paintGain() {
        if ($('gainList')) $('gainList').innerHTML = gainSliderHtml('gainList');
        if ($('fullGain')) $('fullGain').innerHTML = gainSliderHtml('fullGain');
    }

    /** 拖动中：立即更新显示，提交给原生做节流（同倍速的做法） */
    var _gnTimer = null;
    ui.onGainInput = function (stepStr) {
        var step = parseInt(stepStr, 10);
        if (isNaN(step)) return;
        step = gainToStep(step);

        var r1 = $('gainList_r'), r2 = $('fullGain_r');
        if (r1 && r1.value !== String(step)) r1.value = step;
        if (r2 && r2.value !== String(step)) r2.value = step;
        if ($('gainList_v')) $('gainList_v').textContent = gainLabel(step);
        if ($('fullGain_v')) $('fullGain_v').textContent = gainLabel(step);

        App.gain = step;

        if (_gnTimer) clearTimeout(_gnTimer);
        _gnTimer = setTimeout(function () {
            _gnTimer = null;
            TS.call('setGain', step);
        }, 120);
    };

    ui.setGain = function (pct) {
        pct = gainToStep(pct);
        App.gain = pct;
        TS.call('setGain', pct);
        var r1 = $('gainList_r'), r2 = $('fullGain_r');
        if (r1) r1.value = pct;
        if (r2) r2.value = pct;
        if ($('gainList_v')) $('gainList_v').textContent = gainLabel(pct);
        if ($('fullGain_v')) $('fullGain_v').textContent = gainLabel(pct);
        TS.toast('音量 ' + gainLabel(pct));
    };

    /** 原生回传当前增益（进播放页时校正滑杆位置） */
    window.onGain = function (o) {
        if (!o || o.gain === undefined) return;
        var pct = gainToStep(o.gain);
        App.gain = pct;
        var r1 = $('gainList_r'), r2 = $('fullGain_r');
        if (r1) r1.value = pct;
        if (r2) r2.value = pct;
        if ($('gainList_v')) $('gainList_v').textContent = gainLabel(pct);
        if ($('fullGain_v')) $('fullGain_v').textContent = gainLabel(pct);
    };

    /** 音量面板折叠/展开（与倍速面板互斥，同样从「更多」菜单点开） */
    ui.toggleGain = function () {
        var panel = $('gainPanel');
        if (!panel) return;
        var willOpen = panel.classList.contains('hide');
        panel.classList[willOpen ? 'remove' : 'add']('hide');
        if (willOpen) {
            var sp = $('speedPanel');
            if (sp && !sp.classList.contains('hide')) sp.classList.add('hide');
        }
    };

    // ==================== ★ v3.1 其他声音打断策略 ====================

    var FOCUS_MODES = [
        { v: 0, t: '不处理', d: '听书不会被任何声音打断' },
        { v: 1, t: '降低音量', d: '对方发声时音量降到 20%，结束后恢复' },
        { v: 2, t: '暂停播放', d: '对方发声时暂停，结束后自动续播' }
    ];

    function focusModeText(v) {
        for (var i = 0; i < FOCUS_MODES.length; i++) {
            if (FOCUS_MODES[i].v === v) return FOCUS_MODES[i].t;
        }
        return '不处理';
    }

    function paintFocusMode() {
        var el = $('focusModeVal');
        if (el) el.textContent = focusModeText(App.audioFocusMode);
    }

    ui.focusModeMenu = function () {
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">其他声音打断</div>';
        h += '<div class="srcmeta">微信语音条、其它播放器发声时，本 App 怎么反应</div>';
        for (var i = 0; i < FOCUS_MODES.length; i++) {
            var m = FOCUS_MODES[i];
            var on = (m.v === App.audioFocusMode);
            h += '<div class="sheet-item' + (on ? ' on' : '') +
                 '" onclick="App.ui.pickFocusMode(' + m.v + ')">' +
                 TS.esc(m.t) + '<span class="sheet-sub">' + TS.esc(m.d) + '</span></div>';
        }
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    ui.pickFocusMode = function (v) {
        ui.closeSheet();
        App.audioFocusMode = v;
        paintFocusMode();
        TS.call('setSetting', 'audio_focus_mode', String(v));
        TS.toast('已设为「' + focusModeText(v) + '」');
    };

    // ==================== 定时关闭 ====================

    ui.sleepMenu = function () {
        var opts = [0, 10, 15, 30, 45, 60, 90, 120];
        var labels = ['关闭', '10分钟', '15分钟', '30分钟', '45分钟', '1小时', '1.5小时', '2小时'];
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">定时关闭</div>';
        for (var i = 0; i < opts.length; i++) {
            h += '<div class="sheet-item' + (App.sleepMin === opts[i] ? ' on' : '') +
                 '" onclick="App.ui.sleepPick(' + opts[i] + ')">' + labels[i] + '</div>';
        }
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        var d = document.createElement('div');
        d.id = 'sheetWrap';
        d.innerHTML = h;
        document.body.appendChild(d);
    };

    ui.closeSheet = function () {
        var w = $('sheetWrap');
        if (w && w.parentNode) w.parentNode.removeChild(w);
    };

    ui.sleepPick = function (min) {
        ui.closeSheet();
        App.sleepMin = min;
        if (App.sleepTimer) { clearInterval(App.sleepTimer); App.sleepTimer = null; }
        if (min <= 0) {
            App.sleepAt = 0;
            TS.toast('定时关闭已取消');
        } else {
            App.sleepAt = Date.now() + min * 60000;
            App.sleepTimer = setInterval(function () {
                paintSleepBtn();
                if (Date.now() >= App.sleepAt) {
                    clearInterval(App.sleepTimer);
                    App.sleepTimer = null;
                    App.sleepMin = 0;
                    App.sleepAt = 0;
                    TS.call('pause');
                    paintSleepBtn();
                    TS.toast('定时时间到，已暂停');
                }
            }, 30000);
            TS.toast(min + ' 分钟后自动暂停');
        }
        paintSleepBtn();
    };

    function paintSleepBtn() {
        var el = $('sleepBtn');
        if (!el) return;
        if (App.sleepMin > 0) {
            var d = Math.max(0, Math.ceil((App.sleepAt - Date.now()) / 60000));
            el.textContent = '⏱ ' + d + '分';
            el.classList.add('on');
        } else {
            el.textContent = '⏱ 定时';
            el.classList.remove('on');
        }
    }

    // ==================== 进度条拖动 ====================

    var dragging = false, draggingFull = false;

    function bindBar(barId, fillId, isFull) {
        var bar = $(barId);
        if (!bar) return;

        // ★ v2.0：拖动期间把「读 rect → 算百分比 → 写样式」压进一帧一次（rAF 节流）。
        // 原来每个 touchmove 事件都同步读写布局，手指快速滑动时事件频率远高于帧率，
        // 造成强制同步布局（layout thrashing）→ 进度条"跟不上手"。
        var rafId = 0, pendingP = 0, rectCache = null;

        function pctOf(e) {
            // rect 只在拖动开始时取一次；宽度在拖动过程中不会变
            if (!rectCache) rectCache = bar.getBoundingClientRect();
            var x = (e.touches ? e.touches[0].clientX : e.clientX) - rectCache.left;
            return Math.max(0, Math.min(1, x / rectCache.width));
        }

        function setDrag(v) {
            if (isFull) draggingFull = v; else dragging = v;
            bar.classList[v ? 'add' : 'remove']('dragging');
            // ★ v3.7.5：拖动期间去掉气泡 transition，跟手不拖影
            if (isFull && $('fCur')) $('fCur').classList[v ? 'add' : 'remove']('dragging');
        }

        function paint() {
            rafId = 0;
            var fill = $(fillId);
            if (fill) {
                // 用 transform: scaleX 代替 width%：只触发合成，不触发重排
                fill.style.transform = 'scaleX(' + pendingP + ')';
            }
            if (isFull && $('fCur') && App.dur > 0) {
                $('fCur').textContent = TS.fmt(Math.floor(App.dur * pendingP));
                // ★ v3.7.5：气泡同步跟手
                paintCurPos(pendingP, true);
            }
        }

        function move(e) {
            pendingP = pctOf(e);
            if (!rafId) rafId = requestAnimationFrame(paint);
        }

        function end(e) {
            if (isFull ? !draggingFull : !dragging) return;
            var p = pctOf(e.changedTouches ? e.changedTouches[0] : e);
            setDrag(false);
            rectCache = null;
            if (rafId) { cancelAnimationFrame(rafId); rafId = 0; }
            if (App.dur > 0) TS.call('seek', Math.floor(App.dur * p));
            if (e.cancelable) e.preventDefault();
        }

        bar.addEventListener('touchstart', function (e) { rectCache = null; setDrag(true); move(e); }, { passive: true });
        bar.addEventListener('touchmove', function (e) {
            if (isFull ? draggingFull : dragging) { move(e); if (e.cancelable) e.preventDefault(); }
        }, { passive: false });
        bar.addEventListener('touchend', end);

        bar.addEventListener('mousedown', function (e) {
            rectCache = null; setDrag(true); move(e);
            function mm(ev) { move(ev); }
            function mu(ev) {
                document.removeEventListener('mousemove', mm);
                document.removeEventListener('mouseup', mu);
                end(ev);
            }
            document.addEventListener('mousemove', mm);
            document.addEventListener('mouseup', mu);
        });
    }

    // ==================== 书源管理（二级页） ====================

    function loadSources() {
        TS.call('listSources');
        TS.call('listCustom');
        TS.call('listPlugins');
    }

    /** 「我的」页入口卡片 → 二级页 */
    ui.openSources = function () {
        goSub('sources');
        paintSources();
        // ★ v3.1：摘要（上次验证结果）只有 getSettings 带回包里有，
        //   进页面时补拉一次，否则自动验证刚跑完就进来的话标题栏还是空的。
        TS.call('getSettings');
    };

    window.onSourceList = function (arr) {
        var prev = App.sources || [];
        App.sources = arr || [];
        // 名字映射缓存（详情/播放/搜索分组标注来源用）
        var map = {};
        for (var i = 0; i < App.sources.length; i++) {
            map[App.sources[i].id] = App.sources[i].alias || App.sources[i].name;
        }
        App.sourceNames = map;
        paintSourceEntry();
        if (App.page === SUBS.sources) paintSources();
        else if (App.page === SUBS.srcGroup) {
            // 源列表变了：组内可能被删空 → 退回一级页
            var b = bucketsOf();
            if (!(b[App.srcGroupKey] || []).length) { ui.back(); return; }
            paintSrcGroup();
        }
        if (App.page === TABS.home) paintHomeSources();

        // ★ v3.7.9：源表在"启动后"变多了 —— 说明是 Python 运行时后台就绪后
        //   补登记的那批 .py 源。这时要把首页/发现页的源列表一起刷新，
        //   并把用户之前记着的那个源重新选中（可能它刚刚才出现）。
        if (App.sources.length > prev.length) {
            TS.call('discoverSources');
            if (App.pyBootToast) {
                App.pyBootToast = false;
                var added = App.sources.length - prev.length;
                if (added > 0) TS.toast('已就绪 ' + added + ' 个 Python 书源');
            }
        }
    };

    /**
     * ★ v3.7.9：Python 运行时状态回调。
     * 冷启动时解释器在后台解压 assets（13MB），.py 源要等它起好才登记。
     * 前端据此给用户一句明确的提示，而不是让人对着空列表发愣。
     */
    window.onPythonStatus = function (o) {
        o = o || {};
        App.pyState = o.state || 'idle';
        if (o.state === 'loading') {
            App.pyBootToast = true;     // 就绪后由 onSourceList 弹"已就绪 N 个"提示
            // 兜底轮询：原生回调理论上会推 onSourceList，但万一错过，
            // 这里每 1.5s 问一次状态，就绪后自己再拉一遍源表。
            pyPoll();
        }
    };

    /** ★ v3.7.9：Python 运行时启动期间的兜底轮询（最多 ~30 次，约 45 秒） */
    function pyPoll() {
        if (App._pyPollTimer) return;
        var tries = 0;
        App._pyPollTimer = setInterval(function () {
            tries++;
            if (tries > 30) { clearInterval(App._pyPollTimer); App._pyPollTimer = null; return; }
            if (App.pyState === 'ready' || App.pyState === 'failed') {
                clearInterval(App._pyPollTimer); App._pyPollTimer = null;
                return;
            }
            TS.call('pythonStatus');
            TS.call('listSources');
        }, 1500);
    }

    window.onSourceNames = function (o) {
        App.sourceNames = o || {};
        // 名字映射更新后，刷新依赖它的视图
        if (App.page === SUBS.detail) renderDetail();
        paintPlaySource();
        if (App.books && App.books.length) paintSearchChips();
    };

    function sourceName(id) {
        if (App.sourceNames && App.sourceNames[id]) return App.sourceNames[id];
        return id || '';
    }

    /** 「我的」页的入口卡片统计 */
    function paintSourceEntry() {
        var el = $('srcEntrySub');
        if (!el) return;
        var arr = App.sources || [];
        var on = 0, jar = 0, js = 0, py = 0;
        for (var i = 0; i < arr.length; i++) {
            if (arr[i].enabled) on++;
            // ★ v3.0：JS 源单列计数，插件那一堆（ShunSources 19 个）单独标出来
            if (arr[i].origin === 'js') js++;
            else if (arr[i].origin === 'jar') jar++;
            else if (arr[i].origin === 'py') py++;
        }
        var bits = [on + ' / ' + arr.length + ' 个启用'];
        if (js) bits.push(js + ' 个 JS 源');
        if (jar) bits.push(jar + ' 个 jar 插件源');
        if (py) bits.push(py + ' 个 Python 源');
        el.textContent = bits.join(' · ');
    }

    window.onPluginList = function (arr) {
        App.plugins = arr || [];
        if (App.page === SUBS.sources) paintSources();
        else if (App.page === SUBS.srcGroup) {
            // 插件卸载后组内可能清空，退回一级页
            var b = bucketsOf();
            var list = b[App.srcGroupKey] || [];
            if (!list.length) { ui.back(); return; }
            paintSrcGroup();
        }
    };

    /** 卸载插件后的回调：本地同步状态即可，native 之后会补发整表 */
    window.onPluginChanged = function (o) {
        if (o && o.ok) {
            TS.toast('已卸载插件');
            refreshSourceViews();
        } else if (o) {
            TS.toast('卸载失败');
        }
    };

    /**
     * 分组定义。顺序即一级页展示顺序。
     * groupOf() 把单个源映射到某个 k，一级页只列"有源"的分组。
     */
    var SRC_GROUPS = [
        { k: 'builtin', t: '内置源', sub: '随 App 自带，可停用但不可删除' },
        { k: 'js', t: 'JS 插件源', sub: '由 JS 脚本插件包提供，一个包可含多个源' },
        { k: 'jar', t: 'jar 插件源', sub: '由 jar 插件提供，可整包卸载' },
        // ★ v3.7.4：Python 源单独一组（以前混在 jar 组里，且点了删不掉）
        { k: 'py', t: 'Python 源', sub: '由 .py 脚本提供，可单独删除' },
        { k: 'url', t: '订阅导入', sub: '从网络订阅链接添加' },
        { k: 'file', t: '本地导入', sub: '从本地 JSON 文件添加' },
        { k: 'text', t: '粘贴导入', sub: '粘贴 JSON 文本添加' },
        { k: 'other', t: '其它来源', sub: '早期导入或来源未知' }
    ];

    function groupDef(k) {
        for (var i = 0; i < SRC_GROUPS.length; i++) if (SRC_GROUPS[i].k === k) return SRC_GROUPS[i];
        return { k: 'other', t: '其它来源', sub: '' };
    }

    /** 单个源 → 分组 key。eprendre 归到"订阅导入" */
    function groupOf(s) {
        if (!s) return 'other';
        if (s.builtin) return 'builtin';
        var o = s.origin || '';
        if (o === 'eprendre') return 'url';
        if (o === 'jar') return 'jar';
        if (o === 'js') return 'js';
        if (o === 'py') return 'py';
        if (o === 'url' || o === 'file' || o === 'text') return o;
        return 'other';
    }

    /** 按分组聚合所有源 */
    function bucketsOf() {
        var arr = App.sources || [];
        var b = {};
        for (var i = 0; i < arr.length; i++) {
            var k = groupOf(arr[i]);
            if (!b[k]) b[k] = [];
            b[k].push(arr[i]);
        }
        return b;
    }

    function countEnabled(list) {
        var n = 0;
        for (var i = 0; i < list.length; i++) if (list[i].enabled) n++;
        return n;
    }

    // ---------- 一级页：只列分组 ----------

    function paintSources() {
        var c = $('sourcesPageContent');
        if (!c) return;
        var arr = App.sources || [];
        if (!arr.length) {
            c.innerHTML = errBlock('还没有书源，点右上角「+」导入') +
                '<div class="tip">支持粘贴 JSON 配置、本地 JSON 文件、网络订阅链接三种方式。</div>';
            return;
        }

        var b = bucketsOf();
        var on = countEnabled(arr);

        var h = '';
        h += '<div class="srcops" style="padding:0 14px 10px">' +
             '<button class="mini" onclick="App.ui.importMenu()">导入源</button>' +
             '<button class="mini" onclick="App.ui.exportMenu()">导出 / 分享</button>' +
             '<button class="mini" onclick="App.ui.verifyMenu()">批量验证</button>' +
             '<span class="srccount">' + on + ' / ' + arr.length + ' 启用</span>' +
             '</div>';

        // ★ v3.1：上次验证的摘要。自动验证是静默跑的，用户不会看到任何弹窗，
        //   所以这里必须留一行"上次验证：…"，否则用户永远不知道它跑过。
        if (App.lastVerifySummary) {
            h += '<div class="srcverify-tip" onclick="App.ui.verifySummary()">' +
                 '&#9989; ' + TS.esc(App.lastVerifySummary) +
                 '<span class="srcverify-more">详情 &#8250;</span></div>';
        }

        for (var g = 0; g < SRC_GROUPS.length; g++) {
            var def = SRC_GROUPS[g];
            var list = b[def.k];
            if (!list || !list.length) continue;
            var e = countEnabled(list);
            h += '<div class="grprow" onclick="App.ui.openSrcGroup(\'' + def.k + '\')">';
            h += '<div class="grpico">' + groupIcon(def.k) + '</div>';
            h += '<div class="grpinfo">';
            h += '<div class="grpname">' + TS.esc(def.t) +
                 '<span class="grpcnt">' + list.length + '</span></div>';
            h += '<div class="grpsub">' + TS.esc(def.sub) + '</div>';
            h += '</div>';
            h += '<div class="grpstate"><span class="sc' + (e ? ' on' : ' off') + '">' +
                 e + '/' + list.length + ' 开</span><span class="srcarrow">&#8250;</span></div>';
            h += '</div>';
        }

        h += '<div class="tip">点分组进入查看该组的全部书源；在组内点某个源可单独启用/停用、' +
             '测试、改名或删除。源比较多时，可在组内长按任意一行（或点「批量管理」）' +
             '勾选多个源一起删除。</div>';
        c.innerHTML = h;
    }

    function groupIcon(k) {
        var m = {
            builtin: '&#127968;', url: '&#127760;', file: '&#128193;',
            text: '&#128203;', jar: '&#129513;', js: '&#128220;',
            py: '&#128013;', other: '&#128230;'
        };
        return m[k] || '&#128230;';
    }

    ui.openSrcGroup = function (k) {
        App.srcGroupKey = k;
        goSub('srcGroup');
        paintSrcGroup();
    };

    /** 二级页：某个分组下的全部源 */
    function paintSrcGroup() {
        var c = $('srcGroupContent');
        var t = $('srcGroupTitle');
        if (!c) return;
        var def = groupDef(App.srcGroupKey);
        if (t) t.textContent = def.t;

        var b = bucketsOf();
        var list = b[def.k] || [];
        if (!list.length) {
            // 组空了就退出多选态，否则吸顶条会孤零零留在空页上
            if (App.multiSrc) exitSrcMulti(true);
            paintSrcGroupNav(false);            // ★ v3.7.6：空组顶栏不留按钮
            c.innerHTML = errBlock('这个分组下还没有书源');
            return;
        }

        var multi = !!App.multiSrc;
        // 内置源不可删 → 这个分组根本不提供多选删除
        var canBatch = def.k !== 'builtin';

        // ★ v3.7.6：多选态下顶栏不需要批量开关，先清掉再按需画
        if (multi) paintSrcGroupNav(false);

        var h = '';

        // ★ v3.7.4：多选吸顶条。只在多选态出现，与章节多选共用 .multi-bar 样式。
        if (multi) {
            h += '<div class="multi-bar" id="srcMultiBar">';
            h += '<span class="mb-cnt" id="srcMultiCount"></span>';
            h += '<span class="mb-acts">';
            h += '<button class="mb-btn" onclick="App.ui.srcSelAll()">全选</button>';
            h += '<button class="mb-btn" onclick="App.ui.srcSelInvert()">反选</button>';
            h += '<button class="mb-btn danger" id="srcMultiDel" ' +
                 'onclick="App.ui.srcSelDelete()">删除</button>';
            h += '<button class="mb-btn" onclick="App.ui.exitSrcMulti()">取消</button>';
            h += '</span></div>';
        } else {
            // ★ v3.7.6：「全部开启 / 全部关闭」图标从分组标题行**移到橙色顶栏右侧**。
            //   一实一虚：实心圆=开启、空心圆=关闭（纯图标 + title 提示）。
            //   内置源组不给（内置源只能停用，批量开关意义不大，且容易误操作）。
            paintSrcGroupNav(canBatch);
            h += '<div class="grphd">' + TS.esc(def.sub) +
                 '<span class="srcbulk">' +
                   '<span class="srccount">' + countEnabled(list) + ' / ' + list.length + ' 启用</span>' +
                 '</span>' +
                 '</div>';
            if (canBatch && list.length >= 2) {
                h += '<div class="srcops" style="padding:0 0 8px">' +
                     '<button class="mini ghost" onclick="App.ui.enterSrcMulti()">' +
                     '批量管理</button></div>';
            }
        }

        for (var i = 0; i < list.length; i++) {
            var s = list[i];
            var nm = s.alias || s.name;
            var sel = multi && App.multiSrc.sel[s.id];
            // 内置源即便在多选态也不可勾选（灰掉）
            var locked = !!s.builtin;
            var cls = 'srcrow srcrow-tap';
            if (sel) cls += ' sel';
            if (multi && locked) cls += ' locked';

            var click = 'App.ui.srcRowClick(\'' + escAttr(s.id) + '\')';

            h += '<div class="' + cls + '" data-sid="' + escAttr(s.id) + '"' +
                 ' onclick="' + click + '"' +
                 // 长按源行也能进多选（与章节列表手感一致）
                 (multi ? '' : ' oncontextmenu="return App.ui.srcPressStart(event,\'' +
                     escAttr(s.id) + '\')"') +
                 ' onmousedown="' + (multi ? '' : 'App.ui.srcPressStart(null,\'' +
                     escAttr(s.id) + '\')') + '"' +
                 ' onmouseup="App.ui.srcPressEnd()" onmouseleave="App.ui.srcPressEnd()"' +
                 ' ontouchstart="' + (multi ? '' : 'App.ui.srcPressStart(null,\'' +
                     escAttr(s.id) + '\')') + '"' +
                 ' ontouchend="App.ui.srcPressEnd()" ontouchmove="App.ui.srcPressEnd()">';

            // 多选态：左侧圆形勾选框
            h += '<span class="chk chk-src"></span>';

            h += '<div class="srcinfo">';
            h += '<div class="srcname">' + TS.esc(nm) + '</div>';
            h += '<div class="srcdesc">' +
                 '<span class="sc' + (s.enabled ? ' on' : ' off') + '">' +
                 (s.enabled ? '已启用' : '已停用') + '</span>' +
                 (!s.canSearch ? ' · <span class="tag">仅分类</span>' : '') +
                 // ★ v3.0：JS 源里有一批站点反爬，得先去验证页过一次验证码
                 (s.needsVerification ? ' · <span class="tag tag-warn">需验证</span>' : '') +
                 (s.alias ? (' · 原名 ' + TS.esc(s.name)) : '') + '</div>';
            h += '</div>';

            // 行内开关：多选态下隐藏，避免和勾选混淆
            if (!multi) {
                h += '<label class="sw" onclick="event.stopPropagation()">' +
                     '<input type="checkbox" ' + (s.enabled ? 'checked' : '') +
                     ' onchange="App.ui.toggleSource(\'' + escAttr(s.id) + '\',this.checked)">' +
                     '<i></i></label>';
                h += '<span class="srcarrow">&#8250;</span>';
            }
            h += '</div>';
        }

        if (def.k === 'builtin') {
            h += '<div class="tip">内置源只能停用，不能删除。</div>';
        } else if (def.k === 'js') {
            h += '<div class="tip">JS 插件源由脚本提供，随插件包整包更新；' +
                 '标「需验证」的源首次搜索可能需要先在站点上过验证码。</div>';
        } else if (def.k === 'py') {
            h += '<div class="tip">Python 源由 .py 脚本提供，可以单独删除。' +
                 '长按任意一行或点「批量管理」可多选删除。</div>';
        } else {
            h += '<div class="tip">点某个源可查看详情并在那里删除；' +
                 '长按任意一行或点「批量管理」可多选删除。</div>';
        }
        c.innerHTML = h;
        if (multi) paintSrcMultiUI();
    }

    /**
     * ★ v3.7.7：把「全部开启 / 全部关闭」画到橙色顶栏右侧。
     *
     * <p>上一版是两个纯图标（一实一虚）紧挨着，中间没有间隔，用户反馈
     * 「成了一个整体，看不出是两个按钮」。现在改成：
     *  · 两个**独立胶囊**，各带图标 + 文字标签（开启 / 关闭），中间留间隔；
     *  · **用颜色标示当前模式** —— 什么状态占多数就点亮哪个按钮：
     *    全开 → 绿色实心；全关 → 灰色实心；混合 → 两个都是白描边（都不点亮）。
     *
     * @param {boolean} show 是否显示（内置源组 / 空组 / 多选态传 false 清空）
     */
    function paintSrcGroupNav(show) {
        var box = $('srcGroupNavActs');
        if (!box) return;
        if (!show) { box.innerHTML = ''; return; }

        // 统计当前分组的启用比例，用来决定哪个按钮"点亮"
        var list = (bucketsOf()[App.srcGroupKey]) || [];
        var on = 0;
        for (var i = 0; i < list.length; i++) if (list[i].enabled) on++;
        var allOn = list.length > 0 && on === list.length;
        var allOff = list.length > 0 && on === 0;

        box.innerHTML =
            '<span class="bulkbtn' + (allOn ? ' active-on' : '') + '" ' +
              'title="把本组全部书源开启" ' +
              'onclick="App.ui.srcToggleAll(true)">' +
              '<svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm0 15a5 5 0 110-10 5 5 0 010 10z"/></svg>' +
              '<i>全开</i>' +
            '</span>' +
            '<span class="bulkbtn' + (allOff ? ' active-off' : '') + '" ' +
              'title="把本组全部书源关闭" ' +
              'onclick="App.ui.srcToggleAll(false)">' +
              '<svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm0 2.5a7.5 7.5 0 110 15 7.5 7.5 0 010-15z"/></svg>' +
              '<i>全关</i>' +
            '</span>';
    }

    // ==================== ★ v3.7.4 源批量管理 ====================

    /** 进入多选态（分组页内） */
    ui.enterSrcMulti = function () {
        App.multiSrc = { sel: {}, group: App.srcGroupKey };
        paintSrcGroup();
    };

    /** 退出多选态。silent=true 时不重绘（调用方自己会画） */
    function exitSrcMulti(silent) {
        App.multiSrc = null;
        if (!silent) paintSrcGroup();
    }
    ui.exitSrcMulti = function () { exitSrcMulti(false); };

    /** 内置源不可选：给个明确反馈，别让用户以为点坏了 */
    ui.srcLockedTip = function () {
        TS.toast('内置源只能停用，不能删除');
    };

    ui.toggleSrcSel = function (id) {
        if (!App.multiSrc) return;
        var sel = App.multiSrc.sel;
        if (sel[id]) delete sel[id];
        else sel[id] = true;
        paintSrcMultiUI();
    };

    ui.srcSelAll = function () {
        if (!App.multiSrc) return;
        var list = (bucketsOf()[App.srcGroupKey] || []);
        for (var i = 0; i < list.length; i++) {
            if (list[i].builtin) continue;   // 内置源不参与
            App.multiSrc.sel[list[i].id] = true;
        }
        paintSrcMultiUI();
    };

    ui.srcSelInvert = function () {
        if (!App.multiSrc) return;
        var list = (bucketsOf()[App.srcGroupKey] || []);
        var next = {};
        for (var i = 0; i < list.length; i++) {
            var s = list[i];
            if (s.builtin) continue;
            if (!App.multiSrc.sel[s.id]) next[s.id] = true;
        }
        App.multiSrc.sel = next;
        paintSrcMultiUI();
    };

    function srcSelList() {
        if (!App.multiSrc) return [];
        var out = [], k;
        for (k in App.multiSrc.sel) if (App.multiSrc.sel[k]) out.push(k);
        return out;
    }

    /** 只刷勾选态与计数，不重绘整表（避免闪烁） */
    function paintSrcMultiUI() {
        var bar = $('srcMultiBar');
        var on = !!App.multiSrc;
        if (bar) bar.style.display = on ? '' : 'none';
        try {
            if (on) {
                if (document.body.className.indexOf('multi-src-on') < 0) {
                    document.body.className += ' multi-src-on';
                }
            } else {
                document.body.className = document.body.className
                    .replace(/\s*\bmulti-src-on\b/g, '');
            }
        } catch (e) {}

        var rows = document.querySelectorAll('#srcGroupContent .srcrow');
        for (var i = 0; i < rows.length; i++) {
            var el = rows[i];
            var id = el.getAttribute('data-sid');
            var sel = on && App.multiSrc.sel[id];
            if (sel) {
                if (el.className.indexOf('sel') < 0) el.className += ' sel';
            } else {
                el.className = el.className.replace(/\s*\bsel\b/g, '');
            }
        }

        var n = srcSelList().length;
        var cnt = $('srcMultiCount');
        if (cnt) cnt.textContent = '已选 ' + n + ' 个';
        var del = $('srcMultiDel');
        if (del) del.textContent = '删除(' + n + ')';
    }

    /** 长按 600ms 进入多选（与章节多选同一套手势参数） */
    var _srcPressTimer = null, _srcPressFired = false;
    ui.srcPressStart = function (ev, id) {
        if (App.multiSrc) return true;
        ui.srcPressEnd();
        _srcPressFired = false;
        _srcPressTimer = setTimeout(function () {
            _srcPressTimer = null;
            _srcPressFired = true;
            // 长按进入多选，并把长按的这行直接选上（少一次点击）
            App.multiSrc = { sel: {}, group: App.srcGroupKey };
            App.multiSrc.sel[id] = true;
            paintSrcGroup();
        }, 600);
        return true;   // oncontextmenu 需要返回 true 才不弹系统菜单
    };

    ui.srcPressEnd = function () {
        if (_srcPressTimer) { clearTimeout(_srcPressTimer); _srcPressTimer = null; }
    };

    /**
     * 长按已经触发过之后，浏览器还会补一个 click。
     * 若不拦，就会紧接着打开详情菜单，把刚进入的多选态顶掉。
     */
    ui.srcRowClick = function (id) {
        if (_srcPressFired) { _srcPressFired = false; return; }
        if (App.multiSrc) {
            var s = findSource(id);
            if (s && s.builtin) { ui.srcLockedTip(); return; }
            ui.toggleSrcSel(id);
            return;
        }
        ui.sourceMenu(id);
    };

    /** 批量删除：确认 → 逐个删除 → 汇总提示 */
    ui.srcSelDelete = function () {
        if (!App.multiSrc) return;
        var ids = srcSelList();
        if (!ids.length) { TS.toast('还没有勾选任何源'); return; }

        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">批量删除</div>';
        h += '<div class="srcmeta">将删除已勾选的 ' + ids.length +
             ' 个书源。收藏和历史不受影响，但源删掉后相关书籍可能搜不到了。</div>';
        h += '<div class="sheet-item danger" onclick="App.ui.doBatchDeleteSrc()">确认删除</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    ui.doBatchDeleteSrc = function () {
        ui.closeSheet();
        if (!App.multiSrc) return;
        var ids = srcSelList();
        if (!ids.length) return;

        // ★ 不逐个弹 toast：native 每删一个都会回 onCustomChanged + onSourceList，
        //   这里只在全部发完后统一提示一次，避免刷屏。
        App._batchDel = { left: ids.length, done: 0, total: ids.length };
        // 先退出多选态，等列表回来时是干净的单选视图
        exitSrcMulti(true);
        App.multiSrc = null;

        for (var i = 0; i < ids.length; i++) {
            TS.call('removeSourceById', ids[i]);
        }
        TS.toast('正在删除 ' + ids.length + ' 个源…');
    };

    function shortPath(p) {
        if (!p) return '';
        var i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /** 找到源的完整信息 */
    function findSource(id) {
        var arr = App.sources || [];
        for (var i = 0; i < arr.length; i++) if (arr[i].id === id) return arr[i];
        return null;
    }

    /** 该源所属的插件文件路径（非插件源返回 ''） */
    function pluginPathOf(id) {
        var plugs = App.plugins || [];
        for (var i = 0; i < plugs.length; i++) {
            var ids = String(plugs[i].sourceIds || '').split(',');
            for (var j = 0; j < ids.length; j++) {
                if (ids[j].trim() === id) return plugs[i].filePath || '';
            }
            var srcs = plugs[i].sources || [];
            for (var k = 0; k < srcs.length; k++) {
                if (srcs[k].id === id) return plugs[i].filePath || '';
            }
        }
        return '';
    }

    /** 单个源的详情菜单（三级） */
    ui.sourceMenu = function (id) {
        var s = findSource(id);
        if (!s) { TS.toast('源不存在'); return; }
        var nm = s.alias || s.name;
        var isBuiltin = !!s.builtin;
        var plugPath = pluginPathOf(id);

        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">' + TS.esc(nm) + '</div>';
        h += '<div class="srcmeta">' +
             TS.esc(s.desc || (s.alias ? ('原名：' + s.name) : '暂无简介')) + '</div>';

        // ★ v3.0：JS 源的额外说明 —— 站点验证与搜索间隔，这两条是用户最容易踩的坑
        if (s.needsVerification || s.searchDelay) {
            var tips = [];
            if (s.needsVerification) tips.push('该源需要站点验证：首次搜索若返回空，请到站点上过一次验证码再重试');
            if (s.searchDelay) tips.push('两次搜索之间至少间隔 ' + s.searchDelay + ' 秒（站点限流）');
            if (!s.canSearch) tips.push('该源不支持关键词搜索，只能从分类进入浏览');
            h += '<div class="srcmeta srcmeta-warn">' + TS.esc(tips.join('；')) + '</div>';
        }

        // 启用开关：点整行任意处都可切换（label 不拦截事件）
        h += '<div class="sheet-item srow" onclick="App.ui.menuToggle(\'' +
             escAttr(id) + '\',' + (s.enabled ? 'true' : 'false') + ')">' +
             '<span>' + (s.enabled ? '已启用（点击停用）' : '已停用（点击启用）') + '</span>' +
             '<label class="sw"><input type="checkbox" ' + (s.enabled ? 'checked' : '') +
             '><i></i></label></div>';

        h += '<div class="sheet-item" onclick="App.ui.menuTest(\'' + escAttr(id) + '\')">测试连通性</div>';
        h += '<div class="sheet-item" onclick="App.ui.menuRename(\'' + escAttr(id) + '\')">重命名</div>';

        if (isBuiltin) {
            h += '<div class="sheet-item disabled">内置源不可删除</div>';
        } else if (plugPath) {
            h += '<div class="sheet-item danger" onclick="App.ui.menuUninstall(\'' +
                 escAttr(id) + '\')">卸载插件源</div>';
        } else {
            h += '<div class="sheet-item danger" onclick="App.ui.menuDelete(\'' +
                 escAttr(id) + '\')">删除此源</div>';
        }
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    ui.menuToggle = function (id, cur) {
        var next = !cur;
        ui.closeSheet();
        TS.call('setSourceEnabled', id, next);
    };

    ui.menuTest = function (id) {
        ui.closeSheet();
        ui.testSource(id);
    };

    ui.menuRename = function (id) {
        ui.closeSheet();
        ui.renameSource(id);
    };

    var _pendingDel = null;

    function confirmDelete(title, msg, id, isPlugin, path) {
        _pendingDel = { id: id, plugin: !!isPlugin, path: path || '' };
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">' + TS.esc(title) + '</div>';
        h += '<div class="srcmeta">' + TS.esc(msg) + '</div>';
        h += '<div class="sheet-item danger" onclick="App.ui.doMenuDelete()">确认删除</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    }

    ui.menuDelete = function (id) {
        var s = findSource(id);
        confirmDelete('删除书源', '「' + ((s && (s.alias || s.name)) || id) +
            '」将被移除，收藏和历史不受影响。', id, false, '');
    };

    ui.menuUninstall = function (id) {
        var s = findSource(id);
        var path = pluginPathOf(id);
        if (!path) { TS.toast('找不到插件文件，无法卸载'); return; }
        confirmDelete('卸载插件源', '将卸载提供「' + ((s && (s.alias || s.name)) || id) +
            '」的插件文件，同文件内的其它源也会一起消失。', id, true, path);
    };

    ui.doMenuDelete = function () {
        var req = _pendingDel;
        _pendingDel = null;
        ui.closeSheet();
        if (!req || !req.id) return;

        if (req.plugin) {
            // ★ 原生方法名是 removePlugin（不是 removePluginFile）
            if (!req.path) { TS.toast('找不到插件文件路径'); return; }
            TS.toast('正在卸载插件…');
            TS.call('removePlugin', req.path);
        } else {
            TS.toast('正在删除…');
            TS.call('removeCustom', req.id);
        }
    };

    /**
     * 删除自定义源后的回调。
     * 注意：native 的 removeCustom / removeSourceById 会在本回调之后另发一次
     * onSourceList（整表），所以这里只做本地移除 + 提示，避免多一次无效的 listSources 往返。
     */
    window.onCustomChanged = function (o) {
        // ★ v3.7.4：批量删除期间不逐个提示，等全部回完统一报一次
        var batch = App._batchDel;
        if (batch && batch.left > 0) {
            batch.left--;
            if (o && o.ok) batch.done++;
            var arrB = App.sources || [];
            for (var j = arrB.length - 1; j >= 0; j--) {
                if (arrB[j] && arrB[j].id === (o && o.id)) arrB.splice(j, 1);
            }
            if (batch.left <= 0) {
                var done = batch.done, total = batch.total;
                App._batchDel = null;
                TS.toast(done === total
                    ? ('已删除 ' + done + ' 个源')
                    : ('已删除 ' + done + ' 个，' + (total - done) + ' 个失败'));
                paintSourceEntry();
                refreshSourceViews();
            }
            return;
        }

        if (o && o.ok) {
            TS.toast('已删除');
            var arr = App.sources || [];
            for (var i = arr.length - 1; i >= 0; i--) {
                if (arr[i].id === o.id) arr.splice(i, 1);
            }
            paintSourceEntry();
            refreshSourceViews();
        } else if (o && o.id) {
            TS.toast('删除失败');
        }
    };

    /** 删除/卸载后，统一按当前页刷新视图 */
    function refreshSourceViews() {
        if (App.page === SUBS.srcGroup) {
            var b = bucketsOf();
            if (!(b[App.srcGroupKey] || []).length) { ui.back(); return; }
            paintSrcGroup();
        } else if (App.page === SUBS.sources) {
            paintSources();
        }
        if (App.page === TABS.home) paintHomeSources();
    }

    ui.toggleSource = function (id, on) { TS.call('setSourceEnabled', id, !!on); };

    /**
     * ★ v3.7.5：分组顶栏「全部开启 / 全部关闭」。
     *
     * <p>不做新的 native 接口 —— 直接按当前分组的源逐个调 setSourceEnabled，
     * 复用已有的 onSourceChanged 回调。为避免 N 个源弹 N 次 toast，
     * 用一个 App._bulkSw 计数器把它合成一次汇总提示（与批量删除同思路）。
     *
     * @param {boolean} on true=全部开启，false=全部关闭
     */
    ui.srcToggleAll = function (on) {
        on = !!on;
        var list = (bucketsOf()[App.srcGroupKey]) || [];
        var todo = [];
        for (var i = 0; i < list.length; i++) {
            // 状态已一致的跳过，少发无谓的请求
            if (!!list[i].enabled !== on) todo.push(list[i].id);
        }
        if (!todo.length) {
            TS.toast(on ? '本组已全部开启' : '本组已全部关闭');
            return;
        }
        App._bulkSw = { left: todo.length, total: todo.length, on: on, group: App.srcGroupKey };
        for (var j = 0; j < todo.length; j++) TS.call('setSourceEnabled', todo[j], on);
        // 本地先乐观同步，UI 立刻反应（回调回来会再校一次）
        for (var k = 0; k < list.length; k++) list[k].enabled = on;
        paintSrcGroup();
        TS.toast((on ? '正在开启 ' : '正在关闭 ') + todo.length + ' 个源…');
    };

    window.onSourceChanged = function (o) {
        // ★ v3.7.5：批量开关期间不逐个提示，等全部回完统一报一次
        var bulk = App._bulkSw;
        if (bulk && bulk.left > 0) {
            bulk.left--;
            if (o && o.id) {
                var arrB = App.sources || [];
                for (var m = 0; m < arrB.length; m++) {
                    if (arrB[m].id === o.id) arrB[m].enabled = !!o.enabled;
                }
            }
            if (bulk.left <= 0) {
                TS.toast(bulk.on ? '已全部开启' : '已全部关闭');
                App._bulkSw = null;
                paintSourceEntry();
                if (App.page === SUBS.sources) paintSources();
                if (App.page === SUBS.srcGroup) paintSrcGroup();
                if (App.page === TABS.home) paintHomeSources();
            }
            return;
        }

        if (o && o.id) {
            TS.toast((o.enabled ? '已启用 ' : '已停用 ') + sourceName(o.id));
            // 本地同步状态，省一次整表重拉
            var arr = App.sources || [];
            for (var i = 0; i < arr.length; i++) {
                if (arr[i].id === o.id) arr[i].enabled = !!o.enabled;
            }
            paintSourceEntry();
            if (App.page === SUBS.sources) paintSources();
            // ★ v3.7.7：分组页里单独开关某个源，也要把顶栏「全开/全关」的
            //   高亮刷新一下 —— 否则从"全开"关掉一个变成混合态时，顶栏还
            //   亮着"全开"，跟列表里的实际状态对不上。
            if (App.page === SUBS.srcGroup) paintSrcGroup();
        }
    };

    ui.testSource = function (id) {
        var el = $('st_' + id);
        if (el) { el.textContent = '测试中…'; el.className = 'sctest'; }
        var isCustom = false;
        for (var i = 0; i < (App.sources || []).length; i++) {
            if (App.sources[i].id === id && !App.sources[i].builtin) isCustom = true;
        }
        TS.call(isCustom ? 'testCustom' : 'testSource', id);
    };

    window.onSourceTested = function (o) {
        var el = $('st_' + (o && o.id));
        if (el) {
            el.textContent = (o.ok ? '✔ ' : '✘ ') + (o.msg || '');
            el.className = 'sctest ' + (o.ok ? 'ok' : 'bad');
        }
    };

    ui.removeCustom = function (id) { ui.menuDelete(id); };

    ui.renameSource = function (id) {
        var cur = '';
        for (var i = 0; i < (App.sources || []).length; i++) {
            if (App.sources[i].id === id) cur = App.sources[i].alias || App.sources[i].name;
        }
        // 留空 = 恢复原名（原 prompt 语义），所以这里空串也要照传
        ui.inputSheet('给这个源起个新名字（留空恢复原名）', cur, function (name) {
            TS.call('renameSource', id, name);
        });
    };

    /** 按插件文件卸载（保留内部调用，管理页已不直接暴露此按钮） */
    ui.removePluginFile = function (path) {
        _pendingDel = null;
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">卸载插件</div>';
        h += '<div class="srcmeta">该插件提供的书源会立即失效。</div>';
        h += '<div class="sheet-item danger" onclick="App.ui.doRemovePlugin(\'' +
             escAttr(path) + '\')">确认卸载</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    ui.doRemovePlugin = function (path) {
        ui.closeSheet();
        TS.call('removePlugin', path);
        TS.toast('正在卸载…');
    };

    ui.togglePluginFile = function (path, on) { TS.call('setPluginEnabled', path, !!on); };

    // ---------- 导入 ----------

    ui.importMenu = function () {
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">导入源</div>';
        h += '<div class="sheet-item" onclick="App.ui.importPaste()">粘贴 JSON 文本</div>';
        h += '<div class="sheet-item" onclick="App.ui.importFile()">选择本地文件</div>' +
             '<div class="impsub">支持 .json 源配置、.jar 插件、.zip JS 插件包、.py / 含 .py 的 .zip Python 源</div>';
        h += '<div class="sheet-item" onclick="App.ui.importUrl()">从网络订阅链接导入</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    ui.importPaste = function () {
        ui.closeSheet();
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">粘贴源 JSON</div>';
        h += '<textarea id="impText" class="ta" placeholder=\'{"sources":[{"id":"mysite","name":"我的站点","base":"https://example.com","detail":{...},"audio":{...}}]}\'></textarea>';
        h += '<label class="impchk"><input type="checkbox" id="impOver" checked> 覆盖同 id 的已有源</label>';
        h += '<div class="sheet-item" onclick="App.ui.doImportPaste()">开始导入</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    ui.doImportPaste = function () {
        var t = $('impText');
        var txt = t ? t.value : '';
        if (!txt || !txt.trim()) { TS.toast('请先粘贴 JSON'); return; }
        var over = $('impOver') ? $('impOver').checked : true;
        ui.closeSheet();
        TS.toast('正在导入…');
        TS.call('importSourceText', txt, over);
    };

    ui.importFile = function () {
        ui.closeSheet();
        TS.call('pickSourceFile');
    };

    /** 文件选择结果：按类型分派（zip/js → JS 插件包；jar → jar 插件；py → Python 源；json → 文本导入） */
    window.onSourceFilePicked = function (o) {
        if (!o || !o.ok) {
            if (o && o.cancel) return;
            TS.toast((o && o.error) || '读取文件失败');
            return;
        }
        if (o.type === 'js') {
            // ★ v3.0：JS 脚本插件包。一个包可能一次装载十几个源，
            //   解包 + 执行脚本要花点时间（ShunSources 19 个源约 300ms），
            //   文案上先说清楚，免得用户以为卡住。
            TS.toast('正在装载 JS 插件包 ' + (o.file || '') + ' …');
            TS.call('importSourceJs', o.path, o.file || '');
        } else if (o.type === 'jar') {
            TS.toast('正在装载插件 ' + (o.file || '') + ' …');
            TS.call('importSourceJar', o.path, o.file || '');
        } else if (o.type === 'py') {
            // ★ v3.4：Python 源。App 内置真实 CPython 解释器，直接加载运行
            //   .py（不做转译）。首次装载要启动解释器，稍慢，先说清楚。
            TS.toast('正在装载 Python 源 ' + (o.file || '') + ' …');
            TS.call('importPySources', o.path, o.file || '');
        } else {
            // 原版「我的听书」订阅清单长得也是 .json，但内容是 download_url，
            // 需要联网下载插件包。提前告知用户，免得以为卡住了。
            var t = o.json || '';
            if (t.indexOf('download_url') >= 0 && t.indexOf('"sources"') < 0) {
                TS.toast('这是插件订阅，正在下载插件包…');
            } else {
                TS.toast('已读取 ' + (o.file || '文件') + '，正在导入…');
            }
            TS.call('importSourceLocal', o.json, o.file || '', true);
        }
    };

    ui.importUrl = function () {
        ui.closeSheet();
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">从链接导入</div>';
        h += '<input id="impUrl" class="ta one" placeholder="https://example.com/sources.json">';
        h += '<label class="impchk"><input type="checkbox" id="impUrlOver" checked> 覆盖同 id 的已有源</label>';
        h += '<div class="sheet-item" onclick="App.ui.doImportUrl()">开始导入</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    ui.doImportUrl = function () {
        var el = $('impUrl');
        var u = el ? el.value : '';
        if (!u || !u.trim()) { TS.toast('请先填写链接'); return; }
        var over = $('impUrlOver') ? $('impUrlOver').checked : true;
        ui.closeSheet();
        TS.toast('正在下载…');
        TS.call('importSourceUrl', u.trim(), over);
    };

    /** 导入结果：把 added/updated/skipped/failed 讲清楚，别只报"成功" */
    window.onSourceImported = function (r) {
        if (!r) return;
        var added = r.added || 0, upd = r.updated || 0, skip = r.skipped || 0,
            fail = r.failed || 0, bi = r.builtinChanged || 0;
        var bits = [];
        if (added) bits.push('新增 ' + added + ' 个');
        if (upd) bits.push('更新 ' + upd + ' 个');
        if (skip) bits.push('跳过 ' + skip + ' 个');
        if (fail) bits.push('失败 ' + fail + ' 个');
        if (bi) bits.push('内置设置 ' + bi + ' 项');
        var msg = bits.length ? ('导入完成：' + bits.join('，')) : '没有可导入的内容';

        var msgs = (r.messages || []).slice();

        // ★ v2.7：讲清楚导入的是「自家格式」还是「外部格式」。
        //   本 App 的导入侧是单向兼容的 —— 能读第三方源包，
        //   但自己导出的永远是带标识的 v2 包。
        // ★ v3.0：插件导入（jar/js）也不适用 —— 那是"装载插件"，不是"导入源配置"。
        if (r.from !== 'eprendre' && r.from !== 'js' && r.from !== 'jar') {
            if (r.ownPackage) {
                msgs.unshift('来源：枫兮听书专属格式（v2）');
            } else if (added + upd > 0) {
                msgs.unshift('来源：外部格式（兼容导入）。注意：本 App 导出的源包是' +
                             '专属格式，第三方软件无法读取，请勿外传。');
            }
        }

        // ★ v3.0：JS 脚本插件包 —— 讲清楚装的是哪个包、几个源
        if (r.from === 'js') {
            var jp = r.pkgName || '';
            var jv = r.pkgVersion ? (' v' + r.pkgVersion) : '';
            var ja = r.pkgAuthor ? (' · ' + r.pkgAuthor) : '';
            msgs.unshift('JS 插件包：' + (jp || r.file || '未命名') + jv + ja);
            if (added + upd === 0) {
                msgs.unshift('插件包没有装载出可用的书源，请确认包内有 manifest.json 且声明了 scripts。');
            }
        }

        // 原版「我的听书」订阅清单：讲清楚"这是插件包，已下载并装载"
        if (r.from === 'eprendre') {            var pkg = r.entryPackage || '';
            var sz = r.size ? ('（' + Math.round(r.size / 1024) + ' KB）') : '';
            var head = '识别为原版听书插件订阅';
            if (r.file) head += '：' + r.file + sz;
            msgs.unshift(head);
            if (pkg) msgs.unshift('插件包名：' + pkg);
            if (r.updateMsg) msgs.unshift('说明：' + r.updateMsg);
            if (r.downloadUrl) msgs.unshift('下载地址：' + r.downloadUrl);
            if (added + upd === 0 && fail) {
                msgs.unshift('插件包下载或加载失败，请确认下载地址可访问。');
            }
        }

        if (msgs.length) {
            var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
            h += '<div class="sheet" onclick="event.stopPropagation()">';
            h += '<div class="sheet-hd">导入结果</div>';
            h += '<div class="impmsg">' + TS.esc(msg) + '</div>';
            for (var i = 0; i < msgs.length && i < 20; i++) {
                h += '<div class="impitem">' + TS.esc(msgs[i]) + '</div>';
            }
            h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">知道了</div>';
            h += '</div></div>';
            sheet(h);
        } else {
            TS.toast(msg);
        }
        if (added || upd) {
            TS.call('listCustom');
            TS.call('listPlugins');
            // ★ v2.4：导入成功立刻同步首页 ——
            //   先重拉书源总表（刷新「我的」页统计与源管理列表），
            //   再重拉首页可浏览源（discoverSources）。
            //   原生 reload 是同步的，这两个请求返回时新源已在注册表里，
            //   首页无需用户手动刷新即可从"空态提示"切到真实分类书单。
            TS.call('listSources');
            TS.call('discoverSources');
            // 清掉浏览缓存，避免仍命中"无源"时留下的空结果
            try { ui.browseClearAll(); } catch (e) {}
            App.homeSources = null;
            App.home.src = '';
            App.home.cat = '';
            App.homePending = {};
            App.homePage = {};
            App.homeBooks = {};
            if (App.page === TABS.home) {
                // 先显示"正在同步"，等 discoverSources 回调进来再渲染真实内容
                var cl = $('homeContent');
                if (cl) cl.innerHTML =
                    '<div class="loading"><span class="spinner"></span>正在同步书源…</div>';
            }
        }
    };

    // ---------- 导出 / 分享 ----------

    ui.exportMenu = function () {
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">导出源</div>';
        h += '<div class="sheet-item" onclick="App.ui.doExport(false)">导出我的自定义源</div>';
        h += '<div class="sheet-item" onclick="App.ui.doShare()">分享订阅文件</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    ui.doExport = function (withBuiltin) {
        ui.closeSheet();
        App.exportWithBuiltin = !!withBuiltin;
        TS.call('exportSources', !!withBuiltin);
    };

    // ==================== ★ v3.1 批量验证书源 ====================
    //
    // 一次验证 19 个源的耗时在 10~40 秒之间（限流站还要等间隔），
    // 所以交互必须做成「弹面板 → 选范围 → 进度条 → 结果面板」四段，
    // 中间那一段绝不能是空白，否则用户会以为点了没反应。

    /** 批量验证的可选范围（分组多的时候不加新项，只有这三档） */
    function verifyScopes() {
        var a = [
            { k: 'all', t: '全部书源', d: '验证每一个源' },
            { k: 'enabled', t: '仅已启用的源', d: '跳过已停用的，快一些' }
        ];
        // 当前若正站在某个分组页，补一个「仅本组」——这是用户最想要的那档
        if (App.srcGroupKey && App.page === SUBS.srcGroup) {
            var def = groupDef(App.srcGroupKey);
            a.push({ k: 'group:' + App.srcGroupKey, t: '仅「' + def.t + '」',
                     d: '只验证这个分组下的源' });
        }
        return a;
    }

    ui.verifyMenu = function () {
        App.verifyScope = 'all';
        App.verifyAutoClose = true;
        App.verifyDedup = true;
        paintVerifyMenu();
    };

    function paintVerifyMenu() {
        var list = verifyScopes();
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">批量验证书源</div>';

        for (var i = 0; i < list.length; i++) {
            var it = list[i];
            var on = App.verifyScope === it.k;
            h += '<div class="sheet-item vscope' + (on ? ' on' : '') +
                 '" onclick="App.ui.pickVerifyScope(\'' + it.k + '\')">' +
                 (on ? '&#9679; ' : '&#9675; ') + TS.esc(it.t) +
                 '<span class="sheet-sub">' + TS.esc(it.d) + '</span></div>';
        }

        h += '<label class="impchk"><input type="checkbox" id="vfDedup"' +
             (App.verifyDedup ? ' checked' : '') +
             ' onchange="App.verifyDedup=this.checked"> 删除重复源（同域名保留优先级高的）</label>';
        h += '<label class="impchk"><input type="checkbox" id="vfClose"' +
             (App.verifyAutoClose ? ' checked' : '') +
             ' onchange="App.verifyAutoClose=this.checked"> 自动停用失效的源</label>';
        h += '<div class="impsub">验证会真实访问每个书源的搜索/分类接口，' +
             '耗时视源数量而定（十几个源约需十几秒）。</div>';
        h += '<div class="sheet-item" onclick="App.ui.doVerify()">开始验证</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    }

    ui.pickVerifyScope = function (k) {
        App.verifyScope = k;
        paintVerifyMenu();   // 重画一次，把选中圆点挪过去
    };

    ui.doVerify = function () {
        ui.closeSheet();
        App.verifyProg = { done: 0, total: 0, name: '', level: '', open: false };
        showVerifyBar();
        TS.call('verifySources', App.verifyScope || 'all',
                !!App.verifyAutoClose, !!App.verifyDedup);
    };

    // ---- ★ v3.1 非阻塞进度条 ----
    //
    // 原来这里弹的是全屏 sheet，验证十几个源要占屏十几秒，
    // 期间用户点哪都被挡住、也退不出去 —— 这是本轮用户明确提出的问题。
    //
    // 改成：顶部一条细进度条常驻，**不遮挡、不拦截任何点击**，
    // 用户可以照常翻页/听书。想看逐源日志时点它才展开，再点收起。

    /** 画出顶部那条常驻进度条（若已存在则只更新，不重建） */
    function showVerifyBar() {
        var bar = $('vfTop');
        if (!bar) {
            bar = document.createElement('div');
            bar.id = 'vfTop';
            bar.className = 'vf-top';
            bar.innerHTML =
                '<div class="vf-top-bar"><i id="vfTopFill"></i></div>' +
                '<div class="vf-top-txt" id="vfTopTxt" ' +
                'onclick="App.ui.toggleVerifyDetail()">' +
                '<span id="vfTopLabel">正在验证书源…</span>' +
                '<span class="vf-top-cnt" id="vfTopCount">0 / ?</span>' +
                '</div>' +
                '<div class="vflog hide" id="vfLog"></div>';
            document.body.appendChild(bar);
        }
        bar.classList.remove('hide');
        updateVerifyBar();
    }

    function updateVerifyBar() {
        var p = App.verifyProg || { done: 0, total: 0 };
        var f = $('vfTopFill');
        var c = $('vfTopCount');
        var pct = p.total ? Math.round(p.done * 100 / p.total) : 0;
        if (f) f.style.width = pct + '%';
        if (c) c.textContent = p.done + ' / ' + (p.total || '?');
        var l = $('vfTopLabel');
        if (l) {
            l.textContent = p.total
                ? '正在验证书源…' + (p.name ? ('　' + p.name) : '')
                : '正在取源列表…';
        }
    }

    /** 点进度条：展开/收起逐源日志（不影响上方的进度条本体） */
    ui.toggleVerifyDetail = function () {
        var p = App.verifyProg;
        if (!p) return;
        p.open = !p.open;
        var log = $('vfLog');
        if (log) log.classList[p.open ? 'remove' : 'add']('hide');
    };

    /** 动画收起：先把进度条推到 100% 再淡出，让用户看到"确实结束了" */
    function hideVerifyBar() {
        var bar = $('vfTop');
        if (!bar) return;
        var p = App.verifyProg;
        if (p) { p.done = p.total || p.done; updateVerifyBar(); }
        setTimeout(function () {
            if (bar) bar.classList.add('hide');
            var log = $('vfLog');
            if (log) { log.innerHTML = ''; log.classList.add('hide'); }
        }, 600);
    }

    /** 单个源的结论 → 中文短语（进度日志里用） */
    function verifyLevelText(lv) {
        if (lv === 'ok') return '正常';
        if (lv === 'browse') return '可浏览';
        if (lv === 'verify') return '需验证';
        if (lv === 'skip') return '内置跳过';
        return '失效';
    }

    window.onVerifyProgress = function (o) {
        if (!o) return;
        var p = App.verifyProg || (App.verifyProg = { done: 0, total: 0 });
        p.done = o.done || 0;
        p.total = o.total || 0;
        p.name = o.name || '';
        p.level = o.level || '';
        updateVerifyBar();

        var log = $('vfLog');
        if (log) {
            // 第一条进度回来时，清掉"正在取源列表…"那行占位文字
            if (p.done === 1) log.innerHTML = '';
            var bad = (o.level !== 'ok' && o.level !== 'skip' && o.level !== 'browse');
            var line = document.createElement('div');
            line.className = 'vfline ' + (bad ? 'bad' : 'ok');
            line.innerHTML = (bad ? '&#10007;' : '&#10003;') + ' ' + TS.esc(o.name || o.id) +
                '<span>' + verifyLevelText(o.level) + '</span>';
            log.appendChild(line);
            log.scrollTop = log.scrollHeight;
        }
    };

    window.onVerifyDone = function (r) {
        hideVerifyBar();
        // ★ 结果面板不能只报个数 —— 用户真正想知道的是"哪几个废了""哪几个被删了"。
        //   所以把 deadList / removedList / verifyList 原样列出来。
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">验证完成</div>';
        h += '<div class="cf-msg">' + TS.esc((r && r.summary) || '验证结束') + '</div>';

        h += verifyResultBlock('失效的源（' + ((r && r.dead) || 0) + '）',
                               r && r.deadList, 'dead');
        h += verifyResultBlock('需验证的源（' + ((r && r.verify) || 0) + '）',
                               r && r.verifyList, 'warn');
        h += verifyResultBlock('删除的重复源（' + ((r && r.deduped) || 0) + '）',
                               r && r.removedList, 'gone');

        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">关闭</div>';
        h += '</div></div>';
        sheet(h);

        // 结果里如果删过源，列表已经变了，把摘要也同步存下来给下次进页面用
        if (r && r.summary) App.lastVerifySummary = r.summary;
    };

    function verifyResultBlock(title, arr, cls) {
        if (!arr || !arr.length) return '';
        var h = '<div class="vfres ' + cls + '"><div class="vfres-hd">' + TS.esc(title) + '</div>';
        for (var i = 0; i < arr.length; i++) {
            h += '<div class="vfres-row">' + TS.esc(arr[i]) + '</div>';
        }
        h += '</div>';
        return h;
    }

    /** 点摘要行 → 把上次自动验证的结果以人话再说一遍 */
    ui.verifySummary = function () {
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">上次验证结果</div>';
        h += '<div class="cf-msg">' + TS.esc(App.lastVerifySummary || '暂无记录') + '</div>';
        h += '<div class="impsub">这是启动后自动静默验证的结论。' +
             '想重验可以点源管理页的「批量验证」。</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">关闭</div>';
        h += '</div></div>';
        sheet(h);
    };

    window.onSourceExported = function (o) {
        var json = (o && o.json) || '{}';
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">导出内容（' + ((o && o.count) || 0) + ' 个自定义源）</div>';
        // ★ v2.7：明确告诉用户导出的是枫兮专属格式，别人拿去用不了
        h += '<div class="exp-tip">枫兮听书专属格式（v2）。可导入本 App；' +
             '其它听书软件不认这个格式，请勿外传。</div>';
        h += '<textarea id="expText" class="ta">' + TS.esc(json) + '</textarea>';
        h += '<div class="sheet-item" onclick="App.ui.copyExport()">复制全部</div>';
        h += '<div class="sheet-item" onclick="App.ui.shareExport()">分享给别人</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">关闭</div>';
        h += '</div></div>';
        sheet(h);
    };

    ui.copyExport = function () {
        var el = $('expText');
        if (!el) return;
        try {
            el.select();
            el.setSelectionRange(0, 999999);
            if (document.execCommand('copy')) { TS.toast('已复制到剪贴板'); return; }
        } catch (e) {}
        TS.toast('复制失败，请长按文本框手动复制');
    };

    ui.shareExport = function () {
        ui.closeSheet();
        TS.call('shareSources', !!App.exportWithBuiltin);
    };

    ui.doShare = function () {
        ui.closeSheet();
        TS.call('shareSources', true);
    };

    /** 统一挂弹层 */
    function sheet(html) {
        ui.closeSheet();
        var d = document.createElement('div');
        d.id = 'sheetWrap';
        d.innerHTML = html;
        document.body.appendChild(d);
    }

    // ==================== 通用确认 / 输入 弹窗（v2.8） ====================
    //
    // 为什么不用系统 confirm / prompt：
    //   1) WebView 未设置 WebChromeClient 时，confirm 恒返回 false、prompt 恒返回 null，
    //      调用点会「静默短路」，表现为"点了按钮没反应"（v2.8 之前缓存整本就是这样失效的）。
    //   2) 即便补上 WebChromeClient，系统框也是 Material 白底，与本 App 的深色 .sheet
    //      风格割裂，深色主题下尤其刺眼。
    // 所以统一改走自绘弹窗。原生 WebChromeClient 仍保留作为兜底（防第三方 JS 漏网）。

    /**
     * 确认框。msg 为提示文案，onOk 为用户点「确定」后的回调（点取消不调用）。
     */
    ui.confirmSheet = function (msg, onOk) {
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">请确认</div>';
        h += '<div class="cf-msg">' + TS.esc(msg).replace(/\n/g, '<br>') + '</div>';
        h += '<div class="sheet-item danger" id="cfOk">确定</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
        var ok = $('cfOk');
        if (ok) ok.onclick = function () {
            ui.closeSheet();
            if (onOk) onOk();
        };
    };

    /**
     * 单行文本输入框。title 为标题，def 为默认值，onOk(value) 在点「确定」后回调。
     * 返回空串也回调（由调用方决定是否忽略），与 prompt 语义一致。
     */
    ui.inputSheet = function (title, def, onOk) {
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">' + TS.esc(title) + '</div>';
        h += '<input id="ipVal" class="sheet-input" type="text" value="' +
             escAttr(def == null ? '' : def) + '">';
        h += '<div class="sheet-item" id="ipOk">确定</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);

        var v = $('ipVal');
        var ok = $('ipOk');
        // 部分 ROM 上立即 focus 不弹软键盘，延后一拍再试
        if (v) {
            setTimeout(function () {
                try { v.focus(); v.setSelection(v.value.length); } catch (e) {}
            }, 50);
        }
        if (ok) ok.onclick = function () {
            var s = v ? v.value : '';
            ui.closeSheet();
            if (onOk) onOk(s);
        };
    };

    // ==================== 发现（分类浏览） ====================

    // 源与分类清单由原生动态提供（内置源 / 配置源 / 插件源都能出现在发现页）
    App.discoverSources = null;   // null = 还没拿到
    App.disc = { src: '', cat: '', page: 1 };

    ui.discover = function () {
        goSub('discover');
        if (App.discoverSources) renderDiscover();
        else {
            $('discoverCats').innerHTML = '';
            $('discoverList').innerHTML =
                '<div class="loading"><div class="spinner"></div>正在获取可浏览的源…</div>';
            TS.call('discoverSources');
        }
    };

    /** 取当前选中的源配置，必要时回落到第一个 */
    function currentDisc() {
        var list = App.discoverSources || [];
        if (!list.length) return null;
        var cfg = null;
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === App.disc.src) cfg = list[i];
        }
        if (!cfg) {
            cfg = list[0];
            App.disc.src = cfg.id;
            App.disc.cat = (cfg.cats && cfg.cats.length) ? cfg.cats[0].k : '';
            App.disc.page = 1;
        }
        // 分类也要校验：源切换后旧分类可能不存在
        var catOk = false;
        for (var j = 0; cfg.cats && j < cfg.cats.length; j++) {
            if (cfg.cats[j].k === App.disc.cat) catOk = true;
        }
        if (!catOk) {
            App.disc.cat = (cfg.cats && cfg.cats.length) ? cfg.cats[0].k : '';
            App.disc.page = 1;
        }
        return cfg;
    }

    function renderDiscover() {
        var cd = $('discoverCats');
        var cl = $('discoverList');
        if (!cd) return;

        var list = App.discoverSources || [];
        if (!list.length) {
            cd.innerHTML = '';
            if (cl) {
                cl.innerHTML = '<div class="empty"><div class="big">📚</div>没有可浏览的源<br>' +
                    '<span style="font-size:12.5px">去「书源管理」启用支持分类的源，' +
                    '或导入带 browse 配置的自定义源</span></div>';
            }
            return;
        }

        var cfg = currentDisc();

        // ★ v2.5：源与分类都放在**同一行**里横滑（不再让分类折成多行）。
        //   两者之间插一条细分隔线区分「源 / 分类」两个区段。
        var h = '';
        for (var i = 0; i < list.length; i++) {
            var on = App.disc.src === list[i].id;
            h += '<div class="chip' + (on ? ' on' : '') +
                 '" data-src="' + escAttr(list[i].id) +
                 '" onclick="App.ui.discSrc(\'' + escAttr(list[i].id) + '\')">' +
                 TS.esc(list[i].name) + '</div>';
        }
        // 分类 chips（同一个横滑条里，前面加个竖线分隔）
        var cats = (cfg && cfg.cats) || [];
        if (cats.length) {
            h += '<div class="chip-sep"></div>';
            for (var j = 0; j < cats.length; j++) {
                var c = cats[j];
                h += '<div class="chip' + (App.disc.cat === c.k ? ' on' : '') +
                     '" data-cat="' + escAttr(c.k) +
                     '" onclick="App.ui.discCat(\'' + escAttr(c.k) + '\')">' +
                     TS.esc(c.n) + '</div>';
            }
        }
        cd.innerHTML = h;
        // 选中项滚到中间（首尾自动贴边）
        centerChip(cd, App.disc.cat, '[data-cat]');

        if (cl) cl.innerHTML = '<div class="loading"><span class="spinner"></span>加载中…</div>';
        if (cfg && cfg.id) TS.call('browse', cfg.id, App.disc.cat, App.disc.page);
    }

    ui.discSrc = function (sid) {
        App.disc.src = sid;
        App.disc.page = 1;
        var list = App.discoverSources || [];
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === sid) {
                App.disc.cat = (list[i].cats && list[i].cats.length)
                    ? list[i].cats[0].k : '';
            }
        }
        renderDiscover();
    };

    ui.discCat = function (k) {
        App.disc.cat = k; App.disc.page = 1;
        renderDiscover();
    };

    window.onBrowse = function (o) {
        // 成功结果写进缓存（切源/切分类/回首页都不再重新请求）
        if (o && !o.error && o.books && o.books.length) {
            browseSave(o.source, o.path, o.page, o.books);
        }
        // 首页：只渲染"当前正在看的那个分类"，其它分类的迟到结果直接进缓存备用
        if (App.page === TABS.home) {
            // ★ v3.7.5：「加载更多」这一路：追加到已有列表
            var w = App.homeMoreWait;
            App.homeMoreBusy = false;
            App.homeMoreSince = 0;
            // ★ v3.7.10：无论这次结果是否被消费，都要把按钮从「加载中…」恢复回来。
            //   原来只在两个 if 分支内部才会重绘按钮，一旦 o.path / o.page 与
            //   当前首页状态对不上（换源瞬间、迟到结果、类型不一致），按钮就永远
            //   停在「加载中…」且 disabled —— 用户再点也没反应，看起来就是"失效"。
            var consumed = false;
            if (w && o && o.source === w.src && o.path === w.cat && o.page === w.page) {
                App.homeMoreWait = null;
                consumed = true;
                if (o.error) {
                    TS.toast(o.error);
                    // 出错就把 hasMore 收掉，避免反复重试死循环
                    App.homeHasMore = false;
                    paintHomeMore(0, w.page, 0);
                    return;
                }
                renderHomeList(o.books, o.source, o.path, o.page, true);
                return;
            }
            if (o && o.source === App.home.src && o.path === App.home.cat) {
                consumed = true;
                delete App.homePending[o.path];
                if (o.error) { $('homeContent').innerHTML = errBlock(o.error); return; }
                renderHomeList(o.books, o.source, o.path, o.page);
                return;
            }
            // 没被任何分支消费（迟到 / 对不上）→ 至少把按钮状态修回来
            if (!consumed) {
                App.homeMoreWait = null;
                fixHomeMoreBtn();
            }
            return;
        }
        // 发现页等其它场景走原来的整块渲染
        paintBrowseResult(o);
    };

    // 首页每行已加载的数据，供"展开更多"用
    App.homeBooks = {};

    // ==================== 离线缓存 ====================

    ui.openCache = function () {
        goSub('cache');
        var c = $('cacheContent');
        if (c) c.innerHTML = '<div class="loading"><span class="spinner"></span>加载中…</div>';
        TS.call('cacheList');
    };

    // ==================== 账号（接口预留） ====================
    // 本期只做入口与交互占位，真正的注册/登录服务后续接入。
    // 约定：native 侧实现 accountInfo / accountLogin / accountLogout /
    //       accountRegister 四个方法并回调 onAccount* 即可，前端无需改动。

    App.account = App.account || { user: null };

    /** 已登录时刷新卡片上的昵称/头像 */
    function paintAccount() {
        var u = App.account && App.account.user;
        var card = $('accountCard');
        var icon = $('accIcon');
        var title = $('accTitle');
        var sub = $('accSub');
        if (!card) return;
        if (u && (u.name || u.nick)) {
            var nm = u.nick || u.name;
            card.classList.add('logged');
            if (icon) icon.textContent = nm.charAt(0).toUpperCase();
            if (title) title.textContent = nm;
            if (sub) sub.textContent = u.email || '已登录 · 数据同步已开启';
        } else {
            card.classList.remove('logged');
            if (icon) icon.innerHTML = '&#128100;';
            if (title) title.textContent = '未登录';
            if (sub) sub.textContent = '登录后可同步书源、收藏与收听进度';
        }
    }

    ui.openAccount = function () {
        var u = App.account && App.account.user;
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">账号</div>';
        if (u) {
            h += '<div class="sheet-item" onclick="App.ui.accountLogout()">退出登录</div>';
        } else {
            h += '<div class="sheet-item" onclick="App.ui.accountLogin()">登录</div>';
            h += '<div class="sheet-item" onclick="App.ui.accountRegister()">注册新账号</div>';
        }
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    // ==================== 支持开发者（赞赏） ====================

    /**
     * 赞赏金额档位。
     *
     * <p><b>为什么只是"展示"而不是"发起支付"</b>：
     * 1) 收款码方案本身就没有下单能力，金额最终由扫码方自己输入；
     * 2) 真正的"选金额→拉起支付"需要接入支付 SDK / 商户资质，
     *    那会把事情从"自愿赠与"推向"经营行为"，反而增加合规负担；
     * 3) 这里的档位只起一个引导作用，点选后提示"请按该金额扫码"。
     */
    var REWARD_TIERS = [
        { v: 6.66, k: '顺顺利利' },
        { v: 8.88, k: '发发发' },
        { v: 18.88, k: '要发发发' },
        { v: 66.66, k: '六六大顺' }
    ];
    var rewardTier = -1;

    /**
     * 赞赏码图片路径。
     *
     * <p><b>怎么换成你自己的码</b>：把两张收款码图片放到
     * <code>assets/www/reward/</code> 目录下，命名
     * <code>wechat.png</code> / <code>alipay.png</code> 即可，
     * 无需改任何代码。文件不存在时页面会显示占位提示，不会报错。
     */
    var REWARD_IMGS = {
        wechat: 'reward/wechat.png',
        alipay: 'reward/alipay.png'
    };

    ui.openReward = function () {
        goSub('reward');
        renderReward();
    };

    function renderReward() {
        // 金额档位
        var box = $('rwAmounts');
        if (box) {
            var h = '';
            for (var i = 0; i < REWARD_TIERS.length; i++) {
                var t = REWARD_TIERS[i];
                h += '<div class="rw-amt' + (rewardTier === i ? ' on' : '') +
                     '" onclick="App.ui.pickReward(' + i + ')">' +
                     '<div class="v"><small>¥</small>' + t.v.toFixed(2) + '</div>' +
                     '<div class="k">' + TS.esc(t.k) + '</div></div>';
            }
            box.innerHTML = h;
        }
        // 收款码：探测图片是否存在，不存在就显示占位提示（不报错）
        loadRewardImg($('rwImgWx'), REWARD_IMGS.wechat, '微信');
        loadRewardImg($('rwImgAli'), REWARD_IMGS.alipay, '支付宝');
    }

    /** 加载收款码；404/解码失败时降级为占位虚线框 */
    function loadRewardImg(img, url, label) {
        if (!img) return;
        var holder = img.parentNode;
        var markFail = function () {
            if (holder && holder.classList) holder.classList.add('placeholder');
            if (holder) {
                var tip = holder.querySelector('.rw-ph');
                if (!tip) {
                    tip = document.createElement('div');
                    tip.className = 'rw-ph';
                    tip.innerHTML = '尚未放置' + label + '收款码<br>' +
                                    '（把图片放到 ' + TS.esc(url) + '）';
                    holder.appendChild(tip);
                }
            }
            img.style.display = 'none';
        };
        img.onload = function () {
            if (holder && holder.classList) holder.classList.remove('placeholder');
            // 用二维码本身的自然宽高判断是否是"有效图片"
            if (!img.naturalWidth) markFail();
        };
        img.onerror = markFail;
        img.src = url;
    }

    ui.pickReward = function (i) {
        rewardTier = (rewardTier === i) ? -1 : i;   // 再点一次取消
        renderReward();
        if (rewardTier >= 0) {
            TS.toast('已选 ¥' + REWARD_TIERS[rewardTier].v.toFixed(2) +
                     '，长按下方收款码扫码即可');
        }
    };

    /** 预留：登录。把 sheet 关掉再请求，避免弹层挡着后续界面。 */
    ui.accountLogin = function () {
        ui.closeSheet();
        TS.call('accountLogin');
    };

    /** 预留：注册 */
    ui.accountRegister = function () {
        ui.closeSheet();
        TS.call('accountRegister');
    };

    ui.accountLogout = function () {
        ui.closeSheet();
        TS.call('accountLogout');
    };

    // ---- native 回调（后续接服务时直接复用） ----

    window.onAccountInfo = function (o) {
        App.account.user = (o && o.user) ? o.user : null;
        paintAccount();
    };

    window.onAccountChanged = function (o) {
        if (o && o.ok) {
            App.account.user = o.user || null;
            paintAccount();
            TS.toast(o.message || (o.user ? '已登录' : '已退出登录'));
        } else {
            TS.toast((o && o.message) || '操作未完成');
        }
    };

    /** 账号功能尚未接入时的统一回执 */
    window.onAccountUnavailable = function () {
        TS.toast('账号功能即将上线，敬请期待');
    };

    window.paintAccount = paintAccount;

    window.onCacheList = function (arr) {
        var c = $('cacheContent');
        if (!c) return;
        arr = arr || [];
        if (!arr.length) {
            c.innerHTML = '<div class="empty"><div class="big">📦</div>还没有缓存任何内容<br>' +
                '<span style="font-size:12.5px">在详情页点「缓存全部」，或点章节右侧的 ⬇ 单集下载</span></div>';
            return;
        }
        var h = '';
        for (var i = 0; i < arr.length; i++) {
            var it = arr[i];
            h += '<div class="srcrow"><div class="srcinfo">' +
                 '<div class="srcname">' + TS.esc(it.title || it.key) + '</div>' +
                 '<div class="srcdesc">' + TS.esc(it.info || '') + '</div></div>' +
                 '<button class="mini" onclick="App.ui.delCache(\'' + escAttr(it.key) + '\')">删除</button>' +
                 '</div>';
        }
        c.innerHTML = h;
    };

    // ==================== ★ v2.8 播放悬浮球 ====================

    /** 开关：先问原生（可能拉起系统授权页），结果由 onFloatBallState 回推 */
    ui.toggleFloatBall = function (on) {
        TS.call('setFloatBall', !!on);
    };

    /** 原生回推的悬浮球状态 */
    window.onFloatBallState = function (o) {
        o = o || {};
        App.floatBall = o;
        var el = $('setFloatBall');
        if (el) el.checked = !!o.enabled;
        // 想开但没授权 → 说明用户被弹去了授权页，给一句提示
        if (o.enabled && !o.canDraw) {
            TS.toast('请在系统设置里允许「显示在其他应用上层」');
        }
    };

    /** 从授权页返回后的复查结果 */
    window.onFloatBallStateRefresh = function (canDraw) {
        // 主动拉一次完整状态（含 enabled），前端开关便能同步
        TS.call('floatBallState');
        if (canDraw && App.floatBall && App.floatBall.enabled && !App.floatBall.canDraw) {
            TS.toast('悬浮窗权限已开启');
        }
    };

    /** 权限失效时的降级提示 */
    window.onFloatBallDenied = function () {
        var el = $('setFloatBall');
        if (el) el.checked = false;
        TS.toast('悬浮窗权限已失效，悬浮球已关闭');
    };

    /** 点悬浮球回到 App → 直接进全屏播放页 */
    window.onOpenFull = function () {
        if (!App.book && !App.chapters.length) return;
        goSub('full');
    };

    window.onCacheProgress = function (o) {
        o = o || {};
        // ★ v2.8：整本缓存时逐集推进 —— 顺便把章节行的下载图标点亮
        if (o.index) {
            App.cacheStatus = App.cacheStatus || {};
            App.cacheStatus[o.index] = 2;
            paintDlIcon(o.index, 2);
        }
        // toast 节流：几百集时每集弹一次会刷屏，这里降频到约 2 秒一次
        var now = Date.now();
        if (!App._lastCacheToast || now - App._lastCacheToast > 2000 ||
                (o.done || 0) >= (o.total || 0)) {
            App._lastCacheToast = now;
            TS.toast('缓存《' + (o.title || '') + '》 ' + (o.done || 0) + '/' + (o.total || 0));
        }
    };

    window.onCacheDone = function (o) {
        o = o || {};
        TS.toast('缓存完成：' + (o.done || 0) + ' 集');
        if (App.page === SUBS.cache) TS.call('cacheList');
    };

    ui.delCache = function (key) { TS.call('cacheDelete', key); };
    ui.clearCache = function () {
        ui.confirmSheet('清空所有离线缓存？已下载的音频文件会被删除。', function () {
            TS.call('cacheClear');
        });
    };

    // ==================== 主题 ====================

    App.theme = 'system';

    ui.setTheme = function (mode) {
        App.theme = mode;
        TS.call('setTheme', mode);
        paintTheme();
    };

    function paintTheme() {
        var ids = { system: 'thSystem', light: 'thLight', dark: 'thDark' };
        for (var k in ids) {
            var el = $(ids[k]);
            if (el) {
                el.className = 'seg-item' + (App.theme === k ? ' on' : '');
            }
        }
    }

    // ==================== 分享 ====================

    ui.shareBook = function () {
        if (!App.book) return;
        TS.call('shareBook', JSON.stringify(App.book));
    };

    ui.shareApp = function () {
        TS.call('shareText',
            '推荐一个超好用的听书 App「枫兮听书」\n' +
            '聚合多个免费书源，支持离线缓存、倍速播放、定时关闭，无广告无账号。');
    };

    // ==================== 启动页 ====================

    function hideSplash() {
        var sp = $('splash');
        if (!sp) return;
        sp.classList.add('gone');
        setTimeout(function () { if (sp.parentNode) sp.parentNode.removeChild(sp); }, 500);
    }

    // ==================== 设置 ====================

    function syncSettings() { TS.call('getSettings'); }

    window.onSettings = function (o) {
        o = o || {};
        App.speed = o.speed || 1.0;
        paintSpeed();
        // ★ v3.1：增益与打断策略
        App.gain = o.gain || 100;
        App.audioFocusMode = o.audioFocusMode || 0;
        paintGain();
        paintFocusMode();
        // ★ v3.2：启动自动验证开关已移除，这里只接上次验证的摘要
        App.lastVerifySummary = o.lastVerifySummary || '';
        if (App.page === SUBS.sources) paintSources();
        setCheck('setWifiPlay', o.wifiPlayOnly);
        setCheck('setPlayOnFull', o.playOnFull !== false);
        setCheck('setWifiCache', o.wifiCacheOnly);
        setCheck('setAutoCache', o.autoCacheEnabled);
        setVal('setAutoCacheCount', o.autoCacheCount || 3);
        setVal('setCacheLimit', o.cacheLimitMb || 2048);
        setVal('setFf', o.ffSeconds || 30);
        App.playOnFull = (o.playOnFull !== false);
        // ★ v2.8：悬浮球开关的真实状态以原生为准（权限可能被系统收走）
        TS.call('floatBallState');
        // ★ v2.9：下载目录状态也从原生拉一次（设置页要显示当前目录）
        TS.call('downloadDirState');
    };

    function setCheck(id, v) { var el = $(id); if (el) el.checked = !!v; }
    function setVal(id, v) { var el = $(id); if (el) el.value = v; }

    ui.setBool = function (key, v) { TS.call('setSetting', key, v ? 'true' : 'false'); };
    ui.setNumber = function (key, v) {
        var n = parseInt(v, 10);
        if (!isNaN(n)) TS.call('setSetting', key, String(n));
    };

    // ==================== 下载 ====================

    /**
     * ★ v2.9：下载整本。原来是「缓存」语义，现改为纯粹的「下载」。
     * 文案里带上目标目录，让用户明确知道东西会落到哪。
     */
    ui.downloadAll = function () {
        if (!App.book || !App.chapters || !App.chapters.length) return;

        // 已经全下过了就不再重复（避免白跑一遍网络）
        var total = App.chapters.length;
        var done = 0;
        for (var i = 0; i < total; i++) {
            if ((App.cacheStatus && App.cacheStatus[chapNo(i)]) === 2) done++;
        }
        if (done >= total) {
            TS.toast('《' + App.book.title + '》已全部下载');
            return;
        }

        var where = App.dlDirName ? ('保存到「' + App.dlDirName + '」') : '保存在 App 内';
        ui.confirmSheet('下载《' + App.book.title + '》共 ' + total + ' 集？\n' +
                        '将占用约 ' + Math.max(1, Math.ceil(total * 12 / 1024)) +
                        'GB 空间，建议在 WiFi 下进行。\n' + where, function () {
            TS.call('downloadBook', JSON.stringify(App.book));
        });
    };

    // ---- 下载目录设置 ----

    ui.pickDlDir = function () { TS.call('pickDownloadDir'); };
    ui.clearDlDir = function () { TS.call('clearDownloadDir'); };

    /** 原生：下载目录状态（选完 / 清空 / 初始化都会回调到这里） */
    window.onDownloadDir = function (o) {
        o = o || {};
        if (o.cancel) return;                      // 用户点了取消，什么都不做
        if (o.ok === false) {
            if (o.error) TS.toast('无法打开文件夹选择器');
            return;
        }
        App.dlDirName = o.name || '';
        App.dlDirUri = o.uri || '';

        var desc = $('dlDirDesc');
        if (desc) {
            desc.textContent = (o.uri && o.name)
                ? ('保存到「' + o.name + '」')
                : '仅 App 内（其它播放器看不到）';
        }
        var row = $('dlDirResetRow');
        if (row) row.style.display = (o.uri && o.uri.length) ? '' : 'none';
    };

    /** 打开下载管理页 */
    ui.openDlManage = function () {
        goSub('dlmanage');
        TS.call('downloadList');
    };

    /** 原生：下载清单 */
    window.onDownloadList = function (o) {
        o = o || {};
        var box = $('dlManageBody');
        if (!box) return;
        var books = o.books || [];
        var h = '';
        h += '<div class="dlm-sum">共 ' + (o.totalCount || 0) + ' 集 · ' +
             (o.totalText || '0 B') + '</div>';
        if (o.hasDir) {
            h += '<div class="dlm-dir">外部目录：' + TS.esc(o.dirName || '') + '</div>';
        }
        if (!books.length) {
            h += '<div class="empty"><div class="big">📥</div>还没有下载任何内容</div>';
        } else {
            for (var i = 0; i < books.length; i++) {
                var b = books[i];
                h += '<div class="dlm-item">';
                h += '<div class="dlm-info"><div class="dlm-t">' + TS.esc(b.title || b.key) + '</div>';
                h += '<div class="dlm-s">' + b.count + ' 集 · ' + TS.esc(b.sizeText || '') + '</div></div>';
                h += '<button class="btn-mini danger" onclick="App.ui.dlRemove(\'' +
                     escAttr(b.key) + '\',\'' + escAttr(b.title || '') + '\')">删除</button>';
                h += '</div>';
            }
            h += '<div class="dlm-acts"><button class="btn-mini danger" onclick="App.ui.dlClearAll()">' +
                 '全部删除</button></div>';
        }
        box.innerHTML = h;
    };

    ui.dlRemove = function (key, title) {
        ui.confirmSheet('删除《' + (title || '') + '》的全部下载？\n' +
                        '（只删 App 内的文件，已移动到外部文件夹的不受影响）', function () {
            TS.call('downloadDelete', key);
        });
    };

    ui.dlClearAll = function () {
        ui.confirmSheet('清空全部下载？此操作不可恢复。\n' +
                        '（只删 App 内的文件，外部文件夹里的不受影响）', function () {
            TS.call('downloadClearAll');
        });
    };

    // ==================== 设备信息 ====================

    window.onDeviceInfo = function (o) {
        App.canSpeed = !o || o.canSpeed !== false;
        if (o && o.theme) { App.theme = o.theme; paintTheme(); }
        // 版本号用 native 回传的真实值，别再写死在 HTML 里。
        // ★ v2.5：页面上有两个版本位 —— 设置页的 appVer 与关于页的 aboutVer，都要同步。
        if (o && o.version) {
            App.version = o.version;
            if ($('appVer')) $('appVer').textContent = o.version;
            if ($('aboutVer')) $('aboutVer').textContent = o.version;
            if ($('updCurVer')) $('updCurVer').textContent = o.version;
            if ($('aboutEntrySub')) $('aboutEntrySub').textContent =
                '版本 ' + o.version + ' · 更新与交流';
        }
        if ($('netInfo')) {
            var v6 = o && o.ipv6;
            $('netInfo').textContent = (o && o.netText) ||
                (v6 ? 'IPv6 已开启' : 'IPv4（未检测到 IPv6）');
        }
        if (!App.canSpeed) {
            App.speed = 1.0;
            var sl = $('speedList');
            if (sl) sl.classList.add('hide');
        }
    };

    // ==================== 设置 / 关于（二级页） ====================

    /** QQ 群号：更新与反馈入口。改这一处即可全局生效 */
    var QQ_GROUP = '1129386208';

    ui.openSettings = function () {
        goSub('settings');
        // 进页面重新拉一次设置，保证开关显示的是最新值
        syncSettings();
    };

    ui.openAbout = function () {
        goSub('about');
        // 网络 / 版本信息在 about 页里也有，进页面时重拉一次
        TS.call('deviceInfo');
        var el = $('qqGroupNo');
        if (el) el.textContent = QQ_GROUP;
    };

    /** 复制 QQ 群号到剪贴板（原生剪贴板，复制成功给个 toast） */
    ui.copyGroup = function () {
        try {
            TS.call('copyText', QQ_GROUP);
            TS.toast('群号已复制：' + QQ_GROUP);
        } catch (e) {
            TS.toast('群号：' + QQ_GROUP);
        }
    };

    // ==================== ★ v3.7.9 关于页：更新检查 / 开源链接 ====================

    /** 仓库地址（与原生 checkUpdate 里查的仓库保持一致） */
    var GH_REPO = 'https://github.com/fengxitingshu/fengxi-tingshu';
    var GH_ISSUES = GH_REPO + '/issues';

    /** 交给系统浏览器打开仓库主页 */
    ui.openGitHub = function () {
        TS.call('openUrl', GH_REPO);
    };

    /** 打开 issue 列表 */
    ui.openGitHubIssues = function () {
        TS.call('openUrl', GH_ISSUES);
    };

    /**
     * 检查更新：问原生要 GitHub 最新 release，再按结果刷新卡片。
     * 原生那头已经做了超时与失败兜底，这里只管把结果画出来。
     */
    ui.checkUpdate = function () {
        var btn = $('updCheckBtn');
        var msg = $('updMsg');
        if (btn) { btn.disabled = true; btn.textContent = '检查中…'; }
        if (msg) msg.innerHTML = '正在向 GitHub 查询最新版本…';
        TS.call('checkUpdate');
    };

    /** 更新检查结果回调（原生 onUpdateResult） */
    window.onUpdateResult = function (o) {
        o = o || {};
        var btn = $('updCheckBtn');
        var msg = $('updMsg');
        var acts = $('updActions');
        if (btn) { btn.disabled = false; btn.textContent = '检查更新'; }

        var cur = o.local || App.version || '';

        if (!o.ok) {
            if (msg) msg.innerHTML = '检查失败：' + TS.esc(o.error || '网络不可用') +
                '<br><span style="opacity:.7">' + TS.esc(cur ? '当前版本 ' + cur : '') +
                '，可稍后重试，或直接去仓库看最新发布。</span>';
            return;
        }

        if (o.hasNew) {
            if (msg) msg.innerHTML = '发现新版本 <b>' + TS.esc(o.latest) + '</b>' +
                '（当前 ' + TS.esc(cur) + '）<br>' +
                '<span style="opacity:.75">去 GitHub Releases 下载最新安装包。</span>';
            // 有新版时把按钮换成"去下载"，直接开 release 页
            if (acts && !$('updGoBtn')) {
                var b = document.createElement('button');
                b.className = 'mini';
                b.id = 'updGoBtn';
                b.textContent = '去下载';
                b.onclick = function () {
                    TS.call('openUrl', o.url || (GH_REPO + '/releases'));
                };
                acts.appendChild(b);
            }
        } else {
            if (msg) msg.innerHTML = '已是最新版本 <b>' + TS.esc(o.latest || cur) +
                '</b>，无需更新。';
        }
    };

    // ==================== ★ v3.7.3 更新公告 ====================
    //
    // 只写「修复了哪些问题 / 增加了哪些功能」两件事，不写实现过程。
    // 最新版本放数组第一个，超出 1 条的收进「历史版本」折叠区。

    var CHANGELOG = [
        {
            ver: '3.7.11',
            date: '2025-10-11',
            fixes: [
                '★ 彻底修复首页「加载更多」只变页码、不加载内容的老问题：源的第二页返回与上一页重复的书时，页码会被虚增一格而书一本没多。现在页码只在真的有新内容时前进；零新增会自动往后连翻（最多 5 页），期间显示「正在跳过重复内容…」，翻到底明确提示「后面几页都是重复内容，先到这」',
                '修复 Python 书源「读出来的内容与标题不符」：章节页地址被误判成音频直链，导致取链接口根本没被调用，播放器拿到的是网页而是音频。现在按后缀区分页面/音频，只认真正的音频直链，其余交回源取链（同时修复章节地址含 # 被切碎、丢失播放线路名两个连带问题）',
                '优化播放页集数（当前/总数）与外层括号不协调的观感：括号降字重、缩字号，数字等宽、当前集最重，斜杠居中，并与右侧主播名胶囊上下居中对齐',
                '历史记录去掉右上角的删除「✕」，避免误删'
            ],
            adds: [
                '历史记录支持长按卡片，在按下的位置弹出菜单：删除这条记录 / 换源播放 / 搜索这本书',
                '收藏页的收藏书籍换个显示方式：改成「书本封面」书架样式（书脊、光泽、竖版比例），更贴合整体暖橙主题',
                '收藏页同样支持长按书本弹出菜单：取消收藏 / 换源播放 / 搜索这本书（与历史记录同一套交互）',
                '新增系统桌面小组件：可在桌面添加播放控制面板，显示封面、书名/主播、当前集数与进度条，并支持上一集 / 播放暂停 / 下一集；已适配原子随身听，可直接用系统随身听控制播放'
            ]
        },
        {
            ver: '3.7.10',
            date: '2025-10-11',
            fixes: [
                '修复首页「加载更多」只跳页码、看不到新书的问题（加载完成后自动滚到新内容，新增卡片带入场高亮，一眼能看出多了一批）',
                '修复「加载更多」在源返回重复内容时按钮卡在「加载中…」再也点不动的问题（按钮状态现在一定会复位）',
                '修复部分书源因去重字段单一导致翻页内容被整页丢弃、书数不增的问题（去重改用书名+链接组合判定）'
            ],
            adds: [
                '播放页「选集」新增列数切换（1 / 2 / 3 列），点按钮下拉选择，不占地方',
                '播放页「选集」新增区间切换（每 200 集一段），点按钮下拉选择，直达目标段',
                '选集的列数与区间和详情页共用同一份偏好，改一处两处都生效',
                '书源管理页的操作按钮整体加大一档，文字改为严格上下居中'
            ]
        },
        {
            ver: '3.7.9',
            date: '2025-10-11',
            fixes: [
                '修复首页「加载更多」点了没反应的问题（按钮反馈与真实加载状态现在一致）',
                '修复每次冷启动都要等很久才出书源的问题（Python 书源改为后台初始化，不再卡住启动）',
                '修复播放页集数括号与主播名挤在一起、看着不协调的问题'
            ],
            adds: [
                '章节列表新增「1 / 2 / 3 列」显示切换，长列表一屏能看更多',
                '章节列表新增「区间」切换，每 200 集一段，可直接跳段不用一路滑',
                '关于页新增「检查更新」，可一键查询最新发布版本',
                '关于页新增 GitHub 仓库与问题反馈入口',
                'Python 书源在后台就绪后会自动补进源列表，并提示已就绪数量'
            ]
        },
        {
            ver: '3.7.8',
            date: '2025-10-11',
            fixes: [],
            adds: [
                '安装包代码加固：内部代码重命名混淆，反编译可读性大幅下降',
                '应用体积小幅减小，构建流程新增混淆映射表（用于排查问题）'
            ]
        },
        {
            ver: '3.7.7',
            date: '2025-10-11',
            fixes: [
                '修复首页书源加载太慢的问题（分类信息改为并发获取 + 10 分钟缓存）',
                '修复「加载更多」只变了本数、看不到新书的问题（新增内容会自动滚到视野里）',
                '修复播放页书源名和收藏按钮位置松散、收藏按钮背板发白不协调的问题',
                '修复书源分组「全开 / 全关」两个按钮糊成一块、分不清的问题'
            ],
            adds: [
                '分组「全开 / 全关」改成两个独立按钮，并带文字标签',
                '「全开 / 全关」用颜色标示当前模式：全开时绿色点亮，全关时灰色点亮，混合状态都不点亮'
            ]
        },
        {
            ver: '3.7.6',
            date: '2025-10-11',
            fixes: [
                '修复首页每次打开都是空白、要手动点一下源才有内容的问题（现在自动加载上次用的源和分类）',
                '修复首页「加载更多」只能加载到 20 本、再点就没反应的问题',
                '修复播放页右上角收藏按钮看不清的问题（改成带底色的圆钮，已收藏时橙色高亮）'
            ],
            adds: [
                '书源管理的「全部开启 / 全部关闭」按钮移到橙色顶栏右上角，位置更顺手',
                '播放页集数改成纯数字括号显示，如 (3/120)，不再占用一长串汉字'
            ]
        },
        {
            ver: '3.7.5',
            date: '2025-10-11',
            fixes: [
                '修复首页一个分类只显示几本书、往下滑就没内容的问题（现在可以一直加载更多）',
                '修复首页加载缓慢、切换分类卡顿的问题',
                '修复播放页右上角书源名、功能按钮（选集/定时/更多）文字看不清的问题',
                '修复播放页主播名看不清的问题'
            ],
            adds: [
                '悬浮球封面、播放页圆盘封面在播放时缓慢旋转；暂停时停住，从当前角度继续转',
                '当前播放时长改为跟随进度条滑块移动，拖动时实时跟手',
                '书源管理分组顶栏新增「全部开启 / 全部关闭」一键切换（实心/空心圆图标）'
            ]
        },
        {
            ver: '3.7.4',
            date: '2025-10-10',
            fixes: [
                '修复播放页部分文字（标题、时长、倍速数值等）被封面背景色盖住看不清的问题',
                '修复 Python（.py）书源在源管理里点了「删除」却没有任何反应的问题',
                '修复 Python 书源被错误归到「jar 插件源」分组的问题'
            ],
            adds: [
                '新增批量源管理：在分组内长按任意源（或点「批量管理」），勾选多个源一次删除',
                '新增「Python 源」独立分组，.py 导入的书源一目了然',
                '播放页背景叠色加厚、文字加了托底，任何封面上都读得清'
            ]
        },
        {
            ver: '3.7.3',
            date: '2025-10-10',
            fixes: [
                '修复导入 Python（.py）书源时提示「Python 运行时不可用」、书源始终无法导入的问题',
                '修复 Python 运行时启动阶段直接报错、看不到具体原因的问题',
                '修复部分机型缺少运行时组件，导致书源加载失败的问题',
                '修复书源脚本用到网页解析、加密解密、网络请求等能力时报「找不到模块」的问题'
            ],
            adds: [
                '新增「更新公告」页面，可在「我的 → 更新公告」查看每版的修复与新增内容',
                '完善 Python 运行时组件，补齐书源脚本常用的依赖，提高书源兼容性',
                '导入 .py 书源失败时给出更具体的失败原因，方便对照排查'
            ]
        },
        {
            ver: '3.7.2',
            date: '2025-10-09',
            fixes: [
                '修复部分 Python 书源能导入、运行却报错的问题',
                '修复书源脚本依赖缺失导致的加载失败'
            ],
            adds: [
                'Python 源支持更完整：网页解析、加密解密、并发请求等均可使用'
            ]
        },
        {
            ver: '3.7.1',
            date: '2025-10-09',
            fixes: [
                '修复 .py 书源导入后找不到内部模块的问题'
            ],
            adds: [
                '书源管理支持导入 Python（.py）脚本源，兼容性更好'
            ]
        }
    ];

    function clVersionBlock(item, isCur) {
        var h = '<div class="cl-item">';
        h += '<div class="cl-hd"><span class="cl-v">' + TS.esc(item.ver);
        if (isCur) h += '<span class="cl-badge">最新</span>';
        h += '</span><span class="cl-date">' + TS.esc(item.date || '') + '</span></div>';

        if (item.fixes && item.fixes.length) {
            h += '<div class="cl-blk"><div class="cl-blk-t fix"><i></i>修复的问题'
               + '<em>' + item.fixes.length + ' 项</em></div><ul class="cl-ul">';
            for (var i = 0; i < item.fixes.length; i++) {
                h += '<li>' + TS.esc(item.fixes[i]) + '</li>';
            }
            h += '</ul></div>';
        }
        if (item.adds && item.adds.length) {
            h += '<div class="cl-blk"><div class="cl-blk-t add"><i></i>新增的功能'
               + '<em>' + item.adds.length + ' 项</em></div><ul class="cl-ul">';
            for (var j = 0; j < item.adds.length; j++) {
                h += '<li>' + TS.esc(item.adds[j]) + '</li>';
            }
            h += '</ul></div>';
        }
        h += '</div>';
        return h;
    }

    function renderChangelog() {
        var box = $('changelogBody');
        if (!box || box.getAttribute('data-done') === '1') return;
        if (!CHANGELOG.length) return;

        var h = clVersionBlock(CHANGELOG[0], true);
        if (CHANGELOG.length > 1) {
            h += '<div class="cl-old" id="clOld">'
               + '<div class="cl-old-hd" onclick="App.ui.toggleOldLog()">'
               + '<span>历史版本（' + (CHANGELOG.length - 1) + ' 个）</span>'
               + '<span class="setval">&#8250;</span></div><div class="cl-old-bd">';
            for (var i = 1; i < CHANGELOG.length; i++) {
                h += clVersionBlock(CHANGELOG[i], false);
            }
            h += '</div></div>';
        }
        box.innerHTML = h;
        box.setAttribute('data-done', '1');
    }

    ui.toggleOldLog = function () {
        var el = $('clOld');
        if (el) el.classList.toggle('open');
    };

    ui.openChangelog = function () {
        goSub('changelog');
        renderChangelog();
        var v = CHANGELOG.length ? CHANGELOG[0].ver : '';
        if ($('clCurVer') && v) $('clCurVer').textContent = v;
    };

    // ==================== 返回键 ====================

    window.onBackPressed = function () { return ui.back(); };

    // ==================== 工具 ====================

    function errBlock(t) {
        return '<div class="empty"><div class="big">😢</div>' + TS.esc(t) + '</div>';
    }

    // ==================== 初始化 ====================

    function init() {
        bindBar('fbar', 'fFill', true);
        paintSpeed();

        var kwEl = $('kw');
        if (kwEl) {
            kwEl.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.keyCode === 13) { ui.doSearch(); this.blur(); }
            });
        }

        var bar = $('tabbar');
        if (bar) {
            bar.addEventListener('click', function (e) {
                var t = e.target;
                while (t && t !== bar) {
                    if (t.className && String(t.className).indexOf('tabitem') >= 0) {
                        goTab(t.getAttribute('data-tab'));
                        return;
                    }
                    t = t.parentNode;
                }
            });
        }

        // 悬浮球整体点击 → 全屏播放页（点击事件写在 index.html 的 onclick 上）
        if ($('pCover')) {
            $('pCover').onerror = function () { this.style.opacity = 0; };
        }

        // 系统主题变化时若处于「跟随系统」则自动切换
        try {
            var mq = window.matchMedia('(prefers-color-scheme: dark)');
            var onSys = function (e) {
                if (App.theme === 'system') {
                    document.documentElement.className = e.matches ? 'dark' : '';
                }
            };
            if (mq.addEventListener) mq.addEventListener('change', onSys);
            else if (mq.addListener) mq.addListener(onSys);
        } catch (e) {}

        paintTheme();
        TS.call('deviceInfo');
        TS.call('listSources');
        TS.call('listPlugins');
        // ★ v3.7.9：问一次 Python 运行时状态。冷启动时它还在后台解压 assets，
        //   这一步是**非阻塞**的（原生侧不会等解释器），拿到 loading 就先提示
        //   "正在初始化"，等它就绪后 onSourceList 会把 .py 源补齐。
        TS.call('pythonStatus');
        goTab('home');
        // 悬浮球常驻：先按当前状态摆好（没内容时是灰球）
        syncBall();
        // 恢复上次播放会话：有记录就把球点亮，等用户点
        TS.call('resumeSession');
        // 主页下拉刷新（替代原来的右上角刷新按钮）
        bindPullRefresh();
        // 账号卡片状态（预留接口，未接入服务时显示"未登录"）
        TS.call('accountInfo');

        // 从后台切回来时校正一次图标：可能在后台播完/被暂停，本地状态是旧的
        document.addEventListener('visibilitychange', function () {
            if (!document.hidden) ui.syncPlayState();
        });
        window.addEventListener('focus', function () { ui.syncPlayState(); });

        // 启动页至少停留 700ms，避免闪一下就没了
        setTimeout(hideSplash, 700);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
