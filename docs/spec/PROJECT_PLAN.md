# PhairPlay – Project Plan

Version: 2.5
Status: Active
Date: 2026-11-05
Last Updated: 2026-11-05

> **Revision 2.5 (2026-11-05).** The status of every phase was checked against
> the code on `main` @ `34a166c` — **not** against the checkboxes of the older
> plan, and not against the checkboxes of this document. The baseline of the
> requirements is now v4.0 ([`REQUIREMENTS.md`](REQUIREMENTS.md),
> [`REQUIREMENTS_MATRIX.md`](REQUIREMENTS_MATRIX.md)), the traceability chain is
> generated ([`TRACEABILITY.md`](TRACEABILITY.md)), and the acceptance criteria
> carry a contradiction register
> ([`ACCEPTANCE_CRITERIA.md`](ACCEPTANCE_CRITERIA.md) §0.8).
>
> **What changed against v2.4:**
>
> 1. **The numbers were measured again.** The test count, the key count of the
>    translations, the file-size gate and the KDoc gate are stated with the
>    command that produces them and with the date. Several of the figures of
>    v2.4 were stale — the translations have 129 keys, not 80.
> 2. **The phase sections were compressed.** The long task lists of v2.4 listed
>    work that is either done or explicitly out of scope. What is left is what
>    a reader needs: what exists, what does not, and where the proof is.
> 3. **A new section "Release Readiness and Distribution Channels"** names the
>    four channels the project ships through and the conditions that are still
>    open for each of them.
> 4. **The previous version is archived unchanged:**
>    [`archive/PROJECT_PLAN-v2.4.md`](archive/PROJECT_PLAN-v2.4.md).

---

## Phase Order

```
Phase 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12
 Spec   Skel mDNS AirPl AirPl AirPl Mira Cast Stab FireT i18n  Rel   DLNA
        +UI  disc Hand Video Audi      Recv Recv  Port poly       Media
                    +Sho +Ph  +Opt  Full       TV           Renderer
```

| Phase | Milestone | Phase | Milestone |
|---|---|---|---|
| 0 | M0 – Spec | 7 | M7 – Google Cast |
| 1 | M1 – Skeleton + UI | 8 | M8 – Stability |
| 2 | M2 – mDNS | 9 | M9 – Fire TV |
| 3 | M3 – AirPlay handshake | 10 | M10 – i18n |
| 4 | M4 – AirPlay video | 11 | M11 – Release |
| 5 | M5 – AirPlay audio | 12 | M12 – DLNA |
| 6 | M6 – Miracast | | |

## Status Overview

> **How to read the statuses.**
> * **✅ Complete** — the code is there, the tests exist and are green, and the
>   remaining device validation is explicitly marked as open in the acceptance
>   record.
> * **🟡 Implemented, device validation open** — the code is there and the unit
>   tests are green, but the behaviour on a television has never been observed.
> * **🟠 Partial** — a defined part of the milestone is delivered, a defined
>   part is not, and the split is written down.
> * **🔴 Blocked** — the milestone cannot advance without something that is not
>   in the repository (an application ID, a system privilege, hardware).

