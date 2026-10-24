# Acceptance — M10: Internationalization

**Feature / milestone:** M10 — i18n polish (English, German, French)
**Requirement IDs:** FR-03, FR-65 … FR-68, NFR-13, AC-M10.1 … AC-M10.6
**Build / commit:** `51933eb` (branch `feature/quality-m6-m12`, merged into
`feature/project-status`)
**Flavour(s) tested:** `googletv` and `firetv` — both compile
**Device(s) tested:** **none** — no television was available
**Sender(s) tested:** not applicable
**Tester / roles:** Project Manager (audit), with the measured output of the
i18n completeness script and the code evidence
**Date:** 2026-10-24

---

## 1. Functional

Does the feature do what the requirement says?

| # | Scenario | Expected | Result | Evidence |
|---|---|---|---|---|
| 1 | No locale file defines a key that the base locale does not define | 0 stale keys | **PASS (measured)** | `bash scripts/check-i18n-completeness.sh` → exit 0, `OK: i18n completeness check passed — 80 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).` The script checks three things: every base key exists in the translation, no translation key is unknown to the base, and the format placeholders match. |
| 2 | Every shipped locale translates the protocol state texts | every locale file defines all `protocol_state_*` keys | **PASS (measured)** | The same script run covers all 80 keys of every locale; the `protocol_state_*` and `protocol_detail_*` keys are part of that set. They exist in `values/`, `values-de/` and `values-fr/`. |
| 3 | The protocol names are identical in every locale | the four `protocol_*` values are equal in all locale files | **PASS (measured)** | `protocol_airplay`, `protocol_miracast`, `protocol_cast`, `protocol_dlna` are part of the 80 keys the script compares. Measured with the same script: 80 keys in the base file, complete in 2 languages, no stale key, no placeholder difference. |
| 4 | No user-visible text is hardcoded in Kotlin or in a layout | 0 lint findings, 0 grep matches | **PASS (code + lint)** | `grep -rn "setText(\"" app/src/main` → 0 matches. The lint analysis of the `googletv` variant completed and reported exactly one finding — the `UnusedAttribute` error in the shared manifest, not a `HardcodedText` finding; the `firetv` variant was not analysed. The resource references in the UI code are `R.string.*` (`HomeFragment.kt` lines 124–127 use `R.string.protocol_airplay` and so on). |
| 5 | With the device language set to German, the app shows German texts | Home screen, Settings, notification and the protocol cards are in German | **NOT MEASURED** | The resource files are complete (scenarios 1–3), but the rendering, the line breaks and the button widths can only be judged on a device. Device test plan case DT-12. |
| 6 | With the device language set to French, the app shows the French texts | no French key falls back to English | **NOT MEASURED** | Same as scenario 5. Device test plan case DT-12. |
| 7 | French strings exist at all | the `values-fr/` folder is complete | **PASS (measured)** | `app/src/main/res/values-fr/strings.xml` exists with 80 keys, the same set as the base file. The folder did not exist before this cycle — the QA track added it. |
| 8 | The English base file is the single source | all locales carry exactly the base key set | **PASS (measured)** | The script compares the key sets in both directions (missing *and* stale) and reports `complete in 2 language(s)`. |

**Edge cases exercised:**

| Case | Expected | Result |
|---|---|---|
| A key is missing in `values-de/` | the script fails with the key name | **PASS (negative test)** — `docs/audit/qa-quality-gate-negative-test.md` documents the green → red → green run for the i18n script; the acceptance record `docs/acceptance/QA-I18N-ACCEPTANCE.md` §1 lists the case |
| A key is stale in `values-fr/` | the script fails | **PASS (negative test)** — same source |
| A format placeholder differs between base and translation | the script fails | **PASS (negative test)** — same source. **Known limitation:** the comparison is a *sequence* comparison, not a *set* comparison, so a reordering of `%1$s` / `%2$s` is reported as a difference even though Android accepts it |
| A non-indexed placeholder (`%s` instead of `%1$s`) changes type | should be detected | **NOT DETECTED** — the script only recognises indexed placeholders (`%N$typ`). The base file currently uses only `%1$s` (10 occurrences), so there is no live impact; the gap is documented in the QA acceptance record |
| `<plurals>` or `<string-array>` are added to a locale | should be compared | **NOT COMPARED** — the script only handles `<string>` elements. The base file currently contains only `<string>` elements |
| The device language is a third one (e.g. Italian) | the app falls back to English without crashing | **not measured** — no device |

---

## 2. Technical

- Architecture boundaries respected (the protocol stacks stay independent): **yes** —
  the internationalisation is a resource-layer concern; no protocol code was
  touched.
- The file-size rule respected (≤ 400 lines soft, ≤ 550 hard): **yes** — the
  resource files are 131 lines (base), 132 lines (de), 132 lines (fr); the script
  is 274 lines.
