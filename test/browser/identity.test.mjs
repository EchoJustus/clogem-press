// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// The blog identity (Phase 4 Task E1a, DESIGN.md §11.3 item 13) in a real
// browser. On the served demo: the blogger card in the mobile drawer
// (shown with JS at 360 px, focused first, Tab staying in the drawer, the
// right bar's copy then not drawn; never at 1280 or without JS), the home
// banner's colours, and no overflow at 320 and 360 px. On a temp copy of
// the demo with two background images and a 1 s interval, built with
// `bb build --no-search --no-highlight` and served through `page.route`:
// the image is painted behind the page, rotates, does not under reduced
// motion, and does not print. When `bb` is not on PATH the temp-copy part
// says so and skips; CI's browser job has bb.

import { spawn } from 'node:child_process';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const ORIGIN = 'http://clogem-e1a.test';
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css',
                '.svg': 'image/svg+xml', '.png': 'image/png', '.xml': 'application/xml' };

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

// a solid 8×8 PNG of one colour
function png([r, g, b]) {
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc = (buf) => { let c = 0xffffffff; for (const x of buf) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(8, 0); ihdr.writeUInt32BE(8, 4); ihdr[8] = 8; ihdr[9] = 2;
  const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array(8).fill([r, g, b]).flat())]);
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr),
                        chunk('IDAT', zlib.deflateSync(Buffer.concat(Array(8).fill(row)))), chunk('IEND', Buffer.alloc(0))]);
}

