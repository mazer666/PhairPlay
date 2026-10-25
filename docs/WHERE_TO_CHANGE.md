# Where to Change What

This file is written for someone who **does not know the codebase yet**.

You have an idea — "I want to change X" — and you want to know which file to
open. Find your row in the tables below. Every path in them exists; every path
was checked on 2026-10-24.

Two rules before you start:

1. **Read [`docs/ARCHITECTURE.md`](ARCHITECTURE.md)** for the package you are
   about to touch. It explains what the package is for and where the boundary
   between the protocols runs.
2. **Read [`docs/spec/REQUIREMENTS.md`](spec/REQUIREMENTS.md)** if your change
   alters what the product does. A behaviour change needs a requirement, a
   test, and — if it is material — a CR.

---

## The map of the source tree

```text
app/src/main/kotlin/com/phairplay/
├── MainActivity.kt          the single Activity that hosts every screen
├── PhairPlayApp.kt          application class: logging, one-time init
├── airplay/                 the AirPlay 2 stack
│   └── handshake/           pairing, FairPlay, the mirror and audio streams
├── dlna/                    the UPnP / DLNA MediaRenderer stack
├── miracast/               the Miracast (Wi-Fi Display) stack
├── media/                   what this device can decode
├── service/                 the foreground service and its four helpers
├── settings/                the settings model and its persistence
├── ui/                      the screens and their fragments
└── util/                    logging, redaction, address policies, network and Base64 helpers
```

`app/src/googletv/` and `app/src/firetv/` hold the **flavour-specific** code
(Google TV / Fire TV). `app/src/main/cpp/` holds the **native** code (FairPlay
and ALAC). `test-runner/` holds the **JVM test module** that CI runs — see
[`TESTING.md`](TESTING.md).

---

## The table

### The product surface

| I want to change… | Go to | Read first |
|---|---|---|
| …the **device name** the sender sees | `app/src/main/kotlin/com/phairplay/settings/AppSettings.kt` and `app/src/main/kotlin/com/phairplay/settings/SettingsRepository.kt` | [`spec/REQUIREMENTS.md`](spec/REQUIREMENTS.md) FR-05, FR-32 |
| …a **setting** (a new toggle, a new default) | `app/src/main/kotlin/com/phairplay/settings/AppSettings.kt` (the model) → `app/src/main/kotlin/com/phairplay/settings/SettingsRepository.kt` (the DataStore) → `app/src/main/kotlin/com/phairplay/ui/SettingsFragment.kt` (the screen) → `app/src/main/res/values/strings.xml` (the label) | FR-38 — every user-visible string belongs in a resource file |
| …a **string** on any screen | `app/src/main/res/values/strings.xml`, and the translations in `app/src/main/res/values-de/strings.xml` and `app/src/main/res/values-fr/strings.xml` | FR-39, FR-40 |
| …the **Home screen** or a protocol status card | `app/src/main/kotlin/com/phairplay/ui/HomeFragment.kt` and `app/src/main/res/layout/fragment_home.xml` | NFR-05…NFR-10 |
| …the **diagnostics screen** (the report, the self test, the own resource measurement) | `app/src/main/kotlin/com/phairplay/ui/DiagnosticsFragment.kt`, the report and the measurement in `app/src/main/kotlin/com/phairplay/diagnostics/`, and `app/src/main/res/layout/fragment_diagnostics.xml` | NFR-03, NFR-04 |
| …the **waiting screen** (the "ready to receive" screen) | `app/src/main/kotlin/com/phairplay/ui/WaitingScreen.kt` and `app/src/main/res/layout/screen_waiting.xml` | — |
| …the **streaming screen** (the video surface) | `app/src/main/kotlin/com/phairplay/ui/StreamingScreen.kt` and `app/src/main/kotlin/com/phairplay/ui/VideoAspectFit.kt` | ADR-003 |
| …the **now-playing overlay** (title, artist, artwork) | `app/src/main/kotlin/com/phairplay/ui/NowPlayingScreen.kt`, the shared state in `app/src/main/kotlin/com/phairplay/airplay/NowPlayingState.kt` and the parser in `app/src/main/kotlin/com/phairplay/airplay/NowPlayingInfo.kt` | — |
| …the **photo display** | `app/src/main/kotlin/com/phairplay/ui/PhotoScreen.kt`, the payload check in `app/src/main/kotlin/com/phairplay/airplay/PhotoHandler.kt` and the value object in `app/src/main/kotlin/com/phairplay/service/PhotoFrame.kt` | FR-42, FR-43 |
| …the **PIN entry screen** | `app/src/main/kotlin/com/phairplay/ui/PinScreen.kt` | — |
| …the **colours** or the **theme** | `app/src/main/res/values/colors.xml` and `app/src/main/res/values/themes.xml` | NFR-09 |
| …the **focus ring** or an icon | `app/src/main/res/drawable/focus_ring_selector.xml` and the files next to it | NFR-06, NFR-07 |
| …the **navigation panel** (the Home / Settings switch) | `app/src/main/kotlin/com/phairplay/ui/FragmentNavigator.kt` | ADR-003 |
| …the **binding between the Activity and the service** | `app/src/main/kotlin/com/phairplay/ui/ServiceConnectionBinder.kt` | — |

