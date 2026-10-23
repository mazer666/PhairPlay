package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal GENA event subscription registry with local-subnet callback validation.
 */
class GenaEventManager(
    private val localHost: String,
    private val scope: CoroutineScope
) {
    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    fun subscribe(callbackHeader: String?, servicePath: String): GenaSubscription {
        val callbackUrl = parseCallback(callbackHeader)
        require(isAllowedCallback(callbackUrl)) { "GENA callback must be a private IPv4 URL on the local subnet" }
        val sid = "uuid:${UUID.randomUUID()}"
        subscriptions[sid] = Subscription(sid, callbackUrl.toString(), servicePath)
        return GenaSubscription(sid = sid, timeoutSeconds = DEFAULT_TIMEOUT_SECONDS)
    }

    fun unsubscribe(sid: String?): Boolean {
        if (sid.isNullOrBlank()) return false
        return subscriptions.remove(sid) != null
    }

    fun publish(servicePath: String, lastChange: String) {
        val matching = subscriptions.values.filter { it.servicePath == servicePath }
        matching.forEach { subscription ->
            scope.launch(Dispatchers.IO) {
                sendNotify(subscription, lastChange)
            }
        }
    }

    internal fun isAllowedCallback(uri: URI): Boolean {
        if (uri.scheme != "http") return false
        val host = uri.host ?: return false
        val address = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() as? Inet4Address
            ?: return false
        if (!address.isPrivateIpv4()) return false
        return sameIpv4Subnet(localHost, address.hostAddress ?: return false)
    }

    private fun parseCallback(header: String?): URI {
        val raw = header?.trim().orEmpty()
        require(raw.startsWith("<") && raw.endsWith(">")) { "Invalid CALLBACK header" }
        return URI(raw.removePrefix("<").removeSuffix(">"))
    }

    private fun sendNotify(subscription: Subscription, lastChange: String) {
        val body = """
            <e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
              <e:property><LastChange>${lastChange.xmlEscaped()}</LastChange></e:property>
            </e:propertyset>
        """.trimIndent()
        runCatching {
            val connection = (URL(subscription.callbackUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "NOTIFY"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doOutput = true
                setRequestProperty("CONTENT-TYPE", "text/xml; charset=\"utf-8\"")
                setRequestProperty("NT", "upnp:event")
                setRequestProperty("NTS", "upnp:propchange")
                setRequestProperty("SID", subscription.sid)
                setRequestProperty("SEQ", "0")
            }
            connection.outputStream.use { output: OutputStream ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            connection.responseCode
            connection.disconnect()
        }.onFailure {
            Logger.w("GENA notify failed for ${subscription.callbackUrl}: ${it.message}")
        }
    }

    private fun Inet4Address.isPrivateIpv4(): Boolean {
        val bytes = address.map { it.toInt() and 0xff }
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168)
    }

    private fun sameIpv4Subnet(a: String, b: String): Boolean {
        val left = a.split('.')
        val right = b.split('.')
        return left.size == 4 && right.size == 4 &&
            left[0] == right[0] &&
            left[1] == right[1] &&
            left[2] == right[2]
    }

    private fun String.xmlEscaped(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 1_800
        private const val HTTP_TIMEOUT_MS = 3_000
    }
}

data class GenaSubscription(
    val sid: String,
    val timeoutSeconds: Int
)

private data class Subscription(
    val sid: String,
    val callbackUrl: String,
    val servicePath: String
)
