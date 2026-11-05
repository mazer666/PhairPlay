# PhairPlay — Remediation Backlog

**Version:** 1.1
**Owner:** Project Manager
**Date:** 2026-11-05 (measured 15:58, re-measured 16:10)
**Measured on:** commit `34a166c` (branch `main`), release `v1.0.0-alpha.7`
**Scope:** the open items of [`RISKS.md`](RISKS.md),
[`RELEASE_READINESS.md`](RELEASE_READINESS.md) and
[`../compliance/GOOGLE_PLAY_COMPLIANCE.md`](../compliance/GOOGLE_PLAY_COMPLIANCE.md),
merged into one list and sorted by the effect of closing them.

> **The tree moved while this list was being written.** At 15:58 the repository
> was at `34a166c` with all five gates green. At 16:10 it stood at `ee49f8e`
> with uncommitted changes from the parallel tracks. Two consequences for this
> list: **RB-10 is closed** (the DLNA scheme allowlist was removed in favour of
> the shared `FetchableUrlPolicy`), and **RB-26 is new** (two gates are red in
> the working tree). Everything else stands as measured.

**How to read the order:** the list is sorted by *effect*, not by effort. The
first tier contains the items without which no Google Play release happens at
all; the last tier contains the items that improve the repository without
blocking a channel. Two items are deliberately **not** in this list because
they are not engineering work: the recruitment of the closed-test testers and
the hardware procurement are owner actions and are tracked in the release
readiness document (§7).

---

## Tier 1 — Without these, no Google Play release happens

| ID | Maßnahme | Quelle | Schweregrad | Aufwand | Abhängigkeit | Status | Vorgeschlagener Umsetzungsschritt |
|---|---|---|---|---|---|---|---|
| RB-01 | **Execute the 16 device test cases on a Google TV and a Fire TV** and fill in the result sheet | R-12, R-14, readiness B5 | critical | high (hardware + 2 days of testing) | hardware access (owner) | open | The plan is copy-pasteable (`docs/guides/DEVICE_TEST_PLAN.md`, cases DT-01 … DT-16, result sheet at lines 841-856). Install the published Google TV APK on a Google TV, walk the list in order, paste the `adb logcat` excerpts and the screenshots into the sheet. Every later claim about the app rests on this sheet. |
| RB-02 | **Fill in the Data Safety section** in the Play Console | R-21, compliance matrix row "Data safety" | critical | low (the answers are drafted) | RB-15 (owner confirmation on the Cast delivery) | open | Open *Policy and programs → App content → Data safety* and transfer the answers from `docs/compliance/DATA_SAFETY_DRAFT.md` question by question. Answer "No data collected, no data shared" for every type, but only after the owner has confirmed the three open items the draft marks. |
| RB-03 | **Record the foreground-service demonstration video** for the `connectedDevice` type | R-27, readiness A11 | high | medium (needs the TV from RB-01) | RB-01 | open | The video must show the in-app setup and the subsequent device interaction — a phone discovering and connecting to PhairPlay while the foreground service runs. Record it on the same television as RB-01, upload it somewhere the Play reviewer can reach, and paste the link into the declaration. |
| RB-04 | **Complete the "App content" page** (ads, app access, content rating, target audience, news, government, financial, health) | readiness A9 | high | low | — | open | The answers are already written down in `docs/guides/GOOGLE_PLAY_STORE.md:107-124`. Transfer them unchanged. Do not invent new answers. |
| RB-05 | **Opt into the Android TV form factor and upload the TV assets** | readiness A12, A13, A14 | high | low | RB-07 (the banner format) | open | *Setup → Advanced settings → Form factors → Add release type → Android TV*. Then upload the banner and the two screenshots from `docs/playstore/assets/` under the Android TV tab of the main store listing. |
| RB-06 | **Decide and register the Google Cast Application ID** with the fingerprint of the key that signs the **delivered** build | R-02, R-19, R-20 | high | medium (10 minutes in the console, once the fingerprint is known) | the Play app signing decision (RB-08) | open | Follow `docs/guides/CAST_APP_ID.md`. Use `./scripts/cast-registration-info.sh` to read the fingerprint out of the key instead of copying it by hand. If Play generates the app signing key, read the Play SHA-1 from *Test and release → Setup → App signing* **after** the first upload and register that one. |
| RB-07 | **Re-encode the store graphics without an alpha channel** | R-23, compliance matrix row "TV banner" | low | low | — | open | The requirement is "JPEG or 24-bit PNG (no alpha)". All five PNGs in `docs/playstore/assets/` are colour type 6 (RGBA) with fully opaque pixels. Re-save them as colour type 2 (RGB) — the visible result is identical. |
| RB-08 | **Decide the Play App Signing branch** and document it | R-19, R-20 | high | low (a decision, then the PEPK export) | — | open | Branch A: let Play generate the app signing key → the Play fingerprint differs from the local one → a GitHub-installed build must be uninstalled before the Play build can be installed. Branch B: export the existing release key as the app signing key → both channels share the certificate `F4:BF:7B:F0:E1:40:1D:0C:33:EE:85:C2:51:FF:24:12:1B:D5:7C:E4` and the Cast registration works for both. Write the branch into `docs/guides/GOOGLE_PLAY_STORE.md` §5. |
| RB-09 | **Make the store listing match what the app does** — remove the Miracast claim, or request the missing runtime permission | R-26, SEC-06 | high | low (remove) / medium (request) | — | open | The recommended step is the removal: the full description in `docs/guides/GOOGLE_PLAY_STORE.md:140-156` advertises "Miracast discovery", but `MiracastReceiver.hasWifiP2pPermission()` returns `false` on Android 10+ because the Wi-Fi Direct permissions are never requested (`MainActivity.kt:295-307` is the only `requestPermissions` call in the app). The alternative — a runtime request for `NEARBY_WIFI_DEVICES` — needs an owner decision because on Android 10-12 it drags in the fine location permission. |

