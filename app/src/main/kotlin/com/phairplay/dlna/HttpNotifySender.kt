package com.phairplay.dlna

import com.phairplay.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * HttpNotifySender — delivers a GENA `NOTIFY` over a raw socket.
 *
 * WHY: `HttpURLConnection` rejects the non-standard NOTIFY method, so the event message is written by hand.
 * One short-lived connection per event, with a hard timeout so a dead subscriber cannot stall the renderer.
 *
 * HOW: Implements [NotifySender]; used by [EventSubscriptions] via [DlnaReceiver].
 */
class HttpNotifySender : NotifySender {

    override fun send(callback: CallbackUrl, sid: String, seq: Long, propertySetXml: String): Boolean {
        val body = propertySetXml.toByteArray(Charsets.UTF_8)
        val head = "NOTIFY ${callback.path} HTTP/1.1\r\n" +
            "HOST: ${callback.host}:${callback.port}\r\n" +
            "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
            "CONTENT-LENGTH: ${body.size}\r\n" +
            "NT: upnp:event\r\n" +
            "NTS: upnp:propchange\r\n" +
            "SID: $sid\r\n" +
            "SEQ: $seq\r\n" +
            "CONNECTION: close\r\n\r\n"
        return try {
            Socket().use { socket ->
                socket.soTimeout = DlnaConstants.EVENT_DELIVERY_TIMEOUT_MS
                socket.connect(InetSocketAddress(callback.host, callback.port), DlnaConstants.EVENT_DELIVERY_TIMEOUT_MS)
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush()
                }
                val statusLine = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1)).readLine() ?: ""
                val ok = statusLine.split(" ").getOrNull(1)?.toIntOrNull()?.let { it in 200..299 } ?: false
                if (!ok) Logger.w("GENA NOTIFY to ${callback.host}:${callback.port} answered '$statusLine'")
                ok
            }
        } catch (e: Exception) {
            Logger.w("GENA NOTIFY to ${callback.host}:${callback.port} failed: ${e.message}")
            false
        }
    }
}
