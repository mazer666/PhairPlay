# PhairPlay – Acceptance Criteria

Version: 2.0
Status: Active
Date: 2026-10-24

This document defines **measurable, verifiable acceptance criteria** for each milestone. A milestone is considered **DONE** only when ALL its criteria pass on ALL target devices (unless the milestone is device-specific).

---

## 0. Numbering, identifiers and the milestone mapping

### 0.1 Two different numberings — and how they are kept apart

Until v1.0 this document and `PROJECT_PLAN.md` used the word "milestone" for two
different things, and the two numberings collided:

* **`PROJECT_PLAN.md`** numbers the **phases of the project**: `M0` … `M12`
  (M6 = Miracast, M7 = Cast, M8 = Stability, M9 = Fire TV, M10 = i18n,
  M11 = Release, M12 = DLNA).
* This document numbered its own sections 0 … 8. Those sections were written
  against an **older, nine-milestone plan without Miracast, Cast and DLNA**, so
  "Milestone 6" here meant *Stability & Reconnect* while "M6" in the project plan
  meant *Miracast*.

**Resolution (v2.0).** From now on:

| Term | Meaning | Owner document |
|---|---|---|
| **Milestone `M<n>`** | A phase of the project. This is the only meaning of the word "milestone". | `PROJECT_PLAN.md` |
| **Acceptance round `AR-<n>`** | A section of this document. An acceptance round may cover parts of several milestones. | this document |

### 0.2 Identifier scheme

| Pattern | Meaning | Status |
|---|---|---|
| `AC-<n>.<m>` | Criterion `m` of the **legacy acceptance round** `n` (AR-0 … AR-8). The identifier is bound to the round, **not** to a milestone. | Unchanged — existing identifiers keep their meaning so that old references stay valid. |
| `AC-M<n>.<m>` | Criterion `m` of **milestone `M<n>`** of the project plan. | New (v2.0) — used for every criterion added for M6 … M12. |

### 0.3 Mapping: acceptance round → milestone

| Acceptance round | Topic in this document | Criterion IDs | Covers milestone |
|---|---|---|---|
| AR-0 | Specification complete | `AC-0.x` | M0 |
| AR-1 | App starts | `AC-1.x` | M1 |
| AR-2 | Network visibility (mDNS) | `AC-2.x` | M2 |
| AR-3 | Connection & handshake (RTSP) | `AC-3.x` | M3 |
| AR-4 | Video streaming | `AC-4.x` | M4 |
| AR-5 | Audio streaming | `AC-5.x` | M5 |
| AR-6 | Stability & reconnect | `AC-6.x` | **M8** (legacy) |
| AR-7 | Fire TV validation | `AC-7.x` | **M9** (legacy) |
| AR-8 | Release | `AC-8.x` | **M11** (legacy) |

### 0.4 Mapping: milestone → acceptance criteria

| Milestone (`PROJECT_PLAN.md`) | Topic | Binding criteria |
|---|---|---|
| M0 | Specification complete | `AC-0.x` |
| M1 | Skeleton + UI | `AC-1.x` |
| M2 | mDNS discovery | `AC-2.x` |
| M3 | AirPlay handshake + photo | `AC-3.x` |
| M4 | AirPlay video | `AC-4.x` |
| M5 | AirPlay audio | `AC-5.x` |
| M6 | Miracast | **`AC-M6.x`** |
| M7 | Google Cast | **`AC-M7.x`** |
| M8 | Stability & reconnect | `AC-6.x` (legacy) + **`AC-M8.x`** (binding) |
| M9 | Fire TV port | `AC-7.x` (legacy) + **`AC-M9.x`** (binding) |
| M10 | i18n polish | **`AC-M10.x`** |
| M11 | Release | `AC-8.x` (legacy) + **`AC-M11.x`** (binding) |
| M12 | UPnP / DLNA MediaRenderer | **`AC-M12.x`** |

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
identifiers and their wording are **not** changed here, so that a historical
result stays reproducible. Two of them are soft by today's standard (AC-4.1
"correct aspect ratio", AC-5.4 "Audio quality is clean"). For every future
verification the binding criteria of the new rounds apply: AC-M8.x replaces
AR-6, AC-M9.x replaces AR-7 and AC-M11.x replaces AR-8.

