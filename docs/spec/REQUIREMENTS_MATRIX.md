# PhairPlay – Requirements Baseline Matrix (v4.0)

Version: 4.0
Status: Active
Date: 2026-11-05
Owner: product track (`docs/spec/`)

> **What this document is.** The machine-readable baseline of
> [`REQUIREMENTS.md`](REQUIREMENTS.md). Every requirement of that document
> appears here with the **eight attributes**, without exception:
>
> | # | Attribute | Column |
> |---|---|---|
> | 1 | Unique, stable identifier | `ID` |
> | 2 | Description **and** rationale | `Requirement` + `Why` |
> | 3 | Source / applicable external obligation | `Class` + `Source` |
> | 4 | Measurable acceptance criterion | `Acceptance criterion` |
> | 5 | Responsible component / owner | `Owner` |
> | 6 | Verification method | `Verification` |
> | 7 | Expected deliverable | `Artefact` |
> | 8 | Status with traceability to code, test, evidence | `Status` |
>
> The **long text** in `REQUIREMENTS.md` stays the specification; this matrix is
> the baseline that a tool may read. The matrix is generated from nothing by
> hand, but it is verified by a tool: `scripts/build-traceability.sh` reads the
> identifiers of `REQUIREMENTS.md`, the test names of `app/src/test/kotlin/` and
> writes [`TRACEABILITY.md`](TRACEABILITY.md).

---

## 0. Legend

### 0.1 Class — the *nature* of the obligation

| Code | Meaning | Consequence |
|---|---|---|
| **EXT** | **Binding external obligation** — a law or a platform policy of a store or an operating system. | A violation blocks a distribution channel or breaks a platform guarantee. It is not the project's own opinion. |
| **INT** | **Internal project norm** — a rule PhairPlay imposes on itself and publishes. | Binding inside the project, but not a legal or platform obligation. A deviation is a defect against the team's own promise. |
| **REC** | **Recommendation / voluntary hardening** — a measure the team considers useful. | **Not a legal obligation.** A missing recommendation is an improvement backlog item, not a compliance failure. It must never be presented as a duty towards a third party. |

### 0.2 Sources used in this matrix

| Short name | Source |
|---|---|
| `PROJ-NORM` | Internal project norm — `docs/spec/REQUIREMENTS.md` itself, `docs/CONTRIBUTING.md`, `docs/DOCUMENTATION_POLICY.md` |
| `AUDIT` | `docs/audit/2026-10-24-security-audit.md` (finding SEC-xx) |
| `PLAY-API` | Google Play target API level policy, state 2026-11-02, <https://support.google.com/googleplay/android-developer/answer/11926878> |
| `PLAY-16K` | Android 16 KB page size requirement for Play, state 2026-11-02, <https://developer.android.com/guide/practices/page-sizes> |
| `PROTO` | The protocol specification of the respective sender (AirPlay 2, WFD/RFC 2326, UPnP AV / GENA) |
| `REC` | Recommendation of the audit or of the review track — **voluntary** |

### 0.3 Status

| Code | Meaning |
|---|---|
| ✅ | Implemented; the named automated proof is green. |
| 🟡 | Implemented and unit-tested; the **device** proof is still open (the `DT-xx` procedure). |
| 🟠 | Partially implemented; the open part is named in the status text. |
| 🔴 | Not implemented. |
| ⚪ | Deliberately out of scope or deferred — with the reason in `REQUIREMENTS.md` §3. |

### 0.4 Measuring point

All code statements of this matrix were measured on `main` @ `34a166c` (2026-11-05):

```
$ grep -rn "@Test" app/src/test --include=*.kt | wc -l      → 630 test methods
$ grep -rln "@Test" app/src/test --include=*.kt | wc -l      → 72 test classes
$ find app/src/test -name "*.kt" | wc -l                     → 78 files (6 are helpers)
$ find app/src/main -name "*.kt" | wc -l                     → 131 production files
$ find app/src/main -name "*.kt" | xargs wc -l | sort -rn | head -1
                                                            → 396 ui/DiagnosticsFragment.kt
```

---

## 1. Functional Requirements

### 1.1 Service Discovery & Advertising

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-01 | Advertise an AirPlay 2 receiver via mDNS (`_airplay._tcp`, `_raop._tcp`) | Without an advertisement no sender finds the television | INT `PROJ-NORM` + `PROTO` | Both service types are registered within 2 s of service start; the TXT record carries `deviceid`, `features`, `model` | `airplay/MdnsService.kt`, `MdnsServiceRegistrar.kt` | `MdnsServiceTest` (12 tests), `MdnsServiceNameTest` (9) | `MdnsService.kt`, `MdnsServiceName.kt` | ✅ code + test green; picker visibility DT-03 |
| FR-02 | Advertise a Miracast receiver via Wi-Fi Direct (P2P) service discovery | Windows and Android senders look for `_wfd._tcp` | INT `PROJ-NORM` | `_wfd._tcp` is registered with the five sink TXT records | `miracast/MiracastReceiver.kt` | `MiracastReceiverTest` (5) | `MiracastReceiver.kt` | 🟠 advertisement is implemented; the feature is dead on API 33+ without the runtime permission (SEC-06) |
| FR-03 | Optionally advertise a Google Cast receiver | Cast senders only find a registered receiver | INT `PROJ-NORM` | The Cast SDK is initialised only when an application ID is configured | `cast/CastReceiver.kt` | `CastReceiverTest` (3) | `CastReceiver.kt` | 🟠 needs a registered Cast application ID |
| FR-04 | All four receivers can be enabled/disabled independently | The user must be able to switch off what he does not use | INT `PROJ-NORM` | Each switch changes exactly one receiver state; `anyProtocolEnabled` reflects the combination | `settings/AppSettings.kt`, `service/ReceiverSupervisor.kt` | `AppSettingsTest` — `anyProtocolEnabled is true when only … is enabled` (5 cases) | `AppSettings.kt`, `SettingsRepository.kt` | ✅ code + test green |
| FR-05 | The device name in the sender pickers is configurable | The user names his television | INT `PROJ-NORM` | The name is trimmed, capped at 63 characters and reaches every advertisement | `settings/AppSettings.kt`, `airplay/MdnsService.kt`, `dlna/DlnaDescriptionBuilder.kt` | `AppSettingsTest` — `effectiveDisplayName …` (4 cases) | `AppSettings.effectiveDisplayName` | 🟡 code + test green; picker display DT-03 |
| FR-06 | Every enabled receiver advertises within 2 s of start | A sender that finds nothing does not retry for a long time | INT `PROJ-NORM` | All four cards read *advertising* ≤ 2 s after the service start | `service/ReceiverSupervisor.kt` | — (no automated proof possible) → **DT-02** | `ReceiverSupervisor.kt` | 🟡 device DT-02 open |
| FR-07 | Every receiver stops advertising within 5 s of being disabled | A stale advertisement sends sessions to a dead receiver | INT `PROJ-NORM` | No advertisement and no open port 5 s after the switch-off | `service/ReceiverSupervisor.kt`, `airplay/MdnsService.kt` | — → **DT-02**, `AC-M8.8` | `ReceiverSupervisor.stop` | 🟡 device DT-02 open |

