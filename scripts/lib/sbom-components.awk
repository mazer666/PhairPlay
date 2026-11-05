# sbom-components.awk — read the components out of a CycloneDX document.
#
# WHY: the dependency gate must read the SBOM, so that the gate and the
#      document can never disagree about what is in the build. The gate runs
#      offline, so the document is read here — without jq, which is not
#      installed on every machine the owner uses.
#
# USAGE
#   awk -f sbom-components.awk <file.cdx.json>
#
# OUTPUT
#   "<group>:<artifact>:<version>" per component of the top level
#   "components" array.
#
# EXIT CODES
#   0  the components were read
#   1  the document is not readable
#

function skip_space(   c) {
  while (i <= n) {
    c = substr(doc, i, 1)
    if (c == " " || c == "\t" || c == "\n" || c == "\r") {
      i++
    } else {
      return
    }
  }
}

function unescape(text) {
  gsub(/\\"/, "\"", text)
  gsub(/\\\\/, "\\", text)
  gsub(/\\n/, "\n", text)
  gsub(/\\t/, "\t", text)
  gsub(/\\r/, "\r", text)
  return text
}

{ doc = doc $0 "\n" }

END {
  n = length(doc)
  i = 1
  depth = 0
  found = 0

  while (i <= n) {
    skip_space()
    if (i > n) break
    c = substr(doc, i, 1)

    if (c == "{" || c == "[") {
      depth++
      ctype[depth] = (c == "{" ? "object" : "array")
      if (depth == 1) {
        ckey[depth] = ""
      } else if (ctype[depth - 1] == "object") {
        ckey[depth] = pending_key
      } else {
        ckey[depth] = ckey[depth - 1]
      }
      i++
      continue
    }

    if (c == "}" || c == "]") {
      if (depth == 3 && ctype[2] == "array" && ckey[2] == "components") {
        if (group != "" && name != "" && version != "") {
          printf "%s:%s:%s\n", group, name, version
        }
        group = ""
        name = ""
        version = ""
      }
      depth--
      i++
      pending_key = ""
      continue
    }

    if (c == ",") {
      i++
      pending_key = ""
      continue
    }

    if (c == ":") {
      i++
      after_colon = 1
      continue
    }

    if (c == "\"") {
      start = i
      i++
      while (i <= n) {
        cc = substr(doc, i, 1)
        if (cc == "\\") {
          i += 2
          continue
        }
        if (cc == "\"") {
          i++
          break
        }
        i++
      }
      value = unescape(substr(doc, start + 1, i - start - 2))

      if (ctype[depth] == "object" && !after_colon) {
        objkey[depth] = value
        pending_key = value
      } else {
        # a value inside an object
        if (depth == 3 && ctype[2] == "array" && ckey[2] == "components") {
          if (objkey[depth] == "group") group = value
          if (objkey[depth] == "name") name = value
          if (objkey[depth] == "version") version = value
          found = 1
        }
      }
      after_colon = 0
      continue
    }

    # a number, true, false or null
    i++
    after_colon = 0
  }

  if (!found) {
    print "ERROR: no component found in the document" > "/dev/stderr"
    exit 1
  }
}
