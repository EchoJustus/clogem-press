# Internationalization for clogem-press: prior art, Pagefind multilingual, SEO, typography (verified August 2026)

Verification notes for DESIGN.md **v2** §6 (Internationalization). Scope: English as primary content
language, UI switchable between **en, zh-Hans, zh-Hant, ms, ta** (Singapore's four official
languages plus a Simplified/Traditional split). Articles may exist in one or more language versions;
when several exist the default shown follows **en > zh-Hans > zh-Hant > ms > ta**.

Everything below was checked against primary sources: the projects' own docs and, where the docs are
silent or ambiguous, their **source code**. The two most load-bearing findings (Pagefind's
segmentation trigger; giscus's mapping vocabulary) came from source, not documentation.

---

## 1. Prior art: how other generators model per-page translations

### Hugo — the closest model to what vdoing needs

From <https://gohugo.io/content-management/multilingual/> (fetched from `gohugoio/hugoDocs`),
verbatim:

> ### Translation by file name
> 1. `/content/about.en.md`
> 2. `/content/about.fr.md`
>
> The first file is assigned the English language and is linked to the second. […] Their language is
> assigned according to the language code added as a suffix to the file name.
>
> By having the same path and base file name, the content pieces are linked together as translated
> pages.

> **Note:** The language code in a file name must be lowercase. For example, use `about.en-us.md`
> instead of `about.en-US.md`.

> **Note:** If a file has no language code, it will be assigned the default language.

And the escape hatch that matters most for us:

> ### Bypassing default linking
> Any pages sharing the same `translationKey` set in front matter will be linked as translated pages
> regardless of basename or location.

with the worked example `about-us.en.md`, `om.nn.md`, `presentation/a-propos.fr.md` all carrying
`translationKey: about`.

Also relevant:

> Because paths and file names are used to handle linking, all translated pages will share the same
> URL (apart from the language subdirectory).

Hugo additionally supports translation by *content directory* (`contentDir` per language), and for
string translation: "If a string does not have a translation for the current language, Hugo will use
the value from the default language."

**Takeaways for clogem-press.** (i) A lowercase language suffix on the filename is a proven,
低-ceremony convention. (ii) *Implicit* linking by (path, basename) plus an *explicit* override key is
the right shape — and clogem-press already has a better explicit key than `translationKey`, namely
the `permalink`, which is simultaneously the URL and the comment-thread identity. (iii) Hugo forces
lowercase language codes in filenames; we cannot, because `zh-Hans` has a conventionally
Title-cased script subtag — so we compare case-insensitively and canonicalize (see §2).

### mkdocs-static-i18n — the suffix convention, with the fallback question answered

From `ultrabug/mkdocs-static-i18n` `docs/getting-started/quick-start.md`, verbatim:

> - Using the `suffix` docs structure (default), you will suffix your files with
>   `.<language>.<extension>` (like `index.fr.md`)
> - Using the `folder` docs structure, you will create a folder per language code (like `en/` and
>   `fr/`) and put your localized pages on those folders

> Don't worry about missing pages in the non-default language (here `fr`): they will use the default
> version (`en`) by default (this is configurable using the `fallback_to_default` option).

From `docs/setup/translating-content.md`, the URL result of the suffix structure:

```
docs                     site
├── image.en.png         ├── fr
├── image.fr.png         │   ├── image.png
├── index.fr.md          │   ├── index.html
├── index.md             ├── image.png
                         ├── index.html
```

> The language `locale` selected as being the default one (`default: true`) will be the one built on
> the root path `/` of the site.

And a UX note worth copying:

> You may find useful to inform users that some pages are not translated (yet) by injecting content
> on an announcement block when a page is displayed using its fallback language and thus missing a
> translation.

**Status caveat:** the plugin's README now carries — verbatim —
"Due to the core MkDocs upstream being unmaintained and uncertain **this project is frozen as-is**."
It remains excellent *prior art*; it is not a project to depend on.

**Takeaways.** (i) The suffix convention plus "default locale at `/`, others at `/<locale>/`" is the
mainstream answer. (ii) Localized *assets* (`image.fr.png`) via the same suffix rule is a neat
generalization — worth keeping as a v2 non-goal but a v3 possibility. (iii) Explicitly flagging a
fallback-language page to the reader is established practice, not an invention.

### VitePress — root locale unprefixed, others prefixed

From <https://vitepress.dev/guide/i18n>:

```
docs/
├─ es/
│  ├─ foo.md
├─ fr/
│  ├─ foo.md
├─ foo.md
```

`locales` maps locale keys to settings, with the key `root` denoting the default locale; each locale
carries `label`, `lang` (which becomes the `<html lang>` attribute), and `link`. Per-locale
overridable keys include `lang`, `dir`, `title`, `titleTemplate`, `description`, `head`,
`themeConfig`, and `markdown` strings, where "Values fall back to the root-level `markdown` options
when a locale leaves them unset." The docs do not describe a per-page language switcher, nor
hreflang emission.

### Docusaurus — `/[locale]/` prefix except the default, with hreflang

From <https://docusaurus.io/docs/i18n/introduction> and `/docs/i18n/tutorial`:

> Docusaurus will automatically add a `/<locale>/` path segment to your site for locales except the
> default one.

> Good SEO defaults: we set useful SEO headers like `hreflang` for you

Config shape: `i18n: { defaultLocale, locales, localeConfigs: { en: { htmlLang }, fa: { direction } } }`,
with a `localeDropdown` navbar item. Translations live under `website/i18n/[locale]/[pluginName]/`,
and UI strings in `i18n/<locale>/code.json`. The docs do not state a per-page fallback rule for
untranslated content (Docusaurus's model is "translate the whole docs tree per locale", which is a
poorer fit for a knowledge base that is 90 % one language).

### Material for MkDocs (the theme itself) — the anti-pattern to note

From <https://squidfunk.github.io/mkdocs-material/setup/changing-the-language/>:

> HTML5 only allows to set a single language per document, which is why Material for MkDocs only
> supports setting a canonical language for the entire project.

and its recommendation is "create one project in a subfolder per language". That is a *site*-level
split, not an article-level one — the opposite of what this design needs, and a useful reminder that
one `<html lang>` per document is a hard constraint (which Pagefind then leans on, §3).

### Eden (`anteoas/eden`), from [research/05](./05-eden-fabricate.md)

Language-scoped content dirs, `site.edn` `:lang {:en {:name "English" :default true} …}`, and
per-language `content/<lang>/strings.edn` translation maps with nested keys, defaults, and
`{{var}}` interpolation, consumed by an `:eden/t` directive. Still the best Clojure-side reference
for the *string map* layer specifically; its per-language full render pass with `:lang` in the
render context is exactly the shape clogem-press should use.

### Synthesis — the design clogem-press should adopt

| Question | Convergent answer across Hugo / mkdocs-static-i18n / VitePress / Docusaurus |
|---|---|
| Where does the language live? | Either a filename suffix or a directory. Suffix wins for a KB where most articles have one version — a directory scheme forces the whole tree to be duplicated. |
| Default language URL | Unprefixed at `/`; other languages at `/<lang>/`. Unanimous. |
| How are versions linked? | Implicit by (path, basename); explicit override key for the awkward cases (Hugo's `translationKey`). |
| Missing translation | Fall back to the default version, and tell the reader it is a fallback. |
| UI strings | Per-language key→string maps, fall back to the default language, then to the key. |
| `<html lang>` | One per document, set from the page's language. Non-negotiable (HTML5). |

The one place clogem-press must diverge: those four generators all have a *site-wide* default
language, so the unprefixed URL is always the site default. Here the requirement is a **per-article**
priority order, and an article may not have an `en` version at all — so the unprefixed URL must serve
the highest-priority *available* variant. See DESIGN.md v2 §6.3.

---

## 2. The filename-suffix rule vs. vdoing's numbered-name parsing

vdoing's file rule, from [research/02](./02-vdoing-conventions.md) §1 (verified against
`getSidebarData.js`): split the filename on `.`; `order` = the segment before the **first** dot;
`title` = everything between the **first** and the **last** dot; extension = after the last dot.
`order` is `parseInt`'d, and `NaN`/negative means the entry is skipped with a warning.

The two conventions collide on the last dot-segment. Adding a language suffix must not (a) change how
existing vdoing filenames parse, or (b) misread a legitimate dotted title.

The amended algorithm (specified in full in DESIGN.md v2 §6.1) resolves this by making the
language test **closed over the configured `:langs` set** rather than a general BCP-47 matcher:

```
segs ← split(filename, ".")
last(segs) must be "md"                       ; else warn + skip (vdoing behaviour)
body ← segs[0 .. n-2]                         ; drop extension
if (count(body) ≥ 2) and (lower(last(body)) ∈ lower(configured lang codes)):
    lang ← canonical spelling of that code    ; e.g. "zh-hans" → :zh-Hans
    body ← drop-last(body)
else if (count(body) ≥ 2) and near-miss?(last(body)):
    ERROR with a suggestion                   ; "unknown language `zh-Hanz` — did you mean `zh-Hans`?"
                                              ; near-miss? = contains a hyphen and matches
                                              ;   ^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})+$
                                              ;   OR ∈ confusables(:langs) = {zh, zh-CN, zh-TW,
                                              ;      zh-HK, en-US, …}
else:
    lang ← :default-for-this-article
order ← parseInt(first(body))                 ; NaN or <0 → warn + skip
title ← join(rest(body), ".")                 ; "" → warn + skip
```

The near-miss branch is not optional decoration — the worked table below asserts that
`01.article.zh-Hanz.md` **errors**, and without this branch it would fall through to the `else` and
parse as an article titled `article.zh-Hanz` in the default language. Choice 3 below is where the
branch's shape is argued.

Worked examples, all consistent with vdoing for the non-variant cases:

| Filename | order | title | lang |
|---|---|---|---|
| `01.article.md` | 1 | `article` | default |
| `01.article.zh-Hans.md` | 1 | `article` | zh-Hans |
| `01.Vue.js 入门.md` | 1 | `Vue.js 入门` | default |
| `01.Vue.js.md` | 1 | `Vue.js` | default (`js` ∉ `:langs`) |
| `01.Timing.ms.md` | 1 | `Timing` | **ms** — a real false positive; see below |
| `01.article.zh-Hanz.md` | — | — | **error**, "did you mean zh-Hans?" |
| `10.article.ZH-HANS.md` | 10 | `article` | zh-Hans (case-insensitive, canonicalized) |

Three deliberate choices:

1. **Closed set, not a pattern.** Testing against `:langs` means a site that never configures `ms`
   can freely name a file `01.Timing.ms.md`. A site that *does* configure `ms` accepts the ambiguity
   as the price of the convention — exactly as Hugo does — and gets an escape hatch: front matter
   `lang:` overrides filename inference, and `title:` overrides the parsed title. `bb doctor` warns
   whenever stripping a suffix leaves a variant with no same-identity sibling, which catches this
   class of accident.
2. **Case-insensitive compare, canonical storage.** BCP 47 conventionally Title-cases script subtags
   (`zh-Hans`), but case-insensitive filesystems and habit produce `zh-hans`. Hugo sidesteps this by
   *requiring* lowercase; we cannot, because the canonical `<html lang>` value should read `zh-Hans`.
   So: compare lowercased, store the configured spelling, emit the configured spelling.
3. **Near-miss codes are an error, not silently part of the title.** A segment that *contains a
   hyphen* and looks like a language tag (`^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})+$`), or that is in the
   small confusables set derived from `:langs` (`zh`, `zh-CN`, `zh-TW`, `zh-HK`, `en-US`, …), but is
   not itself configured → hard error with a suggestion. The hyphen requirement is what keeps
   `01.Vue.js.md` from tripping it. A silent misparse costs a wrong title *and* a lost translation
   link; failing loudly is cheaper.

---

## 3. Pagefind multilingual — verified against docs **and source**

### From the docs (`docs/content/docs/multilingual.md` in `Pagefind/pagefind`), verbatim

> When indexing, Pagefind will look for a `lang` attribute on your `html` element. Indexing will then
> run independently for each detected language. When Pagefind initializes in the browser it will
> check the same `lang` attribute and load the appropriate index.

> If you load Pagefind search on a page tagged as `<html lang="pt-br">`, you will automatically
> search only the pages on the site with the same language tag. Pagefind will also adapt any stemming
> algorithms to the target language if supported. This applies to both the Pagefind JS API and the
> Pagefind UI.

> Setting the force language option when indexing will opt out of this feature and create one index
> for the site as a whole.

> Pagefind will work automatically for any language. Explicit language support improves the quality
> of search results and the Pagefind UI. If word stemming is unsupported, search results won't match
> across root words. If UI translations are unsupported, the Pagefind UI will be shown in English.

Language-support table rows for the five languages in scope:

| Language | UI translations | Word stemming |
|---|---|---|
| English — `en` | ✅ | ✅ |
| Chinese — `zh` | ✅ | "See below" (segmentation instead) |
| Tamil — `ta` | ✅ | ✅ |
| Malay — `ms` | **not listed** | **not listed** |
| (Indonesian — `id`, for contrast) | ✅ | ✅ |

And on the specialized languages:

> This section currently applies to Chinese, Japanese, and Korean languages. Specialized languages
> are only supported in Pagefind's extended release, which is the default when running `npx pagefind`.

> In practice, this means that on a page tagged as a `zh-` language, `每個月都` will be indexed as the
> words `每個`, `月`, and `都`.

Note that the docs' own worked example, `每個月都`, is **Traditional** Chinese.

### From the source — the questions the docs do not answer

**Does `zh-Hant` get segmented?** Yes. `pagefind/src/fossick/mod.rs`:

```rust
#[cfg(feature = "extended")]
let should_segment = matches!(data.language.split('-').next().unwrap(), "zh" | "ja" | "th");
```

The match is on the **primary subtag**, so `zh`, `zh-Hans`, `zh-Hant`, `zh-CN`, `zh-TW` all segment
identically. This is the single most important finding for this design: a `zh-Hant` UI and `zh-Hant`
content are first-class in `pagefind_extended`, with no configuration.

Two side observations from the same line: **Thai (`th`) is segmented but Korean (`ko`) is not**,
which contradicts the docs' "applies to Chinese, Japanese, and Korean". Neither language is in scope
here; recorded only so the discrepancy is not rediscovered later.

**What exactly is `pagefind_extended`?** `pagefind/Cargo.toml`:

```toml
charabia = { version = "0.9.3", optional = true, default-features = false, features = [
    "chinese", "japanese", "thai",
] }
[features]
extended = ["dep:charabia"]
```

So the extended binary is *precisely* "pagefind + charabia's CJK/Thai segmenters", which matches the
`should_segment` list exactly.

**Which stemmers ship?** The `pagefind_stem` feature list in the same file includes `tamil` and
`indonesian` — and **no `malay`**. `get_stemmer` also dispatches on the primary subtag:

```rust
fn get_stemmer(lang: &str) -> Option<Stemmer> {
    match lang.split('-').next().unwrap() {
        …
        "ta" => Some(Stemmer::create(Algorithm::Tamil)),
        …
        _ => None,
    }
}
```

So `ta` → Snowball Tamil stemmer; `ms` → `None`; `zh-*` → `None` (segmentation instead of stemming,
by design).

**What is the index key?** `pagefind/src/fossick/parser.rs`:

```rust
if let Some(lang) = el.get_attribute("lang") {
    data.language = Some(lang.to_lowercase());
}
…
language: data.language.filter(|lang| !lang.is_empty()).unwrap_or_else(|| "unknown".into()),
```

The full tag is lowercased and used as-is; a missing or empty `lang` becomes the literal
`"unknown"`. This corroborates the empirical observation in [research/08](./08-search-syntax-highlighting.md)
that a `lang="zh-CN"` corpus produced `index/zh-cn_*.pf_index` files.

### Consequences for clogem-press

1. **Five `<html lang>` values → five independent indexes** (`en`, `zh-hans`, `zh-hant`, `ms`, `ta`),
   with zero configuration. Search from an English page searches English pages. This is the desired
   behaviour and it falls out for free.
2. **`pagefind_extended` is now doubly required.** v1 justified it by zh-CN content; v2 needs it for
   both `zh-Hans` and `zh-Hant`, and gets Thai as a bonus we do not use.
3. **`ms` degrades gracefully but visibly**: indexed and searchable (Latin script, whitespace
   delimited), no stemming, and the Pagefind UI chrome renders in English on Malay pages. Mitigation:
   Pagefind's UI accepts custom `translations`, so clogem-press should feed its own `ms` strings from
   the same i18n map that powers the theme chrome — turning a gap into a one-line wiring job. **Do
   not** map `ms` → `id` to borrow the Indonesian stemmer: they are different languages, and a wrong
   stemmer produces wrong matches rather than no matches.
4. **`ta` is fully supported** — UI translations and a Snowball stemmer.
5. **Default trade-off:** a reader searching from a `zh-Hans` page does not, by default, find an
   article that exists only in `zh-Hant`. The build-time alternative, `--force-language`, merges
   everything into one index and destroys per-language stemming, per-language UI, and result
   relevance — a much larger loss, and still rejected.
6. **`zh-Hant` UI strings need explicit wiring — a correctness bug if skipped.** Index selection uses
   the full lowercased tag (so `zh-hant` is genuinely its own index, per §3 above), but the UI's
   *translation* lookup is a separate mechanism with **no language-script key**: the shipped
   translation files are keyed by primary subtag, so `<html lang="zh-Hant">` resolves to `zh.json`,
   which holds **Simplified** strings — while `zh-tw.json` sits unused in the same directory. Left
   alone, a Traditional Chinese page renders Traditional content wrapped in a Simplified search UI.
   The fix is the same wiring §3.3 already prescribes for `ms`: supply strings through the Default
   UI's **`translations`** option (verified option name — `new PagefindUI({ translations: {…} })`);
   the Component UI equivalent is `instance.setTranslations()`, paired with `instance.setLanguage()`.
   So clogem-press supplies its own strings for **both** `ms` and `zh-Hant`, and only `en`/`zh-Hans`/
   `ta` ride on Pagefind's built-in translations.
7. **Cross-language search — settled, not open.** Pagefind's browser API exposes
   **`pagefind.mergeIndex(bundleUrl, {language: "…"})`** (documented on the multisite page), which
   loads an additional index at *query* time alongside the primary one, each keeping its own stemmer
   and segmenter. That makes a "search other languages too" affordance real, and strictly better than
   `--force-language`, which is a build-wide loss. Three constraints, all load-bearing:
   - **The primary index's language cannot be overridden** — it comes from hardcoded `<html lang>`
     detection. Only *merged* indexes accept an explicit `{language: …}`.
   - **Merging the same site's own bundle path is silently skipped**, by a `basePath.startsWith`
     guard meant to stop a site merging itself. It fails quietly — no error, just no extra results —
     so the **absolute URL** must be passed (`https://…/pagefind/pagefind.js`). The documented
     alternative is mutating `document.documentElement.lang` and then `destroy()`/`init()` to re-run
     detection; it works, but discards the loaded index, so the absolute-URL form is preferred.
   - **The Component UI's `lang` attribute swaps UI strings only, never the index.** Reaching for it
     to change *what* is searched is the obvious wrong turn.

   (Earlier revisions listed this as "not verified: whether the JS API exposes a language override".
   It does, with the caveats above. DESIGN.md §6.7 and its risk table are updated accordingly.)

Current version is unchanged from v1: Pagefind **1.5.2 (April 12, 2026)** is still the newest entry
in `CHANGELOG.md`, with an empty `## Unreleased` section above it.

---

## 4. SEO: hreflang, canonical, sitemap

From <https://developers.google.com/search/docs/specialty/international/localized-versions>:

**Three equivalent methods** — HTML `<link rel="alternate" hreflang="…" href="…">` in the head, an
HTTP `Link:` header, or `<xhtml:link rel="alternate" hreflang="…">` children in the XML sitemap.
Google: "The three methods are equivalent from Google's perspective and you can choose the method
that's the most convenient for your site." GitHub Pages does not allow custom headers
(confirmed in [research/09](./09-comments-deployment.md)), so head links and/or sitemap are the
options — clogem-press emits both.

**Bidirectional and self-referencing are mandatory:**

> Each language version must list itself **as well as** all other language versions.

> If two pages don't both point to each other, the tags will be ignored.

This is trivially satisfied by generating the whole hreflang set from a single identity group.

**`x-default`:**

> The reserved `x-default` value is used when no other language/region matches the user's browser
> setting.

Mapped in this design to the unprefixed canonical URL — i.e. the priority-primary variant, which is
exactly "what to show when nothing matches".

**Script subtags are supported** — this was the fact I most wanted confirmed, and Google documents it
with our exact codes:

> For language script variations, the proper script is derived from the country. For example, when
> using `zh-TW` for users in Taiwan, the language script is automatically derived (in this example:
> Chinese-Traditional). You can also specify the script itself explicitly using ISO 15924, like this:
> `zh-Hant`: Chinese (Traditional); `zh-Hans`: Chinese (Simplified)

> Like with other language codes, you can also specify an optional region. For example, use
> `zh-Hans-US`.

So `hreflang="zh-Hans"` and `hreflang="zh-Hant"` are valid to Google, and no `zh-CN`/`zh-TW` aliasing
is needed.

**Partial translation:**

> Localized versions of a page are only considered duplicates if the main content of the page remains
> untranslated.

i.e. a genuinely translated article is not duplicate content, which is the case that matters here.
The page does **not** discuss the interaction between `hreflang` and `rel=canonical`; the design
takes the conservative standard practice of **self-canonical per variant** (each variant canonicalizes
to itself) rather than canonicalizing all variants to one URL, which would ask Google to drop the
translations.

**BCP 47 / `<html lang>`.** W3C, *Language tags in HTML and XML*: the subtag order is
`language-extlang-script-region-variant-extension-privateuse` (example `zh-Hant-HK`), and

> The availability of `zh-Hans` and `zh-Hant` for Chinese written in Simplified and Traditional
> scripts should improve consistency and accuracy.

with the guidance "You should only use region subtags if they are necessary to make a distinction you
need." `zh-Hans` / `zh-Hant` (rather than `zh-CN` / `zh-TW`) is therefore the correct choice for a
Singapore-facing site, where neither mainland-China nor Taiwan region semantics are wanted.

---

## 5. Comments: one giscus thread per article identity

From `giscus/giscus` `components/Configuration.tsx`:

```ts
type Mapping = 'pathname' | 'url' | 'title' | 'og:title' | 'specific' | 'number';
```

and the emitted snippet includes `data-term` when the mapping is `specific` or `number`.

**Design consequence.** The v1 recommendation of `data-mapping="pathname"` was correct for a
monolingual site but is wrong once variants exist: `/pages/a1b2c3/` and `/zh-Hans/pages/a1b2c3/`
would become *two* discussions, fragmenting one article's comments across languages. v2 uses:

```html
data-mapping="specific"
data-term="/pages/a1b2c3/"      <!-- the canonical permalink = the article identity -->
data-strict="1"
```

Every variant of an article emits the same `data-term`, so the group shares one GitHub Discussion.
`data-strict="1"` prevents fuzzy title matching from merging unrelated threads. This also makes the
thread identity independent of the URL scheme, so a later change to the language-prefix layout cannot
orphan existing discussions.

**What strict mode actually matches — and the migration trap in it.** Strict mode does not search
discussion titles. It looks for a marker the giscus bot writes into the discussion **body** at
creation time: an HTML comment `<!-- sha1: <hex> -->` carrying the SHA-1 of the term. A discussion
that giscus did not create carries no such marker and is therefore **invisible to the widget under
strict mode** — the page renders as though no thread existed, and the first comment posted opens a
*second* discussion beside the original.

So migrating pre-existing threads takes **two** steps, not one:

1. retitle the discussion to the term (`/pages/a1b2c3/`), **and**
2. append `<!-- sha1: <sha1-of-that-term> -->` to the discussion **body**.

The hash is a pure function of the term, so `clogem migrate` can print the exact line per thread
(DESIGN.md §7.4 step 8). New sites are unaffected — every thread is bot-created. Disabling strict mode
to sidestep this is the wrong trade: fuzzy title matching is what merges unrelated threads, which is a
worse failure and harder to undo.

**`data-lang` support.** From `giscus/giscus` `lib/i18n.tsx`, `availableLanguages` contains 35 entries
including `en`, `zh-CN`, `zh-TW`, `zh-HK`, `id`, `th`, `vi`, `ko`, `ja` — and **neither `ms` nor
`ta`**. So the mapping is:

| clogem-press lang | `data-lang` |
|---|---|
| `en` | `en` |
| `zh-Hans` | `zh-CN` |
| `zh-Hant` | `zh-TW` |
| `ms` | `en` (**mandatory** — see below) |
| `ta` | `en` (**mandatory** — see below) |

**The fallback is mandatory, not cosmetic.** `data-lang` is routed into the widget's iframe URL, so an
unroutable value does not degrade to English chrome — it **404s the iframe and the widget does not
render at all**. On a Tamil or Malay page that is the difference between English-labelled comments and
*no comments*. clogem-press therefore validates at build time that every configured locale carries a
`:giscus` value drawn from giscus's own `availableLanguages`, and fails the build naming the offending
language rather than shipping an invisible failure.

giscus remains available from Singapore (the China-reachability caveat from research/09 no longer
applies to the audience), so it stays the primary provider — see DESIGN.md D-6.

---

## 6. Typography: Tamil, Malay, and the two Chinese scripts

**Malay** in Singapore is written in **Rumi** (Latin script); Jawi is used ceremonially and is out of
scope. No font work is needed — Malay text renders in the body Latin stack.

**Tamil** needs attention. Platform coverage is good but inconsistent, and the fallback chain matters
because Tamil glyphs are tall and a bad substitution is very visible. Readium's default font-stack
reference (`readium.org/css/docs/CSS09-default_fonts.html`) gives for `ta`:

> "Tamil Sangam MN", "Nirmala UI", Latha, Roboto, Noto, "Noto Sans Tamil"

i.e. macOS/iOS ship *Tamil Sangam MN*, Windows ships *Nirmala UI* (Tiro Typeworks, commissioned by
Microsoft, shipped since Windows 8) and *Latha*, Android resolves through Roboto/Noto to
*Noto Sans Tamil*. Linux desktop coverage varies by distribution and is the weak link.

**Self-hosting option.** *Noto Sans Tamil* is licensed **SIL Open Font License 1.1** — verified from
`notofonts/tamil` `OFL.txt`: "Copyright 2022 The Noto Project Authors
(https://github.com/notofonts/tamil) … This Font Software is licensed under the SIL Open Font
License, Version 1.1." Per the Noto project's own specimen page it covers Tamil plus Basic Latin,
General Punctuation, Devanagari and Grantha, and serves "Tamil language, and other languages like
Irula, Badaga, Kurumba, Paniya, Saurashtra."

Design guidance (DESIGN.md §6.9): ship a `:lang(ta)` stack ending in the system names above; make
self-hosting an opt-in config flag; when enabled, serve a **subsetted woff2 from the site's own
assets** (no Google Fonts CDN — keeps the "no third-party CDN" rule from D-6 and avoids the privacy
and blocking issues), with `font-display: swap` and `unicode-range: U+0B80-0BFF` so Latin-only pages
never fetch it. Give `:lang(ta)` slightly larger `line-height` than the Latin default.

**Chinese** stacks follow the same `:lang()` pattern with the conventional per-platform families
(PingFang SC/TC on macOS/iOS, Microsoft YaHei / JhengHei on Windows, Noto Sans CJK SC/TC on Android
and Linux), all system fonts, nothing downloaded. Note that `:lang(zh-Hans)` and `:lang(zh-Hant)`
must be distinct rules: shape differences between the scripts are not a fallback concern but a
correctness one, and CSS `:lang()` matches on the language-tag prefix so `:lang(zh)` alone would
collapse them.

---

## 7. Where the i18n design deliberately diverges from all the prior art

| Divergence | Why |
|---|---|
| The unprefixed URL serves the **highest-priority available** variant, not a fixed site default | The requirement allows an article with no `en` version; every article must still be reachable at its identity URL, and sidebar/index links must not have to know which languages exist. |
| Chrome language always equals the page's content language; no client-side string swapping | HTML5 allows one `lang` per document, Pagefind's index selection *reads* that attribute, and a client-side chrome swap would desynchronize the two. The stored UI preference instead drives *where the switcher navigates*, not what a given URL renders. |
| Article identity = the permalink (not a separate `translationKey`) | It is already the URL, already the comment-thread key, already collision-checked, and already written into the file by auto-fill. One identity, one field. |
| Indexes iterate identity groups, not files | An article must appear once per index, not once per language version — an explicit requirement, and the reason dedupe-by-identity is a model-layer concern rather than a template concern. |

---

## 8. "China-oriented features removed" — replacements verified

Per D-6, the site's audience is Singapore, so vdoing's China-specific tooling is dropped. Documented
replacements:

| Removed (vdoing) | Replacement |
|---|---|
| `baiduPush` npm script + `baiduPush.sh` + daily cron workflow (see [research/02](./02-vdoing-conventions.md) §8) | `sitemap.xml` (already in the design) submitted via Google Search Console / Bing Webmaster Tools; optionally **IndexNow** for push-style notification |
| `vuepress-plugin-baidu-autopush` (browser-side push) | nothing — client-side ping to a search engine is not a pattern worth reviving |
| `vuepress-plugin-baidu-tongji` | optional analytics slot in config: GA4, or the self-hostable privacy-first options (Plausible; **Umami**, MIT-licensed per its repo, "self-hosted or in the cloud"), default `:none` |
| `social.iconfontCssFile` (iconfont.cn) | vendored inline SVGs (unchanged from v1 §5.3) |

**IndexNow**, from <https://www.indexnow.org/documentation>: participating engines share submitted
URLs with each other; submission is a GET for a single URL or a POST for a batch of up to 10,000;
the API key must be "a minimum of 8 and a maximum of 128 hexadecimal characters"; a `429` response
signals rate limiting. The POST body shape, verbatim:

```
POST /indexnow HTTP/1.1
Content-Type: application/json; charset=utf-8
Host: <searchengine>
{
  "host": "www.example.com",
  "key": "",
  "urlList": [
      "https://www.example.com/url1",
      "https://www.example.com/folder/url2",
      "https://www.example.com/url3"
      ]
}
```

This is the structural analogue of vdoing's Baidu push — walk the pages, collect
`domain + permalink`, POST the list — so the existing `baiduPush.js` logic ports to a `bb indexnow`
task essentially unchanged, if the owner ever wants it. It stays **off by default** (D-13): a static
personal KB gains little over sitemap-based discovery, and it adds a key file to the published root.

---

## 9. Not verified / open questions

- ~~**Pagefind JS API language override**~~ — **resolved**, see §3.6 item 7: `mergeIndex(bundleUrl,
  {language: …})`, with the primary index non-overridable and the same-site `basePath.startsWith`
  guard requiring an absolute URL.
- ~~**Pagefind UI custom translations**~~ — **resolved**, see §3.6 items 6–7: the Default UI option is
  **`translations`**; the Component UI equivalent is `setTranslations()` / `setLanguage()`. The pass
  also turned up a defect this was hiding: `zh-Hant` reaches `zh.json` (Simplified) because UI-string
  resolution has no language-script key, so Traditional needs the same explicit wiring as `ms`.
- **CJK/Tamil heading-slug behaviour in nextjournal/markdown** — still untested, carried forward as
  an open risk from v1. Now larger in scope: five languages of headings instead of two.
- **Charabia's Traditional→Simplified normalization.** charabia's `chinese` feature is known to
  normalize; whether Pagefind's use of it means a `zh-Hant` query can match `zh-Hans` text *within a
  single index* was not established, and is moot here because the two languages have separate
  indexes. Flagged so nobody assumes cross-script matching works.
- **No empirical multilingual Pagefind run** was performed in this session (the v1 report's run was
  `zh-CN` only). The `should_segment` source line is strong evidence for `zh-Hant`, but a five-language
  fixture build is a **Phase 3** acceptance test, not a completed verification — matching DESIGN.md
  Appendix A and the CJK/Tamil risk-table row. (Earlier revisions of this section said "Phase 1",
  which conflicted with both; the five-language *fixture corpus* exists from Phase 1, the *Pagefind
  build over it* is Phase 3.)
- **Real-world Tamil/Malay proofreading capacity** is a project risk, not a technical one — recorded
  in DESIGN.md's risk table.

## Sources

- https://gohugo.io/content-management/multilingual/ (and https://raw.githubusercontent.com/gohugoio/hugoDocs/master/content/en/content-management/multilingual.md)
- https://github.com/ultrabug/mkdocs-static-i18n (README, frozen-status notice)
- https://raw.githubusercontent.com/ultrabug/mkdocs-static-i18n/main/docs/getting-started/quick-start.md
- https://raw.githubusercontent.com/ultrabug/mkdocs-static-i18n/main/docs/setup/translating-content.md
- https://ultrabug.github.io/mkdocs-static-i18n/setup/setting-up-languages/
- https://vitepress.dev/guide/i18n
- https://docusaurus.io/docs/i18n/introduction
- https://docusaurus.io/docs/i18n/tutorial
- https://squidfunk.github.io/mkdocs-material/setup/changing-the-language/
- https://pagefind.app/docs/multilingual/
- https://raw.githubusercontent.com/Pagefind/pagefind/main/docs/content/docs/multilingual.md
- https://raw.githubusercontent.com/Pagefind/pagefind/main/pagefind/Cargo.toml
- https://raw.githubusercontent.com/Pagefind/pagefind/main/pagefind/src/fossick/mod.rs
- https://raw.githubusercontent.com/Pagefind/pagefind/main/pagefind/src/fossick/parser.rs
- https://raw.githubusercontent.com/Pagefind/pagefind/main/CHANGELOG.md
- https://developers.google.com/search/docs/specialty/international/localized-versions
- https://www.w3.org/International/articles/language-tags/
- https://raw.githubusercontent.com/giscus/giscus/main/lib/i18n.tsx
- https://raw.githubusercontent.com/giscus/giscus/main/components/Configuration.tsx
- https://raw.githubusercontent.com/notofonts/tamil/main/OFL.txt
- https://notofonts.github.io/noto-docs/specimen/NotoSansTamil/
- https://readium.org/css/docs/CSS09-default_fonts.html
- https://en.wikipedia.org/wiki/Nirmala_UI
- https://raw.githubusercontent.com/umami-software/umami/master/README.md
- https://www.indexnow.org/documentation
