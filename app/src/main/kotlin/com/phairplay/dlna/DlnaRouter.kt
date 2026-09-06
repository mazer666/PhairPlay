package com.phairplay.dlna

import com.phairplay.dlna.scpd.Scpd

/**
 * DlnaRouter — maps an HTTP request path and method to description, SCPD, SOAP control, GENA or the icon.
 *
 * WHY: Keeps the URL layout in one pure, testable class so [DlnaHttpServer] is only sockets and
 * [SoapDispatcher]/[EventSubscriptions] never see HTTP paths.
 *
 * HOW: `router.handle(request)` → [HttpResponse]. Construct once per receiver start.
 */
class DlnaRouter(
    private val description: DeviceDescription,
    private val soap: SoapDispatcher,
    private val events: EventSubscriptions,
    private val icon: () -> ByteArray,
    private val baseUrl: () -> String
) {
    fun handle(request: HttpRequest): HttpResponse {
        val path = request.path
        UpnpService.fromScpdPath(path)?.let { service ->
            return onlyMethod(request, GET) { HttpResponse.xml(200, Scpd.forService(service)) }
        }
        UpnpService.fromControlPath(path)?.let { service ->
            return onlyMethod(request, POST) { control(service, request) }
        }
        UpnpService.fromEventPath(path)?.let { service ->
            return when (request.method) {
                SUBSCRIBE -> events.subscribe(
                    service, request.header("callback"), request.header("nt"), request.header("sid"), request.header("timeout")
                )
                UNSUBSCRIBE -> events.unsubscribe(service, request.header("sid"))
                else -> HttpResponse.empty(405)
            }
        }
        return when (path) {
            DlnaConstants.DESCRIPTION_PATH -> onlyMethod(request, GET) { HttpResponse.xml(200, description.xml(baseUrl())) }
            DlnaConstants.ICON_PATH -> onlyMethod(request, GET) { HttpResponse.bytes(200, "image/png", icon()) }
            else -> HttpResponse.empty(404)
        }
    }

    private fun control(service: UpnpService, request: HttpRequest): HttpResponse {
        val result = soap.dispatch(
            service, request.header("soapaction"), request.bodyText, SoapContext(userAgent = request.header("user-agent"))
        )
        // EXT is mandatory on UPnP control responses (HTTP Extension Framework acknowledgement).
        return HttpResponse.xml(result.status, result.xml, extraHeaders = mapOf("EXT" to ""))
    }

    private inline fun onlyMethod(request: HttpRequest, method: String, respond: () -> HttpResponse): HttpResponse =
        if (request.method == method) respond() else HttpResponse.empty(405)

    companion object {
        private const val GET = "GET"
        private const val POST = "POST"
        private const val SUBSCRIBE = "SUBSCRIBE"
        private const val UNSUBSCRIBE = "UNSUBSCRIBE"
    }
}
