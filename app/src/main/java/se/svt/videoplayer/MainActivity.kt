package se.svt.videoplayer

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import se.svt.videoplayer.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ActivityMainBinding.inflate(layoutInflater).apply {
                val surface = surfaceView.holder.surface

                MediaExtractor().apply {
                    setDataSource("") // TODO // TODO: Warning: This is a blocking call?
                    (0 until trackCount).map {
                        val format = getTrackFormat(it)
                        val mime = format.getString(MediaFormat.KEY_MIME)
                        val weAreInterestedInThisTrack
                        if (weAreInterestedInThisTrack) {
                            selectTrack(it)
                        }
                    }
                }
            }.root
        )
    }
}