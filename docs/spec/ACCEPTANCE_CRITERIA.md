# PhairPlay – Acceptance Criteria

Version: 3.0
Status: Active
Date: 2026-11-05

This document defines **measurable, verifiable acceptance criteria** for each milestone. A milestone is considered **DONE** only when ALL its criteria pass on ALL target devices (unless the milestone is device-specific).

> **Change log v3.0 (2026-11-05).** The criteria were checked against the
> requirement baseline v4.0 and against the code on `main` @ `34a166c`. Three
> things changed:
>
> 1. **§0.8 — the contradiction register.** Eleven places where a criterion, the
>    requirement it belongs to and the code say different things. Every one of
>    them was measured, not estimated. The full list with the evidence is in
>    §0.8; the measures are in
>    [`REMEDIATION_BACKLOG.md`](REMEDIATION_BACKLOG.md).
> 2. **The legacy rounds were corrected where they name a test that does not
>    exist.** Eight criteria pointed at a method name (`testSdpParsing()` and
>    seven others) that was never written — the Kotlin tests carry backtick
>    names. The criteria now name the test that actually exists.
> 3. **A new round AR-V4** carries the criteria of the baseline v4.0 — the
>    requirements that are new, that are sharpened, and that are open.
>
> **The ✅ of the legacy rounds is not evidence.** The rounds AR-1 … AR-8 were
> closed before the device test plan existed; their "Definition of Done" says
> "all criteria pass", but the device criteria were never executed — the
> project plan records **0 of 16 device test cases executed**. A ✅ in a legacy
> round therefore means *the automated part passed*, never *the criterion was
> proven on a television*.

---

## 0. Numbering, identifiers and the milestone mapping

### 0.1 Two different numberings — and how they are kept apart

Until v1.0 this document and `PROJECT_PLAN.md` used the word "milestone" for two
different things, and the two numberings collided: `PROJECT_PLAN.md` numbers the
**phases of the project** (`M0` … `M12`), while this document numbered its own
sections 0 … 8 — against an **older, nine-milestone plan without Miracast, Cast
and DLNA**. So "Milestone 6" here meant *Stability & Reconnect* while "M6" in the
project plan meant *Miracast*.

**Resolution (v2.0).** A **milestone `M<n>`** is a phase of the project and
belongs to `PROJECT_PLAN.md`; an **acceptance round `AR-<n>`** is a section of
this document and may cover parts of several milestones.

### 0.2 Identifier scheme

| Pattern | Meaning | Status |
|---|---|---|
| `AC-<n>.<m>` | Criterion `m` of the **legacy acceptance round** `n` (AR-0 … AR-8). The identifier is bound to the round, **not** to a milestone. | Unchanged — existing identifiers keep their meaning so that old references stay valid. |
| `AC-M<n>.<m>` | Criterion `m` of **milestone `M<n>`** of the project plan. | New (v2.0) — used for every criterion added for M6 … M12. |

### 0.3 The mapping: milestone ↔ acceptance round ↔ criteria

| Milestone (`PROJECT_PLAN.md`) | Acceptance round | Criterion IDs | Binding |
|---|---|---|---|
| M0 | AR-0 Specification complete | `AC-0.x` | legacy — archived |
| M1 | AR-1 App starts | `AC-1.x` | legacy — archived |
| M2 | AR-2 Network visibility (mDNS) | `AC-2.x` | legacy — archived |
| M3 | AR-3 Connection & handshake (RTSP) | `AC-3.x` | legacy — archived |
| M4 | AR-4 Video streaming | `AC-4.x` | legacy — archived |
| M5 | AR-5 Audio streaming | `AC-5.x` | legacy — archived |
| M6 | — | **`AC-M6.x`** | **binding** |
| M7 | — | **`AC-M7.x`** | **binding** |
| M8 | AR-6 Stability & reconnect | `AC-6.x` (legacy) + **`AC-M8.x`** | **binding** |
| M9 | AR-7 Fire TV validation | `AC-7.x` (legacy) + **`AC-M9.x`** | **binding** |
| M10 | — | **`AC-M10.x`** | **binding** |
| M11 | AR-8 Release | `AC-8.x` (legacy) + **`AC-M11.x`** | **binding** |
| M12 | — | **`AC-M12.x`** | **binding** |
| — | **AR-V4 Baseline v4.0 compliance** | **`AC-V4.x`** | **binding** |

> **Correction proposal for the project track.** `PROJECT_PLAN.md` v2.1 writes
> *"Definition of Done: AC-6.x"* under **Phase 6 – Miracast**, *"AC-7.x"* under
> **Phase 7 – Google Cast** and *"AC-8.x"* under **Phase 8 – Stability**. Under the
> mapping above those references point at the legacy rounds AR-6 (Stability),
> AR-7 (Fire TV) and AR-8 (Release) — they are off by two to three milestones.
> The project track should replace them with `AC-M6.x`, `AC-M7.x` and `AC-M8.x`.
> This file is owned by the product track, so the correction is proposed here and
> not applied.

### 0.5 Reading the legacy rounds

