// SPDX-FileCopyrightText: 2021 Sveriges Television AB
//
// SPDX-License-Identifier: Apache-2.0

package se.svt.oss.android.streaming.duration

import java.time.Duration
import java.util.concurrent.TimeUnit

fun durationOfMicros(micros: Long): Duration = Duration.ofNanos(TimeUnit.MICROSECONDS.toNanos(micros))
fun durationOfDoubleSeconds(seconds: Double): Duration = Duration.ofNanos((seconds * TimeUnit.SECONDS.toNanos(1)).toLong())
