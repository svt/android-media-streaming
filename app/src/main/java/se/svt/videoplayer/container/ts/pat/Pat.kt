package se.svt.videoplayer.container.ts.pat

import io.ktor.utils.io.*
import kotlinx.coroutines.flow.flow
import se.svt.videoplayer.container.ts.Pid
import java.io.EOFException

suspend fun ByteReadChannel.pat() = flow {
    try {
        while (true) {
            val programNum = readShort().toUShort()
            val programMapPid = Pid((readShort().toUShort() and 0x1FFF.toUShort()).toInt())
            emit(programNum to programMapPid)
        }
    } catch (e: EOFException) {}
}
