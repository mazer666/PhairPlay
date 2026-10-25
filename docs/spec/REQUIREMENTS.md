# PhairPlay – Requirements

Version: 3.0
Status: Active
Date: 2026-10-24

> **Change log v3.0 (2026-10-24).** The UPnP / DLNA MediaRenderer is no longer
> "out of scope": it is implemented (package `com.phairplay.dlna`, ADR-004 phases
> D1–D4) and is therefore described by its own requirement section (§1.9).
> FR-04 and FR-06 were amended (four receivers, not three). FR-44 … FR-70 were
> added, NFR-26 … NFR-31 were added. §3 now lists DLNA as in scope and Miracast
> media decoding as an explicit, reasoned exclusion. The milestone numbering of
> `ACCEPTANCE_CRITERIA.md` was reconciled with `PROJECT_PLAN.md`; see the mapping
> table in that document.

---

## 1. Functional Requirements

### 1.1 Service Discovery & Advertising

- FR-01: The app MUST advertise an AirPlay 2 receiver via mDNS (`_airplay._tcp`, `_raop._tcp`).
- FR-02: The app MUST advertise a Miracast receiver via Wi-Fi Direct (P2P) service discovery.
- FR-03: The app MUST optionally advertise a Google Cast receiver (if Cast SDK is registered).
- FR-04: All four receivers (AirPlay, Miracast, Cast, UPnP/DLNA) can be enabled/disabled independently in Settings. *(Amended in v3.0 — was "all three services"; DLNA was added as the fourth receiver in M12, see ADR-004.)*
- FR-05: The device name shown in sender pickers MUST be configurable in Settings (defaults to Android device name).
- FR-06: All enabled receivers start advertising within 2 seconds of app or service launch. *(Amended in v3.0 — "all services" → "all enabled receivers"; a receiver that is switched off in Settings must not advertise.)*
- FR-07: All services stop advertising within 5 seconds of being disabled or app close.

### 1.2 AirPlay 2 Receiver

- FR-08: Accept Screen Mirroring connections from **macOS 12+** and **iOS/iPadOS 13+** via RTSP on TCP port 7000.
- FR-09: Complete the RTSP handshake (OPTIONS → SETUP → ANNOUNCE → RECORD) without errors.
- FR-10: Reject second concurrent AirPlay connection with RTSP 503.
- FR-11: Auto-reconnect and resume advertising after connection loss. *(Status: implemented for the AirPlay receiver and the DLNA renderer — `PhairPlayService.registerNetworkCallback` re-advertises when the last network was lost; the DLNA renderer additionally rebuilds its description server when the device came back with a new address. FR-61 makes this binding for every enabled receiver. The Miracast and Cast receivers have no re-advertisement path — Miracast is a control plane only (ADR-004) and Cast needs a registered application ID.)*
- FR-12: Accept **audio-only AirPlay** streams (music, podcasts) from macOS and iOS senders, playing audio through the TV speakers without requiring a video stream.

#### AirPlay 2 Codec Requirements

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video (Mandatory) | H.264 AVC — up to High Profile Level 5.2 | **Required** | Hardware MediaCodec decode |
| Video (Optional / 4K) | H.265 HEVC | Optional | From Apple TV 4K baseline; hardware check required |
| Audio (Mandatory – Mirroring) | AAC-LC or LPCM (depending on latency requirements) | **Required** | AAC-LC for low bandwidth, LPCM for minimal latency |
| Audio (Mandatory – Music) | ALAC (Apple Lossless) 16-bit / 44.1 kHz | **Required** | Default for music/podcast streaming |
| Audio (Surround – Optional) | Dolby Atmos (E-AC3 with JOC), AC-3 | Optional | Only on capable hardware |
| Container | MP4, MOV, M4V; HLS (HTTP Live Streaming) | **Required** | As used by AirPlay media sessions |
| Image / Photo | JPEG, PNG (via `/photo` HTTP endpoint) | **Required** | See FR-42 |
| Max Resolution | Up to 4K UHD @ 60fps (HDR10, Dolby Vision) | Optional | 1080p @ 60fps is mandatory baseline |
| DRM | FairPlay (evaluation only — see §3) | Not in v1 | Requires Apple licensing |

- FR-42: Accept **AirPlay Photo / Image** transfers sent by iOS/macOS (via the `/photo` HTTP endpoint). Display the received image full-screen. Support JPEG and PNG. Return to HomeScreen on connection close. (Requires AirPlay feature bit 1 `Photo` to be set in the `features` TXT record.)

### 1.3 Miracast Receiver

