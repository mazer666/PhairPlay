#!/usr/bin/env bash
#
# test-check-secrets.sh — the self-test of scripts/check-secrets.sh.
#
# WHY: a gate that can never turn red is worse than no gate: it gives the
#      impression that the repository is free of credentials, while it checks
#      nothing. Every case below therefore proves that the gate turns RED on
#      a committed credential — and that it turns green again afterwards.
#
#      The cases run in a throwaway git repository, so the test never touches
#      the index of the real repository.
#
# WHAT IS PROVEN
#   1. a repository without a credential passes the gate
#   2. a committed private key turns the gate red — and the key material does
#      not appear in the output
#   3. a committed token turns the gate red — and the token does not appear
#      in the output
#   4. a credential that is assigned a literal value turns the gate red — and
#      the value does not appear in the output
#   5. a committed keystore file turns the gate red
#   6. a committed local.properties turns the gate red
#   7. a missing ignore rule turns the gate red
#   8. after the credential is removed the gate is green again
#   9. the real repository of the working tree passes the gate
#
# USAGE
#   ./scripts/test-check-secrets.sh
#
# EXIT CODES
#   0  every case passed
#   1  at least one case failed
#   2  the test itself cannot run (missing tool, missing script)
#

set -u

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
CHECK_SCRIPT="$SCRIPT_DIR/check-secrets.sh"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

if [ ! -x "$CHECK_SCRIPT" ]; then
  printf 'ERROR: %s is missing or not executable.\n' "$CHECK_SCRIPT" >&2
  exit 2
fi
if ! command -v git >/dev/null 2>&1; then
  printf 'ERROR: the test needs git.\n' >&2
  exit 2
fi

passed=0
failed=0
workdir=""
out=""
status=0

cleanup() {
  [ -n "$workdir" ] && rm -rf "$workdir"
}
trap cleanup EXIT

ok() {
  passed=$((passed + 1))
  printf '  ok   %s\n' "$1"
}

bad() {
  failed=$((failed + 1))
  printf '  FAIL %s\n' "$1"
}

# run_gate — runs the gate in the throwaway repository, stores the output in
# $out and the exit code in $status.
run_gate() {
  out="$(cd "$workdir/repo" && "$workdir/repo/scripts/check-secrets.sh" \
         --root "$workdir/repo" 2>&1)"
  status=$?
}

workdir="$(mktemp -d "${TMPDIR:-/tmp}/phairplay-secrets-test.XXXXXX")" || exit 2
mkdir -p "$workdir/repo/scripts/lib"
cp "$CHECK_SCRIPT" "$workdir/repo/scripts/check-secrets.sh"
cp "$SCRIPT_DIR/lib/redact-secret.awk" "$workdir/repo/scripts/lib/redact-secret.awk"
chmod +x "$workdir/repo/scripts/check-secrets.sh"

cd "$workdir/repo" || exit 2
git init -q . >/dev/null 2>&1 || exit 2
git config user.email "gate@example.invalid"
git config user.name "Gate Test"
cat > .gitignore <<'IGNORE'
local.properties
*.jks
*.keystore
IGNORE
printf 'nothing to see here\n' > README.md
git add . >/dev/null 2>&1

printf 'test-check-secrets.sh\n'

# 1. a repository without a credential passes
run_gate
if [ "$status" -eq 0 ]; then
  ok "a repository without a credential passes the gate"
else
  bad "a repository without a credential passes the gate (exit $status: $out)"
fi

# 2. a committed private key turns the gate red
KEY_BODY="MIIEvQIBADANBgkqhkiG9w0BAQEFAASC"
# The PEM header is assembled from fragments, so that this file does not
# contain the literal header and trip the scanner when it scans itself.
KEY_HEADER="-----BEGIN $(printf 'PRIVATE KEY')-----"
printf '%s\n%s\n%s\n' "$KEY_HEADER" "$KEY_BODY" "$KEY_HEADER" > credentials.txt
git add credentials.txt >/dev/null 2>&1
run_gate
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"private-key"*) ok "a committed private key turns the gate red" ;;
    *) bad "a committed private key turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a committed private key turns the gate red (exit $status)"
