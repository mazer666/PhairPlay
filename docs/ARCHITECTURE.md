# PhairPlay Architecture

This document explains how PhairPlay works — from the moment a sender selects the
television to the moment the picture appears on the screen.

It is the **map of the source tree**: what every package is for, where the
boundaries between the protocols run, how a session flows through the code and
where an untrusted input is checked. The file names below are the real ones;
every path in this document exists.

If you want to know *which file to open* for a concrete change, use
[`WHERE_TO_CHANGE.md`](WHERE_TO_CHANGE.md). If you want to know *what the
acceptance criteria and the performance budgets are*, use
[`spec/ACCEPTANCE_CRITERIA.md`](spec/ACCEPTANCE_CRITERIA.md).

---

## How Does AirPlay 2 Work? (Simple Explanation)

Imagine you want to show your Mac's screen on your TV. AirPlay 2 makes this
happen in three steps:

### Step 1: Your TV announces itself ("I'm here!")

When PhairPlay starts, it uses a technology called **mDNS** (Multicast DNS) — the
same thing as Apple's "Bonjour" — to broadcast a message on your local network.
This message says:

> "Hello! I'm a TV called 'My Living Room TV', I support AirPlay 2, and I'm listening on port 7000."

Your Mac hears this message automatically and adds the TV to its AirPlay menu.

### Step 2: Your Mac and TV shake hands ("Let's agree on how to stream")

When you click the TV in the AirPlay menu, your Mac opens a connection to port 7000
and starts a "negotiation" using a protocol called **RTSP** (Real Time Streaming
Protocol). Think of it as a conversation:

- Mac: "Hello, what can you do?" → TV: "I can receive video and audio."
- Mac: "Great. I'll send H.264 video and AAC audio, encrypted with this key: [key]." → TV: "OK, got it."
- Mac: "Set up a channel for video on port X." → TV: "Ready."
- Mac: "Start streaming!" → TV: "Go ahead!"

### Step 3: Video and audio flow

After the handshake, your Mac starts sending video frames (H.264 encoded) and audio
samples (AAC encoded) as **RTP packets** (tiny network packets). The TV:

1. Receives the packets
2. Decrypts the audio using the key from Step 2
3. Passes the video through the hardware decoder (GPU)
4. Displays the video full-screen
5. Plays the audio through the speakers

When you stop screen sharing, the Mac sends a "goodbye" message (RTSP TEARDOWN) and
the TV returns to the waiting screen.

---

## The map of the source tree

```text
app/src/main/kotlin/com/phairplay/
├── MainActivity.kt              the single Activity: navigation panel + fragments + overlays
├── PhairPlayApp.kt              application class: logging init
├── airplay/                     the AirPlay 2 stack (RTSP control plane + media)
│   └── handshake/               pairing, FairPlay, the mirror and audio streams
├── dlna/                        the UPnP / DLNA MediaRenderer stack
├── miracast/                   the Miracast (Wi-Fi Display) stack
├── media/                       what this device can decode
├── service/                     the foreground service and its four helpers
├── settings/                    the settings model and its persistence
├── ui/                          the screens and their fragments
└── util/                        logging, redaction, address policies, network helpers
```

`app/src/googletv/` and `app/src/firetv/` hold the **flavour-specific** code
(Google Cast). `app/src/main/cpp/` holds the **native** code (FairPlay and ALAC).
The JVM test module lives outside the app: `test-runner/` — see
[`TESTING.md`](TESTING.md).

The production tree holds **114 Kotlin files**; the test tree under
`app/src/test/kotlin/` holds **68** more — 65 test classes and three shared
helpers.

---

## Component map

### Orchestration, service and UI

| Component | File | What it does |
|---|---|---|
| `PhairPlayApp` | `PhairPlayApp.kt` | Application class: initialises Timber / the logger |
| `MainActivity` | `MainActivity.kt` | The single Activity: nav panel, fragment container, full-screen overlay |
| `FragmentNavigator` | `ui/FragmentNavigator.kt` | Owns the nav panel selection and the content fragment swap |
| `ServiceConnectionBinder` | `ui/ServiceConnectionBinder.kt` | Owns the `ServiceConnection` to the service and mirrors its state flows |
| `PhairPlayService` | `service/PhairPlayService.kt` | ForegroundService — a thin coordinator that publishes the state |
| `ReceiverSupervisor` | `service/ReceiverSupervisor.kt` | Owns the four protocol receivers and their start/stop lifecycle |
| `ServiceStateBus` | `service/ServiceStateBus.kt` | Owns every published state flow (service, protocols, connection, photo, now-playing, PIN) |
| `ServiceNotificationManager` | `service/ServiceNotificationManager.kt` | Builds and updates the foreground-service notification |
| `NetworkRecoveryMonitor` | `service/NetworkRecoveryMonitor.kt` | Re-advertises over mDNS when the network comes back |
| `ServiceController` | `service/ServiceController.kt` | Sends the start / stop / restart intents to the service |
| `ServiceState` | `service/ServiceState.kt` | The lifecycle state, the per-protocol state, the active connection |
| `BootReceiver` | `service/BootReceiver.kt` | Starts the service after a reboot |
| `PhotoFrame` | `service/PhotoFrame.kt` | The latest still picture received via AirPlay `/photo` |
| `HomeFragment` | `ui/HomeFragment.kt` | The Home screen: one status card per protocol + start / stop / restart |
| `SettingsFragment` | `ui/SettingsFragment.kt` | The Settings screen: device name, protocol switches, audio and PIN toggles |
| `WaitingScreen` | `ui/WaitingScreen.kt` | Idle UI — "ready to receive" |
| `StreamingScreen` | `ui/StreamingScreen.kt` | The video surface (aspect-fit letterbox / pillarbox) |
| `NowPlayingScreen` | `ui/NowPlayingScreen.kt` | Now-playing metadata + album artwork (audio without video) |
| `PhotoScreen` | `ui/PhotoScreen.kt` | Full-screen picture for the AirPlay photo endpoint |
| `PinScreen` | `ui/PinScreen.kt` | On-screen PIN entry for the legacy SRP pairing |
| `VideoAspectFit` | `ui/VideoAspectFit.kt` | The aspect-fit matrix computation for the video surface |

