// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// The support page's header (support/support_page.clj) in a real browser.
// Like analytics.test.mjs it does not use the served `dist/`: it builds the
// five pages with `bb support-page --out <temp>` and serves them through
// `page.route` at https://support.test/clogem-press/. In every language,
// light and dark:
//   - the name (the <h1>, "clogem-press") is one line from 480 px up — it
//     broke after its hyphen at 768–1440 (SP.5 item 1);
//   - at 1280 px the switchers sit beside the brand, on its row;
//   - nothing scrolls sideways at 320–1440 px;
//   - the open language list overlaps its own summary and stays inside the
//     viewport — it hung under the theme switcher (SP.5 item 2);
//   - the page logs no CSP message.
//
// When `bb` is not on PATH it says so and skips; CI's browser job has bb.

import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const ORIGIN = 'https://support.test';
const LANGS = ['en', 'zh-Hans', 'zh-Hant', 'pt-BR', 'es-ES'];
const WIDTHS = [320, 360, 480, 768, 1024, 1280, 1440];

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

export default async function (t) {
  const out = await mkdtemp(path.join(os.tmpdir(), 'clogem-support-'));
  try {
    const r = await run('bb', ['support-page', '--out', out], { cwd: repo });
    if (r.missing) { console.log('  skip: bb is not on PATH'); return; }
    t.check(r.code === 0, `bb support-page exited ${r.code}: ${r.out}`);
    if (r.code !== 0) return;

    for (const scheme of ['light', 'dark']) {
      for (const width of WIDTHS) {
        const page = await t.newPage({ viewport: { width, height: 800 }, colorScheme: scheme });
        const csp = [];
        page.on('console', (m) => { if (/Content.Security.Policy|CSP/i.test(m.text())) csp.push(m.text()); });
        await page.route(`${ORIGIN}/**`, async (route) => {
          const p = new URL(route.request().url()).pathname.replace(/^\/clogem-press\//, '');
          const file = path.join(out, 'clogem-press', p.endsWith('/') || p === '' ? `${p}index.html` : p);
          try {
            route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: await readFile(file) });
          } catch { route.fulfill({ status: 404, body: '' }); }
        });
        for (const lang of LANGS) {
          const at = `${lang} ${width} ${scheme}`;
          await page.goto(`${ORIGIN}/clogem-press/${lang === 'en' ? '' : `${lang}/`}`);
          const m = await page.evaluate(() => {
            const h1 = document.querySelector('h1');
            const range = document.createRange();
            range.selectNodeContents(h1);
            const lines = new Set([...range.getClientRects()].filter((r) => r.width > 0).map((r) => Math.round(r.top)));
            const box = (el) => { const r = el.getBoundingClientRect(); return { l: r.left, r: r.right, t: r.top, b: r.bottom }; };
            return {
              text: h1.textContent, lines: lines.size,
              scroll: document.documentElement.scrollWidth, inner: window.innerWidth,
              brand: box(document.querySelector('.brand')), tools: box(document.querySelector('.tools')),
            };
          });
          t.check(m.text === 'clogem-press', `${at}: the h1 reads ${JSON.stringify(m.text)}`);
          if (width >= 480) t.check(m.lines === 1, `${at}: the name takes ${m.lines} lines`);
          t.check(m.scroll <= m.inner, `${at}: scrollWidth ${m.scroll} > ${m.inner}`);
          if (width === 1280) {
            t.check(m.tools.l >= m.brand.r && m.tools.t < m.brand.b,
                    `${at}: the switchers are not beside the brand (brand ${JSON.stringify(m.brand)}, tools ${JSON.stringify(m.tools)})`);
          }

          await page.click('.langs summary');
          const d = await page.evaluate(() => {
            const s = document.querySelector('.langs summary').getBoundingClientRect();
            const u = document.querySelector('.langs ul').getBoundingClientRect();
            return { s: [s.left, s.right], u: [u.left, u.right, u.top], sb: s.bottom,
                     scroll: document.documentElement.scrollWidth, inner: window.innerWidth };
          });
          t.check(d.u[0] < d.s[1] && d.u[1] > d.s[0],
                  `${at}: the open list (x ${d.u[0]}–${d.u[1]}) is not under its summary (x ${d.s[0]}–${d.s[1]})`);
          t.check(d.u[2] >= d.sb, `${at}: the open list starts above its summary's bottom`);
          t.check(d.u[0] >= 0 && d.u[1] <= d.inner, `${at}: the open list (x ${d.u[0]}–${d.u[1]}) leaves the viewport`);
          t.check(d.scroll <= d.inner, `${at}: with the list open, scrollWidth ${d.scroll} > ${d.inner}`);
          t.check(csp.length === 0, `${at}: CSP: ${csp.join(' | ')}`);
          csp.length = 0;
        }
      }
    }
  } finally {
    await rm(out, { recursive: true, force: true });
  }
}
