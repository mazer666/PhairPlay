# PhairPlay – Project Plan

Version: 2.4
Status: Active
Date: 2026-03-23
Last Updated: 2026-10-25

> **Revision 2.4 (2026-10-25).** Added the binding
> [release schedule](#release-schedule): when alpha, beta and stable releases are
> built, which conditions trigger them, and which platform each of them ships.
> The schedule is the trigger for shipping binaries — a planned date never
> replaces a trigger.

> **Revision 2.3 (2026-10-24, second pass).** The CI quality gate and the lint
> are green since the team leader closed them, and the media URI has an address
> policy. The status table, the phase boxes and the milestone summary below were
> updated against the code on commit `2267672` — not against the checkboxes of
> the older plan. The acceptance criteria identifiers follow
> `docs/spec/ACCEPTANCE_CRITERIA.md` v2.0 (`AC-M<n>.<m>` for the milestones
> M6 … M12; the legacy rounds AR-0 … AR-8 keep their old `AC-<n>.<m>`
> identifiers). The milestone risk register lives in
> [`../project/RISKS.md`](../project/RISKS.md).

> **Revision 2.2 (2026-10-24).** The status table below was verified against the
> code on commit `51933eb`, not against the checkboxes of the older plan. The
> milestone risk register has moved to
> [`../project/RISKS.md`](../project/RISKS.md).

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

> **How to read the statuses.**
> * **✅ Complete** — the code is there, the tests exist and are green, and the
>   remaining device validation is explicitly marked as open in the acceptance
>   record. The acceptance record is the authority; it names the test that
>   proves each claim.
> * **🟡 Implemented, device validation open** — the code is there and the unit
>   tests are green, but the behaviour on a television has never been observed.
> * **🟠 Partial** — a defined part of the milestone is delivered, a defined
>   part is not, and the split is written down.
> * **🔴 Blocked** — the milestone cannot advance without something that is not
>   in the repository (an application ID, a system privilege, hardware).

| Phase | Milestone | Status | Notes |
|---|---|---|---|
| 0 | M0 – Spec | ✅ Complete | 4 spec documents present; `REQUIREMENTS.md` v3.0 and `ACCEPTANCE_CRITERIA.md` v2.0 were realigned to the code in this cycle |
| 1 | M1 – Skeleton | 🟡 Implemented, device validation open | UI, settings, foreground service and both APK flavours build; the UI navigation pass on real hardware is still open |
| 2 | M2 – mDNS | 🟡 Implemented, device validation open | AirPlay mDNS (`_airplay._tcp`, `_raop._tcp`), the InfoResponder, the Miracast WFD advertising and the Cast lifecycle are implemented; the picker entry is not proven on a device |
| 3 | M3 – AirPlay Handshake | 🟡 Implemented, device validation open | Full RTSP router, SDP parsing, plist codec, pairing (Ed25519/X25519 + SRP), FairPlay fp-setup, `/photo`; 202 AirPlay tests are green; the macOS/iOS handshake is not proven on a device |
| 4 | M4 – AirPlay Video | 🟡 Implemented, device validation open | H.264 via `MirrorStreamServer` + `MirrorCrypto`, MediaCodec with SPS-driven reinit and self-heal, aspect-fit rendering; the fps / latency budget is **not measured** |
| 5 | M5 – AirPlay Audio | 🟡 Implemented, device validation open | AAC-ELD/AAC-LC, ALAC, AES-128-CBC, NTP sync, DACP reverse remote, NowPlaying screen; the A/V sync budget is **not measured** |
| 6 | M6 – Miracast | 🟠 Partial — control plane only | Discovery and the WFD RTSP control plane are implemented and tested; the **media path does not exist** and cannot exist for a sideloaded app (ADR-004). No MPEG-TS demuxer, no WFD decoder. |
| 7 | M7 – Google Cast | 🟠 Partial — graceful degradation only | The Cast Connect SDK lifecycle is implemented, the Fire TV flavour degrades to `DISABLED`, the unconfigured Google TV build reports `ERROR`; the end-to-end Cast receiver needs a registered application ID and hardware |
| 8 | M8 – Stability | 🟠 Partial | The frame-queue budget and the mDNS registration bookkeeping are proven by unit tests; the 30-minute session, the RAM, the CPU and the re-advertisement after a network outage are **not measured** |
| 9 | M9 – Fire TV | 🟡 Implemented, device validation open | The flavour builds, carries no Play Services and degrades honestly; it has never been run on a Fire TV |
| 10 | M10 – i18n | 🟠 Partial | EN / DE / FR are complete (80 keys each) and proven by `scripts/check-i18n-completeness.sh`; the RTL baseline and the locale-aware formatting are still open; the texts have never been seen on a device |
| 11 | M11 – Release | 🟠 Beta — not ready for v1.0.0 | The JVM suite is green (**511 tests, 0 failures, 0 errors, 0 skipped**, 59 test classes) and both debug APKs build. **The CI quality gate is green** (all four structure checks exit 0) and **the lint is green for both flavours** (the `firetv` variant was analysed for the first time). The three High findings of the security audit are closed and measured. Still open: no signed APK was cut, no device test was executed, and SEC-04 needs a written owner decision |
| 12 | M12 – DLNA | 🟠 Partial — control plane delivered, media path open | SSDP, description server, hardened SOAP dispatcher, GENA eventing, the Samsung DIAL extension and the playback wiring are implemented with 169 test methods; the media URI now has an **address policy** (loopback, link-local / metadata, multicast, broadcast, obfuscated notations and user info are refused) — still open: the **scheme list** of the media URI, the accepted **public addresses** and the **un-resolved host names** (finding SEC-04, S3 — it needs a written owner decision), no cleartext configuration exists, and no control point has ever talked to the renderer |

The per-milestone acceptance records live in
[`../acceptance/`](../acceptance/): `M06-ACCEPTANCE.md` … `M12-ACCEPTANCE.md`.
The open risks live in [`../project/RISKS.md`](../project/RISKS.md).

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

**Acceptance Criteria:** AC-1.x (legacy round AR-1)

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
- macOS sees PhairPlay within 3s (AC-2.1, legacy round AR-2)
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
- [x] Unit tests for the whole AirPlay stack — 202 tests in `com.phairplay.airplay` and `com.phairplay.airplay.handshake` are green (measured on `51933eb`)
- [ ] Real macOS/iOS AirPlay handshake and photo transfer validation

**Definition of Done:** AC-3.x (legacy round AR-3) — full handshake from macOS without RTSP errors; photo from iOS Photos app appears on screen.

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

**Definition of Done:** AC-4.x (legacy round AR-4) — ≥25fps, ≤100ms latency on Google TV for H.264 streams. HEVC streams play if hardware is capable.
**Note (2026-10-24):** neither number has been measured on a device — see [`../acceptance/M08-ACCEPTANCE.md`](../acceptance/M08-ACCEPTANCE.md) §3.

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

**Definition of Done:** AC-5.x (legacy round AR-5) — A/V sync ≤40ms. ALAC music streams play correctly. Surround audio passes through on capable hardware.
**Note (2026-10-24):** the A/V sync number has not been measured on a device.

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

**Definition of Done:** ~~Windows 10 screen mirroring works end-to-end; LPCM and AAC audio work; H.265 works on capable hardware; HDCP negotiated.~~
**Revised Definition of Done (2026-10-24):** AC-M6.1 … AC-M6.8 — discovery and control plane only. The media path is **out of scope** (see ADR-004 and `docs/spec/REQUIREMENTS.md` §3). Acceptance record: [`../acceptance/M06-ACCEPTANCE.md`](../acceptance/M06-ACCEPTANCE.md).

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

**Definition of Done:** ~~Cast from Chrome works on Google TV; H.264 + VP8 mandatory; optional codecs negotiated at runtime; DRM-free streams play; Widevine-protected streams play via Cast SDK.~~
**Revised Definition of Done (2026-10-24):** AC-M7.1 … AC-M7.6 — graceful degradation. The Cast SDK lifecycle runs, the Fire TV flavour degrades to `DISABLED`, an unconfigured build reports `ERROR`. The end-to-end Cast receiver needs a registered application ID. Acceptance record: [`../acceptance/M07-ACCEPTANCE.md`](../acceptance/M07-ACCEPTANCE.md).

---

## Phase 8 – Stability

**Milestone:** M8

**Tasks:**
- [ ] 30-minute continuous stream tests for all 3 protocols — **open: no device** (device test plan DT-15)
- [ ] Auto-reconnect for all protocols — **open: no protocol-level reconnect exists; only the AirPlay mDNS re-advertisement** (FR-61 deviation)
- [ ] Memory leak audit — **open: no profiler run**
- [x] `start on boot` BroadcastReceiver — implemented (`service/BootReceiver.kt`, `RECEIVE_BOOT_COMPLETED`); the runtime behaviour needs a device (DT-13)
- [x] Bounded mirroring frame queue (90 items / 32 MB) — `MirrorFrameQueueTest`, 13 green tests
- [x] Bounded mDNS registration retry with an explicit error state — `MdnsRegistrationTrackerTest`, 10 green tests

**Definition of Done:** ~~AC-8.x — 30min stable, reconnect working.~~
**Revised Definition of Done (2026-10-24):** AC-M8.1 … AC-M8.8 (plus the legacy round AR-6 / `AC-6.x`). AC-M8.4 and AC-M8.7 are proven by unit tests; AC-M8.1, AC-M8.2, AC-M8.3, AC-M8.5, AC-M8.6 and AC-M8.8 need a device. Acceptance record: [`../acceptance/M08-ACCEPTANCE.md`](../acceptance/M08-ACCEPTANCE.md).

---

## Phase 9 – Fire TV Port

**Milestone:** M9

**Tasks:**
- [ ] Test all phases on Fire TV hardware — **open: no device** (device test plan, Fire TV column)
- [x] Fix API level 25 issues — **the `firetv` variant was analysed for the first time in the second pass: `./gradlew :app:lintFiretvDebug` → `BUILD SUCCESSFUL`, the report says `No issues found.`, so the `NewApi` check for API 25 is proven; `minSdk = 25` is set. The runtime behaviour on an API 25 device still needs hardware**
- [x] Cast graceful fallback (no GMS) — the Fire TV flavour reports `DISABLED` and contains no reference to `com.google.android.gms`
- [x] Fire TV flavor specific adjustments — the flavour compiles, carries its own package id `com.phairplay.firetv` and its own version suffix

**Definition of Done (2026-10-24):** AC-M9.1 … AC-M9.6 (plus the legacy round AR-7 / `AC-7.x`). AC-M9.1 and AC-M9.3 are covered by the build; every other criterion needs a device. Acceptance record: [`../acceptance/M09-ACCEPTANCE.md`](../acceptance/M09-ACCEPTANCE.md).

---

## Phase 10 – i18n Polish

**Milestone:** M10

**Tasks:**
- [x] Complete all string resources in EN and DE — 80 keys in each, proven by `scripts/check-i18n-completeness.sh` (exit 0)
- [x] Add FR strings (community contribution ready) — `values-fr/strings.xml` with 80 keys, same key set
- [ ] RTL support baseline (for future AR/HE) — **open: not started**
- [ ] Locale-aware date/time/number formatting — **open: not started**
- [ ] Render the translations on a device — **open: no device** (device test plan DT-12)

**Definition of Done (2026-10-24):** AC-M10.1 … AC-M10.6. AC-M10.1 … AC-M10.3 are proven by the script; AC-M10.4 by the code; AC-M10.5 and AC-M10.6 need a device. Acceptance record: [`../acceptance/M10-ACCEPTANCE.md`](../acceptance/M10-ACCEPTANCE.md).

---

## Phase 11 – Release

**Milestone:** M11
**Status:** 🟠 Beta — v1.0.0-beta.2 published 2026-10-09; v1.0.0-beta.3 prepared 2026-10-24; **not ready for a stable v1.0.0**

**Tasks:**
- [x] Signed release APKs for both flavors (`scripts/release.sh`) — the script is in place and the beta.2 release was signed with it
- [x] GitHub Release with GoogleTV + FireTV APKs ([v1.0.0-beta.2](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-beta.2))
- [x] CHANGELOG.md entry for v1.0.0-beta.2
- [x] Documentation updated (README, TROUBLESHOOTING, INSTALLATION, PROJECT_PLAN)
- [x] Community bug fixes merged (screen awake, mDNS standby recovery, iOS 17+ RTSP limit, IPv4 wildcard binding)
- [x] CI quality gate with four repository structure checks (`.github/workflows/ci.yml`, job `quality-gate`)
- [x] JVM test suite green — measured on the integrated tree: **511 tests, 0 failures, 0 errors, 0 skipped** in 59 test classes (`BUILD SUCCESSFUL in 3m 18s`). Before this cycle it was 277 tests
- [x] Both debug flavours build — measured: `BUILD SUCCESSFUL` (Google TV 1m 40s, Fire TV 1m 13s)
- [x] Make the quality gate green — **closed and measured**: all four structure checks exit 0 (`check-file-size.sh`: `189 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines`; `check-kdoc-headers.sh`: `233 class declaration(s) checked, all of them have a KDoc header`; `check-docs-links.sh`: `192 internal link(s) resolve`; `check-i18n-completeness.sh`: `80 key(s) … complete in 2 language(s)`). Risk R-07 is closed
- [x] Make the lint green for both flavours — **closed and measured**: `:app:lintGoogletvDebug` and `:app:lintFiretvDebug` → `BUILD SUCCESSFUL` (8m 50s), both reports say `No issues found.` The finding was `android:usesPermissionFlags` without `tools:targetApi="31"`. Risk R-16 is closed
- [ ] Produce and verify the signed v1.0.0 APKs (`apksigner verify`) — **open: the keystore is the owner's**
- [ ] Real-device A/V validation on Google TV and Fire TV hardware — **open: no device; 0 of 15 device test cases executed**
- [x] Close the three S4 findings of the security audit — **closed and measured**: SEC-01 (the AirPlay media URL is checked before the player fetches it), SEC-02 (the picture decoder reads the dimensions first and has a pixel budget), SEC-03 (the reassembled FU-A unit is bounded to 8 MB). SEC-04 stays open as an S3 with a written owner decision (the scheme list of the media URI)
- [ ] Stable v1.0.0 release

**Definition of Done (2026-10-24):** AC-M11.1 … AC-M11.8 (plus the legacy round AR-8 / `AC-8.x`). Acceptance record: [`../acceptance/M11-ACCEPTANCE.md`](../acceptance/M11-ACCEPTANCE.md).

---

## Phase 12 – UPnP / DLNA MediaRenderer

**Milestone:** M12  
**Status:** 🟠 Partial — the control plane is delivered, the media path is open (architected in [ADR-004](../decisions/ADR-004-dlna-mediarenderer-roadmap.md))  

**Goal:** Provide native media streaming for Windows ("Cast to Device"), VLC, BubbleUPnP, and Android UPnP controllers via an incremental, secure, and fully unit-tested DLNA MediaRenderer stack.

> **Note (2026-10-24, second pass).** The sub-phases below were unchecked in this plan
> although the code exists: `app/src/main/kotlin/com/phairplay/dlna/` holds 28
> production files and a large part of the 511 green test methods of the measured
> run. The boxes now reflect the code. What is
> **not** done is stated explicitly in each box.

### Sub-Phase D1: Discovery & Receiver Foundation
- [x] Implement `SsdpAdvertiser` on `239.255.255.250:1900` broadcasting `urn:schemas-upnp-org:device:MediaRenderer:1` and responding to `M-SEARCH` — `SsdpAdvertiser.kt`; `SsdpAdvertiserLiveTest` and `SsdpResponderTest` are green
- [x] Lightweight embedded HTTP server serving `/description.xml` and SCPD service descriptors — `DlnaDescriptionServer.kt` (bounded: 8 KB request line, 16 KB header, 64 KB body, 8 workers + 16 queued)
- [x] Create `DlnaReceiver` in `com.phairplay.dlna` managed by `PhairPlayService` — `DlnaReceiver.kt`; the receiver is owned by `ReceiverSupervisor`
- [x] Add `dlnaEnabled` preference in `AppSettings` and status card in `HomeFragment` — `AppSettings.dlnaEnabled`, `SettingsRepository`, `HomeFragment.kt` lines 127 and 186

### Sub-Phase D2: Control Plane & Hardened SOAP Dispatcher
- [x] Harden XML parser against XXE (`FEATURE_SECURE_PROCESSING`, entity resolution disabled, recursion depth limit) — `SecureSoapXml.kt` (fail-closed, 64 KB / 32 levels); `SecureSoapXmlTest` (10) and `DlnaSoapDispatcherSecurityTest` (6) are green
- [x] Implement `ConnectionManager:1` service (`GetProtocolInfo`, `GetCurrentConnectionIDs`) — `DlnaSoapDispatcherTest` is green; `DlnaProtocolInfo.SOURCE` is not empty
- [x] Implement `AVTransport:1` state machine (`SetAVTransportURI`, `Play`, `Pause`, `Stop`, `GetTransportInfo`, `GetPositionInfo`) — `DlnaAvTransportService.kt`; `DlnaSoapDispatcherTest` (16) is green
- [x] Implement `RenderingControl:1` service (`GetVolume`, `SetVolume`, `GetMute`, `SetMute`) — implemented, but the value is **not applied to the TV volume**; open point O-4

### Sub-Phase D3: Media Playback Integration (Video, Audio, Photo)
- [x] Connect `AVTransport` media URL playback to Android `SurfaceView` via `StreamingScreen` / `MediaPlayer` — `DlnaReceiver.onPlay` → `AirPlayVideoPlayer`; **not verifiable without a device**, and the cleartext fetch is likely blocked on API 35 (risk R-05)
- [x] Support audio-only playback with `NowPlayingScreen` metadata display — `AirPlayVideoPlayer.playAudio`, no surface
- [x] Support photo display (`PhotoScreen`) with strict payload memory limits (max 20 MB, downsampling) — `DlnaImageDecoder.kt`; `DlnaImageDecoderTest` (6) is green
- [x] Connect TV remote transport controls (play, pause, stop) to DLNA state machine — `DlnaRemoteCommand` → `DlnaSoapDispatcher.onRemoteCommand`; the D-pad mapping itself is not tested on a device

### Sub-Phase D4: GENA Eventing & Security Hardening
- [x] Implement GENA event subscription and `LastChange` event dispatching — `GenaEventManager.kt`; the `NOTIFY` goes out over a raw socket because `HttpURLConnection` refuses that method; `GenaEventManagerTest` (8) is green
- [x] Enforce SSRF protection: GENA callback URLs restricted to private IPv4 addresses on the local subnet — `GenaCallbackPolicy.kt`; `GenaCallbackPolicyTest` (16) is green
- [x] Enforce SSRF protection for the **media URI** — `HostAddressPolicy.kt` (new in the second pass): the loopback, the reserved range `0.0.0.0/8`, the link-local range (and with it `169.254.169.254`), the multicast range, the broadcast address, the range `240.0.0.0/4`, the further metadata addresses (`100.100.100.200`, `192.0.0.192`, `fd00:ec2::254`), the known metadata host names, the non-standard notations of an IPv4 address and every user info part are refused. `HostAddressPolicyTest` (11) plus one new case in `DlnaMediaUriTest` are green. **Still open:** the *scheme* list, the accepted **public addresses** and the **un-resolved host names** — finding SEC-04 (S3), risk R-03, change request CR-01
- [ ] End-to-end testing with Windows 11 "Cast to Device", VLC Renderer, and BubbleUPnP — **open: no device and no control point** (device test plan DT-08 … DT-11)
- [ ] Samsung Smart View extension — **delivered**: the Samsung SSDP search term and the DIAL documents are implemented (`SamsungSsdpResponderTest` 6, `DialAppCatalogTest` 10, `DialEndpointTest` 6, `DlnaDeviceDescriptionFieldsTest` 7 — all green); the visibility in a real television list is not proven

**Definition of Done (2026-10-24, second pass):** AC-M12.1 … AC-M12.20. AC-M12.15 is **still not met** — the *address* of the media URI is now restricted, but the *scheme* allowlist still contains `file`, `smb`, `ftp` and other schemes. AC-M12.4, AC-M12.18, AC-M12.19 and the Samsung visibility need a device. Acceptance record: [`../acceptance/M12-ACCEPTANCE.md`](../acceptance/M12-ACCEPTANCE.md).

---

## Milestone Summary

| # | Phase | Key Deliverable | Status | Primary AC | Acceptance record |
|---|---|---|---|---|---|
| M0 | Spec | 4 spec docs, codec matrix, photo/DRM spec | ✅ Complete | AC-0.x | — |
| M1 | Skeleton | Service + UI scaffold (both flavors) | 🟡 Implemented, device validation open | AC-1.x | — |
| M2 | Discovery | AirPlay mDNS + Miracast WFD advertising groundwork | 🟡 Implemented, device validation open | AC-2.x | — |
| M3 | AirPlay Handshake + Photo | RTSP session + `/photo` endpoint | 🟡 Implemented, device validation open | AC-3.x | — |
| M4 | AirPlay Video | H.264 mandatory ≥25fps; H.265 optional | 🟡 Implemented, device validation open | AC-4.x | — |
| M5 | AirPlay Audio | A/V sync ≤40ms; ALAC; optional surround | 🟡 Implemented, device validation open | AC-5.x | — |
| M6 | Miracast | Discovery + WFD control plane; **no media path** | 🟠 Partial | AC-M6.x | [`M06-ACCEPTANCE.md`](../acceptance/M06-ACCEPTANCE.md) |
| M7 | Cast | Cast SDK lifecycle + graceful degradation | 🟠 Partial | AC-M7.x | [`M07-ACCEPTANCE.md`](../acceptance/M07-ACCEPTANCE.md) |
| M8 | Stability | Bounded queue, mDNS retry; the endurance numbers are open | 🟠 Partial | AC-M8.x + AC-6.x | [`M08-ACCEPTANCE.md`](../acceptance/M08-ACCEPTANCE.md) |
| M9 | Fire TV | The flavour builds and degrades honestly; never run on a device | 🟡 Implemented, device validation open | AC-M9.x + AC-7.x | [`M09-ACCEPTANCE.md`](../acceptance/M09-ACCEPTANCE.md) |
| M10 | i18n | EN + DE + FR complete (80 keys each); RTL and formatting open | 🟠 Partial | AC-M10.x | [`M10-ACCEPTANCE.md`](../acceptance/M10-ACCEPTANCE.md) |
| M11 | Release | CI quality gate and lint green; signed APKs and the device validation open | 🟠 Beta — not ready for v1.0.0 | AC-M11.x + AC-8.x | [`M11-ACCEPTANCE.md`](../acceptance/M11-ACCEPTANCE.md) |
| M12 | DLNA | SSDP, SOAP, Playback, GENA, Samsung DIAL — all implemented; the media URI is address-restricted | 🟠 Partial — control plane delivered, media path open | AC-M12.x | [`M12-ACCEPTANCE.md`](../acceptance/M12-ACCEPTANCE.md) |

**Status legend:** ✅ Complete · 🟡 Implemented, device validation open ·
🟠 Partial · 🔴 Blocked.

---

## Release Schedule

> This section is **binding**: it defines when a release happens and when binaries
> may be shipped. A date below is a plan; a *trigger* is the decision. When the
> trigger is met, the release is built and published — when it is not, nothing is
> published, no matter what the calendar says.

### Version scheme

| Stage | Version scheme | What it means | Channel |
|---|---|---|---|
| Alpha | `v1.0.0-alpha.N` | Code complete for a milestone, **not validated on any television** | GitHub Releases, marked *prerelease* |
| Beta | `v1.0.0-beta.N` | Validated on real hardware, defects known and listed | GitHub Releases, marked *prerelease* |
| Stable | `v1.0.0` | Validated, measured, ready for the general user | GitHub Releases, marked *latest* |

### Triggers

Every trigger below is a condition on **measured** state, never on a date. A
release is only built when every condition of its row is true.

| Stage | Trigger — all conditions must hold | Platforms in the release | Artifacts |
|---|---|---|---|
| **Alpha** | 1. A milestone that changes the runtime behaviour is merged.<br>2. `:test-runner:test` is green (0 failures, 0 errors, 0 skipped).<br>3. The quality gate is green: `scripts/check-file-size.sh`, `scripts/check-kdoc-headers.sh`, `scripts/check-i18n-completeness.sh`, `scripts/check-docs-links.sh`.<br>4. Lint is green for **both** flavours.<br>5. Both APK flavours build. | **Google TV** (Android 10+, `com.phairplay.googletv`)<br>**Fire TV** (Android 7.1+, `com.phairplay.firetv`) | Signed release APKs (minified, resources shrunk) + source tag |
| **Beta** | 1. Every case of [`../guides/DEVICE_TEST_PLAN.md`](../guides/DEVICE_TEST_PLAN.md) has been executed on real hardware — 15 of 15.<br>2. The decisive cases are green: **DT-09** (VLC renderer list), **DT-10** (Windows "Cast to Device"), **DT-12** (Samsung Smart View), **DT-13/DT-14** (playback), **DT-15** (30-minute session).<br>3. The performance numbers are measured, not estimated: RAM, CPU load, frame rate, latency (see [`../quality/QUALITY.md`](../quality/QUALITY.md)).<br>4. No open finding of severity S1 or S2 (see [`../project/RISKS.md`](../project/RISKS.md)).<br>5. The acceptance records M01 … M12 name the device they were validated on. | **Google TV** — validated on a real Google TV / Android TV device<br>**Fire TV** — validated on a real Fire TV device | Signed release APKs + release notes listing the known limitations |
| **Stable** | 1. A beta has been in the owner's hands for **at least two weeks** without a new critical finding.<br>2. Every acceptance record carries a *device validated* decision — no *"not measured"* left in a performance field.<br>3. The checklist in [`../project/RELEASE_READINESS.md`](../project/RELEASE_READINESS.md) is complete.<br>4. The release is signed with the **owner's production key** (an alpha key must not be used for a stable release).<br>5. The changelog and the release notes state the platform, the minimum Android version and the known limitations. | **Google TV** and **Fire TV** — both, always. A stable release that ships for one platform only is a defect, not a decision. | Signed release APKs + changelog + release notes |

### Release train

The planned sequence. Each line is an **alpha** unless the trigger column says
otherwise; a date is only the planned day, the trigger decides.

| Release | Planned | Trigger met by | Platforms | Content |
|---|---|---|---|---|
| `v1.0.0-alpha.1` | 2026-10-25 | M6–M12 cycle: security findings SEC-01/02/03 closed, quality gate green, documentation brought onto the state of the code | Google TV + Fire TV | The M6–M12 cycle as documented in `CHANGELOG.md` |
| `v1.0.0-alpha.2` | 2026-11-01 | M13 *Reconnect* (the DLNA renderer becomes visible again after a network loss) and M15 *i18n & RTL* | Google TV + Fire TV | Re-advertisement after a network loss, right-to-left support, locale-aware formatting |
| `v1.0.0-alpha.3` | 2026-11-08 | M14 *Diagnostics* (the app explains its own state to the user) | Google TV + Fire TV | In-app diagnostics: address, ports, protocol states, self-test |
| `v1.0.0-beta.1` | 2026-11-15 | The owner has executed the 15 device test cases | Google TV + Fire TV | The same code as the last alpha, with the device findings fixed |
| `v1.0.0-beta.2` | 2026-11-29 | The findings of beta.1 are fixed and re-validated | Google TV + Fire TV | Fixes from the device test round |
| `v1.0.0` | 2026-12-13 | Two weeks of beta on real devices, no new critical finding, production key | Google TV + Fire TV | The first stable release |

**If a trigger is not met, the release is skipped** — the version number is
burned, not reused, and the next release gets the next number. A release that
does not meet its trigger never gets built.

### Rules that apply to every release

1. **One version, both platforms.** A release ships the Google TV *and* the
   Fire TV flavour, or it does not ship. A platform that cannot be built blocks
   the release — it does not get a reduced version.
2. **The binaries are the artefact.** The gate in the trigger column is run
   against the *same commit* that the tag points at. A release built from an
   untested commit is not a release.
3. **The release notes say what was not tested.** Every release lists the
   device test cases that are still open. An alpha that hides this is a lie in
   the changelog.
4. **The version lives in one place.** `versionCode` and `versionName` in
   `app/build.gradle.kts` are bumped in the same commit that the release tag
   is created from.
5. **Every release gets an entry in [`../../CHANGELOG.md`](../../CHANGELOG.md)**,
   written for a user, not for a colleague.

---

## Risk Register

> The maintained risk register lives in
> [`../project/RISKS.md`](../project/RISKS.md) — 17 entries with the likelihood,
> the impact, the countermeasure, the owner and the status. The table below is the
> original planning view and is kept for the history of the project; where the
> two differ, **the register in `docs/project/RISKS.md` is authoritative**.

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
