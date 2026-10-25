# Release Readiness — v1.0.0 (stable)

**Target:** `v1.0.0` — a stable release, not a beta.
**Prepared by:** Project Manager
**Date:** 2026-10-24
**Measured on:** commit `2267672` (branch `feature/quality-m6-m12`) for the four
structure checks and both lint variants; commit `51933eb` for the JVM suite and
both debug APKs (neither was rebuilt in the second pass)
**Update (second pass, 2026-10-24):** the team leader closed the quality gate,
the lint and the three files above the line limit. **R1, R2 and R3b are done**
and are marked below with their measurement. Everything else stands as it was.

---

## 0. The one-paragraph version

The code is in good shape: the JVM suite is green (**549 tests, 0 failures, 0 errors,
0 skipped**, 63 test classes at the measured run), both debug flavours build, the four
protocol stacks are separated, the DLNA renderer exists, the security documentation is
complete, the three High findings of the security audit are **closed and measured**, and
— since the second pass — **the CI
quality gate is green and the lint is green for both flavours** (the `firetv`
flavour was analysed for the first time: `No issues found.`). What is missing
is **evidence** and **three decisions**. No device test of any kind has been
executed — 0 of the 15 cases of the device test plan. Three High security
findings are still open in the code, one of them (the unbounded FU-A reassembly
buffer) directly in the AirPlay media path. And the DLNA media path has two open
questions that only the owner can answer: the media URI is now address-restricted
but not scheme-restricted (SEC-04, an S3 that needs a written owner decision;
the address policy of the second pass closed the dangerous addresses, the wide
scheme list, the accepted public addresses and the un-resolved host names stay
open), and the app declares no network security configuration, so the cleartext
`http://` media fetch is probably blocked on API 35.

**Recommendation:** cut `v1.0.0-beta.4` once item R7 (the README) is closed, and
cut `v1.0.0` only after the device test plan has been executed and the three
security findings are closed or carry a written owner decision.

---

## 1. What is already proven (measured)

| # | Fact | How it was measured |
|---|---|---|
| 1 | The JVM protocol suite is green: **549 tests, 0 failures, 0 errors, 0 skipped**, 63 test classes | `./gradlew :test-runner:test` → `BUILD SUCCESSFUL in 3m 18s`, exit 0 (measured on the integrated tree). Before this cycle the suite had 277 tests. |
| 2 | Both debug flavours build | `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline` → `BUILD SUCCESSFUL` (commit `51933eb`) |
| 3 | The file-size check is green **and warning-free** | `bash scripts/check-file-size.sh` → exit 0, `OK: file size check passed — 184 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.` |
| 4 | The documentation-link check is green | `bash scripts/check-docs-links.sh` → exit 0, `192 internal link(s) resolve` |
| 5 | The language-completeness check is green | `bash scripts/check-i18n-completeness.sh` → exit 0, `80 key(s) … complete in 2 language(s)` |
| 6 | **The KDoc check is green** | `bash scripts/check-kdoc-headers.sh` → exit 0, `OK: KDoc header check passed — 227 class declaration(s) checked, all of them have a KDoc header.` — the 16 missing headers of the previous round are closed |
| 7 | **The lint is green for both flavours** | `:app:lintGoogletvDebug` → `BUILD SUCCESSFUL` (5m 12s), `:app:lintFiretvDebug` → `BUILD SUCCESSFUL` (3m 39s); both reports say `No issues found.` |
| 8 | The DLNA stack is substantial and covered | 29 production files in `com.phairplay.dlna`, 157 test methods in the measured run, 169 today (the address policy) |
| 9 | The AirPlay stack is substantial and covered | 202 green tests in `com.phairplay.airplay` and `.handshake` |
| 10 | The Samsung Smart View extension is covered | 29 green tests (`SamsungSsdpResponderTest`, `DialAppCatalogTest`, `DialEndpointTest`, `DlnaDeviceDescriptionFieldsTest`) |

---

## 2. The checklist

The order matters: **R1 → R2 → R3** can be done in a week without hardware and
they unblock the pipeline — **R1 and R2 are done and measured since the second
pass**, only the wording of R3 is left. **R4 → R5** need hardware. **R6** needs a
decision from the owner and nothing else. **R7 → R9** are the release mechanics.

