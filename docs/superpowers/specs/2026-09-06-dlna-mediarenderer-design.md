# DLNA MediaRenderer — Design

**Date:** 2026-09-06
**Status:** Approved (Jeremy, 2026-09-06)
**Sub-project:** 3 of 3 (AirPlay audio-only fix → Miracast removal → **DLNA MediaRenderer**)

## Problem

Miracast was removed (ADR-004) because a sideloaded Android app cannot be discovered as a Wi-Fi Display
sink. Windows senders therefore have no way to put media on the TV. Windows exposes UPnP/DLNA Digital
Media Renderers as "Cast to Device", and common Android/desktop control points (BubbleUPnP, VLC) speak
the same protocol. PhairPlay needs a DLNA MediaRenderer so a Windows PC can cast video, music and photos
to the TV, and so third-party control points can drive it.

## Decisions already made (not re-opened here)

- Hand-rolled UPnP: SSDP discovery, device/service description XML, SOAP control for AVTransport,
  RenderingControl and ConnectionManager, GENA eventing. No jUPnP/Cling (large dependency, awkward
  Jetty transport on Android, hides the wire details Windows is picky about). Recorded as ADR-005.
- Target senders: Windows "Cast to Device" (primary), BubbleUPnP and VLC (secondary).
- Playback through Android `MediaPlayer` first. Media3 is a new dependency and is considered only if
  MediaPlayer fails on real files; that is a separate ask-first decision.
- Media scope: video, audio **and** photos (everything Windows offers).
- A new `DlnaPlayer` wrapper rather than generalising `AirPlayVideoPlayer`. AirPlay code is not touched
  beyond the overlay reorder below. If Cast playback is ever built, a shared player can be extracted then.

## Current state (what the code offers to build on)

- No shared player exists. AirPlay owns three engines: `VideoDecoder` (MediaCodec H.264 mirroring),
  `AudioPlayer` (AudioTrack), `AirPlayVideoPlayer` (MediaPlayer for `/play` URLs, 117 lines, no tests).
  Miracast never had a player; Cast only starts/stops the Cast Connect SDK context.
- `PhairPlayService` hosts receivers, exposes `airPlayState`, `castState`, `nowPlaying`, `photoFrame`,
  `pairingPin` flows and a single video-Surface provider that is handed to `AirPlayReceiver`.
- `MainActivity.updateOverlay()` picks the full-screen view: PIN → now-playing card → AirPlay CONNECTED
  streaming surface → photo → hidden.
- `HomeFragment` shows two protocol cards; ADR-004 reserved the third slot for DLNA.
- `NetworkUtils.getPersistentUuid()` gives a stable per-install UUID (used as AirPlay `pi`).
- Manifest already holds `INTERNET`, `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_WIFI_STATE`,
  `ACCESS_NETWORK_STATE`. No new permissions are needed.

## Architecture

New package `com.phairplay.dlna`, layered like `com.phairplay.airplay`:

```
PhairPlayService
  └── DlnaReceiver                      start/stop, wires everything, emits ProtocolState
        ├── SsdpAdvertiser              UDP 239.255.255.250:1900  (discovery)
        ├── DlnaHttpServer              TCP, fixed port with fallback  (description, control, eventing)
        │     ├── DeviceDescription     root device XML
        │     ├── scpd/*                three service description XML documents
        │     ├── SoapDispatcher        SOAP envelope in → typed action → SOAP response or fault
        │     │     ├── AvTransportService
        │     │     ├── RenderingControlService
        │     │     └── ConnectionManagerService
        │     └── EventSubscriptions    GENA SUBSCRIBE/UNSUBSCRIBE, NOTIFY with LastChange
        └── MediaRenderer               the one stateful object: URI → player or photo, UI callbacks
              ├── DlnaPlayer            MediaPlayer wrapper (video + audio)
              └── PhotoFetcher          HTTP GET of an image, size-capped
```

Boundaries:

- The three UPnP service classes, `SoapDispatcher`, `SsdpMessages`, `DeviceDescription`, `DidlLite`,
  `ProtocolInfoList`, `EventSubscriptions` and `LastChangeEncoder` contain no Android imports and are
  JVM-testable in `test-runner`.
