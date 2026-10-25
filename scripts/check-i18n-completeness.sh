#!/usr/bin/env bash
#
# check-i18n-completeness.sh — every language must carry every user-visible string.
#
# WHY: PhairPlay ships for German and French speakers. Android silently falls
#      back to the English base strings for a key that a translation does not
#      have, so a missing translation is invisible in the build and visible
#      on the TV screen. This check makes the gap explicit: a translation file
#      that lacks a key is an error, not a silent fallback.
#
# WHAT IS CHECKED
#   For every language directory app/src/main/res/values-<locale>/:
#     1. every <string name="…"> of the base file app/src/main/res/values/strings.xml
#        exists in the translation;
#     2. the translation has no key the base file does not know (a stale key
#        is a bug: it is never shown and it hides a renamed key);
#     3. the format placeholders (%1$s, %2$d, …) of the base string and of the
#        translation are the same set — a missing placeholder crashes the
#        string formatting at runtime.
#
# EXIT CODES
#   0  every language is complete
#   1  at least one language is incomplete (missing key, stale key or
#      mismatched placeholder)
#   2  usage error (unknown argument, missing value, missing base file)
#
# USAGE
#   ./scripts/check-i18n-completeness.sh [options]
#
#   --root DIR          repository root (default: the repository root)
#   --res-dir DIR       resource directory (default: app/src/main/res)
#   --require LOCALE    locale that must exist, repeatable (default: de, fr)
#   -h, --help          show this help and exit 0
#

set -euo pipefail

ROOT=""
RES_DIR="app/src/main/res"
REQUIRED_LOCALES="de fr"

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
    --res-dir)
      [ "$#" -ge 2 ] || die_usage "--res-dir requires a directory"
      RES_DIR="$2"
      shift 2
      ;;
    --res-dir=*)
      RES_DIR="${1#*=}"
      shift
      ;;
    --require)
      [ "$#" -ge 2 ] || die_usage "--require requires a locale"
      REQUIRED_LOCALES="$REQUIRED_LOCALES $2"
      shift 2
      ;;
    --require=*)
      REQUIRED_LOCALES="$REQUIRED_LOCALES ${1#*=}"
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
cd -- "$ROOT"

BASE_FILE="$RES_DIR/values/strings.xml"
[ -f "$BASE_FILE" ] || die_usage "base string file '$BASE_FILE' not found"

# ── the key extractor ────────────────────────────────────────────────────────
#
# One awk program prints "key<TAB>line<TAB>value" for every <string> element.

