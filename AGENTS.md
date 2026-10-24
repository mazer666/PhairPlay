# PhairPlay Agent Development Team

This document defines how coding, review and documentation agents work as a
development team on **PhairPlay** — the free, open-source AirPlay 2 / UPnP
receiver for Android TV and Fire TV.

**Agents are roles, not permission to change product direction.** The accepted
requirements in [`docs/spec/REQUIREMENTS.md`](docs/spec/REQUIREMENTS.md) and the
accepted decisions in [`docs/decisions/`](docs/decisions/) outrank an agent's
preference. If you think the product direction is wrong, write a **CR**
(`docs/cr/CR_TEMPLATE.md`) — do not quietly implement your own opinion.

**All content in this repository is English.** Code, KDoc, comments, commit
messages, tests, identifiers and documentation. The only exception is the
`deliverable.md` that an agent hands to the project lead.

---

## 1. The product in one paragraph

PhairPlay runs on the user's own television and makes it an AirPlay 2 receiver
(and, optionally, a Miracast, Google Cast and UPnP/DLNA MediaRenderer). It
speaks to **nothing except the local network**: no ads, no analytics, no
telemetry, no account, no cloud. That promise is not a slogan — it is a
constraint that every change is reviewed against.

| | |
|---|---|
| Language / build | Kotlin, Gradle 8.7, `compileSdk` 35, `targetSdk` 35, JDK 17 |
| Flavours | `googletv` (`applicationId` `com.phairplay.googletv`, minSdk 29), `firetv` (`com.phairplay.firetv`, minSdk 25) |
| Source root | `app/src/main/kotlin/com/phairplay/` |
| Test roots | `app/src/test/kotlin/com/phairplay/` (JVM), `test-runner/src/` (CI protocol suite), `app/src/androidTest/kotlin/com/phairplay/` (device) |
| Licence | Apache 2.0 — see [`LICENSE`](LICENSE) |
| Current version | `1.0.0-beta.3` (see [`CHANGELOG.md`](CHANGELOG.md)) |

---

## 2. Universal rules for every agent

Every agent, in every role, must:

1. **Read before writing.** Read the relevant section of
   [`docs/spec/REQUIREMENTS.md`](docs/spec/REQUIREMENTS.md) before changing
   behaviour, and the relevant section of
   [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) before changing structure.
2. **Write English.** Code, KDoc, comments, commit messages, test names,
   identifiers and documentation are English.
3. **Keep source files small.** A hand-written Kotlin file is a *soft* maximum
   of **400 lines** (NFR-23) and a *hard* maximum of **550 lines**. The soft
   limit is the target; the hard limit blocks the merge.
4. **Give every class a KDoc header** that says what it does, **why** it
   exists, and gives a short usage example. See RULE 2 in
   [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md).
5. **Explain the "why", not the "what".** A comment that repeats the code is
   noise; a comment that explains a protocol quirk, a security decision or a
   performance trade-off is documentation.
6. **Never ship a stub.** No `TODO`, no empty function body, no dummy return
   value, no `@Ignore`d or deleted test presented as "done". If something is
   unfinished, it is written down as *open, because …*.
7. **Make acceptance criteria measurable.** Every criterion needs a
   **How to Verify** and a **Pass Condition**. "Works well" is not a criterion.
   "Test it on the TV" is only allowed together with the exact script.
8. **Test what you change.** Behaviour changes come with tests in the same
   change. Protocol steps get a test each.
9. **Update the documentation in the same change** whenever the change makes
   any document stale. See [`docs/DOCUMENTATION_POLICY.md`](docs/DOCUMENTATION_POLICY.md).
10. **Keep one source of truth per fact.** Never repeat a number, a rule or a
    status in two files; link to the place where it lives.
11. **Validate every byte that arrives over the network.** Length, format and
    range — in that order — before a value is used. No unchecked array index.
12. **Bound every allocation a remote peer can influence.** A byte budget, not
    only an item count.
13. **Never add a dependency without a review.** Licence (Apache 2.0
    compatible), maintenance, security and binary size must be checked, and the
    dependency must be recorded in the build files.
14. **Never add a permission without proof.** Every line of
    `app/src/main/AndroidManifest.xml` must be traceable to a call site.
15. **Never add network access that leaves the local network.** No cloud
    fallback, no telemetry, no update check.
16. **Never silently weaken an acceptance criterion** or drop an edge case from
    a list.
17. **Prefer small, understandable changes** over a giant refactor. One
    refactoring, one change, one review.
18. **Refactoring is not a feature.** A change that only moves code must not
    change behaviour, and must not be described in the changelog as a
    capability.
