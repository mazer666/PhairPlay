# ADR-005: DLNA MediaRenderer with a hand-rolled UPnP stack

**Date:** 2026-09-06
**Status:** Accepted

---

## Context

ADR-004 removed Miracast because a sideloaded app cannot be discovered as a Wi-Fi Display sink, and
pointed Windows senders at DLNA instead: Windows exposes UPnP/DLNA Digital Media Renderers as
"Cast to Device", and BubbleUPnP / VLC speak the same protocol.

A renderer needs SSDP discovery, a device/service description, SOAP control for AVTransport,
RenderingControl and ConnectionManager, and GENA eventing with `LastChange`. Two ways to get there:
a UPnP library (jUPnP / Cling) or writing the stack ourselves.

## Decision

Write the stack ourselves in `com.phairplay.dlna`, with every protocol layer as pure Kotlin that runs in the
JVM test suite, and only sockets, `MediaPlayer` and UI glue Android-specific. Playback uses Android
`MediaPlayer` (`DlnaPlayer`), a separate wrapper from the AirPlay URL player so the working AirPlay path is
untouched.

## Consequences

- No new dependency. jUPnP would add a large transitive graph, a Jetty-based transport that is awkward on
  Android TV, and hide the wire details Windows is picky about (`X_DLNADOC`, exact serviceIds, the SEQ 0
  initial event, the sink protocolInfo list).
- The HTTP port is a fixed constant (`DlnaConstants.DEFAULT_HTTP_PORT`, 49494) with an ephemeral fallback;
  the SSDP LOCATION always carries the port actually bound.
- A `WifiManager.MulticastLock` is held while the receiver runs (uses the existing
  `CHANGE_WIFI_MULTICAST_STATE` permission). No new permissions.
- Only formats `MediaPlayer` decodes are advertised (no raw LPCM, no WMA/WMV); Windows transcodes the rest
  or reports a file as unplayable. If real files fail, Media3 is the next step and a separate decision.
- Volume from the sender is applied to the DLNA stream, not the TV's system volume.
- A DLNA video is refused (fault 501) while an AirPlay session holds the Surface; audio and photos are not.
- Network input is hardened at the edges: 64 KB body cap, header sanitising, DOCTYPE/entity rejection and
  a nesting-depth limit on XML, GENA callbacks restricted to private IPv4 addresses.

## Alternatives considered

1. **jUPnP / Cling** — rejected for the dependency and transport reasons above.
2. **Generalise `AirPlayVideoPlayer` into a shared player now** — deferred; the AirPlay wrapper lacks volume,
   transport callbacks and audio-only mode, and has no tests. Extract a shared player only if Cast playback
   is ever built and there are two real users.

## References

- UPnP Device Architecture 1.1; AVTransport:1, RenderingControl:1, ConnectionManager:1 service templates
- DLNA Guidelines (DMR device class, `X_DLNADOC`, protocolInfo / DLNA.ORG_PN)
- Spec: `docs/superpowers/specs/2026-09-06-dlna-mediarenderer-design.md`
