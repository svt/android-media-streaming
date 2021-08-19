// SPDX-FileCopyrightText: 2021 Sveriges Television AB
//
// SPDX-License-Identifier: Apache-2.0

package se.svt.oss.android.streaming

import android.net.Uri
import kotlin.math.max

fun Uri.removeLastPathSegmentIfAny(): Uri = buildUpon()
    .path(pathSegments.take(max(0, pathSegments.size - 1)).joinToString("/"))
    .build()
