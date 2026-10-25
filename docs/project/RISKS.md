# PhairPlay — Risk Register

Version: 1.2
Status: Active
Owner: Project Manager
Date: 2026-10-24
Last measured: 2026-10-24 — the four structure checks and both lint variants on
the current tree (commit `2267672`, branch `feature/quality-m6-m12`); the JVM
suite, both debug APKs and the `googletv` lint of the previous round on commit
`51933eb`

---

## 0. How to read this register

* **Likelihood** — `certain` / `high` / `medium` / `low`. A likelihood is a
  statement about the *state of the code today*, not about the future.
* **Impact** — `high` / `medium` / `low`, judged against the release rules of
  [`../quality/QUALITY.md`](../quality/QUALITY.md) §7: an S4 finding (critical)
  blocks a release, an S3 finding (major) needs a written owner decision.
* **Countermeasure** — what is already in the code, or the concrete action
  that closes the gap. Every countermeasure names the file or the command that
  proves it.
* **Status** — `open`, `open (accepted)`, `mitigated`, `closed`. `open`
  always carries a reason in the countermeasure column.

The register is written in two layers: every risk has a detailed section below
with the full evidence, and §4 condenses all of them into one table with the
columns **risk | likelihood | impact | countermeasure | owner | status**. The
table is the authoritative one-line view; the sections carry the proof.

The register supersedes the risk table that used to live inside
[`../spec/PROJECT_PLAN.md`](../spec/PROJECT_PLAN.md). The project plan now
links here instead of carrying a second, drifting copy.

> **Revision 1.2 (2026-10-24, second pass).** Two risks are **closed** with a
> measurement: **R-07** (the quality gate was red) and **R-16** (the lint was
> red). **R-03** (the SSRF via the media URI) is **partly closed** by the new
> address policy; the residual points stay open and are named. The numbers of
> the file-size check (184 files, 0 warnings) and of the KDoc check (227
> declarations) are the current ones.

---

## 1. Protocol risks

### R-01 — Miracast without a public Wi-Fi Display API

| Field | Value |
|---|---|
| **Risk** | A sideloaded third-party app cannot act as a Wi-Fi Display sink on stock Android TV: `WifiP2pManager.setWfdInfo()` is a hidden system API gated by `CONFIGURE_WIFI_DISPLAY`. Windows 10/11 senders generally do not discover a third-party Miracast receiver. |
| **Likelihood** | **certain** — the limitation is architectural, not a bug. |
| **Impact** | **medium** — M6 stays a control-plane milestone; the product promise for Windows senders is unmet. |
| **Countermeasure** | The app ships only what is legal: the `_wfd._tcp` service advertisement with the sink TXT records and the WFD RTSP control plane (`app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt`, TXT map at lines 213–217, `SERVICE_TYPE_WFD` at line 264). There is deliberately **no** MPEG-TS demuxer and **no** WFD decoder (`grep -rn "MiracastDecoder\|MpegTs\|TsDemux" app/src/main` → 0 matches). The scope is stated as an exclusion in `docs/spec/REQUIREMENTS.md` §3 and in ADR-004. |
| **Owner** | Product (scope decision taken) · Platform (implementation) |
| **Status** | **open (accepted)** — accepted because the alternative needs system privileges PhairPlay will never have. The consequence is documented, not hidden. |

### R-02 — Google Cast without a registered application ID