| Phase | Milestone | Status | Notes (measured 2026-11-05 on `34a166c`) |
|---|---|---|---|
| 0 | M0 – Spec | ✅ Complete | The baseline is v4.0: the long text, the eight-attribute matrix, the codec matrix, the traceability matrix and the acceptance criteria with its contradiction register |
| 1 | M1 – Skeleton | 🟡 Implemented, device validation open | UI, settings, foreground service and both APK flavours build; the navigation pass on real hardware is open |
| 2 | M2 – mDNS | 🟡 Implemented, device validation open | AirPlay mDNS (`_airplay._tcp`, `_raop._tcp`), the InfoResponder, the Miracast WFD advertising and the Cast lifecycle exist; the picker entry is not proven on a device |
| 3 | M3 – AirPlay Handshake | 🟡 Implemented, device validation open | Full RTSP router, SDP parsing, plist codec, pairing (Ed25519/X25519 + SRP), FairPlay fp-setup, `/photo`; **248** AirPlay test methods are in the tree; the macOS/iOS handshake is not proven on a device |
| 4 | M4 – AirPlay Video | 🟡 Implemented, device validation open | H.264 via `MirrorStreamServer` + `MirrorCrypto`, MediaCodec with SPS-driven reinit and self-heal, aspect-fit rendering; the fps / latency budget is **not measured** |
| 5 | M5 – AirPlay Audio | 🟡 Implemented, device validation open | AAC-ELD/AAC-LC, ALAC, AES-128-CBC, NTP sync, DACP reverse remote, NowPlaying screen; the A/V sync budget is **not measured** |
| 6 | M6 – Miracast | 🟠 Partial — control plane only | Discovery and the WFD RTSP control plane are implemented and tested; the **media path does not exist** and cannot exist for a sideloaded app (ADR-004, FR-58 … FR-60) |
| 7 | M7 – Google Cast | 🟠 Partial — graceful degradation only | The Cast Connect SDK lifecycle is implemented, the Fire TV flavour degrades to `DISABLED`, the unconfigured Google TV build reports `ERROR`; the end-to-end receiver needs a registered application ID and hardware |
| 8 | M8 – Stability | 🟠 Partial | The frame-queue budget and the mDNS registration bookkeeping are proven by unit tests; the 30-minute session, the RAM, the CPU and the re-advertisement after a network outage are **not measured** |
| 9 | M9 – Fire TV | 🟡 Implemented, device validation open | The flavour builds, carries no Play Services and degrades honestly; it has never been run on a Fire TV |
| 10 | M10 – i18n | 🟠 Partial | EN / DE / FR are complete — **129 keys each**, proven by `scripts/check-i18n-completeness.sh`; the RTL baseline and the locale-aware formatting are still open; the texts have never been seen on a device |
| 11 | M11 – Release | 🟠 Beta — not ready for v1.0.0 | `v1.0.0-alpha.7` is published (2026-11-02, 592 tests, 0 failures, lint green for both flavours). Still open: no signed APK was verified here, no device test was executed, and **8 of the 14 audit findings are open** |
| 12 | M12 – DLNA | 🟠 Partial — control plane delivered, media path open | SSDP, description server, hardened SOAP dispatcher, GENA eventing, the Samsung DIAL extension and the playback wiring are implemented with **178** test methods; the media URI has an address policy **and** a scheme allow-list of `http`/`https` only (closed 2026-11-05) — still open: the accepted **public addresses**, the unresolved host names (SEC-04), the cleartext configuration (NFR-28) and the control point that has never talked to the renderer |

The per-milestone acceptance records live in
[`../acceptance/`](../acceptance/): `M06-ACCEPTANCE.md` … `M12-ACCEPTANCE.md`.
The open risks live in [`../project/RISKS.md`](../project/RISKS.md). The
prioritised measures of this cycle live in
[`REMEDIATION_BACKLOG.md`](REMEDIATION_BACKLOG.md).

---

## The phases in short

The long task lists of v2.4 are archived. What follows is what each phase
delivers, what it does **not** deliver, and where the proof is.

| Phase | Delivered | **Not** delivered | Proof |
|---|---|---|---|
| **M0** Spec | The requirement baseline v4.0 with the eight attributes, the codec matrix, the acceptance criteria and the traceability matrix | — | [`REQUIREMENTS.md`](REQUIREMENTS.md), [`REQUIREMENTS_MATRIX.md`](REQUIREMENTS_MATRIX.md), [`TRACEABILITY.md`](TRACEABILITY.md) |
| **M1** Skeleton | Home screen with the four protocol cards, settings screen, foreground service with the notification and its two actions, DataStore persistence | the navigation pass on real hardware | `MainActivityTest` (9), `ServiceStateTest` (19), `AppSettingsTest` (19) |
| **M2** mDNS | AirPlay advertisement with the TXT record, the WFD advertisement, the Cast lifecycle, the device name in every advertisement | the appearance of the television in a real picker | `MdnsServiceTest` (12), `MdnsServiceNameTest` (9) |
| **M3** Handshake | The whole RTSP router, the plist codec, the pairing, `/photo` | the handshake with a real macOS or iOS sender | 248 test methods under `airplay/` |
| **M4** Video | H.264 mirroring, the bounded frame queue, the aspect-fit rendering | the measured fps and latency | `MirrorFrameQueueTest` (13), `OutputSizeResolverTest` (12), `VideoDecoderTest` (5) |
| **M5** Audio | AAC-ELD/AAC-LC, ALAC, the NTP sync, the DACP reverse remote | the measured A/V drift | `TimingHandlerTest` (17), `AudioPlayerEncryptionTest` (12), `AlacDecoderTest` (3) |
| **M6** Miracast | The discovery and the WFD control plane | **the entire media path** — no MPEG-TS demuxer, no WFD decoder; excluded on purpose | `MiracastReceiverTest` (5) |
| **M7** Cast | The SDK lifecycle and the honest degradation on both flavours | the end-to-end receiver — it needs a registered application ID | `CastReceiverTest` (3) on both flavours |
| **M8** Stability | The bounded queue, the bounded mDNS retry, the network recovery monitor | the 30-minute session, the RAM, the CPU, the re-advertisement on a device | `MdnsRegistrationTrackerTest` (10), `DlnaReadvertisePlanTest` (7) |
| **M9** Fire TV | The flavour, the `minSdk 25`, the honest Cast degradation | a single run on a Fire TV | the build; the lint report of the `firetv` variant |
| **M10** i18n | 129 keys in EN, DE and FR | the RTL baseline, the locale-aware formatting, the texts on a device | `scripts/check-i18n-completeness.sh` → exit 0, `LocaleFormattingTest` (15) |
| **M11** Release | The release script, the changelog, the CI quality gate, the store assets, the privacy policy gate | a verified signed APK, a single executed device test case | `scripts/release.sh`, the 5 gates of `.github/workflows/ci.yml` |
| **M12** DLNA | SSDP, the description server, the SOAP dispatcher, the GENA eventing, the Samsung DIAL extension, the media fetch with an address **and scheme** policy | the accepted public addresses, the cleartext configuration (NFR-28), the control point that has never talked to the renderer | 178 test methods under `dlna/` |

