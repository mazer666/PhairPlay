# PhairPlay Testing Guide

This document explains how to run tests, what is tested, and how to perform manual testing on real devices.

---

## How to Run Tests

### Unit Tests (no device required)

Unit tests run on your development machine. They mock Android APIs and test the logic in isolation.

```bash
# Run all unit tests for both flavors
./gradlew test

# Run unit tests for a specific flavor
./gradlew testGoogletvDebugUnitTest
./gradlew testFiretvDebugUnitTest

# Run a single test class
./gradlew test --tests "com.phairplay.airplay.RtspHandlerTest"
```

Test results are in: `app/build/reports/tests/`

### Instrumented Tests (requires a device or emulator)

Instrumented tests run on a real Android TV device or emulator. They test UI and system integration.

```bash
# Run all instrumented tests (requires connected device via ADB)
./gradlew connectedAndroidTest

# Verify ADB connection first:
adb devices
```

### Lint

```bash
# Run Android Lint
./gradlew lint

# Lint results: app/build/reports/lint-results-googletv-debug.html
```

### Full CI Check (same as GitHub Actions)

```bash
./gradlew :test-runner:test
./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug \
  :app:assembleGoogletvDebug :app:assembleFiretvDebug
```

GitHub Actions runs the same checks on `main`:

- `JVM protocol tests`: fast protocol/parser/media-unit coverage through `:test-runner:test`
- `Android lint & debug APKs`: Android lint and both debug APKs

The Android Gradle unit-test tasks are not used as CI gates because several tests
exercise Android framework classes that are unstable on the host JVM. Use
`:test-runner:test` for CI-grade protocol/unit coverage and real devices for
Android framework behavior.

---

## What Is Tested

### Unit Tests

| Test Class | Tests | What Is Verified |
|---|---|---|
| `MdnsServiceTest` | 5 | mDNS registration, correct number of services, idempotency, stop behavior, port number |
| `RtspHandlerTest` | 5 | OPTIONS/RECORD/TEARDOWN responses, callback triggering, unknown method handling, SDP parsing |
| `VideoDecoderTest` | 4 | Pre-init safety, double-release safety, empty input handling, basic construction |
| `NetworkUtilsTest` | 5 | Device name reading, fallback behavior, special character sanitization, MAC address format |
| `MainActivityTest` | 3 | Activity startup, WaitingScreen visibility, StreamingScreen hidden at startup |

### What Is NOT Unit Tested (and why)

| Component | Reason | How It's Tested Instead |
|---|---|---|
| MediaCodec H.264 decode | Requires GPU hardware — cannot run on CI | Manual testing on real device (see below) |
| AudioTrack playback | Requires audio hardware | Manual testing on real device |
| NsdManager mDNS broadcast | Requires real network stack | Manual testing: observe macOS AirPlay menu |
| Full RTSP session (network) | Requires real TCP socket pair | Integration tested manually with a Mac |
| AES-CTR encryption (end-to-end) | Requires real audio stream | Manual: verify audio plays correctly |

---

## Manual Test Scenarios

For acceptance testing before a release, perform all scenarios below on both **Google TV** and **Fire TV**.

### Before You Start

Capture a clean baseline before each device run:

```bash
adb devices -l
adb logcat -c
```

Install the correct debug APK:

```bash
# Google TV with AirPlay only
./gradlew assembleGoogletvDebug

# Google TV with Cast enabled for real testing
./gradlew assembleGoogletvDebug -Pphairplay.castAppId=<APP_ID>

# Google TV
adb install -r app/build/outputs/apk/googletv/debug/app-googletv-debug.apk

# Fire TV
adb install -r app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
```

To get the Cast App ID, register the receiver in the Google Cast SDK Developer
Console and associate the Android TV package `com.phairplay.googletv`. See
[Google Cast App ID](guides/CAST_APP_ID.md).

After a failed run, collect diagnostics before restarting the app:

```bash
tools/collect-device-logs.sh

# Optional: force a package if both flavors are installed
PHAIRPLAY_PACKAGE=com.phairplay.firetv tools/collect-device-logs.sh
```

The script writes ADB device details, package info, memory stats, process CPU, and filtered logcat output under `device-test-logs/`.

### Scenario 1: App Startup (Milestone 1)
**Goal:** App starts and shows the WaitingScreen without crashing.

1. Install the debug APK via `adb install`
2. Launch PhairPlay from the TV app list
3. **Expected:** WaitingScreen appears with the TV's device name
4. **Check:** No crash dialog, no FATAL in `adb logcat`
5. **Pass condition:** App remains stable for 30 seconds after launch

---

