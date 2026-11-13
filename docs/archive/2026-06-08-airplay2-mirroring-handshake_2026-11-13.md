# AirPlay 2 Mirroring Handshake Implementation Plan

> **ARCHIVED — superseded on 2026-11-13.**
> This document is kept for historical reference only. It does **not** describe
> the current build. The current version is [the technical specification](../spec/TECHNICAL_SPEC.md) and [the architecture](../ARCHITECTURE.md).
> Do not follow this document for new work.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PhairPlay actually receive a macOS screen-mirroring session by implementing the AirPlay 2 handshake it currently lacks: `GET /info` → `pair-setup`/`pair-verify` → `fp-setup` (FairPlay) → encrypted `SETUP` → the encrypted H.264 mirroring stream, decoded to the existing `VideoDecoder`.

**Architecture:** PhairPlay already has the *back half* of a receiver (RTSP request reader, H.264 `VideoDecoder` → `Surface`, mDNS advertising). It is missing the *front half* — the HTTP-over-RTSP handshake macOS requires. We add that as a set of focused handler modules under `com.phairplay.airplay.handshake`, route `GET`/`POST` by URI path in `RtspHandler`, add a binary-safe response writer, layer ChaCha20-Poly1305 encryption onto the control socket after `pair-verify`, port the reverse-engineered FairPlay key exchange, and stream the decrypted mirroring video into the existing decoder.

**Tech Stack:** Kotlin, Android `MediaCodec` (already used), BouncyCastle `bcprov-jdk18on:1.78.1` (already a dependency — X25519, Ed25519, ChaCha20-Poly1305, AES, HKDF, SHA-512), and a new binary-plist dependency `dd-plist`. Build/test loop: `./gradlew :app:assembleFiretvDebug` → `adb install -r` → `adb logcat` against the real Mac at `192.168.100.6`.

---

## Methodology note (read before executing)

This is **protocol reverse-engineering**, not greenfield feature work, so the TDD model is adapted:

- **Deterministic codecs/crypto get real unit tests** (TLV8, binary plist round-trips, FairPlay against the known vectors shipped in RPiPlay, pairing crypto against published vectors). Write these as proper RED→GREEN tests.
- **The protocol flow is validated on-device** against macOS — the only authoritative "test" of the handshake is whether macOS proceeds to the next step. Each phase ends with an explicit **On-device checkpoint** describing exactly what `adb logcat` must show.
- **Reference implementations are the spec.** Port from these (all GPL/Apache-compatible for personal use; cite them in commit messages):
  - openairplay/airplay-spec: https://openairplay.github.io/airplay-spec/
  - UxPlay: https://github.com/FDH2/UxPlay — `lib/pairing.c`, `lib/fairplay_playfair.c`, `lib/raop.c`, `lib/raop_rtp_mirror.c`, `lib/raop_handlers.h`
  - RPiPlay: https://github.com/FD-/RPiPlay — same `lib/` files, includes FairPlay test data

**The executor must keep the Mac + TV on the same network and reuse the existing ADB connection (`adb connect 192.168.100.6:5555`).** Build env vars: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.

---

## File Structure

**New package `app/src/main/kotlin/com/phairplay/airplay/handshake/`:**
- `PlistCodec.kt` — encode/decode Apple binary plists ↔ `Map<String, Any?>` (wraps dd-plist).
- `Tlv8.kt` — HomeKit-style TLV8 encode/decode used by pair-setup/verify.
- `InfoResponder.kt` — builds the `GET /info` binary-plist body.
- `PairingKeys.kt` — long-lived Ed25519 identity keypair (persisted in SharedPreferences).
- `PairSetupHandler.kt` — `POST /pair-setup` (transient, no-PIN) state machine.
- `PairVerifyHandler.kt` — `POST /pair-verify` X25519 ECDH → session keys.
- `ControlCipher.kt` — ChaCha20-Poly1305 framing applied to the control socket after pair-verify.
- `FairPlay.kt` — port of RPiPlay/UxPlay `fairplay_playfair.c`: answers `fp-setup` and decrypts the stream AES key.
- `MirrorSetupHandler.kt` — parses `SETUP` plist, FairPlay-decrypts the AES key, allocates the data port, builds the SETUP response plist.
- `MirrorStreamServer.kt` — accepts the mirroring data TCP connection, parses the 128-byte packet headers, AES-decrypts H.264, feeds `VideoDecoder`.

