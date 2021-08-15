package se.svt.videoplayer.streaming.hls.m3u.master

import android.net.Uri
import io.ktor.utils.io.*
import se.svt.videoplayer.Result
import se.svt.videoplayer.collect
import se.svt.videoplayer.map
import se.svt.videoplayer.streaming.hls.m3u.BasicTag
import se.svt.videoplayer.streaming.hls.m3u.urlRegex

data class AlternateRendition(
    val type: Type,
    val uri: Uri,
    val groupId: String,
    val language: String?,
    val name: String,
    val default: Boolean,
    val autoSelect: Boolean,
    val channels: Int?,
)

data class Entry(
    val uri: Uri,
    val bandwidth: Int?,
    val averageBandwidth: Int?,
    val codecs: List<String>?,
    val resolution: Pair<Int, Int>?,
)

data class Playlist(
    val independentSegments: Boolean,
    val alternateRenditions: List<AlternateRendition>,
    val entries: List<Entry>,
)

sealed class Error {
    object MissingHeader : Error()
    data class KeyValues(val input: String): Error()
}

enum class Type(val string: String) {
    AUDIO("AUDIO"), VIDEO("VIDEO"), SUBTITLES("SUBTITLES"), CLOSED_CAPTIONS("CLOSED-CAPTIONS")
}

private object PlaylistTag {
    val extXMediaRegex = Regex("""#EXT-X-MEDIA:(.+)$""")
    val extXStreamInfRegex = Regex("""#EXT-X-STREAM-INF:(.+)$""")
    val extXIFrameStreamInfRegex = Regex("""#EXT-X-I-FRAME-STREAM-INF:(.+)$""")

    // Matches KEY=VALUE or KEY="some \" \\\"escaped string"
    val keyValueRegex = Regex("""([A-Z-]+)=(?:([A-Za-z0-9-]+)|(?:"((?:(?:\\{2})*|(?:.*?[^\\](?:\\{2})*)))"))""")

    val resolutionRegex = Regex("([0-9]+)x([0-9]+)")
}

private fun keyValues(input: String) = sequence<Result<Pair<String, String>, Error>> {
    var startIndex = 0
    while (startIndex < input.length) {
        val result = PlaylistTag.keyValueRegex.find(input, startIndex = startIndex)
        if (result == null) {
            yield(Result.Error(Error.KeyValues(input)))
            break
        } else result.let {
            startIndex = it.range.last + 2
            yield(Result.Success(
                it.groupValues[1] to (it.groupValues[2].takeIf(String::isNotEmpty) ?: it.groupValues[3])
            ))
        }
    }
}
    .toList()
    .collect()
    .map(List<Pair<String, String>>::toMap)


/**
 * This parses an `m3u*` master playlist. To parse an `m3u*` media playlist, use `parseMediaPlaylistM3u`.
 * [baseUri] for relative URLs, use this as the base to create a [Uri].
 */
suspend fun ByteReadChannel.parseMasterPlaylistM3u(baseUri: Uri): Result<Playlist, Error> {
    return if (BasicTag.extM3u != readUTF8Line()) {
        Result.Error(Error.MissingHeader)
    } else {
        var independentSegments = false
        var bandwidth: Int? = null
        var averageBandwidth: Int? = null
        var codecs: List<String>? = null
        var resolution: Pair<Int, Int>? = null

        val alternateRenditions = mutableListOf<AlternateRendition>()
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

                entries.add(Entry(
                    uri,
                    bandwidth,
                    averageBandwidth,
                    codecs,
                    resolution
                ))

                bandwidth = null
                averageBandwidth = null
                codecs = null
            } else {
                independentSegments = line == BasicTag.extXIndependentSegments || independentSegments

                PlaylistTag.extXMediaRegex.matchEntire(line)?.let { matchResult ->
                    val keyValues = keyValues(matchResult.groupValues[1]).ok!! // TODO: Error handling

                    alternateRenditions.add(AlternateRendition(
                        Type.values().find { it.string == keyValues["TYPE"]!! }!!, // TODO: Error handling
                        keyValues["URI"]?.let(Uri::parse)!!,
                        keyValues["GROUP-ID"]!!,
                        keyValues["LANGUAGE"],
                        keyValues["NAME"]!!,
                        keyValues["DEFAULT"]?.let {
                            when (it) {
                                "YES" -> true
                                "NO" -> false
                                else -> false // TODO: Handle as error
                            }
                        } ?: false,
                        keyValues["AUTOSELECT"]?.let {
                            when (it) {
                                "YES" -> true
                                "NO" -> false
                                else -> false // TODO: Handle as error
                            }
                        } ?: false,
                        keyValues["CHANNELS"]?.let(String::toIntOrNull)
                    ))
                }

                PlaylistTag.extXStreamInfRegex.matchEntire(line)?.let { matchResult ->
                    val keyValues = keyValues(matchResult.groupValues[1]).ok!! // TODO: Handle error

                    keyValues.forEach { (key, value) ->
                        when (key) {
                            "BANDWIDTH" -> bandwidth = value.toIntOrNull()
                            "AVERAGE-BANDWIDTH" -> averageBandwidth = value.toIntOrNull()
                            "CODECS" -> codecs = value.split(',')
                            "RESOLUTION" -> resolution = PlaylistTag.resolutionRegex.matchEntire(value)!!.let { matchResult -> // TODO: Error handling
                                matchResult.groupValues[1].toIntOrNull()!! to matchResult.groupValues[2].toIntOrNull()!! // TODO
                            }
                        }
                    }
                }

                PlaylistTag.extXIFrameStreamInfRegex.matchEntire(line)?.let { matchResult ->
                    // TODO: #EXT-X-I-FRAME-STREAM-INF
                }
            }
        }
        Result.Success(Playlist(independentSegments, alternateRenditions, entries))
    }
}
