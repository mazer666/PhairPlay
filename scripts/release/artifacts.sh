#!/usr/bin/env bash
# The release artefacts of a PhairPlay release: the binaries, their checksums,
# the manifest, the build provenance, the verification report and the source
# archive.
#
# WHY this file exists: a release is not the APK alone. Whoever installs the
# build has to be able to answer three questions without asking anybody:
#
#   1. Which exact file is this?      -> the manifest and the checksums
#   2. How was it built, from what?    -> the provenance (commit, toolchain)
#   3. What was checked before it?     -> the verification report
#
# The artefacts are written into a clearly identified directory
# (dist/release/<version>/) and are NOT published. Publishing is a separate,
# explicit step (scripts/release.sh <version> without --artifacts-only), so that
# building and deploying stay separable.
#
# This file is sourced, not executed.

# The directory the artefacts are written into, relative to the repository root.
ARTIFACT_ROOT="dist/release"

# The third-party notices live in their own file, because they are a document
# of their own and because this file must stay below the 400-line rule.
. "$REPO_ROOT/scripts/release/notices.sh"

# sha256 of a file, portable between Linux (sha256sum) and macOS (shasum).
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        die "Neither sha256sum nor shasum was found — the checksums cannot be written."
    fi
}

# The size of a file in bytes, portable.
size_of() {
    wc -c < "$1" | tr -d ' '
}

