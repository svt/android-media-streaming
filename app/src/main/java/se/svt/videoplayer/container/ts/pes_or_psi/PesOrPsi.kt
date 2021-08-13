package se.svt.videoplayer.container.ts.pes_or_psi

import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import se.svt.videoplayer.container.ts.Pid
import se.svt.videoplayer.container.ts.Packet as TsPacket

/*enum class KnownPid(val value: Int) {
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
}*/

// TODO: Remove
data class Packet(val data: List<ByteArray>)

/**
 * Concatenates the TS packages so that each PES package can be read continuously.
 */
fun Flow<TsPacket>.pesOrPsi() = flow {
    val accumulators = mutableMapOf<Pid, ByteChannel>()

    collect { packet ->
        if (packet.payloadUnitStartIndicator) {
            accumulators.remove(packet.pid)?.close()

            accumulators[packet.pid] = ByteChannel().apply {
                writeFully(packet.data)

                emit(packet.pid to this)
            }
        } else {
            accumulators[packet.pid]?.writeFully(packet.data)
        }
    }

    accumulators.values.forEach(ByteChannel::close)
}
