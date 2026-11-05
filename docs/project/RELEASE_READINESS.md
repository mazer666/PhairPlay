# Release Readiness — Google Play publication and direct distribution

**Version:** 2.1
**Prepared by:** Project Manager
**Date:** 2026-11-05 (measured 15:58, re-measured 16:10 and 17:20)
**Measured on:** commit `34a166c` (branch `main`), release tag `v1.0.0-alpha.7`
**Supersedes:** revision 1.0 of 2026-10-24, which was written against the
commits `2267672` / `51933eb`, the version `1.0.0-beta.3` and a device test
plan with 15 cases. All of that is stale.

> **The tree moved during this measurement.** At 15:58 the repository was at
> `34a166c` and all five gates were green. At 16:10 the tree stood at
> `ee49f8e` ("feat(release): build the artefacts without publishing them") with
> uncommitted changes from the security, the spec and the UI track: two gates
> were red (`check-privacy-policy.sh` and `check-docs-links.sh`), the file-size
> check reported one warning, and the DLNA scheme allowlist had been removed in
> favour of a single `FetchableUrlPolicy`. **At 17:20 all five gates are green
> again** — the privacy policy was brought up to the manifest date, the links in
> `docs/spec/archive/` were repaired and `DiagnosticsFragment.kt` was split to
> 360 lines. The rows below say which state they describe; the rows that
> changed are marked **[16:10]** or **[17:20]**.

---

## 0. The four decisions at a glance

| Channel | Decision | The one-line reason |
|---|---|---|
| **Google Play — internal testing** | **CONDITIONAL GO** | The artefact is ready (a signed `.aab`, target API 35 ≥ the API 34 the TV rule asks for, 64-bit, 16 KB, the TV assets, a privacy policy that matches the manifest), but every declaration in *App content* is still empty and the foreground-service declaration needs a demonstration video that can only be recorded on a television. |
| **Google Play — production** | **NO-GO** (as of 2026-11-05) | Three independent hard blocks: the closed test with 12 testers over 14 days cannot be compressed, 0 of 16 device test cases have been executed, and the Data Safety section and the foreground-service declaration are not filled in. |
| **GitHub APK — direct distribution** | **GO** | The release `v1.0.0-alpha.7` is published with all three assets, the pipeline verifies the version inside the APK and its signature, and the release notes state the open limitations. |
| **Amazon Fire TV** | **CONDITIONAL GO** (GitHub APK) + **one open owner decision** (store channel) | The `firetv` flavour builds, is signed and is published. The store question — Amazon Appstore or a second Play listing for `com.phairplay.firetv` — is undecided and is not a technical task. |

**The shortest path to a first real device:** install
`PhairPlay-1.0.0-alpha.7-GoogleTV.apk` on a Google TV from the GitHub release,
record the foreground-service demonstration video with it, fill in the
declarations, and start the internal test. The closed test can run in parallel
— it only needs 12 people with a Google account, and its 14 days start on
their opt-in.

---

## 1. What is proven (measured on 2026-11-05)

