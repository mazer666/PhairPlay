# PhairPlay Documentation Index

Every document in this repository, what it owns, and where to look when you
need something. Only files that exist are listed here.

**Start here if you are new:** [`../README.md`](../README.md) → what the product
is and how to install it · [`../AGENTS.md`](../AGENTS.md) → how the team works ·
[`CONTRIBUTING.md`](CONTRIBUTING.md) → how to get a pull request merged ·
[`WHERE_TO_CHANGE.md`](WHERE_TO_CHANGE.md) → where the code for *your* idea
lives · [`project/RELEASE_READINESS.md`](project/RELEASE_READINESS.md) → what
is still missing before `v1.0.0`.

---

## Root — the entry points

| Document | What it owns |
|---|---|
| [`../README.md`](../README.md) | What PhairPlay is, the feature list, installation, usage, the honest limitations, and the link into the documentation. |
| [`../AGENTS.md`](../AGENTS.md) | The role model, the universal rules, the review matrix, the definition of done, the forbidden behaviours and the handoff template. |
| [`../GLOSSARY.md`](../GLOSSARY.md) | Every protocol and platform term explained for a beginner: AirPlay, RAOP, FairPlay, WFD, SSDP, GENA, SOAP, UPnP, DLNA, SDP, RTP, RTCP, NTP, EDID, HDCP, ALAC, AAC-ELD, HEVC, VP9, AV1, H.264, mDNS, NSD, DataStore, ForegroundService, ADB. |
| [`../SECURITY.md`](../SECURITY.md) | The security policy for users: the threat model, the guarantees the code enforces, the limits, and how to report a vulnerability. |
| [`../PRIVACY.md`](../PRIVACY.md) | The data protection statement: what leaves the television, what is stored on it, the permissions and their justification, the zero-telemetry commitment. |
| [`../CHANGELOG.md`](../CHANGELOG.md) | Every released and unreleased change, in the Keep-a-Changelog format. |
| [`../LICENSE`](../LICENSE) | The Apache 2.0 licence text. |

## Core specifications — `docs/spec/`

The product track owns this folder. It answers *what* the product does, not
*how* it is built.

| Document | What it owns |
|---|---|
| [`spec/REQUIREMENTS.md`](spec/REQUIREMENTS.md) | The requirement IDs (`FR-xx`, `NFR-xx`), the codec matrix per protocol, the target devices, and the explicit list of what is out of scope. The source of truth for *what the product must do*. |
| [`spec/TECHNICAL_SPEC.md`](spec/TECHNICAL_SPEC.md) | The technical baseline behind the requirements: the protocol stacks, the system architecture, the AirPlay feature flags, the photo-streaming protocol, the DRM evaluation. |
| [`spec/PROJECT_PLAN.md`](spec/PROJECT_PLAN.md) | The phased roadmap M0–M12 with the status of each milestone, its task list and its definition of done. |
| [`spec/ACCEPTANCE_CRITERIA.md`](spec/ACCEPTANCE_CRITERIA.md) | The measurable acceptance criteria per milestone — each with a "How to Verify" and a "Pass Condition" — and the performance benchmark table. |

## Acceptance records — `docs/acceptance/`

One record per milestone. Each one walks through the same sections — functional,
technical, performance, usability, accessibility, security and privacy,
interoperability, known limitations, decision, release gate — and states for
every criterion whether it is **proven by a test**, **proven only by the code**
or **still open**, and why.

