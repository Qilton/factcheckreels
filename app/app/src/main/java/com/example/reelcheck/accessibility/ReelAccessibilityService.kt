package com.example.reelcheck.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.reelcheck.extractor.ExtractedReelInfo
import com.example.reelcheck.extractor.ReelContentExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReelAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ReelAccessibility"

        private var _instance: ReelAccessibilityService? = null
        val instance: ReelAccessibilityService?
            get() = _instance

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected = _isServiceConnected.asStateFlow()

        private val _latestExtracted = MutableStateFlow<ExtractedReelInfo?>(null)
        val latestExtracted = _latestExtracted.asStateFlow()

        fun extractCurrentScreen(): ExtractedReelInfo? {
            val service = _instance
            if (service == null) {
                Log.w(TAG, "extractCurrentScreen: ReelAccessibilityService is not connected")
                return null
            }
            return service.performExtraction()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        _isServiceConnected.value = true
        Log.i(TAG, "ReelAccessibilityService connected and ready to inspect windows")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can optionally monitor window/content events
    }

    fun performExtraction(): ExtractedReelInfo {
        Log.i(TAG, "Starting accessibility extraction of current active window...")
        var rootNode: AccessibilityNodeInfo? = rootInActiveWindow

        // If rootInActiveWindow is null, search through available windows
        if (rootNode == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val windowList = windows
            Log.d(TAG, "rootInActiveWindow was null, searching ${windowList.size} available windows")
            for (window in windowList) {
                val candidate = window.root
                if (candidate != null) {
                    rootNode = candidate
                    break
                }
            }
        }

        val result = ReelContentExtractor.extract(rootNode)
        _latestExtracted.value = result
        return result
    }

    override fun onInterrupt() {
        Log.w(TAG, "ReelAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_instance == this) {
            _instance = null
            _isServiceConnected.value = false
        }
        Log.i(TAG, "ReelAccessibilityService destroyed")
    }
}
