// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// The navbar, the mobile drawer and the page chrome (Phase 4 Task B2b):
//
//   1. the navbar is exactly --navbarHeight tall, on one row, and every
//      jump — a fresh load of `#id`, a TOC click, a focus move — lands
//      below it; scrolling is smooth unless the reader asked for reduced
//      motion;
//   2. above 50rem it stays one row (801–1600 px): the languages are a
//      dropdown, the search trigger is its icon alone at 64rem and below,
//      and a nav too long for the row stays inside the navbar;
//   3. columns above 50rem, and the 80rem three-column shell;
//   4–6. at 50rem and below, one row and a drawer (js/nav.js), with
//      fallbacks for no JS, no `@media (scripting)` and a failed nav.js;
//   7. the home's text card pads like every other main at 50rem and below.
//
// Smooth scrolling is on in every test, and focus scrolling animates too:
// positions are read under `reducedMotion: 'reduce'`, or once scrollY has
// been still for 10 frames; scrolls are `behavior: 'instant'`.

const homes = ['', 'zh-Hans/', 'zh-Hant/', 'ms/', 'ta/'];
const article = 'pages/c0nta1/';

// a site-relative path, each segment percent-encoded, plus an optional
// fragment (encoded too)
function url(t, rel, id) {
  const p = rel.split('/').map(encodeURIComponent).join('/');
  return new URL(p, t.baseURL).href + (id ? '#' + encodeURIComponent(id) : '');
}

// waits until scrollY has been still for 10 animation frames (3 s cap)
async function settle(page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(150);
  await page.evaluate(() => new Promise((resolve) => {
    let last = scrollY, still = 0;
    const t0 = performance.now();
    const f = () => {
      if (scrollY === last) still++; else { still = 0; last = scrollY; }
      if (still >= 10 || performance.now() - t0 > 3000) resolve(); else requestAnimationFrame(f);
    };
    requestAnimationFrame(f);
  }));
}

async function open(page, t, rel, id) {
  await page.goto(url(t, rel, id), { waitUntil: 'load' });
  await page.waitForSelector('button.pf-trigger-btn', { state: 'attached', timeout: 5000 }).catch(() => {});
  await settle(page);
}

async function scrollTop(page, top) {
  const y = await page.evaluate((top) => { scrollTo({ top, behavior: 'instant' }); return scrollY; }, top);
  return y;
}

// runs in the page: the navbar's box, --navbarHeight in px, and the
// controls of the row that are rendered, each with its box
function navbarState() {
  const nb = document.querySelector('.clogem-navbar');
  const probe = document.createElement('div');
  probe.style.cssText = 'position:absolute;height:var(--navbarHeight)';
  document.body.appendChild(probe);
  const nh = probe.getBoundingClientRect().height;
  probe.remove();
  const r = nb.getBoundingClientRect();
  const items = [...nb.querySelectorAll('.clogem-navbar__nav > ul > .clogem-navbar__item')]
    .map((li) => li.querySelector(':scope > a, :scope > span, :scope > details > summary'));
  const els = [
    ['brand', nb.querySelector('.clogem-navbar__brand')],
    ...items.map((e, i) => [`item ${i + 1}`, e]),
    ['languages', nb.querySelector('.clogem-langs__toggle > summary')],
    ['menu', nb.querySelector('.clogem-navbar__toggle > summary')],
    ['search', nb.querySelector('button.pf-trigger-btn')],
    ['mode', nb.querySelector('.clogem-mode__button')],
  ].filter(([, e]) => e && e.checkVisibility());
  const boxes = els.map(([n, e]) => { const b = e.getBoundingClientRect(); return { n, top: b.top, bottom: b.bottom, left: b.left, right: b.right }; });
  return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, height: r.height, nh, boxes,
           position: getComputedStyle(nb).position,
           overflow: document.documentElement.scrollWidth - innerWidth };
}

