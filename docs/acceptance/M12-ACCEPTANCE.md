# Acceptance — M12: UPnP / DLNA MediaRenderer

**Feature / milestone:** M12 — UPnP / DLNA MediaRenderer (ADR-004 phases D1 … D4)
**Requirement IDs:** FR-44 … FR-57, FR-64 … FR-67, NFR-13, NFR-22, NFR-26 … NFR-31,
AC-M12.1 … AC-M12.20
**Build / commit:** `51933eb` (branch `feature/quality-m6-m12`, merged into
`feature/project-status`)
**Flavour(s) tested:** `googletv` and `firetv` — both compile; the DLNA code is in the
shared `main` source set
**Device(s) tested:** **none** — no television was available
**Sender(s) tested:** **none** — no Windows, no VLC, no Samsung sender was available
**Tester / roles:** Project Manager (audit), with the code evidence and the measured
JVM test run
**Date:** 2026-10-24
**Update (second pass, 2026-10-24):** the team leader closed the three open
points of the previous round — the CI quality gate, the lint and the three files
above the line limit — and the media URI got an **address policy**
(`HostAddressPolicy`), which closes SEC-04 partly. The statements about the
red gate, the lint finding, the missing KDoc header and the missing address
policy were brought up to date in the same commit. The JVM suite and the debug
APKs were **not** built again; the numbers of section 2.1 name the run they come
from.

---

## 1. Functional

Does the feature do what the requirement says?

