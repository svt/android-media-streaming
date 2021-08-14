package se.svt.videoplayer.container.ts.psi

import io.ktor.utils.io.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.flow
import se.svt.videoplayer.Result

sealed class Error : Exception() {
    object ExpectedSectionSyntaxIndicator : Error()
}

data class TableId(val value: Int)

fun ByteReadChannel.psi() = flow<Result<Pair<TableId, ByteArray>, Error>> {
    val startPosition = readByte().toUByte()
    discard(startPosition.toLong())

    try {
        while (true) {
            val tableId = TableId(readByte().toUByte().toInt())
            val flags = readShort().toUShort()

            emit(if ((flags and 0xC000.toUShort()) != 0x8000.toUShort())
                Result.Error(Error.ExpectedSectionSyntaxIndicator)
            else {
                val length = (flags and 0x3FF.toUShort()).toInt()

                discard(5)

                val dataLength = length - 5 - 4 // skipped bytes + crc32

                val bytes = ByteArray(dataLength).apply { readFully(this) }

                // TODO: Use crc32 to verify the above data
                val crc32 = readInt()

                Result.Success(tableId to bytes)
            })
        }
    } catch (e: ClosedReceiveChannelException) {
    }
}