| Field | Value |
|---|---|
| **Risk** | The Cast Connect receiver cannot start without an application ID that only Google can assign. Without it, the receiver reports `ERROR` and the Cast feature is untestable end to end. |
| **Likelihood** | **certain** — the ID is assigned by the Google Cast Developer Console, it cannot be generated locally. |
| **Impact** | **medium** — M7 is a graceful-degradation milestone, not a working Cast receiver. |
| **Countermeasure** | The build property is empty by default (`app/build.gradle.kts` lines 17–19 read `-Pphairplay.castAppId` or `PHAIRPLAY_CAST_APP_ID`, line 35 writes it into `BuildConfig`). Without an ID, `CastReceiver.start()` logs `Google Cast is not configured: missing Cast application ID` and reports `ERROR` instead of pretending to advertise (`app/src/googletv/kotlin/com/phairplay/cast/CastReceiver.kt`). The Fire TV flavour never touches the Cast SDK at all (`app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt` reports `DISABLED`). The acquisition procedure is written down in `docs/guides/CAST_APP_ID.md`. |
| **Owner** | Owner (must register the app) · Platform (graceful degradation implemented) |
| **Status** | **open** — reason: the registration needs a Google account and a console entry that only the owner can create. Until it exists, AC-M7.4 (the error state) is the only behaviour that can be proven. |

### R-03 — DLNA security: SSRF via the media URI

| Field | Value |
|---|---|
| **Risk** | A control point can name any URL as `CurrentURI`. The television then opens that connection (`DlnaReceiver.fetchBounded()` → `URL(uri).openConnection()`, `app/src/main/kotlin/com/phairplay/dlna/DlnaReceiver.kt` line 198). A malicious LAN device can therefore use the TV as a request proxy into the home network. |
| **Likelihood** | **medium** — was `high` before the address policy. The dangerous part of the path is now guarded; the remaining exposure needs a public address or a name that resolves to a link-local address. |
| **Impact** | **high** — the audit grades SEC-04 as *Medium*, and the fixed mapping in `docs/quality/QUALITY.md` §0 turns Medium into **S3** (major): it needs a **written owner decision** in the release notes. The S4 *definition* names exactly this defect class ("an arbitrary-URL fetch that turns the television into a request proxy") — the same class was SEC-01 on the AirPlay path, and that one is now **closed**. **Owner decision required**: either grade SEC-04 as an S4 as well (no release with it) or accept it as an S3 with a written reason. |
| **Countermeasure** | **Partly in place.** The GENA callback URL *is* guarded: `GenaCallbackPolicy` restricts it to a private IPv4 in the local subnet and rejects loopback, link-local (`169.254.169.254`), public addresses, host names and the decimal / octal / hexadecimal notations — 16 cases in `GenaCallbackPolicyTest`. The *media URI* is now guarded as well: the new class `app/src/main/kotlin/com/phairplay/util/HostAddressPolicy.kt` (170 lines) is called from `DlnaMediaUri.validate` and refuses the loopback (`127.0.0.0/8`, `::1`, `::ffff:127.0.0.1`), the reserved range `0.0.0.0/8`, the link-local range (and with it `169.254.169.254`), the multicast range, the broadcast address, the reserved range `240.0.0.0/4`, the further metadata addresses (`100.100.100.200`, `192.0.0.192`, `fd00:ec2::254`), the known metadata host names, the non-standard notations of an IPv4 address (decimal, octal, hexadecimal) and every URI with a user info part. Tests: `HostAddressPolicyTest` (11 test methods) plus `DlnaMediaUriTest > rejects a URI that points at the cloud metadata service`. **Still not guarded:** the *scheme* list of the media URI (`DlnaMediaUri.ALLOWED_SCHEMES` still contains `file`, `smb`, `ftp`, `rtsp`, `rtmp`, `rtmpe`, `rtmps`, `mms`, `mmsh`, `mmst`, `udp`, `pnm` — `app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt` lines 30–33), a **public** address (deliberate: the control point decides which medium the owner wants to see) and a **host name**, which is **not resolved** (a name that points at a link-local address stays a residual risk). |
| **Owner** | DLNA track (CR-01) · Owner (the scheme decision) |
| **Status** | **open — partly closed** — reason: the address policy closed the dangerous part (the metadata, the loopback and the obfuscated addresses) and it is measured. What stays open is a **decision**: reduce the allowlist to `http`/`https` (and reverse the existing test `SetAVTransportURI accepts a file URI`), or accept the wide scheme list for the compatibility with real control points and write the reason into the release notes. **Owner decision required**, because the choice changes the interoperability promise (see O-2 in the handover). The two residual points (public address, un-resolved host name) are documented acceptance criteria, not defects to be fixed before the release. |

