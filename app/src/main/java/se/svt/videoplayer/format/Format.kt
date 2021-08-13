package se.svt.videoplayer.format

import se.svt.videoplayer.container.ts.pmt.Stream

enum class Format(val mimeType: String) {
    H264("video/avc")
}

fun Stream.toFormat() = when (this) {
    Stream.AacAdts -> TODO()
    Stream.AacLatm -> TODO()
    Stream.Ac3 -> TODO()
    Stream.Ac4 -> TODO()
    Stream.Ait -> TODO()
    Stream.Dts -> TODO()
    is Stream.DvbSubs -> TODO()
    Stream.EAc3 -> TODO()
    Stream.H262 -> TODO()
    Stream.H263 -> TODO()
    Stream.H264 -> Format.H264
    Stream.H265 -> TODO()
    Stream.HdmvDts -> TODO()
    Stream.Id3 -> TODO()
    is Stream.Iso639Lang -> TODO()
    Stream.Mpa -> TODO()
    Stream.MpaLsf -> TODO()
    Stream.SpliceInfo -> TODO()
}
