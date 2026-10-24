# Security & Privacy Audit — PhairPlay 1.0.0-beta.3

**Date:** 2026-10-24
**Audited revision:** `main` @ `598eac6` (*release: prepare v1.0.0-beta.3*)
**Scope:** the AirPlay 2 stack, the Miracast stack, the DLNA/UPnP stack, the Google
Cast integration, the Android manifest, the local storage, and the log output.
**Method:** reading every network-facing parser and every store, cross-checking the
manifest against the code, and verifying the claims with unit tests where the
code is in scope.

This document is the single source of truth for the security state of the current
release. `SECURITY.md` is the short version for users, `PRIVACY.md` is the data
protection statement.

---

## 1. Summary

| Severity | Count | Findings |
|---|---|---|
| High | 3 | SEC-01, SEC-02, SEC-03 |
| Medium | 4 | SEC-04, SEC-05, SEC-06, SEC-07 |
| Low | 4 | SEC-08, SEC-09, SEC-10, SEC-11 |
| Info | 3 | SEC-12, SEC-13, SEC-14 |

**Fixed in this change:** SEC-05 is partially fixed (the network identifiers are
now reduced centrally), the manifest part of SEC-06 is fixed (the location
permissions are bounded to API 32 and the "never for location" flag is set), and
the unused `WifiManager` import that made the manifest comment wrong is gone.

**Not fixed in this change:** SEC-01 to SEC-04 and SEC-07 to SEC-11 touch files
that belong to other tracks (`app/src/main/kotlin/com/phairplay/airplay/`,
`app/src/main/kotlin/com/phairplay/dlna/`, `app/src/main/kotlin/com/phairplay/ui/`,
`app/src/main/kotlin/com/phairplay/service/`, `app/src/main/kotlin/com/phairplay/PhairPlayApp.kt`).
Each of them carries a concrete patch proposal and a negative test so the owning
track can apply it without re-deriving the analysis.

### 1.1 The one thing to know

**PIN authentication is off by default.** Every device in the same Wi-Fi may
start a mirroring session, take over the screen and play sound. Every finding
below is measured against that attacker: a device in the same network, with no
credentials, no user interaction and no physical access to the television.

---

## 2. Threat model

### 2.1 Assumptions

* The television is in the user's home Wi-Fi. The attacker is another device in
  that Wi-Fi: a guest's phone, a laptop, a compromised appliance.
* The attacker has no credentials, because PIN authentication is off by default.
* The attacker can send arbitrary bytes to every TCP and UDP port the receiver
  opens, and can send arbitrary multicast packets.
* The attacker cannot read the television's file system, cannot install
  anything, and cannot attack the Android platform itself.
* The television has no internet route in the threat model. The app never uses
  one, but a *sender* can ask it to fetch a URL (SEC-01, SEC-04).

### 2.2 Attack surface

| # | Surface | Attacker | Entry point | Damage potential | Existing defence | Gap | Action |
|---|---|---|---|---|---|---|---|
| S-01 | RTSP control plane (AirPlay) | Any LAN device | TCP 7000 | Screen takeover, sound, crash | One client at a time (`503` for the second), 1 MB message budget, header/body budget, method routing | **Arbitrary URL fetch** (SEC-01) | Fix in the AirPlay track |
| S-02 | RTSP binary plists | Any LAN device | `POST /pair-setup-pin`, `/feedback`, mirror `SETUP` on TCP 7000 | Crash, information disclosure | dd-plist 1.28 parses with external entities disabled (verified in the jar) | No size limit inside `PlistCodec` itself (SEC-11) | Add a size guard |
| S-03 | SDP (`ANNOUNCE`) | Any LAN device | TCP 7000 | Crash, bogus session parameters | `SdpParser` validates the fields it reads | none found | — |
| S-04 | H.264 SPS/PPS | Any LAN device | `SETUP` (config payload), RTP stream | Bogus resolution, decoder fault | Exp-Golomb in 64-bit arithmetic, values above `0xFFFF` rejected, size range-checked before the decoder | none found | — |
| S-05 | AAC/ALAC payloads, RTP interleaving | Any LAN device | TCP 7000 (interleaved), UDP 6001/6002 | Crash, memory exhaustion | 2 MB frame cap, AES-CTR keystream kept in order | **Unbounded FU-A reassembly** (SEC-03) | Cap the accumulator |
| S-06 | Photo endpoint | Any LAN device | `PUT /photo` on TCP 7000 | Memory exhaustion, crash | Magic-byte sniffing, 25 MB body cap | **Image decompression bomb** (SEC-02) | Cap the decoded pixel count |
| S-07 | Pairing / FairPlay | Any LAN device | `/pair-setup`, `/pair-verify`, `/fp-setup` | Session takeover | Ed25519 identity, SRP-6a with a persistent failed-attempt counter, 4-digit PIN by protocol | PIN space is small; the lockout is the actual defence (SEC-13) | Documented, accepted |
| S-08 | SSDP / `M-SEARCH` | Any LAN device | UDP 1900 multicast | Announcement spoofing, reflector | The responder only answers to `ST` it supports; no state is derived from the request | none found | — |
| S-09 | SOAP / XML (DLNA) | Any LAN device | HTTP on the description port | XXE, billion laughs, crash | `FEATURE_SECURE_PROCESSING`, doctype and external entities disabled, 64 KB body cap, 32-level depth cap | **Arbitrary URL fetch** via `SetAVTransportURI` (SEC-04) | Fix in the DLNA track |
| S-10 | GENA callbacks | Any LAN device | `SUBSCRIBE` on the description port | SSRF out of the LAN | Callback must be a private IPv4 in the same /24 | Resolve ≠ connect (SEC-09) | Connect to the validated address |
| S-11 | DLNA HTTP server | Any LAN device | The description port | DoS, buffer exhaustion | 16 KB header budget, 8 KB line limit, 64 KB body cap, 2 s socket timeout, bounded drain | none found | — |
| S-12 | mDNS / TXT records | Any LAN device | Multicast 5353 | Passive identification of the TV | Name sanitised, capped at 63 characters | The MAC and the UUID are broadcast in cleartext (SEC-12) | Protocol requirement, accepted |
| S-13 | DACP reverse control | Any LAN device | RTSP headers `DACP-ID` / `Active-Remote` | The `Active-Remote` token is echoed to a peer-chosen address | 2 s timeouts, `HttpURLConnection` | The token goes to a peer-supplied address | Note in the docs |
| S-14 | Cast SDK | Any LAN device | Cast discovery | Cast session | Google Cast SDK, `googletv` flavour only | The SDK is outside our control; it is a Google component | Documented |
| S-15 | Log output | The user (indirectly) | `adb logcat` / a bug report | Disclosure of the household's network | Central reduction of addresses, MACs, UUIDs and URL queries | Free-text names and track metadata (SEC-05) | Do not log them |
| S-16 | Local storage | An attacker with the device | — | Loss of the receiver identity | App-private storage, `allowBackup="false"` | none found | — |
| S-17 | Permissions | The user | The permission dialog | Excessive trust | Every permission traced to a feature | Declared but never requested (SEC-06) | Fixed in the manifest, gap documented |

