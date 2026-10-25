package com.phairplay.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.phairplay.util.Logger

/**
 * NetworkRecoveryMonitor — Re-advertises the receivers when the network comes back.
 *
 * WHAT: Registers a [ConnectivityManager.NetworkCallback] and tells the service to
 * re-advertise its receivers as soon as the network is available again.
 *
 * WHY: The TV going into standby takes its Wi-Fi interface down with it. An NsdManager
 * registration does not survive that, and nothing in the app noticed: on wake the
 * receiver was still listening on port 7000, still reported itself as running, and was
 * invisible to every Apple device until the app was restarted by hand. This monitor is
 * that missing piece of recovery.
 *
 * The DLNA renderer is affected in the same way — and a second way besides: the
 * description URL of the renderer contains the IP address of the device, so a device
 * that comes back with a new address needs a new description server. That decision is
 * made by the DLNA receiver itself (see `DlnaReceiver.readvertise`).
 *
 * HOW: The service creates one monitor, registers it in `onCreate()` and unregisters it
 * in `onDestroy()`. The monitor only asks for a re-advertisement when the service is
 * actually running — a stopped service has nothing to advertise.
 *
 * Example:
 *   val monitor = NetworkRecoveryMonitor(
 *       context = applicationContext,
 *       isServiceRunning = { stateBus.serviceState.value == ServiceState.Running },
 *       onNetworkRestored = { receivers.readvertiseAll() }
 *   )
 *   monitor.registerNetworkCallback()
 */
internal class NetworkRecoveryMonitor(
    private val context: Context,
    /** True while the service is running — only then is a re-advertisement useful. */
    private val isServiceRunning: () -> Boolean,
    /** Called when the network came back and the receivers have to advertise themselves again. */
    private val onNetworkRestored: () -> Unit
) {

    // Watches for the network coming back so mDNS can be re-registered — see
    // [registerNetworkCallback]. Null when never registered or already unregistered.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Networks currently up. Used to tell "the first network appeared" from
    // "the network came back" — only the latter needs a re-advertisement.
    private val knownNetworks = mutableSetOf<Network>()

    // True once the last network was lost, so the next onAvailable re-advertises.
    @Volatile private var networkWasLost = false

    /**
     * Re-registers the advertisements whenever a network becomes available again.
     *
     * WHY THE FIRST onAvailable IS IGNORED:
     * [ConnectivityManager.NetworkCallback.onAvailable] also fires for the very first
     * network, right after the callback is registered. That happens while the AirPlay
     * receiver is still starting up, so re-advertising then unregisters an mDNS
     * registration that is still in flight. The platform answers that with
     * "already active" or simply drops the new registration — the receiver ends up
     * registered nowhere, invisible to every sender, while the UI happily claims it is
     * advertising. Every further network transition (VPN up, captive portal, Wi-Fi
     * roaming) repeated the churn until the platform refused further registrations
     * with "Exceeded max outstanding requests".
     *
     * So: re-advertise only after the last network was actually lost.
     */
    fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: run {
            Logger.w("No ConnectivityManager — mDNS will not recover automatically from a network drop")
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val hadNetwork = synchronized(knownNetworks) { knownNetworks.isNotEmpty() }
                synchronized(knownNetworks) { knownNetworks.add(network) }

                // A second network appeared while one was already up — the existing
                // advertisement is still valid and re-registering would only churn the
                // platform's mDNS daemon.
                if (hadNetwork) {
                    Logger.d("Additional network available — keeping the existing mDNS advertisement")
                    return
                }
                if (!networkWasLost) {
                    // The first network of this process: the receiver registers on its own.
                    Logger.d("First network available — the receivers register on their own")
                    return
                }
                networkWasLost = false
                if (!isServiceRunning()) return
                Logger.i("Network available again — re-advertising the receivers")
                onNetworkRestored()
            }

            override fun onLost(network: Network) {
                // The registration is gone with the interface. Remember that, so the next
                // onAvailable re-advertises instead of being treated as the initial one.
                val allLost = synchronized(knownNetworks) {
                    knownNetworks.remove(network)
                    knownNetworks.isEmpty()
                }
                if (allLost) networkWasLost = true
                Logger.i("Network lost — the advertisements are gone until it returns")
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess {
                networkCallback = callback
                Logger.d("Network callback registered for mDNS recovery")
            }
            .onFailure { Logger.e("Could not register network callback", it) }
    }

    /** Unregisters the network callback again — the registration must be released. */
    fun unregisterNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
                .onFailure { Logger.w("Could not unregister network callback: ${it.message}") }
        }
        networkCallback = null
    }
}
