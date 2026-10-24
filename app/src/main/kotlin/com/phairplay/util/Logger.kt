package com.phairplay.util

import timber.log.Timber

/**
 * Logger — A thin wrapper around Timber for PhairPlay-specific logging.
 *
 * WHY: Using a wrapper instead of calling Timber directly gives us two benefits:
 * 1. We can add PhairPlay-specific behavior in one place — the log reduction of
 *    [LogRedactor] that keeps IP addresses, MAC addresses and signed URLs out of the
 *    log excerpts users paste into public issue trackers
 * 2. In tests, we can verify that specific log messages were emitted
 *
 * HOW: Use Logger.d/i/w/e throughout the codebase instead of Timber or Log directly.
 * Timber must be initialized in PhairPlayApp.onCreate() before Logger is used.
 *
 * EVERY MESSAGE PASSES THROUGH THE REDACTOR. That is the point of this class: a new log
 * statement cannot leak an address by accident, because the reduction happens after the
 * message was composed, not at the call site. The reduction can be switched off for a local
 * debugging session with [setRedactionMode].
 *
 * Example:
 *   Logger.d("RTSP connection from ${clientIp}")   // logged as "RTSP connection from 192.168.1.x"
 *   Logger.i("mDNS service registered: $serviceName")
 *   Logger.w("Audio buffer underrun — dropping frame")
 *   Logger.e("Unexpected socket error", exception)
 */
object Logger {

    /**
     * The active reduction mode.
     *
     * WHY REDACT is the default in every build: a debug build and a release build must log the
     * same, reduced data. Otherwise the data a contributor sees locally — and pastes into an
     * issue — would differ from what a user can safely share. A debugging session that needs
     * the raw values can switch the mode off explicitly for its duration.
     */
    @Volatile
    private var redactionMode: LogRedactionMode = LogRedactionMode.REDACT

    /**
     * Switches the log reduction on or off.
     *
     * @param mode [LogRedactionMode.REDACT] (the default) or [LogRedactionMode.OFF].
     */
    fun setRedactionMode(mode: LogRedactionMode) {
        redactionMode = mode
    }

    /** The currently active reduction mode. */
    fun redactionMode(): LogRedactionMode = redactionMode

    /**
     * Logs a verbose message (very detailed, for debugging only).
     * Only emitted in debug builds.
     *
     * @param message The log message.
     */
    fun v(message: String) = Timber.v(redact(message))

    /**
     * Logs a debug message (development-time information).
     * Only emitted in debug builds.
     *
     * @param message The log message.
     */
    fun d(message: String) = Timber.d(redact(message))

    /**
     * Logs an informational message (key lifecycle events, state changes).
     * Emitted in both debug and release builds (if a release logging backend is planted).
     *
     * @param message The log message.
     */
    fun i(message: String) = Timber.i(redact(message))

    /**
     * Logs a warning (something unexpected happened but we can recover).
     * Emitted in both debug and release builds.
     *
     * @param message The log message.
     */
    fun w(message: String) = Timber.w(redact(message))

    /**
     * Logs an error with an exception.
     * Emitted in both debug and release builds.
     *
     * RULE 4: Every exception must be logged. This method is the correct way to log
     * exceptions — it captures the stack trace for debugging.
     *
     * The exception is copied before it is logged: its message and the messages of its cause
     * chain routinely repeat the value that caused the failure (a host name, a media URL),
     * and the copy is sanitised by [LogRedactor.redactThrowable].
     *
     * @param message Human-readable description of what failed.
     * @param throwable The exception that caused the error.
     */
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.e(LogRedactor.redactThrowable(throwable), redact(message))
        } else {
            Timber.e(redact(message))
        }
    }

    /**
     * Applies the active reduction to [message].
     *
     * @param message The raw message.
     * @return The message as it is handed to Timber.
     */
    internal fun redact(message: String): String =
        if (redactionMode == LogRedactionMode.REDACT) LogRedactor.redact(message) else message
}