**Modified files:**
- `app/src/main/kotlin/com/phairplay/airplay/RtspMessages.kt` — add binary body + content-type to `RtspResponse`.
- `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt` — binary `sendResponse`, `GET`/`POST` path routing, encryption hooks, mirror-setup wiring.
- `app/src/main/kotlin/com/phairplay/airplay/AirPlayReceiver.kt` — wire mirror stream → `VideoDecoder`; provide the surface for mirroring (not only RECORD).
- `app/src/main/kotlin/com/phairplay/airplay/MdnsService.kt` — add the `pk` (Ed25519 public key) and confirm `features`/`flags` advertise mirroring+pairing.
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — add dd-plist.

---

## Phase 0 — Plumbing: dependencies, binary responses, GET/POST routing

### Task 0.1: Add the dd-plist dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts:160-190` (dependencies block)

- [ ] **Step 1:** In `gradle/libs.versions.toml` under `[versions]` add `ddplist = "1.28"`; under `[libraries]` add:
  ```toml
  ddplist = { group = "com.googlecode.plist", name = "dd-plist", version.ref = "ddplist" }
  ```
- [ ] **Step 2:** In `app/build.gradle.kts` dependencies block add `implementation(libs.ddplist)`.
- [ ] **Step 3:** Run `./gradlew :app:assembleFiretvDebug` — Expected: BUILD SUCCESSFUL (dependency resolves).
- [ ] **Step 4:** Commit: `chore: add dd-plist for AirPlay binary plist support`.

### Task 0.2: Give `RtspResponse` a binary body

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/airplay/RtspMessages.kt`

- [ ] **Step 1:** Add fields to `RtspResponse` (keep existing `body: String` for the legacy SDP/text paths):
  ```kotlin
  data class RtspResponse(
      val statusCode: Int,
      val statusMessage: String,
      val headers: Map<String, String> = emptyMap(),
      val body: String = "",
      val bodyBytes: ByteArray? = null,        // when non-null, this is the wire body
      val contentType: String? = null,         // e.g. "application/x-apple-binary-plist"
      val protocol: String = "RTSP/1.0"
  )
  ```
- [ ] **Step 2:** Add a helper in the same file:
  ```kotlin
  /** Effective wire body: prefers binary bodyBytes, else UTF-8 of the text body. */
  fun RtspResponse.wireBody(): ByteArray = bodyBytes ?: body.toByteArray(Charsets.UTF_8)
  ```
- [ ] **Step 3:** Run `./gradlew :app:compileFiretvDebugKotlin` — Expected: SUCCESS.
- [ ] **Step 4:** Commit: `feat(rtsp): allow binary response bodies`.

### Task 0.3: Make `sendResponse` binary-safe

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt` (the `sendResponse` function, ~line 320)

