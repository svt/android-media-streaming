package se.svt.videoplayer

import android.media.AudioAttributes
import android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN
import android.media.AudioAttributes.USAGE_MEDIA
import android.media.AudioFormat
import android.media.AudioFormat.ENCODING_PCM_16BIT
import android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
import android.media.AudioTrack
import android.media.AudioTrack.MODE_STREAM
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
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
import se.svt.videoplayer.container.aac.aacFlow
import se.svt.videoplayer.container.ts.streams.streams
import se.svt.videoplayer.container.ts.tsFlow
import se.svt.videoplayer.databinding.ActivityMainBinding
import se.svt.videoplayer.format.Format
import se.svt.videoplayer.mediacodec.audioInputBufferIndicesChannel
import se.svt.videoplayer.mediacodec.codecFromFormat
import se.svt.videoplayer.mediacodec.videoInputBufferIndicesChannel
import se.svt.videoplayer.streaming.hls.m3u.master.Type
import se.svt.videoplayer.streaming.hls.m3u.master.parseMasterPlaylistM3u
import se.svt.videoplayer.streaming.hls.m3u.media.parseMediaPlaylistM3u
import se.svt.videoplayer.streaming.hls.tsAsHls
import se.svt.videoplayer.surface.surfaceHolderConfigurationFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.absoluteValue

data class PresentationTime(
    val pts: Duration,
    val realTime: Duration,
)

class MainActivity : AppCompatActivity() {
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val client = HttpClient(CIO) {
            expectSuccess = true
        }

        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos

        var lastPresentationTime: PresentationTime? = null

