# PhairPlay – Requirements

Version: 4.0
Status: Active
Date: 2026-11-05

> **Change log v4.0 (2026-11-05).** This is the **baseline of the project**: every
> requirement now carries the eight attributes — identifier, description and
> rationale, source of obligation, measurable acceptance criterion, owner,
> verification method, expected deliverable, and a status that points at code,
> test and evidence. The machine-readable baseline is
> [`REQUIREMENTS_MATRIX.md`](REQUIREMENTS_MATRIX.md); the traceability chain is
> generated into [`TRACEABILITY.md`](TRACEABILITY.md); the previous version is
> archived unchanged as [`archive/REQUIREMENTS-v3.0.md`](archive/REQUIREMENTS-v3.0.md).
>
> **What changed against v3.0.** (1) The eight attributes were added to every
> requirement. (2) The class of every requirement was made explicit — `EXT` =
> binding external obligation, `INT` = internal project norm, `REC` =
> recommendation, **not** a legal duty (§0.1). (3) Six requirements are new and
> marked as such: FR-74, NFR-21, NFR-22, NFR-32, NFR-33, NFR-34; NFR-21 and
> NFR-22 were **free slots** in the v3.0 sequence, which jumped from NFR-20 to
> NFR-23. (4) Four statements that the code contradicts were corrected: NFR-23,
> NFR-28, NFR-31 and the missing NFR-21 / NFR-22. (5) The deviations of FR-11,
> FR-50, FR-59, FR-66 and NFR-26 are now visible in the line itself.
>
> **No identifier was renumbered and no identifier was reused.**

---

## 0. How to read this document

This file is the **long text** — the specification in prose. The machine-readable
baseline with the eight attributes as columns is
[`REQUIREMENTS_MATRIX.md`](REQUIREMENTS_MATRIX.md); the traceability chain
(requirement → implementation → test → evidence → status) is generated into
[`TRACEABILITY.md`](TRACEABILITY.md).

### 0.1 The class of a requirement — what "source" means here

Every requirement carries a class tag at the end of its line. **A recommendation
is not a legal obligation**, and the project never presents one as such.

| Tag | Meaning | Consequence of a violation |
|---|---|---|
| **EXT** | **Binding external obligation** — a law or a platform policy of a store or an operating system. | The distribution channel is blocked or a platform guarantee is broken. |
| **INT** | **Internal project norm** — a rule PhairPlay imposes on itself and publishes. | A defect against the project's own promise. Not a legal duty. |
| **REC** | **Recommendation / voluntary hardening** — a measure the team considers useful. | An improvement backlog item. **No legal duty, no store obligation.** |
| **INT+EXT** | The requirement is an internal norm *and* rests on an external obligation. | Both of the above. |

The **source** behind each class — the policy, the URL, the internal norm or the
audit finding — is in the `Class + Source` column of the matrix. The **status**
of every requirement lives there too: a requirement without a traceable piece of
evidence is **open**, never "probably fine". The open points are listed in
[`REMEDIATION_BACKLOG.md`](REMEDIATION_BACKLOG.md).

---

---

## 1. Functional Requirements

### 1.1 Service Discovery & Advertising

- FR-01: The app MUST advertise an AirPlay 2 receiver via mDNS (`_airplay._tcp`, `_raop._tcp`). · **INT**
- FR-02: The app MUST advertise a Miracast receiver via Wi-Fi Direct (P2P) service discovery. · **INT**
- FR-03: The app MUST optionally advertise a Google Cast receiver (if Cast SDK is registered). · **INT**
- FR-04: All four receivers (AirPlay, Miracast, Cast, UPnP/DLNA) can be enabled/disabled independently in Settings. *(Amended in v3.0 — was "all three services"; DLNA was added as the fourth receiver in M12, see ADR-004.)* · **INT**
- FR-05: The device name shown in sender pickers MUST be configurable in Settings (defaults to Android device name). · **INT**
- FR-06: All enabled receivers start advertising within 2 seconds of app or service launch. *(Amended in v3.0 — "all services" → "all enabled receivers"; a receiver that is switched off in Settings must not advertise.)* · **INT**
- FR-07: All services stop advertising within 5 seconds of being disabled or app close. · **INT**

### 1.2 AirPlay 2 Receiver

- FR-08: Accept Screen Mirroring connections from **macOS 12+** and **iOS/iPadOS 13+** via RTSP on TCP port 7000. · **INT**
- FR-09: Complete the RTSP handshake (OPTIONS → SETUP → ANNOUNCE → RECORD) without errors. · **INT**
- FR-10: Reject second concurrent AirPlay connection with RTSP 503. · **INT**
- FR-11: Auto-reconnect and resume advertising after connection loss. *(Status: implemented for the AirPlay receiver and the DLNA renderer — `PhairPlayService.registerNetworkCallback` re-advertises when the last network was lost; the DLNA renderer additionally rebuilds its description server when the device came back with a new address. FR-61 makes this binding for every enabled receiver. The Miracast and Cast receivers have no re-advertisement path — Miracast is a control plane only (ADR-004) and Cast needs a registered application ID.) OPEN POINT (v4.0): the re-advertisement path is implemented for the AirPlay receiver and for the DLNA renderer only. Backlog B-03.* · **INT**
- FR-12: Accept **audio-only AirPlay** streams (music, podcasts) from macOS and iOS senders, playing audio through the TV speakers without requiring a video stream. · **INT**


