# Changelog

All notable changes to PhairPlay will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v1.0.0-alpha.3] — 2026-10-25

> **What this release is.** Three things that make the project easier to ship and
> easier to debug: the owner can cut a release himself with one command, the
> owner can check from a laptop whether the television sees the phone, and the
> diagnostics screen shows the session that is streaming right now.
>
> **It is an alpha.** No television has ever run this build: 0 of the 15 device
> test cases of `docs/guides/DEVICE_TEST_PLAN.md` have been executed.
>
> **Platforms:** Google TV (Android 10+) and Fire TV (Android 7.1+), signed with
> the same key as alpha.1 and alpha.2 — the installation updates in place.

### Added
- **`scripts/release.sh` cuts a release in one command** and refuses to publish
  anything it has not checked itself: the quality gate has to be green, the
  version inside the APK has to match the tag, the APK has to be signed. It
  publishes through the GitHub REST API, so the GitHub CLI is no longer needed.
- **`docs/guides/RELEASING.md`** — the guide for the owner: the three commands,
  where the key and the token live (and where they must not), what to do when
  the gate is red, and a table of the errors that can happen.
- **`scripts/device-check.sh`** — a network check the owner runs from a laptop in
  the same WLAN: it searches for the renderer over SSDP, fetches the device
  description, compares the address in the description URL with the laptop's
  own address, and tests the AirPlay port. It ends with a verdict and a sentence
  naming the next action.
- **`docs/guides/NETWORK_CHECK.md`** — the guide for that check, with realistic
  example output.
- **`scripts/test-device-check.sh`** — the self-test of the check: 28 assertions
  over 7 cases, positive and negative, running in seconds without a television.

### Changed
- The diagnostics report names the sender that is streaming right now, the
  protocol and the duration of the session — formatted for the language of the
  reader.
- The CI gate applies the file size rule to the shell scripts as well; until now
  it only checked Kotlin files.

---

## [v1.0.0-alpha.2] — 2026-10-25

> **What this release is.** Three milestones on top of `v1.0.0-alpha.1`: the receiver
> becomes visible again after a network loss, the app explains (and checks) its own
> state, and the language handling is completed.
>
> **It is an alpha.** No television has ever run this build: 0 of the 15 device test
> cases of `docs/guides/DEVICE_TEST_PLAN.md` have been executed. The new diagnostics
> screen exists to make those cases faster to diagnose — use it when you report a
> problem.
>
> **Platforms:** Google TV (Android 10+) and Fire TV (Android 7.1+), both signed with
> the same key as alpha.1 — an alpha.1 installation updates in place.

### Added
- **A diagnostics screen** (Settings → Diagnostics): the local address a television
  must reach, the listening ports, the state of every protocol and of the service.
- **A self-test that runs on the device**: is there a usable IPv4 address, does the
  device answer its own SSDP search, does the description server answer with HTTP 200,
  does something listen on the AirPlay port, how much memory does the app process
  use. Every check reports a measured value; a check that cannot measure says
  "not measured" instead of guessing.
- **Copy to the clipboard** for the whole report, so it can be pasted into an issue.
- The app now **offers its languages** (EN, DE, FR) to the system — on Android 13+
  the language can be chosen per app.
- `LocaleFormatting` — locale-aware formatting of numbers, byte sizes, durations and
  timestamps. The Arabic case is proven by the test suite: the output uses
  Arabic-Indic digits, not ASCII ones.

### Fixed
- **The receiver disappears after a network loss.** The AirPlay announcement recovered
  before, the UPnP/DLNA renderer did not: its SSDP announcements were sent once at
  startup, and the description URL still pointed at the IP address the device had
  before the outage. Both receivers now re-advertise, and the DLNA renderer rebuilds
  its description server when the address changed.

### Changed
- The diagnostic counts are expressed as plural resources (`1 byte` / `1842 bytes`),
  which is what the Android lint asks for — and what is correct.
- The FR-61 deviation in `docs/spec/REQUIREMENTS.md` is closed, and with it the
  corresponding rows of `docs/acceptance/M08-ACCEPTANCE.md`.

---

## [v1.0.0-alpha.1] — 2026-10-25

