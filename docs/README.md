# PhairPlay Documentation Index

Every document in this repository, what it owns, and where to look when you
need something. **Only files that exist are listed here** — the archive is
listed separately, at the end.

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

## What the product must do — `docs/spec/`

The product track owns this folder. It answers *what* the product does, not
*how* it is built.

| Document | What it owns |
|---|---|
| [`spec/REQUIREMENTS.md`](spec/REQUIREMENTS.md) | The requirement IDs (`FR-xx`, `NFR-xx`) with their eight attributes, the target devices, and the explicit list of what is out of scope. The source of truth for *what the product must do*. |
| [`spec/REQUIREMENTS_MATRIX.md`](spec/REQUIREMENTS_MATRIX.md) | The machine-readable matrix of the baseline v4.0: one row per requirement with its eight attributes. The generator of the traceability matrix reads it. |
| [`spec/CODEC_MATRIX.md`](spec/CODEC_MATRIX.md) | The three codec matrices (AirPlay, WFD, Cast) and the DLNA section: which codec is required, optional or out of scope per protocol. |
| [`spec/TECHNICAL_SPEC.md`](spec/TECHNICAL_SPEC.md) | The technical baseline behind the requirements: the protocol stacks, the system architecture, the AirPlay feature flags, the photo-streaming protocol, the DRM evaluation. |
| [`spec/ACCEPTANCE_CRITERIA.md`](spec/ACCEPTANCE_CRITERIA.md) | The measurable acceptance criteria — each with a "How to Verify" and a "Pass Condition" — the performance budget table, and the register of the criteria that contradict each other. |
| [`spec/TRACEABILITY.md`](spec/TRACEABILITY.md) | The **generated** chain requirement → implementation → test → evidence → status. It is written by `scripts/build-traceability.sh` and may not be edited by hand. |
| [`spec/DEVICE_PROCEDURES.md`](spec/DEVICE_PROCEDURES.md) | The hardware procedures DT-17 … DT-19 that are not part of the device test plan: cleartext in the local network, the log of a release build, the installation from the store. |
| [`spec/PROJECT_PLAN.md`](spec/PROJECT_PLAN.md) | The phased roadmap M0–M12 with the status of each milestone, the release schedule with its **triggers**, and the four distribution channels with what is open for each. |
| [`spec/REMEDIATION_BACKLOG.md`](spec/REMEDIATION_BACKLOG.md) | The prioritised, assignable measures of the product and specification track (`B-xx`). The store and release process has its own list in [`project/REMEDIATION_BACKLOG.md`](project/REMEDIATION_BACKLOG.md) (`RB-xx`); the two do not overlap. |

## How the product is built

| Document | What it owns |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | The beginner-friendly explanation of how AirPlay 2 works, the component overview per package, the full data-flow diagram, the design decisions and the security architecture. |
| [`TESTING.md`](TESTING.md) | How to run the unit, instrumented and lint suites, what is and is not covered by an automated test, the manual device test scenarios, and the performance measurement commands. |
| [`WHERE_TO_CHANGE.md`](WHERE_TO_CHANGE.md) | The navigation table "I want to change X → that is here", with the real package paths. Read this before you edit anything. |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | The six coding rules (file size, comments, tests, security, performance, flavours), the pull-request checklist and the commit-message style. |
| [`DOCUMENTATION_POLICY.md`](DOCUMENTATION_POLICY.md) | The same-change rule, the single-source-of-truth rule and the archive rule — what has to be updated together with a change, and where a document goes when it is replaced. |

## Security and compliance

| Document | What it owns |
|---|---|
| [`security/THREAT_MODEL.md`](security/THREAT_MODEL.md) | The trust boundaries, the assets, the entry points of an untrusted input and the assumptions each guarantee rests on. |
| [`security/FINDINGS.md`](security/FINDINGS.md) | Every security finding with its severity, the attack path, the fix and the test that proves it — plus the accepted residual risks. The single source of truth for the security state. |
| [`security/PERMISSIONS.md`](security/PERMISSIONS.md) | Every declared permission, why the app asks for it, and what it does **not** enable. |
| [`compliance/GOOGLE_PLAY_COMPLIANCE.md`](compliance/GOOGLE_PLAY_COMPLIANCE.md) | Every requirement of the Google Play Store for an Android TV app: the source with the URL and the retrieval date, the verbatim wording of the rule, the state and the evidence. |
| [`compliance/DATA_SAFETY_DRAFT.md`](compliance/DATA_SAFETY_DRAFT.md) | The answers of the Play data safety form, derived from what the code does — the app sends nothing, measures nothing, and asks for nothing but the network. |
| [`compliance/STORE_SUBMISSION_CHECKLIST.md`](compliance/STORE_SUBMISSION_CHECKLIST.md) | The steps of the Play console in the order in which they depend on each other, with the file of the repository that belongs to each step. |

## Quality — `docs/quality/`

| Document | What it owns |
|---|---|
| [`quality/QUALITY.md`](quality/QUALITY.md) | The six-dimensional quality model with the measurement method, the current state and the target value per dimension, plus the severity grades S0–S4. |
| [`quality/CI-PIPELINE.md`](quality/CI-PIPELINE.md) | The pipeline of the repository: the jobs, the gates, the self-tests that prove the gates turn red, and the artefacts each job leaves behind. |

## Project status — `docs/project/`

| Document | What it owns |
|---|---|
| [`project/RELEASE_READINESS.md`](project/RELEASE_READINESS.md) | What is still missing before `v1.0.0`: what is proven, what is open, in which order it has to be closed, and which points genuinely need real hardware. |
| [`project/RISKS.md`](project/RISKS.md) | The maintained risk register: every known risk with likelihood, impact, countermeasure, owner and status — the list of what could still go wrong. |
| [`project/REMEDIATION_BACKLOG.md`](project/REMEDIATION_BACKLOG.md) | The prioritised measures of the store, the console and the release process (`RB-xx`). |