**The codec matrix of the AirPlay 2 sender** — mandatory and optional video, audio and container formats — is in [`CODEC_MATRIX.md`](CODEC_MATRIX.md) §1.

- FR-42: Accept **AirPlay Photo / Image** transfers sent by iOS/macOS (via the `/photo` HTTP endpoint). Display the received image full-screen. Support JPEG and PNG. Return to HomeScreen on connection close. (Requires AirPlay feature bit 1 `Photo` to be set in the `features` TXT record.) · **INT**

### 1.3 Miracast Receiver

> **Implementation status: control plane only.** PhairPlay advertises itself as a WFD
> sink and answers the WFD RTSP handshake, but **no Miracast media is rendered** —
> the project contains neither an MPEG-TS demuxer nor a WFD decoder, and a
> sideloaded app cannot become a Wi-Fi Display sink anyway, because the API that
> registers a sink is a hidden system API (ADR-004, FR-58 … FR-60).

- FR-13: Discover and accept incoming Miracast (Wi-Fi Display / WFD) connection requests. *(Status: partially implemented — the Wi-Fi Direct service `_wfd._tcp` is advertised and the WFD RTSP control port 7236 is opened. A sender that requires the hidden Wi-Fi Display API does not connect; see FR-58.)* · **INT**
- FR-14: Negotiate the WFD session (capability exchange, RTSP-WFD handshake). *(Status: implemented for the control plane — `OPTIONS`, `GET_PARAMETER`, `SET_PARAMETER`, `SETUP`, `PLAY`, `PAUSE`, `TEARDOWN` are answered; the capability set reports H.264 CHP/CBP and LPCM. Proof: `MiracastReceiverTest`.)* · **INT**
- FR-15: Decode and display the incoming video stream from the Miracast sender per the codec matrix below. *(Status: not implemented — no MPEG-TS demuxer, no WFD video decoder. Out of scope, see §3.)* · **INT**
- FR-16: Play audio from the Miracast stream per the codec matrix below. *(Status: not implemented — same reason as FR-15.)* · **INT**
- FR-17: Support Windows 10+, Android, and Samsung Galaxy as Miracast senders (Phase 3+). *(Status: not verifiable — without a media path there is no end-to-end sender compatibility; the control plane is tested against a synthetic RTSP client only.)* · **INT**
- FR-18: Show the Miracast connection state on the HomeScreen status card. *(Status: implemented — `MiracastReceiver` emits `ProtocolState`, `HomeFragment` renders the card. The state must be "not available on this device" and not "Advertising" when the platform forbids the feature, see FR-59.)* · **INT**


**The codec matrix of the WFD standard** — the capability announcement of the Miracast control plane, not a promise of playback — is in [`CODEC_MATRIX.md`](CODEC_MATRIX.md) §2.

### 1.4 Google Cast Receiver

- FR-19: Run as a Cast Custom Receiver application registered with the Google Cast SDK. · **EXT**
- FR-20: Accept Cast connections from Chrome/Android/macOS/iOS senders. · **EXT**
- FR-21: Support Cast screen mirroring from Chrome and Android senders. · **EXT**
- FR-22: Display Cast status on the HomeScreen status card. · **INT**
- FR-23: Gracefully degrade if the Cast SDK is unavailable (missing Google Play Services on Fire TV). · **INT**


**The codec matrix of the Cast runtime** — what the Google Cast SDK offers, not a decoder inside this project — is in [`CODEC_MATRIX.md`](CODEC_MATRIX.md) §3.

### 1.5 Service Control

- FR-24: The user MUST be able to **start**, **stop**, and **restart** all receiver services from the app UI. · **INT**
- FR-25: The receiver runs as an Android **ForegroundService** so it continues operating when the app is in the background (e.g., screensaver active). · **INT+EXT**
- FR-26: A **persistent notification** MUST be shown while the ForegroundService is running, with quick-action buttons: Stop, Restart. · **INT**
- FR-27: Service state (running/stopped/error) MUST be visible on the HomeScreen at all times. · **INT**
- FR-28: Stopping the service MUST release all network ports, close all connections, and stop advertising. · **INT**
- FR-29: Restarting the service MUST perform a clean stop followed by a clean start within 3 seconds. · **INT**

### 1.6 Settings

