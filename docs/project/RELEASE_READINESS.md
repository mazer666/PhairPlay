# Release Readiness — v1.0.0 (stable)

**Target:** `v1.0.0` — a stable release, not a beta.
**Prepared by:** Project Manager
**Date:** 2026-10-24
**Measured on:** commit `51933eb` (branch `feature/quality-m6-m12`, merged into
`feature/project-status`)

---

## 0. The one-paragraph version

The code is in good shape: 457 JVM tests run green, both debug flavours build,
the four protocol stacks are separated, the DLNA renderer exists with 157 tests,
and the security documentation is complete. What is missing is **evidence** and
**three decisions**. No device test of any kind has been executed — 0 of the 15
cases of the device test plan. The CI quality gate is red because 16 class
declarations lack a KDoc header, and the lint is red as well: it reports exactly
one finding (`UnusedAttribute` — `app/src/main/AndroidManifest.xml:58` uses an
attribute that needs API 31, while the `googletv` flavour has `minSdk = 29`),
which also means the `firetv` flavour was never linted at all. Three High
security findings are still open in
the code, one of them (the unbounded FU-A reassembly buffer) directly in the
AirPlay media path. And the DLNA media path has two open questions that only
the owner can answer: the media URI has no address policy (SEC-04, an S3 that
needs a written owner decision), and
the app declares no network security configuration, so the cleartext `http://`
media fetch is probably blocked on API 35.

**Recommendation:** cut `v1.0.0-beta.4` once items R1–R4 are closed, and cut
`v1.0.0` only after the device test plan has been executed and the three
security findings are closed or carry a written owner decision.

---

## 1. What is already proven (measured on `51933eb`)

| # | Fact | How it was measured |
|---|---|---|
| 1 | The JVM protocol suite is green: **457 tests, 0 failures, 0 errors, 0 skipped**, 54 test classes | `./gradlew :test-runner:test --offline` → `BUILD SUCCESSFUL in 3m 27s`, exit 0 |
| 2 | Both debug flavours build | `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline` → `BUILD SUCCESSFUL` |
| 3 | The file-size check is green | `scripts/check-file-size.sh` → exit 0, `177 file(s) checked, none above the hard limit of 550 lines` |
| 4 | The documentation-link check is green | `scripts/check-docs-links.sh` → exit 0, `191 internal link(s) resolve` |
| 5 | The language-completeness check is green | `scripts/check-i18n-completeness.sh` → exit 0, `80 key(s) … complete in 2 language(s)` |
| 6 | The DLNA stack is substantial and covered | 28 production files in `com.phairplay.dlna`, 157 green tests |
| 7 | The AirPlay stack is substantial and covered | 202 green tests in `com.phairplay.airplay` and `.handshake` |
| 8 | The Samsung Smart View extension is covered | 29 green tests (`SamsungSsdpResponderTest`, `DialAppCatalogTest`, `DialEndpointTest`, `DlnaDeviceDescriptionFieldsTest`) |

---

## 2. The checklist

The order matters: **R1 → R2 → R3** can be done in a week without hardware and
they unblock the pipeline. **R4 → R5** need hardware. **R6** needs a decision
from the owner and nothing else. **R7 → R9** are the release mechanics.

### Block 1 — the pipeline (no hardware needed)

| # | Item | Gate | Evidence of done | Owner |
|---|---|---|---|---|
| **R1** | Add the 16 missing KDoc headers, **or** decide that test files are exempt (`--skip-tests` in the CI call) | `scripts/check-kdoc-headers.sh` → exit 0 | `OK: KDoc header check passed — 222 class declaration(s) checked` | Architect + file owners |
| **R2** | Make the lint green for both flavours | `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug` → exit 0 | 0 findings (`warningsAsErrors = true` in `app/build.gradle.kts`). **Measured:** the `googletv` variant fails with `1 errors, 0 warnings` — `app/src/main/AndroidManifest.xml:58: Error: Attribute usesPermissionFlags is only used in API level 31 and higher (current min is 29) [UnusedAttribute]` (report: `app/build/reports/lint-results-googletvDebug.txt`). The fix is `tools:targetApi="s"` on the permission element or a `values-v31` manifest variant; after that the `firetv` variant still has to be linted for the first time. | Platform |
| **R3** | Decide the 400 vs. 550 line rule and make the texts agree | either `--strict` in the CI call, or the rule texts rewritten to soft/hard | `docs/CONTRIBUTING.md` and NFR-23 match the script | Owner (rule decision) |
| **R3b** | Split the three files above 400 lines, or accept them with a written reason | `scripts/check-file-size.sh` → 0 warnings | `MainActivity.kt` 447, `MiracastReceiver.kt` 439, `airplay/RtspHandlerTest.kt` 413 | Architect |

### Block 2 — the security decisions (owner decisions, no hardware needed)

