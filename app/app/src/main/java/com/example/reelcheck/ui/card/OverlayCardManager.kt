package com.example.reelcheck.ui.card

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import com.example.reelcheck.R
import com.example.reelcheck.network.FactCheckClaim
import com.example.reelcheck.network.FactCheckResponse
import com.example.reelcheck.network.Verdict
import kotlin.math.max

class OverlayCardManager(private val context: Context) {

    companion object {
        private const val TAG = "ReelCheck-Card"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cardRootView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isCardShowing = false

    private val claimContainers = mutableMapOf<Int, LinearLayout>()
    private val claimBadges = mutableMapOf<Int, TextView>()
    private val claimDetails = mutableMapOf<Int, LinearLayout>()
    private var headerContainerView: LinearLayout? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    fun isShowing(): Boolean = isCardShowing

    fun dismiss() {
        mainHandler.post {
            val root = cardRootView ?: return@post
            if (!isCardShowing) return@post

            // Animate card down
            val curY = windowParams?.y ?: dp(24)
            val animator = ValueAnimator.ofInt(curY, -dp(400)).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val animatedY = va.animatedValue as Int
                    windowParams?.y = animatedY
                    if (root.isAttachedToWindow) {
                        try {
                            windowManager.updateViewLayout(root, windowParams)
                        } catch (_: Exception) {}
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeCardView()
                    }
                })
            }
            animator.start()
        }
    }

    private fun removeCardView() {
        cardRootView?.let { v ->
            if (v.isAttachedToWindow) {
                try {
                    windowManager.removeView(v)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing card view: ${e.message}")
                }
            }
            cardRootView = null
            isCardShowing = false
            Log.i(TAG, "Result card dismissed")
        }
    }

    fun showLoading(username: String, captionSnippet: String) {
        mainHandler.post {
            ensureCardAttached()
            val contentContainer = cardRootView?.findViewById<LinearLayout>(R_ID_CONTENT_CONTAINER) ?: return@post
            contentContainer.removeAllViews()

            // Header
            addHeaderView(contentContainer, title = "ReelCheck", cached = false)

            // Loading layout
            val loadingLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(20), dp(16), dp(20))
            }

            val progressBar = ProgressBar(context).apply {
                isIndeterminate = true
            }
            loadingLayout.addView(progressBar, LinearLayout.LayoutParams(dp(44), dp(44)))

            val loadingText = TextView(context).apply {
                text = "Fact-checking claims with Gemini & Google Search..."
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(14)
                }
                layoutParams = lp
            }
            loadingLayout.addView(loadingText)

            if (username.isNotBlank() || captionSnippet.isNotBlank()) {
                val snippetView = TextView(context).apply {
                    val userText = if (username.isNotBlank()) "@$username: " else ""
                    text = "\"$userText${captionSnippet.take(80)}...\""
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 12f
                    gravity = Gravity.CENTER_HORIZONTAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(8)
                    }
                    layoutParams = lp
                }
                loadingLayout.addView(snippetView)
            }

            contentContainer.addView(loadingLayout)
            animateCardIn()
        }
    }

    fun showResult(response: FactCheckResponse) {
        mainHandler.post {
            ensureCardAttached()
            val contentContainer = cardRootView?.findViewById<LinearLayout>(R_ID_CONTENT_CONTAINER) ?: return@post
            contentContainer.removeAllViews()

            // Header
            addHeaderView(contentContainer, title = "Fact Check Result", cached = response.cached)

            // Body
            if (response.notCheckable != null) {
                // Not checkable state
                val notCheckableLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(16))
                }

                val badge = createBadgeView("OPINION / NON-FACTUAL", "#64748B", Color.WHITE)
                notCheckableLayout.addView(badge)

                val explanation = TextView(context).apply {
                    text = response.notCheckable
                    setTextColor(Color.parseColor("#E2E8F0"))
                    textSize = 13.5f
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(10)
                    }
                    layoutParams = lp
                }
                notCheckableLayout.addView(explanation)

                contentContainer.addView(notCheckableLayout)
            } else if (response.claims.isEmpty()) {
                val emptyLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                }
                val emptyText = TextView(context).apply {
                    text = "No factual claims identified in this reel."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 14f
                }
                emptyLayout.addView(emptyText)
                contentContainer.addView(emptyLayout)
            } else {
                // Scrollable claims list
                val scrollView = ScrollView(context).apply {
                    val displayMetrics = context.resources.displayMetrics
                    val maxScrollHeight = (displayMetrics.heightPixels * 0.45f).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        // Max height constraint
                    }
                }

                val claimsLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(8), dp(14), dp(14))
                }

                response.claims.forEachIndexed { index, claim ->
                    addClaimView(claimsLayout, claim, isLast = index == response.claims.lastIndex)
                }

                scrollView.addView(claimsLayout)
                contentContainer.addView(scrollView)
            }

            animateCardIn()
        }
    }

    fun showStreamingClaims(claims: List<String>, notCheckable: String?, cached: Boolean) {
        mainHandler.post {
            ensureCardAttached()
            val contentContainer = cardRootView?.findViewById<LinearLayout>(R_ID_CONTENT_CONTAINER) ?: return@post
            contentContainer.removeAllViews()
            claimContainers.clear()
            claimBadges.clear()
            claimDetails.clear()

            // Header
            addHeaderView(contentContainer, title = "Fact Check Result", cached = cached)

            if (notCheckable != null) {
                val notCheckableLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(16))
                }
                val badge = createBadgeView("OPINION / NON-FACTUAL", "#64748B", Color.WHITE)
                notCheckableLayout.addView(badge)
                val explanation = TextView(context).apply {
                    text = notCheckable
                    setTextColor(Color.parseColor("#E2E8F0"))
                    textSize = 13.5f
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                    layoutParams = lp
                }
                notCheckableLayout.addView(explanation)
                contentContainer.addView(notCheckableLayout)
            } else if (claims.isEmpty()) {
                val emptyLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                }
                val emptyText = TextView(context).apply {
                    text = "No factual claims identified in this reel."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 14f
                }
                emptyLayout.addView(emptyText)
                contentContainer.addView(emptyLayout)
            } else {
                val scrollView = ScrollView(context).apply {
                    val displayMetrics = context.resources.displayMetrics
                    val maxScrollHeight = (displayMetrics.heightPixels * 0.45f).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val claimsLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(8), dp(14), dp(14))
                }

                claims.forEachIndexed { index, claimTitle ->
                    val claimContainer = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        val bg = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(12).toFloat()
                            setColor(Color.parseColor("#0F172A"))
                            setStroke(dp(1), Color.parseColor("#1E293B"))
                        }
                        background = bg
                        setPadding(dp(12), dp(10), dp(12), dp(12))
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            if (index != claims.lastIndex) bottomMargin = dp(10)
                        }
                        layoutParams = lp
                    }

                    // Verifying badge
                    val verifyingBadge = createBadgeView("⏳ VERIFYING...", "#312E81", Color.parseColor("#A5B4FC"))
                    claimContainer.addView(verifyingBadge)

                    // Claim title
                    val claimTextView = TextView(context).apply {
                        text = claimTitle
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(8) }
                        layoutParams = lp
                    }
                    claimContainer.addView(claimTextView)

                    // Details container for explanation & sources
                    val detailsLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    claimContainer.addView(detailsLayout)

                    claimContainers[index] = claimContainer
                    claimBadges[index] = verifyingBadge
                    claimDetails[index] = detailsLayout

                    claimsLayout.addView(claimContainer)
                }

                scrollView.addView(claimsLayout)
                contentContainer.addView(scrollView)
            }

            animateCardIn()
        }
    }

    fun updateStreamingVerdict(claimIndex: Int, claim: FactCheckClaim) {
        mainHandler.post {
            val badge = claimBadges[claimIndex]
            val details = claimDetails[claimIndex]

            if (badge != null) {
                val (verdictLabel, verdictBgColor, verdictTextColor) = when (claim.verdict) {
                    Verdict.SUPPORTED -> Triple("✓ SUPPORTED", "#064E3B", "#34D399")
                    Verdict.DISPUTED -> Triple("✗ DISPUTED", "#4C0519", "#FB7185")
                    Verdict.UNVERIFIABLE -> Triple("? UNVERIFIABLE", "#451A03", "#FBBF24")
                }
                badge.text = verdictLabel
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor(verdictBgColor))
                }
                badge.background = bg
                badge.setTextColor(Color.parseColor(verdictTextColor))
            }

            if (details != null) {
                details.removeAllViews()

                // Explanation
                val explanationText = TextView(context).apply {
                    text = claim.explanation
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 12.5f
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                    layoutParams = lp
                }
                details.addView(explanationText)

                // Sources
                if (claim.sources.isNotEmpty()) {
                    val sourcesHeader = TextView(context).apply {
                        text = "Sources from Google Search:"
                        setTextColor(Color.parseColor("#64748B"))
                        textSize = 11f
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(10) }
                        layoutParams = lp
                    }
                    details.addView(sourcesHeader)

                    val sourcesLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) }
                        layoutParams = lp
                    }

                    claim.sources.forEach { source ->
                        val sourceBtn = TextView(context).apply {
                            text = "🔗 ${source.title.take(45)}"
                            setTextColor(Color.parseColor("#818CF8"))
                            textSize = 11.5f
                            val lp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = dp(3) }
                            layoutParams = lp
                            setOnClickListener {
                                openUrlInBrowser(source.url)
                            }
                        }
                        sourcesLayout.addView(sourceBtn)
                    }
                    details.addView(sourcesLayout)
                }

                // Smooth fade-in
                details.alpha = 0f
                details.animate().alpha(1f).setDuration(250).start()
            }
        }
    }

    fun finishStreaming(durationMs: Long) {
        mainHandler.post {
            val header = headerContainerView ?: return@post
            val sec = (durationMs / 100.0).let { kotlin.math.round(it) / 10.0 }
            val timeBadge = createBadgeView("⏱ ${sec}s", "#1E293B", Color.parseColor("#94A3B8")).apply {
                val lp = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.rightMargin = dp(8)
                layoutParams = lp
            }
            // Insert before close button (which is last)
            val insertIdx = (header.childCount - 1).coerceAtLeast(0)
            header.addView(timeBadge, insertIdx)
        }
    }

    fun showError(errorMessage: String, onRetry: () -> Unit) {
        mainHandler.post {
            ensureCardAttached()
            val contentContainer = cardRootView?.findViewById<LinearLayout>(R_ID_CONTENT_CONTAINER) ?: return@post
            contentContainer.removeAllViews()

            val isQuota = errorMessage.contains("quota", ignoreCase = true) || errorMessage.contains("budget", ignoreCase = true)
            val isRateLimit = errorMessage.contains("rate limit", ignoreCase = true)
            val isTimeout = errorMessage.contains("timed out", ignoreCase = true) || errorMessage.contains("timeout", ignoreCase = true)
            val isOffline = errorMessage.contains("cannot connect", ignoreCase = true) || errorMessage.contains("unreachable", ignoreCase = true)

            val (headerTitle, badgeText, badgeColor, textColor) = when {
                isQuota -> Quadruple("Budget Cap Reached", "QUOTA EXCEEDED", "#D97706", Color.WHITE)
                isRateLimit -> Quadruple("Rate Limited", "TOO MANY REQUESTS", "#EA580C", Color.WHITE)
                isTimeout -> Quadruple("Request Timed Out", "TIMEOUT", "#BE123C", Color.WHITE)
                isOffline -> Quadruple("Backend Offline", "UNREACHABLE", "#4B5563", Color.WHITE)
                else -> Quadruple("Check Failed", "ERROR", "#DC2626", Color.WHITE)
            }

            addHeaderView(contentContainer, title = headerTitle, cached = false)

            val errorLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(16))
            }

            val badge = createBadgeView(badgeText, badgeColor, textColor)
            errorLayout.addView(badge)

            val errorMsg = TextView(context).apply {
                text = errorMessage
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 13f
                setLineSpacing(dp(2).toFloat(), 1.0f)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
                layoutParams = lp
            }
            errorLayout.addView(errorMsg)

            val retryBtn = Button(context).apply {
                text = "Retry Check"
                setBackgroundColor(Color.parseColor("#4F46E5"))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(14)
                }
                layoutParams = lp
                setOnClickListener {
                    onRetry()
                }
            }
            errorLayout.addView(retryBtn)

            contentContainer.addView(errorLayout)
            animateCardIn()
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun addHeaderView(parent: LinearLayout, title: String, cached: Boolean) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(6))
        }
        headerContainerView = header

        // Title
        val titleText = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleText)

        // Cached badge
        if (cached) {
            val cachedBadge = createBadgeView("⚡ CACHED", "#312E81", Color.parseColor("#A5B4FC")).apply {
                val lp = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.rightMargin = dp(10)
                layoutParams = lp
            }
            header.addView(cachedBadge)
        }

        // Close button
        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
            }
        }
        header.addView(closeButton)

        parent.addView(header)

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        parent.addView(divider)
    }

    private fun addClaimView(parent: LinearLayout, claim: FactCheckClaim, isLast: Boolean) {
        val claimContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            background = bg
            setPadding(dp(12), dp(10), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (!isLast) bottomMargin = dp(10)
            }
            layoutParams = lp
        }

        // Verdict Badge
        val (verdictLabel, verdictBgColor, verdictTextColor) = when (claim.verdict) {
            Verdict.SUPPORTED -> Triple("✓ SUPPORTED", "#064E3B", "#34D399")
            Verdict.DISPUTED -> Triple("✗ DISPUTED", "#4C0519", "#FB7185")
            Verdict.UNVERIFIABLE -> Triple("? UNVERIFIABLE", "#451A03", "#FBBF24")
        }

        val verdictBadge = createBadgeView(verdictLabel, verdictBgColor, Color.parseColor(verdictTextColor))
        claimContainer.addView(verdictBadge)

        // Claim text
        val claimText = TextView(context).apply {
            text = claim.claim
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
        }
        claimContainer.addView(claimText)

        // Explanation text
        val explanationText = TextView(context).apply {
            text = claim.explanation
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12.5f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            layoutParams = lp
        }
        claimContainer.addView(explanationText)

        // Sources
        if (claim.sources.isNotEmpty()) {
            val sourcesHeader = TextView(context).apply {
                text = "Sources from Google Search:"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(10)
                }
                layoutParams = lp
            }
            claimContainer.addView(sourcesHeader)

            val sourcesLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4)
                }
                layoutParams = lp
            }

            claim.sources.forEach { source ->
                val sourceBtn = TextView(context).apply {
                    text = "🔗 ${source.title.take(45)}"
                    setTextColor(Color.parseColor("#818CF8"))
                    textSize = 11.5f
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(3)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        openUrlInBrowser(source.url)
                    }
                }
                sourcesLayout.addView(sourceBtn)
            }
            claimContainer.addView(sourcesLayout)
        }

        parent.addView(claimContainer)
    }

    private fun openUrlInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open source URL: $url", e)
            Toast.makeText(context, "Could not open URL: $url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createBadgeView(text: String, bgColorHex: String, textColor: Int): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor(bgColorHex))
            }
            background = bg
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureCardAttached() {
        if (cardRootView != null && isCardShowing) return

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val cardWidth = (screenWidth * 0.94f).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        windowParams = params

        // Create main container
        val outerContainer = FrameLayout(context).apply {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#18181B")) // Zinc-900
                setStroke(dp(1.5f.toInt()), Color.parseColor("#3F3F46")) // Zinc-700
            }
            background = shape
            elevation = dp(24).toFloat()
        }

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            id = R_ID_OUTER_LAYOUT
        }

        // Top drag handle pill for swipe-down-to-dismiss gesture
        val handlePill = View(context).apply {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(Color.parseColor("#52525B"))
            }
            background = shape
            val lp = LinearLayout.LayoutParams(dp(48), dp(6)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
            layoutParams = lp
        }
        innerLayout.addView(handlePill)

        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            id = R_ID_CONTENT_CONTAINER
        }
        innerLayout.addView(contentContainer)

        outerContainer.addView(innerLayout)

        // Setup swipe-down dismiss gesture on the top handle and card
        var startY = 0f
        var isDragging = false
        val dismissThreshold = dp(60)

        handlePill.setOnTouchListener { _, event ->
            val curParams = windowParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startY
                    if (dy > dp(8)) {
                        isDragging = true
                        curParams.y = max(dp(24) - dy.toInt(), -dp(300))
                        try {
                            windowManager.updateViewLayout(outerContainer, curParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = event.rawY - startY
                    if (isDragging && dy > dismissThreshold) {
                        dismiss()
                    } else if (isDragging) {
                        curParams.y = dp(24)
                        try {
                            windowManager.updateViewLayout(outerContainer, curParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }

        cardRootView = outerContainer
        try {
            windowManager.addView(outerContainer, params)
            isCardShowing = true
            Log.i(TAG, "Result card view attached to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach result card to WindowManager: ${e.message}", e)
        }
    }

    private fun animateCardIn() {
        val root = cardRootView ?: return
        val targetY = dp(24)
        val animator = ValueAnimator.ofInt(-dp(300), targetY).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val animatedY = va.animatedValue as Int
                windowParams?.y = animatedY
                if (root.isAttachedToWindow) {
                    try {
                        windowManager.updateViewLayout(root, windowParams)
                    } catch (_: Exception) {}
                }
            }
        }
        animator.start()
    }

    private val R_ID_OUTER_LAYOUT = 0x7F0A0001
    private val R_ID_CONTENT_CONTAINER = 0x7F0A0002
}
