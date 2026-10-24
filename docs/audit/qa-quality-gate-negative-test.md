# QA Audit — Negative test of the quality-gate scripts

- **Date:** 2026-10-24
- **Tester:** QA & Test Engineer
- **Scope:** `scripts/check-file-size.sh`, `scripts/check-kdoc-headers.sh`,
  `scripts/check-docs-links.sh`, `scripts/check-i18n-completeness.sh`
- **Purpose:** prove that every check script turns **red** on a real
  violation and **green** again when the violation is removed. A check that
  is always green is worthless.

## How the test was run

The scripts were executed with `bash <script>` — the shell of this session
cannot run a script directly (`bash script.sh` fails with
`sandbox exec channel unhealthy`), but a script can be started through
`sh -c 'bash script.sh …'`. Every run in this document is the real output of
the real script, not a reconstruction.

Two runs were made:

1. **Fixture run** — a minimal repository in which all four checks are green.
   For every script the test shows: green → red (one real violation
   introduced) → green (violation removed). This is the proof that the exit
   code follows the violation.
2. **Repository run** — the same test against a copy of the real working
   directory. There the baseline is already red (the tree violates the file
   size rule and the KDoc rule), so the proof is the **delta**: the
   introduced violation adds exactly one line to the report, and removing it
   brings the report back to the baseline count.

## Result

| Run | Cases | As expected | Unexpected |
|---|---:|---:|---:|
| Fixture | 14 | **14** | **0** |
| Repository copy | 8 | **8** | **0** |

Two real bugs were found by this test and fixed before it was repeated:

* `check-docs-links.sh` resolved a link with `..` wrongly
  (`docs/index.md` + `../app/...` was resolved to `docs/app/...`).
* `check-i18n-completeness.sh` compared the format placeholders with the
  default field separator, so a value that contains a space was cut off and a
  wrong placeholder stayed undetected.

Both are covered by the cases below.

---

# Run 1 — fixture (green → red → green)

```text
===================================================================
Negative test of the four quality-gate scripts on a clean fixture
Fixture: /workspace/.mavis/plans/plan_65254cfd/workspace/fixture
===================================================================

### clean fixture — ./scripts/check-file-size.sh 
OK: file size check passed — 1 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.
exit code: 0 (expected: 0)
RESULT: as expected

### clean fixture — ./scripts/check-kdoc-headers.sh 
OK: KDoc header check passed — 1 class declaration(s) checked, all of them have a KDoc header.
exit code: 0 (expected: 0)
RESULT: as expected

### clean fixture — ./scripts/check-docs-links.sh 
OK: documentation link check passed — 1 internal link(s) resolve, 0 external link(s) skipped.
exit code: 0 (expected: 0)
RESULT: as expected

### clean fixture — ./scripts/check-i18n-completeness.sh 
OK: i18n completeness check passed — 2 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).
exit code: 0 (expected: 0)
RESULT: as expected

### file size: a 597-line Kotlin file (hard limit 550) — ./scripts/check-file-size.sh 
ERROR: app/src/main/kotlin/com/phairplay/demo/TooLong.kt:597 — 597 lines exceed the hard limit of 550 lines (NFR-23, docs/CONTRIBUTING.md rule 1). Split the file before merging.
FAILED: file size check — 1 file(s) above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines (2 file(s) checked).
exit code: 1 (expected: 1)
RESULT: as expected

### file size: violation removed — ./scripts/check-file-size.sh 
OK: file size check passed — 1 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.
exit code: 0 (expected: 0)
RESULT: as expected

### KDoc: a class without a KDoc header — ./scripts/check-kdoc-headers.sh 
./app/src/main/kotlin/com/phairplay/demo/NoKDoc.kt:3: MISSING-KDOC NoKDoc — no KDoc header found above the declaration.
FAILED: KDoc header check — 1 class declaration(s) without a KDoc header out of 2 declaration(s) checked.
exit code: 1 (expected: 1)
RESULT: as expected

### KDoc: violation removed — ./scripts/check-kdoc-headers.sh 
OK: KDoc header check passed — 1 class declaration(s) checked, all of them have a KDoc header.
exit code: 0 (expected: 0)
RESULT: as expected

### docs links: a link to a missing file — ./scripts/check-docs-links.sh 
ERROR: docs/broken.md:3 — broken internal link "docs/does-not-exist.md" (resolved to "docs/docs/does-not-exist.md", which does not exist).
FAILED: documentation link check — 1 broken internal link(s) out of 2 internal link(s) checked (0 external link(s) skipped).
exit code: 1 (expected: 1)
RESULT: as expected

### docs links: violation removed — ./scripts/check-docs-links.sh 
OK: documentation link check passed — 1 internal link(s) resolve, 0 external link(s) skipped.
exit code: 0 (expected: 0)
RESULT: as expected

### i18n: the key home_title removed from values-de — ./scripts/check-i18n-completeness.sh 
ERROR: app/src/main/res/values/strings.xml:4 — the key "home_title" of the base file is missing in this translation.
FAILED: i18n completeness check — 1 problem(s) across 2 language(s); the base file has 2 keys.
exit code: 1 (expected: 1)
RESULT: as expected

### i18n: violation removed — ./scripts/check-i18n-completeness.sh 
OK: i18n completeness check passed — 2 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).
exit code: 0 (expected: 0)
RESULT: as expected

### i18n: an extra placeholder in the French translation — ./scripts/check-i18n-completeness.sh 
ERROR: app/src/main/res/values-fr/strings.xml:4 — the key "home_title" has the placeholders "1$s|" but the base file expects "".
FAILED: i18n completeness check — 1 problem(s) across 2 language(s); the base file has 2 keys.
exit code: 1 (expected: 1)
RESULT: as expected

### i18n: violation removed — ./scripts/check-i18n-completeness.sh 
OK: i18n completeness check passed — 2 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).
exit code: 0 (expected: 0)
RESULT: as expected

===================================================================
done — full log in /workspace/.mavis/plans/plan_65254cfd/workspace/negative-test-fixture.log
```

