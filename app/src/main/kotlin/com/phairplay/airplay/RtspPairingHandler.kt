package com.phairplay.airplay

import com.phairplay.airplay.handshake.PairingKeys
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.Logger

/**
 * RtspPairingHandler — handles the AirPlay 2 pairing handshake and the legacy PIN pairing.
 *
 * WHAT: Answers `POST /pair-setup`, `POST /pair-verify`, `POST /pair-pin-start` and
 * `POST /pair-setup-pin` — the anonymous key exchange plus the optional SRP PIN flow that
 * shows a code on the TV.
 *
 * WHY: Pairing is a security boundary of its own: it decides who may stream, it owns the PIN
 * generation and the failed-attempt lockout. Keeping it apart from the media verbs makes the
 * access-control logic easy to review and to test.
 *
 * Example:
 *   val pairing = RtspPairingHandler(verbs, context, sessionState, pinAuthEnabled = true, ...)
 *   val response = pairing.handlePairPinStart(request)   // 200 OK, PIN shown on the TV
 */
internal class RtspPairingHandler(
    private val verbs: RtspHandler,
    private val context: android.content.Context,
    private val sessionState: RtspSessionState,
    private val pinAuthEnabled: Boolean,
    private val pairingStore: com.phairplay.airplay.handshake.PairingStore?,
    private val onShowPin: (pin: String?) -> Unit
) {

    // ─── Legacy AirPlay SRP PIN pairing (only used when pinAuthEnabled) ───────
    @Volatile private var legacyPin: com.phairplay.airplay.handshake.LegacyPairSetupPin? = null
    // True once a controller has completed SRP PIN pairing. Until then, with PIN auth on, we reject
    // pair-verify — which is what makes macOS fall back to the /pair-pin-start + /pair-setup-pin PIN
    // flow (an accepted pair-verify means "already trusted, no PIN needed").
    @Volatile private var pinPaired = false

    /** The pairing context of this connection (pair-setup / pair-verify). */
    private val pairingSession: com.phairplay.airplay.handshake.PairingSession?
        get() = sessionState.pairingSession

    /**
     * POST /pair-setup. With PIN auth off (default) this is the anonymous Ed25519 exchange. With PIN
     * auth on, it runs the HomeKit-style SRP pair-setup (TLV8) — showing a PIN on the TV that the
     * user types on the Mac — so only someone with screen access can pair.
     */
    internal fun handlePairSetup(request: RtspRequest): RtspResponse {
        // /pair-setup is the anonymous key exchange; PIN access control runs on /pair-setup-pin.
        return try {
            val body = pairingSession!!.pairSetup(request.bodyBytes)
            Logger.i("pair-setup OK (returned ${body.size}-byte public key)")
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            Logger.e("pair-setup failed", e)
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    /**
     * POST /pair-verify — the anonymous ECDH handshake. AirPlay uses this same raw exchange even with
     * PIN access control on (the PIN is a SEPARATE /pair-pin-start + /pair-setup-pin layer), so this
     * is never the HomeKit TLV8 variant.
     */
    internal fun handlePairVerify(request: RtspRequest): RtspResponse {
        // PIN access control: refuse pair-verify until the controller has PIN-paired this connection.
        // macOS responds to the rejection by starting the PIN flow (/pair-pin-start → /pair-setup-pin).
        if (pinAuthEnabled && !pinPaired) {
            Logger.i("pair-verify rejected — PIN pairing required first (triggers /pair-pin-start)")
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        return try {
            val body = pairingSession!!.pairVerify(request.bodyBytes)
            Logger.i("pair-verify ${if (request.bodyBytes.firstOrNull()?.toInt() == 1) "M1" else "M2"} OK (returned ${body.size} bytes)")
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            Logger.e("pair-verify failed", e)
            RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
    }

    /**
     * POST /pair-pin-start — macOS asks the receiver to begin PIN pairing and display the code. We
     * generate + show the PIN and prime the SRP session, then reply 200; the SRP exchange follows on
     * /pair-setup-pin. (This precedes pair-setup in the AirPlay PIN flow — see the logs.)
     */
    internal fun handlePairPinStart(request: RtspRequest): RtspResponse {
        if (!pinAuthEnabled) return verbs.handleUnknownInternal(request)
        if ((pairingStore?.failedAttempts() ?: 0) >= MAX_PAIR_ATTEMPTS) {
            Logger.w("pair-pin-start blocked — PIN auth locked ($MAX_PAIR_ATTEMPTS failed attempts)")
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        newSrpSession()
        Logger.i("pair-pin-start — PIN shown, SRP session primed")
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** Generates a fresh 4-digit PIN, shows it on the TV, and primes the legacy SRP session. */
    private fun newSrpSession() {
        val pin = "%0${PIN_DIGITS}d".format(java.security.SecureRandom().nextInt(PIN_SPACE))
        onShowPin(pin)
        legacyPin = com.phairplay.airplay.handshake.LegacyPairSetupPin(pin, PairingKeys.get(context).edPublic)
    }

    /**
     * POST /pair-setup-pin — the legacy AirPlay plist SRP exchange. Step 1 ({method,user}) returns
     * {pk,salt}; step 2 ({pk,proof}) verifies the PIN and returns {proof}. On success the controller
     * is allowed past pair-verify (→ streaming). Bounded by the failed-attempt lockout.
     */
    internal fun handleLegacyPairSetupPin(request: RtspRequest): RtspResponse {
        if (!pinAuthEnabled) return verbs.handleUnknownInternal(request)
        if ((pairingStore?.failedAttempts() ?: 0) >= MAX_PAIR_ATTEMPTS) {
            Logger.w("pair-setup-pin blocked — PIN auth locked ($MAX_PAIR_ATTEMPTS failed attempts)")
            onShowPin(null)
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        return try {
            val plist = PlistCodec.decode(request.bodyBytes)
            if (legacyPin == null) newSrpSession()   // step 1 may arrive without a prior /pair-pin-start
            val result = legacyPin!!.handle(plist)
            if (result.failed) {
                val n = pairingStore?.recordFailedAttempt() ?: 0
                Logger.w("pair-setup-pin attempt failed ($n/$MAX_PAIR_ATTEMPTS)")
                onShowPin(null); legacyPin = null
                return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
            }
            if (result.complete) {
                pairingStore?.resetFailedAttempts()   // legitimate pairing clears the lockout counter
                pinPaired = true                       // now allow pair-verify → streaming proceeds
                onShowPin(null); legacyPin = null
                Logger.i("PIN pairing complete — pair-verify now permitted")
            }
            RtspResponse(
                200, "OK",
                bodyBytes = PlistCodec.encode(result.reply!!),
                contentType = "application/x-apple-binary-plist",
                protocol = request.responseProtocol()
            )
        } catch (e: Exception) {
            Logger.e("pair-setup-pin failed", e)
            onShowPin(null); legacyPin = null
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    companion object {
        // SRP PIN access control. macOS's AirPlay code-entry field is exactly 4 digits, so the PIN
        // must be 4 digits to be enterable. A 4-digit space is low-entropy, so the load-bearing
        // defense is the MAX_PAIR_ATTEMPTS lockout below (uniform random + hard attempt cap, NOT
        // length). The PIN is still uniformly random — no biased truncation.
        private const val PIN_DIGITS = 4
        private const val PIN_SPACE = 10_000        // 10^PIN_DIGITS
        private const val MAX_PAIR_ATTEMPTS = 10
    }
}
