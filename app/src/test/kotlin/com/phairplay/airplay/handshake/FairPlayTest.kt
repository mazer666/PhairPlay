package com.phairplay.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Verifies the FairPlay fp-setup phase-1/phase-2 reply construction for both protocol versions:
 *   - v3 (`FPLY 03 …`): screen mirroring / Safari — four mode-specific replies.
 *   - v2 (`FPLY 02 …`): Apple Music / iTunes RAOP audio — one reply, mode byte patched at offset 13.
 *
 * These are pure-logic tests (no native lib): only [FairPlay.decrypt] touches libplayfair.so.
 * The key regression guarded here is that v2 returns genuinely different captured bytes than v3 —
 * the previous "flip byte 4 on a v3 table" shortcut produced a wrong key and garbled audio.
 */
class FairPlayTest {

    private fun phase1(version: Int, mode: Int): ByteArray = ByteArray(16).also {
        it[0] = 0x46; it[1] = 0x50; it[2] = 0x4c; it[3] = 0x59   // "FPLY"
        it[4] = version.toByte()
        it[5] = 0x01; it[6] = 0x02
        it[11] = 0x04
        it[14] = mode.toByte()
    }

    private fun phase2(version: Int): ByteArray = ByteArray(164).also {
        it[0] = 0x46; it[1] = 0x50; it[2] = 0x4c; it[3] = 0x59
        it[4] = version.toByte()
        for (i in 144 until 164) it[i] = (i - 144).toByte()   // mark the echoed tail
    }

    @Test
    fun `v3 phase 1 returns the 142-byte mode-specific reply`() {
        for (mode in 0..3) {
            val res = FairPlay().setup(phase1(0x03, mode))
            assertEquals(142, res.size)
            assertEquals(0x03, res[4].toInt() and 0xFF)    // FPLY version 3
            assertEquals(mode, res[13].toInt() and 0xFF)   // mode indicator
        }
    }

    @Test
    fun `v2 phase 1 returns the v2 reply with the mode byte patched at offset 13`() {
        for (mode in 0..3) {
            val res = FairPlay().setup(phase1(0x02, mode))
            assertEquals(142, res.size)
            assertEquals(0x02, res[4].toInt() and 0xFF)    // FPLY version 2
            assertEquals(0x02, res[6].toInt() and 0xFF)    // phase-2 reply blob
            assertEquals(mode, res[13].toInt() and 0xFF)   // mode patched in
        }
    }

    @Test
    fun `v2 and v3 replies have genuinely different signature bodies`() {
        val v2 = FairPlay().setup(phase1(0x02, 2))
        val v3 = FairPlay().setup(phase1(0x03, 2))
        // Bytes past the header/mode (offset 14+) are the captured FairPlay challenge response and
        // MUST differ — proving v2 carries real v2 content, not a v3 table with a flipped version byte.
        var identical = true
        for (i in 14 until 142) if (v2[i] != v3[i]) { identical = false; break }
        assertFalse("v2 and v3 replies must differ in body", identical)
    }

    @Test
    fun `negotiated version is recorded`() {
        val fp = FairPlay()
        fp.setup(phase1(0x02, 0)); assertEquals(0x02, fp.negotiatedVersion)
        fp.setup(phase1(0x03, 0)); assertEquals(0x03, fp.negotiatedVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported fp-setup version is rejected`() {
        FairPlay().setup(phase1(0x01, 0))
    }

    @Test
    fun `phase 2 handshake returns 32 bytes header plus echoed tail for v2 and v3`() {
        for (version in intArrayOf(0x02, 0x03)) {
            val res = FairPlay().handshake(phase2(version))
            assertEquals(32, res.size)
            assertEquals(0x46, res[0].toInt() and 0xFF)    // "FPLY" header
            // The phase-2 header is the fixed `FPLY 03 01 04 00 00 00 00 14` for BOTH versions —
            // the sender wraps the audio key from this reply, and echoing 0x02 here produced a wrong
            // key for every Music.app (v2) session. Reference: shairplay fairplay_v2 fp_header.
            assertEquals(0x03, res[4].toInt() and 0xFF)
            assertEquals(0x01, res[5].toInt() and 0xFF)
            assertEquals(0x04, res[6].toInt() and 0xFF)
            assertEquals(0x14, res[11].toInt() and 0xFF)
            for (i in 0 until 20) assertEquals(i, res[12 + i].toInt() and 0xFF)  // tail echoed from req[144..)
        }
    }
}