### The service and its lifecycle

| I want to change… | Go to | Read first |
|---|---|---|
| …the **foreground service** (start, stop, restart, the notification) | `app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt` | ADR-002, FR-24…FR-29 |
| …**starting and stopping** the service from code | `app/src/main/kotlin/com/phairplay/service/ServiceController.kt` and `app/src/main/kotlin/com/phairplay/service/ServiceState.kt` | FR-24…FR-29 |
| …the **state the service publishes** (the flows the UI observes) | `app/src/main/kotlin/com/phairplay/service/ServiceStateBus.kt` | — |
| …the **four protocol receivers** (which one starts, when it stops) | `app/src/main/kotlin/com/phairplay/service/ReceiverSupervisor.kt` | ADR-001 |
| …the **foreground notification** (its text, its actions) | `app/src/main/kotlin/com/phairplay/service/ServiceNotificationManager.kt` | — |
| …the **re-advertisement after a network outage** | `app/src/main/kotlin/com/phairplay/service/NetworkRecoveryMonitor.kt` | FR-61 |
| …the **start on boot** behaviour | `app/src/main/kotlin/com/phairplay/service/BootReceiver.kt` | FR-32 |
| …the **permissions** | `app/src/main/AndroidManifest.xml`, and the permission table in [`audit/2026-10-24-security-audit.md`](audit/2026-10-24-security-audit.md) §4 | NFR-14 — a permission without a traced call site is a finding |

### AirPlay 2 — discovery and the RTSP control plane

