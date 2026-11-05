# PhairPlay — Risk Register

**Version:** 2.0
**Status:** Active
**Owner:** Project Manager
**Date:** 2026-11-05
**Measured on:** commit `34a166c` (branch `main`), tag `v1.0.0-alpha.7`

**Last measured (2026-11-05, baseline `34a166c`):** the five repository gates of
`scripts/` on the release tree — `check-file-size.sh` → `OK: file size check
passed — 216 file(s) checked, none above the hard limit of 550 lines, 0
warning(s) above the soft limit of 400 lines.`, `check-kdoc-headers.sh` → `OK:
KDoc header check passed — 280 class declaration(s) checked, all of them have a
KDoc header.`, `check-i18n-completeness.sh` → `OK: i18n completeness check
passed — 129 key(s) in app/src/main/res/values/strings.xml, complete in 2
language(s).`, `check-docs-links.sh` → `OK: documentation link check passed —
326 internal link(s) resolve, 26 external link(s) skipped.`,
`check-privacy-policy.sh` → `OK: all 13 declared permissions are documented in
the privacy policy.` + `OK: privacy policy check passed.` The JVM suite count
was derived from the sources: 630 `@Test` methods in `app/src/test/kotlin` minus
the 38 methods of the six excluded classes (`test-runner/build.gradle.kts`,
`exclude` block) = **592**, matching the measured run.

