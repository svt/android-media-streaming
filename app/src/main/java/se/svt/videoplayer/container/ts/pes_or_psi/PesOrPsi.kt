package se.svt.videoplayer.container.ts.pes_or_psi

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
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

data class Packet(val data: List<ByteArray>)

/**
 * Picks out the `PES` packages of type [pid] out of the ts [Flow] and concatenates them
 * so that the returned [Packet] contains a full `PES` packet potentially spanning multiple
 * ts packets.
 *
 * If you want to filter out several types of [pid] package types, use a `SharedFlow`.
 */
fun Flow<TsPacket>.pesOrPsi(pid: Pid) = mapNotNull { packet ->
    packet.takeIf { it.pid == pid }
}
    .chunked()
    .map(::Packet)

// TODO: Extract from this file and generalize on T
// TODO: Handle discontinuity: Scrap all data until next payloadUnitStartIndicator
private fun Flow<TsPacket>.chunked() = flow {
    var accumulator = listOf<ByteArray>()
    collect { packet ->
        accumulator = if (packet.payloadUnitStartIndicator) {
            if (accumulator.isNotEmpty())
                emit(accumulator)

            listOf(packet.data)
        } else {
            accumulator + packet.data
        }
    }
    if (accumulator.isNotEmpty())
        emit(accumulator)
}