# DLNA MediaRenderer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PhairPlay discoverable as a UPnP/DLNA MediaRenderer so Windows "Cast to Device", BubbleUPnP and VLC can play video, music and photos on the TV.

**Architecture:** A new `com.phairplay.dlna` package with a hand-rolled UPnP stack: SSDP discovery, a raw-socket HTTP server, device/service description XML, a SOAP dispatcher feeding three service classes, GENA eventing with `LastChange`, and a `MediaRenderer` state machine that drives a `MediaPlayer` wrapper or a photo fetch. `DlnaReceiver` orchestrates it and plugs into `PhairPlayService` exactly like `AirPlayReceiver`. All protocol logic is pure Kotlin and JVM-tested; only the sockets, `MediaPlayer` and UI glue are Android-only.

**Tech Stack:** Kotlin 1.9 / Android (View-based UI), java.net sockets, `javax.xml.parsers` DOM, kotlinx.coroutines, JUnit 4 via `:test-runner`, ADB to the Pi 4 at `192.168.1.185:5555`.

**Spec:** `docs/superpowers/specs/2026-09-06-dlna-mediarenderer-design.md`

---

## Ground rules

- Git is human-managed: **no git writes**. Each task ends with a suggested commit message for Jeremy.
- `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk` on every Gradle call; one command per shell call (no `&&` chains, no `cd`).
- JVM suite: `./gradlew :test-runner:test` (baseline **230** green). Run it after every task that touches JVM-compilable code.
- No new dependencies. Media3 is out of scope (ask Jeremy first if `MediaPlayer` fails on real files).
- CONTRIBUTING rules apply: files ≤ 400 lines, KDoc header on every class (WHAT/WHY/HOW), every public method tested where the JVM allows, no magic numbers, every exception logged.
- Test files live in `app/src/test/kotlin/com/phairplay/dlna/` and are compiled by `test-runner` together with production code (so `internal` is visible to tests).

## File structure

**New — `app/src/main/kotlin/com/phairplay/dlna/`** (all JVM-testable unless marked ⚠ Android-only)

| File | Responsibility |
|---|---|
| `DlnaConstants.kt` | Every port, cap, interval and protocol string |
| `UpnpService.kt` | Enum of the three services with ids, types and URL paths |
| `UpnpError.kt` | UPnP fault codes as an exception |
| `TransportState.kt` | `TransportState` and `MediaClass` enums |
| `UpnpTime.kt` | `H:MM:SS[.fff]` formatting and parsing |
| `SsdpMessages.kt` | Build/parse SSDP datagrams, target matching, MX clamp |
| `SsdpAdvertiser.kt` ⚠ | Multicast socket: alive/byebye NOTIFY, M-SEARCH responder |
| `HttpRequest.kt` | `HttpRequest`, `HttpParse` result, `HttpRequestReader` |
| `HttpResponse.kt` | `HttpResponse` with `toBytes()` and factories |
| `DlnaHttpServer.kt` ⚠ | ServerSocket accept loop, one coroutine per connection |
| `SecureXml.kt` | Hardened DOM parsing, escaping, child/text helpers |
| `SoapXml.kt` | SOAP envelope parse, response and fault builders |
| `SoapDispatcher.kt` | `SoapActionHandler`, `SoapContext`, `SoapArgs`, dispatch + fault mapping |
| `DeviceDescription.kt` | Root device XML |
| `scpd/ScpdBuilder.kt` | Tiny DSL that emits SCPD XML |
| `scpd/AvTransportScpd.kt`, `scpd/RenderingControlScpd.kt`, `scpd/ConnectionManagerScpd.kt`, `scpd/Scpd.kt` | The three service descriptions and a lookup |
| `ProtocolInfoList.kt` | Sink protocolInfo list and media-class classification |
| `DidlLite.kt` | `DidlItem` + DIDL-Lite metadata parser |
| `RendererControl.kt` | `RendererSnapshot` + `RendererControl` interface the services act on |
| `AvTransportService.kt` | AVTransport:1 actions |
| `RenderingControlService.kt` | RenderingControl:1 actions |
| `ConnectionManagerService.kt` | ConnectionManager:1 actions |
| `LastChangeEncoder.kt` | `LastChange` XML for AVT/RCS and snapshot → variable maps |
| `EventSubscriptions.kt` | GENA subscriptions, `CallbackUrl`, `NotifySender`, moderation, expiry |
| `HttpNotifySender.kt` ⚠ | Raw-socket `NOTIFY` delivery |
| `DlnaRouter.kt` | Path/method routing to description, SCPD, SOAP, GENA, icon |
| `PhotoFetcher.kt` | Size-capped image GET with injectable connection opener |
| `RendererPlayer.kt` | Player interface `MediaRenderer` drives |
| `MediaRenderer.kt` | Session state machine, UI callbacks, `RemoteCommand` |
| `DlnaPlayer.kt` ⚠ | `MediaPlayer` implementation of `RendererPlayer` |
| `LocalAddress.kt` | Site-local IPv4 lookup |
| `DlnaIcon.kt` ⚠ | Launcher icon → 120×120 PNG bytes |
| `DlnaReceiver.kt` ⚠ | Orchestrator: wires everything, MulticastLock, tickers |

**New — tests** `app/src/test/kotlin/com/phairplay/dlna/`: `UpnpTimeTest`, `UpnpServiceTest`, `SsdpMessagesTest`, `HttpRequestReaderTest`, `HttpResponseTest`, `SoapDispatcherTest`, `DeviceDescriptionTest`, `ScpdTest`, `ProtocolInfoListTest`, `DidlLiteTest`, `FakeRendererControl` (helper), `AvTransportServiceTest`, `RenderingControlServiceTest`, `ConnectionManagerServiceTest`, `LastChangeEncoderTest`, `EventSubscriptionsTest`, `DlnaRouterTest`, `PhotoFetcherTest`, `FakeRendererPlayer` (helper), `MediaRendererTest`.

**Modified:** `service/ServiceState.kt`, `service/PhairPlayService.kt`, `settings/AppSettings.kt`, `settings/SettingsRepository.kt`, `MainActivity.kt`, `ui/HomeFragment.kt`, `ui/SettingsFragment.kt`, `res/layout/fragment_home.xml`, `res/layout/fragment_settings.xml`, `res/layout/card_protocol_status.xml` (comments), `res/values/strings.xml`, `res/values-de/strings.xml`, `res/values-fr/strings.xml`, `res/values/colors.xml`, new `res/drawable/ic_dlna.xml`, `test-runner/build.gradle.kts` (exclusions), `tools/collect-device-logs.sh`, tests `AppSettingsTest.kt`, `ServiceStateTest.kt`.

**Docs:** new `docs/decisions/ADR-005-dlna-hand-rolled-upnp.md`; `README.md`, `CHANGELOG.md`, `docs/ARCHITECTURE.md`, `docs/spec/REQUIREMENTS.md`, `docs/spec/PROJECT_PLAN.md`, `docs/spec/TECHNICAL_SPEC.md`, `docs/TESTING.md`, `docs/guides/TROUBLESHOOTING.md`.

---

### Task 1: Foundations — constants, enums, errors, time format

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/DlnaConstants.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/UpnpService.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/UpnpError.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/TransportState.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/UpnpTime.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/UpnpTimeTest.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/UpnpServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UpnpTimeTest — AVTransport positions/durations travel as `H:MM:SS[.fff]` strings.
 *
 * WHY: Windows and VLC compare the strings we return from GetPositionInfo; a wrong pad or a rejected
 * fractional Seek target shows up as a stuck progress bar or a failed scrub.
 */
class UpnpTimeTest {

    @Test
    fun `format pads minutes and seconds but not hours`() {
        assertEquals("0:00:00", UpnpTime.format(0L))
        assertEquals("1:02:03", UpnpTime.format(3_723_000L))
    }

    @Test
    fun `format truncates milliseconds and clamps negatives to zero`() {
        assertEquals("0:00:01", UpnpTime.format(1_999L))
        assertEquals("0:00:00", UpnpTime.format(-5L))
    }

    @Test
    fun `parse accepts whole seconds and fractional seconds`() {
        assertEquals(90_000L, UpnpTime.parse("0:01:30"))
        assertEquals(3_723_500L, UpnpTime.parse("1:02:03.500"))
        assertEquals(3_723_500L, UpnpTime.parse(" 1:02:03.5 "))
    }

    @Test
    fun `parse rejects garbage and out-of-range fields`() {
        assertNull(UpnpTime.parse("abc"))
        assertNull(UpnpTime.parse("0:61:00"))
        assertNull(UpnpTime.parse("1:2"))
        assertNull(UpnpTime.parse("0:00:00.1.2"))
    }
}
```

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UpnpServiceTest — the service ids/types/paths must match what Windows hard-codes.
 *
 * WHY: Windows "Cast to Device" refuses a renderer whose serviceId strings deviate from
 * `urn:upnp-org:serviceId:AVTransport` etc. The router also depends on the path lookups.
 */
class UpnpServiceTest {

    @Test
    fun `service ids and types use the standard UPnP strings`() {
        assertEquals("urn:upnp-org:serviceId:AVTransport", UpnpService.AV_TRANSPORT.serviceId)
        assertEquals("urn:schemas-upnp-org:service:RenderingControl:1", UpnpService.RENDERING_CONTROL.serviceType)
        assertEquals("urn:schemas-upnp-org:service:ConnectionManager:1", UpnpService.CONNECTION_MANAGER.serviceType)
    }

    @Test
    fun `paths round-trip through the lookups`() {
        assertEquals(UpnpService.AV_TRANSPORT, UpnpService.fromControlPath("/control/AVTransport"))
        assertEquals(UpnpService.RENDERING_CONTROL, UpnpService.fromEventPath("/event/RenderingControl"))
        assertEquals(UpnpService.CONNECTION_MANAGER, UpnpService.fromScpdPath("/scpd/ConnectionManager.xml"))
        assertEquals(UpnpService.AV_TRANSPORT, UpnpService.fromServiceType("urn:schemas-upnp-org:service:AVTransport:1"))
        assertNull(UpnpService.fromControlPath("/control/Nope"))
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --tests 'com.phairplay.dlna.*'`
Expected: compilation FAILS with `Unresolved reference: UpnpTime` / `UpnpService`.

- [ ] **Step 3: Create the production files**

`DlnaConstants.kt`:
```kotlin
package com.phairplay.dlna

/**
 * DlnaConstants — every fixed number and protocol string the DLNA renderer relies on.
 *
 * WHY: CONTRIBUTING RULE 4 forbids magic numbers and ports scattered through the code. Keeping them
 * here also documents which values are protocol-mandated (SSDP address/port) and which are ours
 * (HTTP port, caps, intervals).
 *
 * HOW: `DlnaConstants.SSDP_PORT`, etc. Never inline these values elsewhere.
 */
object DlnaConstants {
    /** SSDP multicast group and port — fixed by the UPnP Device Architecture. */
    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900

    /** Our HTTP port for description/control/eventing; falls back to an OS-assigned port if taken. */
    const val DEFAULT_HTTP_PORT = 49494

    /** Per-connection read timeout for the HTTP server. */
    const val HTTP_READ_TIMEOUT_MS = 10_000

    /** Caps on network input (RULE 4). */
    const val MAX_HTTP_BODY_BYTES = 64 * 1024
    const val MAX_PHOTO_BYTES = 20 * 1024 * 1024
    const val PHOTO_TIMEOUT_MS = 10_000

    /** SSDP CACHE-CONTROL max-age; alive NOTIFYs are re-sent well inside it. */
    const val CACHE_MAX_AGE_SECONDS = 1800
    const val ALIVE_INTERVAL_MS = 5 * 60 * 1000L
    const val MAX_SEARCH_DELAY_SECONDS = 3

    /** GENA subscription lifetime, LastChange moderation, delivery limits. */
    const val SUBSCRIPTION_TIMEOUT_SECONDS = 1800
    const val EVENT_MODERATION_MS = 200L
    const val EVENT_DELIVERY_TIMEOUT_MS = 5_000
    const val EVENT_MAX_FAILURES = 2
    const val EXPIRY_SWEEP_MS = 60_000L

    /** Remote-key seek step (fast-forward / rewind). */
    const val SEEK_STEP_MS = 10_000L

    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val SERVER_HEADER = "Android/1.0 UPnP/1.0 PhairPlay/1.0"
    const val DESCRIPTION_PATH = "/description.xml"
    const val ICON_PATH = "/icon.png"
    const val ICON_SIZE_PX = 120
}
```

`UpnpService.kt`:
```kotlin
package com.phairplay.dlna

/**
 * UpnpService — the three UPnP AV services a MediaRenderer exposes, with their standard identifiers.
 *
 * WHY: Control points (Windows especially) match serviceId/serviceType strings exactly; deriving every
 * id, type and URL path from one enum guarantees the description, router and SCPD agree.
 *
 * HOW: `UpnpService.AV_TRANSPORT.controlPath` → `/control/AVTransport`; `fromControlPath(path)` for routing.
 */
enum class UpnpService(val pathName: String) {
    AV_TRANSPORT("AVTransport"),
    RENDERING_CONTROL("RenderingControl"),
    CONNECTION_MANAGER("ConnectionManager");

    val serviceId: String get() = "urn:upnp-org:serviceId:$pathName"
    val serviceType: String get() = "urn:schemas-upnp-org:service:$pathName:1"
    val scpdPath: String get() = "/scpd/$pathName.xml"
    val controlPath: String get() = "/control/$pathName"
    val eventPath: String get() = "/event/$pathName"

    companion object {
        fun fromControlPath(path: String): UpnpService? = entries.firstOrNull { it.controlPath == path }
        fun fromEventPath(path: String): UpnpService? = entries.firstOrNull { it.eventPath == path }
        fun fromScpdPath(path: String): UpnpService? = entries.firstOrNull { it.scpdPath == path }
        fun fromServiceType(type: String): UpnpService? = entries.firstOrNull { it.serviceType == type }
    }
}
```

`UpnpError.kt`:
```kotlin
package com.phairplay.dlna

/**
 * UpnpError — a UPnP action failure carrying the standard error code and description.
 *
 * WHY: Service handlers throw this; [SoapDispatcher] turns it into the SOAP `UPnPError` fault the
 * control point expects. Keeping codes in one place stops ad-hoc numbers spreading through handlers.
 *
 * HOW: `throw UpnpError.transitionNotAvailable("no media")`.
 */
class UpnpError(val code: Int, val description: String) : Exception("UPnP error $code: $description") {
    companion object {
        const val INVALID_ACTION = 401
        const val INVALID_ARGS = 402
        const val ACTION_FAILED = 501
        const val TRANSITION_NOT_AVAILABLE = 701
        const val ILLEGAL_MIME_TYPE = 714
        const val RESOURCE_NOT_FOUND = 716
        const val INVALID_INSTANCE_ID = 718
        const val INVALID_CONNECTION_REFERENCE = 706

        fun invalidAction(name: String) = UpnpError(INVALID_ACTION, "Invalid Action: $name")
        fun invalidArgs(detail: String) = UpnpError(INVALID_ARGS, "Invalid Args: $detail")
        fun actionFailed(detail: String) = UpnpError(ACTION_FAILED, "Action Failed: $detail")
        fun transitionNotAvailable(detail: String) =
            UpnpError(TRANSITION_NOT_AVAILABLE, "Transition not available: $detail")
        fun illegalMimeType(detail: String) = UpnpError(ILLEGAL_MIME_TYPE, "Illegal MIME-Type: $detail")
        fun resourceNotFound(detail: String) = UpnpError(RESOURCE_NOT_FOUND, "Resource not found: $detail")
        fun invalidInstanceId(id: String) = UpnpError(INVALID_INSTANCE_ID, "Invalid InstanceID: $id")
        fun invalidConnectionReference(id: String) =
            UpnpError(INVALID_CONNECTION_REFERENCE, "Invalid connection reference: $id")
    }
}
```

`TransportState.kt`:
```kotlin
package com.phairplay.dlna

/**
 * TransportState — the AVTransport:1 transport states this renderer can report.
 *
 * WHY: Enum names are the exact wire strings (`PLAYING`, `PAUSED_PLAYBACK`, …), so `state.name` is what
 * goes into GetTransportInfo and LastChange.
 */
enum class TransportState {
    NO_MEDIA_PRESENT, STOPPED, TRANSITIONING, PLAYING, PAUSED_PLAYBACK;

    /** True while a control point would consider the renderer "in use" (drives the CONNECTED card state). */
    val isActive: Boolean get() = this == TRANSITIONING || this == PLAYING || this == PAUSED_PLAYBACK
}

/** What kind of item a SetAVTransportURI pointed at; decides player vs. photo path and which overlay. */
enum class MediaClass { VIDEO, AUDIO, IMAGE }
```

`UpnpTime.kt`:
```kotlin
package com.phairplay.dlna

/**
 * UpnpTime — converts between milliseconds and the AVTransport `H:MM:SS[.fff]` time format.
 *
 * WHY: GetPositionInfo/GetMediaInfo report durations as strings and Seek REL_TIME targets arrive as
 * strings; both must be exact or control points show a wrong progress bar or reject the seek.
 *
 * HOW: `UpnpTime.format(90_000)` → `"0:01:30"`; `UpnpTime.parse("0:01:30")` → `90000L` (null if malformed).
 */
object UpnpTime {
    private const val MS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val FRACTION_DIGITS = 3

    fun format(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / MS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return "%d:%02d:%02d".format(hours, minutes, seconds)
    }

    fun parse(text: String): Long? {
        val parts = text.trim().split(":")
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val secondParts = parts[2].split(".")
        if (secondParts.size > 2) return null
        val seconds = secondParts[0].toLongOrNull() ?: return null
        if (hours < 0 || minutes !in 0..59 || seconds !in 0..59) return null
        val fraction = secondParts.getOrNull(1) ?: ""
        val millis = if (fraction.isEmpty()) 0L
            else fraction.take(FRACTION_DIGITS).padEnd(FRACTION_DIGITS, '0').toLongOrNull() ?: return null
        return (hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds) * MS_PER_SECOND + millis
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --tests 'com.phairplay.dlna.*'`
Expected: BUILD SUCCESSFUL, 6 tests passed.

- [ ] **Step 5: Hand-off** — `feat(dlna): add UPnP constants, service enum, error codes and time format`

---

### Task 2: SSDP message building and parsing

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/SsdpMessages.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/SsdpMessagesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SsdpMessagesTest — discovery datagrams must carry exactly the headers control points key on.
 *
 * WHY: Windows lists a renderer from NOTIFY *or* from its M-SEARCH reply; a missing EXT/LOCATION/USN or
 * a wrong ST match means the TV never appears in "Cast to Device".
 */
class SsdpMessagesTest {

    private val udn = "uuid:12345678-1234-1234-1234-123456789abc"
    private val location = "http://192.168.1.185:49494/description.xml"

    private val search = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 5\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    @Test
    fun `parse reads method and case-insensitive headers`() {
        val req = SsdpMessages.parse(search)!!
        assertEquals("M-SEARCH", req.method)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", req.header("st"))
        assertTrue(SsdpMessages.isSearch(req))
    }

    @Test
    fun `parse rejects non-SSDP text`() {
        assertNull(SsdpMessages.parse("GET / HTTP/1.1\r\n\r\n"))
        assertNull(SsdpMessages.parse(""))
    }

    @Test
    fun `isSearch requires the ssdp discover MAN header`() {
        val req = SsdpMessages.parse("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n")!!
        assertFalse(SsdpMessages.isSearch(req))
    }

    @Test
    fun `targets cover root, UDN, device type and the three services`() {
        val targets = SsdpMessages.targets(udn)
        assertEquals(6, targets.size)
        assertEquals("upnp:rootdevice" to "$udn::upnp:rootdevice", targets[0])
        assertEquals(udn to udn, targets[1])
        assertTrue(targets.any { it.first == "urn:schemas-upnp-org:service:AVTransport:1" })
    }

    @Test
    fun `matchingTargets answers ssdp all with everything and a device type with one entry`() {
        assertEquals(6, SsdpMessages.matchingTargets("ssdp:all", udn).size)
        val one = SsdpMessages.matchingTargets(DlnaConstants.DEVICE_TYPE, udn)
        assertEquals(listOf(DlnaConstants.DEVICE_TYPE to "$udn::${DlnaConstants.DEVICE_TYPE}"), one)
        assertTrue(SsdpMessages.matchingTargets("urn:schemas-upnp-org:device:MediaServer:1", udn).isEmpty())
        assertTrue(SsdpMessages.matchingTargets(null, udn).isEmpty())
    }

    @Test
    fun `mxSeconds clamps to the configured maximum and defaults to one`() {
        assertEquals(3, SsdpMessages.mxSeconds(SsdpMessages.parse(search)!!))
        val noMx = SsdpMessages.parse("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n")!!
        assertEquals(1, SsdpMessages.mxSeconds(noMx))
    }

    @Test
    fun `notifyAlive carries the mandatory headers`() {
        val text = SsdpMessages.notifyAlive("upnp:rootdevice", "$udn::upnp:rootdevice", location)
        assertTrue(text.startsWith("NOTIFY * HTTP/1.1\r\n"))
        assertTrue(text.contains("NTS: ssdp:alive\r\n"))
        assertTrue(text.contains("LOCATION: $location\r\n"))
        assertTrue(text.contains("CACHE-CONTROL: max-age=1800\r\n"))
        assertTrue(text.contains("USN: $udn::upnp:rootdevice\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `notifyByeBye has no LOCATION and says byebye`() {
        val text = SsdpMessages.notifyByeBye(udn, udn)
        assertTrue(text.contains("NTS: ssdp:byebye\r\n"))
        assertFalse(text.contains("LOCATION"))
    }

    @Test
    fun `searchResponse is a 200 with EXT, ST and USN`() {
        val text = SsdpMessages.searchResponse(DlnaConstants.DEVICE_TYPE, "$udn::${DlnaConstants.DEVICE_TYPE}", location)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("EXT:\r\n"))
        assertTrue(text.contains("ST: ${DlnaConstants.DEVICE_TYPE}\r\n"))
        assertTrue(text.contains("USN: $udn::${DlnaConstants.DEVICE_TYPE}\r\n"))
        assertTrue(text.contains("DATE: "))
    }
}
```

- [ ] **Step 2: Run to verify it fails** — same Gradle command; expected `Unresolved reference: SsdpMessages`.

- [ ] **Step 3: Create `SsdpMessages.kt`**

```kotlin
package com.phairplay.dlna

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SsdpMessages — builds and parses the SSDP datagrams used for UPnP discovery.
 *
 * WHY: Discovery is plain text over UDP; keeping the wire format here (and out of the socket code in
 * [SsdpAdvertiser]) makes every header Windows keys on unit-testable.
 *
 * HOW: `parse(text)` → [SsdpRequest] or null; `matchingTargets(st, udn)` decides what to answer;
 * `notifyAlive`/`notifyByeBye`/`searchResponse` produce the exact bytes to send.
 */
object SsdpMessages {

    /** A parsed M-SEARCH or NOTIFY. Header names are stored upper-case; use [header]. */
    data class SsdpRequest(val method: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.uppercase(Locale.US)]
    }

    private const val SEARCH_METHOD = "M-SEARCH"
    private const val NOTIFY_METHOD = "NOTIFY"
    private const val SEARCH_MAN = "ssdp:discover"
    private const val SEARCH_ALL = "ssdp:all"
    private const val ROOT_DEVICE = "upnp:rootdevice"
    private const val CRLF = "\r\n"

    fun parse(text: String): SsdpRequest? {
        val lines = text.split(CRLF, "\n")
        val startLine = lines.firstOrNull()?.trim().orEmpty()
        val parts = startLine.split(" ")
        if (parts.size < 3) return null
        val method = parts[0]
        if (method != SEARCH_METHOD && method != NOTIFY_METHOD) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().uppercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        return SsdpRequest(method, headers)
    }

    fun isSearch(request: SsdpRequest): Boolean =
        request.method == SEARCH_METHOD && request.header("MAN")?.trim('"') == SEARCH_MAN

    /** Every (NT, USN) pair the renderer announces: root, UDN, device type, then the three services. */
    fun targets(udn: String): List<Pair<String, String>> = buildList {
        add(ROOT_DEVICE to "$udn::$ROOT_DEVICE")
        add(udn to udn)
        add(DlnaConstants.DEVICE_TYPE to "$udn::${DlnaConstants.DEVICE_TYPE}")
        for (service in UpnpService.entries) add(service.serviceType to "$udn::${service.serviceType}")
    }

    /** Targets to answer for a search target `st`; empty when we are not what is being looked for. */
    fun matchingTargets(st: String?, udn: String): List<Pair<String, String>> {
        val wanted = st?.trim().orEmpty()
        if (wanted.isEmpty()) return emptyList()
        val all = targets(udn)
        return if (wanted == SEARCH_ALL) all else all.filter { it.first == wanted }
    }

    /** MX (seconds a responder may wait) clamped to 1..[DlnaConstants.MAX_SEARCH_DELAY_SECONDS]. */
    fun mxSeconds(request: SsdpRequest): Int =
        (request.header("MX")?.toIntOrNull() ?: 1).coerceIn(1, DlnaConstants.MAX_SEARCH_DELAY_SECONDS)

    fun notifyAlive(nt: String, usn: String, location: String): String =
        "NOTIFY * HTTP/1.1$CRLF" +
            "HOST: ${DlnaConstants.SSDP_ADDRESS}:${DlnaConstants.SSDP_PORT}$CRLF" +
            "CACHE-CONTROL: max-age=${DlnaConstants.CACHE_MAX_AGE_SECONDS}$CRLF" +
            "LOCATION: $location$CRLF" +
            "NT: $nt$CRLF" +
            "NTS: ssdp:alive$CRLF" +
            "SERVER: ${DlnaConstants.SERVER_HEADER}$CRLF" +
            "USN: $usn$CRLF$CRLF"

    fun notifyByeBye(nt: String, usn: String): String =
        "NOTIFY * HTTP/1.1$CRLF" +
            "HOST: ${DlnaConstants.SSDP_ADDRESS}:${DlnaConstants.SSDP_PORT}$CRLF" +
            "NT: $nt$CRLF" +
            "NTS: ssdp:byebye$CRLF" +
            "USN: $usn$CRLF$CRLF"

