# PhairPlay — Security Findings Register

**Date:** 2026-11-05
**Applies to:** `main` @ `34a166c` (release `v1.0.0-alpha.7`) plus the hardening
changes of this cycle
**Previous register:** [`../audit/2026-10-24-security-audit.md`](../audit/2026-10-24-security-audit.md)
(SEC-01 … SEC-14)

**Severity scale**

| Level | Meaning |
|---|---|
| **S1** | critical — the attacker gets code execution, the identity of the receiver, or the household's data |
| **S2** | major — a remote denial of service, a request proxy into the home network, or a persistent loss of confidentiality |
| **S3** | minor — a limited information disclosure, or a defect with a local workaround |
| **S4** | low / info — accepted by design, or a functional gap with no security impact |

**A finding counts as fixed only when the fix is implemented AND a negative test
exists that actually executes the attack.** No finding is closed by switching a check
off or by suppressing a lint.

---

## 1. Summary

| ID | Title | Severity | Status |
|---|---|---|---|
| SEC-01 | A device in the LAN can make the television fetch an arbitrary URL (AirPlay) | S2 | **fixed** |
| SEC-02 | An image decompression bomb kills the receiver | S2 | **fixed** (rule proven, decoding path needs a device) |
| SEC-03 | The FU-A reassembly buffer has no upper bound | S2 | **fixed** |
| SEC-04 | A device in the LAN can make the television fetch an arbitrary URL (DLNA) | S2 | **fixed (this cycle)** |
| SEC-05 | Personal data is written to the log | S3 | **fixed (this cycle)** |
| SEC-06 | The Miracast permissions are declared but never requested | S3 | accepted — owner decision |
| SEC-07 | A release build logs nothing | S3 | **open** |
| SEC-08 | Three files bypass the central log reduction | S4 | **fixed (this cycle)** |
| SEC-09 | The GENA callback guard checks one address and connects to another | S4 | accepted for v1.0.0 |
| SEC-10 | Cleartext HTTP is blocked by the platform default | S4 | **open** — owner decision |
| SEC-11 | `PlistCodec` has no size limit of its own | S4 | **fixed (this cycle)** |
| SEC-12 | The MAC address and the persistent UUID are broadcast in cleartext | S4 | accepted |
| SEC-13 | The AirPlay PIN has only 10 000 combinations | S4 | accepted |
| SEC-14 | The XML plist parser of the AirPlay stack is already hardened | S4 | accepted — no action |
| SEC-15 | The GENA subscription registry and its notify fan-out are unbounded | S2 | **fixed (this cycle)** |
| SEC-16 | The plist decoder can exhaust the stack of the request thread | S2 | **fixed (this cycle)** |
| SEC-17 | The exported diagnostics report carries the address and the names | S3 | **fixed (this cycle)** |
| SEC-18 | The Ed25519 identity can leave the device through a device-to-device transfer | S2 | **fixed (this cycle)** |
| SEC-19 | The free text of a request body reaches the log | S3 | **fixed (this cycle)** |
| SEC-20 | Google Cast has no registered application ID | S4 | **open** |

**Fixed in this cycle:** SEC-04, SEC-05, SEC-08, SEC-11, SEC-15, SEC-16, SEC-17,
SEC-18, SEC-19.
**Open:** SEC-07, SEC-10, SEC-20 — and the owner decisions listed in §4.

---

## 2. The findings of the previous audit

### SEC-01 — A device in the LAN can make the television fetch an arbitrary URL (AirPlay)

**Severity:** S2 (was *High*)
**Location:** `app/src/main/kotlin/com/phairplay/airplay/RtspVideoUrlHandler.kt:40`,
rule in `app/src/main/kotlin/com/phairplay/util/FetchableUrlPolicy.kt:81`
**Status:** **fixed**

**Attacker scenario.** A laptop in the same Wi-Fi sends a single `POST /play` to the
receiver's RTSP port. The television opens the connection to the address the attacker
chose.

**Attack path.** `POST /play` with `Content-Location: http://192.168.1.10:8080/secret`
→ `RtspVideoUrlHandler.handleVideoPlay` → `FetchableUrlPolicy.isFetchable(url)` →
`400 Bad Request`, the player is never called.

**Damage potential.** The television becomes a request proxy into the home network; a
`file://` URL would make it a file reader for the app's own sandbox.

