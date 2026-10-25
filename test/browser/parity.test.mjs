// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// Article-page parity (Phase 4 Task E2), on the demo, which sets :theme
// :logo and :repo and :content :edit-link:
//
//   1. the repository link is the nav's last item: in the open drawer at
//      320 and 360, focusable, its label shown; at 900 the label is
//      visually hidden but still the link's name ("GitHub"); at 1280 it
//      shows;
//   2. the logo loads and is 2.2rem tall; with it, the site title is
//      visually hidden at 64rem and below and still names the brand link;
//   3. print hides the edit link and the update bar;
//   4. (P4-E2.1 item 1) a row's badges (`titleTag`, the fallback marker)
//      on an index, a tag page, a home and a catalogue at 320, 360 and
//      414: each on one line, no taller than its single-line box plus
//      2 px, within its row, the date at the row's end; at 1280 the badge
//      follows the title on its line. (P4-E2.2 items 1 and 2) at 320 the
//      /zh-Hans/ y2025a row (a `titleTag` and a fallback marker), its title
//      set to "Configuration" and to a 40-character word, does not
//      overflow the page and its date ends at the row's end; in a wrapped
//      row at 320 and 360 each badge's text baseline is within 2 px of the
//      title's first-line baseline.
//
// The navbar's one-row scan (801–1600 px, every home) is layout.test.mjs
// item 2's, which runs on the same demo.

const homes = ['', 'zh-Hans/', 'zh-Hant/', 'ms/', 'ta/'];
const article = 'pages/c0nta1/';

function url(t, rel) {
  return new URL(rel.split('/').map(encodeURIComponent).join('/'), t.baseURL).href;
}

async function open(page, t, rel) {
  await page.goto(url(t, rel), { waitUntil: 'load' });
  await page.waitForTimeout(100);
}

// runs in the page: the repo link, its label's box and whether it is the
// nav's last item
function repoState() {
  const a = document.querySelector('.clogem-navbar__repo');
  if (!a) return null;
  const label = a.querySelector('.clogem-navbar__repo-label');
  const lr = label.getBoundingClientRect();
  const lis = [...document.querySelectorAll('.clogem-navbar__nav > ul > li')];
  return { last: lis[lis.length - 1] === a.parentElement, href: a.getAttribute('href'),
           target: a.getAttribute('target'), labelW: lr.width, labelH: lr.height,
           shown: a.checkVisibility(), text: label.textContent };
}

async function item1(t) {
  for (const width of [320, 360]) {
    const page = await t.newPage({ viewport: { width, height: 740 }, reducedMotion: 'reduce' });
    for (const rel of [...homes, article]) {
      await open(page, t, rel);
      const what = `${width}px /${rel}`;
      await page.locator('.clogem-navbar__toggle > summary').click();
      await page.waitForTimeout(50);
      const s = await page.evaluate(repoState);
      t.check(s && s.last, `${what}: the repo link is the nav's last item (${JSON.stringify(s)})`);
      if (!s) continue;
      t.check(s.shown && s.labelW > 20 && s.labelH > 5, `${what}: in the open drawer the label shows (${s.labelW}×${s.labelH})`);
      t.check(s.href === 'https://github.com/EchoJustus/clogem-press' && s.target === null,
              `${what}: the link goes to the repository in the same tab (${s.href}, target ${s.target})`);
      const focused = await page.evaluate(() => {
        const a = document.querySelector('.clogem-navbar__repo');
        a.focus();
        return document.activeElement === a && a.closest('#clogem-drawer') !== null;
      });
      t.check(focused, `${what}: the repo link can be focused in the open drawer`);
      const inView = await page.evaluate(() => { const r = document.querySelector('.clogem-navbar__repo').getBoundingClientRect();
        return r.left >= 0 && r.right <= innerWidth; });
      t.check(inView, `${what}: the repo link fits the viewport`);
      // Tab reaches it from the toggle, as every other drawer link
      await page.focus('.clogem-navbar__toggle > summary');
      let reached = false;
      for (let i = 0; i < 25 && !reached; i++) {
        await page.keyboard.press('Tab');
        reached = await page.evaluate(() => document.activeElement && document.activeElement.matches('.clogem-navbar__repo'));
      }
      t.check(reached, `${what}: Tab from the menu toggle reaches the repo link`);
      await page.keyboard.press('Escape');
    }
    await page.close();
  }
  for (const [width, visible] of [[801, false], [900, false], [1024, false], [1025, true], [1280, true]]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    for (const rel of ['', 'ms/']) {
      await open(page, t, rel);
      const what = `${width}px /${rel}`;
      const s = await page.evaluate(repoState);
      t.check(s && s.last && s.shown, `${what}: the repo link is the nav's last item, shown`);
      if (!s) continue;
      if (visible) t.check(s.labelW > 20, `${what}: the label shows (${s.labelW} px)`);
      else t.check(s.labelW <= 1, `${what}: the label is visually hidden (${s.labelW} px)`);
      const named = await page.getByRole('link', { name: 'GitHub', exact: true }).count();
      t.check(named === 1, `${what}: one link is named "GitHub" (${named})`);
    }
    await page.close();
  }
}

