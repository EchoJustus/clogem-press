// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// htmlModules (Phase 4 Task E1b, DESIGN.md §11.3 item 13) in a real
// browser. On a temp copy of the demo whose site.edn sets every slot —
// built with `bb build --no-search --no-highlight` and served through
// `page.route`: no page overflow at 320 and 360 px with a 480 px element
// in a module (each wrapper scrolls instead), the open drawer showing
// `sidebar-t` before the tree, a focus ring at a wrapper's edge staying
// inside it, the two windows clearing back to top and the page buttons at
// 1280×800, 1344×740, 1440×740 and 1600×740, none at 960 px and both at
// 961, the close button hiding its own window by keyboard (focus ending on
// <body>), no close button without JS, and print drawing neither a module
// nor a window. When `bb` is not on PATH it says so and skips; CI's
// browser job has bb.

import { spawn } from 'node:child_process';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const ORIGIN = 'http://clogem-e1b.test';
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css',
                '.svg': 'image/svg+xml', '.png': 'image/png', '.xml': 'application/xml' };

// a 480 px block in the narrow slots, and 300 × 500 px in each window
const WIDE = '<div style=\\"width:480px;height:40px;background:#8884\\">wide</div><a href=\\"/\\">edge</a>';
const BIG = '<div style=\\"width:300px;height:500px;background:#8884\\">big</div>';

function run(cmd, args, opts) {
  return new Promise((resolve) => {
    let out = '';
    let child;
    try { child = spawn(cmd, args, opts); } catch (e) { resolve({ code: -1, out: e.message, missing: true }); return; }
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { out += d; });
    child.on('error', (e) => resolve({ code: -1, out: e.message, missing: e.code === 'ENOENT' }));
    child.on('close', (code) => resolve({ code, out }));
  });
}

async function build() {
  const site = await mkdtemp(path.join(os.tmpdir(), 'clogem-e1b-'));
  await cp(path.join(repo, 'examples', 'demo-site'), site, {
    recursive: true, filter: (src) => !src.split(path.sep).includes('dist'),
  });
  const edn = await readFile(path.join(site, 'site.edn'), 'utf8');
  if (!edn.includes(':per-page 3')) throw new Error('the demo site.edn has no `:per-page 3` to extend');
  const modules = ':html-modules {'
    + [':page-t', ':sidebar-t', ':home-sidebar-b'].map((k) => `${k} "${WIDE}"`).join(' ')
    + ` :window-lb "${BIG}" :window-rb "${BIG}"}`;
  await writeFile(path.join(site, 'site.edn'), edn.replace(':per-page 3', `:per-page 3 ${modules}`));
  const r = await run('bb', ['--config', path.join(repo, 'bb.edn'), 'build', '--no-search', '--no-highlight', '--no-write'],
                      { cwd: site });
  return { site, dist: path.join(site, 'dist'), ...r };
}

async function serve(t, dist, opts) {
  const page = await t.newPage(opts);
  await page.route(`${ORIGIN}/**`, async (route) => {
    let rel = decodeURIComponent(new URL(route.request().url()).pathname);
    if (rel.endsWith('/')) rel += 'index.html';
    try {
      const body = await readFile(path.join(dist, rel));
      await route.fulfill({ status: 200, body, contentType: types[path.extname(rel)] || 'application/octet-stream' });
    } catch {
      await route.fulfill({ status: 404, body: 'not found' });
    }
  });
  return page;
}

// the article the layout tests use: a sidebar, a TOC and both page buttons
const ARTICLE = `${ORIGIN}/pages/17c887/`;

