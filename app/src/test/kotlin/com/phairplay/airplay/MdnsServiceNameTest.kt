package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MdnsServiceNameTest — Unit tests for the mDNS service name sanitizer.
 *
 * WHY: A name the platform's mDNS daemon rejects makes [android.net.nsd.NsdManager]
 * throw inside the registration path, which takes the AirPlay receiver down and hides the
 * device from every AirPlay picker. The sanitizer is the guard against that, so it needs
 * to handle hostile input (control characters, over-long names, names that are nothing
 * but whitespace).
 *
 * Test naming convention: test_[methodName]_[scenario]_[expectedResult]
 */
class MdnsServiceNameTest {

    /**
     * Test: A normal name is kept unchanged.
     */
    @Test
    fun `normal name is unchanged`() {
        assertEquals(
            "Living Room TV",
            MdnsServiceName.sanitize("Living Room TV", "PhairPlay")
        )
    }

    /**
     * Test: Surrounding whitespace is removed.
     */
    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            "Living Room TV",
            MdnsServiceName.sanitize("  Living Room TV  ", "PhairPlay")
        )
    }

    /**
     * Test: Control characters are stripped.
     *
     * WHY: A newline or a null byte inside an mDNS instance name is rejected by the
     * daemon and — worse — would end up in log output and in the TXT records.
     */
    @Test
    fun `control characters are removed`() {
        val withControls = "Living" + Char(0) + "Room" + Char(31)

        assertEquals("LivingRoom", MdnsServiceName.sanitize(withControls, "PhairPlay"))
    }

    /**
     * Test: An over-long name is capped at the DNS-SD limit.
     */
    @Test
    fun `long name is truncated to the limit`() {
        val long = "A".repeat(200)

        val result = MdnsServiceName.sanitize(long, "PhairPlay")

        assertEquals(MdnsServiceName.MAX_LENGTH, result.length)
    }

    /**
     * Test: A name that is only whitespace falls back.
     */
    @Test
    fun `blank name falls back`() {
        assertEquals("PhairPlay", MdnsServiceName.sanitize("   ", "PhairPlay"))
    }

    /**
     * Test: An empty name falls back.
     */
    @Test
    fun `empty name falls back`() {
        assertEquals("PhairPlay", MdnsServiceName.sanitize("", "PhairPlay"))
    }

    /**
     * Test: The fallback is used when the name contains only control characters.
     */
    @Test
    fun `name with only control characters falls back`() {
        val onlyControls = Char(0).toString() + Char(7).toString()

        assertEquals("PhairPlay", MdnsServiceName.sanitize(onlyControls, "PhairPlay"))
    }

    /**
     * Test: The fallback itself is capped, so it can never break the registration either.
     */
    @Test
    fun `over-long fallback is truncated too`() {
        val result = MdnsServiceName.sanitize("", "B".repeat(100))

        assertEquals(MdnsServiceName.MAX_LENGTH, result.length)
    }

    /**
     * Test: The limit is the documented 63 characters.
     */
    @Test
    fun `maximum length is 63`() {
        assertEquals(63, MdnsServiceName.MAX_LENGTH)
    }

    /**
     * Test: The advertised AirPlay name is never the bare device name.
     *
     * WHY this is the regression test of issue #23: on Android 14 the television
     * registers its own `_androidtvremote2._tcp` service under the device name, and
     * the mDNS daemon checks the instance name across all service types. A receiver
     * that advertises `_airplay._tcp` under the bare device name is rejected with a
     * `NameConflictException` and never appears in any AirPlay picker.
     */
    @Test
    fun `airplay name is never the bare device name`() {
        val deviceName = "XGIMI MoGo 3 Pro"

        val result = MdnsServiceName.airPlayInstanceName(deviceName)

        assertFalse(
            "The advertised name must differ from the device name, but was: $result",
            result == deviceName
        )
        assertTrue(
            "The device name must stay readable in the advertised name, but was: $result",
            result.startsWith(deviceName)
        )
    }

    /**
     * Test: The advertised AirPlay name carries the suffix that makes it unique.
     */
    @Test
    fun `airplay name carries the suffix`() {
        assertEquals(
            "Living Room TV (AirPlay)",
            MdnsServiceName.airPlayInstanceName("Living Room TV")
        )
    }

    /**
     * Test: Every further attempt gets its own name.
     *
     * WHY: a name that is already taken on the network stays taken. Without a new
     * name the retry would collide again and the receiver would stay invisible.
     */
    @Test
    fun `each attempt gets its own name`() {
        val first = MdnsServiceName.airPlayInstanceName("Living Room TV", attempt = 0)
        val second = MdnsServiceName.airPlayInstanceName("Living Room TV", attempt = 1)
        val third = MdnsServiceName.airPlayInstanceName("Living Room TV", attempt = 2)

        assertTrue("The attempts must differ: $first / $second / $third",
            first != second && second != third && first != third)
        assertEquals("Living Room TV (AirPlay 2)", second)
        assertEquals("Living Room TV (AirPlay 3)", third)
    }

    /**
     * Test: The advertised name stays inside the DNS-SD limit, even with a long
     * device name.
     */
    @Test
    fun `airplay name with a long device name stays inside the limit`() {
        val result = MdnsServiceName.airPlayInstanceName("A".repeat(200))

        assertEquals(MdnsServiceName.MAX_LENGTH, result.length)
        assertTrue(
            "The suffix must survive the truncation, but was: $result",
            result.endsWith(MdnsServiceName.AIRPLAY_SUFFIX)
        )
    }

    /**
     * Test: The RAOP name keeps the format that the AirPlay protocol requires.
     */
    @Test
    fun `raop name keeps the required format`() {
        val result = MdnsServiceName.raopInstanceName("AABBCCDDEEFF", "Living Room TV")

        assertTrue(
            "The RAOP name must start with the MAC and an @, but was: $result",
            result.startsWith("AABBCCDDEEFF@")
        )
        assertTrue(
            "The RAOP name must carry the device name, but was: $result",
            result.contains("Living Room TV")
        )
    }

    /**
     * Test: The RAOP name stays inside the DNS-SD limit.
     *
     * WHY: the MAC prefix eats 13 of the 63 characters. Without this rule a long
     * device name would produce a label that the daemon rejects — and the receiver
     * would stay invisible for the same reason as in issue #23.
     */
    @Test
    fun `raop name with a long device name stays inside the limit`() {
        val result = MdnsServiceName.raopInstanceName("AABBCCDDEEFF", "A".repeat(200))

        assertEquals(MdnsServiceName.MAX_LENGTH, result.length)
    }

    /**
     * Test: The RAOP name carries the same device name as the AirPlay name, so that
     * both entries of the receiver show the same thing in the picker of the sender.
     */
    @Test
    fun `raop name carries the same device part as the airplay name`() {
        val airPlay = MdnsServiceName.airPlayInstanceName("Living Room TV")
        val raop = MdnsServiceName.raopInstanceName("AABBCCDDEEFF", "Living Room TV")

        assertEquals(airPlay, raop.substringAfter("@"))
    }
}
