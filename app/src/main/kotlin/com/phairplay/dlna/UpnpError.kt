package com.phairplay.dlna

/**
 * UpnpError — a UPnP action failure carrying the standard error code and description.
 *
 * WHY: Service handlers throw this; [SoapDispatcher] turns it into the SOAP `UPnPError` fault the
 * control point expects. Keeping codes in one place stops ad-hoc numbers spreading through handlers.
 *
 * HOW: `throw UpnpError.transitionNotAvailable("no media")`.
 */
class UpnpError(val code: Int, val description: String) : Exception("UPnP error $code: $description") {
    companion object {
        const val INVALID_ACTION = 401
        const val INVALID_ARGS = 402
        const val ACTION_FAILED = 501
        const val TRANSITION_NOT_AVAILABLE = 701
        const val ILLEGAL_MIME_TYPE = 714
        const val RESOURCE_NOT_FOUND = 716
        const val INVALID_INSTANCE_ID = 718
        const val INVALID_CONNECTION_REFERENCE = 706

        fun invalidAction(name: String) = UpnpError(INVALID_ACTION, "Invalid Action: $name")
        fun invalidArgs(detail: String) = UpnpError(INVALID_ARGS, "Invalid Args: $detail")
        fun actionFailed(detail: String) = UpnpError(ACTION_FAILED, "Action Failed: $detail")
        fun transitionNotAvailable(detail: String) =
            UpnpError(TRANSITION_NOT_AVAILABLE, "Transition not available: $detail")
        fun illegalMimeType(detail: String) = UpnpError(ILLEGAL_MIME_TYPE, "Illegal MIME-Type: $detail")
        fun resourceNotFound(detail: String) = UpnpError(RESOURCE_NOT_FOUND, "Resource not found: $detail")
        fun invalidInstanceId(id: String) = UpnpError(INVALID_INSTANCE_ID, "Invalid InstanceID: $id")
        fun invalidConnectionReference(id: String) =
            UpnpError(INVALID_CONNECTION_REFERENCE, "Invalid connection reference: $id")
    }
}
