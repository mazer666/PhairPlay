# AirPlay Audio-Only (Music.app) Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Music.app / iTunes on macOS play audio through PhairPlay on the Pi 4 Android TV instead of connecting and dropping.

**Architecture:** Phase A diagnoses the failing session on the real device with no code changes and classifies it (A1–A4). Phase B applies the ordered hypotheses from the spec: H1 moves the `_raop._tcp` TXT record into a pure, unit-testable `RaopTxtRecord` object and advertises RSA (`et=0,1`) instead of the unfinished FairPlay types, so Music.app sends an `rsaaeskey` that the existing `RaopRsa` + `AlacDecoder` path already handles; a failed key recovery now rejects `ANNOUNCE` with 500 instead of playing silence. H2/H3 are gated stop points.

**Tech Stack:** Kotlin, Android `NsdManager`, existing `RaopRsa` (RSA-OAEP), native `libalac`, JUnit 4 via the `:test-runner` JVM module, ADB against the Pi 4 at `192.168.1.185:5555`.

**Spec:** `docs/superpowers/specs/2026-09-06-airplay-audio-only-fix-design.md`

---

## Ground rules for whoever executes this

- **Git is human-managed.** Never run `git add`, `git commit`, or any other git write. Each task ends with a *hand-off* step that gives Jeremy a suggested commit message; he commits.
- **Ask before:** `sudo` (needed for `tcpdump`), installing dependencies, deleting files.
- **Build env:** `JAVA_HOME` is already JDK 17. Pass `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk` on every Gradle call (there is no `local.properties`). Run **one command per shell call** — no `&&` chains, no `cd &&`.
- **Device:** `adb -s 192.168.1.185:5555`. If `adb devices` shows nothing, run `adb connect 192.168.1.185:5555` first.
- **Style:** match the existing files (KDoc `WHY/HOW` headers, `Logger.*` not `Timber`, files ≤ 400 lines). Comments explain *why*.
- **Phase B must not start until Phase A's diagnosis paragraph is written into Task 3.**

## File structure

**Created**
- `app/src/main/kotlin/com/phairplay/airplay/RaopTxtRecord.kt` — pure function building the `_raop._tcp` service name and TXT attribute map. No Android imports, so it compiles in the JVM test-runner.
- `app/src/test/kotlin/com/phairplay/airplay/RaopTxtRecordTest.kt` — unit tests for the above.

**Modified**
- `app/src/main/kotlin/com/phairplay/airplay/MdnsService.kt:206-229` — `registerRaopService` applies the map from `RaopTxtRecord` instead of hard-coding attributes.
- `app/src/main/kotlin/com/phairplay/airplay/SdpParser.kt:70-76, 118-121, 133-149, 244-251, 318-327` — records when an `rsaaeskey` was present but could not be recovered.
- `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt:718-735` — `ANNOUNCE` returns 500 when the audio key is unrecoverable.
- `app/src/test/kotlin/com/phairplay/airplay/SdpParserTest.kt` — new test for the unrecoverable-key flag.
- `app/src/test/kotlin/com/phairplay/airplay/RtspHandlerTest.kt` — new test for the 500 path.
- `app/src/main/kotlin/com/phairplay/airplay/handshake/BufferedAudioServer.kt:17-22`, `RtspHandler.kt:668-671`, `AudioPlayer.kt:298-299`, `README.md:54-55,183-185`, `CHANGELOG.md` — wording corrections.

---

## Phase A — Diagnosis (no code changes)

### Task 1: Build and install the googletv debug APK on the Pi

**Files:** none modified.

- [x] **Step 1: Confirm the device is attached**

Run: `adb devices -l`
Expected: one line containing `192.168.1.185:5555     device product:lineage_rpi4_tv`. If it is missing, run `adb connect 192.168.1.185:5555` and repeat.

- [x] **Step 2: Build the debug APK**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :app:assembleGoogletvDebug --console=plain`
Expected: ends with `BUILD SUCCESSFUL`. Output APK: `app/build/outputs/apk/googletv/debug/app-googletv-debug.apk`. First build compiles the NDK libraries (`libplayfair`, `libalac`) and can take several minutes.

- [x] **Step 3: Install over the release build**

Run: `adb -s 192.168.1.185:5555 install -r app/build/outputs/apk/googletv/debug/app-googletv-debug.apk`
Expected: `Success`. If you get `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature mismatch with the beta.1 release APK), **ask Jeremy** before uninstalling the release build — uninstalling loses his settings. Command he can approve: `adb -s 192.168.1.185:5555 uninstall com.phairplay.googletv`, then repeat this step.

- [x] **Step 4: Launch the app and confirm it advertises**

Run: `adb -s 192.168.1.185:5555 shell am start -n com.phairplay.googletv/com.phairplay.MainActivity`
Then: `adb -s 192.168.1.185:5555 logcat -d -v time | grep -E 'mDNS|_raop|_airplay|ADVERTISING' | tail -20`
Expected: lines showing both `_airplay._tcp` and `_raop._tcp` registered and the AirPlay state `ADVERTISING`.

- [x] **Step 5: Hand-off**

Nothing to commit. Report the exact build time and APK path.

### Task 2: Capture the failing Music.app session

**Files:** none modified. Output goes to `device-test-logs/<timestamp>/`, which `.gitignore` already excludes.

- [x] **Step 1: Clear the log buffer**

Run: `adb -s 192.168.1.185:5555 logcat -c`

- [x] **Step 2: Start a pid-filtered live capture in the background**

Run: `adb -s 192.168.1.185:5555 shell pidof com.phairplay.googletv`
Expected: a PID. Then run (in the background, writing to the scratchpad):
`adb -s 192.168.1.185:5555 logcat -v time --pid=<PID> > <scratchpad>/music-session.log`
Pid filtering captures every tag the app emits, so nothing is lost to tag guessing.

- [x] **Step 3: Reproduce on the Mac** (Jeremy does this, or you ask him to)

