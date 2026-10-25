# PhairPlay Quality Model

PhairPlay is not "done" because the happy path works once. It is a **receiver**:
it sits on a home network, parses bytes from strangers, and runs on a television
with little RAM. A feature is accepted when it holds up across six dimensions.

This document defines the model: **how each dimension is measured**, **what the
state is today**, and **what the target is**. The state below was measured on
**2026-10-24** on the working tree of `main` — the version `1.0.0-beta.3` plus
the unreleased changes of the current cycle.

> **Every number in this document is a measurement, and the command that
> produced it is named next to it.** A number without a command is a guess,
> and a guess does not belong in this file. When the tree changes, the tables
> are re-measured — a stale number is worse than no number.

Three other documents own the details and are linked rather than repeated here:

- the requirements and the performance budgets —
  [`docs/spec/REQUIREMENTS.md`](../spec/REQUIREMENTS.md) and
  [`docs/spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md)
- the security findings —
  [`docs/audit/2026-10-24-security-audit.md`](../audit/2026-10-24-security-audit.md)
- the release checklist —
  [`docs/project/RELEASE_READINESS.md`](../project/RELEASE_READINESS.md)

---

## 0. The severity scale (S0 – S4)

Every defect, every audit finding and every review comment is graded on this
scale. The scale is the project's own; the security audit uses its own
wording, and the mapping is fixed so that both mean the same thing.

| Grade | Name | What it means | Release rule |
|---|---|---|---|
| **S4** | Critical | Remote code execution; an arbitrary-URL fetch that turns the television into a request proxy; data loss; a security bypass; a crash that any device in the network can trigger at will | **No release may ship with a known S4 finding.** |
| **S3** | Major | Wrong output; an interoperability failure; a major performance regression; a defect that breaks the core workflow in a common case | Needs a written decision by the owner in the release notes |
| **S2** | Significant | The core workflow is degraded or confusing; an edge case is broken | May ship with a documented entry |
| **S1** | Minor | A small inconvenience; a safe workaround exists | May ship |
| **S0** | Cosmetic | No functional impact | May ship |

### Mapping to the security audit

The audit grades its findings as High / Medium / Low / Info. The correspondence
is fixed:

| Audit grade | Quality grade |
|---|---|
| High | **S4** |
| Medium | **S3** |
| Low | **S2** |
| Info | **S1** (an *accepted* Info finding stays accepted; the grade does not reopen it) |
| — | **S0** for a purely cosmetic defect that is not a finding at all |

### The two release rules

1. **No release with a known S4 finding.** Not "no release with an *unfixed*
   S4 finding" — a known S4 blocks the release. Either it is fixed, or the
   owner accepts it in writing in the release notes, and then it is no longer
   *known and unaccepted*.
2. **A finding that is *accepted* is not reopened by a new grade.** The audit's
   list of accepted residual risks stays accepted until the owner changes it.
   That is why an "accepted, Info" finding is S1 and not a release blocker.

**Status of the rule on 2026-10-24: the scale is unchanged, and the number of
open S4 findings is zero.** The three High findings of the audit (SEC-01,
SEC-02, SEC-03) are closed in the code — see section 2. The rule itself is
not relaxed: the next High finding blocks the next release exactly as these
three did.

---

## 1. Correctness

> *Does the receiver do what the requirement says, with the edge cases the
> requirement names?*

### How it is measured

| Method | Command / place | What it proves |
|---|---|---|
| The JVM protocol suite (the CI gate) | `./gradlew :test-runner:test` | The parsers, the protocol state machines and the crypto behave as specified, on the host JVM |
| The Android lint gate | `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug` | No correctness defect that the static analyser can see, in either flavour |
| The build gate | `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug` | Both flavours compile into an installable APK |
| The repository structure gate | the four scripts in `scripts/` | The rules of this model are mechanically enforced, not reviewed by hand |
| Device scenarios | [`docs/guides/DEVICE_TEST_PLAN.md`](../guides/DEVICE_TEST_PLAN.md) (15 cases) and [`docs/TESTING.md`](../TESTING.md) (scenarios 1–7) | The behaviour on real hardware, in the order a user meets it |
| The acceptance records | [`docs/acceptance/`](../acceptance/) | The criterion ID, the measured result and the evidence |

A criterion counts as passed only when it has a **measured** result. "Looks
right on my TV" is not a result.

### Current state (measured 2026-10-24)

| Fact | Value | Source |
|---|---|---|
| Tests in the CI gate | **549** — **0** failures, **0** errors, **0** skipped | `./gradlew :test-runner:test` → the report in `test-runner/build/reports/tests/test/index.html` |
| Test classes in that run | **59** | the same report |
| The same gate before this cycle | **277** tests — the cycle added **234** | the earlier measurement of the same gate |
| Test source files under `app/src/test/kotlin/com/phairplay/` | **68** | `find app/src/test/kotlin/com/phairplay -name "*.kt"` |
| Test files **excluded from the CI gate** | **6** — `MainActivityTest.kt`, `airplay/VideoSubmissionTest.kt`, `airplay/MdnsServiceTest.kt`, `service/ServiceControllerTest.kt`, `util/NetworkUtilsTest.kt`, `ui/VideoAspectFitTest.kt` | the `exclude` block in `test-runner/build.gradle.kts` |
| Why they are excluded | They touch Android framework classes that call JNI and are therefore unstable on the host JVM | the comment above the `exclude` block in the same file |
| Instrumented test files | **1** (`app/src/androidTest/kotlin/com/phairplay/MainActivityTest.kt`) | the `androidTest` source set |
| Android lint | **green for both flavours** — `No issues found.` for `lintGoogletvDebug` and for `lintFiretvDebug` | `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug` |
| Debug APKs | **both build** — Google TV and Fire TV | `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug` |
| CI gate | `./gradlew :test-runner:test` — see `.github/workflows/ci.yml` | the workflow file |
| Real-device validation | **not measured** — **0 of the 15** cases of the device test plan have been executed, because no television was available in the working environment | [`docs/guides/DEVICE_TEST_PLAN.md`](../guides/DEVICE_TEST_PLAN.md) |

The 6 excluded files are a known, documented gap. They are not failures — they
are tests that only run on a device. The consequence is honest and has to be
stated: **a green CI run does not mean the UI layer and the mDNS registration
are covered.**

### Target

- Every requirement of the changed area has a test that runs in the CI gate,
  except where the exclusion is documented in `test-runner/build.gradle.kts`
  with a reason.
- The 6 excluded test files run on a device before a release, and their result
  is written into the acceptance record.
- No criterion is marked passed without a measured result.

---

## 2. Security and privacy

> *Can a device in the home network make the receiver do something it should
> not, and does the receiver leak anything it should not?*

### How it is measured

| Method | Where | What it proves |
|---|---|---|
| The security audit | [`docs/audit/2026-10-24-security-audit.md`](../audit/2026-10-24-security-audit.md) | The threat model, the attack surface, every finding with its attack path, its concrete fix and its negative test |
| The hardening guarantees | [`SECURITY.md`](../../SECURITY.md) §2 | The guarantees the code actually enforces |
| The data-protection statement | [`PRIVACY.md`](../../PRIVACY.md) | What leaves the television, what is stored, which permission is needed for what |
| The permission audit | the audit, §4 | Every line of `AndroidManifest.xml` traced to a call site |
| The negative tests | `app/src/test/kotlin/com/phairplay/` | That the guard rejects the attack, not only that it accepts the normal case |

### Current state (measured 2026-10-24)

| Fact | Value |
|---|---|
| Findings in the audit | **14** — 3 High, 4 Medium, 4 Low, 3 Info |
| Open **S4** findings | **0** — SEC-01, SEC-02 and SEC-03 are closed |
| Still open | **SEC-04** (S3 — the *scheme* list of the DLNA media URI; the address part is closed), **SEC-06** (S3 — the Miracast permissions: the manifest part is closed, the decision between requesting the permission and dropping the feature belongs to the owner), **SEC-10** (S2 — cleartext HTTP; whether the platform really blocks it has not been verified on a device) |
| Accepted residual risks | **7** (audit §7) — each with a written reason |
| Central log reduction | present and active in **every** build (`util/LogRedactor`, `util/LogRedactionMode`, `util/Logger`) |
| Telemetry, analytics, crash reporter | **none** — there is no code path that leaves the local network |

**The three High findings and how they were closed**

| Finding | The attack | The fix in the code | The negative test |
|---|---|---|---|
| **SEC-01** | A single `POST /play` from any device in the network aimed the television at an arbitrary address — a router admin page, a NAS, a printer — and played the answer on the screen; a `file://` URL turned the receiver into a file reader | `util/FetchableUrlPolicy` (scheme allow-list `http`/`https`, no control characters, ≤ 4096 characters) plus `util/HostAddressPolicy` (the loopback, the link-local range, multicast, broadcast, the obfuscated address notations and the user-info trick are refused). A refused URL is answered with `400 Bad Request` and the player never fetches it | `util/FetchableUrlPolicyTest`, `util/HostAddressPolicyTest` |
| **SEC-02** | A photo that is small on the wire but enormous once decoded (20 000 × 20 000 px = 1.6 GB of pixels) killed the app with an `OutOfMemoryError` | `util/SafeBitmapDecoder` — the header is read first, the picture is refused if it does not fit into the pixel budget, and the rest is downsampled with an `inSampleSize` | `util/SafeBitmapDecoderTest` (the budget is proven; the decoding itself needs a device — see the limitation below) |
| **SEC-03** | A peer opened a fragmented H.264 unit and never sent the end bit, so the reassembly buffer grew until the process died | `RtpInterleaved.MAX_FUA_BYTES` (8 MB) caps the reassembled unit, the check runs **before** the append, and a fragment that would cross the cap is discarded together with the incomplete unit | `airplay/RtpFuaReassemblyTest` |

**SEC-04 is partly closed.** The *address* of the DLNA media URI is checked
now (`HostAddressPolicy`). What stays open is the **scheme** list — `DlnaMediaUri`
still accepts `file`, `smb`, `ftp` and others — and the two residual points:
the accepted **public addresses** and the **host names**, which are not
resolved. That is an S3 and needs a written decision by the owner.

> ### Consequence for the next release
>
> **No S4 finding is open.** The release rule in section 0 is met at the
> critical level for the first time in this project.
>
> That does **not** make the build release-ready. Open are: the owner
> decision for SEC-04 (S3), the open findings SEC-06 (S3) and SEC-10 (S2), and
> the fact that **no device test has been executed at all** (0 of 15). The
> honest status of a release today is "the pipeline is green, the evidence on
> hardware is missing" — not "ready".

### Target

- **Zero open S4 findings** at the moment a release is cut. *(Met on
  2026-10-24 — and it stays a target: the next High finding breaks it.)*
- Every new network-facing parser has a **length, a format and a range** check
  before a value is used, and a negative test that proves it.
- Every byte budget is a **byte** budget, not only an item count.
- No new permission without a traced call site.
- No data leaves the local network — proven, not asserted.

---

## 3. Performance

> *Does the receiver keep up, and does it stay small?*

### How it is measured

Every performance number needs a **measurement on a real television**. The
measurement commands are in [`docs/TESTING.md`](../TESTING.md) and in the
device test plan; the budgets are in NFR-01…NFR-04 and in the benchmark table
of [`docs/spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md).

| Metric | How it is measured | Budget |
|---|---|---|
| Peak RAM during one stream | `adb shell dumpsys meminfo com.phairplay.googletv` | ≤ 150 MB (NFR-03) |
| Average CPU | `adb shell top -p $(adb shell pidof com.phairplay.googletv)` | ≤ 30 % (NFR-04) |
| Frame rate | On-screen frame counter during a 5-minute stream | ≥ 25 fps (NFR-02) |
| Video latency | Stopwatch shown on the sender, read on the TV | ≤ 100 ms (NFR-01) |
| A/V drift | A/V sync test video (clapper board) | ≤ 40 ms (NFR-… / FR-35) |
| Connection time | Stopwatch from the AirPlay click to the first frame | ≤ 5 s |

### Current state (measured 2026-10-24)

| Fact | Value |
|---|---|
| **Measured on a device in this cycle** | **not measured** — no television and no hardware were available in the working environment. **0 of the 15** cases of the device test plan were executed. |
| RAM, CPU, frame rate, latency, A/V drift, connection time | **not measured** — no value is available, and no *measured* value is quoted anywhere in this repository; every number that appears is a budget or is marked "not measured" |
| Bounded memory that *is* implemented and unit-tested | the mirror frame queue (32 MB byte budget, 90-item limit), the reassembled FU-A unit (8 MB), the mirror payload cap (8 MB), the RTP packet cap (2 MB), the RTSP message budget (1 MB), the photo payload cap (25 MB), the SOAP body cap (64 KB) |
| Performance claims in the documentation | every one of them points at a budget and a measurement script — none of them is a "feels fast" |

**The honest statement:** the budgets are defined and the measurement scripts
exist, but the *measured* numbers for this cycle have not been taken. Any
release note that quotes a RAM, CPU, fps or latency number must quote a
measurement, and if there is none, it must say so.

### Target

- A measured record per release, on both flavours, with the device model named.
- No regression against the previous release without a CR.
- A performance claim without a measurement is not a claim — it is a guess, and
  it does not go into the documentation.

---

## 4. Maintainability

> *Can a new contributor — or the owner in six months — find the right file,
> understand it, and change it safely?*

### How it is measured

| Method | How | What it proves |
|---|---|---|
| **File size** | `bash scripts/check-file-size.sh` — the line count of every hand-written Kotlin file | that no file has grown into an unreviewable blob |
| **KDoc headers** | `bash scripts/check-kdoc-headers.sh` — every class has a header with what / why / example | that the intent is readable without reading the body |
| **Documentation links** | `bash scripts/check-docs-links.sh` — every internal Markdown link resolves | that a document does not send the reader into the void |
| **Language coverage** | `bash scripts/check-i18n-completeness.sh` — every key exists in every translation | that no user meets an English string in a German build |
| **Test coverage of the public surface** | every public method has a test (NFR-24) | that the behaviour is pinned down |
| **Package boundaries** | the dependency direction between the packages under `app/src/main/kotlin/com/phairplay/` | that the protocol stacks stay independent |
| **Navigation** | [`docs/WHERE_TO_CHANGE.md`](../WHERE_TO_CHANGE.md) resolves every "I want to change X" | that a newcomer finds the file in one step |
| **Documentation freshness** | the same-change rule in [`docs/DOCUMENTATION_POLICY.md`](../DOCUMENTATION_POLICY.md) | that the documents still describe the build |

### The file-size rule: 400 lines soft, 550 lines hard

> **PhairPlay's own limit is stricter than the reference project it learned its
> documentation structure from. That reference project allows 600 lines as a
> target and 900 as a maximum. PhairPlay allows 400 as a target and 550 as an
> absolute maximum. The stricter rule wins, and it wins on purpose.**

Why:

1. **The code is an attack surface, not just code.** Every one of the
   hand-written Kotlin files parses something that a stranger on the network
   can send. A long file is a long list of branches, and a long list of
   branches is a long list of places where a length check can be forgotten.
   A 400-line file is small enough that a reviewer can hold the whole state
   machine in their head.
2. **The audience is a beginner.** The owner is new to this codebase, and so
   is every external contributor who arrives here. 400 lines is roughly one
   screen of code; 900 lines is four. The smaller limit is what makes
   "read the file" a viable onboarding step instead of a research project.
3. **The rule is mechanically checkable.** A line count can be checked by a
   script, in a second, by anyone. A rule that needs judgement is a rule that
   gets negotiated away in review — and then means nothing.
4. **The codebase has already shown what happens without the limit.** In the
   tree of `1.0.0-beta.3` **6 of the then 82** Kotlin files were over the soft
   limit and **3** were over the hard limit. The limit was written first and
   the code grew past it; those files have been split since (the splits are
   listed in [`CHANGELOG.md`](../../CHANGELOG.md)). The number stays strict so
   that the target is unambiguous.
5. **The hard limit exists for the emergency.** A security patch has to be
   small and fast. The hard limit says: an emergency fix may exceed the soft
   limit, but must never produce a 1000-line file — and it must never be
   blocked by the soft limit either.

**How the limit is applied**

| Range | Meaning | Consequence |
|---|---|---|
| ≤ 400 lines | the target | nothing to do |
| 401 – 550 lines | over the soft limit | allowed, but a review comment; a new change to the file should reduce it |
| > 550 lines | over the hard limit | **blocks the merge** — the file must be split first |

The normative statements are NFR-23 in
[`docs/spec/REQUIREMENTS.md`](../spec/REQUIREMENTS.md) (the 400-line target)
and RULE 1 in [`docs/CONTRIBUTING.md`](../CONTRIBUTING.md) (the merge rule).
This document measures and reports them; it does not restate them as a second
rule.

### Current state (measured 2026-10-24)

| Fact | Value | Source |
|---|---|---|
| Hand-written Kotlin files in the repository | **189** | `bash scripts/check-file-size.sh` → `OK: file size check passed — 189 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.` |
| Files over the 400-line soft limit | **0** | the same output |
| Files over the 550-line hard limit | **0** | the same output |
| The largest file today | `airplay/SdpParser.kt` with **391** lines | the same script, sorted |
| Classes with a KDoc header | **233 of 233** | `bash scripts/check-kdoc-headers.sh` → `OK: KDoc header check passed — 233 class declaration(s) checked, all of them have a KDoc header.` |
| Internal documentation links that resolve | **all** of them — 281 at the time of the measurement | `bash scripts/check-docs-links.sh` → `OK: documentation link check passed — 281 internal link(s) resolve, 19 external link(s) skipped.` |
| String keys in the language coverage | **80** — complete in `values/`, `values-de/` and `values-fr/` | `bash scripts/check-i18n-completeness.sh` → `OK: i18n completeness check passed — 114 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).` |

**The link count is a snapshot, the exit code is the contract.** Every document
that is added or extended adds links, so the number above grows with every
change in the repository. What has to stay true is that the script exits 0 —
that is, every internal link resolves.

**One exception, on purpose.** The CI runs the KDoc check with
`--skip-nested`: a class that is nested inside another class is documented by
the KDoc header of the class that contains it, and the check counts 208
declarations instead of 233. The strict run (without the flag) is green as
well — both exit codes are recorded in
[`docs/acceptance/QA-QUALITY-GATE-ACCEPTANCE.md`](../acceptance/QA-QUALITY-GATE-ACCEPTANCE.md).

**What the current cycle added** — five new classes in
`app/src/main/kotlin/com/phairplay/util/`, all of them inside the size and
KDoc rules:

| File | Lines | What it does |
|---|---|---|
| `util/HostAddressPolicy.kt` | 170 | the address rules for a URL that the receiver fetches itself |
| `util/FetchableUrlPolicy.kt` | 83 | the scheme, length and character rules for the same URL |
| `util/SafeBitmapDecoder.kt` | 130 | the pixel budget for a decoded picture |
| `util/LogRedactor.kt` | 178 | the central reduction of the log output |
| `util/LogRedactionMode.kt` | 34 | the switch that turns the reduction off for a local debugging session |

**The other structure of the tree**

| Fact | Value |
|---|---|
| Protocol stacks | `com.phairplay.airplay` (+ `.handshake`), `com.phairplay.dlna`, `com.phairplay.miracast`, `com.phairplay.media` |
| Platform code | `com.phairplay.service`, `com.phairplay.settings`, `com.phairplay.util` |
| UI code | `com.phairplay.ui`, `app/src/main/res/` |
| Flavour-specific code | `app/src/googletv/kotlin/com/phairplay/cast/`, `app/src/firetv/kotlin/com/phairplay/cast/` |
| Native code | `app/src/main/cpp/` — the FairPlay JNI bridge, the ALAC JNI bridge and the `playfair/` and `alac/` C sources |

### Target

- **0 files over the hard limit** — the merge gate is non-negotiable. *(Met.)*
- **0 files over the soft limit.** *(Met.)*
- Every new class has a KDoc header that says what / why / how, checked in
  review and by the script.
- Every public method has a test.
- `docs/WHERE_TO_CHANGE.md` resolves every entry to a path that exists.

---

## 5. Interoperability

> *Does a real sender — a real Mac, a real Windows box, a real VLC — actually
> work with it?*

### How it is measured

| Method | How | What it proves |
|---|---|---|
| The device scenarios | [`docs/guides/DEVICE_TEST_PLAN.md`](../guides/DEVICE_TEST_PLAN.md) (15 cases) | that a real sender can complete a real session |
| The protocol suites | `./gradlew :test-runner:test` | that the parser accepts what the specification says and rejects the rest |
| The cross-implementation matrix | the sender column of the acceptance record | which senders were actually tried |
| The phase plan of ADR-004 | [`docs/decisions/ADR-004-dlna-mediarenderer-roadmap.md`](../decisions/ADR-004-dlna-mediarenderer-roadmap.md) | that a protocol is delivered in reviewable, testable steps |

Interoperability is only claimed for a **sender × version × protocol**
combination that was actually tried. A protocol that was not tried on real
hardware is reported as *not validated*, never as *working*.

### Current state (measured 2026-10-24)

| Protocol | State | Validated on real hardware? |
|---|---|---|
| **AirPlay 2** | The complete stack is implemented: mDNS advertising, the RTSP handshake, HomeKit-style pairing, FairPlay key decryption, H.264 mirroring, AAC-ELD / AAC-LC / ALAC audio, NTP A/V sync, the now-playing overlay, the DACP reverse remote, the photo endpoint | **No.** 0 of the 15 device cases were executed — no macOS or iOS sender was tried. The stack is covered by the JVM suite. |
| **UPnP / DLNA** | The discovery and control plane is shipped (ADR-004, phases D1 + D2): SSDP advertising and `M-SEARCH`, the device description server, the SOAP dispatcher, GENA eventing — plus the Samsung Smart View extension (the Samsung search target and the DIAL documents) | **No.** No Windows, VLC or Samsung control point was tried; 0 of the 15 device cases were executed. |
| **Miracast (WFD)** | The Wi-Fi Direct advertisement and the RTSP control plane are implemented; the media decode is not | **Non-functional on Android 10+** — `WifiP2pManager.setWfdInfo()` is a hidden system API. See SEC-06 |
| **Google Cast** | The Cast Connect SDK lifecycle is implemented in the `googletv` flavour | **Not testable end to end** without a registered Cast app ID — see [`docs/guides/CAST_APP_ID.md`](../guides/CAST_APP_ID.md) |

One limitation has to be repeated in every user-facing place, because it is a
property of the protocol and not a bug:

> A UPnP renderer **fetches the media itself**. A `file:///` URI points at the
> *sender's* disk and cannot be read by the television. Rendering a local file
> therefore works only for network-reachable `http` / `https` URLs.

### Target

- Every protocol that is advertised as available has a **named sender** that
  was tested, in the acceptance record, with the version.
- A protocol that is *not* validated says so in the README, in the settings
  screen and in the release notes — in the same words, everywhere.
- The DLNA phases D3 (media playback) and D4 (hardening and real-device
  validation) are completed before the DLNA renderer is advertised as a
  complete feature.

---

## 6. User experience

> *Can a person who has never heard of AirPlay get their screen onto the
> television — and understand what went wrong when it does not work?*

### How it is measured

| Method | How | What it proves |
|---|---|---|
| The step count | the walkthrough in [`docs/TESTING.md`](../TESTING.md) | how many actions separate "I want to mirror" from "it works" |
| The focus order | the D-pad walkthrough on a real TV | that the screen is usable with a remote, not only with a finger |
| The state text | the Home screen cards | that the screen tells the truth about what is running |
| The i18n coverage | the resource folders, checked by `scripts/check-i18n-completeness.sh` | that a German or French user sees German or French, not English |
| The troubleshooting guide | [`docs/guides/TROUBLESHOOTING.md`](../guides/TROUBLESHOOTING.md) | that the common failures have a named cause and a fix |

### Current state (measured 2026-10-24)

| Fact | Value |
|---|---|
| Languages with a resource folder | **3** — English (`values/`), German (`values-de/`), French (`values-fr/`) |
| String keys covered in every language | **80 of 80** — `bash scripts/check-i18n-completeness.sh` exits 0 |
| TV usability rules in the requirements | NFR-05…NFR-10: card-based layout, large focus indicators, D-pad navigation, 48 dp focus targets, 18 sp body text, the status visible within 1 second |
| The step count for the common path | install → start the app → click AirPlay → pick the TV. The user-visible part is two clicks after the app is running — **read from the code, not measured on a device** |
| Focus order, focus visibility, text scaling | **not measured** — the D-pad walkthrough needs a television; 0 of the 15 device cases were executed |
| Accessibility | **not systematically audited** — no automated accessibility scan, no documented TalkBack pass |
| The "wrong state on the screen" defect class | already found and fixed once; see [`docs/reviews/2026-10-24-pr19-pr17-issue18-review.md`](../reviews/2026-10-24-pr19-pr17-issue18-review.md) |

### Target

- Every string is in a resource file, never hardcoded in Kotlin or XML
  (FR-38).
- Every interactive element is reachable with the D-pad, and the focus is
  always visible (NFR-06, NFR-07).
- The status on the Home screen is **true**: a protocol that is disabled, not
  advertising, advertising, streaming or in error says exactly that.
- A user who cannot find the cause of a failure reads the cause in the
  troubleshooting guide.
- An accessibility pass (TalkBack, focus order, text scaling) is documented
  before the 1.0 release.

---

## 7. The release gate

A release can be cut when **all** of the following hold:

| # | Gate | Where it is checked |
|---|---|---|
| 1 | `./gradlew :test-runner:test` is green | `.github/workflows/ci.yml` |
| 2 | `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug :app:assembleGoogletvDebug :app:assembleFiretvDebug` is green | `.github/workflows/ci.yml`, `.github/workflows/lint.yml` |
| 3 | **No known S4 finding** | section 0 of this document, and the audit |
| 4 | Every open S3 has a written owner decision in the release notes | `CHANGELOG.md` |
| 5 | The device scenarios have been run on both flavours, with the results written down | [`docs/guides/DEVICE_TEST_PLAN.md`](../guides/DEVICE_TEST_PLAN.md), the acceptance record |
| 6 | The file-size rule holds for the files that were touched | section 4 of this document |
| 7 | The documentation that the change made stale has been updated in the same change | [`docs/DOCUMENTATION_POLICY.md`](../DOCUMENTATION_POLICY.md) |
| 8 | The limitations in `README.md` are true of the build that is being released | `README.md`, "Known Limitations" |

### 7.1 The order in which the gates run — and why

The gates are not an arbitrary list. They are ordered **from cheap and
structural to expensive and behavioural**, because every gate assumes that the
ones before it passed.

| Order | Gate | Cost | Why here |
|---|---|---|---|
| 1 | **File size** (`scripts/check-file-size.sh`) | seconds | A file above the hard limit cannot be reviewed at all. When the file cannot be reviewed, nothing that follows can be either — so the cheapest structural check comes first. |
| 2 | **KDoc headers** (`scripts/check-kdoc-headers.sh`) | seconds | The header says *what the class does and why it exists*. A reviewer who cannot read that cannot judge the code that follows. So the intent has to be there before the code is looked at. |
| 3 | **Documentation links** (`scripts/check-docs-links.sh`) | seconds | A broken link is a broken promise to the reader. It is cheap to catch here and expensive to hunt down later, and a renamed file shows up here first. |
| 4 | **Language completeness** (`scripts/check-i18n-completeness.sh`) | seconds | A missing key is a user-visible defect in the *next* release. It is a mechanical comparison of two XML files, so it costs nothing to check now. |
| 5 | **The JVM suite** (`./gradlew :test-runner:test`) | minutes | The behaviour. It comes after the structure checks because a test that runs against a 900-line file still does not make that file reviewable. |
| 6 | **Android lint**, both flavours | minutes | The static analysis of the real Android build — it sees the manifest, the resources and the API levels, which the JVM suite does not. |
| 7 | **The debug APKs**, both flavours | minutes | The proof that the product can be built and installed. A green test suite does not prove that an APK exists. |
| 8 | **The device test plan** (15 cases, both flavours) | hours, and it needs hardware | The last gate, because it is the only one that needs a person, a television and a sender. Everything above it can be automated; this cannot. |

Two properties of the order are worth keeping:

- **The four structure checks run together and all of them report.** One push
  shows the complete list of problems instead of one problem per push — the
  CI job collects the exit codes and fails at the end.
- **The KDoc check runs with `--skip-nested` in CI**, because a nested class
  is documented by the header of the class that contains it. The strict run is
  green as well.

### 7.2 The status of the gate today (measured 2026-10-24)

| # | Gate | Status | Blocking? |
|---|---|---|---|
| 1 | The JVM suite is green | **yes** — 549 tests, 0 failures, 0 errors, 0 skipped, 63 test classes | — |
| 2 | Lint and both debug APKs are green | **yes** — `No issues found.` in both flavours, both APKs build | — |
| 3 | No known S4 finding | **yes** — SEC-01, SEC-02 and SEC-03 are closed, 0 open S4 | — |
| 4 | Every open S3 has a written owner decision | **no** — SEC-04 (S3, the scheme list of the DLNA media URI) still needs the owner's decision; SEC-06 (S3) is open | **yes** |
| 5 | The device scenarios ran on both flavours | **no** — 0 of 15 | **yes** |
| 6 | The file-size rule holds | **yes** — 0 files above 400, 0 above 550 | — |
| 7 | The documentation is up to date | **partly** — the documents of this cycle are updated; the open items are listed in [`docs/project/RELEASE_READINESS.md`](../project/RELEASE_READINESS.md) | depends on the change |
| 8 | The limitations in the README are true of the build | **yes** — the README states that no device test has been executed | — |

**Result: 5 of the 8 gates are green, one is partly met.** The two open gates
are not technical blockers: they need a decision by the owner (gate 4) and
hardware (gate 5). The pipeline — tests, lint, build, structure — is green.

---

## 8. Acceptance package per feature

Every feature that goes into a release should have:

1. the requirement IDs it implements;
2. the acceptance record, filled in from
   [`docs/acceptance/ACCEPTANCE_TEMPLATE.md`](../acceptance/ACCEPTANCE_TEMPLATE.md);
3. the user scenarios, as steps a person can repeat;
4. the functional tests;
5. the technical checks (the file-size rule, the KDoc header, the package
   boundary);
6. the performance expectations and the measured values;
7. the usability expectations;
8. the security/privacy considerations — new input, new permission, new stored
   value: yes or no;
9. the interoperability matrix, where the protocol matters;
10. the known limitations;
11. the evidence — the test output, the measurement, the device model.

**A dimension without a measurement is not a dimension that was checked.**
Leave it blank and say so; do not write "looks good".
