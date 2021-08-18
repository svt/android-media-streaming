package se.svt.oss.android.streaming.streaming.hls.m3u.media

import android.net.Uri
import io.ktor.utils.io.*
import se.svt.oss.android.streaming.Result
import se.svt.oss.android.streaming.duration.durationOfDoubleSeconds
import se.svt.oss.android.streaming.streaming.hls.m3u.BasicTag
import se.svt.oss.android.streaming.streaming.hls.m3u.urlRegex
import java.lang.Exception
import java.time.Duration

data class Entry(
    val uri: Uri,
    val duration: Duration,
    val title: String?,
    val sequence: Int
)

data class Playlist(
    val version: Int?,
    val type: Type?,
    val targetDuration: Duration?,
    val entries: List<Entry>
)

sealed class Error : Exception() {
    object MissingHeader : Error()
    data class InvalidPlaylistType(val type: String) : Error()
    object MissingDuration : Error()
    object MissingTitle : Error()
}

enum class Type {
    VOD,
    EVENT
}

private object SegmentTag {
    val extInfRegex = Regex("""#EXTINF:([0-9.]+)(?:,(.*$))""")
}

private object PlaylistTag {
    val extXPlaylistTypeRegex = Regex("""#EXT-X-PLAYLIST-TYPE:((?:EVENT)|(?:VOD))""")
    val extXTargetdurationRegex = Regex("""#EXT-X-TARGETDURATION:([0-9.]+)""")
    val extXMediaSequenceRegex = Regex("""#EXT-X-MEDIA-SEQUENCE:([0-9]+)""")
}


/**
 * This parses an `m3u*` media playlist. To parse an `m3u*` master playlist, use `parseMasterPlaylistM3u`.
 * [baseUri] for relative URLs, use this as the base to create a [Uri].
 */
suspend fun ByteReadChannel.parseMediaPlaylistM3u(baseUri: Uri): Result<Playlist, Error> {
    return if (BasicTag.extM3u != readUTF8Line()) {
        Result.Error(Error.MissingHeader)
    } else {
        var version: Int? = null
        var playlistType: Type? = null
        var targetDuration: Duration? = null

        var duration: Duration? = null
        var title: String? = null
        var sequence = 0

        val entries = mutableListOf<Entry>()
        while (true) {
            val line = readUTF8Line()
            if (line == null)
                break
            else if (line.isEmpty())
                continue
            else if (!line.startsWith('#')) {
                val uri = if (urlRegex.matchEntire(line) != null) {
                    Uri.parse(line)
                } else baseUri.buildUpon().appendEncodedPath(line).build()

                when {
                    duration == null -> return Result.Error(Error.MissingDuration)
                    title == null -> return Result.Error(Error.MissingTitle)
                    else -> {
                        entries.add(Entry(uri, duration, title, sequence))

                        duration = null
                        title = null

                        sequence += 1
                    }
                }
            } else {
                version =
                    BasicTag.extXVersionRegex.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
                        ?: version
                SegmentTag.extInfRegex.matchEntire(line)?.groupValues?.let { groupValues ->
                    duration = groupValues[1].toDoubleOrNull()?.let(::durationOfDoubleSeconds)
                    title = groupValues[2]
                }
                playlistType =
                    PlaylistTag.extXPlaylistTypeRegex.matchEntire(line)?.groupValues?.get(1)?.let {
                        when (it) {
                            "VOD" -> Type.VOD
                            "EVENT" -> Type.EVENT
                            else -> return Result.Error(Error.InvalidPlaylistType(it))
                        }
                    } ?: playlistType
                targetDuration =
                    PlaylistTag.extXTargetdurationRegex.matchEntire(line)?.groupValues?.get(1)?.toDoubleOrNull()
                        ?.let(::durationOfDoubleSeconds) ?: targetDuration
                sequence =
                    PlaylistTag.extXMediaSequenceRegex.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
                        ?: sequence

            }
        }
        Result.Success(Playlist(version, playlistType, targetDuration, entries))
    }
}
