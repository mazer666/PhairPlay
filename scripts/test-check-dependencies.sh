#!/usr/bin/env bash
#
# test-check-dependencies.sh — the self-test of scripts/check-dependencies.sh.
#
# WHY: a gate that can never turn red is worse than no gate: it gives the
#      impression that every dependency of the release was assessed, while
#      it checks nothing. Every case below therefore proves that the gate
#      turns RED on a broken decision — and that it turns green again
#      afterwards.
#
# WHAT IS PROVEN
#   1. the decisions of the repository pass the gate
#   2. a dependency above the accepted version turns the gate red
#   3. a waiver whose date has passed turns the gate red
#   4. a dependency that is not in the allowlist turns the gate red — it is
#      reported as unassessed, and the decision stays open
#   5. with --allow-unassessed the gate stays green, but it still says that
#      the decision is open
#   6. after the decisions are restored the gate is green again
#
# USAGE
#   ./scripts/test-check-dependencies.sh
#
# EXIT CODES
#   0  every case passed
#   1  at least one case failed
#   2  the test itself cannot run (missing tool, missing script)
#

set -u

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
CHECK_SCRIPT="$SCRIPT_DIR/check-dependencies.sh"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
ALLOWLIST="$REPO_ROOT/scripts/allowlists/dependency-allowlist.txt"
CEILINGS="$REPO_ROOT/scripts/allowlists/known-vulnerabilities.txt"

if [ ! -x "$CHECK_SCRIPT" ]; then
  printf 'ERROR: %s is missing or not executable.\n' "$CHECK_SCRIPT" >&2
  exit 2
fi
for list in "$ALLOWLIST" "$CEILINGS"; do
  if [ ! -f "$list" ]; then
    printf 'ERROR: %s is missing.\n' "$list" >&2
    exit 2
  fi
done

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

# run_gate ARGS... — runs the gate, stores the output in $out and the exit
# code in $status.
run_gate() {
  out="$("$CHECK_SCRIPT" --root "$REPO_ROOT" "$@" 2>&1)"
  status=$?
}

workdir="$(mktemp -d "${TMPDIR:-/tmp}/phairplay-dependencies-test.XXXXXX")" || exit 2

printf 'test-check-dependencies.sh\n'

# 1. the decisions of the repository pass the gate
run_gate --allowlist "$ALLOWLIST" --vulnerabilities "$CEILINGS"
if [ "$status" -eq 0 ]; then
  ok "the decisions of the repository pass the gate"
else
  bad "the decisions of the repository pass the gate (exit $status: $out)"
fi

# 2. a dependency above the accepted version turns the gate red
sed 's|^org.bouncycastle:bcprov-jdk18on  *1\.80\.2.*|org.bouncycastle:bcprov-jdk18on  1.70.0  the ceiling was lowered by the test|' \
  "$CEILINGS" > "$workdir/lowered.txt"
run_gate --allowlist "$ALLOWLIST" --vulnerabilities "$workdir/lowered.txt"
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"VIOLATION"*) ok "a dependency above the accepted version turns the gate red" ;;
    *) bad "a dependency above the accepted version turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a dependency above the accepted version turns the gate red (exit $status)"
fi

# 3. a waiver whose date has passed turns the gate red
sed 's|WAIVED(2026-12-15)|WAIVED(2020-01-01)|g' "$CEILINGS" > "$workdir/expired.txt"
run_gate --allowlist "$ALLOWLIST" --vulnerabilities "$workdir/expired.txt"
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"waiver that ended"*) ok "a waiver whose date has passed turns the gate red" ;;
    *) bad "a waiver whose date has passed turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a waiver whose date has passed turns the gate red (exit $status)"
fi

# 4. a dependency that is not in the allowlist turns the gate red
grep -v '^junit:junit' "$ALLOWLIST" > "$workdir/shorter-allowlist.txt"
run_gate --allowlist "$workdir/shorter-allowlist.txt" --vulnerabilities "$CEILINGS"
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"UNASSESSED"*) ok "a dependency that is not in the allowlist turns the gate red" ;;
    *) bad "a dependency that is not in the allowlist turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a dependency that is not in the allowlist turns the gate red (exit $status)"
fi

# 5. with --allow-unassessed the gate stays green and still names the gap
run_gate --allowlist "$workdir/shorter-allowlist.txt" --vulnerabilities "$CEILINGS" --allow-unassessed
if [ "$status" -eq 0 ]; then
  case "$out" in
    *"unassessed"*) ok "with --allow-unassessed the gate stays green and names the gap" ;;
    *) bad "with --allow-unassessed the gate stays green and names the gap (output: $out)" ;;
  esac
else
  bad "with --allow-unassessed the gate stays green and names the gap (exit $status)"
fi

# 6. after the decisions are restored the gate is green again
run_gate --allowlist "$ALLOWLIST" --vulnerabilities "$CEILINGS"
if [ "$status" -eq 0 ]; then
  ok "after the decisions are restored the gate is green again"
else
  bad "after the decisions are restored the gate is green again (exit $status: $out)"
fi

printf '\n%s\n' "----------------------------------------"
printf 'passed: %s   failed: %s\n' "$passed" "$failed"

if [ "$failed" -ne 0 ]; then
  printf 'RESULT: the dependency gate is not trustworthy.\n'
  exit 1
fi

printf 'RESULT: every case passed.\n'
exit 0