### AirPlay 2 — discovery and session control

| Component | File | What it does |
|---|---|---|
| `AirPlayReceiver` | `airplay/AirPlayReceiver.kt` | **Orchestrator** of the AirPlay pipeline: mDNS + RTSP + the media sessions |
| `MdnsService` | `airplay/MdnsService.kt` | Owns the advertisement lifecycle (start / stop / re-advertise) |
| `MdnsServiceRegistrar` | `airplay/MdnsServiceRegistrar.kt` | Registers `_airplay._tcp` and `_raop._tcp` independently of each other |
| `MdnsRegistrationTracker` | `airplay/MdnsRegistrationTracker.kt` | The per-service registration bookkeeping (attempt counter, registered flag, failure count) |
| `MdnsServiceName` | `airplay/MdnsServiceName.kt` | Sanitises the advertised device name (control characters, 63-character limit) |
| `RtspHandler` | `airplay/RtspHandler.kt` | The **public entry point** of the RTSP control protocol — a stable façade |
| `RtspServer` | `airplay/RtspServer.kt` | Owns the RTSP control socket: bind, accept, read, write (1 MB message cap) |
| `RtspRequestReader` | `airplay/RtspRequestReader.kt` | Reads and parses one RTSP request |
| `RtspRequestRouter` | `airplay/RtspRequestRouter.kt` | Dispatches one parsed request to the handler that answers it |
| `RtspVerbHandler` | `airplay/RtspVerbHandler.kt` | The classic verbs: OPTIONS, ANNOUNCE, SETUP, RECORD, TEARDOWN, PAUSE, `/photo` |
| `RtspPairingHandler` | `airplay/RtspPairingHandler.kt` | `POST /pair-setup`, `/pair-verify`, `/pair-pin-start`, `/pair-setup-pin` |
| `RtspMirrorHandler` | `airplay/RtspMirrorHandler.kt` | The AirPlay 2 mirroring SETUP and the FairPlay `fp-setup` exchange |
| `RtspParameterHandler` | `airplay/RtspParameterHandler.kt` | `GET_PARAMETER` / `SET_PARAMETER`: volume, artwork, now-playing metadata |
| `RtspDeviceInfoHandler` | `airplay/RtspDeviceInfoHandler.kt` | `GET /info`, `GET /server-info`, `POST /feedback`, buffered-audio verbs |
| `RtspVideoUrlHandler` | `airplay/RtspVideoUrlHandler.kt` | The URL video mode: `POST /play`, `/rate`, `/scrub`, `/stop`, `GET /playback-info` |
| `RtspSessionState` | `airplay/RtspSessionState.kt` | The shared mutable state of one control connection |
| `RtspRequest` / `RtspResponse` | `airplay/RtspMessages.kt` | The two data types of the RTSP control plane — a parsed request and the answer to it |
| `OCTET_STREAM` | `airplay/RtspProtocolConstants.kt` | The shared constant for `application/octet-stream` (the pairing and FairPlay bodies) |
| `RtspRequest` extensions | `airplay/RtspRequestExtensions.kt` | Three top-level helpers every handler needs on a parsed request |
| `SdpParser` | `airplay/SdpParser.kt` | SDP codec / encryption / channel / rate parsing |
| `PhotoHandler` | `airplay/PhotoHandler.kt` | Validates the `/photo` payload (25 MB cap) |
| `DacpClient` | `airplay/DacpClient.kt` | `_dacp._tcp` discovery + the reverse remote (TV remote → sender) |
| `StreamStats` | `airplay/StreamStats.kt` | Per-session RTP statistics (count, duplicates, queue drops) |
| `TimingHandler` | `airplay/TimingHandler.kt` | The NTP timing state |

### AirPlay 2 — the three media sessions

The AirPlay stack has **three different ways** to deliver media. They share the
RTSP control plane but nothing else.