---

## 3. Findings

### 3.1 High

---

#### SEC-01 — A device in the LAN can make the television fetch an arbitrary URL

**Severity:** High
**Location:** `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt:336` (`handleVideoPlay`)
→ `app/src/main/kotlin/com/phairplay/airplay/AirPlayVideoPlayer.kt:58` (`player.setDataSource(url)`)
**Track:** AirPlay (not owned by the audit track)

**Attacker scenario.** A laptop in the same Wi-Fi sends a single `POST /play`
request to the receiver's RTSP port. The television opens a connection to the
address the attacker chose.

**Attack path.**

1. The attacker connects to TCP 7000 — no credentials are needed, PIN
   authentication is off by default.
2. `POST /play` with the body `Content-Location: http://192.168.1.10:8080/secret`
   (`RtspHandler.kt:326` → `handleVideoPlay` → `parsePlayBody`, line 348).
3. `handleVideoPlay` passes the string to `onVideoPlay(url, start)` without
   looking at it (line 343).
4. `AirPlayVideoPlayer.play` calls `MediaPlayer.setDataSource(url)`
   (`AirPlayVideoPlayer.kt:58`), which opens the connection from the television.

**Damage potential.** The television becomes a request proxy inside the home
network: the attacker can make it connect to any host the TV can reach —
a router admin page, a NAS, a printer, a camera — and observe the result
through the media pipeline (a "file" that the player accepts is played back on
the screen, so a JPEG or an audio file fetched from an internal service is
shown on the television). `setDataSource` also accepts `file://` and local
paths, which turns the receiver into a file reader for everything the app's UID
can read. It also breaks the product promise: the app is supposed to talk to
the local network only, and this lets a *sender* decide that it talks to the
internet.

**Concrete fix** (AirPlay track — `RtspHandler.handleVideoPlay`):

```kotlin
/** Schemes the receiver is willing to fetch. A file:// or content:// URL would
 *  turn the television into a file reader for the app's own sandbox. */
private fun isFetchableMediaUrl(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https"
}

// in handleVideoPlay:
if (!isFetchableMediaUrl(url)) {
    Logger.w("POST /play rejected: unsupported URL scheme")
    return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
}
```

A stricter variant restricts the host to the local subnet
(`com.phairplay.util.NetworkUtils.isPrivateIpv4`), which matches the product
promise literally — but it breaks a legitimate use case: a media server in the
cloud. That is a product decision, not a security decision.

**Negative test** (proves the fix; belongs in
`app/src/test/kotlin/com/phairplay/airplay/RtspHandlerHandshakeTest.kt`):

```kotlin
@Test
fun `POST play rejects a file url`() {
    val response = handler.handlePlay("Content-Location: file:///data/data/com.phairplay/shared_prefs/phairplay_prefs.xml\r\n")
    assertEquals(400, response.statusCode)
    assertTrue(playRequests.isEmpty())   // the player was never asked to fetch anything
}
```

---

#### SEC-02 — An image decompression bomb kills the receiver

**Severity:** High
**Location:** `app/src/main/kotlin/com/phairplay/ui/PhotoScreen.kt:36`,
`app/src/main/kotlin/com/phairplay/ui/NowPlayingScreen.kt:88`
**Track:** UI (not owned by the audit track)

**Attacker scenario.** The attacker sends a picture that is small on the wire and
enormous when decoded. The television allocates gigabytes and is killed by the
system, or the app is killed by the `OutOfMemoryError`.

**Attack path.**

1. `PUT /photo` with a JPEG whose header announces 20 000 × 20 000 pixels.
   `PhotoHandler.validatePhoto` only checks the magic bytes and the size
   (`airplay/PhotoHandler.kt:14`), so the payload is accepted — a 20 000 × 20 000
   image compresses to well under the 25 MB limit.
2. `PhairPlayService` puts the bytes into `_photoFrame` (line 404).
3. `PhotoScreen.showPhoto` calls `BitmapFactory.decodeByteArray(bytes, 0, bytes.size)`
   **without** `inJustDecodeBounds` and **without** `inSampleSize`. The decoder
   allocates the full bitmap: 20 000 × 20 000 × 4 bytes = 1.6 GB.