> **Implementation status (2026-10-24): control plane only.** PhairPlay advertises
> itself as a WFD sink and answers the WFD RTSP handshake, but **no Miracast media
> is rendered** — the project contains neither an MPEG-TS demuxer nor a WFD
> decoder, and a sideloaded app cannot become a Wi-Fi Display sink anyway, because
> the API that registers a sink is a hidden system API (ADR-004, FR-58 … FR-60).
> The annotations below state the real state of every requirement.

- FR-13: Discover and accept incoming Miracast (Wi-Fi Display / WFD) connection requests. *(Status: partially implemented — the Wi-Fi Direct service `_wfd._tcp` is advertised and the WFD RTSP control port 7236 is opened. A sender that requires the hidden Wi-Fi Display API does not connect; see FR-58.)*
- FR-14: Negotiate the WFD session (capability exchange, RTSP-WFD handshake). *(Status: implemented for the control plane — `OPTIONS`, `GET_PARAMETER`, `SET_PARAMETER`, `SETUP`, `PLAY`, `PAUSE`, `TEARDOWN` are answered; the capability set reports H.264 CHP/CBP and LPCM. Proof: `MiracastReceiverTest`.)*
- FR-15: Decode and display the incoming video stream from the Miracast sender per the codec matrix below. *(Status: not implemented — no MPEG-TS demuxer, no WFD video decoder. Out of scope, see §3.)*
- FR-16: Play audio from the Miracast stream per the codec matrix below. *(Status: not implemented — same reason as FR-15.)*
- FR-17: Support Windows 10+, Android, and Samsung Galaxy as Miracast senders (Phase 3+). *(Status: not verifiable — without a media path there is no end-to-end sender compatibility; the control plane is tested against a synthetic RTSP client only.)*
- FR-18: Show the Miracast connection state on the HomeScreen status card. *(Status: implemented — `MiracastReceiver` emits `ProtocolState`, `HomeFragment` renders the card. The state must be "not available on this device" and not "Advertising" when the platform forbids the feature, see FR-59.)*

#### Miracast (WFD) Codec Requirements

> **Status (2026-10-24): none of these codecs is decoded by PhairPlay.** The table
> describes the WFD standard and what a Miracast sink would need; the project
> implements the discovery and the control plane only (FR-14, FR-60). The codec
> rows are therefore the *capability announcement* of the control plane, not a
> promise of playback.

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video (Mandatory) | H.264 AVC — Constrained High Profile (CHP) and Constrained Baseline Profile (CBP), up to Level 4.2 | **Required** | WFD spec primary codec — *announced, not decoded* |
| Video (Optional / 4K) | H.265 HEVC | Optional | Newer senders (Windows 11 / Android 10+) |
| Audio (Mandatory) | LPCM 16-bit / 48 kHz (mono, stereo) | **Required** | WFD baseline audio — *announced, not decoded* |
| Audio (Optional) | AAC-LC, AAC-HE | Optional | More efficient than LPCM |
| Audio (Surround – Optional) | AC-3 (Dolby Digital) | Optional | When sender/display supports it |
| Container | MPEG-TS (MPEG Transport Stream) | **Required** | Standard WFD stream encapsulation — *no demuxer in the project* |
| Max Resolution | 1080p @ 60fps | **Required** | Mandatory WFD baseline |
| Max Resolution (Optional) | 4K UHD @ 60fps | Optional | Only on hardware that supports it |
| DRM / Copy Protection | HDCP 2.x (hardware-based link protection) | **Required** | Negotiated during WFD setup — *the control plane reports `wfd_content_protection: none`* |

### 1.4 Google Cast Receiver

- FR-19: Run as a Cast Custom Receiver application registered with the Google Cast SDK.
- FR-20: Accept Cast connections from Chrome/Android/macOS/iOS senders.
- FR-21: Support Cast screen mirroring from Chrome and Android senders.
- FR-22: Display Cast status on the HomeScreen status card.
- FR-23: Gracefully degrade if the Cast SDK is unavailable (missing Google Play Services on Fire TV).

#### Google Cast Codec Requirements

