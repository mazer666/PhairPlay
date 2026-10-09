# PhairPlay – Project Plan

Version: 2.1
Status: Active
Date: 2026-03-23
Last Updated: 2026-05-23

---

## Phase Order

```
Phase 0 → Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5 → Phase 6 → Phase 7 → Phase 8 → Phase 9 → Phase 10 → Phase 11 → Phase 12
 Spec     Skeleton  AirPlay   AirPlay   AirPlay   AirPlay   Miracast    Cast     Stability  Fire TV   i18n      Release    DLNA
          + UI       mDNS     Handshk    Video     Audio+    Receiver  Receiver             Port      Polish               Renderer
                    +Service  +RTSP     +Photo    Opt.Codec  Full     Full
                              +Photo   +Opt.HEVC  +HEVC    Codecs    Codecs
  M0        M1       M2        M3        M4        M5        M6         M7        M8         M9       M10       M11        M12
```

## Status Overview

| Phase | Milestone | Status | Notes |
|---|---|---|---|
| 0 | M0 – Spec | ✅ Complete | All spec docs written, codec matrix added (v2.2) |
| 1 | M1 – Skeleton | ✅ Complete | UI, settings, foreground service, and both APK flavors build |
| 2 | M2 – mDNS | ✅ Complete | AirPlay mDNS + InfoResponder implemented; Miracast WFD advertising added; Cast remains registration-dependent |
| 3 | M3 – AirPlay Handshake | ✅ Complete | Full RTSP router, SDP parsing, plist codec, pairing (Ed25519/X25519 + SRP), FairPlay fp-setup, `/photo` endpoint, 247 unit tests |
| 4 | M4 – AirPlay Video | ✅ Complete | H.264 via MirrorStreamServer + MirrorCrypto (AES-128-CTR), MediaCodec with SPS-driven reinit and self-heal, aspect-fit rendering; real-device validation ongoing |
| 5 | M5 – AirPlay Audio | ✅ Complete | AAC-ELD/AAC-LC (AudioStreamServer), ALAC (AlacDecoder + libalac), AES-128-CBC, NTP sync, DACP reverse remote, NowPlayingScreen; real-device validation ongoing |
| 6 | M6 – Miracast | 🔄 Started | Wi-Fi Direct/WFD advertising and RTSP control-plane implemented; system API limitations identified (see ADR-004) |
| 7 | M7 – Google Cast | 🔄 Started | Google TV Cast Connect SDK lifecycle implemented; full testing requires registered Cast app ID |
| 8 | M8 – Stability | 🔄 In Progress | Screen awake, mDNS standby recovery & socket binding fixes integrated in v1.0.0-beta.2 |
| 9 | M9 – Fire TV | 🔄 In Progress | Signed Fire TV APK released (v1.0.0-beta.2); screensaver timeout resolved |
| 10 | M10 – i18n | 🔄 Partial | EN/DE resource structure exists; full UX string audit pending |
| 11 | M11 – Release | 🔄 Beta | v1.0.0-beta.2 signed release published on GitHub (2026-10-09) |
| 12 | M12 – DLNA | 📋 Planned | Stepwise UPnP/DLNA MediaRenderer for Windows & Android (see ADR-004) |

---

## Phase 0 – Specification ✅

**Milestone:** M0
**Status:** ✅ Complete

**Completed tasks:**
- [x] `REQUIREMENTS.md` v2.2 — functional and non-functional requirements, full codec matrix for all 3 protocols, photo/image sharing (FR-42, FR-43), FairPlay DRM evaluation
- [x] `TECHNICAL_SPEC.md` v1.2 — AirPlay protocol stack, system architecture, codec matrix (§10), photo streaming protocol (§9), DRM evaluation (§11), AirPlay feature flags
- [x] `PROJECT_PLAN.md` v2.1 — phased roadmap with status, codec-specific tasks per phase, risk register
- [x] `ACCEPTANCE_CRITERIA.md` v1.0 — verifiable criteria per milestone

**Definition of Done:** All spec documents committed, reviewed, and updated for v2.1 scope including codec matrix and photo sharing.