---

## Tier 2 — Security findings that need a written owner decision

These do not block the store upload. They block the word **"stable"**, because
[`../quality/QUALITY.md`](../quality/QUALITY.md) §7 demands a written owner
decision for every open S3 finding, and a release note that hides one is
worse than a finding.

| ID | Maßnahme | Quelle | Schweregrad | Aufwand | Abhängigkeit | Status | Vorgeschlagener Umsetzungsschritt |
|---|---|---|---|---|---|---|---|
| RB-10 | ~~Decide the DLNA media URI scheme allowlist~~ — **closed concurrently** | R-03, R-04, SEC-04 (S3) | high | — | — | **closed** | `DlnaMediaUri` has no scheme list of its own any more: `validate()` delegates to `FetchableUrlPolicy` (`app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt:62`), which accepts `http` and `https` only (`app/src/main/kotlin/com/phairplay/util/FetchableUrlPolicy.kt:57`). The new `DlnaMediaUriAttackTest` (6 methods) proves that a `file://` URI is refused and never reaches the playback controller. What is left of SEC-04 is a **written owner decision** about the two residual points — a public address is accepted deliberately, and a host name is not resolved — and that belongs to RB-13, not to a code change |
| RB-11 | **Decide the cleartext / network security configuration** | R-05, SEC-10 | high | medium (a `network_security_config.xml` + the manifest attribute) | — | open | Two options: (a) a network security configuration that permits cleartext **only** for private LAN ranges — the right fix for the DLNA media path, the GENA `NOTIFY` and the DACP reverse channel; (b) document the DLNA media path as `https`-only. Option (a) is recommended: without it the "the TV fetches the media" promise (AC-M12.19) does not work on API 28+. The configuration does not exist today — `app/src/main/res/xml/` holds only `locales_config.xml`. |
| RB-12 | **Accept the GENA callback residual for v1.0.0** | R-06, SEC-09 | medium | low (a written sentence) | — | open (accepted) | The check-and-connect window needs a cooperating DNS server inside the LAN. Host names are refused outright, which closes the common case. The decision is to ship with the documented residual and to connect to the pinned address after v1.0.0. The sentence belongs in the release notes. |
| RB-13 | **Write the known limitations into the release notes of the stable release** | R-03, R-05, R-12, R-26 | high | low | RB-11 | open | Four sentences that must appear verbatim in the `v1.0.0` notes: the AirPlay stack has never been validated on a device; the Miracast stack is discovery and control plane only; the DLNA media path depends on the cleartext decision and accepts a public address as well as an un-resolved host name; and no telemetry exists — the only component that talks beyond the LAN is the Google Cast SDK, and only on the Google TV flavour. |

