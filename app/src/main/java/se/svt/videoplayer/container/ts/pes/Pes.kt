package se.svt.videoplayer.container.ts.pes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.svt.videoplayer.Result
import se.svt.videoplayer.container.ts.pes_or_psi.Packet
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.SequenceInputStream
import java.util.*

sealed class Error {
    object ExpectedStartCodePrefix : Error()
}

data class Pes(
    val streamId: Int,
    val dataAlignmentIndicator: Boolean,
    val dts90Khz: Long?, // TODO: Can these be Duration?
    val pts90Khz: Long?, // TODO: Can these be Duration?
    val data: ByteArray
)

fun Flow<Packet>.pes() = map {
    val dataInputStream =
        DataInputStream(SequenceInputStream(Collections.enumeration(it.data.map(::ByteArrayInputStream))))

    val startCode = dataInputStream.readInt()
    val result: Result<Pes, Error> = if (((startCode shr 8)) != 1) {
        Result.Error(Error.ExpectedStartCodePrefix)
    } else {
        val streamId = startCode and 0xFF
        val length = dataInputStream.readUnsignedShort()
        val flags = dataInputStream.readUnsignedShort()

        val dataAlignmentIndicator = (flags and 0x400) != 0
        val ptsFlag = (flags and 0x80) != 0
        val dtsFlag = (flags and 0x40) != 0

        val headerLength = dataInputStream.readUnsignedByte()

        val (pts, dts) = if (ptsFlag) {
            // TODO: Read directly from dataInputStream to avoid this extra allocation
            val extendedHeader = DataInputStream(ByteArrayInputStream(ByteArray(headerLength).apply {
                dataInputStream.readFully(this)
            }))

            val pts1 = (extendedHeader.readUnsignedByte() and 0xE).toLong() shl 29
            val pts2 = (extendedHeader.readUnsignedShort() and 0xFFFE).toLong() shl 14
            val pts3 = (extendedHeader.readUnsignedShort() and 0xFFFE).toLong() shr 1
            val pts90Khz = pts1 or pts2 or pts3

            pts90Khz to if (dtsFlag) {
                val dts1 = (extendedHeader.readUnsignedByte() and 0xE).toLong() shl 29
                val dts2 = (extendedHeader.readUnsignedShort() and 0xFFFE).toLong() shl 14
                val dts3 = (extendedHeader.readUnsignedShort() and 0xFFFE).toLong() shr 1
                val dts90Khz = dts1 or dts2 or dts3
                dts90Khz
            } else null
        } else {
            dataInputStream.skipBytes(headerLength)
            null to null
        }

        val available = it.data.sumOf { it.size } - 4 - 2 - 3 - headerLength
        val data = ByteArray(available).apply {
            dataInputStream.readFully(this)
        }

        Result.Success(Pes(streamId, dataAlignmentIndicator, dts, pts, data))
    }
    result
}