| Component | File | What it does |
|---|---|---|
| `AirPlayMediaPipeline` | `airplay/AirPlayMediaPipeline.kt` | The classic pipeline: `VideoDecoder` (H.264 → Surface) + `AudioPlayer` + the audio UDP receiver |
| `AirPlayMirrorSession` | `airplay/AirPlayMirrorSession.kt` | The AirPlay 2 mirroring streams: FairPlay/ECDH keys, event channel, NTP, mirror data and audio servers |
| `AirPlayUrlSession` | `airplay/AirPlayUrlSession.kt` | The URL video mode: wraps `AirPlayVideoPlayer` and the transport controls |
| `NowPlayingState` | `airplay/NowPlayingState.kt` | The "now playing" metadata and the audio / video flags behind it |
| `VideoDecoder` | `airplay/VideoDecoder.kt` | MediaCodec H.264: SPS-driven reinit, self-heal, keyframe resync |
| `AirPlayVideoPlayer` | `airplay/AirPlayVideoPlayer.kt` | URL video playback + the transport controls (play / pause / scrub / stop) |
| `AudioPlayer` | `airplay/AudioPlayer.kt` | Low-level `AudioTrack` wrapper, AES-128-CTR decryption |

### AirPlay 2 — pairing, FairPlay, media streams

| Component | File | What it does |
|---|---|---|
| `PairingSession` | `airplay/handshake/PairingSession.kt` | HomeKit-style pair-setup / pair-verify (Ed25519 / X25519) |
| `PairingKeys` | `airplay/handshake/PairingKeys.kt` | Ed25519 identity key generation and persistence |
| `PairingStore` | `airplay/handshake/PairingStore.kt` | Controller key persistence + the failed-attempt lockout |
| `LegacyPairSetupPin` | `airplay/handshake/LegacyPairSetupPin.kt` | SRP-6a PIN pairing + AES-GCM key exchange |
| `FairPlay` | `airplay/handshake/FairPlay.kt` | fp-setup v2 (RAOP) and v3 (mirroring / Safari) key decryption |
| `RaopRsa` | `airplay/handshake/RaopRsa.kt` | Legacy `rsaaeskey` RSA-OAEP recovery (AirPort Express) |
| `InfoResponder` | `airplay/handshake/InfoResponder.kt` | `GET /info` capability advertisement (binary plist) |
| `PlistCodec` | `airplay/handshake/PlistCodec.kt` | Apple binary plist encode / decode |
| `MirrorStreamServer` | `airplay/handshake/MirrorStreamServer.kt` | Interleaved RTP reassembly from the RTSP TCP connection (`$` framing) |
| `MirrorCrypto` | `airplay/handshake/MirrorCrypto.kt` | AES-128-CTR decryption (the keystream is always advanced) |
| `MirrorFrameQueue` | `airplay/handshake/MirrorFrameQueue.kt` | The bounded FIFO of mirror frames (90 items **and** 32 MB) |
| `RtpInterleaved` | `airplay/RtpInterleaved.kt` | The `$` framing and the FU-A reassembly (8 MB cap per unit) |
| `AudioStreamServer` | `airplay/handshake/AudioStreamServer.kt` | Mirror audio (type 96): UDP RTP, AES-128-CBC, AAC-ELD/LC, retransmit |
| `AudioReorderBuffer` | `airplay/handshake/AudioReorderBuffer.kt` | Puts arriving audio packets back in order, suppresses duplicates, asks for resends |
| `AudioPlaybackEngine` | `airplay/handshake/AudioPlaybackEngine.kt` | Decrypts, decodes and plays the queued audio frames (one thread owns codec **and** track) |
| `AlacDecoder` | `airplay/handshake/AlacDecoder.kt` | RAOP/SDP audio: AES-128-CBC + Apple ALAC; mute-on-bad-key guard |
| `BufferedAudioServer` | `airplay/handshake/BufferedAudioServer.kt` | AirPlay 2 buffered audio (type 103) — accepted, **not yet played** |
| `RaopControlChannel` | `airplay/handshake/RaopControlChannel.kt` | The one-method sink for the RAOP resend request (type 0x55) |
| `AirPlayNtpClient` | `airplay/handshake/AirPlayNtpClient.kt` | Apple NTP for the A/V synchronisation |
| `H264SpsParser` | `airplay/H264SpsParser.kt` | The H.264 SPS parser (the resolution of the stream) |
| `fairplay_jni.c` | `cpp/fairplay_jni.c` | The JNI bridge to libplayfair (null / length / OOM validated) |
| `playfair/` | `cpp/playfair/` | The reverse-engineered FairPlay C library (all ABIs) |
| `alac/` | `cpp/alac/` | The Apple ALAC decoder (C++) + its JNI bridge `cpp/alac_jni.cpp` |

### DLNA / UPnP MediaRenderer