---

## Tier 3 — Compliance gaps in the repository

| ID | Maßnahme | Quelle | Schweregrad | Aufwand | Abhängigkeit | Status | Vorgeschlagener Umsetzungsschritt |
|---|---|---|---|---|---|---|---|
| RB-14 | **Wire the native library check into CI and into the release pipeline** | R-24, compliance matrix rows "64-bit" and "16 KB page size" | medium | low (the check exists; only the wiring is missing) | — | open | The check was written concurrently: `scripts/check-native-libs.sh` verifies the ABI list, the **ELF alignment of every LOAD segment of every 64-bit library**, the ZIP alignment (every `lib/**.so` uncompressed on a 16 KB boundary) and the ABIs against `app/build.gradle.kts`, with a self-test in `scripts/test-check-native-libs.sh`. **But `grep -rn "check-native-libs" .github/ scripts/release/` → 0 matches** — it runs neither in the CI job `android-build` nor in `scripts/release/verify.sh`, so a violation cannot turn the build red. Add it to both and let it abort |
| RB-15 | **Confirm the three open items of the Data Safety draft with the owner** | `docs/compliance/DATA_SAFETY_DRAFT.md` | high | low (a conversation) | — | open | Three questions: (a) is the Google Cast receiver shipped with a **registered** Application ID, or does the app stay in the `ERROR` state in the store build? (b) is the receiver enabled by default? (c) is the Miracast claim removed from the listing (RB-09)? The Data Safety form must not be filled in before these are answered. |
| RB-16 | **Update the Google Cast SDK** from 20.0.0 | R-25 | low | medium (a version bump + a build + a device test of the Cast path) | RB-01 (the Cast path has to be re-tested afterwards) | open | `gradle/libs.versions.toml` pins `play-services-cast-tv = "20.0.0"` (2021). A new Cast Application ID is registered in 2026 with a five-year-old SDK. Update, then re-run the Cast device case (DT-07) — the graceful-degradation path is what the current version was proven against. |
| RB-17 | **Bring the 38 excluded test methods back into CI** | R-13 | medium | medium (JVM-safe stubs or a device run) | RB-01 (a device run needs the hardware) | open | The six excluded classes are excluded because they call JNI (`test-runner/build.gradle.kts`, `exclude` block). Either give them Robolectric-safe stubs so they run in the JVM suite, or move them into the instrumented test source set and run them on the television from RB-01. Until then, "592 tests" does not mean "every test in the repository". |
| RB-18 | **Resolve the 400 vs. 550 line rule** | R-08 | low | low (a text change or one flag) | — | open | `docs/CONTRIBUTING.md` and NFR-23 say "no source file over 400 lines"; the script warns at 400 and fails at 550; the CI call passes neither `--strict` nor a limit (`.github/workflows/ci.yml:51-52`). Either add `--strict` to the CI call or rewrite both texts to the soft/hard wording. The tree satisfies both limits today. |
| RB-19 | **Close the two known gaps of the i18n check** | R-09 | low | medium (a JVM test) | — | open | The placeholder comparison is a sequence comparison and `<plurals>` / `<string-array>` are not compared at all. A JVM test that compares the three resource folders (`values/`, `values-de/`, `values-fr/`) would make the completeness part of the JVM gate. |
| RB-20 | **Back up the release keystore outside the team-controlled storage** | R-10 | high | low (a copy plus a written confirmation) | — | open | `~/.config/phairplay/phairplay-release.jks` holds the key that signs every release. A lost key means that the installed apps can no longer be upgraded. The backup must live somewhere the team does not control, and the confirmation belongs in the release checklist. |
| RB-26 | **Land the parallel tracks' changes and get the five gates green again** | R-07, R-28, readiness A20 | critical | low (the work is already written; only the landing and the re-measurement are missing) | — | open | Measured at 2026-11-05 16:10: the tree stands at `ee49f8e` with uncommitted changes in the production code, `docs/spec/` and `docs/spec/archive/`. `check-privacy-policy.sh` is red ("the privacy policy (2026-11-02) is older than the manifest (2026-11-05)") and `check-docs-links.sh` reports 31 broken links in `docs/spec/archive/`. `check-file-size.sh` warns about `DiagnosticsFragment.kt` (407 lines). `scripts/release.sh` refuses to publish from a dirty tree (`scripts/release.sh:194-199`), so the tooling stops the release — but the privacy policy has to be touched up to the manifest date and the archived links have to be repaired before the upload |