| # | Item | Gate | Evidence of done | Owner |
|---|---|---|---|---|
| **R6a** | Reduce the DLNA media URI allowlist to `http`/`https` and reverse the test `SetAVTransportURI accepts a file URI and an rtsp URI` | AC-M12.15 | a new negative test: `file:///etc/passwd` → SOAP fault `402`; `DlnaMediaUri.ALLOWED_SCHEMES` no longer contains `file` | DLNA track |
| **R6b** | Decide on the network security configuration (CR-02) | either a `network_security_config.xml` that permits cleartext only for private LAN ranges, **or** a documented statement that the DLNA media path requires `https` | the decision is written down; `app/src/main/res/xml/network_security_config.xml` exists **or** the limitation is in `README.md` and in the guides | Owner |
| **R6c** | Close SEC-03 (the unbounded FU-A reassembly buffer) | a bound in `RtpInterleaved.handleFuaFragment` plus a negative test | `MAX_FUA_BYTES` and the test `a FU-A stream that never ends does not grow without bound` | AirPlay track |
| **R6d** | Close SEC-01 (the arbitrary-URL fetch through `POST /play`) or get a written owner decision | a scheme check in `RtspVideoUrlHandler.handleVideoPlay` plus a negative test | `file://` and `content://` are refused with `400` | AirPlay track |
| **R6e** | Close SEC-02 (the image decompression bomb in `PhotoScreen` / `NowPlayingScreen`) or get a written owner decision | a `SafeBitmapDecoder` with a maximum edge, plus a negative test | the negative test `rejects a picture whose dimensions exceed the limit` | UI track |

> The quality model (`docs/quality/QUALITY.md` §7) says: **no release may ship
> with a known S4 finding.** SEC-01, SEC-02 and SEC-03 are all S4. Until they
> are fixed or carry a written owner decision, the release is a beta.

### Block 3 — the hardware (blocks the "stable" claim)

| # | Item | Gate | Evidence of done | Hardware needed |
|---|---|---|---|---|
| **R4** | Execute the device test plan on a **Google TV** and a **Fire TV** | 15 cases, both flavours, the result sheet of `docs/guides/DEVICE_TEST_PLAN.md` §6 is filled | the filled result sheet with the log excerpts and the screenshots | H1 (Google TV), H2 (Fire TV) |
| **R4a** | DT-01 … DT-05: install, start, AirPlay video, AirPlay audio, pairing PIN | the picture appears, the sound is audible, the PIN blocks an unpaired sender | screenshots + `adb logcat` | H1/H2 + a macOS sender (H3) |
| **R4b** | DT-08 … DT-11: SSDP discovery, **VLC renderer**, SOAP control, GENA event | three `200 OK` answers; PhairPlay in the VLC renderer list within 10 s; the SOAP answers; the `LastChange` arrives | the SSDP probe output, the VLC list photo, the `curl` output | H1/H2 + a sender PC with VLC (H5) |
| **R4c** | DT-12: the German and the French texts on the device | no text is cut off, no key falls back to English | screenshots | H1/H2 |
| **R4d** | DT-15: 30-minute session, RAM, CPU | 0 crashes, ≤ 150 MB, ≤ 30 % | `dumpsys meminfo` + `top` samples | H1/H2 + a macOS sender |
| **R4e** | DT-06, DT-07: Miracast from Windows, Google Cast from Chrome | the honest result — including "not applicable" with a reason | the log excerpt | H1 + a Windows PC (H4), H1 + a Chrome device |
| **R5** | The **Samsung** visibility check (not in the plan yet) | PhairPlay appears in the Smart View list of a Samsung television | a photo of the list | a Samsung television + a Galaxy phone |

### Block 4 — the release mechanics

| # | Item | Gate | Evidence of done | Owner |
|---|---|---|---|---|
| **R7** | Correct the README | the DLNA status, the version (`1.0.0-beta.3`) and the `file:///` statement are correct | `grep -n "file:///" README.md` finds the statement; the README no longer says "planned" | Docs track |
| **R8** | Produce the signed release APKs and verify them | `apksigner verify` reports "Verified" for both | the two APKs and the verification output | Release owner |
| **R9** | Back up the release keystore outside the team-controlled storage | a backup exists and the owner can restore it | a written confirmation | Owner |
| **R9b** | Bump the version to `1.0.0` (or `1.0.0-beta.4`) and write the changelog entry | the version name in `app/build.gradle.kts` equals the tag | `grep -n "versionName" app/build.gradle.kts` | Release owner |
| **R9c** | Decide on the open product points (see the handover) | the decisions are written down | the decision list | Owner |

---

## 3. What genuinely needs real hardware

This is the honest list. **None of it can be replaced by a unit test, a script or
a reading of the code.**

