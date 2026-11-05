# sbom-json.awk — write the CycloneDX 1.5 document of PhairPlay.
#
# WHY: the SBOM has to be produced without adding a dependency to the build:
#      a plugin that generates an SBOM would be one more artifact to trust
#      and one more thing that can fail in the middle of a release. The
#      document is therefore written here, from the inventory that the shell
#      script assembles.
#
# USAGE
#   awk -f sbom-json.awk \
#       -v serial=... -v timestamp=... -v appversion=... -v commit=... \
#       -v gradle=... -v jdk=... -v sdk=... -v builds=<file> < inventory.tsv
#
#   The inventory is the tab separated output of parse-version-catalog.awk
#   plus one X record per hard coded Maven coordinate:
#     V <key> <value>
#     L <alias> <group> <name> <version-ref> <version>
#     P <alias> <id> <version-ref> <version>
#     X <group> <artifact> <version> <source>
#
# EXIT CODES
#   0  the document was written
#   1  the inventory could not be read
#

function jesc(text,   out) {
  gsub(/\\/, "\\\\", text)
  gsub(/"/, "\\\"", text)
  gsub(/\t/, "\\t", text)
  gsub(/\r/, "\\r", text)
  gsub(/\n/, "\\n", text)
  return text
}

function accessor(alias) {
  gsub(/-/, ".", alias)
  return alias
}

function is_used(needle) {
  if (needle == "") return 0
  return index(BUILD, needle) > 0
}

function component(ref, type, group, name, version, scope, usage, source,
                    first_component,   text, license_reason) {
  if (first_component) {
    printf "    {\n"
  } else {
    printf ",\n    {\n"
  }
  text = "      \"bom-ref\": \"" jesc(ref) "\",\n"
  text = text "      \"type\": \"" type "\",\n"
  if (group != "") text = text "      \"group\": \"" jesc(group) "\",\n"
  text = text "      \"name\": \"" jesc(name) "\",\n"
  text = text "      \"version\": \"" jesc(version) "\",\n"
  text = text "      \"purl\": \"" jesc(ref) "\",\n"
  text = text "      \"scope\": \"" scope "\",\n"
  text = text "      \"licenses\": [\n"
  text = text "        { \"license\": { \"name\": \"unknown\" } }\n"
  text = text "      ],\n"
  text = text "      \"properties\": [\n"
  text = text "        { \"name\": \"phairplay:usage\", \"value\": \"" usage "\" },\n"
  text = text "        { \"name\": \"phairplay:declared-in\", \"value\": \"" jesc(source) "\" },\n"
  license_reason = "unknown — the version catalog declares no license and the SBOM is built offline, so the license cannot be resolved"
  text = text "        { \"name\": \"phairplay:license:reason\", \"value\": \"" license_reason "\" }\n"
  text = text "      ]\n"
  text = text "    }"
  printf "%s", text
  return 0
}

BEGIN {
  FS = "\t"
  BUILDS_FILE = (builds == "" ? "" : builds)
  BUILD = ""
  if (BUILDS_FILE != "") {
    while ((getline line < BUILDS_FILE) > 0) {
      BUILD = BUILD line "\n"
    }
    close(BUILDS_FILE)
  }
}

{
  kind = $1
  if (kind == "V") {
    VERS[$2] = $3
    next
  }
  if (kind == "L") {
    L_ALIAS[++L_COUNT] = $2
    L_GROUP[L_COUNT] = $3
    L_NAME[L_COUNT] = $4
    L_REF[L_COUNT] = $5
    L_VER[L_COUNT] = $6
    next
  }
  if (kind == "P") {
    P_ALIAS[++P_COUNT] = $2
    P_ID[P_COUNT] = $3
    P_REF[P_COUNT] = $5
    P_VER[P_COUNT] = $6
    next
  }
  if (kind == "X") {
    X_GROUP[++X_COUNT] = $2
    X_NAME[X_COUNT] = $3
    X_VER[X_COUNT] = $4
    X_SRC[X_COUNT] = $5
    next
  }
}