- FR-30: A dedicated **Settings screen** MUST be accessible from the HomeScreen. · **INT**
- FR-31: Settings MUST be persisted between app restarts (Android DataStore / SharedPreferences). · **INT**
- FR-32: The following settings MUST be configurable: · **INT**

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

- FR-33: Decode video using hardware `MediaCodec`. Supported codecs per protocol: · **INT**
  - **AirPlay**: H.264 AVC (mandatory, up to High Profile Level 5.2); H.265 HEVC (optional, hardware check required).
  - **Miracast**: H.264 AVC CHP/CBP (mandatory); H.265 HEVC (optional).
  - **Google Cast**: H.264 AVC + VP8 (mandatory); H.265 HEVC, VP9, AV1 (optional, API-level gated).
  - Software fallback is NOT used; if hardware decoder is unavailable, the stream is rejected gracefully.
- FR-34: Display decoded video full-screen maintaining aspect ratio. · **INT**
- FR-35: Play audio in sync with video (A/V drift ≤ 40ms). · **INT**
- FR-36: For **audio-only** streams (no video): play through the default audio output without launching the streaming screen. · **INT**
- FR-37: Return to HomeScreen (or remain on HomeScreen for audio-only) when stream ends. · **INT**
- FR-43: For **image / photo** transfers (AirPlay `/photo` endpoint): display received JPEG or PNG full-screen. No video decoder is involved; image is decoded via Android's `BitmapFactory`. Return to HomeScreen when sender disconnects. · **INT**

### 1.8 Internationalization (i18n)

- FR-38: All user-visible strings MUST be defined in Android resource files (never hardcoded in Kotlin/XML). · **INT**
- FR-39: The app MUST support at least **English** and **German** at launch. · **INT**
- FR-40: The i18n structure MUST allow adding new languages without changing Kotlin or layout files. · **INT**
- FR-41: Date/time formats, number formats, and text direction MUST respect the device locale. · **INT**

### 1.9 UPnP / DLNA MediaRenderer

PhairPlay implements a UPnP MediaRenderer (see [ADR-004](../decisions/ADR-004-dlna-mediarenderer-roadmap.md)).
All DLNA logic lives in the package `com.phairplay.dlna`; the AirPlay 2 stack is not
touched by it, and DLNA is an **additional** receiver — it replaces neither AirPlay,
nor Miracast, nor Cast.

