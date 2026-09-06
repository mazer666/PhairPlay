# Miracast Receiver Removal — Design

**Date:** 2026-09-06
**Status:** Approved (Jeremy, 2026-09-06)
**Sub-project:** 2 of 3 (AirPlay audio-only fix → **Miracast removal** → DLNA MediaRenderer)

## Problem

PhairPlay ships a `MiracastReceiver` that advertises a `_wfd._tcp` DNS-SD record over Wi-Fi Direct and
answers WFD RTSP on port 7236. No Miracast sender can ever reach it: senders (Windows, Android, and even
Microsoft's "Miracast over Infrastructure") discover sinks from the **Wi-Fi Display information element**
in P2P beacons/probe responses. On Android that element is set only by `WifiP2pManager.setWfdInfo`, a
hidden system API guarded by the signature permission `CONFIGURE_WIFI_DISPLAY`. A sideloaded app cannot
call it on any retail TV. The code is therefore dead weight that also drags in four permissions
(`CHANGE_WIFI_STATE`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES`) and a
misleading "in progress" status in the README.

Windows senders will be served by DLNA (sub-project 3), which Windows exposes as "Cast to Device".

## Decision

Remove the Miracast receiver and every reference to it; record the platform limitation in ADR-004 so it
is not re-attempted without root/system privileges.

## Scope

**Delete**
- `app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt` (and the now-empty package dir)
- `app/src/test/kotlin/com/phairplay/miracast/MiracastReceiverTest.kt`
- `app/src/main/res/drawable/ic_miracast.xml`

**Code**
- `PhairPlayService`: drop the receiver field, `miracastState` flow, `startMiracast()`, stop/reset lines.
- `ServiceState.kt`: `Protocol` enum becomes `AIRPLAY, CAST`; comments updated.
- `AppSettings` / `SettingsRepository`: drop `miracastEnabled` and its DataStore key (an existing stored
  key is simply ignored — no migration needed).
- `HomeFragment` + `fragment_home.xml`: two protocol cards (AirPlay, Cast).
- `SettingsFragment` + `fragment_settings.xml`: drop the Miracast toggle row.
- Strings (en/de/fr) and the `protocol_miracast` colour: remove Miracast entries; reword the notification
  channel description to "AirPlay/Cast".
- `AndroidManifest.xml`: remove the four Wi-Fi Direct/location permissions, the two location
  `uses-feature` entries and the Miracast lines of the permission comment. Nothing else uses them
  (verified by grep across `MainActivity`, `NetworkUtils`, receivers).
- `tools/collect-device-logs.sh`: drop the `MiracastReceiver:V` tag.
- Tests: `AppSettingsTest`, `ServiceStateTest` updated for two protocols.

**Docs**
- New `docs/decisions/ADR-004-miracast-removed.md` (context, evidence, decision, consequences).
- `ADR-001`: status note pointing at ADR-004. `ADR-002`: tree/wording.
- `README.md`: status paragraph, features list, "does not do", known limitations.
- `docs/spec/REQUIREMENTS.md`: FR-02 removed, FR-04 reworded, §1.3 replaced by a removal note, settings
  table row removed, out-of-scope table gains "Miracast (removed, ADR-004)" and DLNA becomes "Planned".
- `docs/spec/PROJECT_PLAN.md`: phase order, status rows, Phase 6 section replaced by a removal note,
  risk-register rows removed.
- `docs/spec/TECHNICAL_SPEC.md`: architecture diagram, component table row, HDCP section, codec-matrix
  columns.
- `docs/TESTING.md`, `docs/guides/TROUBLESHOOTING.md`, `docs/guides/INSTALLATION.md`: Miracast mentions.
- `CHANGELOG.md`: `### Removed` entry under `[Unreleased]`.

## Non-goals

- Touching Google Cast (stays as-is, still gated on a Cast app ID).
- Adding DLNA (sub-project 3). The freed third card slot stays empty until then.
- Any change to AirPlay code.

## Testing

- JVM: `./gradlew :test-runner:test` — 237 − 5 (MiracastReceiverTest) − 1 (Protocol MIRACAST) = **230** (the deleted `anyProtocolEnabled … only Miracast` case makes it 7, not 6)
  tests, 0 failures. `AppSettingsTest` keeps its `anyProtocolEnabled` cases with two flags.
- Lint (`warningsAsErrors`) + both debug APKs: `UnusedResources` is disabled in lint config, but every
  Miracast string/colour/drawable is removed anyway so nothing dangles.
- On-device: install the googletv debug APK on the Pi; home screen shows two cards; Settings shows two
  protocol toggles; AirPlay still advertises and mirrors.

## Acceptance criteria

1. `grep -ri miracast app/ tools/ .github/` returns nothing; in `docs/` and `README.md` the only hits are
   ADR-004, the changelog "Removed" entry, and explicit "removed — see ADR-004" notes.
2. JVM suite 230/230; lint and both debug APKs green.
3. App runs on the Pi with two protocol cards; AirPlay mirroring unaffected.