**Why it is fixed.** The rule accepts only `http` and `https`, refuses control
characters and an over-long URL, and hands the address to `HostAddressPolicy` — no
loopback, no link-local (and with it `169.254.169.254`), no cloud metadata address, no
multicast or broadcast, and none of the obfuscated notations of an IPv4 address.
**Tests:** `FetchableUrlPolicyTest` (17 cases), `RtspVideoUrlHandlerTest`.

---

### SEC-02 — An image decompression bomb kills the receiver

**Severity:** S2 (was *High*)
**Location:** `app/src/main/kotlin/com/phairplay/util/SafeBitmapDecoder.kt:37` and
`:46`; used by `ui/PhotoScreen.kt` and `ui/NowPlayingScreen.kt`
**Status:** **fixed** (the rule is proven; the decoding path needs a device)

**Attacker scenario.** The attacker sends a picture that is small on the wire and
enormous when decoded.

**Attack path.** `PUT /photo` with a JPEG whose header announces 20 000 × 20 000
pixels → the pixel budget of `SafeBitmapDecoder` refuses it → the previous picture
stays on the screen.

**Damage potential.** Remote denial of service: the mirroring session and the receiver
die, repeatedly, at the will of any LAN device.

**Why it is fixed.** The decoder reads the dimensions first (`inJustDecodeBounds`),
refuses an edge longer than 4096 px or a pixel count above the budget, and
downsamples the rest instead of allocating it.
**Tests:** `SafeBitmapDecoderTest` (the arithmetic and the limit rule).
**Residual:** the decoding path itself needs an instrumented test on a device — only
the rule in front of it is proven by the JVM run.

---

### SEC-03 — The FU-A reassembly buffer has no upper bound

**Severity:** S2 (was *High*)
**Location:** `app/src/main/kotlin/com/phairplay/airplay/RtpInterleaved.kt:81`
**Status:** **fixed**

**Attacker scenario.** The attacker opens a fragmented H.264 NAL unit and never
finishes it.

**Attack path.** FU-A start packet followed by continuation packets forever →
`handleFuaFragment` refuses the append as soon as the accumulator would cross
8 MB → the incomplete unit is dropped, nothing reaches the decoder, and the next
FU-A start fragment begins a clean unit.

**Damage potential.** Remote denial of service through memory exhaustion.

**Why it is fixed.** The check runs **before** the append, so the buffer never exceeds
the limit even for a moment.
**Tests:** `RtpInterleavedTest`, `RtpFuaReassemblyTest`.

---

### SEC-04 — A device in the LAN can make the television fetch an arbitrary URL (DLNA)

**Severity:** S2 (was *Medium*, mapped to S3 in the old register; raised to S2
because the old scheme list still contained `file://`)
**Location:** `app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt:57`,
`app/src/main/kotlin/com/phairplay/dlna/DlnaAvTransportService.kt:39`,
rule in `app/src/main/kotlin/com/phairplay/util/FetchableUrlPolicy.kt:81`
**Status:** **fixed (this cycle)**

**Attacker scenario.** A control point in the LAN sends `SetAVTransportURI` with a
`CurrentURI` of its choice. The renderer fetches it itself.

**Attack path.**

1. The attacker connects to the DLNA description port — no credentials are needed.
2. `SetAVTransportURI` with `<CurrentURI>file:///data/data/com.phairplay.googletv/shared_prefs/phairplay_prefs.xml</CurrentURI>`.
3. `DlnaAvTransportService.setAvTransportUri` calls `DlnaMediaUri.validate`.
4. Before this cycle the URI was accepted: the old `DlnaMediaUri.ALLOWED_SCHEMES`
   contained `file`, `smb`, `ftp`, `rtsp`, `rtmp`, `udp`, `pnm` and more, and a
   `file://` URI carries no host that the address policy could check.
5. The URI was stored in the transport state and handed to `MediaPlayer.setDataSource`,
   which reads the local file system.

**Damage potential.** The television becomes a request proxy into the home network and
a reader for everything the app's own UID may read. The URI was also echoed back
verbatim by `GetPositionInfo` and `GetMediaInfo`, so it travelled out of the device
again.

**The fix — one rule, one place.** `DlnaMediaUri` no longer has a scheme list of its
own. It delegates to `FetchableUrlPolicy`, the rule that already guarded the AirPlay
path, and the rule itself applies `HostAddressPolicy` for the address. There is
therefore **no second truth** any more: `http` and `https` for a remote target,
`file://` and every other local scheme refused, loopback, link-local (and with it
the cloud metadata address `169.254.169.254`), multicast, broadcast, the reserved
ranges, the obfuscated notations and the user info part refused.

