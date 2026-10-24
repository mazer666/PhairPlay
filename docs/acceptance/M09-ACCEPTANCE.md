# Acceptance — M9: Fire TV port

**Feature / milestone:** M9 — Fire TV flavour
**Requirement IDs:** FR-06, FR-09, FR-10, AC-M9.1 … AC-M9.6 (plus the legacy round
AR-7 / `AC-7.x`)
**Build / commit:** `51933eb` (branch `feature/quality-m6-m12`, merged into
`feature/project-status`)
**Flavour(s) tested:** `firetv` — **built and verified at the artifact level**;
no device
**Device(s) tested:** **none** — no Fire TV was available
**Sender(s) tested:** **none**
**Tester / roles:** Project Manager (audit), with the build output and the code
evidence
**Date:** 2026-10-24

---

## 1. Functional

Does the feature do what the requirement says?

| # | Scenario | Expected | Result | Evidence |
|---|---|---|---|---|
| 1 | The `firetv` flavour builds | exit code 0, the debug APK exists | **PASS (measured)** | `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline` → `BUILD SUCCESSFUL` (see section 2.1). The APK is written to `app/build/outputs/apk/firetv/debug/app-firetv-debug.apk`. |
| 2 | The `firetv` flavour uses no API above API 25 without a runtime check | 0 `NewApi` findings in the lint report | **NOT MEASURED** | The lint ran for the `googletv` variant and failed (section 2); the `firetv` variant never ran, so the `NewApi` check for API 25 is still open. The command is `./gradlew :app:lintFiretvDebug`. `minSdk = 25` is set for the flavour (`app/build.gradle.kts` line 66). |
| 3 | The Cast card on Fire TV shows "disabled" and names Google Play Services as the reason | the card shows the disabled state with the reason text | **PASS (code) / NOT MEASURED (device)** | `app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt`: `isAvailable()` returns `false`, `start()` reports `ProtocolState.DISABLED`. The Fire TV implementation contains **no** reference to `com.google.android.gms` (`grep -rn "com.google.android.gms" app/src/firetv` → 0 matches), so the SDK can never be initialised. The card renders the state via `HomeFragment.kt` line 183 (`svc.castState`). The rendering is device-only. |
| 4 | The AirPlay criteria pass on Fire TV hardware | all of AR-3, AR-4, AR-5 pass on a Fire TV Stick | **NOT MEASURED** | No Fire TV was available. Device test plan cases DT-03, DT-04 and DT-05. This is the largest gap of the milestone. |
| 5 | The Fire TV release APK stays below the size budget | ≤ 10 MB | **PARTLY** | The **debug** APK was built and weighed: `app-firetv-debug.apk`, 16 294 476 bytes = **15.5 MB**. A debug APK is always larger than a release APK (no R8 shrinking, no resource optimisation, the full Cast SDK is absent but the debug tooling is present), so this number says nothing about the release budget. The **release** APK was not built in this cycle, so AC-M9.4 is still open. |
| 6 | The UPnP/DLNA renderer is discoverable and controllable on Fire TV | three `200 OK` answers, `description.xml` reachable, `GetProtocolInfo` answers 200 | **NOT MEASURED** | The DLNA code is in the shared `main` source set and is therefore in both flavours; the *device* behaviour is unproven. Device test plan case DT-08. |
| 7 | The `firetv` flavour contains no Play Services classes | 0 entries in the APK | **PASS (measured)** | The built Fire TV debug APK was unpacked and its DEX files were searched: `strings classes*.dex | grep -c "com/google/android/gms"` → **0**. The same search on the Google TV debug APK → **2133** (of which 461 are `com/google/android/gms/cast`). The Fire TV APK therefore carries no Play Services class at all. |
| 8 | Wi-Fi Direct on Fire TV | the Miracast card reports the hardware limitation, not a crash | **NOT MEASURED** | Device test plan case DT-06 records "not applicable — hardware limitation" with a written reason; the unit test `start emits error when WifiP2pManager is unavailable` covers the code path. |
| 9 | The Fire TV flavour uses its own package name and its own launcher | `com.phairplay.firetv`, `versionNameSuffix = "-firetv"` | **PASS (code)** | `app/build.gradle.kts` lines 63–67: `create("firetv") { applicationId = "com.phairplay.firetv"; minSdk = 25; versionNameSuffix = "-firetv" }`. |

**Edge cases exercised:**

