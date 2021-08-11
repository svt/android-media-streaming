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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import se.svt.videoplayer.container.ts.Pid
import se.svt.videoplayer.container.ts.pes.pes
import se.svt.videoplayer.container.ts.pes_or_psi.pesOrPsi
import se.svt.videoplayer.container.ts.tsFlow
import se.svt.videoplayer.databinding.ActivityMainBinding
import se.svt.videoplayer.mediacodec.videoInputBufferIndicesChannel
import se.svt.videoplayer.surface.surfaceConfigurationFlow


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
                    surfaceView.holder.surfaceConfigurationFlow()
                        .mapNotNull { it }
                        .collect { surfaceConfiguration ->
                            // TODO: Don't redo all the work when we get a new surface

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
                                    surfaceConfiguration.surface,
                                    null,
                                    0
                                )

                                start()
                            }

                            CoroutineScope(Dispatchers.IO).launch {
                                (1 until 43).map { "https://ed9.cdn.svt.se/d0/world/20210720/2c082525-031a-4e16-987a-3c47b699fc68/hls-video-avc-1280x720p50-2073/hls-video-avc-1280x720p50-2073-${it}.ts" }
                                    .asFlow()
                                    .flatMapConcat {
                                        Log.e("HTTP", "GET ${it}")
                                        val get: HttpResponse = client.get(it)
                                        val channel: ByteReadChannel = get.receive()
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
                                            inputBuffer.put(pes.data)
                                        }
                                            .mapErr {
                                                Log.e("MainActivity", "buffer index: $it")
                                            }
                                    }
                                Log.e("MainActivity", "I AM DONE COLLECTING!!")
                            }
                        }
                }
            }.root
        )
    }
}
