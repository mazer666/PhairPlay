# ADR-001: Multi-Protocol Support (AirPlay + Miracast + Cast)

**Date:** 2026-03-23
**Status:** Accepted — **amended 2026-09-06:** the Miracast part is superseded by ADR-004 (Miracast removed; a sideloaded app cannot be a Miracast sink).

---

## Context

PhairPlay v1.0 was scoped to AirPlay 2 only (macOS senders). User feedback indicated demand for Miracast (Windows/Android senders) and Google Cast (Chrome/Android senders). Supporting all three makes PhairPlay a universal wireless display receiver.

## Decision

Support all three protocols simultaneously:
- **AirPlay 2** — for macOS and future iOS senders
- **Miracast (WFD)** — for Windows 10+ and Android senders
- **Google Cast** — for Chrome, Android, and iOS senders

Each protocol is implemented as an independent component that can be enabled/disabled via Settings.

## Rationale

1. **User experience**: Users should not need to know which protocol their sender uses. PhairPlay simply works.
2. **Independence**: Protocols don't share network ports or state. One can fail without affecting others.
3. **Graceful degradation**: If a protocol is unavailable (e.g., Cast on Fire TV without GMS), it is hidden in the UI.

## Consequences

- Adds ~3 new package directories (`airplay/`, `miracast/`, `cast/`)
- Increases APK size by ~2-5 MB (Cast SDK dependency)
- Fire TV flavor must gracefully handle missing Google Play Services
- Miracast required `CHANGE_WIFI_STATE` and `ACCESS_FINE_LOCATION` permissions (Wi-Fi P2P) — removed with the receiver, see ADR-004

## Alternatives Considered

1. **AirPlay-only** — simpler, but limits audience to macOS users only.
2. **AirPlay + Miracast, no Cast** — reduces dependencies but misses Chrome users.