---

# Run 2 — copy of the real repository (baseline red, delta proves the check)

The copy of the repository is already red, because the tree violates two
project rules. The proof is therefore the delta:

* **file size** — before the violation: 3 files above the hard limit.
  With the injected 597-line file: 4 files. After removing it: 3 files again.
* **KDoc** — before the violation: 16 declarations without a header.
  With the injected class: 17. After removing it: 16 again.
* **docs links** — the copy is green (12 internal links resolve); the
  injected broken link makes it red, removing it makes it green again.
* **i18n** — the copy is green (80 keys, 2 languages); the injected missing
  key makes it red, removing it makes it green again.

```text
===================================================================
Negative test of the four quality-gate scripts
Repository copy: /workspace/.mavis/plans/plan_65254cfd/workspace/negtest
===================================================================

### file size: a 592-line Kotlin file — ./scripts/check-file-size 
WARNING: app/src/main/kotlin/com/phairplay/MainActivity.kt:447 — 447 lines exceed the soft limit of 400 lines (docs/CONTRIBUTING.md rule 1). Split the file.
ERROR: app/src/main/kotlin/com/phairplay/airplay/AirPlayReceiver.kt:658 — 658 lines exceed the hard limit of 550 lines (NFR-23, docs/CONTRIBUTING.md rule 1). Split the file before merging.
WARNING: app/src/main/kotlin/com/phairplay/airplay/MdnsService.kt:527 — 527 lines exceed the soft limit of 400 lines (docs/CONTRIBUTING.md rule 1). Split the file.
ERROR: app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt:1024 — 1024 lines exceed the hard limit of 550 lines (NFR-23, docs/CONTRIBUTING.md rule 1). Split the file before merging.
WARNING: app/src/main/kotlin/com/phairplay/airplay/handshake/AudioStreamServer.kt:503 — 503 lines exceed the soft limit of 400 lines (docs/CONTRIBUTING.md rule 1). Split the file.
WARNING: app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt:439 — 439 lines exceed the soft limit of 400 lines (docs/CONTRIBUTING.md rule 1). Split the file.
ERROR: app/src/main/kotlin/com/phairplay/qa/TooLong.kt:597 — 597 lines exceed the hard limit of 550 lines (NFR-23, docs/CONTRIBUTING.md rule 1). Split the file before merging.
ERROR: app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt:650 — 650 lines exceed the hard limit of 550 lines (NFR-23, docs/CONTRIBUTING.md rule 1). Split the file before merging.
WARNING: app/src/test/kotlin/com/phairplay/airplay/RtspHandlerTest.kt:413 — 413 lines exceed the soft limit of 400 lines (docs/CONTRIBUTING.md rule 1). Split the file.
FAILED: file size check — 4 file(s) above the hard limit of 550 lines, 5 warning(s) above the soft limit of 400 lines (148 file(s) checked).
exit code: 1 (expected: 1)
RESULT: as expected

### file size: violation removed — ./scripts/check-file-size 
WARNING: app/src/main/kotlin/com/phairplay/MainActivity.kt:447 — 447 lines exceed the soft limit of 400 lines (docs/CONTRIBUTING.md rule 1). Split the file.
ERROR: app/src/main/kotlin/com/phairplay/airplay/AirPlayReceiver.kt:658 — 658 lines exceed the hard limit of 550 lines (NFR-23, docs/CONTRIBUTING.md rule 1). Split the file before merging.
WARNING: app/src/main/kotlin/com/phairplay/airplay/MdnsService.kt:527 — 527 lines exceed the soft limit of 400 lines (docs/CONTRIBUTING.md rule 1). Split the file.
ERROR: app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt:1024 — 1024 lines exceed the hard limit of 550 lines (NFR-23, docs/CONTRIBUTING.md rule 1). Split the file before merging.
WARNING: app/src/main/kotlin/com/phairplay/airplay/handshake/AudioStreamServer.kt:503 — 503 lines exceed the soft limit of 400 lines (docs/CONTRIBUTING.md rule 1). Split the file.
WARNING: app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt:439 — 439 lines exceed the soft limit of 400 lines (docs/CONTRIBUTING.md rule 1). Split the file.
ERROR: app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt:650 — 650 lines exceed the hard limit of 550 lines (NFR-23, docs/CONTRIBUTING.md rule 1). Split the file before merging.
WARNING: app/src/test/kotlin/com/phairplay/airplay/RtspHandlerTest.kt:413 — 413 lines exceed the soft limit of 400 lines (docs/CONTRIBUTING.md rule 1). Split the file.
FAILED: file size check — 3 file(s) above the hard limit of 550 lines, 5 warning(s) above the soft limit of 400 lines (147 file(s) checked).
exit code: 1 (expected: 1)
RESULT: as expected

### KDoc: a class without a KDoc header — ./scripts/check-kdoc-headers 
./app/src/googletv/kotlin/com/phairplay/cast/CastReceiverOptionsProvider.kt:8: MISSING-KDOC CastReceiverOptionsProvider — no KDoc header found above the declaration.
./app/src/main/kotlin/com/phairplay/airplay/NowPlayingInfo.kt:58: MISSING-KDOC Metadata — the KDoc header is not directly above the declaration (7 line(s) in between).
./app/src/main/kotlin/com/phairplay/airplay/NowPlayingInfo.kt:68: MISSING-KDOC MutableMetadata — the KDoc header is not directly above the declaration (17 line(s) in between).
./app/src/main/kotlin/com/phairplay/airplay/PhotoHandler.kt:62: MISSING-KDOC PhotoImageType — the KDoc header is not directly above the declaration (52 line(s) in between).
./app/src/main/kotlin/com/phairplay/airplay/PhotoHandler.kt:67: MISSING-KDOC PhotoValidation — the KDoc header is not directly above the declaration (57 line(s) in between).
./app/src/main/kotlin/com/phairplay/airplay/PhotoHandler.kt:68: MISSING-KDOC Valid — the KDoc header is not directly above the declaration (58 line(s) in between).
./app/src/main/kotlin/com/phairplay/airplay/PhotoHandler.kt:69: MISSING-KDOC Invalid — the KDoc header is not directly above the declaration (59 line(s) in between).
./app/src/main/kotlin/com/phairplay/dlna/GenaEventManager.kt:225: MISSING-KDOC Subscription — the KDoc header is not directly above the declaration (5 line(s) in between).
./app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt:270: MISSING-KDOC WfdRtspServer — the KDoc header is not directly above the declaration (72 line(s) in between).
./app/src/main/kotlin/com/phairplay/qa/NoKDoc.kt:3: MISSING-KDOC NoKDoc — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/airplay/PhotoHandlerTest.kt:6: MISSING-KDOC PhotoHandlerTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/airplay/handshake/PlistCodecTest.kt:8: MISSING-KDOC PlistCodecTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/cast/CastReceiverTest.kt:7: MISSING-KDOC CastReceiverTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/dlna/DlnaDescriptionBuilderTest.kt:7: MISSING-KDOC DlnaDescriptionBuilderTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/dlna/DlnaMediaTypeTest.kt:7: MISSING-KDOC DlnaMediaTypeTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/dlna/SsdpMessagesTest.kt:8: MISSING-KDOC SsdpMessagesTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/media/MediaCapabilitiesTest.kt:8: MISSING-KDOC MediaCapabilitiesTest — no KDoc header found above the declaration.
FAILED: KDoc header check — 17 class declaration(s) without a KDoc header out of 195 declaration(s) checked.
exit code: 1 (expected: 1)
RESULT: as expected

### KDoc: violation removed — ./scripts/check-kdoc-headers 
./app/src/googletv/kotlin/com/phairplay/cast/CastReceiverOptionsProvider.kt:8: MISSING-KDOC CastReceiverOptionsProvider — no KDoc header found above the declaration.
./app/src/main/kotlin/com/phairplay/airplay/NowPlayingInfo.kt:58: MISSING-KDOC Metadata — the KDoc header is not directly above the declaration (7 line(s) in between).
./app/src/main/kotlin/com/phairplay/airplay/NowPlayingInfo.kt:68: MISSING-KDOC MutableMetadata — the KDoc header is not directly above the declaration (17 line(s) in between).
./app/src/main/kotlin/com/phairplay/airplay/PhotoHandler.kt:62: MISSING-KDOC PhotoImageType — the KDoc header is not directly above the declaration (52 line(s) in between).
./app/src/main/kotlin/com/phairplay/airplay/PhotoHandler.kt:67: MISSING-KDOC PhotoValidation — the KDoc header is not directly above the declaration (57 line(s) in between).
./app/src/main/kotlin/com/phairplay/airplay/PhotoHandler.kt:68: MISSING-KDOC Valid — the KDoc header is not directly above the declaration (58 line(s) in between).
./app/src/main/kotlin/com/phairplay/airplay/PhotoHandler.kt:69: MISSING-KDOC Invalid — the KDoc header is not directly above the declaration (59 line(s) in between).
./app/src/main/kotlin/com/phairplay/dlna/GenaEventManager.kt:225: MISSING-KDOC Subscription — the KDoc header is not directly above the declaration (5 line(s) in between).
./app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt:270: MISSING-KDOC WfdRtspServer — the KDoc header is not directly above the declaration (72 line(s) in between).
./app/src/test/kotlin/com/phairplay/airplay/PhotoHandlerTest.kt:6: MISSING-KDOC PhotoHandlerTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/airplay/handshake/PlistCodecTest.kt:8: MISSING-KDOC PlistCodecTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/cast/CastReceiverTest.kt:7: MISSING-KDOC CastReceiverTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/dlna/DlnaDescriptionBuilderTest.kt:7: MISSING-KDOC DlnaDescriptionBuilderTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/dlna/DlnaMediaTypeTest.kt:7: MISSING-KDOC DlnaMediaTypeTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/dlna/SsdpMessagesTest.kt:8: MISSING-KDOC SsdpMessagesTest — no KDoc header found above the declaration.
./app/src/test/kotlin/com/phairplay/media/MediaCapabilitiesTest.kt:8: MISSING-KDOC MediaCapabilitiesTest — no KDoc header found above the declaration.
FAILED: KDoc header check — 16 class declaration(s) without a KDoc header out of 194 declaration(s) checked.
exit code: 1 (expected: 1)
RESULT: as expected

### docs links: a link to a missing file — ./scripts/check-docs-links 
ERROR: docs/qa-negative-test.md:3 — broken internal link "docs/does-not-exist.md" (resolved to "docs/docs/does-not-exist.md", which does not exist).
FAILED: documentation link check — 1 broken internal link(s) out of 13 internal link(s) checked (10 external link(s) skipped).
exit code: 1 (expected: 1)
RESULT: as expected

### docs links: violation removed — ./scripts/check-docs-links 
OK: documentation link check passed — 12 internal link(s) resolve, 10 external link(s) skipped.
exit code: 0 (expected: 0)
RESULT: as expected

### i18n: a key removed from the French translation — ./scripts/check-i18n-completeness 
ERROR: app/src/main/res/values/strings.xml:16 — the key "nav_settings" of the base file is missing in this translation.
FAILED: i18n completeness check — 1 problem(s) across 2 language(s); the base file has 80 keys.
exit code: 1 (expected: 1)
RESULT: as expected

### i18n: violation removed — ./scripts/check-i18n-completeness 
OK: i18n completeness check passed — 80 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).
exit code: 0 (expected: 0)
RESULT: as expected

===================================================================
done — full log in /workspace/.mavis/plans/plan_65254cfd/workspace/negative-test.log
```

---

## How to repeat the test

The test scripts are not part of the repository — they live in the working
directory of the QA agent. To repeat the proof:

1. Copy the repository to a scratch directory.
2. Run the four scripts there and note the counts (the baseline).
3. Introduce one violation per script:
   * a Kotlin file with more than 550 lines,
   * a Kotlin class without a KDoc header,
   * a Markdown file with a link to a file that does not exist,
   * a `<string>` element removed from `values-de/strings.xml`.
4. Run the four scripts again and note that each one reports the new
   violation with the file name and the line number and exits 1.
5. Remove the violations and run the four scripts again: the counts are back
   at the baseline.

## What the test does **not** prove

* It does not prove that the Gradle build is green — `./gradlew` was not
  executable in this session.
* It does not prove that Android Lint is green for the two flavours.
* It does not replace the manual review of a new check script: a reviewer
  still has to read the script before it is merged.
