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
//  10. the sidebar's collapse button (js/nav.js), above 50rem only;
//  11. the page buttons, from 84rem only, never over a box or a link;
//  12. the background patterns on the main column;
//  13. back to top (js/nav.js): shown past 100 px, hidden once used, and
//      covering no link.
//
// Smooth scrolling is on in every test, and focus scrolling animates too:
// positions are read under `reducedMotion: 'reduce'`, or once scrollY has
// been still for 10 frames; scrolls are `behavior: 'instant'`.

import { spawn } from 'node:child_process';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
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

// a click at the centre of `sel`'s box: locator.click() scrolls the page
// first (scrollIntoViewIfNeeded), which a test of scroll positions must not
async function clickAt(page, sel) {
  const c = await page.evaluate((sel) => { const r = document.querySelector(sel).getBoundingClientRect();
    return [r.left + r.width / 2, r.top + r.height / 2]; }, sel);
  await page.mouse.click(c[0], c[1]);
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
function checkRow(t, s, what, { oneRow = true, height = true } = {}) {
  if (height) t.check(Math.abs(s.height - s.nh) <= 1 && s.height <= 64,
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
    // an <a name> (raw HTML in Markdown) and scrollIntoView() on an element
    // without an id land below the navbar too (P4-B2b.1: the margin on
    // [id] alone missed them; the root's padding had covered them)
    const named = await t.newPage({ viewport: { width, height: 740 }, reducedMotion: 'reduce' });
    await named.route(url(t, article), async (route) => {
      const res = await route.fetch();
      const body = (await res.text()).replace(/(<h2 id="in-a-list-item")/, '<p><a name="clogem-b2b-name"></a>A target named, not ided.</p>$1');
      await route.fulfill({ response: res, body });
    });
    await open(named, t, article, 'clogem-b2b-name');
    const state = () => named.evaluate(() => {
      const p = document.querySelector('a[name="clogem-b2b-name"]').parentElement;
      return { top: p.getBoundingClientRect().top, navBottom: Math.max(0, document.querySelector('.clogem-navbar').getBoundingClientRect().bottom),
               atMax: scrollY >= document.documentElement.scrollHeight - innerHeight - 1, scrollY };
    });
    checkAnchor(t, await state(), `${width}px, a fresh load of #clogem-b2b-name (an <a name>)`);
    await scrollTop(named, 0);
    await named.evaluate(() => document.querySelector('a[name="clogem-b2b-name"]').parentElement.scrollIntoView());
    checkAnchor(t, await state(), `${width}px, scrollIntoView() on a <p> without an id`);
    await named.close();
  }
}

// ---------------------------------------------------------------------------
// 1. --navbarHeight, anchors below the navbar, smooth scrolling

async function item1(t) {
  await heights(t, [1024, 1280, 1440]);
  await anchorsAt(t, [1024, 1280, 1440]);

  // TOC clicks, where the TOC shows; an entry clicked is marked once
  // scrolling settles, the last ones too, whose heading cannot reach the top
  // (P4-B2b.1 item 9: toc.js marked an earlier entry at the end of the page)
  for (const [width, height] of [[1280, 740], [1440, 740], [1280, 1024]]) {
    const page = await t.newPage({ viewport: { width, height } });
    for (const [rel, id] of [[article, 'nesting'], [article, 'in-a-list-item'], [article, 'inside-a-fenced-code-block'],
                             ['pages/17c887/', '你好世界'], ['pages/17c887/', 'வணக்கம்-உலகம்'], ['pages/17c887/', 'hello,-world!'],
                             ['pages/17c887/', '100%-done'], ['pages/17c887/', 'bahasa-melayu']]) {
      await open(page, t, rel);
      // the href as the page writes it (`,` and `!` stay unencoded)
      const href = await page.evaluate((id) => [...document.querySelectorAll('.clogem-toc a')]
        .map((a) => a.getAttribute('href')).find((h) => decodeURIComponent(h.slice(1)) === id), id);
      t.check(!!href, `${width}×${height} /${rel}: a TOC entry for #${id}`);
      if (!href) continue;
      await page.locator(`.clogem-toc a[href="${href}"]`).click();
      await settle(page);
      checkAnchor(t, await page.evaluate(anchorState, id), `${width}×${height} /${rel}, TOC click on #${id}`);
      t.check(await page.evaluate((href) => document.querySelector(`.clogem-toc a[href="${href}"]`).parentElement.classList.contains('is-active'), href),
              `${width}×${height} /${rel}: the TOC entry #${id} is active once scrolling settles`);
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

  // the end of the page, beyond a click (P4-B2b.1 item 9): a fragment
  // followed mid-page does not win once the reader scrolls on to the end
  // (the last heading in view does); and a page that grows below the reader
  // after load (giscus adds its iframe) still marks the entry clicked
  for (const rm of ['reduce', 'no-preference']) {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: rm });
    await open(page, t, article, 'nesting');
    await page.evaluate(() => scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' }));
    await settle(page);
    const r = await page.evaluate(() => {
      const ls = [...document.querySelectorAll('.clogem-toc a')];
      const inView = ls.filter((a) => document.getElementById(decodeURIComponent(a.getAttribute('href').slice(1))).getBoundingClientRect().top < innerHeight);
      return { want: inView.pop().getAttribute('href'), got: document.querySelector('.clogem-toc li.is-active a').getAttribute('href') };
    });
    t.check(r.got === r.want, `${rm}: #nesting followed, then the end of the page: ${r.got} is active, want the last heading in view ${r.want}`);
    await page.close();
  }
  // a followed target that is no TOC heading (an h4) marks its section
  {
    const page = await t.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    await page.route(url(t, article), async (route) => {
      const res = await route.fetch();
      const body = (await res.text()).replace('<div class="custom-block tip">\n<p class="custom-block-title">A heading inside</p>',
        '<h4 id="clogem-b2b-h4">A deeper heading</h4><div class="custom-block tip">\n<p class="custom-block-title">A heading inside</p>');
      await route.fulfill({ response: res, body });
    });
    await open(page, t, article, 'clogem-b2b-h4');
    const r = await page.evaluate(() => ({ h4: !!document.getElementById('clogem-b2b-h4'),
      end: scrollY >= document.documentElement.scrollHeight - innerHeight - 1,
      active: document.querySelector('.clogem-toc li.is-active a').getAttribute('href') }));
    t.check(r.h4 && r.end && r.active === '#inside-a-fenced-code-block',
            `an h4 followed to the end of the page: ${r.active} is active, want its section #inside-a-fenced-code-block (${JSON.stringify(r)})`);
    await page.close();
  }
  // at the end of a page that then grows below the reader (giscus), the
  // end-of-page choice is dropped for the line's (a ResizeObserver; no scroll)
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 1024 }, reducedMotion: 'reduce' });
    await open(page, t, 'pages/17c887/');
    await page.evaluate(() => scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' }));
    await settle(page);
    const atEnd = await page.evaluate(() => document.querySelector('.clogem-toc li.is-active a').getAttribute('href'));
    await page.evaluate(() => { const d = document.createElement('div'); d.style.height = '600px'; document.querySelector('main').appendChild(d); });
    await settle(page);
    const r = await page.evaluate(() => {
      const line = document.querySelector('.clogem-navbar').getBoundingClientRect().bottom + 16;
      const ls = [...document.querySelectorAll('.clogem-toc a')];
      const passed = ls.filter((a) => document.getElementById(decodeURIComponent(a.getAttribute('href').slice(1))).getBoundingClientRect().top <= line);
      return { want: (passed.pop() || ls[0]).getAttribute('href'), got: document.querySelector('.clogem-toc li.is-active a').getAttribute('href'),
               end: scrollY >= document.documentElement.scrollHeight - innerHeight - 1 };
    });
    t.check(!r.end && r.got === r.want && r.got !== atEnd,
            `the end of /pages/17c887/ (${atEnd} active), then 600 px more below: ${r.got} is active, want the line's ${r.want} (${JSON.stringify(r)})`);
    await page.close();
  }
  // a page that fits the viewport marks its first entry, not its last
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 2400 }, reducedMotion: 'reduce' });
    await open(page, t, 'pages/17c887/');
    const r = await page.evaluate(() => ({ max: document.documentElement.scrollHeight - innerHeight,
      first: document.querySelector('.clogem-toc a').getAttribute('href'),
      active: document.querySelector('.clogem-toc li.is-active a').getAttribute('href') }));
    t.check(r.max <= 0 && r.active === r.first, `a page that fits (${r.max}): ${r.active} is active, want the first entry ${r.first}`);
    await page.close();
  }
  for (const rm of ['reduce', 'no-preference']) {
    // /zh-Hans/pages/643259/ fits 1280×1024 when toc.js first looks; then
    // 150 px join the page below the article, as giscus's iframe does
    const page = await t.newPage({ viewport: { width: 1280, height: 1024 }, reducedMotion: rm });
    await open(page, t, 'zh-Hans/pages/643259/');
    const fits = await page.evaluate(() => document.documentElement.scrollHeight - innerHeight);
    await page.evaluate(() => { const d = document.createElement('div'); d.style.height = '150px'; document.querySelector('main').appendChild(d); });
    await settle(page);
    const first = await page.evaluate(() => [document.querySelector('.clogem-toc li.is-active a').getAttribute('href'),
                                             document.querySelector('.clogem-toc a').getAttribute('href'), scrollY]);
    t.check(first[0] === first[1] && first[2] === 0, `${rm}: at the top of the grown page the first entry is active (${first})`);
    // the first entry: its heading cannot reach the top either, and the
    // last heading in view is another
    const href = '#' + encodeURIComponent('编号目录');
    await clickAt(page, `.clogem-toc a[href="${href}"]`);
    await settle(page);
    const got = await page.evaluate(() => [document.querySelector('.clogem-toc li.is-active a').getAttribute('href'),
                                           scrollY, document.documentElement.scrollHeight - innerHeight]);
    t.check(fits <= 0 && got[1] > 0 && got[1] >= got[2] - 1 && got[0] === href,
            `${rm}: a page that fitted (${fits}) grows 150 px, a click on #编号目录, which ends at the end, marks it (${got})`);
    await page.close();
  }

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

  // focus: Shift+Tab from the last stop back through every stop never leaves
  // a focused element's top above the navbar's bottom (WCAG 2.2 SC 2.4.11).
  // P4-B2b.2: Chromium 141 does not scroll a focus already inside the
  // viewport, so one at y 0–57 stayed under the navbar; js/nav.js moves it
  // out. Every height, since the stops' positions decide which ones hide
  for (const [rm, sizes] of [['reduce', [360, 1024, 1280].flatMap((w) => [600, 740, 800, 900].map((h) => [w, h]))],
                             ['no-preference', [[1280, 800]]]]) {
    for (const [width, height] of sizes) {
      const page = await t.newPage({ viewport: { width, height }, reducedMotion: rm });
      for (const rel of [article, '', 'pages/17c887/']) {
        await open(page, t, rel);
        await page.evaluate(() => {
          const all = [...document.querySelectorAll('a[href], button, summary, input, iframe, [tabindex]:not([tabindex="-1"])')]
            .filter((e) => e.checkVisibility() && !e.closest('[inert]'));
          scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' });
          all[all.length - 1].focus();
        });
        await settle(page);
        let bad = 0, n = 0;
        for (let i = 0; i < 200; i++) {
          await page.keyboard.press('Shift+Tab');
          if (rm !== 'reduce') await settle(page);
          const f = await page.evaluate(() => {
            const e = document.activeElement;
            const nb = document.querySelector('.clogem-navbar');
            if (!e || e === document.body || nb.contains(e)) return null;
            return { what: e.tagName.toLowerCase() + (typeof e.className === 'string' && e.className ? '.' + e.className.split(' ')[0] : ''),
                     top: e.getBoundingClientRect().top, bottom: e.getBoundingClientRect().bottom, nav: nb.getBoundingClientRect().bottom };
          });
          if (!f) break;   // the navbar: every stop before it is checked
          n++;
          if (f.top < f.nav - 1) {
            bad++;
            t.check(false, `${width}×${height} ${rm} /${rel}: Shift+Tab stop ${i + 1} (${f.what}, y ${f.top.toFixed(1)}–${f.bottom.toFixed(1)}) is under the navbar (bottom ${f.nav.toFixed(1)})`);
          }
        }
        t.check(n >= 10 && bad === 0, `${width}×${height} ${rm} /${rel}: ${n} Shift+Tab stops before the navbar, ${bad} under it`);
      }
      await page.close();
    }
  }

  // smooth scrolling, unless reduced motion
  for (const rm of ['no-preference', 'reduce']) {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: rm });
    await open(page, t, article);
    // once loaded: nav.js has removed clogem-loading (P4-B2b.1 item 6)
    const sb = await page.evaluate(() => [getComputedStyle(document.documentElement).scrollBehavior,
                                          document.documentElement.classList.contains('clogem-loading')]);
    t.check(sb[0] === (rm === 'reduce' ? 'auto' : 'smooth') && !sb[1], `${rm}: the loaded page's root scroll-behavior is ${sb[0]} (clogem-loading ${sb[1]})`);
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