// a temp copy of the demo with two background images, rotating every 1 s
async function build() {
  const site = await mkdtemp(path.join(os.tmpdir(), 'clogem-e1a-'));
  await cp(path.join(repo, 'examples', 'demo-site'), site, {
    recursive: true, filter: (src) => !src.split(path.sep).includes('dist'),
  });
  await writeFile(path.join(site, 'assets', 'bg-red.png'), png([255, 0, 0]));
  await writeFile(path.join(site, 'assets', 'bg-blue.png'), png([0, 0, 255]));
  const edn = await readFile(path.join(site, 'site.edn'), 'utf8');
  if (!edn.includes(':per-page 3')) throw new Error('the demo site.edn has no `:per-page 3` to extend');
  await writeFile(path.join(site, 'site.edn'), edn.replace(':per-page 3',
    ':per-page 3 :body-bg-img ["/assets/bg-red.png" "/assets/bg-blue.png"] :body-bg-img-interval 1'));
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

function lum([r, g, b]) {
  const ch = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
  return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
}
function contrast(a, b) {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}
const rgb = (s) => s.match(/[\d.]+/g).slice(0, 3).map(Number);

export default async function (t) {
  const zh = t.url('zh-Hans/index.html');
  const rects = (page, sel) => page.evaluate((s) => {
    const e = document.querySelector(s);
    return e ? e.getClientRects().length : -1;
  }, sel);

  // --- item 4: the blogger card in the drawer, at 360 px with JS -----------
  {
    const page = await t.newPage({ viewport: { width: 360, height: 740 } });
    await page.goto(zh, { waitUntil: 'load' });
    t.check(await rects(page, '#clogem-drawer > .clogem-blogger') === 0, '360, closed: the drawer card is not drawn');
    t.check(await rects(page, '.clogem-home-right > .clogem-blogger') === 0,
            '360 with JS: the right bar\'s paired card is not drawn (the drawer has its copy)');
    await page.locator('.clogem-navbar__toggle > summary').click();
    await page.waitForFunction(() => document.activeElement && document.activeElement.closest('#clogem-drawer'), null, { timeout: 3000 }).catch(() => {});
    const r = await page.evaluate(() => {
      const card = document.querySelector('#clogem-drawer > .clogem-blogger');
      const first = card.querySelector('a[href]');
      return { shown: card.getClientRects().length > 0, focused: document.activeElement === first,
               label: first.getAttribute('aria-label'), avatar: card.querySelector('img').naturalWidth };
    });
    t.check(r.shown, '360, open: the card shows in the drawer');
    t.check(r.focused, `opening the drawer focuses the card's first link (${r.label})`);
    t.check(r.avatar > 0, 'the avatar loads');
    for (let i = 0; i < 4; i++) {
      await page.keyboard.press('Tab');
      const inside = await page.evaluate(() => !!(document.activeElement && document.activeElement.closest('#clogem-drawer')));
      t.check(inside, `Tab ${i + 1} from the card's first link stays in the drawer`);
    }
    await page.close();
  }

  // --- …never at 1280, nor at 360 without JS -------------------------------
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 800 } });
    await page.goto(zh, { waitUntil: 'load' });
    t.check(await rects(page, '#clogem-drawer > .clogem-blogger') === 0, '1280: the drawer card is not drawn');
    t.check(await rects(page, '.clogem-home-right > .clogem-blogger') > 0, '1280: the right bar\'s card is');
    await page.close();
  }
  {
    const page = await t.newPage({ viewport: { width: 360, height: 740 }, javaScriptEnabled: false });
    await page.goto(zh, { waitUntil: 'load' });
    t.check(await rects(page, '#clogem-drawer > .clogem-blogger') === 0, '360 without JS: the drawer card is hidden');
    t.check(await rects(page, '.clogem-home-right > .clogem-blogger') > 0, '360 without JS: the right bar\'s card shows');
    await page.close();
  }

  // --- items 4 and 6: no overflow at 320 and 360 px -------------------------
  for (const width of [320, 360]) {
    for (const rel of ['index.html', 'zh-Hans/index.html']) {
      const page = await t.newPage({ viewport: { width, height: 740 } });
      await page.goto(t.url(rel), { waitUntil: 'load' });
      await page.locator('.clogem-navbar__toggle > summary').click();
      const o = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, w: innerWidth }));
      t.check(o.sw <= o.w, `${rel} at ${width}, drawer open: no overflow (${o.sw} > ${o.w})`);
      await page.close();
    }
  }

  // --- item 6: the home banner's colours, in every mode ----------------------
  for (const mode of ['light', 'dark', 'read']) {
    const page = await t.newPage({ viewport: { width: 1280, height: 800 } });
    await page.addInitScript((m) => { try { localStorage.setItem('clogem-mode', m); } catch (e) {} }, mode);
    await page.goto(t.url('index.html'), { waitUntil: 'load' });
    const c = await page.evaluate(() => {
      const hero = document.querySelector('.clogem-hero');
      const a = hero.querySelector('.clogem-hero__action');
      const tag = hero.querySelector('.clogem-hero__tagline');
      return { heroBg: getComputedStyle(hero).backgroundColor, tag: getComputedStyle(tag).color,
               fg: getComputedStyle(a).color, bg: getComputedStyle(a).backgroundColor,
               img: hero.querySelector('img').naturalWidth };
    });
    const tc = contrast(rgb(c.tag), rgb(c.heroBg));
    const ac = contrast(rgb(c.fg), rgb(c.bg));
    t.check(tc >= 4.5, `${mode}: the banner's text on its background ${tc.toFixed(2)}:1 (${c.tag} on ${c.heroBg})`);
    t.check(ac >= 4.5, `${mode}: the action ${ac.toFixed(2)}:1 (${c.fg} on ${c.bg})`);
    t.check(c.img > 0, `${mode}: the hero image loads`);
    await page.close();
  }
  {
    const page = await t.newPage({ viewport: { width: 1280, height: 800 } });
    await page.goto(t.url('index.html'), { waitUntil: 'load' });
    await page.emulateMedia({ media: 'print' });
    t.check(await rects(page, '.clogem-hero') === 0, 'print hides the banner');
    t.check(await page.evaluate(() => [...document.querySelectorAll('.clogem-footer p')].every((p) => p.getClientRects().length > 0)),
            'print keeps the footer\'s text lines');
    t.check(await rects(page, '.clogem-footer .clogem-social') === 0, 'print hides the footer\'s links');
    await page.close();
  }

  // --- item 8: the body background, on a temp build -------------------------
  const b = await build();
  try {
    if (b.missing) {
      console.log(`  skip: bb is not on PATH (${b.out.trim()}); the body background checks need a temp build`);
      return;
    }
    t.check(b.code === 0, `the temp build with :body-bg-img succeeds:\n${b.out}`);
    if (b.code !== 0) return;
    const article = `${ORIGIN}/pages/c0nta1/`;
    {
      const page = await serve(t, b.dist, { viewport: { width: 1280, height: 800 } });
      // the zh-Hans home: a 62rem shell, so a 144 px gutter at 1280
      await page.goto(`${ORIGIN}/zh-Hans/`, { waitUntil: 'load' });
      const first = await page.evaluate(() => document.querySelector('img.clogem-body-bg').getAttribute('src'));
      t.check(first === '/assets/bg-red.png', `the first image: ${first}`);
      // painted behind the page: a gutter pixel is neither --bodyBg nor the image's own red
      const shot = await page.screenshot({ clip: { x: 4, y: 400, width: 1, height: 1 } });
      const px = await page.evaluate(async (b64) => {
        const img = new Image();
        img.src = `data:image/png;base64,${b64}`;
        await img.decode();
        const c = document.createElement('canvas'); c.width = 1; c.height = 1;
        const g = c.getContext('2d'); g.drawImage(img, 0, 0);
        return [...g.getImageData(0, 0, 1, 1).data].slice(0, 3);
      }, shot.toString('base64'));
      const bodyBg = await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
      t.check(JSON.stringify(px) !== JSON.stringify(rgb(bodyBg)) && px[0] > px[2] + 40,
              `the image is painted in the gutter at its opacity: ${px} (--bodyBg ${bodyBg})`);
      const onTop = await page.evaluate(() => {
        const m = document.querySelector('main').getBoundingClientRect();
        const e = document.elementFromPoint(m.left + m.width / 2, m.top + 40);
        return e && !e.classList.contains('clogem-body-bg');
      });
      t.check(onTop, 'the page\'s boxes are drawn over it');
      await page.waitForFunction(() => document.querySelector('img.clogem-body-bg').getAttribute('src') !== '/assets/bg-red.png',
                                 null, { timeout: 4000 }).catch(() => {});
      const next = await page.evaluate(() => document.querySelector('img.clogem-body-bg').getAttribute('src'));
      t.check(next === '/assets/bg-blue.png', `it rotates within the interval: ${next}`);
      await page.emulateMedia({ media: 'print' });
      t.check(await rects(page, 'img.clogem-body-bg') === 0, 'print hides it');
      await page.close();
    }
    {
      const page = await serve(t, b.dist, { viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce' });
      await page.goto(article, { waitUntil: 'load' });
      await page.waitForTimeout(2600);
      const src = await page.evaluate(() => document.querySelector('img.clogem-body-bg').getAttribute('src'));
      t.check(src === '/assets/bg-red.png', `under reduced motion it does not rotate: ${src}`);
      await page.close();
    }
  } finally {
    await rm(b.site, { recursive: true, force: true });
  }
}
