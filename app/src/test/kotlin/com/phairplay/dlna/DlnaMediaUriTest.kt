package com.phairplay.dlna

import com.phairplay.util.FetchableUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DlnaMediaUriTest — proves the validation of the media URI.
 *
 * WHY: `SetAVTransportURI` must accept everything a real control point sends — a
 * renderer that refuses ordinary URIs is useless. At the same time the value is
 * untrusted network input and has to be bounded, and the renderer fetches the URI
 * itself, so the scheme and the address behind it have to be checked as well.
 *
 * WHAT WE TEST:
 * - `http` and `https` are accepted, in any case, with a private or a public address,
 * - every other scheme is refused: `file`, `content`, `smb`, `ftp`, `rtsp`, `udp`,
 *   `pnm` — a `file://` URI would turn the television into a reader for the app's
 *   own sandbox,
 * - an empty URI, a URI without a scheme, a URI with a control character and an
 *   oversized URI are refused,
 * - the loopback of the receiver and the cloud metadata service of the router are refused.
 *
 * The rule itself lives in `com.phairplay.util.FetchableUrlPolicy` and is shared with
 * the AirPlay stack — this test proves that the DLNA stack really uses it.
 */
class DlnaMediaUriTest {

    @Test
    fun `accepts the URIs a control point sends`() {
        listOf(
            "http://192.168.1.5:8080/video.mp4",
            "https://192.168.1.5/video.mp4",
            "HTTP://192.168.1.5/video.mp4",
            "http://nas.local/video.mp4"
        ).forEach { uri ->
            assertTrue("must be accepted: $uri", DlnaMediaUri.isAcceptable(uri))
        }
    }

    @Test
    fun `accepts a public address because the control point decides the medium`() {
        assertTrue(DlnaMediaUri.isAcceptable("http://93.184.216.34/movie.mp4"))
    }

    @Test
    fun `rejects a file URI because it would read the local file system`() {
        // The attack: a control point names a file inside the app sandbox and the
        // renderer hands the path to the media stack, which reads it.
        assertFalse(
            DlnaMediaUri.isAcceptable("file:///data/data/com.phairplay.googletv/shared_prefs/phairplay_prefs.xml")
        )
        assertFalse(DlnaMediaUri.isAcceptable("file:///storage/emulated/0/movie.mp4"))
        assertFalse(DlnaMediaUri.isAcceptable("FILE:///etc/passwd"))
    }

    @Test
    fun `rejects the local and streaming schemes a control point may emit`() {
        listOf(
            "smb://192.168.1.5/share/movie.mkv",
            "ftp://192.168.1.5/movie.mp4",
            "rtsp://192.168.1.5:554/live",
            "udp://192.168.1.5:1234",
            "pnm://192.168.1.5/stream",
            "content://media/external/video/1",
            "mms://192.168.1.5/movie.mp4"
        ).forEach { uri ->
            assertFalse("must be refused: $uri", DlnaMediaUri.isAcceptable(uri))
        }
    }

    @Test
    fun `rejects an empty URI`() {
        assertFalse(DlnaMediaUri.isAcceptable(""))
        assertFalse(DlnaMediaUri.isAcceptable("   "))
        assertFalse(DlnaMediaUri.isAcceptable(null))
    }

    @Test
    fun `rejects a URI without a scheme`() {
        assertFalse(DlnaMediaUri.isAcceptable("192.168.1.5/video.mp4"))
        assertFalse(DlnaMediaUri.isAcceptable("/local/video.mp4"))
    }

    @Test
    fun `rejects a URI with control characters`() {
        assertFalse(DlnaMediaUri.isAcceptable("http://192.168.1.5/a\nb.mp4"))
        // The NUL character is written as an escape on purpose: a raw NUL byte in the
        // source would make the file look like a binary file to git.
        assertFalse(DlnaMediaUri.isAcceptable("http://192.168.1.5/a\u0000b.mp4"))
    }

    @Test
    fun `rejects an oversized URI`() {
        val uri = "http://192.168.1.5/" + "a".repeat(FetchableUrlPolicy.MAX_URL_LENGTH)

        assertFalse(DlnaMediaUri.isAcceptable(uri))
    }

    @Test
    fun `rejects an unknown scheme`() {
        assertFalse(DlnaMediaUri.isAcceptable("javascript:alert(1)"))
        assertFalse(DlnaMediaUri.isAcceptable("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun `rejects a URI that points at the cloud metadata service`() {
        // The renderer fetches the URI itself, so this address must never be accepted.
        assertFalse(DlnaMediaUri.isAcceptable("http://169.254.169.254/latest/meta-data/"))
        assertFalse(DlnaMediaUri.isAcceptable("http://127.0.0.1:8080/movie.mp4"))
    }

    @Test
    fun `rejects a URI that hides the real host behind a user info part`() {
        assertFalse(DlnaMediaUri.isAcceptable("http://expected@169.254.169.254/latest/"))
    }

    @Test
    fun `the accepted result carries the trimmed URI`() {
        val result = DlnaMediaUri.validate("  http://192.168.1.5/video.mp4  ")

        assertTrue(result is DlnaMediaUri.Result.Accepted)
        assertEquals(
            "http://192.168.1.5/video.mp4",
            (result as DlnaMediaUri.Result.Accepted).uri
        )
    }

    @Test
    fun `the rejection reason never contains the URI itself`() {
        val result = DlnaMediaUri.validate("http://expected@169.254.169.254/latest/meta-data/")

        assertTrue(result is DlnaMediaUri.Result.Rejected)
        val reason = (result as DlnaMediaUri.Result.Rejected).reason
        assertFalse("the reason must not carry the address", reason.contains("169.254"))
        assertFalse("the reason must not carry the address", reason.contains("expected"))
    }
}