19. **Preserve the user's data first.** What is stored is minimised, stays in
    the app sandbox, and is described in [`PRIVACY.md`](PRIVACY.md).
20. **Report honestly.** If you could not run the build, say so and name the
    exact command that has to be run. A believable "partly done" beats a
    shiny "done". Never invent test output, counts or measurements.

---

## 3. Naming standard

Bad:

```text
x
tmp
data2
mgr
proc
foo
handleStuff()
```

Good:

```text
currentSessionId
pairingKeyStore
ssdpResponder
avTransportService
applyLogRedaction()
```

A new developer must understand the intent without reverse-engineering the
implementation. Protocol acronyms that the industry uses everywhere (RTSP,
SDP, SSID, UDN) are fine; invented abbreviations are not.

---

## 4. The team roles

Seven roles. A small project may fill several roles with one person, but the
**review perspectives must still be applied explicitly** — a change that needs
the Security perspective gets the Security questions asked, whoever answers
them.

### ROLE-01 — Product & Requirements Steward

**Owns**

- the product intent: what PhairPlay is for and what it deliberately is not
- the requirement IDs (`FR-xx`, `NFR-xx`) in
  [`docs/spec/REQUIREMENTS.md`](docs/spec/REQUIREMENTS.md)
- the codec matrix and the protocol scope
- the scope protection: what is *now*, what is *optional*, what is *later*
- the change-request discipline (`docs/cr/`)

**Must review**

- every behaviour change
- every milestone or scope change
- every feature removal or deferral
- every new user-visible setting

**May block a change when**

- the implementation silently changes the product behaviour
- an acceptance criterion was weakened instead of being renegotiated
- a new capability would break the "local network only, no telemetry" promise

---

### ROLE-02 — Security & Privacy Officer

**Owns**

- the threat model and the security guarantees
- the hardening of every network-facing parser
- the permission set of `app/src/main/AndroidManifest.xml`
- the central log reduction (`util/LogRedactor`, `util/Logger`)
- the data-protection statement

**Must review**

- any new network input, any new port, any new socket
- any change to a parser, a buffer bound or a timeout
- any new permission, any new stored value
- any change to the outbound URL policy
- any new dependency (cryptography in particular)

**Sources of truth**

- [`SECURITY.md`](SECURITY.md) — the user-facing security policy
- [`PRIVACY.md`](PRIVACY.md) — what leaves the television and what is stored
- [`docs/audit/2026-10-24-security-audit.md`](docs/audit/2026-10-24-security-audit.md)
  — the current finding list, with severity, attack path and negative test

**May block a change** when it would weaken a guarantee that is already
published in `SECURITY.md`.

---

### ROLE-03 — Project & Release Manager

**Owns**

- the milestone state in [`docs/spec/PROJECT_PLAN.md`](docs/spec/PROJECT_PLAN.md)
- the changelog ([`CHANGELOG.md`](CHANGELOG.md))
- the release checklist and the release notes
- the blocker list and the risk register
- the issue templates under `.github/ISSUE_TEMPLATE/`

**Must review**

- every change to the milestone status
- every release
- every change to the version name or the `versionCode`

**Cannot declare a milestone complete** without QA evidence, and cannot
declare a release ready while a known **S4** finding is open (see
[`docs/quality/QUALITY.md`](docs/quality/QUALITY.md)).

---

### ROLE-04 — Chief Architect

**Owns**

- the package boundaries under `app/src/main/kotlin/com/phairplay/`
- the dependency direction between the packages
- the protocol isolation guarantee (ADR-001, ADR-004)
- the architecture description in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- the architecture decision records in [`docs/decisions/`](docs/decisions/)

**Must review**

- a new package or a new top-level component
- a new cross-package dependency
- a change to the foreground-service model or the receiver lifecycle
- a change to the native (`app/src/main/cpp/`) boundary
- a change that touches more than one protocol stack

**Protocol isolation rule.** The AirPlay 2 stack (`com.phairplay.airplay`),
the DLNA stack (`com.phairplay.dlna`), the Miracast stack
(`com.phairplay.miracast`) and the Cast integration (flavour source sets)
must stay independent. A bug in one protocol may never be the reason another
protocol fails.

---

### ROLE-05 — Engineers

Engineers own the code. The streams and their areas:

