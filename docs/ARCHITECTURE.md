# PhairPlay Architecture

This document explains how PhairPlay works — from the moment a macOS user clicks "AirPlay" to the moment video appears on the TV.

---

## How Does AirPlay 2 Work? (Simple Explanation)

Imagine you want to show your Mac's screen on your TV. AirPlay 2 makes this happen in three steps:

### Step 1: Your TV announces itself ("I'm here!")

When PhairPlay starts, it uses a technology called **mDNS** (Multicast DNS) — the same thing as Apple's "Bonjour" — to broadcast a message on your local network. This message says:

> "Hello! I'm a TV called 'My Living Room TV', I support AirPlay 2, and I'm listening on port 7000."

Your Mac hears this message automatically and adds the TV to its AirPlay menu.

### Step 2: Your Mac and TV shake hands ("Let's agree on how to stream")

When you click the TV in the AirPlay menu, your Mac opens a connection to port 7000 and starts a "negotiation" using a protocol called **RTSP** (Real Time Streaming Protocol). Think of it as a conversation:

- Mac: "Hello, what can you do?" → TV: "I can receive video and audio."
- Mac: "Great. I'll send H.264 video and AAC audio, encrypted with this key: [key]." → TV: "OK, got it."
- Mac: "Set up a channel for video on port X." → TV: "Ready."
- Mac: "Start streaming!" → TV: "Go ahead!"

### Step 3: Video and audio flow

After the handshake, your Mac starts sending video frames (H.264 encoded) and audio samples (AAC encoded) as **RTP packets** (tiny network packets). The TV:

1. Receives the packets
2. Decrypts the audio using the key from Step 2
3. Passes the video through the hardware decoder (GPU)
4. Displays the video full-screen
5. Plays the audio through the speakers

When you stop screen sharing, the Mac sends a "goodbye" message (RTSP TEARDOWN) and the TV returns to the waiting screen.

---

## Component Overview

### Orchestration & UI

| Component | File | What It Does |
|---|---|---|
| `PhairPlayApp` | `PhairPlayApp.kt` | App-level init (logging setup) |
| `MainActivity` | `MainActivity.kt` | Hosts the UI, manages app lifecycle |
| `PhairPlayService` | `service/PhairPlayService.kt` | ForegroundService — keeps receiver alive |
| `AirPlayReceiver` | `airplay/AirPlayReceiver.kt` | **Orchestrator** — starts/stops all sub-services |
| `WaitingScreen` | `ui/WaitingScreen.kt` | Idle UI — "Ready for AirPlay" |
| `StreamingScreen` | `ui/StreamingScreen.kt` | Video SurfaceView (aspect-fit letterbox) |
| `NowPlayingScreen` | `ui/NowPlayingScreen.kt` | Now-playing metadata + album artwork overlay |
| `PinScreen` | `ui/PinScreen.kt` | On-screen PIN entry for legacy SRP pairing |
| `SettingsFragment` | `ui/SettingsFragment.kt` | Mirror audio toggle, PIN-auth toggle, device name |
| `NetworkUtils` | `util/NetworkUtils.kt` | Reads device name, MAC, IP |
| `Base64Util` | `util/Base64Util.kt` | Pure-JVM Base64 (unit-testable, no android.util.Base64) |

### Discovery

| Component | File | What It Does |
|---|---|---|
| `MdnsService` | `airplay/MdnsService.kt` | mDNS advertising (`_airplay._tcp`, `_raop._tcp`) |
| `InfoResponder` | `airplay/handshake/InfoResponder.kt` | `GET /info` capability advertisement (binary plist) |

### RTSP & Session Control

| Component | File | What It Does |
|---|---|---|
| `RtspHandler` | `airplay/RtspHandler.kt` | **Full AirPlay 2 RTSP router** — all verbs, session lifecycle |
| `SdpParser` | `airplay/SdpParser.kt` | SDP codec/encryption/channel/rate parsing |
| `PlistCodec` | `airplay/handshake/PlistCodec.kt` | Apple binary plist encode/decode |
| `RtspMessages` | `airplay/RtspMessages.kt` | RTSP response builders |

