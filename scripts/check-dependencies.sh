#!/usr/bin/env bash
#
# check-dependencies.sh — check the dependencies of the build against the
# decisions of the team.
#
# WHY: the version catalog says which version is used, but not whether the
#      dependency itself was ever looked at. This gate closes that gap: it
#      reads the SBOM of the build and checks every component against two
#      files that the team maintains:
#
#        scripts/allowlists/dependency-allowlist.txt
#            the dependencies that were assessed and accepted. A dependency
#            that is not listed has NOT been assessed. The gate reports it as
#            unassessed and does not count it as passed — the decision is
#            still open.
#
#        scripts/allowlists/known-vulnerabilities.txt
#            the version ceiling of the dependencies that carry a known
#            advisory. A dependency above the ceiling is a violation.
#
#      The gate runs offline. It never asks a server what is known about a
#      version; the knowledge lives in the repository, where it can be
#      reviewed.
#
# EXIT CODES
#   0   every dependency is assessed and within its ceiling
#   1   at least one violation, or at least one unassessed dependency
#   2   usage error (unknown argument, missing value)
#   77  skipped — there is no SBOM to read and none could be built
#
# USAGE
#   ./scripts/check-dependencies.sh [options]
#
#   --root DIR             repository root (default: the repository of this script)
#   --sbom PATH            the SBOM to read (default: the newest file in dist/sbom)
#   --allowlist PATH       the allowlist (default: scripts/allowlists/dependency-allowlist.txt)
#   --vulnerabilities PATH the ceilings (default: scripts/allowlists/known-vulnerabilities.txt)
#   --allow-unassessed     report an unassessed dependency as a warning instead
#                          of an error
#   -h, --help             show this help and exit 0
#

set -euo pipefail

ROOT=""
SBOM=""
ALLOWLIST=""
VULNERABILITIES=""
ALLOW_UNASSESSED=0

usage() {
  sed -n '2,52p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
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
      [ "$#" -ge 2 ] || die_usage "--root requires a directory"
      ROOT="$2"
      shift 2
      ;;
    --root=*)
      ROOT="${1#*=}"
      shift
      ;;
    --sbom)
      [ "$#" -ge 2 ] || die_usage "--sbom requires a path"
      SBOM="$2"
      shift 2
      ;;
    --sbom=*)
      SBOM="${1#*=}"
      shift
      ;;
    --allowlist)
      [ "$#" -ge 2 ] || die_usage "--allowlist requires a path"
      ALLOWLIST="$2"
      shift 2
      ;;
    --allowlist=*)
      ALLOWLIST="${1#*=}"
      shift
      ;;
    --vulnerabilities)
      [ "$#" -ge 2 ] || die_usage "--vulnerabilities requires a path"
      VULNERABILITIES="$2"
      shift 2
      ;;
    --vulnerabilities=*)
      VULNERABILITIES="${1#*=}"
      shift
      ;;
    --allow-unassessed)
      ALLOW_UNASSESSED=1
      shift
      ;;
    *)
      die_usage "unknown argument '$1'"
      ;;
  esac
done

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
AWK_COMPONENTS="$SCRIPT_DIR/lib/sbom-components.awk"
BUILD_SBOM="$SCRIPT_DIR/build-sbom.sh"
[ -f "$AWK_COMPONENTS" ] || die_usage "helper '$AWK_COMPONENTS' is missing"

if [ -z "$ROOT" ]; then
  ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
fi
[ -d "$ROOT" ] || die_usage "root directory '$ROOT' does not exist"
cd -- "$ROOT"

if [ -z "$ALLOWLIST" ]; then
  ALLOWLIST="scripts/allowlists/dependency-allowlist.txt"
fi
if [ -z "$VULNERABILITIES" ]; then
  VULNERABILITIES="scripts/allowlists/known-vulnerabilities.txt"
fi
for list in "$ALLOWLIST" "$VULNERABILITIES"; do
  if [ ! -f "$list" ]; then
    printf 'FAILED: %s is missing — without it no dependency can be assessed.\n' "$list" >&2
    exit 1
  fi
done

WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/phairplay-dependencies.XXXXXX")"
cleanup() {
  [ -n "$WORKDIR" ] && rm -rf "$WORKDIR"
}
trap cleanup EXIT
RESULTS="$WORKDIR/results.txt"
PROBLEMS="$WORKDIR/problems.txt"

