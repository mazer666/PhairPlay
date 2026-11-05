# parse-version-catalog.awk — read a Gradle version catalog into a flat list.
#
# WHY: the SBOM is built from the version catalog, because that is the one
#      place in the repository where every dependency and its version are
#      written down. The catalog is TOML, and the SBOM builder must not add
#      a TOML parser to the build — that would be a new supply chain
#      dependency just to describe the supply chain. The subset of TOML that
#      the catalog uses is small, so this awk program reads it.
#
# USAGE
#   awk -f parse-version-catalog.awk gradle/libs.versions.toml
#
# OUTPUT (tab separated, one record per line)
#   V <key> <value>
#       a version of the [versions] section
#   L <alias> <group> <name> <version-ref> <version>
#       a library of the [libraries] section. Exactly one of version-ref and
#       version is set: the first names an entry of the [versions] section,
#       the second is a literal version.
#   P <alias> <id> <version-ref> <version>
#       a plugin of the [plugins] section, same convention as the libraries.
#
# EXIT CODES
#   0  the catalog was read
#   1  the input could not be read
#

function trim(text) {
  gsub(/^[ \t]+/, "", text)
  gsub(/[ \t]+$/, "", text)
  return text
}

# The value of "name = ..." inside a table, without the quotes.
function field(text, name,   idx, rest) {
  idx = index(text, name " ")
  if (idx == 0) return ""
  rest = substr(text, idx + length(name) + 1)
  if (match(rest, /"[^"]*"/)) {
    return substr(rest, RSTART + 1, RLENGTH - 2)
  }
  return ""
}

{
  line = $0
  sub(/[ \t]*#.*$/, "", line)          # a trailing comment
  line = trim(line)
  if (line == "") next

  if (line ~ /^\[/) {
    section = line
    gsub(/^\[/, "", section)
    gsub(/\]$/, "", section)
    section = trim(section)
    next
  }

  # The value may contain more than one "=", so the split happens at the
  # first one only.
  eq = index(line, "=")
  if (eq == 0) next
  key = trim(substr(line, 1, eq - 1))
  value = trim(substr(line, eq + 1))

  if (section == "versions") {
    if (match(value, /"[^"]*"/)) {
      printf "V\t%s\t%s\n", key, substr(value, RSTART + 1, RLENGTH - 2)
    }
    next
  }

  if (section == "libraries" || section == "plugins") {
    group = field(value, "group")
    name = field(value, "name")
    id = field(value, "id")
    version_ref = field(value, "version.ref")
    literal = field(value, "version")
    if (section == "libraries") {
      if (name == "") next
      printf "L\t%s\t%s\t%s\t%s\t%s\n", key, group, name, version_ref, literal
    } else {
      if (id == "") next
      printf "P\t%s\t%s\t\t%s\t%s\n", key, id, version_ref, literal
    }
    next
  }
}
