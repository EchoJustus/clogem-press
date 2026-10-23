# Browser tests

Playwright tests over a **built** site (Phase 4, D-P4-18). They run in CI's
`browser` job; nothing here ships, and `bb test` never loads it (its test
namespaces are listed in `src/clogem/test_runner.clj`, and nothing here is
Clojure).

`run.mjs` runs every `*.test.mjs` in this directory, in one headless
Chromium, against a site that is already being served. A test file
default-exports `async function (t)`; see the header of `run.mjs` for what
`t` carries. Third-party requests (giscus, …) are aborted, so no test
depends on the network.

| Test | What it asserts |
|---|---|
| `overflow.test.mjs` | At 320 and 360 px wide, every HTML page has `document.documentElement.scrollWidth <= innerWidth`; where the menu toggle renders (P4-B2b), also with the drawer and every `<details>` in it open, and the drawer's own `scrollWidth <= clientWidth`. |
| `dev.test.mjs` | `bb dev` (Phase 4 Task D) on a temp copy of the demo with `--no-search`: an edit to an article is visible within 3 s; breaking its front matter shows the build-error overlay within 3 s, naming the file and covering the viewport, with `window.clogem` intact; the fix clears it within 3 s. Skips (passes, saying why) when `bb` is not on `PATH` or `bb dev` is still starting after 90 s; a `bb dev` that exits before it is ready is a failure that prints its output (`dev-harness.mjs`), and so is one that watches by polling while `CLOGEM_FSWATCHER` is set, as on CI. |
| `search-swap.test.mjs` | Search across `bb dev`'s background index swap (P4-D.1 item 1), with real Pagefind (`CLOGEM_PAGEFIND` on CI): edit a page, initialise search in the page dev's reload loaded, before the swap, then search after it — no request under `/pagefind/` fails and every term finds results; a reload then sees the new index. Same skip and failure rules as `dev.test.mjs`, and one skip of its own: initialising search between the reload and the swap is a race against the index run (~0.4 s on the demo), so after three edits that all lost it, the test prints `skip: could not initialise search before the swap in 3 attempts` and passes without having checked the swap — a skip, not a pass. |
| `modes.test.mjs` | Colour modes and page styles (Phase 4 B1, B2a). Expected colours are what the served stylesheets compute, read in the browser (not re-parsed). The root background of the first animation frame for stored, OS, blocked-storage, garbage and no-JS cases; the toggle by keyboard (Tab, Enter, arrows, Escape) and its stored choice; the toggle named for the current mode (en and zh-Hans, after a keyboard choice, after another tab's); under `Content-Security-Policy: script-src 'self'`, `js/mode.js` applies the stored mode and follows the OS in auto; a live OS change in auto mode; the Pagefind trigger and dialog on `--mainBg`, and its error, shadow and backdrop variables the theme's, in dark, read and light; card and line (switched in-page) on an article, the zh-Hans home, a post list, a category page and a catalogue in light, dark, read and no-JS auto under a dark OS; 40 Tab stops at 1280 (one code block given a long line, so it scrolls) and 360 px each draw the theme's focus ring; print, at 1280 and at 360 px with each control first asserted drawn on screen, hides the chrome (the menu toggle, the drawer, the language toggle and the sidebar's collapse button included) and draws no box (no shadow, border or transition, in both styles, on four pages) but keeps a highlighted code line's accent bar; every `--pf-*` the theme maps beats Pagefind's `:root` (a sentinel there never shows through) in light, dark and read; every box draws its own background; a home's post cards sit on the page, their column no box; a `$&` in a mode label stays literal; a focused heading anchor is shown and a scrollable `<code>` draws its ring inset; `overrides/custom.css` applies, last. |
| `layout.test.mjs` | The navbar and page chrome (Phase 4 Task B2b), on the demo. Smooth scrolling is on, so positions are read under `reducedMotion: 'reduce'` or once `scrollY` has been still for 10 frames, and scrolls are `behavior: 'instant'`. The navbar is `--navbarHeight` tall and one row (1024–1440 px, and every 10 px from 801 to 1600); a fresh load of `…/#id`, a TOC click and a `<p id>` target land below it, the home's `h1` included; the TOC's offset follows `--navbarHeight: 6rem`; 30 Shift+Tab stops at 1280 never sit under it; scrolling is smooth, and instant under reduced motion. The language dropdown by keyboard, pointer and without JS; the icon-only search trigger at 801–1024, named in the page's language; a ten-item nav stays inside the navbar. Columns: sidebar \| main at 900 and 1024, sidebar \| main \| TOC at 1280 and 1440 with `article.clogem-article` ≥ 600 px (beside a 17 px scrollbar too), the TOC `display: none` below 80rem, a 952 px main without a sidebar, a `--single --toc` page's TOC above 64rem, and a 60-entry TOC that scrolls and stays clear of the back-to-top band. At 320, 360 and 768: one row, the navbar sticks, only the toggle, brand, search and mode are controls while closed, 15 Tabs never enter the closed drawer; open with everything in it, nothing overflows and the drawer fits the viewport; the mode menu fits at 320; nothing animates under reduced motion; the focus ring in the drawer. Without JS (static, every link shown, the sidebar after the article, no overflow with the dropdowns open), without `(scripting: enabled)`, and with `js/nav.js` aborted (the toggle still opens the drawer). `nav.js`: the sidebar moved in, Tab through every link, Escape, a click outside, two clicks on the toggle, a followed link, a resize to 1280 and back, one aside throughout, a hidden toggle revealed, and Escape or a click outside closing a dropdown at 1280. The home's text card pads 16px at 320 and 360, as the article's main. Sidebar collapse at 1280: a click hides the aside and widens main by ≥ 200 px, Enter restores it with focus kept, 40 Tabs never enter the collapsed aside, the chevrons (mirrored in RTL), no transition under reduced motion; the server's marker (added by `page.route`) loads collapsed with JS, and without JS or with `nav.js` aborted the sidebar shows and the button does not; not drawn at 360, where a collapsed state still leaves every sidebar link in the drawer, and kept across a resize. Page buttons at 1344, 1407, 1408 and 1440: both shown at the edges with prev/next's hrefs, centred, 50 or 80 px wide, opacity 0.55 (1 hovered), meeting no sidebar, main or TOC box, every visible sidebar, content and TOC link's centre still the link; never a Tab stop; hidden at 1343, 900 and 360; one on the catalogue. |
| `code.test.mjs` | Code blocks (Phase 4 C): every highlighted token's computed colour, the line numbers and the language label reach 4.5:1 on the background behind them in light, dark, read and auto-under-a-dark-OS; line numbers are drawn but are not text; the copy button puts exactly the fenced source on the clipboard, shows `check` and announces "Copied", then resets, and is labelled in the page's language; no button without JS; wide code scrolls inside its box at 320 px. Needs a build that highlights (`CLOGEM_CHROMA` on CI). |
| `analytics.test.mjs` | The analytics loader (Phase 4 Task F): builds temp copies of the demo, one per provider (GA4 with `:consent-default :denied`, Plausible's new and legacy scripts, Umami with and without `:respect-dnt false`), with `bb build --no-search --no-highlight`, and serves each `dist/` through `page.route` at the demo's `:site :url`, `https://clogem-demo.example`, with every provider script fulfilled by a stub that records `document.currentScript`'s attributes. It loads on that host and not at `http://127.0.0.1:8799`; not under Do Not Track or Global Privacy Control, and under both with `:respect-dnt false`; GA4's `dataLayer[0]` is the consent default, before `js` and `config`, and every entry is an `Arguments` object; `plausible.o` is the camelCased `:init`; the legacy stub sees `data-domain` and the Umami stub `data-website-id`; no request completes outside the routed origins. Skips (passes, saying why) when `bb` is not on `PATH`. |

## Running locally

Playwright is pinned to an exact version in `package.json` and
`package-lock.json`; install with `npm ci`, never `npm install`.

```sh
# 1. build the demo, as CI does (Pagefind optional: --no-search builds without it)
cd examples/demo-site
bb --config ../../bb.edn build

# 2. serve it on the address the runner expects
python3 -m http.server --bind 127.0.0.1 8000 --directory dist &

# 3. install Playwright and its Chromium, then run
cd ../../test/browser
npm ci
npx playwright install --with-deps chromium   # or see below
npm test                                      # = node run.mjs; `node run.mjs overflow` runs one file
```

`CLOGEM_BROWSER_BASE_URL` (default `http://127.0.0.1:8000/`) and
`CLOGEM_BROWSER_DIST` (default `examples/demo-site/dist`) point the runner
elsewhere.

### A preinstalled Chromium

Playwright looks for its browsers in `PLAYWRIGHT_BROWSERS_PATH` (default
`~/.cache/ms-playwright`), and each Playwright version wants one exact
Chromium revision there (1.63.0 wants `chromium-1243`). A machine that
already has a different revision — a sandbox with
`PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers` holding `chromium-1194`, say —
need not download another: skip `npx playwright install` and point the
runner at the executable instead.

```sh
CLOGEM_CHROMIUM=/opt/pw-browsers/chromium-1194/chrome-linux/chrome npm test
```

CI always uses the pinned download.
