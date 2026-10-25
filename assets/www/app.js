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
        // ★ v3.1：音量增益百分比（100 = 原始音量，50~200，★ v3.9.7 上限下调）
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
        // ★ v3.9.1：源分组批量管理（显式进入，不与长按手势冲突）
        srcBatch: false,
        srcSel: {},             // 源 id -> true
        // ★ v3.9.50：首页源列表长按管理（长按任意源进入；批量/单选，
        //   删除 + 启停，停用或删除后源从首页列表移除）
        homeSrcBatch: false,
        homeSrcSel: {},         // 源 id -> true
        _homePressTok: null,    // 长按定时器
        _homePressFired: false, // 长按已触发标记（吞掉随后的 click）
        // ★ v2.9：当前下载目录（设置页显示用）
        dlDirName: '',
        dlDirUri: '',

        // 定时关闭（★ v3.9.0 起权威状态在 native SleepTimer，前端只做展示与设置）
        sleepMin: 0,
        sleepAt: 0,
        sleepTimer: null,
        // native 推送的定时状态：{min, remain, winStart, winEnd, holding, chip}
        sleep: null,

        // ★ v3.9.0：已收听集号缓存 { bookKey: {集号:true} }（progress 表镜像）
        listened: {},

        // ★ v3.9.0：已收听集号缓存 { bookKey: {集号:true} }（progress 表镜像）
        listened: {},

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
        diag: 'pageDiag',             // ★ v3.7.15-fix 源加载诊断
        crash: 'pageCrash',           // ★ v3.7.15-fix 崩溃日志
        wgdiag: 'pageWgDiag',         // ★ v3.8.4 小组件诊断日志
        backup: 'pageBackup',         // ★ v3.8.0 备份与恢复
        dlmanage: 'pageDlManage',     // ★ v2.9 下载管理
        custom: 'pageCustom' };       // ★ v3.9.0 自定义源（本地书源 + 网盘接口）

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
        // ★ v3.8.1：自绘弹窗（confirmSheet / inputSheet / 各类 sheet）打开时，
        //   返回键必须先关弹窗。以前这里完全不看 #sheetWrap，返回键会穿透弹窗
        //   去关全屏播放页或退回上一级，用户看到的是「弹窗关不掉、按返回却在切页」。
        //   所有弹窗统一挂在 #sheetWrap 下，所以一处判断即可覆盖全部。
        //
        // ★ v3.9.1：选集弹层（#chSheetWrap）是**另一个**全屏遮罩容器，
        //   之前返回键完全不认识它 —— 打开选集后按返回，直接去关全屏播放页
        //   或退回上一级，而选集还挂在屏幕上。补在最前面（两者同时存在时
        //   先关选集，它更靠上层）。
        //
        // ★ v3.9.3：首页源下拉展开时，返回键先收起它
        var srcPop = $('homeSrcPop');
        if (srcPop && !srcPop.classList.contains('hide')) { ui.closeSrcPop(); return true; }
        if ($('chSheetWrap')) { ui.closeChapterList(); return true; }
        if ($('sheetWrap')) { ui.closeSheet(); return true; }
        // 长按菜单（更多/书签/选集下拉）同理，它挂在 #ctxMenu 上
        if ($('ctxMenu')) { ctxClose(); return true; }
        // ★ v3.8.5：倍速 / 音量增益这两个展开面板是播放页**内嵌**的
        //   （不是 #sheetWrap 弹窗），原来返回键完全看不见它们 ——
        //   面板还开着，返回键却去关全屏页或退回上一级，用户感受就是
        //   "返回键乱跳"。这里让返回键先折面板，一次折一层，
        //   符合"返回 = 收起当前展开的东西"的直觉。
        var spP = $('speedPanel'), gpP = $('gainPanel');
        if ((spP && !spP.classList.contains('hide'))
                || (gpP && !gpP.classList.contains('hide'))) {
            if (spP && !spP.classList.contains('hide')) spP.classList.add('hide');
            if (gpP && !gpP.classList.contains('hide')) gpP.classList.add('hide');
            return true;
        }
        // ★ v3.9.1：源分组处于批量管理态时，返回键先退出批量
        //   （"返回 = 收起当前展开的东西"，与倍速面板同一套直觉）
        if (App.srcBatch) { App.srcBatch = false; App.srcSel = {}; paintSrcGroup(); return true; }
        // ★ v3.9.50：首页源列表管理态，返回键先退出管理
        if (App.homeSrcBatch) {
            App.homeSrcBatch = false; App.homeSrcSel = {};
            if (App.page === TABS.home) renderHomePicker();
            return true;
        }
        // ★ v3.9.1：源分组处于批量管理态时，返回键先退出批量
        //   （"返回 = 收起当前展开的东西"，与倍速面板同一套直觉）
        if (App.srcBatch) { App.srcBatch = false; App.srcSel = {}; paintSrcGroup(); return true; }
        // ★ v3.2：离开当前页面前先收起播放页的「更多」下拉，
        //   否则它会悬空留在新页面上
        ui.closeMore();
        // ★ v3.7.14：源分组页多选态已移除，返回键不再需要先退多选
        if (App.fullShow) { ui.closeFull(); return true; }
        if (App.stack.length > 0) {
            backToSub(App.stack.pop());
            return true;
        }
        if (App.tab !== 'home') { goTab('home'); return true; }

        // ★ v3.9.1：已经回到首页、也没有任何可收起的东西了 —— 按返回就是想退出。
        //   ★ v4.2.0：确认弹框整个去掉 —— 用户反馈"多此一举"。改成标准双击退出：
        //     单击 → toast「再按一次返回桌面」（不再打断操作流）；
        //     2.2 秒内再按 → 直接返回桌面（exitApp 只关界面，后台播放不受影响）。
        var nowTs = Date.now();
        if (nowTs - (App.lastHomeBack || 0) < 2200) {
            App.lastHomeBack = 0;
            TS.call('exitApp');
            return true;
        }
        App.lastHomeBack = nowTs;
        TS.toast('再按一次返回桌面');
        return true;
    };

    function updateTabbar() {
        var bar = $('tabbar');
        if (!bar) return;
        // 二级页（详情/全屏/分组/书源/搜索/赞赏/设置/关于）隐藏底栏
        var isSub = (App.page === SUBS.detail || App.page === SUBS.full
            || App.page === SUBS.group || App.page === SUBS.sources
            || App.page === SUBS.custom || App.page === SUBS.reward
            || App.page === SUBS.settings
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
        // ★ v3.9.38：classic 模式先用启动快照重放上次首页（零网络），
        //   等 discoverSources 回来后 paintHomeSources 仍走重放路径，不闪屏。
        if (App._homeMode === 'classic' && !App._homeSnapDone) {
            App._homeSnapDone = true;
            if (restoreHomeSnap()) paintHomeSources();
        }
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

        // ★ v3.9.38：源列表模式且停在「选择页」→ 渲染源列表
        if (App._homeMode === 'list' && App._homePick) { renderHomePicker(); return; }

        // ★ v3.9.3：首页停在本地书源视图 → 走专属渲染，不进分类浏览流程
        if (App.home.src === 'local') { renderHomeLocal(); return; }

        // ★ v4.6.0：首页停在网盘源视图 → 展示盘内扫描的有声书书架
        if (App.home.src && App.home.src.indexOf('pan_') === 0) { renderHomePan(); return; }

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

    // ==================== ★ v3.9.38 首页显示方式 ====================
    //
    // 'list'    —— 首页先显示「源列表」，点选源后才进分类浏览（现在的首页）；
    //              左上角源名点击 = 回到源列表换源。
    // 'classic' —— 保持现在的首页，但把「最后一次浏览的 源+分类+首屏书单」
    //              存成本地快照，冷启动直接重放（零网络），用户下拉刷新 /
    //              切分类等操作才真正联网 —— 解决"打开半天什么都不显示"。
    App._homeMode = 'classic';
    App._homePick = false;          // list 模式下：是否停在「源列表选择页」
    App._homeSnapDone = false;      // 本次启动是否已恢复过快照（只恢复一次）
    try { App._homeMode = localStorage.getItem('homeMode') === 'list' ? 'list' : 'classic'; } catch (e) {}

    ui.setHomeMode = function (v) {
        App._homeMode = (v === 'list') ? 'list' : 'classic';
        try { localStorage.setItem('homeMode', App._homeMode); } catch (e) {}
        // 切到源列表模式立刻进选择页；切回内容直达立刻恢复内容
        App._homePick = (App._homeMode === 'list');
        resetHomePaging();
        if (App.tab === 'home') paintHomeSources();
        paintHomeModeSeg();
    };

    function paintHomeModeSeg() {
        var ids = { list: 'hmList', classic: 'hmClassic' };
        for (var k in ids) {
            var el = $(ids[k]);
            if (el) el.className = 'seg-item' + (App._homeMode === k ? ' on' : '');
        }
    }
    ui.paintHomeModeSeg = paintHomeModeSeg;

    /** list 模式：首页的「源列表选择页」。
     *  ★ v3.9.39：不只列可浏览源 —— 所有启用的源都显示：
     *  支持分类浏览的直接进首页；仅搜索源标注「搜索」，点了跳搜索页。 */
    function renderHomePicker() {
        var cd = $('discoverCats'), cl = $('homeContent');
        if (!cl) return;
        if (cd) cd.innerHTML = '';
        var el = $('homeSrcName');
        if (el) el.textContent = '选择书源';
        var list = App.homeSources || [];
        var browsable = {};
        // ★ v3.9.50：停用的源不出现在首页源列表（管理态里关掉即从列表移除）。
        //   ★ v3.9.51：homeSources 是 discoverSources 的缓存，setSourceEnabled
        //   后不会自动带 enabled 字段 —— 用 App.sources（onSourceChanged 实时
        //   同步）交叉校验，停用立即从列表消失，不等下一次 discoverSources。
        var enMap = {};
        for (var q = 0; q < (App.sources || []).length; q++) {
            enMap[App.sources[q].id] = App.sources[q].enabled !== false;
        }
        for (var b = 0; b < list.length; b++) {
            if (list[b] && list[b].enabled === false) continue;
            if (list[b] && enMap[list[b].id] === false) continue;
            // ★ v3.9.51：全量表里已经没有这个 id = 已被删除，从列表移除
            if (list[b] && (App.sources || []).length > 0 &&
                !(list[b].id in enMap)) continue;
            browsable[list[b].id] = true;
        }
        // 其他启用的源（不支持分类浏览的搜索源）：排除本地/网盘/已列出的
        var others = [], seen = {};
        for (var s = 0; s < (App.sources || []).length; s++) {
            var cs = App.sources[s];
            if (!cs || !cs.id || seen[cs.id]) continue;
            seen[cs.id] = true;
            if (cs.enabled === false) continue;
            if (cs.id === 'local' || cs.origin === 'pan' || browsable[cs.id]) continue;
            others.push(cs);
        }
        var localOn = false;
        for (var s2 = 0; s2 < (App.sources || []).length; s2++) {
            var cs2 = App.sources[s2];
            if (cs2 && cs2.id === 'local' && cs2.enabled !== false) localOn = true;
        }
        if (!list.length && !others.length && !localOn) {
            cl.innerHTML =
                '<div class="empty nosrc">' +
                  '<div class="big">📚</div>' +
                  '<div class="nosrc-t">还没有可用的书源</div>' +
                  '<div class="nosrc-s">本应用不含内置源，需要你自己导入书源后才可使用</div>' +
                  '<div class="nosrc-btns">' +
                    '<button class="mini" onclick="App.ui.goImportSource()">导入书源</button>' +
                    '<button class="mini ghost" onclick="App.ui.goTab(\'mine\')">书源管理</button>' +
                  '</div>' +
                  '<div class="nosrc-tip">正在获取书源清单，稍等片刻会自动出现</div>' +
                '</div>';
            if (!App._pickerAsked) {
                App._pickerAsked = true;
                TS.call('discoverSources');
            }
            return;
        }
        // ★ v3.9.50：管理态顶栏（批量/单选：启用/停用/删除/全选/反选/完成）
        var h = '';
        if (App.homeSrcBatch) {
            var nsel = 0, k;
            for (k in App.homeSrcSel) if (App.homeSrcSel[k]) nsel++;
            h += '<div class="homebatchbar" id="homeBatchBar">' +
                 '<span class="sbb-cnt">已选 <b>' + nsel + '</b></span>' +
                 '<button class="mini ghost" onclick="App.ui.homeSrcSelAll(true)">全选</button>' +
                 '<button class="mini ghost" onclick="App.ui.homeSrcSelInverse()">反选</button>' +
                 '<button class="mini" onclick="App.ui.homeSrcSelEnable(true)">启用</button>' +
                 '<button class="mini" onclick="App.ui.homeSrcSelEnable(false)">停用</button>' +
                 '<button class="mini" onclick="App.ui.homeSrcSelDelete()">删除</button>' +
                 '<button class="mini ghost" onclick="App.ui.homeSrcBatchExit()">完成</button>' +
                 '</div>';
        }
        h += '<div class="srcgrid">';
        for (var i = 0; i < list.length; i++) {
            var n = (list[i].cats || []).length;
            h += homeSrcCard(list[i].id,
                    TS.esc(list[i].name || list[i].id),
                    n > 0 ? (n + ' 个分类') : '暂无分类',
                    "App.ui.homePickSrc('" + escAttr(list[i].id) + "')");
        }
        for (var j = 0; j < others.length; j++) {
            var nm = others[j].alias || others[j].name || others[j].id;
            h += homeSrcCard(others[j].id,
                    TS.esc(nm), '仅搜索 · 点去搜索',
                    "App.ui.homePickSearchSrc('" + escAttr(others[j].id) + "')");
        }
        if (localOn) {
            h += homeSrcCard('local', '本地书源', '听本机音频',
                    "App.ui.homePickSrc('local')");
        }
        h += '</div>';
        cl.innerHTML = h;
    }

    /** ★ v3.9.50：生成一张源卡片 —— 普通态支持长按进管理，管理态是勾选卡 */
    function homeSrcCard(id, title, sub, tapJs) {
        var sel = !!(App.homeSrcSel && App.homeSrcSel[id]);
        if (App.homeSrcBatch) {
            return '<div class="srcgrid-item srcgrid-item-manage' + (sel ? ' srcgrid-item-sel' : '') +
                   '" onclick="App.ui.homeSrcSelToggle(\'' + escAttr(id) + '\', this)">' +
                   '<div class="pickbox' + (sel ? ' on' : '') + '"></div>' +
                   '<div class="t">' + title + '</div>' +
                   '<div class="s">' + sub + '</div></div>';
        }
        // 长按 480ms 进管理模式；touchmove/touchend 取消（滑动不算长按）。
        // 长按触发后 _homePressFired 会吞掉随后那次 click。
        return '<div class="srcgrid-item" ' +
               'ontouchstart="App.ui.homeSrcPress(\'' + escAttr(id) + '\')" ' +
               'ontouchend="App.ui.homeSrcPressEnd()" ' +
               'ontouchmove="App.ui.homeSrcPressEnd()" ' +
               'ontouchcancel="App.ui.homeSrcPressEnd()" ' +
               'onclick="' + tapJs + '">' +
               '<div class="t">' + title + '</div>' +
               '<div class="s">' + sub + '</div></div>';
    }

    // ==================== ★ v3.9.50 首页源列表长按管理 ====================

    /** 长按开始：480ms 后进入管理模式并勾住按住的这个源 */
    ui.homeSrcPress = function (id) {
        ui.homeSrcPressEnd();
        App._homePressFired = false;
        App._homePressTok = setTimeout(function () {
            App._homePressTok = null;
            App._homePressFired = true;         // 吞掉 touchend 后的 click
            App.homeSrcBatch = true;
            App.homeSrcSel = {};
            App.homeSrcSel[id] = true;
            try { if (navigator.vibrate) navigator.vibrate(30); } catch (e) {}
            renderHomePicker();
            TS.toast('已进入管理：点选多个源后用顶部按钮批量启停/删除');
        }, 480);
    };

    /** 长按结束/滑动：取消定时器 */
    ui.homeSrcPressEnd = function () {
        if (App._homePressTok) { clearTimeout(App._homePressTok); App._homePressTok = null; }
    };

    /** 普通态点击卡片前的统一闸门：吞掉长按后的 click；管理态里改为勾选 */
    ui.homeSrcCardGuard = function () {
        if (App._homePressFired) { App._homePressFired = false; return true; }
        if (App.homeSrcBatch) return true;      // 管理态卡片 onclick 已是勾选，不走这里
        return false;
    };

    /** 管理态：勾选/取消勾选 —— ★ v3.9.52 就地更新，不整页重绘（整绘卡） */
    ui.homeSrcSelToggle = function (id, el) {
        if (!App.homeSrcSel) App.homeSrcSel = {};
        var on = !App.homeSrcSel[id];
        if (on) App.homeSrcSel[id] = true; else delete App.homeSrcSel[id];
        if (el) {
            el.classList[on ? 'add' : 'remove']('srcgrid-item-sel');
            var box = el.querySelector('.pickbox');
            if (box) box.classList[on ? 'add' : 'remove']('on');
        }
        var bar = $('homeBatchBar');
        if (bar) {
            var n = 0, k;
            for (k in App.homeSrcSel) if (App.homeSrcSel[k]) n++;
            var c = bar.querySelector('.sbb-cnt');
            if (c) c.innerHTML = '已选 <b>' + n + '</b>';
        }
    };

    /** 管理态：全选 / 反选 */
    ui.homeSrcSelAll = function (on) {
        App.homeSrcSel = {};
        if (on) {
            var ids = homeSrcManageIds();
            for (var i = 0; i < ids.length; i++) App.homeSrcSel[ids[i]] = true;
        }
        renderHomePicker();
    };
    ui.homeSrcSelInverse = function () {
        var ids = homeSrcManageIds(), next = {};
        for (var i = 0; i < ids.length; i++) {
            if (!App.homeSrcSel[ids[i]]) next[ids[i]] = true;
        }
        App.homeSrcSel = next;
        renderHomePicker();
    };

    /** 当前页面上可管理的源 id（与渲染逻辑一致） */
    function homeSrcManageIds() {
        var ids = [];
        var list = App.homeSources || [];
        for (var i = 0; i < list.length; i++) {
            if (list[i] && list[i].id && list[i].enabled !== false) ids.push(list[i].id);
        }
        var seen = {}, browsable = {};
        for (var b = 0; b < list.length; b++) {
            if (list[b] && list[b].id) { seen[list[b].id] = true; browsable[list[b].id] = true; }
        }
        for (var s = 0; s < (App.sources || []).length; s++) {
            var cs = App.sources[s];
            if (!cs || !cs.id || seen[cs.id]) continue;
            seen[cs.id] = true;
            if (cs.enabled === false) continue;
            if (cs.id === 'local' || cs.origin === 'pan' || browsable[cs.id]) continue;
            ids.push(cs.id);
        }
        var localOn = false;
        for (var s2 = 0; s2 < (App.sources || []).length; s2++) {
            if (App.sources[s2] && App.sources[s2].id === 'local' &&
                App.sources[s2].enabled !== false) localOn = true;
        }
        if (localOn) ids.push('local');
        return ids;
    }

    /** 管理态：退出（清空勾选，不留隐形状态） */
    ui.homeSrcBatchExit = function () {
        App.homeSrcBatch = false;
        App.homeSrcSel = {};
        renderHomePicker();
    };

    /** 管理态：批量启用/停用（复用 _bulkSw 批量提示，回调里会重画首页） */
    ui.homeSrcSelEnable = function (on) {
        var todo = [];
        for (var k in App.homeSrcSel) if (App.homeSrcSel[k]) todo.push(k);
        if (!todo.length) { TS.toast('还没有勾选任何源'); return; }
        App._bulkSw = { left: todo.length, total: todo.length, on: !!on };
        for (var i = 0; i < todo.length; i++) TS.call('setSourceEnabled', todo[i], !!on);
        App.homeSrcSel = {};
        TS.toast((on ? '正在启用 ' : '正在停用 ') + todo.length + ' 个源…');
        // ★ v3.9.52：不再重复拉清单 —— onSourceChanged 批量完成时已统一
        //   调度一次 discoverSources，这里再加一次就是双倍重绘（卡）。
    };

    /** 管理态：批量删除。★ v4.1：内置/插件源删不掉（native 会拒绝），
     *  之前直接对它们调 removeSourceById → ok=false，源"删除后还在"，
     *  用户以为坏了。现在先分流：内置/插件源自动改为**停用**并明确告知，
     *  其余（JSON 自定义源 / Python 源）真正删除。 */
    ui.homeSrcSelDelete = function () {
        var todo = [], dis = [];
        for (var k in App.homeSrcSel) {
            if (!App.homeSrcSel[k]) continue;
            var s = findSource(k);
            if (s && (s.builtin || pluginPathOf(k))) dis.push(k);
            else todo.push(k);
        }
        if (!todo.length && !dis.length) { TS.toast('还没有勾选任何源'); return; }
        var msg = '确定删除选中的源？';
        if (dis.length) msg += '其中 ' + dis.length + ' 个内置/插件源不可删除，将自动改为停用。';
        ui.confirmSheet(msg, function () {
            var all = todo.length + dis.length;
            if (dis.length) App._bulkSw = { left: dis.length, total: dis.length, on: false };
            if (todo.length) App._bulkDel = { left: todo.length, total: todo.length, done: 0 };
            var i;
            for (i = 0; i < dis.length; i++) TS.call('setSourceEnabled', dis[i], false);
            for (i = 0; i < todo.length; i++) TS.call('removeSourceById', todo[i]);
            App.homeSrcSel = {};
            TS.toast(todo.length ? ('正在删除 ' + todo.length + ' 个源…')
                                 : ('正在停用 ' + dis.length + ' 个源…'));
            // 回调（onCustomChanged / onSourceChanged）会统一计数、提示并刷新；
            // 这里只留一次兜底整表重拉（万一某条回调丢了）
            setTimeout(function () {
                App.homeSrcBatch = false;
                App.homeSrcSel = {};
                App._bulkDel = null;
                App._bulkSw = null;
                TS.call('listSources');
                TS.call('discoverSources');
            }, 1500);
        });
    };

    /** ★ v3.9.39：源列表里点了「仅搜索」源 → 跳搜索页（结果可按来源筛选）。
     *  ★ v3.9.41：点完直接弹关键词输入，确认后立即搜索并自动筛选到该源 ——
     *  一条流走完，不再让用户自己再去搜索页敲字、翻筛选。 */
    ui.homePickSearchSrc = function (sid) {
        // ★ v3.9.50：管理态/长按吞 click（同 homePickSrc）
        if (App.homeSrcBatch) { ui.homeSrcSelToggle(sid); return; }
        if (ui.homeSrcCardGuard()) return;
        App._homePick = false;
        var nm = sid;
        for (var i = 0; i < (App.sources || []).length; i++) {
            if (App.sources[i].id === sid) {
                nm = App.sources[i].alias || App.sources[i].name || sid;
                break;
            }
        }
        ui.inputSheet('搜索「' + nm + '」', '', function (kw) {
            kw = (kw || '').trim();
            if (!kw) return;
            goTab('search');
            var el = $('kw');
            if (el) el.value = kw;
            ui.doSearch();
            // 结果回来后自动把筛选切到这个源（见 paintSearchChips）
            App._searchFocusSrc = sid;
        });
    };

    // ==================== ★ v3.9.38 classic 模式：首页启动快照 ====================
    //
    // renderHomeList 渲染出首屏后，把「源+分类+首屏书单+分类条」存进
    // localStorage；冷启动 loadHome 先把快照灌回 App.homeBooks /
    // App.homeSources，paintHomeSources 会走 v3.7.6 的累积重放路径 ——
    // 一行网络请求都不发就能看到上次的首页。用户下拉刷新 / 切分类 /
    // 换源时走原有联网逻辑，快照随之更新。
    var HOME_SNAP_KEY = 'fx_home_snap_v1';

    function saveHomeSnap(all, srcId, catId) {
        if (App._homeMode !== 'classic') return;
        try {
            var cfg = null, list = App.homeSources || [];
            for (var i = 0; i < list.length; i++) if (list[i].id === srcId) cfg = list[i];
            localStorage.setItem(HOME_SNAP_KEY, JSON.stringify({
                src: srcId,
                cat: catId,
                books: all.slice(0, 48),          // 只存首屏，控制体积
                cats: (cfg && cfg.cats) || [],
                srcName: (cfg && cfg.name) || '',
                hasMore: !!App.homeHasMore,
                ts: Date.now()
            }));
        } catch (e) {}
    }

    /** 冷启动恢复快照。成功返回 true（App.home / homeBooks / homeSources 已就位） */
    function restoreHomeSnap() {
        try {
            var snap = JSON.parse(localStorage.getItem(HOME_SNAP_KEY) || 'null');
            if (!snap || !snap.src || !snap.books || !snap.books.length) return false;
            if (App.homeBooks[snap.cat] && App.homeBooks[snap.cat].length) return false;
            App.home.src = snap.src;
            App.home.cat = snap.cat || '';
            App.home.page = 1;
            App.homeBooks[snap.cat] = snap.books;
            App.homePage[snap.cat] = 1;
            App.homeHasMore = !!snap.hasMore;
            if (!App.homeSources) {
                App.homeSources = [{ id: snap.src, name: snap.srcName || snap.src, cats: snap.cats || [] }];
            }
            return true;
        } catch (e) { return false; }
    }

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
        // ★ v3.9.3：上次用的是本地书源（不在 discoverSources 列表里）
        if (saved.src === 'local') {
            // 本地源已停用就不恢复（App.sources 到手后由开关状态兜底）
            App.home.src = 'local';
            App.home.page = 1;
            App.home.cat = '';
            return true;
        }
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
            // ★ v3.9.38：首屏渲染完成 → 存启动快照（classic 模式下次打开秒显）
            saveHomeSnap(all, srcId, catId);
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
        // ★ v3.9.38：源列表模式下，点源名 = 回「源列表选择页」换源
        if (App._homeMode === 'list') {
            ui.closeSrcPop();
            App._homePick = true;
            App._pickerAsked = false;
            paintHomeSources();
            return;
        }
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
                 '" data-id="' + escAttr(list[i].id) + '"' +
                 ' onclick="App.ui.homePickSrc(\'' + escAttr(list[i].id) + '\')">' +
                 '<span>' + TS.esc(list[i].name) + '</span>' +
                 '<span class="tick">✓</span></div>';
        }
        // ★ v3.9.3：自定义源（本地书源）开启后也进下拉，方便直接选。
        // ★ v4.6.0：已启用的网盘源也进下拉——盘内扫描让网盘源有了「书架」，
        //   可以像书源一样当首页源用（点进去就是该网盘的有声书列表）。
        var custom = [], pans = [];
        for (var s = 0; s < (App.sources || []).length; s++) {
            var cs = App.sources[s];
            if (!cs || cs.enabled === false) continue;
            if (cs.id === 'local') custom.push(cs);
            else if (cs.origin === 'pan' || (cs.id && cs.id.indexOf('pan_') === 0)) pans.push(cs);
        }
        if (custom.length) {
            h += '<div class="srcdd-sep">自定义源</div>';
            for (var c2 = 0; c2 < custom.length; c2++) {
                var cOn = App.home.src === custom[c2].id;
                h += '<div class="srcdd-item' + (cOn ? ' on' : '') +
                     '" data-id="' + escAttr(custom[c2].id) + '"' +
                     ' onclick="App.ui.homePickSrc(\'' + escAttr(custom[c2].id) + '\')">' +
                     '<span>' + TS.esc(custom[c2].alias || custom[c2].name || '本地书源') + '</span>' +
                     '<span class="tick">✓</span></div>';
            }
        }
        if (pans.length) {
            h += '<div class="srcdd-sep">网盘源</div>';
            for (var p2 = 0; p2 < pans.length; p2++) {
                var pOn = App.home.src === pans[p2].id;
                h += '<div class="srcdd-item' + (pOn ? ' on' : '') +
                     '" data-id="' + escAttr(pans[p2].id) + '"' +
                     ' onclick="App.ui.homePickSrc(\'' + escAttr(pans[p2].id) + '\')">' +
                     '<span>' + TS.esc(pans[p2].alias || pans[p2].name || pans[p2].id) + '</span>' +
                     '<span class="tick">✓</span></div>';
            }
        }
        $('homeSrcList').innerHTML = h;
        bindSrcPopLongPress();                   // ★ v4.1：长按项 → 编辑/停用/删除
        pop.classList.remove('hide');
        $('homeSrcBtn').classList.add('open');
        // 点页面任意其它地方收起（用捕获阶段，避免被 stopPropagation 挡掉）
        setTimeout(function () {
            document.addEventListener('click', _ddOutside, true);
        }, 0);
    };

    /**
     * ★ v4.1：源下拉项的长按手势。
     * 长按 480ms 弹出统一长按菜单（重命名 / 停用启用 / 测试 / 删除），
     * 滑动取消；长按触发后吞掉随后的 click（不会误选源）。
     */
    function bindSrcPopLongPress() {
        var list = $('homeSrcList');
        if (!list) return;
        // 每次 innerHTML 重画后重绑：克隆替换法自动丢弃旧监听
        var fresh = list.cloneNode(false);       // 只克隆节点本身，不带走子节点
        fresh.innerHTML = list.innerHTML;
        list.parentNode.replaceChild(fresh, list);
        var timer = null, fired = false, sx = 0, sy = 0;
        fresh.addEventListener('touchstart', function (e) {
            var t = e.target;
            while (t && t !== fresh && !(t.classList && t.classList.contains('srcdd-item'))) {
                t = t.parentNode;
            }
            if (!t || t === fresh || !t.getAttribute('data-id')) return;
            var id = t.getAttribute('data-id');
            var p = e.touches[0];
            sx = p.clientX; sy = p.clientY; fired = false;
            timer = setTimeout(function () {
                timer = null; fired = true;
                ui.homeSrcCtx(id, sx, sy);
            }, 480);
        }, { passive: true });
        fresh.addEventListener('touchmove', function (e) {
            if (!timer) return;
            var p = e.touches[0];
            if (Math.abs(p.clientX - sx) + Math.abs(p.clientY - sy) < 10) return;
            clearTimeout(timer); timer = null;
        }, { passive: true });
        fresh.addEventListener('touchend', function (e) {
            if (timer) { clearTimeout(timer); timer = null; }
            if (fired) e.preventDefault();       // 长按后的 click 吞掉
        }, { passive: false });
    }

    /** ★ v4.1：长按下拉里的源 → 编辑/停用启用/测试/删除（不可删的源不给删除项） */
    ui.homeSrcCtx = function (sid, x, y) {
        var s = findSource(sid);
        var nm = s ? (s.alias || s.name || sid) : sid;
        ui.closeSrcPop();                        // 先收下拉，再弹菜单
        var en = s ? s.enabled !== false : true;
        var isBuiltin = s && (s.builtin || pluginPathOf(sid));
        var items = [
            { label: '重命名', icon: '✏️', pick: function () { ui.renameSource(sid); } },
            { label: en ? '停用' : '启用', icon: en ? '⏸' : '▶',
              pick: function () { TS.call('setSourceEnabled', sid, !en); } },
            { label: '测试连通', icon: '📡', pick: function () { ui.testSource(sid); } }
        ];
        if (!isBuiltin) {
            items.push({ label: '删除', icon: '🗑', danger: true,
                         pick: function () { ui.menuDelete(sid); } });
        }
        ctxMenu(Math.max(8, x - 20), Math.max(60, y + 12), items);
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
        // ★ v3.9.50：管理态点卡片 = 勾选（渲染时 onclick 已换，这里兜底）；
        //   长按触发后的那次 click 直接吞掉
        if (App.homeSrcBatch) { ui.homeSrcSelToggle(sid); return; }
        if (ui.homeSrcCardGuard()) return;
        // ★ v3.9.38：无论哪种模式，点了源就是离开了「源列表选择页」
        App._homePick = false;
        ui.closeSrcPop();
        if (App.home.src === sid) {
            // list 模式下点当前源 = 进内容；classic 下行为不变（重放也不闪屏）
            if (sid === 'local') { renderHomeLocal(); return; }
            if (sid && sid.indexOf('pan_') === 0) { renderHomePan(); return; }
            paintHomeSources();
            return;
        }
        App.home.src = sid;
        App.home.page = 1;
        App.homePending = {};
        App.homePage = {};
        App.homeBooks = {};
        resetHomePaging();                       // ★ v3.7.6：换源后重新开始分页
        // ★ v3.9.3：本地书源走专属视图（没有"分类浏览"，直接列本地书）
        if (sid === 'local') {
            saveLastHomeSource(sid);
            renderHomeLocal();
            return;
        }
        // ★ v4.6.0：网盘源走专属视图（列出盘内扫描到的有声书）
        if (sid && sid.indexOf('pan_') === 0) {
            saveLastHomeSource(sid);
            renderHomePan();
            return;
        }
        var list = App.homeSources || [];
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === sid) {
                App.home.cat = (list[i].cats && list[i].cats.length) ? list[i].cats[0].k : '';
            }
        }
        saveLastHomeSource(sid);                 // ★ v3.7.6：记住这次选的源
        paintHomeSources();
    };

    /**
     * ★ v3.9.3：首页的「本地书源」视图。
     * 本地书源没有分类可浏览，直接列出扫描到的本地书（点开即详情）。
     */
    /**
     * ★ v4.6.0：首页的「网盘源」视图。
     * 列出该网盘盘内扫描到的有声书（文件夹=书），点书直接整本入队播放。
     * 没扫过/结果为空时给出扫描入口；扫描中显示进行中状态。
     */
    function renderHomePan() {
        var cd = $('discoverCats');
        var cl = $('homeContent');
        if (!cl) return;
        if (cd) cd.innerHTML = '';
        var pid = App.home.src;
        var el = $('homeSrcName');
        var nm = '';
        for (var s = 0; s < (App.sources || []).length; s++) {
            if (App.sources[s].id === pid) { nm = App.sources[s].alias || App.sources[s].name; break; }
        }
        if (!nm) nm = PAN_NAMES[pid] || pid;
        if (el) el.textContent = nm;
        saveLastHomeSource(pid);

        var st = panState(pid);
        var h = '';
        var books = st.scanBooks || [];
        if (st.scanScanning) {
            h += '<div class="empty nosrc"><div class="big">☁️</div>' +
                 '<div class="nosrc-t">正在扫描网盘目录…</div>' +
                 '<div class="nosrc-s">按文件夹找有声书，最多翻 3 层，稍等片刻</div></div>';
        } else if (books.length) {
            h += '<div class="homebatchbar" style="margin:0 0 8px">' +
                 '<span class="sbb-cnt">' + nm + ' · ' + books.length + ' 本有声书</span>' +
                 '<button class="mini ghost" onclick="App.ui.customPanScan(\'' + pid + '\')">重新扫描</button>' +
                 '</div><div class="srcgrid">';
            for (var i = 0; i < books.length; i++) {
                var bk = books[i];
                var n = bk.files ? bk.files.length : 0;
                h += homeSrcCard('pb_' + i, TS.esc(bk.name),
                        n + ' 集 · 点按播放',
                        "App.ui.customPanDiskPlay('" + escAttr(pid) + "'," + i + ")");
            }
            h += '</div>';
        } else {
            h += '<div class="empty nosrc"><div class="big">☁️</div>' +
                 '<div class="nosrc-t">' + TS.esc(nm) + '还没有扫描到有声书</div>' +
                 '<div class="nosrc-s">网盘里存的有声书文件夹（里面是 mp3 等音频）会自动整理成书</div>' +
                 '<div class="nosrc-btns">' +
                 '<button class="mini" onclick="App.ui.customPanScan(\'' + pid + '\')">扫描盘内书</button>' +
                 '</div></div>';
        }
        cl.innerHTML = h;
    }

    function renderHomeLocal() {
        var cd = $('discoverCats');
        var cl = $('homeContent');
        if (!cl) return;
        if (cd) cd.innerHTML = '';
        var el = $('homeSrcName');
        if (el) el.textContent = '本地书源';
        // 已有扫描结果就直接重放，不再闪"扫描中"
        if (App.custom && App.custom.books && App.custom.books.length) {
            renderHomeLocalBooks(App.custom.books);
            return;
        }
        cl.innerHTML = '<div class="loading"><span class="spinner"></span>扫描本地书籍中…</div>';
        TS.call('localBooks');
    }

    /** 首页本地书列表渲染（onLocalBooks 回包时若正停在本地源视图就画书） */
    function renderHomeLocalBooks(books) {
        var cl = $('homeContent');
        if (!cl) return;
        if (!books || !books.length) {
            cl.innerHTML =
                '<div class="empty nosrc">' +
                  '<div class="big">📁</div>' +
                  '<div class="nosrc-t">没有扫到本地书籍</div>' +
                  '<div class="nosrc-s">到「书源管理 → 自定义源 → 本地书源」发起扫描，<br>或指定一个扫描目录</div>' +
                  '<div class="nosrc-btns">' +
                    '<button class="mini" onclick="App.ui.goTab(\'mine\')">书源管理</button>' +
                  '</div></div>';
            return;
        }
        var h = '';
        for (var i = 0; i < books.length; i++) {
            var b = books[i];
            h += '<div class="bookcard" onclick="App.ui.openBookJson(\'' +
                 escAttr(JSON.stringify(b)) + '\')">';
            h += '<img src="' + TS.esc(b.cover) + '" onerror="TS.imgFail(this)" loading="lazy" decoding="async">';
            h += '<div class="info">';
            h += '<div class="t" data-mq>' + TS.esc(b.title) + '</div>';
            h += '<div class="s">' + TS.esc(b.count ? (b.count + ' 集') : '本地文件') + '</div>';
            h += '<div class="tagline"><span class="tag src">本地</span></div>';
            h += '</div></div>';
        }
        cl.innerHTML = h;
        if (TS.markImages) TS.markImages(cl);
    }

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
        // ★ v3.9.3：只重绘不跳顶 —— 搜索进行中各源陆续回传，之前的整表
        //   重绘 + scrollTop=0 会把用户正在浏览的筛选列表反复甩回顶部。
        if (App.searchFilter && App.searchFilter !== 'all') paintSearch(false);
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
        // ★ v3.9.3：与 paintSearch 共用索引快照 —— 追加的卡片用
        //   openSearchBook(快照下标)，增量到达再多也不会点错书
        if (!App.paintList) App.paintList = [];
        var frag = document.createDocumentFragment();
        var d = document.createElement('div');
        for (var i = 0; i < books.length; i++) {
            var idx = App.paintList.length;
            App.paintList.push(books[i]);
            d.innerHTML = searchCardHtml(books[i], idx);
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
        h += '</div></div>';
        // ★ v3.9.3：收藏也走索引快照（不再把整本书 JSON 塞进 onclick ——
        //   每张卡片几百字节的属性串，几百张卡片时明显拖慢首绘）
        if (i >= 0) {
            h += '<div class="fav-btn" data-key="' + TS.esc(b.key || '') + '" ' +
                 'onclick="event.stopPropagation();App.ui.toggleFavPaint(' + i + ')">☆</div>';
        }
        h += '</div>';
        return h;
    }

    window.onSearchResult = function (arr) {
        App.books = arr || [];
        App.searchRunning = false;
        paintSearchChips();
        // ★ v3.9.3：最终合并也是数据刷新，不把列表甩回顶部
        paintSearch(false);
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
    var _lastChipsHtml = null;

    function paintSearchChips() {
        var el = $('searchChips');
        if (!el) return;
        // ★ v3.9.41：从首页源列表点「仅搜索」源进来 → 结果一到就自动筛选到该源
        if (App._searchFocusSrc) {
            var foc = App._searchFocusSrc, fg = App.searchBySource || {};
            if (fg[foc] && fg[foc].length) {
                App._searchFocusSrc = null;
                ui.filterSearch(foc);
                return;
            }
            if (!App.searchRunning) App._searchFocusSrc = null;   // 该源没结果，放弃
        }
        var grp = App.searchBySource || {};
        var ids = [];
        for (var k in grp) if (grp[k] && grp[k].length) ids.push(k);
        var sum = diagSummary();
        if (!App.books.length && !ids.length && !sum) {
            el.classList.add('hide');
            _lastChipsHtml = null;
            return;
        }
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
        // ★ v3.9.3：内容没变不重建（诊断行每秒都可能刷一次，重建会让
        //   横向 chip 条闪烁、scrollLeft 归零 —— 用户点 chip 时像"乱跳"）
        if (h === _lastChipsHtml) return;
        // 重建时把横向滚动位置搬回来
        var oldRow = el.querySelector('.chiprow');
        var keepSL = oldRow ? oldRow.scrollLeft : 0;
        el.innerHTML = h;
        var newRow = el.querySelector('.chiprow');
        if (newRow && keepSL > 0) newRow.scrollLeft = keepSL;
        _lastChipsHtml = h;
    }

    ui.filterSearch = function (id) {
        App.searchFilter = id;
        paintSearchChips();
        // 用户主动切筛选：回到顶部是预期行为（reset=true）
        paintSearch(true);
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

    function paintSearch(reset) {
        var c = $('searchResult');
        if (!c) return;
        var filter = App.searchFilter || 'all';
        var list;
        if (filter === 'all') {
            list = App.books;
        } else {
            list = (App.searchBySource || {})[filter] || [];
        }
        // ★ v3.9.3：滚动位置保护 —— 数据刷新型重绘（增量批次/按源分组/
        //   最终合并陆续到达）保持用户所在位置；只有「新搜索」和「主动
        //   切筛选」才回顶部。这是"点着点着列表乱跳"的根治。
        var keepSL = c.scrollTop;
        // ★ v3.9.3：索引快照 —— 卡片 onclick 里的下标一律指向这份快照，
        //   之后 App.books 再被增量合并/重排也不会点错书。
        App.paintList = list;
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
                 'onclick="event.stopPropagation();App.ui.toggleFavPaint(' + i + ')">☆</div>';
            h += '</div>';
        }
        c.innerHTML = h;
        // reset=true（新搜索/切筛选）回顶部；数据刷新保持原位置
        c.scrollTop = reset ? 0 : keepSL;
        markFavs(list);
    }

    /** 打开搜索结果：一律走 paintSearch 时的索引快照，杜绝点错书 */
    ui.openSearchBook = function (i) {
        var b = (App.paintList || [])[i];
        if (b) openBookObj(b);
    };

    /** 收藏切换：与卡片 onclick 同一套索引快照 */
    ui.toggleFavPaint = function (i) {
        var b = (App.paintList || [])[i];
        if (b) ui.toggleFavJson(JSON.stringify(b));
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

    /** ★ v4.1：详情失败态的「重试一次」—— 清缓存重新拉章节 */
    ui.detailRetry = function () {
        App._detailRetried = false;
        App.detailError = '';
        if (App.page === SUBS.detail) renderDetailSkeleton(App.book);
        TS.call('clearDetailCache');
        setTimeout(function () {
            TS.call('detailFromJson', JSON.stringify(App.book));
        }, 300);
    };

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
        // ★ v4.1：章节表为空 → 记录失败原因并**自动清缓存重试一次**。
        //   不少失败是暂时的（网络抖动 / 上次失败结果被详情缓存钉住），
        //   重试一次能救回大半；重试还空才亮失败态。
        App.detailError = (!App.chapters.length) ? (d.error || '') : '';
        if (!App.chapters.length) {
            if (!App._detailRetried) {
                App._detailRetried = true;
                if (App.page === SUBS.detail) renderDetailSkeleton(App.book);
                TS.toast('章节加载失败，正在重试…');
                TS.call('clearDetailCache');
                setTimeout(function () {
                    TS.call('detailFromJson', JSON.stringify(App.book));
                }, 900);
            } else {
                App._detailRetried = false;      // 重试也失败：亮失败态，不再死循环
            }
        } else {
            App._detailRetried = false;
        }
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

    /**
     * 重画详情页。
     *
     * ★ v3.9.3：加 keepScroll 参数 —— 已听标记回传、收藏态变化、来源名
     * 映射更新这类"数据刷新型重绘"在用户滚到长列表中间时随时可能到达，
     * 之前一律 c.scrollTop=0，表现就是"章节列表滑着滑着弹回顶部"。
     * 新开详情（默认）仍回顶部。
     */
    function renderDetail(keepScroll) {
        var c = $('detailContent');
        var keepSL = c ? c.scrollTop : 0;
        var b = App.book || {};
        var chs = App.chapters;

        if (!chs.length) {
            // ★ v4.1：失败态给出具体原因（native 回传的各源报错）+ 明确的下一步
            var err = App.detailError || '';
            var srcNm = b.source ? sourceName(b.source) : '';
            var h2 = '<div class="empty"><div class="big">😢</div>没读到章节<br>' +
                '<span style="font-size:12.5px">这本书在当前源可能已失效，返回换同名书试试</span>';
            if (err) {
                h2 += '<div style="font-size:11.5px;color:var(--text2);margin-top:10px;' +
                      'max-width:300px;word-break:break-all">失败详情：' +
                      TS.esc(err) + '</div>';
            }
            if (srcNm) {
                h2 += '<div style="font-size:11.5px;color:var(--text2);margin-top:4px">来源：' +
                      TS.esc(srcNm) + '</div>';
            }
            h2 += '<div style="margin-top:14px">' +
                  '<button class="mini" onclick="App.ui.detailRetry()">重试一次</button> ' +
                  '<button class="mini ghost" onclick="App.ui.back()">返回</button></div>';
            h2 += '</div>';
            c.innerHTML = h2;
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
            // ★ v3.7.16：加 data-mq —— 章节名过长时自动变跑马灯
            h += '<div class="tt" data-mq>' +
                 TS.esc(ch.title || ('第' + no + '集')) + '</div>';
            // ★ v3.9.0：已收听 → 绿色小对勾
            // ★ v3.9.7 修复：v3.9.3 改版时旧「已」字圆圈没删，和新对勾叠成
            //   两个徽标挤在行尾，视觉很乱 —— 删掉旧圆圈，只留对勾（样式已调淡）。
            if (isListened(no)) h += '<span class="chdone" title="已收听">✓</span>';
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
        // ★ v3.9.3：数据刷新型重绘保持滚动位置（新开详情默认回顶部）
        c.scrollTop = keepScroll ? keepSL : 0;
        if (App.chapIdx >= 0) highlight(App.chapIdx);
        // 章节行建好后按下载状态刷一遍图标
        TS.call('cacheStatus', JSON.stringify(b));
        // ★ v3.9.0：拉已收听集号（绿色圆圈「已」）
        if (key) TS.call('listenedMarks', key);
        paintAllDlIcons();
        paintMultiUI();
    }

    /**
     * ★ v3.9.3：已听标记**就地刷新**（不整页重绘）。
     *
     * 之前 onListenedMarks 一到就 renderDetail() 整页 innerHTML 重建 ——
     * 用户刚进详情开始滑动时标记数据到达，轻则一顿卡、重则弹回顶部。
     * 现在只增删各行里的 .chdone 徽标，滚动位置与 DOM 天然不动。
     */
    function paintChapMarks() {
        var list = $('chapList');
        if (!list || !App.book) return;
        var rows = list.children;
        for (var r = 0; r < rows.length; r++) {
            var row = rows[r];
            var m = /^ch(\d+)$/.exec(row.id || '');
            if (!m) continue;
            var abs = +m[1];
            var ch = (App.chapters || [])[abs];
            var no = (ch && ch.index) || (abs + 1);
            var mark = row.querySelector('.chdone');
            if (isListened(no)) {
                if (!mark) {
                    mark = document.createElement('span');
                    mark.className = 'chdone';
                    mark.title = '已收听';
                    mark.textContent = '✓';
                    var dl = row.querySelector('.dl');
                    if (dl) row.insertBefore(mark, dl); else row.appendChild(mark);
                }
                row.classList.add('listened');   // ★ v3.9.7：集号变淡
            } else {
                if (mark) mark.parentNode.removeChild(mark);
                row.classList.remove('listened');
            }
        }
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
        if (App.page === SUBS.detail) renderDetail(true);   // v3.9.3 保滚动位
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
            // ★ v3.9.34：最后播放的一本高亮标识（列表已按时间倒序，第一本即最近）
            var lastCls = (i === 0) ? ' bookcard-last' : '';
            // ★ v3.7.11：去掉右上角的删除「✕」——改为**长按卡片**在按点弹菜单
            //   （删除 / 换源 / 搜索），触屏上更干净、也更不容易误删。
            h += '<div class="bookcard' + lastCls + '" data-hist-key="' + escAttr(b.key || '') +
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
        // ★ v3.9.37：软件内悬浮球开关（设置页「播放悬浮球」）关闭时不显示
        if (!App._ballOn) { el.classList.remove('show'); return; }
        el.classList.add('show');
        el.classList[(!App.book || App.chapIdx < 0) ? 'add' : 'remove']('empty');
    }
    ui.syncBall = syncBall;

    /**
     * ★ v3.9.37：App 内悬浮球可拖动。
     * 位移 < 8px 算点按（照常触发 tapBall 进播放页），拖完松手记住位置；
     * 位置存 localStorage，下次启动原位恢复，并夹在屏幕内防拖丢。
     */
    function initBallDrag() {
        var el = $('player');
        if (!el) return;
        var sx = 0, sy = 0, ox = 0, oy = 0, moved = false, dragging = false;
        el.addEventListener('touchstart', function (e) {
            if (e.touches.length !== 1) return;
            var t = e.touches[0], r = el.getBoundingClientRect();
            sx = t.clientX; sy = t.clientY;
            // ★ v4.1：按下瞬间 :active 的 scale(.92) 会把 rect 缩小一点，
            //   直接用 r.left/r.top 起算会让球在指尖下"歪"几个像素。
            //   按 (锁定尺寸 - 实际rect)/2 补回缩放差。
            var w = el.offsetWidth || 58, h = el.offsetHeight || 58;
            ox = r.left - (w - r.width) / 2;
            oy = r.top - (h - r.height) / 2;
            moved = false; dragging = true;
        }, { passive: true });
        el.addEventListener('touchmove', function (e) {
            if (!dragging) return;
            var t = e.touches[0];
            var dx = t.clientX - sx, dy = t.clientY - sy;
            if (!moved && (Math.abs(dx) + Math.abs(dy)) < 8) return;
            moved = true;
            e.preventDefault();   // 拖球时别让页面跟着滚
            el.classList.add('dragging');
            var w = el.offsetWidth || 58, h = el.offsetHeight || 58;
            var nx = Math.max(4, Math.min(window.innerWidth - w - 4, ox + dx));
            var ny = Math.max(4, Math.min(window.innerHeight - h - 4, oy + dy));
            el.style.transition = 'none';
            el.style.left = nx + 'px';
            el.style.top = ny + 'px';
            el.style.right = 'auto';
            el.style.bottom = 'auto';
        }, { passive: false });
        el.addEventListener('touchend', function (e) {
            if (!dragging) return;
            dragging = false;
            el.classList.remove('dragging');
            el.style.transition = '';
            if (moved) {
                e.preventDefault();   // 拖完松手不再触发 click（避免误进播放页）
                // ★ v4.1：位置改存「靠边 + 距离 + 纵向比例」，不再存绝对像素 ——
                //   换分辨率/旋转屏幕/折叠屏展开后原位语义仍成立，不会跑偏。
                try {
                    var w = el.offsetWidth || 58;
                    var x = parseFloat(el.style.left) || 0;
                    var y = parseFloat(el.style.top) || 0;
                    var left = (x + w / 2) < window.innerWidth / 2;
                    var dist = left ? x : (window.innerWidth - x - w);
                    var yr = Math.max(0, Math.min(1, y / Math.max(1, window.innerHeight)));
                    localStorage.setItem('ballPos', JSON.stringify({
                        v: 2, left: left ? 1 : 0, dist: Math.round(dist), yr: +yr.toFixed(4)
                    }));
                } catch (err) {}
            }
        }, { passive: false });
        // ★ v4.1：恢复上次位置 —— 新格式（靠边+比例）与旧格式（绝对像素）都认；
        //   无论哪种，最终都按当前视口尺寸夹回屏幕内。
        try {
            var raw = localStorage.getItem('ballPos') || '';
            var w = el.offsetWidth || 58, h = el.offsetHeight || 58;
            var x = null, y = null;
            if (raw.charAt(0) === '{') {
                var o = JSON.parse(raw);
                if (o && o.v === 2) {
                    var dist = Math.max(0, Math.min(window.innerWidth - w - 4, +o.dist || 0));
                    x = o.left ? dist : (window.innerWidth - w - dist);
                    y = Math.max(4, Math.min(window.innerHeight - h - 4, (+o.yr || 0) * window.innerHeight));
                }
            } else {
                var p = raw.split(',');
                if (p.length === 2 && isFinite(+p[0]) && isFinite(+p[1])) {
                    x = +p[0];
                    y = +p[1];
                }
            }
            if (x != null && y != null) {
                x = Math.max(4, Math.min(window.innerWidth - w - 4, x));
                y = Math.max(4, Math.min(window.innerHeight - h - 4, y));
                el.style.left = x + 'px';
                el.style.top = y + 'px';
                el.style.right = 'auto';
                el.style.bottom = 'auto';
            }
        } catch (err) {}
    }
    ui.initBallDrag = initBallDrag;

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
        // ★ v3.9.0：同步 native 定时关闭状态（页面重建/冷启动后权威状态在 native）
        try { TS.call('getSleep'); } catch (e) {}
        // ★ v3.9.0：同步 native 定时关闭状态（页面重建/冷启动后权威状态在 native）
        try { TS.call('getSleep'); } catch (e) {}
        // ★ v3.7.13：播放成功就把"连续失败次数"清零，失败提示才不会误报累积
        App.playErrStreak = 0;
        // ★ v3.7.18：这一集播起来了 → 静默重试计数清零
        App._playRetry = null;
        // ★ v3.8.1/v3.8.2：播成功就作废所有在途的失败处理（看门狗/重试计数），
        //   否则下一集的第一次失败可能被上一集遗留的计数当成"已试过"而直接停止
        App._playErrSeq = (App._playErrSeq || 0) + 1;
        App._playErrSwallow = 0;
        App._playRetry = null;
        if (App._playFailTimer) { clearTimeout(App._playFailTimer); App._playFailTimer = null; }
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
        // ★ v3.9.0：本集计入「已收听」—— 选集弹层/详情页的绿色小对勾即时更新。
        //   native 的 progress 表在开播时就会落行，这里同步前端镜像即可，
        //   不需要再回查（回查要等一轮桥往返，视觉上有延迟）。
        if (o && typeof o.index === 'number' && o.index > 0 && App.book && App.book.key) {
            var ls = App.listened[App.book.key] || (App.listened[App.book.key] = {});
            if (!ls[o.index]) {
                ls[o.index] = true;
                if (App.page === SUBS.detail) {
                    // ★ v3.9.3：标记就地刷新（原来整页重绘 + 手工恢复滚动位）
                    paintChapMarks();
                }
                if ($('chSheetWrap')) repaintChsList(true);
            }
        }
        saveSession();
    };

    /**
     * ★ P0-1 播放失败自动换源：native 在播放层换到新音源后续播时回调，
     * 用于刷新播放条/全屏页右上角的「书源胶囊」，并给一句轻提示。
     * 仅更新标签，不重建播放状态（native 已独立完成换源与续播）。
     */
    window.onSourceSwitched = function (o) {
        if (!o) return;
        App.playSource = o.source || App.playSource || '';
        App.playSourceName = o.sourceName || sourceName(App.playSource);
        paintPlaySource();
        if (o.sourceName) TS.toast('已自动切换音源：' + o.sourceName);
    };

    /** ★ P0-2 网络波动自动重试：native 在重试前回调，给一句轻提示（不挡操作）。 */
    window.onPlayRetry = function () {
        if (TS && TS.toast) TS.toast('网络波动，正在自动重试…');
    };

    /**
     * ★ v3.7.13 起源 / ★ v3.8.2 改版：播放失败 → 自动停止播放。
     *
     * <p>演进（三代）：
     * <ol>
     *   <li>v3.7.13：一个 toast「取链失败」—— 与 native 的 toast 重复、
     *       1.6 秒就没了、也没有下一步入口；</li>
     *   <li>v3.7.18：两级 —— 同一集首次失败静默重试一次，仍失败才弹
     *       「播放失败」面板（换下一集 / 换源）；</li>
     *   <li><b>v3.8.2（本轮）</b>：用户要求「播放失败的提示去掉，改成
     *       播放失败自动停止播放」。面板整体移除 —— 之前那层面板在弱网下
     *       频繁弹出，还偶发卡住挡操作。现在真正失败就停，不停留半截状态。</li>
     * </ol>
     *
     * <p>保留的「静默自救」：同一集第一次失败不惊动用户，延迟 800ms 自动
     * 重走一次完整取链。它对用户完全无感知，却能把源站瞬时抖动 / 直链过期
     * 救回来（native 在 onError 里已把该集取链缓存剔掉，重试含自动换源）。
     *
     * <p>停止时做了什么：
     * <ul>
     *   <li>停转圈、播放图标复位为「播放」；</li>
     *   <li>进度已由 native 在 onError 里落盘（saveProgressNow），
     *       之后可从原位续听，不会丢超过 1 秒；</li>
     *   <li>调 native {@code stop()}：释放播放器、撤通知、清队列；</li>
     *   <li>一行 2 秒轻提示（toast，不是页面，不挡任何操作）。</li>
     * </ul>
     */
    window.onPlayError = function (o) {
        App.playing = false;
        paintPlayIcon(false);

        var bk = App.book || {};
        var chIdx = App.chapIdx;

        // ---- 第一级：静默自救（同一集只试一次，用户无感知） ----
        // ★ 必须同时满足「有这一集」，否则重试会调 playChapter，
        //   而 playChapter 在没有章节列表时只挂 pendingAutoPlay、不关转圈，
        //   于是转圈永远转下去 —— 这就是「卡死、整个界面点不动」的来源之一。
        var canRetry = chIdx >= 0 && App.chapters && App.chapters.length > 0
                && chIdx < App.chapters.length;
        var retryKey = (bk.key || bk.title || '?') + ':' + chIdx;
        // ★ v3.8.2 修正（回归时抓出来的真 bug）：_playRetry 绝不能在函数
        //   开头就清掉 —— 清早了，第二次 onPlayError 读到的 tries 永远
        //   是 0，「同一集只自救一次」的上限失效，会退化成无限重试。
        //   它只该在「真播起来了」（onPlaying）和「收摊停止」
        //   （stopForPlayFail）时清。
        var tries = (App._playRetry && App._playRetry.key === retryKey)
            ? App._playRetry.tries : 0;
        if (tries < 1 && canRetry) {
            App._playRetry = { key: retryKey, tries: tries + 1 };
            // 自救期间继续转圈，用户知道还在尝试（但不弹任何东西）
            setBuffering(true);
            // 登记为「在途自救」：后来的失败/成功会作废它，不会叠出多重试
            if (App._playFailTimer) { clearTimeout(App._playFailTimer); App._playFailTimer = null; }
            var mySeq = (App._playErrSeq = (App._playErrSeq || 0) + 1);
            App._playFailTimer = setTimeout(function () {
                App._playFailTimer = null;
                // 序号被更新的失败/成功顶掉 → 这次自救已过期
                if (App._playErrSeq !== mySeq) return;
                // 用户已经手动切了别的集 / 换了书 / 开始播了 → 放弃这次重试
                if (App.chapIdx !== chIdx || App.playing || !App.book) {
                    // 只有「没换集、没在播」时才敢收转圈：换集/开播时
                    // 转圈可能是新集的，不能抢
                    if (App.chapIdx === chIdx && !App.playing) setBuffering(false);
                    return;
                }
                ui.playChapter(chIdx);
            }, 800);
            return;
        }

        // ---- 第二级：自救后仍失败 → 自动停止播放 ----
        setBuffering(false);
        // 作废一切在途的自救/看门狗，防止它们把已停止的状态又拉起来
        App._playErrSeq = (App._playErrSeq || 0) + 1;
        if (App._playFailTimer) { clearTimeout(App._playFailTimer); App._playFailTimer = null; }
        stopForPlayFail(o, bk, chIdx);
    };

    /**
     * 播放彻底失败 → 自动停止（v3.8.2）。
     *
     * <p>「停止」而不是「停在半截」：转圈必停、图标必复位、native 侧
     * 播放器释放 + 通知撤下 + 队列清空。进度已在 native onError 里
     * 落盘，用户想重听随时能从原位开始。
     */
    function stopForPlayFail(o, bk, chIdx) {
        setBuffering(false);
        App.playing = false;
        paintPlayIcon(false);
        saveSession();
        // 收摊：自救计数也清掉 —— 用户下次手动点播放时，这一集的第一次
        // 失败还应再享有一次静默自救（否则会被上次的计数直接判死）
        App._playRetry = null;

        var ch = (App.chapters && chIdx >= 0) ? App.chapters[chIdx] : null;
        var name = (o && o.title) || (ch && ch.title)
            || ('第' + ((ch && ch.index) || (chIdx + 1)) + '集');

        // ★ v3.9.32：失败不再立即停止 —— 15 秒兜底窗口：
        //   期间若恢复播放（迟到的自救成功 / 用户手动点播成功）则取消停止；
        //   15 秒仍无法播放才真正 stop（释放播放器/撤通知/清队列）。
        if (App._graceStopTimer) { clearTimeout(App._graceStopTimer); App._graceStopTimer = null; }
        // ★ v3.9.34 抽搐修复：同一个播放按钮周期里反复「失败→恢复→失败」
        //  会让图标来回跳（抽搐）。连续第 3 次失败不再给缓冲窗，直接停止。
        App._graceFails = (App._graceFails || 0) + 1;
        if (App._graceFails >= 3) {
            App._graceFails = 0;
            TS.call('stop');
            TS.toast('「' + name + '」无法播放，已停止');
            return;
        }
        TS.toast('「' + name + '」播放失败，15 秒后自动停止');
        App._graceStopTimer = setTimeout(function () {
            App._graceStopTimer = null;
            if (App.playing) return;   // 已恢复播放 → 取消停止
            TS.call('stop');
            TS.toast('「' + name + '」无法播放，已停止');
        }, 15000);
    }

    window.onPlayState = function (p) {
        // native 推来的真值，直接采用，不做任何本地推断
        App.playing = !!p;
        // ★ v3.9.32：播放恢复 → 取消 15 秒兜底停止
        if (p && App._graceStopTimer) {
            clearTimeout(App._graceStopTimer);
            App._graceStopTimer = null;
        }
        // ★ v3.9.34：真播起来了 → 连续失败计数清零
        if (p) App._graceFails = 0;
        // 拿到真值就说明 native 那边已经有结论了（在播 or 已停），
        // 不管哪种都不该再转圈。
        setBuffering(false);
        paintPlayIcon(App.playing);
        // ★ v3.8.2：native 明确说「没在播」→ 作废在途的失败自救。
        //   否则用户在自救的 800ms 窗口里从通知栏/小组件点了停止，
        //   重试定时器到点还会把播放重新拉起来（用户会看到"停了又响"）。
        //   注意：播放器出错时 native 先推 onStateChanged(false) 再推
        //   onPlayError，那时定时器还没建，序号会被 onPlayError 重新取，
        //   不会误杀正在排队的那次自救。
        if (!p) {
            App._playErrSeq = (App._playErrSeq || 0) + 1;
            if (App._playFailTimer) { clearTimeout(App._playFailTimer); App._playFailTimer = null; }
        }
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

    window.onProgress = function (cur, dur) {
        setTime(cur || 0, dur || 0);
        saveSession();
    };

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
        // ★ v3.8.1：同理，跳过片头也要向原生要一次（菜单里要显示当前值）
        TS.call('getSkipIntro');
        // ★ v3.8.2：跳过片尾同理
        TS.call('getSkipOutro');

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

    // ★ v3.9.53：快进/快退按钮改用设置里的步长（5 秒一档，默认 30）。
    //   之前按钮写死 ±30，设置里的「快进快退步长」形同虚设。
    ui.jumpDir = function (dir) {
        var s = parseInt(App.ff, 10); if (isNaN(s) || s <= 0) s = 30;
        ui.jump(dir * s);
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
        // ★ v3.9.0：拉已收听集号（绿色小对勾）
        if (App.book && App.book.key) TS.call('listenedMarks', App.book.key);
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
                 // ★ v3.7.16：加 data-mq —— 名称过长时由 bridge.js 的
                 //   markMarquee 自动接管成跑马灯（多列布局下 CSS 会关掉）。
                 '<span class="chtt" data-mq>' +
                 TS.esc(ch.title || ('第' + no + '集')) + '</span>' +
                 // ★ v3.9.7：已收听 → 低调灰绿小对勾（旧「已」字圆圈删除，
                 //   修掉与对勾并存的双徽标，详情页与选集弹层现在样式一致）
                 (isListened(no) ? '<span class="chdone" title="已收听">✓</span>' : '') +
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

    /**
     * ★ v3.7.13：选集弹层里的"列数 / 区间"下拉。
     *
     * <p>以前是自己一套 `.chs-dd` 样式，和历史/收藏长按弹的 `.ctx-menu` 观感不一致。
     * 现在统一走 {@link ctxMenu}（同一份 CSS、同一个动画、同一套贴边收敛逻辑），
     * 只是从"按钮正下方右对齐"的位置弹出而已。
     */
    function chsDropdown(anchorEl, items) {
        ctxClose();
        if (!items || !items.length) return;
        // 锚点：按钮正下方、右对齐（按钮都在弹层右上角）。
        // ctxMenu 内部会做贴边收敛，这里只要给个合理的起始点。
        var r = anchorEl.getBoundingClientRect();
        ctxMenu(Math.max(8, r.right - 190), Math.round(r.bottom + 6), items);
    }

    function chsCloseDropdown() {
        // 统一菜单只有一个实例，直接收掉即可
        ctxClose();
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
                 (it.on ? ' on' : '') + (it.disabled ? ' disabled' : '') +
                 '" data-i="' + i + '">' +
                 '<span class="ci-ico">' + (it.icon || '') + '</span>' +
                 '<span class="ci-tx">' + it.label + '</span>' +
                 // ★ v3.7.13：有值的项（如"倍速 1.5x"）右侧补一段次要文字，
                 //   与 old .more-drop 的信息密度对齐
                 (it.val ? '<span class="ci-val">' + it.val + '</span>' : '') +
                 '</div>';
        }
        m.innerHTML = h;
        document.body.appendChild(m);

        // ---- 定位 ----
        //
        // ★ v3.7.16：加两道硬约束 —— 菜单**绝不超出屏幕**。
        //   .ctx-menu 已有 max-width:82vw + overflow-x:auto（长文案横向滑动），
        //   所以在量宽度前先把 max-width 落到实际像素上再收敛位置。
        var r = m.getBoundingClientRect();
        var vw = window.innerWidth, vh = window.innerHeight;
        var maxW = Math.max(120, vw - 16);          // 左右各留 8px 呼吸位
        if (r.width > maxW) {
            m.style.maxWidth = maxW + 'px';
            r = m.getBoundingClientRect();
        }
        var left = x, top = y;
        if (left + r.width + 8 > vw) left = Math.max(8, vw - r.width - 8);
        if (left < 8) left = 8;
        if (top + r.height + 8 > vh) {
            // 下方放不下 → 翻到锚点上方（贴边菜单的常见做法）
            top = Math.max(8, y - r.height - 8);
        }
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

        // ---- 点别处 / 滚动 → 收起 ----
        //
        // ★ v3.7.16 关键修复：原来直接
        //     document.addEventListener('click', ctxClose, {once:true})
        //   是**在打开菜单的那一次 click 事件里**注册的 —— 此时事件还在
        //   向上冒泡，随后就会命中 document 上的这个监听器，菜单当场被拆掉。
        //   表现就是"点『更多』/ 点选集下拉按钮毫无反应"（菜单闪一下或干脆没有）。
        //   修法：把注册推迟到本轮事件彻底结束后（setTimeout 0 本来就在下一
        //   macrotask，但 move/up 系列仍可能同批到达），再加一个 flag 双保险：
        //   只有"打开之后新发生的"交互才允许关闭菜单。
        setTimeout(function () {
            var closed = false;
            function close() {
                if (closed) return;
                closed = true;
                detach();
                ctxClose();
            }
            function detach() {
                document.removeEventListener('pointerdown', onDown, true);
                document.removeEventListener('scroll', onScroll, true);
            }
            function onDown(e) {
                // 点在菜单内部 → 交给菜单自己的 click 处理，不在这关
                var t = e.target;
                while (t) {
                    if (t.id === 'ctxMenu') return;
                    t = t.parentNode;
                }
                close();
            }
            // ★ v3.8.1：滚动同样要豁免「菜单自己滚」。
            //   菜单受 max-height:56vh + overflow-y:auto 限制（约 9~10 项），
            //   而章节分段每段 200 章 —— 超过 2000 章就有 10 段以上，必须滚动才能
            //   点到下面的段。以前 onScroll 无豁免，一滚就把菜单关掉，
            //   表现就是「书一过 2000 章，后面的分段点不出来」。
            function onScroll(e) {
                var t = e.target;
                while (t) {
                    if (t.id === 'ctxMenu') return;
                    t = t.parentNode;
                }
                close();
            }
            // 用 pointerdown 而不是 click：它在下拉/滚动开始时就到，
            // 且不会与"打开菜单那次 click"混在一起。捕获阶段确保先收到。
            document.addEventListener('pointerdown', onDown, true);
            document.addEventListener('scroll', onScroll, true);
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
    /**
     * ★ v3.7.13：把「更多」下拉换成统一的长按菜单（.ctx-menu）。
     *
     * <p>以前是一套自己的 `.more-drop` 气泡，跟历史/收藏长按弹出的菜单两套观感。
     * 现在统一走 {@link ctxMenu}。这里仍保留 `ui.closeMore` / `moreToggle.on`
     * 这些老接口，避免别处的联动失效。
     */
    ui.moreMenu = function () {
        var btn = $('moreToggle');
        if (!btn) return;
        // 已经开着就关掉（再点一次 = 收起）
        if (App._moreOpen) { ui.closeMore(); return; }

        var sp = App.speed || 1.0;
        var items = [
            {
                label: '倍速', icon: '⏩', val: sp.toFixed(1) + 'x',
                pick: function () { ui.moreSpeed(); }
            },
            {
                label: '音量增益', icon: '🔊', val: gainLabel(App.gain),
                pick: function () { ui.moreGain(); }
            },
            {
                // ★ v3.8.1 跳过片头：起播时自动快进掉片头曲
                label: '跳过片头', icon: '⏭', val: skipIntroLabel(),
                pick: function () { ui.moreSkipIntro(); }
            },
            {
                // ★ v3.8.2 跳过片尾：放到结尾前自动收尾（片尾曲/结尾广告）
                label: '跳过片尾', icon: '⏩', val: skipOutroLabel(),
                pick: function () { ui.moreSkipOutro(); }
            }
        ];
        App._moreOpen = true;
        if (btn) btn.classList.add('on');
        // 挂在按钮**左下方**（按钮在右上角，往右下走会顶到屏幕边）
        var r = btn.getBoundingClientRect();
        ctxMenu(Math.max(8, r.right - 172), Math.round(r.bottom + 8), items);
        // 菜单被别处收起时同步按钮高亮
        var t = setInterval(function () {
            if (!$('ctxMenu')) { clearInterval(t); ui._moreSyncOff(); }
        }, 200);
    };

    /** 菜单不在 → 把按钮高亮摘掉（不递归调 closeMore） */
    ui._moreSyncOff = function () {
        App._moreOpen = false;
        var btn = $('moreToggle');
        if (btn) btn.classList.remove('on');
    };

    /** 收起「更多」下拉（点项、点按钮、点别处都走这里） */
    ui.closeMore = function () {
        App._moreOpen = false;
        ctxClose();
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

    // ==================== ★ v3.8.1/v3.8.2 跳过片头 / 跳过片尾 ====================
    //
    // 片头：起播时自动快进掉开头若干秒（片头曲/开场白）。
    //   · 只对「没有续播进度」的集生效 —— 听到一半的集仍然从原位置续听，
    //     否则每次续听都会被再往前推一次，越听越靠后；
    //   · 0 秒 = 关闭；0~300 秒可调。
    // 片尾：播放到距结尾不足若干秒时自动收尾（片尾曲/结尾广告）。
    //   · 按播放位置判断，跳过后按播放模式进下一集 / 重播 / 停止；
    //   · 手动拖动进度到结尾区同样会生效；
    //   · 0 秒 = 关闭；0~300 秒可调。

    /** 「跳过片头」当前设置的人话描述（显示在菜单右侧） */
    function skipIntroLabel() {
        var s = App.skipIntro || 0;
        return s > 0 ? (s + ' 秒') : '关';
    }

    /** 「跳过片尾」当前设置的人话描述（显示在菜单右侧） */
    function skipOutroLabel() {
        var s = App.skipOutro || 0;
        return s > 0 ? (s + ' 秒') : '关';
    }

    /** 菜单里选「跳过片头」→ 弹出微调面板 */
    ui.moreSkipIntro = function () {
        ui.closeMore();
        openSkipSheet('intro', '跳过片头', App.skipIntro || 0,
            '起播时自动快进开头这段时间（片头曲 / 开场白）。'
            + '<br><span style="opacity:.7">听到一半的集仍从原位置续听，不会被再推一次。</span>');
    };

    /** ★ v3.8.2：菜单里选「跳过片尾」→ 弹出微调面板 */
    ui.moreSkipOutro = function () {
        ui.closeMore();
        openSkipSheet('outro', '跳过片尾', App.skipOutro || 0,
            '播放到距离结尾还有这段时间时自动收尾，按播放模式进下一集（片尾曲 / 结尾广告）。'
            + '<br><span style="opacity:.7">拖动进度到结尾区同样会生效；单集循环会重播本集。</span>');
    };

    /**
     * 跳过片头/片尾共用的微调面板。
     * @param {string} kind  'intro' | 'outro'
     * @param {string} title 面板标题
     * @param {number} cur   当前秒数
     * @param {string} desc  说明文字（可含 <br>）
     */
    function openSkipSheet(kind, title, cur, desc) {
        var presets = [0, 10, 15, 20, 30, 45, 60, 90];
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">' + title + '</div>';
        h += '<div class="cf-msg">' + desc + '</div>';
        for (var i = 0; i < presets.length; i++) {
            var v = presets[i];
            h += '<div class="sheet-item' + (v === cur ? ' on' : '') + '" data-skip="' + v + '">'
                + (v === 0 ? '关闭' : v + ' 秒') + '</div>';
        }
        // 复用 .diag-act（已有的一行按钮容器），不新造 CSS
        h += '<div class="diag-act">';
        h += '<button class="mini ghost" id="skipMinus">−5 秒</button>';
        h += '<input class="sheet-input" id="skipVal" type="number" min="0" max="300" step="5" value="'
            + cur + '" style="width:84px;text-align:center;flex:0 0 auto">';
        h += '<button class="mini ghost" id="skipPlus">+5 秒</button>';
        h += '</div>';
        h += '<div class="sheet-item" id="skipOk">确定</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);

        // ★ 委托必须挂在 .sheet 上（而不是 #sheetWrap）：
        //   .sheet 自带 onclick="event.stopPropagation()"（防点内部时冒泡到遮罩
        //   把弹窗关掉），所以事件根本到不了外层容器。
        //   用 addEventListener 而不是 .onclick，避免把那个内联 handler 覆盖掉。
        var box = document.querySelector('#sheetWrap .sheet') || $('sheetWrap');
        if (box) box.addEventListener('click', function (e) {
            var t = e.target || e.srcElement;
            if (!t) return;
            var v = t.getAttribute && t.getAttribute('data-skip');
            if (v !== null && v !== undefined && v !== '') {
                var el = $('skipVal');
                if (el) el.value = v;
                return;
            }
            if (t.id === 'skipMinus') { adj(-5); return; }
            if (t.id === 'skipPlus') { adj(5); return; }
            if (t.id === 'skipOk') {
                var inp = $('skipVal');
                var n = parseInt(inp ? inp.value : '0', 10);
                if (isNaN(n) || n < 0) n = 0;
                if (n > 300) n = 300;
                ui.closeSheet();
                applySkip(kind, n);
            }
        });

        function adj(d) {
            var inp = $('skipVal');
            if (!inp) return;
            var n = parseInt(inp.value, 10);
            if (isNaN(n)) n = 0;
            n += d;
            if (n < 0) n = 0;
            if (n > 300) n = 300;
            inp.value = n;
        }
    }

    /** 把秒数落盘并提示（原生负责持久化，这里同步本地缓存以便菜单立刻显示） */
    function applySkip(kind, sec) {
        if (kind === 'outro') {
            App.skipOutro = sec;
            TS.call('setSkipOutro', sec);
            TS.toast(sec > 0 ? ('已开启跳过片尾 ' + sec + ' 秒') : '已关闭跳过片尾');
        } else {
            App.skipIntro = sec;
            TS.call('setSkipIntro', sec);
            TS.toast(sec > 0 ? ('已开启跳过片头 ' + sec + ' 秒') : '已关闭跳过片头');
        }
    }

    /** 原生回调：跳过片头设置已保存 */
    window.onSkipIntro = function (o) {
        o = o || {};
        if (typeof o.sec === 'number') App.skipIntro = o.sec;
    };

    /** ★ v3.8.2：原生回调：跳过片尾设置已保存 */
    window.onSkipOutro = function (o) {
        o = o || {};
        if (typeof o.sec === 'number') App.skipOutro = o.sec;
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
    var GAIN_MAX = 300;   // ★ v3.9.53：恢复 300（换算改对数式后 300% 仅 +9.5dB，安全）
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
            '<span>150%</span><span>200%</span><span>300%</span></div>' +
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

    // ==================== 定时关闭（★ v3.9.0 支持可选时段） ====================

    /**
     * 定时关闭面板。
     *
     * ★ v3.9.0 改版：
     *   ① 倒计时权威逻辑移到 native（SleepTimer）—— 旧实现是页面里的
     *      setInterval，用户退回首页/App 退后台页面被回收后倒计时就消失了，
     *      到点不停；现在页面只负责设置与展示；
     *   ② 新增「时段关闭」：可再选一个时段（如 22:00–06:00，支持跨午夜），
     *      只有当前时刻在时段内倒计时才递减，时段外自动停表、剩余时间保留。
     */
    /**
     * 定时关闭面板（★ v3.9.8 改为下拉式）。
     *
     * 旧版每档一行平铺，加「按集停止」后总行数超出一屏，底部选项够不着。
     * 现在倒计时/按集各一个下拉框，选中即生效；时段保持双下拉。
     */
    ui.sleepMenu = function () {
        var s = App.sleep || {};
        var cur = (typeof s.min === 'number') ? s.min : 0;
        var eps = (typeof s.eps === 'number') ? s.eps : 0;
        var ws = (typeof s.winStart === 'number' && s.winStart >= 0) ? s.winStart : -1;
        var we = (typeof s.winEnd === 'number' && s.winEnd >= 0) ? s.winEnd : -1;

        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">定时关闭</div>';

        // ---- 倒计时（下拉，选中即生效） ----
        h += '<div class="sheet-sec">倒计时关闭</div>';
        h += '<div class="sleep-row"><select id="sleepMinSel" class="sel" onchange="App.ui.sleepPick(this.value)">' +
             minOpts(cur) + '</select></div>';

        // ---- 按集停止（下拉 + 自定义集数输入，★ v3.9.34） ----
        h += '<div class="sheet-sec">按集停止</div>';
        h += '<div class="sleep-row"><select id="sleepEpsSel" class="sel" onchange="App.ui.sleepPickEps(this.value)">' +
             epsOpts(eps) + '</select></div>';
        h += '<div class="sleep-row" id="sleepEpsCustom" style="display:' +
             (eps > 5 ? 'flex' : 'none') + '">' +
             '<input id="sleepEpsInput" type="number" min="1" max="99" class="sel" ' +
             'style="flex:1" placeholder="自定义集数（1~99）" value="' + (eps > 5 ? eps : '') + '">' +
             '<div class="sheet-item" style="margin:0;padding:10px 14px" ' +
             'onclick="App.ui.sleepPickEpsInput()">确定</div></div>';

        // ---- 时段关闭 ----
        h += '<div class="sheet-sec">可选时段关闭</div>';
        h += '<div class="sheet-tip">只在所选时段内倒计时；时段外自动停表，剩余时间保留，' +
             '进入时段后继续。不选时段则全天生效。</div>';
        h += '<div class="sleep-win">';
        h += '<select id="sleepWs" class="sel">' + winOpts(ws) + '</select>';
        h += '<span class="sleep-win-x">至</span>';
        h += '<select id="sleepWe" class="sel">' + winOpts(we) + '</select>';
        h += '</div>';
        h += '<div class="sheet-item" onclick="App.ui.sleepSaveWin()">保存时段设置</div>';

        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    /** 倒计时下拉选项（0 = 关闭） */
    function minOpts(cur) {
        var opts = [[0, '关闭'], [10, '10 分钟'], [15, '15 分钟'], [20, '20 分钟'],
                    [30, '30 分钟'], [45, '45 分钟'], [60, '1 小时'],
                    [90, '1.5 小时'], [120, '2 小时']];
        var h = '';
        for (var i = 0; i < opts.length; i++) {
            h += '<option value="' + opts[i][0] + '"' + (cur === opts[i][0] ? ' selected' : '') +
                 '>' + opts[i][1] + '</option>';
        }
        return h;
    }

    /** 按集停止下拉选项（0 = 不启用） */
    function epsOpts(cur) {
        var opts = [[0, '不启用'], [1, '播完本集'], [2, '再听 2 集'],
                    [3, '再听 3 集'], [5, '再听 5 集']];
        if (cur > 5) opts.push([cur, '再听 ' + cur + ' 集（自定义）']);
        opts.push([-1, '自定义集数…']);
        var h = '';
        for (var i = 0; i < opts.length; i++) {
            h += '<option value="' + opts[i][0] + '"' + (cur === opts[i][0] ? ' selected' : '') +
                 '>' + opts[i][1] + '</option>';
        }
        return h;
    }

    /** 时段下拉选项（每 15 分钟一档；-1 = 不选） */
    function winOpts(cur) {
        var h = '<option value="-1"' + (cur < 0 ? ' selected' : '') + '>不指定</option>';
        for (var m = 0; m < 24 * 60; m += 15) {
            h += '<option value="' + m + '"' + (cur === m ? ' selected' : '') + '>' +
                 two2(Math.floor(m / 60)) + ':' + two2(m % 60) + '</option>';
        }
        return h;
    }
    function two2(n) { return n < 10 ? '0' + n : '' + n; }

    ui.closeSheet = function () {
        // ★ v3.9.1：退出确认框被关掉（点遮罩 / 点取消 / 或确认后自动关）时，
        //   必须把「确认框正在显示」的标记清掉，否则下一次在首页按返回
        //   会被误判成"连按第二次"而直接退出。
        App.exitSheet = false;
        // ★ v4.2.0：扫码登录弹窗关闭 → 停掉 native 轮询（不浪费流量与电量）
        if (App.qrLogin) { App.qrLogin = null; TS.call('panQrStop'); }
        var w = $('sheetWrap');
        if (w && w.parentNode) w.parentNode.removeChild(w);
    };

    /**
     * 倒计时选择（★ v3.9.8 起从下拉框触发：选中即生效但**不关面板**，
     * 方便接着设时段；min 由 select.value 传入是字符串，需转数字）。
     */
    ui.sleepPick = function (minStr) {
        var min = parseInt(minStr, 10);
        if (isNaN(min)) return;
        App.sleepMin = min;
        if (App.sleepTimer) { clearInterval(App.sleepTimer); App.sleepTimer = null; }
        var s = App.sleep || {};
        var ws = (typeof s.winStart === 'number' && s.winStart >= 0) ? s.winStart : -1;
        var we = (typeof s.winEnd === 'number' && s.winEnd >= 0) ? s.winEnd : -1;
        if (min <= 0) {
            App.sleepAt = 0;
            TS.call('setSleep', 0, -1, -1);
            TS.toast('定时关闭已取消');
        } else {
            App.sleepAt = Date.now() + min * 60000;   // 仅用于面板内预估，权威在 native
            TS.call('setSleep', min, ws, we);
            TS.toast(min + ' 分钟后自动暂停' + (ws >= 0 && we >= 0 ? '（仅时段内计时）' : ''));
        }
        paintSleepBtn();
    };

    /** ★ v3.9.7：按集停止选择（n=0 取消；下拉触发不关面板） */
    ui.sleepPickEps = function (nStr) {
        var n = parseInt(nStr, 10);
        if (isNaN(n)) return;
        var custom = $('sleepEpsCustom');
        if (n === -1) {   // 自定义：展开输入行，不立即生效
            if (custom) custom.style.display = 'flex';
            var inp = $('sleepEpsInput');
            if (inp) inp.focus();
            return;
        }
        if (custom) custom.style.display = 'none';
        TS.call('setSleepEpisodes', n);
        TS.toast(n > 0 ? ('播完 ' + n + ' 集后自动暂停') : '已取消按集停止');
        paintSleepBtn();
    };

    /** ★ v3.9.34：自定义集数输入确认 */
    ui.sleepPickEpsInput = function () {
        var inp = $('sleepEpsInput');
        if (!inp) return;
        var n = parseInt(inp.value, 10);
        if (isNaN(n) || n < 1 || n > 99) { TS.toast('请输入 1~99 的集数'); return; }
        ui.sleepPickEps(String(n));
    };

    /** 保存时段（不动倒计时） */
    ui.sleepSaveWin = function () {
        var wsEl = $('sleepWs'), weEl = $('sleepWe');
        var ws = wsEl ? parseInt(wsEl.value, 10) : -1;
        var we = weEl ? parseInt(weEl.value, 10) : -1;
        if (isNaN(ws)) ws = -1;
        if (isNaN(we)) we = -1;
        var min = (App.sleep && App.sleep.min) || 0;
        TS.call('setSleep', min, ws, we);
        TS.toast(ws >= 0 && we >= 0
            ? ('已设时段 ' + two2(Math.floor(ws / 60)) + ':' + two2(ws % 60) + '–' +
               two2(Math.floor(we / 60)) + ':' + two2(we % 60))
            : '已取消时段限制');
    };

    /** native 推送的定时状态（设置/停表/到点/进程恢复都会推） */
    window.onSleep = function (o) {
        App.sleep = o || null;
        paintSleepBtn();
    };

    /** ★ v3.9.0：native 回传某本书已收听的集号（1 基） */
    window.onListenedMarks = function (o) {
        if (!o || !o.key) return;
        var set = {};
        var arr = o.idx || [];
        for (var i = 0; i < arr.length; i++) set[arr[i]] = true;
        App.listened[o.key] = set;
        // 选集弹层 / 详情页正开着就更新 —— ★ v3.9.3 详情页改**就地刷新**
        // （只增删「已」徽标，不再整页重绘：之前用户刚开始滑列表时标记
        // 数据到达 → 全页重建 → 卡一下 + 弹回顶部）
        if (App.page === SUBS.detail && App.book && App.book.key === o.key) paintChapMarks();
        if ($('chSheetWrap')) repaintChsList(true);
    };

    /** 当前书某集是否已收听 */
    function isListened(no) {
        var b = App.book;
        if (!b || !b.key) return false;
        var set = App.listened[b.key];
        return !!(set && set[no]);
    }


    /** 当前书某集是否已收听 */
    function isListened(no) {
        var b = App.book;
        if (!b || !b.key) return false;
        var set = App.listened[b.key];
        return !!(set && set[no]);
    }

    function paintSleepBtn() {
        var el = $('sleepBtn');
        if (!el) return;
        var s = App.sleep;
        // ★ v3.9.7：按集停止也在按钮上可见（无倒计时时单独展示）
        if (s && s.eps > 0 && !(s.min > 0 && s.remain > 0)) {
            el.textContent = '⏱ ' + s.eps + '集后停';
            el.classList.add('on');
            return;
        }
        if (s && s.min > 0 && s.remain > 0) {
            var m = Math.max(1, Math.ceil(s.remain / 60));
            var t = '⏱ ' + (m >= 60 ? (Math.floor(m / 60) + '时' + (m % 60 ? (m % 60) + '分' : '')) : m + '分');
            if (s.holding) t += ' · 停表';
            if (s.eps > 0) t += ' · ' + s.eps + '集';
            el.textContent = t;
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
        reconcileHomeSources();
        paintSourceEntry();
        if (App.page === SUBS.sources) paintSources();
        else if (App.page === SUBS.srcGroup) {
            // 源列表变了：组内可能被删空 → 退回一级页
            var b = bucketsOf();
            if (!(b[App.srcGroupKey] || []).length) { ui.back(); return; }
            paintSrcGroup();
        } else if (App.page === SUBS.custom) {
            // ★ v3.9.0：自定义源页（panReady/enabled 变化后重绘徽章与开关）
            paintCustom();
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
        // 名字映射更新后，刷新依赖它的视图（v3.9.3 保滚动位）
        if (App.page === SUBS.detail) renderDetail(true);
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
        // ★ v3.9.0：自定义源置顶 —— 本地书源 + 网盘接口都从这里进
        { k: 'custom', t: '自定义源', sub: '本地书源 + 主流网盘接口' },
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
        // ★ v3.9.0：本地书源 / 网盘接口 → 自定义源组（要先于 builtin 判断：
        //   这两类源 native 标了 builtin=true 表示"不可删除"，但不是内置源组）
        var o0 = s.origin || '';
        if (o0 === 'local' || o0 === 'pan') return 'custom';
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
        h += '<div class="srcops srcops-main" style="padding:0 14px 10px">' +
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
        // ★ v3.9.0：自定义源组是「功能页」不是普通源列表 ——
        //   本地书源要能扫描/重扫，网盘接口要能配 Cookie/解析链接，
        //   这些交互塞不进通用的「一行一源 + 长按菜单」列表里，
        //   所以单独走 pageCustom（#pageCustom）。
        if (k === 'custom') {
            goSub('custom');
            paintCustom();
            // 进来先拉一次已扫描的本地书列表（不触发重扫）；
            // 一次都没扫过时返回空表，页面显示"尚未扫描"
            TS.call('localBooks');
            return;
        }
        goSub('srcGroup');
        paintSrcGroup();
    };

    // ==================== ★ v3.9.0 自定义源页：本地书源 + 网盘接口 ====================

    /**
     * 自定义源页状态。native 回包只更新这个对象 + 重绘，不在回调里拼 DOM，
     * 避免扫描/解析途中用户切页回来看到半截状态。
     */
    App.custom = {
        scanning: false,
        count: -1,          // -1 = 还没扫过；0 = 扫过但没有
        error: '',
        needPerm: false,
        root: '',           // ★ v3.9.1 自定义扫描目录（'' = 扫全机）
        books: [],          // localBooks 回包（Book.toJson）
        pan: {}             // pid -> { shareUrl, files:[], picked:{} }
    };

    /** 六个网盘平台（顺序即展示顺序；id 与 PanResolver 常量一致） */
    var PAN_LIST = ['pan_ali', 'pan_baidu', 'pan_quark', 'pan_tianyi', 'pan_115', 'pan_uc'];
    var PAN_NAMES = {
        pan_ali: '阿里云盘', pan_baidu: '百度网盘', pan_quark: '夸克网盘',
        pan_tianyi: '天翼云盘', pan_115: '115网盘', pan_uc: 'UC网盘'
    };
    var PAN_TIPS = {
        pan_ali: '网页登录自动抓取，也可粘贴 Cookie 兜底',
        pan_baidu: '网页登录自动抓取，也可粘贴 Cookie 兜底',
        pan_quark: '网页登录自动抓取，也可粘贴 Cookie 兜底',
        pan_tianyi: '网页登录 / 密码登录均可，自动抓取',
        pan_115: '网页登录 / 扫码登录均可，自动抓取',
        pan_uc: '网页登录自动抓取，也可粘贴 Cookie 兜底'
    };

    function panState(pid) {
        var p = App.custom.pan[pid];
        if (!p) { p = { shareUrl: '', files: [], picked: {}, scanBooks: null, scanScanning: false }; App.custom.pan[pid] = p; }
        if (p.scanBooks === undefined) p.scanBooks = null;
        if (p.scanScanning === undefined) p.scanScanning = false;
        return p;
    }

    /** App.sources 里的本地书源对象（启用/停用开关要读它的 enabled） */
    function localSrc() {
        var arr = App.sources || [];
        for (var i = 0; i < arr.length; i++) if (arr[i].id === 'local') return arr[i];
        return null;
    }

    function panSrc(pid) {
        var arr = App.sources || [];
        for (var i = 0; i < arr.length; i++) if (arr[i].id === pid) return arr[i];
        return null;
    }

    /** 文件大小人性化 */
    function panSize(n) {
        n = n || 0;
        if (n >= 1073741824) return (n / 1073741824).toFixed(1) + 'G';
        if (n >= 1048576) return (n / 1048576).toFixed(0) + 'M';
        if (n >= 1024) return (n / 1024).toFixed(0) + 'K';
        return n + 'B';
    }

    function paintCustom() {
        var c = $('customPageContent');
        if (!c) return;
        var t = $('customTitle');
        if (t) t.textContent = '自定义源';

        var h = '';
        h += '<div class="tip">本地书源播放本机音频（音乐/听书 App 下载的文件夹都能识别）；' +
             '网盘接口把分享链接包成书源，解析后直接入队播放，自动下一集、' +
             '历史续播、通知栏一样不少。</div>';

        // ============ 本地书源卡片 ============
        var ls = localSrc();
        var lsOn = !!(ls && ls.enabled);
        var st = App.custom;
        h += '<div class="cu-card">';
        h += '<div class="cu-hd">本地书源' +
             '<label class="sw"><input type="checkbox" ' + (lsOn ? 'checked' : '') +
             ' onchange="App.ui.customToggleLocal(this.checked)"><i></i></label></div>';
        h += '<div class="cu-bd">';
        h += '<div class="cu-desc">扫描本机存储里的音频文件，按所在文件夹自动分成一本书，' +
             '文件名即章节名（001、002 自然排序）。全程本地播放，不耗流量。</div>';

        // ★ v3.9.1：自定义扫描目录（默认扫全机，可指定一个文件夹）
        if (st.root) {
            h += '<div class="cu-root">扫描范围：<b>' + TS.esc(st.root) + '</b></div>';
        } else {
            h += '<div class="cu-root dim">扫描范围：全部目录</div>';
        }
        h += '<div class="cu-ops">';
        h += '<button class="mini ghost" onclick="App.ui.customLocalPickDir()">选择文件夹</button>';
        if (st.root) {
            h += '<button class="mini ghost" onclick="App.ui.customLocalSetDir(\'\')">恢复全部目录</button>';
        }
        h += '</div>';

        h += '<div class="cu-ops">';
        h += '<button class="mini" onclick="App.ui.customLocalScan()">' +
             (st.scanning ? '扫描中…' : '扫描本机音频') + '</button>';
        h += '<button class="mini ghost" onclick="App.ui.customLocalBooks()">刷新列表</button>';
        h += '</div>';

        if (st.needPerm) {
            h += '<div class="cu-warn">需要存储访问权限才能扫描音频文件 ' +
                 '<button class="mini" onclick="App.ui.customAskPerm()">去授权</button></div>';
        } else if (st.error) {
            h += '<div class="cu-err">' + TS.esc(st.error) + '</div>';
        } else if (st.count === 0) {
            h += '<div class="cu-desc dim">没有找到音频文件。确认本机有音乐/有声书文件后重试；' +
                 '部分系统需要手动授予「音乐和音频」权限。</div>';
        } else if (st.count > 0) {
            h += '<div class="cu-ok">已扫描到 ' + st.count + ' 本本地书</div>';
        } else {
            h += '<div class="cu-desc dim">尚未扫描，点上方「扫描本机音频」开始。</div>';
        }

        if (st.books.length) {
            h += '<div class="cu-books">';
            for (var i = 0; i < st.books.length; i++) {
                var bk = st.books[i];
                h += '<div class="cu-book" onclick="App.ui.customOpenLocal(' + i + ')">';
                h += '<div class="cu-book-t">' + TS.esc(bk.title || bk.name || '未命名') + '</div>';
                h += '<div class="cu-book-s">' + TS.esc(bk.url || '') + '</div>';
                h += '</div>';
            }
            h += '</div>';
        }
        h += '</div></div>';

        // ============ 网盘接口卡片（每平台一张） ============
        for (var p = 0; p < PAN_LIST.length; p++) {
            var pid = PAN_LIST[p];
            var ps = panSrc(pid);
            var pOn = !!(ps && ps.enabled && ps.panReady);
            var pReady = !!(ps && ps.panReady);
            var st2 = panState(pid);

            h += '<div class="cu-card">';
            h += '<div class="cu-hd">' + PAN_NAMES[pid] +
                 '<span class="cu-badge' + (pReady ? ' ok' : '') + '">' +
                 (pReady ? '已配置' : '未配置') + '</span>' +
                 '<label class="sw"><input type="checkbox" ' + (pOn ? 'checked' : '') +
                 ' onchange="App.ui.customTogglePan(\'' + pid + '\', this.checked)"><i></i></label></div>';
            h += '<div class="cu-bd">';
            h += '<div class="cu-desc">' + TS.esc(PAN_TIPS[pid]) + '</div>';
            h += '<div class="cu-ops">';
            // ★ v4.4.0：全部网盘支持「网页登录」——内嵌 WebView 打开官方登录页，
            //   登录成功自动抓 Cookie；115 扫码、天翼密码登录仍保留。
            h += '<button class="mini" onclick="App.ui.customPanWebLogin(\'' + pid + '\')">网页登录</button>';
            if (pid === 'pan_115') {
                h += '<button class="mini" onclick="App.ui.customPanQr(\'' + pid + '\')">扫码登录</button>';
            }
            if (pid === 'pan_tianyi') {
                h += '<button class="mini" onclick="App.ui.customPanPwd(\'' + pid + '\')">密码登录</button>';
            }
            // ★ v4.5.0：已登录的网盘可扫描盘内有声书（阿里网页接口有签名风控除外）
            if (pid !== 'pan_ali') {
                h += '<button class="mini" onclick="App.ui.customPanScan(\'' + pid + '\')">' +
                     (st2.scanScanning ? '扫描中…' : '扫描盘内书') + '</button>';
            }
            h += '<button class="mini ghost" onclick="App.ui.customPanCookie(\'' + pid + '\')">配置 Cookie</button>';
            h += '<button class="mini ghost" onclick="App.ui.customPanClear(\'' + pid + '\')">清除</button>';
            h += '</div>';

            // ---- 盘内有声书扫描结果（v4.5.0；v4.6.0 重排：独立行布局，
            //      书名+集数左、播放按钮右，不再挤进 cu-hd 标题行） ----
            if (st2.scanBooks && st2.scanBooks.length) {
                h += '<div class="panbook-hd">盘内扫到 ' + st2.scanBooks.length +
                     ' 本有声书（文件夹里全是音频才收录）</div>';
                h += '<div class="panbook-list">';
                for (var bi = 0; bi < st2.scanBooks.length; bi++) {
                    var bk = st2.scanBooks[bi];
                    h += '<div class="panbook-row">' +
                         '<div class="panbook-main">' +
                         '<div class="panbook-name">' + TS.esc(bk.name) + '</div>' +
                         '<div class="panbook-sub">' + (bk.files ? bk.files.length : 0) + ' 集</div>' +
                         '</div>' +
                         '<button class="mini" onclick="App.ui.customPanDiskPlay(\'' + pid + '\',' + bi + ')">▶ 播放</button>' +
                         '</div>';
                }
                h += '</div>';
            } else if (st2.scanScanning) {
                h += '<div class="cu-desc dim">正在扫描网盘目录…</div>';
            }

            // ---- 解析分享链接 ----
            h += '<div class="cu-parse">';
            h += '<input class="cu-input" id="panShare_' + pid + '" placeholder="粘贴分享链接，如 ' +
                 'https://pan.quark.cn/s/xxxx" value="' + TS.esc(st2.shareUrl || '') + '">';
            h += '<button class="mini" onclick="App.ui.customPanParse(\'' + pid + '\")"' +
                 (st2.parsing ? ' disabled' : '') + '>' +
                 (st2.parsing ? '解析中…' : '解析') + '</button>';
            h += '</div>';

            if (st2.error) {
                h += '<div class="cu-err">' + TS.esc(st2.error) + '</div>';
            }

            // ---- 文件清单 ----
            if (st2.files.length) {
                var nPick = 0, k;
                for (k in st2.picked) if (st2.picked[k]) nPick++;
                h += '<div class="cu-files">';
                h += '<div class="cu-files-hd">解析到 ' + st2.files.length + ' 项' +
                     (nPick ? '（已选 ' + nPick + '）' : '') + '</div>';
                for (var fi = 0; fi < st2.files.length; fi++) {
                    var f = st2.files[fi];
                    // 勾选键用数组下标：文件 id 可能含特殊字符，拼进 HTML 属性不安全
                    if (f.dir) {
                        h += '<div class="cu-file dir">📁 ' + TS.esc(f.name || '') + '</div>';
                        continue;
                    }
                    h += '<label class="cu-file">';
                    h += '<input type="checkbox" ' + (st2.picked[fi] ? 'checked' : '') +
                         ' onchange="App.ui.customPanPick(\'' + pid + '\', ' + fi +
                         ', this.checked)">';
                    h += '<span class="cu-file-t">' + TS.esc(f.name || '') + '</span>';
                    h += '<span class="cu-file-s">' + panSize(f.size) + '</span>';
                    h += '</label>';
                }
                h += '<div class="cu-ops">';
                h += '<button class="mini ghost" onclick="App.ui.customPanAll(\'' + pid +
                     '\', true)">全选</button>';
                h += '<button class="mini ghost" onclick="App.ui.customPanAll(\'' + pid +
                     '\', false)">全不选</button>';
                h += '<button class="mini" onclick="App.ui.customPanPlay(\'' + pid + '\')">播放选中</button>';
                h += '</div>';
                h += '</div>';
            }
            h += '</div></div>';
        }

        h += '<div class="tip">网盘直链每次播放时现取、不落库（直链有时效）；' +
             'Cookie 只存在本机。接口变动或 Cookie 失效时会给出明确错误，' +
             '不会静默失败。</div>';
        c.innerHTML = h;
    }

    // ---------- 本地书源交互 ----------

    ui.customLocalScan = function () {
        App.custom.scanning = true;
        App.custom.error = '';
        App.custom.needPerm = false;
        paintCustom();
        TS.call('localScan');
    };

    ui.customLocalBooks = function () {
        TS.call('localBooks');
    };

    ui.customAskPerm = function () {
        TS.call('requestStoragePerm');
    };

    /** 打开系统目录选择器（SAF），挑一个文件夹作为扫描根目录 */
    ui.customLocalPickDir = function () {
        TS.call('localPickDir');
    };

    /**
     * 设置扫描目录（'' = 全部）。
     * 选择器还原不出真实路径的 ROM 走这里：让用户直接粘贴路径。
     */
    ui.customLocalSetDir = function (dir) {
        if (dir == null) return;
        if (dir === '' ) {
            TS.call('localSetDir', '');
            return;
        }
        ui.inputSheet('扫描目录', dir, function (v) {
            var p = (v || '').trim();
            if (!p) { TS.toast('路径为空，已恢复扫描全部目录'); }
            TS.call('localSetDir', p);
        });
    };

    ui.customToggleLocal = function (on) {
        TS.call('setSourceEnabled', 'local', !!on);
        TS.toast(on ? '本地书源已启用' : '本地书源已停用');
    };

    /** 打开一本本地书 → 直接走标准详情流程（detailFromJson → 章节表 → 播放） */
    ui.customOpenLocal = function (i) {
        var b = App.custom.books[i];
        if (!b) return;
        // openBookObj 内部会 historyGet(b.key)，这里不重复调
        ui.openBookJson(JSON.stringify(b));
    };

    window.onLocalScan = function (o) {
        App.custom.scanning = false;
        if (!o) { paintCustom(); return; }
        if (o.ok) {
            App.custom.count = o.count || 0;
            App.custom.needPerm = !!o.needPermission;
            App.custom.error = o.needPermission ? '' : (o.error || '');
            if (typeof o.root === 'string') App.custom.root = o.root;
            // 扫完顺便拉列表（扫描已把结果写进 native 缓存）
            TS.call('localBooks');
            TS.toast(o.needPermission ? '需要存储权限' :
                ('扫描完成，找到 ' + (o.count || 0) + ' 本'));
        } else {
            App.custom.error = o.error || '扫描失败';
        }
        paintCustom();
    };

    window.onLocalBooks = function (o) {
        if (o && o.ok && o.books) {
            App.custom.books = o.books;
            App.custom.count = o.books.length;
        }
        if (o && typeof o.root === 'string') App.custom.root = o.root;
        // ★ v3.9.3：首页正停在「本地书源」视图 → 把扫到的书画进首页
        if (App.home && App.home.src === 'local' && App.page === TABS.home) {
            renderHomeLocalBooks((o && o.ok && o.books) || []);
            return;
        }
        paintCustom();
    };

    /**
     * ★ v3.9.1 自定义扫描目录的结果。
     * 两种来源：① SAF 目录选择器（带 path）；② localSetDir 的回包（带 root）。
     * 选择器在少数 ROM 上还原不出真实路径（path 为空）—— 这时退化成手动粘贴，
     * 而不是报一句"失败"就把用户晾在那儿。
     */
    window.onLocalDir = function (o) {
        if (!o) return;
        if (!o.ok) {
            if (!o.cancel) TS.toast('打不开目录选择器，可改用手动输入路径');
            return;
        }
        // ① 来自 SAF 选择器：带 path 字段（可能为空）
        if ('path' in o) {
            if (o.path) {
                App.custom.root = o.path;
                TS.call('localSetDir', o.path);
            } else {
                // 少数 ROM 的 documentId 是私有 id，还原不出真实路径 ——
                // 退化成手动输入，而不是干巴巴报一句"失败"
                ui.inputSheet('扫描目录（绝对路径）', '/storage/emulated/0/', function (v) {
                    var p = (v || '').trim();
                    if (p) TS.call('localSetDir', p);
                });
                TS.toast('取不到该文件夹路径，请手动输入');
            }
            return;
        }
        // ② 来自 localSetDir 的回包：带 root / count
        if (typeof o.root === 'string') {
            App.custom.root = o.root;
            if (o.needPermission) {
                App.custom.needPerm = true;
            } else if (o.count === 0) {
                TS.toast(o.root ? '已设置，但该目录下没有找到音频文件'
                                : '已恢复扫描全部目录');
            } else {
                TS.toast(o.root ? ('已设为扫描该目录，找到 ' + o.count + ' 本')
                                : '已恢复扫描全部目录');
            }
            paintCustom();
        }
    };

    /** 权限申请结果（MainActivity 回调推来） */
    window.onLocalPerm = function (o) {
        if (o && o.ok) {
            TS.toast('已获得权限，正在重新扫描');
            ui.customLocalScan();
        } else {
            App.custom.needPerm = true;
            paintCustom();
            TS.toast('未获得存储权限，无法扫描本地音频');
        }
    };

    // ---------- 网盘接口交互 ----------

    ui.customTogglePan = function (pid, on) {
        TS.call('setSourceEnabled', pid, !!on);
        TS.toast(on ? (PAN_NAMES[pid] || pid) + ' 已启用' :
                       (PAN_NAMES[pid] || pid) + ' 已停用');
    };

    /** 配置 Cookie 弹窗（多行文本 + 平台指引） */
    ui.customPanCookie = function (pid) {
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">' + TS.esc(PAN_NAMES[pid] || pid) + ' · 配置 Cookie</div>';
        h += '<div class="cu-tip">' + TS.esc(panHelp(pid)).replace(/\n/g, '<br>') + '</div>';
        h += '<textarea id="panCk" class="cu-ta" rows="4" placeholder="整条 Cookie 粘贴到这里">' +
             TS.esc(panCookieGuess(pid)) + '</textarea>';
        h += '<div class="sheet-item" onclick="App.ui.customPanSave(\'' + pid + '\')">保存</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    /** 取 Cookie 指引文案（与 PanResolver.helpOf 对齐） */
    function panHelp(pid) {
        if (pid === 'pan_ali') {
            return '公开分享一般无需 Cookie；仅在自己网盘/加密分享时需要。\n' +
                   '浏览器登录 aliyundrive.com 后 F12 → 网络 → 任一条请求的 ' +
                   'Request Headers 里整条 Cookie 复制过来。';
        }
        var host = {
            pan_baidu: 'https://pan.baidu.com', pan_quark: 'https://pan.quark.cn',
            pan_tianyi: 'https://cloud.189.cn', pan_115: 'https://115.com',
            pan_uc: 'https://drive.uc.cn'
        }[pid] || '';
        return '电脑浏览器登录 ' + host + ' → 按 F12 → 网络 → 刷新页面 → ' +
               '任一条请求 → Request Headers → Cookie，整条复制粘贴到下面。';
    }

    /**
     * Cookie 回填。App.sources 里没有 cookie 原文（native 只给 panReady 布尔），
     * 这里只能留空让用户重贴 —— 安全上也应该如此（不明文泄露）。
     */
    function panCookieGuess(pid) { return ''; }

    ui.customPanSave = function (pid) {
        var el = $('panCk');
        var ck = el ? (el.value || '').trim() : '';
        if (!ck) { TS.toast('Cookie 不能为空'); return; }
        ui.closeSheet();
        TS.call('panSave', pid, ck);
    };

    ui.customPanClear = function (pid) {
        TS.call('panClear', pid);
    };

    // ---------- ★ v4.2.0 网盘登录（扫码 / 密码） ----------

    /**
     * 115 扫码登录：弹窗显示二维码，native 后台轮询，确认后自动存 Cookie。
     * 关闭弹窗时通过 closeSheet 统一停掉轮询（App.qrLogin 标记）。
     */
    /**
     * ★ v4.4.0：网盘「网页登录」——弹内嵌 WebView 打开官方登录页，
     * 登录成功后原生自动抓 Cookie 并回调 onPanSaved（toast + 刷新状态）。
     */
    ui.customPanWebLogin = function (pid) {
        TS.call('panWebLogin', pid);
    };

    /**
     * ★ v4.5.0：扫描已登录网盘内的有声书（DFS 找含音频的文件夹）。
     */
    ui.customPanScan = function (pid) {
        var st = panState(pid);
        st.scanScanning = true;
        st.scanBooks = null;
        paintCustom();
        TS.toast('正在扫描' + (PAN_NAMES[pid] || '') + '目录…');
        TS.call('panScan', pid);
    };

    /** 播放盘内扫描出的某本书（整本入队） */
    ui.customPanDiskPlay = function (pid, bi) {
        var st = panState(pid);
        var bk = st.scanBooks && st.scanBooks[bi];
        if (!bk || !bk.files || !bk.files.length) { TS.toast('这本书没有音频文件'); return; }
        TS.call('panPlay', pid, '__disk__|' + bk.name, JSON.stringify(bk.files));
    };

    ui.customPanQr = function (pid) {
        App.qrLogin = { pid: pid };
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">' + TS.esc(PAN_NAMES[pid] || pid) + ' · 扫码登录</div>';
        h += '<div class="cu-tip">用 115 官方 App「扫一扫」扫描二维码登录。' +
             '此方式会把网页端已登录设备踢下线，属正常现象。</div>';
        h += '<div id="panQrBox" class="pan-qr-box" style="display:flex;justify-content:center;' +
             'padding:12px 0;">二维码生成中…</div>';
        h += '<div id="panQrTip" class="cu-desc dim" style="text-align:center">请稍候…</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
        TS.call('panQrStart', pid);
    };

    /** 更新扫码弹窗里的状态行 */
    function setQrTip(t) {
        var el = $('panQrTip');
        if (el) el.textContent = t || '';
    }

    window.onPanQr = function (o) {
        if (!o) return;
        if (o.ok && o.qr) {
            var box = $('panQrBox');
            if (box) {
                box.innerHTML = '';
                try {
                    new QRCode(box, { text: o.qr, width: 210, height: 210,
                        correctLevel: QRCode.CorrectLevel.M });
                    setQrTip('请用 115 App 扫一扫');
                } catch (e) {
                    box.textContent = '二维码渲染失败，请改用配置 Cookie';
                }
            }
            return;
        }
        if (o.done) {
            App.qrLogin = null;
            ui.closeSheet();
            TS.toast((PAN_NAMES[o.platform] || o.platform) + ' 扫码登录成功');
            paintCustom();
            return;
        }
        if (o.expired) {
            App.qrLogin = null;          // 让 closeSheet 不再重复 panQrStop
            TS.call('panQrStop');
            setQrTip(o.msg || '二维码已过期，请重新打开扫码登录');
            return;
        }
        if (o.state === 1) { setQrTip(o.msg || '已扫码，请在手机上确认'); return; }
        if (!o.ok) {
            setQrTip(o.error || '扫码登录失败，请改用配置 Cookie');
        }
    };

    /** 天翼云盘账号密码登录 */
    ui.customPanPwd = function (pid) {
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">' + TS.esc(PAN_NAMES[pid] || pid) + ' · 密码登录</div>';
        h += '<div class="cu-tip">输入天翼云盘账号（手机号）与密码，登录成功后' +
             '自动保存 Cookie。若触发登录验证码，请改用「配置 Cookie」。</div>';
        h += '<input id="panPwdU" class="cu-input" type="text" ' +
             'placeholder="手机号 / 189.cn 账号" style="width:100%;margin-bottom:8px">';
        h += '<input id="panPwdP" class="cu-input" type="password" ' +
             'placeholder="密码" style="width:100%">';
        h += '<div class="sheet-item" onclick="App.ui.customPanPwdGo(\'' + pid + '\')">登录</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);
    };

    ui.customPanPwdGo = function (pid) {
        var ue = $('panPwdU'), pe = $('panPwdP');
        var u = ue ? (ue.value || '').trim() : '';
        var p = pe ? (pe.value || '') : '';
        if (!u || !p) { TS.toast('账号和密码都要填'); return; }
        ui.closeSheet();
        TS.toast('正在登录' + (PAN_NAMES[pid] || pid) + '…');
        TS.call('panPwdLogin', pid, u, p);
    };

    window.onPanPwdLogin = function (o) {
        if (o && o.ok) {
            TS.toast((PAN_NAMES[o.platform] || o.platform) + ' 登录成功，Cookie 已保存');
            paintCustom();
        } else {
            TS.toast('登录失败：' + ((o && o.error) || '未知错误'));
        }
    };

    ui.customPanParse = function (pid) {
        var el = $('panShare_' + pid);
        var url = el ? (el.value || '').trim() : '';
        if (!url) { TS.toast('先粘贴分享链接'); return; }
        var st = panState(pid);
        st.shareUrl = url;
        st.files = [];
        st.picked = {};
        st.error = '';
        st.parsing = true;
        paintCustom();
        TS.call('panParse', pid, url);
        // ★ v4.6.0：解析超时兜底——原生 45 秒还没回包（网络卡死/接口挂了）
        //   就解除「解析中」状态并提示，不再让按钮永远灰着像没反应。
        clearTimeout(st._pt);
        st._pt = setTimeout(function () {
            if (!st.parsing) return;
            st.parsing = false;
            paintCustom();
            TS.toast('解析超时，请检查网络后重试');
        }, 45000);
    };

    ui.customPanPick = function (pid, fi, on) {
        var st = panState(pid);
        st.picked[fi] = !!on;
        paintCustom();
    };

    ui.customPanAll = function (pid, on) {
        var st = panState(pid);
        for (var i = 0; i < st.files.length; i++) {
            if (st.files[i].dir) continue;
            st.picked[i] = !!on;
        }
        paintCustom();
    };

    /** 播放选中文件 → panPlay（native 侧组装队列并直接起播） */
    ui.customPanPlay = function (pid) {
        var st = panState(pid);
        var picked = [];
        for (var i = 0; i < st.files.length; i++) {
            var f = st.files[i];
            if (f.dir || !st.picked[i]) continue;
            picked.push({ id: f.id, name: f.name, size: f.size, dir: false, token: f.token || '' });
        }
        if (!picked.length) { TS.toast('先勾选要播放的文件'); return; }
        if (!st.shareUrl) { TS.toast('分享链接为空，请重新解析'); return; }
        TS.call('panPlay', pid, st.shareUrl, JSON.stringify(picked));
    };

    window.onPanSaved = function (o) {
        if (!o) return;
        if (o.ok) {
            TS.toast(o.ready ? '已保存，接口可用' : '已保存');
        } else {
            TS.toast('保存失败' + (o.error ? '：' + o.error : ''));
        }
        // panReady 变了 → 让 native 重推源列表，开关状态才准
        TS.call('listSources');
        paintCustom();
    };

    window.onPanScan = function (o) {
        var pid = o && o.platform;
        if (!pid) return;
        var st = panState(pid);
        st.scanScanning = false;
        if (o.ok) {
            st.scanBooks = o.books || [];
            st.error = '';
            TS.toast(st.scanBooks.length
                ? '扫描到 ' + st.scanBooks.length + ' 本有声书'
                : '没扫到有声书（网盘里没有音频文件夹）');
        } else {
            st.scanBooks = null;
            TS.toast('扫描失败：' + (o.error || '未知错误'));
        }
        paintCustom();
        // ★ v4.6.0：首页正停在这个网盘的视图上 → 同步刷新书架
        if (App.tab === 'home' && App.home.src === o.platform) renderHomePan();
    };

    window.onPanFiles = function (o) {
        var pid = o && o.platform;
        if (!pid) return;
        var st = panState(pid);
        st.parsing = false;
        if (o.ok) {
            st.files = o.files || [];
            st.picked = {};
            st.error = '';
            // 默认全选文件（文件夹项 PanResolver 已展平，通常剩下的都是音频）
            var nAudio = 0;
            for (var i = 0; i < st.files.length; i++) {
                if (!st.files[i].dir) { st.picked[st.files[i].id] = true; nAudio++; }
            }
            // ★ v4.7.0：解析成功也要有反馈（成功但空 = 用户眼里「没反应」）
            if (!st.files.length) {
                TS.toast('分享里没有文件（链接可能已失效）');
            } else if (nAudio === 0) {
                TS.toast('解析到 ' + st.files.length + ' 个文件夹，但没有音频文件');
            } else {
                TS.toast('解析到 ' + nAudio + ' 个音频文件，已全选，可直接播放');
            }
        } else {
            st.files = [];
            st.picked = {};
            st.error = o.error || '解析失败';
            // ★ v4.6.0：解析失败必须弹提示——原来错误只写在卡片底部，
            //   用户看不到，感觉就是「解析没反应」
            TS.toast('解析失败：' + st.error);
        }
        paintCustom();
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
            paintSrcGroupNav(false);            // ★ v3.7.6：空组顶栏不留按钮
            c.innerHTML = errBlock('这个分组下还没有书源');
            return;
        }

        // ★ v3.7.14：长按不再"顺便进入多选"（用户反馈长按和选择有冲突）。
        //   现在长按只干一件事：在按点弹菜单（启用/停用、测试、改名、删除）。
        // ★ v3.9.1：但「批量管理」这个需求是真实存在的（一次删十几个源、
        //   整批停用），所以改成**显式进入**：点顶栏「批量」才进多选态，
        //   勾选只通过行首的复选框，与长按手势彻底分开，不再打架。
        var canBatch = def.k !== 'builtin';
        if (!canBatch && App.srcBatch) App.srcBatch = false;   // 内置组不支持

        // 顶栏「全部开启 / 全部关闭 / 批量管理」图标（内置源组不给）
        paintSrcGroupNav(canBatch);

        var h = '';
        h += '<div class="grphd">' + TS.esc(def.sub) +
             '<span class="srcbulk">' +
               '<span class="srccount">' + countEnabled(list) + ' / ' + list.length + ' 启用</span>' +
             '</span>' +
             '</div>';
        h += '<div class="srcops" style="padding:0 0 8px">' +
             '<span class="srchint">' +
             (App.srcBatch ? '勾选要操作的书源，然后在下方工具条选择操作'
                           : '长按任意一行打开菜单；点顶栏「批量」可多选') +
             '</span></div>';

        for (var i = 0; i < list.length; i++) {
            var s = list[i];
            var nm = s.alias || s.name;
            var cls = 'srcrow srcrow-tap';

            // ★ v3.7.13：所有点击/长按统一走 bindSrcPress 委托，
            //   不再在 HTML 上写一堆 onmousedown/ontouchstart（那种写法
            //   每行 4 个内联事件，既臃肿又会和委托打架）。
            h += '<div class="' + cls + (App.srcBatch && App.srcSel[s.id] ? ' srcrow-sel' : '') +
                 '" data-sid="' + escAttr(s.id) + '"' +
                 ' data-nm="' + escAttr(nm) + '">';

            // ★ v3.9.1：批量态 —— 行首复选框（点它就勾/消，不参与长按手势）
            if (App.srcBatch) {
                h += '<label class="srcchk" onclick="event.stopPropagation()">' +
                     '<input type="checkbox" ' + (App.srcSel[s.id] ? 'checked' : '') +
                     ' onchange="App.ui.srcSelToggle(\'' + escAttr(s.id) +
                     '\', this.checked)"><i></i></label>';
            }

            h += '<div class="srcinfo">';
            h += '<div class="srcname">' + TS.esc(nm) + '</div>';
            h += '<div class="srcdesc">' +
                 '<span class="sc' + (s.enabled ? ' on' : ' off') + '">' +
                 (s.enabled ? '已启用' : '已停用') + '</span>' +
                 (!s.canSearch ? ' · <span class="tag">仅分类</span>' : '') +
                 // ★ v3.0：JS 源里有一批站点反爬，得先去验证页过一次验证码
                 (s.needsVerification ? ' · <span class="tag tag-warn">需验证</span>' : '') +
                 (s.alias ? (' · 原名 ' + TS.esc(s.name)) : '') +
                 // ★ v3.7.13：测试连通的结果直接落在这一行，不再只弹 toast
                 '<span class="sctest" id="st_' + escAttr(s.id) + '"></span>' +
                 '</div>';
            h += '</div>';

            // 行内开关（批量态隐藏：这时行首是复选框，两个圈套在一起容易误点）
            if (!App.srcBatch) {
                h += '<label class="sw" onclick="event.stopPropagation()">' +
                     '<input type="checkbox" ' + (s.enabled ? 'checked' : '') +
                     ' data-toggle="' + escAttr(s.id) + '">' +
                     '<i></i></label>';
            }
            h += '</div>';
        }

        if (def.k === 'builtin') {
            h += '<div class="tip">内置源只能停用，不能删除。</div>';
        } else if (def.k === 'js') {
            h += '<div class="tip">JS 插件源由脚本提供，随插件包整包更新；' +
                 '标「需验证」的源首次搜索可能需要先在站点上过验证码。' +
                 '长按任意一行打开菜单：启用/停用、测试连通、改名、删除。</div>';
        } else if (def.k === 'py') {
            h += '<div class="tip">Python 源由 .py 脚本提供，可以单独删除。' +
                 '长按任意一行打开菜单：启用/停用、测试连通、改名、删除。</div>';
            // ★ v3.9.1：Python 源排障入口 —— 解释器没起来 / 缺三方模块时，
            //   光看"源搜不出东西"完全无法定位，这里给两个按钮：
            //   「重新加载」催一次补登记，「运行自检」看 Python 侧到底缺什么。
            h += '<div class="srcops" style="padding:4px 14px 14px">' +
                 '<button class="mini" onclick="App.ui.pyReload()">重新加载 Python 源</button>' +
                 '<button class="mini ghost" onclick="App.ui.pySelfTest()">运行自检</button>' +
                 '</div>';
        } else {
            h += '<div class="tip">长按任意一行打开菜单：启用/停用、测试连通、改名、删除。</div>';
        }

        // ★ v3.9.1：批量态的底部工具条（贴在内容末尾，不挡列表）
        if (App.srcBatch) {
            var n = 0, sid;
            for (sid in App.srcSel) if (App.srcSel[sid]) n++;
            h += '<div class="srcbatchbar" id="srcBatchBar">';
            h += '<span class="sbb-cnt">已选 <b>' + n + '</b> / ' + list.length + '</span>';
            h += '<div class="sbb-ops">';
            h += '<button class="mini ghost" onclick="App.ui.srcSelAll(true)">全选</button>';
            h += '<button class="mini ghost" onclick="App.ui.srcSelInverse()">反选</button>';
            h += '<button class="mini" onclick="App.ui.srcSelEnable(true)">启用</button>';
            h += '<button class="mini" onclick="App.ui.srcSelEnable(false)">停用</button>';
            // ★ v3.9.4：内置源组（builtin）与自定义源组（custom：本地书源 +
            //   网盘接口）都不给删除 —— 这两类源在 native 里注册为 builtin，
            //   removeSourceById 一律返回 false，点了只会得到"删除 0 个"的空结果。
            //   自定义源组只需启用/停用。
            if (def.k !== 'builtin' && def.k !== 'custom') {
                h += '<button class="mini danger" onclick="App.ui.srcSelDelete()">删除</button>';
            }
            h += '<button class="mini ghost" onclick="App.ui.srcBatchToggle()">完成</button>';
            h += '</div></div>';
        }

        c.innerHTML = h;
        bindSrcPress(c);
    }

    // ==================== ★ v3.9.1 源分组批量管理 ====================

    /**
     * 进入 / 退出批量态。
     *
     * <p>刻意**不用长按进入**（v3.7.14 的教训：长按和选择会打架），
     * 而是由顶栏「批量」按钮显式进入；退出时清空勾选，不留隐形状态。
     */
    ui.srcBatchToggle = function () {
        App.srcBatch = !App.srcBatch;
        App.srcSel = {};
        paintSrcGroup();
        if (App.srcBatch) TS.toast('已进入批量管理，勾选后选择操作');
    };

    /** 勾选 / 取消勾选某个源 */
    ui.srcSelToggle = function (id, on) {
        if (!App.srcSel) App.srcSel = {};
        if (on) App.srcSel[id] = true; else delete App.srcSel[id];
        // 只更新计数，不整页重绘（重绘会让复选框失焦、列表跳回顶部）
        var bar = $('srcBatchBar');
        if (bar) {
            var n = 0, sid;
            for (sid in App.srcSel) if (App.srcSel[sid]) n++;
            var box = bar.querySelector('.sbb-cnt');
            if (box) box.innerHTML = '已选 <b>' + n + '</b>';
            var row = document.querySelector('.srcrow[data-sid="' +
                      String(id).replace(/"/g, '\\"') + '"]');
            if (row) row.classList[on ? 'add' : 'remove']('srcrow-sel');
        }
    };

    /** 全选 / 全不选 */
    ui.srcSelAll = function (on) {
        var list = (bucketsOf()[App.srcGroupKey]) || [];
        App.srcSel = {};
        if (on) {
            for (var i = 0; i < list.length; i++) App.srcSel[list[i].id] = true;
        }
        paintSrcGroup();
    };

    /** 反选 */
    ui.srcSelInverse = function () {
        var list = (bucketsOf()[App.srcGroupKey]) || [];
        var next = {};
        for (var i = 0; i < list.length; i++) {
            if (!App.srcSel[list[i].id]) next[list[i].id] = true;
        }
        App.srcSel = next;
        paintSrcGroup();
    };

    /** 选中的源统一启用/停用（复用 onSourceChanged 里的批量计数，只弹一次提示） */
    ui.srcSelEnable = function (on) {
        var todo = [];
        var sid;
        for (sid in App.srcSel) if (App.srcSel[sid]) todo.push(sid);
        if (!todo.length) { TS.toast('还没有勾选任何书源'); return; }
        App._bulkSw = { left: todo.length, total: todo.length, on: !!on };
        for (var i = 0; i < todo.length; i++) TS.call('setSourceEnabled', todo[i], !!on);
        // 本地先乐观同步，UI 立刻反应（回调回来会再校一次）
        var list = (bucketsOf()[App.srcGroupKey]) || [];
        for (var k = 0; k < list.length; k++) {
            if (App.srcSel[list[k].id]) list[k].enabled = !!on;
        }
        paintSrcGroup();
        TS.toast((on ? '正在启用 ' : '正在停用 ') + todo.length + ' 个源…');
    };

    /** ★ v3.9.1：重新登记 .py 源（解释器起来得比源表登记晚时手工催一次） */
    ui.pyReload = function () {
        TS.toast('正在重新加载 Python 源…');
        TS.call('pyReload');
    };

    /** ★ v3.9.1：Python 运行自检，把解释器/模块缺什么讲清楚 */
    ui.pySelfTest = function () {
        TS.toast('正在自检 Python 运行时…');
        TS.call('pySelfTest');
    };

    window.onPySelfTest = function (o) {
        if (!o) { TS.toast('自检无返回'); return; }
        var d = o.detail || {};
        var lines = [];
        lines.push('解释器状态：' + (o.available ? '已就绪' : (o.starting ? '启动中' : '不可用')));
        if (o.error) lines.push('错误：' + o.error);
        if (d.python) lines.push('Python：' + d.python);
        var mods = d.modules || {};
        var bad = [];
        var mk;
        for (mk in mods) if (mods[mk] !== 'ok') bad.push(mk + ' → ' + mods[mk]);
        if (bad.length) {
            lines.push('缺失模块：');
            for (var i = 0; i < bad.length; i++) lines.push('· ' + bad[i]);
        } else if (d.python) {
            lines.push('关键模块：全部可用');
        }
        if (d.base_spider) lines.push('base.spider：' + d.base_spider);
        // 内容较长，用一个可滚动的确认式弹层展示（不是 toast，免得看不全）
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">Python 运行自检</div>';
        h += '<div class="cf-msg" style="max-height:52vh;overflow:auto;text-align:left">' +
             TS.esc(lines.join('\n')).replace(/\n/g, '<br>') + '</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">关闭</div>';
        h += '</div></div>';
        sheet(h);
    };


    /** 批量删除（内置源组走不到这里） */
    ui.srcSelDelete = function () {
        var todo = [];
        var sid;
        for (sid in App.srcSel) if (App.srcSel[sid]) todo.push(sid);
        if (!todo.length) { TS.toast('还没有勾选任何书源'); return; }
        var g = groupDef(App.srcGroupKey);
        ui.confirmSheet('确定删除选中的 ' + todo.length + ' 个' + g.t +
                        '？删除后需要从原文件/链接重新导入。', function () {
            App._bulkDel = { left: todo.length, total: todo.length, done: 0 };
            for (var i = 0; i < todo.length; i++) TS.call('removeSourceById', todo[i]);
            App.srcSel = {};
            TS.toast('正在删除 ' + todo.length + ' 个源…');
        });
    };

    /**
     * ★ v3.7.13：源分组的**事件委托**。
     *
     * <p>替换掉之前一行 4 个内联 `onmousedown/ontouchstart/...` 的写法。
     * 两种手势：
     * <ul>
     *   <li><b>长按 480ms</b> → 在按点弹统一菜单（删除 / 测试连通 / 改名），
     *       同时把这一行选上并进入多选态 —— 与历史/收藏长按手感一致。</li>
     *   <li><b>短按抬起</b> → 多选态里切换勾选，普通态里打开详情菜单。</li>
     * </ul>
     * 开关（.sw 里的 checkbox）单独走 change，不参与长按。
     */
    function bindSrcPress(c) {
        if (!c || c._srcBound) return;
        c._srcBound = true;
        var LONG = 480;
        var timer = null, fired = false, startPt = null, pressed = null, moved = false;

        var rowOf = function (t) {
            while (t && t !== c) {
                if (t.classList && t.classList.contains('srcrow')) return t;
                t = t.parentNode;
            }
            return null;
        };
        var ptOf = function (e) {
            if (e.touches && e.touches[0]) {
                return { x: e.touches[0].clientX, y: e.touches[0].clientY };
            }
            return { x: e.clientX, y: e.clientY };
        };
        var clear = function () {
            if (timer) { clearTimeout(timer); timer = null; }
        };

        var start = function (e) {
            var row = rowOf(e.target);
            if (!row) return;
            // 开关上的按压不算长按（开关自己走 change）
            if (e.target && e.target.closest && e.target.closest('.sw')) return;
            pressed = row;
            moved = false;
            fired = false;
            startPt = ptOf(e);
            clear();
            timer = setTimeout(function () {
                timer = null;
                var id = row.getAttribute('data-sid');
                if (!id) return;
                fired = true;
                try { if (navigator.vibrate) navigator.vibrate(12); } catch (ignored) {}
                // ★ v3.7.14：长按只弹菜单，不再顺带进多选
                srcRowMenu(id, startPt.x, startPt.y);
            }, LONG);
        };
        var move = function (e) {
            if (!startPt) return;
            var p = ptOf(e);
            if (Math.abs(p.x - startPt.x) > 10 || Math.abs(p.y - startPt.y) > 10) {
                moved = true;
                clear();
            }
        };
        var end = function (e) {
            clear();
            if (fired) { fired = false; pressed = null; return; }
            if (!pressed || moved) { pressed = null; return; }
            var row = pressed;
            pressed = null;
            // 开关上的操作不当作行点击
            if (e && e.target && e.target.closest && e.target.closest('.sw')) return;
            var id = row.getAttribute('data-sid');
            if (id) ui.srcRowClick(id);
        };

        c.addEventListener('touchstart', start, { passive: true });
        c.addEventListener('touchmove', move, { passive: true });
        c.addEventListener('touchend', end, { passive: true });
        c.addEventListener('touchcancel', function () { clear(); pressed = null; });
        c.addEventListener('mousedown', function (e) { if (e.button === 0) start(e); });
        c.addEventListener('mousemove', move);
        c.addEventListener('mouseup', end);
        c.addEventListener('mouseleave', function () { clear(); pressed = null; });
        // 桌面右键 = 直接弹菜单
        c.addEventListener('contextmenu', function (e) {
            var row = rowOf(e.target);
            if (!row) return;
            e.preventDefault();
            srcRowMenu(row.getAttribute('data-sid'), e.clientX, e.clientY);
        });
        // 开关：委托 change（行会重绘，绑在容器上不受影响）
        c.addEventListener('change', function (e) {
            var t = e.target;
            if (!t || !t.getAttribute) return;
            var id = t.getAttribute('data-toggle');
            if (id) ui.toggleSource(id, !!t.checked);
        });
    }

    /**
     * ★ v3.7.13：源行长按菜单（统一 .ctx-menu 样式）。
     *
     * <p>把原来「点一行 → 底部升起 sheet」改成「长按一行 → 在按点弹菜单」，
     * 与历史/收藏页一致。测试连通、删除、改名、卸载插件都收进这里。
     */
    function srcRowMenu(id, x, y) {
        var s = findSource(id);
        if (!s) return;
        var plugPath = pluginPathOf(id);
        var items = [];

        items.push({
            label: s.enabled ? '停用这个源' : '启用这个源', icon: '⏻',
            pick: function () { ui.menuToggle(id, s.enabled); }
        });
        items.push({
            label: '测试连通性', icon: '📡',
            pick: function () { ui.testSource(id); }
        });
        items.push({
            label: '重命名', icon: '✏️',
            pick: function () { ui.renameSource(id); }
        });

        if (s.builtin) {
            items.push({ label: '内置源不可删除', icon: '🔒', disabled: true, pick: function () {
                TS.toast('内置源只能停用，不能删除');
            } });
        } else if (plugPath) {
            items.push({
                label: '卸载插件源', icon: '🧩', danger: true,
                pick: function () { ui.menuUninstall(id); }
            });
        } else {
            items.push({
                label: '删除此源', icon: '🗑', danger: true,
                pick: function () { ui.menuDelete(id); }
            });
        }
        ctxMenu(x, y, items);
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
            '</span>' +
            // ★ v3.9.1：批量管理（勾选后统一启用/停用/删除）
            '<span class="bulkbtn' + (App.srcBatch ? ' active-on' : '') + '" ' +
              'title="勾选多个书源后批量启用/停用/删除" ' +
              'onclick="App.ui.srcBatchToggle()">' +
              '<svg viewBox="0 0 24 24"><path d="M3 5h2v14H3V5zm4 0h14v2H7V5zm0 5h14v2H7v-2zm0 5h14v2H7v-2z"/></svg>' +
              '<i>' + (App.srcBatch ? '退出' : '批量') + '</i>' +
            '</span>';
    }

    // ==================== ★ v3.7.4 源批量管理 ====================

    /**
     * ★ v3.7.14：长按多选态曾整体移除（长按和选择有冲突）。
     *
     * <p>但 ★ v3.9.1 起「批量管理」作为**显式入口**回归（顶栏「批量」按钮），
     * 真正的实现见上面的 srcBatchToggle / srcSelAll / srcSelInverse /
     * srcSelEnable / srcSelDelete。
     *
     * <p>⚠️ 这里**绝不能再写** srcSelAll / srcSelInvert / srcSelDelete 的空实现 ——
     * 本块在文件里位于真实实现之后，赋值会把真函数覆盖成空壳，
     * 表现就是「批量管理里勾选后点删除毫无反应」（v3.9.3 的线上问题）。
     * 只保留确实没有新实现、仅为兼容旧调用点的空壳。
     */
    ui.enterSrcMulti = function () {};
    ui.exitSrcMulti = function () {};
    ui.toggleSrcSel = function () {};
    ui.doBatchDeleteSrc = function () {};

    /**
     * ★ v3.7.13：手势已统一由 {@link bindSrcPress} 委托处理。
     * 这里保留两个空壳，仅为兼容可能残留的外部调用（旧版内联事件）。
     */
    ui.srcPressStart = function () { return true; };
    ui.srcPressEnd = function () {};

    /**
     * ★ v3.7.13 / v3.7.14：短按一行的处理。
     *
     * <p>短按与长按**弹同一套菜单**，只是触发时机不同：
     * 长按在按点弹出（长按位置更符合直觉），短按在行的右上角弹出。
     * 多选态已经取消，这里不再做勾选切换。
     */
    ui.srcRowClick = function (id) {
        var row = document.querySelector('#srcGroupContent .srcrow[data-sid="' +
            String(id).replace(/"/g, '\\"') + '"]');
        if (row) {
            var r = row.getBoundingClientRect();
            srcRowMenu(id, Math.max(8, r.right - 190), Math.round(r.top + 8));
        } else {
            srcRowMenu(id, Math.round(window.innerWidth * 0.16),
                Math.round(window.innerHeight * 0.22));
        }
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

    /**
     * ★ v3.7.13：单个源的详情菜单。
     *
     * <p>v3.7.13 起，源列表的"点击"与"长按"都直接弹统一菜单（{@link srcRowMenu}），
     * 这里只保留兼容：外部若还调 `ui.sourceMenu(id)`，就往统一菜单里转发。
     */
    ui.sourceMenu = function (id) {
        var s = findSource(id);
        if (!s) { TS.toast('源不存在'); return; }
        srcRowMenu(id, Math.round(window.innerWidth * 0.16),
            Math.round(window.innerHeight * 0.22));
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
            // ★ v3.7.13：以前这里调的是 removeCustom —— 它只认 SourceStore 里的
            //   JSON 配置，对 Python 源 / 插件源完全无效，native 返回 ok=false，
            //   前端就报"删除失败"。其实源根本没被处理。现在统一走
            //   removeSourceById（v3.7.4 就加好了，但单源删除一直漏接）。
            TS.call('removeSourceById', req.id);
        }
    };

    /**
     * 删除自定义源后的回调。
     * 注意：native 的 removeCustom / removeSourceById 会在本回调之后另发一次
     * onSourceList（整表），所以这里只做本地移除 + 提示，避免多一次无效的 listSources 往返。
     */
    window.onCustomChanged = function (o) {
        // ★ v3.9.1：批量删除回来了（源分组「批量管理」→ 勾选 → 删除）。
        //   _bulkDel 是本版的新计数器；_batchDel 是 v3.7.14 前的旧名，
        //   两个都认，防止老缓存里还留着旧状态导致逐条弹提示。
        var batch = App._bulkDel || App._batchDel;
        if (batch && batch.left > 0) {
            batch.left--;
            if (o && o.ok) batch.done++;
            var arrB = App.sources || [];
            for (var j = arrB.length - 1; j >= 0; j--) {
                if (arrB[j] && arrB[j].id === (o && o.id)) arrB.splice(j, 1);
            }
            if (batch.left <= 0) {
                var done = batch.done, total = batch.total;
                // ★ v3.9.4：两个计数器都要清！原来只清 _batchDel，
                //   _bulkDel 残留（left=0）会让下一次单条删除走进本分支却不减计数，
                //   表现为"再删就不弹提示"。两边一起置空。
                App._batchDel = null;
                App._bulkDel = null;
                TS.toast(done === total
                    ? ('已删除 ' + done + ' 个源')
                    : ('已删除 ' + done + ' 个，' + (total - done) + ' 个失败'));
                reconcileHomeSources();          // ★ v4.1：首页缓存同步剔除
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
            reconcileHomeSources();              // ★ v4.1：首页缓存同步剔除
            paintSourceEntry();
            refreshSourceViews();
        } else if (o && o.id) {
            // ★ v3.7.13：失败时不再只丢一句"删除失败"。分两种情况：
            //   ① 源是内置源 / 插件源 → native 拒绝，属于"本来就不该删"；
            //   ② 真的没删掉 → 提示去"批量管理"里再试，并说明可能原因。
            var s = findSource(o.id);
            var nm = s ? (s.alias || s.name) : o.id;
            if (s && s.builtin) {
                TS.toast('「' + nm + '」是内置源，只能停用不能删除');
            } else if (pluginPathOf(o.id)) {
                TS.toast('「' + nm + '」由插件提供，请改用「卸载插件源」');
                if (s) ui.menuUninstall(o.id);
            } else {
                TS.toast('「' + nm + '」没删掉（可能文件被占用），请到「批量管理」再试一次');
            }
            // 兜底：整表重拉一次，避免本地与 native 状态不一致
            refreshSourceViews();
        }
    };

    /**
     * ★ v4.1：按权威源表（App.sources）校正 App.homeSources。
     * 首页源下拉/选择页渲染用的是 discoverSources 缓存的 homeSources，
     * 之前删除/停用源后只更新了 App.sources，homeSources 不动 ——
     * 表现为「首页删了，源管理还在；源管理删了，首页还在」的双向不同步。
     * 规则：权威表非空时，id 不在表里的源视为已删除（剔除）；
     * 在表里的跟随其 enabled 状态。
     */
    function reconcileHomeSources() {
        var hs = App.homeSources;
        if (!hs || !hs.length || !App.sources || !App.sources.length) return;
        var st = {};
        for (var i = 0; i < App.sources.length; i++) st[App.sources[i].id] = App.sources[i];
        var out = [];
        for (var h = 0; h < hs.length; h++) {
            var s = hs[h];
            if (!s || !s.id) continue;
            var m = st[s.id];
            if (!m) continue;                       // 已被删除 → 首页缓存同步剔除
            s.enabled = m.enabled;                  // 停用状态跟随权威表
            out.push(s);
        }
        App.homeSources = out;
    }

    /** 删除/卸载后，统一按当前页刷新视图 */
    function refreshSourceViews() {
        if (App.page === SUBS.srcGroup) {
            var b = bucketsOf();
            if (!(b[App.srcGroupKey] || []).length) { ui.back(); return; }
            paintSrcGroup();
        } else if (App.page === SUBS.sources) {
            paintSources();
        } else if (App.page === SUBS.custom) {
            // ★ v3.9.0：自定义源页的开关状态来自 App.sources，
            //   源列表刷新后要重绘（否则 Cookie 配好了徽章还是「未配置」）
            paintCustom();
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
        // ★ v3.9.51：同步进 App.homeSources —— 首页「源列表选择页」渲染用的
        //   是这份缓存，之前只同步 App.sources 导致「停用了列表里还在」
        var syncHome = function (sid, en) {
            var hs = App.homeSources || [];
            for (var h = 0; h < hs.length; h++) {
                if (hs[h] && hs[h].id === sid) hs[h].enabled = !!en;
            }
        };
        // ★ v3.7.5：批量开关期间不逐个提示，等全部回完统一报一次
        var bulk = App._bulkSw;
        if (bulk && bulk.left > 0) {
            bulk.left--;
            if (o && o.id) {
                var arrB = App.sources || [];
                for (var m = 0; m < arrB.length; m++) {
                    if (arrB[m].id === o.id) arrB[m].enabled = !!o.enabled;
                }
                syncHome(o.id, o.enabled);
            }
            if (bulk.left <= 0) {
                TS.toast(bulk.on ? '已全部开启' : '已全部关闭');
                App._bulkSw = null;
                paintSourceEntry();
                if (App.page === SUBS.sources) paintSources();
                if (App.page === SUBS.srcGroup) paintSrcGroup();
                if (App.page === TABS.home) paintHomeSources();
                // ★ v3.9.51：向原生要一份权威源清单，首页列表与实际状态严格一致
                setTimeout(function () { TS.call('discoverSources'); }, 300);
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
            syncHome(o.id, o.enabled);
            paintSourceEntry();
            if (App.page === SUBS.sources) paintSources();
            // ★ v3.7.7：分组页里单独开关某个源，也要把顶栏「全开/全关」的
            //   高亮刷新一下 —— 否则从"全开"关掉一个变成混合态时，顶栏还
            //   亮着"全开"，跟列表里的实际状态对不上。
            if (App.page === SUBS.srcGroup) paintSrcGroup();
        }
    };

    /**
     * ★ v3.7.13：测试单个源的连通性。
     *
     * <p>以前的写法里 `$('st_' + id)` 这个元素**根本不存在**（只有底部 sheet
     * 里才有，而 sheet 一关就没了），所以点「测试连通性」后回调写进一个 null，
     * 用户看不到任何反应 —— 这就是"点击单个源测试连通没有效果"的根因。
     *
     * <p>现在：
     * <ol>
     *   <li>行内 `<span class="sctest" id="st_xxx">` 立刻显示「测试中…」（列表里能看见）</li>
     *   <li>测试结束弹一个**结果弹窗**（自带「关闭」按钮），不管成功失败都能看到结论</li>
     *   <li>超时 12 秒兜底，避免 native 没回调时一直转</li>
     * </ol>
     */
    var _srcTestTimer = null;
    ui.testSource = function (id) {
        var el = $('st_' + id);
        if (el) { el.textContent = '· 测试中…'; el.className = 'sctest testing'; }
        App._srcTesting = id;

        // 超时兜底：JS/Python 源跑一次真实搜索可能要等限流，给足 30 秒
        if (_srcTestTimer) clearTimeout(_srcTestTimer);
        _srcTestTimer = setTimeout(function () {
            _srcTestTimer = null;
            if (App._srcTesting === id) {
                App._srcTesting = null;
                var e2 = $('st_' + id);
                if (e2) { e2.textContent = '· 超时'; e2.className = 'sctest bad'; }
                ui.srcTestSheet(id, false, '测试超时（30 秒没返回），站点可能被限流或不可达');
            }
        }, 30000);

        TS.call('testCustom', id);
    };

    /**
     * ★ v3.7.13：测试结果弹窗。用户要求"测试后弹窗提醒，点击关闭"。
     */
    ui.srcTestSheet = function (id, ok, msg) {
        var s = findSource(id);
        var nm = s ? (s.alias || s.name) : id;
        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">测试连通性</div>';
        h += '<div class="srctest-res ' + (ok ? 'ok' : 'bad') + '">' +
             '<span class="str-ico">' + (ok ? '✔' : '✘') + '</span>' +
             '<span class="str-nm">' + TS.esc(nm) + '</span></div>';
        h += '<div class="srcmeta">' + TS.esc(msg || (ok ? '连通正常' : '测试失败')) + '</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">关闭</div>';
        h += '</div></div>';
        sheet(h);
    };

    window.onSourceTested = function (o) {
        if (_srcTestTimer) { clearTimeout(_srcTestTimer); _srcTestTimer = null; }
        var id = o && o.id;
        App._srcTesting = null;
        var el = $('st_' + id);
        if (el) {
            el.textContent = (o && o.ok ? ' · ✔ 正常' : ' · ✘ ' + ((o && o.msg) || '失败'));
            el.className = 'sctest ' + (o && o.ok ? 'ok' : 'bad');
        }
        // ★ 弹窗提醒（用户明确要求），带「关闭」按钮
        ui.srcTestSheet(id, !!(o && o.ok), (o && o.msg) || '');
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
        // ★ v3.8.1：选集弹层用的是另一个容器 #chSheetWrap，两者都是全屏
        //   .sheet-mask（z-index 500）。如果它还在，关掉本弹窗后那层遮罩会
        //   继续盖在界面上，表现就是「弹窗关了但整个页面点不动」。
        //   弹新弹窗前连它一起收掉。
        try {
            var c = $('chSheetWrap');
            if (c && c.parentNode) c.parentNode.removeChild(c);
        } catch (e) {}
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

    // ==================== ★ v2.8 播放悬浮球 → ★ v3.9.37 改为软件内悬浮球 ====================
    //
    // 之前的「悬浮球」开关控制的是**系统级**悬浮窗（FloatBallService，要
    // 「显示在其他应用上层」权限）。用户反馈要的是**软件内**的悬浮球 ——
    // 也就是右下角那个封面球，现在这个开关直接控制它，不再需要任何系统权限。
    // 系统悬浮窗服务不再启用（原生侧同步停用）。
    App._ballOn = true;
    try { App._ballOn = localStorage.getItem('ballOn') !== '0'; } catch (e) {}

    /** 开关：软件内悬浮球显隐（本地记忆，不走原生） */
    ui.toggleFloatBall = function (on) {
        App._ballOn = !!on;
        try { localStorage.setItem('ballOn', App._ballOn ? '1' : '0'); } catch (e) {}
        syncBall();
        TS.toast(App._ballOn ? '悬浮球已开启' : '悬浮球已关闭');
    };

    /** 设置页开关的初始勾选状态（进入设置页时同步） */
    function paintFloatBallSetting() {
        var el = $('setFloatBall');
        if (el) el.checked = App._ballOn;
    }
    ui.paintFloatBallSetting = paintFloatBallSetting;

    /** 原生回推的悬浮球状态（保留兼容：只同步勾选框） */
    window.onFloatBallState = function (o) {
        paintFloatBallSetting();
    };

    /** 从授权页返回后的复查结果（系统悬浮窗已废弃，仅同步勾选框） */
    window.onFloatBallStateRefresh = function (canDraw) {
        paintFloatBallSetting();
    };

    /** 权限失效时的降级提示（系统悬浮窗已废弃，理论上不再触发） */
    window.onFloatBallDenied = function () {
        paintFloatBallSetting();
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

    /**
     * 把主题真正写到 DOM 上。
     *
     * <p>★ v3.8.1：以前只有原生的 Bridge.setTheme 会改
     * {@code document.documentElement.className}，JS 侧（onDeviceInfo /
     * ui.setTheme）只改变量、重画分段控件，从不碰 DOM。于是只要原生那次
     * evaluateJavascript 没生效（最常见的就是冷启动时页面还没加载完），
     * 界面就一直是白的 —— 也就是用户反馈的「深色模式存不住，大退回白色」。
     * 现在 JS 自己也写一次，两条路互为兜底。
     */
    function applyThemeDom(mode) {
        var dark;
        if (mode === 'dark') dark = true;
        else if (mode === 'light') dark = false;
        else {
            // system：问系统要。matchMedia 拿不到时退回浅色（而不是乱猜）。
            dark = !!(window.matchMedia
                && window.matchMedia('(prefers-color-scheme: dark)').matches);
        }
        document.documentElement.className = dark ? 'dark' : '';
    }

    ui.setTheme = function (mode) {
        App.theme = mode;
        TS.call('setTheme', mode);   // 让原生落盘（commit）并切换 WebView 底色
        applyThemeDom(mode);         // 本页立刻生效，不等原生回调
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

    function syncSettings() { TS.call('getSettings'); paintFloatBallSetting(); paintHomeColsSeg(); }

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
        // ★ v3.9.53：预读集数不在预设档位里（自定义值）→ 下拉显示「自定义」
        App.autoCacheCount = parseInt(o.autoCacheCount, 10) || 3;
        (function () {
            var sel = $('setAutoCacheCount');
            if (!sel) return;
            var v = String(o.autoCacheCount || 3), has = false;
            for (var i = 0; i < sel.options.length; i++) {
                if (sel.options[i].value === v) { has = true; break; }
            }
            sel.value = has ? v : 'custom';
        })();
        // ★ v3.9.53：快进步长同步进全局（播放页按钮用），自定义值不在选项里时显示「自定义」
        App.ff = parseInt(o.ffSeconds, 10) || 30;
        var ffSel = $('setFf');
        if (ffSel) {
            var hasOpt = false, oo = ffSel.options, oi;
            for (oi = 0; oi < oo.length; oi++) if (oo[oi].value === String(App.ff)) hasOpt = true;
            if (!hasOpt) ffSel.value = 'custom';
        }
        setVal('setCacheLimit', o.cacheLimitMb || 2048);
        setVal('setFf', o.ffSeconds || 30);
        App.playOnFull = (o.playOnFull !== false);
        // ★ v2.8：悬浮球开关的真实状态以原生为准（权限可能被系统收走）
        TS.call('floatBallState');
        // ★ v2.9：下载目录状态也从原生拉一次（设置页要显示当前目录）
        TS.call('downloadDirState');
        // ★ v3.7.18：顺带统计一次存储占用（进设置页就能看到，不用手动点刷新）
        ui.storageRefresh();
    };

    function setCheck(id, v) { var el = $(id); if (el) el.checked = !!v; }
    function setVal(id, v) { var el = $(id); if (el) el.value = v; }

    ui.setBool = function (key, v) { TS.call('setSetting', key, v ? 'true' : 'false'); };
    ui.setNumber = function (key, v) {
        var n = parseInt(v, 10);
        if (!isNaN(n)) TS.call('setSetting', key, String(n));
    };

    // ★ v3.9.53：预读集数选择 —— 预设档位直接生效；「自定义…」弹输入框（1~50）
    ui.autoCacheCountPick = function (v) {
        if (v === 'custom') {
            var cur = App.autoCacheCount || 3;
            ui.inputSheet('自定义预读集数（1~50）', String(cur), function (txt) {
                var n = parseInt((txt || '').trim(), 10);
                if (isNaN(n) || n < 1 || n > 50) {
                    TS.toast('请输入 1~50 之间的整数');
                    return;
                }
                TS.call('setSetting', 'auto_cache_count', String(n));
                App.autoCacheCount = n;
                var sel = $('setAutoCacheCount');
                if (sel) {
                    var has = false;
                    for (var i = 0; i < sel.options.length; i++) {
                        if (sel.options[i].value === String(n)) { has = true; break; }
                    }
                    sel.value = has ? String(n) : 'custom';
                }
                TS.toast('预读集数：' + n);
            });
            return;
        }
        var n = parseInt(v, 10);
        if (!isNaN(n)) {
            TS.call('setSetting', 'auto_cache_count', String(n));
            App.autoCacheCount = n;
        }
    };

    // ==================== 存储占用（★ v3.7.18） ====================
    //
    // 需求背景：用户报「App 全部占用 500 多 M，核对下是不是有异常」。
    // 排查发现三件事：
    //   1) 500M 里的**大头是音频缓存**（单集 10~15MB），
    //      而 enforceLimit 只在「整本缓存」结束时跑 —— 自动预读那条路
    //      从来不清理，缓存只涨不落（已在 native 侧修掉）；
    //   2) 其次是不可清理的固定开销：APK 18M + 解压出的 so ≈ 8M
    //      + Chaquopy Python 运行时 ≈ 7M（不用 Python 源也照装）；
    //   3) App 里**看不到**这些数，只能去系统设置里看一个总数。
    // 所以这里补「占用可视化 + 一键清理」，让用户自己能核对与处理。

    function fmtMB(b) {
        if (!b) return '0 MB';
        var mb = b / 1024 / 1024;
        if (mb >= 1024) return (mb / 1024).toFixed(2) + ' GB';
        return (mb >= 100 ? mb.toFixed(0) : mb.toFixed(1)) + ' MB';
    }

    ui.storageRefresh = function () {
        var el = $('stDesc');
        if (el) el.textContent = '正在统计…';
        TS.call('storageInfo');
    };

    /** native 回传的占用明细 */
    window.onStorageInfo = function (o) {
        o = o || {};
        if (!o.ok) {
            var d0 = $('stDesc');
            if (d0) d0.textContent = '统计失败';
            return;
        }
        var total = (o.files || 0) + (o.cache || 0) + (o.extCache || 0) + (o.apk || 0);
        var d = $('stDesc');
        if (d) d.textContent = '合计约 ' + fmtMB(total) + '（含安装包与运行时）';

        var row = $('stDetailRow');
        var box = $('stDetail');
        if (box) {
            box.textContent =
                '音频缓存：' + fmtMB(o.audioCache) + '（上限 ' + (o.limitMb || 0) + ' MB）\n'
              + '应用数据：' + fmtMB(o.files) + '\n'
              + '临时缓存：' + fmtMB((o.cache || 0) + (o.extCache || 0)) + '\n'
              + '　· 书源插件：' + fmtMB(o.jsPlugins) + '（不可清理）\n'
              + '　· Python 源：' + fmtMB(o.pySources) + '（不可清理）\n'
              + '　· Python 运行时：' + fmtMB(o.chaquopy) + '（不可清理）\n'
              + '安装包：' + fmtMB(o.apk);
        }
        if (row) row.style.display = '';
        App._storage = o;
    };

    ui.clearAllCache = function () {
        var o = App._storage || {};
        var audio = fmtMB(o.audioCache);
        ui.confirmSheet('清空全部缓存？\n'
            + '将清除：音频缓存（当前 ' + audio + '）、封面图、详情缓存。\n'
            + '不会动：书源、收藏、收听历史、播放进度。',
            function () {
                TS.toast('正在清理…');
                TS.call('clearAllCache');
            });
    };

    /** native 清理完成 */
    window.onCacheCleared = function (o) {
        o = o || {};
        if (!o.ok) { TS.toast('清理失败，请重试'); return; }
        TS.toast('已释放 ' + fmtMB(o.freed));
        ui.storageRefresh();      // 顺手刷新占用数字
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
        // ★ v3.8.1：拿到主题后自己写一次 DOM（不再只依赖原生那次 evaluateJavascript）
        if (o && o.theme) { App.theme = o.theme; applyThemeDom(o.theme); paintTheme(); }
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

    // ==================== ★ v3.7.15-fix 诊断页（源加载自检 / 崩溃日志） ====================

    /** 源加载诊断：进页面即拉一次 */
    ui.openDiag = function () {
        goSub('diag');
        var sum = $('diagSum');
        if (sum) sum.textContent = '正在检测…';
        var list = $('diagList');
        if (list) list.innerHTML = '';
        var log = $('diagLog');
        if (log) log.textContent = '（读取中…）';
        TS.call('sourceDiag');
    };

    ui.diagRefresh = function () {
        var sum = $('diagSum');
        if (sum) sum.textContent = '正在检测…';
        TS.call('sourceDiag');
    };

    ui.diagClear = function () {
        TS.call('sourceDiagClear');
    };

    /** 把诊断内容整块复制，方便贴到群里 / issue 里 */
    ui.diagCopy = function () {
        var txt = _diagText();
        if (!txt) { TS.toast('暂无内容'); return; }
        try { TS.call('copyText', txt); TS.toast('诊断信息已复制'); }
        catch (e) { TS.toast('复制失败'); }
    };

    var _diagCache = '';

    function _diagText() {
        return _diagCache;
    }

    function _esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    /** 原生回调：源加载诊断结果 */
    window.onSourceDiag = function (o) {
        o = o || {};
        var sum = $('diagSum');
        var list = $('diagList');
        var logEl = $('diagLog');

        var lines = [];
        var plugins = o.plugins || [];
        var total = o.totalSources || 0;
        var live = o.liveSourceCount || 0;

        lines.push('插件包：' + (o.pluginCount || 0) + ' 个');
        lines.push('JS 源：' + live + ' 个存活');
        lines.push('当前源总数：' + total);

        if (sum) {
            sum.textContent = 'JS 插件 ' + (o.pluginCount || 0) + ' 个 · JS 源 '
                + live + ' 个 · 源总数 ' + total;
        }

        // 没有 JS 插件包时的明确指引（这是「源不见了」最常见的一种）
        if (!plugins.length) {
            lines.push('');
            lines.push('【结论】本机没有已安装的 JS 插件包。');
            lines.push('如果之前装过，多半是在重装/更新时被清掉了'
                + '（换签名后必须卸载旧版，卸载会一并清空本机数据）。');
            lines.push('解决办法：在「书源管理」里重新导入一次 JS 插件包即可。');
        } else {
            lines.push('');
            for (var i = 0; i < plugins.length; i++) {
                var p = plugins[i];
                lines.push('— ' + (p.fileName || '(无名)')
                    + '　[' + (p.enabled ? '已启用' : '已停用') + ']');
                lines.push('　目录存在：' + (p.dirExists ? '是' : '否')
                    + '　manifest：' + (p.manifestExists ? '有' : '缺失'));
                lines.push('　登记源：' + (p.declaredSources || 0)
                    + ' 个　实际存活：' + (p.liveSources || 0) + ' 个');
                if (p.loadOk) {
                    lines.push('　重试加载：成功（' + (p.loadCount || 0) + ' 个源）');
                } else {
                    lines.push('　重试加载：失败');
                    if (p.loadError) lines.push('　原因：' + p.loadError);
                }
            }
        }

        var runtime = o.runtimeLog || '';
        _diagCache = lines.join('\n')
            + (runtime ? '\n\n===== 运行期记录 =====\n' + runtime : '');

        if (list) {
            list.innerHTML = '<pre class="diag-log">' + _esc(lines.join('\n')) + '</pre>';
        }
        if (logEl) {
            logEl.textContent = runtime ? runtime : '（本次启动暂无可记录的异常）';
        }
    };

    /** 崩溃日志页 */
    ui.openCrashLog = function () {
        goSub('crash');
        var el = $('crashBody');
        if (el) el.textContent = '正在读取…';
        TS.call('crashLog');
    };

    ui.crashRefresh = function () { TS.call('crashLog'); };

    ui.crashClear = function () { TS.call('crashClear'); };

    ui.crashCopy = function () {
        var el = $('crashBody');
        var txt = el ? el.textContent : '';
        if (!txt || txt === '正在读取…') { TS.toast('暂无内容'); return; }
        try { TS.call('copyText', txt); TS.toast('崩溃日志已复制'); }
        catch (e) { TS.toast('复制失败'); }
    };

    /** 原生回调：崩溃日志 */
    window.onCrashLog = function (o) {
        o = o || {};
        var el = $('crashBody');
        if (!el) return;
        el.textContent = o.empty
            ? '（暂无崩溃记录 —— 本次运行没有抓到未捕获异常）'
            : (o.log || '');
    };

    /** ★ v3.8.4：小组件诊断日志页 */
    ui.openWgDiag = function () {
        goSub('wgdiag');
        var el = $('wgdiagBody');
        if (el) el.textContent = '正在读取…';
        TS.call('widgetDiagLog');
    };

    /** ★ v3.9.32：首页 1/2/3 列布局（导航栏「N列」按钮循环切换） */
    App.homeCols = parseInt(localStorage.getItem('homeCols') || '1', 10);
    if (App.homeCols < 1 || App.homeCols > 3) App.homeCols = 1;
    ui.cycleHomeCols = function () {
        App.homeCols = App.homeCols % 3 + 1;
        localStorage.setItem('homeCols', String(App.homeCols));
        applyHomeCols();
        TS.toast('首页：' + App.homeCols + ' 列布局');
    };
    function applyHomeCols() {
        var el = $('homeContent');
        if (el) {
            el.classList.remove('home-grid-2', 'home-grid-3');
            if (App.homeCols === 2) el.classList.add('home-grid-2');
            if (App.homeCols === 3) el.classList.add('home-grid-3');
        }
        paintHomeColsSeg();
    }

    /** ★ v4.6.0：列数选择挪到设置页（顶栏「1列」按钮已删） */
    function paintHomeColsSeg() {
        var ids = { 1: 'hc1', 2: 'hc2', 3: 'hc3' };
        for (var k in ids) {
            var el = $(ids[k]);
            if (el) el.className = 'seg-item' + (String(App.homeCols) === k ? ' on' : '');
        }
    }
    ui.paintHomeColsSeg = paintHomeColsSeg;

    ui.setHomeCols = function (n) {
        n = parseInt(n, 10);
        if (n < 1 || n > 3) n = 1;
        App.homeCols = n;
        localStorage.setItem('homeCols', String(n));
        applyHomeCols();
    };

    /** ★ v4.6.0：设置页 → 自定义源（本地书源 + 网盘接口） */
    ui.openCustomFromSettings = function () {
        App.tab = 'home';
        ui.openSrcGroup('custom');
    };
    applyHomeCols();

    ui.wgDiagRefresh = function () { TS.call('widgetDiagLog'); };

    ui.wgDiagClear = function () { TS.call('widgetDiagClear'); };

    ui.wgDiagCopy = function () {
        var el = $('wgdiagBody');
        var txt = el ? el.textContent : '';
        if (!txt || txt === '正在读取…') { TS.toast('暂无内容'); return; }
        try { TS.call('copyText', txt); TS.toast('诊断日志已复制'); }
        catch (e) { TS.toast('复制失败'); }
    };

    /** 原生回调：小组件诊断日志 */
    window.onWgDiagLog = function (o) {
        o = o || {};
        var el = $('wgdiagBody');
        if (!el) return;
        el.textContent = o.empty
            ? '（暂无小组件记录 —— 添加一次桌面小组件后再来看）'
            : (o.log || '');
    };

    // ==================== ★ v3.8.0 备份与恢复 ====================
    //
    // 页面结构：概览 → 本地备份 → 网络备份(WebDAV) → 自动备份 → 执行记录。
    // 原生侧接口见 Bridge.java 的「★ v3.8.0 备份与恢复」整段。
    //
    // 两条不变量（原生已保证，前端只在文案上如实说明）：
    //   1) 备份包里绝不含音频缓存 / 已下载音频 / Python 运行时；
    //   2) 任何恢复动作都会先自动打一个当前状态的备份包，失败即中止。
    //
    // ★ 本地备份目录（SAF tree uri）存在 localStorage 的 fx_bk_tree_v1 /
    //   fx_bk_name_v1 两个键里：它是纯界面状态，而且刚好随备份包一起走，
    //   换机恢复后不必重选目录。原生侧只负责拉起选择器。

    var LS_BK_TREE = 'fx_bk_tree_v1';
    var LS_BK_NAME = 'fx_bk_name_v1';

    function lsGet(k) { try { return localStorage.getItem(k) || ''; } catch (e) { return ''; } }
    function lsSet(k, v) {
        try { if (v) localStorage.setItem(k, v); else localStorage.removeItem(k); } catch (e) {}
    }

    /** 本地备份的当前状态 */
    App.bk = {
        treeUri: lsGet(LS_BK_TREE),
        dirName: lsGet(LS_BK_NAME),
        hasDav: false, davUrl: '', davDir: '',
        lastLocal: null, busy: false
    };

    /** 执行记录（只在本页面生命周期内累积，不落盘） */
    var _bkLog = [];
    var _bkLogFlushTok = null;

    function bkLog(line) {
        var t = new Date();
        var hh = t.getHours(), mm = t.getMinutes(), ss = t.getSeconds();
        function p2(n) { return n < 10 ? '0' + n : '' + n; }
        _bkLog.push('[' + p2(hh) + ':' + p2(mm) + ':' + p2(ss) + '] ' + line);
        // ★ v3.9.52：诊断码行很长，200 行全量重写 textNode 每条都触发一次
        //   大面积 reflow（备份页因此发卡）。改成：只留 60 条 + 250ms 节流
        //   合并刷新 —— 连续写日志时 DOM 只动一次。
        if (_bkLog.length > 60) _bkLog.shift();
        if (_bkLogFlushTok) return;
        _bkLogFlushTok = setTimeout(function () {
            _bkLogFlushTok = null;
            var el = $('bkLog');
            if (el) {
                el.textContent = _bkLog.join('\n');
                el.scrollTop = el.scrollHeight;
            }
        }, 250);
    }

    ui.bkClearLog = function () {
        if (_bkLogFlushTok) { clearTimeout(_bkLogFlushTok); _bkLogFlushTok = null; }
        _bkLog = [];
        var el = $('bkLog');
        if (el) el.textContent = '（暂无）';
    };

    /** 统一格式化体积 */
    function bkSize(n) {
        n = n || 0;
        if (n < 1024) return n + ' B';
        if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
        if (n < 1024 * 1024 * 1024) return (n / 1048576).toFixed(1) + ' MB';
        return (n / 1073741824).toFixed(2) + ' GB';
    }

    /** 打开备份页 */
    ui.openBackup = function () {
        goSub('backup');
        var sum = $('bkSummary');
        if (sum) sum.textContent = '正在统计本机数据…';
        App.bk.busy = false;
        // 先把本地目录状态画出来（来自 localStorage，不用等原生）
        _bkPaintLocal();
        TS.call('backupStatus');
        _bkRefreshAutoList();
    };

    /** 画本地备份目录一行 */
    function _bkPaintLocal() {
        var dv = $('bkDirVal');
        if (dv) dv.innerHTML = App.bk.dirName
            ? TS.esc(App.bk.dirName) + ' &#8250;'
            : '未设置 &#8250;';
    }

    /** 刷新「网盘备份列表」 */
    function _bkRefreshDavList() {
        var box = $('bkDavList');
        if (!box) return;
        if (!App.bk.hasDav) {
            box.innerHTML = '<div class="tip">还没有配置 WebDAV。填入坚果云 / '
                + 'Nextcloud / 群晖等地址后即可把备份包传到网盘。</div>';
            return;
        }
        box.innerHTML = '<div class="loading"><div class="spinner"></div>正在读取网盘…</div>';
        TS.call('davList');
    }

    /** 刷新「自动备份列表」 */
    function _bkRefreshAutoList() {
        TS.call('autoBackupList');
    }

    // ---------- 概览 ----------

    /** 原生：备份页概览状态 */
    window.onBackupStatus = function (o) {
        o = o || {};
        // ★ stats() 的键：fav / favGroup / history / progress / sourceConf /
        //   customSource / pluginJar / searchHist / jsPlugins / pySources /
        //   jars / sources / total
        var c = o.counts || {};
        App.bk.hasDav = !!o.hasDav;
        App.bk.davUrl = o.davUrl || '';
        App.bk.davDir = o.davDir || '';
        // ★ v3.9.43：同步「信任自签名证书」开关
        var ts = $('bkTrustSsl');
        if (ts) ts.checked = !!o.trustSsl;

        var srcCount = c.sources || 0;
        var pluginCount = (c.jsPlugins || 0) + (c.pySources || 0) + (c.jars || 0);
        var confCount = c.sourceConf || 0;

        var sum = $('bkSummary');
        if (sum) {
            sum.innerHTML = '当前数据：书源 <b>' + srcCount + '</b> 个 · 收藏 <b>'
                + (c.fav || 0) + '</b> 条 · 历史 <b>' + (c.history || 0)
                + '</b> 条 · 进度 <b>' + (c.progress || 0) + '</b> 条';
        }
        var s1 = $('bkScopeSrc');
        if (s1) s1.textContent = srcCount + ' 源 / ' + pluginCount + ' 插件 / '
            + confCount + ' 条源配置';
        var s2 = $('bkScopeFav');
        if (s2) s2.textContent = (c.fav || 0) + ' 收藏 · ' + (c.favGroup || 0)
            + ' 分组 · ' + (c.history || 0) + ' 历史';
        var s3 = $('bkScopeSet');
        if (s3) s3.textContent = '全部偏好 + ' + (c.searchHist || 0) + ' 条搜索历史';

        _bkPaintLocal();

        var ds = $('bkDavState');
        if (ds) ds.textContent = App.bk.hasDav
            ? ((App.bk.davUrl || '已配置') + (App.bk.davDir ? ' / ' + App.bk.davDir : ''))
            : '未配置';
        var up = $('bkDavUpBtn');
        if (up) up.disabled = !App.bk.hasDav;

        var sub = $('bkEntrySub');
        if (sub) sub.textContent = App.bk.hasDav
            ? '本机 + 网盘（WebDAV）'
            : '本机备份 / 可配置网盘（WebDAV）';

        _bkRefreshDavList();
    };

    // ---------- 本地备份 ----------

    /** 选备份目录（原生拉起 SAF 目录选择器） */
    ui.bkPickDir = function () {
        TS.call('pickBackupDir');
    };

    /** 原生：目录已选 */
    window.onBackupDir = function (o) {
        o = o || {};
        if (!o.ok) {
            if (!o.cancel) TS.toast(o.error || '未选择目录');
            return;
        }
        // ★ 原生下发的键是 uri（与下载目录那套保持一致），历史上这里误读成
        //   treeUri，导致 treeUri 恒为空串 → 备份时判成「没选过目录」。
        //   两个键都认，避免任何一侧改名又断掉。
        App.bk.treeUri = o.uri || o.treeUri || '';
        App.bk.dirName = o.name || '';
        lsSet(LS_BK_TREE, App.bk.treeUri);
        lsSet(LS_BK_NAME, App.bk.dirName);
        _bkPaintLocal();
        bkLog('已设置本地备份位置：' + (App.bk.dirName || '(未命名)'));
        TS.toast('备份位置已设置');
    };

    /** 备份到本机 */
    ui.bkBackupLocal = function () {
        if (App.bk.busy) { TS.toast('正在执行上一个操作，请稍候'); return; }
        if (!App.bk.treeUri) {
            ui.confirmSheet('还没有选择备份位置。要现在去选一个文件夹吗？', function () {
                ui.bkPickDir();
            });
            return;
        }
        var name = _bkDefaultName();
        ui.inputSheet('备份文件名', name, function (v) {
            v = (v || '').replace(/[\\/:*?"<>|]/g, '').trim();
            if (!v) { TS.toast('已取消'); return; }
            if (!/\.fxbak$/i.test(v)) v += '.fxbak';
            App.bk.busy = true;
            bkLog('开始备份到本机：' + v);
            var sum = $('bkSummary');
            if (sum) sum.textContent = '正在打包并写入…';
            TS.call('backupLocal', App.bk.treeUri, v);
        });
    };

    function _bkDefaultName() {
        var d = new Date();
        function p2(n) { return n < 10 ? '0' + n : '' + n; }
        return 'fengxi-' + d.getFullYear() + p2(d.getMonth() + 1) + p2(d.getDate())
            + '-' + p2(d.getHours()) + p2(d.getMinutes()) + '.fxbak';
    }

    /** 原生：本地备份完成 */
    window.onBackupDone = function (o) {
        o = o || {};
        App.bk.busy = false;
        if (o.ok) {
            bkLog('备份成功：' + (o.name || '') + '（' + bkSize(o.size) + '）');
            TS.toast('备份完成：' + (o.name || ''));
            App.bk.lastLocal = o;
        } else {
            bkLog('备份失败：' + (o.error || '未知错误'));
            TS.toast('备份失败：' + (o.error || '未知错误'));
        }
        TS.call('backupStatus');
    };

    /** 从备份包恢复 */
    ui.bkPickFile = function () {
        if (App.bk.busy) { TS.toast('正在执行上一个操作，请稍候'); return; }
        TS.call('pickBackupFile');
    };

    /** 原生：选到了 .fxbak 文件 */
    window.onBackupFilePicked = function (o) {
        o = o || {};
        if (!o.ok) {
            if (!o.cancel && o.error) TS.toast(o.error);
            return;
        }
        var msg = '将从「' + (o.name || '备份包') + '」恢复数据。\n\n'
            + '恢复会先自动备份当前数据（保命包），再整体覆盖书源、收藏、历史与设置。'
            + '\n\n确认继续？';
        ui.confirmSheet(msg, function () {
            App.bk.busy = true;
            bkLog('开始从本机备份包恢复：' + (o.name || ''));
            var sum = $('bkSummary');
            if (sum) sum.textContent = '正在恢复…';
            TS.call('restoreLocal', o.uri || '');
        });
    };

    // ---------- 恢复统一结果 ----------

    /** 原生：恢复前先把包里的 localStorage 灌回网页 */
    window.onRestoreLocalStorage = function (o) {
        o = o || {};
        var ls = o.localStorage || o || {};
        var n = 0;
        try {
            for (var k in ls) {
                if (!ls.hasOwnProperty(k)) continue;
                if (k.indexOf('fx_') !== 0) continue;
                var v = ls[k];
                if (v == null) continue;
                localStorage.setItem(k, String(v));
                n++;
            }
        } catch (e) {}
        if (n) bkLog('已恢复 ' + n + ' 项界面偏好');
    };

    /** 原生：恢复完成（本机 / 网盘 / 自动备份三路共用） */
    window.onRestoreDone = function (o) {
        o = o || {};
        App.bk.busy = false;
        // counts 的键与 USER_TABLES 同名（snake_case）
        var c = o.counts || {};
        if (o.ok) {
            var line = '恢复成功：源配置 ' + ((c.source_conf || 0) + (c.custom_source || 0))
                + ' 条 · 收藏 ' + (c.fav || 0) + ' 条 · 历史 ' + (c.history || 0) + ' 条';
            if (o.autoBackupName) line += '（已自动备份：' + o.autoBackupName + '）';
            bkLog(line);
            if (o.warn) bkLog('提示：' + o.warn);
            ui.confirmSheet('恢复完成。书源已重新加载，建议回到首页刷新一次。'
                + (o.warn ? '\n\n（有部分内容未完全恢复：' + o.warn + '）' : ''),
                function () { if (ui.home) ui.home(); });
            TS.toast('恢复完成');
        } else {
            bkLog('恢复失败：' + (o.error || '未知错误'));
            TS.toast('恢复失败：' + (o.error || '未知错误'));
        }
        TS.call('backupStatus');
        _bkRefreshAutoList();
    };

    // ---------- 网络备份（WebDAV） ----------

    ui.bkDavEdit = function () {
        TS.call('davConfig');
    };

    /** 原生：返回当前 WebDAV 配置 */
    window.onDavConfig = function (o) {
        o = o || {};
        var url = o.url || '';
        var user = o.user || '';
        var dir = o.dir || 'fengxi-backup';
        var hasPass = !!o.hasPass;
        var auth = o.auth || 'auto';

        var h = '<div class="sheet-mask" onclick="App.ui.closeSheet()">';
        h += '<div class="sheet" onclick="event.stopPropagation()">';
        h += '<div class="sheet-hd">WebDAV 服务器</div>';
        h += '<div class="cf-msg">支持坚果云、Nextcloud、群晖、威联通、Alist 等。'
            + '坚果云请使用「应用密码」而不是登录密码。</div>';
        h += '<input id="davUrl" class="sheet-input" type="text" placeholder="https://dav.jianguoyun.com/dav/" value="'
            + escAttr(url) + '">';
        h += '<input id="davUser" class="sheet-input" type="text" placeholder="账号 / 邮箱" value="'
            + escAttr(user) + '">';
        h += '<input id="davPass" class="sheet-input" type="password" placeholder="'
            + (hasPass ? '已保存，留空则不修改' : '应用密码') + '" value="">';
        h += '<input id="davDir" class="sheet-input" type="text" placeholder="备份目录" value="'
            + escAttr(dir) + '">';
        h += '<div class="cf-msg">认证协议：大多数服务用「自动」即可；若提示密码错误但密码正确，'
            + '多半是服务端要求 Digest，可改「Digest」或保持「自动」。</div>';
        h += '<select id="davAuth" class="sheet-input">'
            + '<option value="auto"' + (auth === 'auto' ? ' selected' : '') + '>自动（推荐）</option>'
            + '<option value="basic"' + (auth === 'basic' ? ' selected' : '') + '>仅 Basic</option>'
            + '<option value="digest"' + (auth === 'digest' ? ' selected' : '') + '>仅 Digest</option>'
            + '</select>';
        h += '<div class="sheet-item" id="davOk">保存</div>';
        if (hasPass) h += '<div class="sheet-item danger" id="davClear">清除配置</div>';
        h += '<div class="sheet-item cancel" onclick="App.ui.closeSheet()">取消</div>';
        h += '</div></div>';
        sheet(h);

        function val(id) { var e = $(id); return e ? e.value : ''; }

        var ok = $('davOk');
        if (ok) ok.onclick = function () {
            var u = val('davUrl').trim();
            var n = val('davUser').trim();
            var p = val('davPass');
            var d = val('davDir').trim();
            var a = val('davAuth') || 'auto';
            if (!u) { TS.toast('请填写服务器地址'); return; }
            ui.closeSheet();
            App.bk.busy = true;
            bkLog('正在保存 WebDAV 配置…');
            TS.call('davSave', u, n, p, d, a);
        };
        var cl = $('davClear');
        if (cl) cl.onclick = function () {
            ui.closeSheet();
            ui.confirmSheet('清除已保存的 WebDAV 地址与密码？', function () {
                App.bk.busy = true;
                TS.call('davSave', '', '', '', '', 'auto');
            });
        };
    };

    /** 原生：WebDAV 配置已保存 */
    window.onDavSaved = function (o) {
        o = o || {};
        App.bk.busy = false;
        if (o.ok) {
            bkLog('WebDAV 配置已更新' + (o.url ? '：' + o.url : '（已清空）'));
            TS.toast(o.url ? '已保存' : '已清除配置');
        } else {
            bkLog('保存失败：' + (o.error || '未知错误'));
            TS.toast('保存失败：' + (o.error || '未知错误'));
        }
        TS.call('backupStatus');
    };

    ui.bkDavTest = function () {
        if (!App.bk.hasDav) { TS.toast('请先填写服务器设置'); ui.bkDavEdit(); return; }
        var v = $('bkDavTestVal');
        if (v) v.textContent = '测试中…';
        bkLog('正在测试 WebDAV 连接…');
        TS.call('davTest');
    };

    /** 原生：WebDAV 连接测试结果 */
    /** ★ v3.9.43：信任自签名证书开关（家庭 NAS 的 https:// 必开，默认关） */
    ui.bkTrustSsl = function (on) {
        TS.call('setDavTrustSsl', !!on);
        TS.toast(on ? '已信任自签名证书（仅对 WebDAV 生效）'
                    : '已恢复严格证书校验');
    };

    /** 原生：开关状态已更新（回推） */
    window.onDavTrustSsl = function (o) {
        o = o || {};
        var el = $('bkTrustSsl');
        if (el) el.checked = !!o.on;
    };

    window.onDavTested = function (o) {
        o = o || {};
        var v = $('bkDavTestVal');
        if (o.ok) {
            var txt = '正常' + (o.latencyMs ? '（' + o.latencyMs + 'ms）' : '');
            if (v) v.textContent = txt;
            bkLog('WebDAV 连接正常（HTTP ' + (o.code || 207) + '，' + (o.latencyMs || 0) + 'ms）');
            TS.toast('连接正常');
        } else {
            if (v) v.textContent = '失败';
            bkLog('WebDAV 连接失败：' + (o.error || ('HTTP ' + (o.code || '?'))));
            TS.toast('连接失败：' + (o.error || '请检查地址与密码'));
        }
    };

    ui.bkDavBackup = function () {
        if (App.bk.busy) { TS.toast('正在执行上一个操作，请稍候'); return; }
        if (!App.bk.hasDav) { TS.toast('请先填写服务器设置'); ui.bkDavEdit(); return; }
        var name = _bkDefaultName();
        ui.inputSheet('上传到网盘的文件名', name, function (v) {
            v = (v || '').replace(/[\\/:*?"<>|]/g, '').trim();
            if (!v) { TS.toast('已取消'); return; }
            if (!/\.fxbak$/i.test(v)) v += '.fxbak';
            App.bk.busy = true;
            bkLog('开始上传到网盘：' + v);
            TS.call('davBackup', v);
        });
    };

    /** 原生：网盘备份完成 */
    window.onDavBackupDone = function (o) {
        o = o || {};
        App.bk.busy = false;
        if (o.ok) {
            bkLog('已上传：' + (o.name || '') + '（' + bkSize(o.size) + '）');
            TS.toast('已上传到网盘');
            TS.call('davList');
        } else {
            bkLog('上传失败：' + (o.error || '未知错误'));
            TS.toast('上传失败：' + (o.error || '未知错误'));
        }
    };

    ui.bkDavRefresh = function () {
        if (!App.bk.hasDav) { TS.toast('请先填写服务器设置'); ui.bkDavEdit(); return; }
        _bkRefreshDavList();
    };

    /** 原生：网盘上的备份列表 */
    window.onDavList = function (o) {
        o = o || {};
        var box = $('bkDavList');
        if (!box) return;
        if (!o.ok) {
            box.innerHTML = '<div class="tip">读取网盘失败：'
                + TS.esc(o.error || '网络不可用') + '</div>';
            bkLog('读取网盘列表失败：' + (o.error || ''));
            return;
        }
        var list = o.items || [];
        if (!list.length) {
            box.innerHTML = '<div class="tip">网盘上还没有备份包。'
                + '点上面的「备份到网盘」上传第一份。</div>';
            return;
        }
        var h = '<div class="chapter-hd"><span>共 ' + list.length + ' 份</span></div>';
        for (var i = 0; i < list.length; i++) {
            var f = list[i];
            h += '<div class="setrow">';
            h += '<div class="setlabel">' + TS.esc(f.name || '')
                + '<span class="setdesc">' + TS.esc(f.sizeText || bkSize(f.size))
                + (f.atText ? ' · ' + TS.esc(f.atText) : '') + '</span></div>';
            h += '<div class="setval">';
            h += '<button class="mini" data-dav="'
                + escAttr(f.name || '') + '">恢复</button> ';
            h += '<button class="mini ghost" data-davdel="'
                + escAttr(f.name || '') + '">删除</button>';
            h += '</div></div>';
        }
        box.innerHTML = h;

        // 事件委托：避免在 HTML 字符串里嵌套引号导致内联 onclick 出错
        box.onclick = function (e) {
            var t = e.target || e.srcElement;
            if (!t || t.tagName !== 'BUTTON') return;
            var rn = t.getAttribute('data-dav');
            if (rn) { _bkDavRestore(rn); return; }
            var dn = t.getAttribute('data-davdel');
            if (dn) { _bkDavDelete(dn); }
        };
    };

    function _bkDavRestore(name) {
        if (App.bk.busy) { TS.toast('正在执行上一个操作，请稍候'); return; }
        var msg = '将从网盘文件「' + name + '」恢复数据。\n\n'
            + '恢复会先自动备份当前数据，再整体覆盖。确认继续？';
        ui.confirmSheet(msg, function () {
            App.bk.busy = true;
            bkLog('开始从网盘恢复：' + name);
            TS.call('davRestore', name);
        });
    }

    function _bkDavDelete(name) {
        ui.confirmSheet('删除网盘上的「' + name + '」？此操作不可撤销。', function () {
            App.bk.busy = true;
            TS.call('davDelete', name);
        });
    }

    /** 原生：网盘文件已删除 */
    window.onDavDeleted = function (o) {
        o = o || {};
        App.bk.busy = false;
        if (o.ok) {
            bkLog('已删除网盘文件：' + (o.name || ''));
            TS.toast('已删除');
            TS.call('davList');
        } else {
            bkLog('删除失败：' + (o.error || '未知错误'));
            TS.toast('删除失败：' + (o.error || '未知错误'));
        }
    };

    // ---------- 自动备份（恢复前的保命包） ----------

    /** 原生：自动备份清单 */
    window.onAutoBackupList = function (o) {
        o = o || {};
        var box = $('bkAutoList');
        if (!box) return;
        var list = o.items || [];
        if (!list.length) {
            box.innerHTML = '<div class="tip">（还没有自动备份。第一次执行恢复时会自动生成。）</div>';
            return;
        }
        var h = '';
        for (var i = 0; i < list.length; i++) {
            var f = list[i];
            h += '<div class="setrow">';
            h += '<div class="setlabel">' + TS.esc(f.name || '')
                + '<span class="setdesc">' + TS.esc(f.sizeText || bkSize(f.size))
                + (f.atText ? ' · ' + TS.esc(f.atText) : '') + '</span></div>';
            h += '<div class="setval"><button class="mini" data-auto="'
                + escAttr(f.name || '') + '">恢复</button></div>';
            h += '</div>';
        }
        box.innerHTML = h;
        box.onclick = function (e) {
            var t = e.target || e.srcElement;
            if (!t || t.tagName !== 'BUTTON') return;
            var n = t.getAttribute('data-auto');
            if (!n) return;
            _bkAutoRestore(n);
        };
    };

    function _bkAutoRestore(name) {
        if (App.bk.busy) { TS.toast('正在执行上一个操作，请稍候'); return; }
        var msg = '将用自动备份「' + name + '」整体覆盖当前数据。\n\n'
            + '（恢复前仍会再自动备份一次当前状态）确认继续？';
        ui.confirmSheet(msg, function () {
            App.bk.busy = true;
            bkLog('开始从自动备份恢复：' + name);
            TS.call('autoBackupRestore', name);
        });
    }

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
            ver: '4.7.1',
            date: '2026-10-25',
            items: [
                '修复：盘内书「播放失败，15 秒后自动停止」——章节信息解析时字段整体错位一位（把书名当成了文件 id），直链必然失败，已修',
                '修复：盘内书「不能选集」——章节缓存的存/取两边 key 算法不一致，读回来的永远是空列表，已统一',
                '说明：升级后无需重新扫描，之前扫出的书直接点播放即可；若之前清过缓存则需重新扫描一次',
            ],
        },
        {
            ver: '4.7.0',
            date: '2026-10-25',
            items: [
                '修复：网盘盘内书「不能选集」——播放时把整份章节表缓存下来（重启也在），详情页/选集/历史续播都有完整集数',
                '修复：分享解析成功但结果是空/全是文件夹时没有任何提示，现在三种结果都有明确弹窗',
                '提醒：如果之前播串过书，旧的播放记录建议到 设置 → 一键清理缓存 后重新扫描播放',
            ],
        },
        {
            ver: '4.6.0',
            date: '2026-10-25',
            items: [
                '修复：盘内扫描的书播放时串到别的资源——书名含「/」等字符时书的 id 会互相冲突导致历史/缓存串书，已修；且网盘书播放只走网盘直链，绝不换到其他源',
                '修复：分享链接解析失败时没有提示像「没反应」，现在失败立即弹错误、45 秒无响应提示超时',
                '新增：首页左上角下拉新增「网盘源」分组——已启用的网盘源可当首页源，点进去就是该网盘的有声书书架',
                '优化：盘内扫描书列表重新排版（书名+集数+播放一行一 book，不再错乱）',
                '调整：首页顶栏「1列」分列按钮移除，列数选择移到 设置 → 首页列数；自定义源入口也移到设置页',
            ],
        },
        {
            ver: '4.5.0',
            date: '2026-10-25',
            items: [
                '新增：扫描已登录网盘内的有声书——点「扫描盘内书」，自动找网盘里的音频文件夹成书，一键整本播放，不用再找分享链接',
                '修复：夸克/UC 分享解析遇到「书名/章节」文件夹结构时只显示文件夹没法播，现在自动展平子文件夹',
                '说明：阿里云盘因网页接口签名风控暂不支持盘内扫描，分享解析不受影响',
            ],
        },
        {
            ver: '4.4.0',
            date: '2026-10-25',
            items: [
                '网盘登录大升级：全部 6 家网盘新增「网页登录」——点一下弹出登录页，正常登录后 Cookie 自动抓取保存，无需再抄 Cookie',
                '115 扫码登录、天翼密码登录保留，三种方式任选',
                '登录页支持缩放，登录成功自动关闭返回',
            ],
        },
        {
            ver: '4.3.0',
            date: '2026-10-25',
            adds: [
                '★ 播放页点击旋转封面 = 播放/暂停：大圆盘好按，按下有轻微缩放',
                '   反馈；旋转角度不受影响，暂停仍停在当前角度'
            ]
        },
        {
            ver: '4.2.1',
            date: '2026-10-25',
            fixes: [
                '★★ 修复「本地书源重启后列表清空」：v4.2.0 的扫描结果持久化恢复',
                '   只挂在扫描入口上，而重启后列表走的是 localBooks（直读内存缓存），',
                '   恢复逻辑根本没被触发 —— 现在缓存为空时自动从磁盘恢复上次的',
                '   扫描结果，重开 App 列表直接可用'
            ]
        },
        {
            ver: '4.2.0',
            date: '2026-10-25',
            fixes: [
                '★★ 首页返回键改双击退出：去掉「确定要退出吗」确认弹框，单击返回',
                '   只提示「再按一次返回桌面」，2.2 秒内再按直接回桌面（后台播放',
                '   不受影响，通知栏照常可控）',
                '★★ 本地书源权限不足时自动弹出系统授权框：点「扫描本机音频」不再',
                '   需要先按「去授权」再手动重扫，授权完自动重扫',
                '★★ 本地扫描结果持久化：重开 App 不用重新扫描，上次扫到的书直接',
                '   可用（改过扫描范围才会触发重新扫描）'
            ],
            adds: [
                '★ 115 网盘支持扫码登录：弹窗显示二维码，手机扫一下自动拿 Cookie，',
                '   免去打开电脑 F12 抄 Cookie',
                '★ 天翼云盘支持账号密码登录：输手机号+密码即可，密码走官方加密通道；',
                '   触发验证码时明确提示改用 Cookie 方式',
                '★ 其余网盘（百度/阿里/夸克/UC）无公开登录接口，「配置 Cookie」保留'
            ]
        },
        {
            ver: '4.1.0',
            date: '2026-10-25',
            fixes: [
                '★★ 修复本地书源扫不到音频：媒体库不再按"音乐/时长"过滤（听书文件',
                '   常被标成非音乐且时长未提取，之前整批漏掉），文件系统兜底扫描改为',
                '   始终执行（.nomedia 目录、未索引文件不再丢）；存储权限请求同时兼容',
                '   READ_MEDIA_AUDIO 与 READ_EXTERNAL_STORAGE（各家 ROM 都能弹窗）',
                '★★ 修复小分辨率下悬浮球播放键偏移：播放图标居中改用 grid（老 WebView',
                '   的 flex 偶发错位），小屏(<360px)球体自动缩到 48px，拖动起点补偿',
                '   按压缩放差，位置记忆改为"靠边+比例"（旋转/换分辨率不再跑偏）',
                '★ 源识别失败可救回：章节表为空时自动清缓存重试一次；仍失败会显示',
                '   各源的具体报错 + 来源 + 一键重试按钮（之前只有一句"没读到章节"）',
                '★★ 修复源列表双向不同步：首页删源后"书源管理"还留着、反之亦然 ——',
                '   现在所有源操作后首页缓存与权威源表严格对齐',
                '★ 首页左上角源下拉：长按任意源弹出 重命名/停用启用/测试连通/删除',
                '   （内置与插件源不可删，提供停用）；首页批量删除对内置/插件源',
                '   自动改为停用并明确提示，不再"点了删除却还在"'
            ]
        },
        {
            ver: '3.9.54',
            date: '2026-10-19',
            fixes: [
                '★ 全新应用图标：白底圆角方块 + FX 翻开的书与音符，黑色背景已去除',
                '   · 桌面图标（legacy + 自适应 + 圆形）与关于页 Logo 全部同步更换'
            ]
        },
        {
            ver: '3.9.53',
            date: '2026-10-19',
            fixes: [
                '★★ 修复「音量增益几乎没有效果」：换算从线性改为对数式（dB 正确换算）',
                '   · 之前 200% 只有 +1.8dB（v3.9.7 为防爆音砍半的结果），几乎听不出',
                '   · 现在 200% = +6dB（真·音量翻倍）、300% = +9.5dB，增益上限恢复 300',
                '   · 300% 也比旧换算的 300%（+12dB 必爆音）温和，LoudnessEnhancer',
                '     硬限 2000mB 内安全；弱音听不清的集数可以放心往上拉',
                '★ 快进/快退步长细化：5 秒一档（5~60 秒），播放页快进快退按钮',
                '   真正使用该步长（之前按钮写死 ±30，设置形同虚设，一并修复）',
                '★ 自动预读集数：预设档位扩展到 50 集（1/2/3/5/10/20/30/50），',
                '   并新增「自定义…」输入（1~50 任意整数）'
            ]
        },
        {
            ver: '3.9.52',
            date: '2026-10-19',
            fixes: [
                '★ 性能清理：修复弹层/列表操作发卡',
                '   · 首页源管理勾选改为就地更新（之前每点一次整页重绘网格）',
                '   · 去掉重复的源清单刷新调用（一次操作触发两次全量重绘）',
                '   · 备份页「执行记录」改为节流合并刷新 + 只保留最近 60 条',
                '     （之前每条日志全量重写 200 行长文本，连续写日志时明显卡）'
            ]
        },
        {
            ver: '3.9.51',
            date: '2026-10-19',
            fixes: [
                '★ 修复：首页源管理「提示成功但未生效」',
                '   · 根因：原生侧停用/删除其实已生效（实时写库），但首页列表用的',
                '     源缓存（homeSources）从不跟随更新 —— 现在停用/删除后立即从',
                '     列表移除，并向原生拉取权威清单二次校准',
                '★ 「我的」页去重：我的下载 / 备份与恢复 / 更新公告 三个入口与',
                '  「设置」页重复，全部移除（设置页里保留）',
                '★ 备份恢复清单兜底方案实测通过（用户确认备份问题已修复）'
            ]
        },
        {
            ver: '3.9.50',
            date: '2026-10-19',
            fixes: [
                '★ 新功能：首页源列表长按管理',
                '   · 在首页「选择书源」长按任意源 → 进入管理模式（带震动反馈）',
                '   · 管理模式：点卡片单选/再点取消，支持全选/反选批量操作',
                '   · 操作：启用 / 停用 / 删除；停用或删除后源立即从列表移除',
                '   · 返回键先退出管理模式，不误触其他行为',
                '★ 备份恢复收尾：恢复列表失败时，若远端清单不存在（404）会明确',
                '  提示「先点一次备份到网盘」——清单走 GET/PUT，生成后恢复列表',
                '  不再受 PROPFIND 拦截影响'
            ]
        },
        {
            ver: '3.9.49',
            date: '2026-10-18',
            fixes: [
                '★ 恢复列表「清单兜底」——不再强依赖 PROPFIND',
                '   · 每次「备份到网盘」时自动维护远端清单文件 fengxi-manifest.json',
                '     （GET 旧清单→合并→PUT 新清单），删除备份时同步移除条目',
                '   · 恢复列表优先走 PROPFIND（权威、能看到所有文件），被链路拦成',
                '     501 时自动改用 GET 读清单 —— GET 是标准方法，全网畅通',
                '   · 效果：只要「备份到网盘」能用，恢复列表就一定有数据，',
                '     备份→恢复列表→恢复 整条链路不再怕方法拦截',
                '   · 注意：清单只记录本 App 的备份；若你在服务器上手工放了',
                '     .fxbak 文件，在 PROPFIND 可用的网络下列表会显示它们',
                '★ 501 诊断码附响应 Server 头（v3.9.48 实测为 S:nginx），拦截层',
                '  身份可与服务商官方标识直接比对'
            ]
        },
        {
            ver: '3.9.48',
            date: '2026-10-18',
            fixes: [
                '★ 重大发现：坚果云与飞牛返回了完全相同的畸形序列[501,501,403,501,501]',
                '   —— 两家无关服务商给出逐字节一致的响应，说明拦截发生在手机与',
                '   服务器之间（VPN/网络加速器/抓包工具/广告过滤/私人DNS 等），',
                '   不是服务商问题。PROPFIND 全灭但 PUT/DELETE 畅通，所以',
                '   「备份到网盘」能用、「测试连接」正常（它靠 PUT 探针）、',
                '   唯独恢复列表全灭',
                '★ 恢复列表失败提示新增「方法被链路拦截」识别：出现该特征时直接',
                '   建议关闭相关软件或切换 WiFi/移动数据；诊断码同时附带响应',
                '   Server 头（S: 开头），与服务商官方标识不符即为拦截者身份',
                '★ 快速自检：坚果云有正规证书，用坚果云时可关闭「信任自签名',
                '   证书」开关再试——若报证书错误即证实链路上有中间人拦截'
            ]
        },
        {
            ver: '3.9.47',
            date: '2026-10-18',
            fixes: [
                '★ 恢复列表失败提示按服务商分流',
                '   · 实测确认：飞牛对「子目录 PROPFIND」回 501（根目录 Depth:0 却',
                '     正常 207），属服务端限制，客户端已尽力（5 种形式自动降级）',
                '   · 失败提示现在会区分：坚果云给排查清单（应用密码/地址/目录），',
                '     自建 NAS 则说明 501/405 为服务端限制、建议换用坚果云',
                '★ 坚果云为主推备份方案：备份/恢复/恢复列表已全通，新的降级逻辑',
                '  对坚果云零影响（首选拼法就是当初验证通过的标准请求）'
            ]
        },
        {
            ver: '3.9.46',
            date: '2026-10-18',
            fixes: [
                '★ 修复：飞牛恢复列表仍报 405（v3.9.45 补请求体后未根治）',
                '   · 现在列表读取自动尝试 5 种 PROPFIND 形式（标准形式/去尾斜杠/',
                '     无请求体/列根目录过滤/确保目录后重试），任何一种成功即出列表',
                '   · 若全部失败，报错会附带每一步的状态码（如[P1目录:405 P1根:207]），',
                '     截图发来即可一次性定位；同时会把「先备份一次建好远端目录」',
                '     作为最常见诱因直接提示',
                '   · 根目录可列但备份目录下为空时，会自动创建远端目录后再重试，',
                '     杜绝"列表成功但永远是空的"假象'
            ]
        },
        {
            ver: '3.9.45',
            date: '2026-10-18',
            fixes: [
                '★ 修复：飞牛备份成功但恢复列表报 405',
                '   · 根因：飞牛对「没有请求体」的 PROPFIND 列目录请求直接拒绝',
                '     （RFC 允许无请求体，但飞牛强制要求）—— 现在统一附带标准的',
                '     PROPFIND 请求体与 application/xml 类型'
            ]
        },
        {
            ver: '3.9.44',
            date: '2026-10-18',
            fixes: [
                '★ WebDAV 新增「信任自签名证书」开关（备份页 WebDAV 区）',
                '   · 飞牛 NAS 的 HTTP 端口(5005)对认证有限制，请改用',
                '     https://NAS的IP:5006，并开启此开关（自签证书默认拒绝，',
                '     开启后仅对 WebDAV 生效，其它网络请求不受影响）',
                '★ 坚果云用 http/https 均可，无需开启此开关（正规证书）'
            ]
        },
        {
            ver: '3.9.43',
            date: '2026-10-18',
            fixes: [
                '★ 修复：网盘恢复「刷不到记录」时错误被吞 —— 列表读取失败',
                '   （网络/权限/路径）原来表现为空列表，现在直接显示失败原因，',
                '   不再无法定位',
                '★ 飞牛 NAS 的 WebDAV 401：报错会带「凭据核对」行（账号/密码',
                '   长度/base64），截图发来即可确认 App 端凭据是否正确'
            ]
        },
        {
            ver: '3.9.42',
            date: '2026-10-18',
            fixes: [
                '★ WebDAV 401「密码错误」排查升级：报错时直接显示 App 实际发送的',
                '   账号、密码长度和凭据 base64 —— 截图即可当场核对 App 里存的',
                '   凭据是不是你以为的那份（多空格/大小写/旧密码一眼看穿）'
            ]
        },
        {
            ver: '3.9.41',
            date: '2026-10-18',
            fixes: [
                '★ 修复：坚果云「测试连接正常但备份报 403 OperationNotAllowed」',
                '   · 坚果云不允许第三方应用建目录（对已存在目录的建目录请求',
                '     也回 403）—— 现在遇到 403 会先确认目录是否已在服务器上，',
                '     在就直接上传；不在才提示去网页端先建文件夹',
                '★ 源列表点「仅搜索」源：弹出关键词输入，确认后立即搜索',
                '   并自动筛选到该源，一步出结果'
            ]
        },
        {
            ver: '3.9.40',
            date: '2026-10-18',
            fixes: [
                '★ 修复：坚果云 410 真凶落网 —— 上一轮的 URL 编码把 /dav/ 写成了',
                '   //dav/（双斜杠），坚果云服务器对双斜杠路径一律拒绝（410）。',
                '   编码逻辑重写并逐用例自测，坚果云/群晖/飞牛路径结构全部保真',
                '★ 源列表点「仅搜索」源：直接跳搜索页并自动聚焦输入框，',
                '   输入关键词立即搜索该源（这类源没有分类页，搜索是唯一入口）'
            ]
        },
        {
            ver: '3.9.39',
            date: '2026-10-18',
            fixes: [
                '★ 源列表模式的首页：列出全部启用的书源',
                '   · 支持分类浏览的直接进首页；',
                '     仅搜索源标注「仅搜索」，点了跳到搜索页（可按来源筛选）',
                '★ 修复：坚果云测试连接报 410（真正根因找到了）',
                '   · 坚果云的服务器对「点开头」的隐藏文件一律直接拒绝（410 Gone），',
                '     而连接测试的探针文件正是 .fx-probe.txt —— 撞在这上面；',
                '   · 探针改为普通文件名（fx-probe-xxx.txt）',
                '   · 建目录成功即视为连接可用；测试失败时附上每一步的诊断链，',
                '     再有问题把日志发来即可一眼定位'
            ]
        },
        {
            ver: '3.9.38',
            date: '2026-10-18',
            fixes: [
                '★ 设置新增「首页显示」开关（外观分组），两种方式：',
                '   · 源列表：首页先列出全部书源，点选后进入分类浏览；',
                '     点左上角源名随时回到源列表换源',
                '   · 内容直达（默认）：打开直接显示上次浏览的首页内容',
                '     （本地缓存秒出，零网络）；下拉刷新 / 切分类才联网更新',
                '★ 修复：坚果云「测试连接」报 410',
                '   · 坚果云对刚删除过的文件路径回 410（删除墓碑），',
                '     探针文件名改为随机名绕开；删除已不存在的文件也按成功处理',
                '   · 410/404 报错会附带服务器返回的具体原因'
            ]
        },
        {
            ver: '3.9.37',
            date: '2026-10-18',
            fixes: [
                '★ 移除字幕功能（上一版的实验性字幕，包括 LRC/txt 与开关）',
                '★ 修复：首页有时很久加载不出书源',
                '   · 源列表/首页信息等本地轻操作挪到独立线程池，不再被',
                '     搜索/备份等慢任务排队卡住',
                '   · 首页分类清单优先用已有缓存秒出（含过期缓存），后台静默刷新',
                '★ 悬浮球改为软件内实现',
                '   · 设置里的「播放悬浮球」开关现在控制的是 App 内右下角的封面球，',
                '     不再需要「显示在其他应用上层」权限',
                '   · 软件内悬浮球支持拖动，位置自动记忆，点按仍回播放页',
                '★ 修复：坚果云「测试连接正常但备份报没有权限（403）」',
                '   · WebDAV 路径里的中文/空格等现在会正确编码',
                '   · 403 时把服务器返回的具体原因附在提示里（限流/流量用超一目了然）'
            ]
        },
        {
            ver: '3.9.34',
            date: '2026-10-18',
            fixes: [
                '★ 定时关闭「按集停止」支持自定义集数（1~99，下拉选「自定义集数…」）',
                '★ 修复：点播放偶发「播放/停止反复抽搐」',
                '   · 刚失败后 0.8 秒内的连点直接忽略（错误态播放器被拉起会立刻再失败）',
                '   · 连续 3 次自救失败不再反复给缓冲窗，直接停止',
                '★ 跳过片头/片尾改为按专辑记录',
                '   · 播放页「更多」里设置的秒数只对当前书生效，不同书各自记忆；',
                '     没设置过的书跟随全局默认',
                '★ 「关于」页移除已修复问题的诊断入口（源加载诊断/小组件诊断），',
                '   保留崩溃日志',
                '★ 历史列表：最后一次播放的书加「上次播放」标识'
            ]
        },
        {
            ver: '3.9.32',
            date: '2026-10-18',
            fixes: [
                '★ 首页新增布局切换（导航栏「N列」按钮）',
                '   · 1/2/3 列循环：2/3 列为封面朝上的网格，选择自动记忆',
                '★ 播放页背景蒙版调淡',
                '   · 封面虚化的颜色能透出来了，不再「看不出和封面有啥关系」',
                '★ 播放失败兜底改为 15 秒',
                '   · 失败后先给 15 秒缓冲：期间恢复播放（自救成功/手动点播）',
                '     则取消停止；15 秒仍无法播放才真正停止',
                '★ 小组件透明形态加虚化边界',
                '   · 材质档=白色半透明柔边+圆角，透明下也能看清小组件范围'
            ]
        },
        {
            ver: '3.9.28',
            date: '2026-10-17',
            fixes: [
                '★ 诊断插桩（定位「调透明度无效」「401 凭据」两个悬案）',
                '   · 透明度：设置点击 / 保存值 / 小组件实际生效值 三处全链路记日志，',
                '     哪一环断了从「小组件诊断」一眼可见',
                '   · WebDAV 401：日志附上实际发送的 Basic 凭据（base64，仅存本机），',
                '     可逐字符核对输入法到底发出了什么'
            ]
        },
        {
            ver: '3.9.27',
            date: '2026-10-17',
            fixes: [
                '★ 小组件透明度修复（按反馈）',
                '   · 旧版本保存过的高不透明度（90/实底）会残留成黑块 —— 现自动迁移',
                '     回「材质」；档位：材质 / 25 / 50 / 75',
                '   · 纱色从近黑调为玻璃灰蓝（#39414E），半透明档位不再一黑到底',
                '★ WebDAV 401 日志补充密码长度（passLen），用于核对输入法是否',
                '  动过密码（多空格/缺字符）'
            ]
        },
        {
            ver: '3.9.26',
            date: '2026-10-17',
            fixes: [
                '★ 小组件观感调整（按反馈）',
                '   · 移除进度「高亮球」（观感违和），恢复纯色进度格',
                '   · 默认外观改为「材质」：透明底 + 边界圈（毛玻璃感最好看）',
                '   · 透明度档位改为 材质/20/35/50：默认材质，数字越大底色越实，',
                '     差异肉眼可辨（之前 75/90 与实底几乎无差是因为底色太深）'
            ]
        },
        {
            ver: '3.9.25',
            date: '2026-10-17',
            fixes: [
                '★ 小组件外观升级（设置 → 外观 → 小组件）',
                '   · 边缘增加 2dp 灰蓝边界圈，桌面上一眼看清小组件范围',
                '   · 新增「背景不透明度」：实底 / 90% / 75% / 50% / 材质（透明，',
                '     配合 iQOO/originOS 毛玻璃），设置页内调整、桌面即时生效',
                '   · 进度条改为「高亮小球」跟随进度移动（琥珀圆点+白圈）',
                '   · 小组件外观设置从「小组件诊断」页移到「设置 → 外观」',
                '★ WebDAV 401 诊断增强',
                '   · 401 时把服务器指纹（Server 头 / 认证质询 / 账号）写入诊断日志，',
                '     用于定位「密码没错却 401」到底是哪个服务在拒绝'
            ]
        },
        {
            ver: '3.9.24',
            date: '2026-10-17',
            fixes: [
                '★ 桌面小组件白卡问题修复收官（元凶：跑马灯滚动）',
                '   · 四轮二分定位确认：originOS 桌面对小组件内 marquee 滚动',
                '     （setSelected 动作）兼容性差，触发后整块白卡且点不动',
                '   · 永久移除跑马灯：超长书名改为省略号截断',
                '★ 小组件体验升级',
                '   · 进度条恢复可拖动（20 格点击跳转，每格 5%）',
                '   · 按钮加大（46dp 行高）、背景改不透明深色',
                '   · 新增「高级材质背景」开关：透明底可配合 iQOO/originOS 桌面',
                '     毛玻璃材质（设置 → 小组件诊断页内切换）',
                '   · 后台进度刷新恢复 3 秒一次'
            ]
        },
        {
            ver: '3.9.23',
            date: '2026-10-17',
            fixes: [
                '★ 小组件白卡二分第四轮：动作级「排查档位」',
                '   · 频率假设已排除（30s 仍白）；毒源锁定为完整版独有的某个更新动作',
                '   · 「小组件诊断」页新增档位循环按钮：',
                '     0=完整 → 1=去跑马灯动作 → 2=再去图标动作 → 3=再去点击动作',
                '   · 头号嫌疑=跑马灯（marquee 滚动在视图挂载后数秒才启动，',
                '     与「先显示、几秒后白」的时间特征吻合，且极简版无跑马灯）'
            ]
        },
        {
            ver: '3.9.22',
            date: '2026-10-17',
            fixes: [
                '★ 小组件白卡二分第三轮：验证「高频全量更新」假设',
                '   · 进度条已排除；新证据：添加瞬间的完整布局（含图标/跑马灯）',
                '     桌面能渲染，白卡总发生在连续更新之后——频率成为主要嫌疑',
                '   · App 退后台后进度刷新从 3s 放宽到 30s（状态变化仍立即刷新），',
                '     并去掉两个冗余更新动作',
                '   · 若这样能常驻 → 桌面扛不住高频重放，正式版将做成「状态变化刷新」'
            ]
        },
        {
            ver: '3.9.21',
            date: '2026-10-17',
            fixes: [
                '★ 小组件白卡二分第二轮：「无进度条」开关',
                '   · 极简模式实测正常 → 桌面无策略性封杀，是完整布局某构件问题',
                '   · 头号嫌疑=ProgressBar（带 ?android:attr 主题样式，需桌面主题解析）',
                '   · 「小组件诊断」页新增「无进度条模式」：保留封面/图标/按钮，',
                '     仅去掉进度条。极简关 + 无进度条开 → 能常驻即锁定进度条'
            ]
        },
        {
            ver: '3.9.20',
            date: '2026-10-17',
            fixes: [
                '★ 小组件白卡「二分排查」能力：新增极简模式开关',
                '   · 「我的 → 设置 → 小组件诊断」页顶部新增「极简模式」开关，',
                '     开启后小组件只显示纯文字（无图片/进度条/图标）',
                '   · 已确认：进程活着、每 3 秒成功更新、桌面托管正常时仍白卡，',
                '     问题锁定在桌面渲染环节 → 用极简/完整两档二分定位',
                '   · 极简模式也白 = 桌面对本 App 小组件的策略性限制；',
                '     极简不白 = 完整布局某个构件触发了桌面渲染失败，可精准移除'
            ]
        },
        {
            ver: '3.9.19',
            date: '2026-10-17',
            fixes: [
                '★ 小组件白卡第三轮：针对「变白后不能点击」的系统冻结嫌疑',
                '   · 播放服务每 60s 写一条「svc alive」进程心跳：白卡时若心跳',
                '     也停了 = 系统冻结了 App（省电限制）；心跳还在 = 桌面渲染问题',
                '   · App 退后台后小组件进度刷新降频到 3 秒一次（前台仍 0.8s 跟手），',
                '     降低触发 originOS 省电「高耗电冻结」的概率'
            ]
        },
        {
            ver: '3.9.18',
            date: '2026-10-17',
            fixes: [
                '★ 小组件白卡第二轮攻坚（重启后能显示、点进 App 回来变白）',
                '   · 诊断带上封面位图字节数，判定白卡是否与封面位图相关',
                '   · 封面进小组件前双保险加固：长边压到 144px、超 200KB 直接不进',
                '   · 该行为特征也与 originOS 省电限制相关：请到 系统设置→电池→',
                '     后台耗电管理→枫兮听书→允许后台高耗电；并在最近任务里锁定本 App'
            ]
        },
        {
            ver: '3.9.17',
            date: '2026-10-17',
            fixes: [
                '★ 小组件新增「托管探针」诊断',
                '   · 读取系统为小组件实例回报的桌面尺寸选项：',
                '     有尺寸 = 桌面已正常托管，问题在渲染；',
                '     显示「未托管」= 桌面侧绑定损坏（重启手机/清除桌面数据可解）'
            ]
        },
        {
            ver: '3.9.16',
            date: '2026-10-17',
            fixes: [
                '★ 小组件兼容模式重构（白卡最终修复尝试）',
                '   · 诊断证实 App 侧构建/提交全部成功，白卡发生在桌面渲染环节',
                '   · 每次刷新的 RemoteViews 动作从 40+ 个砍到 10 个左右：',
                '     进度条由「20 显示格 + 20 点击格（含 20 个 PendingIntent）」',
                '     简化为单个 ProgressBar——部分桌面对挂大量 PendingIntent 渲染失败',
                '   · 布局结构与 3.8.2（小组件首次回归、确认可用）同量级',
                '   · 代价：桌面上暂不支持点进度条跳转（App 内可拖进度）'
            ]
        },
        {
            ver: '3.9.15',
            date: '2026-10-17',
            fixes: [
                '★ 修复：桌面小组件白卡的另一处高嫌疑原因',
                '   · 诊断日志显示每次刷新都被渲染两遍（onReceive 与 super 重复调 onUpdate），',
                '     秒级进度下桌面每秒收到 4 次全量重绘（每次 40+ 个动作），',
                '     部分桌面（originOS 等）扛不住会卡住/白卡',
                '   · 现去掉重复渲染，刷新统一节流到最低 800ms 一次',
                '     （播放/暂停/切集等关键状态变化不受节流，立即刷新）'
            ]
        },
        {
            ver: '3.9.14',
            date: '2026-10-17',
            fixes: [
                '★ 修复：桌面小组件整块空白（不刷新）的一处漏网原因',
                '   · 四个控制键图标原来引用 @android:drawable（系统框架资源），',
                '     小组件由桌面渲染，部分桌面解析框架资源失败 → 整块白卡',
                '   · 与 v3.9.4 修过的「播放/暂停键框架资源」同源问题，本次全部换成本包资源',
                '★ 小组件诊断升级',
                '   · updateAppWidget 提交异常不再被静默吞掉，会记入「小组件诊断」',
                '   · 提交成功时记录内容状态（有无书名/播放态），桌面不刷新可一眼定位'
            ]
        },
        {
            ver: '3.9.13',
            date: '2026-10-17',
            fixes: [
                '★ 修复：Digest 认证在服务器质询 qop="auth,auth-int" 时必失败',
                '   · 根因：qop 选择了 auth-int，但哈希却按 auth 计算（HA2 缺 body 摘要）',
                '   · 现优先选 auth；仅服务器只给 auth-int 时才按 RFC 2617 算入 body 摘要',
                '★ 401 报错升级为「诊断级」：直接告知服务器要求的认证方式',
                '   · 区分：服务器要 Basic 但密码错 / 已按 Digest 应答仍被拒 / 地址不是 WebDAV 入口',
                '   · 错误里附服务器 WWW-Authenticate 质询摘要，截图即可定位'
            ]
        },
        {
            ver: '3.9.12',
            date: '2026-10-17',
            fixes: [
                '★ 修复：WebDAV 同步/备份报「密码错误」其实是认证协议不匹配',
                '   · 根因：部分服务（飞牛 NAS、部分 Nextcloud/自建）要求 Digest 认证，',
                '     旧版只发 Basic，服务器回 401 被笼统报成密码错',
                '   · 新增 Digest 认证（解析 WWW-Authenticate 质询 → 自动应答并重试）',
                '   · 设置页新增「认证协议」选择：自动 / 仅 Basic / 仅 Digest（默认自动）',
                '   · 401 文案精准区分：协议不匹配提示改协议，真密码错才提示检查密码'
            ]
        },
        {
            ver: '3.9.11',
            date: '2026-10-16',
            fixes: [
                '★ 修复：坚果云 WebDAV 测试连接报「没有权限（403）」',
                '   · 根因：坚果云对 /dav/ 根目录限制较多，列根目录会被拒，',
                '     但对子目录读写完全正常，旧测试逻辑被误判为连接失败',
                '   · 测试改为三级降级：测根 → 测备份目录 → 真实写入探测（PUT+DELETE）',
                '   · 403 提示文案补充坚果云专属排查建议（子目录地址/流量/频率限制）'
            ]
        },
        {
            ver: '3.9.10',
            date: '2026-10-16',
            fixes: [
                '★ 修复：聚合源 jar 导入失败（「jar 里没有找到可用的书源」）',
                '   · 根因：Android 10+ 无法再反射枚举 dex 类名，插件入口 SourceEntry 找不到',
                '   · 现改为直接解析 dex 字符串池提取类名，不依赖系统内部 API',
                '   · 兼容 zip 套 jar 的双层导入'
            ]
        },
        {
            ver: '3.9.9',
            date: '2026-10-16',
            fixes: [
                '★ 新增：支持导入 eprendre「我的听书」格式的聚合源插件（sources_by_agg 等 jar）',
                '   · 内置聚合包里的 20+ 源（番茄听书/博看/B站/酷我听/懒人听书 等）可搜索、看详情、播放',
                '   · 补齐宿主兼容层：直链取链器、Fuel 网络库最小实现（B站/博看 需要）',
                '   · 打包 okhttp + okio 运行库，插件按名链接零缺失'
            ]
        },
        {
            ver: '3.9.8',
            date: '2026-10-16',
            fixes: [
                '★★★ 修复「播放/暂停按钮疯狂抽搐」：v3.9.7 新增的"其他媒体打断"监听有个死循环 —— 暂停自己后自己的媒体流退出活跃列表，下一轮被误判成"别人停了"→自动恢复→又看到别人→再暂停，循环往复。已重写判定状态机：暂停期间绝不自动恢复，直到别的媒体真正全部停止',
                '★★ 定时关闭面板改为下拉式：倒计时、按集停止、可选时段各用下拉框选择，选中即生效 —— 旧版逐行平铺超出一屏，底部选项（保存时段/取消）点不到',
                '★ 所有底部弹层加了高度保险：内容超过屏幕 86% 时面板内部自动滚动，不会再被截断'
            ],
        },
        {
            ver: '3.9.7',
            date: '2026-10-15',
            fixes: [
                '★★ 定时关闭新增「按集停止」：可选播完本集 / 再听 2、3、5 集后自动暂停，与倒计时、时段关闭相互独立、先到先停；定时按钮和桌面小组件都会显示剩余集数',
                '★★ 修复「播放过的章节再点无法播放」：听完（或跳过片尾）的一集会把续播位置记到片尾附近，换源重解析后音频实际时长对不上，拿着超界位置反复 seek 失败 → 所有源都"救不活"。现在起播位置贴近/超出时长就自动从头播，自愈时同一位置二次失败也会改从头试',
                '★ 修复「已收听」标记叠加显示两个徽标（绿色圆圈「已」+ 绿色对勾挤在一起）的排版问题：现在只保留一个低调的灰绿小对勾，已听集号同时变淡，视觉更协调',
                '★★ 修复「设置了暂停播放，其他媒体还是打断不了」：以前只靠音频焦点（对方 App 得"自觉"申请才有效），很多国产 App 根本不申请。现在额外监听系统媒体播放状态，检测到别的 App 在放声音就按你的策略暂停/降音，对方停了自动恢复（Android 8.0+）',
                '★ 音量增益调低：上限 300% → 200%，放大幅度减半（200% ≈ +3dB）——原来的高档位实测是削波爆音不是更响，现在温和放大不劈'
            ],
        },
        {
            ver: '3.9.6',
            date: '2026-10-14',
            fixes: [
                '★★★ 找到「一打开就闪退」的**真正原因**并修复：读取「播放倍速」设置时用了 SharedPreferences.getFloat()，而手机里这个值被存成了**整数**，一读就抛 ClassCastException，直接把 MainActivity 的启动带崩 → 点图标必闪退。现已改为**宽容读取**（整数/长整数/浮点/字符串都能正确转成倍速），并把坏值自动改写成正确类型（自愈）——升级后**不用卸载重装**，打开即好',
                '★★ 堵住坏值的来源：恢复备份时，倍速这类「浮点设置项」以前会被写成整数（备份 JSON 里是整数时），现改为强制按浮点写回。以后恢复备份不会再踩这个坑',
                '★ 倍速读取加范围兜底：任何非法值（0、负数、超大）一律回落到 1.0 倍速，不再让异常值影响启动',
                '★ 沿用 v3.9.5 的成果：App 启动时各初始化步骤仍全部独立隔离，单点失败不再导致整个 App 打不开；崩溃日志双写（内部 + /sdcard/Android/data/com.tingshu.app/files/crash.log），排查更快'
            ],
            adds: []
        },
        {
            ver: '3.9.5',
            date: '2026-10-14',
            fixes: [
                '★★ 继续修复「一打开就闪退」：v3.9.4 的通知小图标修复没解决问题，说明崩溃点不在那里。本版把 App.onCreate 里**每一个初始化步骤都单独隔离**（设置、Cookie、播放状态、书源注册、Python 宿主、缓存、数据库），任何一步失败只让对应功能不可用，**不再让整个 App 打不开**',
                '★★ 崩溃日志现在**一定会留下**：把崩溃处理器提前到 Application 启动的第一行（原来排在初始化中段，一旦初始化崩溃它还没挂上，所以什么日志都没有）；并且**同时写一份到外部目录** /sdcard/Android/data/com.tingshu.app/files/crash.log（免权限），用「文件管理」直接就能找到，不用再连电脑抓 logcat',
                '★ 初始化失败的步骤会以「初始化失败（已隔离，未闪退）」的形式记进同一份崩溃日志，即使是没到崩溃程度的错误也能看到是哪一步出的问题',
                '★ 沿用 v3.9.4 的通知小图标修复（仍指向 App 自绘图标）与批量管理删除修复'
            ],
            adds: []
        },
        {
            ver: '3.9.4',
            date: '2026-10-14',
            fixes: [
                '★★ 修复「新版一打开就闪退」：通知栏播放控制的**小图标**误用了系统框架资源（android.R.drawable.*）。Android 规定前台服务通知的小图标必须来自本 App，用框架资源会在 startForeground() 时抛异常直接闪退。现已改回 App 自绘的品牌图标',
                '★★ 修复书源管理「分组的批量管理」里勾选后点删除毫无反应：一段 v3.7.14 时期的旧空实现残留在文件末尾，把真正的删除/全选/反选函数覆盖成了空壳。已移除残留，并给自定义源组隐藏无意义的「删除」按钮（本地书源 / 网盘接口本就不可删）',
                '★ 修复章节列表「已收听」标记不刷新的老问题复用：一处过期实现又把详情页改回整页重绘，导致滑动时卡顿并弹回顶部。已就地刷新（只增删「已」徽标）',
                '★ 修复首页「本地书源」视图下扫描完成后不显示书：扫描回包的分支恢复',
                '★ 删除文件内多段重复代码（自定义源页整段重复、Python 自检块重复），包体与运行都更干净'
            ],
            adds: []
        },
        {
            ver: '3.9.0',
            date: '2026-10-14',
            fixes: [],
            adds: [
                '★★ 桌面小组件白板重写：全新深色渐变卡片设计 —— 不透明深底（任意壁纸上白字都清晰）、封面右上角「正在播」状态点、四键全部坐上圆形背板（播放键用主题色渐变圆突出，比以前更好点）、进度条加粗到 6dp 且已播部分用暖色渐变。功能不变：可点进度条（20 格）、四键控制、点封面进 App',
                '★★ 新增「自定义源」：书源管理里置顶的「自定义源」组，内含两块能力。① 本地书源：扫描本机音频文件，按所在文件夹自动分成一本书（文件名即章节名，001/002 自然排序），全程本地播放不耗流量；② 主流网盘接口：阿里云盘、百度网盘、夸克网盘、天翼云盘、115网盘、UC网盘六个平台，粘贴分享链接解析出文件清单，勾选后直接入队播放 —— 自动下一集、历史续播、通知栏、小组件全部自动生效',
                '★★ 定时关闭升级为「进程级常驻」：以前倒计时长在播放页里，一退页面就停；现在由 App 进程自己计时，退后台、锁屏、甚至进程重启后都会恢复，到点照常暂停',
                '★ 定时关闭新增「可选时段」：可再选一个时段（如 22:00–06:00，支持跨午夜），只有当前时刻在时段内倒计时才递减；时段外自动停表、剩余时间保留，进入时段后继续。适合「只想睡前听、不希望白天也计时」的场景',
                '★ 选集页新增「已收听」标记：听过的集在章节列表里显示绿色小对勾（进度表里有过记录的就算），追长书时一眼看到哪集听过、哪集没听过',
                '★ 小组件新增两个角标：当前倍速（≠1.0 才显示，如「2×」）和定时关闭剩余时间（如「⏱ 23分」/「⏱ 停表」），播放状态一眼可见'
            ]
        },
        {
            ver: '3.8.5',
            date: '2026-10-13',
            fixes: [
                '★★ 桌面小组件推倒重写，解决「多台手机全部用不了」：前几版小组件的空白，是**桌面绘制卡片时解析不了我们自绘的那些样式**造成的 —— 背景一旦加载失败就回落到系统白底，而字和图都是白的，看上去就是一张白卡片（进度条还会退化成一个小圆圈）。本版把所有需要桌面去解析的自绘样式全部去掉：底色直接用固定颜色、进度条改成 20 个小格子直接上色、封面直接传图片，只保留系统自带图标。白卡、圆圈、内容残缺这些情况应该都能恢复正常',
                '★★ 修复返回键逻辑太乱：以前返回键完全没用上前端的处理结果，于是"打开了更多菜单或倍速面板，按返回却在切页/退出"。现在返回键一次收一层：先关弹窗 → 关长按菜单 → 折叠倍速/音量面板 → 退出全屏播放页 → 退回上一级 → 回首页',
                '★ 新增首页返回退出确认：在首页按返回会弹「确定要退出吗？」，点「退出」才关闭 App；若 2.5 秒内连按两次返回则直接退出，不用等弹窗'
            ],
            adds: []
        },
        {
            ver: '3.8.4',
            date: '2026-10-13',
            fixes: [
                '★★ 修复「跳过片头 / 跳过片尾」功能丢失：上一版构建时误用了旧版文件，把这两个功能整个冲掉了。本版完整恢复，播放页「更多」菜单里照旧能开关和微调（0~300 秒）',
                '★★ 修复桌面小组件还是一片空白：这次把能想到的宿主兼容性问题全部排掉了 —— 预览图换成普通位图（原来指向自适应图标，部分桌面加载不了）、进度条改用框架自带样式（不再依赖桌面解析主题属性）、四个按键全部换成系统框架位图（不再用自绘矢量，避免桌面进程加载资源失败）、绘制过程改成一段一段独立 try，任何一段出问题也只丢那一段的功能而不是整块变空白。另外加了诊断日志，万一还有问题也能一眼定位',
                '★ 修复源管理页「导入源 / 导出分享 / 批量验证」这一行在窄屏手机上排布错乱：按钮换行挤压，「N / N 启用」被孤零零挤到第二行。现在窄屏下压缩按钮内边距、计数固定不换行，一排整齐显示',
                '★★ 修复状态栏（通知栏）图标不是上传的枫兮听书图标：播放通知、进度刷新通知、悬浮球通知的小图标全部换成上传的 App 图标',
                '★ 修复系统媒体卡片（锁屏 / 通知播放控制）不显示可拖动进度条：开播瞬间往往还拿不到总时长，时长元数据一直没补进去，系统就永远不画进度条。现在时长一到位立即补写；Android 11 以上改由系统依据播放位置自动绘制，不再与手写进度条互相冲突',
                '★ 修正播放倍速在系统媒体控件里不可用：声明的动作位标志写错了一位（1<<16 应为 1<<30），导致部分系统（如原子播放器）读不到倍速能力'
            ],
            adds: []
        },
        {
            ver: '3.8.2',
            date: '2026-10-13',
            fixes: [
                '★★ 播放失败不再弹提示页面，改为自动停止播放：以前失败后弹的那个面板在弱网下频繁弹出，还偶发卡住挡操作。现在同一集先静默自救一次（用户无感知），确实救不回来就自动停止 —— 停转圈、图标复位、播放器释放、通知撤下，进度已保存可从原位续听，只留一行两秒的轻提示',
                '★ 修正静默自救没有上限的问题（开发中回归时抓出）：同一集只自救一次，救不回来就停止，不会不停自动重试；用户在自救的瞬间从通知栏或小组件点了停止，也不会被重试拉回来重新响起'
            ],
            adds: [
                '★ 新增「跳过片尾」：播放页「更多」菜单里，与「跳过片头」并列。播放到距离结尾还有设定秒数时自动收尾，按播放模式进下一集 / 重播 / 停止；拖动进度到结尾区同样生效。0~300 秒可调，带常用档位与 ±5 秒微调',
                '★ 新增桌面播放控制小组件：样式参考通知栏控制 —— 封面 + 书名 + 作者集数 + 可点进度条（20 格，每格 5%）+ 四键（上一集 / 播放暂停 / 下一集 / 停止，顺序与通知栏一致）。点击与通知栏走同一条播放服务通路，App 不在前台也能控制；在桌面小组件列表里选「枫兮听书 · 播放控制」即可添加',
                '★ 更换 App 图标：新的「枫兮听书」书法图标（含自适应图标，各密度与圆形变体齐全）'
            ]
        },
        {
            ver: '3.8.1',
            date: '2025-10-12',
            fixes: [
                '★★ 修复本地备份写不出去：选了文件夹却生成不了备份包。两个原因叠在一起 —— 一是界面读错了原生下发的字段（一直以为没选过目录），二是把「目录树地址」当成「文件地址」交给了系统，系统在里面找不到可写的落点。现在两条都修正，并加了一层兜底：根目录写不进去时自动退到子目录再试',
                '★★ 修复播放失败面板经常卡住、甚至整个界面点不动：加了「自救看门狗」—— 静默重试后 10 秒内没播起来就不再干等，直接出提示；同时给「短时间内重复报错只提示一次」加了上限，最多吞两次，第三次必定提示，不会再出现「转圈停了但什么都没有」',
                '★★ 修复深色模式存不住（大退后回到白色）：主题写入改成同步落盘，页面加载完成后再补刷一次主题，界面自己也会按已保存的主题重画。以前只在启动那一刻刷一次，而那时页面还没起来，等于白刷',
                '★ 修复书章节超过 2000 章后下面的分段选不了：分段菜单一滚动就把自己关掉了，而章节一多（每 200 章一段，2000 章就是 10 段）就必须滚动才能看到下面的段。现在菜单内部滚动不再误关',
                '★ 修复部分弹窗按返回键没反应：返回键以前完全不认识自绘弹窗，会直接穿透过去关掉播放页或退回上一级。现在返回键优先关弹窗',
                '★ 修复悬浮球偶尔跑到右上角：位置不再按绝对像素存，改成存「靠左/靠右 + 纵向比例」，横竖屏切换、折叠屏展开都不会跑偏；同时给顶部留了安全距离，不会再贴到状态栏上',
                '★ 修复带 @ 的书源链接添加失败（形如 https://xxx/a.json?download=true@____）：现在会自动剥掉 @ 后面的备注再请求，且只在确认不会影响正常网址时才剥',
                '修复播放失败面板与选集弹层叠在一起时，关掉上层后下层遮罩仍在、整页点不动'
            ],
            adds: [
                '★ 新增「跳过片头」：播放页「更多」菜单里。可设定起播自动快进多少秒（0~300 秒，带常用档位与 ±5 秒微调）。只对「没有续播进度」的集生效 —— 听到一半的集仍从原位置接着听，不会被再往前推一次'
            ]
        },
        {
            ver: '3.8.0',
            date: '2025-10-12',
            fixes: [],
            adds: [
                '★★ 新增「备份与恢复」：在「我的」页和设置页都能进入。一次把书源（含自定义源、分组、JS 插件包、Python 源、jar 插件）、收藏与分组、播放历史与播放进度、全部软件设置、搜索历史打包成一个 .fxbak 文件，换手机或重装后原样搬回来',
                '★ 本地备份：位置自己选（手机存储、SD 卡、外接 U 盘都行），选一次之后长期记住，之后一键生成备份包；恢复时直接选一个 .fxbak 文件即可',
                '★ 网络备份（WebDAV 协议）：支持坚果云、Nextcloud、群晖 / 威联通 NAS、Alist 等任何标准 WebDAV 服务。填地址、账号、密码、目录，可「测试连接」确认通不通，然后一键上传；网盘上的备份包可以列出、直接恢复、或删除',
                '恢复前自动留底：每次执行恢复都会先把当前数据自动打一个包存起来（最多保留 3 份），万一恢复到一半不满意，可以一键退回之前的状态',
                '备份包里不含音频缓存与已下载的音频文件（体积大且可重新获取），所以一份完整备份通常只有几百 KB，上传网盘很快'
            ]
        },
        {
            ver: '3.7.18',
            date: '2025-10-11',
            fixes: [
                '★★ 播放失败提示不再「加载中就弹」：以前一取链失败就立刻弹「播放失败」面板，但很多次只是源站瞬时抖动或直链临期，重新解析（含自动换源）就能救回来。现在改成两级 —— 第一次失败静默重试一次（转圈继续保持，不打扰你）；只有重试后仍然失败、确实连不上时，才弹出失败面板（面板里的「换下一集 / 换一个书源」按钮不变）',
                '★★ 修复存储占用异常增长：自动预读（播放时后台预存后 3 集）的缓存从来不执行「缓存上限」清理 —— 只有整本缓存走完流程才会清一次，所以日常使用中缓存只涨不落，单集音频 10~15MB，听一下午就能涨到几百 MB。现在每次预读落盘后都会立即按上限清理最旧的整本缓存',
                '★ 修复播放页选集「3 列」超出屏幕：3 列时每列被长章节名撑到接近整屏宽，第 2、3 列被推到屏幕外。根因是网格列宽 `1fr` 的隐含最小值不会小于内容宽度，长中文名（不能断行）就把列撑开了。现在把列宽的最小值显式钉为 0，2 列 / 3 列都能与屏幕等宽，任何机型、任何长度的章节名都不会再溢出',
                '彻底移除桌面播放控制小组件（长期无法稳定工作：空白、按钮失灵、点击只回 App，且不同手机系统兼容性不一致）。相关代码与资源已全部删除，不再占用任何资源。桌面播放控制请用「通知栏播放条」或系统自带的原子随身听 / 音乐控制中心'
            ],
            adds: [
                '设置页新增「存储占用」，把占了多少、具体占在哪逐项列清楚（音频缓存 / 应用数据 / 书源插件 / Python 运行时 / 安装包），并区分「可清理」与「不可清理」两类，方便你自己核对那个大数字是怎么来的',
                '设置页新增「一键清理缓存」：一次清掉音频缓存、封面图、详情页缓存，不动书源、收藏、收听历史与播放进度',
                '章节名称过长时的横向滚动（跑马灯）现在在 2 列 / 3 列布局下同样生效 —— 以前多列时会退化成省略号，只能看到前几个字'
            ]
        },
        {
            ver: '3.7.17',
            date: '2025-10-11',
            fixes: [
                '★★ 修复「JS 插件包装载失败：加载异常 NoClassDefFoundError: org.mozilla.javascript.W2」—— 打包混淆把 JS 引擎内部一个按名字反射加载的类（VMBridge）改成了 W2，运行时按原名找不到，整个 JS 引擎起不来，所以任何 JS 插件包都装不进去。这也是「JS 源消失」的最底层根因。现在把 JS 引擎整体加入混淆保护名单，并新增构建校验：引擎关键类一旦被改名，构建直接失败',
                '修复小组件再次空白：与上一条同源 —— JS 引擎类加载失败会让 App 进程反复崩溃，进程一挂小组件就没有内容可刷新。引擎修好后进程稳定，小组件随之恢复。装好本版后请长按移除旧小组件再重新添加一次'
            ],
            adds: []
        },
        {
            ver: '3.7.16',
            date: '2025-10-11',
            fixes: [
                '★★ 修复「桌面小组件一片空白 + 封面不显示 + 播放键没用 + 进度条点了没反应 + 点哪都跳回 App」—— 这五个症状其实是同一个根因：打包时的代码混淆步骤把小组件相关的两个类改了名字（PlayerWidgetProvider 被改成 a、点击接收器被改成 b），而系统是按 AndroidManifest 里写的原始类名去找类的，找不到就等于这个小组件根本不存在。上一版 v3.7.15 就已经带着这个问题了。现在把它加进混淆保护名单，并新增一道构建自动校验：以后任何 AndroidManifest 里注册的组件只要被改名，构建直接失败，不可能再流出去',
                '★ 修复小组件按钮「投递不到播放器」：桌面是另一个 App，它替我们点击时，系统会检查目标组件是否允许外部访问。原来的目标（播放服务、小组件 Provider）都是「仅本 App 可用」，外部的点击请求会被系统静默丢掉 —— 这就是「按钮点了毫无反应」。现在新增了一个专门对外接收点击的中间组件，由它再转给播放服务，链路就通了',
                '★ 修复播放界面「更多」菜单、选集「列数 / 区间」下拉菜单点了没反应：这两个菜单以前是在「打开它的那一次点击」里注册「点别处自动关闭」的监听，而这次点击还没结束、正在往外冒泡，立刻就把刚注册的监听触发了，菜单开了就被当场拆掉（肉眼几乎看不到）。现在改成等这一次点击彻底结束后再开始监听，并保证点菜单自己内部不会被误关',
                '修复上传的文件源（.zip / JS 插件包）导入后看不到 —— 与小组件的是同一个根因，混淆把相关类改名导致导入链路异常。现在已修复；若你之前导入过但在列表里找不到，重新导入一次即可',
                '菜单不再超出屏幕：宽度超过屏幕时自动收窄，长菜单项文字改成横向滑动查看（以前会被截断成「…」看不到内容）',
                '菜单在屏幕底部放不下时会自动翻到上方显示，不会再被屏幕边缘切掉'
            ],
            adds: [
                '选集列表的超长章节名改为横向滚动显示（跑马灯），不再被截成「第 001 集 三国演…」；详情页章节列表同样生效。多列排布时自动关闭滚动（格子太窄滚动反而看不清）'
            ]
        },
        {
            ver: '3.7.15',
            date: '2025-10-11',
            fixes: [
                '修复上一版（v3.7.14）安装包无法安装的问题 —— 那个包是没签名的，手机一律报「解析包错误」。原因已经定位：打包时把签名之前的中间产物当成了成品。现在构建脚本和发布脚本都加了签名硬闸门，签不过名直接判构建失败，这类问题不会再流到手上',
                '如果装过 v3.7.14，请先卸载再装本版（未签名包与正式签名冲突，无法直接覆盖）',
                '★ 修复桌面小组件「一片空白」：给进度条加的 20 个点击分区用了普通 View，而桌面小组件系统只允许特定几种控件（白名单里没有 View），启动器加载布局时直接失败，整块就渲染成空白。现在全部换成 ImageView，外观和点击行为完全一样。并加了自动校验，以后布局里再出现非法控件会直接构建失败',
                '★ 修复「之前能用的 JS 书源突然不见了」：以前 JS 插件加载失败是静默吞掉的，失败了什么都不说，只看到源没了。现在失败会记录原因，并新增「源加载诊断」页（关于页 → 问题诊断），一眼看清每个插件包的目录是否还在、加载出几个源、失败报什么错',
                '说明：本版换了签名证书，必须先卸载旧版才能装，而卸载会清空本机数据 —— JS 插件包就存在应用私有目录里。所以重装后需要重新导入一次 JS 插件包，诊断页会明确告诉你属于哪种情况',
                '★ 给闪退装上「黑匣子」：新增崩溃日志记录，任何闪退都会把完整堆栈 + 版本号 + 机型写到本机。下次闪退后打开「关于 → 崩溃日志」截图发我即可精确定位（本版只加记录能力，没有盲改业务逻辑）'
            ],
            adds: [
                '桌面小组件改成系统通知播放器的样子：封面改成方形圆角并居左，书名一行大字、下面一行灰色小字显示「主播 · 集数」，三个控制键去掉背板、变成三枚均匀分布的白色图标，卡片换成 26dp 圆角的中性半透明玻璃底',
                '小组件进度条现在可以调进度了：点进度条上想去的那个位置就跳过去（分 20 格，每格 5%，一章 20~60 分钟的话一格约 1~3 分钟）。说明一下，安卓的桌面小组件系统不允许放可拖动的滑条，所以用的是「点哪跳哪」，这也是系统自带音乐控件在小组件里的常见做法',
                '进度条左右分别显示「当前时间」和「总时长」，原来挤在一行里的 12:22 / 38:00 拆开了'
            ]
        },
        {
            ver: '3.7.14',
            date: '2025-10-11',
            fixes: [
                '修复书源管理里「长按」和「选择」互相打架的问题 —— 现在长按只干一件事：在按住的位置弹出菜单。行内勾选框、多选吸顶条、批量删除按钮全部去掉，短按也是弹同一套菜单，误触不再切到多选态',
                '修复「测试连通性」结果不可信的问题：Python 源以前走的是给 jar 插件兜底的分支，会拿一个根本不存在的 BASE 字段拼出 example.com 去请求，结论永远是「无响应」。现在 Python 源单独处理，直接跑一次真实搜索来判定连通；只支持浏览的源改为检测分类页能否拿到内容',
                '修复桌面小组件「点按钮不能控制、点哪都直接跳回 App」：整块面板的根布局上挂了「打开 App」的点击区，把三个控制按钮全吞了。现在只有封面和信息区可点开 App，三个按钮各自独立投递给播放服务',
                '修复小组件按钮在 App 进程被回收后点了没反应：此时播放器还没初始化，命令会被静默丢弃。现在会退化成把 App 拉起来（直接进播放页），至少不是「点了毫无动静」',
                '修复小组件不显示封面：封面是异步下载的，而小组件状态在开播那一刻就同步了，那时图还没下回来，于是永远显示占位音符。现在封面下载完成后会主动补推一次',
                '修复小组件在自动连播时停在上集信息：切书 / 切集只在「播放状态变化」时同步，而自动播下一集时播放状态一直是「播放中」，不触发同步',
                '小组件控制按钮改用直接投递播放服务的方式，避开「从后台广播启动前台服务」被系统限制的问题'
            ],
            adds: [
                '桌面小组件换成半透明「液态玻璃」外观：深色半透明底 + 顶部高光渐变 + 1dp 细白描边 + 24dp 大圆角，壁纸会从面板下透出来，观感贴近 iQOO / OriginOS 的液态玻璃',
                '小组件封面放大到 56×76，带细边和圆角，在玻璃面板上像一张小卡片',
                '小组件书名和播放信息过长时改为跑马灯横向滚动，不再被截断成「三国演…」',
                '小组件文字加投影，在浅色壁纸上也读得清'
            ]
        },
        {
            ver: '3.7.13',
            date: '2025-10-11',
            fixes: [
                '修复书源管理里点「删除此源」总是提示「删除失败」：单源删除一直调的是只认 JSON 配置的老接口，对 Python 源和插件源完全无效（native 直接返回失败）。现在改成按源类型自动分派的删除接口，JSON / Python / 插件源都能删掉；失败时也会按类型给出准确原因（内置源只能停用、插件源请用「卸载插件源」、文件被占用请重试）',
                '修复点击单个书源的「测试连通性」没有任何反应：测试结果原本写进一个只存在于底部菜单里的元素，菜单一关元素就没了，用户什么都看不到。现在列表行内直接显示「测试中… / ✔ 正常 / ✘ 失败」',
                '修复播放失败提示互相打架：native 和前端会各弹一句「取链失败」，两句叠在同一位置，用户只看到半截。现在统一由前端展示，且把播放器底层报错原因一并带上',
                '统一下拉菜单视觉：选集页的「列数 / 区间」和播放页的「更多」以前各有一套自己的气泡样式，现在全部统一成与历史/收藏长按菜单同一套（同一份样式、同一个弹出动画、同一套贴边收敛）'
            ],
            adds: [
                '书源管理改为长按交互：长按任意一行即在按住的位置弹出菜单（启停 / 测试连通性 / 重命名 / 删除），与历史和收藏页的手感完全一致（v3.7.14 起不再顺带进入多选）',
                '测试连通性结束后弹出结果面板，显示源名、结论与失败原因，带「关闭」按钮，不再是一闪而过的提示',
                '播放失败时弹出面板，说明是哪一集、哪个源、播放器报了什么，并直接提供「换下一集试试」「换一个书源」两个下一步动作，不用自己摸索'
            ]
        },
        {
            ver: '3.7.12',
            date: '2025-10-11',
            fixes: [
                '修复收藏页「图炸了」：部分书源返回的封面是 800/1200px 的大图，会把书架网格的列撑到整行宽，一张图占满一屏。现在列宽改为 minmax(0,1fr)（不受内容撑开），封面改用 padding-top 撑比例并绝对定位裁切，图片再大也固定在 3 列小格子里；同时兼容了不支持 aspect-ratio 的旧 WebView',
                '赞赏页收款码容器同步改用同一兼容写法（1:1 比例不受图片影响）'
            ],
            adds: []
        },
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
        // ★ v3.9.38：首页显示模式 —— 源列表模式冷启动先进「选择页」
        App._homePick = (App._homeMode === 'list');
        paintHomeModeSeg();
        // 悬浮球常驻：先按当前状态摆好（没内容时是灰球）
        syncBall();
        // ★ v3.9.37：软件内悬浮球支持拖动 + 位置记忆
        initBallDrag();
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
