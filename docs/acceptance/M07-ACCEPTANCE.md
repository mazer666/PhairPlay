# Acceptance — M7: Google Cast receiver (graceful degradation)

**Feature / milestone:** M7 — Google Cast receiver for Google TV
**Requirement IDs:** FR-06, FR-08, FR-11, NFR-19, AC-M7.1 … AC-M7.6
**Build / commit:** the integrated tree (branch `main`; the earlier stand was
`51933eb`)
**Flavour(s) tested:** `googletv` (Cast SDK present) and `firetv` (no Cast SDK);
both compile
**Device(s) tested:** **none** — no television was available
**Sender(s) tested:** **none** — no Chrome / Cast sender was available
**Tester / roles:** Project Manager (audit), with the code evidence of the Cast
integration and the JVM test run
**Date:** 2026-10-24
**Update (second pass, 2026-10-24):** the team leader closed the three open
points of the previous round — the CI quality gate, the lint and the three files
above the line limit. The statements about the red gate, the missing KDoc
header, the lint finding and the file-size warning were brought up to date in
the same commit.
**Update (third pass, 2026-10-24):** the JVM suite was **run again** on the
integrated tree and measured: **549 tests, 0 failures, 0 errors, 0 skipped**,
63 test classes, `BUILD SUCCESSFUL in 3m 18s`. Before this cycle the suite had
277 tests. Both debug APKs build on that tree and the lint is green for both
flavours.

---

## 1. Functional

Does the feature do what the requirement says?

| # | Scenario | Expected | Result | Evidence |
|---|---|---|---|---|
| 1 | The `firetv` flavour contains no reference to the Google Cast SDK | 0 matches | **PASS (code)** | `grep -rn "com.google.android.gms" app/src/firetv` → 0 matches. The Fire TV flavour has its own `CastReceiver` (`app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt`) that never imports the SDK. |
| 2 | On Fire TV the Cast receiver reports `DISABLED` and never initialises the Cast SDK | `ProtocolState.DISABLED`, no SDK call | **PASS (code)** | `app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt`: `start()` logs `Google Cast disabled on Fire TV flavor` and calls `onStateChanged(ProtocolState.DISABLED)`; `isAvailable()` returns `false` unconditionally. The `CastReceiverContext` symbol does not occur in the Fire TV sources. |
| 3 | On `googletv` without a registered application ID the receiver reports `ERROR` and does not start the SDK | `ProtocolState.ERROR`, `CastReceiverContext.initInstance` not called | **PASS (unit test + code)** | `app/src/googletv/kotlin/com/phairplay/cast/CastReceiver.kt`: `if (!isConfigured()) { Logger.w("Google Cast is not configured: missing Cast application ID"); onStateChanged(ProtocolState.ERROR); return }`. Unit tests `blank Cast app ID is not configured` and `placeholder Cast app IDs are not configured` — **PASSED** in the measured run. |
| 4 | `BuildConfig.CAST_APP_ID` is empty unless the build is given an ID | the property defaults to `""` | **PASS (code)** | `app/build.gradle.kts` lines 17–19 read `-Pphairplay.castAppId` / `PHAIRPLAY_CAST_APP_ID`, line 35 writes the value into `BuildConfig`. No `local.properties` exists in the repository and no ID is set in the environment, so the generated constant is empty for this build. |
| 5 | A registered application ID is accepted by the configuration check | `isConfigured("…")` is `true` | **PASS (unit test)** | `CastReceiverTest > registered Cast app ID is configured` — **PASSED**. |
| 6 | The Fire TV APK contains no Play Services classes | 0 entries in the APK listing | **NOT MEASURED** | The APK was built in this audit (see section 2.1) but the listing was not inspected for `com/google/android/gms`. Reason: the inspection is a one-line `unzip -l` that belongs to the release checklist. |
| 7 | With a valid ID the Google TV flavour starts the Cast Connect receiver | the SDK is initialised and the state becomes `ADVERTISING` | **NOT MEASURED** | Reason: no application ID exists (see "Known limitations") and no device is available. This is the core of the milestone and it is unproven. |
| 8 | Cast media load and playback are handled | the sender can push a media stream | **NOT MEASURED** | Reason: the same as scenario 7. There is no Cast media implementation in the repository — `grep -rn "MediaManager\|MediaLoadRequest" app/src` → 0 matches. |