- The service classes act on a `RendererControl` interface implemented by `MediaRenderer`. Tests use a
  fake. The interface exposes: load(uri, metadata, senderAgent), setNext(uri, metadata), play, pause,
  stop, seekTo(ms), next, snapshot() (transport state, status, URIs, duration, position, volume, mute),
  setVolume, setMute.
- `MediaRenderer` is the only class with mutable session state, guarded by one lock. It is the only
  class that knows about `DlnaPlayer`, `PhotoFetcher` and the service callbacks.
- Every file stays under the 400-line rule (CONTRIBUTING RULE 1). Expected sizes: `MediaRenderer` and
  `AvTransportService` ~300, `EventSubscriptions` and `SsdpAdvertiser` ~250, the rest smaller.

## Components

### SsdpAdvertiser / SsdpMessages
- Acquires a `WifiManager.MulticastLock` for the lifetime of the receiver (multicast is otherwise
  filtered on Android Wi-Fi; harmless on Ethernet).
- `MulticastSocket` bound to 1900, joined to 239.255.255.250 on the active interface.
- On start, and every 5 minutes, sends `NOTIFY ssdp:alive` for: `upnp:rootdevice`, the UDN, the device
  type `urn:schemas-upnp-org:device:MediaRenderer:1`, and the three service types. Each carries
  `SERVER`, `CACHE-CONTROL: max-age=1800`, `USN`, `LOCATION`, `NT`, `NTS`.
- Answers `M-SEARCH` whose `ST` is `ssdp:all`, `upnp:rootdevice`, the UDN, the device type or one of
  the service types, unicast to the requester after a random delay of 0..min(MX, 3) seconds.
- On stop sends `ssdp:byebye` for the same set.
- `SsdpMessages` is a pure object: builds NOTIFY / M-SEARCH-response / byebye text, parses an incoming
  datagram into method + headers, and decides whether an `ST` matches. All testable.

### DlnaHttpServer / HttpRequestReader
- Raw `ServerSocket`; accept loop on `Dispatchers.IO`; one coroutine per connection; HTTP/1.1 with
  `Connection: close` (Windows and BubbleUPnP are fine with this; it avoids keep-alive state).
- `HttpRequestReader`: request line, headers, `Content-Length` body capped at 64 KB (returns 413 above).
  Separate from the AirPlay `RtspRequestReader` because SOAP bodies, GENA verbs and limits differ; the
  overlap is ~60 lines.
- Binds `DEFAULT_HTTP_PORT` (a named constant, 49494) and falls back to an OS-assigned port if that
  fails. The SSDP `LOCATION` and the description's base URL always use the port actually bound.
- Routes:
  - `GET /description.xml` → `DeviceDescription`
  - `GET /scpd/AVTransport.xml`, `/scpd/RenderingControl.xml`, `/scpd/ConnectionManager.xml`
  - `POST /control/{AVTransport|RenderingControl|ConnectionManager}` → `SoapDispatcher`
  - `SUBSCRIBE` / `UNSUBSCRIBE /event/{service}` → `EventSubscriptions`
  - `GET /icon.png` → the app launcher icon rendered to a 120×120 PNG once at start.
- Anything else → 404; wrong method on a known path → 405.

### DeviceDescription
Root device XML with: `specVersion` 1.0, `deviceType` MediaRenderer:1, `friendlyName` =
`AppSettings.effectiveDisplayName` (falls back to the system device name exactly as mDNS does),
`manufacturer` "PhairPlay", `modelName` "PhairPlay", `modelNumber` = version name, `UDN` =
`uuid:` + `NetworkUtils.getPersistentUuid()`, `dlna:X_DLNADOC` = `DMR-1.50` (namespace
`urn:schemas-dlna-org:device-1-0`), an `iconList` with the PNG, and a `serviceList` whose serviceIds are
exactly `urn:upnp-org:serviceId:AVTransport`, `urn:upnp-org:serviceId:RenderingControl`,
`urn:upnp-org:serviceId:ConnectionManager` with matching SCPD, control and event URLs.