    fun searchResponse(st: String, usn: String, location: String, now: Date = Date()): String =
        "HTTP/1.1 200 OK$CRLF" +
            "CACHE-CONTROL: max-age=${DlnaConstants.CACHE_MAX_AGE_SECONDS}$CRLF" +
            "DATE: ${httpDate(now)}$CRLF" +
            "EXT:$CRLF" +
            "LOCATION: $location$CRLF" +
            "SERVER: ${DlnaConstants.SERVER_HEADER}$CRLF" +
            "ST: $st$CRLF" +
            "USN: $usn$CRLF$CRLF"

    private fun httpDate(date: Date): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(date)
}
```

- [ ] **Step 4: Run the tests** — expected BUILD SUCCESSFUL, all `dlna` tests pass.

- [ ] **Step 5: Hand-off** — `feat(dlna): SSDP message builder and parser`

---

### Task 3: HTTP request reader and response builder

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/HttpRequest.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/HttpResponse.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/HttpRequestReaderTest.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/HttpResponseTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * HttpRequestReaderTest — the DLNA control server parses SOAP/GENA requests from raw sockets.
 *
 * WHY: Every byte from the network passes this gate (RULE 4): body caps, malformed lines and EOF must
 * each map to a distinct outcome so the server can answer 413/400 or just close.
 */
class HttpRequestReaderTest {

    private fun stream(text: String, body: ByteArray = ByteArray(0)) =
        ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1) + body)

    private val reader = HttpRequestReader(maxBodyBytes = 1024)

    @Test
    fun `reads method, path, headers and body`() {
        val body = "<x/>".toByteArray()
        val parsed = reader.read(stream(
            "POST /control/AVTransport?x=1 HTTP/1.1\r\nHost: tv\r\nSOAPACTION: \"a#Play\"\r\n" +
                "Content-Length: ${body.size}\r\n\r\n", body
        ))
        val request = (parsed as HttpParse.Ok).request
        assertEquals("POST", request.method)
        assertEquals("/control/AVTransport", request.path)
        assertEquals("\"a#Play\"", request.header("soapaction"))
        assertEquals("\"a#Play\"", request.header("SoapAction"))
        assertEquals("<x/>", request.bodyText)
    }

    @Test
    fun `request without body parses with empty body`() {
        val parsed = reader.read(stream("GET /description.xml HTTP/1.1\r\nHost: tv\r\n\r\n"))
        assertTrue(parsed is HttpParse.Ok)
        assertEquals(0, (parsed as HttpParse.Ok).request.body.size)
    }

    @Test
    fun `body above the cap is TooLarge`() {
        val parsed = reader.read(stream("POST /x HTTP/1.1\r\nContent-Length: 1025\r\n\r\n", ByteArray(1025)))
        assertTrue(parsed is HttpParse.TooLarge)
    }

    @Test
    fun `missing HTTP version is Malformed`() {
        assertTrue(reader.read(stream("GET /x\r\n\r\n")) is HttpParse.Malformed)
    }

    @Test
    fun `truncated body is Malformed`() {
        assertTrue(reader.read(stream("POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc")) is HttpParse.Malformed)
    }

    @Test
    fun `empty stream is Eof`() {
        assertTrue(reader.read(ByteArrayInputStream(ByteArray(0))) is HttpParse.Eof)
    }
}
```

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HttpResponseTest — responses must be valid HTTP/1.1 with Content-Length and Connection: close.
 *
 * WHY: We never keep connections alive; a wrong Content-Length makes control points hang.
 */
class HttpResponseTest {

    @Test
    fun `toBytes writes status line, mandatory headers and body`() {
        val text = String(HttpResponse.xml(200, "<a/>").toBytes(), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("CONTENT-LENGTH: 4\r\n"))
        assertTrue(text.contains("CONNECTION: close\r\n"))
        assertTrue(text.contains("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n"))
        assertTrue(text.endsWith("\r\n\r\n<a/>"))
    }

    @Test
    fun `reason phrases cover the codes we emit`() {
        assertEquals("Precondition Failed", HttpResponse.reason(412))
        assertEquals("Payload Too Large", HttpResponse.reason(413))
        assertEquals("Method Not Allowed", HttpResponse.reason(405))
        assertEquals("Unknown", HttpResponse.reason(299))
    }

    @Test
    fun `empty response has zero content length`() {
        val text = String(HttpResponse.empty(404).toBytes(), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 404 Not Found\r\n"))
        assertTrue(text.contains("CONTENT-LENGTH: 0\r\n"))
    }
}
```

- [ ] **Step 2: Run to verify they fail** — expected `Unresolved reference: HttpRequestReader`.

- [ ] **Step 3: Create the production files**

`HttpRequest.kt`:
```kotlin
package com.phairplay.dlna

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * HttpRequest — one parsed HTTP/1.1 request from a control point.
 *
 * WHY: SOAP control and GENA eventing both arrive as HTTP; the router and services need method, path,
 * case-insensitive headers and the body without touching the socket.
 *
 * HOW: Produced by [HttpRequestReader]; `request.header("soapaction")` (any case).
 */
class HttpRequest(
    val method: String,
    val path: String,
    private val headers: Map<String, String>,
    val body: ByteArray
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    val bodyText: String get() = String(body, Charsets.UTF_8)
}

/** Outcome of reading one request — distinct so the server can answer 413/400 or close on EOF. */
sealed class HttpParse {
    data class Ok(val request: HttpRequest) : HttpParse()
    object Eof : HttpParse()
    object Malformed : HttpParse()
    object TooLarge : HttpParse()
}

/**
 * HttpRequestReader — reads exactly one HTTP request (request line, headers, Content-Length body).
 *
 * WHY: It is the validation gate for every byte a DLNA control point sends (RULE 4): line, header and
 * body sizes are capped before anything is parsed. Separate from the AirPlay `RtspRequestReader` because
 * the verbs (SUBSCRIBE/NOTIFY), limits and body semantics differ.
 *
 * HOW: `when (reader.read(socket.getInputStream())) { is HttpParse.Ok -> …; HttpParse.TooLarge -> 413 … }`
 */
class HttpRequestReader(private val maxBodyBytes: Int = DlnaConstants.MAX_HTTP_BODY_BYTES) {

    fun read(input: InputStream): HttpParse {
        val requestLine = readLine(input) ?: return HttpParse.Eof
        val parts = requestLine.trim().split(" ")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return HttpParse.Malformed

        val headers = HashMap<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readLine(input) ?: return HttpParse.Malformed
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) return HttpParse.TooLarge
            val colon = line.indexOf(':')
            if (colon <= 0) return HttpParse.Malformed
            headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0) return HttpParse.Malformed
        if (length > maxBodyBytes) return HttpParse.TooLarge
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return HttpParse.Malformed
            read += n
        }
        return HttpParse.Ok(HttpRequest(parts[0], parts[1].substringBefore('?'), headers, body))
    }

    /** Reads up to CRLF/LF (stripped). Null on EOF before any byte or on an over-long line. */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            if (buffer.size() > MAX_LINE_BYTES) return null
        }
        return buffer.toString("ISO-8859-1")
    }

    companion object {
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}
```

`HttpResponse.kt`:
```kotlin
package com.phairplay.dlna

/**
 * HttpResponse — an HTTP/1.1 response the DLNA server writes back, always `Connection: close`.
 *
 * WHY: One place to get the status line, Content-Length and server headers right; [afterSend] lets GENA
 * fire the initial NOTIFY only after the SUBSCRIBE reply has been written (the spec-mandated order).
 *
 * HOW: `HttpResponse.xml(200, body)`, `HttpResponse.empty(404)`, then `socket.write(response.toBytes())`.
 */
class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    /** Invoked by the server once the response bytes are on the wire (used for the GENA initial event). */
    val afterSend: (() -> Unit)? = null
) {
    fun toBytes(): ByteArray {
        val head = StringBuilder()
        head.append("HTTP/1.1 $status ${reason(status)}\r\n")
        head.append("SERVER: ${DlnaConstants.SERVER_HEADER}\r\n")
        head.append("CONNECTION: close\r\n")
        head.append("CONTENT-LENGTH: ${body.size}\r\n")
        headers.forEach { (name, value) -> head.append("$name: $value\r\n") }
        head.append("\r\n")
        return head.toString().toByteArray(Charsets.ISO_8859_1) + body
    }

    companion object {
        private const val XML_CONTENT_TYPE = "text/xml; charset=\"utf-8\""

        fun empty(status: Int, headers: Map<String, String> = emptyMap()) = HttpResponse(status, headers)

        fun bytes(status: Int, contentType: String, body: ByteArray, extraHeaders: Map<String, String> = emptyMap()) =
            HttpResponse(status, mapOf("CONTENT-TYPE" to contentType) + extraHeaders, body)

        fun xml(status: Int, xml: String, extraHeaders: Map<String, String> = emptyMap()) =
            bytes(status, XML_CONTENT_TYPE, xml.toByteArray(Charsets.UTF_8), extraHeaders)

        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            412 -> "Precondition Failed"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
    }
}
```

- [ ] **Step 4: Run the tests** — expected BUILD SUCCESSFUL.

- [ ] **Step 5: Hand-off** — `feat(dlna): raw HTTP request reader and response builder`

---

### Task 4: Secure XML, SOAP envelope handling and the dispatcher

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/SecureXml.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/SoapXml.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/SoapDispatcher.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/SoapDispatcherTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SoapDispatcherTest — SOAP control requests become typed actions; failures become UPnPError faults.
 *
 * WHY: Windows treats any malformed response as "device not responding". The fault codes (401/402/501/718)
 * are also what BubbleUPnP shows the user, so they must be exact.
 */
class SoapDispatcherTest {

    private val avt = UpnpService.AV_TRANSPORT
    private val soapAction = "\"${avt.serviceType}#Play\""

    private fun envelope(action: String, inner: String = "") =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"${avt.serviceType}\">$inner</u:$action></s:Body></s:Envelope>"

    private fun dispatcher(handler: SoapActionHandler) = SoapDispatcher(mapOf(avt to handler))

    @Test
    fun `successful action returns a 200 response envelope with out arguments escaped`() {
        val result = dispatcher { action, args, _ ->
            assertEquals("Play", action)
            assertEquals("0", args["InstanceID"])
            assertEquals("1", args["Speed"])
            mapOf("Note" to "a<b&c")
        }.dispatch(avt, soapAction, envelope("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"))
        assertEquals(200, result.status)
        assertTrue(result.xml.contains("<u:PlayResponse xmlns:u=\"${avt.serviceType}\">"))
        assertTrue(result.xml.contains("<Note>a&lt;b&amp;c</Note>"))
    }

    @Test
    fun `UpnpError from the handler becomes a 500 fault with its code`() {
        val result = dispatcher { _, _, _ -> throw UpnpError.transitionNotAvailable("no media") }
            .dispatch(avt, soapAction, envelope("Play"))
        assertEquals(500, result.status)
        assertTrue(result.xml.contains("<errorCode>701</errorCode>"))
        assertTrue(result.xml.contains("<faultcode>s:Client</faultcode>"))
        assertTrue(result.xml.contains("UPnPError"))
    }

    @Test
    fun `unexpected exception becomes a 501 fault`() {
        val result = dispatcher { _, _, _ -> error("boom") }.dispatch(avt, soapAction, envelope("Play"))
        assertTrue(result.xml.contains("<errorCode>501</errorCode>"))
    }

    @Test
    fun `unreadable body is a 402 fault`() {
        val result = dispatcher { _, _, _ -> emptyMap() }.dispatch(avt, soapAction, "not xml")
        assertTrue(result.xml.contains("<errorCode>402</errorCode>"))
    }

    @Test
    fun `body with a DOCTYPE is rejected as unreadable`() {
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE s [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" +
            envelope("Play", "<InstanceID>&x;</InstanceID>").substringAfter("?>")
        val result = dispatcher { _, _, _ -> emptyMap() }.dispatch(avt, soapAction, xxe)
        assertTrue(result.xml.contains("<errorCode>402</errorCode>"))
    }

    @Test
    fun `service without a handler is a 401 fault`() {
        val result = SoapDispatcher(emptyMap()).dispatch(avt, soapAction, envelope("Play"))
        assertTrue(result.xml.contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun `user agent reaches the handler through the context`() {
        var seen: String? = null
        dispatcher { _, _, context -> seen = context.userAgent; emptyMap() }
            .dispatch(avt, soapAction, envelope("Play"), SoapContext(userAgent = "Microsoft-Windows/10.0 UPnP/1.0"))
        assertEquals("Microsoft-Windows/10.0 UPnP/1.0", seen)
    }

    @Test
    fun `SoapArgs rejects a non-zero InstanceID and missing required args`() {
        try { SoapArgs.requireInstanceZero(mapOf("InstanceID" to "1")); error("expected 718") }
        catch (e: UpnpError) { assertEquals(718, e.code) }
        SoapArgs.requireInstanceZero(emptyMap())   // absent InstanceID defaults to 0
        try { SoapArgs.required(emptyMap(), "CurrentURI"); error("expected 402") }
        catch (e: UpnpError) { assertEquals(402, e.code) }
    }
}
```

- [ ] **Step 2: Run to verify it fails** — expected `Unresolved reference: SoapDispatcher`.

- [ ] **Step 3: Create the production files**

`SecureXml.kt`:
```kotlin
package com.phairplay.dlna

import com.phairplay.util.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SecureXml — DOM parsing hardened against external entities, plus the small helpers every XML
 * consumer in the DLNA stack needs (namespace-agnostic child lookup, escaping).
 *
 * WHY: SOAP bodies and DIDL metadata come straight from the network. Disabling DOCTYPE/entity
 * processing closes the XXE class of bugs (RULE 4). On Android the platform parser ignores unknown
 * features, so each is set best-effort; the platform parser never resolves external entities anyway.
 *
 * HOW: `SecureXml.parse(xml)?.documentElement`; `SecureXml.escape(text)` for anything placed in XML.
 */
object SecureXml {

    private val HARDENING_FEATURES = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false
    )

    /** Parses [xml] or returns null (logged) when it is blank, malformed, or contains a DOCTYPE. */
    fun parse(xml: String): Document? {
        if (xml.isBlank()) return null
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
            Logger.w("SecureXml: DOCTYPE rejected")
            return null
        }
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                for ((feature, value) in HARDENING_FEATURES) {
                    runCatching { setFeature(feature, value) }   // unsupported on Android's parser
                }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            Logger.w("SecureXml: parse failed: ${e.message}")
            null
        }
    }

    fun childElements(parent: Node): List<Element> {
        val children = parent.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }
    }

    /** Text of the first child element whose local name matches, ignoring namespace prefixes. */
    fun firstText(parent: Element, localName: String): String? =
        childElements(parent).firstOrNull { localNameOf(it) == localName }?.textContent

    fun localNameOf(element: Element): String = element.localName ?: element.nodeName.substringAfter(':')

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
```

`SoapXml.kt`:
```kotlin
package com.phairplay.dlna

/**
 * SoapXml — parses a UPnP SOAP request envelope and builds response / fault envelopes.
 *
 * WHY: UPnP control is SOAP 1.1 with a fixed shape; hand-building it (no library) keeps the wire
 * format exact and testable, and lets us reject anything outside that shape.
 *
 * HOW: `SoapXml.parseAction(body)` → [Action]; `SoapXml.response(type, "Play", args)`; `SoapXml.fault(701, …)`.
 */
object SoapXml {

    data class Action(val name: String, val args: Map<String, String>)

    private const val ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val ENCODING_STYLE = "http://schemas.xmlsoap.org/soap/encoding/"
    private const val CONTROL_NS = "urn:schemas-upnp-org:control-1-0"

    /** First element inside `s:Body` is the action; its children are the arguments. Null if unreadable. */
    fun parseAction(body: String): Action? {
        val document = SecureXml.parse(body) ?: return null
        val envelope = document.documentElement ?: return null
        val soapBody = SecureXml.childElements(envelope).firstOrNull { SecureXml.localNameOf(it) == "Body" }
            ?: return null
        val actionElement = SecureXml.childElements(soapBody).firstOrNull() ?: return null
        val args = LinkedHashMap<String, String>()
        for (arg in SecureXml.childElements(actionElement)) {
            args[SecureXml.localNameOf(arg)] = arg.textContent ?: ""
        }
        return Action(SecureXml.localNameOf(actionElement), args)
    }

    /** `"urn:…:service:AVTransport:1#Play"` → (serviceType, "Play"); null when the header is unusable. */
    fun actionFromHeader(soapAction: String?): Pair<String, String>? {
        val raw = soapAction?.trim()?.trim('"') ?: return null
        val hash = raw.indexOf('#')
        if (hash <= 0 || hash == raw.length - 1) return null
        return raw.substring(0, hash) to raw.substring(hash + 1)
    }

    fun response(serviceType: String, action: String, outArgs: Map<String, String>): String {
        val args = outArgs.entries.joinToString("") { (name, value) -> "<$name>${SecureXml.escape(value)}</$name>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"$ENVELOPE_NS\" s:encodingStyle=\"$ENCODING_STYLE\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"$serviceType\">$args</u:${action}Response>" +
            "</s:Body></s:Envelope>"
    }

    fun fault(code: Int, description: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"$ENVELOPE_NS\" s:encodingStyle=\"$ENCODING_STYLE\"><s:Body>" +
            "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"$CONTROL_NS\"><errorCode>$code</errorCode>" +
            "<errorDescription>${SecureXml.escape(description)}</errorDescription></UPnPError>" +
            "</detail></s:Fault></s:Body></s:Envelope>"
}
```

`SoapDispatcher.kt`:
```kotlin
package com.phairplay.dlna

import com.phairplay.util.Logger

/** Per-request facts a handler may need beyond the SOAP arguments. */
data class SoapContext(val userAgent: String? = null)

/** One UPnP service's action implementation. Throw [UpnpError] for protocol-level failures. */
fun interface SoapActionHandler {
    @Throws(UpnpError::class)
    fun handle(action: String, args: Map<String, String>, context: SoapContext): Map<String, String>
}

/**
 * SoapArgs — argument validation shared by the service handlers.
 *
 * WHY: Every AVTransport/RenderingControl action carries `InstanceID`, and we only have instance 0;
 * the 718 and 402 faults must be uniform across services.
 */
object SoapArgs {
    private const val ONLY_INSTANCE = "0"

    fun requireInstanceZero(args: Map<String, String>) {
        val id = (args["InstanceID"] ?: ONLY_INSTANCE).trim()
        if (id != ONLY_INSTANCE) throw UpnpError.invalidInstanceId(id)
    }

    fun required(args: Map<String, String>, name: String): String =
        args[name] ?: throw UpnpError.invalidArgs("missing $name")
}

/**
 * SoapDispatcher — turns a SOAP control POST into a service action call and a SOAP reply.
 *
 * WHY: All three services share the envelope handling and fault mapping; the services themselves stay
 * pure "action in, arguments out" so they are trivially unit-tested.
 *
 * HOW: `dispatcher.dispatch(UpnpService.AV_TRANSPORT, soapActionHeader, body, SoapContext(ua))` → [Result]
 * whose `status` is 200 for success and 500 for a fault, `xml` being the envelope to send.
 */
class SoapDispatcher(private val handlers: Map<UpnpService, SoapActionHandler>) {

    data class Result(val status: Int, val xml: String)

    fun dispatch(
        service: UpnpService,
        soapActionHeader: String?,
        body: String,
        context: SoapContext = SoapContext()
    ): Result {
        val parsed = SoapXml.parseAction(body)
            ?: return fault(UpnpError.invalidArgs("unreadable SOAP body"))
        val headerAction = SoapXml.actionFromHeader(soapActionHeader)?.second
        if (headerAction != null && headerAction != parsed.name) {
            return fault(UpnpError.invalidArgs("SOAPACTION '$headerAction' does not match body '${parsed.name}'"))
        }
        val handler = handlers[service] ?: return fault(UpnpError.invalidAction(parsed.name))
        return try {
            val out = handler.handle(parsed.name, parsed.args, context)
            Logger.d("SOAP ${service.pathName}.${parsed.name} ok")
            Result(200, SoapXml.response(service.serviceType, parsed.name, out))
        } catch (e: UpnpError) {
            Logger.w("SOAP ${service.pathName}.${parsed.name} → ${e.code} ${e.description}")
            fault(e)
        } catch (e: Exception) {
            Logger.e("SOAP ${service.pathName}.${parsed.name} failed", e)
            fault(UpnpError.actionFailed(e.message ?: "unexpected error"))
        }
    }

    private fun fault(error: UpnpError) = Result(500, SoapXml.fault(error.code, error.description))
}
```

- [ ] **Step 4: Run the tests** — expected BUILD SUCCESSFUL, `SoapDispatcherTest` 8 passed.

- [ ] **Step 5: Hand-off** — `feat(dlna): hardened XML parsing, SOAP envelopes and action dispatcher`

---

### Task 5: Device description and the three service descriptions (SCPD)

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/DeviceDescription.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/scpd/ScpdBuilder.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/scpd/AvTransportScpd.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/scpd/RenderingControlScpd.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/scpd/ConnectionManagerScpd.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/scpd/Scpd.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/DeviceDescriptionTest.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/ScpdTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceDescriptionTest — the root description is what Windows validates before listing the renderer.
 *
 * WHY: Missing `X_DLNADOC`, a non-standard serviceId or a relative URL Windows cannot resolve all make
 * the TV silently absent from "Cast to Device".
 */
class DeviceDescriptionTest {

    private val base = "http://192.168.1.185:49494"
    private val xml = DeviceDescription(
        friendlyName = "Living Room <TV>",
        udn = "uuid:12345678-1234-1234-1234-123456789abc",
        modelNumber = "1.0.0"
    ).xml(base)

    @Test
    fun `is well-formed XML with a root device`() {
        val doc = SecureXml.parse(xml)
        assertNotNull(doc)
        assertEquals("root", SecureXml.localNameOf(doc!!.documentElement))
    }

