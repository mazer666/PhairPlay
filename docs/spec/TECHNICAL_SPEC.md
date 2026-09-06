# PhairPlay – Technical Specification

Version: 1.2
Status: Active
Date: 2026-03-23

---

## 1. AirPlay 2 Protocol Overview

AirPlay 2 Screen Mirroring works as a layered protocol stack. The description below covers the full flow from the moment a **macOS or iOS/iPadOS** user taps "AirPlay" to media appearing on the TV.

> **Supported senders:** macOS 12+ (Monterey and later), iOS 13+ / iPadOS 13+.
> The wire protocol is identical for both — the same RTSP/RTP stack handles both sender types.

### Step 1 – Service Discovery (mDNS / Bonjour)

macOS continuously scans the local network for AirPlay receivers using **mDNS** (Multicast DNS, RFC 6762), the same technology as Apple's "Bonjour".

PhairPlay registers two mDNS services:

| Service Type | Purpose |
|---|---|
| `_airplay._tcp` | Main AirPlay service — advertises device name, features, model |
| `_raop._tcp` | Remote Audio Output Protocol — required for audio streaming negotiation |

Each service registration includes **TXT records** that tell the sender what the receiver supports:

| TXT Key | Value | Meaning |
|---|---|---|
| `deviceid` | MAC address (e.g., `aa:bb:cc:dd:ee:ff`) | Unique device identifier |
| `features` | Bitmask (e.g., `0x5A7FFFF7,0x1E`) | Which AirPlay features are supported |
| `model` | `AppleTV5,3` | Tells macOS to treat us like an Apple TV |
| `srcvers` | `220.68` | AirPlay server version |
| `vv` | `2` | Protocol version 2 |
| `pi` | UUID string | Persistent device UUID |
| `pk` | 64-byte hex public key | Ed25519 public key (used in authenticated mode) |

**Android API used:** `android.net.nsd.NsdManager` — Android's built-in mDNS implementation.

### Step 2 – RTSP Session Establishment

Once macOS discovers the device, it opens a **TCP connection to port 7000** and speaks **RTSP** (Real Time Streaming Protocol, RFC 2326) with Apple-specific extensions.

The RTSP handshake sequence for screen mirroring:

```
macOS                           PhairPlay (Android TV)
  │                                     │
  │── OPTIONS rtsp://... RTSP/1.0 ────► │  "What can you do?"
  │◄─ 200 OK (Public: OPTIONS, SETUP…)─ │  "Here are my capabilities"
  │                                     │
  │── ANNOUNCE rtsp://... RTSP/1.0 ───► │  "I'm about to send you a stream"
  │   Content-Type: application/sdp     │  (SDP describes codec, ports, keys)
  │◄─ 200 OK ──────────────────────────│
  │                                     │
  │── SETUP rtsp://... RTSP/1.0 ──────► │  "Set up the video channel"
  │   Transport: RTP/AVP/TCP;...        │  (negotiates port numbers)
  │◄─ 200 OK (Transport: ...) ─────────│
  │                                     │
  │── SETUP rtsp://... RTSP/1.0 ──────► │  "Set up the audio channel"
  │◄─ 200 OK ──────────────────────────│
  │                                     │
  │── RECORD rtsp://... RTSP/1.0 ─────► │  "Start streaming now"
  │◄─ 200 OK ──────────────────────────│
  │                                     │
  │   [RTP video packets over TCP] ───► │
  │   [RTP audio packets over UDP] ───► │
  │   [Timing packets over UDP] ──────► │
  │                                     │
  │── TEARDOWN rtsp://... RTSP/1.0 ───► │  "Stop, I'm disconnecting"
  │◄─ 200 OK ──────────────────────────│
```

**SDP (Session Description Protocol)** in the ANNOUNCE body describes:
- Video codec: H.264 (`a=rtpmap:96 H264/90000`)
- Video parameters: profile-level-id, SPS/PPS (codec initialization data)
- Audio codec: AAC-ELD or ALAC
- Encryption keys (AES-128-CTR for audio, AES-128-CBC for video)
- Port numbers for RTP/RTCP

