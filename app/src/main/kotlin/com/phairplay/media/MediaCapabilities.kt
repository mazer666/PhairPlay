package com.phairplay.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import com.phairplay.util.Logger

private const val ENCODING_E_AC3_JOC_COMPAT = 18

/**
 * Device media capability snapshot shared by receiver protocols.
 *
 * The protocol layers should advertise only capabilities present here. This keeps optional
 * codecs such as HEVC, VP9, AV1, AC-3, and E-AC-3 hardware-gated instead of hardcoded.
 */
data class DeviceMediaCapabilities(
    val video: Set<VideoCodec>,
    val audio: Set<AudioCodec>,
    val passthrough: Set<PassthroughCodec>
) {
    fun supports(codec: VideoCodec): Boolean = codec in video
    fun supports(codec: AudioCodec): Boolean = codec in audio
    fun supports(codec: PassthroughCodec): Boolean = codec in passthrough

    val supportsCastBaseline: Boolean
        get() = supports(VideoCodec.H264) &&
            supports(VideoCodec.VP8) &&
            supports(AudioCodec.AAC) &&
            supports(AudioCodec.MP3) &&
            supports(AudioCodec.LPCM)

    val supportsMiracastBaseline: Boolean
        get() = supports(VideoCodec.H264) && supports(AudioCodec.LPCM)

    companion object {
        val EMPTY = DeviceMediaCapabilities(emptySet(), emptySet(), emptySet())
    }
}

/** Video codecs that PhairPlay can advertise when Android exposes a decoder. */
enum class VideoCodec(val mimeType: String) {
    H264("video/avc"),
    HEVC("video/hevc"),
    VP8("video/x-vnd.on2.vp8"),
    VP9("video/x-vnd.on2.vp9"),
    AV1("video/av01")
}

/** Audio codecs that are decoded by MediaCodec or handled directly by AudioTrack. */
enum class AudioCodec(val mimeType: String?) {
    AAC("audio/mp4a-latm"),
    MP3("audio/mpeg"),
    OPUS("audio/opus"),
    FLAC("audio/flac"),
    ALAC("audio/alac"),
    LPCM(null)
}

/** HDMI/TV audio formats that can be passed through to a capable output device. */
enum class PassthroughCodec(val encoding: Int) {
    AC3(AudioFormat.ENCODING_AC3),
    E_AC3(AudioFormat.ENCODING_E_AC3),
    E_AC3_JOC(ENCODING_E_AC3_JOC_COMPAT)
}

/**
 * Android MediaCodec/AudioTrack-backed detector with a pure mapping function for tests.
 */
object MediaCapabilities {

    fun detect(context: Context): DeviceMediaCapabilities {
        val decoderMimeTypes = runCatching { decoderMimeTypes() }
            .onFailure { Logger.w("Could not query MediaCodec decoder list: ${it.message}") }
            .getOrDefault(emptySet())
        val outputEncodings = runCatching { outputEncodings(context) }
            .onFailure { Logger.w("Could not query audio output encodings: ${it.message}") }
            .getOrDefault(emptySet())
        return from(decoderMimeTypes, outputEncodings)
    }

    internal fun from(
        decoderMimeTypes: Set<String>,
        outputEncodings: Set<Int>
    ): DeviceMediaCapabilities {
        val normalizedMimes = decoderMimeTypes.mapTo(mutableSetOf()) { it.lowercase() }
        val video = VideoCodec.entries
            .filterTo(mutableSetOf()) { it.mimeType.lowercase() in normalizedMimes }
        val audio = AudioCodec.entries
            .filterTo(mutableSetOf()) { codec ->
                codec == AudioCodec.LPCM ||
                    (codec.mimeType?.lowercase()?.let { it in normalizedMimes } == true)
            }
        val passthrough = PassthroughCodec.entries
            .filterTo(mutableSetOf()) { it.encoding in outputEncodings }

        return DeviceMediaCapabilities(video = video, audio = audio, passthrough = passthrough)
    }

    private fun decoderMimeTypes(): Set<String> {
        return MediaCodecList(MediaCodecList.ALL_CODECS)
            .codecInfos
            .asSequence()
            .filterNot { it.isEncoder }
            .flatMap { it.supportedTypes.asSequence() }
            .map { it.lowercase() }
            .toSet()
    }

    private fun outputEncodings(context: Context): Set<Int> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptySet()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptySet()
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .asSequence()
            .flatMap { device: AudioDeviceInfo -> device.encodings.asSequence() }
            .toSet()
    }

}
