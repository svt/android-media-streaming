package se.svt.videoplayer.duration

import java.time.Duration
import java.util.concurrent.TimeUnit

fun durationOfMicros(micros: Long): Duration = Duration.ofNanos(TimeUnit.MICROSECONDS.toNanos(micros))
fun durationOfDoubleSeconds(seconds: Double): Duration = Duration.ofNanos((seconds * TimeUnit.SECONDS.toNanos(1)).toLong())
