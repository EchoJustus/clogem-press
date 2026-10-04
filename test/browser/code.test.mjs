// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// Code blocks (Phase 4 C, DESIGN.md §11.3 item 10), on the demo's
// code-blocks article:
//
//   - every highlighted token's COMPUTED colour reaches 4.5:1 on the
//     background actually behind it (a highlighted line's, else the code
//     block's), as do the line numbers and the language label — in light,
//     dark, read and auto-under-a-dark-OS;
//   - the copy button puts exactly the fenced source on the clipboard (no
//     line numbers), shows `check` and "Copied", and returns after ~2 s;
//   - without JS there is no button at all;
//   - line numbers are drawn, but are not text.
//
// The no-overflow check at 320 and 360 px covers this page with every
// other one (overflow.test.mjs); here a wide block is also shown to scroll
// inside its own box.

const article = 'pages/eed953/index.html';
const wide = 'pages/e19a7c/index.html';

// the first block of the article, as fenced
const firstSource = [
  'import { search } from "./search.js";',
  '',
  'const result = await search("clogem");',
  'if (result.hits.length === 0) {',
  '  console.log("no hits");',
  '}',
  '',
].join('\n');

async function inMode(t, mode, os = 'light', opts = {}) {
  const page = await t.newPage({ colorScheme: os, ...opts });
  if (mode) {
    await page.addInitScript((m) => { try { localStorage.setItem('clogem-mode', m); } catch (e) {} }, mode);
  }
  return page;
}

// Runs in the page: [ratio, description] for every token, line number and label.
function measure() {
  const parse = (c) => {
    const m = c.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const [r, g, b, a = '1'] = m[1].split(/[ ,/]+/).filter(Boolean);
    return { r: +r, g: +g, b: +b, a: +a };
  };
  const lum = ({ r, g, b }) => {
    const ch = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
  };
  const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
  const bgOf = (el) => {
    for (let e = el; e; e = e.parentElement) {
      const c = parse(getComputedStyle(e).backgroundColor);
      if (c && c.a > 0) return c;
    }
    return { r: 255, g: 255, b: 255, a: 1 };
  };
  const out = [];
  for (const pre of document.querySelectorAll('pre.clogem-code.chroma')) {
    for (const span of pre.querySelectorAll('code span')) {
      if (!span.textContent.trim() || span.children.length) continue;
      const fg = parse(getComputedStyle(span).color);
      out.push([ratio(fg, bgOf(span)), `token .${span.className} "${span.textContent.trim().slice(0, 20)}"`]);
    }
    if (pre.classList.contains('line-numbers')) {
      for (const line of pre.querySelectorAll('.line')) {
        const fg = parse(getComputedStyle(line, '::before').color);
        out.push([ratio(fg, bgOf(line)), `line number (${line.className})`]);
      }
    }
    if (pre.dataset.lang) {
      const fg = parse(getComputedStyle(pre, '::before').color);
      out.push([ratio(fg, bgOf(pre)), `language label ${pre.dataset.lang}`]);
    }
  }
  return out;
}

export default async function code(t) {
  // ---- contrast, per mode
  for (const [label, mode, os] of [['light', 'light'], ['dark', 'dark'], ['read', 'read'],
                                   ['auto under a dark OS', null, 'dark']]) {
    const page = await inMode(t, mode, os);
    await page.goto(t.url(article), { waitUntil: 'load' });
    const results = await page.evaluate(measure);
    t.check(results.length > 50, `${label}: measured ${results.length} items`);
    let min = [Infinity, ''];
    for (const [r, what] of results) {
      if (r < min[0]) min = [r, what];
      t.check(r >= 4.5, `${label}: ${what} is ${r.toFixed(2)}:1`);
    }
    console.log(`  code contrast minimum, ${label}: ${min[0].toFixed(2)} (${min[1]})`);
    t.check(await page.locator('pre .line.hl').count() === 5, `${label}: five highlighted lines`);
  }

  // ---- line numbers are drawn, not text
  {
    const page = await inMode(t, 'light');
    await page.goto(t.url(article), { waitUntil: 'load' });
    const [drawn, text] = await page.evaluate(() => {
      const line = document.querySelector('pre.line-numbers .line');
      return [getComputedStyle(line, '::before').content, document.querySelector('pre.line-numbers code').textContent];
    });
    t.check(drawn === '"1"' || drawn === 'counter(clogem-ln)' || /1/.test(drawn), `the first line number is drawn (${drawn})`);
    t.check(text === firstSource, `code.textContent is the fenced source: ${JSON.stringify(text)}`);
  }

  // ---- the copy button
  {
    const page = await inMode(t, 'light', 'light', { permissions: ['clipboard-read', 'clipboard-write'] });
    await page.goto(t.url(article), { waitUntil: 'load' });
    const buttons = page.locator('pre.clogem-code > button.clogem-copy');
    t.check(await buttons.count() === await page.locator('pre.clogem-code').count(), 'one button per block');
    const first = buttons.first();
    t.check(await first.getAttribute('aria-label') === 'Copy', 'labelled "Copy"');
    t.check((await first.locator('use').getAttribute('href')).endsWith('#copy'), 'shows the copy icon');
    await first.click();
    await page.waitForFunction(() => document.querySelector('.clogem-copy').classList.contains('is-copied'));
    const clip = await page.evaluate(() => navigator.clipboard.readText());
    t.check(clip === firstSource, `the clipboard holds exactly the source: ${JSON.stringify(clip)}`);
    t.check(await first.getAttribute('aria-label') === 'Copied', 'then labelled "Copied"');
    t.check((await first.locator('use').getAttribute('href')).endsWith('#check'), 'then shows the check icon');
    t.check((await page.locator('.clogem-code-status').textContent()) === 'Copied', 'and announces it');
    await page.waitForTimeout(2300);
    t.check(await first.getAttribute('aria-label') === 'Copy', 'back to "Copy" after about 2 s');

    // a Tamil page: the strings follow the page language
    await page.goto(t.url('ta/pages/e19a7c/index.html'), { waitUntil: 'load' });
    t.check(await page.locator('.clogem-copy').first().getAttribute('aria-label') === 'நகலெடு',
            'the Tamil page labels the button in Tamil');
  }

  // ---- no JS, no button
  {
    const page = await inMode(t, null, 'light', { javaScriptEnabled: false });
    await page.goto(t.url(article), { waitUntil: 'load' });
    t.check(await page.locator('.clogem-copy').count() === 0, 'without JS there is no copy button');
  }

  // ---- wide code scrolls inside its own box at 320 px
  {
    const page = await inMode(t, 'light', 'light', { viewport: { width: 320, height: 640 } });
    await page.goto(t.url(wide), { waitUntil: 'load' });
    const [inner, outer, docW, winW] = await page.evaluate(() => {
      const c = document.querySelector('pre.clogem-code > code');
      return [c.scrollWidth, c.clientWidth, document.documentElement.scrollWidth, window.innerWidth];
    });
    t.check(inner > outer, `the wide block scrolls inside (${inner} > ${outer})`);
    t.check(docW <= winW, `the page does not (${docW} <= ${winW})`);
  }
}