| I want to change… | Go to | Read first |
|---|---|---|
| …the **mDNS advertising** (the device in the AirPlay menu) | `app/src/main/kotlin/com/phairplay/airplay/MdnsService.kt` (the lifecycle) and `app/src/main/kotlin/com/phairplay/airplay/MdnsServiceRegistrar.kt` (the two registrations) | FR-01…FR-07 |
| …the **mDNS retry** (the delays 1 s / 3 s / 6 s) | `app/src/main/kotlin/com/phairplay/airplay/MdnsService.kt` and the bookkeeping in `app/src/main/kotlin/com/phairplay/airplay/MdnsRegistrationTracker.kt` | FR-04 |
| …the **advertised device name** (sanitising, the 63-character limit) | `app/src/main/kotlin/com/phairplay/airplay/MdnsServiceName.kt` | — |
| …the **device information** the sender reads (`GET /info`) | `app/src/main/kotlin/com/phairplay/airplay/handshake/InfoResponder.kt` and `app/src/main/kotlin/com/phairplay/airplay/RtspDeviceInfoHandler.kt` | — |
| …the **RTSP control socket** (bind, accept, the 1 MB message cap) | `app/src/main/kotlin/com/phairplay/airplay/RtspServer.kt` | SEC-02 in [`SECURITY.md`](../SECURITY.md) |
| …the **RTSP request parsing** | `app/src/main/kotlin/com/phairplay/airplay/RtspRequestReader.kt` | — |
| …the **RTSP method dispatch** (which handler answers which request) | `app/src/main/kotlin/com/phairplay/airplay/RtspRequestRouter.kt` | — |
| …the **classic RTSP verbs** (OPTIONS / ANNOUNCE / SETUP / RECORD / TEARDOWN / PAUSE) | `app/src/main/kotlin/com/phairplay/airplay/RtspVerbHandler.kt` | FR-08…FR-10 |
| …the **AirPlay 2 pairing endpoints** (`/pair-setup`, `/pair-verify`, `/pair-pin-start`) | `app/src/main/kotlin/com/phairplay/airplay/RtspPairingHandler.kt` | SEC-05 in [`SECURITY.md`](../SECURITY.md) |
| …the **AirPlay 2 mirroring SETUP** and the FairPlay exchange | `app/src/main/kotlin/com/phairplay/airplay/RtspMirrorHandler.kt` | — |
| …the **media parameters** (`GET_PARAMETER` / `SET_PARAMETER`: volume, artwork, metadata) | `app/src/main/kotlin/com/phairplay/airplay/RtspParameterHandler.kt` | — |
| …the **shared RTSP session state** | `app/src/main/kotlin/com/phairplay/airplay/RtspSessionState.kt` | — |
| …the **RTSP request and response types** | `app/src/main/kotlin/com/phairplay/airplay/RtspMessages.kt` | — |
| …the **SDP parsing** (codecs, keys, channels) | `app/src/main/kotlin/com/phairplay/airplay/SdpParser.kt` | — |
| …the **pairing** (HomeKit-style Ed25519/X25519) | `app/src/main/kotlin/com/phairplay/airplay/handshake/PairingSession.kt`, `app/src/main/kotlin/com/phairplay/airplay/handshake/PairingKeys.kt`, `app/src/main/kotlin/com/phairplay/airplay/handshake/PairingStore.kt` | SEC-05 in [`SECURITY.md`](../SECURITY.md) |
| …the **legacy PIN pairing** (SRP-6a) | `app/src/main/kotlin/com/phairplay/airplay/handshake/LegacyPairSetupPin.kt` | — |
| …the **FairPlay** key decryption | `app/src/main/kotlin/com/phairplay/airplay/handshake/FairPlay.kt`, the legacy RSA path in `app/src/main/kotlin/com/phairplay/airplay/handshake/RaopRsa.kt`, the native bridge in `app/src/main/cpp/fairplay_jni.c` | SEC-14 in the audit |
| …the **binary plist** codec | `app/src/main/kotlin/com/phairplay/airplay/handshake/PlistCodec.kt` | SEC-11 in the audit (the size guard) |

### AirPlay 2 — the three media paths

