#!/usr/bin/env bash
# PhairPlay release script
#
# Cuts a release: quality gate → version → build → verify → tag → publish.
#
# Usage:
#   ./scripts/release.sh --setup            # once: keystore and credentials
#   ./scripts/release.sh v1.0.0-alpha.3    # cut a release
#
# The script is written for the owner, who is not an Android developer. It
# refuses to publish anything it has not checked itself:
#
#   1. the quality gate (the four scripts/check-*.sh) has to be green,
#   2. the version in app/build.gradle.kts is set to the version of the tag and
#      committed together with the tag,
#   3. both flavours are built — one Gradle call each, capped heap, no daemon,
#   4. the built APKs are verified: the version inside the APK has to be the
#      version of the tag, and the APK has to be signed,
#   5. the release is published through the GitHub REST API (curl + token);
#      the GitHub CLI is only the fallback.
#
# Credentials live outside the repository:
#   ~/.config/phairplay/phairplay-release.jks   the signing key
#   ~/.config/phairplay/release.env             the passwords and the token
#
# The documentation for the owner is docs/guides/RELEASING.md.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RELEASE_LIB="$SCRIPT_DIR/release"

CREDS_FILE="${HOME}/.config/phairplay/release.env"
KEYSTORE_FILE="${HOME}/.config/phairplay/phairplay-release.jks"

# shellcheck source=release/common.sh
. "$RELEASE_LIB/common.sh"
# shellcheck source=release/version.sh
. "$RELEASE_LIB/version.sh"
# shellcheck source=release/build.sh
. "$RELEASE_LIB/build.sh"
# shellcheck source=release/verify.sh
. "$RELEASE_LIB/verify.sh"
# shellcheck source=release/github.sh
. "$RELEASE_LIB/github.sh"

GH_OWNER=""
GH_REPO=""
RELEASE_BRANCH=""
TEST_STATUS="green"
SKIP_TESTS="false"

usage() {
    cat <<'EOF'
PhairPlay release script

Usage:
  ./scripts/release.sh --setup                     set up the keystore and the credentials (once)
  ./scripts/release.sh v1.0.0-alpha.3             cut a release
  ./scripts/release.sh v1.0.0-alpha.3 --skip-tests  cut a release without the JVM test suite

Options:
  --setup, -s     create the release keystore and store the credentials outside the repository
  --skip-tests    do not run the JVM protocol suite; the release notes state that it was not run
  -h, --help      show this help and exit

The version has to follow the release scheme of docs/spec/PROJECT_PLAN.md:
  v1.0.0-alpha.N   a prerelease ("alpha")
  v1.0.0-beta.N    a prerelease ("beta")
  v1.0.0           the stable release, published as "latest"

Everything the script does is documented in docs/guides/RELEASING.md.
EOF
}

# ── first-time setup ─────────────────────────────────────────────────────────