---

## Tier 4 — Release mechanics and consistency

| ID | Maßnahme | Quelle | Schweregrad | Aufwand | Abhängigkeit | Status | Vorgeschlagener Umsetzungsschritt |
|---|---|---|---|---|---|---|---|
| RB-21 | **Bump the version to `1.0.0` and write the changelog entry** | R-11, readiness B7 | medium | low | RB-13 (the notes must be written first) | open | `versionCode = 11`, `versionName = "1.0.0"` in `app/build.gradle.kts`. `scripts/release.sh` bumps it itself and warns when `CHANGELOG.md` has no entry for the version — the entry has to exist before the tag is pushed. |
| RB-22 | **Decide the Fire TV store channel** | readiness D5 | medium | low (a decision) / high (the submission) | — | open | Amazon Appstore (the native channel on Fire TV, needs an Amazon developer account and its own review) or a second Google Play listing for `com.phairplay.firetv` (reaches only the Fire TV devices that carry Google Play). The recommendation is the Amazon Appstore plus the GitHub APK for the early adopters. |
| RB-23 | **Produce the Fire TV store assets** | readiness D9 | low | low | RB-22 | open | The assets in `docs/playstore/assets/` are a TV banner and TV screenshots. An Amazon Appstore listing needs its own icon set (512 × 512 minimum) and its own screenshot set. |
| RB-24 | **Bring the stale documents back in step with the code** | consistency check, `docs/spec/PROJECT_PLAN.md` | medium | low (text only) | — | open | The milestone status table in `docs/spec/PROJECT_PLAN.md` still carries 549 tests, 80 i18n keys, 15 device test cases and the version `1.0.0-beta.3`. The values measured on 2026-11-05 are 592 tests at the release `v1.0.0-alpha.7` (665 `@Test` methods in the tree of 16:10, of which 38 are excluded), 129 i18n keys, 16 device test cases and the version `v1.0.0-alpha.7`. The file belongs to the spec track, so the correction is a request to that track, not an edit from here |
| RB-25 | **State the signature difference between the Play and the GitHub build in a user-facing place** | R-19, readiness C7 | medium | low | RB-08 | open | The README and the release notes should tell a user who installed the GitHub APK that the Play build replaces it only after an uninstall, because the two carry different certificates. |

---

## What is deliberately not in this backlog

* **The 12 closed-test testers.** Not engineering work. It is the longest
  pole on the production track and belongs into the owner's task list — see
  [`RELEASE_READINESS.md`](RELEASE_READINESS.md) §7, order 2.
* **The hardware.** The procurement is an owner action. Once the hardware
  exists, RB-01 and RB-03 become ordinary work.
* **The Miracast media path.** Excluded by scope in ADR-001 and ADR-004, not
  by omission.
* **The FU-A buffer, the image decoder and the arbitrary-URL fetch on the
  AirPlay path.** Closed and measured (SEC-01, SEC-02, SEC-03) — they are
  documented in the security audit, not here.
