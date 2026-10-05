// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// What the `bb dev` browser tests share (not a test: run.mjs only runs
// `*.test.mjs`): a temp copy of examples/demo-site, `bb dev` started there
// on its own port, its output collected line by line, and the rule for when
// that start is a skip and when it is a failure.
//
//   - `bb` not on PATH: skip, saying so;
//   - still starting after 90 s: skip, saying so (a slow machine);
//   - EXITED before it was ready — a crash at startup: a FAILURE that prints
//     everything it wrote. Waiting out the 90 s and returning used to report
//     `ok` for a `bb dev` that had crashed (P4-D.1 item 10).

import { spawn } from 'node:child_process';
import { cp, mkdtemp, readdir, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const repo = path.resolve(here, '..', '..');

export async function findSource(dir, link) {
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

// Resolve once `pred(line)` holds for a line of the child's output (from
// index `from` on); with {exited: code} as soon as the child has exited
// without it; or with null after `ms`.
export function waitForLine(lines, pred, ms, child, from = 0) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    const tick = () => {
      const hit = lines.slice(from).find(pred);
      if (hit) return resolve(hit);
      if (child && (child.exitCode !== null || child.signalCode !== null)) {
        return resolve({ exited: child.exitCode ?? child.signalCode });
      }
      if (Date.now() - t0 > ms) return resolve(null);
      setTimeout(tick, 20);
    };
    tick();
  });
}

// Poll `fn` in the page (surviving the reloads in between) until it returns
// a truthy value; returns the elapsed ms, or null after `ms`.
export async function within(page, fn, arg, ms) {
  const t0 = Date.now();
  while (Date.now() - t0 <= ms) {
    try {
      if (await page.evaluate(fn, arg)) return Date.now() - t0;
    } catch (e) { /* the page is reloading */ }
    await new Promise((r) => setTimeout(r, 25));
  }
  return null;
}

// Copy the demo, start `bb dev <args>` in it and wait until it watches.
// Returns {site, port, lines, child, stop} once it is ready, or null after
// a skip or a recorded failure (`t.check`). `stop()` ends it and removes
// the copy; call it in a `finally` whatever this returned.
export async function startDev(t, args) {
  const site = await mkdtemp(path.join(os.tmpdir(), 'clogem-dev-browser-'));
  const port = 18900 + Math.floor(Math.random() * 90);
  const lines = [];
  const state = { site, port, lines, child: null };
  state.stop = async () => {
    const child = state.child;
    if (child && child.exitCode === null && child.signalCode === null) {
      child.kill('SIGTERM');
      await new Promise((r) => { child.on('exit', r); setTimeout(r, 5000); });
    }
    await rm(site, { recursive: true, force: true });
  };
  await cp(path.join(repo, 'examples', 'demo-site'), site, {
    recursive: true,
    filter: (src) => !/[\\/]dist(-[^\\/]*)?([\\/]|$)/.test(path.relative(repo, src)),
  });
  let child;
  try {
    child = spawn('bb', ['--config', path.join(repo, 'bb.edn'), 'dev', '--port', String(port), ...args],
                  { cwd: site, stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    console.log(`  skip: cannot run bb (${e.message})`);
    return { ...state, ready: false };
  }
  state.child = child;
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
  let spawnFailed = false;
  const ready = await Promise.race([
    waitForLine(lines, (l) => /clogem-press: watching (via|by)/.test(l), 90000, child),
    spawnError.then((e) => { spawnFailed = true; console.log(`  skip: cannot run bb (${e.message})`); return null; }),
  ]);
  if (spawnFailed) return { ...state, ready: false };
  // a spawn that failed (ENOENT: no bb on PATH) reports a negative errno
  if (ready && typeof ready === 'object' && typeof ready.exited === 'number' && ready.exited < 0) {
    console.log(`  skip: cannot run bb (exit ${ready.exited})`);
    return { ...state, ready: false };
  }
  if (ready && typeof ready === 'object') {
    // let the pipes drain, then report everything it printed
    await new Promise((r) => setTimeout(r, 200));
    t.check(false, `bb dev exited (${ready.exited}) before it was ready:\n${lines.map((l) => `    | ${l}`).join('\n')}`);
    return { ...state, ready: false };
  }
  if (!ready) {
    console.log('  skip: bb dev did not come up within 90 s');
    console.log(lines.slice(-10).map((l) => `    | ${l}`).join('\n'));
    return { ...state, ready: false };
  }
  console.log(`  ${ready.trim()}`);
  return { ...state, ready: true };
}
