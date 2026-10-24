package com.phairplay.miracast

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import com.phairplay.airplay.RtspRequest
import com.phairplay.airplay.RtspRequestReader
import com.phairplay.airplay.RtspResponse
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * MiracastReceiver — Miracast (Wi-Fi Display / WFD) receiver service advertiser.
 *
 * WHY: Miracast allows Windows 10+ and Android devices to wirelessly mirror
 * their screen without being on the same Wi-Fi network. It uses Wi-Fi Direct
 * (P2P) to create a direct device-to-device connection.
 *
 * HOW: Implementation proceeds in phases:
 * - Phase 1: Architecture defined, P2P manager initialized
 * - Phase 2: Wi-Fi P2P service discovery advertised
 * - WFD RTSP session negotiation
 * - Phase 4 (M6): H.264 video decode + audio playback
 *
 * Miracast protocol stack:
 *   Wi-Fi Direct (P2P) → WFD RTSP → RTP/H.264 → MediaCodec → SurfaceView
 *
 * Key Android APIs used:
 *   - [WifiP2pManager]: for discovering peers and accepting connections
 *   - [WifiP2pManager.Channel]: communication channel to the P2P framework
 *   - Custom WFD RTSP: similar to AirPlay RTSP but with WFD-specific methods
 *
 * IMPORTANT LIMITATIONS (see ADR-001):
 * - Miracast requires Wi-Fi Direct, which some Android TV devices disable
 * - The WFD stack on Android TV is partly hidden (system APIs)
 * - Real-world compatibility must be tested on actual hardware
 * - Miracast is NOT available on Fire TV with standard APIs
 *
 * Example:
 *   val receiver = MiracastReceiver(context) { state -> updateUI(state) }
 *   receiver.start()  // begins P2P service advertisement
 *   receiver.stop()   // stops advertisement and closes session
 */