### Pairing & FairPlay

| Component | File | What It Does |
|---|---|---|
| `PairingSession` | `airplay/handshake/PairingSession.kt` | HomeKit-style pair-setup/pair-verify (Ed25519/X25519) |
| `PairingKeys` | `airplay/handshake/PairingKeys.kt` | Ed25519 identity key generation and persistence |
| `PairingStore` | `airplay/handshake/PairingStore.kt` | Controller key persistence + failed-attempt lockout |
| `LegacyPairSetupPin` | `airplay/handshake/LegacyPairSetupPin.kt` | SRP-6a PIN pairing + AES-GCM key exchange |
| `FairPlay` | `airplay/handshake/FairPlay.kt` | fp-setup v2 (RAOP) and v3 (mirroring/Safari) key decryption |
| `RaopRsa` | `airplay/handshake/RaopRsa.kt` | Legacy rsaaeskey RSA-OAEP recovery (AirPort Express) |
| `fairplay_jni.c` | `cpp/fairplay_jni.c` | JNI bridge to libplayfair (null/length/OOM validated) |
| `playfair/` | `cpp/playfair/` | Reverse-engineered FairPlay C library (all ABIs) |

### Video

| Component | File | What It Does |
|---|---|---|
| `MirrorStreamServer` | `airplay/handshake/MirrorStreamServer.kt` | Interleaved RTP reassembly from RTSP TCP (`$` framing) |
| `MirrorCrypto` | `airplay/handshake/MirrorCrypto.kt` | AES-128-CTR decryption (keystream always advanced) |
| `VideoDecoder` | `airplay/VideoDecoder.kt` | MediaCodec H.264: SPS-driven reinit, self-heal, keyframe resync |
| `AirPlayVideoPlayer` | `airplay/AirPlayVideoPlayer.kt` | URL video mode (`/play` content) + transport controls |

### Audio

| Component | File | What It Does |
|---|---|---|
| `AudioStreamServer` | `airplay/handshake/AudioStreamServer.kt` | Mirror audio (type 96): UDP RTP, AES-128-CBC, AAC-ELD/LC, retransmit |
| `AlacDecoder` | `airplay/handshake/AlacDecoder.kt` | RAOP/SDP audio: AES-128-CBC + Apple ALAC; mute-on-bad-key guard |
| `BufferedAudioServer` | `airplay/handshake/BufferedAudioServer.kt` | AirPlay 2 buffered audio (type 103) — accepted, not yet played |
| `AudioPlayer` | `airplay/AudioPlayer.kt` | Low-level AudioTrack wrapper |
| `alac/` | `cpp/alac/` | Apple ALAC decoder (C++) + JNI bridge |
| `AirPlayNtpClient` | `airplay/handshake/AirPlayNtpClient.kt` | Apple NTP for A/V synchronisation |
| `TimingHandler` | `airplay/TimingHandler.kt` | NTP timing state |

### DLNA / UPnP MediaRenderer (`dlna/`)