### scpd/
Three Kotlin files, one raw-string constant each (`AvTransportScpd`, `RenderingControlScpd`,
`ConnectionManagerScpd`), trimmed to the actions and state variables implemented below. Kept as Kotlin
constants (not assets) so the JVM tests can validate them.

### SoapDispatcher
- Action name from the `SOAPACTION` header (`"urn:schemas-upnp-org:service:AVTransport:1#Play"`).
- Arguments from the body via `javax.xml.parsers.DocumentBuilderFactory` with DTDs and external
  entities disabled (XXE hardening).
- Dispatches to the service by name; serialises `<u:{Action}Response>` with escaped values, or a SOAP
  fault carrying `<UPnPError><errorCode>` and `<errorDescription>`.
- Fault codes: 401 Invalid Action, 402 Invalid Args, 501 Action Failed, 701 Transition not available,
  714 Illegal MIME type, 716 Resource not found, 718 Invalid InstanceID (anything but `0`).

### AvTransportService
Actions: `SetAVTransportURI`, `SetNextAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek` (units
`REL_TIME` as `H:MM:SS[.fff]`, and `TRACK_NR` = 1), `Next`, `Previous` (restarts the current item),
`GetMediaInfo`, `GetTransportInfo`, `GetPositionInfo`, `GetDeviceCapabilities`,
`GetTransportSettings`, `GetCurrentTransportActions`.

Transport states: `NO_MEDIA_PRESENT`, `STOPPED`, `TRANSITIONING`, `PLAYING`, `PAUSED_PLAYBACK`.
Transport status: `OK` or `ERROR_OCCURRED`. Play speed is always `1`. `NumberOfTracks` is 0 or 1.
Positions and durations are formatted `H:MM:SS`; unknown duration is `0:00:00`.

Rules: `Play`/`Pause`/`Seek` with no media → 701. `Pause` while stopped → 701. `SetAVTransportURI`
returns immediately (state becomes `TRANSITIONING`); `PLAYING` is reported when the player is prepared.
`SetNextAVTransportURI` is stored and started automatically on completion; `Next` starts it at once.

### RenderingControlService
`GetVolume`/`SetVolume` (0..100, channel `Master`, other channels → 402), `GetMute`/`SetMute`,
`ListPresets` → `FactoryDefaults`, `SelectPreset` → volume 100, unmuted. Volume is applied to the DLNA
stream through `MediaPlayer.setVolume` with a perceptual curve, **not** to the TV's system volume, so
casting never changes what the TV remote controls.

### ConnectionManagerService / ProtocolInfoList
`GetProtocolInfo` returns an empty Source and the sink list below; `GetCurrentConnectionIDs` → `0`;
`GetCurrentConnectionInfo` → the single connection, direction Input, status OK.
`ProtocolInfoList` also classifies an item as VIDEO, AUDIO or IMAGE from (in order) the DIDL `res`
protocolInfo MIME, the DIDL `upnp:class`, then the URI extension. Unknown → 714.

Sink list (only what `MediaPlayer` decodes; raw LPCM and WMA/WMV are deliberately absent):
- video: `video/mp4`, `video/x-matroska`, `video/webm`, `video/3gpp`, `video/mpeg`, `video/vnd.dlna.mpeg-tts`
- audio: `audio/mpeg`, `audio/mp4`, `audio/x-m4a`, `audio/aac`, `audio/flac`, `audio/x-wav`, `audio/wav`, `audio/ogg`
- image: `image/jpeg`, `image/png`, `image/gif`, `image/webp`
Each as `http-get:*:{mime}:*`, plus DLNA.ORG_PN variants for `AVC_MP4_*`, `MP3`, `AAC_ISO`, `JPEG_LRG`,
`PNG_LRG` so Windows recognises native formats and does not transcode them.

