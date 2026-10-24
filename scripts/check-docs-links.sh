#!/usr/bin/env bash
#
# check-docs-links.sh — every internal Markdown link must resolve.
#
# WHY: a documentation link that points at a file that no longer exists is a
#      dead end for the reader and silently rots: nobody notices until the
#      moment somebody follows it. The check runs in CI so a rename breaks
#      the build instead of the reader's trust.
#
# WHAT IS CHECKED
#   Every Markdown file in the repository is scanned for
#     * inline links     [text](target)
#     * image links      ![alt](target)
#     * reference links  [label]: target
#   A target is internal when it is a relative path. It must resolve to an
#   existing file or directory, counted from the directory of the Markdown
#   file that contains it. A target that starts with "/" is counted from the
#   repository root.
#
#   Targets that are not checked (by design):
#     * absolute URLs   http://, https://, ftp://, mailto:, tel:, data:
#     * protocol relative URLs  //example.org/page
#     * pure anchors   #section
#   The anchor part of a link (#section) is stripped before the path is
#   resolved; whether the anchor itself exists is not checked.
#
# EXIT CODES
#   0  every internal link resolves
#   1  at least one internal link is broken
#   2  usage error (unknown argument, missing value)
#
# USAGE
#   ./scripts/check-docs-links.sh [options]
#
#   --root DIR      directory to scan (default: the repository root)
#   --ext EXT       file extension to check, repeatable (default: .md)
#   -h, --help      show this help and exit 0
#

set -euo pipefail

ROOT=""
EXTENSIONS=".md"

usage() {
  sed -n '2,45p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
}

die_usage() {
  printf 'ERROR: %s\n' "$1" >&2
  printf 'Try "%s --help" for usage.\n' "$0" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --root)
      [ "$#" -ge 2 ] || die_usage "--root requires a directory argument"
      ROOT="$2"
      shift 2
      ;;
    --root=*)
      ROOT="${1#*=}"
      shift
      ;;
    --ext)
      [ "$#" -ge 2 ] || die_usage "--ext requires a file extension"
      EXTENSIONS="$EXTENSIONS $2"
      shift 2
      ;;
    --ext=*)
      EXTENSIONS="$EXTENSIONS ${1#*=}"
      shift
      ;;
    *)
      die_usage "unknown argument '$1'"
      ;;
  esac
done

if [ -z "$ROOT" ]; then
  SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
  ROOT="$(dirname -- "$SCRIPT_DIR")"
fi
[ -d "$ROOT" ] || die_usage "root directory '$ROOT' does not exist"
ROOT="$(cd -- "$ROOT" && pwd)"
cd -- "$ROOT"

find_args=()
first=1
for ext in $EXTENSIONS; do
  if [ "$first" -eq 1 ]; then
    find_args+=(-name "*${ext}")
    first=0
  else
    find_args+=(-o -name "*${ext}")
  fi
done

# ── the link extractor ───────────────────────────────────────────────────────
#
# One awk program prints "file<TAB>line<TAB>target" for every link it finds.

AWK_PROGRAM="$(cat <<'AWK'
function emit(file, lno, target, t) {
  t = target
  gsub(/^[ \t]+/, "", t)
  gsub(/[ \t]+$/, "", t)
  if (t == "") return
  # strip the optional title:  path "Title"
  if (index(t, " ") > 0) t = substr(t, 1, index(t, " ") - 1)
  # strip angle brackets:  <path with spaces>
  if (t ~ /^</ && t ~ />$/) t = substr(t, 2, length(t) - 2)
  if (t == "") return
  printf "%s\t%d\t%s\n", file, lno, t
}

FNR == 1 { file = FILENAME }

{
  lno = FNR
  s = $0

  # reference definition:  [label]: target
  if (match(s, /^[ \t]*\[[^]]+\]:[ \t]*/)) {
    rest = substr(s, RLENGTH + 1)
    if (match(rest, /^[^ \t]+/)) emit(file, lno, substr(rest, 1, RLENGTH))
  }

  # inline link and image:  [text](target)  /  ![alt](target)
  tmp = s
  while (match(tmp, /\]\(/)) {
    rest = substr(tmp, RSTART + 2)
    if (match(rest, /\)/)) {
      emit(file, lno, substr(rest, 1, RSTART - 1))
      tmp = substr(rest, RSTART + 1)
    } else {
      tmp = rest
    }
  }
}
AWK
)"

# ── collect the links ────────────────────────────────────────────────────────

FILES="$(find . -type f \( "${find_args[@]}" \) \
  -not -path './.git/*' \
  -not -path '*/build/*' \
  -not -path '*/.gradle/*' \
  -not -path '*/node_modules/*' \
  -not -path '*/.idea/*' \
  | sort)"

if [ -z "$FILES" ]; then
  printf 'OK: documentation link check passed — no Markdown files found to check.\n'
  exit 0
fi

LINKS="$(printf '%s\0' $FILES | xargs -0 awk "$AWK_PROGRAM" || true)"

if [ -z "$LINKS" ]; then
  printf 'OK: documentation link check passed — no links found.\n'
  exit 0
fi

# ── resolve the links ───────────────────────────────────────────────────────

# normalize_path collapses "." and ".." without requiring the path to exist.
# A relative path stays relative, an absolute path stays absolute.
normalize_path() {
  local path="$1"
  local out=""
  local part
  local absolute=0
  local old_ifs="$IFS"
  case "$path" in
    /*) absolute=1 ;;
  esac
  IFS='/'
  # shellcheck disable=SC2086
  set -- $path
  IFS="$old_ifs"
  for part in "$@"; do
    case "$part" in
      ''|'.') continue ;;
      '..')
        case "$out" in
          */*) out="${out%/*}" ;;
          *) out="" ;;
        esac
        ;;
      *) out="${out:+$out/}$part" ;;
    esac
  done
  if [ "$absolute" -eq 1 ]; then
    out="/$out"
  fi
  printf '%s' "$out"
}

errors=0
checked=0
skipped=0

while IFS="$(printf '\t')" read -r file line target; do
  [ -n "$file" ] || continue

  case "$target" in
    http://*|https://*|ftp://*|mailto:*|tel:*|data:*|'//'*) skipped=$((skipped + 1)); continue ;;
    '#'*) skipped=$((skipped + 1)); continue ;;
  esac

  path="${target%%#*}"
  [ -n "$path" ] || { skipped=$((skipped + 1)); continue; }
  path="${path//%20/ }"

  case "$path" in
    /*) resolved="$(normalize_path "$ROOT$path")" ;;
    *)  resolved="$(normalize_path "$(dirname -- "$file")/$path")" ;;
  esac

  checked=$((checked + 1))
  if [ ! -e "$resolved" ]; then
    printf 'ERROR: %s:%s — broken internal link "%s" (resolved to "%s", which does not exist).\n' \
      "${file#./}" "$line" "$target" "${resolved#$ROOT/}" >&2
    errors=$((errors + 1))
  fi
done <<EOF
$LINKS
EOF

if [ "$errors" -gt 0 ]; then
  printf 'FAILED: documentation link check — %s broken internal link(s) out of %s internal link(s) checked (%s external link(s) skipped).\n' \
    "$errors" "$checked" "$skipped" >&2
  exit 1
fi

printf 'OK: documentation link check passed — %s internal link(s) resolve, %s external link(s) skipped.\n' \
  "$checked" "$skipped"
exit 0