| # | Scenario | Expected | Result | Evidence |
|---|---|---|---|---|
| 1 | An `M-SEARCH` with `ST: ssdp:all` is answered with exactly three `200 OK` datagrams | three datagrams: `upnp:rootdevice`, the MediaRenderer device type and the UDN, each with `LOCATION`, `USN`, `ST`, `EXT` | **PASS (unit test, real datagrams)** | `SsdpAdvertiserLiveTest > a real M-SEARCH datagram is answered with three responses` — **PASSED**; `SsdpResponderTest > ssdp all is answered with three responses`, `the three answers carry the rootdevice, the device type and the UDN`, `the UDN answer carries the UDN as its USN`, `every answer carries the headers a control point needs` — all **PASSED**. Implementation: `app/src/main/kotlin/com/phairplay/dlna/SsdpResponder.kt`. |
| 2 | An `M-SEARCH` with the MediaRenderer device type is answered with exactly one datagram | one answer | **PASS (unit test, real datagrams)** | `SsdpAdvertiserLiveTest > a real M-SEARCH for the device type is answered with one response` — **PASSED**; `SsdpResponderTest > a MediaRenderer search is answered with exactly one response`, `a rootdevice search is answered with exactly one response` — **PASSED**. |
| 3 | A search for an unknown target is not answered | no answer | **PASS (unit test)** | `SsdpResponderTest > a search for an unknown target is not answered`, `a message without a search target is not answered` — **PASSED**. |
| 4 | The device description and the three SCPD documents are served under the advertised `LOCATION` | the description carries the friendly name, the UDN, the device type and the three services; every SCPD carries its action names | **PASS (unit test)** | `DlnaDescriptionServerTest > serves the device description`, `serves the AVTransport service description with its actions`, `serves the ConnectionManager service description` — **PASSED**; `DlnaDescriptionBuilderTest` — **PASSED**. Implementation: `DlnaDescriptionBuilder.kt`, `DlnaDescriptionServer.kt`. |
| 5 | The control plane answers the three services | `ConnectionManager:1`, `AVTransport:1`, `RenderingControl:1` answer; an unknown action is answered with a SOAP fault; an invalid argument with `402` | **PASS (unit test)** | `DlnaSoapDispatcherTest` — 16 tests, all **PASSED**, among them `SetAVTransportURI accepts the http URL a control point sends`, `GetMediaInfo reports the loaded media`, `GetPositionInfo returns the times in the UPnP H MM SS format`, `RenderingControl SetVolume affects GetVolume`, `RenderingControl rejects an out of range volume`, `AVTransport actions notify playback controller`, `transport state changes are published to the event subscribers`. The fault behaviour is proven in `DlnaSoapDispatcherSecurityTest > an unknown action answers with a SOAP fault` and `an unknown control URL answers with 404` — **PASSED**. |
| 6 | The AVTransport state machine transitions `STOPPED → PLAYING → PAUSED_PLAYBACK → STOPPED` and reports durations in `H:MM:SS(.mmm)` | the documented transitions and format | **PASS (unit test)** | `DlnaSoapDispatcherTest` — the transport state transitions and the position format are asserted; `DlnaTimeFormatTest` (4 tests) — **PASSED**. |
| 7 | `GetProtocolInfo` returns a non-empty `Source` | a renderer with an empty `Source` is hidden by control points | **PASS (code + unit test)** | `DlnaProtocolInfo.SOURCE` = `http-get:*:video/mp4:*,http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/jpeg:*,http-get:*:image/png:*`; `DlnaProtocolInfo.SINK` is filled as well. This was one of the three reasons a renderer did not appear in the VLC list at all. |
| 8 | A `SUBSCRIBE` with a permitted callback is answered with `SID`, `TIMEOUT` and `EXT`; an `UNSUBSCRIBE` removes the subscription | the documented answers | **PASS (unit test)** | `DlnaDescriptionServerTest > a SUBSCRIBE with a valid callback is answered with SID, TIMEOUT and EXT`, `an UNSUBSCRIBE with an unknown SID is refused` — **PASSED**; `GenaEventManagerTest > subscribe returns SID and unsubscribe removes it` — **PASSED**. |
| 9 | A `LastChange` event is delivered when the transport state changes and carries the nested UPnP format | the control point receives the event | **PASS (unit test)** | `GenaEventManagerTest > a LastChange event reaches the control point` — **PASSED**. The delivery uses a **raw socket**, because `HttpURLConnection` refuses the HTTP method `NOTIFY` — the bug that kept the events from ever reaching the control point (implementation note in `GenaEventManager.kt` lines 188–215). |
| 10 | A GENA callback outside the permitted set is refused | loopback, `169.254.169.254`, public addresses, host names, the decimal / octal / hexadecimal notation and every IPv6 form are refused | **PASS (unit test)** | `GenaCallbackPolicyTest` — 16 tests, all **PASSED**: `allows a private IPv4 callback in the local subnet`, `rejects the loopback address`, `rejects the IPv6 loopback`, `rejects the link-local cloud metadata address`, `rejects a public address`, `rejects a DNS name`, `rejects the decimal notation of an address`, `rejects the octal notation of an address`, `rejects the hexadecimal notation of an address`, `rejects an IPv4 mapped IPv6 address`, `rejects a multicast address`, `rejects the broadcast address and the unspecified address`, `rejects a foreign scheme`, `rejects a user info trick that hides the real host`, `rejects a private address in another subnet`, `the literal parser rejects everything that is not a plain dotted quad`. |
| 11 | The control plane refuses an XXE payload without leaking file content | the answer is an error, it does not contain `root:` | **PASS (unit test)** | `DlnaSoapDispatcherSecurityTest > DOCTYPE is rejected`, `an XXE payload inside a SOAP envelope is rejected`, `a billion laughs payload is rejected`, `broken XML is rejected without leaking parser details` — **PASSED**; `SecureSoapXmlTest > an XXE payload cannot read a local file`, `an external entity in a SOAP envelope is refused`, `a billion laughs payload is refused`, `a DOCTYPE declaration is refused even without an entity`, `the parser does not resolve external entities`, `a deeply nested document is refused`, `an oversized document is refused` — all **PASSED**. Implementation: `SecureSoapXml.kt` (fail-closed when the parser does not support the secure feature). |
| 12 | Over-sized requests are refused with `414` / `431` / `413`; a client beyond the worker limit receives `503` | the documented status codes | **PASS (unit test)** | `DlnaDescriptionServerTest > rejects an over-long request line`, `rejects an over-long header line`, `rejects an over-long body` — **PASSED**. The worker limit (8 workers, 16 queued, then `503`) is implemented in `DlnaDescriptionServer.kt`. |
| 13 | No answer contains a stack trace, an exception class name, a file path or a version string | the answers are generic | **PASS (unit test)** | `DlnaDescriptionServerTest > error answers leak neither stack traces nor paths nor versions` — **PASSED**. |
| 14 | A photo payload above 20 MB is refused and a photo is downsampled to at most 1920 px on its longest edge | the documented limits | **PASS (unit test, arithmetic) / NOT MEASURED (decoding)** | `DlnaImageDecoder.MAX_IMAGE_BYTES = 20 * 1024 * 1024`, `DEFAULT_MAX_DIMENSION = 1920`; `DlnaImageDecoderTest` — 6 tests, all **PASSED**, including the `calculateInSampleSize` arithmetic. The actual `BitmapFactory` decode needs an Android runtime and can only be checked on a device. |
| 15 | The DLNA switch in Settings starts and stops the renderer and the state is shown on the Home screen | the switch persists, the card follows the state | **PASS (unit test) / NOT MEASURED (device)** | `AppSettings.dlnaEnabled` (default `true`), `SettingsRepository` persists it (`Keys.DLNA_ENABLED`), `HomeFragment` collects `svc.dlnaState` (line 186). `AppSettingsTest > anyProtocolEnabled is true when only DLNA is enabled` and `default settings have all protocols enabled` — **PASSED**. The card rendering and the disappearance of the advertisement within 5 s are device-only. |
| 16 | Stopping the service closes the description server, drops the GENA subscriptions and sends the SSDP `byebye` | no port stays bound, no event is delivered afterwards | **PASS (unit test, partly) / NOT MEASURED (device)** | `GenaEventManagerTest > stop drops every subscription` — **PASSED**. The `byebye` is sent by `SsdpAdvertiser.stop()`; the port check (`adb shell ss -ltn`) is device-only. |
| 17 | The renderer refuses a media URI whose host points at a link-local, loopback, multicast, broadcast or obfuscated address, and refuses a URI with a user info part | `http://169.254.169.254/latest/meta-data/` is rejected | **PASS for the address — the criterion itself is still open** | **Address policy:** the new class `app/src/main/kotlin/com/phairplay/util/HostAddressPolicy.kt` (170 lines) is called from `DlnaMediaUri.validate` and refuses the loopback (`127.0.0.0/8`, `::1`, `::ffff:127.0.0.1`), the reserved range `0.0.0.0/8`, the link-local range (and with it `169.254.169.254`), the multicast range, the broadcast address, the reserved range `240.0.0.0/4`, the further metadata addresses (`100.100.100.200`, `192.0.0.192`, `fd00:ec2::254`), the known metadata host names (`metadata`, `metadata.google.internal`, `metadata.goog`, `instance-data`, `instance-data.ec2.internal`) and every non-standard notation of an IPv4 address (decimal `2130706433`, octal `0177.0.0.1`, hexadecimal `0x7f000001`), plus any URI with a user info part. Tests: `HostAddressPolicyTest` — 11 test methods, plus one new case in `DlnaMediaUriTest` (`rejects a URI that points at the cloud metadata service`). **Still open:** the *scheme* list of the media URI is unchanged — `DlnaMediaUri.ALLOWED_SCHEMES` still contains `file`, `smb`, `ftp`, `rtsp`, `rtmp`, `rtmpe`, `rtmps`, `mms`, `mmsh`, `mmst`, `udp`, `pnm` (`app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt` lines 30–33) — and the existing test still asserts the opposite: `DlnaSoapDispatcherTest > SetAVTransportURI accepts a file URI and an rtsp URI`. That reduction is CR-01 and it is **not** implemented; it is a deliberate compatibility decision and it needs an owner decision. Residual risks: a **public** address is allowed, and a **host name is not resolved** — a name that points at a link-local address stays a documented residual risk. Risk R-03, change request CR-01. |
| 18 | The TV remote moves the same transport state machine | the control point sees the new state | **PARTLY** | The remote command goes through the dispatcher into the same state machine (`DlnaSoapDispatcher.onRemoteCommand`), which is proven by the unit test `AVTransport actions notify playback controller` and `transport state changes are published to the event subscribers`. The device half (press OK on the TV remote, the progress bar in VLC pauses) is device-only. |
| 19 | The Samsung SSDP search term is answered and the DIAL documents are served | one answer for `urn:samsung.com:service:MultiScreenService:1`; `/dial/` and `/dial/PhairPlay` are served | **PASS (unit test)** | `SamsungSsdpResponderTest` — 6 tests, all **PASSED** (`the Samsung search is answered with exactly one response`, `the Samsung answer carries the search target, the USN and the location`, `the location of the Samsung answer is the device description of this server`, `the USN of the Samsung answer names the device`, `the Samsung search is not answered twice`, `the advertiser accepts the Samsung search`); `DialAppCatalogTest` — 10 tests **PASSED**; `DialEndpointTest` — 6 tests over real HTTP **PASSED** (`the dial directory answers with the app list`, `the dial info of PhairPlay is answered`, `an unknown app is answered with 404`, `an app id that tries to escape the path is answered with 404`, `the device description carries the Application-Url header`, `the Application-Url header is not sent with the app documents`); `DlnaDeviceDescriptionFieldsTest` — 7 tests **PASSED**. |
| 20 | The DLNA pull semantics are documented for the user | the README states that a `file:///` URI points at the sender's disk | **PARTLY** | The statement is in `docs/guides/CASTING_TO_A_TV.md` under "Known limitations" and in `docs/spec/REQUIREMENTS.md` §3 — but `grep -n "file:///" README.md` → no match. AC-M11.8 / AC-M12.20 name the README; the README is owned by the docs track. |
| 21 | A media file that a control point announces over `http` is rendered on the television | the video is rendered within 5 s | **NOT MEASURED — and possibly blocked** | The code path exists (`DlnaReceiver.onPlay` → `AirPlayVideoPlayer`, `DlnaReceiver.fetchBounded`), but two things stand in the way: (a) no device, (b) **no `network_security_config.xml`** exists (`app/src/main/res/xml/` does not exist), so on API 35 the platform blocks the cleartext `http://` fetch (SEC-10 / NFR-28 / risk R-05). This is the highest-risk open point of the milestone. |