// item 1's height check: the navbar is --navbarHeight (±1) and ≤ 64 px,
// and its rendered controls lie inside it, on one row
function checkRow(t, s, what, { oneRow = true } = {}) {
  t.check(Math.abs(s.height - s.nh) <= 1 && s.height <= 64,
          `${what}: the navbar is ${s.height.toFixed(1)} px, want --navbarHeight ${s.nh.toFixed(1)} (≤ 64)`);
  for (const b of s.boxes) {
    t.check(b.top >= s.top - 0.5 && b.bottom <= s.bottom + 0.5 && b.left >= s.left - 0.5 && b.right <= s.right + 0.5,
            `${what}: ${b.n} (${b.left.toFixed(0)}–${b.right.toFixed(0)} × ${b.top.toFixed(1)}–${b.bottom.toFixed(1)}) is outside the navbar`);
  }
  if (oneRow && s.boxes.length) {
    const maxTop = Math.max(...s.boxes.map((b) => b.top));
    const minBottom = Math.min(...s.boxes.map((b) => b.bottom));
    t.check(maxTop <= minBottom, `${what}: not one row (largest top ${maxTop.toFixed(1)} > smallest bottom ${minBottom.toFixed(1)}: ${s.boxes.map((b) => b.n).join(', ')})`);
  }
}

// runs in the page: where heading `id` sits against the navbar
function anchorState(id) {
  const h = document.getElementById(id);
  const nb = document.querySelector('.clogem-navbar').getBoundingClientRect();
  const max = document.documentElement.scrollHeight - innerHeight;
  return { top: h ? h.getBoundingClientRect().top : NaN, navBottom: Math.max(0, nb.bottom),
           atMax: scrollY >= max - 1, scrollY };
}

function checkAnchor(t, a, what) {
  t.check(a.top >= a.navBottom - 0.5, `${what}: the target's top ${a.top.toFixed(1)} is under the navbar (bottom ${a.navBottom.toFixed(1)})`);
  t.check(a.atMax || a.top <= a.navBottom + 32, `${what}: the target's top ${a.top.toFixed(1)} is more than 32 px below the navbar (${a.navBottom.toFixed(1)})`);
}

// the fragments item 1 jumps to: [page, id, widths it is checked at]
const anchors = [
  [article, 'nesting'], [article, 'in-a-list-item'], [article, 'inside-a-fenced-code-block'],
  ['pages/17c887/', '你好世界'], ['', 'clogem-press-demo-site'],
  ['ta/pages/e19a7c/', 'அகலமான-அட்டவணை', 768], ['zh-Hans/pages/643259/', '语言后缀', 768],
];

async function heights(t, widths) {
  for (const width of widths) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    for (const rel of [...homes, article, 'categories/guide/', 'pages/559f0f/']) {
      await open(page, t, rel);
      checkRow(t, await page.evaluate(navbarState), `${width}px /${rel}`);
    }
    await page.close();
  }
}

async function anchorsAt(t, widths) {
  for (const width of widths) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    for (const [rel, id, maxWidth] of anchors) {
      if (maxWidth && width > maxWidth) continue;
      await open(page, t, rel, id);
      checkAnchor(t, await page.evaluate(anchorState, id), `${width}px, a fresh load of /${rel}#${id}`);
    }
    await page.close();
  }
}

// a target that is not a heading
async function nonHeading(t, widths) {
  for (const width of widths) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    await page.route(url(t, article), async (route) => {
      const res = await route.fetch();
      const body = (await res.text()).replace(/(<h2 id="in-a-list-item")/, '<p id="clogem-b2b-target">A target that is not a heading.</p>$1');
      await route.fulfill({ response: res, body });
    });
    await open(page, t, article, 'clogem-b2b-target');
    checkAnchor(t, await page.evaluate(anchorState, 'clogem-b2b-target'), `${width}px, a fresh load of #clogem-b2b-target (a <p>)`);
    await page.close();
  }
}

// ---------------------------------------------------------------------------
// 1. --navbarHeight, anchors below the navbar, smooth scrolling