### DidlLite
Parses the `CurrentURIMetaData` DIDL-Lite document: `dc:title`, `upnp:artist` or `dc:creator`,
`upnp:album`, `upnp:albumArtURI`, `upnp:class`, and the `res` whose protocolInfo MIME agrees with the
`upnp:class` (falling back to the first `res`), plus the first `res` duration found. Some media servers list a
thumbnail `res` before the media `res`, so "first res" would misclassify a video as an image. Malformed or
empty metadata yields nulls, never an exception surfaced to the sender.

### EventSubscriptions / LastChangeEncoder
- `SUBSCRIBE` with `CALLBACK` and `NT: upnp:event` → new SID (`uuid:` random), `TIMEOUT: Second-1800`;
  renewal by SID; `UNSUBSCRIBE`; expiry sweep every minute. Missing/invalid headers → 400 or 412.
- The mandatory initial `NOTIFY` (SEQ 0) with the full state goes out right after the SUBSCRIBE reply.
- Later changes are sent as `LastChange` (AVT and RCS namespaces), moderated to at most one NOTIFY per
  200 ms per service, via a short-lived `HttpURLConnection` with a 5-second timeout on `Dispatchers.IO`.
  Two consecutive delivery failures drop the subscription.
- Callback URLs must be plain `http` to a private (RFC 1918 / link-local) address; otherwise 412.
- `LastChangeEncoder` builds the `<Event><InstanceID val="0"><TransportState val="…"/>…` document and
  escapes it for the property set. Pure, tested.

### MediaRenderer
- `load(uri, metadata, senderAgent)`: stops whatever is current, classifies the item, then either
  `DlnaPlayer.load(uri, audioOnly)` (video renders on the shared Surface, audio has no surface) or
  `PhotoFetcher.fetch(uri)`. State `TRANSITIONING` until prepared/fetched, then `PLAYING`.
- Tracks next URI/metadata; on completion starts it, otherwise goes to `STOPPED`.
- Maps player events to transport state and fires `LastChange` through `EventSubscriptions`.
- Drives the service callbacks: `onStateChanged(ProtocolState)`, `onNowPlayingChanged(NowPlayingInfo?)`
  (title/artist/album from DIDL, artwork fetched from `albumArtURI` with the photo size cap),
  `onPhotoReceived(bytes, mime)`, `onPhotoCleared()`, `onSenderNameChanged(String)`.
- Sender name from the control point's `User-Agent`: contains "Windows" → "Windows PC", "BubbleUPnP" →
  "BubbleUPnP", "VLC" → "VLC", otherwise "DLNA Sender".
- Refuses a **video** load with fault 501 "receiver busy" while an AirPlay session is CONNECTED (the
  Surface is in use). Audio and photos are unaffected. The AirPlay state is supplied by the service as a
  `() -> Boolean`.

### DlnaPlayer
Same shape as `AirPlayVideoPlayer` (`@Synchronized` methods, lazy surface provider, guard against
`onPrepared` racing `release()`) plus: `setVolume(0..100)`, `setMute`, absolute `seekTo(ms)`,
`positionMs()/durationMs()`, audio-only mode (no surface, `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`),
callbacks `onPrepared`, `onCompleted`, `onError(what, extra)`. Uses the header variant of
`setDataSource` to add `transferMode.dlna.org: Streaming` and `getcontentFeatures.dlna.org: 1`.

### PhotoFetcher
`HttpURLConnection` GET with 10-second timeouts and a 20 MB cap (named constant). Takes an injected
connection opener so limits are JVM-tested. Returns bytes + MIME or a typed failure.

## Service and UI integration

- `Protocol` enum gains `DLNA`. `ServiceState.kt` comments updated.
- `PhairPlayService`: `dlnaReceiver` field, `dlnaState` flow, `startDlna(settings)` (idempotent like
  `startAirPlay`), stop/reset lines, `sendDlnaTransportCommand(command)` for remote keys. Passes the same
  `videoSurfaceProvider` lambda and an `isAirPlayConnected` lambda. DLNA writes into the existing
  `_nowPlaying`, `_photoFrame` and `_activeConnection` flows; `updateNotification` shows the sender name
  while CONNECTED.