END {
  if (L_COUNT == 0 && P_COUNT == 0 && X_COUNT == 0) {
    print "ERROR: the inventory is empty" > "/dev/stderr"
    exit 1
  }

  root_ref = "pkg:maven/com.phairplay/phairplay@" appversion

  printf "{\n"
  printf "  \"bomFormat\": \"CycloneDX\",\n"
  printf "  \"specVersion\": \"1.5\",\n"
  printf "  \"serialNumber\": \"urn:uuid:%s\",\n", serial
  printf "  \"version\": 1,\n"
  printf "  \"metadata\": {\n"
  printf "    \"timestamp\": \"%s\",\n", timestamp
  printf "    \"tools\": {\n"
  printf "      \"components\": [\n"
  printf "        { \"type\": \"application\", \"name\": \"gradle\", \"version\": \"%s\" },\n", jesc(gradle)
  printf "        { \"type\": \"application\", \"name\": \"jdk\", \"version\": \"%s\" },\n", jesc(jdk)
  printf "        { \"type\": \"framework\", \"name\": \"android-sdk\", \"version\": \"%s\" }\n", jesc(sdk)
  printf "      ]\n"
  printf "    },\n"
  printf "    \"component\": {\n"
  printf "      \"bom-ref\": \"%s\",\n", jesc(root_ref)
  printf "      \"type\": \"application\",\n"
  printf "      \"group\": \"com.phairplay\",\n"
  printf "      \"name\": \"phairplay\",\n"
  printf "      \"version\": \"%s\",\n", jesc(appversion)
  printf "      \"purl\": \"%s\"\n", jesc(root_ref)
  printf "    },\n"
  printf "    \"properties\": [\n"
  printf "      { \"name\": \"phairplay:commit\", \"value\": \"%s\" },\n", jesc(commit)
  printf "      { \"name\": \"phairplay:source\", \"value\": \"gradle/libs.versions.toml\" }\n"
  printf "    ]\n"
  printf "  },\n"

  printf "  \"components\": [\n"
  first = 1
  for (i = 1; i <= L_COUNT; i++) {
    version = L_VER[i]
    if (version == "") {
      version = (L_REF[i] in VERS) ? VERS[L_REF[i]] : "unknown"
    }
    SEEN[L_GROUP[i] ":" L_NAME[i]] = 1
    usage = is_used("libs." accessor(L_ALIAS[i])) ? "used" : "declared-unused"
    component("pkg:maven/" L_GROUP[i] "/" L_NAME[i] "@" version,
              "library", L_GROUP[i], L_NAME[i], version,
              "required", usage, "gradle/libs.versions.toml", first)
    first = 0
    REF[++REF_COUNT] = "pkg:maven/" L_GROUP[i] "/" L_NAME[i] "@" version
  }
  for (i = 1; i <= P_COUNT; i++) {
    version = P_VER[i]
    if (version == "") {
      version = (P_REF[i] in VERS) ? VERS[P_REF[i]] : "unknown"
    }
    SEEN[P_ID[i] ":" P_ID[i] ".gradle.plugin"] = 1
    usage = is_used("libs.plugins." accessor(P_ALIAS[i])) ? "used" : "declared-unused"
    component("pkg:maven/" P_ID[i] "/" P_ID[i] ".gradle.plugin@" version,
              "library", P_ID[i], P_ID[i] ".gradle.plugin", version,
              "required", usage, "gradle/libs.versions.toml", first)
    first = 0
    REF[++REF_COUNT] = "pkg:maven/" P_ID[i] "/" P_ID[i] ".gradle.plugin@" version
  }
  for (i = 1; i <= X_COUNT; i++) {
    # A coordinate that the catalog already describes is one dependency, not
    # two. The catalog wins, because it carries the version reference.
    if ((X_GROUP[i] ":" X_NAME[i]) in SEEN) continue
    SEEN[X_GROUP[i] ":" X_NAME[i]] = 1
    usage = "used"
    component("pkg:maven/" X_GROUP[i] "/" X_NAME[i] "@" X_VER[i],
              "library", X_GROUP[i], X_NAME[i], X_VER[i],
              "required", usage, X_SRC[i], first)
    first = 0
    REF[++REF_COUNT] = "pkg:maven/" X_GROUP[i] "/" X_NAME[i] "@" X_VER[i]
  }
  printf "\n  ],\n"

  printf "  \"dependencies\": [\n"
  printf "    {\n"
  printf "      \"ref\": \"%s\",\n", jesc(root_ref)
  printf "      \"dependsOn\": [\n"
  for (i = 1; i <= REF_COUNT; i++) {
    if (i > 1) printf ",\n"
    printf "        { \"ref\": \"%s\" }", jesc(REF[i])
  }
  printf "\n      ]\n"
  printf "    }"
  for (i = 1; i <= REF_COUNT; i++) {
    printf ",\n    {\n"
    printf "      \"ref\": \"%s\",\n", jesc(REF[i])
    printf "      \"dependsOn\": []\n"
    printf "    }"
  }
  printf "\n  ]\n"
  printf "}\n"
}