| Case | Expected | Result |
|---|---|---|
| Play Services are missing on the device | the Cast receiver reports `DISABLED` and never initialises the SDK | **PASS (code)** — the Fire TV implementation has no SDK import at all, so the failure mode cannot occur |
| The user turns Cast on on Fire TV | the card shows the disabled state with the reason | **PASS (code)** / **NOT MEASURED (device)** |
| The device is an old Fire TV on API 25 | no `NewApi` crash | **NOT MEASURED** — needs the lint report and a device |
| The device has no Wi-Fi Direct | the Miracast card shows the error, the app keeps running | **PASS (unit test)** — `MiracastReceiverTest > start emits error when WifiP2pManager is unavailable` |

---

## 2. Technical

- Architecture boundaries respected (the protocol stacks stay independent): **yes** —
  the flavour split is a source-set split; the Cast class of the Fire TV flavour
  has the same public surface as the Google TV one.
- The file-size rule respected (≤ 400 lines soft, ≤ 550 hard): **yes** — the
  Fire TV flavour contains one file, `CastReceiver.kt`, 40 lines.
- Every new class has a KDoc header: **yes** — the Fire TV `CastReceiver` has a
  KDoc header (lines 7–12). The KDoc gate reports one violation in the
  **Google TV** flavour:
  `app/src/googletv/kotlin/com/phairplay/cast/CastReceiverOptionsProvider.kt:8: MISSING-KDOC CastReceiverOptionsProvider`.
- Every new public method has a test: **partly** — `isConfigured` and
  `isAvailable` are covered by `CastReceiverTest` (3 tests, **PASSED**); `start()`
  and `stop()` are not, because they need a real `Context`.
- Tests green — `./gradlew :test-runner:test`: **yes** — measured on `51933eb`:
  `BUILD SUCCESSFUL in 3m 27s`, 457 tests, 0 failures, 0 errors, 0 skipped,
  54 test classes. Note: the JVM runner compiles the `main` source set **and the
  `firetv` flavour** (`../app/src/firetv/kotlin` is in the `srcDirs` of
  `test-runner/build.gradle.kts`), so the Fire TV Cast implementation is
  compiled and its shared logic is tested. The `googletv` flavour source set is
  **not** compiled by the runner, so the Google TV variant of the Cast
  receiver has no unit test at all.
- Lint clean — `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug`: **no — the lint was run and it failed.** Measured on `51933eb`: the `googletv` variant ends with `:app:lintGoogletvDebug FAILED`, `1 errors, 0 warnings`; the single finding is `app/src/main/AndroidManifest.xml:58: Error: Attribute usesPermissionFlags is only used in API level 31 and higher (current min is 29) [UnusedAttribute]`. The `firetv` variant did not run — the build stopped at the first failure. The finding sits in the shared manifest, so it is not specific to this milestone.
- Both flavours build — `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug`:
  **yes** — see section 2.1.
- No new dependency / no new permission: **yes** — the Fire TV flavour has **no**
  Cast SDK dependency and adds no permission.

### 2.1 Build log (measured)

```
$ source /workspace/toolchain/env.sh
$ ./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline --console=plain
...
> Task :app:packageGoogletvDebug
> Task :app:packageFiretvDebug
BUILD SUCCESSFUL
```

**Test run output:**

```
BUILD SUCCESSFUL in 3m 27s
457 tests, 0 failures, 0 errors, 0 skipped (54 test classes)
```

### 2.2 The built artefacts (measured)

```
app/build/outputs/apk/googletv/debug/app-googletv-debug.apk   29 296 434 bytes (27.9 MB)
app/build/outputs/apk/firetv/debug/app-firetv-debug.apk        16 294 476 bytes (15.5 MB)
```

Both are **debug** APKs. The release APKs were not built in this cycle.

**Play Services in the APKs (measured):**

```bash
unzip -o -q app/build/outputs/apk/firetv/debug/app-firetv-debug.apk 'classes*.dex'
strings classes*.dex | grep -c "com/google/android/gms"
# firetv  → 0
# googletv → 2133   (of which 461 are com/google/android/gms/cast)
```

---

## 3. Performance

