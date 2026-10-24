# PhairPlay Testing Guide

This document explains how to run the tests, what the automated suite actually
proves, and what it does **not** prove.

Two test worlds exist in this project, and they answer different questions:

| World | Question it answers | Command |
|---|---|---|
| **The JVM suite** | Does the protocol logic behave? | `./gradlew :test-runner:test` |
| **The device plan** | Does it work on a television? | [`guides/DEVICE_TEST_PLAN.md`](guides/DEVICE_TEST_PLAN.md) — **0 of 15 cases have been executed** |

If you only remember one thing: the JVM suite is green, and **no television has
ever talked to PhairPlay**.

---

## 1. The JVM test suite

### 1.1 Running it

```bash
# The whole suite — this is the CI gate
./gradlew :test-runner:test

# One test class
./gradlew :test-runner:test --tests "com.phairplay.airplay.RtspHandlerHandshakeTest"

# One test class and another one in the same run
./gradlew :test-runner:test \
  --tests "com.phairplay.util.HostAddressPolicyTest" \
  --tests "com.phairplay.airplay.RtspVideoUrlHandlerTest"

# A single test method
./gradlew :test-runner:test \
  --tests "com.phairplay.util.HostAddressPolicyTest.refuses the cloud metadata address of the router"
```

The HTML report lands in **`test-runner/build/reports/tests/test/index.html`**,
the machine-readable results in `test-runner/build/test-results/test/*.xml`.

> The module is called **`test-runner`**, not `app`. The Android Gradle plugin
> unit-test tasks (`testGoogletvDebugUnitTest` and friends) exist, but they are
> **not** the CI gate — see §1.4.

### 1.2 The baseline of this cycle

Measured on the integrated tree (2026-10-24):

| Metric | Value |
|---|---|
| Test methods | **511** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **0** |
| Test classes | **59** |
| Build | `BUILD SUCCESSFUL` (the recorded run of this tree took 3m 18s — [`spec/PROJECT_PLAN.md`](spec/PROJECT_PLAN.md)) |

The test tree holds **65** test classes in total. **Six of them are excluded
from the JVM module** and therefore never run — see §1.3:
`MainActivityTest`, `VideoSubmissionTest`, `MdnsServiceTest`,
`ServiceControllerTest`, `NetworkUtilsTest` and `VideoAspectFitTest`.
59 + 6 = 65.

**Before this cycle the suite had 277 tests.** The cycle added the DLNA
control plane, the address policies, the picture decoder and the tests that came
with the split of the RTSP handler.

The milestone status and the release gate own these numbers in
[`spec/PROJECT_PLAN.md`](spec/PROJECT_PLAN.md); they are repeated here because
a developer needs them at the moment they runs the suite.

**The package split of the measured run:**

```text
com.phairplay.airplay:           177
com.phairplay.airplay.handshake:   35
com.phairplay.cast:                 3
com.phairplay.dlna:               158
com.phairplay.media:                4
com.phairplay.miracast:             5
com.phairplay.service:             35
com.phairplay.settings:            19
com.phairplay.util:                75
                                 ----
                                 511
```

### 1.3 What the JVM module compiles — and what it does **not**

The module lives in `test-runner/` and is a **plain Kotlin/JVM Gradle project**
without the Android Gradle plugin. It compiles **four source directories
together** into one compilation unit:

```text
test-runner/build.gradle.kts
  srcDirs = ../app/src/main/kotlin        ← the production code
           ../app/src/firetv/kotlin       ← the Fire TV flavour (no Cast SDK)
           ../app/src/test/kotlin         ← the tests
           src/stubs                      ← three stubs that shadow real files
```

Because production and test sources live in **one compilation unit**, the
`internal` members of the production code are reachable from the tests without
any friend-module workaround.

#### The trap: the excluded files

The following files are **not** compiled by the JVM module. A test you add in
one of those packages will simply never run — and the suite stays green.

| Excluded | Why |
|---|---|
| `**/ui/**` | The screens need AndroidX (Leanback), the `R` class and the Activity lifecycle — **and the pattern also drops the `ui` package of the test tree** (see the trap below) |
| `**/MainActivity.kt`, `**/PhairPlayApp.kt` | The Activity lifecycle and the application class |
| `**/settings/SettingsRepository.kt` | Needs AndroidX DataStore |
| `**/service/PhairPlayService.kt`, `**/service/BootReceiver.kt`, `**/service/ServiceNotificationManager.kt`, `**/service/ReceiverSupervisor.kt` | Need `NotificationCompat` and the generated `R` class. A **stub** of `PhairPlayService` (only the companion constants) is used instead |
| `**/airplay/VideoDecoder.kt` | Needs `MediaCodec` and a `Surface`. A **stub** without the codec API is used instead; the SPS parser it shares with production stays real |
| `**/airplay/VideoSubmissionTest.kt` | Exercises the real MediaCodec submission path, not the JVM stub |
| `**/MdnsServiceTest.kt` | `NsdManager`'s static initialiser calls a JNI method (`VMRuntime.newUnpaddedArray`) that does not exist on the JVM |
| `**/ServiceControllerTest.kt` | `android.os.Build.<clinit>` calls `SystemProperties.native_get` (JNI) |
| `**/NetworkUtilsTest.kt` | `ContentResolver.<clinit>` triggers the same `android.os.Build` JNI call |
| `**/MainActivityTest.kt` | Needs the Activity |

