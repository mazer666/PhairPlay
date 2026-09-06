# ADR-004: Miracast Receiver Removed

**Date:** 2026-09-06
**Status:** Accepted (supersedes the Miracast part of ADR-001)

---

## Context

ADR-001 planned a Miracast (Wi-Fi Display) sink alongside AirPlay and Cast. By v1.0.0-beta.1 the code
advertised a `_wfd._tcp` DNS-SD record over Wi-Fi Direct and answered WFD RTSP on port 7236, with
MPEG-TS demux and playback "pending".

That work could never be reached by a real sender:

- Miracast sources (Windows, Android, Samsung Smart View) discover sinks from the **Wi-Fi Display
  information element (WFD IE)** carried in Wi-Fi Direct beacons and probe responses — not from a DNS-SD
  service record.
- On Android the WFD IE is set only through `WifiP2pManager.setWfdInfo(WifiP2pWfdInfo)`, a hidden system
  API guarded by the signature-level permission `android.permission.CONFIGURE_WIFI_DISPLAY`. A sideloaded
  app cannot obtain it on any retail Android TV or Fire TV.
- Microsoft's "Miracast over Infrastructure" (MS-MICE) moves the *stream* onto the LAN, but its
  Initialization section still requires the sink to be "discoverable by Beacons and/or Probe Requests as in
  standard Miracast", with a Microsoft vendor extension in the WSC IE — i.e. the same WFD IE. The mDNS
  `_display._tcp` registration is only for host-name resolution after discovery. The Raspberry Pi sink
  project *lazycast* documents the same constraint: "in the device discovery phase, it still requires a
  wifi p2p device to broadcast beacon and probe response frames".

So no Windows or Android sender will ever list PhairPlay as a Miracast receiver unless the app runs with
system privileges (root / platform signature). The existing control-plane code was dead weight and its
"in progress" status in the README was misleading. It also required four permissions
(`CHANGE_WIFI_STATE`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES`) that
nothing else uses.

## Decision

Remove the Miracast receiver, its settings toggle, home-screen card, strings, colour, icon, tests and the
four permissions. Serve Windows senders through **DLNA / UPnP MediaRenderer** instead (Windows exposes it
as "Cast to Device"); that is sub-project 3.

## Consequences

- Home screen shows two protocol cards (AirPlay, Cast); the third slot is reserved for DLNA.
- Smaller permission surface: no location or Wi-Fi-state permissions.
- `AppSettings.miracastEnabled` and its DataStore key are gone; a previously stored key is ignored.
- Miracast must not be re-attempted as a normal app. It is only viable as a system app on a custom ROM
  (e.g. LineageOS with platform signing), which is out of scope for a sideloaded APK.

## Alternatives considered

1. **Keep it as a root/system-only feature** — the full sink (WFD IE, RTSP M1–M7, MPEG-TS demux,
   H.264 + LPCM) for a tiny audience that cannot be tested on retail hardware. Rejected.
2. **Leave the dead code and fix the docs** — keeps permissions and UI for a feature that can never
   work. Rejected.

## References

- MS-MICE Initialization: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-mice/f6b2c820-ff11-4593-8b87-614868401a60
- MS-MICE Overview: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-mice/ab6341b7-4fc7-41fd-a74d-3fe023455482
- `WifiP2pManager.setWfdInfo` (system API): https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager
- lazycast README: https://github.com/homeworkc/lazycast
