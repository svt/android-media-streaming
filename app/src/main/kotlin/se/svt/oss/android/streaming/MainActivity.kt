package se.svt.oss.android.streaming

import android.media.MediaCodecList
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.svt.oss.android.streaming.audiotrack.audioTrack
import se.svt.oss.android.streaming.cache.SingleElementCache
import se.svt.oss.android.streaming.container.aac.aacFlow
import se.svt.oss.android.streaming.container.ts.streams.streams
import se.svt.oss.android.streaming.container.ts.tsFlow
import se.svt.oss.android.streaming.databinding.ActivityMainBinding
import se.svt.oss.android.streaming.format.Format
import se.svt.oss.android.streaming.mediacodec.mediaCodec
import se.svt.oss.android.streaming.streaming.hls.m3u.master.Type
import se.svt.oss.android.streaming.streaming.hls.m3u.master.parseMasterPlaylistM3u
import se.svt.oss.android.streaming.streaming.hls.m3u.media.parseMediaPlaylistM3u
import se.svt.oss.android.streaming.streaming.hls.tsAsHls
import se.svt.oss.android.streaming.surface.surfaceHolderConfigurationFlow
import java.time.Duration
import kotlin.math.absoluteValue
import se.svt.oss.android.streaming.audio.Arguments as AudioArguments
import se.svt.oss.android.streaming.audiotrack.Error as AudioTrackError
import se.svt.oss.android.streaming.container.aac.Error as AacError
import se.svt.oss.android.streaming.mediacodec.Error as MediaCodecError
import se.svt.oss.android.streaming.streaming.hls.m3u.master.Error as HlsM3uMasterError
import se.svt.oss.android.streaming.streaming.hls.m3u.media.Error as HlsM3uMediaError
import se.svt.oss.android.streaming.video.Arguments as VideoArguments

// https://api.svt.se/video/ewAdr96
private val MANIFEST_URL =
    Uri.parse("https://svt-vod-10a.akamaized.net/d0/world/20210630/5a3fd48e-c39a-4e43-959f-39c41e79ac43/hls-ts-full.m3u8?alt=https%3A%2F%2Fswitcher.cdn.svt.se%2F5a3fd48e-c39a-4e43-959f-39c41e79ac43%2Fhls-ts-full.m3u8")

data class PresentationTime(
    val pts: Duration,
    val realTime: Duration,
)

sealed class Error : Exception {
    constructor() : super()
    constructor(exception: java.lang.Exception) : super(exception)

    data class Aac(val error: AacError) : Error(error)
    data class MediaCodec(val error: MediaCodecError) : Error(error)
    object NoAlternateRenditionFound : Error()
    object NoEntryFound : Error()
    data class HlsM3uMaster(val error: HlsM3uMasterError) : Error(error)
    data class HlsM3uMedia(val error: HlsM3uMediaError) : Error(error)
    data class AudioTrack(val error: AudioTrackError) : Error(error)
}

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
                            val videoBufferIndexChannelProvider = SingleElementCache { arguments: VideoArguments ->
                                mediaCodec(codecInfos, arguments, surfaceHolderConfiguration)
                            }
                            val audioBufferIndexChannelProvider = SingleElementCache { audioArguments: AudioArguments ->
                                audioTrack(audioArguments)
                                    .mapErr(Error::AudioTrack)
                                    .andThen { audioTrack ->
                                    mediaCodec(codecInfos, audioArguments, audioTrack).mapErr(Error::MediaCodec)
                                }
                            }

                            withContext(Dispatchers.IO) {
                                val masterPlaylist = MANIFEST_URL
                                    .let { uri ->
                                        client
                                            .get<HttpResponse>(uri.toString())
                                            .receive<ByteReadChannel>()
                                            .parseMasterPlaylistM3u(uri.removeLastPathSegmentIfAny())
                                    }

                                val audioMediaPlaylist = masterPlaylist
                                    .mapErr(Error::HlsM3uMaster)
                                    .andThen { playlist ->
                                        playlist.alternateRenditions.find { it.type == Type.AUDIO && it.channels == 2 && it.language == "sv" }
                                            .okOr(Error.NoAlternateRenditionFound)
                                    }
                                    .andThen { audio ->
                                        client
                                            .get<HttpResponse>(audio.uri.toString())
                                            .receive<ByteReadChannel>()
                                            .parseMediaPlaylistM3u(audio.uri.removeLastPathSegmentIfAny())
                                    }

                                // TODO: Pick by bandwidth and/or a combination
                                val videoMediaPlaylist = masterPlaylist.orThrow().entries.minByOrNull { entry ->
                                    entry.resolution?.let { (width, height) ->
                                        (width - surfaceHolderConfiguration.width).absoluteValue + (height - surfaceHolderConfiguration.height).absoluteValue
                                    } ?: Int.MAX_VALUE
                                }
                                    .okOr(Error.NoEntryFound)
                                    .andThen { entry ->
                                        client
                                            .get<HttpResponse>(entry.uri.toString())
                                            .receive<ByteReadChannel>()
                                            .parseMediaPlaylistM3u(entry.uri.removeLastPathSegmentIfAny())
                                            .mapErr(Error::HlsM3uMedia)
                                    }

                                // TODO: This must be done in parallel with video below, use async {}
                                val deferredAudio = async {
                                    audioMediaPlaylist.orThrow().entries.map { it.uri } // TODO: Handle errors
                                        .asFlow()
                                        .map {
                                            Log.e(
                                                MainActivity::class.java.simpleName,
                                                "Audio fetch $it"
                                            )
                                            client.get<HttpResponse>(it.toString())
                                                .receive<ByteReadChannel>()
                                        }
                                        .buffer()
                                        .flatMapConcat {
                                            // TODO: errors and use timestamp
                                            it.aacFlow().orThrow().flow
                                        }
                                        .collect { packetResult ->
                                            packetResult
                                                .mapErr(Error::Aac)
                                                .andThen { packet ->
                                                    audioBufferIndexChannelProvider.get(AudioArguments(
                                                        packet.samplingFrequency,
                                                        listOf(packet.audioSpecificConfig),
                                                        packet.channels,
                                                        Format.Aac
                                                    ))
                                                        .andThen {
                                                            it.receive { inputBuffer ->
                                                                inputBuffer.put(packet.data)
                                                                Duration.ofNanos(System.nanoTime())
                                                            }
                                                            .mapErr(Error::MediaCodec)
                                                        }
                                                }
                                                .mapErr {
                                                    Log.e(MainActivity::class.java.simpleName, "$it")
                                                    Unit
                                                }
                                        }
                                }

                                val deferredVideo = async {
                                    videoMediaPlaylist.orThrow().entries // TODO: Handle errors
                                        .asFlow()
                                        .map {
                                            Log.e(MainActivity::class.java.simpleName, "Video Fetch ${it.uri}")
                                            client.get<HttpResponse>(it.uri.toString())
                                                .receive<ByteReadChannel>()
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
                                            videoBufferIndexChannelProvider.get(VideoArguments(
                                                // TODO: Read from TS
                                                Format.H264,
                                                1280,
                                                720
                                            ))
                                                .orThrow() // TODO: Handle error
                                                .receive { inputBuffer ->
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
                                }

                                deferredAudio.await()
                                deferredVideo.await()
                                Log.e(MainActivity::class.java.simpleName, "Stream ended")
                            }
                        }
                }
            }.root
        )
    }
}
