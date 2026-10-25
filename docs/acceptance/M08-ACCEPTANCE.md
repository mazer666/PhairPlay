# Acceptance — M8: Stability, reconnect and resource budgets

**Feature / milestone:** M8 — Stability of the running receiver
**Requirement IDs:** FR-36, FR-61 … FR-63, NFR-01 … NFR-04, NFR-27,
AC-M8.1 … AC-M8.8 (plus the legacy round AR-6 / `AC-6.x`)
**Build / commit:** `51933eb` (branch `feature/quality-m6-m12`, merged into
`feature/project-status`)
**Flavour(s) tested:** `googletv` and `firetv` — both compile
**Device(s) tested:** **none** — no television was available
**Sender(s) tested:** **none** — no sender was available
**Tester / roles:** Project Manager (audit), with the code evidence and the JVM
test run
**Date:** 2026-10-24
**Update (second pass, 2026-10-24):** the team leader closed the three open
points of the previous round — the CI quality gate, the lint and the three files
above the line limit. The statements about the red gate, the lint finding and
the file-size warning were brought up to date in the same commit. The JVM suite
and the debug APKs were **not** built again; the numbers of section 2.1 name the
run they come from.

---

## 1. Functional

Does the feature do what the requirement says?

| # | Scenario | Expected | Result | Evidence |
|---|---|---|---|---|
| 1 | The mirroring frame queue never holds more than 90 items and never more than 32 MB | the byte budget and the item budget both hold | **PASS (unit test)** | `app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorFrameQueue.kt`: `DEFAULT_MAX_ITEMS = 90` (line 148), `DEFAULT_MAX_BYTES = 32L * 1024 * 1024` (line 159). Unit tests `offer keeps the queued bytes within the budget when it is exceeded on purpose`, `offer drops the oldest frames first`, `the byte counter always equals the real occupancy of the queue`, `an item that is bigger than the whole budget is refused`, `the item limit is enforced independently of the byte budget`, `the default budget is 32 MB` — all **PASSED** in the measured run. |
| 2 | A failed mDNS advertisement is retried with a bounded backoff and ends in an explicit error state | the tracker counts the failures and does not tear down the sibling service | **PASS (unit test)** | `MdnsRegistrationTrackerTest` — 10 tests, all **PASSED**: `advertising requires both services`, `a failure does not unregister the sibling`, `failure of an outdated attempt is ignored`, `consecutive failures are counted`, `late success is still honoured`, `failed attempt keeps an earlier registration`, `each attempt has its own number`, `attempts of the two services are independent`, `reset forgets the registrations`. The backoff values (1 s / 3 s / 6 s) are documented in `CHANGELOG.md` under 1.0.0-beta.3 "mDNS registration could not recover". |
| 3 | After the television loses and regains its network, the AirPlay receiver re-advertises within 5 s without an app restart | the device is visible in the picker ≤ 5 s after the network is back | **NOT MEASURED** | The mechanism exists: `app/src/main/kotlin/com/phairplay/service/NetworkRecoveryMonitor.kt` registers a `ConnectivityManager.NetworkCallback` and calls `onNetworkRestored`. The timing is device behaviour and needs hardware. Device test plan: DT-02 / DT-15. |
| 4 | After the same outage **every enabled receiver** re-advertises — AirPlay *and* UPnP/DLNA | both re-advertise | **NOT MEASURED — the code path exists for both** | Milestone M13 closed the FR-61 deviation: `NetworkRecoveryMonitor` now calls `ReceiverSupervisor.readvertiseAll()`, which re-advertises AirPlay over mDNS **and** the DLNA renderer over SSDP. The DLNA renderer additionally rebuilds its description server when the device came back with a different address — the description URL contains that address. The decision is proven by `DlnaReadvertisePlanTest`; the socket behaviour and the five-second bound need hardware (DT-02 / DT-15). |
| 5 | Stopping the service releases every network port; starting it re-binds them within 3 s | no listener on 7000 or 1900 after Stop; both bound again ≤ 3 s after Start | **NOT MEASURED** | Device behaviour (`adb shell ss -ltn`). The stop path is implemented: `DlnaReceiver.stop()` closes the description server and sends the SSDP `byebye` (FR-55), `AirPlayReceiver.stop()` closes the RTSP socket. The timing is device-only. |
| 6 | A 30-minute continuous AirPlay mirroring session runs without a crash | 0 `FATAL` entries, the session is alive at minute 30 | **NOT MEASURED** | Device test plan case DT-15. This is the single most important stability number in the project and it has never been taken. |
| 7 | The peak resident memory stays below 150 MB | ≤ 150 MB at every measurement | **NOT MEASURED** | `adb shell dumpsys meminfo com.phairplay.googletv` — device test plan case DT-15. |
| 8 | The average CPU load stays below 30 % | the mean of the samples ≤ 30 % | **NOT MEASURED** | `adb shell top -p $(adb shell pidof com.phairplay.googletv)` — device test plan case DT-15. |
| 9 | The receiver starts after a reboot | the `BootReceiver` starts the service | **PARTLY** | `app/src/main/kotlin/com/phairplay/service/BootReceiver.kt` exists and the `RECEIVE_BOOT_COMPLETED` permission is declared; the setting "start on boot" exists and is tested by `AppSettingsTest > default settings have start on boot disabled`. The **runtime** behaviour is device-only — device test plan case DT-13. |
| 10 | An auto-reconnect exists for all protocols | each protocol reconnects on its own | **NOT MEASURED** | The **advertisement** recovers on its own for AirPlay and DLNA (scenario 3 and 4, milestone M13). There is still no protocol-level auto-reconnect for AirPlay, Miracast, Cast or DLNA — an open session does not resume, the sender has to reconnect. |
| 11 | A memory leak audit was performed | no leak over a long session | **NOT MEASURED** | No leak audit exists in this cycle. The only structural evidence is the bounded frame queue (scenario 1) and the released codecs in `AudioPlaybackEngine` / `MirrorStreamServer`. |

