package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SoapXmlTest — envelope parsing must accept only real SOAP requests and build the exact response
 * and fault shapes control points expect.
 *
 * WHY: `actionFromHeader` and `parseAction` are the two gates between untrusted network bytes and a
 * typed action call; getting either loose turns them into an injection surface.
 */
class SoapXmlTest {

    private val avt = UpnpService.AV_TRANSPORT

    private fun envelope(action: String, inner: String = "", xmlns: String? = avt.serviceType) =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$xmlns\">$inner</u:$action></s:Body></s:Envelope>"

    @Test
    fun `actionFromHeader accepts a quoted header`() {
        assertEquals(avt.serviceType to "Play", SoapXml.actionFromHeader("\"${avt.serviceType}#Play\""))
    }

    @Test
    fun `actionFromHeader accepts an unquoted header`() {
        assertEquals(avt.serviceType to "Play", SoapXml.actionFromHeader("${avt.serviceType}#Play"))
    }

    @Test
    fun `actionFromHeader is null without a hash`() {
        assertNull(SoapXml.actionFromHeader(avt.serviceType))
    }

    @Test
    fun `actionFromHeader is null when the hash is the first character`() {
        assertNull(SoapXml.actionFromHeader("#Play"))
    }

    @Test
    fun `actionFromHeader is null when the hash is the last character`() {
        assertNull(SoapXml.actionFromHeader("${avt.serviceType}#"))
    }

    @Test
    fun `actionFromHeader is null for a blank or missing header`() {
        assertNull(SoapXml.actionFromHeader(null))
        assertNull(SoapXml.actionFromHeader(""))
    }

    @Test
    fun `parseAction reads a prefixed action and its prefixed arguments`() {
        val action = SoapXml.parseAction(
            envelope("Play", "<u:InstanceID>0</u:InstanceID><u:Speed>1</u:Speed>")
        )!!
        assertEquals("Play", action.name)
        assertEquals("0", action.args["InstanceID"])
        assertEquals("1", action.args["Speed"])
    }

    @Test
    fun `parseAction is null for a non-SOAP root element`() {
        assertNull(SoapXml.parseAction("<NotAnEnvelope><Body/></NotAnEnvelope>"))
    }

    @Test
    fun `parseAction is null when the root local name is Envelope but the namespace is wrong`() {
        val wrongNs = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"urn:not-soap\">" +
            "<s:Body><u:Play xmlns:u=\"${avt.serviceType}\"/></s:Body></s:Envelope>"
        assertNull(SoapXml.parseAction(wrongNs))
    }

    @Test
    fun `response wraps out arguments in the action-specific response element`() {
        val xml = SoapXml.response(avt.serviceType, "Play", mapOf("Note" to "a<b"))
        assertTrue(xml.contains("<u:PlayResponse xmlns:u=\"${avt.serviceType}\">"))
        assertTrue(xml.contains("<Note>a&lt;b</Note>"))
        assertTrue(xml.contains("</u:PlayResponse>"))
    }

    @Test
    fun `fault carries the UPnPError code and escaped description`() {
        val xml = SoapXml.fault(701, "no <media>")
        assertTrue(xml.contains("<errorCode>701</errorCode>"))
        assertTrue(xml.contains("<errorDescription>no &lt;media&gt;</errorDescription>"))
        assertTrue(xml.contains("<faultcode>s:Client</faultcode>"))
    }
}