### R-04 — DLNA security: `file://` turns the TV into a file reader

| Field | Value |
|---|---|
| **Risk** | `file:///data/data/com.phairplay.googletv/…` is an accepted scheme today (`DlnaMediaUri.ALLOWED_SCHEMES`), and `MediaPlayer.setDataSource(url)` accepts `file://` paths. A control point could ask the TV to read a file inside the app sandbox. |
| **Likelihood** | **medium** — the scheme is accepted; the sandbox limits what is reachable. The address policy does not change this, because a `file://` URI carries no host to check. |
| **Impact** | **medium** — information disclosure inside the app sandbox; combined with R-03 it is the same attack surface. |
| **Countermeasure** | The app sandbox is the boundary (`android:allowBackup="false"`, no external storage permission — see the permission audit in `docs/audit/2026-10-24-security-audit.md` §4). The scheme reduction from R-03 also closes this; the address policy of the second pass does not. |
| **Owner** | DLNA track (CR-01) |
| **Status** | **open** — reason: same change request as R-03; the two are fixed by one edit (the scheme list). |


### R-05 — Cleartext HTTP is blocked by the platform default

| Field | Value |
|---|---|
| **Risk** | The app targets API 35 and declares **no** `network_security_config.xml` (`app/src/main/res/xml/` does not exist; the manifest has no `networkSecurityConfig` attribute). Android therefore blocks cleartext HTTP by default. The GENA `NOTIFY` (raw socket), the DACP reverse channel and the DLNA media fetch (`MediaPlayer.setDataSource` on an `http://` URL) are affected. Discovery and SOAP control work; the outgoing directions may not. |
| **Likelihood** | **medium** — the platform behaviour is documented, but the affected paths were never executed on a device, so the exact set of blocked paths is not measured. |
| **Impact** | **high** — if confirmed, "the TV fetches the media" (the core DLNA promise, AC-M12.19) does not work on a real device. |
| **Countermeasure** | A network security configuration that permits cleartext **only** for private LAN ranges is the planned fix (NFR-28, CR-02 in the product handover). Until it exists, the affected features must be documented as unavailable, and the device test plan cases DT-09 / DT-10 / DT-11 must record what actually happens. |
| **Owner** | Platform (CR-02) |
| **Status** | **open** — reason: the configuration does not exist yet and the affected paths cannot be tested without a device. This is the **single most important unknown** for the DLNA milestone: it decides whether AC-M12.19 is achievable on API 35 at all. |

### R-06 — GENA callback: check and connect are not the same address

| Field | Value |
|---|---|
| **Risk** | The callback guard validates the resolved address, the notification then connects to the host name in the URL (SEC-09 in the security audit, TOCTOU). A DNS answer that changes between the check and the connection bypasses the policy. |
| **Likelihood** | **low** — it needs a cooperating or compromised DNS server inside the LAN. |
| **Impact** | **medium** — the SSRF guarantee of the GENA callback becomes bypassable in a narrow window. |
| **Countermeasure** | The policy `GenaCallbackPolicy` refuses host names outright (`GenaCallbackPolicyTest` → `rejects a DNS name`), which closes the window for the common case. The remaining hardening — connect to the *validated* address instead of re-resolving — is not implemented. |
| **Owner** | DLNA track |
| **Status** | **open (accepted for v1.0.0)** — reason: the residual window is narrow, the audit lists it as a residual risk, and the mitigation (connect to the pinned address) is a change in `GenaEventManager`. Decision: ship with the documented residual risk, fix after v1.0.0. |

---