- FR-44: The app MUST advertise itself as a UPnP MediaRenderer via SSDP on `239.255.255.250:1900` using the device type `urn:schemas-upnp-org:device:MediaRenderer:1`, and MUST answer an incoming `M-SEARCH` whose search target is `ssdp:all` with **exactly three** `200 OK` datagrams — `upnp:rootdevice`, the MediaRenderer device type and the UDN — and every other matching search target with exactly one datagram. Every answer MUST carry the `LOCATION`, `USN`, `ST`, `EXT` and `SERVER` headers. *Implementation: `com.phairplay.dlna.SsdpAdvertiser`, `SsdpResponder`, `SsdpMessages`. Proof: `SsdpResponderTest`, `SsdpAdvertiserLiveTest`.* · **INT**
- FR-45: The app MUST serve the UPnP device description and the service descriptions (SCPD) of `ConnectionManager:1`, `AVTransport:1` and `RenderingControl:1` over HTTP under the `LOCATION` URL it advertises. *Implementation: `DlnaDescriptionServer`, `DlnaDescriptionBuilder`, `DlnaServiceCatalog`. Proof: `DlnaDescriptionServerTest`, `DlnaDescriptionBuilderTest`.* · **INT**
- FR-46: The app MUST answer the UPnP control actions `GetProtocolInfo`, `GetCurrentConnectionIDs`, `GetCurrentConnectionInfo` (ConnectionManager), `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`, `GetTransportInfo`, `GetPositionInfo`, `GetMediaInfo`, `GetCurrentTransportActions` (AVTransport) and `GetVolume`, `SetVolume`, `GetMute`, `SetMute`, `GetVolumeDB` (RenderingControl). An unknown action MUST be answered with the SOAP fault `401 Invalid Action` (HTTP 500), an invalid or missing argument with `402 Invalid Args`. *Implementation: `DlnaSoapDispatcher`, `DlnaAvTransportService`, `SoapResponses`.* · **INT**
- FR-47: The `GetProtocolInfo` answer MUST contain a non-empty `Source` and a non-empty `Sink` protocol list. A renderer with an empty `Source` list is treated by control points as a device that cannot fetch anything and is hidden from the renderer picker. *Implementation: `DlnaProtocolInfo`.* · **INT**
- FR-48: The app MUST support GENA eventing: `SUBSCRIBE` and `UNSUBSCRIBE` on `/upnp/event/<Service>`, a subscription answer that carries `SID`, `TIMEOUT` and `EXT`, and `LastChange` notifications in the nested UPnP format whenever the transport state or the render state changes. A subscription MUST expire after its timeout, and a control point that no longer answers MUST be dropped instead of being notified forever. *Implementation: `GenaEventManager`, `GenaLastChange`, `DlnaDescriptionServer`. Proof: `GenaEventManagerTest`.* · **INT**
- FR-49: The app MUST refuse a GENA callback URL that is not an `http` URL whose host is a literal, non-obfuscated IPv4 address in one of the private ranges `10/8`, `172.16/12`, `192.168/16` and inside the same /24 as the renderer. Loopback, link-local (`169.254/16`, the router metadata address), multicast, broadcast, decimal/octal/hexadecimal notations, host names and every IPv6 form MUST be rejected with `412 Precondition Failed`. *Implementation: `GenaCallbackPolicy`, `GenaEventManager.subscribe`. Proof: `GenaCallbackPolicyTest`, `GenaEventManagerTest`, `DlnaDescriptionServerTest`.* · **INT**
- FR-50: The renderer MUST fetch media only over `http` and `https`. A `CurrentURI` with any other scheme (`file`, `smb`, `ftp`, `rtsp`, …) MUST be refused with the SOAP fault `402 Invalid Args` and MUST NOT be opened. **Closed on 2026-11-05** (commits `b4e5afa`, `55acde4`): the own scheme allow-list was removed — `DlnaMediaUri` now delegates to the shared `FetchableUrlPolicy.check`, which accepts `http` and `https` only. The test that asserted the opposite was replaced by "rejects a file URI because it would read the local file system". Audit finding SEC-04, backlog B-01. · **INT**
- FR-51: Every HTTP request the DLNA server accepts MUST be bounded: request line ≤ 8 KB, header block ≤ 16 KB, body ≤ 64 KB, socket read timeout 2 s, total request deadline 10 s, at most 8 concurrent workers and 16 queued clients. Further clients MUST receive `503 Service Unavailable`; an over-sized request MUST be answered with `414` / `431` / `413` instead of being buffered. *Implementation: `HttpRequestReader`, `DlnaDescriptionServer`. Proof: `DlnaDescriptionServerTest`.* · **INT**
- FR-52: The XML parser of the control plane MUST reject a DOCTYPE declaration and external entities, MUST disable entity expansion, MUST bound the payload to 64 KB and the nesting depth to 32 levels, and MUST fail closed when the platform parser cannot be hardened. *Implementation: `SecureSoapXml`. Proof: `SecureSoapXmlTest`, `DlnaSoapDispatcherSecurityTest`.* · **INT**
- FR-53: No answer of the DLNA control plane may contain a stack trace, an exception class name, a file path, a library version or a host path. Error details belong in the log, not in the answer. *Implementation: `SoapResponses.fault`, `DlnaSoapDispatcher.dispatch`. Proof: `DlnaDescriptionServerTest`.* · **INT**
- FR-54: The UPnP/DLNA receiver MUST be switchable independently of the other receivers in Settings, and its state (disabled / advertising / connected / error) MUST be visible on the HomeScreen. *Implementation: `AppSettings.dlnaEnabled`, `SettingsRepository`, `SettingsFragment`, `HomeFragment`, `PhairPlayService.startDlna`.* · **INT**
- FR-55: When the DLNA receiver is stopped, the app MUST send the SSDP `byebye`, close the description server socket, drop every GENA subscription, release the media player and stop advertising. No port may stay bound and no event may be delivered afterwards. *Implementation: `DlnaReceiver.stop`, `DlnaDescriptionServer.stop`, `GenaEventManager.stop`.* · **INT**
- FR-56: The media keys of the TV remote (play/pause, pause, stop) MUST drive the **same** AVTransport state machine that the control point drives, so that the state a control point reads and the `LastChange` event it receives stay truthful. *Implementation: `DlnaReceiver.sendRemoteCommand`, `DlnaSoapDispatcher.onRemoteCommand`, `MainActivity` media-key handling.* · **INT**
- FR-57: The user-facing documentation MUST state that a UPnP renderer fetches the media itself, that therefore only network-reachable `http`/`https` URLs can be played, and that a `file:///` URI points at the sender's disk and cannot be rendered by the television. This is a property of the protocol, not a defect, and MUST NOT be presented as one. *Proof: `README.md`, `docs/guides/`, `TECHNICAL_SPEC.md` §12.* · **INT**

### 1.10 Miracast receiver — scope and honest limits

Miracast is the weakest link of the project: a sideloaded third-party Android app
cannot become a Wi-Fi Display sink on stock consumer hardware, because the API that
registers a sink (`WifiP2pManager.setWfdInfo`) is a hidden system API gated behind a
system permission (see ADR-004). The requirements below describe what PhairPlay
actually does — not what a Miracast sink would do.

