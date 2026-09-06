# Miracast Receiver Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Remove the unreachable Miracast receiver, its UI, settings, permissions and documentation, and record why in ADR-004.

**Architecture:** Pure removal. The service keeps its two remaining protocol flows (AirPlay, Cast); `Protocol` shrinks to two values; the home screen shows two cards. No behaviour of AirPlay or Cast changes.

**Tech Stack:** Kotlin/Android (View-based UI), Gradle, JUnit 4 via `:test-runner`, ADB to the Pi 4 at `192.168.1.185:5555`.

**Spec:** `docs/superpowers/specs/2026-09-06-miracast-removal-design.md`

---

## Ground rules

- Git is human-managed: no git writes; each task ends with a suggested commit message for Jeremy.
- Deletions in Task 1 were explicitly approved by Jeremy on 2026-09-06.
- `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk` on every Gradle call; one command per shell call.

## File structure

**Deleted:** `app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt`, `app/src/test/kotlin/com/phairplay/miracast/MiracastReceiverTest.kt`, `app/src/main/res/drawable/ic_miracast.xml`.

**Modified (code):** `service/PhairPlayService.kt`, `service/ServiceState.kt`, `settings/AppSettings.kt`, `settings/SettingsRepository.kt`, `ui/HomeFragment.kt`, `ui/SettingsFragment.kt`, `res/layout/fragment_home.xml`, `res/layout/fragment_settings.xml`, `res/layout/card_protocol_status.xml` (comment), `res/values/strings.xml`, `res/values-de/strings.xml`, `res/values-fr/strings.xml`, `res/values/colors.xml`, `AndroidManifest.xml`, `tools/collect-device-logs.sh`, tests `AppSettingsTest.kt`, `ServiceStateTest.kt`.

**Modified (docs):** `README.md`, `CHANGELOG.md`, `docs/decisions/ADR-001…`, `ADR-002…`, new `ADR-004-miracast-removed.md`, `docs/spec/REQUIREMENTS.md`, `PROJECT_PLAN.md`, `TECHNICAL_SPEC.md`, `docs/TESTING.md`, `docs/guides/TROUBLESHOOTING.md`, `INSTALLATION.md`.

---

### Task 1: Update the tests first, then delete the receiver

- [x] **Step 1: `ServiceStateTest.kt`** — delete the test `Protocol has MIRACAST value` (lines ~96-99).
- [x] **Step 2: `AppSettingsTest.kt`** — in `default settings have all protocols enabled` delete the `miracastEnabled` assertion; delete the test `anyProtocolEnabled is true when only Miracast is enabled`; in the four remaining `anyProtocolEnabled` tests remove the `miracastEnabled = …` argument.
- [x] **Step 3: Delete files** — `rm` the three files listed above and `rmdir` the two empty `miracast` directories.
- [x] **Step 4: Run** `./gradlew :test-runner:test` → **BUILD SUCCESSFUL, 230 tests** (237 − 5 deleted `MiracastReceiverTest` − 1 deleted `Protocol` test). The JVM module still compiles because `PhairPlayService` (the only remaining reference to `MiracastReceiver`) is excluded from the test-runner, and `AppSettings.miracastEnabled` still exists until Task 2.
- [x] **Step 5: Hand-off** — `refactor: remove the unreachable Miracast receiver and its tests`.

### Task 2: Service, state and settings

