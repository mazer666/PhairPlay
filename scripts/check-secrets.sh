#!/usr/bin/env bash
#
# check-secrets.sh — scan the working tree for credentials that must not be
# committed.
#
# WHY: a signing key, a personal access token or a keystore in the history of
#      the repository is a release blocker: it is read by everyone who clones
#      it, and the credentials have to be rotated afterwards. The scan runs in
#      CI so that the mistake is caught by the push, not by a customer.
#
# WHAT IS CHECKED
#   1. private keys in versioned files
#        the PEM header of a private key, with and without the RSA, EC, DSA
#        and OPENSSH prefix
#   2. keystore files in the repository
#        *.jks, *.keystore, *.p12, *.pfx
#   3. token patterns in versioned files
#        GitHub personal access tokens, OpenAI style keys, AWS access key ids,
#        Google API keys, Slack tokens
#   4. assignments of a credential to a literal value
#        password = "...", api_key = "...", secret = "..." and their variants
#   5. the ignore rules: local.properties and the keystore patterns have to be
#      in .gitignore, and no such file may be versioned
#
# WHAT IS NOT PRINTED
#   The script never prints a secret. Every finding is reported as
#   "<file>:<line> — <rule> — <redacted preview>", where the preview shows at
#   most the first characters of the match and replaces the rest with a
#   marker. The content of the line is never echoed.
#
# EXIT CODES
#   0   no secret found
#   1   at least one secret found
#   2   usage error (unknown argument, missing value)
#   77  skipped — there is nothing to scan (a skip is never a pass)
#
# USAGE
#   ./scripts/check-secrets.sh [options]
#
#   --root DIR      directory to scan (default: the repository of this script)
#   -h, --help      show this help and exit 0
#

set -euo pipefail

ROOT=""

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
      [ "$#" -ge 2 ] || die_usage "--root requires a directory"
      ROOT="$2"
      shift 2
      ;;
    --root=*)
      ROOT="${1#*=}"
      shift
      ;;
    *)
      die_usage "unknown argument '$1'"
      ;;
  esac
done

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
AWK_REDACT="$SCRIPT_DIR/lib/redact-secret.awk"
[ -f "$AWK_REDACT" ] || die_usage "helper '$AWK_REDACT' is missing"

if [ -z "$ROOT" ]; then
  ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
fi
[ -d "$ROOT" ] || die_usage "root directory '$ROOT' does not exist"
cd -- "$ROOT"

errors=0
warnings=0

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  errors=$((errors + 1))
}

warn() {
  printf 'WARNING: %s\n' "$1" >&2
  warnings=$((warnings + 1))
}

# ── the list of files to scan ────────────────────────────────────────────────
#
# Only versioned files are scanned: a secret that is not committed cannot
# leak through the repository. Untracked files are the developer's business.
#
# The list is kept in a file, because the shell cannot hold NUL bytes in a
# variable and a file name may contain a space.

FILE_LIST="$(mktemp "${TMPDIR:-/tmp}/phairplay-secrets.XXXXXX")"
cleanup() {
  [ -n "$FILE_LIST" ] && rm -f "$FILE_LIST"
}
trap cleanup EXIT

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git ls-files -z > "$FILE_LIST"
else
  warn "not a git repository — every file below the root is scanned instead"
  find . -type f -not -path './.git/*' -not -path '*/build/*' \
    -not -path '*/.gradle/*' -not -path '*/node_modules/*' -print0 > "$FILE_LIST"
fi

scanned="$(tr -cd '\0' < "$FILE_LIST" | wc -c | tr -d ' ')"
if [ "$scanned" -eq 0 ]; then
  printf 'SKIP: secret scan — there is no versioned file to scan.\n'
  printf '      A skip is not a pass: nothing was scanned.\n'
  exit 77
fi
printf 'Scanning %s versioned file(s).\n' "$scanned"

# ── rule 1 and 3: patterns that match the secret itself ─────────────────────
#
# The patterns are assembled from fragments on purpose: written out in one
# piece, the scanner would match its own source code and report a secret in
# scripts/check-secrets.sh on every run.

