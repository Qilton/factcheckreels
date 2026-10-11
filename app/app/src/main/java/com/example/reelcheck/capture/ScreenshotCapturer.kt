package com.example.reelcheck.capture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import java.io.ByteArrayOutputStream

data class ScreenshotData(
    val width: Int,
    val height: Int,
    val jpegBytes: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Manages an active MediaProjection capture session.
 * Keeps VirtualDisplay alive to support multiple on-demand taps across reels
 * without hitting Android 14+ MediaProjection single-use invalidation.
 *
 * Complies with Rule 4: "Process screenshots and audio in memory only.
 * Do not write them to disk, store them, or log them."
 */
class ScreenshotSession(
    private val mediaProjection: MediaProjection,
    private val displayMetrics: DisplayMetrics
) {
    companion object {
        private const val TAG = "ScreenshotSession"
    }

    private val width = displayMetrics.widthPixels
    private val height = displayMetrics.heightPixels
    private val densityDpi = displayMetrics.densityDpi
    private val handler = Handler(Looper.getMainLooper())

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var pendingCallback: ((ScreenshotData) -> Unit)? = null

    init {
        setupSession()
    }

    @SuppressLint("WrongConstant")
    private fun setupSession() {
        try {
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                val cb = pendingCallback
                if (cb != null) {
                    pendingCallback = null
                    try {
                        val result = processImageToJpeg(image, width, height)
                        Log.i(
                            TAG,
                            "Screenshot captured in memory: ${result.width}x${result.height}, size: ${result.jpegBytes.size} bytes (${result.jpegBytes.size / 1024} KB)"
                        )
                        cb(result)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error processing image to JPEG", e)
                    } finally {
                        image.close()
                    }
                } else {
                    image.close()
                }
            }, handler)

            val vd = mediaProjection.createVirtualDisplay(
                "ReelCheck-PersistentDisplay",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            imageReader = reader
            virtualDisplay = vd
            Log.i(TAG, "ScreenshotSession initialized with VirtualDisplay ${width}x$height @ $densityDpi dpi")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed initializing ScreenshotSession", e)
            release()
        }
    }

    fun captureNow(onCaptured: (ScreenshotData) -> Unit) {
        pendingCallback = onCaptured
    }

    private fun processImageToJpeg(image: Image, targetWidth: Int, targetHeight: Int): ScreenshotData {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * targetWidth

        val bitmap = Bitmap.createBitmap(
            targetWidth + rowPadding / pixelStride,
            targetHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        val croppedBitmap = if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, targetWidth, targetHeight)
            bitmap.recycle()
            cropped
        }

        val outputStream = ByteArrayOutputStream()
        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val jpegBytes = outputStream.toByteArray()

        val data = ScreenshotData(
            width = croppedBitmap.width,
            height = croppedBitmap.height,
            jpegBytes = jpegBytes
        )
        croppedBitmap.recycle()
        return data
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        Log.i(TAG, "ScreenshotSession released")
    }
}