The rounds AR-0 … AR-8 belong to milestones that were closed before v2.0; their
identifiers keep their meaning, so that a historical result stays reproducible.
Two of them are soft by today's standard (AC-4.1 "correct aspect ratio", AC-5.4
"Audio quality is clean"). For every future verification the binding criteria
apply: AC-M8.x replaces AR-6, AC-M9.x replaces AR-7 and AC-M11.x replaces AR-8.

### 0.6 Rules for every criterion

* A criterion is only satisfied when its **Pass Condition** is met exactly.
* "Manual on device" is only allowed together with an exact test script.
* A criterion that cannot be executed in CI must name the command that the team
  lead runs and the artefact that proves the result.
* If a criterion is not met, it is reported as **not met** with a reason — never
  as "partly met".
* A criterion that needs real hardware names a **device procedure** `DT-xx`.

### 0.7 The device procedures (`DT-xx`)

A criterion that cannot be executed on a build server is written as a **device
procedure** with the identifier `DT-xx`. The procedures are defined in two
places:

| Range | Where | Owner |
|---|---|---|
| `DT-01` … `DT-16` | [`../guides/DEVICE_TEST_PLAN.md`](../guides/DEVICE_TEST_PLAN.md) | QA track |
| `DT-17` … `DT-19` | [`DEVICE_PROCEDURES.md`](DEVICE_PROCEDURES.md) | product track |

**A `DT-xx` that nobody defined is not a proof.** The generator of the
traceability matrix checks every `DT-xx` that a requirement names against both
files and fails when it finds one that is not defined — that is the red case of
`scripts/test-build-traceability.sh`.

The three procedures of the v4.0 baseline:

| Procedure | Proves | State on 2026-11-05 |
|---|---|---|
| **DT-17** | the cleartext configuration of the LAN (NFR-28) | **fails** — the configuration does not exist |
| **DT-18** | the log of a release build (NFR-31) | **fails** — a release build writes nothing |
| **DT-19** | the store installation and the in-app policy (FR-72, FR-73, NFR-21) | not executed — no internal track yet |

### 0.8 The contradiction register

Every row was measured on `main` @ `34a166c`. "Open" means the contradiction is
still in the tree.

| # | Where | The criterion / the requirement says | The code says | State |
|---|---|---|---|---|
| C-01 | AC-0.5 | the four documents are committed to the branch `claude/setup-phairplay-project-8cpcN` | that branch does not exist; the tree is on `main` (`git branch -a`) | **open** — the criterion is unsatisfiable as written |
| C-02 | AC-1.7, AC-8.6 | `./gradlew assembleDebug` and `./gradlew build` | the project has two flavours; CI runs `:app:lintGoogletvDebug`, `:app:lintFiretvDebug` and `:test-runner:test` (`.github/workflows/ci.yml`) | **open** — the command does not build what the criterion claims |
| C-03 | AC-1.8, AC-8.1 | `./gradlew test` proves the suite | the suite of the app runs as `:test-runner:test`; `./gradlew test` does not run it | **open** — the proof command is wrong |
| C-04 | AR-1 … AR-8 | "Definition of Done: all criteria pass ✅" | the device criteria were never executed — 0 of 16 device test cases (`PROJECT_PLAN.md`, Phase 11) | **open** — the ✅ is not evidence |
| C-05 | AC-2.1 | the device appears in the macOS picker within **3 s** | FR-06 says **2 s** | **open** — two thresholds for one fact |
| C-06 | AC-2.6, AC-3.5, AC-3.7, AC-3.8, AC-4.8, AC-4.9, AC-5.6, AC-5.7 | the proof is a method `testSdpParsing()`, `testOversizedMessageRejected()`, `testTxtRecordContents()`, `testMediaCodecInit()`, `testNalUnitExtraction()`, `testDecryption()`, `testAacFrameExtraction()`, `testMalformedMessageHandled()` | **none of the eight method names exists** — the Kotlin tests carry backtick names | **corrected in v3.0** — the rows now name the test that exists |
| C-07 | AC-M6.6 | the receiver reports `ERROR` and the home screen never shows "Advertising" | `ReceiverSupervisor.kt:175` sets `ProtocolState.ADVERTISING` before the receiver can report the error | **open** — the unit test passes, the device does not |
| C-08 | AC-M10.3 | the four `protocol_*` values are equal in every locale | `scripts/check-i18n-completeness.sh` compares the key set and the placeholders — **not** the values | **open** — the criterion has no automatic proof |
| C-09 | AC-M12.3 | the proof is the test `DlnaControlPointProbeTest` | that test does not exist in the tree (`find . -name "DlnaControlPointProbeTest*"` → 0) | **open** — the criterion names a plan, not a fact |
| C-10 | AC-M12.15 | the renderer refuses a `file://` URI with the fault `402` | **closed on 2026-11-05**: `DlnaMediaUri` delegates to `FetchableUrlPolicy`, and `DlnaMediaUriTest` asserts "rejects a file URI because it would read the local file system" | ✅ **closed** (commits `b4e5afa`, `55acde4`) |
| C-11 | AC-M11.6 | every finding of the security audit is fixed or documented as open | 8 of the 14 findings are still open: SEC-04, SEC-05, SEC-06, SEC-07, SEC-08, SEC-09, SEC-10, SEC-11 | **open** — the criterion is met literally, but the security state is not "green" |

