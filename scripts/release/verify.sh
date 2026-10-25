#!/usr/bin/env bash
# The verification of a built release APK.
#
# WHY: a build that succeeds is not a release that may be published. Two
#      things have to hold before an APK leaves the machine:
#        1. the APK carries the version of the tag (AC-M11.7: "the version name
#           in the code equals the released tag"), and
#        2. the APK is signed — an unsigned APK cannot be installed over an
#           installed app and proves nothing about the build.
#      The signing check is not optional: an unsigned APK stops the release.
#
# This file is sourced, not executed.

VERIFY_VERSION_CHECKED="false"

verify_find_tools() {
    # Locates aapt2 and apksigner.
    #
    # WHY: aapt2 checks the version that is really inside the APK, apksigner
    #      checks the signature. Both belong to the Android build-tools and
    #      are therefore not on the PATH of a normal shell.
    AAPT2=$(find_build_tool aapt2 || true)
    APKSIGNER=$(find_build_tool apksigner || true)
    if [ -z "$AAPT2" ]; then
        yellow "aapt2 was not found — the version inside the APK cannot be checked automatically."
    fi
    [ -n "$APKSIGNER" ] \
        || die "apksigner was not found. It belongs to the Android build-tools: install the build-tools or put apksigner on the PATH — an unsigned APK must not be published."
}

verify_apk_version() {
    # verify_apk_version <apk> <flavour> <expected version name> <expected versionCode>
    #
    # Reads versionName and versionCode out of the APK and compares them with
    # the tag. The flavour adds its own suffix to the version name
    # (versionNameSuffix in app/build.gradle.kts), so both the plain name and
    # the name with the suffix of the flavour are accepted.
    local apk="$1" flavor="$2" expected_name="$3" expected_code="$4"
    local badging actual_name actual_code
    badging=$("$AAPT2" dump badging "$apk" 2>/dev/null) \
        || die "aapt2 could not read $apk — the release is aborted."
    actual_code=$(printf '%s\n' "$badging" \
        | sed -n "s/.*versionCode='\([0-9][0-9]*\)'.*/\1/p" | head -1)
    actual_name=$(printf '%s\n' "$badging" \
        | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
    [ -n "$actual_name" ] \
        || die "The version name could not be read out of $apk — the release is aborted."

    case "$actual_name" in
        "$expected_name"|"$expected_name-$flavor") : ;;
        *) die "The $flavor APK carries the version '$actual_name', but the tag says '$expected_name'. The build and the tag do not belong together — the release is aborted." ;;
    esac
    if [ -n "$expected_code" ] && [ "$actual_code" != "$expected_code" ]; then
        die "The $flavor APK carries the versionCode $actual_code, but the build file says $expected_code. The release is aborted."
    fi
    green "$flavor: versionName '$actual_name', versionCode '$actual_code'"
    VERIFY_VERSION_CHECKED="true"
}

verify_apk_signature() {
    # verify_apk_signature <apk> <flavour>
    #
    # WHY: an unsigned APK must never become a release. apksigner exits 0 only
    #      when the signature is complete and the certificate is readable.
    local apk="$1" flavor="$2" output
    if ! output=$("$APKSIGNER" verify --verbose --print-certs "$apk" 2>&1); then
        printf '%s\n' "$output" >&2
        die "The $flavor APK is not signed correctly — the release is aborted. The signing variables (KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) must point at the release keystore."
    fi
    local signer
    signer=$(printf '%s\n' "$output" | sed -n 's/^Signer #1 certificate DN: //p' | head -1)
    [ -n "$signer" ] && green "$flavor: signed by $signer" \
        || green "$flavor: signed (apksigner verify passed)"
}

verify_release_artifact() {
    # verify_release_artifact <apk> <flavour> <expected version name> <expected versionCode>
    local apk="$1" flavor="$2" expected_name="$3" expected_code="$4"
    step "Verifying the $flavor APK"
    if [ -n "$AAPT2" ]; then
        verify_apk_version "$apk" "$flavor" "$expected_name" "$expected_code"
    else
        yellow "The version inside the $flavor APK was NOT checked (aapt2 is missing)."
    fi
    verify_apk_signature "$apk" "$flavor"
}