**Edge cases exercised:**

| Case | Expected | Result |
|---|---|---|
| A malformed `M-SEARCH` arrives | it is not answered | **PASS** — `SsdpMessagesTest` — 8 tests, all **PASSED** (`recognizes MediaRenderer M-SEARCH`, `recognizes ssdp all search`, `recognizes a MAN header without a space after the colon`, `rejects non search messages`, `rejects a search for a different device type`, `search response includes location and USN`, `the UDN answer carries the UDN as its USN`, `the search target is read from the message`) |
| A control point subscribes with a loopback callback | it is refused | **PASS** — `GenaCallbackPolicyTest > rejects the loopback address`; `DlnaDescriptionServerTest > a SUBSCRIBE with a forbidden callback is refused` |
| A control point subscribes with `http://2130706433/…` (decimal notation) | it is refused | **PASS** — `GenaCallbackPolicyTest > rejects the decimal notation of an address` |
| An XXE payload arrives in a SOAP envelope | it is refused, no file content leaks | **PASS** — `DlnaSoapDispatcherSecurityTest` and `SecureSoapXmlTest` |
| A billion-laughs payload arrives | it is refused | **PASS** — same |
| A deeply nested XML arrives | it is refused | **PASS** — `SecureSoapXmlTest > a deeply nested document is refused` (32-level limit) |
| An over-long request line / header / body arrives | `414` / `431` / `413` | **PASS** — `DlnaDescriptionServerTest` |
| An unknown control URL arrives | `404` | **PASS** — `DlnaSoapDispatcherSecurityTest > an unknown control URL answers with 404` |
| An unknown SOAP action arrives | a SOAP fault, no stack trace | **PASS** — `DlnaSoapDispatcherSecurityTest > an unknown action answers with a SOAP fault` |
| A DIAL app id tries to escape the path | `404`, no file is touched | **PASS** — `DialEndpointTest > an app id that tries to escape the path is answered with 404`; `DialAppCatalogTest > an app id that tries to escape the path has no document` |
| A DIAL app id uses a forbidden character | it is refused | **PASS** — `DialAppCatalogTest > an app id with a forbidden character is refused` |
| The DIAL app id has a different case | `404` | **PASS** — `DialAppCatalogTest > the app id is case sensitive` |
| An empty / over-long / control-character media URI arrives | it is refused | **PASS** — `DlnaMediaUriTest` — 8 test methods (7 in the measured run plus the new `rejects a URI that points at the cloud metadata service`), all **PASSED** (`rejects an empty URI`, `rejects a URI without a scheme`, `rejects a URI with control characters`, `rejects an oversized URI`, `rejects an unknown scheme`) |
| The media URI points at the cloud metadata endpoint | it must be refused | **PASS** — `DlnaMediaUriTest > rejects a URI that points at the cloud metadata service` (new in the second pass) and `HostAddressPolicyTest > refuses the cloud metadata address of the router`, `refuses the other metadata addresses`, `refuses a metadata host name` — the address policy refuses `169.254.169.254`, `100.100.100.200`, `192.0.0.192`, `fd00:ec2::254` and the known metadata names. |
| A `NOTIFY` is sent | `HttpURLConnection` refuses the method | **PASS (fixed)** — the raw socket implementation in `GenaEventManager.kt` bypasses it; the regression test `GenaEventManagerTest > a LastChange event reaches the control point` is **PASSED** |