> **Status (2026-10-24): the decode happens in the Cast SDK, not in PhairPlay.**
> The `googletv` flavour starts the Cast Connect receiver; the `firetv` flavour
> cannot (no Google Play Services) and reports "disabled" (FR-69). The rows below
> describe what the Cast runtime offers, not a decoder inside this project.

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video (Mandatory) | H.264 AVC (Baseline, Main, High Profile); VP8 | **Required** | Cast baseline codecs |
| Video (Optional / 4K) | H.265 HEVC, VP9, AV1 | Optional | Newer hardware (Android TV API 31+ for AV1) |
| Audio (Mandatory) | AAC-LC, AAC-HE, MP3, WAV (LPCM) | **Required** | Cast baseline audio |
| Audio (Surround – Optional) | Dolby Digital Plus (E-AC3), Dolby Atmos, Opus, FLAC | Optional | High-quality audio on capable devices |
| Container | MP4, WebM | **Required** | Native Cast containers |
| Container (Adaptive) | DASH (Dynamic Adaptive Streaming over HTTP), HLS | **Required** | For adaptive bitrate streaming |
| Max Resolution | Up to 4K UHD @ 60fps (HDR10+) | Optional | 1080p mandatory, 4K hardware-dependent |
| DRM | Widevine L1 / L3, PlayReady | **Required** | For DRM-protected content streams |

### 1.5 Service Control

- FR-24: The user MUST be able to **start**, **stop**, and **restart** all receiver services from the app UI.
- FR-25: The receiver runs as an Android **ForegroundService** so it continues operating when the app is in the background (e.g., screensaver active).
- FR-26: A **persistent notification** MUST be shown while the ForegroundService is running, with quick-action buttons: Stop, Restart.
- FR-27: Service state (running/stopped/error) MUST be visible on the HomeScreen at all times.
- FR-28: Stopping the service MUST release all network ports, close all connections, and stop advertising.
- FR-29: Restarting the service MUST perform a clean stop followed by a clean start within 3 seconds.

### 1.6 Settings

- FR-30: A dedicated **Settings screen** MUST be accessible from the HomeScreen.
- FR-31: Settings MUST be persisted between app restarts (Android DataStore / SharedPreferences).
- FR-32: The following settings MUST be configurable:

| Setting | Default | Description |
|---|---|---|
| Display name | Android device name | Name shown in sender pickers |
| AirPlay enabled | ON | Enable/disable AirPlay service |
| Miracast enabled | ON | Enable/disable Miracast service |
| Cast enabled | ON | Enable/disable Cast service |
| AirPlay PIN auth | OFF | Require PIN for AirPlay connections |
| Start on boot | OFF | Auto-start service on device boot |
| Show debug overlay | OFF | Show FPS / latency overlay (dev) |

### 1.7 Video / Audio

- FR-33: Decode video using hardware `MediaCodec`. Supported codecs per protocol:
  - **AirPlay**: H.264 AVC (mandatory, up to High Profile Level 5.2); H.265 HEVC (optional, hardware check required).
  - **Miracast**: H.264 AVC CHP/CBP (mandatory); H.265 HEVC (optional).
  - **Google Cast**: H.264 AVC + VP8 (mandatory); H.265 HEVC, VP9, AV1 (optional, API-level gated).
  - Software fallback is NOT used; if hardware decoder is unavailable, the stream is rejected gracefully.
- FR-34: Display decoded video full-screen maintaining aspect ratio.
- FR-35: Play audio in sync with video (A/V drift ≤ 40ms).
- FR-36: For **audio-only** streams (no video): play through the default audio output without launching the streaming screen.
- FR-37: Return to HomeScreen (or remain on HomeScreen for audio-only) when stream ends.
- FR-43: For **image / photo** transfers (AirPlay `/photo` endpoint): display received JPEG or PNG full-screen. No video decoder is involved; image is decoded via Android's `BitmapFactory`. Return to HomeScreen when sender disconnects.

### 1.8 Internationalization (i18n)

- FR-38: All user-visible strings MUST be defined in Android resource files (never hardcoded in Kotlin/XML).
- FR-39: The app MUST support at least **English** and **German** at launch.
- FR-40: The i18n structure MUST allow adding new languages without changing Kotlin or layout files.
- FR-41: Date/time formats, number formats, and text direction MUST respect the device locale.

### 1.9 UPnP / DLNA MediaRenderer

PhairPlay implements a UPnP MediaRenderer (see [ADR-004](../decisions/ADR-004-dlna-mediarenderer-roadmap.md)).
All DLNA logic lives in the package `com.phairplay.dlna`; the AirPlay 2 stack is not
touched by it, and DLNA is an **additional** receiver — it replaces neither AirPlay,
nor Miracast, nor Cast.

