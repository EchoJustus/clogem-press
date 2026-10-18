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
| `overflow.test.mjs` | At 320 and 360 px wide, every HTML page has `document.documentElement.scrollWidth <= innerWidth`. |
| `dev.test.mjs` | `bb dev` (Phase 4 Task D) on a temp copy of the demo with `--no-search`: an edit to an article is visible within 3 s; breaking its front matter shows the build-error overlay within 3 s, naming the file and covering the viewport, with `window.clogem` intact; the fix clears it within 3 s. Skips (passes, saying why) when `bb` is not on `PATH` or `bb dev` is still starting after 90 s; a `bb dev` that exits before it is ready is a failure that prints its output (`dev-harness.mjs`), and so is one that watches by polling while `CLOGEM_FSWATCHER` is set, as on CI. |
| `search-swap.test.mjs` | Search across `bb dev`'s background index swap (P4-D.1 item 1), with real Pagefind (`CLOGEM_PAGEFIND` on CI): edit a page, initialise search in the page dev's reload loaded, before the swap, then search after it — no request under `/pagefind/` fails and every term finds results; a reload then sees the new index. Same skip and failure rules as `dev.test.mjs`, and one skip of its own: initialising search between the reload and the swap is a race against the index run (~0.4 s on the demo), so after three edits that all lost it, the test prints `skip: could not initialise search before the swap in 3 attempts` and passes without having checked the swap — a skip, not a pass. |
| `modes.test.mjs` | Colour modes and page styles (Phase 4 B1, B2a). Expected colours are what the served stylesheets compute, read in the browser (not re-parsed). The root background of the first animation frame for stored, OS, blocked-storage, garbage and no-JS cases; the toggle by keyboard (Tab, Enter, arrows, Escape) and its stored choice; the toggle named for the current mode (en and zh-Hans, after a keyboard choice, after another tab's); under `Content-Security-Policy: script-src 'self'`, `js/mode.js` applies the stored mode and follows the OS in auto; a live OS change in auto mode; the Pagefind trigger and dialog on `--mainBg`, and its error, shadow and backdrop variables the theme's, in dark, read and light; card and line (switched in-page) on an article, the zh-Hans home, a post list, a category page and a catalogue in light, dark, read and no-JS auto under a dark OS; 40 Tab stops at 1280 and 360 px each draw the theme's focus ring; print hides the chrome and draws no shadow; `overrides/custom.css` applies, last. |
| `code.test.mjs` | Code blocks (Phase 4 C): every highlighted token's computed colour, the line numbers and the language label reach 4.5:1 on the background behind them in light, dark, read and auto-under-a-dark-OS; line numbers are drawn but are not text; the copy button puts exactly the fenced source on the clipboard, shows `check` and announces "Copied", then resets, and is labelled in the page's language; no button without JS; wide code scrolls inside its box at 320 px. Needs a build that highlights (`CLOGEM_CHROMA` on CI). |

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
