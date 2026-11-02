#!/usr/bin/env bash
#
# test-check-privacy-policy.sh — the self-test of scripts/check-privacy-policy.sh.
#
# WHY: a gate that can never turn red is worse than no gate: it gives the
#      impression that the privacy policy is covered, while it checks nothing.
#      Google Play reviews the policy against the app, so a gate that passes
#      for the wrong reason costs a rejected release. Every case below therefore
#      proves that the gate turns RED on a broken input — and that it turns
#      green again on the real tree.
#
# WHAT IS PROVEN
#   1. the real tree passes
#   2. a permission that the policy does not mention -> the gate fails
#   3. a permission that is declared over several lines is found as well
#      (a single-line parser would skip it — and pass everything)
#   4. a policy without the sentence about the entry inside the app -> fails
#   5. a policy that is older than the manifest -> fails
#   6. a policy without the "Last updated" line -> fails
#
# USAGE
#   ./scripts/test-check-privacy-policy.sh
#
# EXIT CODES
#   0  every case passed
#   1  at least one case failed
#

set -u

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
CHECK_SCRIPT="$SCRIPT_DIR/check-privacy-policy.sh"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

if [ ! -x "$CHECK_SCRIPT" ]; then
  printf 'ERROR: %s is missing or not executable.\n' "$CHECK_SCRIPT" >&2
  exit 2
fi

passed=0
failed=0
workdir=""

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

# Runs the gate against a manifest and a policy in a scratch directory.
run_gate() {
  local manifest="$1" policy="$2" out
  out=$("$CHECK_SCRIPT" --root "$workdir" --manifest "$manifest" --policy "$policy" 2>&1)
  printf '%s' "$out"
}

workdir=$(mktemp -d "${TMPDIR:-/tmp}/phairplay-privacy-test.XXXXXX") || exit 2

MANIFEST="$workdir/AndroidManifest.xml"
POLICY="$workdir/PRIVACY.md"

# The manifest of the test uses two permissions, one of them spread over
# several lines — exactly the shape that a naive parser skips.
cat > "$MANIFEST" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission
        android:name="android.permission.NEARBY_WIFI_DEVICES"
        android:usesPermissionFlags="neverForLocation" />
</manifest>
MANIFEST

cat > "$POLICY" <<'POLICY'
# Privacy policy

**Last updated:** 2099-01-01

You can read this policy in the app.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | sockets |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct |
POLICY

printf 'test-check-privacy-policy.sh\n'

# 1. the real tree passes
if "$CHECK_SCRIPT" --root "$REPO_ROOT" >/dev/null 2>&1; then
  ok "the real tree passes the gate"
else
  bad "the real tree passes the gate"
fi

# 2. an undocumented permission turns the gate red
sed 's/| `INTERNET` | sockets |//' "$POLICY" > "$workdir/missing.md"
out=$(run_gate "AndroidManifest.xml" "missing.md")
case "$out" in
  *"does not mention"*) ok "an undocumented permission turns the gate red" ;;
  *) bad "an undocumented permission turns the gate red (output: $out)" ;;
esac

# 3. a permission over several lines is found as well
cat > "$workdir/multi.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission
        android:name="android.permission.NEARBY_WIFI_DEVICES" />
</manifest>
MANIFEST
cat > "$workdir/no-multi.md" <<'POLICY'
# Privacy policy

**Last updated:** 2099-01-01

You can read this policy in the app.

| Permission | Why |
|---|---|
| `INTERNET` | sockets |
POLICY
out=$(run_gate "multi.xml" "no-multi.md")
case "$out" in
  *"does not mention"*) ok "a permission over several lines is found as well" ;;
  *) bad "a permission over several lines is found as well (output: $out)" ;;
esac

# 4. a policy without the sentence about the entry in the app turns the gate red
sed 's/You can read this policy in the app.//' "$POLICY" > "$workdir/no-app.md"
out=$(run_gate "AndroidManifest.xml" "no-app.md")
case "$out" in
  *"inside the app"*) ok "a policy without the in-app entry turns the gate red" ;;
  *) bad "a policy without the in-app entry turns the gate red (output: $out)" ;;
esac

# 5. a policy that is older than the manifest turns the gate red
sed 's/2099-01-01/2001-01-01/' "$POLICY" > "$workdir/old.md"
out=$(run_gate "AndroidManifest.xml" "old.md")
case "$out" in
  *"older than the manifest"*) ok "a policy that is older than the manifest turns the gate red" ;;
  *) bad "a policy that is older than the manifest turns the gate red (output: $out)" ;;
esac

# 6. a policy without the date turns the gate red
sed '/Last updated/d' "$POLICY" > "$workdir/no-date.md"
out=$(run_gate "AndroidManifest.xml" "no-date.md")
case "$out" in
  *"Last updated"*) ok "a policy without the date turns the gate red" ;;
  *) bad "a policy without the date turns the gate red (output: $out)" ;;
esac

printf '\n%s\n' "----------------------------------------"
printf 'passed: %s   failed: %s\n' "$passed" "$failed"

if [ "$failed" -ne 0 ]; then
  printf 'RESULT: the privacy policy gate is not trustworthy.\n'
  exit 1
fi

printf 'RESULT: every case passed.\n'
exit 0
