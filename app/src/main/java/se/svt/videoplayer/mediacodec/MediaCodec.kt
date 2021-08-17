package se.svt.videoplayer.mediacodec

import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import se.svt.videoplayer.Result
import se.svt.videoplayer.andThen
import se.svt.videoplayer.audiotrack.writeAllBlockingWithResult
import se.svt.videoplayer.format.Format
import se.svt.videoplayer.mapErr
import se.svt.videoplayer.okOrElse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.TimeUnit

sealed class Error {
    data class CodecException(val exception: MediaCodec.CodecException) : Error()
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
            Result.Error(Error.CodecException(e))
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
        val result = inputBufferIndicesChannel.trySend(Result.Error(Error.CodecException(e)))
        if (result.isFailure)
            Log.e(MediaCodec::class.java.simpleName, "onError trySend failed: $result")
    }

    final override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        Log.e(MediaCodec::class.java.simpleName, "onOutputFormatChanged: $format")
    }
}

@ExperimentalCoroutinesApi
fun MediaCodec.videoInputBufferIndicesChannel() = InputBufferIndicesChannel(this, Channel<Result<Int, Error>>(capacity = BUFFERED).apply {
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
fun MediaCodec.audioInputBufferIndicesChannel(
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
