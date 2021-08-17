package se.svt.videoplayer.audiotrack

import android.media.AudioTrack
import se.svt.videoplayer.Result
import java.nio.ByteBuffer

sealed class Error {
    object InvalidOperation : Error()
    object BadValue : Error()
    object DeadObject : Error()
}

fun AudioTrack.writeAllBlockingWithResult(byteBuffer: ByteBuffer): Result<Int, Error> =
    when (val size = write(byteBuffer, byteBuffer.remaining(), AudioTrack.WRITE_BLOCKING)) {
        AudioTrack.ERROR_INVALID_OPERATION -> Result.Error(Error.InvalidOperation)
        AudioTrack.ERROR_BAD_VALUE -> Result.Error(Error.BadValue)
        AudioTrack.ERROR_DEAD_OBJECT -> Result.Error(Error.DeadObject)
        else -> Result.Success(size)
    }
