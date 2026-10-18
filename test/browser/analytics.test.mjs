// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// The analytics loader (Phase 4 Task F, DESIGN.md §11.3 item 14) in a real
// browser. Like dev.test.mjs it does not use the served `dist/`: it builds
// temp copies of examples/demo-site, one per provider, with
// `bb build --no-search --no-highlight`, and serves each `dist/` through
// `page.route` at the demo's `:site :url`, https://clogem-demo.example —
// and the same files at http://127.0.0.1:8799, where the loader must stay
// quiet. Each provider's script URL is fulfilled with a stub that records
// `document.currentScript`'s attributes, so no request leaves the test:
// every request is to one of the routed origins, or the test fails.
//
// When `bb` is not on PATH it says so and skips; CI's browser job has bb.

import { spawn } from 'node:child_process';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const HOST = 'https://clogem-demo.example';
const LOCAL = 'http://127.0.0.1:8799';
const UUID = '94db1cb1-74f4-4a40-ad6c-962362670409';
const PROVIDER_ORIGINS = ['https://www.googletagmanager.com', 'https://plausible.io', 'https://cloud.umami.is'];

const variants = {
  ga4: '{:provider :ga4 :id "G-ABC123" :consent-default :denied}',
  plausible: '{:provider :plausible :src "https://plausible.io/js/pa-XyZ.js"' +
             ' :init {:hash-based-routing true :file-downloads {:file-extensions ["pdf"]}' +
             ' :custom-properties {:my-key "v"}}}',
  legacy: '{:provider :plausible :domain "clogem-demo.example"}',
  umami: `{:provider :umami :website-id "${UUID}"}`,
  umamiNoDnt: `{:provider :umami :website-id "${UUID}" :respect-dnt false}`,
};

const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css',
                '.svg': 'image/svg+xml', '.json': 'application/json', '.png': 'image/png', '.xml': 'application/xml' };

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

// a temp copy of the demo with `analytics` in its site.edn, built
async function build(analytics) {
  const site = await mkdtemp(path.join(os.tmpdir(), 'clogem-analytics-'));
  await cp(path.join(repo, 'examples', 'demo-site'), site, {
    recursive: true, filter: (src) => !src.split(path.sep).includes('dist'),
  });
  const edn = await readFile(path.join(site, 'site.edn'), 'utf8');
  await writeFile(path.join(site, 'site.edn'), edn.replace(/\}\s*$/, ` :analytics ${analytics}}\n`));
  const r = await run('bb', ['--config', path.join(repo, 'bb.edn'), 'build', '--no-search', '--no-highlight', '--no-write'],
                      { cwd: site });
  return { site, dist: path.join(site, 'dist'), ...r };
}

// serve `dist` at `origin` and stub every provider; returns the page and
// the list of URLs whose requests completed
async function open(t, dist, origin, { init } = {}) {
  const page = await t.newPage();
  const requests = [];
  // what completed: anything else (giscus) is aborted by t.newPage's route
  page.on('requestfinished', (r) => requests.push(r.url()));
  if (init) await page.addInitScript(init);
  await page.route(`${origin}/**`, async (route) => {
    const u = new URL(route.request().url());
    let rel = decodeURIComponent(u.pathname);
    if (rel.endsWith('/')) rel += 'index.html';
    try {
      const body = await readFile(path.join(dist, rel));
      await route.fulfill({ status: 200, body, contentType: types[path.extname(rel)] || 'application/octet-stream' });
    } catch {
      await route.fulfill({ status: 404, body: 'not found' });
    }
  });
  for (const o of PROVIDER_ORIGINS) {
    await page.route(`${o}/**`, (route) => route.fulfill({
      status: 200, contentType: 'text/javascript',
      body: '(window.__stub = window.__stub || []).push({src: document.currentScript.src, attrs: ' +
            'Object.fromEntries(Array.from(document.currentScript.attributes, (a) => [a.name, a.value]))});',
    }));
  }
  return { page, requests };
}

const dnt = () => Object.defineProperty(Navigator.prototype, 'doNotTrack', { get: () => '1' });
const gpc = () => Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', { get: () => true });

async function stubs(page, expectLoad) {
  if (expectLoad) {
    await page.waitForFunction(() => (window.__stub || []).length > 0, null, { timeout: 5000 }).catch(() => {});
  } else {
    await page.waitForTimeout(300);
  }
  return page.evaluate(() => window.__stub || []);
}

