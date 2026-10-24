# Acceptance — M11: Release (v1.0.0 readiness)

**Feature / milestone:** M11 — Release engineering and the release artefacts
**Requirement IDs:** FR-44 … FR-70 (release-relevant), NFR-19, NFR-22, NFR-26 … NFR-31,
AC-M11.1 … AC-M11.8 (plus the legacy round AR-8 / `AC-8.x`)
**Build / commit:** `51933eb` (branch `feature/quality-m6-m12`, merged into
`feature/project-status`); the released artefact is `v1.0.0-beta.3`
(`app/build.gradle.kts`: `versionCode = 3`, `versionName = "1.0.0-beta.3"`)
**Flavour(s) tested:** `googletv` and `firetv` — both debug APKs built
**Device(s) tested:** **none** — no television was available
**Sender(s) tested:** **none**
**Tester / roles:** Project Manager (audit), with the measured build and test output
**Date:** 2026-10-24

---

## 1. Functional

Does the feature do what the requirement says?

| # | Scenario | Expected | Result | Evidence |
|---|---|---|---|---|
| 1 | All unit tests of both flavours pass | exit code 0, 0 failures | **PARTLY** | `./gradlew :test-runner:test` → **BUILD SUCCESSFUL in 3m 27s**, 457 tests, 0 failures, 0 errors, 0 skipped, 54 test classes. **Caveat 1:** 38 `@Test` methods in 6 files are excluded from the runner (`test-runner/build.gradle.kts`, `exclude` block): `MainActivityTest` (9), `MdnsServiceTest` (12), `VideoSubmissionTest` (5), `ServiceControllerTest` (4), `NetworkUtilsTest` (5), `ui/VideoAspectFitTest` (3). **Caveat 2:** the runner compiles the `main` source set **and the `firetv` flavour** (`test-runner/build.gradle.kts`); the `googletv` flavour source set is **not** compiled, so the Google TV variant of the Cast receiver is not covered by the suite. It is one suite, not two separate flavour suites. |
| 2 | Lint is clean for both flavours | exit code 0, 0 findings | **NO — 1 finding, the build is red** | Measured: `:app:lintGoogletvDebug FAILED`, `1 errors, 0 warnings`, the finding is `app/src/main/AndroidManifest.xml:58: Error: Attribute usesPermissionFlags is only used in API level 31 and higher (current min is 29) [UnusedAttribute]` (`app/build/reports/lint-results-googletvDebug.txt`). The `firetv` variant never ran, because the build stopped at the first failure. The configuration is strict: `abortOnError = true` and `warningsAsErrors = true` in `app/build.gradle.kts`, and the workflow has an extra step that fails when no lint report was produced (`lint.yml` lines 68–79). |
| 3 | Signed release APKs are produced for both flavours | both APKs exist and `apksigner verify` reports "Verified" | **NOT MEASURED** | Only the **debug** APKs were built in this audit. The release path needs a keystore that lives outside the repository (`~/.config/phairplay/release.jks`, see `scripts/release.sh`). The previous release `v1.0.0-beta.2` was signed and published (2026-10-09) — that is a historical fact, not a measurement of this commit. |
| 4 | `CHANGELOG.md` contains the entry of the released version, including the DLNA section and the documented limitations | the entry names the version and the date and lists DLNA, Miracast and Cast with their real state | **PARTLY** | `CHANGELOG.md` has a `[1.0.0-beta.3] - 2026-10-24` section with the DLNA block (SSDP, description server, SOAP dispatcher, GENA, MediaCapabilities, the Settings toggle, the protocol isolation) and the statement "Media playback (phase D3) is not part of this release." **That last sentence contradicts the code** — `DlnaReceiver.onPlay` plays through `AirPlayVideoPlayer` and the photo pipeline exists. Corrected in this cycle: see the CHANGELOG entry of this commit. |
| 5 | `README.md`, `SECURITY.md` and `PRIVACY.md` describe exactly the protocols that are shipped | no document claims a protocol that is not implemented and no document denies an implemented one | **NO — three defects** | `README.md` line 25: "**UPnP/DLNA MediaRenderer** is planned as an upcoming option" — the package `com.phairplay.dlna` exists with 28 production files and 157 passing tests. `README.md` line 48 repeats the same claim. `README.md` line 19 also says "Current Status — v1.0.0-beta.2" while `app/build.gradle.kts` says `1.0.0-beta.3`. Correction proposed to the docs track (it owns the README). |
| 6 | Every finding of the security audit is either fixed or documented as open with a reason | 0 findings open **and** unexplained | **YES** | `docs/audit/2026-10-24-security-audit.md` §7 lists the 7 accepted residual risks with their reasons; the 14 findings each carry a location, an attack path and a concrete fix. **But:** SEC-01, SEC-02, SEC-03 (the three High findings) are still **open in the code**, and SEC-03 (the unbounded FU-A buffer) sits directly in the AirPlay path. They are documented, so this criterion is met in the "documented as open" sense — and the quality model turns them into a release blocker. See risk R-03 and the release checklist. |
| 7 | The version name in the code equals the released tag | the two values are equal | **PARTLY** | `app/build.gradle.kts` line 32: `versionName = "1.0.0-beta.3"`; the tag `v1.0.0-beta.3` exists in the changelog. The mismatch is with `README.md`, which still says beta.2 (see scenario 5). |
| 8 | The DLNA pull semantics are documented for the user | the README states that only `http`/`https` URLs can be played | **NO** | `grep -n "file:///" README.md` → no match. The statement exists in `docs/guides/CASTING_TO_A_TV.md` under "Known limitations" and in `docs/spec/REQUIREMENTS.md` §3, but not in the README. AC-M11.8 names the README explicitly. Correction proposed to the docs track. |
| 9 | The four repository structure checks are green | exit code 0 for each script | **PARTLY — 3 of 4 green** | Measured on `51933eb`: `check-file-size.sh` → exit 0 (`OK: file size check passed — 177 file(s) checked, none above the hard limit of 550 lines, 3 warning(s) above the soft limit of 400 lines`); `check-docs-links.sh` → exit 0 (`191 internal link(s) resolve, 15 external link(s) skipped` — the count grows with every new document, it was 167 before the acceptance records were added); `check-i18n-completeness.sh` → exit 0; `check-kdoc-headers.sh` → **exit 1**, `16 class declaration(s) without a KDoc header out of 222 declaration(s) checked`. The CI job `quality-gate` therefore makes the build **red**. |
| 10 | The CI workflow runs the JVM suite, the lint and both APKs | three jobs with the right commands | **PASS (code)** | `.github/workflows/ci.yml`: job `quality-gate` (lines 13–51), job `jvm-tests` (lines 53–83, `./gradlew :test-runner:test --stacktrace`, JDK 17, `timeout-minutes: 30`), job `android-build` (lines 84–142, matrix `googletv` / `firetv`, lint **and** debug APK, `if-no-files-found: error` on the APK upload). `lint.yml` runs the Android lint for both flavours with `fail-fast: false`. |
| 11 | The release script produces both signed APKs and publishes them | a reproducible, credential-free-in-repo release path | **PASS (code)** | `scripts/release.sh` builds both signed APKs, creates the tag and publishes the GitHub release through `gh`; the keystore and the credentials live in `~/.config/phairplay/`, never in the repository. `--setup` creates them. |

