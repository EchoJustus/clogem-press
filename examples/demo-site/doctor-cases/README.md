# Doctor cases

Filenames that are supposed to **fail**. They live here, outside `content/`, so
the demo site still builds — a near-miss language suffix is a hard error by
design (DESIGN.md §6.1), so a copy inside `content/` would break CI on every run
rather than demonstrating anything.

To see the error, copy one into `content/01.Guide/10.Basics/` (dropping the
`.disabled` suffix) and run `bb doctor`.

| File | Expected |
|---|---|
| `01.article.zh-Hanz.md.disabled` | error: unknown language `zh-Hanz` — did you mean `zh-Hans`? (one edit from a configured code, DESIGN.md §6.1 rule (b)) |
| `01.article.zh-CN.md.disabled` | error: `zh-CN` is in the confusables set derived from `:langs` (rule (a)) |
| `01.article.ta-IN.md.disabled` | error: a configured primary subtag (`ta`) followed by a region-shaped subtag (rule (c)) |
| `01.Setup.md.disabled`, `01.Teardown.md.disabled` | error: duplicate sidebar number 1 for *different* articles (M1). `10.Basics/` already has `01.getting-started.md`, so **either one alone** collides; copy both to see three identities named in one error. |
| `02.conventions.ms.md.disabled` | error: an identity group whose members declare different `permalink:` values (§6.2). Copy it into `content/01.Guide/10.Basics/`, beside the three `02.conventions*` files it claims to be a variant of. |
| `duplicate-dirs/10.Alpha/`, `duplicate-dirs/10.Beta/` | error: two **sibling directories** sharing a number (D-3, §6.1). Copy both directories into `content/01.Guide/` and drop the `.disabled` suffixes. `01.Guide/` already has `10.Basics/`, so **either one alone** collides; copy both to see three directories named in one error. Directories are never language-suffixed, so unlike files there is no legal same-number case to exempt — any two siblings sharing a number are a collision. |

**What is deliberately NOT here any more:** plain hyphenated slugs. Under the
Phase 1 rule any hyphenated two- or three-letter word in the last dot-segment
was treated as a misspelled language code, so `02.api-design.md`,
`03.my-notes.md` and `05.re-frame.md` hard-errored (D-P2-14). They are ordinary
titles now — `content/02.Notes/10.Local/04.api-design.md` is the MUST-PASS
fixture for that, built by CI on every push.

The last row is the directory half of the same rule, and it is scoped
differently on purpose: files group on *(number, base name)* because language
variants share both by construction, while directories group on the number
alone because they are never language-suffixed.

The third row is the point of v2.1's M1 correction. Note the contrast with
`02.conventions.md` / `.zh-Hans.md` / `.zh-Hant.md`, which are checked into
`content/` and also share a number — and must **not** error, because same
number *and* same base name means one article with three language variants.
The rule keys on identity, not on the number alone; the unscoped vdoing rule
would fail the build on every translated article in the tree.

`test/clogem/scan_test.clj` asserts all of these, so they are checked on every
push rather than only when a human runs the copy-and-see recipe.
