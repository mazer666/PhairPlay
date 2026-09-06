package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SoapDispatcherTest — SOAP control requests become typed actions; failures become UPnPError faults.
 *
 * WHY: Windows treats any malformed response as "device not responding". The fault codes (401/402/501/718)
 * are also what BubbleUPnP shows the user, so they must be exact.
 */
class SoapDispatcherTest {

    private val avt = UpnpService.AV_TRANSPORT
    private val soapAction = "\"${avt.serviceType}#Play\""

    private fun envelope(action: String, inner: String = "") =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"${avt.serviceType}\">$inner</u:$action></s:Body></s:Envelope>"

    private fun dispatcher(handler: SoapActionHandler) = SoapDispatcher(mapOf(avt to handler))

    @Test
    fun `successful action returns a 200 response envelope with out arguments escaped`() {
        val result = dispatcher { action, args, _ ->
            assertEquals("Play", action)
            assertEquals("0", args["InstanceID"])
            assertEquals("1", args["Speed"])
            mapOf("Note" to "a<b&c")
        }.dispatch(avt, soapAction, envelope("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"))
        assertEquals(200, result.status)
        assertTrue(result.xml.contains("<u:PlayResponse xmlns:u=\"${avt.serviceType}\">"))
        assertTrue(result.xml.contains("<Note>a&lt;b&amp;c</Note>"))
    }

    @Test
    fun `UpnpError from the handler becomes a 500 fault with its code`() {
        val result = dispatcher { _, _, _ -> throw UpnpError.transitionNotAvailable("no media") }
            .dispatch(avt, soapAction, envelope("Play"))
        assertEquals(500, result.status)
        assertTrue(result.xml.contains("<errorCode>701</errorCode>"))
        assertTrue(result.xml.contains("<faultcode>s:Client</faultcode>"))
        assertTrue(result.xml.contains("UPnPError"))
    }

    @Test
    fun `unexpected exception becomes a 501 fault`() {
        val result = dispatcher { _, _, _ -> error("boom") }.dispatch(avt, soapAction, envelope("Play"))
        assertTrue(result.xml.contains("<errorCode>501</errorCode>"))
    }

    @Test
    fun `unreadable body is a 402 fault`() {
        val result = dispatcher { _, _, _ -> emptyMap() }.dispatch(avt, soapAction, "not xml")
        assertTrue(result.xml.contains("<errorCode>402</errorCode>"))
    }

    @Test
    fun `body with a DOCTYPE is rejected as unreadable`() {
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE s [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" +
            envelope("Play", "<InstanceID>&x;</InstanceID>").substringAfter("?>")
        val result = dispatcher { _, _, _ -> emptyMap() }.dispatch(avt, soapAction, xxe)
        assertTrue(result.xml.contains("<errorCode>402</errorCode>"))
    }

    @Test
    fun `SOAPACTION action name mismatching the body is a 401 fault`() {
        val header = "\"${avt.serviceType}#Stop\""
        val result = dispatcher { _, _, _ -> emptyMap() }.dispatch(avt, header, envelope("Play"))
        assertTrue(result.xml.contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun `SOAPACTION naming a different known service is a 401 fault`() {
        val header = "\"${UpnpService.RENDERING_CONTROL.serviceType}#Play\""
        val result = dispatcher { _, _, _ -> emptyMap() }.dispatch(avt, header, envelope("Play"))
        assertTrue(result.xml.contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun `service without a handler is a 401 fault`() {
        val result = SoapDispatcher(emptyMap()).dispatch(avt, soapAction, envelope("Play"))
        assertTrue(result.xml.contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun `user agent reaches the handler through the context`() {
        var seen: String? = null
        dispatcher { _, _, context -> seen = context.userAgent; emptyMap() }
            .dispatch(avt, soapAction, envelope("Play"), SoapContext(userAgent = "Microsoft-Windows/10.0 UPnP/1.0"))
        assertEquals("Microsoft-Windows/10.0 UPnP/1.0", seen)
    }

    @Test
    fun `SoapArgs rejects a non-zero InstanceID and missing required args`() {
        try { SoapArgs.requireInstanceZero(mapOf("InstanceID" to "1")); error("expected 718") }
        catch (e: UpnpError) { assertEquals(718, e.code) }
        SoapArgs.requireInstanceZero(emptyMap())   // absent InstanceID defaults to 0
        SoapArgs.requireInstanceZero(mapOf("InstanceID" to "   "))   // blank InstanceID defaults to 0
        try { SoapArgs.required(emptyMap(), "CurrentURI"); error("expected 402") }
        catch (e: UpnpError) { assertEquals(402, e.code) }
    }
}
