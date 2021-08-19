// SPDX-FileCopyrightText: 2021 Sveriges Television AB
//
// SPDX-License-Identifier: Apache-2.0

package se.svt.oss.android.streaming.mediacodec

import android.media.MediaCodecInfo
import se.svt.oss.android.streaming.format.Format
import se.svt.oss.android.streaming.okOrElse

// TODO: There are duplicates for formats that expose other flags like secure surfaces and stuff.
fun mediaCodecInfoFromFormat(codecInfos: Array<MediaCodecInfo>, format: Format) = codecInfos.find {
    it.supportedTypes.contains(format.mimeType)
}.okOrElse { Error.NoCodecForFormat(format) }
