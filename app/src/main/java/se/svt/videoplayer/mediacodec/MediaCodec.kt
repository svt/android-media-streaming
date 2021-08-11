package se.svt.videoplayer.mediacodec

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import se.svt.videoplayer.Result
import java.util.concurrent.TimeUnit

sealed class Error {
    data class CodecException(val exception: MediaCodec.CodecException) : Error()
}

@ExperimentalCoroutinesApi
fun MediaCodec.videoInputBufferIndicesChannel() = Channel<Result<Int, Error>>(capacity = BUFFERED).apply {
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
}