**Definition of Done per milestone:** the binding acceptance criteria of the
milestone — `AC-M<n>.x` for M6 … M12 and `AC-V4.x` for the baseline v4.0 — in
[`ACCEPTANCE_CRITERIA.md`](ACCEPTANCE_CRITERIA.md). The legacy rounds AR-0 …
AR-8 are archived and are **not** a proof: their device criteria were never
executed.

---

## Release Schedule

> This section is **binding**: it defines when a release happens and when binaries
> may be shipped. A date below is a plan; a *trigger* is the decision. When the
> trigger is met, the release is built and published — when it is not, nothing is
> published, no matter what the calendar says.

### Version scheme

| Stage | Version scheme | What it means | Channel |
|---|---|---|---|
| Alpha | `v1.0.0-alpha.N` | Code complete for a milestone, **not validated on any television** | GitHub Releases, marked *prerelease* |
| Beta | `v1.0.0-beta.N` | Validated on real hardware, defects known and listed | GitHub Releases, marked *prerelease* |
| Stable | `v1.0.0` | Validated, measured, ready for the general user | GitHub Releases, marked *latest* |

### Triggers

Every trigger below is a condition on **measured** state, never on a date. A
release is only built when every condition of its row is true.

| Stage | Trigger — all conditions must hold | Platforms in the release | Artifacts |
|---|---|---|---|
| **Alpha** | 1. A milestone that changes the runtime behaviour is merged.<br>2. `:test-runner:test` is green (0 failures, 0 errors, 0 skipped).<br>3. The quality gate is green: `scripts/check-file-size.sh`, `scripts/check-kdoc-headers.sh`, `scripts/check-i18n-completeness.sh`, `scripts/check-docs-links.sh`, `scripts/check-privacy-policy.sh`.<br>4. Lint is green for **both** flavours.<br>5. Both APK flavours build. | **Google TV** (Android 10+, `com.phairplay.googletv`)<br>**Fire TV** (Android 7.1+, `com.phairplay.firetv`) | Signed release APKs (minified, resources shrunk) + source tag |
| **Beta** | 1. Every case of [`../guides/DEVICE_TEST_PLAN.md`](../guides/DEVICE_TEST_PLAN.md) has been executed on real hardware — 16 of 16.<br>2. The decisive cases are green: **DT-09** (VLC renderer list), **DT-10** (Windows "Cast to Device"), **DT-12** (Samsung Smart View), **DT-13/DT-14** (playback), **DT-15** (30-minute session), **DT-16** (picture aspect ratio).<br>3. The performance numbers are measured, not estimated: RAM, CPU load, frame rate, latency (see [`../quality/QUALITY.md`](../quality/QUALITY.md)).<br>4. No open finding of severity S1 or S2 (see [`../project/RISKS.md`](../project/RISKS.md)).<br>5. The acceptance records M01 … M12 name the device they were validated on. | **Google TV** — validated on a real Google TV / Android TV device<br>**Fire TV** — validated on a real Fire TV device | Signed release APKs + release notes listing the known limitations |
| **Stable** | 1. A beta has been in the owner's hands for **at least two weeks** without a new critical finding.<br>2. Every acceptance record carries a *device validated* decision — no *"not measured"* left in a performance field.<br>3. The checklist in [`../project/RELEASE_READINESS.md`](../project/RELEASE_READINESS.md) is complete.<br>4. The release is signed with the **owner's production key** (an alpha key must not be used for a stable release).<br>5. The changelog and the release notes state the platform, the minimum Android version and the known limitations. | **Google TV** and **Fire TV** — both, always. A stable release that ships for one platform only is a defect, not a decision. | Signed release APKs + changelog + release notes |