## 2. Process and quality risks

### R-07 — Maintainability: the file-size rule and the KDoc rule are green (closed)

| Field | Value |
|---|---|
| **Risk** | The quality gate is part of CI (`.github/workflows/ci.yml`, job `quality-gate`, lines 13–51). One of its four checks was red on commit `51933eb`: the KDoc check reported 16 class declarations without a header, and three files were above the soft limit of 400 lines. |
| **Likelihood** | **was `certain`** — the gate was red on every push. **Closed by measurement** (see below). |
| **Impact** | **medium** — every push to `main` was red; the gate could not be used as a release signal. |
| **Countermeasure** | **Closed.** All four checks are green on the current tree (commit `2267672`): `bash scripts/check-file-size.sh` → exit 0, `OK: file size check passed — 184 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.`; `bash scripts/check-kdoc-headers.sh` → exit 0, `OK: KDoc header check passed — 227 class declaration(s) checked, all of them have a KDoc header.`; `bash scripts/check-docs-links.sh` → exit 0, `OK: documentation link check passed — 192 internal link(s) resolve, 15 external link(s) skipped.`; `bash scripts/check-i18n-completeness.sh` → exit 0, `OK: i18n completeness check passed — 80 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).` The 16 missing headers were added (8 of them in test files), and the three files above 400 lines were split: `MainActivity.kt` 447 → 348 lines, `miracast/MiracastReceiver.kt` 439 → 260 lines (the WFD RTSP server moved to `miracast/WfdRtspServer.kt`, 205 lines), `airplay/RtspHandlerTest.kt` 413 → `RtspHandlerHandshakeTest.kt` (239) and `RtspHandlerSessionTest.kt` (206) with the shared helper `TestableRtspHandler.kt` (56). |
| **Owner** | Architect / each file owner |
| **Status** | **closed** — reason: the four checks exit 0 and the gate is green; the measurement is in the countermeasure column. **Remains as a rule:** the gate only stays green if every new file keeps its KDoc header and stays below 400 lines — the negative-test protocol in `docs/audit/qa-quality-gate-negative-test.md` proves that a violation still turns the gate red. |

### R-08 — The 400 vs. 550 line rule is documented ambiguously

| Field | Value |
|---|---|
| **Risk** | `docs/CONTRIBUTING.md` and NFR-23 say "no source file over 400 lines", the script warns at 400 and fails at 550, and the CI call does not set `--strict`. A 500-line file therefore violates the written rule and still passes the gate. |
| **Likelihood** | **certain** — read from the script and the workflow. |
| **Impact** | **low** — a documentation inconsistency, not a defect. |
| **Countermeasure** | Either set `--strict` in the CI call or rewrite the rule texts to the soft/hard wording. The script already documents both limits and supports `--soft-limit` / `--hard-limit`. **Note:** the tree currently satisfies **both** limits — `184 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines` — so the ambiguity has no practical effect today. It becomes active again with the next file that grows past 400 lines. |
| **Owner** | Owner (rule decision) · Docs (text) |
| **Status** | **open** — reason: a rule decision, not a technical problem. The gap is now latent rather than active. |

### R-09 — Language coverage: the gate is a shell script outside the Gradle run

| Field | Value |
|---|---|
| **Risk** | The completeness of the translations rests on `scripts/check-i18n-completeness.sh`, which is not part of `./gradlew :test-runner:test`. Android's own `MissingTranslation` lint is switched off in `app/build.gradle.kts`. Two of the two available mechanisms are therefore outside the JVM gate. |
| **Likelihood** | **medium** — a future change can break a translation without any gate noticing. |
| **Impact** | **medium** — a missing key falls back to English at runtime; in a German or French UI that is a visible defect. |
| **Countermeasure** | The script itself is green and does more than the lint check: `./scripts/check-i18n-completeness.sh` → exit 0, `OK: i18n completeness check passed — 80 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s)`. It runs in the CI job `quality-gate`. It compares missing keys, stale keys and the format placeholders. Two known gaps: the placeholder comparison is a **sequence** comparison, not a set comparison, and `<plurals>` / `<string-array>` are not compared at all (the base file currently contains only `<string>`). |
| **Owner** | QA |
| **Status** | **mitigated, with a known gap** — reason: the current state is proven and green; the gap is documented in the QA acceptance record. A JVM test that compares the three resource folders would close it and is an open improvement, not a blocker. |

