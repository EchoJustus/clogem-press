# Rebuilding the self-hosted Tamil subsets

`:theme {:fonts {:tamil :self-hosted}}` (DESIGN.md §6.9, §11.2, D-P3-12) serves
two files committed under `src/clogem/theme/resources/fonts/`:

| File | Source | Size |
|---|---|---|
| `noto-sans-tamil-400.woff2` | `NotoSansTamil-Regular.ttf` | 23 712 B |
| `noto-sans-tamil-700.woff2` | `NotoSansTamil-Bold.ttf` | 24 812 B |
| `OFL.txt` | the release zip's `OFL.txt` | — |

Nothing here runs at build time: the files are committed, and a site build
needs no Python, no fonttools and no network. This page is the record of how
they were made, so they can be remade or audited.

## Source

Noto Sans Tamil **v2.004**, from the notofonts/tamil release:

    https://github.com/notofonts/tamil/releases/download/NotoSansTamil-v2.004/NotoSansTamil-v2.004.zip
    sha256 f8284e0f200a7f29a439b4ec88280d864b2b31f8479111c5b658ba6da38b3005

Use the static TTFs under `NotoSansTamil/googlefonts/ttf/` (not the `UI`
variants):

    NotoSansTamil-Regular.ttf  sha256 eaf00dabd04f93ae9826ebf0e1ae01c31b3eefc083b72657294633d5820e603d
    NotoSansTamil-Bold.ttf     sha256 22ebe56c9720ffe506f0abd34564549feeb5a7e43cf7b93d1c370037adabe427

Licence: SIL Open Font License 1.1, with **no Reserved Font Name** (the
`OFL.txt` header reserves none), so a subset may keep the name "Noto Sans
Tamil". The OFL requires the licence to travel with the font, hence
`OFL.txt` beside the woff2 files, and the subsets keep every `name` table
record (`--name-IDs='*'`), including the copyright, licence (ID 13) and
licence URL (ID 14).

## Commands

fonttools 4.66.1 with brotli 1.2.0 (any recent fonttools works; the output is
not byte-reproducible across fonttools versions, which is why the files are
committed rather than regenerated):

```sh
python3 -m venv venv && venv/bin/pip install fonttools brotli
unzip NotoSansTamil-v2.004.zip 'NotoSansTamil/googlefonts/ttf/NotoSansTamil-Regular.ttf' \
                               'NotoSansTamil/googlefonts/ttf/NotoSansTamil-Bold.ttf' '../OFL.txt'
T=NotoSansTamil/googlefonts/ttf

venv/bin/pyftsubset $T/NotoSansTamil-Regular.ttf \
  --unicodes='U+0B80-0BFF,U+200C-200D,U+25CC' --layout-features='*' --name-IDs='*' \
  --flavor=woff2 --output-file=noto-sans-tamil-400.woff2

venv/bin/pyftsubset $T/NotoSansTamil-Bold.ttf \
  --unicodes='U+0B80-0BFF,U+200C-200D,U+25CC' --layout-features='*' --name-IDs='*' \
  --flavor=woff2 --output-file=noto-sans-tamil-700.woff2
```

The ranges: the Tamil block (U+0B80–0BFF), ZWNJ/ZWJ (U+200C–200D), which
Tamil shaping uses, and the dotted circle (U+25CC) a shaper draws under an
orphaned combining mark. `--layout-features='*'` keeps every GSUB/GPOS
feature, which Tamil conjuncts and vowel signs need. `fonts/tamil.css`
declares the same `unicode-range`, so a browser fetches a file only for a
page that renders Tamil.
