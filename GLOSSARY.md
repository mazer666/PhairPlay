# PhairPlay Glossary

Every term you will meet in this repository, explained in plain language.

**How to read this file:** each entry says *what the term is*, *why it exists*,
and *where you meet it in PhairPlay*. If you only need one word, read the
first sentence. If you are about to change code, read the whole entry.

Terms are grouped by what they are about:

1. [The protocols we speak](#1-the-protocols-we-speak)
2. [The formats and codecs we handle](#2-the-formats-and-codecs-we-handle)
3. [The networking words](#3-the-networking-words)
4. [Android and the build](#4-android-and-the-build)
5. [Working on PhairPlay](#5-working-on-phairplay)

---

## 1. The protocols we speak

### AirPlay

Apple's protocol for sending picture and sound from a Mac, an iPhone or an iPad
to a receiver. AirPlay 2 added the ability to mirror a whole screen (not just
play a file) and to play audio without a picture.

PhairPlay is an **AirPlay 2 receiver**: it makes the television appear in the
AirPlay menu of a Mac or an iPhone. The stack is split into several pieces —
the *discovery* (mDNS), the *handshake* (RTSP), the *pairing*, the *FairPlay*
key exchange and the *media streams* (RTP). See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the whole flow.

**What it does not include:** AirPlay DRM. FairPlay-protected video (Netflix,
Disney+, Apple TV+) cannot be mirrored by any open-source receiver.

### RAOP

**Remote Audio Output Protocol.** The older, audio-only part of AirPlay, before
Apple unified everything under the AirPlay 2 RTSP handshake. RAOP is still
alive in two ways: it has its **own mDNS service type** (`_raop._tcp`), so
PhairPlay advertises it next to `_airplay._tcp`; and it is the origin of the
**ALAC** audio format and of the RTSP-based audio path.

You will see the term in the mDNS registration and in the audio handling. When
a doc says "RAOP audio", it means "the Apple audio stream", not a separate
application.

### FairPlay

Apple's **digital rights management** system. Before a stream is sent, Apple
devices encrypt it; the receiver has to ask Apple's servers for the key.

PhairPlay implements the **key exchange** part of FairPlay — `fp-setup` v2/v3
and the legacy `rsaaeskey` path — so that the *session key* for an ordinary
AirPlay stream can be recovered. What it deliberately does **not** implement is
the DRM part: Netflix, Disney+ and Apple TV+ content stays encrypted and
cannot be played. That is Apple's licensing decision, not a missing feature in
this repository. The evaluation is written down in
[`docs/spec/REQUIREMENTS.md`](docs/spec/REQUIREMENTS.md) §3.

The native code lives in `app/src/main/cpp/` and is reached through
`app/src/main/cpp/fairplay_jni.c`.

### WFD

**Wi-Fi Display.** The protocol behind **Miracast**: Windows "Cast to
device", many Android phones and some Samsung TVs send their screen using
WFD. The negotiation happens over **RTSP-WFD** — a slightly different RTSP
dialect than AirPlay's.

PhairPlay implements the **advertisement** (Wi-Fi Direct / P2P) and the
**control plane** (the RTSP handshake), but the **media decode is not
implemented**. On top of that there is a platform problem that no amount of
code fixes: `WifiP2pManager.setWfdInfo()` is a hidden Android system API, so a
sideloaded app cannot act as a WFD sink on a normal Android TV or Fire TV.
See ADR-001 and finding SEC-06 in the security audit.

### SSDP

**Simple Service Discovery Protocol.** How UPnP devices find each other: the
device sends a UDP message to the multicast address `239.255.255.250:1900` and
says "I am a MediaRenderer". A controller that wants to know what is available
sends an `M-SEARCH` question to the same address.

In PhairPlay it lives in `app/src/main/kotlin/com/phairplay/dlna/` —
`SsdpAdvertiser` (the announcements), `SsdpResponder` (the answers to
`M-SEARCH`) and `SsdpMessages` (the text of the messages).

Since the Samsung Smart View work, the responder answers **two** search
targets: the UPnP one and `urn:samsung.com:service:MultiScreenService:1`
(`SsdpMessages.SAMSUNG_MULTI_SCREEN`). A Samsung television does not ask for
a UPnP renderer — it asks for a MultiScreen service, and only then reads the
DIAL documents.

### GENA

**General Event Notification Architecture.** The UPnP mechanism for *push*:
a controller subscribes with `SUBSCRIBE`, and the renderer then sends it a
`NOTIFY` whenever something changes — in our case the `LastChange` event with
the new transport state.

The security-relevant part: a subscription contains a **callback URL**, and
the renderer will later send data to it. If that URL is not checked, the
television can be talked into contacting the internet. PhairPlay therefore
restricts callbacks to private IPv4 addresses in the local subnet — see
`GenaCallbackPolicy` and finding SEC-09.

**The event goes out over a raw socket.** `HttpURLConnection` refuses the HTTP
method `NOTIFY` (it only allows GET, POST, HEAD, OPTIONS, PUT, DELETE and
TRACE), so the `LastChange` event would never have reached the control point.
`GenaEventManager` therefore writes the HTTP request into a `Socket` itself.

### SOAP

**Simple Object Access Protocol.** How a UPnP controller *commands* a device:
it sends an HTTP `POST` with an XML body in a fixed shape, and the device
answers with another XML body — or with a **SOAP Fault** describing why it
refused.

PhairPlay's SOAP dispatcher (`DlnaSoapDispatcher`) implements the actions of
`ConnectionManager:1`, `AVTransport:1` and `RenderingControl:1`. Because the
body is XML that arrives from the network, the parser is hardened: doctypes
and external entities are disabled (`SecureSoapXml`).

### UPnP

**Universal Plug and Play.** A family of specifications for letting devices on
a home network find each other and control each other, without any setup and
without a server in the middle. UPnP is the *framework*; SSDP is how devices
find each other, SOAP is how they are commanded, and GENA is how they push
events.

PhairPlay implements UPnP as a **MediaRenderer** — a device that plays media
that a controller points it at.

### DLNA

**Digital Living Network Alliance.** A profile *on top of* UPnP that defines
which media formats a renderer must support and how a controller should talk
to it. When people say "DLNA", they usually mean "UPnP AV / MediaRenderer".

For PhairPlay, DLNA is the second protocol next to AirPlay 2: it is what makes
the television appear in **Windows "Cast to device"**, in **VLC** ("Playback →
Renderer") and in mobile controllers such as BubbleUPnP. It is an *additional,
independently switchable* option — it does not replace Miracast or Cast
(ADR-001, ADR-004).

> **The one limitation to know:** a UPnP renderer **fetches the media
> itself**. A `file:///` URI points at the *sender's* disk, and the television
> cannot read that disk. Rendering a local file therefore works only for
> network-reachable `http` / `https` URLs. This is a property of the
> protocol, not a bug.

### DIAL

**Discovery And Launch.** The small half of the **Samsung Smart View**
discovery: a Samsung television does not look for a UPnP renderer, it looks for
a *MultiScreen* service and then asks it which apps it offers.

The dialogue has three steps: the television sends the SSDP search
`urn:samsung.com:service:MultiScreenService:1`, it reads the
`Application-Url` header of the device description, and it asks that URL for
two XML documents — `GET /dial/` (the app list) and `GET /dial/<AppId>` (the
details of one app).

**Why it is important here:** without the DIAL answer the television finds
PhairPlay and then shows an **empty app list** — the user never sees the
entry and cannot pick it. The implementation is in
`app/src/main/kotlin/com/phairplay/dlna/DialAppCatalog.kt`; the renderer
offers exactly one app, itself, so the answers are static documents and the
app id from the request is only compared, never used as a file name or as part
of a URL.

### RTSP

**Real Time Streaming Protocol.** The text-based control protocol that the
AirPlay handshake uses. The sender sends commands like `OPTIONS`, `SETUP`,
`ANNOUNCE`, `RECORD` and `TEARDOWN`, the receiver answers with status codes
(`200 OK`, `503 Service Unavailable`, …).

In AirPlay, after `RECORD` the *same* TCP connection switches from text to
binary RTP frames ("interleaved" framing, marked with a `$` byte). That
switch is handled in `RtpInterleaved`.

### SDP

**Session Description Protocol.** A small text format in which the sender
describes *what* it is going to send: the video codec and its parameters, the
audio codec, the encryption key and the initialisation vector, the channels
and the sample rate. It arrives in the body of the RTSP `ANNOUNCE` request.

PhairPlay parses it in `app/src/main/kotlin/com/phairplay/airplay/SdpParser.kt`.
Everything in it is untrusted input from the network and is validated before
use.

### RTP

**Real-time Transport Protocol.** The format in which the actual picture and
sound travel: small packets, each with a header (a timestamp, a sequence
number, a payload type) and a bit of media.

In AirPlay, the video RTP packets arrive interleaved on the RTSP TCP
connection; the audio packets usually arrive over their own UDP socket. RTP
has no delivery guarantee — a lost packet is simply lost — which is why the
audio path has its own retransmission mechanism.

### RTCP

**RTP Control Protocol.** The companion of RTP: it carries the feedback
between the endpoints — how many packets arrived, how much jitter there is,
how fast the two ends think the network is.

**PhairPlay does not implement RTCP.** The interleaved frame reader ignores
the RTCP channel, and the audio clock synchronisation that macOS performs with
RTCP is documented in `AppSettings` as not fully implemented — which is the
reason the mirroring-audio switch exists and why the default behaviour was
chosen conservatively.

### NTP

**Network Time Protocol.** The protocol by which two computers agree on the
time. Its timestamp is a fixed-point number: 32 bits for the seconds since
1900 and 32 bits for the fraction, which gives about 230 years and a
resolution far below a millisecond.

PhairPlay uses NTP to keep picture and sound together: the sender tells the
receiver "this frame belongs to this moment", and the receiver schedules the
decode and the playback accordingly. The A/V drift budget is **40 ms**
(FR-35). See `AirPlayNtpClient` and `TimingHandler`.

### EDID

**Extended Display Identification Data.** The small block of information a
display sends about itself: its native resolution, its refresh rate, its
aspect ratio, its colour capabilities. A TV reads it over HDMI and uses it to
decide what mode to switch to.

PhairPlay does **not** read EDID: it runs on the television itself, so the
television already knows its own capabilities. The term appears in this
glossary because it comes up in every discussion about "what resolution can
the TV show" — and the answer there is the *display's* answer, not the
receiver's.

### HDCP

**High-bandwidth Digital Content Protection.** The copy-protection scheme for
HDMI and DisplayPort. It requires the source and the display to authenticate
each other; if they cannot, the display shows a black screen rather than the
protected picture.

HDCP is enforced in **hardware**, not in software. A receiver does not
"implement" HDCP — it either gets a protected signal from a source that
already negotiated it, or it gets nothing. PhairPlay therefore does not
implement HDCP either. This matters for Miracast: the WFD negotiation
mentions HDCP 2.x, but on an Android TV the sender usually falls back to an
unprotected stream.

---

## 2. The formats and codecs we handle

### H.264 / AVC

The video format that almost everything uses: web video, phone video, camera
video, and AirPlay screen mirroring. Also called **AVC** or **MPEG-4 Part 10**.

It comes in profiles (Baseline, Main, High) and levels (a number that limits
the resolution and the bitrate). PhairPlay decodes it with the **hardware**
decoder (`MediaCodec`) — never with a software fallback, because a television
cannot afford the CPU cost (FR-33).

A raw H.264 frame is split into **NAL units**. A frame that is too big for one
network packet is split into **fragments**; on the wire that is the **FU-A**
format, and the receiver has to put the fragments back together
(`RtpInterleaved`). The size of that reassembly buffer was the finding
SEC-03; it is closed, see [NAL unit and FU-A](#nal-unit-and-fu-a).

### NAL unit and FU-A

**NAL unit** (Network Abstraction Layer) — the smallest self-contained piece of
an H.264 stream. A whole picture is not one NAL unit: the picture data is
split into units, and the units that carry the picture can be far larger than
one network packet.

**FU-A** (Fragmentation Unit, A) — the way a *too large* NAL unit is spread
over several RTP packets: the first packet carries the header and the start
bit, the following packets only the payload, the last one the end bit. The
receiver has to collect the fragments again before it can hand the unit to the
decoder.

**Why it is important here:** a peer on the network can open a fragmented unit
and then never send the end bit. Without a bound the receiver would keep
appending fragments until the process died. `RtpInterleaved.MAX_FUA_BYTES`
(8 MB) caps the reassembled unit, the check runs **before** the append, and a
fragment that would cross the cap is discarded together with the incomplete
unit — so the next start fragment begins a clean unit again. The same rule
applies to the payload of a single RTP packet (2 MB).

### H.264 SPS

**SPS** — the **Sequence Parameter Set**, the small NAL unit that describes
the *shape* of the stream: the picture size, the profile and the level. The
**PPS** (Picture Parameter Set) describes how the pictures are coded.

**Why it is important here:** the AirPlay SDP does **not** contain a width and
a height. The real resolution is only encoded inside the SPS, so the receiver
has to parse it before it can configure the hardware decoder
(`H264SpsParser`).

The SPS is attacker-controlled input — every device in the network may send
one — so it is treated as untrusted: the Exp-Golomb values are bounded, the
geometry is computed in 64-bit arithmetic so that no intermediate value can
overflow, and the resulting size is checked against a plausible range
(64 … 8192 px) before it leaves the parser. A hostile SPS therefore ends up as
`null` (the caller falls back to the hint from the SDP), never as a negative
or absurd picture size. The parser accepts raw and Annex-B-prefixed input
through one shared implementation.

### HEVC / H.265

The successor to H.264: roughly the same picture quality at half the bitrate.
Phone and camera manufacturers have moved to it, and AirPlay 2 on newer Apple
hardware can send it.

It is an **optional** feature for PhairPlay, gated on the hardware: a
television whose `MediaCodec` does not list an HEVC decoder simply does not
get HEVC. PhairPlay does not transcode — it either decodes what the hardware
can decode, or it rejects the stream.

### VP9

Google's open video format, the successor to VP8. It is part of the **Google
Cast** baseline, not of AirPlay. Like HEVC, it is hardware-gated.

### AV1

The newest open video format, considerably more efficient than H.264. It
belongs to the **Cast** stack and needs a recent Android version (API 31+).

### ALAC

**Apple Lossless Audio Codec.** Apple's lossless compression for music — the
format in which the Mac sends you "CD quality" audio. It compresses without
losing anything: the decoded audio is bit-for-bit the original.

PhairPlay decodes it in `AlacDecoder` (Kotlin) plus a native decoder in
`app/src/main/cpp/alac/`.

### AAC-ELD

**AAC Enhanced Low Delay.** A variant of AAC designed for *interactive* audio:
it needs very little buffering, which is what you want when the sound has to
match the picture.

In AirPlay 2 it is the format of the **mirroring** audio stream (the type
"96" stream). The other common variant, **AAC-LC**, is the ordinary one for
music. PhairPlay handles both.

### LPCM

**Linear Pulse-Code Modulation** — raw, uncompressed audio samples. Big over
the network, but no encoder and no decoder, so the latency is minimal. It is
the Miracast baseline audio format.

### Plist / binary plist

**Property list** — Apple's XML-based configuration format, used all over
Apple's systems. In AirPlay the handshake payloads are **binary** plists (a
compact, key-value format), not XML.

PhairPlay reads and writes them in
`app/src/main/kotlin/com/phairplay/airplay/handshake/PlistCodec.kt`. The
underlying library (`dd-plist`) is already hardened against XXE — that was
verified with `javap` and is recorded as SEC-14 in the audit.

### DMAP

**Digital Media Access Protocol.** The part of iTunes that describes what is
playing: the title, the artist, the album, the artwork. AirPlay sends it in a
`SET_PARAMETER` request, and PhairPlay shows it in the now-playing overlay
(`NowPlayingInfo`, `NowPlayingScreen`).

### DACP

**Digital Audio Control Protocol.** The *reverse* channel of AirPlay: the
receiver can tell the sender "pause" or "next track". This is how the
**TV remote controls the Mac's music** while the Mac is streaming to the TV
(`DacpClient`). The service type is `_dacp._tcp`.

---

## 3. The networking words

### mDNS

**Multicast DNS.** A tiny DNS that works inside one local network: a device
shouts "I am called *Living Room TV* and I speak AirPlay" to a multicast
address, and every device that is interested hears it.

That shout is what makes the television appear in the AirPlay menu. It is
also the same mechanism behind **Bonjour**, **Avahi** and **DNS-SD** — the
names are interchangeable in practice.

mDNS is why a router with **AP isolation** or **multicast filtering**
enabled breaks PhairPlay: the announcement never reaches the other devices.

### NSD

**Network Service Discovery** — the Android name for the same thing. The
platform class is `NsdManager`, which registers a service and resolves other
services.

PhairPlay uses it through `app/src/main/kotlin/com/phairplay/airplay/` — the
service types are `_airplay._tcp` and `_raop._tcp`.

### DNS-SD

**DNS Service Discovery** — the IETF-standard version of mDNS, with the
service-type naming (`_airplay._tcp`) and the **TXT** record. The TXT record
is a list of key/value pairs that travels with the announcement; in AirPlay it
carries the device identifier, the feature flags and the model.

### Multicast vs unicast

**Unicast** is one conversation between two addresses. **Multicast** is one
message to a group address that every interested member receives. mDNS and
SSDP use multicast; the media streams use unicast.

Android needs a **multicast lock** (`WifiManager.MulticastLock`) to receive
multicast packets while the screen is off. PhairPlay holds it while it
advertises — and that is why the app needs the `CHANGE_WIFI_MULTICAST_STATE`
permission.

### LAN

**Local Area Network** — your home network, the one the television and the
sender are both in. PhairPlay only ever talks to the LAN. There is no
internet path in the application; see [`PRIVACY.md`](PRIVACY.md).

### TCP and UDP

**TCP** guarantees that every byte arrives, in order. **UDP** does not
guarantee anything, but it is much faster and much lighter — which is what
you want for a live video stream where a lost frame is better than a stalled
one.

In AirPlay: the RTSP handshake and the mirrored video use **TCP** (port
7000); the audio stream and the NTP timing use **UDP** (ports 6001/6002).

### IP address

The number that identifies your television in the network, for example
`192.168.1.42`. PhairPlay reads it without any permission, with
`java.net.NetworkInterface` — the manifest comment used to claim otherwise,
and that was corrected.

### HTTP

The protocol of the web: `GET` asks for something, `POST` sends something.
PhairPlay uses it in three places: the AirPlay `/info` and `/photo` endpoints,
the DLNA description server, and the GENA / DACP callbacks.

### URL and URI

**URI** — the name of a resource (`http://192.168.1.10:8080/photo.jpg`).
**URL** — a URI that also says *where* it is. In practice people use them
interchangeably.

The scheme matters for security: `http` and `https` mean "fetch it from the
network", while `file://` means "read it from the disk". A receiver that
accepts `file://` on behalf of a sender becomes a reader for everything its
own app can read — that is exactly finding SEC-01.

### XML

A markup language: elements in angle brackets, nested. UPnP uses it for the
device description and for every SOAP request and answer.

**XXE** (XML External Entity) is the classic attack against an XML parser: a
document that tries to make the parser read a local file or fetch a URL.
PhairPlay's parser disables it.

### AES, CTR and CBC

**AES** is the encryption standard that AirPlay uses for the media stream.

**CTR** (counter mode) turns AES into a stream cipher: the key and a counter
produce a keystream, and the keystream is XOR-ed with the data. It can encrypt
any length without padding — which is why AirPlay uses it for audio frames of
odd sizes.

**CBC** (cipher block chaining) encrypts block by block, each block depending
on the previous one. It is used for the ALAC audio path.

The **IV** (initialisation vector) is the random starting value that makes
two encryptions of the same data look different.

### IP address spoofing, SSRF

**Spoofing** is pretending to be someone else by using their address.
**SSRF** (Server-Side Request Forgery) is when an attacker makes *your* device
send a request on its behalf — from inside your network, where it may be
allowed to go places the attacker cannot.

This is why the DLNA GENA callback is restricted to the local subnet, and why
the URL the receiver fetches on a sender's behalf needs a scheme allow-list.

**The two rules that implement it** live in
`app/src/main/kotlin/com/phairplay/util/`:

| Rule | What it decides |
|---|---|
| `HostAddressPolicy` | Whether the **host** of a URL may be fetched: loopback, link-local (and with it the cloud metadata service `169.254.169.254`), multicast, broadcast, the reserved range, the obfuscated notations of an IPv4 address (decimal, octal, hexadecimal) and every URI that hides the real host behind a user info part are refused. |
| `FetchableUrlPolicy` | Whether the **whole URL** may be fetched: the scheme must be `http` or `https`, the URL must be free of control characters and at most 4096 characters long, and the host must pass `HostAddressPolicy`. |

Both rules are used by both protocol stacks — the AirPlay URL mode
(`POST /play`) and the DLNA renderer (`SetAVTransportURI`) — because in both
cases the *television* opens the connection, not the sender.

A **host name** is deliberately not resolved: resolving it would send a DNS
query from the television, and a name can still point anywhere. That stays a
documented residual risk.

---

## 4. Android and the build

### ADB

**Android Debug Bridge** — the command-line tool that talks to a television or
a phone over USB or over the network. It is how you install an APK, read the
log, look at the memory use and start the app from the command line.

```bash
adb connect <TV-IP>              # connect over the network
adb install app-googletv-debug.apk
adb logcat                       # the device log
adb shell dumpsys meminfo com.phairplay.googletv
```

**Sideloading** means installing an APK by hand instead of from a store. A
Fire TV needs "ADB debugging" and "Apps from unknown sources" switched on;
the full walkthrough is in [`docs/guides/INSTALLATION.md`](docs/guides/INSTALLATION.md).

### ForegroundService

A service that keeps running when the app is in the background, and shows a
**notification** the user cannot simply swipe away.

PhairPlay needs one: the receiver has to keep advertising and keep listening
while the user is watching something else. The service lives in
`app/src/main/kotlin/com/phairplay/service/`, and its notification is the
"Stop / Restart" control.

### DataStore

Android's modern replacement for `SharedPreferences`: an asynchronous,
type-safe key/value store that cannot block the main thread.

PhairPlay stores the settings in it — see `SettingsRepository`. The *model*
(the data class with the fields and their defaults) is separate, in
`AppSettings`, so that the logic can be tested without Android.

### SharedPreferences

The older key/value store. PhairPlay still uses it for the two things that
are not settings: the Ed25519 identity key and the paired-controller public
keys (`PairingKeys`, `PairingStore`). Those are secrets, so they live in
app-private storage with `android:allowBackup="false"`.

### MediaCodec

Android's hardware video and audio decoder. It takes encoded data and pushes
decoded pictures into a surface, and it asks the hardware to do the work.

PhairPlay uses it for H.264 and, where the hardware offers it, HEVC. There is
**no software fallback** — a stream the hardware cannot decode is rejected
rather than decoded slowly (FR-33).

### AudioTrack

Android's audio output: a stream of PCM samples that the TV's speakers play.

### Bitmap and inSampleSize

A **Bitmap** is the decoded picture in memory. A JPEG that is 200 KB on the
wire can announce 20 000 × 20 000 pixels — 1.6 GB of pixel memory. The size
of the *file* says nothing about the size of the *bitmap*, and that gap was
the finding SEC-02.

**inSampleSize** is the knob that prevents it: it is a power of two, and the
decoder applies it **before** the pixels are allocated. A value of 4 means
"keep every fourth pixel in each direction" — a 4000 × 3000 picture becomes
1000 × 750 and costs a sixteenth of the memory. The picture is a little less
sharp, and on a television nobody can tell.

**Why it is important here:** `SafeBitmapDecoder`
(`app/src/main/kotlin/com/phairplay/util/SafeBitmapDecoder.kt`) reads the
header first, refuses a picture outside the budget (longest edge 4096 px,
total 4096 × 4096 pixels) and downsamples the rest with an `inSampleSize`. It
also asks for 16 bits per pixel (`RGB_565`) instead of 32, because the alpha
channel of a photo is not worth half of the memory. `PhotoScreen` and the
album artwork of `NowPlayingScreen` use it.

### SurfaceView / TextureView

The two ways to put video on an Android screen. A `SurfaceView` is a separate
layer that the hardware writes into directly — the cheapest and the lowest
latency, but it cannot be transformed freely. A `TextureView` is a normal
view that you can scale and rotate.

PhairPlay uses a `TextureView` with a matrix for the aspect-fit, because a
resolution change during a session must not freeze the picture.

### Leanback

Google's library for Android TV applications: the focus handling, the on-screen
keyboard and the TV-specific widgets. It is the reason the UI is View-based
instead of Jetpack Compose (ADR-003).

### D-pad

The arrow keys on a television remote. On a TV there is no touch screen, so
every control has to be reachable and visible with the arrow keys. NFR-06 and
NFR-07 make this a requirement, not a preference.

### tools:targetApi

An attribute in `AndroidManifest.xml` that tells the Android tooling: "this
element uses an attribute or a feature that only exists from API level X".
The `tools` namespace is removed again when the manifest is merged, so the
annotation never reaches the APK.

**Why it is important here:** the manifest declares
`NEARBY_WIFI_DEVICES` with `android:usesPermissionFlags="neverForLocation"`.
That attribute was introduced with API 31, but PhairPlay supports API 25
(Fire TV). Android Lint therefore reported *"Attribute usesPermissionFlags is
only used in API level 31 and higher"* — and the build treats warnings as
errors (`warningsAsErrors = true`).

The finding was closed with `tools:targetApi="31"` on the permission element.
That is not a way to hide a real problem: it is the documented way to say "yes,
this needs API 31, and on an older system the attribute is simply ignored".
On an older Android the permission is declared without the flag — only the
"never for location" promise is missing there, which is exactly what the
annotation is for.

The Miracast feature itself has a different, unresolved problem on Android 10
and newer — see the entry on **WFD** above and finding SEC-06 in the audit.

### Gradle flavour

Two builds of the same app from one source tree: `googletv` and `firetv`.
Shared code is in `app/src/main/`, the flavour-specific code in
`app/src/googletv/` and `app/src/firetv/`. The Fire TV has no Google Play
Services, so the Cast SDK is only in the Google TV flavour.

### Lint

Android's static analyser. It finds things like a missing permission, a
resource used on the wrong thread, or an API call that does not exist on the
oldest Android version you support. The PhairPlay build treats **warnings as
errors** (`warningsAsErrors = true` in `app/build.gradle.kts`).

### CI

**Continuous Integration** — the automatic checks that run on every push and
every pull request. For PhairPlay: the JVM test suite
(`./gradlew :test-runner:test`), Android lint and both debug APKs. The
workflows live in `.github/workflows/`.

### ProGuard / R8

The tool that shrinks and obfuscates the release build. PhairPlay enables
`isMinifyEnabled` and `isShrinkResources` for the release build, with the
rules in `app/proguard-rules.pro`.

---

## 5. Working on PhairPlay

### KDoc

The comment format Kotlin uses for documentation: a block before a class or a
function, starting with `/**` and ending with `*/`.

PhairPlay requires a KDoc **header** on every class that says what it does,
**why** it exists and **how** to use it (RULE 2 in
[`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md)). It is the first thing a
newcomer reads.

### ADR

**Architecture Decision Record** — a short document that records one decision:
the context, the decision, the consequences and the alternatives that were
rejected. The existing ones are in [`docs/decisions/`](docs/decisions/), and
the template is [`docs/adr/ADR_TEMPLATE.md`](docs/adr/ADR_TEMPLATE.md).

An ADR is written *after* the decision, and it is never edited afterwards — a
changed decision is a *new* ADR that supersedes the old one.

### CR

**Change Request** — a proposal to change something that was already decided:
a requirement, a codec, a budget, an architectural decision. The template is
[`docs/cr/CR_TEMPLATE.md`](docs/cr/CR_TEMPLATE.md).

A CR is the answer to "I think this should work differently". Without one, a
pull request that changes the product is a pull request that gets closed.

### S0 – S4

PhairPlay's **severity grades**, from Cosmetic (S0) to Critical (S4). They are
used for defects, audit findings and review comments. The full definition and
the release rule — *no release with a known S4 finding* — is in
[`docs/quality/QUALITY.md`](docs/quality/QUALITY.md).

### Unreleased / Keep a Changelog

`CHANGELOG.md` follows the **Keep a Changelog** format: the changes are
grouped into *Added*, *Changed*, *Deprecated*, *Removed*, *Fixed* and
*Security*, and the versions follow **Semantic Versioning** (`MAJOR.MINOR.PATCH`,
with a suffix such as `-beta.3` while the software is still in beta).

### Same-Change rule

A change and the documentation that describes it belong together — in the same
commit, in the same pull request. The full rule is in
[`docs/DOCUMENTATION_POLICY.md`](docs/DOCUMENTATION_POLICY.md).

### Single source of truth

One fact, one place. A number that appears in five documents becomes a
number that is wrong in at least one of them. The other documents link
instead of repeating.

### Byte budget — the MirrorFrameQueue example

A **byte budget** is the rule that limits how much *memory* a remote peer can
make the receiver hold. The classic mistake is to limit only the **number of
items** in a queue: 90 frames is harmless, 90 frames of 8 MB each is 720 MB —
and the app process dies.

**The example in this repository:** the mirroring queue
(`app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorFrameQueue.kt`)
has **two independent bounds**:

| Bound | Value | What it protects |
|---|---|---|
| item count (`DEFAULT_MAX_ITEMS`) | 90 frames ≈ 1.5 s at 60 fps | the **latency** — the decoder is never more than 1.5 s behind |
| byte budget (`DEFAULT_MAX_BYTES`) | 32 MB | the **memory** — the queue can never eat more than a fifth of the 150 MB process budget (NFR-03) |

**Why it is important here:** every queue, buffer and accumulator that a peer
on the network can influence needs a byte budget, not only an item count.
The budget has to be checked **before** the data is appended, and the counter
has to be exact — with a plain `volatile int`, the reader thread and the
decoder thread can lose each other's increment, and a counter that reports
less than the real occupancy silently disables the cap. That is why the
accounting in `MirrorFrameQueue` happens under one lock.

The same rule appears in other places: the reassembled FU-A unit (8 MB), the
payload of a single RTP packet (2 MB), the mirror payload (8 MB), the RTSP
message (1 MB), the photo payload (25 MB) and the SOAP body (64 KB).