### 1.2 AirPlay 2 Receiver

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-08 | Accept screen-mirroring connections from macOS 12+ and iOS/iPadOS 13+ via RTSP on TCP 7000 | The AirPlay 2 control plane | INT `PROJ-NORM` + `PROTO` | Port 7000 is bound; a full handshake is answered without an error response | `airplay/RtspServer.kt`, `RtspHandler.kt` | `RtspHandlerHandshakeTest` (11), `RtspHandlerSessionTest` (10) → **DT-03** | `RtspServer.kt`, `RtspHandler.kt` | 🟡 code + test green; real sender DT-03 open |
| FR-09 | Complete the RTSP handshake OPTIONS → SETUP → ANNOUNCE → RECORD without errors | A broken handshake is the most visible defect for the user | INT `PROJ-NORM` + `PROTO` | The four methods are routed and answered `200 OK`; the SDP is parsed | `airplay/RtspRequestRouter.kt`, `RtspVerbHandler.kt` | `RtspHandlerHandshakeTest`, `SdpParserTest` (21) → **DT-03** | `RtspRequestRouter.kt` | 🟡 code + test green; DT-03 open |
| FR-10 | Reject a second concurrent AirPlay connection with RTSP 503 | One television can show one stream | INT `PROJ-NORM` | The second client receives `RTSP/1.0 503 Service Unavailable` and the first session survives | `airplay/RtspServer.kt:239` | — (no automated proof) → **DT-03** | `RtspServer.kt` | 🟠 implemented, **no automated test** — backlog B-02 |
| FR-11 | Auto-reconnect and re-advertise after a connection loss | A television that disappears must come back without user action | INT `PROJ-NORM` | Every enabled receiver re-advertises ≤ 5 s after the network returns | `service/PhairPlayService.kt`, `NetworkRecoveryMonitor.kt`, `ReceiverSupervisor.kt` | `DlnaReadvertisePlanTest` (7) → **DT-02** | `NetworkRecoveryMonitor.kt` | 🟡 AirPlay + DLNA implemented; **Miracast and Cast have no re-advertisement path** — backlog B-03 |
| FR-12 | Accept audio-only AirPlay streams and play them through the TV speakers | Music and podcasts are the most frequent use case | INT `PROJ-NORM` + `PROTO` | Audio plays without a video pipeline and without the streaming screen | `airplay/AudioPlayer.kt`, `handshake/AudioStreamServer.kt`, `handshake/AlacDecoder.kt` | `AudioPlayerTest` (9), `AudioStreamServerTest` (3), `AlacDecoderTest` (3) → **DT-04** | `AudioPlayer.kt`, `AlacDecoder.kt` | 🟡 code + test green; DT-04 open |
| FR-42 | Accept AirPlay photo transfers via `/photo`, display JPEG and PNG full-screen, return to the home screen on close | Sharing a picture is a daily action on iOS | INT `PROJ-NORM` + `PROTO` | `PUT /photo` stores the picture, `DELETE /photo` clears it; the image is downsampled before it reaches the screen | `airplay/PhotoHandler.kt`, `ui/PhotoScreen.kt`, `service/PhotoFrame.kt` | `PhotoHandlerTest` (4), `SafeBitmapDecoderTest` (16) → **DT-03** | `PhotoHandler.kt`, `PhotoScreen.kt` | 🟡 code + test green; DT-03 open |

### 1.3 Miracast Receiver

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-13 | Discover and accept incoming Miracast (WFD) connection requests | Windows and Android senders look for a WFD sink | INT `PROJ-NORM` | The WFD RTSP control port 7236 is opened and answers | `miracast/MiracastReceiver.kt`, `WfdRtspServer.kt` | `MiracastReceiverTest` (5) | `MiracastReceiver.kt` | 🟠 control plane only — a sender that needs the hidden sink API cannot connect (FR-58) |
| FR-14 | Negotiate the WFD session (capability exchange, RTSP-WFD handshake) | The control plane is the only part that can exist | INT `PROJ-NORM` + `PROTO` | `OPTIONS`, `GET_PARAMETER`, `SET_PARAMETER`, `SETUP`, `PLAY`, `PAUSE`, `TEARDOWN` are answered; every other method gets `501` | `miracast/WfdRtspServer.kt` | `MiracastReceiverTest` (5) | `WfdRtspServer.kt` | ✅ code + test green (control plane) |
| FR-15 | Decode and display the incoming Miracast video stream | A sink without a picture is not a sink | INT `PROJ-NORM` | — the feature is **excluded**, not delivered | — | — | — | 🔴 **not implemented** — no MPEG-TS demuxer, no WFD decoder; excluded in `REQUIREMENTS.md` §3; **no proof expected** |
| FR-16 | Play audio from the Miracast stream | Same as FR-15 | INT `PROJ-NORM` | — the feature is **excluded**, not delivered | — | — | — | 🔴 **not implemented** — same reason as FR-15; **no proof expected** |
| FR-17 | Support Windows 10+, Android and Samsung Galaxy as Miracast senders | Sender compatibility is the user-visible promise | INT `PROJ-NORM` | — the feature is **excluded**, not delivered | — | — | — | ⚪ **not verifiable** — without a media path there is no end-to-end sender test; **no proof expected** |
| FR-18 | Show the Miracast connection state on the home screen | The user must see whether a sender is connected | INT `PROJ-NORM` | The card shows *advertising / connected / error / not available* | `miracast/MiracastReceiver.kt`, `ui/HomeFragment.kt` | `MiracastReceiverTest` → **DT-06** | `HomeFragment.kt` | 🟡 code + test green; DT-06 open — **the state is wrong while the permission is missing, see FR-59** |

### 1.4 Google Cast Receiver

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-19 | Run as a Cast custom receiver registered with the Cast SDK | Without a registration the Cast SDK refuses to start | EXT `PROTO` (Google Cast SDK terms) + INT | The Cast context is started with a registered application ID | `cast/CastReceiver.kt` | `CastReceiverTest` (3) | `CastReceiver.kt` | 🟠 blocked on a registered application ID |
| FR-20 | Accept Cast connections from Chrome / Android / macOS / iOS | The sender matrix of the Cast SDK | EXT `PROTO` | A sender finds the receiver in the Cast dialog | `cast/CastReceiver.kt` | — → **DT-07** | `CastReceiver.kt` | 🟠 needs a registered application ID |
| FR-21 | Support Cast screen mirroring | Screen mirroring is the reason a user casts | EXT `PROTO` | The mirrored tab appears on the television | `cast/CastReceiver.kt` | — → **DT-07** | `CastReceiver.kt` | 🟠 needs a registered application ID |
| FR-22 | Display the Cast status on the home screen | The card must never lie | INT `PROJ-NORM` | The card shows *disabled / error / connected* with the reason | `ui/HomeFragment.kt` | `CastReceiverTest` → **DT-07** | `HomeFragment.kt` | 🟡 code + test green; DT-07 open |
| FR-23 | Degrade gracefully if the Cast SDK is unavailable (no Play Services on Fire TV) | Fire TV has no Google Play Services | INT `PROJ-NORM` | The `firetv` flavour reports *disabled* and never touches the Cast SDK | `src/firetv/.../cast/CastReceiver.kt` | `CastReceiverTest` (firetv variant) | `CastReceiver.kt` | ✅ code + test green |