4. The same happens for the album artwork: `NowPlayingScreen.update` decodes
   `info.artwork` the same way. The artwork body is bounded by the 1 MB RTSP
   message budget, and a 1 MB PNG can still declare 10 000 × 10 000 pixels
   (400 MB).

**Damage potential.** Remote denial of service: the mirroring session and the
receiver die, repeatedly, at the will of any LAN device. On a TV with little
RAM this is a one-shot kill.

**Concrete fix** (UI track — a new `com.phairplay.util.SafeBitmapDecoder`, so
both call sites share it):

```kotlin
object SafeBitmapDecoder {

    /** Longest edge of a decoded picture, in pixels. A TV screen is 4K at most. */
    private const val MAX_EDGE_PX = 4096

    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth > MAX_EDGE_PX || bounds.outHeight > MAX_EDGE_PX) return null

        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
```

and in `PhotoScreen.showPhoto`:

```kotlin
val bitmap = SafeBitmapDecoder.decode(bytes) ?: run {
    Logger.w("PhotoScreen: BitmapFactory rejected photo payload")
    return false
}
```

**Negative test** (UI track — `app/src/test/kotlin/com/phairplay/util/SafeBitmapDecoderTest.kt`):

```kotlin
@Test
fun `rejects a picture whose dimensions exceed the limit`() {
    val bomb = buildJpegHeader(width = 20_000, height = 20_000)
    assertNull(SafeBitmapDecoder.decode(bomb))
}
```

---

#### SEC-03 — The FU-A reassembly buffer has no upper bound

**Severity:** High
**Location:** `app/src/main/kotlin/com/phairplay/airplay/RtpInterleaved.kt:223`
(`handleFuaFragment`), accumulator created at line 247, appended at line 260
**Track:** AirPlay (not owned by the audit track)

**Attacker scenario.** The attacker opens a fragmented H.264 NAL unit and never
finishes it. The receiver keeps the fragments in memory until the process is
killed.

**Attack path.**

1. The attacker completes a normal pairing and starts a mirroring session, then
   switches to interleaved RTP on the RTSP connection.
2. It sends an FU-A packet with the start bit set (S=1) and a payload of up to
   2 MB (`MAX_RTP_FRAME_BYTES`, `RtpInterleaved.kt:69`).
3. It then sends FU-A continuation packets (S=0, E=0) forever.
4. `handleFuaFragment` appends every fragment to a `ByteArrayOutputStream`
   (line 260) that has **no size limit**. The 2 MB cap applies to a single
   frame, not to the accumulated NAL unit.

**Damage potential.** Remote denial of service. The accumulator grows until the
`OutOfMemoryError`; the process is killed and the session ends. The frame queue
in `MirrorStreamServer` has a 64 MB budget (fixed in beta.3), but this buffer
lives outside that queue and is not covered by it.

**Concrete fix** (AirPlay track — `RtpInterleaved.kt`):

```kotlin
/**
 * Maximum size of a reassembled FU-A NAL unit.
 *
 * A real IDR slice of a 4K frame is a few hundred kilobytes. 8 MB leaves a wide
 * margin and keeps the buffer bounded even when a peer never sends the end bit.
 */
private const val MAX_FUA_BYTES = 8 * 1024 * 1024
```

and in `handleFuaFragment`, before the append:

```kotlin
if (accumulator.size() + (rtpFrame.size - fragmentStart) > MAX_FUA_BYTES) {
    Logger.w("RtpInterleaved: FU-A exceeds ${MAX_FUA_BYTES} B — discarding the fragment")
    return null   // drop the incomplete NAL unit; the next FU-A start begins a new one
}
```

**Negative test** (AirPlay track — `app/src/test/kotlin/com/phairplay/airplay/RtpInterleavedTest.kt`):

```kotlin
@Test
fun `a FU-A stream that never ends does not grow without bound`() {
    val delivered = mutableListOf<ByteArray>()
    // 16 fragments of 1 MB each — more than the 8 MB budget
    repeat(16) { feedFuAFragment(start = it == 0, end = false, payload = ByteArray(1024 * 1024)) }
    assertTrue(delivered.isEmpty())          // nothing is handed to the decoder
    // and the accumulator was dropped, so the next start fragment works again
}
```

**Status as of 2026-10-24 (implemented by the AirPlay track):**
the reassembled FU-A NAL unit is bounded. `RtpInterleaved.MAX_FUA_BYTES` (8 MB) caps
the accumulator, and the check runs **before** the append, so the buffer never exceeds
the limit — not even for a moment. A fragment that would cross the cap is discarded
together with the incomplete unit, a warning with the two sizes is logged (never the
content), and nothing is handed to the decoder. Because the incomplete unit is dropped
as well, the next FU-A start fragment begins a clean unit again.

Tests: `RtpInterleavedTest` — `a FU-A stream that never ends does not grow without
bound`, `the incomplete FU-A unit is dropped when the limit is exceeded`, `a new FU-A
start fragment works again after the limit was exceeded`, `a normal FU-A stream is
reassembled completely` and `a large but legitimate FU-A unit below the limit is still
reassembled`.

One detail of the proposed test above had to be corrected: the interleaved frame header
carries a **16-bit** length field (RFC 2326 §10.12), so one RTP packet on the TCP
channel is at most 65 535 bytes — a 1 MB fragment cannot be sent that way. The
implementation therefore drives the limit with 129 fragments of the maximum size
(≈ 8.4 MB of traffic), which is the cheapest way for an attacker to reach the cap. For
the same reason `MAX_RTP_FRAME_BYTES` (2 MB) can never be exceeded on this path: the
effective frame limit is the 64 KB of the framing itself.