- Every new class has a KDoc header: **not applicable** — no class was added.
- Every new public method has a test: **no** — and this is a known gap: the
  completeness check lives **only** in `scripts/check-i18n-completeness.sh`, which
  is **not** part of `./gradlew :test-runner:test`. The script runs in the CI job
  `quality-gate`, so it is green in CI, but a developer who runs only the Gradle
  suite sees nothing. Android's own `MissingTranslation` lint is switched off in
  `app/build.gradle.kts`.
- Tests green — `./gradlew :test-runner:test`: **yes** — measured on `51933eb`:
  `BUILD SUCCESSFUL in 3m 27s`, 457 tests, 0 failures, 0 errors, 0 skipped,
  54 test classes. **Caveat:** no test in that suite covers the resource
  completeness.
- Lint clean — `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug`: **no — the lint was run and it failed.** Measured on `51933eb`: the `googletv` variant ends with `:app:lintGoogletvDebug FAILED`, `1 errors, 0 warnings`; the single finding is `app/src/main/AndroidManifest.xml:58: Error: Attribute usesPermissionFlags is only used in API level 31 and higher (current min is 29) [UnusedAttribute]`. The `firetv` variant did not run — the build stopped at the first failure. The finding sits in the shared manifest, so it is not specific to this milestone.
- Both flavours build — `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug`:
  **yes** — see section 2.1.
- No new dependency / no new permission: **yes** — resources only.

### 2.1 Measured output of the i18n check

```
$ bash scripts/check-i18n-completeness.sh ; echo "exit=$?"
OK: i18n completeness check passed — 80 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).
exit=0
```

### 2.2 Build log (measured)

```
$ source /workspace/toolchain/env.sh
$ ./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline --console=plain
...
BUILD SUCCESSFUL
```

---

## 3. Performance

| Metric | Budget | Measured | Result |
|---|---:|---:|---|
| Number of translated keys | 80 per locale | **80** in `values/`, **80** in `values-de/`, **80** in `values-fr/` | — |
| Runtime of the completeness check | — | **not measured** — no timing run | — |
| APK size increase caused by the French locale | — | **not measured** — only the debug APKs were built and not weighed | — |

**Reference device / input:** none.

**Measurement commands used:**

```bash
grep -c '<string name=' app/src/main/res/values/strings.xml \
  app/src/main/res/values-de/strings.xml \
  app/src/main/res/values-fr/strings.xml
```

> The key counts above are measured with `grep -c '<string name='` on the three
> files; the completeness itself is proven by the script, not by the count.

---

## 4. Usability

- Step count from "I want to mirror" to "it works": **not applicable** — the
  milestone has no user flow of its own.
- Discoverability: the language follows the device setting; there is no in-app
  language switcher (that was not in scope).
- The screen tells the truth: **not applicable**.
- The error state is understandable: the protocol state texts are translated in
  all three languages (`protocol_state_disabled`, `protocol_state_advertising`,
  `protocol_state_connected`, `protocol_state_error` and the four
  `protocol_detail_*` texts) — proven by the completeness check.
- Visual / polish issues seen: **not assessed** — no device. Line breaks, button
  widths and the 1440p label can only be judged on the target screen. The QA
  track flags the same point: "the texts were never seen on a device".
- Strings: **yes** — every user-visible text is in a resource file; there is no
  hardcoded string in the Kotlin sources (0 grep matches for `setText("`).
  The strings that name a protocol are identical in every locale, as FR-68
  requires.

---

## 5. Accessibility

- D-pad navigation: **not measured** — no device.
- Focus target size ≥ 48 dp (NFR-07): **not measured**.
- Text size ≥ 18 sp for body, ≥ 32 sp for titles (NFR-08): **not measured**.
- Contrast readable from 3 metres (NFR-09): **not measured**.
- TalkBack / screen reader: **not measured**. Note that TalkBack reads the
  resource values, so the translations are directly part of the spoken output;
  a wrong translation is an accessibility defect, not only a cosmetic one.
- What is **not** covered yet: the entire verification of the German and French
  texts on a screen, including the text scaling of the longest translations.

---

## 6. Security and privacy

- New network input: **no**.
- New stored data: **no**.
- New outbound connection: **no**.
- New permission: **no**.
- Negative tests added: **yes, for the checking mechanism** — the negative test
  of `check-i18n-completeness.sh` (missing key, stale key, differing placeholder)
  is documented in `docs/audit/qa-quality-gate-negative-test.md` with a
  green → red → green run.
- The log reduction still applies to every new log statement (SEC-07)? **not
  applicable** — no log statement was added.
- Known findings from the audit that still apply to this feature: **none**.
- Privacy impact: does anything new leave the local network? **It must not, and
  nothing new leaves it.** The resource files contain no personal data; no
  locale-dependent value is written to the log or sent anywhere. Note for the
  owner: a locale-dependent log message could become a fingerprint — the current
  log statements carry no locale-dependent content.