- `AppSettings.dlnaEnabled` (default `true`), `SettingsRepository` key, `anyProtocolEnabled` includes it.
  `SettingsFragment` gets a toggle row under Protocols (save + restart, like PIN auth).
- `HomeFragment` / `fragment_home.xml`: third card with `ic_dlna` drawable, `protocol_dlna` colour,
  strings in en/de/fr. Card state comes from `dlnaState`.
- `MainActivity.updateOverlay()` order becomes: PIN → now-playing → **photo** → streaming surface if
  AirPlay **or** DLNA is CONNECTED → hidden. Moving photo above the surface is safe for AirPlay because
  the service clears `_photoFrame` when an AirPlay stream connects.
- `MainActivity.onKeyDown`: when DLNA is CONNECTED (and AirPlay is not), play/pause/centre toggle,
  stop stops, fast-forward/rewind seek ±10 s, next starts the next URI. Routed through `MediaRenderer` so
  LastChange keeps the sender UI in step. AirPlay routing is unchanged.
- CONNECTED ⇔ transport state ∈ {TRANSITIONING, PLAYING, PAUSED_PLAYBACK}; `STOPPED` and
  `NO_MEDIA_PRESENT` show ADVERTISING and hide the overlay.

## Sender compatibility

- **Discovery.** Windows adds renderers from NOTIFY and from its own M-SEARCH for the device type. The
  advertiser restarts when the display name changes so the friendly name updates without a stale entry.
- **Description.** Windows refuses renderers without `X_DLNADOC` DMR-1.50, the standard serviceIds and a
  fetchable icon.
- **Formats.** Windows reads `GetProtocolInfo` before casting and transcodes formats not listed. The sink
  list above is limited to what `MediaPlayer` plays. If Windows reports a file as unplayable on a real
  test, that is the trigger to evaluate Media3 (separate ask-first decision, not part of this spec).
- **Control sequence.** Windows sends `Stop`, `SetAVTransportURI` (with DIDL), `Play` Speed `1`, then
  polls `GetPositionInfo`/`GetTransportInfo` about once a second and subscribes to AVTransport and
  RenderingControl events. Slideshows/playlists arrive as repeated `SetAVTransportURI` or
  `SetNextAVTransportURI`. `SetAVTransportURI` must return before the media is prepared.
- **Fetching.** Windows hosts local files on its own HTTP server and expects `Range` requests, which
  `MediaPlayer` issues.
- **BubbleUPnP / VLC.** Standard control points; BubbleUPnP relies on `SetNextAVTransportURI` and
  `GetCurrentTransportActions`; VLC on `GetPositionInfo` and `Seek REL_TIME`.

## Error handling

- SOAP faults use the codes listed under `SoapDispatcher`. Every fault is logged with the action name.
- HTTP: 400 malformed, 404 unknown path, 405 wrong method, 412 GENA precondition failed, 413 body over
  64 KB, 500 unexpected exception (logged with stack, never swallowed — CONTRIBUTING RULE 4).
- `MediaPlayer` error → transport `STOPPED`, status `ERROR_OCCURRED`, LastChange fired, overlay cleared,
  card back to ADVERTISING. Photo fetch failure or over-cap → same path.
- DOM parsing has DTDs and external entities disabled. GENA callbacks restricted to plain `http` on
  private addresses. Body and photo caps are named constants.
- Port 1900 or the HTTP port in use → `ProtocolState.ERROR` on the DLNA card only; AirPlay unaffected.
- All sockets, the `MulticastLock`, `MediaPlayer` and coroutine scope are released in
  `DlnaReceiver.stop()`; stop is idempotent.

## Testing

JVM (`./gradlew :test-runner:test`, baseline 230 green), one `[Class]Test.kt` per class, all pure Kotlin:

- `SsdpMessagesTest` — NOTIFY/byebye/M-SEARCH-response headers; parse M-SEARCH; `ST` matching incl.
  `ssdp:all`; MX clamp.