| Document | What it owns |
|---|---|
| [`acceptance/M06-ACCEPTANCE.md`](acceptance/M06-ACCEPTANCE.md) | Whether the Miracast receiver meets FR-13 … FR-18 — and what the Wi-Fi Direct advertisement and the RTSP control plane already prove without a device. |
| [`acceptance/M07-ACCEPTANCE.md`](acceptance/M07-ACCEPTANCE.md) | Whether the Google Cast receiver degrades gracefully without a registered app ID, and what still separates it from an end-to-end Cast session. |
| [`acceptance/M08-ACCEPTANCE.md`](acceptance/M08-ACCEPTANCE.md) | Whether the running receiver stays stable: the bounded queues, the mDNS recovery, the resource budgets — and which endurance numbers are still unmeasured. |
| [`acceptance/M09-ACCEPTANCE.md`](acceptance/M09-ACCEPTANCE.md) | Whether the Fire TV flavour is usable: the build, the D-pad navigation and the honest gap between "it compiles" and "it ran on a device". |
| [`acceptance/M10-ACCEPTANCE.md`](acceptance/M10-ACCEPTANCE.md) | Whether the interface is really translated: the completeness of English, German and French, and what is still open (right-to-left, formatting). |
| [`acceptance/M11-ACCEPTANCE.md`](acceptance/M11-ACCEPTANCE.md) | What a `v1.0.0` release still needs: the CI gate, the signed artefacts, the version bump and the device validation. |
| [`acceptance/M12-ACCEPTANCE.md`](acceptance/M12-ACCEPTANCE.md) | Whether the UPnP/DLNA MediaRenderer works: every criterion AC-M12.1 … AC-M12.20 with its evidence, and the open media path. |
| [`acceptance/QA-QUALITY-GATE-ACCEPTANCE.md`](acceptance/QA-QUALITY-GATE-ACCEPTANCE.md) | The acceptance record of the quality gate: what each of the four structure checks and the CI workflow must prove, and what was actually measured. |
| [`acceptance/QA-I18N-ACCEPTANCE.md`](acceptance/QA-I18N-ACCEPTANCE.md) | The acceptance record of the language coverage: the key table of `en`/`de`/`fr`, the missing entries and the rule that keeps them complete. |
| [`acceptance/QA-DEVICE-TEST-ACCEPTANCE.md`](acceptance/QA-DEVICE-TEST-ACCEPTANCE.md) | The acceptance record of the device test plan: whether every one of the 15 cases is copy-pasteable, has a time limit and has an evidence rule. |
| [`acceptance/ACCEPTANCE_TEMPLATE.md`](acceptance/ACCEPTANCE_TEMPLATE.md) | The template for a new acceptance record: functional, technical, performance, usability, accessibility, security/privacy, interoperability, known limitations, decision. |

## How the product works

| Document | What it owns |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | The beginner-friendly explanation of how AirPlay 2 works, the component overview per package, the full data-flow diagram, the design decisions and the security architecture. |
| [`TESTING.md`](TESTING.md) | How to run the unit, instrumented and lint suites, what is and is not covered by an automated test, the manual device test scenarios, and the performance measurement commands. |

## Contributing and working on the code

| Document | What it owns |
|---|---|
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | The six coding rules (file size, comments, tests, security, performance, flavours), the pull-request checklist and the commit-message style. |
| [`WHERE_TO_CHANGE.md`](WHERE_TO_CHANGE.md) | The navigation table "I want to change X → that is here", with the real package paths. Read this before you edit anything. |
| [`DOCUMENTATION_POLICY.md`](DOCUMENTATION_POLICY.md) | The same-change rule, the single-source-of-truth rule and the archive rule — what has to be updated together with a change, and where a document goes when it is replaced. |
| [`quality/QUALITY.md`](quality/QUALITY.md) | The six-dimensional quality model with the measurement method, the current state and the target value per dimension, plus the severity grades S0–S4. |
| [`adr/ADR_TEMPLATE.md`](adr/ADR_TEMPLATE.md) | The template for a new architecture decision record. |
| [`cr/CR_TEMPLATE.md`](cr/CR_TEMPLATE.md) | The template for a change request against an accepted requirement or an accepted decision. |

## Architecture decisions — `docs/decisions/`

Accepted decisions. An agent that wants to change one of them writes a CR, not
a pull request.

| Document | What it owns |
|---|---|
| [`decisions/ADR-001-multi-protocol.md`](decisions/ADR-001-multi-protocol.md) | Why AirPlay 2, Miracast and Google Cast are supported side by side, each independently switchable, and how DLNA was added as a fourth option. |
| [`decisions/ADR-002-service-architecture.md`](decisions/ADR-002-service-architecture.md) | Why the receiver runs as a foreground service and how the receiver lifecycle is modelled. |
| [`decisions/ADR-003-ui-design.md`](decisions/ADR-003-ui-design.md) | Why the UI is View-based instead of Jetpack Compose, and how the TV design language is applied. |
| [`decisions/ADR-004-dlna-mediarenderer-roadmap.md`](decisions/ADR-004-dlna-mediarenderer-roadmap.md) | Why a UPnP/DLNA MediaRenderer was adopted, and the four-phase delivery plan D1–D4 behind it. |

> **Migration note.** The templates in `docs/adr/` and `docs/cr/` are new. The
> existing records still live in `docs/decisions/`. The numbering scheme of the
> two locations will be reconciled in one step — see the open points in the
> handover note, do not renumber the existing ADRs on your own.

