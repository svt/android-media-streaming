package se.svt.oss.android.streaming.streaming.hls.m3u

internal object BasicTag {
    val extXVersionRegex = Regex("#EXT-X-VERSION:([0-9]+)")

    const val extXIndependentSegments = "#EXT-X-INDEPENDENT-SEGMENTS"
    const val extM3u = "#EXTM3U"
}

internal val urlRegex = Regex("""([A-Za-z]+://)""")
