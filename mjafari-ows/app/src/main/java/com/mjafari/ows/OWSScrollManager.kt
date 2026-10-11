package com.mjafari.ows

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Handles scrolling through the OWS form to expose all tasks.
 * Detects end-of-form by comparing node snapshots before/after scroll.
 */
class OWSScrollManager(private val service: OWSAccessibilityService) {

    companion object {
        const val SCROLL_DELAY = 800L         // ms to wait after scroll
        const val MAX_SCROLL_ATTEMPTS = 50    // safety cap
        const val NO_CHANGE_THRESHOLD = 3     // consecutive no-change scrolls = end of form
    }

    private var noChangeCount = 0
    private var scrollAttempts = 0
    private var lastNodeSnapshot = ""

    fun reset() {
        noChangeCount = 0
        scrollAttempts = 0
        lastNodeSnapshot = ""
    }

    /**
     * Perform one scroll down action.
     * Returns true if scroll was performed, false if at end or failed.
     */
    fun scrollDown(): Boolean {
        if (scrollAttempts >= MAX_SCROLL_ATTEMPTS) {
            OWSLogger.warning("Max scroll attempts reached ($MAX_SCROLL_ATTEMPTS)")
            return false
        }
        scrollAttempts++
        OWSStateManager.sendStatus("Scrolling… (attempt $scrollAttempts)")

        // Method 1: Try scrollable node
        val root = service.rootInActiveWindow
        if (root != null) {
            val scrollable = findScrollableNode(root)
            if (scrollable != null) {
                val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(SCROLL_DELAY)
                return scrolled
            }
        }

        // Method 2: Dispatch swipe gesture (swipe up = scroll down)
        return performSwipeUp()
    }

    /**
     * Check if the form has ended (no new content after scrolling).
     */
    fun hasReachedEnd(): Boolean {
        val root = service.rootInActiveWindow ?: return true
        val snapshot = buildNodeSnapshot(root)

        if (snapshot == lastNodeSnapshot) {
            noChangeCount++
            OWSLogger.info("No change after scroll ($noChangeCount/$NO_CHANGE_THRESHOLD)")
        } else {
            noChangeCount = 0
            lastNodeSnapshot = snapshot
        }

        return noChangeCount >= NO_CHANGE_THRESHOLD
    }

    /**
     * Returns the scrollable container node (RecyclerView, ScrollView, WebView, etc.)
     */
    fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * Performs a swipe-up gesture (equivalent to scrolling down the page).
     * Uses GestureDescription API (API 24+).
     */
    private fun performSwipeUp(): Boolean {
        val displayMetrics = service.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val startX = (screenWidth / 2).toFloat()
        val startY = (screenHeight * 0.75f)
        val endY = (screenHeight * 0.25f)

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(startX, endY)

        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                result = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                result = false
                latch.countDown()
            }
        }, null)

        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        Thread.sleep(SCROLL_DELAY)
        return result
    }

    /**
     * Build a lightweight snapshot string of current visible node texts.
     * Used to detect if scroll actually changed content.
     */
    private fun buildNodeSnapshot(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectTexts(root, sb, depth = 0, maxDepth = 8)
        return sb.toString()
    }

    private fun collectTexts(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) {
            sb.append(text).append("|")
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        sb.append(bounds.top).append(",")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, sb, depth + 1, maxDepth)
        }
    }
}
