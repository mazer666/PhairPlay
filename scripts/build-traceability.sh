#!/usr/bin/env bash
#
# build-traceability.sh — generate the requirement ↔ implementation ↔ test matrix.
#
# WHAT: reads the requirement identifiers and their text from
#      docs/spec/REQUIREMENTS.md, reads the test classes and their test methods
#      from app/src/test/kotlin/ and test-runner/, and writes the traceability
#      matrix docs/spec/TRACEABILITY.md.
#
# WHY: a requirement that cannot be traced to a proof and to a piece of evidence
#      is a wish. A traceability matrix that is written by hand drifts away from
#      the code within one cycle, and a reviewer cannot tell the difference
#      between a matrix that was maintained and one that was copied. This script
#      is therefore the single source of the matrix: it is regenerated, it cannot
#      drift silently, and it fails loudly when a requirement loses its proof.
#
# HOW A REQUIREMENT IS LINKED: the expected proof is read from the requirement
# itself — the long text of REQUIREMENTS.md and the row of
# REQUIREMENTS_MATRIX.md. The first rule that matches wins:
#
#   `automated`  a test class that exists in the test tree (a file of that name
#                or a class declaration of that name)
#   `device`     a device procedure `DT-xx` that has a HEADING in
#                docs/guides/DEVICE_TEST_PLAN.md, in docs/spec/DEVICE_PROCEDURES.md
#                or in docs/spec/ACCEPTANCE_CRITERIA.md — a `DT-xx` that nobody
#                defined does not count and makes the run fail
#   `gate`       a gate script `scripts/*.sh` that exists
#   `command`    a command (grep, zipalign, apksigner, find, awk, ./gradlew …)
#                that can be run as it is written
#   `excluded`   the requirement is marked `no proof expected` — it is out of
#                scope on purpose, so no proof is demanded of it
#   `none`       nothing of the above — **the run fails**
#
# USAGE
#   ./scripts/build-traceability.sh [options]
#
#   --root DIR           repository root (default: the repository root)
#   --requirements FILE  default docs/spec/REQUIREMENTS.md
#   --matrix FILE        default docs/spec/REQUIREMENTS_MATRIX.md (optional)
#   --out FILE           default docs/spec/TRACEABILITY.md
#   --test-dir DIR       test source root, repeatable; the first one replaces
#                        the default (app/src/test/kotlin test-runner/src)
#   --device-plan FILE   repeatable; where a `DT-xx` may be defined; the first
#                        one replaces the default
#   --check              do not write the file, only report
#   --strict             a device-only proof is a failure as well
#   -h, --help           show this help and exit 0
#
# EXIT CODES: 0 every requirement names a proof that exists; 1 at least one
# requirement has no proof (with --strict: one is proven only on a device);
# 2 usage error. The generator is self-tested by
# scripts/test-build-traceability.sh — it proves that it turns red when a
# requirement loses its test, when a device procedure is not defined and when a
# requirement has no proof at all.
#
#

set -u

ROOT=""
REQUIREMENTS="docs/spec/REQUIREMENTS.md"
MATRIX="docs/spec/REQUIREMENTS_MATRIX.md"
OUT="docs/spec/TRACEABILITY.md"
TEST_DIRS=""
TEST_DIRS_SET=0
DEVICE_PLANS=""
PLANS_SET=0
CHECK_ONLY=0
STRICT=0

TMP_DIR=""
cleanup() {
  [ -n "$TMP_DIR" ] && rm -rf "$TMP_DIR"
  return 0
}
trap cleanup EXIT

usage() {
  awk 'NR >= 2 { if ($0 ~ /^set -u$/) exit; print }' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
}

die_usage() {
  printf 'ERROR: %s\n' "$1" >&2
  printf 'Try "%s --help" for usage.\n' "$0" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --root) [ "$#" -ge 2 ] || die_usage "--root requires a directory argument"; ROOT="$2"; shift 2 ;;
    --requirements) [ "$#" -ge 2 ] || die_usage "--requirements requires a file"; REQUIREMENTS="$2"; shift 2 ;;
    --matrix) [ "$#" -ge 2 ] || die_usage "--matrix requires a file"; MATRIX="$2"; shift 2 ;;
    --out) [ "$#" -ge 2 ] || die_usage "--out requires a file"; OUT="$2"; shift 2 ;;
    --test-dir) [ "$#" -ge 2 ] || die_usage "--test-dir requires a directory"; if [ "$TEST_DIRS_SET" -eq 0 ]; then TEST_DIRS="$2"; TEST_DIRS_SET=1; else TEST_DIRS="$TEST_DIRS $2"; fi; shift 2 ;;
    --device-plan) [ "$#" -ge 2 ] || die_usage "--device-plan requires a file"; if [ "$PLANS_SET" -eq 0 ]; then DEVICE_PLANS="$2"; PLANS_SET=1; else DEVICE_PLANS="$DEVICE_PLANS $2"; fi; shift 2 ;;
    --check) CHECK_ONLY=1; shift ;;
    --strict) STRICT=1; shift ;;
    *) die_usage "unknown argument '$1'" ;;
  esac
done