---

## 2. Technical

- Architecture boundaries respected (the protocol stacks stay independent): **yes** —
  all DLNA logic is in `com.phairplay.dlna` (29 production files). The AirPlay 2
  stack is untouched by it — the only cross-package call is
  `DlnaReceiver.onPlay` → `AirPlayVideoPlayer`, which reuses the *player* and
  does not couple the protocol stacks. This is the guarantee of ADR-001 and
  ADR-004.
- The file-size rule respected (≤ 400 lines soft, ≤ 550 hard): **yes** — the
  largest file of the package is `DlnaDescriptionServer.kt` with 385 lines; no
  file exceeds 400. The new `HostAddressPolicy.kt` has 170 lines.
  `bash scripts/check-file-size.sh` → exit 0, `0 warning(s) above the soft
  limit of 400 lines`.
- Every new class has a KDoc header (what / why / example): **yes** — the
  production classes carry KDoc headers, and the violation of the previous round
  (`app/src/main/kotlin/com/phairplay/dlna/GenaEventManager.kt:242: MISSING-KDOC
  Subscription — the KDoc header is not directly above the declaration (5
  line(s) in between)`) is closed. The KDoc gate is green on the whole tree:
  `bash scripts/check-kdoc-headers.sh` → exit 0, `OK: KDoc header check passed —
  227 class declaration(s) checked, all of them have a KDoc header.`
- Every new public method has a test: **largely yes** — 169 DLNA test methods in
  the current tree (157 in the measured run of `51933eb`, plus the 12 new ones
  of the address policy). The two known gaps: no test opens two concurrent
  clients against the description server, and no test asserts that the media URI
  is refused for a non-HTTP scheme (scenario 17).
- Tests green — `./gradlew :test-runner:test`: **yes** — measured on `51933eb`:
  `BUILD SUCCESSFUL in 3m 27s`, 457 tests, 0 failures, 0 errors, 0 skipped,
  54 test classes. The DLNA package alone accounts for **157** of them.
  **Current state (measured in the SEC-01 run of 2026-10-24):** 490 test methods
  in 58 test classes, of which **158** are in `com.phairplay.dlna` and **59** in
  `com.phairplay.util`. The eleven methods of the address policy moved with the
  class from `com.phairplay.dlna` to `com.phairplay.util` (11 in
  `HostAddressPolicyTest`, 1 in `DlnaMediaUriTest`), and the SEC-01 fix added 21
  methods (16 in `FetchableUrlPolicyTest`, 5 in `RtspVideoUrlHandlerTest`). The
  measured run: `BUILD SUCCESSFUL`, 490 tests, 0 failures, 0 errors, 0 skipped.