async function item1(t) {
  await heights(t, [1024, 1280, 1440]);
  await anchorsAt(t, [1024, 1280, 1440]);

  // TOC clicks, where the TOC shows; an entry clicked is marked once
  // scrolling settles (not the last entries: an older toc.js bug, out of
  // scope, marks an earlier one at the end of the page)
  for (const width of [1280, 1440]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    for (const [rel, id] of [[article, 'nesting'], [article, 'in-a-list-item'], [article, 'inside-a-fenced-code-block'],
                             ['pages/17c887/', '你好世界']]) {
      await open(page, t, rel);
      const href = '#' + encodeURIComponent(id);
      await page.locator(`.clogem-toc a[href="${href}"]`).click();
      await settle(page);
      checkAnchor(t, await page.evaluate(anchorState, id), `${width}px /${rel}, TOC click on #${id}`);
      t.check(await page.evaluate((href) => document.querySelector(`.clogem-toc a[href="${href}"]`).parentElement.classList.contains('is-active'), href),
              `${width}px /${rel}: the TOC entry #${id} is active once scrolling settles`);
    }
    // the TOC sticks below the navbar
    await open(page, t, article);
    t.check(await scrollTop(page, 600) === 600, `${width}px: scrollY reached 600`);
    const [toc, nav] = await page.evaluate(() => [document.querySelector('.clogem-toc').getBoundingClientRect().top,
                                                  document.querySelector('.clogem-navbar').getBoundingClientRect().bottom]);
    t.check(toc >= nav, `${width}px: the stuck TOC's top ${toc} is under the navbar (bottom ${nav})`);
    await page.close();
  }

  await nonHeading(t, [1280]);

  // the TOC's offset follows the navbar: with --navbarHeight 6rem the
  // navbar is 96 px and headings land at 108; a fixed offset of 80 fails
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 } });
    await page.route('**/clogem/css/theme.css*', async (route) => {
      const res = await route.fetch();
      await route.fulfill({ response: res, body: (await res.text()) + '\n:root { --navbarHeight: 6rem }\n' });
    });
    await open(page, t, article);
    const h = await page.evaluate(() => document.querySelector('.clogem-navbar').getBoundingClientRect().height);
    t.check(Math.abs(h - 96) <= 1, `--navbarHeight 6rem: the navbar is ${h} px, want 96`);
    for (const id of ['in-a-list-item', 'nesting']) {
      await page.locator(`.clogem-toc a[href="#${id}"]`).click();
      await settle(page);
      const a = await page.evaluate(anchorState, id);
      t.check(Math.abs(a.top - 108) <= 1.5, `--navbarHeight 6rem: #${id} lands at ${a.top}, want 108`);
      t.check(await page.evaluate((id) => document.querySelector(`.clogem-toc a[href="#${id}"]`).parentElement.classList.contains('is-active'), id),
              `--navbarHeight 6rem: the TOC entry #${id} is active once scrolling settles`);
    }
    await page.close();
  }

  // focus: Shift+Tab back from the end never leaves a focused element under
  // the navbar (WCAG 2.2 SC 2.4.11)
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: 'reduce' });
    await open(page, t, article);
    await page.evaluate(() => {
      const all = [...document.querySelectorAll('a[href], button, summary, input, [tabindex]:not([tabindex="-1"])')]
        .filter((e) => e.checkVisibility() && !e.closest('[inert]'));
      all[all.length - 1].focus();
    });
    let bad = 0;
    for (let i = 0; i < 30; i++) {
      await page.keyboard.press('Shift+Tab');
      const f = await page.evaluate(() => {
        const e = document.activeElement;
        const nb = document.querySelector('.clogem-navbar');
        if (!e || e === document.body || nb.contains(e)) return null;
        return { what: e.tagName.toLowerCase() + (typeof e.className === 'string' && e.className ? '.' + e.className.split(' ')[0] : ''),
                 bottom: e.getBoundingClientRect().bottom, top: e.getBoundingClientRect().top, nav: nb.getBoundingClientRect().bottom };
      });
      if (f && f.bottom <= f.nav) {
        bad++;
        t.check(false, `Shift+Tab stop ${i + 1} (${f.what}, y ${f.top.toFixed(0)}–${f.bottom.toFixed(0)}) is under the navbar (bottom ${f.nav.toFixed(0)})`);
      }
    }
    t.check(bad === 0, `focus never lands under the navbar (${bad} stops did)`);
    await page.close();
  }

  // smooth scrolling, unless reduced motion
  for (const rm of ['no-preference', 'reduce']) {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: rm });
    await open(page, t, article);
    const sb = await page.evaluate(() => getComputedStyle(document.documentElement).scrollBehavior);
    t.check(sb === (rm === 'reduce' ? 'auto' : 'smooth'), `${rm}: the root's scroll-behavior is ${sb}`);
    t.check(await scrollTop(page, 0) === 0, `${rm}: scrollY reached 0`);
    await page.evaluate(() => {
      window.__samples = [];
      document.querySelector('.clogem-toc a[href="#in-a-list-item"]').addEventListener('click', () => {
        const f = () => { window.__samples.push(scrollY); if (window.__samples.length < 240) requestAnimationFrame(f); };
        requestAnimationFrame(f);
      }, { once: true });
    });
    await page.locator('.clogem-toc a[href="#in-a-list-item"]').click();
    await settle(page);
    const { samples, final } = await page.evaluate(() => ({ samples: window.__samples, final: scrollY }));
    t.check(final > 1000, `${rm}: the click scrolled to ${final}`);
    if (rm === 'reduce') t.check(samples[0] === final, `reduce: the first sample ${samples[0]} is already the final ${final}`);
    else t.check(samples.some((y) => y > 0 && y < final), `no-preference: a frame between 0 and ${final} (${samples.slice(0, 8).join(', ')}…)`);
    await page.close();
  }
}

