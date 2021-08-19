// SPDX-FileCopyrightText: 2021 Sveriges Television AB
//
// SPDX-License-Identifier: Apache-2.0

package se.svt.oss.android.streaming.streaming.hls.audio

import io.ktor.utils.io.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import se.svt.oss.android.streaming.Result
import se.svt.oss.android.streaming.andThen
import se.svt.oss.android.streaming.map
import java.lang.Exception
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

sealed class Version {
    data class V2(val minor: Int) : Version()
    data class V3(val minor: Int) : Version()
    data class V4(val minor: Int) : Version()
}

sealed class Error : Exception() {
    object InvalidId3Header : Error()
    object CompressedVersion2 : Error()
    data class UnsupportedVersion(val major: Int, val minor: Int) : Error()
    data class UnknownTextEncoding(val encoding: Int) : Error()
}

sealed class Frame {
    abstract val owner: String

    data class Priv(override val owner: String, val data: ByteArray) : Frame()
}

object FrameFlags {
    object V3 {
        const val COMPRESSED = 0x0080
        const val ENCRYPTED = 0x0040
        const val GROUP_IDENTIFIER = 0x0020
    }

    object V4 {
        const val COMPRESSED = 0x0008
        const val ENCRYPTED = 0x0004
        const val GROUP_IDENTIFIER = 0x0040
        const val UNSYNCHRONIZED = 0x0002
        const val HAS_DATA_LENGTH = 0x0001
    }
}

object TextEncoding {
    const val ISO_8859_1 = 0
    const val UTF_16 = 1
    const val UTF_16BE = 2
    const val UTF_8 = 3
}