| Component | File | What it does |
|---|---|---|
| `DlnaReceiver` | `dlna/DlnaReceiver.kt` | Ties the UPnP control plane to the Android media stack |
| `SsdpAdvertiser` | `dlna/SsdpAdvertiser.kt` | Owns the SSDP socket: the advertisement and the `M-SEARCH` answers |
| `SsdpResponder` | `dlna/SsdpResponder.kt` | Decides which answer belongs to which search target (no socket code) |
| `SsdpMessages` | `dlna/SsdpMessages.kt` | Builds and classifies the SSDP messages (line-based header parsing) |
| `DlnaDescriptionServer` | `dlna/DlnaDescriptionServer.kt` | The HTTP server for the device description, the SCPDs and the control endpoints |
| `DlnaDescriptionBuilder` | `dlna/DlnaDescriptionBuilder.kt` | Builds the device descriptor and the three service descriptions |
| `DlnaSoapDispatcher` | `dlna/DlnaSoapDispatcher.kt` | Routes a SOAP control request to the service that owns the action |
| `DlnaAvTransportService` | `dlna/DlnaAvTransportService.kt` | The `AVTransport:1` action set and the transport state machine |
| `DlnaTransportState` | `dlna/DlnaTransportState.kt` | The mutable state of the AVTransport instance |
| `DlnaPlaybackController` | `dlna/DlnaPlaybackController.kt` | The interface between the control plane and the media stack |
| `DlnaProtocolInfo` | `dlna/DlnaProtocolInfo.kt` | The `Source` / `Sink` protocol lists of `GetProtocolInfo` |
| `DlnaServiceCatalog` | `dlna/DlnaServiceCatalog.kt` | The catalogue of the services the renderer offers |
| `DlnaTimeFormat` | `dlna/DlnaTimeFormat.kt` | Renders durations and positions in the literal UPnP formats (`H:MM:SS(.mmm)`) |
| `DlnaMediaUri` | `dlna/DlnaMediaUri.kt` | Validates the media URI a control point hands to `SetAVTransportURI` |
| `DlnaMediaMetadata` | `dlna/DlnaMediaMetadata.kt` | Parses the DIDL-Lite metadata document of a media URI |
| `DlnaMediaType` | `dlna/DlnaMediaType.kt` | Decides the media kind (video / audio / image) of a URI |
| `DlnaImageDecoder` | `dlna/DlnaImageDecoder.kt` | Decodes a photo payload with a 20 MB cap and downsampling |
| `DlnaNetworkAddress` | `dlna/DlnaNetworkAddress.kt` | Picks the IPv4 address the renderer announces |
| `GenaEventManager` | `dlna/GenaEventManager.kt` | The GENA subscription registry and the `LastChange` delivery |
| `GenaCallbackPolicy` | `dlna/GenaCallbackPolicy.kt` | The SSRF guard for the event callback address |
| `GenaLastChange` | `dlna/GenaLastChange.kt` | Builds the nested `LastChange` documents |
| `SecureSoapXml` | `dlna/SecureSoapXml.kt` | The only XML parser the control plane is allowed to use |
| `HttpRequestReader` | `dlna/HttpRequestReader.kt` | Reads one HTTP request with hard limits on size and time |
| `SoapResponses` | `dlna/SoapResponses.kt` | Builds the SOAP envelopes (success and fault) |
| `SoapResult` | `dlna/SoapResult.kt` | The result of one SOAP action |
| `DlnaHttpResponses` | `dlna/DlnaHttpResponses.kt` | Writes the HTTP answers of the description server |
| `DialAppCatalog` | `dlna/DialAppCatalog.kt` | The DIAL documents (`/dial/`, `/dial/<AppId>`) for Samsung Smart View |

### Miracast (Wi-Fi Display)

| Component | File | What it does |
|---|---|---|
| `MiracastReceiver` | `miracast/MiracastReceiver.kt` | The Wi-Fi Direct advertisement and the session lifecycle |
| `WfdRtspServer` | `miracast/WfdRtspServer.kt` | The WFD RTSP control server on port 7236 |

### Media, settings and utilities

| Component | File | What it does |
|---|---|---|
| `MediaCapabilities` | `media/MediaCapabilities.kt` | Detects what this device can decode |
| `AppSettings` | `settings/AppSettings.kt` | The immutable model of every user-configurable setting |
| `SettingsRepository` | `settings/SettingsRepository.kt` | Persists the settings (DataStore) |
| `Logger` | `util/Logger.kt` | The Timber wrapper — every message passes through the redactor |
| `LogRedactor` | `util/LogRedactor.kt` | Removes IP hosts, MAC halves, UUIDs and URL credentials from log messages |
| `LogRedactionMode` | `util/LogRedactionMode.kt` | The switch for the redaction (`ON` by default, `OFF` for a local debug session) |
| `HostAddressPolicy` | `util/HostAddressPolicy.kt` | The address rules for a URL that the receiver fetches itself |
| `FetchableUrlPolicy` | `util/FetchableUrlPolicy.kt` | The rule for a media URL that a peer asked the receiver to fetch |
| `SafeBitmapDecoder` | `util/SafeBitmapDecoder.kt` | Decodes a picture from the network with a pixel budget |
| `NetworkUtils` | `util/NetworkUtils.kt` | Reads the device name, the MAC address and the IP address |
| `Base64Util` | `util/Base64Util.kt` | Pure-JVM Base64 (unit-testable, no `android.util.Base64`) |

