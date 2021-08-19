// SPDX-FileCopyrightText: 2021 Sveriges Television AB
//
// SPDX-License-Identifier: Apache-2.0

package se.svt.oss.android.streaming.container.ts

data class Pid(val value: Int) {
    companion object {
        val PAT = Pid(0)
    }
}
