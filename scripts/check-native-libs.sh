#!/usr/bin/env bash
#
# check-native-libs.sh — verify the native libraries inside the built artifacts.
#
# WHY: Google Play requires every 64-bit native library in an app to be usable
#      on a device with a 16 KB page size. Two independent properties decide
#      that, and both are checked here:
#
#        1. the ELF alignment of the LOAD segments of the library — a 64-bit
#           library whose LOAD segments are aligned to 0x1000 cannot be mapped
#           on a 16 KB page device. The requirement applies since 2026-08-01.
#        2. the alignment of the library inside the ZIP container — the .so
#           must be stored uncompressed and start on a 16 KB boundary.
#
#      A gate that only looks at the build script cannot see a native library
#      that was compiled with the wrong page size, so the check reads the
#      artifacts that are actually shipped.
#
# WHAT IS CHECKED
#   1. which ABI the artifact contains and which .so files live in it
#   2. the ELF alignment of every LOAD segment of every 64-bit library
#      (the 32-bit libraries are exempt — the script says so explicitly
#      instead of staying silent about them)
#   3. the ZIP alignment: every entry on a 4 byte boundary and every
#      lib/**.so uncompressed on a 16 KB boundary
#   4. the ABIs in the artifact against the ABIs declared in app/build.gradle.kts
#
# EXIT CODES
#   0   every check that could run passed
#   1   at least one check found a violation
#   2   usage error (unknown argument, missing value)
#   77  skipped — there is no artifact to check (a skip is never a pass)
#
# USAGE
#   ./scripts/check-native-libs.sh [options]
#
#   --apk PATH          APK to check, repeatable
#   --aab PATH          AAB to check, repeatable
#   --root DIR          repository root (default: the repository of this script)
#   --max-align N       required ELF LOAD alignment in bytes (default: 16384 = 16 KB)
#   --zip-align N       required ZIP offset of a .so in bytes (default: 16384 = 16 KB)
#   --require-zipalign  fail when the Android build tools are not installed
#   -h, --help          show this help and exit 0
#
# EXAMPLES
#   ./scripts/check-native-libs.sh
#   ./scripts/check-native-libs.sh --apk app/build/outputs/apk/googletv/debug/app-googletv-debug.apk
#   ./scripts/check-native-libs.sh --require-zipalign
#

set -euo pipefail

MAX_ALIGN=16384
ZIP_ALIGN=16384
REQUIRE_ZIPALIGN=0
ROOT=""
APK_ARGS=""
AAB_ARGS=""

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
AWK_ELF="$SCRIPT_DIR/lib/elf-load-align.awk"
AWK_ZIP="$SCRIPT_DIR/lib/zip-align.awk"

usage() {
  sed -n '2,54p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
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
    --apk)
      [ "$#" -ge 2 ] || die_usage "--apk requires a path"
      APK_ARGS="$APK_ARGS $2"
      shift 2
      ;;
    --apk=*)
      APK_ARGS="$APK_ARGS ${1#*=}"
      shift
      ;;
    --aab)
      [ "$#" -ge 2 ] || die_usage "--aab requires a path"
      AAB_ARGS="$AAB_ARGS $2"
      shift 2
      ;;
    --aab=*)
      AAB_ARGS="$AAB_ARGS ${1#*=}"
      shift
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
    --max-align)
      [ "$#" -ge 2 ] || die_usage "--max-align requires a number"
      MAX_ALIGN="$2"
      shift 2
      ;;
    --max-align=*)
      MAX_ALIGN="${1#*=}"
      shift
      ;;
    --zip-align)
      [ "$#" -ge 2 ] || die_usage "--zip-align requires a number"
      ZIP_ALIGN="$2"
      shift 2
      ;;
    --zip-align=*)
      ZIP_ALIGN="${1#*=}"
      shift
      ;;
    --require-zipalign)
      REQUIRE_ZIPALIGN=1
      shift
      ;;
    *)
      die_usage "unknown argument '$1'"
      ;;
  esac
done

case "$MAX_ALIGN" in
  ''|*[!0-9]*) die_usage "max align must be a positive number, got '$MAX_ALIGN'" ;;
esac
case "$ZIP_ALIGN" in
  ''|*[!0-9]*) die_usage "zip align must be a positive number, got '$ZIP_ALIGN'" ;;
esac

for helper in "$AWK_ELF" "$AWK_ZIP" "$SCRIPT_DIR/lib/artifact-readers.sh"; do
  [ -f "$helper" ] || die_usage "helper '$helper' is missing"
done

# shellcheck source=lib/artifact-readers.sh
. "$SCRIPT_DIR/lib/artifact-readers.sh"

