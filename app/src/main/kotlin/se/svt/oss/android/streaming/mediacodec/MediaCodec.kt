package se.svt.oss.android.streaming.mediacodec

import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build.VERSION.SDK_INT
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import se.svt.oss.android.streaming.Result
import se.svt.oss.android.streaming.andThen
import se.svt.oss.android.streaming.audiotrack.writeAllBlockingWithResult
import se.svt.oss.android.streaming.format.Format
import se.svt.oss.android.streaming.mapErr
import se.svt.oss.android.streaming.okOrElse
import se.svt.oss.android.streaming.orThrow
import se.svt.oss.android.streaming.surface.SurfaceHolderConfiguration
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.TimeUnit
import se.svt.oss.android.streaming.audio.Arguments as AudioArguments
import se.svt.oss.android.streaming.video.Arguments as VideoArguments

sealed class Error: Exception {
    constructor() : super()
    constructor(exception: Exception) : super(exception)

    data class Codec(val exception: MediaCodec.CodecException) : Error(exception)
    data class NullInputBuffer(val index: Int) : Error()
    data class NoCodecForFormat(val format: Format) : Error()
}

class InputBufferIndicesChannel(
    private val mediaCodec: MediaCodec,
    private val channel: Channel<Result<Int, Error>>
) {
    suspend fun receive(writeCallback: suspend (ByteBuffer) -> Duration) = channel.receive().andThen { index ->
        try {
            mediaCodec.getInputBuffer(index).okOrElse { Error.NullInputBuffer(index) }
                .andThen { buffer ->
                    val start = buffer.position()
                    val presentationTime = writeCallback(buffer)
                    val size = buffer.position() - start

                    mediaCodec.queueInputBuffer(
                        index,
                        0,
                        size,
                        TimeUnit.NANOSECONDS.toMicros(presentationTime.toNanos()), // Why is there no toMicros?
                        0
                    )

                    Result.Success(Unit)
                }
        } catch (e: MediaCodec.CodecException) {
            Result.Error(Error.Codec(e))
        } catch (e: IllegalStateException) {
            Log.e("MediaCodec", "receive", e)
            Result.Success(Unit)
        }
    }
}

private abstract class Callback(private val inputBufferIndicesChannel: Channel<Result<Int, Error>>) : MediaCodec.Callback() {
    final override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        val result = inputBufferIndicesChannel.trySend(Result.Success(index))
        if (result.isFailure)
            Log.e(MediaCodec::class.java.simpleName, "onInputBufferAvailable trySend failed: $result")
    }

    final override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
        val result = inputBufferIndicesChannel.trySend(Result.Error(Error.Codec(e)))
        if (result.isFailure)
            Log.e(MediaCodec::class.java.simpleName, "onError trySend failed: $result")
    }

    final override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        Log.e(MediaCodec::class.java.simpleName, "onOutputFormatChanged: $format")
    }
}

@ExperimentalCoroutinesApi
private fun MediaCodec.videoInputBufferIndicesChannel() = InputBufferIndicesChannel(this, Channel<Result<Int, Error>>(capacity = BUFFERED).apply {
    setCallback(object : Callback(this) {
        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) = try {
            codec.releaseOutputBuffer(index, TimeUnit.MICROSECONDS.toNanos(info.presentationTimeUs))
        } catch (e: MediaCodec.CodecException) {
            Log.e(MediaCodec::class.java.simpleName, "onOutputBufferAvailable", e)
            Unit
        }
    })
})

@ExperimentalCoroutinesApi
private fun MediaCodec.audioInputBufferIndicesChannel(
    audioTrack: AudioTrack
) = InputBufferIndicesChannel(this, Channel<Result<Int, Error>>(capacity = BUFFERED).apply {
    setCallback(object : Callback(this) {
        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) = try {
            codec.getOutputBuffer(index)?.let { byteBuffer ->
                audioTrack.writeAllBlockingWithResult(byteBuffer).mapErr {
                    Log.e("MediaCodec", "writeAllBlockingWithResult = $it")
                }
            }
            releaseOutputBuffer(index, TimeUnit.MICROSECONDS.toNanos(info.presentationTimeUs))
        } catch (e: MediaCodec.CodecException) {
            Log.e(MediaCodec::class.java.simpleName, "onOutputBufferAvailable", e)
            Unit
        }
    })
})

fun mediaCodec(codecInfos: Array<MediaCodecInfo>, audioArguments: AudioArguments, audioTrack: AudioTrack) =
    mediaCodecInfoFromFormat(codecInfos, audioArguments.format).andThen { mediaCodecInfo ->
        MediaCodec.createByCodecName(mediaCodecInfo.name)
            .run {
                val bufferIndicesChannel = audioInputBufferIndicesChannel(audioTrack)

                try {
                    configure(
                        MediaFormat().apply {
                            if (SDK_INT >= 23) setFloat(
                                MediaFormat.KEY_OPERATING_RATE,
                                audioArguments.samplingFrequency.toFloat()
                            )
                            setInteger(
                                MediaFormat.KEY_SAMPLE_RATE,
                                audioArguments.samplingFrequency
                            )
                            setString(MediaFormat.KEY_MIME, Format.Aac.mimeType)
                            setInteger(MediaFormat.KEY_CHANNEL_COUNT, audioArguments.channels)
                            if (SDK_INT >= 23) setInteger(
                                MediaFormat.KEY_PRIORITY,
                                0 /* realtime */
                            )
                            audioArguments.audioSpecificConfigs.forEachIndexed { index, it ->
                                setByteBuffer("csd-$index", ByteBuffer.wrap(it))
                            }
                        },
                        null,
                        null,
                        0
                    )
                    start()
                    Result.Success(bufferIndicesChannel)
                } catch (e: MediaCodec.CodecException) {
                    Result.Error(Error.Codec(e))
                }
            }
    }

fun mediaCodec(
    codecInfos: Array<MediaCodecInfo>,
    videoMediaCodecArguments: VideoArguments,
    surfaceHolderConfiguration: SurfaceHolderConfiguration
): Result<InputBufferIndicesChannel, Error> = MediaCodec.createByCodecName(
    mediaCodecInfoFromFormat(codecInfos, videoMediaCodecArguments.format).orThrow().name
)
    .run {
        val bufferIndicesChannel = videoInputBufferIndicesChannel()
        try {
            configure(
                MediaFormat().apply {
                    setString(MediaFormat.KEY_MIME, videoMediaCodecArguments.format.mimeType)
                    setInteger(MediaFormat.KEY_WIDTH, videoMediaCodecArguments.width)
                    setInteger(MediaFormat.KEY_HEIGHT, videoMediaCodecArguments.height)
                },
                surfaceHolderConfiguration.surfaceHolder.surface,
                null,
                0
            )

            start()
            Result.Success(bufferIndicesChannel)
        } catch (e: MediaCodec.CodecException) {
            Result.Error(Error.Codec(e))
        }
    }
