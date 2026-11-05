package com.phairplay.util

/**
 * LogRedactor — the single place where identifying data is removed from log messages.
 *
 * WHY: PhairPlay promises "zero telemetry, local network only". Its logs never leave the device,
 * but a user who reports a problem usually pastes a log excerpt into a public issue tracker.
 * A log line that carries the household's IP addresses, a MAC address or a signed media URL
 * turns the excerpt into personal data that is then published. Removing the data centrally —
 * instead of at every single log statement — means a new log statement cannot silently
 * reintroduce the leak, and a reviewer only has to look at one file.
 *
 * WHAT IS REDACTED
 *  - **IPv4 addresses**: the host octet is replaced (`192.168.1.42` → `192.168.1.x`). The
 *    /24 prefix survives because it is shared by every device of the household and therefore
 *    identifies nobody, while "is the sender in my subnet?" is the first question asked when
 *    a LAN problem is debugged.
 *  - **MAC addresses**: the device-specific half is replaced (`aa:bb:cc:dd:ee:ff` →
 *    `aa:bb:cc:xx:xx:xx`). The vendor prefix is kept because it names the hardware family,
 *    not the device.
 *  - **UUIDs**: replaced completely (`<uuid>`). A UUID is a persistent device identifier, so
 *    keeping parts of it would not make it any less identifying.
 *  - **URLs**: the credentials and the query/fragment are replaced
 *    (`https://host/x?token=abc` → `https://host/x?<redacted>`). Signed media URLs carry access
 *    tokens, and a URL can also carry a user name and a password.
 *  - **Personal strings**: the name of the television and the name of the sender are
 *    registered with [registerPersonalString] and replaced wherever they appear. A name is
 *    free text — no pattern can recognise "Wohnzimmer TV" — so the app hands the strings it
 *    knows to the reduction, and the reduction removes them from every message. That way a
 *    new log statement cannot leak the name of the household by accident.
 *
 * WHAT IS NOT REDACTED — AND WHY THAT IS NOT A GAP IN THIS CLASS
 *  Free text that the app does not know: the title, the artist and the album of the track
 *  that is currently playing, and the body of a request the sender sent. Those must not be
 *  logged in the first place — the call sites do not log them. The reduction is the second
 *  line of defence, not the first.
 *
 * Example:
 *   LogRedactor.redact("client 192.168.1.42 connected")   // "client 192.168.1.x connected"
 *   LogRedactor.redact("mac aa:bb:cc:dd:ee:ff")            // "mac aa:bb:cc:xx:xx:xx"
 */
object LogRedactor {

    /**
     * The text that replaces a personal string.
     *
     * WHY a name of its own and not "redacted": the reader of a log excerpt should be able
     * to tell "a name was here" from "an address was here".
     */
    const val PERSONAL_PLACEHOLDER = "<name>"

    /**
     * A personal string shorter than this is not replaced.
     *
     * WHY: a two-letter name like "TV" occurs in ordinary words ("AirPlay TV session"), and
     * replacing it would mangle every message. The call sites do not log the name at all;
     * this class is the second line of defence, and it refuses to be a bad one.
     */
    private const val MIN_PERSONAL_LENGTH = 4

    /** The names of the devices of the household — the strings that must not be logged. */
    private val personalStrings = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * Removes identifying data from [message].
     *
     * @param message The raw log message.
     * @return The message with identifying data replaced by placeholders.
     */
    fun redact(message: String): String {
        if (message.isEmpty()) return message
        // The order matters: the URL pass may remove a query string that still contains an
        // address, the address passes then clean up what is left in plain text.
        var result = maskUrls(message)
        result = UUID_PATTERN.replace(result, UUID_PLACEHOLDER)
        result = MAC_PATTERN.replace(result) { match -> maskMac(match.value) }
        result = IPV4_PATTERN.replace(result) { match -> maskIpv4(match.value) }
        // The personal strings come last: a name may contain an address-shaped word.
        result = maskPersonalStrings(result)
        return result
    }