### 1.5 Service Control

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-24 | The user can start, stop and restart all receiver services from the UI | The receiver must be controllable without a build tool | INT `PROJ-NORM` | Start sends `ACTION_START`, stop `ACTION_STOP`, restart is stop + start | `service/ServiceController.kt`, `ui/HomeFragment.kt` | `ServiceControllerTest` (4), `ServiceStateTest` (19) | `ServiceController.kt` | ✅ code + test green |
| FR-25 | The receiver runs as a foreground service | The television must keep receiving while the screen is off | EXT (Android platform contract) + INT | The service is started with `startForegroundService` and holds a foreground notification | `service/PhairPlayService.kt`, `ServiceNotificationManager.kt` | `PhairPlayServiceTest` (16) | `PhairPlayService.kt` | ✅ code + test green |
| FR-26 | A persistent notification with the quick actions *Stop* and *Restart* is shown | The user must be able to stop the receiver from the system UI | INT `PROJ-NORM` | The notification shows both actions and they act on the service | `service/ServiceNotificationManager.kt` | — → **DT-14** | `ServiceNotificationManager.kt` | 🟡 code + test green; DT-14 open |
| FR-27 | The service state (running / stopped / error) is visible on the home screen at all times | The state card is the single truth about the receiver | INT `PROJ-NORM` | The state bus drives the card without a restart | `service/ServiceStateBus.kt`, `ui/HomeFragment.kt` | `ServiceStateTest` (19) | `ServiceStateBus.kt` | ✅ code + test green |
| FR-28 | Stopping the service releases all ports, closes all connections and stops advertising | A leaked port blocks the next start and keeps the device visible | INT `PROJ-NORM` | No listener on 7000 / 1900 / the description port after the stop | `service/ReceiverSupervisor.kt`, `dlna/DlnaReceiver.kt` | — → **DT-02**, `AC-M8.8` | `ReceiverSupervisor.stop` | 🟡 device DT-02 open |
| FR-29 | Restarting the service performs a clean stop and a clean start within 3 s | A restart must not need a reboot of the television | INT `PROJ-NORM` | Both ports are bound again ≤ 3 s after the restart | `service/ServiceController.kt` | — → **DT-02** | `ServiceController.kt` | 🟡 device DT-02 open |

### 1.6 Settings

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-30 | A dedicated settings screen is reachable from the home screen | Every setting needs a place | INT `PROJ-NORM` | The settings screen opens and returns to the home screen | `ui/SettingsFragment.kt`, `ui/FragmentNavigator.kt` | `MainActivityTest` (9) → **DT-01** | `SettingsFragment.kt` | 🟡 code + test green; DT-01 open |
| FR-31 | The settings are persisted across app restarts | A setting that is lost on restart is a bug | INT `PROJ-NORM` | A written value is read back after the process is killed | `settings/SettingsRepository.kt` | `AppSettingsTest` (19) → **DT-02** | `SettingsRepository.kt` | 🟡 code + test green; DT-02 open |
| FR-32 | Display name, the four receiver switches, the PIN flag, start-on-boot and the debug overlay are configurable | These are the settings the product promises | INT `PROJ-NORM` | Every listed setting has a default, a store and a UI element | `settings/AppSettings.kt`, `ui/SettingsFragment.kt` | `AppSettingsTest` — `default settings …` (6 cases) | `AppSettings.kt` | ✅ code + test green |

### 1.7 Video / Audio

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-33 | Decode video with the hardware `MediaCodec`; a missing hardware decoder rejects the stream gracefully | Software decoding would not keep up on a TV SoC | INT `PROJ-NORM` | The decoder is created from `MediaCodecList`; no software fallback is used | `airplay/VideoDecoder.kt`, `media/MediaCapabilities.kt` | `VideoDecoderTest` (5), `VideoSubmissionTest` (5), `MediaCapabilitiesTest` (4) → **DT-03** | `VideoDecoder.kt` | 🟡 code + test green; DT-03 open |
| FR-34 | Display the decoded video full-screen, keeping the aspect ratio | A stretched picture is the most complained-about defect | INT `PROJ-NORM` | The picture is fitted without distortion; the aspect-fit arithmetic is unit-tested | `ui/StreamingScreen.kt`, `ui/VideoAspectFit.kt` | `VideoAspectFitTest` (3) → **DT-03** | `VideoAspectFit.kt` | 🟡 code + test green; DT-03 open |
| FR-35 | Play the audio in sync with the video (drift ≤ 40 ms) | Lip-sync is noticed immediately | INT `PROJ-NORM` | The drift budget is 40 ms; the timing arithmetic is unit-tested | `airplay/TimingHandler.kt`, `handshake/AirPlayNtpClient.kt`, `handshake/AudioReorderBuffer.kt` | `TimingHandlerTest` (17) → **DT-04** | `TimingHandler.kt` | 🟠 **not measured on a device** — backlog B-04 |
| FR-36 | For audio-only streams, play through the default output without launching the streaming screen | A music session must not blank the screen | INT `PROJ-NORM` | The streaming screen stays closed; the now-playing screen carries the metadata | `airplay/AirPlayVideoPlayer.kt`, `ui/NowPlayingScreen.kt` | `DmapParserTest` (5) → **DT-04** | `AirPlayVideoPlayer.playAudio` | 🟡 code + test green; DT-04 open |
| FR-37 | Return to the home screen when the stream ends (audio-only stays on the home screen) | The television must not stay on a black screen | INT `PROJ-NORM` | The screen switches back ≤ 2 s after the end of the stream | `service/PhairPlayService.kt`, `ui/FragmentNavigator.kt` | — → **DT-03** | `PhairPlayService.kt` | 🟡 device DT-03 open |
| FR-43 | Display a received JPEG or PNG full-screen via `BitmapFactory`, without a video decoder | The photo path has its own pipeline | INT `PROJ-NORM` | The picture is decoded with a pixel budget; the screen returns on disconnect | `ui/PhotoScreen.kt`, `service/PhotoFrame.kt` | `PhotoHandlerTest` (4), `SafeBitmapDecoderTest` (16) → **DT-03** | `PhotoScreen.kt` | 🟡 code + test green; DT-03 open |

### 1.8 Internationalization

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-38 | Every user-visible string lives in a resource file | A hardcoded string cannot be translated | INT `PROJ-NORM` | 0 lint findings for `HardcodedText`; 0 `setText("` call sites | `app/src/main/res/values*/strings.xml` | `scripts/check-i18n-completeness.sh` + `:app:lintGoogletvDebug` | `strings.xml` | ✅ code + test green |
| FR-39 | The app ships English and German at launch | The project is German-speaking | INT `PROJ-NORM` | The base file has 80 keys; `de` and `fr` carry all of them | `app/src/main/res/values-de/strings.xml` | `scripts/check-i18n-completeness.sh` → `80 key(s) … complete in 2 language(s)` | `values-de/strings.xml` | ✅ code + test green; on-screen DT-12 |
| FR-40 | A new language can be added without changing Kotlin or layout files | A translator must not need the build system | INT `PROJ-NORM` | A new `values-<locale>` directory is sufficient | `app/src/main/res/values-*/strings.xml` | `scripts/check-i18n-completeness.sh` (exit 0 for 2 locales) | the resource directory layout | ✅ code + test green |
| FR-41 | Date, time, number formats and the text direction follow the device locale | A German television needs German numbers | INT `PROJ-NORM` | The locale-aware formatting is unit-tested; the RTL baseline is not implemented | `util/LocaleFormatting.kt` | `LocaleFormattingTest` (15) | `LocaleFormatting.kt` | 🟠 formatting ✅, **RTL baseline open** — backlog B-05 |