- FR-44: The app MUST advertise itself as a UPnP MediaRenderer via SSDP on `239.255.255.250:1900` using the device type `urn:schemas-upnp-org:device:MediaRenderer:1`, and MUST answer an incoming `M-SEARCH` whose search target is `ssdp:all` with **exactly three** `200 OK` datagrams — `upnp:rootdevice`, the MediaRenderer device type and the UDN — and every other matching search target with exactly one datagram. Every answer MUST carry the `LOCATION`, `USN`, `ST`, `EXT` and `SERVER` headers. *Implementation: `com.phairplay.dlna.SsdpAdvertiser`, `SsdpResponder`, `SsdpMessages`. Proof: `SsdpResponderTest`, `SsdpAdvertiserLiveTest`.*
- FR-45: The app MUST serve the UPnP device description and the service descriptions (SCPD) of `ConnectionManager:1`, `AVTransport:1` and `RenderingControl:1` over HTTP under the `LOCATION` URL it advertises. *Implementation: `DlnaDescriptionServer`, `DlnaDescriptionBuilder`, `DlnaServiceCatalog`. Proof: `DlnaDescriptionServerTest`, `DlnaDescriptionBuilderTest`.*
- FR-46: The app MUST answer the UPnP control actions `GetProtocolInfo`, `GetCurrentConnectionIDs`, `GetCurrentConnectionInfo` (ConnectionManager), `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`, `GetTransportInfo`, `GetPositionInfo`, `GetMediaInfo`, `GetCurrentTransportActions` (AVTransport) and `GetVolume`, `SetVolume`, `GetMute`, `SetMute`, `GetVolumeDB` (RenderingControl). An unknown action MUST be answered with the SOAP fault `401 Invalid Action` (HTTP 500), an invalid or missing argument with `402 Invalid Args`. *Implementation: `DlnaSoapDispatcher`, `DlnaAvTransportService`, `SoapResponses`.*
- FR-47: The `GetProtocolInfo` answer MUST contain a non-empty `Source` and a non-empty `Sink` protocol list. A renderer with an empty `Source` list is treated by control points as a device that cannot fetch anything and is hidden from the renderer picker. *Implementation: `DlnaProtocolInfo`.*
- FR-48: The app MUST support GENA eventing: `SUBSCRIBE` and `UNSUBSCRIBE` on `/upnp/event/<Service>`, a subscription answer that carries `SID`, `TIMEOUT` and `EXT`, and `LastChange` notifications in the nested UPnP format whenever the transport state or the render state changes. A subscription MUST expire after its timeout, and a control point that no longer answers MUST be dropped instead of being notified forever. *Implementation: `GenaEventManager`, `GenaLastChange`, `DlnaDescriptionServer`. Proof: `GenaEventManagerTest`.*
- FR-49: The app MUST refuse a GENA callback URL that is not an `http` URL whose host is a literal, non-obfuscated IPv4 address in one of the private ranges `10/8`, `172.16/12`, `192.168/16` and inside the same /24 as the renderer. Loopback, link-local (`169.254/16`, the router metadata address), multicast, broadcast, decimal/octal/hexadecimal notations, host names and every IPv6 form MUST be rejected with `412 Precondition Failed`. *Implementation: `GenaCallbackPolicy`, `GenaEventManager.subscribe`. Proof: `GenaCallbackPolicyTest`, `GenaEventManagerTest`, `DlnaDescriptionServerTest`.*
- FR-50: The renderer MUST fetch media only over `http` and `https`. A `CurrentURI` with any other scheme (`file`, `smb`, `ftp`, `rtsp`, …) MUST be refused with the SOAP fault `402 Invalid Args` and MUST NOT be opened. *Implementation: `DlnaMediaUri.validate` — currently accepts a wide scheme list; deviation documented in the handover as a change request (audit finding SEC-04).*
- FR-51: Every HTTP request the DLNA server accepts MUST be bounded: request line ≤ 8 KB, header block ≤ 16 KB, body ≤ 64 KB, socket read timeout 2 s, total request deadline 10 s, at most 8 concurrent workers and 16 queued clients. Further clients MUST receive `503 Service Unavailable`; an over-sized request MUST be answered with `414` / `431` / `413` instead of being buffered. *Implementation: `HttpRequestReader`, `DlnaDescriptionServer`. Proof: `DlnaDescriptionServerTest`.*
- FR-52: The XML parser of the control plane MUST reject a DOCTYPE declaration and external entities, MUST disable entity expansion, MUST bound the payload to 64 KB and the nesting depth to 32 levels, and MUST fail closed when the platform parser cannot be hardened. *Implementation: `SecureSoapXml`. Proof: `SecureSoapXmlTest`, `DlnaSoapDispatcherSecurityTest`.*
- FR-53: No answer of the DLNA control plane may contain a stack trace, an exception class name, a file path, a library version or a host path. Error details belong in the log, not in the answer. *Implementation: `SoapResponses.fault`, `DlnaSoapDispatcher.dispatch`. Proof: `DlnaDescriptionServerTest`.*
- FR-54: The UPnP/DLNA receiver MUST be switchable independently of the other receivers in Settings, and its state (disabled / advertising / connected / error) MUST be visible on the HomeScreen. *Implementation: `AppSettings.dlnaEnabled`, `SettingsRepository`, `SettingsFragment`, `HomeFragment`, `PhairPlayService.startDlna`.*
- FR-55: When the DLNA receiver is stopped, the app MUST send the SSDP `byebye`, close the description server socket, drop every GENA subscription, release the media player and stop advertising. No port may stay bound and no event may be delivered afterwards. *Implementation: `DlnaReceiver.stop`, `DlnaDescriptionServer.stop`, `GenaEventManager.stop`.*
- FR-56: The media keys of the TV remote (play/pause, pause, stop) MUST drive the **same** AVTransport state machine that the control point drives, so that the state a control point reads and the `LastChange` event it receives stay truthful. *Implementation: `DlnaReceiver.sendRemoteCommand`, `DlnaSoapDispatcher.onRemoteCommand`, `MainActivity` media-key handling.*
- FR-57: The user-facing documentation MUST state that a UPnP renderer fetches the media itself, that therefore only network-reachable `http`/`https` URLs can be played, and that a `file:///` URI points at the sender's disk and cannot be rendered by the television. This is a property of the protocol, not a defect, and MUST NOT be presented as one. *Proof: `README.md`, `docs/guides/`, `TECHNICAL_SPEC.md` §12.*