    /**
     * Registers a string that must not appear in a log message.
     *
     * WHY this exists: the name of the television and the name of the sender are free
     * text. No pattern can recognise them, so the app — which knows them — hands them to
     * the reduction, and the reduction removes them from every message that is written
     * afterwards. The call sites do not log the names either; this is the second line of
     * defence against the next developer who does.
     *
     * @param value the string to hide. A null, a blank string and a string shorter than
     *   four characters are ignored.
     */
    fun registerPersonalString(value: String?) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.length < MIN_PERSONAL_LENGTH) return
        if (personalStrings.any { it.equals(trimmed, ignoreCase = true) }) return
        personalStrings.add(trimmed)
    }

    /** Forgets every registered personal string — used when the settings change. */
    fun clearPersonalStrings() {
        personalStrings.clear()
    }

    /** Replaces every registered personal string in [text]. */
    private fun maskPersonalStrings(text: String): String {
        var result = text
        for (personal in personalStrings) {
            result = result.replace(personal, PERSONAL_PLACEHOLDER)
        }
        return result
    }

    /**
     * Returns a copy of [throwable] whose message and cause chain no longer contain
     * identifying data.
     *
     * WHY: exception messages routinely embed the value that caused them — a
     * `UnknownHostException` names its host, a `MalformedURLException` repeats the URL, a
     * `MediaPlayer` error repeats the media location. Logging the original exception would
     * therefore leak through the back door of [Logger.e].
     *
     * The copy keeps the stack trace and the cause chain, and prefixes each message with the
     * original exception class, so the failure stays diagnosable.
     *
     * @param throwable The exception to sanitise.
     * @return A sanitised copy that can be handed to Timber.
     */
    fun redactThrowable(throwable: Throwable): Throwable {
        // NOTE: the one-argument constructor is the only public one that leaves the cause
        // uninitialised, and only an uninitialised cause can be set afterwards with
        // initCause(). The four-argument constructor is protected.
        val copy = Throwable(describe(throwable))
        copy.stackTrace = throwable.stackTrace
        val cause = throwable.cause
        // A self-referencing cause (Throwable("x", this)) would otherwise recurse forever.
        if (cause != null && cause !== throwable) {
            runCatching { copy.initCause(redactThrowable(cause)) }
        }
        return copy
    }

    /** "java.net.UnknownHostException: 192.168.1.99" — class name plus the redacted message. */
    private fun describe(throwable: Throwable): String {
        val message = throwable.message
        return if (message.isNullOrEmpty()) {
            throwable.javaClass.name
        } else {
            "${throwable.javaClass.name}: ${redact(message)}"
        }
    }

    /**
     * Replaces the credentials and the query string of every absolute URL in [text].
     *
     * Only absolute URLs (with a scheme) are touched. A relative URI such as
     * `/scrub?position=12` is a protocol message, not a location, and stays readable.
     */
    private fun maskUrls(text: String): String =
        URL_PATTERN.replace(text) { match -> maskUrl(match.value) }

    private fun maskUrl(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val authorityStart = schemeEnd + 3
        // The search MUST start at authorityStart. A search from index 0 would find the slash
        // inside "://" (at schemeEnd + 1), which lies BEFORE the authority, and the substring
        // below would then be called with begin > end and throw.
        val separator = url.substring(authorityStart).indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authorityEnd = if (separator < 0) url.length else authorityStart + separator
        val authority = url.substring(authorityStart, authorityEnd)

        var result = url
        if (authority.contains('@')) {
            val host = authority.substringAfterLast('@')
            result = result.substring(0, authorityStart) + CREDENTIALS_PLACEHOLDER + "@" +
                host + result.substring(authorityEnd)
        }
        val queryStart = result.indexOf('?')
        return if (queryStart >= 0) {
            result.substring(0, queryStart) + "?" + QUERY_PLACEHOLDER
        } else {
            result
        }
    }

    /**
     * Replaces the host octet of an IPv4 address: `192.168.1.42` → `192.168.1.x`.
     *
     * Text that only looks like an address (a version string such as "1.2.3.400") is returned
     * unchanged — masking it would hide a version number without protecting anything.
     */
    private fun maskIpv4(text: String): String {
        val parts = text.split('.')
        if (parts.size != 4) return text
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return text
            if (!part.all { it.isDigit() }) return text
            if (part.toInt() > 255) return text
        }
        return parts[0] + "." + parts[1] + "." + parts[2] + "." + HOST_OCTET_PLACEHOLDER
    }

    /** Replaces the device-specific half of a MAC address: `aa:bb:cc:dd:ee:ff` → `aa:bb:cc:xx:xx:xx`. */
    private fun maskMac(text: String): String {
        val parts = text.split(':')
        if (parts.size != 6) return text
        return parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + MAC_TAIL_PLACEHOLDER
    }

    // ── Patterns ────────────────────────────────────────────────────────────────────────────

    /**
     * A dotted quad. The look-arounds keep the match from starting or ending inside a longer
     * number, so "1.2.3.4567" is not matched as "1.2.3.456".
     */
    private val IPV4_PATTERN = Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])""")

    /** Six colon-separated hex octets. */
    private val MAC_PATTERN = Regex("""(?<![\w:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![\w:])""")

    /** The canonical 8-4-4-4-12 UUID form. */
    private val UUID_PATTERN =
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")

    /** An absolute URL. Trailing punctuation that belongs to the surrounding sentence is excluded. */
    private val URL_PATTERN = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s"'<>,\)\]]+""")

    // ── Placeholders ─────────────────────────────────────────────────────────────────────────

    private const val HOST_OCTET_PLACEHOLDER = "x"
    private const val MAC_TAIL_PLACEHOLDER = "xx:xx:xx"
    private const val UUID_PLACEHOLDER = "<uuid>"
    private const val CREDENTIALS_PLACEHOLDER = "<redacted>"
    private const val QUERY_PLACEHOLDER = "<redacted>"
}
