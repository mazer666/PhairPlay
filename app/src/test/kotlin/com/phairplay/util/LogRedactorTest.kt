package com.phairplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LogRedactorTest — proves that the central log reduction removes identifying data and
 * nothing else.
 *
 * WHY: The log excerpts of a bug report end up in a public issue tracker. Every assertion
 * here is a privacy guarantee that the product promise "no telemetry, local network only"
 * depends on; a regression would silently re-expose the household's network to the public.
 */
class LogRedactorTest {

    // ── IPv4 addresses ───────────────────────────────────────────────────────────────────────

    @Test
    fun `masks the host octet of an IPv4 address`() {
        assertEquals("client 192.168.1.x connected", LogRedactor.redact("client 192.168.1.42 connected"))
    }

    @Test
    fun `masks an IPv4 address at the end of a message`() {
        assertEquals("peer 10.0.0.x", LogRedactor.redact("peer 10.0.0.7"))
    }

    @Test
    fun `masks an IPv4 address that is followed by a port`() {
        assertEquals("listening on http://192.168.1.x:7000/", LogRedactor.redact("listening on http://192.168.1.42:7000/"))
    }

    @Test
    fun `keeps the network prefix so the subnet stays debuggable`() {
        // The /24 prefix is shared by every device of the household and identifies nobody,
        // while "is the sender in my subnet?" is the first question when a LAN problem is debugged.
        assertTrue(LogRedactor.redact("from 192.168.1.42").startsWith("192.168.1."))
    }

    @Test
    fun `does not mask a number sequence that is not a valid address`() {
        // "1.2.3.400" is a version string, not an address — masking it would hide information
        // without protecting anything.
        assertEquals("version 1.2.3.400", LogRedactor.redact("version 1.2.3.400"))
    }

    @Test
    fun `does not mask a version string with three parts`() {
        assertEquals("app 1.0.0 released", LogRedactor.redact("app 1.0.0 released"))
    }

    // ── MAC addresses ────────────────────────────────────────────────────────────────────────

    @Test
    fun `masks the device specific half of a MAC address`() {
        assertEquals("device aa:bb:cc:xx:xx:xx", LogRedactor.redact("device aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `masks an upper case MAC address`() {
        assertEquals("device AA:BB:CC:xx:xx:xx", LogRedactor.redact("device AA:BB:CC:DD:EE:FF"))
    }

    // ── UUIDs ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `replaces a UUID completely`() {
        assertEquals(
            "device <uuid>",
            LogRedactor.redact("device e0ff8a27-6738-3d56-8a16-cc53aacee925")
        )
    }

    // ── URLs ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `masks the query string of an absolute URL`() {
        assertEquals(
            "play https://host/movie.mov?<redacted>",
            LogRedactor.redact("play https://host/movie.mov?token=abc123&sig=deadbeef")
        )
    }

    @Test
    fun `masks the credentials of an absolute URL`() {
        assertEquals(
            "play http://<redacted>@host/movie.mov",
            LogRedactor.redact("play http://user:secret@host/movie.mov")
        )
    }

    @Test
    fun `keeps a relative protocol URI readable`() {
        // "/scrub?position=12" is an RTSP message, not a location — it stays useful.
        assertEquals("RTSP GET /scrub?position=12", LogRedactor.redact("RTSP GET /scrub?position=12"))
    }

    @Test
    fun `masks the address inside a URL as well`() {
        assertEquals(
            "http://192.168.1.x:8080/description.xml",
            LogRedactor.redact("http://192.168.1.42:8080/description.xml")
        )
    }

    // ── Non-regression ───────────────────────────────────────────────────────────────────────

    @Test
    fun `leaves an ordinary message untouched`() {
        val message = "RTSP server listening on port 7000 — mirroring ready"
        assertEquals(message, LogRedactor.redact(message))
    }

    @Test
    fun `leaves an empty message untouched`() {
        assertEquals("", LogRedactor.redact(""))
    }

    @Test
    fun `masks several identifiers in one message`() {
        val redacted = LogRedactor.redact("peer 192.168.1.42 mac aa:bb:cc:dd:ee:ff id e0ff8a27-6738-3d56-8a16-cc53aacee925")
        assertEquals("peer 192.168.1.x mac aa:bb:cc:xx:xx:xx id <uuid>", redacted)
    }

    // ── Exceptions ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `redacts the message of an exception`() {
        val original = IllegalStateException("cannot reach host 192.168.1.99")
        val copy = LogRedactor.redactThrowable(original)
        assertTrue(copy.message!!.contains("192.168.1.x"))
        assertTrue(!copy.message!!.contains("192.168.1.99"))
    }

    @Test
    fun `keeps the stack trace of an exception`() {
        val original = IllegalStateException("boom")
        val copy = LogRedactor.redactThrowable(original)
        assertEquals(original.stackTrace.toList(), copy.stackTrace.toList())
    }

    @Test
    fun `redacts the cause chain of an exception`() {
        val cause = java.net.UnknownHostException("192.168.1.99")
        val original = IllegalStateException("setup failed", cause)
        val copy = LogRedactor.redactThrowable(original)
        val copiedCause = copy.cause!!
        assertTrue(copiedCause.message!!.contains("192.168.1.x"))
        assertTrue(!copiedCause.message!!.contains("192.168.1.99"))
    }
}
