# PhairPlay Quality Model

PhairPlay is not "done" because the happy path works once. It is a **receiver**:
it sits on a home network, parses bytes from strangers, and runs on a television
with little RAM. A feature is accepted when it holds up across six dimensions.

This document defines the model: **how each dimension is measured**, **what the
state is today**, and **what the target is**. The measured state was taken on
**2026-10-24** against the working tree of `1.0.0-beta.3`.

Two other documents own the details and are linked rather than repeated here:

- the requirements and the performance budgets —
  [`docs/spec/REQUIREMENTS.md`](../spec/REQUIREMENTS.md) and
  [`docs/spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md)
- the security findings —
  [`docs/audit/2026-10-24-security-audit.md`](../audit/2026-10-24-security-audit.md)

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

---

## 1. Correctness

> *Does the receiver do what the requirement says, with the edge cases the
> requirement names?*

### How it is measured

| Method | Command / place | What it proves |
|---|---|---|
| The JVM protocol suite (the CI gate) | `./gradlew :test-runner:test` | The parsers, the protocol state machines and the crypto behave as specified, on the host JVM |
| The Android lint gate | `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug` | No correctness defect that the static analyser can see |
| The build gate | `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug` | Both flavours compile |
| Device scenarios | [`docs/TESTING.md`](../TESTING.md), scenarios 1–7 | The behaviour on real hardware, in the order a user meets it |
| The acceptance records | `docs/acceptance/` | The criterion ID, the measured result and the evidence |

A criterion counts as passed only when it has a **measured** result. "Looks
right on my TV" is not a result.

### Current state (2026-10-24)

| Fact | Value |
|---|---|
| Test files in `app/src/test/kotlin/com/phairplay/` | **58** — 56 test classes and 2 shared fixtures (`airplay/SpsTestData.kt`, `dlna/DlnaTestNetwork.kt`) |
| Instrumented test files | **1** (`app/src/androidTest/kotlin/com/phairplay/MainActivityTest.kt`) |
| Test files **excluded from the CI gate** | **6** — `MainActivityTest.kt`, `airplay/VideoSubmissionTest.kt`, `airplay/MdnsServiceTest.kt`, `service/ServiceControllerTest.kt`, `util/NetworkUtilsTest.kt`, `ui/VideoAspectFitTest.kt` |
| Why they are excluded | They touch Android framework classes that call JNI and are therefore unstable on the host JVM (see the `exclude` block in `test-runner/build.gradle.kts`) |
| CI gate | `./gradlew :test-runner:test` — see `.github/workflows/ci.yml` |
| Real-device validation of the AirPlay stack | **ongoing**, not complete (see `CHANGELOG.md`) |

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
| The hardening guarantees | [`SECURITY.md`](../../SECURITY.md) §2 | The nine guarantees the code actually enforces |
| The data-protection statement | [`PRIVACY.md`](../../PRIVACY.md) | What leaves the television, what is stored, which permission is needed for what |
| The permission audit | the audit, §4 | Every line of `AndroidManifest.xml` traced to a call site |
| The negative tests | `app/src/test/kotlin/com/phairplay/` | That the guard rejects the attack, not only that it accepts the normal case |

### Current state (2026-10-24)

| Fact | Value |
|---|---|
| Findings in the current audit | **14** — 3 High, 4 Medium, 4 Low, 3 Info |
| Open **S4** findings | **3** — SEC-01 (arbitrary URL fetch via `POST /play`), SEC-02 (image decompression bomb), SEC-03 (unbounded FU-A reassembly) |
| Accepted residual risks | **7** (audit §7) — each with a written reason |
| Central log reduction | present (`util/LogRedactor`, `util/Logger`), applied in every build |
| Permissions that are declared but never requested | none outstanding (SEC-06 fixed in the manifest) |
| Telemetry, analytics, crash reporter | **none** — there is no code path that leaves the local network |

> ### Consequence for the next release
>
> **Three known S4 findings are open.** Under the rule in section 0, the
> current code is **not release-ready**. The three findings have a concrete fix
> and a negative test each, written down in the audit, in the track that owns
> the file. Until they are closed, the honest status of a release is
> "blocked by SEC-01, SEC-02, SEC-03" — not "ready".

### Target

- **Zero open S4 findings** at the moment a release is cut.
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
measurement commands are in [`docs/TESTING.md`](../TESTING.md); the budgets are
in NFR-01…NFR-04 and in the benchmark table of
[`docs/spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md).

| Metric | How it is measured | Budget |
|---|---|---|
| Peak RAM during one stream | `adb shell dumpsys meminfo com.phairplay.googletv` | ≤ 150 MB (NFR-03) |
| Average CPU | `adb shell top -p $(adb shell pidof com.phairplay.googletv)` | ≤ 30 % (NFR-04) |
| Frame rate | On-screen frame counter during a 5-minute stream | ≥ 25 fps (NFR-02) |
| Video latency | Stopwatch shown on the sender, read on the TV | ≤ 100 ms (NFR-01) |
| A/V drift | A/V sync test video (clapper board) | ≤ 40 ms (NFR-… / FR-35) |
| Connection time | Stopwatch from the AirPlay click to the first frame | ≤ 5 s |

### Current state (2026-10-24)

| Fact | Value |
|---|---|
| Measured on a device in this cycle | **none** — no television was available in the working environment |
| Bounded memory that is implemented and unit-tested | the mirror frame queue (64 MB byte budget, 90-item limit), the FU-A frame cap (2 MB), the mirror payload cap (8 MB), the RTSP message budget (1 MB), the photo payload cap (25 MB), the SOAP body cap (64 KB) |
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
| **File size** | the line count of every hand-written Kotlin file | that no file has grown into an unreviewable blob |
| **KDoc headers** | every class has a header with what / why / example | that the intent is readable without reading the body |
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