### Step 3 – Video Streaming

After RECORD, macOS sends video as **RTP packets over the RTSP TCP connection** (interleaved in the RTSP stream using `$` framing).

Each video packet contains a fragment of an **H.264 NAL unit** (Network Abstraction Layer — the elementary unit of H.264 video). The MediaCodec decoder reassembles NAL units into frames.

**Video path:**
```
RTP bytes (TCP) → RtspHandler strips RTP header →
VideoDecoder extracts NAL units → MediaCodec (hardware) → SurfaceView
```

**H.264 profile:** Up to High Profile Level 5.2. Typical mirroring uses Constrained High Profile (CHP) or Main Profile. The decoder must handle all profiles up to High Profile Level 5.2 (supports 1080p @ 60fps and 4K @ 30fps).

**H.265 / HEVC (optional):** Used by Apple TV 4K for 4K content. Enabled only if `MediaCodecInfo` reports hardware `video/hevc` support. The SDP `a=rtpmap` line will contain `H265/90000` in this case. Falls back to H.264 if HEVC is not available.

### Step 4 – Audio Streaming

Audio is sent as **RTP packets over a separate UDP socket** (port negotiated in SETUP).

Audio format is one of:
- **AAC-LC** — Low Complexity AAC, used for screen mirroring (lower bitrate)
- **AAC-ELD** (Enhanced Low Delay AAC) — used when low-latency audio is required during mirroring
- **ALAC** (Apple Lossless) 16-bit / 44.1 kHz — default for music/podcast audio-only streaming
- **LPCM** — uncompressed PCM, used when the sender requests minimal processing latency

**Surround audio (optional):**
- **Dolby Atmos** (E-AC3 with JOC) — on hardware that supports `AudioFormat.ENCODING_E_AC3_JOC`
- **AC-3** (Dolby Digital) — on hardware that supports `AudioFormat.ENCODING_AC3`

Detection of surround format is done via `AudioManager.getDevices()` at startup and advertised in the mDNS `features` bitmask accordingly.

Audio packets are **AES-128-CTR encrypted**. The key and IV are provided in the SDP body of the ANNOUNCE request.

**Audio path:**
```
RTP bytes (UDP) → AES-128-CTR decrypt → AudioDecoder extracts AAC/ALAC frame →
AudioTrack (hardware output)
```

### Step 5 – Timing Synchronization

A separate UDP socket (timing port) handles **NTP-based time synchronization** between sender and receiver. This is used to keep audio and video in sync.

### Step 6 – Audio-Only Mode (music / podcasts from macOS and iOS)

When the sender initiates an **audio-only** AirPlay stream (e.g., playing music from Apple Music, Spotify, or a podcast app), the SDP body in the ANNOUNCE request contains **only an audio media section** — there is no `m=video` line.

Detection in `RtspHandler`:
```kotlin
val hasVideo = sdp.contains("m=video")
val hasAudio = sdp.contains("m=audio")
// audio-only: hasVideo == false, hasAudio == true
```

Audio-only path:
```
RTP bytes (UDP) → AES-128-CTR decrypt → AAC/ALAC decode →
AudioTrack (hardware output)
↕ no VideoDecoder started, no SurfaceView shown
```

UI behaviour: the app remains on the **HomeScreen**. The AirPlay protocol card updates to show sender name and "Audio streaming" detail text. No fullscreen streaming activity is launched.

---