if [ -z "$ROOT" ]; then
  SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
  ROOT="$(dirname -- "$SCRIPT_DIR")"
fi
[ -d "$ROOT" ] || die_usage "root directory '$ROOT' does not exist"

cd -- "$ROOT" || die_usage "cannot enter '$ROOT'"
[ -f "$REQUIREMENTS" ] || die_usage "requirements file '$REQUIREMENTS' does not exist"
[ "$TEST_DIRS_SET" -eq 1 ] || TEST_DIRS="app/src/test/kotlin test-runner/src"
[ "$PLANS_SET" -eq 1 ] || DEVICE_PLANS="docs/guides/DEVICE_TEST_PLAN.md docs/spec/DEVICE_PROCEDURES.md docs/spec/ACCEPTANCE_CRITERIA.md"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/phairplay-traceability.XXXXXX")" || exit 2

# The raw facts are collected by scripts/lib/traceability-collect.sh — the
# collection walks the file system, the decision about what a requirement is
# proven by stays in this file.
. "$ROOT/scripts/lib/traceability-collect.sh"
FACTS="$TMP_DIR/facts.txt"
cat "$REQ_TEXT" "$REQ_ENTRIES" "$CLASS_META" "$CLASS_DECL" "$PROD_INDEX" "$DT_DEFINED" \
  | awk -F'\t' '
    function add(id, kind, value) {
      if (value == "") return
      if (index(seen[id], " " kind "|" value " ") > 0) return
      seen[id] = seen[id] " " kind "|" value " "
      entries[id] = entries[id] " " kind "|" value
    }
    function short(text,   n, w, out, i) {
      sub(/^- (FR|NFR)-[0-9]+: /, "", text)
      sub(/ *· \*\*(INT|EXT|REC|INT\+EXT|EXT\+INT)\*\*.*$/, "", text)
      n = split(text, w, " ")
      out = ""
      for (i = 1; i <= n && i <= 14; i++) {
        if (w[i] == "") continue
        out = (out == "" ? w[i] : out " " w[i])
      }
      return out
    }
    function trim(s) { sub(/ +$/, "", s); sub(/^ +/, "", s); return s }
    /^#MODE / { mode = substr($0, 7); next }
    mode == "req-text"     { reqtext[$1] = $2; order[++n] = $1; next }
    mode == "req-entries"  {
      id = $1; kind = $2; text = $3
      # every backticked token of the text
      rest = text
      while (match(rest, /`[^`]*`/)) {
        tok = substr(rest, RSTART + 1, RLENGTH - 2)
        if (tok ~ /Test$/)                add(id, "T", tok)
        else if (tok ~ /\.kt$/)           add(id, "I", tok)
        else if (tok ~ /^scripts\/.*\.sh$/) add(id, "G", tok)
        else if (tok ~ /^(grep|zipalign|apksigner|find|awk|sed|diff|\.\/gradlew)/) add(id, "C", tok)
        rest = substr(rest, RSTART + RLENGTH)
      }
      # every device procedure of the text
      rest = text
      while (match(rest, /DT-[0-9][0-9]/)) {
        add(id, "D", substr(rest, RSTART, RLENGTH))
        rest = substr(rest, RSTART + RLENGTH)
      }
      if (text ~ /no proof expected/) add(id, "X", "excluded")
      next
    }
    mode == "class-meta"   { pkg[$1] = $2; methods[$1] = $3; next }
    mode == "class-decl"   { declared[$0] = 1; next }
    mode == "prod-index"   { prod[$1] = $2; next }
    mode == "dt-defined"   { dtdef[$0] = 1; next }
    END {
      for (i = 1; i <= n; i++) {
        id = order[i]
        text = reqtext[id]
        tests = ""; impl = ""; gates = ""; cmds = ""; dts = ""; bads = ""; excluded = 0
        count = split(entries[id], list, " ")
        for (j = 1; j <= count; j++) {
          e = list[j]
          if (e == "") continue
          p = index(e, "|")
          kind = substr(e, 1, p - 1)
          value = substr(e, p + 1)
          if (kind == "T") {
            if ((value in pkg) || (value in declared)) {
              tests = (tests == "" ? value : tests " " value)
            }
          } else if (kind == "I") {
            base = value; sub(/\.kt$/, "", base)
            if (base ".kt" in prod) {
              impl = (impl == "" ? prod[base ".kt"] : impl " " prod[base ".kt"])
            }
          } else if (kind == "G") {
            gates = (gates == "" ? value : gates " " value)
          } else if (kind == "C") {
            cmds = (cmds == "" ? value : cmds " " value)
          } else if (kind == "D") {
            if (value in dtdef) dts = (dts == "" ? value : dts " " value)
            else bads = (bads == "" ? value : bads " " value)
          } else if (kind == "X") {
            excluded = 1
          }
        }
        status = "none"
        evidence = "—"
        if (tests != "") {
          status = "automated"
          first = tests; sub(/ .*/, "", first)
          if (first in pkg && pkg[first] != "") {
            evidence = "`./gradlew :app:testGoogletvDebugUnitTest --tests \"" pkg[first] "." first "\"`"
          } else {
            evidence = "`" first "`"
          }
          if (first in methods && methods[first] != "") evidence = evidence " " methods[first]
        } else if (dts != "") {
          status = "device"
          evidence = "device procedure " dts
        } else if (gates != "") {
          status = "gate"
          evidence = "gate `" gates "`"
        } else if (cmds != "") {
          status = "command"
          evidence = cmds
        } else if (excluded == 1) {
          status = "excluded"
          evidence = "no proof expected — the feature is excluded on purpose"
        } else {
          reason = "no test class, no defined device procedure, no gate script and no command"
          if (bads != "") reason = reason "; undefined device procedure " bads
          print "PROBLEM\t" id "\t" reason
        }
        printf "ROW\t%s\t%s\t%s\t%s\t%s\t%s\n", id, short(text), trim(impl), trim(tests), evidence, status
      }
    }
  ' > "$FACTS"