// P4-B2b.1 item 6: under no-preference a fresh load of `#id` jumps at once
// (root scroll-behavior: smooth animated the browser's own jump from the top
// for ~600 ms), while a TOC click and a heading anchor's click stay smooth
async function item1Load(t) {
  const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: 'no-preference' });
  await page.addInitScript(() => {
    window.__frames = [];
    const f = () => { window.__frames.push(scrollY); if (window.__frames.length < 600) requestAnimationFrame(f); };
    requestAnimationFrame(f);
    addEventListener('load', () => { window.__load = window.__frames.length; });
  });
  await open(page, t, article, 'nesting');
  const r = await page.evaluate(() => {
    const fin = scrollY, fr = window.__frames;
    return { fin, load: window.__load, reached: fr.indexOf(fin), between: fr.filter((y) => y > 0 && y < fin).length };
  });
  t.check(r.fin > 500 && r.between === 0, `a fresh load of #nesting: ${r.between} frames between 0 and the final ${r.fin}`);
  t.check(r.reached >= 0 && r.reached <= r.load + 2, `a fresh load of #nesting: the final scrollY at frame ${r.reached}, load at ${r.load}`);
  // in-page jumps stay smooth: a TOC click, a click on a heading's #, Enter
  // on a focused TOC link (Chromium drops focus before the jump, so a
  // :focus-within rule missed it) and history back, with the pointer away
  const frames = () => page.evaluate(() => { window.__y0 = scrollY; window.__s = []; const f = () => { window.__s.push(scrollY); if (window.__s.length < 240) requestAnimationFrame(f); }; requestAnimationFrame(f); });
  const toc = '.clogem-toc a[href="#in-a-list-item"]';
  const jumps = [
    ['a TOC click', async () => { await scrollTop(page, 0); await settle(page); await frames(); await clickAt(page, toc); }],
    ['a heading anchor click', async () => {
      await page.evaluate(() => scrollTo({ top: document.getElementById('inside-a-fenced-code-block').getBoundingClientRect().top + scrollY - 500, behavior: 'instant' }));
      await settle(page); await frames(); await clickAt(page, '#inside-a-fenced-code-block .header-anchor'); }],
    ['Enter on a focused TOC link', async () => {
      await page.evaluate(() => { history.replaceState(null, '', location.pathname); });
      await scrollTop(page, 0); await settle(page); await page.mouse.move(1270, 730);
      await page.focus(toc); await frames(); await page.keyboard.press('Enter'); }],
    ['history back', async () => {
      await scrollTop(page, 0); await settle(page); await clickAt(page, toc); await settle(page);
      await page.mouse.move(1270, 730); await frames(); await page.evaluate(() => history.back()); }],
  ];
  // before load (giscus, a slow image): a click turns smooth scrolling on
  {
    const slow = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: 'no-preference' });
    await slow.route(url(t, article), async (route) => {
      const res = await route.fetch();
      await route.fulfill({ response: res, body: (await res.text()).replace('</main>', '<img alt="" src="/clogem-b2b-pending.png"></main>') });
    });
    await slow.route('**/clogem-b2b-pending.png', () => {});   // never answered
    await slow.goto(url(t, article), { waitUntil: 'domcontentloaded' });
    await slow.waitForFunction(() => document.documentElement.classList.contains('clogem-loading'), null, { timeout: 5000 }).catch(() => {});
    await slow.waitForTimeout(300);
    const before = await slow.evaluate(() => [document.readyState, document.documentElement.classList.contains('clogem-loading')]);
    await slow.evaluate(() => { window.__y0 = scrollY; window.__s = []; const f = () => { window.__s.push(scrollY); if (window.__s.length < 240) requestAnimationFrame(f); }; requestAnimationFrame(f); });
    await clickAt(slow, toc);
    await settle(slow);
    const s = await slow.evaluate(() => ({ samples: window.__s, from: window.__y0, fin: scrollY }));
    t.check(before[0] !== 'complete' && before[1] && s.fin - s.from > 100 && s.samples.some((y) => y > s.from && y < s.fin),
            `a TOC click before load (${before}): from ${s.from} to ${s.fin}, a frame strictly between (${s.samples.slice(0, 6).join(', ')}…)`);
    await slow.close();
  }
  for (const [what, act] of jumps) {
    await act();
    await settle(page);
    const s = await page.evaluate(() => ({ samples: window.__s, from: window.__y0, fin: scrollY }));
    const lo = Math.min(s.from, s.fin), hi = Math.max(s.from, s.fin);
    t.check(hi - lo > 100 && s.samples.some((y) => y > lo && y < hi),
            `${what}: from ${s.from} to ${s.fin}, a frame strictly between (${s.samples.slice(0, 6).join(', ')}…)`);
  }
  await page.close();
}

// P4-B2b.1 item 1: focusing a navbar control never moves the page. Part 1's
// root scroll-padding covered the whole sticky navbar, so Chromium took every
// control in it for obscured and centred it on focus — the page jumped up
async function item1Focus(t) {
  for (const [width, height] of [[360, 740], [768, 1024], [1280, 800]]) {
    for (const rm of ['no-preference', 'reduce']) {
      const page = await t.newPage({ viewport: { width, height }, reducedMotion: rm });
      await open(page, t, article);
      const what = `${width}×${height} ${rm}`;
      const start = async () => {
        await page.evaluate(() => { document.activeElement && document.activeElement.blur();
          scrollTo({ top: Math.min(1000, document.documentElement.scrollHeight - innerHeight), behavior: 'instant' }); });
        await settle(page);
        return page.evaluate(() => scrollY);
      };
      const still = async (y0, step) => {
        await settle(page);
        const y = await page.evaluate(() => scrollY);
        t.check(Math.abs(y - y0) <= 2, `${what}: ${step} moved the page from ${y0} to ${y}`);
      };
      let y0 = await start();
      t.check(y0 >= 900, `${what}: scrolled to ${y0}`);
      await page.keyboard.press('Tab');
      t.check(await focusIn(page, '.clogem-navbar'), `${what}: the first Tab lands in the navbar`);
      await still(y0, 'the first Tab');
      if (width <= 800) {
        y0 = await start();
        await clickAt(page, '.clogem-navbar__toggle > summary');
        t.check(await isOpen(page), `${what}: a click opens the drawer`);
        await still(y0, 'opening the drawer');
        await page.keyboard.press('Escape');
        t.check(!await isOpen(page), `${what}: Escape closes the drawer`);
        await still(y0, 'Escape on the drawer');
      }
      y0 = await start();
      await clickAt(page, '.clogem-mode__button');
      t.check(await page.evaluate(() => !document.getElementById('clogem-mode-menu').hidden), `${what}: a click opens the mode menu`);
      await still(y0, 'opening the mode menu');
      await page.keyboard.press('Escape');
      t.check(await page.evaluate(() => document.getElementById('clogem-mode-menu').hidden), `${what}: Escape closes the mode menu`);
      await still(y0, 'Escape on the mode menu');
      if (width > 800) {
        y0 = await start();
        await clickAt(page, '.clogem-langs__toggle > summary');
        t.check(await page.evaluate(() => document.querySelector('.clogem-langs__toggle').open), `${what}: a click opens the language dropdown`);
        await still(y0, 'opening the language dropdown');
        await page.keyboard.press('Escape');
        t.check(await page.evaluate(() => !document.querySelector('.clogem-langs__toggle').open), `${what}: Escape closes the language dropdown`);
        await still(y0, 'Escape on the language dropdown');
        // Escape from a link in the open list: the summary takes focus
        y0 = await start();
        await clickAt(page, '.clogem-langs__toggle > summary');
        await page.keyboard.press('Tab');
        t.check(await focusIn(page, '.clogem-langs > ul'), `${what}: Tab enters the open language list`);
        await still(y0, 'Tab into the language list');
        await page.keyboard.press('Escape');
        t.check(await page.evaluate(() => !document.querySelector('.clogem-langs__toggle').open
                                          && document.activeElement === document.querySelector('.clogem-langs__toggle > summary')),
                `${what}: Escape from a list link closes the language dropdown and focuses its summary`);
        await still(y0, 'Escape from a language list link');
      }
      await page.close();
    }
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
    // the rule hiding the trigger's shortcut is scoped to the trigger:
    // Pagefind's searchbox draws its hint as a .pf-trigger-shortcut too
    // (1.5.2's modal uses <pagefind-input>, which has none, so a probe in
    // the search box, beside the modal, stands in for it)
    const m = await page.evaluate(() => {
      const d = document.querySelector('pagefind-modal dialog');
      const probe = document.createElement('div');
      probe.className = 'pf-searchbox-input-wrapper';
      probe.innerHTML = '<span class="pf-trigger-shortcut"><span class="pf-trigger-key">K</span></span>';
      document.querySelector('.clogem-search').appendChild(probe);
      const shown = getComputedStyle(probe.firstChild).display;
      probe.remove();
      return { open: !!d && d.open, shortcut: shown };
    });
    t.check(m.open, `900px /${rel}: the icon-only trigger opens the modal`);
    t.check(m.shortcut !== 'none', `900px /${rel}: a .pf-trigger-shortcut outside the trigger is still displayed (${m.shortcut})`);
    await page.close();
  }

}