### Scenario 2: mDNS Discovery (Milestone 2)
**Goal:** macOS discovers PhairPlay in the AirPlay menu within 3 seconds.

1. Ensure Mac and TV are on the same Wi-Fi network
2. Launch PhairPlay on the TV
3. Start a timer on your phone
4. On your Mac, click the AirPlay icon in the menu bar (or System Preferences → Displays → AirPlay Display)
5. **Expected:** TV name appears in the AirPlay menu within 3 seconds
6. Close PhairPlay (press Back on TV)
7. **Expected:** TV name disappears from the AirPlay menu within 10 seconds

---

### Scenario 3: Screen Mirroring Connection (Milestone 3)
**Goal:** Successful RTSP handshake — macOS connects without errors.

1. Launch PhairPlay on the TV
2. On your Mac, select the TV from the AirPlay menu
3. **Expected:** StreamingScreen appears on TV (transitions from WaitingScreen)
4. Check `adb logcat` for RTSP messages:
   - You should see: OPTIONS → ANNOUNCE → SETUP → SETUP → RECORD (all `200 OK`)
   - **Fail condition:** Any `4xx` or `5xx` RTSP error codes in the log
5. On your Mac, stop screen sharing
6. **Expected:** WaitingScreen reappears on TV within 2 seconds

---

### Scenario 4: Video Quality (Milestone 4)
**Goal:** Video plays at ≥25fps with ≤100ms latency.

1. Connect macOS to PhairPlay as in Scenario 3
2. On your Mac, open a terminal and run: `watch -n 0.1 date +%T.%3N` (shows a millisecond clock)
3. Look at the TV screen
4. **Expected:**
   - Frame rate: ≥25fps (video looks smooth, not choppy)
   - Latency: the time shown on TV is ≤100ms behind the time on your Mac
   - No black flashes, no frozen frames during 5 minutes

**Tip:** You can also use macOS built-in Screen Sharing quality tool:
- Hold Option while clicking the AirPlay icon → "Quality" diagnostics

---

### Scenario 5: Audio Sync (Milestone 5)
**Goal:** Audio plays in sync with video.

1. Find an A/V sync test video (search for "A/V sync test clapper board")
2. Connect macOS to PhairPlay
3. Play the test video on your Mac (it should be mirrored to the TV)
4. **Expected:** When the clapper board snaps shut, the sound happens at the same moment visually
5. **Fail condition:** Audio is more than ~40ms ahead or behind the video

---

### Scenario 6: Stability (Milestone 6)
**Goal:** 30-minute continuous stream without disconnect or crash.

1. Connect macOS to PhairPlay
2. Start a timer
3. Keep the Mac active and streaming (move the mouse occasionally, watch something)
4. **Expected after 30 minutes:**
   - TV still shows the streaming screen
   - No crash on TV
   - `adb logcat` shows no FATAL or reconnection events
5. Check RAM: `adb shell dumpsys meminfo com.phairplay.*` should show < 150MB

---

### Scenario 7: Reconnect After Disconnect (Milestone 6)
**Goal:** Automatic reconnect works.

**Test 7a: Sender disconnects:**
1. Connect macOS to PhairPlay
2. On your Mac, click the AirPlay icon and select "Turn Off AirPlay Mirroring"
3. **Expected:** TV shows WaitingScreen within 2 seconds
4. Wait 5 seconds
5. On your Mac, select the TV from AirPlay again
6. **Expected:** Streaming resumes without restarting the app

**Test 7b: Network interruption:**
1. Connect macOS to PhairPlay
2. Briefly disable and re-enable Wi-Fi on your Mac (or unplug/replug Ethernet)
3. **Expected:** PhairPlay reappears in the macOS AirPlay menu within 5 seconds of network restoration

---

## Performance Benchmarks

Run these measurements and record them in the release notes:

```bash
# RAM usage during streaming
adb shell dumpsys meminfo com.phairplay.googletv | grep "TOTAL"

# CPU usage (5-second average) — replace PID with actual process ID
adb shell top -n 5 -p $(adb shell pidof com.phairplay.googletv) | tail -5
```

Target values:
- RAM peak: ≤ 150 MB
- CPU average: ≤ 30%
- Frame rate: ≥ 25 fps
- Latency: ≤ 100 ms

---

## Known Test Limitations

| Limitation | Impact | Workaround |
|---|---|---|
| No CI hardware for video tests | MediaCodec tests can't run in CI | Manual testing required before release |
| FairPlay content can't be tested | Protected content (Netflix, etc.) is blocked by Apple — expected behavior | Document as known limitation in README |
| Wi-Fi quality varies | Latency tests may fail on congested 2.4 GHz | Test on 5 GHz or Ethernet |
