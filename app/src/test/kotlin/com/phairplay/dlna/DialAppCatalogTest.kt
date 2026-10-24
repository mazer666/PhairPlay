package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DialAppCatalogTest — proves the DIAL documents of the renderer.
 *
 * WHY: a Samsung control point reads two documents after the SSDP discovery — the
 * app list of the device and the details of one app. The app list decides whether
 * PhairPlay appears in the app picker of the TV at all, and the app id in the path
 * decides whether the details are served.
 *
 * WHAT WE TEST:
 * - the app list carries the DIAL namespace, the version and the app,
 * - the app info carries the name, the state and the version,
 * - the transport options of the app are announced,
 * - an unknown app id has no document (the server answers with 404),
 * - the app id is case sensitive,
 * - an app id that tries to escape the path has no document.
 */
class DialAppCatalogTest {

    private val catalog = DialAppCatalog()

    @Test
    fun `the app list names the DIAL namespace and the protocol version`() {
        val xml = catalog.appsListXml()

        assertTrue(
            "the DIAL namespace is missing",
            xml.contains("xmlns=\"urn:dial-multiscreen-org:schemas:dial\"")
        )
        assertTrue("the DIAL version is missing", xml.contains("dialVer=\"2.2\""))
        assertTrue("the XML declaration is missing", xml.startsWith("<?xml version=\"1.0\""))
    }

    @Test
    fun `the app list offers the app PhairPlay`() {
        val xml = catalog.appsListXml()

        assertTrue("the app list has to carry the app", xml.contains("<apps>"))
        assertTrue("the app name is missing", xml.contains("name=\"PhairPlay\""))
        assertTrue("the app id is missing", xml.contains("id=\"PhairPlay\""))
    }

    @Test
    fun `the app info carries the name, the state and the version`() {
        val xml = catalog.appInfoXml("PhairPlay")

        assertNotNull("the app info is missing", xml)
        assertTrue("the app name is missing", xml!!.contains("<name>PhairPlay</name>"))
        assertTrue("the state is missing", xml.contains("<state>stopped</state>"))
        assertTrue("the app version is missing", xml.contains("<version>1.0.0</version>"))
        assertTrue("the DIAL version is missing", xml.contains("dialVer=\"2.2\""))
    }

    @Test
    fun `the app info announces the four transport options`() {
        val xml = catalog.appInfoXml("PhairPlay")

        assertNotNull("the app info is missing", xml)
        assertTrue("allowStop is missing", xml!!.contains("allowStop=\"true\""))
        assertTrue("allowPause is missing", xml.contains("allowPause=\"true\""))
        assertTrue("allowResume is missing", xml.contains("allowResume=\"true\""))
        assertTrue("allowSeek is missing", xml.contains("allowSeek=\"true\""))
    }

    @Test
    fun `an unknown app id has no document`() {
        assertNull(catalog.appInfoXml("Unbekannt"))
    }

    @Test
    fun `the app id is case sensitive`() {
        // The control point takes the id from the path it was given — a lower case
        // id is a different app and must not be served.
        assertNull(catalog.appInfoXml("phairplay"))
        assertNotNull(catalog.appInfoXml("PhairPlay"))
    }

    @Test
    fun `an app id that tries to escape the path has no document`() {
        assertNull(catalog.appInfoXml("../secret"))
        assertNull(catalog.appInfoXml("..%2Fsecret"))
        assertNull(catalog.appInfoXml("PhairPlay/../../etc/passwd"))
        assertNull(catalog.appInfoXml(""))
    }

    @Test
    fun `an app id with a forbidden character is refused`() {
        assertNull(catalog.appInfoXml("Phair Play"))
        assertNull(catalog.appInfoXml("PhairPlay<script>"))
        assertNull(catalog.appInfoXml("Phair\nPlay"))
    }

    @Test
    fun `only letters, digits, dash, underscore and dot belong to an app id`() {
        assertTrue(DialAppCatalog.isAllowedAppId("PhairPlay"))
        assertTrue(DialAppCatalog.isAllowedAppId("app-1_2.3"))
        assertFalse("a slash does not belong to an app id", DialAppCatalog.isAllowedAppId("a/b"))
        assertFalse("a backslash does not belong to an app id", DialAppCatalog.isAllowedAppId("a\\b"))
        assertFalse("an app id cannot be empty", DialAppCatalog.isAllowedAppId(""))
    }

    @Test
    fun `the base path of the DIAL documents is the dial directory`() {
        assertEquals("/dial", DialAppCatalog.BASE_PATH)
    }
}