// P4-B2b.1 item 2: widening across 50rem while scrolled keeps the reading
// position. The aside was still in the drawer when the media query flipped,
// laid out in the navbar (display: contents) until nav.js moved it back,
// and scroll anchoring shifted the page by ~400 px
async function item2Resize(t) {
  const at = async (from, to, abort) => {
    const page = await t.newPage({ viewport: { width: from, height: 740 }, reducedMotion: 'reduce' });
    if (abort) await page.route('**/clogem/js/nav.js*', (route) => route.abort());
    await open(page, t, article);
    await page.evaluate(() => { const h = document.getElementById('in-a-list-item');
      scrollBy({ top: h.getBoundingClientRect().top - 200, behavior: 'instant' }); });
    await settle(page);
    const before = await page.evaluate(() => document.getElementById('in-a-list-item').getBoundingClientRect().top);
    await page.setViewportSize({ width: to, height: 740 });
    await page.waitForTimeout(300);
    await settle(page);
    const r = await page.evaluate(() => ({ top: document.getElementById('in-a-list-item').getBoundingClientRect().top,
      inShell: !!document.querySelector('aside.clogem-sidebar').closest('.clogem-shell') }));
    await page.close();
    return { before, ...r };
  };
  // Each resize is measured against the same page with nav.js aborted: the
  // browser's own scroll anchoring reflows text above the heading too, by
  // a different amount under different fonts (1024 → 768 moves it 31 px on
  // CI's runners, about 4 px here; 360 → 1280, three columns from one, ~116 px
  // everywhere), and what must not move it is the aside nav.js moves
  for (const [from, to] of [[700, 900], [768, 1024], [360, 1280], [900, 700], [1024, 768]]) {
    const base = await at(from, to, true);
    const r = await at(from, to, false);
    t.check(Math.abs(r.top - base.top) <= 24,
            `resize ${from} → ${to}: #in-a-list-item went from ${r.before.toFixed(1)} to ${r.top.toFixed(1)}; with nav.js aborted, to ${base.top.toFixed(1)}`);
    if (to > 800) t.check(r.inShell, `resize ${from} → ${to}: the sidebar is back in the shell`);
  }
}

// a nav too long for one row (10 items added, 13 in all): above 50rem no
// two controls overlap, nothing overflows, every control is inside the
// navbar, and a jump lands below it however tall it wraps (P4-B2b.1 item 3)
async function longNav(page, t, rel, label = 'Item') {
  await page.route(url(t, rel), async (route) => {
    const res = await route.fetch();
    const extra = Array.from({ length: 10 }, (_, i) => `<li class="clogem-navbar__item"><a href="/">${label} ${i + 1}</a></li>`).join('');
    const body = (await res.text()).replace('<nav class="clogem-navbar__nav"><ul>', '<nav class="clogem-navbar__nav"><ul>' + extra);
    await route.fulfill({ response: res, body });
  });
}

function overlaps(boxes) {
  const out = [];
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i], b = boxes[j];
      const x = Math.min(a.right, b.right) - Math.max(a.left, b.left), y = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      if (x > 0.5 && y > 0.5) out.push(`${a.n} × ${b.n} by ${x.toFixed(1)}`);
    }
  }
  return out;
}

async function item2LongNav(t) {
  for (const js of [true, false]) {
    for (const rel of [article, 'ms/', 'zh-Hans/pages/c0nta1/']) {
      const page = await t.newPage({ viewport: { width: 1600, height: 740 }, reducedMotion: 'reduce', javaScriptEnabled: js });
      await longNav(page, t, rel);
      await page.goto(url(t, rel), { waitUntil: 'load' });
      if (js) await page.waitForSelector('button.pf-trigger-btn', { state: 'attached', timeout: 5000 }).catch(() => {});
      await page.evaluate(() => document.fonts.ready);
      const what = `a long nav, ${js ? 'JS' : 'no JS'}, /${rel}`;
      let bad = 0, n = 0;
      for (let width = 801; width <= 1600; width += 10) {
        await page.setViewportSize({ width, height: 740 });
        const s = await page.evaluate(navbarState);
        n = Math.max(n, s.boxes.filter((b) => b.n.startsWith('item')).length);
        const ov = overlaps(s.boxes);
        const out = s.boxes.filter((b) => !(b.top >= s.top - 0.5 && b.bottom <= s.bottom + 0.5 && b.left >= s.left - 0.5 && b.right <= s.right + 0.5)).map((b) => b.n);
        const ok = ov.length === 0 && out.length === 0 && s.overflow <= 0;
        if (!ok && bad++ < 4) t.check(false, `${what} at ${width}: overlaps ${ov.join(', ') || 'none'}; outside the navbar ${out.join(', ') || 'none'}; overflow ${s.overflow}`);
      }
      t.check(n >= 13 && bad === 0, `${what}: ${n} items; ${bad} of 80 widths failed`);
      await page.close();
    }
  }
  // the old check at 1024, and a fresh load of #nesting at four widths
  for (const js of [true, false]) {
    for (const width of [801, 850, 1024, 1280]) {
      const page = await t.newPage({ viewport: { width, height: 740 }, reducedMotion: 'reduce', javaScriptEnabled: js });
      await longNav(page, t, article);
      const what = `a long nav, ${js ? 'JS' : 'no JS'}, ${width}px`;
      if (js) await open(page, t, article, 'nesting');
      else { await page.goto(url(t, article, 'nesting'), { waitUntil: 'load' }); await page.waitForTimeout(200); }
      const s = await page.evaluate(navbarState);
      t.check(s.boxes.filter((b) => b.n.startsWith('item')).length >= 13, `${what}: ${s.boxes.length} controls rendered`);
      checkRow(t, s, what, { oneRow: false, height: width >= 1024 });
      t.check(s.overflow <= 0, `${what}: the page overflows by ${s.overflow}`);
      const children = await page.evaluate(() => {
        const nb = document.querySelector('.clogem-navbar'), r = nb.getBoundingClientRect();
        return [...nb.querySelectorAll('.clogem-navbar__nav li, .clogem-navbar > *, #clogem-drawer > *')]
          .filter((e) => e.checkVisibility())
          .map((e) => { const b = e.getBoundingClientRect(); return [e.tagName + '.' + e.className, b.top >= r.top - 0.5 && b.bottom <= r.bottom + 0.5 && b.left >= r.left - 0.5 && b.right <= r.right + 0.5]; })
          .filter(([, ok]) => !ok).map(([n]) => n);
      });
      t.check(children.length === 0, `${what}: every navbar child is inside the navbar (${children.join(', ')})`);
      checkAnchor(t, await page.evaluate(anchorState, 'nesting'), `${what} (navbar ${s.height.toFixed(1)} px), a fresh load of #nesting`);
      await page.close();
    }
  }
  // loaded on one row at 1280, then narrowed until it wraps: the offset
  // follows (nav.js's ResizeObserver, P4-B2b.2 item 2)
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: 'reduce' });
    await longNav(page, t, article);
    await open(page, t, article);
    for (const width of [801, 850]) {
      await page.setViewportSize({ width, height: 740 });
      await settle(page);   // the observer's callback, as a reader's next jump would see
      await page.evaluate(() => { location.hash = ''; });
      await page.evaluate(() => { location.hash = 'nesting'; });
      await settle(page);
      const h = await page.evaluate(() => document.querySelector('.clogem-navbar').getBoundingClientRect().height);
      t.check(h > 70, `a long nav loaded at 1280, narrowed to ${width}: the navbar wraps (${h.toFixed(1)} px)`);
      checkAnchor(t, await page.evaluate(anchorState, 'nesting'), `a long nav loaded at 1280, narrowed to ${width} (navbar ${h.toFixed(1)} px), #nesting followed`);
    }
    await page.close();
  }
  // longer labels wrap the navbar at 1280 too: the stuck TOC stays below it
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: 'reduce' });
    await longNav(page, t, article, 'Documentation');
    await open(page, t, article);
    await scrollTop(page, 900);
    await settle(page);
    const r = await page.evaluate(() => ({ nav: document.querySelector('.clogem-navbar').getBoundingClientRect().bottom,
                                           toc: document.querySelector('.clogem-toc').getBoundingClientRect().top }));
    t.check(r.nav > 70 && r.toc >= r.nav, `a long nav of longer labels, 1280px: the navbar wraps (bottom ${r.nav.toFixed(1)}) and the stuck TOC's top ${r.toc.toFixed(1)} is below it`);
    await page.close();
  }
  // pages that finish loading early (no toc.js, code.js…): the browser's
  // jump to the fragment comes before any ResizeObserver callback, so nav.js
  // measures the navbar when it runs (P4-B2b.1)
  for (const rel of ['', 'page/2/']) {
    for (const width of [801, 850]) {
      const page = await t.newPage({ viewport: { width, height: 740 }, reducedMotion: 'reduce' });
      await longNav(page, t, rel);
      await open(page, t, rel, 'clogem-press-demo-site');
      const h = await page.evaluate(() => document.querySelector('.clogem-navbar').getBoundingClientRect().height);
      checkAnchor(t, await page.evaluate(anchorState, 'clogem-press-demo-site'), `a long nav, ${width}px /${rel} (navbar ${h.toFixed(1)} px), a fresh load of #clogem-press-demo-site`);
      await page.close();
    }
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
      const body = (await res.text()).replace(/<aside class="clogem-sidebar"[^>]*>[\s\S]*?<\/aside>/, '')
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

// P4-B2b.1 item 4: `:theme :right-menu-bar false` drops the TOC; from 80rem
// the article takes its room (584 px in the 62rem shell). A temp copy of the
// demo, built with bb (skipped, saying so, without it) and served in place
// of the demo through page.route
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
               '.json': 'application/json', '.woff2': 'font/woff2', '.png': 'image/png' };

