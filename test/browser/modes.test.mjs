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
// Expected colours are what the stylesheets being served compute (palette()
// below), so the test follows the palette rather than restating it.

const article = 'pages/643259/index.html';   // en, sidebar, TOC, comments

// {mode → {var → computed value}}: what theme.css computes on <html> in
// each mode (P4-B2a item 1). B1 parsed the first rule whose selector was
// exactly `.theme-mode-dark` and so on, missing later and compound rules;
// the browser is a cascade engine, so it is asked instead: on a no-JS page
// (nothing rewrites the classes), with Pagefind's stylesheet removed (so
// these are the theme's own values; item 2 checks that they beat it),
// <html> is given each mode's class. Auto is read under a dark OS.
const colourVars = ['--bodyBg', '--mainBg', '--sidebarBg', '--blurBg', '--accent', '--codeBg', '--codeColor',
                    '--pf-error-bg', '--pf-modal-backdrop'];
const plainVars = ['--pf-shadow-lg'];

// runs in the page: a probe element paints each colour variable as its
// background, which the browser resolves to rgb()/rgba(); the rest are read
// as <html>'s computed custom property
function readVars({ colours, plain }) {
  const probe = document.createElement('div');
  document.body.appendChild(probe);
  const v = {};
  for (const name of colours) {
    probe.style.background = `var(${name})`;
    v[name] = getComputedStyle(probe).backgroundColor;
  }
  for (const name of plain) v[name] = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  probe.remove();
  return v;
}