- [ ] **Step 1:** Replace `sendResponse` with a version that writes ASCII headers then raw bytes, using byte length and an optional Content-Type:
  ```kotlin
  private fun sendResponse(outputStream: OutputStream, response: RtspResponse) {
      val wire = response.wireBody()
      val head = StringBuilder()
      head.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
      if (response.protocol.startsWith("RTSP")) head.append("CSeq: $currentCSeq\r\n")
      head.append("Server: AirTunes/220.68\r\n")          // match what macOS expects from AirPlay
      response.contentType?.let { head.append("Content-Type: $it\r\n") }
      response.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
      if (wire.isNotEmpty()) head.append("Content-Length: ${wire.size}\r\n")
      head.append("\r\n")
      val out = if (encryptedChannel != null) encryptedChannel!!.wrap(outputStream) else outputStream
      out.write(head.toString().toByteArray(Charsets.US_ASCII))
      if (wire.isNotEmpty()) out.write(wire)
      out.flush()
  }
  ```
  (`encryptedChannel` is added in Phase 2; declare it now as `@Volatile private var encryptedChannel: ControlCipher? = null` and a no-op `ControlCipher` placeholder, or guard with a TODO that Phase 2 fills. To avoid a forward reference, in Phase 0 use plain `outputStream` and add the `encryptedChannel` branch in Task 2.4.)
- [ ] **Step 2:** Build + install + relaunch, then re-run an AirPlay attempt from the Mac and capture logs:
  Run: `./gradlew :app:assembleFiretvDebug && adb -s 192.168.100.6:5555 install -r app/build/outputs/apk/firetv/debug/app-firetv-debug.apk`
- [ ] **Step 3:** Commit: `feat(rtsp): binary-safe response writer with Content-Type`.

### Task 0.4: Route `GET`/`POST` by URI path

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt` (the `routeRequest` `when`, ~line 148)

- [ ] **Step 1:** Add `GET` and `POST` arms that dispatch on `request.uri.substringBefore("?")`:
  ```kotlin
  "GET"  -> when (request.uri.substringBefore("?")) {
      "/info" -> handleInfo(request)
      else    -> handleUnknownInternal(request)
  }
  "POST" -> when (request.uri.substringBefore("?")) {
      "/pair-setup"  -> pairSetupHandler.handle(request)
      "/pair-verify" -> pairVerifyHandler.handle(request)
      "/fp-setup"    -> fairPlay.handleSetup(request)
      "/feedback"    -> RtspResponse(200, "OK", protocol = request.responseProtocol())
      "/audioMode"   -> RtspResponse(200, "OK", protocol = request.responseProtocol())
      else           -> handleUnknownInternal(request)
  }
  ```
- [ ] **Step 2:** For now stub `handleInfo`, `pairSetupHandler`, `pairVerifyHandler`, `fairPlay` to return `RtspResponse(200, "OK", protocol = request.responseProtocol())` and `Logger.i` the path. (Real impls land in later phases. Use lazily-constructed properties so later phases only swap the body.)
- [ ] **Step 3:** Build, install, relaunch with cleared logs (`adb logcat -c`), attempt AirPlay from the Mac.
- [ ] **On-device checkpoint:** `adb logcat -d | grep -i 'RTSP \(GET\|POST\)'` shows `GET /info`, then `POST /pair-setup` — and **no more "Unknown RTSP method"**. macOS will still fail to connect (bodies are stubs), but it must now advance past `/info` to `/pair-setup`. This proves routing works.
- [ ] **Step 4:** Commit: `feat(airplay): route GET/POST handshake paths`.

---

## Phase 1 — `GET /info`

### Task 1.1: PlistCodec round-trip (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/airplay/handshake/PlistCodec.kt`
- Test: `app/src/test/kotlin/com/phairplay/airplay/handshake/PlistCodecTest.kt`

- [ ] **Step 1 (RED):** Write a test that a `Map<String,Any?>` encoded to a binary plist and decoded back is equal, and that the encoded bytes start with the bplist magic `"bplist00"`.
  ```kotlin
  @Test fun roundTripsMap() {
      val m = mapOf("name" to "PhairPlay", "n" to 7L, "b" to true)
      val bytes = PlistCodec.encode(m)
      assertEquals("bplist00", String(bytes.copyOf(8), Charsets.US_ASCII))
      assertEquals(m, PlistCodec.decode(bytes))
  }
  ```
