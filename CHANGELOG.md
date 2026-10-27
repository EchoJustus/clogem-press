# Changelog

## 0.3.0 — Phase 4: theme polish and advanced features

Phase 4 is complete, and its exit criterion is asserted in CI (DESIGN.md §8,
§11.3 item 22): every key of vdoing's `themeConfig`, and every front-matter
key vdoing reads, is implemented, deferred or removed. `clogem.release-test`
checks each against vdoing 1.12.9, cited by file and line, and runs `doctor`
on a `site.edn` that sets every deferred, implicit and removed key: each is
named. The tasks, in the order they landed: Task A — foundations (§11.3
items 7, 11, 16, 18); Task B1 — colour modes, an accessible palette, the
mode toggle and icons (items 2–6 and 8); Task C — code highlighting (item
10); Task D — the dev loop, and its follow-up (item 12); Task B2a — the card
and line page styles, focus rings and B1's follow-ups (item 9); Task F —
analytics, verification and a static root (items 14, 15); Task B2b — the
navbar, the mobile drawer and the page chrome (item 9, D-P4-9); Task E2 —
article-page parity (item 16, D-P4-13); Task E1a — the blog identity and
the home banner (item 13, D-P4-13); Task G — cross-language search (item
17, D-P4-17, D-P4-18); the Phase 4 follow-ups — Task D's known issues and
what the E1a and E2 checks left; Task E1b — htmlModules (item 13); and the
release's own block, the last vdoing keys (item 22). The support page, the
first block below, is a separate tool built from `support/`: it changes
nothing in the generator or in a built site.

A 0.2.0 site gets, without changing anything, what vdoing has on by
default: page buttons, a back-to-top button, title badges and an update bar
under every article — each of which a site can turn off — and every build
writes `dist/404.html` (a root `404.html` in the static directory replaces
it). Its code is highlighted, and the reader can choose a colour mode
(Upgrading, below). It can opt into the rest: the line page style
(`:theme :page-style :line`), a sidebar that starts collapsed, background
patterns (`:content-bg-style`), the blog identity (`:blogger`, `:social`,
`:footer`, the home banner, `:body-bg-img`), htmlModules (`:theme
:html-modules`), an edit link and last-updated dates (`:content :edit-link`,
`:theme :last-updated`), a navbar logo and repository link, analytics
(`:analytics`), search-console verification (`:seo :verification`), a
static directory (`:content :static-dir`) and cross-language search
(`:search {:cross-language true}`). A key it set while 0.2.0 listed it as
planned now takes effect.

### Upgrading from 0.2.0

- **Code is highlighted by default** (Task C): the first build of a site
  with a code block fetches Chroma (a 3 MB download, verified and cached
  outside the site), and a failed fetch or run fails the build;
  `--no-highlight` or `:highlight {:provider :none}` gives 0.2.0's plain
  `<pre><code>`. A bad `:tools :chroma` pin is now an error, like
  Pagefind's.
- **On by default, as in vdoing**: page buttons (`:theme :page-button`),
  back to top (`:back-to-top`), title badges (`:title-badge`) and the
  update bar under every article (`:update-bar {:show-to-article
  false}` drops it). The previous and next links, as before, now with
  `:prev-links` and `:next-links` to drop them site-wide; front matter
  `prev: true` / `next: true` now shows the usual link instead of warning.