---

## Phase 1 – Skeleton + Service Architecture + UI

**Milestone:** M1
**Status:** ✅ Build-ready; real-device validation pending

**Goal:** App starts on both platforms. Google TV-style HomeScreen. ForegroundService running. Settings screen accessible. All three service status cards visible.

**Tasks:**
- [x] `HomeFragment.kt` — Google TV Streamer-style home screen with 3 service cards
- [x] `SettingsFragment.kt` — settings screen with toggles and text inputs
- [x] `PhairPlayService.kt` — ForegroundService with start/stop/restart
- [x] `ServiceController.kt` — start/stop/restart API
- [x] `AppSettings.kt` — settings data model
- [x] `SettingsRepository.kt` — DataStore persistence
- [x] Persistent notification with Stop/Restart actions
- [x] `res/values/strings.xml` (EN), `res/values-de/strings.xml` (DE)
- [x] Focused unit tests for service/settings state
- [ ] Full real-device UI navigation pass on Google TV and Fire TV

**Definition of Done:**
- App starts on both platforms without crash
- HomeScreen shows 3 service cards (AirPlay / Miracast / Cast) with status
- Settings screen opens and saves settings
- ForegroundService persists in background
- Notification visible with Stop/Restart buttons
- DE strings visible when device language is German

**Acceptance Criteria:** AC-1.x

---

## Phase 2 – mDNS + Network Visibility

**Milestone:** M2

**Goal:** All enabled services advertise on the network. macOS sees AirPlay. Windows sees Miracast. Chrome sees Cast.

**Tasks:**
- [x] `MdnsService.kt` — AirPlay mDNS advertisement
- [x] Miracast Wi-Fi Direct / WFD service advertisement
- [x] Google TV Cast Connect SDK lifecycle and manifest registration
- [x] `NetworkUtils.kt` — IP, MAC, UUID helpers
- [x] Settings: device name applied to implemented advertisers

**Definition of Done:**
- macOS sees PhairPlay within 3s (AC-2.1)
- Device name from Settings is shown in picker
- WifiP2p service registered (Miracast P2P)
- Service cards update when advertising starts/stops

---

## Phase 3 – AirPlay Handshake (RTSP) + Photo Endpoint

**Milestone:** M3
**Status:** ✅ Complete

**Tasks:**
- [x] `RtspHandler.kt` — full AirPlay 2 RTSP router: OPTIONS, ANNOUNCE, SETUP (plist + SDP), RECORD, TEARDOWN, GET/SET_PARAMETER, FLUSH, PAUSE, photo PUT/DELETE, `/play`, `/rate`, `/scrub`, `/stop`, `/feedback`, buffered-audio verbs
- [x] `PlistCodec.kt` — Apple binary plist encode/decode
- [x] `InfoResponder.kt` — `GET /info` capability advertisement
- [x] `SdpParser.kt` — codec/encryption/channel/rate parsing for all AirPlay audio types
- [x] `PairingSession.kt` / `PairingKeys.kt` / `PairingStore.kt` — Ed25519 identity, X25519 ECDH, controller key persistence, lockout
- [x] `LegacyPairSetupPin.kt` — SRP-6a PIN pairing + AES-GCM key exchange; `PinScreen.kt` on-screen PIN UI
- [x] `FairPlay.kt` — fp-setup phase 1/2 for v3 (mirroring/Safari) and v2 (RAOP audio)
- [x] `RaopRsa.kt` — legacy rsaaeskey recovery (RSA-OAEP, AirPort Express key)
- [x] `PhotoHandler.kt` + `PhotoScreen.kt` — `/photo` PUT/DELETE; JPEG/PNG full-screen display
- [x] AirPlay `features` bitmask in mDNS TXT record
- [x] 247 unit tests (FairPlay, RaopRsa, SRP, RTSP, pairing, plist, DMAP)
- [ ] Real macOS/iOS AirPlay handshake and photo transfer validation

**Definition of Done:** AC-3.x — full handshake from macOS without RTSP errors; photo from iOS Photos app appears on screen.