- [ ] **Step 2:** Run `./gradlew :test-runner:test --tests '*PlistCodecTest*'` (or `:app:testFiretvDebugUnitTest`) — Expected: FAIL (PlistCodec missing).
- [ ] **Step 3 (GREEN):** Implement `PlistCodec.encode(Map)` / `decode(ByteArray)` using dd-plist (`NSDictionary`/`BinaryPropertyListWriter`/`PropertyListParser.parse`). Map Kotlin `String/Long/Boolean/ByteArray/Map/List` ↔ `NSObject`.
- [ ] **Step 4:** Run the test — Expected: PASS.
- [ ] **Step 5:** Commit: `feat(airplay): binary plist codec`.

### Task 1.2: `/info` response body

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/airplay/handshake/InfoResponder.kt`
- Modify: `RtspHandler.handleInfo`

- [ ] **Step 1:** Implement `InfoResponder.build(): ByteArray` returning a bplist with the capability keys macOS reads. Port the exact key set + `features`/`statusFlags` values from UxPlay `lib/raop_handlers.h` (`raop_handler_info`). Minimum keys: `deviceID`, `features` (Long, must match the mDNS `features`), `statusFlags`, `model` ("AppleTV5,3"), `name`, `sourceVersion` ("220.68"), `pi`, `pk` (32-byte Ed25519 public key, see Task 2.1), `vv` (2), and the `audioFormats`/`displays` arrays (copy from UxPlay). `displays` must advertise the TV resolution (1920×1080).
- [ ] **Step 2:** `handleInfo` returns `RtspResponse(200, "OK", bodyBytes = InfoResponder.build(), contentType = "application/x-apple-binary-plist", protocol = request.responseProtocol())`.
- [ ] **Step 3:** Build/install/relaunch, clear logs, attempt from Mac.
- [ ] **On-device checkpoint:** macOS still sends `POST /pair-setup` (and may now retry with a *larger* pair-setup body, indicating it accepted `/info`). Confirm no plist parse errors logged on the Mac side isn't observable, so rely on: PhairPlay logs `GET /info` then `POST /pair-setup` repeatedly without macOS giving up immediately.
- [ ] **Step 4:** Commit: `feat(airplay): implement GET /info capability response`.

---

## Phase 2 — Pairing (`pair-setup` transient + `pair-verify`)

> Reference: UxPlay `lib/pairing.c` and openairplay-spec "Pair Setup"/"Pair Verify". macOS mirroring uses **transient** pair-setup (method 0x00, no PIN) then pair-verify (X25519). After pair-verify, the control channel is ChaCha20-Poly1305 encrypted.

### Task 2.1: Persistent Ed25519 identity

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/airplay/handshake/PairingKeys.kt`
- Test: `.../handshake/PairingKeysTest.kt`

- [ ] **Step 1 (RED):** Test that `PairingKeys.get(context).ed25519Public` is 32 bytes and is stable across two calls.
- [ ] **Step 2:** Run test — FAIL.
- [ ] **Step 3 (GREEN):** Generate an Ed25519 keypair with BouncyCastle (`Ed25519PrivateKeyParameters`), persist the 32-byte seed in SharedPreferences (`phairplay_prefs`, key `pairing_ed25519_seed`), expose `ed25519Public: ByteArray`, `sign(data): ByteArray`.
- [ ] **Step 4:** Run test — PASS. Commit: `feat(airplay): persistent Ed25519 pairing identity`.
- [ ] **Step 5:** Wire `pk` into `MdnsService` TXT (`setAttribute("pk", hex(ed25519Public))`) and into `InfoResponder`.

### Task 2.2: TLV8 codec (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/airplay/handshake/Tlv8.kt`
- Test: `.../handshake/Tlv8Test.kt`