suspend fun ByteReadChannel.id3(): Result<List<Frame>, Error> {
    val firstByte = readByte().toUByte().toInt()
    val secondByte = readByte().toUByte().toInt()
    val thirdByte = readByte().toUByte().toInt()

    return if (!arrayOf(firstByte, secondByte, thirdByte).contentEquals(arrayOf(0x49, 0x44, 0x33))) {
        Result.Error(Error.InvalidId3Header)
    } else {
        val majorVersion = readByte().toUByte().toInt()
        val minorVersion = readByte().toUByte().toInt()
        val versionResult: Result<Version, Error> = when (majorVersion) {
            2 -> Result.Success(Version.V2(minorVersion))
            3 -> Result.Success(Version.V3(minorVersion))
            4 -> Result.Success(Version.V4(minorVersion))
            else -> Result.Error(Error.UnsupportedVersion(majorVersion, minorVersion))
        }
        val flags = readByte().toUByte().toInt()
        val size = readSynchSafeInt()

        val unsynchronized = majorVersion < 4 && (flags and 0x80) != 0

        versionResult.andThen { version ->
            val framesSizeResult: Result<Int, Error> = when (version) {
                is Version.V2 -> {
                    val compressed = flags and 0x40 != 0
                    if (compressed)
                        Result.Error(Error.CompressedVersion2)
                    else
                        Result.Success(size)
                }
                is Version.V3 -> {
                    val extendedHeader = flags and 0x40 != 0
                    Result.Success(if (extendedHeader) {
                        val extendedHeaderSize = readInt()
                        discard(extendedHeaderSize.toLong())

                        size - (extendedHeaderSize + 4)
                    } else size)
                }
                is Version.V4 -> {
                    val extendedHeader = flags and 0x40 != 0
                    val hasFooter = flags and 0x10 != 0
                    Result.Success(
                        if (extendedHeader) {
                            val extendedHeaderSize = readInt()
                            discard(extendedHeaderSize.toLong() - 4)
                            size - extendedHeaderSize
                        } else {
                            size
                        } - if (hasFooter) 10 else 0
                    )
                }
            }

            framesSizeResult.map { framesSize ->
                if (unsynchronized) {
                    TODO("Not implemented yet")
                }

                val frameHeaderSize = if (version is Version.V2) 6 else 10

                // TODO: Can read directly from this but to be safe that we advance correctly
                // TODO: We're consuming frameHeaderSize here.
                // TODO: The ExoPlayer logic does a break when it discovers data looking like padding
                // TODO: Would be better to figure out the correct number of frames instead?
                val framesByteReadChannels = ByteReadChannel(ByteArray(framesSize).apply { readFully(this) })

                val frames = mutableListOf<Frame>()
                try {
                    while (true) {
                        val idFirstByte = framesByteReadChannels.readByte().toUByte().toInt()
                        val idSecondByte = framesByteReadChannels.readByte().toUByte().toInt()
                        val idThirdByte = framesByteReadChannels.readByte().toUByte().toInt()
                        val idFourthByte =
                            if (majorVersion >= 3) framesByteReadChannels.readByte().toUByte()
                                .toInt() else 0

                        val frameSize = when (version) {
                            is Version.V4 -> {
                                framesByteReadChannels.readInt()
                                // TODO: ExoPlayer does some unsignedIntFrameSizeHack here

                            }
                            is Version.V3 -> {
                                framesByteReadChannels.readInt()
                            }
                            is Version.V2 -> {
                                val firstByte = framesByteReadChannels.readByte().toUByte().toInt()
                                val secondByte = framesByteReadChannels.readByte().toUByte().toInt()
                                val thirdByte = framesByteReadChannels.readByte().toUByte().toInt()
                                (firstByte shl 16) or (secondByte shl 8) or thirdByte
                            }
                        }
                        val flags = when (version) {
                            is Version.V2 -> 0
                            is Version.V3, is Version.V4 -> {
                                framesByteReadChannels.readShort().toUShort().toInt()
                            }
                        }

                        val frame = ByteReadChannel(ByteArray(frameSize).apply {
                            framesByteReadChannels.readFully(this)
                        })

                        if (idFirstByte == 0 && idSecondByte == 0 && idThirdByte == 0 && frameSize == 0 && flags == 0) {
                            // we're reading padding
                            break
                        } else {
                            val compressed = when (version) {
                                is Version.V2 -> false
                                is Version.V3 -> (flags and FrameFlags.V3.COMPRESSED) != 0
                                is Version.V4 -> (flags and FrameFlags.V4.COMPRESSED) != 0
                            }

                            val encrypted = when (version) {
                                is Version.V2 -> false
                                is Version.V3 -> (flags and FrameFlags.V3.ENCRYPTED) != 0
                                is Version.V4 -> (flags and FrameFlags.V4.ENCRYPTED) != 0
                            }

                            val hasGroupIdentifier = when (version) {
                                is Version.V2 -> false
                                is Version.V3 -> (flags and FrameFlags.V3.GROUP_IDENTIFIER) != 0
                                is Version.V4 -> (flags and FrameFlags.V4.GROUP_IDENTIFIER) != 0
                            }

                            val hasDataLength = when (version) {
                                is Version.V2 -> false
                                is Version.V3 -> compressed
                                is Version.V4 -> (flags and FrameFlags.V4.HAS_DATA_LENGTH) != 0
                            }

                            val unsynchronized = when (version) {
                                is Version.V2 -> false
                                is Version.V3 -> false
                                is Version.V4 -> (flags and FrameFlags.V4.UNSYNCHRONIZED) != 0
                            }

                            if (compressed || encrypted) {
                                TODO("Not implemented yet")
                                // TODO: ExoPlayer just skips this frame
                            }

                            val groupIdentifier =
                                if (hasGroupIdentifier) frame.readByte().toUByte().toInt() else null

                            val dataLength = if (hasDataLength) frame.readInt() else null

                            if (unsynchronized) {
                                TODO("Not implemented yet")
                                // TODO: removeUnsynchronization
                            }

                            frames.add(when {
                                idFirstByte == 'T'.code && idSecondByte == 'X'.code && idThirdByte == 'X'.code
                                        && (version is Version.V2 || idFourthByte == 'X'.code) -> {
                                    // decodeTxxxFrame
                                    val encoding = frame.readByte().toUByte().toInt()
                                    val charsetResult: Result<Charset, Error> = when (encoding) {
                                        TextEncoding.UTF_16 -> Result.Success(
                                            StandardCharsets.UTF_16
                                        )
                                        TextEncoding.UTF_16BE -> Result.Success(
                                            StandardCharsets.UTF_16BE
                                        )
                                        TextEncoding.UTF_8 -> Result.Success(
                                            StandardCharsets.UTF_8
                                        )
                                        TextEncoding.ISO_8859_1 -> Result.Success(
                                            StandardCharsets.ISO_8859_1
                                        )
                                        else -> Result.Error(
                                            Error.UnknownTextEncoding(
                                                encoding
                                            )
                                        )
                                    }

                                    TODO("Not implemented yet")
                                }
                                idFirstByte == 'T'.code -> {
                                    //String id = getFrameId(majorVersion, frameId0, frameId1, frameId2, frameId3);
                                    //frame = decodeTextInformationFrame(id3Data, frameSize, id);
                                    TODO("Not implemented yet")
                                }
                                idFirstByte == 'W'.code && idSecondByte == 'X'.code && idThirdByte == 'X'.code
                                        && (version is Version.V2 || idFourthByte == 'X'.code) -> {
                                    // decodeWxxxFrame
                                    TODO("Not implemented yet")
                                }
                                idFirstByte == 'W'.code -> {
                                    // String id = getFrameId(majorVersion, frameId0, frameId1, frameId2, frameId3);
                                    //        frame = decodeUrlLinkFrame(id3Data, frameSize, id);
                                    TODO("Not implemented yet")
                                }
                                idFirstByte == 'P'.code && idSecondByte == 'R'.code && idThirdByte == 'I'.code && idFourthByte == 'V'.code -> {
                                    // decodePrivFrame

                                    val data = ByteArray(frameSize).apply { frame.readFully(this) }

                                    val ownerEndIndex = data.indexOf(0.toByte())
                                    val owner = String(
                                        data,
                                        0,
                                        ownerEndIndex,
                                        StandardCharsets.ISO_8859_1
                                    )
                                    val privateData = data.copyOfRange(ownerEndIndex + 1, data.size)
                                    Frame.Priv(owner, privateData)
                                }
                                idFirstByte == 'G'.code && idSecondByte == 'E'.code && idThirdByte == 'O'.code
                                        && (version is Version.V2 || idFourthByte == 'B'.code) -> {
                                    // decodeGeobFrame
                                    TODO("Not implemented yet")
                                }
                                version is Version.V2 && (idFirstByte == 'P'.code && idSecondByte == 'I'.code && idThirdByte == 'C'.code)
                                        || (idFirstByte == 'A'.code && idSecondByte == 'P'.code && idThirdByte == 'I'.code && idFourthByte == 'C'.code) -> {
                                    // decodeApicFrame
                                    TODO("Not implemented yet")
                                }
                                idFirstByte == 'C'.code && idSecondByte == 'O'.code && idThirdByte == 'M'.code
                                        && (version is Version.V2 || idFourthByte == 'M'.code) -> {
                                    // decodeCommentFrame
                                    TODO("Not implemented yet")
                                }
                                idFirstByte == 'C'.code && idSecondByte == 'H'.code && idThirdByte == 'A'.code && idFourthByte == 'P'.code -> {
                                    // decodeChapterFrame
                                    TODO("Not implemented yet")
                                }
                                idFirstByte == 'C'.code && idSecondByte == 'T'.code && idThirdByte == 'O'.code && idFourthByte == 'C'.code -> {
                                    // decodeChapterTOCFrame
                                    TODO("Not implemented yet")
                                }
                                idFirstByte == 'M'.code && idSecondByte == 'L'.code && idThirdByte == 'L'.code && idFourthByte == 'T'.code -> {
                                    // decodeMlltFrame
                                    TODO("Not implemented yet")
                                }
                                else -> {
                                    // String id = getFrameId(majorVersion, frameId0, frameId1, frameId2, frameId3);
                                    //        frame = decodeBinaryFrame(id3Data, frameSize, id);
                                    TODO("Not implemented yet")
                                }
                            })
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {}
                frames
            }
        }
    }
}

suspend fun ByteReadChannel.readSynchSafeInt(): Int {
    val firstByte = readByte().toUByte().toInt()
    val secondByte = readByte().toUByte().toInt()
    val thirdByte = readByte().toUByte().toInt()
    val fourthByte = readByte().toUByte().toInt()
    return (firstByte shl 21) or (secondByte shl 14) or (thirdByte shl 7) or fourthByte
}
