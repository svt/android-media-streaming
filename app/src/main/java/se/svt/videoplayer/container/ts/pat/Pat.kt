package se.svt.videoplayer.container.ts.pat

import io.ktor.utils.io.*
import kotlinx.coroutines.flow.flow
import se.svt.videoplayer.container.ts.Pid
import java.io.EOFException

suspend fun ByteReadChannel.pat() = flow {
    try {
        // TODO: PATs are dangerous: They only come in one packet and not very often,
        // TODO: so if we try to wait until the packet is fully written to by close
        // TODO: well wait for so long that we stall the pipeline?
        // TODO: The correct fix here would be to close early if TS frame contains a length field
        while (this@pat.availableForRead >= 2 * 2) {
            val programNum = readShort().toUShort()
            val programMapPid = Pid((readShort().toUShort() and 0x1FFF.toUShort()).toInt())
            emit(programNum to programMapPid)
        }
    } catch (e: EOFException) {}
}
