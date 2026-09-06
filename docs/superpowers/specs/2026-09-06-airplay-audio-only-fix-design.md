# AirPlay Audio-Only (Music.app) Fix — Design

**Date:** 2026-09-06
**Status:** Closed 2026-09-06 — partially delivered (see Outcome)

## Outcome

- **Fixed:** the session drop (album-artwork `SET_PARAMETER` exceeded the 64 KB body limit — Fault 1), a
  native ALAC crash on wrong-key streams, and the FairPlay phase-2 header. `_raop._tcp` now carries
  the AirPlay 2 keys. Screen mirroring and macOS system-audio output keep working.
- **Also fixed (found during regression testing):** choppy macOS system-audio output — the realtime audio
  player now pre-rolls ~200 ms for audio-only streams, uses a doubled AudioTrack buffer and an
  urgent-audio playback thread; mirroring audio unchanged.
- **Not fixed:** Music.app audio-only stays silent. macOS Music uses AirPlay 1 + FairPlay **v2** key
  wrapping for receivers without buffered audio; v2 has never been reverse-engineered (H1 and H2 both
  tested and ruled out on-device). H3 (AirPlay 2 buffered audio) is the real fix and is deferred to
  its own spec. Alternative for later: a second audio-only AirPlay 1 speaker record using the RSA path.
- Full record: `docs/superpowers/plans/2026-09-06-airplay-audio-only-fix.md`, "Diagnosis result".
**Sub-project:** 1 of 3 (AirPlay audio-only fix → Miracast removal → DLNA MediaRenderer)

---

## Problem

Screen mirroring from macOS to PhairPlay v1.0.0-beta.1 works. Selecting PhairPlay as the
AirPlay output from Music.app (or iTunes) on the same Mac connects and then drops within
seconds. No audio is ever heard.

The previous developer concluded that Apple Music audio is "FairPlay-protected on every
reachable path and cannot be decrypted by any open-source receiver" (commit `abe0040`).
That conclusion is contradicted by Shairport Sync, whose classic (AirPlay 1) mode plays
Music.app output from current macOS using the RSA-wrapped key path (`rsaaeskey`) with no
FairPlay involvement. PhairPlay already implements that RSA path (`RaopRsa`, unit-tested)
and the ALAC decoder it needs — but never offers it to the sender.

## Evidence gathered so far (no device logs yet)

| Fact | Where |
|---|---|
| `_raop._tcp` TXT advertises `et=0,3,5` (none, FairPlay, FairPlay SAPv2.5). Type `1` (RSA) is absent. | `MdnsService.registerRaopService()` |
| `cn=0,1,2,3` advertises PCM, ALAC, AAC, AAC-ELD. | same |
| `rsaaeskey` → `RaopRsa` RSA-OAEP decrypt exists and is unit-tested. | `SdpParser.parseRsaAesKey`, `RaopRsaTest` |
| `fpaeskey` → FairPlay v2 unwrap exists; decode-health "mute guard" silences output when the key is wrong. | `SdpParser.parseFpAesKey`, `AudioPlayer.updateDecodeHealth` |
| AirPlay 2 buffered audio (type 103) is accepted but never decrypted or played. Features bits 40 (buffered audio) and 41 (PTP) are **not** advertised, so macOS should not choose it. | `BufferedAudioServer`, `InfoResponder.AIRPLAY_FEATURES = 0x1E5A7FFFF7` |
| Shairport Sync classic advertises `et=0,1` and plays Music.app audio on current macOS. | https://github.com/mikebrady/shairport-sync |

Because type 1 is not advertised, Music.app must choose FairPlay (type 3/5). If the FairPlay v2
unwrap yields a wrong key, the mute guard produces silence — but the user reports a *drop*,
not silence. The real failure point is therefore unknown until logs are captured. **No code
changes are made before Phase A completes.**

## Goals

1. Music.app / iTunes on macOS plays audio through PhairPlay on the user's Pi 4 LineageOS Android TV, for at
   least five minutes, with no session drop.