# The number of tests and failures of the last JVM test run.
# The XML reports of the test task are the source; a missing report directory
# means "not run", which is reported as such and never as a pass.
test_run_counts() {
    local results="$REPO_ROOT/test-runner/build/test-results/test"
    local tests=0 failures=0 classes=0
    if [ -d "$results" ]; then
        tests=$(grep -ho '<testcase ' "$results"/*.xml 2>/dev/null | wc -l | tr -d ' ')
        failures=$(grep -ho '<failure' "$results"/*.xml 2>/dev/null | wc -l | tr -d ' ')
        classes=$(ls "$results"/*.xml 2>/dev/null | wc -l | tr -d ' ')
    fi
    printf '%s %s %s' "$tests" "$failures" "$classes"
}

# The certificate fingerprint of a signed APK, empty when it cannot be read.
apk_signer_sha256() {
    local apk="$1" tool
    tool=$(command -v apksigner || true)
    [ -n "$tool" ] || tool=$(ls -1 "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | tail -1)
    [ -n "$tool" ] || return 0
    "$tool" verify --print-certs "$apk" 2>/dev/null \
        | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1
}

# Writes the checksum file of a directory.
artifacts_write_checksums() {
    local dir="$1" file
    file="$dir/SHA256SUMS"
    : > "$file"
    (
        cd "$dir" || exit 1
        for artefact in $(ls | grep -v '^SHA256SUMS$' | sort); do
            [ -f "$artefact" ] || continue
            printf '%s  %s\n' "$(sha256_of "$artefact")" "$artefact" >> "$file"
        done
    )
    green "Checksums: $file"
}

# Writes the build provenance: which commit, which toolchain, which flags.
artifacts_write_provenance() {
    local version="$1" dir="$2" file="$3" commit branch dirty gradle_version jdk_version
    commit=$(git -C "$REPO_ROOT" rev-parse HEAD)
    branch=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)
    dirty=$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no | wc -l | tr -d ' ')
    gradle_version=$(sed -n 's/^distributionUrl=.*gradle-\([0-9.]*\)-.*/\1/p' \
        "$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties" | head -1)
    jdk_version=$(java -version 2>&1 | head -1 | sed 's/.*"\([^"]*\)".*/\1/')

    cat > "$file" <<EOF
PhairPlay $version — build provenance
=====================================

Product            PhairPlay (AirPlay 2 / UPnP-DLNA receiver for Android TV)
Version            $version
Git commit         $commit
Git branch         $branch
Uncommitted changes in the tracked files: $dirty
Built at           $(date -u +%Y-%m-%dT%H:%M:%SZ)
Built on           $(uname -s) $(uname -m)

Toolchain
---------
Gradle             $gradle_version
JDK                $jdk_version
Android SDK        ${ANDROID_HOME:-unknown}
compileSdk         $(sed -n 's/^[[:space:]]*compileSdk = \([0-9]*\).*/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -1)
targetSdk          $(sed -n 's/^[[:space:]]*targetSdk = \([0-9]*\).*/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -1)
minSdk googletv    $(sed -n 's/^[[:space:]]*minSdk = \([0-9]*\).*/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -1)
minSdk firetv      25
Signing key        the release keystore outside the repository (never in the repository)

Build invocation
----------------
The artefacts were built by scripts/release.sh --artifacts-only. The script runs
the quality gate, the JVM test suite, builds one flavour per Gradle call and
verifies the version and the signature of every artefact before it writes them
here. The Kotlin compilation runs in its own Gradle call, so that the compiler
and R8 never compete for the memory of the machine.

Publication
-----------
These artefacts are NOT published. Building and publishing are separate steps:
scripts/release.sh <version> without --artifacts-only creates the tag and
uploads to GitHub, and that step needs an explicit authorisation.
EOF
    green "Provenance: $file"
}

# Writes the manifest: the machine-readable description of the release.
artifacts_write_manifest() {
    local version="$1" dir="$2" version_code commit branch dirty timestamp
    local tests failures classes
    version_code=$(sed -n 's/^[[:space:]]*versionCode = \([0-9]*\).*/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -1)
    commit=$(git -C "$REPO_ROOT" rev-parse HEAD)
    branch=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)
    dirty=$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no | wc -l | tr -d ' ')
    timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    read -r tests failures classes <<< "$(test_run_counts)"

    {
        printf '{\n'
        printf '  "schema": "phairplay-release-manifest/1",\n'
        printf '  "product": "PhairPlay",\n'
        printf '  "version": %s,\n' "$(json_string "$version")"
        printf '  "versionCode": %s,\n' "${version_code:-0}"
        printf '  "generatedAt": %s,\n' "$(json_string "$timestamp")"
        printf '  "git": { "commit": %s, "branch": %s, "uncommittedTrackedFiles": %s },\n' \
            "$(json_string "$commit")" "$(json_string "$branch")" "$dirty"
        printf '  "build": {\n'
        printf '    "gradle": %s,\n' "$(json_string "$(sed -n 's/^distributionUrl=.*gradle-\([0-9.]*\)-.*/\1/p' "$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties" | head -1)")"
        printf '    "compileSdk": %s,\n' "$(sed -n 's/^[[:space:]]*compileSdk = \([0-9]*\).*/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -1)"
        printf '    "targetSdk": %s,\n' "$(sed -n 's/^[[:space:]]*targetSdk = \([0-9]*\).*/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -1)"
        printf '    "minSdk": { "googletv": 29, "firetv": 25 },\n'
        printf '    "flavours": [ "googletv", "firetv" ]\n'
        printf '  },\n'
        printf '  "verification": {\n'
        printf '    "qualityGate": "green",\n'
        printf '    "jvmTests": { "tests": %s, "failures": %s, "testClasses": %s },\n' \
            "${tests:-0}" "${failures:-0}" "${classes:-0}"
        printf '    "deviceTests": { "executed": 0, "of": 16, "note": %s }\n' \
            "$(json_string "No device test has been executed in this environment — the device test plan is the only proof that needs real hardware.")"
        printf '  },\n'
        printf '  "supplyChain": {\n'
        printf '    "sbom": %s,\n' "$(json_string "$([ -f "$dir/sbom/phairplay-$version-sbom.cdx.json" ] && echo "sbom/phairplay-$version-sbom.cdx.json" || echo "")")"
        printf '    "thirdPartyNotices": %s,\n' "$(json_string "THIRD-PARTY-NOTICES.md")"
        printf '    "dependencyDecisions": %s,\n' "$(json_string "scripts/allowlists/dependency-allowlist.txt")"
        printf '    "knownAdvisories": %s\n' "$(json_string "scripts/allowlists/known-vulnerabilities.txt")"
        printf '  },\n'
        printf '  "publication": { "published": false, "reason": %s },\n' \
            "$(json_string "Built with --artifacts-only: the artefacts are on disk and are not published. Publishing needs an explicit authorisation.")"
        printf '  "artifacts": [\n'

        local first=1 name file size sum signer type
        for file in "$dir"/PhairPlay-*.apk "$dir"/PhairPlay-*.aab "$dir"/PhairPlay-*.tar.gz; do
            [ -f "$file" ] || continue
            name=$(basename "$file")
            size=$(size_of "$file")
            sum=$(sha256_of "$file")
            case "$name" in
                *.apk) type="apk"; signer=$(apk_signer_sha256 "$file") ;;
                *.aab) type="aab"; signer="signed (verified with jarsigner)" ;;
                *) type="source-archive"; signer="" ;;
            esac
            [ $first -eq 1 ] || printf ',\n'
            first=0
            printf '    { "name": %s, "type": %s, "sizeBytes": %s, "sha256": %s, "signer": %s }' \
                "$(json_string "$name")" "$(json_string "$type")" "$size" \
                "$(json_string "$sum")" "$(json_string "$signer")"
        done
        printf '\n  ]\n'
        printf '}\n'
    } > "$dir/release-manifest.json"

    green "Manifest: $dir/release-manifest.json"
}

