/* 枫兮影视 v2.23 —— 海报墙 + 直播 + 短剧 + 源管理 + 飞牛 NAS 配置。纯 ES5。 */
var App = (function () {
    var $ = TS.$, esc = TS.esc;
    var TITLES = {
        pageHome: '枫兮', pageLive: '直播', pageShort: '短剧',
        pageDetail: '影片详情',
        pageSettings: '我的', pageWeb: '飞牛影视网页',
        pageSrcMgmt: '源管理', pageSrcCat: '源',
        pageSearch: '搜索', pageHistory: '观看历史'
    };
    var TABS = { pageHome: 0, pageLive: 1, pageShort: 2, pageSettings: 3 };
    var stack = [], cur = 'pageHome';
    var cfg = {}, libs = [], curMdb = '', curItem = null;

    function each(arr, fn) { Array.prototype.slice.call(arr, 0).forEach(fn); }

    function show(id, push) {
        if (push && id !== cur) stack.push(cur);
        each(document.querySelectorAll('.page'), function (p) { p.classList.add('hide'); });
        var el = $(id);
        if (!el) return;
        el.classList.remove('hide');
        cur = id;
        $('navTitle').textContent = TITLES[id] || '枫兮';
        // v2.16：用明确的 block/none（而非 ''），不依赖 CSS 默认值。
        // 两个顶栏下拉严格互斥，避免 boot 未初始化时挤成一团、盖住标题。
        var sel = $('homeSrcsel');
        if (sel) sel.style.display = (id === 'pageHome') ? 'block' : 'none';
        closeAllSrcMenus();
        var ls = document.querySelector('.livesel');
        if (ls) ls.style.display = (id === 'pageLive') ? 'block' : 'none';
        // v2.20.1：用 display:none 而非 visibility:hidden——后者仍占 34px 布局位，
        // 导致首页源下拉到不了最左边
        $('navBack').style.display = (id === 'pageHome' || id === 'pageSettings'
            || id === 'pageLive' || id === 'pageShort'
            || id === 'pageSrcMgmt') ? 'none' : 'block';
        each(document.querySelectorAll('.tabitem'), function (t) {
            t.classList.toggle('on', t.getAttribute('data-tab') === id);
        });
        // v2.24：切回首页时海报墙是空的就补一次加载。
        // v2.23 还额外要求「空态处于隐藏」，结果加完源再返回首页仍卡在
        // 「立即添加订阅源」不刷新；已有内容时不重复请求，避免丢滚动位置。
        if (id === 'pageHome' && !$('shelf').innerHTML) loadHome();
        // 切到直播/短剧页时刷新内容
        if (id === 'pageLive') { renderLives(); ensureLiveLoaded(); }
        if (id === 'pageShort') loadShort();
        if (id === 'pageHistory') TS.call('getHistory');
    }

    function go(id) { show(id, true); }
    function back() {
        // 返回键优先关闭打开着的弹窗（源选择 / 直播源 / 线路选择）
        var open = document.querySelector('.ovl:not(.hide)');
        if (open) { open.classList.add('hide'); return true; }
        if (stack.length) { show(stack.pop(), false); return true; }
        if (cur !== 'pageHome') { show('pageHome', false); return true; }
        return false;
    }

    // ==================== 配置 ====================

    function readCfg() {
        try { cfg = JSON.parse(TS.call('getConfig') || '{}'); }
        catch (e) { cfg = {}; }
        applyTheme(cfg.theme);
        return cfg;
    }

    function fillCfg() {
        readCfg();
        var av = document.getElementById('appVer');
        if (av && cfg.version) av.textContent = 'v' + cfg.version + '（versionCode ' + (cfg.versionCode || '') + '）';
        updateStatus();
        fillSettings();
        applyColumns();
        renderHistory();
        try { loadSources(); } catch (e) {}
    }

    // ==================== 设置中心（分组行式） ====================

    var RATIOS = ['适配', '16:9', '4:3', '填充'];
    var LP_SPEEDS = [2, 2.5, 3];

    function setTgl(id, on) { $(id).classList.toggle('on', !!on); }

    function fillSettings() {
        $('svRender').textContent = cfg.textureRender ? 'TextureView' : 'SurfaceView';
        $('svDecode').textContent = cfg.preferSoft ? '软解码' : '优先硬解';
        $('svLpSpeed').textContent = (cfg.longPressSpeed || 3) + 'x';
        $('svRatio').textContent = RATIOS[cfg.defaultRatio || 0] || '适配';
        $('svColumns').textContent = (cfg.homeColumns || 2) + ' 列';
        $('svTheme').textContent = cfg.theme === 'light' ? '亮色' : cfg.theme === 'dark' ? '暗色' : '跟随系统';
        setTgl('tvAutoNext', cfg.autoNext !== false);
        setTgl('tvBgPlay', !!cfg.backgroundPlay);
    }

    /** v2.11：把主题设置写到 <html data-theme> 上（不写 = 跟随系统）。 */
    function applyTheme(t) {
        if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t);
        else document.documentElement.removeAttribute('data-theme');
    }

    /** 设置变更立即落盘（以 cfg 为底合并 patch，不动输入框）。 */
    function persist(patch) {
        var o = {};
        for (var k in cfg) o[k] = cfg[k];
        for (var k2 in patch) o[k2] = patch[k2];
        try { TS.call('saveConfig', JSON.stringify(o)); } catch (e) {}
        readCfg();
        applyTheme(cfg.theme);
        fillSettings();
        applyColumns();
    }

    // v2.22：车机浮窗态（<480px）。applyColumns 在 car 态忽略配置列数，
    // 按宽度自动裁决，避免设置页残留的内联 3 列压过 car.css。
    function applyColumns() {
        var n;
        if (isCarMode()) {
            n = window.innerWidth < 420 ? 1 : 2;
        } else {
            n = parseInt(cfg.homeColumns, 10) || 2;
        }
        // v2.23：四个 shelf（首页 / 短剧 / 搜索 / 历史）全部生效，原来只有首页生效。
        each(document.querySelectorAll('.shelf'), function (sh) {
            sh.style.gridTemplateColumns = 'repeat(' + n + ',minmax(0,1fr))';
        });
    }

    function isCarMode() {
        return document.body.classList.contains('car');
    }

    /** v2.22：按浮窗宽度进出车机卡片态（Deepal+ 浮窗 resize 不重建 Activity，
     *  WebView 触发 window.onresize → 自动切换布局）。 */
    function applyCarMode() {
        var car = window.innerWidth < 480;
        document.body.classList.toggle('car', car);
        applyColumns();
    }

    var carResizeTimer = null;
    window.addEventListener('resize', function () {
        if (carResizeTimer) return;
        carResizeTimer = setTimeout(function () {
            carResizeTimer = null;
            applyCarMode();
        }, 200);
    });

    function bindSettings() {
        $('srRender').addEventListener('click', function () { persist({ textureRender: !cfg.textureRender }); });
        $('srDecode').addEventListener('click', function () { persist({ preferSoft: !cfg.preferSoft }); });
        $('srLpSpeed').addEventListener('click', function () {
            var cur = cfg.longPressSpeed || 3, i = LP_SPEEDS.indexOf(cur);
            persist({ longPressSpeed: LP_SPEEDS[(i + 1 + LP_SPEEDS.length) % LP_SPEEDS.length] });
        });
        $('srRatio').addEventListener('click', function () {
            persist({ defaultRatio: ((cfg.defaultRatio || 0) + 1) % RATIOS.length });
        });
        $('srAutoNext').addEventListener('click', function () { persist({ autoNext: cfg.autoNext === false }); });
        $('srBgPlay').addEventListener('click', function () { persist({ backgroundPlay: !cfg.backgroundPlay }); });
        $('srColumns').addEventListener('click', function () {
            persist({ homeColumns: (parseInt(cfg.homeColumns, 10) || 2) >= 3 ? 1 : (parseInt(cfg.homeColumns, 10) || 2) + 1 });
        });
        // v2.11：主题模式 循环切换 跟随系统 → 亮色 → 暗色
        $('srTheme').addEventListener('click', function () {
            var order = ['auto', 'light', 'dark'];
            var i = order.indexOf(cfg.theme || 'auto');
            if (i < 0) i = 0;
            var t = order[(i + 1) % order.length];
            persist({ theme: t });
            TS.toast('主题：' + (t === 'light' ? '亮色' : t === 'dark' ? '暗色' : '跟随系统'));
        });
        $('srHistory').addEventListener('click', function () {
            TS.call('clearHistory');
            TS.toast('观看历史已清空');
        });
    }

    // ==================== 观看历史（继续观看） ====================

    function renderHistory() {
        var box = $('hisw');
        if (!box) return;
        var list = [];
        try { list = (cfg.history || []).slice(0, 12); } catch (e) {}
        if (!list.length) { box.innerHTML = ''; return; }
        var html = '';
        for (var i = 0; i < list.length; i++) {
            var h = list[i];
            html += '<div class="hcard" data-guid="' + esc(h.guid) + '" data-title="' + esc(h.title) + '">'
                + '<div class="ph"></div><div class="hn">' + esc(h.title || '未命名') + '</div></div>';
        }
        box.innerHTML = html;
        each(box.querySelectorAll('.hcard'), function (el) {
            el.addEventListener('click', function () {
                openDetail(el.getAttribute('data-guid'), el.getAttribute('data-title'));
            });
        });
    }

    function updateStatus() {
        var s = $('navStatus');
        if (!s) return;   // v2.6 顶栏状态字样已去掉
        var t = '未连接', on = false;
        if (cfg.base) { t = cfg.hasCredential ? '已登录' : '未登录'; on = !!cfg.hasCredential; }
        s.textContent = t;
        s.classList.toggle('on', on);
    }

    function saveCfg() {
        TS.toast('NAS 服务器与登录设置已移到「设置 → 源管理 → NAS 源」里');
    }

    // ==================== 首页 / 海报墙 ====================

    function showEmpty(msg) {
        $('shelf').innerHTML = '';
        $('mdbChips').innerHTML = '';
        $('homeHint').textContent = msg;
        $('emptyText').textContent = msg;
        $('homeEmpty').classList.remove('hide');
    }

    var homeTimer = null;
    function loadHome() {
        readCfg();
        // v2.15：旧版要求 cfg.base（NAS 地址）才加载，用 CMS / 爬虫源时明明有源
        // 却直接显示「还没有可用的 NAS 源」，loadLibraries 根本不会被调用
        var sr;
        try { sr = JSON.parse(TS.call('listSources') || '{}'); } catch (e) { sr = {}; }
        var cname = '';
        for (var i = 0; i < (sr.list || []).length; i++)
            if (sr.list[i].id === sr.cur) cname = sr.list[i].name || '当前源';
        if (!sr.cur) {
            showEmpty('还没有选择数据源\n请点顶栏下拉选择，或到「设置 → 源管理」导入源');
            return;
        }
        $('homeEmpty').classList.add('hide');
        $('homeHint').textContent = '正在读取 ' + (cname || '当前源') + ' 的媒体库…';
        $('shelf').innerHTML = '';
        // v2.16：慢接口（豆瓣推荐 / 自愈切源）可能十几秒无响应。给一个超时兜底，
        // 别让用户对着空白一直等、以为卡死。onLibraries 回来时会 clearTimeout。
        if (homeTimer) clearTimeout(homeTimer);
        homeTimer = setTimeout(function () {
            if ($('shelf').innerHTML || !$('homeEmpty').classList.contains('hide')) return;
            $('homeHint').textContent = '这个源响应较慢或连不上，可点右上角下拉换一个源试试';
        }, 12000);
        TS.call('loadLibraries');
    }

    function card(d) {
        // v2.16：封面下面垫一层「首字占位」，图片加载失败时只隐藏 <img>，
        // 露出占位字，卡片不再整片空白（外部图床防盗链时的兜底）。
        var ph = '<div class="bk-ph">' + esc((d.title || '?').charAt(0)) + '</div>';
        var img = d.poster
            ? '<img class="bk-cover" loading="lazy" src="' + esc(d.poster) + '" onerror="TS.imgFail(this)">'
            : '';
        return '<div class="bk" data-guid="' + esc(d.guid) + '" data-title="' + esc(d.title)
            + '" data-kind="' + esc(d.kind || '') + '">'
            + '<div class="bk-cv">' + ph + img + '</div>'
            + '<div class="bk-info"><div class="bk-name">' + esc(d.title) + '</div>'
            + '<div class="bk-sub">' + esc(d.sub || d.type || '')
            + (d.src ? '<span class="bk-src">来源：' + esc(d.src) + '</span>' : '')
            + '</div></div></div>';
    }

    function renderShelf(list) {
        $('homeEmpty').classList.add('hide');
        if (!list || !list.length) {
            $('shelf').innerHTML = '';
            $('homeHint').textContent = '这个媒体库暂时没有内容';
            $('emptyText').textContent = '读到了媒体库，但没有影片。可能是接口字段不兼容，可到「设置 → 内嵌网页浏览」降级查看。';
            $('homeEmpty').classList.remove('hide');
            return;
        }
        $('homeHint').textContent = '共 ' + list.length + ' 部 · 点封面播放';
        $('shelf').innerHTML = list.map(card).join('');
        each($('shelf').querySelectorAll('.bk'), function (el) {
            el.addEventListener('click', function () {
                openDetail(el.getAttribute('data-guid'), el.getAttribute('data-title'));
            });
        });
    }

    window.onLibraries = function (r) {
        if (homeTimer) { clearTimeout(homeTimer); homeTimer = null; }
        if (!r.ok) {
            showEmpty('读取媒体库失败：' + (r.error || '未知原因')
                + '\n可点右上角下拉切换线路，或到「设置 → 内嵌网页浏览」降级查看。');
            return;
        }
        libs = r.list || [];
        if (!libs.length) { showEmpty('没有读到媒体库（接口返回空）'); return; }
        $('mdbChips').innerHTML = libs.map(function (l, i) {
            return '<div class="chip' + (i === 0 ? ' on' : '') + '" data-i="' + i + '">'
                + esc(l.title || l.guid) + '</div>';
        }).join('');
        each($('mdbChips').querySelectorAll('.chip'), function (el) {
            el.addEventListener('click', function () {
                each($('mdbChips').querySelectorAll('.chip'), function (x) { x.classList.remove('on'); });
                el.classList.add('on');
                curMdb = libs[parseInt(el.getAttribute('data-i'), 10)].guid;
                $('homeHint').textContent = '加载中…';
                $('shelf').innerHTML = '';
                TS.call('loadItems', curMdb, 1);
            });
        });
        curMdb = libs[0].guid;
        TS.call('loadItems', curMdb, 1);
    };

    window.onItems = function (r) {
        if (!r.ok) {
            var msg = '读取影片失败：' + (r.error || '未知原因');
            if (r.raw) msg += '\n服务器响应：' + r.raw;
            $('homeHint').textContent = msg;
            $('emptyText').textContent = '把这条报错截图发回即可定位；也可到「设置 → 内嵌网页浏览」降级查看。';
            $('homeEmpty').classList.remove('hide');
            return;
        }
        renderShelf(r.list);
    };

    // ==================== 详情 / 选集 ====================

    function openDetail(guid, title) {
        if (!guid) { TS.toast('该条目缺少 ID'); return; }
        curItem = { guid: guid, title: title };
        $('dtTitle').textContent = title;
        $('dtSub').textContent = '';
        $('dtDesc').textContent = '加载中…';
        $('dtDesc').style.maxHeight = '';
        $('dtPoster').style.visibility = 'hidden';
        $('dtPoster').src = '';
        $('epCount').textContent = '';
        $('epList').innerHTML = '';
        go('pageDetail');
        TS.call('loadDetail', guid);
        TS.call('loadEpisodes', guid);
    }

    window.onDetail = function (r) {
        if (!r.ok) { $('dtDesc').textContent = '详情读取失败（不影响播放）：' + (r.error || ''); return; }
        var d = (r.list && r.list[0]) || {};
        if (d.poster) {
            $('dtPoster').src = d.poster;
            $('dtPoster').style.visibility = 'visible';
            $('dtPoster').onerror = function () { TS.imgFail(this); };
        }
        if (d.title) $('dtTitle').textContent = d.title;
        // meta 行：类型（主题色）+ 补充信息（灰色），参考主流影视 App
        var meta = '';
        if (d.type) meta += '<span>' + esc(d.type) + '</span>';
        if (d.sub) meta += (meta ? ' ' : '') + '<span class="dim">· ' + esc(d.sub) + '</span>';
        $('dtSub').innerHTML = meta;
        $('dtDesc').textContent = d.sub || '点「立即播放」开始观看，或在下方选集里挑选一集。';
    };

    // v2.23：EasyBox 对齐 —— 选集正序 / 倒序切换（只重排，不重新请求）
    var epReverse = false, epLast = [];
    function renderEpList() {
        var list = epLast.slice();
        if (epReverse) list.reverse();
        var btn = $('epOrderBtn');
        if (btn) btn.textContent = epReverse ? '倒序 ⇅' : '正序 ⇅';
        $('epCount').textContent = list.length ? '共 ' + list.length + ' 集' : '';
        if (!list.length) {
            $('epList').innerHTML = '<div class="tip">未读到选集数据，可直接点上方「立即播放」。</div>';
            return;
        }
        $('epList').innerHTML = list.map(function (e, i) {
            var n = e.title || ('第 ' + (i + 1) + ' 集');
            return '<div class="ep2" data-guid="' + esc(e.guid) + '" data-title="'
                + esc(n) + '">' + esc(n) + '</div>';
        }).join('');
        each($('epList').querySelectorAll('.ep2'), function (el) {
            el.addEventListener('click', function () {
                TS.call('openVideo', el.getAttribute('data-guid'), el.getAttribute('data-title'), curItem ? curItem.guid : '');
            });
        });
    }
    window.onEpisodes = function (r) { epLast = r.list || []; renderEpList(); };

    // ==================== 搜索 / 观看历史页（v2.19，v2.20.1 聚合增强） ====================

    // 聚合搜索状态：全部结果 + 各源统计 + 当前筛选
    var searchAll = [], searchSrc = [], searchFilter = '';

    function doSearch() {
        var kw = $('searchInput').value.trim();
        if (!kw) { TS.toast('请输入搜索关键词'); return; }
        $('searchHint').textContent = '正在并行搜索…';
        $('searchEmpty').classList.add('hide');
        $('searchChips').innerHTML = '';
        $('searchShelf').innerHTML = '';
        searchAll = []; searchSrc = []; searchFilter = '';
        TS.call('search', kw);
    }

    function renderSearchChips() {
        var el = $('searchChips');
        if (!searchSrc.length) { el.innerHTML = ''; return; }
        var h = '<div class="tabitem' + (searchFilter === '' ? ' on' : '')
            + '" data-src="">全部显示 · ' + searchAll.length + '</div>';
        for (var i = 0; i < searchSrc.length; i++) {
            var s = searchSrc[i];
            if (!s.count) continue;
            h += '<div class="tabitem' + (searchFilter === s.name ? ' on' : '')
                + '" data-src="' + esc(s.name) + '">' + esc(s.name)
                + ' · ' + s.count + '</div>';
        }
        el.innerHTML = h;
        each(el.querySelectorAll('.tabitem'), function (t) {
            t.addEventListener('click', function () {
                searchFilter = t.getAttribute('data-src') || '';
                renderSearchList();
                renderSearchChips();
            });
        });
    }

    function renderSearchList() {
        var list = searchFilter
            ? searchAll.filter(function (d) { return d.src === searchFilter; })
            : searchAll;
        $('searchShelf').innerHTML = list.map(card).join('');
        each($('searchShelf').querySelectorAll('.bk'), function (el) {
            el.addEventListener('click', function () {
                openDetail(el.getAttribute('data-guid'), el.getAttribute('data-title'));
            });
        });
    }

    window.onSearch = function (r) {
        if (!r) return;
        // 渐进聚合：每完成一个源推一次
        if (r.progress && r.sources) {
            searchSrc = r.sources || [];
            var kw = r.kw || '';
            // Bridge 每次推的是累计 list，直接覆盖
            searchAll = r.list || [];
            $('searchHint').textContent = '搜索(' + r.done + '/' + r.total + ')「' + kw + '」已得 '
                + searchAll.length + ' 部';
            renderSearchChips();
            renderSearchList();
            if (r.done >= r.total) {
                if (searchAll.length) {
                    $('searchHint').textContent = '搜索完成 · 共 ' + searchAll.length + ' 部（'
                        + searchSrc.filter(function (s) { return s.count; }).length + ' 个源命中）';
                } else {
                    var errs = searchSrc.filter(function (s) { return s.error; })
                        .map(function (s) { return s.name + '：' + s.error; }).join('\n');
                    $('searchHint').textContent = '';
                    $('searchEmptyText').textContent =
                        (errs ? '全部源都没有结果\n' + errs : '没有搜到内容')
                        + '\n搜索并行查询所有资源站 / 爬虫源';
                    $('searchEmpty').classList.remove('hide');
                }
            }
            return;
        }
        // 兼容旧的整包返回
        var list = (r && r.list) || [];
        if (r && r.ok && list.length) {
            $('searchHint').textContent = '搜到 ' + list.length + ' 部';
            $('searchShelf').innerHTML = list.map(card).join('');
            each($('searchShelf').querySelectorAll('.bk'), function (el) {
                el.addEventListener('click', function () {
                    openDetail(el.getAttribute('data-guid'), el.getAttribute('data-title'));
                });
            });
            return;
        }
        $('searchHint').textContent = '';
        $('searchEmptyText').textContent =
            (r && r.error ? r.error : '没有搜到内容')
            + '\n搜索使用当前数据源，可回首页顶部换源后重试';
        $('searchEmpty').classList.remove('hide');
    };

    window.onHistory = function (r) {
        var list = Array.isArray(r) ? r : ((r && r.list) || []);
        $('histShelf').innerHTML = list.map(card).join('');
        if (!list.length) {
            $('histShelf').innerHTML = '';
            $('histEmpty').classList.remove('hide');
        } else {
            $('histEmpty').classList.add('hide');
        }
        each($('histShelf').querySelectorAll('.bk'), function (el) {
            el.addEventListener('click', function () {
                openDetail(el.getAttribute('data-guid'), el.getAttribute('data-title'));
            });
        });
    };

    // ==================== 数据源选择弹窗（v2.13：居中弹窗 + 搜索，参考成熟影视 App） ====================

    function renderHomeSrc() {
        var name = '选择源';
        for (var i = 0; i < srcs.length; i++) {
            if (srcs[i].id === curSrc) name = srcs[i].name;
        }
        if ($('homeSrcName')) $('homeSrcName').textContent = name;
        if ($('mgrSrcName')) $('mgrSrcName').textContent = name;
    }

    /** 弹出影视源选择弹窗（首页 / 源管理共用）。nas/cms/js/spider 全部可选。 */
    function openSrcPick(title) {
        loadSources();
        $('srcPickTitle').textContent = title || '请选择首页数据源';
        $('srcPickSearch').value = '';
        fillSrcPick('');
        $('srcPickOvl').classList.remove('hide');
    }

    function fillSrcPick(kw) {
        var box = $('srcPickList');
        var k = (kw || '').toLowerCase();
        var list = [];
        for (var i = 0; i < srcs.length; i++) {
            var t = srcs[i].type === 'fnos' ? 'nas' : srcs[i].type;
            if (t !== 'nas' && t !== 'cms' && t !== 'js' && t !== 'spider') continue;
            if (k && (srcs[i].name || '').toLowerCase().indexOf(k) < 0
                && (srcs[i].api || '').toLowerCase().indexOf(k) < 0) continue;
            list.push(srcs[i]);
        }
        box.innerHTML = list.length ? list.map(function (s) {
            var t = s.type === 'fnos' ? 'nas' : s.type;
            return '<div class="pk-btn' + (s.id === curSrc ? ' on' : '') + '" data-id="'
                + esc(s.id) + '">' + esc(s.name)
                + '<div class="pk-d">' + esc((CATS[t] || t) + (s.api ? ' · ' + s.api : '')) + '</div></div>';
        }).join('') : '<div class="tip" style="text-align:center;padding:14px">'
            + (k ? '没有匹配「' + esc(kw) + '」的源' : '还没有源，到 设置 → 源管理 添加') + '</div>';
        each(box.querySelectorAll('.pk-btn'), function (el) {
            el.addEventListener('click', function () {
                var id = el.getAttribute('data-id');
                var r;
                try { r = JSON.parse(TS.call('selectSource', id) || '{}'); } catch (e) { r = {}; }
                if (!r.ok) { TS.toast('切换失败：' + (r.error || '')); return; }
                $('srcPickOvl').classList.add('hide');
                TS.toast('已切换到 ' + (r.name || '源'));
                cfg = readCfg();
                loadSources();
                renderHomeSrc();
                if (cur === 'pageHome') loadHome();
            });
        });
    }

    function openLivePick() {
        // v2.15：必须先摘掉 hide 再渲染 —— renderLives 里遇到 hide 会直接 return，
        // 顺序反了弹窗里的直播源列表就永远是空的
        $('livePickOvl').classList.remove('hide');
        renderLives();
    }

    function closeLivePick() { $('livePickOvl').classList.add('hide'); }

    function closeAllSrcMenus() {
        // v2.23：四个弹层全收（原来切 tab 时线路选择 / 悬浮窗引导会残留盖住新页面）
        each(document.querySelectorAll('#srcPickOvl,#livePickOvl,#linePickOvl,#floatPermOvl'), function (m) {
            m.classList.add('hide');
        });
    }

    // ==================== 源管理（v2.4：分类 → 源 → 设置 三层） ====================

    var CATS = { nas: 'NAS 源', cms: '资源站', js: '短剧源', box: 'TVBox 源', spider: '爬虫源' };
    var srcs = [], curSrc = '', srcCat = '', expandedId = '';

    function loadSources() {
        var r;
        try { r = JSON.parse(TS.call('listSources') || '{}'); } catch (e) { r = {}; }
        srcs = r.list || [];
        curSrc = r.cur || '';
        // 设置页摘要：当前源
        var cur = null;
        for (var i = 0; i < srcs.length; i++) if (srcs[i].id === curSrc) cur = srcs[i];
        $('svSource').textContent = cur ? (cur.name + '（' + (CATS[cur.type] || cur.type) + '）')
            : '未添加源，点「进入」添加';
        renderHomeSrc();
        return r;
    }

    function renderSrcMgmt() {
        loadSources();
        var counts = { nas: 0, cms: 0, js: 0, box: 0, spider: 0 };
        for (var i = 0; i < srcs.length; i++) {
            var t = srcs[i].type === 'fnos' ? 'nas' : srcs[i].type;
            if (counts[t] !== undefined) counts[t]++;
        }
        var box = $('srcCatList');
        var desc = {
            nas: '服务器地址、账号密码等设置都在源里展开配置',
            cms: '苹果 CMS V10 资源站，填接口地址即可',
            js: 'JS 脚本源（阅读 / prelude 式），自动接入短剧栏',
            box: 'TVBox 配置 / 仓库接口，点源选择线路后对接首页与直播',
            spider: 'TVBox 爬虫源（v2.13 起内置 spider 运行时，点行设为当前即可播放）'
        };
        var order = ['nas', 'cms', 'spider', 'js', 'box'];
        var html = '';
        order.forEach(function (cat) {
            html += '<div class="srow" data-cat="' + cat + '"><div class="s-l">'
                + '<div class="s-t">' + CATS[cat] + '</div><div class="s-d">' + desc[cat] + '</div></div>'
                + '<div class="s-v">' + counts[cat] + ' 个 ›</div></div>';
        });
        box.innerHTML = html;
        each(box.querySelectorAll('.srow'), function (row) {
            row.addEventListener('click', function () { openSrcCat(row.getAttribute('data-cat')); });
        });
    }

    function openSrcCat(cat) {
        srcCat = cat;
        expandedId = '';
        $('srcCatTitle').textContent = CATS[cat];
        $('srcCatHint').textContent = cat === 'nas' ? '点源展开设置（地址 / 登录）'
            : cat === 'js' ? '点源查看，导入按钮在上一层'
            : cat === 'box' ? '点 TVBox 源弹出「选择线路」，选定后资源站进首页、直播源进直播'
            : '点源展开设置';
        go('pageSrcCat');
        renderSrcCat();
    }

    function renderSrcCat() {
        loadSources();
        var list = [];
        for (var i = 0; i < srcs.length; i++) {
            var t = srcs[i].type === 'fnos' ? 'nas' : srcs[i].type;
            if (t === srcCat) list.push(srcs[i]);
        }
        var box = $('srcList');
        var html = '';
        for (var j = 0; j < list.length; j++) {
            var s = list[j];
            var d;
            if (srcCat === 'js') d = '脚本源 · 自动接入短剧栏';
            else if (srcCat === 'box') d = (s.selectedLine ? '当前线路：' + s.selectedLine : '未选线路')
                + ' · ' + (s.api || '');
            else if (srcCat === 'spider') d = '爬虫源 · 点行设为当前（内置 spider 运行时播放） · ' + (s.api || '');
            else d = (s.api || '未配置') + (s.user ? ' · ' + s.user : '');
            var act = srcCat === 'box' ? '选线路' : (srcCat === 'spider' ? '设为当前' : '设置');
            html += '<div class="srow" data-id="' + esc(s.id) + '"><div class="s-l">'
                + '<div class="s-t">' + esc(s.name) + '</div><div class="s-d">' + esc(d) + '</div></div>'
                + (s.id === curSrc ? '<div class="s-v" style="color:#3ecb8f">当前</div>' : '')
                + '<div class="s-v link">' + (srcCat === 'spider' ? act : (expandedId === s.id ? '收起' : act)) + '</div>'
                + '<div class="s-v del" data-del="' + esc(s.id) + '" style="color:#ff6b6b;padding-left:10px">×</div></div>';
            if (expandedId === s.id && srcCat !== 'box' && srcCat !== 'spider') html += srcEditor(s);
        }
        box.innerHTML = html || '<div class="tip" style="padding:10px 14px">该分类还没有源（TVBox 源在上一层「TVBox 配置解析」导入）</div>';
        // 添加入口（短剧源 / TVBox 源 / 爬虫源走导入）
        var add = $('srcAddCard');
        if (srcCat === 'js' || srcCat === 'box' || srcCat === 'spider') {
            add.classList.add('hide');
        } else {
            add.classList.remove('hide');
            $('srcAddTitle').textContent = srcCat === 'nas' ? '添加 NAS 源' : '添加资源站';
            $('srcAddBody').innerHTML = srcAddForm();
            $('btnAddSource').onclick = function () { addSource(); };
        }
        bindSrcRows(box);
    }

    /** 第三层：源设置展开区（NAS 含服务器地址/登录相关，全部从设置页搬进来）。 */
    function srcEditor(s) {
        var h = '<div class="card" style="margin:0 2px 10px"><div class="card-t">'
            + esc(s.name) + ' · 设置</div>';
        h += '<label class="lb">源名称</label><input class="inp" data-f="name" value="' + esc(s.name) + '">';
        if (s.type === 'nas' || s.type === 'fnos') {
            h += '<label class="lb">服务器地址</label><input class="inp" data-f="api" placeholder="http://192.168.x.x:5666 或 [IPv6]:5666" value="' + esc(s.api || '') + '">';
            h += '<label class="lb">用户名</label><input class="inp" data-f="user" value="' + esc(s.user || '') + '">';
            h += '<label class="lb">密码</label><input class="inp" type="password" data-f="pass" value="' + esc(s.pass || '') + '">';
            h += '<label class="row"><input type="checkbox" data-f="trustSsl"' + (s.trustSsl ? ' checked' : '') + '> 信任自签 HTTPS 证书</label>';
            h += '<div class="tip">NAS 源的服务器地址与登录相关设置都在这里。保存后点「重新登录」获取新令牌。</div>';
            h += '<div class="row" style="margin-top:8px"><button class="btn primary" data-act="save">保存</button>'
                + '<button class="btn" data-act="login">重新登录</button>'
                + '<button class="btn" data-act="use">设为当前</button></div>';
        } else if (s.type === 'cms') {
            h += '<label class="lb">接口地址</label><input class="inp" data-f="api" placeholder="https://…/api.php/provide/vod" value="' + esc(s.api || '') + '">';
            h += '<div class="row" style="margin-top:8px"><button class="btn primary" data-act="save">保存</button>'
                + '<button class="btn" data-act="use">设为当前</button></div>';
        } else {
            h += '<div class="tip">JS 脚本源：短剧栏自动使用本分类的源。如需更换，请到上一层的「导入源」。</div>';
        }
        h += '</div>';
        return h;
    }

    function bindSrcRows(box) {
        each(box.querySelectorAll('.srow'), function (row) {
            row.addEventListener('click', function (e) {
                var del = e.target && e.target.getAttribute ? e.target.getAttribute('data-del') : null;
                if (del) {
                    TS.call('delSource', del);
                    TS.toast('已删除');
                    renderSrcCat();
                    return;
                }
                var id = row.getAttribute('data-id');
                // TVBox 源：点行直接弹「选择线路」（参考 TVBox 订阅管理的交互）
                if (srcCat === 'box') {
                    for (var i = 0; i < srcs.length; i++) {
                        if (srcs[i].id === id) { openLinePicker(srcs[i]); return; }
                    }
                    return;
                }
                // 爬虫源（v2.13）：点击设为当前源，内置 spider 运行时直接播放
                if (srcCat === 'spider') {
                    var sr;
                    try { sr = JSON.parse(TS.call('selectSource', id) || '{}'); } catch (e2) { sr = {}; }
                    TS.toast(sr.ok ? '已切换到「' + (sr.name || '') + '」，回首页即可浏览'
                        : '切换失败：' + (sr.error || ''));
                    if (sr.ok) { renderSrcCat(); renderHomeSrc(); }
                    return;
                }
                if (expandedId === id) { expandedId = ''; renderSrcCat(); return; }
                expandedId = id;
                renderSrcCat();
                bindEditor(id);
            });
        });
    }

    // ==================== TVBox 源：选择线路弹层（v2.8） ====================

    /** 弹「选择线路」弹层（图1 TVBox 订阅交互）。boxSrc 为 box 类型的源对象。 */
    function openLinePicker(boxSrc) {
        var lines = [];
        try { lines = boxSrc.lines || []; } catch (e) {}
        var mask = $('linePickOvl');
        var listEl = $('linePickList');
        $('linePickTitle').textContent = boxSrc.name || 'TVBox 源';
        $('linePickSub').textContent = (boxSrc.api || '') + (boxSrc.selectedLine
            ? ' · 当前线路：' + boxSrc.selectedLine : '');
        if (!lines.length) {
            listEl.innerHTML = '<div class="tip" style="padding:12px">线路列表为空，请删除该源后重新导入 TVBox 配置。</div>';
        } else {
            listEl.innerHTML = lines.map(function (ln, i) {
                var on = boxSrc.selectedLine && boxSrc.selectedLine === (ln.name || '');
                return '<div class="smitem' + (on ? ' on' : '') + '" data-li="' + i
                    + '" style="background:var(--card);margin-bottom:8px;padding:12px;text-align:center;border-radius:9px">'
                    + '<div class="sm-n" style="font-size:15px">' + esc(ln.name || ('线路 ' + (i + 1))) + '</div></div>';
            }).join('');
            each(listEl.querySelectorAll('.smitem[data-li]'), function (el) {
                el.addEventListener('click', function () {
                    var ln = lines[parseInt(el.getAttribute('data-li'), 10)];
                    if (!ln) return;
                    listEl.innerHTML = '<div class="tip" style="padding:12px">正在解析线路「' + esc(ln.name) + '」…（中文域名线路较慢，请稍候）</div>';
                    // v2.10：异步解析，结果走 window.onBoxLineDone（同步调用会堵死前端）
                    TS.call('selectBoxLine', boxSrc.id, ln.url, ln.name || '');
                });
            });
        }
        mask.classList.remove('hide');
    }

    /** v2.10：selectBoxLine 异步结果 —— 刷新首页/直播下拉与源管理。 */
    window.onBoxLineDone = function (r) {
        if (!r || !r.ok) {
            var listEl = $('linePickList');
            if (listEl) listEl.innerHTML = '<div class="tip" style="padding:12px;color:#ff6b6b">解析失败：'
                + esc((r && r.error) || '未知原因') + '</div>';
            TS.toast('线路解析失败：' + ((r && r.error) || '未知原因'));
            return;
        }
        closeLinePicker();
        var msg = '线路「' + (r.lineName || '') + '」：添加 ' + (r.added || 0)
            + ' 个资源站、' + (r.spiderAdded || 0) + ' 个爬虫源、' + (r.liveAdded || 0) + ' 个直播源';
        if (r.skipped) msg += '，跳过 ' + r.skipped + ' 个无效源';
        if (r.firstName) msg += '；首页已选「' + r.firstName + '」';
        TS.toast(msg);
        loadSources();
        // 首页：当前源名与下拉列表刷新
        renderHomeSrc();
        if (cur === 'pageHome') loadHome();
        // 直播：下拉内容刷新（有直播源时自动选中第一个）
        renderLives();
        // v2.14：无论当前在哪个页都记下新线路的直播源；切到直播页时自动加载
        if (r.firstLive) {
            pendingLive = r.firstLive;
            if (cur === 'pageLive') ensureLiveLoaded();
        }
        // 源管理页当前视图刷新
        if (srcCat) renderSrcCat();
        if (cur === 'pageSrcMgmt') renderSrcMgmt();
    };

    function closeLinePicker() { $('linePickOvl').classList.add('hide'); }

    function bindEditor(id) {
        var card = $('srcList').querySelector('.card:last-child');
        if (!card) return;
        var inputs = card.querySelectorAll('[data-f]');
        var actBtns = card.querySelectorAll('[data-act]');
        each(actBtns, function (b) {
            b.addEventListener('click', function () {
                var patch = { id: id };
                each(inputs, function (inp) {
                    var f = inp.getAttribute('data-f');
                    patch[f] = inp.type === 'checkbox' ? inp.checked : inp.value;
                });
                var act = b.getAttribute('data-act');
                var r;
                try { r = JSON.parse(TS.call('updateSource', JSON.stringify(patch)) || '{}'); }
                catch (e) { r = {}; }
                if (r.ok) {
                    TS.toast('已保存');
                    if (act === 'login') {
                        TS.toast('正在登录…');
                        TS.call('loginSource', id);
                    } else if (act === 'use') {
                        var ur;
                        try { ur = JSON.parse(TS.call('selectSource', id) || '{}'); } catch (e2) { ur = {}; }
                        TS.toast(ur.ok ? '已切换到当前源' : ('切换失败：' + (ur.error || '')));
                        if (ur.ok) { cfg = readCfg(); loadSources(); }
                    }
                    renderSrcCat();
                } else TS.toast('保存失败：' + (r.error || ''));
            });
        });
    }

    /** 第二层添加表单。 */
    function srcAddForm() {
        var h = '<label class="lb">源名称</label><input id="naName" class="inp" placeholder="如：我的飞牛">';
        if (srcCat === 'nas') {
            h += '<label class="lb">服务器地址</label><input id="naApi" class="inp" placeholder="http://192.168.x.x:5666 或 [IPv6]:5666">';
            h += '<label class="lb">用户名</label><input id="naUser" class="inp">';
            h += '<label class="lb">密码</label><input id="naPass" class="inp" type="password">';
            h += '<label class="row"><input type="checkbox" id="naTrust" checked> 信任自签 HTTPS 证书</label>';
        } else {
            h += '<label class="lb">接口地址</label><input id="naApi" class="inp" placeholder="https://…/api.php/provide/vod">';
        }
        return h;
    }

    function addSource() {
        var api = $('naApi') ? $('naApi').value : '';
        if (!api && srcCat !== 'js') { TS.toast('请填接口地址'); return; }
        TS.toast('正在验证源…');
        var body = JSON.stringify({
            name: $('naName') ? $('naName').value : '',
            type: srcCat, api: api,
            user: $('naUser') ? $('naUser').value : '',
            pass: $('naPass') ? $('naPass').value : '',
            trustSsl: $('naTrust') ? $('naTrust').checked : true
        });
        // v2.23：NAS 源要跑账号密码登录（同步网络），改走异步桥，避免界面假死
        TS.call('addSourceAsync', body);
    }

    window.onAddSourceDone = function (r) {
        if (!r) return;
        if (r.ok) {
            TS.toast('源已添加');
            renderSrcCat();
            // v2.24：在首页直接添加源时立刻刷新海报墙，别停在空态
            if (cur === 'pageHome') loadHome();
        } else TS.toast('添加失败：' + (r.error || ''));
    };

    window.onSourceLogin = function (ok, msg) {
        TS.toast((ok ? '✓ ' : '✗ ') + msg);
        if (ok) { loadSources(); if (cur === 'pageHome') loadHome(); }
    };

    /** TVBox 异步解析完成（v2.8：仓库存为 TVBox 源并立刻弹「选择线路」）。 */
    window.onTvboxDone = function (r) {
        if (!r || !r.ok) {
            TS.toast('解析失败：' + (r && r.error || '未知原因'));
            return;
        }
        if (r.type === 'repo') {
            // 仓库：源已存为 TVBox 源，直接弹出线路选择（图1交互）
            loadSources();
            renderSrcMgmt();
            for (var i = 0; i < srcs.length; i++) {
                if (srcs[i].id === r.id) {
                    TS.toast('已导入多仓「' + (r.name || '') + '」：' + (r.lines || []).length + ' 条线路，请选择线路');
                    go('pageSrcCat');
                    srcCat = 'box';
                    $('srcCatTitle').textContent = CATS.box;
                    $('srcCatHint').textContent = '点 TVBox 源弹出「选择线路」，选定后资源站进首页、直播源进直播';
                    renderSrcCat();
                    openLinePicker(srcs[i]);
                    return;
                }
            }
            TS.toast('已导入 TVBox 仓库，到 源管理 → TVBox 源 选择线路');
            return;
        }
        var msg = '解析完成：添加 ' + (r.added || 0) + ' 个资源站、'
            + (r.spiderAdded || 0) + ' 个爬虫源、' + (r.liveAdded || 0) + ' 个直播源';
        if (r.skipped) msg += '，跳过 ' + r.skipped + ' 个无接口地址的站点';
        TS.toast(msg);
        loadSources();
        renderSrcMgmt();
    };

    // ==================== 直播（v2.3） ====================

    var liveGroups = [], liveCh = {}, curLiveSrc = '', pendingLive = '';

    /** v2.14：切到直播页时自动选中并拉取一个源（选完线路后不必再手动点一次）。 */
    function ensureLiveLoaded() {
        var r;
        try { r = JSON.parse(TS.call('listLives') || '{}'); } catch (e) { r = {}; }
        var list = r.list || [];
        if (!list.length) return;
        var target = null;
        if (pendingLive) {
            for (var i = 0; i < list.length; i++)
                if (list[i].id === pendingLive) { target = list[i]; break; }
        }
        pendingLive = '';
        if (!target) {
            if (curLiveSrc) return;      // 已有选中且没有新线路，不重复拉取
            target = list[0];
        }
        curLiveSrc = target.id;
        if ($('liveSrcName')) $('liveSrcName').textContent = target.name || '直播源';
        loadLiveUrl(target.url, target.id);
    }

    function renderLives() {
        var r;
        try { r = JSON.parse(TS.call('listLives') || '{}'); } catch (e) { r = {}; }
        var list = r.list || [];
        // 顶部按钮显示当前源名（v2.13：管理/选择全在居中弹窗里）
        var curName = '选择直播源';
        for (var n = 0; n < list.length; n++) if (list[n].id === curLiveSrc) curName = list[n].name || '直播源';
        if ($('liveSrcName')) $('liveSrcName').textContent = curName;
        // 弹窗没开着就不用画列表
        var box = $('livePickList');
        if (!box || $('livePickOvl').classList.contains('hide')) return;
        box.innerHTML = list.length ? list.map(function (s, i) {
            var ico = s.icon
                ? '<img class="sm-ico" src="' + esc(s.icon) + '" onerror="this.style.visibility=\'hidden\'">'
                : '<div class="sm-badge">' + esc((s.name || '源')[0]) + '</div>';
            return '<div class="pk-row' + (s.id === curLiveSrc ? ' on' : '') + '" data-i="' + i + '">'
                + ico
                + '<div style="flex:1;min-width:0"><div style="font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">'
                + esc(s.name) + '</div></div>'
                + '<div class="sm-del" data-delive="' + esc(s.id) + '" style="color:#ff6b6b;padding:4px 8px;font-size:16px">×</div></div>';
        }).join('') : '<div class="tip" style="text-align:center;padding:10px 0 2px">还没有直播源：到「设置 → 源管理」重新选线路，或在下方添加完整 http 的 m3u/txt 地址</div>';
        each(box.querySelectorAll('.pk-row'), function (el) {
            el.addEventListener('click', function (ev) {
                if (ev.target && ev.target.getAttribute && ev.target.getAttribute('data-delive')) return;
                var s = list[parseInt(el.getAttribute('data-i'), 10)];
                if (!s) return;
                closeLivePick();
                curLiveSrc = s.id;
                if ($('liveSrcName')) $('liveSrcName').textContent = s.name || '直播源';
                loadLiveUrl(s.url, s.id);
            });
        });
        each(box.querySelectorAll('[data-delive]'), function (el) {
            el.addEventListener('click', function (ev) {
                ev.stopPropagation();
                TS.call('delLive', el.getAttribute('data-delive'));
                TS.toast('直播源已删除');
                curLiveSrc = '';
                pendingLive = '';
                renderLives();
            });
        });
    }

    function loadLiveUrl(url, id) {
        curLiveSrc = id || '';
        $('liveHint').textContent = '正在拉取直播列表…';
        $('liveChips').innerHTML = '';
        $('liveChannels').innerHTML = '';
        TS.call('loadLiveAsync', url);   // 异步：结果走 window.onLiveText
    }

    window.onLiveText = function (r) {
        if (!r || !r.ok) {
            // v2.15：错误里已带实际地址与 HTTP 状态，直接展示便于定位
            $('liveHint').textContent = (r && r.error) || '拉取失败：未知原因';
            return;
        }
        if (r.srcId) {
            curLiveSrc = r.srcId;
            if (r.srcName && $('liveSrcName')) $('liveSrcName').textContent = r.srcName;
            renderLives();
            TS.toast('已自动切换到可用的直播源：' + (r.srcName || ''));
        }
        try { parseLive(r.text || ''); } catch (e) {
            $('liveHint').textContent = '解析出错：' + e;
        }
    };

    /** 解析直播源：m3u（#EXTINF）与 TVBox txt（`分组,#genre#` / `频道名,url`）双格式。 */
    function parseLive(text) {
        if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);   // 去 BOM
        text = text.replace(/^\uFEFF/, '').trim();
        liveGroups = []; liveCh = {};
        var nonStream = 0;
        var lines = text.split(/\r?\n/), curName = '', curGroup = '未分组', curLogo = '';
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i].trim();
            if (!line) continue;
            if (line.indexOf('#EXTINF') === 0) {
                var g = line.match(/group-title="([^"]*)"/);
                curGroup = (g && g[1]) ? g[1] : '未分组';
                var lg = line.match(/tvg-logo="([^"]*)"/i);
                curLogo = (lg && lg[1]) ? lg[1] : '';
                curName = line.slice(line.lastIndexOf(',') + 1).trim();
            } else if (line.indexOf('#') === 0) {
                continue;
            } else if (/,#genre#\s*$/i.test(line.replace(/，/g, ','))) {
                // txt 格式分组头：`央视频道,#genre#`
                curGroup = line.replace(/，/g, ',').replace(/,#genre#\s*$/i, '').trim() || '未分组';
                curName = '';
            } else if (curName) {
                // m3u 频道行：只收真流协议（http/https/rtmp/rtsp/udp/rtp），
                // 像「video://https://www.xxx.cn/...」这类指向网页的非流地址丢弃并计数
                var lu = line.trim().split('$')[0].trim();
                if (!/^(https?|rtmps?|rtsps?|udp|rtp):\/\//i.test(lu)) { curName = ''; nonStream++; continue; }
                if (!liveCh[curGroup]) { liveCh[curGroup] = []; liveGroups.push(curGroup); }
                liveCh[curGroup].push({ name: curName, url: lu, logo: curLogo });
                curName = ''; curLogo = '';
            } else {
                // txt 格式频道行：`CCTV1,http://…`（可能带多个备选 url）
                var norm = line.replace(/，/g, ',');
                var p = norm.indexOf(',');
                if (p <= 0) continue;
                var nm = norm.substring(0, p).trim(), u = norm.substring(p + 1).trim();
                if (!/^https?:\/\//i.test(u)) continue;
                u = u.split('#')[0].split('$')[0].trim();   // 取第一个备选，去 $ 扩展参数
                if (!u) continue;
                if (!liveCh[curGroup]) { liveCh[curGroup] = []; liveGroups.push(curGroup); }
                liveCh[curGroup].push({ name: nm, url: u });
            }
        }
        if (!liveGroups.length) {
            $('liveHint').textContent = nonStream > 0
                ? ('没解析到可播放频道：该源 ' + nonStream + ' 个频道都是网页链接（video:// 等），不是视频流，请换真实 IPTV 直播源')
                : '没解析到频道（支持 m3u/m3u8 与 TVBox txt 两种格式）';
            return;
        }
        var total = 0;
        for (var k in liveCh) total += liveCh[k].length;
        $('liveHint').textContent = '共 ' + liveGroups.length + ' 个分组 / ' + total + ' 个频道';
        $('liveChips').innerHTML = liveGroups.map(function (g, i) {
            return '<div class="chip' + (i === 0 ? ' on' : '') + '" data-g="' + esc(g) + '">' + esc(g)
                + '（' + liveCh[g].length + '）</div>';
        }).join('');
        each($('liveChips').querySelectorAll('.chip'), function (el) {
            el.addEventListener('click', function () {
                each($('liveChips').querySelectorAll('.chip'), function (x) { x.classList.remove('on'); });
                el.classList.add('on');
                renderChannels(el.getAttribute('data-g'));
            });
        });
        renderChannels(liveGroups[0]);
    }

    function renderChannels(g) {
        var all = liveCh[g] || [];
        var list = all.length > 300 ? all.slice(0, 300) : all;
        var box = $('liveChannels');
        box.innerHTML = (all.length > 300
            ? '<div class="tip" style="padding:6px 14px">该分组 ' + all.length + ' 个频道，先显示前 300 个</div>'
            : '')
            + list.map(function (c, i) {
                var ico = c.logo
                    ? '<img class="ch-ico" src="' + esc(c.logo) + '" onerror="this.style.display=\'none\'">'
                    : '<div class="ch-badge">' + esc((c.name || '频')[0]) + '</div>';
                return '<div class="srow" data-i="' + i + '"><div class="s-l" style="display:flex;align-items:center;gap:10px">'
                    + ico + '<div class="s-t">' + esc(c.name) + '</div></div><div class="s-v link">▶ 播放</div></div>';
            }).join('');
        each(box.querySelectorAll('.srow'), function (row) {
            row.addEventListener('click', function () {
                var c = list[parseInt(row.getAttribute('data-i'), 10)];
                if (!c) return;
                if (!/^(https?|rtmps?|rtsps?|udp|rtp):\/\//i.test(c.url)) {
                    TS.toast('该频道不是可播放的直播流（地址指向网页或解析器）'); return;
                }
                TS.call('playDirect', c.url, c.name);
            });
        });
    }

    // ==================== 短剧（v2.4：JS 短剧源榜单 + 竖滑；v2.9 分页加载） ====================

    var shortCur = { tabUrl: '', page: 1, totalPage: 1, loading: false };

    function shortCardList(list, append) {
        var shelf = $('shortShelf');
        var html = list.map(card).join('');
        if (append) {
            var tmp = document.createElement('div');
            tmp.innerHTML = html;
            each(tmp.querySelectorAll('.bk'), function (el) { shelf.appendChild(el); });
        } else {
            shelf.innerHTML = html;
        }
        each(shelf.querySelectorAll('.bk'), function (el) {
            el.addEventListener('click', function () {
                var g = el.getAttribute('data-guid'), t = el.getAttribute('data-title');
                // v2.22：短剧卡片统一走竖屏沉浸页（ShortActivity 已支持 spid|/jssrc|/
                // nas/cms 全部取集路径），不再让爬虫源卡片跳横屏播放页造成体验断裂
                TS.call('openShort', g, t);
            });
        });
    }

    window.onShortList = function (r) {
        var chips = $('shortChips');
        shortCur = { tabUrl: r && r.tabUrl || '', page: 1, totalPage: 1, loading: false };
        if (!r || !r.ok) {
            chips.innerHTML = '';
            $('shortShelf').innerHTML = '';
            $('shortEmptyText').textContent = (r && r.error ? r.error : '没有找到短剧内容')
                + '\n可到「设置 → 源管理 → 导入源」导入 JS 短剧源（zip 源包或脚本）';
            $('shortEmpty').classList.remove('hide');
            return;
        }
        // 榜单 tab（JS 源的 categoryMenus）
        var tabs = r.tabs || [];
        if (tabs.length) {
            chips.innerHTML = tabs.map(function (t, i) {
                return '<div class="chip' + (i === 0 ? ' on' : '') + '" data-u="'
                    + esc(t.url || '') + '">' + esc(t.title || t.name || ('榜' + (i + 1))) + '</div>';
            }).join('');
            each(chips.querySelectorAll('.chip'), function (el) {
                el.addEventListener('click', function () {
                    each(chips.querySelectorAll('.chip'), function (x) { x.classList.remove('on'); });
                    el.classList.add('on');
                    loadShort(el.getAttribute('data-u'));
                });
            });
        } else chips.innerHTML = '';
        var list = r.list || [];
        shortCur.totalPage = r.totalPage || 1;
        if (!list.length) {
            $('shortShelf').innerHTML = '';
            $('shortEmptyText').textContent = '该分类暂时没有内容，点上方切换分类，或到「设置 → 源管理」换一条线路';
            $('shortEmpty').classList.remove('hide');
            return;
        }
        $('shortEmpty').classList.add('hide');
        $('shortHint').textContent = '共 ' + list.length + ' 部'
            + (shortCur.totalPage > 1 ? ' · 下滑加载更多（第 1/' + shortCur.totalPage + ' 页）' : '')
            + ' · 点封面进入竖滑播放';
        shortCardList(list, false);
    };

    // v2.9：翻页追加
    window.onShortPage = function (r) {
        shortCur.loading = false;
        if (!r || !r.ok) {
            if (r && r.page > 1) { shortCur.page = r.page - 1; TS.toast(r.error || '加载更多失败'); }
            return;
        }
        var list = r.list || [];
        shortCur.totalPage = r.totalPage || 1;
        shortCur.page = r.page || shortCur.page;
        if (!list.length) {
            $('shortHint').textContent = '没有更多了';
            return;
        }
        shortCardList(list, true);
        $('shortHint').textContent = '下滑加载更多 · 第 ' + shortCur.page + '/' + shortCur.totalPage + ' 页';
    };

    function loadShort(tabUrl) {
        shortCur = { tabUrl: tabUrl || '', page: 1, totalPage: 1, loading: true };
        $('shortHint').textContent = '正在读取短剧源…';
        $('shortEmpty').classList.add('hide');
        $('shortShelf').innerHTML = '';
        if (tabUrl) TS.call('loadShortPage', tabUrl, 1);
        else TS.call('loadShortPage', '', 1);
    }

    // 原生自动选路成功后，提示（服务器地址已在源管理里）
    window.onBaseHealed = function (b) {
        if (!b) return;
        cfg.base = b;
        $('homeHint').textContent = '已切换为 ' + b + '，正在重新读取媒体库…';
        TS.toast('已自动切换到网页可用的地址');
    };

    // v2.15：当前源读不到分类时，原生自动切到同线路里可用的源
    window.onSourceHealed = function (name) {
        TS.toast('当前源不可用，已自动切换到 ' + name);
        loadSources();
        renderHomeSrc();
    };

    // ==================== 降级：内嵌网页 ====================

    function openWeb() {
        var u = TS.call('getWebUrl');
        if (!u || u.indexOf('http') !== 0) { TS.toast('请先保存服务器地址'); return; }
        $('webFrame').src = u;
        go('pageWeb');
    }

    // ==================== 事件绑定 ====================

    function boot() {
        fillCfg();
        each(document.querySelectorAll('.tabitem'), function (t) {
            t.addEventListener('click', function () { show(t.getAttribute('data-tab'), false); stack = []; });
        });
        $('navBack').addEventListener('click', function () { back(); });
        // v2.19：顶栏 搜索 / 观看历史 入口
        $('navSearch').addEventListener('click', function () {
            go('pageSearch');
            try { $('searchInput').focus(); } catch (e2) {}
        });
        $('navHist').addEventListener('click', function () { go('pageHistory'); });
        $('searchBtn').addEventListener('click', doSearch);
        $('searchInput').addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.keyCode === 13) doSearch();
        });
        $('histClear').addEventListener('click', function () {
            TS.call('clearHistory');
            $('histShelf').innerHTML = '';
            $('histEmpty').classList.remove('hide');
            renderHistory();
            TS.toast('观看历史已清空');
        });
        $('srDiag').addEventListener('click', openWeb);   // v2.9：内嵌网页浏览入口（诊断已移除）
        // v2.25：闪退后把原生崩溃堆栈复制到剪贴板，方便直接发回来定位
        var crashEl = $('srCrashLog');
        if (crashEl) crashEl.addEventListener('click', function () {
            var r;
            try { r = JSON.parse(TS.call('getCrashLog') || '{}'); } catch (e2) { r = {}; }
            var t = r.text || '';
            if (!t) { TS.toast('暂无崩溃记录（App 运行正常）'); return; }
            TS.copy(t);
            TS.toast('已复制最近 ' + t.length + ' 字崩溃日志，粘贴发送即可定位');
        });
        // v2.9：短剧页滚动到底自动加载下一页
        var scroller = document.querySelector('.content');
        if (scroller) scroller.addEventListener('scroll', function () {
            if ($('pageShort').classList.contains('hide')) return;
            if (shortCur.loading || shortCur.page >= shortCur.totalPage) return;
            if (!shortCur.tabUrl) return;
            if (scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 400) {
                shortCur.loading = true;
                TS.call('loadShortPage', shortCur.tabUrl, shortCur.page + 1);
            }
        });
        bindSettings();
        $('srSrcMgmt').addEventListener('click', function () { go('pageSrcMgmt'); renderSrcMgmt(); });
        // v2.13：源选择统一改居中弹窗
        $('homeSrcBtn').addEventListener('click', function () { openSrcPick('请选择首页数据源'); });
        $('mgrSrcBtn').addEventListener('click', function () { openSrcPick('请选择数据源'); });
        $('liveSrcBtn').addEventListener('click', openLivePick);
        $('srcPickSearch').addEventListener('input', function () { fillSrcPick(this.value.trim()); });
        $('srcPickOvl').addEventListener('click', function (e) {
            if (e.target === $('srcPickOvl')) $('srcPickOvl').classList.add('hide');
        });
        $('livePickOvl').addEventListener('click', function (e) {
            if (e.target === $('livePickOvl')) closeLivePick();
        });
        // 直播源添加（表单常驻弹窗底部）
        $('liveAddBtn2').addEventListener('click', function (ev) {
            ev.stopPropagation();
            var name = $('liveAddName') ? $('liveAddName').value : '';
            var url = $('liveAddUrl') ? $('liveAddUrl').value : '';
            if (!url) { TS.toast('请填直播源地址'); return; }
            var ar;
            try { ar = JSON.parse(TS.call('addLive', name, url) || '{}'); } catch (e2) { ar = {}; }
            if (ar.ok) {
                TS.toast('直播源已添加，列表里点选即可加载');
                if ($('liveAddName')) $('liveAddName').value = '';
                if ($('liveAddUrl')) $('liveAddUrl').value = '';
                renderLives();
            } else TS.toast('添加失败：' + (ar.error || ''));
        });
        $('linePickClose').addEventListener('click', closeLinePicker);
        $('linePickOvl').addEventListener('click', function (e) {
            if (e.target === $('linePickOvl')) closeLinePicker();
        });
        $('btnImportLocal').addEventListener('click', function () {
            TS.toast('请在文件管理器中选择源文件');
            TS.call('importPick');
        });
        $('btnTvbox').addEventListener('click', function () {
            var u = $('tvboxUrl').value;
            if (!u) { TS.toast('请填 TVBox 配置地址'); return; }
            TS.toast('正在解析配置…');
            TS.call('parseTvboxAsync', u);
        });
        window.onImportDone = function (r) {
            if (!r) return;
            if (r.ok) {
                $('impUrl').value = ''; $('impText').value = '';
                TS.toast('已导入：' + (r.name || '源') + ' → ' + (r.cat || '短剧源'));
                renderSrcMgmt();
                if (cur === 'pageHome') loadHome();
            } else TS.toast('导入失败：' + (r.error || ''));
        };
        $('btnImport').addEventListener('click', function () {
            var url = $('impUrl').value, txt = $('impText').value;
            if (!url && !txt) { TS.toast('请填源地址或粘贴脚本内容'); return; }
            TS.toast('正在导入…');
            // v2.23：改异步桥，30s 下载不再堵死 WebView 线程
            TS.call('importSourceAsync', url || txt, '');
        });
        $('actShare').addEventListener('click', function () {
            if (!curItem) return;
            TS.copy('「' + curItem.title + '」- 枫兮影视 ' + (cfg.base || ''));
            TS.toast('已复制分享文本');
        });
        $('actIntro').addEventListener('click', function () {
            var d = $('dtDesc');
            d.style.maxHeight = d.style.maxHeight ? '' : 'none';
            $('actIntro').classList.toggle('on');
        });
        // v2.22：悬浮小窗权限引导
        $('floatPermGo').addEventListener('click', function () {
            $('floatPermOvl').classList.add('hide');
            TS.call('openOverlaySettings');
        });
        $('floatPermClose').addEventListener('click', function () {
            $('floatPermOvl').classList.add('hide');
        });
        $('floatPermOvl').addEventListener('click', function (e) {
            if (e.target === $('floatPermOvl')) $('floatPermOvl').classList.add('hide');
        });
        $('epOrderBtn').addEventListener('click', function () {
            epReverse = !epReverse;
            renderEpList();
        });
        $('srMyHist').addEventListener('click', function () { go('pageHistory'); });
        $('srMySearch').addEventListener('click', function () {
            go('pageSearch');
            try { $('searchInput').focus(); } catch (e3) {}
        });
        $('btnPlay').addEventListener('click', function () {
            if (curItem) TS.call('openVideo', curItem.guid, curItem.title, curItem.guid);
        });
        // v2.16：无论有没有 cfg.base，都先跑一次 show() 初始化顶栏
        // （两个下拉互斥、返回键可见性、tab 高亮）。旧版 cfg.base 有值时
        // 跳过 show()，导致首页顶栏两个下拉同时显示、标题被挤没。
        // v2.23：不再因「没有 NAS 地址」跳到设置页 —— CMS / 爬虫 / TVBox 源
        // 全都不需要 cfg.base，旧逻辑会让这些用户冷启动落在设置页、首页空白。
        // loadHome() 内部已处理「未选源」空态，直接展示「立即添加订阅源」入口。
        show('pageHome', false);
        applyCarMode();   // v2.22：初始按浮窗宽度定布局
    }

    window.onLoginChanged = function () { loadSources(); if (cur === 'pageHome') loadHome(); };

    // 原生账号密码登录结果（Token 已由原生保存，这里刷新界面并加载首页）
    window.onNativeLogin = function (ok, msg) {
        TS.toast((ok ? '✓ ' : '✗ ') + msg);
        if (ok) { fillCfg(); loadHome(); }
    };
    window.onNativeBack = function () { if (!back()) TS.call('exitApp'); };

    // ==================== v2.22 悬浮小窗 ====================

    // 权限被拒 → 引导浮层
    window.onFloatPerm = function () {
        var ovl = $('floatPermOvl');
        if (ovl) ovl.classList.remove('hide');
    };
    // 浮窗状态推送（Service start/stop 时原生回传）
    window.onFloatState = function (s) {
        TS.toast(s && s.showing ? '已进入悬浮小窗' : '悬浮小窗已关闭');
    };

    if (document.readyState === 'loading')
        document.addEventListener('DOMContentLoaded', boot);
    else boot();

    return {
        go: go, show: show, back: back, loadHome: loadHome,
        saveCfg: saveCfg, openWeb: openWeb,
        // v2.23：空态「立即添加订阅源」直达（EasyBox 首启引导）
        goSrcMgmt: function () { go('pageSrcMgmt'); renderSrcMgmt(); }
    };
})();
