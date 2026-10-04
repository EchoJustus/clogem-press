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
// that. When `bb dev` cannot start — no `bb` on PATH, or the server does not
// come up within 90 s — it says so and skips rather than failing; anything
// after a successful start is a real failure.

import { spawn } from 'node:child_process';
import { cp, mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const permalink = '/pages/643259/';   // an English article with a sidebar and TOC

async function findSource(dir, link) {
  for (const e of await readdir(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      const hit = await findSource(p, link);
      if (hit) return hit;
    } else if (e.name.endsWith('.md')) {
      const text = await readFile(p, 'utf8');
      if (new RegExp(`^permalink:\\s*${link.replace(/\//g, '\\/')}\\s*$`, 'm').test(text)) return p;
    }
  }
  return null;
}

// Resolve once `pred(line)` holds for a line of the child's output, or with
// null after `ms`.
function waitForLine(lines, pred, ms) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    const tick = () => {
      const hit = lines.find(pred);
      if (hit) return resolve(hit);
      if (Date.now() - t0 > ms) return resolve(null);
      setTimeout(tick, 50);
    };
    tick();
  });
}

// Poll `fn` in the page (surviving the reloads in between) until it returns
// a truthy value; returns the elapsed ms, or null after `ms`.
async function within(page, fn, arg, ms) {
  const t0 = Date.now();
  while (Date.now() - t0 <= ms) {
    try {
      if (await page.evaluate(fn, arg)) return Date.now() - t0;
    } catch (e) { /* the page is reloading */ }
    await new Promise((r) => setTimeout(r, 25));
  }
  return null;
}

export default async function (t) {
  const site = await mkdtemp(path.join(os.tmpdir(), 'clogem-dev-browser-'));
  const port = 18900 + Math.floor(Math.random() * 90);
  const lines = [];
  let child = null;
  try {
    await cp(path.join(repo, 'examples', 'demo-site'), site, {
      recursive: true,
      filter: (src) => !/[\\/]dist(-[^\\/]*)?([\\/]|$)/.test(path.relative(repo, src)),
    });
    const source = await findSource(path.join(site, 'content'), permalink);
    t.check(source, `no source file with permalink ${permalink}`);
    if (!source) return;
    const original = await readFile(source, 'utf8');

    try {
      child = spawn('bb', ['--config', path.join(repo, 'bb.edn'), 'dev', '--no-search', '--port', String(port)],
                    { cwd: site, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      console.log(`  skip: cannot run bb (${e.message})`);
      return;
    }
    const spawnError = new Promise((resolve) => child.on('error', resolve));
    for (const s of [child.stdout, child.stderr]) {
      s.setEncoding('utf8');
      let buf = '';
      s.on('data', (d) => {
        buf += d;
        const parts = buf.split('\n');
        buf = parts.pop();
        lines.push(...parts);
      });
    }
    const ready = await Promise.race([
      waitForLine(lines, (l) => /clogem-press: watching (via|by)/.test(l), 90000),
      spawnError.then((e) => { console.log(`  skip: cannot run bb (${e.message})`); return null; }),
    ]);
    if (!ready) {
      if (child.exitCode === null && !child.killed) console.log('  skip: bb dev did not come up within 90 s');
      console.log(lines.slice(-10).map((l) => `    | ${l}`).join('\n'));
      return;
    }
    console.log(`  ${ready.trim()}`);

    const base = `http://127.0.0.1:${port}`;
    const ctx = await t.browser.newContext();
    try {
      await ctx.route('**/*', (route) =>
        new URL(route.request().url()).origin === base ? route.continue() : route.abort());
      const page = await ctx.newPage();
      await page.goto(base + permalink, { waitUntil: 'load' });

      // 1. an edit appears
      const marker = `dev-marker-${Date.now()}`;
      await writeFile(source, `${original.trimEnd()}\n\n${marker}\n`);
      const shown = await within(page, (m) => document.body && document.body.innerText.includes(m), marker, 3000);
      t.check(shown !== null, 'the edit did not appear within 3 s');
      if (shown !== null) console.log(`  edit → visible in ${shown} ms`);

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
    if (child && child.exitCode === null) {
      child.kill('SIGTERM');
      await new Promise((r) => { child.on('exit', r); setTimeout(r, 3000); });
    }
    await rm(site, { recursive: true, force: true });
  }
}