## 2. System Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                          PhairPlay App                               │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                     UI Layer (Fragments)                        │  │
│  │   MainActivity ─ HomeFragment ─ SettingsFragment               │  │
│  └────────────────────────────┬───────────────────────────────────┘  │
│                               │ binds / observes StateFlow            │
│  ┌────────────────────────────▼───────────────────────────────────┐  │
│  │               PhairPlayService  (ForegroundService)             │  │
│  │  serviceState / airPlayState / castState                        │  │
│  └────────┬─────────────────────────────────┬─────────────────────┘  │
│           │                                 │                        │
│  ┌────────▼──────┐                 ┌────────▼──────┐                │
│  │ AirPlayReceiver│                 │ CastReceiver  │                │
│  │               │                 │               │                │
│  │ MdnsService   │                 │ CastReceiver  │                │
│  │ RtspHandler   │                 │ Context (GMS) │                │
│  │ VideoDecoder  │                 └───────────────┘                │
│  │ AudioPlayer   │                                                  │
│  └───────┬───────┘                                                  │
│          │                                                           │
│  ┌───────▼───────────────────────────────────────────────────────┐  │
│  │              SettingsRepository (DataStore)                    │  │
│  │  Flow<AppSettings> — drives enable/disable of each receiver    │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
                      │ MediaCodec / AudioTrack
              ┌───────▼─────────────────────┐
              │   Android OS / Hardware      │
              │  GPU decode, AudioFlinger,   │
              │  SurfaceView                 │
              └─────────────────────────────┘

