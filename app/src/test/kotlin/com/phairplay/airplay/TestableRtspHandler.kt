package com.phairplay.airplay

/**
 * TestableRtspHandler — Subclass of [RtspHandler] that exposes internal methods for unit testing
 * without requiring a real network socket.
 *
 * WHAT: Exposes the internal verb handlers (OPTIONS, ANNOUNCE, SETUP, RECORD, TEARDOWN,
 * PAUSE and the two photo endpoints) as public test seams and lets a test mark mirror
 * streams as active.
 *
 * WHY: [RtspHandler] is the most security-critical component of PhairPlay and its verb
 * handlers are `internal`. A test that needs a real socket to reach them would be slow
 * and flaky; the seam keeps the tests on the host JVM and lets them drive the handler
 * exactly the way a sender does.
 *
 * HOW: Create one with the callbacks the test wants to observe, call the
 * `handle...Public` methods with an [RtspRequest] and use [seedActiveStreams] to mark
 * mirror streams as active without the full FairPlay SETUP handshake.
 *
 * Example:
 *   val handler = TestableRtspHandler(
 *       onStreamingStarted = { session -> assertTrue(session.hasVideo) },
 *       onStreamingStopped = {}
 *   )
 *   handler.seedActiveStreams(96, 110)
 */
class TestableRtspHandler(
    onStreamingStarted: (SessionDescription) -> Unit,
    onStreamingStopped: () -> Unit,
    onPhotoReceived: (ByteArray, PhotoImageType) -> Unit = { _, _ -> },
    onPhotoCleared: () -> Unit = {},
    onMirrorAudioStop: () -> Unit = {},
    onMirrorVideoStop: () -> Unit = {}
) : RtspHandler(
    context = io.mockk.mockk(relaxed = true),
    videoSurfaceProvider = { null },
    onStreamingStarted = onStreamingStarted,
    onStreamingStopped = onStreamingStopped,
    onPhotoReceived = onPhotoReceived,
    onPhotoCleared = onPhotoCleared,
    onMirrorAudioStop = onMirrorAudioStop,
    onMirrorVideoStop = onMirrorVideoStop
) {
    /** Test seam: mark mirror streams active without driving the full FairPlay SETUP handshake. */
    fun seedActiveStreams(vararg types: Int) { activeStreamTypes.addAll(types.toList()) }

    fun handleOptionsPublic(req: RtspRequest) = handleOptionsInternal(req)
    fun handleAnnouncePublic(req: RtspRequest) = handleAnnounceInternal(req)
    fun handleSetupPublic(req: RtspRequest) = handleSetupInternal(req)
    fun handleRecordPublic(req: RtspRequest) = handleRecordInternal(req)
    fun handleTeardownPublic(req: RtspRequest) = handleTeardownInternal(req)
    fun handleUnknownMethodPublic(req: RtspRequest) = handleUnknownInternal(req)
    fun handlePausePublic(req: RtspRequest) = handlePauseInternal(req)
    fun handlePhotoPutPublic(req: RtspRequest) = handlePhotoPutInternal(req)
    fun handlePhotoDeletePublic(req: RtspRequest) = handlePhotoDeleteInternal(req)
}
