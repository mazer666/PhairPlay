# QA Acceptance Record — Device Test Plan

- **Milestone:** M6–M12, round 2 — "Quality gate: check scripts, CI workflow, language completeness, device test plan"
- **Scope:** `docs/guides/DEVICE_TEST_PLAN.md`
- **Tester:** QA & Test Engineer
- **Date:** 2026-10-24
- **Build/commit:** the working directory `feature/quality-m6-m12` (commit hash **not measured** — `git` was not executable in this session, see section 8)
- **Requirement IDs:** `docs/CONTRIBUTING.md` ("Acceptance criteria must be measurable — 'Manual test on device' is only allowed together with an exact script"), the performance benchmark table of `docs/spec/ACCEPTANCE_CRITERIA.md` (frame rate ≥ 25 fps, latency ≤ 100 ms, RAM ≤ 150 MB, CPU ≤ 30 %, connection ≤ 5 s, mDNS visibility ≤ 3 s)

---

## 1. Functional

### 1.1 The plan itself

| Scenario | Expected | Result | Evidence |
|---|---|---|---|
| The plan names every acceptance criterion that needs real hardware | a list with the reason | **PASS** | `docs/guides/DEVICE_TEST_PLAN.md`, section 4 — 24 rows, each with the criterion, the reason and the case that proves it |
| Every case is copy-pasteable | the commands run without editing | **PASS** | 15 cases, each with a *Preconditions / Steps / Expected result / Time limit / Evidence* block; the commands use the shell variables of section 2.4 |
| The commands are correct for this repository | the package names, the ports and the actions are the ones in the code | **PASS** | `com.phairplay.googletv` / `com.phairplay.firetv` from `app/build.gradle.kts`; `com.phairplay.MainActivity` and `com.phairplay.service.PhairPlayService` from `AndroidManifest.xml`; `com.phairplay.action.START/STOP/RESTART` from `PhairPlayService.kt`; RTSP port 7000 (`RtspHandler.RTSP_PORT`), UDP timing 6002 (`TimingHandler.TIMING_PORT`), audio RTP 6001, Miracast WFD RTSP 7236 (`MiracastReceiver.WFD_RTSP_PORT`), SSDP 1900 (`SsdpMessages.PORT`) |
| The VLC renderer test has its own section | start VLC, Playback → Renderer, PhairPlay listed, play a network URL, check the screen | **PASS** | section 3, case **DT-09**, with the ten steps, the expected result and the time limits (renderer listed ≤ 10 s, picture ≤ 5 s, transport commands ≤ 2 s) |
| Every case has a time limit | the owner knows when to stop waiting | **PASS** | 15 cases, each with a *Time limit* line |
| Every case has a result sheet | the owner can report | **PASS** | section 6 — a table with one column per flavour, an evidence column and a notes column; `n/a` needs a written reason |

### 1.2 The cases of the plan

| Case | What it proves | Hardware needed |
|---|---|---|
| DT-01 | install, first start, foreground service, home screen | Google TV + Fire TV |
| DT-02 | start / stop / restart of the receiver | Google TV + Fire TV |
| DT-03 | AirPlay video mirroring from macOS | Google TV or Fire TV + Mac |
| DT-04 | AirPlay audio only, now-playing screen | Google TV or Fire TV + Mac |
| DT-05 | pairing PIN | Google TV or Fire TV + Mac |
| DT-06 | Miracast from Windows | TV + Windows PC |
| DT-07 | Google Cast | Google TV + Chrome device |
| DT-08 | SSDP discovery, three answers | TV + sender computer |
| DT-09 | **VLC renderer** | TV + sender computer with VLC |
| DT-10 | SOAP control, XXE refusal, SSRF refusal | TV + sender computer |
| DT-11 | GENA `SUBSCRIBE` and `NOTIFY` | TV + sender computer |
| DT-12 | German and French user interface | Google TV + Fire TV |
| DT-13 | start on boot | Google TV + Fire TV |
| DT-14 | notification and its actions | Google TV + Fire TV |
| DT-15 | debug overlay, memory and stability | Google TV + Fire TV |

---

## 2. Technical

- Architecture boundaries respected: **yes** — the plan is a document, it
  changes no code and no build file.
- Central config respected: **yes** — the time limits come from the
  performance benchmark table of `docs/spec/ACCEPTANCE_CRITERIA.md`; the
  package names come from the build files.
