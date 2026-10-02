# Client-Side Search + Syntax Highlighting for clogem-press

All version/date claims below were verified against primary sources (npm registry API, PyPI API, GitHub tags/releases, official docs) in August 2026. Additionally, the two recommended binaries (Pagefind extended, Chroma) were **downloaded and executed locally** in this Linux sandbox to prove the "no Node, invokable from babashka" claim empirically.

---

## Part A — Static-Site Search (no server)

### Comparison table

| Library | Latest ver. | Published | Maintenance | CJK/Chinese | Index model | UI included |
|---|---|---|---|---|---|---|
| **Pagefind** | 1.5.2 | 2026-04-12 | Very active (1.5.0 Apr 2026 added new Component UI) | **Yes — built-in segmentation in `pagefind_extended`** | Build-time, chunked, lazy-loaded | Yes: 3 UIs + CSS vars |
| **FlexSearch** | 0.8.212 | 2025-09-06 | Active-ish (0.8 rewrite 2025) | **Yes — built-in `Charset.CJK` encoder preset** | In-memory JS, built client-side or via node | No |
| **MiniSearch** | 7.2.0 | 2025-09-16 | Active | No built-in — needs custom tokenizer (`Intl.Segmenter`) at index *and* query time | In-memory JS | No |
| **Fuse.js** | 7.5.0 | 2026-07-13 | Active | Incidental (fuzzy substring works on Chinese, no tokenization) | No index — linear scan of all docs per keystroke | No |
| **Lunr.js** | 2.3.9 | **2020-08-19** | **Frozen ~6 years** | Via separate `lunr-languages` (`lunr.zh` uses `@node-rs/jieba` in Node, `Intl.Segmenter` in browser — throws if unavailable) | In-memory; pre-serializing index requires a JS runtime | No |
| **Stork** | 1.6.0 | ~2022 | **Dead** — README: "Project update: I'm winding down my work with Stork" (James Little). Not archived, but do not adopt | Poor | Rust binary + wasm (Pagefind-like model) | Basic |

### Pagefind details (verified against pagefind.app docs, GitHub releases, and local execution)

