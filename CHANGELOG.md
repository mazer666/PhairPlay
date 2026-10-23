# Changelog

All notable changes to PhairPlay will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0-beta.3] - 2026-10-24

### Added

**UPnP / DLNA MediaRenderer — discovery and control plane (ADR-004 phases D1 + D2)**
- The TV appears as a DLNA MediaRenderer in Windows "Cast to Device", VLC and mobile UPnP controllers.
- SSDP discovery (`SsdpAdvertiser`): advertises `urn:schemas-upnp-org:device:MediaRenderer:1` and answers `M-SEARCH` queries.
- Device description server (`DlnaDescriptionServer`): serves the UPnP device descriptor and the SCPD over a bounded, non-blocking HTTP server (2 s socket timeout, 16 KB header budget, 64 KB body budget, 8 KB per-line limit).
- SOAP dispatcher (`DlnaSoapDispatcher`): `ConnectionManager:1`, `AVTransport:1` and `RenderingControl:1` actions, parsed with a hardened XML reader (`FEATURE_SECURE_PROCESSING`, doctype declarations and external entities disabled).
- GENA eventing (`GenaEventManager`): `LastChange` notifications; callback URLs are restricted to private IPv4 addresses inside the local subnet (SSRF hardening).
- `MediaCapabilities`: reports what the device can decode, so the renderer advertises a sane protocol list.
- Settings toggle "UPnP / DLNA Receiver" and a status card on the Home screen (en / de / fr).
- Protocol isolation per ADR-004: all DLNA logic lives in `com.phairplay.dlna`; the AirPlay 2 stack is untouched. DLNA is an additional, independently switchable protocol — it does not replace Miracast or Google Cast.
- Media playback (phase D3) is not part of this release.

