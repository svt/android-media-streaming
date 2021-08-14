package se.svt.videoplayer.container.ts.streams

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

/**
 * Concatenates the TS packages so that each PES/PSI stream can be read continuously.
 *
 * Be careful when `collect`-ing this Flow. Since each stream is intertwined with other streams,
 * as `collect` will `suspend` until the stream has been fully read, this can cause a deadlock!
 */
fun Flow<TsPacket>.streams() = flow {
    val accumulators = mutableMapOf<Pid, ByteChannel>()

    collect { packet ->
        if (packet.payloadUnitStartIndicator) {
            accumulators.remove(packet.pid)?.close()

            val accumulator = ByteChannel(autoFlush = true).apply {
                writeFully(packet.data)
            }
            accumulators[packet.pid] = accumulator

            emit(packet.pid to accumulator)
        } else {
            accumulators[packet.pid]?.writeFully(packet.data)
        }
    }

    accumulators.values.forEach(ByteChannel::close)
}
