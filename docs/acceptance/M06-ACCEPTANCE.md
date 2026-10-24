# Acceptance — M6: Miracast receiver (discovery and control plane)

**Feature / milestone:** M6 — Miracast / Wi-Fi Display receiver
**Requirement IDs:** FR-13 … FR-18 (status "not implemented" for the media path),
FR-58 … FR-60 (scope exclusion), AC-M6.1 … AC-M6.8
**Build / commit:** `51933eb` (branch `feature/quality-m6-m12`, merged into
`feature/project-status`)
**Flavour(s) tested:** `googletv` and `firetv` — both compile; the Miracast code is
in the shared `main` source set, so both flavours carry the identical implementation
**Device(s) tested:** **none** — no television was available
**Sender(s) tested:** **none** — no Windows sender was available
**Tester / roles:** Project Manager (audit), with the code evidence of the Miracast
implementation and the JVM test run
**Date:** 2026-10-24
**Update (second pass, 2026-10-24):** the team leader closed the three open
points of the previous round — the CI quality gate, the lint and the three files
above the line limit. The statements about the red gate, the missing KDoc
headers, the 439-line file and the single lint finding were brought up to date
in the same commit. The JVM suite and the debug APKs were **not** built again;
the numbers of section 2.1 name the run they come from.

---

## 1. Functional

Does the feature do what the requirement says?

| # | Scenario | Expected | Result | Evidence |
|---|---|---|---|---|
| 1 | The receiver registers the Wi-Fi Direct DNS-SD service `_wfd._tcp` | a registration with the sink TXT records | **PASS (code + unit test)** | `app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt` line 256 `SERVICE_TYPE_WFD = "_wfd._tcp"`; the TXT map is built at lines 205–209 with `wfd_device_type=primary_sink`, `wfd_session_available=1`, `wfd_rtsp_port=<WFD_RTSP_PORT>`, `wfd_video_formats=h264-chp,h264-cbp`, `wfd_audio_codecs=lpcm`. Unit test `MiracastReceiverTest > start advertises WFD service and emits advertising state` — **PASSED** in the measured run. |
| 2 | The WFD RTSP control plane answers the seven WFD verbs | `200 OK` for OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN | **PASS (code + unit test)** | `WfdRtspServer.kt` (the class was split out of `MiracastReceiver.kt` in the second pass) line 165: `else -> RtspResponse(statusCode = 501, statusMessage = "Not Implemented")` — every verb outside the supported set is refused with `501`. Unit tests `WFD RTSP port uses Miracast default`, `WFD RTSP server advertises sink capabilities`, `WFD RTSP PLAY emits connected state once` — all **PASSED**. |
| 3 | `GET_PARAMETER` reports the sink capability set | the answer contains `wfd_video_formats`, `wfd_audio_codecs`, `wfd_client_rtp_ports`, `wfd_content_protection` | **PASS (code)** | `WfdRtspServer.kt` lines 171–172 build the answer body with `wfd_audio_codecs: LPCM 00000003 00` and `wfd_video_formats: 00 00 02 10 0001FFFF …`. Read from the source; **not** covered by a separate assertion in the current test. |
| 4 | A second concurrent WFD client is rejected | the second client receives `RTSP/1.0 503 Service Unavailable` | **NOT MEASURED** | The `503` branch exists in the server implementation, but there is **no** test in `MiracastReceiverTest` that opens two sockets. AC-M6.5 names such a test as the requirement; it does not exist yet. |
| 5 | The receiver reports `ERROR` when `WifiP2pManager` is unavailable | the state is `ERROR`, the Home screen never shows "Advertising" | **PASS (unit test) / NOT MEASURED (device)** | Unit test `MiracastReceiverTest > start emits error when WifiP2pManager is unavailable` — **PASSED**. The device half of AC-M6.6 (the card shows the error text) needs hardware: device test plan case DT-06. |
| 6 | The app never requests a location permission at runtime | the only `requestPermissions` call is the `POST_NOTIFICATIONS` one | **PASS (code)** | `grep -rn "requestPermissions" app/src/main` → only the `POST_NOTIFICATIONS` request in `MainActivity.kt`. The location permissions are declared but never requested — see the permission audit, `docs/audit/2026-10-24-security-audit.md` §4. |
| 7 | No hidden Wi-Fi Display API is used | `setWfdInfo` / `CONFIGURE_WIFI_DISPLAY` do not occur in the sources | **PASS (code)** | `grep -rn "setWfdInfo\|CONFIGURE_WIFI_DISPLAY" app/src/main` → 0 matches. The implementation uses only the public `WifiP2pManager` / `WifiP2pDnsSdServiceInfo`. |
| 8 | No MPEG-TS demuxer and no WFD decoder exist | 0 matches for `MiracastDecoder`, `MpegTs`, `TsDemux` | **PASS (code)** | `grep -rn "MiracastDecoder\|MpegTs\|TsDemux" app/src/main` → 0 matches. The media path is deliberately absent; the scope exclusion is written down in `docs/spec/REQUIREMENTS.md` §3 and in ADR-004. |