On the Mac: open Music.app → click the AirPlay icon in the playback bar → select the PhairPlay device → press Play. Wait until macOS shows the device deselected / audio returns to the Mac (the drop). Note the wall-clock time of the drop.

- [x] **Step 4: Snapshot device state**

Run: `tools/collect-device-logs.sh`
Expected: `Wrote device test logs to device-test-logs/<timestamp>`. Stop the background logcat.

- [x] **Step 5: Extract the RTSP timeline**

Run: `grep -n -E 'OPTIONS|ANNOUNCE|SETUP|RECORD|TEARDOWN|FLUSH|GET /info|pair-|fp-setup|rsaaeskey|fpaeskey|FairPlay|RECORD —|Audio muted|decode healthy|NTP|timing|Session:|closed|error|Exception' <scratchpad>/music-session.log`
Expected: an ordered list of the RTSP verbs the Mac sent and every log line about keys, decode health, and connection close. Copy this list verbatim into Task 3.

- [x] **Step 6: Hand-off**

Nothing to commit. Attach the grep output and the `device-test-logs` folder path to your report.

### Task 3: Classify the failure and amend this plan

**Files:**
- Modify: this file, section "Diagnosis result" below.

- [x] **Step 1: Classify using the table**

| Class | You see in the Task 2 timeline | Phase B entry point |
|---|---|---|
| **A1** discovery / capability | Mac sends only `GET /info` and/or `OPTIONS`, then the connection closes. No `ANNOUNCE`. | H1 (Task 4) — also compare `/info` `features` with Shairport Sync classic |
| **A2** classic RAOP handshake | `ANNOUNCE` arrives; a `SETUP` or `RECORD` returns non-200, or the Mac closes right after a 200. | Fix the failing verb first; then H1 |
| **A3** key / decrypt | `RECORD` returns 200; `fpaeskey` present; `RAOP FairPlay audio-key decrypt failed` or `Audio muted: ALAC decoded only …`; Mac tears down seconds later. | H1 (Task 4) |
| **A4** timing | `RECORD` returns 200; no `Audio muted`; NTP/timing warnings; Mac tears down. | Investigate `TimingHandler` / `AirPlayNtpClient` first; H1 does not help |

- [x] **Step 2: If the TV log does not show *why* the Mac closed, propose a Mac-side capture**

Ask Jeremy (this needs `sudo`): `sudo tcpdump -i en0 -w <scratchpad>/airplay.pcap host 192.168.1.185 and '(tcp port 7000 or udp portrange 6000-6003)'`. Then repeat Task 2 Step 3 and read the pcap with `tcpdump -r <scratchpad>/airplay.pcap -A | grep -E 'RTSP|ANNOUNCE|SETUP|RECORD|TEARDOWN|rsaaeskey|fpaeskey'`. Only do this with his explicit yes.

- [x] **Step 3: Write the diagnosis paragraph**

Replace the placeholder text in the "Diagnosis result" section at the bottom of this file with: the class (A1–A4), the exact last successful RTSP verb, the log lines proving it, and which Phase B task is the entry point.

- [x] **Step 4: Hand-off**

Suggested commit for Jeremy: `docs(plan): record Music.app AirPlay failure diagnosis (class A?)`.

---

## Phase B — Fix

### Task 3b: Accept large album-artwork `SET_PARAMETER` bodies (fixes Fault 1)

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/airplay/RtspRequestReader.kt`
- Modify: `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt:125-128, 998`
- Create: `app/src/test/kotlin/com/phairplay/airplay/RtspRequestReaderTest.kt`

Music.app sends album artwork as `SET_PARAMETER` with `Content-Type: image/jpeg` and bodies of several hundred KB. The reader applies the 64 KB control-message limit to every body except `PUT /photo`, so the artwork is rejected and the connection closed. Fix: give artwork its own limit, the same way photos already have one. Genuinely oversized bodies still disconnect (DoS guard unchanged).

- [x] **Step 1: Write the failing tests**

```kotlin
package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * RtspRequestReaderTest — body-size limits on the AirPlay control socket.
 *
 * WHY: Music.app sends album artwork as a SET_PARAMETER whose body is hundreds of KB. Applying
 * the 64 KB control-message limit to it closed the connection 190 ms after RECORD, which macOS
 * showed as the device "dropping". Artwork gets its own limit; everything else keeps the
 * defensive 64 KB cap.
 */
class RtspRequestReaderTest {

    private val controlLimit = 64 * 1024
    private val artworkLimit = 4 * 1024 * 1024

    private fun request(method: String, contentType: String, body: ByteArray): ByteArrayInputStream {
        val head = "$method rtsp://192.168.1.185/1 RTSP/1.0\r\n" +
            "CSeq: 7\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "\r\n"
        return ByteArrayInputStream(head.toByteArray(Charsets.ISO_8859_1) + body)
    }

    private fun reader() = RtspRequestReader(
        maxMessageBytes = controlLimit,
        maxPhotoBytes = 25 * 1024 * 1024,
        maxArtworkBytes = artworkLimit
    )

    @Test
    fun `SET_PARAMETER artwork larger than the control limit is accepted`() {
        val artwork = ByteArray(432_829) { 0x42 }
        val parsed = reader().read(request("SET_PARAMETER", "image/jpeg", artwork))
        assertNotNull("artwork must not be rejected", parsed)
        assertEquals(432_829, parsed!!.bodyBytes.size)
        assertEquals("image/jpeg", parsed.headers["Content-Type"])
    }

    @Test
    fun `SET_PARAMETER artwork above the artwork limit is still rejected`() {
        val huge = ByteArray(artworkLimit + 1)
        assertNull(reader().read(request("SET_PARAMETER", "image/png", huge)))
    }

    @Test
    fun `non-artwork body above the control limit is rejected`() {
        val big = ByteArray(controlLimit + 1) { 0x61 }
        assertNull(reader().read(request("ANNOUNCE", "application/sdp", big)))
    }

