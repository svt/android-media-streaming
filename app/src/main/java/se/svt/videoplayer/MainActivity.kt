package se.svt.videoplayer

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.svt.videoplayer.container.ts.Pid
import se.svt.videoplayer.container.ts.pes.pes
import se.svt.videoplayer.container.ts.pes_or_psi.pesOrPsi
import se.svt.videoplayer.container.ts.tsFlow
import se.svt.videoplayer.databinding.ActivityMainBinding
import se.svt.videoplayer.mediacodec.videoInputBufferIndicesChannel
import se.svt.videoplayer.surface.surfaceHolderConfigurationFlow


class MainActivity : AppCompatActivity() {
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val client = HttpClient(CIO) {
            expectSuccess = true
        }

        setContentView(
            ActivityMainBinding.inflate(layoutInflater).apply {

                lifecycleScope.launch {
                    surfaceView.holder.surfaceHolderConfigurationFlow()
                        .mapNotNull { it }
                        .collect { surfaceHolderConfiguration ->
                            // TODO: Don't redo all the work when we get a new surface
                            // TODO: Note that we need to recreate the codec though

                            //val codecName = "OMX.android.goldfish.h264.decoder"
                            val codecName = "c2.qti.avc.decoder"
                            val mediaCodec = MediaCodec.createByCodecName(codecName)
                            val bufferIndexChannel = mediaCodec.videoInputBufferIndicesChannel()
                            mediaCodec.apply {
                                configure(
                                    MediaFormat().apply {
                                        setString("mime", "video/avc")
                                        setInteger("width", 1280)
                                        setInteger("height", 720)
                                    },
                                    surfaceHolderConfiguration.surfaceHolder.surface,
                                    null,
                                    0
                                )

                                start()
                            }

                            withContext(Dispatchers.IO) {
                                // https://svt-vod-10a.akamaized.net/d0/world/20210630/5a3fd48e-c39a-4e43-959f-39c41e79ac43/hls-video-avc-960x540p25-1310/hls-video-avc-960x540p25-1310.m3u8
                                (1 until 930).map { "https://svt-vod-10a.akamaized.net/d0/world/20210630/5a3fd48e-c39a-4e43-959f-39c41e79ac43/hls-video-avc-960x540p25-1310/hls-video-avc-960x540p25-1310-${it}.ts" }
                                    .asFlow()
                                    .map {
                                        Log.e(MainActivity::class.java.simpleName, "Fetch ${it}")
                                        val get: HttpResponse = client.get(it)
                                        val channel: ByteReadChannel = get.receive()
                                        channel
                                    }
                                    .buffer()
                                    .flatMapConcat { channel ->
                                        val tsFlow = tsFlow(channel)
                                            .buffer()
                                            .mapNotNull { it.ok } // TODO: Handle errors
                                            .buffer()
                                        //.shareIn(this, SharingStarted.Lazily)

                                        /*tsFlow
                                            .pesOrPsi(PAT_ID)
                                            .psi()
                                            .mapNotNull { it.ok } // TODO: Handle errors
                                            .pat()
                                            .collect {
                                                Log.e("PAT", "${it}")
                                            }*/

                                        /*tsFlow.pesOrPsi(Pid(32)) // TODO
                                            .psi()
                                            .mapNotNull { it.ok } // TODO: Handle errors
                                            .pmt()
                                            .collect {
                                                Log.e("PMT", "${it}")
                                            }*/

                                        tsFlow.pesOrPsi(Pid(80)) // TODO
                                            .buffer()
                                            .pes()
                                            .buffer()
                                    }
                                    .mapNotNull { it.ok } // TODO: Handle errors
                                    .buffer()
                                    .collect { pes ->
                                        bufferIndexChannel.receive { inputBuffer ->
                                            inputBuffer.put(
                                                // TODO: Don't block readRemaining, instead read as much as is available then read the rest at another time
                                                pes.byteReadChannel.readRemaining().run {
                                                    ByteArray(remaining.toInt()).apply {
                                                        readFully(
                                                            this
                                                        )
                                                    }
                                                })
                                        }
                                            .mapErr {
                                                Log.e(MainActivity::class.java.simpleName, "buffer index: $it")
                                            }
                                    }
                                Log.e(MainActivity::class.java.simpleName, "Stream ended")
                            }
                        }
                }
            }.root
        )
    }
}