IFS= read -r -d '' AWK_PROGRAM <<'AWK' || true
{
  line = $0

  # a value that continues on the next line
  if (pending) {
    if (match(line, /<\/string>/)) {
      value = value " " substr(line, 1, RSTART - 1)
      printf "%s\t%d\t%s\n", pkey, plno, value
      pending = 0
    } else {
      value = value " " line
    }
    next
  }

  if (match(line, /<string[ \t]+name="[^"]*"/)) {
    head = substr(line, RSTART, RLENGTH)
    key = head
    sub(/^<string[ \t]+name="/, "", key)
    sub(/"$/, "", key)
    lno = FNR
    rest = substr(line, RSTART + RLENGTH)
    if (match(rest, />/)) {
      rest = substr(rest, RSTART + 1)
      if (match(rest, /<\/string>/)) {
        printf "%s\t%d\t%s\n", key, lno, substr(rest, 1, RSTART - 1)
      } else {
        pkey = key
        plno = lno
        value = rest
        pending = 1
      }
    }
  }
}
AWK

# ── the comparison ───────────────────────────────────────────────────────────
#
# The first file holds the base keys, the second file the translated keys.

IFS= read -r -d '' COMPARE_PROGRAM <<'AWK' || true
BEGIN { FS = "\t" }

function placeholders(s,   parts, n, i, token, list) {
  n = split(s, parts, /%/)
  list = ""
  for (i = 2; i <= n; i++) {
    if (match(parts[i], /^[0-9]+\$[a-zA-Z]/)) {
      token = substr(parts[i], 1, RLENGTH)
      if (index("|" list "|", "|" token "|") == 0) list = list token "|"
    }
  }
  return list
}

FILENAME == ARGV[1] {
  base_line[$1] = $2
  base_value[$1] = $3
  base_count++
  next
}

{
  if (!($1 in base_line)) {
    printf "STALE-KEY\t%s:%s\tthe key \"%s\" does not exist in the base file %s.\n", locale_file, $2, $1, base_file
    errors++
    next
  }
  seen[$1] = 1
  expected = placeholders(base_value[$1])
  found = placeholders($3)
  if (expected != found) {
    printf "PLACEHOLDER\t%s:%s\tthe key \"%s\" has the placeholders \"%s\" but the base file expects \"%s\".\n", \
      locale_file, $2, $1, found, expected
    errors++
  }
}

END {
  # report the missing keys in the order of the base file, not in hash order
  n = 0
  for (key in base_line) {
    if (!(key in seen)) missing[++n] = key
  }
  for (i = 2; i <= n; i++) {
    k = missing[i]
    l = base_line[k]
    j = i - 1
    while (j >= 1 && base_line[missing[j]] > l) {
      missing[j + 1] = missing[j]
      j--
    }
    missing[j + 1] = k
  }
  for (i = 1; i <= n; i++) {
    key = missing[i]
    printf "MISSING-KEY\t%s:%s\tthe key \"%s\" of the base file is missing in this translation.\n", base_file, base_line[key], key
    errors++
  }
  printf "### base=%d errors=%d\n", base_count, errors
}
AWK

TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

extract_keys() {
  awk "$AWK_PROGRAM" "$1"
}

BASE_KEYS="$TMP_DIR/base.keys"
extract_keys "$BASE_FILE" > "$BASE_KEYS"

BASE_COUNT="$(wc -l < "$BASE_KEYS" | tr -d ' ')"
[ "$BASE_COUNT" -gt 0 ] || die_usage "no <string> element found in '$BASE_FILE'"

errors=0
languages=0

# ── the required locales must exist ──────────────────────────────────────────

for locale in $REQUIRED_LOCALES; do
  locale_dir="$RES_DIR/values-$locale"
  if [ ! -d "$locale_dir" ]; then
    printf 'ERROR: %s — the language directory "%s" does not exist.\n' "$BASE_FILE" "$locale_dir" >&2
    errors=$((errors + 1))
  elif [ ! -f "$locale_dir/strings.xml" ]; then
    printf 'ERROR: %s — the language directory "%s" has no strings.xml.\n' "$BASE_FILE" "$locale_dir" >&2
    errors=$((errors + 1))
  fi
done

# ── compare every locale that exists ─────────────────────────────────────────

for locale_dir in "$RES_DIR"/values-*; do
  [ -d "$locale_dir" ] || continue
  locale="$(basename "$locale_dir")"
  locale="${locale#values-}"
  locale_file="$locale_dir/strings.xml"
  [ -f "$locale_file" ] || continue

  languages=$((languages + 1))
  locale_keys="$TMP_DIR/$locale.keys"
  extract_keys "$locale_file" > "$locale_keys"

  report="$(awk -v base_file="$BASE_FILE" -v locale_file="$locale_file" "$COMPARE_PROGRAM" "$BASE_KEYS" "$locale_keys" || true)"
  printf '%s\n' "$report" | grep -v '^### ' | grep -v '^$' > "$TMP_DIR/$locale.report" || true
  while IFS="$(printf '\t')" read -r kind where message; do
    [ -n "$kind" ] || continue
    printf 'ERROR: %s — %s\n' "$where" "$message" >&2
  done < "$TMP_DIR/$locale.report"
  locale_errors="$(printf '%s\n' "$report" | sed -n 's/^### .*errors=\([0-9]*\).*/\1/p')"
  locale_errors="${locale_errors:-0}"
  errors=$((errors + locale_errors))
done

if [ "$errors" -gt 0 ]; then
  printf 'FAILED: i18n completeness check — %s problem(s) across %s language(s); the base file has %s keys.\n' \
    "$errors" "$languages" "$BASE_COUNT" >&2
  exit 1
fi

printf 'OK: i18n completeness check passed — %s key(s) in %s, complete in %s language(s).\n' \
  "$BASE_COUNT" "$BASE_FILE" "$languages"
exit 0
