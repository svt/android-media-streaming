package se.svt.videoplayer

import android.media.MediaCodec
import android.media.MediaCodecList
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
import se.svt.videoplayer.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        }

                        (0 until mediaExtractor.trackCount).map {
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
                        }

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