2. The Mac's volume slider changes TV volume; the now-playing overlay shows track metadata; the
   TV remote's play/pause reaches the Mac (all three already implemented for the audio-only
   session — they must keep working).
3. Documentation stops claiming Apple Music audio is undecryptable.

## Non-goals

- iOS / iPadOS audio-only senders (iOS 17+ requires AirPlay 2 buffered audio — see H3).
- AirPlay 2 buffered audio (type 103) playback, unless Phase B reaches H3, in which case it
  becomes a **separate spec** and this project stops.
- Any change to the video/mirroring path.
- Multi-room / grouped audio.

## Test environment

| Role | Device |
|---|---|
| Receiver | Raspberry Pi 4, LineageOS 23.2 Android TV (`lineage_rpi4_tv`, Android 16 / API 36, arm64-v8a), ADB at `192.168.1.185:5555`, googletv debug flavor |
| Sender | Mac on the same LAN, Music.app (and iTunes if installed) |
| Build host | This Mac: JDK 17, Android SDK at `~/Library/Android/sdk`, `adb` via Homebrew |

Build/install loop:

```bash
ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:assembleGoogletvDebug
adb -s 192.168.1.185:5555 install -r app/build/outputs/apk/googletv/debug/app-googletv-debug.apk
tools/collect-device-logs.sh
```

## Phase A — Diagnosis (no code changes)

**Purpose:** find the exact RTSP step at which the session ends and why.

1. Build and install the googletv debug APK from this checkout (not the beta.1 release APK, so
   debug-level protocol logging is present).
2. Start `adb logcat` filtered to the PhairPlay tags; clear the buffer; reproduce: on the Mac
   open Music.app, choose PhairPlay as AirPlay output, press play, wait for the drop.
3. Run `tools/collect-device-logs.sh` immediately after the drop.
4. If the TV-side log does not show *why* the sender tore down (e.g., the sender just closes the
   TCP connection), add a packet capture on the Mac side. `tcpdump` needs `sudo` — **ask the user
   before running it.** Filter: TCP port 7000 plus UDP 6000–6003 to/from the TV.
5. Classify the failure into exactly one of:

| Class | Signature in logs | Points at |
|---|---|---|
| A1 Discovery / capability | Sender never sends `ANNOUNCE`/`SETUP`; only `GET /info` or `OPTIONS` then close | mDNS TXT, `/info` features/statusFlags |
| A2 Classic RAOP handshake | `ANNOUNCE` arrives; `SETUP` or `RECORD` returns non-200 or sender closes right after | `RtspHandler` RAOP branch, `SdpParser`, ports |
| A3 Key / decrypt | `RECORD` succeeds; audio RTP flows; decode-health mutes; sender tears down after N seconds | FairPlay v2 unwrap (`fpaeskey`), `AudioPlayer` |
| A4 Timing | `RECORD` succeeds; NTP requests unanswered or malformed; sender tears down | `TimingHandler`, `AirPlayNtpClient` |

**Exit criterion:** a one-paragraph diagnosis with the log excerpt, written into the
implementation plan before Phase B begins.

## Phase B — Fix (hypothesis-driven, in order)

### H1 — Offer the RSA path (expected for A1/A3)

Change the `_raop._tcp` TXT record to advertise encryption types `0,1` (none, RSA), removing
`3` and `5` until the FairPlay audio unwrap is proven on-device. Music.app then sends
`a=rsaaeskey:` in the SDP, which the existing `RaopRsa` decrypts; ALAC frames go through the
existing `AlacDecoder` / `libalac`.

Design changes:

- Extract the RAOP TXT attributes into a pure function (e.g. `RaopTxtRecord.build(...)`
  returning `Map<String, String>`) so it can be unit-tested in the JVM test-runner.
  `MdnsService` (Android-only, excluded from the JVM runner) just applies the map.