**The trade-off, stated plainly.** A control point that offers an `rtsp://`, `smb://`
or `ftp://` URI now gets the SOAP fault `402 Invalid Args` instead of a playback
attempt. The renderer cannot speak those protocols itself, and accepting the scheme
meant handing the path to the media stack unchecked. The compatibility cost lands on
real control points, so the decision is listed in §4 as an open owner decision — the
safer variant was implemented, the decision was **not** invented.

**Tests (the attack is executed):**
* `DlnaMediaUriTest` — `rejects a file URI because it would read the local file
  system`, `rejects the local and streaming schemes a control point may emit`,
  `rejects a URI that points at the cloud metadata service`, `rejects a URI that
  hides the real host behind a user info part`.
* `DlnaMediaUriAttackTest` — the end-to-end proof: `a file URI is refused and never
  reaches the playback controller`, `the cloud metadata address is refused and never
  reaches the playback controller`, `a refused URI is not stored and can therefore
  not be played`, `the refused URI is not echoed back by GetMediaInfo`, and
  `a legitimate http URI still reaches the playback controller` (the control that
  proves the rule does not break the feature).
* `DlnaSoapDispatcherTest` — the old test `SetAVTransportURI accepts a file URI and
  an rtsp URI` was **reversed** into
  `SetAVTransportURI refuses a local scheme and accepts an https URI`.

---

### SEC-05 — Personal data is written to the log

**Severity:** S3 (was *Medium*)
**Location:** the call sites listed below and
`app/src/main/kotlin/com/phairplay/util/LogRedactor.kt`
**Status:** **fixed (this cycle)**

**Attacker scenario.** Not an attacker but the *user*: a problem report with a log
excerpt ends up in a public issue tracker. The excerpt then carries the name of the
household's television, the name of the sender and — the worst of them — what the
owner was listening to.

**The attack path that was open.** `LogRedactor` removes addresses, MAC addresses,
UUIDs, URL credentials and query strings. It **cannot** recognise free text, so the
free text must not be logged in the first place. These statements did log it:

| Location | What was logged |
|---|---|
| `airplay/RtspParameterHandler.kt:65` | the title, the artist and the album of the track that is playing |
| `airplay/RtspParameterHandler.kt:31` | the whole body of a `GET_PARAMETER` request |
| `airplay/RtspDeviceInfoHandler.kt:61` and `:78` | the **values** of the plist of `/feedback` and of the buffered control verbs — free text a peer chose |
| `airplay/AirPlayReceiver.kt:157` | the display name |
| `airplay/MdnsService.kt:163` | the advertised name |
| `airplay/MdnsServiceRegistrar.kt:132` and `:217` | the advertised name |
| `airplay/handshake/LegacyPairSetupPin.kt:57` | the user name of the controller that wants to pair |
| `dlna/DlnaReceiver.kt:87` | the friendly name |
| `service/ReceiverSupervisor.kt:170` | the display name |
| `ui/SettingsFragment.kt:344` | the new display name |

**The fix — two layers.**

1. **The call sites do not log the free text any more.** The now-playing metadata is
   logged as the *length* of the title, the artist and the album; a request body is
   logged as its size; the plist of a request is logged as the keys and the *shape* of
   the values (`describeValue`); every name is replaced by its length.
2. **The central reduction knows the names as well.** `LogRedactor` gained
   `registerPersonalString`; the app registers the display name when the AirPlay
   receiver starts (`service/ReceiverSupervisor.kt:86`). A log statement that names
   the television anyway no longer leaks it. A name shorter than four characters is
   not replaced — "TV" occurs in ordinary words, and a reduction that mangles every
   message is worse than none.

**Damage potential.** Publication of personal data by the user, without any attacker
at all — and, for the plist dump, a peer could put arbitrary text into a log excerpt
of the victim's television.

**Tests (the attack is executed):** `LogPrivacyTest` —
`the title the artist and the album of the playing track are not logged` (with the
control that the metadata really was parsed, so there was something to leak),
`the body of a request is not logged`,
`the name of the television is removed by the central reduction`,
`a short name is not replaced because it would mangle every message`,
`the reduction still removes the address of the network`.

---

### SEC-06 — The Miracast permissions are declared but never requested