| I want to change… | Go to | Read first |
|---|---|---|
| …the **classic media pipeline** (the H.264 decoder and the AudioTrack) | `app/src/main/kotlin/com/phairplay/airplay/AirPlayMediaPipeline.kt` | FR-33, NFR-02 |
| …the **AirPlay 2 mirroring session** (the keys, the event channel, the NTP client) | `app/src/main/kotlin/com/phairplay/airplay/AirPlayMirrorSession.kt` | — |
| …the **AirPlay URL session** (the video that is played from a URL) | `app/src/main/kotlin/com/phairplay/airplay/AirPlayUrlSession.kt` | — |
| …the **now-playing state** (the metadata and the audio / video flags) | `app/src/main/kotlin/com/phairplay/airplay/NowPlayingState.kt` | — |
| …the **screen mirroring** (the video path) | `app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorStreamServer.kt` (reassembly) → `app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorCrypto.kt` (AES-128-CTR) → `app/src/main/kotlin/com/phairplay/airplay/VideoDecoder.kt` (MediaCodec) | FR-33, NFR-02 |
| …the **frame queue** or the memory budget of the mirror | `app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorFrameQueue.kt` | SEC-02 in [`SECURITY.md`](../SECURITY.md) |
| …the **RTP interleaving** and the FU-A reassembly | `app/src/main/kotlin/com/phairplay/airplay/RtpInterleaved.kt` | SEC-03 in the audit — the unit is capped at 8 MB |
| …the **H.264 SPS parser** (the resolution of the stream) | `app/src/main/kotlin/com/phairplay/airplay/H264SpsParser.kt` | NFR-12 |
| …the **audio** (AAC-ELD / AAC-LC) | `app/src/main/kotlin/com/phairplay/airplay/handshake/AudioStreamServer.kt` (the UDP/RTP audio path), `app/src/main/kotlin/com/phairplay/airplay/handshake/AudioReorderBuffer.kt` (the order and the resends) and `app/src/main/kotlin/com/phairplay/airplay/AudioPlayer.kt` (the AudioTrack) | FR-12, FR-35 |
| …the **audio decoding thread** (the codec and the track in one owner) | `app/src/main/kotlin/com/phairplay/airplay/handshake/AudioPlaybackEngine.kt` | — |
| …the **RAOP resend request** | `app/src/main/kotlin/com/phairplay/airplay/handshake/RaopControlChannel.kt` | — |
| …the **ALAC** decoding (Apple Lossless) | `app/src/main/kotlin/com/phairplay/airplay/handshake/AlacDecoder.kt`, the native code in `app/src/main/cpp/alac/` and its bridge `app/src/main/cpp/alac_jni.cpp` | — |
| …the **buffered audio** (AirPlay 2 type 103) | `app/src/main/kotlin/com/phairplay/airplay/handshake/BufferedAudioServer.kt` | Accepted, **not played back yet** |
| …the **A/V synchronisation** | `app/src/main/kotlin/com/phairplay/airplay/handshake/AirPlayNtpClient.kt` and `app/src/main/kotlin/com/phairplay/airplay/TimingHandler.kt` | NFR-01, FR-35 |
| …the **reverse remote** (the TV remote controls the sender) | `app/src/main/kotlin/com/phairplay/airplay/DacpClient.kt` | — |
| …the **AirPlay video URL mode** (`POST /play`) | `app/src/main/kotlin/com/phairplay/airplay/RtspVideoUrlHandler.kt` (the endpoints), `app/src/main/kotlin/com/phairplay/airplay/AirPlayUrlSession.kt` (the session) and `app/src/main/kotlin/com/phairplay/airplay/AirPlayVideoPlayer.kt` (the player) | The URL is checked by `app/src/main/kotlin/com/phairplay/util/FetchableUrlPolicy.kt` — SEC-01 in the audit is **closed** |
| …the **stream statistics** (frames, drops, duplicates) | `app/src/main/kotlin/com/phairplay/airplay/StreamStats.kt` | — |

### DLNA / UPnP MediaRenderer

