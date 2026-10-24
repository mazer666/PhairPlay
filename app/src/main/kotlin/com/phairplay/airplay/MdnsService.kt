package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
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
 * The registrations themselves are done by [MdnsServiceRegistrar]; this class owns the
 * advertisement lifecycle (start / stop / restart), the Wi-Fi multicast lock and the retry
 * policy that re-registers a service whose attempt failed.
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

    // Guard against double-start
    @Volatile
    private var isStarted = false

    /**
     * Bookkeeping for the two service registrations — see [MdnsRegistrationTracker].
     */
    private val tracker = MdnsRegistrationTracker(serviceCount = 2)

    // Scope for the retry backoff. Cancelled on stop(), recreated on start()/restart().
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The pending retry, so it can be cancelled when a new round starts or we stop.
    @Volatile
    private var retryJob: Job? = null

    /**
     * Performs the actual registrations — see [MdnsServiceRegistrar].
     */
    private val registrar = MdnsServiceRegistrar(
        context = context,
        nsdManager = nsdManager,
        tracker = tracker,
        onStateChange = onStateChange,
        onActualNameRegistered = onActualNameRegistered,
        handleRegistrationFailure = { slot, gen, label, errorCode ->
            handleRegistrationFailure(slot, gen, label, errorCode)
        }
    )

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

        tracker.reset()
        registrar.registerAll(effectiveName)
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
        registrar.unregisterAll()
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
        registrar.unregisterAll()
        tracker.reset()
        isStarted = true
        ensureScope()
        // Keep the multicast lock across a restart — dropping it would briefly make the
        // receiver invisible to mDNS queries on a network that filters idle multicast.
        acquireMulticastLock()

        val effectiveName = resolveDisplayName(displayNameOverride)
        registrar.registerAll(effectiveName)
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
            registrar.retryRegistration(slot)
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

    companion object {
        /** AirPlay RTSP port — [RtspHandler] must listen on this port. */
        const val AIRPLAY_PORT = 7000

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
