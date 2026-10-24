package com.phairplay.util

/**
 * LogRedactionMode — controls how much identifying data [LogRedactor] removes from a log message.
 *
 * WHY: PhairPlay promises "no telemetry, local network only". The logs never leave the device,
 * but users paste log excerpts into public issue trackers, and a log line that carries the
 * household's IP addresses or a signed media URL is personal data that then lives in a public
 * thread. The mode makes the decision explicit and switchable instead of implicit.
 *
 * HOW: [Logger.setRedactionMode] changes the mode at runtime. The default is [REDACT] in every
 * build — a debug build and a release build log the same, reduced data, so that what a
 * contributor sees locally is what a user can safely paste into an issue.
 *
 * Example:
 *   Logger.setRedactionMode(LogRedactionMode.OFF)   // local debugging session only
 */
enum class LogRedactionMode {

    /**
     * No reduction: the message is logged exactly as it was written.
     *
     * Use only for a local debugging session — the raw values contain the IP addresses and
     * identifiers of the devices in the user's network.
     */
    OFF,

    /**
     * Identifying network data is replaced by a placeholder (the default).
     *
     * See [LogRedactor] for exactly what is replaced and what is not.
     */
    REDACT
}