- **What it is**: a fully static search engine written in Rust. It runs *after* the SSG, indexing the generated HTML directory — a perfect post-build step for our `docs/` output folder. Zero coupling to the SSG's internals.
- **Installation paths** (per https://pagefind.app/docs/installation/): `npx pagefind`, `pip install 'pagefind[extended]'`, `cargo install pagefind`, or **precompiled standalone binaries from GitHub releases — no Node or Python required**. Binaries exist for x86_64/aarch64 Linux (musl, fully static), macOS (Intel + Apple Silicon), Windows.
- **CJK**: the **`pagefind_extended`** binary variant "includes specialized support for indexing Chinese and Japanese pages". Per https://pagefind.app/docs/multilingual/: Chinese (`zh-*`), Japanese, Korean get word segmentation (e.g. `每個月都` → `每個`/`月`/`都`) at both index and query time; language auto-detected from `<html lang>`, one index per language. Caveat: **no stemming for CJK** (irrelevant for Chinese in practice).
- **Empirically verified here**: downloaded `pagefind_extended-v1.5.2-x86_64-unknown-linux-musl.tar.gz` (58 MB binary), ran it with no runtime deps over two `lang="zh-CN"` HTML pages — it auto-detected `zh-cn`, emitted a per-language chunked index (`index/zh-cn_*.pf_index`, `fragment/zh-cn_*.pf_fragment`), printed only a benign "no stemming for zh-cn" note, finished in 0.26 s.
- **Client payload (measured, gzipped)**: `pagefind.js` 12 KB + wasm 66 KB + Default UI JS 29 KB (or Component UI 37 KB) + UI CSS ~14 KB raw. Index fragments load lazily per query. Official scaling claim: full-text search on a **10,000-page site under 300 KB total network payload** (typically ~100 KB) — index size does *not* force a monolithic download, unlike every JS library above.
- **UI**: three options — Default UI (`PagefindUI`), the new **Component UI** (v1.5.0+, search modal, better a11y), and a Modular UI for custom layouts. Theming via CSS custom properties (`--pagefind-ui-primary`, `--pagefind-ui-text`, `--pagefind-ui-background`, ...); docs explicitly describe **dark mode** by re-declaring the variables under your dark-mode class/media query. Built-in translated UI strings keyed off page language (Chinese included), custom translations supported. Also supports `data-pagefind-body`, `data-pagefind-meta`, filters, and weighted results — enough to replicate vdoing's `fulltext-search` UX and exceed it.
- **Babashka integration**: trivial — a bb task that (1) downloads the platform-appropriate `pagefind_extended` release tarball once (pin version + checksum, cache in `.cache/`), (2) `babashka.process/shell`s `pagefind_extended --site docs` after HTML generation. No Node anywhere.

### Why the others lose

- **Lunr**: core frozen at 2.3.9/Aug 2020; Chinese path is bolted on via a separate project; pre-building the index needs a JS runtime (violates no-Node), and building in-browser means shipping all documents JSON + paying index-build cost on every page load. Monolithic index grows linearly (easily MBs for a mature knowledge base).
- **MiniSearch**: nicest JS API and actively maintained, but no built-in CJK — you must wire `Intl.Segmenter` yourself and ship the whole serialized index or documents JSON.
- **FlexSearch**: fastest JS option and has a built-in CJK charset preset, but 0.7→0.8 had significant API churn, docs are sprawling, its "persistent index" story is server-DB oriented (only IndexedDB applies in-browser), and again the index is monolithic client-side.
- **Fuse.js**: fuzzy list filtering, not full-text search; O(n) scan of every document per keystroke, whole corpus shipped to client. Fine for filtering a tag list, wrong tool for site search.
- **Stork**: explicitly wound down by its author; final release era ~2022. Eliminated on maintenance grounds alone.

### Recommendation (Part A)

- **Primary: Pagefind (extended binary), pinned ≥ 1.5.2.** It is the only option that simultaneously: needs no Node, is a single static binary callable from a bb task, has first-class Chinese segmentation, scales sub-linearly in client bandwidth via chunked lazy indexes, and ships a themable dark-mode-ready UI. It's also what the wider SSG world (Hugo/Eleventy/Astro/Starlight communities) has converged on.
- **Fallback: FlexSearch 0.8 with `Charset.CJK`** — pure client-side, no build-time binary at all: the bb build emits a `search-docs.json` (title/headers/excerpt/url per page) during rendering, and the browser builds the index on first search interaction (web worker). Choose this only if shipping/downloading a third-party binary is unacceptable; accept the monolithic-index scaling cost and write a thin custom search box UI (no UI is included). (MiniSearch + `Intl.Segmenter` is the alternate fallback if API stability is valued over built-in CJK — `Intl.Segmenter` is now available in all evergreen browsers.)

---

## Part B — Syntax Highlighting

### Comparison table

| Option | Kind | Latest | Published | Maintenance | Clojure quality | Line numbers | Node needed? |
|---|---|---|---|---|---|---|---|
| **Chroma** | Build-time, Go single binary | v2.27.0 (+v3.0.0-alpha.5) | 2026-06-17 (alpha 2026-07-08) | **Very active** | Good (regex lexer auto-converted from Pygments' Clojure lexer; `clojure`/`clj`/`edn` aliases) | **Built-in** (`--html-lines`, `--html-lines-table`, `--html-highlight=N:M`, `--html-linkable-lines`) | **No** |
| Pygments | Build-time, Python CLI (`pygmentize`) | 2.20.0 | 2026-03-29 | Active | Very good (the reference lexer) | Yes (`-O linenos`) | No, but needs Python |
| Shiki | Build-time, JS/TextMate | 4.4.3 | 2026-08-10 | Very active | **Best** (VS Code TextMate grammar) | Via transformers | **Yes** — hard blocker |
| highlight.js | Client-side | 11.12.0 | 2026-08-12 | Active | Decent (core-shipped grammar) | **No** (intentionally unsupported; 3rd-party plugins stale) | No |
| Prism.js | Client-side | 1.30.0 | 2025-03-10 | **Maintenance mode** — "only accept security-relevant PRs" while v2 languishes (stalled since ~2022) | Basic | Official plugin (+ copy-to-clipboard plugin) | No |
| clygments | Clojure lib wrapping Pygments | 2.0.2 | copyright ends 2020 | **Dormant** | n/a | n/a | No, but JVM-only — **not babashka-compatible** |
| venantius/glow | Clojure lib (ANTLR) | ~0.1.x | years stale | **Dormant** | Clojure-only | No | No, but JVM/ANTLR — **not babashka-compatible**, and can't highlight bash/yaml/json/etc. |

### Chroma details (verified by local execution of the v2.27.0 release binary)

- Single 8.4 MB static binary from https://github.com/alecthomas/chroma/releases (linux/macos/windows). Very active: v2.24→v2.27 all shipped Apr–Jun 2026; v3 alphas in July 2026.
- 200+ languages (lexers are XML definitions auto-converted from Pygments via `pygments2chroma_xml.py`, so coverage/quality tracks Pygments); **all Pygments styles converted** including `github`, `github-dark`, `dracula`, `onedark` (gallery: https://xyproto.github.io/splash/docs/).
- **Verified command**: `chroma --lexer clojure --html --html-only --html-prefix=hl- --html-lines sample.clj` → emitted a clean class-based HTML fragment (`hl-kd` for `defn`, `hl-nv`, `hl-s`, `hl-p`, per-line `hl-line`/`hl-ln` spans with line numbers). `chroma --style=github --html-styles` → emitted the full stylesheet for that style. Full verified flag set: `--html-inline-styles`, `--html-prefix`, `--html-lines`, `--html-lines-table`, `--html-lines-style`, `--html-highlight=N[:M]`, `--html-base-line`, `--html-linkable-lines`, `--html-styles`, `--html-all-styles`, `--html-only`, `--fail`.
- One quirk observed: in our run, `--html-styles` combined with `--html-prefix=hl-` still emitted CSS under the default `.chroma` prefix — the implementation should either post-process the generated CSS (one string replace in bb) or verify flag interaction; token-class HTML honored the prefix correctly.
  - **Correction (Phase 4, DESIGN.md §11.3 item 10):** not a bug. `--html-styles` honours `--html-prefix` only when `--html` is also passed. Verified with Chroma 2.27.0: `chroma --style=github --html-styles --html-prefix=hl-` prints `.chroma .err {…}`; `chroma --style=github --html --html-styles --html-prefix=hl-` prints `.hl-chroma .hl-err {…}`. No post-processing is needed.
- v2.27.0's release note is itself about light/dark: it added `WithModeClasses` "light/dark theme switching" output (library API; check CLI exposure — if absent, the two-stylesheet approach below covers it regardless).

### Recommended approach (Part B)

**Build-time highlighting with the Chroma CLI binary, invoked from babashka** — this mirrors what VuePress 1/vdoing does (Prism at build time in Node) but with zero Node:

1. bb task downloads the pinned Chroma release binary (same pattern/cache dir as Pagefind).
2. During markdown rendering, each fenced code block is piped through `babashka.process` → `chroma --lexer <lang> --html --html-only --html-lines`, with a **content-hash cache** (`(hash [lang code])` → HTML fragment on disk) so warm builds spawn ~0 processes. Cold-build cost is ~5–10 ms/block — hundreds of blocks stay in seconds; unknown languages fall back to a plain escaped `<pre>` (Chroma's `--fail` flag makes detection easy).
3. **Dark/light via CSS variables**: run `chroma --style=github --html-styles` and `chroma --style=github-dark --html-styles` once at build; transform the two stylesheets into one sheet where each token class reads `var(--code-<token>)` and the variables are defined on `:root` (light values) and `html[data-theme="dark"]` / `@media (prefers-color-scheme: dark)` (dark values). This is a mechanical string transform in bb over Chroma's regular CSS output, and matches vdoing's theme-toggle model exactly.
4. **Line numbers**: `--html-lines` (or `--html-lines-table` to keep numbers unselectable/copy-clean).
5. **Copy button**: not a highlighter concern anywhere — no build-time highlighter provides one. The SSG template wraps each block in a `<div class="code-block">` with a `<button>`, plus ~10 lines of vanilla `navigator.clipboard.writeText` JS. (This is also how vdoing does it.)

**Fallback**: client-side **highlight.js 11.x** with a custom bundle (clojure + the handful of languages actually used, ~30–50 KB) and two CSS themes toggled with the site theme — zero build-time tooling, at the cost of a runtime flash/JS dependency and no sane line numbers. 

**Rejected**: Shiki (best output quality — TextMate grammars, native dual-theme CSS variables via `--shiki-dark` — but hard-requires a JS runtime at build, violating the no-Node constraint; revisit only if a Node escape hatch is ever accepted); Prism (maintenance mode with v2 stalled for 4+ years — wrong horizon for a new project); Pygments/`pygmentize` (fine tool, but adds a Python runtime dependency to every build machine for no benefit over Chroma, since Chroma's lexers/styles are converted from Pygments anyway); clygments (dormant since ~2020, JVM-only, unusable from babashka); venantius/glow (dormant, JVM/ANTLR, Clojure-only — a docs site also needs bash/yaml/json/edn/js highlighting).

### Cross-cutting notes for the design doc

- Both recommended tools (Pagefind extended, Chroma) follow the identical integration pattern: *pinned GitHub-release static binary, downloaded+cached by a bb task, invoked via `babashka.process`, operating on/for the generated HTML*. One "fetch-tool" helper in the build covers both (platform detection: linux/macos × amd64/aarch64; both projects publish all four).
- Order of operations: render markdown → Chroma-highlight code blocks (during render) → write HTML to `docs/` → run `pagefind_extended --site docs` as the final build step → deploy `docs/` to GitHub Pages. Pagefind's output lands in `docs/pagefind/` and is plain static files, fully compatible with GitHub Pages.
- CI note: GitHub Actions Linux runners run both musl/static binaries with no setup (verified here on a plain Linux container).

## Sources
- https://pagefind.app/
- https://pagefind.app/docs/multilingual/
- https://pagefind.app/docs/installation/
- https://pagefind.app/docs/ui-usage/
- https://github.com/Pagefind/pagefind/releases
- https://github.com/jameslittle230/stork
- https://github.com/olivernn/lunr.js
- https://github.com/MihaiValentin/lunr-languages
- https://github.com/nextapps-de/flexsearch
- https://github.com/lucaong/minisearch
- https://registry.npmjs.org/lunr
- https://registry.npmjs.org/flexsearch
- https://registry.npmjs.org/minisearch
- https://registry.npmjs.org/fuse.js
- https://registry.npmjs.org/pagefind
- https://registry.npmjs.org/highlight.js
- https://registry.npmjs.org/prismjs
- https://registry.npmjs.org/shiki
- https://pypi.org/pypi/Pygments/json
- https://github.com/alecthomas/chroma
- https://github.com/alecthomas/chroma/tags
- https://raw.githubusercontent.com/alecthomas/chroma/master/README.md
- https://github.com/alecthomas/chroma/releases
- https://github.com/PrismJS/prism
- https://github.com/highlightjs/highlight.js/releases
- https://shiki.style/guide/dual-themes
- https://github.com/bfontaine/clygments
- https://github.com/venantius/glow