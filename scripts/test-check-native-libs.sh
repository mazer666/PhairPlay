#!/usr/bin/env bash
#
# test-check-native-libs.sh — the self-test of scripts/check-native-libs.sh.
#
# WHY: a gate that can never turn red is worse than no gate: it gives the
#      impression that the 16 KB page size requirement is covered, while it
#      checks nothing. Every case below therefore proves that the gate turns
#      RED on a broken artifact — and that it turns green again afterwards.
#
# WHAT IS PROVEN
#   1. a correctly built artifact passes the gate
#   2. a 64-bit library whose LOAD segment is aligned to 0x1000 instead of
#      0x4000 turns the gate red
#   3. an ABI that app/build.gradle.kts does not declare turns the gate red
#   4. a compressed native library is reported as a note, not as an error —
#      a compressed library is extracted at install time, so the ELF
#      alignment decides and not the position in the ZIP
#   5. a 32-bit library with a 4 KB alignment is accepted — the 16 KB
#      requirement does not apply to it, and the gate says so
#   6. a native library that does not start on a 16 KB boundary inside the ZIP
#      container turns the gate red
#   7. without an artifact the gate skips with exit 77 (a skip is not a pass)
#   8. after the broken artifacts are removed the gate is green again
#   9. the real artifact of the working tree passes (when a build output exists)
#
# NOTE ON --zip-align 1
#   The synthetic artifacts are built with Info-ZIP, which does not lay the
#   entries out on a 16 KB boundary. The cases that prove the ELF alignment,
#   the ABI check and the compression check therefore run with --zip-align 1,
#   so that each of them fails for exactly one reason. Case 6 covers the ZIP
#   alignment itself with the default of 16 KB, and case 9 covers the default
#   setting on the real artifact.
#
# USAGE
#   ./scripts/test-check-native-libs.sh
#
# EXIT CODES
#   0  every case passed
#   1  at least one case failed
#   2  the test itself cannot run (missing tool, missing script)
#

set -u

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
CHECK_SCRIPT="$SCRIPT_DIR/check-native-libs.sh"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
MAKE_ELF="$SCRIPT_DIR/lib/make-elf.awk"

if [ ! -x "$CHECK_SCRIPT" ]; then
  printf 'ERROR: %s is missing or not executable.\n' "$CHECK_SCRIPT" >&2
  exit 2
fi
if [ ! -f "$MAKE_ELF" ]; then
  printf 'ERROR: %s is missing.\n' "$MAKE_ELF" >&2
  exit 2
fi
if ! command -v zip >/dev/null 2>&1; then
  printf 'ERROR: the test needs the "zip" command to build the broken artifacts.\n' >&2
  exit 2
fi

passed=0
failed=0
skipped=0
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

not_run() {
  skipped=$((skipped + 1))
  printf '  n/a  %s (%s)\n' "$1" "$2"
}

# run_gate ARGS... — runs the gate, stores its output in $out and its exit
# code in $status. The gate is expected to fail in most cases, so the exit
# code must not abort the test.
run_gate() {
  out="$("$CHECK_SCRIPT" --root "$REPO_ROOT" "$@" 2>&1)"
  status=$?
  printf '%s\n' "$out"
}

# make_lib FILE CLASS ALIGN — writes a minimal ELF library with one PT_LOAD
make_lib() {
  awk -f "$MAKE_ELF" -v class="$2" -v align="$3" > "$1"
  dd if=/dev/zero bs=4096 count=1 2>/dev/null >> "$1"
}

workdir="$(mktemp -d "${TMPDIR:-/tmp}/phairplay-nativelibs-test.XXXXXX")" || exit 2
stage="$workdir/stage"
mkdir -p "$stage/lib/arm64-v8a" "$stage/lib/armeabi-v7a" "$stage/lib/x86" "$stage/lib/x86_64"

# The four ABIs of app/build.gradle.kts, each with one library.
make_lib "$stage/lib/arm64-v8a/libplayfair.so" 2 16384
make_lib "$stage/lib/x86_64/libplayfair.so" 2 16384
make_lib "$stage/lib/armeabi-v7a/libplayfair.so" 1 4096
make_lib "$stage/lib/x86/libplayfair.so" 1 4096

good_apk="$workdir/good.apk"
( cd "$stage" && zip -q -0 "$good_apk" \
    lib/arm64-v8a/libplayfair.so lib/x86_64/libplayfair.so \
    lib/armeabi-v7a/libplayfair.so lib/x86/libplayfair.so )

printf 'test-check-native-libs.sh\n'

# 1. a correctly built artifact passes
run_gate --apk "$good_apk" --zip-align 1 > /dev/null
if [ "$status" -eq 0 ]; then
  ok "a correctly built artifact passes the gate"
else
  bad "a correctly built artifact passes the gate (exit $status)"
fi

# 2. a 64-bit library aligned to 4 KB instead of 16 KB turns the gate red
mkdir -p "$workdir/bad-align/lib/arm64-v8a" "$workdir/bad-align/lib/armeabi-v7a" \
         "$workdir/bad-align/lib/x86" "$workdir/bad-align/lib/x86_64"
cp "$stage/lib/armeabi-v7a/libplayfair.so" "$workdir/bad-align/lib/armeabi-v7a/"
cp "$stage/lib/x86/libplayfair.so" "$workdir/bad-align/lib/x86/"
cp "$stage/lib/x86_64/libplayfair.so" "$workdir/bad-align/lib/x86_64/"
make_lib "$workdir/bad-align/lib/arm64-v8a/libplayfair.so" 2 4096
bad_apk="$workdir/bad-align.apk"
( cd "$workdir/bad-align" && zip -q -0 "$bad_apk" \
    lib/arm64-v8a/libplayfair.so lib/x86_64/libplayfair.so \
    lib/armeabi-v7a/libplayfair.so lib/x86/libplayfair.so )