### 1.10 Miracast receiver — scope and honest limits

Miracast is the weakest link of the project: a sideloaded third-party Android app
cannot become a Wi-Fi Display sink on stock consumer hardware, because the API that
registers a sink (`WifiP2pManager.setWfdInfo`) is a hidden system API gated behind a
system permission (see ADR-004). The requirements below therefore describe what
PhairPlay actually does — not what a Miracast sink would do.

- FR-58: The Miracast receiver MUST use public Android APIs only. It MUST NOT call hidden or system APIs (for example `WifiP2pManager.setWfdInfo`) and MUST NOT require the system permission `CONFIGURE_WIFI_DISPLAY`. A sideloaded app cannot obtain that permission, so an implementation that depends on it cannot work at all. *Implementation: `com.phairplay.miracast.MiracastReceiver` uses `WifiP2pManager` / `WifiP2pDnsSdServiceInfo` only.*
- FR-59: When `WifiP2pManager` is unavailable or the required runtime permission is not granted, the Miracast receiver MUST report an explicit "not available on this device" state and the HomeScreen MUST show that state. It MUST NOT report "Advertising". *Implementation: `MiracastReceiver.initializeWifiP2p`, `MiracastReceiver.registerP2pService`. Deviation: `PhairPlayService.startMiracast` currently sets `ADVERTISING` before the receiver can report the error — documented as a change request.*
- FR-60: The documentation MUST state that the Miracast implementation covers the discovery and control plane only (Wi-Fi Direct service advertisement, WFD RTSP handshake, capability exchange) and that no Miracast media is rendered: the project contains neither an MPEG-TS demuxer nor a WFD video/audio decoder. *Proof: `grep -rn "MiracastDecoder" app/src/main` → 0 matches.*

### 1.11 Resilience and re-advertisement

- FR-61: After the last network interface is lost and comes back (Wi-Fi off/on, TV standby, VPN up/down), every enabled receiver MUST re-advertise within 5 seconds — without an app restart and without dropping the open sockets. *Implementation: `PhairPlayService.registerNetworkCallback`, `NetworkRecoveryMonitor`, `ReceiverSupervisor.readvertiseAll`, `AirPlayReceiver.readvertise`, `DlnaReceiver.readvertise`, `SsdpAdvertiser.readvertise`. The DLNA renderer rebuilds its description server when the device came back with a different address, because the description URL contains that address. Proof: `DlnaReadvertisePlanTest` (the decision what has to be rebuilt); the socket behaviour itself needs a device and is listed in `docs/guides/DEVICE_TEST_PLAN.md`.*
- FR-62: A failed advertisement MUST be retried with a bounded backoff and MUST end in an explicit error state. A receiver MUST NOT disappear silently from the sender pickers while the UI claims that it is advertising. *Implementation: `MdnsRegistrationTracker` (retry after 1 s / 3 s / 6 s), `MdnsService`. Proof: `MdnsRegistrationTrackerTest`, `MdnsServiceTest`.*

### 1.12 Resource budgets

