#!/usr/bin/env bash
# The publication of a release — GitHub REST API first, GitHub CLI as fallback.
#
# WHY: the GitHub CLI is not installed on every machine, and it needs an
#      interactive login that a token makes unnecessary. The script therefore
#      talks to the REST API with curl and a token, and only falls back to
#      "gh" when the API call does not succeed. Two hosts are involved:
#        * api.github.com      creates and changes the release
#        * uploads.github.com   uploads the assets — an upload against
#          api.github.com answers 404, because the asset endpoint does not
#          exist there.
#
# This file is sourced, not executed.

GITHUB_API="${GITHUB_API:-https://api.github.com}"
GITHUB_UPLOADS="${GITHUB_UPLOADS:-https://uploads.github.com}"

github_check_token() {
    # Checks that the token is accepted. A wrong or expired token is the most
    # common reason for a failed publication, and it is better to say so
    # before the build than after it.
    local status out
    out=$(mktemp "${TMPDIR:-/tmp}/phairplay-token.XXXXXX") || return 1
    status=$(curl -sS -o "$out" -w '%{http_code}' \
        -H "Authorization: Bearer $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "$GITHUB_API/repos/$GH_OWNER/$GH_REPO") || status="000"
    rm -f "$out"
    case "$status" in
        200) green "The GitHub token is accepted ($TOKEN_SOURCE)." ;;
        401) die "The GitHub token was rejected (401). The token is expired or wrong — create a new one and put it into $CREDS_FILE or into the environment." ;;
        403) die "The GitHub token was rejected (403). It has no permission for $GH_OWNER/$GH_REPO." ;;
        *) die "GitHub answered $status when the token was checked." ;;
    esac
}

github_request() {
    # github_request <method> <url> [body file] [output file] — prints the HTTP
    # status code and writes the response into the output file.
    local method="$1" url="$2" body_file="${3:-}" out_file="${4:-/dev/null}"
    if [ -n "$body_file" ]; then
        curl -sS -o "$out_file" -w '%{http_code}' -X "$method" \
            -H "Authorization: Bearer $GITHUB_TOKEN" \
            -H "Accept: application/vnd.github+json" \
            -H "X-GitHub-Api-Version: 2022-11-28" \
            -H "Content-Type: application/json" \
            --data-binary "@$body_file" \
            "$url"
        return $?
    fi
    curl -sS -o "$out_file" -w '%{http_code}' -X "$method" \
        -H "Authorization: Bearer $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "$url"
    return $?
}

github_upload_asset() {
    # github_upload_asset <release id> <file> <asset name> <output file>
    #
    # WHY uploads.github.com: the asset endpoint of the releases API lives on
    #      its own host. An upload against api.github.com answers 404.
    local release_id="$1" file="$2" name="$3" out_file="$4"
    curl -sS -o "$out_file" -w '%{http_code}' -X POST \
        -H "Authorization: Bearer $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@$file" \
        "$GITHUB_UPLOADS/repos/$GH_OWNER/$GH_REPO/releases/$release_id/assets?name=$name"
    return $?
}

