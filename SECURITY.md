# Security Policy

PhairPlay is an AirPlay 2 / UPnP receiver that runs on the user's own
television and talks to **nothing except the local network**. This document
states what the project defends against, what it does *not* defend against,
which releases receive fixes, and how to report a problem.

**Last updated:** 2026-10-24
**Applies to:** PhairPlay 1.0.0-beta.3 — flavours `googletv` (Android TV) and
`firetv` (Amazon Fire TV)

---

## 1. The threat model in one paragraph

The attacker is **any device in the same Wi-Fi network**: a guest's phone, a
laptop, a compromised household appliance. PIN authentication is **off by
default**, so a sender does not have to prove anything before it may start a
session. Every byte that arrives over the network is therefore untrusted input:
RTSP headers and bodies, SDP, binary plists, H.264 SPS/PPS, AAC/ALAC payloads,
SSDP `M-SEARCH` requests, SOAP/XML bodies, GENA callback URLs, mDNS TXT records
and HTTP headers.

The receiver is a *display*. The damage an attacker can do is bounded by what a
display can be made to do: take over the screen, play sound, show a picture,
crash the app, make the app fetch a URL of the attacker's choosing, and — via a
user who pastes a log excerpt into a public issue tracker — publish the
household's network details.

**Out of scope:** attacks that require physical access to the television,
attacks from the internet (the application contains no internet code path), and
attacks on the Android platform itself.

The full table, surface by surface, is in
`docs/audit/2026-10-24-security-audit.md`.

## 2. Security architecture principles

| # | Principle | Where it lives |
|---|---|---|
| SEC-01 | **Every network input is untrusted.** Each parser bounds length, format and range before a value is used, and no index is read unchecked. | `airplay/RtspRequestReader`, `airplay/RtspHandler`, `airplay/SdpParser`, `airplay/H264SpsParser`, `airplay/handshake/PlistCodec`, `airplay/PhotoHandler` |
| SEC-02 | **No unbounded allocation driven by a remote peer.** Every read has a byte budget, not only a count. | RTSP message 1 MB, photo 25 MB, interleaved RTP frame 2 MB, mirror payload 8 MB, mirror queue 64 MB, SOAP body 64 KB, HTTP header 16 KB, HTTP line 8 KB |
| SEC-03 | **No XML external entities.** The SOAP reader disables doctypes, external entities and external DTD/Schema access. | `dlna/DlnaSoapDispatcher` (internal `SecureSoapXml`) |
| SEC-04 | **No request forgery out of the local network.** An outbound HTTP target must be a private IPv4 address in the local subnet. | `dlna/GenaEventManager.isAllowedCallback` |
| SEC-05 | **No secrets outside the app sandbox.** The Ed25519 identity and the paired-controller keys live in app-private storage, and `android:allowBackup="false"` keeps them out of the cloud backup. | `airplay/handshake/PairingKeys`, `airplay/handshake/PairingStore`, `AndroidManifest.xml` |
| SEC-06 | **No telemetry.** There is no analytics, no crash reporter and no code path that leaves the local network. | source-wide; proven in `PRIVACY.md` §2 |
| SEC-07 | **Data minimisation in the logs.** Log output is reduced centrally, so a new log statement cannot leak an address by accident. | `util/LogRedactor`, `util/Logger` |
| SEC-08 | **Minimal permissions**, and a permission that is declared but never used is recorded as a finding, not silently kept. | `AndroidManifest.xml`, `PRIVACY.md` §4, audit finding SEC-08 |
| SEC-09 | **One sender at a time.** A second RTSP client is answered with `503 Service Unavailable` and closed. | `airplay/RtspHandler.runServer` |

## 3. What this project does *not* protect against

Honesty about the limits is part of the policy.

* **An attacker in the LAN can take over the screen.** With PIN authentication
  off (the default), any device in the network may start a mirroring session.
  This is a deliberate trade-off — the product is "open by default" — and it is
  the reason PIN authentication exists. Turning it on moves the burden to the
  user, who must then enter a 4-digit code shown on the television.
* **The traffic is not encrypted.** AirPlay 2 media is encrypted with
  FairPlay/AES once the handshake succeeded, but the RTSP control plane, the
  mDNS announcements, the SSDP responses and the device description are plain
  text. A passive sniffer on the same Wi-Fi can read them. Encrypting the
  discovery protocols is not possible without breaking interoperability.
* **A hostile peer can crash the receiver.** Several parsers are hardened
  against resource exhaustion, but a bug that is found late is a bug that is
  found late. The receiver is a foreground service; a crash ends the session.
* **The television is not a secure enclave.** The attacker may be malware that
  is already running on another device of the same household, and it may have
  access to the router.
* **The app cannot verify the sender.** AirPlay identifies a sender by an
  anonymous key exchange, not by a certificate. Pinning a sender to a
  particular MAC address would break the protocol.

## 4. Supported versions

| Version | Supported | Notes |
|---|---|---|
| **1.0.0-beta.3** (current) | ✅ | `main` branch; receives security fixes |
| 1.0.0-beta.2 and older | ❌ | Upgrade — fixes are made in the current release only |

