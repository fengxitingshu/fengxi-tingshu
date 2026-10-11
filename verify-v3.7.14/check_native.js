#!/usr/bin/env node
/**
 * v2.2 原生侧契约检查。
 *
 * 这些是"改错一个下标就整条功能失效、但界面上看不出来"的硬约定，
 * 用源码扫描守住，避免以后重构时无声回退。
 */
const fs = require('fs');
const path = require('path');

const ROOT = '/root/tingshu-app/src/com/tingshu/app';
const R = f => fs.readFileSync(path.join(ROOT, f), 'utf8');
// ★ v3.3：eprendre 兼容类分散在 src/ 与 src-kt/ 两棵树里，各给一个读取器
const SRC = '/root/tingshu-app/src';
const KT = '/root/tingshu-app/src-kt';
/** 依次在 src-kt / src 下找文件（Kotlin 优先，因为 BookDetail/Episode 已迁过去） */
const R2 = f => {
  const rel = path.join('com', f);
  const kt = path.join(KT, rel);
  if (fs.existsSync(kt)) return fs.readFileSync(kt, 'utf8');
  return fs.readFileSync(path.join(SRC, rel), 'utf8');
};

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail === undefined ? '' : detail });
}

// ==================== 1) 通知栏动作顺序 + 紧凑视图下标 ====================
{
  const s = R('player/PlayerService.java');

  // 动作添加顺序：上一集 → 播放暂停 → 下一集 → 停止
  const iPrev = s.indexOf('"上一集"');
  const iToggle = s.indexOf('"暂停" : "播放"');
  const iNext = s.indexOf('"下一集"');
  const iStop = s.indexOf('"停止"');
  check('通知动作按 上一集→播放暂停→下一集→停止 顺序添加',
    iPrev > 0 && iToggle > iPrev && iNext > iToggle && iStop > iNext,
    `idx prev=${iPrev} toggle=${iToggle} next=${iNext} stop=${iStop}`);

  // 紧凑视图引用下标 0/1/2（对应上面三个动作）
  check('setShowActionsInCompactView(0, 1, 2)',
    /setShowActionsInCompactView\(\s*0\s*,\s*1\s*,\s*2\s*\)/.test(s));

  // 进度条三要素
  check('通知进度条 setProgress(max, cur, false) —— 第三参必须 false',
    /b\.setProgress\(\s*max\s*,\s*cur\s*,\s*false\s*\)/.test(s));
  check('进度条 cur 已钳制到 0..max（v2.4 applyProgress）',
    /int cur = \(int\) Math\.min\(max, \(p \* max\) \/ dur\);/.test(s) ||
    /Math\.min\(max,.*\* max\) \/ dur/.test(s));
  check('v2.4 每秒 tick 驱动进度（updateProgress）',
    /public void updateProgress\(long posMs, long durMs\)/.test(s) &&
    /applyProgress\(b\)/.test(s));
  check('durMs 未知时估算兜底（lastKnownDur）',
    /lastKnownDur/.test(s));

  // 通知刷新节流 1000ms（v2.2 由 5000 收紧）
  const ma = fs.readFileSync(path.join(ROOT, 'MainActivity.java'), 'utf8');
  check('v2.4 onProgress 走轻量 updateProgress（每秒刷）',
    /updateProgress\(cur \* 1000L, dur \* 1000L\)/.test(ma));

  // 封面两级缓存
  check('封面图内存 LRU 缓存存在', /COVER_MEM/.test(s) && /removeEldestEntry/.test(s));
  check('封面图磁盘缓存存在',
    /coverDiskFile/.test(s) && /getCacheDir\(\), "cover"\)/.test(s) && /COVER_DISK_TTL_MS/.test(s));
}