**Severity:** S3
**Location:** `app/src/main/AndroidManifest.xml:56`–`:71`,
`app/src/main/kotlin/com/phairplay/miracast/MiracastReceiver.kt:206`
**Status:** **accepted — owner decision**

**The facts.** `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`
and `ACCESS_COARSE_LOCATION` are declared. The only runtime permission the app ever
requests is `POST_NOTIFICATIONS` (`app/src/main/kotlin/com/phairplay/MainActivity.kt:301`).
A declared permission is not a granted permission, so on Android 10 and later the
Miracast receiver never starts.

**Damage potential.** No security damage — the feature is dead rather than dangerous.
The privacy damage would be in the *fix*: asking for `ACCESS_FINE_LOCATION` in order
to mirror a screen is the most privacy-sensitive request in the manifest.

**Why it is accepted.** The location permissions are already capped at API 32
(`android:maxSdkVersion="32"`) and `NEARBY_WIFI_DEVICES` carries
`android:usesPermissionFlags="neverForLocation"`, so a current television does not
declare a location permission at all. Making the feature work means a runtime
request for a location permission — a **product** decision (is Miracast worth a
location prompt?), not a security decision. It is listed in §4.

---

### SEC-07 — A release build logs nothing

**Severity:** S3
**Location:** `app/src/main/kotlin/com/phairplay/PhairPlayApp.kt:38`
**Status:** **open**

**The facts.** `initLogging` plants a `Timber.DebugTree()` only in a debug build. In
a release build no tree is planted, and Timber without a tree is a no-op — so a
release build writes **nothing**, not even a warning.

**Damage potential.** A crash in the field is undiagnosable; the owner has to
reproduce it with a debug build, which is a different binary from the one the user
runs. The comment in the code claims that "critical errors use `android.util.Log`
directly" — `grep -rn "android.util.Log" app/src/main` finds **no** call site at
all, so the comment is wrong.

**Why it is not fixed here.** A release tree that writes warnings and errors is a
**product** decision: it decides what leaves the device in a log on a user's
television, and the privacy policy (`PRIVACY.md` §5) currently promises that a
release build writes nothing. The two have to change together, and the policy is not
this track's file. The safe variant — write nothing — is what the code does today.

**Test that would close it:** an instrumented test that plants the release tree,
calls `Logger.d` and `Logger.e`, and asserts that only the error reaches the log and
that the error message carries the reduced form of an address.

---

### SEC-08 — Three files bypass the central log reduction

**Severity:** S4 (was *Low*)
**Location:** `app/src/main/kotlin/com/phairplay/MainActivity.kt`,
`app/src/main/kotlin/com/phairplay/service/BootReceiver.kt`,
`app/src/main/kotlin/com/phairplay/ui/ServiceConnectionBinder.kt`
**Status:** **fixed (this cycle)**

**The facts.** Eleven call sites used `Timber` directly instead of
`com.phairplay.util.Logger`. None of them logged an identifier, so the exposure was
nil — but the guarantee "every log line is reduced" had a hole, and the next
developer who added a `Timber.i("client $ip")` would not have noticed.

**The fix.** All eleven call sites now use `Logger`, the unused `Timber` imports are
gone, and a regression guard was added.

**Damage potential.** None today. The exposure was the guarantee itself.

**Test:** `LogReductionCoverageTest` — `no source file calls Timber directly` walks
the production source set and fails with the file and the line of every offender.
The only permitted direct use is `Timber.plant(…)`, which installs the tree and
writes nothing.

---

### SEC-09 — The GENA callback guard checks one address and connects to another

**Severity:** S4
**Location:** `app/src/main/kotlin/com/phairplay/dlna/GenaEventManager.kt:188`
(`sendNotify`) against `dlna/GenaCallbackPolicy.kt:63`
**Status:** **accepted for v1.0.0**

**The facts.** `isAllowedCallback` refuses a host name outright, so the callback URL
must be a literal private IPv4 address of the local subnet. The notification then
opens the connection to that address — there is no re-resolution, because there is no
name to resolve. The residual window of the old finding is therefore closed for the
common case.

**Damage potential.** Low: the attacker must already be in the LAN and must win a
race; the callback URL carries a literal address.

**Why it is accepted.** The remaining hardening — pinning the resolved address into
the subscription instead of the URL — is a change in `GenaEventManager` with a small
benefit, because the policy already refuses names. It is tracked as R-06 in
`docs/project/RISKS.md` and deferred behind v1.0.0.