    @Test
    fun `carries identity, DLNA doc marker and escaped friendly name`() {
        assertTrue(xml.contains("<deviceType>${DlnaConstants.DEVICE_TYPE}</deviceType>"))
        assertTrue(xml.contains("<friendlyName>Living Room &lt;TV&gt;</friendlyName>"))
        assertTrue(xml.contains("<UDN>uuid:12345678-1234-1234-1234-123456789abc</UDN>"))
        assertTrue(xml.contains("<dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">DMR-1.50</dlna:X_DLNADOC>"))
        assertTrue(xml.contains("<modelNumber>1.0.0</modelNumber>"))
    }

    @Test
    fun `lists the three services with standard ids and absolute URLs`() {
        for (service in UpnpService.entries) {
            assertTrue(xml.contains("<serviceId>${service.serviceId}</serviceId>"))
            assertTrue(xml.contains("<serviceType>${service.serviceType}</serviceType>"))
            assertTrue(xml.contains("<SCPDURL>$base${service.scpdPath}</SCPDURL>"))
            assertTrue(xml.contains("<controlURL>$base${service.controlPath}</controlURL>"))
            assertTrue(xml.contains("<eventSubURL>$base${service.eventPath}</eventSubURL>"))
        }
    }

    @Test
    fun `advertises a PNG icon`() {
        assertTrue(xml.contains("<mimetype>image/png</mimetype>"))
        assertTrue(xml.contains("<url>$base${DlnaConstants.ICON_PATH}</url>"))
    }
}
```

```kotlin
package com.phairplay.dlna

import com.phairplay.dlna.scpd.Scpd
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScpdTest — every service description must parse and declare the actions/variables we implement.
 *
 * WHY: Control points read the SCPD to learn which actions exist; a typo here means Windows never calls
 * the action, and an un-evented `LastChange` means it never subscribes.
 */
class ScpdTest {

    @Test
    fun `all three SCPDs are well-formed`() {
        for (service in UpnpService.entries) {
            assertNotNull("SCPD for $service", SecureXml.parse(Scpd.forService(service)))
        }
    }

    @Test
    fun `AVTransport declares the transport actions and an evented LastChange`() {
        val xml = Scpd.forService(UpnpService.AV_TRANSPORT)
        for (action in listOf("SetAVTransportURI", "SetNextAVTransportURI", "Play", "Pause", "Stop", "Seek",
            "Next", "Previous", "GetMediaInfo", "GetTransportInfo", "GetPositionInfo",
            "GetDeviceCapabilities", "GetTransportSettings", "GetCurrentTransportActions")) {
            assertTrue("missing $action", xml.contains("<name>$action</name>"))
        }
        assertTrue(xml.contains("<stateVariable sendEvents=\"yes\"><name>LastChange</name>"))
        assertTrue(xml.contains("<allowedValue>PAUSED_PLAYBACK</allowedValue>"))
    }

    @Test
    fun `RenderingControl declares volume and mute with a 0-100 range`() {
        val xml = Scpd.forService(UpnpService.RENDERING_CONTROL)
        for (action in listOf("ListPresets", "SelectPreset", "GetVolume", "SetVolume", "GetMute", "SetMute")) {
            assertTrue("missing $action", xml.contains("<name>$action</name>"))
        }
        assertTrue(xml.contains("<minimum>0</minimum><maximum>100</maximum>"))
    }

    @Test
    fun `ConnectionManager declares protocol info and connection actions`() {
        val xml = Scpd.forService(UpnpService.CONNECTION_MANAGER)
        for (action in listOf("GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo")) {
            assertTrue("missing $action", xml.contains("<name>$action</name>"))
        }
        assertTrue(xml.contains("<stateVariable sendEvents=\"yes\"><name>SinkProtocolInfo</name>"))
    }
}
```

- [ ] **Step 2: Run to verify they fail** — expected `Unresolved reference: DeviceDescription` / `Scpd`.

- [ ] **Step 3: Create `DeviceDescription.kt`**

```kotlin
package com.phairplay.dlna

/**
 * DeviceDescription — the UPnP root device description XML served at `/description.xml`.
 *
 * WHY: This is the document a control point fetches from the SSDP LOCATION to decide whether we are a
 * usable MediaRenderer. Windows additionally requires the DLNA `X_DLNADOC` DMR marker and an icon.
 *
 * HOW: `DeviceDescription(friendlyName, udn, versionName).xml("http://ip:port")` — URLs are absolute so
 * every control point resolves them identically.
 */
class DeviceDescription(
    private val friendlyName: String,
    private val udn: String,
    private val modelNumber: String
) {
    fun xml(baseUrl: String): String {
        val services = UpnpService.entries.joinToString("") { service ->
            "<service>" +
                "<serviceType>${service.serviceType}</serviceType>" +
                "<serviceId>${service.serviceId}</serviceId>" +
                "<SCPDURL>$baseUrl${service.scpdPath}</SCPDURL>" +
                "<controlURL>$baseUrl${service.controlPath}</controlURL>" +
                "<eventSubURL>$baseUrl${service.eventPath}</eventSubURL>" +
                "</service>"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<device>" +
            "<deviceType>${DlnaConstants.DEVICE_TYPE}</deviceType>" +
            "<friendlyName>${SecureXml.escape(friendlyName)}</friendlyName>" +
            "<manufacturer>$MANUFACTURER</manufacturer>" +
            "<manufacturerURL>$PROJECT_URL</manufacturerURL>" +
            "<modelDescription>$MODEL_DESCRIPTION</modelDescription>" +
            "<modelName>$MODEL_NAME</modelName>" +
            "<modelNumber>${SecureXml.escape(modelNumber)}</modelNumber>" +
            "<modelURL>$PROJECT_URL</modelURL>" +
            "<UDN>${SecureXml.escape(udn)}</UDN>" +
            "<dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">$DLNA_DOC</dlna:X_DLNADOC>" +
            "<iconList><icon><mimetype>image/png</mimetype>" +
            "<width>${DlnaConstants.ICON_SIZE_PX}</width><height>${DlnaConstants.ICON_SIZE_PX}</height>" +
            "<depth>24</depth><url>$baseUrl${DlnaConstants.ICON_PATH}</url></icon></iconList>" +
            "<serviceList>$services</serviceList>" +
            "</device></root>"
    }

    companion object {
        const val MANUFACTURER = "PhairPlay"
        const val MODEL_NAME = "PhairPlay"
        const val MODEL_DESCRIPTION = "PhairPlay AirPlay/DLNA receiver for Android TV"
        const val PROJECT_URL = "https://github.com/mazer666/PhairPlay"
        const val DLNA_DOC = "DMR-1.50"
    }
}
```

- [ ] **Step 4: Create the SCPD builder and the three descriptions**

`scpd/ScpdBuilder.kt`:
```kotlin
package com.phairplay.dlna.scpd

/**
 * ScpdBuilder — a tiny DSL that emits a UPnP Service Control Protocol Description (SCPD) document.
 *
 * WHY: The three SCPDs are ~150 lines of repetitive XML each; generating them from a compact list of
 * actions and state variables keeps every file under the 400-line rule and makes the XML impossible to
 * mistype (argument ↔ state-variable links are written once).
 *
 * HOW: `scpd { action("Play") { input("InstanceID", "A_ARG_TYPE_InstanceID") }; variable("LastChange", evented = true) }`
 */
class ScpdBuilder {

    private class Argument(val name: String, val direction: String, val variable: String)
    private class Action(val name: String, val arguments: List<Argument>)
    private class Variable(
        val name: String, val dataType: String, val evented: Boolean,
        val allowed: List<String>, val range: IntRange?, val default: String?
    )

    class ActionScope {
        internal val arguments = mutableListOf<Argument>()
        fun input(name: String, variable: String) { arguments += Argument(name, "in", variable) }
        fun output(name: String, variable: String) { arguments += Argument(name, "out", variable) }
    }

    private val actions = mutableListOf<Action>()
    private val variables = mutableListOf<Variable>()

    fun action(name: String, block: ActionScope.() -> Unit = {}) {
        actions += Action(name, ActionScope().apply(block).arguments)
    }

    fun variable(
        name: String,
        dataType: String = "string",
        evented: Boolean = false,
        allowed: List<String> = emptyList(),
        range: IntRange? = null,
        default: String? = null
    ) {
        variables += Variable(name, dataType, evented, allowed, range, default)
    }

    fun build(): String {
        val actionXml = actions.joinToString("") { action ->
            val args = action.arguments.joinToString("") { arg ->
                "<argument><name>${arg.name}</name><direction>${arg.direction}</direction>" +
                    "<relatedStateVariable>${arg.variable}</relatedStateVariable></argument>"
            }
            val list = if (args.isEmpty()) "" else "<argumentList>$args</argumentList>"
            "<action><name>${action.name}</name>$list</action>"
        }
        val variableXml = variables.joinToString("") { variable ->
            val allowed = if (variable.allowed.isEmpty()) "" else
                "<allowedValueList>" + variable.allowed.joinToString("") { "<allowedValue>$it</allowedValue>" } + "</allowedValueList>"
            val range = variable.range?.let {
                "<allowedValueRange><minimum>${it.first}</minimum><maximum>${it.last}</maximum><step>1</step></allowedValueRange>"
            } ?: ""
            val default = variable.default?.let { "<defaultValue>$it</defaultValue>" } ?: ""
            "<stateVariable sendEvents=\"${if (variable.evented) "yes" else "no"}\"><name>${variable.name}</name>" +
                "<dataType>${variable.dataType}</dataType>$default$allowed$range</stateVariable>"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>$actionXml</actionList>" +
            "<serviceStateTable>$variableXml</serviceStateTable>" +
            "</scpd>"
    }
}

fun scpd(block: ScpdBuilder.() -> Unit): String = ScpdBuilder().apply(block).build()
```

`scpd/AvTransportScpd.kt`:
```kotlin
package com.phairplay.dlna.scpd

/**
 * AvTransportScpd — AVTransport:1 service description, trimmed to the actions this renderer implements.
 *
 * WHY: Control points only call actions declared here; declaring what we do not implement would invite
 * 401 faults, declaring less would hide Seek/Next from BubbleUPnP and VLC.
 */
object AvTransportScpd {
    private const val INSTANCE = "A_ARG_TYPE_InstanceID"

    val XML: String by lazy {
        scpd {
            action("SetAVTransportURI") {
                input("InstanceID", INSTANCE); input("CurrentURI", "AVTransportURI"); input("CurrentURIMetaData", "AVTransportURIMetaData")
            }
            action("SetNextAVTransportURI") {
                input("InstanceID", INSTANCE); input("NextURI", "NextAVTransportURI"); input("NextURIMetaData", "NextAVTransportURIMetaData")
            }
            action("GetMediaInfo") {
                input("InstanceID", INSTANCE); output("NrTracks", "NumberOfTracks"); output("MediaDuration", "CurrentMediaDuration")
                output("CurrentURI", "AVTransportURI"); output("CurrentURIMetaData", "AVTransportURIMetaData")
                output("NextURI", "NextAVTransportURI"); output("NextURIMetaData", "NextAVTransportURIMetaData")
                output("PlayMedium", "PlaybackStorageMedium"); output("RecordMedium", "RecordStorageMedium"); output("WriteStatus", "RecordMediumWriteStatus")
            }
            action("GetTransportInfo") {
                input("InstanceID", INSTANCE); output("CurrentTransportState", "TransportState")
                output("CurrentTransportStatus", "TransportStatus"); output("CurrentSpeed", "TransportPlaySpeed")
            }
            action("GetPositionInfo") {
                input("InstanceID", INSTANCE); output("Track", "CurrentTrack"); output("TrackDuration", "CurrentTrackDuration")
                output("TrackMetaData", "CurrentTrackMetaData"); output("TrackURI", "CurrentTrackURI")
                output("RelTime", "RelativeTimePosition"); output("AbsTime", "AbsoluteTimePosition")
                output("RelCount", "RelativeCounterPosition"); output("AbsCount", "AbsoluteCounterPosition")
            }
            action("GetDeviceCapabilities") {
                input("InstanceID", INSTANCE); output("PlayMedia", "PossiblePlaybackStorageMedia")
                output("RecMedia", "PossibleRecordStorageMedia"); output("RecQualityModes", "PossibleRecordQualityModes")
            }
            action("GetTransportSettings") {
                input("InstanceID", INSTANCE); output("PlayMode", "CurrentPlayMode"); output("RecQualityMode", "CurrentRecordQualityMode")
            }
            action("Stop") { input("InstanceID", INSTANCE) }
            action("Play") { input("InstanceID", INSTANCE); input("Speed", "TransportPlaySpeed") }
            action("Pause") { input("InstanceID", INSTANCE) }
            action("Seek") { input("InstanceID", INSTANCE); input("Unit", "A_ARG_TYPE_SeekMode"); input("Target", "A_ARG_TYPE_SeekTarget") }
            action("Next") { input("InstanceID", INSTANCE) }
            action("Previous") { input("InstanceID", INSTANCE) }
            action("GetCurrentTransportActions") { input("InstanceID", INSTANCE); output("Actions", "CurrentTransportActions") }

            variable("TransportState", allowed = listOf("STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "NO_MEDIA_PRESENT"))
            variable("TransportStatus", allowed = listOf("OK", "ERROR_OCCURRED"))
            variable("PlaybackStorageMedium", allowed = listOf("NONE", "NETWORK"))
            variable("RecordStorageMedium", allowed = listOf("NOT_IMPLEMENTED"))
            variable("PossiblePlaybackStorageMedia")
            variable("PossibleRecordStorageMedia")
            variable("CurrentPlayMode", allowed = listOf("NORMAL"), default = "NORMAL")
            variable("TransportPlaySpeed", allowed = listOf("1"))
            variable("RecordMediumWriteStatus", allowed = listOf("NOT_IMPLEMENTED"))
            variable("CurrentRecordQualityMode", allowed = listOf("NOT_IMPLEMENTED"))
            variable("PossibleRecordQualityModes")
            variable("NumberOfTracks", dataType = "ui4", range = 0..1)
            variable("CurrentTrack", dataType = "ui4", range = 0..1)
            variable("CurrentTrackDuration")
            variable("CurrentMediaDuration")
            variable("CurrentTrackMetaData")
            variable("CurrentTrackURI")
            variable("AVTransportURI")
            variable("AVTransportURIMetaData")
            variable("NextAVTransportURI")
            variable("NextAVTransportURIMetaData")
            variable("RelativeTimePosition")
            variable("AbsoluteTimePosition")
            variable("RelativeCounterPosition", dataType = "i4")
            variable("AbsoluteCounterPosition", dataType = "i4")
            variable("CurrentTransportActions")
            variable("LastChange", evented = true)
            variable("A_ARG_TYPE_SeekMode", allowed = listOf("TRACK_NR", "REL_TIME", "ABS_TIME"))
            variable("A_ARG_TYPE_SeekTarget")
            variable(INSTANCE, dataType = "ui4")
        }
    }
}
```

`scpd/RenderingControlScpd.kt`:
```kotlin
package com.phairplay.dlna.scpd

/**
 * RenderingControlScpd — RenderingControl:1 description: Master-channel volume, mute and one preset.
 *
 * WHY: Windows' "Cast to Device" volume slider and BubbleUPnP's mute button call exactly these actions.
 */
object RenderingControlScpd {
    private const val INSTANCE = "A_ARG_TYPE_InstanceID"
    private const val CHANNEL = "A_ARG_TYPE_Channel"

    val XML: String by lazy {
        scpd {
            action("ListPresets") { input("InstanceID", INSTANCE); output("CurrentPresetNameList", "PresetNameList") }
            action("SelectPreset") { input("InstanceID", INSTANCE); input("PresetName", "A_ARG_TYPE_PresetName") }
            action("GetVolume") { input("InstanceID", INSTANCE); input("Channel", CHANNEL); output("CurrentVolume", "Volume") }
            action("SetVolume") { input("InstanceID", INSTANCE); input("Channel", CHANNEL); input("DesiredVolume", "Volume") }
            action("GetMute") { input("InstanceID", INSTANCE); input("Channel", CHANNEL); output("CurrentMute", "Mute") }
            action("SetMute") { input("InstanceID", INSTANCE); input("Channel", CHANNEL); input("DesiredMute", "Mute") }

            variable("PresetNameList", default = "FactoryDefaults")
            variable("LastChange", evented = true)
            variable("Volume", dataType = "ui2", range = 0..100)
            variable("Mute", dataType = "boolean")
            variable(CHANNEL, allowed = listOf("Master"))
            variable(INSTANCE, dataType = "ui4")
            variable("A_ARG_TYPE_PresetName", allowed = listOf("FactoryDefaults"))
        }
    }
}
```

`scpd/ConnectionManagerScpd.kt`:
```kotlin
package com.phairplay.dlna.scpd

/**
 * ConnectionManagerScpd — ConnectionManager:1 description exposing the sink protocolInfo list.
 *
 * WHY: Windows calls GetProtocolInfo before casting to decide which formats it may send natively.
 */
object ConnectionManagerScpd {
    val XML: String by lazy {
        scpd {
            action("GetProtocolInfo") { output("Source", "SourceProtocolInfo"); output("Sink", "SinkProtocolInfo") }
            action("GetCurrentConnectionIDs") { output("ConnectionIDs", "CurrentConnectionIDs") }
            action("GetCurrentConnectionInfo") {
                input("ConnectionID", "A_ARG_TYPE_ConnectionID"); output("RcsID", "A_ARG_TYPE_RcsID")
                output("AVTransportID", "A_ARG_TYPE_AVTransportID"); output("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo")
                output("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"); output("PeerConnectionID", "A_ARG_TYPE_ConnectionID")
                output("Direction", "A_ARG_TYPE_Direction"); output("Status", "A_ARG_TYPE_ConnectionStatus")
            }

            variable("SourceProtocolInfo", evented = true)
            variable("SinkProtocolInfo", evented = true)
            variable("CurrentConnectionIDs", evented = true)
            variable("A_ARG_TYPE_ConnectionStatus", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown"))
            variable("A_ARG_TYPE_ConnectionManager")
            variable("A_ARG_TYPE_Direction", allowed = listOf("Input", "Output"))
            variable("A_ARG_TYPE_ProtocolInfo")
            variable("A_ARG_TYPE_ConnectionID", dataType = "i4")
            variable("A_ARG_TYPE_AVTransportID", dataType = "i4")
            variable("A_ARG_TYPE_RcsID", dataType = "i4")
        }
    }
}
```

`scpd/Scpd.kt`:
```kotlin
package com.phairplay.dlna.scpd

import com.phairplay.dlna.UpnpService

/**
 * Scpd — lookup from a [UpnpService] to its description document.
 *
 * WHY: The HTTP router serves `/scpd/{Service}.xml`; this keeps the mapping in one place.
 */
object Scpd {
    fun forService(service: UpnpService): String = when (service) {
        UpnpService.AV_TRANSPORT -> AvTransportScpd.XML
        UpnpService.RENDERING_CONTROL -> RenderingControlScpd.XML
        UpnpService.CONNECTION_MANAGER -> ConnectionManagerScpd.XML
    }
}
```

- [ ] **Step 5: Run the tests** — expected BUILD SUCCESSFUL, `DeviceDescriptionTest` 4 + `ScpdTest` 4 passed.

- [ ] **Step 6: Hand-off** — `feat(dlna): root device description and SCPD documents`

---

### Task 6: Protocol-info sink list and DIDL-Lite metadata

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/ProtocolInfoList.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/DidlLite.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/ProtocolInfoListTest.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/DidlLiteTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProtocolInfoListTest — what we advertise as playable, and how an incoming item is classified.
 *
 * WHY: Windows transcodes anything not in the sink list, and the class decides whether the item goes to
 * MediaPlayer (video/audio) or the photo overlay (image).
 */
class ProtocolInfoListTest {

    @Test
    fun `sink list contains the MediaPlayer-playable formats and DLNA profiles`() {
        val sink = ProtocolInfoList.sinkString()
        assertTrue(sink.contains("http-get:*:video/mp4:*"))
        assertTrue(sink.contains("http-get:*:audio/mpeg:*"))
        assertTrue(sink.contains("http-get:*:image/jpeg:*"))
        assertTrue(sink.contains("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3"))
        assertTrue(sink.contains("http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG"))
        assertFalse(sink.contains("audio/L16"))
        assertFalse(sink.contains("x-ms-wma"))
    }

    @Test
    fun `protocolInfo MIME wins over class and extension`() {
        assertEquals(MediaClass.AUDIO, ProtocolInfoList.classify("http-get:*:audio/mpeg:*", "object.item.videoItem", "http://x/a.mp4"))
    }

    @Test
    fun `upnp class is used when protocolInfo has a wildcard MIME`() {
        assertEquals(MediaClass.IMAGE, ProtocolInfoList.classify("http-get:*:*:*", "object.item.imageItem.photo", "http://x/blob"))
    }

    @Test
    fun `extension is the last resort`() {
        assertEquals(MediaClass.VIDEO, ProtocolInfoList.classify(null, null, "http://x/movie.MKV?token=1"))
        assertEquals(MediaClass.AUDIO, ProtocolInfoList.classify(null, null, "http://x/song.flac"))
        assertEquals(MediaClass.IMAGE, ProtocolInfoList.classify(null, null, "http://x/pic.png"))
    }

    @Test
    fun `unknown item is null`() {
        assertNull(ProtocolInfoList.classify(null, null, "http://x/blob"))
        assertNull(ProtocolInfoList.classify("http-get:*:application/octet-stream:*", null, "http://x/blob"))
    }
}
```

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DidlLiteTest — metadata Windows/BubbleUPnP attach to SetAVTransportURI feeds the now-playing card.
 *
 * WHY: Title/artist/album/art must be read from the DIDL-Lite item; malformed metadata must degrade
 * to "no metadata", never to a fault.
 */
class DidlLiteTest {

    private val didl = """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
            xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
          <item id="1" parentID="0" restricted="1">
            <dc:title>Track One</dc:title>
            <upnp:artist>Some Artist</upnp:artist>
            <dc:creator>Creator Fallback</dc:creator>
            <upnp:album>Album X</upnp:album>
            <upnp:albumArtURI>http://192.168.1.10:2869/art.jpg</upnp:albumArtURI>
            <upnp:class>object.item.audioItem.musicTrack</upnp:class>
            <res protocolInfo="http-get:*:audio/mpeg:DLNA.ORG_PN=MP3" duration="0:03:25.000">http://192.168.1.10:2869/track.mp3</res>
          </item>
        </DIDL-Lite>
    """.trimIndent()

    @Test
    fun `parses title artist album art class protocolInfo and duration`() {
        val item = DidlLite.parse(didl)
        assertEquals("Track One", item.title)
        assertEquals("Some Artist", item.artist)
        assertEquals("Album X", item.album)
        assertEquals("http://192.168.1.10:2869/art.jpg", item.albumArtUri)
        assertEquals("object.item.audioItem.musicTrack", item.upnpClass)
        assertEquals("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3", item.protocolInfo)
        assertEquals(205_000L, item.durationMs)
    }

    @Test
    fun `creator is used when artist is absent`() {
        val item = DidlLite.parse(didl.replace("<upnp:artist>Some Artist</upnp:artist>", ""))
        assertEquals("Creator Fallback", item.artist)
    }

    @Test
    fun `empty null and malformed metadata give an empty item`() {
        assertEquals(DidlItem.EMPTY, DidlLite.parse(""))
        assertEquals(DidlItem.EMPTY, DidlLite.parse(null))
        assertEquals(DidlItem.EMPTY, DidlLite.parse("<DIDL-Lite><item>"))
        assertNull(DidlLite.parse("<DIDL-Lite xmlns=\"x\"><item></item></DIDL-Lite>").title)
    }
}
```

- [ ] **Step 2: Run to verify they fail** — expected `Unresolved reference: ProtocolInfoList` / `DidlLite`.

- [ ] **Step 3: Create the production files**

`ProtocolInfoList.kt`:
```kotlin
package com.phairplay.dlna

import java.util.Locale

/**
 * ProtocolInfoList — the formats this renderer says it can play, and how an incoming item is classified.
 *
 * WHY: ConnectionManager.GetProtocolInfo returns the sink list; Windows only sends formats found there and
 * transcodes the rest. We list exactly what Android `MediaPlayer` decodes (no raw LPCM, no WMA/WMV). The
 * classification decides video/audio (MediaPlayer) vs image (photo overlay) for SetAVTransportURI.
 *
 * HOW: `ProtocolInfoList.sinkString()`; `ProtocolInfoList.classify(protocolInfo, upnpClass, uri)`.
 */
object ProtocolInfoList {

    private val VIDEO_MIME = listOf(
        "video/mp4", "video/x-matroska", "video/webm", "video/3gpp", "video/mpeg", "video/vnd.dlna.mpeg-tts"
    )
    private val AUDIO_MIME = listOf(
        "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/flac", "audio/x-wav", "audio/wav", "audio/ogg"
    )
    private val IMAGE_MIME = listOf("image/jpeg", "image/png", "image/gif", "image/webp")

    /** DLNA profile names so Windows recognises the common containers as native. */
    private val DLNA_PROFILES = listOf(
        "video/mp4" to "AVC_MP4_BL_CIF15_AAC_520",
        "video/mp4" to "AVC_MP4_MP_SD_AAC_MULT5",
        "video/mp4" to "AVC_MP4_HP_HD_AAC",
        "audio/mpeg" to "MP3",
        "audio/mp4" to "AAC_ISO",
        "audio/mp4" to "AAC_ISO_320",
        "image/jpeg" to "JPEG_LRG",
        "image/jpeg" to "JPEG_MED",
        "image/jpeg" to "JPEG_SM",
        "image/png" to "PNG_LRG"
    )

    private val EXTENSIONS: Map<String, MediaClass> = mapOf(
        "mp4" to MediaClass.VIDEO, "m4v" to MediaClass.VIDEO, "mkv" to MediaClass.VIDEO, "webm" to MediaClass.VIDEO,
        "3gp" to MediaClass.VIDEO, "mpg" to MediaClass.VIDEO, "mpeg" to MediaClass.VIDEO, "ts" to MediaClass.VIDEO,
        "mov" to MediaClass.VIDEO,
        "mp3" to MediaClass.AUDIO, "m4a" to MediaClass.AUDIO, "aac" to MediaClass.AUDIO, "flac" to MediaClass.AUDIO,
        "wav" to MediaClass.AUDIO, "ogg" to MediaClass.AUDIO, "oga" to MediaClass.AUDIO,
        "jpg" to MediaClass.IMAGE, "jpeg" to MediaClass.IMAGE, "png" to MediaClass.IMAGE, "gif" to MediaClass.IMAGE,
        "webp" to MediaClass.IMAGE
    )

    val sink: List<String> =
        (VIDEO_MIME + AUDIO_MIME + IMAGE_MIME).map { "http-get:*:$it:*" } +
            DLNA_PROFILES.map { (mime, profile) -> "http-get:*:$mime:DLNA.ORG_PN=$profile" }

    fun sinkString(): String = sink.joinToString(",")

    /** protocolInfo MIME first, then the DIDL `upnp:class`, then the URI extension; null if unknown. */
    fun classify(protocolInfo: String?, upnpClass: String?, uri: String): MediaClass? {
        mimeFromProtocolInfo(protocolInfo)?.let { mime -> classifyMime(mime)?.let { return it } }
        upnpClass?.let { cls ->
            when {
                cls.startsWith("object.item.videoItem") -> return MediaClass.VIDEO
                cls.startsWith("object.item.audioItem") -> return MediaClass.AUDIO
                cls.startsWith("object.item.imageItem") -> return MediaClass.IMAGE
            }
        }
        val extension = uri.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.US)
        return EXTENSIONS[extension]
    }

    fun classifyMime(mime: String): MediaClass? = when {
        mime.startsWith("video/") -> MediaClass.VIDEO
        mime.startsWith("audio/") -> MediaClass.AUDIO
        mime.startsWith("image/") -> MediaClass.IMAGE
        else -> null
    }

    /** Third field of `protocol:network:contentFormat:additionalInfo`, or null for `*`/missing. */
    fun mimeFromProtocolInfo(protocolInfo: String?): String? {
        val parts = protocolInfo?.split(":") ?: return null
        if (parts.size < 3) return null
        val mime = parts[2].trim().lowercase(Locale.US)
        return if (mime.isEmpty() || mime == "*") null else mime
    }
}
```

`DidlLite.kt`:
```kotlin
package com.phairplay.dlna

import org.w3c.dom.Element

/**
 * DidlItem — the fields we use from a DIDL-Lite item: what to show and how to classify it.
 */
data class DidlItem(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtUri: String? = null,
    val upnpClass: String? = null,
    val protocolInfo: String? = null,
    val durationMs: Long? = null
) {
    companion object {
        val EMPTY = DidlItem()
    }
}

/**
 * DidlLite — parses the `CurrentURIMetaData` DIDL-Lite document a control point sends with a URI.
 *
 * WHY: Windows and BubbleUPnP describe the item (title, artist, album art, class, MIME, duration) here;
 * the now-playing card and the media classification depend on it. Anything malformed must degrade to
 * [DidlItem.EMPTY] rather than fail the action.
 *
 * HOW: `DidlLite.parse(metadataXml)` — never throws.
 */
object DidlLite {

    fun parse(xml: String?): DidlItem {
        if (xml.isNullOrBlank()) return DidlItem.EMPTY
        val document = SecureXml.parse(xml) ?: return DidlItem.EMPTY
        val root = document.documentElement ?: return DidlItem.EMPTY
        val item = SecureXml.childElements(root).firstOrNull {
            val name = SecureXml.localNameOf(it)
            name == "item" || name == "container"
        } ?: return DidlItem.EMPTY
        val res = SecureXml.childElements(item).firstOrNull { SecureXml.localNameOf(it) == "res" }
        return DidlItem(
            title = text(item, "title"),
            artist = text(item, "artist") ?: text(item, "creator"),
            album = text(item, "album"),
            albumArtUri = text(item, "albumArtURI"),
            upnpClass = text(item, "class"),
            protocolInfo = res?.getAttribute("protocolInfo")?.trim()?.ifEmpty { null },
            durationMs = res?.getAttribute("duration")?.let { UpnpTime.parse(it) }
        )
    }

    private fun text(parent: Element, localName: String): String? =
        SecureXml.firstText(parent, localName)?.trim()?.ifEmpty { null }
}
```

- [ ] **Step 4: Run the tests** — expected BUILD SUCCESSFUL.

- [ ] **Step 5: Hand-off** — `feat(dlna): sink protocolInfo list, media classification and DIDL-Lite parser`

---

### Task 7: Renderer contract and the AVTransport service

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/RendererControl.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/AvTransportService.kt`
- Test helper: `app/src/test/kotlin/com/phairplay/dlna/FakeRendererControl.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/AvTransportServiceTest.kt`

- [ ] **Step 1: Write the fake and the failing test**

`FakeRendererControl.kt`:
```kotlin
package com.phairplay.dlna

/**
 * FakeRendererControl — records every call the UPnP services make and serves a settable snapshot.
 *
 * WHY: Lets AvTransportService/RenderingControlService be tested as pure protocol logic, with no
 * MediaPlayer and no threads.
 */
class FakeRendererControl : RendererControl {
    val calls = mutableListOf<String>()
    var snapshot = RendererSnapshot()
    var loadError: UpnpError? = null

    override fun load(uri: String, metadata: String, senderAgent: String?) {
        loadError?.let { throw it }
        calls += "load:$uri:${senderAgent ?: "-"}"
        snapshot = snapshot.copy(state = TransportState.TRANSITIONING, currentUri = uri, currentMetadata = metadata)
    }
    override fun setNext(uri: String, metadata: String) { calls += "next:$uri"; snapshot = snapshot.copy(nextUri = uri, nextMetadata = metadata) }
    override fun play() { calls += "play"; snapshot = snapshot.copy(state = TransportState.PLAYING) }
    override fun pause() { calls += "pause"; snapshot = snapshot.copy(state = TransportState.PAUSED_PLAYBACK) }
    override fun stop() { calls += "stop"; snapshot = snapshot.copy(state = TransportState.STOPPED, positionMs = 0) }
    override fun seekTo(positionMs: Long) { calls += "seek:$positionMs"; snapshot = snapshot.copy(positionMs = positionMs) }
    override fun next() { calls += "advance" }
    override fun setVolume(volume: Int) { calls += "volume:$volume"; snapshot = snapshot.copy(volume = volume) }
    override fun setMute(mute: Boolean) { calls += "mute:$mute"; snapshot = snapshot.copy(mute = mute) }
    override fun snapshot(): RendererSnapshot = snapshot
}
```

`AvTransportServiceTest.kt`:
```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AvTransportServiceTest — every AVTransport:1 action against the renderer contract.
 *
 * WHY: This is the protocol surface Windows, BubbleUPnP and VLC drive. Wrong 701/402 behaviour makes
 * senders show errors; wrong GetPositionInfo formatting freezes their progress bars.
 */
class AvTransportServiceTest {

    private val control = FakeRendererControl()
    private val service = AvTransportService(control)
    private val ctx = SoapContext(userAgent = "Microsoft-Windows/10.0 UPnP/1.0")
    private val uri = "http://192.168.1.10:2869/movie.mp4"

    private fun call(action: String, vararg args: Pair<String, String>) =
        service.handle(action, mapOf("InstanceID" to "0", *args), ctx)

    private fun expectFault(code: Int, block: () -> Unit) {
        try { block(); fail("expected UPnP fault $code") } catch (e: UpnpError) { assertEquals(code, e.code) }
    }

    @Test
    fun `SetAVTransportURI loads the item and forwards the sender agent`() {
        call("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to "<DIDL-Lite/>")
        assertEquals(listOf("load:$uri:Microsoft-Windows/10.0 UPnP/1.0"), control.calls)
        assertEquals("<DIDL-Lite/>", control.snapshot.currentMetadata)
    }

    @Test
    fun `SetAVTransportURI without CurrentURI is 402`() {
        expectFault(402) { call("SetAVTransportURI") }
    }

    @Test
    fun `Play with no media is 701 and after a load calls the renderer`() {
        expectFault(701) { call("Play", "Speed" to "1") }
        call("SetAVTransportURI", "CurrentURI" to uri)
        call("Play", "Speed" to "1")
        assertTrue(control.calls.contains("play"))
    }

    @Test
    fun `Pause is 701 unless playing or transitioning`() {
        expectFault(701) { call("Pause") }
        control.snapshot = RendererSnapshot(state = TransportState.STOPPED, currentUri = uri)
        expectFault(701) { call("Pause") }
        control.snapshot = control.snapshot.copy(state = TransportState.PLAYING)
        call("Pause")
        assertTrue(control.calls.contains("pause"))
    }

    @Test
    fun `Stop always succeeds`() {
        call("Stop")
        assertEquals(listOf("stop"), control.calls)
    }

    @Test
    fun `Seek REL_TIME parses the target and TRACK_NR restarts`() {
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri, durationMs = 600_000)
        call("Seek", "Unit" to "REL_TIME", "Target" to "0:01:30")
        call("Seek", "Unit" to "TRACK_NR", "Target" to "1")
        assertEquals(listOf("seek:90000", "seek:0"), control.calls)
    }

    @Test
    fun `Seek rejects bad units targets and no media`() {
        expectFault(701) { call("Seek", "Unit" to "REL_TIME", "Target" to "0:00:10") }
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri)
        expectFault(402) { call("Seek", "Unit" to "REL_TIME", "Target" to "later") }
        expectFault(402) { call("Seek", "Unit" to "X_FOO", "Target" to "0:00:10") }
        expectFault(402) { call("Seek", "Unit" to "TRACK_NR", "Target" to "2") }
    }

    @Test
    fun `SetNextAVTransportURI stores and Next advances only when set`() {
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri)
        expectFault(701) { call("Next") }
        call("SetNextAVTransportURI", "NextURI" to "http://x/2.mp3", "NextURIMetaData" to "")
        call("Next")
        assertEquals(listOf("next:http://x/2.mp3", "advance"), control.calls)
    }

    @Test
    fun `Previous restarts the current item`() {
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri)
        call("Previous")
        assertEquals(listOf("seek:0"), control.calls)
    }

    @Test
    fun `GetTransportInfo and GetPositionInfo report formatted state`() {
        control.snapshot = RendererSnapshot(
            state = TransportState.PLAYING, currentUri = uri, currentMetadata = "<m/>",
            durationMs = 3_723_000, positionMs = 61_000
        )
        val transport = call("GetTransportInfo")
        assertEquals("PLAYING", transport["CurrentTransportState"])
        assertEquals("OK", transport["CurrentTransportStatus"])
        assertEquals("1", transport["CurrentSpeed"])

        val position = call("GetPositionInfo")
        assertEquals("1", position["Track"])
        assertEquals("1:02:03", position["TrackDuration"])
        assertEquals("0:01:01", position["RelTime"])
        assertEquals("0:01:01", position["AbsTime"])
        assertEquals(uri, position["TrackURI"])
        assertEquals("<m/>", position["TrackMetaData"])
        assertEquals("2147483647", position["RelCount"])
    }

    @Test
    fun `GetMediaInfo reflects media presence`() {
        assertEquals("0", call("GetMediaInfo")["NrTracks"])
        assertEquals("NONE", call("GetMediaInfo")["PlayMedium"])
        control.snapshot = RendererSnapshot(state = TransportState.STOPPED, currentUri = uri, nextUri = "http://x/2")
        val info = call("GetMediaInfo")
        assertEquals("1", info["NrTracks"])
        assertEquals("NETWORK", info["PlayMedium"])
        assertEquals("http://x/2", info["NextURI"])
    }

    @Test
    fun `GetCurrentTransportActions follows the state`() {
        assertEquals("", call("GetCurrentTransportActions")["Actions"])
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri, nextUri = "http://x/2")
        assertEquals("Pause,Stop,Seek,Next", call("GetCurrentTransportActions")["Actions"])
        control.snapshot = control.snapshot.copy(state = TransportState.PAUSED_PLAYBACK, nextUri = "")
        assertEquals("Play,Stop,Seek", call("GetCurrentTransportActions")["Actions"])
    }

    @Test
    fun `capabilities and settings are static`() {
        assertEquals("NETWORK,NONE", call("GetDeviceCapabilities")["PlayMedia"])
        assertEquals("NORMAL", call("GetTransportSettings")["PlayMode"])
    }

    @Test
    fun `non-zero InstanceID is 718 and unknown action is 401`() {
        expectFault(718) { service.handle("Play", mapOf("InstanceID" to "3"), ctx) }
        expectFault(401) { call("Record") }
    }
}
```

- [ ] **Step 2: Run to verify they fail** — expected `Unresolved reference: RendererControl`.

- [ ] **Step 3: Create `RendererControl.kt`**

```kotlin
package com.phairplay.dlna

/**
 * RendererSnapshot — an immutable view of the renderer's transport, media and volume state.
 *
 * WHY: The UPnP services and the LastChange encoder read state through this value object, so they never
 * touch player threads or locks.
 */
data class RendererSnapshot(
    val state: TransportState = TransportState.NO_MEDIA_PRESENT,
    /** `OK` or `ERROR_OCCURRED` (AVTransport TransportStatus). */
    val status: String = STATUS_OK,
    val currentUri: String = "",
    val currentMetadata: String = "",
    val nextUri: String = "",
    val nextMetadata: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val volume: Int = MAX_VOLUME,
    val mute: Boolean = false
) {
    val hasMedia: Boolean get() = currentUri.isNotEmpty()
    val numberOfTracks: Int get() = if (hasMedia) 1 else 0

    /** Comma-separated `CurrentTransportActions` a control point may call in this state. */
    val transportActions: String
        get() {
            val next = if (nextUri.isNotEmpty()) ",Next" else ""
            return when (state) {
                TransportState.NO_MEDIA_PRESENT -> ""
                TransportState.STOPPED -> "Play,Seek$next"
                TransportState.TRANSITIONING -> "Stop"
                TransportState.PLAYING -> "Pause,Stop,Seek$next"
                TransportState.PAUSED_PLAYBACK -> "Play,Stop,Seek$next"
            }
        }

    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_ERROR = "ERROR_OCCURRED"
        const val MAX_VOLUME = 100
    }
}

/**
 * RendererControl — what the UPnP services may ask the renderer to do.
 *
 * WHY: AvTransportService and RenderingControlService are pure protocol logic tested against a fake;
 * [MediaRenderer] is the real implementation that owns the player, the photo fetch and the UI callbacks.
 *
 * Contract: [load] classifies the item and may throw [UpnpError] (714 unknown type, 501 busy). State
 * changes are asynchronous — [snapshot] is the only source of truth.
 */
interface RendererControl {
    @Throws(UpnpError::class)
    fun load(uri: String, metadata: String, senderAgent: String?)
    fun setNext(uri: String, metadata: String)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    /** Starts the item set by [setNext] immediately. Callers check `snapshot().nextUri` first. */
    fun next()
    fun setVolume(volume: Int)
    fun setMute(mute: Boolean)
    fun snapshot(): RendererSnapshot
}
```

- [ ] **Step 4: Create `AvTransportService.kt`**

```kotlin
package com.phairplay.dlna

/**
 * AvTransportService — AVTransport:1 actions mapped onto [RendererControl].
 *
 * WHY: This is the service Windows "Cast to Device", BubbleUPnP and VLC drive to load and control media.
 * It enforces the state rules (701 faults) and the wire formats; the renderer does the actual work.
 *
 * HOW: Registered with [SoapDispatcher] for [UpnpService.AV_TRANSPORT]. Every action carries
 * `InstanceID`, which must be 0.
 */
class AvTransportService(private val control: RendererControl) : SoapActionHandler {

    override fun handle(action: String, args: Map<String, String>, context: SoapContext): Map<String, String> {
        SoapArgs.requireInstanceZero(args)
        return when (action) {
            "SetAVTransportURI" -> {
                control.load(SoapArgs.required(args, "CurrentURI"), args["CurrentURIMetaData"] ?: "", context.userAgent)
                emptyMap()
            }
            "SetNextAVTransportURI" -> {
                control.setNext(SoapArgs.required(args, "NextURI"), args["NextURIMetaData"] ?: "")
                emptyMap()
            }
            "Play" -> {
                if (!control.snapshot().hasMedia) throw UpnpError.transitionNotAvailable("no media loaded")
                control.play(); emptyMap()
            }
            "Pause" -> {
                val state = control.snapshot().state
                if (state != TransportState.PLAYING && state != TransportState.TRANSITIONING) {
                    throw UpnpError.transitionNotAvailable("not playing")
                }
                control.pause(); emptyMap()
            }
            "Stop" -> { control.stop(); emptyMap() }
            "Seek" -> seek(args)
            "Next" -> {
                if (control.snapshot().nextUri.isEmpty()) throw UpnpError.transitionNotAvailable("no next item")
                control.next(); emptyMap()
            }
            "Previous" -> {
                if (!control.snapshot().hasMedia) throw UpnpError.transitionNotAvailable("no media loaded")
                control.seekTo(0L); emptyMap()
            }
            "GetMediaInfo" -> mediaInfo()
            "GetTransportInfo" -> transportInfo()
            "GetPositionInfo" -> positionInfo()
            "GetDeviceCapabilities" -> mapOf(
                "PlayMedia" to "NETWORK,NONE", "RecMedia" to NOT_IMPLEMENTED, "RecQualityModes" to NOT_IMPLEMENTED
            )
            "GetTransportSettings" -> mapOf("PlayMode" to "NORMAL", "RecQualityMode" to NOT_IMPLEMENTED)
            "GetCurrentTransportActions" -> mapOf("Actions" to control.snapshot().transportActions)
            else -> throw UpnpError.invalidAction(action)
        }
    }

    private fun seek(args: Map<String, String>): Map<String, String> {
        if (!control.snapshot().hasMedia) throw UpnpError.transitionNotAvailable("no media loaded")
        val unit = SoapArgs.required(args, "Unit").trim()
        val target = SoapArgs.required(args, "Target").trim()
        when (unit) {
            "REL_TIME", "ABS_TIME" ->
                control.seekTo(UpnpTime.parse(target) ?: throw UpnpError.invalidArgs("bad time '$target'"))
            "TRACK_NR" ->
                if (target == "1") control.seekTo(0L) else throw UpnpError.invalidArgs("no track $target")
            else -> throw UpnpError.invalidArgs("unsupported seek unit $unit")
        }
        return emptyMap()
    }

    private fun mediaInfo(): Map<String, String> {
        val s = control.snapshot()
        return mapOf(
            "NrTracks" to s.numberOfTracks.toString(),
            "MediaDuration" to UpnpTime.format(s.durationMs),
            "CurrentURI" to s.currentUri,
            "CurrentURIMetaData" to s.currentMetadata,
            "NextURI" to s.nextUri,
            "NextURIMetaData" to s.nextMetadata,
            "PlayMedium" to if (s.hasMedia) "NETWORK" else "NONE",
            "RecordMedium" to NOT_IMPLEMENTED,
            "WriteStatus" to NOT_IMPLEMENTED
        )
    }

    private fun transportInfo(): Map<String, String> {
        val s = control.snapshot()
        return mapOf("CurrentTransportState" to s.state.name, "CurrentTransportStatus" to s.status, "CurrentSpeed" to PLAY_SPEED)
    }

    private fun positionInfo(): Map<String, String> {
        val s = control.snapshot()
        val position = UpnpTime.format(s.positionMs)
        return mapOf(
            "Track" to s.numberOfTracks.toString(),
            "TrackDuration" to UpnpTime.format(s.durationMs),
            "TrackMetaData" to s.currentMetadata,
            "TrackURI" to s.currentUri,
            "RelTime" to position,
            "AbsTime" to position,
            "RelCount" to COUNTER_NOT_IMPLEMENTED,
            "AbsCount" to COUNTER_NOT_IMPLEMENTED
        )
    }

    companion object {
        const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
        const val PLAY_SPEED = "1"
        /** AVTransport:1 says counters we do not track are reported as Int.MAX_VALUE. */
        const val COUNTER_NOT_IMPLEMENTED = "2147483647"
    }
}
```

- [ ] **Step 5: Run the tests** — expected BUILD SUCCESSFUL, `AvTransportServiceTest` 14 passed.

- [ ] **Step 6: Hand-off** — `feat(dlna): renderer contract and AVTransport service`

---

### Task 8: RenderingControl and ConnectionManager services

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/RenderingControlService.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/ConnectionManagerService.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/RenderingControlServiceTest.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/ConnectionManagerServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * RenderingControlServiceTest — Master-channel volume/mute and the single preset.
 *
 * WHY: The Windows volume slider sends SetVolume 0..100 on channel Master; anything else is a 402.
 * Volume is applied to the stream, never to the TV's system volume (that is the renderer's job).
 */
class RenderingControlServiceTest {

    private val control = FakeRendererControl()
    private val service = RenderingControlService(control)

    private fun call(action: String, vararg args: Pair<String, String>) =
        service.handle(action, mapOf("InstanceID" to "0", "Channel" to "Master", *args), SoapContext())

    private fun expectFault(code: Int, block: () -> Unit) {
        try { block(); fail("expected UPnP fault $code") } catch (e: UpnpError) { assertEquals(code, e.code) }
    }

    @Test
    fun `SetVolume clamps to 0-100 and GetVolume reads it back`() {
        call("SetVolume", "DesiredVolume" to "150")
        assertEquals("100", call("GetVolume")["CurrentVolume"])
        call("SetVolume", "DesiredVolume" to "37")
        assertEquals(listOf("volume:100", "volume:37"), control.calls)
    }

    @Test
    fun `SetMute accepts 1 0 true false and GetMute reports 1 or 0`() {
        call("SetMute", "DesiredMute" to "1")
        assertEquals("1", call("GetMute")["CurrentMute"])
        call("SetMute", "DesiredMute" to "false")
        assertEquals("0", call("GetMute")["CurrentMute"])
        expectFault(402) { call("SetMute", "DesiredMute" to "maybe") }
    }

    @Test
    fun `non-Master channel and non-numeric volume are 402`() {
        expectFault(402) { service.handle("GetVolume", mapOf("InstanceID" to "0", "Channel" to "LF"), SoapContext()) }
        expectFault(402) { call("SetVolume", "DesiredVolume" to "loud") }
    }

    @Test
    fun `presets list FactoryDefaults and selecting it resets volume and mute`() {
        assertEquals("FactoryDefaults", call("ListPresets")["CurrentPresetNameList"])
        call("SelectPreset", "PresetName" to "FactoryDefaults")
        assertEquals(listOf("volume:100", "mute:false"), control.calls)
        expectFault(402) { call("SelectPreset", "PresetName" to "Loud") }
    }

    @Test
    fun `non-zero InstanceID is 718 and unknown action is 401`() {
        expectFault(718) { service.handle("GetVolume", mapOf("InstanceID" to "1", "Channel" to "Master"), SoapContext()) }
        expectFault(401) { call("GetBrightness") }
    }
}
```

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * ConnectionManagerServiceTest — the sink list and the single static connection.
 *
 * WHY: Windows calls GetProtocolInfo first; an empty or malformed Sink means it offers to transcode
 * everything or refuses to cast.
 */
class ConnectionManagerServiceTest {

    private val service = ConnectionManagerService()

    private fun call(action: String, vararg args: Pair<String, String>) = service.handle(action, mapOf(*args), SoapContext())

    @Test
    fun `GetProtocolInfo returns an empty source and the sink list`() {
        val out = call("GetProtocolInfo")
        assertEquals("", out["Source"])
        assertEquals(ProtocolInfoList.sinkString(), out["Sink"])
    }

    @Test
    fun `connection ids and info describe the single input connection`() {
        assertEquals("0", call("GetCurrentConnectionIDs")["ConnectionIDs"])
        val info = call("GetCurrentConnectionInfo", "ConnectionID" to "0")
        assertEquals("0", info["RcsID"])
        assertEquals("0", info["AVTransportID"])
        assertEquals("Input", info["Direction"])
        assertEquals("OK", info["Status"])
        assertEquals("-1", info["PeerConnectionID"])
    }

    @Test
    fun `unknown connection is 706 and unknown action is 401`() {
        try { call("GetCurrentConnectionInfo", "ConnectionID" to "7"); fail() } catch (e: UpnpError) { assertEquals(706, e.code) }
        try { call("PrepareForConnection"); fail() } catch (e: UpnpError) { assertEquals(401, e.code) }
    }
}
```

- [ ] **Step 2: Run to verify they fail** — expected `Unresolved reference: RenderingControlService`.

- [ ] **Step 3: Create the production files**

`RenderingControlService.kt`:
```kotlin
package com.phairplay.dlna

import java.util.Locale

/**
 * RenderingControlService — RenderingControl:1: Master-channel volume, mute and one preset.
 *
 * WHY: Windows' "Cast to Device" volume slider and BubbleUPnP's mute button talk to this service. Volume
 * is applied to the DLNA stream through the renderer, deliberately not to the TV's system volume, so
 * casting never changes what the TV remote controls.
 *
 * HOW: Registered with [SoapDispatcher] for [UpnpService.RENDERING_CONTROL].
 */
class RenderingControlService(private val control: RendererControl) : SoapActionHandler {

    override fun handle(action: String, args: Map<String, String>, context: SoapContext): Map<String, String> {
        SoapArgs.requireInstanceZero(args)
        return when (action) {
            "ListPresets" -> mapOf("CurrentPresetNameList" to FACTORY_DEFAULTS)
            "SelectPreset" -> {
                if (SoapArgs.required(args, "PresetName") != FACTORY_DEFAULTS) {
                    throw UpnpError.invalidArgs("unknown preset")
                }
                control.setVolume(RendererSnapshot.MAX_VOLUME)
                control.setMute(false)
                emptyMap()
            }
            "GetVolume" -> {
                requireMaster(args)
                mapOf("CurrentVolume" to control.snapshot().volume.toString())
            }
            "SetVolume" -> {
                requireMaster(args)
                val desired = SoapArgs.required(args, "DesiredVolume").trim().toIntOrNull()
                    ?: throw UpnpError.invalidArgs("DesiredVolume must be numeric")
                control.setVolume(desired.coerceIn(0, RendererSnapshot.MAX_VOLUME))
                emptyMap()
            }
            "GetMute" -> {
                requireMaster(args)
                mapOf("CurrentMute" to if (control.snapshot().mute) "1" else "0")
            }
            "SetMute" -> {
                requireMaster(args)
                control.setMute(parseBoolean(SoapArgs.required(args, "DesiredMute")))
                emptyMap()
            }
            else -> throw UpnpError.invalidAction(action)
        }
    }

    private fun requireMaster(args: Map<String, String>) {
        val channel = args["Channel"] ?: MASTER
        if (channel != MASTER) throw UpnpError.invalidArgs("unsupported channel $channel")
    }

    /** UPnP booleans arrive as 1/0, true/false or yes/no. */
    private fun parseBoolean(raw: String): Boolean = when (raw.trim().lowercase(Locale.US)) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> throw UpnpError.invalidArgs("not a boolean: $raw")
    }

    companion object {
        const val MASTER = "Master"
        const val FACTORY_DEFAULTS = "FactoryDefaults"
    }
}
```

`ConnectionManagerService.kt`:
```kotlin
package com.phairplay.dlna

/**
 * ConnectionManagerService — ConnectionManager:1 with the sink protocolInfo list and one static connection.
 *
 * WHY: Control points (Windows in particular) read GetProtocolInfo before casting to learn which formats
 * we accept natively. We never negotiate connections, so the single connection 0 is always present.
 *
 * HOW: Registered with [SoapDispatcher] for [UpnpService.CONNECTION_MANAGER]. No InstanceID here.
 */
class ConnectionManagerService : SoapActionHandler {

    override fun handle(action: String, args: Map<String, String>, context: SoapContext): Map<String, String> =
        when (action) {
            "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to ProtocolInfoList.sinkString())
            "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to CONNECTION_ID)
            "GetCurrentConnectionInfo" -> {
                val id = SoapArgs.required(args, "ConnectionID").trim()
                if (id != CONNECTION_ID) throw UpnpError.invalidConnectionReference(id)
                mapOf(
                    "RcsID" to CONNECTION_ID,
                    "AVTransportID" to CONNECTION_ID,
                    "ProtocolInfo" to "",
                    "PeerConnectionManager" to "",
                    "PeerConnectionID" to NO_PEER,
                    "Direction" to "Input",
                    "Status" to "OK"
                )
            }
            else -> throw UpnpError.invalidAction(action)
        }

    companion object {
        const val CONNECTION_ID = "0"
        const val NO_PEER = "-1"
    }
}
```

- [ ] **Step 4: Run the tests** — expected BUILD SUCCESSFUL.

- [ ] **Step 5: Hand-off** — `feat(dlna): RenderingControl and ConnectionManager services`

---

### Task 9: LastChange encoding and GENA event subscriptions

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/LastChangeEncoder.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/EventSubscriptions.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/LastChangeEncoderTest.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/EventSubscriptionsTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LastChangeEncoderTest — the LastChange document control points parse to update their UI.
 *
 * WHY: Windows' transport buttons and BubbleUPnP's progress bar follow LastChange; the namespace, the
 * `channel="Master"` attribute on RCS variables and attribute escaping must be exact.
 */
class LastChangeEncoderTest {

    @Test
    fun `avTransport wraps variables in the AVT namespace with instance 0`() {
        val xml = LastChangeEncoder.avTransport(linkedMapOf("TransportState" to "PLAYING", "CurrentTrackURI" to "http://x/a?b=1&c=2"))
        assertTrue(xml.startsWith("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">"))
        assertTrue(xml.contains("<TransportState val=\"PLAYING\"/>"))
        assertTrue(xml.contains("<CurrentTrackURI val=\"http://x/a?b=1&amp;c=2\"/>"))
        assertTrue(xml.endsWith("</InstanceID></Event>"))
    }

    @Test
    fun `renderingControl adds the Master channel to every variable`() {
        val xml = LastChangeEncoder.renderingControl(linkedMapOf("Volume" to "40", "Mute" to "0"))
        assertTrue(xml.contains("xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\""))
        assertTrue(xml.contains("<Volume channel=\"Master\" val=\"40\"/>"))
        assertTrue(xml.contains("<Mute channel=\"Master\" val=\"0\"/>"))
    }

    @Test
    fun `snapshot to variable maps carry the fields control points poll`() {
        val snapshot = RendererSnapshot(
            state = TransportState.PAUSED_PLAYBACK, currentUri = "http://x/a.mp4", currentMetadata = "<m/>",
            nextUri = "http://x/b.mp4", durationMs = 90_000, volume = 55, mute = true
        )
        val avt = LastChangeEncoder.avTransportVars(snapshot)
        assertEquals("PAUSED_PLAYBACK", avt["TransportState"])
        assertEquals("OK", avt["TransportStatus"])
        assertEquals("Play,Stop,Seek,Next", avt["CurrentTransportActions"])
        assertEquals("1", avt["NumberOfTracks"])
        assertEquals("0:01:30", avt["CurrentTrackDuration"])
        assertEquals("http://x/a.mp4", avt["AVTransportURI"])
        assertEquals("<m/>", avt["CurrentTrackMetaData"])
        assertEquals("http://x/b.mp4", avt["NextAVTransportURI"])
        val rcs = LastChangeEncoder.renderingControlVars(snapshot)
        assertEquals("55", rcs["Volume"])
        assertEquals("1", rcs["Mute"])
    }
}
```

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EventSubscriptionsTest — GENA subscribe/renew/unsubscribe, the initial event, moderation and expiry.
 *
 * WHY: Windows subscribes to AVTransport and RenderingControl and expects the SEQ 0 initial NOTIFY
 * immediately after the SUBSCRIBE reply; without it the transport buttons stay disabled.
 */
class EventSubscriptionsTest {

    private class FakeSender : NotifySender {
        val sent = mutableListOf<Triple<CallbackUrl, Long, String>>()
        var succeed = true
        override fun send(callback: CallbackUrl, sid: String, seq: Long, propertySetXml: String): Boolean {
            sent += Triple(callback, seq, propertySetXml)
            return succeed
        }
    }

    private var now = 1_000_000L
    private val sender = FakeSender()
    private val subs = EventSubscriptions(
        sender = sender,
        initialState = { service -> mapOf("LastChange" to "<initial-${service.pathName}/>") },
        clock = { now },
        sidFactory = { "uuid:test-sid" }
    )
    private val avt = UpnpService.AV_TRANSPORT
    private val callback = "<http://192.168.1.10:2869/upnp/eventing/abc>"

    private fun subscribe(): HttpResponse = subs.subscribe(avt, callback, "upnp:event", null, "Second-1800")

    @Test
    fun `subscribe returns SID and TIMEOUT and sends the SEQ 0 initial event after the reply`() {
        val response = subscribe()
        assertEquals(200, response.status)
        assertEquals("uuid:test-sid", response.headers["SID"])
        assertEquals("Second-1800", response.headers["TIMEOUT"])
        assertTrue(sender.sent.isEmpty())
        response.afterSend!!.invoke()
        assertEquals(1, sender.sent.size)
        val (url, seq, body) = sender.sent[0]
        assertEquals("192.168.1.10", url.host)
        assertEquals(2869, url.port)
        assertEquals("/upnp/eventing/abc", url.path)
        assertEquals(0L, seq)
        assertTrue(body.contains("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"))
        assertTrue(body.contains("<e:property><LastChange>&lt;initial-AVTransport/&gt;</LastChange></e:property>"))
    }

    @Test
    fun `renewal by SID extends the subscription and unknown SID is 412`() {
        subscribe()
        assertEquals(200, subs.subscribe(avt, null, null, "uuid:test-sid", "Second-1800").status)
        assertEquals(412, subs.subscribe(avt, null, null, "uuid:other", null).status)
        assertEquals(400, subs.subscribe(avt, callback, "upnp:event", "uuid:test-sid", null).status)
    }

    @Test
    fun `missing NT bad callback or public callback are 412`() {
        assertEquals(412, subs.subscribe(avt, callback, null, null, null).status)
        assertEquals(412, subs.subscribe(avt, "<ftp://192.168.1.10/x>", "upnp:event", null, null).status)
        assertEquals(412, subs.subscribe(avt, "<http://8.8.8.8/x>", "upnp:event", null, null).status)
        assertEquals(412, subs.subscribe(avt, "<http://evil.example/x>", "upnp:event", null, null).status)
        assertEquals(0, subs.subscriptionCount())
    }

    @Test
    fun `publish merges variables and flushPending sends one NOTIFY with SEQ 1`() {
        subscribe().afterSend!!.invoke()
        subs.publish(avt, mapOf("LastChange" to "<a/>"))
        subs.publish(avt, mapOf("LastChange" to "<b/>"))
        subs.flushPending()
        assertEquals(2, sender.sent.size)
        assertEquals(1L, sender.sent[1].second)
        assertTrue(sender.sent[1].third.contains("&lt;b/&gt;"))
        assertFalse(sender.sent[1].third.contains("&lt;a/&gt;"))
    }

    @Test
    fun `flushPending respects the moderation window`() {
        subscribe().afterSend!!.invoke()
        subs.publish(avt, mapOf("LastChange" to "<a/>"))
        subs.flushPending()
        subs.publish(avt, mapOf("LastChange" to "<b/>"))
        subs.flushPending()
        assertEquals(2, sender.sent.size)             // second publish held back
        now += DlnaConstants.EVENT_MODERATION_MS
        subs.flushPending()
        assertEquals(3, sender.sent.size)
        assertEquals(2L, sender.sent[2].second)
    }

    @Test
    fun `publish to a service with no subscribers sends nothing`() {
        subs.publish(UpnpService.RENDERING_CONTROL, mapOf("LastChange" to "<a/>"))
        subs.flushPending()
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `two consecutive delivery failures drop the subscription`() {
        subscribe().afterSend!!.invoke()
        sender.succeed = false
        subs.publish(avt, mapOf("LastChange" to "<a/>")); subs.flushPending()
        assertEquals(1, subs.subscriptionCount())
        now += DlnaConstants.EVENT_MODERATION_MS
        subs.publish(avt, mapOf("LastChange" to "<b/>")); subs.flushPending()
        assertEquals(0, subs.subscriptionCount())
    }

    @Test
    fun `unsubscribe removes and expiry sweep drops old subscriptions`() {
        subscribe()
        assertEquals(200, subs.unsubscribe(avt, "uuid:test-sid").status)
        assertEquals(412, subs.unsubscribe(avt, "uuid:test-sid").status)
        subscribe()
        now += DlnaConstants.SUBSCRIPTION_TIMEOUT_SECONDS * 1000L + 1
        subs.sweepExpired()
        assertEquals(0, subs.subscriptionCount())
    }

    @Test
    fun `CallbackUrl parses host port and path and knows private ranges`() {
        val url = CallbackUrl.parse("http://10.0.0.5/notify")
        assertNotNull(url)
        assertEquals(80, url!!.port)
        assertEquals("/notify", url.path)
        assertTrue(url.isPrivate)
        assertTrue(CallbackUrl.parse("http://172.16.4.4:1234/")!!.isPrivate)
        assertTrue(CallbackUrl.parse("http://169.254.1.1/")!!.isPrivate)
        assertFalse(CallbackUrl.parse("http://1.2.3.4/")!!.isPrivate)
        assertNull(CallbackUrl.parse("https://192.168.1.1/"))
        assertNull(CallbackUrl.parse("http://host.local/"))
    }
}
```

- [ ] **Step 2: Run to verify they fail** — expected `Unresolved reference: LastChangeEncoder` / `EventSubscriptions`.

- [ ] **Step 3: Create `LastChangeEncoder.kt`**

```kotlin
package com.phairplay.dlna

/**
 * LastChangeEncoder — builds the `LastChange` event document for AVTransport and RenderingControl.
 *
 * WHY: UPnP AV services do not event individual variables; they event one `LastChange` XML blob listing
 * the variables that changed. Control points (Windows, BubbleUPnP) parse it to update their UI.
 *
 * HOW: `LastChangeEncoder.avTransport(LastChangeEncoder.avTransportVars(snapshot))` → XML that goes into
 * the `LastChange` property of a GENA NOTIFY (which escapes it once more).
 */
object LastChangeEncoder {
    const val AVT_NAMESPACE = "urn:schemas-upnp-org:metadata-1-0/AVT/"
    const val RCS_NAMESPACE = "urn:schemas-upnp-org:metadata-1-0/RCS/"
    private const val CHANNEL_ATTRIBUTE = "channel=\"Master\" "

    fun avTransport(vars: Map<String, String>): String = encode(AVT_NAMESPACE, vars, withChannel = false)

    fun renderingControl(vars: Map<String, String>): String = encode(RCS_NAMESPACE, vars, withChannel = true)

    fun avTransportVars(s: RendererSnapshot): Map<String, String> = linkedMapOf(
        "TransportState" to s.state.name,
        "TransportStatus" to s.status,
        "CurrentTransportActions" to s.transportActions,
        "NumberOfTracks" to s.numberOfTracks.toString(),
        "CurrentTrack" to s.numberOfTracks.toString(),
        "CurrentTrackDuration" to UpnpTime.format(s.durationMs),
        "CurrentMediaDuration" to UpnpTime.format(s.durationMs),
        "CurrentTrackURI" to s.currentUri,
        "AVTransportURI" to s.currentUri,
        "CurrentTrackMetaData" to s.currentMetadata,
        "AVTransportURIMetaData" to s.currentMetadata,
        "NextAVTransportURI" to s.nextUri,
        "NextAVTransportURIMetaData" to s.nextMetadata
    )

    fun renderingControlVars(s: RendererSnapshot): Map<String, String> = linkedMapOf(
        "Volume" to s.volume.toString(),
        "Mute" to if (s.mute) "1" else "0"
    )

    private fun encode(namespace: String, vars: Map<String, String>, withChannel: Boolean): String {
        val body = vars.entries.joinToString("") { (name, value) ->
            "<$name ${if (withChannel) CHANNEL_ATTRIBUTE else ""}val=\"${SecureXml.escape(value)}\"/>"
        }
        return "<Event xmlns=\"$namespace\"><InstanceID val=\"0\">$body</InstanceID></Event>"
    }
}
```

- [ ] **Step 4: Create `EventSubscriptions.kt`**

```kotlin
package com.phairplay.dlna

import com.phairplay.util.Logger
import java.util.EnumMap
import java.util.UUID

/**
 * CallbackUrl — a parsed GENA callback (`<http://host:port/path>`), restricted to plain http on a
 * private IPv4 address so a hostile SUBSCRIBE cannot make the TV POST to the internet (RULE 4).
 */
data class CallbackUrl(val host: String, val port: Int, val path: String) {

    /** RFC 1918 and link-local ranges only. */
    val isPrivate: Boolean
        get() {
            val octets = host.split(".").map { it.toIntOrNull() ?: return false }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254)
        }

    companion object {
        private const val SCHEME = "http://"
        private const val DEFAULT_PORT = 80
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /** Null unless the URL is `http://` followed by a dotted IPv4 host. */
        fun parse(text: String): CallbackUrl? {
            val trimmed = text.trim().removePrefix("<").removeSuffix(">")
            if (!trimmed.startsWith(SCHEME)) return null
            val rest = trimmed.removePrefix(SCHEME)
            val hostPort = rest.substringBefore('/')
            val path = "/" + rest.substringAfter('/', "")
            val host = hostPort.substringBefore(':')
            if (!IPV4.matches(host)) return null
            val port = if (hostPort.contains(':')) hostPort.substringAfter(':').toIntOrNull() ?: return null else DEFAULT_PORT
            if (port !in 1..65535) return null
            return CallbackUrl(host, port, path)
        }
    }
}

/** Delivers one GENA NOTIFY. Returns true on a 2xx reply. Implemented over a raw socket in [HttpNotifySender]. */
fun interface NotifySender {
    fun send(callback: CallbackUrl, sid: String, seq: Long, propertySetXml: String): Boolean
}

/**
 * EventSubscriptions — GENA subscription registry with the initial event, moderated LastChange delivery,
 * renewal, expiry and failure-based eviction.
 *
 * WHY: Windows subscribes to AVTransport and RenderingControl and disables its transport buttons until the
 * SEQ 0 initial NOTIFY arrives; afterwards it follows LastChange. Moderation (one NOTIFY per
 * [DlnaConstants.EVENT_MODERATION_MS] per service) stops a seek scrub from flooding the network.
 *
 * HOW: The router calls [subscribe]/[unsubscribe]; the renderer calls [publish]; [DlnaReceiver] calls
 * [flushPending] on a timer and [sweepExpired] once a minute. Tests call them directly with a fake clock.
 */
class EventSubscriptions(
    private val sender: NotifySender,
    /** Full evented state per service, sent as the SEQ 0 initial event. */
    private val initialState: (UpnpService) -> Map<String, String>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sidFactory: () -> String = { "uuid:" + UUID.randomUUID() }
) {
    private class Subscription(
        val sid: String,
        val service: UpnpService,
        val callbacks: List<CallbackUrl>,
        var expiresAt: Long,
        var nextSeq: Long = 0L,
        var failures: Int = 0
    )

    private val lock = Any()
    private val subscriptions = LinkedHashMap<String, Subscription>()
    private val pending = EnumMap<UpnpService, LinkedHashMap<String, String>>(UpnpService::class.java)
    private val lastFlushAt = EnumMap<UpnpService, Long>(UpnpService::class.java)

    fun subscribe(
        service: UpnpService,
        callbackHeader: String?,
        ntHeader: String?,
        sidHeader: String?,
        timeoutHeader: String?
    ): HttpResponse {
        val timeout = DlnaConstants.SUBSCRIPTION_TIMEOUT_SECONDS
        val headers = { sid: String -> mapOf("SID" to sid, "TIMEOUT" to "Second-$timeout") }
        synchronized(lock) {
            if (sidHeader != null) {
                // Renewal: SID present, CALLBACK/NT must be absent (UPnP DA 4.1.2).
                if (callbackHeader != null || ntHeader != null) return HttpResponse.empty(400)
                val existing = subscriptions[sidHeader.trim()] ?: return HttpResponse.empty(412)
                existing.expiresAt = clock() + timeout * MS_PER_SECOND
                return HttpResponse.empty(200, headers(existing.sid))
            }
            if (ntHeader?.trim() != "upnp:event") return HttpResponse.empty(412)
            val callbacks = parseCallbacks(callbackHeader)
            if (callbacks.isEmpty()) {
                Logger.w("GENA subscribe rejected: no acceptable callback in '$callbackHeader'")
                return HttpResponse.empty(412)
            }
            val subscription = Subscription(sidFactory(), service, callbacks, clock() + timeout * MS_PER_SECOND)
            subscriptions[subscription.sid] = subscription
            Logger.i("GENA subscribed ${service.pathName} sid=${subscription.sid} → ${callbacks.first().host}")
            return HttpResponse(200, headers(subscription.sid), afterSend = { sendInitialEvent(subscription.sid) })
        }
    }

    fun unsubscribe(service: UpnpService, sidHeader: String?): HttpResponse = synchronized(lock) {
        val removed = sidHeader?.let { subscriptions.remove(it.trim()) }
        if (removed == null) HttpResponse.empty(412) else HttpResponse.empty(200)
    }

    /** Queues changed variables; merged with earlier pending values until the next flush. */
    fun publish(service: UpnpService, vars: Map<String, String>) = synchronized(lock) {
        pending.getOrPut(service) { LinkedHashMap() }.putAll(vars)
    }

    /** Sends pending changes for services whose moderation window has elapsed. */
    fun flushPending() {
        val deliveries = mutableListOf<Triple<Subscription, Long, String>>()
        synchronized(lock) {
            val now = clock()
            for (service in UpnpService.entries) {
                val vars = pending[service]?.takeIf { it.isNotEmpty() } ?: continue
                val targets = subscriptions.values.filter { it.service == service }
                if (targets.isEmpty()) { pending.remove(service); continue }
                if (now - (lastFlushAt[service] ?: 0L) < DlnaConstants.EVENT_MODERATION_MS) continue
                val body = propertySet(vars)
                for (sub in targets) deliveries += Triple(sub, sub.nextSeq++, body)
                pending.remove(service)
                lastFlushAt[service] = now
            }
        }
        deliveries.forEach { (sub, seq, body) -> deliver(sub, seq, body) }
    }

    fun sweepExpired() = synchronized(lock) {
        val now = clock()
        val expired = subscriptions.values.filter { it.expiresAt <= now }.map { it.sid }
        expired.forEach { subscriptions.remove(it); Logger.d("GENA subscription expired $it") }
    }

    fun subscriptionCount(): Int = synchronized(lock) { subscriptions.size }

    private fun sendInitialEvent(sid: String) {
        val subscription = synchronized(lock) { subscriptions[sid] } ?: return
        val body = propertySet(initialState(subscription.service))
        val seq = synchronized(lock) { subscription.nextSeq++ }
        deliver(subscription, seq, body)
    }

    private fun deliver(subscription: Subscription, seq: Long, body: String) {
        val ok = subscription.callbacks.any { sender.send(it, subscription.sid, seq, body) }
        synchronized(lock) {
            if (ok) {
                subscription.failures = 0
            } else if (++subscription.failures >= DlnaConstants.EVENT_MAX_FAILURES) {
                subscriptions.remove(subscription.sid)
                Logger.w("GENA dropping ${subscription.sid} after ${subscription.failures} failed deliveries")
            }
        }
    }

    private fun parseCallbacks(header: String?): List<CallbackUrl> =
        CALLBACK_PATTERN.findAll(header ?: "")
            .mapNotNull { CallbackUrl.parse(it.groupValues[1]) }
            .filter { it.isPrivate }
            .toList()

    companion object {
        private const val MS_PER_SECOND = 1000L
        private val CALLBACK_PATTERN = Regex("<([^>]+)>")

        fun propertySet(vars: Map<String, String>): String =
            "<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
                vars.entries.joinToString("") { (name, value) ->
                    "<e:property><$name>${SecureXml.escape(value)}</$name></e:property>"
                } +
                "</e:propertyset>"
    }
}
```

- [ ] **Step 5: Run the tests** — expected BUILD SUCCESSFUL, `LastChangeEncoderTest` 3 + `EventSubscriptionsTest` 9 passed.

- [ ] **Step 6: Hand-off** — `feat(dlna): LastChange encoding and GENA event subscriptions`

---

### Task 10: HTTP router

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/DlnaRouter.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/DlnaRouterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DlnaRouterTest — path + method → description, SCPD, SOAP, GENA or icon.
 *
 * WHY: Windows fetches the description, each SCPD and the icon, POSTs SOAP and SUBSCRIBEs; any wrong
 * status or missing EXT header and it drops the device.
 */
class DlnaRouterTest {

    private val events = EventSubscriptions(
        sender = { _, _, _, _ -> true },
        initialState = { emptyMap() },
        sidFactory = { "uuid:sid-1" }
    )
    private val router = DlnaRouter(
        description = DeviceDescription("TV", "uuid:abc", "1.0"),
        soap = SoapDispatcher(mapOf(UpnpService.AV_TRANSPORT to SoapActionHandler { _, _, _ -> emptyMap() })),
        events = events,
        icon = { byteArrayOf(1, 2, 3) },
        baseUrl = { "http://192.168.1.185:49494" }
    )

    private fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String = "") =
        HttpRequest(method, path, headers.mapKeys { it.key.lowercase() }, body.toByteArray())

    private fun body(response: HttpResponse) = String(response.body, Charsets.UTF_8)

    @Test
    fun `GET description returns the device XML with absolute URLs`() {
        val response = router.handle(request("GET", "/description.xml"))
        assertEquals(200, response.status)
        assertTrue(body(response).contains("<friendlyName>TV</friendlyName>"))
        assertTrue(body(response).contains("http://192.168.1.185:49494/control/AVTransport"))
    }

    @Test
    fun `GET each SCPD returns XML`() {
        for (service in UpnpService.entries) {
            val response = router.handle(request("GET", service.scpdPath))
            assertEquals(200, response.status)
            assertNotNull(SecureXml.parse(body(response)))
        }
    }

    @Test
    fun `POST control dispatches SOAP and adds the EXT header`() {
        val envelope = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:Play xmlns:u=\"${UpnpService.AV_TRANSPORT.serviceType}\"><InstanceID>0</InstanceID></u:Play></s:Body></s:Envelope>"
        val response = router.handle(request("POST", "/control/AVTransport",
            mapOf("SOAPACTION" to "\"${UpnpService.AV_TRANSPORT.serviceType}#Play\""), envelope))
        assertEquals(200, response.status)
        assertEquals("", response.headers["EXT"])
        assertTrue(body(response).contains("<u:PlayResponse"))
    }

    @Test
    fun `service without a registered handler faults with 401`() {
        val envelope = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:GetVolume xmlns:u=\"x\"/></s:Body></s:Envelope>"
        val response = router.handle(request("POST", "/control/RenderingControl", body = envelope))
        assertEquals(500, response.status)
        assertTrue(body(response).contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun `SUBSCRIBE and UNSUBSCRIBE reach the event registry`() {
        val response = router.handle(request("SUBSCRIBE", "/event/AVTransport",
            mapOf("CALLBACK" to "<http://192.168.1.10:2869/x>", "NT" to "upnp:event", "TIMEOUT" to "Second-1800")))
        assertEquals(200, response.status)
        assertEquals("uuid:sid-1", response.headers["SID"])
        assertEquals(200, router.handle(request("UNSUBSCRIBE", "/event/AVTransport", mapOf("SID" to "uuid:sid-1"))).status)
    }

    @Test
    fun `GET icon returns PNG bytes`() {
        val response = router.handle(request("GET", "/icon.png"))
        assertEquals(200, response.status)
        assertEquals("image/png", response.headers["CONTENT-TYPE"])
        assertEquals(3, response.body.size)
    }

    @Test
    fun `wrong method is 405 and unknown path is 404`() {
        assertEquals(405, router.handle(request("GET", "/control/AVTransport")).status)
        assertEquals(405, router.handle(request("POST", "/description.xml")).status)
        assertEquals(405, router.handle(request("GET", "/event/AVTransport")).status)
        assertEquals(404, router.handle(request("GET", "/nope")).status)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — expected `Unresolved reference: DlnaRouter`.

- [ ] **Step 3: Create `DlnaRouter.kt`**

```kotlin
package com.phairplay.dlna

import com.phairplay.dlna.scpd.Scpd

/**
 * DlnaRouter — maps an HTTP request path and method to description, SCPD, SOAP control, GENA or the icon.
 *
 * WHY: Keeps the URL layout in one pure, testable class so [DlnaHttpServer] is only sockets and
 * [SoapDispatcher]/[EventSubscriptions] never see HTTP paths.
 *
 * HOW: `router.handle(request)` → [HttpResponse]. Construct once per receiver start.
 */
class DlnaRouter(
    private val description: DeviceDescription,
    private val soap: SoapDispatcher,
    private val events: EventSubscriptions,
    private val icon: () -> ByteArray,
    private val baseUrl: () -> String
) {
    fun handle(request: HttpRequest): HttpResponse {
        val path = request.path
        UpnpService.fromScpdPath(path)?.let { service ->
            return onlyMethod(request, GET) { HttpResponse.xml(200, Scpd.forService(service)) }
        }
        UpnpService.fromControlPath(path)?.let { service ->
            return onlyMethod(request, POST) { control(service, request) }
        }
        UpnpService.fromEventPath(path)?.let { service ->
            return when (request.method) {
                SUBSCRIBE -> events.subscribe(
                    service, request.header("callback"), request.header("nt"), request.header("sid"), request.header("timeout")
                )
                UNSUBSCRIBE -> events.unsubscribe(service, request.header("sid"))
                else -> HttpResponse.empty(405)
            }
        }
        return when (path) {
            DlnaConstants.DESCRIPTION_PATH -> onlyMethod(request, GET) { HttpResponse.xml(200, description.xml(baseUrl())) }
            DlnaConstants.ICON_PATH -> onlyMethod(request, GET) { HttpResponse.bytes(200, "image/png", icon()) }
            else -> HttpResponse.empty(404)
        }
    }

    private fun control(service: UpnpService, request: HttpRequest): HttpResponse {
        val result = soap.dispatch(
            service, request.header("soapaction"), request.bodyText, SoapContext(userAgent = request.header("user-agent"))
        )
        // EXT is mandatory on UPnP control responses (HTTP Extension Framework acknowledgement).
        return HttpResponse.xml(result.status, result.xml, extraHeaders = mapOf("EXT" to ""))
    }

    private inline fun onlyMethod(request: HttpRequest, method: String, respond: () -> HttpResponse): HttpResponse =
        if (request.method == method) respond() else HttpResponse.empty(405)

    companion object {
        private const val GET = "GET"
        private const val POST = "POST"
        private const val SUBSCRIBE = "SUBSCRIBE"
        private const val UNSUBSCRIBE = "UNSUBSCRIBE"
    }
}
```

- [ ] **Step 4: Run the tests** — expected BUILD SUCCESSFUL, `DlnaRouterTest` 7 passed.

- [ ] **Step 5: Hand-off** — `feat(dlna): HTTP router for description, control, eventing and icon`

---

### Task 11: Photo fetcher, player contract and the MediaRenderer state machine

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/PhotoFetcher.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/RendererPlayer.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/MediaRenderer.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/PhotoFetcherTest.kt`
- Test helper: `app/src/test/kotlin/com/phairplay/dlna/FakeRendererPlayer.kt`
- Test: `app/src/test/kotlin/com/phairplay/dlna/MediaRendererTest.kt`

- [ ] **Step 1: Write the failing tests and the fake player**

`PhotoFetcherTest.kt`:
```kotlin
package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * PhotoFetcherTest — image download limits and MIME detection, with a fake connection.
 *
 * WHY: A cast photo is fetched into memory; the 20 MB cap (RULE 4) and a sane MIME fallback protect the
 * TV from a hostile or misconfigured server.
 */
class PhotoFetcherTest {

    private fun connection(bytes: ByteArray, status: Int = 200, contentType: String? = "image/png", declaredLength: Long = -1) =
        { url: URL ->
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = status
                override fun getContentType() = contentType
                override fun getContentLengthLong() = declaredLength
                override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
            }
        }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9)

    @Test
    fun `returns bytes and the declared image MIME`() {
        val result = PhotoFetcher(connection(png)).fetch("http://192.168.1.10/pic.png") as PhotoFetcher.Result.Ok
        assertEquals(png.size, result.bytes.size)
        assertEquals("image/png", result.mimeType)
    }

    @Test
    fun `sniffs the MIME when the server does not declare an image type`() {
        val result = PhotoFetcher(connection(jpeg, contentType = "application/octet-stream")).fetch("http://x/a") as PhotoFetcher.Result.Ok
        assertEquals("image/jpeg", result.mimeType)
    }

    @Test
    fun `rejects non-2xx, oversized declared and oversized actual bodies`() {
        assertTrue(PhotoFetcher(connection(png, status = 404)).fetch("http://x/a") is PhotoFetcher.Result.Failed)
        assertTrue(PhotoFetcher(connection(png, declaredLength = 100), maxBytes = 50).fetch("http://x/a") is PhotoFetcher.Result.Failed)
        assertTrue(PhotoFetcher(connection(ByteArray(60)), maxBytes = 50).fetch("http://x/a") is PhotoFetcher.Result.Failed)
    }

    @Test
    fun `rejects non-http schemes and bad URLs`() {
        assertTrue(PhotoFetcher(connection(png)).fetch("ftp://x/a") is PhotoFetcher.Result.Failed)
        assertTrue(PhotoFetcher(connection(png)).fetch("not a url") is PhotoFetcher.Result.Failed)
    }
}
```

`FakeRendererPlayer.kt`:
```kotlin
package com.phairplay.dlna

/**
 * FakeRendererPlayer — records player calls and lets tests fire prepared/completed/error callbacks.
 *
 * WHY: MediaPlayer cannot run on the JVM; the renderer state machine is tested against this instead.
 */
class FakeRendererPlayer : RendererPlayer {
    val calls = mutableListOf<String>()
    var listener: RendererPlayer.Listener? = null
    var position = 0L
    var duration = 0L

    override fun load(uri: String, audioOnly: Boolean, listener: RendererPlayer.Listener) {
        calls += "load:$uri:${if (audioOnly) "audio" else "video"}"
        this.listener = listener
    }
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun stop() { calls += "stop" }
    override fun seekTo(positionMs: Long) { calls += "seek:$positionMs"; position = positionMs }
    override fun positionMs() = position
    override fun durationMs() = duration
    override fun setVolume(percent: Int) { calls += "volume:$percent" }
    override fun setMute(mute: Boolean) { calls += "mute:$mute" }
    override fun release() { calls += "release" }
}
```

`MediaRendererTest.kt`:
```kotlin
package com.phairplay.dlna

import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.service.ProtocolState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * MediaRendererTest — the DLNA session state machine: URI → player or photo, transport states, overlays.
 *
 * WHY: This is where Windows' load/play/stop sequence, playlist advancing, remote keys and the UI
 * callbacks meet. Every transition the spec names is pinned here without MediaPlayer or sockets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRendererTest {

    private val player = FakeRendererPlayer()
    private var airPlayConnected = false
    private val protocolStates = mutableListOf<ProtocolState>()
    private val snapshots = mutableListOf<RendererSnapshot>()
    private val nowPlaying = mutableListOf<NowPlayingInfo?>()
    private val photos = mutableListOf<String>()
    private var photoCleared = 0
    private val senders = mutableListOf<String>()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)
    private val fetcher = PhotoFetcher({ url: URL ->
        object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = if (url.path.contains("missing")) 404 else 200
            override fun getContentType() = "image/jpeg"
            override fun getContentLengthLong() = -1L
            override fun getInputStream(): InputStream = ByteArrayInputStream(jpeg)
        }
    })

    private val callbacks = RendererCallbacks(
        onProtocolState = { protocolStates += it },
        onSnapshot = { snapshots += it },
        onNowPlaying = { nowPlaying += it },
        onPhoto = { _, mime -> photos += mime },
        onPhotoCleared = { photoCleared++ },
        onSenderName = { senders += it }
    )

    private fun renderer(block: suspend (MediaRenderer) -> Unit) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        block(MediaRenderer(player, fetcher, { airPlayConnected }, callbacks, this, dispatcher))
    }

    private val video = "http://192.168.1.10:2869/movie.mp4"
    private val audioDidl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item>" +
        "<dc:title>Song</dc:title><upnp:artist>Band</upnp:artist><upnp:class>object.item.audioItem</upnp:class>" +
        "<upnp:albumArtURI>http://192.168.1.10/art.jpg</upnp:albumArtURI></item></DIDL-Lite>"
    private val windows = "Microsoft-Windows/10.0 UPnP/1.0 Microsoft-DLNA DLNADOC/1.50"

    @Test
    fun `video load goes TRANSITIONING then PLAYING once prepared after Play`() = renderer { r ->
        r.load(video, "", windows)
        assertEquals(TransportState.TRANSITIONING, r.snapshot().state)
        assertEquals(listOf("stop", "load:$video:video"), player.calls)
        assertEquals(1, photoCleared)
        assertNull(nowPlaying.last())
        assertEquals("Windows PC", senders.last())
        assertEquals(ProtocolState.CONNECTED, protocolStates.last())

        r.play()
        assertEquals(TransportState.TRANSITIONING, r.snapshot().state)
        player.listener!!.onPrepared(120_000)
        assertEquals(TransportState.PLAYING, r.snapshot().state)
        assertEquals(120_000L, r.snapshot().durationMs)
        assertTrue(player.calls.contains("play"))
    }

    @Test
    fun `prepared without a Play request parks in STOPPED`() = renderer { r ->
        r.load(video, "", null)
        player.listener!!.onPrepared(1000)
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertEquals("DLNA Sender", senders.last())
    }

    @Test
    fun `pause resume and stop drive the player and the overlay`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(1000)
        r.pause()
        assertEquals(TransportState.PAUSED_PLAYBACK, r.snapshot().state)
        assertTrue(player.calls.contains("pause"))
        r.play()
        assertEquals(TransportState.PLAYING, r.snapshot().state)
        r.stop()
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertEquals(ProtocolState.ADVERTISING, protocolStates.last())
        assertEquals(0L, r.snapshot().positionMs)
    }

    @Test
    fun `play from STOPPED reloads through the player and waits for prepare`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(1000); r.stop()
        player.calls.clear()
        r.play()
        assertEquals(TransportState.TRANSITIONING, r.snapshot().state)
        assertEquals(listOf("play"), player.calls)
        player.listener!!.onPrepared(1000)
        assertEquals(TransportState.PLAYING, r.snapshot().state)
    }

    @Test
    fun `completion starts the next URI or stops`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(1000)
        r.setNext("http://x/2.mp4", "")
        player.listener!!.onCompleted()
        assertEquals("http://x/2.mp4", r.snapshot().currentUri)
        assertEquals("", r.snapshot().nextUri)
        assertEquals(TransportState.TRANSITIONING, r.snapshot().state)
        player.listener!!.onPrepared(500)
        assertEquals(TransportState.PLAYING, r.snapshot().state)
        player.listener!!.onCompleted()
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertEquals(ProtocolState.ADVERTISING, protocolStates.last())
    }

    @Test
    fun `audio load shows the now-playing card with DIDL metadata and fetched art`() = renderer { r ->
        r.load("http://192.168.1.10/song.mp3", audioDidl, "BubbleUPnP/4.0")
        assertEquals("load:http://192.168.1.10/song.mp3:audio", player.calls.last())
        val info = nowPlaying.last()!!
        assertEquals("Song", info.title)
        assertEquals("Band", info.artist)
        assertEquals("BubbleUPnP", info.senderName)
        assertEquals(jpeg.size, info.artwork!!.size)
    }

    @Test
    fun `image load fetches the photo and reports PLAYING without a player`() = renderer { r ->
        r.load("http://192.168.1.10/pic.jpg", "", "VLC/3.0")
        assertEquals(listOf("image/jpeg"), photos)
        assertEquals(TransportState.PLAYING, r.snapshot().state)
        assertEquals("VLC", senders.last())
        assertTrue(player.calls.none { it.startsWith("load") })
        r.stop()
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertTrue(photoCleared >= 1)
    }

    @Test
    fun `failed photo fetch reports an error status`() = renderer { r ->
        r.load("http://192.168.1.10/missing.jpg", "", null)
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertEquals(RendererSnapshot.STATUS_ERROR, r.snapshot().status)
    }

    @Test
    fun `player error reports ERROR_OCCURRED and clears the overlay`() = renderer { r ->
        r.load(video, "", null); r.play()
        player.listener!!.onError("what=1")
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertEquals(RendererSnapshot.STATUS_ERROR, r.snapshot().status)
        assertEquals(ProtocolState.ADVERTISING, protocolStates.last())
    }

    @Test
    fun `unknown item is 714 and video during AirPlay is 501`() = renderer { r ->
        try { r.load("http://x/blob", "", null); fail() } catch (e: UpnpError) { assertEquals(714, e.code) }
        airPlayConnected = true
        try { r.load(video, "", null); fail() } catch (e: UpnpError) { assertEquals(501, e.code) }
        r.load("http://x/song.mp3", "", null)   // audio is allowed alongside AirPlay
        assertEquals(TransportState.TRANSITIONING, r.snapshot().state)
    }

    @Test
    fun `seek volume and mute update the snapshot and the player`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(100_000)
        r.seekTo(30_000)
        assertEquals("seek:30000", player.calls.last())
        assertEquals(30_000L, r.snapshot().positionMs)
        r.setVolume(140); r.setMute(true)
        assertEquals(100, r.snapshot().volume)
        assertTrue(r.snapshot().mute)
        assertTrue(player.calls.containsAll(listOf("volume:100", "mute:true")))
    }

    @Test
    fun `remote commands toggle seek and stop`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(100_000)
        r.remote(RemoteCommand.PLAY_PAUSE)
        assertEquals(TransportState.PAUSED_PLAYBACK, r.snapshot().state)
        r.remote(RemoteCommand.PLAY_PAUSE)
        assertEquals(TransportState.PLAYING, r.snapshot().state)
        player.position = 50_000
        r.remote(RemoteCommand.SEEK_FORWARD)
        assertEquals("seek:${50_000 + DlnaConstants.SEEK_STEP_MS}", player.calls.last())
        r.remote(RemoteCommand.SEEK_BACK)
        assertEquals("seek:${60_000 - DlnaConstants.SEEK_STEP_MS}", player.calls.last())
        r.remote(RemoteCommand.STOP)
        assertEquals(TransportState.STOPPED, r.snapshot().state)
    }

    @Test
    fun `protocol state is only reported when it changes`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(1000); r.seekTo(10); r.setVolume(50)
        assertEquals(listOf(ProtocolState.CONNECTED), protocolStates)
    }
}
```

- [ ] **Step 2: Run to verify they fail** — expected `Unresolved reference: PhotoFetcher` / `RendererPlayer` / `MediaRenderer`.

- [ ] **Step 3: Create `PhotoFetcher.kt`**

```kotlin
package com.phairplay.dlna

import com.phairplay.util.Logger
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * PhotoFetcher — downloads a cast image (or album art) into memory with a hard size cap.
 *
 * WHY: DLNA image items and `albumArtURI` are plain HTTP URLs on the sender; the TV must fetch them itself.
 * The cap and timeouts (RULE 4) protect against hostile or broken servers. The connection opener is
 * injectable so the limits are unit-tested without a network.
 *
 * HOW: `when (val r = fetcher.fetch(uri)) { is Ok -> show(r.bytes, r.mimeType); is Failed -> … }` — blocking,
 * call on an IO dispatcher.
 */
class PhotoFetcher(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val maxBytes: Int = DlnaConstants.MAX_PHOTO_BYTES
) {
    sealed class Result {
        data class Ok(val bytes: ByteArray, val mimeType: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun fetch(uri: String): Result {
        val url = runCatching { URL(uri) }.getOrElse { return Result.Failed("bad url") }
        if (url.protocol != "http" && url.protocol != "https") return Result.Failed("unsupported scheme ${url.protocol}")
        var connection: HttpURLConnection? = null
        return try {
            connection = open(url).apply {
                connectTimeout = DlnaConstants.PHOTO_TIMEOUT_MS
                readTimeout = DlnaConstants.PHOTO_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("transferMode.dlna.org", "Interactive")
            }
            val status = connection.responseCode
            if (status !in 200..299) return Result.Failed("HTTP $status")
            if (connection.contentLengthLong > maxBytes) return Result.Failed("declared size over cap")
            val bytes = readCapped(connection) ?: return Result.Failed("body over cap")
            val declared = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
            val mime = declared?.takeIf { it.startsWith("image/") } ?: sniffMime(bytes) ?: FALLBACK_MIME
            Result.Ok(bytes, mime)
        } catch (e: Exception) {
            Logger.w("PhotoFetcher: $uri failed: ${e.message}")
            Result.Failed(e.message ?: "io error")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readCapped(connection: HttpURLConnection): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        connection.inputStream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > maxBytes) return null
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun sniffMime(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() -> "image/gif"
        else -> null
    }

    companion object {
        private const val READ_CHUNK = 16 * 1024
        private const val FALLBACK_MIME = "image/jpeg"
    }
}
```

- [ ] **Step 4: Create `RendererPlayer.kt`**

```kotlin
package com.phairplay.dlna

/**
 * RendererPlayer — the playback engine [MediaRenderer] drives; implemented by [DlnaPlayer] on Android and
 * by a fake in tests.
 *
 * WHY: MediaPlayer cannot run on the JVM. Hiding it behind this interface keeps the session state machine
 * fully unit-testable.
 *
 * Contract: [load] prepares asynchronously and reports through the listener; [play] after [stop] must
 * re-prepare the last URI and fire [Listener.onPrepared] again; [stop] is idempotent.
 */
interface RendererPlayer {
    interface Listener {
        fun onPrepared(durationMs: Long)
        fun onCompleted()
        fun onError(message: String)
    }

    fun load(uri: String, audioOnly: Boolean, listener: Listener)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun positionMs(): Long
    fun durationMs(): Long
    /** 0..100, applied to this stream only. */
    fun setVolume(percent: Int)
    fun setMute(mute: Boolean)
    fun release()
}
```

- [ ] **Step 5: Create `MediaRenderer.kt`**

```kotlin
package com.phairplay.dlna

import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Everything the renderer tells the service/UI. All callbacks may be invoked from IO threads. */
class RendererCallbacks(
    val onProtocolState: (ProtocolState) -> Unit,
    val onSnapshot: (RendererSnapshot) -> Unit,
    val onNowPlaying: (NowPlayingInfo?) -> Unit,
    val onPhoto: (bytes: ByteArray, mimeType: String) -> Unit,
    val onPhotoCleared: () -> Unit,
    val onSenderName: (String) -> Unit
)

/** TV-remote media keys routed to the renderer while DLNA is connected. */
enum class RemoteCommand { PLAY_PAUSE, STOP, NEXT, SEEK_FORWARD, SEEK_BACK }

/**
 * MediaRenderer — the one stateful DLNA object: turns SetAVTransportURI into playback or a photo, tracks
 * the transport state, fires LastChange snapshots and drives the service overlays.
 *
 * WHY: The UPnP services are pure protocol; the player and the photo fetch are asynchronous. This class
 * owns the single lock that reconciles SOAP calls (IO threads), player callbacks (MediaPlayer thread) and
 * fetch completions (coroutines), and it alone knows which overlay each media class uses.
 *
 * HOW: Constructed by [DlnaReceiver]; registered as the [RendererControl] for the services. State changes
 * reach the outside only through [RendererCallbacks].
 */
class MediaRenderer(
    private val player: RendererPlayer,
    private val photos: PhotoFetcher,
    private val isAirPlayConnected: () -> Boolean,
    private val callbacks: RendererCallbacks,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : RendererControl {

    private val lock = Any()
    private var snapshot = RendererSnapshot()
    private var mediaClass: MediaClass? = null
    private var currentItem = DidlItem.EMPTY
    private var senderName = DEFAULT_SENDER
    private var lastAgent: String? = null
    private var playRequested = false
    private var lastProtocolState: ProtocolState? = null
    /** Bumped on every load; async completions for an older generation are ignored. */
    private var generation = 0

    // ─── RendererControl ─────────────────────────────────────────────────────

    override fun load(uri: String, metadata: String, senderAgent: String?) =
        loadInternal(uri, metadata, senderAgent, autoPlay = false)

    override fun setNext(uri: String, metadata: String) {
        synchronized(lock) { snapshot = snapshot.copy(nextUri = uri, nextMetadata = metadata) }
        publish()
    }

    override fun play() {
        var startPlayer = false
        var refetchPhoto: String? = null
        synchronized(lock) {
            when (snapshot.state) {
                TransportState.TRANSITIONING -> playRequested = true
                TransportState.PAUSED_PLAYBACK -> {
                    snapshot = snapshot.copy(state = TransportState.PLAYING, status = RendererSnapshot.STATUS_OK)
                    startPlayer = mediaClass != MediaClass.IMAGE
                }
                TransportState.STOPPED -> {
                    playRequested = true
                    if (mediaClass == MediaClass.IMAGE) {
                        refetchPhoto = snapshot.currentUri
                    } else {
                        startPlayer = true                         // player re-prepares → onPrepared → PLAYING
                    }
                    snapshot = snapshot.copy(state = TransportState.TRANSITIONING, status = RendererSnapshot.STATUS_OK)
                }
                TransportState.PLAYING, TransportState.NO_MEDIA_PRESENT -> {}
            }
        }
        if (startPlayer) player.play()
        refetchPhoto?.let { fetchPhoto(it, currentGeneration()) }
        publish()
    }

    override fun pause() {
        var pausePlayer = false
        synchronized(lock) {
            when (snapshot.state) {
                TransportState.PLAYING -> {
                    snapshot = snapshot.copy(state = TransportState.PAUSED_PLAYBACK)
                    pausePlayer = mediaClass != MediaClass.IMAGE
                }
                TransportState.TRANSITIONING -> playRequested = false
                else -> {}
            }
        }
        if (pausePlayer) player.pause()
        publish()
    }

    override fun stop() {
        synchronized(lock) {
            if (!snapshot.hasMedia) return
            playRequested = false
            snapshot = snapshot.copy(state = TransportState.STOPPED, positionMs = 0L, status = RendererSnapshot.STATUS_OK)
        }
        player.stop()
        clearOverlays()
        publish()
    }

    override fun seekTo(positionMs: Long) {
        val target: Long
        synchronized(lock) {
            if (!snapshot.hasMedia || mediaClass == MediaClass.IMAGE) return
            target = positionMs.coerceIn(0L, if (snapshot.durationMs > 0) snapshot.durationMs else Long.MAX_VALUE)
            snapshot = snapshot.copy(positionMs = target)
        }
        player.seekTo(target)
        publish()
    }

    override fun next() {
        val (uri, metadata) = synchronized(lock) { snapshot.nextUri to snapshot.nextMetadata }
        if (uri.isEmpty()) return
        val agent = synchronized(lock) { lastAgent }
        try {
            loadInternal(uri, metadata, agent, autoPlay = true)
        } catch (e: UpnpError) {
            Logger.w("DLNA next item rejected: ${e.description}")
            stop()
        }
    }

    override fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, RendererSnapshot.MAX_VOLUME)
        synchronized(lock) { snapshot = snapshot.copy(volume = clamped) }
        player.setVolume(clamped)
        publish()
    }

    override fun setMute(mute: Boolean) {
        synchronized(lock) { snapshot = snapshot.copy(mute = mute) }
        player.setMute(mute)
        publish()
    }

    override fun snapshot(): RendererSnapshot = synchronized(lock) {
        val live = snapshot.state == TransportState.PLAYING || snapshot.state == TransportState.PAUSED_PLAYBACK
        if (live && mediaClass != MediaClass.IMAGE) {
            snapshot.copy(positionMs = player.positionMs(), durationMs = maxOf(player.durationMs(), snapshot.durationMs))
        } else snapshot
    }

    // ─── Remote keys / lifecycle ─────────────────────────────────────────────

    fun remote(command: RemoteCommand) {
        when (command) {
            RemoteCommand.PLAY_PAUSE -> if (snapshot().state == TransportState.PLAYING) pause() else play()
            RemoteCommand.STOP -> stop()
            RemoteCommand.NEXT -> next()
            RemoteCommand.SEEK_FORWARD -> seekTo(snapshot().positionMs + DlnaConstants.SEEK_STEP_MS)
            RemoteCommand.SEEK_BACK -> seekTo(snapshot().positionMs - DlnaConstants.SEEK_STEP_MS)
        }
    }

    fun release() {
        stop()
        player.release()
    }

    // ─── Internals ───────────────────────────────────────────────────────────

    private fun loadInternal(uri: String, metadata: String, senderAgent: String?, autoPlay: Boolean) {
        val item = DidlLite.parse(metadata)
        val mediaClass = ProtocolInfoList.classify(item.protocolInfo, item.upnpClass, uri)
            ?: throw UpnpError.illegalMimeType(uri)
        if (mediaClass == MediaClass.VIDEO && isAirPlayConnected()) {
            throw UpnpError.actionFailed("receiver busy with an AirPlay session")
        }
        val myGeneration: Int
        val name: String
        synchronized(lock) {
            generation++
            myGeneration = generation
            this.mediaClass = mediaClass
            currentItem = item
            lastAgent = senderAgent
            senderName = senderNameFrom(senderAgent)
            name = senderName
            playRequested = autoPlay
            snapshot = snapshot.copy(
                state = TransportState.TRANSITIONING, status = RendererSnapshot.STATUS_OK,
                currentUri = uri, currentMetadata = metadata,
                nextUri = if (autoPlay) "" else snapshot.nextUri,
                nextMetadata = if (autoPlay) "" else snapshot.nextMetadata,
                durationMs = item.durationMs ?: 0L, positionMs = 0L
            )
        }
        player.stop()
        callbacks.onSenderName(name)
        Logger.i("DLNA load $mediaClass from '$name': $uri")
        when (mediaClass) {
            MediaClass.VIDEO -> {
                callbacks.onPhotoCleared(); callbacks.onNowPlaying(null)
                player.load(uri, audioOnly = false, listener = playerListener(myGeneration))
            }
            MediaClass.AUDIO -> {
                callbacks.onPhotoCleared(); callbacks.onNowPlaying(nowPlayingInfo(null))
                player.load(uri, audioOnly = true, listener = playerListener(myGeneration))
                item.albumArtUri?.let { fetchArtwork(it, myGeneration) }
            }
            MediaClass.IMAGE -> {
                callbacks.onNowPlaying(null)
                fetchPhoto(uri, myGeneration)
            }
        }
        publish()
    }

    private fun playerListener(myGeneration: Int) = object : RendererPlayer.Listener {
        override fun onPrepared(durationMs: Long) {
            val shouldPlay: Boolean
            synchronized(lock) {
                if (myGeneration != generation) return
                shouldPlay = playRequested
                snapshot = snapshot.copy(
                    state = if (shouldPlay) TransportState.PLAYING else TransportState.STOPPED,
                    durationMs = if (durationMs > 0) durationMs else snapshot.durationMs
                )
            }
            if (shouldPlay) player.play()
            publish()
        }

        override fun onCompleted() {
            synchronized(lock) { if (myGeneration != generation) return }
            val hasNext = synchronized(lock) { snapshot.nextUri.isNotEmpty() }
            if (hasNext) next() else finish(RendererSnapshot.STATUS_OK)
        }

        override fun onError(message: String) {
            synchronized(lock) { if (myGeneration != generation) return }
            Logger.w("DLNA player error: $message")
            finish(RendererSnapshot.STATUS_ERROR)
        }
    }

    /** Playback (or a photo) ended, normally or with an error: back to STOPPED and hide overlays. */
    private fun finish(status: String) {
        synchronized(lock) {
            playRequested = false
            snapshot = snapshot.copy(state = TransportState.STOPPED, status = status, positionMs = 0L)
        }
        clearOverlays()
        publish()
    }

    private fun fetchPhoto(uri: String, myGeneration: Int) {
        scope.launch(ioDispatcher) {
            when (val result = photos.fetch(uri)) {
                is PhotoFetcher.Result.Ok -> {
                    synchronized(lock) {
                        if (myGeneration != generation) return@launch
                        snapshot = snapshot.copy(state = TransportState.PLAYING, status = RendererSnapshot.STATUS_OK)
                    }
                    callbacks.onPhoto(result.bytes, result.mimeType)
                    publish()
                }
                is PhotoFetcher.Result.Failed -> {
                    synchronized(lock) { if (myGeneration != generation) return@launch }
                    Logger.w("DLNA photo fetch failed: ${result.reason}")
                    finish(RendererSnapshot.STATUS_ERROR)
                }
            }
        }
    }

    private fun fetchArtwork(uri: String, myGeneration: Int) {
        scope.launch(ioDispatcher) {
            val result = photos.fetch(uri) as? PhotoFetcher.Result.Ok ?: return@launch
            val info = synchronized(lock) {
                if (myGeneration != generation) return@launch
                nowPlayingInfo(result.bytes)
            }
            callbacks.onNowPlaying(info)
        }
    }

    private fun nowPlayingInfo(artwork: ByteArray?) =
        NowPlayingInfo(senderName, currentItem.title, currentItem.artist, currentItem.album, artwork)

    private fun clearOverlays() {
        callbacks.onPhotoCleared()
        callbacks.onNowPlaying(null)
    }

    private fun currentGeneration(): Int = synchronized(lock) { generation }

    /** Emits the snapshot (for LastChange) and the protocol state only when the latter changed. */
    private fun publish() {
        val current = snapshot()
        callbacks.onSnapshot(current)
        val protocolState = if (current.state.isActive) ProtocolState.CONNECTED else ProtocolState.ADVERTISING
        val changed = synchronized(lock) {
            (protocolState != lastProtocolState).also { if (it) lastProtocolState = protocolState }
        }
        if (changed) callbacks.onProtocolState(protocolState)
    }

    companion object {
        const val DEFAULT_SENDER = "DLNA Sender"

        /** Friendly sender label from the control point's User-Agent. */
        fun senderNameFrom(userAgent: String?): String = when {
            userAgent == null -> DEFAULT_SENDER
            userAgent.contains("Windows", ignoreCase = true) -> "Windows PC"
            userAgent.contains("BubbleUPnP", ignoreCase = true) -> "BubbleUPnP"
            userAgent.contains("VLC", ignoreCase = true) -> "VLC"
            else -> DEFAULT_SENDER
        }
    }
}
```

Note on the initial-state quirk: `publish()` is first called from `loadInternal`, so `lastProtocolState` starts null and the first CONNECTED is always emitted. `DlnaReceiver` reports ADVERTISING itself on start (Task 12).

- [ ] **Step 6: Run the tests** — expected BUILD SUCCESSFUL, `PhotoFetcherTest` 4 + `MediaRendererTest` 13 passed. If `MediaRendererTest` reports `UnconfinedTestDispatcher` unresolved, confirm `kotlinx-coroutines-test:1.8.1` is on the test-runner classpath (it is in `test-runner/build.gradle.kts`).

- [ ] **Step 7: Check file sizes** — `wc -l app/src/main/kotlin/com/phairplay/dlna/*.kt`; every file must be ≤ 400 lines (`MediaRenderer.kt` is expected around 300).

- [ ] **Step 8: Hand-off** — `feat(dlna): photo fetcher, player contract and MediaRenderer state machine`

---

### Task 12: Android-only pieces — sockets, MediaPlayer, icon, receiver

No JVM tests here (sockets and MediaPlayer); the gate is a clean compile plus the on-device test in Task 17. `DlnaReceiver.kt` and `DlnaIcon.kt` reference `R`, so they are excluded from the test-runner in Step 7.

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/dlna/LocalAddress.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/SsdpAdvertiser.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/DlnaHttpServer.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/HttpNotifySender.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/DlnaPlayer.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/DlnaIcon.kt`
- Create: `app/src/main/kotlin/com/phairplay/dlna/DlnaReceiver.kt`
- Modify: `test-runner/build.gradle.kts` (exclude list)

- [ ] **Step 1: Create `LocalAddress.kt`**

```kotlin
package com.phairplay.dlna

import com.phairplay.util.Logger
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LocalAddress — the IPv4 address control points can reach this TV on.
 *
 * WHY: The SSDP LOCATION and the description's absolute URLs must name an address on the LAN; Android has
 * no single "my IP" API that works for both Wi-Fi and Ethernet, so we scan the interfaces.
 *
 * HOW: `LocalAddress.ipv4()` → `"192.168.1.185"` or null when no site-local interface is up.
 */
object LocalAddress {
    fun ipv4(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        Logger.w("LocalAddress: could not enumerate interfaces: ${e.message}")
        null
    }
}
```

- [ ] **Step 2: Create `SsdpAdvertiser.kt`**

```kotlin
package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketException
import kotlin.random.Random

/**
 * SsdpAdvertiser — announces the renderer over SSDP and answers M-SEARCH queries.
 *
 * WHY: UPnP discovery is how Windows, BubbleUPnP and VLC find the TV. Alive NOTIFYs make it appear
 * immediately; M-SEARCH replies make it appear when a control point starts later; byebye removes it.
 *
 * HOW: `SsdpAdvertiser(scope, udn) { locationUrl }.start()` / `stop()`. The caller must hold a
 * `WifiManager.MulticastLock` (done in [DlnaReceiver]) or Wi-Fi drops the multicast traffic.
 */
class SsdpAdvertiser(
    private val scope: CoroutineScope,
    private val udn: String,
    private val location: () -> String
) {
    private var socket: MulticastSocket? = null
    private val group: InetAddress = InetAddress.getByName(DlnaConstants.SSDP_ADDRESS)
    private val jobs = mutableListOf<Job>()

    fun start() {
        val multicast = MulticastSocket(DlnaConstants.SSDP_PORT).apply {
            reuseAddress = true
            @Suppress("DEPRECATION")
            joinGroup(group)
        }
        socket = multicast
        jobs += scope.launch { receiveLoop(multicast) }
        jobs += scope.launch {
            while (isActive) {
                sendAlive(multicast)
                delay(DlnaConstants.ALIVE_INTERVAL_MS)
            }
        }
        Logger.i("SSDP advertising $udn at ${location()}")
    }

    fun stop() {
        socket?.let { runCatching { sendByeBye(it) }.onFailure { e -> Logger.w("SSDP byebye failed: ${e.message}") } }
        jobs.forEach { it.cancel() }
        jobs.clear()
        socket?.let { s ->
            @Suppress("DEPRECATION")
            runCatching { s.leaveGroup(group) }
            s.close()
        }
        socket = null
        Logger.i("SSDP advertising stopped")
    }

    private suspend fun receiveLoop(multicast: MulticastSocket) {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                multicast.receive(packet)
            } catch (e: SocketException) {
                break   // socket closed by stop()
            }
            val request = SsdpMessages.parse(String(packet.data, 0, packet.length, Charsets.ISO_8859_1)) ?: continue
            if (!SsdpMessages.isSearch(request)) continue
            val targets = SsdpMessages.matchingTargets(request.header("ST"), udn)
            if (targets.isEmpty()) continue
            // UPnP asks responders to spread replies over 0..MX seconds so a search does not get a burst.
            val delayMs = Random.nextLong(0L, SsdpMessages.mxSeconds(request) * MS_PER_SECOND)
            val replyTo = packet.address
            val replyPort = packet.port
            scope.launch {
                delay(delayMs)
                for ((st, usn) in targets) {
                    send(multicast, SsdpMessages.searchResponse(st, usn, location()), replyTo, replyPort)
                }
            }
        }
    }

    private fun sendAlive(multicast: MulticastSocket) {
        // Sent twice per UPnP DA recommendation (UDP may drop one).
        repeat(ALIVE_REPEATS) {
            for ((nt, usn) in SsdpMessages.targets(udn)) {
                send(multicast, SsdpMessages.notifyAlive(nt, usn, location()), group, DlnaConstants.SSDP_PORT)
            }
        }
    }

    private fun sendByeBye(multicast: MulticastSocket) {
        for ((nt, usn) in SsdpMessages.targets(udn)) {
            send(multicast, SsdpMessages.notifyByeBye(nt, usn), group, DlnaConstants.SSDP_PORT)
        }
    }

    private fun send(multicast: MulticastSocket, text: String, address: InetAddress, port: Int) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        try {
            multicast.send(DatagramPacket(bytes, bytes.size, address, port))
        } catch (e: Exception) {
            Logger.w("SSDP send to $address:$port failed: ${e.message}")
        }
    }

    companion object {
        private const val RECEIVE_BUFFER_BYTES = 2048
        private const val ALIVE_REPEATS = 2
        private const val MS_PER_SECOND = 1000L
    }
}
```

- [ ] **Step 3: Create `DlnaHttpServer.kt` and `HttpNotifySender.kt`**

`DlnaHttpServer.kt`:
```kotlin
package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * DlnaHttpServer — minimal HTTP/1.1 server (Connection: close) for description, SOAP control and GENA.
 *
 * WHY: UPnP control points speak plain HTTP; a raw ServerSocket keeps us in control of the verbs
 * (SUBSCRIBE/UNSUBSCRIBE are not HTTP-library friendly) and mirrors the AirPlay RTSP server's approach.
 *
 * HOW: `val port = server.start()`; `server.stop()`. All request handling is delegated to [handler]
 * (the [DlnaRouter]); parse failures are answered here with 400/413.
 */
class DlnaHttpServer(
    private val scope: CoroutineScope,
    private val handler: (HttpRequest) -> HttpResponse
) {
    private var serverSocket: ServerSocket? = null
    private val reader = HttpRequestReader()

    /** Binds [DlnaConstants.DEFAULT_HTTP_PORT], falling back to an OS-assigned port. Returns the bound port. */
    fun start(): Int {
        val socket = try {
            ServerSocket(DlnaConstants.DEFAULT_HTTP_PORT)
        } catch (e: IOException) {
            Logger.w("DLNA HTTP port ${DlnaConstants.DEFAULT_HTTP_PORT} busy (${e.message}) — using an ephemeral port")
            ServerSocket(0)
        }
        serverSocket = socket
        scope.launch { acceptLoop(socket) }
        Logger.i("DLNA HTTP server listening on ${socket.localPort}")
        return socket.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }.onFailure { Logger.w("DLNA HTTP close failed: ${it.message}") }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break   // closed by stop()
            }
            scope.launch { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        var afterSend: (() -> Unit)? = null
        try {
            client.use { connection ->
                connection.soTimeout = DlnaConstants.HTTP_READ_TIMEOUT_MS
                val response = when (val parsed = reader.read(connection.getInputStream())) {
                    is HttpParse.Ok -> respond(parsed.request)
                    HttpParse.TooLarge -> HttpResponse.empty(413)
                    HttpParse.Malformed -> HttpResponse.empty(400)
                    HttpParse.Eof -> return
                }
                connection.getOutputStream().apply { write(response.toBytes()); flush() }
                afterSend = response.afterSend
            }
        } catch (e: IOException) {
            Logger.d("DLNA HTTP connection ${client.inetAddress} ended: ${e.message}")
        }
        // Runs after the reply is on the wire (GENA initial event) and after the socket is closed.
        afterSend?.let { scope.launch { it() } }
    }

    private fun respond(request: HttpRequest): HttpResponse = try {
        Logger.d("DLNA HTTP ${request.method} ${request.path}")
        handler(request)
    } catch (e: Exception) {
        Logger.e("DLNA HTTP handler failed for ${request.method} ${request.path}", e)
        HttpResponse.empty(500)
    }
}
```

`HttpNotifySender.kt`:
```kotlin
package com.phairplay.dlna

import com.phairplay.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * HttpNotifySender — delivers a GENA `NOTIFY` over a raw socket.
 *
 * WHY: `HttpURLConnection` rejects the non-standard NOTIFY method, so the event message is written by hand.
 * One short-lived connection per event, with a hard timeout so a dead subscriber cannot stall the renderer.
 *
 * HOW: Implements [NotifySender]; used by [EventSubscriptions] via [DlnaReceiver].
 */
class HttpNotifySender : NotifySender {

    override fun send(callback: CallbackUrl, sid: String, seq: Long, propertySetXml: String): Boolean {
        val body = propertySetXml.toByteArray(Charsets.UTF_8)
        val head = "NOTIFY ${callback.path} HTTP/1.1\r\n" +
            "HOST: ${callback.host}:${callback.port}\r\n" +
            "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
            "CONTENT-LENGTH: ${body.size}\r\n" +
            "NT: upnp:event\r\n" +
            "NTS: upnp:propchange\r\n" +
            "SID: $sid\r\n" +
            "SEQ: $seq\r\n" +
            "CONNECTION: close\r\n\r\n"
        return try {
            Socket().use { socket ->
                socket.soTimeout = DlnaConstants.EVENT_DELIVERY_TIMEOUT_MS
                socket.connect(InetSocketAddress(callback.host, callback.port), DlnaConstants.EVENT_DELIVERY_TIMEOUT_MS)
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush()
                }
                val statusLine = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1)).readLine() ?: ""
                val ok = statusLine.split(" ").getOrNull(1)?.toIntOrNull()?.let { it in 200..299 } ?: false
                if (!ok) Logger.w("GENA NOTIFY to ${callback.host}:${callback.port} answered '$statusLine'")
                ok
            }
        } catch (e: Exception) {
            Logger.w("GENA NOTIFY to ${callback.host}:${callback.port} failed: ${e.message}")
            false
        }
    }
}
```

- [ ] **Step 4: Create `DlnaPlayer.kt`**

```kotlin
package com.phairplay.dlna

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import com.phairplay.util.Logger