### 1.9 UPnP / DLNA MediaRenderer

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-44 | Advertise as a MediaRenderer via SSDP and answer an `M-SEARCH` for `ssdp:all` with exactly three `200 OK` datagrams, every other target with exactly one; every answer carries `LOCATION`, `USN`, `ST`, `EXT`, `SERVER` | The renderer must be findable and must not flood the network | INT `PROJ-NORM` + `PROTO` | `ssdp:all` → 3 answers with the three expected `ST` values; a device-type search → 1 answer | `dlna/SsdpAdvertiser.kt`, `SsdpResponder.kt`, `SsdpMessages.kt` | `SsdpResponderTest` (8), `SsdpAdvertiserLiveTest` (3), `SsdpMessagesTest` (8) | `SsdpAdvertiser.kt` | ✅ code + test green |
| FR-45 | Serve the device description and the three SCPD documents under the advertised `LOCATION` | A control point cannot control a renderer it cannot describe | INT `PROJ-NORM` + `PROTO` | `description.xml` contains the friendly name, the UDN, the device type and the three services; every SCPD lists its actions | `dlna/DlnaDescriptionServer.kt`, `DlnaDescriptionBuilder.kt`, `DlnaServiceCatalog.kt` | `DlnaDescriptionServerTest` (13), `DlnaDescriptionBuilderTest` (6) | `DlnaDescriptionServer.kt` | ✅ code + test green |
| FR-46 | Answer the 14 UPnP control actions; an unknown action → SOAP fault `401`, an invalid argument → `402` | The control plane is the contract with every control point | INT `PROJ-NORM` + `PROTO` | Each of the three services answers its actions; the faults carry the right code | `dlna/DlnaSoapDispatcher.kt`, `DlnaAvTransportService.kt`, `SoapResponses.kt` | `DlnaSoapDispatcherTest` (16) | `DlnaSoapDispatcher.kt` | ✅ code + test green |
| FR-47 | The `GetProtocolInfo` answer carries a non-empty `Source` and `Sink` list | An empty `Source` hides the renderer from the picker | INT `PROJ-NORM` + `PROTO` | `Source` and `Sink` are non-empty | `dlna/DlnaProtocolInfo.kt` | `DlnaSoapDispatcherTest` — `GetProtocolInfo returns a non-empty Source list` (2 cases) | `DlnaProtocolInfo.kt` | ✅ code + test green |
| FR-48 | Support GENA eventing: `SUBSCRIBE` / `UNSUBSCRIBE`, an answer with `SID`, `TIMEOUT`, `EXT`, nested `LastChange` notifications, expiry, and dropping a control point that no longer answers | A control point shows the state from the events | INT `PROJ-NORM` + `PROTO` | The subscription answer carries the three headers; a transport change produces a `LastChange` document | `dlna/GenaEventManager.kt`, `GenaLastChange.kt` | `GenaEventManagerTest` (8), `DlnaSoapDispatcherTest` — `transport state changes are published …` | `GenaEventManager.kt` | ✅ code + test green |
| FR-49 | Refuse a GENA callback that is not a plain `http` URL to a literal private IPv4 in the same /24; reject loopback, link-local, multicast, broadcast, obfuscated notations, host names and every IPv6 form with `412` | Otherwise the television becomes an SSRF proxy out of the network | INT `PROJ-NORM` + `AUDIT` | Every rejected notation is a test case; the answer is `412 Precondition Failed` | `dlna/GenaCallbackPolicy.kt` | `GenaCallbackPolicyTest` (16), `GenaEventManagerTest`, `DlnaDescriptionServerTest` — `a SUBSCRIBE with a forbidden callback is refused` | `GenaCallbackPolicy.kt` | ✅ code + test green |
| FR-50 | Fetch media only over `http` and `https`; every other scheme → SOAP fault `402`, nothing is opened | `file://` would turn the television into a reader of the app sandbox | INT `PROJ-NORM` + `AUDIT` (SEC-04) | `SetAVTransportURI` with `file:///etc/passwd` answers `402`; the scheme allow-list contains only `http` and `https` | `dlna/DlnaMediaUri.kt`, `util/FetchableUrlPolicy.kt` | `DlnaMediaUriTest` — "rejects a file URI because it would read the local file system" | `FetchableUrlPolicy.check` | ✅ **closed on 2026-11-05** (commits `b4e5afa`, `55acde4`) — the own allow-list was removed, the shared `FetchableUrlPolicy` decides |
| FR-51 | Bound every accepted HTTP request: request line ≤ 8 KB, header ≤ 16 KB, body ≤ 64 KB, read timeout 2 s, deadline 10 s, 8 workers, 16 queued, further clients get `503` | A television must survive a hostile client | INT `PROJ-NORM` + `REC` (audit) | An over-sized request is answered `414` / `431` / `413` instead of being buffered | `dlna/HttpRequestReader.kt`, `DlnaDescriptionServer.kt` | `DlnaDescriptionServerTest` — `rejects an over-long request line / header line / body` (3 cases) | `HttpRequestReader.kt` | ✅ code + test green |
| FR-52 | Reject a DOCTYPE and external entities in the control plane, disable entity expansion, bound payload to 64 KB and depth to 32, fail closed | XXE turns the television into a file reader | INT `PROJ-NORM` + `REC` (audit) | An XXE payload is answered `400` and no file content appears in the answer | `dlna/SecureSoapXml.kt` | `SecureSoapXmlTest` (10), `DlnaSoapDispatcherSecurityTest` (6) | `SecureSoapXml.kt` | ✅ code + test green |
| FR-53 | No answer of the control plane contains a stack trace, an exception class name, a file path or a version | An error answer must not describe the internals | INT `PROJ-NORM` + `REC` (audit) | The error answers leak none of the four | `dlna/SoapResponses.kt` | `DlnaDescriptionServerTest` — `error answers leak neither stack traces nor paths nor versions` | `SoapResponses.fault` | ✅ code + test green |
| FR-54 | The DLNA receiver is switchable independently and its state is visible on the home screen | Same promise as the other three receivers | INT `PROJ-NORM` | The switch changes the renderer state; the card follows | `settings/AppSettings.kt`, `ui/SettingsFragment.kt`, `ui/HomeFragment.kt`, `service/ReceiverSupervisor.kt` | `AppSettingsTest` — `anyProtocolEnabled … only DLNA` | `AppSettings.dlnaEnabled` | ✅ code + test green; card display DT-02 |
| FR-55 | On stop: send the SSDP `byebye`, close the description server, drop every GENA subscription, release the player | A stopped renderer must leave no trace in the network | INT `PROJ-NORM` | No listener on the description port; no `200 OK` after the stop | `dlna/DlnaReceiver.kt`, `DlnaDescriptionServer.kt`, `GenaEventManager.kt` | — → **DT-02**, `AC-M12.18` | `DlnaReceiver.stop` | 🟡 device DT-02 open |
| FR-56 | The media keys of the television remote drive the same AVTransport state machine as the control point | Otherwise the control point reads a state that does not exist | INT `PROJ-NORM` | `onRemoteCommand("play-pause")` leads to `PAUSED_PLAYBACK` in `GetTransportInfo` | `dlna/DlnaReceiver.kt`, `DlnaSoapDispatcher.kt`, `MainActivity.kt` | `DlnaSoapDispatcherTest` → **DT-09** | `DlnaSoapDispatcher.onRemoteCommand` | 🟡 code + test green; DT-09 open |
| FR-57 | Document that a UPnP renderer fetches the media itself, that only `http`/`https` URLs play, and that `file:///` points at the sender's disk | A protocol property must not be sold as a defect | INT `PROJ-NORM` | `README.md` and the technical specification state the pull semantics | `docs/`, `README.md` | `grep -n "file:///" README.md` | the documentation | ✅ code + test green |