**Edge cases exercised:**

| Case | Expected | Result |
|---|---|---|
| A structure check finds a violation | the CI step exits 1 and the job is red | **PASS (measured)** — the step was executed with the workflow's own shell logic in the QA track: `STEP EXIT CODE: 1`, `FAIL: scripts/check-file-size.sh`, `FAIL: scripts/check-kdoc-headers.sh`; no `continue-on-error` anywhere in `.github/workflows/` |
| The first check fails | the other three still run | **PASS (measured)** — the same run shows `PASS: scripts/check-docs-links.sh` and `PASS: scripts/check-i18n-completeness.sh` after the failures |
| An APK was not built | the upload must not look like a success | **PASS (code)** — `if-no-files-found: error` on the APK upload |
| A lint run produced no report | the job must not look green | **PASS (code)** — `lint.yml` has an extra step "Verify a lint report was produced" |
| The release keystore is missing | the script must refuse, not publish an unsigned build | **PASS (code)** — `scripts/release.sh` requires the credentials file and aborts with an error |
| The build is run without a Cast application ID | the build still succeeds, the runtime reports `ERROR` | **PASS (code + unit test)** — `app/build.gradle.kts` line 19; `CastReceiverTest > blank Cast app ID is not configured` |
| The JVM runner is executed on a machine without the Android SDK | the runner still works | **PASS** — the `test-runner` module resolves its dependencies from Maven Central only and uses the Robolectric `android-all` JAR, so it needs no Android SDK |

---

## 2. Technical

- Architecture boundaries respected (the protocol stacks stay independent): **yes** —
  the release tooling touches nothing in the protocol layers.
