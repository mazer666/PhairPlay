package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RaopTxtRecordTest — pins the `_raop._tcp` advertisement that decides which audio flow macOS uses.
 *
 * WHY: with an AirPlay-1-only record, Music.app ran the legacy ANNOUNCE flow with FairPlay v2 and
 * produced silence; with the AirPlay 2 keys (`ft`, `pk`, `sf`, `vv`, `txtvers`, `ch`/`sr`/`ss`)
 * it uses the fp-setup v3 + plist SETUP flow that macOS system audio already plays through.
 */
class RaopTxtRecordTest {

    private val features = "0x5A7FFFF7,0x1E"
    private val pk = "0011223344556677889900aabbccddeeff00112233445566778899aabbccddee"
    private val record = RaopTxtRecord.build(
        macHex = "AABBCCDDEEFF",
        displayName = "Living Room TV",
        features = features,
        publicKeyHex = pk
    )

    @Test
    fun `service name is MAC without colons, an at sign, then the display name`() {
        assertEquals("AABBCCDDEEFF@Living Room TV", record.serviceName)
    }

    @Test
    fun `publishes the AirPlay 2 keys that make Music_app use the modern flow`() {
        assertEquals(features, record.attributes["ft"])
        assertEquals(pk, record.attributes["pk"])
        assertEquals("1", record.attributes["txtvers"])
        assertEquals("2", record.attributes["vv"])
        assertEquals("0x4", record.attributes["sf"])
        assertEquals("2", record.attributes["ch"])
        assertEquals("44100", record.attributes["sr"])
        assertEquals("16", record.attributes["ss"])
    }

    @Test
    fun `uses the reference encryption and codec lists`() {
        assertEquals("0,3,5", record.attributes["et"])
        assertEquals("0,1,2,3", record.attributes["cn"])
    }

    @Test
    fun `contains every key a RAOP sender requires`() {
        val required = listOf(
            "txtvers", "ch", "cn", "da", "et", "ft", "md", "pk", "sf", "sr", "ss", "sv", "tp",
            "vn", "vs", "vv", "am"
        )
        required.forEach { key ->
            assertTrue("missing TXT key '$key'", record.attributes.containsKey(key))
        }
    }

    @Test
    fun `uses UDP transport and RAOP version 65537`() {
        assertEquals("UDP", record.attributes["tp"])
        assertEquals("65537", record.attributes["vn"])
    }
}