async function item2(t) {
  for (const [width, titleShown] of [[320, false], [360, false], [900, false], [1024, false], [1025, true], [1280, true]]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    for (const rel of homes) {
      await open(page, t, rel);
      const what = `${width}px /${rel}`;
      const s = await page.evaluate(() => {
        const img = document.querySelector('.clogem-navbar__brand > img.clogem-navbar__logo');
        const title = document.querySelector('.clogem-navbar__brand > .clogem-navbar__title');
        const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
        const ir = img.getBoundingClientRect(), tr = title.getBoundingClientRect();
        return { natural: img.naturalWidth, complete: img.complete, h: ir.height, rem, titleW: tr.width,
                 text: title.textContent, alt: img.getAttribute('alt') };
      });
      t.check(s.complete && s.natural > 0, `${what}: the logo loaded (naturalWidth ${s.natural})`);
      t.check(Math.abs(s.h - 2.2 * s.rem) <= 0.5, `${what}: the logo is ${s.h} px tall, want 2.2rem (${2.2 * s.rem})`);
      t.check(s.alt === '', `${what}: the logo is decorative (alt "${s.alt}")`);
      if (titleShown) t.check(s.titleW > 20, `${what}: the title shows (${s.titleW} px)`);
      else t.check(s.titleW <= 1, `${what}: the title is visually hidden (${s.titleW} px)`);
      const named = await page.getByRole('link', { name: s.text, exact: true }).count();
      t.check(named >= 1, `${what}: the brand link is still named "${s.text}" (${named})`);
    }
    await page.close();
  }
}

async function item3(t) {
  const page = await t.newPage({ viewport: { width: 1280, height: 900 } });
  await page.emulateMedia({ media: 'print' });
  for (const rel of ['pages/643259/', 'zh-Hans/pages/643259/', 'pages/559f0f/']) {
    await open(page, t, rel);
    const s = await page.evaluate(() => {
      const vis = (sel) => { const e = document.querySelector(sel); return e ? e.checkVisibility() : null; };
      return { link: vis('.clogem-page-edit__link'), bar: vis('.clogem-update-bar'), edit: !!document.querySelector('.clogem-page-edit') };
    });
    t.check(s.edit, `print /${rel}: the page has an edit line`);
    t.check(s.link === false, `print /${rel}: the edit link is hidden (${s.link})`);
    t.check(s.bar !== true, `print /${rel}: the update bar is hidden (${s.bar})`);
  }
  await page.emulateMedia({ media: 'screen' });
  await open(page, t, 'pages/643259/');
  const shown = await page.evaluate(() => [document.querySelector('.clogem-page-edit__link').checkVisibility(),
                                           document.querySelector('.clogem-update-bar').checkVisibility()]);
  t.check(shown[0] && shown[1], `screen: the edit link and the update bar show (${shown})`);
  await page.close();
}

// runs in the page: every badge in a row, with its box, its single-line
// height (an absolutely positioned clone beside it, so nothing squeezes
// it), its text's line boxes, its row's box and the row's link and date
function rowBadges() {
  return [...document.querySelectorAll('.clogem-row')].flatMap((row) =>
    [...row.querySelectorAll('.clogem-title-tag, .clogem-fallback')].map((b) => {
      const clone = b.cloneNode(true);
      clone.style.cssText = 'position: absolute; white-space: nowrap; visibility: hidden;';
      row.appendChild(clone);
      const one = clone.getBoundingClientRect().height;
      clone.remove();
      const range = document.createRange();
      range.selectNodeContents(b);
      const rects = [...range.getClientRects()].filter((r) => r.width > 0);
      const br = b.getBoundingClientRect(), rr = row.getBoundingClientRect();
      const cs = getComputedStyle(row);
      const time = row.querySelector('time');
      const links = [...row.querySelector('a').getClientRects()];
      const last = links[links.length - 1];
      return { text: b.textContent, w: br.width, h: br.height, one, lines: rects.length,
               top: br.top, bottom: br.bottom, left: br.left, rowTop: rr.top, rowBottom: rr.bottom,
               rowRight: rr.right - parseFloat(cs.paddingRight) - parseFloat(cs.borderRightWidth),
               timeRight: time ? time.getBoundingClientRect().right : null,
               linkRight: last.right, linkTop: last.top, linkBottom: last.bottom };
    }));
}

