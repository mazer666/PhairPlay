#!/usr/bin/env bash
# cast-registration-info.sh — prints everything the Google Cast Developer Console
# asks for, so that the registration is a copy-and-paste job.
#
# WHAT: it prints the application id of the Google TV flavour, the signing
#       certificate with its SHA-1 and SHA-256 fingerprint, and the command that
#       bakes the app id into the build.
#
# WHY: the Cast Console asks for two things that are easy to get wrong — the
#      package name and the fingerprint of the signing certificate. The
#      fingerprint has to be the one of the key the release is signed with, and
#      that key changes with every new installation of the build tools. Reading
#      the fingerprint by hand out of a keytool output is where the registration
#      usually goes wrong; this script reads it from the key itself.
#
# HOW: it finds the keystore the same way the release script does — the
#      environment first, then the credentials file. It never prints a password.
#
# Usage:
#   ./scripts/cast-registration-info.sh
#
# Example:
#   ./scripts/cast-registration-info.sh
#   # Application ID (Google TV):  com.phairplay.googletv
#   # SHA-1 fingerprint:          D3:79:31:...
#
# The guide for the registration itself is docs/guides/CAST_APP_ID.md.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CREDS_FILE="${HOME}/.config/phairplay/release.env"

# The application id of the Google TV flavour — the one the console asks for.
GOOGLE_TV_PACKAGE="com.phairplay.googletv"
FIRE_TV_PACKAGE="com.phairplay.firetv"

# The default media receiver of Google. It needs no registration: a sender can
# cast to it without the app owning an id. It is only listed here so that the
# difference is visible.
DEFAULT_MEDIA_RECEIVER_ID="CC1AD845"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
red()  { printf '\033[0;31m%s\033[0m\n' "$*"; }
green(){ printf '\033[0;32m%s\033[0m\n' "$*"; }

die() { red "ERROR: $*" >&2; exit 1; }

# ── the keystore ─────────────────────────────────────────────────────────────

find_keystore() {
    # Prints the path of the release keystore or nothing.
    if [ -n "${KEYSTORE_PATH:-}" ] && [ -f "$KEYSTORE_PATH" ]; then
        printf '%s\n' "$KEYSTORE_PATH"
        return 0
    fi
    if [ -f "$CREDS_FILE" ]; then
        local from_file
        from_file=$(sed -n "s/^KEYSTORE_PATH=['\"]\{0,1\}\([^'\"]*\)['\"]\{0,1\}$/\1/p" "$CREDS_FILE" | head -1)
        if [ -n "$from_file" ] && [ -f "$from_file" ]; then
            printf '%s\n' "$from_file"
            return 0
        fi
    fi
    for candidate in \
        "$HOME/.config/phairplay/phairplay-release.jks" \
        "$HOME/.android/phairplay-release.jks"
    do
        [ -f "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
    done
    return 1
}

find_keytool() {
    local candidate
    for candidate in \
        "${JAVA_HOME:-}/bin/keytool" \
        "/opt/homebrew/opt/openjdk@17/bin/keytool" \
        "/opt/homebrew/opt/openjdk/bin/keytool" \
        "/usr/local/opt/openjdk@17/bin/keytool" \
        "/usr/lib/jvm/default-java/bin/keytool" \
        "$(command -v keytool 2>/dev/null || true)"
    do
        if [ -n "$candidate" ] && [ -x "$candidate" ] && "$candidate" -help >/dev/null 2>&1; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# ── the output ───────────────────────────────────────────────────────────────

bold "=== PhairPlay — Google Cast registration ==="
echo ""

echo "# Application ID (Google TV):  $GOOGLE_TV_PACKAGE"
echo "# Application ID (Fire TV):    $FIRE_TV_PACKAGE   (no Cast — no Google Play Services)"
echo "#"
echo "# SHA-1 fingerprint:"
echo "#   (the field \"SHA-1 fingerprint\" in the console)"
echo ""

KEYSTORE=$(find_keystore) \
    || die "No release keystore found. Looked at \$KEYSTORE_PATH, $CREDS_FILE and the standard directories."
KEYTOOL=$(find_keytool) \
    || die "keytool was not found. Install a JDK 17 (or newer) or put keytool on the PATH."

green "Keystore: $KEYSTORE"

# The password is needed for the keytool call, but it is never printed.
STORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
if [ -z "$STORE_PASSWORD" ] && [ -f "$CREDS_FILE" ]; then
    STORE_PASSWORD=$(sed -n "s/^KEYSTORE_PASSWORD=['\"]\{0,1\}\([^'\"]*\)['\"]\{0,1\}$/\1/p" "$CREDS_FILE" | head -1)
fi
if [ -z "$STORE_PASSWORD" ]; then
    red "No keystore password available — the fingerprint cannot be read automatically."
    echo ""
    echo "Run this instead (the password is asked for, never printed):"
    echo "  $KEYTOOL -list -v -keystore \"$KEYSTORE\" -alias phairplay"
    exit 1
fi

ALIAS="${KEY_ALIAS:-phairplay}"
DETAIL=$("$KEYTOOL" -J-Duser.language=en -list -v -keystore "$KEYSTORE" -alias "$ALIAS" -storepass "$STORE_PASSWORD" 2>/dev/null)
if [ -z "$DETAIL" ]; then
    red "The alias '$ALIAS' could not be read from the keystore."
    echo ""
    echo "Run this instead (the password is asked for, never printed):"
    echo "  $KEYTOOL -list -v -keystore \"$KEYSTORE\" -alias <alias>"
    exit 1
fi

SHA1=$(printf '%s\n' "$DETAIL" | sed -n 's/^[[:space:]]*SHA1:[[:space:]]*//p' | head -1)
SHA256=$(printf '%s\n' "$DETAIL" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1)
OWNER=$(printf '%s\n' "$DETAIL" | sed -n 's/^Owner:[[:space:]]*//p' | head -1)

[ -n "$SHA1" ] || die "The SHA-1 fingerprint could not be read out of the keystore."

echo "$SHA1"
echo ""
echo "# SHA-256 fingerprint (for your own records):"
echo "#   $SHA256"
echo ""
echo "# Owner of the certificate:"
echo "#   $OWNER"
echo "#"
echo "# The console asks for the fingerprint **without** the colons:"
echo "#   $(printf '%s' "$SHA1" | tr -d ':' | tr '[:lower:]' '[:upper:]')"
echo ""

bold "=== What to enter in the console ==="
echo ""
echo "  Application name:      PhairPlay"
echo "  Application ID:        (the console assigns it)"
echo "  Android package name:  $GOOGLE_TV_PACKAGE"
echo "  SHA-1 fingerprint:     $(printf '%s' "$SHA1" | tr -d ':')"
echo ""
echo "  Add the test device:   the serial number of the television"
echo "                          (Settings > About > Build > Serial number)"
echo ""

bold "=== After the console assigned an ID ==="
echo ""
echo "Build with it:"
echo "  ./gradlew :app:assembleGoogletvRelease -Pphairplay.castAppId=<APP_ID>"
echo ""
echo "or, without touching the command line — the user-level Gradle file,"
echo "which is NOT part of the repository:"
echo "  printf 'phairplay.castAppId=<APP_ID>\\n' >> ${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
echo ""
echo "The ID belongs to the account that registered it. The guide with the"
echo "step-by-step is docs/guides/CAST_APP_ID.md."
echo ""
echo "For reference: the default media receiver of Google ($DEFAULT_MEDIA_RECEIVER_ID)"
echo "needs no registration — only a receiver app of your own does."