---

### SEC-10 — Cleartext HTTP is blocked by the platform default

**Severity:** S4 (functional)
**Location:** `app/src/main/kotlin/com/phairplay/dlna/DlnaReceiver.kt:269`
(`fetchBounded`, `HttpURLConnection`), `app/src/main/kotlin/com/phairplay/airplay/DacpClient.kt`
**Status:** **open** — owner decision

**The facts.** The app declares no network security configuration: there is no
`res/xml/network_security_config.xml` and the manifest has no
`android:networkSecurityConfig` attribute. The app targets API 35, so the platform
default applies and **cleartext traffic is not permitted**.

**Which paths are affected — measured by reading the code:**

| Path | Transport | Affected by the cleartext policy? |
|---|---|---|
| GENA `NOTIFY` | raw `Socket` (`GenaEventManager.kt:203`) | **No** — the policy applies to the HTTP stacks, not to a raw socket |
| DLNA media fetch | `HttpURLConnection` (`DlnaReceiver.kt:270`) | **Yes** |
| DLNA photo fetch | `HttpURLConnection` (`DlnaReceiver.kt:270`) | **Yes** |
| DACP reverse command | `HttpURLConnection` (`DacpClient.kt`) | **Yes** |

**Damage potential.** No direct security damage — the traffic is refused. The risk is
that somebody "fixes" it by switching cleartext on globally, which would also allow
the media URL of SEC-01 and SEC-04 to leave the network.

**Why it is not fixed here.** A network security configuration that permits cleartext
is a **product** decision about the interoperability promise, and the safe variant —
cleartext blocked — is what the code does today. It is listed in §4.

---

### SEC-11 — `PlistCodec` has no size limit of its own

**Severity:** S4
**Location:** `app/src/main/kotlin/com/phairplay/airplay/handshake/PlistCodec.kt:45`
and `:65`
**Status:** **fixed (this cycle)**

**The facts.** `PropertyListParser.parse(bytes)` parsed whatever it was given. The
caller's budget protected it — the RTSP reader rejects a body above 1 MB before
`PlistCodec` is reached — but the limit was one layer away from the parser, and
`POST /feedback` reaches the decoder **without any pairing**.

**The fix.** `MAX_PLIST_BYTES` (1 MB) is checked before the parser is started, and
every failure of the parse now becomes an `IllegalArgumentException` — so the caller
answers `400 Bad Request` and the connection thread stays alive.

**Damage potential.** Memory exhaustion of the receiver, or a crash of the request
thread.

**Test:** `PlistCodecHardeningTest` — `a plist above the size limit is refused`
(asserts an `IllegalArgumentException` and nothing else).

---

### SEC-12 — The MAC address and the persistent UUID are broadcast in cleartext

**Severity:** S4 (accepted)
**Location:** `airplay/MdnsService.kt` (TXT records),
`airplay/handshake/InfoResponder.kt` (`deviceID`, `macAddress`),
`dlna/DlnaDescriptionBuilder.kt` (`UDN`)

**Accepted.** The AirPlay protocol identifies the receiver by its MAC address and by
a persistent UUID, and both are sent in cleartext to every device in the network. A
passive sniffer can therefore tell which television is on the network and track it
over time. This cannot be changed while speaking AirPlay; it can only be avoided by
switching the receiver off. Documented in `PRIVACY.md` §2 and in `SECURITY.md` §7.

---

### SEC-13 — The AirPlay PIN has only 10 000 combinations

**Severity:** S4 (accepted, mitigated)
**Location:** `airplay/RtspPairingHandler.kt:150`–`:152`,
`airplay/handshake/PairingStore.kt:32`

**Accepted.** The PIN is 4 digits because that is what the macOS input field accepts.
The actual defence is the lockout: the failed-attempt counter is persistent, and
after 10 failed attempts the pairing endpoints answer `470 Connection Authorization
Required` until a successful pairing clears the counter. The counter is per receiver,
not per sender, so an attacker who burns the ten attempts locks the legitimate user
out until they reset the app data. Accepted for the beta phase; a per-sender lockout
is the next improvement.

---

### SEC-14 — The XML plist parser of the AirPlay stack is already hardened

**Severity:** S4 (positive finding)
**Location:** `dd-plist 1.28`, `com.dd.plist.XMLPropertyListParser`

