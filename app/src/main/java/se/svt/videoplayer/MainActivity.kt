package se.svt.videoplayer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaSync
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import se.svt.videoplayer.container.ts.Pid
import se.svt.videoplayer.container.ts.pes.pes
import se.svt.videoplayer.container.ts.pes_or_psi.pesOrPsi
import se.svt.videoplayer.container.ts.tsFlow
import se.svt.videoplayer.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.min


class MainActivity : AppCompatActivity() {
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val client = HttpClient(CIO) {
            expectSuccess = true
        }

        //val byteChannel = ByteChannel()
        val pesChannel = Channel<ByteArray>(capacity = 1000)
        val bufferedReceiverChannel = BufferedReceiverChannel(pesChannel)

        CoroutineScope(Dispatchers.IO).launch {
            val urls = (1 until 43).map { "https://ed9.cdn.svt.se/d0/world/20210720/2c082525-031a-4e16-987a-3c47b699fc68/hls-video-avc-1280x720p50-2073/hls-video-avc-1280x720p50-2073-${it}.ts" }
            urls.forEach {
                val get: HttpResponse = client.get(it)
                val channel: ByteReadChannel = get.receive()
                val tsFlow = tsFlow(channel)
                    .mapNotNull { it.ok } // TODO: Handle errors
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
                    .pes()
                    .mapNotNull { it.ok } // TODO: Handle errors
                    .collect { pes ->
                        //Log.e("PES", "size: ${pes.data.size}, ${pes}")
                        //byteChannel.writeFully(pes.data)
                        pesChannel.send(pes.data)
                    }
            }
        }

        setContentView(
            ActivityMainBinding.inflate(layoutInflater).apply {
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val initialSurface = holder.surface

                        val mediaSync = MediaSync().apply {
                            playbackParams = PlaybackParams().setSpeed(0.0f)
                            setSurface(initialSurface)
                        }

                        val surface = mediaSync.createInputSurface()

                        val mediaExtractor = MediaExtractor().apply {
                            setDataSource("https://storage.googleapis.com/wvmedia/clear/h264/tears/tears_h264_baseline_240p_800.mp4") // TODO // TODO: Warning: This is a blocking call?
                            //setDataSource("https://ed9.cdn.svt.se/d0/world/20210720/2c082525-031a-4e16-987a-3c47b699fc68/hls-ts-avc.m3u8") // TODO // TODO: Warning: This is a blocking call?
                            //setDataSource("https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd") // TODO // TODO: Warning: This is a blocking call?
                        }

                        val mediaCodec = MediaCodec.createByCodecName("OMX.android.goldfish.h264.decoder")
                        mediaCodec.configure(
                            MediaFormat().apply {
                                                setString("mime", "video/avc")
                                setInteger("width", 1280)
                                setInteger("height", 720)
                            },
                            surface,
                            null,
                            0
                        )

                        mediaCodec.setCallback(object : MediaCodec.Callback() {
                            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                                codec.getInputBuffer(index)?.let { buffer ->
                                    val size = bufferedReceiverChannel.receiveAvailableTo(buffer)
                                    //val size = byteChannel.readAvailable(buffer)
                                    if (size == -1) {
                                        Log.e("MainActivity", "byteChannel closed")
                                    } else {
                                        /*Log.e(
                                            MainActivity::class.java.simpleName,
                                            "index: $index size: $size"
                                        )*/
                                        codec.queueInputBuffer(
                                            index,
                                            0,
                                            size,
                                            System.nanoTime() / 1000, // TODO
                                            0
                                        )
                                    }
                                }
                            }

                            override fun onOutputBufferAvailable(
                                codec: MediaCodec,
                                index: Int,
                                info: MediaCodec.BufferInfo
                            ) {
                                mediaCodec.releaseOutputBuffer(index, TimeUnit.MICROSECONDS.toNanos(info.presentationTimeUs))
                            }

                            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                                TODO("Not yet implemented")
                            }

                            override fun onOutputFormatChanged(
                                codec: MediaCodec,
                                format: MediaFormat
                            ) {
                                Log.e(MainActivity::class.java.simpleName, "onOutputFormatChanged $format")

                            }
                        })

