package com.example.reelcheck.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.reelcheck.MainActivity
import com.example.reelcheck.R
import com.example.reelcheck.accessibility.ReelAccessibilityService
import com.example.reelcheck.capture.ScreenshotSession
import com.example.reelcheck.capture.ScreenshotData
import com.example.reelcheck.network.FactCheckApiClient
import com.example.reelcheck.network.FactCheckRequest
import com.example.reelcheck.network.FactCheckResponse
import com.example.reelcheck.ui.card.OverlayCardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val TAG = "ReelCheck-Overlay"
        const val CHANNEL_ID = "reelcheck_overlay_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.reelcheck.action.START_OVERLAY"
        const val ACTION_STOP = "com.example.reelcheck.action.STOP_OVERLAY"

        private val _isOverlayRunning = MutableStateFlow(false)
        val isOverlayRunning = _isOverlayRunning.asStateFlow()

        private val _bubbleTapEvents = MutableSharedFlow<Long>(extraBufferCapacity = 10)
        val bubbleTapEvents = _bubbleTapEvents.asSharedFlow()

        private var projectionResultCode: Int = 0
        private var projectionIntent: Intent? = null
        private var activeMediaProjection: MediaProjection? = null

        private val _latestScreenshot = MutableStateFlow<ScreenshotData?>(null)
        val latestScreenshot = _latestScreenshot.asStateFlow()

        private val _latestAudioClip = MutableStateFlow<com.example.reelcheck.capture.AudioClipData?>(null)
        val latestAudioClip = _latestAudioClip.asStateFlow()

        private val _latestFactCheck = MutableStateFlow<FactCheckResponse?>(null)
        val latestFactCheck = _latestFactCheck.asStateFlow()

        private val _isFactChecking = MutableStateFlow(false)
        val isFactChecking = _isFactChecking.asStateFlow()

        private val _isMediaProjectionReady = MutableStateFlow(false)
        val isMediaProjectionReady = _isMediaProjectionReady.asStateFlow()

        @SuppressLint("StaticFieldLeak")
        private var instance: OverlayService? = null

        fun dismissCard() {
            instance?.cardManager?.dismiss()
        }

        fun checkCurrentScreenNow() {
            instance?.triggerFactCheckFlow()
        }

        fun captureAudioNow(seconds: Int = 5): com.example.reelcheck.capture.AudioClipData? {
            val clip = instance?.getOrCreateAudioSession()?.getRecentAudioClip(seconds)
            if (clip != null) {
                _latestAudioClip.value = clip
            }
            return clip
        }

        fun setMediaProjectionPermission(resultCode: Int, intent: Intent) {
            projectionResultCode = resultCode
            projectionIntent = intent
            _isMediaProjectionReady.value = true
            instance?.updateForegroundServiceType()
            Log.i(TAG, "MediaProjection permission saved in OverlayService")
        }

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var bubbleContainer: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var cardManager: OverlayCardManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cardManager = OverlayCardManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received, stopping OverlayService")
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()

        if (bubbleContainer == null) {
            createAndAttachBubble()
        }

        startSmartPrefetchWatcher()

        _isOverlayRunning.value = true
        return START_STICKY
    }

    fun updateForegroundServiceType() {
        startInForeground()
    }

    private fun startInForeground() {
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ReelCheck Bubble Active")
            .setContentText("Floating bubble is ready over reels. Tap to fact-check.")
            .setSmallIcon(R.drawable.ic_fact_check)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Bubble", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (_isMediaProjectionReady.value) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ReelCheck Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status while ReelCheck floating bubble is active over screen"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private var screenshotSession: com.example.reelcheck.capture.ScreenshotSession? = null
    private var audioSession: com.example.reelcheck.capture.AudioPlaybackCaptureSession? = null

    private fun getOrCreateMediaProjection(): MediaProjection? {
        if (activeMediaProjection != null) return activeMediaProjection
        val intent = projectionIntent ?: return null
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val mp = mpManager?.getMediaProjection(projectionResultCode, intent) ?: return null
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system")
                screenshotSession?.release()
                screenshotSession = null
                audioSession?.release()
                audioSession = null
                activeMediaProjection = null
            }
        }, Handler(Looper.getMainLooper()))
        activeMediaProjection = mp
        return mp
    }

    private fun getOrCreateScreenshotSession(): com.example.reelcheck.capture.ScreenshotSession? {
        if (screenshotSession != null) return screenshotSession
        val mp = getOrCreateMediaProjection() ?: return null
        val session = com.example.reelcheck.capture.ScreenshotSession(mp, resources.displayMetrics)
        screenshotSession = session
        return session
    }

    fun getOrCreateAudioSession(): com.example.reelcheck.capture.AudioPlaybackCaptureSession? {
        if (audioSession != null) return audioSession
        val mp = getOrCreateMediaProjection() ?: return null
        val session = com.example.reelcheck.capture.AudioPlaybackCaptureSession(mp)
        audioSession = session
        return session
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createAndAttachBubble() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val sizePx = (58 * density).toInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - sizePx - (16 * density).toInt()
            y = screenHeight / 3
        }
        layoutParams = params

        // Create bubble view programmatically
        val container = FrameLayout(this).apply {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#8B5CF6"), // Bright violet
                    Color.parseColor("#4F46E5")  // Indigo
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                setStroke((3 * density).toInt(), Color.WHITE)
            }
            background = shape
            elevation = 16 * density

            val iconView = ImageView(context).apply {
                setImageDrawable(androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_fact_check))
                val iconPadding = (12 * density).toInt()
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                setColorFilter(Color.WHITE)
            }
            addView(
                iconView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = 10 * density

        container.setOnTouchListener { _, event ->
            val curParams = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = curParams.x
                    initialY = curParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val maxX = (resources.displayMetrics.widthPixels - sizePx).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - sizePx).coerceAtLeast(0)
                        curParams.x = (initialX + dx).coerceIn(0, maxX)
                        curParams.y = (initialY + dy).coerceIn(0, maxY)
                        windowManager?.updateViewLayout(container, curParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onBubbleTapped(curParams.x, curParams.y)
                    }
                    true
                }
                else -> false
            }
        }

        bubbleContainer = container
        windowManager?.addView(container, params)
        Log.i(TAG, "Floating bubble successfully attached to WindowManager at (${params.x}, ${params.y})")
    }

    private fun onBubbleTapped(x: Int, y: Int) {
        val timestamp = System.currentTimeMillis()
        Log.i(TAG, ">>> REELCHECK BUBBLE TAPPED! Position: ($x, $y) at $timestamp <<<")
        _bubbleTapEvents.tryEmit(timestamp)

        // If card is already showing, toggle/dismiss it
        if (cardManager?.isShowing() == true) {
            cardManager?.dismiss()
            return
        }

        triggerFactCheckFlow()
    }

    fun triggerFactCheckFlow() {
        // 1. Accessibility extraction (Spike 2)
        val extracted = ReelAccessibilityService.extractCurrentScreen()
        val userText = extracted?.username ?: "unknown"
        val captionText = extracted?.caption ?: ""
        val onScreenText = extracted?.onScreenTexts?.joinToString("\n") ?: ""

        // 2. Audio Capture from in-memory rolling buffer (Spike 4)
        val audioClip = getOrCreateAudioSession()?.getRecentAudioClip(5)
        if (audioClip != null) {
            _latestAudioClip.value = audioClip
            Log.i(
                TAG,
                "Audio captured from rolling buffer: ${audioClip.pcmByteCount} PCM bytes (${audioClip.wavBytes.size} WAV bytes in RAM), RMS=${audioClip.rmsAmplitude.toInt()}, silent=${audioClip.isSilent}"
            )
        }

        // 3. MediaProjection Screenshot (Spike 3)
        val session = getOrCreateScreenshotSession()
        if (session != null) {
            bubbleContainer?.visibility = View.INVISIBLE
            session.captureNow { data ->
                bubbleContainer?.visibility = View.VISIBLE
                _latestScreenshot.value = data
                val kb = data.jpegBytes.size / 1024
                Log.i(TAG, "Screenshot in RAM: ${data.width}x${data.height} ($kb KB)")

                // 4. Show Loading Card
                cardManager?.showLoading(userText, captionText)
                _isFactChecking.value = true

                // 5. Send to backend via SSE Streaming (Milestone M3)
                serviceScope.launch {
                    val t0 = System.currentTimeMillis()
                    val jpegBase64 = Base64.encodeToString(data.jpegBytes, Base64.NO_WRAP)
                    val audioBase64 = if (audioClip != null && !audioClip.isSilent) {
                        Base64.encodeToString(audioClip.wavBytes, Base64.NO_WRAP)
                    } else null

                    val request = FactCheckRequest(
                        username = userText,
                        caption = captionText,
                        onScreenText = onScreenText,
                        screenshotJpegBase64 = jpegBase64,
                        audioWavBase64 = audioBase64
                    )

                    FactCheckApiClient.checkReelStream(
                        request = request,
                        onClaims = { claimsEvent ->
                            val tClaims = System.currentTimeMillis() - t0
                            Log.i(TAG, "Streaming: claims received in ${tClaims}ms (count: ${claimsEvent.claims.size}, cached: ${claimsEvent.cached})")
                            cardManager?.showStreamingClaims(
                                claims = claimsEvent.claims,
                                notCheckable = claimsEvent.notCheckable,
                                cached = claimsEvent.cached
                            )
                        },
                        onVerdict = { verdictEvent ->
                            val tVerdict = System.currentTimeMillis() - t0
                            Log.i(TAG, "Streaming: verdict for claim #${verdictEvent.claimIndex} received in ${tVerdict}ms (${verdictEvent.claim.verdict})")
                            cardManager?.updateStreamingVerdict(verdictEvent.claimIndex, verdictEvent.claim)
                        },
                        onComplete = { completeEvent ->
                            val tTotal = System.currentTimeMillis() - t0
                            Log.i(TAG, "Streaming: complete in ${tTotal}ms (server reported ${completeEvent.totalDurationMs}ms)")
                            _isFactChecking.value = false
                            cardManager?.finishStreaming(completeEvent.totalDurationMs)
                        },
                        onError = { err ->
                            _isFactChecking.value = false
                            Log.e(TAG, "Stream fact check failed: ${err.message}", err)
                            cardManager?.showError(err.message ?: "Failed to reach ReelCheck backend") {
                                triggerFactCheckFlow()
                            }
                        }
                    )
                }
            }
        } else {
            val msg = "Screen capture not authorized. Open ReelCheck app to authorize MediaProjection."
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            Log.w(TAG, "Bubble tapped but MediaProjection is not authorized")
        }
    }

    private var prefetchJob: Job? = null
    private var lastPrefetchedCaption: String? = null

    private fun startSmartPrefetchWatcher() {
        prefetchJob?.cancel()
        prefetchJob = serviceScope.launch {
            while (isActive) {
                delay(3500)
                if (_isFactChecking.value || cardManager?.isShowing() == true) continue
                val extracted = ReelAccessibilityService.extractCurrentScreen()
                val caption = extracted?.caption?.trim() ?: ""
                val username = extracted?.username ?: ""
                if (caption.length > 20 && caption != lastPrefetchedCaption) {
                    val session = screenshotSession
                    if (session != null) {
                        Log.i(TAG, "Smart Prefetch: Reel on screen for >3.5s ($username: ${caption.take(30)}...). Pre-fetching check into backend cache...")
                        lastPrefetchedCaption = caption
                        session.captureNow { data ->
                            serviceScope.launch {
                                val jpegBase64 = Base64.encodeToString(data.jpegBytes, Base64.NO_WRAP)
                                val request = FactCheckRequest(
                                    username = username,
                                    caption = caption,
                                    onScreenText = extracted?.onScreenTexts?.joinToString("\n") ?: "",
                                    screenshotJpegBase64 = jpegBase64,
                                    audioWavBase64 = null
                                )
                                val res = FactCheckApiClient.checkReel(request)
                                res.onSuccess {
                                    Log.i(TAG, "Smart Prefetch: Successfully cached reel check for $username into backend cache (cached=${it.cached})")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        _isOverlayRunning.value = false
        prefetchJob?.cancel()
        prefetchJob = null
        serviceScope.cancel()
        cardManager?.dismiss()
        cardManager = null
        audioSession?.release()
        audioSession = null
        screenshotSession?.release()
        screenshotSession = null
        activeMediaProjection?.stop()
        activeMediaProjection = null
        bubbleContainer?.let { view ->
            windowManager?.removeView(view)
            bubbleContainer = null
            Log.i(TAG, "Floating bubble view removed from WindowManager")
        }
    }
}