        setContentView(
            ActivityMainBinding.inflate(layoutInflater).apply {

                lifecycleScope.launch {
                    surfaceView.holder.surfaceHolderConfigurationFlow()
                        .mapNotNull { it }
                        .collect { surfaceHolderConfiguration ->
                            // TODO: Don't redo all the work when we get a new surface
                            // TODO: Note that we need to recreate the codec though

                            //val codecName = "OMX.android.goldfish.h264.decoder"
                            //val codecName = "c2.qti.avc.decoder"
                            val mediaCodec = MediaCodec.createByCodecName(codecFromFormat(codecInfos, Format.H264)?.name!!)
                            val bufferIndexChannel = mediaCodec.videoInputBufferIndicesChannel()

                            val audioTrack = AudioTrack(
                                AudioAttributes.Builder()
                                    .setUsage(USAGE_MEDIA)
                                    .setContentType(CONTENT_TYPE_UNKNOWN)
                                    .setFlags(0)
                                    .build(),
                                AudioFormat.Builder()
                                    .setSampleRate(/*packet.samplingFrequency*/48000) // TODO
                                    .setChannelMask(12) // TODO
                                    .setEncoding(ENCODING_PCM_16BIT) // TODO
                                    .build(),
                                /*packet.samplingFrequency*/48000, // TODO
                                MODE_STREAM,
                                AUDIO_SESSION_ID_GENERATE
                            ).apply {
                                play()
                            }

                            val audioMediaCodec = MediaCodec.createByCodecName("OMX.google.aac.decoder")

                            val audioBufferIndexChannel = audioMediaCodec.audioInputBufferIndicesChannel(audioTrack)
                            audioMediaCodec.apply {
                                configure(
                                    MediaFormat().apply {
                                        setFloat("operating-rate", 48000.toFloat()/*packet.samplingFrequency.toFloat()*/) // TODO
                                        setInteger("sample-rate", 48000/*packet.samplingFrequency*/) // TODO
                                        setString("mime", "audio/mp4a-latm")
                                        setInteger("channel-count", 2/*packet.channels*/) // TODO
                                        setInteger("priority", 0)
                                        // TODO: Look at "csd-" + i logic in ExoPlayer
                                        // TODO: We have an audioSpecific config in the Aac packages, use it!
                                        setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(17, -112)))
                                    },
                                    null,
                                    null,
                                    0
                                )
                                start()
                            }

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
                                // api.svt.se/video/ewAdr96

                                val masterPlaylist =
                                    Uri.parse("https://svt-vod-10a.akamaized.net/d0/world/20210630/5a3fd48e-c39a-4e43-959f-39c41e79ac43")
                                        .let { basePath ->
                                            client
                                                .get<HttpResponse>(
                                                    Uri.parse("$basePath/hls-ts-full.m3u8?alt=https%3A%2F%2Fswitcher.cdn.svt.se%2F5a3fd48e-c39a-4e43-959f-39c41e79ac43%2Fhls-ts-full.m3u8")
                                                        .toString()
                                                )
                                                .receive<ByteReadChannel>()
                                                .parseMasterPlaylistM3u(basePath)
                                        }
                                Log.e("masterPlaylist", "masterPlaylist: $masterPlaylist")

                                val audio = masterPlaylist.map {
                                    it.alternateRenditions.find { it.type == Type.AUDIO && it.channels == 2 && it.language == "sv" }
                                }.ok!!

                                val audioLastPathSegment = audio.uri.lastPathSegment!!
                                val audioBasePath = audioLastPathSegment.let { audio.uri.toString().removeSuffix(it) }

                                val audioMediaPlaylist =
                                    Uri.parse(audioBasePath)
                                        .let { basePath ->
                                            client
                                                .get<HttpResponse>(
                                                    Uri.parse("$basePath/$audioLastPathSegment")
                                                        .toString()
                                                )
                                                .receive<ByteReadChannel>()
                                                .parseMediaPlaylistM3u(basePath)
                                        }

                                // Pick video by resolution
                                // TODO: Pick by bandwidth and/or a combination
                                val entry = masterPlaylist.ok!!.entries.minByOrNull { entry ->
                                    // TODO: Don't crash
                                    entry.resolution!!.let { (width, height) ->
                                        (width - surfaceHolderConfiguration.width).absoluteValue + (height - surfaceHolderConfiguration.height).absoluteValue
                                    }
                                }!!

                                val lastPathSegment = entry.uri.lastPathSegment!!
                                val basePath = lastPathSegment.let { entry.uri.toString().removeSuffix(it) }

                                val mediaPlaylist =
                                    Uri.parse(basePath)
                                        .let { basePath ->
                                            client
                                                .get<HttpResponse>(
                                                    Uri.parse("$basePath/$lastPathSegment")
                                                        .toString()
                                                )
                                                .receive<ByteReadChannel>()
                                                .parseMediaPlaylistM3u(basePath)
                                        }

                                // TODO: This must be done in parallel with video below, use async {}
                                audioMediaPlaylist.ok!!.entries.map { it.uri } // TODO: Handle errors
                                    .asFlow()
                                    .map {
                                        Log.e(MainActivity::class.java.simpleName, "Fetch $it")
                                        val get: HttpResponse = client.get(it.toString())
                                        val channel: ByteReadChannel = get.receive()
                                        channel
                                    }
                                    .buffer()
                                    .flatMapConcat {
                                        it.aacFlow()
                                    }
                                    .collect { result ->
                                        val packet = result.ok!!

                                        audioBufferIndexChannel.receive { inputBuffer ->
                                            inputBuffer.put(packet.data)

                                            Duration.ofNanos(System.nanoTime())
                                        }
                                            .mapErr {
                                                Log.e(
                                                    MainActivity::class.java.simpleName,
                                                    "buffer index: $it"
                                                )
                                            }
                                    }

                                mediaPlaylist.ok!!.entries.map { it.uri } // TODO: Handle errors
                                    .asFlow()
                                    .map {
                                        Log.e(MainActivity::class.java.simpleName, "Fetch $it")
                                        val get: HttpResponse = client.get(it.toString())
                                        val channel: ByteReadChannel = get.receive()
                                        channel
                                    }
                                    .buffer()
                                    .flatMapConcat { channel ->
                                        tsFlow(channel)
                                            .buffer()
                                            .mapNotNull { it.ok } // TODO: Handle errors
                                            .streams()
                                            .buffer()
                                            .tsAsHls()
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
                                                        readFully(this)
                                                    }
                                                })

                                            val presentationTime = if (pes.pts != null) {
                                                val lst = lastPresentationTime
                                                val presentationTime = if (lst == null) {
                                                    PresentationTime(
                                                        pes.pts,
                                                        Duration.ofNanos(System.nanoTime())
                                                    )
                                                } else {
                                                    PresentationTime(
                                                        pes.pts,
                                                        lst.realTime + (pes.pts - lst.pts)
                                                    )
                                                }
                                                lastPresentationTime = presentationTime

                                                presentationTime.realTime
                                            } else
                                                Duration.ofNanos(System.nanoTime())

                                            presentationTime
                                        }
                                            .mapErr {
                                                Log.e(
                                                    MainActivity::class.java.simpleName,
                                                    "buffer index: $it"
                                                )
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