### Flavour-specific code

| Component | File | What it does |
|---|---|---|
| `CastReceiver` | `googletv/kotlin/com/phairplay/cast/CastReceiver.kt` | The Google Cast receiver (Google TV flavour) |
| `CastReceiverOptionsProvider` | `googletv/kotlin/com/phairplay/cast/CastReceiverOptionsProvider.kt` | The Cast options provider (Google TV flavour) |
| `CastReceiver` | `firetv/kotlin/com/phairplay/cast/CastReceiver.kt` | The Google Cast receiver (Fire TV flavour) |

---

## The protocol separation (ADR-001)

PhairPlay runs **four independent receivers**. ADR-001
([`decisions/ADR-001-multi-protocol.md`](decisions/ADR-001-multi-protocol.md))
and its amendment in ADR-004
([`decisions/ADR-004-dlna-mediarenderer-roadmap.md`](decisions/ADR-004-dlna-mediarenderer-roadmap.md))
define the rule:

> Every protocol is its own package, its own ports, its own state and its own
> switch in Settings. One protocol can fail without affecting the others, and
> one protocol can be switched off without touching the other three.

| Receiver | Package | Network surface | Settings switch |
|---|---|---|---|
| AirPlay 2 | `com.phairplay.airplay` | mDNS (`_airplay._tcp`, `_raop._tcp`) + RTSP/TCP 7000 + RTP/UDP | `AppSettings.airPlayEnabled` |
| Miracast (WFD) | `com.phairplay.miracast` | Wi-Fi Direct + WFD RTSP/TCP 7236 | `AppSettings.miracastEnabled` |
| Google Cast | `com.phairplay.cast` (flavour source set) | the Cast SDK | `AppSettings.castEnabled` |
| DLNA / UPnP | `com.phairplay.dlna` | SSDP/UDP 1900 (multicast) + an ephemeral HTTP port | `AppSettings.dlnaEnabled` |

### What the separation means in the code

1. **No protocol imports another protocol's internals.** The only deliberate
   cross-package calls are narrow and reviewed:
   - `dlna/DlnaReceiver.kt` → `airplay/AirPlayVideoPlayer.kt` — the DLNA renderer
     reuses the *media player*; it does not reuse the AirPlay protocol.
   - `dlna/DlnaImageDecoder.kt` → `util/SafeBitmapDecoder.kt` — the DLNA photo
     path and the AirPlay photo path share the downsampling arithmetic.
   - `util/HostAddressPolicy.kt` → `dlna/GenaCallbackPolicy.kt` — the address
     policy reuses the strict IPv4 literal parser of the GENA policy.
2. **The shared code lives in `util/`, not in a protocol package.** The address
   policies, the picture decoder, the logger and the network helpers are the
   only code two protocols may both use.
3. **`ReceiverSupervisor` is the only place that knows all four.** It starts and
   stops them; it never merges their states. Each receiver reports its own
   `ProtocolState` and the Home screen renders one card per protocol.
4. **The UI is protocol-agnostic.** `HomeFragment` shows four cards and nothing
   that is specific to a protocol beyond its name and its icon.

---

## The entry points

There are exactly three places where the outside world enters the app.

```
  Android system
        │
        ├──► BootReceiver.onReceive()  ──► ServiceController.start()
        │
        ├──► MainActivity.onCreate()    ──► ServiceController.start()
        │            │
        │            └──► ServiceConnectionBinder.bind()
        │                     └──► PhairPlayService (bound)
        │                              └──► ServiceStateBus (StateFlows)
        │                                       └──► HomeFragment / SettingsFragment
        │                                                        └──► overlays
        │
        └──► ServiceController  ──► Intent(ACTION_START | ACTION_STOP | ACTION_RESTART)
                     │
                     ▼
            PhairPlayService (ForegroundService)
                     │
                     ├──► ServiceStateBus          (what the UI observes)
                     ├──► ServiceNotificationManager (the foreground notification)
                     ├──► NetworkRecoveryMonitor    (re-advertise after a network outage)
                     └──► ReceiverSupervisor        (the four protocol receivers)
                              ├──► AirPlayReceiver
                              ├──► MiracastReceiver
                              ├──► CastReceiver
                              └──► DlnaReceiver
```

- **`PhairPlayService`** (`service/PhairPlayService.kt`) is the foreground
  service. It is deliberately a **thin coordinator**: it owns the Android service
  lifecycle and delegates everything else to four helpers — `ServiceStateBus`,
  `ReceiverSupervisor`, `ServiceNotificationManager` and
  `NetworkRecoveryMonitor`.
- **`ReceiverSupervisor`** (`service/ReceiverSupervisor.kt`) is the only class
  that creates, starts and stops the four protocol receivers. It translates their
  callbacks into the published state and into the notification text.
- **`MainActivity`** (`MainActivity.kt`) is the only Activity. It owns the nav
  panel and the fragment container; `FragmentNavigator` does the navigation and
  `ServiceConnectionBinder` does the service binding.

---

## Data flow: an AirPlay mirroring session

