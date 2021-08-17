package se.svt.videoplayer.mediacodec

import android.media.MediaCodecInfo
import se.svt.videoplayer.format.Format
import se.svt.videoplayer.okOrElse

// TODO: There are duplicates for formats that expose other flags like secure surfaces and stuff.
fun mediaCodecInfoFromFormat(codecInfos: Array<MediaCodecInfo>, format: Format) = codecInfos.find {
    it.supportedTypes.contains(format.mimeType)
}.okOrElse { Error.NoCodecForFormat(format) }
