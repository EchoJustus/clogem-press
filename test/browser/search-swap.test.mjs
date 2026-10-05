// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// Search across `bb dev`'s background index swap (P4-D.1 item 1, DESIGN.md
// §5.4). dev sends the reload first and indexes after, so a page that reloads
// and initialises search before the new bundle is swapped in holds the OLD
// `pagefind-entry.json` and `.pf_meta`, whose index chunks and fragments are
// content-hashed. The swap used to delete that bundle: the page's next search
// 404'd on them ("invalid gzip data", or 0 results) until a manual reload.
// dev now keeps the previous bundle until the next swap and serves a
// `pagefind/` file the live one lacks from it.
//
// Real Pagefind (CLOGEM_PAGEFIND, as on CI): edit a page, initialise search
// in the page dev's reload just loaded — before the swap — then search again
// after the swap. No request under /pagefind/ may fail. Same skip and
// failure rules as dev.test.mjs (dev-harness.mjs).

import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { findSource, startDev, waitForLine, within } from './dev-harness.mjs';

const permalink = '/pages/643259/';
// terms whose index chunks the init does not load: they are fetched after
// the swap, with the hashes the old entry names
const terms = ['permalink', 'front', 'language', 'directory', 'article', 'conventions'];

export default async function (t) {
  const dev = await startDev(t, []);
  try {
    if (!dev.ready) return;
    const { site, port, lines, child } = dev;
    const indexed = (l) => /clogem-press: search index updated in \d+ ms/.test(l);
    const first = await waitForLine(lines, (l) => indexed(l) || /search index failed/.test(l), 60000, child);
    if (typeof first !== 'string' || !indexed(first)) {
      t.check(false, `the first background index did not finish:\n${lines.slice(-15).map((l) => `    | ${l}`).join('\n')}`);
      return;
    }
    const source = await findSource(path.join(site, 'content'), permalink);
    t.check(source, `no source file with permalink ${permalink}`);
    if (!source) return;
    const original = await readFile(source, 'utf8');

    const base = `http://127.0.0.1:${port}`;
    const ctx = await t.browser.newContext();
    const failed = [];
    try {
      await ctx.route('**/*', (route) =>
        new URL(route.request().url()).origin === base ? route.continue() : route.abort());
      const page = await ctx.newPage();
      page.on('response', (r) => {
        if (new URL(r.url()).pathname.startsWith('/pagefind/') && r.status() >= 400) failed.push(`${r.status()} ${r.url()}`);
      });
      page.on('requestfailed', (r) => {
        if (new URL(r.url()).pathname.startsWith('/pagefind/')) failed.push(`failed ${r.url()}`);
      });
      await page.goto(base + permalink, { waitUntil: 'load' });

      // The window between the reload and the swap is the index run (~0.4 s
      // on the demo). Try up to three edits to initialise inside it.
      let won = false;
      let marker = '';
      for (let attempt = 1; attempt <= 3 && !won; attempt++) {
        marker = `swapmarker${Date.now()}`;
        const from = lines.length;
        await writeFile(source, `${original.trimEnd()}\n\n${marker} zebra quokka\n`);
        const shown = await within(page, (m) => document.body && document.body.innerText.includes(m), marker, 5000);
        t.check(shown !== null, `attempt ${attempt}: the edit did not reload the page within 5 s`);
        if (shown === null) return;
        await page.evaluate(async () => {
          window.__pf = await import('/pagefind/pagefind.js');
          await window.__pf.init();
        });
        won = !lines.slice(from).some(indexed);
        console.log(`  attempt ${attempt}: search initialised ${won ? 'before' : 'after'} the swap`);
        const swapped = await waitForLine(lines, indexed, 30000, child, from);
        t.check(typeof swapped === 'string', `attempt ${attempt}: the background index did not finish`);
        if (typeof swapped !== 'string') return;
      }
      if (!won) {
        console.log('  skip: could not initialise search before the swap in 3 attempts (a fast machine)');
        return;
      }

      // after the swap: searches with the old bundle's hashes
      const results = await page.evaluate(async (qs) => {
        const out = [];
        for (const q of qs) {
          try {
            const r = await window.__pf.search(q);
            const data = await Promise.all(r.results.slice(0, 5).map((x) => x.data()));
            out.push({ q, n: r.results.length, data: data.length });
          } catch (e) {
            out.push({ q, error: String(e) });
          }
        }
        return out;
      }, terms);
      for (const r of results) {
        t.check(!r.error, `search "${r.q}" after the swap threw: ${r.error}`);
        t.check(r.n > 0 && r.data > 0, `search "${r.q}" after the swap found nothing: ${JSON.stringify(r)}`);
      }
      t.check(failed.length === 0, `requests under /pagefind/ failed during or after the swap:\n    ${failed.join('\n    ')}`);
      console.log(`  after the swap: ${results.map((r) => `${r.q}=${r.n ?? 'error'}`).join(', ')}; ${failed.length} failed requests`);

      // and a reload sees the new index
      await page.reload({ waitUntil: 'load' });
      const fresh = await page.evaluate(async (m) => {
        const pf = await import('/pagefind/pagefind.js');
        await pf.init();
        return (await pf.search(m)).results.length;
      }, marker);
      t.check(fresh > 0, `after a reload, the new index does not find ${marker}`);
      t.check(failed.length === 0, `requests under /pagefind/ failed after the reload:\n    ${failed.join('\n    ')}`);
    } finally {
      await ctx.close();
    }
  } finally {
    await dev.stop();
  }
}