## Guides for users — `docs/guides/`

| Document | What it owns |
|---|---|
| [`guides/INSTALLATION.md`](guides/INSTALLATION.md) | Every installation path: ADB, the sideloading apps, both flavours, and the developer options that have to be enabled first. |
| [`guides/TROUBLESHOOTING.md`](guides/TROUBLESHOOTING.md) | The failure catalogue: the device does not appear in the picker, the TV sleeps during streaming, no audio, no video, and the router settings that cause each. |
| [`guides/NETWORK_CHECK.md`](guides/NETWORK_CHECK.md) | The network check the owner runs from the laptop before a playback test: the discovery, the description document, the advertised address and the AirPlay port — with the expected output for every verdict. |
| [`guides/CAST_APP_ID.md`](guides/CAST_APP_ID.md) | How to register a Google Cast receiver app ID, which is required to test the Cast stack end to end. `scripts/cast-registration-info.sh` prints the values the console asks for. |
| [`guides/CASTING_TO_A_TV.md`](guides/CASTING_TO_A_TV.md) | The step-by-step guide for getting a picture onto the television — from macOS, from Windows, from VLC and from a mobile UPnP controller — including the honest `file:///` limitation and the HTTP workaround. |
| [`guides/DEVICE_TEST_PLAN.md`](guides/DEVICE_TEST_PLAN.md) | The 16 copy-pasteable hardware cases DT-01 … DT-16: the exact commands, the expected result, the time limit and the evidence per case — plus the result sheet for the Google TV and the Fire TV. |
| [`guides/RELEASING.md`](guides/RELEASING.md) | How the owner cuts a signed release (`.apk` and `.aab`) and publishes it to GitHub Releases with `./scripts/release.sh`. |
| [`guides/RELEASE_ARTIFACTS.md`](guides/RELEASE_ARTIFACTS.md) | What is in the artefact directory of a release, how long it is kept, how to retrieve it — and what the build **without** publishing does not do. |
| [`guides/GOOGLE_PLAY_STORE.md`](guides/GOOGLE_PLAY_STORE.md) | Step-by-step guide for publishing the `googletv` Android App Bundle (`.aab`) on the Google Play Store, including Android TV opt-in, Data Safety answers, store listing texts and store graphics in `docs/playstore/assets/`. |

> **One honest limitation applies to every user-facing guide:** a UPnP renderer
> fetches the media itself. A `file:///` URI points at the *sender's* disk and
> cannot be read by the television, so rendering a local file works only for
> network-reachable `http`/`https` URLs. This is a property of the protocol,
> not a bug.

## Architecture decisions — `docs/adr/`

Accepted decisions. An agent that wants to change one of them writes a CR, not
a pull request.

| Document | What it owns |
|---|---|
| [`adr/ADR-001-multi-protocol.md`](adr/ADR-001-multi-protocol.md) | Why AirPlay 2, Miracast and Google Cast are supported side by side, each independently switchable, and how DLNA was added as a fourth option. |
| [`adr/ADR-002-service-architecture.md`](adr/ADR-002-service-architecture.md) | Why the receiver runs as a foreground service and how the receiver lifecycle is modelled. |
| [`adr/ADR-003-ui-design.md`](adr/ADR-003-ui-design.md) | Why the UI is View-based instead of Jetpack Compose, and how the TV design language is applied. |
| [`adr/ADR-004-dlna-mediarenderer-roadmap.md`](adr/ADR-004-dlna-mediarenderer-roadmap.md) | Why a UPnP/DLNA MediaRenderer was adopted, and the four-phase delivery plan D1–D4 behind it. |

## Templates and forms

| Document | What it owns |
|---|---|
| [`acceptance/ACCEPTANCE_TEMPLATE.md`](acceptance/ACCEPTANCE_TEMPLATE.md) | The template for a new acceptance record: functional, technical, performance, usability, accessibility, security/privacy, interoperability, known limitations, decision. |
| [`adr/ADR_TEMPLATE.md`](adr/ADR_TEMPLATE.md) | The template for a new architecture decision record. |
| [`cr/CR_TEMPLATE.md`](cr/CR_TEMPLATE.md) | The template for a change request against an accepted requirement or an accepted decision. |
| [`../.github/ISSUE_TEMPLATE/bug_report.md`](../.github/ISSUE_TEMPLATE/bug_report.md) | The bug report form, including the device, the sender and the log-collection step. |
| [`../.github/ISSUE_TEMPLATE/feature_request.md`](../.github/ISSUE_TEMPLATE/feature_request.md) | The feature request form. |

## Archive — `docs/archive/`

Everything that was **superseded** on 2026-11-13 lives in one folder: the
security audit of 2026-10-24, the acceptance records of the milestone rounds
M6 … M12, the review of the pull requests of 2026-10-24, the plan of the
AirPlay handshake, and the previous versions of the project plan, the
requirements and the acceptance criteria.

| Document | What it owns |
|---|---|
| [`archive/README.md`](archive/README.md) | The index of the archive: the rules for what moves in, how an archived document is marked, and which document replaced it. |

---

## How to keep this index honest

The index lists **only files that exist**. When you add a document, add a row
here in the same change — that is the same-change rule of
[`DOCUMENTATION_POLICY.md`](DOCUMENTATION_POLICY.md), and it is what keeps this
page from turning into a list of hopes.

When a document is replaced, move it to [`archive/`](archive/) with the
banner of the archive rule and add it to the index of the archive — in the
same change, and never without a link to its successor.
