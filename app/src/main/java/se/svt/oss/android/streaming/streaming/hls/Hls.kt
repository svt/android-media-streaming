package se.svt.oss.android.streaming.streaming.hls

import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import se.svt.oss.android.streaming.Result
import se.svt.oss.android.streaming.andThen
import se.svt.oss.android.streaming.container.ts.Pid
import se.svt.oss.android.streaming.container.ts.pat.pat
import se.svt.oss.android.streaming.container.ts.pes.Pes
import se.svt.oss.android.streaming.container.ts.pes.pes
import se.svt.oss.android.streaming.container.ts.pmt.pmt
import se.svt.oss.android.streaming.container.ts.psi.psi
import se.svt.oss.android.streaming.mapErr
import se.svt.oss.android.streaming.okOr
import se.svt.oss.android.streaming.orThrow
import se.svt.oss.android.streaming.container.ts.pes.Error as PesError
import se.svt.oss.android.streaming.container.ts.psi.Error as PsiError

sealed class Error : Exception() {
    object MissingPsiTableEntry : Error()
    object MissingPatTableEntry : Error()
    data class Pes(val error: PesError) : Error()
    data class Psi(val error: PsiError) : Error()
}
/**
 * HLS specification (https://datatracker.ietf.org/doc/html/rfc8216#section-3.2) requires
 * a TS stream to only contain one single program. Each TS stream must contain a PAT and PMT.
 *
 * Because of this, find the first entries in the PAT and PMT and *cancel* the remaining parsing.
 * Otherwise we would deadlock as `collect` would be waiting indefinitely for PAT and PMT streams
 * to end before we could start parsing the other streams.
 */
fun Flow<Pair<Pid, ByteReadChannel>>.tsAsHls() = flow<Result<Pes, Error>> {
    var pmtPid: Pid? = null
    var streamPid: Pid? = null

    try {
        collect { (pid, byteReadChannel) ->
            when (pid) {
                Pid.PAT -> {
                    val (_, pidFromTableEntry) = byteReadChannel.psi()
                        .firstOrNull()
                        .okOr(Error.MissingPsiTableEntry)
                        .andThen { result ->
                            result.mapErr(Error::Psi)
                                .andThen { (_, byteArray) ->
                                    ByteReadChannel(byteArray).pat()
                                        .firstOrNull()
                                        .okOr(Error.MissingPatTableEntry)
                                }
                        }
                        .orThrow()
                    pmtPid = pidFromTableEntry
                }
                pmtPid -> {
                    val (pidFromTableEntry, _) = byteReadChannel.psi()
                        .firstOrNull()
                        .okOr(Error.MissingPsiTableEntry)
                        .andThen { result ->
                            result.mapErr(Error::Psi)
                                .andThen { (_, byteArray) ->
                                    ByteReadChannel(byteArray).pmt()
                                        .streams
                                        .firstOrNull()
                                        .okOr(Error.MissingPatTableEntry)
                                }
                        }
                        .orThrow()
                    streamPid = pidFromTableEntry
                }
                streamPid -> {
                    emit(byteReadChannel.pes().mapErr(Error::Pes))
                }
            }
        }
    } catch (e: Error) {
        emit(Result.Error(e))
    }
}
