// SPDX-FileCopyrightText: 2021 Sveriges Television AB
//
// SPDX-License-Identifier: Apache-2.0

package se.svt.oss.android.streaming.container.ts

import android.util.Log
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.flow
import se.svt.oss.android.streaming.Result

sealed class Error {
    object MissingSyncByte : Error()
}

// TODO: equals, hashCode conundrum
data class Packet(
    val data: ByteArray,
    val pid: Pid,
    val payloadUnitStartIndicator: Boolean,
    val discontinuityFlag: Boolean
)

private const val PACKET_SIZE = 188
private const val HEADER_SIZE = 4
private const val SYNC_BYTE = 0x47

private object AdaptionFieldFlags {
    const val PAYLOAD = 0x10
    const val ADAPTION = 0x20
}

fun tsFlow(channel: ByteReadChannel) =
    mutableMapOf<Pid, Int>().let { previousContinuityCounters ->
        flow {
            try {
                while (true) {
                    val header = channel.readInt()

                    // https://en.wikipedia.org/wiki/MPEG_transport_stream#Packet
                    val transportError = (header and 0x800000) != 0
                    val payloadUnitStartIndicator = (header and 0x400000) != 0
                    val hasAdaptionField = (header and AdaptionFieldFlags.ADAPTION) != 0
                    val continuityCounter = header and 0xF

                    val adaptationFieldLength =
                        if (hasAdaptionField) channel.readByte().toUByte().toInt() else 0

                    channel.discardExact(adaptationFieldLength.toLong())

                    val packetSize =
                        PACKET_SIZE - HEADER_SIZE - adaptationFieldLength - (if (hasAdaptionField) 1 else 0)
                    val byteArray = ByteArray(packetSize).apply {
                        channel.readFully(this)
                    }

                    val pid = Pid((0x1fff00 and header) shr 8)

                    val previousContinuityCounter = previousContinuityCounters[pid]
                    val repeatedContinuityCounter = continuityCounter == previousContinuityCounter
                    previousContinuityCounters[pid] = continuityCounter
                    val discontinuityFlag = continuityCounter != ((previousContinuityCounter ?: (continuityCounter - 1)) + 1) and 0xF

                    if (!repeatedContinuityCounter && !transportError) {
                        val result: Result<Packet, Error> = if (header shr 24 != SYNC_BYTE)
                            Result.Error(Error.MissingSyncByte)
                        else
                            Result.Success(
                                Packet(
                                    byteArray,
                                    pid,
                                    payloadUnitStartIndicator,
                                    discontinuityFlag
                                )
                            )
                        emit(result)
                    } else {
                        Log.e(
                            "TS",
                            "Skipping packet due to repeatedContinuityCounter = $repeatedContinuityCounter transportError = $transportError"
                        )
                    }
                }
            } catch (e: ClosedReceiveChannelException) {}
        }
    }