export default async function (t) {
  const b = await build();
  try {
    if (b.missing) {
      console.log('skip: bb is not on PATH, so the htmlModules build was not made');
      return;
    }
    t.check(b.code === 0, `the temp build with every htmlModules slot succeeds:\n${b.out}`);
    if (b.code !== 0) return;

    // --- item 2: no page overflow at 320 and 360; each wrapper scrolls -----
    for (const width of [320, 360]) {
      // the en home hides its right bar, so home-sidebar-b is on zh-Hans's
      for (const [rel, sel] of [['pages/17c887/', '.clogem-module--page-t'],
                                ['pages/17c887/', '.clogem-module--sidebar-t'],
                                ['zh-Hans/', '.clogem-module--home-sidebar-b']]) {
        const page = await serve(t, b.dist, { viewport: { width, height: 740 } });
        await page.goto(`${ORIGIN}/${rel}`, { waitUntil: 'load' });
        // at this width nav.js has moved the sidebar into the drawer, which
        // has no layout until it opens
        if (sel === '.clogem-module--sidebar-t') await page.locator('.clogem-navbar__toggle > summary').click();
        const r = await page.evaluate((s) => {
          const e = document.querySelector(s);
          if (!e) return null;
          e.scrollLeft = 9999;
          return { sw: document.documentElement.scrollWidth, w: innerWidth,
                   scrolled: e.scrollLeft > 0, over: e.scrollWidth > e.clientWidth };
        }, sel);
        t.check(r !== null, `${rel} at ${width}: ${sel} is on the page`);
        if (r) {
          t.check(r.sw <= r.w, `${rel} at ${width} with a 480 px ${sel}: no page overflow (${r.sw} > ${r.w})`);
          t.check(r.over && r.scrolled, `${sel} scrolls inside its own box at ${width}`);
        }
        await page.close();
      }
    }

    // --- item 2: the open drawer shows sidebar-t before the tree ----------
    {
      const page = await serve(t, b.dist, { viewport: { width: 360, height: 740 } });
      await page.goto(ARTICLE, { waitUntil: 'load' });
      await page.locator('.clogem-navbar__toggle > summary').click();
      const r = await page.evaluate(() => {
        const m = document.querySelector('#clogem-drawer .clogem-module--sidebar-t');
        const nav = document.querySelector('#clogem-drawer #clogem-sidebar nav');
        if (!m || !nav) return null;
        return { shown: m.getClientRects().length > 0,
                 before: m.compareDocumentPosition(nav) & Node.DOCUMENT_POSITION_FOLLOWING ? true : false,
                 top: m.getBoundingClientRect().top, navTop: nav.getBoundingClientRect().top };
      });
      t.check(r !== null, '360, drawer open: the sidebar and its sidebar-t module are in the drawer');
      if (r) {
        t.check(r.shown, 'the drawer draws sidebar-t');
        t.check(r.before && r.top <= r.navTop, `sidebar-t comes before the tree (${r.top} ≤ ${r.navTop})`);
      }
      await page.close();
    }

    // --- item 2: a focus ring at a wrapper's edge stays inside it ---------
    {
      const page = await serve(t, b.dist, { viewport: { width: 1280, height: 800 } });
      await page.goto(ARTICLE, { waitUntil: 'load' });
      const r = await page.evaluate(() => {
        const w = document.querySelector('.clogem-module--page-t');
        const a = w.querySelector('a[href]');
        a.focus({ preventScroll: true });
        const ring = a.getBoundingClientRect();
        const box = w.getBoundingClientRect();
        return { focused: document.activeElement === a,
                 inside: ring.left - 4 >= box.left - 0.5 && ring.right + 4 <= box.right + 0.5
                         && ring.top - 4 >= box.top - 0.5 && ring.bottom + 4 <= box.bottom + 0.5,
                 ring: [ring.left, ring.right, ring.top, ring.bottom].map(Math.round),
                 box: [box.left, box.right, box.top, box.bottom].map(Math.round) };
      });
      t.check(r.focused, 'the module\'s link takes focus');
      t.check(r.inside, `its whole ring is inside the wrapper: ${r.ring} in ${r.box}`);
      await page.close();
    }

    // --- item 4: the windows meet neither back to top nor a page button ---
    for (const [width, height] of [[1280, 800], [1344, 740], [1440, 740], [1600, 740]]) {
      const page = await serve(t, b.dist, { viewport: { width, height } });
      await page.goto(ARTICLE, { waitUntil: 'load' });
      await page.evaluate(() => window.scrollTo({ top: 600, behavior: 'instant' }));
      await page.waitForTimeout(1200);                     // back to top appears on scroll
      const r = await page.evaluate(() => {
        const box = (s) => {
          const e = document.querySelector(s);
          if (!e || e.getClientRects().length === 0) return null;
          const r = e.getBoundingClientRect();
          return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, w: r.width, h: r.height };
        };
        return { lb: box('.clogem-window--lb'), rb: box('.clogem-window--rb'),
                 up: box('.clogem-back-to-top'),
                 buttons: [...document.querySelectorAll('.clogem-page-button')]
                   .filter((e) => e.getClientRects().length > 0)
                   .map((e) => { const r = e.getBoundingClientRect();
                                 return { left: r.left, right: r.right, top: r.top, bottom: r.bottom }; }) };
      });
      const overlap = (a, c) => a && c && a.left < c.right && c.left < a.right && a.top < c.bottom && c.top < a.bottom;
      t.check(r.lb && r.rb, `${width}×${height}: both windows are drawn`);
      t.check(r.up !== null, `${width}×${height}: back to top is drawn`);
      for (const [name, w] of [['lb', r.lb], ['rb', r.rb]]) {
        t.check(!overlap(w, r.up), `${width}×${height}: ${name} does not meet back to top`);
        t.check(w && w.w <= 200.5, `${width}×${height}: ${name} is at most 200 px wide (${w && Math.round(w.w)})`);
        for (const [i, pb] of r.buttons.entries()) {
          t.check(!overlap(w, pb), `${width}×${height}: ${name} does not meet page button ${i}`);
        }
      }
      if (width >= 1344) t.check(r.buttons.length === 2, `${width}: both page buttons are drawn`);
      await page.close();
    }

    // --- item 4: none at 960 px, both at 961 ------------------------------
    for (const [width, want] of [[960, false], [961, true]]) {
      const page = await serve(t, b.dist, { viewport: { width, height: 740 } });
      await page.goto(ARTICLE, { waitUntil: 'load' });
      const n = await page.evaluate(() => [...document.querySelectorAll('.clogem-window')]
        .map((e) => e.getClientRects().length));
      t.check(n.length === 2, `${width}: both windows are in the markup`);
      t.check(n.every((c) => (c > 0) === want), `${width}: the windows ${want ? 'have' : 'have no'} client rects (${n})`);
      await page.close();
    }

    // --- item 4: Enter on a close button reached by Tab -------------------
    {
      const page = await serve(t, b.dist, { viewport: { width: 1280, height: 800 } });
      await page.goto(ARTICLE, { waitUntil: 'load' });
      const reached = await page.evaluate(async () => {
        const c = document.querySelector('.clogem-window--lb .clogem-window__close');
        return { shown: !c.hidden, focusable: c.tabIndex >= 0 };
      });
      t.check(reached.shown, 'js/window.js reveals the close button');
      // Tab until the first window's close button has focus
      let on = false;
      for (let i = 0; i < 200 && !on; i++) {
        await page.keyboard.press('Tab');
        on = await page.evaluate(() => document.activeElement
          === document.querySelector('.clogem-window--lb .clogem-window__close'));
      }
      t.check(on, 'Tab reaches the lb window\'s close button');
      if (on) {
        await page.keyboard.press('Enter');
        const r = await page.evaluate(() => ({
          lb: document.querySelector('.clogem-window--lb').getClientRects().length,
          rb: document.querySelector('.clogem-window--rb').getClientRects().length,
          active: document.activeElement === document.body ? 'body' : document.activeElement.className,
        }));
        t.check(r.lb === 0, 'Enter hides that window');
        t.check(r.rb > 0, 'the other window stays');
        t.check(r.active === 'body', `focus ends on <body> (it is on ${r.active})`);
      }
      await page.close();
    }

    // --- item 4: without JS there is no close button, and both show -------
    {
      const page = await serve(t, b.dist, { viewport: { width: 1280, height: 800 }, javaScriptEnabled: false });
      await page.goto(ARTICLE, { waitUntil: 'load' });
      const r = await page.evaluate(() => ({
        windows: [...document.querySelectorAll('.clogem-window')].map((e) => e.getClientRects().length),
        buttons: [...document.querySelectorAll('.clogem-window__close')].map((e) => e.getClientRects().length),
      }));
      t.check(r.windows.length === 2 && r.windows.every((c) => c > 0), `without JS both windows show (${r.windows})`);
      t.check(r.buttons.length === 2 && r.buttons.every((c) => c === 0),
              `without JS no close button is drawn (${r.buttons})`);
      await page.close();
    }

    // --- items 2 and 4: print draws no module and no window --------------
    {
      const page = await serve(t, b.dist, { viewport: { width: 1280, height: 800 } });
      await page.goto(ARTICLE, { waitUntil: 'load' });
      await page.evaluate(() => window.scrollTo({ top: 600, behavior: 'instant' }));
      await page.waitForTimeout(1200);
      const shown = await page.evaluate(() => [...document.querySelectorAll('.clogem-module, .clogem-window')]
        .filter((e) => e.getClientRects().length > 0).length);
      t.check(shown > 0, `on screen ${shown} modules and windows are drawn`);
      await page.emulateMedia({ media: 'print' });
      const left = await page.evaluate(() => [...document.querySelectorAll('.clogem-module, .clogem-window')]
        .filter((e) => e.getClientRects().length > 0).map((e) => e.className));
      t.check(left.length === 0, `print draws none of them (${left.join(' ')})`);
      await page.emulateMedia({ media: null });
      await page.close();
    }
  } finally {
    await rm(b.site, { recursive: true, force: true });
  }
}
