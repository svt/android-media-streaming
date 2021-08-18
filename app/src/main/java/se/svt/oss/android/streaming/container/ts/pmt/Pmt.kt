package se.svt.oss.android.streaming.container.ts.pmt

import io.ktor.utils.io.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import se.svt.oss.android.streaming.container.ts.Pid
import java.nio.charset.StandardCharsets

private enum class Type(val value: Int) {
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
    DVBSUBS(0x59)
}

private enum class Descriptor(val value: Int) {
    REGISTRATION(0x05),
    ISO639_LANG(0x0A),
    AC3(0x6A),
    AIT(0x6F),
    EAC3(0x7A),
    DTS(0x7B),
    DVB_EXT(0x7F),
    DVBSUBS(0x59),
}

private enum class DescriptorExt(val value: Int) {
    DVB_EXT_AC4(0x15)
}

private enum class FormatIdentifier(val value: Int) {
    AC3(0x41432d33),
    E_AC3(0x45414333),
    AC4(0x41432d34),
    HEVC(0x48455643),
}

sealed class Stream {
    object Mpa : Stream()
    object MpaLsf : Stream()
    object AacAdts : Stream()
    object AacLatm : Stream()
    object Ac3 : Stream()
    object EAc3 : Stream()
    object Ac4 : Stream()
    object H262 : Stream()
    object H263 : Stream()
    object H264 : Stream()
    object H265 : Stream()
    object Id3 : Stream()
    object SpliceInfo : Stream()
    object Dts : Stream()
    object HdmvDts : Stream()
    data class Iso639Lang(val language: String) : Stream()

    data class SubtitleInfo(val language: String, val type: Int, val initializationData: ByteArray)

    data class DvbSubs(val subtitleInfo: List<SubtitleInfo>) : Stream()
    object Ait : Stream()
}

data class Pmt(val pcrPid: Pid, val streams: Flow<Pair<Pid, Stream?>>)

suspend fun ByteReadChannel.pmt(): Pmt {
    val pcrPid = Pid((readShort().toUShort() and 0x1FFF.toUShort()).toInt())
    val programInfoLength = (readShort().toUShort() and 0x3FF.toUShort()).toLong()

    // descriptors
    discard(programInfoLength)

    return Pmt(
        pcrPid,
        flow {
            try {
                while (true) {
                    val streamType = readByte().toUByte().toInt()
                    val elementaryPid = Pid((readShort().toUShort() and 0x1FFF.toUShort()).toInt())
                    val esInfoLength = (readShort().toUShort() and 0x3FF.toUShort()).toLong()

                    emit(elementaryPid to if (esInfoLength > 0) {
                        val descriptorTag = readByte().toUByte().toInt()
                            .let { descriptor ->
                                Descriptor.values().find { it.value == descriptor }
                            }
                        when (descriptorTag) {
                            Descriptor.REGISTRATION -> {
                                val formatIdentifier =
                                    readInt().let { formatIdentifier ->
                                        FormatIdentifier.values()
                                            .find { it.value == formatIdentifier }
                                    }
                                when (formatIdentifier) {
                                    FormatIdentifier.AC3 -> Stream.Ac3
                                    FormatIdentifier.E_AC3 -> Stream.EAc3
                                    FormatIdentifier.AC4 -> Stream.Ac4
                                    FormatIdentifier.HEVC -> Stream.H265
                                    null -> null
                                }
                            }
                            Descriptor.ISO639_LANG -> {
                                Stream.Iso639Lang(String(ByteArray(3).apply {
                                    readFully(this)
                                }, StandardCharsets.UTF_8).trim())
                            }
                            Descriptor.AC3 -> Stream.Ac3
                            Descriptor.AIT -> Stream.Ait
                            Descriptor.EAC3 -> Stream.EAc3
                            Descriptor.DTS -> Stream.Dts
                            Descriptor.DVB_EXT -> {
                                val descriptorTagExt = readByte().toUByte().toInt()
                                    .let { descriptorExt ->
                                        DescriptorExt.values()
                                            .find { it.value == descriptorExt }
                                    }
                                when (descriptorTagExt) {
                                    DescriptorExt.DVB_EXT_AC4 -> Stream.Ac4
                                    null -> null
                                }
                            }
                            Descriptor.DVBSUBS -> {
                                Stream.DvbSubs((0 until (esInfoLength - 1) / 8).map {
                                    val language = String(ByteArray(3).apply {
                                        readFully(this)
                                    }, StandardCharsets.UTF_8).trim()
                                    val type = readByte().toUByte().toInt()
                                    val initializationData =
                                        ByteArray(4).apply { readFully(this) }
                                    Stream.SubtitleInfo(language, type, initializationData)
                                })
                            }
                            null -> {
                                discard(esInfoLength - 1)
                                null
                            }
                        }
                    } else {
                        when (Type.values().find { it.value == streamType }) {
                            Type.MPA -> Stream.Mpa
                            Type.MPA_LSF -> Stream.MpaLsf
                            Type.AAC_ADTS -> Stream.AacAdts
                            Type.AAC_LATM -> Stream.AacLatm
                            Type.AC3 -> Stream.Ac3
                            Type.DTS -> Stream.Dts
                            Type.HDMV_DTS -> Stream.HdmvDts
                            Type.E_AC3 -> Stream.EAc3
                            Type.AC4 -> Stream.Ac4
                            Type.H262 -> Stream.H262
                            Type.H263 -> Stream.H263
                            Type.H264 -> Stream.H264
                            Type.H265 -> Stream.H265
                            Type.ID3 -> Stream.Id3
                            Type.SPLICE_INFO -> Stream.SpliceInfo
                            Type.DVBSUBS -> Stream.DvbSubs(listOf())
                            null -> null
                        }
                    })
                }
            } catch (e: ClosedReceiveChannelException) {}
        }
    )
}