PhairPlay is pre-release software. The `main` branch is the release branch;
there is no long-term-support branch during the beta phase.

## 5. Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Use GitHub's private vulnerability reporting on this repository:
<https://github.com/mazer666/PhairPlay/security/advisories/new>

Please include:

* the affected version and commit,
* the smallest reproduction you have — a packet capture or a `curl`/`nc`
  transcript is ideal,
* what an attacker gains (screen takeover, crash, information disclosure, …),
* whether the reproduction contains data of yours. Replace real names,
  addresses and URLs with dummy values before you send it.

If private reporting is not available to you, open a public issue **without
exploit details** and ask for a private channel there.

**What you can expect.** The maintainer acknowledges a report and confirms
whether it is reproduced. There is no contractual response time during the
beta phase, and there is no bug bounty. If a fix is warranted, it lands in the
next release and is listed in the `Security` section of `CHANGELOG.md`.

## 6. Hardening guarantees

These are the properties the code enforces today, each with the place that
enforces it. They are the checklist a reviewer can verify.

1. **Bounded reads.** `RtspRequestReader` rejects a request line, a header
   block or a body above its budget *before* the body is allocated. The DLNA
   HTTP server bounds the single line as well as the total header block, and
   drains a rejected request so the error response still arrives.
2. **Bounded queues.** The mirroring frame queue is bounded by **bytes**
   (64 MB) and not only by item count (90) — the count alone allowed a peer to
   park ~720 MB in the process.
3. **Bounded arithmetic.** The H.264 SPS parser computes Exp-Golomb codes in
   64-bit arithmetic and rejects values above `0xFFFF`, so a crafted SPS
   cannot overflow into a bogus picture size. The parsed size is additionally
   range-checked before it reaches the decoder.
4. **Bounded XML.** The SOAP parser enables `FEATURE_SECURE_PROCESSING`,
   refuses doctype declarations, disables external general and parameter
   entities, denies external DTD and schema access, does not expand entity
   references, and rejects a body above 64 KB or nested deeper than 32 levels.
5. **Restricted outbound requests.** A GENA callback URL is accepted only if it
   is `http`, resolves to a private IPv4 address, and lies in the same /24 as
   the receiver. The media URI of `SetAVTransportURI` follows its own address
   rules: loopback, link-local (and with it the cloud metadata address
   `169.254.169.254`), multicast, broadcast, the reserved ranges and the
   non-standard notations of an IPv4 address are refused.
6. **Image payloads are sniffed, not trusted.** A photo must be a JPEG or a PNG
   by magic bytes, and a declared `Content-Type` that contradicts the payload
   is rejected.
7. **PIN pairing is rate limited.** The failed-attempt counter is persistent
   and blocks further pairing attempts after 10 failures; only a successful
   pairing clears it. The PIN itself is generated with `SecureRandom` and is
   never logged.
8. **Only one session at a time.** A second RTSP client is refused, so a peer
   cannot displace an ongoing session at will.
9. **Nothing is exported.** `MainActivity` is the only exported component
   besides the launcher intent; the service and the boot receiver are
   `exported="false"`.
10. **The logs are reduced.** `Logger` passes every message through
    `LogRedactor`, which replaces the host part of an IP address, the
    device-specific half of a MAC address, complete UUIDs and the credentials
    and query of a URL. The reduction is on by default in every build, and
    `Logger.setRedactionMode(LogRedactionMode.OFF)` turns it off for a local
    debugging session.
11. **No backup of the identity.** `android:allowBackup="false"` keeps the
    Ed25519 seed and the paired-controller keys out of the cloud backup.
12. **No telemetry code.** There is no analytics SDK, no crash reporter, no
    update check and no HTTP client that talks to a host outside the local
    network.

## 7. Deliberately accepted risks

| Risk | Why it is accepted |
|---|---|
| PIN authentication is off by default | The product promise is "one tap and it works". A PIN on every connection would break the primary use case. The setting exists and is documented. |
| The RTSP control plane is unencrypted | Required by the AirPlay protocol; the sender would not talk to an encrypted port. |
| The device name and the MAC address are broadcast on the LAN | Required for the sender to find the receiver. The receiver can be switched off completely in Settings. |
| The MAC address is used as the `deviceid` | Required by the protocol. It is never written to the disk and never logged. |
| A release build logs nothing at all | Chosen over shipping a logging backend that could leak. The cost is that a crash in the field cannot be diagnosed from a user's device; the owner collects logs from a debug build instead. |
| The Miracast (Wi-Fi Direct) path is currently non-functional on Android 10+ | The location / nearby-devices permissions are never requested at runtime. Fixing it means asking the user for a location permission, which the audit weighs against the feature's value (finding SEC-08). |

## 8. The source of this document

The security review of the current release, with every finding, its attack
path, a concrete fix and a negative test, is
`docs/audit/2026-10-24-security-audit.md`. The privacy counterpart is
`PRIVACY.md`.
