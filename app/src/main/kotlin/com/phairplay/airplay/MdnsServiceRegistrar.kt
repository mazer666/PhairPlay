package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils

/**
 * MdnsServiceRegistrar — Registers the two mDNS services of an AirPlay receiver.
 *
 * WHAT: Builds the [NsdServiceInfo] of `_airplay._tcp` and `_raop._tcp`, hands both to
 * [NsdManager.registerService] and observes the registration callbacks.
 *
 * WHY: The two services are registered **independently**: one failing must not tear down the
 * other, and each has its own attempt counter (see [MdnsRegistrationTracker]). The
 * registrations therefore belong into one small object that owns the listeners — while the
 * advertisement lifecycle (start / stop / restart) and the retry policy stay with [MdnsService].
 *
 * HOW: The owner ([MdnsService]) creates it with the platform manager, the tracker and the
 * callbacks, then registers the services:
 *   val registrar = MdnsServiceRegistrar(context, nsdManager, tracker,
 *                                       onStateChange, onActualNameRegistered,
 *                                       handleRegistrationFailure)
 *   registrar.registerAll("Living Room TV")
 *
 * Example:
 *   registrar.registerAll("Living Room TV")   // registers _airplay._tcp + _raop._tcp
 *   registrar.unregisterAll()                 // removes both registrations again
 */
internal class MdnsServiceRegistrar(
    /** Context — used to read the persistent device id that macOS uses to recognize us. */
    private val context: Context,
    /** Android's built-in mDNS manager — handles multicast registration. */
    private val nsdManager: NsdManager,
    /** Bookkeeping for the two service registrations. */
    private val tracker: MdnsRegistrationTracker,
    /** Called with the protocol state — ADVERTISING once both services are registered. */
    private val onStateChange: (ProtocolState) -> Unit,
    /** Called with the actual registered name (may differ due to collision resolution). */
    private val onActualNameRegistered: (String) -> Unit,
    /** Called when a registration attempt failed — the owner decides whether to retry. */
    private val handleRegistrationFailure: (slot: Int, gen: Int, label: String, errorCode: Int) -> Unit
) {

    // Listeners track registration state; held to enable unregistration later
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null

    // The name we requested to register — compared against the actual registered name
    // in onServiceRegistered to detect mDNS collision auto-renaming.
    @Volatile
    private var requestedName: String = ""

    /**
     * Registers both mDNS services under [displayName].
     *
     * The requested name is remembered so that a retry after a failed attempt registers
     * the very same name again — and so that a collision auto-renaming can be detected.
     */
    internal fun registerAll(displayName: String) {
        requestedName = displayName
        registerAirPlayService(displayName)
        registerRaopService(displayName)
    }

    /**
     * Releases every registration this instance holds.
     *
     * Safe to call when a registration never made it through — [NsdManager] throws
     * IllegalArgumentException for a listener it does not know, which is not an error
     * here.
     */
    internal fun unregisterAll() {
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
     * Registers the service of [slot] again — used by the retry of a failed attempt.
     *
     * The failed service is retried on its own; the sibling keeps its live registration.
     */
    internal fun retryRegistration(slot: Int) {
        when (slot) {
            SLOT_AIRPLAY -> registerAirPlayService(requestedName)
            SLOT_RAOP    -> registerRaopService(requestedName)
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
            port = MdnsService.AIRPLAY_PORT

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
            port = MdnsService.AIRPLAY_PORT

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
    }
}
