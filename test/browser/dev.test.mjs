// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// `bb dev` in a real browser (Phase 4 Task D, DESIGN.md §11.3 item 12):
//
//   - an edit to an article shows in the open page within 3 s;
//   - breaking the file shows the build-error overlay within 3 s, naming it;
//   - fixing it clears the overlay (the next good build reloads the page).
//
// Unlike the other tests this one does not use the served `dist/`: it copies
// examples/demo-site to a temp directory (the checked-in tree is never
// edited), starts `bb dev --no-search` there on its own port, and drives
// that. When `bb` is not on PATH, or `bb dev` is still starting after 90 s,
// it says so and skips rather than failing. A `bb dev` that EXITS before it
// is ready — a crash at startup — is a failure that prints its output, and
// so is anything after a successful start (dev-harness.mjs).

import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { findSource, startDev, waitForLine, within } from './dev-harness.mjs';

const permalink = '/pages/643259/';   // an English article with a sidebar and TOC

export default async function (t) {
  const dev = await startDev(t, ['--no-search']);
  try {
    if (!dev.ready) return;
    const { site, port, lines, child } = dev;
    const source = await findSource(path.join(site, 'content'), permalink);
    t.check(source, `no source file with permalink ${permalink}`);
    if (!source) return;
    const original = await readFile(source, 'utf8');

    const base = `http://127.0.0.1:${port}`;
    const ctx = await t.browser.newContext();
    try {
      await ctx.route('**/*', (route) =>
        new URL(route.request().url()).origin === base ? route.continue() : route.abort());
      const page = await ctx.newPage();
      await page.goto(base + permalink, { waitUntil: 'load' });

      // 1. an edit appears
      const marker = `dev-marker-${Date.now()}`;
      const from = lines.length;
      await writeFile(source, `${original.trimEnd()}\n\n${marker}\n`);
      const shown = await within(page, (m) => document.body && document.body.innerText.includes(m), marker, 3000);
      t.check(shown !== null, 'the edit did not appear within 3 s');
      const rebuilt = await waitForLine(lines, (l) => /rebuilt in \d+ ms/.test(l), 3000, child, from);
      if (shown !== null) {
        console.log(`  edit → visible in ${shown} ms` +
                    (typeof rebuilt === 'string' ? ` (${rebuilt.trim().replace(/^clogem-press: /, '')})` : ''));
      }

      // 2. a broken file shows the overlay, naming it
      await writeFile(source, original.replace(/^title:.*$/m, 'title: [unclosed'));
      const broke = await within(page, () => !!document.getElementById('clogem-dev-overlay'), null, 3000);
      t.check(broke !== null, 'no build-error overlay within 3 s of breaking the file');
      if (broke !== null) {
        console.log(`  break → overlay in ${broke} ms`);
        const text = await page.evaluate(() => document.getElementById('clogem-dev-overlay').innerText);
        t.check(text.includes(path.basename(source)), `the overlay does not name ${path.basename(source)}: ${text.slice(0, 200)}`);
        const box = await page.evaluate(() => {
          const r = document.getElementById('clogem-dev-overlay').getBoundingClientRect();
          return { w: r.width, h: r.height, vw: innerWidth, vh: innerHeight };
        });
        t.check(box.w >= box.vw - 1 && box.h >= box.vh - 1, `the overlay does not cover the viewport: ${JSON.stringify(box)}`);
        t.check(await page.evaluate(() => typeof window.clogem === 'object' && typeof window.clogem.getMode === 'function'),
                'window.clogem was overwritten');
      }

      // 3. the fix clears it
      await writeFile(source, `${original.trimEnd()}\n\n${marker}-fixed\n`);
      const fixed = await within(page, (m) => !document.getElementById('clogem-dev-overlay')
                                    && document.body.innerText.includes(m), `${marker}-fixed`, 3000);
      t.check(fixed !== null, 'the overlay was not cleared within 3 s of the fix');
      if (fixed !== null) console.log(`  fix → page back in ${fixed} ms`);
    } finally {
      await ctx.close();
    }
  } finally {
    await dev.stop();
  }
}