// ---------------------------------------------------------------------------
// 2. One row above 50rem: the language dropdown, the trigger, long navs

async function item2(t) {
  // one row at every width from 801 to 1600, resized rather than reloaded
  {
    const page = await t.newPage({ viewport: { width: 1600, height: 740 } });
    for (const rel of [...homes, article]) {
      await open(page, t, rel);
      for (let width = 801; width <= 1600; width += 10) {
        await page.setViewportSize({ width, height: 740 });
        checkRow(t, await page.evaluate(navbarState), `${width}px /${rel}`);
      }
    }
    await page.close();
  }

  // the dropdown
  for (const width of [801, 1280]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    await open(page, t, '');
    const vis = await page.evaluate(() => [document.querySelector('.clogem-langs__label').checkVisibility(),
                                           document.querySelector('.clogem-langs__toggle > summary').checkVisibility(),
                                           document.querySelector('.clogem-langs ul').checkVisibility()]);
    t.check(!vis[0] && vis[1] && !vis[2], `${width}px: the label is hidden, the summary shown, the closed list hidden (${vis})`);
    await page.focus('.clogem-langs__toggle > summary');
    await page.keyboard.press('Enter');
    t.check(await page.evaluate(() => document.querySelector('.clogem-langs__toggle').open), `${width}px: Enter opens the language dropdown`);
    const n = await page.evaluate(() => document.querySelectorAll('.clogem-langs ul a').length);
    const reached = new Set();
    for (let i = 0; i < n; i++) {
      await page.keyboard.press('Tab');
      reached.add(await page.evaluate(() => document.activeElement.closest('.clogem-langs ul') ? document.activeElement.getAttribute('data-clogem-lang') : null));
    }
    reached.delete(null);
    t.check(reached.size === n, `${width}px: Tab reaches every language link (${[...reached].join(' ')} of ${n})`);
    const box = await page.evaluate(() => { const r = document.querySelector('.clogem-langs ul').getBoundingClientRect();
      return { left: r.left, right: r.right, overflow: document.documentElement.scrollWidth - innerWidth }; });
    t.check(box.left >= 0 && box.right <= width && box.overflow <= 0, `${width}px: the open list (${box.left}–${box.right}) fits, overflow ${box.overflow}`);
    await page.evaluate(() => localStorage.removeItem('clogem-lang'));
    await Promise.all([page.waitForURL(/\/ms\/$/), page.locator('.clogem-langs a[data-clogem-lang="ms"]').click()]);
    t.check(await page.evaluate(() => localStorage.getItem('clogem-lang')) === 'ms', `${width}px: a click in the dropdown stores the preference`);
    await page.close();
  }
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, javaScriptEnabled: false });
    await page.goto(url(t, ''), { waitUntil: 'load' });
    const summary = page.locator('.clogem-langs__toggle > summary');
    await summary.click();
    const opened = await page.evaluate(() => [document.querySelector('.clogem-langs__toggle').open, document.querySelector('.clogem-langs ul').checkVisibility()]);
    await summary.click();
    const closed = await page.evaluate(() => [document.querySelector('.clogem-langs__toggle').open, document.querySelector('.clogem-langs ul').checkVisibility()]);
    t.check(opened[0] && opened[1] && !closed[0] && !closed[1], `no JS: the summary opens (${opened}) and closes (${closed}) the list`);
    await page.close();
  }

  // the trigger: its icon alone at 801–1024, named in the page's language
  for (const [rel, name] of [['', 'Search'], ['ms/', 'Cari'], ['zh-Hant/', '搜尋']]) {
    const page = await t.newPage({ viewport: { width: 801, height: 740 } });
    await open(page, t, rel);
    for (const width of [801, 900, 1024, 1025, 1280]) {
      await page.setViewportSize({ width, height: 740 });
      const s = await page.evaluate(() => {
        const b = document.querySelector('button.pf-trigger-btn');
        const r = b.getBoundingClientRect();
        const shown = (sel) => !!b.querySelector(sel) && b.querySelector(sel).checkVisibility();
        return { w: r.width, h: r.height, label: b.getAttribute('aria-label'), text: shown('.pf-trigger-text'),
                 shortcut: shown('.pf-trigger-shortcut'), icon: shown('.pf-trigger-icon') };
      });
      if (width <= 1024) {
        t.check(!s.text && !s.shortcut && s.icon && s.w >= 24 && s.h >= 24,
                `${width}px /${rel}: the trigger is its icon alone, ≥ 24×24 (${JSON.stringify(s)})`);
      } else {
        t.check(s.text, `${width}px /${rel}: above 64rem the trigger's text shows`);
      }
      t.check(s.label === name, `${width}px /${rel}: the trigger is named "${s.label}", want "${name}"`);
    }
    await page.setViewportSize({ width: 900, height: 740 });
    await page.locator('button.pf-trigger-btn').click();
    await page.waitForFunction(() => document.querySelector('pagefind-modal dialog')?.open, null, { timeout: 5000 }).catch(() => {});
    const m = await page.evaluate(() => {
      const d = document.querySelector('pagefind-modal dialog');
      const sc = d && d.querySelector('.pf-trigger-shortcut');
      return { open: !!d && d.open, shortcut: sc ? getComputedStyle(sc).display : 'absent' };
    });
    t.check(m.open, `900px /${rel}: the icon-only trigger opens the modal`);
    t.check(m.shortcut !== 'none', `900px /${rel}: the modal's own shortcut hint is still displayed (${m.shortcut})`);
    await page.close();
  }

  // a nav too long for one row stays inside the navbar
  {
    const page = await t.newPage({ viewport: { width: 1024, height: 740 }, reducedMotion: 'reduce' });
    await page.route(url(t, article), async (route) => {
      const res = await route.fetch();
      const extra = Array.from({ length: 10 }, (_, i) => `<li class="clogem-navbar__item"><a href="/">Item ${i + 1}</a></li>`).join('');
      const body = (await res.text()).replace('<nav class="clogem-navbar__nav"><ul>', '<nav class="clogem-navbar__nav"><ul>' + extra);
      await route.fulfill({ response: res, body });
    });
    await open(page, t, article, 'nesting');
    const s = await page.evaluate(navbarState);
    t.check(s.boxes.filter((b) => b.n.startsWith('item')).length >= 13, `a long nav: ${s.boxes.length} controls rendered`);
    checkRow(t, s, '1024px, a long nav', { oneRow: false });
    t.check(s.overflow <= 0, `a long nav: the page overflows by ${s.overflow}`);
    const children = await page.evaluate(() => {
      const nb = document.querySelector('.clogem-navbar'), r = nb.getBoundingClientRect();
      return [...nb.querySelectorAll('.clogem-navbar__nav li, .clogem-navbar > *, #clogem-drawer > *')]
        .filter((e) => e.checkVisibility())
        .map((e) => { const b = e.getBoundingClientRect(); return [e.tagName + '.' + e.className, b.top >= r.top - 0.5 && b.bottom <= r.bottom + 0.5 && b.left >= r.left - 0.5 && b.right <= r.right + 0.5]; })
        .filter(([, ok]) => !ok).map(([n]) => n);
    });
    t.check(children.length === 0, `a long nav: every navbar child is inside the navbar (${children.join(', ')})`);
    checkAnchor(t, await page.evaluate(anchorState, 'nesting'), 'a long nav, a fresh load of #nesting');
    await page.close();
  }
}