**Edge cases exercised:**

| Case | Expected | Result |
|---|---|---|
| The device has no Wi-Fi Direct hardware | the receiver reports `ERROR`, it does not pretend to advertise | **PASS** — unit test `start emits error when WifiP2pManager is unavailable` |
| A sender asks for a method the control plane does not implement | `501 Not Implemented` | **PASS (code)** — `MiracastReceiver.kt` line 399; no dedicated unit test |
| A second client connects while a session runs | `503 Service Unavailable` | **NOT MEASURED** — no test opens two sockets; see scenario 4 |
| The TV is a Fire TV (no usable Wi-Fi Direct) | the card shows the hardware limitation, not a crash | **NOT MEASURED** — device test plan case DT-06 records "not applicable — hardware limitation" with a written reason |

---

## 2. Technical

- Architecture boundaries respected (the protocol stacks stay independent): **yes** —
  all Miracast code lives in `com.phairplay.miracast` (two files:
  `MiracastReceiver.kt`, 260 lines, and `WfdRtspServer.kt`, 205 lines — the WFD
  RTSP server was split out of the receiver); the AirPlay stack is untouched by
  it, and the `googletv` / `firetv` flavour split does not affect it.
- The file-size rule respected (≤ 400 lines soft, ≤ 550 hard): **yes** —
  `MiracastReceiver.kt` is 260 lines and `WfdRtspServer.kt` is 205 lines, both
  below the soft limit of 400. Measured: `bash scripts/check-file-size.sh` →
  exit 0, `OK: file size check passed — 184 file(s) checked, none above the hard
  limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.` The three
  files that were above the limit in the previous round are gone.
- Every new class has a KDoc header (what / why / example): **yes** — the
  production file carries its header at `MiracastReceiver.kt` lines 16–47, and
  the KDoc gate is green on the whole tree: `bash scripts/check-kdoc-headers.sh`
  → exit 0, `OK: KDoc header check passed — 227 class declaration(s) checked, all
  of them have a KDoc header.` The violation that was still open in this file
  (`MiracastReceiver.kt:270: MISSING-KDOC WfdRtspServer`) is closed: the class
  now lives in its own file, `WfdRtspServer.kt`, with its own KDoc header.
- Every new public method has a test: **partly** — five tests exist in
  `MiracastReceiverTest`; the `503` path (AC-M6.5) has none.
- Tests green — `./gradlew :test-runner:test`: **yes** — measured on
  `51933eb`: `BUILD SUCCESSFUL in 3m 27s`, 457 tests, 0 failures, 0 errors,
  0 skipped, across 54 test classes. The five Miracast tests are part of that
  run. **Current state (not re-run):** the runner's source set now holds **469**
  test methods in **56** test classes — a static count of the `@Test`
  annotations after excluding the 38 methods of the six excluded files. The
  `com.phairplay.miracast` package still holds its 5 test methods. The JVM
  suite was not executed again in this documentation cycle, so the number is a
  static count and not a fresh run result.
