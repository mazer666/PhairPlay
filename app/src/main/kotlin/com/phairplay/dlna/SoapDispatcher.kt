package com.phairplay.dlna

import com.phairplay.util.Logger

/** Per-request facts a handler may need beyond the SOAP arguments. */
data class SoapContext(val userAgent: String? = null)

/** One UPnP service's action implementation. Throw [UpnpError] for protocol-level failures. */
fun interface SoapActionHandler {
    @Throws(UpnpError::class)
    fun handle(action: String, args: Map<String, String>, context: SoapContext): Map<String, String>
}

/**
 * SoapArgs — argument validation shared by the service handlers.
 *
 * WHY: Every AVTransport/RenderingControl action carries `InstanceID`, and we only have instance 0;
 * the 718 and 402 faults must be uniform across services.
 *
 * HOW: `SoapArgs.requireInstanceZero(args)` before touching renderer state; `SoapArgs.required(args, "CurrentURI")`.
 */
object SoapArgs {
    private const val ONLY_INSTANCE = "0"

    fun requireInstanceZero(args: Map<String, String>) {
        // A blank InstanceID (e.g. "" or whitespace) is treated the same as an absent one, rather
        // than failing 718, since some control points send it that way for the only instance we have.
        val id = (args["InstanceID"] ?: ONLY_INSTANCE).trim().ifBlank { ONLY_INSTANCE }
        if (id != ONLY_INSTANCE) throw UpnpError.invalidInstanceId(id)
    }

    fun required(args: Map<String, String>, name: String): String =
        args[name] ?: throw UpnpError.invalidArgs("missing $name")
}

/**
 * SoapDispatcher — turns a SOAP control POST into a service action call and a SOAP reply.
 *
 * WHY: All three services share the envelope handling and fault mapping; the services themselves stay
 * pure "action in, arguments out" so they are trivially unit-tested.
 *
 * HOW: `dispatcher.dispatch(UpnpService.AV_TRANSPORT, soapActionHeader, body, SoapContext(ua))` → [Result]
 * whose `status` is 200 for success and 500 for a fault, `xml` being the envelope to send.
 */
class SoapDispatcher(private val handlers: Map<UpnpService, SoapActionHandler>) {

    /** Outcome of a dispatch: 200 with the action response, or 500 with a SOAP fault. */
    data class Result(val status: Int, val xml: String)

    fun dispatch(
        service: UpnpService,
        soapActionHeader: String?,
        body: String,
        context: SoapContext = SoapContext()
    ): Result {
        val parsed = SoapXml.parseAction(body)
            ?: return fault(UpnpError.invalidArgs("unreadable SOAP body"))
        val actionName = parsed.name
        val loggedName = truncated(actionName)
        val header = SoapXml.actionFromHeader(soapActionHeader)
        if (header != null) {
            val (headerServiceType, headerAction) = header
            if (headerAction != actionName) {
                return fault(
                    UpnpError.invalidAction(
                        "SOAPACTION '${truncated(headerAction)}' does not match body '$loggedName'"
                    )
                )
            }
            // The SOAPACTION header names a serviceType too; if it resolves to a *different* known
            // service than the one this body was posted to, the request is inconsistent — reject it
            // rather than let a handler silently run under a mismatched service identity.
            val headerService = UpnpService.fromServiceType(headerServiceType)
            if (headerService != null && headerService != service) {
                return fault(
                    UpnpError.invalidAction(
                        "SOAPACTION service '${truncated(headerServiceType)}' does not match '${service.serviceType}'"
                    )
                )
            }
        }
        val handler = handlers[service] ?: return fault(UpnpError.invalidAction(loggedName))
        return try {
            val out = handler.handle(actionName, parsed.args, context)
            Logger.d("SOAP ${service.pathName}.$loggedName ok")
            Result(200, SoapXml.response(service.serviceType, actionName, out))
        } catch (e: UpnpError) {
            Logger.w("SOAP ${service.pathName}.$loggedName → ${e.code} ${truncated(e.description)}")
            fault(e)
        } catch (e: Throwable) {
            // Throwable (not Exception): a hostile body can drive recursive parsing/handling deep
            // enough to trigger a StackOverflowError, which must still produce a clean 501 fault
            // rather than kill the connection thread.
            Logger.e("SOAP ${service.pathName}.$loggedName failed", e)
            fault(UpnpError.actionFailed(truncated(e.message ?: "unexpected error")))
        }
    }

    private fun fault(error: UpnpError) = Result(500, SoapXml.fault(error.code, error.description))

    /** Caps an attacker-controlled string before it lands in a fault description or a log line. */
    private fun truncated(text: String): String = text.take(MAX_LOGGED_NAME)

    companion object {
        private const val MAX_LOGGED_NAME = 64
    }
}