// ---------------------------------------------------------------------------
// 3. Columns above 50rem, and the 80rem three-column shell

const box = (sel) => {
  const e = document.querySelector(sel);
  if (!e) return null;
  const r = e.getBoundingClientRect();
  return { left: r.left, right: r.right, top: r.top + scrollY, width: r.width, display: getComputedStyle(e).display };
};

async function item3(t) {
  // two columns at 900 and 1024: the sidebar left of main, the same top
  for (const width of [900, 1024]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    for (const rel of [article, 'pages/cjk001/']) {
      await open(page, t, rel);
      const r = await page.evaluate(([box]) => {
        const f = new Function('return ' + box)();
        return { sb: f('aside.clogem-sidebar'), main: f('main'), toc: f('.clogem-toc'), overflow: document.documentElement.scrollWidth - innerWidth };
      }, [box.toString()]);
      t.check(r.sb && r.sb.right <= r.main.left && Math.abs(r.sb.top - r.main.top) <= 1,
              `${width}px /${rel}: the sidebar (${r.sb && r.sb.left}–${r.sb && r.sb.right}, y ${r.sb && r.sb.top}) is left of main (${r.main.left}, y ${r.main.top})`);
      t.check(r.overflow <= 0, `${width}px /${rel}: overflow ${r.overflow}`);
      if (r.toc) t.check(r.toc.display === 'none', `${width}px /${rel}: below 80rem the TOC is display: none (${r.toc.display})`);
    }
    await page.close();
  }
  // three columns from 80rem, the article at least 600 px
  for (const width of [1280, 1440]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    await open(page, t, article);
    const r = await page.evaluate(([box]) => {
      const f = new Function('return ' + box)();
      return { sb: f('aside.clogem-sidebar'), main: f('main'), toc: f('.clogem-toc'), art: f('article.clogem-article') };
    }, [box.toString()]);
    t.check(r.art.width >= 600, `${width}px: article.clogem-article is ${r.art.width} px, want ≥ 600`);
    t.check(r.sb.right <= r.main.left && r.main.right <= r.toc.left && r.toc.display !== 'none',
            `${width}px: sidebar ${r.sb.left}–${r.sb.right}, main ${r.main.left}–${r.main.right}, TOC ${r.toc.left}–${r.toc.right} run left to right`);
    await page.close();
  }
  // beside a 17 px classic scrollbar (the media query still sees 1280)
  // the article is 615: a 2rem gap would leave it at 599. Headless
  // Chromium hides scrollbars, so a 17 px body margin takes its place
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 } });
    await open(page, t, article);
    const w = await page.evaluate(() => {
      document.body.style.marginInlineEnd = '17px';
      return { art: document.querySelector('article.clogem-article').getBoundingClientRect().width,
               shell: document.querySelector('.clogem-shell').getBoundingClientRect().width };
    });
    t.check(w.shell === 1263 && w.art >= 600, `1280px beside a 17 px scrollbar (a ${w.shell} px shell): the article is ${w.art} px, want ≥ 600`);
    await page.close();
  }
  // 1279: below 80rem, no TOC beside a sidebar
  {
    const page = await t.newPage({ viewport: { width: 1279, height: 740 } });
    await open(page, t, article);
    t.check(await page.evaluate(() => getComputedStyle(document.querySelector('.clogem-toc')).display) === 'none', '1279px: the TOC is display: none');
    await page.close();
  }
  // pages without a sidebar and a TOC keep the 62rem shell
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 } });
    for (const rel of ['', 'categories/guide/', 'pages/559f0f/']) {
      await open(page, t, rel);
      const w = await page.evaluate(() => document.querySelector('main').getBoundingClientRect().width);
      t.check(Math.abs(w - 952) <= 1, `1280px /${rel}: main is ${w} px, want 952`);
    }
    await page.close();
  }
  // a --single --toc page (a post with no tree) keeps its TOC above 64rem
  {
    const page = await t.newPage({ viewport: { width: 1100, height: 740 } });
    await page.route(url(t, article), async (route) => {
      const res = await route.fetch();
      const body = (await res.text()).replace(/<aside class="clogem-sidebar">[\s\S]*?<\/aside>/, '')
        .replace('class="clogem-shell clogem-shell--toc"', 'class="clogem-shell clogem-shell--single clogem-shell--toc"');
      await route.fulfill({ response: res, body });
    });
    await open(page, t, article);
    t.check(await page.evaluate(() => !document.querySelector('aside.clogem-sidebar') && !!document.querySelector('.clogem-shell--single.clogem-shell--toc')),
            '--single --toc: the rewrite took');
    for (const width of [1100, 1280, 1024]) {
      await page.setViewportSize({ width, height: 740 });
      const r = await page.evaluate(([box]) => { const f = new Function('return ' + box)(); return { main: f('main'), toc: f('.clogem-toc') }; }, [box.toString()]);
      if (width === 1024) t.check(r.toc.display === 'none', `--single --toc at 1024: the TOC is display: none (${r.toc.display})`);
      else t.check(r.toc.display !== 'none' && r.toc.left >= r.main.right, `--single --toc at ${width}: the TOC (${r.toc.left}) is right of main (${r.main.right})`);
    }
    await page.close();
  }
  // a long TOC scrolls inside itself, clear of the back-to-top band
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 } });
    await page.route(url(t, article), async (route) => {
      const res = await route.fetch();
      const lis = Array.from({ length: 60 }, (_, i) => `<li class="level-2"><a href="#nesting">Entry ${i + 1}</a></li>`).join('');
      const body = (await res.text()).replace(/(<aside class="clogem-toc"><nav[^>]*><ul>)/, '$1' + lis);
      await route.fulfill({ response: res, body });
    });
    await open(page, t, article);
    t.check(await scrollTop(page, 600) === 600, 'a long TOC: scrollY reached 600');
    const r = await page.evaluate(() => {
      const toc = document.querySelector('.clogem-toc');
      const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
      return { n: toc.querySelectorAll('a').length, bottom: toc.getBoundingClientRect().bottom, bound: innerHeight - 4.7 * rem,
               scrolls: toc.scrollHeight > toc.clientHeight };
    });
    t.check(r.n >= 60 && r.bottom <= r.bound + 0.5, `a long TOC (${r.n} links): its bottom ${r.bottom} ≤ innerHeight − 4.7rem (${r.bound})`);
    t.check(r.scrolls, 'a long TOC scrolls');
    const last = page.locator('.clogem-toc a').last();
    await last.scrollIntoViewIfNeeded();
    const vis = await page.evaluate(() => {
      const toc = document.querySelector('.clogem-toc'), a = [...toc.querySelectorAll('a')].pop();
      const tr = toc.getBoundingClientRect(), ar = a.getBoundingClientRect();
      return ar.top >= tr.top - 0.5 && ar.bottom <= tr.bottom + 0.5 && toc.scrollTop > 0;
    });
    t.check(vis, 'a long TOC: its last link can be reached by scrolling the TOC');
    await page.close();
  }
}

export default async function layout(t) {
  await item1(t);
  await item2(t);
  await item3(t);
}