---

## Acceptance Round AR-0 … AR-8 (legacy) — archived

The rounds **AR-0 … AR-8** are the acceptance rounds of the milestones that
were closed before the device test plan existed. Their identifiers keep their
meaning — `AC-<n>.<m>` is bound to the round, not to a milestone — and the
mapping in §0.3 above still points at them.

Their **text** is archived unchanged:
[`archive/ACCEPTANCE_CRITERIA-legacy-rounds.md`](archive/ACCEPTANCE_CRITERIA-legacy-rounds.md).

**Three things about them must be known before a criterion of a legacy round is
used as a proof:**

1. **The ✅ of a legacy round is not evidence.** "Definition of Done: all
   criteria pass ✅" means *the automated part passed*. The device criteria
   were never executed — the project plan records **0 of 16** device test cases.
2. **Eight rows named a test that does not exist** (`testSdpParsing()` and
   seven others). Those rows were **corrected in v3.0**; the correction is
   written down in the table "The corrected legacy criteria" below.
3. **Three commands are wrong**: AC-0.5 names a branch that does not exist,
   and AC-1.7 / AC-1.8 / AC-8.1 / AC-8.6 name Gradle commands that do not run
   what the criterion claims. The commands that run today are
   `./gradlew :test-runner:test` and `./gradlew :app:lintGoogletvDebug
   :app:lintFiretvDebug`.

### 0.9 The corrected legacy criteria

The eight rows of C-06 named a test method that was never written. The
Kotlin tests carry backtick names — the criterion has to name the test that
actually exists.

| Criterion | The proof named before | The proof that exists |
|---|---|---|
| AC-2.6 | `MdnsServiceTest.testTxtRecordContents()` | `MdnsServiceNameTest` (9 cases) + `MdnsServiceTest` — "start registers two mDNS services" |
| AC-3.5 | `RtspHandlerHandshakeTest.testSdpParsing()` | `SdpParserTest` (21 cases) — "video+audio SDP sets hasVideo=true and hasAudio=true" |
| AC-3.7 | `RtspHandlerHandshakeTest.testOversizedMessageRejected()` | `RtspHandlerHandshakeTest` — "ANNOUNCE with empty body returns 400" + `RtspRequestReader` budget |
| AC-3.8 | `RtspHandlerHandshakeTest.testMalformedMessageHandled()` | `RtspHandlerHandshakeTest` — "unknown RTSP method returns 501" + "ANNOUNCE with blank body returns 400" |
| AC-4.8 | `VideoDecoderTest.testMediaCodecInit()` | `VideoDecoderTest` — "VideoDecoder is created successfully" |
| AC-4.9 | `VideoDecoderTest.testNalUnitExtraction()` | `RtpFuaReassemblyTest` (8 cases) + `RtpInterleavedTest` (10 cases) |
| AC-5.6 | `AudioPlayerTest.testDecryption()` | `AudioPlayerEncryptionTest` (12 cases) — "isAudioEncrypted is true when both key and IV are exactly 16 bytes" |
| AC-5.7 | `AudioPlayerTest.testAacFrameExtraction()` | `SdpParserTest` — "AAC-ELD audio codec is detected" + `AudioStreamServerTest` (3 cases) |

The **binding** criteria for the milestones that are still open are the rounds
AR-M6 … AR-M12 below and the round AR-V4 at the end of this document.

## Milestone M6 – Miracast receiver (control plane, honest limits)

**Goal:** PhairPlay advertises itself as a WFD sink using the public Android APIs, and it says
honestly where the platform allows nothing more. **No Miracast media is rendered** — the project
contains neither an MPEG-TS demuxer nor a WFD decoder (FR-58 … FR-60).

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-M6.1 | The Miracast receiver uses no hidden or system Wi-Fi Display API | `grep -rn "setWfdInfo\|CONFIGURE_WIFI_DISPLAY" app/src/main` | 0 matches |
| AC-M6.2 | The Wi-Fi Direct service is advertised as `_wfd._tcp` with the sink TXT records `wfd_device_type=primary_sink`, `wfd_session_available=1`, `wfd_rtsp_port=7236`, `wfd_video_formats=h264-chp,h264-cbp`, `wfd_audio_codecs=lpcm` | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.miracast.MiracastReceiverTest"` — the TXT map must be reachable from the test (extract it from `registerP2pService()` into a companion constant) | The test asserts the five values and the service type; it passes |
| AC-M6.3 | The WFD RTSP control plane answers `OPTIONS`, `GET_PARAMETER`, `SET_PARAMETER`, `SETUP`, `PLAY`, `PAUSE`, `TEARDOWN` with `200 OK` and every other method with `501 Not Implemented` | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.miracast.MiracastReceiverTest"` | All tests pass; one test asserts `501` for an unknown method |
| AC-M6.4 | `GET_PARAMETER` returns the sink capability set | same as AC-M6.3 | The response body contains `wfd_video_formats`, `wfd_audio_codecs`, `wfd_client_rtp_ports` and `wfd_content_protection` |
| AC-M6.5 | A second concurrent WFD client is rejected | New test in `MiracastReceiverTest` that opens two real sockets against `WfdRtspServer` | The second client receives `RTSP/1.0 503 Service Unavailable` |
| AC-M6.6 | When `WifiP2pManager` is unavailable or the runtime permission is missing, the receiver reports `ERROR` and the Home screen never shows "Advertising" | Existing unit test `start emits error when WifiP2pManager is unavailable` + instrumented test on the device | The unit test passes; on the device the Miracast card shows the error detail text and never the advertising text while the permission is missing |
| AC-M6.7 | The app never requests a location permission at runtime | `grep -rn "requestPermissions" app/src/main` | The only occurrence is the `POST_NOTIFICATIONS` request in `MainActivity`; no location permission is requested |
| AC-M6.8 | The documentation states that Miracast covers the discovery and control plane only and that no Miracast media is rendered | `grep -rn "MiracastDecoder\|MpegTs\|TsDemux" app/src/main` and `grep -n "control plane" README.md` | 0 matches in the sources (no decoder exists) and the README carries the statement |