**Edge cases exercised:**

| Case | Expected | Result |
|---|---|---|
| One mDNS service fails to register | the other one keeps advertising; the failed one is retried | **PASS** — `MdnsRegistrationTrackerTest > a failure does not unregister the sibling` |
| A registration succeeds late, after a failure was already recorded | the late success is honoured | **PASS** — `MdnsRegistrationTrackerTest > late success is still honoured` |
| A stale attempt reports a failure after a newer attempt succeeded | the stale failure is ignored | **PASS** — `MdnsRegistrationTrackerTest > failure of an outdated attempt is ignored` |
| A frame larger than the whole byte budget arrives | it is refused, the queue does not grow | **PASS** — `MirrorFrameQueueTest > an item that is bigger than the whole budget is refused` |
| The producer offers faster than the consumer drains | the oldest frames are dropped, the producer never blocks | **PASS (in isolation)** — `MirrorFrameQueueTest > offer drops the oldest frames first`, `offer never blocks while the budget is permanently exceeded`, `the byte budget holds while a producer and a consumer run at the same time` |
| The network flaps repeatedly | no re-advertisement storm | **PASS (code)** — `NetworkRecoveryMonitor` re-advertises only after the last network was actually lost; the fix is documented in `CHANGELOG.md` under 1.0.0-beta.3 ("mDNS churn on network transitions") |
| The DLNA receiver is the only one still bound after a network outage | — | **PASS (code)** — milestone M13 gave the DLNA renderer a re-advertisement path (`DlnaReceiver.readvertise`, `SsdpAdvertiser.readvertise`); the FR-61 deviation is closed. Not measured on a device. |

---

## 2. Technical

- Architecture boundaries respected (the protocol stacks stay independent): **yes** —
  the recovery monitor lives in the service layer
  (`app/src/main/kotlin/com/phairplay/service/NetworkRecoveryMonitor.kt`, 128 lines)
  and calls into the receivers; no protocol stack knows about another.