**What you have to do about it:** a behaviour that lives in an excluded file is
proven by the **APK build** (the file has to compile) and by a **device test**
(the behaviour has to happen). If you add a test for such a file, it belongs
into the Android instrumented test source set — and it will only run on real
hardware.

The stubs live in `test-runner/src/stubs/`: `BuildConfig.kt`,
`PhairPlayService.kt` and `VideoDecoder.kt`. They shadow the real files of the
same name; keep them in sync with the production API.

#### The trap inside the trap: a test that never runs

The pattern `**/ui/**` drops the **whole `ui` package of the test tree** as
well — and one test class lives there:

| Test class | Test methods | What it covers | Status |
|---|---:|---|---|
| `app/src/test/kotlin/com/phairplay/ui/VideoAspectFitTest.kt` | 3 | `VideoAspectFit.scale()` — the aspect-fit arithmetic of the video surface | **never executed** — the `**/ui/**` exclusion removes it from the module, so it produces no result file and no failure |

`VideoAspectFit` itself is a **pure** function without a single Android type, so
the test could run on the JVM. The exclusion `**/ui/**` is simply too coarse:
it excludes the test together with the screens. The 511 measured tests do
**not** contain those 3 methods.

If you want them in the suite, one of the two has to change (both are outside
the test module):

- narrow the exclusion in `test-runner/build.gradle.kts` to the files that
  really need AndroidX (the screens), and
- move `VideoAspectFit` out of `com.phairplay.ui` (e.g. into
  `com.phairplay.util` or a new `com.phairplay.media`), or exclude
  `app/src/main/kotlin/com/phairplay/ui/VideoAspectFit.kt` explicitly and
  include only the test.

Until then, treat the aspect-fit arithmetic as **unproven by the suite**.

### 1.4 Why the Android unit-test tasks are not the gate

The Android Gradle plugin unit-test tasks (`./gradlew :app:testGoogletvDebugUnitTest`)
run some tests that the JVM module cannot run — but several of them exercise
Android framework classes that are unstable on the host JVM. The project
therefore uses **`:test-runner:test`** for the CI-grade protocol and unit
coverage, and real devices for the Android framework behaviour. The CI does the
same: the job `jvm-tests` runs `./gradlew :test-runner:test`, the job
`android-build` runs lint and builds both debug APKs.

### 1.5 The other gates

```bash
# Android lint for both flavours
./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug

# Both debug APKs
./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug

# Everything the CI runs
./gradlew :test-runner:test \
  :app:lintGoogletvDebug :app:lintFiretvDebug \
  :app:assembleGoogletvDebug :app:assembleFiretvDebug
```

The lint reports say `No issues found.` for both variants; the debug APKs are
uploaded by CI and fail the job when no APK was produced.

### 1.6 The four structure checks

They are not tests of the product — they are tests of the repository. They run
in the CI job `quality-gate` and every one of them exits **1** on a violation.

```bash
bash scripts/check-file-size.sh        ; echo "exit=$?"   # ≤ 400 soft / ≤ 550 hard lines
bash scripts/check-kdoc-headers.sh     ; echo "exit=$?"   # every class has a KDoc header
bash scripts/check-docs-links.sh       ; echo "exit=$?"   # every internal link resolves
bash scripts/check-i18n-completeness.sh ; echo "exit=$?"  # every language has every key
```

The measured state of this cycle:

```text
OK: file size check passed — 189 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.
OK: KDoc header check passed — 233 class declaration(s) checked, all of them have a KDoc header.
OK: documentation link check passed — 245 internal link(s) resolve, 19 external link(s) skipped.
OK: i18n completeness check passed — 80 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).
```

These four counts are a **snapshot of 2026-10-24**; the scripts print the
current values on every run. The link count in particular moves with every
document change — take the number from the script output, not from here.

The KDoc check runs with `--skip-nested` in CI (a nested declaration is covered
by the KDoc header of the class that contains it).

---

## 2. What the JVM suite proves — and what it does not

### 2.1 It does prove

- The **parsers**: RTSP requests, SDP, binary plists, the H.264 SPS, the DIDL-Lite
  metadata, the SSDP messages.
- The **protocol state machines**: the AVTransport transitions, the RTSP session
  lifecycle, the AirPlay handshake answers.
- The **security guards**: the address policies, the URL policy, the XML hardening,
  the size and time budgets — each with a negative test that proves the refusal.
- The **crypto**: AES-128-CTR / CBC, the FairPlay key decryption, the SRP-6a PIN
  pairing, the RSA-OAEP recovery.
- The **pure arithmetic** of the media paths: the FU-A reassembly, the frame
  queue's byte budget, the downsampling factors, the RTSP statistics.

### 2.2 It does **not** prove