### 1.10 Miracast — scope and honest limits

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-58 | Use public Android APIs only; never call a hidden API and never require `CONFIGURE_WIFI_DISPLAY` | A sideloaded app can never obtain that permission | INT `PROJ-NORM` | 0 matches for `setWfdInfo` / `CONFIGURE_WIFI_DISPLAY` in `app/src/main` | `miracast/MiracastReceiver.kt` | `AC-M6.1` — `grep -rn "setWfdInfo\|CONFIGURE_WIFI_DISPLAY" app/src/main` → 0 | `MiracastReceiver.kt` | ✅ code + test green |
| FR-59 | Without `WifiP2pManager` or without the runtime permission, report *not available on this device*; never report *advertising* | A card that says *advertising* while nothing is advertised is a lie | INT `PROJ-NORM` | The state becomes `ERROR` and the card never shows *advertising* | `miracast/MiracastReceiver.kt`, `service/ReceiverSupervisor.kt` | `MiracastReceiverTest` — `start emits error when WifiP2pManager is unavailable` | `MiracastReceiver.initializeWifiP2p` | 🟠 **open** — `ReceiverSupervisor.kt:175` sets `ADVERTISING` before the receiver can report the error — backlog B-06 |
| FR-60 | Document that the Miracast implementation covers discovery and control plane only and that no Miracast media is rendered | The documentation must match the code | INT `PROJ-NORM` | 0 matches for `MiracastDecoder` / `MpegTs` / `TsDemux`; the README states the limitation | `miracast/`, `README.md` | `AC-M6.8` — `grep -rn "MiracastDecoder\|MpegTs\|TsDemux" app/src/main` → 0 | the documentation | ✅ code + test green |

### 1.11 Resilience and re-advertisement

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-61 | After the last network interface is lost and returns, every enabled receiver re-advertises within 5 s without an app restart and without dropping the open sockets | A television that sleeps must come back alone | INT `PROJ-NORM` | The AirPlay entry is in the picker ≤ 5 s and the SSDP probe answers three times | `service/PhairPlayService.kt`, `NetworkRecoveryMonitor.kt`, `ReceiverSupervisor.kt`, `airplay/AirPlayReceiver.kt`, `dlna/DlnaReceiver.kt` | `DlnaReadvertisePlanTest` (7) → **DT-02**, `AC-M8.5`, `AC-M8.6` | `NetworkRecoveryMonitor.kt` | 🟡 decision logic unit-tested; the socket behaviour needs a device — backlog B-03 |
| FR-62 | A failed advertisement is retried with a bounded backoff and ends in an explicit error state; the receiver never disappears silently | A silent disappearance is worse than an error | INT `PROJ-NORM` | Retry after 1 s / 3 s / 6 s, then an error state that the card shows | `airplay/MdnsRegistrationTracker.kt`, `MdnsService.kt` | `MdnsRegistrationTrackerTest` (10), `MdnsServiceTest` (12) | `MdnsRegistrationTracker.kt` | ✅ code + test green |

### 1.12 Resource budgets

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-63 | Bound the mirroring frame queue twice: ≤ 90 items (≈ 1.5 s at 60 fps) **and** ≤ 32 MB; drop the oldest frames; never block the reader; exact byte counter | A slow television must not take the whole memory of the device | INT `PROJ-NORM` | 100 frames of 1 MB leave `size ≤ 90` and `byteSize ≤ 32 MB` | `airplay/handshake/MirrorFrameQueue.kt`, `MirrorStreamServer.kt` | `MirrorFrameQueueTest` (13) | `MirrorFrameQueue.kt` | ✅ code + test green |
| FR-64 | Refuse a DLNA photo payload above 20 MB and downsample the decoded bitmap to ≤ 1920 px on the longest edge | A 20 000 × 20 000 picture would allocate 1.6 GB | INT `PROJ-NORM` + `REC` (audit SEC-02) | A payload above the limit is refused; the decoded edge is ≤ 1920 px | `dlna/DlnaImageDecoder.kt`, `dlna/DlnaReceiver.kt` | `DlnaImageDecoderTest` (6) | `DlnaImageDecoder.kt` | ✅ code + test green |

### 1.13 Codec capability advertising

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-65 | Query the decoders and the audio output encodings at runtime and advertise a codec only if the device can decode it | Advertising HEVC on a device without it makes the sender fail | INT `PROJ-NORM` | A codec that `MediaCodecList` does not offer is not advertised | `media/MediaCapabilities.kt` | `MediaCapabilitiesTest` (4) | `MediaCapabilities.kt` | ✅ code + test green |
| FR-66 | The advertised protocol list should be derived from the runtime capability snapshot; as long as it is a fixed constant it must not contain an entry the receiver cannot handle, and the deviation must be documented | The renderer must not promise what it cannot do | INT `PROJ-NORM` | The constant list is documented as a deviation in the technical specification §12 | `dlna/DlnaProtocolInfo.kt` | `DlnaSoapDispatcherTest` — `GetProtocolInfo …` | `DlnaProtocolInfo.kt` | 🟠 **fixed constant** — the deviation is documented; backlog B-07 |

### 1.14 Internationalization — extension

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-67 | The string resources of every shipped locale are a subset of the keys of the base locale | A key that only a translation knows is never shown and hides a renamed key | INT `PROJ-NORM` | 0 keys that exist only in a locale file | `app/src/main/res/values*/strings.xml` | `scripts/check-i18n-completeness.sh` → exit 0 | the resource files | ✅ code + test green |
| FR-68 | A user-visible string that names a **protocol** is identical in every locale; a string that names a **state** exists in every offered locale | A protocol that is called differently in two languages confuses the user | INT `PROJ-NORM` | The four `protocol_*` values are equal in all locales; every `protocol_state_*` key exists in every locale | `app/src/main/res/values*/strings.xml` | `scripts/check-i18n-completeness.sh` → **the value comparison is not implemented** | the resource files | 🟠 **open** — the gate checks the key set and the placeholders, not the values — backlog B-08 |