async function palette(t, url, style = 'card') {
  const out = {};
  for (const [mode, os] of [['light', 'light'], ['read', 'light'], ['dark', 'light'], ['auto', 'dark']]) {
    const page = await t.newPage({ colorScheme: os, javaScriptEnabled: false });
    await page.goto(url, { waitUntil: 'load' });
    await page.evaluate(({ mode, style }) => {
      document.querySelectorAll('link[rel=stylesheet][href*="pagefind"]').forEach((l) => l.remove());
      document.documentElement.className = `theme-mode-${mode} theme-style-${style}`;
    }, { mode, style });
    out[mode] = await page.evaluate(readVars, { colours: colourVars, plain: plainVars });
    await page.close();
  }
  return out;
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
  const url = t.url(article);
  const pal = await palette(t, url);
  const bodyBg = Object.fromEntries(Object.entries(pal).map(([m, v]) => [m, v['--bodyBg']]));
  const mainBg = Object.fromEntries(Object.entries(pal).map(([m, v]) => [m, v['--mainBg']]));

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

  // --- the toggle names the current mode (P4-B2a item 3) --------------------
  const name = (page) => page.evaluate(() => {
    const b = document.querySelector('.clogem-mode__button');
    return [b.getAttribute('aria-label'), b.getAttribute('title')];
  });
  for (const [u, want] of [[url, 'Colour mode: Dark'], [t.url('zh-Hans/index.html'), '主题模式：深色模式']]) {
    const page = await t.newPage({ colorScheme: 'light' });
    await page.addInitScript(() => { try { localStorage.setItem('clogem-mode', 'dark'); } catch (e) {} });
    await page.goto(u, { waitUntil: 'load' });
    const [label, title] = await name(page);
    t.check(label === want && title === want, `with dark stored the toggle is named "${label}" / "${title}", want "${want}"`);
    await page.close();
  }
  {
    const page = await t.newPage({ colorScheme: 'light' });
    await page.goto(url, { waitUntil: 'load' });
    t.check((await name(page))[0] === 'Colour mode: Follow system', `nothing stored: ${(await name(page))[0]}`);
    await page.focus('.clogem-mode__button');
    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowDown');
    t.check(await page.evaluate(() => document.activeElement?.getAttribute('data-mode')) === 'light', 'ArrowDown reaches Light');
    await page.keyboard.press('Enter');
    t.check((await name(page))[0] === 'Colour mode: Light', `after choosing Light by keyboard: ${(await name(page))[0]}`);
    // another page of the same reader (same context, so the same storage)
    const other = await page.context().newPage();
    await other.goto(url, { waitUntil: 'load' });
    await other.evaluate(() => window.clogem.setMode('read'));
    await page.waitForFunction(() => document.documentElement.getAttribute('data-mode') === 'read', null, { timeout: 2000 }).catch(() => {});
    t.check((await name(page))[0] === 'Colour mode: Reading',
            `another tab stores read: the first page's toggle is "${(await name(page))[0]}"`);
    await other.close();
    // P4-B2a.1 item 7: a label is text, not a replacement pattern
    await page.evaluate(() => {
      document.querySelector('#clogem-mode-menu button[data-mode="dark"]').lastChild.textContent = "Dark $& $1 $$ $'";
      window.clogem.setMode('dark');
    });
    t.check((await name(page))[0] === "Colour mode: Dark $& $1 $$ $'", `a label with $& in it: "${(await name(page))[0]}"`);
    await page.close();
  }

  // --- a CSP that blocks the inline head script (P4-B2a item 3) --------------
  // script-src 'self' without the script's sha256: only js/mode.js runs
  const withCsp = async (page) => page.route('**/' + article.replace(/index\.html$/, ''), async (route) => {
    const res = await route.fetch();
    await route.fulfill({ response: res, headers: { ...res.headers(), 'content-security-policy': "script-src 'self'" } });
  });
  const state = (page) => page.evaluate(() => ({
    cls: document.documentElement.className,
    mode: document.documentElement.getAttribute('data-mode'),
    bg: getComputedStyle(document.documentElement).backgroundColor,
    pressed: [...document.querySelectorAll('#clogem-mode-menu button[aria-pressed="true"]')].map((b) => b.dataset.mode),
    label: document.querySelector('.clogem-mode__button').getAttribute('aria-label'),
  }));
  {
    const page = await t.newPage({ colorScheme: 'light' });
    await page.addInitScript(() => { try { localStorage.setItem('clogem-mode', 'dark'); } catch (e) {} });
    await withCsp(page);
    await page.goto(url, { waitUntil: 'load' });
    await page.waitForFunction(() => document.documentElement.hasAttribute('data-mode'), null, { timeout: 2000 }).catch(() => {});
    const s = await state(page);
    t.check(/\btheme-mode-dark\b/.test(s.cls) && !/theme-mode-auto/.test(s.cls), `CSP, dark stored: class "${s.cls}"`);
    t.check(s.mode === 'dark', `CSP, dark stored: data-mode ${s.mode}`);
    t.check(s.bg === bodyBg.dark, `CSP, dark stored: background ${s.bg}, want ${bodyBg.dark}`);
    t.check(JSON.stringify(s.pressed) === '["dark"]', `CSP, dark stored: pressed ${s.pressed}`);
    t.check(s.label === 'Colour mode: Dark', `CSP, dark stored: named "${s.label}"`);
    await page.close();
  }
  {
    const page = await t.newPage({ colorScheme: 'dark' });
    await page.addInitScript(() => { try { localStorage.setItem('clogem-mode', 'auto'); } catch (e) {} });
    await withCsp(page);
    await page.goto(url, { waitUntil: 'load' });
    await page.waitForFunction(() => document.documentElement.hasAttribute('data-mode'), null, { timeout: 2000 }).catch(() => {});
    let s = await state(page);
    t.check(/\btheme-mode-dark\b/.test(s.cls) && s.mode === 'auto', `CSP, auto stored, dark OS: ${JSON.stringify(s)}`);
    await page.emulateMedia({ colorScheme: 'light' });
    await page.waitForFunction(() => /\btheme-mode-light\b/.test(document.documentElement.className), null, { timeout: 2000 }).catch(() => {});
    s = await state(page);
    t.check(/\btheme-mode-light\b/.test(s.cls) && s.bg === bodyBg.light, `CSP, auto: an OS change to light is followed: ${JSON.stringify(s)}`);
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

  // --- Pagefind's remaining variables follow the mode (P4-B2a item 2) ------
  // Pagefind's :root block is linked after theme.css; its dark values sit
  // under [data-pf-theme="dark"], which nothing sets
  for (const mode of ['light', 'dark', 'read']) {
    const page = await t.newPage({ colorScheme: 'light' });
    await page.addInitScript((m) => { try { localStorage.setItem('clogem-mode', m); } catch (e) {} }, mode);
    await page.goto(url, { waitUntil: 'load' });
    t.check(await page.evaluate(() => !!document.querySelector('link[href*="pagefind-component-ui.css"]')),
            `${mode}: Pagefind's stylesheet is linked`);
    const got = await page.evaluate(readVars, { colours: ['--pf-error-bg', '--pf-modal-backdrop'], plain: ['--pf-shadow-lg'] });
    for (const v of ['--pf-error-bg', '--pf-shadow-lg', '--pf-modal-backdrop']) {
      t.check(!['', 'rgba(0, 0, 0, 0)'].includes(pal[mode][v]), `${mode}: the theme sets ${v}`);
      t.check(got[v] === pal[mode][v], `${mode}: ${v} is ${got[v]}, want the theme's ${pal[mode][v]}`);
    }
    // P4-B2a.1 item 10: in light and read most of the theme's values equal
    // Pagefind's, so a mapping that loses to its :root reads the same. Set
    // every --pf-* the theme maps to a sentinel in Pagefind's :root rule:
    // none may show through on <html>.
    const lost = await page.evaluate(() => {
      const sheets = [...document.styleSheets];
      const theme = sheets.find((s) => /\/theme\.css/.test(s.href || ''));
      const pf = sheets.find((s) => /pagefind-component-ui\.css/.test(s.href || ''));
      // whatever rule the theme declares it in
      const all = (rules) => [...rules].flatMap((r) => [...(r.style ? [r] : []), ...(r.cssRules ? all(r.cssRules) : [])]);
      const mapped = [...new Set(all(theme.cssRules).flatMap((r) => [...r.style])
        .filter((p) => p.startsWith('--pf-')))];
      const root = [...pf.cssRules].find((r) => r.selectorText === ':root');
      const tried = mapped.filter((p) => root.style.getPropertyValue(p) !== '');
      for (const p of tried) root.style.setProperty(p, 'clogem-sentinel');
      const h = getComputedStyle(document.documentElement);
      return { tried: tried.length, lost: tried.filter((p) => h.getPropertyValue(p).trim() === 'clogem-sentinel') };
    });
    t.check(lost.tried >= 20, `${mode}: the theme maps ${lost.tried} of Pagefind's :root variables`);
    t.check(lost.lost.length === 0, `${mode}: Pagefind's :root wins over the theme's ${lost.lost.join(', ')}`);
    await page.close();
  }

  // --- card and line page styles (P4-B2a item 5) -----------------------------
  // The demo builds with :page-style :card; <html>'s style class is switched
  // in-page. Auto is the no-JS page under a dark OS, where theme-mode-auto
  // stays and the dark media block applies.
  // [page, is .clogem-main a box?, the other boxes]
  const stylePages = [
    [article, true, ['.clogem-sidebar']],
    // the only demo home with a right bar; its post list is :simple
    ['zh-Hans/index.html', true, ['.clogem-home-right > section']],
    // the detailed post list: cards on the page, not in a card; the home's
    // own text is the card above them (P4-B2a.1 item 3)
    ['page/2/index.html', false, ['.clogem-main--posts > .clogem-content', '.clogem-post-card']],
    ['categories/basics/index.html', true, []],
    ['pages/559f0f/index.html', true, []],   // a catalogue
  ];
  const mainSel = '.clogem-main';
  for (const [rel, mainIsBox, others] of stylePages) {
    for (const mode of ['light', 'dark', 'read', 'auto']) {
      const auto = mode === 'auto';
      const page = await t.newPage({ colorScheme: auto ? 'dark' : 'light', javaScriptEnabled: !auto });
      if (!auto) await page.addInitScript((m) => { try { localStorage.setItem('clogem-mode', m); } catch (e) {} }, mode);
      await page.goto(t.url(rel), { waitUntil: 'load' });
      for (const style of ['card', 'line']) {
        const r = await page.evaluate(({ style, mainSel, others }) => {
          const h = document.documentElement;
          h.className = h.className.replace(/\btheme-style-\S+/, `theme-style-${style}`);
          const probe = document.createElement('div');
          document.body.appendChild(probe);
          const read = (v) => { probe.style.background = `var(${v})`; return getComputedStyle(probe).backgroundColor; };
          const mainBg = read('--mainBg'), sidebarBg = read('--sidebarBg');
          probe.remove();
          const box = (el) => { const c = getComputedStyle(el); return { bg: c.backgroundColor, shadow: c.boxShadow, border: c.borderTopWidth }; };
          const main = document.querySelector(mainSel);
          return {
            cls: h.className, root: getComputedStyle(h).backgroundColor, mainBg,
            main: main && box(main),
            // each box's own background: the sidebar's is --sidebarBg
            others: others.map((s) => [s, s === '.clogem-sidebar' ? sidebarBg : mainBg, [...document.querySelectorAll(s)].map(box)]),
          };
        }, { style, mainSel, others });
        const what = `${rel} ${mode} ${style}`;
        if (!r.main) { t.check(false, `${what}: no ${mainSel}`); continue; }
        t.check(new RegExp(`theme-mode-${auto ? 'auto' : mode}\\b`).test(r.cls), `${what}: <html class="${r.cls}">`);
        if (!mainIsBox) {
          t.check(r.main.bg === 'rgba(0, 0, 0, 0)' && r.main.shadow === 'none' && r.main.border === '0px',
                  `${what}: the post cards' column is no box (${r.main.bg}, ${r.main.shadow}, ${r.main.border})`);
        } else if (style === 'card') {
          t.check(r.main.bg === r.mainBg && r.main.bg !== r.root,
                  `${what}: the main box is ${r.main.bg}, want --mainBg ${r.mainBg}, distinct from the page ${r.root}`);
          t.check(r.main.shadow !== 'none', `${what}: the main box has a shadow (${r.main.shadow})`);
        } else {
          t.check(r.main.bg === r.root, `${what}: the main box ${r.main.bg} is the page's ${r.root}`);
          t.check(r.main.shadow === 'none', `${what}: no shadow (${r.main.shadow})`);
          t.check(r.main.border === '1px', `${what}: a 1px border (${r.main.border})`);
        }
        for (const [sel, want, bs] of r.others) {
          t.check(bs.length > 0, `${what}: ${sel} is on the page`);
          for (const b of bs) {
            // P4-B2a.1 item 2: every box draws its own background
            t.check(b.bg === want, `${what}: ${sel} is ${b.bg}, want ${want}`);
            if (style === 'card') t.check(b.shadow !== 'none' && b.bg !== r.root, `${what}: ${sel} is a card on the page ${r.root} (${b.bg}, ${b.shadow})`);
            else t.check(b.shadow === 'none' && b.border === '1px', `${what}: ${sel} is flat with a 1px border (${b.shadow}, ${b.border})`);
          }
        }
      }
      await page.close();
    }
  }

  // --- focus rings (P4-B2a item 6, D-P4-9) -----------------------------------
  // Every stop of 40 Tabs draws the theme's ring: solid, at least 2px, in
  // the ring colour — the code ring inside a code block. B1 left links,
  // <summary> and heading anchors on Chromium's `auto 1px`.
  for (const width of [1280, 360]) {
    for (const mode of ['light', 'dark']) {
      const page = await t.newPage({ colorScheme: 'light', viewport: { width, height: 800 } });
      await page.addInitScript((m) => { try { localStorage.setItem('clogem-mode', m); } catch (e) {} }, mode);
      await page.goto(t.url('pages/c0nta1/index.html'), { waitUntil: 'load' });
      // P4-B2b: at 1280 the article is 632 px wide and no code block of the
      // page scrolls, so none is a Tab stop; one long line makes the first
      // block scroll again
      if (width === 1280) {
        await page.evaluate(() => {
          const line = document.createElement('span');
          line.className = 'line';
          line.textContent = 'a long line that scrolls '.repeat(12);
          document.querySelector('pre.clogem-code > code').appendChild(line);
        });
      }
      const ring = await page.evaluate(() => {
        const probe = document.createElement('div');
        document.body.appendChild(probe);
        const read = (v) => { probe.style.color = `var(${v})`; return getComputedStyle(probe).color; };
        const r = { ring: read('--focusRing'), code: read('--codeFocusRing'), accent: read('--accent'), codeColor: read('--codeColor') };
        probe.remove();
        return r;
      });
      t.check(ring.ring === ring.accent && ring.code === ring.codeColor,
              `${width}px ${mode}: the ring is the accent and the code ring the code colour: ${JSON.stringify(ring)}`);
      const kinds = new Set();
      let bad = 0;
      for (let i = 0; i < 40; i++) {
        await page.keyboard.press('Tab');
        const f = await page.evaluate(() => {
          const e = document.activeElement;
          if (!e || e === document.body) return null;
          const c = getComputedStyle(e);
          return {
            what: e.tagName.toLowerCase() + (typeof e.className === 'string' && e.className ? '.' + e.className.split(' ')[0] : ''),
            style: c.outlineStyle, width: parseFloat(c.outlineWidth), color: c.outlineColor, inCode: !!e.closest('pre'),
            offset: c.outlineOffset, opacity: c.opacity,
          };
        });
        if (!f) continue;
        kinds.add(f.what);
        const want = f.inCode ? ring.code : ring.ring;
        // P4-B2a.1 item 5: a heading anchor is shown when focused, and the
        // scrollable <code> draws its ring inset (its <pre> clips)
        const ok = f.style === 'solid' && f.width >= 2 && f.color === want
          && (f.what !== 'a.header-anchor' || f.opacity === '1')
          && (!(f.inCode && f.what.startsWith('code')) || f.offset === '-2px');
        if (!ok && bad++ < 5) t.check(false, `${width}px ${mode}, stop ${i + 1} (${f.what}): outline ${f.style} ${f.width}px ${f.color} offset ${f.offset}, opacity ${f.opacity}; want solid ≥2px ${want}`);
      }
      t.check(bad === 0, `${width}px ${mode}: every focus stop draws the ring (${bad} did not)`);
      t.check(kinds.size >= 4, `${width}px ${mode}: Tab reached links, summaries, buttons…: ${[...kinds].join(' ')}`);
      t.check(kinds.has('button.clogem-copy'), `${width}px ${mode}: a copy button was among the stops`);
      t.check(kinds.has('a.header-anchor') && [...kinds].some((k) => k.startsWith('code')),
              `${width}px ${mode}: a heading anchor and a scrollable <code> were among the stops`);
      await page.close();
    }
  }

  // --- print -------------------------------------------------------------------
  // P4-B2b: at two sizes, each checking the controls that are drawn there —
  // asserted on screen first, so a control the screen already hides cannot
  // pass for one print hides (computed display, not checkVisibility(),
  // which is false for the drawer's display: contents)
  const display = (sels) => Object.fromEntries(sels.map((s) =>
    [s, document.querySelector(s) ? getComputedStyle(document.querySelector(s)).display : 'absent']));
  for (const [viewport, onScreen] of [[{ width: 1280, height: 720 }, ['.clogem-langs__toggle', '.clogem-navbar__drawer']],
                                      [{ width: 360, height: 740 }, ['.clogem-navbar__toggle']]]) {
    const page = await t.newPage({ colorScheme: 'dark', viewport });
    await page.addInitScript(() => { try { localStorage.setItem('clogem-mode', 'dark'); } catch (e) {} });
    await page.goto(url, { waitUntil: 'load' });
    const screen = await page.evaluate(display, onScreen);
    for (const [s, d] of Object.entries(screen)) t.check(d !== 'none' && d !== 'absent', `${viewport.width}px: ${s} is drawn on screen (display ${d})`);
    await page.emulateMedia({ media: 'print' });
    const shown = await page.evaluate(display,
      ['.clogem-navbar', '.clogem-sidebar', '.clogem-toc', '.clogem-mode', '.clogem-search', '.clogem-comments',
       '.clogem-prev-next', '.header-anchor', ...onScreen]);
    for (const [s, d] of Object.entries(shown)) t.check(d === 'none' || d === 'absent', `${viewport.width}px: print hides ${s} (display ${d})`);
    const bg = await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
    t.check(bg === 'rgb(255, 255, 255)', `print uses the light palette even in dark mode: ${bg}`);
    await page.close();
  }
  // P4-B2a: print draws no box, in either page style — no shadow, no
  // border, and no transition into that (P4-B2a.1 item 4). Only the boxes:
  // a highlighted code line keeps its inset accent bar, its only marker
  // when backgrounds are not printed (P4-B2a.1 item 1).
  const printBoxes = ['.clogem-main', '.clogem-home-right > section', '.clogem-post-card', '.clogem-main--posts > .clogem-content'];
  for (const rel of [article, 'zh-Hans/index.html', 'page/2/index.html', 'pages/eed953/index.html']) {
    for (const style of ['card', 'line']) {
      const page = await t.newPage({ colorScheme: 'light' });
      await page.goto(t.url(rel), { waitUntil: 'load' });
      await page.evaluate((style) => {
        const h = document.documentElement;
        h.className = h.className.replace(/\btheme-style-\S+/, `theme-style-${style}`);
      }, style);
      await page.emulateMedia({ media: 'print' });
      const r = await page.evaluate((sels) => {
        const name = (e) => e.tagName.toLowerCase() + (e.className && typeof e.className === 'string' ? '.' + e.className.split(' ').join('.') : '');
        const probe = document.createElement('div');
        document.body.appendChild(probe);
        probe.style.color = 'var(--accent)';
        const accent = getComputedStyle(probe).color;
        probe.remove();
        const boxes = sels.flatMap((s) => [...document.querySelectorAll(s)]).map((e) => {
          const c = getComputedStyle(e);
          return { what: name(e), shadow: c.boxShadow, transition: c.transitionDuration,
                   border: [c.borderTopWidth, c.borderRightWidth, c.borderBottomWidth, c.borderLeftWidth].join(' ') };
        });
        // what prints: a hidden element's shadow is not drawn
        const shadowed = [...document.querySelectorAll('*')]
          .filter((e) => e.checkVisibility() && getComputedStyle(e).boxShadow !== 'none');
        return {
          accent, boxes,
          others: shadowed.filter((e) => !e.matches('pre.clogem-code .line.hl')).map(name),
          hl: [...document.querySelectorAll('pre.clogem-code .line.hl')].map((e) => getComputedStyle(e).boxShadow),
        };
      }, printBoxes);
      const what = `print, ${rel} ${style}`;
      t.check(r.boxes.length > 0, `${what}: a box to check`);
      for (const b of r.boxes) {
        t.check(b.shadow === 'none' && b.border === '0px 0px 0px 0px' && /^0s(, 0s)*$/.test(b.transition),
                `${what}: ${b.what} is no box (shadow ${b.shadow}, border ${b.border}, transition ${b.transition})`);
      }
      t.check(r.others.length === 0, `${what}: no shadow but a highlighted line's, yet ${r.others.slice(0, 5).join(', ')}`);
      if (rel.includes('eed953')) {
        const bar = `${r.accent} 3px 0px 0px 0px inset`;
        t.check(r.hl.length > 0 && r.hl.every((x) => x === bar), `${what}: each highlighted line keeps its bar ${bar}: ${r.hl.join(' | ')}`);
      }
      await page.close();
    }
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
