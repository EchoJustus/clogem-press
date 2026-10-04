// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// Colour modes in a real browser (Phase 4 B1, D-P4-2 … D-P4-5):
//
//   - the first frame is already in the right mode, for every combination
//     of stored mode, OS scheme, blocked storage and disabled JS;
//   - the toggle works from the keyboard and remembers the choice;
//   - in auto mode an OS change switches the palette without a reload;
//   - Pagefind's modal and trigger follow the mode;
//   - print hides the chrome;
//   - the site's overrides/custom.css is applied.
//
// Expected colours are read from the theme.css being served, so the test
// follows the palette rather than restating it.

import { readFile } from 'node:fs/promises';
import path from 'node:path';

const article = 'pages/643259/index.html';   // en, sidebar, TOC, comments

function hexToRgb(hex) {
  let h = hex.trim().replace('#', '');
  if (h.length === 3) h = [...h].map((c) => c + c).join('');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
  return `rgb(${r}, ${g}, ${b})`;
}

// {mode → {var → value}} from theme.css's mode blocks
function palette(css) {
  const block = (sel, from = 0) => {
    const i = css.indexOf(`\n${sel} {`, from);
    if (i < 0) throw new Error(`no rule ${sel}`);
    const body = css.slice(css.indexOf('{', i) + 1, css.indexOf('}', i));
    return Object.fromEntries([...body.matchAll(/(--[A-Za-z0-9-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]));
  };
  return {
    light: block(':root, .theme-mode-light'),
    read: block('.theme-mode-read'),
    dark: block('.theme-mode-dark'),
  };
}

async function firstFrame(t, url, { os, stored, storageThrows = false, js = true }) {
  const page = await t.newPage({ colorScheme: os, javaScriptEnabled: js });
  if (js) {
    await page.addInitScript(({ stored, storageThrows }) => {
      if (storageThrows) {
        Object.defineProperty(window, 'localStorage', { get() { throw new Error('blocked'); } });
      } else {
        try {
          if (stored == null) localStorage.removeItem('clogem-mode');
          else localStorage.setItem('clogem-mode', stored);
        } catch (e) { /* about:blank */ }
      }
      window.__firstFrame = new Promise((resolve) => requestAnimationFrame(() =>
        resolve(getComputedStyle(document.documentElement).backgroundColor)));
    }, { stored, storageThrows });
  }
  await page.goto(url, { waitUntil: 'load' });
  const bg = js
    ? await page.evaluate(() => window.__firstFrame)
    : await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
  const mode = await page.evaluate(() => document.documentElement.getAttribute('data-mode'));
  await page.close();
  return { bg, mode };
}

export default async function modes(t) {
  const css = await readFile(path.join(t.dist, 'clogem', 'css', 'theme.css'), 'utf8');
  const pal = palette(css);
  const bodyBg = Object.fromEntries(Object.entries(pal).map(([m, v]) => [m, hexToRgb(v['--bodyBg'])]));
  const mainBg = Object.fromEntries(Object.entries(pal).map(([m, v]) => [m, hexToRgb(v['--mainBg'])]));
  const url = t.url(article);

  // --- the first frame ----------------------------------------------------
  const matrix = [
    ['stored dark, light OS', { os: 'light', stored: 'dark' }, 'dark'],
    ['stored read, dark OS', { os: 'dark', stored: 'read' }, 'read'],
    ['nothing stored, dark OS', { os: 'dark', stored: null }, 'dark'],
    ['nothing stored, light OS', { os: 'light', stored: null }, 'light'],
    ['localStorage throws, dark OS', { os: 'dark', storageThrows: true }, 'dark'],
    ['localStorage throws, light OS', { os: 'light', storageThrows: true }, 'light'],
    // the demo's default is auto, so a garbage value follows the OS
    ['a garbage value, dark OS', { os: 'dark', stored: 'sepia' }, 'dark'],
    ['JS disabled, dark OS', { os: 'dark', js: false }, 'dark'],
    ['JS disabled, light OS', { os: 'light', js: false }, 'light'],
  ];
  for (const [what, opts, want] of matrix) {
    const { bg, mode } = await firstFrame(t, url, opts);
    t.check(bg === bodyBg[want], `first frame, ${what}: background ${bg}, want ${want} ${bodyBg[want]}`);
    if (opts.js === false) t.check(mode === null, `${what}: no data-mode without JS`);
  }
  {
    // a garbage value falls back to the DEFAULT, not to auto: a page whose
    // <html data-default-mode> is read must start in read
    const page = await t.newPage({ colorScheme: 'dark' });
    await page.addInitScript(() => { try { localStorage.setItem('clogem-mode', 'sepia'); } catch (e) {} });
    await page.route('**/' + article.replace(/index\.html$/, ''), async (route) => {
      const res = await route.fetch();
      const body = (await res.text()).replace('data-default-mode="auto"', 'data-default-mode="read"');
      await route.fulfill({ response: res, body });
    });
    await page.goto(url, { waitUntil: 'load' });
    const [bg, mode] = await page.evaluate(() => [
      getComputedStyle(document.documentElement).backgroundColor,
      document.documentElement.getAttribute('data-mode')]);
    t.check(mode === 'read' && bg === bodyBg.read, `a garbage value with default read: ${mode} ${bg}`);
    await page.close();
  }

  // --- the toggle, by keyboard ----------------------------------------------
  {
    const page = await t.newPage({ colorScheme: 'light' });
    await page.goto(url, { waitUntil: 'load' });
    t.check(await page.locator('.clogem-mode').isVisible(), 'the toggle is revealed by JS');
    let on = false;
    for (let i = 0; i < 80 && !on; i++) {
      await page.keyboard.press('Tab');
      on = await page.evaluate(() => document.activeElement?.classList.contains('clogem-mode__button'));
    }
    t.check(on, 'Tab reaches the toggle');
    await page.keyboard.press('Enter');
    t.check(await page.locator('#clogem-mode-menu').isVisible(), 'Enter opens the menu');
    t.check(await page.getAttribute('.clogem-mode__button', 'aria-expanded') === 'true', 'aria-expanded=true');
    t.check(await page.evaluate(() => document.activeElement?.getAttribute('data-mode')) === 'auto',
            'focus is on the current mode (auto)');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('ArrowDown');
    t.check(await page.evaluate(() => document.activeElement?.getAttribute('data-mode')) === 'dark',
            'the arrow keys reach Dark');
    await page.keyboard.press('Enter');
    const after = await page.evaluate(() => ({
      mode: document.documentElement.getAttribute('data-mode'),
      cls: document.documentElement.className,
      stored: localStorage.getItem('clogem-mode'),
      bg: getComputedStyle(document.documentElement).backgroundColor,
      pressed: [...document.querySelectorAll('#clogem-mode-menu button')].map((b) => b.getAttribute('aria-pressed')),
      focus: document.activeElement?.classList.contains('clogem-mode__button'),
    }));
    t.check(after.mode === 'dark' && /\btheme-mode-dark\b/.test(after.cls), `Enter applies Dark: ${JSON.stringify(after)}`);
    t.check(after.stored === 'dark', 'and stores it');
    t.check(after.bg === bodyBg.dark, `the palette is dark: ${after.bg}`);
    t.check(JSON.stringify(after.pressed) === '["false","false","true","false"]', `aria-pressed: ${after.pressed}`);
    t.check(after.focus, 'choosing returns focus to the button');
    t.check(!(await page.locator('#clogem-mode-menu').isVisible()), 'choosing closes the menu');

    await page.keyboard.press('Enter');
    t.check(await page.locator('#clogem-mode-menu').isVisible(), 'Enter reopens it');
    await page.keyboard.press('Tab');
    t.check(await page.evaluate(() => document.activeElement?.getAttribute('data-mode')) === 'read',
            'Tab moves between the choices too');
    await page.keyboard.press('Escape');
    t.check(!(await page.locator('#clogem-mode-menu').isVisible()), 'Escape closes it');
    t.check(await page.evaluate(() => document.activeElement?.classList.contains('clogem-mode__button')),
            'Escape returns focus to the button');
    t.check(await page.getAttribute('.clogem-mode__button', 'aria-expanded') === 'false', 'aria-expanded=false');

    await page.click('.clogem-mode__button');
    t.check(await page.locator('#clogem-mode-menu').isVisible(), 'a click opens it');
    await page.mouse.click(5, 600);
    t.check(!(await page.locator('#clogem-mode-menu').isVisible()), 'a click outside closes it');

    const events = await page.evaluate(() => new Promise((resolve) => {
      document.addEventListener('clogem:modechange', (e) => resolve(e.detail), { once: true });
      window.clogem.setMode('read');
    }));
    t.check(events.mode === 'read' && events.resolved === 'read', `clogem:modechange: ${JSON.stringify(events)}`);
    t.check(await page.evaluate(() => window.clogem.setMode('sepia')) === false, 'setMode refuses a non-mode');
    t.check(await page.evaluate(() => typeof window.clogem.setCommentsTheme) === 'function',
            'window.clogem is extended, not replaced (comments.js)');
    t.check(await page.evaluate(() => document.querySelector('script[src="https://giscus.app/client.js"]').getAttribute('data-theme')) === 'light',
            'giscus follows: read → light');
    await page.evaluate(() => window.clogem.setMode('dark'));
    t.check(await page.evaluate(() => document.querySelector('script[src="https://giscus.app/client.js"]').getAttribute('data-theme')) === 'dark',
            'giscus follows: dark → dark');
    await page.reload({ waitUntil: 'load' });
    t.check(await page.evaluate(() => document.documentElement.getAttribute('data-mode')) === 'dark', 'the choice survives a reload');
    t.check(await page.evaluate(() => document.querySelector('script[src="https://giscus.app/client.js"]').getAttribute('data-theme')) === 'dark',
            'giscus starts in the stored mode');
    await page.close();
  }

  // --- a live OS change in auto mode ---------------------------------------
  {
    const page = await t.newPage({ colorScheme: 'light' });
    await page.goto(url, { waitUntil: 'load' });
    const bg = () => page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
    t.check(await bg() === bodyBg.light, 'auto on a light OS is light');
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.waitForFunction((want) => getComputedStyle(document.documentElement).backgroundColor === want,
                               bodyBg.dark, { timeout: 2000 }).catch(() => {});
    t.check(await bg() === bodyBg.dark, `an OS change to dark switches the palette live: ${await bg()}`);
    t.check(await page.evaluate(() => document.documentElement.getAttribute('data-mode')) === 'auto', 'the mode stays auto');
    await page.emulateMedia({ colorScheme: 'light' });
    await page.waitForFunction((want) => getComputedStyle(document.documentElement).backgroundColor === want,
                               bodyBg.light, { timeout: 2000 }).catch(() => {});
    t.check(await bg() === bodyBg.light, 'and back');
    await page.close();
  }

  // --- the search modal follows the mode -----------------------------------
  for (const mode of ['dark', 'read', 'light']) {
    const page = await t.newPage({ colorScheme: 'light' });
    await page.addInitScript((m) => { try { localStorage.setItem('clogem-mode', m); } catch (e) {} }, mode);
    await page.goto(url, { waitUntil: 'load' });
    const trigger = page.locator('pagefind-modal-trigger button').first();
    await trigger.waitFor({ timeout: 5000 });
    const tbg = await trigger.evaluate((el) => getComputedStyle(el).backgroundColor);
    t.check(tbg === mainBg[mode], `${mode}: the search trigger is ${tbg}, want --mainBg ${mainBg[mode]}`);
    await trigger.click();
    const modal = page.locator('dialog.pf-modal[open]').first();
    await modal.waitFor({ timeout: 5000 }).catch(() => {});
    const mbg = await modal.evaluate((el) => getComputedStyle(el).backgroundColor).catch(() => null);
    t.check(mbg === mainBg[mode], `${mode}: the search dialog is ${mbg}, want --mainBg ${mainBg[mode]}`);
    await page.close();
  }

  // --- print -------------------------------------------------------------------
  {
    const page = await t.newPage({ colorScheme: 'dark' });
    await page.addInitScript(() => { try { localStorage.setItem('clogem-mode', 'dark'); } catch (e) {} });
    await page.goto(url, { waitUntil: 'load' });
    await page.emulateMedia({ media: 'print' });
    const shown = await page.evaluate(() => Object.fromEntries(
      ['.clogem-navbar', '.clogem-sidebar', '.clogem-toc', '.clogem-mode', '.clogem-search', '.clogem-comments']
        .map((s) => [s, document.querySelector(s) ? getComputedStyle(document.querySelector(s)).display : 'absent'])));
    for (const [s, d] of Object.entries(shown)) t.check(d === 'none' || d === 'absent', `print hides ${s} (display ${d})`);
    const bg = await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
    t.check(bg === 'rgb(255, 255, 255)', `print uses the light palette even in dark mode: ${bg}`);
    await page.close();
  }

  // --- overrides/custom.css is applied, last --------------------------------
  {
    const page = await t.newPage();
    await page.goto(url, { waitUntil: 'load' });
    const after = await page.evaluate(() => getComputedStyle(document.querySelector('.clogem-footer p'), '::after').content);
    t.check(after.includes('overrides/custom.css'), `the demo's custom.css applies: ${after}`);
    const last = await page.evaluate(() => [...document.querySelectorAll('link[rel=stylesheet]')].pop().getAttribute('href'));
    t.check(/\/clogem\/overrides\/custom\.css\?v=/.test(last), `custom.css is the last stylesheet: ${last}`);
    const iconBox = await page.locator('.clogem-breadcrumbs__sep').first().boundingBox();
    t.check(iconBox && iconBox.width > 0 && iconBox.width < 40, `the sprite icons render at text size: ${JSON.stringify(iconBox)}`);
    await page.close();
  }
}
