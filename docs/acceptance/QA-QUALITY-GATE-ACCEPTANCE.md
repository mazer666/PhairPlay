# QA Acceptance Record — Quality Gate (structure checks and CI)

- **Milestone:** M6–M12, round 2 — "Quality gate: check scripts, CI workflow, language completeness, device test plan"
- **Scope:** `scripts/`, `.github/workflows/`, `docs/guides/DEVICE_TEST_PLAN.md`, `app/src/main/res/values-de/`, `app/src/main/res/values-fr/`
- **Tester:** QA & Test Engineer
- **Date:** 2026-10-24
- **Build/commit:** the working directory `feature/quality-m6-m12` (commit hash **not measured** — `git` was not executable in this session, see section 8)
- **Requirement IDs:** NFR-23 (no source file over 400 lines), NFR-25 (all classes have KDoc headers), `docs/CONTRIBUTING.md` rule 1 and rule 2, AC-8.5 (CI pipeline is green)

---

## 1. Functional

### 1.1 The four check scripts

| Scenario | Expected | Result | Evidence |
|---|---|---|---|
| `scripts/check-file-size.sh` finds a Kotlin file with 597 lines | exit code 1, message with file name and line count | **PASS** | `./scripts/check-file-size.sh` → `ERROR: app/src/main/kotlin/com/phairplay/demo/TooLong.kt:597 — 597 lines exceed the hard limit of 550 lines` — see `docs/audit/qa-quality-gate-negative-test.md`, section "file size" |
| `scripts/check-file-size.sh` after the violation is removed | exit code 0 on a clean tree | **PASS** | same log: `OK: file size check passed — 1 file(s) checked, none above the hard limit of 550 lines` |
| `scripts/check-kdoc-headers.sh` finds a class without a KDoc header | exit code 1, message with file name and line number | **PASS** | `ERROR: app/src/main/kotlin/com/phairplay/demo/NoKDoc.kt:3: MISSING-KDOC NoKDoc — no KDoc header found above the declaration.` |
| `scripts/check-kdoc-headers.sh` after the violation is removed | exit code 0 on a clean tree | **PASS** | `OK: KDoc header check passed — 1 class declaration(s) checked, all of them have a KDoc header.` |
| `scripts/check-docs-links.sh` finds a link to a missing file | exit code 1, message with file name and line number | **PASS** | `ERROR: docs/broken.md:3 — broken internal link "docs/does-not-exist.md" (resolved to "docs/docs/does-not-exist.md", which does not exist).` |
| `scripts/check-docs-links.sh` after the violation is removed | exit code 0 on a clean tree | **PASS** | `OK: documentation link check passed — 1 internal link(s) resolve, 0 external link(s) skipped.` |
| `scripts/check-i18n-completeness.sh` finds a key missing in `values-de` | exit code 1, message with file name and line number | **PASS** | `ERROR: app/src/main/res/values/strings.xml:4 — the key "home_title" of the base file is missing in this translation.` |
| `scripts/check-i18n-completeness.sh` finds a placeholder that does not match | exit code 1, message with file name and line number | **PASS** | `ERROR: app/src/main/res/values-fr/strings.xml:4 — the key "home_title" has the placeholders "1$s|" but the base file expects "".` |
| `scripts/check-i18n-completeness.sh` after the violation is removed | exit code 0 on a clean tree | **PASS** | `OK: i18n completeness check passed — 2 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).` |
| Every script supports `--help` | usage text, exit code 0 | **PASS** | `./scripts/check-file-size.sh --help` → exit 0; the same for the other three |
| Every script rejects an unknown argument | exit code 2, error message | **PASS** | `./scripts/check-file-size.sh --bogus` → `ERROR: unknown argument '--bogus'` + `exit=2` |

**Negative test:** 14 of 14 cases behaved as expected
(`grep -c "RESULT: as expected"` = 14, `grep -c UNEXPECTED` = 0 in
`docs/audit/qa-quality-gate-negative-test.md`).

