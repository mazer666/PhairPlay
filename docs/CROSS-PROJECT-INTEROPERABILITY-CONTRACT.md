# Cross-project interoperability contract — PhairDLNA / PhairPlay / PhairPlayRust

Status: Draft cross-repository requirements; **not** evidence of working interoperability. Coordinated 2026-11-15.

## Projects and roles
- [PhairDLNA](https://github.com/mazer666/PhairDLNA): Rust, macOS-first **DLNA/UPnP MediaServer**, source of media library, metadata and HTTP playback; optional future protocol-neutral cast controller and AirPlay **sender**.
- [PhairPlay](https://github.com/mazer666/PhairPlay): Android TV / Fire TV **AirPlay receiver** and DLNA **MediaRenderer** implemented in Kotlin/native modules; Apache-2.0 top-level license, audit embedded third-party code separately.
- [PhairPlayRust](https://github.com/mazer666/PhairPlayRust): Rust-first multi-platform **receiver** with modular AirPlay/DLNA/other engines, Rust core, native shells and UniFFI bridge; AGPL-3.0-or-later. Its DLNA engine is a MediaRenderer, **not** a MediaServer.

## Mandatory outcomes
XINT-001: PhairDLNA shall discover compatible PhairPlay and PhairPlayRust DLNA renderer instances on permitted LAN interfaces without hardcoded device identifiers; both receiver projects must expose correct discoverable, versioned service descriptors.
XINT-002: Provide standard UPnP AV interoperability: SSDP -> descriptions -> ConnectionManager compatibility -> AVTransport SetAVTransportURI -> Play/Pause/Seek/Stop where supported; discover and document unsupported actions. A generic third-party control point must also work: no proprietary coupling required for basic DLNA.
XINT-003: ContentDirectory, DIDL-Lite, protocolInfo, MIME types, resource URLs, HTTP GET/HEAD/Range, metadata and subtitles must be correct for the client's actual capabilities. Renderer and server roles must not be conflated.
XINT-004: A protocol-neutral optional cast-controller interface may request playback to an explicitly selected target; feature OFF means no extra background processes or outbound discovery. Simple and Advanced share a single config model.
XINT-005: Coexistence of all three apps on one LAN must not cause SSDP/mDNS collision, duplicate identities, false renderer/server advertisements, endless rediscovery, port conflicts, advert storms or unstable client lists. Stable distinct UUID/service identities and explicit listener binding; do not auto-expose WAN.
XINT-006: A receiver restart, sleep/wake, Wi-Fi/Ethernet switch, NAS loss, sender disconnect or IP change yields bounded recovery, accurate status, no resource leak and no destructive alteration of media originals.
XINT-007: Protocol and feature negotiation is based on advertised AND tested capabilities; support unknown/unsupported states and safe fallbacks. No claim that AirPlay sender and receiver interoperate until a genuine sender module is built and tested.
XINT-008: Zero forced account, cloud, telemetry or external lookup. Any cross-app optional integration must be consented to, disconnectable, privacy-preserving and not weaken the standalone operation of any project.
XINT-009: If a richer cross-app control API is later justified, it must be independently optional, versioned, backward compatible within published range, access-controlled, validated, local-only by default and independently security-reviewed. DLNA interoperability cannot depend on it.
XINT-010: Preserve project independence: no runtime hard dependency among three applications, no forced shared database or installation, no license-unsafe source copying. Review Apache-2.0 -> AGPLv3 compatibility, attribution and every bundled third-party/native component.
XINT-011: Every cross-project change needs coordinated owner review, changelog/contract update, version matrix, automated protocol fixtures and negative/security tests in affected repositories.
XINT-012: Performance: optional integration must not slow core streams or produce unbounded CPU/RAM/network/FD growth. Measure baseline with integration OFF and ON; release thresholds defined on common hardware before approval.

## Acceptance matrix (initial; all NOT RUN)
- X-A01 Discovery: 30 repeats, all intended servers/renderers identified by different UUID, p95 discovery time measured, unrelated devices unaffected.
- X-A02 End-to-end: PhairDLNA -> generic UPnP control point -> PhairPlay renderer; same to PhairPlayRust. Select a media item, play, pause, seek, stop; log supported/unsupported operations explicitly.
- X-A03 Media: audio, video, image, metadata/artwork, subtitle support where advertised; test HTTP Range and track/codec mismatches.
- X-A04 Coexistence: all three simultaneously, multiple network interfaces, at least 10 discovery cycles, no port/address collision and no multicast storm.
- X-A05 Fault injection: abruptly restart each process, disconnect client, rotate IP, sleep/wake, disconnect media root mid-stream; verify bounded recovery and truthful state.
- X-A06 Security: malicious SOAP/SSDP/XML, unsafe URL redirection/SSRF, unauthorized callback, large metadata and path traversal; reject safely, prevent internet exposure.
- X-A07 Resources: multiple streams + scans + discovery, compare CPU/RSS/latency with integration disabled and enabled using documented environment.
- X-A08 Real devices: both PhairPlay and PhairPlayRust receiver implementations on actual supported TV/host hardware plus independent third-party renderer and control point. Simulator/unit tests are not substitutes.
- X-A09 AirPlay future: genuine AirPlay sender to PhairPlay/PhairPlayRust receiver, handshake/auth/playback/reconnect/stop tested on physical endpoints **only after sender approved and implemented**.

For each case: version, git commit, OS/firmware, hardware, network, fixtures, expected/observed, logs, status (NOT RUN / PASS / FAIL / BLOCKED). Never mark tests green just because modules exist.

## Architecture guardrails
- Transport-neutral media/casting interfaces; DLNA server, DLNA renderer, AirPlay receiver and AirPlay sender remain distinct protocol roles.
- Do not wire PhairDLNA directly to PhairPlayRust's internal UniFFI or proprietary engine state; protocol boundaries and optional documented API only.
- Reuse test vectors, threat mitigations and documented behavior when compatible; license/provenance review precedes source-porting.
- Avoid duplicated network discovery logic and hard-coded compatibility overrides. Any device-specific workaround must carry provenance, user override and regression fixture.
- Security and streaming performance outrank feature proliferation.

## Agent responsibilities
Inspect live source and real tests in all three repositories. Locate actual service descriptors, transport methods, metadata/media support, headers/range behavior, identity/port binding, security policies and versioning. Trace each XINT requirement to implementation and evidence; identify gaps with concrete file paths and recommended changes. Coordinate proposals across repositories; use independent QA/security/architecture reviews. Do **not** start protocol feature implementation in PhairDLNA before its requirements baseline approval; no releases/tags and manual-only GitHub Actions.