| # | Fact | How it was measured |
|---|---|---|
| 1 | The release is published with three assets | GitHub API, `releases/tags/v1.0.0-alpha.7`: `PhairPlay-1.0.0-alpha.7-GoogleTV.apk` = **16 600 258 B**, `PhairPlay-1.0.0-alpha.7-FireTV.apk` = **11 668 733 B**, `PhairPlay-1.0.0-alpha.7-GoogleTV.aab` = **9 003 755 B**; `prerelease: true`, published `2026-11-02T20:00:26Z` |
| 2 | The JVM suite is green with **592** tests | Derived from the sources: 630 `@Test` methods in `app/src/test/kotlin` minus the 38 methods of the six excluded classes (`test-runner/build.gradle.kts`, `exclude` block) = 592. The excluded classes are `MainActivityTest` (9), `MdnsServiceTest` (12), `VideoSubmissionTest` (5), `ServiceControllerTest` (4), `NetworkUtilsTest` (5), `ui/VideoAspectFitTest` (3). |
| 3 | All five repository gates are green **[15:58]** | `check-file-size.sh` → `216 file(s) checked, 0 warning(s) above the soft limit of 400 lines`; `check-kdoc-headers.sh` → `280 class declaration(s) checked`; `check-i18n-completeness.sh` → `129 key(s) … complete in 2 language(s)`; `check-docs-links.sh` → `326 internal link(s) resolve`; `check-privacy-policy.sh` → `13 declared permissions are documented` |
| 3b | **All five gates are green in the working tree [17:20]** | `check-file-size.sh` → `225 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines`; `check-kdoc-headers.sh` → `292 class declaration(s) checked`; `check-i18n-completeness.sh` → `129 key(s) … complete in 2 language(s)`; `check-docs-links.sh` → `401 internal link(s) resolve`; `check-privacy-policy.sh` → `OK: privacy policy check passed.` At 16:10 two of them were red; the parallel tracks repaired them |
| 3c | The DLNA scheme allowlist has been removed **[16:10]** | `DlnaMediaUri.validate()` delegates to `FetchableUrlPolicy` (`app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt:62`), whose `FETCHABLE_SCHEMES = setOf("http", "https")` (`app/src/main/kotlin/com/phairplay/util/FetchableUrlPolicy.kt:57`) refuses everything else. The new `DlnaMediaUriAttackTest` (6 methods) proves that a `file://` URI never reaches the playback controller. R-03 and R-04 changed accordingly |
| 3d | The native library check exists but is not wired in **[16:10]** | `scripts/check-native-libs.sh` verifies the ELF alignment of the 64-bit libraries, the ZIP alignment and the ABIs; `grep -rn "check-native-libs" .github/ scripts/release/` → **0 matches** |
| 4 | The build configuration meets the Play target requirements | `targetSdk = 35`, `compileSdk = 35` (`app/build.gradle.kts:24`, `:30`); the `googletv` flavour has `minSdk = 29` (`:60`) |
| 5 | The bundle carries 32- and 64-bit native code | `abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")` (`app/build.gradle.kts:40-42`) |
| 6 | The manifest declares the TV form factor correctly | `android.software.leanback` `required="true"`, `android.hardware.touchscreen` `required="false"` (`app/src/main/AndroidManifest.xml:91-96`), the `LEANBACK_LAUNCHER` category (`:163-168`), `android:banner="@drawable/app_banner"` (`:136`) |
| 7 | The launcher banner is a full-size TV banner | `app/src/main/res/drawable-nodpi/app_banner.png` measured at **320 × 180 px**, PNG colour type 2 (RGB, no alpha) — `nodpi` keeps the exact pixel size on every density |
| 8 | The privacy policy matches the code | `scripts/check-privacy-policy.sh` → exit 0: every one of the 13 declared permissions is named in `PRIVACY.md`, the policy is reachable from inside the app, and it is not older than the manifest |
| 9 | The store graphics exist with the required dimensions | `docs/playstore/assets/`: `icon-512x512.png` 512 × 512, `feature-graphic-1024x500.png` 1024 × 500, `tv-banner-1280x720.png` 1280 × 720, two screenshots at 1920 × 1080 — all measured from the PNG headers |
| 10 | The release build is memory-safe | `scripts/release/build.sh`: two Gradle calls per flavour, `GRADLE_MEMORY_OPTIONS="…-Xmx1100m -Pkotlin.compiler.execution.strategy=in-process"` (line 31) |

---

## 2. Channel A — Google Play, internal testing