### 1.2 The state of the repository today (measured, not estimated)

| Script | Exit code | Measured result on the working directory |
|---|---:|---|
| `check-file-size.sh` | **1** | 3 files above the hard limit of 550 lines, 5 warnings above the soft limit of 400 lines, 147 files checked |
| `check-kdoc-headers.sh` | **1** | 16 class declarations without a KDoc header out of 194 declarations checked |
| `check-docs-links.sh` | **0** | 12 internal links resolve, 10 external links skipped (before this cycle added `docs/guides/DEVICE_TEST_PLAN.md`: 17 internal links) |
| `check-i18n-completeness.sh` | **0** | 80 keys in the base file, complete in 2 languages (`de`, `fr`) |

### 1.3 The CI job

| Scenario | Expected | Result | Evidence |
|---|---|---|---|
| The `quality-gate` job exists in `.github/workflows/ci.yml` | the job runs the four scripts | **PASS** | `.github/workflows/ci.yml` lines 13–51, job `quality-gate` |
| The job fails the build when a script fails | the step exits 1 | **PASS** | the step was executed locally with `bash -e` (the same shell GitHub Actions uses): `STEP EXIT CODE: 1`, with `FAIL: scripts/check-file-size.sh` and `FAIL: scripts/check-kdoc-headers.sh` in the log |
| The job runs all four checks even if the first one fails | one push shows every problem | **PASS** | the same run shows `PASS: scripts/check-docs-links.sh` and `PASS: scripts/check-i18n-completeness.sh` after the two failures |
| No `continue-on-error` anywhere in the workflow | the build really breaks | **PASS** | `grep -n "continue-on-error" .github/workflows/*.yml` → no match |

---

## 2. Technical

- Architecture boundaries respected: **yes** — the scripts are POSIX-shell
  tools in `scripts/`, they read the repository and change nothing. No Kotlin
  source was modified.
- Central config respected: **yes** — the limits are read from
  `docs/CONTRIBUTING.md` rule 1 (400/550) and the requirement NFR-25; the
  scripts have no hard-coded magic numbers other than the documented defaults,
  and `--soft-limit` / `--hard-limit` allow an override.
- Tests green: **not measured** — see section 8.
- Migration/versioning checked: **not applicable** — no resource, no schema
  and no persisted data changed.

### 2.1 How each script decides its exit code

| Script | Exit 0 | Exit 1 | Exit 2 |
|---|---|---|---|
| `check-file-size.sh` | no file above the hard limit (soft-limit violations are warnings) | at least one file above 550 lines, or above 400 lines with `--strict` | unknown argument, missing value, impossible limit combination |
| `check-kdoc-headers.sh` | every class-like declaration has a KDoc header | at least one declaration has no KDoc header | unknown argument, missing value |
| `check-docs-links.sh` | every internal link resolves | at least one internal link is broken | unknown argument, missing value |
| `check-i18n-completeness.sh` | every locale carries every key of the base file, with matching placeholders | a key is missing, a key is stale, a placeholder differs, or a required language is missing | unknown argument, missing value, base file not found |

### 2.2 The `lint.yml` question

**Result: `lint.yml` already ran for both flavours — no flavour gap existed.**
The matrix of the `android-lint` job contains two entries, `googletv` with
`:app:lintGoogletvDebug` and `firetv` with `:app:lintFiretvDebug`, and
`fail-fast: false` makes sure both results are reported. The lint
configuration in `app/build.gradle.kts` sets `abortOnError = true` and
`warningsAsErrors = true`, so a finding really fails the task.

Three holes in `lint.yml` were closed anyway:

