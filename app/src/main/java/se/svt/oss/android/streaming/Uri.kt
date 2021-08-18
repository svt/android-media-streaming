package se.svt.oss.android.streaming

import android.net.Uri
import kotlin.math.max

fun Uri.removeLastPathSegmentIfAny(): Uri = buildUpon()
    .path(pathSegments.take(max(0, pathSegments.size - 1)).joinToString("/"))
    .build()