Data flows (sender → receiver):
macOS/iOS  ──[mDNS]────────► MdnsService
macOS/iOS  ──[RTSP/TCP:7000]► RtspHandler ──► VideoDecoder ──► SurfaceView
macOS/iOS  ──[RTP/UDP]──────► AudioPlayer ──► AudioTrack
Chrome/Android ──[Cast]─────► CastReceiver (Cast SDK)
```

---

## 3. Component Responsibilities

### Service Layer

| Component | File | Responsibility |
|---|---|---|
| `PhairPlayService` | `service/PhairPlayService.kt` | ForegroundService: owns lifecycle of all protocol receivers; exposes `StateFlow` for UI |
| `ServiceController` | `service/ServiceController.kt` | Singleton helper: `start` / `stop` / `restart` the service from any Context |
| `ServiceState` | `service/ServiceState.kt` | Sealed class hierarchy: `Running`, `Stopped`, `Restarting`, `Error(msg)` |
| `BootReceiver` | `service/BootReceiver.kt` | BOOT_COMPLETED receiver; starts service if `startOnBoot` setting is enabled |
| `SettingsRepository` | `settings/SettingsRepository.kt` | DataStore-backed; exposes `Flow<AppSettings>`; `suspend update {}` for writes |
| `AppSettings` | `settings/AppSettings.kt` | Immutable data class with all 7 user-configurable settings |

### Protocol Receivers

| Component | File | Responsibility |
|---|---|---|
| `AirPlayReceiver` | `airplay/AirPlayReceiver.kt` | Orchestrates mDNS, RTSP, video/audio pipeline; emits `ProtocolState` |
| `DlnaReceiver` | `dlna/DlnaReceiver.kt` | Orchestrates SSDP, HTTP/SOAP/GENA and the `MediaRenderer`; emits `ProtocolState` (ADR-005) |
| `MediaRenderer` | `dlna/MediaRenderer.kt` | DLNA session state machine: URI → `DlnaPlayer` (MediaPlayer) or `PhotoFetcher`; drives overlays |
| `MdnsService` | `airplay/MdnsService.kt` | Registers/unregisters `_airplay._tcp` + `_raop._tcp` via NsdManager |
| `RtspHandler` | `airplay/RtspHandler.kt` | Full RTSP state machine (OPTIONS→ANNOUNCE→SETUP→RECORD→TEARDOWN) |
| `VideoDecoder` | `airplay/VideoDecoder.kt` | MediaCodec H.264 hardware decode → SurfaceView |
| `AudioPlayer` | `airplay/AudioPlayer.kt` | AES-128-CTR decrypt → AAC/ALAC decode → AudioTrack |
| `CastReceiver` | `cast/CastReceiver.kt` | Google Cast SDK receiver; GMS availability check; graceful Fire TV degradation |

### UI Layer

| Component | File | Responsibility |
|---|---|---|
| `MainActivity` | `MainActivity.kt` | Single-activity host; side nav panel; swaps Home/Settings fragments |
| `HomeFragment` | `ui/HomeFragment.kt` | Protocol state cards; service control buttons; binds to PhairPlayService |
| `SettingsFragment` | `ui/SettingsFragment.kt` | All 7 settings with immediate-save listeners via SettingsRepository |

### Utilities

| Component | File | Responsibility |
|---|---|---|
| `NetworkUtils` | `util/NetworkUtils.kt` | Reads device IP, MAC address, network interface info |

---

## 4. Libraries

Only libraries that cannot be replaced by Android built-in APIs are included.

| Library | Version | Justification | Alternative Considered |
|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8.x | Structured concurrency for all async I/O; part of Kotlin ecosystem | Java threads — no, too low-level; RxJava — no, too heavy |
| `com.jakewharton.timber:timber` | 5.0.x | Tagged, level-filtered logging with pluggable backends | Log directly — no, hard to filter in release builds |
| `org.bouncycastle:bcprov-jdk15on` | 1.78.x | AES-128-CTR for audio decryption; SRP-6a for future pairing | Android's javax.crypto — partial support, missing SRP |
| `androidx.datastore:datastore-preferences` | 1.1.x | Async, coroutine-based typed key-value store for settings | SharedPreferences — no, blocking I/O; Room — no, too heavy for KV |
| `androidx.leanback:leanback` | 1.2.x | TV-specific focus handling, on-screen keyboard for display name | View-only — insufficient for text input on TV remotes |

**Deliberately excluded:**
- No Retrofit/OkHttp (raw TCP/UDP sockets for RTSP/RTP)
- No Room/SQLite (DataStore is sufficient; no relational data)
- No Jetpack Compose (View-based UI for maximum TV/D-pad compatibility — see ADR-003)
- No Hilt/Dagger (manual DI is sufficient at this scale)

---

## 5. Security Concept

### 5.1 Input Validation
Every byte received from the network is treated as potentially malicious:
- **RTSP messages**: Maximum message size enforced (64 KB). Fields parsed with strict regex/parsers. Unknown methods return `501 Not Implemented`.
- **SDP body**: Length validated before parsing. Keys extracted with byte-length assertions.
- **RTP packets**: Header length validated. Payload size checked against packet length before memcopy.
- **Encryption keys**: Length asserted to be exactly 16 bytes (AES-128) before use.

### 5.2 Permissions Minimization
No permission is requested that is not strictly required. Specifically:
- `RECORD_AUDIO` — NOT requested (we receive audio, we do not record it)
- `READ_EXTERNAL_STORAGE` — NOT requested
- `CAMERA` — NOT requested
- No `MANAGE_EXTERNAL_STORAGE`, no `INSTALL_PACKAGES`, no system-level permissions

### 5.3 No Secrets in Code
- Device ID (MAC address) is read at runtime from the network interface — never hardcoded.
- AES keys are received per-session in the RTSP ANNOUNCE body — never stored persistently.
- No API keys, tokens, or passwords anywhere in the codebase.

### 5.4 Exception Handling Policy
- Every `try/catch` block MUST log the exception via `Logger`.
- Caught exceptions MUST trigger a graceful state reset (return to waiting state) — never ignore.
- `OutOfMemoryError` and critical JVM errors are not caught; they propagate to crash the app (fail fast).

### 5.5 Authentication (v1)
v1.0 uses **unauthenticated mode** (no PIN pairing required). The `features` TXT record bitmask is set to advertise that authentication is not required. This matches the behavior of open AirPlay receivers.

Future versions may implement HAP-based SRP-6a pairing (the infrastructure in `AirPlayReceiver` is designed to support this extension).

---

## 6. Performance Goals & Strategies

| Goal | Target | Strategy |
|---|---|---|
| Video latency | ≤ 100 ms | Direct MediaCodec surface output; no intermediate buffer copies |
| Frame rate | ≥ 25 fps | Hardware decode only; I/O on background coroutine; UI on main thread |
| RAM usage | ≤ 150 MB | Fixed-size ring buffers for RTP; MediaCodec manages its own buffers |
| CPU usage | ≤ 30% avg | MediaCodec offloads to GPU; coroutines avoid busy-waiting |
| A/V sync | ≤ 40 ms drift | NTP-based presentation timestamps fed to MediaCodec |

### Thread Model
```
[Main Thread]      UI updates, Surface creation/destruction
[IO Dispatcher]    TCP/UDP socket reads, RTSP parsing
[Default Dispatcher] RTP packet processing, decryption
[MediaCodec]       Runs on its own internal thread (hardware)
[AudioTrack]       Runs on its own internal thread (hardware)
```

No network operation may run on the Main Thread. No UI operation may run off the Main Thread.

---

## 7. Build Flavors

Two product flavors are configured from day one:

| Flavor | applicationId | minSdk | Notes |
|---|---|---|---|
| `googletv` | `com.phairplay.googletv` | 29 | Google TV / Android TV, Leanback UI, may use newer APIs |
| `firetv` | `com.phairplay.firetv` | 25 | Amazon Fire TV, must avoid Google-only APIs |

Shared code lives in `app/src/main/`. Flavor-specific overrides live in `app/src/googletv/` and `app/src/firetv/`.

The `firetv` flavor MUST NOT use any API gated on API level 26+ without a version check at runtime.

---

## 8. Supported AirPlay Feature Flags

The `features` TXT record is a bitmask that tells macOS which AirPlay capabilities the receiver has. PhairPlay v1.0 will advertise the following flags (based on the open AirPlay spec):

| Bit | Feature | PhairPlay v1 | Notes |
|---|---|---|---|
| 0 | Video | ✅ Supported | H.264 AVC mandatory |
| 1 | Photo | ✅ Supported | JPEG/PNG via `/photo` endpoint — see §9 |
| 2 | VideoFairPlay | ❌ Not supported | Requires Apple FPS license — see §11 |
| 5 | Screen | ✅ Supported | Screen mirroring |
| 6 | Screen Rotate | ✅ Supported | Landscape/portrait |
| 7 | Audio | ✅ Supported | ALAC / AAC-LC / AAC-ELD / LPCM |
| 9 | AudioRedundant | ✅ Supported | |
| 14 | AudioSyncedVideo | ✅ Supported | A/V sync via NTP |
| 23 | HasUnifiedAdvertiserInfo | ✅ Supported | |
| 26 | SupportsAirPlayVideoV2 | ✅ Supported | |
| 27 | MetaDataFeatures_0 | ✅ Supported | |

The resulting hex value for the `features` field: `0x5A7FFFF7,0x1E` (will be refined during implementation to include the Photo bit).

---

## 9. Photo / Image Sharing via AirPlay

### Overview

AirPlay supports sending individual still images (JPEG or PNG) from macOS/iOS to a receiver. This is distinct from video mirroring: no RTSP session is involved; instead, the sender makes an HTTP `PUT` or `POST` request to the receiver's `/photo` endpoint.

### Protocol Flow

```
macOS/iOS                        PhairPlay (Android TV)
    │                                    │
    │── PUT /photo HTTP/1.1 ───────────► │
    │   Content-Type: image/jpeg         │
    │   X-Apple-AssetKey: <uuid>         │
    │   Content-Length: <N>              │
    │   [JPEG body]                      │
    │◄─ HTTP/1.1 200 OK ────────────────│
    │                                    │
    │   [photo displayed on TV]          │
    │── DELETE /photo HTTP/1.1 ─────────►│  "Clear the image"
    │◄─ HTTP/1.1 200 OK ────────────────│