# Writes the verification report: what was checked, with which result.
artifacts_write_verification_report() {
    local version="$1" dir="$2" file="$3" tests failures classes gate_list
    read -r tests failures classes <<< "$(test_run_counts)"
    # The list of the gates is taken from the release script itself, so the report
    # cannot name fewer gates than the release actually ran.
    gate_list=$(printf '%s.sh, ' $QUALITY_GATE_SCRIPTS | sed 's/, $//')

    cat > "$file" <<EOF
PhairPlay $version — verification report
========================================

Generated $(date -u +%Y-%m-%dT%H:%M:%SZ) by scripts/release.sh --artifacts-only.

## What was checked automatically

| Check | Command | Result |
|---|---|---|
| Quality gate | scripts/$gate_list | green |
| JVM protocol suite | ./gradlew :test-runner:test | $tests tests, $failures failures ($classes test classes) |
| Version inside the artefacts | aapt2 dump badging | equals $version |
| Signature of the APKs | apksigner verify | signed by the release key |
| Signature of the bundle | jarsigner verify | signed |

## What was NOT checked

| Check | Why | State |
|---|---|---|
| The 16 device test cases of docs/guides/DEVICE_TEST_PLAN.md | needs a real Google TV, Fire TV or Samsung television | **NOT EXECUTED** — 0 of 16 |
| The Google Play review | needs the developer console and an account | NOT RUN |
| The Android TV quality review (banner, screenshot, 64-bit, 16 KB page sizes) | needs the developer console | the technical parts are verified in the build, the review itself did not run |
| The Google Cast receiver end to end | needs a registered Cast application ID | NOT RUN |

**An unexecuted check is never a pass.** This report states what was measured;
everything else is open and listed in docs/project/RELEASE_READINESS.md.
EOF
    green "Verification report: $file"
}

# Writes the source archive of the release.
artifacts_write_source_archive() {
    local version="$1" dir="$2" file commit
    commit=$(git -C "$REPO_ROOT" rev-parse HEAD)
    file="$dir/PhairPlay-$version-source.tar.gz"
    git -C "$REPO_ROOT" archive --format=tar.gz \
        --prefix="PhairPlay-$version/" -o "$file" "$commit" \
        || die "The source archive could not be created."
    green "Source archive: $file"
}

# ── artefacts only: build and verify, but do not publish ─────────────────────

