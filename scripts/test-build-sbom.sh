#!/usr/bin/env bash
#
# test-build-sbom.sh — the self-test of scripts/build-sbom.sh.
#
# WHY: a gate that can never turn red is worse than no gate: it gives the
#      impression that the dependencies of the release are known, while it
#      describes nothing. Every case below therefore proves that the script
#      turns RED on a broken input — and that it writes a usable document
#      when the input is right.
#
# WHAT IS PROVEN
#   1. a missing version catalog turns the script red
#   2. a catalog without a single dependency turns the script red
#   3. the real catalog produces a document with the expected number of
#      components — the number is counted independently of the script
#   4. the document is valid JSON (checked with python3 when it is installed,
#      otherwise with the built-in structural check)
#   5. the document names the components, their versions and their purl
#   6. the document marks a dependency that is declared but not used
#   7. the document carries the commit hash, the time stamp and the tool chain
#
# USAGE
#   ./scripts/test-build-sbom.sh
#
# EXIT CODES
#   0  every case passed
#   1  at least one case failed
#   2  the test itself cannot run (missing tool, missing script)
#

set -u

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
BUILD_SCRIPT="$SCRIPT_DIR/build-sbom.sh"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

if [ ! -x "$BUILD_SCRIPT" ]; then
  printf 'ERROR: %s is missing or not executable.\n' "$BUILD_SCRIPT" >&2
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

workdir="$(mktemp -d "${TMPDIR:-/tmp}/phairplay-sbom-test.XXXXXX")" || exit 2

# json_is_valid FILE — a structural check of a JSON document: the brackets
# have to match and the strings have to be closed. It is deliberately weak,
# so that the test does not need python3; when python3 is installed, the
# test uses it as well.
json_is_valid() {
  local file="$1"
  awk '
    {
      for (i = 1; i <= length($0); i++) {
        c = substr($0, i, 1)
        if (instring) {
          if (c == "\\") { i++; continue }
          if (c == "\"") instring = 0
          continue
        }
        if (c == "\"") { instring = 1; continue }
        if (c == "{" || c == "[") { depth++; stack[depth] = c }
        else if (c == "}" || c == "]") {
          if (depth == 0) exit 1
          if ((c == "}" && stack[depth] != "{") || (c == "]" && stack[depth] != "[")) exit 1
          depth--
        }
      }
    }
    END { if (depth != 0 || instring) exit 1 }
  ' "$file"
}

printf 'test-build-sbom.sh\n'

# 1. a missing version catalog turns the script red
out="$("$BUILD_SCRIPT" --root "$REPO_ROOT" \
       --catalog "$workdir/does-not-exist.toml" \
       --output "$workdir/missing.json" 2>&1)"
status=$?
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"does not exist"*) ok "a missing version catalog turns the script red" ;;
    *) bad "a missing version catalog turns the script red (output: $out)" ;;
  esac
else
  bad "a missing version catalog turns the script red (exit $status)"
fi

# 2. a catalog without a single dependency turns the script red
printf '[versions]\n' > "$workdir/empty.toml"
out="$("$BUILD_SCRIPT" --root "$REPO_ROOT" \
       --catalog "$workdir/empty.toml" \
       --output "$workdir/empty.json" 2>&1)"
status=$?
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"declares no dependency"*) ok "a catalog without a single dependency turns the script red" ;;
    *) bad "a catalog without a single dependency turns the script red (output: $out)" ;;
  esac
else
  bad "a catalog without a single dependency turns the script red (exit $status)"
fi

# 3. the real catalog produces the expected number of components
sbom="$workdir/phairplay-sbom.cdx.json"
out="$("$BUILD_SCRIPT" --root "$REPO_ROOT" --output "$sbom" 2>&1)"
status=$?
if [ "$status" -ne 0 ]; then
  bad "the real catalog produces a document (exit $status: $out)"
else
  ok "the real catalog produces a document"
fi

# The expected number is counted here without the helpers of the script, so
# that the check is independent of the code under test.
catalog="$REPO_ROOT/gradle/libs.versions.toml"
expected="$(
  {
    grep -oE 'group = "[^"]+", name = "[^"]+"' "$catalog" | sed -E 's/group = "([^"]+)", name = "([^"]+)"/\1:\2/'
    grep -oE 'id = "[^"]+"' "$catalog" | sed -E 's/id = "([^"]+)"/\1:\1.gradle.plugin/'
    (cd "$REPO_ROOT" && grep -hoE '"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+"' \
      app/build.gradle.kts build.gradle.kts test-runner/build.gradle.kts 2>/dev/null | tr -d '"' \
      | sed -E 's/^([^:]+):([^:]+):[^:]+$/\1:\2/')
  } | sort -u | wc -l | tr -d ' '
)"
actual="$(grep -c '"scope": "required"' "$sbom" 2>/dev/null || true)"
if [ "$actual" = "$expected" ]; then
  ok "the document holds the expected number of components ($expected)"
else
  bad "the document holds the expected number of components (expected $expected, found $actual)"
fi

# 4. the document is valid JSON
if json_is_valid "$sbom"; then
  ok "the document passes the structural JSON check"
else
  bad "the document passes the structural JSON check"
fi
if command -v python3 >/dev/null 2>&1; then
  if python3 -m json.tool "$sbom" >/dev/null 2>&1; then
    ok "the document is valid JSON (python3)"
  else
    bad "the document is valid JSON (python3)"
  fi
else
  printf '  n/a  the document is valid JSON (python3) — python3 is not installed\n'
fi

# 5. the document names the components, their versions and their purl
for expected_text in \
  "pkg:maven/androidx.appcompat/appcompat@1.7.0" \
  "pkg:maven/org.bouncycastle/bcprov-jdk18on@1.78.1" \
  "pkg:maven/com.googlecode.plist/dd-plist@1.28" \
  "\"specVersion\": \"1.5\"" \
  "\"bomFormat\": \"CycloneDX\""
do
  if grep -qF "$expected_text" "$sbom"; then
    ok "the document contains $expected_text"
  else
    bad "the document contains $expected_text"
  fi
done

# 6. a dependency that is declared but not used is marked as such
if grep -q '"phairplay:usage", "value": "declared-unused"' "$sbom"; then
  ok "the document marks a dependency that is declared but not used"
else
  bad "the document marks a dependency that is declared but not used"
fi

# 7. the document carries the commit hash, the time stamp and the tool chain
for expected_text in "phairplay:commit" "phairplay:source" "\"name\": \"gradle\"" "\"name\": \"jdk\""; do
  if grep -qF "$expected_text" "$sbom"; then
    ok "the document contains $expected_text"
  else
    bad "the document contains $expected_text"
  fi
done

printf '\n%s\n' "----------------------------------------"
printf 'passed: %s   failed: %s\n' "$passed" "$failed"

if [ "$failed" -ne 0 ]; then
  printf 'RESULT: the SBOM builder is not trustworthy.\n'
  exit 1
fi

printf 'RESULT: every case passed.\n'
exit 0