> **What this release is.** The first build that carries the M6–M12 cycle with the
> three security findings closed, the quality gate green and the documentation
> brought onto the state of the code. It ships **both platforms** — a Google TV APK
> (Android 10+) and a Fire TV APK (Android 7.1+).
>
> **It is an alpha.** No television has ever run this build: 0 of the 15 device test
> cases of `docs/guides/DEVICE_TEST_PLAN.md` have been executed, because no hardware
> was available where it was built. The release notes of the tag list every case
> that is still open.
>
> **The release plan is binding.** When alpha, beta and stable releases are built,
> which conditions trigger them and which platform each of them ships is documented
> in [`docs/spec/PROJECT_PLAN.md`](docs/spec/PROJECT_PLAN.md#release-schedule).

### Security
- The DLNA media URI is checked against an address policy: loopback, link-local
  (including the cloud metadata address), multicast, broadcast, reserved ranges and
  obfuscated IPv4 notations are refused.
- A media URL that the receiver would have to fetch itself is limited to `http`
  and `https`; everything else is answered with `400 Bad Request`.
- Image decoding is bounded: an edge length of 4096 px and a pixel budget keep a
  malicious photo from exhausting the memory of the television.
- A fragmented RTP unit is capped at 8 MB; the check happens before the unit is
  appended, and an incomplete unit is discarded instead of being carried over.

### Added
- A binding release schedule with the triggers for alpha, beta and stable releases
  and the platform each of them ships.
- `docs/acceptance/M06-ACCEPTANCE.md` … `docs/acceptance/M12-ACCEPTANCE.md` — the
  acceptance records of the milestones M6 … M12.
- `docs/guides/DEVICE_TEST_PLAN.md` — 15 copy-pasteable device test cases.

### Changed
- The documentation now describes the state the code is actually in: the README no
  longer calls the DLNA renderer "planned", the architecture overview lists the real
  classes, and every number in a document is a measurement with its command next
  to it.
- The CI quality gate checks the file size, the KDoc headers, the documentation
  links and the language completeness; the Android lint runs for both flavours.

### Known limitations
- Miracast is a control plane only — a real media path needs a system-level WFD
  stack that a sideloaded app cannot register for (`docs/decisions/ADR-004-miracast-media-path.md`).
- Google Cast is not included: the Cast SDK needs a registered application ID.
- The release APKs are signed with a new key. An installation from the Play Store
  has to be removed first.

---

## [Unreleased]

> **Scope of this entry.** This entry describes the cycle that produced the
> milestone status audit M0 … M12, the acceptance records, the risk register and
> the quality gate, plus the three points the team leader closed afterwards: the
> **CI quality gate**, the **Android lint** and the **address policy of the
> DLNA media URI**. Everything listed here is **in the tree**; nothing is
> announced as working on a device, because no device test has been executed
> (0 of 15 cases of `docs/guides/DEVICE_TEST_PLAN.md`).

### Added
- `SECURITY.md` — the security policy: threat model, the hardening guarantees the code enforces, the supported versions, and how to report a vulnerability privately.
- `PRIVACY.md` — the data protection statement: what leaves the television, what is stored on it, the permissions and their justification, and the explicit zero-telemetry commitment.
- `docs/audit/2026-10-24-security-audit.md` — the security and privacy review of 1.0.0-beta.3 with 14 findings, each with its attack path, a concrete fix and a negative test.
- `docs/project/RISKS.md` — the maintained risk register: 17 entries with likelihood, impact, countermeasure, owner and status.
- `docs/project/RELEASE_READINESS.md` — the release readiness checklist for a stable v1.0.0: what is proven, what is open, in which order it has to be closed, and what genuinely needs real hardware.
- `docs/acceptance/M06-ACCEPTANCE.md` … `docs/acceptance/M12-ACCEPTANCE.md` — the acceptance records of the milestones M6 … M12. Each one states, for every criterion, whether it is proven by a test, proven only on the code, or still open — and why.
- `docs/guides/DEVICE_TEST_PLAN.md` — the device test plan with 15 copy-pasteable cases (DT-01 … DT-15), the hardware and software prerequisites, the time limits per case and a result sheet.
- `docs/acceptance/QA-QUALITY-GATE-ACCEPTANCE.md`, `docs/acceptance/QA-I18N-ACCEPTANCE.md`, `docs/acceptance/QA-DEVICE-TEST-ACCEPTANCE.md` — the acceptance records of the quality gate, of the language coverage and of the device test plan.
- `docs/audit/qa-quality-gate-negative-test.md` — the raw protocol of the negative test of the four check scripts (22 cases, green → red → green).

### Changed
- **The DLNA renderer is now a documented, delivered part of the product** — the milestone M12 was "planned" in the project plan; the package `com.phairplay.dlna` exists with 29 production files and 157 of the 457 green JVM tests of the measured run (169 test methods in the tree today). `docs/spec/PROJECT_PLAN.md` now says so, and the per-sub-phase boxes reflect the code.
- **The specification documents were realigned with the code.** `docs/spec/REQUIREMENTS.md` v3.0 adds FR-44 … FR-70 and NFR-26 … NFR-31, states the implementation status of every amended requirement and removes the "DLNA / UPnP — Out of scope — Not planned" line that contradicted ADR-004 and the code. `docs/spec/ACCEPTANCE_CRITERIA.md` v2.0 introduces the `AC-M<n>.<m>` identifiers for M6 … M12 and the mapping table between the acceptance rounds and the milestones. `docs/spec/TECHNICAL_SPEC.md` v1.3 describes the fourth receiver and the DLNA hardening guarantees.
- **The five largest source files were split.** `RtspHandler.kt` went from 1024 to 267 lines (twelve new files, the original class stays as the facade), `PhairPlayService.kt` from 650 to 289, `AirPlayReceiver.kt` from 658 to 374, `MdnsService.kt` from 527 to 338, `AudioStreamServer.kt` from 503 to 259. The public API of each class is unchanged; the moved symbols keep their signature and their visibility.
- **The CI got a quality gate.** `.github/workflows/ci.yml` has a new job `quality-gate` that runs four repository structure checks — file size, KDoc headers, documentation links and language completeness — and fails the build on a violation. `lint.yml` additionally fails when a lint run produced no report, and the APK upload fails when no APK was built.
- **The language coverage was completed.** `values-de/strings.xml` and `values-fr/strings.xml` now carry all 80 keys of the base file; the base file's header comment no longer allows omitting a key.

### Security
- **The AirPlay URL mode no longer fetches an arbitrary address.** In the URL mode the sender names the address and the television fetches it, so a single `POST /play` from any device in the network could aim the receiver at a router admin page, a NAS, a printer or a camera and play the answer on the screen; a `file://` URL additionally turned the receiver into a file reader for everything the app's own UID may read. `POST /play` now applies `FetchableUrlPolicy`: only `http` and `https` may be fetched, the URL must be free of control characters and at most 4096 characters long, and the host must pass the shared address policy `HostAddressPolicy` — the loopback of the receiver, the link-local range (and with it the cloud metadata service of the router `169.254.169.254`), the multicast and broadcast ranges and the obfuscated notations of an address are refused. A refused URL is answered with `400 Bad Request` and the player is never asked to fetch it. A public address stays allowed on purpose: the sender decides which medium the owner wants to see, and PhairPlay itself sends nothing.
- **The DLNA media URI has an address policy.** A control point names the media URL in `SetAVTransportURI`, and the television fetches it itself — a hostile device on the network could therefore aim the TV at the cloud metadata service of the router (`169.254.169.254`) and read the answer, which contains credentials, as if the TV had asked for it. The new `HostAddressPolicy` refuses the loopback (`127.0.0.0/8`, `::1`, `::ffff:127.0.0.1`), the reserved range `0.0.0.0/8`, the link-local range, the multicast range, the broadcast address, the reserved range `240.0.0.0/4`, the further metadata addresses (`100.100.100.200`, `192.0.0.192`, `fd00:ec2::254`), the known metadata host names, the non-standard notations of an IPv4 address (decimal `2130706433`, octal `0177.0.0.1`, hexadecimal `0x7f000001`) and every URI that hides the real host behind a user info part. It is covered by `HostAddressPolicyTest` (11 test methods) and a new case in `DlnaMediaUriTest`.
- **Central log reduction.** Every message that goes through `com.phairplay.util.Logger` is now reduced before it reaches the log: the host part of an IP address, the device-specific half of a MAC address, complete UUIDs, and the credentials and query string of a URL are replaced by placeholders. A logged exception is copied and sanitised as well, because exception messages repeat the value that caused them. The reduction is on by default in every build — a debug build and a release build log the same, reduced data — and can be switched off for a local debugging session with `Logger.setRedactionMode(LogRedactionMode.OFF)`.
- **The location permissions are bounded.** `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` are now capped at API 32, and `NEARBY_WIFI_DEVICES` carries `android:usesPermissionFlags="neverForLocation"`. A television running Android 13 or newer therefore no longer declares a location permission at all. The runtime behaviour is unchanged — the permissions were never granted, because the app never requests them.
- **The GENA event now actually reaches the control point.** `HttpURLConnection` refuses the HTTP method `NOTIFY`, so the `LastChange` event was never delivered. It now goes out over a raw socket.
- **A picture can no longer blow up the receiver.** A photo that is small on the wire but enormous once decoded — 20 000 × 20 000 px is well under the 25 MB payload limit and would have cost 1.6 GB of pixels — was decoded with `BitmapFactory.decodeByteArray` without bounds and without sampling, and killed the app with an `OutOfMemoryError`. The new `com.phairplay.util.SafeBitmapDecoder` reads the dimensions first, refuses what does not fit into the pixel budget and downsamples the rest with an `inSampleSize`, so the pixels are never allocated at full size. `PhotoScreen` and the album artwork of `NowPlayingScreen` use it. The downsampling arithmetic is the same one the DLNA photo path already used.

### Fixed
- **The CI quality gate is green.** The KDoc check reported 16 class declarations without a KDoc header; all 16 now have one (`OK: KDoc header check passed — 227 class declaration(s) checked, all of them have a KDoc header.`). The three files above the soft limit of 400 lines were split as well: `MainActivity.kt` 447 → 348 lines (the navigation and the service binding moved to `ui/FragmentNavigator.kt` and `ui/ServiceConnectionBinder.kt`), `miracast/MiracastReceiver.kt` 439 → 260 lines (the WFD RTSP server moved to `miracast/WfdRtspServer.kt`), `airplay/RtspHandlerTest.kt` 413 → `RtspHandlerHandshakeTest.kt` and `RtspHandlerSessionTest.kt` with the shared helper `TestableRtspHandler.kt`. The size check is now warning-free: `184 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.`
- **The Android lint is green for both flavours.** The only finding was `Attribute usesPermissionFlags is only used in API level 31 and higher (current min is 29)` — the attribute belongs to the promise that PhairPlay never derives a location from a Wi-Fi result, so it stays and is now marked with `tools:targetApi="31"`. Both variants are analysed: `:app:lintGoogletvDebug` and `:app:lintFiretvDebug` report `No issues found.` The Fire TV flavour was analysed for the first time in this round.
- The manifest claimed that `ACCESS_WIFI_STATE` is used to read the IP and MAC address. It is not: both are read with `java.net.NetworkInterface`, which needs no permission. The comment and the unused `WifiManager` import are gone.
- The mirroring frame queue is bounded by **32 MB**, not 64 MB. The 1.0.0-beta.3 entry said 64 MB; the code (`MirrorFrameQueue.DEFAULT_MAX_BYTES`) says 32 MB, and the unit test asserts 32 MB.
- **The reassembled FU-A unit is bounded.** The cap of 2 MB applied to a single RTP packet, not to the NAL unit that is assembled from many of them. A peer that opened a fragmented unit and never sent the end bit grew the accumulator until the process died with an `OutOfMemoryError`. `RtpInterleaved.MAX_FUA_BYTES` (8 MB) now caps the unit, the check runs **before** the append, and a fragment that would cross the cap is discarded together with the incomplete unit — so the next start fragment begins a clean unit again.

### Known limitations of this build
- **No device test has been executed.** The 15 cases of the device test plan have never been run; the acceptance records mark every criterion that needs hardware as open.
- **The DLNA media URI is still not restricted to `http`/`https`.** `DlnaMediaUri` accepts `file`, `smb`, `ftp` and other schemes. That is a deliberate decision for the compatibility with real control points, but it means the scheme reduction of the change request CR-01 is still open. The *address* of the URI is now checked (see **Security**); what stays open is the scheme list, the accepted **public addresses** and the **host names**, which are not resolved — a name that points at a link-local address remains a residual risk.
- **The cleartext `http://` media fetch is probably blocked on API 35.** The app declares no `network_security_config.xml`; the platform default refuses cleartext. Whether the DLNA media path works at all on a current device is therefore unproven.
- **The picture decoder is only covered by its rule, not by its decoding.** `BitmapFactory` is part of the Android framework and does not exist in the pure JVM test module. `SafeBitmapDecoderTest` therefore proves the dimension and pixel budget in front of the decoder; the decoding path itself needs an instrumented test on a real device.
- **The README understates the DLNA status** ("planned") and the version. The README belongs to the docs track; the correction is proposed in the project handover and not applied here.

---

## [1.0.0-beta.3] - 2026-10-24

### Added

**UPnP / DLNA MediaRenderer — discovery and control plane (ADR-004 phases D1 + D2)**
- The TV appears as a DLNA MediaRenderer in Windows "Cast to Device", VLC and mobile UPnP controllers.
- SSDP discovery (`SsdpAdvertiser`): advertises `urn:schemas-upnp-org:device:MediaRenderer:1` and answers `M-SEARCH` queries.
- Device description server (`DlnaDescriptionServer`): serves the UPnP device descriptor and the SCPD over a bounded, non-blocking HTTP server (2 s socket timeout, 16 KB header budget, 64 KB body budget, 8 KB per-line limit).
- SOAP dispatcher (`DlnaSoapDispatcher`): `ConnectionManager:1`, `AVTransport:1` and `RenderingControl:1` actions, parsed with a hardened XML reader (`FEATURE_SECURE_PROCESSING`, doctype declarations and external entities disabled).
- GENA eventing (`GenaEventManager`): `LastChange` notifications; callback URLs are restricted to private IPv4 addresses inside the local subnet (SSRF hardening).
- `MediaCapabilities`: detects what the device can decode and logs it at service start. The protocol list that the renderer advertises comes from `DlnaProtocolInfo`, a fixed list that describes what the renderer can pull.
- Settings toggle "UPnP / DLNA Receiver" and a status card on the Home screen (en / de / fr).
- Protocol isolation per ADR-004: all DLNA logic lives in `com.phairplay.dlna`; the AirPlay 2 stack is untouched. DLNA is an additional, independently switchable protocol — it does not replace Miracast or Google Cast.
- Media playback (phase D3) is **not** part of this release as a published feature: the wiring exists in the code (`DlnaReceiver` hands the URI to the video player), but it has never been run on a device, and the platform blocks the cleartext `http://` fetch on API 35 until a network security configuration exists. See the known limitations of the release.

**Bring the app forward when a mirroring session starts (PR #19)**
- A mirroring session that begins while the app is in the background now opens the streaming screen instead of showing a black surface until the app is opened by hand. Requires the optional "Display over other apps" permission; without it the behaviour is unchanged.
- Audio-only sessions are deliberately **not** brought to the front — a podcast or Apple Music stream no longer interrupts whatever is on screen.

**Mirroring recovery across resolution changes (PR #17)**
- The H.264 SPS parser accepts raw and Annex-B input through one shared implementation; the SPS prefix is no longer misread as profile fields.
- A missed codec input no longer discards the only IDR available after a reconfiguration — the decoder retries once on a codec rebuilt with the cached SPS/PPS.
- The display uses a fixed-size `TextureView` with matrix-based aspect fitting, so a resolution change no longer freezes the picture.
- A diagnostics overlay reports received / submitted / output / render-callback / texture-update counters, the display refresh rate and the recovery attempts.

### Fixed
- **AirPlay did not appear in the sender's device list (issue #18)**: four separate defects made the receiver invisible — protocol switches and the device name in Settings were only persisted and never applied to the running service; a failed mDNS registration was never retried; `FAILURE_ALREADY_ACTIVE` was treated as a success; and the Home screen reported "Disabled — Enable in Settings" for a receiver that was enabled and advertising.
- **mDNS registration could not recover**: the two mDNS services are now registered independently with a bounded retry (1 s / 3 s / 6 s), a failed service no longer tears down its sibling, and a permanently failing advertisement ends in an explicit error state instead of silently disappearing from every AirPlay picker.
- **mDNS churn on network transitions**: re-advertisement now happens only after the last network was actually lost. Previously the first `NetworkCallback.onAvailable` raced the initial registration and unregistered it again, which is what produced the platform's "Exceeded max outstanding requests" refusal.
- **Misleading status text**: a protocol card that is disabled because the service is stopped now says "Start the service to advertise" instead of pointing at a setting that was already correct.
- **Advertised device names**: the name is sanitised before registration (control characters removed, capped at the DNS-SD limit of 63 characters). A name the platform's mDNS daemon rejects used to take the whole AirPlay receiver down.

### Security
- The H.264 SPS parser computes Exp-Golomb codes in 64-bit arithmetic and rejects values above the plausible range, so a crafted SPS can no longer overflow into a bogus picture size.
- The mirroring frame queue is now bounded by bytes (32 MB) as well as by item count (90). Previously 90 frames of the permitted 8 MB each could keep ~720 MB alive and OOM the receiver.
- The DLNA description server bounds the length of a single request and header line and drains a rejected request before closing, so a client cannot make it buffer an unbounded line.

---
## [1.0.0-beta.2] - 2026-10-09

### Fixed
- **Screen awake during playback**: Prevent TV screensaver / sleep from activating during active AirPlay mirroring or audio streaming (`FLAG_KEEP_SCREEN_ON`). Fixes session teardown after ~19 minutes on Fire TV and Google TV (PR #13).
- **mDNS recovery after standby / network drop**: Automatically re-advertise AirPlay via mDNS when network connectivity returns after device standby or Wi-Fi reconnect (PR #13).
- **Wi-Fi multicast packet loss**: Acquire `WifiManager.MulticastLock` while advertising to ensure sender discovery packets are received reliably in power-saving mode (PR #13).
- **iOS 17+ AirPlay 2 mirroring SETUP limit**: Raised RTSP message size cap (`MAX_MESSAGE_BYTES`) from 64 KB to 1 MB, resolving `Request body too large` errors during binary plist negotiation on newer iOS senders (PR #11).
- **IPv4 wildcard socket binding**: Explicitly bind RTSP, event, audio, and mirror sockets to `0.0.0.0` to ensure compatibility with Android TV network stacks (e.g. Philips/Sony) where dual-stack IPv6 sockets dropped IPv4 SYNs (PR #15).

---

## [1.0.0-beta.1] - 2026-06-14

### Added

**AirPlay 2 receiver — full stack**
- Screen mirroring (H.264) from macOS 12+ and iOS/iPadOS 16+ via RTSP on port 7000
- FairPlay session decryption: fp-setup v2 (RAOP audio) and v3 (mirroring/Safari) via native libplayfair (JNI); legacy rsaaeskey RSA-OAEP recovery for AirPort Express compatibility
- HomeKit-style pairing: Ed25519 identity, X25519 ECDH key agreement, controller key persistence (`PairingStore`), failed-attempt lockout
- Legacy SRP-6a PIN pairing with on-screen PIN entry screen (`LegacyPairSetupPin`, `PinScreen`)
- `MirrorStreamServer` + `MirrorCrypto` — interleaved RTP reassembly, AES-128-CTR stream decryption (keystream always advanced to prevent reuse)
- `AudioStreamServer` — mirror realtime audio (type 96): UDP RTP, AES-128-CBC, AAC-ELD/AAC-LC decode via MediaCodec, RAOP retransmit, AudioTrack with volume
- `AlacDecoder` + native libalac — RAOP/SDP audio path: AES-128-CBC (per-packet IV) + Apple's ALAC decoder; decode-health mute guard (wrong key → silence, not static)
- `BufferedAudioServer` — AirPlay 2 buffered audio (type 103) accepted and instrumented
- `AirPlayVideoPlayer` — AirPlay video URL mode (`/play`) + transport controls (play/pause/scrub/stop)
- `NowPlayingInfo` (DMAP parser) + album artwork → `NowPlayingScreen` overlay
- `DacpClient` — `_dacp._tcp` discovery + reverse transport control from TV remote to sender (play/pause/skip/volume)
- `AirPlayNtpClient` — Apple NTP for A/V synchronisation
- `InfoResponder` — `GET /info` capability advertisement (plist)
- `PlistCodec` — Apple binary plist encode/decode
- `RaopRsa` — legacy rsaaeskey recovery (RSA-OAEP, AirPort Express key)
- `StreamStats` — per-session RTP statistics (packet count, duplicates, queue drops)
- `Base64Util` — pure-JVM Base64 so SDP parsing is testable without Android framework
- `SdpParser` — extended: codec/encryption/channel/rate parsing for all AirPlay audio types
- Aspect-fit (letterbox/pillarbox) video rendering with black background in `StreamingScreen`
- Real PNG bitmap launcher icon and TV banner (replaces placeholder XML)
- Mirror Audio toggle and PIN-auth toggle in Settings
- Receiver survives app restart/relaunch; mirroring and audio stop cleanly on app exit

**Native layer**
- CMake build for all ABIs (armeabi-v7a, arm64-v8a, x86, x86_64)
- `fairplay_jni.c` — JNI bridge for `playfair_decrypt` with full null/length/OOM validation
- Apple ALAC decoder (C++, vendored) + JNI bridge (`alac_jni.cpp`)
- Reverse-engineered FairPlay (C, `playfair/`) compiled for all ABIs
- Strict-aliasing fix in `modified_md5.c` (union type-punning) and `sap_hash.c` (memcpy + union)

**Test suite**
- 247 unit tests, 0 failures: FairPlay, RaopRsa, Base64Util, ALAC cookie, DMAP, legacy PIN SRP, audio stream server, RTSP handler, service controller
- Robolectric added for framework-dependent tests (Android Base64, Intent, etc.)

**Release infrastructure**
- `scripts/release.sh` — local release script: builds signed GoogleTV + FireTV APKs, creates git tag, publishes GitHub Release via `gh` CLI (no CI minutes consumed)
- First signed GitHub Release: [v1.0.0-beta.1](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-beta.1)

### Changed
- `VideoDecoder`: SPS/PPS-driven reinit on resolution change, self-heal on decoder error, keyframe resync after drops, decoupled network reader (bounded queue, drop-under-load), re-attach to Surface after backgrounding
- `AudioPlayer`: extended to support ALAC and new audio stream types from `AudioStreamServer`
- `RtspHandler`: extended to 700+ lines — handles all AirPlay 2 verbs (ANNOUNCE, SETUP plist+SDP, RECORD, TEARDOWN stream-scoped, GET/SET_PARAMETER, FLUSH, PAUSE, photo PUT/DELETE, `/play`, `/rate`, `/scrub`, `/stop`, `/feedback`, buffered-audio control)
- `AirPlayReceiver`: event channel socket now closed via `use {}` block (fixes file-descriptor leak)
- `SettingsFragment`: mirror audio and PIN-auth toggles added

### Fixed
- `DatagramPacket` length reset before each `receive()` call in `AudioStreamServer` — prevented packet truncation when a smaller packet arrived first
- JNI bridge (`fairplay_jni.c`) now validates input arrays for null, length, and OOM before native access — prevents out-of-bounds reads and native crashes
- Strict-aliasing UB in `modified_md5.c` and `sap_hash.c` — union + memcpy replaces direct `uint32_t*` cast of `unsigned char*`
- `Cipher.getInstance()` moved out of hot path in `AudioStreamServer` (~92 allocations/s → 1 per session)

---

<!-- Format:
## [X.Y.Z] - YYYY-MM-DD

### Added
### Changed
### Fixed
### Removed
### Security
### Known limitations of this build
-->
