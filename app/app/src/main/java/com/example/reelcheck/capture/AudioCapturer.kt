package com.example.reelcheck.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class AudioClipData(
    val durationSeconds: Int,
    val sampleRate: Int,
    val channels: Int,
    val pcmByteCount: Int,
    val wavBytes: ByteArray,
    val rmsAmplitude: Double,
    val isSilent: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe in-memory circular buffer for raw PCM bytes.
 * Never writes to disk (Rule 4).
 */
class CircularPcmBuffer(val capacityBytes: Int) {
    private val buffer = ByteArray(capacityBytes)
    private var writePos = 0
    private var totalBytesWritten = 0L
    private val lock = Any()

    fun write(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            for (i in 0 until length) {
                buffer[writePos] = data[offset + i]
                writePos = (writePos + 1) % capacityBytes
                totalBytesWritten++
            }
        }
    }

    fun readRecent(bytesToRead: Int): ByteArray {
        synchronized(lock) {
            val available = minOf(totalBytesWritten, capacityBytes.toLong()).toInt()
            val toFetch = minOf(bytesToRead, available)
            if (toFetch <= 0) return ByteArray(0)

            val result = ByteArray(toFetch)
            var startPos = (writePos - toFetch) % capacityBytes
            if (startPos < 0) startPos += capacityBytes

            for (i in 0 until toFetch) {
                result[i] = buffer[(startPos + i) % capacityBytes]
            }
            return result
        }
    }

    fun clear() {
        synchronized(lock) {
            writePos = 0
            totalBytesWritten = 0L
            buffer.fill(0)
        }
    }
}

/**
 * Manages an AudioPlaybackCapture session using an active MediaProjection.
 * Maintains an in-memory rolling buffer (e.g. 5-10 seconds) of internal media audio.
 *
 * Adheres strictly to Rule 4:
 * Audio is processed entirely in RAM, never persisted to disk or external storage.
 */
class AudioPlaybackCaptureSession(
    private val mediaProjection: MediaProjection,
    val sampleRate: Int = 16000, // 16kHz Mono 16-bit PCM (standard for speech / AI processing)
    val rollingBufferSeconds: Int = 10
) {
    companion object {
        private const val TAG = "AudioCaptureSession"
        const val BYTES_PER_SAMPLE = 2 // 16-bit PCM
        const val CHANNELS = 1          // Mono
    }

    private val bytesPerSecond = sampleRate * CHANNELS * BYTES_PER_SAMPLE
    private val rollingBuffer = CircularPcmBuffer(bytesPerSecond * rollingBufferSeconds)

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null

    init {
        initCapture()
    }

    @SuppressLint("MissingPermission")
    private fun initCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "AudioPlaybackCapture requires Android 10 (API 29)+")
            return
        }

        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val internalBufferSize = maxOf(minBufferSize, bytesPerSecond * 2)

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(internalBufferSize)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize (state: ${record.state})")
                record.release()
                return
            }

            audioRecord = record
            startRecordingLoop()
            Log.i(TAG, "AudioPlaybackCaptureSession successfully initialized ($sampleRate Hz, Mono, 16-bit)")
        } catch (e: Throwable) {
            Log.e(TAG, "Exception initializing AudioPlaybackCaptureSession", e)
            release()
        }
    }

    private fun startRecordingLoop() {
        val record = audioRecord ?: return
        try {
            record.startRecording()
            isRecording = true

            recordingThread = Thread({
                val tempBuffer = ByteArray(1024)
                while (isRecording) {
                    val bytesRead = record.read(tempBuffer, 0, tempBuffer.size)
                    if (bytesRead > 0) {
                        rollingBuffer.write(tempBuffer, 0, bytesRead)
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $bytesRead")
                        break
                    }
                }
            }, "ReelCheck-AudioCaptureThread").apply {
                isDaemon = true
                start()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start AudioRecord recording", e)
        }
    }

    /**
     * Grabs the last [seconds] of captured audio from the rolling buffer in memory,
     * packages it as an in-memory WAV byte array, and analyzes RMS amplitude.
     */
    fun getRecentAudioClip(seconds: Int = 5): AudioClipData {
        val requestedBytes = seconds * bytesPerSecond
        val pcm = rollingBuffer.readRecent(requestedBytes)
        val rms = calculateRms(pcm)
        // A noise floor threshold of 5.0 for 16-bit PCM indicates silence (or opted-out stream)
        val isSilent = rms < 5.0 || pcm.isEmpty()

        val wav = if (pcm.isNotEmpty()) {
            buildWavBytes(pcm, sampleRate, CHANNELS, 16)
        } else {
            ByteArray(0)
        }

        Log.i(
            TAG,
            "Audio clip captured: ${pcm.size} PCM bytes (${wav.size} WAV bytes in RAM), RMS: %.2f, silent: $isSilent".format(rms)
        )

        return AudioClipData(
            durationSeconds = seconds,
            sampleRate = sampleRate,
            channels = CHANNELS,
            pcmByteCount = pcm.size,
            wavBytes = wav,
            rmsAmplitude = rms,
            isSilent = isSilent
        )
    }

    fun isCapturing(): Boolean = isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun release() {
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (e: Throwable) {
            // Ignore on cleanup
        }
        audioRecord?.release()
        audioRecord = null
        rollingBuffer.clear()
        Log.i(TAG, "AudioPlaybackCaptureSession released")
    }

    private fun calculateRms(pcmData: ByteArray): Double {
        if (pcmData.isEmpty()) return 0.0
        var sumSquares = 0.0
        val sampleCount = pcmData.size / BYTES_PER_SAMPLE
        for (i in 0 until sampleCount) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            sumSquares += (sample.toDouble() * sample.toDouble())
        }
        return sqrt(sumSquares / sampleCount)
    }

    /**
     * Generates a standard 44-byte RIFF/WAVE header + PCM payload in memory.
     */
    private fun buildWavBytes(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val subChunk2Size = pcmData.size
        val chunkSize = 36 + subChunk2Size
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()

        val output = ByteArrayOutputStream(44 + subChunk2Size)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        // "fmt " sub-chunk
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // SubChunk1Size (16 for PCM)
        header.putShort(1.toShort()) // AudioFormat (1 for PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())

        // "data" sub-chunk
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(subChunk2Size)

        output.write(header.array())
        output.write(pcmData)

        return output.toByteArray()
    }
}
