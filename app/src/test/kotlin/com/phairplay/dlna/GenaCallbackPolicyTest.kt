package com.phairplay.dlna

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * GenaCallbackPolicyTest — proves the SSRF guard of the event callback.
 *
 * WHY: the callback URL of a GENA subscription comes from the control point. If the
 * renderer posts events to an arbitrary URL, a hostile device on the LAN can use the
 * TV as a forwarder into the router's cloud metadata service, into a loopback port
 * of the TV or into another machine of the private network.
 *
 * Every rejection below is an attack that has to fail. The test uses the real
 * strings a hostile peer would send.
 */
class GenaCallbackPolicyTest {

    private val localHost = "192.168.1.20"

    @Test
    fun `allows a private IPv4 callback in the local subnet`() {
        assertTrue(isAllowed("http://192.168.1.44:1400/event"))
    }

    @Test
    fun `rejects the loopback address`() {
        assertFalse(isAllowed("http://127.0.0.1:1400/event"))
        assertFalse(isAllowed("http://127.1.2.3:1400/event"))
    }

    @Test
    fun `rejects the IPv6 loopback`() {
        assertFalse(isAllowed("http://[::1]:1400/event"))
    }

    @Test
    fun `rejects the link-local cloud metadata address`() {
        assertFalse(isAllowed("http://169.254.169.254/latest/meta-data/"))
        assertFalse(isAllowed("http://169.254.1.1:80/"))
    }

    @Test
    fun `rejects a public address`() {
        assertFalse(isAllowed("http://8.8.8.8:1400/event"))
        assertFalse(isAllowed("http://1.1.1.1/"))
    }

    @Test
    fun `rejects a DNS name`() {
        assertFalse(isAllowed("http://evil.example.com/event"))
        assertFalse(isAllowed("http://localhost:1400/event"))
    }

    @Test
    fun `rejects the decimal notation of an address`() {
        // 2130706433 is 127.0.0.1 written as a single number.
        assertFalse(isAllowed("http://2130706433:1400/event"))
    }

    @Test
    fun `rejects the octal notation of an address`() {
        // 0177.0.0.1 is 127.0.0.1 in octal notation.
        assertFalse(isAllowed("http://0177.0.0.1:1400/event"))
        assertFalse(isAllowed("http://010.0.0.1:1400/event"))
    }

    @Test
    fun `rejects the hexadecimal notation of an address`() {
        // 0x7f000001 is 127.0.0.1 in hexadecimal notation.
        assertFalse(isAllowed("http://0x7f000001:1400/event"))
        assertFalse(isAllowed("http://0x7f.0x0.0x0.0x1:1400/event"))
    }

    @Test
    fun `rejects an IPv4 mapped IPv6 address`() {
        assertFalse(isAllowed("http://[::ffff:192.168.1.44]:1400/event"))
    }

    @Test
    fun `rejects a multicast address`() {
        assertFalse(isAllowed("http://239.255.255.250:1900/event"))
    }

    @Test
    fun `rejects the broadcast address and the unspecified address`() {
        assertFalse(isAllowed("http://255.255.255.255/event"))
        assertFalse(isAllowed("http://0.0.0.0:1400/event"))
    }

    @Test
    fun `rejects a foreign scheme`() {
        assertFalse(isAllowed("https://192.168.1.44:1400/event"))
        assertFalse(isAllowed("file:///etc/passwd"))
        assertFalse(isAllowed("gopher://192.168.1.44:1400/event"))
    }

    @Test
    fun `rejects a user info trick that hides the real host`() {
        assertFalse(isAllowed("http://192.168.1.44@evil.example.com/event"))
    }

    @Test
    fun `rejects a private address in another subnet`() {
        assertFalse(isAllowed("http://192.168.2.44:1400/event"))
        assertFalse(isAllowed("http://10.0.0.5:1400/event"))
    }

    @Test
    fun `the literal parser rejects everything that is not a plain dotted quad`() {
        assertNull(GenaCallbackPolicy.parseIpv4Literal("192.168.1"))
        assertNull(GenaCallbackPolicy.parseIpv4Literal("192.168.1.1.1"))
        assertNull(GenaCallbackPolicy.parseIpv4Literal("192.168.1.256"))
        assertNull(GenaCallbackPolicy.parseIpv4Literal("192.168.1.x"))
        assertNull(GenaCallbackPolicy.parseIpv4Literal(" 192.168.1.1"))
        assertNull(GenaCallbackPolicy.parseIpv4Literal("192.168.1.1 "))
        assertTrue(
            GenaCallbackPolicy.parseIpv4Literal("192.168.1.1")!!.contentEquals(intArrayOf(192, 168, 1, 1))
        )
    }

    private fun isAllowed(url: String): Boolean =
        GenaCallbackPolicy.isAllowedCallback(URI(url), localHost)
}
