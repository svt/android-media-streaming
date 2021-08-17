package se.svt.videoplayer.container.aac

import android.util.Log
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.flow
import se.svt.videoplayer.Result
import se.svt.videoplayer.andThen
import se.svt.videoplayer.map
import se.svt.videoplayer.okOr
import se.svt.videoplayer.okOrElse
import se.svt.videoplayer.streaming.hls.audio.Frame
import se.svt.videoplayer.streaming.hls.audio.id3
import java.time.Duration
import java.util.concurrent.TimeUnit
import se.svt.videoplayer.streaming.hls.audio.Error as Id3Error

sealed class Error {
    object ExpectedSync : Error()
    data class InvalidSamplingFrequencyIndex(val index: Int): Error()
    data class Id3(val error: Id3Error) : Error()
    object MissingId3TimestampFrame : Error()
    object ExpectedPrivFrame: Error()
    object Expected8OctetTimestamp : Error()
    data class UnknownProfileObjectType(val type: Int): Error()
}

private const val HEADER_SIZE = 7
private val SAMPLING_FREQUENCY_TABLE = arrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

data class Packet(
    val channels: Int,
    val samplingFrequency: Int,
    val data: ByteArray,
    val audioSpecificConfig: ByteArray
)

enum class ObjectType(val value: Int) {
    // Advanced Audio Coding Low-Complexity profile.
    AAC_LC(2),

    // Spectral Band Replication.
    AAC_SBR(5),

    // Error Resilient Bit-Sliced Arithmetic Coding.
    AAC_ER_BSAC(22),

    // Enhanced low delay.
    AAC_ELD(23),

    // Parametric Stereo.
    AAC_PS(29),

    // Escape code for extended audio object types.
    ESCAPE(31),

    // Extended high efficiency.
    AAC_XHE(42),
}

fun ByteReadChannel.aacFlow() = flow<Result<Packet, Error>> {
    try {
        when (val id3Frames = id3()) {
            is Result.Success -> {
                val timestampResult = id3Frames.data.frames.find {
                    it.owner == "com.apple.streaming.transportStreamTimestamp"
                }
                    .okOr(Error.MissingId3TimestampFrame)
                    .andThen { (if (it is Frame.Priv) it.data else null).okOr(Error.ExpectedPrivFrame) }
                    .andThen { data ->
                        if (data.size == 8) {
                            Result.Success(ByteReadChannel(data).readLong() and 0x1FFFFFFFFL)
                        } else Result.Error(Error.Expected8OctetTimestamp)
                    }
                when (timestampResult) {
                    is Result.Success -> {
                        // TODO: Pass as Result<WrappingPackage(timestamp, Flow<..>), Error>
                        val timestamp = Duration.ofNanos(TimeUnit.MICROSECONDS.toNanos(timestampResult.data)) // TODO: Introduce ofMicros

                        while (true) {
                            val firstByte = readByte().toUByte().toInt()
                            val secondByte = readByte().toUByte().toInt()
                            val sync = (firstByte shl 4) or (secondByte shr 4)

                            if (sync != 0xFFF) {
                                emit(Result.Error<Packet, Error>(Error.ExpectedSync))
                                break
                            } else {
                                val id = (secondByte shr 3) and 0x1
                                val layer = (secondByte shr 1) and 0x3
                                val protectionAbsent = (secondByte and 0x1) != 0

                                val thirdByte = readByte().toUByte().toInt()
                                val profileObjectType = (thirdByte shr 6).let { objectType ->
                                    ObjectType.values()
                                        .find { it.value == objectType + 1 }
                                        .okOrElse { Error.UnknownProfileObjectType(objectType) }
                                }
                                Log.e("AAC", "profileObjectType = ${profileObjectType}")
                                val samplingFrequencyIndex = (thirdByte shr 2) and 0xF
                                val privateBit = ((thirdByte shr 1) and 0x1) != 0

                                val fourthByte = readByte().toUByte().toInt()
                                val channelConfiguration = ((thirdByte shl 2) or (fourthByte shr 6)) and 0x07
                                val originalCopy = ((fourthByte shr 5) and 0x1) != 0
                                val home = ((fourthByte shr 4) and 0x1) != 0

                                // variable header
                                val copyrightIdentificationBit = ((fourthByte shr 3) and 0x1) != 0
                                val copyrightIdentificationStart = ((fourthByte shr 2) and 0x1) != 0

                                val fifthByte = readByte().toUByte().toInt()
                                val sixthByte = readByte().toUByte().toInt()

                                val frameLength =
                                    ((fourthByte shl 11) or (fifthByte shl 3) or (sixthByte shr 5)) and 0x1FFF

                                val seventhByte = readByte().toUByte().toInt()
                                val bufFullness = (sixthByte and 0x1F) or (seventhByte shr 2)
                                val numberOfRawDataBlocksInFrame = seventhByte and 0x3

                                if (!protectionAbsent) {
                                    val crcCheck = readShort().toUShort().toInt()
                                    // TODO: Do crc check
                                }

                                val channels = channelConfiguration + if (channelConfiguration == 0x07) 1 else 0
                                val samplingFrequencyResult = SAMPLING_FREQUENCY_TABLE
                                    .getOrNull(samplingFrequencyIndex)
                                    .okOrElse { Error.InvalidSamplingFrequencyIndex(samplingFrequencyIndex) }

                                val size = frameLength - HEADER_SIZE - if (protectionAbsent) 0 else 2

                                val result: Result<Packet, Error> =
                                    samplingFrequencyResult.andThen { samplingFrequency ->
                                        profileObjectType.map { objectType ->
                                            val audioSpecificConfig = audioSpecificConfig(
                                                objectType,
                                                samplingFrequencyIndex,
                                                channelConfiguration
                                            )
                                            Log.e("AAC", "audioSpecificConfig = ${audioSpecificConfig[0]} ${audioSpecificConfig[1]}")
                                            Packet(
                                                channels,
                                                samplingFrequency,
                                                ByteArray(size).apply { readFully(this) },
                                                audioSpecificConfig
                                            )
                                        }
                                    }
                                emit(result)
                            }
                        }
                    }
                    is Result.Error -> emit(Result.Error(timestampResult.exception))
                }
            }
            is Result.Error -> emit(Result.Error(Error.Id3(id3Frames.exception)))
        }
    } catch (e: ClosedReceiveChannelException) {}
}

private fun audioSpecificConfig(
    audioObjectType: ObjectType, sampleRateIndex: Int, channelConfig: Int
) = byteArrayOf(
    (audioObjectType.value shl 3 and 0xF8 or (sampleRateIndex shr 1 and 0x07)).toByte(),
    (sampleRateIndex shl 7 and 0x80 or (channelConfig shl 3 and 0x78)).toByte()
)