# PhairPlay — Threat Model

**Date:** 2026-11-05
**Applies to:** `main` @ `34a166c` (release `v1.0.0-alpha.7`) plus the hardening
changes of this cycle
**Method:** every network-facing parser, socket, queue and store was read in the
source. Every claim in this document was checked against the code, not against the
previous audit. Where a claim could not be measured in this environment, it says so.

This document is the source of truth for the threat model. `SECURITY.md` is the short
version for users; `docs/security/FINDINGS.md` carries the findings with their attack
paths; `docs/security/PERMISSIONS.md` carries the permission inventory.

---

## 1. The attacker

**The attacker is any device in the same Wi-Fi network**: a guest's phone, a laptop, a
compromised household appliance, a device that is already on the network and does not
belong to the owner.

**PIN authentication is off by default** (`SettingsRepository.airPlayPinAuthEnabled`,
default `false`), so a sender does not have to prove anything before it may start a
session. Every byte that arrives over the network is therefore untrusted input.

| Property | Value | Why it matters |
|---|---|---|
| Credentials | none (PIN auth off by default) | every parser below is reachable without an authentication |
| Physical access | none assumed | no local socket, no ADB, no shared storage |
| Network position | same L2 segment | multicast (mDNS, SSDP) and unicast (TCP 7000, the DLNA port) are both reachable |
| DNS | a cooperating or compromised resolver in the LAN is possible | a name is not resolved, so a name can point anywhere |
| Internet | no route in the model | PhairPlay sends nothing to the internet itself — but a *peer* can name a URL that the receiver then fetches |

**Out of scope:** attacks that require physical access to the television, attacks
from the internet (the application has no internet code path of its own), and attacks
on the Android platform itself.

**The damage is bounded by what a display can be made to do:** take over the screen,
play sound, show a picture, crash the app, make the app fetch a URL of the attacker's
choosing, and — through a user who pastes a log excerpt or a diagnostics report into
a public issue tracker — publish the household's network details.

---

## 2. Attack surfaces

Status values: **hardened** (a bound exists and is tested) · **hardened (residual)**
(a bound exists, a documented residual risk remains) · **open** (no bound, tracked in
the findings register).

