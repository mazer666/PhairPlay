#!/usr/bin/env bash
# The quality gate, the test run and the Gradle builds of a PhairPlay release.
#
# WHY: a release is built from one commit, and that commit has to be green
#      before it becomes a release. The gate is the same gate the CI runs
#      (scripts/check-*.sh), the test run is the JVM protocol suite, and the
#      build runs one Gradle call per flavour — a release build with R8 needs
#      more memory than a small machine has, and two flavours in one call
#      double the peak heap.
#
# This file is sourced, not executed.

# The four repository checks of the quality gate. They need neither Gradle nor
# an Android SDK, only bash — and each of them exits 1 on a violation.
QUALITY_GATE_SCRIPTS="check-file-size check-kdoc-headers check-i18n-completeness check-docs-links"

build_prepare_environment() {
    # Prepares the Android SDK and the JDK for the build and writes
    # local.properties (which is in .gitignore and stays on the machine).
    local sdk
    sdk=$(android_sdk_dir) \
        || die "The Android SDK was not found. Set ANDROID_HOME or install the SDK — see docs/guides/RELEASING.md."
    export ANDROID_HOME="$sdk"

    if [ -z "${JAVA_HOME:-}" ]; then
        local candidate
        for candidate in \
            "/usr/libexec/java_home" \
            "/opt/homebrew/opt/openjdk@17" \
            "/opt/homebrew/opt/openjdk" \
            "/usr/lib/jvm/java-17-openjdk-amd64"
        do
            if [ -x "$candidate" ] && [ -d "$candidate/bin" ]; then
                export JAVA_HOME="$candidate"
                break
            fi
        done
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME/bin" ]; then
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
    command -v java >/dev/null 2>&1 \
        || die "No Java found. Install JDK 17 — see docs/guides/RELEASING.md."
    local java_version
    java_version=$(java -version 2>&1 | head -1)
    case "$java_version" in
        *\"17.*|*\"1.8.*) : ;;
        *) warn "The project is built with JDK 17, but $java_version was found. The build may fail." ;;
    esac

    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$REPO_ROOT/local.properties"
    green "Android SDK: $ANDROID_HOME"
    green "Java:        $java_version"
}

build_run_quality_gate() {
    # Runs the four checks of the quality gate. A red check stops the release:
    # a release is never built from a red tree.
    local check
    for check in $QUALITY_GATE_SCRIPTS; do
        step "Quality gate: scripts/$check.sh"
        # The KDoc check runs with --skip-nested, exactly as the CI runs it:
        # a nested class is documented by the header of the class around it.
        if [ "$check" = "check-kdoc-headers" ]; then
            if ! "$REPO_ROOT/scripts/$check.sh" --skip-nested; then
                die "The quality gate is red (scripts/$check.sh). Fix the finding and run the release again — nothing is published from a red tree."
            fi
        elif ! "$REPO_ROOT/scripts/$check.sh"; then
            die "The quality gate is red (scripts/$check.sh). Fix the finding and run the release again — nothing is published from a red tree."
        fi
    done
    green "The quality gate is green."
}

build_run_tests() {
    # Runs the JVM protocol suite of the release trigger.
    step "JVM protocol suite (:test-runner:test)"
    if ! ( cd "$REPO_ROOT" && ./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m" :test-runner:test ); then
        die "The JVM protocol suite is red — the release is aborted. Fix the failures and run the release again."
    fi
    green "The JVM protocol suite is green."
}

build_gradle() {
    # build_gradle <task> — runs the Gradle wrapper with the memory settings
    # of the project.
    #
    # WHY: "--no-daemon" and the capped heap. A release build with R8 needs
    #      more memory than a small laptop has; a daemon keeps that memory
    #      alive between the two flavours and the kernel kills the process.
    ( cd "$REPO_ROOT" && ./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m" "$@" )
}

build_release_apks() {
    # Builds the two release flavours — one Gradle call each, one after the
    # other — plus the Google Play Store Android App Bundle (.aab) for the
    # googletv flavour. Both flavours always ship (PROJECT_PLAN § "Release
    # Schedule": "One version, both platforms.").
    local flavor
    for flavor in googletv firetv; do
        step "Building the $flavor release APK"
        build_gradle ":app:assemble${flavor}Release" --stacktrace \
            || die "The $flavor release build failed — the release is aborted."
    done
    step "Building the googletv release AAB (Google Play Store bundle)"
    build_gradle ":app:bundleGoogletvRelease" --stacktrace \
        || die "The googletv release AAB build failed — the release is aborted."
}

build_locate_apk() {
    # build_locate_apk <flavour> — prints the path of the built APK.
    local flavor="$1"
    local path="$REPO_ROOT/app/build/outputs/apk/$flavor/release/app-$flavor-release.apk"
    [ -f "$path" ] \
        || die "The $flavor APK was not found at $path — the release is aborted."
    printf '%s\n' "$path"
}

build_locate_aab() {
    # build_locate_aab <flavour> — prints the path of the built AAB.
    local flavor="$1"
    local path="$REPO_ROOT/app/build/outputs/bundle/${flavor}Release/app-$flavor-release.aab"
    [ -f "$path" ] \
        || die "The $flavor AAB was not found at $path — the release is aborted."
    printf '%s\n' "$path"
}
