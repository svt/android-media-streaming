package se.svt.videoplayer.streaming.hls.m3u

import java.time.Duration
import java.util.concurrent.TimeUnit

internal object BasicTag {
    val extXVersionRegex = Regex("#EXT-X-VERSION:([0-9]+)")
}

internal val urlRegex = Regex("""([A-Za-z]+://)""")

fun durationOfDoubleSeconds(seconds: Double): Duration = Duration.ofNanos((seconds * TimeUnit.SECONDS.toNanos(1)).toLong())