async function item3RightMenuBar(t) {
  const site = await mkdtemp(path.join(os.tmpdir(), 'clogem-rmb-'));
  try {
    await cp(path.join(repo, 'examples', 'demo-site'), site, { recursive: true, filter: (src) => !src.split(path.sep).includes('dist') });
    const edn = await readFile(path.join(site, 'site.edn'), 'utf8');
    t.check(edn.includes(':theme {'), ':right-menu-bar false: the demo has a :theme map');
    await writeFile(path.join(site, 'site.edn'), edn.replace(':theme {', ':theme {:right-menu-bar false '));
    const r = await new Promise((resolve) => {
      let out = '';
      const child = spawn('bb', ['--config', path.join(repo, 'bb.edn'), 'build', '--no-search', '--no-highlight', '--no-write'], { cwd: site });
      child.stdout.on('data', (d) => { out += d; });
      child.stderr.on('data', (d) => { out += d; });
      child.on('error', (e) => resolve({ code: -1, out: e.message, missing: e.code === 'ENOENT' }));
      child.on('close', (code) => resolve({ code, out }));
    });
    if (r.missing) { console.log('  skip: :right-menu-bar false — bb is not on PATH'); return; }
    t.check(r.code === 0, `:right-menu-bar false: the build exits ${r.code}: ${r.out.slice(-400)}`);
    const dist = path.join(site, 'dist');
    for (const width of [1280, 1440, 1024, 900]) {
      const page = await t.newPage({ viewport: { width, height: 740 } });
      await page.route(new URL('/**', t.baseURL).href, async (route) => {
        let rel = decodeURIComponent(new URL(route.request().url()).pathname);
        if (rel.endsWith('/')) rel += 'index.html';
        try {
          await route.fulfill({ status: 200, body: await readFile(path.join(dist, rel)), contentType: mime[path.extname(rel)] || 'application/octet-stream' });
        } catch { await route.fulfill({ status: 404, body: 'not found' }); }
      });
      // the default demo, for the widths that must not change
      const plain = await t.newPage({ viewport: { width, height: 740 } });
      for (const rel of [article, 'pages/cjk001/']) {
        await page.goto(url(t, rel), { waitUntil: 'load' });
        await plain.goto(url(t, rel), { waitUntil: 'load' });
        const what = `:right-menu-bar false, ${width}px /${rel}`;
        const get = (p) => p.evaluate(([box]) => {
          const f = new Function('return ' + box)();
          return { sb: f('aside.clogem-sidebar'), main: f('main'), art: f('article.clogem-article'), toc: !!document.querySelector('.clogem-toc'),
                   cls: document.querySelector('.clogem-shell').className, overflow: document.documentElement.scrollWidth - innerWidth };
        }, [box.toString()]);
        const s = await get(page), d = await get(plain);
        t.check(!s.toc && s.overflow <= 0, `${what}: no TOC (${s.toc}), overflow ${s.overflow}`);
        t.check(s.sb && s.sb.right <= s.main.left, `${what}: the sidebar (${s.sb && s.sb.right}) is left of main (${s.main.left})`);
        if (rel === article) t.check(s.cls.includes('clogem-shell--wide'), `${what}: the shell is ${s.cls}`);
        if (width >= 1280 && rel === article) t.check(s.art.width >= 600, `${what}: article.clogem-article is ${s.art.width} px, want ≥ 600`);
        if (width < 1280) t.check(Math.abs(s.art.width - d.art.width) <= 0.5 && Math.abs(s.main.left - d.main.left) <= 0.5,
                                  `${what}: the article is ${s.art.width} px at ${s.main.left}, as the default's ${d.art.width} at ${d.main.left}`);
        // a page without a TOC for another reason keeps the default's width
        if (rel === 'pages/cjk001/') t.check(!s.cls.includes('clogem-shell--wide') && Math.abs(s.main.width - d.main.width) <= 0.5,
                                            `${what}: no headings, so the default's shell (${s.cls}; main ${s.main.width}, default ${d.main.width})`);
      }
      await plain.close();
      await page.close();
    }
  } finally {
    await rm(site, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------------------
// 4–6. At 50rem and below: one row, the drawer, js/nav.js and the fallbacks

const mobilePages = ['', 'zh-Hans/', 'ms/', 'ta/', article, 'zh-Hans/pages/c0nta1/', 'ta/pages/e19a7c/', 'ms/categories/'];

// runs in the page: opens the drawer and every <details> in it
function openAll() {
  const tg = document.querySelector('.clogem-navbar__toggle');
  if (tg) tg.open = true;
  document.querySelectorAll('#clogem-drawer details, .clogem-navbar details').forEach((d) => { d.open = true; });
}

// runs in the page: the overflow conditions with the drawer open
function drawerFits() {
  const d = document.getElementById('clogem-drawer');
  const r = d.getBoundingClientRect();
  return { doc: document.documentElement.scrollWidth - innerWidth, sw: d.scrollWidth, cw: d.clientWidth,
           bottom: r.bottom, innerHeight, right: r.right, innerWidth };
}

const isOpen = (page) => page.evaluate(() => document.querySelector('.clogem-navbar__toggle').open);
const focusIn = (page, sel) => page.evaluate((sel) => !!document.activeElement && !!document.activeElement.closest(sel), sel);
const asides = (page) => page.evaluate(() => ({ n: document.querySelectorAll('aside.clogem-sidebar').length,
                                                ids: document.querySelectorAll('#clogem-drawer').length }));

async function item4(t) {
  await heights(t, [320, 360, 768]);
  await anchorsAt(t, [320, 360, 768]);
  await nonHeading(t, [360]);
  for (const width of [320, 360, 768]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    for (const rel of mobilePages) {
      await open(page, t, rel);
      const what = `${width}px /${rel}`;
      const s = await page.evaluate(navbarState);
      checkRow(t, s, what);
      t.check(s.boxes.some((b) => b.n === 'menu'), `${what}: the menu summary is rendered`);
      const can = await page.evaluate(() => document.documentElement.scrollHeight - innerHeight >= 600);
      if (can) {
        t.check(await scrollTop(page, 600) === 600, `${what}: scrollY reached 600`);
        const top = await page.evaluate(() => document.querySelector('.clogem-navbar').getBoundingClientRect().top);
        t.check(top === 0, `${what}: scrolled 600 px, the navbar's top is ${top}`);
        await scrollTop(page, 0);
      }
      // closed: only the toggle, the brand, search and mode are controls
      const c = await page.evaluate(() => {
        const nb = document.querySelector('.clogem-navbar');
        const shown = [...nb.querySelectorAll('a[href], button, summary')].filter((e) => e.checkVisibility());
        const name = (e) => e.matches('.clogem-navbar__toggle > summary') ? 'menu' : e.matches('.clogem-navbar__brand') ? 'brand'
          : e.matches('button.pf-trigger-btn') ? 'search' : e.matches('.clogem-mode__button') ? 'mode' : e.outerHTML.slice(0, 60);
        const br = nb.querySelector('.clogem-navbar__brand');
        const bb = br.getBoundingClientRect();
        const overlaps = shown.filter((e) => e !== br).filter((e) => { const r = e.getBoundingClientRect();
          return r.left < bb.right - 0.5 && r.right > bb.left + 0.5 && r.top < bb.bottom && r.bottom > bb.top; }).map(name);
        return { shown: shown.map(name), overlaps, ellipsis: getComputedStyle(br).textOverflow };
      });
      t.check(c.shown.join(' ') === 'menu brand search mode', `${what}: closed, the controls are ${c.shown.join(' ')}`);
      t.check(c.overlaps.length === 0 && c.ellipsis === 'ellipsis', `${what}: the brand overlaps ${c.overlaps.join(', ') || 'nothing'}, text-overflow ${c.ellipsis}`);
      let into = false;
      await page.evaluate(() => document.activeElement && document.activeElement.blur());
      for (let i = 0; i < 15; i++) {
        await page.keyboard.press('Tab');
        if (await focusIn(page, '#clogem-drawer')) into = true;
      }
      t.check(!into, `${what}: 15 Tabs from the top never enter the closed drawer`);
    }
    await page.close();
  }

  // open, at 320, with everything in it open: nothing overflows, the
  // drawer fits the viewport and its last link can be focused in it
  {
    const page = await t.newPage({ viewport: { width: 320, height: 740 } });
    for (const rel of mobilePages) {
      await open(page, t, rel);
      await page.evaluate(openAll);
      const f = await page.evaluate(drawerFits);
      const what = `320px /${rel}, the drawer open`;
      t.check(f.doc <= 0, `${what}: the page overflows by ${f.doc}`);
      t.check(f.sw <= f.cw, `${what}: the drawer's scrollWidth ${f.sw} > clientWidth ${f.cw}`);
      t.check(f.bottom <= f.innerHeight, `${what}: the drawer's bottom ${f.bottom} is below the viewport (${f.innerHeight})`);
      const last = await page.evaluate(() => {
        const d = document.getElementById('clogem-drawer');
        const all = [...d.querySelectorAll('a[href]')].filter((e) => e.checkVisibility());
        const a = all[all.length - 1];
        a.focus();
        const r = a.getBoundingClientRect(), dr = d.getBoundingClientRect();
        return { focused: document.activeElement === a, inside: r.top >= dr.top - 0.5 && r.bottom <= dr.bottom + 0.5 };
      });
      t.check(last.focused && last.inside, `${what}: the last link, focused, lies inside the drawer (${JSON.stringify(last)})`);
    }
    // the mode menu fits at 320
    for (const rel of homes) {
      await open(page, t, rel);
      await page.locator('.clogem-mode__button').click();
      const m = await page.evaluate(() => { const r = document.getElementById('clogem-mode-menu').getBoundingClientRect(); return [r.left, r.right]; });
      t.check(m[0] >= 0 && m[1] <= 320, `320px /${rel}: the open mode menu spans ${m[0]}–${m[1]}`);
    }
    await page.close();
  }

  // reduced motion: nothing animates when the drawer opens
  {
    const page = await t.newPage({ viewport: { width: 360, height: 740 }, reducedMotion: 'reduce' });
    await open(page, t, article);
    await page.locator('.clogem-navbar__toggle > summary').click();
    const r = await page.evaluate(() => {
      const d = document.getElementById('clogem-drawer');
      const els = [d, ...d.querySelectorAll('*'), document.querySelector('.clogem-navbar__toggle')];
      return { anims: d.getAnimations({ subtree: true }).length,
               moving: els.filter((e) => getComputedStyle(e).transitionDuration.split(',').some((x) => parseFloat(x) !== 0)).length };
    });
    t.check(r.anims === 0 && r.moving === 0, `reduce: the drawer runs ${r.anims} animations, ${r.moving} elements with a transition`);
    await page.close();
  }

  // every Tab stop in the open drawer draws the focus ring
  for (const mode of ['light', 'dark']) {
    const page = await t.newPage({ viewport: { width: 360, height: 740 } });
    await page.addInitScript((m) => { try { localStorage.setItem('clogem-mode', m); } catch (e) {} }, mode);
    await open(page, t, article);
    const ring = await page.evaluate(() => {
      const p = document.createElement('div'); document.body.appendChild(p);
      p.style.color = 'var(--focusRing)'; const c = getComputedStyle(p).color; p.remove(); return c;
    });
    await page.focus('.clogem-navbar__toggle > summary');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.activeElement && document.activeElement.closest('#clogem-drawer'), null, { timeout: 3000 }).catch(() => {});
    let stops = 0, bad = 0;
    while (stops < 80 && await focusIn(page, '#clogem-drawer')) {
      stops++;
      const f = await page.evaluate(() => { const c = getComputedStyle(document.activeElement);
        return { style: c.outlineStyle, width: parseFloat(c.outlineWidth), color: c.outlineColor, what: document.activeElement.outerHTML.slice(0, 50) }; });
      if (!(f.style === 'solid' && f.width >= 2 && f.color === ring) && bad++ < 3) t.check(false, `360px ${mode}: drawer stop ${stops} (${f.what}): ${f.style} ${f.width}px ${f.color}, want solid ≥2px ${ring}`);
      await page.keyboard.press('Tab');
    }
    t.check(stops > 10 && bad === 0, `360px ${mode}: ${stops} drawer stops, ${bad} without the ring`);
    await page.close();
  }
}

async function item5(t) {
  // no JS: the old wrapping navbar, static, every link shown
  for (const width of [320, 360, 768]) {
    const page = await t.newPage({ viewport: { width, height: 740 }, javaScriptEnabled: false });
    for (const rel of mobilePages) {
      await page.goto(url(t, rel), { waitUntil: 'load' });
      const what = `no JS, ${width}px /${rel}`;
      const r = await page.evaluate(() => {
        const nb = document.querySelector('.clogem-navbar');
        const links = [...nb.querySelectorAll('.clogem-navbar__nav > ul > li > a, .clogem-navbar__nav > ul > li > details > summary, .clogem-langs a')];
        const sb = document.querySelector('aside.clogem-sidebar'), main = document.querySelector('main');
        return { pos: getComputedStyle(nb).position, hidden: links.filter((e) => !e.checkVisibility()).map((e) => e.textContent.trim()),
                 n: links.length, toggle: !!document.querySelector('.clogem-navbar__toggle') && document.querySelector('.clogem-navbar__toggle').getClientRects().length > 0,
                 sidebar: sb ? [sb.getBoundingClientRect().top, main.getBoundingClientRect().bottom] : null };
      });
      t.check(r.pos === 'static', `${what}: the navbar is ${r.pos}`);
      t.check(r.n > 0 && r.hidden.length === 0, `${what}: links hidden without a click: ${r.hidden.join(', ')}`);
      t.check(!r.toggle, `${what}: the menu toggle is rendered`);
      if (r.sidebar) t.check(r.sidebar[0] >= r.sidebar[1], `${what}: the sidebar's top ${r.sidebar[0]} is above main's bottom ${r.sidebar[1]}`);
      const ov = await page.evaluate(() => { document.querySelectorAll('.clogem-navbar details').forEach((d) => { d.open = true; });
        return document.documentElement.scrollWidth - innerWidth; });
      t.check(ov <= 0, `${what}: with every navbar <details> open the page overflows by ${ov}`);
    }
    await page.close();
  }
  {
    const page = await t.newPage({ viewport: { width: 360, height: 740 }, javaScriptEnabled: false, reducedMotion: 'reduce' });
    await page.goto(url(t, article, 'nesting'), { waitUntil: 'load' });
    await page.waitForTimeout(200);
    const top = await page.evaluate(() => document.getElementById('nesting').getBoundingClientRect().top);
    t.check(top >= 0 && top <= 32, `no JS, 360px: #nesting lands at ${top}, want 0–32`);
    await page.close();
  }

  // a failed nav.js: the drawer still opens natively
  for (const how of ['click', 'Enter']) {
    const page = await t.newPage({ viewport: { width: 360, height: 740 } });
    await page.route('**/clogem/js/nav.js*', (route) => route.abort());
    for (const rel of [article, 'ms/']) {
      await open(page, t, rel);
      const what = `360px /${rel}, nav.js blocked, ${how}`;
      if (how === 'click') await page.locator('.clogem-navbar__toggle > summary').click();
      else { await page.focus('.clogem-navbar__toggle > summary'); await page.keyboard.press('Enter'); }
      t.check(await isOpen(page), `${what}: the summary opens the drawer`);
      const r = await page.evaluate(() => {
        const d = document.getElementById('clogem-drawer');
        const links = [...d.querySelectorAll('.clogem-navbar__nav > ul > li > a, .clogem-navbar__nav > ul > li > details > summary, .clogem-langs a')];
        const bad = links.filter((e) => { e.focus(); return !e.checkVisibility() || document.activeElement !== e; }).map((e) => e.textContent.trim());
        const sb = document.querySelector('aside.clogem-sidebar'), main = document.querySelector('main');
        return { n: links.length, bad, inDrawer: !!d.querySelector('aside'),
                 sidebar: sb ? sb.getBoundingClientRect().top + scrollY >= main.getBoundingClientRect().bottom + scrollY : true,
                 ov: document.documentElement.scrollWidth - innerWidth };
      });
      t.check(r.n > 0 && r.bad.length === 0, `${what}: drawer links not visible or not focusable: ${r.bad.join(', ')}`);
      t.check(!r.inDrawer && r.sidebar, `${what}: the sidebar stays below main`);
      t.check(r.ov <= 0, `${what}: the page overflows by ${r.ov}`);
    }
    await page.close();
  }
}

async function item6(t) {
  for (const width of [320, 360, 768]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    await open(page, t, article);
    const what = `${width}px`;
    t.check(await page.evaluate(() => document.querySelector('aside.clogem-sidebar').parentElement.id === 'clogem-drawer'),
            `${what}: nav.js moved the sidebar into the drawer at start-up`);
    // keyboard
    await page.evaluate(() => document.activeElement && document.activeElement.blur());
    let on = false;
    for (let i = 0; i < 5 && !on; i++) {
      await page.keyboard.press('Tab');
      on = await page.evaluate(() => document.activeElement.matches('.clogem-navbar__toggle > summary'));
    }
    t.check(on, `${what}: Tab reaches the menu summary first`);
    await page.keyboard.press('Enter');
    t.check(await isOpen(page), `${what}: Enter opens the drawer`);
    await page.waitForFunction(() => document.activeElement && document.activeElement.closest('#clogem-drawer'), null, { timeout: 3000 }).catch(() => {});
    t.check(await focusIn(page, '#clogem-drawer'), `${what}: focus moved into the drawer`);
    const reached = new Set();
    for (let i = 0; i < 150 && await focusIn(page, '#clogem-drawer'); i++) {
      const k = await page.evaluate(() => {
        const e = document.activeElement;
        e.dataset.b2bSeen = '1';
        return e.matches('.clogem-navbar__item > details:not([open]) > summary');
      });
      if (k) await page.keyboard.press('Enter');
      await page.keyboard.press('Tab');
    }
    const missed = await page.evaluate(() => [...document.querySelectorAll('#clogem-drawer a[href], #clogem-drawer summary')]
      .filter((e) => e.checkVisibility() && !e.dataset.b2bSeen).map((e) => e.textContent.trim()));
    const kinds = await page.evaluate(() => ['.clogem-navbar__nav > ul > li > a', '.clogem-navbar__item > details > summary',
      '.clogem-navbar__menu a', '.clogem-langs a', '.clogem-sidebar a'].map((s) => document.querySelectorAll(`#clogem-drawer ${s}[data-b2b-seen]`).length));
    t.check(missed.length === 0 && kinds.every((n) => n > 0), `${what}: Tab reaches every drawer link (missed: ${missed.join(', ')}; per kind ${kinds})`);
    // Escape closes it and focuses the summary
    await page.evaluate(() => document.querySelector('#clogem-drawer a[href]').focus());
    await page.keyboard.press('Escape');
    t.check(!await isOpen(page) && await page.evaluate(() => document.activeElement.matches('.clogem-navbar__toggle > summary')),
            `${what}: Escape closes the drawer and focuses the summary`);
    if (width === 360) {
      await page.locator('.clogem-navbar__toggle > summary').click();
      await page.evaluate(() => { document.querySelector('#clogem-drawer .clogem-navbar__item > details').open = true; });
      await page.evaluate(() => document.querySelector('#clogem-drawer .clogem-navbar__menu a').focus());
      await page.keyboard.press('Escape');
      t.check(!await isOpen(page) && await page.evaluate(() => document.activeElement.matches('.clogem-navbar__toggle > summary')),
              '360px: with the Guide dropdown open, one Escape closes the drawer and focuses the summary');
    }
    // pointer
    if (width !== 320) {
      await page.locator('.clogem-navbar__toggle > summary').click();
      t.check(await isOpen(page), `${what}: a click opens the drawer`);
      const vp = page.viewportSize();
      await page.mouse.click(vp.width - 4, vp.height / 2);
      t.check(!await isOpen(page), `${what}: a click outside the drawer closes it`);
      await page.locator('.clogem-navbar__toggle > summary').click();
      await page.locator('.clogem-navbar__toggle > summary').click();
      t.check(!await isOpen(page), `${what}: two clicks on the summary leave it closed`);
    }
    // following a link closes it
    await page.locator('.clogem-navbar__toggle > summary').click();
    await page.evaluate(() => { document.querySelector('#clogem-drawer .clogem-navbar__nav a[href]').setAttribute('href', '#nesting'); });
    await page.locator('#clogem-drawer .clogem-navbar__nav a[href="#nesting"]').click();
    t.check(!await isOpen(page), `${what}: following a link in the drawer closes it`);
    await settle(page);
    checkAnchor(t, await page.evaluate(anchorState, 'nesting'), `${what}: #nesting from the drawer`);
    const d = await asides(page);
    t.check(d.n === 1 && d.ids === 1, `${what}: one aside.clogem-sidebar (${d.n}) and one #clogem-drawer (${d.ids})`);
    await page.close();
  }

  // leaving the drawer layout closes the drawer and puts the sidebar back
  {
    const page = await t.newPage({ viewport: { width: 360, height: 740 } });
    await open(page, t, article);
    await page.locator('.clogem-navbar__toggle > summary').click();
    t.check(await isOpen(page), 'resize: the drawer is open at 360');
    let d = await asides(page);
    t.check(d.n === 1 && d.ids === 1, `resize: one aside (${d.n}) and one drawer (${d.ids}) while open`);
    await page.setViewportSize({ width: 1280, height: 740 });
    await page.waitForTimeout(200);
    const r = await page.evaluate(() => {
      const a = document.querySelector('aside.clogem-sidebar'), m = document.querySelector('main');
      return { rendered: document.querySelector('.clogem-navbar__toggle > summary').getClientRects().length > 0,
               open: document.querySelector('.clogem-navbar__toggle').open,
               parent: a.parentElement.classList.contains('clogem-shell'),
               before: !!(a.compareDocumentPosition(m) & Node.DOCUMENT_POSITION_FOLLOWING),
               left: a.getBoundingClientRect().right <= m.getBoundingClientRect().left,
               top: Math.abs(a.getBoundingClientRect().top - m.getBoundingClientRect().top) };
    });
    t.check(!r.rendered && !r.open, `resize to 1280: the summary is not rendered (${r.rendered}) and the drawer is closed (${!r.open})`);
    t.check(r.parent && r.before && r.left && r.top <= 1, `resize to 1280: the sidebar is back in the shell, before main, left of it, same top (${JSON.stringify(r)})`);
    d = await asides(page);
    t.check(d.n === 1 && d.ids === 1, `resize: one aside (${d.n}) after resizing`);
    await page.setViewportSize({ width: 360, height: 740 });
    await page.waitForTimeout(200);
    t.check(!await isOpen(page), 'back at 360: the drawer starts closed');
    await page.evaluate(() => { scrollTo({ top: 0, behavior: 'instant' }); document.activeElement && document.activeElement.blur(); });
    let into = false;
    for (let i = 0; i < 15; i++) { await page.keyboard.press('Tab'); if (await focusIn(page, '#clogem-drawer')) into = true; }
    t.check(!into, 'back at 360: Tab does not enter the closed drawer');
    d = await asides(page);
    t.check(d.n === 1 && d.ids === 1 && await page.evaluate(() => document.querySelector('aside.clogem-sidebar').parentElement.id === 'clogem-drawer'),
            `back at 360: one aside, in the drawer again (${d.n})`);
    await page.close();
  }

  // no `@media (scripting)`: nav.js loads but the CSS draws no drawer, so
  // the sidebar is never moved
  {
    const page = await t.newPage({ viewport: { width: 360, height: 740 } });
    await page.route('**/clogem/css/theme.css*', async (route) => {
      const res = await route.fetch();
      await route.fulfill({ response: res, body: (await res.text()).replaceAll('(scripting: enabled)', '(scripting: initial-only)') });
    });
    await open(page, t, article);
    const r = await page.evaluate(() => ({
      pos: getComputedStyle(document.querySelector('.clogem-navbar')).position,
      toggle: document.querySelector('.clogem-navbar__toggle > summary').getClientRects().length,
      inShell: document.querySelector('aside.clogem-sidebar').parentElement.classList.contains('clogem-shell'),
      nav: typeof window.clogem === 'object' }));
    t.check(r.pos === 'static' && r.toggle === 0, `no (scripting: enabled): the no-JS navbar (${r.pos}, toggle rendered ${r.toggle})`);
    t.check(r.inShell && r.nav, 'no (scripting: enabled): the sidebar stays in the shell');
    await page.close();
  }

  // a one-language site with no :nav: the toggle is revealed, on a sidebar
  // page only (rewritten in-page)
  {
    const page = await t.newPage({ viewport: { width: 360, height: 740 } });
    await page.route(url(t, article), async (route) => {
      const res = await route.fetch();
      const body = (await res.text()).replace('<details class="clogem-navbar__toggle">', '<details class="clogem-navbar__toggle" hidden="hidden">')
        .replace(/<nav class="clogem-navbar__nav"><ul>[\s\S]*?<\/ul><\/nav>/, '<nav class="clogem-navbar__nav"><ul></ul></nav>')
        .replace(/<nav aria-label="[^"]*" class="clogem-langs">[\s\S]*?<\/ul><\/nav>/, '');
      await route.fulfill({ response: res, body });
    });
    await open(page, t, article);
    const r = await page.evaluate(() => ({ hidden: document.querySelector('.clogem-navbar__toggle').hidden,
      inDrawer: !!document.querySelector('#clogem-drawer aside.clogem-sidebar') }));
    t.check(!r.hidden && r.inDrawer, `:nav [], one language: nav.js reveals the toggle and moves the sidebar in (${JSON.stringify(r)})`);
    await page.locator('.clogem-navbar__toggle > summary').click();
    await page.waitForFunction(() => document.activeElement && document.activeElement.closest('#clogem-drawer'), null, { timeout: 3000 }).catch(() => {});
    t.check(await focusIn(page, '#clogem-drawer .clogem-sidebar'), ':nav [], one language: opening focuses the sidebar\'s first control');
    await page.close();
  }

  // outside the drawer layout: Escape and a click outside close a dropdown
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 } });
    await open(page, t, article);
    // Browse: a summary that holds no link (Guide's holds one)
    for (const sel of ['.clogem-langs__toggle', '.clogem-navbar__item > details:has(> summary > span)']) {
      await page.locator(`${sel} > summary`).first().click();
      t.check(await page.evaluate((s) => document.querySelector(s).open, sel), `1280px: a click opens ${sel}`);
      await page.keyboard.press('Escape');
      t.check(await page.evaluate((s) => !document.querySelector(s).open && document.activeElement === document.querySelector(`${s} > summary`), sel),
              `1280px: Escape closes ${sel} and focuses its summary`);
      await page.locator(`${sel} > summary`).first().click();
      await page.mouse.click(640, 500);
      t.check(await page.evaluate((s) => !document.querySelector(s).open, sel), `1280px: a click outside closes ${sel}`);
    }
    await page.close();
  }
}