export default async function (t) {
  const builds = {};
  try {
    for (const [k, a] of Object.entries(variants)) {
      const b = await build(a);
      builds[k] = b;
      if (b.missing) { console.log(`  skip: cannot run bb (${b.out.trim()})`); return; }
      t.check(b.code === 0, `${k}: bb build exited ${b.code}:\n${b.out}`);
      if (b.code !== 0) return;
    }
    const article = '/pages/643259/';

    async function visit(k, origin, opts) {
      const { page, requests } = await open(t, builds[k].dist, origin, opts);
      await page.goto(origin + article, { waitUntil: 'load' });
      const s = await stubs(page, opts?.expectLoad ?? origin === HOST);
      const foreign = requests.filter((u) => ![HOST, LOCAL, ...PROVIDER_ORIGINS].includes(new URL(u).origin));
      t.check(foreign.length === 0, `${k}: requests left the test: ${foreign.join(', ')}`);
      return { page, s };
    }

    // GA4, consent denied
    {
      const { page, s } = await visit('ga4', HOST);
      t.check(s.length === 1 && s[0].src === 'https://www.googletagmanager.com/gtag/js?id=G-ABC123',
              `ga4: gtag.js was not loaded once on the host: ${JSON.stringify(s)}`);
      const dl = await page.evaluate(() => (window.dataLayer || []).map((e) =>
        ({ kind: Object.prototype.toString.call(e), args: Array.from(e, (x) => (x instanceof Date ? 'Date' : x)) })));
      t.check(dl.length === 3, `ga4: dataLayer has ${dl.length} entries: ${JSON.stringify(dl)}`);
      t.check(dl.every((e) => e.kind === '[object Arguments]'), `ga4: a dataLayer entry is not an Arguments object: ${JSON.stringify(dl)}`);
      t.check(JSON.stringify(dl[0]?.args) === JSON.stringify(['consent', 'default', {
        ad_storage: 'denied', ad_user_data: 'denied', ad_personalization: 'denied', analytics_storage: 'denied' }]),
              `ga4: dataLayer[0] is not the consent default: ${JSON.stringify(dl[0])}`);
      t.check(dl[1]?.args[0] === 'js' && dl[1]?.args[1] === 'Date' &&
              JSON.stringify(dl[2]?.args) === JSON.stringify(['config', 'G-ABC123']),
              `ga4: 'js' and 'config' do not follow the consent default: ${JSON.stringify(dl)}`);
    }
    {
      const { page, s } = await visit('ga4', LOCAL);
      t.check(s.length === 0, `ga4: loaded at ${LOCAL}: ${JSON.stringify(s)}`);
      t.check(await page.evaluate(() => window.dataLayer === undefined), `ga4: dataLayer set at ${LOCAL}`);
    }
    for (const [name, init] of [['DNT', dnt], ['GPC', gpc]]) {
      const { page, s } = await visit('ga4', HOST, { init, expectLoad: false });
      t.check(s.length === 0, `ga4: loaded under ${name}: ${JSON.stringify(s)}`);
      t.check(await page.evaluate(() => window.dataLayer === undefined), `ga4: dataLayer set under ${name}`);
    }

    // Plausible, the new script
    {
      const { page, s } = await visit('plausible', HOST);
      t.check(s.length === 1 && s[0].src === 'https://plausible.io/js/pa-XyZ.js', `plausible: ${JSON.stringify(s)}`);
      const o = await page.evaluate(() => window.plausible && window.plausible.o);
      t.check(JSON.stringify(o) === JSON.stringify({
        customProperties: { 'my-key': 'v' }, fileDownloads: { fileExtensions: ['pdf'] }, hashBasedRouting: true }),
              `plausible: plausible.o is ${JSON.stringify(o)}`);
    }
    {
      const { s } = await visit('plausible', HOST, { init: gpc, expectLoad: false });
      t.check(s.length === 0, `plausible: loaded under GPC: ${JSON.stringify(s)}`);
    }

    // Plausible, the legacy script
    {
      const { s } = await visit('legacy', HOST);
      t.check(s.length === 1 && s[0].src === 'https://plausible.io/js/script.js' &&
              s[0].attrs['data-domain'] === 'clogem-demo.example',
              `plausible legacy: the stub did not see data-domain: ${JSON.stringify(s)}`);
    }

    // Umami
    {
      const { s } = await visit('umami', HOST);
      t.check(s.length === 1 && s[0].src === 'https://cloud.umami.is/script.js' &&
              s[0].attrs['data-website-id'] === UUID && s[0].attrs['data-domains'] === 'clogem-demo.example' &&
              s[0].attrs['data-do-not-track'] === 'true',
              `umami: ${JSON.stringify(s)}`);
    }
    {
      const { s } = await visit('umami', LOCAL);
      t.check(s.length === 0, `umami: loaded at ${LOCAL}: ${JSON.stringify(s)}`);
    }
    {
      const { s } = await visit('umami', HOST, { init: dnt, expectLoad: false });
      t.check(s.length === 0, `umami: loaded under DNT: ${JSON.stringify(s)}`);
    }
    // :respect-dnt false: loads under DNT and GPC, and does not ask Umami to
    for (const init of [dnt, gpc]) {
      const { s } = await visit('umamiNoDnt', HOST, { init, expectLoad: true });
      t.check(s.length === 1 && !('data-do-not-track' in s[0].attrs),
              `umami with :respect-dnt false: ${JSON.stringify(s)}`);
    }
  } finally {
    for (const b of Object.values(builds)) await rm(b.site, { recursive: true, force: true });
  }
}
