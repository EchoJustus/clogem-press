# clogem-press

**A vdoing-class knowledge base + blog + docs generator for the Clojure
ecosystem, with a babashka CLI.**

clogem-press reimplements the *content conventions* of
[vuepress-theme-vdoing](https://github.com/xugaoyi/vuepress-theme-vdoing) —
numbered directories that become a sidebar, auto front matter with stable random
permalinks, category/tag/archive indexes, catalogue pages — on a Clojure stack
with **zero resolved dependencies**. Markdown parsing, templating, YAML, HTTP,
JSON and XML are all built into babashka.

It adds the thing vdoing has no story for at all: **five-language content**,
built on one rule — *an article's identity is its permalink, and every language
version shares it.*

The full design rationale, with every external claim verified against primary
sources, is in [DESIGN.md](DESIGN.md); thirteen research reports back it in
[`research/`](research/).

> **Status: Phase 3 complete (0.2.0).** The generator scans a content tree,
> resolves identity groups, normalizes front matter, and renders a five-language
> site with vdoing's core surface: collapsible sidebar tree, category / tag /
> archive index pages, a paginated blog homepage with sticky posts, breadcrumbs
> and prev/next, catalogue pages, `@pages/` auto-creation, a TOC bar with
> scroll-spy, and the eight markdown containers plus `cardList` /
> `cardImgList`. Phase 3 adds canonical and hreflang links, per-language Atom
> feeds and a sitemap, Pagefind search with one index per language, a
> remembered language choice with an "also available in …" banner, and giscus
> comments with one thread per article across its languages. Syntax
> highlighting and the full theme are Phase 4 — see
> [the implementation plan](DESIGN.md#8-implementation-plan).

## Install

babashka 1.13+ is the only prerequisite.

```bash
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
git clone https://github.com/EchoJustus/clogem-press
```

## Use

clogem-press is designed to run **from your content directory** against a
generator checkout elsewhere:

```bash
cd my-site
bb --config ../clogem-press/bb.edn build
```

`:paths` resolve relative to that `bb.edn` while the working directory stays
yours, which is what lets a content repo's CI check out a pinned generator tag
and run it against local content with no packaging step.

| Task | What it does |
|---|---|
| `bb build` | Render the site to `dist/`. Writes missing front matter unless `--no-write`. Renders every page in memory first (in parallel; `CLOGEM_JOBS=1` for one thread) and writes only when all of it succeeded, file by file and atomically (temp file and rename; in place when the OS refuses the temp file or the rename), skipping files whose bytes are unchanged — a page that fails to render, or a site asset that cannot be read, leaves `dist/` and `permalinks.edn` as they were (an I/O error while writing, such as a full disk, or a Pagefind failure can still leave some files updated). Runs Pagefind last under `:search {:provider :pagefind}`; `--no-search` builds without search (no index, no search UI). Removes `.html` files in the output directory that the build did not write, so the output directory must be a directory of its own. |
| `bb dev` | Build, serve on 127.0.0.1:1888, rebuild on change, push a reload to the browser. See [Working locally](#working-locally). |
| `bb serve` | Serve an already-built directory at the site's `:base`, no watching. `--host`, `--port`, `--base`. |
| `bb doctor` | Report content problems without building. Exits non-zero on errors. |
| `bb fm-fix` | Front-matter normalization only — what CI runs before the build. `--dry-run` to preview. |
| `bb clean` | Remove the output directory. |
| `bb fetch-tool` | Fetch, sha256-verify and cache a pinned binary — Pagefind by default, `--tool chroma` or `--tool fswatcher` — and print its path. |
| `bb test` | Run the test suite. The browser tests over a built site are separate: `test/browser/README.md`. |

Every task takes `--help`.

## Working locally

```bash
cd my-site
bb --config ../clogem-press/bb.edn dev            # http://127.0.0.1:1888/
```

`bb dev` builds, serves `dist/` and rebuilds on every change to `content/`,
`assets/`, `i18n/`, `overrides/`, `site.edn` and the generator's own theme
resources. Saves are collected until 100 ms pass quietly, so a burst of writes
is one rebuild, and editor temp files (`.#x`, `x~`, `4913`, `*.swp`, `*.tmp`)
are ignored. A rebuild of the demo takes about 0.3 s; the page reloads about
half a second after a save, and a stylesheet-only change swaps the CSS in
place. Search is re-indexed in the background after the reload; the previous
index keeps working meanwhile.

When a rebuild fails, the terminal says why and the open page shows the error
in an overlay over the last good build; saving a fix clears it. Escape hides
it.

| Flag | |
|---|---|
| `--port 1888`, `--host 127.0.0.1` | Where to listen. Both servers listen on loopback only; `--host 0.0.0.0` to reach it from another device. |
| `--base /project/` | The site's base path (default: `:site :base`). `dist/` is served there, and `/` redirects to it, so a project site's links work locally. `bb serve --base` too. |
| `--poll`, `--interval 500` | Watch by polling instead of the fswatcher pod. Not usually needed: dev proves the pod delivers events at startup and polls on its own when it does not (`--probe-ms 3000` is that check's budget). |
| `--no-search` | Build without search (no index, no search UI). |
| `--no-write` | Never write front matter or `permalinks.edn` back to the source tree (dev writes them by default, as `build` does). |
| `--reload-code` | When working on the generator: also reload the theme's `.clj` code on change. |

The fswatcher pod is fetched on first use like Pagefind — pinned, sha256-verified
and cached (`bb fetch-tool --tool fswatcher`); offline, or on a platform it has
no build for (Windows on ARM), dev polls. With the pod, a `site.edn` change that
moves `:content :dir` needs a restart; with `--poll` it does not.

## Conventions in one screen

```
content/
├── index.md                                   home page
├── 01.Guide/10.Basics/02.conventions.md       en   ┐
├── 01.Guide/10.Basics/02.conventions.zh-Hans.md    ├ ONE article,
├── 01.Guide/10.Basics/02.conventions.zh-Hant.md    ┘ three variants
├── 01.Guide/20.Advanced/01.tamil-only.ta.md   an article with no English version
├── 02.Notes/10.Local/01.hawker-guide.ms.md
├── 00.Catalogue/01.Guide.md                   pageComponent: Catalogue → card grid of 01.Guide
├── _posts/2026-08-01-hello.md                 blog posts, sorted by date
└── @pages/                                    auto-created index pages
```

- **Numbered directories** become the sidebar. The number is everything before
  the first dot; the title is what follows. Gaps of 10 are recommended.
- **A language suffix** on the filename makes a variant. The test is closed over
  your configured `:langs`, so `03.Vue.js.md` is still a file titled `Vue.js`.
  Matching is case-insensitive; the configured spelling is what gets emitted.
- **A near-miss language code is a hard error**, with a suggestion —
  `01.a.zh-Hanz.md` says *did you mean `zh-Hans`?* A silent misparse would cost
  both a wrong title and a lost translation link.
- **Files sharing a number *and* a base name are language variants of one
  article** — one sidebar entry. Files sharing a number with *different*
  identities are a collision and a hard error.
- **Front matter is auto-filled surgically**: missing keys are inserted into the
  existing block, never reserialized. Manual values are never overwritten, so a
  second pass produces no diff.
- **A new variant inherits its permalink** from its identity group rather than
  minting one. Write the translation; the build files it under the existing
  article.
- **Every index holds article identities**, so an article with three language
  versions is one sidebar leaf, one category row, one archive entry — in every
  language, in the same order. A row shows the reader's own version when it
  exists, else the primary with a fallback marker.
- **Hyphenated words are titles, not language tags.** `02.api-design.md` is an
  article titled `api-design`; only confusables (`zh-CN`), one-edit misspellings
  (`zh-Hanz`) and configured-primary-plus-region shapes (`en-us`) are errors.

`examples/demo-site/` exercises all of it and is built by CI on every push,
which makes it the executable specification rather than prose that can drift.

## URLs

```
/pages/a1b2c3/            the article's PRIMARY variant — the first language in
                          :priority that THIS article has, not a site default
/zh-Hans/pages/a1b2c3/    a non-primary variant
/zh-Hans/                 that language's home
/categories/  /categories/<slug>/  /categories/<slug>/page/2/
/tags/  /tags/<slug>/  /archives/  /page/2/
                          index pages and pagination: bare for the site-default
                          language, /zh-Hans/… for every other one
```

A Tamil-only article lives at `/pages/d4e5f6/`, in Tamil. Every article is
reachable at its identity URL, so sidebar entries, index rows and links never
need to know which languages exist.

## Theme

**Colour modes.** Four, in vdoing's order: *Follow system*, *Light*, *Dark*
and *Reading* (a sepia page with a dark code block). `:theme :default-mode`
picks the one a first visit sees; the navbar's mode button lets the reader
choose, and the choice is remembered in `localStorage` (`clogem-mode`). The
button is a menu button that opens four choices: Enter or Space opens it, the
arrow keys move, Escape closes it. It is revealed by JavaScript, so a reader
without JS never meets a dead control; such a reader gets `:default-mode`,
with *Follow system* following the OS through a media query. The palette is
WCAG AA in every mode — text, muted text and the accent at 4.5:1 or better on
every surface, which `bb test` checks against `theme.css` — and links inside
prose are underlined. Pagefind's search dialog and giscus follow the mode;
printing uses the light palette and drops the chrome.

The mode and page-style classes are on `<html>`, unqualified:
`.theme-mode-dark`, `.theme-style-card`. (0.2.0 put them on `<body>`; CSS
written as `body.theme-mode-dark` must drop `body`.)

**Your own CSS.** Put it in `overrides/custom.css` in your site. It ships as
`dist/clogem/overrides/custom.css` with a cache-busting `?v=` and is linked
**last**, after the theme's and Pagefind's stylesheets, so a rule of the same
specificity wins. `bb dev` hot-swaps it without a reload.

**Icons** come from one sprite, `dist/clogem/icons.svg`, built from vendored
[Lucide](https://lucide.dev) (ISC; Feather-derived icons also MIT) and, for
brands, [Simple Icons](https://simpleicons.org) (CC0) and
[Tabler](https://tabler.io/icons) (MIT). The licence notices are at the top of
the sprite; sources, versions and hashes are in
`src/clogem/theme/resources/icons/MANIFEST.edn`.

**Content-Security-Policy.** Every page carries exactly one inline script,
the colour-mode script in `<head>`, and it is byte-identical on every page,
so one hash covers the site:

```
script-src 'self' 'sha256-aykGrfu05czJ6oIj+Xn+Qrjxa7JG8hF3RGl0W0liGmw='
```

`:i18n {:preference :redirect}` adds a second inline script, giscus needs
`https://giscus.app` in `script-src`, `frame-src` and `style-src`, and
Pagefind needs `worker-src 'self'` and `'wasm-unsafe-eval'` (DESIGN.md §6.8,
§11.2 items 42 and 49).

## Development

```bash
bb test                                             # 249 tests / 975 assertions
cd examples/demo-site && bb --config ../../bb.edn build
```

## License

EPL-2.0. See [LICENSE](LICENSE).