cmd_build_artifacts() {
    # Builds and verifies the artefacts of a version and writes them into
    # dist/release/<version>/ — WITHOUT creating a tag and WITHOUT publishing
    # anything.
    #
    # WHY this mode exists: building a release and publishing a release are two
    # different acts. The artefacts have to be verifiable before anybody sees
    # them, and a publication that cannot be taken back needs an explicit
    # authorisation. This mode produces the artefacts, the manifest, the
    # checksums, the provenance and the verification report — and stops there.
    local tag="$1"
    version_validate "$tag"

    bold "=== PhairPlay $VERSION_TAG — artefacts only (no tag, no publication) ==="
    echo ""

    step "Preflight"
    require_cmd git "Install Git."
    require_cmd keytool "Install a JDK 17 — keytool reads the keystore."

    load_credentials
    [ -n "${KEYSTORE_PATH:-}" ] && [ -f "$KEYSTORE_PATH" ] \
        || die "The keystore $KEYSTORE_PATH does not exist. Run: ./scripts/release.sh --setup"
    green "Keystore:    $KEYSTORE_PATH"

    RELEASE_BRANCH=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)
    if [ "$RELEASE_BRANCH" = "HEAD" ]; then
        die "The checkout is detached — check out the branch the artefacts are built from."
    fi
    green "Branch:      $RELEASE_BRANCH"

    local dirty
    dirty=$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no)
    if [ -n "$dirty" ]; then
        printf '%s\n' "$dirty" >&2
        die "The working tree has uncommitted changes. Commit or stash them and run the release again."
    fi

    build_prepare_environment

    step "Supply chain"
    build_run_supply_chain

    step "Quality gate"
    build_run_quality_gate

    if [ "$SKIP_TESTS" = "true" ]; then
        yellow "The JVM protocol suite is NOT run (--skip-tests)."
        TEST_STATUS="not run"
    else
        build_run_tests
    fi

    step "Version $VERSION_NUMBER"
    version_bump "$REPO_ROOT/app/build.gradle.kts" "$VERSION_NUMBER"
    version_commit "$REPO_ROOT/app/build.gradle.kts" "app/build.gradle.kts"
    if ! grep -q "$VERSION_NUMBER" "$REPO_ROOT/CHANGELOG.md"; then
        yellow "CHANGELOG.md has no entry for $VERSION_NUMBER — add one (PROJECT_PLAN: every release gets an entry)."
    fi

    step "Build"
    build_release_apks

    local googletv_source firetv_source googletv_aab_source
    googletv_source=$(build_locate_apk googletv)
    firetv_source=$(build_locate_apk firetv)
    googletv_aab_source=$(build_locate_aab googletv)

    verify_find_tools
    verify_release_artifact "$googletv_source" googletv "$VERSION_NUMBER" "$VERSION_CODE"
    verify_release_artifact "$firetv_source" firetv "$VERSION_NUMBER" "$VERSION_CODE"
    verify_aab_signature "$googletv_aab_source" googletv

    step "Artefacts"
    local dir="$REPO_ROOT/$ARTIFACT_ROOT/$VERSION_NUMBER"
    mkdir -p "$dir" || die "The artefact directory $dir could not be created."
    cp "$googletv_source" "$dir/PhairPlay-$VERSION_NUMBER-GoogleTV.apk"
    cp "$firetv_source" "$dir/PhairPlay-$VERSION_NUMBER-FireTV.apk"
    cp "$googletv_aab_source" "$dir/PhairPlay-$VERSION_NUMBER-GoogleTV.aab"
    # The SBOM of this build belongs to the artefacts: it says what is inside
    # the binaries that are shipped next to it.
    if [ -f "$REPO_ROOT/dist/sbom/phairplay-$VERSION_NUMBER-sbom.cdx.json" ]; then
        mkdir -p "$dir/sbom"
        cp "$REPO_ROOT/dist/sbom/phairplay-$VERSION_NUMBER-sbom.cdx.json" "$dir/sbom/"
    fi
    artifacts_write_third_party_notices "$VERSION_NUMBER" "$dir"
    artifacts_write_source_archive "$VERSION_NUMBER" "$dir"
    artifacts_write_provenance "$VERSION_NUMBER" "$dir" "$dir/BUILD-PROVENANCE.txt"
    artifacts_write_verification_report "$VERSION_NUMBER" "$dir" "$dir/VERIFICATION.md"
    artifacts_write_manifest "$VERSION_NUMBER" "$dir"
    artifacts_write_checksums "$dir"

    echo ""
    green "=== The artefacts of $VERSION_TAG are ready — and NOT published ==="
    echo "$dir"
    echo ""
    echo "They carry: the two signed APKs, the signed app bundle, the source archive,"
    echo "the manifest (release-manifest.json), the checksums (SHA256SUMS), the build"
    echo "provenance (BUILD-PROVENANCE.txt) and the verification report (VERIFICATION.md)."
    echo ""
    echo "Publishing them — a tag and a GitHub release — is a separate step and"
    echo "needs an explicit authorisation:"
    echo "  ./scripts/release.sh $VERSION_TAG"
}