- FR-63: The AirPlay mirroring frame queue MUST be bounded twice: by item count (90 frames ≈ 1.5 s at 60 fps — the latency bound) **and** by total bytes (32 MB — the memory bound). When the byte budget is exceeded the oldest frames MUST be dropped, the network reader MUST never block, and the byte counter MUST be exact. *Implementation: `com.phairplay.airplay.handshake.MirrorFrameQueue`, `MirrorStreamServer`. Proof: `MirrorFrameQueueTest`.*
- FR-64: The DLNA photo pipeline MUST refuse a payload above 20 MB and MUST downsample the decoded bitmap to at most 1920 px on its longest edge before it reaches the screen. *Implementation: `DlnaImageDecoder`, `DlnaReceiver.fetchBounded`. Proof: `DlnaImageDecoderTest`.*

### 1.13 Codec capability advertising

- FR-65: The app MUST query the decoders and the audio output encodings of the device at runtime (`MediaCodecList`, `AudioManager.getDevices`) and MUST advertise a codec only when the device can actually decode it. Optional codecs (HEVC, VP9, AV1, AC-3, E-AC-3) MUST NOT be advertised when they are missing. *Implementation: `com.phairplay.media.MediaCapabilities`, `DeviceMediaCapabilities`. Proof: `MediaCapabilitiesTest`.*
- FR-66: The protocol list that the renderer advertises SHOULD be derived from the runtime capability snapshot of FR-65. As long as the list is a fixed constant, it MUST NOT contain an entry that the receiver cannot handle, and the deviation MUST be documented in the technical specification. *Implementation: `DlnaProtocolInfo` — fixed constant today; documented in `TECHNICAL_SPEC.md` §12.*

### 1.14 Internationalization (extension of §1.8)

- FR-67: The string resources of every shipped locale MUST be a subset of the keys of the base locale (`res/values/strings.xml`). A key that exists only in a locale file is an error. A key that is missing in a locale file is allowed only when the base text is the intended translation. *Proof: a key diff of `res/values*/strings.xml` in CI.*
- FR-68: A user-visible string that names a **protocol** (AirPlay, Miracast, Cast, DLNA) MUST be identical in every locale; a user-visible string that names a **state** (disabled / advertising / connected / error) MUST exist in every locale the app is offered in. *Proof: the same key diff plus a value comparison of the `protocol_*` keys.*

### 1.15 Optional receivers: graceful degradation

- FR-69: On a device without Google Play Services (the `firetv` flavour), the Cast receiver MUST report "disabled" and MUST NOT initialise the Cast SDK. The HomeScreen MUST explain that Cast requires Google Play Services. *Implementation: `app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt`. Proof: `CastReceiverTest`.*
- FR-70: When no Cast application ID is registered, the Cast receiver MUST report an explicit "not configured" error and the documentation MUST point to the registration guide (`docs/guides/CAST_APP_ID.md`). It MUST NOT claim to advertise a Cast receiver. *Implementation: `CastReceiver.isConfigured`, `CastReceiver.start`. Proof: `CastReceiverTest`.*

---

## 2. Non-Functional Requirements

### 2.1 Performance (unchanged)
- NFR-01: Video latency ≤ 100ms on local 5 GHz Wi-Fi.
- NFR-02: Frame rate ≥ 25fps sustained.
- NFR-03: Peak RAM ≤ 150 MB (single active stream). *The budget is implemented as the constant `PerformanceBudgets.MAX_RESIDENT_BYTES`; the app measures its own resident memory and shows it against the budget in the diagnostics report.*
- NFR-04: Average CPU ≤ 30% on mid-range TV SoC. *The budget is implemented as the constant `PerformanceBudgets.MAX_CPU_PERCENT`; the app measures its own CPU load and shows it against the budget in the diagnostics report.*

### 2.2 UI / UX
- NFR-05: UI design MUST follow the **Google TV Streamer** design language: dark background, card-based layout, large focus indicators, D-pad navigable.
- NFR-06: All interactive elements MUST be focusable via D-pad / remote control.
- NFR-07: Minimum touch target and focus target size: 48dp.
- NFR-08: Text minimum size: 18sp for body, 32sp for titles.
- NFR-09: Sufficient contrast ratio for TV viewing from 3 meters.
- NFR-10: The HomeScreen MUST show the active protocol, sender name, and duration within 1 second of change.

### 2.3 Privacy & Security