| Hole | Before | After |
|---|---|---|
| A lint run that produced **no** report still counted as green | `if-no-files-found` was not set (default `warn`) | `if-no-files-found: error` on the report upload **and** an extra step "Verify a lint report was produced" that fails when no `lint-results-*.xml` exists |
| The workflow had no explicit permissions | none | `permissions: contents: read` |
| A hanging job blocked the pipeline | no timeout | `timeout-minutes: 30` |

The same `if-no-files-found: error` was added to the APK upload of the
`android-build` job in `ci.yml` — an APK that was not built must not look like
a successful build.

---

## 3. Performance

| Metric | Budget | Measured | Result |
|---|---:|---:|---|
| Runtime of the four checks together | — | **not measured** (no timing run in this session) | — |
| Number of files scanned by the size check | — | 147 Kotlin files | — |

The checks are pure text scans; they run in the order of a second on a laptop
and are given a `timeout-minutes: 10` budget in CI. The runtime was **not
measured** — the shell of this session cannot time a run reliably.

---

## 4. Usability

- task step count: **one** — `./scripts/check-file-size.sh` (or the CI job).
- discoverability notes: every script has a `--help` that prints the limits,
  the exit codes and examples; the CI job names the four checks in its log.
- mobile/native behaviour: not applicable — the scripts run on a build server.
- visual/polish issues: the error messages name the file, the line number and
  the limit that was broken, so a contributor can fix the problem without
  searching.

---

## 5. Accessibility

- keyboard: not applicable.
- VoiceOver/TalkBack: not applicable.
- scaling/high contrast: not applicable.
- The user-visible strings of the app were **not** changed by this milestone,
  except that the German and French translations were completed (see
  `QA-I18N-ACCEPTANCE.md`).

---

## 6. Security/privacy

- new data stored: **none** — the scripts read the repository and write
  nothing.
- network access: **none** — no script opens a socket.
- negative tests: 14 negative cases, see `docs/audit/qa-quality-gate-negative-test.md`.
- threat notes: the scripts are part of the trusted build chain. They must
  never be weakened to make the pipeline green; the known open violations are
  listed in section 8 instead.

---

## 7. Interoperability/data integrity

- reopen in the reference project: not applicable.
- external viewers/editors: not applicable.
- roundtrip: the i18n check compares the base file with every translation in
  both directions (missing **and** stale keys) and compares the format
  placeholders — verified with a deliberately broken translation.
- recovery/save integrity: not applicable.

---

## 8. Known limitations

1. **The gate is red on the current working directory, on purpose.**
   `check-file-size.sh` reports three files above the hard limit of 550 lines
   (`airplay/AirPlayReceiver.kt` 658, `airplay/RtspHandler.kt` 1024,
   `service/PhairPlayService.kt` 650) and `check-kdoc-headers.sh` reports 16
   class declarations without a KDoc header. Both are real violations of
   `docs/CONTRIBUTING.md`. Three of the five oversized files are exactly the
   files that the parallel refactoring splits in this cycle, so the gate is
   expected to turn green when that refactoring is merged. **The gate was not
   weakened to make the pipeline green.**
2. **The complete list of open violations** (so the owner can prioritise):
   - file size, hard limit: `app/src/main/kotlin/com/phairplay/airplay/AirPlayReceiver.kt` (658),
     `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt` (1024),
     `app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt` (650)
   - file size, soft limit (warnings only): `MainActivity.kt` (447),
     `airplay/MdnsService.kt` (527), `airplay/handshake/AudioStreamServer.kt` (503),
     `miracast/MiracastReceiver.kt` (439),
     `app/src/test/kotlin/com/phairplay/airplay/RtspHandlerTest.kt` (413)
   - KDoc header: `googletv/.../CastReceiverOptionsProvider.kt:8`,
     `airplay/NowPlayingInfo.kt:58` and `:68`,
     `airplay/PhotoHandler.kt:62`, `:67`, `:68`, `:69`,
     `dlna/GenaEventManager.kt:225`, `miracast/MiracastReceiver.kt:270`,
     and the seven test classes `airplay/PhotoHandlerTest.kt:6`,
     `airplay/handshake/PlistCodecTest.kt:8`, `cast/CastReceiverTest.kt:7`,
     `dlna/DlnaDescriptionBuilderTest.kt:7`, `dlna/DlnaMediaTypeTest.kt:7`,
     `dlna/SsdpMessagesTest.kt:8`, `media/MediaCapabilitiesTest.kt:8`