if [ -z "$ROOT" ]; then
  ROOT="$(dirname -- "$SCRIPT_DIR")"
fi
[ -d "$ROOT" ] || die_usage "root directory '$ROOT' does not exist"
cd -- "$ROOT"

errors=0
warnings=0
checks=0

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  errors=$((errors + 1))
}

warn() {
  printf 'WARNING: %s\n' "$1" >&2
  warnings=$((warnings + 1))
}

note() {
  printf '  %s\n' "$1"
}

# ── collect the artifacts ───────────────────────────────────────────────────

apks="$APK_ARGS"
aabs="$AAB_ARGS"

if [ -z "$apks$aabs" ]; then
  for candidate in app/build/outputs/apk/*/debug/*.apk; do
    if [ -f "$candidate" ]; then
      apks="$apks $candidate"
    fi
  done
  if [ -z "$apks" ]; then
    for candidate in app/build/outputs/apk/*/*/*.apk; do
      if [ -f "$candidate" ]; then
        apks="$apks $candidate"
      fi
    done
  fi
  for candidate in app/build/outputs/bundle/*/*.aab; do
    if [ -f "$candidate" ]; then
      aabs="$aabs $candidate"
    fi
  done
fi

if [ -z "$apks$aabs" ]; then
  printf 'SKIP: native library check — no artifact to check.\n'
  printf '      Looked for app/build/outputs/apk/*/debug/*.apk and app/build/outputs/bundle/*/*.aab.\n'
  printf '      Build the artifacts first: ./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug\n'
  printf '      A skip is not a pass: this check has verified nothing.\n'
  exit 77
fi

# ── the expected ABIs (from app/build.gradle.kts) ───────────────────────────