async function item4(t) {
  const pages = ['archives/', 'tags/meta/', 'zh-Hans/', 'pages/559f0f/'];
  for (const width of [320, 360, 414, 1280]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    for (const rel of pages) {
      await open(page, t, rel);
      const what = `${width}px /${rel}`;
      const bs = await page.evaluate(rowBadges);
      t.check(bs.length > 0, `${what}: the page has a badge in a row`);
      for (const b of bs) {
        const box = `"${b.text}" ${b.w.toFixed(1)}×${b.h.toFixed(1)}`;
        if (width === 1280) {
          const mid = (b.top + b.bottom) / 2;
          t.check(b.left >= b.linkRight - 1 && mid > b.linkTop && mid < b.linkBottom,
                  `${what}: badge ${box} follows the title on its line (left ${b.left}, title ends ${b.linkRight}, ${b.linkTop}–${b.linkBottom})`);
          continue;
        }
        t.check(b.lines === 1, `${what}: badge ${box} is one line (${b.lines} line boxes)`);
        t.check(b.h <= b.one + 2, `${what}: badge ${box} is no taller than its single-line ${b.one.toFixed(1)} px + 2`);
        t.check(b.top >= b.rowTop - 0.5 && b.bottom <= b.rowBottom + 0.5,
                `${what}: badge ${box} is within its row (${b.top}–${b.bottom} in ${b.rowTop}–${b.rowBottom})`);
        if (b.timeRight !== null)
          t.check(Math.abs(b.timeRight - b.rowRight) <= 0.5, `${what}: the date ends at the row's end (${b.timeRight}, row ${b.rowRight})`);
      }
    }
    await page.close();
  }
}

// runs in the page: set the y2025a row's title to `text`, then the page's
// and the row's widths and the date's right edge
function longWordRow(text) {
  const a = document.querySelector('.clogem-row a[href$="/y2025a/"]');
  if (!a) return null;
  a.textContent = text;
  const row = a.closest('.clogem-row'), rr = row.getBoundingClientRect(), cs = getComputedStyle(row);
  return { scrollWidth: document.documentElement.scrollWidth, innerWidth,
           badges: row.querySelectorAll('.clogem-title-tag, .clogem-fallback').length,
           rowRight: rr.right - parseFloat(cs.paddingRight) - parseFloat(cs.borderRightWidth),
           timeRight: row.querySelector('time').getBoundingClientRect().right };
}

async function item4LongWord(t) {
  const page = await t.newPage({ viewport: { width: 320, height: 740 } });
  for (const text of ['Configuration', 'x'.repeat(40)]) {
    await open(page, t, 'zh-Hans/');
    const r = await page.evaluate(longWordRow, text);
    const what = `320px /zh-Hans/ y2025a titled "${text}"`;
    t.check(r && r.badges === 2, `${what}: the row has two badges (${r && r.badges})`);
    if (!r) continue;
    t.check(r.scrollWidth <= r.innerWidth, `${what}: the page does not overflow (${r.scrollWidth} > ${r.innerWidth})`);
    t.check(Math.abs(r.timeRight - r.rowRight) <= 0.5, `${what}: the date ends at the row's end (${r.timeRight}, row ${r.rowRight})`);
  }
  await page.close();
}

// runs in the page: for every row whose title wraps, the title's
// first-line baseline and each badge's text baseline (the bottom of a
// zero-size inline-block put first in each)
function wrappedBaselines() {
  const mark = (el) => {
    const m = document.createElement('span');
    m.style.cssText = 'display: inline-block; width: 0; height: 0; vertical-align: baseline;';
    el.insertBefore(m, el.firstChild);
    const y = m.getBoundingClientRect().bottom;
    m.remove();
    return y;
  };
  return [...document.querySelectorAll('.clogem-row')].flatMap((row) => {
    const a = row.querySelector('a');
    const range = document.createRange();
    range.selectNodeContents(a);
    const tops = new Set([...range.getClientRects()].filter((r) => r.width > 0).map((r) => Math.round(r.top)));
    if (tops.size < 2) return [];
    const title = mark(a);
    return [...row.querySelectorAll('.clogem-title-tag, .clogem-fallback')].map((b) =>
      ({ text: b.textContent, link: a.textContent, title, badge: mark(b) }));
  });
}

async function item4Baseline(t) {
  for (const width of [320, 360]) {
    const page = await t.newPage({ viewport: { width, height: 740 } });
    let n = 0;
    for (const rel of ['archives/', 'tags/meta/', 'zh-Hans/', 'pages/559f0f/']) {
      await open(page, t, rel);
      for (const b of await page.evaluate(wrappedBaselines)) {
        n++;
        t.check(Math.abs(b.badge - b.title) <= 2,
                `${width}px /${rel}: badge "${b.text}" in "${b.link}" sits on the title's first line (baseline ${b.badge.toFixed(1)}, title ${b.title.toFixed(1)})`);
      }
    }
    t.check(n > 0, `${width}px: a wrapped row with a badge was found`);
    await page.close();
  }
}

export default async function (t) {
  for (const item of [item1, item2, item3, item4, item4LongWord, item4Baseline]) {
    try {
      await item(t);
    } catch (e) {
      t.check(false, `${item.name} threw: ${e && e.stack || e}`);
    }
  }
}