**Definition of Done:** All 8 criteria pass. A Miracast sender that does not find PhairPlay is **not**
a failure of these criteria — the criteria describe what PhairPlay itself does.

---

## Milestone M7 – Google Cast receiver (graceful degradation)

**Goal:** Cast never lies. It either works (registered application ID + Google Play Services) or it
states why it does not (FR-69, FR-70).

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-M7.1 | The `firetv` flavour contains no reference to the Google Cast SDK | `grep -rn "com.google.android.gms" app/src/firetv` | 0 matches |
| AC-M7.2 | The `firetv` APK contains no Play Services classes | `unzip -l app/build/outputs/apk/firetv/release/app-firetv-release.apk` and search the listing for `com/google/android/gms` | 0 entries |
| AC-M7.3 | On the `firetv` flavour the Cast receiver reports `DISABLED` and never initialises the Cast SDK | `./gradlew :app:testFiretvDebugUnitTest --tests "com.phairplay.cast.CastReceiverTest"` | The flavour-specific test passes and asserts `DISABLED` |
| AC-M7.4 | On the `googletv` flavour without a registered application ID the Cast receiver reports `ERROR` and does not start the SDK | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.cast.CastReceiverTest"` | The test passes; `isConfigured("")` and `isConfigured("TODO_REGISTER_YOUR_CAST_APP_ID")` are `false` |
| AC-M7.5 | `BuildConfig.CAST_APP_ID` is empty unless the build is given `-Pphairplay.castAppId=…` | `grep -n "phairplay.castAppId" app/build.gradle.kts`; build without the property and read the generated `BuildConfig.java` | The property defaults to `""`; the generated constant is `""` |
| AC-M7.6 | The documentation states that Cast needs a registered application ID and that PhairPlay renders no Cast media itself | `grep -n "CAST_APP_ID.md" README.md` | The README links the guide and states the limitation |

**Definition of Done:** All 6 criteria pass. A missing Cast application ID is **not** a failure of
these criteria — it is the documented, tested behaviour.

---

## Milestone M8 – Stability, reconnect and resource budgets

**Goal:** A 30-minute session stays stable, the receiver comes back after a network outage on its
own, and no single protocol can exhaust the memory of the television (FR-61 … FR-64).

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-M8.1 | A 30-minute continuous AirPlay mirroring session runs without a crash | Device script: start the service, mirror from a Mac for 30 minutes, then `adb logcat -d -s AndroidRuntime:E` | 0 `FATAL` entries; the session is still active at minute 30 |
| AC-M8.2 | The peak resident memory stays below the budget | `adb shell dumpsys meminfo com.phairplay.googletv` every 5 minutes during the session of AC-M8.1 | ≤ 150 MB at every measurement |
| AC-M8.3 | The average CPU load stays below the budget | `adb shell top -p $(adb shell pidof com.phairplay.googletv)` sampled every 60 s during AC-M8.1 | The mean of the samples is ≤ 30 % |
| AC-M8.4 | The mirroring frame queue never holds more than 90 items and never more than 32 MB | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.airplay.handshake.MirrorFrameQueueTest"` | All tests pass; the byte-budget test offers 100 × 1 MB frames and asserts `byteSize ≤ 32 MB` and `size ≤ 90` |
| AC-M8.5 | After the television loses and regains its network, the AirPlay receiver re-advertises within 5 s without an app restart | Device script: `adb shell svc wifi disable`, wait 15 s, `adb shell svc wifi enable`, then watch the macOS AirPlay picker | The device is visible in the picker ≤ 5 s after the network is back |
| AC-M8.6 | After the same outage **every enabled receiver** re-advertises — AirPlay *and* UPnP/DLNA | Same script as AC-M8.5, plus the M-SEARCH probe of AC-M12.3 from a PC in the same LAN | The AirPlay picker shows the device ≤ 5 s **and** the probe receives three `200 OK` answers ≤ 5 s after the network is back |
| AC-M8.7 | A failed advertisement is retried with a bounded backoff and ends in an explicit error state | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.airplay.MdnsRegistrationTrackerTest" --tests "com.phairplay.airplay.MdnsServiceTest"` | All tests pass |
| AC-M8.8 | Stopping the service releases every network port; starting it re-binds them within 3 s | Device script: `adb shell ss -ltn` before and after Stop / Start in the app | No listener on 7000 (AirPlay RTSP) or 1900 (SSDP) after Stop; both are bound again ≤ 3 s after Start |