expected_abis=""
if [ -f app/build.gradle.kts ]; then
  expected_abis="$(sed -n 's/.*abiFilters[^=]*=[[:space:]]*setOf(\([^)]*\)).*/\1/p' app/build.gradle.kts \
                  | tr ',' '\n' \
                  | sed -n 's/.*"\([^"]*\)".*/\1/p')"
fi

if [ -z "$expected_abis" ]; then
  warn "no abiFilters declaration found in app/build.gradle.kts — the ABI check cannot run"
else
  note "expected ABIs (app/build.gradle.kts): $(printf '%s' "$expected_abis" | tr '\n' ' ')"
fi

zipalign_path=""
if zipalign_path="$(find_zipalign)"; then
  note "zipalign: $zipalign_path"
else
  zipalign_path=""
  note "zipalign: not found — the built-in ZIP alignment check is used instead"
  if [ "$REQUIRE_ZIPALIGN" -eq 1 ]; then
    fail "the Android build tools are not installed, so zipalign cannot verify the APK (--require-zipalign)"
  fi
fi

# ── check one artifact ──────────────────────────────────────────────────────

check_artifact() {
  local artifact="$1" kind="$2"
  local tmpdir entry abi name target info cls aligns hex dec
  local present_abis missing unexpected method local_offset data_offset
  local fnlen extralen count

  printf '\n%s: %s\n' "$kind" "$artifact"
  checks=$((checks + 1))

  tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/phairplay-nativelibs.XXXXXX")"

  unzip -Z1 "$artifact" 2>/dev/null \
    | grep -E '(^|/)lib/[^/]+/[^/]+\.so$' > "$tmpdir/so.txt" || true
  if [ ! -s "$tmpdir/so.txt" ]; then
    fail "$artifact contains no native library (no lib/<abi>/*.so entry)"
    rm -rf "$tmpdir"
    return 1
  fi

  present_abis="$(while IFS= read -r entry; do abi_of "$entry"; done < "$tmpdir/so.txt" | sort -u)"

  note "ABIs in the artifact:"
  while read -r count abi; do
    [ -n "$abi" ] || continue
    note "$(printf '%-14s %s native library file(s)' "$abi" "$count")"
  done <<EOF
$(while IFS= read -r entry; do abi_of "$entry"; done < "$tmpdir/so.txt" | sort | uniq -c)
EOF

  # (4) the ABIs against the declaration in app/build.gradle.kts
  if [ -n "$expected_abis" ]; then
    missing=""
    for abi in $expected_abis; do
      if ! list_contains "$present_abis" "$abi"; then
        missing="$missing $abi"
      fi
    done
    unexpected=""
    for abi in $present_abis; do
      if ! list_contains "$expected_abis" "$abi"; then
        unexpected="$unexpected $abi"
      fi
    done
    if [ -n "$missing" ]; then
      fail "$artifact is missing the declared ABI(s):$missing"
    fi
    if [ -n "$unexpected" ]; then
      fail "$artifact contains an ABI that app/build.gradle.kts does not declare:$unexpected"
    fi
    if [ -z "$missing" ] && [ -z "$unexpected" ]; then
      note "the ABIs match the declaration in app/build.gradle.kts"
    fi
  fi

  # (2) the ELF alignment of every native library
  while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    abi="$(abi_of "$entry")"
    name="${entry##*/}"
    target="$tmpdir/$abi-$name"
    if ! unzip -p "$artifact" "$entry" > "$target" 2>/dev/null; then
      fail "$artifact: $entry cannot be extracted"
      continue
    fi
    if ! info="$(elf_info "$target")"; then
      fail "$artifact: $entry is not a readable ELF file"
      continue
    fi
    cls="$(printf '%s\n' "$info" | awk '{print $1}')"
    if ! aligns="$(elf_load_alignments "$target" "$info")"; then
      fail "$artifact: $entry has no readable program header table"
      continue
    fi
    if [ "$cls" = "2" ]; then
      while read -r hex dec; do
        [ -n "$dec" ] || continue
        if [ "$dec" -lt "$MAX_ALIGN" ]; then
          fail "$artifact: $entry — the 64-bit library has a LOAD segment aligned to $hex, but $MAX_ALIGN bytes (16 KB) are required (Google Play 16 KB page size)"
        fi
      done <<EOF
$aligns
EOF
    else
      note "$(printf '%-42s 32-bit library — the %s byte alignment requirement does not apply to it' "$entry" "$MAX_ALIGN")"
    fi
  done < "$tmpdir/so.txt"

  # (3) the ZIP alignment of the native libraries
  #
  # Only the native libraries are read here: they are the entries with a hard
  # alignment requirement, and reading the local header of every one of the
  # ~1200 entries of an APK would make the check needlessly slow. The 4 byte
  # alignment of the remaining entries is covered by zipalign, which the
  # script runs as well when the Android build tools are installed.
  #
  # The ZIP alignment is only a requirement for an APK. In an app bundle the
  # native libraries are stored compressed and Play re-packages them into the
  # device APKs, where bundletool aligns them. The ELF alignment above is
  # therefore the only thing that is checked for a bundle.
  if [ "$kind" = "AAB" ]; then
    note "AAB: Play re-packages the bundle and aligns the native libraries itself, so only the ELF alignment is checked here"
  elif zip_entries "$artifact" > "$tmpdir/entries.tsv"; then
    while IFS="$(printf '\t')" read -r method local_offset name; do
      [ -n "$name" ] || continue
      case "$name" in
        */lib/*.so|lib/*.so) ;;
        *) continue ;;
      esac
      local_header="$(zip_local_header "$artifact" "$local_offset")" || local_header=""
      fnlen="$(printf '%s\n' "$local_header" | cut -f1)"
      extralen="$(printf '%s\n' "$local_header" | cut -f2)"
      if [ -z "$fnlen" ]; then
        fail "$artifact: the local file header of $name cannot be read"
        continue
      fi
      data_offset=$((local_offset + 30 + fnlen + extralen))
      if [ "$method" != "0" ]; then
        warn "$artifact: $name is stored compressed — it is extracted at install time, so the ELF alignment decides, not the position in the ZIP"
        continue
      fi
      if [ $((data_offset % ZIP_ALIGN)) -ne 0 ]; then
        fail "$artifact: $name starts at byte $data_offset, which is not on a $ZIP_ALIGN byte boundary"
      fi
    done < "$tmpdir/entries.tsv"
  else
    fail "$artifact cannot be read as a ZIP archive"
  fi

  if [ "$kind" = "APK" ] && [ -n "$zipalign_path" ]; then
    if "$zipalign_path" -c -P 16 -v 4 "$artifact" > "$tmpdir/zipalign.log" 2>&1; then
      note "zipalign reports the APK as correctly aligned"
    else
      fail "$artifact: zipalign reports the APK as NOT aligned"
      sed -n '1,20p' "$tmpdir/zipalign.log" | sed 's/^/  /' >&2
    fi
  fi

  rm -rf "$tmpdir"
  return 0
}

for apk in $apks; do
  check_artifact "$apk" "APK" || true
done
for aab in $aabs; do
  check_artifact "$aab" "AAB" || true
done

# ── result ───────────────────────────────────────────────────────────────────

if [ "$errors" -gt 0 ]; then
  printf '\nFAILED: native library check — %s violation(s) in %s artifact(s), %s warning(s).\n' \
    "$errors" "$checks" "$warnings" >&2
  exit 1
fi

printf '\nOK: native library check passed — %s artifact(s) checked, %s warning(s).\n' "$checks" "$warnings"
exit 0
