package com.phairplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HostAddressPolicyTest — proves the address rules for a URL the receiver fetches.
 *
 * WHY: the receiver fetches the media URL itself — the DLNA renderer and the AirPlay URL
 * mode alike. A peer that points the TV at the cloud metadata service of the router could
 * read the credentials in the answer. These tests pin down every rule of the policy,
 * including the notations that hide an address from a naive check.
 */
class HostAddressPolicyTest {

    @Test
    fun `accepts a media server in the local network`() {
        assertNull(HostAddressPolicy.check("http://192.168.1.5:8080/movie.mp4"))
        assertNull(HostAddressPolicy.check("http://10.0.0.7/movie.mp4"))
        assertNull(HostAddressPolicy.check("http://172.16.3.9/movie.mp4"))
        assertNull(HostAddressPolicy.check("rtsp://192.168.1.5:554/live"))
    }

    @Test
    fun `accepts a host name`() {
        assertNull(HostAddressPolicy.check("http://nas.local/movie.mp4"))
        assertNull(HostAddressPolicy.check("http://host/video.mp4"))
    }

    @Test
    fun `accepts a file URI because it carries no address`() {
        assertNull(HostAddressPolicy.check("file:///storage/emulated/0/movie.mp4"))
    }

    @Test
    fun `refuses the cloud metadata address of the router`() {
        val reason = HostAddressPolicy.check("http://169.254.169.254/latest/meta-data/")
        assertNotNull(reason)
        assertTrue(reason!!.contains("link-local"))
    }

    @Test
    fun `refuses the other metadata addresses`() {
        assertNotNull(HostAddressPolicy.check("http://100.100.100.200/latest/meta-data/"))
        assertNotNull(HostAddressPolicy.check("http://192.0.0.192/latest/meta-data/"))
        assertNotNull(HostAddressPolicy.check("http://[fd00:ec2::254]/latest/meta-data/"))
    }

    @Test
    fun `refuses a metadata host name`() {
        assertNotNull(HostAddressPolicy.check("http://metadata.google.internal/computeMetadata/v1/"))
        assertNotNull(HostAddressPolicy.check("http://instance-data/latest/meta-data/"))
    }

    @Test
    fun `refuses the loopback address of the renderer itself`() {
        assertNotNull(HostAddressPolicy.check("http://127.0.0.1:8080/movie.mp4"))
        assertNotNull(HostAddressPolicy.check("http://[::1]:8080/movie.mp4"))
        assertNotNull(HostAddressPolicy.check("http://[::ffff:127.0.0.1]/movie.mp4"))
    }

    @Test
    fun `refuses the reserved and the broadcast ranges`() {
        assertNotNull(HostAddressPolicy.check("http://0.0.0.0/movie.mp4"))
        assertNotNull(HostAddressPolicy.check("http://255.255.255.255/movie.mp4"))
        assertNotNull(HostAddressPolicy.check("http://239.1.2.3/movie.mp4"))
    }

    @Test
    fun `refuses an address in a non standard notation`() {
        // The decimal form of 127.0.0.1.
        assertNotNull(HostAddressPolicy.check("http://2130706433/movie.mp4"))
        // The octal form of 127.0.0.1.
        assertNotNull(HostAddressPolicy.check("http://0177.0.0.1/movie.mp4"))
        // The hexadecimal form of 127.0.0.1.
        assertNotNull(HostAddressPolicy.check("http://0x7f000001/movie.mp4"))
    }

    @Test
    fun `refuses a user info part that hides the real host`() {
        assertNotNull(HostAddressPolicy.check("http://expected@169.254.169.254/latest/"))
    }

    @Test
    fun `reports the reason for the refusal`() {
        assertEquals(
            "the address uses a non-standard notation",
            HostAddressPolicy.check("http://2130706433/movie.mp4")
        )
        assertEquals(
            "the URI carries a user info part",
            HostAddressPolicy.check("http://a@b/movie.mp4")
        )
    }
}
