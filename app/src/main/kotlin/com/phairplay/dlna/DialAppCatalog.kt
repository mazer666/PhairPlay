package com.phairplay.dlna

/**
 * DialAppCatalog — the DIAL documents (app list and app info) of the renderer.
 *
 * WHAT: DIAL stands for "Discovery And Launch" and is the second half of the
 * Samsung Smart View discovery. The TV first finds the renderer with the SSDP
 * search `urn:samsung.com:service:MultiScreenService:1` (see [SsdpResponder]).
 * It then reads the `Application-Url` header of the device description, which
 * points at `/dial`, and asks for two documents:
 *
 * - `GET /dial/` — the list of the apps the device offers,
 * - `GET /dial/<AppId>` — the details of a single app.
 *
 * WHY: a Samsung TV that finds the renderer but gets no DIAL answer opens an
 * empty app list, and the user cannot pick PhairPlay in it.
 *
 * WHY THE ANSWERS ARE STATIC: the renderer offers exactly one app — PhairPlay
 * itself. The only value that comes from a request is the app id, and it is
 * merely compared with the known id. It never becomes a file name, never becomes
 * part of a URL and never reaches the XML document. A request can therefore
 * neither read a file, nor reach another host, nor inject XML.
 *
 * Example:
 *   val catalog = DialAppCatalog()
 *   catalog.appsListXml()             // the app list
 *   catalog.appInfoXml("PhairPlay")   // the details of the app, null for an unknown id
 */
class DialAppCatalog {

    /**
     * Returns the DIAL app list.
     *
     * WHY the list holds one entry: the renderer is the app. Samsung shows the
     * entries of this list in the app picker of the Smart View screen.
     */
    fun appsListXml(): String =
        """
            <?xml version="1.0" encoding="UTF-8" ?>
            <service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="$DIAL_VERSION">
              <apps>
                <app name="$APP_NAME" id="$APP_ID" />
              </apps>
            </service>
        """.trimIndent()

    /**
     * Returns the DIAL app info of the app [appId].
     *
     * WHY the state is always `stopped`: the DIAL state describes the media
     * session of the app itself. PhairPlay does not run such a session — the
     * media is pushed to the renderer by a DLNA control point — so the renderer
     * never owns a running DIAL session.
     *
     * @param appId the id of the app, for example `PhairPlay`.
     * @return the document, or null when the id is not the app of this renderer.
     *   An unknown id has to be answered with 404, not with an empty document.
     */
    fun appInfoXml(appId: String): String? {
        if (!isAllowedAppId(appId) || appId != APP_ID) return null
        return """
            <?xml version="1.0" encoding="UTF-8" ?>
            <service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="$DIAL_VERSION">
              <name>$APP_NAME</name>
              <options allowStop="true" allowPause="true" allowResume="true" allowSeek="true"/>
              <state>$APP_STATE</state>
              <version>$APP_VERSION</version>
            </service>
        """.trimIndent()
    }

    companion object {
        /** The path the DIAL documents live under. */
        const val BASE_PATH = "/dial"

        /** The id of the app this renderer offers. The id is case sensitive. */
        const val APP_ID = "PhairPlay"

        /** The name of the app this renderer offers. */
        const val APP_NAME = "PhairPlay"

        /** The version of the DIAL protocol this renderer speaks. */
        const val DIAL_VERSION = "2.2"

        /** The version of the app this renderer offers. */
        const val APP_VERSION = "1.0.0"

        /** The state of the DIAL app session — see [appInfoXml]. */
        const val APP_STATE = "stopped"

        /** The longest app id the renderer accepts. */
        const val MAX_APP_ID_LENGTH = 64

        /**
         * Returns true when [appId] is a syntactically allowed app id.
         *
         * WHY the check: the id comes from the request path. Only letters, digits,
         * dash, underscore and dot are accepted, so a path segment like `../..`
         * or `a/b` never reaches the comparison. The id is never used as a path
         * or as a file name — the check only keeps the request honest.
         */
        fun isAllowedAppId(appId: String): Boolean =
            appId.length in 1..MAX_APP_ID_LENGTH && appId.all { isAllowedCharacter(it) }

        /** Returns true when [character] may appear in an app id. */
        private fun isAllowedCharacter(character: Char): Boolean =
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '_' ||
                character == '.'
    }
}