**Edge cases exercised:**

| Case | Expected | Result |
|---|---|---|
| No application ID configured | `ERROR`, not a silent "ready" state | **PASS** — unit test `blank Cast app ID is not configured` |
| The placeholder ID `TODO_REGISTER_YOUR_CAST_APP_ID` is still in the source | it must not count as configured | **PASS** — unit test `placeholder Cast app IDs are not configured`; the constant is checked explicitly in both flavour implementations |
| The ID `00000000` | must not count as configured | **PASS (code)** — both flavour implementations reject it explicitly |
| The device has no Google Play Services (Fire TV) | `DISABLED`, the SDK is never touched | **PASS (code)** — the Fire TV implementation has no SDK import at all |
| The device has no Google Play Services (Google TV without GMS) | `DISABLED` | **PASS (code)** — `isAvailable(context)` checks for `com.google.android.gms`; `start()` reports `DISABLED` before the SDK call |

---

## 2. Technical

- Architecture boundaries respected (the protocol stacks stay independent): **yes** —
  the Cast integration is a flavour-specific source set
  (`app/src/googletv/kotlin/com/phairplay/cast/` and
  `app/src/firetv/kotlin/com/phairplay/cast/`), each with one class of the same
  name and the same public surface. The AirPlay and DLNA stacks are untouched.
- The file-size rule respected (≤ 400 lines soft, ≤ 550 hard): **yes** —
  `CastReceiver.kt` (googletv) 81 lines, `CastReceiver.kt` (firetv) 40 lines,
  `CastReceiverOptionsProvider.kt` 14 lines. The repository-wide check is green
  as well: `bash scripts/check-file-size.sh` → exit 0, `0 warning(s) above the
  soft limit of 400 lines`.
- Every new class has a KDoc header (what / why / example): **yes** — the two
  `CastReceiver` classes and `CastReceiverOptionsProvider` carry their headers.
  The violation of the previous round
  (`app/src/googletv/kotlin/com/phairplay/cast/CastReceiverOptionsProvider.kt:8:
  MISSING-KDOC CastReceiverOptionsProvider`) is closed, and the KDoc gate is
  green on the whole tree: `bash scripts/check-kdoc-headers.sh` → exit 0,
  `OK: KDoc header check passed — 227 class declaration(s) checked, all of them
  have a KDoc header.`
- Every new public method has a test: **partly** — the configuration check is
  covered by three tests; `start()` / `stop()` are not tested because they need a
  real `Context` with or without Play Services. **Caveat:** the JVM runner does
  not compile the `googletv` source set (`test-runner/build.gradle.kts` lists
  `../app/src/main/kotlin`, `../app/src/firetv/kotlin` and
  `../app/src/test/kotlin`), so `CastReceiverTest` exercises the **Fire TV**
  implementation of `isConfigured`. The logic is identical in both
  implementations, but the `googletv` class itself is not covered by the run.
- Tests green — `./gradlew :test-runner:test`: **yes** — measured on the integrated
  tree: `BUILD SUCCESSFUL in 3m 41s`, **549 tests**, 0 failures, 0 errors,
  0 skipped, 63 test classes. The three Cast tests are part of that run (see the
  caveat above: they run against the Fire TV implementation). The
  `com.phairplay.cast` package still holds its 3 test methods. Before this
  cycle the suite had 277 tests.