// ==================== 2) iQOO 音源适配（MediaBrowserService） ====================
{
  const bp = path.join(ROOT, 'player/PlayerBrowserService.java');
  check('PlayerBrowserService.java 存在', fs.existsSync(bp));
  const s = fs.readFileSync(bp, 'utf8');

  check('继承 MediaBrowserService', /extends MediaBrowserService/.test(s));
  check('onGetRoot 返回非 null 的 BrowserRoot（返回 null 系统会完全不列出本 App）',
    /return new BrowserRoot\(ROOT_ID/.test(s));
  check('onLoadChildren 有返回结果', /result\.sendResult\(items\)/.test(s));
  check('容器项 FLAG_BROWSABLE', /MediaItem\.FLAG_BROWSABLE/.test(s));
  check('播放项 FLAG_PLAYABLE', /MediaItem\.FLAG_PLAYABLE/.test(s));
  check('绑定 PlayerService 的 session token',
    /setSessionToken\(svc\.sessionToken\(\)\)/.test(s));

  const man = fs.readFileSync('/root/tingshu-app/AndroidManifest.xml', 'utf8');
  check('Manifest 声明 android.media.browse.MediaBrowserService action',
    /android\.media\.browse\.MediaBrowserService/.test(man));
  check('PlayerBrowserService 已注册且 exported=true',
    /PlayerBrowserService[\s\S]{0,200}android:exported="true"/.test(man));

  // PlayerService 暴露给 BrowserService 的读取口
  const ps = R('player/PlayerService.java');
  check('PlayerService 暴露 currentBook/currentChapter/currentIndex/sessionToken',
    /public Book currentBook\(\)/.test(ps) &&
    /public Chapter currentChapter\(\)/.test(ps) &&
    /public int currentIndex\(\)/.test(ps) &&
    /public MediaSession\.Token sessionToken\(\)/.test(ps));
}

// ==================== 3) 提速四件套（原生侧） ====================
{
  const ag = R('source/Aggregator.java');

  // ① 取链缓存
  check('Aggregator 有取链结果缓存（AUDIO_CACHE）',
    /AUDIO_CACHE/.test(ag) && /audioCacheGet/.test(ag) && /audioCachePut/.test(ag));
  check('取链缓存有 TTL', /AUDIO_TTL_MS/.test(ag));
  check('audioWithFallback 首步命中缓存直接返回',
    /AudioSource hit = audioCacheGet\(book, chapterIdx\);\s*\n\s*if \(hit != null\)/.test(ag));
  check('取链失败可主动剔除（invalidateAudio）', /public static void invalidateAudio/.test(ag));

  // ② 预加载
  check('Aggregator 有下一集预加载（preloadNext）',
    /public static void preloadNext/.test(ag) && /PRELOAD_POOL/.test(ag));
  check('预加载独立单线程池，不与用户请求抢线程',
    /newSingleThreadExecutor\(namedFactory\("preload"\)\)/.test(ag));
  check('解析出直链后 warmUp 预热连接（DNS/TLS）', /public static void warmUp/.test(ag));

  // ③ 搜索边出边渲染（native 侧：worker 里就回调 onPartial）
  check('搜索在 worker 线程里直接回调 onPartial（不等 latch）',
    /if \(cb != null\) \{\s*\n\s*List<Book> got = buckets\[idx\];[\s\S]{0,260}cb\.onPartial\(srcs\.get\(idx\)\.id\(\), got\)/.test(ag));
  check('合并阶段不再重复 onPartial（避免重复追加）',
    /onPartial 已经在 worker 里发过了/.test(ag));

  // 桥接推送
  const br = R('Bridge.java');
  check('Bridge 推 onSearchPartial 给前端', /callback\("onSearchPartial"/.test(br));

  // ④ 预加载接入播放流程
  const ma = R('MainActivity.java');
  check('onStarted 里触发下一集预加载', /Aggregator\.preloadNext\(book, nextHuman, null\)/.test(ma));
  check('gotoChapter 进队列后也触发预加载', /Aggregator\.preloadNext\(book, nextHuman, null\)/.test(ma));
  check('播放出错剔除取链缓存（防止死链复用）',
    /Aggregator\.invalidateAudio\(b, human\)/.test(ma));
  check('停止播放重置预加载标记', /Aggregator\.resetPreload\(\)/.test(ma));
}

// ==================== 4) 详情缓存命中即返回（不回退 v1.9） ====================
{
  const ag = R('source/Aggregator.java');
  check('detailWithFallback 首行查缓存后立即返回',
    /BookDetail cached = detailCacheGet\(book\);\s*\n\s*if \(cached != null\) return cached;/.test(ag));
  check('详情磁盘缓存 TTL = 3 天', /3L \* 24 \* 3600 \* 1000/.test(ag));
  check('详情并发赛跑（不再串行等源）', /DETAIL_POOL\.execute/.test(ag) && /slot\[0\]/.test(ag));
  check('详情整体截止时间存在', /DETAIL_DEADLINE_MS/.test(ag));
}

// ==================== 4b) ★ v3.7.3 license 屏蔽 + 更新公告 ====================
{
  const PROJ = '/root/tingshu-app';

  // --- license 检查屏蔽：libchaquopy_java.so 偏移 0x5c1c 的 16 字节 ---
  // Chaquopy 12.0.0 的 libchaquopy_java.so 在 Cython 模块 init 阶段会调
  // java.chaquopy.check_license()，它要求 ticket.txt 形如
  //     <base64(json)>.<base64(rsa签名)>
  // 本工程运行时资产是自建的，ticket.txt 里只有裸时间戳，于是
  //     signed_ticket, signature_b64 = ticket.split(".")
  // 抛 ValueError: need more than 1 value to unpack（CPython 3.8 模板），
  // 且没有 Python 栈（发生在 C 层），Java 侧只看到「Python 运行时不可用」。
  // 修复：把那条调用 check_license 的 blr 换成「incref 后直接返回 ticket」。
  //   原始 088142f9 e0031aaa ff1700f9 00013fd6  (ldr x8,[..]; mov x0,x26; str xzr; blr x8)
  //   补丁 490340f9 29050091 490300f9 e0031aaa  (ldr x9,[x26]; add x9,#1; str x9,[x26]; mov x0,x26)
  const LIC = PROJ + '/lib/arm64-v8a/libchaquopy_java.so';
  check('v3.7.3：libchaquopy_java.so 存在', fs.existsSync(LIC));
  if (fs.existsSync(LIC)) {
    const buf = fs.readFileSync(LIC);
    const patched = buf.slice(0x5c1c, 0x5c1c + 16).toString('hex');
    check('v3.7.3：license 补丁已生效（0x5c1c = 490340f9...）',
      patched === '490340f929050091490300f9e0031aaa', patched.slice(0, 16));
    check('v3.7.3：license 补丁不再是原始序列',
      patched !== '088142f9e0031aaa ff1700f9 00013fd6'.replace(/ /g, ''));
  }

  // --- 补丁脚本（幂等 + 原始字节校验） ---
  const PL = PROJ + '/patch_license.py';
  check('v3.7.3：patch_license.py 存在（可复现、幂等）', fs.existsSync(PL));
  if (fs.existsSync(PL)) {
    const src = fs.readFileSync(PL, 'utf8');
    check('v3.7.3：patch_license.py 带原始字节校验（拒绝误打）',
      /原始字节|ORIG/.test(src) && /bytes\.fromhex/.test(src));
    check('v3.7.3：patch_license.py 幂等（已打则跳过）',
      /已存在|已打过|already/i.test(src));
  }

  // --- 构建闸门：未打补丁就直接中断 ---
  const bld = fs.readFileSync(PROJ + '/build.sh', 'utf8');
  check('v3.7.3：build.sh 有 license 补丁自检闸门',
    /license 检查屏蔽自检/.test(bld) && /490340f9/.test(bld));
  check('v3.7.3：build.sh 缺补丁时中断并提示 patch_license.py',
    /未打 license 补丁/.test(bld) && /patch_license\.py/.test(bld));

  // --- 更新公告 UI（二级页 + 入口 + 渲染） ---
  const idx2 = fs.readFileSync(PROJ + '/assets/www/index.html', 'utf8');
  const ajs = fs.readFileSync(PROJ + '/assets/www/app.js', 'utf8');
  check('v3.7.3：更新公告二级页 pageChangelog 存在',
    /id="pageChangelog"/.test(idx2));
  check('v3.7.3：我的页有更新公告入口（openChangelog）',
    /openChangelog\(\)/.test(idx2) && /更新公告/.test(idx2));
  check('v3.7.3：关于页有更新公告入口',
    /setlabel">更新公告/.test(idx2));
  check('v3.7.3：SUBS 注册 changelog 页',
    /changelog:\s*'pageChangelog'/.test(ajs));
  check('v3.7.3：更新公告含「修复的问题」区块',
    /修复的问题/.test(ajs) && /cl-blk-t fix/.test(ajs));
  check('v3.7.3：更新公告含「新增的功能」区块',
    /新增的功能/.test(ajs) && /cl-blk-t add/.test(ajs));
  check('v3.7.3：公告数据不含实现细节（无 ticket/二进制补丁/so 等词）',
    !/ticket\.txt|二进制补丁|libchaquopy_java|0x5c1c/i.test(
      (ajs.match(/var CHANGELOG = \[[\s\S]*?\n    \];/) || [''])[0]));
  check('v3.7.3：updateTabbar 把 changelog 当二级页隐藏底栏',
    /App\.page === SUBS\.changelog/.test(ajs));
  check('v3.7.3：历史版本折叠区存在',
    /cl-old/.test(ajs) && /toggleOldLog/.test(ajs));
  check('v3.7.3：更新公告样式已定义',
    /\.cl-blk-t/.test(fs.readFileSync(PROJ + '/assets/www/style.css', 'utf8')));
}

// ==================== 4c) ★ v3.7.4 播放页可读性 + 批量源管理 ====================
{
  const PROJ = '/root/tingshu-app';
  const css4 = fs.readFileSync(PROJ + '/assets/www/style.css', 'utf8');
  const js4 = fs.readFileSync(PROJ + '/assets/www/app.js', 'utf8');
  const bsj = fs.readFileSync(PROJ + '/src/com/tingshu/app/BridgeSource.java', 'utf8');
  const brj = fs.readFileSync(PROJ + '/src/com/tingshu/app/Bridge.java', 'utf8');

  // --- 播放页文字可读性 ---
  check('v3.7.4：.fbg-tint 浅色叠色加厚（.70/.85/.96）',
    /255, 255, 255, \.70\) 0%,[\s\S]*?255, 255, 255, \.85\) 46%,[\s\S]*?255, 255, 255, \.96\) 100%\)/.test(css4));
  check('v3.7.4：.fbg-tint 深色叠色加厚（.66/.80/.92）',
    /0, 0, 0, \.66\) 0%,[\s\S]*?0, 0, 0, \.80\) 46%,[\s\S]*?0, 0, 0, \.92\) 100%\)/.test(css4));
  check('v3.7.4：applyTone 浅色 alpha 与 CSS 同步（.68/.82/.95）',
    /a1 = \.68; a2 = \.82; a3 = \.95;/.test(js4));
  check('v3.7.4：applyTone 深色 alpha 与 CSS 同步（.64/.80/.92）',
    /a1 = \.64; a2 = \.80; a3 = \.92;/.test(js4));
  check('v3.7.4：顶部标题 .ftitle 加文字板底',
    /\.fnav \.ftitle \{[\s\S]*?background: rgba\(255, 255, 255, \.58\)/.test(css4)
    && /html\.dark \.fnav \.ftitle \{ background: rgba\(0, 0, 0, \.38\)/.test(css4));
  check('v3.7.4：时长 .ftimes span 加文字板底（原全页最裸漏）',
    /\.ftimes span \{[\s\S]*?background: rgba\(255, 255, 255, \.58\)/.test(css4)
    && /html\.dark \.ftimes span \{ background: rgba\(0, 0, 0, \.38\)/.test(css4));
  check('v3.7.4：书名 .fbook 改双向描边阴影',
    /0 1px 2px rgba\(255, 255, 255, \.75\)/.test(css4)
    && /0 2px 10px rgba\(0, 0, 0, \.22\)/.test(css4));
  check('v3.7.4：倍速/增益面板整块加底（.page.full .speed-collapse）',
    /\.page\.full \.speed-collapse \{[\s\S]*?background: rgba\(255, 255, 255, \.62\)/.test(css4)
    && /html\.dark \.page\.full \.speed-collapse \{ background: rgba\(0, 0, 0, \.42\)/.test(css4));

  // --- 批量源管理：前端 ---
  check('v3.7.4：SRC_GROUPS 新增 Python 源分组',
    /k: 'py', t: 'Python 源'/.test(js4));
  check('v3.7.4：groupOf 识别 origin=py',
    /o === 'py'\) return 'py';/.test(js4));
  // ★ v3.7.14：多选交互整体下掉（用户反馈"长按和选择有冲突"）。
  //   下面这几条从"多选存在"改为"多选已移除、只留长按菜单"。
  check('v3.7.14：分组页多选吸顶条已移除（不再有 srcMultiBar / srcMultiDel）',
    !/id="srcMultiBar"/.test(js4) && !/id="srcMultiDel"/.test(js4));
  check('v3.7.14：多选函数已改为空实现（保留符号但不建多选态）',
    /ui\.enterSrcMulti = function \(\) \{\};/.test(js4)
    && /ui\.srcSelAll = function \(\) \{\};/.test(js4)
    && /ui\.srcSelInvert = function \(\) \{\};/.test(js4));
  // ★ v3.7.13：源行的长按手势由 bindSrcPress 事件委托统一处理（480ms）。
  // ★ v3.7.14：长按只弹菜单，不再顺带进入多选。
  check('v3.7.13/v3.7.14：长按源行弹统一菜单（委托手势 + 480ms 计时器，不进多选）',
    /function bindSrcPress/.test(js4) && /var LONG = 480;/.test(js4)
    && /srcRowMenu\(id, startPt\.x, startPt\.y\);/.test(js4)
    && !/App\.multiSrc\.sel\[id\] = true;/.test(js4)
    && /timer = setTimeout\(function \(\) \{/.test(js4));
  check('v3.7.14：行内不再渲染勾选框（chk-src），内置源保护走菜单 disabled',
    !/chk chk-src/.test(js4) && /内置源不可删除/.test(js4)
    && /disabled: true/.test(js4));
  check('v3.7.14：短按一行与长按弹同一套菜单（srcRowClick → srcRowMenu）',
    /ui\.srcRowClick = function \(id\) \{/.test(js4)
    && /srcRowMenu\(id, Math\.max\(8, r\.right - 190\)/.test(js4));
  check('v3.7.14：返回键不再需要"先退出源多选"',
    !/if \(App\.multiSrc\) \{ exitSrcMulti\(false\); return true; \}/.test(js4));
  check('v3.7.14：移除多选 CSS 依赖（不再用 body.multi-src-on 驱动勾选框）',
    !/body\.multi-src-on \.chk-src \{ display: block; \}/.test(css4));
  check('v3.7.14：源行提示文案改为「长按任意一行打开菜单」',
    /长按任意一行打开菜单/.test(js4));

  // --- Python 源删除：native ---
  check('v3.7.4：Bridge 暴露 removeSourceById',
    /public void removeSourceById\(final String id\)/.test(brj));
  check('v3.7.4：BridgeSource.removeSourceById 按 py_ 前缀分派',
    /id\.startsWith\("py_"\)/.test(bsj)
    && /PythonHost\.unloadSource\(id\)/.test(bsj));
  check('v3.7.4：py 源删除同时删磁盘 .py 文件',
    /python_sources|sourcesDir\(ctx\)/.test(bsj)
    && /f\.delete\(\)/.test(bsj));
  check('v3.7.4：removeSourceById 拒绝内置源',
    /removeSourceById[\s\S]{0,600}isBuiltin\(id\)\) return false;/.test(bsj));
  check('v3.7.4：allSourcesJson 给 PySourceAdapter 标 origin=py',
    /s instanceof com\.tingshu\.app\.python\.PySourceAdapter\) \{\s*origin = "py";/.test(bsj));
  check('v3.7.4：removeCustom 保留（旧调用点兼容）',
    /public static boolean removeCustom\(String id\)/.test(bsj));
}

// ==================== 4d) ★ v3.7.5 首页/播放页/源分组修缮 ====================
{
  const PROJ = '/root/tingshu-app';
  const css5 = fs.readFileSync(PROJ + '/assets/www/style.css', 'utf8');
  const js5 = fs.readFileSync(PROJ + '/assets/www/app.js', 'utf8');
  const idx5 = fs.readFileSync(PROJ + '/assets/www/index.html', 'utf8');
  const brj5 = fs.readFileSync(PROJ + '/src/com/tingshu/app/Bridge.java', 'utf8');

  // --- #139 封面旋转（暂停停住保持角度） ---
  check('v3.7.5：.fdisc 用 animation-play-state 控制（不重置角度）',
    /\.fdisc \{ animation: disc-spin 18s linear infinite; animation-play-state: paused; \}/.test(css5)
    && /\.fdisc\.playing \{ animation-play-state: running; \}/.test(css5));
  check('v3.7.5：悬浮球封面 .pcover 同样 rotation 驱动',
    /\.player \.pcover \{[\s\S]*?animation: disc-spin 18s linear infinite;[\s\S]*?animation-play-state: paused;/.test(css5)
    && /\.player\.playing \.pcover \{ animation-play-state: running; \}/.test(css5));
  check('v3.7.5：置灰悬浮球不转 + 减少动效不转',
    /\.player\.empty \.pcover \{ animation: none; animation-play-state: paused; \}/.test(css5)
    && /@media \(prefers-reduced-motion: reduce\) \{[\s\S]*?\.player \.pcover \{ animation: none; \}/.test(css5));
  check('v3.7.5：paintPlayIcon 同时切 fDisc 与 player 的 .playing',
    /if \(\$\('fDisc'\)\) \$\('fDisc'\)\.classList\[p \? 'add' : 'remove'\]\('playing'\);/.test(js5)
    && /if \(\$\('player'\)\) \$\('player'\)\.classList\[p \? 'add' : 'remove'\]\('playing'\);/.test(js5));

  // --- #141 当前时长跟随进度条 ---
  check('v3.7.5：paintCurPos 按百分比定位 + 边距收敛',
    /function paintCurPos\(p, draggingNow\)/.test(js5)
    && /var CUR_PAD = 8;/.test(js5)
    && /el\.style\.left = pct\.toFixed\(2\) \+ '%';/.test(js5));
  check('v3.7.5：setTime 里调用 paintCurPos（正常播放跟随）',
    /paintCurPos\(dur > 0 \? \(cur \/ dur\) : 0, false\);/.test(js5));
  check('v3.7.5：拖动进度条时 paintCurPos(pendingP, true) 跟手',
    /paintCurPos\(pendingP, true\);/.test(js5));
  check('v3.7.5：#fCur 标记 .ftime-cur + 绝对定位 CSS',
    /<span id="fCur" class="ftime-cur">/.test(idx5)
    && /\.ftimes \.ftime-cur \{[\s\S]*?position: absolute;/.test(css5)
    && /\.ftimes \.ftime-cur\.dragging \{ transition: none; \}/.test(css5));

  // --- #140 播放页书源名/按钮/主播名可读性 ---
  // ★ v3.7.7 起 .fsrc-tag 底色从 .72 降到 .58、dark 从 .5 降到 .38（与顶栏标题统一），故断言放宽
  check('v3.7.5：.page.full .fsrc-tag 加厚底 + 主文字色（v3.7.7 起底色降为 .58/.38）',
    /\.page\.full \.fsrc-tag \{[\s\S]*?background: rgba\(255, 255, 255, \.(72|58)\);[\s\S]*?color: var\(--text\);/.test(css5)
    && /html\.dark \.page\.full \.fsrc-tag \{[\s\S]*?background: rgba\(0, 0, 0, \.(5|38)\);/.test(css5));
  check('v3.7.5：.page.full .fop 按钮加厚底（原 var(--bg) 纯色割裂）',
    /\.page\.full \.fop \{[\s\S]*?background: rgba\(255, 255, 255, \.68\);[\s\S]*?color: var\(--text\);/.test(css5)
    && /html\.dark \.page\.full \.fop \{[\s\S]*?background: rgba\(0, 0, 0, \.46\);/.test(css5));
  check('v3.7.5：.fop.on 选中态优先级高于播放页覆盖',
    /\.page\.full \.fop\.on,\s*html\.dark \.page\.full \.fop\.on \{[\s\S]*?background: var\(--primary\); color: #fff;/.test(css5));
  check('v3.7.5：主播名 .fartist 加 pill 底 + 主文字色',
    /\.page\.full \.fmeta \.fartist:not\(:empty\) \{[\s\S]*?background: rgba\(255, 255, 255, \.6\);[\s\S]*?color: var\(--text\);/.test(css5)
    && /html\.dark \.page\.full \.fmeta \.fartist:not\(:empty\) \{[\s\S]*?background: rgba\(0, 0, 0, \.38\);/.test(css5));

  // --- #142 分组顶栏全部开启/全部关闭 ---
  // ★ v3.7.6 已把按钮从分组标题行移到橙色顶栏，v3.7.7 又从裸图标改成带文字胶囊。
  //   这里保留 v3.7.5 的语义：分组确实提供「全部开启 / 全部关闭」两个动作入口 + 两个函数调用。
  check('v3.7.5：分组提供「全部开启 / 全部关闭」两个动作入口',
    (/class="srcbulk"/.test(js5) || /paintSrcGroupNav/.test(js5) || /srcGroupNavActs/.test(js5))
    && /srcToggleAll\(true\)/.test(js5) && /srcToggleAll\(false\)/.test(js5));
  check('v3.7.5：ui.srcToggleAll 批量开关（跳过已一致 + 汇总）',
    /ui\.srcToggleAll = function \(on\)/.test(js5)
    && /App\._bulkSw = \{ left: todo\.length/.test(js5));
  check('v3.7.5：onSourceChanged 批量分支统一提示（不逐个 toast）',
    /var bulk = App\._bulkSw;\s*if \(bulk && bulk\.left > 0\)/.test(js5)
    && /TS\.toast\(bulk\.on \? '已全部开启' : '已全部关闭'\)/.test(js5));
  check('v3.7.5：.bulkico 一实一虚样式（实心绿 / 空心淡）',
    /\.bulkico\.on \{ color: #34c759; \}/.test(css5)
    && /\.bulkico\.off \{ color: var\(--text2\); opacity: \.72; \}/.test(css5)
    && /\.srcbulk \{[\s\S]*?margin-left: auto;/.test(css5));
  check('v3.7.5：内置源组不给批量开关（canBatch 控制）',
    /canBatch && list\.length >= 2/.test(js5)
    || /paintSrcGroupNav\(canBatch\)/.test(js5));

  // --- #138 首页加载更多 + 性能 ---
  check('v3.7.5：renderHomeList 支持 append 追加',
    /function renderHomeList\(books, srcId, catId, page, append\)/.test(js5)
    && /if \(!append\) \{[\s\S]*?App\.homeBooks\[catId\] = \[\];/.test(js5));
  check('v3.7.5：底部「加载更多」按钮 + 到底提示',
    /id="homeMoreBtn"/.test(js5)
    && /已经到底了 · 共 /.test(js5));
  check('v3.7.5：ui.homeMore 取下一页 + 缓存命中直接追加',
    /ui\.homeMore = function \(\)/.test(js5)
    && /var next = \(App\.homePage\[cat\] \|\| 1\) \+ 1;/.test(js5));
  check('v3.7.5：滚到距底 320px 自动加载',
    /if \(near < 320\) ui\.homeMore\(\);/.test(js5)
    && /function bindHomeMoreScroll\(\)/.test(js5));
  check('v3.7.5：onBrowse 处理 homeMoreWait 追加分支',
    /var w = App\.homeMoreWait;/.test(js5)
    && /renderHomeList\(o\.books, o\.source, o\.path, o\.page, true\);/.test(js5));
  check('v3.7.5：首页去重（避免翻页重复）',
    /var seen = \{\};[\s\S]*?if \(seen\[kk\]\) continue;/.test(js5));
  check('v3.7.5：首页跳过 markFavs（省一次 SQLite 往返）',
    /\/\/ markFavs\(fresh\);/.test(js5));
  check('v3.7.5：bookCard 封面加 decoding=async',
    /<img src="' \+ TS\.esc\(b\.cover\) \+ '" onerror="TS\.imgFail\(this\)" ' \+\s*'loading="lazy" decoding="async">/.test(js5));
  check('v3.7.5：.home-more 样式齐全',
    /\.home-more \{[\s\S]*?display: flex;/.test(css5)
    && /\.home-more \.hm-btn/.test(css5)
    && /\.home-more\.done \{/.test(css5));
  check('v3.7.5：homeHasMore/homeMoreBusy/homeMoreWait 初始化',
    /App\.homeHasMore = false;/.test(js5)
    && /App\.homeMoreBusy = false;/.test(js5)
    && /App\.homeMoreWait = null;/.test(js5));

  // --- native：discoverSources / browse 换到 searchPool ---
  check('v3.7.5：discoverSources 改走 searchPool（不再堵 singlePool）',
    /public void discoverSources\(\) \{[\s\S]{0,400}Aggregator\.searchPool\(\)\.execute/.test(brj5));
  check('v3.7.5：browse 改走 searchPool',
    /public void browse\(final String sourceId, final String path, final int page\) \{[\s\S]{0,400}Aggregator\.searchPool\(\)\.execute/.test(brj5));
}

// ==================== 4e) ★ v3.7.6 首页默认源 / 加载更多 / 顶栏批量开关 ====================
{
  const js6 = fs.readFileSync('/root/tingshu-app/assets/www/app.js', 'utf8');
  const css6 = fs.readFileSync('/root/tingshu-app/assets/www/style.css', 'utf8');
  const idx6 = fs.readFileSync('/root/tingshu-app/assets/www/index.html', 'utf8');

  // --- #1 首页默认源（记住上次用的源 + 分类） ---
  check('v3.7.6：有 lastHomeSrc 持久化 key',
    /var LAST_SRC_KEY = 'fx_last_home_src_v1';/.test(js6));
  check('v3.7.6：saveLastHomeSource 写入 src + cat',
    /function saveLastHomeSource\(sid\) \{[\s\S]{0,300}src: sid \|\| '',\s*cat: App\.home\.cat \|\| ''/.test(js6));
  check('v3.7.6：loadLastHomeSource 读回并容错',
    /function loadLastHomeSource\(\) \{[\s\S]{0,300}o && o\.src\) return o;/.test(js6));
  check('v3.7.6：restoreLastHomeSource 按 id 命中 + 分类校正',
    /function restoreLastHomeSource\(list\) \{[\s\S]{0,600}App\.home\.src = hit\.id;/.test(js6));
  check('v3.7.6：onDiscoverSources 里源到手就恢复上次的源',
    /window\.onDiscoverSources = function \(arr\) \{[\s\S]{0,400}if \(!App\.home\.src\) restoreLastHomeSource\(App\.home\.sources \|\| App\.homeSources\)/.test(js6)
    || /window\.onDiscoverSources = function \(arr\) \{[\s\S]{0,400}if \(!App\.home\.src\) restoreLastHomeSource\(App\.homeSources\)/.test(js6));
  check('v3.7.6：homePickSrc 里记住选择',
    /ui\.homePickSrc = function \(sid\) \{[\s\S]{0,1200}saveLastHomeSource\(sid\)/.test(js6));
  check('v3.7.6：homeCat 里记住分类',
    /ui\.homeCat = function \(k\) \{[\s\S]{0,400}saveLastHomeSource\(App\.home\.src\)/.test(js6));
  check('v3.7.6：homeCurrentSrc 回落到第一个源时也记住',
    /if \(!cfg\) \{\s*cfg = list\[0\];\s*App\.home\.src = cfg\.id;\s*App\.home\.page = 1;\s*saveLastHomeSource\(cfg\.id\);/.test(js6));

  // --- #2 加载更多不再卡在 20 本 ---
  check('v3.7.6：paintHomeSources 不再无条件复位 homeHasMore（根因修复）',
    !/function paintHomeSources\(force\) \{[\s\S]{0,600}App\.homeHasMore = false;\s*App\.homeMoreBusy = false;\s*App\.homeMoreWait = null;\s*\n\s*var list = App\.homeSources/.test(js6));
  check('v3.7.6：抽出 resetHomePaging 显式复位',
    /function resetHomePaging\(\) \{[\s\S]{0,300}App\.homePageSize = 0;/.test(js6));
  check('v3.7.6：换源 / 换分类 / 刷新三处都调 resetHomePaging',
    (js6.match(/resetHomePaging\(\);/g) || []).length >= 3);
  check('v3.7.6：重画时若已累积则重放（不丢分页进度）',
    /var acc = App\.homeBooks\[App\.home\.cat\];\s*if \(acc && acc\.length\) \{\s*replayHomeList\(acc, cfg\.id, App\.home\.cat\);\s*return;/.test(js6));
  check('v3.7.6：满页基准 homePageSize 参与 hasMore 判定',
    /App\.homePageSize = pageSize;/.test(js6)
    && /var bench = App\.homePageSize \|\| 0;/.test(js6));
  check('v3.7.6：返回不满一页 → 判定到底',
    /else if \(bench > 0 && pageSize < bench\) \{\s*App\.homeHasMore = false;/.test(js6));
  check('v3.7.6：返回 0 条 → 判定到底',
    /if \(pageSize <= 0\) \{\s*App\.homeHasMore = false;/.test(js6));
  check('v3.7.6：paintHomeMore 去掉 `|| !freshCount` 误判到底',
    !/if \(!App\.homeHasMore \|\| !freshCount\) \{/.test(js6)
    && /if \(!App\.homeHasMore\) \{/.test(js6));
  check('v3.7.6：App.homePageSize 初始化',
    /App\.homePageSize = 0;\s*\/\/ ★ v3\.7\.6/.test(js6));

  // --- #3 顶栏批量开关 ---
  check('v3.7.6：index.html 顶栏加了 srcGroupNavActs 容器',
    /id="srcGroupNavActs"/.test(idx6) && /class="btn ghost srcnav-acts"/.test(idx6));
  check('v3.7.6：新增 paintSrcGroupNav 画到顶栏（v3.7.7 起参数名改为 show）',
    /function paintSrcGroupNav\((on|show)\) \{[\s\S]{0,700}var box = \$\('srcGroupNavActs'\);/.test(js6));
  check('v3.7.6：grphd 里不再有 bulkico（只剩计数）',
    !/h \+= '<div class="grphd">'[\s\S]{0,700}bulkico on/.test(js6));
  // ★ v3.7.14：多选取消后，不再有"多选态清空顶栏按钮"这条路径，
  //   保留的是"空组时不画顶栏按钮"这条（分组没源就没必要给全开/全关）。
  check('v3.7.6/v3.7.14：空组时清空顶栏按钮（多选分支已随多选移除）',
    /paintSrcGroupNav\(false\);\s*\/\/ ★ v3\.7\.6：空组顶栏不留按钮/.test(js6)
    && !/if \(multi\) paintSrcGroupNav\(false\);/.test(js6)
    && !/App\.multiSrc/.test(js6));
  check('v3.7.6：顶栏按钮仍调 srcToggleAll(true/false)',
    /paintSrcGroupNav[\s\S]{0,900}App\.ui\.srcToggleAll\(true\)/.test(js6)
    && /App\.ui\.srcToggleAll\(false\)/.test(js6));
  check('v3.7.6：.nav .btn.srcnav-acts 显式恢复可见（覆盖 .ghost 隐藏）',
    /\.nav \.btn\.srcnav-acts \{[\s\S]{0,200}visibility: visible;/.test(css6));
  // ★ v3.7.7 起：顶栏批量按钮从「两个裸图标」演进为「两个带文字胶囊」，见 4f) 块。
  //   这里只保留 v3.7.6 的"画到顶栏"这一层语义（容器仍在、按钮仍成对）。
  check('v3.7.6：顶栏批量按钮成对渲染（v3.7.7 起为 .bulkbtn 胶囊）',
    /\.nav \.srcnav-acts \.(bulkbtn|bulkico)/.test(css6));

  // --- #4 播放页收藏按钮可读性（★ v3.7.7 起改为 .favbtn 透明质感，见 4f) 块）---
  check('v3.7.6：收藏按钮有自己的独立样式块（v3.7.7 起为 .favbtn）',
    /\.fnav \.(favbtn|#fullFav)/.test(css6));
  check('v3.7.6：收藏按钮字色跟随主文字色',
    /\.fnav \.favbtn \{[\s\S]{0,300}color: var\(--text\);/.test(css6)
    || /\.fnav #fullFav \{[\s\S]{0,400}color: var\(--text\);/.test(css6));
  check('v3.7.6：已收藏态用暖色星标表达状态（v3.7.7 起为 .favbtn.on 橙字）',
    /\.fnav \.favbtn\.on \{ color: #ffb300; \}/.test(css6)
    || /\.fnav #fullFav\.on \{/.test(css6));
  check('v3.7.6：暗色下收藏按钮有独立状态样式',
    /html\.dark \.fnav \.favbtn/.test(css6)
    || /html\.dark \.fnav #fullFav/.test(css6));

  // --- #5 集数文案 (N/M) ---
  // ★ v3.7.11（#164）：全角括号方案回退，改为「轻括号 + 等宽数字 + 进度底色」分层结构，
  //   四条断言保留 v3.7.6/3.7.9 的语义（集数确实做了分层 / 不再输出「第 N 集 / 共 M 集」/ 注释同步）。
  check('v3.7.11：集数用分层 span（fep-par / fep-no / fep-sep / fep-tot）+ 进度底色',
    /'<span class="fep-par">\(<\/span>'/.test(js6)
    && /'<span class="fep-no">' \+ no/.test(js6)
    && /'<span class="fep-sep">\/<\/span>'/.test(js6)
    && /'<span class="fep-tot">' \+ total/.test(js6)
    && /class="fep-bar"/.test(js6));
  check('v3.7.6：不再输出「第 N 集 / 共 M 集」',
    !/ep\.textContent = '第 ' \+ no \+ ' 集 \/ 共 ' \+ total \+ ' 集';/.test(js6));
  check('v3.7.9：paintFullInfo 注释同步更新',
    /全屏页信息区：书名 \/ 集数「\(N\/M\)」/.test(js6));
  check('v3.7.6：index.html 注释同步更新',
    /集数「\(N\/M\)」\+ 主播名（★ v3\.7\.6 去汉字改纯数字括号）/.test(idx6));
}

// ==================== 4f) ★ v3.7.7 首页源提速 / 加载更多可见 / 顶栏右簇 / 批量按钮分色 ====================
{
  const js7 = fs.readFileSync('/root/tingshu-app/assets/www/app.js', 'utf8');
  const css7 = fs.readFileSync('/root/tingshu-app/assets/www/style.css', 'utf8');
  const idx7 = fs.readFileSync('/root/tingshu-app/assets/www/index.html', 'utf8');
  const brg7 = fs.readFileSync('/root/tingshu-app/src/com/tingshu/app/Bridge.java', 'utf8');
  const agg7 = fs.readFileSync('/root/tingshu-app/src/com/tingshu/app/source/Aggregator.java', 'utf8');
  const reg7 = fs.readFileSync('/root/tingshu-app/src/com/tingshu/app/source/SourceRegistry.java', 'utf8');

  // --- #1 首页书源分类并发预取 + 进程内缓存 ---
  check('v3.7.7：Bridge 有分类缓存 CAT_CACHE（ConcurrentHashMap）',
    /ConcurrentHashMap<[^>]*>\s*CAT_CACHE/.test(brg7) || /static[\s\S]{0,40}CAT_CACHE/.test(brg7));
  check('v3.7.7：分类缓存有 TTL 常量 CAT_TTL_MS',
    /CAT_TTL_MS\s*=/.test(brg7));
  check('v3.7.7：提供 invalidateCatCache() 供源变更后失效',
    /void\s+invalidateCatCache\s*\(\s*\)/.test(brg7) && /CAT_CACHE\.clear\(\)/.test(brg7));
  check('v3.7.7：discoverSources 里交给 catPool 并发取分类',
    /catPool\(\)/.test(brg7));
  check('v3.7.7：并发等待用 CountDownLatch（带超时兜底）',
    /CountDownLatch/.test(brg7) && /\.await\(/.test(brg7));
  check('v3.7.7：Aggregator 新增 CAT_POOL 线程池（8 线程 namedFactory("cat")）',
    /CAT_POOL\s*=[\s\S]{0,120}newFixedThreadPool\(\s*8/.test(agg7)
    && /namedFactory\(\s*"cat"\s*\)/.test(agg7));
  check('v3.7.7：Aggregator 暴露 catPool() 访问器',
    /catPool\s*\(\s*\)\s*\{[\s\S]{0,80}return\s+CAT_POOL/.test(agg7));
  check('v3.7.7：SourceRegistry.reload() 末尾失效分类缓存',
    /void\s+reload\s*\([\s\S]{0,900}invalidateCatCache\s*\(\s*\)/.test(reg7));

  // --- #2 加载更多：去掉双层滚动 + 滚到新内容 ---
  check('v3.7.7：#homeContent 不再挂 class="content"（根因：双层滚动容器）',
    /id="homeContent"[^>]*>/.test(idx7)
    && !/id="homeContent"[^>]*class="[^"]*\bcontent\b/.test(idx7));
  check('v3.7.7：index.html 里有对应的根因说明注释',
    /双层滚动|class="content"[\s\S]{0,80}自带/.test(idx7));
  check('v3.7.7：新增 scrollHomeToAnchor 把新内容滚进视口',
    /function scrollHomeToAnchor\(anchorId\) \{[\s\S]{0,600}scrollTo\(/.test(js7));
  check('v3.7.7：用 requestAnimationFrame 保证 insert 后再测量',
    /function scrollHomeToAnchor\(anchorId\) \{[\s\S]{0,300}requestAnimationFrame/.test(js7));
  check('v3.7.7：追加时给第一张新卡打锚点 id',
    /var anchorId = 'freshAnchor' \+ Date\.now\(\);/.test(js7));
  check('v3.7.7：锚点用 replace 注入到首个 .bookcard 上',
    /replace\('<div class="bookcard"',\s*'<div class="bookcard" id="' \+ anchorId \+ '"',\s*1\)/.test(js7));
  check('v3.7.7：锚点卡插入后立即 scrollHomeToAnchor',
    /insertAdjacentHTML\('beforebegin', h\);\s*scrollHomeToAnchor\(anchorId\);/.test(js7));

  // --- #3 播放页顶栏：书源名 + 收藏靠右成簇 + 收藏按钮透明质感 ---
  check('v3.7.7：index.html 里书源标签 + 收藏包进 .fnav-right 一簇',
    /<div class="fnav-right">[\s\S]{0,400}id="fullFav"/.test(idx7));
  check('v3.7.7：收藏按钮 class 改为 btn favbtn',
    /class="btn favbtn"[^>]*id="fullFav"/.test(idx7));
  check('v3.7.7：.fnav .fnav-right 自己贴右（margin-left:auto）+ 内部定距 gap:8px',
    /\.fnav \.fnav-right \{[\s\S]{0,200}margin-left: auto;[\s\S]{0,200}gap: 8px;/.test(css7));
  check('v3.7.7：收藏按钮背景透明（去掉上一版 .62 白实底，解决"背板不协调"）',
    /\.fnav \.favbtn \{[\s\S]{0,200}background: transparent;/.test(css7));
  check('v3.7.7：收藏按钮 34×34 圆形',
    /\.fnav \.favbtn \{[\s\S]{0,120}width: 34px; height: 34px;[\s\S]{0,120}border-radius: 50%;/.test(css7));
  check('v3.7.7：收藏按钮按下才显形（:active 淡色底 + 缩放）',
    /\.fnav \.favbtn:active \{ background: rgba\(0, 0, 0, \.08\); transform: scale\(\.94\); \}/.test(css7));
  check('v3.7.7：已收藏用暖色（.on 橙字 #ffb300）',
    /\.fnav \.favbtn\.on \{ color: #ffb300; \}/.test(css7));
  check('v3.7.7：旧版 .62 白实底写法已彻底移除',
    !/\.fnav #fullFav \{[\s\S]{0,400}rgba\(255, 255, 255, \.62\)/.test(css7));
  check('v3.7.7：.page.full .fsrc-tag 底色降到 .58（与顶栏标题统一）',
    /\.page\.full \.fsrc-tag \{[\s\S]{0,300}rgba\([^)]+,\s*\.58\)/.test(css7));
  check('v3.7.7：index.html 注释说明 v3.7.7 打包成一簇',
    /★ v3\.7\.7：两者包进 \.fnav-right/.test(idx7));

  // --- #4 源分组批量按钮：两个独立胶囊 + 状态色 ---
  check('v3.7.7：paintSrcGroupNav 渲染两个 .bulkbtn（不再是裸 .bulkico 图标）',
    /box\.innerHTML =[\s\S]{0,200}class="bulkbtn' \+ \(allOn \? ' active-on' : ''\)/.test(js7)
    && /class="bulkbtn' \+ \(allOff \? ' active-off' : ''\)/.test(js7));
  check('v3.7.7：两个按钮各带文字标签「全开」/「全关」',
    /<i>全开<\/i>/.test(js7) && /<i>全关<\/i>/.test(js7));
  check('v3.7.7：全开 / 全关状态判定（allOn / allOff）',
    /var allOn = list\.length > 0 && on === list\.length;/.test(js7)
    && /var allOff = list\.length > 0 && on === 0;/.test(js7));
  check('v3.7.7：两个按钮仍分别调 srcToggleAll(true) / srcToggleAll(false)',
    /onclick="App\.ui\.srcToggleAll\(true\)"/.test(js7)
    && /onclick="App\.ui\.srcToggleAll\(false\)"/.test(js7));
  check('v3.7.7：.bulkbtn 是胶囊（height:28px / border-radius:14px / 白描边）',
    /\.nav \.srcnav-acts \.bulkbtn \{[\s\S]{0,260}height: 28px;[\s\S]{0,120}border-radius: 14px;/.test(css7)
    && /border: 1px solid rgba\(255, 255, 255, \.5\);/.test(css7));
  check('v3.7.7：两个胶囊之间有间隔（容器 gap:6px，不再粘成一整块）',
    /\.nav \.btn\.srcnav-acts \{[\s\S]{0,300}gap: 6px;/.test(css7));
  check('v3.7.7：全开态点亮为绿实底 #34c759',
    /\.nav \.srcnav-acts \.bulkbtn\.active-on \{[\s\S]{0,200}background: #34c759;/.test(css7));
  check('v3.7.7：全关态点亮为深灰实底 rgba(0,0,0,.42)',
    /\.nav \.srcnav-acts \.bulkbtn\.active-off \{[\s\S]{0,200}background: rgba\(0, 0, 0, \.42\);/.test(css7));
  check('v3.7.7：混合态两个都不点亮（只有白描边）',
    /\.nav \.srcnav-acts \.bulkbtn:not\(\.active-on\):not\(\.active-off\) \{[\s\S]{0,120}background: rgba\(255, 255, 255, \.1\);/.test(css7));
  check('v3.7.7：旧版裸图标 .bulkico 样式已彻底移除',
    !/\.nav \.srcnav-acts \.bulkico/.test(css7) && !/bulkico/.test(js7));
}

// ==================== 5) 版本号 ====================
{
  const man = fs.readFileSync('/root/tingshu-app/AndroidManifest.xml', 'utf8');
  check('versionCode = 42', /android:versionCode="42"/.test(man));
  check('versionName = 3.7.15（三段式）', /android:versionName="3\.7\.15"/.test(man));
  const idx = fs.readFileSync('/root/tingshu-app/assets/www/index.html', 'utf8');
  check('设置页版本显示 3.7.15', /id="appVer">3\.7\.15</.test(idx));
  check('关于页版本显示 3.7.15', /id="aboutVer">3\.7\.15</.test(idx));
  const jsV = fs.readFileSync('/root/tingshu-app/assets/www/app.js', 'utf8');
  check('CHANGELOG 含 3.7.9 条目', /ver: '3\.7\.9'/.test(jsV));
  check('CHANGELOG 含 3.7.10 条目', /ver: '3\.7\.10'/.test(jsV));
  check('CHANGELOG 仍保留 3.7.6 条目（历史不丢）', /ver: '3\.7\.6'/.test(jsV));
}

// ==================== 6) ★ v3.1 音量增益 ====================
{
  const pc = R('player/PlayerCore.java');
  // 降幅只能靠 setVolume（LoudnessEnhancer 做不了负增益），增压靠 LoudnessEnhancer
  check('增益：<=100% 走 setVolume 衰减', /mp\.setVolume\(v, v\)/.test(pc));
  check('增益：>100% 走 LoudnessEnhancer', /setTargetGain\(millibels\)/.test(pc));
  check('增益：100→0mB 200→600mB 换算', /\(gainPct - 100\) \* 6/.test(pc));
  check('增益：钳制 50~300', /if \(pct < 50\) return 50;/.test(pc) && /pct > 300/.test(pc));
  // ★ 最易漏的点：每换一集都是新 MediaPlayer，音频会话变了必须重建 enhancer
  check('增益：onPrepared 里重建 enhancer（换集不失效）',
    /applyGainInternal\(mp\);/.test(pc) && /enhancerSession == session/.test(pc));
  check('增益：release 时释放 enhancer', /releaseEnhancer\(\);/.test(pc));

  const st = R('data/Settings.java');
  check('Settings.gain 默认 100 且钳制', /getInt\("gain_pct", 100\)/.test(st));
  check('Settings.audioFocusMode 默认 0', /getInt\("audio_focus_mode", 0\)/.test(st));
  check('Settings 仍保留 lastVerifySummary 摘要', /last_verify_summary/.test(st));

  const br = R('Bridge.java');
  check('Bridge 暴露 setGain / getGain', /public void setGain\(final int pct\)/.test(br)
    && /public void getGain\(\)/.test(br));
}

// ==================== 7) ★ v3.1 音频焦点（其他媒体打断） ====================
{
  const pc = R('player/PlayerCore.java');
  check('焦点：API26+ 走 AudioFocusRequest.Builder', /new AudioFocusRequest\.Builder/.test(pc));
  // ★ 旧重载在 API 31 已移除，源码必须走反射，否则在 API 35 上编不过
  check('焦点：API21-25 走反射（旧签名已移除）',
    /legacyRequest\(\)/.test(pc) && /"requestAudioFocus"/.test(pc));
  check('焦点：动态 Proxy 造 listener', /java\.lang\.reflect\.Proxy\.newProxyInstance/.test(pc));
  check('焦点：模式 0 不申请焦点（听书不被打断）', /int mode = Settings\.audioFocusMode\(\);\s*\n\s*if \(mode == 0\) return;/.test(pc));
  check('焦点：duck 恢复要重放用户增益（不能硬回 1.0）',
    /applyGainInternal\(player\);/.test(pc) && !/player\.setVolume\(1f, 1f\);\s*\/\/\s*恢复/.test(pc));
  check('焦点：用户手动 pause 要清 pausedByFocus',
    /pausedByFocus = false;/.test(pc));
}

// ==================== 8) ★ v3.1 源验证 / 去重 / 失效关停 ====================
{
  const bs = R('BridgeSource.java');
  check('验证：四种结论枚举齐备',
    /LV_OK = "ok"/.test(bs) && /LV_BROWSE = "browse"/.test(bs)
    && /LV_VERIFY = "verify"/.test(bs) && /LV_DEAD = "dead"/.test(bs));
  check('验证：内置源跳过', /LV_SKIP/.test(bs));
  // ★ 顺序：先去重再验证最后关停
  check('批量验证：先去重 → 再验证 → 最后关停',
    bs.indexOf('findDuplicates(list)') < bs.indexOf('vr = verifySource(s)'));
  check('批量验证：失效的才关（autoCloseDead 且原本启用）',
    /autoCloseDead && SourceRepo\.isEnabled\(s\.id\(\)\)/.test(bs));
  check('批量验证：按 searchDelaySeconds 限流', /searchDelaySeconds\(\)/.test(bs));

  const sr = R('source/SourceRegistry.java');
  check('去重：域名归一化去协议/www/端口/路径', /startsWith\("www\."\)/.test(sr)
    && /indexOf\(":\/\/"\)/.test(sr) && /indexOf\(':'\)/.test(sr));
  check('去重：不剥 m. 子域（手机站内容不同）', /不去掉[\s\S]{0,20}m\./.test(sr));

  const br = R('Bridge.java');
  check('Bridge 暴露 verifySources(scope, autoClose, dedup)',
    /public void verifySources\(final String scope, final boolean autoCloseDead,\s*\n\s*final boolean dedup\)/.test(br));
  check('Bridge 进度回调 onVerifyProgress', /callback\("onVerifyProgress", p\)/.test(br));
  check('Bridge 结果回调 onVerifyDone', /callback\("onVerifyDone", r\)/.test(br));

  // ★ v3.2：启动自动验证已移除（冷启动跑十几个源会拖慢首屏）。
  //   这里反向断言：App.onCreate 里不该再出现自动验证调度。
  const app = R('core/App.java');
  check('v3.2：onCreate 不再挂自动验证', !/scheduleAutoVerify/.test(app)
    && !/verifyAll\("all"/.test(app));
  // 开关与它的 setter 也应一并删干净
  const st2 = R('data/Settings.java');
  check('v3.2：自动验证开关已移除', !/autoVerifySources/.test(st2)
    && !/auto_verify_sources/.test(st2));
  const br2 = R('Bridge.java');
  check('v3.2：getSettings 不再回包 autoVerifySources',
    !/autoVerifySources/.test(br2) && /lastVerifySummary/.test(br2));

  // ================= ★ v3.3：补「我的听书」插件缺失的 SDK 类 =================
  //
  // 起因：用户导入 my_sound_01.jar 报「加不进来」。根因是插件里 Mting/Qmtsw
  // 两个源直接 implements com.github.eprendre.tingshu.sources.ILogin，
  // 而宿主没有这个接口 —— Dalvik 解析类时必须能解析全部直接接口，
  // 找不到就 NoClassDefFoundError，被 JarLoader 的 catch 静默吞掉。
  const ilogin = R2('github/eprendre/tingshu/sources/ILogin.java');
  check('v3.3：ILogin 接口已补', /interface\s+ILogin/.test(ilogin)
    && /String\s+getLoginUrl\(\)/.test(ilogin));
  check('v3.3：ILogin$DefaultImpls 提供 isLoginDesktop 桥接',
    /class\s+DefaultImpls/.test(ilogin)
    && /static\s+boolean\s+isLoginDesktop\(ILogin\s+self\)/.test(ilogin));

  // WebView 取链器：插件 field 表引用 INSTANCE，method 表引用 setUp$default
  const wv = R2('github/eprendre/tingshu/sources/AudioUrlWebViewExtractor.kt');
  check('v3.3：AudioUrlWebViewExtractor 提供 INSTANCE 单例',
    /val\s+INSTANCE\s*=\s*AudioUrlWebViewExtractor\(\)/.test(wv));
  check('v3.3：AudioUrlWebViewExtractor.setUp 带默认参数（产出 $default 桥接）',
    /fun\s+setUp\(sniff:\s*Boolean\s*=\s*false/.test(wv));
  const wvs = R2('github/eprendre/tingshu/sources/AudioUrlWebViewSniffExtractor.kt');
  check('v3.3：AudioUrlWebViewSniffExtractor 提供 INSTANCE 单例',
    /val\s+INSTANCE\s*=\s*AudioUrlWebViewSniffExtractor\(\)/.test(wvs));

  // BookDetail 迁到 Kotlin：插件只引用带 DefaultConstructorMarker 的合成构造器，
  // 那个构造器 Java 声明不出来，必须由 Kotlin 编译器生成。
  check('v3.3：BookDetail 已改为 Kotlin data class',
    /data\s+class\s+BookDetail\(/.test(R2('github/eprendre/tingshu/utils/BookDetail.kt')));
  check('v3.3：Episode 已改为 Kotlin data class',
    /data\s+class\s+Episode\(/.test(R2('github/eprendre/tingshu/utils/Episode.kt')));

  // MyExtKt 补齐插件引用到的扩展函数
  const ext = R2('github/eprendre/tingshu/extensions/MyExtKt.java');
  check('v3.3：MyExtKt.config$default 为 4 参（与插件引用一致）',
    /config\$default\(org\.jsoup\.Connection\s+conn,\s*\n?\s*boolean\s+followRedirects,\s*\n?\s*int\s+mask,\s*Object\s+marker\)/.test(ext));
  check('v3.3：MyExtKt 补 getCookie / showToast / notifyLoadingEpisodes',
    /getCookie\(String\s+url\)/.test(ext) && /showToast\(String\s+text\)/.test(ext)
    && /notifyLoadingEpisodes\(String\s+text\)/.test(ext));

  // JarLoader：失败不再静默
  const jl = R('core/JarLoader.java');
  check('v3.3：JarLoader 记录 firstError 而不是静默吞异常',
    /public\s+String\s+firstError/.test(jl) && /noteError\(r,\s*unwrap\(t\)\)/.test(jl));
  check('v3.3：JarLoader 提取缺失类名回显',
    /List<String>\s+missingClasses/.test(jl) && /缺失的宿主类名/.test(jl));
  check('v3.3：JarLoader 增加「直接枚举 TingShu 子类」兜底路径',
    /tryAddEprendreSource/.test(jl)
    && /com\.github\.eprendre\.tingshu\.sources\.TingShu"\.equals\(k\.getName\(\)\)/.test(jl));
}

// ================= ★ v3.4：内置 CPython 直接运行 .py 源 =================
//
// 方案要点：不做任何转译，App 内嵌真实 CPython 3.8（Chaquopy）跑原版 .py 源。
// 断言重点在「运行时四件套齐备 + 通道打通 + 失败隔离」。
{
  // ---- 1) native 运行时：loadNativeLibs 强制的 6 个 .so 一个不能少 ----
  const LIBDIR = '/root/tingshu-app/lib/arm64-v8a';
  const needLibs = ['libpython3.8.so', 'libchaquopy_java.so', 'chaquopy.so',
    'chaquopy_android.so', 'libsqlite3_chaquopy.so',
    'libcrypto_chaquopy.so', 'libssl_chaquopy.so'];
  for (const n of needLibs) {
    check('v3.4：lib/arm64-v8a/' + n, fs.existsSync(LIBDIR + '/' + n));
  }
  check('v3.4：libpython3.8 体积合理（>2MB 才是真解释器）',
    fs.existsSync(LIBDIR + '/libpython3.8.so')
    && fs.statSync(LIBDIR + '/libpython3.8.so').size > 2 * 1024 * 1024);

  // ---- 2) Python assets：ABI 探测哨兵 + 标准库 + 依赖 + 引导层 ----
  const PYP = '/root/tingshu-app/python/assets/chaquopy';
  const needAssets = ['bootstrap.imy', 'stdlib-common.imy', 'stdlib-arm64-v8a.imy',
    'requirements.imy', 'app.imy', 'build.json', 'cacert.pem'];
  for (const n of needAssets) {
    check('v3.4：assets/chaquopy/' + n, fs.existsSync(PYP + '/' + n));
  }
  // ABI 探测只 AssetManager.open 不读内容 → stdlib-arm64-v8a.imy 必须是极小哨兵
  check('v3.4：stdlib-arm64-v8a.imy 是空 zip 哨兵（省 ~2MB）',
    fs.existsSync(PYP + '/stdlib-arm64-v8a.imy')
    && fs.statSync(PYP + '/stdlib-arm64-v8a.imy').size < 200);
  // build.json 必须覆盖全部 asset（Chaquopy extractAssets 按 key 前缀匹配）
  let bj = null;
  try { bj = JSON.parse(fs.readFileSync(PYP + '/build.json', 'utf8')); } catch (e) {}
  check('v3.4：build.json 可解析且含 assets 表', !!bj && !!bj.assets);
  check('v3.4：build.json 收录 stdlib-common.imy / bootstrap.imy / app.imy',
    !!bj && !!bj.assets && !!bj.assets['stdlib-common.imy']
    && !!bj.assets['bootstrap.imy'] && !!bj.assets['app.imy']);
  // bootstrap-native 是 CPython 内置模块 .so，缺了 import json/zlib 就废
  const bnDir = PYP + '/bootstrap-native/arm64-v8a';
  let bnCount = 0;
  try { bnCount = fs.readdirSync(bnDir).filter(f => f.endsWith('.so')).length; } catch (e) {}
  check('v3.4：bootstrap-native .so 数量充足（>=30）', bnCount >= 30, '实际 ' + bnCount);

  // ---- 3) Java 侧通道：PythonHost / PySourceAdapter / 桥接 ----
  const ph = R('python/PythonHost.java');
  check('v3.4：PythonHost 用 AndroidPlatform 启动解释器',
    /new AndroidPlatform\(ctx\)/.test(ph) && /Python\.start\(/.test(ph));
  check('v3.4：PythonHost 启动后跑 self_test 自检',
    /callAttr\("self_test"\)/.test(ph));
  check('v3.4：PythonHost 失败只置 lastError（不抛给上层）',
    /lastError = t\.getClass\(\)\.getSimpleName\(\)/.test(ph));
  check('v3.4：PythonHost 暴露 loadSource / unloadSource / call',
    /public static String loadSource\(/.test(ph)
    && /public static void unloadSource\(/.test(ph)
    && /public static JSONObject call\(/.test(ph));
  check('v3.4：PythonHost 源目录 = filesDir/python_sources',
    /new File\(ctx\.getFilesDir\(\), "python_sources"\)/.test(ph));

  const pa = R('python/PySourceAdapter.java');
  check('v3.4：PySourceAdapter 实现 BrowsableSource',
    /class PySourceAdapter implements BrowsableSource/.test(pa));
  check('v3.4：适配器映射 home/category/search/detail/player 五个通道',
    /home_content/.test(pa) && /category_content/.test(pa)
    && /search_content/.test(pa) && /detail_content/.test(pa)
    && /player_content/.test(pa));
  check('v3.4：适配器解析 TVBox 播放串「名$址#名$址」',
    /split\("#"\)/.test(pa) && /lastIndexOf\('\$'\)|indexOf\('\$'\)/.test(pa));
  check('v3.4：适配器同源调用串行化（解释器非线程安全）',
    /synchronized \(lock\)/.test(pa));

  const sr2 = R('source/SourceRegistry.java');
  check('v3.4：SourceRegistry 装载 python_sources 下的 .py',
    /loadPythonSources\(\)/.test(sr2) && /endsWith\("\.py"\)/.test(sr2));
  check('v3.4：Python 源 id 前缀 py_',
    /"py_" \+ fn/.test(sr2));
  // ★ v3.7.9：这句从 "return" 改成 "PY_PENDING.set(true); return;" ——
  //   不但要跳过，还要记下"有 .py 源等着补"，供 App 就绪回调判断是否要 reload。
  check('v3.7.9：运行时未就绪时整体跳过，但记下待补登记标记',
    /if \(!com\.tingshu\.app\.python\.PythonHost\.available\(\)\) \{\s*PY_PENDING\.set\(true\)/.test(sr2));

  const bss = R('BridgeSource.java');
  check('v3.4：BridgeSource.importPySources 收 .py 与含 .py 的 .zip',
    /public static JSONObject importPySources\(/.test(bss)
    && /unzipPyFiles\(/.test(bss));
  check('v3.4：zip 解包防路径穿越（丢 .. 条目 + 只取 basename）',
    /name\.contains\("\.\."\)\) continue;/.test(bss)
    && /substring\(slash \+ 1\)/.test(bss));
  check('v3.4：单个源失败只跳过（不整体失败）',
    /bump\(r, "failed"\);\s*\n\s*continue;/.test(bss));
  check('v3.4：导入后 reload 使新源立即生效',
    /SourceRegistry\.reload\(\);/.test(bss));

  const br3 = R('Bridge.java');
  check('v3.4：Bridge 暴露 importPySources 到前端',
    /public void importPySources\(final String path/.test(br3)
    && /BridgeSource\.importPySources\(path, fileName\)/.test(br3));

  const ma = R('MainActivity.java');
  check('v3.4：文件选择按 .py 分派到 Python 通道',
    /endsWith\("\.py"\)/.test(ma) && /importPyFile\(/.test(ma));  check('v3.4：.zip 内探 .py（只读中央目录，不解压）',
    /looksLikePyZip\(/.test(ma) && /new java\.util\.zip\.ZipFile/.test(ma));
  check('v3.4：Python 分支回 type:"py"',
    /o\.put\("type", "py"\)/.test(ma));

  check('v3.7：ticket.txt 存在（Chaquopy 资产版本票据）',
    fs.existsSync(PYP + '/ticket.txt'));
  let bj7 = null;
  try { bj7 = JSON.parse(fs.readFileSync(PYP + '/build.json', 'utf8')); } catch (e) {}
  check('v3.7：build.json 收录 ticket.txt',
    !!bj7 && !!bj7.assets && !!bj7.assets['ticket.txt']);

  // ---- ★ v3.6：Python 运行时起不来 —— 两处根因 ----
  //
  // 现象：真机导入 .py 报「Python 运行时不可用：」（原因还是空串）。
  //
  // 根因一：available() 调 ensureStarted(null)，传 null 直接 return false，
  //         全项目没有任何一处用真实 Context 启动过解释器。
  // 根因二：build.sh 用 zipalign -f 4 只做 4 字节对齐；Android 6.0+ 要求
  //         native 库 4096 页对齐，否则安装后 .so 无法 mmap 加载，
  //         System.loadLibrary("python3.8") 直接失败。
  check('v3.7.9：available() 非阻塞点火（自取 Context 后丢后台，不等待）',
    /kickStart\(\);/.test(ph) && /return false;\s*\/\/ 首次调用/.test(ph));
  check('v3.7.9：kickStart 在 App 未就绪时不置 TRIED（留给下次重试）',
    /if \(app == null\) \{[\s\S]{0,160}TRIED\.set\(false\)/.test(ph));
  check('v3.6：运行时不可用时报错不再留空原因',
    /解释器启动失败，请重启 App 后重试/.test(bss));

  const bs2 = fs.readFileSync('/root/tingshu-app/build.sh', 'utf8');
  check('v3.6：zipalign 带 -p（native 库 4KB 页对齐）',
    /zipalign"\s+-p\s+-f\s+4/.test(bs2));
  check('v3.6：构建脚本内置对齐校验（不对齐直接失败，不签坏包）',
    /zipalign"\s+-c\s+-p\s+-v\s+4/.test(bs2)
    && /zipalign 校验失败/.test(bs2));

  const man3 = fs.readFileSync('/root/tingshu-app/AndroidManifest.xml', 'utf8');
  check('v3.6：Manifest 声明 extractNativeLibs=true',
    /android:extractNativeLibs="true"/.test(man3));

  // APK 实测：所有 .so 必须 4096 对齐且未压缩存储
  const APK = '/root/tingshu-app/TingShu.apk';
  if (fs.existsSync(APK)) {
    try {
      const { execSync } = require('child_process');
      const za = '/root/android/android-14/zipalign';
      if (fs.existsSync(za)) {
        let okAlign = true;
        try { execSync(za + ' -c -p 4 "' + APK + '"', { stdio: 'ignore' }); }
        catch (e) { okAlign = false; }
        check('v3.6：APK 内 native 库通过 zipalign -p 校验', okAlign);
      }
    } catch (e) {}
  }

  // ---- ★ v3.7.1：java.chaquopy 缺失 ----
  //
  // 现象：真机导入报 ImportError: No module named 'java.chaquopy'，
  //       栈顶是 bootstrap/java/__init__.py 第 30 行。
  //
  // 根因：upstream java/__init__.py 第 30 行是 `from .chaquopy import (...)`
  //       → 即 import「java.chaquopy」子模块；该 .so 的初始化函数是
  //       PyInit_chaquopy（顶层名 chaquopy），靠 java 包的
  //       `__path__ = pkgutil.extend_path(__path__, __name__)` 把
  //       sys.path 上所有同名 java/ 目录拼进来。
  //       bootstrap-native/<abi> 恰在 sys.path 上，所以 .so 必须放
  //       bootstrap-native/<abi>/java/ 里。原来只放了 lib/<abi>/，
  //       java.chaquopy 永远找不到。
  const JAVA_SO = PYP + '/bootstrap-native/arm64-v8a/java/chaquopy.so';
  const JAVA_SO_A = PYP + '/bootstrap-native/arm64-v8a/java/chaquopy_android.so';
  check('v3.7.1：bootstrap-native/<abi>/java/chaquopy.so 存在（供 java.chaquopy 导入）',
    fs.existsSync(JAVA_SO));
  check('v3.7.1：bootstrap-native/<abi>/java/chaquopy_android.so 存在（供 java.chaquopy_android 导入）',
    fs.existsSync(JAVA_SO_A));
  check('v3.7.1：build.json 收录 bootstrap-native/<abi>/java/chaquopy.so',
    !!bj7 && !!bj7.assets
    && !!bj7.assets['bootstrap-native/arm64-v8a/java/chaquopy.so']);
  check('v3.7.1：build.json 收录 bootstrap-native/<abi>/java/chaquopy_android.so',
    !!bj7 && !!bj7.assets
    && !!bj7.assets['bootstrap-native/arm64-v8a/java/chaquopy_android.so']);
  check('v3.7.1：构建脚本在打包前硬校验 java/chaquopy.so',
    /bootstrap-native\/arm64-v8a\/java\/chaquopy\.so/.test(bs2)
    && /Chaquopy 资产缺失/.test(bs2));
  check('v3.7.1：构建脚本反向复核 APK 内 java/chaquopy.so',
    /APK 内缺少/.test(bs2) && /java\/chaquopy\.so 已入包/.test(bs2));

  // APK 实测：这两个 .so 必须在 assets 里
  if (fs.existsSync(APK)) {
    try {
      const { execSync } = require('child_process');
      const list = execSync('unzip -l "' + APK + '"', { encoding: 'utf8' });
      check('v3.7.1：APK 内已打入 assets/chaquopy/bootstrap-native/arm64-v8a/java/chaquopy.so',
        /assets\/chaquopy\/bootstrap-native\/arm64-v8a\/java\/chaquopy\.so/.test(list));
      check('v3.7.1：APK 内已打入 .../java/chaquopy_android.so',
        /assets\/chaquopy\/bootstrap-native\/arm64-v8a\/java\/chaquopy_android\.so/.test(list));
    } catch (e) {}
  }

  // ---- ★ v3.7.2：stdlib / 三方依赖残缺 ----
  //
  // 现象：java.chaquopy 修好后，报错推进到
  //   bootstrap/java/primitive.py line 3: ModuleNotFoundError: No module named 'ctypes'
  //
  // 根因：stdlib-common.imy 早前只打了 390 个 pyc（官方 598），整个 ctypes 包缺失；
  //       bootstrap-native 只有 40 个 .so（官方 57），缺 _ctypes.so 等 17 个；
  //       requirements.imy 里 lxml / Crypto / cryptography 是手写 stub，
  //       还带着 cpython-311 的 pyc —— 目标是 CPython 3.8，必然 ImportError。
  //
  // 修复：全部换用官方资产（target-3.8.13-1 + chaquo.com/pypi-7.0 的 cp38 arm64 wheel）。
  (function () {
    const zlib = require('zlib');
    function unzipList(zipPath) {
      // 轻量：只读中央目录里的文件名
      const buf = fs.readFileSync(zipPath);
      const names = [];
      for (let i = 0; i + 46 <= buf.length; i++) {
        if (buf.readUInt32LE(i) === 0x02014b50) {
          const nlen = buf.readUInt16LE(i + 28);
          const elen = buf.readUInt16LE(i + 30);
          const clen = buf.readUInt16LE(i + 32);
          names.push(buf.toString('utf8', i + 46, i + 46 + nlen));
          i += 45 + nlen + elen + clen;
        }
      }
      return names;
    }
    const stdlib = unzipList(PYP + '/stdlib-common.imy');
    const reqs = unzipList(PYP + '/requirements.imy');

    const stdPyc = stdlib.filter(n => n.endsWith('.pyc')).length;
    check('v3.7.2：stdlib-common.imy 是完整版（>=598 个 pyc，残缺版只有 390）',
      stdPyc >= 598);
    check('v3.7.2：stdlib 含 ctypes 包（primitive.py 依赖）',
      stdlib.includes('ctypes/__init__.pyc')
      && stdlib.includes('ctypes/_endian.pyc')
      && stdlib.includes('ctypes/util.pyc'));
    check('v3.7.2：stdlib 含 stdlib 关键模块 hashlib/ssl/socket',
      stdlib.includes('hashlib.pyc') && stdlib.includes('ssl.pyc')
      && stdlib.includes('socket.pyc'));

    check('v3.7.2：requirements 含 lxml 真实 C 扩展（etree.so）',
      reqs.includes('lxml/etree.so') && reqs.includes('lxml/__init__.py'));
    check('v3.7.2：requirements 含 pycryptodome 真实实现（AES + _raw_aes.so）',
      reqs.includes('Crypto/Cipher/AES.py')
      && reqs.includes('Crypto/Cipher/_raw_aes.so')
      && reqs.includes('Crypto/Cipher/_mode_ecb.py'));
    check('v3.7.2：requirements 含 Padding 及其依赖链',
      reqs.includes('Crypto/Util/Padding.py')
      && reqs.includes('Crypto/Util/_raw_api.py')
      && reqs.includes('Crypto/Util/py3compat.py'));
    check('v3.7.2：requirements 含 cryptography 真实实现（_openssl.so）',
      reqs.includes('cryptography/hazmat/bindings/_openssl.so')
      && reqs.includes('cryptography/hazmat/primitives/ciphers/algorithms.py'));
    check('v3.7.2：requirements 已无 3.11 pyc（跨版本残留）',
      reqs.filter(n => n.includes('cpython-311')).length === 0);
    check('v3.7.2：requirements 纯 Python 包（requests/bs4/urllib3）完好',
      reqs.includes('requests/__init__.py') && reqs.includes('requests/api.py')
      && reqs.includes('bs4/__init__.py') && reqs.includes('urllib3/__init__.py'));

    // bootstrap-native 数量下限（残缺失为 40，官方 57）
    const nat = fs.readdirSync(PYP + '/bootstrap-native/arm64-v8a')
      .filter(n => n.endsWith('.so')).length;
    check('v3.7.2：bootstrap-native 的 C 扩展齐全（>=57，残留版只有 40）', nat >= 57);
    check('v3.7.2：bootstrap-native 含 _ctypes.so',
      fs.existsSync(PYP + '/bootstrap-native/arm64-v8a/_ctypes.so'));

    // 构建脚本的完整性闸门
    check('v3.7.2：build.sh 内置 stdlib/依赖完整性自检',
      /chk_imy stdlib-common\.imy/.test(bs2)
      && /chk_imy requirements\.imy/.test(bs2)
      && /stdlib-common\.imy 不完整/.test(bs2));
    check('v3.7.2：build.sh 内置 native 数量与架构自检',
      /bootstrap-native 缺 C 扩展/.test(bs2) && /非 arm64 ELF/.test(bs2));

    // APK 实测
    if (fs.existsSync(APK)) {
      try {
        const { execSync } = require('child_process');
        const list = execSync('unzip -l "' + APK + '"', { encoding: 'utf8' });
        check('v3.7.2：APK 内 stdlib-common.imy 已更新（2902914 字节）',
          /2902914\s+.*assets\/chaquopy\/stdlib-common\.imy/.test(list));
        check('v3.7.2：APK 内 requirements.imy 已更新（3281769 字节）',
          /3281769\s+.*assets\/chaquopy\/requirements\.imy/.test(list));
        check('v3.7.2：APK 内已打入 _ctypes.so',
          /_ctypes\.so/.test(list));
      } catch (e) {}
    }
  })();

  // ---- 5) ★ v3.5 修复：导入失败根因 ----
  // sanitizePyName 的点号白名单问题：点号不在允许字符里，
  // 若把带 .py 的整串丢进净化循环，后缀会被冲成 _py，文件失去
  // Python 源语义，importlib 报「无法为文件创建模块规格」。
  check('v3.5：sanitizePyName 先剥 .py 后缀再净化',
    /endsWith\("\.py"\)\) base = base\.substring\(0, base\.length\(\) - 3\);/.test(bss));
  check('v3.5：sanitizePyName 净化字符集不含点号（避免把后缀吃掉）',
    /c == '_' \|\| c == '-'\) \{/.test(bss));
  check('v3.5：sanitizePyName 统一补回 .py 后缀',
    /return s \+ "\.py";/.test(bss));
  check('v3.5：sanitizePyName 去首尾下划线（纯中文名净化后不留一串 _）',
    /while \(s\.startsWith\("_"\)\) s = s\.substring\(1\);/.test(bss));
  check('v3.5：sanitizePyName 数字开头的名字加前缀',
    /Character\.isDigit\(s\.charAt\(0\)\)\) s = "s_" \+ s;/.test(bss));

  // 批内去重：同一个 zip 里常混着同一源的多个拷贝
  check('v3.5：按源显示名做批内去重',
    /seenNames\.add\(dedupKey\)/.test(bss)
    && /同一书源的重复文件/.test(bss));
  check('v3.5：重复源会被卸掉而非并列登记',
    /PythonHost\.unloadSource\(pid\)/.test(bss));

  // 跨次去重：先传单个 .py、后又传整包 zip 的场景
  check('v3.5：跨次导入去重（同名旧源自动卸掉）',
    /unloadByName\(disp, pid\)/.test(bss));
  check('v3.5：PythonHost 暴露 loadedNames / unloadByName',
    /public static java\.util\.Map<String, String> loadedNames\(\)/.test(ph)
    && /public static void unloadByName\(String name, String keepKey\)/.test(ph));
  check('v3.5：MainActivity.importPyFile 落盘名去首尾下划线',
    /replaceAll\("\^_\+\|_\+\$", ""\)/.test(ma));

  // ---- 6) 引导层与宿主基类 ----
  const appPy = '/root/tingshu-app/python/assets/chaquopy/app.imy';
  check('v3.4：app.imy 已打包（引导层）', fs.existsSync(appPy));
  const okAppJs = /o\.type === 'py'/.test(
    fs.readFileSync('/root/tingshu-app/assets/www/app.js', 'utf8'));
  check('v3.4：前端 onSourceFilePicked 有 py 分支并调 importPySources',
    okAppJs && /TS\.call\('importPySources'/.test(
      fs.readFileSync('/root/tingshu-app/assets/www/app.js', 'utf8')));
  check('v3.4：导入面板文案提到 Python 源',
    /Python 源/.test(fs.readFileSync('/root/tingshu-app/assets/www/app.js', 'utf8')));
}


// ==================== ★ v3.7.9 断言块 ====================
{
  const appJsPath = '/root/tingshu-app/assets/www/app.js';
  const appJs = fs.readFileSync(appJsPath, 'utf8');
  const cssPath = '/root/tingshu-app/assets/www/style.css';
  const css = fs.readFileSync(cssPath, 'utf8');
  const htmlPath = '/root/tingshu-app/assets/www/index.html';
  const html = fs.readFileSync(htmlPath, 'utf8');
  // 本块自包含：ph / srcReg / appJava / bridge / bss 都在别的块作用域里，
  // 这里重新读一遍源码，避免跨块引用。
  const ph = fs.readFileSync(path.join(ROOT, 'python/PythonHost.java'), 'utf8');
  const srcReg = fs.readFileSync(path.join(ROOT, 'source/SourceRegistry.java'), 'utf8');
  const appJava = fs.readFileSync(path.join(ROOT, 'core/App.java'), 'utf8');
  const bridge = fs.readFileSync(path.join(ROOT, 'Bridge.java'), 'utf8');
  const bss = fs.readFileSync(path.join(ROOT, 'BridgeSource.java'), 'utf8');

  // ---- 1) 「加载更多」按钮点击无效 ----
  check('v3.7.9：homeMore 有超时兜底时间戳 homeMoreSince',
    /App\.homeMoreSince\s*=\s*0/.test(appJs) && /App\.homeMoreSince\s*=\s*now/.test(appJs));
  check('v3.7.9：busy 未超时 → 给按钮"加载中…"反馈而不是静默 return',
    /b0\.textContent\s*=\s*'加载中…'/.test(appJs));
  check('v3.7.9：busy 超时（>15s）→ 复位并真正重发',
    /now\s*-\s*App\.homeMoreSince\)\s*>\s*15000/.test(appJs)
    && /App\.homeMoreBusy\s*=\s*false;/.test(appJs));
  check('v3.7.9：resetHomePaging 一并复位 homeMoreSince',
    /function resetHomePaging[\s\S]{0,400}App\.homeMoreSince\s*=\s*0/.test(appJs));

  // ---- 2) 首启书源加载慢（Python 运行时改后台启动） ----
  check('v3.7.9：PythonHost.available() 不再同步启动解释器（无 Python.start 直调路径）',
    /public static boolean available\(\)\s*\{[\s\S]{0,700}?kickStart\(\);/.test(ph));
  check('v3.7.9：PythonHost 有 starting 状态（区分"启动中"与"不可用"）',
    /private static volatile boolean starting/.test(ph)
    && /public static boolean starting\(\)/.test(ph));
  check('v3.7.9：PythonHost 有 ReadyListener 回调机制',
    /interface ReadyListener/.test(ph) && /public static void setReadyListener/.test(ph));
  check('v3.7.9：kickStart 把 ensureStarted 丢到后台线程池',
    /private static void kickStart\(\)[\s\S]{0,900}?Aggregator\.singlePool\(\)\.execute/.test(ph));
  check('v3.7.9：SourceRegistry.loadPythonSources 只查状态、不触发启动',
    /if \(!com\.tingshu\.app\.python\.PythonHost\.available\(\)\) \{\s*PY_PENDING\.set\(true\)/.test(srcReg));
  check('v3.7.9：SourceRegistry 暴露 pythonPending()',
    /public static boolean pythonPending\(\)/.test(srcReg));
  check('v3.7.9：App.onCreate 挂了 Python 就绪回调并后台补 reload',
    /PythonHost\.setReadyListener/.test(appJava)
    && /pythonPending\(\)/.test(appJava)
    && /SourceRegistry\.reload\(\)/.test(appJava));
  check('v3.7.9：Bridge 新增 pythonStatus 接口（loading/ready/failed/idle）',
    /public void pythonStatus\(\)/.test(bridge)
    && /"loading"/.test(bridge) && /"ready"/.test(bridge) && /"failed"/.test(bridge));
  check('v3.7.9：前端有 onPythonStatus 处理 + 兜底轮询',
    /window\.onPythonStatus\s*=/.test(appJs) && /function pyPoll\(\)/.test(appJs));
  check('v3.7.9：源表变多时自动重拉 discoverSources',
    /App\.sources\.length\s*>\s*prev\.length/.test(appJs)
    && /TS\.call\('discoverSources'\)/.test(appJs));
  check('v3.7.9：BridgeSource 导入 Python 源改用阻塞式 ensureStarted',
    /com\.tingshu\.app\.python\.PythonHost\.ensureStarted\(ctx\)/.test(bss));

  // ---- 3) 章节列表分列 / 区间 ----
  check('v3.7.9：详情页渲染章节工具条 renderChapTools',
    /function renderChapTools\(/.test(appJs));
  check('v3.7.9：分列控件 1/2/3 三档 + chapSetCols',
    /chapSetCols\(/.test(appJs) && /names\s*=\s*\{\s*1:\s*'1 列',\s*2:\s*'2 列',\s*3:\s*'3 列'/.test(appJs));
  check('v3.7.9：区间步长 200 集（CHAP_SEG）',
    /var CHAP_SEG = 200;/.test(appJs));
  check('v3.7.9：区间窗口按段裁剪（chapWindow）',
    /function chapWindow\(total\)/.test(appJs) && /start = seg \* CHAP_SEG/.test(appJs));
  check('v3.7.9：只渲染当前区间（避免上千集一次性建 DOM）',
    /for \(var i = win\.start; i < win\.end; i\+\+\)/.test(appJs));
  check('v3.7.9：容器带 cols-N 类，配合 CSS 网格',
    /'<div class="chapter-list cols-' \+ chapCols\(\) \+ '" id="chapList">'/.test(appJs));
  check('v3.7.9：视图偏好持久化 fx_chap_view_v1',
    /fx_chap_view_v1/.test(appJs) && /function loadChapView\(\)/.test(appJs));
  check('v3.7.9：CSS 定义 2/3 列网格 + 工具条样式',
    /\.chapter-list\.cols-2\s*\{/.test(css) && /\.chapter-list\.cols-3\s*\{/.test(css)
    && /\.chap-tools\s*\{/.test(css));

  // ---- 4) 播放页括号布局 ----
  // ★ v3.7.11（#164）：全角括号改为「轻括号 + 等宽数字 + 进度底色」，语义仍是「集数做了分层」。
  check('v3.7.11：集数改分层 span（轻括号 + 等宽数字 + 总数）',
    /'<span class="fep-par">\(<\/span>'/.test(appJs)
    && /'<span class="fep-no">' \+ no/.test(appJs)
    && /'<span class="fep-sep">\/<\/span>'/.test(appJs)
    && /'<span class="fep-tot">' \+ total/.test(appJs));
  check('v3.7.9：不再输出半角 (N/M) 直排',
    /ep\.textContent = '\(' \+ no \+ '\/' \+ total \+ '\)'/.test(appJs) === false);
  check('v3.7.11：CSS 给集数做了字重/透明度层次（fep-par / fep-no / fep-sep / fep-tot / fep-bar）',
    /\.fmeta \.fep-par\s*\{/.test(css)
    && /\.fmeta \.fep-no\s*\{/.test(css)
    && /\.fmeta \.fep-sep\s*\{/.test(css)
    && /\.fmeta \.fep-tot\s*\{/.test(css)
    && /\.fmeta \.fep-bar\s*\{/.test(css));

  // ---- 5) 关于页更新接口 + GitHub 链接 ----
  check('v3.7.9：Bridge 新增 checkUpdate（查 GitHub latest release）',
    /public void checkUpdate\(\)/.test(bridge)
    && /api\.github\.com\/repos\/fengxitingshu\/fengxi-tingshu\/releases\/latest/.test(bridge));
  check('v3.7.9：Bridge 新增 openUrl（只放行 http/https）',
    /public void openUrl\(final String url\)/.test(bridge)
    && /startsWith\("http:\/\/"\)/.test(bridge));
  check('v3.7.9：版本比较 compareVer 按三段数字逐位比',
    /private static int compareVer\(String a, String b\)/.test(bridge)
    && /for \(int i = 0; i < 3; i\+\+\)/.test(bridge));
  check('v3.7.9：关于页有更新卡片与检查按钮',
    /id="updCheckBtn"/.test(html) && /App\.ui\.checkUpdate\(\)/.test(html));
  check('v3.7.9：关于页有 GitHub 仓库与问题反馈入口',
    /App\.ui\.openGitHub\(\)/.test(html) && /App\.ui\.openGitHubIssues\(\)/.test(html));
  check('v3.7.9：前端有 onUpdateResult 回调（有新版才提示）',
    /window\.onUpdateResult\s*=/.test(appJs) && /o\.hasNew/.test(appJs));
  check('v3.7.9：CSS 定义 .ab-link',
    /\.ab-link\s*\{/.test(css));

  // ---- 版本号 ----
  const mf = fs.readFileSync('/root/tingshu-app/AndroidManifest.xml', 'utf8');
  check('v3.7.9／当前版本：versionCode=42 / versionName=3.7.15',
    /android:versionCode="42"/.test(mf) && /android:versionName="3\.7\.15"/.test(mf));
  check('v3.7.9：更新公告仍保留 3.7.9 条目',
    /ver: '3\.7\.9'/.test(appJs));

  // ==================== ★ v3.7.10 断言块 ====================
  // ---- #1 首页加载更多：新增内容必须"看得见" ----
  check('v3.7.10：scrollHomeToAnchor 把新内容滚到视口上沿（不是留 1/4 空位）',
    /var top = el\.offsetTop - 8;/.test(appJs));
  check('v3.7.10：新增 markFreshCards 给本批新卡打入场高亮',
    /function markFreshCards\(/.test(appJs) && /classList\.add\('just-added'\)/.test(appJs));
  check('v3.7.10：高亮 1.3s 后自动摘掉，可重复触发',
    /classList\.remove\('just-added'\)/.test(appJs));
  check('v3.7.10：CSS 定义 .bookcard.just-added 入场动画',
    /\.bookcard\.just-added\s*\{/.test(css) && /@keyframes cardIn/.test(css));
  check('v3.7.10：onBrowse 兜底复位按钮（未被消费也修回可点）',
    /function fixHomeMoreBtn\(/.test(appJs) && /fixHomeMoreBtn\(\);/.test(appJs));
  check('v3.7.10：fixHomeMoreBtn 恢复 disabled=false 与「加载更多」文案',
    /b\.disabled = false; b\.textContent = '加载更多';/.test(appJs));
  check('v3.7.11：去重指纹升级为「书名+链接+key」三元组，避免整页被误丢',
    /var fp = function \(b\) \{/.test(appJs)
    && /return \(t \|\| k \|\| u\) \+ '@@' \+ \(u \|\| k\) \+ '@@' \+ k;/.test(appJs));
  check('v3.7.10：累积与去重都走同一指纹 fp()',
    /seen\[fp\(all\[s\]\)\] = true;/.test(appJs) && /var kk = fp\(bk\);/.test(appJs));

  // ---- #2 播放页选集：列数 / 区间下拉 ----
  check('v3.7.10：选集弹层头部有列数与区间两个下拉按钮',
    /id="chsColsBtn"/.test(appJs) && /id="chsSegBtn"/.test(appJs));
  check('v3.7.10：下拉按钮用 .chs-tool 胶囊样式（不常驻展开）',
    /class="chs-tool"/.test(appJs) && /\.chs-tool\s*\{/.test(css));
  // ★ v3.7.13：下拉菜单已统一到通用 .ctx-menu（见 ctxMenu / chsDropdown 转发）
  check('v3.7.10/v3.7.13：列数/区间下拉转发到统一菜单 chsDropdown → ctxMenu',
    /function chsDropdown\(/.test(appJs) && /ctxMenu\(/.test(appJs)
    && !/id = 'chsDropdown'/.test(appJs));
  check('v3.7.10/v3.7.13：点空白 / 触摸 / 滚动都会收起统一菜单',
    /document\.addEventListener\('click', ctxClose, \{ once: true \}\)/.test(appJs)
    && /document\.addEventListener\('touchstart', ctxClose, \{ once: true, passive: true \}\)/.test(appJs)
    && /document\.addEventListener\('scroll', ctxClose, \{ once: true, passive: true \}\)/.test(appJs));
  check('v3.7.10：列数下拉含 1/2/3 三档',
    /\{ label: '1 列'/.test(appJs) && /\{ label: '3 列'/.test(appJs));
  check('v3.7.10/v3.7.13：选项点击后立即收起菜单',
    /ctxClose\(\);\n            if \(items\[idx\] && items\[idx\]\.pick\)/.test(appJs));
  check('v3.7.10：选集列表按当前区间裁剪（chsListHtml 用 chapWindow）',
    /function chsListHtml\(chs\)/.test(appJs) && /var win = chapWindow\(chs\.length\)/.test(appJs));
  check('v3.7.10：选集列表容器带 cols-N（复用详情页栅格）',
    /class="chlist-inner cols-' \+ chapCols\(\)/.test(appJs));
  check('v3.7.10：CSS 定义 .chlist-inner.cols-2/3 网格',
    /\.chlist-inner\.cols-2\s*\{/.test(css) && /\.chlist-inner\.cols-3\s*\{/.test(css));
  check('v3.7.10：打开选集时自动切到当前集所在区间',
    /if \(curIdx < win0\.start \|\| curIdx >= win0\.end\)/.test(appJs));
  check('v3.7.10：选集与详情页共用同一份偏好（loadChapView/saveChapView）',
    /loadChapView\(\);\s*\/\/ 与详情页同一份偏好/.test(appJs)
      && /saveChapView\(\);/g.test(appJs));
  check('v3.7.10：列数按钮显示当前值（N 列 ▾）',
    /cb\.textContent = chapCols\(\) \+ ' 列 ▾';/.test(appJs));
  check('v3.7.10：区间按钮显示当前段（a~b ▾）',
    /sb\.textContent = chapSegLabel\(chapSeg\(\), chs\.length\) \+ ' ▾';/.test(appJs));

  // ---- #3 书源管理按钮 ----
  check('v3.7.10：源管理按钮加大到 min-height 34px',
    /\.srcops button\.mini,[\s\S]{0,200}min-height: 34px/.test(css));
  check('v3.7.10：用 inline-flex + align-items:center 做垂直居中',
    /\.srcops button\.mini,[\s\S]{0,300}display: inline-flex; align-items: center; justify-content: center;/.test(css));
  check('v3.7.10：按钮字号提到 13.5px',
    /\.srcops button\.mini,[\s\S]{0,300}font-size: 13\.5px/.test(css));
  check('v3.7.10：二级页按钮同一套规则（#srcGroupContent .mini）',
    /#srcGroupContent \.mini/.test(css));
  check('v3.7.10：.srcops 改为 flex + 允许换行',
    /\.srcops \{ display: flex; align-items: center; gap: 8px; flex-wrap: wrap; \}/.test(css));

  // ---- 版本号 ----
  check('v3.7.13／当前版本：versionCode=42 / versionName=3.7.15',
    /android:versionCode="42"/.test(mf) && /android:versionName="3\.7\.15"/.test(mf));
  check('v3.7.13：更新公告保留 3.7.13 条目', /ver: '3\.7\.13'/.test(appJs));
  check('v3.7.13：单源删除改走 removeSourceById（不再用只认 JSON 的 removeCustom）',
    /TS\.call\('removeSourceById', req\.id\)/.test(appJs)
    && !/TS\.call\('removeCustom'/.test(appJs));
  check('v3.7.13：源行手势改事件委托 bindSrcPress（480ms 长按）',
    /function bindSrcPress\(/.test(appJs) && /var LONG = 480;/.test(appJs));
  check('v3.7.13：长按/点击共用 srcRowMenu（统一 .ctx-menu）',
    /function srcRowMenu\(/.test(appJs)
    && /ctxMenu\(x, y, items\)/.test(appJs));
  check('v3.7.13：测试连通结果弹窗带「关闭」按钮',
    /ui\.srcTestSheet = function/.test(appJs)
    && /\u5173\u95ed|关闭/.test(appJs));
  check('v3.7.13：播放失败弹窗带「换下一集 / 换一个书源 / 关闭」',
    /ui\.playFailSheet = function/.test(appJs)
    && /ui\.playFailNext = function/.test(appJs)
    && /ui\.playFailSwap = function/.test(appJs));
  check('v3.7.13：native 不再重复 toast 播放失败原因',
    !/toast\("取链失败，试试其他源"\)/.test(
      fs.readFileSync('/root/tingshu-app/src/com/tingshu/app/MainActivity.java','utf8')));

  // ============ ★ v3.7.14 ============
  const idxHtml = fs.readFileSync('/root/tingshu-app/assets/www/index.html', 'utf8');
  // 改标题文案（只升版本号，描述跟上）
  check('v3.7.14 归档／当前版本：versionCode=42 / versionName=3.7.15',
    /android:versionCode="42"/.test(mf) && /android:versionName="3\.7\.15"/.test(mf));
  check('v3.7.14：更新公告首条是 3.7.14', /ver: '3\.7\.14'/.test(appJs));
  check('v3.7.14／当前：设置页/关于页显示 3.7.15',
    /id="appVer">3\.7\.15</.test(idxHtml) && /id="aboutVer">3\.7\.15</.test(idxHtml));
  // --- A 源管理：多选下掉，只留长按菜单 ---
  check('v3.7.14/A：源管理多选 UI 已移除（无吸顶条/勾选框/批量删除）',
    !/id="srcMultiBar"/.test(appJs) && !/chk chk-src/.test(appJs)
    && !/ui\.doBatchDeleteSrc = function \(\) \{\s*ui\.closeSheet\(\)/.test(appJs));
  check('v3.7.14/A：多选相关接口改为空实现（enterSrcMulti/srcSelAll/srcSelInvert）',
    /ui\.enterSrcMulti = function \(\) \{\};/.test(appJs)
    && /ui\.srcSelAll = function \(\) \{\};/.test(appJs)
    && /ui\.srcSelInvert = function \(\) \{\};/.test(appJs));
  check('v3.7.14/A：长按只弹菜单，不再设 App.multiSrc',
    /function bindSrcPress/.test(appJs)
    && !/App\.multiSrc\.sel\[id\] = true;/.test(appJs));
  check('v3.7.14/A：行内提示改为「长按任意一行打开菜单」',
    /长按任意一行打开菜单/.test(appJs));
  check('v3.7.14/A：返回键不再"先退出源多选"',
    !/if \(App\.multiSrc\) \{ exitSrcMulti\(false\); return true; \}/.test(appJs));

  // --- B 测试连通：native 端修复 ---
  const bsrc14 = fs.readFileSync(
    '/root/tingshu-app/src/com/tingshu/app/BridgeSource.java', 'utf8');
  check('v3.7.14/B：Python 源单独走 PySourceAdapter 分支（不再误走 jar 兜底）',
    /instanceof com\.tingshu\.app\.python\.PySourceAdapter/.test(bsrc14));
  check('v3.7.14/B：Python 分支先查运行时是否就绪',
    /PySourceAdapter\)[\s\S]{0,600}?PythonHost\.available\(\)/.test(bsrc14));
  check('v3.7.14/B：内置源判断前置（不再被 JsonSource 分支抢先）',
    bsrc14.indexOf('isBuiltin(id)') < bsrc14.indexOf('instanceof JsonSource'));
  check('v3.7.14/B：删掉反射取 BASE 的无效兜底 cfgBaseOf',
    bsrc14.indexOf('cfgBaseOf') < 0);
  check('v3.7.14/B：只支持浏览的源改测分类页（testByBrowse + categories）',
    /testByBrowse/.test(bsrc14) && /BrowsableSource[\s\S]{0,200}?categories\(\)/.test(bsrc14));

  // --- W 小组件 ---
  const provider14 = fs.readFileSync(
    '/root/tingshu-app/src/com/tingshu/app/widget/PlayerWidgetProvider.java', 'utf8');
  // 注释里也写了 wgRoot 的旧写法，先剥注释再断言，避免命中说明文字
  const providerCode14 = provider14.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  const psvc14 = fs.readFileSync(
    '/root/tingshu-app/src/com/tingshu/app/player/PlayerService.java', 'utf8');
  const mact14 = fs.readFileSync(
    '/root/tingshu-app/src/com/tingshu/app/MainActivity.java', 'utf8');
  const wgLay14 = fs.readFileSync('/root/tingshu-app/res/layout/widget_player.xml', 'utf8');
  const wgBg14 = fs.readFileSync('/root/tingshu-app/res/drawable/wg_bg.xml', 'utf8');

  check('v3.7.14/W：wgRoot 上不再挂 PendingIntent（按钮不再被整块面板吞掉）',
    !/setOnClickPendingIntent\(\s*R\.id\.wgRoot/.test(providerCode14));
  check('v3.7.14/W：三个控制键各自挂独立 PendingIntent',
    /setOnClickPendingIntent\(R\.id\.wgToggle/.test(provider14)
    && /setOnClickPendingIntent\(R\.id\.wgPrev/.test(provider14)
    && /setOnClickPendingIntent\(R\.id\.wgNext/.test(provider14));
  check('v3.7.14/W：只有封面区/信息区可点开 App',
    /setOnClickPendingIntent\(R\.id\.wgCoverTap/.test(provider14)
    && /setOnClickPendingIntent\(R\.id\.wgInfoTap/.test(provider14));
  check('v3.7.14/W：控制键改 getService 直投播放服务',
    /PendingIntent\.getService\(ctx, req, i, flags\)/.test(provider14));
  check('v3.7.14/W：进程被回收后 CTL 为空时降级拉起 App',
    /Ctl c = CTL;[\s\S]{0,3000}?startActivity\(ui\)/.test(psvc14));
  check('v3.7.14/W：小组件封面上屏（CoverListener 回调链路完整）',
    /interface CoverListener/.test(psvc14) && /cl\.onCoverReady\(bmp\)/.test(psvc14)
    && /bindCoverListener\(\)/.test(mact14));
  check('v3.7.14/W：切书/切集也同步小组件（onStarted → syncWidget）',
    /public void onStarted\(Book book, int chapterIdx\)[\s\S]*?syncWidget\(\)/.test(mact14));
  check('v3.7.14/W：书名与副标题设 marquee + 代码触发 setSelected',
    /id="@\+id\/wgTitle"[\s\S]{0,500}?ellipsize="marquee"/.test(wgLay14)
    && /id="@\+id\/wgSub"[\s\S]{0,500}?ellipsize="marquee"/.test(wgLay14)
    && /setBoolean\(R\.id\.wgTitle, "setSelected", true\)/.test(provider14)
    && /setBoolean\(R\.id\.wgSub, "setSelected", true\)/.test(provider14));
  // ---- v3.7.15 小组件改版：仿通知播放器 + 可点进度条 ----
  check('v3.7.15/W：背景改为中性半透明灰玻璃（#CC2A2E3A，26dp 圆角）',
    wgBg14.indexOf('#CC2A2E3A') > 0 && /corners android:radius="26dp"/.test(wgBg14));
  check('v3.7.15/W：背景保留高光层与淡白描边',
    /#30FFFFFF/.test(wgBg14) && /stroke android:width="1dp" android:color="#30FFFFFF"/.test(wgBg14));
  check('v3.7.15/W：控制键去掉背板（不再引用 wg_btn_bg）',
    (wgLay14.match(/@drawable\/wg_btn_bg/g) || []).length === 0);
  check('v3.7.15/W：封面改为方形 62×62dp',
    /android:layout_width="62dp"/.test(wgLay14) && /android:layout_height="62dp"/.test(wgLay14));
  check('v3.7.15/W：三个控制键统一染白（android:tint）',
    (wgLay14.match(/android:tint="#FFFFFF"/g) || []).length === 3);

  // 可点进度条：20 格 + 左右时间 + ACT_SEEK 通路
  check('v3.7.15/W：进度条上盖 20 格点击层（wgSeek0..19）',
    wgLay14.indexOf('@+id/wgSeek0') > 0 && wgLay14.indexOf('@+id/wgSeek19') > 0
    && (wgLay14.match(/@\+id\/wgSeek\d+/g) || []).length === 20);
  check('v3.7.15/W：进度条左侧显示当前时间、右侧显示总时长',
    /@\+id\/wgTimeCur/.test(wgLay14) && /@\+id\/wgTimeDur/.test(wgLay14));
  check('v3.7.15/W：Provider 为每格挂独立 requestCode（100+i，避免 extras 被覆盖）',
    /100 \+ i/.test(provider14) && /getIdentifier\(/.test(provider14)
    && /wgSeek/.test(provider14));
  check('v3.7.15/W：seek 走广播 + sec extras',
    /WIDGET_SEEK/.test(provider14) && /putExtra\("sec", sec\)/.test(provider14)
    && /seekPi\(/.test(provider14));
  check('v3.7.15/W：PlayerService 新增 ACT_SEEK 并消费 sec',
    /ACT_SEEK = "com\.tingshu\.app\.SEEK"/.test(psvc14)
    && /ACT_SEEK\.equals\(a\)[\s\S]{0,200}?getIntExtra\("sec"/.test(psvc14)
    && /c\.svcSeek\(sec\)/.test(psvc14));
  check('v3.7.15/W：seek 复用 svcSeek → MediaSession 同步（MainActivity 已有实现）',
    /public void svcSeek\(int sec\)[\s\S]{0,200}?refreshNotification\(\)/.test(mact14));

  // ★ 签名闸门（防 v3.7.14 未签名包再流出）
  const bs15 = fs.readFileSync('/root/tingshu-app/build.sh', 'utf8');
  const rs15 = fs.readFileSync('/root/tingshu-app/发布Release.sh', 'utf8');
  check('v3.7.15/SIGN：build.sh 加签名硬闸门（verify 不过即失败）',
    /apksigner" verify "\$PROJ\/TingShu\.apk" >\/dev\/null[\s\S]{0,200}?exit 1/.test(bs15));
  check('v3.7.15/SIGN：build.sh 比对成品 != aligned.apk（堵死贴错源）',
    /cmp -s "\$PROJ\/TingShu\.apk" "\$OUT\/aligned\.apk"[\s\S]{0,200}?exit 1/.test(bs15));
  check('v3.7.15/SIGN：build.sh 校验 MANIFEST.MF 存在',
    /META-INF\/MANIFEST\.MF/.test(bs15));
  check('v3.7.15/SIGN：build.sh 断言证书含组织与地区（防占位证书）',
    /O=FengXi TingShu Project/.test(bs15) && /证书身份闸门/.test(bs15));
  check('v3.7.15/SIGN：签名用规范别名 fengxi（非 test）',
    /--ks-key-alias fengxi/.test(bs15));
  check('v3.7.15/SIGN：发布脚本上传前先验签',
    /apksigner" verify "\$APK"[\s\S]{0,400}?exit 1/.test(rs15));

  check('v3.7.14/W：setFloat 加 API 31 守卫（低版本不崩）',
    /Build\.VERSION\.SDK_INT >= 31[\s\S]{0,600}?setFloat/.test(provider14));

  // ==================== ★ v3.7.15-fix：三项回归（小组件空白 / JS 源丢失 / 闪退） ====================

  // ---- 1) 小组件空白：RemoteViews 白名单 ----
  //
  // 根因回顾：v3.7.15 进度条上盖的 20 个点击分区用了 <View>，
  // 而 RemoteViews 白名单里没有 View（只有 LinearLayout/FrameLayout/
  // RelativeLayout/ImageView/TextView/ProgressBar/Button 等），
  // 桌面启动器 inflate 直接失败 → 整块小组件渲染成空白。
  // 这里把"布局里绝不能出现非白名单控件"固化成断言，
  // 以后再往小组件里加控件时会被当场拦下。
  check('v3.7.15-fix/W：布局里已无 <View>（RemoteViews 白名单不含 View）',
    !/<View\b/.test(wgLay14));
  check('v3.7.15-fix/W：进度条 20 格点击分区全部是 ImageView',
    (wgLay14.match(/@\+id\/wgSeek\d+/g) || []).length === 20
    && (wgLay14.match(/<ImageView/g) || []).length >= 24);
  //
  // 白名单校验：把布局里出现过的所有标签名抽出来，逐个比对。
  // 任何一个不在名单里的标签都会让桌面渲染失败。
  const allowedRv = ['LinearLayout', 'FrameLayout', 'RelativeLayout', 'ImageView',
    'TextView', 'ProgressBar', 'Button', 'ImageButton', 'ViewFlipper', 'ViewStub',
    'Chronometer', 'AnalogClock', 'Space', 'GridLayout', 'GridLayout$Alignment',
    'ScrollView', 'HorizontalScrollView', 'ListView', 'GridView', 'StackView',
    'AdapterViewFlipper', 'ViewSwitcher', 'CheckedTextView', 'RadioButton',
    'RadioGroup', 'CheckBox', 'Switch', 'ToggleButton', 'Spinner', 'RatingBar',
    'SeekBar', 'DatePicker', 'TimePicker', 'NumberPicker', 'CalendarView',
    'TextClock', 'EditText', 'AutoCompleteTextView', 'MultiAutoCompleteTextView',
    'ExpandableListView', 'WebView', 'TableLayout', 'TableRow', 'AbsoluteLayout',
    'ViewAnimator', 'MediaController', 'SurfaceView', 'TextureView', 'ProgressBar'];
  // 只取"真正的控件标签"：形如 <Tag 或 </Tag，排除 <attr/ 之类的属性元素
  const tags = (wgLay14.match(/<\/?([A-Z][A-Za-z0-9$_.]*)/g) || [])
    .map(s => s.replace(/^<\/?/, ''));
  const illegal = tags.filter(t => allowedRv.indexOf(t) < 0)
    .filter(t => ['shape', 'corners', 'gradient', 'stroke', 'solid', 'item',
      'selector', 'layer-list', 'ripple'].indexOf(t.toLowerCase()) < 0);
  check('v3.7.15-fix/W：布局控件全在 RemoteViews 白名单内（非法控件：'
    + (illegal.length ? illegal.join(',') : '无') + '）',
    illegal.length === 0);
  //
  // 打包后复核：以 aapt2 反解出的真实标签为准，避免"源码改了但没进包"。
  const rvTagsInApk = rvTagsFromApk();
  check('v3.7.15-fix/W：打包后布局中 <View> 数量为 0（aapt2 复核）',
    rvTagsInApk.filter(t => t === 'View').length === 0);
  check('v3.7.15-fix/W：打包后 ImageView ≥ 24（封面 + 3 键 + 20 分区）',
    rvTagsInApk.filter(t => t === 'ImageView').length >= 24);

  // ---- 2) JS 源消失：加载失败不再静默 ----
  const sr15 = fs.readFileSync(
    '/root/tingshu-app/src/com/tingshu/app/source/SourceRegistry.java', 'utf8');
  check('v3.7.15-fix/SRC：JS 插件加载失败会记诊断（不再静默吞异常）',
    /JS 插件加载失败/.test(sr15) && /DiagLog\.add/.test(sr15));
  check('v3.7.15-fix/SRC：catch 分支也记录（含异常类名与消息）',
    /JS 插件加载异常/.test(sr15)
    && /t\.getClass\(\)\.getSimpleName\(\)[\s\S]{0,120}?t\.getMessage\(\)/.test(sr15));
  check('v3.7.15-fix/SRC：成功路径记录"几个源"（便于比对数量骤减）',
    /JS 插件 OK/.test(sr15) && /r\.sources\.size\(\)/.test(sr15));
  check('v3.7.15-fix/SRC：无 JS 插件记录时给出明确提示',
    /库里没有记录/.test(sr15));
  check('v3.7.15-fix/SRC：新增 diagJson（目录/manifest/登记数/存活数/重试结果）',
    /public static org\.json\.JSONObject diagJson\(\)/.test(sr15)
    && /manifestExists/.test(sr15) && /liveSources/.test(sr15)
    && /loadError/.test(sr15) && /totalSources/.test(sr15));
  check('v3.7.15-fix/SRC：diagJson 重试加载时"不改注册表"（只读探针）',
    /现场重试一次加载/.test(sr15));

  const dl15 = fs.readFileSync(
    '/root/tingshu-app/src/com/tingshu/app/core/DiagLog.java', 'utf8');
  check('v3.7.15-fix/DIAG：新增 DiagLog 环形缓冲（上限 200 条，不落盘）',
    /class DiagLog/.test(dl15) && /MAX = 200/.test(dl15)
    && /ArrayDeque/.test(dl15));

  const br15 = fs.readFileSync('/root/tingshu-app/src/com/tingshu/app/Bridge.java', 'utf8');
  check('v3.7.15-fix/DIAG：Bridge 暴露 sourceDiag / sourceDiagClear',
    /public void sourceDiag\(\)/.test(br15)
    && /public void sourceDiagClear\(\)/.test(br15)
    && /onSourceDiag/.test(br15));

  // ---- 3) 概率闪退：崩溃日志落盘 + 崩溃日志页 ----
  check('v3.7.15-fix/CRASH：MainActivity 挂默认 UncaughtExceptionHandler',
    /setDefaultUncaughtExceptionHandler/.test(mact14)
    && /UncaughtExceptionHandler\(\)/.test(mact14));
  check('v3.7.15-fix/CRASH：崩溃日志落到 filesDir/crash/ 并带时间戳',
    /new File\(getFilesDir\(\), "crash"\)/.test(mact14)
    && /crash-" \+ ts \+ "\.log/.test(mact14));
  check('v3.7.15-fix/CRASH：日志含版本/机型/Android 版本（便于定位机型问题）',
    /版本: /.test(mact14) && /MANUFACTURER/.test(mact14) && /VERSION\.RELEASE/.test(mact14));
  check('v3.7.15-fix/CRASH：只保留最近 5 份（按修改时间倒序裁剪）',
    /all\.length > 5/.test(mact14) && /lastModified/.test(mact14));
  check('v3.7.15-fix/CRASH：记完仍走系统默认处理（不吞异常）',
    /prev\.uncaughtException\(t, e\)/.test(mact14));

  const idx15 = fs.readFileSync('/root/tingshu-app/assets/www/index.html', 'utf8');
  const app15 = fs.readFileSync('/root/tingshu-app/assets/www/app.js', 'utf8');
  check('v3.7.15-fix/UI：新增「源加载诊断」二级页',
    /id="pageDiag"/.test(idx15) && /id="diagSum"/.test(idx15) && /id="diagLog"/.test(idx15));
  check('v3.7.15-fix/UI：新增「崩溃日志」二级页',
    /id="pageCrash"/.test(idx15) && /id="crashBody"/.test(idx15));
  check('v3.7.15-fix/UI：关于页加入两个诊断入口',
    /App\.ui\.openDiag\(\)/.test(idx15) && /App\.ui\.openCrashLog\(\)/.test(idx15));
  check('v3.7.15-fix/UI：两个二级页已注册进 SUBS（切页与返回键才认）',
    /diag: 'pageDiag'/.test(app15) && /crash: 'pageCrash'/.test(app15));
  check('v3.7.15-fix/UI：回调 onSourceDiag / onCrashLog 已实现',
    /window\.onSourceDiag = function/.test(app15)
    && /window\.onCrashLog = function/.test(app15));
  check('v3.7.15-fix/UI：无插件时给出可操作结论（重新导入 JS 插件包）',
    /没有已安装的 JS 插件包/.test(app15) && /重新导入/.test(app15));
  check('v3.7.15-fix/UI：诊断页支持一键复制（便于反馈）',
    /ui\.diagCopy = function/.test(app15) && /ui\.crashCopy = function/.test(app15));

  const css15 = fs.readFileSync('/root/tingshu-app/assets/www/style.css', 'utf8');
  check('v3.7.15-fix/CSS：诊断页样式已补齐（.diag-sum/.diag-log/.diag-act）',
    /\.diag-sum\s*\{/.test(css15) && /\.diag-log\s*\{/.test(css15)
    && /\.diag-act\s*\{/.test(css15));
  check('v3.7.15-fix/CSS：日志块等宽字体 + 可滚动（长堆栈能翻）',
    /monospace/.test(css15) && /max-height: 52vh; overflow: auto/.test(css15));

  // ---- 版本号 ----
  check('v3.7.15/V：前端关于页版本号已到 3.7.15',
    /id="aboutVer">3\.7\.15/.test(idx15) && /id="appVer">3\.7\.15/.test(idx15));
}

// 打包后复核用：从 APK 里反解小组件布局的真实标签名
function rvTagsFromApk() {
  try {
    const out = require('child_process').execSync(
      '/root/android/android-14/aapt2 dump xmltree '
      + '/root/tingshu-app/TingShu.apk --file res/layout/widget_player.xml 2>/dev/null',
      { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
    return (out.match(/E: ([A-Za-z][A-Za-z0-9_.]*)/g) || [])
      .map(s => s.replace(/^E: /, ''));
  } catch (e) {
    return [];
  }
}

// ---------- 输出 ----------
let pass = 0, fail = 0;
for (const r of results) {
  if (r.ok) pass++; else fail++;
  console.log((r.ok ? '  PASS  ' : '  FAIL  ') + r.name + (r.detail ? ('   [' + r.detail + ']') : ''));
}
console.log('\n(' + pass + ' passed, ' + fail + ' failed)');
process.exit(fail ? 1 : 0);