**Re-measured 2026-11-05, 16:10 (working tree, after the parallel tracks
landed):** the tree is no longer at `34a166c` — the commit `ee49f8e` ("feat
(release): build the artefacts without publishing them") was added at 15:59 and
the working tree carries uncommitted changes from the security and the spec
track. The state has moved:

* `check-file-size.sh` → `221 file(s) checked, none above the hard limit of 550
  lines, **1 warning(s) above the soft limit of 400 lines**`
  (`app/src/main/kotlin/com/phairplay/ui/DiagnosticsFragment.kt`, 407 lines).
* `check-kdoc-headers.sh` → `287 class declaration(s) checked`.
* `check-privacy-policy.sh` → **RED**: "the privacy policy (2026-11-02) is
  older than the manifest (2026-11-05)". The manifest was edited in the
  working tree (the backup and data-extraction rules); the permission set did
  not change, but the gate compares the *dates*.
* `check-docs-links.sh` → **RED**: 31 broken internal links, all of them in
  `docs/spec/archive/` (the spec track's files). None of the broken links is in
  `docs/project/` or `docs/compliance/`.
* `@Test` methods in `app/src/test/kotlin`: **665** (was 630). The exclude list
  is unchanged, so **627** methods would run — derived, not measured, because
  no Gradle run was permitted in this session. The last **measured** figure
  remains **592** at the release `v1.0.0-alpha.7`.
* The DLNA scheme allowlist is **gone**: `DlnaMediaUri.validate()` now
  delegates to `FetchableUrlPolicy`, which accepts `http` and `https` only.
  See R-03 and R-04.

**What this revision changed.** Revision 1.2 was written on 2026-10-24 against
commits `2267672` / `51933eb` and carried the numbers of that day (549 tests,
184 files, 227 KDoc declarations, 114 i18n keys, 15 device test cases). Every
one of those numbers is stale. The register below was re-derived from the code
of `34a166c`, the store documents were read again on 2026-11-05, and ten new
risks (R-18 … R-28) were added that belong to the Google Play publication and
to the concurrent work on the tree.

---

## 0. How to read this register

* **Likelihood** — `certain` / `high` / `medium` / `low`. A likelihood is a
  statement about the *state of the code today*, not about the future.
* **Impact** — `high` / `medium` / `low`, judged against the release rules of
  [`../quality/QUALITY.md`](../quality/QUALITY.md) §7: an S4 finding (critical)
  blocks a release, an S3 finding (major) needs a written owner decision.
* **Countermeasure** — what is already in the code, or the concrete action that
  closes the gap. Every countermeasure names the file or the command that
  proves it.
* **Status** — `open`, `open (accepted)`, `mitigated`, `closed`. `open` always
  carries a reason in the last column.
* **Evidence / reason** — the proof for the row. A row without a file, a
  command, a test name or an official URL is not a proven row.

§1 is the authoritative one-line view. §2 carries the detail of the risks that
changed in this revision and of the new ones; the unchanged risks keep their
proof in §1.

---

## 1. Register

| ID | Risk | Likelihood | Impact | Countermeasure | Owner | Status | Evidence / reason |
|---|---|---|---|---|---|---|---|
| R-01 | Miracast without a public Wi-Fi Display API | certain | medium | Ship the `_wfd._tcp` advertisement and the WFD RTSP control plane only; the media path is a documented scope exclusion | Product / Platform | open (accepted) | ADR-004 and `docs/spec/REQUIREMENTS.md` §3 exclude the media path; `grep -rn "MiracastDecoder\|MpegTs\|TsDemux" app/src/main` → 0 matches. The limitation is architectural, so no countermeasure can close it. |
| R-02 | Google Cast without a registered application ID | certain | medium | The receiver reports `ERROR` instead of advertising; the acquisition procedure is written down | Owner | open | `CastReceiver.start()` logs `Google Cast is not configured: missing Cast application ID` and reports `ERROR` (`app/src/googletv/kotlin/com/phairplay/cast/CastReceiver.kt:23-27`). The ID can only be assigned by the Google Cast Developer Console — the owner has to register it. |
| R-03 | SSRF via the DLNA media URI | medium | high (S3 → written owner decision) | The address of the media URI is checked (`HostAddressPolicy`); **the scheme list has been removed**; the public addresses and the un-resolved host names remain open | DLNA / Owner | open — partly closed | `app/src/main/kotlin/com/phairplay/util/HostAddressPolicy.kt` refuses loopback, link-local/metadata, multicast, broadcast, the obfuscated notations and user info; `HostAddressPolicyTest` covers 11 cases. **The scheme residual is closed as of the concurrent change:** `DlnaMediaUri.validate()` no longer has a list of its own and delegates to `FetchableUrlPolicy` (`app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt:62`), whose `FETCHABLE_SCHEMES = setOf("http", "https")` (`app/src/main/kotlin/com/phairplay/util/FetchableUrlPolicy.kt:57`) refuses everything else. What stays open is a **decision**: a **public** address is accepted deliberately (the control point chooses the medium) and a **host name** is not resolved, so a name that points at a link-local address remains a residual risk. Finding SEC-04. |
| R-04 | `file://` turns the TV into a file reader | was medium | medium | **Closed:** the scheme is refused on the DLNA path as well, and the refusal is proven by a negative test | DLNA | **closed** | `DlnaMediaUri.validate("file:///etc/passwd")` is rejected with "the URL scheme is not supported" (`app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt:37`, the check itself at `:62` via `FetchableUrlPolicy.check`). The new `app/src/test/kotlin/com/phairplay/dlna/DlnaMediaUriAttackTest.kt` (6 test methods) proves that a file URI "is refused and never reaches the playback controller" and that a refused URI is not stored and is not echoed back by `GetMediaInfo`. The app sandbox remains the outer boundary (`android:allowBackup="false"`, `app/src/main/AndroidManifest.xml:133`). |
| R-05 | Cleartext HTTP is blocked by the platform default | medium | high | Decide on a network security configuration for private LAN ranges (CR-02) or document the DLNA media path as `https`-only | Platform | open | Re-measured 2026-11-05: `app/src/main/res/xml/` contains only `locales_config.xml`, and `grep -rn "networkSecurityConfig\|usesCleartextTraffic" app/src/` → **no match**. On API 28+ the platform therefore blocks `http://`. The DLNA media fetch and the GENA `NOTIFY` are affected; no device test has measured which paths actually fail (0 of 16 cases). |
| R-06 | GENA callback: check and connect differ | low | medium | Host names are refused outright; the residual window is accepted and documented | DLNA | open (accepted for v1.0.0) | `GenaCallbackPolicy` refuses every DNS name (`GenaCallbackPolicyTest` → `rejects a DNS name`), which closes the window for the common case. The remaining TOCTOU is SEC-09. |
| R-07 | The quality gate is red | was certain | medium | **Closed at the baseline; red again in the current working tree** | Architect | **closed at `34a166c`, open in the working tree** | Measured green on `34a166c` (2026-11-05): file-size `216 file(s) checked, 0 warning(s) above the soft limit of 400 lines`; KDoc `280 class declaration(s) checked`; i18n `129 key(s) … complete in 2 language(s)`; docs-links `326 internal link(s) resolve`; privacy `13 declared permissions are documented`. **Re-measured at 16:10 in the working tree: two gates are red** — `check-privacy-policy.sh` ("the privacy policy (2026-11-02) is older than the manifest (2026-11-05)") and `check-docs-links.sh` (31 broken links, all in `docs/spec/archive/`), and `check-file-size.sh` reports one warning (`DiagnosticsFragment.kt`, 407 lines). The causes are the uncommitted changes of the parallel tracks, not a regression of this register's scope → risk R-28 |
| R-08 | 400 vs. 550 line rule documented ambiguously | certain | low | Set `--strict` in the CI call or rewrite the rule texts to soft/hard | Owner / Docs | open | `docs/CONTRIBUTING.md` and NFR-23 say "no source file over 400 lines", the script warns at 400 and fails at 550, and the CI call does not pass `--strict` (`.github/workflows/ci.yml:51-52` passes only `--ext .kt --ext .sh`). The tree satisfies both limits today, so the gap is latent. |
| R-09 | The i18n completeness check is outside the Gradle run | medium | medium | The script runs in CI and is green; a JVM test would close the gap | QA | mitigated, with a known gap | `scripts/check-i18n-completeness.sh` → exit 0, `129 key(s) … complete in 2 language(s)`; it runs in the CI job `quality-gate`. Android's own `MissingTranslation` lint is switched off (`app/build.gradle.kts:152`). The placeholder comparison is a sequence comparison and `<plurals>` / `<string-array>` are not compared at all. |
| R-10 | The release key lives outside the repository | low / medium | high | The owner backs up `~/.config/phairplay/phairplay-release.jks` outside the team storage | Owner | open | `scripts/release.sh --setup` creates the keystore and `~/.config/phairplay/release.env` (mode 600) and the script refuses to run without them (`scripts/release.sh:180-184`). The backup is an owner action that has not been confirmed. |
| R-11 | Version and changelog drift at the release cut | medium | medium | `scripts/release.sh` takes the version as its argument and aborts when the APK version differs from the tag | Release owner | open | `verify_apk_version` dies with "the build and the tag do not belong together" (`scripts/release/verify.sh:49-52`). The version is currently `versionCode = 10`, `versionName = "1.0.0-alpha.7"` (`app/build.gradle.kts:31-32`); the next stable cut still needs the bump and the changelog entry. |
| R-12 | Device test coverage is zero | certain | high | The device test plan exists and is copy-pasteable; it needs hardware | QA / Owner | open | The plan holds **16** cases (DT-01 … DT-16); the result sheet at `docs/guides/DEVICE_TEST_PLAN.md:841-856` is still empty (every cell is `☐ pass ☐ fail ☐ n/a`). **0 of 16 executed.** This is the largest single gap and the reason the word "stable" is not defensible yet. |
| R-13 | Six test classes never run in CI | certain | medium | The six excluded files need a device run or JVM-safe stubs | QA / Platform | open | The `exclude` block in `test-runner/build.gradle.kts` removes `MainActivityTest.kt` (9), `MdnsServiceTest.kt` (12), `VideoSubmissionTest.kt` (5), `ServiceControllerTest.kt` (4), `NetworkUtilsTest.kt` (5) and `ui/VideoAspectFitTest.kt` (3) — **38 methods** that are unproven, not proven-failing. At the baseline `34a166c`: 630 `@Test` − 38 = the **592** the JVM run reports. In the working tree of 16:10 the total is 665 `@Test`, so **627** would run — derived, not measured, because no Gradle run was permitted. The exclude list itself is unchanged. |
| R-14 | The Miracast and Samsung visibility claims are protocol-level only | certain | medium | The protocol side is covered by 29 tests; the device side is DT-09 and the Samsung check | QA | open | `SamsungSsdpResponderTest`, `DialAppCatalogTest`, `DialEndpointTest`, `DlnaDeviceDescriptionFieldsTest` — 29 green tests. That a *television* lists the device is decided by the TV model, not by PhairPlay; DT-09 and the Samsung check have never run. The release manifest of the new `--artifacts-only` mode states the same gap in the artefact itself: "0 of 16 device test cases were executed" (commit `ee49f8e`). |
| R-15 | Samsung Tizen is not a target | certain | medium | Out of scope for v1.0.0; the existing Samsung support serves DLNA televisions | Owner | open (accepted for v1.0.0) | The repository knows two flavours only (`app/build.gradle.kts:56-69`), and no Tizen case exists in the device test plan. A Tizen television cannot run an APK. |
| R-16 | The lint gate is red | was certain | medium | **Closed:** both flavours are analysed and green | Platform | **closed** | The finding was `app/src/main/AndroidManifest.xml` carrying `android:usesPermissionFlags="neverForLocation"` without `tools:targetApi="31"`; the attribute is now marked (`app/src/main/AndroidManifest.xml:62-65`). Measured in the earlier round: `:app:lintGoogletvDebug` and `:app:lintFiretvDebug` → `BUILD SUCCESSFUL`, both reports `No issues found.` The configuration stays strict (`abortOnError = true`, `warningsAsErrors = true`, `app/build.gradle.kts:137-140`). |
| R-17 | Five classes were split; the behaviour of the moved code is unproven | medium | medium | The public API is unchanged and the suite is green; the device test plan is the missing proof | Architect | open (file-size part closed) | `RtspHandler.kt` 1024 → 267, `PhairPlayService.kt` 650 → 289, `AirPlayReceiver.kt` 658 → 374, `MdnsService.kt` 527 → 338, `AudioStreamServer.kt` 503 → 259. The file-size half is closed by the measurement in R-07; the behavioural half needs a device. |
| R-18 | The release build exhausts the memory of the build machine | was certain | high | **Solved:** the compile and the shrinking run in two Gradle calls, the heap is capped at 1100 MB and the Kotlin compiler runs in-process | Release owner | **mitigated** (root cause solved) | The kernel killed the build inside `:app:minifyGoogletvReleaseWithR8` without a message in the log. The fix is in `scripts/release/build.sh`: `GRADLE_MEMORY_OPTIONS="-Dorg.gradle.jvmargs=-Xmx1100m -Pkotlin.compiler.execution.strategy=in-process"` (line 31) and two calls per flavour — `:app:compile<flavour>ReleaseKotlin` then `:app:assemble<flavour>Release` (lines 126-130). Both flavours and the bundle built with it for `v1.0.0-alpha.7`. **Residual:** the CI job `jvm-tests` still calls `./gradlew :test-runner:test` **without** these options (`.github/workflows/ci.yml:88`), and the CI does not build a release at all — the release path is only exercised on the release machine. |
| R-19 | Play App Signing: the installed signature differs from the local one | certain | high | Decide before the first Play upload whether Play generates the app signing key or the owner exports the existing one | Owner | open | Play signs the delivered APKs with its own app signing key (Google-generated RSA 4096 bit, or the owner-supplied one) while the local key signs only the upload bundle (`https://support.google.com/googleplay/android-developer/answer/9842756`, retrieved 2026-11-05). If Play generates the key, the **certificate of the Play build differs from the certificate of the GitHub APK** — an installed GitHub APK must be uninstalled before the Play build can be installed, and the Cast registration has to use the Play fingerprint (see R-20). The local release key fingerprint is `F4:BF:7B:F0:E1:40:1D:0C:33:EE:85:C2:51:FF:24:12:1B:D5:7C:E4` (`docs/guides/CAST_APP_ID.md`, `docs/guides/GOOGLE_PLAY_STORE.md:172`). |
| R-20 | The Google Cast registration needs the fingerprint of the **Play** signing key, not the upload key | high | medium | Register the Cast Application ID with the Play app signing SHA-1, or export the release key as the app signing key | Owner | open | Play App Signing guidance: "If your app uses APIs such as Google Maps, OAuth, or Facebook Login, those services authenticate your app using your app signing key's fingerprint. Because Google signs the final APK, you must register the Google-held app signing key fingerprint with your API providers, not just your local upload key." (`https://support.google.com/googleplay/android-developer/answer/9842756`, retrieved 2026-11-05). The Cast Developer Console compares the fingerprint with the certificate that actually signed the APK (`docs/guides/CAST_APP_ID.md`, "Why the fingerprint matters"). The current guide documents only the **local** fingerprint → the registration would fail for a Play-signed build. |
| R-21 | The Data Safety section has not been declared | certain | high | Fill in the form from the code-derived draft in `docs/compliance/DATA_SAFETY_DRAFT.md` | Owner | open | Google Play requires "a clear and accurate Data safety section for every app detailing collection, use, and sharing of user data" (`https://support.google.com/googleplay/android-developer/answer/10144311`, retrieved 2026-11-05). The draft answers exist in `docs/compliance/DATA_SAFETY_DRAFT.md`; the console form has not been filled in. An incomplete App content page blocks the release review. |
| R-22 | The production track needs a closed test with 12 testers over 14 days | certain (personal account created after 2023-11-13) | high | Start the closed test as early as possible — the 14 days cannot be compressed | Owner | open | "Developers with personal accounts created after November 13, 2023, must run a closed test for their app with a minimum of 12 testers who have been opted in continuously for at least 14 days. When you meet these criteria, you can apply for production access" (`https://support.google.com/googleplay/android-developer/answer/14151465`, retrieved 2026-11-05). **Whether the owner's account is a personal account created after that date is unknown** — this decides whether the 14-day rule applies at all. Internal testing is explicitly optional and has no requirement. |
| R-23 | The Android TV banner carries an alpha channel | certain | low | Re-encode the banner as a 24-bit PNG without alpha | Docs / Owner | open | The Play requirement is "JPEG or 24-bit PNG (no alpha), dimensions 1280px by 720px" (`https://support.google.com/googleplay/android-developer/answer/9866151`, retrieved 2026-11-05). Measured on 2026-11-05: `docs/playstore/assets/tv-banner-1280x720.png` is a PNG of colour type 6 (RGBA) — every pixel is fully opaque (alpha min = alpha max = 255), so the channel is present but carries no transparency. The visual result is unchanged; the format does not match the wording of the requirement. The icon, the feature graphic and the screenshots are the same case. |
| R-24 | The 16 KB page size and the 64-bit requirement are not wired into a gate | high | medium | Wire `scripts/check-native-libs.sh` into the CI job and into `scripts/release/verify.sh` | Platform | open (the script exists, the wiring does not) | The check itself was added concurrently: `scripts/check-native-libs.sh` verifies the ABI list, the **ELF alignment of every LOAD segment of every 64-bit library**, the ZIP alignment (every `lib/**.so` uncompressed on a 16 KB boundary) and the ABIs against `app/build.gradle.kts` — exactly what was missing, plus a self-test `scripts/test-check-native-libs.sh`. **But `grep -rn "check-native-libs" .github/ scripts/release/` → 0 matches**: the script runs neither in CI nor in the release pipeline, so a violation cannot turn the build red. The release pipeline still verifies only the version and the signature (`scripts/release/verify.sh`). The build configuration itself is correct (`abiFilters` = `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, `app/build.gradle.kts:40-42`) and the 16 KB alignment was measured manually for `v1.0.0-alpha.7`. |
| R-25 | The bundled Cast SDK is five years old | certain | low | Decide whether to update `play-services-cast-tv` before the store publication | Platform | open | `gradle/libs.versions.toml` pins `play-services-cast-tv = "20.0.0"`. The Play Services SDK versions that Google supports move forward, and the app registers a *new* Cast Application ID for a 2026 publication with a 2021 SDK. The current version is what the graceful-degradation path was proven against (`CastReceiverOptionsProvider`, `CastReceiver`). No measured defect is known. |
| R-26 | The store listing advertises a feature that cannot work | high | high | Remove the Miracast claim from the listing, or request the missing runtime permission | Owner / Platform | open | The store listing text in `docs/guides/GOOGLE_PLAY_STORE.md:140-156` advertises "Miracast discovery". The runtime permissions for Wi-Fi Direct are **never requested**: the only `requestPermissions` call in the whole app asks for `POST_NOTIFICATIONS` (`app/src/main/kotlin/com/phairplay/MainActivity.kt:295-307`). `hasWifiP2pPermission()` therefore returns `false` on Android 10+ (`app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt:247-250`) and the Miracast state becomes `ERROR` — finding SEC-06, restated in `PRIVACY.md` §6. A listing that describes a feature the app cannot deliver is a misrepresentation risk in the review. |
| R-27 | The foreground-service declaration needs a demonstration video recorded on a television | high | medium | Record the video on a Google TV with the published APK | Owner / QA | open | "When your apps target Android 14 and above, you'll need to declare any foreground service types that you use … 3. Include a link to a video demonstrating each foreground service feature." (`https://support.google.com/googleplay/android-developer/answer/13392821`, retrieved 2026-11-05). PhairPlay declares `foregroundServiceType="connectedDevice"` (`app/src/main/AndroidManifest.xml:182`) and the matching `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission (`:43`). The video must show the in-app setup and the device interaction — which means a real television (R-12) and a recording. The runtime prerequisite of the type is met: `CHANGE_WIFI_STATE` (`:56`) and `CHANGE_WIFI_MULTICAST_STATE` (`:37`) are declared (`https://developer.android.com/develop/background-work/services/fgs/service-types`, retrieved 2026-11-05). |
| R-28 | The working tree carries uncommitted changes from parallel tracks, and two gates are red | certain | high | Land or revert the parallel changes before the release cut, then re-run the five gates | Release owner + all track owners | open | Observed on 2026-11-05 at 16:10: the tree is at `ee49f8e` (not the `34a166c` baseline this register was measured against) and carries uncommitted modifications in the production code, the spec documents and `docs/spec/archive/`. Two gates are **red**: `check-privacy-policy.sh` ("the privacy policy (2026-11-02) is older than the manifest (2026-11-05)") and `check-docs-links.sh` (31 broken links, all in `docs/spec/archive/`). `check-file-size.sh` reports one warning (`DiagnosticsFragment.kt`, 407 lines). `scripts/release.sh` refuses to publish from a dirty tree (`scripts/release.sh:194-199`), so the release cut is stopped by the tooling — but the **substantive** risk is that a store upload could carry a manifest change that no release note describes, and that the privacy policy would then be older than the app it documents |

---

## 2. Detail on the new risks

### R-18 — The release build and the memory of the machine

The failure was silent: the kernel killed the build inside
`:app:minifyGoogletvReleaseWithR8`, and the log carried no error message. Two
changes closed it, both in `scripts/release/build.sh`:

1. **The compile and the shrinking run in two Gradle calls.** The Kotlin
   compiler and R8 are the two memory hogs of a release build; in two calls the
   compiler is gone before R8 starts, and R8 is up to date in the second call.
2. **The heap is capped at 1100 MB and the compiler runs in-process.** The
   default runs the compiler in its own daemon JVM whose heap stays alive while
   R8 needs the rest of the memory. 1100 MB was measured: both flavours and the
   bundle build with it.

What is **not** closed: the CI never exercises the release path. The `jvm-tests`
job runs the JVM suite with the default memory settings and no
`--no-daemon`, and the `android-build` job builds **debug** APKs. A regression
in the release memory profile would therefore only surface on the release
machine, in the middle of a release cut.

### R-19 / R-20 — Two certificates, one Cast registration

Google Play App Signing splits the signing into two keys:

| Key | Who holds it | Purpose |
|---|---|---|
| Upload key | the owner (`~/.config/phairplay/phairplay-release.jks`) | signs the `.aab` before the upload; proves the identity of the uploader |
| App signing key | Google Play | signs the APKs that the devices install |

The consequence for PhairPlay is concrete:

* The APK installed from GitHub and the APK installed from the Play Store carry
  **different certificates** if Play generates the app signing key. Android
  refuses to upgrade across different signatures → the GitHub build has to be
  uninstalled first.
* The Google Cast Developer Console compares the registered fingerprint with
  the certificate that actually signed the running APK. A registration with the
  local upload fingerprint does **not** start a receiver that is delivered by
  Play.

The owner therefore has one decision with two branches, and it has to be taken
**before** the first upload:

* **Branch A — Play generates the app signing key.** Read the Play app signing
  SHA-1 from *Test and release → Setup → App signing* after the first upload
  and register the Cast Application ID with that fingerprint. The local
  fingerprint stays valid for the GitHub channel.
* **Branch B — the owner exports the existing release key as the app signing
  key.** Then both channels carry the same certificate
  `F4:BF:7B:F0:E1:40:1D:0C:33:EE:85:C2:51:FF:24:12:1B:D5:7C:E4`, the Cast
  registration works for both, and a Play build can upgrade a GitHub build in
  place. Branch B is what `docs/guides/GOOGLE_PLAY_STORE.md:176-179` already
  suggests; it needs the PEPK export to be done before the first release.

### R-22 — The 14 days that cannot be compressed

Google Play requires **new personal developer accounts created after 13 November
2023** to run a closed test with **at least 12 testers opted in continuously
for 14 days** before the app can be published to production. The 14 days are a
wall-clock minimum; no amount of testing compresses them. Internal testing has
**no** requirement at all and is explicitly optional.

Three consequences for the owner:

1. The account type decides whether the rule applies. An **organisation**
   account has no such requirement.
2. If the rule applies, the closed test has to start **before** the production
   date is planned — the earliest possible production date is 14 days after the
   12th tester opted in.
3. The questionnaire asks how the testers were recruited and how they engaged.
   A test track with no real usage is rejected with "more testing required",
   which restarts the 14 days.

### R-26 — The listing must describe what the app does

`PRIVACY.md` §6 states the gap without softening it: the Wi-Fi Direct
permissions are declared but never requested, "the Miracast (Wi-Fi Direct)
advertisement cannot start. This is a functional gap, not a privacy feature".
The store listing nevertheless advertises Miracast. Two ways out:

* **Remove the claim** from the listing text. Cheap, honest, and it costs
  nothing but the feature promise.
* **Request the permission at runtime** when the user switches Miracast on.
  This asks the user for `NEARBY_WIFI_DEVICES` (with `neverForLocation`, so no
  location is derived) — an acceptable request on Android 13+, but on Android
  10-12 it needs the fine location permission, which is the worst permission
  request in the manifest for a feature that ADR-001 already calls unreliable.

The removal is the recommended step; the permission request is an engineering
change with a privacy cost and needs an owner decision.

---

## 3. Blocking summary for the Google Play publication

**Blocking the production track:**

* **R-22** — the 12-testers / 14-days closed test (a wall-clock minimum).
* **R-12** — 0 of 16 device test cases; the Android TV quality review is
  executed on real television hardware.
* **R-21** — the Data Safety section is not declared.
* **R-26** — the listing advertises a feature that cannot work.
* **R-28** — two gates are red in the working tree, so the bundle that would be
  uploaded is not the bundle that was audited.

**Blocking the internal test track:** R-21, R-27 (the foreground-service
demonstration video needs a television), R-23 (the banner format) and R-28.
R-19 and R-20 are owner decisions that must be taken before the first upload,
because the Cast registration depends on them.

**Not blocking, but open:** R-03 in part (the two residual points — the public
address and the un-resolved host name — need a written owner decision, not a
silent change), R-05 (the cleartext decision), R-02 (the Cast Application ID,
needed for the Cast feature to work at all), R-08 (the rule wording), R-10 (the
key backup), R-11 (the version bump), R-24 (the native library check is not
wired into a gate), R-25 (the Cast SDK age), R-13 (38 test methods outside
CI).

**Closed with a measurement:** R-07 (quality gate — at the baseline `34a166c`;
red again in the working tree, see R-28), R-16 (lint), R-18 (the release build
memory — root cause solved), R-03 in part (the address policy **and** the
scheme list), R-04 (the `file://` file reader).
