package com.phairplay.dlna

import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.service.ProtocolState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * MediaRendererTest — the DLNA session state machine: URI → player or photo, transport states, overlays.
 *
 * WHY: This is where Windows' load/play/stop sequence, playlist advancing, remote keys and the UI
 * callbacks meet. Every transition the spec names is pinned here without MediaPlayer or sockets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRendererTest {

    private val player = FakeRendererPlayer()
    private var airPlayConnected = false
    private val protocolStates = mutableListOf<ProtocolState>()
    private val snapshots = mutableListOf<RendererSnapshot>()
    private val nowPlaying = mutableListOf<NowPlayingInfo?>()
    private val photos = mutableListOf<String>()
    private var photoCleared = 0
    private val senders = mutableListOf<String>()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)
    private val fetcher = PhotoFetcher({ url: URL ->
        object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = if (url.path.contains("missing")) 404 else 200
            override fun getContentType() = "image/jpeg"
            override fun getContentLengthLong() = -1L
            override fun getInputStream(): InputStream = ByteArrayInputStream(jpeg)
        }
    })

    private val callbacks = RendererCallbacks(
        onProtocolState = { protocolStates += it },
        onSnapshot = { snapshots += it },
        onNowPlaying = { nowPlaying += it },
        onPhoto = { _, mime -> photos += mime },
        onPhotoCleared = { photoCleared++ },
        onSenderName = { senders += it }
    )

    private fun renderer(block: suspend (MediaRenderer) -> Unit) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        block(MediaRenderer(player, fetcher, { airPlayConnected }, callbacks, this, dispatcher))
    }

    private val video = "http://192.168.1.10:2869/movie.mp4"
    private val audioDidl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item>" +
        "<dc:title>Song</dc:title><upnp:artist>Band</upnp:artist><upnp:class>object.item.audioItem</upnp:class>" +
        "<upnp:albumArtURI>http://192.168.1.10/art.jpg</upnp:albumArtURI></item></DIDL-Lite>"
    private val windows = "Microsoft-Windows/10.0 UPnP/1.0 Microsoft-DLNA DLNADOC/1.50"

    @Test
    fun `video load goes TRANSITIONING then PLAYING once prepared after Play`() = renderer { r ->
        r.load(video, "", windows)
        assertEquals(TransportState.TRANSITIONING, r.snapshot().state)
        assertEquals(listOf("stop", "load:$video:video"), player.calls)
        assertEquals(1, photoCleared)
        assertNull(nowPlaying.last())
        assertEquals("Windows PC", senders.last())
        assertEquals(ProtocolState.CONNECTED, protocolStates.last())

        r.play()
        assertEquals(TransportState.TRANSITIONING, r.snapshot().state)
        player.listener!!.onPrepared(120_000)
        assertEquals(TransportState.PLAYING, r.snapshot().state)
        assertEquals(120_000L, r.snapshot().durationMs)
        assertTrue(player.calls.contains("play"))
    }

    @Test
    fun `prepared without a Play request parks in STOPPED`() = renderer { r ->
        r.load(video, "", null)
        player.listener!!.onPrepared(1000)
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertEquals("DLNA Sender", senders.last())
    }

    @Test
    fun `pause resume and stop drive the player and the overlay`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(1000)
        r.pause()
        assertEquals(TransportState.PAUSED_PLAYBACK, r.snapshot().state)
        assertTrue(player.calls.contains("pause"))
        r.play()
        assertEquals(TransportState.PLAYING, r.snapshot().state)
        r.stop()
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertEquals(ProtocolState.ADVERTISING, protocolStates.last())
        assertEquals(0L, r.snapshot().positionMs)
    }

    @Test
    fun `play from STOPPED reloads through the player and waits for prepare`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(1000); r.stop()
        player.calls.clear()
        r.play()
        assertEquals(TransportState.TRANSITIONING, r.snapshot().state)
        assertEquals(listOf("play"), player.calls)
        player.listener!!.onPrepared(1000)
        assertEquals(TransportState.PLAYING, r.snapshot().state)
    }

    @Test
    fun `completion starts the next URI or stops`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(1000)
        r.setNext("http://x/2.mp4", "")
        player.listener!!.onCompleted()
        assertEquals("http://x/2.mp4", r.snapshot().currentUri)
        assertEquals("", r.snapshot().nextUri)
        assertEquals(TransportState.TRANSITIONING, r.snapshot().state)
        player.listener!!.onPrepared(500)
        assertEquals(TransportState.PLAYING, r.snapshot().state)
        player.listener!!.onCompleted()
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertEquals(ProtocolState.ADVERTISING, protocolStates.last())
    }

    @Test
    fun `audio load shows the now-playing card with DIDL metadata and fetched art`() = renderer { r ->
        r.load("http://192.168.1.10/song.mp3", audioDidl, "BubbleUPnP/4.0")
        assertEquals("load:http://192.168.1.10/song.mp3:audio", player.calls.last())
        val info = nowPlaying.last()!!
        assertEquals("Song", info.title)
        assertEquals("Band", info.artist)
        assertEquals("BubbleUPnP", info.senderName)
        assertEquals(jpeg.size, info.artwork!!.size)
    }

    @Test
    fun `image load fetches the photo and reports PLAYING without a player`() = renderer { r ->
        r.load("http://192.168.1.10/pic.jpg", "", "VLC/3.0")
        assertEquals(listOf("image/jpeg"), photos)
        assertEquals(TransportState.PLAYING, r.snapshot().state)
        assertEquals("VLC", senders.last())
        assertTrue(player.calls.none { it.startsWith("load") })
        r.stop()
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertTrue(photoCleared >= 1)
    }

    @Test
    fun `failed photo fetch reports an error status`() = renderer { r ->
        r.load("http://192.168.1.10/missing.jpg", "", null)
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertEquals(RendererSnapshot.STATUS_ERROR, r.snapshot().status)
    }

    @Test
    fun `player error reports ERROR_OCCURRED and clears the overlay`() = renderer { r ->
        r.load(video, "", null); r.play()
        player.listener!!.onError("what=1")
        assertEquals(TransportState.STOPPED, r.snapshot().state)
        assertEquals(RendererSnapshot.STATUS_ERROR, r.snapshot().status)
        assertEquals(ProtocolState.ADVERTISING, protocolStates.last())
    }

    @Test
    fun `unknown item is 714 and video during AirPlay is 501`() = renderer { r ->
        try { r.load("http://x/blob", "", null); fail() } catch (e: UpnpError) { assertEquals(714, e.code) }
        airPlayConnected = true
        try { r.load(video, "", null); fail() } catch (e: UpnpError) { assertEquals(501, e.code) }
        r.load("http://x/song.mp3", "", null)   // audio is allowed alongside AirPlay
        assertEquals(TransportState.TRANSITIONING, r.snapshot().state)
    }

    @Test
    fun `seek volume and mute update the snapshot and the player`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(100_000)
        r.seekTo(30_000)
        assertEquals("seek:30000", player.calls.last())
        assertEquals(30_000L, r.snapshot().positionMs)
        r.setVolume(140); r.setMute(true)
        assertEquals(100, r.snapshot().volume)
        assertTrue(r.snapshot().mute)
        assertTrue(player.calls.containsAll(listOf("volume:100", "mute:true")))
    }

    @Test
    fun `remote commands toggle seek and stop`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(100_000)
        r.remote(RemoteCommand.PLAY_PAUSE)
        assertEquals(TransportState.PAUSED_PLAYBACK, r.snapshot().state)
        r.remote(RemoteCommand.PLAY_PAUSE)
        assertEquals(TransportState.PLAYING, r.snapshot().state)
        player.position = 50_000
        r.remote(RemoteCommand.SEEK_FORWARD)
        assertEquals("seek:${50_000 + DlnaConstants.SEEK_STEP_MS}", player.calls.last())
        r.remote(RemoteCommand.SEEK_BACK)
        assertEquals("seek:${60_000 - DlnaConstants.SEEK_STEP_MS}", player.calls.last())
        r.remote(RemoteCommand.STOP)
        assertEquals(TransportState.STOPPED, r.snapshot().state)
    }

    @Test
    fun `protocol state is only reported when it changes`() = renderer { r ->
        r.load(video, "", null); r.play(); player.listener!!.onPrepared(1000); r.seekTo(10); r.setVolume(50)
        assertEquals(listOf(ProtocolState.CONNECTED), protocolStates)
    }
}