cmd_setup() {
    bold "=== PhairPlay release setup ==="
    echo ""
    echo "This creates the signing key and stores the credentials outside the repository."
    echo ""
    echo "  Keystore:    $KEYSTORE_FILE"
    echo "  Credentials: $CREDS_FILE"
    echo ""
    echo "Neither of the two files belongs into the repository — they stay on this machine."
    echo ""

    if [ -f "$KEYSTORE_FILE" ]; then
        echo "The keystore already exists at $KEYSTORE_FILE"
        local overwrite
        read -rp "Overwrite it? [y/N] " overwrite
        case "$overwrite" in
            [Yy]*) : ;;
            *) echo "Aborted."; exit 0 ;;
        esac
    fi

    mkdir -p "$(dirname "$KEYSTORE_FILE")"

    local key_alias keystore_password key_password token
    read -rp "Key alias [phairplay]: " key_alias
    [ -n "$key_alias" ] || key_alias="phairplay"
    read -rsp "Keystore password: " keystore_password; echo
    read -rsp "Key password (Enter = the same as the keystore password): " key_password; echo
    [ -n "$key_password" ] || key_password="$keystore_password"

    echo ""
    echo "Creating the key..."
    keytool -genkey -v \
        -keystore "$KEYSTORE_FILE" \
        -alias "$key_alias" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$keystore_password" \
        -keypass "$key_password" \
        -dname "CN=PhairPlay, O=PhairPlay, C=DE" \
        || die "The keystore could not be created."

    token="${GITHUB_TOKEN:-}"
    if [ -z "$token" ]; then
        echo ""
        echo "A GitHub token is needed to publish the release."
        echo "Create one at https://github.com/settings/tokens (scope: repo)."
        read -rsp "GitHub token (Enter to skip it): " token; echo
    fi

    mkdir -p "$(dirname "$CREDS_FILE")"
    chmod 700 "$(dirname "$CREDS_FILE")"
    {
        printf 'KEYSTORE_PATH=%s\n' "$(shell_quote "$KEYSTORE_FILE")"
        printf 'KEYSTORE_PASSWORD=%s\n' "$(shell_quote "$keystore_password")"
        printf 'KEY_ALIAS=%s\n' "$(shell_quote "$key_alias")"
        printf 'KEY_PASSWORD=%s\n' "$(shell_quote "$key_password")"
        printf 'GITHUB_TOKEN=%s\n' "$(shell_quote "$token")"
    } > "$CREDS_FILE"
    chmod 600 "$CREDS_FILE"

    green "The credentials are stored in $CREDS_FILE (mode 600)."
    if [ -n "$token" ]; then
        local repository
        repository=$(detect_repository || true)
        if [ -n "$repository" ]; then
            GH_OWNER="${repository%%/*}"
            GH_REPO="${repository#*/}"
            github_check_token || true
        else
            yellow "The GitHub repository could not be read out of the remote — the token was not checked."
        fi
    else
        yellow "No GitHub token was stored. Add GITHUB_TOKEN to $CREDS_FILE or export it before the release."
    fi

    echo ""
    green "Done. Cut a release with: ./scripts/release.sh v1.0.0-alpha.3"
    echo "The guide for the whole flow is docs/guides/RELEASING.md."
}

# ── the release flow ─────────────────────────────────────────────────────────