### Release train

| Release | Planned | Trigger met by | Platforms | Content |
|---|---|---|---|---|
| `v1.0.0-alpha.1` … `alpha.6` | 2026-10-25 … 2026-11-02 — **shipped** | the M6–M18 cycles, the security findings SEC-01/02/03, the quality gate, the lint, the Play Store preparation, the aspect-ratio fix | Google TV + Fire TV | see `CHANGELOG.md` |
| `v1.0.0-alpha.7` | 2026-11-02 — **shipped** | the privacy policy is reachable from inside the app, a new gate keeps it in step with the declared permissions, the certificate fingerprint in the guides is corrected | Google TV + Fire TV | the signed `.aab` for the Play Console, the store graphics and the step-by-step guide |
| `v1.0.0-alpha.8` | *not scheduled* | the baseline v4.0 is merged and the traceability gate is part of the quality gate | Google TV + Fire TV | the requirement baseline v4.0, the traceability matrix, the remediation backlog |
| `v1.0.0-beta.1` | 2026-11-15 — *planned* | the owner has executed the 16 device test cases | Google TV + Fire TV | the same code as the last alpha, with the device findings fixed |
| `v1.0.0-beta.2` | 2026-11-29 | the findings of beta.1 are fixed and re-validated | Google TV + Fire TV | fixes from the device test round |
| `v1.0.0` | 2026-12-13 | two weeks of beta on real devices, no new critical finding, production key | Google TV + Fire TV | the first stable release |

**If a trigger is not met, the release is skipped** — the version number is
burned, not reused, and the next release gets the next number. A release that
does not meet its trigger never gets built.

---

## Release Readiness and Distribution Channels

> This section names the **four channels** the project ships through, the
> artefact of each channel, and the conditions that are still **open** for it.
> The step-by-step guide for the store is
> [`../guides/GOOGLE_PLAY_STORE.md`](../guides/GOOGLE_PLAY_STORE.md); the order
> of the steps and the two facts that cost time if they are unknown are
> described in the section "Publication in the Google Play Store" of v2.4,
> which is unchanged and **not** repeated here.
>
> **The rule that applies to every channel:** a channel is only opened when the
> trigger of its stage is met. An alpha is not a beta, and a sideloaded APK is
> not a store release — the same binary, the same commit, but a different claim
> in the release notes.

### The four channels at a glance

| # | Channel | Artefact | Signed by | Who installs it | Claim of the release notes |
|---|---|---|---|---|---|
| 1 | **Google Play — internal testing** | the `.aab` of the release | the upload key, then Play App Signing | the owner and the testers, through the Play console | "alpha — not validated on a television" |
| 2 | **Google Play — production** | the `.aab` of the release | Play App Signing | anybody from the store | "stable — measured on the devices named in the release notes" |
| 3 | **GitHub — APK** | `app-googletv-release.apk`, `app-firetv-release.apk` | the local release key | the user, with `adb install` or a download | "alpha or beta — sideloaded, no store guarantee" |
| 4 | **Amazon Fire TV** | `app-firetv-release.apk` | the local release key | the user, with the Amazon download or `adb install` | "alpha or beta — no Google Cast on this platform by design" |

### The open conditions per channel