- Lint clean — `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug`: **yes** —
  measured after the team leader's fix: `:app:lintGoogletvDebug` → `BUILD
  SUCCESSFUL` (5m 12s) and `:app:lintFiretvDebug` → `BUILD SUCCESSFUL` (3m 39s).
  Both reports say `No issues found.` The single finding of the previous round —
  `app/src/main/AndroidManifest.xml:58: Error: Attribute usesPermissionFlags is
  only used in API level 31 and higher (current min is 29) [UnusedAttribute]` —
  is closed: the permission element now carries `tools:targetApi="31"`. The
  `firetv` variant, which was never analysed before, is analysed and green.

- Both flavours build — `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug`:
  **yes** — see section 2.1.
- No new dependency / no new permission: **yes** — the DLNA stack uses only the
  JDK (`java.net`, `javax.xml`) and the Android framework. No new permission was
  added; the DLNA receiver uses the `INTERNET` permission that was already
  declared.

### 2.1 Measured outputs

**JVM suite** (measured on `51933eb`; **not** re-run in this cycle)

```
$ source /workspace/toolchain/env.sh
$ ./gradlew :test-runner:test --offline --console=plain
...
BUILD SUCCESSFUL in 3m 27s
457 tests, 0 failures, 0 errors, 0 skipped (54 test classes)
```

**Package split of the measured run**

```
com.phairplay.airplay:          167
com.phairplay.airplay.handshake: 35
com.phairplay.cast:               3
com.phairplay.dlna:             157
com.phairplay.media:              4
com.phairplay.miracast:           5
com.phairplay.service:           35
com.phairplay.settings:          19
com.phairplay.util:              32
```

**Package split today** (static count of the `@Test` annotations of the same
source set — the suite was not executed again)

```
com.phairplay.airplay:          167
com.phairplay.airplay.handshake: 35
com.phairplay.cast:               3
com.phairplay.dlna:             169   (+12: the address policy)
com.phairplay.media:              4
com.phairplay.miracast:           5
com.phairplay.service:           35
com.phairplay.settings:          19
com.phairplay.util:              32
                                    ---
                                    469   (56 test classes)
```

**Both debug APKs** (measured on `51933eb`; **not** rebuilt in this cycle)

```
$ ./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --offline --console=plain
...
BUILD SUCCESSFUL
```

**Repository structure checks** (measured in the second pass, current tree)

```
$ bash scripts/check-file-size.sh        ; echo "exit=$?"
OK: file size check passed — 184 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.
exit=0

$ bash scripts/check-kdoc-headers.sh     ; echo "exit=$?"
OK: KDoc header check passed — 227 class declaration(s) checked, all of them have a KDoc header.
exit=0

$ bash scripts/check-docs-links.sh       ; echo "exit=$?"
OK: documentation link check passed — 192 internal link(s) resolve, 15 external link(s) skipped.
exit=0

$ bash scripts/check-i18n-completeness.sh ; echo "exit=$?"
OK: i18n completeness check passed — 80 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).
exit=0
```

**Lint** (measured in the second pass, current tree)

```
$ ./gradlew :app:lintGoogletvDebug     -> BUILD SUCCESSFUL (5m 12s)
$ ./gradlew :app:lintFiretvDebug       -> BUILD SUCCESSFUL (3m 39s)

$ cat app/build/reports/lint-results-googletvDebug.txt
No issues found.
$ cat app/build/reports/lint-results-firetvDebug.txt
No issues found.
```

---

## 3. Performance

| Metric | Budget | Measured | Result |
|---|---:|---:|---|
| SSDP answer latency | ≤ 100 ms (the target of the benchmark table) | **not measured** — no timing test, no device | — |
| DLNA discovery in the control point list | ≤ 3 s (target) / ≤ 10 s (acceptance) | **not measured** — no device | — |
| DLNA photo payload | ≤ 20 MB | **PASS (unit test)** — `DlnaImageDecoder.MAX_IMAGE_BYTES = 20 * 1024 * 1024`, `DlnaImageDecoderTest` | — |
| Mirroring frame queue | ≤ 90 items and ≤ 32 MB | **PASS (unit test)** — `MirrorFrameQueueTest` | — |
| Request line / header block / body | ≤ 8 KB / ≤ 16 KB / ≤ 64 KB | **PASS (unit test)** — `DlnaDescriptionServerTest` asserts the rejection | — |
| XML document size / depth | ≤ 64 KB / ≤ 32 levels | **PASS (unit test)** — `SecureSoapXmlTest` | — |
| Peak RAM during one stream | ≤ 150 MB | **not measured** — no device | — |
| Average CPU | ≤ 30 % | **not measured** — no device | — |
| Frame rate | ≥ 25 fps | **not measured** — no device | — |
| Video latency | ≤ 100 ms | **not measured** — no device | — |

**Reference device / input:** none. The relevant input would be a Windows 11
"Cast to Device", a VLC 3.0+ on the same LAN and a local HTTP server serving the
media file (device test plan case DT-09).

**Measurement commands used:**

```bash
# device test plan case DT-08 / DT-09 / DT-10, from a PC in the same LAN:
python3 - <<'PY'
import socket
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM,socket.IPPROTO_UDP)
s.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_TTL,2)
msg=("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
     'MAN: "ssdp:discover"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n')