| Component | File | What It Does |
|---|---|---|
| `DlnaReceiver` | `dlna/DlnaReceiver.kt` | **Orchestrator** — MulticastLock, wires SSDP, HTTP, services, eventing, renderer |
| `SsdpAdvertiser` / `SsdpMessages` | `dlna/SsdpAdvertiser.kt`, `SsdpMessages.kt` | SSDP alive/byebye NOTIFY and M-SEARCH replies |
| `DlnaHttpServer` / `DlnaRouter` | `dlna/DlnaHttpServer.kt`, `DlnaRouter.kt` | Raw HTTP/1.1 server; routes description, SCPD, SOAP, GENA, icon |
| `HttpRequestReader` / `HttpResponse` | `dlna/HttpRequest.kt`, `HttpResponse.kt` | Size-capped request parsing, sanitised responses |
| `DeviceDescription` / `scpd/*` | `dlna/DeviceDescription.kt`, `dlna/scpd/` | Root device XML (`X_DLNADOC` DMR-1.50) and the three SCPDs |
| `SoapDispatcher` / `SoapXml` / `SecureXml` | `dlna/SoapDispatcher.kt`, … | Hardened XML, SOAP envelopes, UPnP faults |
| `AvTransportService` / `RenderingControlService` / `ConnectionManagerService` | `dlna/*Service.kt` | The UPnP AV services (pure protocol logic) |
| `EventSubscriptions` / `LastChangeEncoder` | `dlna/EventSubscriptions.kt`, `LastChangeEncoder.kt` | GENA subscriptions, moderated `LastChange` events |
| `MediaRenderer` | `dlna/MediaRenderer.kt` | Session state machine: URI → player or photo, overlays, remote keys |
| `DlnaPlayer` / `PhotoFetcher` | `dlna/DlnaPlayer.kt`, `PhotoFetcher.kt` | MediaPlayer wrapper (video/audio); capped image download |
| `ProtocolInfoList` / `DidlLite` | `dlna/ProtocolInfoList.kt`, `DidlLite.kt` | Advertised formats + classification; DIDL-Lite metadata |

### Metadata & Remote

| Component | File | What It Does |
|---|---|---|
| `NowPlayingInfo` | `airplay/NowPlayingInfo.kt` | DMAP parser → title, artist, album, artwork |
| `DacpClient` | `airplay/DacpClient.kt` | `_dacp._tcp` discovery + reverse remote (TV remote → sender) |
| `StreamStats` | `airplay/StreamStats.kt` | Per-session RTP statistics (count, duplicates, queue drops) |

---

## How Does DLNA Work? (Simple Explanation)

Windows ("Cast to Device"), BubbleUPnP and VLC use UPnP instead of AirPlay:

1. **Announce:** `SsdpAdvertiser` multicasts `NOTIFY ssdp:alive` and answers `M-SEARCH`, pointing at `http://<tv-ip>:49494/description.xml`.
2. **Describe:** the control point fetches the device description and the three service descriptions, and subscribes to events (GENA).
3. **Control:** it POSTs SOAP actions — `SetAVTransportURI` (with DIDL-Lite metadata) then `Play`; later `Pause`, `Seek`, `Stop`, `SetVolume`.
4. **Fetch and play:** the TV downloads the media itself. `MediaRenderer` hands video/audio to `DlnaPlayer` (MediaPlayer) and images to `PhotoFetcher`; the same overlays as AirPlay are used.
5. **Report:** every state change is evented as `LastChange`, so the sender's progress bar and buttons follow the TV.

## Full Data Flow Diagram

