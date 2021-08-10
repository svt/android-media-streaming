package se.svt.videoplayer.container.ts.psi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.svt.videoplayer.Result
import se.svt.videoplayer.collect
import se.svt.videoplayer.container.ts.pes_or_psi.Packet
import se.svt.videoplayer.map
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.SequenceInputStream
import java.util.*

sealed class Error {
    object ExpectedSectionSyntaxIndicator : Error()
}

data class TableId(val value: Int)

fun Flow<Packet>.psi() = map {
    val dataInputStream =
        DataInputStream(SequenceInputStream(Collections.enumeration(it.data.map(::ByteArrayInputStream))))
    val startPosition = dataInputStream.readUnsignedByte()
    dataInputStream.skipBytes(startPosition)

    sequence<Result<Pair<TableId, ByteArray>, Error>> {
        try {
            while (true) {
                val tableId = TableId(dataInputStream.readUnsignedByte())
                val flags = dataInputStream.readUnsignedShort()
                yield(if ((flags and 0xC000) != 0x8000)
                    Result.Error(Error.ExpectedSectionSyntaxIndicator)
                else {
                    val length = flags and 0x3FF

                    dataInputStream.skipBytes(5)

                    val dataLength = length - 5 - 4 // skipped bytes + crc32

                    val bytes = ByteArray(dataLength).apply {
                        dataInputStream.readFully(this)
                    }

                    // TODO: Use crc32 to verify the above data
                    val crc32 = dataInputStream.readInt()

                    Result.Success(tableId to bytes)
                })
            }
        } catch (e: EOFException) {}
    }
        .toList()
        .collect()
        .map(List<Pair<TableId, ByteArray>>::toMap)
}