| Channel | What it needs | **Open today (2026-11-05)** | Trigger |
|---|---|---|---|
| **1 — Google Play internal** | the `.aab`, the store graphics, the listing texts, the data safety form, the privacy policy reachable in the app | (a) the internal track has **never** been used on a real television — **DT-19**; (b) the Cast application ID must be registered with the **Play App Signing** SHA-1, not with the upload key — a Cast ID of the upload key does not work on the store build; (c) the target API level must be checked against the policy of the submission day (NFR-21); (d) the 16 KB page alignment of the shipped libraries is unverified (NFR-22) | the **alpha** trigger + a signed `.aab` |
| **2 — Google Play production** | everything of channel 1, plus the content rating, the target audience, the foreground-service declaration and the Android TV form factor opt-in | (a) all of channel 1; (b) the **16 device test cases** have never been executed — the production rollout trigger ("the device tests are executed and the internal track has been used on a real Google TV") is therefore not met; (c) an app installed from the store is signed by Google, so a **previously sideloaded** version has to be uninstalled once — that is a support question, not a defect | the **beta** trigger + the internal track proven on a real Google TV |
| **3 — GitHub APK** | the two signed APKs, their checksums, the release notes with the open device cases | (a) the release key belongs to the owner — no signed artefact could be verified in this environment (NFR-33); (b) the fingerprint of the sideloaded build differs from the fingerprint of the store build — the two cannot be swapped without an uninstall; (c) the APK carries the fingerprint of the local key, which is **not** the fingerprint of the Play build — the guides must not mix them up | the **alpha** trigger |
| **4 — Amazon Fire TV** | the `firetv` APK, the Fire TV column of the device test plan, the note that Cast is disabled by design | (a) the Fire TV has **never** been run — 0 of the 16 cases on this platform; (b) the Cast card must explain that Google Play Services are missing (FR-69 — implemented and unit-tested, not seen on a device); (c) the flavour carries no Google Cast classes by design — a store release that promises Cast on Fire TV would be a defect | the **alpha** trigger; a **stable** release always ships for Google TV **and** Fire TV |

### What "release ready" means per channel

| Channel | The claim that may be made | The claim that may **not** be made |
|---|---|---|
| 1 — internal | "installed from the store, alpha" | "validated on a television" |
| 2 — production | "measured on the devices named in the release notes" | anything about a device that was not part of the 16 cases |
| 3 — GitHub APK | "sideloaded build, the open device cases are listed" | "the same as the store build" — the signature differs |
| 4 — Fire TV | "Fire TV build without Google Cast" | "supports Google Cast" — it cannot, by design |

### The readiness checklist that applies to all four channels

1. The quality gate is green **on the commit that the tag points at** — not on
   a neighbouring commit.
2. `:test-runner:test` is green with 0 failures, 0 errors and **0 skipped** — a
   skipped test is a hidden gap, not a pass.
3. Lint is green for **both** flavours.
4. `scripts/build-traceability.sh` is green — every requirement has a proof.
   The traceability gate belongs to the quality gate from the next alpha on.
5. Every finding of the security audit is either closed or documented as open
   **with a reason and an owner** — 8 of the 14 findings are open today
   (SEC-04, SEC-05, SEC-06, SEC-07, SEC-08, SEC-09, SEC-10, SEC-11).
6. The release notes list the device test cases that are still open — an alpha
   that hides this is a lie in the changelog.
7. The version lives in one place: `versionCode` and `versionName` are bumped
   in the same commit that the release tag is created from.

---

## Rules that apply to every release

1. **One version, both platforms.** A release ships the Google TV *and* the
   Fire TV flavour, or it does not ship. A platform that cannot be built blocks
   the release — it does not get a reduced version.
2. **The binaries are the artefact.** The gate in the trigger column is run
   against the **same commit** that the tag points at. A release built from an
   untested commit is not a release.
3. **The release notes say what was not tested.** Every release lists the
   device test cases that are still open.
4. **The version lives in one place.** `versionCode` and `versionName` in
   `app/build.gradle.kts` are bumped in the same commit that the release tag is
   created from.
5. **Every release gets an entry in [`../../CHANGELOG.md`](../../CHANGELOG.md)**,
   written for a user, not for a colleague.

**How a release is cut:** [`../guides/RELEASING.md`](../guides/RELEASING.md) —
one command, and it refuses to publish an unchecked build.

---

## Risk Register

> The maintained risk register lives in
> [`../project/RISKS.md`](../project/RISKS.md) — 17 entries with the likelihood,
> the impact, the countermeasure, the owner and the status. It is authoritative.
> The original planning view of the risks was part of v2.4 and is archived with
> it: [`archive/PROJECT_PLAN-v2.4.md`](archive/PROJECT_PLAN-v2.4.md).
>
> The measures of the security, compliance and quality findings of 2026-11 are
> in [`REMEDIATION_BACKLOG.md`](REMEDIATION_BACKLOG.md) — they are *not* risks
> but concrete, assignable work.