### Block 1 — the pipeline (no hardware needed)

> **Status of this block: DONE.** R1, R2 and R3b were closed by the team leader
> and are marked with their measurement. R3 (the wording of the line rule) is
> still an owner decision, but the tree satisfies both limits today.

| # | Item | Gate | Evidence of done | Owner | Status |
|---|---|---|---|---|---|
| **R1** | Add the 16 missing KDoc headers, **or** decide that test files are exempt (`--skip-tests` in the CI call) | `scripts/check-kdoc-headers.sh` → exit 0 | `OK: KDoc header check passed — 227 class declaration(s) checked, all of them have a KDoc header.` | Architect + file owners | **✅ done** — the 16 headers were added; the gate exits 0 |
| **R2** | Make the lint green for both flavours | `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug` → exit 0 | 0 findings (`warningsAsErrors = true` in `app/build.gradle.kts`). **Measured:** `:app:lintGoogletvDebug` → `BUILD SUCCESSFUL` (5m 12s), `:app:lintFiretvDebug` → `BUILD SUCCESSFUL` (3m 39s); both reports say `No issues found.` The finding was `app/src/main/AndroidManifest.xml:58: Error: Attribute usesPermissionFlags is only used in API level 31 and higher (current min is 29) [UnusedAttribute]`; it is fixed with `tools:targetApi="31"` on the permission element (`app/src/main/AndroidManifest.xml` lines 64–65). The `firetv` variant was analysed **for the first time** in this round. | Platform | **✅ done** |
| **R3** | Decide the 400 vs. 550 line rule and make the texts agree | either `--strict` in the CI call, or the rule texts rewritten to soft/hard | `docs/CONTRIBUTING.md` and NFR-23 match the script | Owner (rule decision) | **open** — a rule decision, not a technical problem; the tree satisfies both limits today (risk R-08) |
| **R3b** | Split the three files above 400 lines, or accept them with a written reason | `scripts/check-file-size.sh` → 0 warnings | **Measured:** `OK: file size check passed — 184 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.` The three files were split: `MainActivity.kt` 447 → 348 lines, `miracast/MiracastReceiver.kt` 439 → 260 lines (`WfdRtspServer.kt` 205), `airplay/RtspHandlerTest.kt` 413 → `RtspHandlerHandshakeTest.kt` (239) and `RtspHandlerSessionTest.kt` (206) with `TestableRtspHandler.kt` (56). | Architect | **✅ done** |

### Block 2 — the security decisions (owner decisions, no hardware needed)

| # | Item | Gate | Evidence of done | Owner |
|---|---|---|---|---|
| **R6a** | Reduce the DLNA media URI allowlist to `http`/`https` and reverse the test `SetAVTransportURI accepts a file URI and an rtsp URI` | AC-M12.15 | a new negative test: `file:///etc/passwd` → SOAP fault `402`; `DlnaMediaUri.ALLOWED_SCHEMES` no longer contains `file`. **Partly done in the second pass:** the *address* of the media URI is now checked (`HostAddressPolicy`: loopback, link-local / metadata, multicast, broadcast, obfuscated notations, user info — 11 test methods). What is still missing is the scheme reduction and the owner decision on the residual points (the scheme list itself, the accepted public addresses, the un-resolved host names) | DLNA track · Owner (decision) |
| **R6b** | Decide on the network security configuration (CR-02) | either a `network_security_config.xml` that permits cleartext only for private LAN ranges, **or** a documented statement that the DLNA media path requires `https` | the decision is written down; `app/src/main/res/xml/network_security_config.xml` exists **or** the limitation is in `README.md` and in the guides | Owner |
| **R6c** | ~~Close SEC-03 (the unbounded FU-A reassembly buffer)~~ | a bound in `RtpInterleaved.handleFuaFragment` plus a negative test | **closed and measured:** `RtpInterleaved.MAX_FUA_BYTES` (8 MB), the check runs before the append, and the tests `a FU-A stream that never ends does not grow without bound`, `the incomplete FU-A unit is dropped when the limit is exceeded` and `a new FU-A start fragment works again after the limit was exceeded` are green | AirPlay track |
| **R6d** | ~~Close SEC-01 (the arbitrary-URL fetch through `POST /play`)~~ | a scheme check in `RtspVideoUrlHandler.handleVideoPlay` plus a negative test | **closed and measured:** `FetchableUrlPolicy` accepts only `http` and `https`, the address has to pass `HostAddressPolicy`, and `file://` and `content://` are refused with `400` without the player being asked to fetch anything. Tests: `FetchableUrlPolicyTest` and `RtspVideoUrlHandlerTest` | AirPlay track |
| **R6e** | ~~Close SEC-02 (the image decompression bomb in `PhotoScreen` / `NowPlayingScreen`)~~ | a `SafeBitmapDecoder` with a maximum edge, plus a negative test | **closed and measured:** `SafeBitmapDecoder` reads the dimensions first, refuses what does not fit into the pixel budget and downsamples the rest; `PhotoScreen` and `NowPlayingScreen` use it. Tests: `SafeBitmapDecoderTest` (16 cases) | UI track |

