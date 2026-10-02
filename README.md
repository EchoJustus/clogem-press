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

> **Status: Phase 2 complete (0.1.1).** The generator scans a content tree,
> resolves identity groups, normalizes front matter, and renders a multilingual
> site with vdoing's core surface: collapsible sidebar tree, category / tag /
> archive index pages, a paginated blog homepage with sticky posts, breadcrumbs
> and prev/next, catalogue pages, `@pages/` auto-creation, a TOC bar with
> scroll-spy, and the eight markdown containers plus `cardList` /
> `cardImgList`. Search, syntax highlighting, hreflang/feeds and the full
> theme are Phases 3–4 — see
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
| `bb build` | Render the site to `dist/`. Writes missing front matter unless `--no-write`. Runs Pagefind last under `:search {:provider :pagefind}`; `--no-search` builds without search (no index, no search UI). Removes `.html` files in the output directory that the build did not write, so the output directory must be a directory of its own. |
| `bb dev` | Build, serve on :1888, rebuild on change, push an SSE reload. `--poll` if inotify is unreliable; `--no-search` builds without search. |
| `bb serve` | Serve an already-built directory, no watching. |
| `bb doctor` | Report content problems without building. Exits non-zero on errors. |
| `bb fm-fix` | Front-matter normalization only — what CI runs before the build. `--dry-run` to preview. |
| `bb clean` | Remove the output directory. |
| `bb fetch-tool` | Fetch, sha256-verify and cache the pinned Pagefind binary; print its path. |
| `bb test` | Run the test suite. |

Every task takes `--help`.

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

## Development

```bash
bb test                                             # 249 tests / 975 assertions
cd examples/demo-site && bb --config ../../bb.edn build
```

## License

EPL-2.0. See [LICENSE](LICENSE).