// P4-B2b.1 items 7 and 8: at 1280 a dropdown closes when focus leaves it
// (vdoing's DropdownLink closes on focusout), not while it moves inside;
// and mode.js's own Escape wins over nav.js's (e.defaultPrevented)
async function item6Desktop(t) {
  const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: 'reduce' });
  await open(page, t, article);
  const cases = [['the language dropdown', '.clogem-langs__toggle', '.clogem-langs ul a'],
                 ['Browse', '.clogem-navbar__item > details:has(> summary > span)', '.clogem-navbar__item > details:has(> summary > span) .clogem-navbar__menu a']];
  for (const [what, sel, links] of cases) {
    await page.focus(`${sel} > summary`);
    await page.keyboard.press('Enter');
    const isOpenNow = () => page.evaluate((s) => document.querySelector(s).open, sel);
    t.check(await isOpenNow(), `1280: Enter opens ${what}`);
    const n = await page.evaluate((l) => document.querySelectorAll(l).length, links);
    let inside = true;
    for (let i = 0; i < n; i++) {
      await page.keyboard.press('Tab');
      const onLink = await page.evaluate((l) => [...document.querySelectorAll(l)].includes(document.activeElement), links);
      if (!onLink || !await isOpenNow()) inside = false;
    }
    t.check(n >= 2 && inside, `1280: Tab through the ${n} links of ${what} keeps it open`);
    await page.keyboard.press('Tab');
    t.check(!await isOpenNow(), `1280: Tab past the last link of ${what} closes it`);
    await page.keyboard.press('Escape');
  }
  // a mouse press inside an open dropdown, not on a link (the menu's padding,
  // the current language) leaves it open and the tab alive: the focusout
  // with no relatedTarget it causes is not focus outside (P4-B2b.1: closing
  // the <details> during that mousedown crashed Chromium 153's renderer)
  let crashed = false;
  page.on('crash', () => { crashed = true; });
  for (const [what, sel, spot] of [['Browse', '.clogem-navbar__item > details:has(> summary > span)', '.clogem-navbar__item > details:has(> summary > span) .clogem-navbar__menu'],
                                   ['the language dropdown', '.clogem-langs__toggle', '.clogem-langs ul .is-current']]) {
    await clickAt(page, `${sel} > summary`);
    t.check(await page.evaluate((s) => document.querySelector(s).open, sel), `1280: a click opens ${what}`);
    const xy = await page.evaluate((s) => { const r = document.querySelector(s).getBoundingClientRect(); return [r.left + 3, r.top + 3]; }, spot);
    const target = await page.evaluate(([x, y]) => { const e = document.elementFromPoint(x, y); return e && !e.closest('a[href]') ? e.tagName : 'a link'; }, xy);
    await page.mouse.click(xy[0], xy[1]);
    await page.waitForTimeout(100);
    t.check(!crashed, `1280: a press inside ${what} (on ${target}) crashed the page`);
    if (crashed) break;
    t.check(target !== 'a link' && await page.evaluate((s) => document.querySelector(s).open, sel), `1280: a press inside ${what}, on ${target}, leaves it open`);
    await page.mouse.click(640, 500);
    t.check(await page.evaluate((s) => !document.querySelector(s).open, sel), `1280: then a click outside closes ${what}`);
  }
  if (crashed) { await page.close().catch(() => {}); return; }
  // focus into a frame (giscus's iframe) blurs the window, with no
  // relatedTarget and no click in this document: the dropdown closes
  await page.evaluate(() => { const f = document.createElement('iframe'); f.id = 'clogem-b2b-frame'; f.style.cssText = 'width:300px;height:80px';
    f.srcdoc = '<button style="width:100%;height:60px">in a frame</button>'; document.querySelector('main').prepend(f); });
  await page.waitForTimeout(200);
  await clickAt(page, '.clogem-langs__toggle > summary');
  const wasOpen = await page.evaluate(() => document.querySelector('.clogem-langs__toggle').open);
  await clickAt(page, '#clogem-b2b-frame');
  await page.waitForTimeout(150);
  t.check(wasOpen && await page.evaluate(() => !document.querySelector('.clogem-langs__toggle').open && document.activeElement.id === 'clogem-b2b-frame'),
          '1280: a click into a frame closes the language dropdown');
  await page.evaluate(() => document.getElementById('clogem-b2b-frame').remove());

  // both open, focus in the mode menu: one Escape closes the mode menu only
  await clickAt(page, '.clogem-mode__button');
  await page.evaluate(() => { document.querySelector('.clogem-langs__toggle').open = true; });
  const before = await page.evaluate(() => [!document.getElementById('clogem-mode-menu').hidden, document.querySelector('.clogem-langs__toggle').open,
                                            !!document.activeElement.closest('#clogem-mode-menu')]);
  t.check(before.every(Boolean), `1280: the mode menu (focused) and the language dropdown are both open (${before})`);
  await page.keyboard.press('Escape');
  const after = await page.evaluate(() => [document.getElementById('clogem-mode-menu').hidden, document.querySelector('.clogem-langs__toggle').open]);
  t.check(after[0] && after[1], `1280: one Escape closes the mode menu (${after[0]}) and leaves the language dropdown open (${after[1]})`);
  await page.close();
}