---

## Phase 4 – AirPlay Video (H.264 mandatory, H.265 optional)

**Milestone:** M4
**Status:** ✅ Complete (H.264 mandatory path; H.265 optional pending hardware check)

**Tasks:**

**Mandatory (H.264):**
- [x] `MirrorStreamServer.kt` — interleaved RTP reassembly from RTSP TCP stream (`$` framing)
- [x] `MirrorCrypto.kt` — AES-128-CTR decryption (keystream always advanced)
- [x] `VideoDecoder.kt` — MediaCodec H.264 with SPS/PPS-driven reinit on resolution change, self-heal on decoder error, keyframe resync after drops, decoupled network reader (bounded queue, drop-under-load), Surface re-attach after backgrounding
- [x] `StreamingScreen.kt` — aspect-fit (letterbox/pillarbox) SurfaceView with black background; SPS size validation

**Optional (H.265 / HEVC):**
- [ ] Runtime HEVC capability check via `MediaCodecList.findDecoderForFormat("video/hevc")`
- [ ] SDP negotiation: detect `H265/90000` in `a=rtpmap`; fall back to H.264 if HEVC unavailable
- [ ] Advertise HEVC support in AirPlay `features` bitmask only if hardware supports it

**Definition of Done:** AC-4.x — ≥25fps, ≤100ms latency on Google TV for H.264 streams. HEVC streams play if hardware is capable.

---

## Phase 5 – AirPlay Audio (full codec matrix)

**Milestone:** M5
**Status:** ✅ Complete (mandatory codecs; optional surround pending)

**Tasks:**

**Mandatory audio codecs:**
- [x] `AudioStreamServer.kt` — mirror realtime audio (type 96): UDP RTP, AES-128-CBC, AAC-ELD/AAC-LC via MediaCodec, RAOP retransmit, AudioTrack
- [x] `AlacDecoder.kt` + native libalac — RAOP/SDP audio: AES-128-CBC (per-packet IV) + Apple ALAC; mute-on-decrypt-error guard
- [x] `BufferedAudioServer.kt` — AirPlay 2 buffered audio (type 103) accepted and instrumented
- [x] `AirPlayNtpClient.kt` — Apple NTP for A/V sync
- [x] `NowPlayingInfo.kt` (DMAP parser) + album artwork → `NowPlayingScreen.kt` overlay
- [x] `DacpClient.kt` — `_dacp._tcp` discovery + reverse remote (TV remote → sender play/pause/skip/volume)
- [x] `StreamStats.kt` — per-session RTP statistics
- [x] Audio-only mode: bypass VideoDecoder; stay on HomeScreen

**Optional surround audio:**
- [ ] Runtime surround capability check: `AudioManager.getDevices()` → check `AudioFormat.ENCODING_AC3` / `ENCODING_E_AC3_JOC` support
- [ ] AC-3 (Dolby Digital) output via AudioTrack with `ENCODING_AC3` if device supports it
- [ ] E-AC-3 / Dolby Atmos (JOC) output via AudioTrack with `ENCODING_E_AC3_JOC` if device supports it
- [ ] Advertise surround capability in AirPlay `features` bitmask only if hardware supports it

**Definition of Done:** AC-5.x — A/V sync ≤40ms. ALAC music streams play correctly. Surround audio passes through on capable hardware.

---

## Phase 6 – Miracast Receiver (full codec matrix)

**Milestone:** M6

**Goal:** Miracast screen mirroring from Windows 10+ and Android with full WFD codec matrix.

**Tasks:**

**Protocol and transport:**
- [ ] `WifiDirectManager.kt` — full Wi-Fi P2P (peer discovery, connection accept)
- [ ] `MiracastSession.kt` — WFD RTSP negotiation (capability exchange, `wfd-video-formats`, `wfd-audio-codecs`)
- [ ] WFD capability advertisement: report supported video/audio codecs in WFD capability exchange
- [ ] MPEG-TS demuxer: parse incoming MPEG Transport Stream; extract video/audio elementary streams