```
  macOS (sender)                          Android TV (PhairPlay)
  ─────────────                           ──────────────────────

  [App starts on TV]
                                          PhairPlayApp.onCreate()
                                               │
                                          MainActivity.onCreate()
                                               │
                                          AirPlayReceiver.start()  (+ DlnaReceiver.start())
                                           ├── MdnsService.start()
                                           │    └── NsdManager.registerService(_airplay._tcp)
                                           │    └── NsdManager.registerService(_raop._tcp)
                                           └── RtspHandler.start()
                                                └── ServerSocket.accept() [waiting...]
                                          WaitingScreen shown

  ─────── DISCOVERY ─────────────────────────────────────────────────────

  [User opens AirPlay menu on Mac]

  mDNS query (multicast)  ──────────────► NsdManager responds automatically
  ◄── mDNS response ────────────────────── ("PhairPlay" at 192.168.1.x:7000)

  AirPlay device appears in macOS menu ✓

  ─────── HANDSHAKE ──────────────────────────────────────────────────────

  [User clicks "PhairPlay" in AirPlay menu]

  TCP connect → port 7000 ─────────────► RtspHandler.accept()
  OPTIONS rtsp://... ──────────────────► handleOptions() → 200 OK
  ANNOUNCE (SDP body) ─────────────────► handleAnnounce()
                                               │ parsesSDP:
                                               │  - H.264 codec params (SPS/PPS)
                                               │  - Audio codec (AAC-ELD/ALAC)
                                               │  - AES-128 key + IV (for audio)
                                               └─ → 200 OK
  SETUP (video channel) ───────────────► handleSetup() → 200 OK + port
  SETUP (audio channel) ───────────────► handleSetup() → 200 OK + port
  RECORD ──────────────────────────────► handleRecord()
                                               │
                                          onStreamingStarted() called
                                               │
                                          VideoDecoder.initialize(SPS, PPS)
                                          AudioPlayer.initialize(key, IV)
                                          StreamingScreen shown (WaitingScreen hidden)

  ─────── STREAMING ──────────────────────────────────────────────────────

  [macOS is now mirroring its screen]

  RTP video packet (TCP, interleaved) ─► RtspHandler reads RTP frames
                                               └── VideoDecoder.decodeNalUnit()
                                                    └── MediaCodec (hardware GPU)
                                                         └── → SurfaceView (display)

  RTP audio packet (UDP) ──────────────► AudioPlayer.playAudioPacket()
                                               ├── Strip RTP header
                                               ├── AES-128-CTR decrypt
                                               ├── Parse AAC/ALAC frame
                                               └── AudioTrack.write() → speakers

  NTP timing packet (UDP) ─────────────► TimingHandler (keeps A/V in sync)

  ─────── DISCONNECT ─────────────────────────────────────────────────────

  [User stops screen sharing on Mac]

  TEARDOWN rtsp://... ─────────────────► handleTeardown()
                                               │
                                          onStreamingStopped() called
                                               │
                                          VideoDecoder.release()
                                          AudioPlayer.release()
                                          StreamingScreen hidden
                                          WaitingScreen shown
                                          MdnsService.restart() (re-advertise)
```

---

## Design Decisions

### Why View-based UI instead of Jetpack Compose?

Jetpack Compose is modern, but it adds complexity for TV apps:
- Focus handling with D-pad is harder to get right in Compose
- SurfaceView (needed for low-latency video) has quirks in Compose
- The `AppCompat.Leanback` theme (required for TV launcher integration) works better with traditional Views

For v1.0, View-based UI with ConstraintLayout is simpler and more compatible with TV devices.

### Why no ViewModel/LiveData/Flow for state?

With only two states (WAITING, STREAMING), a ViewModel would add complexity without benefit. The state is managed directly in `AirPlayReceiver` with a callback to `MainActivity`. If the app grows to need more complex state, a ViewModel can be introduced later.

### Why Bouncy Castle for cryptography?

Android's built-in `javax.crypto` supports AES-128-CTR, but:
- The API for it is verbose
- SRP-6a (needed for future PIN pairing) is not available in Android's built-in crypto
- Bouncy Castle is a well-audited, widely-used crypto library

### Why raw sockets instead of a networking library?

AirPlay uses a custom RTSP variant with Apple extensions. No off-the-shelf HTTP/RTSP library handles these extensions correctly. Implementing the protocol with raw Java sockets is more work initially, but gives full control over the protocol behavior — which is necessary for compatibility.

### Why one Activity?

- Simpler lifecycle management
- The MediaCodec Surface doesn't need to be passed across Activity transitions
- TV apps typically use a single-Activity architecture anyway (Leanback recommends it)

---

## Security Architecture

### Input Validation Gates

Every external input passes through a validation gate before being processed:

```
Network bytes → [LENGTH CHECK] → [FORMAT CHECK] → [RANGE CHECK] → safe internal data
```

1. **LENGTH CHECK**: All RTSP messages are limited to 64 KB. Buffer sizes are checked before any array access.
2. **FORMAT CHECK**: RTSP method names are matched against an allowlist. Unknown methods return 501.
3. **RANGE CHECK**: Numeric values from the network (port numbers, timestamps) are validated to be in expected ranges.

### No Sensitive Data in Logs

The `Logger` wrapper is designed to be extended to redact IP addresses and keys from debug logs before a release build is published.

### Principle of Least Privilege

The app requests only 4 permissions, all of which are strictly necessary. No storage, no camera, no microphone.