### R-10 — The release is signed with a key that lives outside the repository

| Field | Value |
|---|---|
| **Risk** | `scripts/release.sh` reads the keystore from `~/.config/phairplay/release.jks` and the credentials from `~/.config/phairplay/release.env`. Neither is in the repository. Without that file, no signed release can be cut, and the same key must be reused for every future version — losing it means a new key, which breaks the upgrade path for installed apps. |
| **Likelihood** | **low** for the key, **medium** for the process — the key exists (v1.0.0-beta.2 was signed and published), but the process around it is undocumented. |
| **Impact** | **high** — a lost key means users cannot upgrade in place. |
| **Countermeasure** | `scripts/release.sh --setup` creates the keystore and stores the credentials outside the repository; the script refuses to run without them. The key must be backed up by the owner in a place the team does not control. |
| **Owner** | Owner |
| **Status** | **open** — reason: the backup of the release key is an owner action that has not been confirmed. The alpha release `v1.0.0-alpha.1` (2026-10-25) was signed with a **new** key that was handed to the owner together with its password; this key must be kept, because every later version has to be signed with it. It is still not the production key of the Play Store build, so the owner has to uninstall the store version before installing the alpha. |

### R-11 — Release signing and the version number drift

| Field | Value |
|---|---|
| **Risk** | `app/build.gradle.kts` carries the version of the next release, while `CHANGELOG.md` also lists an `[Unreleased]` section with content that has not been versioned yet. A release cut from the wrong state publishes a binary whose version does not match the changelog. |
| **Likelihood** | **medium** |
| **Impact** | **medium** — a mislabelled release; AC-M11.7 requires version name and tag to be equal. |
| **Countermeasure** | `scripts/release.sh` takes the version as its argument and the changelog uses the Keep a Changelog format, so the two can be checked against each other before the tag is pushed. |
| **Owner** | Release owner |
| **Status** | **open** — reason: the version bump to `1.0.0` has not happened yet. The alpha train keeps the version in step: `v1.0.0-alpha.1` shipped with `versionCode = 4` / `versionName = "1.0.0-alpha.1"` and a matching changelog entry, and the [release schedule](../spec/PROJECT_PLAN.md#release-schedule) binds every release to its own version bump. |

---

## 3. Verification risks

### R-12 — Device test coverage is zero

| Field | Value |
|---|---|
| **Risk** | The entire acceptance of the product rests on scenarios that a JVM cannot prove: the mDNS picker entry, the picture on the screen, the SSDP answer in a real LAN, the VLC renderer list, the German and French texts, the boot receiver. None of the 15 cases of the device test plan has been executed. |
| **Likelihood** | **certain** — 0 of 15 cases executed. |
| **Impact** | **high** — without device tests there is no evidence that the receiver works on a television at all. |
| **Countermeasure** | The plan exists and is copy-pasteable: `docs/guides/DEVICE_TEST_PLAN.md` with the cases DT-01 … DT-15, hardware list H1–H6, software list S1–S5, time limits per case and a result sheet. The acceptance record `docs/acceptance/QA-DEVICE-TEST-ACCEPTANCE.md` states honestly that the execution is open. |
| **Owner** | QA + owner (hardware access) |
| **Status** | **open** — reason: no hardware was available in this cycle. This is the largest single gap in the release readiness and the reason v1.0.0 cannot be called *stable* yet. |

### R-13 — Six test classes never run in CI

| Field | Value |
|---|---|
| **Risk** | The CI gate excludes six test files from the JVM runner (`test-runner/build.gradle.kts`, `exclude` block): `MainActivityTest.kt` (9 tests), `MdnsServiceTest.kt` (12), `VideoSubmissionTest.kt` (5), `ServiceControllerTest.kt` (4), `NetworkUtilsTest.kt` (5), `ui/VideoAspectFitTest.kt` (3) — 38 of the 507 `@Test` methods in the repository. |
| **Likelihood** | **certain** — the exclusion list is in the build file. |
| **Impact** | **medium** — a green CI run does not mean these classes pass; they are unproven, not proven-failing. |
| **Countermeasure** | The exclusions are documented with their reason (Android framework classes that call JNI). The JVM run on the integrated tree reports **511 tests, 0 failures, 0 errors, 0 skipped across 59 classes** — the excluded classes are *additional* to that number, not part of it. The exclusion list is unchanged, so the excluded methods are unchanged as well. |
| **Owner** | QA / Platform |
| **Status** | **open** — reason: the classes need either a device run or JVM-safe stubs; both are engineering work, not a release blocker for the protocols, but the MediaCodec path (`VideoSubmissionTest`) is relevant to the video claim. |

### R-14 — The Miracast and Samsung visibility claims are protocol-level only

| Field | Value |
|---|---|
| **Risk** | The Samsung Smart View work proves that PhairPlay *answers* the Samsung SSDP search and serves the DIAL documents. It does **not** prove that a Samsung television lists the device — that decision is made by the TV model and the app version on the phone. The same is true for the Windows "Cast to Device" picker and for VLC. |
| **Likelihood** | **certain** — the limitation is inherent. |
| **Impact** | **medium** — the headline feature claim may not hold on real devices. |
| **Countermeasure** | The protocol side is covered by 29 tests (`SamsungSsdpResponderTest` 6, `DialAppCatalogTest` 10, `DialEndpointTest` 6, `DlnaDeviceDescriptionFieldsTest` 7), all green in the measured run. The device side is spelled out as a hard requirement in `docs/guides/CASTING_TO_A_TV.md` under "Known limitations" and is case DT-09 of the device test plan. |
| **Owner** | QA (device) |
| **Status** | **open** — reason: it needs a Samsung television, a Galaxy phone and a Windows/VLC sender. None is available in this cycle. |

### R-15 — Samsung Tizen is not a target

| Field | Value |
|---|---|
| **Risk** | The task framing mentions Samsung Tizen as a possible target. The repository knows two flavours only — `googletv` and `firetv` (`app/build.gradle.kts`, product flavours) — and no Tizen case in the device test plan. A Tizen television cannot run an APK that is not built for it. |
| **Likelihood** | **certain** — no Tizen flavour exists. |
| **Impact** | **medium** — if Tizen is a goal, it is a third flavour plus its own device test set, not a configuration change. |
| **Countermeasure** | None — this is a product decision, not a defect. The Samsung Smart View support that exists serves **Samsung Galaxy phones and tablets** as the sender and a Samsung television as the receiver only if that television speaks DLNA. |
| **Owner** | Owner |
| **Status** | **open (accepted for v1.0.0)** — reason: a third flavour is out of scope for a stable v1.0.0. Decision required from the owner (see O-4). |

### R-16 — The lint gate is green for both flavours (closed)

| Field | Value |
|---|---|
| **Risk** | The lint did not pass. `app/src/main/AndroidManifest.xml:58` carried `android:usesPermissionFlags="neverForLocation"`, an attribute that exists only from API 31, while the `googletv` flavour has `minSdk = 29`. The lint reported it as an error (`UnusedAttribute`). Because the `googletv` variant failed first, the `firetv` variant was never analysed at all — its findings were unknown, not absent. |
| **Likelihood** | **was `certain`** — measured on `51933eb`: `:app:lintGoogletvDebug FAILED`, `1 errors, 0 warnings`; the report was `app/build/reports/lint-results-googletvDebug.txt`. **Closed by measurement** (see below). |
| **Impact** | **medium** — the pipeline that is supposed to protect the release was red, and half of the lint surface (the Fire TV flavour) had never been analysed. |
| **Countermeasure** | **Closed.** The attribute is correct and stays — it is the promise to the platform that PhairPlay never derives a location from a Wi-Fi result — and the permission element now carries `tools:targetApi="31"` (`app/src/main/AndroidManifest.xml` lines 64–65), so lint knows that the attribute is meant for API 31 and above and is ignored by older systems. Measured: `:app:lintGoogletvDebug` → `BUILD SUCCESSFUL` (5m 12s), `:app:lintFiretvDebug` → `BUILD SUCCESSFUL` (3m 39s); both reports say `No issues found.` (`app/build/reports/lint-results-googletvDebug.txt`, `app/build/reports/lint-results-firetvDebug.txt`). The configuration stays strict (`abortOnError = true`, `warningsAsErrors = true` in `app/build.gradle.kts`), so the next finding turns the gate red again. |
| **Owner** | Platform |
| **Status** | **closed** — reason: both variants are analysed and green, with 0 findings; the measurement is in the countermeasure column. **The Fire TV flavour has been analysed for the first time in this round** — its previous "unknown findings" are now "no findings". |

### R-17 — Maintainability: five classes were split; the tree is now below the line limit

| Field | Value |
|---|---|
| **Risk** | The five largest source files were split in this cycle: `RtspHandler.kt` 1024 → 267 lines (twelve new files), `PhairPlayService.kt` 650 → 289, `AirPlayReceiver.kt` 658 → 374, `MdnsService.kt` 527 → 338, `AudioStreamServer.kt` 503 → 259. The public API is unchanged, but the behaviour of the split classes has never been observed on a device. The three files that were still above the soft limit of 400 lines were split in the second pass: `MainActivity.kt` 447 → 348, `miracast/MiracastReceiver.kt` 439 → 260, `airplay/RtspHandlerTest.kt` 413 → `RtspHandlerHandshakeTest.kt` (239) and `RtspHandlerSessionTest.kt` (206) with the helper `TestableRtspHandler.kt` (56). |
| **Likelihood** | **medium** — the splits are mechanical and the JVM suite is green, but no device run exists to confirm the behaviour of the split classes. |
| **Impact** | **medium** — a regression that only shows up in the moved code would not be caught by the JVM suite alone. |
| **Countermeasure** | The moved symbols keep their signature and their visibility, and the suite is green after the split (measured: **511 tests, 0 failures, 0 errors, 0 skipped** in 59 classes). The file-size script passes **without a warning**: `bash scripts/check-file-size.sh` → exit 0, `OK: file size check passed — 189 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.` Item R3b of the release checklist is therefore **done**. |
| **Owner** | Architect / each file owner |
| **Status** | **open** — reason: the device test plan (DT-01 … DT-15) is the missing proof; it has never been executed. The file-size part of the risk is closed; the behavioural part of the split is not. |

---

## 4. Register summary

| # | Risk | Likelihood | Impact | Countermeasure | Owner | Status |
|---|---|---|---|---|---|---|
| R-01 | Miracast without a public Wi-Fi Display API | certain | medium | Ship the `_wfd._tcp` advertisement and the WFD RTSP control plane only; the media path is a documented scope exclusion (ADR-004) | Product / Platform | open (accepted) |
| R-02 | Google Cast without a registered application ID | certain | medium | The receiver reports `ERROR` instead of advertising; the acquisition procedure is in `docs/guides/CAST_APP_ID.md` | Owner | open |
| R-03 | SSRF via the DLNA media URI | medium | high (S3 → written owner decision) | The address of the media URI is now checked (`HostAddressPolicy`: loopback, link-local / metadata, multicast, broadcast, obfuscated notations, user info — 11 test methods); **open:** the scheme list, the public addresses and the un-resolved host names need either CR-01 or a written owner decision in the release notes | DLNA / Owner | open — partly closed |
| R-04 | `file://` turns the TV into a file reader | medium | medium | Same change as R-03 (the scheme list); the app sandbox limits the reachable files in the meantime | DLNA | open |
| R-05 | Cleartext HTTP blocked by the platform default | medium | high | Decide on a network security configuration for private LAN ranges (CR-02) or document the DLNA media path as `https`-only | Platform | open |
| R-06 | GENA callback: check and connect differ | low | medium | Host names are refused outright; the residual window is accepted and documented | DLNA | open (accepted for v1.0.0) |
| R-07 | Quality gate red: 16 missing KDoc headers | was certain | medium | **Closed:** all four structure checks exit 0 — the file-size check reports `184 file(s) checked … 0 warning(s) above the soft limit of 400 lines`, the KDoc check `227 class declaration(s) checked, all of them have a KDoc header` | Architect | **closed** |
| R-08 | 400 vs. 550 line rule documented ambiguously | certain | low | Set `--strict` in the CI call or rewrite the rule texts to soft/hard; the tree satisfies both limits today | Owner / Docs | open |
| R-09 | i18n completeness checked outside the Gradle run | medium | medium | `scripts/check-i18n-completeness.sh` runs in CI and is green; a JVM test would close the gap | QA | mitigated |
| R-10 | Release key outside the repository | low / medium | high | The owner backs up `~/.config/phairplay/release.jks` outside the team storage | Owner | open |
| R-11 | Version and changelog drift at release cut | medium | medium | `scripts/release.sh` takes the version as its argument; check it against the changelog before tagging | Release owner | open |
| R-12 | Device test coverage is zero | certain | high | The device test plan (DT-01 … DT-15) exists and is copy-pasteable; it needs hardware | QA / Owner | open |
| R-13 | 38 test methods never run in CI | certain | medium | The six excluded files need a device run or JVM-safe stubs | QA / Platform | open |
| R-14 | Miracast / Samsung visibility proven only at protocol level | certain | medium | The protocol side is covered by 29 tests; the device side is DT-09 and the Samsung check | QA | open |
| R-15 | Samsung Tizen is not a target | certain | medium | Out of scope for v1.0.0; the existing Samsung support serves DLNA televisions | Owner | open (accepted for v1.0.0) |
| R-16 | The lint gate is red: one `UnusedAttribute` finding | was certain | medium | **Closed:** the permission carries `tools:targetApi="31"`; both variants are analysed and green — `:app:lintGoogletvDebug` and `:app:lintFiretvDebug` → `BUILD SUCCESSFUL`, both reports `No issues found.` | Platform | **closed** |
| R-17 | Five classes were split; three files were still too long | medium | medium | The public API is unchanged and the suite is green; the file-size check is now warning-free; the device test plan is the missing proof | Architect | open (file-size part closed) |

**Blocking for a stable v1.0.0:** R-12 (no device test at all), R-03 and R-05
(the DLNA media path — these two can be closed by a written owner decision
instead of a fix, but not silently), and the two open security findings of the
security audit (SEC-04 as an open decision and SEC-10), which are tracked in the
audit and in the release checklist.
**No longer blocking:** R-07 (quality gate) and R-16 (lint) are **closed** with
a measurement; the pipeline is green for both flavours. The three high findings
**SEC-01, SEC-02 and SEC-03 are closed** with a measurement as well: the AirPlay
media URL is checked before the player fetches it, the picture decoder reads the
dimensions first and has a pixel budget, and the reassembled FU-A unit is bounded
to 8 MB.
**Accepted with a written reason:** R-01, R-06, R-15.