- Keep `cn=0,1,2,3` unchanged.
- Add a unit test asserting `et` contains `1` and does not contain `3`/`5`, and that all
  required keys (`tp`, `vn`, `vs`, `am`, `cn`, `et`, `md`, `da`, `sv`) are present.
- If decrypting the RSA key fails, `SdpParser` must log at error level and the handler must
  return `RTSP 500` on `ANNOUNCE` so the sender shows an error rather than a silent stream.

On-device check: SDP contains `rsaaeskey`; `RECORD` succeeds; audio is heard; five-minute soak.

### H2 — Fix FairPlay v2 audio key unwrap (if H1 is insufficient or A3 shows FairPlay was
### chosen despite `et=0,1`)

Port the `fpaeskey` unwrap exactly as UxPlay's `fairplay_playfair.c` does it (the fp-setup
phase-2 state feeds `playfair_decrypt`). Add JVM unit tests using the FairPlay test vectors
shipped in RPiPlay. Only then re-add `et=3` to the TXT record.

### H3 — Real AirPlay 2 buffered audio (only if H1 and H2 both fail)

Encrypted control channel after pair-verify (ChaCha20-Poly1305), PTP timing, `shk` stream
key, ChaCha20 packet decryption, AAC-LC 44.1 kHz decode, anchor-time scheduling. **Stop and
return to the user; this becomes its own spec.**

### Documentation corrections (all hypotheses)

- README "What PhairPlay Does NOT Do" and "Known Limitations": remove the Apple Music
  undecryptable claim; state what actually works after the fix.
- `BufferedAudioServer` and `RtspHandler` doc-comments: remove "FairPlay-2-encrypted,
  undecryptable" wording; say "not implemented" instead.
- `CHANGELOG.md` `[Unreleased]` → `Fixed`.

## Error handling

- Wrong or missing audio key → error log + `RTSP 500` on `ANNOUNCE`; never a silent stream.
- Sender teardown mid-session → existing `TEARDOWN` path; receiver returns to `ADVERTISING`.
- Port already in use / mDNS registration failure → existing `ProtocolState.ERROR` path.

## Testing

| Layer | What | Where it runs |
|---|---|---|
| Unit (JVM) | RAOP TXT record builder; `RaopRsa` round-trip (existing); FairPlay v2 vectors (H2 only) | `./gradlew :test-runner:test` |
| Lint + build | both flavors | `:app:lintGoogletvDebug :app:lintFiretvDebug :app:assembleGoogletvDebug :app:assembleFiretvDebug` |
| On-device | Music.app 5-minute soak; volume; now-playing overlay; DACP play/pause; then re-verify **screen mirroring still works** (regression) | Pi 4 Android TV + Mac |

## Acceptance criteria

1. Music.app → PhairPlay plays for ≥ 5 min without drop on the user's Pi 4 LineageOS Android TV.
2. Volume slider, now-playing overlay, and TV-remote play/pause all work.
3. Screen mirroring (video + AAC-ELD audio) is unchanged.
4. JVM tests, both-flavor lint, and both debug APKs are green.
5. README/CHANGELOG/comments no longer claim Apple Music audio is undecryptable.

## Risks

| Risk | Mitigation |
|---|---|
| The drop is class A1/A4, not key-related; H1 alone won't fix it | Phase A classification gates Phase B; the plan is amended with the real cause before code changes |
| macOS 26 Music.app refuses AirPlay 1 (RSA) receivers | Verify with Shairport Sync classic on the Mac if available; otherwise H3 |
| Changing the TXT record breaks mirroring negotiation | Mirroring uses `_airplay._tcp`, not `_raop._tcp`; regression check is an acceptance criterion |

## References

- Shairport Sync (classic + AirPlay 2 reference): https://github.com/mikebrady/shairport-sync
- UxPlay (`lib/raop_handlers.h`, `lib/fairplay_playfair.c`): https://github.com/FDH2/UxPlay
- RPiPlay FairPlay test vectors: https://github.com/FD-/RPiPlay
- openairplay spec: https://openairplay.github.io/airplay-spec/
