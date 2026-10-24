package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GenaEventManager — the GENA (General Event Notification Architecture) registry.
 *
 * WHY: a control point subscribes to a service with a `SUBSCRIBE` request and then
 * expects the renderer to push state changes to a callback URL. Three things have to
 * be right for that to work:
 *
 * - the callback URL has to be validated (see [GenaCallbackPolicy]) — otherwise the
 *   renderer becomes a request forwarder for the local network,
 * - the `LastChange` document has to use the nested UPnP format (see [GenaLastChange]),
 * - subscriptions have to expire, and a control point that stopped answering has to
 *   be dropped instead of being notified forever.
 *
 * Example:
 *   val subscription = manager.subscribe("<http://192.168.1.44:1400/event>", "/upnp/event/AVTransport")
 *   manager.publish("/upnp/event/AVTransport", lastChangeDocument)
 */
class GenaEventManager(
    private val localHost: String,
    private val scope: CoroutineScope
) {
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val purgeStarted = AtomicBoolean(false)

    /**
     * Registers a control point for event notifications.
     *
     * @param callbackHeader the raw `CALLBACK` header, e.g. `<http://192.168.1.44:1400/event>`.
     * @param servicePath the event URL of the service, e.g. `/upnp/event/AVTransport`.
     * @param requestedTimeoutSeconds the timeout the control point asked for; it is
     *   clamped into the range the renderer supports.
     * @return the subscription with its SID and the effective timeout.
     * @throws IllegalArgumentException when the callback URL is not allowed.
     */
    fun subscribe(
        callbackHeader: String?,
        servicePath: String,
        requestedTimeoutSeconds: Int? = null
    ): GenaSubscription {
        val callbackUrl = parseCallback(callbackHeader)
        require(isAllowedCallback(callbackUrl)) {
            "GENA callback must be a private IPv4 URL on the local subnet"
        }
        require(servicePath.startsWith("/upnp/event/")) { "Unknown event service path" }
        startPurgeLoop()
        val timeout = clampTimeout(requestedTimeoutSeconds)
        val sid = "uuid:${UUID.randomUUID()}"
        subscriptions[sid] = Subscription(
            sid = sid,
            callbackUrl = callbackUrl.toString(),
            servicePath = servicePath,
            expiresAtMillis = System.currentTimeMillis() + timeout * 1_000L
        )
        return GenaSubscription(sid = sid, timeoutSeconds = timeout)
    }

    /**
     * Removes a subscription.
     *
     * @param sid the SID from the `SID` header.
     * @return true when a subscription was removed.
     */
    fun unsubscribe(sid: String?): Boolean {
        if (sid.isNullOrBlank()) return false
        return subscriptions.remove(sid) != null
    }

    /**
     * Sends a `LastChange` event to every subscriber of [servicePath].
     *
     * @param servicePath the event URL of the service.
     * @param lastChange the nested UPnP `LastChange` document.
     */
    fun publish(servicePath: String, lastChange: String) {
        purgeExpired()
        val matching = subscriptions.values.filter { it.servicePath == servicePath }
        matching.forEach { subscription ->
            scope.launch(Dispatchers.IO) {
                if (!sendNotify(subscription, lastChange)) {
                    // The control point does not answer any more — an endless retry
                    // would keep a dead peer alive forever.
                    subscriptions.remove(subscription.sid)
                    Logger.w("GENA subscription ${subscription.sid} removed after failed notify")
                }
            }
        }
    }

    /**
     * Removes every subscription and stops the background maintenance.
     *
     * WHY: when the protocol is switched off, the renderer must not keep posting
     * events to a control point that already gave up.
     */
    fun stop() {
        subscriptions.clear()
    }

    /** Returns true when the callback URL passes the SSRF guard. */
    internal fun isAllowedCallback(uri: URI): Boolean =
        GenaCallbackPolicy.isAllowedCallback(uri, localHost)

    /** Returns the number of subscriptions that are still active. */
    internal fun activeSubscriptionCount(): Int {
        purgeExpired()
        return subscriptions.size
    }

    /**
     * Parses the `CALLBACK` header.
     *
     * WHY: the header is a list of angle-bracketed URLs, e.g.
     * `<http://192.168.1.44:1400/event>`. Only the first entry is used.
     */
    private fun parseCallback(header: String?): URI {
        val raw = header?.trim().orEmpty()
        require(raw.startsWith("<") && raw.endsWith(">")) { "Invalid CALLBACK header" }
        val inner = raw.removePrefix("<").removeSuffix(">")
        val first = inner.substringBefore(',').trim()
        require(first.isNotEmpty()) { "Invalid CALLBACK header" }
        return URI(first)
    }

    /** Clamps the requested subscription timeout into the supported range. */
    private fun clampTimeout(requested: Int?): Int {
        val fallback = DEFAULT_TIMEOUT_SECONDS
        val value = requested ?: fallback
        return value.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    }

    /** Removes every subscription whose timeout has passed. */
    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        subscriptions.entries.removeIf { it.value.expiresAtMillis <= now }
    }

    /**
     * Starts the background task that removes expired subscriptions.
     *
     * WHY lazily: a renderer without subscribers has nothing to maintain, and the
     * task must not outlive the protocol.
     */
    private fun startPurgeLoop() {
        if (!purgeStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PURGE_INTERVAL_MS)
                purgeExpired()
            }
        }
    }

    /**
     * Posts one `NOTIFY` to the control point.
     *
     * @return true when the control point accepted the notification.
     */
    private fun sendNotify(subscription: Subscription, lastChange: String): Boolean {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
              <e:property><LastChange>${SoapResponses.escape(lastChange)}</LastChange></e:property>
            </e:propertyset>
        """.trimIndent()
        return runCatching {
            val uri = URI(subscription.callbackUrl)
            val host = uri.host ?: error("callback without host")
            val port = if (uri.port > 0) uri.port else 80
            val target = uri.rawPath.orEmpty().ifEmpty { "/" } +
                (uri.rawQuery?.let { "?$it" } ?: "")

            // WHY a raw socket instead of HttpURLConnection: the JDK refuses every HTTP
            // method outside GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE, and "NOTIFY" is
            // exactly the method the UPnP eventing specification requires. With
            // HttpURLConnection the event was never delivered at all.
            val request = buildString {
                append("NOTIFY ").append(target).append(" HTTP/1.1\r\n")
                append("HOST: ").append(host).append(':').append(port).append("\r\n")
                append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
                append("NT: upnp:event\r\n")
                append("NTS: upnp:propchange\r\n")
                append("SID: ").append(subscription.sid).append("\r\n")
                append("SEQ: ").append(subscription.nextSequence()).append("\r\n")
                append("CONTENT-LENGTH: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
                append("CONNECTION: close\r\n\r\n")
            }
            val bodyBytes = body.toByteArray(Charsets.UTF_8)

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), HTTP_TIMEOUT_MS)
                socket.soTimeout = HTTP_TIMEOUT_MS
                val output = socket.getOutputStream()
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.write(bodyBytes)
                output.flush()

                // The control point answers with a status line; 2xx means "accepted".
                val statusLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                statusLine != null && statusLine.contains(" 2")
            }
        }.onFailure {
            Logger.w("GENA notify failed for ${subscription.sid}: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    companion object {
        /** Default subscription timeout in seconds. */
        const val DEFAULT_TIMEOUT_SECONDS = 1_800

        /** Shortest subscription timeout the renderer accepts. */
        const val MIN_TIMEOUT_SECONDS = 30

        /** Longest subscription timeout the renderer accepts. */
        const val MAX_TIMEOUT_SECONDS = 1_800

        private const val PURGE_INTERVAL_MS = 30_000L
        private const val HTTP_TIMEOUT_MS = 3_000
    }
}

/** The result of a successful `SUBSCRIBE`. */
data class GenaSubscription(
    val sid: String,
    val timeoutSeconds: Int
)

/**
 * Subscription — the server-side state of one GENA subscriber.
 *
 * WHY: for every `SUBSCRIBE` the manager has to remember the SID, the callback URL
 * and the service path to be able to notify that subscriber later, and it has to know
 * when the subscription expires so that a control point that stopped listening is
 * dropped instead of being notified forever.
 */
private data class Subscription(
    val sid: String,
    val callbackUrl: String,
    val servicePath: String,
    val expiresAtMillis: Long
) {
    private val sequence = java.util.concurrent.atomic.AtomicInteger(0)

    /** Returns the next event sequence number of this subscription. */
    fun nextSequence(): Int = sequence.incrementAndGet()
}
