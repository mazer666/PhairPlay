#!/usr/bin/env bash
#
# notices.sh — the third-party notices of a release.
#
# WHAT: writes THIRD-PARTY-NOTICES.md: every dependency that the build
#      contains, the decision of the team about it and the advisories that are
#      known.
#
# WHY: an APK that ships a third-party library has to name it. The notice is
#      generated from the SBOM of the build, so it cannot name a component that
#      is not in the build, and it cannot forget one that is. It is honest
#      about a licence that cannot be resolved offline instead of guessing it.
#
# This file is sourced, not executed.
#
# Writes the third-party notices of the release: every dependency that is
# shipped, the decision of the team about it and the advisories that are known.
#
# WHY: an APK that ships a third-party library has to name it. The notice is
# generated from the SBOM of the build, so it cannot name a component that is
# not in the build, and it cannot forget one that is.
artifacts_write_third_party_notices() {
    local version="$1" dir="$2" file="$3" sbom
    sbom="$REPO_ROOT/dist/sbom/phairplay-$version-sbom.cdx.json"
    file="$dir/THIRD-PARTY-NOTICES.md"

    {
        printf '# Third-party notices — PhairPlay %s\n\n' "$version"
        printf 'Generated from the SBOM of this build (`sbom/phairplay-%s-sbom.cdx.json`).\n' "$version"
        printf 'The list below is the list of the components that this build actually\n'
        printf 'contains — not a list from the documentation.\n\n'

        if [ -f "$sbom" ]; then
            printf '## Components\n\n'
            printf '| Component | Version | Licence | PURL |\n|---|---|---|---|\n'
            awk '
                /"bom-ref"/ { ref = $0; sub(/.*"bom-ref": "/, "", ref); sub(/".*/, "", ref) }
                /"group"/   { g = $0; sub(/.*"group": "/, "", g); sub(/".*/, "", g) }
                /"name"/    { if (name == "") { n = $0; sub(/.*"name": "/, "", n); sub(/".*/, "", n) } }
                /"version"/ { v = $0; sub(/.*"version": "/, "", v); sub(/".*/, "", v) }
                /"purl"/    { p = $0; sub(/.*"purl": "/, "", p); sub(/".*/, "", p) }
                /"license"/ { if (lic == "") { l = $0; sub(/.*"name": "/, "", l); sub(/".*/, "", l); lic = l } }
                /"phairplay:usage"/ { u = $0; sub(/.*"value": "/, "", u); sub(/".*/, "", u) }
                /\}/ {
                    if (n != "" && v != "" && ref !~ /phairplay@/) {
                        printf "| %s | %s | %s | `%s` |\n", (g == "" ? n : g ":" n), v, (lic == "" ? "unknown" : lic), p
                    }
                    ref = ""; g = ""; n = ""; v = ""; p = ""; lic = ""; u = ""
                }
            ' "$sbom"
            printf '\n'
        else
            printf '## Components\n\n**The SBOM of this build is missing** — the list of the components\n'
            printf 'cannot be written. This is a defect of the release, not a remark.\n\n'
        fi

        printf '## The decisions of the team\n\n'
        printf 'Every dependency of the build was looked at and accepted\n'
        printf '(`scripts/allowlists/dependency-allowlist.txt`). A dependency that is not\n'
        printf 'listed there has **not** been assessed — the dependency gate reports it as\n'
        printf 'unassessed and it does not count as passed.\n\n'

        printf '## Known advisories\n\n'
        if [ -f "$REPO_ROOT/scripts/allowlists/known-vulnerabilities.txt" ]; then
            printf 'The version ceilings of the dependencies that carry an advisory, with the\n'
            printf 'reason of every waiver:\n\n'
            printf '```\n'
            grep -vE '^[[:space:]]*#|^[[:space:]]*$' "$REPO_ROOT/scripts/allowlists/known-vulnerabilities.txt"
            printf '```\n\n'
        fi

        printf '## The licence texts\n\n'
        printf 'The licence of a component is read from the build metadata of the build; it\n'
        printf 'is shown as `unknown` when it cannot be resolved offline. **The licence\n'
        printf 'texts themselves are not bundled in this release.** They are in the\n'
        printf 'repositories of the projects named by the PURL above. Bundling the texts is\n'
        printf 'an open item of the remediation backlog.\n'
    } > "$file"

    green "Third-party notices: $file"
}
