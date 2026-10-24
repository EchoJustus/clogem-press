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
//   3. print hides the edit link and the update bar.
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

export default async function (t) {
  for (const item of [item1, item2, item3]) {
    try {
      await item(t);
    } catch (e) {
      t.check(false, `${item.name} threw: ${e && e.stack || e}`);
    }
  }
}