/**
 * DlnaPlayer — [RendererPlayer] on Android's [MediaPlayer]: fetches the URL itself, renders video on the
 * shared streaming [Surface] or plays audio-only.
 *
 * WHY: Same engine and the same patterns as `AirPlayVideoPlayer` (synchronized methods, lazy surface, guard
 * against `onPrepared` racing a release), plus what DLNA needs: stream-level volume/mute, absolute seek,
 * transport callbacks and re-preparing on Play-after-Stop.
 *
 * Volume is applied with `MediaPlayer.setVolume` so the TV's system volume is untouched.
 */
class DlnaPlayer(
    private val context: Context,
    private val surfaceProvider: () -> Surface?
) : RendererPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var lastUri: String? = null
    private var lastAudioOnly = false
    private var listener: RendererPlayer.Listener? = null
    private var playWhenPrepared = false
    private var volumePercent = RendererSnapshot.MAX_VOLUME
    private var muted = false

    @Synchronized
    override fun load(uri: String, audioOnly: Boolean, listener: RendererPlayer.Listener) {
        releaseLocked()
        lastUri = uri
        lastAudioOnly = audioOnly
        this.listener = listener
        playWhenPrepared = false
        prepareLocked(uri, audioOnly)
    }

    private fun prepareLocked(uri: String, audioOnly: Boolean) {
        val player = MediaPlayer()
        mediaPlayer = player
        val currentListener = listener
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (audioOnly) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            if (!audioOnly) surfaceProvider()?.let { player.setSurface(it) }
            player.setOnPreparedListener { prepared -> onPrepared(prepared, audioOnly) }
            player.setOnCompletionListener { done -> if (isCurrent(done)) currentListener?.onCompleted() }
            player.setOnErrorListener { failed, what, extra ->
                if (isCurrent(failed)) currentListener?.onError("MediaPlayer error what=$what extra=$extra")
                true   // handled — do not also fire onCompletion
            }
            player.setDataSource(context, Uri.parse(uri), DLNA_HEADERS)
            player.prepareAsync()
            Logger.i("DlnaPlayer: preparing ${if (audioOnly) "audio" else "video"} $uri")
        }.onFailure { e ->
            Logger.e("DlnaPlayer: load failed", e)
            releaseLocked()
            currentListener?.onError(e.message ?: "load failed")
        }
    }

    private fun onPrepared(prepared: MediaPlayer, audioOnly: Boolean) {
        val shouldStart: Boolean
        val duration: Long
        synchronized(this) {
            if (mediaPlayer !== prepared) return     // released or replaced while preparing
            // The Surface usually appears only after the overlay is shown — attach it now as well.
            if (!audioOnly) surfaceProvider()?.let { runCatching { prepared.setSurface(it) } }
            applyVolumeLocked(prepared)
            shouldStart = playWhenPrepared
            playWhenPrepared = false
            duration = runCatching { prepared.duration.toLong() }.getOrDefault(0L)
            if (shouldStart) runCatching { prepared.start() }
        }
        Logger.i("DlnaPlayer: prepared dur=${duration}ms autoStart=$shouldStart")
        listener?.onPrepared(duration)
    }

    @Synchronized
    override fun play() {
        val player = mediaPlayer
        if (player == null) {
            // Play after Stop: MediaPlayer was released, so prepare the last URI again and start on prepared.
            val uri = lastUri ?: return
            playWhenPrepared = true
            prepareLocked(uri, lastAudioOnly)
            return
        }
        runCatching { if (!player.isPlaying) player.start() }.onFailure { Logger.w("DlnaPlayer: start failed: ${it.message}") }
    }

    @Synchronized
    override fun pause() {
        runCatching { mediaPlayer?.let { if (it.isPlaying) it.pause() } }.onFailure { Logger.w("DlnaPlayer: pause failed: ${it.message}") }
    }

    /** Idempotent. Releases the MediaPlayer; [play] re-prepares [lastUri]. */
    @Synchronized
    override fun stop() {
        releaseLocked()
    }

    @Synchronized
    override fun seekTo(positionMs: Long) {
        runCatching { mediaPlayer?.seekTo(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }
            .onFailure { Logger.w("DlnaPlayer: seek failed: ${it.message}") }
    }

    @Synchronized
    override fun positionMs(): Long = runCatching { mediaPlayer?.currentPosition?.toLong() }.getOrNull() ?: 0L

    @Synchronized
    override fun durationMs(): Long = runCatching { mediaPlayer?.duration?.toLong() }.getOrNull() ?: 0L

    @Synchronized
    override fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, RendererSnapshot.MAX_VOLUME)
        mediaPlayer?.let { applyVolumeLocked(it) }
    }

    @Synchronized
    override fun setMute(mute: Boolean) {
        muted = mute
        mediaPlayer?.let { applyVolumeLocked(it) }
    }

    @Synchronized
    override fun release() {
        releaseLocked()
        lastUri = null
        listener = null
    }

    private fun isCurrent(player: MediaPlayer): Boolean = synchronized(this) { mediaPlayer === player }

    /** Perceptual curve: 50 % on the slider is noticeably quieter than half amplitude would be. */
    private fun applyVolumeLocked(player: MediaPlayer) {
        val gain = if (muted) 0f else {
            val fraction = volumePercent / RendererSnapshot.MAX_VOLUME.toFloat()
            fraction * fraction
        }
        runCatching { player.setVolume(gain, gain) }
    }

    private fun releaseLocked() {
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null
        playWhenPrepared = false
    }

    companion object {
        /** DLNA transport hints some servers (Windows included) expect on media requests. */
        private val DLNA_HEADERS = mapOf(
            "transferMode.dlna.org" to "Streaming",
            "getcontentFeatures.dlna.org" to "1"
        )
    }
}
```

- [ ] **Step 5: Create `DlnaIcon.kt`**

```kotlin
package com.phairplay.dlna

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import com.phairplay.R
import com.phairplay.util.Logger
import java.io.ByteArrayOutputStream