### 1.15 Optional receivers — graceful degradation

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-69 | On a device without Google Play Services the Cast receiver reports *disabled* and never initialises the SDK; the home screen names Play Services as the reason | Fire TV has no Play Services | INT `PROJ-NORM` | The `firetv` flavour reports `DISABLED`; 0 references to `com.google.android.gms` | `src/firetv/.../cast/CastReceiver.kt` | `CastReceiverTest` (firetv), `AC-M7.1` | `CastReceiver.kt` | ✅ code + test green |
| FR-70 | Without a registered Cast application ID the receiver reports an explicit *not configured* error and points to the registration guide; it never claims to advertise | The card must not promise a receiver that does not exist | INT `PROJ-NORM` | `isConfigured("")` and `isConfigured("TODO_REGISTER_YOUR_CAST_APP_ID")` are `false` | `cast/CastReceiver.kt` | `CastReceiverTest` (3), `AC-M7.5` | `CastReceiver.kt` | ✅ code + test green |

### 1.16 Video aspect ratio robustness

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-71 | If the crop rectangle differs from the coded aspect ratio by more than 5 %, treat the rectangle as a **scaled** picture and use the coded aspect ratio from the SPS | A decoder that scales to the panel and reports the scaled size would stretch the picture | INT `PROJ-NORM` | A scaled crop rectangle yields the coded aspect ratio in the output size | `airplay/OutputSizeResolver.kt`, `airplay/VideoDecoder.kt` | `OutputSizeResolverTest` (12) → **DT-16** | `OutputSizeResolver.kt` | ✅ code + test green; DT-16 open |

### 1.17 Privacy policy and store publication

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-72 | The privacy policy is reachable **inside the app**, not only in a store listing | Google Play reviews the policy against the app | EXT (Google Play policy) + INT | The settings row opens the policy; the URL is a string resource | `ui/SettingsFragment.kt`, `res/values/strings.xml` | `scripts/check-privacy-policy.sh` → exit 0 | the settings row | ✅ code + test green |
| FR-73 | The privacy policy names **every** declared permission and is not older than the manifest | A permission without a mention in the policy is a finding in the review | EXT (Google Play policy) + INT | Every `<uses-permission>` appears in the policy; the date is not older than the manifest | `PRIVACY.md`, `app/src/main/AndroidManifest.xml` | `scripts/check-privacy-policy.sh` + `scripts/test-check-privacy-policy.sh` (6 cases) | `PRIVACY.md` | ✅ code + test green |

### 1.18 New in v4.0

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| FR-74 | The binary property list decoder refuses a payload above 1 MB itself, independently of the caller | Today the 1 MB budget of the RTSP reader is the only guard; a future call site without that budget has no second line of defence | **REC** — voluntary hardening from `AUDIT` (SEC-11); **not** a legal obligation | `PlistCodec.decode` throws for a payload > 1 MB; the RTSP path still accepts a body at the limit | `airplay/handshake/PlistCodec.kt` | `PlistCodecTest` — a new case `decode refuses a payload above one megabyte` | `PlistCodec.MAX_PLIST_BYTES` | 🔴 **open** — no size guard exists today — backlog B-09 |

---

## 2. Non-Functional Requirements

### 2.1 Performance

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| NFR-01 | Video latency ≤ 100 ms on local 5 GHz Wi-Fi | A latency above 100 ms feels like a remote control | INT `PROJ-NORM` | The measured delay between the input on the sender and the picture on the television ≤ 100 ms | `airplay/handshake/AirPlayNtpClient.kt` | — → **DT-03**, `AC-M8.1` | the timing path | 🟠 **not measured** — backlog B-04 |
| NFR-02 | Frame rate ≥ 25 fps sustained | Below 25 fps the picture stutters | INT `PROJ-NORM` | ≥ 25 rendered frames per second over 5 minutes | `airplay/VideoDecoder.kt` | — → **DT-15** | the decode path | 🟠 **not measured** — backlog B-04 |
| NFR-03 | Peak RAM ≤ 150 MB for a single active stream | The television shares its memory with everything else | INT `PROJ-NORM` | `MAX_RESIDENT_BYTES` = 150 MB; the app measures its own resident memory and shows it against the budget | `diagnostics/PerformanceBudgets.kt`, `ProcessMemoryProbe.kt`, `ResourceAssessment.kt` | `ResourceAssessmentTest` (14), `ResourceProbeTest` (15) → **DT-15** | `PerformanceBudgets.kt` | 🟡 the budget and the measurement ✅; the **device** number is open — backlog B-04 |
| NFR-04 | Average CPU ≤ 30 % on a mid-range TV SoC | A CPU load above 30 % makes the menu sluggish | INT `PROJ-NORM` | `MAX_CPU_PERCENT` = 30; the app measures its own load and shows it against the budget | `diagnostics/CpuUsage.kt`, `PerformanceBudgets.kt` | `ResourceAssessmentTest` (14) → **DT-15** | `PerformanceBudgets.kt` | 🟡 the budget and the measurement ✅; the **device** number is open — backlog B-04 |