3. **The test run of the JVM suite was not executed in this session.** The
   shell of this session cannot run `./gradlew`. The figures handed over by
   the team lead are:
   - baseline before this cycle: **277 tests, 0 failures, 0 skipped**
   - after the DLNA/security/AirPlay wave: **427 tests, 22 failures** — the
     failures were still being worked on when this task started. **This
     milestone does not claim 427/0.**
   - *Testlauf nicht ausgeführt — muss beim Teamleiter verifiziert werden:*
     `./gradlew :test-runner:test`
4. **The Android Lint job was not executed in this session** (no Gradle, no
   Android SDK). The workflow change is verified by reading the YAML and by
   `bash -n` on the shell block of every `run:` step.
   - *Testlauf nicht ausgeführt — muss beim Teamleiter verifiziert werden:*
     `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug`
5. **`docs/spec/REQUIREMENTS.md` line 180 still says "DLNA / UPnP — Out of
   scope — Not planned"**, although round 1 delivered a complete MediaRenderer
   and `docs/decisions/ADR-004-dlna-mediarenderer-roadmap.md` exists. The
   spec belongs to another track and was not changed here.
6. **`docs/spec/ACCEPTANCE_CRITERIA.md` has no acceptance criterion for the
   DLNA MediaRenderer, Miracast, Google Cast, the pairing PIN, the German and
   French user interface, "start on boot" and the notification.** The device
   test plan therefore uses the identifiers DT-01 … DT-15 and lists the gap
   explicitly.

---

## 9. Decision

**accepted with documented limitation**

The four scripts exist, each one turns red on a real violation (proved with 14
negative cases) and green again when the violation is removed, and the CI job
runs them without `continue-on-error`. The gate is red on the current tree
because the tree violates two project rules; the exact list of violations is
in section 8 and belongs to the owner.

---

## 10. Changed files

| File | Purpose |
|---|---|
| `scripts/check-file-size.sh` | new — enforces NFR-23 / CONTRIBUTING rule 1 (400 soft / 550 hard) |
| `scripts/check-kdoc-headers.sh` | new — enforces NFR-25 / CONTRIBUTING rule 2 |
| `scripts/check-docs-links.sh` | new — every internal Markdown link must resolve |
| `scripts/check-i18n-completeness.sh` | new — every key of the base file must exist in every locale, with matching placeholders |
| `.github/workflows/ci.yml` | new job `quality-gate`; `permissions`, `timeout-minutes`, `if-no-files-found: error` for the APK |
| `.github/workflows/lint.yml` | `permissions`, `timeout-minutes`, report verification step, `if-no-files-found: error` |
| `docs/guides/DEVICE_TEST_PLAN.md` | new — see `QA-DEVICE-TEST-ACCEPTANCE.md` |
| `docs/acceptance/QA-QUALITY-GATE-ACCEPTANCE.md` | this record |
| `docs/acceptance/QA-I18N-ACCEPTANCE.md` | the language record |
| `docs/acceptance/QA-DEVICE-TEST-ACCEPTANCE.md` | the device test plan record |
| `docs/audit/qa-quality-gate-negative-test.md` | the raw output of the negative test |
| `app/src/main/res/values-de/strings.xml` | completed to 80/80 keys |
| `app/src/main/res/values-fr/strings.xml` | completed to 80/80 keys |
| `app/src/main/res/values/strings.xml` | header comment only — the old comment allowed omitting keys, which contradicts the new gate |
