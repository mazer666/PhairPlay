package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MdnsService — Advertises PhairPlay as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS/iOS to show PhairPlay in the AirPlay menu, the device must announce
 * itself using mDNS (Multicast DNS, the same protocol as Apple's Bonjour).
 * Without this advertisement, no sender would know PhairPlay exists.
 *
 * HOW: Registers two mDNS services using Android's [NsdManager]:
 * - `_airplay._tcp` — main AirPlay service with feature flags and device info
 * - `_raop._tcp`    — audio streaming service (required even for screen mirroring)
 *
 * Both services use port [AIRPLAY_PORT] (7000), which is where [RtspHandler] listens.
 *
 * The service name shown in AirPlay pickers is determined by [displayNameOverride]:
 * - If set: uses the user-configured name from Settings
 * - If blank/null: falls back to [NetworkUtils.getDeviceName]
 *
 * State changes are reported via [onStateChange] callback.
 *
 * ── Registration lifecycle (why this class is not as simple as it looks) ──
 *
 * [NsdManager.unregisterService] is asynchronous: it returns before the platform's
 * mDNS daemon has actually dropped the registration. Registering again immediately
 * afterwards can therefore collide with the outgoing registration. The platform
 * answers that with `FAILURE_ALREADY_ACTIVE`, and — after a few such cycles — the
 * daemon starts refusing work with "Exceeded max outstanding requests", leaving the
 * receiver permanently invisible in every AirPlay picker.
 *
 * The two services are therefore registered **independently**:
 *   1. Each service has its own attempt counter (see [MdnsRegistrationTracker]).
 *   2. A failed service is retried on its own — a service that registered fine is never
 *      torn down because its sibling failed.
 *   3. `FAILURE_ALREADY_ACTIVE` is treated as a *failure*, not as a success: our
 *      registration was rejected. It is retried after a longer pause, by which time the
 *      previous registration has been released.
 *   4. Only when *both* services are registered does the state become
 *      [ProtocolState.ADVERTISING]. If the retries run out, the state becomes
 *      [ProtocolState.ERROR] — never [ProtocolState.DISABLED], which means "the user
 *      switched this protocol off".
 *
 * Example:
 *   val mdns = MdnsService(context, onStateChange = { state -> /* update UI */ })
 *   mdns.start(displayNameOverride = "Living Room TV")
 *   mdns.stop()
 *   mdns.restart(displayNameOverride = "Living Room TV")
 */
class MdnsService(
    private val context: Context,
    private val onStateChange: (ProtocolState) -> Unit = {},
    /**
     * Called with the actual mDNS service name after registration completes.
     *
     * Android's NsdManager resolves name collisions automatically: if another device
     * on the network is already registered as "PhairPlay", Android will register us as
     * "PhairPlay (2)" instead. The [onActualNameRegistered] callback delivers the name
     * that was actually registered (which may differ from the requested name).
     *
     * The caller can use this to update the UI (e.g., show "Registered as: PhairPlay (2)")
     * or log the divergence for debugging.
     *
     * Only the `_airplay._tcp` service name is reported (not the `_raop._tcp` name,
     * which has a MAC address prefix and is not shown to users).
     */
    private val onActualNameRegistered: (String) -> Unit = {}
) {

    // Android's built-in mDNS manager — handles multicast registration
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Wi-Fi multicast lock, held for as long as we advertise.
     *
     * Wi-Fi hardware drops multicast and broadcast frames that are not addressed to this
     * device whenever it is allowed to power-save, and mDNS is entirely multicast. Without
     * this lock a sender's query can simply never reach us, which looks exactly like "the TV
     * is not discoverable" even though the registration is live. The manifest has asked for
     * CHANGE_WIFI_MULTICAST_STATE since the beginning; nothing used it.
     *
     * Costs battery on a phone — irrelevant here, this is a mains-powered TV receiver whose
     * entire job is to be findable.
     */
    private val multicastLock: WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("PhairPlay-mdns").apply { setReferenceCounted(false) }
    }.getOrElse {
        Logger.w("Could not create Wi-Fi multicast lock: ${it.message}")
        null
    }

    // Listeners track registration state; held to enable unregistration later
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null

    // Guard against double-start
    @Volatile
    private var isStarted = false

    /**
     * Bookkeeping for the two service registrations — see [MdnsRegistrationTracker].
     */
    private val tracker = MdnsRegistrationTracker(serviceCount = 2)

    // The name we requested to register — compared against the actual registered name
    // in onServiceRegistered to detect mDNS collision auto-renaming.
    @Volatile
    private var requestedName: String = ""

    // Scope for the retry backoff. Cancelled on stop(), recreated on start()/restart().
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The pending retry, so it can be cancelled when a new round starts or we stop.
    @Volatile
    private var retryJob: Job? = null

    /**
     * Starts mDNS advertising.
     *
     * Registers both the `_airplay._tcp` and `_raop._tcp` services.
     * The device will appear in the macOS/iOS AirPlay menu within ~1-3 seconds.
     *
     * Idempotent: calling it twice without [stop] in between is a no-op.
     *
     * @param displayNameOverride User-configured display name from Settings.
     *   Pass `null` or blank to use the Android system device name.
     */
    fun start(displayNameOverride: String? = null) {
        if (isStarted) {
            Logger.w("MdnsService.start() called but already registered — ignoring")
            return
        }
        isStarted = true
        ensureScope()
        acquireMulticastLock()

        val effectiveName = resolveDisplayName(displayNameOverride)
        Logger.i("Starting mDNS advertising as '$effectiveName'")
        requestedName = effectiveName

        tracker.reset()
        registerAirPlayService(effectiveName)
        registerRaopService(effectiveName)
    }

    /**
     * Stops mDNS advertising.
     *
     * Unregisters both mDNS services. The device disappears from sender pickers
     * within ~5-10 seconds (mDNS goodbye packet sent immediately, but senders cache briefly).
     *
     * Safe to call even if [start] was never called.
     */
    fun stop() {
        Logger.i("Stopping mDNS advertising")
        // Abandon any pending retry so it cannot resurrect the advertisement.
        retryJob?.cancel()
        retryJob = null
        unregisterAll()
        releaseMulticastLock()
        tracker.reset()
        isStarted = false
        scope.cancel()
        onStateChange(ProtocolState.DISABLED)
    }

    /**
     * Restarts mDNS advertising.
     *
     * Used after a streaming session ends to immediately re-advertise the device
     * in sender pickers, and after a network drop.
     *
     * Unlike [stop] this deliberately does **not** publish [ProtocolState.DISABLED]:
     * the receiver is enabled and about to advertise again, so reporting "disabled"
     * in between made the Home screen claim "Enable in Settings" for a receiver that
     * was enabled all along (issue #18). A failed re-registration is reported as
     * [ProtocolState.ERROR] instead.
     *
     * @param displayNameOverride Updated display name, if changed in Settings.
     */
    fun restart(displayNameOverride: String? = null) {
        Logger.d("Restarting mDNS advertising")
        retryJob?.cancel()
        retryJob = null
        unregisterAll()
        tracker.reset()
        isStarted = true
        ensureScope()
        // Keep the multicast lock across a restart — dropping it would briefly make the
        // receiver invisible to mDNS queries on a network that filters idle multicast.
        acquireMulticastLock()

        val effectiveName = resolveDisplayName(displayNameOverride)
        requestedName = effectiveName
        registerAirPlayService(effectiveName)
        registerRaopService(effectiveName)
    }

    private fun ensureScope() {
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: return
        if (!lock.isHeld) {
            runCatching { lock.acquire() }
                .onSuccess { Logger.d("Wi-Fi multicast lock acquired") }
                .onFailure { Logger.w("Could not acquire multicast lock: ${it.message}") }
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
                .onSuccess { Logger.d("Wi-Fi multicast lock released") }
                .onFailure { Logger.w("Could not release multicast lock: ${it.message}") }
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Determines the effective name to advertise.
     * Uses [override] if non-blank; otherwise reads from the Android system.
     *
     * The result is sanitized: an mDNS service name that the platform rejects would
     * otherwise take the whole AirPlay receiver down (see [sanitizeServiceName]).
     */
    private fun resolveDisplayName(override: String?): String {
        val trimmed = override?.trim() ?: ""
        val candidate = if (trimmed.isNotEmpty()) trimmed else NetworkUtils.getDeviceName(context)
        return sanitizeServiceName(candidate)
    }

    /**
     * Makes a name safe to use as an mDNS service instance name.
     *
     * WHY: A user-supplied name (or a vendor device name) may contain characters the
     * platform's mDNS daemon rejects. [NsdManager.registerService] then throws, and an
     * unhandled throw inside the registration path takes the AirPlay receiver down with
     * it — the device vanishes from every AirPlay picker with no obvious cause.
     */
    private fun sanitizeServiceName(raw: String): String =
        MdnsServiceName.sanitize(raw, NetworkUtils.getDeviceName(context))

    /**
     * Releases every registration this instance holds.
     *
     * Safe to call when a registration never made it through — [NsdManager] throws
     * IllegalArgumentException for a listener it does not know, which is not an error
     * here.
     */
    private fun unregisterAll() {
        val listeners = listOfNotNull(airPlayListener, raopListener)
        airPlayListener = null
        raopListener = null
        for (listener in listeners) {
            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                Logger.d("mDNS unregister skipped: ${e.message}")
            }
        }
    }

    /**
     * Registers the `_airplay._tcp` mDNS service.
     *
     * TXT records tell senders what features PhairPlay supports.
     * See TECHNICAL_SPEC.md §8 for bit-level breakdown of the `features` value.
     *
     * @param displayName The name shown in sender AirPlay pickers.
     */
    private fun registerAirPlayService(displayName: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE_AIRPLAY
            port = AIRPLAY_PORT

            // Core identity TXT records
            setAttribute("deviceid", NetworkUtils.getMacAddress())
            setAttribute("features", AIRPLAY_FEATURES)
            setAttribute("model", AIRPLAY_MODEL)
            setAttribute("srcvers", AIRPLAY_SERVER_VERSION)
            setAttribute("vv", "2")                             // AirPlay protocol version 2
            setAttribute("pi", NetworkUtils.getPersistentUuid(context))
            setAttribute("flags", "0x4")                        // Screen-mirroring receiver
        }

        val gen = tracker.beginAttempt(SLOT_AIRPLAY)
        val listener = createRegistrationListener(
            serviceLabel = "_airplay._tcp",
            slot = SLOT_AIRPLAY,
            gen = gen,
            onRegisteredName = { actualName ->
                // Detect collision auto-renaming: NsdManager appended " (2)", " (3)", etc.
                if (actualName != requestedName) {
                    Logger.w("mDNS name collision detected: requested='$requestedName' " +
                             "actual='$actualName' — NsdManager resolved automatically")
                }
                onActualNameRegistered(actualName)
            }
        )
        airPlayListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            // A rejected service name or a platform limit throws here instead of calling
            // back. Treat it exactly like a failed registration so the service is retried
            // and the user finally gets an ERROR state instead of a silent dead receiver.
            Logger.e("mDNS registerService threw for _airplay._tcp", e)
            handleRegistrationFailure(SLOT_AIRPLAY, gen, "_airplay._tcp", NsdManager.FAILURE_INTERNAL_ERROR)
        }
    }

    /**
     * Registers the `_raop._tcp` mDNS service.
     *
     * RAOP (Remote Audio Output Protocol) is the audio component of AirPlay.
     * macOS and iOS require it even for screen mirroring — not only for audio-only streams.
     *
     * RAOP service name format required by the AirPlay protocol:
     *   `"<MACADDRESS_NOCOLONS>@<DeviceName>"`
     *   e.g., `"AABBCCDDEEFF@Living Room TV"`
     *
     * @param displayName The device name portion of the RAOP service name.
     */
    private fun registerRaopService(displayName: String) {
        val macHex = NetworkUtils.getMacAddress().replace(":", "").uppercase()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$macHex@$displayName"  // required RAOP format
            serviceType = SERVICE_TYPE_RAOP
            port = AIRPLAY_PORT

            setAttribute("cn", "0,1,2,3")        // Cipher numbers (encryption types)
            setAttribute("da", "true")             // Digest authentication capable
            setAttribute("et", "0,3,5")            // Encryption types supported
            setAttribute("md", "0,1,2")            // Metadata types supported
            setAttribute("sv", "false")            // Software volume control
            setAttribute("tp", "UDP")              // Transport for audio RTP
            setAttribute("vn", "65537")            // Version number (required)
            setAttribute("vs", AIRPLAY_SERVER_VERSION)
            setAttribute("am", AIRPLAY_MODEL)
        }

        val gen = tracker.beginAttempt(SLOT_RAOP)
        val listener = createRegistrationListener(
            serviceLabel = "_raop._tcp",
            slot = SLOT_RAOP,
            gen = gen,
            onRegisteredName = null  // RAOP name has MAC prefix — not shown to users
        )
        raopListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Logger.e("mDNS registerService threw for _raop._tcp", e)
            handleRegistrationFailure(SLOT_RAOP, gen, "_raop._tcp", NsdManager.FAILURE_INTERNAL_ERROR)
        }
    }

    /**
     * Handles a failed registration of one service.
     *
     * The service is retried on its own — the other service keeps its registration and
     * is never torn down because of a sibling's failure. When the retries are exhausted
     * the state becomes [ProtocolState.ERROR]: the receiver is enabled but not
     * advertising, which is exactly what ERROR means.
     */
    private fun handleRegistrationFailure(
        slot: Int,
        gen: Int,
        label: String,
        errorCode: Int
    ) {
        val failures = tracker.onFailed(slot, gen)
        if (failures < 0) return          // outdated attempt — a newer one is in flight

        if (failures >= MAX_REGISTRATION_ATTEMPTS) {
            Logger.e("mDNS registration failed permanently for $label after $failures attempts")
            isStarted = false
            onStateChange(ProtocolState.ERROR)
            return
        }

        val delayMs = registrationRetryDelayMs(failures, errorCode)
        Logger.w("mDNS registration failed ($label, attempt $failures, errorCode=$errorCode) — " +
                 "retrying in $delayMs ms")
        retryJob = scope.launch {
            delay(delayMs)
            if (!isStarted) return@launch
            when (slot) {
                SLOT_AIRPLAY -> registerAirPlayService(requestedName)
                SLOT_RAOP    -> registerRaopService(requestedName)
            }
        }
    }

    /**
     * Backoff before the next registration attempt.
     *
     * `FAILURE_ALREADY_ACTIVE` means the previous registration is still winding down,
     * so that case waits longer than a plain failure.
     */
    private fun registrationRetryDelayMs(attempt: Int, errorCode: Int): Long {
        val base = REGISTRATION_RETRY_DELAYS_MS.getOrElse(attempt - 1) {
            REGISTRATION_RETRY_DELAYS_MS.last()
        }
        return if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
            maxOf(base, ALREADY_ACTIVE_RETRY_DELAY_MS)
        } else {
            base
        }
    }

    /**
     * Creates an [NsdManager.RegistrationListener] with logging and callbacks.
     *
     * @param serviceLabel     Human-readable service type for log messages.
     * @param slot             Which service this listener belongs to.
     * @param gen              Attempt number of this registration.
     * @param onRegisteredName Called with the actual registered service name (may differ from
     *   requested due to collision resolution). Pass null if the name is not user-visible.
     */
    private fun createRegistrationListener(
        serviceLabel: String,
        slot: Int,
        gen: Int,
        onRegisteredName: ((String) -> Unit)?
    ): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // NsdManager may append " (2)" to resolve name conflicts.
                // Log the actual name so we can debug picker-visibility issues.
                Logger.i("mDNS registered: $serviceLabel as '${serviceInfo.serviceName}'")
                onRegisteredName?.invoke(serviceInfo.serviceName)
                if (tracker.onRegistered(slot, gen)) {
                    onStateChange(ProtocolState.ADVERTISING)
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Error codes from NsdManager:
                //   FAILURE_ALREADY_ACTIVE (3) — the previous registration is still winding
                //     down. It is NOT a success: our registration was rejected, so the
                //     device would not be discoverable. Retry once the old one is gone.
                //   FAILURE_MAX_LIMIT (4)      — too many services (should not happen)
                //   FAILURE_INTERNAL_ERROR (0) — system mDNS daemon issue
                Logger.e("mDNS registration FAILED for $serviceLabel, errorCode=$errorCode")
                handleRegistrationFailure(slot, gen, serviceLabel, errorCode)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Logger.d("mDNS unregistered: $serviceLabel")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Non-fatal: the service will expire via mDNS TTL (~4500ms by default)
                Logger.w("mDNS unregistration failed for $serviceLabel, errorCode=$errorCode (non-fatal)")
            }
        }
    }

    companion object {
        /** Standard mDNS service type for AirPlay receivers. */
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp"

        /** Standard mDNS service type for RAOP (audio). Required alongside AirPlay. */
        private const val SERVICE_TYPE_RAOP = "_raop._tcp"

        /** AirPlay RTSP port — [RtspHandler] must listen on this port. */
        const val AIRPLAY_PORT = 7000

        /**
         * AirPlay feature bitmask: advertise screen mirroring, video, and audio support.
         * See TECHNICAL_SPEC.md §8 for the full bit-level breakdown.
         */
        private const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"

        /** Pretend to be an Apple TV so macOS uses the screen mirroring protocol. */
        private const val AIRPLAY_MODEL = "AppleTV5,3"

        /** AirPlay server version — matches a real Apple TV for maximum compatibility. */
        private const val AIRPLAY_SERVER_VERSION = "220.68"

        /** Slot of the `_airplay._tcp` service in the registration tracker. */
        private const val SLOT_AIRPLAY = 0

        /** Slot of the `_raop._tcp` service in the registration tracker. */
        private const val SLOT_RAOP = 1

        /** How many times a service registration is attempted before giving up. */
        private const val MAX_REGISTRATION_ATTEMPTS = 3

        /** Backoff before retry 1, retry 2 and retry 3 (milliseconds). */
        private val REGISTRATION_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 6_000L)

        /**
         * Minimum wait before retrying after `FAILURE_ALREADY_ACTIVE`.
         * The platform has not released the previous registration yet, so retrying sooner
         * would just be rejected again.
         */
        private const val ALREADY_ACTIVE_RETRY_DELAY_MS = 2_000L
    }
}