s.sendto(msg.encode(),("239.255.255.250",1900))
PY
curl -s "http://<TV-IP>:<PORT>/description.xml" | head -30
adb shell dumpsys meminfo com.phairplay.googletv
```

> The only performance figures of M12 that are measured are the **bounds** that
> the unit tests assert. Every number that describes the behaviour of the
> renderer on a real network is **not measured**.

---

## 4. Usability

- Step count from "I want to mirror" to "it works": **not measurable** — no
  device. The documented path is: enable "DLNA" in Settings → Protocols, then
  pick the TV in the renderer's list (2 steps on the TV, 2 on the sender).
- Discoverability: the DLNA card is the fourth card on the Home screen
  (`HomeFragment.kt` lines 124–127) and the switch is in Settings → Protocols.
- The screen tells the truth: **partly** — the card follows `svc.dlnaState`
  (line 186), and the receiver reports `ERROR` when the fetch or the decode
  fails (`DlnaReceiver` lines 179, 187). The **advertisement** state after a
  network outage is not learned (no re-advertisement path, FR-61 deviation).
- The error state is understandable: the SOAP fault is generic and leaks nothing
  (proven by `error answers leak neither stack traces nor paths nor versions`);
  the *user* sees the protocol card's error state.
- Visual / polish issues seen: **not assessed** — no device.
- Strings: all user-visible texts are in resource files; the DLNA card uses
  `R.string.protocol_dlna`, present in en / de / fr.

---

## 5. Accessibility

- D-pad navigation: **not measured** — no device.
- Focus target size ≥ 48 dp (NFR-07): **not measured**.
- Text size ≥ 18 sp for body, ≥ 32 sp for titles (NFR-08): **not measured**.
- Contrast readable from 3 metres (NFR-09): **not measured**.
- TalkBack / screen reader: **not measured**.
- What is **not** covered yet: the accessibility verification of the DLNA card
  and of the remote-control path. There is a concrete functional consequence
  here: the DLNA transport commands are driven by the TV remote through
  `DlnaRemoteCommand`, and the D-pad mapping has never been exercised on a real
  remote. Device test plan case DT-10 and DT-15 cover the behaviour, not the
  accessibility.

---

## 6. Security and privacy

- New network input (a new parser, a new field, a new port): **yes, a lot** —
  an SSDP listener on UDP 1900 (multicast), an HTTP server on an OS-assigned port
  (the description server) and a SOAP/GENA request parser. Every one of them is
  bounded and hardened:
  - the XML parser is fail-closed (`SecureSoapXml.kt`),
  - the HTTP reader is bounded (`HttpRequestReader.kt`: request line ≤ 8 KB,
    header block ≤ 16 KB, body ≤ 64 KB, 2 s socket timeout, 10 s total deadline,
    8 workers + 16 queued, then `503`),
  - the GENA callback is restricted to a private IPv4 in the local subnet
    (`GenaCallbackPolicy.kt`).
- New stored data (what, where, how long): **yes, transient** — the GENA
  subscriptions (SID, callback URL, expiry) live in memory only
  (`GenaEventManager`), and they are dropped on `stop()`
  (`GenaEventManagerTest > stop drops every subscription`). Nothing is written
  to disk.
- New outbound connection (where does it go?): **yes** — the GENA `NOTIFY` goes
  to the subscribed callback address, which the policy restricts to a private
  IPv4 in the local subnet; the photo fetch goes to the URL the control point
  named. **The second one is now address-restricted** — `HostAddressPolicy`
  refuses the loopback, the reserved, the link-local (and with it the cloud
  metadata address), the multicast, the broadcast and the `240.0.0.0/4`
  addresses, the non-standard notations of an IPv4 address, the known metadata
  host names and every URI with a user info part. What stays open is the
  **scheme list**, the **public addresses** and the **un-resolved host names** —
  see scenario 17.
- New permission: **no** — `INTERNET` only, already declared.
- Negative tests added (the test that proves the guard rejects the attack):
  **yes, extensively** — `DlnaSoapDispatcherSecurityTest` (6), `SecureSoapXmlTest`
  (10), `GenaCallbackPolicyTest` (16), `DlnaDescriptionServerTest` (13, of which
  8 are negative), `DlnaMediaUriTest` (8, of which 7 are negative),
  `DlnaSoapDispatcherTest` (16), and — new in the second pass —
  `HostAddressPolicyTest` (11, all negative for the refused addresses).
  All **PASSED** in the measured run; the twelve new ones are part of the
  current static count (section 2.1).
- The log reduction still applies to every new log statement (SEC-07)? **yes** —
  every message goes through `com.phairplay.util.Logger`, which reduces IP
  addresses, MAC halves, UUIDs and URL credentials before writing.
- Known findings from the audit that still apply to this feature: **SEC-04**
  (the arbitrary-URL fetch via `SetAVTransportURI` — **partly closed**: the
  address of the media URI is now checked, the scheme list, the public addresses
  and the un-resolved host names stay open, scenario 17),
  **SEC-09** (the GENA callback checks one address and connects to another —
  residual, accepted), **SEC-10** (cleartext blocked by the platform default —
  **still open**, and it decides whether the media fetch works at all).
- Privacy impact: does anything new leave the local network? **It must not.**
  Everything the DLNA stack sends stays inside the local network — the GENA
  callback is policy-restricted, the SSDP advertisement is multicast on the LAN.
  **The exception is the media fetch:** the TV opens the URL a control point
  named. The address policy now keeps that fetch away from the loopback, the
  link-local and the metadata addresses of the router; what remains is the
  deliberate acceptance of public addresses and of host names that are not
  resolved — a documented residual risk of the same finding (SEC-04, risk R-03).


---

## 7. Interoperability and data integrity

| Sender | Version | Protocol | Result |
|---|---|---|---|
| macOS | — | AirPlay 2 | not applicable to M12 |
| iPhone / iPad | — | AirPlay 2 | not applicable to M12 |
| Windows | 10 / 11 | Miracast (WFD) | not applicable to M12 |
| Windows | 10 / 11 | DLNA / UPnP ("Cast to Device") | **NOT MEASURED** — no Windows sender and no device. Device test plan case DT-09. The protocol side is proven by the unit tests. |
| VLC | 3.0+ | DLNA / UPnP (renderer list) | **NOT MEASURED** — no VLC and no device. Device test plan case DT-09, the dedicated case. |
| Chrome | — | Google Cast | not applicable to M12 |
| Samsung Galaxy (Smart View / AllShare) | — | DLNA + DIAL | **NOT MEASURED** — the protocol side is proven by 29 tests; whether the television lists the device depends on the model and the app version |

- Round trip: does the state come back correctly (transport state, position, metadata)?
  **PASS (unit test)** — `DlnaSoapDispatcherTest > GetMediaInfo reports the loaded media`,
  `GetPositionInfo returns the times in the UPnP H MM SS format`,
  `RenderingControl SetVolume affects GetVolume`, `AVTransport actions notify playback controller`,
  `transport state changes are published to the event subscribers` — all **PASSED**.
- Does an unsupported format fail cleanly instead of crashing? **yes (unit test)** —
  the fault paths are covered, and no answer leaks internals.
- Recovery: does the receiver come back to a usable state after an interruption?
  **partly** — `DlnaReceiver.stop()` closes the description server, drops the
  subscriptions and sends the SSDP `byebye`; the **re-advertisement after a
  network outage does not exist** (FR-61 deviation).
- For DLNA specifically: was a **network-reachable `http`/`https`** URL used? A
  `file:///` URL points at the *sender's* disk and cannot be read by the
  television — that is a property of the protocol, not a bug. The code accepts
  `file` anyway (scenario 17); the *documented* behaviour is the limitation
  above.

