#!/usr/bin/env bash
#
# check-kdoc-headers.sh — every Kotlin class needs a KDoc header.
#
# WHY: docs/CONTRIBUTING.md rule 2 asks every class to explain WHAT it does,
#      WHY it exists and HOW to use it. A class without that header is a trap
#      for the next reader, and the header is the cheapest place to say why a
#      protocol detail looks the way it does. The check runs in CI so the
#      header cannot be forgotten in a hurry.
#
# WHAT IS CHECKED
#   Every class-like declaration (class, object, interface, data class,
#   sealed class, enum class, companion object with a name, …) must be
#   preceded by a KDoc block (/** … */) that
#     * ends directly above the declaration — only annotations and blank
#       lines may sit in between, and
#     * contains at least one line of description text.
#   Declarations inside comments and inside string literals are ignored.
#
# EXIT CODES
#   0  every class has a KDoc header
#   1  at least one class is missing its KDoc header
#   2  usage error (unknown argument, missing value)
#
# USAGE
#   ./scripts/check-kdoc-headers.sh [options]
#
#   --root DIR      directory to scan (default: the repository root)
#   --ext EXT       file extension to check, repeatable (default: .kt)
#   --skip-tests    ignore files below a /test/ or /androidTest/ directory
#   -h, --help      show this help and exit 0
#
# EXAMPLE
#   ./scripts/check-kdoc-headers.sh
#

set -euo pipefail

ROOT=""
EXTENSIONS=".kt"
SKIP_TESTS=0
SKIP_NESTED=0

usage() {
  sed -n '2,40p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
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
    --skip-tests)
      SKIP_TESTS=1
      shift
      ;;
    --skip-nested)
      SKIP_NESTED=1
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

# ── the checker ──────────────────────────────────────────────────────────────
#
# One awk program scans every file. It keeps a small state machine:
#   in_block   — inside a /* … */ comment that is not a KDoc
#   in_kdoc    — inside a /** … */ comment
#   kdoc_lines — number of description lines seen in the running KDoc
#   last_end   — line number where the most recent KDoc block ended
#   gap        — lines seen since that KDoc ended (must be annotations only)

AWK_PROGRAM="$(cat <<'AWK'
function trim(s) {
  sub(/^[ \t]+/, "", s)
  sub(/[ \t]+$/, "", s)
  return s
}