```
  macOS (sender)                          Android TV (PhairPlay)
  ─────────────                           ──────────────────────

  [the service starts]
                                          ReceiverSupervisor.startAirPlay()
                                            └──► AirPlayReceiver.start()
                                                 ├──► MdnsService.start()
                                                 │      └──► MdnsServiceRegistrar
                                                 │           ├── _airplay._tcp
                                                 │           └── _raop._tcp
                                                 └──► RtspHandler.start()
                                                        └──► RtspServer.runServer()
  ─────── DISCOVERY ──────────────────────────────────────────────────────────

  mDNS query ───────────────────────────► NsdManager answers
  ◄── "PhairPlay" at 192.168.1.x:7000

  ─────── HANDSHAKE (all on the RTSP socket, port 7000) ─────────────────────

  TCP connect ─────────────────────────► RtspServer.accept()
  OPTIONS ────────────────────────────► RtspRequestReader.read()
                                          └──► RtspRequestRouter.routeRequest()
                                               └──► RtspVerbHandler  → 200 OK
  ANNOUNCE (SDP) ─────────────────────► RtspVerbHandler.handleAnnounce()
                                          └──► SdpParser
                                               └── 200 OK
  POST /pair-verify (plist) ──────────► RtspRequestRouter
                                          └──► RtspPairingHandler → 200 OK

  ── the classic path (RTSP + SDP) ───────────────────────────────────────────

  SETUP ───────────────────────────────► RtspVerbHandler.handleSetup() → 200 OK + port
  RECORD ─────────────────────────────► RtspVerbHandler.handleRecord()
                                          └──► onStreamingStarted()
                                               └──► AirPlayReceiver
                                                    └──► AirPlayMediaPipeline
                                                         ├──► VideoDecoder (MediaCodec)
                                                         └──► AudioPlayer  (AudioTrack)

  ── the AirPlay 2 mirroring path (binary plist) ─────────────────────────────

  SETUP (mirror, plist) ──────────────► RtspRequestRouter
                                          └──► RtspMirrorHandler
                                               ├──► FairPlay (fp-setup)
                                               ├──► onMirrorStreamStart / onMirrorAudioStart
                                               │    └──► AirPlayMirrorSession
                                               │         ├──► MirrorStreamServer (one per stream)
                                               │         │      ├──► MirrorCrypto (AES-128-CTR)
                                               │         │      ├──► MirrorFrameQueue (90 / 32 MB)
                                               │         │      └──► VideoDecoder (MediaCodec)
                                               │         ├──► AudioStreamServer (UDP)
                                               │         │      └──► AudioReorderBuffer
                                               │         │           └──► AudioPlaybackEngine
                                               │         └──► AirPlayNtpClient (NTP)
                                               └── 200 OK + the stream ports

  ─────── STREAMING ─────────────────────────────────────────────────────────

  RTP video (TCP, interleaved) ──────► RtspServer reads the `$` frames
                                          └──► RtpInterleaved (FU-A cap: 8 MB)
                                               └──► RtspHandler.onVideoNalUnit
                                                    └──► VideoDecoder.decodeNalUnit()
                                                         └──► SurfaceView

  RTP video (the mirror socket) ──────► MirrorStreamServer
                                          └──► MirrorCrypto → MirrorFrameQueue
                                               └──► VideoDecoder → SurfaceView

  RTP audio (UDP) ───────────────────► AudioStreamServer
                                          └──► AudioReorderBuffer
                                               └──► AudioPlaybackEngine
                                                    └──► AudioTrack.write()

  SET_PARAMETER (metadata) ──────────► RtspParameterHandler
                                          └──► NowPlayingState → the UI

  ─────── DISCONNECT ────────────────────────────────────────────────────────

  TEARDOWN ───────────────────────────► RtspVerbHandler.handleTeardown()
                                          └──► onStreamingStopped()
                                               └──► the media pipeline releases
                                                    the decoder and the track
```

The **URL video mode** (`POST /play`) takes a different route from the same
socket: `RtspRequestRouter` → `RtspVideoUrlHandler` → `AirPlayUrlSession` →
`AirPlayVideoPlayer`. Before the URL is handed to the player it must pass
`FetchableUrlPolicy` — see the security section below.

---

## Data flow: a DLNA playback