**Mandatory video (H.264):**
- [ ] `MiracastDecoder.kt` — H.264 AVC decode via VideoDecoder (Constrained High Profile + Constrained Baseline Profile)
- [ ] H.264 level negotiation: advertise up to Level 4.2 in WFD capability exchange

**Optional video (H.265):**
- [ ] Runtime HEVC capability check; advertise in WFD `wfd-video-formats` only if available
- [ ] H.265 HEVC decode via extended VideoDecoder

**Mandatory audio (LPCM):**
- [ ] `MiracastAudio.kt` — LPCM 16-bit / 48 kHz decode via AudioTrack direct pass-through

**Optional audio (AAC, AC-3):**
- [ ] AAC-LC / AAC-HE decode via MediaCodec; advertise in WFD `wfd-audio-codecs`
- [ ] AC-3 (Dolby Digital) pass-through via AudioTrack `ENCODING_AC3` (if device supports)
- [ ] Runtime audio capability check before advertising surround in WFD exchange

**DRM / copy protection:**
- [ ] Negotiate HDCP 2.x in WFD capability exchange (`wfd-content-protection`)
- [ ] Graceful fallback if HDCP negotiation fails: reject session with WFD RTSP 403

**UI:**
- [ ] Miracast status card updates in HomeScreen (connecting, streaming, codec info in debug overlay)

**Definition of Done:** AC-6.x — Windows 10 screen mirroring works end-to-end; LPCM and AAC audio work; H.265 works on capable hardware; HDCP negotiated.

---

## Phase 7 – Google Cast Receiver (full codec matrix)

**Milestone:** M7

**Goal:** Google Cast screen mirroring and media casting from Chrome and Android, with full codec support matrix.

**Tasks:**

**Setup:**
- [ ] Register Cast application on Google Cast Developer Console
- [x] Cast SDK lifecycle integration (CastReceiverContext)
- [ ] Cast MediaManager load/playback command handling
- [x] Graceful fallback on Fire TV (GMS not available — Cast reports disabled)

**Mandatory video (H.264 + VP8):**
- [ ] H.264 AVC decode via VideoDecoder (all profiles including High Profile)
- [ ] VP8 decode via MediaCodec `video/x-vnd.on2.vp8`

**Optional video (H.265, VP9, AV1):**
- [ ] Runtime capability check for HEVC / VP9 / AV1 via `MediaCodecList`
- [ ] VP9 decode via MediaCodec `video/x-vnd.on2.vp9` (API 23+)
- [ ] H.265 HEVC decode via extended VideoDecoder (API 21+)
- [ ] AV1 decode via MediaCodec `video/av01` (API 29+ SW, API 31+ HW preferred)
- [ ] Report supported codecs to Cast SDK via `MediaCapabilities`

**Mandatory audio (AAC-LC, AAC-HE, MP3, LPCM):**
- [ ] AAC-LC / AAC-HE decode via MediaCodec
- [ ] MP3 decode via MediaCodec `audio/mpeg`
- [ ] LPCM / WAV pass-through via AudioTrack

**Optional audio (Opus, FLAC, Dolby):**
- [ ] Opus decode via MediaCodec `audio/opus` (API 21+)
- [ ] FLAC decode via MediaCodec `audio/flac` (API 21+)
- [ ] E-AC-3 / Dolby Atmos (JOC) pass-through (if device supports `ENCODING_E_AC3_JOC`)
- [ ] Dolby Digital Plus (E-AC-3) pass-through (if device supports `ENCODING_E_AC3`)
- [ ] Runtime audio capability check; report to Cast SDK

**Container / streaming:**
- [ ] MP4 / WebM container parsing (handled by Cast SDK)
- [ ] HLS adaptive streaming (handled by Cast SDK ExoPlayer integration)
- [ ] DASH adaptive streaming (handled by Cast SDK ExoPlayer integration)

**DRM:**
- [ ] Widevine L1/L3 via Android `MediaDrm` (handled automatically by Cast SDK)
- [ ] PlayReady via Android `MediaDrm` (handled automatically by Cast SDK)