| What | Why the JVM cannot prove it | Where it is proven instead |
|---|---|---|
| **The bitmap decoding path** | `BitmapFactory` is part of the Android framework and does not exist in the pure JVM module. `SafeBitmapDecoderTest` proves the dimension and pixel budget *in front of* the decoder — not the decoding itself | an instrumented test on a real device |
| **The MediaCodec submission path** | `VideoSubmissionTest` is excluded from the JVM module on purpose | the APK build + a device test |
| **The mDNS registration** | `MdnsServiceTest` is excluded — `NsdManager` needs JNI | the AirPlay picker of a real sender (DT-03) |
| **The Activity, the notification, the settings persistence** | AndroidX Leanback, `NotificationCompat` and DataStore are not on the classpath | the device plan (DT-01, DT-13, DT-14) |
| **Every behaviour of the television** | No frame, no surface, no speaker, no system UI, no LAN | the device plan — **0 of 15 cases executed** |
| **The cleartext `http://` media fetch** | The platform's cleartext policy is a device decision; no `network_security_config.xml` exists | a device test on API 35 (DT-09 / DT-10) |
| **The aspect-fit arithmetic** | `VideoAspectFitTest` sits in the excluded `ui` package of the test tree and never runs (§1.3) | a narrow fix of the exclusion, then the suite |

**A green JVM suite is not a working product.** It says the protocol logic is
consistent; it says nothing about the television.

---

## 3. The device test plan

The authoritative list of everything that needs hardware is
[`guides/DEVICE_TEST_PLAN.md`](guides/DEVICE_TEST_PLAN.md): **15 cases**
(DT-01 … DT-15), each with a copy-pasteable script, a time limit and an
evidence requirement.

| Case | What it proves |
|---|---|
| DT-01 | Install and first start |
| DT-02 | Start, stop and restart the receiver |
| DT-03 | AirPlay mirroring (video) from macOS |
| DT-04 | AirPlay audio only (the now-playing screen) |
| DT-05 | The AirPlay pairing PIN |
| DT-06 | Miracast from Windows |
| DT-07 | Google Cast (Google TV only) |
| DT-08 | DLNA: SSDP discovery from the network |
| DT-09 | VLC renderer test |
| DT-10 | DLNA: SOAP control from the command line |
| DT-11 | GENA event subscription |
| DT-12 | The German and the French user interface |
| DT-13 | Start on boot |
| DT-14 | The notification and its actions |
| DT-15 | Debug overlay and stability under load |

**Status: 0 of 15 cases have been executed.** No television was available, so
every acceptance criterion that needs hardware is still open. The acceptance
records in [`acceptance/`](acceptance/) mark each criterion as *proven by a
test*, *proven on the code* or *still open*.

### 3.1 Before you start

```bash
adb devices -l
adb logcat -c
```

Install the right debug APK:

```bash
./gradlew assembleGoogletvDebug
./gradlew assembleGoogletvDebug -Pphairplay.castAppId=<APP_ID>   # for a real Cast test

adb install -r app/build/outputs/apk/googletv/debug/app-googletv-debug.apk
adb install -r app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
```

The Cast App ID has to be registered in the Google Cast SDK Developer Console
and associated with the package `com.phairplay.googletv` — see
[Google Cast App ID](guides/CAST_APP_ID.md).

After a failed run, collect the diagnostics **before** you restart the app:

```bash
tools/collect-device-logs.sh

# if both flavours are installed
PHAIRPLAY_PACKAGE=com.phairplay.firetv tools/collect-device-logs.sh
```

The script writes the ADB device details, the package info, the memory stats,
the process CPU and the filtered logcat output under `device-test-logs/`.

### 3.2 The performance budgets

The budgets themselves are owned by
[`spec/ACCEPTANCE_CRITERIA.md`](spec/ACCEPTANCE_CRITERIA.md) §"Performance
budgets" — the peak RAM, the average CPU, the frame rate and the latency. The
measurement commands are in the device test plan (DT-03, DT-15):

```bash
# RAM during a stream
adb shell dumpsys meminfo com.phairplay.googletv | grep "TOTAL"

# CPU (5-second average) — replace the PID with the real process id
adb shell top -n 5 -p $(adb shell pidof com.phairplay.googletv) | tail -5
```

**None of these numbers has been measured.** They are targets, not results.

---

## 4. Known test limitations

| Limitation | Impact | Workaround |
|---|---|---|
| No CI hardware for video tests | MediaCodec tests cannot run in CI | Manual testing on a real device (DT-03, DT-15) |
| FairPlay content cannot be tested | Protected content (Netflix and the like) is blocked by Apple — this is the expected behaviour | Documented as a known limitation |
| Wi-Fi quality varies | Latency tests may fail on a congested 2.4 GHz network | Test on 5 GHz or Ethernet |
| The cleartext media fetch is probably blocked on API 35 | The DLNA media path may not work at all on a current device — there is no `network_security_config.xml` | DT-09 / DT-10 with an `https` source, or a decided CR-02 |
| The DLNA media URI still accepts `file`, `smb` and other schemes | A deliberate compatibility decision, not a test defect — see the audit finding SEC-04 | An owner decision in the release notes |