// ---------------------------------------------------------------------------
// 7. The home's text card pads like every other main at 50rem and below

async function item7(t) {
  for (const width of [320, 360, 801, 1280]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    await open(page, t, article);
    const main = await page.evaluate(() => getComputedStyle(document.querySelector('main')).padding);
    t.check(main === (width <= 800 ? '16px' : '20px 24px'), `${width}px: the article's main pads ${main}`);
    for (const rel of ['', 'zh-Hant/', 'ms/', 'ta/', 'page/2/']) {
      await open(page, t, rel);
      const p = await page.evaluate(() => getComputedStyle(document.querySelector('.clogem-main--posts > .clogem-content')).padding);
      t.check(p === main, `${width}px /${rel}: the text card pads ${p}, want ${main} as the article's main`);
    }
    await page.close();
  }
}

// ---------------------------------------------------------------------------
// 10. Sidebar collapse

// runs in the page: the sidebar's and main's state, and the button's
function collapseState() {
  const aside = document.querySelector('aside.clogem-sidebar');
  const btn = document.querySelector('.clogem-sidebar-toggle');
  const main = document.querySelector('main.clogem-main');
  return { aside: getComputedStyle(aside).display, asideShown: aside.checkVisibility(), inShell: !!aside.closest('.clogem-shell'),
           main: main.getBoundingClientRect().width, expanded: btn.getAttribute('aria-expanded'),
           btn: btn.checkVisibility(), btnW: btn.getBoundingClientRect().width, btnH: btn.getBoundingClientRect().height,
           cls: document.querySelector('.clogem-shell').classList.contains('is-sidebar-collapsed'),
           focused: document.activeElement === btn };
}

// rewrites the article so its shell carries the server's marker
async function withMarker(page, t) {
  await page.route(url(t, article), async (route) => {
    const res = await route.fetch();
    const body = (await res.text()).replace('<div class="clogem-shell clogem-shell--toc">', '<div class="clogem-shell clogem-shell--toc" data-clogem-collapsed="">');
    await route.fulfill({ response: res, body });
  });
}

async function item10(t) {
  // a click collapses, Enter expands; focus stays on the button
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: 'reduce' });
    await open(page, t, article);
    const s0 = await page.evaluate(collapseState);
    t.check(s0.btn && s0.btnW >= 24 && s0.btnH >= 24 && s0.expanded === 'true' && s0.asideShown,
            `1280: nav.js reveals the button, ≥ 24×24, expanded, the sidebar shown (${JSON.stringify(s0)})`);
    await page.locator('.clogem-sidebar-toggle').click();
    const s1 = await page.evaluate(collapseState);
    t.check(s1.aside === 'none' && s1.expanded === 'false' && s1.main - s0.main >= 200,
            `1280: a click hides the aside (${s1.aside}), aria-expanded ${s1.expanded}, main ${s0.main} → ${s1.main}`);
    const icons = await page.evaluate(() => [...document.querySelectorAll('.clogem-sidebar-toggle .clogem-icon')]
      .filter((e) => e.checkVisibility()).map((e) => e.querySelector('use').getAttribute('href').split('#')[1]));
    t.check(icons.join() === 'chevron-right', `collapsed: the button shows chevron-right (${icons})`);
    // Tab from the top never lands inside the collapsed aside
    await page.evaluate(() => { document.activeElement.blur(); scrollTo({ top: 0, behavior: 'instant' }); });
    let inside = 0;
    for (let i = 0; i < 40; i++) {
      await page.keyboard.press('Tab');
      if (await page.evaluate(() => !!document.activeElement.closest('aside.clogem-sidebar'))) inside++;
    }
    t.check(inside === 0, `collapsed: ${inside} of 40 Tab stops landed inside the aside`);
    await page.focus('.clogem-sidebar-toggle');
    await page.keyboard.press('Enter');
    const s2 = await page.evaluate(collapseState);
    t.check(s2.asideShown && s2.expanded === 'true' && Math.abs(s2.main - s0.main) <= 0.5 && s2.focused,
            `1280: Enter expands it again and focus stays on the button (${JSON.stringify(s2)})`);
    const icons2 = await page.evaluate(() => [...document.querySelectorAll('.clogem-sidebar-toggle .clogem-icon')]
      .filter((e) => e.checkVisibility()).map((e) => e.querySelector('use').getAttribute('href').split('#')[1]));
    t.check(icons2.join() === 'chevron-left', `expanded: the button shows chevron-left (${icons2})`);
    // reduced motion: nothing here animates
    const tr = await page.evaluate(() => ['aside.clogem-sidebar', 'main.clogem-main', '.clogem-shell', '.clogem-sidebar-toggle']
      .map((s) => getComputedStyle(document.querySelector(s)).transitionDuration));
    t.check(tr.every((d) => /^0s(, 0s)*$/.test(d)), `reduce: every transition is 0s (${tr.join(' | ')})`);
    // RTL mirrors the chevron
    const m = await page.evaluate(() => { document.documentElement.dir = 'rtl';
      const v = getComputedStyle(document.querySelector('.clogem-sidebar-toggle .is-expanded')).transform;
      document.documentElement.dir = 'ltr'; return v; });
    t.check(m === 'matrix(-1, 0, 0, 1, 0, 0)', `rtl: the chevron is mirrored (${m})`);
    await page.close();
  }

  // the server's marker: collapsed at load with JS; shown, and no button, without it
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 } });
    await withMarker(page, t);
    await open(page, t, article);
    const s = await page.evaluate(collapseState);
    t.check(s.cls && s.aside === 'none' && s.expanded === 'false' && s.btn, `the marker: the page loads collapsed (${JSON.stringify(s)})`);
    await page.close();
  }
  for (const [what, opts, abort] of [['no JS', { javaScriptEnabled: false }, false], ['nav.js aborted', {}, true]]) {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, ...opts });
    await withMarker(page, t);
    if (abort) await page.route('**/clogem/js/nav.js*', (route) => route.abort());
    await page.goto(url(t, article), { waitUntil: 'load' });
    const s = await page.evaluate(() => ({ marker: document.querySelector('.clogem-shell').hasAttribute('data-clogem-collapsed'),
      aside: document.querySelector('aside.clogem-sidebar').checkVisibility(), btn: document.querySelector('.clogem-sidebar-toggle').checkVisibility() }));
    t.check(s.marker && s.aside && !s.btn, `${what}, with the marker: the sidebar shows and the button is invisible (${JSON.stringify(s)})`);
    await page.close();
  }

  // at 360 the button is not drawn; a collapsed state never hides the
  // drawer's sidebar, and survives the trip back to 1280
  {
    const page = await t.newPage({ viewport: { width: 360, height: 740 } });
    await open(page, t, article);
    t.check(!(await page.evaluate(collapseState)).btn, '360: the button is not visible');
    await page.setViewportSize({ width: 1280, height: 740 });
    await page.waitForFunction(() => !!document.querySelector('aside.clogem-sidebar').closest('.clogem-shell'));
    await page.locator('.clogem-sidebar-toggle').click();
    t.check((await page.evaluate(collapseState)).aside === 'none', '1280: collapsed');
    await page.setViewportSize({ width: 360, height: 740 });
    await page.waitForFunction(() => !!document.querySelector('#clogem-drawer aside.clogem-sidebar'));
    t.check(!(await page.evaluate(collapseState)).btn, '360 after a collapse: the button is not visible');
    await page.locator('.clogem-navbar__toggle > summary').click();
    const links = await page.evaluate(() => {
      const as = [...document.querySelectorAll('#clogem-drawer aside.clogem-sidebar a[href]')];
      return as.map((a) => { a.focus(); return a.checkVisibility() && document.activeElement === a; });
    });
    t.check(links.length >= 4 && links.every(Boolean), `360, the drawer open after a collapse: every sidebar link is shown and focusable (${links})`);
    await page.locator('.clogem-navbar__toggle > summary').click();
    await page.setViewportSize({ width: 1280, height: 740 });
    await page.waitForFunction(() => !!document.querySelector('aside.clogem-sidebar').closest('.clogem-shell'));
    const s = await page.evaluate(collapseState);
    t.check(s.aside === 'none' && s.expanded === 'false' && s.btn, `back at 1280: still collapsed (${JSON.stringify(s)})`);
    await page.close();
  }
}