**Definition of Done:** All 8 criteria pass on Google TV; AC-M8.1 … AC-M8.4 and AC-M8.8 also pass on
Fire TV (see AC-M9.3).

---

## Milestone M9 – Fire TV port

**Goal:** Everything that works on Google TV also works on Fire TV — with one documented exception:
there is no Google Cast on Fire TV (FR-69).

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-M9.1 | The `firetv` flavour builds | `./gradlew :app:assembleFiretvDebug` | Exit code 0; `app/build/outputs/apk/firetv/debug/app-firetv-debug.apk` exists |
| AC-M9.2 | The `firetv` flavour uses no API above API 25 without a runtime check | `./gradlew :app:lintFiretvDebug` | 0 `NewApi` findings |
| AC-M9.3 | The AirPlay criteria of AR-3, AR-4 and AR-5 pass on Fire TV hardware | Device script per criterion, on a Fire TV Stick | All of them pass |
| AC-M9.4 | The Fire TV release APK stays below the size budget | `ls -la app/build/outputs/apk/firetv/release/app-firetv-release.apk` | ≤ 10 MB |
| AC-M9.5 | The UPnP/DLNA renderer is discoverable and controllable on Fire TV | The M-SEARCH probe of AC-M12.3, run from a PC in the same LAN against the Fire TV | Three `200 OK` answers; `description.xml` reachable; `GetProtocolInfo` answers HTTP 200 |
| AC-M9.6 | The Cast card on Fire TV shows "disabled" and names Google Play Services as the reason | Device: open the app on the Fire TV | The card shows the disabled state and the reason text mentions Google Play Services |

**Definition of Done:** All 6 criteria pass.

---

## Milestone M10 – Internationalization

**Goal:** The app speaks the language of the television, and adding a language never touches Kotlin
or a layout file (FR-67, FR-68).

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-M10.1 | No locale file defines a key that the base locale does not define | CI script that compares the `name=` attributes of `app/src/main/res/values/strings.xml` and of every `app/src/main/res/values-*/strings.xml` | 0 keys that exist only in a locale file |
| AC-M10.2 | Every shipped locale translates the protocol state texts | same script, restricted to the keys `protocol_state_*` and `protocol_detail_*` | Every locale file defines all `protocol_state_*` keys |
| AC-M10.3 | The protocol names are identical in every locale | same script, restricted to the keys `protocol_airplay`, `protocol_miracast`, `protocol_cast`, `protocol_dlna` | The four values are equal in all locale files |
| AC-M10.4 | No user-visible text is hardcoded in Kotlin or in a layout | `./gradlew :app:lintGoogletvDebug` (rule `HardcodedText`) and `grep -rn "setText(\"" app/src/main` | 0 lint findings and 0 grep matches |
| AC-M10.5 | With the device language set to German, the app shows German texts | Device script: Settings → System → Languages → Deutsch, restart the app | Home screen, Settings, notification and the protocol cards are in German |
| AC-M10.6 | With the device language set to French, the app shows the French texts for the keys that the French file defines | Device script: Settings → System → Languages → Français, restart the app | No French key falls back to English; the app does not crash on a missing key |

**Definition of Done:** All 6 criteria pass.

---

## Milestone M11 – Release

**Goal:** The project can be released as a signed beta: green build, green tests, consistent
documentation, no unexplained open security finding.

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-M11.1 | All unit tests of both flavours pass | `./gradlew test` | Exit code 0; 0 failures |
| AC-M11.2 | Lint is clean for both flavours | `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug` | Exit code 0; 0 findings (warnings count as errors) |
| AC-M11.3 | Signed release APKs are produced for both flavours | `KEYSTORE_PATH=… KEYSTORE_PASSWORD=… KEY_ALIAS=… KEY_PASSWORD=… ./gradlew :app:assembleGoogletvRelease :app:assembleFiretvRelease`, then `apksigner verify` on both APKs | Both APKs exist and `apksigner verify` reports "Verified" |
| AC-M11.4 | `CHANGELOG.md` contains the entry of the released version, including the DLNA section and the documented limitations | Manual review of `CHANGELOG.md` | The entry names the version and the date and lists DLNA, Miracast and Cast with their real state |
| AC-M11.5 | `README.md`, `SECURITY.md` and `PRIVACY.md` describe exactly the protocols that are shipped | `grep -n "DLNA\|Miracast\|Cast" README.md SECURITY.md PRIVACY.md` + review | No document claims a protocol that is not implemented; no document denies an implemented protocol |
| AC-M11.6 | Every finding of the security audit is either fixed or documented as open with a reason | Review of `docs/audit/2026-10-24-security-audit.md` against the code | 0 findings that are open **and** unexplained |
| AC-M11.7 | The version name in the code equals the released tag | `grep -n "versionName" app/build.gradle.kts` compared with the tag | The two values are equal |
| AC-M11.8 | The DLNA pull semantics are documented for the user | `grep -n "file:///" README.md` | The README states that only `http`/`https` URLs can be played and that a `file:///` URI points at the sender's disk (FR-57) |