**Still open:** nothing that belongs to this finding. The buffer lives in the read loop
of one mirroring session, and the receiver accepts one session at a time, so the worst
case is 8 MB per session. A peer that keeps sending fragments after its unit was
discarded produces one warning per fragment — that is a logging nuisance, not a memory
one.

---

### 3.2 Medium

---

#### SEC-04 — A device in the LAN can make the television fetch an arbitrary URL (DLNA)

**Severity:** Medium
**Location:** `app/src/main/kotlin/com/phairplay/dlna/DlnaSoapDispatcher.kt:57`
(`SetAVTransportURI`) → `app/src/main/kotlin/com/phairplay/dlna/DlnaReceiver.kt:104`
(`onPlay`) → line 117 (`mediaPlayer.play(target)`)
**Track:** DLNA (owned by another track — **not modified by the audit track**)

Same mechanism as SEC-01, reached through UPnP instead of AirPlay: a control
point sends `SetAVTransportURI` with an arbitrary `CurrentURI`, and the
receiver fetches it with `MediaPlayer.setDataSource`. `CurrentURI` is stored
and returned verbatim by `GetPositionInfo` (line 98), so the value also travels
back out.

**Damage potential:** the same as SEC-01 — the television becomes a request
proxy inside the home network, and `file://` is not excluded.

**Concrete fix** (DLNA track): validate `CurrentURI` in `DlnaSoapDispatcher.avTransport`
before it is stored, with the same scheme allow-list as SEC-01
(`http`, `https`), and answer with a SOAP fault (`402 Invalid Args`) instead of
silently accepting it. A shared helper belongs in `com.phairplay.util`, e.g.
`MediaUrlPolicy.isFetchable(url)`, so both protocols apply the same rule.

**Negative test** (DLNA track — `app/src/test/kotlin/com/phairplay/dlna/DlnaSoapDispatcherTest.kt`):

```kotlin
@Test
fun `SetAVTransportURI rejects a file url`() {
    val result = dispatcher.dispatch("/upnp/control/AVTransport", "\"urn:...#SetAVTransportURI\"", bodyWithUri("file:///etc/passwd"))
    assertEquals(500, result.statusCode)   // a fault, not a 200
    assertTrue(result.body.contains("Invalid Args"))
}
```

**Status as of 2026-10-24 (implemented by the DLNA track, verified by the lead):**
the scheme allow-list and the address policy exist.
`DlnaMediaUri.validate` refuses a `CurrentURI` with an unknown scheme, with control
characters, that is too long, or whose address is not allowed. The address rules live in
`HostAddressPolicy`:

* loopback, the reserved range `0.0.0.0/8`, link-local (`169.254/16`, which contains
  the cloud metadata address `169.254.169.254`), multicast, broadcast and the reserved
  range `240.0.0.0/4` are refused,
* the cloud metadata addresses `100.100.100.200`, `192.0.0.192` and `fd00:ec2::254` and
  the known metadata host names are refused by name,
* the decimal, octal and hexadecimal notations of an IPv4 address are refused, so an
  address always means what it looks like,
* a URI with a user info part is refused, because it hides the real host.

`SetAVTransportURI` answers such a URI with the SOAP fault `402 Invalid Args`.
Tests: `HostAddressPolicyTest` (10 cases) and `DlnaMediaUriTest`.

**Still open:** a public address is accepted, because the control point decides which
media the owner wants to see and PhairPlay itself sends nothing. A *name* is not
resolved, because resolving it would send a DNS query from the television; a name that
points at a link-local address therefore stays a residual risk.

---

#### SEC-05 — Personal data is written to the log

**Severity:** Medium
**Location:**
* `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt:734` — the sender's name
* `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt:881` — title, artist and album of the playing track
* `app/src/main/kotlin/com/phairplay/dlna/DlnaReceiver.kt:64` — the device name
* `app/src/main/kotlin/com/phairplay/airplay/MdnsService.kt:155` and `:457` — the device name
* `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt:342` — the media URL

**Attacker scenario.** Not an attacker but the *user*: a problem report with a
log excerpt ends up in a public issue tracker. The excerpt then carries the
name of the household's television and what the user was listening to.

**Attack path.** The log statements above are unconditional. A pattern-based
reduction cannot recognise a name, so the central `LogRedactor` (added by this
audit) does not touch them.

**Damage potential.** Disclosure of personal data through a support channel. The
track title, the artist and the device name identify the person behind the
account; in a public issue they are world-readable and permanently archived by
the search engines.

**What this audit fixed.** The network identifiers are now reduced centrally:
`com.phairplay.util.LogRedactor` replaces the host part of an IP address, the
device-specific half of a MAC address, complete UUIDs and the credentials and
query string of a URL, in every build and for every message that goes through
`com.phairplay.util.Logger`. What remains are the free-text fields.

**Concrete fix** (AirPlay / DLNA track): stop logging free text that identifies
a person.

```kotlin
// RtspHandler.kt:734 — do not log the sender's device name
Logger.i("Session: hasVideo=${s.hasVideo} hasAudio=${s.hasAudio} " +
         "codec=${s.audioCodec} encrypted=${s.isAudioEncrypted}")

// RtspHandler.kt:881 — do not log what is playing
Logger.i("SET_PARAMETER now-playing metadata received")
```

If the values are needed for debugging, log a **truncated hash** instead
(`meta.title?.hashCode()`), which is stable enough to correlate two log lines
and useless to a reader. The same applies to the device name in
`MdnsService.kt:155` and `DlnaReceiver.kt:64`.