github_build_notes() {
    # github_build_notes <tag> <prerelease true|false> <test status> <asset 1> <asset 2>
    #
    # WHY the notes say what was not tested: the project plan demands it
    #      ("Every release lists the device test cases that are still open").
    #      A release that hides an open case is a defect.
    local tag="$1" prerelease="$2" test_status="$3" asset_googletv="$4" asset_firetv="$5" asset_googletv_aab="${6:-}"
    local previous commits since plan_url
    plan_url="https://github.com/$GH_OWNER/$GH_REPO/blob/main/docs/guides/DEVICE_TEST_PLAN.md"

    previous=$(git -C "$REPO_ROOT" tag --sort=-version:refname 2>/dev/null \
        | grep -v "^${tag}$" | head -1)
    if [ -n "$previous" ]; then
        commits=$(git -C "$REPO_ROOT" log --pretty=format:"- %s" "$previous..$tag" 2>/dev/null | head -30)
        since="since $previous"
    else
        commits=$(git -C "$REPO_ROOT" log --pretty=format:"- %s" "$tag" 2>/dev/null | head -30)
        since="the first release"
    fi
    [ -n "$commits" ] || commits="- no commit since the last release"

    cat <<EOF
## What's changed ($since)

$commits

---

### Installation

Side-load the APK that matches your device (or upload the AAB to Google Play Console):

- **$asset_googletv** — Google TV / Android TV APK (Android 10+)
- **$asset_firetv** — Amazon Fire TV APK (Android 7.1+)
${asset_googletv_aab:+- **$asset_googletv_aab** — Google Play Store Android App Bundle (Google TV)}

Use [adb](https://developer.android.com/tools/adb) or a sideloading app like *Downloader* to install.

### What was not tested

EOF

    if [ "$prerelease" = "true" ]; then
        cat <<EOF
**This is a prerelease.** No television has run this build: **0 of the 15 device test
cases** of the [device test plan]($plan_url) have been executed. Report what you
find as an issue.

EOF
    else
        cat <<EOF
The device test cases are recorded in the [device test plan]($plan_url). Every case
that is still open is listed there — a release that hides an open case is a defect.

EOF
    fi

    cat <<EOF
### The build itself

EOF
    if [ "$test_status" = "green" ]; then
        cat <<EOF
- The four checks of the quality gate and the JVM protocol suite were **green** on
  the tagged commit.
EOF
    else
        cat <<EOF
- **The JVM protocol suite was NOT run for this release.** The four checks of the
  quality gate were green, but no test suite was executed — treat this release as
  untested.
EOF
    fi
    if [ "$VERIFY_VERSION_CHECKED" != "true" ]; then
        cat <<EOF
- The version inside the APK was **not** checked automatically, because aapt2 was
  not available on the build machine.
EOF
    fi
    cat <<EOF
- Both APKs are signed with the release key. An update over an installed version
  only works if the key is the same — otherwise uninstall the old version first.
EOF
}

github_publish_rest() {
    # Publishes the release with the REST API. Returns 1 if any step failed, so
    # that the caller can fall back to the GitHub CLI.
    local tag="$1" title="$2" notes="$3" prerelease="$4"
    shift 4
    local workdir status body payload release_id="" name asset asset_id
    workdir=$(mktemp -d "${TMPDIR:-/tmp}/phairplay-release.XXXXXX") \
        || die "A temporary directory could not be created."
    body="$workdir/release.json"
    payload="$workdir/payload.json"

    status=$(github_request GET \
        "$GITHUB_API/repos/$GH_OWNER/$GH_REPO/releases/tags/$tag" "" "$body") || status="000"
    case "$status" in
        200)
            release_id=$(json_field "$(cat "$body")" id)
            green "The release $tag exists already — the notes and the assets are replaced."
            ;;
        404)
            release_id=""
            ;;
        *)
            warn "GitHub answered $status while looking for the release $tag."
            rm -rf "$workdir"
            return 1
            ;;
    esac

    if [ -n "$release_id" ]; then
        printf '{"name":%s,"body":%s,"prerelease":%s,"draft":false}' \
            "$(json_string "$title")" "$(json_string "$notes")" "$prerelease" > "$payload"
        status=$(github_request PATCH \
            "$GITHUB_API/repos/$GH_OWNER/$GH_REPO/releases/$release_id" "$payload" "$body") || status="000"
        case "$status" in
            200) green "The release notes were updated." ;;
            *) warn "GitHub answered $status while updating the release $tag."; rm -rf "$workdir"; return 1 ;;
        esac
    else
        printf '{"tag_name":%s,"name":%s,"body":%s,"prerelease":%s,"draft":false,"target_commitish":%s}' \
            "$(json_string "$tag")" "$(json_string "$title")" "$(json_string "$notes")" \
            "$prerelease" "$(json_string "$RELEASE_BRANCH")" > "$payload"
        status=$(github_request POST \
            "$GITHUB_API/repos/$GH_OWNER/$GH_REPO/releases" "$payload" "$body") || status="000"
        case "$status" in
            201)
                release_id=$(json_field "$(cat "$body")" id)
                green "The release $tag was created (id $release_id)."
                ;;
            *)
                warn "GitHub answered $status while creating the release $tag: $(cat "$body")"
                rm -rf "$workdir"
                return 1
                ;;
        esac
    fi

    if [ -z "$release_id" ]; then
        warn "The id of the release could not be read out of the answer of GitHub."
        rm -rf "$workdir"
        return 1
    fi

    for asset in "$@"; do
        name=$(basename "$asset")
        asset_id=$(json_asset_id "$(cat "$body")" "$name")
        if [ -n "$asset_id" ]; then
            # GitHub refuses a second asset with the same name — the old one
            # has to go before the new one is uploaded.
            status=$(github_request DELETE \
                "$GITHUB_API/repos/$GH_OWNER/$GH_REPO/releases/assets/$asset_id" "" "$body") || status="000"
            case "$status" in
                204) green "Removed the old asset $name." ;;
                *) warn "GitHub answered $status while removing the old asset $name." ;;
            esac
        fi
        status=$(github_upload_asset "$release_id" "$asset" "$name" "$body") || status="000"
        case "$status" in
            201) green "Uploaded $name." ;;
            404)
                warn "GitHub answered 404 for the upload of $name. The asset endpoint of the releases API lives on uploads.github.com — this is a bug in the script."
                rm -rf "$workdir"
                return 1
                ;;
            *)
                warn "GitHub answered $status for the upload of $name: $(cat "$body")"
                rm -rf "$workdir"
                return 1
                ;;
        esac
    done

    rm -rf "$workdir"
    return 0
}

github_publish_gh() {
    # Publishes the release with the GitHub CLI — the fallback for a machine
    # where the REST API does not work but gh is installed and logged in.
    local tag="$1" title="$2" notes="$3" prerelease="$4"
    shift 4
    require_cmd gh "Install the GitHub CLI (https://cli.github.com) or fix the REST API call."
    local asset status
    if [ "$prerelease" = "true" ]; then
        gh release create "$tag" --title "$title" --notes "$notes" --prerelease "$@"
        status=$?
    else
        gh release create "$tag" --title "$title" --notes "$notes" "$@"
        status=$?
    fi
    if [ "$status" -eq 0 ]; then
        green "The release $tag was published with the GitHub CLI."
        return 0
    fi
    # The release exists already (a rebuild after a failure): upload the
    # assets again and let the CLI replace what is there.
    for asset in "$@"; do
        gh release upload "$tag" "$asset" --clobber \
            || die "The upload of $asset failed."
    done
    green "The release $tag was published with the GitHub CLI."
    return 0
}

github_publish() {
    # github_publish <tag> <title> <notes> <prerelease> <asset> [<asset> ...]
    local tag="$1" title="$2" notes="$3" prerelease="$4"
    shift 4
    if github_publish_rest "$tag" "$title" "$notes" "$prerelease" "$@"; then
        return 0
    fi
    yellow "The REST API did not succeed — falling back to the GitHub CLI."
    github_publish_gh "$tag" "$title" "$notes" "$prerelease" "$@"
}
