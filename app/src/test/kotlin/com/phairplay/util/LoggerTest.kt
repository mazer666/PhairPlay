package com.phairplay.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * LoggerTest — proves that the central log reduction is actually applied to what reaches
 * Timber, and that it can be switched off.
 *
 * WHY: the reduction must be automatic. If a developer adds a log statement that mentions an
 * address, the guarantee "no address leaves the device in a log excerpt" only holds if the
 * reduction happens inside the logger and not at the call site.
 */
class LoggerTest {

    /**
     * A Timber tree that keeps the formatted messages instead of writing them to logcat.
     * The app itself never installs such a tree — this one exists for the test only.
     */
    private class CapturingTree : Timber.Tree() {
        val messages = mutableListOf<String>()
        val throwables = mutableListOf<Throwable?>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages.add(message)
            throwables.add(t)
        }
    }

    private val tree = CapturingTree()

    @Before
    fun setUp() {
        Timber.plant(tree)
        Logger.setRedactionMode(LogRedactionMode.REDACT)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        Logger.setRedactionMode(LogRedactionMode.REDACT)
    }

    @Test
    fun `redacts an address in a log message`() {
        Logger.i("New client connected: 192.168.1.42")
        assertEquals(listOf("New client connected: 192.168.1.x"), tree.messages)
    }

    @Test
    fun `redacts an address in a warning`() {
        Logger.w("Mirror data connection from 10.0.0.7")
        assertTrue(tree.messages.single().contains("10.0.0.x"))
    }

    @Test
    fun `redacts an address in a debug message`() {
        Logger.d("RTSP GET /playback-info from 192.168.1.42")
        assertEquals(listOf("RTSP GET /playback-info from 192.168.1.x"), tree.messages)
    }

    @Test
    fun `redacts an address in a verbose message`() {
        Logger.v("timing probe from 192.168.1.42")
        assertEquals(listOf("timing probe from 192.168.1.x"), tree.messages)
    }

    @Test
    fun `redacts an address in an error message`() {
        Logger.e("Video decoder failed for 192.168.1.42")
        assertTrue(tree.messages.single().contains("192.168.1.x"))
    }

    @Test
    fun `redacts the message of a logged exception`() {
        Logger.e("AirPlay video: setup failed", java.net.UnknownHostException("host 192.168.1.99"))
        val loggedThrowable = tree.throwables.single()!!
        assertTrue(loggedThrowable.message!!.contains("192.168.1.x"))
        assertTrue(!loggedThrowable.message!!.contains("192.168.1.99"))
    }

    @Test
    fun `keeps the raw message when the reduction is switched off`() {
        Logger.setRedactionMode(LogRedactionMode.OFF)
        Logger.i("New client connected: 192.168.1.42")
        assertEquals(listOf("New client connected: 192.168.1.42"), tree.messages)
    }

    @Test
    fun `reports the active reduction mode`() {
        assertEquals(LogRedactionMode.REDACT, Logger.redactionMode())
        Logger.setRedactionMode(LogRedactionMode.OFF)
        assertEquals(LogRedactionMode.OFF, Logger.redactionMode())
    }
}