/**
 * DlnaIcon — renders the launcher icon to the PNG bytes advertised in the device description.
 *
 * WHY: Windows fetches the icon while validating a renderer; an unreachable icon is one of the reasons a
 * device silently fails to appear in "Cast to Device".
 *
 * HOW: `DlnaIcon.png(context)` once at receiver start; cache the bytes.
 */
object DlnaIcon {
    fun png(context: Context): ByteArray {
        val size = DlnaConstants.ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val drawable = AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)
            if (drawable != null) {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
            }
        } catch (e: Exception) {
            Logger.w("DlnaIcon: could not draw launcher icon, serving a blank PNG: ${e.message}")
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private const val PNG_QUALITY = 100
}
```

- [ ] **Step 6: Create `DlnaReceiver.kt`**

```kotlin
package com.phairplay.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.view.Surface
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DlnaReceiver — top-level orchestrator for the DLNA MediaRenderer, the DLNA counterpart of `AirPlayReceiver`.
 *
 * WHY: Wires discovery ([SsdpAdvertiser]), the HTTP server + router, the SOAP services, GENA eventing and the
 * [MediaRenderer] into one lifecycle that `PhairPlayService` starts and stops, and holds the multicast lock
 * without which Android Wi-Fi drops SSDP traffic.
 *
 * HOW:
 *   val receiver = DlnaReceiver(context, displayName, versionName, videoSurfaceProvider = { surface },
 *       isAirPlayConnected = { … }, onStateChanged = { … })
 *   receiver.start(); receiver.remote(RemoteCommand.PLAY_PAUSE); receiver.stop()
 */