# ── the SBOM ─────────────────────────────────────────────────────────────────

if [ -z "$SBOM" ]; then
  SBOM="$(ls -1 dist/sbom/*.cdx.json 2>/dev/null | sort | tail -1)"
fi

if [ -z "$SBOM" ] || [ ! -f "$SBOM" ]; then
  if [ ! -x "$BUILD_SBOM" ]; then
    printf 'SKIP: dependency check — there is no SBOM and scripts/build-sbom.sh is missing.\n'
    printf '      A skip is not a pass: no dependency was checked.\n'
    exit 77
  fi
  printf 'No SBOM found — building it first.\n'
  if ! "$BUILD_SBOM" --root "$ROOT"; then
    printf 'SKIP: dependency check — the SBOM could not be built.\n' >&2
    printf '      A skip is not a pass: no dependency was checked.\n' >&2
    exit 77
  fi
  SBOM="$(ls -1 dist/sbom/*.cdx.json 2>/dev/null | sort | tail -1)"
  if [ -z "$SBOM" ] || [ ! -f "$SBOM" ]; then
    printf 'SKIP: dependency check — the SBOM could not be built.\n' >&2
    exit 77
  fi
fi

COMPONENTS="$(awk -f "$AWK_COMPONENTS" "$SBOM")" || {
  printf 'FAILED: %s cannot be read as a CycloneDX document.\n' "$SBOM" >&2
  exit 1
}
if [ -z "$COMPONENTS" ]; then
  printf 'SKIP: dependency check — the SBOM holds no component.\n' >&2
  printf '      A skip is not a pass: no dependency was checked.\n' >&2
  exit 77
fi

printf 'Checking the dependencies of %s\n' "$SBOM"

# ── the allowlist and the version ceilings ──────────────────────────────────

ALLOWED=""
while IFS= read -r line; do
  line="${line%%#*}"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  [ -n "$line" ] || continue
  ALLOWED="$ALLOWED$line
"
done < "$ALLOWLIST"

# The ceilings are read once and kept as a list of
# "<key> <version> <note>" lines. An associative array would be shorter, but
# bash 3.2 does not have one and the scripts have to run on the Mac of the
# owner.
CEILINGS=""
while IFS= read -r line; do
  line="${line%%#*}"
  set -- $line
  [ "$#" -ge 2 ] || continue
  key="$1"
  version="$2"
  shift 2
  CEILINGS="$CEILINGS$key $version $*
"
done < "$VULNERABILITIES"

# find_ceiling KEY — prints "<version>\t<note>" of the dependency, or fails.
find_ceiling() {
  local wanted="$1" line key
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    key="${line%% *}"
    if [ "$key" = "$wanted" ]; then
      printf '%s\n' "${line#* }"
      return 0
    fi
  done <<EOF
$CEILINGS
EOF
  return 1
}

# version_gt A B — is the version A greater than the version B?
version_gt() {
  local lhs="$1" rhs="$2"
  local old_ifs="$IFS"
  local -a left right
  local i count lv rv
  IFS='.'
  left=($lhs)
  right=($rhs)
  IFS="$old_ifs"
  count="${#left[@]}"
  if [ "${#right[@]}" -gt "$count" ]; then
    count="${#right[@]}"
  fi
  for ((i = 0; i < count; i++)); do
    lv="${left[i]:-0}"
    rv="${right[i]:-0}"
    lv="${lv%%[!0-9]*}"
    rv="${rv%%[!0-9]*}"
    [ -n "$lv" ] || lv=0
    [ -n "$rv" ] || rv=0
    if [ "$lv" -gt "$rv" ]; then
      return 0
    fi
    if [ "$lv" -lt "$rv" ]; then
      return 1
    fi
  done
  return 1
}

# list_contains LIST ITEM — does the newline separated list contain the item?
list_contains() {
  local list="$1" item="$2" line
  while IFS= read -r line; do
    if [ "$line" = "$item" ]; then
      return 0
    fi
  done <<EOF
$list
EOF
  return 1
}

TODAY="$(date +%Y-%m-%d)"

# ── every component ──────────────────────────────────────────────────────────