class MiracastReceiver(
    private val context: Context,
    private val onStateChanged: (ProtocolState) -> Unit
) {

    // Android's Wi-Fi P2P manager — the entry point for all Wi-Fi Direct operations
    private var wifiP2pManager: WifiP2pManager? = null

    // The communication channel between the app and the Wi-Fi P2P framework
    private var channel: Channel? = null

    // The local DNS-SD service record advertised through Wi-Fi Direct.
    private var serviceInfo: WifiP2pDnsSdServiceInfo? = null

    // Whether the P2P service advertisement is currently active
    @Volatile
    private var isAdvertising = false

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val rtspServer = WfdRtspServer(
        onSessionStarted = {
            Logger.i("Miracast WFD session connected")
            onStateChanged(ProtocolState.CONNECTED)
        },
        onSessionStopped = {
            Logger.i("Miracast WFD session stopped")
            if (isAdvertising) onStateChanged(ProtocolState.ADVERTISING)
        }
    )

    /**
     * Starts the Miracast receiver.
     *
     * Current implementation:
     * - Initializes the WifiP2pManager and Channel
     * - Logs availability of Wi-Fi Direct on this device
     * - Registers a local Wi-Fi Direct DNS-SD WFD service
     * - Opens the WFD RTSP control server on port 7236
     */
    fun start() {
        Logger.i("MiracastReceiver starting")
        initializeWifiP2p()
    }

    /**
     * Stops the Miracast receiver.
     *
     * Unregisters P2P service, disconnects any active WFD session,
     * and releases the WifiP2pManager channel.
     */
    fun stop() {
        Logger.i("MiracastReceiver stopping")
        try {
            stopP2pAdvertisement()
            rtspServer.stop()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                channel?.close()
            }
        } catch (e: Exception) {
            Logger.e("Error stopping MiracastReceiver (non-fatal)", e)
        } finally {
            wifiP2pManager = null
            channel = null
            isAdvertising = false
            job.cancel()
            onStateChanged(ProtocolState.DISABLED)
        }
    }

    /**
     * Initializes the WifiP2pManager and Channel.
     *
     * The [WifiP2pManager] is retrieved from Android's system services.
     * The [Channel] is the app's communication link to the P2P framework.
     *
     * If Wi-Fi Direct is not available on this device (some Android TV boxes
     * don't support it), [wifiP2pManager] will be null and we emit an ERROR state.
     */
    private fun initializeWifiP2p() {
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Logger.w("WifiP2pManager not available on this device — Miracast not supported")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        // Initialize the channel: connects the app to the Wi-Fi P2P framework
        // The looper parameter specifies which thread receives P2P framework callbacks
        channel = wifiP2pManager!!.initialize(
            context,
            context.mainLooper,
            object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    // P2P framework disconnected — this can happen if Wi-Fi is turned off
                    Logger.w("WifiP2p channel disconnected")
                    onStateChanged(ProtocolState.ERROR)
                }
            }
        )

        Logger.i("WifiP2pManager initialized — registering P2P service")
        registerP2pService()
    }

    /**
     * Stops the P2P service advertisement.
     */
    private fun stopP2pAdvertisement() {
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        val activeService = serviceInfo ?: return
        if (!isAdvertising) return

        try {
            manager.removeLocalService(
                activeChannel,
                activeService,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Logger.d("P2P service advertisement stopped")
                    }

                    override fun onFailure(reason: Int) {
                        Logger.w("P2P service removal failed, reason=$reason (non-fatal)")
                    }
                }
            )
        } catch (e: SecurityException) {
            Logger.e("Missing Wi-Fi P2P permission while removing Miracast service", e)
        }
        serviceInfo = null
        isAdvertising = false
    }

    /**
     * Registers the WFD local service record used by Wi-Fi Direct discovery.
     *
     * Android exposes Wi-Fi Direct service discovery through DNS-SD TXT records.
     * Miracast senders look for `_wfd._tcp` and then continue with WFD capability
     * negotiation over RTSP after the P2P group is formed.
     */
    private fun registerP2pService() {
        val manager = wifiP2pManager
        val activeChannel = channel
        if (manager == null || activeChannel == null) {
            Logger.w("Cannot register Miracast P2P service before Wi-Fi P2P initialization")
            onStateChanged(ProtocolState.ERROR)
            return
        }
        if (!hasWifiP2pPermission()) {
            Logger.w("Cannot register Miracast P2P service: missing Wi-Fi Direct permission")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        val txtRecord = mapOf(
            "wfd_device_type" to "primary_sink",
            "wfd_session_available" to "1",
            "wfd_rtsp_port" to WFD_RTSP_PORT.toString(),
            "wfd_video_formats" to "h264-chp,h264-cbp",
            "wfd_audio_codecs" to "lpcm"
        )
        val localService = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE_NAME,
            SERVICE_TYPE_WFD,
            txtRecord
        )

        try {
            manager.addLocalService(
                activeChannel,
                localService,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        serviceInfo = localService
                        isAdvertising = true
                        rtspServer.start(scope)
                        Logger.i("Miracast WFD P2P service advertised")
                        onStateChanged(ProtocolState.ADVERTISING)
                    }

                    override fun onFailure(reason: Int) {
                        serviceInfo = null
                        isAdvertising = false
                        Logger.e("Miracast WFD P2P service registration failed, reason=$reason")
                        onStateChanged(ProtocolState.ERROR)
                    }
                }
            )
        } catch (e: SecurityException) {
            serviceInfo = null
            isAdvertising = false
            Logger.e("Missing Wi-Fi P2P permission while registering Miracast service", e)
            onStateChanged(ProtocolState.ERROR)
        }
    }

    private fun hasWifiP2pPermission(): Boolean {
        return context.checkSelfPermission(PERMISSION_NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(PERMISSION_ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val WFD_RTSP_PORT = 7236
        private const val SERVICE_INSTANCE_NAME = "PhairPlay"
        private const val SERVICE_TYPE_WFD = "_wfd._tcp"
        private const val PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
        private const val PERMISSION_NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    }
}

/**
 * WfdRtspServer — the RTSP endpoint a Miracast sender opens after the WFD handshake.
 *
 * WHY: the Wi-Fi Display handshake only negotiates the connection; the session itself
 * (OPTIONS, GET_PARAMETER, SETUP, PLAY, TEARDOWN) runs over RTSP. Without this server
 * the sender has no session to stream into and the Miracast connection dies right
 * after the handshake.
 *
 * Example:
 *   WfdRtspServer(onSessionStarted = { ... }, onSessionStopped = { ... }).start(scope)
 */
internal class WfdRtspServer(
    private val onSessionStarted: () -> Unit,
    private val onSessionStopped: () -> Unit
) {
    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = MAX_MESSAGE_BYTES
    )

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null
    private var currentCSeq = 0
    private var sessionStarted = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    fun stop() {
        running = false
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing WFD RTSP sockets (non-fatal)", e)
        }
        activeClient = null
        serverSocket = null
        if (sessionStarted) {
            sessionStarted = false
            onSessionStopped()
        }
    }

    private fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = ServerSocket(MiracastReceiver.WFD_RTSP_PORT)
            Logger.i("WFD RTSP server listening on port ${MiracastReceiver.WFD_RTSP_PORT}")
            while (running && scope.isActive) {
                val client = serverSocket!!.accept()
                if (activeClient != null && !activeClient!!.isClosed) {
                    sendServiceUnavailable(client)
                    client.close()
                    continue
                }
                activeClient = client
                handleClient(client)
            }
        } catch (e: Exception) {
            if (running) Logger.e("WFD RTSP server error", e)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            while (running && !socket.isClosed) {
                val request = requestReader.read(input) ?: break
                currentCSeq = request.headers["CSeq"]?.toIntOrNull() ?: 0
                val response = routeRequest(request)
                sendResponse(output, response)
                if (request.method == "TEARDOWN") break
            }
        } catch (e: Exception) {
            if (running) Logger.e("Error handling WFD RTSP client", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Logger.e("Error closing WFD RTSP client socket (non-fatal)", e)
            }
            activeClient = null
            if (sessionStarted) {
                sessionStarted = false
                onSessionStopped()
            }
        }
    }

    internal fun routeRequest(request: RtspRequest): RtspResponse {
        Logger.d("WFD RTSP ${request.method} ${request.uri}")
        return when (request.method) {
            "OPTIONS" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Public" to "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER")
            )
            "GET_PARAMETER" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Content-Type" to "text/parameters"),
                body = sinkParameters()
            )
            "SET_PARAMETER" -> RtspResponse(statusCode = 200, statusMessage = "OK")
            "SETUP" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf(
                    "Session" to WFD_SESSION_ID,
                    "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"
                )
            )
            "PLAY" -> {
                if (!sessionStarted) {
                    sessionStarted = true
                    onSessionStarted()
                }
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    headers = mapOf("Session" to WFD_SESSION_ID)
                )
            }
            "PAUSE" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Session" to WFD_SESSION_ID)
            )
            "TEARDOWN" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Session" to WFD_SESSION_ID)
            )
            else -> RtspResponse(statusCode = 501, statusMessage = "Not Implemented")
        }
    }

    private fun sinkParameters(): String =
        listOf(
            "wfd_audio_codecs: LPCM 00000003 00",
            "wfd_video_formats: 00 00 02 10 0001FFFF 00000000 00000000 00 0000 0000 00 none none",
            "wfd_client_rtp_ports: RTP/AVP/TCP;unicast 0 0 mode=play",
            "wfd_content_protection: none",
            "wfd_display_edid: none",
            "wfd_coupled_sink: none",
            "wfd_connector_type: 05"
        ).joinToString(separator = "\r\n", postfix = "\r\n")

    private fun sendResponse(outputStream: OutputStream, response: RtspResponse) {
        val sb = StringBuilder()
        sb.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        sb.append("CSeq: $currentCSeq\r\n")
        sb.append("Server: PhairPlay/1.0\r\n")
        response.headers.forEach { (key, value) -> sb.append("$key: $value\r\n") }
        if (response.body.isNotEmpty()) {
            sb.append("Content-Length: ${response.body.toByteArray(Charsets.UTF_8).size}\r\n")
        }
        sb.append("\r\n")
        sb.append(response.body)
        outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    private fun sendServiceUnavailable(socket: Socket) {
        val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
        socket.outputStream.write(response.toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 65536
        private const val WFD_SESSION_ID = "PhairPlayWfdSession"
    }
}