- The file-size rule respected (≤ 400 lines soft, ≤ 550 hard): **yes** — the
  touched files in this milestone are all small: `NetworkRecoveryMonitor.kt` 128
  lines, `MdnsRegistrationTracker.kt` (in `airplay/`), `MirrorFrameQueue.kt`.
  The repository-wide check is green as well: `bash scripts/check-file-size.sh`
  → exit 0, `0 warning(s) above the soft limit of 400 lines`.
- Every new class has a KDoc header: **yes** for the production classes —
  `NetworkRecoveryMonitor.kt` lines 10–32, `MirrorFrameQueue.kt` lines 3–30.
  The KDoc gate reports no violation in these files, and the gate is green on
  the whole tree: `bash scripts/check-kdoc-headers.sh` → exit 0,
  `OK: KDoc header check passed — 227 class declaration(s) checked, all of them
  have a KDoc header.`
- Every new public method has a test: **yes** for the two testable units
  (`MdnsRegistrationTracker`, `MirrorFrameQueue`); the device-dependent paths
  (`NetworkRecoveryMonitor`) have none and cannot have any on the JVM.
- Tests green — `./gradlew :test-runner:test`: **yes** — measured on the integrated
  tree: `BUILD SUCCESSFUL in 3m 18s`, **511 tests**, 0 failures, 0 errors, 0 skipped,
  59 test classes. This includes the 10 `MdnsRegistrationTrackerTest` and the 13
  `MirrorFrameQueueTest` cases. The `com.phairplay.service` package still holds its
  35 test methods. Before this cycle the suite had 277 tests.
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
- No new dependency / no new permission: **yes** — no new dependency, no new
  permission in this milestone.

### 2.1 Build log (measured)

```
$ source /workspace/toolchain/env.sh
$ ./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline --console=plain
...
BUILD SUCCESSFUL
```

**Test run output:**

```
BUILD SUCCESSFUL in 3m 18s
511 tests, 0 failures, 0 errors, 0 skipped (59 test classes)
```

The test run and the APK build above describe the integrated tree (the merge of
all eight work streams plus the three security fixes). Before this cycle the
suite had 277 tests.

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
| Peak RAM during one stream | ≤ 150 MB (NFR-03) | **not measured** | — |
| Average CPU | ≤ 30 % (NFR-04) | **not measured** | — |
| Frame rate | ≥ 25 fps (NFR-02) | **not measured** | — |
| Video latency | ≤ 100 ms (NFR-01) | **not measured** | — |
| A/V drift | ≤ 40 ms | **not measured** | — |
| Re-advertisement after a network outage | ≤ 5 s | **not measured** | — |
| Mirroring frame queue | ≤ 90 items **and** ≤ 32 MB | **PASS (unit test)** — `MirrorFrameQueueTest` asserts both bounds; the constant in the code is `DEFAULT_MAX_BYTES = 32L * 1024 * 1024` | — |

**Reference device / input:** none.

**Measurement commands used:**

```bash
# device test plan case DT-15 (needs a Google TV and a macOS sender):
adb shell dumpsys meminfo com.phairplay.googletv
adb shell top -p $(adb shell pidof com.phairplay.googletv)
adb logcat -d -s AndroidRuntime:E
```

> Only one performance figure of M8 is measured: the frame queue budget, and it
> is measured by a unit test on the JVM, not on a device. Every number that
> describes the running product is **not measured**.

---

## 4. Usability

- Step count from "I want to mirror" to "it works": **not measured** — no device.
- Discoverability: not a discoverability feature; the stability behaviour is
  invisible to the user when it works.