- FR-58: The Miracast receiver MUST use public Android APIs only. It MUST NOT call hidden or system APIs (for example `WifiP2pManager.setWfdInfo`) and MUST NOT require the system permission `CONFIGURE_WIFI_DISPLAY`. A sideloaded app cannot obtain that permission, so an implementation that depends on it cannot work at all. *Implementation: `com.phairplay.miracast.MiracastReceiver` uses `WifiP2pManager` / `WifiP2pDnsSdServiceInfo` only.* · **INT**
- FR-59: When `WifiP2pManager` is unavailable or the required runtime permission is not granted, the Miracast receiver MUST report an explicit "not available on this device" state and the HomeScreen MUST show that state. It MUST NOT report "Advertising". *Implementation: `MiracastReceiver.initializeWifiP2p`, `MiracastReceiver.registerP2pService`. **OPEN POINT (v4.0): the deviation still exists** — `ReceiverSupervisor.kt:175` sets `ProtocolState.ADVERTISING` before the receiver can report the error, so the home screen briefly claims to advertise a receiver that does not. Backlog B-06.* · **INT**
- FR-60: The documentation MUST state that the Miracast implementation covers the discovery and control plane only (Wi-Fi Direct service advertisement, WFD RTSP handshake, capability exchange) and that no Miracast media is rendered: the project contains neither an MPEG-TS demuxer nor a WFD video/audio decoder. *Proof: `grep -rn "MiracastDecoder" app/src/main` → 0 matches.* · **INT**

### 1.11 Resilience and re-advertisement

- FR-61: After the last network interface is lost and comes back (Wi-Fi off/on, TV standby, VPN up/down), every enabled receiver MUST re-advertise within 5 seconds — without an app restart and without dropping the open sockets. *Implementation: `PhairPlayService.registerNetworkCallback`, `NetworkRecoveryMonitor`, `ReceiverSupervisor.readvertiseAll`, `AirPlayReceiver.readvertise`, `DlnaReceiver.readvertise`, `SsdpAdvertiser.readvertise`. The DLNA renderer rebuilds its description server when the device came back with a different address, because the description URL contains that address. Proof: `DlnaReadvertisePlanTest` (the decision what has to be rebuilt); the socket behaviour itself needs a device and is listed in `docs/guides/DEVICE_TEST_PLAN.md`.* · **INT**
- FR-62: A failed advertisement MUST be retried with a bounded backoff and MUST end in an explicit error state. A receiver MUST NOT disappear silently from the sender pickers while the UI claims that it is advertising. *Implementation: `MdnsRegistrationTracker` (retry after 1 s / 3 s / 6 s), `MdnsService`. Proof: `MdnsRegistrationTrackerTest`, `MdnsServiceTest`.* · **INT**

### 1.12 Resource budgets

- FR-63: The AirPlay mirroring frame queue MUST be bounded twice: by item count (90 frames ≈ 1.5 s at 60 fps — the latency bound) **and** by total bytes (32 MB — the memory bound). When the byte budget is exceeded the oldest frames MUST be dropped, the network reader MUST never block, and the byte counter MUST be exact. *Implementation: `com.phairplay.airplay.handshake.MirrorFrameQueue`, `MirrorStreamServer`. Proof: `MirrorFrameQueueTest`.* · **INT**
- FR-64: The DLNA photo pipeline MUST refuse a payload above 20 MB and MUST downsample the decoded bitmap to at most 1920 px on its longest edge before it reaches the screen. *Implementation: `DlnaImageDecoder`, `DlnaReceiver.fetchBounded`. Proof: `DlnaImageDecoderTest`.* · **INT**

### 1.13 Codec capability advertising

- FR-65: The app MUST query the decoders and the audio output encodings of the device at runtime (`MediaCodecList`, `AudioManager.getDevices`) and MUST advertise a codec only when the device can actually decode it. Optional codecs (HEVC, VP9, AV1, AC-3, E-AC-3) MUST NOT be advertised when they are missing. *Implementation: `com.phairplay.media.MediaCapabilities`, `DeviceMediaCapabilities`. Proof: `MediaCapabilitiesTest`.* · **INT**
- FR-66: The protocol list that the renderer advertises SHOULD be derived from the runtime capability snapshot of FR-65. As long as the list is a fixed constant, it MUST NOT contain an entry that the receiver cannot handle, and the deviation MUST be documented in the technical specification. **OPEN POINT (v4.0):** `DlnaProtocolInfo` is still a fixed constant, not a derivation of the runtime snapshot of FR-65. The deviation is documented in `TECHNICAL_SPEC.md` §12. Backlog B-07. · **INT**

### 1.14 Internationalization (extension of §1.8)

- FR-67: The string resources of every shipped locale MUST be a subset of the keys of the base locale (`res/values/strings.xml`). A key that exists only in a locale file is an error. A key that is missing in a locale file is allowed only when the base text is the intended translation. *Proof: a key diff of `res/values*/strings.xml` in CI.* · **INT**
- FR-68: A user-visible string that names a **protocol** (AirPlay, Miracast, Cast, DLNA) MUST be identical in every locale; a user-visible string that names a **state** (disabled / advertising / connected / error) MUST exist in every locale the app is offered in. *Proof: the same key diff plus a value comparison of the `protocol_*` keys.* · **INT**

