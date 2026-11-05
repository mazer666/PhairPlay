# ARCHIVED — the legacy acceptance rounds AR-0 … AR-8

> **This document is an archive, not the specification.**
>
> * **Status:** superseded on 2026-11-05 by
>   [`../ACCEPTANCE_CRITERIA.md`](../ACCEPTANCE_CRITERIA.md) **v3.0**.
> * **Why it was moved:** the rounds AR-0 … AR-8 belong to the milestones that
>   were closed before the device test plan existed. Their identifiers stay
>   valid — `AC-<n>.<m>` is bound to the round, not to a milestone — and the
>   mapping in §0.3 / §0.4 of the current document still points at them. They
>   were moved out of the specification so that the **binding** criteria — the
>   rounds AR-M6 … AR-M12 and AR-V4 — stay readable.
> * **What is wrong with them** (measured on `main` @ `34a166c`, see the
>   contradiction register §0.8 of the current document):
>   1. Their "Definition of Done" says *all criteria pass* with a ✅, but the
>      device criteria were **never executed** — 0 of the 16 device test cases.
>      A ✅ in a legacy round means *the automated part passed*.
>   2. Eight of them name a test method (`testSdpParsing()` and seven others)
>      that **does not exist** — the Kotlin tests carry backtick names. Those
>      eight rows were **corrected** in v3.0; the text below is the unchanged
>      original, kept for the history.
>   3. AC-0.5 names a branch (`claude/setup-phairplay-project-8cpcN`) that does
>      not exist any more, and AC-1.7 / AC-1.8 / AC-8.1 / AC-8.6 name Gradle
>      commands that do not run what the criterion claims (`./gradlew test` does
>      not run the suite; the suite runs as `:test-runner:test`).
> * **How to use this file:** for the history of the acceptance practice. Not
>   for the current state. The criteria that bind today are in the current
>   document.

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
| AC-3.5 | SDP is correctly parsed (video codec, audio codec, keys extracted) | Unit test | `RtspHandlerHandshakeTest.testSdpParsing()` passes |
| AC-3.6 | Unit test: RTSP method routing | Unit test | `RtspHandlerHandshakeTest` + `RtspHandlerSessionTest` — all handler method tests pass |
| AC-3.7 | RTSP oversized message (>64KB) is rejected without crash | Fuzzing test / unit test | `RtspHandlerHandshakeTest.testOversizedMessageRejected()` passes |
| AC-3.8 | Malformed RTSP message does not crash the app | Unit test with invalid input | `RtspHandlerHandshakeTest.testMalformedMessageHandled()` passes |

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