**Negative test** (AirPlay track): a test that plants a capturing Timber tree
(as `com.phairplay.util.LoggerTest` does) and asserts that the logged text does
not contain the title that was sent.

---

#### SEC-06 — The Miracast permissions are declared but never requested, so the feature cannot work

**Severity:** Medium
**Location:** `app/src/main/AndroidManifest.xml:36-38` (before this change),
`app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt:206` and `:254`
(`hasWifiP2pPermission`)
**Track:** manifest (fixed by this audit), Miracast (not owned by the audit track)

**Attacker scenario.** Not an attack — a functional and a privacy decision.

**The facts.**

* `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` and `NEARBY_WIFI_DEVICES` are
  declared in the manifest.
* The only runtime permission the app ever requests is `POST_NOTIFICATIONS`
  (`app/src/main/kotlin/com/phairplay/MainActivity.kt:355-370`). A
  `grep -rn "requestPermissions" app/src/main` returns exactly that one call.
* A declared permission is not a granted permission. Since Android 6 the
  location permissions are runtime permissions, so on Android 10 and later they
  are never granted.
* `hasWifiP2pPermission()` therefore returns `false` on every modern device,
  `registerP2pService()` bails out at line 206 and the Miracast state becomes
  `ERROR`. The Home screen briefly shows "Advertising" because
  `PhairPlayService.startMiracast()` sets that state before the receiver can
  report the error — which is exactly the "wrong state on the screen" class of
  bug the project already fixed once (see `docs/reviews/2026-10-24-pr19-pr17-issue18-review.md`).

