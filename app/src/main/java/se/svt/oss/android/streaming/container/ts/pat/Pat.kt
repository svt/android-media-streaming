package se.svt.oss.android.streaming.container.ts.pat

import io.ktor.utils.io.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.flow
import se.svt.oss.android.streaming.container.ts.Pid

data class ProgramNumber(val value: Int)

suspend fun ByteReadChannel.pat() = flow {
    try {
        while (true) {
            val programNum = readShort().toUShort()
            val programMapPid = Pid((readShort().toUShort() and 0x1FFF.toUShort()).toInt())
            emit(ProgramNumber(programNum.toInt()) to programMapPid)
        }
    } catch (e: ClosedReceiveChannelException) {}
}
