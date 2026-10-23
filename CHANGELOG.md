# Changelog

## Unreleased

Phase 4, Task B1 — colour modes, an accessible palette, the mode toggle and
icons (DESIGN.md §8, §11.3 items 2–6 and 8), then Task A — foundations
(§11.3 items 7, 11, 16, 18), then Task C — code highlighting (§11.3 item
10), then Task D — the dev loop (§11.3 item 12), then Task B2a — the card
and line page styles, focus rings and B1's follow-ups (the first half of
B2, §11.3 item 1), then Task F — analytics, verification and a static root
(§11.3 items 14, 15), then Task B2b — the navbar, the mobile drawer and the
page chrome (the second half of B2, §11.3 item 9, D-P4-9). The version
stays 0.2.0. A site that already set `:theme :page-style :line` now gets
that look: 0.2.0 and B1 accepted the value but drew card and line the same.

**Task B2b — navbar, mobile drawer and page chrome** (§11.3 item 9, D-P4-9):

### Added (B2b)

- **`--navbarHeight`** (3.6rem, vdoing's value) on `:root`. The navbar is
  exactly that tall, on one row, and the root's `scroll-padding-top`
  (`--navbarHeight` + 0.75rem) puts every jump below it: a link to a
  fragment, a TOC click, a fresh load of `…/#id`, `scrollIntoView()` and a
  focus move by Tab — headings or not (the home's `h1` and any `id`
  included). The TOC sticks 1rem below the navbar, and `js/toc.js` takes
  its offset from the navbar's measured bottom instead of a fixed 80 px.
- **Smooth scrolling**, always on, under `prefers-reduced-motion:
  no-preference`; under `reduce` scrolling stays instant.
- **A language dropdown** above 50rem: the switcher's list opens from a
  `<details class="clogem-langs__toggle">` whose summary is the
  `languages` icon, the page language's `:nav/language` and a chevron, as
  vdoing's. The list is the summary's next sibling, not its content, so
  wherever the summary is not rendered the list shows. Without JS the
  summary still opens and closes it.
- **An icon-only search trigger** at 64rem and below (CSS only): the
  trigger keeps its name, the page language's `:nav/search`.

### Changed (B2b)

- **The navbar's order** is now brand, nav, languages, search, mode: the
  languages moved from last place to just after the nav. On the desktop
  the brand sits at the start and everything else at the end; the gap is
  1rem.
- **A nav too long for one row** wraps its own items inside the navbar,
  which grows taller rather than spilling over the page; about two lines
  fit in `--navbarHeight`.
- **Smooth scrolling is always on.** vdoing's `smoothScroll` defaults to
  off; clogem-press has no key for it, and reduced motion turns it off.

### Fixed (B2b)

- **Anchors on the home's `h1`, and on targets that are not headings**,
  no longer land under the navbar: only `.clogem-content` h2–h6 had a
  `scroll-margin-top` (4rem), now replaced by the root's scroll padding (the
  two would add up). At 1280 px a heading used to land 5–6 px under the
  navbar, and a Shift+Tab stop (a copy button) wholly under it.

### Tests (B2b)

- `test/browser/layout.test.mjs` (new): the navbar's height and one row at
  1024–1440 px and at every width from 801 to 1600, anchors after a fresh
  load and a TOC click, a `<p>` target, the TOC's offset under
  `--navbarHeight: 6rem`, 30 Shift+Tab stops at 1280, smooth scrolling and
  reduced motion; the language dropdown by keyboard, pointer and without
  JS; the icon-only trigger at 801–1024; a ten-item nav at 1024.
- `bb test`: `clogem.layout-test` (new) pins the markup and the CSS;
  `theme_modes_test` checks that `smooth` appears only under
  `(prefers-reduced-motion: no-preference)`. The contrast parser
  (`test/clogem/css_palette.clj`) learnt a leading `not` and
  `(scripting: …)`, through `:scripting?` (default true; `initial-only` is
  false, as in Chromium).

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
- **`bb.edn` requires babashka 1.13.219 or later** (`:min-bb-version`; it
  said 1.13.0, which no one tested). CI runs the tests and the browser
  tests under 1.13.219 and the latest release (P4-D.4).
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
  named its absolute path (reachable with `--host 0.0.0.0`). A file under
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
