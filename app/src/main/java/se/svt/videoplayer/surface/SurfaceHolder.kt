package se.svt.videoplayer.surface

import android.util.Log
import android.view.SurfaceHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow


data class SurfaceHolderConfiguration(
    val surfaceHolder: SurfaceHolder,
    val format: Int,
    val width: Int,
    val height: Int,
)

/**
 * Warning: Unclear whether these will trigger if the callback is added too late?
 */
@ExperimentalCoroutinesApi
fun SurfaceHolder.surfaceHolderConfigurationFlow() = callbackFlow {
    val callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            val result = trySend(SurfaceHolderConfiguration(holder, format, width, height))
            if (result.isFailure) {
                Log.e("surfaceHolderConfigurat", "surfaceChanged trySend $result")
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            val result = trySend(null)
            if (result.isFailure) {
                Log.e("surfaceHolderConfigurat", "surfaceDestroyed trySend $result")
            }
        }
    }
    addCallback(callback)

    awaitClose {
        removeCallback(callback)
    }
}
