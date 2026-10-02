// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// Browser test runner (Phase 4, D-P4-18). Runs every `*.test.mjs` beside
// this file against a built site that is already being served, in one
// headless Chromium. Nothing here ships, and `bb test` never loads it.
//
//   node run.mjs [substring …]     only the test files whose name contains one
//
// Environment:
//   CLOGEM_BROWSER_BASE_URL  where the site is served   (http://127.0.0.1:8000/)
//   CLOGEM_BROWSER_DIST      the directory being served (../../examples/demo-site/dist)
//   CLOGEM_CHROMIUM          a Chromium executable to launch instead of the one
//                            `npx playwright install chromium` downloaded — for a
//                            machine whose preinstalled browser is another
//                            revision (README.md)
//
// A test file default-exports `async function (t)`; `t` carries the browser,
// the base URL, the list of HTML pages, `t.url(page)`, `t.newPage(opts)`
// (third-party requests are aborted, so a test never depends on the
// network) and `t.check(ok, message)`, which records a failure without
// stopping the test. A test that throws fails too.

import { chromium } from 'playwright';
import { readdir, stat } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const baseURL = new URL(process.env.CLOGEM_BROWSER_BASE_URL || 'http://127.0.0.1:8000/');
const dist = path.resolve(process.env.CLOGEM_BROWSER_DIST
  || path.join(here, '..', '..', 'examples', 'demo-site', 'dist'));

async function htmlFiles(dir, rel = '') {
  const out = [];
  for (const e of (await readdir(dir, { withFileTypes: true })).sort((a, b) => a.name < b.name ? -1 : 1)) {
    const r = rel ? `${rel}/${e.name}` : e.name;
    if (e.isDirectory()) out.push(...await htmlFiles(path.join(dir, e.name), r));
    else if (e.isFile() && e.name.endsWith('.html')) out.push(r);
  }
  return out;
}

// `zh-Hans/pages/x/index.html` → the URL a reader visits, each segment
// percent-encoded (the CJK and Tamil index slugs are kept verbatim on disk)
function pageURL(rel) {
  const p = rel.replace(/(^|\/)index\.html$/, '$1');
  return new URL(p.split('/').map(encodeURIComponent).join('/'), baseURL).href;
}

async function main() {
  const filters = process.argv.slice(2);
  const files = (await readdir(here))
    .filter((f) => f.endsWith('.test.mjs'))
    .filter((f) => filters.length === 0 || filters.some((s) => f.includes(s)))
    .sort();
  if (files.length === 0) {
    console.error('browser tests: no *.test.mjs matched');
    process.exit(1);
  }
  if (!(await stat(dist).catch(() => null))?.isDirectory()) {
    console.error(`browser tests: ${dist} does not exist; build the site first`);
    process.exit(1);
  }
  const pages = await htmlFiles(dist);
  const browser = await chromium.launch(
    process.env.CLOGEM_CHROMIUM ? { executablePath: process.env.CLOGEM_CHROMIUM } : {});
  console.log(`browser tests: Chromium ${browser.version()}, ${pages.length} pages under ${dist}, served at ${baseURL.href}`);

  let failed = 0;
  try {
    for (const f of files) {
      const failures = [];
      let checks = 0;
      const contexts = [];
      const t = {
        browser, baseURL: baseURL.href, dist, pages,
        url: pageURL,
        check(ok, message) { checks++; if (!ok) failures.push(message); },
        async newPage(opts = {}) {
          const ctx = await browser.newContext(opts);
          contexts.push(ctx);
          await ctx.route('**/*', (route) =>
            new URL(route.request().url()).origin === baseURL.origin ? route.continue() : route.abort());
          return ctx.newPage();
        },
      };
      const t0 = Date.now();
      try {
        const mod = await import(pathToFileURL(path.join(here, f)).href);
        await mod.default(t);
      } catch (e) {
        failures.push(`threw: ${e && e.stack || e}`);
      } finally {
        for (const c of contexts) await c.close();
      }
      const secs = ((Date.now() - t0) / 1000).toFixed(1);
      if (failures.length) {
        failed++;
        console.log(`FAIL ${f} (${checks} checks, ${failures.length} failed, ${secs} s)`);
        for (const m of failures.slice(0, 50)) console.log(`  - ${m}`);
        if (failures.length > 50) console.log(`  … and ${failures.length - 50} more`);
      } else {
        console.log(`ok   ${f} (${checks} checks, ${secs} s)`);
      }
    }
  } finally {
    await browser.close();
  }
  console.log(failed ? `browser tests: ${failed} of ${files.length} failed` : `browser tests: all ${files.length} passed`);
  process.exit(failed ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