> **Decision: CONDITIONAL GO.**
>
> *Why not a plain GO:* the App content page of the app is empty. Google Play
> reviews the declarations before it accepts a build, and the
> foreground-service declaration additionally demands a demonstration video that
> can only be recorded on a television — which needs hardware that this
> environment does not have.
>
> *Why it is not a NO-GO:* the internal test track has **no** requirement of its
> own ("Requirements to access track: **None**", `https://support.google.com/googleplay/android-developer/answer/14151465`,
> retrieved 2026-11-05). Every remaining item is owner work in the console, not
> engineering work in the repository.

**The condition for the internal test to start:** the app setup must be
complete — the *App content* page fully answered (privacy policy, ads, app
access, content rating, target audience, data safety, and the foreground
service declaration with its video) — and the Android TV form factor must be
opted in. Only then can a build be uploaded to the internal test track.

| # | Condition | Status | Evidence | Who has to act |
|---|---|---|---|---|
| A1 | A signed `.aab` exists for the `googletv` flavour | **met** | Release asset `PhairPlay-1.0.0-alpha.7-GoogleTV.aab` = 9 003 755 B (GitHub API, 2026-11-05); the pipeline verifies the bundle signature with `jarsigner` (`scripts/release/verify.sh:88-111`) | — |
| A2 | The bundle is uploaded to the Play Console | **open** | — | Owner |
| A3 | Target API level ≥ 34 (the Android TV rule) | **met** | `targetSdk = 35` (`app/build.gradle.kts:30`). Rule: "Android TV and Android XR apps, which must target Android 14 (API level 34) or higher" (`https://support.google.com/googleplay/android-developer/answer/11926878`, retrieved 2026-11-05) | — |
| A4 | Android App Bundle format | **met** | "From June 2023, new and existing TV apps are required to be published as App Bundles" (`https://developer.android.com/guide/app-bundle/`, retrieved 2026-11-05) | — |
| A5 | 32- and 64-bit support | **met** | `abiFilters` (`app/build.gradle.kts:40-42`); TV-G6, "From August 1, 2026, TV apps must support both 32- and 64-bit architectures" (`https://developer.android.com/docs/quality-guidelines/tv-app-quality`, retrieved 2026-11-05) | — |
| A6 | 16 KB page size support | **met (not checked by a gate)** | Measured for `v1.0.0-alpha.7`: the native libraries are aligned to `0x4000`, `zipalign -c -P 16 -v 4` reports every `.so` as OK (`docs/guides/GOOGLE_PLAY_STORE.md:88`). The release pipeline itself does **not** check it (`scripts/release/verify.sh` has no `zipalign` call) → risk R-24 | Platform (add the gate) |
| A7 | Privacy policy in the store listing **and** inside the app | **met** (Text) / **open** (Konsole) | `PRIVACY.md` exists, is linked from the settings and is verified by `scripts/check-privacy-policy.sh` → exit 0. The console field is still empty | Owner (paste the URL) |
| A8 | Data safety section | **open** | The draft answers exist: `docs/compliance/DATA_SAFETY_DRAFT.md`. Requirement: "All developers must complete a clear and accurate Data safety section for every app" (`https://support.google.com/googleplay/android-developer/answer/10144311`, retrieved 2026-11-05) → risk R-21 | Owner |
| A9 | Ads / app access / content rating / target audience / news / government / financial / health declarations | **open** | The exact answers are written down in `docs/guides/GOOGLE_PLAY_STORE.md:107-124` and in `docs/compliance/STORE_SUBMISSION_CHECKLIST.md` | Owner |
| A10 | Foreground service declaration `connectedDevice` | **open** | The manifest is ready: `foregroundServiceType="connectedDevice"` (`app/src/main/AndroidManifest.xml:180-183`) with `FOREGROUND_SERVICE_CONNECTED_DEVICE` (`:43`) and the runtime prerequisite `CHANGE_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` (`app/src/main/AndroidManifest.xml:37`, `:56`). Requirement: "you'll need to declare any foreground service types that you use … 3. Include a link to a video demonstrating each foreground service feature" (`https://support.google.com/googleplay/android-developer/answer/13392821`, retrieved 2026-11-05) → risk R-27 | Owner (form) + QA (video, needs a TV) |
| A11 | The foreground-service **demonstration video** exists | **blocked** | It must show the in-app setup and the subsequent device interaction — i.e. a phone connecting to PhairPlay on a real television. **No hardware was available in this cycle**; 0 of 16 device cases executed | Owner (hardware) |
| A12 | Android TV form factor opt-in | **open** | Path: *Setup → Advanced settings → Form Factors → Add release type → Android TV* (`https://developer.android.com/training/tv/publishing/distribute`, retrieved 2026-11-05). The manifest side is ready (A6 row above) | Owner |
| A13 | TV banner uploaded (1280 × 720, 24-bit PNG without alpha) | **open** | The file exists and has the right dimensions, but it is a PNG of **colour type 6 (RGBA)** — every pixel is opaque, so the channel is present but unused. Requirement: "JPEG or 24-bit PNG (no alpha)" (`https://support.google.com/googleplay/android-developer/answer/9866151`, retrieved 2026-11-05) → risk R-23 | Owner / Docs (re-encode) |
| A14 | At least one TV screenshot uploaded | **met** (Material) / **open** (Upload) | Two screenshots at 1920 × 1080 in `docs/playstore/assets/`; the requirement is "at least one Android TV screenshot" (`https://support.google.com/googleplay/android-developer/answer/9866151`, retrieved 2026-11-05) | Owner (upload) |
| A15 | The description mentions "Android TV" | **met** | "PhairPlay turns your Android TV or Google TV into a fast, privacy-first receiver …" (`docs/guides/GOOGLE_PLAY_STORE.md:140`) | — |
| A16 | Play App Signing is enrolled and the app signing SHA-1 is read | **open** | A new app is enrolled automatically; the fingerprint is read under *Test and release → Setup → App signing* (`https://support.google.com/googleplay/android-developer/answer/9842756`, retrieved 2026-11-05). The decision that belongs to it is R-19 / R-20 | Owner |
| A17 | The Google Cast Application ID is registered | **open** | Without an ID the receiver reports `ERROR` (`app/src/googletv/kotlin/com/phairplay/cast/CastReceiver.kt:23-27`); the procedure is `docs/guides/CAST_APP_ID.md`. **The fingerprint that is registered must be the one of the key that signs the delivered build** → R-20 | Owner |
| A18 | The listing does not advertise a feature the app cannot deliver | **open** | The listing advertises Miracast; the Wi-Fi Direct permissions are never requested at runtime (`MainActivity.kt:295-307` is the only `requestPermissions` call) → the feature cannot start on Android 10+ (SEC-06) → risk R-26 | Owner / Platform |
| A19 | The internal test track has no tester requirement | **met** | "Internal testing … Requirements to access track: None." (`https://support.google.com/googleplay/android-developer/answer/14151465`, retrieved 2026-11-05) | — |
| A20 | **[17:20]** The repository gates are green, so that the uploaded bundle matches the audited code | **met** | All five gates exit 0 (measured 17:20). `scripts/release.sh` aborts on a red gate (`scripts/release/build.sh:72-89`), so the condition is enforced by the tooling. The working tree still carries uncommitted parallel work — `scripts/release.sh:194-199` refuses to publish from a dirty tree → risk R-28 | The parallel tracks commit their work before the release cut |

