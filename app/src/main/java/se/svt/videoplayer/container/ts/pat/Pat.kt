package se.svt.videoplayer.container.ts.pat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException

fun Flow<Map<Int, ByteArray>>.pat() = mapNotNull { it[0]?.let { data ->
    DataInputStream(ByteArrayInputStream(data)).let { dataInputStream ->
        sequence {
            try {
                while (true) {
                    val programNum = dataInputStream.readUnsignedShort()
                    val programMapPid = dataInputStream.readUnsignedShort() and 0x1FFF
                    yield(programNum to programMapPid)
                }
            } catch (e: EOFException) {}
        }
            .toMap()
    }
} }