- Lint clean — `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug`: **yes** —
  measured after the team leader's fix: `:app:lintGoogletvDebug` → `BUILD
  SUCCESSFUL` (5m 12s) and `:app:lintFiretvDebug` → `BUILD SUCCESSFUL` (3m 39s).
  Both reports say `No issues found.` The single finding of the previous round —
  `app/src/main/AndroidManifest.xml:58: Error: Attribute usesPermissionFlags is
  only used in API level 31 and higher (current min is 29) [UnusedAttribute]` —
  is closed: the permission element now carries `tools:targetApi="31"`. The
  `firetv` variant, which was never analysed before, is analysed and green.

- Both flavours build — `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug`:
  **yes** — see section 2.1.
- No new dependency / no new permission: **no new permission** — the Cast SDK is
  a project dependency of the `googletv` flavour only. The SDK brings no
  permission of its own; the permission audit in
  `docs/audit/2026-10-24-security-audit.md` §4 lists `ACCESS_WIFI_STATE` as
  required by the Cast SDK.

### 2.1 Build log (measured)

```
$ source /workspace/toolchain/env.sh
$ ./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline --console=plain
...
BUILD SUCCESSFUL
```

**Test run output (measured on the integrated tree):**

```
BUILD SUCCESSFUL in 3m 18s
549 tests, 0 failures, 0 errors, 0 skipped (63 test classes)
```

The test run and the APK build above describe the integrated tree. Before this
cycle the suite had 277 tests.

**Repository structure checks (measured in the second pass, current tree):**

```
$ bash scripts/check-file-size.sh        ; echo "exit=$?"
OK: file size check passed — 184 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.
exit=0

$ bash scripts/check-kdoc-headers.sh     ; echo "exit=$?"
OK: KDoc header check passed — 227 class declaration(s) checked, all of them have a KDoc header.
exit=0

$ bash scripts/check-docs-links.sh       ; echo "exit=$?"
OK: documentation link check passed — 192 internal link(s) resolve, 15 external link(s) skipped.
exit=0

$ bash scripts/check-i18n-completeness.sh ; echo "exit=$?"
OK: i18n completeness check passed — 114 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).
exit=0
```

**Lint (measured in the second pass, current tree):**

```
$ ./gradlew :app:lintGoogletvDebug     -> BUILD SUCCESSFUL (5m 12s)
$ ./gradlew :app:lintFiretvDebug       -> BUILD SUCCESSFUL (3m 39s)

