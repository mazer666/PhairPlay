#!/usr/bin/env bash
#
# build-sbom.sh — write the CycloneDX 1.5 SBOM of the declared dependencies.
#
# WHY: a release cannot be reviewed without knowing what went into it. The
#      SBOM lists every dependency the repository declares, with the version
#      it is pinned to, so that a reviewer can compare it against the
#      advisories of the day.
#
#      The document is built from the version catalog and from the build
#      scripts, without adding a plugin to the build: a plugin that writes an
#      SBOM would be one more artifact to trust, and one more thing that can
#      fail in the middle of a release. Everything here runs offline.
#
# WHAT IS IN THE DOCUMENT
#   * every component of the version catalog: group, name, version, purl
#   * the flag "used" or "declared-unused" per component
#   * the dependency relations: the application depends on the components
#   * the tool chain: Gradle, JDK, Android SDK, Android Gradle Plugin
#   * the commit hash and the time stamp of the document
#   * the license of every component. When it cannot be determined offline,
#     the document says "unknown" and why — nothing is invented.
#
# EXIT CODES
#   0  the document was written
#   1  the document could not be written (missing catalog, unreadable input)
#   2  usage error (unknown argument, missing value)
#
# USAGE
#   ./scripts/build-sbom.sh [options]
#
#   --root DIR       repository root (default: the repository of this script)
#   --catalog PATH   version catalog (default: <root>/gradle/libs.versions.toml)
#   --output PATH    output file
#                    (default: <root>/dist/sbom/phairplay-<version>-sbom.cdx.json)
#   -h, --help       show this help and exit 0
#

set -euo pipefail

ROOT=""
CATALOG=""
OUTPUT=""

usage() {
  sed -n '2,42p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
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
    --catalog)
      [ "$#" -ge 2 ] || die_usage "--catalog requires a path"
      CATALOG="$2"
      shift 2
      ;;
    --catalog=*)
      CATALOG="${1#*=}"
      shift
      ;;
    --output)
      [ "$#" -ge 2 ] || die_usage "--output requires a path"
      OUTPUT="$2"
      shift 2
      ;;
    --output=*)
      OUTPUT="${1#*=}"
      shift
      ;;
    *)
      die_usage "unknown argument '$1'"
      ;;
  esac
done

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
AWK_CATALOG="$SCRIPT_DIR/lib/parse-version-catalog.awk"
AWK_JSON="$SCRIPT_DIR/lib/sbom-json.awk"
for helper in "$AWK_CATALOG" "$AWK_JSON"; do
  [ -f "$helper" ] || die_usage "helper '$helper' is missing"
done

if [ -z "$ROOT" ]; then
  ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
fi
[ -d "$ROOT" ] || die_usage "root directory '$ROOT' does not exist"
cd -- "$ROOT"

if [ -z "$CATALOG" ]; then
  CATALOG="gradle/libs.versions.toml"
fi
if [ ! -f "$CATALOG" ]; then
  printf 'FAILED: the version catalog %s does not exist.\n' "$CATALOG" >&2
  printf '      The SBOM is built from the declared dependencies, so without the\n' >&2
  printf '      catalog there is nothing to describe. Restore the file and run\n' >&2
  printf '      the script again.\n' >&2
  exit 1
fi

WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/phairplay-sbom.XXXXXX")"
cleanup() {
  [ -n "$WORKDIR" ] && rm -rf "$WORKDIR"
}
trap cleanup EXIT

INVENTORY="$WORKDIR/inventory.tsv"
BUILDS="$WORKDIR/builds.txt"
SEEN="$WORKDIR/seen.txt"
: > "$SEEN"

# ── the inventory ───────────────────────────────────────────────────────────

awk -f "$AWK_CATALOG" "$CATALOG" > "$INVENTORY"

# The build scripts may also name a dependency directly, without the catalog.
# Those coordinates belong into the SBOM as well.
BUILD_FILES="$(find . \( -name build -o -name .gradle -o -name .git -o -name .cxx \) -prune \
              -o -name '*.gradle.kts' -print | sort)"
if [ -n "$BUILD_FILES" ]; then
  cat $BUILD_FILES > "$BUILDS"
  grep -HoE '"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+"' $BUILD_FILES 2>/dev/null \
    | sed 's/"//g' \
    | sort -u \
    | while IFS= read -r hit; do
        # The coordinate is the last three fields of the line; the file name
        # is everything in front of it.
        version="${hit##*:}"
        rest="${hit%:*}"
        name="${rest##*:}"
        rest="${rest%:*}"
        group="${rest##*:}"
        file="${rest%:*}"
        # The same coordinate may stand in more than one build script. It is
        # one dependency, so it is listed once.
        if grep -qxF "$group:$name" "$SEEN"; then
          continue
        fi
        printf '%s:%s\n' "$group" "$name" >> "$SEEN"
        printf 'X\t%s\t%s\t%s\t%s\n' "$group" "$name" "$version" "${file#./}"
      done >> "$INVENTORY"
