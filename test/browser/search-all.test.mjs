// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// Cross-language search (Phase 4 Task G, DESIGN.md §11.3 item 17,
// D-P4-17 / D-P4-18) on the served demo, which turns the feature on.
//
// What it holds to: the checkbox is off for a new reader and the page's
// own index is the only one fetched; ticking it finds a page in another
// language, remembers the choice and survives a reload; the merged
// results are weighted far below the reader's own language; Malay and
// Traditional Chinese keep their own summary strings in both states and
// grow no second dialog; the language filter narrows to one language; the
// row and its filter menu fit a 320 px screen; the box is reachable and
// usable from the keyboard; blocked storage leaves it working, off; no
// request under `pagefind/` fails and no result href is absolute; print
// hides the row; a merged index that fails to load (a 404, or aborted)
// falls back to the page's own language, unticked for that page with the
// stored choice kept; and a dialog opened and typed in before this script
// runs keeps its term, results and focus. One more part builds a temp copy under
// `--base /clogem-demo/` and serves it with `bb serve`, to show the merge
// URLs carry the base; it skips, saying so, without `bb` or
// CLOGEM_PAGEFIND.

import { spawn } from 'node:child_process';
import { cp, mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');

const TERM_CJK = '百分号';          // zh-Hans only: 0 hits from an English page
const SETTLE = 2000;                 // the search debounce plus a merge load

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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const box = (page) => page.locator('.clogem-search-all input[type=checkbox]');
const field = (page) => page.locator('pagefind-modal input').first();
const summary = (page) => page.locator('pagefind-summary').first();
const hrefs = (page) =>
  page.locator('pagefind-results a').evaluateAll((as) => as.map((a) => a.getAttribute('href')));

// Open `url` with the search dialog showing. `page` may be given, for a
// context of this test's own (the `bb serve` part).
async function openSearch(page, url) {
  await page.goto(url);
  await page.click('pagefind-modal-trigger button');
  await page.waitForSelector('pagefind-modal input');
  return page;
}

async function search(page, term) {
  await field(page).fill(term);
  await sleep(SETTLE);
}

export default async function (t) {
  const failed = [];     // requests under pagefind/ that did not complete
  const absolute = [];   // result hrefs that left the site
  async function page(opts = {}) {
    const p = await t.newPage(opts);
    p.on('requestfailed', (r) => { if (r.url().includes('/pagefind/')) failed.push(r.url()); });
    return p;
  }

  // ---------------------------------------------------------------- 1
  // The English home: off by default, and only its own index is fetched
  {
    const p = await page();
    const metas = [];
    p.on('request', (r) => { if (r.url().includes('.pf_meta')) metas.push(r.url()); });
    await openSearch(p, t.baseURL);
    t.check(await box(p).count() === 1, 'the English home has one "Search all languages" checkbox');
    t.check(!(await box(p).isChecked()), 'a new reader finds it unticked');
    await search(p, TERM_CJK);
    t.check(/\b0\b|No results/i.test(await summary(p).innerText()),
            `unticked, ${TERM_CJK} finds nothing: ${(await summary(p).innerText()).trim()}`);
    t.check(metas.length > 0 && metas.every((u) => /pagefind\.en_/.test(u)),
            `unticked, only the English index is fetched: ${metas.map((u) => u.split('/').pop()).join(', ')}`);

    await box(p).check();
    await sleep(SETTLE);
    t.check((await field(p).inputValue()) === TERM_CJK, 'the typed term carries over to the merged input');
    const found = await hrefs(p);
    t.check(found.includes('/pages/cjk001/'), `ticked, it finds the zh-Hans page: ${found.join(', ')}`);
    absolute.push(...found.filter((h) => /^https?:/.test(h)));
    t.check(metas.some((u) => /pagefind\.zh-hans_/.test(u)), 'and fetches the zh-Hans index');
    t.check((await p.evaluate(() => localStorage.getItem('clogem-search-all'))) === '1',
            'the choice is stored as "1"');
    t.check((await p.evaluate(() => document.activeElement && document.activeElement.type)) === 'checkbox',
            'focus stays on the checkbox');
    t.check(await p.locator('dialog dialog').count() === 0, 'no second dialog');

    await openSearch(p, t.baseURL);
    t.check(await box(p).isChecked(), 'a reload loads it ticked');
    await search(p, TERM_CJK);
    t.check((await hrefs(p)).includes('/pages/cjk001/'), 'and still finds the zh-Hans page');
    await box(p).uncheck();
    await sleep(SETTLE);
    t.check((await p.evaluate(() => localStorage.getItem('clogem-search-all'))) === null,
            'unticking removes the key');
    t.check(/\b0\b|No results/i.test(await summary(p).innerText()),
            'and the search is the page\'s own language again');
  }

  // ---------------------------------------------------------------- 2
  // The reader's own language leads: indexWeight 0.001 on every merge
  {
    const p = await page();
    await openSearch(p, `${t.baseURL}zh-Hans/`);
    await box(p).check();
    await search(p, 'permalink');
    const found = await hrefs(p);
    absolute.push(...found.filter((h) => /^https?:/.test(h)));
    t.check(found[0] === '/zh-Hans/pages/relocated/',
            `the zh-Hans result leads, not the English one: ${found.slice(0, 3).join(', ')}`);
  }

  // ---------------------------------------------------------------- 3
  // Malay and Traditional Chinese keep their own strings, in both states
  {
    for (const [lang, term, re] of [['ms', 'penjaja', /hasil untuk/], ['zh-Hant', '中文分类', /找到.*的相關結果/]]) {
      const p = await page();
      await openSearch(p, `${t.baseURL}${lang}/`);
      await search(p, term);
      const off = (await summary(p).innerText()).trim();
      t.check(re.test(off), `${lang}, unticked: ${off}`);
      t.check(await p.locator('dialog dialog').count() === 0, `${lang}, unticked: no nested dialog`);
      await box(p).check();
      await sleep(SETTLE);
      const on = (await summary(p).innerText()).trim();
      t.check(re.test(on), `${lang}, ticked: ${on}`);
      t.check(await p.locator('dialog dialog').count() === 0, `${lang}, ticked: no nested dialog`);
      if (lang === 'zh-Hant') {
        const found = await hrefs(p);
        absolute.push(...found.filter((h) => /^https?:/.test(h)));
        t.check(found[0] === '/zh-Hant/pages/e19a7c/', `zh-Hant's own page leads: ${found.slice(0, 2).join(', ')}`);
      }
    }
  }

  // ---------------------------------------------------------------- 4
  // The language filter. No Escape here: it closes the whole modal.
  {
    const p = await page();
    await openSearch(p, `${t.baseURL}ms/`);
    await box(p).check();
    await search(p, 'pagefind');
    const dropdown = p.locator('.clogem-search-all pagefind-filter-dropdown');
    t.check(await dropdown.count() === 1, 'ticked, the row holds the language filter');
    await dropdown.locator('button').first().click();
    const menu = p.locator('.pf-dropdown-menu');
    const options = (await menu.innerText()).split('\n').map((s) => s.trim()).filter(Boolean);
    t.check(options.includes('English') && options.includes('Bahasa Melayu'),
            `the values are the language names: ${options.join(' / ')}`);
    await menu.getByText('Bahasa Melayu', { exact: true }).first().click();
    await sleep(SETTLE);
    const found = await hrefs(p);
    absolute.push(...found.filter((h) => /^https?:/.test(h)));
    t.check(found.length === 1 && found[0] === '/pages/1bd8ea/',
            `filtering to Malay leaves its own page: ${found.join(', ')}`);
  }

  // ---------------------------------------------------------------- 5
  // 320 and 360 px: the row, and the open filter menu, fit
  {
    for (const width of [320, 360]) {
      const p = await page({ viewport: { width, height: 720 } });
      await openSearch(p, t.baseURL);
      await box(p).check();
      await search(p, 'pagefind');
      await p.locator('.clogem-search-all pagefind-filter-dropdown button').first().click();
      await sleep(300);
      const over = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      t.check(over <= 0, `${width}px: no sideways scroll (scrollWidth - innerWidth = ${over})`);
      const rect = await p.locator('.pf-dropdown-menu').boundingBox();
      t.check(rect && rect.x >= 0 && rect.x + rect.width <= width,
              `${width}px: the filter menu is inside the viewport (${rect && Math.round(rect.x)}…${rect && Math.round(rect.x + rect.width)})`);
      t.check(await p.locator('.pf-modal-close').count() === 1,
              `${width}px: exactly one close button (a modal built in JS draws two)`);
    }
  }

  // ---------------------------------------------------------------- 6
  // Keyboard: Tab reaches the box, Space toggles it, focus stays on it
  {
    const p = await page();
    await openSearch(p, t.baseURL);
    let reached = false;
    for (let i = 0; i < 12 && !reached; i++) {
      await p.keyboard.press('Tab');
      reached = await p.evaluate(() => !!(document.activeElement
        && document.activeElement.closest && document.activeElement.closest('.clogem-search-all')));
    }
    t.check(reached, 'Tab reaches the checkbox inside the dialog');
    if (reached) {
      await p.keyboard.press('Space');
      await sleep(SETTLE);
      t.check(await box(p).isChecked(), 'Space ticks it');
      t.check((await p.evaluate(() => document.activeElement && document.activeElement.type)) === 'checkbox',
              'and focus is still on it');
      await p.keyboard.press('Space');
      await sleep(500);
      t.check(!(await box(p).isChecked()), 'Space again unticks it');
    }
  }

  // ---------------------------------------------------------------- 7
  // Blocked storage: off, and working
  {
    const p = await page();
    await p.addInitScript(() => {
      const bad = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); },
                    removeItem() { throw new Error('blocked'); } };
      try { Object.defineProperty(window, 'localStorage', { get: () => bad }); } catch (e) { /* ignore */ }
    });
    await openSearch(p, t.baseURL);
    t.check(await box(p).count() === 1, 'blocked storage: the row is still drawn');
    t.check(!(await box(p).isChecked()), 'blocked storage: off');
    await box(p).check();
    await search(p, TERM_CJK);
    const found = await hrefs(p);
    absolute.push(...found.filter((h) => /^https?:/.test(h)));
    t.check(found.includes('/pages/cjk001/'), 'blocked storage: ticking it still searches every language');
  }

  // ---------------------------------------------------------------- 8
  // Print hides the row with the rest of the search chrome
  {
    const p = await page();
    await openSearch(p, t.baseURL);
    await box(p).check();
    await sleep(500);
    await p.emulateMedia({ media: 'print' });
    const rects = await p.locator('.clogem-search-all').evaluate((el) => el.getClientRects().length);
    t.check(rects === 0, `print draws no checkbox row (${rects} client rects)`);
    await p.emulateMedia({ media: 'screen' });
  }

  // ---------------------------------------------------------------- 9
  // Under a non-root base: a temp build served by `bb serve`
  {
    const site = await mkdtemp(path.join(os.tmpdir(), 'clogem-search-all-'));
    let server = null;
    try {
      if (!process.env.CLOGEM_PAGEFIND) {
        console.log('  skip: the base part needs CLOGEM_PAGEFIND (a real Pagefind index)');
      } else {
        await cp(path.join(repo, 'examples', 'demo-site'), site, {
          recursive: true, filter: (src) => !src.split(path.sep).includes('dist'),
        });
        const built = await run('bb', ['--config', path.join(repo, 'bb.edn'), 'build',
                                       '--base', '/clogem-demo/', '--no-highlight', '--no-write'],
                                { cwd: site });
        if (built.missing) {
          console.log('  skip: the base part needs bb on PATH');
        } else if (built.code !== 0) {
          t.check(false, `bb build --base failed (${built.code}):\n${built.out}`);
        } else {
          // the port, as dev-harness.mjs picks one
          const port = 18900 + Math.floor(Math.random() * 90);
          server = spawn('bb', ['--config', path.join(repo, 'bb.edn'), 'serve',
                                '--dir', path.join(site, 'dist'), '--base', '/clogem-demo/',
                                '--port', String(port)],
                         { cwd: site, stdio: ['ignore', 'pipe', 'pipe'] });
          const origin = `http://127.0.0.1:${port}`;
          let up = false;
          for (let i = 0; i < 100 && !up; i++) {
            await sleep(100);
            up = await fetch(`${origin}/clogem-demo/`).then((r) => r.ok).catch(() => false);
          }
          if (!up) {
            t.check(false, 'bb serve did not come up in 10 s');
          } else {
            // a context of its own: t.newPage aborts every origin but the demo's
            const ctx = await t.browser.newContext();
            try {
              const p = await ctx.newPage();
              await openSearch(p, `${origin}/clogem-demo/`);
              await box(p).check();
              await search(p, TERM_CJK);
              const found = await hrefs(p);
              t.check(found.includes('/clogem-demo/pages/cjk001/'),
                      `under /clogem-demo/ the merged href keeps the base: ${found.join(', ')}`);
              t.check(!found.some((h) => /^https?:/.test(h)),
                      'and is still site-relative (baseUrl, not the absolute merge path)');
            } finally { await ctx.close(); }
          }
        }
      }
    } finally {
      if (server && server.exitCode === null) {
        server.kill('SIGTERM');
        await new Promise((r) => { server.on('exit', r); setTimeout(r, 5000); });
      }
      await rm(site, { recursive: true, force: true });
    }
  }

  // --------------------------------------------------------------- 10
  // A merged index that fails to load (G.1 item 1): Pagefind 1.5.2 awaits
  // each merge in turn, so one bad pf_meta failed the whole ticked search.
  // The page falls back to its own language for this load: the search
  // finds the reader's own page, the box ends unticked, the term and focus
  // stay in the input, and the stored choice is kept for later pages.
  // t.newPage, not page(): these requests fail on purpose.
  {
    const cases = [
      ['one foreign pf_meta answers 404', /\/pagefind\/pagefind\.zh-hans_[^/]*\.pf_meta/, (r) => r.fulfill({ status: 404, body: 'gone' })],
      ['every foreign pf_meta is aborted', /\/pagefind\/pagefind\.(zh-hans|zh-hant|ms|ta)_[^/]*\.pf_meta/, (r) => r.abort()],
    ];
    for (const [label, re, answer] of cases) {
      const p = await t.newPage();
      await p.addInitScript(() => { try { localStorage.setItem('clogem-search-all', '1'); } catch (e) { /* ignore */ } });
      let hit = 0;
      await p.route(re, (r) => { hit++; return answer(r); });
      await openSearch(p, t.baseURL);
      t.check(await box(p).isChecked(), `${label}: with "1" stored it opens ticked`);
      await field(p).fill('pagefind');
      await p.waitForFunction(() => document.querySelectorAll('pagefind-results a').length > 0, null, { timeout: 10000 })
        .catch(() => {});
      await sleep(500);
      const found = await hrefs(p);
      t.check(hit > 0, `${label}: the failing request was made (${hit})`);
      t.check(found.includes('/pages/e19a7c/'),
              `${label}: the search still finds the reader's own page: ${(await summary(p).innerText()).trim()} ${found.slice(0, 3).join(', ')}`);
      t.check(!(await box(p).isChecked()), `${label}: the box ends unticked for this page`);
      t.check((await p.evaluate(() => localStorage.getItem('clogem-search-all'))) === '1',
              `${label}: the stored choice is kept`);
      t.check((await field(p).inputValue()) === 'pagefind', `${label}: the typed term is kept`);
      t.check(await p.evaluate(() => { const a = document.activeElement;
        return !!(a && a.tagName === 'INPUT' && a.type !== 'checkbox' && a.closest('pagefind-modal')); }),
              `${label}: focus stays in the search input`);
    }
  }

  // --------------------------------------------------------------- 11
  // The dialog opened, and a term typed, before js/search-all.js runs, with
  // "1" stored (G.1 item 2): the term and the results survive the swap,
  // and focus stays in the dialog, on the input.
  {
    const p = await page();
    await p.addInitScript(() => { try { localStorage.setItem('clogem-search-all', '1'); } catch (e) { /* ignore */ } });
    let release;
    const gate = new Promise((r) => { release = r; });
    await p.route(/\/js\/search-all\.js/, async (r) => { await gate; await r.continue(); });
    // deferred scripts hold DOMContentLoaded back, so wait for the commit only
    await p.goto(t.baseURL, { waitUntil: 'commit' });
    await p.waitForFunction(() => window.PagefindComponents && customElements.get('pagefind-modal'), null, { timeout: 15000 });
    await p.click('pagefind-modal-trigger button');
    await p.waitForSelector('pagefind-modal input');
    await search(p, 'pagefind');
    t.check(await p.locator('.clogem-search-all').count() === 0, 'held back: the row is not drawn yet');
    t.check((await hrefs(p)).includes('/pages/e19a7c/'), 'held back: the default search has found the page');
    release();
    await p.waitForSelector('.clogem-search-all', { timeout: 10000 });
    await sleep(SETTLE);
    t.check(await box(p).isChecked(), 'released: the stored choice ticks the box');
    t.check((await field(p).inputValue()) === 'pagefind', 'released: the typed term carries over');
    const found = await hrefs(p);
    absolute.push(...found.filter((h) => /^https?:/.test(h)));
    t.check(found.includes('/pages/e19a7c/'), `released: the results survive: ${found.slice(0, 3).join(', ')}`);
    t.check(await p.evaluate(() => { const a = document.activeElement;
      return !!(a && a.tagName === 'INPUT' && a.type !== 'checkbox' && a.closest('pagefind-modal')); }),
            `released: focus is in the dialog, on the input (${await p.evaluate(() => document.activeElement && document.activeElement.tagName)})`);
    t.check(await p.evaluate(() => !!document.querySelector('pagefind-modal dialog[open]')), 'released: the dialog is still open');
  }

  // --------------------------------------------------------------- 12
  t.check(failed.length === 0, `no request under pagefind/ failed: ${failed.slice(0, 3).join(', ')}`);
  t.check(absolute.length === 0, `no result href left the site: ${absolute.slice(0, 3).join(', ')}`);
}