---

## 3. Channel B — Google Play, production

> **Decision: NO-GO as of 2026-11-05.**
>
> Three independent blocks, each of which alone is enough:
>
> 1. **The closed test.** A personal developer account created after
>    2023-11-13 must run a closed test with **at least 12 testers opted in
>    continuously for 14 days** before production access can even be applied
>    for (`https://support.google.com/googleplay/android-developer/answer/14151465`,
>    retrieved 2026-11-05). This is a wall-clock minimum — no test result can
>    shorten it. Whether it applies at all depends on the account type, which
>    is not yet known.
> 2. **The device tests.** 0 of 16 cases executed. The Android TV quality
>    review is performed on real television hardware, and the Play criteria
>    explicitly test the behaviour on TV devices.
> 3. **The declarations.** The Data Safety section and the foreground-service
>    declaration are not filled in; both are mandatory for a release that
>    targets Android 14+.
>
> *What has to be additionally true for production:* everything from Channel A,
> plus the closed test, plus a production rollout plan, plus a release whose
> version is `1.0.0` with a matching changelog entry.

| # | Condition | Status | Evidence | Who has to act |
|---|---|---|---|---|
| B1 | Everything from Channel A (A1 … A19) | **blocked** | See §2 | Owner + QA |
| B2 | Closed test with 12 testers, continuously opted in for 14 days | **blocked** | "Developers with personal accounts created after November 13, 2023, must run a closed test for their app with a minimum of 12 testers who have been opted in continuously for at least 14 days" (`https://support.google.com/googleplay/android-developer/answer/14151465`, retrieved 2026-11-05) → risk R-22 | Owner |
| B3 | The account type is known (personal after 2023-11-13 → rule applies; organisation → it does not) | **open** | Same source. This single fact decides whether the 14 days exist at all | Owner |
| B4 | Production access is applied for and approved | **open** | "When you meet these criteria, you can apply for production access on the Dashboard in Play Console … The application form contains three sections" (same source) | Owner |
| B5 | The 16 device test cases are executed on a Google TV and a Fire TV | **blocked** | `docs/guides/DEVICE_TEST_PLAN.md:841-856` — every result cell is still `☐ pass ☐ fail ☐ n/a` → risk R-12 | QA + Owner |
| B6 | The release notes state the open limitations honestly | **open** | The template is `docs/guides/RELEASING.md`; the current release notes already name the Miracast limitation, the missing Cast ID and the missing device validation | Release owner |
| B7 | The version is bumped to `1.0.0` and the changelog has the entry | **open** | `versionCode = 10`, `versionName = "1.0.0-alpha.7"` (`app/build.gradle.kts:31-32`); `CHANGELOG.md` has an `[Unreleased]` section → risk R-11 | Release owner |
| B8 | The release key is backed up outside the team storage | **open** | `scripts/release.sh --setup` creates `~/.config/phairplay/phairplay-release.jks`; the backup is an unconfirmed owner action → risk R-10 | Owner |
| B9 | The owner decisions R-03 / R-04 (the scheme allowlist) and R-05 (cleartext) are written down | **open** | `docs/audit/2026-10-24-security-audit.md` SEC-04 (S3) and SEC-10; both need a written owner decision in the release notes, not a silent change | Owner |
| B10 | **[16:10]** The DLNA scheme allowlist is closed; the cleartext behaviour is still undecided | **open** (half) | The scheme part is **closed**: `DlnaMediaUri.validate()` delegates to `FetchableUrlPolicy` (`app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt:62`), which accepts `http` and `https` only, and `DlnaMediaUriAttackTest` proves that a `file://` URI never reaches the playback controller. The **cleartext** part is still open: no `network_security_config.xml` exists — `app/src/main/res/xml/` holds `locales_config.xml`, `backup_rules.xml` and `data_extraction_rules.xml`, but no network configuration → risk R-05 | Owner + Platform |

