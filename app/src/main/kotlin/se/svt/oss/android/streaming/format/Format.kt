// SPDX-FileCopyrightText: 2021 Sveriges Television AB
//
// SPDX-License-Identifier: Apache-2.0

package se.svt.oss.android.streaming.format

import se.svt.oss.android.streaming.container.ts.pmt.Stream

enum class Format(val mimeType: String) {
    H264("video/avc"),
    Aac("audio/mp4a-latm"),
}

fun Stream.toFormat() = when (this) {
    Stream.AacAdts -> Format.Aac
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