```
  Windows "Cast to Device" / VLC / a UPnP app      Android TV (PhairPlay)
  ─────────────────────────────────────────────      ──────────────────────

  [the service starts]
                                               ReceiverSupervisor.startDlna()
                                                 └──► DlnaReceiver.start()
                                                      ├──► DlnaDescriptionServer.start()
                                                      └──► SsdpAdvertiser.start()
                                                           └──► SSDP NOTIFY on
                                                                239.255.255.250:1900

  ─────── DISCOVERY ────────────────────────────────────────────────────────

  M-SEARCH * HTTP/1.1 ──────────────► SsdpAdvertiser receives the datagram
                                          └──► SsdpResponder
                                               └── three datagrams for "ssdp:all":
                                                   upnp:rootdevice,
                                                   the device type, the UDN
  ◄── 200 OK × 3 (LOCATION, USN, ST)

  GET http://<tv>:<port>/description.xml ► DlnaDescriptionServer
                                          └──► HttpRequestReader (bounded)
                                               └──► DlnaDescriptionBuilder
  ◄── the device descriptor + the three SCPDs

  ─────── CONTROL ──────────────────────────────────────────────────────────

  POST /upnp/control/AVTransport1 ───► DlnaDescriptionServer
                                          └──► HttpRequestReader
                                               └──► DlnaSoapDispatcher
                                                    └──► SecureSoapXml.parse()
                                                         └──► DlnaAvTransportService
                                                              ├──► DlnaMediaUri.validate()
                                                              │     └──► HostAddressPolicy
                                                              ├──► DlnaTransportState
                                                              └──► DlnaPlaybackController
  ◄── 200 OK + a SOAP envelope (SoapResponses.ok / .fault)

  SUBSCRIBE (callback) ─────────────► DlnaSoapDispatcher → the description server
                                          └──► GenaEventManager.subscribe()
                                               └──► GenaCallbackPolicy  ← SSRF guard

  ─────── PLAYBACK ─────────────────────────────────────────────────────────

  SetAVTransportURI + Play ─────────► DlnaAvTransportService
                                          └──► DlnaReceiver.onPlay()
                                               ├── video / audio → AirPlayVideoPlayer
                                               │                    (the media player)
                                               └── image → DlnaReceiver.fetchBounded()
                                                            (20 MB cap)
                                                         └──► DlnaImageDecoder
                                                              └──► SafeBitmapDecoder
                                               └──► ProtocolState.CONNECTED

  ─────── EVENTING ─────────────────────────────────────────────────────────

  the transport state changes ───────► DlnaAvTransportService.publishTransportState()
                                          └──► GenaLastChange (the nested document)
                                               └──► GenaEventManager.publish()
                                                    └──► a raw socket writes the
                                                         HTTP NOTIFY to the callback

  ─────── STOP ─────────────────────────────────────────────────────────────

  the service stops ─────────────────► DlnaReceiver.stop()
                                          ├──► SsdpAdvertiser.stop()  (the byebye)
                                          ├──► DlnaDescriptionServer.stop()
                                          └──► the media player is released
```

---

## Security architecture

### Where an input is checked

Every value that arrives from the network is untrusted. The checks live in
**dedicated, unit-tested classes** — not in the socket code — so that they can be
reviewed and proven on their own.

| Guard | File | What it refuses | Called from |
|---|---|---|---|
| `FetchableUrlPolicy` | `util/FetchableUrlPolicy.kt` | Every scheme except `http` / `https`, control characters, URLs longer than 4096 characters, and every host that `HostAddressPolicy` refuses | `airplay/RtspVideoUrlHandler.kt` (the AirPlay URL mode) |
| `HostAddressPolicy` | `util/HostAddressPolicy.kt` | The loopback, `0.0.0.0/8`, the link-local range (and with it the cloud metadata service `169.254.169.254`), the multicast and broadcast ranges, `240.0.0.0/4`, the further metadata addresses and names, the decimal / octal / hexadecimal notations of an IPv4 address, and every URI with a user info part | `util/FetchableUrlPolicy.kt` and `dlna/DlnaMediaUri.kt` (the DLNA media URI) |
| `GenaCallbackPolicy` | `dlna/GenaCallbackPolicy.kt` | Every callback that is not a literal private IPv4 address in the local subnet — loopback, link-local, multicast, broadcast, public addresses, host names, obfuscated notations, foreign schemes | `dlna/GenaEventManager.kt` (the GENA subscription) |
| `SecureSoapXml` | `dlna/SecureSoapXml.kt` | DOCTYPE declarations, external entities, entity expansion (billion laughs), documents deeper than 32 levels and documents larger than 64 KB — and it fails **closed** if the parser cannot be hardened | `dlna/DlnaSoapDispatcher.kt` and `dlna/DlnaMediaMetadata.kt` |
| `SafeBitmapDecoder` | `util/SafeBitmapDecoder.kt` | Pictures whose header announces more than 4096 px on the longest edge or more than 4096 × 4096 pixels; everything else is downsampled with an `inSampleSize` | `ui/PhotoScreen.kt`, `ui/NowPlayingScreen.kt` and `dlna/DlnaImageDecoder.kt` |

What is **not** refused, on purpose: a **public** address in a media URL stays
allowed (the sender decides which medium the owner wants to see, and PhairPlay
itself sends nothing), and a **host name** is not resolved (resolving it would
send a DNS query from the television). Both are documented residual risks.

### The size and time budgets of the network input