**No action.** The static initialiser of `XMLPropertyListParser` sets
`load-external-dtd = false`, `external-general-entities = false`,
`external-parameter-entities = false`, `setXIncludeAware(false)`,
`setExpandEntityReferences(false)` and installs an entity resolver. An XXE payload
is therefore not exploitable, and the "billion laughs" expansion does not happen
either, because entity references are not expanded. Recorded so that a future upgrade
of `dd-plist` re-checks this property.

---

## 3. The new findings

### SEC-15 — The GENA subscription registry and its notify fan-out are unbounded

**Severity:** S2
**Location:** `app/src/main/kotlin/com/phairplay/dlna/GenaEventManager.kt:51`
(`subscribe`), `:104` (`publish`), cap at `:254`
**Status:** **fixed (this cycle)**

**Attacker scenario.** Any device in the LAN opens a subscription, then another one,
then another one — thousands of times.

**Attack path.**

1. The attacker connects to the DLNA description port. No credentials are needed.
2. `SUBSCRIBE` with a `CALLBACK` header that points at a private address of the
   subnet — the address passes the SSRF guard.
3. Each subscription is remembered in `subscriptions` until it expires, and the
   timeout may be 1 800 seconds (`GenaEventManager.kt:229`).
4. One `Play` action publishes a state change; `publish` iterates over every
   subscription of the service and **starts one coroutine per subscription**, each of
   which opens an outbound socket (`sendNotify`, `:188`).

**Damage potential.** Remote denial of service: thousands of simultaneous outbound
connections exhaust the sockets and the threads of the process, and the description
server stops answering — the renderer disappears from every control point.

**The fix.** `MAX_SUBSCRIPTIONS = 32`. The registry and the fan-out are bounded by
the same number, so the two can never drift apart. The price is that a control point
that arrives when the cap is reached receives no state events; the playback path does
not depend on GENA, so the damage is limited to the notification.

**Tests:** `GenaEventManagerTest` — `the number of subscriptions is capped` (fills
the registry, asserts that the next subscription is refused **and** that the refused
subscription was not stored) and `a freed slot can be used again` (the cap is not a
permanent lockout).

---

### SEC-16 — The plist decoder can exhaust the stack of the request thread

**Severity:** S2
**Location:** `app/src/main/kotlin/com/phairplay/airplay/handshake/PlistCodec.kt:65`
**Status:** **fixed (this cycle)**

**Attacker scenario.** The attacker sends a binary plist whose objects are nested
tens of thousands of levels deep. The body is below the 1 MB message budget — a
nested chain costs about 11 bytes per level, so 80 000 levels fit into 880 KB.

**Attack path.**

1. `POST /feedback` with the crafted body — reachable **without any pairing**, PIN
   authentication is off by default.
2. `RtspDeviceInfoHandler.handleFeedback` calls `PlistCodec.decode`.
3. The library walks the object graph recursively, and the conversion into the
   Kotlin tree (`fromNs`) recurses as well.
4. The stack of the request thread is exhausted. A `StackOverflowError` is an
   `Error` and **not** an `Exception`: it slips through every `catch (e: Exception)`
   in the request path and kills the connection thread instead of refusing the
   request.

**Damage potential.** Remote denial of service. Repeated requests kill one connection
thread each and leave the sockets and the memory of the process behind.

**The fix.** The decoder catches the `StackOverflowError` explicitly and turns it
into an `IllegalArgumentException`, exactly like every other parse failure. The
caller then answers `400 Bad Request` and the thread stays alive.

**Test:** `PlistCodecHardeningTest` — `a deeply nested plist is refused instead of
exhausting the stack` builds a binary plist with 80 000 nested dictionaries
(880 051 bytes, hand-written in the binary plist format and validated against an
independent reference parser) and asserts that the failure is an
`IllegalArgumentException` and **not** a `StackOverflowError`.

---

### SEC-17 — The exported diagnostics report carries the address and the names

**Severity:** S3
**Location:** `app/src/main/kotlin/com/phairplay/diagnostics/DiagnosticsReportBuilder.kt:66`,
`app/src/main/kotlin/com/phairplay/diagnostics/DiagnosticsExportRedactor.kt:71`,
`app/src/main/kotlin/com/phairplay/ui/DiagnosticsReportExporter.kt`
**Status:** **fixed (this cycle)**

**Attacker scenario.** Not an attacker but the *user*: the owner copies the
diagnostics report into the clipboard and pastes it into a bug tracker. The pasted
text names the television, its address and — while a session runs — the sender.