### 1.15 Optional receivers: graceful degradation

- FR-69: On a device without Google Play Services (the `firetv` flavour), the Cast receiver MUST report "disabled" and MUST NOT initialise the Cast SDK. The HomeScreen MUST explain that Cast requires Google Play Services. *Implementation: `app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt`. Proof: `CastReceiverTest`.* · **INT**
- FR-70: When no Cast application ID is registered, the Cast receiver MUST report an explicit "not configured" error and the documentation MUST point to the registration guide (`docs/guides/CAST_APP_ID.md`). It MUST NOT claim to advertise a Cast receiver. *Implementation: `CastReceiver.isConfigured`, `CastReceiver.start`. Proof: `CastReceiverTest`.* · **INT**

### 1.16 Video aspect ratio robustness

- FR-71: When the hardware decoder reports a crop rectangle whose aspect ratio differs from the aspect ratio of the coded picture by more than 5%, the app MUST treat that rectangle as a **scaled picture** and MUST use the coded aspect ratio (parsed from the stream's SPS) for the on-screen aspect-fit. A decoder that scales the picture to the panel and reports the scaled size as a crop rectangle would otherwise stretch the picture horizontally. *Implementation: `com.phairplay.airplay.OutputSizeResolver`, `VideoDecoder.publishOutputSize`. Proof: `OutputSizeResolverTest`.* · **INT**

### 1.17 Privacy policy and store publication

- FR-72: The app MUST offer the privacy policy from its own settings, so that the policy is reachable **inside the app** and not only in a store listing. *Implementation: the row "Privacy policy" in `SettingsFragment`, the URL in the string resource `privacy_policy_url`. Proof: the privacy policy gate `scripts/check-privacy-policy.sh` and the APK build.* · **INT+EXT**
- FR-73: The privacy policy MUST name **every** permission the app declares, and it MUST NOT be older than the manifest. Google Play reviews the policy against the app — a permission without a mention in the policy is a finding in the review. *Proof: `scripts/check-privacy-policy.sh` with its self-test `scripts/test-check-privacy-policy.sh` (six cases, including the negative ones).* · **INT+EXT**

### 1.18 Plist decoder — defence in depth (new in v4.0)

- FR-74: The binary property list decoder MUST refuse a payload above 1 MB itself, independently of the caller that hands it the bytes. *Rationale: today the 1 MB budget of the RTSP reader is the only guard in front of `PlistCodec`; a future call site without that budget would have no second line of defence (audit finding SEC-11). **Class: REC — a recommendation, not a legal obligation.** Implementation target: `PlistCodec.MAX_PLIST_BYTES`. Proof: `PlistCodecTest` — a new case `decode refuses a payload above one megabyte`.* · **REC**


---

## 2. Non-Functional Requirements

### 2.1 Performance (unchanged)
- NFR-01: Video latency ≤ 100ms on local 5 GHz Wi-Fi. · **INT**
- NFR-02: Frame rate ≥ 25fps sustained. · **INT**
- NFR-03: Peak RAM ≤ 150 MB (single active stream). *The budget is implemented as the constant `PerformanceBudgets.MAX_RESIDENT_BYTES`; the app measures its own resident memory and shows it against the budget in the diagnostics report.* · **INT**
- NFR-04: Average CPU ≤ 30% on mid-range TV SoC. *The budget is implemented as the constant `PerformanceBudgets.MAX_CPU_PERCENT`; the app measures its own CPU load and shows it against the budget in the diagnostics report.* · **INT**

### 2.2 UI / UX
- NFR-05: UI design MUST follow the **Google TV Streamer** design language: dark background, card-based layout, large focus indicators, D-pad navigable. · **INT**
- NFR-06: All interactive elements MUST be focusable via D-pad / remote control. · **INT**
- NFR-07: Minimum touch target and focus target size: 48dp. · **INT**
- NFR-08: Text minimum size: 18sp for body, 32sp for titles. · **INT**
- NFR-09: Sufficient contrast ratio for TV viewing from 3 meters. · **INT**
- NFR-10: The HomeScreen MUST show the active protocol, sender name, and duration within 1 second of change. · **INT**

### 2.3 Privacy & Security

- NFR-11: No ads, no analytics, no telemetry. · **INT**
- NFR-12: All network input validated before processing. · **INT**
- NFR-13: No hardcoded secrets. · **INT**
- NFR-14: Minimal Android permissions. · **INT+EXT**
- NFR-26: Every outbound connection of the app MUST target an address in the local network (private IPv4 range). The code MUST NOT contain a hardcoded internet endpoint. **OPEN POINT (v4.0): a public address is still accepted** as a media address — the loopback, the link-local and the obfuscated notations are refused, but a routable public address passes. Whether that is acceptable is an **owner decision**, not a security decision (audit SEC-04). Backlog B-12. *Proof: `grep -rn "https\?://" app/src/main --include=*.kt` shows documentation URLs only; an integration test asserts that the GENA callback guard rejects every non-private address.* · **INT**
- NFR-27: Every server socket of the app MUST bound the size and the duration of the requests it accepts and MUST answer an over-sized or expired request with a status code instead of allocating the payload. See FR-51 (DLNA HTTP), FR-63 (mirroring queue) and the RTSP limits of the AirPlay stack. · **INT**
- NFR-28: If a protocol needs cleartext HTTP (the DLNA media fetch, the GENA `NOTIFY`, the DACP reverse channel), the app MUST declare a network security configuration that permits cleartext **only** for private LAN address ranges, and MUST NOT enable cleartext globally. As long as no such configuration exists, the affected features MUST be documented as unavailable — see the open point in the handover. **Corrected in v4.0 (2026-11-05): the cited proof does not exist — `app/src/main/res/xml/network_security_config.xml` is not in the repository (verified with `find app/src -name "network_security_config*"` → 0 matches). The requirement is therefore OPEN; the GENA `NOTIFY` and the DACP reverse command cannot work on a device that enforces the platform default. Backlog B-13.** *Proof when it is implemented: the file exists and lists no public domain; a device test plays an `http://` media URL from a local server.* · **INT**
- NFR-29: Every log line of the app MUST be reduced before it is written: host part of an IP address, device-specific half of a MAC address, complete UUIDs, and the credentials and query string of a URL MUST be replaced by placeholders. The reduction MUST be active in every build, including release. *Implementation: `com.phairplay.util.Logger`, `LogRedactor`, `LogRedactionMode`. Proof: `LoggerTest`, `LogRedactorTest`.* · **INT**
- NFR-30: The app MUST declare only the Android permissions it actually uses, and every declared permission MUST be justified in `PRIVACY.md`. A runtime permission MUST be requested only when the user switches the corresponding feature on, and the request MUST be preceded by a rationale. *Proof: the permission table in `PRIVACY.md` covers every `<uses-permission>` line of the manifest; `grep -rn "requestPermissions" app/src/main` lists only the notification permission.* · **INT+EXT**
- NFR-31: A release build MUST write warnings and errors to the log (after the reduction of NFR-29); a release build that writes nothing makes a field failure undiagnosable. **Corrected in v4.0 (2026-11-05): this requirement is OPEN. `PhairPlayApp.kt:40` plants a log tree only under `BuildConfig.DEBUG`, so a release build writes nothing at all, and the cited proof test does not exist. Backlog B-15.** *Proof when it is implemented: a unit test with a release log tree asserts that an error is written and that a debug message is dropped.* · **INT**

### 2.4 Compatibility
- NFR-15: Google TV: Android 10+ (API 29+), ARMv8. · **INT**
- NFR-16: Fire TV: Android 7.1+ (API 25+), ARMv7/ARMv8. · **INT**
- NFR-17: AirPlay sender (screen mirroring): macOS 12+, iOS/iPadOS 13+. · **INT**
- NFR-18: AirPlay sender (audio-only): macOS 12+, iOS/iPadOS 13+. · **INT**
- NFR-19: Miracast sender: Windows 10+, Android 4.2+. · **INT**
- NFR-20: Cast sender: Chrome 72+, Android 5+. · **EXT+INT**

### 2.5 Code Quality
- NFR-23: No source file exceeds 400 lines (soft limit). A file MUST NOT exceed 550 lines (hard limit). *(Amended in v3.0 — the requirement previously named 400 lines as an absolute limit, which contradicted the project rule in `docs/CONTRIBUTING.md` (soft 400 / hard 550). **Corrected in v4.0 (2026-11-05):** the two files that were above the soft limit have been split in the meantime. Measured on `main` @ `34a166c`: the largest Kotlin file is `ui/DiagnosticsFragment.kt` with 396 lines, and `scripts/check-file-size.sh` reports 0 files above the soft limit and 0 above the hard limit. The old statement is obsolete.)* · **INT**
- NFR-24: Every public method has at least one unit test. · **INT**
- NFR-25: All classes have KDoc headers. · **INT**

### 2.6 Platform obligations and production readiness (new in v4.0)

> **Why this section exists.** v3.0 jumped from NFR-20 to NFR-23; **NFR-21 and
> NFR-22 were free and never filled**. They carried two genuine store obligations
> that no other requirement covered; NFR-32 … NFR-34 close the production-readiness
> gaps of the security and compliance cycle of 2026-11.

- NFR-21: The published application MUST target the API level that the Google Play policy requires at the time of the submission. *Rationale: Play rejects an upload whose target API level is below the current minimum; the obligation changes with the calendar, so it must be re-checked at every submission and not only at build time. **Class: EXT — a binding platform obligation** (Google Play target API policy, state 2026-11-02, <https://support.google.com/googleplay/android-developer/answer/11926878>). Today `targetSdk = 35` (`app/build.gradle.kts:30`). Proof: `grep -n "targetSdk" app/build.gradle.kts` and the check in `docs/project/RELEASE_READINESS.md`.* · **EXT**

- NFR-22: The native libraries of the application MUST be compatible with a 16 KB page size. *Rationale: the app ships `libplayfair.so` and `libalac.so` (`app/src/main/cpp/CMakeLists.txt`); a device that uses 16 KB pages cannot load a library that was linked for 4 KB pages, and Play requires the support for apps that target Android 15 and later. **Class: EXT — a binding platform obligation** (Android 16 KB page size requirement, state 2026-11-02, <https://developer.android.com/guide/practices/page-sizes>). The NDK version in use (`ndkVersion = 28.2.13676358`) supports the alignment, but the alignment of the **shipped** libraries is not yet proven. Proof: `zipalign -c -P 16 -v 4 app-googletv-release.apk` reports every `.so` as aligned.* · **EXT**

- NFR-32: Every dependency version MUST be pinned — no dynamic version, no snapshot — and the licence of every dependency MUST be compatible with the Apache 2.0 distribution of the project; the licence inventory MUST be part of the release. *Rationale: an unpinned version makes a release unreproducible, and an incompatible licence (for example the proprietary Google Cast SDK) constrains the distribution channel. **Class: INT+EXT** — the pinning is an internal project norm, the licence compatibility is an external obligation (Apache 2.0 §4). Proof: `grep -nE '\+|latest|snapshot' gradle/libs.versions.toml` returns 0 matches; the licence list is in `NOTICE`.* · **INT+EXT**

- NFR-33: A release MUST be signed with the owner's production key; an alpha or a beta key MUST NOT be reused for a stable release, and the signature MUST be verified before the publication. *Rationale: a release signed with a throw-away key cannot be updated later, and the Play App Signing fingerprint of the store build differs from the fingerprint of a sideloaded build. **Class: INT — an internal project norm.** Proof: `apksigner verify --print-certs` on both APKs and on the `.aab`, the fingerprint recorded in the release notes.* · **INT**

- NFR-34: Every requirement of this baseline MUST have a traceable acceptance criterion and a traceable piece of evidence; the traceability matrix MUST be generated by a script and MUST NOT be written by hand. *Rationale: a requirement that cannot be traced is a wish, and a hand-written traceability matrix drifts away from the code within one cycle. **Class: INT — an internal project norm.** Proof: `scripts/build-traceability.sh` writes `docs/spec/TRACEABILITY.md` with 0 rows without evidence; `scripts/test-build-traceability.sh` proves that the script turns red when a requirement loses its test.* · **INT**


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
> scope — Not planned"*. That contradicted [ADR-004](../decisions/ADR-004-dlna-mediarenderer-roadmap.md)
> and the code: the package `com.phairplay.dlna` is implemented and shipped. The
> requirement moved into §1.9 (FR-44 … FR-57); the table now lists only what is
> genuinely not implemented.

### FairPlay DRM – Evaluation Note

**What is FairPlay?** Apple's DRM system for HLS content, used by Apple TV+ and
other Apple-licensed platforms. **Technical path:** (1) register as an Apple
Developer; (2) apply for an **FPS deployment package** (Key Security Module — KSM);
(3) implement the FPS key exchange with the binary-only, Apple-supplied KSM
library; (4) accept that the KSM is platform-specific — Apple ships it for
macOS/iOS/tvOS and has never published an Android-compatible one.

**Conclusion for PhairPlay v1:**
- FairPlay is **not implementable** on Android without Apple providing an Android KSM binary — which they have not done.
- Even if Apple provided one, the license terms would likely be **incompatible with open-source distribution** under Apache 2.0.
- **FairPlay is excluded from PhairPlay v1 and is not on the roadmap** unless Apple changes their licensing policy.
- PhairPlay can receive unencrypted AirPlay streams and AirPlay streams encrypted with the open AES-128-CTR session key mechanism. FairPlay-protected premium content (e.g., Apple TV+) will not play.

> **Note on open codecs:** H.265 HEVC, VP9 and AV1 are fully implementable on Android via `MediaCodec` with hardware support checks; they are planned as optional features in v2.
>
> **Note on HDCP and Widevine/PlayReady:** HDCP (for Miracast) is negotiated at the WFD level and enforced by hardware; Widevine and PlayReady (for Cast) are handled by the Cast SDK and `MediaDrm`.

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
> itself**. Only network-reachable `http`/`https` URLs can be played; a `file:///`
> URI points at the sender's disk. A property of the protocol, documented for the
> user (FR-57).