**Definition of Done:** All 8 criteria pass.

---

## Milestone M12 – UPnP / DLNA MediaRenderer

**Goal:** The television appears as a DLNA MediaRenderer in Windows "Cast to Device", VLC and mobile
UPnP control points; the control plane, the eventing and the hardening behave exactly as specified
(FR-44 … FR-57).

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-M12.1 | An `M-SEARCH` with `ST: ssdp:all` is answered with exactly three `200 OK` datagrams — `upnp:rootdevice`, the MediaRenderer device type and the UDN — each carrying `LOCATION`, `USN`, `ST` and `EXT` | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.SsdpAdvertiserLiveTest" --tests "com.phairplay.dlna.SsdpResponderTest"` | All tests pass; the live test asserts 3 answers with the three expected `ST` values and the four headers |
| AC-M12.2 | An `M-SEARCH` with the MediaRenderer device type is answered with exactly one datagram | same as AC-M12.1 | The live test asserts exactly 1 answer |
| AC-M12.3 | **Discovery, automatically verifiable (control-point probe):** a probe that performs the three steps of a real control point — (1) `M-SEARCH` to `239.255.255.250:1900`, (2) `GET` of the advertised `LOCATION` URL, (3) `POST` of the SOAP action `GetProtocolInfo` — receives a coherent answer from the running renderer | New test `DlnaControlPointProbeTest` in `app/src/test/kotlin/com/phairplay/dlna/`; run `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.DlnaControlPointProbeTest"` | The probe receives ≥ 3 search answers; the fetched `description.xml` contains `urn:schemas-upnp-org:device:MediaRenderer:1` and the three `<controlURL>` entries; the SOAP answer is HTTP 200 with a non-empty `<Source>` |
| AC-M12.4 | **Discovery on the device (VLC):** the television appears in the renderer list of VLC under the configured device name | Device script: (1) start PhairPlay on the TV and wait until the DLNA card reads "Advertising"; (2) on a PC in the same LAN open the renderer list of VLC 3.x — the path differs per version: the toolbar button *Cast* → tab *Renderer*, or the menu *Playback → Renderer*; (3) read the list | The entry appears within 10 s and shows the name configured in Settings; the name is identical to the entry in the AirPlay picker |
| AC-M12.5 | The device description and the three SCPD documents are served under the advertised `LOCATION` | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.DlnaDescriptionServerTest" --tests "com.phairplay.dlna.DlnaDescriptionBuilderTest"` | All tests pass; `description.xml` contains the friendly name, the UDN, the device type and the three services; every SCPD contains its action names |
| AC-M12.6 | The control plane answers the three services; an unknown action is answered with HTTP 500 and the SOAP fault `401`, an invalid argument with `402` | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.DlnaSoapDispatcherTest"` | All tests pass; one test asserts `500` + `401` for an unknown action, one asserts `500` + `402` for an invalid argument |
| AC-M12.7 | The AVTransport state machine transitions `STOPPED → PLAYING → PAUSED_PLAYBACK → STOPPED` and reports durations in the UPnP format `H:MM:SS(.mmm)` | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.DlnaSoapDispatcherTest" --tests "com.phairplay.dlna.DlnaTimeFormatTest"` | All tests pass |
| AC-M12.8 | The TV remote moves the same transport state machine, so the control point sees the new state | Unit test: `DlnaSoapDispatcher.onRemoteCommand("play-pause")` followed by `GetTransportInfo` → `PAUSED_PLAYBACK`. Device script: during a DLNA session press OK on the TV remote; the progress bar in VLC pauses | The unit test passes; on the device the control point reflects the pause within 1 s |
| AC-M12.9 | GENA: a `SUBSCRIBE` with a permitted callback is answered with `SID`, `TIMEOUT` and `EXT`; an `UNSUBSCRIBE` removes the subscription | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.DlnaDescriptionServerTest" --tests "com.phairplay.dlna.GenaEventManagerTest"` | All tests pass |
| AC-M12.10 | A `LastChange` event is delivered when the transport state changes and carries the nested UPnP format | same as AC-M12.9 | The test asserts that the notified document contains `LastChange` and the new `TransportState` |
| AC-M12.11 | A GENA callback outside the permitted set is refused with `412` — loopback, `169.254.169.254`, a public address, a host name, the decimal / octal / hexadecimal notation and every IPv6 form | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.GenaCallbackPolicyTest"` | All tests pass; at least one case exists per rejected notation |
| AC-M12.12 | The control plane refuses an XXE payload without leaking file content | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.DlnaSoapDispatcherSecurityTest" --tests "com.phairplay.dlna.SecureSoapXmlTest"` | All tests pass; the answer is HTTP 400 and does not contain `root:` |
| AC-M12.13 | Over-sized requests are refused with `414` / `431` / `413`; a client beyond the worker limit receives `503` | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.DlnaDescriptionServerTest"` | All tests pass |
| AC-M12.14 | No answer contains a stack trace, an exception class name, a file path or a version string | same as AC-M12.13 | The existing test "error answers leak neither stack traces nor paths nor versions" passes |
| AC-M12.15 | The renderer refuses a media URI whose scheme is not `http`/`https` with the SOAP fault `402` and does not open it | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.DlnaSoapDispatcherTest"` — a new negative test with `file:///etc/passwd` | The new test passes: status code 500, body contains `402`; `file` is no longer in the accepted scheme list of `DlnaMediaUri` |
| AC-M12.16 | A photo payload above 20 MB is refused and a photo is downsampled to at most 1920 px on its longest edge | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.DlnaImageDecoderTest"` | All tests pass |
| AC-M12.17 | The DLNA switch in Settings starts and stops the renderer and the state is shown on the Home screen | Unit test `AppSettingsTest` / `SettingsRepository` + device script: Settings → "DLNA MediaRenderer" off and on while watching the DLNA card | The switch persists; the card follows the state; the SSDP advertisement disappears within 5 s of switching off |
| AC-M12.18 | Stopping the service closes the description server, drops the GENA subscriptions and sends the SSDP `byebye` | Device script: stop the service on the TV, then `adb shell ss -ltn` on the TV and run the M-SEARCH probe of AC-M12.3 from the PC | No listener on the description port; the probe receives no `200 OK` answer |
| AC-M12.19 | A media file that a control point announces over `http` is rendered on the television | Device script: (1) on the PC serve a directory with an MP4 over `python3 -m http.server 8080`; (2) in VLC open the renderer list, select the television, open the MP4; (3) watch the TV | The video is rendered on the TV within 5 s and the transport state in VLC reads "Playing" |
| AC-M12.20 | The pull semantics of a UPnP renderer are documented for the user | `grep -n "file:///" README.md` | The README contains the statement (identical to AC-M11.8) |