class DlnaReceiver(
    private val context: Context,
    /** User-configured display name (blank = system device name), shown as the UPnP friendlyName. */
    private val displayName: String,
    private val versionName: String,
    private val videoSurfaceProvider: () -> Surface?,
    private val isAirPlayConnected: () -> Boolean,
    private val onStateChanged: (ProtocolState) -> Unit,
    private val onSenderNameChanged: (String) -> Unit = {},
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    private val onPhotoReceived: (bytes: ByteArray, mimeType: String) -> Unit = { _, _ -> },
    private val onPhotoCleared: () -> Unit = {}
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val udn = "uuid:" + NetworkUtils.getPersistentUuid(context)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var httpServer: DlnaHttpServer? = null
    private var advertiser: SsdpAdvertiser? = null
    private var events: EventSubscriptions? = null
    private var renderer: MediaRenderer? = null
    @Volatile private var boundPort = 0

    fun start() {
        Logger.i("DlnaReceiver starting (displayName='$displayName')")
        scope.launch {
            try {
                startInternal()
            } catch (e: Exception) {
                Logger.e("Failed to start DlnaReceiver", e)
                onStateChanged(ProtocolState.ERROR)
            }
        }
    }

    fun stop() {
        Logger.i("DlnaReceiver stopping")
        try {
            advertiser?.stop()
            httpServer?.stop()
            renderer?.release()
        } catch (e: Exception) {
            Logger.e("Error during DlnaReceiver stop", e)
        } finally {
            releaseMulticastLock()
            scope.cancel()
            onStateChanged(ProtocolState.DISABLED)
        }
    }

    /** TV-remote media keys while DLNA is connected. No-op before start. */
    fun remote(command: RemoteCommand) {
        renderer?.remote(command)
    }

    // ─── Startup ─────────────────────────────────────────────────────────────

    private fun startInternal() {
        acquireMulticastLock()
        val friendlyName = displayName.trim().ifEmpty { NetworkUtils.getDeviceName(context) }
        val description = DeviceDescription(friendlyName, udn, versionName)
        val icon = DlnaIcon.png(context)

        val subscriptions = EventSubscriptions(HttpNotifySender(), initialState = { initialEventState(it) })
        events = subscriptions
        val mediaRenderer = MediaRenderer(
            player = DlnaPlayer(context, videoSurfaceProvider),
            photos = PhotoFetcher(),
            isAirPlayConnected = isAirPlayConnected,
            callbacks = RendererCallbacks(
                onProtocolState = onStateChanged,
                onSnapshot = { publishSnapshot(it) },
                onNowPlaying = onNowPlayingChanged,
                onPhoto = onPhotoReceived,
                onPhotoCleared = onPhotoCleared,
                onSenderName = onSenderNameChanged
            ),
            scope = scope
        )
        renderer = mediaRenderer

        val dispatcher = SoapDispatcher(
            mapOf(
                UpnpService.AV_TRANSPORT to AvTransportService(mediaRenderer),
                UpnpService.RENDERING_CONTROL to RenderingControlService(mediaRenderer),
                UpnpService.CONNECTION_MANAGER to ConnectionManagerService()
            )
        )
        val router = DlnaRouter(description, dispatcher, subscriptions, icon = { icon }, baseUrl = { baseUrl() })
        val server = DlnaHttpServer(scope) { router.handle(it) }
        httpServer = server
        boundPort = server.start()

        advertiser = SsdpAdvertiser(scope, udn) { baseUrl() + DlnaConstants.DESCRIPTION_PATH }.also { it.start() }

        scope.launch { while (isActive) { delay(DlnaConstants.EVENT_MODERATION_MS); subscriptions.flushPending() } }
        scope.launch { while (isActive) { delay(DlnaConstants.EXPIRY_SWEEP_MS); subscriptions.sweepExpired() } }

        onStateChanged(ProtocolState.ADVERTISING)
        Logger.i("DLNA renderer '$friendlyName' ready at ${baseUrl()}")
    }

    private fun baseUrl(): String = "http://${LocalAddress.ipv4() ?: LOOPBACK}:$boundPort"

    private fun publishSnapshot(snapshot: RendererSnapshot) {
        val subscriptions = events ?: return
        subscriptions.publish(
            UpnpService.AV_TRANSPORT,
            mapOf(LAST_CHANGE to LastChangeEncoder.avTransport(LastChangeEncoder.avTransportVars(snapshot)))
        )
        subscriptions.publish(
            UpnpService.RENDERING_CONTROL,
            mapOf(LAST_CHANGE to LastChangeEncoder.renderingControl(LastChangeEncoder.renderingControlVars(snapshot)))
        )
    }

    /** Full evented state for the GENA initial event (SEQ 0). */
    private fun initialEventState(service: UpnpService): Map<String, String> {
        val snapshot = renderer?.snapshot() ?: RendererSnapshot()
        return when (service) {
            UpnpService.AV_TRANSPORT ->
                mapOf(LAST_CHANGE to LastChangeEncoder.avTransport(LastChangeEncoder.avTransportVars(snapshot)))
            UpnpService.RENDERING_CONTROL ->
                mapOf(LAST_CHANGE to LastChangeEncoder.renderingControl(LastChangeEncoder.renderingControlVars(snapshot)))
            UpnpService.CONNECTION_MANAGER -> mapOf(
                "SourceProtocolInfo" to "",
                "SinkProtocolInfo" to ProtocolInfoList.sinkString(),
                "CurrentConnectionIDs" to ConnectionManagerService.CONNECTION_ID
            )
        }
    }

    // ─── Multicast lock ──────────────────────────────────────────────────────

    private fun acquireMulticastLock() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Logger.w("MulticastLock release failed: ${it.message}") }
        multicastLock = null
    }

    companion object {
        private const val LAST_CHANGE = "LastChange"
        private const val LOOPBACK = "127.0.0.1"
        private const val MULTICAST_LOCK_TAG = "phairplay-dlna-ssdp"
    }
}
```

- [ ] **Step 7: Exclude the two `R`-dependent files from the JVM test runner**

In `test-runner/build.gradle.kts`, inside the `exclude(` list, replace the last entry
`"**/airplay/VideoDecoder.kt"` with:

```kotlin
                "**/airplay/VideoDecoder.kt",
                // DLNA glue that references R (launcher icon) — everything else in dlna/ compiles on the JVM.
                "**/dlna/DlnaIcon.kt",
                "**/dlna/DlnaReceiver.kt"
```

- [ ] **Step 8: Compile both**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test`
Expected: BUILD SUCCESSFUL, all prior tests still green (the new Android-only files compile against the android-all stub jar; `DlnaReceiver`/`DlnaIcon` are excluded).

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :app:assembleGoogletvDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Hand-off** — `feat(dlna): SSDP advertiser, HTTP server, GENA sender, MediaPlayer wrapper and receiver`

---

### Task 13: Protocol enum and settings

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/service/ServiceState.kt`
- Modify: `app/src/main/kotlin/com/phairplay/settings/AppSettings.kt`
- Modify: `app/src/main/kotlin/com/phairplay/settings/SettingsRepository.kt`
- Modify: `app/src/test/kotlin/com/phairplay/service/ServiceStateTest.kt`
- Modify: `app/src/test/kotlin/com/phairplay/settings/AppSettingsTest.kt`

- [ ] **Step 1: Add the failing tests**

In `ServiceStateTest.kt`, after the `Protocol has CAST value` test:
```kotlin
    @Test
    fun `Protocol has DLNA value`() {
        assertEquals(Protocol.DLNA, Protocol.valueOf("DLNA"))
    }
```

In `AppSettingsTest.kt`: in `default settings have all protocols enabled` add `assertTrue(AppSettings.DEFAULT.dlnaEnabled)`; replace the four `anyProtocolEnabled` tests with:
```kotlin
    @Test
    fun `anyProtocolEnabled is true when all protocols are enabled`() {
        assertTrue(AppSettings(airPlayEnabled = true, castEnabled = true, dlnaEnabled = true).anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only AirPlay is enabled`() {
        assertTrue(AppSettings(airPlayEnabled = true, castEnabled = false, dlnaEnabled = false).anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only Cast is enabled`() {
        assertTrue(AppSettings(airPlayEnabled = false, castEnabled = true, dlnaEnabled = false).anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only DLNA is enabled`() {
        assertTrue(AppSettings(airPlayEnabled = false, castEnabled = false, dlnaEnabled = true).anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is false when all protocols are disabled`() {
        assertFalse(AppSettings(airPlayEnabled = false, castEnabled = false, dlnaEnabled = false).anyProtocolEnabled)
    }
```

- [ ] **Step 2: Run to verify they fail** — expected `Unresolved reference: DLNA` / `dlnaEnabled`.

- [ ] **Step 3: Production changes**

`ServiceState.kt`: `enum class Protocol { AIRPLAY, CAST }` → `enum class Protocol { AIRPLAY, CAST, DLNA }`; in the `ProtocolState` KDoc `(AirPlay / Cast)` → `(AirPlay / Cast / DLNA)` and `(AirPlayReceiver, CastReceiver)` → `(AirPlayReceiver, CastReceiver, DlnaReceiver)`.

`AppSettings.kt`: after the `castEnabled` property add:
```kotlin
    /**
     * Whether the DLNA / UPnP MediaRenderer is enabled.
     * When false: no SSDP advertising and the DLNA HTTP port is not opened.
     * Windows exposes this receiver as "Cast to Device".
     */
    val dlnaEnabled: Boolean = true,
```
and `anyProtocolEnabled` → `airPlayEnabled || castEnabled || dlnaEnabled` with its KDoc "If both are disabled" → "If all are disabled".

`SettingsRepository.kt`: add `dlnaEnabled = this[Keys.DLNA_ENABLED] ?: true,` after the `castEnabled` line in `toAppSettings`; `this[Keys.DLNA_ENABLED] = settings.dlnaEnabled` after the `CAST_ENABLED` write in `fromAppSettings`; `val DLNA_ENABLED = booleanPreferencesKey("dlna_enabled")` after `CAST_ENABLED` in `Keys`.

- [ ] **Step 4: Run the JVM suite** — `./gradlew :test-runner:test` → BUILD SUCCESSFUL.

- [ ] **Step 5: Hand-off** — `feat(settings): DLNA protocol flag and settings key`

---

### Task 14: Service integration

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt`

No JVM test (the service is excluded from the test-runner); the gate is the debug build plus Task 17.

- [ ] **Step 1: Imports and KDoc**

Add imports:
```kotlin
import com.phairplay.BuildConfig
import com.phairplay.dlna.DlnaReceiver
import com.phairplay.dlna.RemoteCommand
```
In the class KDoc change "hosts all receiver protocols" wording to mention DLNA: `WHY: The AirPlay/Cast/DLNA receivers need to run continuously…`.

- [ ] **Step 2: State flow and receiver field**

After the `castState` pair add:
```kotlin
    private val _dlnaState = MutableStateFlow(ProtocolState.DISABLED)
    val dlnaState: StateFlow<ProtocolState> = _dlnaState.asStateFlow()
```
After `private var castReceiver: CastReceiver? = null` add:
```kotlin
    private var dlnaReceiver: DlnaReceiver? = null
```

- [ ] **Step 3: Start / stop wiring**

In `startReceivers()` change the log line to
`Logger.i("Starting receivers: AirPlay=${settings.airPlayEnabled}, Cast=${settings.castEnabled}, DLNA=${settings.dlnaEnabled}")`
and add `if (settings.dlnaEnabled)      startDlna(settings)` after `startCast()`.

In `stopAllReceiversInternal()` add `try { dlnaReceiver?.stop() } catch (e: Exception) { Logger.e("DLNA stop error", e) }` after the Cast line, `dlnaReceiver = null` after `castReceiver = null`, and `_dlnaState.value = ProtocolState.DISABLED` after `_castState.value = …`.

- [ ] **Step 4: `startDlna`** — add after `startCast()`:

```kotlin
    /**
     * Creates and starts the [DlnaReceiver] (UPnP MediaRenderer — "Cast to Device" on Windows).
     *
     * Idempotent like [startAirPlay]: a redundant ACTION_START must not open a second SSDP/HTTP server.
     * DLNA reuses the AirPlay overlay flows: video → CONNECTED (streaming surface), audio → [_nowPlaying],
     * photos → [_photoFrame]. The [ActiveConnection] is only cleared when it belongs to DLNA so an AirPlay
     * session is never clobbered by a DLNA stop.
     */
    private fun startDlna(settings: AppSettings) {
        if (dlnaReceiver != null) {
            Logger.i("DLNA receiver already running — skipping duplicate start")
            return
        }
        var pendingSenderName = "DLNA Sender"
        dlnaReceiver = DlnaReceiver(
            context = applicationContext,
            displayName = settings.effectiveDisplayName,
            versionName = BuildConfig.VERSION_NAME,
            videoSurfaceProvider = { videoSurfaceProvider?.invoke() },
            isAirPlayConnected = { _airPlayState.value == ProtocolState.CONNECTED },
            onSenderNameChanged = { name -> pendingSenderName = name.ifEmpty { "DLNA Sender" } },
            onNowPlayingChanged = { info -> _nowPlaying.value = info },
            onPhotoReceived = { bytes, mimeType ->
                _photoFrame.value = PhotoFrame(bytes = bytes.copyOf(), mimeType = mimeType)
            },
            onPhotoCleared = { _photoFrame.value = null },
            onStateChanged = { state ->
                _dlnaState.value = state
                when (state) {
                    ProtocolState.CONNECTED -> {
                        _activeConnection.value = ActiveConnection(pendingSenderName, Protocol.DLNA)
                        updateNotification(isRunning = true, streamingSenderName = pendingSenderName)
                    }
                    ProtocolState.ADVERTISING,
                    ProtocolState.DISABLED,
                    ProtocolState.ERROR -> {
                        if (_activeConnection.value?.protocol == Protocol.DLNA) {
                            _activeConnection.value = null
                            updateNotification(isRunning = state == ProtocolState.ADVERTISING)
                        }
                    }
                }
            }
        ).also { it.start() }
        Logger.d("DLNA receiver started (displayName='${settings.effectiveDisplayName}')")
    }

    /** Routes a TV-remote media key to the DLNA renderer (play/pause, stop, seek, next). No-op if not running. */
    fun sendDlnaRemoteCommand(command: RemoteCommand) {
        dlnaReceiver?.remote(command)
    }
```

- [ ] **Step 5: Build** — `./gradlew :app:assembleGoogletvDebug` → BUILD SUCCESSFUL. Also `./gradlew :test-runner:test` → still green (the service is excluded, but `PhairPlayServiceTest` constants are unchanged).

- [ ] **Step 6: Hand-off** — `feat(service): host the DLNA receiver beside AirPlay and Cast`

---

### Task 15: UI — overlay routing, remote keys, home card, settings toggle, resources

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/phairplay/ui/HomeFragment.kt`
- Modify: `app/src/main/kotlin/com/phairplay/ui/SettingsFragment.kt`
- Modify: `app/src/main/res/layout/fragment_home.xml`
- Modify: `app/src/main/res/layout/fragment_settings.xml`
- Modify: `app/src/main/res/layout/card_protocol_status.xml`
- Modify: `app/src/main/res/values/strings.xml`, `values-de/strings.xml`, `values-fr/strings.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/drawable/ic_dlna.xml`

- [ ] **Step 1: `MainActivity.kt` — DLNA state, overlay order, remote keys**

Add import `import com.phairplay.dlna.RemoteCommand`. Add the field `private var currentDlnaState = ProtocolState.DISABLED` next to `currentAirPlayState`.

In `observeOverlayState()` add a fifth collector:
```kotlin
        lifecycleScope.launch {
            svc.dlnaState.collectLatest { state ->
                currentDlnaState = state
                updateOverlay()
            }
        }
```

Replace the body of `updateOverlay()` with:
```kotlin
        val photoFrame = currentPhotoFrame
        val nowPlaying = currentNowPlaying
        val pin = currentPin
        val videoActive = currentAirPlayState == ProtocolState.CONNECTED ||
            currentDlnaState == ProtocolState.CONNECTED
        when {
            // PIN pairing (access control) happens before streaming — show the code over everything.
            pin != null -> showPinScreen(pin)
            // Audio-only (AirPlay system audio / Music, DLNA music): now-playing card instead of a black surface.
            nowPlaying != null -> showNowPlayingScreen(nowPlaying)
            // Photos (AirPlay /photo, DLNA image items). Safe above the surface: the service clears the photo
            // when an AirPlay stream connects, and the DLNA renderer clears it when a video loads.
            photoFrame != null -> showPhotoScreen(photoFrame)
            videoActive -> showStreamingScreen()
            else -> hideStreamingScreen()
        }
```

Replace `onKeyDown` with:
```kotlin
    /**
     * Routes TV-remote media keys while an overlay is showing. AirPlay keys go back to the sender via DACP
     * (reverse remote); DLNA keys act locally on the renderer, which events the change so the control
     * point's UI follows. Returns false for other keys so normal navigation is unaffected.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val airPlayActive = currentAirPlayState == ProtocolState.CONNECTED ||
            (currentNowPlaying != null && currentDlnaState != ProtocolState.CONNECTED)
        if (currentDlnaState == ProtocolState.CONNECTED && !airPlayActive) {
            val command = when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> RemoteCommand.PLAY_PAUSE
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> RemoteCommand.STOP
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> RemoteCommand.NEXT
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteCommand.SEEK_FORWARD
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteCommand.SEEK_BACK
                else -> null
            }
            if (command != null) {
                service?.sendDlnaRemoteCommand(command)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        if (airPlayActive) {
            val command = when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> com.phairplay.airplay.DacpClient.CMD_PLAY_PAUSE
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> com.phairplay.airplay.DacpClient.CMD_NEXT
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> com.phairplay.airplay.DacpClient.CMD_PREV
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> com.phairplay.airplay.DacpClient.CMD_FF
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> com.phairplay.airplay.DacpClient.CMD_REW
                else -> null
            }
            if (command != null) {
                service?.sendAirPlayRemoteCommand(command)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
```
The AirPlay branch is byte-for-byte the previous behaviour (the old `overlayActive` condition, now named `airPlayActive`, minus the case where the now-playing card belongs to DLNA).

- [ ] **Step 2: `HomeFragment.kt`** — add `private lateinit var cardDlna: View`; `cardDlna = view.findViewById(R.id.card_dlna)` in `bindViews`; `setupCard(cardDlna, R.drawable.ic_dlna, R.string.protocol_dlna)` in `configureProtocolCards`; a collector in `observeServiceState`:
```kotlin
        viewLifecycleOwner.lifecycleScope.launch {
            svc.dlnaState.collectLatest { state -> updateProtocolCard(cardDlna, state) }
        }
```
KDoc: `(AirPlay / Cast)` → `(AirPlay / Cast / DLNA)`; `(cardAirPlay or cardCast)` → `(cardAirPlay, cardCast or cardDlna)`.

- [ ] **Step 3: `fragment_home.xml`** — give the Cast include `android:layout_marginEnd="12dp"` and add after it:
```xml
            <!-- DLNA card -->
            <include
                android:id="@+id/card_dlna"
                layout="@layout/card_protocol_status"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1" />
```
Update the ASCII header comment's card row to `│  📺 AirPlay │ │  🔵 Cast    │ │  🟠 DLNA    │`.

`card_protocol_status.xml`: comments `(AirPlay/Cast)` → `(AirPlay/Cast/DLNA)` and `"AirPlay", "Cast"` → `"AirPlay", "Cast", "DLNA"`.

- [ ] **Step 4: `SettingsFragment.kt` and `fragment_settings.xml`**

Layout: after the `row_cast` include add
```xml
        <include layout="@layout/settings_toggle_row"
            android:id="@+id/row_dlna"
            android:layout_width="match_parent"
            android:layout_height="72dp" />
```
Fragment: `private lateinit var rowDlna: View`; `rowDlna = view.findViewById(R.id.row_dlna)`; `configureToggleRow(rowDlna, R.string.setting_dlna_enabled, R.string.setting_dlna_subtitle)`; `setToggle(rowDlna, settings.dlnaEnabled)`; `setToggleListener(rowDlna) { enabled -> saveAndRestart { it.copy(dlnaEnabled = enabled) } }` (restart so SSDP/HTTP start or stop immediately).

Also in `showDisplayNameDialog()`, change the two `save { it.copy(displayName = …) }` calls (OK and "Reset to default") to `saveAndRestart { … }`. The spec requires the DLNA friendly name to follow a display-name change without a stale entry lingering in Windows; both receivers read the name only at start, so a restart is the mechanism. This also makes the AirPlay name update immediately, which was previously deferred to the next manual restart.

- [ ] **Step 5: Strings, colour, icon**

`values/strings.xml`: after `protocol_cast` add `<string name="protocol_dlna">DLNA</string>`; after `setting_cast_subtitle` add
```xml
    <string name="setting_dlna_enabled">DLNA Renderer</string>
    <string name="setting_dlna_subtitle">Accept "Cast to Device" from Windows and DLNA apps</string>
```
after `content_desc_cast_icon` add `<string name="content_desc_dlna_icon">DLNA protocol icon</string>`; change `notification_channel_description` to `Persistent notification for the AirPlay/Cast/DLNA receiver service`.

`values-de/strings.xml`: after `setting_cast_subtitle` add
```xml
    <string name="setting_dlna_enabled">DLNA-Renderer</string>
    <string name="setting_dlna_subtitle">„An Gerät übertragen“ von Windows und DLNA-Apps akzeptieren</string>
```
and `notification_channel_description` → `Benachrichtigung für den AirPlay/Cast/DLNA-Empfänger-Dienst`.

`values-fr/strings.xml`: after `setting_cast_enabled` add `<string name="setting_dlna_enabled">Récepteur DLNA</string>`.

`values/colors.xml`: after `protocol_cast` add
```xml
    <!-- DLNA: amber, distinct from the two blues -->
    <color name="protocol_dlna">#FFFF9F0A</color>
```

`drawable/ic_dlna.xml` (same shape as `ic_cast.xml`):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <size android:width="32dp" android:height="32dp" />
    <solid android:color="@color/protocol_dlna" />
</shape>
```

- [ ] **Step 6: Build and lint**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug`
Expected: BUILD SUCCESSFUL (catches any missed `R.id`/`R.string`).
Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug`
Expected: BUILD SUCCESSFUL (`warningsAsErrors`; `MissingTranslation` is disabled so the partial fr strings are fine).

- [ ] **Step 7: Hand-off** — `feat(ui): DLNA card, settings toggle, overlay routing and remote keys`

---

### Task 16: Tooling, ADR-005 and documentation

**Files:**
- Modify: `tools/collect-device-logs.sh`
- Create: `docs/decisions/ADR-005-dlna-hand-rolled-upnp.md`
- Modify: `README.md`, `CHANGELOG.md`, `docs/ARCHITECTURE.md`, `docs/spec/REQUIREMENTS.md`, `docs/spec/PROJECT_PLAN.md`, `docs/spec/TECHNICAL_SPEC.md`, `docs/TESTING.md`, `docs/guides/TROUBLESHOOTING.md`, `docs/decisions/ADR-002-service-architecture.md`

- [ ] **Step 1: `tools/collect-device-logs.sh`** — in the `adb logcat` filter list add `'DlnaReceiver:V' \` and `'MediaRenderer:V' \` after the `'RtspHandler:V' \` line (Timber tags by class name).

- [ ] **Step 2: Create `docs/decisions/ADR-005-dlna-hand-rolled-upnp.md`** (format of ADR-004):

```markdown
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
- The HTTP port is a fixed constant (`DlnaConstants.DEFAULT_HTTP_PORT`) with an ephemeral fallback; the
  SSDP LOCATION always carries the port actually bound.
- A `WifiManager.MulticastLock` is held while the receiver runs (uses the existing
  `CHANGE_WIFI_MULTICAST_STATE` permission). No new permissions.
- Only formats `MediaPlayer` decodes are advertised (no raw LPCM, no WMA/WMV); Windows transcodes the rest
  or reports a file as unplayable. If real files fail, Media3 is the next step and a separate decision.
- Volume from the sender is applied to the DLNA stream, not the TV's system volume.
- A DLNA video is refused (fault 501) while an AirPlay session holds the Surface; audio and photos are not.

## Alternatives considered

1. **jUPnP / Cling** — rejected for the dependency and transport reasons above.
2. **Generalise `AirPlayVideoPlayer` into a shared player now** — deferred; the AirPlay wrapper lacks volume,
   transport callbacks and audio-only mode, and has no tests. Extract a shared player only if Cast playback
   is ever built and there are two real users.

## References

- UPnP Device Architecture 1.1; AVTransport:1, RenderingControl:1, ConnectionManager:1 service templates
- DLNA Guidelines (DMR device class, `X_DLNADOC`, protocolInfo / DLNA.ORG_PN)
- Spec: `docs/superpowers/specs/2026-09-06-dlna-mediarenderer-design.md`
```

- [ ] **Step 3: `ADR-002`** — add `└── DlnaReceiver` to the architecture tree and "AirPlay/Cast receivers" → "AirPlay/Cast/DLNA receivers".

- [ ] **Step 4: `README.md`**
- Intro line: "…AirPlay 2 receiver for Android TV and Fire TV" → "…AirPlay 2 and DLNA receiver for Android TV and Fire TV. It lets your macOS or iOS device mirror its screen and audio to your TV, and lets Windows and DLNA apps cast video, music and photos to it — no Apple TV or Chromecast required."
- Status paragraph: replace "Windows senders will be served by DLNA instead." with "Windows senders are served by the DLNA MediaRenderer ("Cast to Device")."
- Features: add a `### DLNA / UPnP MediaRenderer` section after the AirPlay section:
  - Windows "Cast to Device" — video, music and photos
  - BubbleUPnP, VLC and other UPnP control points
  - Transport controls, seek and volume from the sender; TV remote play/pause/stop/seek
  - Now-playing card with metadata and album art for music; full-screen photos
- "What PhairPlay does NOT do": Miracast bullet → "**Miracast** — impossible for a sideloaded app (ADR-004). Use DLNA ("Cast to Device") from Windows instead."; add "**DLNA formats Android cannot decode** (WMA/WMV, raw LPCM) — Windows transcodes or reports them as unplayable."
- How to Use: add a "From Windows" subsection: right-click a video/music/photo file → Cast to Device → pick the TV; prerequisites: same network, network profile *Private*, Network discovery on, "Windows Media Player Network Sharing Service" running.
- Known Limitations: add "DLNA volume changes the cast stream only, not the TV's volume."

- [ ] **Step 5: `docs/ARCHITECTURE.md`** — add a `### DLNA / UPnP MediaRenderer` component table (one row per file in the plan's File structure, `DlnaReceiver` marked **Orchestrator**), and a short "How does DLNA work?" section after the AirPlay one: SSDP announce → control point fetches description → SetAVTransportURI + Play over SOAP → TV fetches the media URL itself with MediaPlayer → GENA LastChange keeps the sender's UI in step. Add `DlnaReceiver` beside `AirPlayReceiver` under `PhairPlayService` in the data-flow diagram header.

- [ ] **Step 6: `docs/spec/REQUIREMENTS.md`** — replace §1.3 "Miracast Receiver — removed" note's DLNA sentence with a pointer to the new §1.5; add `### 1.5 DLNA / UPnP MediaRenderer` with FR-40…FR-47: SSDP discovery; root/service descriptions incl. `X_DLNADOC` DMR-1.50; AVTransport actions (list); RenderingControl volume/mute; ConnectionManager sink list; GENA eventing with initial event and LastChange; video/audio/photo rendering on the existing overlays; TV-remote control. Settings table: add "DLNA enabled | on/off | on". Out-of-scope table: DLNA row → "Implemented (sub-project 3)"; add "DLNA server/control point, transcoding, playlists, subtitles | Not planned".

- [ ] **Step 7: `docs/spec/PROJECT_PLAN.md`** — status row 6 → `| 6 | M6 – DLNA MediaRenderer | ✅ Complete | Hand-rolled UPnP (SSDP/SOAP/GENA), MediaPlayer playback, Windows "Cast to Device" + BubbleUPnP/VLC validated — ADR-005 |`; replace the Phase 6 removal note with a Phase 6 section listing the sub-project's deliverables and tests; keep the Miracast removal as a one-line historical note pointing at ADR-004.

- [ ] **Step 8: `docs/spec/TECHNICAL_SPEC.md`** — add `DlnaReceiver` to the architecture diagram beside `AirPlayReceiver`; component-table rows for the dlna package; a "DLNA" column in the codec/container matrix listing the sink MIME types from `ProtocolInfoList`; a short §"DLNA ports" note (SSDP 1900 UDP, HTTP `49494` TCP with ephemeral fallback).

- [ ] **Step 9: `docs/TESTING.md`** — in "What Is Tested" add the dlna test classes; in "What Is NOT Unit Tested" add `DlnaPlayer`, `SsdpAdvertiser`, `DlnaHttpServer`, `HttpNotifySender`, `DlnaReceiver` (sockets/MediaPlayer); add `### Scenario 8: DLNA "Cast to Device"` with the Windows MP4/MP3/JPEG, BubbleUPnP next-track and VLC seek steps and expected TV behaviour.

- [ ] **Step 10: `docs/guides/TROUBLESHOOTING.md`** — rename the first section to "Device not appearing in AirPlay / Cast / DLNA menu" and add a `## Windows "Cast to Device" does not list the TV` section: network profile must be *Private*; Network discovery on; "Windows Media Player Network Sharing Service" running; router multicast/AP isolation; check `adb logcat -s DlnaReceiver` for "advertising". Add `## DLNA plays audio but no video / "can't play"`: format not in the sink list → Windows transcodes; try MP4/H.264 + AAC.

- [ ] **Step 11: `CHANGELOG.md`** — under `[Unreleased]` add
```markdown
### Added
- DLNA / UPnP MediaRenderer: Windows "Cast to Device", BubbleUPnP and VLC can play video, music and
  photos on the TV. Hand-rolled SSDP/SOAP/GENA stack, MediaPlayer playback, third protocol card and
  settings toggle, TV-remote transport keys. See `docs/decisions/ADR-005-dlna-hand-rolled-upnp.md`.
```
(If an `### Added` block already exists under `[Unreleased]`, append the bullet there.)

- [ ] **Step 12: Verify** — `grep -rn "sub-project 3" README.md docs CHANGELOG.md` shows only historical notes (ADR-004, the changelog "Removed" entry, and the Miracast spec/plan); nothing describes DLNA as "planned".

- [ ] **Step 13: Hand-off** — `docs: DLNA MediaRenderer (ADR-005), README, specs, testing and troubleshooting`

---

### Task 17: CI-equivalent and on-device verification

- [ ] **Step 1: Full local CI**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test :app:lintGoogletvDebug :app:lintFiretvDebug :app:assembleGoogletvDebug :app:assembleFiretvDebug`
Expected: BUILD SUCCESSFUL; test count = 230 baseline + the new dlna tests (roughly 100) with 0 failures. Record the exact count in the hand-off.

- [ ] **Step 2: File-size rule** — `find app/src/main/kotlin/com/phairplay/dlna -name '*.kt' -exec wc -l {} +` → no file over 400 lines.

- [ ] **Step 3: Install on the Pi**

```bash
adb connect 192.168.1.185:5555
```
```bash
adb install -r app/build/outputs/apk/googletv/debug/app-googletv-debug.apk
```
Launch PhairPlay; confirm in `adb logcat -s DlnaReceiver:V SsdpAdvertiser:V DlnaHttpServer:V` that the HTTP server binds and "SSDP advertising" is logged; the home screen shows three cards with DLNA "Advertising".

- [ ] **Step 4: Description sanity from the Mac**

```bash
curl -s http://192.168.1.185:49494/description.xml
```
Expected: the root device XML with the TV's friendly name and three services. Then:
```bash
curl -s -X POST http://192.168.1.185:49494/control/ConnectionManager -H 'SOAPACTION: "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo"' -H 'Content-Type: text/xml; charset="utf-8"' --data '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1"/></s:Body></s:Envelope>'
```
Expected: 200 with the sink list.

- [ ] **Step 5: Windows "Cast to Device"** (Jeremy, at the PC) — right-click an MP4 → Cast to Device → the TV appears under its display name; video plays full-screen; pause/seek/stop and the volume slider work; the DLNA card shows Connected with "Windows PC". Repeat with an MP3 (now-playing card with title/artist and art) and a JPEG (full-screen photo). Casting a folder of photos advances slides.

- [ ] **Step 6: BubbleUPnP** (Android phone) — select the TV as renderer, play an album: second track starts automatically via SetNextAVTransportURI; TV remote play/pause toggles and the phone's UI follows.

- [ ] **Step 7: VLC** (Mac) — Playback → Renderer → the TV; play a video; seek from VLC.

- [ ] **Step 8: Regression** — AirPlay mirroring from the Mac still works; while mirroring, a DLNA video cast is refused (Windows shows an error) but a DLNA audio cast plays; toggling DLNA off in Settings stops advertising (card shows Disabled) and `curl` to the description URL fails.

- [ ] **Step 9: Hand-off** — `git status --short` summary for Jeremy with the final test count and the on-device results (include anything that did not work verbatim from the logs).