**Bring the app forward when a mirroring session starts (PR #19)**
- A mirroring session that begins while the app is in the background now opens the streaming screen instead of showing a black surface until the app is opened by hand. Requires the optional "Display over other apps" permission; without it the behaviour is unchanged.
- Audio-only sessions are deliberately **not** brought to the front — a podcast or Apple Music stream no longer interrupts whatever is on screen.

**Mirroring recovery across resolution changes (PR #17)**
- The H.264 SPS parser accepts raw and Annex-B input through one shared implementation; the SPS prefix is no longer misread as profile fields.
- A missed codec input no longer discards the only IDR available after a reconfiguration — the decoder retries once on a codec rebuilt with the cached SPS/PPS.
- The display uses a fixed-size `TextureView` with matrix-based aspect fitting, so a resolution change no longer freezes the picture.
- A diagnostics overlay reports received / submitted / output / render-callback / texture-update counters, the display refresh rate and the recovery attempts.

### Fixed
- **AirPlay did not appear in the sender's device list (issue #18)**: four separate defects made the receiver invisible — protocol switches and the device name in Settings were only persisted and never applied to the running service; a failed mDNS registration was never retried; `FAILURE_ALREADY_ACTIVE` was treated as a success; and the Home screen reported "Disabled — Enable in Settings" for a receiver that was enabled and advertising.
- **mDNS registration could not recover**: the two mDNS services are now registered independently with a bounded retry (1 s / 3 s / 6 s), a failed service no longer tears down its sibling, and a permanently failing advertisement ends in an explicit error state instead of silently disappearing from every AirPlay picker.
- **mDNS churn on network transitions**: re-advertisement now happens only after the last network was actually lost. Previously the first `NetworkCallback.onAvailable` raced the initial registration and unregistered it again, which is what produced the platform's "Exceeded max outstanding requests" refusal.
- **Misleading status text**: a protocol card that is disabled because the service is stopped now says "Start the service to advertise" instead of pointing at a setting that was already correct.
- **Advertised device names**: the name is sanitised before registration (control characters removed, capped at the DNS-SD limit of 63 characters). A name the platform's mDNS daemon rejects used to take the whole AirPlay receiver down.

### Security
- The H.264 SPS parser computes Exp-Golomb codes in 64-bit arithmetic and rejects values above the plausible range, so a crafted SPS can no longer overflow into a bogus picture size.
- The mirroring frame queue is now bounded by bytes (64 MB) as well as by item count (90). Previously 90 frames of the permitted 8 MB each could keep ~720 MB alive and OOM the receiver.
- The DLNA description server bounds the length of a single request and header line and drains a rejected request before closing, so a client cannot make it buffer an unbounded line.

---
## [1.0.0-beta.2] - 2026-10-09

### Fixed
- **Screen awake during playback**: Prevent TV screensaver / sleep from activating during active AirPlay mirroring or audio streaming (`FLAG_KEEP_SCREEN_ON`). Fixes session teardown after ~19 minutes on Fire TV and Google TV (PR #13).
- **mDNS recovery after standby / network drop**: Automatically re-advertise AirPlay via mDNS when network connectivity returns after device standby or Wi-Fi reconnect (PR #13).
- **Wi-Fi multicast packet loss**: Acquire `WifiManager.MulticastLock` while advertising to ensure sender discovery packets are received reliably in power-saving mode (PR #13).
- **iOS 17+ AirPlay 2 mirroring SETUP limit**: Raised RTSP message size cap (`MAX_MESSAGE_BYTES`) from 64 KB to 1 MB, resolving `Request body too large` errors during binary plist negotiation on newer iOS senders (PR #11).
- **IPv4 wildcard socket binding**: Explicitly bind RTSP, event, audio, and mirror sockets to `0.0.0.0` to ensure compatibility with Android TV network stacks (e.g. Philips/Sony) where dual-stack IPv6 sockets dropped IPv4 SYNs (PR #15).

---

## [1.0.0-beta.1] - 2026-06-14

### Added

**AirPlay 2 receiver — full stack**
- Screen mirroring (H.264) from macOS 12+ and iOS/iPadOS 16+ via RTSP on port 7000
- FairPlay session decryption: fp-setup v2 (RAOP audio) and v3 (mirroring/Safari) via native libplayfair (JNI); legacy rsaaeskey RSA-OAEP recovery for AirPort Express compatibility
- HomeKit-style pairing: Ed25519 identity, X25519 ECDH key agreement, controller key persistence (`PairingStore`), failed-attempt lockout
- Legacy SRP-6a PIN pairing with on-screen PIN entry screen (`LegacyPairSetupPin`, `PinScreen`)
- `MirrorStreamServer` + `MirrorCrypto` — interleaved RTP reassembly, AES-128-CTR stream decryption (keystream always advanced to prevent reuse)
- `AudioStreamServer` — mirror realtime audio (type 96): UDP RTP, AES-128-CBC, AAC-ELD/AAC-LC decode via MediaCodec, RAOP retransmit, AudioTrack with volume
- `AlacDecoder` + native libalac — RAOP/SDP audio path: AES-128-CBC (per-packet IV) + Apple's ALAC decoder; decode-health mute guard (wrong key → silence, not static)
- `BufferedAudioServer` — AirPlay 2 buffered audio (type 103) accepted and instrumented
- `AirPlayVideoPlayer` — AirPlay video URL mode (`/play`) + transport controls (play/pause/scrub/stop)
- `NowPlayingInfo` (DMAP parser) + album artwork → `NowPlayingScreen` overlay
- `DacpClient` — `_dacp._tcp` discovery + reverse transport control from TV remote to sender (play/pause/skip/volume)
- `AirPlayNtpClient` — Apple NTP for A/V synchronisation
- `InfoResponder` — `GET /info` capability advertisement (plist)
- `PlistCodec` — Apple binary plist encode/decode
- `RaopRsa` — legacy rsaaeskey recovery (RSA-OAEP, AirPort Express key)
- `StreamStats` — per-session RTP statistics (packet count, duplicates, queue drops)
- `Base64Util` — pure-JVM Base64 so SDP parsing is testable without Android framework
- `SdpParser` — extended: codec/encryption/channel/rate parsing for all AirPlay audio types
- Aspect-fit (letterbox/pillarbox) video rendering with black background in `StreamingScreen`
- Real PNG bitmap launcher icon and TV banner (replaces placeholder XML)
- Mirror Audio toggle and PIN-auth toggle in Settings
- Receiver survives app restart/relaunch; mirroring and audio stop cleanly on app exit

**Native layer**
- CMake build for all ABIs (armeabi-v7a, arm64-v8a, x86, x86_64)
- `fairplay_jni.c` — JNI bridge for `playfair_decrypt` with full null/length/OOM validation
- Apple ALAC decoder (C++, vendored) + JNI bridge (`alac_jni.cpp`)
- Reverse-engineered FairPlay (C, `playfair/`) compiled for all ABIs
- Strict-aliasing fix in `modified_md5.c` (union type-punning) and `sap_hash.c` (memcpy + union)

**Test suite**
- 247 unit tests, 0 failures: FairPlay, RaopRsa, Base64Util, ALAC cookie, DMAP, legacy PIN SRP, audio stream server, RTSP handler, service controller
- Robolectric added for framework-dependent tests (Android Base64, Intent, etc.)

**Release infrastructure**
- `scripts/release.sh` — local release script: builds signed GoogleTV + FireTV APKs, creates git tag, publishes GitHub Release via `gh` CLI (no CI minutes consumed)
- First signed GitHub Release: [v1.0.0-beta.1](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-beta.1)

### Changed
- `VideoDecoder`: SPS/PPS-driven reinit on resolution change, self-heal on decoder error, keyframe resync after drops, decoupled network reader (bounded queue, drop-under-load), re-attach to Surface after backgrounding
- `AudioPlayer`: extended to support ALAC and new audio stream types from `AudioStreamServer`
- `RtspHandler`: extended to 700+ lines — handles all AirPlay 2 verbs (ANNOUNCE, SETUP plist+SDP, RECORD, TEARDOWN stream-scoped, GET/SET_PARAMETER, FLUSH, PAUSE, photo PUT/DELETE, `/play`, `/rate`, `/scrub`, `/stop`, `/feedback`, buffered-audio control)
- `AirPlayReceiver`: event channel socket now closed via `use {}` block (fixes file-descriptor leak)
- `SettingsFragment`: mirror audio and PIN-auth toggles added

### Fixed
- `DatagramPacket` length reset before each `receive()` call in `AudioStreamServer` — prevented packet truncation when a smaller packet arrived first
- JNI bridge (`fairplay_jni.c`) now validates input arrays for null, length, and OOM before native access — prevents out-of-bounds reads and native crashes
- Strict-aliasing UB in `modified_md5.c` and `sap_hash.c` — union + memcpy replaces direct `uint32_t*` cast of `unsigned char*`
- `Cipher.getInstance()` moved out of hot path in `AudioStreamServer` (~92 allocations/s → 1 per session)

---

<!-- Format:
## [X.Y.Z] - YYYY-MM-DD

### Added
### Changed
### Fixed
### Removed
-->