| Hardware | What it decides | Which criteria |
|---|---|---|
| **Google TV** (Chromecast with Google TV, Android 10+) | Whether the receiver works at all: the AirPlay picture, the 30-minute stability, the RAM and the CPU budget, the German and French texts, the notification, the boot receiver, the DLNA discovery in a real LAN | AC-M8.1 … AC-M8.8, AC-M10.5, AC-M10.6, AC-M12.1 … AC-M12.3, AC-M12.18, AC-M12.19, DT-01 … DT-05, DT-08 … DT-15 |
| **Fire TV** (Stick, Android 7.1 / API 25) | Whether the oldest supported platform still works: the same AirPlay path on API 25, the D-pad navigation, the DLNA discovery, the APK size | AC-M9.1 … AC-M9.6, DT-01 … DT-05, DT-08, DT-12 … DT-15 (Fire TV column) |
| **VLC 3.0+ on a PC in the same LAN** | Whether the TV appears in the renderer list at all — the single most important interoperability question of the project | AC-M12.4, AC-M12.19, DT-09 |
| **A Windows PC** | Whether "Cast to Device" finds the television; whether Miracast can be tested at all | AC-M6.1 … AC-M6.6, DT-06, DT-10 |
| **A Samsung television + a Galaxy phone** | Whether the Samsung Smart View list shows the device — the protocol side is proven, the *decision of the television* is not | the Samsung section of `docs/guides/CASTING_TO_A_TV.md`; a new device test case |
| **A local HTTP server** (`python3 -m http.server 8080`) on the sender | Whether the renderer can actually pull a network-reachable media file — and, with it, whether the cleartext rule blocks the fetch | AC-M12.19, DT-09 |
| **A macOS sender** | The AirPlay handshake, the photo transfer, the pairing PIN, the now-playing screen | AC-3.x, AC-4.x, AC-5.x, DT-03 … DT-05 |
| **A Chrome device with a registered Cast application ID** | Whether the Cast path works end to end | AC-M7.4, DT-07 |

---

## 4. Release gate — the honest status

The gate is defined in [`../quality/QUALITY.md`](../quality/QUALITY.md) §7.

| # | Gate | Status | Blocking? |
|---|---|---|---|
| 1 | `./gradlew :test-runner:test` is green | **yes** — 457 tests, 0 failures | — |
| 2 | Lint and both debug APKs are green | **partly** — the APKs build (measured); the lint **fails** with 1 finding and the `firetv` variant was never analysed | **yes** (R2) |
| 3 | No known S4 finding | **no** — SEC-01, SEC-02, SEC-03 | **yes** (R6c–R6e) |
| 4 | Every open S3 has a written owner decision | **partly** — SEC-04 (S3, the DLNA media URI) and SEC-10 (S2, cleartext) are open; SEC-09 (S2) is accepted | **yes** (R6a, R6b) |
| 5 | The device scenarios ran on both flavours | **no** — 0 of 15 | **yes** (R4) |
| 6 | The file-size rule holds for the touched files | **partly** — no file above 550; three above 400 | **yes** (R3, R3b) |
| 7 | The documentation is up to date | **no** — the README contradicts the code in three places | **yes** (R7) |
| 8 | The limitations in `README.md` are true of this build | **no** — the README understates the DLNA status | **yes** (R7) |
| 9 | The quality gate is green | **no** — 16 missing KDoc headers | **yes** (R1) |
| 10 | The release is signed and verified | **no** — not produced in this cycle | **yes** (R8) |
| 11 | The release key is backed up | **unknown** — the owner has to confirm | **yes** (R9) |
| 12 | The version name equals the tag | **not yet** — `1.0.0-beta.3` in the build file | **yes** (R9b) |

**Result: 1 of 12 gates is green.** The release is a beta until R1–R9 are
closed.

The lint measurement changes the picture of R2: the lint is not "not run" any
more, it is **red with one finding**, and that finding is in the shared
manifest, so it hits both flavours. The `firetv` variant has never been linted
at all.

---

## 5. The two release paths

### Path A — `v1.0.0-beta.4` (recommended now)

Close **R1, R2, R3, R7** — no hardware, no owner decision, about a week. R2 is
now a concrete one-line manifest fix plus the first lint run of the `firetv`
variant.
Requirements: the quality gate is green, the lint is green, the README is
correct. Everything else stays as it is documented. This gives the project a
clean, publishable artefact with a truthful changelog.

### Path B — `v1.0.0` (stable)

Path A **plus** **R4, R5** (the device tests), **R6a–R6e** (the security
decisions), **R8, R9, R9b, R9c** (the release mechanics). This is the only path
on which the word "stable" is defensible: it means "tested on real hardware
with the open security findings closed".

### What the release notes must say in either case

* The AirPlay stack works on the JVM and has never been validated on a device in
  this cycle — until DT-03 … DT-05 are executed.
* The Miracast stack is discovery and control plane only; **no Miracast media
  is rendered**, by design (ADR-004).
* The Google Cast stack needs a registered application ID and degrades honestly
  without one.
* The DLNA renderer is implemented; its media path has an open address-policy
  finding and depends on the cleartext decision.
* No telemetry, no analytics, no crash reporter — the only component that talks
  beyond the LAN is the Google Cast SDK, and only on the Google TV flavour.