**Definition of Done:** AC-7.x — Cast from Chrome works on Google TV; H.264 + VP8 mandatory; optional codecs negotiated at runtime; DRM-free streams play; Widevine-protected streams play via Cast SDK.

---

## Phase 8 – Stability

**Milestone:** M8

**Tasks:**
- [ ] 30-minute continuous stream tests for all 3 protocols
- [ ] Auto-reconnect for all protocols
- [ ] Memory leak audit
- [ ] `start on boot` BroadcastReceiver

**Definition of Done:** AC-8.x — 30min stable, reconnect working.

---

## Phase 9 – Fire TV Port

**Milestone:** M9

**Tasks:**
- [ ] Test all phases on Fire TV hardware
- [ ] Fix API level 25 issues
- [ ] Cast graceful fallback (no GMS)
- [ ] Fire TV flavor specific adjustments

---

## Phase 10 – i18n Polish

**Milestone:** M10

**Tasks:**
- [ ] Complete all string resources in EN and DE
- [ ] Add FR strings (community contribution ready)
- [ ] RTL support baseline (for future AR/HE)
- [ ] Locale-aware date/time/number formatting

---

## Phase 11 – Release

**Milestone:** M11
**Status:** 🔄 Beta — v1.0.0-beta.2 published 2026-10-09

**Tasks:**
- [x] Signed release APKs for both flavors (`scripts/release.sh`)
- [x] GitHub Release with GoogleTV + FireTV APKs ([v1.0.0-beta.2](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-beta.2))
- [x] CHANGELOG.md entry for v1.0.0-beta.2
- [x] Documentation updated (README, TROUBLESHOOTING, INSTALLATION, PROJECT_PLAN)
- [x] Community bug fixes merged (screen awake, mDNS standby recovery, iOS 17+ RTSP limit, IPv4 wildcard binding)
- [ ] Real-device A/V validation on Google TV and Fire TV hardware
- [ ] Stable v1.0.0 release

---

## Phase 12 – UPnP / DLNA MediaRenderer 📋

**Milestone:** M12  
**Status:** 📋 Planned (Architected in [ADR-004](../decisions/ADR-004-dlna-mediarenderer-roadmap.md))  

**Goal:** Provide native media streaming for Windows ("Cast to Device"), VLC, BubbleUPnP, and Android UPnP controllers via an incremental, secure, and fully unit-tested DLNA MediaRenderer stack.

### Sub-Phase D1: Discovery & Receiver Foundation
- [ ] Implement `SsdpAdvertiser` on `239.255.255.250:1900` broadcasting `urn:schemas-upnp-org:device:MediaRenderer:1` and responding to `M-SEARCH`
- [ ] Lightweight embedded HTTP server serving `/description.xml` and SCPD service descriptors
- [ ] Create `DlnaReceiver` in `com.phairplay.dlna` managed by `PhairPlayService`
- [ ] Add `dlnaEnabled` preference in `AppSettings` and status card in `HomeFragment`

### Sub-Phase D2: Control Plane & Hardened SOAP Dispatcher
- [ ] Harden XML parser against XXE (`FEATURE_SECURE_PROCESSING`, entity resolution disabled, recursion depth limit)
- [ ] Implement `ConnectionManager:1` service (`GetProtocolInfo`, `GetCurrentConnectionIDs`)
- [ ] Implement `AVTransport:1` state machine (`SetAVTransportURI`, `Play`, `Pause`, `Stop`, `GetTransportInfo`, `GetPositionInfo`)
- [ ] Implement `RenderingControl:1` service (`GetVolume`, `SetVolume`, `GetMute`, `SetMute`)

### Sub-Phase D3: Media Playback Integration (Video, Audio, Photo)
- [ ] Connect `AVTransport` media URL playback to Android `SurfaceView` via `StreamingScreen` / `MediaPlayer`
- [ ] Support audio-only playback with `NowPlayingScreen` metadata display
- [ ] Support photo display (`PhotoScreen`) with strict payload memory limits (max 20 MB, downsampling)
- [ ] Connect TV remote transport controls (play, pause, stop) to DLNA state machine