**The attack path that was open.** `DiagnosticsRedactor` removed only MAC addresses
and UUIDs. The copy button copied the text of the screen verbatim
(`DiagnosticsFragment.copyReportToClipboard`), and that text carries
`report.deviceName`, `report.localAddress` and `activeSession.senderName`. The
details of the self test carry addresses as well (`192.168.1.42`,
`127.0.0.1:7000`, `239.255.255.250:1900`).

**Damage potential.** Publication of the network details of the household in a public
place — and the addresses of the local network are personal data under the GDPR.

**The fix.** Two reports, two rules.

* The **screen** keeps the name and the address — that is the question the screen
  exists for ("which address do I look for?").
* The **export** removes them: the two personal fields are replaced by the builder
  itself (a name is free text, no pattern can recognise it), and every value passes
  through `DiagnosticsExportRedactor`, which removes the complete IPv4 address, the
  IPv6 address, the MAC address and the UUID. What stays is what makes a bug report
  useful: the version, the platform, the state of every protocol, the ports, the
  outcome of the self test and the resource measurement.

**Tests:** `DiagnosticsExportTest` — `the export carries no IPv4 address`,
`the export carries no MAC address and no UUID`, `the export carries neither the
name of the television nor the name of the sender`, `the export keeps the values
that make a bug report useful`, `the report on the screen still names the device`
(the control that proves the two reports are not the same thing),
`the export redactor removes an IPv6 address`, `the export redactor keeps a
duration`, `the export redactor keeps a version number`.

---

### SEC-18 — The Ed25519 identity can leave the device through a device-to-device transfer

**Severity:** S2
**Location:** `app/src/main/AndroidManifest.xml:133`,
`app/src/main/res/xml/data_extraction_rules.xml`,
`app/src/main/res/xml/backup_rules.xml`,
`app/src/main/kotlin/com/phairplay/airplay/handshake/PairingKeys.kt:38`
**Status:** **fixed (this cycle)**

**Attacker scenario.** The owner replaces the television. The new device is set up
from the old one, and the data of the app is migrated — including the identity of
the receiver.

**The facts.** `android:allowBackup="false"` switches the **cloud** backup off on
every Android version. It does **not** switch the device-to-device transfer off:
the platform documentation states that on devices of some manufacturers a D2D
migration of the app's files happens anyway, and that the rules for it are the ones
of `android:dataExtractionRules`, not the manifest attribute
(<https://developer.android.com/guide/topics/manifest/application-element> and
<https://developer.android.com/about/versions/12/behavior-changes-12>, both read
2026-11-05).

**What the app holds.** `shared_prefs/phairplay_prefs.xml` carries the 32-byte
Ed25519 seed (`pairing_ed25519_seed`) — the long-term identity of the receiver that
every AirPlay sender pairs with — and `shared_prefs/phairplay_pairings.xml` carries
the public keys of the paired controllers.

**Damage potential.** The identity of the television ends up on a machine that has
nothing to do with it. A sender that knows the identity can impersonate the
receiver.

**The fix.** Two rule files, both excluding every domain — from the cloud backup
**and** from the device transfer, in the credential-encrypted and in the
device-protected storage:
`res/xml/data_extraction_rules.xml` (Android 12 and newer) and
`res/xml/backup_rules.xml` (Android 11 and older, which the platform documentation
asks for as well). Both are named in the manifest.

**Tests:** `BackupRulesTest` — `the data extraction rules exclude every domain from
the cloud backup`, `the data extraction rules exclude every domain from the device
transfer`, `the legacy backup rules exclude every domain`, `the manifest names both
rule files and switches the backup off`, `the rule files exist`.

**Residual.** The seed is stored in cleartext inside the app sandbox. The platform
offers no keystore binding for a preference that a third-party protocol reads, and
the sandbox is the boundary. Documented, not fixed.

---

### SEC-19 — The free text of a request body reaches the log

**Severity:** S3
**Location:** `app/src/main/kotlin/com/phairplay/airplay/RtspDeviceInfoHandler.kt:61`
and `:78`
**Status:** **fixed (this cycle)** — together with SEC-05

**The facts.** The `/feedback` handler and the buffered-audio control verbs logged
the **values** of the decoded plist: `"$k=" + when (v) { … else -> v.toString() }`.
The body comes from the sender, so the values are free text that a peer chose.