### 0.6 Rules for every criterion

* A criterion is only satisfied when its **Pass Condition** is met exactly.
* "Manual on device" is only allowed together with an exact test script.
* A criterion that cannot be executed in CI must name the command that the team
  lead runs and the artefact that proves the result.
* If a criterion is not met, it is reported as **not met** with a reason — never
  as "partly met".

---

## Acceptance Round AR-0 – Specification Complete (M0)

**Goal:** All specification documents are present, complete, and consistent.

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-0.1 | `docs/spec/REQUIREMENTS.md` exists and is complete | `ls docs/spec/` + manual review | File present, all sections filled |
| AC-0.2 | `docs/spec/TECHNICAL_SPEC.md` exists and is complete | `ls docs/spec/` + manual review | File present, architecture diagram included |
| AC-0.3 | `docs/spec/ACCEPTANCE_CRITERIA.md` exists and is complete | `ls docs/spec/` + manual review | File present, all milestones covered |
| AC-0.4 | `docs/spec/PROJECT_PLAN.md` exists and is complete | `ls docs/spec/` + manual review | File present, all phases and DoD defined |
| AC-0.5 | All 4 documents are committed to the `claude/setup-phairplay-project-8cpcN` branch | `git log --oneline` | Commit containing all 4 files visible in log |

**Definition of Done:** All 5 criteria pass. ✅

---

## Acceptance Round AR-1 – App Starts (M1)

**Goal:** The app launches without crashing on both target platforms and shows the Waiting Screen.

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-1.1 | App installs via `adb install` on Google TV | `adb install app-googletv-debug.apk` | Exit code 0, "Success" |
| AC-1.2 | App installs via `adb install` on Fire TV | `adb install app-firetv-debug.apk` | Exit code 0, "Success" |
| AC-1.3 | App starts on Google TV without crashing | Launch app, observe for 30s | No crash dialog, no FATAL in logcat |
| AC-1.4 | App starts on Fire TV without crashing | Launch app, observe for 30s | No crash dialog, no FATAL in logcat |
| AC-1.5 | Waiting Screen displays the device name | Visual inspection | Device name matches name in system settings |
| AC-1.6 | Waiting Screen displays usage instructions | Visual inspection | "Open AirPlay on your Mac..." text visible |
| AC-1.7 | App builds cleanly for both flavors | `./gradlew assembleDebug` | Exit code 0, 0 errors, 0 warnings (treat warnings as errors) |
| AC-1.8 | All unit tests pass | `./gradlew test` | Exit code 0, 0 failures |

**Definition of Done:** All 8 criteria pass on both devices. ✅

---

## Acceptance Round AR-2 – Network Visibility (mDNS) (M2)

**Goal:** macOS discovers PhairPlay in the AirPlay picker within 3 seconds of app launch.

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-2.1 | PhairPlay appears in macOS AirPlay menu within 3 seconds | Start timer at app launch, watch macOS menu bar → AirPlay icon | Device name appears ≤ 3s after app launch |
| AC-2.2 | Device name in macOS picker matches TV device name | Compare macOS picker with device settings name | Names are identical |
| AC-2.3 | PhairPlay disappears from macOS picker within 10 seconds of app close | Close app, watch picker | Device removed from picker ≤ 10s after close |
| AC-2.4 | mDNS re-registers correctly after app returns from background | Background app 60s, then bring to foreground | Device reappears in macOS picker ≤ 5s |
| AC-2.5 | Unit test: `MdnsService` registers correct service types | `./gradlew test` | `MdnsServiceTest` — all tests pass |
| AC-2.6 | Unit test: TXT record contains required keys (deviceid, features, model) | `./gradlew test` | `MdnsServiceTest.testTxtRecordContents()` passes |
| AC-2.7 | No `W/NsdManager` or `E/NsdManager` errors in logcat | `adb logcat -s NsdManager` | 0 errors during normal operation |

