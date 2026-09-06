package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EventSubscriptionsTest — GENA subscribe/renew/unsubscribe, the initial event, moderation and expiry.
 *
 * WHY: Windows subscribes to AVTransport and RenderingControl and expects the SEQ 0 initial NOTIFY
 * immediately after the SUBSCRIBE reply; without it the transport buttons stay disabled.
 */
class EventSubscriptionsTest {

    private class FakeSender : NotifySender {
        val sent = mutableListOf<Triple<CallbackUrl, Long, String>>()
        var succeed = true
        override fun send(callback: CallbackUrl, sid: String, seq: Long, propertySetXml: String): Boolean {
            sent += Triple(callback, seq, propertySetXml)
            return succeed
        }
    }

    private var now = 1_000_000L
    private val sender = FakeSender()
    private val subs = EventSubscriptions(
        sender = sender,
        initialState = { service -> mapOf("LastChange" to "<initial-${service.pathName}/>") },
        clock = { now },
        sidFactory = { "uuid:test-sid" }
    )
    private val avt = UpnpService.AV_TRANSPORT
    private val callback = "<http://192.168.1.10:2869/upnp/eventing/abc>"

    private fun subscribe(): HttpResponse = subs.subscribe(avt, callback, "upnp:event", null, "Second-1800")

    @Test
    fun `subscribe returns SID and TIMEOUT and sends the SEQ 0 initial event after the reply`() {
        val response = subscribe()
        assertEquals(200, response.status)
        assertEquals("uuid:test-sid", response.headers["SID"])
        assertEquals("Second-1800", response.headers["TIMEOUT"])
        assertTrue(sender.sent.isEmpty())
        response.afterSend!!.invoke()
        assertEquals(1, sender.sent.size)
        val (url, seq, body) = sender.sent[0]
        assertEquals("192.168.1.10", url.host)
        assertEquals(2869, url.port)
        assertEquals("/upnp/eventing/abc", url.path)
        assertEquals(0L, seq)
        assertTrue(body.contains("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"))
        assertTrue(body.contains("<e:property><LastChange>&lt;initial-AVTransport/&gt;</LastChange></e:property>"))
    }

    @Test
    fun `renewal by SID extends the subscription and unknown SID is 412`() {
        subscribe()
        assertEquals(200, subs.subscribe(avt, null, null, "uuid:test-sid", "Second-1800").status)
        assertEquals(412, subs.subscribe(avt, null, null, "uuid:other", null).status)
        assertEquals(400, subs.subscribe(avt, callback, "upnp:event", "uuid:test-sid", null).status)
    }

    @Test
    fun `missing NT bad callback or public callback are 412`() {
        assertEquals(412, subs.subscribe(avt, callback, null, null, null).status)
        assertEquals(412, subs.subscribe(avt, "<ftp://192.168.1.10/x>", "upnp:event", null, null).status)
        assertEquals(412, subs.subscribe(avt, "<http://8.8.8.8/x>", "upnp:event", null, null).status)
        assertEquals(412, subs.subscribe(avt, "<http://evil.example/x>", "upnp:event", null, null).status)
        assertEquals(0, subs.subscriptionCount())
    }

    @Test
    fun `publish merges variables and flushPending sends one NOTIFY with SEQ 1`() {
        subscribe().afterSend!!.invoke()
        subs.publish(avt, mapOf("LastChange" to "<a/>"))
        subs.publish(avt, mapOf("LastChange" to "<b/>"))
        subs.flushPending()
        assertEquals(2, sender.sent.size)
        assertEquals(1L, sender.sent[1].second)
        assertTrue(sender.sent[1].third.contains("&lt;b/&gt;"))
        assertFalse(sender.sent[1].third.contains("&lt;a/&gt;"))
    }

    @Test
    fun `flushPending respects the moderation window`() {
        subscribe().afterSend!!.invoke()
        subs.publish(avt, mapOf("LastChange" to "<a/>"))
        subs.flushPending()
        subs.publish(avt, mapOf("LastChange" to "<b/>"))
        subs.flushPending()
        assertEquals(2, sender.sent.size)             // second publish held back
        now += DlnaConstants.EVENT_MODERATION_MS
        subs.flushPending()
        assertEquals(3, sender.sent.size)
        assertEquals(2L, sender.sent[2].second)
    }

    @Test
    fun `publish to a service with no subscribers sends nothing`() {
        subs.publish(UpnpService.RENDERING_CONTROL, mapOf("LastChange" to "<a/>"))
        subs.flushPending()
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `two consecutive delivery failures drop the subscription`() {
        subscribe().afterSend!!.invoke()
        sender.succeed = false
        subs.publish(avt, mapOf("LastChange" to "<a/>")); subs.flushPending()
        assertEquals(1, subs.subscriptionCount())
        now += DlnaConstants.EVENT_MODERATION_MS
        subs.publish(avt, mapOf("LastChange" to "<b/>")); subs.flushPending()
        assertEquals(0, subs.subscriptionCount())
    }

    @Test
    fun `unsubscribe removes and expiry sweep drops old subscriptions`() {
        subscribe()
        assertEquals(200, subs.unsubscribe(avt, "uuid:test-sid").status)
        assertEquals(412, subs.unsubscribe(avt, "uuid:test-sid").status)
        subscribe()
        now += DlnaConstants.SUBSCRIPTION_TIMEOUT_SECONDS * 1000L + 1
        subs.sweepExpired()
        assertEquals(0, subs.subscriptionCount())
    }

    @Test
    fun `CallbackUrl parses host port and path and knows private ranges`() {
        val url = CallbackUrl.parse("http://10.0.0.5/notify")
        assertNotNull(url)
        assertEquals(80, url!!.port)
        assertEquals("/notify", url.path)
        assertTrue(url.isPrivate)
        assertTrue(CallbackUrl.parse("http://172.16.4.4:1234/")!!.isPrivate)
        assertTrue(CallbackUrl.parse("http://169.254.1.1/")!!.isPrivate)
        assertFalse(CallbackUrl.parse("http://1.2.3.4/")!!.isPrivate)
        assertNull(CallbackUrl.parse("https://192.168.1.1/"))
        assertNull(CallbackUrl.parse("http://host.local/"))
    }
}
