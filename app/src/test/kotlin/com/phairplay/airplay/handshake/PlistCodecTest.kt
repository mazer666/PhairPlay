package com.phairplay.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlistCodecTest — proves the binary property list codec of the AirPlay handshake.
 *
 * WHY: the handshake exchanges its setup information as a binary plist. If a value
 * does not survive the round trip, the sender and the receiver disagree about the
 * session and the stream never starts.
 *
 * Example:
 *   PlistCodec.decode(PlistCodec.encode(mapOf("name" to "PhairPlay")))
 */
class PlistCodecTest {

    @Test
    fun encodesBinaryPlistMagic() {
        val bytes = PlistCodec.encode(mapOf("name" to "PhairPlay"))
        assertEquals("bplist00", String(bytes.copyOf(8), Charsets.US_ASCII))
    }

    @Test
    fun roundTripsScalarTypes() {
        val map = mapOf(
            "name" to "PhairPlay",
            "count" to 7L,
            "enabled" to true,
            "features" to 0x1E5A7FFFF7L
        )
        val decoded = PlistCodec.decode(PlistCodec.encode(map))
        assertEquals("PhairPlay", decoded["name"])
        assertEquals(7L, decoded["count"])
        assertEquals(true, decoded["enabled"])
        assertEquals(0x1E5A7FFFF7L, decoded["features"])
    }

    @Test
    fun roundTripsBinaryData() {
        val key = ByteArray(16) { it.toByte() }
        val decoded = PlistCodec.decode(PlistCodec.encode(mapOf("ekey" to key)))
        assertTrue(decoded["ekey"] is ByteArray)
        assertArrayEquals(key, decoded["ekey"] as ByteArray)
    }

    @Test
    fun roundTripsNestedListsAndMaps() {
        val map = mapOf(
            "streams" to listOf(
                mapOf("type" to 110L, "dataPort" to 50000L)
            )
        )
        val decoded = PlistCodec.decode(PlistCodec.encode(map))
        @Suppress("UNCHECKED_CAST")
        val streams = decoded["streams"] as List<Map<String, Any?>>
        assertEquals(110L, streams[0]["type"])
        assertEquals(50000L, streams[0]["dataPort"])
    }
}