- Lint clean — `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug`: **yes** —
  measured after the team leader's fix: `:app:lintGoogletvDebug` → `BUILD
  SUCCESSFUL` (5m 12s) and `:app:lintFiretvDebug` → `BUILD SUCCESSFUL` (3m 39s).
  Both reports say `No issues found.` (`app/build/reports/lint-results-googletvDebug.txt`,
  `app/build/reports/lint-results-firetvDebug.txt`). The single finding of the
  previous round — `app/src/main/AndroidManifest.xml:58: Error: Attribute
  usesPermissionFlags is only used in API level 31 and higher (current min is 29)
  [UnusedAttribute]` — is closed: the permission element now carries
  `tools:targetApi="31"` (`app/src/main/AndroidManifest.xml` lines 64–65). The
  `firetv` variant, which was never analysed before, is analysed and green.
- Both flavours build — `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug`:
  **yes** — see the build log quoted in section 2.1.
- No new dependency / no new permission: **yes** — the Miracast implementation uses
  only the Android framework (`WifiP2pManager`, `WifiP2pDnsSdServiceInfo`). The
  permissions it needs (`CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES`,
  `ACCESS_WIFI_STATE`) were already declared and are traced in
  `docs/audit/2026-10-24-security-audit.md` §4.

### 2.1 Build log (measured)

```
$ source /workspace/toolchain/env.sh
$ ./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline --console=plain
...
BUILD SUCCESSFUL
```

Both debug APKs are produced from the same commit the JVM suite was run on
(`51933eb`). The tree has moved on since then (the quality-gate and the lint
fixes); **no APK was built again** in this documentation cycle, so the two
measurements above describe `51933eb`, not the current head.

**Test run output:**

```
BUILD SUCCESSFUL in 3m 27s
457 tests, 0 failures, 0 errors, 0 skipped (54 test classes)
```

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
OK: i18n completeness check passed — 80 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).
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
| Frame rate | ≥ 25 fps | **not measured** — no Miracast media path exists, and no device | — |
| Video latency | ≤ 100 ms | **not measured** | — |
| RAM peak | ≤ 150 MB | **not measured** | — |
| CPU average | ≤ 30 % | **not measured** | — |
| WFD RTSP port | 7236 | **PASS (code)** — `WFD_RTSP_PORT` is asserted by the unit test `WFD RTSP port uses Miracast default` | — |

**Reference device / input:** none — no device was available.

**Measurement commands used:**

```bash
# would be used on a device (device test plan case DT-06):
adb shell dumpsys meminfo com.phairplay.googletv
adb shell top -p $(adb shell pidof com.phairplay.googletv)
```

> No performance number was measured for M6. The Miracast media path does not
> exist in this build, so the performance budgets of the quality model
> (`docs/quality/QUALITY.md` §3) do not apply to it.

---

## 4. Usability

- Step count from "I want to mirror" to "it works": **not measurable** — the
  Windows sender cannot discover the receiver on stock Android TV (see "Known
  limitations"). The step count for the *user-visible* path is: Settings →
  Protocols → Miracast on (1 tap), then the card reads "Advertising".
- Discoverability: the Miracast card is the third of four protocol cards on the
  Home screen (`HomeFragment.kt` lines 124–127, four cards: AirPlay, Miracast,
  Cast, DLNA).
- The screen tells the truth: **yes** — `MiracastReceiver` reports
  `ProtocolState.ERROR` when `WifiP2pManager` is unavailable, and the card
  renders that state (`HomeFragment.kt` line 180 collects `svc.miracastState`).
  The unit test `start emits error when WifiP2pManager is unavailable` proves the
  state transition; the rendering of the card is device-only.
- The error state is understandable: the log states the reason
  (`Google Cast is not configured…` is the Cast equivalent); on the card the
  state text comes from the resource strings `protocol_state_error` and
  `protocol_detail_*`.
- Visual / polish issues seen: **not assessed** — no device.
- Strings: all user-visible texts are in resource files — `grep -rn "setText(\""
  app/src/main` → 0 matches (AC-M10.4).

---

## 5. Accessibility

- D-pad navigation: every interactive element reachable, focus always visible?
  **not measured** — no device.
- Focus target size ≥ 48 dp (NFR-07)? **not measured** — no device.
- Text size ≥ 18 sp for body, ≥ 32 sp for titles (NFR-08)? **not measured**.
- Contrast readable from 3 metres (NFR-09)? **not measured**.
- TalkBack / screen reader: **not measured**.
- What is **not** covered yet: the whole accessibility verification of the Miracast
  card. The card is a `View` in `HomeFragment` like the other three; there is no
  M6-specific accessibility code that could be missing. The measurement needs a
  device.

---

## 6. Security and privacy

- New network input (a new parser, a new field, a new port): **yes** — a WFD RTSP
  control plane on TCP 7236 (`MiracastReceiver.WFD_RTSP_PORT`) and a Wi-Fi Direct
  DNS-SD service advertisement. The RTSP request line is read with the shared
  `RtspRequestReader` of the AirPlay stack, which bounds the request size.
- New stored data (what, where, how long): **no** — the Miracast receiver stores
  nothing.
- New outbound connection (where does it go?): **no** — the Miracast receiver
  opens no outbound connection; it only advertises and accepts.
- New permission (which one, traced to which call site?): **no new permission in
  this cycle**. `CHANGE_WIFI_STATE` and `NEARBY_WIFI_DEVICES` (with
  `neverForLocation`) exist for `WifiP2pManager`; the location permissions are
  capped at API 32.
- Negative tests added (the test that proves the guard rejects the attack):
  **partly** — the permission guard is proven by
  `MiracastReceiverTest > start emits error when WifiP2pManager is unavailable`.
  There is **no** negative test for a hostile WFD RTSP request (oversized request
  line, unknown verb beyond the `501` branch).
- The log reduction still applies to every new log statement (SEC-07)? **yes** —
  every message goes through `com.phairplay.util.Logger`, which reduces the
  message before it is written (`docs/audit/2026-10-24-security-audit.md` §6).
- Known findings from the audit that still apply to this feature: **SEC-06**
  (the Miracast error state could not be observed on a device), **SEC-07**
  (a release build logs nothing — the reason the log output cannot be used as
  device evidence).
- Privacy impact: does anything new leave the local network? **It must not.** The
  Miracast receiver sends nothing outside the local network; it advertises the
  device name over Wi-Fi Direct, which is required for the sender to find it and
  is documented in `PRIVACY.md`.

---

## 7. Interoperability and data integrity

| Sender | Version | Protocol | Result |
|---|---|---|---|
| macOS | — | AirPlay 2 | not applicable to M6 |
| iPhone / iPad | — | AirPlay 2 | not applicable to M6 |
| Windows | 10 / 11 | Miracast (WFD) | **NOT MEASURED** — no Windows sender and no device. Device test plan case DT-06. |
| Windows | — | DLNA / UPnP | covered by M12 |
| VLC | — | DLNA / UPnP | covered by M12 |
| Chrome | — | Google Cast | covered by M7 |
| Samsung Galaxy | — | Miracast (WFD) | **NOT MEASURED** — FR-17 names the Samsung Galaxy as a Miracast sender; no device |

- Round trip: does the state come back correctly (transport state, position, metadata)?
  **partly** — the WFD control plane reports its own capability set; the transport
  state of a Miracast session is `CONNECTED` after `PLAY` (unit test
  `WFD RTSP PLAY emits connected state once`), but the state round trip with a
  real sender is not measured.
- Does an unsupported format fail cleanly instead of crashing? **yes (code)** —
  every unknown method returns `501 Not Implemented` instead of an exception.
- Recovery: does the receiver come back to a usable state after an interruption?
  **not measured** — no device.

---

## 8. Known limitations

What does **not** work, and what is the reason? Every entry here must also be
visible where a user will meet it.

| Limitation | Reason | Where it is documented |
|---|---|---|
| No Miracast **media** is rendered. There is no MPEG-TS demuxer and no WFD video or audio decoder. | A sideloaded app cannot use the hidden `WifiP2pManager.setWfdInfo()` API, so a Windows sender does not discover the receiver in the first place. | `docs/spec/REQUIREMENTS.md` §3 (scope exclusion); ADR-004 (context, point 1); `README.md` ("Honest limitations") |
| The receiver covers the discovery and the control plane only | The public Android API offers no way to act as a Wi-Fi Display sink | ADR-004; `docs/spec/REQUIREMENTS.md` FR-58 … FR-60 |
| A second concurrent WFD client is rejected with `503`, but that path has no test | The concurrency case was never written as a unit test | This document, section 1, scenario 4 |
| The Miracast card shows `ERROR` on devices without usable Wi-Fi Direct | Hardware limitation | `docs/guides/TROUBLESHOOTING.md` |

---

## 9. Decision

**accepted with documented limitation**

**Decided by:** Project Manager, on behalf of the owner, with the scope decision
already taken in ADR-004 and in `docs/spec/REQUIREMENTS.md` §3
**Date:** 2026-10-24

The M6 milestone as it is defined in `docs/spec/PROJECT_PLAN.md` — "Miracast
receiver, discovery and control plane" — is implemented and covered by tests. The
milestone as it was originally written in the plan ("full codec matrix", MPEG-TS
demuxing, H.264 decode, HDCP negotiation) is **not** implemented and cannot be
implemented by a sideloaded app; that part is removed from the plan and recorded
as a scope exclusion.

The one gap inside the reduced scope is the untested `503` path for a second
concurrent client (AC-M6.5). It is an open point, not a defect: the behaviour
exists in the code, the proof does not.

The second pass closed the two pipeline gates that were red in the previous
round: the CI quality gate is green (all four structure checks exit 0) and the
lint is green for **both** flavours. The Miracast files are below the line
limit as well (`MiracastReceiver.kt` 260 lines, `WfdRtspServer.kt` 205 lines).
None of that changes the device proof: the milestone is still unverified on
hardware, and the `503` path still has no test.

**Follow-up (open, because …):**

1. **Add the AC-M6.5 concurrency test** — a test that opens two sockets against
   `WfdRtspServer` and asserts `503` on the second. Reason: the criterion names
   it and it does not exist yet.
2. **Run device test plan case DT-06 on a Google TV with a Windows sender.**
   Reason: no hardware was available; this is the only way to observe the
   behaviour on a real device.
3. **Decide whether the WFD capability advertisement is enough** — if the owner
   wants Windows to actually find the device, the only remaining lever is a
   rooted or system-signed build with `CONFIGURE_WIFI_DISPLAY`. Reason: that
   changes the distribution model of the app and is therefore an owner decision.

---

## 10. Release gate

Every box has to be checked before a release can be cut. The gate itself is
defined in [`../quality/QUALITY.md`](../quality/QUALITY.md) §7.

| # | Gate | Result |
|---|---|---|
| 1 | `./gradlew :test-runner:test` is green | **yes** — measured on `51933eb`: 457 tests, 0 failures, 0 errors, 0 skipped. Current static count: 469 test methods in 56 classes (the suite was not re-run in this cycle). |
| 2 | Lint and both debug APKs are green | **yes** — the lint is green for both flavours (`:app:lintGoogletvDebug` and `:app:lintFiretvDebug`, `BUILD SUCCESSFUL`, both reports `No issues found.`); both debug APKs build (measured on `51933eb`) |
| 3 | **No known S4 finding** | **yes for this milestone** — the M6 surface has no S4 finding; the open S4 findings (SEC-01, SEC-02, SEC-03) belong to AirPlay and the UI track, not to M6 |
| 4 | Every open S3 has a written owner decision | **yes** — SEC-06 and SEC-07 are the findings that touch M6; both carry a written decision in `docs/audit/2026-10-24-security-audit.md` §7 |
| 5 | The device scenarios ran on both flavours | **no** — DT-06 has not been executed on either flavour |
| 6 | The file-size rule holds for the touched files | **yes** — `MiracastReceiver.kt` 260 lines and `WfdRtspServer.kt` 205 lines, both below the soft limit of 400. `bash scripts/check-file-size.sh` → exit 0 with `0 warning(s) above the soft limit of 400 lines`. Risk R-17. |
| 7 | The documentation is up to date (same change) | **yes** — the scope exclusion is in `docs/spec/REQUIREMENTS.md` §3 and ADR-004; the user-facing limitation is in `README.md` and `docs/guides/CASTING_TO_A_TV.md` |
| 8 | The limitations in `README.md` are true of this build | **yes** — README states "Miracast and Google Cast receiver stacks are in progress (control-plane implemented; media playback pending)"; the more precise wording ("no media path, by design") is proposed as CR-08 |

**CI quality gate** (the job `quality-gate` of `.github/workflows/ci.yml`, not a
separate gate in `docs/quality/QUALITY.md` §7): **green** — all four structure
checks exit 0 (section 2.1).

