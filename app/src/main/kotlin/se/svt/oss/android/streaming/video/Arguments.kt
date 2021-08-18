package se.svt.oss.android.streaming.video

import se.svt.oss.android.streaming.format.Format

data class Arguments(
    val format: Format,
    val width: Int,
    val height: Int,
)