- **`dist/404.html`** is written by every build (Task E2).
- **A key 0.2.0 listed as planned now takes effect** — with no change to
  `site.edn`: `:analytics` loads its provider on the deployed site (remove
  it, or set `:provider :none`, to keep it off); `:content :static-dir` is
  copied, and a file in it that collides with the build's own output is an
  error; `:theme :html-modules` is injected; `:theme :page-style :line`,
  B2b's five page-chrome keys, the E1a identity keys and `:search
  :cross-language` do what they say.
- **Warnings, never errors**: an unknown `site.edn` key, or vdoing's
  spelling of one, warns, naming its path and the key to write (Task A);
  an unknown command-line option warns and is ignored (the follow-ups;
  it was ignored silently).
- **The page**: the mode and page-style classes moved from `<body>` to
  `<html>`, so CSS written as `body.theme-mode-dark` no longer matches
  (Task B1); the navbar is one row — brand, nav, languages, search, mode —
  with a drawer at 50rem and below, and beside a sidebar the TOC shows from
  80rem (Task B2b); the footer reads *Powered by clogem-press* where it
  said `clogem-press 0.2.0`, and the version stays in `<meta
  name="generator">` (Task E1a); site CSS that styled `.clogem-main` or a
  code block's `<pre>` may need adjusting (Tasks B2a, C).
- **`bb dev` and `bb serve` listen on 127.0.0.1** (`--host 0.0.0.0`
  restores every interface), and `bb.edn` asks for babashka 1.13.219 or
  later (Task D).

### Known issues

- The Malay (`ms`) and Tamil (`ta`) strings await native review; each
  task's *Needs native review* section lists its own.
- `HEAD` reports `Content-Length: 0` from `bb dev` and `bb serve` on
  babashka 1.13.219 (1.13.220 and later send GET's length).
- Two browser layout checks depend on the fonts, and can fail under DejaVu
  Sans or a serif font: the navbar staying one row on `/ms/` at 1031–1061
  px, and the long nav at 850 px (`layout.test.mjs`). Both pass on CI and
  passed in this release's local run.

**Support page** (DESIGN.md §7.5): clogem-press's page on the Clogem
support site, https://echojustus.github.io/clogem-support/clogem-press/,
built and published as the hub's own pages are. Not part of the generator:
nothing in `src/clogem/` changes, and a built site is unaffected.
Languages (SP.2): English (the default) at `clogem-press/`; Simplified
Chinese at `zh-Hans/`; Traditional Chinese, with Taiwan wording, at
`zh-Hant/`; Brazilian Portuguese at `pt-BR/` (also for `pt`); European
Spanish at `es-ES/` (also for `es`). Five pages, **179,039 bytes** in all
(as of Task G's privacy clause, below; 178,640 as of SP.8): `index.html`
34,880, `zh-Hant/index.html` 36,259, `zh-Hans/index.html` 36,144,
`pt-BR/index.html` 35,820, `es-ES/index.html` 35,936 (each within the
64 KB budget), plus, on the English and Chinese
pages, the fonts they use (SP.8 and SP.7, below; README).

### Added (support page)

- `bb support-page` (`support/support_page.clj`, Babashka and its built-in
  hiccup2) writes `dist-support/clogem-press/` from `support/i18n/*.edn`
  (the words, one file per language) and `support/clogem-press.svg` (the
  v3 48-unit icon, EPL-2.0). Each page is one self-contained file
  (budget 64 KB): no JavaScript, no cookies, nothing loaded from other
  sites, no `style=` attributes; a Content-Security-Policy allowing only its
  one style by SHA-256 and `data:` images; `no-referrer`, `color-scheme`,
  light and dark `theme-color`, a canonical link and a description; the
  icon as a `data:` image in the header and as the favicon (base64, the
  form the hub's front page reads an app's icon from). Light and dark after
  the system, or the hub's no-script switcher (Auto, Light, Dark; opens on
  Auto, remembers nothing), with clogem-press's jade as the accent: a darker
  jade for text in light mode, a lighter one in dark, every pair WCAG AA.
  Six sections, in this order: what clogem-press is, what it does today
  (0.2.0, and Phase 4 as in progress and unreleased), getting started, help
  and feedback, privacy, licence and source.
- Five languages (SP.2): the words in `support/i18n/` (`en.edn`, which was
  `support/page.edn`; `zh-Hans.edn`, `zh-Hant.edn`, `pt-BR.edn`,
  `es-ES.edn`), each page in its own folder, `<html lang>` set, its own
  canonical link and the reciprocal `hreflang` alternates of all five
  (`pt`, `es` and `x-default` too); a language switcher in the header, each
  label in its own language, the current one marked `aria-current="page"`;
  no automatic redirect. The switcher (SP.3) is a no-script `<details>`
  dropdown beside the theme switcher, not a flat row of five links: its
  summary shows a globe, the current language and a chevron (the word for
  "Language" hidden for screen readers, `:lang-label`), and its panel lists
  the five as plain relative links; the browser opens and closes it (click,
  Enter or Space), and without a script it does not close on Escape or a
  click outside. Hidden in print. The build refuses a
  translation whose sections, blocks, link targets, code or commands differ
  from English's. On the translations, links to pages in English only carry
  `hreflang="en"` and a note, （英文）, *(em inglês)* or *(en inglés)*. The
  help section says people may write in any of the page's languages, and
  replies may come in English. The one stylesheet adds system CJK fonts by
  `:lang()` (PingFang, Microsoft YaHei or JhengHei, Noto Sans CJK, Source
  Han Sans; none bundled) and no letter-spacing on CJK. Each translation
  names the generator's own content languages (English, Simplified and
  Traditional Chinese, Malay, Tamil), and none suggests it supports
  Portuguese or Spanish content.
- The privacy section's wording is **the owner's**, approved on
  2026-10-26 (the translations mirror the English text fact for fact, and
  change when it changes): the page (no cookies, no script,
  nothing from other sites; GitHub Pages' own logs), the generator (runs
  locally, sends nothing; downloads Chroma, Pagefind and the fswatcher pod
  from their GitHub releases only when first needed or on `bb fetch-tool`,
  each SHA-256-verified and cached), and the sites it builds (other
  services only when their owner turns on giscus, analytics, or an image
  hosted elsewhere).
- `.github/workflows/support-page.yml`: on pushes to `main` that touch the
  page's sources, and by hand; never on pull requests. It tests and builds
  the pages, then clones clogem-support's `gh-pages` over SSH with the
  `CLOGEM_SUPPORT_DEPLOY_KEY` deploy key (from `env:`, mode 600, GitHub's
  published host keys pinned), replaces `clogem-press/` with the built
  folder (`cp -R dist-support/clogem-press/.`) and stages it alone, commits as `github-actions[bot]` and pushes without force,
  retrying from a fresh clone up to 3 times; then waits up to 10 minutes
  for the live page, warning if it hasn't changed. Without the secret it
  stops with a notice, successfully. Actions pinned by commit.
- `bb.edn`: the `support-page` task, and `"support"` on `:paths` so the
  task and its tests can require the generator.

### Tests (support page)

- `test/clogem/support_page_test.clj` (34 tests after SP.4), every rule on each of
  the five pages: no script, handlers or style attributes; the CSP's hash is the style's; the head tags; every
  image a `data:` URI with alt, width and height; nothing loaded from
  elsewhere; the icon passes the hub's plain-drawing checks; the switcher;
  every colour pair AA in light and dark; the sections, the jump links and
  the heading levels in order; no commerce words and no address but
  `EchoJustus.Studio@outlook.com`; the help links; words escaped and only
  the allowed pieces; the byte budget; licence headers. The workflow's
  text, and its publishing step run against a local bare repository
  standing in for `gh-pages`: the first publication adds
  `clogem-press/index.html` and nothing else changes, a rerun pushes
  nothing, another writer's commit is kept, a refused push is retried from
  a fresh clone, and a missing secret is a notice and exit 0.
- SP.2: the build writes the five files and nothing else; each page's
  `lang`, canonical link and complete, reciprocal `hreflang` set, each
  resolving to a built file; the language switcher (all five in order,
  `aria-current` on itself, each relative `href` resolving to the right
  file, each label's `lang`); parity with English (sections, blocks, link
  targets, commands, keys, no empty string) and the build refusing drift;
  every title, the lead, the description and the switchers' labels
  translated; `hreflang="en"` and the note on English-only links; smoke
  checks on wording (no 软件, 軟件, 程序代碼 or 網絡 in zh-Hant; no 軟, 體, 網,
  連, 結, 設 or 預 in zh-Hans; Brazilian, not European, Portuguese; Spain's
  Spanish and *tú*); the generator's five content languages named on every
  page and no claim of Portuguese or Spanish content; CJK fonts and
  spacing; the stand-in publishes all five files and nothing else.

### Changed (SP.9, support page)

- **The privacy note is approved** (the owner, 2026-10-26): each
  `support/i18n/*.edn` now marks the section as the approved wording,
  which changes only with the owner's approval, and the test checks that
  mark in place of the draft's. The page's words are unchanged.
- **The footer's year stays 2026** (the owner: "all 2026"), not the build's.
- **The deploy key is set up** (the owner, 2026-10-26), so the first push
  to `main` that touches the page publishes it. The one-time steps no
  longer say a key may not be shared: GitHub takes a public key as a
  deploy key on one repository only, and a key of its own is best, so that
  each can be revoked alone.

### Changed (SP.8, support page)

- **The footer opens with the copyright notice**, the owner's rule for every
  Clogem support page (2026-10-26; clogem-support's `CLAUDE.md`, "Footer
  copyright notices"; DESIGN.md §7.5 item 9): `© 2026 EchoJustus (Jiapeng
  Ji). clogem-press.`, the same characters on all five pages. The
  publisher is always `EchoJustus (Jiapeng Ji)`, in full, and never wraps
  across lines (one `white-space: nowrap` element, its text unchanged; the
  product's name too, so it never breaks at its hyphen). It is written in
  the generator (`support-page/copyright-holder`), not in `support/i18n/`,
  so no translation changes it; the year is fixed, the page's first
  publication. Licence headers in source files, the MIT
  notices and `LICENSE` are unchanged: the rule covers footers only.
- **Sizes**: each page grows by 101 bytes, and `wenkai-latin-400` by 120
  (`©`), to 22,884; the others are unchanged. Five pages, 178,640 bytes:
  `index.html` 34,804, `zh-Hant/index.html` 36,181, `zh-Hans/index.html`
  36,066, `pt-BR/index.html` 35,733, `es-ES/index.html` 35,856. A page's
  download, HTML and fonts, as it opens: English 80,208 (83,624 with the
  language list open), zh-Hant 202,685, zh-Hans 183,938, pt-BR 35,733,
  es-ES 35,856.
- Tests: the notice word for word, first in every footer and once on each
  page; `copyright-holder`; every `EchoJustus` a reader sees in a footer
  followed by ` (Jiapeng Ji)`; the paragraph as built, with its two nowrap
  elements; no `©` or `Jiapeng` in `support/i18n/`; DESIGN.md quoting the
  notice. They failed first. The status check's "no dates promised" now passes over the
  notice's year, and only that. Chromium (5 languages, 320–1440 px, light
  and dark, 60 runs): the notice exact, the name on one line, the footer's
  paragraphs not overlapping, no sideways scroll, no CSP message.

### Changed (SP.7, support page)

- **LXGW WenKai for English and Chinese only** (the owner, 2026-10-26,
  after reviewing PR #17; DESIGN.md §7.5 item 8). The en, zh-Hant and
  zh-Hans pages keep their text in WenKai, Regular and Medium; **zh-Hant
  now uses the main LXGW WenKai**, its own Traditional glyphs, not WenKai
  TC, which is gone from `support/fonts.edn`, `support/fonts.sh`'s
  downloads and the published licences (`OFL-LXGW-WenKai-TC.txt`).
- **pt-BR and es-ES are back in the system fonts** they had before SP.6,
  the hub's text and heading stacks and its weights: WenKai lacked or
  misplaced accents there. They declare no `@font-face`, load no font
  file, credit no font, and their CSP is the hub's, without `font-src`.
  Portuguese and Spanish words on the other pages (the dropdown's
  "Português (Brasil)", "Español (España)") take the system's stack by
  `:lang(pt)` and `:lang(es)`.
- **The subsets**: Latin (from the English page and the Latin of the
  Chinese pages only), each Chinese page's own Chinese, and `wenkai-names-500`
  on the English page alone. Sizes (v1.530): `wenkai-latin-400` 22,764,
  `wenkai-latin-500` 22,520, `wenkai-hant-400` 103,400, `wenkai-hant-500`
  17,700, `wenkai-sc-400` 87,548, `wenkai-sc-500` 14,920,
  `wenkai-names-500` 3,416 bytes. A page's download, HTML and fonts, as it
  opens: English 79,987 (83,403 with the language list open), zh-Hant
  202,464, zh-Hans 183,717, pt-BR 35,632, es-ES 35,755. (SP.8 added the
  footer's notice: its figures, above, are current.)
- **"…" in English is the system's**: WenKai draws U+2026 full-width and
  centred, as Chinese sets it; the Latin subsets' `unicode-range` leaves it
  out, and Chinese text keeps WenKai's own.
- **The Chinese leads no longer end on 言。 alone** (at 1024–1440 px):
  `text-wrap: pretty`, and a `:lang(zh)` measure of 40rem for a browser
  without it.
- **`support/fonts.sh` checks the Python version** first: brotli's pinned
  wheels are CPython 3.12's and 3.13's, and on another version pip's hash
  error read as tampering. It now stops with "the font step needs Python
  3.12 or 3.13; found X.Y". CI stays on ubuntu-24.04's 3.12.
- **The privacy draft** says, on the three WenKai pages, that the page's
  fonts come from this same site; pt-BR and es-ES, which load none, are
  unchanged. Still a draft for the owner's approval.
- Tests: pt-BR and es-ES with no `@font-face`, font URL, WenKai or
  `font-src`, and the system weights; en, zh-Hant and zh-Hans in the main
  WenKai, with no TC in the plan, the generator or the font step; the
  `:lang(pt)`/`:lang(es)` rule and the dropdown's `lang`s; no list holding
  Portuguese or Spanish text, no Latin list holding U+2026; the Python
  check; the privacy clause; the lead's wrapping. Each failed first.
  Chromium (5 languages, 360 and 1280 px, light and dark): pt-BR and es-ES
  request no font file; the others load WenKai and no TC file; the
  dropdown's pt and es names are the system's; "…" sits on the baseline;
  no CSP message, no sideways scroll.

### Changed (SP.6, support page)

- **Text is set in LXGW WenKai (霞鹜文楷)**, all of it but headings and
  code, in English, Chinese, Portuguese and Spanish; the zh-Hant page in
  LXGW WenKai TC (霞鶩文楷 TC). Both v1.530, Regular and Medium (emphasis:
  `<strong>`, the chips, the buttons, the eyebrow, the switchers, the jump
  links); headings keep the system font, code the monospace one, and the
  system stacks stay behind WenKai (`font-display: swap`). The owner's
  decisions of 2026-10-26 (DESIGN.md §7.5 item 8): the files are published
  beside the pages as `clogem-press/fonts/*.woff2`, the CSP gains
  `font-src 'self'` and nothing else, and subsetting runs `pyftsubset` in
  GitHub Actions with no Python in the repository. (SP.7 narrowed this
  to English and Chinese; the current sizes are SP.8's, above, and the README's.)
- **WenKai TC's emphasis is Medium, not Bold**: v1.530 of WenKai TC has
  Light, Regular and Medium and no Bold.
- **Subsets by script and weight**, each page declaring only those it uses
  (`unicode-range`, paths relative to its folder, so each page's CSP hash
  is its own): Latin, Simplified and Traditional, Regular and Medium, and
  two small ones for the switcher's 简体中文 and 繁體中文 on the other pages.
  `bb support-page` writes their character lists to `dist-support/glyphs/`;
  `bb support-fonts` prints the pins in `support/fonts.edn`.
- **`support/fonts.sh`**, the font step: pinned, hash-checked downloads
  (cached), fontTools 4.66.1 and brotli 1.2.0 installed by hash, one
  `pyftsubset` per list, a coverage check, the licences beside the
  subsets, size caps. Without it the pages still work, in the system fonts.
- **The workflow is two jobs**: `build` (no secret: tests, page, fonts,
  artifact) and `publish` (`needs: build`: the artifact and the same SSH
  publishing loop; no checkout, pip or font download). It refuses a folder
  without fonts. CI gains `support-fonts`, which runs the font step on
  every push and keeps the folder as an artifact.
- **The language order** is English, 繁體中文, 简体中文, Português (Brasil),
  Español (España), in the dropdown and the `hreflang` alternates.
- **The footer credits the font** in each language, linking its repository.
- Tests: the plan's pins; the character lists against the pages' text,
  read from the HTML; each page's `@font-face` rules; WenKai for text, the
  system for headings and code, Medium for emphasis, no weight without a
  face; the CSP; the workflows' jobs, secrets and pins; the font step's
  options; no Python added; the order; the stand-in publishing the fonts;
  the credit. Each failed first. Chromium (5 languages, 360 and 1280 px,
  light and dark) found two gaps, now fixed and tested: the switcher's
  Latin names inherited WenKai TC on the zh-Hant page, and the chips'
  capitals (pt-BR's Ó, Ç) were missing from their list.

### Fixed (SP.5, support page)

- **The name keeps one line.** From 480 px up "clogem-press" no longer
  breaks after its hyphen (it did at 768–1440 px, by language): the `<h1>`
  is `nowrap` there, with its text unchanged, and the brand's flex basis is
  its `min-content`, so when the brand and the switchers don't both fit the
  switchers wrap below it instead of squeezing it; under 30rem they take a
  row of their own, and the heading's smallest size is 1.85rem, so the name
  is one line at 320 and 360 px too. At 1280 px the switchers still sit
  beside the brand in every language.
- **The language list opens under its own button.** The language switcher
  is now last in the row, at the end edge, and its panel hangs from it
  (it hung from the switchers' end edge, under the theme switcher).
- **The privacy draft no longer reads as a complete list:** clogem-press's
  own pages keep three things in the reader's browser; giscus and
  analytics, when the owner turns them on, keep their own data under their
  own terms. The tool-download paragraph says each fact once.
- **pt-BR and es-ES buttons say where they lead** ("Perguntar no GitHub",
  "Preguntar en GitHub", "Sugerir no GitHub", "Proponer en GitHub")
  instead of repeating their cards' titles.
- `test/browser/support-page.test.mjs` checks the header in Chromium.

### Fixed (SP.4, support page)

- **The header fits a 320 px screen in every language.** The pt-BR and
  es-ES theme switchers were wider than the content box at 360 px, and the
  hero's `auto` grid column grew with them (at 320 the page scrolled
  sideways). The column is `minmax(0,1fr)`; under 30rem the theme switcher
  drops its icons and tightens; the switchers never outgrow the content
  box. The language dropdown hangs from the switchers' end edge, so it no
  longer runs off the left of a 360 px screen when the summary sits on the
  left; when the switchers wrap, the language's line is the lower one.
- **At 1280 px the switchers sit beside the brand** in every language, as
  on the hub: the brand's words wrap within a flexible basis.
- **Short inline code keeps one line** (`bb dev` broke into `bb` / `dev`):
  code of up to 24 characters gets `class="nw"`; a longer path may break.
- **Shorter pill labels**: pt-BR's chip "Em andamento", and the Q&A and
  idea buttons in pt-BR and es-ES, which wrapped.
- **The privacy draft** names all three keys a built site keeps in the
  reader's browser (the colour mode, the language choice and the
  dismissed "also available in …" banners), and says that tool downloads
  come from the tool's releases and are checked against a pinned hash *by
  default*: a site's `:tools` config can name its own source and hash, or
  a binary already installed. In all five languages.
- **The in-progress card** says what Phase 4 added to `bb dev` (one
  rebuild per burst, the build-error overlay, theme hot reload, `:base`
  in dev); live reload itself shipped in 0.1.0.
- **zh-Hans / zh-Hant: "marks" is 商标 / 商標**, the word for trademarks.
- **The live-page wait is about 10 minutes** (it could run 23): no try
  after 570 s, `--max-time 15`, a `timeout-minutes: 12` backstop with
  `continue-on-error`, so it still only warns.
- **MIT notice** on `support_page_test.clj` (its `git!`, `stand-in!` and
  refusing hook follow the hub's `portal_test.clj`) and on the workflow
  (its `publish` and 3-attempt loop follow `portal.yml`).
- Tests: the hero's column, the switchers' bounds, the panel's anchor;
  short code on one line; every `localStorage` key in the theme's code
  covered by the privacy note in each language; "by default" for
  downloads; 商标 / 商標; pill label lengths; the Phase 4 wording; the
  live-page wait's bound; the MIT notice in all three files. Each failed
  first on `f8de9e4`.

### Needs native review (support page)

Machine-made translations, to be read by a native speaker before or soon
after publication, and whenever `en.edn` changes:

- `support/i18n/zh-Hans.edn`: Simplified Chinese, mainland-neutral.
- `support/i18n/zh-Hant.edn`: Traditional Chinese, Taiwan wording and
  punctuation.
- `support/i18n/pt-BR.edn`: Brazilian Portuguese.
- `support/i18n/es-ES.edn`: Spanish of Spain, *tú*.

### The owner's one-time steps (support page; done on 2026-10-26)

1. `ssh-keygen -t ed25519 -N "" -C "clogem-press support page" -f clogem-support-deploy`.
2. The public half to EchoJustus/clogem-support, **Settings > Deploy
   keys**, with **Allow write access** (GitHub takes a public key as a
   deploy key on one repository only; a key of its own can be revoked
   alone).
3. The private half as the Actions secret `CLOGEM_SUPPORT_DEPLOY_KEY` here,
   then delete the local copy.
4. Approve the privacy note's wording before the first publication: once
   the secret is set, the next push to `main` that touches the page
   publishes it. The four translations follow it.

Once it is live, the hub's own session links it from the front page's
clogem-press card and its README's table.

**Release 0.3.0 — the last vdoing keys** (§8's exit criterion, §11.3 item
22): what an audit of vdoing 1.12.9 (`d77b420`) found unclassified, as the
owner decided on 2026-10-27 — four small keys implemented, one deferred,
five marked implicit — then the exit criterion, asserted, and the version:

### Added (release 0.3.0)

- **`:theme :prev-links` / `:next-links`** (vdoing's `prevLinks` /
  `nextLinks`, `PageNav.vue`): `true` by default, as in vdoing; `false`
  drops that link — the one at the end of the page and its page button —
  from every article and catalogue page. A page's own front matter wins
  whenever it sets one, as in vdoing: `prev: false` hides its link, a
  permalink or `.md` path links that page, and **`prev: true` / `next:
  true` now shows the usual neighbour** (it used to warn "front matter
  prev/next names an unknown page: true"). Both are page-chrome keys
  (`config/page-chrome-keys`): any other value warns and is repaired to
  `true`; `prevLinks` and `nextLinks` warn with the kebab spelling.
- **Front matter `showSidebar: false`** (vdoing's `Layout.vue`) drops the
  sidebar as `sidebar: false` does: read from the primary variant, it
  leaves the table of contents in place, and the page gets the
  single-column shell and no collapse button.
- **`pageComponent.data.key`** (vdoing's `Catalogue.vue`, `data.path ||
  data.key`): a Catalogue page names its directory with `data.path` or,
  when that is absent or blank, `data.key` (`model/catalogue-path`). The
  warning for neither now says "has no data.path (or data.key)".
- **A nav item's `:aria-label`** (vdoing's `ariaLabel`, `DropdownLink.vue`):
  a string or a map by language, like `:text`, rendered as the
  `aria-label` of the item's dropdown `<summary>` — the control vdoing's
  dropdown button is — or of its link. A known nav item option, not walked
  as options; `ariaLabel` warns with the kebab spelling.

### Changed (release 0.3.0)

- vdoing's site-wide **`navbar`** switch is **deferred** past v1 with front
  matter `navbar: false` (DESIGN.md §8): a `site.edn` that sets it is told
  so, and that every page shows the navbar.
- **Implicit** — a `site.edn` that sets one is told what clogem-press does
  instead and to remove it: `searchPlaceholder` (the i18n string
  `:search/placeholder`), `locales` and `label` (`:langs :locales` and
  `i18n/<lang>.edn`), `selectText` (`:nav/language`) and
  `activeHeaderLinks` (the table of contents always marks the heading
  being read). `:label` under `:langs :locales <lang>` is still a locale
  option, and quiet.
- README (the conventions, page buttons, the navbar, sidebar collapse) and
  `config.example.edn` document the four keys.
- `src/clogem/version.edn` is `0.3.0`; its comment adds "0.3.0 = Phase 4
  (theme polish and advanced features) complete" and drops "No release tag
  has been pushed yet": each release is tagged on `main` once merged.
  Pinned as the current version, 0.2.0 became 0.3.0 in `identity_test`
  (the generator meta, and the footer without it), `config_test` (three
  "in 0.2.0" warnings, which name the running version) and
  `config.example.edn`'s IndexNow note.
- README's status line says Phase 4 is complete (0.3.0); DESIGN §8 marks
  the release done and §11.3 item 22 records it.

### Tests (release 0.3.0)

- New `test/clogem/release_test.clj`: one test per key above — the two
  switches on and off, with `prev: true`, `next: true` and a permalink
  overriding them, and their warnings; `showSidebar: false` beside
  `sidebar: false`; `data.key` alone, blank `data.path`, and both; a nav
  link's and a dropdown's label, per language; the `navbar` and the five
  implicit warnings. Each failed before its change (44 failed assertions in
  7 tests).
- **The exit criterion** (DESIGN.md §8), in the same namespace:
  `vdoing-theme-keys` lists vdoing's 83 themeConfig keys — `types/index.ts`'s
  own, the `DefaultThemeConfig` keys it inherits (@vuepress/types 1.9.5,
  `nav` items' included) and the three it reads that neither declares —
  each with its vdoing file and line: 61 are known where clogem-press reads
  them (and `config/vdoing-keys` agrees), 14 are implicit, deferred or
  removed, and 6 sit inside one of those. `vdoing-front-matter-keys` lists
  the 45 front-matter keys vdoing reads: 42 are read by the clogem source
  named, and DESIGN §8 lists `navbar: false` and `search: false` as
  deferred and `sidebar: 'auto'` as removed. `doctor`, on a `site.edn` that
  sets the 14, names each once. Nothing is `:planned` any more. A `nav`
  item's `target` and `rel`, declared by @vuepress/types but never read by
  vdoing (its `NavLink.vue` sets both itself), are left unclassified, and
  `doctor` names them as unknown nav item options.
- `the-version-is-0-3-0`: `version.edn`, and a build's `<meta
  name="generator">` and Atom `<generator version>`.

**Task E1b — htmlModules** (§11.3 item 13, second half; D-P4-13):

### Added (E1b)

- **Config** (`clogem.config/check-html-modules!`): `:theme :html-modules`
  is read. A slot — vdoing's `:home-sidebar-b`, `:sidebar-t`, `:sidebar-b`,
  `:page-t`, `:page-b`, `:window-lb` and `:window-rb`, plus clogem-press's
  own `:head-extra` (DESIGN.md §5.3) — is a string of HTML, or a map of
  language keyword to string; `""` is valid and quiet, and suppresses the
  slot in that language. A show-mode (`:page-t-show-mode`,
  `:page-b-show-mode`) is `:all` (the default), `:article` or `:custom`,
  written as a keyword or as a string (vdoing's `"article"` and `"custom"`,
  or `"all"`), and is stored as a keyword. `:head-extra` must be head
  content, read in order: whitespace, comments, whole `<script>`,
  `<style>`, `<noscript>` and `<template>` blocks, and `<link>` and
  `<meta>` tags, whose quoted values may hold a `>`. Anything else — a
  `<div>`, an `<img>`, an end tag, bare text, a comment, block or tag that
  never closes, and also a `<title>` or a `<base>` (a second title; every
  `#fragment` link retargeted) — warns, naming the first bad piece as
  written, and that value is dropped.
  Every other problem is a warning too, and the check repairs the config —
  it removes a bad slot, uses `:all` for a bad show-mode, and removes
  `:html-modules` entirely when it is not a map — so the renderers read
  valid values only. The 10 keys are known keys (a slot's per-language map
  is user data and is not walked), and vdoing's `homeSidebarB`, `sidebarT`,
  `sidebarB`, `pageT`, `pageB`, `pageTshowMode`, `pageBshowMode`,
  `windowLB` and `windowRB` warn with the kebab spelling, at any path.
  New accessors: `module-slots`, `html-module`, `show-mode`, `module-set?`
  and `windows?`.
- **The slots** (`clogem.theme.modules`, a new namespace that requires
  `clogem.theme.identity` for `raw-html` and never `clogem.theme.layout`).
  Each is wrapped in `<div class="clogem-module clogem-module--<slot>">` —
  a `<section>` for `home-sidebar-b`, so the home's box rules apply — which
  carries `data-pagefind-ignore="all"` while search is on, so a module
  never lands in a search result. Where each goes: `home-sidebar-b` is the
  last box of the home's right bar (so none under `hideRightBar: true`);
  `sidebar-t` and `sidebar-b` sit inside `aside.clogem-sidebar`, around its
  `<nav>`, which carries them into the mobile drawer and lets a collapse
  hide them, and render only where a tree does; `page-t` is above
  `.clogem-content` on an article and after the card grid, inside the
  article, on a catalogue; `page-b` is right after `</article>`, above the
  edit link. The page slots never render on the home, an index or
  pagination page, the archives or the 404 page. Per-language values follow
  the fallback chain by `contains?` (so zh-Hant does not pass through
  zh-Hans), and `{{base}}` becomes the encoded base.
- **The show-modes**: `:all` shows on every article and catalogue page,
  `:article` only where the page is an article, `:custom` only on the
  others. clogem-press reads its own `:article?`, so a catalogue page
  counts as custom; vdoing reads `article !== false` alone, and its
  generated catalogues carry `article: false`, as the demo's do, so the two
  agree on those. README says so.
- **`:head-extra`**: the page language's HTML, as written — unwrapped, with
  no Pagefind attribute — in every `document` page's `<head>`, after
  Pagefind's tags and before `overrides/custom.css`, which stays the last
  stylesheet (the analytics loader stays the last child of `<head>`). It is
  on the 404 page too, and never on a redirect stub.
- **The windows** (vdoing's `windowLB` and `windowRB`):
  `div.clogem-window--lb` and `--rb`, rendered just before back to top,
  which stays the last child of `<body>`; none on the 404 page. Each holds
  a close button (the `x` icon, `:window/close`) that is `hidden` until
  `js/window.js` reveals it; a click hides that window for that page load
  only, and moves focus to `<body>` if the button had it. The script is
  deferred, same-origin, 358 B gzipped (budget 400), linked only on a page
  that renders a window and shipped only while `config/windows?` holds.
- **CSS** (`css/identity.css`, +438 B gzipped; `theme.css` unchanged): a
  wrapper is `min-width: 0; max-width: 100%; overflow-x: auto` with
  0.25rem of padding, which keeps a focus ring out of the clip; the sidebar
  slots are 0.85rem with a 1rem gap to the tree, by their own class, so
  nothing depends on where the aside sits; the page slots have a 1rem block
  margin. A window is `position: fixed; bottom: 0; z-index: 5`, at most
  200 px wide, its module at most `min(400px, calc(50vh - 2.5rem))` tall
  and scrolling; `lb` is at `left: 0` and `rb` at `right: 4.7rem`, which
  clears back to top; there are none at 60rem and below. Print hides every
  module and every window. `identity.css` is now linked and shipped for any
  slot other than `:head-extra` as well.
- **Strings**: `:window/close` in all five languages.

### Changed (E1b)

- `:theme :html-modules` no longer warns ":theme :html-modules has no
  effect in 0.2.0; nothing is injected.", and `htmlModules` is no longer
  one of the `:planned` vdoing spellings: **a site that set any slot now
  gets that HTML injected.** A value that is not a map (0.2.0 accepted
  `false`, `:none`, `true` and `0`) now warns with the shape it must have
  instead, and `nil` and `{}` stay quiet, as before.
- `config.example.edn` documents every slot, both show-modes, the
  per-language maps and `""`, `{{base}}`, the trust ("injected as written")
  and the head-only rule.

### Tests (E1b)

- New `test/clogem/modules_test.clj` (30 tests): one per config rule, the
  8 slots and both show-mode spellings quiet, the vdoing spellings and the
  unknown-key warnings, the accessors; the placement of every slot on the
  home (with and without `hideRightBar`, and `/page/2/`), an article with a
  tree, a post without one, a catalogue, an `article: false` page, the
  index, category, tag and archive pages and `404.html`; the 3 × 3
  show-mode matrix in both spellings; `""`, the fallback chain and
  `{{base}}` under `:site :base "/my docs/"`; `data-pagefind-ignore="all"`
  on every wrapper with search on and no `data-pagefind-` at all without
  it; `:head-extra`'s place between Pagefind and `custom.css`, in the
  page's own language, on the 404 page, and on none of the 21 redirect
  stubs an `:i18n :prefix-default? true` build makes, which carry no
  module or window either; the windows right before back to top (and
  ending `<body>` under `:back-to-top false`), none on the 404 page,
  `window.js` linked per language and shipped only with a window, and its
  400 B budget printed. With `CLOGEM_PAGEFIND`: a word that only `page-t`
  carries, on an article and a catalogue, is in no fragment, while the
  article's own words are.
- New `test/browser/modules.test.mjs` (77 checks): a temp demo copy with
  every slot set, built `--no-search --no-highlight` and served through
  `page.route`. No page overflow at 320 and 360 px with a 480 px element in
  `page-t`, `sidebar-t` and `home-sidebar-b`, each wrapper scrolling
  instead; the open drawer drawing `sidebar-t` before the tree; a
  keyboard-focused link at a wrapper's edge keeping its whole ring inside
  it; both windows clearing back to top and the page buttons at 1280×800,
  1344×740, 1440×740 and 1600×740; no client rects at 960 px and rects at
  961; Enter on a close button reached by Tab hiding that window, keeping
  the other and leaving focus on `<body>`; no close button and both windows
  without JS; print drawing neither a module nor a window.
- Deliberate pin changes. `config_test`: `the-pre-flight-keys-all-warn`
  loses the `:html-modules` "no effect" message and counts 3 warnings, not
  4; `vdoing-spellings-name-the-clogem-key` has `htmlModules` no longer
  planned (still 8 warnings); `a-scalar-html-modules-warns-and-never-
  crashes` expects the new not-a-map message. `identity_test`:
  `identity-css-only-when-something-needs-it` gains a module case (true)
  and a `:head-extra`-only case (false); the print block of `identity.css`
  now hides `.clogem-module` and `.clogem-window` as well; and
  `the-drawer-card`'s "nowhere else is the hidden card given a display" now
  looks at the blogger card's own `[hidden]` selectors, since
  `.clogem-window__close[hidden]` is a second one. The example config, the
  demo's `site.edn` and `{:html-modules {}}` stay quiet, and the pins on
  `clogem-sidebar`, back to top, the analytics loader and `custom.css`
  being last are unchanged.

### Fixed (E1b.1)

- **`:head-extra`'s head-only check reads the value in order** (items
  1–4). It stripped comments, then `<script>`, `<style>`, `<noscript>`
  and `<template>` blocks, then `<link>` and `<meta>` tags, and looked at
  what was left, which went wrong four ways: a quoted `>` ended a tag, so
  `<meta name="description" content="a > b">` warned about text `b">` and
  was dropped; a `<!--` in a script swallowed the rest, so
  `<script>var s = '<!--';</script><div>leak</div>` passed; `\b` matched
  before a `-`, so `<meta-data foo>` passed as a `<meta>`; and `<link
  rel=a></link>` warned that it "has <link>, but it must be head content
  only — <link>, …", the `/` dropped. Now one scan takes, at each
  position, whitespace, a whole comment, a whole block (its end tag in any
  case, spaces allowed before the `>`), or a `<link>` or `<meta>` tag whose
  name ends at whitespace, `/` or `>` and whose quoted values may hold a
  `>`. The first anything else is named as written — `</link>`,
  `<meta-data>`, `<TITLE>`, text "…" — and that value is dropped, as
  before. A comment, block or tag that never closes would swallow the rest
  of `<head>`, so it now warns too ("an unclosed <script>"); the check is
  new in E1b, so nothing on `main` relies on it
  (`a-quoted-gt-stays-inside-its-tag`,
  `a-comment-opener-in-a-script-hides-nothing`,
  `a-meta-or-link-custom-element-is-not-head-content`,
  `a-closing-tag-is-named-with-its-slash`). `head-extra-is-head-content-only`
  also accepts `<LINK>`, self-closing tags, `<script>var x = '</div>';</script>`,
  `<Script>x</sCrIpT >`, `<noscript><link></noscript>` and
  `<template><body></template>`, and, a deliberate pin change, names
  `<TITLE>` as written rather than as `<title>`.
- **The redirect-stub assertion tests something** (item 5). "A redirect
  stub has neither" looped over the demo's pages without a navbar, and the
  demo makes no stub, so it ran zero times. The new
  `a-redirect-stub-has-no-head-extra-module-or-window` builds the demo
  with every slot and `:i18n :prefix-default? true`, requires more than 10
  stubs (it makes 21), and checks that none has `:head-extra`, a module, a
  window or `window.js`, while the article each one redirects to has all
  four.
- **`js/window.js` has room to change** (item 6): 358 B gzipped, from 397
  of its 400 B budget, so a one-line edit no longer broke it. Its inline
  comment is gone; the two-line header stays, and the budget is unchanged.

### Fixed (E1b.2)

An adversarial probe checked `:head-extra`'s scan against parse5 7.1.2, an
HTML parser to the spec, with scripting on and off.

- **A long value no longer crashes the config check** (item 1). The text
  pattern, `(?:[^<]|<(?!…))*`, recursed once per character, so a value of
  ~8,000 characters without a `<` threw `StackOverflowError` out of
  `check-html-modules!`. Every repetition in the scan is now possessive
  (or a lazy `.*?`), which Java runs as a loop, and the whole value is read
  by one regex: 1–2 MB of text, `<meta>` tags, comments, attributes, a
  quoted value, a script, a style or a template body each take under
  350 ms here, and 20,000 characters of text warn like any text
  (`a-long-value-never-overflows-the-stack`).
- **An `=` where an attribute name may start begins a name** (item 2), as
  in HTML's attribute states, so `<meta ="><div>">`, `<meta name="x"
  ="><div>">`, `<meta a="b"="><div>">`, `<meta a=b ="><div>">` and
  `<meta/="><div>">` end at their first `>` and name `<div>`; they passed.
  An end tag's attributes are read the same way, so
  `<script></script x="><div>">` passes, as in a browser, and
  `<script></script x="><meta>`, whose quote never closes, warns
  (`an-equals-sign-where-a-name-may-start-begins-a-name`).
- **A double-escaped script is left open** (item 3). In a script, `<!--`
  escapes it and a later `<script` double-escapes it, and then a
  `</script>` does not end it, so `<script><!-- x="<script>"; </script>`
  swallowed the page and passed. The scan now follows the script's
  escaped and double-escaped states, and that value warns as "an unclosed
  <script> (after "<!--" and "<script", HTML does not end it at
  "</script>")"; `<script><!--<script>--></script>` and
  `<script><!-- document.write("<script></script>"); --></script>` pass,
  the latter newly (`a-double-escaped-script-never-ends-at-its-end-tag`).
- **A `<template>` is read as HTML** (item 4). It was raw text to its first
  `</template>`, so `<template><template></template><meta x>` and
  `<template><!--</template><!-- -->` passed though the outer template
  never closes. Its content is now read as tags (whose quoted values, and
  comments, may hold `</template>`) and comments; another `<template>`, an
  element whose content is not HTML (`<script>`, `<style>`, `<title>`,
  `<textarea>`, …) or a `<!…>`/`<?…>` in it warns
  (`a-template-holds-html-but-not-a-template`). A `<noscript>` is still
  read as a browser with scripting on reads it; README and
  `config.example.edn` say that with scripting off body content in one
  (an `<img>` pixel) ends `<head>` there, and that double-escaped scripts
  warn.
- **Text that does not show is named readably** (item 5): a value of only
  U+000B was named `text ""`, since `str/trim` removed it; a control,
  format or separator character other than a space is now written
  `\uXXXX`, as in `text "\u000b"` or `text "﻿"`, and only HTML
  whitespace is trimmed (`text-that-does-not-show-is-named-escaped`).
- **The probe's cases are a regression set**
  (`the-probe-cases-agree-with-a-browser`): its 76 cases and 61 more, each
  with parse5's verdict, pass or warn as a browser has them, except 10
  that a browser reads safely and the scan still rejects, erring toward
  caution (an end tag such as `</meta>`, a `<!DOCTYPE>`, `<![CDATA[`, `<?`,
  and a `<template>` holding a `<template>`, `<script>`, `<style>` or
  `<!DOCTYPE>`). Each new test fails on E1b.1's scan.

### Needs native review (E1b)

- `:window/close`: ms "Tutup", ta "மூடு".
### Merged with Task G (E1b)

`main` with Task G (#19) merged in. Five spots held both tasks' lines, and
each keeps both: this file's intro and blocks (G's sentence first, then
E1b's; E1b's block above G's), README's test count (re-measured), the
`skip?` entries in `assets.clj` (`js/window.js`, then G's two files), the
test runner's namespaces (G's, then E1b's), and the end of `document`'s
Pagefind block in `layout.clj`: G's `js/search-all.js` tag closes the
block, then `(modules/head ctx)` follows, so the head runs Pagefind's
tags, `search-all.js`, `:head-extra`, `window.js`, then `custom.css`.
Together: 906 tests, 0 failures.

Then `main` with the Phase 4 follow-ups (#20): only this file's intro and
block order held both (the follow-ups' sentence before E1b's; E1b's block
above theirs), and README's count is re-measured. Together: 921 tests, 0
failures.

**Phase 4 follow-ups** (before 0.3.0: Task D's known issues and smaller
items, and what the E1a and E2 checks left; tests in
`clogem.followups-test` unless named):

### Fixed (P4 follow-ups)

- **A file of 1 MB or more deleted mid-request is sent as it was, never
  http-kit's 500 naming its absolute path** (item 1). It was a `File`
  body, which http-kit opens only after the handler has returned, so a
  rebuild deleting it in between answered `500` with the path as the body
  (reachable with `--host 0.0.0.0`). Every file is now read whole, or from
  20 MB mapped — what http-kit did with a `File` body — from one open
  before the response exists; a file gone before that open is a plain 404
  (`a-large-file-deleted-mid-request-is-sent-whole-never-its-path`,
  `a-file-gone-before-it-is-opened-is-a-plain-404`; `dev_round2_test`'s
  `a-file-is-read-whole-or-mapped-from-one-open` replaces
  `a-small-file-is-read-whole-and-a-big-one-is-a-file`). A read holds the
  file once: it was read into a buffer and then copied, twice its size
  (follow-ups.1, `a-whole-read-holds-the-file-once`: a 100 MB read in a
  150 MB heap).
- **HEAD sends no body, whatever the status, and the real
  `Content-Length` where http-kit allows it** (item 2). A HEAD of a 404,
  a 500, a 403 or a 400 sent its body on babashka 1.13.219, and every HEAD
  said `Content-Length: 0`. A HEAD is now answered as GET is: on babashka
  1.13.220 and later (http-kit 2.9.0-beta4) the body is left for http-kit,
  which drops it and sends GET's `Content-Length`; on 1.13.219, whose
  http-kit sends any body to HEAD and counts `Content-Length` from the body
  it sends, the body is dropped here and the length stays 0
  (`head-reports-the-length-and-sends-no-body`). A HEAD reads none of the
  file: one under 20 MB was read whole, to be dropped, about 30 times
  slower than 0.2.0 for 19 MB (follow-ups.1). On 1.13.220 and later its
  body is the file mapped, whose length http-kit sends without reading a
  byte (on Windows a file under 20 MB is still read: a mapping would hold
  it against the next rebuild's replace until a GC); on 1.13.219 there is
  none; a page dev adds the reload script to is still read, for its length
  (`head-reads-no-file`).
- **A fswatcher pod that dies while `bb dev` runs is followed by polling**
  (item 4). dev stopped watching — nothing rebuilt again — and babashka's
  pod client printed `ERROR: Pod quit unexpectedly` once per watch (six
  lines). dev's follower now checks the pod's process on every pass and,
  once it is gone, prints one line (`the fswatcher pod quit; dev polls
  every 500 ms for the rest of this session.`) and polls, with one full
  rebuild once polling starts, as the inotify-limit fallback does; the
  pod client's lines are dropped, as at Ctrl-C
  (`a-pod-that-dies-falls-back-to-polling-with-one-line`, with the real
  pod, kills it; skipped without `CLOGEM_FSWATCHER`. `dev_round3_test`'s
  `the-real-pod-quitting-at-ctrl-c-is-not-reported` now expects silence
  either way). Ctrl-C signals the pod with dev, and the pod can die before
  dev's shutdown hook says it is stopping, so the follower waits up to
  `signal-grace-ms` (1 s) for that, as the pod's stderr does, before it
  falls back: a stop never prints the line or starts a poller
  (follow-ups.1, `a-pod-that-dies-at-ctrl-c-is-no-fallback`).
- **The polling fallback's first rebuild says `(full rebuild)`** (item 11),
  not `(6 files changed)`, the count of the watched roots submitted to
  force it (`the-fallback-s-first-rebuild-says-full-rebuild`), and reloads
  the page. The marker the poller submits for it is a path on no system,
  taken out before any path function sees it: the first marker,
  `"<full rebuild>"`, is no valid Windows path, and the rebuild handed it
  to `fs/` functions (`gone-temp?`; `theme-namespaces` under
  `--reload-code`), a false `build failed` there (follow-ups.1,
  `the-full-rebuild-marker-is-never-a-path`).

- **A `:build :out` in `site.edn` outside the site directory gets a hint
  that helps** (item 6). With `:build {:out "dist"}` and `dist` a link out
  of the site, the hint recommended "a directory of its own, such as the
  default "dist"" — the folder refused. It now says a value in
  `site.edn` must stay inside the site, and to build elsewhere, through a
  link or `../dist`, to remove `:build :out` from `site.edn` (the default
  `dist` may be a link) or pass `--out`; the other refusals keep their
  hint (`an-out-outside-the-site-gets-a-hint-that-helps`).

- **A pre-encoded `:nav` link ending in `/` is encoded once** (item 8,
  pre-existing before Task D). A site-page link such as
  `/%E6%96%87%E6%A1%A3/` or `/my%20docs/` was percent-encoded whole, so
  its escapes became `%25E6…` and `%2520`. The page part is still encoded
  segment by segment, except that a valid `%XX` escape is kept
  (`u/url-encode-path-once`), so `/文档/` and `/%E6%96%87%E6%A1%A3/` give
  the same href, under any base and language prefix, while `#`, `?` and
  the sub-delimiters stay encoded: they are part of a page's name, and
  `/tags/c#/` is the tag page of C#, `/tags/c%23/`. (The first fix encoded
  it as an owner's href is, `u/url-encode-href`, which keeps them, so
  `/tags/c#/` became a link to `/tags/c` and `/categories/a?b/` a query;
  `:theme :update-bar :more-article`, which goes the same way, too —
  follow-ups.1.) A link without the slash gets the base only and is
  appended as written, as since P4-D.4, so `/my docs` keeps its raw space,
  as it did before (`a-pre-encoded-nav-link-is-not-encoded-twice`: `/文档/`,
  `/my docs/`, pre-encoded forms, `/tags/c#/`, `/categories/a?b/` and
  `/a&b=c+d/`, with and without the slash, under `/` and `/my docs/`, on an
  English and a Chinese page; `more-article-is-encoded-as-a-nav-link-is`).

- **An unknown command-line option is a warning** (item 10), one line on
  stderr for each, naming it and the task's options — `clogem-press: bb
  build: unknown option --prot (ignored); its options are --site-dir, …,
  --help.` — and the task runs as before: it was silently ignored, and an
  error would break scripts that pass one. `bb dev`'s own rebuilds, which
  hand `build` dev's options, say nothing
  (`an-unknown-option-is-a-warning-naming-it-and-the-task-s-options`, in
  process and through `bb.edn`).
- **`--help` names no fix round or design section** (item 9): `bb clean
  --help` ended "(P4-D.4 item 3)" and `bb fetch-tool --help` began its
  parenthesis "§4;". Both moved to code comments
  (`no-task-s-help-names-an-internal-round`, over every task).

### Changed (P4 follow-ups)

- **`theme.css` drops E2's redundant `overflow-wrap: anywhere`** on a
  list row's link (item 14): with `min-width: 0` the link shrinks, and the
  body's `overflow-wrap: break-word` already breaks a long word. 11,970 →
  11,965 B gzipped (44,918 → 44,893 B). Nothing renders differently: every
  row link's line boxes and every page's scroll size were the same, with
  the declaration and without, on the 214 demo pages with rows at 320, 360
  and 1280 px and with parity's long words ("Configuration", 40 and 60
  characters) on the zh-Hans home; full-page screenshots were identical
  wherever two shots of the same page agreed; `layout`, `modes` (the page
  styles), `parity` and `overflow` browser tests pass.

### Documentation (P4 follow-ups)

- **Task D's entry on `:min-bb-version` said `bb.edn` "requires" babashka
  1.13.219** (item 7). babashka only warns about an older version and runs
  the task anyway; the entry now says so.

### Tests (P4 follow-ups)

- **`bb clean` refuses a marked output directory with a `.git` below its
  top level** (item 5): `dist/deploy/site/.git/` or a worktree's
  `dist/deploy/.git` file. The code refused both; nothing tested it, and
  a `clean` that checks only `dist/.git` passed every test
  (`clean-refuses-a-marked-directory-with-a-git-below-its-top`).
- **Back to top's mobile placement rule is guarded by its offset check**
  (item 12, `layout.test.mjs` item 13). The re-pinned check probed only the
  prev/next links' centres, which back to top covers neither with the
  mobile placement rule (`@media (max-width: 50rem)`: 16 px from the right,
  24 from the bottom) nor without it, so it passed with the rule removed.
  Comparing boxes would not do either: with the rule in place the button
  still covers the right end of Next's box (about 430 px², 380 without).
  What fails without the rule, whatever the fonts, is item 13's offset
  check (`32 px from the right, 40 from the bottom, want 16,24`), and the
  test now says that is the guard; the prev/next check keeps its centres
  and says so. A check added first, the update bar's and the footer's
  links compared rect against rect from there to the page's end, depended
  on the fonts both ways and is dropped (follow-ups.1): with the rule in
  place, DejaVu Sans's footer line reaches under the button (5 × 16 px),
  and without it a serif one clears it, where Liberation Sans missed by
  4 px.
- **`parity_test`'s `the-home-s-more-follows-more-article`** (item 13): its
  first `testing` label still said the right bar's More "goes to the
  archives as before"; since the merge with E1a it checks that a simple
  home's right bar has no update bar, and the label says so.
- **`dev_round2_test`'s `a-replaced-watched-directory-is-registered-again`**,
  once seen failing under load (item 15), passed `bb test --pattern
  dev-round2` 10 times in 10, so it is left as it is; it already waits on
  each condition (`wait-until`), not on a fixed sleep.

### Merged with Task G (P4 follow-ups)

`main` with Task G merged in cleanly apart from this file's intro, where
both sentences are kept and this block moves below the support page's,
first among the task blocks. Together: 885 tests, 0 failures.

**Task G — cross-language search** (§11.3 item 17, D-P4-17, D-P4-18):

### Added (G)

- **Config**: `:search {:cross-language true}` is read
  (`clogem.config/cross-language?`, `check-search!`). 0.2.0 accepted the
  key as planned and ignored it; it now turns on the "Search all
  languages" checkbox. `true` and `false` are the values — nil is off, and
  anything else is one warning naming it, repaired to false, so no site
  that built before starts failing. `true` under `:provider :none` (what
  `--no-search` sets) says nothing and does nothing.
- **Merge data on the page** (`clogem.search/indexed-langs`,
  `merge-data`; `render/page-map`, `theme/layout`): a page that offers the
  checkbox carries `data-clogem-merge` on its `<pagefind-config>` — the
  encoded base (so `base + "pagefind/"` is the element's own
  `bundle-path`), its own language's label, the two labels the row shows,
  and the OTHER indexed languages as {Pagefind key, label} in
  `:langs :priority` order. "Indexed" is the languages with an article
  variant, which is exactly what carries `data-pagefind-body`. No
  attribute where the page's own language has no indexed pages (there
  Pagefind already falls back to the largest index) or where no other
  language has any. The data is computed once per language, beside the
  search UI strings, so articles, homes, indexes and `404.html` all get
  it; `css/search-all.css` follows Pagefind's stylesheet in the head and
  `js/search-all.js` follows `js/search.js`.
- **`js/search-all.js`** (new, 1,844 B gzipped; budget 2,048): the
  checkbox row inside the search dialog, built from elements and text
  nodes (no `innerHTML`), and a second, named Pagefind instance
  (`clogem-all`) that merges the other languages' indexes at query time —
  absolute merge URLs with `baseUrl`, Pagefind's own lowercased language
  keys, `indexWeight` 0.001 and a `clogem-language` `mergeFilter` per
  index. Ticking the box swaps the rendered modal's input, summary,
  results and keyboard hints for that instance's and adds a language
  filter; unticking swaps them back. The typed term carries over, focus
  stays on the checkbox, and the choice is remembered as
  `clogem-search-all` (`"1"`, removed when unticked; every storage access
  in a try/catch, blocked storage meaning off). The default instance
  never merges, so a reader who never ticks the box downloads no other
  index.
- **`css/search-all.css`** (new, 368 B gzipped; budget 512): the row's
  layout, and the full-width dropdown wrapper that keeps Pagefind's filter
  menu inside the viewport at 320 px (its own rules carry (3,0,0), hence
  one `!important`). Colours are Pagefind's `--pf-*`, which the theme
  already maps, so dark and read follow; print hides the row with
  `.clogem-search`. `theme.css` is untouched (11,970 B gzipped, as before).
- **`:nav/search-all`** ("Search all languages") in all five theme i18n
  files; the filter's label reuses `:nav/language`.
- Both files ship only where `:search :cross-language` is true
  (`assets/skip?`).

### Changed (G)

- `config.example.edn` documents `:cross-language` and drops "deferred to
  Phase 4 (D-P3-11)".
- **The demo turns the feature on** (`examples/demo-site/site.edn`:
  `:search {:provider :pagefind :cross-language true}`), so CI's browser
  job covers it (D-P4-18); its `doctor` is unchanged at one warning.
  CI's `--no-search` step greps for `js/search-all.js` and
  `css/search-all.css` too.
- **DESIGN** §6.7 records what was built and four more facts of the 1.5.2
  bundle, re-read in `pagefind.js` and `pagefind-worker.js`: a merge's
  `language` must be Pagefind's lowercased key, or `findIndex` silently
  falls back to the largest index; each index segments the query by its
  own language; an absolute merge path gives absolute result URLs unless
  `baseUrl` is passed (the worker has no `document`, so the same-origin
  strip never runs there); and the language filter is `mergeFilter`. §8,
  §10 and §11.3 items 17 and 18 mark cross-language search done, with
  the 0.3.0 release the next, separate step; §11.2 item 43 points to it.
- **README**: a *Cross-language search* paragraph in Theme; the CSP
  paragraph names `js/search-all.js` (same-origin; merges need nothing
  beyond Pagefind's `worker-src 'self'` and `'wasm-unsafe-eval'`); the
  support page's approval step records the owner's approval of the new
  clause; the
  test count.
- **The support page's privacy note** now says a site keeps **up to four**
  things in its reader's browser, the fourth being the cross-language
  search choice on a site that offers it, in all five languages
  (`support/i18n/*.edn`). **The owner approved the new clause on
  2026-10-26** (below); the approval mark stays where it is. DESIGN §7.5 item 5 lists
  `clogem-search-all`. The rebuilt pages are 34,880 / 36,259 / 36,144 /
  35,820 / 35,936 bytes, 179,039 in all (README, above).

### Tests (G)

- `search_test.clj`'s Task G section, on the five-language fixture: each
  page's attribute parses back to its own label plus the other four
  languages' lowercased keys, in priority order; `base` + `pagefind/`
  equals `bundle-path` under `--base /x/` and `/文/`; with no `ms`
  variant, `ms` pages carry nothing and the others exclude `ms`; with one
  indexed language nothing is emitted (though both files still ship); the
  key absent, false, or true under `:provider :none` gives a byte-identical
  `dist/`; the head's order; `404.html`; `check_search.clj`'s `<body>`
  regex still matches; and, with `CLOGEM_PAGEFIND`, each page's keys plus
  its own equal `pagefind-entry.json`'s (that one passes the binary on, as
  `the-real-pagefind-indexes-five-languages` does: `with-site` binds
  `search/*env*` to `{}`, so it had run whatever the tools cache held, and
  failed on a machine with an empty cache). The head's order is held on a
  build that has `overrides/custom.css` and an analytics loader, so
  "custom.css the last stylesheet, analytics last of all" is checked, not
  assumed.
- `test/clogem/search_all_test.clj` (new): the two files' gzip budgets,
  printed as `nav.js`'s are; no `innerHTML` and no modal built in JS; the
  stored key written the way `support_page_test`'s scan reads it, with
  every access wrapped; the dropdown-wrapper rule and no colour of its
  own; the row hidden in print by `theme.css`'s `.clogem-search` rule; the
  string in all five languages. The same file holds item 1's config
  tests: `"yes"`, `1` and `:true` each give exactly one warning and leave
  the feature off; nil and false are silent; `:cross-language true` draws
  no "planned" warning; `:crossLanguage`'s hint lists `:cross-language`
  among the options; the example config and the demo stay quiet. `config_test.clj`'s
  `a-new-key-is-registered-in-one-place` now uses a synthetic `:zz-test`
  key (`with-redefs`), so it no longer depends on which real key is still
  planned. Each failed first on `f44fcf6`.
- `support_page_test.clj`'s `the-privacy-note-names-every-stored-key` now
  pins four keys, and `the-privacy-note-keeps-to-what-clogem-press-stores`
  the new openings; the first failed as soon as `js/search-all.js`
  existed, and passed with the note.

- `test/browser/search-all.test.mjs` (new, 45 checks): the English home
  finds no `百分号` unticked and fetches only the English `pf_meta`;
  ticking it finds `/pages/cjk001/`, stores `"1"` and survives a reload,
  and unticking undoes both. The zh-Hans home's first `permalink` result
  is `/zh-Hans/pages/relocated/` (the merges' weight). Malay and
  Traditional Chinese keep their own summary strings in both states, with
  no nested dialog. The filter's values are the language names, and
  *Bahasa Melayu* leaves `/pages/1bd8ea/`. At 320 and 360 px, ticked and
  with the menu open: no overflow, the menu inside the viewport, one
  close button. Tab reaches the box and Space toggles it, focus staying
  on it; blocked storage leaves it off and working; no request under
  `pagefind/` fails, no result href is absolute; print draws no row. A
  temp build under `--base /clogem-demo/`, served with `bb serve`, gives
  `/clogem-demo/pages/cjk001/`.

### Fixed (G.1)

- **A merged index that fails to load no longer breaks the ticked
  search** (item 1). Pagefind 1.5.2's Component UI awaits each
  `mergeIndex` in turn with no isolation, so one other language's
  `pf_meta` answering 404 (a partial or stale deploy) or a dropped request
  left every ticked search at "Searching for …" with no results, the
  reader's own language included — and with `"1"` stored, every later
  page opened ticked and broken. The named instance has no `error` event
  for this (it fires only when `pagefind.js` itself fails to import), but
  its `triggerLoad()` rejects while a merge cannot load, and each search
  retries the load: `js/search-all.js` now listens for the instance's
  `loading` event, and when its load rejects, that page falls back to the
  default instance — its components swapped back, the box unticked there,
  the typed term carried over and focus kept in the input (or on the
  box). The stored choice is left as it is, since a later page may load.
  `search-all.test.mjs`: with `"1"` stored and the zh-Hans `pf_meta`
  routed to 404, and again with every foreign `pf_meta` aborted,
  `pagefind` finds `/pages/e19a7c/` on the English home, the box ends
  unticked, storage is still `"1"`, the term is kept and focus is in the
  input (14 checks; the search and untick checks failed on `b35ca17`).
- **A dialog opened before `js/search-all.js` runs keeps its search**
  (item 2). With `"1"` stored, the start-up swap put the named instance's
  empty input in place of one the reader had already typed in: the term
  and results were lost and focus dropped to `<body>` with the dialog
  open. The start-up path and the checkbox now share one swap, which
  carries the term over and, when focus was in the outgoing components,
  moves it to the incoming input. `search-all.test.mjs` holds the
  script's response back (`page.route`), opens the dialog and types
  `pagefind`, then releases it: the box is ticked, the term and
  `/pages/e19a7c/` survive, and focus is on the input in the open dialog
  (7 checks; the term, results and focus checks failed on `b35ca17`).
- **`config.example.edn` says when the two files ship** (item 3): with
  the key on *and* `:provider :pagefind`, so not under `--no-search`, as
  `assets/skip?` decides through `config/cross-language?`; it said
  "whenever this is true". `search_all_test.clj`
  `the-example-says-when-the-files-ship` checks `skip?` both ways and the
  sentence (failed on `b35ca17`).
- `js/search-all.js` is 1,935 B gzipped (it was 1,844; budget 2,048,
  unchanged): the header comment is shorter. `bb test`: 870 tests
  (README's count).

### Needs native review (G)

Machine-made, to be read by a native speaker:

- `:nav/search-all` in `src/clogem/theme/resources/i18n/ms.edn`
  ("Cari dalam semua bahasa") and `ta.edn`
  ("எல்லா மொழிகளிலும் தேடு"), marked in each file.
- The support page's new privacy clause in `support/i18n/zh-Hans.edn`,
  `zh-Hant.edn`, `pt-BR.edn` and `es-ES.edn` (the sentences below).

### Approved by the owner (G)

The privacy note's wording is the owner's (DESIGN §7.5 item 5). The owner
approved these five sentences on 2026-10-26 (PR #19); a push to `main` that
touches `support/**` publishes the page. Each replaces the "three
things" sentence in its file; the rest of the paragraph is unchanged.

- **en**: "Of its own, a site keeps up to four things in its reader's
  browser (the colour mode they chose, the language they chose, which
  “also available in …” language banners they dismissed, and, on a site
  that offers it, whether they chose to search all languages) and sends
  none of them anywhere; …"
- **zh-Hans**:
  "网站本身最多会在读者的浏览器中保存四样东西（读者选择的配色模式、选择的语言、读者关闭了哪些“也有其他语言版本”的语言提示条，以及在提供此选项的网站上，读者是否选择搜索所有语言），并且…"
- **zh-Hant**:
  "網站本身最多會在讀者的瀏覽器中儲存四樣東西（讀者選擇的色彩模式、選擇的語言、讀者關閉了哪些「也有其他語言版本」的語言提示列，以及在提供此選項的網站上，讀者是否選擇搜尋所有語言），並且…"
- **pt-BR**: "Por conta própria, um site guarda até quatro coisas no
  navegador do leitor (o modo de cor escolhido, o idioma escolhido, quais
  avisos de idioma “também disponível em…” o leitor fechou e, em um site
  que oferece essa opção, se escolheu pesquisar em todos os idiomas) e…"
- **es-ES**: "Por sí mismo, un sitio guarda hasta cuatro cosas en el
  navegador del lector (el modo de color elegido, el idioma elegido, qué
  avisos de idioma «también disponible en…» ha cerrado el lector y, en un
  sitio que lo ofrezca, si ha elegido buscar en todos los idiomas) y…"

**Task E1a — blog identity and banner** (§11.3 item 13, first half; D-P4-13):

### Added (E1a)

- **Config** (`clogem.config/check-identity!`): `:theme :blogger`,
  `:social`, `:footer`, `:banner-bg`, `:body-bg-img`,
  `:body-bg-img-opacity`, `:body-bg-img-interval`, `:title-badge` and
  `:title-badge-icons` are read. Every problem is a warning that drops the
  value or sets its default (`:title-badge true`, opacity 0.5, interval
  15). Images (`:avatar`, `:body-bg-img`, a string badge icon, a social
  `:svg`, `heroImage`) are an `https:` URL, a `data:image/…` URI or a site
  path, given a leading `/`; `http:` warns (mixed content), `//…` and other
  schemes are dropped. The keys inside `:blogger`, `:footer` and each social
  link are known keys, and `check-keys!` walks `:theme :social :icons` as it
  walks `:nav`; vdoing's `createYear`, `copyrightInfo` and `iconClass` name
  the kebab key, `iconfontCssFile` is not needed.
- **Social links**: in the blogger card and the footer, `rel="me"`, named
  by their `:title` (else the host or mail address, with a warning), in the
  same tab. One resolver, `clogem.sprite/social-icon`: a vendored brand
  (brands win, so `:x` is X) or a UI icon; an unknown icon warns and shows
  the link icon. `sprite/brands-in-use` is the brands the links name, so the
  sprite holds exactly those, and the icon helper renders no other brand.
- **The blogger card** (vdoing's BloggerBar: avatar, links, name, slogan)
  first in the home's right bar, and a hidden copy first in the mobile
  drawer, shown only with scripting at 50rem and below — where the right
  bar's copy (`clogem-blogger--paired`) hides. Opening the drawer focuses
  its first link (nav.js unchanged: it focuses the drawer's first link).
- **The footer**: the social links, `© {years} {copyright-info}` and
  *Powered by clogem-press* (`:footer/powered-by`, linked). The years are
  `:create-year`–`:end-year`, the end year defaulting to the newest dated
  article's. `:copyright-info` is trusted HTML (D-P4-13), per language along
  the fallback chain with `""` suppressing it, and `{{base}}` interpolated.
  `:powered-by false` drops the credit; an empty footer is omitted.
- **The home banner** (vdoing's hero) between the navbar and the shell, on
  the home's first page, when its front matter has `heroImage`, `heroText`,
  `tagline`, `actionText` with `actionLink`, or `bannerBg`. `heroText` is
  the `<h1>` of a home without one (the fallback title then goes: one h1
  per page). `bannerBg` beats `:theme :banner-bg`: auto (a grid on
  #28282d, white, 14.67:1), none, an image behind the text, or a trusted
  `background: …` style attribute; a bad one is auto, with a warning naming
  the home file. The action is `--mainBg` on `--accent` (≥ 5.19:1 in every
  mode; never white, which is 2.75:1 in dark).
- **The title badge**, first in article and archive `<h1>`s: book-open,
  pencil or hash (or `:title-badge-icons`), chosen by the CRC32 of the
  identity permalink (of the archive's root path), so every language and
  build shows the same one. `aria-hidden`; the Pagefind result title is
  unchanged. Not on catalogues, categories or tags, as in vdoing.
- **The body background image**, `img.clogem-body-bg` fixed behind the page
  before back to top, its opacity a `data-o` in tenths (no style
  attribute). Two or more images rotate with `js/bodybg.js` (deferred,
  same-origin, 445 B gzipped, budget 600), which does nothing under reduced
  motion or while the page is hidden, and ships only then.
- **`css/identity.css`** (1,410 B gzipped, budget 2,048): the card, the
  links and the background image, linked right after theme.css and shipped
  only while a site uses one of them (`config/identity-css?`). theme.css
  gains only the badge, the banner and the footer lines: 11,354 → 11,717 B
  gzipped (+363) of 12,288.
  Merged with Task E2 (+259, 11,354 → 11,613 on its own): 11,970 B
  gzipped of 12,288.
- Strings `:blogger/label`, `:social/label`, `:footer/powered-by` in all
  five languages.

### Changed (E1a)

- **The footer text**: `clogem-press 0.2.0` gives way to the copyright
  line and *Powered by clogem-press*; the version stays in `<meta
  name="generator">`.
- **Title badges are on by default**, as in vdoing; `:theme :title-badge
  false` gives exactly the old `<h1>`.
- **A home with `postList: simple` loses *Recently updated***: its list is
  the recent articles already (vdoing shows UpdateArticle there).
- **The "planned" warnings are gone** for the E1a keys (and their vdoing
  spellings say only which key to write).
- **A social `:svg` is an `<img>`**, not a sprite symbol, so it is not
  recoloured.
- `:theme :body-bg-img` forces `:page-style :card`, with a warning, as
  vdoing does.
- The demo shows the identity: a blogger card, four social links (its
  sprite: the UI icons plus github and mastodon, 11,648 B), a footer from
  2024, and a banner on its English home. Its `assets/demo.png` was the
  PNG signature written out as text; it is a real 64×64 PNG now.

### Tests (E1a)

- New `clogem.identity-test`: every config rule (0 errors each; the
  0.2.0-era `:footer "x"`, `:social []` and `:blogger "me"` one warning
  each), the sprite of a temp site with github, x, `:mail` and an unknown
  `:yuque` (exactly the UI icons plus brand-github and brand-x, every `<use>`
  resolving), the right bar's order under detailed and simple, the drawer
  card, the footer's years, per-language info and `{{base}}`, "Powered by"
  in five languages, the banner (h1, page 2, base, each `bannerBg` kind,
  `:theme :banner-bg`, the warning), the badge (CRC32, stable across builds
  and variants, off, custom), the background image (one, two, base, no
  inline script), the budgets and the colour pairs in every mode, the
  demo, whose new links all resolve under `--base /clogem-demo/`, and, with
  `CLOGEM_PAGEFIND`, every indexed article's result title equal to its
  `<h1>` text with the badge in place.
- New `test/browser/identity.test.mjs`; its reduced-motion check records
  every `src` the image takes (one sample missed a full red → blue → red
  turn).
- Mutants, each killed: an unknown social icon kept (`social-links`,
  `the-sprite-holds-exactly-the-brands-in-use`, …); identity.css linked
  with nothing on (`identity-css-ships-and-is-linked-only-when-used`);
  `icon*` taking a brand not in use (`the-icon-helper`); the drawer card
  without `hidden` (`the-drawer-card`, `the-demo-shows-the-identity`,
  layout_test's drawer pin); the end year from the create year
  (`footer-years`); the hero on `/page/2/` (`the-hero`); the badge hashed
  on the language URL (`the-title-badge`); bodybg.js shipped with one image
  and the reduced-motion check removed (`the-body-background`, and the
  browser test).
- Pins changed deliberately: `config_test`'s pre-flight warnings (6 → 4),
  `a-new-key-is-registered-in-one-place` (now Task G's
  `:search :cross-language`) and the `bodyBgImgInterval` messages;
  `theme_modes_test`'s icon helper (a brand not in use throws) and the
  demo's sprite (two brands and their notice); `build_test`'s simple home
  (no update bar) and the indexed-page `<h1>` (a badge may come first);
  `layout_test`'s drawer (the hidden card may come first);
  `layout.test.mjs`'s `:nav []` drawer case drops the card, as its subject
  is the sidebar, and its "a page that fits, then grows" case runs at
  1280×1100 instead of ×1024: the demo's new footer made
  `/zh-Hans/pages/643259/` 7 px taller than 1024.

### Fixed (E1a.1)

- **An owner's site path keeps its `?query`, `#fragment` and `%XX`
  escapes** (item 1). Every identity URL — social links, the avatar, a
  social `:svg`, `heroImage`, `bannerBg`, the body image, badge images —
  was encoded whole, so `/about/#contact` became `/about/%23contact`,
  `?v=2` became `%3Fv%3D2`, and `/assets/my%20pic.png` (the spelling
  `image-value` asks for) became `my%2520pic.png`: a file with a space in
  its name could not be used. New `u/url-encode-href` keeps the query, the
  fragment and every valid escape, and encodes non-ASCII and other unsafe
  characters once (`identity-urls-keep-their-query-fragment-and-escapes`,
  under `/` and `/my docs/`, each URL resolving to its file in `dist/`;
  `an-owner-href-keeps-its-structure`).
- **A rejected front-matter `heroImage` warns once** (item 2), not once per
  page of a paginated home (3 times in `build`, 6 in `doctor`, for three
  pages): it is checked on the first page only. A rejected `heroImage`
  with nothing else asking for a banner no longer leaves an empty one
  (`a-rejected-hero-image`: `http:`, `//host/x.png` and a bare word, and
  the paginated case in `build` and `doctor`).
- **A home that inherits its banner from `index.md` marks its language**
  (item 5): the demo's `/zh-Hant/`, `/ms/` and `/ta/` showed the English
  tagline and action as if in the page's language. The title, tagline,
  action (and an `heroAlt`'s image) inherited as a string from the
  site-default file now carry `lang` (`config/html-lang`); a per-language
  map is resolved as before (`an-inherited-hero-names-its-language`, and
  `the-demo-shows-the-identity`: `/ms/` marked `lang="en"`, `/zh-Hans/`,
  whose own `index.zh-Hans.md` sets no hero key, has no banner).
- **A blank language in a social link's per-language `:title` falls back**
  (item 3): `{:en "Mail" :zh-Hans ""}` gave every zh-Hans page a link with
  `aria-label=""`, silently. The blank entry now warns and is dropped, so
  the fallback chain names the link (`a-blank-language-in-a-link-title-falls-back`).
- **`:body-bg-img-interval` is at most a day** (item 4). Above 2³¹−1 ms
  Chromium's `setInterval` fires at once, so `3000000` swapped the image
  nonstop (529 requests in 2 s). A finite number from 1 to 86,400 is kept;
  above that it warns and is 86,400; anything else (`##Inf` and `##NaN`
  included) warns and is 15, as before (`body-bg-img-values`,
  `the-body-background`: `data-interval="86400"`).
- **A bare word is no image** (item 8b): `:banner-bg "dark-blue"`,
  `:body-bg-img "transparent"` and `:title-badge-icons ["star"]` became
  `/dark-blue` and so on, and dangling `<img>`s. A value with neither `/`
  nor `.` now warns and is dropped (`:banner-bg` is auto); for a badge
  icon the warning says to write a keyword (`a-bare-word-is-no-image`).
- **One bad blogger value, one warning** (item 8a): `{:blogger {:name ""}}`
  also said the card had "neither a :name nor an :avatar". And an explicit
  `nil` inside an identity map (`{:footer {:powered-by nil}}`, `{:blogger
  {:name "J" :avatar nil}}`, a social link's `:svg nil`) is absent, as a
  top-level `nil` is, instead of a warning
  (`the-blogger-card-needs-a-name-or-an-avatar`).
- **A raw-HTML `<h1>` in the home's body is its `<h1>`** (item 8c), so a
  `heroText` beside it is a `<p>`, not a second `<h1>` (`the-hero`).
- **Text drawn on the page keeps 4.5:1 over a body image** (item 6). The
  footer, the home's pagination and a clear banner's text sit on the page
  with no box, so over a 0.5-opacity image against the mode they fell to
  1.32:1 (footer) and 2.56:1 (pagination, banner title) on a black image
  in light mode, 1.22:1 and 2.18:1 on a white one in dark. While a body
  image is on, identity.css gives them a `--mainBg` backdrop, on screen
  only (`identity.test.mjs`: black and white 64×64 images in light and
  dark, each text's colour against the colour its box paints most in a
  screenshot).
- **No gap above the home's right bar on mobile** (item 8d): with the
  paired blogger card hidden at 50rem and below, the next box still took
  `section + section`'s 1rem (595.1 against the aside's 579.1 at 360 px).
  It is 0 there now (`identity.test.mjs`, at 360 and 800).
- **The demo's `overrides/custom.css` marker shows once** (item 8e), on the
  footer's first `<p>`, not on both (`identity.test.mjs`; `modes.test.mjs`
  unchanged).
- **Tests (item 9)**: `layout.test.mjs`'s "end of `/pages/17c887/`, then
  600 px more below" check no longer hangs on the footer's height (it went
  red at `545d09c` and green at `849cd4e` with no test change). The
  viewport is sized from the page's own geometry so the last heading is in
  view below the line at the end, which makes the end's choice and the
  line's differ; the check asserts that they do, then that growth gives
  the line's entry back. It runs as built, with the footer 150 px taller,
  and with it 150 px shorter (it is 147 px, so gone), and all three fail
  with `toc.js`'s `ResizeObserver` removed.
- **Tests (item 7)**: the stylesheet order theme, identity, highlight is
  pinned on a highlighted build with the fake Chroma
  (`highlight_test`'s `identity-css-sits-between-theme-and-highlight`;
  `bb test` otherwise builds with highlighting off, so moving
  `identity/head` below highlight.css changed no test); `actionText`
  without `actionLink`, or the reverse, gives no banner
  (`action-text-alone-is-no-banner`); the badge test's three articles each
  have a zh-Hans variant, and it asserts that hashing the language URL
  would pick another icon for at least one of them (it does for all
  three), so the fixture discriminates (`the-title-badge`).
- **A hero value from a per-language map's other language names it**
  (E1a.2, item 1): `heroText: {en: Hello}` showed `Hello` on `/zh-Hans/`
  unmarked, though an inherited plain-string `tagline` beside it carried
  `lang="en"`. A map's value taken from another language along the
  fallback chain now carries that language's `lang`; the page's own
  language, from a map or from its own index file, stays unmarked
  (`a-hero-in-its-own-language-is-unmarked`: an `index.zh-Hans.md` with
  its own `heroText` and `tagline`, `{en: Hello, zh-Hans: 你好}` and
  `{en: Hello}` on `/zh-Hans/`).
- **A value with a scheme is no bare word** (E1a.2, item 2):
  `javascript:x` or `mailto:x` as `:avatar`, `:body-bg-img`, a social
  `:svg` or a badge icon was called "a bare word, not a file", and for a
  badge the warning suggested the keyword `:javascript:x`. It gets the
  "not an https: URL, a data:image/… URI or a site path" warning again
  (`a-bare-word-is-no-image`).
- **An `<h1>` inside an HTML comment is not the home's `<h1>`** (E1a.2,
  item 3): `<!-- <h1>old</h1> -->` in the body made `heroText` a `<p>`, and
  the page had no `<h1>` at all (`the-hero`).
- **A `nil` in a social link's per-language `:title` is absent** (E1a.2,
  item 4): `{:en "L" :zh-Hans nil}` threw the whole title away, with a
  warning, and named the link `l.example`; it is now `{:en "L"}`, silently.
  A title blank in every language (`{:en "" :zh-Hans ""}`) warns once, not
  three times (`a-blank-language-in-a-link-title-falls-back`).
- **Docs** (E1a.2, item 5): `css/identity.css` is 1,410 B gzipped after
  E1a.1 (the Task E1a block said 1,222); README's `bb test` count is
  775 tests / ~50,100 assertions.

### Merged with Task E2 (E1a)

- **Tests**: `parity_test.clj`'s `the-home-s-more-follows-more-article`
  asserted a 10-row *Recently updated* box in the zh-Hans home's right bar,
  which E1a drops under `postList: simple`. It now asserts the box is absent
  there, and checks the right bar's *More →* (the archives, else
  `:more-article`) on a temp build with a `postList: detailed` home.
  README's `bb test` count is 800 tests / ~52,100 assertions.
- **Browser pins**: `parity.test.mjs` item 1 counts the links named
  "GitHub" in the navbar only — E1a's footer has a social link of that
  name. `layout.test.mjs` item 13's "back to top covers neither prev/next
  link" at 360 scrolls prev/next to the viewport's bottom edge, not to the
  page's end, below which E2's update bar and E1a's footer now push them
  out of view.

### Needs native review (E1a)

- `:blogger/label`: ms "Tentang penulis", ta "ஆசிரியர் பற்றி".
- `:social/label`: ms "Pautan sosial", ta "சமூக ஊடக இணைப்புகள்".
- `:footer/powered-by`: ms "Dikuasakan oleh {{name}}", ta "{{name}} மூலம் இயக்கப்படுகிறது".

**Task E2 — article-page parity** (§11.3 item 16, D-P4-13):

### Added (E2)

- **The edit link** (D-P4-16; vdoing's `PageEdit`): with `:content
  :edit-link`, every article and catalogue page (`article: false` ones
  included) gets `<div class="clogem-page-edit">` right after
  `</article>` — before prev/next, outside `data-pagefind-body` — holding
  *Edit this page* (`:page/edit`, unused until now, with the `pencil`
  icon). It opens the variant's own file (`:dir`, then the file relative
  to the site, percent-encoded per segment; the host never) in the same
  tab, as clogem's `:nav` links do (vdoing opens a new tab): `<repo>/edit/
  <branch>/<path>` on github.com and any other host, Gitee included;
  `/-/edit/` on gitlab.com; `/src/…?mode=edit&spa=0&at=<branch>&fileviewer=
  file-view-default` on bitbucket.org, as vdoing's `createEditLink`. Front
  matter `editLink: false` hides it; any value but `true`/`false` is a
  warning naming the file, and is ignored. Never on a home or an index
  page. Print hides it. `render/site-rel` is now `config/site-rel`, shared.
  The demo turns it on, pointing at its own files in this repository.
- **Last-updated dates from git** (D-P4-16; vdoing's `lastUpdated`): with
  `:theme :last-updated true`, `render/render-site` runs one `git -C
  <site> log --format=%x01%aI --name-only -z --relative -- <content>`
  (new `clogem.gitlog`, after the highlight pre-pass) and keeps each file's
  newest commit, carried on the config as `:clogem/git-dates`. The edit
  line then ends with `<span class="clogem-page-edit__updated">` — the
  `clock` icon and `<time datetime="<ISO>">` holding `:page/last-updated`
  (unused until now) with the author's calendar date, `yyyy-mm-dd` in
  every language (babashka has no zh, ms or ta locale). git runs under
  `LC_ALL=C`, so a translated git's "not a git repository" is still
  recognized. Outside git, or with git missing, there are no dates,
  silently; a shallow clone (`git
  rev-parse --is-shallow-repository`) is one warning naming `fetch-depth:
  0`, and any other git failure one warning quoting git's first stderr
  line ("dubious ownership", a repository with no commits) — no dates
  either way. `doctor` never runs git, and a build with the key off never
  does. Print keeps the date. The demo leaves it off: CI's checkout is
  shallow.
- **The update bar under articles** (D-P4-13; vdoing's `UpdateArticle`,
  on by default): every article page (`:article?` — never a catalogue or
  an `article: false` page) ends, after the comments and inside `<main>`,
  with `<section class="clogem-update-bar">`: `:index/updates`, the 3
  newest articles of `(:posts model)` (newest first by front-matter date,
  as vdoing's `$sortPostsByDate`) other than the page itself, as index
  rows (`layout/article-row`, fallback badge included), then *More →*. No
  other article, no section. `:theme :update-bar :show-to-article false`
  drops it; print hides it.
- **`:theme :update-bar :more-article`** (`parity/more-article-href`):
  where every *More →* goes — through `layout/nav-href`, so `"/tags/"` is
  `/zh-Hans/tags/` on a zh-Hans page and a URL is left alone; unset, the
  archives in the page's language; with the archives off and nothing set,
  no link. The home's right-bar list uses it (it went to the archives
  root), and the `postList: simple` list now ends with one too, as
  vdoing's simple list (an `UpdateArticle`) does. The home still lists 10.
- **`titleTag` in list rows**: `layout/article-row` shows the badge of the
  variant it links (`layout/title-tag`, `data-pagefind-ignore` under
  Pagefind) right after the title and before the fallback badge, as a
  home card does — in index pages, the home's lists, the update bar and
  catalogue rows (vdoing shows it in `UpdateArticle`'s rows, not in its
  catalogue). `.clogem-row time` gets `margin-inline-start: auto`, so the
  badges stay by the title and the date at the row's end.
- **Front matter `pageClass`** (vdoing's, `Layout.vue`): a
  whitespace-separated string or a list of strings, appended to `<body
  class>` after `page-<kind>` (`check_search.clj` reads the first `page-`
  token) by `layout/page-classes`. Articles and catalogues read their
  variant's front matter, homes their `index.md` (the list options' file),
  index pages their `@pages/` file — on every page of that index. A token
  that is not `[A-Za-z_][A-Za-z0-9_-]*`, or a value that is not a string
  or a list of strings, is a warning naming the file (once per file, from
  its first page in its own language) and is dropped. A page without
  `pageClass` renders byte-identical.
- **The navbar logo** (`:theme :logo`, vdoing's `logo`; read since this
  task, it warned as planned): a site path (`/…`, not `//…`, with the
  base added) or an `https://` URL, else a warning and no logo. The brand
  becomes `<a class="clogem-navbar__brand has-logo">` holding
  `<img class="clogem-navbar__logo" alt="">` (2.2rem tall, at most 8rem
  wide; vdoing's `$navbarHeight - 1.4rem`) and `<span
  class="clogem-navbar__title">`; without a logo it is byte-identical. At
  64rem and below the title is visually hidden — still the link's name —
  as vdoing hides the site name at ≤ 959 px. A site-path logo that no
  output of the build (the site's `assets/`, the static directory, a
  theme file) writes is one warning, in `build` and `doctor`
  (`render/warn-missing-logo!`, beside `warn-skipped-links!`), and is
  linked all the same.
- **The repository link** (`:theme :repo`, vdoing's `repo`, and
  `:theme :repo-label`, vdoing's `repoLabel`): the nav's last `<li>`, `<a
  class="clogem-navbar__repo">` with `<span
  class="clogem-navbar__repo-label">` and the `external-link` icon, in
  the same tab. The label is `:repo-label` (a non-blank string or a map by
  language, else a warning and the default), else GitHub, GitLab or
  Bitbucket by host, else `:nav/source` (new, in all five languages). It
  is visually hidden above 50rem up to 64rem (a `max-width: 64rem` rule
  reset at `max-width: 50rem`), and a text link in the drawer, so it counts
  in the navbar's `shown?`: a one-language site with no `:nav` but a
  `:repo` gets the drawer. The demo sets both, with its own
  `assets/logo.svg`.
- **A 404 page** (D-P4-16; vdoing's `404.vue`, without its random Chinese
  messages): one `dist/404.html` per build, at the output root whatever
  the base (`render/page-file` honours the page-map thunk's
  `:clogem/file`, at both `uri->file` call sites), rendered through
  `layout/page` — navbar, `main.clogem-main`, footer — by
  `parity/not-found` as `page-not-found` (`<body class="lang-en
  page-not-found">`), in the default language: `:not-found/title`, `<h1>
  404`, `:not-found/message` and one link per language to its home, in
  `:priority` order and in that language's own `:not-found/home`
  (`:not-found/*`, new in all five languages; a site-added language
  without the string is marked with the language its fallback words are
  in, as the language banner is). Every link is
  root-relative with the base. It carries `<meta name="robots"
  content="noindex">` (after the verification tags) and no `:seo`, so no
  canonical, hreflang, `og:locale`, sitemap entry or language banner; no
  `data-pagefind-body`, and no JS of its own. A root `404.html` in the
  static directory replaces it, with no collision error. `bb dev` and `bb
  serve` answer a miss under the base with it, status 404 (dev injects
  its reload script); outside the base, or with no `404.html`, the stub
  as before. The demo's build is now 243 pages.

### Changed (E2)

- **`:content :edit-link`, `:theme :update-bar`, `:theme :last-updated`
  and `:theme :repo` are read** (`config/check-article-parity!`, after
  `check-page-chrome!`): 0.2.0 warned that each was planned and had no
  effect. Every bad value is a warning that repairs it to its default or
  turns the feature off, never an error, with the hint "See
  config.example.edn (vdoing's …)". `:edit-link {:repo :branch :dir}` is
  stored as `{:url :branch :dir}`: `:repo` is `"owner/name"` (GitHub's) or
  an `https://` URL, else the valid `:theme :repo`; `:branch` defaults to
  `"main"`, `:dir` to `""` (no `..` segment, no `\`). `:update-bar` holds
  `:show-to-article` (default `true`) and `:more-article` (a site path or an
  `https://` URL). `:theme :update-bar` and `:content :edit-link` are now
  walked for unknown keys, so vdoing's `updateBar.showToArticle` and
  `.moreArticle` name their kebab spellings. vdoing's `editLinks`,
  `docsRepo`, `docsDir`, `docsBranch`, `updateBar`, `lastUpdated` and
  `repo` name the keys that replace them; `editLinkText` says the label is
  the i18n string `:page/edit`. Accessors: `config/edit-link`,
  `last-updated?`, `update-bar`, `repo-url`.

### Tests (E2)

- `clogem.parity-test` (new): every rule of `check-article-parity!`, each
  with 0 errors; `:content {:edit-link true}` is one warning; no E2 key's
  warning says "planned"; vdoing's spellings, `updateBar`'s own included.
  `config_test`'s `:updateBar` row is no longer planned.
- The edit link: the exact href of `/pages/cjk001/` (a CJK path); each
  variant's own file (`/zh-Hans/pages/643259/` → `.zh-Hans.md`); the
  catalogue `/pages/559f0f/`; none on homes, `/archives/`, tag and
  category pages; one per article page; GitLab, Bitbucket, `git.example`
  and Gitee hrefs, the branch and path encoded per segment; `editLink:
  false`, `true` and `1` (one warning, ignored, also with the link off);
  the `:theme :repo` fallback; after `</article>`, no `target`.
- Last updated, on temp repositories committed with fixed dates and no
  identity of CI's own (`GIT_CEILING_DIRECTORIES` set): two commits give
  the newer date; a CJK name with a space and a `"`; a rename dated by the
  rename commit; an untracked file undated; a site in a subdirectory of
  its repository; not a repository: no dates, 0 warnings; git missing: the
  same; `git clone --depth 1 file://…`: 1 warning, no dates; a failing
  git: 1 warning quoting it; git runs under `LC_ALL=C`; the key off, or
  `doctor`: git never runs; the rendered date beside the edit link, alone
  when `editLink: false`.
- The update bar: 3 rows, never the current page, on every article page
  of the demo; after the comments; the fallback badge on an untranslated
  row; none on catalogues, `article: false` pages, homes or index pages;
  `:show-to-article false`; `:more-article "/tags/"` on a zh-Hans page and
  an `https://` URL; the archives off gives no *More*; a one-article site
  has no bar; the zh-Hans home's simple list ends with *More*, its right
  bar still lists 10.
- `titleTag` rows: `<span class="clogem-title-tag" data-pagefind-ignore="">原创</span>`
  right after the `/pages/y2025a/` link on `/archives/` and `/tags/meta/`,
  and before the fallback badge in the zh-Hans simple list, in every row
  of the demo that links it; no attribute under `:search :none`.
- `pageClass`: a token on an article (string), its zh-Hans variant (list),
  a catalogue, both homes, the archives, the tags overview and a tag's
  page, each after `page-<kind>`; `a{b` and `9bad` dropped with one warning
  each, naming the file; `5` ignored with one warning; a paginated home's
  and a category overview's bad token warn once per file; the demo and a
  page without it unchanged.
- The navbar: `:logo` and `:repo-label` rules (a path with a space is
  fine; `//…`, `http:`, a blank label, an empty map warn); vdoing's
  `repoLabel`; the demo's brand, logo
  and repo link on every navbar page, the link last in the nav, before
  the languages, with no `target`; no `:logo`, the brand unchanged; only
  `:repo` on a one-language site with no `:nav` gets the toggle and the
  drawer; GitLab, Bitbucket, `Source` / `源代码` and a per-language
  `:repo-label`; a logo under `--base /proj/`; an `https://` logo
  unchecked; a missing logo file is one warning in `build` and `doctor`.
- `test/browser/parity.test.mjs` (new): the repo link is the nav's last
  item, focusable and reached by Tab in the open drawer at 320 and 360 on
  every home and an article, its label shown; at 801–1024 the label is
  hidden and one link is named "GitHub", at 1025 and 1280 it shows; the
  logo loads, 2.2rem tall, `alt=""`, the title hidden at 1024 and below
  and shown from 1025, the brand still named by it; print hides the edit
  link and the update bar.
- The 404 page: on the demo, at the root, in English, the shell, every
  home in its own words, `noindex` before the stylesheets, no canonical,
  hreflang, `og:locale`, language data or `data-pagefind-body`, one inline
  script, not in `sitemap.xml`; `<body class="lang-en page-not-found">`
  opening with `<pagefind-config>`; under `--base /clogem-demo/` still
  `dist/404.html`, every link under the base; a static `public/404.html`
  wins with no error or warning, a rebuild keeps it, and once deleted the
  generated page comes back and is not swept; `doctor` sees no collision;
  `make-handler` answers `/nope/` with 404 and the page (dev with the
  reload script), a real file 200, outside the base the stub, a `dist/`
  with no `404.html` the stub; a site-added `ja` without
  `:not-found/home` gets its link marked `lang="en"`.
  `overflow.test.mjs` visits `/404.html` at 320 and 360.
- `layout.test.mjs` (B2b) keeps its scenarios on the demo's new pages:
  the h4-at-the-end and the page-that-fits-then-grows checks strip the
  edit line and the update bar from their routed page (`withoutE2`), so
  the page still ends where its article does; the long nav loaded at 1280
  and narrowed to 801 and 850 adds 13 items, not 10 (`longNav`'s new
  `count`), since the brand is the logo alone at 64rem and below and 10
  no longer wrap there. Item 2's 801–1600 scan, the closed drawer's
  `menu brand search mode` and the drawer-link checks are unchanged and
  pass with the logo and the repo link.

### Fixed (E2.1)

- **A row's badges keep one line.** In a list or catalogue row (a flex
  row), the `titleTag` badge shrank to its min-content width and
  stretched to the row's height: at 320 px on `/zh-Hans/` the y2025a
  row's 原创 was 26.0×108.8, one character per line; the fallback marker
  had stretched the same way since before E2. Both are now `flex: none;
  white-space: nowrap; align-self: center` in a `.clogem-row`, so the
  title wraps instead and the date stays at the row's end. `theme.css`
  is 11,594 B gzipped, 240 B over `main`. `parity.test.mjs` item 4, at
  320, 360 and 414 on `/archives/`, `/tags/meta/`, `/zh-Hans/` and a
  catalogue: each badge in a row has one line box, is no taller than its
  single-line box plus 2 px and lies within its row, and the date ends at
  the row's end; at 1280 the badge follows the title on its line.

- **A backslash in a POSIX file name is kept.** `config/site-rel` turned
  every `\` into `/`, so on Linux `content/01.G/02.back\slash.md` got the
  edit link `…/content/01.G/02.back/slash.md` and no last-updated date
  (its git key did not match). Only where `java.io.File/separator` is `\`
  is it replaced now. `parity_test.clj`
  `a-backslash-in-a-posix-file-name-is-kept`: the href ends in
  `02.back%5Cslash.md` and the page has the file's commit date.

- **A static `404.HTML` warns.** A static 404 page in another case still
  replaces the generated one (the collision rule folds case too, so this
  stays no error), but GitHub Pages and other hosts look for `404.html`,
  so the site had no 404 page and nothing said so. The build (and
  `doctor`) now warns once, naming the file and saying to rename it to
  `404.html`; an exact `404.html` gives no warning. `parity_test.clj`
  `the-404-page-under-a-base-a-static-override-and-the-sweep`: a
  `404.HTML` gives one warning and no error, and the static-override case
  now asserts the build's result (`build-or-error`) instead of relying on
  an exception.

- **(E2.2) A long word in a row's title wraps.** At 320 px on `/zh-Hans/`
  the y2025a row (a `titleTag` and a fallback marker) titled
  "Configuration" pushed its date out of the row and the page 7 px wide
  (`scrollWidth` 327). The row's link is now `min-width: 0;
  overflow-wrap: anywhere`; the badges' `flex: none` changed nothing
  beside `white-space: nowrap` and is gone, and the badges are selected
  as `.clogem-row > span`. `parity.test.mjs` item 4 (`item4LongWord`): at
  320 that row titled "Configuration" and a 40-character word does not
  overflow the page, and its date ends at the row's end.

- **(E2.2) A wrapped row's badges sit on the title's first line.** They
  were `align-self: center`, at the row's middle, while the title's first
  line and the date sat at the top. A `.clogem-row` is now `align-items:
  baseline` (a lone `align-self: baseline` on the badges only lines them
  up with each other). `theme.css` is 11,613 B gzipped, 259 B over
  `main`. `parity.test.mjs` item 4 (`item4Baseline`): in every wrapped
  row at 320 and 360, each badge's text baseline is within 2 px of the
  title's first-line baseline.

- **(E2.2) No case warning beside an exact `404.html`.** With both
  `public/404.html` and `public/404.HTML`, the build warned that the site
  had no 404 page (it took the first case-folded match) beside the
  collision error. An exact `404.html` is now taken first, so only the
  collision error is reported. `parity_test.clj`
  `the-404-page-under-a-base-a-static-override-and-the-sweep`: both files
  give the collision error and no warning.

### Needs native review (E2)

- `ms`: `:nav/source` "Kod sumber" (the navbar's repository link, when
  its host is not GitHub, GitLab or Bitbucket).
- `ta`: `:nav/source` "மூலக் குறியீடு".
- `ms`: the 404 page's `:not-found/title` "Halaman tidak ditemui",
  `:not-found/message` "Tiada halaman di alamat ini. Halaman itu mungkin
  telah dipindahkan, atau pautannya salah." and `:not-found/home` "Pergi
  ke laman utama".
- `ta`: `:not-found/title` "பக்கம் கிடைக்கவில்லை", `:not-found/message`
  "இந்த முகவரியில் பக்கம் இல்லை. அது நகர்த்தப்பட்டிருக்கலாம், அல்லது
  இணைப்பு தவறாக இருக்கலாம்." and `:not-found/home` "முகப்புப்
  பக்கத்துக்குச் செல்".

**Task B2b — navbar, mobile drawer and page chrome** (§11.3 item 9, D-P4-9):

### Added (B2b)

- **`--navbarHeight`** (3.6rem, vdoing's value) on `:root`. The navbar is
  exactly that tall, on one row, and a `scroll-margin-top` on every
  element outside the navbar (`--jumpOffset`: the navbar's height +
  0.75rem) puts every jump below it: a link to a
  fragment, a TOC click, a fresh load of `…/#id`, `scrollIntoView()` and a
  focus move by Tab — headings or not (the home's `h1`, any `id` and an
  `<a name>` included). The TOC sticks 1rem below the navbar, and `js/toc.js` takes
  its offset from the navbar's measured bottom instead of a fixed 80 px.
- **Smooth scrolling**, always on, under `prefers-reduced-motion:
  no-preference`; under `reduce` scrolling stays instant. The browser's own
  jump to a fresh load's `#id` is instant (B2b.1).
- **A language dropdown** above 50rem: the switcher's list opens from a
  `<details class="clogem-langs__toggle">` whose summary is the
  `languages` icon, the page language's `:nav/language` and a chevron, as
  vdoing's. The list is the summary's next sibling, not its content, so
  wherever the summary is not rendered the list shows. Without JS the
  summary still opens and closes it.
- **An icon-only search trigger** at 64rem and below (CSS only): the
  trigger keeps its name, the page language's `:nav/search`.
- **The mobile navbar and drawer** (at 50rem and below, with JS): one
  sticky row, exactly `--navbarHeight` tall — a menu toggle, the brand
  (ellipsised), the search icon and the mode button — instead of up to
  four rows (258–285 px at 320 px). The toggle is a `<details
  class="clogem-navbar__toggle">` holding only its summary (`menu`/`x` and
  a visually hidden `:nav/menu`, new in all five languages); while it is
  open, `#clogem-drawer`, its sibling, shows below the navbar with the nav
  (dropdowns collapsed until opened) and every language. A site with no
  `:nav` and one language gets no toggle, except on a page with a
  sidebar, where it is rendered `hidden` and revealed by `js/nav.js`.
- **`js/nav.js`** (same-origin, `defer`, after `mode.js`; 2,095 B
  gzipped with the sidebar collapse, back to top, B2b.1 and B2b.2, budget 2,200 B): while the drawer layout is active — read from
  the CSS, at start-up and on every resize — it moves the page's sidebar
  into the drawer and puts it back on leaving it. On open, focus moves
  into the drawer; Escape closes it and focuses the toggle, as do a click
  outside, following a link in it, and a page restored from the
  back-forward cache. Above 50rem, Escape or a click outside closes an
  open nav or language dropdown. `window.clogem` is extended, never
  replaced; no inline script is added, so the CSP is unchanged.
- **A wider three-column shell** from 80rem: a page with a sidebar and a
  TOC is sidebar | main | TOC with a 1.5rem gap in `--maxWidth` + 18rem,
  so at 1280 px main is 680 px and the article 632 (615 beside a 17 px
  scrollbar); it was 376 and 328. The TOC is at most the viewport less the
  navbar's height (as `nav.js` measures it; `--navbarHeight` without JS) and
  6.2rem tall and scrolls inside itself.
- **One check for the page chrome's `:theme` keys** (`:sidebar-collapsed`,
  `:right-menu-bar`, `:page-button`, `:content-bg-style`, `:back-to-top`):
  `nil` is the default, and a bad value (`"yes"` for a boolean, `7` for
  `:content-bg-style`) is one warning naming the key, the value, what it
  must be and the default it falls back to — never an error, since 0.2.0
  accepted any value for these planned keys.
- **vdoing's `smoothScroll`** is now a known key, in `:theme` or at the top
  level: one warning says clogem-press always scrolls smoothly unless the
  reader asks for reduced motion, and that it is ignored (it used to be
  reported as an unknown option).
- **`:theme :right-menu-bar`** (vdoing's `rightMenuBar`, default `true`):
  `false` drops the right-hand TOC — its aside, `clogem-shell--toc` and
  `js/toc.js` — from every page. Headings keep their ids and anchors, and
  the home's right bar still follows `hideRightBar`. vdoing then nests the
  headings under the sidebar's link; clogem-press does not.
- **Sidebar collapse** (`:theme :sidebar-collapsed`, vdoing's `sidebarOpen`
  inverted, default `false`): above 50rem an article with a sidebar gets a
  chevron button at the top of its main column, named by the page
  language's `:page/sidebar`, with `aria-expanded` and `aria-controls` on
  the aside's new `id="clogem-sidebar"`. It hides the sidebar (none of its
  links can take focus) and gives the article its column: at 1280 px main
  grows from 680 to 992 px. `true` starts every article collapsed. The
  button is rendered `hidden` and revealed by `js/nav.js`, so without JS,
  or with a failed `nav.js`, the sidebar shows and there is no button; at
  50rem and below there is no button and the drawer always shows the
  sidebar. The choice holds across resizes but not across pages. Print
  hides the button.
- **Page buttons** (`:theme :page-button`, vdoing's `pageButton`, default
  `true`): an article or catalogue page repeats its previous and next links
  as two chevrons fixed at the viewport's edges, vertically centred, 70 px
  tall, at opacity 0.55 (1 on hover), as vdoing's. They are `aria-hidden`
  duplicates out of the Tab order, with the target's title as their
  `title` and no `rel`. Unlike vdoing's, which show from 961 px over the
  content, they show only from 84rem (1344 px), 50 px wide and 80 px from
  88rem, where they cover no box. Print hides them; `false` omits them.
- **Background patterns** (`:theme :content-bg-style`, vdoing's
  `contentBgStyle`, default `nil`): `1`–`6` draw vdoing's grid, horizontal,
  vertical, two diagonal and dotted patterns on the main column of articles
  and catalogues (the class `clogem-bg-style-N`), in a new `--linesColor`,
  set in every mode: `rgba(50, 0, 0, 0.05)` in light, `rgba(50, 0, 0,
  0.03)` in read (B2b.1), `rgba(125, 125, 125, 0.05)` in dark. Print draws
  none.
- **Back to top** (`:theme :back-to-top`, default `true`; vdoing always
  shows it): a round `arrow-up` button, named by the page language's new
  `:page/back-to-top` (all five languages), fixed 2rem from the right and
  2.5rem from the bottom (1rem and 1.5rem at 50rem and below), 2.2rem on
  `--blurBg`, below the navbar and its drawer. `js/nav.js` shows it once
  the page is scrolled more than 100 px; activating it scrolls to the top —
  smoothly, or at once under reduced motion — and hides it at once, the
  next Tab starting at the navbar. Without JS there is none; print hides
  it; `false` omits it.

### Changed (B2b)

- **The navbar's order** is now brand, nav, languages, search, mode: the
  languages moved from last place to just after the nav. On the desktop
  the brand sits at the start and everything else at the end; the gap is
  1rem.
- **A nav too long for one row** wraps its own items inside the navbar,
  which grows taller rather than spilling over the page; with JS the jump
  offset follows its height (B2b.1).
- **The navbar without JS at 50rem and below is static**: it still
  wraps, with every top-level link, dropdown summary and language shown,
  but no longer sticks over the page (it was 190–268 px tall at 320 px).
  The same goes for a browser without `@media (scripting)`. A `nav.js`
  that fails to load leaves the one-row navbar, and its toggle opens the
  drawer natively, with the sidebar below the article.
- **Smooth scrolling is always on.** vdoing's `smoothScroll` defaults to
  off; clogem-press has no key for it, and reduced motion turns it off.
- **The TOC breakpoint.** Beside a sidebar the TOC shows from 80rem
  (1280 px), as vdoing's does; at 1025–1279 px, where it used to show, such
  a page is now sidebar | main (main 632 px from 1024), and above 50rem a
  TOC page no longer stacks its sidebar above the article. A page with a
  TOC and no sidebar keeps it above 64rem.

### Fixed (B2b)

- **Anchors on the home's `h1`, and on targets that are not headings**,
  no longer land under the navbar: only `.clogem-content` h2–h6 had a
  `scroll-margin-top` (4rem), now replaced by one on every element outside
  the navbar (B2b.1; B2b had the root's scroll padding). At 1280 px a heading used to land 5–6 px under the
  navbar, and a Shift+Tab stop (a copy button) wholly under it.

- **Navbar dropdowns overflowing a phone**: an open Malay "Semak imbas"
  menu widened the page by 44 px at 320 px (28 at 360) and Tamil's by 18;
  at 50rem and below the dropdowns are in the flow (`position: static`).
- **The home's text card** (`.clogem-main--posts > .clogem-content`) kept
  `20px 24px` of padding up to 800 px; at 50rem and below it now pads 1rem,
  as every other main does.

### Tests (B2b)

- `test/browser/layout.test.mjs` (new): the navbar's height and one row at
  1024–1440 px and at every width from 801 to 1600, anchors after a fresh
  load and a TOC click, a `<p>` target, the TOC's offset under
  `--navbarHeight: 6rem`, 30 Shift+Tab stops at 1280, smooth scrolling and
  reduced motion; the language dropdown by keyboard, pointer and without
  JS; the icon-only trigger at 801–1024; a ten-item nav at 1024; two
  columns at 900 and 1024, three at 1280 and 1440 with the article ≥ 600 px
  (beside a 17 px scrollbar too), the 952 px main of pages without a
  sidebar, a `--single --toc` page and a 60-entry TOC; at 320, 360 and
  768 px the one-row navbar, the closed drawer out of the Tab order, the
  open drawer within the viewport with everything in it open, the mode
  menu at 320, reduced motion and the focus ring in the drawer; without JS,
  without `(scripting: enabled)` and with `nav.js` blocked; the keyboard,
  pointer, link, resize and no-duplicate behaviour of `nav.js`; the home's
  text-card padding.
- `overflow.test.mjs` also opens the drawer, and everything in it, on every
  page where its toggle renders; `modes.test.mjs` checks print at 1280 and
  360 px, each control first asserted drawn on screen, and at 1280 gives
  one code block a long line (with the wider article no demo code block
  scrolled, so none was a Tab stop).
- `bb test`: one toggle per page with a nav or languages, `:nav/menu` in
  the page's language, the hidden toggle of a one-language `:nav []` site
  on sidebar pages only, the drawer's CSS, `nav.js` linked once after
  `mode.js`, and its budget (`nav-js-budget`, 2,200 B since B2b.2).
- `bb test`: `clogem.layout-test` (new) pins the markup and the CSS;
  `theme_modes_test` checks that `smooth` appears only under
  `(prefers-reduced-motion: no-preference)`. The contrast parser
  (`test/clogem/css_palette.clj`) learnt a leading `not` and
  `(scripting: …)`, through `:scripting?` (default true; `initial-only` is
  false, as in Chromium).
- `bb test`: every bad value of the five chrome keys (`"yes"`, `1`, `:on`;
  `0`, `7`, `1.5`, `"1"`, `:grid`, `true`) warns once and is repaired, and
  good values and `nil` are quiet; `smoothScroll` in `:theme` and at the top
  level warns once with its pinned message.
- `bb test`: with `:right-menu-bar false` no page of a temp site has a
  `clogem-toc`, `clogem-shell--toc` or `js/toc.js`, while its h2/h3 keep
  their ids and anchors and the home keeps `clogem-home-right`.
- `bb test`: the collapse button, the aside's id and `aria-controls` on
  every sidebar page of the demo and on no other (the home, a category page,
  the catalogue, a post), `hidden`, outside the article; the shell's marker
  with `true` only, on sidebar pages only, and read by no CSS rule.
  `layout.test.mjs`: the collapse by pointer and keyboard at 1280, Tab
  around the collapsed aside, the marker with and without JS and with
  `nav.js` aborted, 360 px and the drawer, a resize round trip, and reduced
  motion. `modes.test.mjs`' print check covers the button at 1280.
- `bb test`: on every demo page the page buttons repeat `prev-next`'s
  hrefs, `lang` and titles, with `aria-hidden`, `tabindex="-1"`, a chevron
  and no `rel`, after `</article>`; `/pages/c0nta1/` keeps one `rel="prev"`
  and one `rel="next"`; the first leaf has no prev button, a page without
  neighbours has neither, and `false` omits them. `layout.test.mjs`: at
  1344, 1407, 1408 and 1440 both are visible, centred, 50 or 80 px wide at
  the edges, meet no sidebar, main or TOC box and leave every visible link's
  centre clickable; hover, 40 Tab stops, and hidden at 1343, 900 and 360.
- `bb test`: each of `1`–`6` marks the article, post and catalogue mains of a
  temp site and no home, category, tag, archive or `/page/2/` page; the
  default and every bad value mark none. Every mode block declares
  `--linesColor` (`full-set`), the patterns use no `background` shorthand,
  and the contrast check gains the surface "--linesColor over --mainBg": the
  real values pass and `rgba(0,0,0,0.6)` fails. `layout.test.mjs`: on an
  article and a catalogue, each pattern's gradient functions, pattern 1
  centred, the background colour still `--mainBg`, dark lines in dark mode,
  none in print; `modes.test.mjs`' print check draws no pattern.
- `bb test`: back to top is last in `<body>`, `hidden`, on every page with
  the navbar (an article, a catalogue, the home, a category page, the
  archive), named in each language (返回顶部 on zh-Hans), and gone with
  `false`. `layout.test.mjs` at 360 and 1280 on an article and the home:
  hidden at the top, shown within 1 s of a 200 px scroll, ≥ 24×24 and in
  the viewport at its offsets; Enter reaches the top at once under reduced
  motion and smoothly within 3 s otherwise, hidden all the way, and one Tab
  then focuses the brand or the menu toggle; never shown without JS or
  with `nav.js` aborted; it covers no TOC or sidebar link at 1280, neither
  prev/next link at 360, and the open drawer covers it.
  `overflow.test.mjs` checks every page scrolled 200 px too, and
  `modes.test.mjs` its print at 360.

### Fixed (B2b.1)

- **Focusing a navbar control no longer throws the page up.** The root's
  `scroll-padding-top` covered the whole sticky navbar, so Chromium took
  every control in it for obscured and centred it on focus: at 360×740,
  scrolled to 1000 px, the first Tab landed at 624 and Escape on the mode
  menu at 607; scrolled to 1200, Escape on the drawer at 822; a page
  scrolled less than ~376 px went to the top (`main` moved 0 px). The
  offset is now a `scroll-margin-top` on `:where(body *)` — every element,
  so an `<a name>` target and `scrollIntoView()` keep it — and zero inside
  `.clogem-navbar`; the calls that refocus a navbar control (closing the
  drawer, a dropdown or the mode menu, opening the mode menu) pass
  `preventScroll` too.
- **Widening past 50rem while scrolled** no longer moves the reading
  position by ~400 px: the aside, still in the drawer until `nav.js` moves
  it back, was laid out inside the navbar (`display: contents`), 306 px
  tall. Above 50rem the drawer's aside is not displayed and main keeps its
  column, so the move back shifts nothing.
- **A long nav above 50rem**: the language summary no longer overlaps the
  search trigger (by up to 36 px; `.clogem-langs` shrank below it), the
  page no longer overflows without JS (by up to 15 px), and with JS a jump
  lands below a wrapped navbar and the stuck TOC stays below it: `nav.js`
  measures the navbar when it runs, before the load's jump to `#id`, and
  on every resize (a `ResizeObserver`), into an adopted stylesheet's
  `--navbarMeasured`.
- **`:right-menu-bar false`** left the article 584 px wide at 1280, the
  TOC-less 62rem shell: a sidebar page whose TOC the key dropped now gets
  `clogem-shell--wide`, and from 80rem the TOC's room (872 px at 1280).
  Pages without headings, and every page below 80rem, are unchanged.
- **Read mode's `--linesColor`** is `rgba(50, 0, 0, 0.03)`: where pattern
  1's gradients cross, 0.05 twice took a link to 4.22:1 (4.40 measured).
- **A fresh load of `…/#id` jumps at once** instead of animating from the
  top for ~600 ms: `nav.js` puts `clogem-loading` on `<html>` until two
  frames after `load`, or until the first click (a tap and Enter on a link
  are clicks; so is back to top), and smooth scrolling is
  `html:not(.clogem-loading)`. Every jump after that is smooth, and none is
  under reduced motion; only history traversal before `load` (which giscus
  or a slow image can delay) stays instant. Without JS (or with `nav.js`
  failing) the load's jump animates, as before.
- **The TOC at the end of the page** marks the entry just followed: a
  heading that cannot reach the top left an earlier entry active, and
  B2b's wider article made that four entries of `/pages/17c887/` (it was
  the last three). Scrolled to the end of a page that scrolls, `toc.js`
  marks the section holding the followed fragment (its heading, or for an
  h4 or a `<p id>` the TOC heading before it) while the target is still
  where the jump left it (in view, not under the navbar), else the last
  heading whose top is in view; a page that fits the viewport keeps its
  first entry, and a page that grows under the reader (a
  `ResizeObserver`) is looked at again.
- **Desktop dropdowns close when focus leaves them** for another control,
  as vdoing's `DropdownLink` does: Tab past the language list's or a nav
  dropdown's last link used to leave it open over the page. A press on
  something unfocusable inside one (its padding, the current language)
  leaves it open; outside, the click handler closes it, and a click into a
  frame (giscus's iframe), which blurs the window, closes it too.

### Tests (B2b.1)

- `layout.test.mjs`: at 360×740, 768×1024 and 1280×800, under both motion
  settings and scrolled to 1000, the first Tab, the drawer, the mode menu
  and the language dropdown (each opened and closed with Escape) leave
  `scrollY` unchanged; after widening 700 → 900, 768 → 1024 and
  360 → 1280, and narrowing 900 → 700 and 1024 → 768, a heading 200 px
  down lands within 24 px of where it lands with `nav.js` aborted (it was
  ~400 px off; the browser's own scroll anchoring reflows the text above
  it by a font-dependent amount either way: 31 px for 1024 → 768 on CI's
  runners, about 4 px here, ~116 px for 360 → 1280); a 13-item nav scanned at 801–1600
  in 10 px steps on three pages, with JS and without, has no overlapping
  controls, no overflow and no control outside the navbar, and `#nesting`
  lands below it at 801, 850, 1024 and 1280 (and the home's `h1` on `/` and
  `/page/2/`, which load before any `ResizeObserver` callback, at 801 and
  850); with longer labels the navbar wraps at 1280 and the stuck TOC
  stays below it; a `:right-menu-bar false`
  temp build gives the article ≥ 600 px at 1280 and 1440 and changes
  nothing at 900 and 1024. Each item now runs in its own `try`, so a throw
  fails that item and the rest still run (`CLOGEM_LAYOUT_ITEMS` picks
  items, and an unknown name fails). At 1280, Tab through the language dropdown's and Browse's links
  keeps each open and Tab past the last closes it; with the mode menu
  focused and the language dropdown open, one Escape closes only the mode
  menu (`e.defaultPrevented`, which only a text pin covered); a press on
  Browse's padding or the current language leaves the dropdown open and
  the tab alive; back to top
  is hidden at 50 px and shown at 101 (`y <= 100 * 0` survived every test).
  A fresh load of `/pages/c0nta1/#nesting` under no-preference has no
  frame between 0 and its final `scrollY` (31 before), while a TOC click,
  a heading anchor's click, Enter on a focused TOC link and history back
  each have one; `theme_modes_test` pins the selector. An `<a name>` target
  and `scrollIntoView()` on a `<p>` without an id land below the navbar.
  A TOC click before `load` (an image left pending) is smooth; a click into
  an iframe closes the language dropdown. Item 1's TOC clicks cover every
  entry of `/pages/17c887/`, at 1280×740,
  1440×740 and 1280×1024, each active once scrolling settles (13 failed
  before); the exclusion of the last entries is gone. `#nesting` followed,
  then the end of the page, marks the last heading in view; a page that
  grows 150 px after load (as giscus's iframe does) keeps its first entry
  at the top and still marks the entry clicked; at the end of
  `/pages/17c887/`, 600 px added below give the line's entry back with no
  scroll; an h4 followed to the end marks its section; a page that fits
  the viewport marks its first entry.
  The comment saying that opening the drawer scrolls the page up is
  corrected: since item 1 it keeps the page where it is.
- `bb test`: the `"y <= 100"` text pin is gone, replaced by the browser
  check; no `scroll-padding` declaration, the margin on `:where(body *)`,
  the `--jumpOffset` per media query, the TOC's measured offset, the wide shell on the `:right-menu-bar false` tree article and on
  no default page; the contrast check gains the surface "--linesColor twice
  over --mainBg", where read's old 0.05 fails at 4.22:1.

### Fixed (B2b.2)

- **Shift+Tab no longer leaves a focused element under the sticky
  navbar** (WCAG 2.2 SC 2.4.11), a regression from B2b.1: the
  `scroll-margin-top` that replaced the root's `scroll-padding-top` places
  an element only once a scroll happens, and Chromium 141 does not scroll
  a focus already in the viewport. At 1280×800 on `/pages/c0nta1/`,
  scrolled to the end, a heading anchor took focus at y 28–57 under the
  57.6 px navbar; Shift+Tab from the end of `/pages/c0nta1/`, `/` and
  `/pages/17c887/` at 360, 1024 and 1280 by 600, 740, 800 and 900 left one
  under it in 24 of the 36 (in Chromium 153, which counts the margin, in
  none). `nav.js` now scrolls a keyboard-focused element outside the
  navbar whose top is above the navbar's bottom down to its
  `scroll-margin-top`, in the root's scroll-behavior; the navbar's own
  controls are left alone, so focusing one still moves nothing.
  **`nav-js-budget` is 2,200 B** (was 2,000): the handler is ~190 B
  gzipped, and `nav.js` was at 1,979; the alternatives, CSS alone, did not
  hold in Chromium 141 (DESIGN.md §11.3 item 9).

- **Unobservable code dropped**: the `preventScroll` that B2b.1 added
  where focus returns to a navbar control (closing the drawer, Escape on a
  dropdown, opening and closing the mode menu in `mode.js`) changed
  nothing, since a control in the sticky navbar is never scrolled to (no
  test failed without it, in Chromium 141 or 153); nor did `nav.js`'s
  `matchMedia` change listener, which only repeated `resize`. Back to
  top's focus on `<body>` keeps its `preventScroll`.

### Tests (B2b.2)

- `layout.test.mjs`: the Shift+Tab check now starts at each page's last
  stop and walks back through every stop to the navbar, on
  `/pages/c0nta1/`, `/` and `/pages/17c887/`, at 360, 1024 and 1280 by
  600, 740, 800 and 900 under reduced motion and at 1280×800 under
  no-preference, and fails on a focused element whose top is above the
  navbar's bottom (1 px allowed). Before the fix, Chromium 141 hid a
  stop in 25 of the 39 page-size runs (40 stops); CI's Chromium 153
  counts the margin itself and hid none. The
  no-jump checks gain Tab into the open language list, then Escape: the
  summary takes focus and `scrollY` stays.
- `layout.test.mjs`: the 13-item nav loaded at 1280 (one row), narrowed
  to 801 and to 850 (82.6 px, wrapped), then `#nesting` followed: the
  heading lands below the navbar. Without `nav.js`'s `ResizeObserver`
  `--navbarMeasured` stayed 57.6 px and it landed 12.6 px under; the old
  check loaded `#nesting` fresh at each width, so it never saw this.
- `layout.test.mjs`'s `settle()` caps its wait for `document.fonts.ready`
  at 1 s: Chromium 141 resolves it only once the document has loaded, so
  B2b.1's TOC click before `load` (an image never answered) hung the file
  there; Chromium 153 resolves it earlier.
- The resize test's comment, and B2b.1's entry above, gave the article's
  own reflow as "31 px on CI, about 4 px here": the bound is relative to
  the same page with `nav.js` aborted, where the heading moves 31.2 px at
  768 → 1024 on CI (4.0 px in this sandbox, whose fonts differ) and
  116.4–116.6 px at 360 → 1280, with or without `nav.js`.

### Needs native review (B2b)

- `:nav/menu`: ms "Menu", ta "மெனு".
- `:page/back-to-top`: ms "Kembali ke atas", ta "மேலே செல்".

**Task F — analytics, verification and static root** (§11.3 items 14, 15):

### Added (F)

- **Analytics**: `:analytics {:provider :ga4 | :plausible | :umami}`
  (default `:none`). Every page loads `dist/clogem/js/analytics.js`, our
  own file and the last child of `<head>`, which loads the chosen
  provider's script — only on the `:site :url` host (never under `bb
  serve`, on localhost, a fork or a preview), never in `bb dev` (which
  does not emit the tag), and, unless `:respect-dnt false`, not for a
  browser sending Do Not Track or Global Privacy Control. No inline
  script is added. GA4 takes `:id "G-…"` and an opt-in `:consent-default
  :denied`, sent before `config` as Google's consent mode asks; Plausible
  takes the site's own `:src` (`…/js/pa-XXXX.js`) and `plausible.init`
  options as `:init`, or `:domain` for the legacy script; Umami takes
  `:website-id`. `:src` overrides a provider's script URL (`https://`
  only). Under `:none` no file ships and no page changes. Every bad value
  is a warning that switches analytics off, never an error. An `:init`
  option it does not know warns and is not passed to `plausible.init`;
  `:src` and `:domain` are trimmed. README
  "Analytics" lists the CSP each provider needs and notes the site
  owner's duty under the PDPA.
- **Search-console verification**: `:seo {:verification {:google … :bing
  …}}`, each a string or a vector of them, emits one
  `google-site-verification` or `msvalidate.01` meta tag per value on
  every page (redirect stubs excepted). Any other value warns and is
  ignored.
- **A static directory**: `:content {:static-dir "public"}` (off unless
  set) copies every file under it, dotfiles included, byte for byte to
  the root of the output directory — `CNAME`, `favicon.ico`, a
  verification file, an IndexNow `<key>.txt`, `.nojekyll`,
  `.well-known/`. It never copies a `.git`, never follows a linked
  directory, and silently skips editor and OS leftovers — the names `bb
  dev` ignores (`.CNAME.swp`, `CNAME~`, `.#CNAME`, Emacs' auto-save
  `#CNAME#`, `*.tmp`, `4913`) and
  `.DS_Store`, `Thumbs.db`, `desktop.ini`. A linked file is copied only
  when its target is inside the site and not inside a `.git` (a link to
  `.git/config`, which can hold a remote's credentials, is not
  published); any other link is skipped with a
  warning naming it. A static directory that is itself a link inside the
  site (`public -> web/static`) is copied from its target. Unchanged files are not rewritten, and `bb dev` rebuilds
  when one changes — a dotfile too, when it polls (`--poll`, or its
  fallback), which never saw one before P4-F.1, and a file under a linked
  static directory or a linked `assets/` (P4-F.2). Polling skips a `.git`
  without listing it, and a `.DS_Store` write no longer makes it rebuild. A static file where the build writes — a page,
  `feed.xml`, `sitemap.xml`, `robots.txt`, a site asset, anything under
  `clogem/`, `assets/` or `pagefind/`, the `.clogem-output` marker, a
  temp-file or `.pagefind-…` name, or a page's directory — is an error
  before anything is written, and in `bb doctor`, once per static file;
  each error says how to fix it. A static directory
  that is not a relative path inside the site, or overlaps a source
  directory or the output directory, warns and is not copied.

### Changed (F)

- **A site that set `:analytics` on 0.2.0 now loads that provider.**
  0.2.0 accepted the key and warned that it had no effect; after this
  change a valid `:provider`/`:id` (with a `:site :url`) loads the
  provider's script on the deployed site. Remove `:analytics`, or set
  `:provider :none`, to keep it off.
- **`:analytics :provider` no longer warns "has no effect in 0.2.0"**,
  and `:content :static-dir` and `:seo :verification` no longer warn
  "planned, not implemented in 0.2.0": all three are read. A `:provider`
  set on a site with no `:site :url` warns that analytics needs it.
  `:analytics :init`, `:consent-default` and `:respect-dnt` are known keys
  (they used to warn "is not an analytics option").
- **A site that set `:content :static-dir` on 0.2.0 can stop building.**
  0.2.0 warned that the key was planned and copied nothing; now the
  directory is copied, and a file in it that collides with the build's
  own output — `public/robots.txt`, `public/assets/logo.png` — is an
  error where 0.2.0 built. Setting the key is the opt-in, so a collision
  stays an error; the message says what to do: move the file into
  `assets/`, or ship `robots.txt` as `assets/robots.txt` (README "Static
  files", "Upgrading").
- **The site's `assets/` no longer publishes a link's target from outside
  the site** (hardening behaviour 0.2.0 already had): a linked file is
  copied only when its real path is inside the site, and any other link
  is skipped with a warning naming it — the build does not fail. A linked
  `assets/` directory inside the site is now copied from its target
  (0.2.0 copied nothing from it, silently); one whose target is outside
  the site warns and copies nothing. A link into a `.git` is skipped
  the same way.
- **The site's `assets/` no longer publishes editor and OS leftovers**
  (hardening behaviour 0.2.0 already had): `CNAME~`, `#logo#`, `4913`,
  `*.tmp`, `Thumbs.db`, `desktop.ini` and the other names the static
  directory skips are not copied, silently. Dotfiles in `assets/` were
  never copied.
- **`bb dev` ignores Emacs' auto-save files** (`#x#`), as it already did
  its lock and backup files.

### Documentation (F)

- README: "Static files" (what the static directory is for; why `CNAME`
  matters to branch deploys and other hosts but not to the Actions
  artifact flow), and in "Theme", search-console verification, analytics
  (providers, gates, cookies, consent, CSP additions, a PDPA note citing
  the PDPC's Advisory Guidelines on the PDPA for Selected Topics, revised
  May 2024, ¶¶7.7, 7.12 and 7.13 — not legal advice). The static
  directory is in the `bb build` row and in `bb dev`'s watched list.
- `config.example.edn` shows every `:analytics`, `:seo :verification` and
  `:content :static-dir` key. DESIGN.md §5.2, §5.3, §5.4, §5.6, §6.10,
  D-13, §8 and §11.3 items 14–16 record what was built.
- `test/browser/analytics.test.mjs`, listed in `test/browser/README.md`.
  It covers all four Do Not Track signals the loader reads —
  `navigator.doNotTrack`, `window.doNotTrack`, `navigator.msDoNotTrack`
  and Global Privacy Control — and says what its "no request leaves the
  test" check can and cannot see.

**Task B2a — page styles** (§11.3 item 1, first half; B1's follow-ups):

### Added (B2a)

- **The card and line page styles** (vdoing's `pageStyle`). `:theme
  :page-style :card`, the default, draws the main column of every page
  kind, the sidebar, and the home page's right-bar sections and post cards
  as `--mainBg` cards with a 5px radius and vdoing's shadows, on a page of
  a contrasting colour. A home's post cards sit on the page, as vdoing's
  do: their column is no box, and the home's own text above them is the
  card. `:line` draws the same boxes flat — a 1px border, no shadow — on a
  page of the card's colour, in every mode. Print draws no box in either
  style: no shadow, border, radius, padding or transition on the boxes. A
  highlighted code line keeps its accent bar in print. Both values were
  accepted since 0.2.0 and drew the same thing.
- **The mode button is named for the current mode** — "Colour mode: Dark",
  "主题模式：深色模式" — in the page's language (the new `:mode/current`
  string), and renamed on every change, another tab's included. It was
  always "Colour mode".
- **`js/mode.js` applies the reader's mode when the head script cannot
  run** — a `Content-Security-Policy` without its `sha256` — as the head
  script would: the stored mode, else `:default-mode`, else auto, and auto
  follows the OS. Such a page used to stay in `:default-mode`, with
  `clogem.getMode()` saying so. It is one frame late; the hash is still
  the way to avoid that.

### Changed (B2a)

- **Dark mode's page is lighter than its card**, as vdoing's is: dark's
  `--bodyBg` (and the auto media block's) is `#22252b`; the card,
  `--mainBg`, stays `#16181d`. They were equal, so a card could not show.
  Text, link-hover, muted and accent are 10.74, 7.66, 6.01 and 5.58:1 on
  it. The rest of the palette is unchanged. Under `:line` the page is
  `#16181d` again.
- **One keyboard focus ring.** Everything focusable — links, `<summary>`,
  buttons, heading anchors, Pagefind's trigger — draws `2px solid` in the
  accent at a 2px offset (`--focusRing`); links, summaries and heading
  anchors used to get the browser's thin default. Inside a code block the
  ring is the code colour (`--codeFocusRing`): the accent on read mode's
  dark code block was 2.41:1, under WCAG 1.4.11's 3:1. A focused heading
  anchor is shown; it stayed invisible.
- **The main column has a background and padding in both styles**, and the
  sidebar's radius is 5px (was 8px). Site CSS that styled `.clogem-main`
  may need adjusting.
- **`:mode/label` is no longer rendered**; the mode button's name is
  `:mode/current` (Added, above). A site that overrode `:mode/label` should
  override `:mode/current` instead.
- **Pagefind follows the mode in full.** The error box, its text, the
  shadows, the scroll shadow and the modal backdrop were Pagefind's light
  values in every mode (its dark ones sit under `[data-pf-theme="dark"]`,
  which nothing sets). Dark now gets Pagefind's dark shadows and 0.7
  backdrop, and the error colours come from the palette (`--errorBg`,
  `--errorBorder`, `--errorText`, new in every mode block): Pagefind's own
  error text was 4.41:1 in light and 4.42:1 in dark; every mode's now
  reaches 5.9:1 or better.

### Documentation (B2a)

- README: page styles and focus rings; a CSP without the head script's hash
  still gets the reader's mode. config.example.edn describes `:card` and
  `:line`. DESIGN.md §8 records B2's split and marks B2a done; §11.3 items
  2–5 and 9 carry B2a notes. test/browser/README.md's `modes` row.
- **`[data-mode]` versus the mode class.** With JavaScript,
  `theme-mode-auto` is replaced by the resolved `theme-mode-light` or
  `theme-mode-dark`, so `.theme-mode-dark` covers both *Dark* and *Follow
  system* on a dark OS. To style the reader's *choice*, use
  `[data-mode="auto"|"light"|"dark"|"read"]`; `.theme-mode-auto` exists
  only without JavaScript (README, Theme).

### Tests (B2a)

- **The contrast tests read every rule.** They read the first rule whose
  selector was exactly `.theme-mode-dark` (and so on), so a later or
  compound rule was never checked, and took `#ffffffe6` as opaque.
  `test/clogem/css_palette.clj` merges every rule that applies to `<html>`
  in cascade order, for every mode (auto in its dark media block, and
  print) and both page styles, resolves `var()`, and composites a
  translucent colour over what lies under it; `--blurBg`, the navbar, is a
  surface now. `highlight_test` shares it, and `modes.test.mjs` asks the
  browser for the merged values.
- **`theme.css` has a budget**: at most 12,288 B gzipped, checked by `bb
  test`. It is 30,785 B, 8,455 B gzipped (25,351 B and 6,980 B before
  B2a). The print tests now cover everything the print rule hides.
- `page_style_test` (new) and `modes.test.mjs` cover the page styles, focus
  rings, Pagefind's variables and the toggle's name. The checks of what B2a
  changes — the two styles, the dark page, the toggle's name, Pagefind's
  added variables, the one focus ring — fail on the code before it
  (`d037142`). The others pass there too and guard against regressions:
  the palette reader's own tests, the real palette in every mode, B1's
  toggle markup, the style class on every page, the banner column's
  missing ring, the gzip budget, and in the browser B1's first frame,
  toggle and print chrome.
- **Fix round P4-B2a.1.** The print rule dropped every shadow, a
  highlighted code line's accent bar too (it printed on 0.2.0), and a post
  card's shadow faded out after the switch to print; it now names the
  boxes, with `transition: none`. The mode button's name no longer expands
  a `$&` in a mode label. Tests: every box's own background (the sidebar's
  `--sidebarBg`, the rest `--mainBg`) in every mode and style, the post
  cards' column being no box, print on four pages in both styles (no box,
  the highlighted line's bar), a focused heading anchor shown and the
  scrollable `<code>`'s inset ring, one gzip budget constant, read mode's
  code ring at 4.5:1 or more (it pinned the accent's 2.41:1), and every
  `--pf-*` the theme maps beating Pagefind's `:root` in light, dark and
  read (set to a sentinel there, none shows through).

### Needs native review (B2a)

- `:mode/current` in **Malay** (`Mod warna: {{mode}}`) and **Tamil**
  (`வண்ணப் பயன்முறை: {{mode}}`).

**Task D — the dev loop** (§11.3 item 12):

### Changed (D)

- **`bb dev` and `bb serve` listen on 127.0.0.1** instead of every
  interface. `--host 0.0.0.0` restores the old behaviour. The URL they
  print is still `http://localhost:PORT/` — giscus's `originsRegex`
  (`http://localhost:[0-9]+`) does not accept `127.0.0.1` — with
  `--host 0.0.0.0` too, plus a line saying it listens on every interface.
- **On loopback, both servers answer only for `localhost`, `127.0.0.1`
  and `[::1]`** (any port), and for the address `--host` gave: a request
  with another `Host` is a 403, so a DNS-rebinding page cannot read the
  site or a build error through a name of its own. Loopback is decided on
  the address `--host` resolves to, however it is spelled (`127.1`,
  `127.0.0.2`, `::1`, `localhost`), and the URL printed is always
  answered. With any other `--host` there is no check.
- **`bb.edn` asks for babashka 1.13.219 or later** (`:min-bb-version`; it
  said 1.13.0, which no one tested). An older babashka prints a warning
  and runs the task anyway: babashka does not refuse it. CI runs the tests
  and the browser tests under 1.13.219 and the latest release (P4-D.4).
- **`permalinks.edn` is written only when it changes.** It was rewritten,
  byte for byte the same, on every build.
- **A missing UI string key warns once per key and language, in `build`
  too**, naming the string file to add it to (`i18n/ta.edn`). It used to
  warn only in dev — where it never fired, because the rebuild dropped the
  dev flag — and once per call. `build` still renders the key's name;
  `bb dev` renders `⟦key⟧`, and warns once per `bb dev` session (again
  only if the key is defined and then goes missing once more).
  `:i18n {:missing-key :silent}` turns it off.
- **`dist/clogem/` is the generator's own directory**: a build deletes any
  file there it did not write, so a deleted `overrides/custom.css` no
  longer lingers as `dist/clogem/overrides/custom.css`. Everything else a
  build did not write outside `dist/clogem/` and `.html` stays (`CNAME`,
  `.nojekyll`).

### Added (D)

- **One rebuild per burst of saves.** `bb dev` collects changes until
  100 ms pass quietly and ignores editor temp files; 20 quick writes were 20
  full rebuilds.
- **A build-error overlay.** A failed rebuild shows its error over the page
  (self-contained, no theme CSS), and the next good build clears it. While
  no build has succeeded, pages show a plain error page.
- **`:base` in `dev` and `serve`.** With `:base "/project/"`, `dist/` is
  served at `/project/`, `/` redirects there, and links work. `bb serve
  --base`, defaulting to the site's `:base`.
- **`--host`** on `dev` and `serve`; **`--base`**, **`--no-write`** and
  **`--reload-code`** (reload the theme's `.clj` code) on `dev`.
- **Theme hot reload.** `bb dev` watches the generator's theme resources;
  a theme CSS change swaps in place.
- **`bb dev` follows `site.edn`.** A changed `:site :base` or `:build :out`
  is served at once (one line says where), and a watched directory that
  appears later — `overrides/`, `assets/`, `i18n/`, or a `:content :dir`
  moved in `site.edn` — is watched and rebuilt for, with the pod as with
  `--poll`.

### Fixed (D)

- **The fswatcher pod is fetched, sha256-verified and cached** through
  `clogem.tools` and loaded from that path, not from the pod registry.
  Offline on the first run, or with no build for the platform, dev polls
  with one message. Its registrations are spaced 20 ms apart: back-to-back
  ones hung the pod more often than not, and dev then fell back to
  polling after its 3 s probe.
- **Search no longer 404s during a dev rebuild.** The index is rebuilt in
  the background after the reload, into a staging directory swapped in
  whole; it was deleted first and rebuilt before the reload was sent.
  Every bundle it replaces that a page could have loaded since the last
  reload is kept (at most 8) as `dist/.pagefind-prev-<pid>-<start>-<n>/`,
  and dev serves a `pagefind/` file the live bundle lacks from them,
  newest first — so a page that loaded an older index keeps searching it
  until its next reload, two saves within one index run included. `bb dev`
  removes them when it stops; any `bb build`, `--no-search` too, removes
  those and the staging directories of a `bb dev` no longer running —
  by process id and start time, so a reused pid (dev is often pid 1 in a
  container) is not mistaken for it.
- **A file renamed while dev served it** is a 404, never a 500 whose body
  named its absolute path (reachable with `--host 0.0.0.0`) — except, until
  the Phase 4 follow-ups (item 1), a file of 1 MB or more deleted after the
  handler answered and before http-kit opened it, which was still that
  500. A file under
  1 MB — every Pagefind file — is read whole from one open, so a rebuild
  swapping it mid-response cannot mix two versions; a larger one is sent
  as 0.2.0 sent every file, with its `Content-Length`, to HTTP/1.0 and
  HTTP/1.1 alike (P4-D.4). A HEAD request gets no body; 0.2.0 sent the
  whole file, which corrupts a keep-alive connection. A file of 2 GiB or
  more, which http-kit cannot send (0.2.0: a 500 and a stack trace), is
  a 500 saying so.
- **Neither server serves outside its directory through a link**: a
  directory's `index.html` linked outside it was served (`GET /dir/`). A
  request path with a backslash, a `:`, a NUL or a `..` past the root is
  refused before the filesystem is touched (on Windows, `\\host\share`
  would have been a network stat).
- **With the pod, `site.edn` saved by rename** (`sed -i`, vim, emacs,
  atomic-save editors) is still watched afterwards, and a `site.edn` that
  is a symbolic link is watched at its target too. Pointing the link
  elsewhere is one rebuild; it was two, the second a rescan of every file
  in the new target's directory (P4-D.3). The previous target's directory
  is no longer watched afterwards (P4-D.4).
- **With the pod, a directory created while `bb dev` runs is watched**:
  `mkdir content/07.Topic` and a page in it, `mkdir -p a/b/c`, `cp -r` of
  a tree. The pod watches only the subdirectories that exist when it is
  told about a directory, so the page 404'd until something else changed.
  Files made before the new directory is registered are rescanned. A
  watched directory replaced (`rm -rf overrides && mkdir overrides`, an
  `mv` swap) is watched again — its old watch died with it, silently —
  and the old registration is released. Creating `overrides/` is one
  rebuild, not two.
- **`bb dev` keeps its inotify use bounded** (P4-D.3). Each pod
  registration is an inotify instance, and Linux allows 128 per user. A
  directory an existing registration already covers is no longer
  registered again (`chmod -R` over 14 subdirectories took 14
  instances); a deleted directory's watch is released when its delete
  arrives, however busy the tree (a mkdir/rm loop leaked one per cycle); a
  re-registration that fails keeps the working watch and is retried. At
  the system's limit (too many open files, or no space left on device)
  dev prints one line, releases the pod and polls for the rest of the
  session, rebuilding once as polling starts: the change that hit the
  limit was otherwise never built (P4-D.4).
- **A `:content :dir` that does not exist yet** is followed once it is
  created; the failed build in between left dev watching the old one.
  With `--poll`, the save that moves it is one failed build, not two: the
  old directory's files no longer being watched read as deleted
  (P4-D.3).
- **The build's own writes never trigger a rebuild**: front matter it
  fills in, `permalinks.edn` and `@pages/` files each caused a second
  build, and with the pod's filter gone an endless loop. Each write is
  filtered once (P4-D.3): the first event for the file uses it up, and any
  later one rebuilds whatever it holds — an undo back to the bytes the
  build wrote, within 3 s, was ignored and `dist/` kept the edit. A
  directory the build creates (`content/@pages/`) is the build's own too;
  it was one extra rebuild.
- **Ctrl-C waits for the build running** (up to 10 s), stops Pagefind and
  any index run queued behind it, and removes the staging directories, so
  nothing is written after `stopped` and no Pagefind outlives `bb dev`.
  Ctrl-C during the first build or the pod's probe now does the same; it
  printed nothing and skipped it all. Since P4-D.3: a build Ctrl-C
  interrupts writes nothing — Chroma killed by the same signal is not
  reported as a failure, and the build no longer goes on to write `dist/`
  with plain code; a Pagefind the process group's SIGTERM stopped is not
  reported either; the probe's temp file is removed; and `stopped` is
  printed only once dev has started, not under a listen or config error.
- **A busy port, a port out of range, or a `--host` that does not
  resolve, is not this machine's or has no address family here (`::1` on
  an IPv4-only host)** is one error naming the host and port (exit 1), not
  a stack trace; `bb dev` binds before its first build, so it writes
  nothing then. `--port 0` prints the port it got. An option value that
  does not parse (`--port abc`) is one line too, not babashka's dump of
  the whole option spec, and `--host [::1]` is printed `[::1]`, not
  `[[::1]]` (P4-D.3).
- **A `:base` with non-ASCII characters or a space** (`/文档/`, `/my docs/`)
  is mounted by `dev` and `serve`; it 404'd. A base written
  percent-encoded (`"/%E6%96%87/"`) is the same base as `"/文/"`; it was
  encoded again in every link, by `build` too. Every URL `build` emits now
  carries the base percent-encoded (P4-D.3): theme assets, the icon
  sprite, the search bundle, the home and language-home links, nav links
  and markdown's `/assets/…` links had `href="/my docs/…"`. Since P4-D.4
  a `:nav` link that gets only the base has the base encoded and is
  appended as written, so its `#fragment`, `?query` or `%20` is kept (D.3
  encoded them into the path), and the redirect stubs of
  `:prefix-default? true` and dev's 404 page encode the base too.
- **`:build :out` in `site.edn` is honoured** by `build`, `dev` and
  `clean`: the CLI's `--out` defaulted to `dist` and overrode it every
  time. `--out` still wins. A site whose CI passes `--out` (the live
  site's passes `--out ../dist`) is unaffected.
- **An output directory can no longer be a source directory** (P4-D.3).
  `:build :out "assets"` passed `doctor`, then the build swept
  `assets/mine.html` as a stale page and `bb clean` deleted `assets/`.
  `build`, `clean` and `doctor` now refuse — as a config error — an
  output directory, from `site.edn` or `--out`, that is, holds or is
  inside the content, assets, i18n or overrides directory or the site's
  `.git`, sits inside any `.git`, or is the site directory or an ancestor
  of it. A `:build :out` written in `site.edn` must also stay inside the
  site directory (absolute, `..` and links included), while the default
  `dist` and `--out` may point elsewhere — `--out ../dist`, or a `dist`
  that is a symbolic link to `/var/www/site` (P4-D.4). A `dist` link to
  nothing is a clear config error, not a `FileAlreadyExistsException`.
- **Builds mark their output directory** with a `.clogem-output` file.
  A build still writes into any existing directory the rules above
  accept, as 0.2.0 did — one holding only `CNAME`, `.nojekyll` or a deploy
  worktree's `.git` included — but removes stale `.html` (and stale files
  under `dist/clogem/`) only where a previous build left the marker: the
  first build into a directory deletes nothing, so a `dist/` 0.2.0 built
  is marked by its first build and swept from its second (P4-D.4).
  `bb clean` deletes only a marked directory, and never one holding a
  `.git`; it refuses both, saying how to clean them.
- **`bb serve` with an unreadable `site.edn`** warns and serves at `/`, as
  0.2.0 did (`--base` needs no config); it exited 1.
- **`bb serve` serves the directory `bb build` wrote** (P4-D.3): `--dir`,
  else `--out` (new), else `site.edn`'s `:build :out`, else `dist`. With
  `:build :out "public"` it looked for `dist/`. `bb dev` sweeps its
  previous search bundles from every output directory it built into in
  the session, not only the last.
- **Messages:** the pod fallback line has one `clogem-press:` prefix and
  keeps its hint, which names `bb dev --poll` for a `CLOGEM_FSWATCHER`
  that names nothing; a failed download leaves only an empty
  `<platform>.lock` in the cache, which is harmless (P4-D.3: D.2 deleted
  it, and with it the version directory, under a second fetch waiting on
  the same lock, which then failed too); Ctrl-C while the index runs prints `stopped`, not "Pagefind
  exited 130", while a Pagefind killed from outside (the OOM killer) is
  reported as an index failure; a Pagefind failure shows its output on
  indented lines, not run together with `;`. Its hint says what is true
  (P4-D.3): "the previous search index is still in place" only when there
  is one — the first index of a session has none — and `build`'s names
  the output directory written, not always `dist/`. Since P4-D.4: a
  failed fswatcher fetch says `fswatcher:`, not `dev:` (`bb fetch-tool
  --tool fswatcher` raises it too); `bb serve`'s unreadable-`site.edn`
  warning names only what falls back (not "at / from dist/" whatever
  `--base`, `--dir` or `--out` said), and the directory it prints is
  normalized; Ctrl-C during the first build no longer prints `dev server
  at …` after, or on the same line as, `stopped`.
- **`bb <task> --help` (or `-h`) prints the task's usage and options and
  does nothing else**, for every task (pre-existing, P4-D.4): no task read
  the flag, so `bb clean --help` deleted the output directory, and the
  one-line option error's "`--help` lists the options" was not true.
- **`doctor`'s code-language check** reports an unreadable Chroma — cached,
  `CLOGEM_CHROMA` or `:tools :chroma :path` — with `build`'s own message
  and hint, not as a stack trace (follow-up to Task C).
- **A stylesheet saved by rename** (`sed -i`, JetBrains, GNOME editors,
  and since P4-D.3 rsync's `.custom.css.Xy12Zq` and npm write-file-atomic's
  `custom.css.<hash>`) is swapped in place; the temp file's name made it a
  full reload.
- **The 404 page escapes the base it links to** as an attribute.
- **Feed summaries are well-formed XML.** A character reference to a
  control character, U+FFFE or U+FFFF stays as written, references are
  decoded once (`&#38;amp;` is `&amp;`), and a raw control character —
  the markdown parser turns `&#27;` in prose into one — is dropped; it
  failed the build, in 0.2.0 too.
- **A new or deleted `overrides/custom.css` reloads the page** instead of
  waiting for a manual reload (the CSS swap only re-fetches stylesheets the
  page already links).
- **The poll loop survives an `Error`**, and so do pod callbacks.
- **A `site.edn` change that moves `:content :dir`** is followed, polling
  or with the pod.

**Task C — code highlighting** (§11.3 item 10):

### Breaking (C)

- **A bad `:tools :chroma` pin is now an error**, like Pagefind's: the
  build runs Chroma, so `:chroma` joined `config/fatal-tool-ids`. Task A
  made it a warning that kept the built-in pin; a `:version` that is not a
  version, a `:sha256` that is not a per-platform map (0.2.0's own DESIGN
  §5.6 sketch had one string), or a `:tools :chroma` that is not a map now
  stops `build` and `doctor` with a config error. Delete the key to use the
  built-in pin.
- **The first build of a site with code fetches Chroma** (a 3 MB download,
  an 8.4 MB binary unpacked; once, sha256-verified, cached outside the site
  like Pagefind). A failed
  download, a hash mismatch, or a Chroma run that fails is a build error
  (exit 1) whose hint names `--no-highlight` and
  `:highlight {:provider :none}`; so is a `CLOGEM_CHROMA` that cannot be
  run (no exec bit, a `noexec` mount). This is the one new way a build that
  passed in 0.2.0 can fail. A site with no code block never fetches it:
  "has code" is read from the parsed pages, so a fence inside a blockquote
  counts and a nested list indented four spaces does not.

### Added (C)

- **Syntax highlighting, on by default**:
  `:highlight {:provider :chroma :line-numbers true :copy-button true
  :style "github" :dark-style "github-dark"}`. `:provider :none`, or
  `--no-highlight` on `build` and `dev`, renders code as 0.2.0 did. Chroma
  2.27.0 knows 297 languages, by name, alias or file extension (`clojure`,
  `clj`, `edn`); an unknown one is shown as plain text with one warning per
  language per build, and a block with no language is plain text with no
  warning, naming the first file (in path order) that uses it. Every code
  block of every article, `index*.md` and `@pages/*` page is highlighted in
  one Chroma process per language (at most 150 blocks each) and cached in
  memory, so a `bb dev` rebuild runs none for unchanged code. In a `dev`
  rebuild a Chroma failure is a warning, given once, after which code
  renders plain and Chroma is not run again until `bb dev` restarts.
- **Fence options**: ` ```js{1,3-5} ` and ` ```js {2} ` highlight lines;
  ` ```js:no-line-numbers ` and ` ```js:line-numbers ` override
  `:highlight :line-numbers` for one block; extra attributes such as
  `title="x"` are ignored.
- **Line numbers are drawn by CSS**, never written into the page: copying
  code never copies them, and Pagefind indexes `import`, not `1import`.
- **Colours for every mode**: `dist/clogem/css/highlight.css` (12 KB),
  generated at build time from the two Chroma styles as `--hl-*` variables,
  linked right after `theme.css` and only when the build highlights. Light
  mode uses `:style`; dark and reading mode (whose code block is dark) use
  `:dark-style`; printing uses the light colours. Every token colour, the
  line numbers and the language label reach 4.5:1 on the code background
  and on a highlighted line in every mode, checked by `bb test` and in a
  browser. An unknown style is a warning and the default is used.
- **A copy button and a language label** on each code block. The button
  (`js/code.js`, only when `:copy-button` is on, only on pages with code) is
  added by JavaScript and only where the browser has the clipboard API, so
  there is never a dead button, and nothing for search or the feeds to
  index; it copies exactly the code, shows a check mark and announces
  "Copied" for two seconds. The label is the fence's language, from CSS.
  Its strings are in all five languages.
- Wide code scrolls inside its own box; the label and the button stay put.
- `CLOGEM_CHROMA` (or `:tools :chroma :path`) names an installed Chroma,
  as `CLOGEM_PAGEFIND` does for Pagefind.
- **`doctor` checks code languages** against Chroma's list, with the same
  warning `build` gives, when a Chroma binary is already here
  (`CLOGEM_CHROMA`, `:tools :chroma :path` or the tools cache). It never
  downloads one: without one it says once, as info, that it skipped the
  check. A `CLOGEM_CHROMA` that `build` could not run is a `doctor` error.

### Changed (C)

- The light-mode code background is `#f6f8fa` (was `#f1f3f5`), so that
  every `github` token colour reaches 4.5:1; reading mode keeps `#282c34`,
  on which every `github-dark` token that ships does too (the lowest is
  `.c`, 4.55:1; `.err` is not shipped). New palette variables per mode:
  `--codeLineNumber` and `--codeHlBg` (a highlighted line).
- `pre.clogem-code` no longer scrolls itself: its `<code>` does, and the
  `<pre>` carries `data-lang`. CSS that styled the `<pre>`'s padding or
  scrolling may need to move to `pre.clogem-code > code`.
- Chroma's lexers mark text they cannot tokenize as an error token — a
  Tamil symbol in Clojure code is split at every vowel sign — and `github`
  paints those white on red. Error tokens are shown in the code colour
  instead.

### Fixed (C)

- **A fence's options leaked into its class**: ` ```js{1,3-5} ` rendered
  `class="language-js{1,3-5}"` and ` ```js:no-line-numbers ` rendered
  `class="language-js:no-line-numbers"` (0.2.0). The fence's info string
  is now parsed for its language, and no class can contain `{`, `}` or
  `:`, with highlighting on or off.

### Needs native review (C)

- `:code/copy` and `:code/copied` in **Malay** (`Salin`, `Disalin`) and
  **Tamil** (`நகலெடு`, `நகலெடுக்கப்பட்டது`).

### Documentation (C)

- DESIGN.md §11.3 item 10 is marked done, with what was measured; §5.6
  shows the `:highlight` keys; §8 marks Task C done. README: highlighting
  is on by default, the first build fetches Chroma, and how to turn it off.
  config.example.edn documents `:highlight`.

**Task B1 — colour modes** (§11.3 items 2–6 and 8):

### Breaking (B1)

- **The mode and page-style classes moved from `<body>` to `<html>`.**
  `theme-mode-*` and `theme-style-*` are now on `<html>` (with
  `data-default-mode`, and `data-mode` once the head script has run);
  `<body>` keeps `lang-*` and `page-*`. The theme's selectors are
  unqualified (`.theme-mode-dark`). User CSS written as
  `body.theme-mode-dark { … }` no longer matches: drop `body`.

### Added (B1)

- **Colour modes that work from the first frame** (D-P4-2). An inline
  script at the top of every `<head>` (byte-identical everywhere, 551
  bytes, CSP `'sha256-aykGrfu05czJ6oIj+Xn+Qrjxa7JG8hF3RGl0W0liGmw='`)
  applies the reader's stored mode — or `:theme :default-mode` — before any
  stylesheet loads, resolves *Follow system* to light or dark, and follows
  an OS change live. Without JavaScript, `:default-mode` applies and
  *Follow system* follows the OS through a media query.
  `<meta name="color-scheme" content="light dark">` and a per-mode
  `color-scheme`, so scrollbars and form controls match.
- **A mode toggle in the navbar** (D-P4-3): a menu button opening *Follow
  system*, *Light*, *Dark* and *Reading*, each with an icon; Enter or Space
  opens it, the arrow keys move, Escape closes it. Revealed by JavaScript,
  so there is never a dead control. The choice is remembered
  (`localStorage['clogem-mode']`) and shared between tabs.
  `window.clogem.getMode()` / `setMode(m)` and a `clogem:modechange` event
  for scripts. giscus follows the mode, including on its first load.
- **A new, WCAG AA palette in every mode** (D-P4-4): new light and reading
  backgrounds, a darker accent (`#1a7350`) and muted text in light and
  reading modes, a lighter muted text in dark mode, and a dark code block in
  reading mode (as vdoing has). Text, muted text and links reach 4.5:1 or
  better on every surface — checked by `bb test` against `theme.css`.
- **Links inside article text are underlined** (WCAG 1.4.1); navigation
  links are not.
- **Print styles**: the light palette whatever the mode, and no navbar,
  sidebar, TOC, toggle, search, comments or language banner. Reduced motion
  turns transitions and animations off.
- **Pagefind's search box and dialog follow the mode** (D-P4-5); they used
  to stay white in dark mode.
- **Icons** (D-P4-6): one sprite, `dist/clogem/icons.svg` (9.4 KB), built
  from vendored Lucide 1.52.0 (ISC; Feather-derived icons also MIT), with
  22 curated brand icons from Simple Icons 16.34.0 (CC0) and Tabler 3.48.0
  (MIT) ready for the social links of a later task — a sprite carries only
  the brands a site uses, and none yet. The licence notices head the
  sprite. The navbar dropdowns, sidebar groups and breadcrumbs use icons
  instead of the `▾ ▸ ›` text glyphs.
- **`overrides/custom.css`** (D-P4-8): a site's own stylesheet ships as
  `dist/clogem/overrides/custom.css`, cache-busted, and is linked last,
  after the theme's and Pagefind's. `bb dev` hot-swaps it.
- **`:theme :page-style` is validated**: `:card` or `:line`; anything else
  is a warning and falls back to `:card` (0.2.0 accepted any value). The
  two styles themselves arrive in Task B2.

### Changed (B1)

- **Code stays monospace on Chinese and Tamil pages.** The language font
  rules are now `:where(:lang(…))`, so `code`, `pre` and `kbd` keep the
  monospace stack; 0.2.0 rendered code in PingFang or Noto Sans Tamil there.
- **Inline code** takes the custom-block background and the text colour,
  not the code block's; an untranslated language in the switcher is shown
  in the muted colour rather than faded.
- **giscus's `client.js` is deferred rather than async**, so the theme it
  starts in is the reader's stored mode.

### Needs native review (B1)

- The five new theme strings (`:mode/label`, `:mode/auto`, `:mode/light`,
  `:mode/dark`, `:mode/read`) in **Malay** (`Mod warna`, `Ikut sistem`,
  `Cerah`, `Gelap`, `Bacaan`) and **Tamil** (`வண்ணப் பயன்முறை`,
  `கணினியைப் பின்பற்று`, `வெளிர்`, `இருள்`, `வாசிப்பு`).

**Task A — foundations** (§11.3 items 7, 11, 16, 18):

### Added

- **Cache-busting** (D-P4-7). Every theme asset URL the layout emits
  (`/clogem/…` CSS, JS and font CSS) carries `?v=<first 8 hex of the sha256
  of the file as written to dist/>`, and Pagefind's UI files
  `?v=<pinned Pagefind version>`, so GitHub Pages' 10-minute cache can no
  longer pair new HTML with an old stylesheet. Our own CSS versions its
  relative `url()`s too (the Tamil fonts). Implemented once, in
  `clogem.assets/href`; the file layout is unchanged, and stripping every
  `?v=` gives 0.2.0's bytes.
- **Config key warnings** (part of D-P4-16). An unknown key anywhere in
  `site.edn` warns with its path and the nearest known key; vdoing's
  camelCase spellings (`pageStyle`, `htmlModules`, `bodyBgImg`, `updateBar`,
  …) warn with the clogem-press key; keys planned for a later Phase 4 task
  warn "planned, not implemented in 0.2.0"; a non-empty
  `:theme :html-modules`, an `:analytics :provider` other than `:none` and
  `:seo :indexnow :enabled true` warn that they have no effect. `:nav` items
  are checked too (`:nav 1 :items 0 :lnk`), `:site :author` only in its
  `{:name :link}` shape, and keys a design revision removed
  (`:generator :ref`) say why. vdoing's `sidebarOpen` maps to the planned
  `:theme :sidebar-collapsed` (inverted), `sidebar` is reported as what
  clogem-press always does, `algolia` as deferred. All warnings, never
  errors. One table, `clogem.config/known-keys`.
- **`bb fetch-tool --tool pagefind|chroma|fswatcher`** (default
  `pagefind`). A new `clogem.tools` fetches, verifies and caches any pinned
  binary from a descriptor; Chroma 2.27.0 (`CLOGEM_CHROMA`,
  `:tools :chroma`) and the `org.babashka/fswatcher` 0.0.7 pod
  (`CLOGEM_FSWATCHER`, `:tools :fswatcher`, a zip unpacked with
  `java.util.zip`) are fetchable, though nothing uses them yet. The pod's
  hashes are trust-on-first-use: upstream publishes none. Since nothing
  runs them, a malformed `:tools :chroma` or `:tools :fswatcher` pin is a
  warning and the built-in pin is used; Pagefind's stays an error. That
  holds for a value that is not a map at all (`:tools {:chroma "2.27.0"}`,
  which 0.2.0 accepted), and the pin is repaired as one unit: a bad
  `:version` or `:sha256` resets both, so a custom version is never kept
  without hashes to verify it (every other key, such as `:url`, is kept).
- **`CLOGEM_JOBS`**: pages render on a bounded parallel pool, one worker per
  processor by default; `CLOGEM_JOBS=1` renders on one thread. The output
  and the diagnostics are identical either way.
- **A CI `browser` job** (D-P4-18): Playwright 1.63.0 (pinned, `npm ci`)
  over the built demo, running every `test/browser/*.test.mjs`; the first
  asserts that no page scrolls sideways at 320 or 360 px. Nothing in
  `test/browser/` ships.

### Changed

- **A failed render no longer leaves a mixed `dist/`** (D-P4-11). Every
  page and generated file is rendered in memory first, and every site
  asset read; only when all of it has succeeded is each file written,
  atomically (temp file and rename; in place when the OS refuses either),
  and only if its bytes changed. A
  render failure — any `Throwable` — exits 1 naming the page, and an
  unreadable site asset exits 1 naming the file; either leaves `dist/` and
  `permalinks.edn` exactly as they were (the ledger is now written after
  `dist/`). An I/O error *while writing* (a full disk, an unwritable
  directory) can still leave some files updated, and so can Pagefind
  failing after the pages are written (Phase 5). The summary line gains
  `(N written, M unchanged)`; a rebuild with no source change writes 0
  files. Files the build did not write (`CNAME`, `.nojekyll`) are still
  never deleted.
- **Writing** (D-P4-11). Every output must lie inside the output directory,
  and a directory left where a file now belongs is an error naming it
  (remove it or run `bb clean`) rather than a silently missing file; so is
  the reverse, a file left where a directory now belongs (an earlier
  build's `assets/docs` file when `assets/docs/readme.txt` now needs a
  directory), which used to fail mid-write with a raw
  `FileAlreadyExistsException` and a mixed `dist/`. Both are checked
  before anything is written. Temp
  files are `.clogem-tmp-<pid>-<nanoTime>`, so a name near the 255-byte
  limit still writes. Only abandoned temp files (a dead PID, or older than
  10 minutes) are swept, anywhere in the output tree — also when the
  output directory is itself a symlink (`dist -> /var/www/site`) — so
  `bb dev` and `bb build` into one `dist/` no longer delete each other's
  temp files. With search on, two builds into one `dist/` can still
  collide while Pagefind rebuilds `dist/pagefind/`, as in 0.2.0 (Phase 5):
  give one of them `--no-search`, or a separate `--out`. On Windows a
  rename refused because another process holds the file open is retried
  for ~2.5 s, then written in place as 0.2.0 did (untested on Windows
  itself). On a case-insensitive file system a case-only asset rename is
  written rather than kept in its old case.
- **Faster builds** (D-P4-11): Damerau-Levenshtein keeps rolling rows
  instead of a matrix (~40× faster; a 300-article site analyses in 0.25 s
  instead of 4.4 s), and pages render in parallel (that site builds in
  1.6 s instead of 7.7 s, the demo in 0.37 s instead of 0.68 s).
- Render diagnostics are reported in a fixed order: severity, file, line,
  message.

### Fixed

- `bb dev` survives a rebuild that throws an `Error` (it caught only
  `Exception`).
- A permalink with a `.` or `..` segment or a backslash
  (`permalink: /../../escaped/`) is now an **error** naming its source: it
  used to be written outside `dist/`, or with `/tags/../` over the home
  page. Such permalinks are now rejected because they can escape or alias
  `dist/`, even where 0.2.0 built them harmlessly (`/./tags/`, which a
  browser resolves to `/tags/`): write the clean spelling instead. One rule
  (`clogem.util/unsafe-permalink?`)
  covers an article's front matter, an `@pages/` file's `permalink:`
  (`@pages/tagsPage.md`; its index keeps the default path) and the keys of
  `permalinks.edn`. A bad ledger key is caught before write mode copies it
  into an article's front matter, so the failing build changes no source
  file. In an `@pages/` file whose `permalink:` is never read — any
  language variant but the default language's — an unsafe value is a
  warning that it is unused, not an error.
- **A page that would sit inside another output of the same build** is an
  error in `doctor` and before a build writes anything, naming both and
  where each comes from: `permalink: /assets/demo.txt/` beside the site
  asset `assets/demo.txt`, or `permalink: /robots.txt/` beside the
  generated `robots.txt` (or, with search on, a page under `/pagefind/`).
  0.2.0 silently buried the asset under a directory; the previous
  development build failed mid-write with a raw `FileSystemException`.
  Choose another permalink.
- A dangling symlink where the build needs a directory is reported as a
  broken symlink, not as a file.
- An unreadable site asset whose exception carries only the path reads
  "could not read site asset <p>: access denied (AccessDeniedException)",
  not the path twice.

### Documentation

- DESIGN.md §8 Phase 4 rebaselined to 18 days in nine tasks (the
  total is now 56 days), with its exit criterion, the deferred front-matter
  keys `navbar: false` and `search: false`, and the dropped vdoing options.
  New §11.3, the Phase 4 implementation changelog, records the phase's
  decisions. Corrections: htmlModules has 7 slots plus 2 show-modes, not 9
  regions, and the title badge is a random static icon (§1.2); Chroma's
  `--html-styles` honours `--html-prefix` with `--html` (§10, research/08);
  Pagefind falls back to the largest index for a language with none, and
  merged indexes stem with the primary page's WASM (§6.7, §10); dev is a
  fast full rebuild, not an incremental one (§5.4, §5.5); `:tools :chroma`
  takes a per-platform hash map and styles go under `:highlight` (§5.6).
- DESIGN.md §11.3 records the write path's guarantees and limits, and a
  known limitation for Phase 5: a case-only permalink change on macOS or
  Windows.
- config.example.edn: a config map value falls back to the map's first
  value, not "the key itself"; documents `:tools :chroma`,
  `:tools :fswatcher` and `CLOGEM_JOBS`; no longer promises that a failed
  build leaves `dist/` exactly as it was (only a render or read failure
  does).
- DESIGN.md §11.3's known limitations add two for Phase 5: concurrent
  builds with search on, and the stale-HTML sweep skipping a symlinked
  output directory.

## 0.2.0 — Phase 3: internationalization

Phase 3 is complete: the demo's five-language corpus builds with correct
hreflang, one giscus thread per article identity and per-language Pagefind
indexes, and the Tamil-only article resolves at its bare URL — each asserted
in CI (DESIGN.md §8). Part A: SEO, feeds, sitemap and the doctor i18n checks
(§11.2 items 30–39, D-P3-1 … D-P3-7). Part B: Pagefind search and the optional
self-hosted Tamil font (items 40–46, D-P3-8 … D-P3-12). Part C: the stored
language preference, the "also available" banner and giscus comments (items
47–50, D-P3-13 … D-P3-15).

### Added

- **A remembered language choice** (D-P3-13). Clicking a language in the
  navbar switcher or in the per-article variant bar stores its code in
  `localStorage['clogem-lang']`; nothing else sets it (no
  `navigator.languages` seeding), and blocked storage behaves as if none
  were set. Neither kind of link is ever rewritten.
  New vanilla `js/lang.js`, shipped to every page of a site with more than
  one language and to no single-language site.
- **The "also available" banner** (D-P3-14, D-11). New
  `:i18n {:preference :banner}` (default; `:redirect` and `:ignore` are the
  alternatives, anything else is a config error). On a page served at its
  bare URL — an article or catalogue identity URL, a home or index
  overview — whose hreflang set holds the reader's stored language L, a
  dismissible `<div role="note" lang="<L>" dir="<L's :dir>">` at the top of
  the main column offers the L page, **written in L** (a site-added language
  with no banner strings gets them along the fallback chain, and the note's
  `lang`/`dir` are then those of the text shown). Dismissal is remembered
  per page (the 100 most recent) and moves focus to the main column. A long
  `:label` wraps inside the note and in the navbar switcher. The page's data is a JSON `<script
  id="clogem-lang-data">` that escapes `<`, `>` and `&`, so a `</script>` in
  a site string stays inert. `:redirect` instead replaces a bare URL with
  the L variant from an inline `<head>` script placed before the
  stylesheets, so it does not wait for them and nothing paints; prefixed
  URLs never redirect, and a `#hash` or `?query` is dropped (anchors differ
  per language). `:ignore` shows nothing. New theme keys
  `:banner/available`, `:banner/read` and `:banner/dismiss` in all five
  files. **The Malay and Tamil banner strings are new and need native
  review** (DESIGN §10).
- **giscus comments** (D-P3-15). With `:comments {:provider :giscus :repo …
  :repo-id … :category … :category-id …}` every tree article and post (not
  catalogues, homes or index pages) ends with the giscus widget:
  `data-mapping="specific"` and `data-term` = the bare permalink
  (`/pages/xxxxxx/`, no base or language prefix), so every language shares
  one thread; `data-strict="1"`; `data-lang` from the locale's `:giscus`;
  `data-theme` from `:theme :default-mode`; lazy loading. `comment: false`
  on the primary variant turns it off. New `js/comments.js` exposes
  `window.clogem.setCommentsTheme(theme)` for the Phase 4 colour toggle.
- **Demo:** giscus enabled with placeholder ids (`example/clogem-demo`), and
  `:preference :banner` spelled out.
- **CI:** `.github/scripts/check_giscus.py` asserts, on the root-base and
  `/clogem-demo/` builds, one giscus script per article variant, one
  `data-term` per identity group (the bare permalink), distinct terms across
  groups, the right `data-lang` per language, and no script on any other
  page — the last clause of the Phase 3 exit criterion. A group with no
  script on any variant (`comment: false` on the primary) passes; a mixed
  group fails. The demo's hello post has comments off to exercise it.
- **Search with Pagefind** (`:search {:provider :pagefind}`; the default
  stays `:none`). `build` runs the `pagefind_extended` binary over `dist/`
  as its last step — one index per `<html lang>`, so five indexes on a
  five-language site with no configuration. `build --no-search` and `dev
  --no-search` skip it; `dev` otherwise re-indexes on every rebuild (about
  0.5 s on the demo); `doctor` never runs it. A failed fetch, a sha256
  mismatch or a non-zero exit fails the build with exit 1 (after `dist/` is
  written — CI then does not deploy it).
- **The generator fetches Pagefind itself:** the release asset for the
  platform, over babashka's HTTP client (honouring `HTTPS_PROXY`), checked
  against a per-platform sha256 pin (`:tools {:pagefind {:version "1.5.2"
  :sha256 {<platform> "<hex>"}}}`; 1.5.2's five hashes are built in), and
  unpacked with `tar` into `$XDG_CACHE_HOME/clogem-press/tools/pagefind/
  <version>/<platform>/` (else `~/.cache/…`), outside the site.
  `CLOGEM_TOOLS_DIR` / `:tools :cache-dir` move the cache;
  `CLOGEM_PAGEFIND` / `:tools :pagefind :path` use a preinstalled binary.
  New task **`bb fetch-tool`** fetches and verifies it and prints its path.
- **Only article content is indexed:** `data-pagefind-body` on the article
  and catalogue `<article>` only, so homes, index and pagination pages are
  left out; heading anchors, the variant bar and fallback markers are
  `data-pagefind-ignore`.
- **Pagefind's Component UI** on every page: `<pagefind-config>` first in
  `<body>` (with `lang="zh-TW"` on zh-Hant pages, for Pagefind's Traditional
  strings), a navbar search button labelled with the existing `:nav/search`,
  and the search dialog. Malay, which Pagefind has no strings for, gets them
  from 25 new theme keys `:search/…` (in all five theme files; en, zh-Hans,
  zh-Hant and ta copy Pagefind's own) through a vendored `js/search.js`.
  **The Malay `:search/…` strings are new and need native review** (DESIGN
  §10, review capacity). Under `:search {:provider :none}` no search markup
  or script is emitted at all.
- **`:theme {:fonts {:tamil :self-hosted}}`** serves subset Noto Sans Tamil
  v2.004 Regular and Bold (woff2, ~24 KB each, SIL OFL 1.1, `OFL.txt`
  beside them) through `clogem/fonts/tamil.css` — `font-display: swap`,
  `unicode-range` matching the subset, `local()` first. The default
  `:system` links and copies no font file. `scripts/subset-tamil.md` has the
  source and the `pyftsubset` commands.
- **Demo:** `:search {:provider :pagefind}`, and a three-language article
  with a long code line and a wide table (the §6.9 wrapping check's case).
- **CI:** caches the tools directory keyed on the Pagefind version, platform
  and hash; `bb fetch-tool` exports `CLOGEM_PAGEFIND` before `bb test`, so
  the real-binary integration test always runs there.
  `.github/scripts/check_search.clj` asserts, on the root-base and the
  `/clogem-demo/` build, that `pagefind-entry.json` lists exactly the five
  languages with one page per article variant, that only article and
  catalogue pages carry `data-pagefind-body` (never a home), and that every
  page opens `<body>` with `<pagefind-config>` (`lang="zh-TW"` on zh-Hant);
  the link resolver now covers `<base>pagefind/…`.

- **Canonical, hreflang and `og:locale` in every page's `<head>`.** A
  self-referencing canonical; the page's whole hreflang set, itself included,
  with `x-default` at the bare URL (identity group for articles and
  catalogues, all five copies for homes and index pages; none on `…/page/N/`);
  `og:locale` from a new per-locale `:og` (`en_US`, `zh_CN`, `zh_TW`, `ms_MY`,
  `ta_IN`) plus `og:locale:alternate` per other language in the set.
- **Atom feeds**, one per language: `/feed.xml` and `/<lang>/feed.xml`, the
  20 newest of that language's own variants, RFC 3339 dates, per-entry
  hreflang alternates, a plain-text summary from `<!-- more -->`, categories
  and tags. Every page links its language's feed. A language with no articles
  yet gets an empty feed. New default `:seo {:feeds true}`.
- **`/sitemap.xml`** with `xhtml:link` alternates for every page that has a
  set (no `lastmod`/`priority`/`changefreq`), and **`/robots.txt`** under base
  `/` only; a site's own `assets/robots.txt` is copied there instead.
- **`:i18n :fallback`** (default `[:site-default :en]`) is honoured by UI
  strings, config strings and category labels; a bad value is a config error.
- **doctor/build warnings:** a translation orphaned by a renamed source
  (with a rename suggestion; the same-number case extends the existing
  duplicate-number error instead), a site string key that is a near-miss of a
  theme key ("did you mean …?"), and a site-added key missing for some
  language.
- **CI:** `.github/scripts/check_seo.py` asserts absolute self-canonicals,
  hreflang reciprocity, five parseable feeds, the sitemap and robots.txt on
  the root-base build; the `/clogem-demo/` build must have no robots.txt.

### Changed

- **`:comments :category` is required** under `:provider :giscus` (the
  category name giscus shows as `data-category`), and `:comments :provider`
  must be `:none` or `:giscus`; both are config errors at load.
- **`:comments :repo` must be `owner/name`** (`[A-Za-z0-9-]+/[A-Za-z0-9._-]+`);
  a bare name, a URL or a value with a space used to build and break the
  widget at runtime, and is now a config error naming the value. An unknown
  `:comments` key warns; `:mapping :permalink`, which §5.6's sketch used to
  show and real site.edn files carry, is accepted quietly (any other
  `:mapping` warns: threads are always keyed on the permalink).
- **`:theme :default-mode` is validated**: `:auto`, `:light`, `:dark` or
  `:read` (theme.css styles all four); a typo such as `:drak` used to give
  `body.theme-mode-drak` and an auto giscus theme silently, and is now a
  config error. giscus starts `:read` as `light`.
- **giscus language codes** follow giscus's current routable set
  (re-verified 2026-10-01): `bg`, `cs`, `da`, `eu`, `gr`, `hbs`, `hu`, `kh`,
  `uz`, `zh-Hans` and `zh-Hant` are now accepted. `ms` and `ta` still 404
  and must map to `en`; the defaults stay `zh-CN` / `zh-TW`.
- **Windows on ARM** (`aarch64-pc-windows-msvc`) is a supported Pagefind
  platform; its 1.5.2 hash is built in (six hashes now).
- With a blank `:site :url` none of the URL-bearing output above is
  emitted and analyse warns once; the site still builds.
- Redirect stubs no longer carry `rel=canonical` (they are `noindex`), and
  their `<html lang>` is the default language's, not a hard-coded `en`.
- The demo site's `:url` is `https://clogem-demo.example` (reserved TLD), so
  it no longer emits canonicals on the real domain.
- `og:locale` and `og:locale:alternate` are emitted even with a blank
  `:site :url` (they name languages, not URLs), and a site's own
  `assets/robots.txt` is copied to the root without one too.
- `:site :url` is validated: no scheme and host is a config error (every
  canonical came out relative); a path that repeats `:base`
  (`https://u.github.io/repo` with `:base "/repo/"`) is a config warning
  saying to drop the path (the base was doubled). A trailing slash is fine.
- `:seo :x-default` is read and validated; `:primary` is the only legal
  value, anything else a config error.
- The orphaned-translation heuristic is quieter: the distance allowed scales
  with the shorter base name (0 up to 3 characters, 1 up to 6, 2 above), and
  a post compares its date-stripped slug with posts of the same date only,
  so a weekly series or `01.css.md` beside `02.js.ta.md` no longer warns.
- `doctor`'s raised ex-info now carries `:clogem/warnings` beside
  `:clogem/errors`, so a caller can see the warnings of a run that failed.

### Fixed

Search follow-up (DESIGN.md §11.2 item 46), each with a regression test:

- **Search no longer indexes article chrome.** The meta line (author, date,
  categories, tags), the `titleTag` badge and a catalogue row's date are
  `data-pagefind-ignore`; Pagefind joined them with the text around them, so
  `markdown` found nothing (`Localmarkdown`), `2026` matched 15 of 16 English
  pages through the date, excerpts opened with `2026-08-16Guide / Basics.`
  and one result title read `A post from the year before原创`. The result
  title is now set with `data-pagefind-meta="title"` on the title text alone;
  categories and tags are `data-pagefind-filter` values (`category`, `tag`)
  and are indexed as separate words through `data-pagefind-index-attrs`.
- **A rebuild into the same `dist/` drops deleted pages.** `build` removes
  `.html` files under the output directory that it did not write (never
  other files, never outside it, never through a link), and Pagefind's
  `pagefind/` is replaced, not added to — a deleted page stayed searchable
  and every `bb dev` rebuild grew the bundle. **`:build :out` may no longer
  be the site directory, an ancestor of it, or a directory holding the
  content directory or a `site.edn`:** that is now a config error, raised
  before anything is written.
- **`--no-search` builds without search.** Pages no longer link the Pagefind
  CSS and JS or show a search box that the build never backs with a bundle.
- **The Pagefind cache is verified on every use.** A stamp beside the binary
  records the archive hash it was verified against and the binary's own
  hash; a cache entry from another pin (a site with its own `:url`/`:sha256`
  sharing the cache), a modified binary or a missing stamp is deleted and
  fetched again instead of run.
- **Concurrent first-time fetches no longer race.** The fetch holds a lock
  file and installs with an atomic rename; four parallel cold-cache builds
  failed 2 rounds in 6 before.
- **Downloads:** a connection dropped mid-download is a build error with the
  offline hint, not a stack trace; 30 s connect, 5 min response and 5 min
  idle-body timeouts (a silent server hung the build); `NO_PROXY` /
  `no_proxy` are honoured, `HTTP_PROXY` is used for an http mirror, and
  `user:pass@` in the proxy URL authenticates.
- **Smaller:** a non-map where a map is expected (`:search :pagefind`,
  `:theme {:fonts :self-hosted}`, `:seo :none`) is a config error instead of
  a `ClassCastException`; leftover `*.download-*` / `*.partial-*` files of a
  killed fetch are swept; an archive whose binary is a symlink is refused;
  tar reads the archive on stdin (no `C:` host parsing) and a missing tar is
  reported cleanly; a relative `CLOGEM_PAGEFIND` resolves against the
  working directory and its errors print the absolute path and say whether
  it is a directory or missing.
- **The search tests failed as a non-root user** (CI only): the test's
  fake Pagefind, run with no `--site`, wrote its bundle to `/pagefind`. The
  fake now refuses to run without `--site`, and the test passes one.

- **`bb test` errored on babashka 1.13.219**, which `:min-bb-version
  "1.13.0"` admits: the timezone test set the JVM default zone through
  `java.util.TimeZone/setDefault`, which that build rejects reflectively.
  The scenario now runs `fm-fix` and `build` as `TZ=<zone> bb --config …`
  subprocesses — the way a zone really reaches the generator.
- **The Tamil-only checks were vacuous.** CI and `build_test` grepped
  `lang="ta"`, which the switcher's `hreflang="ta" lang="ta"` matches on
  every page; both now assert on the `<html>` element of `/pages/171a98/`.
- **A date that does not exist crashed `build` and `doctor`.** A
  date-shaped but invalid `date:` (`"2026-02-30 10:00:00"`, `"2026-13-01"`,
  `24:00:00`, `10:61:00`, an offset of `+25:00`) threw
  `DateTimeException` from the feed code with no file named and no dist/;
  0.1.1 built the same tree. It is now an analyse warning naming the file,
  "`date:` value `…` is not a valid date", reported once by `doctor`, and the
  article is treated as undated (sorts last, left out of feeds and
  `/archives/`).
- **An unquoted zoned YAML timestamp lost its zone.** `date:
  2026-08-01T10:00:00+08:00` became `2026-08-01T02:00:00+08:00` in feeds
  under `TZ=Asia/Singapore`. The instant is now kept at the written offset
  (an EDN `#inst` gets `…Z`); a quoted `"…+08"` is read instead of silently
  treated as zoneless.
- **robots.txt named a sitemap that did not exist** under `:seo {:sitemap
  false}`; the `Sitemap:` line is written only when the sitemap is.
- **Feed summaries carried the heading anchor's `#`** ("…#Sub heading…");
  `aria-hidden` elements are dropped before tags are stripped.
- **One orphaned translation could draw two contradictory diagnostics** —
  the duplicate-number error suggesting one rename and a warning suggesting
  another. A file covered by the duplicate-number error is now skipped by
  the orphan check.
- **Removing `:en` changed the fallback chain from 0.1.1's.** The default
  `[:site-default :en]` was filtered to the configured languages, so a
  config map's `:en` value lost to its first value; only a chain the site
  wrote is now validated, and the default is kept as is.
- **A per-language `:site :author`** (`{:en "Jane" :zh-Hans "简"}`) was
  ignored by feeds and the byline; it is resolved per language.
- **Article hrefs were not percent-encoded** (since 0.1.1): lists, the
  sidebar, prev/next, the variant bar and markdown links emitted a CJK or
  spaced permalink raw, unlike every other href.

### Documentation

- DESIGN.md §11.2 items 47–50 record D-P3-13 … D-P3-15 and the exit
  criterion. §6.4 rule 3 is deliberately narrowed (the preference never
  rewrites the switcher's links); the banner's text in the reader's chosen
  language is the one exception to rule 2; §6.8 gains `:comments :category`
  and the CSP note (`script-src`, `frame-src` and `style-src
  https://giscus.app` — client.js links giscus's `default.css` into the host
  page; the `:redirect` inline script needs `'unsafe-inline'` or its hash);
  §5.6's sketch shows `:comments :category` and `:i18n :preference`, and no
  longer `:mapping`; Appendix A
  item 11 lists giscus's current languages; §8 records the exit criterion
  as met.
- DESIGN.md §6.7: zh-Hant search UI strings come from `lang="zh-TW"`, not
  from strings of ours; cross-language search (`mergeIndex`) is deferred to
  Phase 4; a strict CSP needs `worker-src 'self'` and `'wasm-unsafe-eval'`.
  §6.9: a self-hosted Tamil face is still fetched by English pages, for the
  switcher's "தமிழ்" label. §4: the tools cache lives outside the site.
- DESIGN.md §6.6: `og:locale` corrected to `language_TERRITORY`; Google's
  canonical-with-hreflang guidance cited. §6.7, §10 and Appendix A item 7:
  Pagefind does **not** word-segment zh-Hant (jieba runs with a Simplified
  dictionary). §6.9: Latha is a Windows feature-on-demand font; Nirmala UI
  is the default Tamil face.

## 0.1.1 — Phase 2 fix round

Defects found in 0.1.0 by review and by building the real site, each fixed
with a regression test that fails on 0.1.0. DESIGN.md §11.2
items 16–29, and amendments to items 2, 5 and 8, record the decisions.

### Fixed

- **Non-BMP characters percent-encoded as `%3F%3F`.** A category `😀Fun`
  linked to `/categories/%3F%3Ffun/` (a 404) and a heading `## Hello 😀 world`
  got anchors no id matched. Encoding now walks code points.
- **`build --no-write` wrote `dist/` before reporting content errors.**
  Analyse errors now stop `build` before the ledger write and before `dist/`
  exists. Render-time exceptions can still leave a partial `dist/` (a known
  limitation, DESIGN.md §11.2 item 2).
- **A YAML error in `index*.md` or `@pages/*` still bypassed that gate.**
  Those files were read at render time, so `postList: [bad` in
  `content/index.md` failed `build` (with or without `--no-write`) after 29
  files were in `dist/`, and doctor counted it once per language home. They
  are now resolved and parsed during analyse; each bad file is one error, and
  the build stops before `dist/` exists.
- **Two spellings of one language's `index`/`@pages` file.** With
  `index.zh-Hant.md` and `index.ZH-HANT.md` both present the ALL-CAPS file
  won silently. The exact canonical spelling is preferred, and two files
  naming the same language are an error naming both, as in the content tree.
- **`:theme :per-page` and `:theme :sidebar-depth` were untyped.** A string
  crashed render with a ClassCastException, for `:sidebar-depth` after 55
  pages had been written. Both are config errors now, repaired to their
  defaults so `doctor` keeps reporting; an explicit `nil` means the default.
  A bad front-matter `sidebarDepth` warns, naming the file.
- **`:generator :min-version` ignored pre-releases and malformed floors.**
  Versions compare by semver precedence (`0.1.0-phase1` < `0.1.0`), and a
  floor that is not a `MAJOR.MINOR.PATCH(-pre)?` string is a config error.
  A floor written like a tag (`"v0.1.1"`) is told to drop the leading `v`.
- **Hand-written dates sorted as strings.** `"2026-9-5"` sorted after
  `"2026-10-01"` on the home list, archives, sticky ranks and post prev/next.
- **Post prev/next re-sorted every post on every article page.** The order is
  computed once per build (`:post-order`).
- **Category labels skipped sidebar groups and Catalogue headings.**
  `:i18n :category-labels` now applies to every display of a category name.
- **Homepage excerpts reported their problems once per language home**, and
  a `<!-- more -->` inside `::: tip` warned about an unclosed container.
  Excerpt diagnostics are discarded; the article page reports each once.
- **`/tags/` and `/categories/` could list no article.** On a site whose
  articles carry `tags: []`, `/tags/` showed only "All 0", `/categories/`
  linked nothing, and every home ended with an empty Tags card. Overviews now
  list every article, paginated, below the bar; empty bars and cards are
  omitted.
- **"All N" no longer matched the list beneath it.** With 5 articles, 2 of
  them tagged, `/tags/` read "All 2" above 5 rows. "All" counts every
  article, which is what the overview lists.
- **Filtered index pages reused the overview's `<title>`.**
- **The article fallback notice could never render.** It is gone; switcher
  entries that land on a language home because the article is untranslated
  are marked, with visually-hidden text from `:page/fallback-notice`. The
  notice is said once, in the page's language: no duplicate `title`.
- **`index.<lang>.md` and `@pages/` suffixes were case-sensitive**, so
  `index.zh-hant.md` was ignored on Linux while the tree accepted the same
  spelling; `render/localized-file` returns `{:path :own?}`.
- Homes without a body of their own had no `<h1>`; `toc.js` loaded on pages
  without a TOC; no `<meta name="description">`; zh-Hant's archive string was
  封存 ("sealed") rather than 歸檔.

### Changed

These change published URLs or what builds.

- **Slugs are legal Windows file names.** `< > : " | ? *` and control
  characters collapse to `-`, trailing dots and spaces are trimmed, and
  reserved device names get `_` after the stem: `Q&A: why?` → `q&a-why`
  (was `q&a:-why?`), `etc.` → `etc`, `CON` → `con_`; `COM0`, `LPT0`, `CONIN$`
  and `CONOUT$` are reserved too. A tag or category containing those
  characters moves to a new URL.
- **Category and tag slug collisions are a build error.** `Notes` beside a
  `_posts/notes/` subfolder, or tags `Clojure` and `clojure`, fail `build`,
  `fm-fix` and `doctor` instead of silently overwriting one index page (it
  was a doctor-only warning). Slugs are never renamed automatically.
- **Near-miss rule (c) is case-sensitive.** `01.en-dash.md`, `ta-da`,
  `ms-word` and `en-bloc` are titles again; a tag needs BCP 47 casing
  (`ta-IN`, `zh-Hanz`) or to be a typo of a configured script (`zh-hsna`).
  Suffix *matching* is still case-insensitive.
- **Rule (c) catches upper-case tags again.** The case-sensitive rule let
  `02.title.ZH-HANT-HK.md` (an error in 0.1.0) become an English article
  titled `title.ZH-HANT-HK`. A configured script now anchors the primary
  case-insensitively too (`ZH-HANT-HK`, `Zh-Hant-HK`, `ZH-HSNA`), and an
  ALL-CAPS configured primary followed only by UPPERCASE or 3-digit regions
  is a tag (`EN-NZ`, `MS-BN`, `TA-MY`). `MS-Word`, `Ta-Da` and `ms-access-tips`
  stay titles; an all-caps `TA-DA.md` is now an error (DESIGN.md §6.1).
- **Homepage excerpts are marker-only.** Without `<!-- more -->` a card
  shows no excerpt and no read-more link (VuePress/vdoing behaviour); add the
  marker where an excerpt is wanted. The demo now carries explicit markers.
- `localized-file` lives in `clogem.pages` (`render/localized-file` is an
  alias): a single 3-arity function returning a map, with `:ambiguous` when
  two files name the language. `render/index-paths` takes the model.

### Tests

283 tests / 1629 assertions (from 249 / 975).

## 0.1.0 — Phase 2: core vdoing parity

Implements DESIGN.md §8 Phase 2 in full (all seven line items) plus the
decisions D-P2-1 … D-P2-14 recorded in §11.2. A vdoing-convention tree now
renders in all four usage modes — KB+blog, blog-only, KB-only, docs — and the
demo site and test suite prove it rather than describe it.

### Added

- **Sidebar tree** (`clogem.model/sidebar-tree`, `clogem.theme.layout/sidebar`)
  — nested `<details>`/`<summary>` groups per numbered directory, collapsible
  without JS; `[:theme :sidebar-open]` opens all groups or only the active
  trail; the current leaf and every ancestor are marked; an article page shows
  only its own top-level tree; posts and `sidebar: false` pages show none.
- **Indexes in the model** — `:categories`, `:tags`, `:archives`, `:catalogue`,
  `:sticky`, and `:posts` widened to every article group. All hold article ids
  built from identity groups, so dedupe-by-identity stays structural.
- **Index pages** — `/categories/` (bars with counts), `/categories/<slug>/`,
  `/categories/<slug>/page/N/`, the same for `/tags/`, and `/archives/`
  (year → month, newest first), rendered per language with matching
  `<html lang>`, bare for the site-default language and under `/<lang>/`
  otherwise. Rows follow §6.8; row order is identical across languages. Slugs
  keep CJK and Tamil verbatim and are percent-encoded in hrefs.
- **Homepage** — `postList: detailed | simple | none`, `simplePostListLength`,
  `hideRightBar`, `features`; sticky articles first (`sticky: true` = rank 1);
  excerpts from `<!-- more -->` or the first paragraph (marker-only since
  0.1.1); `/page/N/` pagination
  at `[:theme :per-page]`; an update bar linking to `/archives/`.
- **Article chrome** — breadcrumbs (catalogue page when one covers the
  directory, else the category index), the ArticleInfo line (author, ISO date,
  linked categories and tags), the `titleTag` badge, prev/next (tree order for
  tree articles, date order for posts, front-matter `prev`/`next`/`false`).
- **Catalogue pages** — `pageComponent: {name: Catalogue, data: {path imgUrl
  description}}` renders a card grid of the subtree instead of the body;
  unknown names and unresolved paths warn and fall back to the body.
- **`@pages/` auto-creation** — `categoriesPage.md`, `tagsPage.md`,
  `archivesPage.md` created when missing (never overwritten, idempotent,
  skipped under `--no-write`, gated by the `:content` toggles); their
  `permalink:` sets the index root and their body renders above the list.
- **TOC bar** — h2–h3 by default, `sidebarDepth` governs depth, built from
  the same AST as the body so ids and anchors agree; a vendored vanilla
  scroll-spy (`js/toc.js`, no CDN, no deps) that decodes the fragment before
  `getElementById`.
- **Containers** — `note tip warning danger details theorem right center` as a
  source-line pre-pass with nesting, code-fence immunity, list-item nesting,
  page-language default titles; `cardList` / `cardImgList` YAML card grids with
  links routed through the link rewriter; malformed YAML is a warning plus a
  visible danger block.
- **Nav** — per-language prefixing of site pages, permalinks resolved to the
  reader's variant, `:items` dropdowns; a parent may carry both `:link` and
  `:items`.
- **Language switcher** — site-wide on every page, listing every configured
  language, landing on the same page in the other language (`aria-current` on
  the current one).
- **Config** — `[:theme :per-page 10]`, `[:theme :sidebar-depth 2]`;
  `[:theme :sidebar-open]`, `[:content :category/:tag/:archive]` and
  `[:i18n :category-labels]` are now read. 18 new theme strings in all five
  languages, plus `:container/theorem`.
- **Root-relative links get the site base** (vdoing's `$withBase`) in markdown
  hrefs and image sources.
- **`doctor`** — reports undated articles, variants disagreeing on
  `pageComponent`/`article`/`comment`, directories deeper than level 3,
  unresolved catalogue paths and slug collisions, and renders every page in
  memory so dead links and container problems land in the report.

### Fixed

- **Config validation is fatal (D-P2-12).** A bad `:langs :default` printed
  "the build will not run" and then ran, exiting 0. `build`, `doctor` and
  `fm-fix` now exit non-zero on a config error; `build` writes nothing.
- **Hyphenated slugs were misspelled language tags (D-P2-14).**
  `02.api-design.md`, `03.my-notes.md`, `05.re-frame.md` hard-errored. The
  regex branch is gone; a near miss is a confusable, one hyphenated edit from a
  configured code, or a configured primary subtag followed only by
  script/region-shaped subtags.

### Changed

- `:index/recent` removed from the theme strings (nothing renders it).
- `build_test`'s mechanism-2 assertion reads the categories as three linked
  names rather than one plain string; same invariant.
- `clogem.markdown/parse` takes the page context (one-arity form kept).
- Version `0.1.0` (`src/clogem/version.edn`), so the content repo can declare
  `:generator {:min-version "0.1.0"}`. (No `v0.1.0` tag was pushed with this
  release; pin publish.yml's `ref:` to a commit sha until a tag exists.)

### Review pass

An adversarial review of the Phase 2 diff found and fixed: posts' prev/next
skipped `article: false` posts; `sidebarDepth: 0` still showed h2s; the body's
`# Title` rendered as a second `<h1>`; a non-padded `date:` produced an invalid
`<time datetime>`; `catalogue-dir-key`'s backslash normalization never fired;
the card-list YAML dedent was a no-op; `@pages/` front matter was re-read on
every rendered page (a malformed file diagnosed ~200 times); a user-authored
`@pages/` body leaked onto every language's page; blank tags rendered empty
spans; a `:nav` permalink naming nothing was given a language prefix; and a
site could not remove a default locale. Each has a test.

### Tests

249 tests / 975 assertions (from 144 / 431): model indexes and tree, index
pages per language, homepage modes and pagination, article chrome, catalogue
pages, `@pages/` golden files, TOC and scroll-spy, containers and card lists,
config fatality end to end (including a real `bb` subprocess), the near-miss
rule table, theme-string parity across languages, and the four usage modes.

## Unreleased — Phase 0 + Phase 1 (merged as PR #1)

First working generator. Implements DESIGN.md §8's Phase 0 (generator side) and
Phase 1 in full.

### Added

- **CLI** (`bb.edn` + `clogem.cli`) — `build`, `dev`, `serve`, `clean`,
  `doctor`, `fm-fix`, `test`, using quickblog's ns-metadata spec pattern so
  option parsing, `--help` and defaults come from one definition per task.
  Designed for `bb --config /path/to/clogem-press/bb.edn <task>` from the
  content directory.
- **Config** (`clogem.config`) — load, deep-merge (defaults < site.edn < CLI),
  normalize and validate. Language codes are canonicalized and indexed
  case-insensitively. Validation covers the mandatory giscus `data-lang` mapping
  and the `:generator :min-version` compatibility floor.
- **Scanner** (`clogem.scan`) — §6.1's amended parsing algorithm: numbered
  directories, language suffixes closed over the configured `:langs`,
  case-insensitive matching with canonical storage, near-miss language codes as
  hard errors with edit-distance suggestions, and the identity-scoped
  duplicate-number rule.
- **Front matter** (`clogem.frontmatter`) — YAML and EDN read; surgical
  auto-fill that inserts only missing keys and never reserializes the block;
  CRLF and BOM preserved.
- **Model** (`clogem.model`) — identity groups by permalink, resolved implicitly
  by (directory, order, filename base) or explicitly by a shared `permalink:`;
  primary-variant selection from `:priority`; permalink minting with collision
  checks; variant permalink inheritance; `permalinks.edn` ledger; deterministic
  read-only fallback URLs.
- **Markdown** (`clogem.markdown`) — nextjournal/markdown with renderer
  overrides: raw HTML passthrough, heading anchors from pre-computed slugs,
  permalink-aware link rewriting with dead-link warnings.
- **Theme** (`clogem.theme.*`) — navbar with language switcher, flat sidebar,
  article page, per-article variant bar, fallback notices, `<html lang>` per
  variant, five sets of UI strings, and a CSS-variables stylesheet with the
  `:lang()` font stacks of §6.9.
- **Export** (`clogem.render`) — the §6.3 URL scheme, redirect stubs under
  `:prefix-default? true`, asset copying.
- **Dev server** (`clogem.dev`) — http-kit, a vendored static handler, SSE live
  reload, and a `--poll` watcher that the fswatcher pod falls back to
  automatically.
- **`examples/demo-site`** — a five-language fixture exercising every
  convention, plus `doctor-cases/` holding the filenames that are *supposed* to
  fail.
- **Tests** — 140 tests / 415 assertions: golden-file front-matter write-back,
  parser table tests reproducing §6.1's worked examples, identity-group
  resolution, permalink-collision avoidance, ledger round-tripping, output
  layout under a non-root base, dev-server path containment and watcher
  liveness, and CJK/Tamil heading-slug characterization.
- **CI** (`.github/workflows/ci.yml`) — tests, then three demo builds
  (`--no-write`, normal, and one under a non-root `--base`) each followed by
  `git diff --exit-code`, then `doctor`, then assertions on the emitted URL
  scheme and on every internal link resolving to a real file.

### Fixed — defect-fix pass over Phase 0/1

An independent review of the Phase 1 implementation found ten defects; all ten
are fixed, each with a regression test written to fail first.

1. **The site `:base` was baked into the output layout.** With `:base
   "/project/"`, pages exported to `dist/project/…` while their links pointed
   at `/project/…` — a doubled prefix once deployed and a 404 at the site root.
   `dist/` *is* the deploy root, so `render/uri->file` strips the base. Only
   base `/` had ever been exercised, which is exactly the base that hides it;
   CI now builds under a non-root base too.
2. **Two posts sharing a slug collided into one article.** A post's identity
   key dropped the `YYYY-MM-DD-` prefix and flattened every subfolder to
   `_posts`, so posts differing only by date or subfolder became one identity
   group and hard-errored. Identity now uses the full stem and the subfolder;
   only the *display* title drops the date.
3. **Every build erased the D-15 tombstone ledger.** `:tombstones` was read from
   a cfg key nothing ever set, so `permalinks.edn` was rewritten with `{}` each
   time — irreversible, since a tombstone is by definition a permalink no file
   declares any more. Also made ledger serialization canonical, so a rewrite
   that changed nothing lands as no diff.
4. **Conflicting `permalink:` declarations split the group they claimed to
   merge.** The warning said the highest-priority variant's permalink was being
   used; the code gave every declaring member its own. Now a hard error — see
   the design change below.
5. **Non-map front matter crashed or corrupted the file.** Scalar and list
   blocks threw from `contains?` deep inside the fill plan; a YAML **set** did
   not throw — `contains?` accepts it and answers false for every key — so it
   reached the writer, which appended mapping lines into a non-mapping block
   and corrupted the source while the build reported success. All are now
   errors, and the pipeline raises before `apply-fill!`.
6. **The dev server's containment check was a string prefix test.** A
   `dist-readonly/` sibling — which CI creates — escaped a `dist/` root. It now
   compares on the path separator.
7. **`try-pod-watch!` never probed.** It returned true on registration, so in a
   zero-event container `bb dev` announced it was watching and silently never
   rebuilt. It now proves delivery, and budgets registration too (`watch` was
   observed blocking indefinitely). Fixed alongside it: `(requiring-resolve
   'babashka.pods/load-pod)` deadlocked once the server was up.
8. **Fresh permalinks could collide with the ledger's.** `taken` omitted the
   ledger's own permalinks, and the deterministic `--no-write` fallback was
   never checked against anything. Both fixed; group iteration is sorted so a
   read-only build reproduces.
9. **Mixed `:date` types threw on a legal tree.** An unquoted YAML date parses
   to `java.util.Date` and a quoted one to a String, so a migrated vdoing tree
   plus one auto-filled article broke `sort-by`. `:date` is normalized to a
   String at parse time.
10. **Duplicate numbers were only checked for files.** Sibling *directories*
    sharing a number kept vdoing's warn-and-carry-on, in the half of the tree
    D-3 is simplest about.

### Design changes made during implementation

Recorded in DESIGN.md in the same commit:

- **Identity uses the filename base, not the display title.** §6.2 said
  "(directory, order, title)"; the first demo build proved that must mean the
  title parsed from the *filename*, since a translation almost always sets a
  translated `title:` — keying on the display title gave every translated file
  its own permalink and silently defeated the i18n design.
- **`:giscus` presence is required only when giscus is the provider**; an
  unroutable *value* is an error whenever one is present.
- **Heading slugs are not GitHub-style.** nextjournal/markdown preserves
  punctuation. CJK and Tamil survive verbatim, which retires the risk the design
  flagged; a whitespace repair is applied uniformly to heading ids and the TOC.
- **Errors are collected and reported together** rather than raised at the first
  offender.
- **Conflicting permalinks inside one identity group are a hard error** (§6.2).
  Mechanism 2 joins articles; it never splits one. Rewriting the losers to the
  winner was the alternative and is worse — front-matter rule 1 forbids
  overwriting a manual value, so the file on disk would never converge with the
  site it describes.
- **`bb dev` detects a dead watcher by probing it**, not by trusting
  registration, and falls back to polling automatically (§5.4). Appendix A
  item 5 records the measurements: the pod's default two-second event latency is
  its own debounce (anchored to the write, not to a poll clock), so clogem-press
  passes `:delay-ms 100` — §5.4's own debounce — and both the probe and the dev
  rebuild loop drop from ~2 s to ~0.1 s. The probe budget is one deadline shared
  by registration and delivery, sized for registration, which intermittently
  never returns.
- **D-3's duplicate-number rule covers sibling directories**, grouped on the
  number alone since directories are never language-suffixed.