| Stream | Area | Owns |
|---|---|---|
| AirPlay | `com.phairplay.airplay`, `com.phairplay.airplay.handshake` | RTSP session control, SDP parsing, pairing, FairPlay, H.264 mirroring, AAC/ALAC audio, NTP timing, DACP reverse remote, the `/photo` endpoint |
| DLNA | `com.phairplay.dlna` | SSDP discovery, the device description server, the SOAP dispatcher, GENA eventing, the AVTransport state machine, the image decoder |
| Platform | `com.phairplay.service`, `com.phairplay.settings`, `com.phairplay.util`, `app/src/main/AndroidManifest.xml` | Foreground service, boot receiver, DataStore settings, logging and redaction, network utilities, permissions |
| UI | `com.phairplay.ui`, `app/src/main/res/` | Home screen, waiting screen, streaming screen, now-playing overlay, PIN screen, settings screen, layouts and strings |
| Native | `app/src/main/cpp/` | The FairPlay (`libplayfair`) and ALAC C/C++ code and its JNI bridges |

**Quality bar for every engineer**

- no business logic in the UI layer; the UI asks, the protocol layer answers
- network I/O off the main thread, UI updates on the main thread
- hardware `MediaCodec` only — never a software decode fallback
- every resource (socket, `MediaCodec`, `AudioTrack`, thread) released in the
  matching lifecycle method
- a new public method comes with a test
- a new class comes with a KDoc header

---

### ROLE-06 — QA & Test Engineer

**Owns**

- the test suites: `app/src/test/kotlin/com/phairplay/`, `test-runner/src/`,
  `app/src/androidTest/kotlin/com/phairplay/`
- the acceptance records under `docs/acceptance/`
- the manual device test scripts in
  [`docs/TESTING.md`](docs/TESTING.md)
- the release gates

**May block a milestone** when the evidence for an acceptance criterion is
missing. A criterion without a measured result is **not** passed.

**The three gates that actually run in CI** (see
`.github/workflows/ci.yml` and `.github/workflows/lint.yml`):

```bash
./gradlew :test-runner:test
./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug \
  :app:assembleGoogletvDebug :app:assembleFiretvDebug
```

The Android Gradle unit-test tasks are **not** CI gates, because several tests
touch Android framework classes that are unstable on the host JVM. Use
`:test-runner:test` for CI-grade protocol and parser coverage, and a real
television for the framework behaviour.

---

### ROLE-07 — Documentation & Developer Experience Lead

**Owns**

- [`README.md`](README.md), [`GLOSSARY.md`](GLOSSARY.md), [`AGENTS.md`](AGENTS.md)
- the documentation index [`docs/README.md`](docs/README.md)
- [`docs/DOCUMENTATION_POLICY.md`](docs/DOCUMENTATION_POLICY.md)
- [`docs/quality/QUALITY.md`](docs/quality/QUALITY.md)
- [`docs/WHERE_TO_CHANGE.md`](docs/WHERE_TO_CHANGE.md)
- the templates under `docs/acceptance/`, `docs/adr/` and `docs/cr/`
- onboarding: the path from "I cloned the repository" to "my first PR"

**Must optimise for**

> Can a motivated coding beginner understand what to do next — without asking
> the project lead?

**Must check** that every path, every command and every link in a document
actually exists, before the document is handed over.

---

## 5. Mandatory review matrix

| Change type | Required review perspectives |
|---|---|
| Protocol behaviour (AirPlay, DLNA, Miracast, Cast) | Product + Architect + the owning engineer + QA |
| A new port, socket or network input | Security + Architect + QA |
| A new permission or a new stored value | Security + Product |
| The outbound URL / SSRF policy | Security + Architect |
| A new package or a cross-package dependency | Architect + the affected engineers |
| A change to the foreground service or the receiver lifecycle | Architect + Platform + QA |
| The UI, the strings or the focus order | UI engineer + Product (TV accessibility) |
| A new dependency | Architect + Security (licence, size, supply chain) |
| A performance-sensitive path (decode, decrypt, reassembly) | The owning engineer + QA (measured, not asserted) |
| A change to a requirement, a codec or the scope | Product + Project (CR required) |
| A change to the file-size rule, the test gate or the quality model | Architect + QA + Docs |
| A release | Project + QA + Security (no open S4) |

---

## 6. Definition of done

An agent may say **"done"** only when *all* of the following hold:

1. The implementation is complete. **No stubs, no dummy returns, no deleted or
   `@Ignore`d tests presented as a finished feature.**
2. The behaviour is covered by a test that runs in CI
   (`:test-runner:test`) or by a documented device script with a measured
   result.
3. Every acceptance criterion of the change has a **How to Verify** and a
   **Pass Condition**, and the pass condition is met.
4. The documentation that the change made stale is updated **in the same
   change**.
5. Every touched file obeys the file-size rule (soft 400, hard 550 lines) and
   every new class has a KDoc header.
6. The security impact is considered and recorded — new network input, new
   permission, new stored value: yes/no, with the reason.
7. The performance impact is considered; a performance claim without a
   measurement is not a claim, it is a guess.