- [ ] **Step 1 (RED):** Test round-trip including a value >255 bytes (must split into multiple fragments of the same type and reassemble). Vectors: type `0x06`=state, `0x03`=publicKey.
- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3 (GREEN):** Implement `encode(Map<Int, ByteArray>)` and `decode(ByteArray): Map<Int, ByteArray>` per the HomeKit TLV8 fragmentation rule.
- [ ] **Step 4:** Run — PASS. Commit: `feat(airplay): TLV8 codec`.

### Task 2.3: pair-setup (transient) + pair-verify

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/airplay/handshake/PairSetupHandler.kt`, `PairVerifyHandler.kt`
- Test: `.../handshake/PairVerifyHandlerTest.kt`

- [ ] **Step 1:** Implement `PairSetupHandler.handle(request)`:
  - For the transient flow macOS uses for mirroring, respond to state M1 with M2 carrying the receiver's curve public key + salt as required by `pairing.c`'s transient branch. (Port the exact TLV item set from UxPlay `pairing_session_handle_setup`.)
  - Content-Type of responses: `application/octet-stream`.
- [ ] **Step 2:** Implement `PairVerifyHandler.handle(request)` (two messages):
  - M1: parse client X25519 public key (TLV `0x03`); generate our X25519 ephemeral keypair; compute shared secret; HKDF-SHA512 (salt `"Pair-Verify-Encrypt-Salt"`, info `"Pair-Verify-Encrypt-Info"`) → session key; sign (our X25519 pub ‖ client X25519 pub) with Ed25519; return our X25519 pub + a ChaCha20-Poly1305-encrypted sub-TLV (nonce `"PV-Msg02"`).
  - M2: verify client proof; on success derive the two directional stream keys via HKDF (salt `"Control-Salt"`, info `"Control-Read-Encryption-Key"` / `"Control-Write-Encryption-Key"`). Store them for `ControlCipher`.
  - Port nonces/salts/labels verbatim from `pairing.c`.
- [ ] **Step 3 (test):** Unit-test pair-verify M1 against a captured macOS client public key if available; otherwise test the HKDF/sign helpers against vectors from `pairing.c` comments. At minimum assert the handler returns 200 with a non-empty TLV body for a well-formed M1.
- [ ] **Step 4:** Build/install/relaunch, clear logs, attempt from Mac.
- [ ] **On-device checkpoint:** logs show `POST /pair-setup` then `POST /pair-verify` (M1 then M2), then **`POST /fp-setup`** arriving — proving macOS accepted pairing and moved on.
- [ ] **Step 5:** Commit: `feat(airplay): pair-setup (transient) and pair-verify`.

### Task 2.4: Control-channel encryption

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/airplay/handshake/ControlCipher.kt`
- Modify: `RtspHandler` (`encryptedChannel`, the read path in `requestReader`, and `sendResponse`)

- [ ] **Step 1:** Implement `ControlCipher` holding the two HKDF keys + per-direction counters; AirPlay frames encrypted control data as `[2-byte LE length][ciphertext][16-byte Poly1305 tag]` with a 12-byte nonce = 8-byte LE counter (zero-padded). Provide `wrapInput(InputStream): InputStream` and `wrapOutput(OutputStream): OutputStream` that transparently decrypt/encrypt these frames.
- [ ] **Step 2:** In `RtspHandler.handleClient`, after `PairVerifyHandler` signals success, set `encryptedChannel` and re-wrap the streams so subsequent `requestReader.read()` and `sendResponse` operate on plaintext.
- [ ] **Step 3:** Build/install/relaunch; attempt from Mac.
- [ ] **On-device checkpoint:** the `SETUP` request after fp-setup parses as readable plist (Task 4) rather than garbage — confirming decryption works.
- [ ] **Step 4:** Commit: `feat(airplay): ChaCha20-Poly1305 control channel encryption`.

---

## Phase 3 — FairPlay (`fp-setup`)