### 2.2 UI / UX

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| NFR-05 | The UI follows the Google TV Streamer design language: dark background, cards, large focus indicators, D-pad navigable | The television must feel like part of the system | INT `PROJ-NORM` | The layout uses the dark theme, card rows and visible focus states | `ui/HomeFragment.kt`, `WaitingScreen.kt`, `StreamingScreen.kt` | — → **DT-01** | the layout files | 🟡 device DT-01 open |
| NFR-06 | Every interactive element is reachable with the D-pad / the remote | A television has no pointer | INT `PROJ-NORM` | Every focusable view is reachable in a defined order | `res/layout/*.xml` | — → **DT-01**, `DT-03** | the layout files | 🟡 device DT-01 open |
| NFR-07 | Minimum touch and focus target size 48 dp | A small focus target is unusable on a TV | INT `PROJ-NORM` | Every focusable view has ≥ 48 dp in both dimensions | `res/layout/*.xml` | — → **DT-01** | the layout files | 🟡 device DT-01 open |
| NFR-08 | Text minimum size 18 sp for body, 32 sp for titles | A television is viewed from a distance | INT `PROJ-NORM` | Every `textSize` is ≥ 18 sp, every title ≥ 32 sp | `res/layout/*.xml`, `res/values/dimens.xml` | — → **DT-01** | the dimension files | 🟡 device DT-01 open |
| NFR-09 | Sufficient contrast ratio for a TV viewed from 3 metres | Low contrast is unreadable in a living room | INT `PROJ-NORM` | The body text reaches WCAG AA (4.5:1) against its background | `res/values/colors.xml` | — (no contrast check exists) → **DT-01** | the colour resources | 🟠 **no automated proof** — backlog B-10 |
| NFR-10 | The home screen shows the active protocol, the sender name and the duration within 1 s of the change | The user must see what is running | INT `PROJ-NORM` | The status card updates ≤ 1 s after the state change | `ui/HomeFragment.kt`, `airplay/StreamStats.kt` | `StreamStatsTest` (3) → **DT-03** | `HomeFragment.kt` | 🟡 code + test green; DT-03 open |

### 2.3 Privacy & Security

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| NFR-11 | No advertising, no analytics, no telemetry | The product promise and the reason for the project | INT `PROJ-NORM` | 0 analytics / crash-reporting SDKs in the dependency list and in the manifest | `app/build.gradle.kts`, `gradle/libs.versions.toml` | `grep -rni "firebase\|analytics\|crashlytics\|amplitude\|mixpanel\|sentry\|datadog" app/build.gradle.kts gradle/libs.versions.toml` → 0 | the dependency list | ✅ code + test green |
| NFR-12 | Every network input is validated before processing | The attacker is a device in the same network | INT `PROJ-NORM` + `REC` (audit) | Each parser has a size / length / character guard with a negative test | `airplay/RtspRequestReader.kt`, `dlna/SecureSoapXml.kt`, `util/HostAddressPolicy.kt`, `util/FetchableUrlPolicy.kt` | `RtspHandlerHandshakeTest`, `SecureSoapXmlTest` (10), `HostAddressPolicyTest` (11), `FetchableUrlPolicyTest` (16) | the parsers | 🟠 generally ✅, but the **plist decoder has no own guard** (FR-74) — backlog B-09 |
| NFR-13 | No hardcoded secrets | A secret in the repository is a secret in the world | INT `PROJ-NORM` | The Cast application ID defaults to the empty string; no key material in the source | `app/build.gradle.kts` | `grep -n "castAppId" app/build.gradle.kts` → default `""` | the build configuration | ✅ code + test green |
| NFR-14 | Minimal Android permissions | Every permission is a request to the user | INT `PROJ-NORM` + EXT (Google Play data safety) | Every `<uses-permission>` has a traced use in `PRIVACY.md` | `app/src/main/AndroidManifest.xml` | `scripts/check-privacy-policy.sh` → exit 0 | the manifest | 🟠 the Miracast permission decision is open (SEC-06) — backlog B-11 |
| NFR-26 | Every outbound connection targets an address in the local network; no hardcoded internet endpoint in the code | The receiver must not become a proxy out of the household | INT `PROJ-NORM` | 0 hardcoded public endpoints in the production sources | `util/HostAddressPolicy.kt`, `util/FetchableUrlPolicy.kt` | `HostAddressPolicyTest` (11), `FetchableUrlPolicyTest` (16) | the address policy | 🟠 the loopback, link-local and obfuscated notations are refused, but a **public** address is still accepted — an owner decision, backlog B-12 |
| NFR-27 | Every server socket bounds the size and the duration of the requests it accepts and answers an over-sized request with a status code | A television must survive a hostile client | INT `PROJ-NORM` + `REC` (audit) | The RTSP, the WFD and the DLNA readers each carry their own budget | `airplay/RtspRequestReader.kt`, `dlna/HttpRequestReader.kt`, `airplay/handshake/MirrorFrameQueue.kt` | `RtspHandlerHandshakeTest` — `testOversizedMessageRejected`, `DlnaDescriptionServerTest` (3 cases), `MirrorFrameQueueTest` (13) | the readers | 🟠 the socket budgets ✅; the **plist decoder** has none — backlog B-09 |
| NFR-28 | If a protocol needs cleartext HTTP, the app declares a network security configuration that permits cleartext only for private LAN ranges and never globally | A global cleartext permission would break the promise of NFR-26 | INT `PROJ-NORM` + `REC` (audit SEC-10) | `res/xml/network_security_config.xml` exists and lists no public domain | `app/src/main/res/xml/` | — the file does not exist yet; the device procedure **DT-17** proves it on a television | `network_security_config.xml` | 🔴 **open** — the file is not in the repository, so the GENA `NOTIFY` and the DACP command cannot work — backlog B-13 |
| NFR-29 | Every log line is reduced before it is written: host part of an IP, device half of a MAC, complete UUIDs, URL credentials and query string; the reduction is active in **every** build | A bug report with a log excerpt ends up in a public issue tracker | INT `PROJ-NORM` + `REC` (audit) | The reduction runs on every level; no call site bypasses it | `util/Logger.kt`, `LogRedactor.kt`, `LogRedactionMode.kt` | `LoggerTest` (8), `LogRedactorTest` (19) | `Logger.kt` | 🟠 **12 direct `Timber.` call sites in 5 files bypass the reduction** (`MainActivity.kt`, `PhairPlayApp.kt`, `BootReceiver.kt`, `ServiceConnectionBinder.kt`, `LogRedactor.kt`) — backlog B-14 |
| NFR-30 | The app declares only the permissions it uses, each one is justified in `PRIVACY.md`, and a runtime permission is requested only when the user switches the feature on and only after a rationale | A permission asked at the wrong moment is a permission the user denies | INT `PROJ-NORM` + EXT (Google Play data safety) | `grep -rn "requestPermissions" app/src/main` lists only the notification request; every permission is in `PRIVACY.md` | `app/src/main/AndroidManifest.xml`, `MainActivity.kt` | `scripts/check-privacy-policy.sh`, `AC-M6.7` | the manifest | 🟠 the request discipline ✅; the **Miracast permission** is still undecided — backlog B-11 |
| NFR-31 | A release build writes warnings and errors to the log after the reduction; a release build that writes nothing makes a field failure undiagnosable | A crash in the field must be diagnosable from the user's device | INT `PROJ-NORM` + `REC` (audit SEC-07) | A release tree writes `W` and `E`, drops `D`; a new test class `ReleaseLogTreeTest` asserts both | `PhairPlayApp.kt`, `util/Logger.kt` | — planned: `ReleaseLogTreeTest` (does not exist yet) and the device procedure **DT-18** | `PhairPlayApp.initLogging` | 🔴 **open** — `PhairPlayApp.kt:40` plants a tree only under `BuildConfig.DEBUG` — backlog B-15 |

### 2.4 Compatibility

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| NFR-15 | Google TV: Android 10+ (API 29+), ARMv8 | The primary target platform | INT `PROJ-NORM` | The `googletv` flavour has `minSdk 29` and builds | `app/build.gradle.kts` | `grep -n "minSdk = 29" app/build.gradle.kts` → **DT-01** | the flavour configuration | ✅ build configuration; device DT-01 open |
| NFR-16 | Fire TV: Android 7.1+ (API 25+), ARMv7 / ARMv8 | The second target platform | INT `PROJ-NORM` | The `firetv` flavour has `minSdk 25` and builds without an `NewApi` finding | `app/build.gradle.kts` | `grep -n "minSdk = 25" app/build.gradle.kts`, `:app:lintFiretvDebug` → **DT-01** | the flavour configuration | ✅ build configuration; device DT-01 open |
| NFR-17 | AirPlay sender (screen mirroring): macOS 12+, iOS/iPadOS 13+ | The sender matrix of the AirPlay protocol | INT `PROJ-NORM` + `PROTO` | A sender of every listed version completes the handshake | `airplay/` | — → **DT-03** | the AirPlay stack | 🟡 device DT-03 open |
| NFR-18 | AirPlay sender (audio-only): macOS 12+, iOS/iPadOS 13+ | The same matrix for the audio path | INT `PROJ-NORM` + `PROTO` | A sender of every listed version plays audio | `airplay/` | — → **DT-04** | the audio path | 🟡 device DT-04 open |
| NFR-19 | Miracast sender: Windows 10+, Android 4.2+ | The sender matrix of the WFD control plane | INT `PROJ-NORM` + `PROTO` | — the feature is **excluded**, not delivered | `miracast/` | — | — | ⚪ **not verifiable** — no media path exists (FR-15, FR-16); **no proof expected** |
| NFR-20 | Cast sender: Chrome 72+, Android 5+ | The sender matrix of the Cast SDK | EXT (Google Cast SDK) | A sender finds the receiver in the Cast dialog | `cast/` | — → **DT-07** | the Cast integration | 🟠 needs a registered application ID |

### 2.5 Code Quality

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| NFR-23 | No source file above 400 lines (soft); none above 550 lines (hard) | A big file hides more than one responsibility | INT `PROJ-NORM` | The gate reports 0 files above the hard limit and 0 warnings above the soft limit | `scripts/check-file-size.sh` | `scripts/check-file-size.sh` → `189 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines` | the gate script | ✅ code + test green — the largest Kotlin file is `ui/DiagnosticsFragment.kt` with 396 lines |
| NFR-24 | Every public method has at least one unit test | An untested public method is an unverified behaviour | INT `PROJ-NORM` | Every requirement of this matrix names either an automated proof or a device procedure; the traceability matrix has no row without evidence | `app/src/test/` | `scripts/build-traceability.sh` → `TRACEABILITY.md`, 0 rows without evidence | the traceability matrix | 🟠 partially — the device-only surfaces have no unit test by nature — backlog B-16 |
| NFR-25 | Every class has a KDoc header | The header is the cheapest place to say why a class exists | INT `PROJ-NORM` | The gate finds 0 class declarations without a KDoc header | `scripts/check-kdoc-headers.sh` | `scripts/check-kdoc-headers.sh` → `233 class declaration(s) checked, all of them have a KDoc header` | the gate script | ✅ code + test green |

### 2.6 New in v4.0

| ID | Requirement | Why | Class + Source | Acceptance criterion | Owner | Verification | Artefact | Status |
|---|---|---|---|---|---|---|---|---|
| NFR-21 | The published app targets the API level that the Google Play policy requires at the time of the submission | Play rejects an upload whose target API level is below the current minimum | **EXT** — binding platform obligation; Google Play target API policy, state 2026-11-02, <https://support.google.com/googleplay/android-developer/answer/11926878> | `targetSdk` ≥ the minimum the Play console demands on the day of the upload; the value is written down in the release notes | `app/build.gradle.kts`, `docs/project/RELEASE_READINESS.md` | `grep -n "targetSdk" app/build.gradle.kts` → 35 today; re-check at every submission | the build configuration, the release notes | 🟡 `targetSdk = 35` today; **must be re-checked at every submission** — the obligation changes with the calendar |
| NFR-22 | The native libraries of the app are compatible with a 16 KB page size, because the app ships `libplayfair.so` and `libalac.so` | A device with 16 KB pages cannot load a library that was linked for 4 KB pages; Play requires the support for apps targeting Android 15+ | **EXT** — binding platform obligation; Android 16 KB page size requirement, state 2026-11-02, <https://developer.android.com/guide/practices/page-sizes> | The libraries are built with a NDK that supports 16 KB alignment (`ndkVersion = 28.2.13676358`); the alignment of the shipped `.so` is verified with `zipalign -c -P 16` | `app/build.gradle.kts`, `app/src/main/cpp/CMakeLists.txt` | `zipalign -c -P 16 -v 4 app/build/outputs/apk/googletv/release/app-googletv-release.apk` → every `.so` aligned | the native libraries, the APK | 🟠 **not yet proven** — the NDK version supports it, but the alignment of the shipped libraries is not verified — backlog B-17 |
| NFR-32 | Every dependency version is pinned; no dynamic version; the license of every dependency is compatible with the Apache 2.0 distribution, and the license inventory is part of the release | An unpinned version makes a release unreproducible; an incompatible license blocks the distribution | INT `PROJ-NORM` (the licence obligation itself is EXT, Apache 2.0 §4) | `gradle/libs.versions.toml` contains no `+`, no `latest.release` and no snapshot; the licence list is in `NOTICE` / the documentation | `gradle/libs.versions.toml`, `NOTICE` | `grep -nE '\+|latest|snapshot' gradle/libs.versions.toml` → 0 | the version catalogue, the licence list | 🟠 the versions are pinned ✅; **no licence inventory exists** — backlog B-18 |
| NFR-33 | The release is signed with the owner's production key; an alpha or beta key is never reused for a stable release; the signature is verified before the publication | A release signed with a throw-away key cannot be updated later | INT `PROJ-NORM` | `apksigner verify` reports *Verified* for both APKs and the `.aab`; the key fingerprint is recorded in the release notes | `scripts/release.sh`, `docs/project/RELEASE_READINESS.md` | `apksigner verify --print-certs app-googletv-release.apk` → `Verified` | the signed artefacts, the release notes | 🟡 the script exists (`scripts/release.sh`); **no signed release has been verified in this environment** — backlog B-19 |
| NFR-34 | Every requirement of this baseline has a traceable acceptance criterion and a traceable piece of evidence; the traceability matrix is generated, not written by hand | A requirement that cannot be traced is a wish | INT `PROJ-NORM` | `scripts/build-traceability.sh` writes `docs/spec/TRACEABILITY.md` with 0 rows without evidence; the self-test proves that a requirement without a test is reported | `scripts/build-traceability.sh`, `docs/spec/TRACEABILITY.md` | `scripts/test-build-traceability.sh` → red case and green case | the generated matrix, the self-test | ✅ the script and the self-test exist (this cycle) |

---

## 3. Summary of the open points

The requirements that are **not** met today. Every one of them has an entry in
[`REMEDIATION_BACKLOG.md`](REMEDIATION_BACKLOG.md).

| ID | Short | Severity | Backlog |
|---|---|---|---|
| FR-50 | The media URI scheme allow-list — **closed on 2026-11-05** by another track | — | B-01 (erledigt) |
| FR-59 | The Miracast card shows *advertising* before the receiver can report the error | Medium | B-06 |
| FR-74 | The plist decoder has no own size guard | Low | B-09 |
| NFR-11 … NFR-14 | No open point | — | — |
| NFR-26 | A public address is still accepted as a media address | Medium | B-12 |
| NFR-28 | The network security configuration does not exist | Medium | B-13 |
| NFR-29 | 12 log call sites bypass the central reduction | Low | B-14 |
| NFR-31 | A release build logs nothing | Medium | B-15 |
| NFR-68 | The i18n gate does not compare the values of the protocol strings | Low | B-08 |
| FR-10 | The RTSP 503 for a second connection has no automated test | Low | B-02 |
| FR-11, FR-61 | Miracast and Cast have no re-advertisement path | Medium | B-03 |
| NFR-01 … NFR-04, NFR-35 | The performance numbers are not measured on a device | Medium | B-04 |
| NFR-41 | The RTL baseline is not implemented | Low | B-05 |
| NFR-66 | The protocol list is a fixed constant | Low | B-07 |
| NFR-14, NFR-30 | The Miracast permission decision is open | Medium | B-11 |
| NFR-09 | No contrast proof | Low | B-10 |
| NFR-14 … NFR-20 | The device validation of the compatibility rows is open | Medium | B-16 |
| NFR-21 | The target API level must be re-checked at every submission | Low | B-20 |
| NFR-22 | The 16 KB page alignment of the native libraries is not proven | Medium | B-17 |
| NFR-32 | No licence inventory | Medium | B-18 |
| NFR-33 | No signed release has been verified in this environment | Medium | B-19 |
