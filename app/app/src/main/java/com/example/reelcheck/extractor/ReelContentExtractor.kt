package com.example.reelcheck.extractor

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

data class NodeSummary(
    val className: String,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?
)

data class ExtractedReelInfo(
    val packageName: String,
    val username: String?,
    val caption: String?,
    val onScreenTexts: List<String>,
    val allNodesCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Dedicated Instagram & Reel content extraction module.
 * Per Rule 3 of project conventions: keep all extraction code in a single module
 * so it is easy to update and patch when app layout changes.
 */
object ReelContentExtractor {

    private const val TAG = "ReelContentExtractor"

    // Known Instagram view IDs
    private val INSTAGRAM_USERNAME_IDS = setOf(
        "com.instagram.android:id/row_feed_photo_profile_name",
        "com.instagram.android:id/reel_viewer_title",
        "com.instagram.android:id/clips_author_name",
        "com.instagram.android:id/feed_more_author_name",
        "com.instagram.android:id/user_name",
        "com.instagram.android:id/title_view"
    )

    private val INSTAGRAM_CAPTION_IDS = setOf(
        "com.instagram.android:id/row_feed_comment_textview_layout",
        "com.instagram.android:id/row_feed_textview_comments",
        "com.instagram.android:id/carousel_image_comment_view",
        "com.instagram.android:id/clips_caption_component",
        "com.instagram.android:id/video_caption",
        "com.instagram.android:id/caption_text_view"
    )

    // Standard UI noise to ignore when gathering content
    private val IGNORED_TEXTS = setOf(
        "like", "likes", "comment", "comments", "share", "send", "save",
        "remix", "more", "audio", "original audio", "back", "search",
        "home", "reels", "profile", "explore", "follow", "following"
    )

    fun extract(rootNode: AccessibilityNodeInfo?): ExtractedReelInfo {
        if (rootNode == null) {
            Log.w(TAG, "extract: rootNode is null")
            return ExtractedReelInfo(
                packageName = "unknown",
                username = null,
                caption = null,
                onScreenTexts = emptyList(),
                allNodesCount = 0
            )
        }

        val packageName = rootNode.packageName?.toString() ?: "unknown"
        val nodes = mutableListOf<NodeSummary>()
        traverse(rootNode, nodes)

        Log.i(TAG, "Traversed accessibility tree for package '$packageName'. Total text nodes found: ${nodes.size}")

        var username: String? = null
        var caption: String? = null
        val onScreenTexts = mutableListOf<String>()

        // 1. Try matching known Instagram view IDs first
        for (node in nodes) {
            val viewId = node.viewId
            val text = node.text ?: node.contentDescription

            if (text.isNullOrBlank()) continue

            if (username == null && viewId != null && INSTAGRAM_USERNAME_IDS.contains(viewId)) {
                username = text.trim()
                Log.d(TAG, "Found username via Instagram viewId '$viewId': '$username'")
                continue
            }

            if (caption == null && viewId != null && INSTAGRAM_CAPTION_IDS.contains(viewId)) {
                caption = text.trim()
                Log.d(TAG, "Found caption via Instagram viewId '$viewId': '$caption'")
                continue
            }
        }

        // 2. Heuristic extraction if view IDs were not matched (e.g. mobile web, mock reel, or updated app)
        if (username == null || caption == null) {
            for (node in nodes) {
                val viewId = node.viewId?.lowercase() ?: ""
                val text = (node.text ?: node.contentDescription)?.trim() ?: continue

                if (text.isBlank()) continue
                if (IGNORED_TEXTS.contains(text.lowercase())) continue

                // Check for username patterns
                if (username == null) {
                    if (viewId.contains("username") || viewId.contains("author") || viewId.contains("profile_name") ||
                        (text.startsWith("@") && text.length in 2..32 && !text.contains(" "))
                    ) {
                        username = text
                        Log.d(TAG, "Found candidate username via heuristics: '$username' (viewId: '$viewId')")
                        continue
                    }
                }

                // Check for caption patterns
                if (caption == null) {
                    if (viewId.contains("caption") || viewId.contains("comment_text") || viewId.contains("description")) {
                        caption = text
                        Log.d(TAG, "Found candidate caption via heuristics: '$caption' (viewId: '$viewId')")
                        continue
                    }
                }
            }
        }

        // 3. Fallback: if caption is still null, pick the longest substantial text
        if (caption == null) {
            val candidate = nodes
                .mapNotNull { it.text ?: it.contentDescription }
                .map { it.trim() }
                .filter { it.length > 20 && it != username && !IGNORED_TEXTS.contains(it.lowercase()) }
                .maxByOrNull { it.length }

            if (candidate != null) {
                caption = candidate
                Log.d(TAG, "Selected longest text as fallback caption: '$caption'")
            }
        }

        // 4. Collect all distinct readable on-screen texts
        for (node in nodes) {
            val text = (node.text ?: node.contentDescription)?.trim()
            if (!text.isNullOrBlank() && !IGNORED_TEXTS.contains(text.lowercase())) {
                if (!onScreenTexts.contains(text)) {
                    onScreenTexts.add(text)
                }
            }
        }

        val result = ExtractedReelInfo(
            packageName = packageName,
            username = username,
            caption = caption,
            onScreenTexts = onScreenTexts,
            allNodesCount = nodes.size
        )

        Log.i(TAG, "=== Extracted Reel Content ===")
        Log.i(TAG, "Package: ${result.packageName}")
        Log.i(TAG, "Username: ${result.username ?: "<not found>"}")
        Log.i(TAG, "Caption: ${result.caption ?: "<not found>"}")
        Log.i(TAG, "On-screen texts (${result.onScreenTexts.size} items): ${result.onScreenTexts.take(5)}")
        Log.i(TAG, "==============================")

        return result
    }

    private fun traverse(node: AccessibilityNodeInfo?, outNodes: MutableList<NodeSummary>, depth: Int = 0) {
        if (node == null || depth > 30) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val viewId = node.viewIdResourceName

        if (!text.isNullOrBlank() || !desc.isNullOrBlank()) {
            outNodes.add(
                NodeSummary(
                    className = node.className?.toString() ?: "unknown",
                    viewId = viewId,
                    text = text,
                    contentDescription = desc
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, outNodes, depth + 1)
        }
    }
}