- The file-size rule respected (≤ 400 lines soft, ≤ 550 hard): **partly** — no
  file is above the hard limit; three are above the soft limit of 400
  (`MainActivity.kt` 447, `MiracastReceiver.kt` 439, `airplay/RtspHandlerTest.kt`
  413). The script passes, the written rule does not. Risk R-08.
- Every new class has a KDoc header: **no** — 16 declarations without a KDoc
  header (8 of them in test files). The CI gate is red because of it. Risk R-07.
- Every new public method has a test: **not for the CI and release tooling** —
  the scripts are covered by the negative-test protocol in
  `docs/audit/qa-quality-gate-negative-test.md` (22 cases), not by the JVM suite.
- Tests green — `./gradlew :test-runner:test`: **yes** — 457 tests, 0 failures,
  0 errors, 0 skipped.
- Lint clean — `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug`: **no — the lint was run and it failed.** Measured on `51933eb`: the `googletv` variant ends with `:app:lintGoogletvDebug FAILED`, `1 errors, 0 warnings`; the single finding is `app/src/main/AndroidManifest.xml:58: Error: Attribute usesPermissionFlags is only used in API level 31 and higher (current min is 29) [UnusedAttribute]` (report: `app/build/reports/lint-results-googletvDebug.txt`). The `firetv` variant did not run — the build stopped at the first failure. The finding sits in the shared manifest, so it is not specific to this milestone.
  A first attempt with `--offline` failed earlier and for a different reason: the `androidTest` dependencies `androidx.test.ext:junit:1.1.5` and `androidx.test.espresso:espresso-core:3.5.1` are not in the local Gradle cache, so the lint model could not be generated.
- Both flavours build — `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug`:
  **yes** — see section 2.1.
- No new dependency / no new permission: **yes** — the release tooling adds no
  dependency to the app.

### 2.1 Measured outputs

**JVM suite**

```
$ source /workspace/toolchain/env.sh
$ ./gradlew :test-runner:test --offline --console=plain
...
BUILD SUCCESSFUL in 3m 27s
EXIT=0
457 tests, 0 failures, 0 errors, 0 skipped (54 test classes)
```

**Both debug APKs**

```
$ ./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline --console=plain
...
BUILD SUCCESSFUL
```

**Repository structure checks**

```
$ bash scripts/check-file-size.sh        ; echo "exit=$?"
OK: file size check passed — 177 file(s) checked, none above the hard limit of 550 lines, 3 warning(s) above the soft limit of 400 lines.
exit=0

$ bash scripts/check-kdoc-headers.sh     ; echo "exit=$?"
FAILED: KDoc header check — 16 class declaration(s) without a KDoc header out of 222 declaration(s) checked.
exit=1

$ bash scripts/check-docs-links.sh       ; echo "exit=$?"
OK: documentation link check passed — 191 internal link(s) resolve, 15 external link(s) skipped.
exit=0

$ bash scripts/check-i18n-completeness.sh ; echo "exit=$?"
OK: i18n completeness check passed — 80 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).
exit=0
```

---

## 3. Performance

| Metric | Budget | Measured | Result |
|---|---:|---:|---|
| JVM suite runtime | ≤ 30 min (the CI timeout) | **3 min 27 s** | **PASS** |
| Debug APK size (googletv) | ≤ 10 MB (the Fire TV release budget, AC-M9.4) | **not measured** — the APK was built but not weighed | — |
| Debug APK size (firetv) | ≤ 10 MB | **not measured** | — |
| Peak RAM during one stream | ≤ 150 MB | **not measured** — no device | — |
| Average CPU | ≤ 30 % | **not measured** — no device | — |
| Frame rate | ≥ 25 fps | **not measured** — no device | — |
| Video latency | ≤ 100 ms | **not measured** — no device | — |
| A/V drift | ≤ 40 ms | **not measured** — no device | — |

**Reference device / input:** none. The build machine is the only reference
environment; the device numbers are the ones the quality model
(`docs/quality/QUALITY.md` §3) requires and none of them has been taken.

**Measurement commands used:**

```bash
# what was actually run:
./gradlew :test-runner:test --offline --console=plain
./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline --console=plain

# what still has to run on a device:
adb shell dumpsys meminfo com.phairplay.googletv
adb shell top -p $(adb shell pidof com.phairplay.googletv)
ls -la app/build/outputs/apk/firetv/release/app-firetv-release.apk
apksigner verify app/build/outputs/apk/googletv/release/app-googletv-release.apk
```

---

## 4. Usability