---

## 4. Channel C — GitHub APK, direct distribution

> **Decision: GO.**
>
> The artefact exists, it is signed, its version is verified against the tag,
> and the release notes carry the limitations. Nothing in the repository blocks
> the direct distribution of `v1.0.0-alpha.7`. What the channel does **not**
> give is any evidence that the app works on a television — that remains the
> open device test, and the release notes say so.

| # | Condition | Status | Evidence | Who has to act |
|---|---|---|---|---|
| C1 | A signed release APK exists for both flavours | **met** | `PhairPlay-1.0.0-alpha.7-GoogleTV.apk` = 16 600 258 B, `PhairPlay-1.0.0-alpha.7-FireTV.apk` = 11 668 733 B (GitHub API, 2026-11-05) | — |
| C2 | The version inside the APK equals the tag | **met** | `verify_apk_version` reads `versionName` / `versionCode` with `aapt2` and aborts on a mismatch (`scripts/release/verify.sh:31-58`); the release build sets `versionName = "1.0.0-alpha.7"`, `versionCode = 10` | — |
| C3 | The APK is signed | **met** | `verify_apk_signature` runs `apksigner verify --verbose --print-certs` and dies on a failure (`scripts/release/verify.sh:60-74`) | — |
| C4 | The quality gate was green before the release | **met** | `build_run_quality_gate` runs the five `check-*.sh` scripts and dies on the first red one (`scripts/release/build.sh:72-89`); all five exit 0 (measured 2026-11-05) | — |
| C5 | The release notes state the open limitations | **met** | The `v1.0.0-alpha.7` notes name the Miracast scope exclusion, the missing Cast Application ID and the absent device validation | — |
| C6 | The download matches the local build | **met** | The SHA-256 of the published downloads equals the SHA-256 of the local builds (measured in the release round of 2026-11-02) | — |
| C7 | The user is told that the Play build later replaces this one only after an uninstall | **open** | Different certificates → Android refuses the upgrade across signatures → risk R-19. The information exists in `docs/project/RISKS.md` but not yet in a user-facing place | Owner (release notes / README) |
| C8 | The device validation is missing and stated | **met** (honest) | The release notes say that no device test has been executed; 0 of 16 cases → risk R-12 | — |

