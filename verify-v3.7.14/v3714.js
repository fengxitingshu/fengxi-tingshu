// v3.7.14 专项回归
//  A 源管理：多选已移除，长按只弹菜单
//  B 测试连通：native 端修复（静态源码断言）
//  W 小组件：按钮独立 PendingIntent / 不再 wgRoot 拦截 / 跑马灯 / 玻璃背景
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const ROOT = '/root/tingshu-app';
const WWW = path.join(__dirname, 'www');

let pass = 0, fail = 0;
function check(name, ok, extra) {
  if (ok) { pass++; console.log('  ok    ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra ? '  → ' + extra : '')); }
}
const read = p => { try { return fs.readFileSync(p, 'utf8'); } catch (e) { return ''; } };

(async () => {
  const appjs = read(path.join(ROOT, 'assets/www/app.js'));
  const css = read(path.join(ROOT, 'assets/www/style.css'));
  const provider = read(path.join(ROOT, 'src/com/tingshu/app/widget/PlayerWidgetProvider.java'));
  const wgLayout = read(path.join(ROOT, 'res/layout/widget_player.xml'));
  const wgBg = read(path.join(ROOT, 'res/drawable/wg_bg.xml'));
  const wgBtn = read(path.join(ROOT, 'res/drawable/wg_btn_bg.xml'));
  const psvc = read(path.join(ROOT, 'src/com/tingshu/app/player/PlayerService.java'));
  const wstate = read(path.join(ROOT, 'src/com/tingshu/app/player/WidgetState.java'));
  const mact = read(path.join(ROOT, 'src/com/tingshu/app/MainActivity.java'));
  const bsrc = read(path.join(ROOT, 'src/com/tingshu/app/BridgeSource.java'));

  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 412, height: 915 } });
  const errs = [];
  page.on('pageerror', e => errs.push(String(e.message)));
  await page.addInitScript({ path: path.join(WWW, 'mock.js') });
  await page.addInitScript(() => { window.mockDiscover = () => []; window.__CALLS = []; });
  await page.goto('file://' + path.join(WWW, 'index.html'), { waitUntil: 'load' });
  await page.waitForTimeout(600);

  // ---------- A 源管理：多选移除 ----------
  console.log('\n# A 源管理：多选已移除，只留长按菜单');

  await page.evaluate(() => {
    window.onSourceList([
      { id: 'py_a', name: '源甲', origin: 'py', enabled: true, canSearch: true },
      { id: 'py_b', name: '源乙', origin: 'py', enabled: false, canSearch: true },
      { id: 'py_c', name: '源丙', origin: 'py', enabled: true, canSearch: true }
    ]);
    App.ui.openSources();
    App.ui.openSrcGroup('py');
  });
  await page.waitForTimeout(400);

  const a1 = await page.evaluate(() => {
    const rows = document.querySelectorAll('#srcGroupContent .srcrow');
    return {
      n: rows.length,
      chk: document.querySelectorAll('#srcGroupContent .chk-src').length,
      multiBar: !!document.getElementById('srcMultiBar'),
      hint: (document.querySelector('#srcGroupContent .srchint') || {}).textContent || '',
      switchedOn: !!document.querySelector('#srcGroupContent .sw input'),
      calls: window.__CALLS.slice()
    };
  });
  check('A1 源行正常渲染', a1.n === 3, JSON.stringify(a1));
  check('A2 行内不再有勾选框（.chk-src）', a1.chk === 0, 'got ' + a1.chk);
  check('A3 不再有多选吸顶条 #srcMultiBar', a1.multiBar === false);
  check('A4 提示文案改为「长按任意一行打开菜单」', /长按任意一行打开菜单/.test(a1.hint), a1.hint);
  check('A5 行内开关仍在（data-toggle）', a1.switchedOn === true);

  // 长按只弹菜单，不进多选
  await page.evaluate(async () => {
    const el = document.querySelectorAll('#srcGroupContent .srcrow')[0];
    const r = el.getBoundingClientRect();
    const x = Math.round(r.left + r.width * 0.4), y = Math.round(r.top + 22);
    const t = (cx, cy) => new Touch({ identifier: 1, target: el, clientX: cx, clientY: cy });
    el.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, cancelable: true,
      touches: [t(x, y)], targetTouches: [t(x, y)], changedTouches: [t(x, y)] }));
    await new Promise(r2 => setTimeout(r2, 620));
    el.dispatchEvent(new TouchEvent('touchend', { bubbles: true, cancelable: true,
      touches: [], targetTouches: [], changedTouches: [t(x, y)] }));
  });
  await page.waitForTimeout(300);

  const a2 = await page.evaluate(() => ({
    menu: !!document.getElementById('ctxMenu'),
    items: Array.from(document.querySelectorAll('#ctxMenu .ctx-item'))
      .map(e => (e.querySelector('.ci-tx') || {}).textContent || ''),
    multiSrc: !!App.multiSrc,
    selRows: document.querySelectorAll('#srcGroupContent .srcrow.sel').length,
    bodyCls: document.body.className
  }));
  check('A6 长按弹出统一菜单 #ctxMenu', a2.menu === true, JSON.stringify(a2));
  check('A7 菜单含 启用/停用、测试连通性、重命名', 
    a2.items.some(t => /启用|停用/.test(t)) &&
    a2.items.some(t => /测试连通性/.test(t)) &&
    a2.items.some(t => /重命名/.test(t)), JSON.stringify(a2.items));
  check('A8 长按后【不再】进入多选态（App.multiSrc 为空）', a2.multiSrc === false);
  check('A9 长按后没有任何行被标记 selected', a2.selRows === 0);
  check('A10 body 不再挂 multi-src-on', a2.bodyCls.indexOf('multi-src-on') < 0, a2.bodyCls);

  // 多选相关接口已成空壳
  const a3 = await page.evaluate(() => {
    const before = !!App.multiSrc;
    App.ui.enterSrcMulti();
    const afterEnter = !!App.multiSrc;
    App.ui.srcSelAll(); App.ui.srcSelInvert();
    return { before, afterEnter, bar: !!document.getElementById('srcMultiBar') };
  });
  check('A11 enterSrcMulti 已成空实现（不建多选态）', a3.afterEnter === false);
  check('A12 调 srcSelAll 后依然没有多选条', a3.bar === false);

  // 短按同样弹菜单
  await page.evaluate(() => App._ctxClose && App._ctxClose());
  await page.waitForTimeout(120);
  await page.evaluate(() => App.ui.srcRowClick('py_b'));
  await page.waitForTimeout(250);
  const a4 = await page.evaluate(() => ({
    menu: !!document.getElementById('ctxMenu'),
    multiSrc: !!App.multiSrc
  }));
  check('A13 短按一行也弹同一套菜单', a4.menu === true);
  check('A14 短按不进多选', a4.multiSrc === false);

  // 源码层面：多选 UI 构造已删干净
  check('A15 app.js 不再构造多选吸顶条 srcMultiBar', appjs.indexOf('id="srcMultiBar"') < 0);
  check('A16 app.js 不再渲染行内勾选框 chk-src', appjs.indexOf('chk chk-src') < 0);
  check('A17 app.js 长按回调里不再设置 App.multiSrc', 
    !/fired = true;[\s\S]{0,400}?App\.multiSrc = \{/.test(appjs));

  // ---------- B 测试连通 native 修复 ----------
  console.log('\n# B 测试连通性：native 端修复');

  check('B1 BridgeSource 给 Python 源单开一路（PySourceAdapter 分支）',
    bsrc.indexOf('instanceof com.tingshu.app.python.PySourceAdapter') > 0);
  check('B2 Python 分支先判断运行时是否就绪',
    /PySourceAdapter\)[\s\S]{0,600}?PythonHost\.available\(\)/.test(bsrc));
  check('B3 内置源判断移到 JsonSource 之前（不再误判）',
    bsrc.indexOf('isBuiltin(id)') < bsrc.indexOf('instanceof JsonSource'));
  check('B4 删掉反射取 BASE 的无效兜底（cfgBaseOf）',
    bsrc.indexOf('cfgBaseOf') < 0);
  check('B5 不能搜索的源改测分类（testByBrowse）',
    bsrc.indexOf('testByBrowse') > 0 && /BrowsableSource[\s\S]{0,200}?categories\(\)/.test(bsrc));

  // 前端侧链路仍完好
  const b1 = await page.evaluate(() => {
    window.__LOG2 = [];
    const orig = TS.call;
    TS.call = function () { window.__LOG2.push(Array.prototype.slice.call(arguments)); return orig.apply(TS, arguments); };
    App.ui.goTab('sources');
    App.ui.openSources();
    App.ui.openSrcGroup('py');
    App.ui.testSource('py_a');
    return { el: (document.getElementById('st_py_a') || {}).textContent, log: window.__LOG2 };
  });
  check('B6 前端点测试 → 行内显示「测试中…」', /测试中/.test(b1.el || ''), String(b1.el));
  check('B7 前端确实发出 testCustom 调用',
    b1.log.some(x => x[0] === 'testCustom' && x[1] === 'py_a'), JSON.stringify(b1.log));

  const b2 = await page.evaluate(() => {
    window.onSourceTested({ id: 'py_a', ok: true, msg: '连通正常，搜索命中 20 条' });
    return {
      el: (document.getElementById('st_py_a') || {}).textContent,
      hasRes: !!document.querySelector('.srctest-res.ok'),
      msg: (document.querySelector('.srcmeta') || {}).textContent,
      close: Array.from(document.querySelectorAll('.sheet-item')).some(x => /关闭/.test(x.textContent))
    };
  });
  check('B8 回调后行内留下结论', /✔/.test(b2.el || ''), String(b2.el));
  check('B9 弹出成功结果面板', b2.hasRes === true);
  check('B10 面板带「关闭」按钮', b2.close === true);
  check('B11 面板展示 native 返回的 msg', /命中 20 条/.test(b2.msg || ''), String(b2.msg));

  // 失败态
  const b3 = await page.evaluate(() => {
    document.querySelectorAll('.sheet-item').forEach(x => { if (/关闭/.test(x.textContent)) x.click(); });
    window.onSourceTested({ id: 'py_a', ok: false, msg: '站点已响应，但没搜出结果（可能被限流或关键词无货）' });
    return {
      bad: !!document.querySelector('.srctest-res.bad'),
      msg: (document.querySelector('.srcmeta') || {}).textContent
    };
  });
  check('B12 失败时弹失败面板（.srctest-res.bad）', b3.bad === true);
  check('B13 失败文案给用户可判断的提示', /限流|关键词/.test(b3.msg || ''), String(b3.msg));

  // ---------- W 小组件 ----------
  console.log('\n# W 桌面小组件');

  // 注意：注释里也出现了 setOnClickPendingIntent(R.id.wgRoot 这段文字，
  // 所以先剥掉注释再断言，否则会命中说明文字造成假失败。
  const providerCode = provider.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  check('W1 wgRoot 上【不再】挂任何 PendingIntent（热点不再吞按钮）',
    !/setOnClickPendingIntent\(\s*R\.id\.wgRoot/.test(providerCode));
  check('W2 三个控制键各自挂独立 PendingIntent',
    /setOnClickPendingIntent\(R\.id\.wgToggle/.test(provider) &&
    /setOnClickPendingIntent\(R\.id\.wgPrev/.test(provider) &&
    /setOnClickPendingIntent\(R\.id\.wgNext/.test(provider));
  check('W3 只有封面区 / 信息区可点开 App',
    /setOnClickPendingIntent\(R\.id\.wgCoverTap/.test(provider) &&
    /setOnClickPendingIntent\(R\.id\.wgInfoTap/.test(provider));
  check('W4 控制 PendingIntent 改为 getService 直投 PlayerService（避开后台启动前台服务限制）',
    /PendingIntent\.getService\(ctx, req, i, flags\)/.test(provider));
  check('W5 服务端 CTL 为空时降级拉起 App（不再静默丢弃命令）',
    /Ctl c = CTL;[\s\S]{0,1200}?startActivity\(ui\)/.test(psvc));
  check('W6 降级拉起时带 openFull 直接进播放页', /putExtra\("openFull", true\)/.test(psvc));

  // 封面
  check('W7 封面就绪回调接口已定义', /interface CoverListener/.test(psvc));
  check('W8 封面下载完成后触发回调', /cl\.onCoverReady\(bmp\)/.test(psvc));
  check('W9 MainActivity 注册了封面上屏回调', /bindCoverListener\(\)/.test(mact));
  check('W10 onStart 里补挂回调（服务重启后不丢）',
    /protected void onStart\(\)[\s\S]{0,600}?bindCoverListener\(\)/.test(mact));
  check('W11 切书/切集时也同步（onStarted 里调 syncWidget）',
    /public void onStarted\(Book book, int chapterIdx\)[\s\S]*?syncWidget\(\)/.test(mact));
  check('W12 封面尺寸加大到 56x76dp',
    /android:layout_width="56dp"/.test(wgLayout) && /android:layout_height="76dp"/.test(wgLayout));

  // 跑马灯
  check('W13 书名 TextView 设了 marquee',
    /id="@\+id\/wgTitle"[\s\S]{0,500}?android:ellipsize="marquee"/.test(wgLayout));
  check('W14 副标题 TextView 设了 marquee',
    /id="@\+id\/wgSub"[\s\S]{0,500}?android:ellipsize="marquee"/.test(wgLayout));
  check('W15 marquee 配了无限循环',
    /marqueeRepeatLimit="marquee_forever"/.test(wgLayout));
  check('W16 Provider 里 setSelected(true) 触发滚动',
    /setBoolean\(R\.id\.wgTitle, "setSelected", true\)/.test(provider) &&
    /setBoolean\(R\.id\.wgSub, "setSelected", true\)/.test(provider));

  // 液态玻璃
  check('W17 背景改为半透明深色（不再实心暖橙）',
    wgBg.indexOf('#9E1A1A22') > 0 && wgBg.indexOf('#FF8A3D') < 0);
  check('W18 背景叠了白色高光渐变（玻璃折光）',
    /gradient[\s\S]{0,300}?#3DFFFFFF/.test(wgBg));
  check('W19 圆角加大到 24dp',
    /corners android:radius="24dp"/.test(wgBg));
  check('W20 有 1dp 细白描边（玻璃边缘）',
    /stroke android:width="1dp" android:color="#4DFFFFFF"/.test(wgBg));
  check('W21 按钮加了玻璃底 wg_btn_bg',
    wgBtn.length > 0 && wgBtn.indexOf('#26FFFFFF') > 0);
  check('W22 布局里三个按钮都引用了 wg_btn_bg',
    (wgLayout.match(/@drawable\/wg_btn_bg/g) || []).length === 3);
  check('W23 文字加了投影（浅壁纸上也读得清）',
    (wgLayout.match(/android:shadowRadius/g) || []).length >= 3);

  // setFloat 兼容
  check('W24 setFloat 加了 API 31 守卫（低版本不崩）',
    /Build\.VERSION\.SDK_INT >= 31[\s\S]{0,600}?setFloat/.test(provider));

  // 小组件状态快照链路完好
  check('W25 WidgetState 快照字段齐全',
    /epText/.test(wstate) && /curSec/.test(wstate) && /durSec/.test(wstate) && /cover/.test(wstate));
  check('W26 小组件清单声明仍在',
    read(path.join(ROOT, 'AndroidManifest.xml')).indexOf('PlayerWidgetProvider') > 0);

  // ---------- Z 无异常 ----------
  console.log('');
  check('Z1 全程无页面级 JS 异常', errs.length === 0, errs.join(' | '));
  await b.close();

  console.log('\n' + pass + ' passed / ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
})();