run_gate --apk "$bad_apk" --zip-align 1 > /dev/null
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"LOAD segment aligned to 0x1000"*)
      ok "a 64-bit library aligned to 4 KB turns the gate red" ;;
    *)
      bad "a 64-bit library aligned to 4 KB turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a 64-bit library aligned to 4 KB turns the gate red (exit $status)"
fi

# 3. an ABI that is not declared turns the gate red
mkdir -p "$workdir/bad-abi/lib/mips"
cp "$stage/lib/arm64-v8a/libplayfair.so" "$workdir/bad-abi/lib/mips/"
abi_apk="$workdir/bad-abi.apk"
( cd "$workdir/bad-abi" && zip -q -0 "$abi_apk" lib/mips/libplayfair.so )
run_gate --apk "$abi_apk" --zip-align 1 > /dev/null
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"does not declare"*)
      ok "an ABI that is not declared turns the gate red" ;;
    *)
      bad "an ABI that is not declared turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "an ABI that is not declared turns the gate red (exit $status)"
fi

# 4. a compressed native library is a note, not an error
compressed_apk="$workdir/compressed.apk"
( cd "$stage" && zip -q "$compressed_apk" \
    lib/arm64-v8a/libplayfair.so lib/x86_64/libplayfair.so \
    lib/armeabi-v7a/libplayfair.so lib/x86/libplayfair.so )
run_gate --apk "$compressed_apk" --zip-align 1 > /dev/null
if [ "$status" -eq 0 ]; then
  case "$out" in
    *"stored compressed"*)
      ok "a compressed native library is reported as a note, not as an error" ;;
    *)
      bad "a compressed native library is reported as a note, not as an error (output: $out)" ;;
  esac
else
  bad "a compressed native library is reported as a note, not as an error (exit $status)"
fi

# 4b. a bundle whose libraries are stored compressed is accepted — Play
#     re-packages a bundle and aligns the libraries itself
mkdir -p "$workdir/bundle/base/lib/arm64-v8a" "$workdir/bundle/base/lib/armeabi-v7a" \
         "$workdir/bundle/base/lib/x86" "$workdir/bundle/base/lib/x86_64"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  cp "$stage/lib/$abi/libplayfair.so" "$workdir/bundle/base/lib/$abi/"
done
bundle="$workdir/bundle.aab"
( cd "$workdir/bundle" && zip -q "$bundle" \
    base/lib/arm64-v8a/libplayfair.so base/lib/x86_64/libplayfair.so \
    base/lib/armeabi-v7a/libplayfair.so base/lib/x86/libplayfair.so )
run_gate --aab "$bundle" > /dev/null
if [ "$status" -eq 0 ]; then
  ok "a bundle with compressed libraries is accepted"
else
  bad "a bundle with compressed libraries is accepted (exit $status)"
fi

# 5. a 32-bit library with a 4 KB alignment is accepted
run_gate --apk "$good_apk" --zip-align 1 > /dev/null
case "$out" in
  *"32-bit library"*) ok "a 32-bit library is accepted and the exemption is stated" ;;
  *) bad "a 32-bit library is accepted and the exemption is stated (output: $out)" ;;
esac

# 6. a native library that is not aligned in the ZIP container turns the gate red
run_gate --apk "$good_apk" > /dev/null
if [ "$status" -eq 1 ]; then
  case "$out" in
    *"not on a 16384 byte boundary"*)
      ok "a native library that is not 16 KB aligned in the ZIP turns the gate red" ;;
    *)
      bad "a native library that is not 16 KB aligned in the ZIP turns the gate red (wrong reason: $out)" ;;
  esac
else
  bad "a native library that is not 16 KB aligned in the ZIP turns the gate red (exit $status)"
fi

# 7. without an artifact the gate skips with exit 77
empty_root="$workdir/empty"
mkdir -p "$empty_root"
out="$("$CHECK_SCRIPT" --root "$empty_root" 2>&1)"
status=$?
if [ "$status" -eq 77 ]; then
  case "$out" in
    *"SKIP"*) ok "without an artifact the gate skips with exit 77" ;;
    *) bad "without an artifact the gate skips with exit 77 (output: $out)" ;;
  esac
else
  bad "without an artifact the gate skips with exit 77 (exit $status)"
fi

# 8. after the broken artifacts are removed the gate is green again
rm -f "$bad_apk" "$abi_apk" "$compressed_apk"
run_gate --apk "$good_apk" --zip-align 1 > /dev/null
if [ "$status" -eq 0 ]; then
  ok "after the broken artifacts are removed the gate is green again"
else
  bad "after the broken artifacts are removed the gate is green again (exit $status)"
fi

# 9. the real artifact of the working tree passes (when a build output exists)
real_apk=""
for candidate in "$REPO_ROOT"/app/build/outputs/apk/*/debug/*.apk; do
  if [ -f "$candidate" ]; then
    real_apk="$candidate"
    break
  fi
done
if [ -n "$real_apk" ]; then
  run_gate --apk "${real_apk#$REPO_ROOT/}" > /dev/null
  if [ "$status" -eq 0 ]; then
    ok "the real artifact of the working tree passes the gate"
  else
    bad "the real artifact of the working tree passes the gate (exit $status)"
  fi
else
  not_run "the real artifact of the working tree passes the gate" "no build output in the working tree"
fi

printf '\n%s\n' "----------------------------------------"
printf 'passed: %s   failed: %s   not run: %s\n' "$passed" "$failed" "$skipped"

if [ "$failed" -ne 0 ]; then
  printf 'RESULT: the native library gate is not trustworthy.\n'
  exit 1
fi

printf 'RESULT: every case passed.\n'
exit 0