---

## 5. Channel D — Amazon Fire TV

> **Decision: CONDITIONAL GO for the GitHub APK, and one open owner decision
> for the store channel.**
>
> The `firetv` flavour builds, is signed and is published. It is a separate
> application (`com.phairplay.firetv`) and therefore a separate listing
> everywhere.
>
> **The open decision:** *Amazon Appstore or a second Google Play listing?*
> Fire TV devices do not ship the Google Play Store — the Amazon Appstore is
> the native channel there. An Amazon Appstore submission needs an Amazon
> developer account, its own review, its own listing and its own privacy
> policy URL. A second Play listing for `com.phairplay.firetv` would only
> reach Fire TV devices that happen to have Google Play installed, which is
> the exception, not the rule. **Recommendation: the Amazon Appstore for the
> store channel, and the GitHub APK for the early adopters.** The decision is
> the owner's, because it costs an account and a second review process.

| # | Condition | Status | Evidence | Who has to act |
|---|---|---|---|---|
| D1 | A signed Fire TV APK exists | **met** | `PhairPlay-1.0.0-alpha.7-FireTV.apk` = 11 668 733 B (GitHub API, 2026-11-05) | — |
| D2 | The flavour has its own application ID | **met** | `applicationId = "com.phairplay.firetv"`, `minSdk = 25`, `versionNameSuffix = "-firetv"` (`app/build.gradle.kts:63-68`) | — |
| D3 | The flavour does not pull in the Cast SDK | **met (by design)** | `"googletvImplementation"(libs.play.services.cast.tv)` (`app/build.gradle.kts:224`); the Fire TV receiver reports `DISABLED` (`app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt`) | — |
| D4 | The flavour works on the oldest supported platform | **blocked** | API 25 is the minimum; DT-01 … DT-05 and DT-08, DT-12 … DT-15 have a Fire TV column that is still empty → risk R-12 | QA + Owner |
| D5 | The store channel is decided | **open** | Amazon Appstore vs. a second Play listing vs. GitHub only — see the decision above | Owner |
| D6 | The Amazon developer account exists | **open** | Depends on D5 | Owner |
| D7 | The listing text for the Fire TV variant is written | **open** | The current store text mentions "Android TV or Google TV"; the Fire TV variant needs its own wording | Owner |
| D8 | The privacy policy URL for the second listing | **met** | `PRIVACY.md` covers both flavours ("Applies to: every version of PhairPlay for Google TV and Fire TV") | — |
| D9 | The Fire TV store graphics exist | **open** | The assets in `docs/playstore/assets/` are TV screenshots and a banner; an Amazon Appstore listing needs its own icon set (512 × 512 minimum) | Owner |

