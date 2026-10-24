# Acceptance — <FEATURE / MILESTONE / RELEASE>

> Copy this file, rename it to
> `<FEATURE>-ACCEPTANCE.md` (for example `M12-ACCEPTANCE.md` or
> `DLNA-D3-ACCEPTANCE.md`) and fill it in. Do not edit this template in place.
>
> **Rules for filling it in**
>
> - Every row needs a **measured** result. "Looks good" is not a result.
> - A test that could not be run says so explicitly and names the command that
>   has to be run. An unrun test is **not** a passed test.
> - A criterion that fails stays in the table with the failure recorded. Do not
>   delete it.
> - Every command in this document has to be the real one from
>   [`../TESTING.md`](../TESTING.md) or from `.github/workflows/`.

---

**Feature / milestone:** <!-- e.g. DLNA phase D3, or release 1.0.0-beta.4 -->
**Requirement IDs:** <!-- FR-xx / NFR-xx, or "none — no behaviour change" -->
**Build / commit:** <!-- the commit sha that was tested -->
**Flavour(s) tested:** <!-- googletv / firetv / both -->
**Device(s) tested:** <!-- the model, the Android version, the Wi-Fi standard -->
**Sender(s) tested:** <!-- macOS 14.5, iOS 18, Windows 11, VLC 3.0 … -->
**Tester / roles:** <!-- who did it -->
**Date:**

---

## 1. Functional

Does the feature do what the requirement says?

| # | Scenario | Expected | Result | Evidence |
|---|---|---|---|---|
| | <!-- e.g. "Windows 'Cast to device' finds the television" --> | | | <!-- the test name, the log excerpt, the file name --> |
| | | | | |

**Edge cases exercised:**

| Case | Expected | Result |
|---|---|---|
| <!-- e.g. "a second sender connects while a session runs" --> | | |
| <!-- e.g. "the sender disconnects in the middle of a frame" --> | | |
| <!-- e.g. "a malformed request arrives" --> | | |

---

## 2. Technical

- Architecture boundaries respected (the protocol stacks stay independent): yes / no
- The file-size rule respected (≤ 400 lines soft, ≤ 550 hard): yes / no
- Every new class has a KDoc header (what / why / example): yes / no
- Every new public method has a test: yes / no
- Tests green — `./gradlew :test-runner:test`: yes / no / **not run**
- Lint clean — `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug`: yes / no / **not run**
- Both flavours build — `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug`: yes / no / **not run**
- No new dependency / no new permission: yes / no — if no, which and why:

**Test run output (paste the summary line, not the whole log):**

```
<!-- e.g. "BUILD SUCCESSFUL — 412 tests completed, 0 failed" -->
```

---

## 3. Performance

| Metric | Budget | Measured | Result |
|---|---:|---:|---|
| <!-- e.g. RAM peak --> | <!-- 150 MB --> | | |
| <!-- e.g. CPU average --> | <!-- 30 % --> | | |
| <!-- e.g. frame rate --> | <!-- ≥ 25 fps --> | | |
| <!-- e.g. video latency --> | <!-- ≤ 100 ms --> | | |
| <!-- e.g. A/V drift --> | <!-- ≤ 40 ms --> | | |

**Reference device / input:** <!-- the TV model, the sender, the Wi-Fi band -->

**Measurement commands used:**

```bash
<!-- the exact commands from ../TESTING.md, e.g. -->
<!-- adb shell dumpsys meminfo com.phairplay.googletv -->
```

> If nothing was measured, write **"not measured"** in the table. Do not leave
> a cell empty and do not estimate.

---

## 4. Usability

- Step count from "I want to mirror" to "it works": <!-- count -->
- Discoverability: can a first-time user find the feature without a manual?
- The screen tells the truth: does the status card show the real state of the receiver?
- The error state is understandable: what does the user see when it fails, and is the message actionable?
- Visual / polish issues seen:
- Strings: are all user-visible texts in a resource file (no hardcoded strings)?

---

## 5. Accessibility

- D-pad navigation: every interactive element reachable, focus always visible?
- Focus target size ≥ 48 dp (NFR-07)?
- Text size ≥ 18 sp for body, ≥ 32 sp for titles (NFR-08)?
- Contrast readable from 3 metres (NFR-09)?
- TalkBack / screen reader:
- What is **not** covered yet:

> Accessibility is not postponed until the release. If a part is not done, say
> which part and why.

---

## 6. Security and privacy

- New network input (a new parser, a new field, a new port):
- New stored data (what, where, how long):
- New outbound connection (where does it go?):
- New permission (which one, traced to which call site?):
- Negative tests added (the test that proves the guard rejects the attack):
- The log reduction still applies to every new log statement (SEC-07)?
- Known findings from the audit that still apply to this feature: SEC-xx, SEC-yy
- Privacy impact: does anything new leave the local network? **It must not.**

---

## 7. Interoperability and data integrity

| Sender | Version | Protocol | Result |
|---|---|---|---|
| <!-- macOS --> | | AirPlay 2 | |
| <!-- iPhone / iPad --> | | AirPlay 2 | |
| <!-- Windows --> | | Miracast (WFD) | |
| <!-- Windows --> | | DLNA / UPnP | |
| <!-- VLC --> | | DLNA / UPnP | |
| <!-- Chrome --> | | Google Cast | |

- Round trip: does the state come back correctly (transport state, position, metadata)?
- Does an unsupported format fail cleanly instead of crashing?
- Recovery: does the receiver come back to a usable state after an interruption?
- For DLNA specifically: was a **network-reachable `http`/`https`** URL used? A `file:///` URL points at the *sender's* disk and cannot be read by the television — that is a property of the protocol, not a bug.

---

## 8. Known limitations

What does **not** work, and what is the reason? Every entry here must also be
visible where a user will meet it — in `README.md` or in the matching guide.

| Limitation | Reason | Where it is documented |
|---|---|---|
| | | |

---

## 9. Decision

**accepted** / **rejected** / **accepted with documented limitation**

**Decided by:** <!-- the role -->
**Date:**

**Follow-up (open, because …):**
<!-- list the open points with their reason. An open point without a reason is
     an excuse; an open point with a reason is a plan. -->

---

## 10. Release gate

Every box has to be checked before a release can be cut. The gate itself is
defined in [`../quality/QUALITY.md`](../quality/QUALITY.md) §7.

| # | Gate | Result |
|---|---|---|
| 1 | `./gradlew :test-runner:test` is green | |
| 2 | Lint and both debug APKs are green | |
| 3 | **No known S4 finding** | |
| 4 | Every open S3 has a written owner decision | |
| 5 | The device scenarios ran on both flavours | |
| 6 | The file-size rule holds for the touched files | |
| 7 | The documentation is up to date (same change) | |
| 8 | The limitations in `README.md` are true of this build | |