## Guides for users — `docs/guides/`

| Document | What it owns |
|---|---|
| [`guides/INSTALLATION.md`](guides/INSTALLATION.md) | Every installation path: ADB, the sideloading apps, both flavours, and the developer options that have to be enabled first. |
| [`guides/TROUBLESHOOTING.md`](guides/TROUBLESHOOTING.md) | The failure catalogue: the device does not appear in the picker, the TV sleeps during streaming, no audio, no video, and the router settings that cause each. |
| [`guides/NETWORK_CHECK.md`](guides/NETWORK_CHECK.md) | The network check the owner runs from the laptop before a playback test: the discovery, the description document, the advertised address and the AirPlay port — with the expected output for every verdict. |
| [`guides/CAST_APP_ID.md`](guides/CAST_APP_ID.md) | How to register a Google Cast receiver app ID, which is required to test the Cast stack end to end. |
| [`guides/CASTING_TO_A_TV.md`](guides/CASTING_TO_A_TV.md) | The step-by-step guide for getting a picture onto the television — from macOS, from Windows, from VLC and from a mobile UPnP controller — including the honest `file:///` limitation and the HTTP workaround. |
| [`guides/DEVICE_TEST_PLAN.md`](guides/DEVICE_TEST_PLAN.md) | The 15 copy-pasteable hardware cases DT-01 … DT-15: the exact commands, the expected result, the time limit and the evidence per case — plus the result sheet for the Google TV and the Fire TV. |

> **One honest limitation applies to every user-facing guide:** a UPnP renderer
> fetches the media itself. A `file:///` URI points at the *sender's* disk and
> cannot be read by the television, so rendering a local file works only for
> network-reachable `http`/`https` URLs. This is a property of the protocol,
> not a bug.

## Project status and risk — `docs/project/`

| Document | What it owns |
|---|---|
| [`project/RELEASE_READINESS.md`](project/RELEASE_READINESS.md) | What is still missing before `v1.0.0`: what is proven, what is open, in which order it has to be closed, and which points genuinely need real hardware. |
| [`project/RISKS.md`](project/RISKS.md) | The maintained risk register: every known risk with likelihood, impact, countermeasure, owner and status — the list of what could still go wrong. |

## Reviews and audits — `docs/reviews/`, `docs/audit/`

| Document | What it owns |
|---|---|
| [`reviews/2026-10-24-pr19-pr17-issue18-review.md`](reviews/2026-10-24-pr19-pr17-issue18-review.md) | The review of pull request #19, pull request #17 and issue #18, including the "wrong state on the screen" defect class that came out of it. |
| [`audit/2026-10-24-security-audit.md`](audit/2026-10-24-security-audit.md) | The full security and privacy audit of 1.0.0-beta.3: threat model, attack surface table, 14 findings with severity, attack path, concrete fix and negative test, the permission audit and the accepted residual risks. The single source of truth for the security state of the release. |
| [`audit/qa-quality-gate-negative-test.md`](audit/qa-quality-gate-negative-test.md) | The raw protocol of the negative test of the four check scripts — the proof that each one turns red on a real violation and green again when it is removed (22 cases, green → red → green). |

## Planning material — `docs/superpowers/`

| Document | What it owns |
|---|---|
| [`superpowers/plans/2026-06-08-airplay2-mirroring-handshake.md`](superpowers/plans/2026-06-08-airplay2-mirroring-handshake.md) | The original design plan for the AirPlay 2 mirroring handshake, kept as the historical reference for how the flow was derived. |

## Archive — `docs/archive/`

| Document | What it owns |
|---|---|
| [`archive/README.md`](archive/README.md) | The naming convention and the rules for what moves into the archive and how an archived document is marked. |

## Templates and forms — `.github/`

| Document | What it owns |
|---|---|
| [`../.github/ISSUE_TEMPLATE/bug_report.md`](../.github/ISSUE_TEMPLATE/bug_report.md) | The bug report form, including the device, the sender and the log-collection step. |
| [`../.github/ISSUE_TEMPLATE/feature_request.md`](../.github/ISSUE_TEMPLATE/feature_request.md) | The feature request form. |

---

## How to keep this index honest

The index lists **only files that exist**. When you add a document, add a row
here in the same change — that is the same-change rule of
[`DOCUMENTATION_POLICY.md`](DOCUMENTATION_POLICY.md), and it is what keeps this
page from turning into a list of hopes.