- The screen tells the truth: **partly**. The service card reports the real
  protocol state (`HomeFragment.kt` collects four `StateFlow`s). The mDNS failure
  state was explicitly fixed in 1.0.0-beta.3 ("a permanently failing advertisement
  ends in an explicit error state instead of silently disappearing from every
  AirPlay picker"). The **DLNA** half of the recovery is missing (scenario 4), so
  the DLNA card can stay in a stale state after a network outage — the card does
  not lie about the *advertising* state, but it does not learn about the outage.
- The error state is understandable: the error text is a resource string
  (`protocol_state_error`, `protocol_detail_*`), identical in en / de / fr.
- Visual / polish issues seen: **not assessed** — no device.
- Strings: all user-visible texts are in resource files.

---

## 5. Accessibility

- D-pad navigation: **not measured** — no device.
- Focus target size ≥ 48 dp (NFR-07): **not measured**.
- Text size ≥ 18 sp for body, ≥ 32 sp for titles (NFR-08): **not measured**.
- Contrast readable from 3 metres (NFR-09): **not measured**.
- TalkBack / screen reader: **not measured**.
- What is **not** covered yet: the accessibility verification of the Home screen
  protocol cards under an error state. There is no M8-specific accessibility code;
  the error state only changes a text and a colour.

---

## 6. Security and privacy

- New network input (a new parser, a new field, a new port): **no** — the
  recovery monitor adds no parser and no port; it only re-triggers the existing
  advertisements.
- New stored data: **no**.
- New outbound connection: **no** — the monitor re-registers the two mDNS
  services over the local network.
- New permission: **no** — `ACCESS_NETWORK_STATE` was already declared for the
  `ConnectivityManager.NetworkCallback` (see the permission audit,
  `docs/audit/2026-10-24-security-audit.md` §4).
- Negative tests added: **yes, for the parts that can be tested on the JVM** —
  the mDNS tracker has ten tests covering the failure, the late success and the
  stale attempt. There is no negative test for a network flap, because that is
  device behaviour.
- The log reduction still applies to every new log statement (SEC-07)? **yes**.
- Known findings from the audit that still apply to this feature: **SEC-07**
  (a release build logs nothing — the reason the recovery cannot be verified
  from a release build's logcat).
- Privacy impact: does anything new leave the local network? **It must not.** The
  recovery only re-announces the device on the LAN.

---

## 7. Interoperability and data integrity

| Sender | Version | Protocol | Result |
|---|---|---|---|
| macOS | — | AirPlay 2 | **NOT MEASURED** — no device, no sender. The re-advertisement path is proven by the unit test, the end-to-end recovery is not. |
| iPhone / iPad | — | AirPlay 2 | **NOT MEASURED** — as above |
| Windows | — | Miracast (WFD) | **NOT MEASURED** — Miracast has no re-advertisement path either; it is a Wi-Fi Direct service, not an mDNS one |
| Windows | — | DLNA / UPnP | **NOT MEASURED** — the DLNA re-advertisement path exists since milestone M13; the end-to-end recovery on a real device is not measured |
| VLC | — | DLNA / UPnP | **NOT MEASURED** — as above |
| Chrome | — | Google Cast | **NOT MEASURED** |

- Round trip: does the state come back correctly? **partly** — the tracker state
  is proven by unit tests; the receiver state after a flap is not.
- Does an unsupported format fail cleanly instead of crashing? **yes (code)** —
  a failed registration ends in `ProtocolState.ERROR`, not in an exception.
- Recovery: does the receiver come back to a usable state after an interruption?
  **partly** — proven for the mDNS bookkeeping, unproven for the running device.

---

## 8. Known limitations

| Limitation | Reason | Where it is documented |
|---|---|---|
| The DLNA receiver did not re-advertise after a network outage | Only the AirPlay mDNS path had a recovery hook | **Closed in milestone M13** — `docs/spec/REQUIREMENTS.md` FR-61 (deviation removed). The residual risk is covered by R-12 (no device test has been executed). |
| Miracast does not re-advertise either | It is a Wi-Fi Direct service, not an mDNS one; a separate path does not exist | `docs/spec/REQUIREMENTS.md` §3 |
| There is no 30-minute, 1-hour or 24-hour endurance test result | No device was available in any cycle so far | This document; `docs/guides/DEVICE_TEST_PLAN.md` case DT-15 |
| There is no memory leak audit | No device, no profiler run | This document, section 1, scenario 11 |
| The queue budget is proven in isolation, not in the integration | The `MirrorStreamServer → MirrorFrameQueue → Decoder` path needs a device | The QA acceptance record `docs/acceptance/QA-QUALITY-GATE-ACCEPTANCE.md` §5.4 |

---

## 9. Decision

**accepted with documented limitation**

**Decided by:** Project Manager, on behalf of the owner
**Date:** 2026-10-24

M8 is the milestone with the **largest gap between "implemented" and "proven"**
in the whole plan. The two units that can be proven on the JVM are proven and
green: the frame queue budget and the mDNS registration bookkeeping. Everything
that describes the *running product* — 30 minutes without a crash, RAM, CPU,
re-advertisement after a flap, ports released on Stop — is **not measured**,
because no device was available in this cycle.

The milestone therefore cannot be called "complete". It is **partially
implemented, partially proven**, and the remaining part needs hardware, not code.

The one **functional** gap that is not a measurement gap: the DLNA receiver has
no re-advertisement path (FR-61). That is a small change with a clear
specification; it is listed as a follow-up.

The second pass closed the two pipeline gates that were red in the previous
round: the CI quality gate is green (all four structure checks exit 0) and the
lint is green for **both** flavours. That changes the standing of the milestone
in one respect only: the pipeline can now carry the code. The distance to a
"stable" claim is unchanged — it is a hardware distance, not a pipeline
distance.

**Follow-up (open, because …):**

1. **Run device test plan cases DT-02, DT-13, DT-15** on a Google TV **and** a
   Fire TV and fill in the result sheet (section 6 of the plan). Reason: the
   30-minute stability, the memory and the CPU numbers of M8 are only produced
   there.
2. **Add a re-advertisement path for the DLNA receiver** (`DlnaReceiver` has no
   `readvertise`). Reason: FR-61 requires every enabled receiver to re-advertise
   within 5 s; the deviation is documented but not implemented.
3. **Decide whether an endurance test (8 h / 24 h) is required for v1.0.0.**
   Reason: the quality model names 30 minutes; a "stable" release claim should
   say which duration it covers.
4. **Perform a memory leak audit** once a device is available. Reason: no
   profiler run exists.

---

## 10. Release gate

| # | Gate | Result |
|---|---|---|
| 1 | `./gradlew :test-runner:test` is green | **yes** — measured on the integrated tree: **511 tests, 0 failures, 0 errors, 0 skipped** in 59 test classes (`BUILD SUCCESSFUL in 3m 18s`). Before this cycle the suite had 277 tests. |
| 2 | Lint and both debug APKs are green | **yes** — the lint is green for both flavours (`:app:lintGoogletvDebug` and `:app:lintFiretvDebug`, `BUILD SUCCESSFUL`, both reports `No issues found.`); both debug APKs build (measured) |
| 3 | **No known S4 finding** | **yes for this milestone** — the M8 surface has no S4 finding. SEC-03 (the unbounded FU-A buffer) touched the AirPlay RTP path and is now **closed and measured** (`RtpInterleaved.MAX_FUA_BYTES`, 8 MB, plus negative tests); see the risk register and the status block in `docs/audit/2026-10-24-security-audit.md` |
| 4 | Every open S3 has a written owner decision | **yes** — SEC-07 has a written decision (audit §7) |
| 5 | The device scenarios ran on both flavours | **no** — DT-02, DT-13 and DT-15 have not been executed |
| 6 | The file-size rule holds for the touched files | **yes** — all touched files are well below 400 lines; `bash scripts/check-file-size.sh` → exit 0 with `0 warning(s) above the soft limit of 400 lines` |
| 7 | The documentation is up to date (same change) | **yes** — the FR-61 deviation is written down in `docs/spec/REQUIREMENTS.md` |
| 8 | The limitations in `README.md` are true of this build | **yes** — the README does not claim a measured endurance time |

**CI quality gate** (the job `quality-gate` of `.github/workflows/ci.yml`, not a
separate gate in `docs/quality/QUALITY.md` §7): **green** — all four structure
checks exit 0 (section 2.1).

