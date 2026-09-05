# Clojure Markdown Libraries for a Babashka-Compatible SSG (verified Aug 2026)

**Method note:** Everything below marked "verified" was tested empirically in this session under **babashka v1.13.219** (released 2026-07-27, latest as of Aug 2026), installed locally, not taken from memory. Version/maintenance data cross-checked against Clojars API and GitHub.

## Summary table

| Library | Latest version | Runs under bb today? | AST? | Front matter | Maintenance |
|---|---|---|---|---|---|
| **nextjournal/markdown** | `io.github.nextjournal/markdown 0.7.225` | **Yes — built into bb** (since bb 1.12.201, 2025-06-12; bb 1.13.219 pins exactly 0.7.225) | Yes, data-first, walkable, incl. `:toc` | No (strip + clj-yaml yourself, ~6 lines) | Active (pushed 2026-07-29) |
| **markdown-clj (yogthos)** | `markdown-clj/markdown-clj 1.12.9` | **Yes — as a regular Clojars dep** (verified: downloads & runs) | No — string in, HTML string out | **Yes**, full YAML via bundled clj-yaml dep | Active (pushed 2026-08-05); 575★, 3.7M Clojars downloads |
| **cybermonday** | `com.kiranshila/cybermonday 0.6.215` | **No** — fails: `Unable to resolve classname: com.vladsch.flexmark.util.ast.Node` (verified) | Yes (hiccup-shaped AST) | Yes (`parse-md` returns `:frontmatter`) | **Dormant** — last substantive commit Aug 2024, dependabot-only since; repo owner renamed kiranshila→activexray |
| **markdown2clj (nilenso)** | `markdown2clj 0.1.3` (~2017, 475 downloads) | **No** — fails: `Unable to resolve classname: org.commonmark.parser.Parser` (verified) | Yes (crude) | No | Abandoned |
| **clarktown (askonomm)** | zero-dep pure Clojure | Would load (pure Clojure), untested | Partial | No | **Red flag**: GitHub repo "frozen in time", moved to author's personal git host |
| **flexmark-java (interop)** | `0.64.8` | **No** — JVM only, Java classes not in bb's SCI | Yes (Java AST) | Via YamlFrontMatter extension | Semi-maintained (pushed Apr 2025, release Feb 2024, 178 open issues); targets CommonMark **0.28** (old spec) |

## nextjournal/markdown — detailed (the standout)