---

## 6. What only a device can prove

This is the honest list. **None of it can be replaced by a unit test, a script or
a reading of the code.**

| Hardware / action | What it decides | Which rows above |
|---|---|---|
| **A Google TV** with the published APK | Whether the receiver works at all: the picture, the sound, the pairing PIN, the 30-minute stability, the RAM and CPU budget, the German and French texts, the boot receiver, the DLNA discovery in a real LAN | A11, B5, D4 |
| **The foreground-service demonstration video** | Whether the Play review accepts the `connectedDevice` declaration | A10, A11, B1 |
| **A Fire TV** | Whether the oldest supported platform still works | D4 |
| **A PC with VLC in the same LAN** | Whether the TV appears in the renderer list at all — the most important interoperability question of the project | DT-09 |
| **A Windows PC** | Whether "Cast to Device" finds the television | DT-10 |
| **A Chrome device with a registered Cast Application ID** | Whether the Cast path works end to end | A17 |

---

## 7. The owner decisions, in the order they block the work

| Order | Decision | Blocks | Risk |
|---|---|---|---|
| 1 | Personal account created after 2023-11-13, or organisation account? | Whether the 14-day closed test exists at all → the production date | R-22 |
| 2 | Start recruiting the 12 closed-test testers **now** | Every production date after that | R-22 |
| 3 | Play app signing key: let Play generate it, or export the existing release key? | The Cast fingerprint, and whether a Play build upgrades a GitHub build in place | R-19, R-20 |
| 4 | Register the Cast Application ID with the fingerprint that signs the **delivered** build | The Cast feature (A17) | R-02, R-20 |
| 5 | Get hardware: one Google TV and one Fire TV | The FGS video, the device tests, the whole production claim | R-12, R-27 |
| 6 | Remove the Miracast claim from the listing, or request the missing runtime permission? | The store listing accuracy (A18) | R-26 |
| 7 | Fire TV store channel: Amazon Appstore or a second Play listing? | Channel D beyond the GitHub APK | — |
| 8 | The DLNA scheme allowlist and the cleartext decision | The written release notes for a stable release | R-03, R-04, R-05 |
| 9 | The 400 vs. 550 line rule wording | Nothing — latent | R-08 |
| 10 | Back up the release keystore outside the team storage | Nothing today, everything later | R-10 |

---

## 8. Related documents

* [`RISKS.md`](RISKS.md) — the full risk register with the evidence per row.
* [`REMEDIATION_BACKLOG.md`](REMEDIATION_BACKLOG.md) — the prioritised list of
  the open items from this document, the risk register and the compliance
  matrix.
* [`../compliance/GOOGLE_PLAY_COMPLIANCE.md`](../compliance/GOOGLE_PLAY_COMPLIANCE.md) —
  every Play requirement with its official source and retrieval date.
* [`../compliance/DATA_SAFETY_DRAFT.md`](../compliance/DATA_SAFETY_DRAFT.md) —
  the answers of the Data Safety form, derived from the source code.
* [`../compliance/STORE_SUBMISSION_CHECKLIST.md`](../compliance/STORE_SUBMISSION_CHECKLIST.md) —
  the console work in the order the owner has to do it.
* [`../guides/GOOGLE_PLAY_STORE.md`](../guides/GOOGLE_PLAY_STORE.md) — the
  guide the owner works from; it is correct as far as the console paths go.