| Metric | Budget | Measured | Result |
|---|---:|---:|---|
| Fire TV **debug** APK size | — (the ≤ 10 MB budget applies to the release APK) | **15.5 MB** — `app-firetv-debug.apk`, 16 294 476 bytes | — |
| Fire TV **release** APK size | ≤ 10 MB (AC-M9.4) | **not measured** — the release APK was not built | — |
| Peak RAM during one stream | ≤ 150 MB | **not measured** — no device | — |
| Average CPU | ≤ 30 % | **not measured** — no device | — |
| Frame rate | ≥ 25 fps | **not measured** — no device | — |
| Video latency | ≤ 100 ms | **not measured** — no device | — |
| A/V drift | ≤ 40 ms | **not measured** — no device | — |

**Reference device / input:** none. The target device class for this milestone is
a Fire TV Stick on Android 7.1 (API 25) — hardware list H2 of the device test
plan.

**Measurement commands used:**

```bash
# device test plan, Fire TV column:
adb shell dumpsys meminfo com.phairplay.firetv
adb shell top -p $(adb shell pidof com.phairplay.firetv)
ls -la app/build/outputs/apk/firetv/release/app-firetv-release.apk
```

> No performance number was measured for M9. Every figure in the table is
> **not measured**.

---

## 4. Usability

- Step count from "I want to mirror" to "it works": **not measured** — no device.
- Discoverability: the Home screen is identical on both flavours; the four
  protocol cards are the same (`HomeFragment.kt` lines 124–127).
- The screen tells the truth: **partly** — the Cast card reports `DISABLED` on
  Fire TV (code proven), and the reason text names Google Play Services. The
  rendering needs a device.
- The error state is understandable: the disabled state has its own resource
  strings (`protocol_state_disabled`, `protocol_detail_disabled`,
  `protocol_detail_service_stopped`), identical in en / de / fr.
- Visual / polish issues seen: **not assessed** — no device.
- Strings: all user-visible texts are in resource files —
  `grep -rn "setText(\"" app/src/main` → 0 matches (AC-M10.4).

---

## 5. Accessibility

- D-pad navigation: **not measured** — no device. This is the highest risk area
  for a Fire TV, because the Fire TV remote is a D-pad without a back button in
  the usual sense; the navigation of the Home screen has never been checked on
  it.
- Focus target size ≥ 48 dp (NFR-07): **not measured**.
- Text size ≥ 18 sp for body, ≥ 32 sp for titles (NFR-08): **not measured**.
- Contrast readable from 3 metres (NFR-09): **not measured**.
- TalkBack / screen reader: **not measured**.
- What is **not** covered yet: the entire Fire TV UI verification — navigation,
  focus, text size, contrast and the notification (DT-14). There is no Fire TV
  specific accessibility code; the risk is that the *shared* UI was only ever
  looked at on a Google TV.

---

## 6. Security and privacy

- New network input (a new parser, a new field, a new port): **no** — the Fire TV
  flavour adds no parser and no port; it removes the Cast SDK.
- New stored data: **no**.
- New outbound connection: **no** — in fact fewer than on Google TV, because the
  Cast SDK is not present. PhairPlay's own code still talks only to the LAN.
- New permission: **no** — the Fire TV flavour declares the same permissions as
  the Google TV flavour; nothing was added for it.
- Negative tests added: **partly** — `CastReceiverTest` proves that an empty or
  placeholder application ID is not accepted and that a registered one is. The
  "Fire TV reports DISABLED" behaviour is proven by the code, not by a test,
  because the Fire TV implementation has no `Context` dependency to mock
  meaningfully.
- The log reduction still applies to every new log statement (SEC-07)? **yes** —
  `Google Cast disabled on Fire TV flavor` goes through `Logger`.
- Known findings from the audit that still apply to this feature: **SEC-06**
  (the location permission question, relevant because Fire TV's Wi-Fi Direct is
  unusable — the audit lists "Miracast is non-functional on Android 10+" as an
  accepted residual risk).
- Privacy impact: does anything new leave the local network? **It must not, and
  the Fire TV flavour leaves strictly less than the Google TV one** — no Cast
  SDK means no Google traffic at all. The privacy promise of `PRIVACY.md` holds
  in full on Fire TV.

---

## 7. Interoperability and data integrity