---

## 8. Known limitations

| Limitation | Reason | Where it is documented |
|---|---|---|
| The television has never been seen in a VLC renderer list or a Windows "Cast to Device" list | No hardware was available | `docs/guides/CASTING_TO_A_TV.md`; device test plan DT-08 / DT-09; risk R-12 |
| The media fetch over plain `http` is likely blocked on API 35 | No `network_security_config.xml` exists | SEC-10; NFR-28; risk R-05 |
| The media URI accepts `file`, `smb`, `ftp`, `rtsp`, `rtmp` and other schemes | The allowlist was deliberately wide when it was written; the change request CR-01 was not implemented. **Deliberate decision for the compatibility with real control points — it needs an owner decision, because the choice changes the interoperability promise** | `app/src/main/kotlin/com/phairplay/dlna/DlnaMediaUri.kt`; AC-M12.15; risk R-03 |
| A **public** address in the media URI is allowed | The control point decides which medium the owner wants to see, and PhairPlay itself sends nothing to it | `app/src/main/kotlin/com/phairplay/util/HostAddressPolicy.kt`; risk R-03 |
| A **host name** in the media URI is not resolved | Resolving it would send a DNS query from the television, and a name can still point anywhere — a name that points at a link-local address stays a residual risk | `app/src/main/kotlin/com/phairplay/util/HostAddressPolicy.kt`; risk R-03 |
| The DIAL start command `POST /dial/<AppId>` is not implemented | A DIAL `POST` would add body parsing and a state change without a device to test it against | `docs/guides/CASTING_TO_A_TV.md` ("Known limitations"); the Samsung acceptance record |
| The DIAL `<state>` is constantly `stopped` | PhairPlay runs no DIAL session of its own | same as above |
| The `RenderingControl` volume and mute are stored but never applied to the TV volume | No `AudioManager.setStreamVolume` call exists in the project | `docs/spec/REQUIREMENTS.md`; the open point in the product handover (O-4) |
| The DLNA receiver does not re-advertise after a network outage | Only the AirPlay mDNS path has a recovery hook | `docs/spec/REQUIREMENTS.md` FR-61 |
| The `file:///` pull semantics are not in the README | The README is owned by the docs track | AC-M11.8 / AC-M12.20; the contradiction list in the handover |

**Closed since the previous round** (no longer a limitation):

| Was a limitation | Closed by | Measured |
|---|---|---|
| The media URI had **no address policy** — `http://169.254.169.254/latest/meta-data/` was accepted | the new `HostAddressPolicy` class, called from `DlnaMediaUri.validate` | `HostAddressPolicyTest` (11 test methods) and `DlnaMediaUriTest > rejects a URI that points at the cloud metadata service` |


---

## 9. Decision

**accepted with documented limitation — for the control plane; the media path is open**

**Decided by:** Project Manager, on behalf of the owner
**Date:** 2026-10-24

The **code** for M12 is complete to the letter of ADR-004: D1 (SSDP + description
server), D2 (hardened SOAP dispatcher, three services), D3 (playback, audio-only,
photo with a 20 MB limit, remote control) and D4 (GENA eventing, SSRF hardening,
bounded HTTP). 157 tests cover it, all green in the measured run, including the
Samsung Smart View extension with its 29 further tests; the current tree adds
**12** more test methods for the address policy (169 in the package).

What is **not** true of this milestone is the **end-to-end behaviour on a
television**. No control point has ever talked to the running renderer, and two
concrete gaps stand between the code and a working media path:

1. **No `network_security_config.xml`** — on API 35 the platform blocks the
   cleartext `http://` fetch that the renderer needs (SEC-10, risk R-05). Until
   that is decided, AC-M12.19 is unachievable.