---

## 7. Interoperability and data integrity

| Sender | Version | Protocol | Result |
|---|---|---|---|
| macOS | — | AirPlay 2 | not applicable to M10 |
| iPhone / iPad | — | AirPlay 2 | not applicable to M10 |
| Windows | — | Miracast (WFD) | not applicable to M10 |
| Windows | — | DLNA / UPnP | not applicable to M10 |
| VLC | — | DLNA / UPnP | not applicable to M10 |
| Chrome | — | Google Cast | not applicable to M10 |

- Round trip: not applicable.
- Does an unsupported format fail cleanly instead of crashing? **yes (code)** —
  a missing key falls back to the base locale; there is no crash path, because
  Android resolves a missing resource itself.
- Recovery: not applicable.
- The one interoperability property of this milestone is that the **protocol
  names are identical in every locale** (FR-68) — a sender or a user guide that
  says "DLNA" must see the same word everywhere. **PASS (measured)**.

---

## 8. Known limitations

| Limitation | Reason | Where it is documented |
|---|---|---|
| The completeness check is not part of the Gradle test run | The check is a shell script; the JVM suite has no resource comparison | `docs/acceptance/QA-I18N-ACCEPTANCE.md`; risk R-09 in `docs/project/RISKS.md` |
| The placeholder comparison is a sequence comparison, not a set comparison | Implementation of `check-i18n-completeness.sh` | `docs/acceptance/QA-I18N-ACCEPTANCE.md` §2 |
| Non-indexed placeholders and `<plurals>` / `<string-array>` are not compared | Implementation of `check-i18n-completeness.sh`; the base file currently contains only `<string>` with `%1$s` | `docs/acceptance/QA-I18N-ACCEPTANCE.md` §2 |
| The translations were never read by a native speaker | No native reviewer was available | `docs/acceptance/QA-I18N-ACCEPTANCE.md` §2 |
| The texts were never seen on a device | No device was available | Device test plan DT-12 |
| No RTL baseline was implemented | The milestone list mentions "RTL support baseline (for future AR/HE)" as an open task | `docs/spec/PROJECT_PLAN.md`, phase 10 |

---

## 9. Decision

**accepted with documented limitation**

**Decided by:** Project Manager, on behalf of the owner
**Date:** 2026-10-24

The measurable part of M10 is **done and proven**: three locales, 80 keys each,
complete in both directions, no stale key, matching placeholders, checked by a
script that is part of CI and whose own negative behaviour is documented.

Two things are *not* done and are honestly named in the plan rather than
claimed: the **RTL baseline** and the **locale-aware date/time/number
formatting** are still open tasks in phase 10 of the project plan, and they were
not touched in this cycle. The **rendering** of the translations on a screen is
unproven.

**Follow-up (open, because …):**

1. **Run device test plan case DT-12** on a Google TV and a Fire TV: set the
   device language to German and to French, walk through Home, Settings and the
   notification. Reason: no device was available.
2. **Have a native speaker read the German and the French settings screen once.**
   Reason: the completeness check proves the keys exist, not that the sentences
   are natural.
3. **Add a JVM test that compares `values/`, `values-de/` and `values-fr/`,**
   and re-enable Android's `MissingTranslation` lint afterwards. Reason: today
   the only guarantee is a shell script outside the Gradle run.
4. **Decide on the RTL baseline and the locale-aware formatting** — they are
   listed as M10 tasks but were not delivered. Reason: they only matter for
   future Arabic / Hebrew locales; the decision belongs to the owner.

---

## 10. Release gate

| # | Gate | Result |
|---|---|---|
| 1 | `./gradlew :test-runner:test` is green | **yes** — measured on `51933eb`: 457 tests, 0 failures, 0 errors, 0 skipped. Note: this does **not** cover the resource completeness. |
| 2 | Lint and both debug APKs are green | **partly** — both debug APKs build (measured); the lint **fails** with 1 error (`UnusedAttribute`, `app/src/main/AndroidManifest.xml:58`) |
| 3 | **No known S4 finding** | **yes** — the M10 surface has no S4 finding |
| 4 | Every open S3 has a written owner decision | **yes** — the two script gaps are written down as P3 items in the QA acceptance record |
| 5 | The device scenarios ran on both flavours | **no** — DT-12 has not been executed |
| 6 | The file-size rule holds for the touched files | **yes** — the three resource files are 131/132/132 lines |
| 7 | The documentation is up to date (same change) | **yes** — `docs/acceptance/QA-I18N-ACCEPTANCE.md` documents the key table and the script behaviour |
| 8 | The limitations in `README.md` are true of this build | **yes** — the README does not claim a French or German UI that has been seen on a device |
