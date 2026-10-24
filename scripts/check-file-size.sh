#!/usr/bin/env bash
#
# check-file-size.sh — enforce the PhairPlay source file size rule.
#
# WHY: NFR-23 and docs/CONTRIBUTING.md rule 1 forbid oversized source files.
#      A file that grows past the limit is hard to review, hard to split and
#      usually hides more than one responsibility. The check therefore runs in
#      CI instead of during a code review, where it is cheap to enforce.
#
# LIMITS (PhairPlay is stricter than the reference project Vocument, which
# uses 600/900 — the stricter rule wins, see docs/CONTRIBUTING.md rule 1):
#
#   soft limit  400 lines  -> reported as WARNING, does not fail the run
#   hard limit  550 lines  -> reported as ERROR, fails the run (exit 1)
#
# The soft limit exists because a handful of files are being split in the
# current refactoring cycle. The hard limit is the absolute ceiling: a file
# above it must be split before it is merged.
#
# EXIT CODES
#   0  no hard violation (soft-limit warnings may still be printed)
#   1  at least one file is above the hard limit
#      (or above the soft limit when --strict is given)
#   2  usage error (unknown argument, missing value)
#
# USAGE
#   ./scripts/check-file-size.sh [options]
#
#   --root DIR        directory to scan (default: the repository root)
#   --soft-limit N    soft limit in lines (default: 400)
#   --hard-limit N    hard limit in lines (default: 550)
#   --ext EXT         file extension to check, repeatable (default: .kt)
#   --strict          treat soft-limit violations as errors as well
#   -h, --help        show this help and exit 0
#
# EXAMPLES
#   ./scripts/check-file-size.sh                 # check every .kt file
#   ./scripts/check-file-size.sh --strict        # fail on > 400 lines too
#   ./scripts/check-file-size.sh --ext .kt --ext .sh
#

set -euo pipefail

SOFT_LIMIT=400
HARD_LIMIT=550
STRICT=0
ROOT=""
EXTENSIONS=".kt"

usage() {
  sed -n '2,40p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
}

die_usage() {
  printf 'ERROR: %s\n' "$1" >&2
  printf 'Try "%s --help" for usage.\n' "$0" >&2
  exit 2
}

# ── argument parsing ─────────────────────────────────────────────────────────

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --strict)
      STRICT=1
      shift
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
    --soft-limit)
      [ "$#" -ge 2 ] || die_usage "--soft-limit requires a number"
      SOFT_LIMIT="$2"
      shift 2
      ;;
    --soft-limit=*)
      SOFT_LIMIT="${1#*=}"
      shift
      ;;
    --hard-limit)
      [ "$#" -ge 2 ] || die_usage "--hard-limit requires a number"
      HARD_LIMIT="$2"
      shift 2
      ;;
    --hard-limit=*)
      HARD_LIMIT="${1#*=}"
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

case "$SOFT_LIMIT" in
  ''|*[!0-9]*) die_usage "soft limit must be a positive number, got '$SOFT_LIMIT'" ;;
esac
case "$HARD_LIMIT" in
  ''|*[!0-9]*) die_usage "hard limit must be a positive number, got '$HARD_LIMIT'" ;;
esac
if [ "$HARD_LIMIT" -le "$SOFT_LIMIT" ]; then
  die_usage "hard limit ($HARD_LIMIT) must be greater than the soft limit ($SOFT_LIMIT)"
fi

# ── locate the repository ────────────────────────────────────────────────────

if [ -z "$ROOT" ]; then
  SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
  ROOT="$(dirname -- "$SCRIPT_DIR")"
fi
[ -d "$ROOT" ] || die_usage "root directory '$ROOT' does not exist"
cd -- "$ROOT"

# ── scan ─────────────────────────────────────────────────────────────────────

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

checked=0
errors=0
warnings=0

while IFS= read -r file; do
  [ -n "$file" ] || continue
  # awk counts a trailing line without a newline character, wc -l does not.
  lines="$(awk 'END { print NR }' "$file")"
  checked=$((checked + 1))
  if [ "$lines" -gt "$HARD_LIMIT" ]; then
    printf 'ERROR: %s:%s — %s lines exceed the hard limit of %s lines (NFR-23, docs/CONTRIBUTING.md rule 1). Split the file before merging.\n' \
      "${file#./}" "$lines" "$lines" "$HARD_LIMIT" >&2
    errors=$((errors + 1))
  elif [ "$lines" -gt "$SOFT_LIMIT" ]; then
    printf 'WARNING: %s:%s — %s lines exceed the soft limit of %s lines (docs/CONTRIBUTING.md rule 1). Split the file.\n' \
      "${file#./}" "$lines" "$lines" "$SOFT_LIMIT" >&2
    warnings=$((warnings + 1))
  fi
done < <(find . -type f \( "${find_args[@]}" \) \
  -not -path './.git/*' \
  -not -path '*/build/*' \
  -not -path '*/.gradle/*' \
  -not -path '*/node_modules/*' \
  -not -path '*/.idea/*' \
  | sort)

# ── result ───────────────────────────────────────────────────────────────────

if [ "$errors" -gt 0 ]; then
  printf 'FAILED: file size check — %s file(s) above the hard limit of %s lines, %s warning(s) above the soft limit of %s lines (%s file(s) checked).\n' \
    "$errors" "$HARD_LIMIT" "$warnings" "$SOFT_LIMIT" "$checked" >&2
  exit 1
fi

if [ "$STRICT" -eq 1 ] && [ "$warnings" -gt 0 ]; then
  printf 'FAILED: file size check (--strict) — %s file(s) above the soft limit of %s lines, %s above the hard limit of %s lines (%s file(s) checked).\n' \
    "$warnings" "$SOFT_LIMIT" "$errors" "$HARD_LIMIT" "$checked" >&2
  exit 1
fi

printf 'OK: file size check passed — %s file(s) checked, none above the hard limit of %s lines, %s warning(s) above the soft limit of %s lines.\n' \
  "$checked" "$HARD_LIMIT" "$warnings" "$SOFT_LIMIT"
exit 0
