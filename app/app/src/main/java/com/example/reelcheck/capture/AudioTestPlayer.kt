package com.example.reelcheck.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.sin

/**
 * Generates synthetic audio playback for testing AudioPlaybackCapture under
 * ALLOW_CAPTURE_BY_ALL (standard media playback) vs ALLOW_CAPTURE_BY_NONE (opt-out).
 */
object AudioTestPlayer {
    private const val TAG = "AudioTestPlayer"
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false
    private var playThread: Thread? = null

    fun playTone(optOut: Boolean = false, frequencyHz: Double = 440.0) {
        stop()

        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val capturePolicy = if (optOut) {
            AudioAttributes.ALLOW_CAPTURE_BY_NONE
        } else {
            AudioAttributes.ALLOW_CAPTURE_BY_ALL
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowedCapturePolicy(capturePolicy)
                }
            }
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.play()
            audioTrack = track
            isPlaying = true

            playThread = Thread({
                val chunk = ShortArray(1024)
                var angle = 0.0
                val angleIncrement = 2.0 * Math.PI * frequencyHz / sampleRate

                while (isPlaying) {
                    for (i in chunk.indices) {
                        chunk[i] = (sin(angle) * 20000.0).toInt().toShort()
                        angle += angleIncrement
                        if (angle > 2.0 * Math.PI) angle -= 2.0 * Math.PI
                    }
                    track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                }
            }, "ReelCheck-TonePlayerThread").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "Playing tone (${frequencyHz}Hz), optOut=$optOut, policy=$capturePolicy")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start AudioTestPlayer", e)
        }
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying

    fun stop() {
        isPlaying = false
        playThread?.interrupt()
        playThread = null
        try {
            audioTrack?.stop()
        } catch (e: Throwable) {
            // Ignore
        }
        audioTrack?.release()
        audioTrack = null
        Log.i(TAG, "AudioTestPlayer stopped")
    }
}