**Definition of Done:** All 20 criteria pass. AC-M12.4 and AC-M12.19 are the two criteria that need
real hardware and a second device; the other 18 are automated.

---

## Acceptance Round AR-V4 – Baseline v4.0 compliance

**Goal:** The requirement baseline of 2026-11-05 is complete, traceable and
honest. Every criterion below is **open** today unless its row says otherwise —
the state is measured, not estimated.

| # | Criterion | How to Verify | Pass Condition | State |
|---|---|---|---|---|
| AC-V4.1 | Every requirement of the baseline carries the eight attributes | `./scripts/build-traceability.sh` + a read of [`REQUIREMENTS_MATRIX.md`](REQUIREMENTS_MATRIX.md) | 108 requirement rows, each with identifier, description, rationale, source, acceptance criterion, owner, verification, deliverable and status | ✅ met — 108 rows, 0 missing |
| AC-V4.2 | Every requirement is classified as a binding external obligation, an internal norm or a recommendation | `grep -c "· \*\*\(INT\|EXT\|REC\)" docs/spec/REQUIREMENTS.md` | The count equals the number of requirements; the tag of every line is one of the three classes | ✅ met — 108 tags |
| AC-V4.3 | The traceability matrix is generated, not written by hand | `head -8 docs/spec/TRACEABILITY.md` | The file names `scripts/build-traceability.sh` as its generator and carries the note "do not edit by hand" | ✅ met |
| AC-V4.4 | No requirement of the matrix has no proof | `./scripts/build-traceability.sh --check` | Exit 0; the summary reports 0 rows without a proof | ✅ met — 58 automated, 25 device, 13 gate, 8 command, 4 excluded, 0 without |
| AC-V4.5 | The generator turns red when a requirement loses its proof | `./scripts/test-build-traceability.sh` | All 5 cases pass, including the two red cases (a deleted test class, an undefined `DT-xx`) | ✅ met — 5 of 5 |
| AC-V4.6 | The renderer refuses every media scheme except `http` and `https` (FR-50) | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.dlna.DlnaMediaUriTest"` | `file`, `smb` and `ftp` are refused; a `file:///` URI is answered with the fault `402`; the test "SetAVTransportURI accepts a file URI" is **gone** | ✅ **met** — closed on 2026-11-05 (commits `b4e5afa`, `55acde4`) |
| AC-V4.7 | The Miracast card never shows "Advertising" when the receiver cannot advertise (FR-59) | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.miracast.MiracastReceiverTest"` + the device procedure DT-06 | The state becomes `ERROR` and `ReceiverSupervisor` does not set `ADVERTISING` before the receiver reports | 🔴 **open** — `ReceiverSupervisor.kt:175` sets it (C-07, B-06) |
| AC-V4.8 | A release build writes warnings and errors to the log (NFR-31) | The device procedure **DT-18** | The log of a release APK contains a warning of the receiver and no debug message; the address is reduced | 🔴 **open** — no release tree exists (B-15) |
| AC-V4.9 | The network security configuration exists and permits cleartext only for the LAN (NFR-28) | The device procedure **DT-17** | `app/src/main/res/xml/network_security_config.xml` exists, names no public domain, and an `http://` media URL plays | 🔴 **open** — the file does not exist (B-13) |
| AC-V4.10 | The i18n gate compares the **values** of the protocol strings, not only the keys (FR-68) | `./scripts/check-i18n-completeness.sh` | The script fails when a `protocol_*` value differs between two locales | 🔴 **open** — the comparison is not implemented (C-08, B-08) |
| AC-V4.11 | The published build targets the API level the Play policy demands (NFR-21) | `grep -n "targetSdk" app/build.gradle.kts` and the console of the submission day | The target API level is at least the minimum the console shows on the day of the upload | 🟡 open — `targetSdk = 35` today; the obligation changes with the calendar |
| AC-V4.12 | The native libraries are aligned for a 16 KB page size (NFR-22) | `zipalign -c -P 16 -v 4 app-googletv-release.apk` | Every `.so` of the bundle is reported as aligned for 16 KB | 🟠 open — the NDK supports it, the shipped libraries are not verified (B-17) |
| AC-V4.13 | Every dependency version is pinned and the licence inventory exists (NFR-32) | `grep -nE '\+\|latest\|snapshot' gradle/libs.versions.toml` + a read of `NOTICE` | The grep returns 0 matches and `NOTICE` names every dependency with its licence | 🟠 open — the versions are pinned, the inventory is missing (B-18) |
| AC-V4.14 | The release is signed with the production key and the signature is verified (NFR-33) | `apksigner verify --print-certs app-googletv-release.apk` | The output says *Verified* and the fingerprint is the production one, not the alpha one | 🟠 open — no signed release was verified in this environment (B-19) |
| AC-V4.15 | The legacy criteria point at test methods that exist (C-06) | `grep -rn "testSdpParsing\|testTxtRecordContents\|testMediaCodecInit\|testNalUnitExtraction\|testDecryption\|testAacFrameExtraction\|testOversizedMessageRejected\|testMalformedMessageHandled" app/src/test` | The grep returns 0 matches — every criterion names a backtick test that exists | ✅ met — the eight rows were corrected in v3.0 |
| AC-V4.16 | The binary plist decoder refuses a payload above 1 MB by itself (FR-74) | `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.airplay.handshake.PlistCodecTest"` | `PlistCodec.decode` throws for a payload above 1 MB, even when the caller passes no budget | 🔴 **open** — no size guard exists (B-09) |