| I want to change… | Go to | Read first |
|---|---|---|
| …the **DLNA entry point** (wiring the renderer into the service) | `app/src/main/kotlin/com/phairplay/dlna/DlnaReceiver.kt` and `app/src/main/kotlin/com/phairplay/dlna/DlnaPlaybackController.kt` | ADR-004 |
| …the **DLNA discovery** (SSDP) | `app/src/main/kotlin/com/phairplay/dlna/SsdpAdvertiser.kt` (the socket), `app/src/main/kotlin/com/phairplay/dlna/SsdpResponder.kt` (which answer belongs to which search target) and `app/src/main/kotlin/com/phairplay/dlna/SsdpMessages.kt` (the message format) | ADR-004, phase D1 |
| …the **DLNA device description** (the XML the controller downloads) | `app/src/main/kotlin/com/phairplay/dlna/DlnaDescriptionServer.kt` and `app/src/main/kotlin/com/phairplay/dlna/DlnaDescriptionBuilder.kt` | ADR-004, phase D1 |
| …the **DLNA SOAP actions** (Play / Pause / Stop / Seek) | `app/src/main/kotlin/com/phairplay/dlna/DlnaSoapDispatcher.kt` (the routing) and `app/src/main/kotlin/com/phairplay/dlna/DlnaAvTransportService.kt` (the action set) | ADR-004, phase D2 |
| …the **DLNA XML hardening** (XXE) | `app/src/main/kotlin/com/phairplay/dlna/SecureSoapXml.kt` | SEC-03 in [`SECURITY.md`](../SECURITY.md) |
| …the **SOAP envelopes** (the success and the fault answers) | `app/src/main/kotlin/com/phairplay/dlna/SoapResponses.kt` and `app/src/main/kotlin/com/phairplay/dlna/SoapResult.kt` | — |
| …the **HTTP answers** of the description server | `app/src/main/kotlin/com/phairplay/dlna/DlnaHttpResponses.kt` | — |
| …the **GENA eventing** (`LastChange` notifications) | `app/src/main/kotlin/com/phairplay/dlna/GenaEventManager.kt`, `app/src/main/kotlin/com/phairplay/dlna/GenaCallbackPolicy.kt`, `app/src/main/kotlin/com/phairplay/dlna/GenaLastChange.kt` | ADR-004, phase D4; SEC-09 in the audit |
| …the **DLNA transport state machine** | `app/src/main/kotlin/com/phairplay/dlna/DlnaTransportState.kt` and `app/src/main/kotlin/com/phairplay/dlna/DlnaRemoteCommand.kt` | — |
| …the **DLNA time format** (`H:MM:SS(.mmm)` in `GetPositionInfo`) | `app/src/main/kotlin/com/phairplay/dlna/DlnaTimeFormat.kt` | — |
| …the **DLNA image decoding** (photos) | `app/src/main/kotlin/com/phairplay/dlna/DlnaImageDecoder.kt` | ADR-004, phase D3 |
| …the **DLNA media URL and metadata** | `app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt`, `app/src/main/kotlin/com/phairplay/dlna/DlnaMediaMetadata.kt`, `app/src/main/kotlin/com/phairplay/dlna/DlnaMediaType.kt` | — |
| …the **address of the media URI** (which host the TV may fetch) | `app/src/main/kotlin/com/phairplay/util/HostAddressPolicy.kt` — it was `app/src/main/kotlin/com/phairplay/dlna/DlnaMediaAddressPolicy.kt` and moved into `util/` because the AirPlay URL mode uses the same rules | SEC-04 in the audit |
| …the **protocol list** (`GetProtocolInfo`) | `app/src/main/kotlin/com/phairplay/dlna/DlnaProtocolInfo.kt` and `app/src/main/kotlin/com/phairplay/dlna/DlnaServiceCatalog.kt` | — |
| …the **DLNA network address** (which IP the TV announces) | `app/src/main/kotlin/com/phairplay/dlna/DlnaNetworkAddress.kt` | — |
| …the **DIAL documents** (Samsung Smart View) | `app/src/main/kotlin/com/phairplay/dlna/DialAppCatalog.kt` | — |
| …the **HTTP request reading** in the DLNA server | `app/src/main/kotlin/com/phairplay/dlna/HttpRequestReader.kt` | SEC-02 in [`SECURITY.md`](../SECURITY.md) |

### Miracast, Cast and the shared helpers

