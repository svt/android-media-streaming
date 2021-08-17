package se.svt.videoplayer.mediacodec

import android.media.AudioTrack
import android.media.AudioTrack.WRITE_BLOCKING
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import se.svt.videoplayer.Result
import se.svt.videoplayer.andThen
import se.svt.videoplayer.format.Format
import se.svt.videoplayer.okOrElse
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.TimeUnit

sealed class Error {
    data class CodecException(val exception: MediaCodec.CodecException) : Error()
    data class NullInputBuffer(val index: Int) : Error()
}

class VideoInputBufferIndicesChannel(
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
        }
    }
}

class AudioInputBufferIndicesChannel(
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

@ExperimentalCoroutinesApi
fun MediaCodec.videoInputBufferIndicesChannel() = VideoInputBufferIndicesChannel(this, Channel<Result<Int, Error>>(capacity = BUFFERED).apply {
    setCallback(object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            val result = trySend(Result.Success(index))
            if (result.isFailure)
                Log.e(MediaCodec::class.java.simpleName, "onInputBufferAvailable trySend failed: $result")
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) = releaseOutputBuffer(index, TimeUnit.MICROSECONDS.toNanos(info.presentationTimeUs))

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            val result = trySend(Result.Error(Error.CodecException(e)))
            if (result.isFailure)
                Log.e(MediaCodec::class.java.simpleName, "onError trySend failed: $result")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.e(MediaCodec::class.java.simpleName, "onOutputFormatChanged: $format")
        }
    })
})

@ExperimentalCoroutinesApi
fun MediaCodec.audioInputBufferIndicesChannel(
    audioTrack: AudioTrack
) = AudioInputBufferIndicesChannel(this, Channel<Result<Int, Error>>(capacity = BUFFERED).apply {
    setCallback(object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            val result = trySend(Result.Success(index))
            if (result.isFailure)
                Log.e(MediaCodec::class.java.simpleName, "onInputBufferAvailable trySend failed: $result")
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            Log.e("MediaCodec", "audio onOutputBufferAvailable")
            codec.getOutputBuffer(index)?.let {
                val write = audioTrack.write(it, it.remaining(), WRITE_BLOCKING)
                Log.e("AudioTrack", "write = $write")
            }
            releaseOutputBuffer(index, TimeUnit.MICROSECONDS.toNanos(info.presentationTimeUs))
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            val result = trySend(Result.Error(Error.CodecException(e)))
            if (result.isFailure)
                Log.e(MediaCodec::class.java.simpleName, "onError trySend failed: $result")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.e(MediaCodec::class.java.simpleName, "onOutputFormatChanged: $format")
        }
    })
})

// TODO: There are duplicates for formats that expose other flags like secure surfaces and stuff.
fun codecFromFormat(codecInfos: Array<MediaCodecInfo>, format: Format) = codecInfos.find {
    it.supportedTypes.contains(format.mimeType)
}