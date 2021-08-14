package se.svt.videoplayer.container.ts.pes

import io.ktor.utils.io.*
import se.svt.videoplayer.Result
import java.io.ByteArrayInputStream
import java.io.DataInputStream

sealed class Error {
    object ExpectedStartCodePrefix : Error()
}

data class Pes(
    val streamId: Int,
    val dataAlignmentIndicator: Boolean,
    val dts90Khz: Long?, // TODO: Can these be Duration?
    val pts90Khz: Long?, // TODO: Can these be Duration?
    val byteReadChannel: ByteReadChannel
)

suspend fun ByteReadChannel.pes(): Result<Pes, Error> = readInt().let { startCode ->
    if (((startCode shr 8)) != 1) {
        Result.Error(Error.ExpectedStartCodePrefix)
    } else {
        val streamId = startCode and 0xFF
        val length = readShort().toUShort()
        val flags = readShort().toUShort()

        val dataAlignmentIndicator = (flags and 0x400.toUShort()) != 0.toUShort()
        val ptsFlag = (flags and 0x80.toUShort()) != 0.toUShort()
        val dtsFlag = (flags and 0x40.toUShort()) != 0.toUShort()

        val headerLength = readByte().toUByte()

        val (pts, dts) = if (ptsFlag) {
            // TODO: Read directly from channel to avoid this extra allocation and the warnings
            val extendedHeader = DataInputStream(ByteArrayInputStream(ByteArray(headerLength.toInt()).apply {
                readFully(this)
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
            discard(headerLength.toLong())
            null to null
        }

        Result.Success(Pes(streamId, dataAlignmentIndicator, dts, pts, this))
    }
}