cmd_release() {
    local tag="$1"
    version_validate "$tag"

    bold "=== PhairPlay $VERSION_TAG ==="
    echo ""

    step "Preflight"
    require_cmd git "Install Git."
    require_cmd curl "Install curl — the release is published through the GitHub REST API."
    require_cmd keytool "Install a JDK 17 — keytool reads and creates the keystore."

    local repository
    repository=$(detect_repository) \
        || die "The GitHub repository could not be read. Set GITHUB_REPOSITORY=owner/repo or check the remote of the checkout."
    GH_OWNER="${repository%%/*}"
    GH_REPO="${repository#*/}"
    green "Repository:  $GH_OWNER/$GH_REPO"

    load_credentials
    [ -n "${KEYSTORE_PATH:-}" ] && [ -f "$KEYSTORE_PATH" ] \
        || die "The keystore $KEYSTORE_PATH does not exist. Run: ./scripts/release.sh --setup"
    [ -n "${GITHUB_TOKEN:-}" ] \
        || die "No GitHub token found. Put GITHUB_TOKEN into $CREDS_FILE or export it in the shell."
    green "Token:       $TOKEN_SOURCE"
    green "Keystore:    $KEYSTORE_PATH"

    RELEASE_BRANCH=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)
    if [ "$RELEASE_BRANCH" = "HEAD" ]; then
        die "The checkout is detached — check out the branch the release is cut from."
    fi
    green "Branch:      $RELEASE_BRANCH"

    local dirty
    dirty=$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no)
    if [ -n "$dirty" ]; then
        printf '%s\n' "$dirty" >&2
        die "The working tree has uncommitted changes. Commit or stash them and run the release again."
    fi

    build_prepare_environment
    github_check_token

    # A tag that exists already must point at the commit that is built now —
    # a release that moves an existing tag is a release that lies.
    git -C "$REPO_ROOT" fetch --tags --quiet origin 2>/dev/null || true
    local tag_state tag_sha head_sha
    tag_state=$(version_tag_state "$VERSION_TAG")
    if [ "$tag_state" != "none" ]; then
        tag_sha=$(git -C "$REPO_ROOT" rev-list -n 1 "$VERSION_TAG" 2>/dev/null || true)
        head_sha=$(git -C "$REPO_ROOT" rev-parse HEAD)
        if [ "$tag_sha" != "$head_sha" ]; then
            die "The tag $VERSION_TAG already exists and points at ${tag_sha:0:8}, but the checkout is at ${head_sha:0:8}. A release is always built from the commit of its tag. Delete the tag and the release, then run the release again:
  git tag -d $VERSION_TAG
  git push origin :refs/tags/$VERSION_TAG"
        fi
        yellow "The tag $VERSION_TAG exists already and points at the current commit — the release is rebuilt."
    fi

    step "Quality gate"
    build_run_quality_gate

    if [ "$SKIP_TESTS" = "true" ]; then
        yellow "The JVM protocol suite is NOT run (--skip-tests). The release notes will say that."
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

    local googletv_source firetv_source googletv_aab_source workdir
    googletv_source=$(build_locate_apk googletv)
    firetv_source=$(build_locate_apk firetv)
    googletv_aab_source=$(build_locate_aab googletv)

    verify_find_tools
    verify_release_artifact "$googletv_source" googletv "$VERSION_NUMBER" "$VERSION_CODE"
    verify_release_artifact "$firetv_source" firetv "$VERSION_NUMBER" "$VERSION_CODE"
    verify_aab_signature "$googletv_aab_source" googletv

    workdir=$(mktemp -d "${TMPDIR:-/tmp}/phairplay-assets.XXXXXX") \
        || die "A temporary directory could not be created."
    local googletv_asset="$workdir/PhairPlay-$VERSION_NUMBER-GoogleTV.apk"
    local firetv_asset="$workdir/PhairPlay-$VERSION_NUMBER-FireTV.apk"
    local googletv_aab_asset="$workdir/PhairPlay-$VERSION_NUMBER-GoogleTV.aab"
    cp "$googletv_source" "$googletv_asset"
    cp "$firetv_source" "$firetv_asset"
    cp "$googletv_aab_source" "$googletv_aab_asset"
    green "Assets: PhairPlay-$VERSION_NUMBER-GoogleTV.apk, PhairPlay-$VERSION_NUMBER-FireTV.apk, PhairPlay-$VERSION_NUMBER-GoogleTV.aab"

    step "Tag and push"
    if [ "$tag_state" = "none" ]; then
        version_tag_create "$VERSION_TAG"
    else
        green "The tag $VERSION_TAG exists already."
    fi
    git -C "$REPO_ROOT" push origin "HEAD:refs/heads/$RELEASE_BRANCH" \
        || die "The commit could not be pushed — the release is aborted."
    git -C "$REPO_ROOT" push origin "refs/tags/$VERSION_TAG" \
        || die "The tag could not be pushed — the release is aborted."
    green "The commit and the tag $VERSION_TAG are on the remote."

    step "Publish"
    local notes
    notes=$(github_build_notes "$VERSION_TAG" "$VERSION_PRERELEASE" "$TEST_STATUS" \
        "$(basename "$googletv_asset")" "$(basename "$firetv_asset")" "$(basename "$googletv_aab_asset")")
    github_publish "$VERSION_TAG" "PhairPlay $VERSION_TAG" "$notes" "$VERSION_PRERELEASE" \
        "$googletv_asset" "$firetv_asset" "$googletv_aab_asset"

    rm -rf "$workdir"

    echo ""
    green "=== The release $VERSION_TAG is published ==="
    echo "https://github.com/$GH_OWNER/$GH_REPO/releases/tag/$VERSION_TAG"
    echo ""
    echo "Check it: the tag, the two assets, the version inside the APK and the"
    echo "signature. The guide for that is docs/guides/RELEASING.md."
}

# ── entry point ───────────────────────────────────────────────────────────────

main() {
    local tag=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --setup|-s)
                cmd_setup
                exit 0
                ;;
            --skip-tests)
                SKIP_TESTS="true"
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            -*)
                usage >&2
                die "Unknown option: $1"
                ;;
            *)
                if [ -n "$tag" ]; then
                    usage >&2
                    die "Only one version can be released at a time."
                fi
                tag="$1"
                ;;
        esac
        shift
    done

    if [ -z "$tag" ]; then
        usage >&2
        exit 1
    fi
    cmd_release "$tag"
}

main "$@"
