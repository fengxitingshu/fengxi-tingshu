#!/usr/bin/env python3
"""车机版布局离线渲染：1920x720 / 1280x480 真实 CSS 像素 1:1 截图"""
import asyncio
from playwright.async_api import async_playwright

OUT = "/workspace/fengxi-tingshu/preview"
SIZES = [(1920, 720, "1920x720"), (1280, 480, "1280x480")]

BOOKS = [
    ["三国演义·白云出岫", "1 集 · 公版书"], ["水浒传·有声版", "2 集 · 公版书"],
    ["史记·全本朗读", "5 集 · 公版书"], ["红楼梦·程乙本", "4 集 · 公版书"],
    ["西游记·经典版", "5 集 · 公版书"], ["论语·诵读", "3 集 · 公版书"],
    ["厉害了 compatriots｜无敌系统爆笑修仙", "22 集 · 玄幻"], ["凡人修仙传·多人剧", "180 集 · 玄幻"],
]

MOCK = """(books) => {
  document.querySelectorAll('#splash,#toast,.sheet-mask,.sheet').forEach(e=>e.remove());
  const cats = document.getElementById('discoverCats');
  if (cats) cats.innerHTML = '<span class="chip on">全部</span><span class="chip">公版书</span>' +
    '<span class="chip">播客电台</span><span class="chip">个人音频库</span><span class="chip">玄幻</span>';
  const wrap = document.getElementById('homeContent');
  let h = '<div class="hero"><img src="appicon.png">' +
    '<div class="hmeta"><div class="hlabel">继续收听</div>' +
    '<div class="hname">厉害了 compatriots｜无敌系统爆笑修仙</div>' +
    '<div class="hsub">第 22 集 · 已听 12:41 / 36:20</div></div>' +
    '<div class="hbtn">继续</div></div>';
  for (const b of books) {
    h += '<div class="bookcard"><img src="appicon.png">' +
      '<div class="info"><div class="t">' + b[0] + '</div>' +
      '<div class="s">' + b[1] + '</div>' +
      '<div class="tagline"><span class="tag src">公版书</span></div></div>' +
      '<div class="fav-btn">\\u2606</div></div>';
  }
  h += '<div class="home-more"><div class="hm-btn">加载更多</div><div class="hm-count">已加载 30 本</div></div>';
  if (wrap) wrap.innerHTML = h;
}"""


async def shoot(page, w, h, tag):
    await page.set_viewport_size({"width": w, "height": h})
    await page.wait_for_timeout(600)
    await page.screenshot(path=f"{OUT}/car-home-{tag}.png")

    await page.evaluate("""() => {
        document.querySelectorAll('.page').forEach(p=>p.classList.add('hide'));
        const f = document.getElementById('pageFull');
        f.classList.remove('hide');
        document.body.classList.add('has-player');
        document.getElementById('fullBook').textContent = '厉害了 compatriots｜无敌系统爆笑修仙';
        document.getElementById('fullEp').textContent = '(22 / 1542)';
        document.getElementById('fullArtist').textContent = '白云出岫';
        document.getElementById('fullTitle').textContent = '正在播放';
        document.getElementById('fCur').textContent = '12:41';
        document.getElementById('fDur').textContent = '36:20';
        document.getElementById('fFill').style.width = '34%';
        document.getElementById('fullSourceTag').textContent = 'xmllysy';
        document.getElementById('fullCover').src = 'appicon.png';
        document.getElementById('fbgImg').src = 'appicon.png';
        document.getElementById('player').classList.add('hide');
        document.getElementById('tabbar').classList.add('hide');
    }""")
    await page.wait_for_timeout(500)
    await page.screenshot(path=f"{OUT}/car-player-{tag}.png")

    await page.evaluate("""() => {
        document.getElementById('pageFull').classList.add('hide');
        document.querySelectorAll('.page').forEach(p=>p.classList.add('hide'));
        document.getElementById('pageHome').classList.remove('hide');
        document.getElementById('tabbar').classList.remove('hide');
        const m = document.createElement('div');
        m.className = 'sheet-mask';
        m.innerHTML = '<div class="sheet"><div class="sheet-hd">选择章节</div>' +
          '<div class="sheet-item">第 1 集 · 初入宗门</div><div class="sheet-item on">第 2 集 · 系统觉醒</div>' +
          '<div class="sheet-item">第 3 集 · 打脸师兄</div><div class="sheet-item">第 4 集 · 下山历练</div>' +
          '<div class="sheet-item cancel">取消</div></div>';
        document.body.appendChild(m);
    }""")
    await page.wait_for_timeout(300)
    await page.screenshot(path=f"{OUT}/car-sheet-{tag}.png")

    # 「我的」页
    await page.evaluate("""() => {
        document.querySelectorAll('.sheet-mask').forEach(e=>e.remove());
        document.querySelectorAll('.page').forEach(p=>p.classList.add('hide'));
        document.getElementById('pageMine').classList.remove('hide');
    }""")
    await page.wait_for_timeout(300)
    await page.screenshot(path=f"{OUT}/car-mine-{tag}.png")


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        for w, h, tag in SIZES:
            ctx = await b.new_context(viewport={"width": w, "height": h}, device_scale_factor=1)
            page = await ctx.new_page()
            await page.goto("http://127.0.0.1:8765/index.html", wait_until="load")
            await page.wait_for_timeout(2500)
            await page.evaluate("() => { document.querySelectorAll('#splash,#toast,.sheet-mask').forEach(e=>e.remove()); }")
            await page.evaluate(MOCK, BOOKS)
            await shoot(page, w, h, tag)
            await ctx.close()
        await b.close()

asyncio.run(main())