1. **The code is an attack surface, not just code.** Every one of the 82
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
4. **The codebase has already shown what happens without the limit.** Measured
   on 2026-10-24, **6 of the 82 files** are over the soft limit and **3** are
   over the hard limit. The limit was written first and the code grew past it;
   the split of those files is planned work. The number stays strict so that
   the target is unambiguous.
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

### Current state (2026-10-24)

Measured on the working tree of `1.0.0-beta.3`:

| Fact | Value |
|---|---|
| Hand-written Kotlin files in `app/src/main/kotlin/com/phairplay/` | **82** |
| Total lines in those files | **14 453** |
| Files over the 400-line soft limit | **6** |
| Files over the 550-line hard limit | **3** |

The six files over the soft limit, with their measured line counts:

| File | Lines | Over |
|---|---|---|
| `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt` | 1024 | hard |
| `app/src/main/kotlin/com/phairplay/airplay/AirPlayReceiver.kt` | 658 | hard |
| `app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt` | 650 | hard |
| `app/src/main/kotlin/com/phairplay/airplay/MdnsService.kt` | 527 | soft |
| `app/src/main/kotlin/com/phairplay/airplay/handshake/AudioStreamServer.kt` | 503 | soft |
| `app/src/main/kotlin/com/phairplay/MainActivity.kt` | 447 | soft |

> A refactoring that splits the first five of these is running in parallel with
> this documentation work. It is behaviour preserving — it is **not** a feature
> and must not appear in the changelog as one. After it lands, this table has
> to be re-measured, because a table with stale numbers is worse than no
> table.

| Other fact | Value |
|---|---|
| Flavour-specific code | `app/src/googletv/kotlin/com/phairplay/cast/` (2 files), `app/src/firetv/kotlin/com/phairplay/cast/` (1 file) |
| Native code | `app/src/main/cpp/` — the FairPlay JNI bridge, the ALAC JNI bridge and the `playfair/` and `alac/` C sources |

### Target

- **0 files over the hard limit** — the merge gate is non-negotiable.
- **0 files over the soft limit** after the planned split lands.
- Every new class has a KDoc header that says what / why / how, checked in
  review.
- Every public method has a test.
- `docs/WHERE_TO_CHANGE.md` resolves every entry to a path that exists.

---

## 5. Interoperability

> *Does a real sender — a real Mac, a real Windows box, a real VLC — actually
> work with it?*

### How it is measured

| Method | How | What it proves |
|---|---|---|
| The device scenarios | [`docs/TESTING.md`](../TESTING.md) | that a real sender can complete a real session |
| The protocol suites | `./gradlew :test-runner:test` | that the parser accepts what the specification says and rejects the rest |
| The cross-implementation matrix | the sender column of the acceptance record | which senders were actually tried |
| The phase plan of ADR-004 | `docs/decisions/ADR-004-dlna-mediarenderer-roadmap.md` | that a protocol is delivered in reviewable, testable steps |

Interoperability is only claimed for a **sender × version × protocol**
combination that was actually tried. A protocol that was not tried on real
hardware is reported as *not validated*, never as *working*.

### Current state (2026-10-24)

| Protocol | State | Validated on real hardware? |
|---|---|---|
| **AirPlay 2** | The complete stack is implemented: mDNS advertising, the RTSP handshake, HomeKit-style pairing, FairPlay key decryption, H.264 mirroring, AAC-ELD / AAC-LC / ALAC audio, NTP A/V sync, the now-playing overlay, the DACP reverse remote, the photo endpoint | **Partially.** Real-device validation with macOS and iOS senders is still in progress; the release notes say so |
| **UPnP / DLNA** | The discovery and control plane is shipped (ADR-004, phases D1 + D2): SSDP advertising and `M-SEARCH`, the device description server, the SOAP dispatcher, GENA eventing | **Not validated on real hardware in this cycle** (no Windows/VLC controller was available) |
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
| The i18n coverage | the resource folders | that a German or French user sees German or French, not English |
| The troubleshooting guide | [`docs/guides/TROUBLESHOOTING.md`](../guides/TROUBLESHOOTING.md) | that the common failures have a named cause and a fix |

### Current state (2026-10-24)

| Fact | Value |
|---|---|
| Languages with a resource folder | **3** — English (`values/`), German (`values-de/`), French (`values-fr/`) |
| TV usability rules in the requirements | NFR-05…NFR-10: card-based layout, large focus indicators, D-pad navigation, 48 dp focus targets, 18 sp body text, the status visible within 1 second |
| Accessibility | **not systematically audited** — no automated accessibility scan, no documented TalkBack pass |
| The "wrong state on the screen" defect class | already found and fixed once; see [`docs/reviews/2026-10-24-pr19-pr17-issue18-review.md`](../reviews/2026-10-24-pr19-pr17-issue18-review.md) |
| The step count for the common path | install → start the app → click AirPlay → pick the TV. The user-visible part is two clicks after the app is running |

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
| 5 | The device scenarios have been run on both flavours, with the results written down | [`docs/TESTING.md`](../TESTING.md), the acceptance record |
| 6 | The file-size rule holds for the files that were touched | section 4 of this document |
| 7 | The documentation that the change made stale has been updated in the same change | [`docs/DOCUMENTATION_POLICY.md`](../DOCUMENTATION_POLICY.md) |
| 8 | The limitations in `README.md` are true of the build that is being released | `README.md`, "Known Limitations" |

**Current status against this gate: not met.** Gates 3 and 5 are open — three
S4 findings are documented and unfixed, and the device scenarios have not been
run in this cycle.

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