printf '%s\n' "$COMPONENTS" > "$WORKDIR/components.txt"
while IFS= read -r component; do
  [ -n "$component" ] || continue
  group="${component%%:*}"
  rest="${component#*:}"
  artifact="${rest%%:*}"
  version="${rest#*:}"
  key="$group:$artifact"

  if ! list_contains "$ALLOWED" "$key"; then
    {
      printf 'UNASSESSED: %s:%s:%s — the dependency is not in the allowlist, so nobody has assessed it.\n' \
        "$group" "$artifact" "$version"
      printf '             Add it to %s with a reason, or remove it from the build.\n' "$ALLOWLIST"
    } >> "$PROBLEMS"
    continue
  fi

  if ceiling_entry="$(find_ceiling "$key")"; then
    ceiling="${ceiling_entry%% *}"
    note="${ceiling_entry#* }"
    if version_gt "$version" "$ceiling"; then
      {
        printf 'VIOLATION: %s:%s:%s is newer than the accepted version %s.\n' \
          "$group" "$artifact" "$version" "$ceiling"
        printf '            %s\n' "$note"
      } >> "$PROBLEMS"
      continue
    fi
    waiver_date="${note#*WAIVED(}"
    waiver_date="${waiver_date%%)*}"
    case "$waiver_date" in
      [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;;
      *) waiver_date="" ;;
    esac
    if [ -n "$waiver_date" ]; then
      # The dates are ISO, so they can be compared as strings.
      if [[ "$waiver_date" < "$TODAY" ]]; then
        {
          printf 'VIOLATION: %s:%s:%s runs under a waiver that ended on %s.\n' \
            "$group" "$artifact" "$version" "$waiver_date"
          printf '            %s\n' "$note"
        } >> "$PROBLEMS"
      else
        {
          printf 'WAIVED: %s:%s:%s — %s\n' "$group" "$artifact" "$version" "$note"
          printf '        the waiver ends on %s; raise the version or the ceiling before that day.\n' \
            "$waiver_date"
        } >> "$PROBLEMS"
      fi
    fi
  fi

  printf 'OK: %s:%s:%s\n' "$group" "$artifact" "$version" >> "$RESULTS"
done < "$WORKDIR/components.txt"

[ -f "$PROBLEMS" ] || : > "$PROBLEMS"
[ -f "$RESULTS" ] || : > "$RESULTS"

cat "$RESULTS"
cat "$PROBLEMS" >&2

unassessed="$(grep -c '^UNASSESSED' "$PROBLEMS" || true)"
violations="$(grep -c '^VIOLATION' "$PROBLEMS" || true)"
waived="$(grep -c '^WAIVED' "$PROBLEMS" || true)"
checked="$(grep -c '^OK' "$RESULTS" || true)"

# ── the allowlist entries that are no longer used ───────────────────────────

USED_KEYS=""
while IFS= read -r line; do
  key="${line#OK: }"
  key="${key%:*}"
  USED_KEYS="$USED_KEYS$key
"
done < "$RESULTS"

while IFS= read -r key; do
  [ -n "$key" ] || continue
  if ! list_contains "$USED_KEYS" "$key"; then
    printf 'WARNING: %s stands in the allowlist, but no dependency of the build uses it any more\n' "$key" >&2
  fi
done <<EOF
$ALLOWED
EOF

# ── result ───────────────────────────────────────────────────────────────────

printf '\n%s\n' "----------------------------------------"
printf 'checked: %s   unassessed: %s   violations: %s   waived: %s\n' \
  "$checked" "$unassessed" "$violations" "$waived"

if [ "$violations" -gt 0 ]; then
  printf 'FAILED: dependency check — %s violation(s), %s unassessed dependency/ies.\n' \
    "$violations" "$unassessed" >&2
  exit 1
fi

if [ "$unassessed" -gt 0 ] && [ "$ALLOW_UNASSESSED" -eq 0 ]; then
  printf 'FAILED: dependency check — %s dependency/ies have not been assessed.\n' "$unassessed" >&2
  printf '      The decision is still open; it does not count as passed.\n' >&2
  exit 1
fi

if [ "$unassessed" -gt 0 ]; then
  printf 'OK: dependency check passed with %s unassessed dependency/ies — the decision is still open.\n' \
    "$unassessed"
  exit 0
fi

printf 'OK: dependency check passed — %s dependency/ies assessed, %s waived.\n' "$checked" "$waived"
exit 0