- NFR-11: No ads, no analytics, no telemetry.
- NFR-12: All network input validated before processing.
- NFR-13: No hardcoded secrets.
- NFR-14: Minimal Android permissions.
- NFR-26: Every outbound connection of the app MUST target an address in the local network (private IPv4 range). The code MUST NOT contain a hardcoded internet endpoint. *Proof: `grep -rn "https\?://" app/src/main --include=*.kt` shows documentation URLs only; an integration test asserts that the GENA callback guard rejects every non-private address.*
- NFR-27: Every server socket of the app MUST bound the size and the duration of the requests it accepts and MUST answer an over-sized or expired request with a status code instead of allocating the payload. See FR-51 (DLNA HTTP), FR-63 (mirroring queue) and the RTSP limits of the AirPlay stack.
- NFR-28: If a protocol needs cleartext HTTP (the DLNA media fetch, the GENA `NOTIFY`, the DACP reverse channel), the app MUST declare a network security configuration that permits cleartext **only** for private LAN address ranges, and MUST NOT enable cleartext globally. As long as no such configuration exists, the affected features MUST be documented as unavailable — see the open point in the handover. *Proof: `app/src/main/res/xml/network_security_config.xml` exists and lists no public domain; a device test plays an `http://` media URL from a local server.*
- NFR-29: Every log line of the app MUST be reduced before it is written: host part of an IP address, device-specific half of a MAC address, complete UUIDs, and the credentials and query string of a URL MUST be replaced by placeholders. The reduction MUST be active in every build, including release. *Implementation: `com.phairplay.util.Logger`, `LogRedactor`, `LogRedactionMode`. Proof: `LoggerTest`, `LogRedactorTest`.*
- NFR-30: The app MUST declare only the Android permissions it actually uses, and every declared permission MUST be justified in `PRIVACY.md`. A runtime permission MUST be requested only when the user switches the corresponding feature on, and the request MUST be preceded by a rationale. *Proof: the permission table in `PRIVACY.md` covers every `<uses-permission>` line of the manifest; `grep -rn "requestPermissions" app/src/main` lists only the notification permission.*
- NFR-31: A release build MUST write warnings and errors to the log (after the reduction of NFR-29); a release build that writes nothing makes a field failure undiagnosable. *Proof: a unit test with a release log tree asserts that an error is written and that a debug message is dropped.*

### 2.4 Compatibility
- NFR-15: Google TV: Android 10+ (API 29+), ARMv8.
- NFR-16: Fire TV: Android 7.1+ (API 25+), ARMv7/ARMv8.
- NFR-17: AirPlay sender (screen mirroring): macOS 12+, iOS/iPadOS 13+.
- NFR-18: AirPlay sender (audio-only): macOS 12+, iOS/iPadOS 13+.
- NFR-19: Miracast sender: Windows 10+, Android 4.2+.
- NFR-20: Cast sender: Chrome 72+, Android 5+.

### 2.5 Code Quality
- NFR-23: No source file exceeds 400 lines (soft limit). A file MUST NOT exceed 550 lines (hard limit). *(Amended in v3.0 — the requirement previously named 400 lines as an absolute limit, which contradicted the project rule in `docs/CONTRIBUTING.md` (soft 400 / hard 550). The two files above the soft limit today are `service/PhairPlayService.kt` (650 lines) and `miracast/MiracastReceiver.kt` (439 lines); both are being split by the refactoring track and are listed as open in the handover.)*
- NFR-24: Every public method has at least one unit test.
- NFR-25: All classes have KDoc headers.

---

## 3. Explicitly Excluded / Deferred Features

| Feature | Reason | Status |
|---|---|---|
| FairPlay DRM (streaming content) | See evaluation note below | Not in v1; v2 research item |
| HomeKit / HAP pairing | Complex separate protocol | v3 roadmap |
| WiDi (Intel) | EOL technology | Not planned |
| Miracast media rendering (MPEG-TS demux, WFD video/audio decode) | No public Android API lets a sideloaded app act as a Wi-Fi Display sink; only the discovery and control plane are implemented | Out of scope — documented limitation, see FR-58 … FR-60 |
| DLNA media sources other than `http`/`https` (e.g. `file:///`, `smb://`) | A UPnP renderer fetches the media itself; a `file:///` URI points at the sender's disk and is not readable by the television | Not supported — documented limitation, see FR-50 and FR-57 |
| Cloud / remote streaming | Security risk | Not planned |
| Screen recording to file | Privacy concern | Not planned |
| H.265 / HEVC decode (AirPlay) | Optional, hardware-gated | v2 optional (API check required) |
| H.265 / HEVC decode (Miracast) | Subsumed by the Miracast media exclusion above — there is no Miracast media path at all | Out of scope (see the Miracast row) |
| VP9 / AV1 decode (Cast) | Optional, API level gated (API 31+ for AV1) | v2 optional |
| Dolby Atmos / AC-3 decode | Optional surround audio | v2 optional |
| AirPlay audio grouping (multi-room) | Requires full AirPlay 2 stack | v3 roadmap |
| 4K UHD streams (AirPlay, Miracast) | Optional, hardware-dependent (AirPlay); for Miracast see the Miracast media exclusion above | v2 optional (AirPlay) |
| HDR10 / Dolby Vision (AirPlay) | Optional, API 31+ required | v2 optional |