### Sub-Phase D4: GENA Eventing & Security Hardening
- [ ] Implement GENA event subscription and `LastChange` event dispatching
- [ ] Enforce SSRF protection: GENA callback URLs restricted to private IPv4 addresses on the local subnet
- [ ] End-to-end testing with Windows 11 "Cast to Device", VLC Renderer, and BubbleUPnP

---

## Milestone Summary

| # | Phase | Key Deliverable | Status | Primary AC |
|---|---|---|---|---|
| M0 | Spec | 4 spec docs, codec matrix, photo/DRM spec | ✅ Complete | AC-0.x |
| M1 | Skeleton | Service + UI scaffold (both flavors) | ✅ Build-ready | AC-1.x |
| M2 | Discovery | AirPlay mDNS + Miracast WFD advertising groundwork | 🔄 In Progress | AC-2.x |
| M3 | AirPlay Handshake + Photo | RTSP session + `/photo` endpoint | 🔄 In Progress | AC-3.x |
| M4 | AirPlay Video | H.264 mandatory ≥25fps; H.265 optional | 🔄 In Progress | AC-4.x |
| M5 | AirPlay Audio | A/V sync ≤40ms; ALAC; optional surround | 🔄 In Progress | AC-5.x |
| M6 | Miracast | H.264 CHP/CBP + LPCM mandatory; HEVC/AAC/AC3 optional | 🔄 Started | AC-6.x |
| M7 | Cast | H.264+VP8 mandatory; HEVC/VP9/AV1 optional; Widevine | 🔄 Started | AC-7.x |
| M8 | Stability | 30min tests all protocols; auto-reconnect | 🔄 In Progress | AC-8.x |
| M9 | Fire TV | All protocols on Fire TV; Cast graceful fallback | 🔄 In Progress | AC-9.x |
| M10 | i18n | EN+DE complete | 🔄 Partial | AC-10.x |
| M11 | Release | Signed APKs, CI green, all tests pass | 🔄 Beta | AC-11.x |
| M12 | DLNA | Stepwise UPnP/DLNA MediaRenderer (SSDP, SOAP, Playback, GENA) | 📋 Planned | AC-12.x |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| AirPlay undocumented behavior | High | High | Reference UxPlay/RPiPlay open-source implementations for protocol understanding; write code from scratch |
| Miracast requires hidden Android APIs on some devices | High | High | Use WifiP2pManager + custom WFD RTSP; test early on real hardware |
| Cast not available on Fire TV (no GMS) | Certain | Medium | Graceful fallback; disable Cast toggle on Fire TV flavor |
| Wi-Fi P2P conflicts with existing AP connection | Medium | High | Test multi-connected scenarios early |
| Google Cast SDK license terms | Low | High | Review carefully before distribution |
| Open-source AirPlay implementations: legal risk | Low | High | Reference only for protocol understanding; write code from scratch |
| H.265 / HEVC not available on all target devices | High | Low | Runtime capability check; feature advertised only when HW available; graceful fallback to H.264 |
| AV1 hardware decoder not available below API 31 | High | Low | Software AV1 decoder (API 29+) as fallback; advertise only if decoder found via MediaCodecList |
| HDCP negotiation fails on some Android TV hardware | Medium | Medium | Graceful WFD session rejection; log HDCP capability at startup; test on Fire TV (limited HDCP HW) |
| Dolby Atmos / AC-3 pass-through requires specific HDMI setup | Medium | Low | Runtime check via AudioManager; optional feature; disabled if not advertised by sink device |
| FairPlay DRM requested by users | Low | Low | Document clearly in FAQ: FairPlay not supported by design; AirPlay screen mirroring (non-DRM) works |
| Large JPEG/PNG images (e.g. 12MP from iPhone) cause OOM | Medium | Medium | Use BitmapFactory.Options.inSampleSize; downsample to display resolution before decoding |
| MPEG-TS demuxing complexity for WFD | Medium | High | Consider reusing ExoPlayer's TS extractor for WFD streams to avoid reimplementing demuxing |
