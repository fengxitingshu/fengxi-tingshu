// 红果短剧源（官网数据 hongguoduanju.com）。
// 全部走官网对普通用户开放的页面与接口，无需登录、无需签名：
//   搜索   /api/search/result/?query=<关键词>&page=<页>   JSON：searchList + totalPages
//   热播榜 首页 HTML 内嵌 homeSections（4 个榜单 tab + video_list）
//   详情   /player/<series_id> HTML 内嵌 vid_list（下标即集数顺序）、series_name、series_cover、series_intro
//   播放   /video/public/<vid>/360p.mp4 官网明文 MP4 直链，无需请求页面、无需请求头
// 书籍地址统一 hg:<series_id>；剧集地址 hg:<series_id>:<vid>。
// 播放地址也支持写成分享口令（含 series_id 的链接/口令贴进搜索框即可搜到该部剧）。

(function () {
  var SITE = 'https://hongguoduanju.com';
  var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36';
  var OPTS = { headers: { 'User-Agent': UA, 'Referer': SITE + '/' } };
  // 源宿主：prelude 不提供全局 host（只挂在源对象上），registerSource 后从返回值取
  var host;

  // 官网 HTML 里的 URL 用 \u002F 转义斜杠
  function unesc(s) {
    return String(s || '').replace(/\\u002F/g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
  }

  function toBook(v) {
    var d = (v && v.video_data) || v || {};
    var id = String(d.series_id || d.keyword || '');
    if (!/^\d+$/.test(id)) return null;
    return {
      bookUrl: 'hg:' + id,
      title: d.series_title || d.name || '未命名短剧',
      intro: d.series_intro || '',
      coverUrl: unesc(d.series_cover),
      status: d.episode_right_text || (d.episode_cnt ? '全 ' + d.episode_cnt + ' 集' : '')
    };
  }

  // 从一段文本里提取平衡的 JSON 数组/对象（从 target 首次出现处开始）
  function extractBalanced(text, key, open, close) {
    var i = text.indexOf(key);
    if (i < 0) return null;
    var start = text.indexOf(open, i);
    if (start < 0) return null;
    var depth = 0, inStr = false, esc = false;
    for (var j = start; j < text.length; j++) {
      var c = text.charAt(j);
      if (esc) { esc = false; continue; }
      if (c === '\\') { if (inStr) esc = true; continue; }
      if (c === '"') { inStr = !inStr; continue; }
      if (inStr) continue;
      if (c === open) depth++;
      else if (c === close) { depth--; if (depth === 0) return text.substring(start, j + 1); }
    }
    return null;
  }

  // 首页 → homeSections 数组（带 30 分钟缓存，避免每次进发现页都拉一遍）
  function homeSections() {
    var cached = null;
    try {
      if (host.cacheAge('home') >= 0 && host.cacheAge('home') < 30 * 60) {
        cached = host.readCache('home');
        if (cached) return JSON.parse(cached);
      }
    } catch (e) { }
    var html = host.getString(SITE + '/', OPTS);
    var raw = extractBalanced(html, '"homeSections"', '[', ']');
    if (!raw) throw new Error('红果首页没有找到热播榜数据');
    var sections = JSON.parse(raw);
    host.writeCache('home', raw);
    return sections;
  }

  // 详情页 → { title, coverUrl, intro, vids }
  function fetchSeries(sid) {
    var html = host.getString(SITE + '/player/' + sid, OPTS);
    var m = html.match(/"vid_list"\s*:\s*\[([^\]]*)\]/);
    if (!m) throw new Error('没有找到剧集列表（vid_list），可能该剧已下架');
    var vids = m[1].split(',').map(function (s) { return s.trim().replace(/^"|"$/g, ''); })
      .filter(function (s) { return /^\d+$/.test(s); });
    if (!vids.length) throw new Error('剧集列表为空');
    var t = html.match(/"series_name"\s*:\s*"([^"]*)"/);
    var c = html.match(/"series_cover"\s*:\s*"([^"]*)"/);
    var i = html.match(/"series_intro"\s*:\s*"([^"]*)"/);
    return {
      title: t ? t[1] : '未命名短剧',
      coverUrl: unesc(c ? c[1] : ''),
      intro: i ? i[1] : '',
      vids: vids
    };
  }

  host = registerSource({
    id: 'a3f8c21d94b4e9a80c73f15b6d2e4a97',
    name: '红果短剧',
    url: SITE,
    group: '短剧',
    description: '红果短剧（官网数据）：热播榜分类浏览 + 关键词搜索，全站免登录播放，每集 360p MP4 明文直链。分享链接/口令也可以直接贴到搜索框里找到剧。',

    // 发现页：热播榜 4 个榜单 tab + 分类页 tab（分类页支持 ?page= 翻页，每页 24 部）
    categoryMenus: function () {
      var tabs = homeSections().map(function (s) { return { title: s.tab_name, url: 'hg:tab:' + s.tab_type }; });
      var cats = [
        ['真人短剧', '/category/real-drama'],
        ['漫剧', '/category/comic-drama'],
        ['AI 剧', '/category/ai-drama'],
        ['传奇', '/category/real-drama/legend'],
        ['都市', '/category/real-drama/urban'],
        ['逆袭', '/category/real-drama/comeback']
      ];
      for (var i = 0; i < cats.length; i++)
        tabs.push({ title: cats[i][0], url: SITE + cats[i][1] + '?page=1' });
      return tabs.length ? [{ title: '红果短剧', tabs: tabs }] : [];
    },

    categoryPage: function (url) {
      var u = String(url);
      if (u.indexOf('hg:tab:') === 0) {
        var type = u.replace('hg:tab:', '');
        var section = homeSections().filter(function (s) { return s.tab_type === type; })[0]
          || homeSections()[0];
        var books = (section.video_list || []).map(toBook).filter(function (b) { return b; });
        return { books: books, currentPage: 1, totalPage: 1 };
      }
      // 分类页（http URL，可带 ?page=N）：HTML 内嵌 series_id / series_name / series_cover
      // 三个数组字段各出现 N 次且顺序一致，按序配对即可
      var html = host.getString(u, OPTS);
      var ids = html.match(/"series_id"\s*:\s*"?(\d+)"?/g) || [];
      var names = html.match(/"series_name"\s*:\s*"([^"]*)"/g) || [];
      var covers = html.match(/"series_cover"\s*:\s*"([^"]*)"/g) || [];
      var intro = html.match(/"series_intro"\s*:\s*"([^"]*)"/);
      var out = [], seen = {};
      for (var i = 0; i < ids.length; i++) {
        var id = String(ids[i]).replace(/[^\d]/g, '');
        if (!id || seen[id]) continue;
        seen[id] = 1;
        var nm = '', cv = '';
        var nmM = i < names.length ? names[i].match(/"series_name"\s*:\s*"([^"]*)"/) : null;
        var cvM = i < covers.length ? covers[i].match(/"series_cover"\s*:\s*"([^"]*)"/) : null;
        nm = nmM ? unesc(nmM[1]) : '未命名短剧';
        cv = cvM ? unesc(cvM[1]) : '';
        out.push({
          bookUrl: 'hg:' + id,
          title: nm,
          intro: intro ? unesc(intro[1]) : '',
          coverUrl: cv,
          status: ''
        });
      }
      var tp = html.match(/"totalPages"\s*:\s*(\d+)/);
      return { books: out, currentPage: 1, totalPage: tp ? parseInt(tp[1], 10) : 1 };
    },

    // 搜索：支持关键词，也支持贴入含 series_id 的分享链接/口令（直接定位到那部剧）
    search: function (keywords, page) {
      var kw = String(keywords || '').trim();
      if (!kw) return { books: [], totalPage: 1 };
      var m = kw.match(/(\d{15,})/);
      if (m) {
        // 看起来是 series_id / 分享口令：确认它确实是剧而不是误输入的长数字
        try {
          var d = fetchSeries(m[1]);
          return {
            books: [{ bookUrl: 'hg:' + m[1], title: d.title, intro: d.intro, coverUrl: d.coverUrl, status: '全 ' + d.vids.length + ' 集' }],
            totalPage: 1
          };
        } catch (e) { /* 不是有效 id，按普通关键词继续 */ }
      }
      var j = host.getJson(SITE + '/api/search/result/?query=' + encodeURIComponent(kw) + '&page=' + page, OPTS);
      var list = items(j, 'searchList').filter(function (v) { return v && (v.doc_type === 23 || v.video_data); });
      var books = list.map(toBook).filter(function (b) { return b; });
      var total = int(j, 'totalPages', 1);
      return { books: books, currentPage: page, totalPage: Math.max(1, total) };
    },

    bookDetail: function (bookUrl, loadEpisodes, loadFullPages) {
      var sid = String(bookUrl).replace(/^hg:/, '');
      var d = fetchSeries(sid);
      var episodes = [];
      if (loadEpisodes) {
        for (var i = 0; i < d.vids.length; i++) {
          episodes.push({ title: '第 ' + (i + 1) + ' 集', url: 'hg:' + sid + ':' + d.vids[i] });
        }
      }
      return {
        episodes: episodes,
        title: d.title,
        intro: d.intro,
        coverUrl: d.coverUrl,
        status: '全 ' + d.vids.length + ' 集'
      };
    },

    // 官网明文 MP4 直链（经实测无需请求头；集数下标与 vid_list 一致）
    video: function (url) {
      var parts = String(url).split(':');
      var vid = parts[parts.length - 1];
      if (!/^\d+$/.test(vid)) throw new Error('剧集地址不正确：' + url);
      return SITE + '/video/public/' + vid + '/360p.mp4';
    }
  }).host;
})();