- Tests green: **not measured** — see section 8.
- Migration/versioning checked: **not applicable**.

---

## 3. Performance

| Metric | Budget | Measured | Result |
|---|---:|---:|---|
| mDNS visibility | ≤ 3 s | **not measured** (needs a device) | open, DT-03 |
| Connection time | ≤ 5 s | **not measured** (needs a device) | open, DT-03 |
| Video frame rate | ≥ 25 fps | **not measured** (needs a device) | open, DT-15 |
| Video latency | ≤ 100 ms | **not measured** (needs a device) | open, DT-15 |
| RAM usage (peak) | ≤ 150 MB | **not measured** (needs a device) | open, DT-15 |
| CPU usage (average) | ≤ 30 % | **not measured** (needs a device) | open, DT-15 |

The budgets are taken from the benchmark table of
`docs/spec/ACCEPTANCE_CRITERIA.md`; **no value was measured in this
session**, because no device was available to the QA agent.

---

## 4. Usability

- task step count: **one command** per case (`adb connect`, then the commands
  of the case).
- discoverability notes: the plan starts with the hardware and software
  list, then the shell variables, then the cases.
- mobile/native behaviour: not applicable.
- visual/polish issues: the plan asks for a screenshot per case, so a visual
  defect cannot be lost between the TV and the record.

---

## 5. Accessibility

- keyboard: not applicable.
- VoiceOver/TalkBack: not applicable — the plan uses the remote control
  (`adb shell input keyevent`), not a keyboard.
- scaling/high contrast: not applicable.

---

## 6. Security/privacy

- new data stored: **none**.
- network access: the plan runs the receiver on the local network of the
  tester; **no internet access is needed** and no data leaves the LAN.
- negative tests: case **DT-10** sends an XXE payload and an SSRF payload to
  the running receiver and expects a SOAP fault without internal details; case
  **DT-11** expects a subscription to a public callback address to be refused.
- threat notes: the payloads in DT-10 and DT-11 are the attack cases of the
  security audit — they must be run against the device, because the JVM tests
  prove the parser, not the device's network stack.

---

## 7. Interoperability/data integrity

- reopen in the reference project: not applicable.
- external viewers/editors: **this is the core of the plan** — VLC is the
  external control point of DT-09 and DT-10.
- roundtrip: not applicable.
- recovery/save integrity: not applicable.

---

## 8. Known limitations

1. **No device was available to the QA agent in this session.** The plan is a
   script, not an execution. Every case is **open**. The owner runs it on a
   Google TV and a Fire TV and fills in section 6 of the plan.
2. **The plan is written against the code of this cycle.** The file names of
   the packages are stable, but the ports of the DLNA description server are
   ephemeral (`ServerSocket(0)`), so the tester has to read the port from the
   `LOCATION` header of the SSDP answer — the plan says so in DT-08 and
   DT-10.
3. **`docs/spec/ACCEPTANCE_CRITERIA.md` has no acceptance criterion for the
   DLNA MediaRenderer, Miracast, Google Cast, the pairing PIN, the German and
   French user interface, "start on boot" and the notification.** The plan
   uses the identifiers DT-01 … DT-15 and lists the gap in its section 4.
   The spec track has to add the missing criteria; until then the results of
   the plan cannot be mapped onto an approved criterion.
4. **The test run of the JVM suite was not executed in this session.** The
   figures handed over by the team lead are: baseline before this cycle
   **277 tests, 0 failures, 0 skipped**; after the DLNA/security/AirPlay wave
   **427 tests, 22 failures** — the failures were still being worked on when
   this task started. **This milestone does not claim 427/0.**
5. **The Fire TV flavour cannot be tested for Miracast on every device** —
   Wi-Fi Direct is missing on many Fire TV builds. The plan asks for the
   reason in the notes column instead of a false failure.

---

## 9. Decision

**accepted as a plan — the execution is open**

The plan turns every "manual test on device" into a copy-pasteable script
with an expected result and a time limit, as the project rules demand. It
does **not** claim that any of the 15 cases passed: no hardware was available.

---

## 10. Changed files

| File | Purpose |
|---|---|
| `docs/guides/DEVICE_TEST_PLAN.md` | new — 15 device cases, the hardware matrix, the log collection and the result sheet |
| `docs/acceptance/QA-DEVICE-TEST-ACCEPTANCE.md` | this record |