**Damage potential.** The feature is dead on every device that matters, and the
naive fix — adding the location permission to the runtime request — would ask
the user for *precise location* in order to mirror a screen. That is the single
worst permission request in the whole manifest, for a feature that is
documented as unreliable anyway (ADR-001: "Miracast requires Wi-Fi Direct,
which some Android TV devices disable").

**What this audit fixed in the manifest.** The location permissions are now
capped at API 32, so a current television does not declare them at all, and
`NEARBY_WIFI_DEVICES` carries `android:usesPermissionFlags="neverForLocation"`
— the platform's own way of promising that the app never derives a location
from a Wi-Fi result. The shape now matches Google's Wi-Fi Direct documentation
(`developer.android.com/develop/connectivity/wifi/wifi-direct`). The
behaviour is unchanged (the permissions were never granted anyway), so this is
a reduction of the permission surface without a functional change.

**Concrete fix** (platform track — the decision belongs to the owner):

*Option A — make the feature work:* request `NEARBY_WIFI_DEVICES` at runtime on
API 33+ and `ACCESS_FINE_LOCATION` on API 29-32, **only when the user switches
Miracast on** and with a rationale screen. The app must then also declare
`ACCESS_COARSE_LOCATION` next to `ACCESS_FINE_LOCATION` — on Android 12+ the
system ignores a `FINE` request that is not accompanied by `COARSE`
(`developer.android.com/develop/sensors-and-location/location/permissions/runtime`).

*Option B — drop the feature:* remove the Miracast receiver, its permissions
and its card, and document that PhairPlay speaks AirPlay 2 and UPnP/DLNA. This
removes the location permissions from the manifest completely.

**Negative test** (platform track): an instrumented test that starts the
Miracast receiver and asserts that the state becomes `ADVERTISING` (option A) —
today it becomes `ERROR` on every device without the permission.

---

#### SEC-07 — A release build logs nothing, although the code claims it does

**Severity:** Medium
**Location:** `app/src/main/kotlin/com/phairplay/PhairPlayApp.kt:37-44`
**Track:** platform (not owned by the audit track)

**The facts.**

```kotlin
private fun initLogging() {
    if (BuildConfig.DEBUG) {
        Timber.plant(Timber.DebugTree())
    }
    // In release builds, we deliberately plant no tree to avoid
    // exposing debug information. Critical errors use android.util.Log directly.
}
```

The comment is wrong: `grep -rn "android.util.Log" app/src/main` finds no call
site at all. Timber without a tree is a no-op, so **a release build writes
nothing at all** — not a warning, not an error.

**Damage potential.** A crash in the field is undiagnosable. The owner has to
reproduce the failure with a debug build, which is a different binary from the
one the user is running. The statement in the comment also misleads every
future reader into believing that errors are visible in production.

**Concrete fix** (platform track): plant a release tree that writes only
warnings and errors, and let the central reduction apply:

```kotlin
private fun initLogging() {
    if (BuildConfig.DEBUG) {
        Timber.plant(Timber.DebugTree())
    } else {
        // A release build still reports failures — but only failures, and only after
        // the central reduction. The reduction (util/LogRedactor) is what makes it
        // safe to write a log line at all in a build that runs on a user's TV.
        Timber.plant(ReleaseTree())
    }
}

/** Writes warnings and errors to logcat; everything else is dropped. */
private class ReleaseTree : Timber.Tree() {
    override fun isLoggable(priority: Int): Boolean = priority >= android.util.Log.WARN
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        android.util.Log.println(priority, tag ?: "PhairPlay", message)
    }
}
```

**Negative test:** an instrumented test that plants the release tree, calls
`Logger.d("…")` and `Logger.e("…")`, and asserts that only the error reaches
the log — and that the error message contains the reduced form of an address.

---

### 3.3 Low

---

#### SEC-08 — Two files bypass the central log reduction

**Severity:** Low
**Location:** `app/src/main/kotlin/com/phairplay/MainActivity.kt:80, 92, 103, 145, 148, 308`
and `app/src/main/kotlin/com/phairplay/service/BootReceiver.kt:39, 48, 51, 54`
**Track:** platform / service (not owned by the audit track)

Ten call sites use `Timber` directly instead of `com.phairplay.util.Logger`.
None of them logs an identifier today, so the current exposure is nil — but the
guarantee "every log line is reduced" has a hole, and the next developer who
adds a `Timber.i("client $ip")` in one of these files would not notice.

**Concrete fix:** replace `Timber.` with `Logger.` in those two files, delete
the now-unused `import timber.log.Timber`, and add a lint rule or a review
checklist item that forbids a direct `Timber` call outside `util/Logger.kt`.

**Negative test:** a grep-based check in CI:

```bash
! grep -rn "Timber\." app/src/main --include=*.kt | grep -v "com/phairplay/util/Logger.kt"
```

---

#### SEC-09 — The GENA callback guard checks one address and connects to another

**Severity:** Low
**Location:** `app/src/main/kotlin/com/phairplay/dlna/GenaEventManager.kt:46`
(`isAllowedCallback`) versus `:68` (`URL(subscription.callbackUrl).openConnection()`)
**Track:** DLNA (not owned by the audit track)

`isAllowedCallback` resolves the host name and checks that the result is a
private IPv4 in the local /24. `sendNotify` then opens a **new** connection to
the same URL, which resolves the name again. A name whose second answer points
somewhere else bypasses the check.

**Damage potential.** Limited: the attacker has to be in the LAN already and
would have to win a DNS race. The result is a request from the television to
an address the guard rejected — an SSRF out of the local subnet.

**Concrete fix** (DLNA track): keep the resolved address and connect to it:

```kotlin
// store the validated address with the subscription
data class Subscription(val sid: String, val callbackUrl: String, val servicePath: String, val address: InetAddress)

// and connect to the validated address, keeping the host name in the Host header
val connection = URL("http://${subscription.address.hostAddress}:${subscription.port}/…")
```

**Negative test:** a test with a host name that resolves to a public address
must be rejected by `subscribe()`, and a test that changes the answer of the
resolver between the check and the call must still connect to the checked
address.

---

#### SEC-10 — Cleartext HTTP is blocked by the platform default

**Severity:** Low (functional, to be verified on a device)
**Location:** `app/src/main/kotlin/com/phairplay/dlna/GenaEventManager.kt:68`,
`app/src/main/kotlin/com/phairplay/airplay/DacpClient.kt:67`
**Track:** DLNA / AirPlay (not owned by the audit track)

The app declares no network security configuration. Since the app targets API
35, the platform default applies: **cleartext traffic is not permitted**. Both
outbound requests use `http://`:

* the GENA `NOTIFY` to the subscriber's callback,
* the DACP reverse-control command to the sender.

Both are wrapped in `runCatching { … }`, so a `Cleartext HTTP traffic to … not
permitted` exception is swallowed and logged as a warning. The feature simply
does not work, and the reason is invisible in the log.

**Damage potential.** No direct security damage — the traffic is refused. The
risk is that somebody "fixes" it by switching cleartext on globally, which
would also allow the media URL of SEC-01 and SEC-04 to leave the network.

**Concrete fix** (platform track): add a `res/xml/network_security_config.xml`
that permits cleartext **only** where it is needed, and document the choice:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- The receiver only ever talks to the local network. Cleartext is required for
         the DLNA/GENA and DACP control channels, which are plain HTTP by protocol. -->
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

The tighter alternative — a domain list of the local subnet — is not expressible
in a network security config, because the rules match host names, not CIDR
ranges. The real protection against an outbound request to the internet is the
URL allow-list of SEC-01 and SEC-04, not the cleartext setting.

*Status: not verified on a device in this environment — the statement follows
from the documented platform default (`targetSdk >= 28` ⇒ cleartext blocked)
and from the absence of a network security config in the repository. It must be
confirmed on a real television before the finding is closed.*

---

#### SEC-11 — `PlistCodec` has no size limit of its own

**Severity:** Low
**Location:** `app/src/main/kotlin/com/phairplay/airplay/handshake/PlistCodec.kt:37`
(`decode`)
**Track:** AirPlay (not owned by the audit track)

`PropertyListParser.parse(bytes)` parses whatever it is given. Today the
caller's budget protects it: the RTSP reader rejects a body above 1 MB before
`PlistCodec` is reached. The limit is therefore one layer away from the parser,
and a future call site that forgets the budget has no second line of defence.

**Damage potential.** Memory exhaustion of the receiver, or a deep-nesting
parser crash, if a call site ever hands an unbounded buffer to the decoder.

**Concrete fix** (AirPlay track):

```kotlin
fun decode(bytes: ByteArray): Map<String, Any?> {
    require(bytes.size <= MAX_PLIST_BYTES) { "plist too large: ${bytes.size}" }
    …
}

private const val MAX_PLIST_BYTES = 1024 * 1024
```

**Negative test:** `assertThrows(IllegalArgumentException::class.java) {
PlistCodec.decode(ByteArray(1024 * 1024 + 1)) }`

---

### 3.4 Info

---

#### SEC-12 — The MAC address and the persistent UUID are broadcast in cleartext

**Severity:** Info (accepted)
**Location:** `airplay/MdnsService.kt` (TXT records), `airplay/handshake/InfoResponder.kt`
(`deviceID`, `macAddress`, `pi`), `dlna/DlnaDescriptionBuilder.kt` (`UDN`)

The AirPlay protocol identifies the receiver by its MAC address and by a
persistent UUID, and both are sent in cleartext to every device in the network.
A passive sniffer can therefore tell which television is on the network and
track it over time. This cannot be changed while speaking AirPlay; it can only
be avoided by switching the receiver off.

**Accepted.** Documented in `PRIVACY.md` §2 and `SECURITY.md` §7.

---

#### SEC-13 — The AirPlay PIN has only 10 000 combinations

**Severity:** Info (accepted, mitigated)
**Location:** `airplay/RtspHandler.kt:997` (`PIN_SPACE`), `:999` (`MAX_PAIR_ATTEMPTS`),
`airplay/handshake/PairingStore.kt` (`recordFailedAttempt`)

The PIN is 4 digits because that is what the macOS input field accepts. The
actual defence is the lockout: the failed-attempt counter is persistent, and
after 10 failed attempts the pairing endpoints answer `470 Connection
Authorization Required` until a successful pairing clears the counter. The
counter is per receiver, not per sender, so an attacker who burns the ten
attempts locks the legitimate user out until they reset the app data.

**Accepted** for the beta phase; the residual risk (a denial of service against
the pairing) is preferable to an unlimited guessing oracle. A per-sender
lockout would be the next improvement.

---

#### SEC-14 — The XML plist parser of the AirPlay stack is already hardened

**Severity:** Info (positive finding)
**Location:** `dd-plist 1.28`, `com.dd.plist.XMLPropertyListParser`

`PlistCodec.decode` accepts XML plists, so the XXE question was checked
against the artefact itself. The static initialiser of
`XMLPropertyListParser` (verified with `javap -c` on
`dd-plist-1.28.jar`) sets `load-external-dtd = false`,
`external-general-entities = false`, `external-parameter-entities = false`,
`setXIncludeAware(false)`, `setExpandEntityReferences(false)` and installs an
entity resolver. An XXE payload is therefore not exploitable, and the
"billion laughs" expansion does not happen either, because entity references
are not expanded.

**No action.** Recorded so that a future upgrade of `dd-plist` re-checks this
property.

---

### 3.5 Cross-check: the DLNA stack

The DLNA stack belongs to another track and was **not modified** by this audit.
Its hardening was read and verified:

* `dlna/DlnaSoapDispatcher` — `FEATURE_SECURE_PROCESSING`, doctype declarations
  and external entities disabled, external DTD/Schema access denied, entity
  expansion off, 64 KB body cap, 32-level depth cap. Sound.
* `dlna/GenaEventManager.isAllowedCallback` — a callback must be `http`, must
  resolve to a private IPv4, and must lie in the same /24. Sound, with the
  TOCTOU caveat of SEC-09.
* `dlna/DlnaDescriptionServer` — 16 KB header budget, 8 KB per-line limit,
  64 KB body cap, 2 s socket timeout, bounded drain of a rejected request.
  Sound.
* `dlna/SsdpAdvertiser` — answers `M-SEARCH` only for the service types it
  actually implements and derives no state from the request. Sound.

The two findings that remain in that stack are SEC-04 and SEC-09, both marked
above as belonging to that track.

---

## 4. Permission audit

Every line of `app/src/main/AndroidManifest.xml`, with the evidence.

| Permission | Purpose | Needed? | Privacy relevant? | Recommendation |
|---|---|---|---|---|
| `INTERNET` | TCP/UDP sockets (AirPlay 7000, UDP 6001/6002, Miracast 7236, DLNA) | **Yes** — every socket needs it | No | Keep |
| `CHANGE_WIFI_MULTICAST_STATE` | The mDNS multicast lock (`airplay/MdnsService.kt:101`) | **Yes** — `createMulticastLock` throws without it | No | Keep |
| `ACCESS_WIFI_STATE` | Required by Wi-Fi Direct and by the Cast SDK | **Yes** — Google's Wi-Fi Direct documentation lists it | No | Keep. **The manifest comment was wrong**: the IP and MAC addresses are read with `java.net.NetworkInterface` (`util/NetworkUtils.kt:69`), which needs no permission. The comment and the unused `WifiManager` import were corrected. |
| `ACCESS_NETWORK_STATE` | `ConnectivityManager.registerNetworkCallback` (`service/PhairPlayService.kt:155`) | **Yes** | No | Keep |
| `FOREGROUND_SERVICE` | The persistent receiver service | **Yes** | No | Keep |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | The `connectedDevice` service type on API 34+ (`AndroidManifest.xml:121`) | **Yes** | No | Keep |
| `CHANGE_WIFI_STATE` | `WifiP2pManager` (Miracast) | **Yes** — Google's Wi-Fi Direct documentation lists it | No | Keep |
| `NEARBY_WIFI_DEVICES` | `WifiP2pManager` on API 33+ | **Yes** | No | Keep, and **add `android:usesPermissionFlags="neverForLocation"`** (done) — the app never derives a location from a Wi-Fi result |
| `ACCESS_FINE_LOCATION` | `WifiP2pManager` up to API 32 | **Yes, but only up to API 32** | **Yes** | **Capped at API 32** (done). Android 13+ replaced it with `NEARBY_WIFI_DEVICES` for Wi-Fi Direct |
| `ACCESS_COARSE_LOCATION` | Mandatory companion of `ACCESS_FINE_LOCATION` on Android 12+ | **Yes, as the companion** | **Yes** | **Capped at API 32** (done). It cannot be dropped: on Android 12+ the system ignores a `FINE` request that is not accompanied by `COARSE` (`developer.android.com/develop/sensors-and-location/location/permissions/runtime`) |
| `RECEIVE_BOOT_COMPLETED` | The optional "start on boot" (`service/BootReceiver.kt:32`) | **Yes** | No | Keep |
| `SYSTEM_ALERT_WINDOW` | Brings the streaming screen to the front (`service/PhairPlayService.kt:457`) | **Yes** | Low — a special app access, granted by the user, and used for nothing but the activity start | Keep. The app never draws an overlay |
| `POST_NOTIFICATIONS` | The foreground service notification | **Yes** | No | Keep; it is the only runtime permission the app requests |

**Nothing could be removed outright.** Every declared permission has a traced
use, and the two that looked superfluous (`ACCESS_COARSE_LOCATION` and the
"ACCESS_WIFI_STATE is used to read the IP and MAC" claim) turned out to be
required respectively by the Android 12+ location rule and by Wi-Fi Direct. What
*was* reduced is the location ask on current devices: with the API-32 cap, a
television running Android 13 or newer does not declare a location permission
at all.

**Explicitly not requested:** `RECORD_AUDIO`, `CAMERA`, `READ/WRITE_EXTERNAL_STORAGE`,
`READ_MEDIA_IMAGES`, `BLUETOOTH`, `BLUETOOTH_CONNECT`, `QUERY_ALL_PACKAGES`,
`POST_NOTIFICATIONS` is the only runtime request.

---

## 5. Data protection audit

### 5.1 What is stored

| Store | Content | Assessment |
|---|---|---|
| `datastore/phairplay_settings.preferences_pb` | Display name, four protocol switches, PIN-auth flag, start-on-boot, debug overlay, high-resolution flag, mirror-audio flag | Necessary, app-private |
| `shared_prefs/phairplay_prefs.xml` | `phairplay_device_uuid` (random), `pairing_ed25519_seed` (32-byte private key seed) | Necessary. The seed is the receiver's identity; it is in the app sandbox and `allowBackup="false"` keeps it out of the cloud backup |
| `shared_prefs/phairplay_pairings.xml` | `ltpk_<id>` public keys of paired controllers, `pair_failed_attempts` | Necessary. Public keys only, no secret |
| Media, photos, artwork | **Not stored** — memory only, for the duration of the display | Correct |

No file, cache or download directory is used. `grep -rn "File(\|openFileOutput\|filesDir\|cacheDir" app/src/main`
finds nothing.

### 5.2 What leaves the device

Only to the local network, and only the entries listed in `PRIVACY.md` §2.
There is no analytics SDK, no crash reporter, no update check and no HTTP
client that addresses a host outside the LAN. The only outbound HTTP calls are
the DACP command, the GENA notification and the media fetch of SEC-01/SEC-04 —
and the first two are addressed to a peer that the protocol itself designates.

### 5.3 The logs

* A release build writes nothing (SEC-07).
* A debug build writes to logcat, reduced centrally by `util/LogRedactor`.
* Free text (device name, sender name, track metadata) is still logged
  (SEC-05).

---

## 6. What this change implements

| File | Change | Test |
|---|---|---|
| `app/src/main/kotlin/com/phairplay/util/LogRedactor.kt` | New — the central reduction of IP addresses, MAC addresses, UUIDs, URL credentials and query strings, plus a sanitised copy of an exception | `LogRedactorTest` (19 cases) |
| `app/src/main/kotlin/com/phairplay/util/LogRedactionMode.kt` | New — `OFF` / `REDACT` | `LoggerTest` |
| `app/src/main/kotlin/com/phairplay/util/Logger.kt` | Every message now passes through the reduction; the exception is copied and sanitised; the mode is switchable | `LoggerTest` (6 cases) |
| `app/src/main/kotlin/com/phairplay/util/NetworkUtils.kt` | The unused `WifiManager` import is gone (it made the manifest comment wrong), the log goes through `Logger`, and the KDoc states that the MAC address is a device identifier that leaves the device in cleartext | covered by the existing `NetworkUtilsTest` |
| `app/src/main/AndroidManifest.xml` | The location permissions are capped at API 32, `NEARBY_WIFI_DEVICES` carries `neverForLocation`, the permission comment is corrected and names the evidence | manifest inspection |
| `app/src/main/kotlin/com/phairplay/util/HostAddressPolicy.kt` | New — the address rules for a URL the receiver fetches itself (SEC-04) | `HostAddressPolicyTest` (10 cases) |
| `app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt` | The address rules are applied to the `CurrentURI` of `SetAVTransportURI` | `DlnaMediaUriTest` |

---

## 7. Accepted residual risks

| Risk | Why it is accepted |
|---|---|
| PIN authentication is off by default | The product promise is "one tap and it works". The setting exists |
| The control plane is unencrypted | The AirPlay protocol does not allow it |
| The device name, the MAC address and the UUID are broadcast on the LAN | Required for the sender to find the receiver; the receiver can be switched off completely |
| The AirPlay PIN is 4 digits | The macOS input field accepts 4 digits; the persistent lockout is the real defence |
| A release build logs nothing | Chosen over shipping a backend that could leak. The cost is documented in SEC-07 |
| The `Active-Remote` token is echoed to a peer-supplied address | It is the DACP protocol; the peer that receives it is the peer that started the session |
| Miracast is non-functional on Android 10+ | The fix requires a location permission request, which is the most privacy-sensitive request in the manifest. The decision belongs to the owner (SEC-06) |

---

## 8. What could not be measured here

* **No device, no television.** Every statement about runtime behaviour comes
  from reading the code and from the platform documentation. The statements
  that need a device are marked as such: SEC-07 (does a release build really
  log nothing?), SEC-10 (is cleartext really refused?), SEC-06 (does the
  Miracast state really end up in `ERROR`?).
* **No attacker simulation against a running receiver.** The attack paths were
  derived from the code; the DoS findings (SEC-02, SEC-03) were not reproduced
  under memory pressure, because the sandbox has 3 GB of RAM and no Android
  runtime.
* **The Google Cast SDK is a black box.** Its manifest declares no permissions
  and it is not part of the threat model beyond that.