// ---------------------------------------------------------------------------
// 11. Page buttons

// runs in the page: the page buttons' boxes and what they would cover
function pageButtonState() {
  const r = (e) => { const b = e.getBoundingClientRect(); return { left: b.left, right: b.right, top: b.top, bottom: b.bottom, width: b.width, height: b.height }; };
  const btns = [...document.querySelectorAll('a.clogem-page-button')].map((a) => ({
    cls: a.className, href: a.getAttribute('href'), shown: a.checkVisibility(), opacity: getComputedStyle(a).opacity,
    z: getComputedStyle(a).zIndex, ...r(a) }));
  const boxes = ['.clogem-sidebar', '.clogem-main', '.clogem-toc'].map((s) => [s, document.querySelector(s)]).filter(([, e]) => e).map(([s, e]) => ({ s, ...r(e) }));
  const visible = { prev: document.querySelector('.clogem-prev-next__prev')?.getAttribute('href'),
                    next: document.querySelector('.clogem-prev-next__next')?.getAttribute('href') };
  return { btns, boxes, visible, innerHeight };
}

async function item11(t) {
  const page = await t.newPage({ viewport: { width: 1440, height: 740 }, reducedMotion: 'reduce' });
  await open(page, t, article);
  for (const width of [1344, 1407, 1408, 1440]) {
    await page.setViewportSize({ width, height: 740 });
    const s = await page.evaluate(pageButtonState);
    const want = width >= 1408 ? 80 : 50;
    t.check(s.btns.length === 2 && s.btns.every((b) => b.shown), `${width}: both page buttons are visible (${JSON.stringify(s.btns)})`);
    const prev = s.btns.find((b) => b.cls.includes('is-prev')), next = s.btns.find((b) => b.cls.includes('is-next'));
    t.check(prev && next && prev.href === s.visible.prev && next.href === s.visible.next,
            `${width}: the buttons go where prev/next go (${prev && prev.href}, ${next && next.href} vs ${s.visible.prev}, ${s.visible.next})`);
    for (const b of s.btns) {
      t.check(Math.abs((b.top + b.bottom) / 2 - s.innerHeight / 2) <= 1 && Math.abs(b.height - 70) <= 0.5,
              `${width} ${b.cls}: vertically centred and 70 px tall (${b.top}–${b.bottom} in ${s.innerHeight})`);
      t.check(Math.abs(b.width - want) <= 0.5, `${width} ${b.cls}: ${b.width} px wide, want ${want}`);
      t.check(b.opacity === '0.55' && Number(b.z) < 10, `${width} ${b.cls}: opacity ${b.opacity}, z-index ${b.z}`);
      for (const x of s.boxes) {
        const meets = b.left < x.right && x.left < b.right && b.top < x.bottom && x.top < b.bottom;
        t.check(!meets, `${width} ${b.cls} (${b.left}–${b.right}) meets ${x.s} (${x.left}–${x.right})`);
      }
    }
    t.check(prev && next && prev.left === 0 && next.right === width, `${width}: at the viewport's edges (${prev && prev.left}, ${next && next.right})`);
    // every link a reader can see stays reachable beside them
    const covered = await page.evaluate(() => {
      const out = [];
      for (const a of document.querySelectorAll('.clogem-sidebar a[href], .clogem-content a[href], .clogem-toc a[href]')) {
        if (!a.checkVisibility()) continue;
        a.scrollIntoView({ behavior: 'instant', block: 'center', inline: 'nearest' });
        for (const r of a.getClientRects()) {
          const x = r.left + r.width / 2, y = r.top + r.height / 2;
          const hit = document.elementFromPoint(x, y);
          if (!a.contains(hit)) out.push(`${a.textContent.trim().slice(0, 30)} → ${hit && hit.className}`);
        }
      }
      scrollTo({ top: 0, behavior: 'instant' });
      return out;
    });
    t.check(covered.length === 0, `${width}: every visible link's centre is the link (${covered.slice(0, 5).join('; ')})`);
  }
  // hover: opacity 1
  await page.setViewportSize({ width: 1440, height: 740 });
  await page.hover('a.clogem-page-button.is-next');
  t.check(await page.evaluate(() => getComputedStyle(document.querySelector('a.clogem-page-button.is-next')).opacity) === '1',
          '1440: a hovered page button is opaque');
  // never a Tab stop
  await page.mouse.move(700, 400);
  await page.evaluate(() => document.activeElement.blur());
  let landed = 0;
  for (let i = 0; i < 40; i++) {
    await page.keyboard.press('Tab');
    if (await page.evaluate(() => !!document.activeElement.closest('.clogem-page-button'))) landed++;
  }
  t.check(landed === 0, `1440: ${landed} of 40 Tab stops landed on a page button`);
  // hidden below 84rem
  for (const width of [1343, 900, 360]) {
    await page.setViewportSize({ width, height: 740 });
    const shown = await page.evaluate(() => [...document.querySelectorAll('a.clogem-page-button')].map((a) => a.checkVisibility()));
    t.check(shown.length === 2 && shown.every((v) => !v), `${width}: the page buttons are hidden (${shown})`);
  }
  // the catalogue has a next button only
  await page.setViewportSize({ width: 1440, height: 740 });
  await open(page, t, 'pages/559f0f/');
  const c = await page.evaluate(pageButtonState);
  t.check(c.btns.length === 1 && c.btns[0].cls.includes('is-next') && c.btns[0].shown && c.btns[0].href === c.visible.next,
          `the catalogue at 1440: one next button (${JSON.stringify(c.btns)})`);
  await page.close();
}

// ---------------------------------------------------------------------------
// 12. Background patterns

// runs in the page: gives main pattern `n` and reads what it computes
function patternState(n) {
  const main = document.querySelector('main.clogem-main');
  main.classList.remove(...[1, 2, 3, 4, 5, 6].map((i) => `clogem-bg-style-${i}`));
  if (n) main.classList.add(`clogem-bg-style-${n}`);
  const c = getComputedStyle(main);
  const probe = document.createElement('div');
  probe.style.backgroundColor = 'var(--mainBg)';
  main.appendChild(probe);
  const mainBg = getComputedStyle(probe).backgroundColor;
  probe.remove();
  return { image: c.backgroundImage, position: c.backgroundPosition, size: c.backgroundSize, color: c.backgroundColor, mainBg,
           lines: c.getPropertyValue('--linesColor').trim() };
}

async function item12(t) {
  for (const rel of [article, 'pages/559f0f/']) {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, colorScheme: 'light' });
    await open(page, t, rel);
    const none = await page.evaluate(patternState, 0);
    t.check(none.image === 'none', `/${rel}: no pattern by default (${none.image})`);
    for (const n of [1, 2, 3, 4, 5, 6]) {
      const s = await page.evaluate(patternState, n);
      const fns = s.image.match(/(repeating-linear|radial|linear)-gradient\(/g) || [];
      const want = n === 1 ? ['linear-gradient(', 'linear-gradient('] : n === 6 ? ['radial-gradient('] : ['repeating-linear-gradient('];
      t.check(fns.join() === want.join(), `/${rel} pattern ${n}: ${fns.join(' ')}, want ${want.join(' ')}`);
      t.check(s.image.includes('rgba(50, 0, 0, 0.05)'), `/${rel} pattern ${n}: drawn in light's --linesColor (${s.image.slice(0, 80)}…)`);
      t.check(s.color === s.mainBg, `/${rel} pattern ${n}: background-color ${s.color} is --mainBg ${s.mainBg}`);
      if (n === 1) t.check(s.position.split(', ').every((p) => p === '50% 50%'), `/${rel} pattern 1: each layer at 50% 50% (${s.position})`);
    }
    // dark mode: the dark lines
    await page.evaluate(() => { const h = document.documentElement; h.className = h.className.replace(/\btheme-mode-\S+/, 'theme-mode-dark'); });
    const dark = await page.evaluate(patternState, 3);
    t.check(dark.lines === 'rgba(125, 125, 125, 0.05)' && dark.image.includes('rgba(125, 125, 125, 0.05)'),
            `/${rel} dark: --linesColor is ${dark.lines}`);
    t.check(dark.color === dark.mainBg, `/${rel} dark: background-color ${dark.color} is --mainBg ${dark.mainBg}`);
    // print: no pattern
    await page.emulateMedia({ media: 'print' });
    const printed = await page.evaluate(patternState, 6);
    t.check(printed.image === 'none', `/${rel} print: background-image ${printed.image}`);
    await page.close();
  }
}

// ---------------------------------------------------------------------------
// 13. Back to top

function upState() {
  const b = document.querySelector('.clogem-back-to-top');
  const r = b.getBoundingClientRect();
  return { shown: b.checkVisibility(), hidden: b.hidden, left: r.left, right: r.right, top: r.top, bottom: r.bottom,
           width: r.width, height: r.height, iw: document.documentElement.clientWidth, ih: innerHeight, y: scrollY };
}

