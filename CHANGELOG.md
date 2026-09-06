# Changelog

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
  excerpts from `<!-- more -->` or the first paragraph; `/page/N/` pagination
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
- Version `0.1.0` (`src/clogem/version.edn`), tagged `v0.1.0`, so the content
  repo can pin `ref: v0.1.0` and declare `:generator {:min-version "0.1.0"}`.

### Tests

231 tests / 894 assertions (from 144 / 431): model indexes and tree, index
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
