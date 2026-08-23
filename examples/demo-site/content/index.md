---
home: true
title: clogem-press demo
---

# clogem-press demo site

A five-language fixture tree that exercises every content convention the
generator implements. CI builds it on every push, which makes it the executable
specification rather than documentation that can drift.

What it covers:

- numbered directories three levels deep (`03.Deep/10.Level2/20.Level3/`)
- a `zh-Hans` + `zh-Hant` pair sharing one identity
- a **Tamil-only** article, so the per-article priority fallback of §6.3 is
  always exercised
- a Malay-only article
- an explicit-permalink variant living elsewhere in the tree (§6.2 mechanism 2)
- `_posts/` and `@pages/`, including two posts that share a slug and a date and are
  different articles anyway because one lives in `_posts/tech/` (§6.2)
- a catalogue-page placeholder
