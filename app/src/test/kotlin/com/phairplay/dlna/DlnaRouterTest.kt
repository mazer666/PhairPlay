package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DlnaRouterTest — path + method → description, SCPD, SOAP, GENA or icon.
 *
 * WHY: Windows fetches the description, each SCPD and the icon, POSTs SOAP and SUBSCRIBEs; any wrong
 * status or missing EXT header and it drops the device.
 */
class DlnaRouterTest {

    private val events = EventSubscriptions(
        sender = { _, _, _, _ -> true },
        initialState = { emptyMap() },
        sidFactory = { "uuid:sid-1" }
    )
    private val router = DlnaRouter(
        description = DeviceDescription("TV", "uuid:abc", "1.0"),
        soap = SoapDispatcher(mapOf(UpnpService.AV_TRANSPORT to SoapActionHandler { _, _, _ -> emptyMap() })),
        events = events,
        icon = { byteArrayOf(1, 2, 3) },
        baseUrl = { "http://192.168.1.185:49494" }
    )

    private fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String = "") =
        HttpRequest(method, path, headers.mapKeys { it.key.lowercase() }, body.toByteArray())

    private fun body(response: HttpResponse) = String(response.body, Charsets.UTF_8)

    @Test
    fun `GET description returns the device XML with absolute URLs`() {
        val response = router.handle(request("GET", "/description.xml"))
        assertEquals(200, response.status)
        assertTrue(body(response).contains("<friendlyName>TV</friendlyName>"))
        assertTrue(body(response).contains("http://192.168.1.185:49494/control/AVTransport"))
    }

    @Test
    fun `GET each SCPD returns XML`() {
        for (service in UpnpService.entries) {
            val response = router.handle(request("GET", service.scpdPath))
            assertEquals(200, response.status)
            assertNotNull(SecureXml.parse(body(response)))
        }
    }

    @Test
    fun `POST control dispatches SOAP and adds the EXT header`() {
        val envelope = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:Play xmlns:u=\"${UpnpService.AV_TRANSPORT.serviceType}\"><InstanceID>0</InstanceID></u:Play></s:Body></s:Envelope>"
        val response = router.handle(request("POST", "/control/AVTransport",
            mapOf("SOAPACTION" to "\"${UpnpService.AV_TRANSPORT.serviceType}#Play\""), envelope))
        assertEquals(200, response.status)
        assertEquals("", response.headers["EXT"])
        assertTrue(body(response).contains("<u:PlayResponse"))
    }

    @Test
    fun `service without a registered handler faults with 401`() {
        val envelope = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:GetVolume xmlns:u=\"x\"/></s:Body></s:Envelope>"
        val response = router.handle(request("POST", "/control/RenderingControl", body = envelope))
        assertEquals(500, response.status)
        assertTrue(body(response).contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun `SUBSCRIBE and UNSUBSCRIBE reach the event registry`() {
        val response = router.handle(request("SUBSCRIBE", "/event/AVTransport",
            mapOf("CALLBACK" to "<http://192.168.1.10:2869/x>", "NT" to "upnp:event", "TIMEOUT" to "Second-1800")))
        assertEquals(200, response.status)
        assertEquals("uuid:sid-1", response.headers["SID"])
        assertEquals(200, router.handle(request("UNSUBSCRIBE", "/event/AVTransport", mapOf("SID" to "uuid:sid-1"))).status)
    }

    @Test
    fun `GET icon returns PNG bytes`() {
        val response = router.handle(request("GET", "/icon.png"))
        assertEquals(200, response.status)
        assertEquals("image/png", response.headers["CONTENT-TYPE"])
        assertEquals(3, response.body.size)
    }

    @Test
    fun `wrong method is 405 and unknown path is 404`() {
        assertEquals(405, router.handle(request("GET", "/control/AVTransport")).status)
        assertEquals(405, router.handle(request("POST", "/description.xml")).status)
        assertEquals(405, router.handle(request("GET", "/event/AVTransport")).status)
        assertEquals(404, router.handle(request("GET", "/nope")).status)
    }
}