**Definition of Done:** All 16 criteria pass. AC-V4.1 … AC-V4.6, AC-V4.11 and
AC-V4.15 are met. The remaining nine are the open points of the remediation
backlog; each of them is a **finding**, not a task with an unknown state.
are met. The remaining eleven are the open points of the remediation backlog;
each of them is a **finding**, not a task with an unknown state.

---

## Performance Benchmark Summary

These benchmarks apply to all milestones from M4 onward:

| Metric | Minimum (Pass) | Target (Good) | Test Method |
|---|---|---|---|
| Video frame rate | ≥ 25 fps | ≥ 30 fps | On-screen frame counter |
| Video latency | ≤ 100 ms | ≤ 50 ms | Stopwatch comparison |
| Audio/Video sync | ≤ 40 ms | ≤ 20 ms | A/V sync test video |
| RAM usage (peak) | ≤ 150 MB | ≤ 100 MB | `dumpsys meminfo` |
| CPU usage (avg) | ≤ 30% | ≤ 20% | `top` command |
| Connection time | ≤ 5 s | ≤ 2 s | Stopwatch from click to video |
| mDNS visibility | ≤ 3 s | ≤ 1 s | Stopwatch from app launch |
| Re-advertisement after a network outage | ≤ 5 s | ≤ 2 s | Stopwatch from `onAvailable` (AC-M8.5, AC-M8.6) |
| SSDP answer latency | ≤ 1 s | ≤ 100 ms | Stopwatch from `M-SEARCH` to the first `200 OK` (AC-M12.1) |
| DLNA discovery in the control point list | ≤ 10 s | ≤ 3 s | Stopwatch from the renderer start to the entry in the VLC list (AC-M12.4) |
| Mirroring frame queue | ≤ 90 items **and** ≤ 32 MB | ≤ 45 items | `MirrorFrameQueueTest` (AC-M8.4) |
| DLNA photo payload | ≤ 20 MB | ≤ 8 MB | `DlnaImageDecoderTest` (AC-M12.16) |

> **Note (v2.0).** The benchmark table above is the *union* of the historical AR-1 … AR-8 rows and
> the rows added for M8 and M12. A row that only applies to one protocol says so; the AirPlay rows
> (frame rate, latency, A/V sync) apply to the AirPlay mirroring path only — the UPnP/DLNA path
> fetches a finished media file and therefore has no frame-rate or latency requirement of its own.