    @Test
    fun `small text SET_PARAMETER still parses`() {
        val body = "volume: -20.0\r\n".toByteArray()
        val parsed = reader().read(request("SET_PARAMETER", "text/parameters", body))
        assertNotNull(parsed)
        assertEquals("volume: -20.0\r\n", parsed!!.body)
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --tests 'com.phairplay.airplay.RtspRequestReaderTest' --console=plain`
Expected: compile error — no `maxArtworkBytes` parameter.

- [x] **Step 3: Implement**

In `RtspRequestReader.kt`, change the constructor and `readBody`:

```kotlin
internal class RtspRequestReader(
    private val maxMessageBytes: Int,
    private val maxPhotoBytes: Int,
    /** Album artwork arrives as `SET_PARAMETER` with an image-typed body of several hundred KB. */
    private val maxArtworkBytes: Int = maxPhotoBytes
) {
```

and

```kotlin
        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val bodyLimit = when {
            method == "PUT" && uri.substringBefore("?") == PhotoHandler.PHOTO_PATH -> maxPhotoBytes
            method == "SET_PARAMETER" && isImage(headers) -> maxArtworkBytes
            else -> maxMessageBytes
        }
```

plus, at the bottom of the class:

```kotlin
    private fun isImage(headers: Map<String, String>): Boolean =
        headers["Content-Type"]?.lowercase()?.startsWith("image/") == true
```

In `RtspHandler.kt` pass the new limit (line ~125):

```kotlin
    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = PhotoHandler.MAX_PHOTO_BYTES,
        maxArtworkBytes = MAX_ARTWORK_BYTES
    )
```

and in the companion object next to `MAX_MESSAGE_BYTES`:

```kotlin
        /** Album artwork via SET_PARAMETER: Music.app sends 300–800 KB JPEGs; 4 MB leaves headroom. */
        private const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
```

- [x] **Step 4: Run the reader tests, then the whole suite**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --tests 'com.phairplay.airplay.RtspRequestReaderTest' --console=plain`
Expected: 4 tests pass.
Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --console=plain`
Expected: 226 tests, 0 failures.

- [x] **Step 5: Hand-off**

Suggested commit: `fix(airplay): accept large album-artwork SET_PARAMETER bodies instead of dropping the session`.


### Task 4: `RaopTxtRecord` — pure TXT record builder advertising RSA (H1)

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/airplay/RaopTxtRecord.kt`
- Create: `app/src/test/kotlin/com/phairplay/airplay/RaopTxtRecordTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RaopTxtRecordTest — pins the `_raop._tcp` advertisement that decides which audio key path
 * macOS Music.app uses.
 *
 * WHY: advertising only FairPlay encryption types (`et=3,5`) forced Music.app onto the
 * FairPlay-wrapped key path, which this receiver cannot unwrap, so audio-only sessions dropped.
 * Advertising RSA (`et=1`) routes Music.app to `rsaaeskey`, which `RaopRsa` decrypts. Shairport
 * Sync's classic mode advertises exactly `et=0,1` and plays Music.app audio on current macOS.
 */
class RaopTxtRecordTest {

    private val record = RaopTxtRecord.build(macHex = "AABBCCDDEEFF", displayName = "Living Room TV")

    @Test
    fun `service name is MAC without colons, an at sign, then the display name`() {
        assertEquals("AABBCCDDEEFF@Living Room TV", record.serviceName)
    }

    @Test
    fun `advertises RSA encryption and no FairPlay types`() {
        val et = record.attributes.getValue("et").split(",")
        assertTrue("et must offer RSA (1)", "1" in et)
        assertTrue("et must offer unencrypted (0)", "0" in et)
        assertFalse("et must not offer FairPlay (3) until fpaeskey unwrap is verified", "3" in et)
        assertFalse("et must not offer FairPlay SAPv2.5 (5) until verified", "5" in et)
    }

    @Test
    fun `keeps the codec list unchanged`() {
        assertEquals("0,1,2,3", record.attributes["cn"])
    }

    @Test
    fun `contains every key a RAOP sender requires`() {
        val required = listOf("cn", "da", "et", "md", "sv", "tp", "vn", "vs", "am")
        required.forEach { key ->
            assertTrue("missing TXT key '$key'", record.attributes.containsKey(key))
        }
    }

    @Test
    fun `uses UDP transport and RAOP version 65537`() {
        assertEquals("UDP", record.attributes["tp"])
        assertEquals("65537", record.attributes["vn"])
    }
}
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --tests 'com.phairplay.airplay.RaopTxtRecordTest' --console=plain`
Expected: compilation error `Unresolved reference: RaopTxtRecord`.

- [x] **Step 3: Write the implementation**

```kotlin
package com.phairplay.airplay

/**
 * RaopTxtRecord — builds the `_raop._tcp` mDNS service name and TXT attributes.
 *
 * WHY: The TXT record tells a sender which audio-key wrapping it may use. Music.app picks the
 * strongest type the receiver lists. Listing FairPlay (`3`, `5`) made it send `fpaeskey`, which
 * this receiver cannot unwrap, so audio-only sessions ended within seconds. Listing RSA (`1`)
 * makes it send `rsaaeskey`, which [com.phairplay.airplay.handshake.RaopRsa] recovers with the
 * embedded AirPort Express key — the same path Shairport Sync's classic mode uses.
 *
 * HOW: Pure Kotlin, no Android imports, so the record is unit-tested in the JVM test-runner.
 * [MdnsService] applies [attributes] to an `NsdServiceInfo`.
 */
object RaopTxtRecord {

    data class Record(val serviceName: String, val attributes: Map<String, String>)

    /**
     * @param macHex      Device MAC with colons removed, upper-case (e.g. `AABBCCDDEEFF`).
     * @param displayName Name shown in sender pickers.
     */
    fun build(macHex: String, displayName: String): Record = Record(
        serviceName = "$macHex@$displayName",
        attributes = mapOf(
            "cn" to CODECS,
            "da" to "true",
            "et" to ENCRYPTION_TYPES,
            "md" to METADATA_TYPES,
            "sv" to "false",
            "tp" to "UDP",
            "vn" to RAOP_VERSION,
            "vs" to SERVER_VERSION,
            "am" to MODEL
        )
    )

    /** Codec numbers: 0 PCM, 1 ALAC, 2 AAC, 3 AAC-ELD. */
    private const val CODECS = "0,1,2,3"

    /**
     * Encryption types: 0 none, 1 RSA (`rsaaeskey`). FairPlay types 3 and 5 are deliberately
     * omitted until the `fpaeskey` unwrap is verified on-device (see spec H2).
     */
    private const val ENCRYPTION_TYPES = "0,1"

    /** Metadata types: 0 text, 1 artwork, 2 progress. */
    private const val METADATA_TYPES = "0,1,2"

    private const val RAOP_VERSION = "65537"

    /** Must match [MdnsService]'s `_airplay._tcp` `srcvers` and `model`. */
    private const val SERVER_VERSION = "220.68"
    private const val MODEL = "AppleTV5,3"
}
```

- [x] **Step 4: Run the tests to verify they pass**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --tests 'com.phairplay.airplay.RaopTxtRecordTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [x] **Step 5: Hand-off**

Suggested commit: `feat(airplay): RaopTxtRecord advertises RSA audio key path (et=0,1), unit-tested`.

### Task 5: Make `MdnsService` use `RaopTxtRecord`

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/airplay/MdnsService.kt:206-229`

There is no JVM test for `MdnsService` (it is excluded from the test-runner because `NsdManager` needs the Android runtime); correctness is verified by the assemble step and Task 7 on-device.

- [x] **Step 1: Replace the body of `registerRaopService`**

Replace lines 206–229 (from `private fun registerRaopService(displayName: String) {` through the closing `}` of the `NsdServiceInfo().apply { … }` block) with:

```kotlin
    private fun registerRaopService(displayName: String) {
        val macHex = NetworkUtils.getMacAddress().replace(":", "").uppercase()
        val record = RaopTxtRecord.build(macHex = macHex, displayName = displayName)

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = record.serviceName  // required RAOP format: MAC@Name
            serviceType = SERVICE_TYPE_RAOP
            port = AIRPLAY_PORT
            record.attributes.forEach { (key, value) -> setAttribute(key, value) }
        }
```

Leave the `raopListener = createRegistrationListener(…)` and `nsdManager.registerService(…)` lines that follow unchanged.

- [x] **Step 2: Remove the now-unused constants only if nothing else uses them**

Run: `grep -n 'AIRPLAY_SERVER_VERSION\|AIRPLAY_MODEL' app/src/main/kotlin/com/phairplay/airplay/MdnsService.kt`
Expected: both are still used by `registerAirPlayService` (lines ~171-172). Keep them. Add this line to the KDoc of `AIRPLAY_SERVER_VERSION`: `Keep in sync with [RaopTxtRecord].`

- [x] **Step 3: Build both flavors and lint**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.
Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug --console=plain`
Expected: `BUILD SUCCESSFUL` (lint has `warningsAsErrors = true`; an `UnusedResources`-style failure means a constant became unused — fix by deleting it, not by suppressing).

- [x] **Step 4: Hand-off**

Suggested commit: `refactor(airplay): MdnsService applies RaopTxtRecord for the _raop._tcp advert`.

### Task 6: Reject `ANNOUNCE` with 500 when the RSA audio key cannot be recovered

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/airplay/SdpParser.kt`
- Modify: `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt:718-735`
- Test: `app/src/test/kotlin/com/phairplay/airplay/SdpParserTest.kt`
- Test: `app/src/test/kotlin/com/phairplay/airplay/RtspHandlerTest.kt`

Spec requirement: "Wrong or missing audio key → error log + RTSP 500 on ANNOUNCE; never a silent stream."

- [x] **Step 1: Write the failing SDP parser test**

Add to `SdpParserTest` (inside the class, before the companion/fixture section):

```kotlin
    // ─── Unrecoverable RSA key ────────────────────────────────────────────────

    @Test
    fun `rsaaeskey that fails RSA recovery sets audioKeyUnrecoverable`() {
        // 256 bytes that are not a valid OAEP ciphertext → RaopRsa.decryptAesKey returns null
        val junkBlob = java.util.Base64.getEncoder().encodeToString(ByteArray(256) { 0x5A })
        val sdp = """
            v=0
            o=iTunes 1 0 IN IP4 192.168.1.10
            s=iTunes
            c=IN IP4 192.168.1.185
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
            a=rsaaeskey:$junkBlob
            a=aesiv:MTIzNDU2Nzg5MDEyMzQ1Ng==
        """.trimIndent()

        val result = SdpParser.parse(sdp)
        assertNotNull(result)
        assertTrue(result!!.audioKeyUnrecoverable)
        assertFalse(result.isAudioEncrypted)
    }

    @Test
    fun `audio-only SDP without any key is not flagged unrecoverable`() {
        val result = SdpParser.parse(SDP_AUDIO_ONLY_ALAC)
        assertNotNull(result)
        assertFalse(result!!.audioKeyUnrecoverable)
    }
```

- [x] **Step 2: Run to verify failure**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --tests 'com.phairplay.airplay.SdpParserTest' --console=plain`
Expected: compile error `Unresolved reference: audioKeyUnrecoverable`.

- [x] **Step 3: Implement the flag in `SdpParser`**

(a) In `SessionDescription` (around line 325), after `val aesIv: ByteArray? = null,` add:

```kotlin
    /**
     * True when the SDP carried an `rsaaeskey` that [com.phairplay.airplay.handshake.RaopRsa]
     * could not recover. The handler rejects the session so the sender shows an error instead
     * of a silent stream.
     */
    val audioKeyUnrecoverable: Boolean = false,
```

(b) In `parse(...)`, next to `var aesKey: ByteArray? = null` (line ~71) add:

```kotlin
        var audioKeyUnrecoverable = false
```

(c) Replace the line `parseAesKey(attr)?.let { aesKey = it }` (line ~118) with:

```kotlin
                            if (attr.startsWith(RSA_KEY_PREFIX)) {
                                val recovered = parseAesKey(attr)
                                if (recovered != null) aesKey = recovered else audioKeyUnrecoverable = true
                            }
```

(d) In the `return SessionDescription(...)` call (line ~133) add `audioKeyUnrecoverable = audioKeyUnrecoverable,` after `aesIv = aesIv,`.

(e) In `parseAesKey`, change `if (!attr.startsWith("rsaaeskey:")) return null` to use the constant and log the failure:

```kotlin
    private fun parseAesKey(attr: String): ByteArray? {
        if (!attr.startsWith(RSA_KEY_PREFIX)) return null
        val blob = decodeBase64Safely(attr.removePrefix(RSA_KEY_PREFIX)) ?: return null
        if (blob.size == 16) return blob
        val key = RaopRsa.decryptAesKey(blob)
        if (key == null) {
            Logger.e("rsaaeskey: RSA recovery of ${blob.size}B blob failed — session will be rejected")
        } else {
            Logger.i("rsaaeskey: RSA-decrypted ${blob.size}B blob → 16B AES key (RSA audio path)")
        }
        return key
    }
```

(f) At the bottom of `SdpParser.kt`, next to the existing top-level `private const val DEFAULT_SAMPLE_RATE = 44100` (line ~380), add:

```kotlin
        private const val RSA_KEY_PREFIX = "rsaaeskey:"
```

`Logger.e(message: String, throwable: Throwable? = null)` accepts a single argument, so the call above compiles as written.

- [x] **Step 4: Run the parser tests**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --tests 'com.phairplay.airplay.SdpParserTest' --console=plain`
Expected: all pass, including the two new tests.

- [x] **Step 5: Write the failing handler test**

Add to `RtspHandlerTest` in the `// ─── ANNOUNCE ───` section:

```kotlin
    @Test
    fun `ANNOUNCE with an unrecoverable rsaaeskey returns 500 and does not start streaming`() {
        val junkBlob = java.util.Base64.getEncoder().encodeToString(ByteArray(256) { 0x5A })
        val sdp = """
            v=0
            o=iTunes 1 0 IN IP4 192.168.1.10
            s=iTunes
            c=IN IP4 192.168.1.185
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
            a=rsaaeskey:$junkBlob
            a=aesiv:MTIzNDU2Nzg5MDEyMzQ1Ng==
        """.trimIndent()

        val handler = createTestHandler()
        val response = handler.handleAnnouncePublic(
            RtspRequest(
                method = "ANNOUNCE",
                uri = "rtsp://192.168.1.185/1",
                headers = mapOf("CSeq" to "2", "Content-Type" to "application/sdp"),
                body = sdp
            )
        )

        assertEquals(500, response.statusCode)
        assertFalse(streamingStarted)
    }
```

- [x] **Step 6: Run to verify failure**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --tests 'com.phairplay.airplay.RtspHandlerTest' --console=plain`
Expected: the new test fails with `expected:<500> but was:<200>`.

- [x] **Step 7: Implement in `RtspHandler.handleAnnounceInternal`**

After the `if (parsed == null) { … return 400 }` block (line ~726) and before `currentSession = parsed.copy(...)`, add:

```kotlin
        if (parsed.audioKeyUnrecoverable) {
            Logger.e("ANNOUNCE: rsaaeskey could not be recovered — rejecting so the sender shows an error " +
                     "instead of a silent stream")
            currentSession = null
            return RtspResponse(statusCode = 500, statusMessage = "Internal Server Error")
        }
```

- [x] **Step 8: Run the handler tests, then the whole JVM suite**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --tests 'com.phairplay.airplay.RtspHandlerTest' --console=plain`
Expected: all pass.
Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --console=plain`
Expected: `BUILD SUCCESSFUL`; total tests = 222 baseline + 12 new = 234, 0 failures.

- [x] **Step 9: Hand-off**

Suggested commit: `fix(airplay): reject ANNOUNCE with 500 when the rsaaeskey cannot be recovered`.

### Task 7: On-device verification of H1 (acceptance criteria 1–3)

**Files:** none modified.

- [x] **Step 1: Build, install, relaunch**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :app:assembleGoogletvDebug --console=plain`
Run: `adb -s 192.168.1.185:5555 install -r app/build/outputs/apk/googletv/debug/app-googletv-debug.apk`
Run: `adb -s 192.168.1.185:5555 shell am force-stop com.phairplay.googletv`
Run: `adb -s 192.168.1.185:5555 shell am start -n com.phairplay.googletv/com.phairplay.MainActivity`

- [x] **Step 2: Confirm the new TXT record is on the wire (from the Mac)**

`dns-sd` is interactive and never exits on its own, so run each command, wait about five seconds, then press Ctrl-C.

Run on the Mac: `dns-sd -B _raop._tcp local.`
Expected: an `Add` line whose instance name ends in `@<your TV name>` (e.g. `AABBCCDDEEFF@Living Room TV`). Note it.
Run on the Mac: `dns-sd -L "<instance name>" _raop._tcp local.`
Expected: the TXT line contains `et=0,1` and does **not** contain `et=0,3,5`. If it still shows the old record, the Pi is still running the old process — repeat Step 1's force-stop and relaunch.

- [x] **Step 3: Start a pid-filtered logcat and reproduce with Music.app**

Run: `adb -s 192.168.1.185:5555 logcat -c`
Run in background: `adb -s 192.168.1.185:5555 logcat -v time --pid=$(adb -s 192.168.1.185:5555 shell pidof com.phairplay.googletv) > <scratchpad>/h1-session.log`
On the Mac: Music.app → AirPlay → PhairPlay → Play. Let it run for **5 minutes**.

- [x] **Step 4: Check the log for the RSA path and healthy decode**

Run: `grep -n -E 'rsaaeskey: RSA-decrypted|RECORD — streaming starting|Audio decode healthy|Audio muted|TEARDOWN|ANNOUNCE' <scratchpad>/h1-session.log`
Expected, in order: `rsaaeskey: RSA-decrypted 256B blob → 16B AES key`, `RECORD — streaming starting (audioOnly=true, encrypted=true)`, `Audio decode healthy (…/24 frames)`, and **no** `TEARDOWN` before the 5-minute mark. Audio must be audible from the TV.

- [x] **Step 5: Verify controls and metadata**

- Move the Mac's volume slider: expect a `SET_PARAMETER volume` log line and an audible change.
- Look at the TV: the now-playing overlay shows the track title/artist and artwork.
- Press play/pause on the TV remote: Music.app pauses/resumes (DACP). Log shows the DACP command.

- [x] **Step 6: Regression — screen mirroring**

Stop Music.app AirPlay. On the Mac: Control Center → Screen Mirroring → PhairPlay. Expected: the Mac desktop appears on the TV with audio (AAC-ELD) within ~3 s, exactly as before. Stop mirroring; TV returns to the waiting screen.

- [x] **Step 7: Record the result**

If everything in Steps 4–6 passed, write "H1 verified on-device on <date>" into the Diagnosis result section of this file and skip Task 8. If Step 4 shows `fpaeskey` instead of `rsaaeskey`, or `Audio muted`, go to Task 8.

- [x] **Step 8: Hand-off**

Suggested commit: `docs(plan): H1 verified on Pi 4 — Music.app plays via rsaaeskey`.

### Task 8: GATE — H2 (FairPlay v2 unwrap) — only if Task 7 failed

**Files:** none modified in this task. This is an investigation-and-stop task; it produces an amended plan, not code.

- [x] **Step 1: Confirm which key macOS actually sent**

Run: `grep -n -E 'rsaaeskey|fpaeskey' <scratchpad>/h1-session.log`
If `rsaaeskey` was sent and recovery still failed → the RSA key material in `RaopRsa` is wrong; compare it byte-for-byte with UxPlay `lib/raop_rtp.c` / Shairport Sync `rtsp.c` (both embed the same AirPort Express private key). If `fpaeskey` was sent despite `et=0,1` → macOS ignored the advert; continue.

- [x] **Step 2: Read the reference implementation**

Files to read in UxPlay (https://github.com/FDH2/UxPlay): `lib/fairplay_playfair.c` (`fairplay_decrypt`), `lib/raop_handlers.h` (`raop_handler_announce`, how `fpaeskey` is passed to `fairplay_decrypt` together with the fp-setup state). Compare with PhairPlay `handshake/FairPlay.kt` `decrypt(fpKey)` and `RtspHandler.handleRecordInternal` lines 780–792. Note every difference (input ordering, which fp-setup message bytes are used, key length).

- [x] **Step 3: STOP and report**

Write the differences found into the Diagnosis result section, then return to Jeremy with a proposal for concrete H2 tasks (each with a unit test against the RPiPlay FairPlay vectors in `https://github.com/FD-/RPiPlay/tree/master/lib/playfair`). Do not implement H2 without an amended plan.

### Task 9: GATE — H3 (AirPlay 2 buffered audio) — only if H1 and H2 both fail

- [ ] **Step 1: STOP.** Per the spec, H3 is a separate spec. Report to Jeremy with the evidence from Tasks 3, 7 and 8. Do not start any buffered-audio code in this plan.

### Task 10: Documentation corrections

**Files:**
- Modify: `README.md:54-55, 183-185`
- Modify: `app/src/main/kotlin/com/phairplay/airplay/handshake/BufferedAudioServer.kt:17-22`
- Modify: `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt:668-671`
- Modify: `app/src/main/kotlin/com/phairplay/airplay/AudioPlayer.kt:298-299`
- Modify: `CHANGELOG.md` (`[Unreleased]` section)

Do this task after Task 7 succeeds (wording depends on what works).

- [x] **Step 1: README "What PhairPlay Does NOT Do" (lines 54–55)**

Replace:
```
- **Apple Music in-app audio** — protected on every AirPlay path; use system audio output instead
- **Buffered audio playback** (AirPlay 2 type 103) — accepted but not played back yet
```
with:
```
- **AirPlay 2 buffered audio** (type 103, used by iOS 17+ Music) — accepted but not played back yet; macOS Music.app uses the classic path, which works
```

- [x] **Step 2: README "Known Limitations" (lines 183–185)**

Replace the bullet starting `- **Apple Music in-app audio is not decryptable.**` with:
```
- **Music.app on macOS plays via the classic (AirPlay 1) audio path** using RSA-wrapped keys. iOS 17+ Music requires AirPlay 2 buffered audio, which is not yet implemented.
```
Keep the `Buffered audio (AirPlay 2 type 103)` bullet.

- [x] **Step 3: `BufferedAudioServer.kt` lines 17–22**

Replace the `STATUS:` paragraph with:
```
 * STATUS: accept-only. It accepts the TCP connection (so a sender that chooses this stream doesn't
 * abort) and logs the framing of the first packets. Playback isn't implemented: the buffered stream
 * needs the HAP-encrypted control channel, PTP timing, and ChaCha20-Poly1305 packet decryption
 * (see Shairport Sync). macOS Music.app uses the classic RAOP path instead, which is implemented.
```

- [x] **Step 4: `RtspHandler.kt` lines 668–671**

Replace the comment inside the `103 ->` branch with:
```
                        // Buffered (audio-only) AirPlay 2 — accepted + instrumented; playback not
                        // implemented (needs encrypted control channel + PTP + ChaCha20 decrypt).
```

- [x] **Step 5: `AudioPlayer.kt` lines 298–299**

Change the mute message to:
```kotlin
            Logger.w("Audio muted: ALAC decoded only $decodeSuccesses/$decodeAttempts frames — " +
                     "stream key looks wrong")
```

- [x] **Step 6: `CHANGELOG.md`**

Under `## [Unreleased]` add:
```
### Fixed
- Music.app / iTunes audio-only AirPlay from macOS no longer drops: the `_raop._tcp` record now
  advertises RSA key wrapping (`et=0,1`) so the sender uses `rsaaeskey`, which the receiver decrypts.
- `ANNOUNCE` with an unrecoverable audio key is rejected with RTSP 500 instead of playing silence.

### Changed
- `_raop._tcp` TXT attributes moved to `RaopTxtRecord` (pure Kotlin, unit-tested).
```

- [x] **Step 7: Verify no stale claim remains**

Run: `grep -rn -i 'undecryptable\|not decryptable\|no FOSS receiver\|protected on every' README.md CHANGELOG.md app/src/main/kotlin`
Expected: no output.

- [x] **Step 8: Hand-off**

Suggested commit: `docs: correct Apple Music audio claims; describe classic RAOP path and buffered-audio gap`.

### Task 11: Full CI-equivalent check and final hand-off

- [x] **Step 1: JVM tests**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :test-runner:test --console=plain`
Expected: `BUILD SUCCESSFUL`, 234 tests, 0 failures.

- [x] **Step 2: Lint + both debug APKs**

Run: `ANDROID_HOME=/Users/jeremyprowse/Library/Android/sdk ./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug :app:assembleGoogletvDebug :app:assembleFiretvDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Working-tree summary for Jeremy**

Run: `git status --short` and `git diff --stat`. Report the file list, the test count, the on-device result from Task 7, and this suggested squash message if he prefers one commit:

```
fix(airplay): make Music.app audio-only AirPlay work via the RSA key path

- _raop._tcp advertises et=0,1 (RSA) instead of unfinished FairPlay types; extracted to
  RaopTxtRecord (pure Kotlin, 5 unit tests)
- ANNOUNCE returns 500 when rsaaeskey cannot be recovered (no more silent streams)
- docs: remove the "Apple Music is undecryptable" claims
Verified on Pi 4 LineageOS Android TV: 5-minute Music.app soak, volume, now-playing, DACP,
mirroring regression OK.
```

---

## Diagnosis result (filled in by Task 3 / Task 7)

**Captured 2026-09-06 on the Pi 4 (debug build from this checkout), Music.app on macOS at 192.168.1.197. Two independent faults.**

**Fault 1 — the drop — class A2 (control-plane), receiver-side.** The whole classic handshake succeeds: `POST /fp-setup` ×2 (v2, mode 0 then 26), `ANNOUNCE` (ALAC, `fpaeskey`), `SETUP` (UDP 6001/6002), `RECORD` 200 with the FairPlay-unwrapped key. Three `SET_PARAMETER`s follow: volume, volume, now-playing DMAP. The fourth `SET_PARAMETER` carries album artwork and is **432 829 bytes**. `RtspRequestReader.readBody` applies the 64 KB `maxMessageBytes` limit to it, logs `Request body too large (432829 bytes) — rejecting`, returns null, and `RtspHandler` treats null as EOF → `Client disconnected` → `Streaming stopped`. The receiver closes the control connection 190 ms after RECORD; macOS then deselects the device. Proving lines:

```
04:08:34.768 I RECORD — streaming starting (audioOnly=true, encrypted=true)
04:08:34.919 D RTSP SET_PARAMETER rtsp://192.168.1.197/3871555714
04:08:34.952 W Request body too large (432829 bytes) — rejecting
04:08:34.952 I Client disconnected
04:08:34.953 I Streaming stopped — releasing media components
```

**Fault 2 — no audio — class A3 (key / decrypt).** In the 150 ms the stream lived, every ALAC frame failed: `E/AlacJni: ALACDecoder.Decode failed: -50` (kALAC_ParamError) ×19, zero successes. The AES-CBC key recovered from `fpaeskey` via the FairPlay v2 unwrap does not decrypt the stream. Music.app chose FairPlay because the TXT record offers `et=0,3,5` and no RSA. Fixing Fault 1 alone yields a stable but silent session (the decode-health guard would mute it).

**Phase B entry point:** new **Task 3b** (accept large artwork bodies) fixes Fault 1, then **Task 4/5 (H1)** address Fault 2 by routing Music.app to `rsaaeskey`. Task 6 stays. Task 7 verifies both.

Raw capture: `device-test-logs/20260906-music-drop/` (git-ignored) and the session log in the scratchpad.

**Task 7 result, first attempt (H1 as originally specified) — 2026-09-06.** Task 3b fixed the drop: the session stays up and the now-playing screen shows artwork. Audio stayed silent. Music.app **ignored `et=0,1`** and still sent `fpaeskey` via fp-setup **v2**; ALAC decode failed on all but 4/24 frames. After ~2 minutes the process crashed: `SIGSEGV in ALACDecoder::Decode` (libalac fed garbage continuously while muted).

**Comparison capture (system audio vs Music.app):**

| | macOS system-audio output (plays) | Music.app (silent) |
|---|---|---|
| Transport | IPv6 link-local | IPv4 |
| fp-setup | **v3**, mode 1 | **v2**, mode 0/2/3 |
| Session setup | AirPlay 2 plist `SETUP` (stream type 96, `TEARDOWN streams=[96]`) | Classic `ANNOUNCE` (SDP, `fpaeskey`) → `SETUP` → `RECORD` |
| Audio path | `AudioStreamServer` + `AlacDecoder` | legacy `AudioPlayer` + `AlacDecoder` |
| Decode | healthy | `-50` on nearly every frame |

Both paths use identical AES-CBC and ALAC code, so the difference is the key the v2 FairPlay handshake yields. Rather than fix v2 (H2, uncertain), **make Music.app take the v3 path**: our `_raop._tcp` record only had AirPlay 1 keys; UxPlay's (which plays Music.app) also carries `ft` (features), `pk`, `sf`, `vv`, `txtvers`, `ch`, `sr`, `ss`. macOS uses those to treat the receiver as AirPlay 2 for audio.

**Amended Phase B (replaces H1):**
- **Task 4/5 (revised):** `RaopTxtRecord.build(macHex, displayName, features, publicKeyHex)` publishes the AirPlay 2 record above with `et=0,3,5` (reference value; RSA is still handled if a legacy sender offers it). `MdnsService` passes `AIRPLAY_FEATURES` and the Ed25519 `pk`, and also adds `pk` to `_airplay._tcp`.
- **Task 3c (new):** `AudioPlayer.playAudioPacket` returns before decoding once `muted` is set — fixes the libalac SIGSEGV.
- **Task 7 (re-run):** expect Music.app to log `fp-setup … v=0x03`, no `ANNOUNCE`, `Audio decoder: ALAC … (ct=2)` from `AudioStreamServer`, and audible playback.

**Task 7 result, second attempt (AirPlay 2 style `_raop._tcp` record) — 2026-09-06.** Music.app **still** used fp-setup v2 + `ANNOUNCE` (no pair-verify), silent, 3/24 frames decoded. Conclusion: macOS Music only takes the AirPlay 2 flow for receivers advertising buffered audio (features bit 40 etc.); otherwise it uses AirPlay 1 with FairPlay v2 regardless of the RAOP record. The record change is kept (harmless, matches UxPlay) but is not the fix. The crash guard (Task 3c) worked: 21 decode failures, no SIGSEGV.

**H2 finding — the actual bug.** Compared `FairPlay.kt` against the reference that implemented FairPlay v2 for Music.app (`joerg-krause/shairplay` branch `fairplay_v2`, `src/lib/fairplay_playfair.c`):
- v2 phase-1 reply: identical (`fply_2` bytes match `REPLY_V2` byte-for-byte; mode patched at offset 13).
- `playfair_decrypt` input: identical (164-byte key message, 72-byte `fpaeskey`, no key derivation).
- **Phase-2 reply header: DIFFERENT.** The reference uses `fp_header = FPLY 03 01 04 00 00 00 00 14` for **both** v2 and v3. Our `handshake()` patches byte 4 to the request's version (`0x02`) on the previous developer's assumption. The sender wraps the audio key from this reply, so the patched header yields a wrong key for every Music.app session.

**Task 8 (H2) becomes concrete:** stop patching byte 4 in `FairPlay.handshake`; update `FairPlayTest` to pin the fixed header; re-run Task 7.

**Task 7 result, third attempt (fixed phase-2 header) — 2026-09-06.** Still silent: 6/24 frames decoded. The successes are the short (< 16 B, therefore unencrypted) near-silence frames Music.app sends at session start; every encrypted frame fails. The Mac's resolver was confirmed (`dns-sd -L`) to hold the current TXT records, so mDNS caching did not invalidate the tests. Conclusion: the FairPlay **v2** key exchange has never been reverse-engineered (the `playfair` code implements only v3; cf. openairplay/airplay2-receiver issue #12), and macOS Music ignores the RAOP `et` list while `_airplay._tcp` advertises FairPlay. H2 is infeasible with available code.

**Decision (Jeremy, 2026-09-06): stop here.** Keep the landed fixes (Tasks 3b, 3c, 4/5 revised, 6, FairPlay header), document the Music.app limitation, and move on to sub-project 2 (Miracast removal) and 3 (DLNA). Options recorded for later: (a) a second audio-only AirPlay 1 speaker record with a different device ID (Shairport Sync classic model, RSA path, extra picker entry); (b) H3 — AirPlay 2 buffered audio, the proper fix, separate spec.

**Final state:** JVM suite 234/234; lint + both debug APKs green (Task 11); on-device: Music.app session stable with metadata/artwork, silent; system-audio output and screen mirroring verified by Jeremy on the final build.

### Task 12 (added 2026-09-06): harden realtime audio pacing (`AudioStreamServer`)

**Finding:** macOS system-audio output sounded "smooth" at 04:56 and "choppy" at 05:31 with byte-identical negotiation (same SETUP plist, type-96 dict, AudioTrack sizing) and the same 7 HAL underruns/min in both; ping Mac→Pi: 0 % loss, jitter up to 101 ms. Not a regression — a pre-existing pacing weakness: the AudioTrack is created with only `minBuf` (170 ms; the log line claiming `minBuf*2` is wrong), `play()` is called with an empty buffer, and because the sender is realtime the receiver never gets ahead, so any scheduling hiccup or Wi-Fi jitter burst drains the buffer.

**Change:**
- `prerollFrameCount(sampleRate, framesPerPacket)` — pure, unit-tested: packets needed to cover 200 ms.
- `prerollFramesFor(codecType, …)` — 0 for AAC-ELD (mirroring keeps immediate playback so lip-sync is unchanged), else the pre-roll count. Playback waits for that many queued frames (max 600 ms) before `play()`.
- AudioTrack buffer = `minBuf * 2` (matches the existing log line; capacity does not add latency by itself).
- Playback thread runs at `THREAD_PRIORITY_URGENT_AUDIO`, restored in `finally`.

**Verify:** JVM tests; 60 s system-audio listening test on the Pi (expect far fewer `Underrun detected` lines and no audible gaps); mirroring still lip-synced.

**Task 12 result — 2026-09-06.** 60 s + 80 s system-audio listening tests: "smooth" (Jeremy); `Underrun detected` fell from 7/min to 2 in ~4 min total; `Audio pre-roll: 26/26 frames queued before play()` logged for ALAC (ct=2); no pre-roll line for mirroring (ct=8), whose slight A/V lag Jeremy judged pre-existing/acceptable. JVM suite 237/237.

**Sub-project 1 closed.** Delivered: no session drop (3b), no native crash (3c), FairPlay header per reference, AirPlay 2 style RAOP record + `pk` (4/5), ANNOUNCE 500 on unrecoverable RSA key (6), smooth realtime audio (12), docs corrected (10), CI-equivalent green (11). Not delivered: Music.app audio-only (needs H3 — AirPlay 2 buffered audio — or the second speaker record). Next: sub-project 2 (Miracast removal).