**Definition of Done:** All 7 criteria pass. ✅

---

## Acceptance Round AR-3 – Connection & Handshake (RTSP) (M3)

**Goal:** AirPlay connection is established from macOS. The RTSP handshake completes without errors.

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-3.1 | macOS completes RTSP OPTIONS → SETUP → ANNOUNCE → RECORD sequence | PhairPlay logcat shows all 4 RTSP methods received | All 4 methods logged, all responded with `200 OK` |
| AC-3.2 | No RTSP error responses (4xx / 5xx) during normal handshake | Inspect logcat | 0 error responses for a standard connection attempt |
| AC-3.3 | A second connection attempt while streaming is rejected | Connect a second Mac while first is active | Second connection gets `503 Service Unavailable` |
| AC-3.4 | TEARDOWN cleans up all resources | Disconnect from macOS, check logcat | Resources freed, WaitingScreen shown, mDNS still active |
| AC-3.5 | SDP is correctly parsed (video codec, audio codec, keys extracted) | Unit test | `RtspHandlerTest.testSdpParsing()` passes |
| AC-3.6 | Unit test: RTSP method routing | Unit test | `RtspHandlerTest` — all handler method tests pass |
| AC-3.7 | RTSP oversized message (>64KB) is rejected without crash | Fuzzing test / unit test | `RtspHandlerTest.testOversizedMessageRejected()` passes |
| AC-3.8 | Malformed RTSP message does not crash the app | Unit test with invalid input | `RtspHandlerTest.testMalformedMessageHandled()` passes |

**Definition of Done:** All 8 criteria pass. ✅

---

## Acceptance Round AR-4 – Video Streaming (M4)

**Goal:** macOS Screen Mirroring video is decoded and displayed at ≥25fps with ≤100ms latency.

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-4.1 | Video is displayed full-screen after connection | Visual inspection | Video fills screen with correct aspect ratio |
| AC-4.2 | No black flashes or frozen frames during normal streaming | 5-minute streaming test | 0 visible glitches |
| AC-4.3 | Frame rate ≥ 25fps | Display frame counter overlay on macOS + count on TV | TV shows ≥ 25 rendered frames per second |
| AC-4.4 | Video latency ≤ 100ms | Display stopwatch on macOS, watch TV | Measured delta ≤ 100ms |
| AC-4.5 | Video uses hardware decoder | `adb shell dumpsys media.codec` | MediaCodec uses `OMX.` or `c2.` hardware component |
| AC-4.6 | App switches from WaitingScreen to StreamingScreen when video starts | Visual inspection | Transition happens within 1s of stream start |
| AC-4.7 | App returns to WaitingScreen when stream stops | Disconnect from macOS | WaitingScreen shown within 2s |
| AC-4.8 | Unit test: VideoDecoder initializes MediaCodec correctly | Unit test | `VideoDecoderTest.testMediaCodecInit()` passes |
| AC-4.9 | Unit test: NAL unit extraction from RTP payload | Unit test | `VideoDecoderTest.testNalUnitExtraction()` passes |

**Definition of Done:** All 9 criteria pass on Google TV. ✅

---

## Acceptance Round AR-5 – Audio Streaming (M5)

**Goal:** Audio plays in sync with video. No dropouts, no crackling.

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-5.1 | Audio is audible after connection | Play audio on macOS, listen on TV | Audio heard from TV speakers/HDMI |
| AC-5.2 | Audio/video sync ≤ 40ms drift | Play A/V sync test video (clapper board) | Visual drift ≤ 40ms |
| AC-5.3 | No audio dropouts during 5-minute stream | 5-minute streaming test | 0 audible dropouts |
| AC-5.4 | No audio crackling or distortion | Streaming test with music | Audio quality is clean |
| AC-5.5 | Audio continues after video pause/resume | Pause screen sharing briefly, resume | Audio resumes correctly |
| AC-5.6 | Unit test: AES-128-CTR decryption | Unit test with known test vector | `AudioPlayerTest.testDecryption()` passes |
| AC-5.7 | Unit test: AAC-ELD frame extraction from RTP | Unit test | `AudioPlayerTest.testAacFrameExtraction()` passes |