- Step count from "I want to mirror" to "it works": **not measured** — no device.
- Discoverability: the release artefacts are discoverable through the README,
  the installation guide and the GitHub release page; the release process itself
  is documented in `docs/CONTRIBUTING.md` and in `scripts/release.sh`.
- The screen tells the truth: not a release-tooling concern; the relevant truth
  is in the documentation, and the README currently overstates the DLNA status
  (scenario 5).
- The error state is understandable: not applicable to the release tooling.
- Visual / polish issues seen: **not assessed** — no device.
- Strings: not applicable to the release tooling.

---

## 5. Accessibility

- D-pad navigation: **not measured** — no device.
- Focus target size ≥ 48 dp (NFR-07): **not measured**.
- Text size ≥ 18 sp for body, ≥ 32 sp for titles (NFR-08): **not measured**.
- Contrast readable from 3 metres (NFR-09): **not measured**.
- TalkBack / screen reader: **not measured**.
- What is **not** covered yet: the accessibility verification of the released
  build on a real television. A release claim of "stable" that has never been
  seen on a screen is a documentation claim, not a verified one.

---

## 6. Security and privacy

- New network input (a new parser, a new field, a new port): **no** — the
  release tooling adds no network surface.
- New stored data: **yes** — the release script writes the keystore and the
  credentials to `~/.config/phairplay/` (outside the repository, with
  `0600`-style intent; the path is not inside the git tree). The git history
  carries no secret: `git ls-files scripts/` shows only the script itself, and
  `.gitignore` excludes the local credentials.
- New outbound connection (where does it go?): **yes** — `scripts/release.sh`
  publishes to GitHub through the `gh` CLI. That is the only outbound
  connection of the release path and it is explicit.
- New permission: **no**.
- Negative tests added (the test that proves the guard rejects the attack):
  **yes, for the tooling** — `docs/audit/qa-quality-gate-negative-test.md`
  documents 22 green → red → green cases for the four scripts. For the app, the
  negative tests of the protocol guards are covered in the M12 acceptance record.
- The log reduction still applies to every new log statement (SEC-07)? **yes** —
  no new log statement was added by the release tooling.
- Known findings from the audit that still apply to this feature: **SEC-07**
  (a release build logs nothing — the reason the device evidence for a release
  build has to come from the debug build or from `adb logcat` with the right
  tag), **SEC-10** (cleartext is blocked by the platform default — the release
  inherits this unchanged).
- Privacy impact: does anything new leave the local network? **It must not.**
  The release tooling talks to GitHub, not to the local network; the app itself
  keeps its zero-telemetry promise, which is documented in `PRIVACY.md` and
  backed by the permission audit.

---

## 7. Interoperability and data integrity

| Sender | Version | Protocol | Result |
|---|---|---|---|
| macOS | — | AirPlay 2 | **NOT MEASURED** — no device, no sender |
| iPhone / iPad | — | AirPlay 2 | **NOT MEASURED** — no device, no sender |
| Windows | — | Miracast (WFD) | **NOT MEASURED** — no device, no sender |
| Windows | — | DLNA / UPnP | **NOT MEASURED** — no device, no sender |
| VLC | — | DLNA / UPnP | **NOT MEASURED** — no device, no sender |
| Chrome | — | Google Cast | **NOT MEASURED** — no application ID, no device |

- Round trip: not applicable to the release tooling.
- Does an unsupported format fail cleanly instead of crashing? **yes (code)** —
  the release script aborts with a clear error when a credential is missing.
- Recovery: does the receiver come back to a usable state after an interruption?
  **not measured** — no device.
- Artefact integrity: the release path produces both APKs from the same source
  tree and signs them with one key. **The keystore backup is the owner's
  responsibility and is not confirmed** — risk R-10. Losing the key means
  installed apps cannot be upgraded in place.

---

## 8. Known limitations

| Limitation | Reason | Where it is documented |
|---|---|---|
| The CI quality gate is **red** on the current commit | 16 declarations still lack a KDoc header | Risk R-07 in `docs/project/RISKS.md`; `docs/acceptance/QA-QUALITY-GATE-ACCEPTANCE.md` §1.2 |
| The lint fails with one finding | `app/src/main/AndroidManifest.xml:58` uses `android:usesPermissionFlags`, an attribute that needs API 31, while the `googletv` flavour has `minSdk = 29` | This document, section 2; risk R-16 |
| The signed release APKs were not produced in this cycle | The keystore is outside the repository and the owner supplies it | `scripts/release.sh`; risk R-10 |
| The README overstates the DLNA status and understates the version | The README is owned by the docs track; the correction is proposed here | This document, scenario 5; the contradiction list in the handover |
| The `file:///` pull-semantics statement is missing from the README | Same ownership situation | AC-M11.8 |
| 38 test methods never run in CI | Six test files are excluded from the JVM runner | Risk R-13; `test-runner/build.gradle.kts` |
| No end-to-end validation on any device | No hardware was available | `docs/guides/DEVICE_TEST_PLAN.md`; risk R-12 |

