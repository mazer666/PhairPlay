package com.phairplay.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FetchableUrlPolicyTest — proves which URLs the receiver is willing to fetch itself.
 *
 * WHY: in the AirPlay URL mode the sender names the address and the television fetches
 * it. Without a rule, a single `POST /play` from any device in the network turns the
 * receiver into a request proxy for the home network and into a file reader for
 * everything the app's own UID may read. These tests pin down the rule that closes it:
 * only `http` and `https` may be fetched, the URL must be clean and bounded, and the
 * host must pass the shared address policy.
 *
 * WHAT WE TEST:
 * - `http` and `https` are allowed, in any case
 * - every other scheme is refused: `file`, `content`, `rtsp`, `ftp`
 * - an empty URL, a URL without a scheme and a URL with a control character are refused
 * - a URL that is too long is refused
 * - the loopback of the receiver and the cloud metadata service of the router are refused
 * - a user info part that hides the real host is refused
 * - a media server in the local network and a public address stay allowed
 */
class FetchableUrlPolicyTest {

    @Test
    fun `allows an http url`() {
        assertTrue(FetchableUrlPolicy.isFetchable("http://192.168.1.5:8080/movie.mp4"))
        assertTrue(FetchableUrlPolicy.isFetchable("http://nas.local/movie.mp4"))
    }

    @Test
    fun `allows an https url`() {
        assertTrue(FetchableUrlPolicy.isFetchable("https://192.168.1.5/movie.mp4"))
        assertTrue(FetchableUrlPolicy.isFetchable("https://cdn.example.com/movie.mp4"))
    }

    @Test
    fun `allows the scheme in any case`() {
        assertTrue(FetchableUrlPolicy.isFetchable("HTTP://192.168.1.5/movie.mp4"))
        assertTrue(FetchableUrlPolicy.isFetchable("HtTpS://192.168.1.5/movie.mp4"))
    }

    @Test
    fun `refuses a file url because it would read the local file system`() {
        assertFalse(FetchableUrlPolicy.isFetchable("file:///data/data/com.phairplay/shared_prefs/phairplay_prefs.xml"))
        assertFalse(FetchableUrlPolicy.isFetchable("FILE:///storage/emulated/0/movie.mp4"))
    }

    @Test
    fun `refuses a content url`() {
        assertFalse(FetchableUrlPolicy.isFetchable("content://media/external/video/1"))
    }

    @Test
    fun `refuses an rtsp url`() {
        assertFalse(FetchableUrlPolicy.isFetchable("rtsp://192.168.1.5:554/live"))
    }

    @Test
    fun `refuses an ftp url`() {
        assertFalse(FetchableUrlPolicy.isFetchable("ftp://192.168.1.5/movie.mp4"))
    }

    @Test
    fun `refuses an empty url`() {
        assertFalse(FetchableUrlPolicy.isFetchable(null))
        assertFalse(FetchableUrlPolicy.isFetchable(""))
        assertFalse(FetchableUrlPolicy.isFetchable("   "))
    }

    @Test
    fun `refuses a url without a scheme`() {
        assertFalse(FetchableUrlPolicy.isFetchable("192.168.1.5/movie.mp4"))
        assertFalse(FetchableUrlPolicy.isFetchable("//192.168.1.5/movie.mp4"))
        assertFalse(FetchableUrlPolicy.isFetchable("/movie.mp4"))
    }

    @Test
    fun `refuses a url with a control character`() {
        assertFalse(FetchableUrlPolicy.isFetchable("http://192.168.1.5/movie.mp4\r\nX-Injected: 1"))
        assertFalse(FetchableUrlPolicy.isFetchable("http://192.168.1.5/mo\u0000vie.mp4"))
        assertFalse(FetchableUrlPolicy.isFetchable("http://192.168.1.5/movie.mp4\u001B"))
    }

    @Test
    fun `refuses a url that is too long`() {
        val long = "http://192.168.1.5/" + "a".repeat(FetchableUrlPolicy.MAX_URL_LENGTH)
        assertFalse(FetchableUrlPolicy.isFetchable(long))
    }

    @Test
    fun `refuses the loopback of the receiver`() {
        assertFalse(FetchableUrlPolicy.isFetchable("http://127.0.0.1:8080/movie.mp4"))
        assertFalse(FetchableUrlPolicy.isFetchable("http://[::1]:8080/movie.mp4"))
    }

    @Test
    fun `refuses the cloud metadata service of the router`() {
        assertFalse(FetchableUrlPolicy.isFetchable("http://169.254.169.254/latest/meta-data/"))
        assertFalse(FetchableUrlPolicy.isFetchable("http://[fd00:ec2::254]/latest/meta-data/"))
        assertFalse(FetchableUrlPolicy.isFetchable("http://metadata.google.internal/computeMetadata/v1/"))
    }

    @Test
    fun `refuses a user info part that hides the real host`() {
        assertFalse(FetchableUrlPolicy.isFetchable("http://expected@169.254.169.254/latest/"))
    }

    @Test
    fun `refuses an address in a non standard notation`() {
        assertFalse(FetchableUrlPolicy.isFetchable("http://2130706433/movie.mp4"))
        assertFalse(FetchableUrlPolicy.isFetchable("http://0177.0.0.1/movie.mp4"))
    }

    @Test
    fun `allows a public address because the peer decides which medium the owner sees`() {
        assertTrue(FetchableUrlPolicy.isFetchable("http://93.184.216.34/movie.mp4"))
    }
}