// runs in the page: the links of `sel` whose centre is on screen, below the
// navbar, and is not the link itself (something covers it)
function coveredLinks(sel) {
  const nav = document.querySelector('.clogem-navbar').getBoundingClientRect().bottom;
  const out = [];
  let checked = 0;
  for (const a of document.querySelectorAll(sel)) {
    if (!a.checkVisibility()) continue;
    for (const r of a.getClientRects()) {
      const x = r.left + r.width / 2, y = r.top + r.height / 2;
      if (y < Math.max(nav, 0) || y > innerHeight || x < 0 || x > innerWidth) continue;
      checked++;
      const hit = document.elementFromPoint(x, y);
      if (!a.contains(hit)) out.push(`${a.textContent.trim().slice(0, 24)} → ${hit && hit.className}`);
    }
  }
  return { checked, out };
}

async function item13(t) {
  for (const width of [360, 1280]) {
    for (const rel of [article, '']) {
      const what = `${width} /${rel}`;
      const page = await t.newPage({ viewport: { width, height: 740 }, reducedMotion: 'reduce' });
      await open(page, t, rel);
      const s0 = await page.evaluate(upState);
      t.check(!s0.shown && s0.hidden, `${what}: hidden at scrollY 0 (${JSON.stringify(s0)})`);
      // the threshold is 100 px (P4-B2b.1 item 8): hidden at 50, shown at 101
      const frames = () => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
      for (const [y, want] of [[50, false], [101, true], [50, false]]) {
        await scrollTop(page, y);
        await frames();
        const s = await page.evaluate(upState);
        t.check(s.y === y && s.shown === want, `${what}: at scrollY ${s.y} back to top is ${s.shown ? 'shown' : 'hidden'}, want ${want ? 'shown' : 'hidden'}`);
      }
      t.check(await scrollTop(page, 200) === 200, `${what}: scrollY reached 200`);
      const shown = await page.waitForFunction(() => document.querySelector('.clogem-back-to-top').checkVisibility(), null, { timeout: 1000 })
        .then(() => true, () => false);
      const s1 = await page.evaluate(upState);
      t.check(shown && s1.width >= 24 && s1.height >= 24 && s1.left >= 0 && s1.right <= s1.iw && s1.top >= 0 && s1.bottom <= s1.ih,
              `${what}: after 200 px it shows within 1 s, ≥ 24×24, inside the viewport (${JSON.stringify(s1)})`);
      const want = width <= 800 ? [16, 24] : [32, 40];
      t.check(Math.abs(s1.iw - s1.right - want[0]) <= 0.5 && Math.abs(s1.ih - s1.bottom - want[1]) <= 0.5,
              `${what}: ${s1.iw - s1.right} px from the right, ${s1.ih - s1.bottom} from the bottom, want ${want}`);
      // reduce: Enter takes the page to the top at once, and hides it
      await page.focus('.clogem-back-to-top');
      await page.keyboard.press('Enter');
      const s2 = await page.evaluate(upState);
      t.check(s2.y === 0 && s2.hidden, `${what} reduce: after Enter scrollY is ${s2.y}, the button hidden ${s2.hidden}`);
      await page.keyboard.press('Tab');
      const f = await page.evaluate(() => {
        const e = document.activeElement;
        return e.matches('.clogem-navbar__brand') ? 'brand' : e.matches('.clogem-navbar__toggle > summary') ? 'menu' : e.tagName + '.' + e.className;
      });
      t.check(f === (width <= 800 ? 'menu' : 'brand'), `${what}: one Tab after it focuses ${f}`);
      t.check((await page.evaluate(upState)).hidden, `${what}: still hidden at the top`);
      await page.close();
    }
  }

  // no-preference: a smooth way up, the button hidden all the way
  for (const width of [360, 1280]) {
    const page = await t.newPage({ viewport: { width, height: 740 }, reducedMotion: 'no-preference' });
    await open(page, t, article);
    t.check(await scrollTop(page, 1500) === 1500, `${width} smooth: scrollY reached 1500`);
    await page.waitForFunction(() => document.querySelector('.clogem-back-to-top').checkVisibility(), null, { timeout: 1000 });
    await page.evaluate(() => {
      window.__up = [];
      const f = () => { window.__up.push([scrollY, document.querySelector('.clogem-back-to-top').hidden]);
                        if (scrollY > 0 && window.__up.length < 400) requestAnimationFrame(f); };
      document.querySelector('.clogem-back-to-top').addEventListener('click', () => requestAnimationFrame(f), { once: true });
    });
    await page.focus('.clogem-back-to-top');
    await page.keyboard.press('Enter');
    const reached = await page.waitForFunction(() => scrollY === 0, null, { timeout: 3000 }).then(() => true, () => false);
    const samples = await page.evaluate(() => window.__up);
    t.check(reached, `${width} smooth: scrollY reached 0 within 3 s`);
    t.check(samples.some(([y]) => y > 0 && y < 1500), `${width} smooth: a sample on the way (${samples.slice(0, 6).map(([y]) => y).join(', ')}…)`);
    t.check(samples.every(([, h]) => h), `${width} smooth: hidden from the click on (${samples.filter(([, h]) => !h).length} frames shown)`);
    await page.close();
  }

  // without JS, or with nav.js aborted, it never shows
  for (const [what, opts, abort] of [['no JS', { javaScriptEnabled: false }, false], ['nav.js aborted', {}, true]]) {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, ...opts });
    if (abort) await page.route('**/clogem/js/nav.js*', (route) => route.abort());
    await page.goto(url(t, article), { waitUntil: 'load' });
    await page.evaluate(() => scrollTo({ top: 200, behavior: 'instant' }));
    await page.waitForTimeout(1000);
    t.check(!(await page.evaluate(upState)).shown, `${what}: back to top never shows`);
    await page.close();
  }

  // it covers no link: the TOC and the sidebar at 1280, prev/next at 360
  // at the end, the drawer's links at 360
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: 'reduce' });
    await open(page, t, article);
    for (const y of [200, 'end']) {
      await page.evaluate((y) => scrollTo({ top: y === 'end' ? document.documentElement.scrollHeight : y, behavior: 'instant' }), y);
      await page.waitForFunction(() => document.querySelector('.clogem-back-to-top').checkVisibility(), null, { timeout: 1000 });
      const c = await page.evaluate(coveredLinks, '.clogem-toc a[href], .clogem-sidebar a[href]');
      t.check(c.checked >= 4 && c.out.length === 0, `1280 at ${y}: back to top covers no TOC or sidebar link (${c.checked} checked; ${c.out.join('; ')})`);
    }
    await page.close();
  }
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 740 }, reducedMotion: 'reduce' });
    await page.route(url(t, article), async (route) => {
      const res = await route.fetch();
      const lis = Array.from({ length: 60 }, (_, i) => `<li class="level-2"><a href="#nesting">Entry ${i + 1}</a></li>`).join('');
      await route.fulfill({ response: res, body: (await res.text()).replace(/(<aside class="clogem-toc"><nav[^>]*><ul>)/, '$1' + lis) });
    });
    await open(page, t, article);
    await scrollTop(page, 600);
    await page.waitForFunction(() => document.querySelector('.clogem-back-to-top').checkVisibility(), null, { timeout: 1000 });
    const r = await page.evaluate(() => ({ toc: document.querySelector('.clogem-toc').getBoundingClientRect().bottom,
                                           up: document.querySelector('.clogem-back-to-top').getBoundingClientRect().top }));
    t.check(r.toc <= r.up, `1280, a 60-entry TOC: the stuck TOC's bottom ${r.toc} is above back to top (${r.up})`);
    await page.close();
  }
  {
    const page = await t.newPage({ viewport: { width: 360, height: 740 }, reducedMotion: 'reduce' });
    await open(page, t, article);
    await page.evaluate(() => scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' }));
    await page.waitForFunction(() => document.querySelector('.clogem-back-to-top').checkVisibility(), null, { timeout: 1000 });
    const c = await page.evaluate(coveredLinks, '.clogem-prev-next a');
    t.check(c.checked === 2 && c.out.length === 0, `360 at the end: back to top covers neither prev/next link (${c.checked} checked; ${c.out.join('; ')})`);
    await page.close();
  }
  {
    // 480 px tall, so the open drawer reaches the bottom of the viewport
    const page = await t.newPage({ viewport: { width: 360, height: 480 }, reducedMotion: 'reduce' });
    await open(page, t, article);
    // the drawer, open after 200 px, covers it. (Opening it keeps the page
    // at 200 — before P4-B2b.1 item 1 it did not — so the second scrollTop
    // is only a guard; the drawer is scrolled in steps.)
    await scrollTop(page, 200);
    await page.waitForFunction(() => document.querySelector('.clogem-back-to-top').checkVisibility(), null, { timeout: 1000 });
    await page.locator('.clogem-navbar__toggle > summary').click();
    await scrollTop(page, 200);
    await page.waitForFunction(() => document.querySelector('.clogem-back-to-top').checkVisibility(), null, { timeout: 1000 }).catch(() => {});
    const d = await page.evaluate(() => {
      const drawer = document.getElementById('clogem-drawer');
      const up = document.querySelector('.clogem-back-to-top');
      const ur = up.getBoundingClientRect();
      const out = [], seen = new Set();
      let under = 0;
      for (let top = 0; top <= drawer.scrollHeight; top += drawer.clientHeight / 2) {
        drawer.scrollTop = top;
        const dr = drawer.getBoundingClientRect();
        for (const a of drawer.querySelectorAll('a[href]')) {
          if (!a.checkVisibility()) continue;
          const r = a.getBoundingClientRect();
          const x = r.left + r.width / 2, y = r.top + r.height / 2;
          if (y < dr.top || y > Math.min(dr.bottom, innerHeight)) continue;
          seen.add(a);
          if (r.right > ur.left && r.bottom > ur.top) under++;
          const hit = document.elementFromPoint(x, y);
          if (!a.contains(hit)) out.push(`${a.textContent.trim().slice(0, 24)} → ${hit && hit.className}`);
        }
      }
      // where the two meet, the drawer is drawn over the button
      const mid = up.getBoundingClientRect(), dr = drawer.getBoundingClientRect();
      const x = (mid.left + Math.min(mid.right, dr.right)) / 2, y = (mid.top + mid.bottom) / 2;
      const over = mid.left < dr.right && mid.bottom <= dr.bottom + 0.5 ? drawer.contains(document.elementFromPoint(x, y)) : null;
      return { checked: seen.size, under, out, over, shown: up.checkVisibility(), open: document.querySelector('.clogem-navbar__toggle').open, y: scrollY };
    });
    t.check(d.open && d.y === 200 && d.over === true, `360×480, the drawer open at 200: it is drawn over back to top (${JSON.stringify({ open: d.open, y: d.y, over: d.over })})`);
    t.check(d.shown && d.checked >= 8 && d.out.length === 0, `360, the drawer open: back to top covers no drawer link (${d.checked} checked, shown ${d.shown}; ${d.out.join('; ')})`);
    await page.close();
  }
}

// each item on its own (P4-B2b.1 item 8): a throw is that item's failure,
// and the later items still run. CLOGEM_LAYOUT_ITEMS=item1,item13 runs only
// those
export default async function layout(t) {
  const items = [item1, item1Load, item1Focus, item2, item2Resize, item2LongNav, item3, item3RightMenuBar, item4, item5, item6, item6Desktop, item7,
                 item10, item11, item12, item13];
  const only = (process.env.CLOGEM_LAYOUT_ITEMS || '').split(',').map((n) => n.trim()).filter(Boolean);
  for (const n of only) t.check(items.some((i) => i.name === n), `CLOGEM_LAYOUT_ITEMS: no item ${n} (${items.map((i) => i.name).join(', ')})`);
  for (const item of items) {
    if (only.length && !only.includes(item.name)) continue;
    try {
      await item(t);
    } catch (e) {
      t.check(false, `${item.name} threw: ${e && e.stack || e}`);
    }
  }
}