**The attack path.** A peer sends `POST /feedback` with a plist whose value is a
text of its choosing. The text appears in the log of the victim's television. The
owner pastes the log excerpt into a public issue tracker — and the peer has written
into a public place. It is a log injection with a delivery path, not a bug.

**Damage potential.** The peer chooses what is written into a document that the
victim is likely to publish. At worst a convincing fake log line; at least a
mechanism to plant text in a public place.

**The fix.** `describeValue` writes the *shape* of a value — `12B`, `list[3]`,
`string[42]`, `none` or the class name — and never the value.

**Tests:** `LogPrivacyTest` — `the body of a request is not logged` and
`the title the artist and the album of the playing track are not logged`.

---

### SEC-20 — Google Cast has no registered application ID

**Severity:** S4
**Location:** `app/src/googletv/kotlin/com/phairplay/cast/CastReceiver.kt:36`
(`isConfigured`), `app/build.gradle.kts` (the `phairplay.castAppId` property)
**Status:** **open**

**The facts.** Without an application ID the Cast Connect receiver cannot start. The
receiver reports `ERROR` instead of pretending to advertise a protocol it cannot
serve. The Fire TV flavour never touches the Cast SDK at all.

**Damage potential.** None — the feature is unavailable, not dangerous.

**Why it is open.** The application ID can only be assigned by Google in the Cast
Developer Console; it is an owner action. The acquisition procedure is written down
in `docs/guides/CAST_APP_ID.md`. Tracked as R-02 in `docs/project/RISKS.md`.

---

## 4. Open owner decisions

These need a decision by the owner. The **safer variant was implemented** in every
case; the decision is about the interoperability promise, not about security.

| # | Decision | The safer variant that is implemented now | What the other variant would change |
|---|---|---|---|
| O-1 | Which URI schemes may the DLNA renderer fetch? | `http` and `https` only — `file://`, `smb://`, `ftp://`, `rtsp://` are refused with the SOAP fault `402 Invalid Args` (SEC-04) | A wider list would let a control point name a local path; the renderer cannot speak those protocols itself |
| O-2 | May the DLNA renderer fetch a **public** address? | Yes — the control point decides which medium the owner wants to see, and PhairPlay itself sends nothing | A restriction to the local subnet would break a legitimate media server in the cloud |
| O-3 | May the DLNA renderer follow a **host name**? | Yes, and the name is **not** resolved — a name that points at a link-local address stays a residual risk | Resolving the name would send a DNS query from the television, and a name can still point anywhere |
| O-4 | Should the DLNA stack be able to fetch **cleartext** HTTP? | No — the platform default blocks it, so the DLNA media path does not work on API 28+ (SEC-10) | A network security configuration would allow it, and with it the media URL of SEC-01/SEC-04 could leave the network |
| O-5 | Should the Miracast feature ask for a **location permission**? | No — the permission is declared but never requested, so the feature is dead on Android 10+ (SEC-06) | Asking for it would work, and it is the most privacy-sensitive request in the manifest |
| O-6 | Should a **release build** write warnings and errors to the log? | No — a release build writes nothing, and `PRIVACY.md` §5 promises exactly that (SEC-07) | A release tree would make a field crash diagnosable, and the privacy policy would have to change with it |
| O-7 | Should the AirPlay PIN be **locked out per sender** instead of per receiver? | No — the lockout is per receiver (SEC-13) | A per-sender lockout would stop an attacker from locking the owner out of the pairing |

---

## 5. Accepted residual risks

| Risk | Why it is accepted |
|---|---|
| PIN authentication is off by default | The product promise is "one tap and it works". The setting exists and is documented. |
| The control plane is unencrypted | The AirPlay protocol does not allow it. |
| The device name, the MAC address and the UUID are broadcast on the LAN | Required for the sender to find the receiver; the receiver can be switched off completely. |
| The AirPlay PIN is 4 digits | The macOS input field accepts 4 digits; the persistent lockout is the real defence. |
| A public address may be fetched (O-2) | The peer decides which medium the owner wants to see. |
| A host name is not resolved (O-3) | Resolving it would send a DNS query from the television. |
| The Ed25519 seed is stored in cleartext in the app sandbox | The sandbox is the boundary; the platform offers no keystore binding for a value a third-party protocol reads. |
| The GENA callback connects to a name it resolved before (SEC-09) | The policy refuses host names outright, so the window is narrow. |
| A release build writes nothing (O-6) | Chosen over shipping a logging backend that could leak. The cost is documented in SEC-07. |
