package se.svt.oss.android.streaming.audiotrack

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.delay
import se.svt.oss.android.streaming.Result
import se.svt.oss.android.streaming.audio.Arguments
import se.svt.oss.android.streaming.map
import java.nio.ByteBuffer

sealed class Error: Exception {
    constructor() : super()
    constructor(exception: java.lang.Exception) : super(exception)

    object InvalidOperation : Error()
    object BadValue : Error()
    object DeadObject : Error()

    object AudioTrackUninitialized : Error()
    object AudioTrackNoStaticData : Error()
    data class AudioTrackUnknownState(val state: Int) : Error()
}

fun AudioTrack.writeAllBlockingWithResult(byteBuffer: ByteBuffer): Result<Int, Error> =
    when (val size = write(byteBuffer, byteBuffer.remaining(), AudioTrack.WRITE_BLOCKING)) {
        AudioTrack.ERROR_INVALID_OPERATION -> Result.Error(Error.InvalidOperation)
        AudioTrack.ERROR_BAD_VALUE -> Result.Error(Error.BadValue)
        AudioTrack.ERROR_DEAD_OBJECT -> Result.Error(Error.DeadObject)
        else -> Result.Success(size)
    }

suspend fun audioTrack(audioMediaCodecArguments: Arguments) = AudioTrack(
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
        .setFlags(0)
        .build(),
    AudioFormat.Builder()
        .setSampleRate(audioMediaCodecArguments.samplingFrequency)
        .setChannelMask(when (audioMediaCodecArguments.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
            8 -> if (Build.VERSION.SDK_INT >= 23)
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else
                AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_SIDE_LEFT or AudioFormat.CHANNEL_OUT_SIDE_RIGHT
            else -> AudioFormat.CHANNEL_INVALID
        })
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT) // TODO
        .build(),
    audioMediaCodecArguments.samplingFrequency,
    AudioTrack.MODE_STREAM,
    AudioManager.AUDIO_SESSION_ID_GENERATE
).let {
    delay(500) // TODO: There's a race condition where AudioTrack hasn't initialized yet
    val result: Result<AudioTrack, Error> = when (val state = it.state) {
        AudioTrack.STATE_UNINITIALIZED -> Result.Error(Error.AudioTrackUninitialized)
        AudioTrack.STATE_INITIALIZED -> Result.Success(it)
        AudioTrack.STATE_NO_STATIC_DATA -> Result.Error(Error.AudioTrackNoStaticData)
        else -> Result.Error(Error.AudioTrackUnknownState(state))
    }
    result.map(AudioTrack::play)
    result
}