| # | Surface | Attacker | Entry point (file:line) | Damage potential | Existing countermeasure (file) | Residual risk | Status |
|---|---|---|---|---|---|---|---|
| S-01 | RTSP control plane (AirPlay) | any LAN device | `airplay/RtspServer.kt:123` (`runServer`), TCP 7000 | screen takeover, sound, crash | one client at a time (`RtspServer.kt:132`, second client gets `503`), 1 MB message budget (`RtspServer.kt:257`), header and body budget (`RtspRequestReader.kt:56`, `:93`) | the control plane is plaintext — a sniffer reads it | hardened (residual) |
| S-02 | RTSP binary plists | any LAN device | `airplay/handshake/PlistCodec.kt:65` (`decode`), reached from `POST /feedback` (`airplay/RtspDeviceInfoHandler.kt:60`), `POST /play` (`airplay/RtspVideoUrlHandler.kt:52`), mirror `SETUP` (`airplay/RtspMirrorHandler.kt:84`) | crash of the request thread, memory exhaustion | size limit 1 MB (`PlistCodec.kt:45`), every parse failure — including a stack overflow — becomes an `IllegalArgumentException` (`PlistCodec.kt:65`) | none known | hardened |
| S-03 | SDP (`ANNOUNCE`) | any LAN device | `airplay/SdpParser.kt:48` (`parse`) | bogus session parameters, crash | every field is range-checked before it is used; the parser returns `null` on anything it does not understand | none known | hardened |
| S-04 | H.264 SPS/PPS | any LAN device | `airplay/H264SpsParser.kt:59` (`parseResolution`) | bogus resolution, decoder fault | Exp-Golomb in 64-bit arithmetic, `ue(v)` values above `0xFFFF` refused (`H264SpsParser.kt:270`), 31 leading zeros refused (`H264SpsParser.kt:278`), dimension range-checked (`H264SpsParser.kt:30`, `:172`) | none known | hardened |
| S-05 | Interleaved RTP video, FU-A reassembly | any LAN device | `airplay/RtpInterleaved.kt` (frame reader), TCP 7000 | memory exhaustion | single frame ≤ 2 MB (`RtpInterleaved.kt:69`), reassembled NAL unit ≤ 8 MB checked **before** the append (`RtpInterleaved.kt:81`) | one warning per discarded fragment — a logging nuisance | hardened |
| S-06 | Mirroring payload and frame queue | any LAN device | `airplay/handshake/MirrorStreamServer.kt:98` (payload), queue in `airplay/handshake/MirrorFrameQueue.kt` | memory exhaustion | payload ≤ 8 MB (`MirrorStreamServer.kt:345`), queue ≤ 90 items **and** ≤ 32 MB (`MirrorFrameQueue.kt:148`, `:159`) | none known | hardened |
| S-07 | Audio RTP, ALAC payloads, control channel | any LAN device | UDP 6001 (`airplay/AirPlayMediaPipeline.kt:131`), UDP control (`airplay/handshake/AudioStreamServer.kt:109`) | crash, memory exhaustion | UDP buffers are fixed-size (16 KB, `AirPlayMediaPipeline.kt:188`; 2 KB, `AudioStreamServer.kt:109`), reorder buffer holds 32 packets (`airplay/handshake/AudioReorderBuffer.kt:173`) and resends at most 128 (`AudioReorderBuffer.kt:176`) | none known | hardened |
| S-08 | Photo endpoint | any LAN device | `airplay/PhotoHandler.kt:14` (`validatePhoto`), `PUT /photo` on TCP 7000 | memory exhaustion, crash | magic-byte sniffing, 25 MB body cap (`PhotoHandler.kt:12`), pixel budget before the decoder (`util/SafeBitmapDecoder.kt:37`, `:46`) | the decoding path itself needs a device test — the rule in front of it is proven | hardened (residual) |
| S-09 | Pairing and FairPlay handshake | any LAN device | `airplay/RtspPairingHandler.kt:47` (`/pair-setup`), `:64` (`/pair-verify`), `:109` (`/pair-setup-pin`), `airplay/handshake/FairPlay.kt:34` | session takeover, key disclosure | Ed25519 identity, X25519 ECDH (`airplay/handshake/PairingSession.kt:52`), persistent failed-attempt counter with a lockout after 10 tries (`RtspPairingHandler.kt:152`), PIN proof compared in constant time (`airplay/handshake/LegacyPairSetupPin.kt:74`) | the PIN space is 10 000 — the lockout is the real defence | hardened (residual) |
| S-10 | DACP reverse control | any LAN device | `airplay/DacpClient.kt` (outbound command to the sender) | the `Active-Remote` token is echoed to a peer-chosen address | 2 s timeouts, `HttpURLConnection` | the token goes to the address the peer that started the session named — that is the DACP protocol | hardened (residual) |
| S-11 | mDNS advertisement and TXT records | any LAN device | `airplay/MdnsService.kt` (registration), multicast 5353 | passive identification of the television | name sanitised and capped at 63 characters (`airplay/MdnsServiceName.kt:20`), registration retried with backoff, one registration per service | the MAC address and the UUID are broadcast in cleartext — required by the protocol | hardened (residual) |
| S-12 | SSDP `M-SEARCH` | any LAN device | UDP 1900, `dlna/SsdpAdvertiser.kt:189` | announcement spoofing, reflector | the responder answers only for the service types it implements (`dlna/SsdpResponder.kt`), the datagram buffer is 2 KB (`SsdpAdvertiser.kt:289`), no state is derived from the request | none known | hardened |
| S-13 | DLNA HTTP description server | any LAN device | `dlna/DlnaDescriptionServer.kt:109` (`acceptLoop`), the description port | denial of service, buffer exhaustion | 16 KB header budget, 8 KB line limit, 64 KB body cap (`dlna/HttpRequestReader.kt:142`, `:145`, `:148`), 2 s socket timeout and a 10 s request deadline (`DlnaDescriptionServer.kt:371`, `:374`), 8 worker threads + 16 queued clients (`DlnaDescriptionServer.kt:377`, `:380`), a rejected request is drained (`DlnaDescriptionServer.kt:353`) | none known | hardened |
| S-14 | SOAP / XML control plane | any LAN device | `dlna/DlnaSoapDispatcher.kt:42` (`dispatch`) | XXE, entity expansion, crash | `FEATURE_SECURE_PROCESSING`, doctype and external entities disabled, external DTD/Schema access denied (`dlna/SecureSoapXml.kt:164`), 64 KB body cap (`SecureSoapXml.kt:33`), 32-level depth cap (`SecureSoapXml.kt:36`) | none known | hardened |
| S-15 | GENA event subscription and callback | any LAN device | `SUBSCRIBE` on the description port, `dlna/GenaEventManager.kt:51` | SSRF out of the LAN, resource exhaustion | the callback must be a private IPv4 in the same /24 and must not be a host name (`dlna/GenaCallbackPolicy.kt:63`), 3 s connect timeout (`GenaEventManager.kt:257`), at most 32 subscriptions (`GenaEventManager.kt:254`) | the guard resolves the name and the notification connects to the name again — a TOCTOU window (SEC-15) | hardened (residual) |
| S-16 | DLNA media URI (`SetAVTransportURI`) | any LAN device | `dlna/DlnaAvTransportService.kt:39`, rule in `dlna/DlnaMediaUri.kt:57` | the television becomes a request proxy and a file reader | **one** rule for both protocol stacks: `util/FetchableUrlPolicy.kt:81` — `http`/`https` only, bounded length, no control characters, address checked by `util/HostAddressPolicy.kt:77` (no loopback, no link-local, no metadata service, no obfuscated notation, no user info) | a **public** address stays allowed, and a host name is not resolved — both are documented decisions | hardened (residual) |
| S-17 | DLNA photo fetch | any LAN device | `dlna/DlnaReceiver.kt:269` (`fetchBounded`) | memory exhaustion, decompression bomb | 20 MB download cap (`dlna/DlnaImageDecoder.kt:23`), 10 s timeout, redirects not followed, downsampling before the screen (`DlnaImageDecoder.kt:26`, `util/SafeBitmapDecoder.kt:37`) | none known | hardened |
| S-18 | Miracast (Wi-Fi Direct, WFD RTSP) | any LAN device | `miracast/WfdRtspServer.kt:77`, TCP 7236 | screen takeover, crash | one client at a time, 64 KB message budget (`WfdRtspServer.kt:202`), the RTSP reader is shared with the AirPlay stack | the feature cannot start on Android 10+ without a runtime permission request (SEC-16) | hardened (residual) |
| S-19 | Google Cast | any LAN device | Cast discovery, `app/src/googletv/kotlin/com/phairplay/cast/CastReceiver.kt` | Cast session | the official Google SDK, `googletv` flavour only; the Fire TV flavour reports `DISABLED` | the SDK is a black box outside our control; the application ID is still missing (SEC-17) | documented |
| S-20 | Diagnostics report and its export | the user (indirectly) | `diagnostics/DiagnosticsReportBuilder.kt:50` (screen), `:66` (export), `ui/DiagnosticsReportExporter.kt` | publication of the household's network details in a public bug tracker | the screen keeps the name and the address (the owner needs them); the **export** carries no IP address, no MAC address, no UUID, neither the device name nor the sender name (`diagnostics/DiagnosticsExportRedactor.kt:71`) | the free text of the self test is pattern-redacted; a name inside an unexpected value would survive | hardened |
| S-21 | Log output | the user (indirectly) | `adb logcat`, a bug report; every message through `util/Logger.kt` → `util/LogRedactor.kt` | disclosure of the household's network in a pasted log excerpt | central reduction of IP addresses (host octet), MAC addresses (device half), complete UUIDs, URL credentials and query strings | **free text is not redacted** — the device name, the sender name and the title of the playing track are logged as they are (SEC-13) | hardened (residual) |
| S-22 | Local storage and the device identity | an attacker with the device | `airplay/handshake/PairingKeys.kt:38` (Ed25519 seed), `airplay/handshake/PairingStore.kt:12` (paired controllers) | loss of the receiver identity, impersonation of the television | app-private storage, `allowBackup="false"` **plus** explicit data extraction rules that exclude every domain from the cloud backup *and* from the device-to-device transfer (`app/src/main/AndroidManifest.xml:133`, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`) | the seed is stored in cleartext inside the app sandbox — the platform offers no keystore binding here | hardened |
| S-23 | Permissions | the user | the permission dialog, `app/src/main/AndroidManifest.xml` | excessive trust | every declared permission is traced to a feature (`docs/security/PERMISSIONS.md`); the location permissions are capped at API 32 | the Miracast permissions are declared but never requested, so the feature is dead on Android 10+ (SEC-16) | documented |
| S-24 | Boot and notification paths | — | `service/BootReceiver.kt`, `service/PhairPlayService.kt` | the receiver starts without the owner asking for it | "start on boot" is off by default, the service is `exported="false"`, the boot receiver is `exported="false"` (`AndroidManifest.xml:176`) | an OEM may reset the app-op on boot — documented in the device test plan | hardened |

---

## 3. What is *not* an attack surface

* **The Android platform** — the app runs with the platform's sandbox and permissions.
* **The file system** — no external storage permission, no `File`/`openFileOutput`
  use for media, no cache or download directory. The media is memory-only.
* **The internet** — there is no analytics SDK, no crash reporter, no update check.
  The only outbound HTTP calls are the DACP command, the GENA notification and the
  media fetch; the first two are addressed to the peer the protocol designates, the
  third is now behind the URL allow-list of S-16.

---

## 4. The rules that carry the weight

Four rules do most of the work. Each of them exists in **exactly one place**, so
that a second protocol stack cannot grow a wider version of it.

| Rule | Where it lives | Applies to |
|---|---|---|
| A URL the receiver fetches itself must be `http`/`https`, bounded, clean, and point at an allowed address | `util/FetchableUrlPolicy.kt:81` + `util/HostAddressPolicy.kt:77` | `POST /play` (AirPlay), `SetAVTransportURI` (DLNA) |
| A GENA callback must be a plain private IPv4 of the local subnet | `dlna/GenaCallbackPolicy.kt:63` | `SUBSCRIBE` |
| Every log message is reduced before it is written | `util/Logger.kt` → `util/LogRedactor.kt` | every log statement in the app |
| Everything that leaves the app is reduced again, more strictly | `diagnostics/DiagnosticsExportRedactor.kt:71` + `diagnostics/DiagnosticsReportBuilder.kt:66` | the copied diagnostics report |

---

## 5. What could not be measured here

* **No device, no television.** Every statement about runtime behaviour comes from
  reading the code and from the platform documentation. The statements that need a
  device are marked: the release logging behaviour, the cleartext policy (SEC-14) and
  the Miracast state (SEC-16).
* **No attacker simulation against a running receiver.** The attack paths were
  derived from the code and are exercised by unit tests that run the attack — not by
  a live penetration test.
* **The Google Cast SDK is a black box.** It is not part of the threat model beyond
  the fact that it exists.
* **The concurrent change in `airplay/OutputSizeResolver.kt`** was in the working tree
  while this document was written and does not compile (see the handover note). It is
  not part of the hardening of this cycle.