$ cat app/build/reports/lint-results-googletvDebug.txt
No issues found.
$ cat app/build/reports/lint-results-firetvDebug.txt
No issues found.
```

---

## 3. Performance

| Metric | Budget | Measured | Result |
|---|---:|---:|---|
| Frame rate | ≥ 25 fps | **not measured** — no Cast media path exists | — |
| Video latency | ≤ 100 ms | **not measured** | — |
| RAM peak | ≤ 150 MB | **not measured** | — |
| CPU average | ≤ 30 % | **not measured** | — |
| APK size (Google TV, with the Cast SDK) | ≤ 10 MB (AC-M9.4, Fire TV) | **not measured** — the debug APK was built but not weighed | — |

**Reference device / input:** none.

**Measurement commands used:**

```bash
# device test plan case DT-07 (needs a Google TV and a Chrome sender):
adb shell dumpsys meminfo com.phairplay.googletv
ls -la app/build/outputs/apk/googletv/release/app-googletv-release.apk
```

> No performance number was measured for M7.

---

## 4. Usability

- Step count from "I want to mirror" to "it works": **not measurable** — the
  Cast path cannot be completed without an application ID and a sender. The
  user-visible path is: Settings → Protocols → Cast on, then the card reads
  "Advertising" or the error text.
- Discoverability: the Cast card is the third protocol card on the Home screen
  (`HomeFragment.kt` lines 124–127).
- The screen tells the truth: **yes** — without an ID the receiver reports
  `ERROR` and the card shows the error state; on Fire TV it reports `DISABLED`
  with the reason text that names Google Play Services (AC-M9.6). The state
  transitions are covered by unit tests; the rendering needs a device.
- The error state is understandable: the log states "Google Cast is not
  configured: missing Cast application ID" and the guide
  `docs/guides/CAST_APP_ID.md` explains how to obtain an ID.
- Visual / polish issues seen: **not assessed** — no device.
- Strings: all user-visible texts are in resource files — `grep -rn "setText(\""
  app/src/main` → 0 matches.

---

## 5. Accessibility

- D-pad navigation: **not measured** — no device.
- Focus target size ≥ 48 dp (NFR-07): **not measured**.
- Text size ≥ 18 sp for body, ≥ 32 sp for titles (NFR-08): **not measured**.
- Contrast readable from 3 metres (NFR-09): **not measured**.
- TalkBack / screen reader: **not measured**.
- What is **not** covered yet: the whole accessibility verification of the Cast
  card. There is no M7-specific accessibility code; the card is a `View` like the
  other three. The measurement needs a device.

---

## 6. Security and privacy

- New network input (a new parser, a new field, a new port): **partly** — the
  Cast SDK itself opens the discovery sockets; PhairPlay contributes no parser
  of its own for Cast. The trust boundary is the Google SDK, which is outside the
  source tree.
- New stored data: **no** — the Cast receiver stores nothing; the SDK owns the
  session state.
- New outbound connection (where does it go?): **yes** — to Google's Cast
  infrastructure through the SDK. This is the **only** component of PhairPlay
  that talks beyond the local network, and it is a third-party component that
  PhairPlay does not control. It is declared in `PRIVACY.md`.
- New permission: **no new permission** for this feature in this cycle.
- Negative tests added: **no Cast-specific negative test exists** — the guard
  that is tested is the configuration check (an empty or placeholder ID must not
  count as configured). There is no test that proves the SDK is not initialised
  without an ID, because that needs a real `Context`.
- The log reduction still applies to every new log statement (SEC-07)? **yes** —
  every message goes through `com.phairplay.util.Logger`.
- Known findings from the audit that still apply to this feature: **SEC-13** — the
  Cast SDK is a black box; it is not part of the threat model beyond that
  (audit §8).
- Privacy impact: does anything new leave the local network? **The Cast SDK
  does.** This is the documented exception in `PRIVACY.md`: with Cast enabled,
  the Google SDK performs its own discovery and session traffic. PhairPlay itself
  sends nothing beyond the LAN; the zero-telemetry commitment applies to
  PhairPlay's own code, not to the Google SDK.

---

## 7. Interoperability and data integrity

| Sender | Version | Protocol | Result |
|---|---|---|---|
| macOS | — | AirPlay 2 | not applicable to M7 |
| iPhone / iPad | — | AirPlay 2 | not applicable to M7 |
| Windows | — | Miracast (WFD) | not applicable to M7 |
| Windows | — | DLNA / UPnP | covered by M12 |
| VLC | — | DLNA / UPnP | covered by M12 |
| Chrome | — | Google Cast | **NOT MEASURED** — no application ID, no device, no sender. Device test plan case DT-07. |

- Round trip: does the state come back correctly? **not measured**.
- Does an unsupported format fail cleanly instead of crashing? **yes (code)** —
  without an ID the receiver reports `ERROR` instead of throwing; the SDK call is
  wrapped in a `try`/`catch` that reports `ERROR` on any exception.
- Recovery: does the receiver come back to a usable state after an interruption?
  **not measured** — no device.

---

## 8. Known limitations

| Limitation | Reason | Where it is documented |
|---|---|---|
| Google Cast does not work without a registered application ID | The ID is assigned by Google in the Cast Developer Console; it cannot be created locally | `docs/guides/CAST_APP_ID.md`; `README.md` ("Honest limitations"); `docs/spec/REQUIREMENTS.md` §3 |
| Google Cast does not exist on Fire TV | Fire TV ships no Google Play Services, so the Cast SDK cannot run | `app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt`; `docs/spec/REQUIREMENTS.md`; ADR-001 (graceful degradation) |
| PhairPlay renders no Cast media itself | There is no Cast media implementation in the repository (`grep -rn "MediaManager\|MediaLoadRequest" app/src` → 0 matches); the Cast SDK is only wired to its lifecycle | `docs/spec/REQUIREMENTS.md` §3; the media capability is a declared out-of-scope item |
| The Cast stack is unverified end to end | No application ID and no device in this cycle | This document; `docs/acceptance/QA-DEVICE-TEST-ACCEPTANCE.md` |

---

## 9. Decision

**accepted with documented limitation**

**Decided by:** Project Manager, on behalf of the owner
**Date:** 2026-10-24

M7 is delivered as what the requirements actually ask for: a **graceful
degradation**. The Cast SDK lifecycle is implemented, the flavour split works, the
Fire TV path degrades to `DISABLED`, and the unconfigured path degrades to
`ERROR` instead of pretending. The full Cast receiver — a sender that can push
media — is **not** delivered and cannot be delivered without an application ID
that only the owner can obtain.

The milestone status in `docs/spec/PROJECT_PLAN.md` ("🟠 Partial — graceful
degradation only") is therefore correct: it is not "in progress", it is
"degradation delivered, function pending an external prerequisite".

The second pass closed the two pipeline gates that were red in the previous
round: the CI quality gate is green (all four structure checks exit 0) and the
lint is green for **both** flavours. That changes nothing about the Cast
prerequisites: without a registered application ID and without hardware, the
milestone stays a graceful degradation.

**Follow-up (open, because …):**

1. **Register the Cast application** in the Google Cast Developer Console and
   build the Google TV flavour with `-Pphairplay.castAppId=<APP_ID>`.
   Reason: without the ID no end-to-end Cast behaviour can be observed.
2. **Run device test plan case DT-07** on a Google TV with a Chrome sender.
   Reason: no hardware was available.
3. **Decide whether Cast media playback is in scope for v1.0.0.** Reason: there
   is no implementation, and a decision is cheaper now than after the release.

---

## 10. Release gate

| # | Gate | Result |
|---|---|---|
| 1 | `./gradlew :test-runner:test` is green | **yes** — measured on the integrated tree: **549 tests, 0 failures, 0 errors, 0 skipped** in 63 test classes (`BUILD SUCCESSFUL in 3m 18s`). Before this cycle the suite had 277 tests. |
| 2 | Lint and both debug APKs are green | **yes** — the lint is green for both flavours (`:app:lintGoogletvDebug` and `:app:lintFiretvDebug`, `BUILD SUCCESSFUL`, both reports `No issues found.`); both debug APKs build (measured on the integrated tree) |
| 3 | **No known S4 finding** | **yes** — the M7 surface has no S4 finding; the SDK is a documented black box (SEC-13) |
| 4 | Every open S3 has a written owner decision | **yes** — SEC-13 is an accepted Info finding; the Cast scope is written down in `docs/spec/REQUIREMENTS.md` §3 |
| 5 | The device scenarios ran on both flavours | **no** — DT-07 has not been executed on either flavour |
| 6 | The file-size rule holds for the touched files | **yes** — all Cast files are far below the soft limit |
| 7 | The documentation is up to date (same change) | **yes** — `docs/guides/CAST_APP_ID.md`, `README.md`, `docs/spec/REQUIREMENTS.md` §3 |
| 8 | The limitations in `README.md` are true of this build | **yes** — README states that full Cast testing requires a Cast app ID |

**CI quality gate** (the job `quality-gate` of `.github/workflows/ci.yml`, not a
separate gate in `docs/quality/QUALITY.md` §7): **green** — all four structure
checks exit 0 (section 2.1).