> Reference: RPiPlay `lib/fairplay_playfair.c` (+ its companion key tables) and UxPlay's copy. This is a direct port of reverse-engineered constants and AES operations. Keep the C structure; translate to Kotlin byte arrays + `javax.crypto`/BouncyCastle AES.

### Task 3.1: Port FairPlay (TDD against shipped vectors)

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/airplay/handshake/FairPlay.kt`
- Test: `.../handshake/FairPlayTest.kt`

- [ ] **Step 1 (RED):** Using the known `fp-setup` request/response pairs from RPiPlay's source (the 16-byte mode-1 replies and the 142-byte mode-3 setup), assert `FairPlay.handleSetup(stage1Bytes)` returns the exact expected 142-byte (or 32-byte) response bytes.
- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3 (GREEN):** Port the four reply messages and the `fairplay_decrypt` routine. `FairPlay` exposes:
  - `handleSetup(request): RtspResponse` (returns `application/octet-stream` with the correct stage reply),
  - `decryptKey(ekey: ByteArray): ByteArray` (used by SETUP to recover the 16-byte AES stream key).
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** On-device: confirm `POST /fp-setup` returns 200 and macOS proceeds to `SETUP`. Commit: `feat(airplay): port FairPlay fp-setup handshake`.

---

## Phase 4 — `SETUP` and data-port allocation

> Reference: UxPlay `lib/raop.c` `raop_handler_setup` (the two-message SETUP: first the timing/event ports, then the stream request carrying `ekey`/`eiv`/`streamConnectionID`).

### Task 4.1: Parse SETUP and build the response

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorSetupHandler.kt`
- Modify: `RtspHandler` routing (`"SETUP"` arm must call this when the body is a plist, not SDP)

- [ ] **Step 1:** Detect plist SETUP (Content-Type `application/x-apple-binary-plist`) vs legacy SDP SETUP and branch. For plist SETUP:
  - **Message A** (has `timingPort`/`eventPort`, no `streams`): create the event-channel server socket, reply with a plist `{ eventPort, timingPort, (timingPeerInfo) }`.
  - **Message B** (has `streams` array): for the stream with `type == 110` (mirror video): read `ekey`, `eiv`; `aesKey = fairPlay.decryptKey(ekey)`; create `MirrorStreamServer` listening on a fresh TCP port; reply with plist `{ streams: [ { type:110, dataPort:<port>, streamConnectionID } ] }`.
- [ ] **Step 2:** Store `aesKey`, `eiv`, and `streamConnectionID` for `MirrorStreamServer` (the AES-CTR keystream derivation uses these — see Task 5.1).
- [ ] **Step 3:** On-device: after SETUP reply, macOS **opens a TCP connection to `dataPort`** (verify with `adb shell ss -tnp | grep <dataPort>` and a log line in `MirrorStreamServer.accept`).
- [ ] **Step 4:** Commit: `feat(airplay): SETUP parsing, FairPlay key decrypt, data port`.

---

## Phase 5 — Mirroring stream → decoder

> Reference: UxPlay `lib/raop_rtp_mirror.c` (`raop_rtp_mirror_thread`) — the 128-byte packet header, payload types, and the AES-CTR decryption with the SHA-512-derived key/IV from `streamConnectionID`.

### Task 5.1: Stream key derivation + packet parser (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorStreamServer.kt`
- Test: `.../handshake/MirrorPacketTest.kt`

- [ ] **Step 1 (RED):** Test the header parser against a captured/known 128-byte mirror header: extract `payloadSize` (LE int at offset 0), `payloadType` (offset 4, low 8 bits: 0=video, 1=codec/SPS-PPS), and `timestamp`. Test AES-CTR key derivation: `aesKeyMirror = SHA512(aesKey ‖ "AirPlayStreamKey" ‖ streamConnectionID)[0..16]`, `aesIV = SHA512(aesKey ‖ "AirPlayStreamIV" ‖ streamConnectionID)[0..16]` (confirm exact label strings against `raop_rtp_mirror.c`).
- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3 (GREEN):** Implement parsing + `AES/CTR/NoPadding` decryption of the payload.
- [ ] **Step 4:** Run — PASS. Commit: `feat(airplay): mirror packet parse + AES-CTR decrypt`.