**Definition of Done:** All 7 criteria pass on Google TV. ✅

---

## Acceptance Round AR-6 – Stability & Reconnect (legacy, covers M8)

**Goal:** 30-minute continuous streaming without issues. Automatic reconnect after disruption.

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-6.1 | 30-minute continuous stream without crash | Automated timer + logcat monitor | 0 crashes, 0 FATAL in logcat |
| AC-6.2 | 30-minute continuous stream without unexpected disconnect | Monitor RTSP session | Session stays active for full 30 minutes |
| AC-6.3 | RAM usage stays ≤ 150 MB throughout the 30-minute test | `adb shell dumpsys meminfo com.phairplay.*` every 5 minutes | Peak ≤ 150 MB at every measurement |
| AC-6.4 | CPU usage stays ≤ 30% average throughout the 30-minute test | `adb shell top -p $(pidof com.phairplay.*)` | 5-minute rolling average ≤ 30% |
| AC-6.5 | Automatic reconnect after network disruption (Wi-Fi toggle) | Disable and re-enable Wi-Fi on sender Mac | PhairPlay reappears in macOS picker within 5s of reconnect |
| AC-6.6 | Automatic reconnect after sender disconnects | Disconnect AirPlay from macOS, wait 5s, reconnect | Reconnect completes without restarting the app |
| AC-6.7 | App survives Android "Don't Keep Activities" developer option | Enable option, background app, return | App still functional after returning from background |

**Definition of Done:** All 7 criteria pass on Google TV. ✅

---

## Acceptance Round AR-7 – Fire TV Validation (legacy, covers M9)

**Goal:** All previous milestones also pass on Fire TV.

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-7.1 | All AC-1.x criteria pass on Fire TV | See Milestone 1 | All pass |
| AC-7.2 | All AC-2.x criteria pass on Fire TV | See Milestone 2 | All pass |
| AC-7.3 | All AC-3.x criteria pass on Fire TV | See Milestone 3 | All pass |
| AC-7.4 | All AC-4.x criteria pass on Fire TV | See Milestone 4 | All pass |
| AC-7.5 | All AC-5.x criteria pass on Fire TV | See Milestone 5 | All pass |
| AC-7.6 | All AC-6.x criteria pass on Fire TV | See Milestone 6 | All pass |
| AC-7.7 | `firetv` flavor APK size ≤ 10 MB | `ls -la app-firetv-release.apk` | Size ≤ 10 MB |

**Definition of Done:** All 7 criteria pass on Fire TV. ✅

---

## Acceptance Round AR-8 – Release (legacy, covers M11)

**Goal:** Project is ready for public v1.0 release.

| # | Criterion | How to Verify | Pass Condition |
|---|---|---|---|
| AC-8.1 | All unit tests pass | `./gradlew test` | Exit code 0 |
| AC-8.2 | All instrumented tests pass on emulator (API 29) | `./gradlew connectedAndroidTest` | Exit code 0 |
| AC-8.3 | Release APK builds for `googletv` flavor | `./gradlew assembleGoogletvRelease` | Exit code 0, APK generated |
| AC-8.4 | Release APK builds for `firetv` flavor | `./gradlew assembleFiretvRelease` | Exit code 0, APK generated |
| AC-8.5 | CI pipeline (GitHub Actions) is green | GitHub Actions status | All workflow checks green |
| AC-8.6 | No Kotlin compiler errors or warnings | `./gradlew build` | 0 errors, 0 warnings |
| AC-8.7 | `README.md` contains installation instructions for both platforms | Manual review | Sideloading steps for Google TV and Fire TV present |
| AC-8.8 | `CHANGELOG.md` contains v1.0.0 entry | Manual review | Version entry with release date present |
| AC-8.9 | GitHub Release created with both APKs attached | GitHub Releases page | Release exists with 2 APK assets |
| AC-8.10 | No known CRITICAL or HIGH severity security issues | Manual security review | 0 critical/high issues |

**Definition of Done:** All 10 criteria pass. ✅

---

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