| I want to change… | Go to | Read first |
|---|---|---|
| …the **Miracast** receiver (Wi-Fi Display) | `app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt` | ADR-001; **SEC-06: non-functional on Android 10+** |
| …the **WFD RTSP control server** (port 7236) | `app/src/main/kotlin/com/phairplay/miracast/WfdRtspServer.kt` | ADR-001 |
| …the **Cast** receiver | `app/src/googletv/kotlin/com/phairplay/cast/CastReceiver.kt` and `app/src/googletv/kotlin/com/phairplay/cast/CastReceiverOptionsProvider.kt` (Google TV) · `app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt` (Fire TV) | FR-19…FR-23 |
| …the **Cast app ID** | the Gradle property `phairplay.castAppId` in `app/build.gradle.kts`, and the guide [`guides/CAST_APP_ID.md`](guides/CAST_APP_ID.md) | `scripts/cast-registration-info.sh` prints the values the console asks for |
| …**what this device can decode** (the codec list) | `app/src/main/kotlin/com/phairplay/media/MediaCapabilities.kt` | FR-33 |
| …the **logging** and the **log redaction** | `app/src/main/kotlin/com/phairplay/util/Logger.kt`, `app/src/main/kotlin/com/phairplay/util/LogRedactor.kt`, `app/src/main/kotlin/com/phairplay/util/LogRedactionMode.kt` | SEC-07 in [`SECURITY.md`](../SECURITY.md) |
| …the **address policy** (which host the receiver may fetch) | `app/src/main/kotlin/com/phairplay/util/HostAddressPolicy.kt` | SEC-01 and SEC-04 in the audit |
| …the **fetchable URL policy** (which URL the receiver may fetch) | `app/src/main/kotlin/com/phairplay/util/FetchableUrlPolicy.kt` | SEC-01 in the audit |
| …the **picture decoder** (the pixel budget of a network picture) | `app/src/main/kotlin/com/phairplay/util/SafeBitmapDecoder.kt` | SEC-02 in the audit |
| …the **IP address / MAC / device name** of the TV | `app/src/main/kotlin/com/phairplay/util/NetworkUtils.kt` | SEC-12 in the audit |
| …the **Base64** helper | `app/src/main/kotlin/com/phairplay/util/Base64Util.kt` | — |

### The project around the code

| I want to change… | Go to | Read first |
|---|---|---|
| …the **application start-up** (logging init) | `app/src/main/kotlin/com/phairplay/PhairPlayApp.kt` | **SEC-07 in the audit: a release build currently logs nothing** |
| …the **Activity** (the screens, the lifecycle, the runtime permission) | `app/src/main/kotlin/com/phairplay/MainActivity.kt` | — |
| …the **build configuration**, the flavours, the dependencies | `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` | — |
| …the **JVM test module** (what it compiles, what it excludes) | `test-runner/build.gradle.kts` | [`TESTING.md`](TESTING.md) |
| …the **CI pipeline** | `.github/workflows/ci.yml` and `.github/workflows/lint.yml` | — |
| …the **quality gate** (the four structure checks) | `scripts/check-file-size.sh`, `scripts/check-kdoc-headers.sh`, `scripts/check-docs-links.sh`, `scripts/check-i18n-completeness.sh` | — |
| …the **release process** | `scripts/release.sh` and the release section of [`../CHANGELOG.md`](../CHANGELOG.md) | — |
| …the **device logs** that a user sends you | `tools/collect-device-logs.sh` | — |
| …a **test** | `app/src/test/kotlin/com/phairplay/` (the package of the class under test) · `test-runner/` (the CI gate) | [`TESTING.md`](TESTING.md) |
| …the **documentation** | see the index in [`README.md`](README.md) of `docs/` | [`DOCUMENTATION_POLICY.md`](DOCUMENTATION_POLICY.md) |

---

## The things you must not do

### "I want to add network access."

Stop and read [`SECURITY.md`](../SECURITY.md) first.

PhairPlay talks to the local network and to nothing else. There is no cloud
fallback, no telemetry, no update check. A new outbound connection needs a
Security review, and a new URL that the receiver fetches on behalf of a sender
needs a **scheme allow-list** — `app/src/main/kotlin/com/phairplay/util/FetchableUrlPolicy.kt`
is the rule for the AirPlay URL mode, and
`app/src/main/kotlin/com/phairplay/util/HostAddressPolicy.kt` is the rule for
the address of a fetched URL. SEC-01 and SEC-04 in the audit say exactly why.

### "I want to add a permission."

Every line of `app/src/main/AndroidManifest.xml` must be traceable to a call
site. If you cannot name the call site, the permission does not belong in the
manifest. The permission table in the audit shows the format.