### Task 5.2: Feed the decoder

**Files:**
- Modify: `MirrorStreamServer.kt`, `AirPlayReceiver.kt`

- [ ] **Step 1:** On `payloadType == 1`: parse the avcC/SPS+PPS from the (decrypted) codec payload; call `videoDecoder.initialize(sps, pps, width, height)` (width/height from `VideoDecoder.parseSpsResolution(sps)`).
- [ ] **Step 2:** On `payloadType == 0`: split the decrypted payload into NAL units (length-prefixed AVCC → Annex-B or pass length-prefixed per `VideoDecoder` expectation), call `videoDecoder.decodeNalUnit(nal, ptsUs)`.
- [ ] **Step 3:** In `AirPlayReceiver`, provide the mirroring `Surface` (via `videoSurfaceProvider`) when SETUP(stream 110) arrives — not only on legacy RECORD. Create the `VideoDecoder` at that point and pass it to `MirrorStreamServer`.
- [ ] **Step 4:** Build/install/relaunch, attempt from Mac.
- [ ] **On-device checkpoint:** **the Mac desktop appears on the TV.** If black/garbled, capture `adb logcat | grep -iE 'VideoDecoder|MediaCodec|nal|sps'` and iterate (common issues: AVCC vs Annex-B NAL framing, missing SPS/PPS as MediaCodec csd-0/csd-1, wrong CTR counter handling).
- [ ] **Step 5:** Commit: `feat(airplay): decode mirroring stream to surface`.

---

## Phase 6 — Stabilize

### Task 6.1: Teardown, reconnect, latency
- [ ] **Step 1:** Handle `TEARDOWN`/socket close: stop `MirrorStreamServer`, release `VideoDecoder`, re-advertise mDNS (reuse existing `onStreamingStopped`).
- [ ] **Step 2:** Verify reconnect works twice in a row without restarting the app (logcat shows a clean second handshake).
- [ ] **Step 3:** Measure perceived latency; if >150 ms, try decoding with `MediaCodec` low-latency hints (`KEY_LOW_LATENCY` on API ≥ 30 — not available on API 28, so document the floor) and ensure no buffering in `MirrorStreamServer`.
- [ ] **Step 4:** Commit: `feat(airplay): teardown + reconnect stability`.

### Task 6.2 (optional): Mirroring audio
- [ ] Wire the type-96 audio stream (AAC-ELD) into the existing `AudioPlayer`. Skip for v1 if video-only is acceptable.

---

## Self-Review

- **Spec coverage:** `/info` (Phase 1), pair-setup/verify (Phase 2), control encryption (2.4), fp-setup/FairPlay (Phase 3), SETUP+key decrypt+ports (Phase 4), mirror stream decode (Phase 5), teardown (Phase 6) — every handshake step in the failing log (`GET /info`, `POST /pair-setup`) is covered, plus the steps that follow.
- **Type consistency:** `RtspResponse.bodyBytes`/`contentType`/`wireBody()` used consistently from Task 0.2 onward; `FairPlay.decryptKey(ekey)` defined in 3.1 and consumed in 4.1; `MirrorStreamServer` consumes `aesKey`/`eiv`/`streamConnectionID` set in 4.1 and `VideoDecoder.initialize/decodeNalUnit` per the real signatures read from `VideoDecoder.kt`.
- **Known risk / not a placeholder, a genuine unknown:** exact byte layouts for pair-setup transient, FairPlay tables, SETUP plist keys, and the mirror header are taken from the cited reference files — the executor must open those files; the plan gives the structure and the on-device acceptance test for each, which is the correct fidelity for reverse-engineered protocol work.