PRIVATE_KEY_PATTERN="-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"
GITHUB_TOKEN_PATTERN="(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}"
GITHUB_PAT_PATTERN="github_pat_[A-Za-z0-9_]{50,}"
OPENAI_TOKEN_PATTERN="sk-[A-Za-z0-9]{20,}"
AWS_KEY_PATTERN="AKIA[0-9A-Z]{16}"
GOOGLE_API_KEY_PATTERN="AIza[0-9A-Za-z_-]{35}"
SLACK_TOKEN_PATTERN="xox[baprs]-[A-Za-z0-9-]{10,}"
ASSIGNMENT_PATTERN="(password|passwd|pwd|api[_-]?key|apikey|access[_-]?key|client[_-]?secret|auth[_-]?token|secret|token)[[:space:]]*[:=][[:space:]]*[\"'][^\"']{6,}[\"']"

# check_pattern ID DESCRIPTION PATTERN
#
# Prints one redacted line per hit and counts them.
check_pattern() {
  local id="$1" description="$2" pattern="$3" hits findings count
  hits="$(xargs -0 grep -nEI -- "$pattern" < "$FILE_LIST" 2>/dev/null || true)"
  [ -n "$hits" ] || return 0
  findings="$(printf '%s\n' "$hits" \
    | SECRET_ID="$id" SECRET_DESC="$description" SECRET_PATTERN="$pattern" \
      awk -f "$AWK_REDACT")"
  count="$(printf '%s' "$findings" | grep -c . || true)"
  if [ "$count" -gt 0 ]; then
    printf '%s\n' "$findings" >&2
  fi
  errors=$((errors + count))
}

check_pattern "private-key" "a private key is committed" "$PRIVATE_KEY_PATTERN"
check_pattern "github-token" "a GitHub token is committed" "$GITHUB_TOKEN_PATTERN"
check_pattern "github-pat" "a GitHub fine-grained token is committed" "$GITHUB_PAT_PATTERN"
check_pattern "openai-key" "an OpenAI style API key is committed" "$OPENAI_TOKEN_PATTERN"
check_pattern "aws-key" "an AWS access key id is committed" "$AWS_KEY_PATTERN"
check_pattern "google-api-key" "a Google API key is committed" "$GOOGLE_API_KEY_PATTERN"
check_pattern "slack-token" "a Slack token is committed" "$SLACK_TOKEN_PATTERN"
check_pattern "credential-assignment" "a credential is assigned a literal value" "$ASSIGNMENT_PATTERN"

# ── rule 2: keystore files in the repository ─────────────────────────────────

keystore_hits=""
for suffix in jks keystore p12 pfx; do
  found="$(xargs -0 -n 50 sh -c \
    'for f in "$@"; do case "$f" in *.'"$suffix"') printf "%s\n" "$f";; esac; done' _ \
    < "$FILE_LIST" 2>/dev/null || true)"
  [ -n "$found" ] || continue
  keystore_hits="$keystore_hits$found
"
done

if [ -n "$keystore_hits" ]; then
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    fail "$file — a keystore or key store file is versioned (rule keystore-file)"
  done <<EOF
$keystore_hits
EOF
fi

# ── rule 5: the ignore rules ────────────────────────────────────────────────

if [ ! -f .gitignore ]; then
  fail ".gitignore is missing — local.properties and the keystores would be committed"
else
  for pattern in "local.properties" "*.jks" "*.keystore"; do
    if ! grep -qxF "$pattern" .gitignore; then
      fail ".gitignore does not contain the line '$pattern' — the file would be committed by accident"
    fi
  done
fi

for tracked in local.properties keystore.properties release.properties release.env; do
  if git ls-files --error-unmatch "$tracked" >/dev/null 2>&1; then
    fail "$tracked is versioned — it belongs in .gitignore and not in the repository"
  fi
done

# ── result ───────────────────────────────────────────────────────────────────

if [ "$errors" -gt 0 ]; then
  printf '\nFAILED: secret scan — %s finding(s) in %s scanned file(s), %s warning(s).\n' \
    "$errors" "$scanned" "$warnings" >&2
  printf '      Rotate every credential that was committed and remove it from the history.\n' >&2
  exit 1
fi

printf '\nOK: secret scan passed — %s file(s) scanned, no credential found, %s warning(s).\n' \
  "$scanned" "$warnings"
exit 0