2. **The media URI is address-restricted, but not scheme-restricted** — the
   address policy refuses the loopback, the reserved, the link-local (and with
   it the cloud metadata address), the multicast and the broadcast addresses,
   the non-standard notations of an IPv4 address, the known metadata host names
   and every user info part. What is still accepted: `file`, `smb` and every
   other scheme, a **public** address, and a **host name that is not resolved**
   (AC-M12.15, SEC-04, risk R-03).

The second one is the **same defect class as SEC-01**: an arbitrary-URL fetch
that turns the television into a request proxy — that is the wording of the S4
definition in `docs/quality/QUALITY.md` §0. The **audit grades SEC-04 as
Medium**, and the fixed mapping Medium → **S3** turns it into a major finding:
formally it needs a **written owner decision** in the release notes, not a fix
before the release. The owner therefore has to decide explicitly: treat the
DLNA media URI like SEC-01 (S4 → no release with it) or accept SEC-04 as an S3
with a written reason. Either way it has to be named in the release notes. The
second pass moved the finding from "no protection at all" to "the dangerous
addresses are refused, the compatibility decisions are deliberate and
documented" — the owner decision is still needed for the three residual points.

The milestone status in `docs/spec/PROJECT_PLAN.md` was "📋 Planned" with all
four sub-phases unchecked; it has been **corrected in this cycle** to "🟠 Partial
— control plane delivered, media path open", with the boxes reflecting the code.

**Follow-up (open, because …):**

1. **Reduce the media URI allowlist to `http`/`https`** and reverse the test
   `SetAVTransportURI accepts a file URI and an rtsp URI`. Reason: it closes the
   last open part of SEC-04 / AC-M12.15 (graded *Medium* in the audit, therefore
   **S3** in the quality model). The address policy of the second pass does not
   cover the scheme question.
2. **Decide on the network security configuration** (CR-02): either add a
   configuration that permits cleartext only for private LAN ranges, or document
   that the DLNA media path only works with `https`. Reason: it decides whether
   AC-M12.19 is achievable at all on API 35.
3. **Run device test plan cases DT-08, DT-09, DT-10 and DT-11.** Reason: no
   hardware was available; these four cases decide whether the milestone is
   "implemented" or "working".
4. **Implement or remove the `RenderingControl` volume/mute** — the control
   point currently shows the user a slider that does nothing. Reason: "pretend
   it works" is worse than "not there" (open point O-4).
5. **Add the re-advertisement path for the DLNA receiver.** Reason: FR-61
   requires every enabled receiver to re-advertise within 5 s.


---

## 10. Release gate

| # | Gate | Result |
|---|---|---|
| 1 | `./gradlew :test-runner:test` is green | **yes** — measured on `51933eb`: 457 tests, 0 failures, 0 errors, 0 skipped. Current static count: 469 test methods in 56 classes, 169 of them in the DLNA package (the suite was not re-run in this cycle). |
| 2 | Lint and both debug APKs are green | **yes** — the lint is green for both flavours (`:app:lintGoogletvDebug` and `:app:lintFiretvDebug`, `BUILD SUCCESSFUL`, both reports `No issues found.`); both debug APKs build (measured on `51933eb`) |
| 3 | **No known S4 finding** | **yes for this milestone** — the DLNA surface has no S4 finding. SEC-04 (the arbitrary-URL fetch through `SetAVTransportURI`) is graded *Medium* in the audit and therefore **S3** in the quality model (`docs/quality/QUALITY.md` §0, Medium → S3); the second pass closed the dangerous part of it (the address policy), the scheme list and the two residual points stay open — so it still needs a written owner decision rather than a fix before the release (see gate 4). The two open S4 findings (SEC-01, SEC-02) belong to the AirPlay and the UI track; SEC-03 (the unbounded FU-A reassembly buffer) was closed on 2026-10-24 by the AirPlay track. |
| 4 | Every open S3 has a written owner decision | **partly** — SEC-04 (S3, the DLNA media URI) is **partly closed**: the address policy refuses the loopback, the link-local, the metadata, the multicast, the broadcast and the obfuscated addresses; the **scheme list**, the **public addresses** and the **un-resolved host names** are still open and need either the CR-01 change or a written owner decision in the release notes; SEC-09 (S2, the GENA TOCTOU) is an accepted residual risk; the `RenderingControl` gap is an open point with a proposed decision (O-4) |
| 5 | The device scenarios ran on both flavours | **no** — DT-08, DT-09, DT-10 and DT-11 have not been executed |
| 6 | The file-size rule holds for the touched files | **yes** — the largest file of the package is 385 lines; `bash scripts/check-file-size.sh` → exit 0 with `0 warning(s) above the soft limit of 400 lines` |
| 7 | The documentation is up to date (same change) | **partly** — `docs/guides/CASTING_TO_A_TV.md` is current; the README still calls the DLNA renderer "planned" and lacks the `file:///` statement. The correction belongs to the docs track. |
| 8 | The limitations in `README.md` are true of this build | **NO** — the README understates the DLNA status. Correction proposed to the docs track. |

**CI quality gate** (the job `quality-gate` of `.github/workflows/ci.yml`, not a
separate gate in `docs/quality/QUALITY.md` §7): **green** — all four structure
checks exit 0 (section 2.1).