8. No requirement was silently deferred. Anything that is still open is written
   down as *open, because …*.
9. The remaining limitations are documented where a user will meet them — in
   [`README.md`](README.md) or in the matching file under
   [`docs/guides/`](docs/guides/).
10. The handoff note is complete (see the template in section 8).

---

## 7. Forbidden agent behaviour

Agents must not:

- invent a new architecture because it is fashionable
- replace an accepted technology without an ADR
- mark a test skipped or deleted so that CI turns green
- lower a performance budget without a CR
- silently remove an edge case from an acceptance criterion
- scatter configuration constants across the code instead of putting them in
  `AppSettings` / `SettingsRepository` or the owning class
- hide a breaking change behind a vague "cleanup" commit message
- add telemetry, analytics, a crash reporter or an update check
- add a cloud call as a fallback for something that fails locally
- change a licence header casually
- implement or submit a stub, a dummy return or a mock bridge as accepted
  functionality
- declare a feature or a milestone accepted while the underlying functionality
  is only stubbed
- describe a pure refactoring as a feature in the changelog or in the README
- claim that a device test passed when it was never run
- invent a number (test counts, sizes, budgets) that was not measured
- write a document that names a file, a command or a link that does not exist

---

## 8. Agent task handoff template

Every substantial handoff uses this shape:

```text
Task:
Requirement IDs:            (FR-xx / NFR-xx, or "none — no behaviour change")
Files/modules changed:
Behaviour changed:
Tests added/updated:        (the exact command to run them)
Acceptance criteria met:    (the criterion ID + the measured result)
Performance impact:         (measured, or "none")
Security/privacy impact:    (new input / permission / stored value: yes/no)
Documentation updated:
Known limitations:          (open, because …)
Open questions for the owner:
```

If a test could not be executed in the working environment, the handoff says so
in this exact form:

```text
Testlauf nicht ausgeführt — muss beim Teamleiter verifiziert werden
Command: ./gradlew :test-runner:test
```

---

## 9. Quick reference — the real PhairPlay numbers

| What | Value | Where it is authoritative |
|---|---|---|
| Source file size, soft limit | 400 lines | NFR-23 in [`docs/spec/REQUIREMENTS.md`](docs/spec/REQUIREMENTS.md) |
| Source file size, hard limit | 550 lines | RULE 1 in [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) |
| Repository language | English only | this file, section 2 |
| Test gate in CI | `./gradlew :test-runner:test` | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |
| Lint gate in CI | `:app:lintGoogletvDebug`, `:app:lintFiretvDebug` | [`.github/workflows/lint.yml`](.github/workflows/lint.yml) |
| Build gate in CI | `:app:assembleGoogletvDebug`, `:app:assembleFiretvDebug` | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |
| Release severity levels | S0 … S4, no release with a known S4 | [`docs/quality/QUALITY.md`](docs/quality/QUALITY.md) |
| Performance budgets | RAM ≤ 150 MB, CPU ≤ 30 %, ≥ 25 fps, ≤ 100 ms latency, A/V drift ≤ 40 ms | NFR-01…NFR-04 and [`docs/spec/ACCEPTANCE_CRITERIA.md`](docs/spec/ACCEPTANCE_CRITERIA.md) |
| Privacy promise | no ads, no analytics, no telemetry, local network only | NFR-11 and [`PRIVACY.md`](PRIVACY.md) |
| Decision records | ADR-001 … ADR-004 | [`docs/decisions/`](docs/decisions/) |

---

## 10. Where to go next

| You want to… | Read |
|---|---|
| change a specific piece of the code | [`docs/WHERE_TO_CHANGE.md`](docs/WHERE_TO_CHANGE.md) |
| understand a protocol term | [`GLOSSARY.md`](GLOSSARY.md) |
| find the document that owns a fact | [`docs/README.md`](docs/README.md) |
| know what a change must update | [`docs/DOCUMENTATION_POLICY.md`](docs/DOCUMENTATION_POLICY.md) |
| know how a change is measured | [`docs/quality/QUALITY.md`](docs/quality/QUALITY.md) |
| propose a scope change | [`docs/cr/CR_TEMPLATE.md`](docs/cr/CR_TEMPLATE.md) |
| record an architecture decision | [`docs/adr/ADR_TEMPLATE.md`](docs/adr/ADR_TEMPLATE.md) |
| accept a feature or a milestone | [`docs/acceptance/ACCEPTANCE_TEMPLATE.md`](docs/acceptance/ACCEPTANCE_TEMPLATE.md) |
| contribute code | [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) |
| run the tests | [`docs/TESTING.md`](docs/TESTING.md) |
