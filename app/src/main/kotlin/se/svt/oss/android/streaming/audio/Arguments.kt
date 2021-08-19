// SPDX-FileCopyrightText: 2021 Sveriges Television AB
//
// SPDX-License-Identifier: Apache-2.0

package se.svt.oss.android.streaming.audio

import se.svt.oss.android.streaming.format.Format

data class Arguments(
    val samplingFrequency: Int,
    val audioSpecificConfigs: List<ByteArray>,
    val channels: Int,
    val format: Format,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Arguments

        if (samplingFrequency != other.samplingFrequency) return false
        if (audioSpecificConfigs.size != other.audioSpecificConfigs.size) return false
        if (audioSpecificConfigs.zip(other.audioSpecificConfigs).any { (left, right) -> !left.contentEquals(right) }) return false
        if (channels != other.channels) return false
        if (format != other.format) return false

        return true
    }

    override fun hashCode(): Int {
        var result = samplingFrequency
        result = 31 * result + audioSpecificConfigs.hashCode()
        result = 31 * result + channels
        result = 31 * result + format.hashCode()
        return result
    }
}