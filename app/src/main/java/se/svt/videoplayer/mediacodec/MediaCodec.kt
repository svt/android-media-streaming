package se.svt.videoplayer.mediacodec

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import se.svt.videoplayer.Result
import se.svt.videoplayer.andThen
import se.svt.videoplayer.map
import se.svt.videoplayer.okOrElse
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

sealed class Error {
    data class CodecException(val exception: MediaCodec.CodecException) : Error()
    data class NullInputBuffer(val index: Int) : Error()
}

class VideoInputBufferIndicesChannel(
    private val mediaCodec: MediaCodec,
    private val channel: Channel<Result<Int, Error>>
) {
    suspend fun <T> receive(writeCallback: suspend (ByteBuffer) -> T) = channel.receive().andThen { index ->
        mediaCodec.getInputBuffer(index).okOrElse { Error.NullInputBuffer(index) }.map { buffer ->
            var size = 0
            try {
                val start = buffer.position()
                val value = writeCallback(buffer)
                size = buffer.position() - start

                value
            } finally {
                mediaCodec.queueInputBuffer(
                    index,
                    0,
                    size,
                    0, // TODO
                    0
                )
            }
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
