#!/usr/bin/env bash
#
# traceability-collect.sh — collect the raw facts of the traceability matrix.
#
# WHAT: walks the test tree, the production tree and the requirement documents
#      and writes the intermediate fact files that build-traceability.sh
#      combines into the matrix.
#
# WHY: the collection is the part that touches the file system; the part that
#      decides what a requirement is proven by is a different concern. Two
#      files keep both of them readable and keep every script of the project
#      below the 400-line rule of docs/CONTRIBUTING.md.
#
# USAGE: this file is sourced by scripts/build-traceability.sh — it is not run
#        on its own. It expects these variables from the caller: TMP_DIR,
#        TEST_DIRS, DEVICE_PLANS, REQUIREMENTS, MATRIX. It defines the fact
#        files CLASS_META, CLASS_DECL, PROD_INDEX, DT_DEFINED, REQ_TEXT and
#        REQ_ENTRIES.
#
TEST_FILES="$TMP_DIR/test-files.txt"
: > "$TEST_FILES"
for dir in $TEST_DIRS; do
  [ -d "$dir" ] || continue
  find "$dir" -type f -name "*Test.kt" 2>/dev/null >> "$TEST_FILES"
done
sort -u "$TEST_FILES" > "$TEST_FILES.sorted" && mv "$TEST_FILES.sorted" "$TEST_FILES"

# The test methods of every test file — ONE pass over the whole tree. A grep per
# file costs ~90 ms on a slow filesystem, which is 13 seconds for 144 greps.
METHODS_RAW="$TMP_DIR/methods-raw.txt"
: > "$METHODS_RAW"
if [ -s "$TEST_FILES" ]; then
  # one pass for the test methods AND for the class declarations
  # shellcheck disable=SC2046
  grep -Hn -Eo 'fun `[^`]*`|(internal |open |abstract |sealed |data |public |private )*class [A-Za-z0-9_]+' $(cat "$TEST_FILES") 2>/dev/null > "$METHODS_RAW" || true
fi

# class name -> package, and class name -> its first two test methods
CLASS_META="$TMP_DIR/class-meta.txt"
printf '#MODE class-meta\n' > "$CLASS_META"
awk -F: '{
  file = $1
  line = $0
  sub(/^[^:]*:[0-9]+:/, "", line)
  if (line !~ /^fun `/) next
  n = split(file, parts, "/")
  pkg = ""
  root = 0
  for (i = 1; i < n - 1; i++) { if (parts[i] == "kotlin") { root = i; break } }
  if (root == 0) { for (i = 1; i < n - 1; i++) { if (parts[i] == "src") { root = i; break } } }
  if (root > 0) {
    pkg = parts[root + 1]
    for (j = root + 2; j < n; j++) pkg = pkg "." parts[j]
  }
  base = file
  sub(/^.*\//, "", base)
  sub(/\.kt$/, "", base)
  sub(/^fun `/, "", line)
  sub(/`$/, "", line)
  count[base]++
  if (count[base] <= 2) methods[base] = methods[base] "«" line "» "
  if (!(base in seen)) { seen[base] = 1; order[++cnt] = base; pkgof[base] = pkg }
}
END {
  for (k = 1; k <= cnt; k++) printf "%s\t%s\t%s\n", order[k], pkgof[order[k]], methods[order[k]]
}' "$METHODS_RAW" >> "$CLASS_META"

# a file without a test method still is a test class
while IFS= read -r file; do
  [ -n "$file" ] || continue
  base="$(basename "$file" .kt)"
  if ! grep -q "^$base	" "$CLASS_META" 2>/dev/null; then
    pkg=""
    case "$file" in
      *"/src/test/kotlin/"*) pkg="$(printf '%s' "${file#*/src/test/kotlin/}" | sed 's|/[^/]*$||' | tr '/' '.')" ;;
      *"/src/"*) pkg="$(printf '%s' "${file#*/src/}" | sed 's|/[^/]*$||' | tr '/' '.')" ;;
    esac
    pkg="${pkg#src.}"
    pkg="${pkg#test.}"
    pkg="${pkg#kotlin.}"
    printf '%s\t%s\t\n' "$base" "$pkg" >> "$CLASS_META"
  fi
done < "$TEST_FILES"

# every class that the test tree declares (a class may live in another file)
CLASS_DECL="$TMP_DIR/class-decl.txt"
sed -nE 's/^[^:]*:[0-9]+:.*class ([A-Za-z0-9_]+)$/\1/p' "$METHODS_RAW" | sort -u > "$CLASS_DECL.body" || true
# the marker goes in FRONT — a ">" would truncate it
{ printf '#MODE class-decl\n'; cat "$CLASS_DECL.body"; } > "$CLASS_DECL"

PROD_INDEX="$TMP_DIR/prod-index.txt"
printf '#MODE prod-index\n' > "$PROD_INDEX"
find app/src -type f -name "*.kt" \
  -not -path "*/test/*" -not -path "*/androidTest/*" -not -path "*/build/*" 2>/dev/null \
  | while IFS= read -r f; do
  printf '%s\t%s\n' "$(basename "$f")" "$f" >> "$PROD_INDEX"
done

DT_DEFINED="$TMP_DIR/dt-defined.txt"
printf '#MODE dt-defined\n' > "$DT_DEFINED"
# A procedure counts as DEFINED when it has a HEADING ("## DT-07 — …"). A mere
# mention in a sentence or in a table of references is not a definition —
# otherwise a cross-reference would make a deleted procedure look like it still
# exists, and the gate could never fail.
for plan in $DEVICE_PLANS; do
  [ -f "$plan" ] || continue
  grep -Eo '^#{2,4} +DT-[0-9][0-9]' "$plan" 2>/dev/null | sed -E 's/^#+ +//' >> "$DT_DEFINED"
done
sort -u "$DT_DEFINED" > "$DT_DEFINED.sorted" && mv "$DT_DEFINED.sorted" "$DT_DEFINED"

REQ_TEXT="$TMP_DIR/req-text.txt"
{
  printf '#MODE req-text\n'
  grep -E '^- (FR|NFR)-[0-9]+:' "$REQUIREMENTS" \
    | sed -E 's/^- ((FR|NFR)-[0-9]+):.*/\1\t&/'
} > "$REQ_TEXT"

REQ_ENTRIES="$TMP_DIR/req-entries.txt"
{
  printf '#MODE req-entries\n'
  # the long text of every requirement
  grep -E '^- (FR|NFR)-[0-9]+:' "$REQUIREMENTS" \
    | sed -E 's/^- ((FR|NFR)-[0-9]+):.*/\1\t&/' \
    | awk -F'\t' '{ print $1 "\tL\t" $2 }'
  # the matrix row of every requirement
  if [ -f "$MATRIX" ]; then
    grep -E '^\| (FR|NFR)-[0-9]+ \|' "$MATRIX" 2>/dev/null \
      | sed -E 's/^\| ((FR|NFR)-[0-9]+) \|.*/\1\t&/' \
      | awk -F'\t' '{ print $1 "\tM\t" $2 }'
  fi
} > "$REQ_ENTRIES"