- `HttpRequestReaderTest` — request line, headers, body by Content-Length, 413 over cap, malformed → null.
- `DeviceDescriptionTest` — well-formed XML; UDN, friendlyName, `X_DLNADOC`, three serviceIds, URLs use
  the bound port.
- `SoapDispatcherTest` — envelope → action + args; response shape; unknown action → 401; bad
  InstanceID → 718; XXE payload rejected.
- `AvTransportServiceTest` (fake `RendererControl`) — every transition; 701 cases; Seek parsing incl.
  fractions; position formatting; next-URI on completion; `Next`; `GetCurrentTransportActions` per state.
- `RenderingControlServiceTest` — clamp, mute, non-Master channel → 402, presets.
- `ConnectionManagerServiceTest` — sink list, connection info.
- `ProtocolInfoListTest` — classification order (protocolInfo → class → extension), unknown → 714.
- `DidlLiteTest` — full metadata; missing fields; malformed → nulls.
- `EventSubscriptionsTest` — SID issue, renew, expiry, UNSUBSCRIBE, initial event SEQ 0, moderation,
  drop after two failures, non-private callback → 412.
- `LastChangeEncoderTest` — namespaces, escaping, multiple variables in one event.
- `PhotoFetcherTest` — cap enforced, MIME from header, failure typed.
- `AppSettingsTest`, `ServiceStateTest` — third protocol.
- Excluded from the JVM run (Android-only), like today's `AirPlayVideoPlayer`: `DlnaPlayer`,
  `SsdpAdvertiser` socket code, `DlnaHttpServer` accept loop, `DlnaReceiver`, UI.

Lint (`warningsAsErrors`) and both debug APKs must stay green.

On-device (Pi 4 LineageOS TV, ADB 192.168.1.185:5555): Windows "Cast to Device" plays an MP4 (video
overlay, transport controls, volume slider, seek), an MP3 (now-playing card with metadata/art) and a JPEG
(photo overlay); BubbleUPnP plays an album with automatic next-track; VLC renders a video and seeks.
`tools/collect-device-logs.sh` gains a `DlnaReceiver:V` tag.

## Docs

- New `docs/decisions/ADR-005-dlna-hand-rolled-upnp.md`: context, why not jUPnP/Cling, decision,
  consequences (fixed HTTP port constant, MulticastLock, MediaPlayer-first with Media3 as fallback).
- `README.md` (status, features, how to use from Windows), `docs/ARCHITECTURE.md` (component table,
  DLNA flow), `docs/spec/REQUIREMENTS.md` (DLNA FRs, out-of-scope row → Implemented),
  `docs/spec/PROJECT_PLAN.md` (Phase/M6 slot → DLNA), `docs/spec/TECHNICAL_SPEC.md` (architecture
  diagram, component table, codec matrix column), `docs/TESTING.md`, `docs/guides/TROUBLESHOOTING.md`
  (Windows prerequisites: network profile Private, Network discovery on, Windows Media Player Network
  Sharing Service running; router multicast filtering).
- `CHANGELOG.md`: `### Added` entry under `[Unreleased]`.

## Non-goals

- Acting as a DLNA media server (DMS) or control point (DMC).
- Playlist file parsing (M3U/PLS), subtitles, transcoding, OpenHome, gapless beyond `SetNextAVTransportURI`.
- Any Cast change; any AirPlay change beyond the `updateOverlay` reorder and the `isAirPlayConnected`
  lambda read by the service.
- Media3 / ExoPlayer (only if `MediaPlayer` fails on real files; ask first).
- System-volume control from the sender.

## Acceptance criteria

1. Windows "Cast to Device" lists the TV under the configured display name and plays an MP4, an MP3 and
   a JPEG; pause, seek, stop and the Windows volume slider work; the DLNA card shows Connected with the
   sender name.
2. BubbleUPnP plays two tracks back-to-back via next-URI; VLC renders a video and seeks.
3. JVM suite green with the new tests added (230 + new); lint and both debug APKs green; no Kotlin file
   over 400 lines.
4. AirPlay mirroring and audio behave exactly as before; DLNA disabled in Settings stops SSDP and the
   HTTP server and shows the card as Disabled.
