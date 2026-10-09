# ADR-004: Stepwise UPnP / DLNA MediaRenderer Integration

**Date:** 2026-10-09  
**Status:** Accepted  

---

## Context

PhairPlay was originally conceived as an AirPlay 2 receiver (macOS and iOS senders) with optional Miracast and Google Cast capabilities ([ADR-001](ADR-001-multi-protocol.md)).

While the AirPlay 2 receiver is now mature and released (`v1.0.0-beta.2`), user demand and real-world testing have highlighted the following realities regarding non-Apple streaming:
1. **Miracast Limitations on Android TV:** A sideloaded third-party Android app cannot reliably act as a Wi-Fi Display sink (receiver) on standard consumer Android TV and Fire TV hardware because `WifiP2pManager.setWfdInfo()` is a hidden system API gated by system-level permissions (`CONFIGURE_WIFI_DISPLAY`). As a result, Windows 10/11 senders generally fail to discover third-party Miracast receiver apps.
2. **Community Proposals:** A community pull request (PR #14) attempted to replace Miracast with a complete DLNA MediaRenderer. While the PR was closed due to an overwhelming monolithic patch (+12,654 lines) and lack of formal review, the underlying idea—providing a UPnP/DLNA MediaRenderer for Windows, VLC, and Android controllers—is technically sound, feasible on non-root Android TV, and highly requested.

## Decision

We will adopt **UPnP/DLNA MediaRenderer** as an official future protocol in PhairPlay, implemented through a **clean, modular, stepwise approach** rather than a single monolithic dump.

DLNA will complement AirPlay and Cast, providing native support for:
- Windows "Cast to Device" / Windows Media Player
- VLC Media Player ("Playback → Renderer")
- Popular mobile UPnP controllers (BubbleUPnP, mconnect, etc.)

## Phased Implementation Roadmap

The implementation will proceed in four distinct, reviewable, and unit-tested phases:

### Phase D1: Foundation & Discovery (SSDP + Base Receiver)
- **SSDP Discovery:** Implement a UDP multicast listener on `239.255.255.250:1900` to advertise `urn:schemas-upnp-org:device:MediaRenderer:1` and reply to `M-SEARCH` queries.
- **HTTP Device Description Server:** A lightweight, non-blocking HTTP server listening on an OS-assigned or dedicated port to serve static XML device and SCPD descriptors (`description.xml`).
- **Lifecycle & Service Integration:** Add `DlnaReceiver` orchestrator under `com.phairplay.dlna`, wire into `PhairPlayService`, add `dlnaEnabled` preference in `AppSettings`, and show a status card on `HomeFragment`.
- **Target:** The TV appears reliably in Windows, BubbleUPnP, and VLC device pickers.

### Phase D2: Control Plane & Hardened SOAP Dispatcher
- **Secure XML Processing:** Secure XML parsing configured with `XMLConstants.FEATURE_SECURE_PROCESSING`, external entity resolution disabled (XXE defense), and recursion depth limits.
- **Core Services:**
  - `ConnectionManager:1`: `GetProtocolInfo`, `GetCurrentConnectionIDs`.
  - `AVTransport:1`: State machine handling `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `GetTransportInfo`, `GetPositionInfo`.
  - `RenderingControl:1`: `GetVolume`, `SetVolume`, `GetMute`, `SetMute`.
- **Target:** Handshake and commands from UPnP control points execute and update transport state machine without errors.

### Phase D3: Media Playback Integration (Video, Audio, Photo)
- **Media Engine Wiring:** Connect `AVTransport` URI playback to the Android UI using the existing `StreamingScreen` SurfaceView and `AirPlayVideoPlayer` / MediaPlayer infrastructure.
- **Audio & Photo Mode:** Support audio-only playback and photo display (`PhotoScreen`) with strict memory/payload caps (max 20 MB image size, inSampleSize downsampling) to prevent OOM.
- **Remote Control:** Map TV remote D-pad / media keys (play, pause, stop, volume) to the DLNA state machine.
- **Target:** Full video, audio, and photo playback on screen from Windows and mobile UPnP controllers.

### Phase D4: Eventing (GENA) & Security Hardening
- **GENA Eventing:** General Event Notification Architecture for sending `LastChange` event callbacks to subscribed controllers.
- **SSRF Hardening:** Strict callback validation—GENA event notifications are strictly restricted to private IPv4 addresses within the local LAN subnet.
- **Real-Device Validation:** End-to-end testing across Windows 11, VLC, and Android devices on Google TV and Fire TV hardware.

## Consequences & Safety Guarantees

1. **Protocol Isolation:** All DLNA logic resides strictly within package `com.phairplay.dlna`. AirPlay 2 core code remains untouched.
2. **Resource Constraints:** The embedded HTTP server will use bounded buffers, strict header length limits, and connection timeouts to prevent resource exhaustion.
3. **Opt-Out & Flexibility:** DLNA can be toggled on/off in Settings independently of AirPlay and Cast.
