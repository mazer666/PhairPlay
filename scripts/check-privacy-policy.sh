#!/usr/bin/env bash
#
# check-privacy-policy.sh — every declared permission must be documented in the privacy policy.
#
# WHY: Google Play reviews the privacy policy against the app. A permission the
#      app declares but the policy does not mention is a finding in the review
#      ("the policy does not comprehensively disclose how your app accesses
#      user data"), and it is exactly the kind of drift nobody notices: a new
#      permission is added to the manifest, the policy stays as it was, and the
#      store entry silently becomes a lie.
#
# WHAT IS CHECKED
#   1. Every <uses-permission android:name="…"> of the app manifests is named
#      in PRIVACY.md — by the bare permission name (ACCESS_WIFI_STATE).
#   2. PRIVACY.md states that the app is reachable from inside the app (the
#      Play policy asks for the policy inside the app as well as in the
#      store listing).
#   3. PRIVACY.md is not older than the newest change of the app manifests
#      (the "Last updated" line) — a policy that predates a permission change
#      is a stale policy.
#
# EXIT CODES
#   0  the privacy policy covers the app
#   1  a permission is undocumented, the in-app entry is missing or the
#      policy is older than the manifests
#   2  usage error (unknown argument, missing file)
#
# USAGE
#   ./scripts/check-privacy-policy.sh [options]
#
#   --root DIR       repository root (default: the repository root)
#   --manifest FILE  manifest to read (default: app/src/main/AndroidManifest.xml)
#   --policy FILE    privacy policy to read (default: PRIVACY.md)
#   -h, --help       show this help and exit 0
#
# EXAMPLE
#   ./scripts/check-privacy-policy.sh
#

set -euo pipefail

ROOT=""
MANIFEST="app/src/main/AndroidManifest.xml"
POLICY="PRIVACY.md"

usage() {
    sed -n '2,40p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
}

die_usage() {
    printf 'ERROR: %s\n' "$1" >&2
    printf 'Try "%s --help" for help.\n' "$0" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --root)
            [ $# -ge 2 ] || die_usage "--root requires a directory argument"
            ROOT="$2"; shift 2 ;;
        --manifest)
            [ $# -ge 2 ] || die_usage "--manifest requires a file argument"
            MANIFEST="$2"; shift 2 ;;
        --policy)
            [ $# -ge 2 ] || die_usage "--policy requires a file argument"
            POLICY="$2"; shift 2 ;;
        -h|--help)
            usage; exit 0 ;;
        *)
            die_usage "Unknown argument: $1" ;;
    esac
done

[ -n "$ROOT" ] || ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST_PATH="$ROOT/$MANIFEST"
POLICY_PATH="$ROOT/$POLICY"

[ -f "$MANIFEST_PATH" ] || die_usage "The manifest $MANIFEST was not found."
[ -f "$POLICY_PATH" ] || die_usage "The privacy policy $POLICY was not found."

failed=0

# ── 1. every permission is named in the policy ────────────────────────────────

# The permissions are read with awk and not with a single sed, because a
# <uses-permission> declaration may be spread over several lines — the sed of
# a single line would silently skip those permissions, and a gate that checks
# nothing is worse than no gate at all.
permissions=$(awk '
    /<uses-permission/ {
        block = $0
        while (block !~ /\/>/ && (getline line) > 0) block = block " " line
        if (match(block, /android:name="[^"]*"/)) {
            value = substr(block, RSTART, RLENGTH)
            sub(/^android:name="/, "", value)
            sub(/"$/, "", value)
            print value
        }
    }
' "$MANIFEST_PATH" \
    | sed 's/^android\.permission\.//' \
    | sort -u)

if [ -z "$permissions" ]; then
    printf 'ERROR: no permission was found in %s — the manifest parsing is broken.\n' "$MANIFEST" >&2
    exit 1
fi

missing=""
for permission in $permissions; do
    if ! grep -q "$permission" "$POLICY_PATH"; then
        missing="$missing $permission"
    fi
done

if [ -n "$missing" ]; then
    printf 'ERROR: the privacy policy does not mention these permissions:%s\n' "$missing" >&2
    printf 'Add them to the permission table of %s.\n' "$POLICY" >&2
    failed=1
else
    printf 'OK: all %s declared permissions are documented in the privacy policy.\n' \
        "$(printf '%s\n' "$permissions" | wc -l | tr -d ' ')"
fi

# ── 2. the policy must be reachable from inside the app ───────────────────────

if ! grep -qi "in the app\|inside the app" "$POLICY_PATH"; then
    printf 'ERROR: the privacy policy does not say that it can be read inside the app.\n' >&2
    printf 'Google Play asks for the policy inside the app as well as in the store listing.\n' >&2
    failed=1
fi

# ── 3. the policy must not be older than the manifest ─────────────────────────

policy_date=$(sed -n 's/^\*\*Last updated:\*\*[[:space:]]*\([0-9][0-9-]*\).*/\1/p' "$POLICY_PATH" | head -1)
manifest_date=$(date -r "$MANIFEST_PATH" +%Y-%m-%d 2>/dev/null || echo "")

if [ -z "$policy_date" ]; then
    printf 'ERROR: the privacy policy has no "**Last updated:** YYYY-MM-DD" line.\n' >&2
    failed=1
elif [ -n "$manifest_date" ] && [ "$policy_date" \< "$manifest_date" ]; then
    printf 'ERROR: the privacy policy (%s) is older than the manifest (%s).\n' \
        "$policy_date" "$manifest_date" >&2
    printf 'Update the policy when the permissions change.\n' >&2
    failed=1
fi

if [ "$failed" -ne 0 ]; then
    exit 1
fi

printf 'OK: privacy policy check passed.\n'
exit 0
