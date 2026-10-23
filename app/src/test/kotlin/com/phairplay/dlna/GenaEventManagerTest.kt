package com.phairplay.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class GenaEventManagerTest {

    private val manager = GenaEventManager(
        localHost = "192.168.1.20",
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    @Test
    fun `allows private IPv4 callback on same subnet`() {
        assertTrue(manager.isAllowedCallback(URI("http://192.168.1.44:1400/event")))
    }

    @Test
    fun `rejects callback outside local subnet`() {
        assertFalse(manager.isAllowedCallback(URI("http://192.168.2.44:1400/event")))
    }

    @Test
    fun `rejects public and non-http callback URLs`() {
        assertFalse(manager.isAllowedCallback(URI("http://8.8.8.8:1400/event")))
        assertFalse(manager.isAllowedCallback(URI("https://192.168.1.44:1400/event")))
    }

    @Test
    fun `subscribe returns SID and unsubscribe removes it`() {
        val subscription = manager.subscribe(
            callbackHeader = "<http://192.168.1.44:1400/event>",
            servicePath = "/upnp/event/AVTransport"
        )

        assertTrue(subscription.sid.startsWith("uuid:"))
        assertTrue(manager.unsubscribe(subscription.sid))
        assertFalse(manager.unsubscribe(subscription.sid))
    }
}