| Input | Budget | Where |
|---|---|---|
| An RTSP control message | 1 MB | `airplay/RtspServer.kt` (`MAX_MESSAGE_BYTES`) |
| A WFD RTSP control message | 64 KB | `miracast/WfdRtspServer.kt` (`MAX_MESSAGE_BYTES`) |
| A reassembled FU-A NAL unit | 8 MB | `airplay/RtpInterleaved.kt` (`MAX_FUA_BYTES`) |
| The mirror frame queue | 90 items **and** 32 MB | `airplay/handshake/MirrorFrameQueue.kt` |
| An AirPlay `/photo` payload | 25 MB | `airplay/PhotoHandler.kt` (`MAX_PHOTO_BYTES`) |
| A DLNA photo payload | 20 MB, downsampled to 1920 px | `dlna/DlnaImageDecoder.kt` |
| An HTTP request line / header block / body | 8 KB / 16 KB / 64 KB | `dlna/HttpRequestReader.kt` (`MAX_LINE_BYTES`, `MAX_HEADER_BYTES`, `MAX_BODY_BYTES`) |
| The socket timeout / the total deadline of one request | 2 s / 10 s | `dlna/DlnaDescriptionServer.kt` (`READ_TIMEOUT_MS`, `REQUEST_DEADLINE_MS`) |
| The DLNA description server | 8 workers + 16 queued, then `503` | `dlna/DlnaDescriptionServer.kt` |
| A SOAP XML document | 64 KB, 32 nesting levels | `dlna/SecureSoapXml.kt` |
| A media URL | 4096 characters, no control characters | `util/FetchableUrlPolicy.kt` |

### No identifying data in the logs

Every message that goes through `com.phairplay.util.Logger` is reduced by
`util/LogRedactor.kt` **before** it reaches the log: the host part of an IP
address, the device-specific half of a MAC address, complete UUIDs and the
credentials and query string of a URL are replaced by placeholders. A logged
exception is copied and sanitised as well, because an exception message repeats
the value that caused it.

The reduction mode is **on in every build**. It can be switched off for a local
debugging session with `Logger.setRedactionMode(LogRedactionMode.OFF)`. Note the
separate, deliberate decision of `PhairPlayApp`: a **release build plants no
Timber tree at all**, so a release build writes no log output whatsoever (SEC-07
in the audit). Where anything is written — in a debug build — it is reduced.

### Principle of Least Privilege

The app requests only the permissions it needs for the local network. No
storage, no camera, no microphone. The permission table with the call site of
every line is in
[`audit/2026-10-24-security-audit.md`](audit/2026-10-24-security-audit.md) §4.

---

## Design decisions

### Why View-based UI instead of Jetpack Compose?

Jetpack Compose is modern, but it adds complexity for TV apps:
- Focus handling with D-pad is harder to get right in Compose
- SurfaceView (needed for low-latency video) has quirks in Compose
- The `AppCompat.Leanback` theme (required for TV launcher integration) works better with traditional Views

For v1.0, View-based UI with ConstraintLayout is simpler and more compatible with TV devices.

### Why does the service publish `StateFlow`s instead of a ViewModel?

The service is the **only** long-lived component that knows what the receivers
are doing, and it outlives every Activity. It therefore owns the state and
publishes it as `StateFlow`s through `ServiceStateBus`. The Activity binds to
the service (`ServiceConnectionBinder`) and collects the flows; it does not
copy the state into a ViewModel. That keeps one source of truth for a state
that exists on both sides of the Activity lifecycle.

### Why is the RTSP handler split into many small classes?

AirPlay uses RTSP to negotiate codecs, ports and encryption before any media
flows, and the protocol is large: the classic verbs, the AirPlay 2 handshake, the
mirroring SETUP, the media parameters and the URL-video mode. One class grew
past 1000 lines. The split keeps `RtspHandler` as **one stable API** that
`AirPlayReceiver` talks to, while each part of the protocol lives in a class that
owns exactly one responsibility. The split is behaviour preserving.

### Why Bouncy Castle for cryptography?

Android's built-in `javax.crypto` supports AES-128-CTR, but:
- The API for it is verbose
- SRP-6a (needed for the PIN pairing) is not available in Android's built-in crypto
- Bouncy Castle is a well-audited, widely-used crypto library

### Why raw sockets instead of a networking library?

AirPlay uses a custom RTSP variant with Apple extensions, and the DLNA stack
needs a bounded HTTP server with an exact answer format. No off-the-shelf
HTTP/RTSP library handles these correctly. Implementing the protocol with raw
Java sockets is more work initially, but gives full control over the protocol
behaviour — which is necessary for compatibility.

### Why one Activity?

- Simpler lifecycle management
- The MediaCodec Surface doesn't need to be passed across Activity transitions
- TV apps typically use a single-Activity architecture anyway (Leanback recommends it)

---

## What is not proven yet

The architecture above describes the **code**. It does not describe a measured
behaviour on a television: **no device test has ever been executed** — 0 of the
15 cases of [`guides/DEVICE_TEST_PLAN.md`](guides/DEVICE_TEST_PLAN.md) have
been run. The DLNA media path in particular is unproven on a real device, and
the cleartext `http://` fetch it needs is probably blocked by the platform
default on API 35 (no `network_security_config.xml` exists).

The honest list of what still needs hardware is in
[`guides/DEVICE_TEST_PLAN.md`](guides/DEVICE_TEST_PLAN.md), and the milestone
status is in [`spec/PROJECT_PLAN.md`](spec/PROJECT_PLAN.md).