- [x] **Step 1: `ServiceState.kt`** — `enum class Protocol { AIRPLAY, MIRACAST, CAST }` → `enum class Protocol { AIRPLAY, CAST }`; `(AirPlay / Miracast / Cast)` → `(AirPlay / Cast)`; `(AirPlayReceiver, MiracastReceiver, CastReceiver)` → `(AirPlayReceiver, CastReceiver)`; "one of the three supported protocols" → "one of the supported protocols".
- [x] **Step 2: `AppSettings.kt`** — delete the `miracastEnabled` property and its KDoc (lines ~39-43); `anyProtocolEnabled` → `airPlayEnabled || castEnabled`; "If all three are disabled" → "If both are disabled".
- [x] **Step 3: `SettingsRepository.kt`** — delete the `miracastEnabled = this[Keys.MIRACAST_ENABLED] ?: true` line, the `this[Keys.MIRACAST_ENABLED] = settings.miracastEnabled` line and the `MIRACAST_ENABLED` key constant.
- [x] **Step 4: `PhairPlayService.kt`** — delete `import com.phairplay.miracast.MiracastReceiver`; the `_miracastState`/`miracastState` pair; `private var miracastReceiver: MiracastReceiver? = null`; `if (settings.miracastEnabled)  startMiracast()`; the whole `startMiracast()` function; in `stopAllReceiversInternal` the `try { miracastReceiver?.stop() } …`, `miracastReceiver = null`, `_miracastState.value = ProtocolState.DISABLED` lines; in `startReceivers` the log string `Miracast=${settings.miracastEnabled}, `; class KDoc "AirPlay/Miracast/Cast" → "AirPlay/Cast" (two places).
- [x] **Step 5: Run** `./gradlew :test-runner:test` → **230 tests, 0 failures**.
- [x] **Step 6: Hand-off** — `refactor(service): drop Miracast from service state and settings`.

### Task 3: UI, layouts, strings, colour

- [x] **Step 1: `HomeFragment.kt`** — delete `cardMiracast` declaration, `findViewById(R.id.card_miracast)`, the `setupCard(cardMiracast, …)` line, the `svc.miracastState.collectLatest` block; KDoc "all three receiver protocols (AirPlay / Miracast / Cast)" → "the receiver protocols (AirPlay / Cast)"; "(cardAirPlay, cardMiracast, or cardCast)" → "(cardAirPlay or cardCast)".
- [x] **Step 2: `fragment_home.xml`** — delete the `<!-- Miracast card -->` include block; ASCII-art header line: drop the `📡 Miracast` box.
- [x] **Step 3: `SettingsFragment.kt`** — delete `rowMiracast` declaration, `findViewById(R.id.row_miracast)`, the `configureToggleRow(rowMiracast, …)`, `setToggle(rowMiracast, …)` and `setToggleListener(rowMiracast) …` lines.
- [x] **Step 4: `fragment_settings.xml`** — delete the `row_miracast` include; header comment "AirPlay, Miracast, Cast" → "AirPlay, Cast".
- [x] **Step 5: `card_protocol_status.xml`** — comments "(AirPlay/Miracast/Cast)" → "(AirPlay/Cast)", `"AirPlay", "Miracast", "Cast"` → `"AirPlay", "Cast"`.
- [x] **Step 6: strings** — `values/strings.xml`: delete `protocol_miracast`, `setting_miracast_enabled`, `setting_miracast_subtitle`, `error_wifi_p2p_unavailable`, `content_desc_miracast_icon`; `notification_channel_description` → "Persistent notification for the AirPlay/Cast receiver service". `values-de`: delete `setting_miracast_enabled`, `setting_miracast_subtitle`, `error_wifi_p2p_unavailable`; channel description → "Benachrichtigung für den AirPlay/Cast-Empfänger-Dienst". `values-fr`: delete `setting_miracast_enabled`.
- [x] **Step 7: `colors.xml`** — delete the `protocol_miracast` colour and its comment.
- [x] **Step 8: Build** `./gradlew :app:assembleGoogletvDebug` → BUILD SUCCESSFUL (catches any missed `R.id`/`R.string` reference).
- [x] **Step 9: Hand-off** — `refactor(ui): two protocol cards and toggles (AirPlay, Cast)`.

### Task 4: Manifest permissions and tooling