                        mediaCodec.start()


                        /*(0 until mediaExtractor.trackCount).map {
                            val format = mediaExtractor.getTrackFormat(it)
                            val mime = format.getString(MediaFormat.KEY_MIME)
                            // TODO: Is a track both audio and video? If we have two codecs but one extractor, how does that work??
                            val weAreInterestedInThisTrack = true
                            if (weAreInterestedInThisTrack) {
                                mediaExtractor.selectTrack(it)

                                val mediaCodec =
                                    MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format)
                                        ?.let { codecName ->
                                            MediaCodec.createByCodecName(codecName)
                                        } ?: mime?.let(MediaCodec::createDecoderByType) // TODO: createDecoderByType may throw IllegalArgumentException

                                // TODO: Don't parse mime
                                val isAudio = mime?.contains("audio") == true
                                if (mime?.contains("video") == true) {
                                    mediaCodec?.configure(format, surface, null, 0)
                                } else {
                                    if (isAudio) {
                                        mediaCodec?.configure(format, null, null, 0)
                                    }
                                }

                                mediaCodec?.setCallback(object : MediaCodec.Callback() {
                                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                                        codec.getInputBuffer(index)?.let { buffer ->
                                            val size = mediaExtractor.readSampleData(buffer, 0)
                                            // TODO: If size is negative, no data is currently available, but will onInputBufferAvailable be called again? I.e. we should trigger a callback
                                            if (size > 0) {
                                                Log.e(
                                                    MainActivity::class.java.simpleName,
                                                    "index: $index size: $size: sampleTime: ${mediaExtractor.sampleTime}"
                                                )
                                                codec.queueInputBuffer(
                                                    index,
                                                    0,
                                                    size,
                                                    mediaExtractor.sampleTime,
                                                    0
                                                )

                                                mediaExtractor.advance()
                                            }
                                        }
                                    }

                                    override fun onOutputBufferAvailable(
                                        codec: MediaCodec,
                                        index: Int,
                                        info: MediaCodec.BufferInfo
                                    ) {
                                        if (isAudio) {
                                            codec.getOutputBuffer(index)?.let { buffer ->
                                                mediaSync.queueAudio(buffer, index, info.presentationTimeUs)
                                            }
                                            // TODO: No release?
                                        } else {
                                            mediaCodec.releaseOutputBuffer(index, TimeUnit.MICROSECONDS.toNanos(info.presentationTimeUs))
                                        }
                                    }

                                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                                        TODO("Not yet implemented")
                                    }

                                    override fun onOutputFormatChanged(
                                        codec: MediaCodec,
                                        format: MediaFormat
                                    ) {
                                        Log.e(MainActivity::class.java.simpleName, "onOutputFormatChanged $format")

                                    }
                                })

                                mediaCodec?.start()


                            }
                        }*/

                        mediaSync.playbackParams = PlaybackParams().setSpeed(1.0f)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        Log.e(MainActivity::class.java.simpleName, "surfaceChanged $format, $width, $height")
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Log.e(MainActivity::class.java.simpleName, "surfaceDestroyed")
                    }
                })
            }.root
        )
    }
}

private class BufferedReceiverChannel(val receiverChannel: ReceiveChannel<ByteArray>) {
    private var current: Pair<ByteArray, Int>? = null

    fun receiveAvailableTo(buffer: ByteBuffer): Int {
        var written = 0

        while (buffer.remaining() > 0) {
            current?.let { (byteArray, offset) ->
                val length = min(buffer.remaining(), byteArray.size - offset)
                buffer.put(byteArray, offset, length)
                written += length
                val fullyConsumed = length == byteArray.size - offset
                current = if (fullyConsumed)
                    null
                else
                    byteArray to offset + length
            }

            if (buffer.remaining() > 0) {
                val tryReceive = receiverChannel.tryReceive()
                if (tryReceive.isClosed)
                    return -1
                tryReceive.getOrNull()?.let { byteArray ->
                    val length = min(buffer.remaining(), byteArray.size)
                    buffer.put(byteArray, 0, length)
                    written += length

                    if (byteArray.size > length)
                        current = byteArray to length
                }
            }
        }

        return written
    }
}