> The quality model (`docs/quality/QUALITY.md` §7) says: **no release may ship
> with a known S4 finding.** SEC-01, SEC-02 and SEC-03 were all S4 — all three are
> **closed and measured** now. What still blocks the stable claim is the hardware
> block below and the two written owner decisions R6a and R6b.

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
| 1 | `./gradlew :test-runner:test` is green | **yes** — **549 tests, 0 failures, 0 errors, 0 skipped** in 63 test classes (measured on the integrated tree, `BUILD SUCCESSFUL in 3m 18s`) | — |
| 2 | Lint and both debug APKs are green | **yes** — the lint is green for both flavours (`:app:lintGoogletvDebug` and `:app:lintFiretvDebug`, both reports `No issues found.`); both APKs build (measured) | — (R2 done) |
| 3 | No known S4 finding | **yes** — SEC-01, SEC-02 and SEC-03 are closed and measured | — (R6c–R6e done) |
| 4 | Every open S3 has a written owner decision | **partly** — SEC-04 (S3, the DLNA media URI) is *partly closed* by the address policy and still needs the scheme decision; SEC-10 (S2, cleartext) is open; SEC-09 (S2) is accepted | **yes** (R6a, R6b) |
| 5 | The device scenarios ran on both flavours | **no** — 0 of 15 | **yes** (R4) |
| 6 | The file-size rule holds for the touched files | **yes** — no file above 550, **and none above 400**; `0 warning(s) above the soft limit of 400 lines` | — (R3b done; R3 is a wording decision) |
| 7 | The documentation is up to date | **no** — the README contradicts the code in three places | **yes** (R7) |
| 8 | The limitations in `README.md` are true of this build | **no** — the README understates the DLNA status | **yes** (R7) |
| 9 | The quality gate is green | **yes** — all four structure checks exit 0; the KDoc check reports `227 class declaration(s) checked, all of them have a KDoc header` | — (R1 done) |
| 10 | The release is signed and verified | **no** — not produced in this cycle | **yes** (R8) |
| 11 | The release key is backed up | **unknown** — the owner has to confirm | **yes** (R9) |
| 12 | The version name equals the tag | **not yet** — `1.0.0-beta.3` in the build file | **yes** (R9b) |

**Result: 4 of 12 gates are green** (1, 2, 6, 9). The release is a beta until
R4, R6a–R6e and R7–R9 are closed.

The lint measurement of the second pass changes the picture of R2 completely:
the lint is not "not run" and not "red" any more, it is **green for both
flavours**, and the `firetv` variant — never analysed until now — reports no
findings.

---

## 5. The two release paths

### Path A — `v1.0.0-beta.4` (recommended now)

Close **R7** (the README) — no hardware, no owner decision, about an hour.
R1, R2 and R3b are already closed and measured, so the pipeline is green.
Requirements: the quality gate is green (it is), the lint is green (it is), the
README is correct (it is not). Everything else stays as it is documented. This
gives the project a clean, publishable artefact with a truthful changelog.

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
  finding (the address is now checked, the scheme list and the two residual
  points are not) and depends on the cleartext decision.
* No telemetry, no analytics, no crash reporter — the only component that talks
  beyond the LAN is the Google Cast SDK, and only on the Google TV flavour.
