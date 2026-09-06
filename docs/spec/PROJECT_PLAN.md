# PhairPlay – Project Plan

Version: 2.1
Status: Active
Date: 2026-03-23
Last Updated: 2026-05-23

---

## Phase Order

```
Phase 0 → Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5 → Phase 6 → Phase 7 → Phase 8 → Phase 9 → Phase 10 → Phase 11
 Spec     Skeleton  AirPlay   AirPlay   AirPlay   AirPlay   (removed)   Cast     Stability  Fire TV   i18n      Release
          + UI       mDNS     Handshk    Video     Audio+    Miracast  Receiver             Port      Polish
                    +Service  +RTSP     +Photo    Opt.Codec  Full     Full
                              +Photo   +Opt.HEVC  +HEVC    Codecs    Codecs
  M0        M1       M2        M3        M4        M5        M6         M7        M8         M9       M10       M11
```

## Status Overview

| Phase | Milestone | Status | Notes |
|---|---|---|---|
| 0 | M0 – Spec | ✅ Complete | All spec docs written, codec matrix added (v2.2) |
| 1 | M1 – Skeleton | ✅ Complete | UI, settings, foreground service, and both APK flavors build |
| 2 | M2 – mDNS | ✅ Complete | AirPlay mDNS + InfoResponder implemented; Cast remains registration-dependent |
| 3 | M3 – AirPlay Handshake | ✅ Complete | Full RTSP router, SDP parsing, plist codec, pairing (Ed25519/X25519 + SRP), FairPlay fp-setup, `/photo` endpoint, 247 unit tests |
| 4 | M4 – AirPlay Video | ✅ Complete | H.264 via MirrorStreamServer + MirrorCrypto (AES-128-CTR), MediaCodec with SPS-driven reinit and self-heal, aspect-fit rendering; real-device validation ongoing |
| 5 | M5 – AirPlay Audio | ✅ Complete | AAC-ELD/AAC-LC (AudioStreamServer), ALAC (AlacDecoder + libalac), AES-128-CBC, NTP sync, DACP reverse remote, NowPlayingScreen; real-device validation ongoing |
| 6 | M6 – Miracast | ❌ Removed | Sideloaded apps cannot be Miracast sinks (WFD IE needs a system permission) — ADR-004. Replaced by DLNA (sub-project 3) |
| 7 | M7 – Google Cast | 🔄 Started | Google TV Cast Connect SDK lifecycle implemented; full testing requires registered Cast app ID |
| 8 | M8 – Stability | ⏳ Pending | |
| 9 | M9 – Fire TV | 🔄 In Progress | Signed Fire TV APK released (v1.0.0-beta.1); real Fire TV A/V validation pending |
| 10 | M10 – i18n | 🔄 Partial | EN/DE resource structure exists; full UX string audit pending |
| 11 | M11 – Release | 🔄 Beta | v1.0.0-beta.1 signed release published on GitHub (2026-06-14); full stable release pending real-device validation |

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
- HomeScreen shows 2 service cards (AirPlay / Cast) with status
- Settings screen opens and saves settings
- ForegroundService persists in background
- Notification visible with Stop/Restart buttons
- DE strings visible when device language is German

**Acceptance Criteria:** AC-1.x

---

## Phase 2 – mDNS + Network Visibility

**Milestone:** M2

**Goal:** All enabled services advertise on the network. macOS sees AirPlay. Chrome sees Cast.

**Tasks:**
- [x] `MdnsService.kt` — AirPlay mDNS advertisement
- [x] Google TV Cast Connect SDK lifecycle and manifest registration
- [x] `NetworkUtils.kt` — IP, MAC, UUID helpers
- [x] Settings: device name applied to implemented advertisers

**Definition of Done:**
- macOS sees PhairPlay within 3s (AC-2.1)
- Device name from Settings is shown in picker
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

## Phase 6 – Miracast Receiver — removed

**Milestone:** M6 — ❌ removed 2026-09-06. A sideloaded Android app cannot set the Wi-Fi Display information element, so no Miracast sender can discover it; see `docs/decisions/ADR-004-miracast-removed.md`. Windows senders are served by the DLNA MediaRenderer (sub-project 3).

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
**Status:** 🔄 Beta — v1.0.0-beta.1 published 2026-06-14

**Tasks:**
- [x] Signed release APKs for both flavors (`scripts/release.sh`)
- [x] GitHub Release with GoogleTV + FireTV APKs ([v1.0.0-beta.1](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-beta.1))
- [x] CHANGELOG.md entry for v1.0.0-beta.1
- [x] Documentation updated (README, ARCHITECTURE, PROJECT_PLAN)
- [ ] All tests green on CI (247 JVM tests pass; Android Lint pending)
- [ ] Real-device A/V validation on Google TV and Fire TV hardware
- [ ] Stable v1.0.0 release

---

## Milestone Summary

| # | Phase | Key Deliverable | Status | Primary AC |
|---|---|---|---|---|
| M0 | Spec | 4 spec docs, codec matrix, photo/DRM spec | ✅ Complete | AC-0.x |
| M1 | Skeleton | Service + UI scaffold (both flavors) | ✅ Build-ready | AC-1.x |
| M2 | Discovery | AirPlay mDNS advertising | ✅ Complete | AC-2.x |
| M3 | AirPlay Handshake + Photo | RTSP session + `/photo` endpoint | 🔄 In Progress | AC-3.x |
| M4 | AirPlay Video | H.264 mandatory ≥25fps; H.265 optional | 🔄 In Progress | AC-4.x |
| M5 | AirPlay Audio | A/V sync ≤40ms; ALAC; optional surround | 🔄 In Progress | AC-5.x |
| M6 | Miracast | Removed — ADR-004 | ❌ Removed | — |
| M7 | Cast | H.264+VP8 mandatory; HEVC/VP9/AV1 optional; Widevine | 🔄 Started | AC-7.x |
| M8 | Stability | 30min tests all protocols; auto-reconnect | ⏳ Pending | AC-8.x |
| M9 | Fire TV | All protocols on Fire TV; Cast graceful fallback | 🔄 Build-ready | AC-9.x |
| M10 | i18n | EN+DE complete | 🔄 Partial | AC-10.x |
| M11 | Release | Signed APKs, CI green, all tests pass | ⏳ Pending | AC-11.x |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| AirPlay undocumented behavior | High | High | Reference UxPlay/RPiPlay open-source implementations for protocol understanding; write code from scratch |
| Cast not available on Fire TV (no GMS) | Certain | Medium | Graceful fallback; disable Cast toggle on Fire TV flavor |
| Google Cast SDK license terms | Low | High | Review carefully before distribution |
| Open-source AirPlay implementations: legal risk | Low | High | Reference only for protocol understanding; write code from scratch |
| H.265 / HEVC not available on all target devices | High | Low | Runtime capability check; feature advertised only when HW available; graceful fallback to H.264 |
| AV1 hardware decoder not available below API 31 | High | Low | Software AV1 decoder (API 29+) as fallback; advertise only if decoder found via MediaCodecList |
| Dolby Atmos / AC-3 pass-through requires specific HDMI setup | Medium | Low | Runtime check via AudioManager; optional feature; disabled if not advertised by sink device |
| FairPlay DRM requested by users | Low | Low | Document clearly in FAQ: FairPlay not supported by design; AirPlay screen mirroring (non-DRM) works |
| Large JPEG/PNG images (e.g. 12MP from iPhone) cause OOM | Medium | Medium | Use BitmapFactory.Options.inSampleSize; downsample to display resolution before decoding |