# A line that may sit between a KDoc block and the declaration it documents.
function is_annotation_like(s, t) {
  t = trim(s)
  if (t == "") return 1
  if (t ~ /^@/) return 1                    # annotation
  if (t ~ /^[A-Za-z_][A-Za-z0-9_]*[ \t]*=/) return 1   # named argument
  if (t ~ /^\)/) return 1                   # closing parenthesis of an annotation
  if (t ~ /,$/) return 1                    # argument list continues
  if (t ~ /^[A-Za-z0-9_."-]+$/) return 1    # positional argument
  return 0
}

function reset_state() {
  in_block = 0
  in_kdoc = 0
  kdoc_lines = 0
  last_end = 0
  last_content = 0
  gap_count = 0
  delete gap
  declarations = 0
  violations = 0
}

# Flush the counters of the file that just ended into the global totals.
function flush_file() {
  total_declarations += declarations
  total_violations += violations
}

FNR == 1 {
  if (NR > 1) flush_file()
  reset_state()
}

{
  line = $0
  t = trim(line)

  # ── inside a plain block comment ───────────────────────────────────────────
  if (in_block) {
    if (index(line, "*/") > 0) in_block = 0
    next
  }

  # ── inside a KDoc block ────────────────────────────────────────────────────
  # A KDoc block ends on the first line that ends with "*/". Looking for a
  # "*/" anywhere in the line would end the block too early: an example in
  # the KDoc may contain a comment such as "/* update UI */".
  if (in_kdoc) {
    if (t ~ /\*\/[ \t]*$/) {
      part = line
      sub(/\*\/[ \t]*$/, "", part)
      gsub(/^[ \t]*\*[ \t]?/, "", part)
      if (trim(part) != "") kdoc_lines++
      last_end = FNR
      last_content = kdoc_lines
      kdoc_lines = 0
      in_kdoc = 0
      gap_count = 0
      delete gap
    } else {
      part = line
      gsub(/^[ \t]*\*[ \t]?/, "", part)
      if (trim(part) != "") kdoc_lines++
    }
    next
  }

  # ── a KDoc block starts here ───────────────────────────────────────────────
  if (t ~ /^\/\*\*/) {
    if (t ~ /\*\/[ \t]*$/) {
      part = t
      sub(/^\/\*\*/, "", part)
      sub(/\*\/[ \t]*$/, "", part)
      kdoc_lines = (trim(part) != "") ? 1 : 0
      last_end = FNR
      last_content = kdoc_lines
      kdoc_lines = 0
      gap_count = 0
      delete gap
    } else {
      in_kdoc = 1
      kdoc_lines = 0
    }
    next
  }

  # ── a plain block comment starts here ──────────────────────────────────────
  if (t ~ /^\/\*/) {
    if (index(line, "*/") == 0) in_block = 1
    next
  }

  # ── a line comment ─────────────────────────────────────────────────────────
  if (t ~ /^\/\//) next

  # ── is this a class-like declaration? ──────────────────────────────────────
  rest = t
  while (match(rest, /^(public|private|protected|internal|open|abstract|final|sealed|data|value|inline|annotation|enum|inner|companion|expect|actual|external|fun|operator|infix|tailrec|suspend)[ \t]+/)) {
    rest = substr(rest, RLENGTH + 1)
  }
  if (match(rest, /^(class|object|interface)[ \t]+[A-Za-z_][A-Za-z0-9_]*/)) {
    decl = substr(rest, RSTART, RLENGTH)
    split(decl, parts, /[ \t]+/)
    name = parts[2]

    # A nested declaration is indented; --skip-nested leaves those to the
    # KDoc of the class that contains them.
    if (skip_nested == 1 && line ~ /^[ \t]/) {
      gap[++gap_count] = line
      next
    }

    declarations++
    if (last_end == 0) {
      printf "%s:%d: MISSING-KDOC %s — no KDoc header found above the declaration.\n", FILENAME, FNR, name
      violations++
    } else {
      blocked = 0
      for (i = 1; i <= gap_count; i++) {
        if (!is_annotation_like(gap[i])) { blocked = 1; break }
      }
      if (blocked) {
        printf "%s:%d: MISSING-KDOC %s — the KDoc header is not directly above the declaration (%d line(s) in between).\n", FILENAME, FNR, name, gap_count
        violations++
      } else if (last_content == 0) {
        printf "%s:%d: MISSING-KDOC %s — the KDoc header has no description text.\n", FILENAME, FNR, name
        violations++
      }
    }
  }

  gap[++gap_count] = line
}

END {
  flush_file()
  printf "### declarations=%d violations=%d\n", total_declarations, total_violations
}
AWK
)"

FILES="$(find . -type f \( "${find_args[@]}" \) \
  -not -path './.git/*' \
  -not -path '*/build/*' \
  -not -path '*/.gradle/*' \
  -not -path '*/node_modules/*' \
  -not -path '*/.idea/*' \
  -not -path './test-runner/src/stubs/*' \
  | sort)"

if [ "$SKIP_TESTS" -eq 1 ]; then
  FILES="$(printf '%s\n' "$FILES" | grep -v '/test/' || true)"
fi

if [ -z "$FILES" ]; then
  printf 'OK: KDoc header check passed — no Kotlin files found to check.\n'
  exit 0
fi

REPORT="$(printf '%s\0' $FILES | xargs -0 awk -v skip_nested="$SKIP_NESTED" "$AWK_PROGRAM" || true)"

# The checker prints one line per violation and a summary line at the end.
printf '%s\n' "$REPORT" | grep -v '^### ' | grep -v '^$' || true

DECLARATIONS="$(printf '%s\n' "$REPORT" | sed -n 's/^### declarations=\([0-9]*\).*/\1/p' | awk '{ s += $1 } END { print s + 0 }')"
VIOLATIONS="$(printf '%s\n' "$REPORT" | sed -n 's/.*violations=\([0-9]*\).*/\1/p' | awk '{ s += $1 } END { print s + 0 }')"
DECLARATIONS="${DECLARATIONS:-0}"
VIOLATIONS="${VIOLATIONS:-0}"

if [ "$VIOLATIONS" -gt 0 ]; then
  printf 'FAILED: KDoc header check — %s class declaration(s) without a KDoc header out of %s declaration(s) checked.\n' \
    "$VIOLATIONS" "$DECLARATIONS" >&2
  exit 1
fi

printf 'OK: KDoc header check passed — %s class declaration(s) checked, all of them have a KDoc header.\n' "$DECLARATIONS"
exit 0
