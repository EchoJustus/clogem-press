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