fi
case "$out" in
  *"$KEY_BODY"*) bad "the private key material stays out of the output" ;;
  *) ok "the private key material stays out of the output" ;;
esac

# 3. a committed token turns the gate red
# The dummy token is assembled from fragments, so that this file does not
# contain a literal token and trip the scanner when it scans itself.
TOKEN="ghp_$(printf 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789')"
printf 'token = %s\n' "$TOKEN" > token.txt
git add token.txt >/dev/null 2>&1
run_gate
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"github-token"*) ok "a committed token turns the gate red" ;;
    *) bad "a committed token turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a committed token turns the gate red (exit $status)"
fi
case "$out" in
  *"$TOKEN"*) bad "the token stays out of the output" ;;
  *) ok "the token stays out of the output" ;;
esac

# 4. a credential that is assigned a literal value turns the gate red
LITERAL="s3cr3t-value-42"
printf 'password = "%s"\n' "$LITERAL" > literal.txt
git add literal.txt >/dev/null 2>&1
run_gate
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"credential-assignment"*) ok "a credential assigned a literal value turns the gate red" ;;
    *) bad "a credential assigned a literal value turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a credential assigned a literal value turns the gate red (exit $status)"
fi
case "$out" in
  *"$LITERAL"*) bad "the literal value stays out of the output" ;;
  *) ok "the literal value stays out of the output" ;;
esac

# 5. a committed keystore file turns the gate red
printf 'not a real keystore' > release.jks
git add -f release.jks >/dev/null 2>&1
run_gate
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"keystore-file"*) ok "a committed keystore file turns the gate red" ;;
    *) bad "a committed keystore file turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a committed keystore file turns the gate red (exit $status)"
fi

# 6. a committed local.properties turns the gate red
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
git add -f local.properties >/dev/null 2>&1
run_gate
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"is versioned"*) ok "a committed local.properties turns the gate red" ;;
    *) bad "a committed local.properties turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a committed local.properties turns the gate red (exit $status)"
fi

# 7. a missing ignore rule turns the gate red
rm -f local.properties
git rm -q --cached local.properties >/dev/null 2>&1
printf 'build/\n' > .gitignore
git add .gitignore >/dev/null 2>&1
run_gate
if [ "$status" -eq 1 ]; then
  case "$out" in
    *".gitignore does not contain"*) ok "a missing ignore rule turns the gate red" ;;
    *) bad "a missing ignore rule turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a missing ignore rule turns the gate red (exit $status)"
fi

# 8. after the credential is removed the gate is green again
cd "$workdir/repo" || exit 2
rm -f credentials.txt token.txt literal.txt release.jks
cat > .gitignore <<'IGNORE'
local.properties
*.jks
*.keystore
IGNORE
git add -A >/dev/null 2>&1
run_gate
if [ "$status" -eq 0 ]; then
  ok "after the credential is removed the gate is green again"
else
  bad "after the credential is removed the gate is green again (exit $status: $out)"
fi

# 9. the real repository of the working tree passes the gate
out="$("$CHECK_SCRIPT" --root "$REPO_ROOT" 2>&1)"
status=$?
if [ "$status" -eq 0 ]; then
  ok "the real repository of the working tree passes the gate"
else
  bad "the real repository of the working tree passes the gate (exit $status: $out)"
fi

printf '\n%s\n' "----------------------------------------"
printf 'passed: %s   failed: %s\n' "$passed" "$failed"

if [ "$failed" -ne 0 ]; then
  printf 'RESULT: the secret scan is not trustworthy.\n'
  exit 1
fi

printf 'RESULT: every case passed.\n'
exit 0
