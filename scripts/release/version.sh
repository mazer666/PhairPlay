#!/usr/bin/env bash
# The version scheme, the version bump and the tag of a PhairPlay release.
#
# WHY: PROJECT_PLAN.md § "Release Schedule" defines the version scheme as
#      binding (v1.0.0-alpha.N, v1.0.0-beta.N, v1.0.0) and rule 4 of that
#      section says: "The version lives in one place. versionCode and
#      versionName in app/build.gradle.kts are bumped in the same commit that
#      the release tag is created from." Both live in this file.
#
# This file is sourced, not executed.

# The scheme of docs/spec/PROJECT_PLAN.md § "Release Schedule".
VERSION_SCHEME_STABLE='^v[0-9]+\.[0-9]+\.[0-9]+$'
VERSION_SCHEME_PRERELEASE='^v[0-9]+\.[0-9]+\.[0-9]+-(alpha|beta)\.[0-9]+$'

VERSION_TAG=""          # v1.0.0-alpha.3
VERSION_NUMBER=""       # 1.0.0-alpha.3 (the tag without the leading "v")
VERSION_PRERELEASE=""   # "true" for alpha and beta, "false" for the stable release
VERSION_CODE=""         # the versionCode that the release APK carries

CURRENT_VERSION_CODE=""
CURRENT_VERSION_NAME=""

version_validate() {
    # Checks the tag against the release scheme and fills the version fields.
    #
    # WHY: a release that does not follow the scheme cannot be published: the
    #      project plan binds the scheme, and the plan is what the owner and
    #      the testers read. A tag like "v1.2" or "release-3" is refused here
    #      instead of producing a release nobody can classify.
    local tag="$1"
    if [[ "$tag" =~ $VERSION_SCHEME_STABLE ]]; then
        VERSION_TAG="$tag"
        VERSION_NUMBER="${tag#v}"
        VERSION_PRERELEASE="false"
    elif [[ "$tag" =~ $VERSION_SCHEME_PRERELEASE ]]; then
        VERSION_TAG="$tag"
        VERSION_NUMBER="${tag#v}"
        VERSION_PRERELEASE="true"
    else
        die "'$tag' does not follow the release scheme of docs/spec/PROJECT_PLAN.md.
  Accepted:  v1.0.0-alpha.N   (prerelease)
             v1.0.0-beta.N    (prerelease)
             v1.0.0           (stable, published as latest)
  Example:   ./scripts/release.sh v1.0.0-alpha.3"
    fi
}

version_read_current() {
    # Reads versionCode and versionName out of app/build.gradle.kts.
    #
    # WHY: the script has to know the old versionCode to count it up, and it
    #      has to know the old versionName to decide whether a bump is needed
    #      at all — a release that is rebuilt with the same version must not
    #      count the versionCode up a second time.
    local file="$1"
    CURRENT_VERSION_CODE=$(sed -n \
        's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*$/\1/p' \
        "$file" | head -1)
    CURRENT_VERSION_NAME=$(sed -n \
        's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*$/\1/p' \
        "$file" | head -1)
    [ -n "$CURRENT_VERSION_CODE" ] \
        || die "No versionCode found in $file — the release script cannot continue."
    [ -n "$CURRENT_VERSION_NAME" ] \
        || die "No versionName found in $file — the release script cannot continue."
}

version_bump() {
    # version_bump <build.gradle.kts> <version name>
    #
    # Writes the version name into the build file and counts the versionCode
    # up by one. The pattern only matches the two lines of defaultConfig —
    # versionNameSuffix of a flavour is a different key and stays untouched.
    local file="$1" target="$2"
    version_read_current "$file"
    if [ "$CURRENT_VERSION_NAME" = "$target" ]; then
        VERSION_CODE="$CURRENT_VERSION_CODE"
        green "The version is already '$target' (versionCode $VERSION_CODE) — nothing to bump."
        return 0
    fi
    VERSION_CODE=$((CURRENT_VERSION_CODE + 1))
    # The replacement keeps the indentation of the original line and puts a
    # letter behind the back reference, so a versionCode like 16 can never be
    # read as the back reference \16.
    sed -i.bak -E \
        "s|^([[:space:]]*)versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$|\1versionCode = ${VERSION_CODE}|" \
        "$file"
    sed -i.bak -E \
        "s|^([[:space:]]*)versionName[[:space:]]*=[[:space:]]*\"[^\"]*\"[[:space:]]*$|\1versionName = \"${target}\"|" \
        "$file"
    rm -f "$file.bak"
    # Read the file back: a silent failure here would publish an APK with the
    # version of the previous release.
    version_read_current "$file"
    [ "$CURRENT_VERSION_NAME" = "$target" ] \
        || die "The versionName in $file was not written — aborting."
    [ "$CURRENT_VERSION_CODE" = "$VERSION_CODE" ] \
        || die "The versionCode in $file was not written — aborting."
    green "versionName  $CURRENT_VERSION_NAME"
    green "versionCode  $CURRENT_VERSION_CODE (was $((VERSION_CODE - 1)))"
}

version_commit() {
    # Commits the version bump. The commit is the commit the tag points at,
    # so AC-M11.7 ("the version name in the code equals the released tag")
    # holds for the commit that carries the tag.
    local file="$1" relative="$2"
    if git -C "$REPO_ROOT" diff --quiet -- "$relative"; then
        green "The version in $relative is already committed."
        return 0
    fi
    git -C "$REPO_ROOT" add -- "$relative"
    git -C "$REPO_ROOT" commit -m "chore(release): bump version to $VERSION_NUMBER" \
        || die "The version commit failed — the release is aborted."
    green "Committed the version bump: $(git -C "$REPO_ROOT" log -1 --pretty=format:'%h %s')"
}

version_tag_state() {
    # Prints where the tag points: "none", "local", "remote" or "both".
    # WHY: a release that is rebuilt after a failure must not move an existing
    #      tag — the tag is the reference the release notes and the downloads
    #      point to.
    local tag="$1"
    local local_sha="" remote_sha=""
    local_sha=$(git -C "$REPO_ROOT" rev-list -n 1 "$tag" 2>/dev/null || true)
    remote_sha=$(git -C "$REPO_ROOT" ls-remote --tags origin "refs/tags/$tag" 2>/dev/null \
        | awk 'NR==1 { print $1 }')
    if [ -n "$local_sha" ] && [ -n "$remote_sha" ]; then
        printf 'both\n'
    elif [ -n "$local_sha" ]; then
        printf 'local\n'
    elif [ -n "$remote_sha" ]; then
        printf 'remote\n'
    else
        printf 'none\n'
    fi
}

version_tag_create() {
    # Creates the annotated tag on the current commit.
    local tag="$1"
    git -C "$REPO_ROOT" tag -a "$tag" -m "Release $tag" \
        || die "The tag $tag could not be created — the release is aborted."
    green "Created the tag $tag on $(git -C "$REPO_ROOT" rev-parse --short HEAD)."
}
