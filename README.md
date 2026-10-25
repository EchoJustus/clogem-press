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
> comments with one thread per article across its languages. Phase 4, in
> progress, adds colour modes and syntax highlighting so far; the rest of the
> theme follows — see [the implementation plan](DESIGN.md#8-implementation-plan).

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
| `bb build` | Render the site to the output directory (`dist/` by default; `--out` or `:build :out`). Writes missing front matter unless `--no-write`. Renders every page in memory first (in parallel; `CLOGEM_JOBS=1` for one thread) and writes only when all of it succeeded, file by file and atomically (temp file and rename; in place when the OS refuses the temp file or the rename), skipping files whose bytes are unchanged — a page that fails to render, or a site asset that cannot be read, leaves `dist/` and `permalinks.edn` as they were (an I/O error while writing, such as a full disk, or a Pagefind failure can still leave some files updated). Runs Pagefind last under `:search {:provider :pagefind}`; `--no-search` builds without search (no index, no search UI). Highlights code with Chroma unless `--no-highlight` (see Theme). Removes `.html` files in the output directory that the build did not write, so the output directory must be a directory of its own: it may not be, hold or sit inside `content/`, `assets/`, `i18n/` or `overrides/`, be or sit inside a `.git`, nor be the site directory or one of its ancestors; a `:build :out` written in `site.edn` must stay inside the site directory, while the default `dist` and `--out` may point elsewhere, through a symbolic link too (`dist -> /var/www/site`, `--out ../dist`). A build writes into any existing directory that passes those rules — one holding only `CNAME`, `.nojekyll` or a deploy worktree's `.git` included — and marks it with `.clogem-output`; it removes stale `.html` only in a directory a previous build marked, so the first build into a directory sweeps nothing. Copies the static directory, when `:content :static-dir` names one, to the output directory's root (see [Static files](#static-files)). |
| `bb dev` | Build, serve on 127.0.0.1:1888 (printed as `http://localhost:1888/`), rebuild on change, push a reload to the browser. See [Working locally](#working-locally). |
| `bb serve` | Serve an already-built directory at the site's `:base`, no watching: `--dir`, else `--out`, else `:build :out`, else `dist/`. `--host`, `--port`, `--base`. |
| `bb doctor` | Report content problems without building. Exits non-zero on errors. |
| `bb fm-fix` | Front-matter normalization only — what CI runs before the build. `--dry-run` to preview. |
| `bb clean` | Remove the output directory — only one a build marked as its own (`.clogem-output`), and never one holding a `.git` (a deploy worktree): both are refused, saying how to clean them. A symbolic-link `dist` is removed as a link; its target stays. |
| `bb fetch-tool` | Fetch, sha256-verify and cache a pinned binary — Pagefind by default, `--tool chroma` or `--tool fswatcher` — and print its path. |
| `bb test` | Run the test suite. The browser tests over a built site are separate: `test/browser/README.md`. |

Every task takes `--help`.

## Working locally

```bash
cd my-site
bb --config ../clogem-press/bb.edn dev            # http://localhost:1888/
```

`bb dev` builds, serves the output directory (`dist/` by default) and rebuilds on every change to `content/`,
`assets/`, `i18n/`, `overrides/`, the static directory when `:content
:static-dir` names one, `site.edn` and the generator's own theme
resources — including a directory created while it runs (a new
`content/07.Topic/` and the pages in it, an `overrides/` the site did not
have, a tree copied in with `cp -r`), a watched directory deleted and made
again, and a `site.edn` saved by an editor that replaces the file, or that is
a symbolic link to one elsewhere. Saves are collected until 100 ms pass
quietly, so a burst of writes is one rebuild, and editor temp files (`.#x`,
`x~`, `#x#`, `4913`, `*.swp`, `*.tmp`) are ignored; so are dev's own writes — the
front matter and `permalinks.edn` a build fills in never trigger another. A rebuild of the demo takes about 0.3 s; the page reloads about 0.6 s
after a save, and a stylesheet-only change swaps the CSS in place. Search is
re-indexed in the background after the reload; a page that loaded the previous
index keeps searching it until its next reload. A `site.edn` edit that changes
`:site :base` or `:build :out` is served at once. Ctrl-C waits for a rebuild
in progress (up to 10 s), stops a running index and removes its working
directories before it prints `stopped`.

It listens on 127.0.0.1 but prints `http://localhost:1888/`: an embed that
allow-lists origins — giscus's `giscus.json`, with `"originsRegex":
["http://localhost:[0-9]+"]` — accepts `localhost` and refuses `127.0.0.1`, so
open the URL as printed (with `--host 0.0.0.0` too, which also says it listens
on every interface). On loopback — however `--host` spells it — it answers
only requests addressed to `localhost`, `127.0.0.1`, `[::1]` or the address
`--host` gave: any other `Host` is a 403, so a web page
cannot reach it through a DNS-rebinding name of its own; with any other
`--host` there is no such check. A port in use or out of range, or a `--host`
that is not this machine's, is one error saying which; `--port` or `--host
127.0.0.1`. `--port 0` takes any free port and prints it.

When a rebuild fails, the terminal says why and the open page shows the error
in an overlay over the last good build; saving a fix clears it. Escape hides
it.

| Flag | |
|---|---|
| `--port 1888`, `--host 127.0.0.1` | Where to listen. Both servers listen on loopback only; `--host 0.0.0.0` to reach it from another device. |
| `--base /project/` | The site's base path (default: `:site :base`). The output directory (`dist/` by default) is served there, and `/` redirects to it, so a project site's links work locally. `bb serve --base` too. |
| `--poll`, `--interval 500` | Watch by polling instead of the fswatcher pod. Not usually needed: dev proves the pod delivers events at startup and polls on its own when it does not (`--probe-ms 3000` is that check's budget). Use it to copy a large tree in slowly (below). |
| `--no-search` | Build without search (no index, no search UI). |
| `--no-highlight` | Render code without syntax highlighting (no Chroma). Without it, a Chroma that cannot be fetched or run is warned about once and code shows plain; `build` fails instead. |
| `--no-write` | Never write front matter or `permalinks.edn` back to the source tree (dev writes them by default, as `build` does). |
| `--reload-code` | When working on the generator: also reload the theme's `.clj` code on change. |

**Many folders copied in slowly.** With the pod, every folder that appears
after its parent is already watched takes an inotify instance of its own
(fswatcher makes one per watch), and Linux allows 128 per user by default: a
`cp -r` of 40 folders that is slow enough for dev to see each parent first
takes up to 40 more. At the limit dev says so, polls for the rest of the
session and rebuilds once. For a large tree copied in slowly, run `bb dev
--poll`, or copy it elsewhere and move it in with one `mv`.

The fswatcher pod is fetched on first use like Pagefind — pinned, sha256-verified
and cached (`bb fetch-tool --tool fswatcher`); offline, or on a platform it has
no build for (Windows on ARM), dev polls. Ctrl-C stops it, its background
index included, and prints `stopped`.

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
  exists, else the primary with a fallback marker, and the `titleTag:`
  badge (原创, 转载, …) of the version it shows.
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

## Static files

Files that must sit at the site's root exactly as they are — `CNAME`,
`favicon.ico`, a search console's verification file
(`google0123456789abcdef.html`, `BingSiteAuth.xml`), an IndexNow key file
(`<key>.txt`), `.nojekyll`, `.well-known/security.txt` — go in a static
directory, which a site switches on in `site.edn`:

```clojure
:content {:static-dir "public"}   ; off unless set; "public" is the convention
```

Every file under it is copied byte for byte to the same path below the output
directory's root — `public/.well-known/security.txt` becomes
`dist/.well-known/security.txt` — dotfiles included; a `.git` inside it is
never copied, and neither are editor and OS leftovers: the names `bb dev`
ignores (`.CNAME.swp` and vim's other swap files, `CNAME~`, `.#CNAME`,
`#CNAME#`, `*.tmp`, `4913`, the build's own `.clogem-tmp-…` files) and `.DS_Store`, `Thumbs.db` and `desktop.ini`, skipped
without a word. A linked file is copied (its target's bytes) only when the
target is inside the site; a link to a file outside it, into a `.git`
(`public/gitcfg -> ../.git/config`), to a directory, or
to nothing is skipped with a warning naming it. The same rules hold for
`assets/`, leftovers included. A static directory that is itself a link (`public ->
web/static`) is copied from its target when that is inside the site, as is
a linked `assets/`; a linked `assets/` whose target is outside the site
warns and copies nothing. `--base` changes
nothing on disk: a project site's `dist/` is served at its base, so
`public/CNAME` is `/my-project/CNAME` there. Files are written only when
their bytes change, and `bb dev` rebuilds when one is added or edited.

It is a warning, and nothing is copied, when the static directory is not a
relative path inside the site, sits inside a `.git` (the site's own, or a
`.git` in its path), or is, holds or sits inside `content/`, `assets/`,
`i18n/`, `overrides/` or the output directory (`:build :out` or `--out`).
It is an error, before anything is written and
in `bb doctor`, when a static file would land where the build writes: at
the same path as a page, `feed.xml`, `sitemap.xml`, `robots.txt` (the
generated one, or `assets/robots.txt`, which already replaces it) or a site
asset (a root `404.html` is the exception: it replaces the generated 404
page, below); inside `clogem/`, `assets/` or `pagefind/`; on `.clogem-output`, a
`.clogem-tmp-…` name or a top-level `.pagefind-…` name; or where a page's
directory goes (a file `public/categories`). Each error says how to fix
it.

**Upgrading a site that already set `:content :static-dir`.** 0.2.0
accepted the key, warned that it was planned, and copied nothing; now the
directory is copied, so a file in it that collides fails the build where
0.2.0 built. Setting the key is the opt-in, and a collision stays an error:
move a file such as `public/assets/logo.png` to `assets/logo.png`, and
ship your own `robots.txt` as `assets/robots.txt` (delete
`public/robots.txt`, or merge it into the one already in `assets/`).

A static `.html` file deleted from the static directory disappears from the
output at the next build, as any `.html` the build no longer writes does
(in an output directory a previous build marked). Any other file deleted
from it — `CNAME`, `BingSiteAuth.xml` — stays in the output until `bb
clean`. Prefer the static directory to placing files in `dist/` by hand:
those survive a rebuild only when they are not `.html`, and are lost to
`bb clean`.

**The 404 page.** Every build writes `dist/404.html` — at the output
root whatever `--base`, which is where GitHub Pages, Netlify, Cloudflare
Pages and `bb serve` look for it. It is in the site's default language,
with the navbar and footer, says that there is no page at this address,
and links every language's home, each in that language's words; every link
carries the base, since a host serves the page at whatever address was
missed. It is `noindex`, not in `sitemap.xml`, and has no canonical,
hreflang or `og:locale`. A root `404.html` in the static directory
replaces it. `bb dev` and `bb serve` answer a missing page under the base
with it, status 404 (the plain stub outside the base, or with no
`404.html`).

`CNAME` matters to a GitHub Pages branch deploy and to other hosts.
GitHub's Actions artifact flow (`actions/deploy-pages`, what the publish
workflow uses) ignores it and takes the custom domain from the repository's
Pages settings. An IndexNow key file is just a static `<key>.txt`; nothing
is generated, and pushing URLs to IndexNow is planned for Phase 5.

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

**Page styles.** `:theme :page-style` is vdoing's `pageStyle`. `:card` (the
default) draws the main column of every page, the sidebar, and the home page's
right-bar sections and post cards as cards — the card colour, a small radius
and a shadow — on a page of a contrasting colour: darker in light and reading
mode, lighter in dark. A home's post cards sit on the page, as vdoing's do, not
in a card: their column is no box, and the home's own text above them is the
card. `:line` draws the same boxes flat, with a 1px border and no shadow, on a
page of the card's own colour. Print draws no box in either style — no shadow,
border, radius or padding — but keeps a highlighted code line's accent bar.
Everything focusable draws one keyboard focus ring: 2px, in the accent (in the
code colour inside a code block), at 3:1 or better on what is behind it.

**The navbar** is one row, `--navbarHeight` (3.6rem) tall: the brand at
the start; the nav, the languages, search and the mode button at the end.
Above 50rem the languages are a dropdown, as vdoing's; it and the nav's
dropdowns close on Escape, a click outside or when focus leaves them. At
64rem and below the search trigger is its icon alone, still named in the
page's language.
A nav too long for the row wraps its own items onto a second line inside
the navbar, which grows taller rather than spill over the page, and keeps
the languages, search and mode on the row's end. With JavaScript the jump
offset below follows the navbar's real height (`js/nav.js` measures it);
without, it assumes one row, so keep the top level short and put the rest
in a dropdown (`:items`). Every jump to a fragment — a link, the TOC, a
fresh load of `…/#id` — and every focus move by Tab lands 0.75rem below the
navbar (a `scroll-margin-top` on every element outside the navbar, so an
`<a name>` target and `scrollIntoView()` too; with JavaScript the navbar's
own controls never scroll the page), headings or not. Scrolling is smooth
unless the reader's system asks for reduced motion, except the browser's
own jump to a fresh load's `#id`, which `js/nav.js` keeps instant (without
JavaScript it animates too); until the page has loaded, history back and
forward are instant as well, while a click is smooth. Space and Page Down
step by the browser's usual 87.5% of the window, so in a window under about
460 px tall the line just above the old fold passes under the sticky
navbar, as before B2b. There is no key for smooth scrolling
(vdoing's `smoothScroll`, off by default there, is known and ignored: a
site that sets it is told to remove it).

**Logo and repository link.** `:theme :logo` (vdoing's `logo`) puts an
image, 2.2rem tall, before the site title in the navbar: a site path —
`"/assets/logo.svg"` for `assets/logo.svg` (a path the build does not
write is a warning; the image is linked all the same) — or an `https://`
URL. It is decorative (`alt=""`): the title stays the link's name, and at
64rem and below, where vdoing hides it too, the title is visually hidden
and only the logo shows. `:theme :repo` (vdoing's `repo`: `"owner/name"`
for GitHub, or an `https://` URL) adds a link at the end of the nav,
labelled `:theme :repo-label` (a string or a map by language), else
*GitHub*, *GitLab* or *Bitbucket* by its host, else *Source*
(`:nav/source`), with an external-link icon. It opens in the same tab, like
every nav link (vdoing opens a new one). Between 50 and 64rem the label is
visually hidden, leaving the icon; at 50rem and below the link is in the
drawer with the rest of the nav, so a one-language site with no `:nav`
gets the drawer when it has `:repo`.

**Mobile layout.** At 50rem (800 px) and below the navbar is one sticky
row: a menu button, the brand (cut short with an ellipsis), the search
icon and the mode button. The menu button opens a drawer below the
navbar with the nav, every language and, on an article, the sidebar,
which `js/nav.js` moves there (and back, if the window widens). Escape, a
click outside or following a link closes it. Without JavaScript — or in
a browser without `@media (scripting)` — the navbar is the full wrapping
one, every link shown, and it does not stick; the sidebar follows the
article. If `js/nav.js` fails to load, the menu button still opens the
drawer (it is a `<details>`), and the sidebar stays below the article.
One limitation: without JavaScript, Tab reaches the sidebar before the
article, which is drawn above it, because the sidebar comes first in the
page.

**Columns.** Above 50rem an article with a sidebar is sidebar | article;
from 80rem (1280 px) its TOC joins them on the right, in a shell 18rem
wider than `--maxWidth`, as vdoing's TOC shows only from 1280 px. A page
with a TOC and no sidebar shows it above 64rem.
`:theme :right-menu-bar false` (vdoing's `rightMenuBar`) drops that TOC
from every page; headings keep their ids and `#` anchors, and from 80rem
the article takes the TOC's room, as vdoing's does. vdoing nests the
headings under the sidebar's link instead; clogem-press does not. The home
page's right bar is not the TOC: its front matter's `hideRightBar` decides.

**Sidebar collapse.** Above 50rem an article's sidebar has a collapse
button at the top of the article (a chevron); it hides the sidebar and
gives the article its column, and the same button brings it back. The
reader's choice holds while they stay on the page, across resizes, but it
is **not kept across pages**: each page starts as `:theme
:sidebar-collapsed` says (default `false`; vdoing's `sidebarOpen`,
inverted). The button needs JavaScript; without it the sidebar always
shows. In the mobile drawer the sidebar always shows.

**Page buttons.** On by default, as in vdoing (`pageButton`): an article
or catalogue page with a previous or next page repeats those two links as
chevrons fixed at the window's left and right edges, half-opaque until
hovered. They show only from 84rem (1344 px), where the window is wide
enough for them to cover nothing, and print never shows them. They are
duplicates for the mouse: screen readers and the keyboard use the
previous/next links at the end of the article. `:theme :page-button false`
omits them.

**Background patterns.** `:theme :content-bg-style` (vdoing's
`contentBgStyle`) draws a faint pattern on the column of articles and
catalogue pages: `1` a grid, `2` horizontal lines, `3` vertical lines, `4`
and `5` the two diagonals, `6` dots; the default, `nil`, draws none. The
lines are `--linesColor` (5% opaque, lighter in dark mode, 3% in read
mode, where pattern 1's crossings draw it twice), which you can
set in `overrides/custom.css`; `bb test` checks that text keeps 4.5:1 on
them. Print draws no pattern.

**Back to top.** On by default, as in vdoing: once the page is scrolled
more than 100 px down, a round arrow button shows at the bottom right
(2rem from the right and 2.5rem from the bottom; 1rem and 1.5rem at 50rem
and below). It scrolls smoothly to the top — instantly under reduced
motion — and hides at once; the next Tab starts again at the navbar. It
sits below the navbar, so the open mobile drawer covers it. It needs
JavaScript; print never shows it. `:theme :back-to-top false` omits it.

**Blog identity** (vdoing's `blogger`, `social`, `footer`, `bannerBg`,
`bodyBgImg` and `titleBadge`; every key is in `config.example.edn`). A bad
value warns and is dropped or set to its default; none fails a build. An
image is an `https:` URL, a `data:image/…` URI or a site path
(`"assets/me.png"` is `/assets/me.png`; the base is prefixed); an `http:`
image warns (mixed content) and is dropped. Names, slogans and link titles
are text — a string or a per-language map — and are escaped.

- **The blogger card** (`:theme :blogger {:avatar :name :slogan}`, a
  `:name` or an `:avatar` at least) heads the home's right bar, before
  *Recently updated*, categories and tags. At 50rem and below, with
  JavaScript, a copy opens the mobile drawer instead, and opening the drawer
  focuses its first link. The avatar is decorative (`alt=""`).
- **Social links** (`:theme :social {:icons [{:icon :title :link}]}`) are in
  the card and the footer, `rel="me"`, in the same tab. `:icon` is a
  vendored brand (`:github`, `:mastodon`, `:x` — X, not the close icon —
  and the rest listed in `config.example.edn`) or a UI icon (`:mail`,
  `:rss`, …); only the brands a site uses are built into its sprite. An
  unknown icon warns and shows the link icon. `:svg "assets/me.svg"`
  shows your own image instead (an `<img>`, so not recoloured). vdoing's
  iconfont (`iconfontCssFile`, `at.alicdn.com`) is not used.
- **The footer** is the social links, `© 2019–2026 …` and *Powered by
  clogem-press*. `:theme :footer {:create-year 2019 :copyright-info "…"}`;
  the end year is `:end-year`, else the year of the newest dated article.
  `:copyright-info` is trusted HTML, per language too (`""` hides it in that
  language), and `{{base}}` in it is the site's base. `:powered-by false`
  drops the credit. The version is no longer in the footer; it is in
  `<meta name="generator">`.
- **The home banner** shows on the home's first page when its front matter
  has `heroImage` (with `heroAlt`), `heroText`, `tagline`, `actionText` with
  `actionLink`, or `bannerBg`. `heroText` is the page's `<h1>` when the home
  has no `# heading` of its own. `bannerBg` (else `:theme :banner-bg`) is
  `auto` (a grid on dark grey, white text), `none` (the card's colours), an
  image, or `background: …` — your own CSS, used as is. With a background
  image, auto and none are transparent.
- **The background image** (`:theme :body-bg-img`, one or several) is fixed
  behind the page at `:body-bg-img-opacity` (0.5, in tenths). Several
  rotate every `:body-bg-img-interval` seconds (15) — not under reduced
  motion, nor while the tab is hidden. It forces `:page-style :card`.
- **The title badge** — a small icon before an article's and the archive's
  title — is on by default (`:theme :title-badge false` turns it off). It is
  one of book-open, pencil and hash, or of `:title-badge-icons` (images or UI
  icons), chosen from the article's permalink, so each article shows the
  same one in every language and every build.

The opt-in parts — the card, the links and the background image — are in
`css/identity.css`, which ships and is linked only while a site uses them.

**Edit link.** `:content :edit-link` (vdoing's `editLinks`, `docsRepo`,
`docsBranch` and `docsDir`) puts *Edit this page* (`:page/edit`, with a
pencil) under every article and catalogue page — never under a home or an
index page — and opens the page's own source file in the repository's web
editor, in the same tab; a translation opens its own `.<lang>.md`:

```clojure
:content {:edit-link {:repo "owner/name"      ; or an https:// URL; absent: :theme :repo
                      :branch "main"          ; the default
                      :dir "docs"}}           ; where the site sits in the repository, default ""
```

github.com, and any host but two, get `<repo>/edit/<branch>/<path>`;
gitlab.com gets `<repo>/-/edit/<branch>/<path>`, and bitbucket.org
`<repo>/src/<branch>/<path>?mode=edit…`, as vdoing's do. Gitee and
self-hosted forges get the GitHub form. Front matter `editLink: false`
hides the link on that page. A bad `:repo`, no repository at all, or an
`:edit-link` that is not a map is a warning that turns the link off; a bad
`:branch` or `:dir` warns and takes the default. Print never shows it.

**Last updated.** `:theme :last-updated true` (vdoing's `lastUpdated`; off
by default) adds *Last updated 2026-10-04* (`:page/last-updated`, with a
clock) beside the edit link: the date of the last commit that touched the
page's own source file, the author's calendar date, `yyyy-mm-dd` in every
language. The build runs **one** `git log` over the content directory;
`doctor` never runs git. A file git has no commit for (new, untracked)
shows no date, and a site outside git shows none, silently. **A shallow
clone dates every file to its newest commit**, so it is refused with one
warning and no dates: `actions/checkout` is shallow by default, so a
workflow that builds with this on must check out with `fetch-depth: 0`
(the live site's `publish.yml` already does). A commit the build itself
makes before building (an `fm-fix` step, say) dates the files it touched.
Print keeps the date.

**Update bar.** On by default, as in vdoing (`updateBar`): every article
ends — after the comments — with *Recently updated*, the three newest
other articles by front-matter `date` (an untranslated one in its
primary language, flagged like any index row), and *More →*. A catalogue
or `article: false` page has none, and neither does a site with only one
article. `:theme :update-bar {:show-to-article false}` drops it.
`:more-article` (a site path such as `"/tags/"`, which gets the page's
language prefix, or an `https://` URL) is where *More →* goes, here and
on the home page — its right bar's recent list, and the `postList: simple`
list, which now ends with one too; by default it is the archives in the
page's language, and with the archives switched off there is none. Print
never shows the bar.

**Page classes.** Front matter `pageClass` (vdoing's) adds class names to
that page's `<body>`, after `lang-<lang> page-<kind>`, for your own CSS:
`pageClass: wide` or `pageClass: [wide, no-toc]`. An article or catalogue
page reads its own file's; a home reads its `index.md` (or
`index.<lang>.md`); an index page its `@pages/` file, on every page of
that index. Each name must be a plain class name — a letter or `_`, then
letters, digits, `-` or `_` — and any other is dropped with a warning:
the names go into the page as they are, so treat them as trusted.

The mode and page-style classes are on `<html>`, unqualified:
`.theme-mode-dark`, `.theme-style-card`. (0.2.0 put them on `<body>`; CSS
written as `body.theme-mode-dark` must drop `body`.)

**Mode class or `[data-mode]`?** With JavaScript, `theme-mode-auto` is
replaced by the *resolved* `theme-mode-light` or `theme-mode-dark`, so
`.theme-mode-dark` covers both *Dark* and *Follow system* on a dark OS — style
the palette with it. To style the reader's *choice*, use
`[data-mode="auto"]`, `"light"`, `"dark"` or `"read"` on `<html>`.
`.theme-mode-auto` exists only without JavaScript.

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

**Code highlighting is on by default.** Fenced code is highlighted at build
time by [Chroma](https://github.com/alecthomas/chroma) (MIT), which knows 297
languages by name, alias or file extension (` ```clojure `, ` ```clj `,
` ```edn `). **The first build of a site with code downloads Chroma** — a
3 MB archive (an 8.4 MB binary unpacked), once, checked against a pinned
sha256 and cached outside the site
(`~/.cache/clogem-press/tools`, like Pagefind); `CLOGEM_CHROMA` names one
you installed yourself. If that download or a Chroma run fails, `build`
fails with exit 1 (`bb dev` only warns, and shows the code plain). To turn
highlighting off, build with `--no-highlight` or set
`:highlight {:provider :none}`; code is then plain `<pre><code>`, as in 0.2.0.

````
```js{1,3-5}              highlight lines 1, 3, 4 and 5 (also ```js {2})
```js:no-line-numbers     no line numbers for this block (:line-numbers sets the default)
````

Line numbers are drawn by CSS, so they are never copied and never indexed by
search. Each block shows its language and, where the browser offers the
clipboard API, a copy button (`:highlight :copy-button false` removes it).
The colours come from Chroma's `github` and `github-dark` styles
(`:highlight :style` / `:dark-style`) as `dist/clogem/css/highlight.css`;
reading mode uses the dark one, because its code block is dark. An unknown
language is shown as plain text, with one warning per build.

**Search-console verification.** The meta-tag method of Google Search
Console and Bing Webmaster Tools, on every page (`bb dev` included; a
redirect stub, which is `noindex`, has none):

```clojure
:seo {:verification {:google "abc123…"            ; <meta name="google-site-verification">
                     :bing   ["1A2B…" "3C4D…"]}}   ; <meta name="msvalidate.01">, one per value
```

Each value is the `content` of the tag the console asks for: a string, or a
vector of them for several owners. Any other value is a warning and is
ignored. The file method works too: put the console's file in the
[static directory](#static-files).

**Analytics** is off by default (`:analytics {:provider :none}`). One
provider can be chosen; its script is loaded only because the site owner
chose it, and nothing of it ships under `:none`:

```clojure
:analytics {:provider :ga4 :id "G-XXXXXXXXXX"
            :consent-default :denied}                    ; optional, GA4 only
:analytics {:provider :plausible                         ; Plausible's new script:
            :src "https://plausible.io/js/pa-XXXX.js"    ;   the site's own, from its settings
            :init {:outbound-links true :file-downloads true}} ; plausible.init options, optional
:analytics {:provider :plausible :domain "example.com"}  ; the legacy script (data-domain)
:analytics {:provider :umami
            :website-id "94db1cb1-74f4-4a40-ad6c-962362670409"}
```

`:src` overrides the provider's script URL — a self-hosted Umami, a proxied
Plausible script — and must be `https://`. For Plausible, `:domain` selects
the legacy script (default `https://plausible.io/js/script.js`), and without
it `:src` is required; `:init` takes the options of `plausible.init`
kebab-cased (`:hash-based-routing`, `:file-downloads {:file-extensions
["pdf"]}`, `:custom-properties {…}`, whose own keys are passed as written).
Umami's `:src` defaults to `https://cloud.umami.is/script.js`. A bad value
is a warning that switches analytics off, never an error.

The page loads `dist/clogem/js/analytics.js` (ours), the last thing in
`<head>`, which loads the provider's script — and does nothing at all:

- in `bb dev`, which never emits it;
- on any host but `:site :url`'s, so `bb serve`, localhost, a fork or a
  preview deploy count nothing (analytics needs `:site :url`);
- when the reader's browser sends Do Not Track or Global Privacy Control,
  unless `:respect-dnt false`. Umami is also told `data-do-not-track`.

GA4 sets `_ga` cookies; Plausible and Umami are cookieless. With
`:consent-default :denied`, GA4 starts in consent mode with every storage
type denied, and the site's own consent UI grants it by calling
`gtag('consent', 'update', {analytics_storage: 'granted'})`. clogem-press
ships no consent banner.

**Personal data.** Whether analytics needs consent is the site owner's
question, not the generator's. Under Singapore's PDPA, the PDPC's Advisory
Guidelines on the PDPA for Selected Topics (revised May 2024) discuss
cookies at ¶7.7; say at ¶7.12 that an individual's failure to manage the
browser's settings does not mean consent to every website; and put the duty
at ¶7.13 on the organisation collecting the data, itself or through its
data intermediaries. This is not legal advice.

**Content-Security-Policy.** Every page carries exactly one inline script,
the colour-mode script in `<head>`, and it is byte-identical on every page,
so one hash covers the site:

```
script-src 'self' 'sha256-aykGrfu05czJ6oIj+Xn+Qrjxa7JG8hF3RGl0W0liGmw='
```

`:i18n {:preference :redirect}` adds a second inline script, giscus needs
`https://giscus.app` in `script-src`, `frame-src` and `style-src`, and
Pagefind needs `worker-src 'self'` and `'wasm-unsafe-eval'` (DESIGN.md §6.8,
§11.2 items 42 and 49). The copy button's strings are a
`<script type="application/json">` data block, which CSP does not govern;
`js/code.js` and `js/nav.js` (the drawer) are same-origin files. A policy without the hash still gets the
reader's mode: `js/mode.js` (same-origin) applies it, one frame late.
The blog identity adds no inline script: `js/bodybg.js`, which rotates
several background images, is same-origin. A `bannerBg: "background: …"`
banner is a `style` attribute, which a policy that restricts styles must
allow (`style-src-attr 'unsafe-inline'`), and a `data:` image needs
`img-src data:`; an `https:` image elsewhere needs its host in `img-src`.

Analytics adds no inline script: `'self'` covers `js/analytics.js`, and the
provider's own hosts are needed:

| Provider | Add |
|---|---|
| GA4 (no Ads features) | `script-src https://www.googletagmanager.com` (Google lists it as `script-src-elem`); `img-src https://www.googletagmanager.com https://*.google-analytics.com`; `connect-src https://www.googletagmanager.com https://*.google-analytics.com https://*.google.com` |
| Plausible | its host (`https://plausible.io`, or the `:src` host) in `script-src` and `connect-src` |
| Umami Cloud | `script-src https://cloud.umami.is`; `connect-src https://gateway.umami.is` |

## Development

```bash
bb test                                             # 800 tests / ~52,100 assertions
cd examples/demo-site && bb --config ../../bb.edn build
```

## License

EPL-2.0. See [LICENSE](LICENSE).