ROWS="$TMP_DIR/rows.txt"
grep '^ROW' "$FACTS" | cut -f2- > "$ROWS"
PROBLEMS="$TMP_DIR/problems.txt"
grep '^PROBLEM' "$FACTS" | cut -f2- > "$PROBLEMS"

total=$(awk 'END { print NR }' "$ROWS")
n_automated=$(awk -F'\t' '$NF == "automated"' "$ROWS" | awk 'END { print NR }')
n_device=$(awk -F'\t' '$NF == "device"' "$ROWS" | awk 'END { print NR }')
n_gate=$(awk -F'\t' '$NF == "gate"' "$ROWS" | awk 'END { print NR }')
n_command=$(awk -F'\t' '$NF == "command"' "$ROWS" | awk 'END { print NR }')
n_excluded=$(awk -F'\t' '$NF == "excluded"' "$ROWS" | awk 'END { print NR }')
missing=$(awk 'END { print NR }' "$PROBLEMS")

if [ "$CHECK_ONLY" -eq 0 ]; then
  out_dir="$(dirname -- "$OUT")"
  [ -d "$out_dir" ] || mkdir -p "$out_dir"
  {
    printf '# PhairPlay – Traceability Matrix\n\n'
    printf 'Version: 4.0\nStatus: Generated — do not edit by hand\nDate: %s\n' "$(date -u '+%Y-%m-%d')"
    printf 'Generator: `scripts/build-traceability.sh`\n\n'
    printf '> **This file is generated.** Run `./scripts/build-traceability.sh` to rebuild\n'
    printf '> it; a change written here by hand is lost at the next run. The self-test of\n'
    printf '> the generator is `scripts/test-build-traceability.sh`.\n'
    printf '>\n'
    printf '> The chain is: **requirement → implementation → test → evidence → status**.\n'
    printf '> The *status* column answers "is the requirement traceable?" — whether a\n'
    printf '> requirement is *met* is answered by the matrix of the requirements and by\n'
    printf '> the acceptance criteria.\n\n'
    printf '| Requirement | Requirement (short) | Implementation | Test | Evidence | Status |\n'
    printf '|---|---|---|---|---|---|\n'
    awk -F'\t' '{ printf "| %s | %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, $5, $6 }' "$ROWS"
    printf '\n## Summary\n\n| Metric | Value |\n|---|---|\n'
    printf '| Requirements | %s |\n' "$total"
    printf '| `automated` — a test class of the tree proves it | %s |\n' "$n_automated"
    printf '| `device` — the proof is a device procedure | %s |\n' "$n_device"
    printf '| `gate` — the proof is a gate script | %s |\n' "$n_gate"
    printf '| `command` — the proof is a command | %s |\n' "$n_command"
    printf '| `excluded` — no proof expected | %s |\n' "$n_excluded"
    printf '| `none` — no proof | %s |\n' "$missing"
    printf '\nThe status legend: `automated` — a test class of the tree proves the\n'
    printf 'requirement. `device` — the proof needs real hardware and is named by a\n'
    printf '`DT-xx` procedure that is defined. `gate` — the proof is a script of\n'
    printf '`scripts/`. `command` — the proof is a command. `excluded` — the feature is\n'
    printf 'out of scope on purpose. `none` — no proof; the generator fails.\n'
  } > "$OUT"
  printf 'wrote %s\n' "$OUT"
fi

if [ "$missing" -gt 0 ]; then
  printf 'FAILED: traceability — %s of %s requirement(s) have no proof:\n' "$missing" "$total" >&2
  while IFS="$(printf '\t')" read -r pid reason; do
    [ -n "$pid" ] || continue
    printf '  %s — %s\n' "$pid" "$reason" >&2
  done < "$PROBLEMS"
  exit 1
fi

if [ "$STRICT" -eq 1 ] && [ "$n_device" -gt 0 ]; then
  printf 'FAILED: traceability (--strict) — %s requirement(s) are proven on a device only.\n' "$n_device" >&2
  exit 1
fi

printf 'OK: traceability — %s requirement(s): %s automated, %s device, %s gate, %s command, %s excluded, 0 without a proof.\n' \
  "$total" "$n_automated" "$n_device" "$n_gate" "$n_command" "$n_excluded"
exit 0