```

The HTTP server listens on the same port as the RTSP server (port 7000) or a separate HTTP port (to be decided during implementation). The `RtspHandler` is extended to detect non-RTSP requests (HTTP verbs `PUT`, `GET`, `DELETE`) and route them to a new `PhotoHandler` component.

### Android Implementation

```
HTTP PUT /photo → PhotoHandler.receive(inputStream, contentType, contentLength)
  → BitmapFactory.decodeStream(inputStream)   // JPEG or PNG
  → PhotoScreen (full-screen ImageView)
  → BitmapDrawable displayed via ImageView.setImageBitmap()
```

No video decoder or MediaCodec is used. Image decoding runs on the `IO Dispatcher` (never on Main Thread). Display update runs on the `Main Thread`.

### Supported Image Formats

| Format | MIME Type | Android API | Notes |
|---|---|---|---|
| JPEG | `image/jpeg` | All API levels | Most common from AirPlay |
| PNG | `image/png` | All API levels | Transparency support |
| HEIC | `image/heic` | API 28+ | Optional, hardware-dependent |

HEIC (High Efficiency Image Container) is optional and only attempted if `BitmapFactory.isSupportedMimeType("image/heic")` returns true; otherwise the receiver returns HTTP 415 Unsupported Media Type.

### New Component: `PhotoHandler`

| Component | File | Responsibility |
|---|---|---|
| `PhotoHandler` | `airplay/PhotoHandler.kt` | Receives HTTP PUT requests; decodes JPEG/PNG via BitmapFactory; emits Bitmap to UI |
| `PhotoScreen` | `ui/PhotoScreen.kt` | Full-screen Fragment with ImageView; displayed when a photo is received |

### Memory Management

- Images are loaded into `Bitmap` objects. Large images (e.g., 4K JPEG) are downsampled using `BitmapFactory.Options.inSampleSize` to fit the display resolution.
- Bitmaps are recycled immediately when the photo session ends or a new image replaces the previous one.
- Maximum loaded image size: display resolution (e.g., 1920×1080 pixels), never larger.

---

## 10. Codec Matrix – All Protocols

The following table summarises the full codec support across all three protocols.

### 10.1 Video Codecs

| Codec | AirPlay 2 | Google Cast | Android API | Hardware Required |
|---|---|---|---|---|
| H.264 AVC (Baseline/Main) | ✅ Mandatory | ✅ Mandatory | API 16+ | Yes (MediaCodec) |
| H.264 AVC (High Profile, up to L5.2) | ✅ Mandatory | ✅ Mandatory | API 16+ | Yes |
| H.265 HEVC | ✅ Optional | ✅ Optional | API 21+ | Yes (capability check) |
| VP8 | ❌ Not used | ✅ Mandatory | API 16+ | Yes |
| VP9 | ❌ Not used | ✅ Optional | API 23+ | Yes |
| AV1 | ❌ Not used | ✅ Optional | API 29+ (SW), 31+ (HW) | Preferred HW |

**Runtime capability check** (applied for all optional codecs):
```kotlin
val codecList = MediaCodecList(MediaCodecList.SECURE_CODECS_ONLY)
val format = MediaFormat.createVideoFormat(mimeType, width, height)
val decoderName = codecList.findDecoderForFormat(format)
val isSupported = decoderName != null
```

### 10.2 Audio Codecs

| Codec | AirPlay 2 | Google Cast | Notes |
|---|---|---|---|
| LPCM 16-bit / 44.1–48 kHz | ✅ Mandatory (mirror) | ✅ Mandatory (WAV) | Via AudioTrack directly |
| AAC-LC | ✅ Mandatory (mirror) | ✅ Mandatory | MediaCodec `audio/mp4a-latm` |
| AAC-ELD | ✅ Mandatory (mirror) | — | Enhanced Low Delay variant |
| AAC-HE | — | ✅ Mandatory | High Efficiency AAC |
| ALAC | ✅ Mandatory (music) | — | Apple Lossless; Android MediaCodec `audio/alac` |
| MP3 | — | ✅ Mandatory | MediaCodec `audio/mpeg` |
| Opus | — | ✅ Optional | MediaCodec `audio/opus` (API 21+) |
| FLAC | — | ✅ Optional | MediaCodec `audio/flac` (API 21+) |
| AC-3 (Dolby Digital) | ✅ Optional (surround) | — | `AudioFormat.ENCODING_AC3` |
| E-AC-3 / Dolby Atmos (JOC) | ✅ Optional (surround) | ✅ Optional | `AudioFormat.ENCODING_E_AC3_JOC` |
| Dolby Digital Plus (E-AC3) | — | ✅ Optional | `AudioFormat.ENCODING_E_AC3` |

### 10.3 Container Formats

| Container | AirPlay 2 | Google Cast | Notes |
|---|---|---|---|
| MP4 / ISOBMFF | ✅ Required | ✅ Required | Standard media container |
| MOV | ✅ Required | — | Apple QuickTime container |
| M4V | ✅ Required | — | iTunes video container |
| WebM | — | ✅ Required | VP8/VP9/AV1 container |
| HLS | ✅ Required | ✅ Required | Adaptive bitrate streaming |
| DASH | — | ✅ Required | Adaptive bitrate streaming |
| RTP/RTSP | ✅ Required (mirror) | — | Live mirroring transport |

### 10.4 Maximum Resolution & HDR

| Protocol | Mandatory Max | Optional Max | HDR |
|---|---|---|---|
| AirPlay 2 | 1080p @ 60fps | 4K UHD @ 60fps | HDR10, Dolby Vision (optional, API 24+) |
| Google Cast | 1080p @ 60fps | 4K UHD @ 60fps | HDR10+ (optional) |

---

## 10.5 DLNA formats and ports

The DLNA sink list (`ProtocolInfoList`) advertises only what Android `MediaPlayer` decodes: video `mp4`, `x-matroska`, `webm`, `3gpp`, `mpeg`, `vnd.dlna.mpeg-tts`; audio `mpeg`, `mp4`, `x-m4a`, `aac`, `flac`, `x-wav`, `wav`, `ogg`; images `jpeg`, `png`, `gif`, `webp`; plus DLNA.ORG_PN profiles for MP4/H.264, MP3, AAC, JPEG and PNG. WMA/WMV and raw LPCM are deliberately absent (Windows transcodes or refuses).

Ports: SSDP UDP 1900 (multicast 239.255.255.250); HTTP TCP 49494 (`DlnaConstants.DEFAULT_HTTP_PORT`) with an OS-assigned fallback that is reflected in the SSDP LOCATION.

## 11. DRM / Copy Protection

### 11.1 HDCP (Miracast) — not applicable

The Miracast receiver was removed (ADR-004); no WFD/HDCP negotiation remains in PhairPlay.

### 11.2 Widevine & PlayReady (Google Cast)

Google Cast streams for DRM-protected content use **Widevine** (Google's DRM) or **PlayReady** (Microsoft's DRM). These are handled entirely by:
1. The **Google Cast SDK** (CastReceiverContext / MediaManager)
2. Android's **`MediaDrm` API** (used internally by the Cast SDK)

PhairPlay does **not** need to implement Widevine or PlayReady key exchange logic. The Cast SDK handles license acquisition, key delivery, and secure decryption. On devices without Widevine L1 (hardware-secured), content providers may restrict playback to Widevine L3 (software).

### 11.3 FairPlay (AirPlay)

**Status: Not implemented. Not planned for v1.**

FairPlay Streaming (FPS) is Apple's proprietary DRM for HLS content. A full evaluation is provided in `REQUIREMENTS.md §3`. Summary:

| Aspect | Assessment |
|---|---|
| Technical feasibility on Android | Not feasible — Apple has never released an Android KSM binary |
| Open-source compatibility | Incompatible — FPS license restricts distribution |
| Scope of impact | Only affects premium DRM-protected content (Apple TV+, iTunes purchases) |
| Unencrypted AirPlay | Fully supported — FairPlay only applies to licensed premium content |
| Workaround | None within scope — users must use unprotected content for AirPlay to PhairPlay |

AirPlay screen mirroring (which uses session AES-128-CTR encryption, **not** FairPlay) is fully supported. FairPlay only applies to streaming licensed premium video content directly to the receiver.