### "I want to add a dependency."

Before you do:

1. explain why the existing code or a platform API cannot solve it;
2. check the licence (Apache 2.0 compatible);
3. check the maintenance and the security posture;
4. check the binary size — this is an APK that runs on a television;
5. record it in `app/build.gradle.kts` and in the version catalogue.

### "I want to make the file bigger."

The soft limit is **400 lines**, the hard limit is **550** (NFR-23 and RULE 1
in [`CONTRIBUTING.md`](CONTRIBUTING.md)). Split the class instead — one
responsibility per class, and each class gets a KDoc header that says what it
does, why it exists and how to use it.

### "I want to change what the product does."

1. Find the requirement in [`spec/REQUIREMENTS.md`](spec/REQUIREMENTS.md).
2. If the behaviour really should change, write a **CR** from
   [`cr/CR_TEMPLATE.md`](cr/CR_TEMPLATE.md).
3. Update the acceptance criteria in
   [`spec/ACCEPTANCE_CRITERIA.md`](spec/ACCEPTANCE_CRITERIA.md).
4. Then implement — with a test.

### "I want to change the architecture."

Read [`ARCHITECTURE.md`](ARCHITECTURE.md) first, then write an **ADR** from
[`adr/ADR_TEMPLATE.md`](adr/ADR_TEMPLATE.md). The existing decisions live in
[`decisions/`](decisions/).

### "I want to change the documentation."

The rules are in [`DOCUMENTATION_POLICY.md`](DOCUMENTATION_POLICY.md). The short
version: the documentation changes **in the same change**, one fact lives in
exactly one place, and a replaced document goes to
[`archive/`](archive/) with the archive banner.

---

## A note on the files that were split

Five files were larger than the hard limit and were split into smaller classes
during the M6 … M12 cycle. **The split is done** — the table above points at the
new files.

| Package | What was split | Where it went |
|---|---|---|
| `com.phairplay.airplay` | the RTSP handler (1024 → 267 lines) | `airplay/RtspServer.kt`, `airplay/RtspRequestRouter.kt`, `airplay/RtspVerbHandler.kt`, `airplay/RtspPairingHandler.kt`, `airplay/RtspMirrorHandler.kt`, `airplay/RtspParameterHandler.kt`, `airplay/RtspDeviceInfoHandler.kt`, `airplay/RtspVideoUrlHandler.kt` — `airplay/RtspHandler.kt` stays the façade |
| `com.phairplay.airplay` | the receiver orchestrator (658 → 374 lines) | `airplay/AirPlayMediaPipeline.kt`, `airplay/AirPlayMirrorSession.kt`, `airplay/AirPlayUrlSession.kt`, `airplay/NowPlayingState.kt` |
| `com.phairplay.airplay` | the mDNS advertising (527 → 338 lines) | `airplay/MdnsServiceRegistrar.kt` |
| `com.phairplay.airplay.handshake` | the audio stream server (503 → 259 lines) | `airplay/handshake/AudioReorderBuffer.kt`, `airplay/handshake/AudioPlaybackEngine.kt`, `airplay/handshake/RaopControlChannel.kt` |
| `com.phairplay.service` | the foreground service (650 → 289 lines) | `service/ServiceStateBus.kt`, `service/ReceiverSupervisor.kt`, `service/ServiceNotificationManager.kt`, `service/NetworkRecoveryMonitor.kt` |
| `com.phairplay` | the Activity (447 → 348 lines) | `ui/FragmentNavigator.kt`, `ui/ServiceConnectionBinder.kt` |
| `com.phairplay.miracast` | the Miracast receiver (439 → 260 lines) | `miracast/WfdRtspServer.kt` |
| `com.phairplay.dlna` | the address policy of the media URI | `util/HostAddressPolicy.kt` — it left the DLNA package because the AirPlay URL mode uses the same rules |

The split was **behaviour preserving**: nothing was added and nothing was
removed. It is not a feature and it does not belong in the changelog as one.
