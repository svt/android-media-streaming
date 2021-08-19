// SPDX-FileCopyrightText: 2021 Sveriges Television AB
//
// SPDX-License-Identifier: Apache-2.0

package se.svt.oss.android.streaming.video

import se.svt.oss.android.streaming.format.Format

data class Arguments(
    val format: Format,
    val width: Int,
    val height: Int,
)
