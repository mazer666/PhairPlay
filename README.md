# PhairPlay

PhairPlay is a free, open-source, ad-free AirPlay 2 receiver for Android TV and Fire TV. It lets your macOS or iOS/iPadOS device mirror its screen and audio directly to your TV — no Apple TV required. A second protocol, **UPnP / DLNA**, turns the television into a MediaRenderer for Windows, VLC, BubbleUPnP and Samsung Smart View.

```
 macOS (Monterey+)            Android TV / Fire TV
 iOS / iPadOS (16+)           ┌──────────────────────┐
 ┌────────────────┐  AirPlay  │                      │
 │  [Your Screen] │ ────────► │  [Your TV Screen]    │
 │                │           │                      │
 └────────────────┘           └──────────────────────┘
      Click AirPlay →              PhairPlay
      Select your TV →             (this app)
      Done. ✓
```

---

## Current Status

**The latest published release is [`v1.0.0-alpha.2`](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-alpha.2) (2026-10-25)** — on top of the M6–M12 cycle it adds the **reconnect after a network loss** (the DLNA renderer becomes visible again), a **diagnostics screen with a self-test** that runs on the device, and the **language handling** (the app offers EN/DE/FR to the system, numbers and dates follow the language of the reader, the layouts are right-to-left capable). It ships **both platforms**: a Google TV APK (Android 10+) and a Fire TV APK (Android 7.1+). It is an **alpha**: it has not been tested on a television yet. The release plan — when alpha, beta and stable releases are built and what triggers them — is in [`docs/spec/PROJECT_PLAN.md`](docs/spec/PROJECT_PLAN.md#release-schedule).

The AirPlay 2 stack is implemented end-to-end: mDNS advertising (with standby auto-recovery and Wi-Fi multicast lock), RTSP handshake (up to 1 MB payload support for iOS 17+ setups), HomeKit-style pairing, FairPlay key decryption, H.264 mirroring, AAC-ELD/AAC-LC/ALAC audio, NTP A/V sync, keep-screen-on display management, and DACP reverse remote.

The **UPnP/DLNA MediaRenderer is implemented** — not planned. The package `com.phairplay.dlna` holds 28 production files: SSDP discovery, the device description server, the SOAP control plane (`ConnectionManager`, `AVTransport`, `RenderingControl`), GENA eventing and the Samsung Smart View extension. Miracast and Google Cast are still control-plane only.

> **Nothing in this README has been tested on a television.** No device test has ever been executed — 0 of the 15 cases of [`docs/guides/DEVICE_TEST_PLAN.md`](docs/guides/DEVICE_TEST_PLAN.md) have been run, because no television was available. Everything below is proven by the automated test suite and by reading the code. What that does **not** say is listed under [Known Limitations](#known-limitations).

**The quality gates are green** — measured on the current tree:

| Gate | Command | Result |
|---|---|---|
| JVM test suite | `./gradlew :test-runner:test` | 549 tests, 0 failures, 0 errors, 0 skipped (63 test classes) |
| Android lint | `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug` | `No issues found.` for both flavours |
| Debug APKs | `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug` | both build — Google TV 29.3 MB, Fire TV 16.3 MB |
| Repository checks | the four `scripts/check-*.sh` (see [Option B](#option-b-build-from-source)) | all four green — file size, KDoc headers, documentation links, language completeness |

The quality model that owns these gates is [`docs/quality/QUALITY.md`](docs/quality/QUALITY.md); the full list of what is still open is in [`docs/project/RELEASE_READINESS.md`](docs/project/RELEASE_READINESS.md).

**The security work of this cycle.** All three High findings of the [security audit](docs/audit/2026-10-24-security-audit.md) are closed:

- **SEC-01** — a sender can no longer make the television fetch an arbitrary URL. The AirPlay URL mode accepts only `http` and `https`, and the host has to pass an address policy (the receiver's own loopback, the link-local range including the router's cloud metadata service, the multicast and broadcast ranges and the obfuscated notations of an address are refused).
- **SEC-02** — a picture is decoded with a pixel budget, so a small file can no longer exhaust the memory of the television.
- **SEC-03** — the reassembled FU-A unit is capped at 8 MB, so a peer that opens a fragmented unit and never ends it cannot grow the buffer without bound.
- **SEC-04 is partly closed** — the *address* of the DLNA media URI is checked now (loopback, link-local, multicast, broadcast, the metadata addresses and the obfuscated notations of an address are refused). The *scheme* list is still open, see [What PhairPlay Does NOT Do](#what-phairplay-does-not-do).

On top of that the log output is reduced centrally (the host part of an IP address, the device-specific half of a MAC address, complete UUIDs and the credentials of a URL are replaced by placeholders), and the XML reader of the SOAP dispatcher refuses doctypes and external entities (XXE hardening).

## Features

### AirPlay 2 (fully implemented)
- Screen mirroring from macOS 12+ and iOS/iPadOS 16+ — H.264 hardware decode
- Keep screen awake during mirroring and audio playback (`FLAG_KEEP_SCREEN_ON`) — prevents TV screensaver or sleep
- Automatic mDNS recovery after TV wake from standby or Wi-Fi reconnect
- Wi-Fi Multicast Lock held during advertising for reliable device discovery
- Robust socket binding to IPv4 wildcard (`0.0.0.0`) across all Android TV / Fire TV models
- FairPlay session decryption (fp-setup v2/v3 + legacy rsaaeskey) via native libplayfair
- HomeKit-style pairing (Ed25519/X25519) and legacy SRP PIN pairing
- Mirroring audio: AAC-ELD, AAC-LC, ALAC — with independent A/V start/stop
- System audio streaming (ALAC, unencrypted) — reliable path for app audio
- AirPlay video URL mode (`/play` content) + transport controls (play/pause/scrub)
- Now-playing metadata (DMAP) with album artwork overlay
- DACP reverse remote — TV remote controls the sender's playback
- NTP timing and UDP audio retransmit (packet-loss recovery)
- AirPlay photo receiver — JPEG/PNG from iOS Photos app displayed full-screen
- Access-control lockout after repeated failed pairing attempts

### UPnP / DLNA MediaRenderer (implemented — control plane complete, media path open)

The television advertises itself on the local network as a UPnP MediaRenderer
(SSDP), serves its device description, and answers the three UPnP services
(`ConnectionManager`, `AVTransport`, `RenderingControl`) over SOAP. Playback
state changes are pushed back to the control point as GENA `LastChange` events
— they really arrive now: the delivery goes out over a raw socket, because
`HttpURLConnection` refuses the HTTP method `NOTIFY`.

How you reach it:

| Sender | Menu path |
|---|---|
| Windows 10 / 11 | Settings → Devices → Bluetooth & other devices → **Cast to device** → your TV |
| VLC 3.0+ | **Playback → Renderer** → your TV |
| BubbleUPnP (Android) | *Renderer* → your TV |
| Samsung Smart View (Galaxy phone/tablet) | the Smart View app on the phone → your TV |

The full walkthrough for every sender is in
[`docs/guides/CASTING_TO_A_TV.md`](docs/guides/CASTING_TO_A_TV.md).

**Samsung Smart View** is answered as well: PhairPlay answers the Samsung
search target `urn:samsung.com:service:MultiScreenService:1` and serves the
DIAL documents `/dial/` and `/dial/<appId>` that a Samsung TV asks for.
Whether your television then shows the entry in its Smart View list depends on
the TV model and on the Android / Smart View version — it can only be checked
on a real Samsung TV.

**What is still open:** that the television appears in the VLC, Windows or
BubbleUPnP list has never been confirmed on real hardware (no device test has
been run), and the media path — the part that actually pulls the video — has
never run on a device either.

### App & Platform
- Android TV / Fire TV app shell with foreground service and status UI
- Multi-protocol receiver architecture: AirPlay 2 and the UPnP/DLNA MediaRenderer implemented, Google Cast and Miracast in development (control plane only)
- Each protocol can be toggled on/off independently in Settings
- Works on Google TV (Android 10+) and Fire TV (Android 7+)
- Miracast Wi-Fi Direct / WFD advertisement and RTSP control-plane
- Google TV Cast Connect SDK lifecycle (full testing requires Cast app ID)
- Interface in English, German and French — 80 text keys, complete in all three languages (right-to-left layouts are not covered yet)
- Zero ads, zero analytics, zero internet required
- Open source — Apache 2.0 license

## What PhairPlay Does NOT Do

- **FairPlay DRM content** (Netflix, Disney+, Apple TV+) — Apple DRM; not decryptable by any open-source receiver
- **Apple Music in-app audio** — protected on every AirPlay path; use system audio output instead
- **Buffered audio playback** (AirPlay 2 type 103) — accepted but not played back yet
- **Cloud/remote streaming** — local network only
- **Miracast renders no media** — the Wi-Fi Direct advertisement and the RTSP control plane work, the MPEG-TS media path does not exist
- **Google Cast needs a registered app ID** — without one the SDK degrades gracefully and no Cast session can be tested end to end
- **A UPnP renderer does not receive the file — it fetches it itself.** A `file:///` URI points at the *sender's* disk, which the television cannot read. A local file therefore only works when it is reachable over the network (`http` / `https`); see [`docs/guides/CASTING_TO_A_TV.md`](docs/guides/CASTING_TO_A_TV.md)
- **The DLNA media URI accepts more than `http` / `https`.** `file`, `smb`, `ftp` and further schemes are still accepted on purpose, for compatibility with real control points. The *address* of the URI is checked (loopback, link-local, multicast, broadcast and the obfuscated notations of an address are refused); the *scheme* list is an open decision of the owner

---

## Requirements

**On your TV:**
- Google TV (Android 10+) or Amazon Fire TV (Android 7+)
- Connected to the same Wi-Fi network as your Mac
- Sideloading enabled (for Fire TV) or ADB enabled (for Google TV)

**On your Mac:**
- macOS 12 (Monterey) or later
- Connected to the same Wi-Fi network as your TV

**Network:**
- Both devices on the same subnet (common home router setup works)
- Multicast/mDNS must not be blocked (most home routers are fine)
- 5 GHz Wi-Fi or Ethernet strongly recommended for best performance

---

## Installation

### Option A: Download a Release APK (easiest)

Go to the [Releases page](https://github.com/mazer666/PhairPlay/releases) and download the APK for your device:

| APK | Device | Latest release |
|-----|--------|----------------|
| `PhairPlay-1.0.0-alpha.2-GoogleTV.apk` | Google TV, Android TV (Android 10+) | [`v1.0.0-alpha.2`](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-alpha.2) |
| `PhairPlay-1.0.0-alpha.2-FireTV.apk` | Amazon Fire TV (Android 7.1+) | [`v1.0.0-alpha.2`](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-alpha.2) |

Then install it via ADB (see the Sideloiding Guide below) or a sideloading app like *Downloader* on Fire TV.

**Want to cut the next release yourself?** The whole flow — quality gate, version
bump, build, signature check, tag, upload — is one command. See
[Cutting a release](docs/guides/RELEASING.md); it needs no Android Studio, only a
JDK, the Android SDK and `curl`.

> **The alpha is signed with a new release key.** If PhairPlay is already installed
> (for example from the Play Store), uninstall it first — Android refuses to replace
> an app that was signed with a different key:
> `adb uninstall com.phairplay.googletv` / `adb uninstall com.phairplay.firetv`

### Option B: Build from Source

1. **Install prerequisites**
   ```bash
   # Install Android Studio from https://developer.android.com/studio
   # Install JDK 17 or later
   ```

2. **Clone the repository**
   ```bash
   git clone https://github.com/mazer666/PhairPlay.git
   cd PhairPlay
   ```

3. **Build the APK**
   ```bash
   # For Google TV:
   ./gradlew assembleGoogletvDebug

   # Google TV with a registered Cast App ID:
   ./gradlew assembleGoogletvDebug -Pphairplay.castAppId=<APP_ID>

   # For Fire TV:
   ./gradlew assembleFiretvDebug
   ```
   The APK will be in `app/build/outputs/apk/`.

   To run the same local checks used by CI before testing on a TV:
   ```bash
   ./gradlew :test-runner:test
   ./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug \
     :app:assembleGoogletvDebug :app:assembleFiretvDebug
   ```

   Four repository checks run beside them. They need **no Gradle and no Android
   SDK** — only `bash` — and they are part of the CI quality gate, so a
   violation breaks the build instead of waiting for a review:
   ```bash
   bash scripts/check-file-size.sh          # no source file above 400 lines (hard limit 550)
   bash scripts/check-kdoc-headers.sh       # every Kotlin class has a KDoc header
   bash scripts/check-docs-links.sh         # every internal Markdown link resolves
   bash scripts/check-i18n-completeness.sh  # every text key exists in de and fr
   ```
   Each script has a `--help` and exits with `1` when it finds a violation.

4. **Install via ADB**
   ```bash
   # Enable ADB on your TV first (see below)
   adb connect <TV-IP-ADDRESS>

   # Google TV:
   adb install app/build/outputs/apk/googletv/debug/app-googletv-debug.apk

   # Fire TV:
   adb install app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
   ```

---

## Sideloading Guide

### Google TV (e.g., Chromecast with Google TV)

1. Go to **Settings → System → About → Android TV OS build** and click it 7 times to enable Developer Options.
2. Go to **Settings → System → Developer Options** and enable **USB debugging**.
3. Note your TV's IP address from **Settings → Network & Internet**.
4. On your Mac/PC, run:
   ```bash
   adb connect <TV-IP>
   adb install app-googletv-debug.apk
   ```
5. Launch PhairPlay from your app list.

### Fire TV (Fire TV Stick, Fire TV Cube, etc.)

1. Go to **Settings → My Fire TV → About** and click **Build** 7 times to enable Developer Options.
2. Go to **Settings → My Fire TV → Developer Options** and enable:
   - **ADB debugging** → ON
   - **Apps from Unknown Sources** → ON
3. Note your Fire TV's IP address from **Settings → My Fire TV → About → Network**.
4. On your Mac/PC, run:
   ```bash
   adb connect <FireTV-IP>
   adb install app-firetv-debug.apk
   ```
5. Launch PhairPlay from **Apps → Your Apps & Games**.

---

## How to Use

1. Launch PhairPlay on your TV. You will see the Waiting Screen with your TV's name.
2. On your Mac, click the **AirPlay** icon in the menu bar (or go to **System Preferences → Displays → AirPlay Display**).
3. Select your TV from the list (it should appear as your TV's name).
4. Your Mac's screen will appear on the TV instantly.
5. To stop: click the AirPlay icon on your Mac and select "Turn Off AirPlay Mirroring", or just quit PhairPlay on the TV.

**Mirroring while PhairPlay is in the background.** To have the video appear when a sender connects while you are on the TV home screen or in another app, allow PhairPlay to open itself: **Settings → Apps → Special app access → Display over other apps → PhairPlay**. If your TV hides that screen, grant it over ADB:

```bash
adb shell appops set com.phairplay.googletv SYSTEM_ALERT_WINDOW allow   # Fire TV: com.phairplay.firetv
```

Without it, start PhairPlay before you start mirroring; a session that begins in the background plays audio only.

### Mirroring over VLC or "Cast to Device" (UPnP / DLNA)

If your sender has no AirPlay, use the UPnP/DLNA MediaRenderer instead:

1. On the TV: open PhairPlay and switch on **DLNA** under **Settings → Protocols**.
2. On the sending device:
   - **VLC:** **Playback → Renderer** → pick your TV.
   - **Windows 10/11:** Settings → Devices → Bluetooth & other devices → **Cast to device** → pick your TV.
   - **BubbleUPnP** (Android): open the app, choose *Renderer* → pick your TV.
   - **Samsung Smart View** (Galaxy): swipe down → **Smart View** → pick your TV.
3. Play the file or the stream. The picture appears on the TV.

**Important:** a UPnP renderer fetches the media itself. A file that only exists
on the sending computer (`file:///…`) cannot be read by the television — the
file has to be reachable over the network, for example through
`python3 -m http.server 8080` on the sending computer. The details and the
workarounds are in [`docs/guides/CASTING_TO_A_TV.md`](docs/guides/CASTING_TO_A_TV.md).

---

## Known Limitations

- **Beta software** — the AirPlay 2 stack is complete but real-device validation with various macOS/iOS senders is ongoing. Please report issues.
- **No device test has ever been executed.** The 15 cases of [`docs/guides/DEVICE_TEST_PLAN.md`](docs/guides/DEVICE_TEST_PLAN.md) have never been run, because no television was available for this cycle. Every statement in this README comes from the automated test suite and from reading the code — none of it is confirmed on real hardware.
- **Apple Music in-app audio is not decryptable.** macOS protects it with FairPlay on every AirPlay path. Route the Mac's system audio output instead (works fine).
- **FairPlay-protected video** (Netflix, Disney+, Apple TV+) cannot be mirrored — this is Apple's DRM, not a PhairPlay limitation.
- **Buffered audio (AirPlay 2 type 103)** is accepted but not yet played back.
- **Google Cast** requires a registered Cast app ID for end-to-end testing; see [docs/guides/CAST_APP_ID.md](docs/guides/CAST_APP_ID.md).
- **Miracast** — Wi-Fi Direct and RTSP control plane work; MPEG-TS media decode is future work.
- **The DLNA renderer has never been seen in a picker.** Whether the television shows up in the VLC renderer list, in Windows "Cast to device" or in BubbleUPnP is unconfirmed — that needs a real television and a real control point (device test plan DT-08 / DT-09).
- **Samsung Smart View visibility is unconfirmed.** PhairPlay answers the Samsung discovery and serves the DIAL documents, but whether a Samsung TV shows the entry in its Smart View list depends on the TV model and on the Android / Smart View version. Only a real Samsung TV can answer that.
- **The DLNA media path over plain `http://` is probably blocked on API 35.** The app declares no `network_security_config.xml`, so the platform default refuses cleartext traffic. Whether a control point can push a video at all is therefore unproven.
- If your router has **AP isolation** or **multicast filtering** enabled, PhairPlay may not appear in the AirPlay menu. Disable these settings on your router.
- On very busy 2.4 GHz Wi-Fi networks, you may experience latency above 100 ms. Use 5 GHz or Ethernet for best results.
- **PIN auth is optional.** When disabled (default), any device on the same network can mirror to the TV. Enable PIN auth in Settings if you're on a shared network.

For real-device failures, run `tools/collect-device-logs.sh` before restarting the app. It captures package state, memory, CPU, and filtered PhairPlay logs into `device-test-logs/`.

---

## Documentation

The full documentation lives in [`docs/`](docs/README.md) — the index answers "which
document owns which question":

| You want to … | Read |
|---|---|
| understand how it works | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| install it on a television | [`docs/guides/INSTALLATION.md`](docs/guides/INSTALLATION.md) |
| get a picture onto the screen | [`docs/guides/CASTING_TO_A_TV.md`](docs/guides/CASTING_TO_A_TV.md) |
| fix a problem | [`docs/guides/TROUBLESHOOTING.md`](docs/guides/TROUBLESHOOTING.md) |
| look up a term | [`GLOSSARY.md`](GLOSSARY.md) |
| change the code | [`docs/WHERE_TO_CHANGE.md`](docs/WHERE_TO_CHANGE.md) and [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) |
| see what is missing before v1.0.0 | [`docs/project/RELEASE_READINESS.md`](docs/project/RELEASE_READINESS.md) and [`docs/project/RISKS.md`](docs/project/RISKS.md) |
| know how safe it is | [`SECURITY.md`](SECURITY.md), [`PRIVACY.md`](PRIVACY.md) and the [security audit](docs/audit/2026-10-24-security-audit.md) |

---

## Contributing

Contributions are welcome! Please read [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) before submitting a pull request.

Key points:
- Follow the coding rules in CONTRIBUTING.md (file size ≤400 lines soft / ≤550 lines hard max, class comments, test coverage)
- All PRs require passing CI (build + tests + lint)
- Discuss major changes in a GitHub Issue first

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

---

## Acknowledgments

- [openairplay/airplay-spec](https://github.com/openairplay/airplay-spec) — Community-maintained AirPlay protocol documentation
- [UxPlay](https://github.com/FDH2/UxPlay) — Open-source AirPlay mirror server (reference implementation)
- [RPiPlay](https://github.com/FD-/RPiPlay) — AirPlay mirroring for Raspberry Pi (reference implementation)
