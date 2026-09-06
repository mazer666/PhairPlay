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
