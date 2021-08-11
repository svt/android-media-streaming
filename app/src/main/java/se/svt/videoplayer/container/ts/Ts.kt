package se.svt.videoplayer.container.ts

import android.util.Log
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import se.svt.videoplayer.Result
import java.io.IOException

sealed class Error {
    object MissingSyncByte : Error()
    data class IO(val exception: IOException) : Error()
    //data class UnknownPid(val pid: Int) : Error()
    //data class UnexpectedTableId(val tableId: Int) : Error()
    //object ExpectedSectionSyntaxIndicator : Error()
}

// TODO: equals, hashCode conundrum
data class Packet(val data: ByteArray, val pid: Pid, val payloadUnitStartIndicator: Boolean, val discontinuityFlag: Boolean)

private const val PACKET_SIZE = 188
private const val HEADER_SIZE = 4
private const val SYNC_BYTE = 0x47

private object AdaptionFieldFlags {
    const val PAYLOAD = 0x10
    const val ADAPTION = 0x20
}

// TODO: Convert `Frame` and `Pid` into sealed class with Pat containing the Pat table
// TODO: And have a hierarchy: Frame -> (Pat or (Pes -> Mpa, Dts, Ac3 etc) or Program(tableId, Section))
// TODO: This way we can separate all the PAT parsing mumbo jumbo with mutable maps to outside this
/*private enum class Pid(val value: Int) {
    PAT(0x00),
    MPA(0x03),
    MPA_LSF(0x04),
    AAC_ADTS(0x0F),
    AAC_LATM(0x11),
    AC3(0x81),
    DTS(0x8A),
    HDMV_DTS(0x82),
    E_AC3(0x87),
    AC4(0xAC),
    H262(0x02),
    H263(0x10),
    H264(0x1B),
    H265(0x24),
    ID3(0x15),
    SPLICE_INFO(0x86),
    DVBSUBS(0x59),
}

private const val PAT = 0*/

fun tsFlow(channel: ByteReadChannel) : Flow<Result<Packet, Error>> {
    var previousContinuityCounters = mutableMapOf<Pid, Int>()

    return flow {
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

                    /*if (pidValue == PAT) {
                        when (val result = parsePat(byteArray)) {
                            is Result.Error -> emit(Result.Error<Packet, Error>(result.exception))
                            is Result.Success -> payloadReaders.putAll(result.data)
                        }
                    } else {*/
                    val result: Result<Packet, Error> = if (header shr 24 != SYNC_BYTE)
                        Result.Error(Error.MissingSyncByte)
                    else {
                        /*val pid = Pid.values().find { it.value == pidValue }
                        if (pid == null) {
                            if (payloadReaders[pidValue.toShort()] != null) {
                                TODO("Not impl")
                            } else {
                                Result.Error(Error.UnknownPid(pidValue))
                            }
                        } else
                            */Result.Success(Packet(byteArray, pid, payloadUnitStartIndicator, discontinuityFlag))
                    }

                    //Log.e("TS", "EMITTING")
                    emit(result)
                    //Log.e("TS", "EMITTED")
                    //}
                } else {
                    Log.e("SKIPPPP", "SKIPPING ${repeatedContinuityCounter} $transportError")
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // Just terminate the flow
        } catch (e: IOException) {
            Log.e("IOEXCEPTION", "TS ${e}")
            emit(Result.Error<Packet, Error>(Error.IO(e)))
        } catch (e: Throwable) {
            Log.e("THROWABLE", "THROWABLE: $e")
        }
    }
}

/*private fun parsePat(byteArray: ByteArray): Result<Sequence<Pair<Short, Unit>>, Error> {
    val inputStream = DataInputStream(byteArray.inputStream())
    val tableId = inputStream.readByte().toInt()
    val secondHeaderByte = inputStream.readByte().toInt()
    val result: Result<Sequence<Pair<Short, Unit>>, Error> = when {
        tableId != 0 -> Result.Error(Error.UnexpectedTableId(tableId))
        (secondHeaderByte and 0x80) == 0 -> Result.Error(Error.ExpectedSectionSyntaxIndicator)
        else -> {
            val programCount = inputStream.available() / 4
            Result.Success((0 until programCount).asSequence().mapNotNull {
                val programNumber = inputStream.readShort().toInt()
                val pid = inputStream.readShort() and 0x1FFF
                pid.takeIf { programNumber != 0 }?.let { pid to Unit }
            })
        }
    }
    return result
}
*/
