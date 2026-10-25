#!/usr/bin/env bash
# Shared helpers for the PhairPlay release script.
#
# WHY: cutting a release is a long chain — a quality gate, a version bump, two
#      Gradle builds, an artefact check, a tag and a publication. The chain
#      itself lives in scripts/release.sh; everything that is a *tool*, a
#      *credential* or a *file format* lives here, so the flow stays readable.
#
# PORTABILITY: the owner may work on macOS, whose system Bash is still 3.2.
#      Therefore: no mapfile, no readarray, no associative arrays, no ${var,,}.
#      Everything below runs on Bash 3.2 and on GNU Bash.
#
# This file is sourced, not executed.

# ── output ────────────────────────────────────────────────────────────────────

red()    { printf '\033[0;31m%s\033[0m\n' "$*"; }
green()  { printf '\033[0;32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
bold()   { printf '\033[1m%s\033[0m\n' "$*"; }
step()   { printf '\n▸ %s\n' "$*"; }
warn()   { printf 'WARNING: %s\n' "$*" >&2; }
die()    { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

require_cmd() {
    # require_cmd <command> [hint]
    command -v "$1" >/dev/null 2>&1 \
        || die "'$1' not found. ${2:-Install it and try again.}"
}

# ── shell quoting ─────────────────────────────────────────────────────────────

shell_quote() {
    # Quotes a value so that it can be written into a file that is sourced by
    # bash. A password may contain spaces, '#' or quotes — without this it
    # would break the credentials file on the next run.
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

# ── Android SDK ───────────────────────────────────────────────────────────────

android_sdk_dir() {
    # Prints the SDK directory or nothing. The order is the one the owner is
    # most likely to recognise: the environment first, then the two standard
    # installation directories of macOS and Linux.
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
        printf '%s\n' "$ANDROID_HOME"
        return 0
    fi
    if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
        printf '%s\n' "$ANDROID_SDK_ROOT"
        return 0
    fi
    local candidate
    for candidate in \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk" \
        "/opt/homebrew/share/android-commandlinetools" \
        "/usr/local/lib/android/sdk" \
        "/opt/android-sdk"
    do
        if [ -d "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

version_is_newer() {
    # version_is_newer <a> <b> — true when the version a is newer than b.
    #
    # WHY this exists: the build-tools versions are compared numerically, because
    # "9.0.0" is newer than "35.0.0" for a plain string sort. The comparison
    # uses parameter expansion and a here-string, without a pipeline: a glob
    # that is piped into another command does not survive every shell
    # environment, and the tool lookup has to work everywhere.
    local v1="$1" v2="$2" m1 p1 r1 m2 p2 r2
    [ -n "$v1" ] || return 1
    [ -n "$v2" ] || return 0
    IFS=. read -r m1 p1 r1 <<< "$v1"
    IFS=. read -r m2 p2 r2 <<< "$v2"
    m1=$((10#${m1:-0})); p1=$((10#${p1:-0})); r1=$((10#${r1:-0}))
    m2=$((10#${m2:-0})); p2=$((10#${p2:-0})); r2=$((10#${r2:-0}))
    [ "$m1" -gt "$m2" ] && return 0
    [ "$m1" -lt "$m2" ] && return 1
    [ "$p1" -gt "$p2" ] && return 0
    [ "$p1" -lt "$p2" ] && return 1
    [ "$r1" -gt "$r2" ] && return 0
    return 1
}

find_build_tool() {
    # find_build_tool <aapt2|apksigner>
    #
    # WHY the tools are part of the Android build-tools and are therefore not
    #      on the PATH of a normal shell. The newest build-tools directory
    #      wins — and "newest" is compared numerically, because "9.0.0" is
    #      newer than "35.0.0" for a plain string sort.
    local tool="$1" sdk dir version candidate best="" best_version=""
    if command -v "$tool" >/dev/null 2>&1; then
        command -v "$tool"
        return 0
    fi
    sdk=$(android_sdk_dir) || return 1

    # The directories are walked with a glob and without a pipeline, so the
    # lookup also works in shells where a glob does not survive a pipe.
    for dir in "$sdk"/build-tools/*/; do
        [ -d "$dir" ] || continue
        candidate="$dir$tool"
        [ -x "$candidate" ] || continue
        version="${dir%/}"
        version="${version##*/}"
        if [ -z "$best" ] || version_is_newer "$version" "$best_version"; then
            best="$candidate"
            best_version="$version"
        fi
    done
    if [ -n "$best" ]; then
        printf '%s\n' "$best"
        return 0
    fi

    # Fallback for the case that the glob found nothing: the same search with
    # a pipeline.
    version=$(ls -1d "$sdk"/build-tools/*/ 2>/dev/null \
        | sed 's|.*/||; s|/||' \
        | sort -t. -k1,1n -k2,2n -k3,3n \
        | tail -1)
    [ -n "$version" ] || return 1
    candidate="$sdk/build-tools/$version/$tool"
    [ -x "$candidate" ] || return 1
    printf '%s\n' "$candidate"
}

# ── credentials ───────────────────────────────────────────────────────────────

load_credentials() {
    # Sources the credentials file and exports the signing variables.
    #
    # WHY: the file is a plain KEY=VALUE list that the owner can edit by hand.
    #      GITHUB_TOKEN is deliberately *not* exported: a token that leaks into
    #      the environment of a Gradle build (a stack trace prints it) is a
    #      leaked token.
    [ -f "$CREDS_FILE" ] \
        || die "No credentials found at $CREDS_FILE. Run: ./scripts/release.sh --setup"
    local env_token="${GITHUB_TOKEN:-}"
    # shellcheck disable=SC1090
    . "$CREDS_FILE"
    export KEYSTORE_PATH KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD
    if [ -n "${PHAIRPLAY_CAST_APP_ID:-}" ]; then
        export PHAIRPLAY_CAST_APP_ID
    fi
    if [ -n "$env_token" ]; then
        GITHUB_TOKEN="$env_token"
        TOKEN_SOURCE="the environment variable GITHUB_TOKEN"
    elif [ -n "${GITHUB_TOKEN:-}" ]; then
        TOKEN_SOURCE="the credentials file $CREDS_FILE"
    else
        TOKEN_SOURCE=""
    fi
    return 0
}

# ── GitHub repository ─────────────────────────────────────────────────────────

detect_repository() {
    # Prints "owner/repo" or nothing.
    #
    # WHY: the REST API needs the owner and the repository name. The remote of
    #      the checkout is the truth; GITHUB_REPOSITORY wins, because that is
    #      what GitHub Actions sets.
    if [ -n "${GITHUB_REPOSITORY:-}" ]; then
        printf '%s\n' "$GITHUB_REPOSITORY"
        return 0
    fi
    local url tail
    url=$(git -C "$REPO_ROOT" remote get-url origin 2>/dev/null || true)
    case "$url" in
        *github.com*)
            tail="${url#*github.com}"
            tail="${tail#[:/]}"
            tail="${tail%.git}"
            tail="${tail%/}"
            if [ -n "$tail" ] && [ "${tail#*/}" != "$tail" ]; then
                printf '%s\n' "$tail"
                return 0
            fi
            ;;
    esac
    return 1
}

# ── JSON ──────────────────────────────────────────────────────────────────────

json_escape() {
    # Escapes a text for a JSON string (without the surrounding quotes).
    # WHY: jq is not installed everywhere, so the escaping is done with sed and
    #      awk — both are part of every macOS and every Linux.
    sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' \
        | awk 'BEGIN { ORS = "" } { if (NR > 1) printf "%s", "\\n"; printf "%s", $0 }'
}

json_string() {
    # Prints a quoted JSON string.
    printf '"%s"' "$(printf '%s' "$1" | json_escape)"
}

json_field() {
    # json_field <json> <key> — prints the value of a top-level string field.
    # python3 is used when it is available (it is on macOS by default), the
    # grep/sed fallback reads the simple GitHub responses.
    if command -v python3 >/dev/null 2>&1; then
        printf '%s' "$1" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(1)
value = data.get(sys.argv[1], "")
print(value if value is not None else "")
' "$2"
        return 0
    fi
    printf '%s' "$1" \
        | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" \
        | head -1
}

json_asset_id() {
    # json_asset_id <json> <asset name> — prints the id of the named asset.
    if command -v python3 >/dev/null 2>&1; then
        printf '%s' "$1" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(1)
for asset in data.get("assets", []):
    if asset.get("name") == sys.argv[1]:
        print(asset.get("id", ""))
        break
' "$2"
        return 0
    fi
    # Fallback for the GitHub asset layout: the id stands in front of the name.
    printf '%s' "$1" \
        | sed -n "s/.*\"id\":\([0-9][0-9]*\),\"node_id\":\"[^\"]*\",\"name\":\"$2\".*/\1/p" \
        | head -1
}