- **Coordinates/version:** `io.github.nextjournal/markdown 0.7.225` on Clojars (413K downloads, ISC license). No GitHub releases; ships via Clojars only. API declared stable (no longer alpha) at 0.7.181. 0.7.225 added `:disable-footnotes true`. (bb's own CHANGELOG says "bump to 0.7.255" but that version does not exist on Clojars and bb master `deps.edn` pins `{:mvn/version "0.7.225"}` — a typo in bb's changelog.)
- **Architecture — NOT pure Clojure:** wraps **commonmark-java** on the JVM (since 0.6.157, which swapped out GraalJS/markdown-it — a ~10x speedup) and markdown-it on ClojureScript. It runs in bb because babashka **compiles it and commonmark-java into the native image as a built-in** (bb issue #1825; +1MB binary, 69→70MB), *not* because it's pure Clojure. Consequence: `require`-able out of the box in bb ≥1.12.201 with **zero dependencies**; but you cannot use a *different* version of it in bb (built-in wins) and other commonmark-java wrappers still fail in bb.
- **bb API surface (verified via `ns-publics` in bb 1.13.219):** `parse`, `parse*`, `->hiccup ([markdown] [hiccup-renderers markdown])`, `default-hiccup-renderers`, `into-hiccup`, `node->text`, `toc->hiccup ([ctx toc])`, `table-alignment`, `empty-doc`. Note: in the bundled 0.7.x the old `nextjournal.markdown.transform` ns is **gone** (consolidated into `nextjournal.markdown`); `nextjournal.markdown.utils` exists with `empty-doc`, `hashtag-tokenizer`, `internal-link-tokenizer`, `normalize-tokenizer`, `insert-sidenote-containers`, formula helpers.
- **Verified parse output:** `(md/parse s)` returns `{:type :doc :content [...] :toc {...} :footnotes [...] :title "..."}`.
  - **TOC is free:** `:toc` is a nested tree of `{:type :toc :heading-level n :content [...] :attrs {:id "sub-head"} :path [:content 1]}` — GitHub-style slugs (`"Sub *head*"` → `sub-head`) are auto-generated on every heading's `:attrs {:id ...}`, and `:path` gives the exact AST location (get-in-able). `:title` is auto-extracted from the first h1. `toc->hiccup` renders it.
  - **GFM verified:** tables (`:table/:table-head/:table-row/:table-header/:table-body/:table-data` + `table-alignment`), task lists (`:todo-list`/`:todo-item`), `~~strikethrough~~` → `[:s ...]`, bare-URL autolinks (`www.example.com`, `https://…` → `:link` nodes). Also LaTeX `$..$`/`$$..$$` formulas (disable with `:disable-inline-formulas`), footnotes (`:footnote-ref` + doc-level `:footnotes`).
  - **CommonMark correctness verified:** multi-paragraph list items parse correctly (one `li`, two `p`s).
- **Extensibility (all verified in bb):**
  - *Custom hiccup renderers:* `(md/->hiccup (assoc md/default-hiccup-renderers :strikethrough (fn [ctx node] (md/into-hiccup [:del.custom] ctx node))) s)` — a renderer map keyed by node `:type`; full control per node type. This is the hook for heading-anchor markup, custom containers, internal-link → href rewriting at render time.
  - *Custom text tokenizers* (parser-level extension): `(md/parse (update u/empty-doc :text-tokenizers conj {:regex #"\[\[([^\]]+)\]\]" :handler (fn [m] {:type :internal-link :text (m 1)})}) s)` — produced `{:type :internal-link :text "Some Page"}` nodes inline. A ready-made `u/internal-link-tokenizer` (wikilinks) and `u/hashtag-tokenizer` ship in utils. This is exactly what a vdoing-style knowledge base needs for `[[wiki links]]` and tag syntax.
- **Raw HTML caveat (verified):** HTML blocks/inline parse into `:html-block`/`:html-inline` nodes (added 0.7.181), but the **default** hiccup renderers render a red "Unknown type" error box for them in 0.7.225. Fix is trivial and verified: `(assoc renderers :html-block (fn [_ node] (hiccup2.core/raw (md/node->text node))))` — end-to-end output was correct: `<div class="warn"><p><strong>bold</strong></p></div>`. An SSG that supports embedded HTML/components **must** install this passthrough.
- **Front matter (verified):** NOT handled. `---\ntitle: Hi\n---` parses as `:ruler` + a setext h2 garbage heading. Must split front matter before parsing (see recommendation).

## markdown-clj (yogthos) — detailed

- **Version/maintenance:** 1.12.9 on Clojars; repo pushed 2026-08-05; EPL-1.0; the most-used Clojure md library (3.7M downloads).
- **bb compatibility (verified):** works as a plain `bb.edn` dependency (`{:deps {markdown-clj/markdown-clj {:mvn/version "1.12.9"}}}`); it is **not** a bb built-in (bb bundles nextjournal/markdown instead — chosen in bb issue #1825 explicitly *because of* markdown-clj's CommonMark-compliance problems).
- **Front matter (verified — its best feature):** `md-to-html-string-with-meta` parses real `---`-delimited YAML front matter into nested keywordized ordered maps (`{:metadata {:title "Hi there" :tags ("a" "b") :nested {:x 1}} :html "..."}`) — it depends on `clj-commons/clj-yaml 1.0.29` (in its pom). Also supports MultiMarkdown `Key: Value` metadata (values come back as vectors of strings).
- **Features (verified):** GFM tables yes; `~~strike~~` → `<del>` yes; footnotes via `:footnotes? true` yes; **task lists NO** (renders literal `[ ] task`); **bare-URL autolinks NO**; heading anchors via `:heading-anchors true` but slugs are URL-encoded and ugly: `# Hello World!` → `<h1 id="hello_world%21">` (not GitHub-style; you'd override via `:custom-transformers`/`:replacement-transformers`, which operate line-by-line on strings, not on a tree).
- **No AST:** string → HTML string. TOC extraction, link rewriting, component embedding all require regexing HTML or the source — a structural mismatch for this SSG.
- **CommonMark violations (verified, matching bb issue #1825):**
  - Multi-paragraph list item → `<ul><li>item one</li></ul><p>  continued para</p><ul><li>item two</li></ul>` (list split in two).
  - Link text spanning two lines → not parsed at all (documented line-by-line parser limitation: "tag content is not allowed to span multiple lines").
  - Raw HTML block wrapped in `<p>` with invalid nesting: `<p><div class="warn"></p>…<p></div></p>`.

## cybermonday, markdown2clj, clarktown, flexmark — why they're out

- **cybermonday** has the nicest data model on paper (markdown → hiccup-shaped AST `[:markdown/heading {:level 1} ...]`, front matter included via `:frontmatter`, `lower-fns` for custom node lowering, GFM tables/tasklists/strikethrough/footnotes) but it wraps **flexmark (Java)** on the JVM — verified failure under bb. Its JS side targets **nbb** (Node), not bb. Dormant since Aug 2024. Also flexmark requires Java 11+, and its inline-HTML handling is documented as "really rudimentary" (attrs containing `=` break it). Only viable if the SSG were JVM-Clojure, and even then maintenance is a concern.
- **markdown2clj** (nilenso): thin commonmark-java wrapper, v0.1.3 from ~2017, negligible adoption, verified bb failure. Not a candidate.
- **clarktown**: pure Clojure/zero-dep so it would likely load in bb, but the GitHub repo is explicitly "frozen in time", migrated to the author's personal git host — unacceptable supply-chain risk, and it lacks tables/AST depth anyway.
- **flexmark-java direct interop**: most complete feature set in the JVM world (pegdown/kramdown emulation, attributes extension `{#custom-id}`, YamlFrontMatterExtension, TOC extension), but JVM-only (kills the bb CLI), pinned to CommonMark **0.28** dialect, last release 0.64.8 (Feb 2024) with 178 open issues. Only relevant if we later add an optional JVM "power mode".

## Answers to the key questions

**(a) YAML front matter:** No AST-producing bb-compatible library parses it. Two working options, both verified under bb: (1) markdown-clj's `md-to-html-string-with-meta` (full YAML, but ties you to its weak renderer); (2) **recommended:** split it yourself and use **clj-yaml, which is a bb built-in** (`clj-yaml.core` requires with zero deps; returns keywordized nested ordered maps). Verified working splitter: `(re-matches #"(?s)\A---\r?\n(.*?)\r?\n---\r?\n(.*)" s)` → `yaml/parse-string` the middle, `md/parse` the rest.

**(b) Walkable AST for TOC / anchors / link rewriting / components:** **nextjournal/markdown, decisively.** TOC comes pre-built (`:toc` with nested levels + `:path` back-pointers), heading slugs/ids are pre-computed GitHub-style in `:attrs :id`, internal-link rewriting is doable either at parse time (custom `:text-tokenizers` — a wikilink tokenizer ships in `nextjournal.markdown.utils`) or at render time (custom renderer for `:link`), and custom components map onto custom node types + entries in the `->hiccup` renderer map. The AST is plain nested maps — `clojure.walk`/`tree-seq` friendly (verified). markdown-clj offers none of this; cybermonday offers it but not under bb.

**(c) GFM coverage:** nextjournal/markdown: tables, task lists, strikethrough, autolinks — all verified, plus footnotes and LaTeX. markdown-clj: tables, strikethrough, footnotes yes; task lists and autolinks **no**. cybermonday (JVM only): full GFM via flexmark extensions.

**(d) Runs under babashka today (Aug 2026):** nextjournal/markdown 0.7.225 (built-in, zero config), markdown-clj 1.12.9 (as a Clojars dep), clj-yaml (built-in), hiccup2 (built-in). **Not** runnable: cybermonday, markdown2clj, flexmark (all die on Java classname resolution — verified error messages above).

## Recommendation

**Use babashka's built-in `nextjournal.markdown` (0.7.225) as the sole parser**, with:
1. a ~6-line front-matter splitter + built-in `clj-yaml.core` for page metadata;
2. a renderer map based on `md/default-hiccup-renderers`, overriding at minimum `:html-block`/`:html-inline` with a `hiccup2.core/raw` passthrough (required for embedded HTML/components), plus `:heading` (to add vdoing-style anchor links using the already-present `:attrs :id`) and `:link` (internal-link rewriting to output paths);
3. `:text-tokenizers` (e.g. bundled `internal-link-tokenizer`) if wikilink/tag syntax is wanted;
4. built-in `hiccup2.core` for final HTML serialization.

This whole pipeline was executed end-to-end in stock bb v1.13.219 in this session with an **empty `bb.edn`** — zero dependencies to resolve at build time, which is ideal for a fast CI GitHub-Pages build. It also keeps a future JVM/ClojureScript escape hatch, since nextjournal/markdown is cross-platform with the same API. Sole notable risks: raw-HTML defaults need the passthrough override (trivial, verified), the parser dialect is pinned to whatever bb bundles (currently the latest release anyway; bb bumps it promptly), and no built-in syntax highlighting (do it post-hoc on `:code` nodes — separate concern). markdown-clj remains the fallback only if an HTML-string pipeline with zero AST needs were acceptable — its verified CommonMark violations (split lists, no multi-line inline elements, mangled raw HTML) make it unsuitable as the foundation of a docs/knowledge-base theme.

Local artifacts from verification (scripts runnable with the bb binary alongside them): `/tmp/claude-0/-home-user/4a670205-d376-5fec-8b1f-1c5c54b2eaf6/scratchpad/{test1,test3,test4,pipeline,edge2}.clj`, `/tmp/claude-0/-home-user/4a670205-d376-5fec-8b1f-1c5c54b2eaf6/scratchpad/mdclj/{t,edge}.clj` (markdown-clj), plus failing repros in `cyber/` and `md2clj/`.

## Sources
- https://github.com/yogthos/markdown-clj
- https://github.com/nextjournal/markdown
- https://raw.githubusercontent.com/nextjournal/markdown/main/CHANGELOG.md
- https://raw.githubusercontent.com/nextjournal/markdown/main/README.md
- https://github.com/kiranshila/cybermonday
- https://github.com/kiranshila/cybermonday/commits/master
- https://github.com/nilenso/markdown2clj
- https://github.com/askonomm/clarktown
- https://github.com/babashka/babashka/issues/1825
- https://raw.githubusercontent.com/babashka/babashka/master/CHANGELOG.md
- https://raw.githubusercontent.com/babashka/babashka/master/deps.edn
- https://clojars.org/api/artifacts/markdown-clj/markdown-clj
- https://clojars.org/api/artifacts/io.github.nextjournal/markdown
- https://clojars.org/api/artifacts/com.kiranshila/cybermonday
- https://clojars.org/api/artifacts/markdown2clj/markdown2clj
- https://repo.clojars.org/markdown-clj/markdown-clj/1.12.9/markdown-clj-1.12.9.pom
- https://github.com/vsch/flexmark-java
- https://github.com/nextjournal/markdown/releases