- [x] **Step 1: `AndroidManifest.xml`** — delete the `<!-- Miracast (Wi-Fi Direct / Wi-Fi P2P) -->` block (four `uses-permission` lines), the two `uses-feature` entries for `android.hardware.location.gps` / `.network`, and the three Miracast lines of the header comment (`CHANGE_WIFI_STATE`, `ACCESS_FINE_LOCATION`; keep the rest).
- [x] **Step 2: `tools/collect-device-logs.sh`** — delete the `'MiracastReceiver:V' \` line.
- [x] **Step 3: Lint** `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug` → BUILD SUCCESSFUL.
- [x] **Step 4: Hand-off** — `chore: drop Wi-Fi Direct and location permissions no longer needed`.

### Task 5: Documentation and ADR-004

- [x] **Step 1: Create `docs/decisions/ADR-004-miracast-removed.md`** (format of ADR-003): Context (what was built; how Miracast discovery actually works; `setWfdInfo` / `CONFIGURE_WIFI_DISPLAY`; MS-MICE still needs the WFD IE — cite the MS-MICE Initialization page and lazycast README), Decision (remove; serve Windows via DLNA), Consequences (permissions dropped; two cards; re-attempt only viable as a system/root app), Alternatives (root-only feature; leave dead code).
- [x] **Step 2: `ADR-001`** — add under Status: `**Amended:** Miracast removed 2026-09-06 — see ADR-004.`; in Consequences change the Miracast permissions bullet to past tense with the ADR-004 pointer.
- [x] **Step 3: `ADR-002`** — "AirPlay/Miracast/Cast receivers" → "AirPlay/Cast receivers"; remove the `├── MiracastReceiver` tree line.
- [x] **Step 4: `README.md`** — line 25 → "Google Cast receiver lifecycle is implemented (needs a registered Cast app ID). Miracast was removed: a sideloaded Android app cannot be discovered as a Miracast sink (see ADR-004); Windows senders will be served by DLNA."; delete the features bullet "Miracast Wi-Fi Direct / WFD advertisement…"; "does not do" bullet → "**Miracast** — impossible for a sideloaded app (ADR-004); **Cast media playback** — needs a Cast app ID"; delete the Known-Limitations Miracast bullet.
- [x] **Step 5: `REQUIREMENTS.md`** — delete FR-02; FR-04 "All three services" → "Both services"; replace §1.3 (heading through the codec table) with "### 1.3 Miracast Receiver — removed\n\nRemoved 2026-09-06; see `docs/decisions/ADR-004-miracast-removed.md`."; delete the "Miracast enabled" settings row; out-of-scope table: add `| Miracast (WFD) | Sideloaded apps cannot set the WFD IE | Removed — ADR-004 |` and change the DLNA row to `| DLNA / UPnP MediaRenderer | Windows "Cast to Device", BubbleUPnP/VLC | Planned (sub-project 3) |`; also update FR-33 codec bullet and NFR-19 / sender-support table rows that mention Miracast (delete them).
- [x] **Step 6: `PROJECT_PLAN.md`** — status row M6 → `| 6 | M6 – Miracast | ❌ Removed | Sideloaded apps cannot be Miracast sinks — ADR-004 |`; M2 note drop "Miracast WFD advertising added;"; replace the Phase 6 section body with a one-line removal note; milestone table M6 row → Removed; delete the three Miracast/Wi-Fi P2P/MPEG-TS/HDCP risk rows.
- [x] **Step 7: `TECHNICAL_SPEC.md`** — diagram: remove the `MiracastReceiver` column lines (161-171, 183, 190); delete the component-table row; delete "§11.1 HDCP (Miracast)"; drop the "Miracast (WFD)" column from the three codec/container tables and the resolution table row.
- [x] **Step 8: `TESTING.md`, `TROUBLESHOOTING.md`, `INSTALLATION.md`** — remove "Miracast" from the section title and delete "Cause 5"; "All services (AirPlay, Miracast, Cast)" → "(AirPlay, Cast)"; comment "Google TV with AirPlay/Miracast only" → "AirPlay only".
- [x] **Step 9: `CHANGELOG.md`** — under `[Unreleased]` add `### Removed` with the Miracast entry pointing at ADR-004.
- [x] **Step 10: Verify** `grep -rin miracast app tools .github` → empty; `grep -rin miracast README.md docs CHANGELOG.md` → only ADR-004, the changelog entry, and explicit removal notes.
- [x] **Step 11: Hand-off** — `docs: record Miracast removal (ADR-004) and update specs, plan, guides`.

### Task 6: CI-equivalent and on-device check

- [x] **Step 1** `./gradlew :test-runner:test :app:lintGoogletvDebug :app:lintFiretvDebug :app:assembleGoogletvDebug :app:assembleFiretvDebug` → BUILD SUCCESSFUL, 230 tests.
- [x] **Step 2** Install the googletv debug APK on the Pi, launch, confirm via logcat that AirPlay advertises; ask Jeremy to confirm the home screen shows two cards and mirroring still works.
- [x] **Step 3** `git status --short` summary for Jeremy.