---

## 9. Decision

**accepted with documented limitation — as a BETA, not as a stable v1.0.0**

**Decided by:** Project Manager, on behalf of the owner
**Date:** 2026-10-24

The release engineering of M11 is **in place and partly verified**: the CI
pipeline runs the four structure checks, the JVM suite, the lint and both debug
APKs; the release script builds, signs and publishes both flavours; the
changelog follows a consistent format. The measured evidence of this cycle is
the green JVM suite (457 tests, 0 failures) and the successful build of both
debug APKs.

What is missing is everything that makes a release *stable* rather than
*beta*:

1. the CI gate is **red** (16 KDoc headers) — the release cannot be merged
   through a green pipeline;
2. the **lint gate is red** (one `UnusedAttribute` finding in the shared manifest) and no signed APK was produced;
3. **no device test** of any kind has been executed — 0 of 15 cases;
4. three **High security findings** are still open in the code (SEC-01, SEC-02,
   SEC-03), one of them (SEC-03, the unbounded FU-A buffer) directly in the
   AirPlay media path;
5. the **documentation** contradicts the code in three places (README: DLNA
   "planned", version beta.2, missing `file:///` statement).

Points 1, 2 and 5 are cheap and can be closed this week. Points 3 and 4 decide
whether the release is called `v1.0.0` or `v1.0.0-beta.4`.

**Follow-up (open, because …):**

1. **Fix the 16 KDoc violations** (or decide that test files are exempt with
   `--skip-tests` in the CI call) so the quality gate turns green. Reason: the
   pipeline is red, so no merge and no release can go through it.
2. **Run `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug`** and fix the
   findings. Reason: AC-M11.2 requires zero findings; the configuration counts
   warnings as errors.
3. **Produce and verify the signed release APKs** with `apksigner verify`.
   Reason: AC-M11.3; the keystore is the owner's.
4. **Correct the README** (version beta.3, DLNA implemented, `file:///`
   statement). Reason: AC-M11.5 and AC-M11.8 fail today.
5. **Close SEC-01, SEC-02 and SEC-03** or get a written owner decision for each.
   Reason: SEC-03 is an unbounded remote-triggerable buffer in the AirPlay path
   — an S4-class finding in the quality model.
6. **Execute the device test plan** on a Google TV and a Fire TV. Reason: no
   claim of "stable" is defensible without it.
7. **Back up the release keystore** outside the team-controlled storage.
   Reason: the key is the identity of the project after v1.0.0.

---

## 10. Release gate

The gate is defined in [`../quality/QUALITY.md`](../quality/QUALITY.md) §7.

| # | Gate | Result |
|---|---|---|
| 1 | `./gradlew :test-runner:test` is green | **yes** — 457 tests, 0 failures, 0 errors, 0 skipped (measured) |
| 2 | Lint and both debug APKs are green | **partly** — both debug APKs build (measured); the lint **fails** with 1 error (`UnusedAttribute`, `app/src/main/AndroidManifest.xml:58`) |
| 3 | **No known S4 finding** | **NO** — SEC-01, SEC-02 and SEC-03 are open in the code. SEC-03 sits in the AirPlay RTP path. Each needs a fix or a written owner decision. |
| 4 | Every open S3 has a written owner decision | **partly** — SEC-04 (S3, the DLNA media URI) is open in the code and needs either the CR-01 change or a written owner decision in the release notes; SEC-09 and SEC-10 carry their grades in the audit, the accepted residual risks are listed in §7 |
| 5 | The device scenarios ran on both flavours | **NO** — 0 of 15 device test cases executed |
| 6 | The file-size rule holds for the touched files | **partly** — no file is above 550; three are above 400. The script passes, the written rule does not (risk R-08) |
| 7 | The documentation is up to date (same change) | **NO** — the README contradicts the code in three places (DLNA status, version, `file:///` statement). The changelog contradiction (D3 media playback) is corrected in this commit. |
| 8 | The limitations in `README.md` are true of this build | **NO** — the README says the DLNA MediaRenderer "is planned"; it is implemented. Correction proposed to the docs track. |