> **Correction v3.0 (2026-10-24).** In v2.2 this table listed *"DLNA / UPnP — Out of
> scope — Not planned"*. That contradicted [ADR-004](../decisions/ADR-004-dlna-mediarenderer-roadmap.md),
> which accepted DLNA as milestone M12, and it contradicted the code: the package
> `com.phairplay.dlna` is implemented and shipped. The row was removed and the
> requirement was moved into §1.9 (FR-44 … FR-57). The exclusion table now lists
> only what is genuinely not implemented — including the two honest limits of the
> DLNA and Miracast stacks.

### FairPlay DRM – Evaluation Note

**What is FairPlay?** Apple FairPlay Streaming (FPS) is Apple's DRM system for protecting HLS content. It is used by Apple TV+, iTunes, and other Apple-licensed content platforms.

**Technical path to implementation:**
1. Register as an Apple Developer (active program membership required).
2. Apply to Apple for an **FPS deployment package** (Key Security Module — KSM). Apple approves or denies based on use case.
3. Implement the FPS key exchange protocol: the receiver must communicate with a license server using Apple's proprietary key exchange, using the provided KSM library (binary-only, Apple-supplied).
4. The KSM binary is platform-specific — Apple provides it for macOS/iOS/tvOS. An Android implementation would require Apple to supply an Android-compatible KSM, which Apple has never done publicly.

**Conclusion for PhairPlay v1:**
- FairPlay is **not implementable** on Android without Apple providing an Android KSM binary — which they have not done.
- Even if Apple provided one, the license terms would likely be **incompatible with open-source distribution** under Apache 2.0.
- **FairPlay is excluded from PhairPlay v1 and is not on the roadmap** unless Apple changes their licensing policy.
- PhairPlay can receive unencrypted AirPlay streams and AirPlay streams encrypted with the open AES-128-CTR session key mechanism. FairPlay-protected premium content (e.g., Apple TV+) will not play.

> **Note on open codecs:** H.265 HEVC, VP9, and AV1 are fully implementable on Android via `MediaCodec` with hardware support checks. These are planned as optional features in v2 behind `MediaCodecInfo.CodecCapabilities` capability queries at runtime.

> **Note on HDCP and Widevine/PlayReady:** HDCP (for Miracast) is negotiated at the WFD protocol level and enforced by hardware — no software implementation is needed. Widevine and PlayReady (for Cast) are handled by the Google Cast SDK and the Android DRM framework (`MediaDrm`) — the app does not need to implement DRM logic directly.

---

## 4. Target Devices & Senders

### Receivers (this app)
| Platform | Min OS | API | Arch |
|---|---|---|---|
| Google TV (Chromecast with Google TV) | Android 10 | 29 | ARMv8 |
| Amazon Fire TV Stick 4K | Android 7.1 | 25 | ARMv8 |
| Amazon Fire TV Stick 3rd gen | Android 7.1 | 25 | ARMv7/ARMv8 |

### Senders (clients)
| Protocol | Platform | Version | Notes |
|---|---|---|---|
| AirPlay 2 | macOS | 12 (Monterey)+ | Screen mirroring + audio-only |
| AirPlay 2 | iOS / iPadOS | 13+ | Screen mirroring + audio-only |
| Miracast | Windows | 10+ | Screen mirroring — *control plane only, see FR-58 … FR-60* |
| Miracast | Android | 4.2+ | Screen mirroring — *control plane only, see FR-58 … FR-60* |
| Google Cast | Chrome browser | 72+ | Tab/screen cast — *requires a registered Cast app ID, see FR-70* |
| Google Cast | Android | 5+ | Screen cast — *requires a registered Cast app ID, see FR-70* |
| UPnP / DLNA | Windows | 10+ | "Cast to Device" / Windows Media Player |
| UPnP / DLNA | VLC | 3.0+ | Playback → Renderer |
| UPnP / DLNA | BubbleUPnP, mconnect, other UPnP control points | – | Android / iOS control points |

> A UPnP control point does not push a stream — the **renderer fetches the media
> itself**. Only network-reachable `http`/`https` URLs can therefore be played;
> a `file:///` URI points at the sender's disk and is not readable by the TV.
> This is a property of the protocol and is documented for the user (FR-57).
