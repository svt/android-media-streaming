package se.svt.videoplayer.container.ts

data class Pid(val value: Int) {
    companion object {
        val PAT = Pid(0)
    }
}
