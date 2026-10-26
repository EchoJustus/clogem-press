#!/usr/bin/env bash
# Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
#
# The support page's font step (DESIGN.md §7.5; README, "Support page"):
# after `bb support-page`, cut the LXGW WenKai subsets the pages use and
# put them, with the fonts' licences, in <out>/clogem-press/fonts/.
#
#     bb support-page && support/fonts.sh            # dist-support/
#     support/fonts.sh DIR                           # after bb support-page --out DIR
#
# 1. The plan (support/fonts.edn, printed as lines by `bb support-fonts`):
#    each font file's URL and SHA-256, each licence's, each subset's face
#    and size cap.
# 2. Each file is downloaded once into the cache (CLOGEM_FONT_CACHE, by
#    default ~/.cache/clogem-press/fonts; CI caches it) and checked against
#    its SHA-256, every run; a mismatch stops the step.
# 3. fontTools and brotli, at the versions and hashes below, into a venv
#    outside the repository (CLOGEM_FONT_VENV, by default under TMPDIR),
#    made by python3, which must be Python 3.12 or 3.13 (checked).
#    The repository holds no Python: pyftsubset and the coverage check run
#    from here, on the runner (or on your machine).
# 4. pyftsubset cuts each subset from <out>/glyphs/<id>.txt, the characters
#    the build found the pages set in it, as WOFF2.
# 5. Every character of each list must be in its subset's cmap.
# 6. The fonts' OFL.txt go beside the subsets (OFL condition 2).
# 7. Each subset must be within its cap; the sizes, and each page's
#    download, are printed.
set -euo pipefail

out=${1:-dist-support}
here=$(cd "$(dirname "$0")" && pwd)
cache=${CLOGEM_FONT_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/clogem-press/fonts}
venv=${CLOGEM_FONT_VENV:-${TMPDIR:-/tmp}/clogem-press-fonts-venv}
glyphs="$out/glyphs"
fonts="$out/clogem-press/fonts"

fail() { echo "::error::fonts.sh: $*" >&2; exit 1; }

[ -d "$out/clogem-press" ] && [ -d "$glyphs" ] || fail "no $out/clogem-press/ or $glyphs/: run bb support-page first"

# 1. the plan
plan=$(bb --config "$here/../bb.edn" support-fonts)
[ -n "$plan" ] || fail "bb support-fonts printed nothing"

# 2. the fonts and their licences, checked against their pins
mkdir -p "$cache"
fetch() {
  local name=$1 url=$2 sha=$3
  case $name in */* | .*) fail "bad file name $name" ;; esac
  if ! printf '%s  %s\n' "$sha" "$cache/$name" | sha256sum -c --quiet --status - 2> /dev/null; then
    echo "Downloading $url"
    rm -f "$cache/$name"
    curl --fail --location --proto '=https' --proto-redir '=https' --silent --show-error --retry 3 \
      -o "$cache/$name.part" "$url"
    mv "$cache/$name.part" "$cache/$name"
  fi
  printf '%s  %s\n' "$sha" "$cache/$name" | sha256sum -c --quiet - ||
    { rm -f "$cache/$name"; fail "$name does not match its SHA-256 in support/fonts.edn"; }
}
while read -r kind name url sha _; do
  case $kind in font | licence) fetch "$name" "$url" "$sha" ;; esac
done <<< "$plan"

# 3. fontTools and brotli, pinned by version and hash (the wheels for
#    CPython 3.12 and 3.13 on x86-64 Linux, and fontTools' pure one): on any
#    other Python, pip would find no wheel with a pinned hash and report the
#    files as tampered with, so the version is checked first
pyver=$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])') || fail "the font step needs Python 3.12 or 3.13; found no python3"
case $pyver in
  3.12 | 3.13) ;;
  *) fail "the font step needs Python 3.12 or 3.13; found $pyver" ;;
esac
python3 -m venv "$venv"
"$venv/bin/python" -m pip install --quiet --disable-pip-version-check --require-hashes --no-deps --only-binary=:all: -r /dev/stdin << 'EOF'
fonttools==4.66.1 \
  --hash=sha256:7b8ff9e0edbcee2fbf7dff0c41b9041c1901c26acf64e23adb67495012df11de \
  --hash=sha256:83572afe48733bad7a4a9c11721d3a726c2e976d82b063fc9bdd049d76955abd \
  --hash=sha256:7234ae9e28db64273fbbfa72caebd0a97e3bdba6b05064114741b9539ef339d0
brotli==1.2.0 \
  --hash=sha256:072e7624b1fc4d601036ab3f4f27942ef772887e876beff0301d261210bca97f \
  --hash=sha256:67a91c5187e1eec76a61625c77a6c8c785650f5b576ca732bd33ef58b0dff49c
EOF

# 4-5. the subsets, each checked against its list
rm -rf "$fonts"
mkdir -p "$fonts"
lists=("$glyphs"/*.txt)
[ -e "${lists[0]}" ] || fail "no character lists in $glyphs/"
for list in "${lists[@]}"; do
  id=$(basename "$list" .txt)
  line=$(grep -E "^subset $id " <<< "$plan") || fail "$id is not a subset in support/fonts.edn"
  read -r _ _ src _ <<< "$line"
  # all layout features (vert for vertical text, the CJK punctuation
  # spacing); every name record, so the copyright and licence travel with
  # the file (the fonts' names may be kept for web delivery: DESIGN.md §7.5)
  "$venv/bin/pyftsubset" "$cache/$src" --text-file="$list" --output-file="$fonts/$id.woff2" \
    --flavor=woff2 --layout-features='*' --desubroutinize --name-IDs='*' --name-languages='*' --notdef-outline
  "$venv/bin/python" -I -c 'import sys; from fontTools.ttLib import TTFont; cmap = TTFont(sys.argv[1]).getBestCmap(); missing = sorted({c for c in open(sys.argv[2], encoding="utf-8").read() if c != "\n" and ord(c) not in cmap}); sys.exit("%s lacks: %s" % (sys.argv[1], " ".join("U+%04X %s" % (ord(c), c) for c in missing))) if missing else None' \
    "$fonts/$id.woff2" "$list" || fail "$id does not cover its list"
done
echo "Coverage: every character of each list is in its subset."

# 6. the licences
while read -r kind name _ _ as; do
  if [ "$kind" = licence ]; then cp "$cache/$name" "$fonts/$as"; fi
done <<< "$plan"

# 7. the sizes
over=0
printf '%-24s %10s %10s\n' subset bytes cap
for list in "${lists[@]}"; do
  id=$(basename "$list" .txt)
  read -r _ _ _ cap <<< "$(grep -E "^subset $id " <<< "$plan")"
  size=$(wc -c < "$fonts/$id.woff2")
  printf '%-24s %10d %10d\n' "$id" "$size" "$cap"
  if [ "$size" -gt "$cap" ]; then echo "::error::$id is $size bytes, over its cap of $cap"; over=1; fi
done
[ "$over" = 0 ] || exit 1
echo
echo "Each page and every font it declares (the other languages' names load only when the list is opened):"
(
  cd "$out/clogem-press"
  find . -name index.html | sort | while read -r page; do
    total=$(wc -c < "$page")
    for f in $(grep -o 'fonts/[a-z0-9-]*\.woff2' "$page" | sort -u); do
      total=$((total + $(wc -c < "$f")))
    done
    printf '%-22s %10d bytes\n' "${page#./}" "$total"
  done
)