fi

# The catalog is the primary source: without a single entry in it, the
# dependency declaration of the project is gone and the document would
# describe the build scripts only.
CATALOG_COMPONENTS="$(grep -c '^[LP]' "$INVENTORY" || true)"
if [ "$CATALOG_COMPONENTS" -eq 0 ]; then
  printf 'FAILED: the version catalog %s declares no dependency.\n' "$CATALOG" >&2
  printf '      The catalog is the primary source of the SBOM; without it the\n' >&2
  printf '      document would describe the build scripts only.\n' >&2
  exit 1
fi
COMPONENTS="$(grep -c '^[LPX]' "$INVENTORY" || true)"

# ── the tool chain and the identity of the document ──────────────────────────

app_version="$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle.kts 2>/dev/null | head -1)"
[ -n "$app_version" ] || app_version="unknown"

gradle_version="$(sed -n 's/.*gradle-\([0-9][0-9.]*\)-.*/\1/p' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | head -1)"
[ -n "$gradle_version" ] || gradle_version="unknown"

jdk_version="$(grep -hoE 'jvmToolchain\([0-9]+\)' test-runner/build.gradle.kts app/build.gradle.kts 2>/dev/null \
               | grep -oE '[0-9]+' | head -1)"
if [ -z "$jdk_version" ]; then
  jdk_version="$(sed -n 's/.*jvmTarget[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle.kts 2>/dev/null | head -1)"
fi
[ -n "$jdk_version" ] || jdk_version="unknown"

sdk_version="$(sed -n 's/.*compileSdk[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' app/build.gradle.kts 2>/dev/null | head -1)"
[ -n "$sdk_version" ] || sdk_version="unknown"

agp_version="$(awk -F '\t' '$1 == "V" && $2 == "agp" { print $3; exit }' "$INVENTORY")"
[ -n "$agp_version" ] || agp_version="unknown"

commit="$(git rev-parse HEAD 2>/dev/null || printf 'unknown')"
timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# A random serial number, as the specification asks for. The bits are taken
# from /dev/urandom and the version and the variant nibble are set, so that
# the number is a valid version 4 UUID.
make_uuid() {
  local hex variant
  hex="$(od -An -tx1 -N16 /dev/urandom 2>/dev/null | tr -d ' \n')"
  if [ "${#hex}" -ne 32 ]; then
    hex="$(printf '%032x' "$(( $(date +%s) * 100000 + $$ ))")"
  fi
  variant="a"
  case "${hex:16:1}" in
    8|9|a|b) variant="${hex:16:1}" ;;
    *) variant="8" ;;
  esac
  printf '%s-%s-4%s-%s%s-%s' "${hex:0:8}" "${hex:8:4}" "${hex:13:3}" \
    "$variant" "${hex:17:3}" "${hex:20:12}"
}

serial="$(make_uuid)"

if [ -z "$OUTPUT" ]; then
  OUTPUT="dist/sbom/phairplay-$app_version-sbom.cdx.json"
fi

mkdir -p "$(dirname -- "$OUTPUT")"

# ── the document ────────────────────────────────────────────────────────────

awk -f "$AWK_JSON" \
  -v serial="$serial" \
  -v timestamp="$timestamp" \
  -v appversion="$app_version" \
  -v commit="$commit" \
  -v gradle="$gradle_version" \
  -v jdk="$jdk_version" \
  -v sdk="$sdk_version" \
  -v agp="$agp_version" \
  -v builds="$BUILDS" \
  "$INVENTORY" > "$OUTPUT" || {
    printf 'FAILED: the SBOM could not be written.\n' >&2
    exit 1
  }

COMPONENTS="$(grep -c '"scope": "required"' "$OUTPUT" || true)"
USED="$(grep -c '"phairplay:usage", "value": "used"' "$OUTPUT" || true)"
DECLARED="$(grep -c '"phairplay:usage", "value": "declared-unused"' "$OUTPUT" || true)"

printf 'OK: SBOM written — %s\n' "$OUTPUT"
printf '    components: %s (%s used, %s declared but unused)\n' "$COMPONENTS" "$USED" "$DECLARED"
printf '    version: %s   commit: %s   timestamp: %s\n' "$app_version" "$commit" "$timestamp"
printf '    tool chain: gradle %s, jdk %s, android-sdk %s, agp %s\n' \
  "$gradle_version" "$jdk_version" "$sdk_version" "$agp_version"
exit 0
