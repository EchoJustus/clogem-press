# Changelog

## Unreleased (Phase 3)

Phase 3 part A: SEO, feeds, sitemap and the doctor i18n checks. DESIGN.md
§11.2 items 30–39 record the decisions (D-P3-1 … D-P3-7). `version.edn` is
unchanged until the phase is released.

### Added

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