| Sender | Version | Protocol | Result |
|---|---|---|---|
| macOS | — | AirPlay 2 | **NOT MEASURED** on Fire TV — device test plan DT-03 / DT-04 |
| iPhone / iPad | — | AirPlay 2 | **NOT MEASURED** on Fire TV |
| Windows | — | Miracast (WFD) | **NOT MEASURED** — Fire TV has no usable Wi-Fi Direct; recorded as "not applicable — hardware limitation" with a written reason |
| Windows | — | DLNA / UPnP | **NOT MEASURED** on Fire TV — device test plan DT-08 |
| VLC | — | DLNA / UPnP | **NOT MEASURED** on Fire TV — device test plan DT-09 |
| Chrome | — | Google Cast | **not applicable** — Fire TV has no Google Play Services; the receiver reports `DISABLED` |

- Round trip: does the state come back correctly? **not measured**.
- Does an unsupported format fail cleanly instead of crashing? **yes (code)** —
  the Cast receiver reports `DISABLED` instead of throwing when Play Services
  are missing.
- Recovery: does the receiver come back to a usable state after an interruption?
  **not measured** — no device.

---

## 8. Known limitations

| Limitation | Reason | Where it is documented |
|---|---|---|
| The Fire TV flavour has never been run on a Fire TV in this project | No hardware was available in any cycle | This document; `docs/guides/DEVICE_TEST_PLAN.md` (hardware list H2) |
| Google Cast does not exist on Fire TV | Fire TV ships no Google Play Services | `app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt`; ADR-001; `docs/spec/REQUIREMENTS.md` §3 |
| Miracast does not work on Fire TV | Fire TV hardware does not support Wi-Fi Direct in a usable way | ADR-004; `docs/spec/REQUIREMENTS.md` §3 |
| The Fire TV release APK size is unknown | Only the debug APK was built in this cycle; the release APK needs a keystore | This document, section 3 |
| The Fire TV notification and its actions have never been checked | No device | Device test plan DT-14 |

---

## 9. Decision

**accepted with documented limitation**

**Decided by:** Project Manager, on behalf of the owner
**Date:** 2026-10-24

The Fire TV port is **code-complete and build-proven**: the flavour compiles, it
ships a Cast receiver that degrades honestly to `DISABLED`, it carries no Play
Services dependency and no extra permission, and its shared logic is covered by
the green JVM suite.

What is missing is **proof on the target device**. Every Fire TV behaviour that
matters — the AirPlay handshake on API 25, the D-pad navigation, the
notification, the DLNA discovery — has never been observed. The plan status
"🟡 Implemented, device validation open" is therefore honest for M9: the
flavour is built, not yet validated on hardware.

**Follow-up (open, because …):**

1. **Run the whole device test plan on a Fire TV Stick** (hardware H2) and fill
   in the Fire TV column of the result sheet. Reason: no hardware was available;
   this is the only way to close M9.
2. **Build and weigh the release APKs** for both flavours to close AC-M9.4.
   Reason: only the debug APKs were built in this cycle, and the release script
   needs a keystore (risk R-10).
3. **Make the lint green, then run `./gradlew :app:lintFiretvDebug`** and check
   for `NewApi` findings. Reason: the lint was run for the `googletv` variant
   and failed with one finding in the shared manifest; the `firetv` variant was
   never analysed, so AC-M9.2 is still open.
4. **Repeat the Play Services check on the Fire TV *release* APK.** The check
   was performed in this cycle on the built debug APK and returned 0 matches
   (section 2.2); AC-M7.2 names the release APK, which was not built. Reason:
   the release APK is a different artefact.

---

## 10. Release gate

| # | Gate | Result |
|---|---|---|
| 1 | `./gradlew :test-runner:test` is green | **yes** — measured on `51933eb`: 457 tests, 0 failures, 0 errors, 0 skipped; the Fire TV source set is part of the compiled module |
| 2 | Lint and both debug APKs are green | **partly** — both debug APKs build (measured); the lint **fails** with 1 error (`UnusedAttribute`, `app/src/main/AndroidManifest.xml:58`) |
| 3 | **No known S4 finding** | **yes** — the Fire TV surface has no S4 finding; it has fewer attack surface than the Google TV flavour |
| 4 | Every open S3 has a written owner decision | **yes** — the Miracast residual risk is written down in the audit §7 |
| 5 | The device scenarios ran on both flavours | **no** — the Fire TV column of the result sheet is empty |
| 6 | The file-size rule holds for the touched files | **yes** — the Fire TV flavour file is 40 lines |
| 7 | The documentation is up to date (same change) | **yes** — `docs/guides/INSTALLATION.md` describes the two flavours and the Cast limitation |
| 8 | The limitations in `README.md` are true of this build | **yes** — the README states that Cast is unavailable on Fire TV |
