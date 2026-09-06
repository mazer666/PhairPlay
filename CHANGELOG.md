# Changelog

All notable changes to PhairPlay will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- DLNA / UPnP MediaRenderer: Windows "Cast to Device", BubbleUPnP and VLC can play video, music and
  photos on the TV. Hand-rolled SSDP/SOAP/GENA stack, MediaPlayer playback, third protocol card and
  settings toggle, TV-remote transport keys. See `docs/decisions/ADR-005-dlna-hand-rolled-upnp.md`.
- Changing the display name now restarts the receivers so the AirPlay and DLNA names update immediately.

### Fixed
- Music.app / iTunes audio-only sessions no longer drop within seconds: album artwork arrives as a
  ~430 KB `SET_PARAMETER` body, which the 64 KB control-message limit rejected, closing the
  connection. Artwork now has its own 4 MB limit (`RtspRequestReader`).
- Native crash (SIGSEGV in `ALACDecoder::Decode`) when a muted, wrong-key audio stream kept being
  fed to libalac — the legacy `AudioPlayer` now stops decoding once the stream is muted.
- `ANNOUNCE` with an `rsaaeskey` that cannot be RSA-recovered is rejected with RTSP 500 instead of
  playing a silent stream.
- FairPlay fp-setup phase-2 reply uses the fixed `FPLY 03 01 04 …` header for v2 and v3 senders,
  matching the reference implementation (previously the version byte was echoed).
- Choppy macOS system-audio output (AirPlay 2 realtime ALAC): the realtime audio player started with
  an empty, minimum-size AudioTrack and never got ahead of the sender, so any scheduling hiccup or
  Wi-Fi jitter burst underran it. Audio-only streams now pre-roll ~200 ms before playback, the track
  buffer is doubled, and the playback thread runs at urgent-audio priority. Mirroring audio keeps
  immediate playback so lip-sync is unchanged.

### Changed
- `_raop._tcp` TXT record moved to `RaopTxtRecord` (pure Kotlin, unit-tested) and extended with the
  AirPlay 2 keys UxPlay publishes (`ft`, `pk`, `sf`, `vv`, `txtvers`, `ch`, `sr`, `ss`); `pk` is
  also advertised on `_airplay._tcp`.

### Removed
- Miracast receiver (Wi-Fi Direct advertisement + WFD RTSP control plane), its settings toggle, home
  card, strings and the `CHANGE_WIFI_STATE` / location / `NEARBY_WIFI_DEVICES` permissions. A sideloaded
  Android app cannot set the Wi-Fi Display information element, so no sender could ever discover it —
  see `docs/decisions/ADR-004-miracast-removed.md`. Windows senders will be served by DLNA.

### Known issue
- Music.app audio-only from macOS connects and shows now-playing metadata but stays silent: Music
  uses the AirPlay 1 flow with FairPlay v2 key wrapping, which no open-source implementation can
  decrypt. macOS system-audio output (AirPlay 2 realtime, FairPlay v3) plays correctly. The fix is
  AirPlay 2 buffered audio (type 103), tracked as future work.

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
