package com.mjafari.ows

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

/**
 * Core Accessibility Service for m.jafari ows.
 *
 * Receives commands from MainActivity, processes OWS form fields,
 * scrolls through the form, and stops before Submit.
 */
class OWSAccessibilityService : AccessibilityService() {

    companion object {
        // Singleton instance so MainActivity can communicate with the service
        var instance: OWSAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var processingJob: Job? = null
    private lateinit var formProcessor: OWSFormProcessor
    private lateinit var scrollManager: OWSScrollManager

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        formProcessor = OWSFormProcessor(this)
        scrollManager = OWSScrollManager(this)

        // Configure service dynamically as well (belt-and-suspenders)
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 100
        serviceInfo = info

        OWSLogger.info("OWS Accessibility Service connected")
        OWSStateManager.sendStatus("Service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        processingJob?.cancel()
        OWSLogger.info("OWS Accessibility Service disconnected")
    }

    override fun onInterrupt() {
        processingJob?.cancel()
        OWSLogger.warning("Service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We use rootInActiveWindow for scanning; no special handling needed here
        // Could be used to detect page changes in future enhancements
    }

    // ─── Public API (called from MainActivity) ───────────────────────────────

    /**
     * Start the automated form processing in a coroutine.
     */
    fun startProcessing() {
        if (processingJob?.isActive == true) {
            OWSLogger.warning("Processing already running")
            return
        }

        OWSFieldDetector.clearProcessed()
        scrollManager.reset()
        OWSStateManager.start()
        OWSLogger.info("Starting processing in mode: ${OWSStateManager.currentMode}")
        OWSLogger.info("⚠ Submit will NOT be pressed automatically")

        processingJob = CoroutineScope(Dispatchers.IO).launch {
            runProcessingLoop()
        }
    }

    fun stopProcessing() {
        OWSStateManager.stop()
        processingJob?.cancel()
        OWSLogger.info("Processing stopped by user")
    }

    fun pauseProcessing() {
        OWSStateManager.pause()
        OWSLogger.info("Processing paused")
    }

    fun resumeProcessing() {
        OWSStateManager.resume()
        OWSLogger.info("Processing resumed")
        // If the job was cancelled due to pause, restart
        if (processingJob?.isActive != true) {
            processingJob = CoroutineScope(Dispatchers.IO).launch {
                runProcessingLoop()
            }
        }
    }

    // ─── Main Processing Loop ────────────────────────────────────────────────

    private suspend fun runProcessingLoop() {
        OWSStateManager.sendStatus("Scanning tasks…")

        // Small initial delay for the screen to stabilize
        delay(500)

        var consecutiveEmptyScans = 0
        val maxEmptyScans = 3

        while (OWSStateManager.shouldContinue()) {

            // Handle pause
            if (!OWSStateManager.waitIfPaused()) {
                OWSLogger.info("Processing stopped during pause")
                break
            }

            // Get current accessibility tree
            val root = rootInActiveWindow
            if (root == null) {
                OWSLogger.warning("No active window — waiting…")
                OWSStateManager.sendStatus("Waiting for OWS…")
                delay(1000)
                consecutiveEmptyScans++
                if (consecutiveEmptyScans >= 5) {
                    OWSLogger.error("OWS window not found after 5 attempts")
                    mainHandler.post {
                        OWSStateManager.sendStatus("Please open OWS first")
                    }
                    OWSStateManager.setError()
                    break
                }
                continue
            }
            consecutiveEmptyScans = 0

            // Detect fields in current view
            val fields = OWSFieldDetector.detectFields(root)
            val newFields = fields.filter { !OWSFieldDetector.isAlreadyProcessed(it.uniqueId) }

            if (newFields.isEmpty()) {
                OWSLogger.info("No new fields in current view")

                // Check if we've reached the end
                if (scrollManager.hasReachedEnd()) {
                    OWSLogger.info("End of form reached")
                    OWSStateManager.sendStatus("Form scan complete")
                    break
                }

                // Scroll to find more
                OWSStateManager.sendStatus("Scrolling…")
                val scrolled = scrollManager.scrollDown()
                if (!scrolled) {
                    OWSLogger.warning("Could not scroll further")
                    break
                }
                delay(OWSScrollManager.SCROLL_DELAY)
                continue
            }

            // Process each new field
            for (field in newFields) {
                if (!OWSStateManager.shouldContinue()) break
                if (!OWSStateManager.waitIfPaused()) break

                // CRITICAL: Never touch Submit
                val fieldText = field.node.text?.toString()?.lowercase() ?: ""
                val fieldLabel = field.label.lowercase()
                if (fieldText.contains("submit") || fieldLabel.contains("submit")) {
                    OWSLogger.info("Skipping Submit button — user must press manually")
                    continue
                }

                OWSStateManager.sendStatus("Processing: ${field.label.take(30)}…")
                OWSLogger.info("Processing field [${field.type}]: ${field.label}")

                val success = try {
                    formProcessor.processField(field)
                } catch (e: Exception) {
                    OWSLogger.error("Exception processing field: ${e.message}")
                    OWSStateManager.incrementError()
                    true // continue with next field
                }

                if (!success) {
                    OWSLogger.warning("Field processing failed: ${field.label}")
                }

                // Small delay between fields
                delay(300)
            }

            // After processing visible fields, scroll to next batch
            if (OWSStateManager.shouldContinue()) {
                if (scrollManager.hasReachedEnd()) {
                    OWSLogger.info("No more content after scrolling")
                    break
                }
                OWSStateManager.sendStatus("Scrolling to next tasks…")
                scrollManager.scrollDown()
                delay(OWSScrollManager.SCROLL_DELAY)
            }
        }

        // Finalize
        withContext(Dispatchers.Main) {
            finishProcessing()
        }
    }

    private fun finishProcessing() {
        if (OWSStateManager.currentState == OWSStateManager.AppState.STOPPED) {
            OWSStateManager.sendStatus("Stopped by user")
            OWSLogger.info("=== Operation stopped by user ===")
        } else {
            OWSStateManager.complete()
            OWSStateManager.sendStatus("Completed — please review and submit manually")

            val summary = OWSLogger.getSummary(
                OWSStateManager.currentMode,
                OWSStateManager.processedCount,
                OWSStateManager.skippedCount,
                OWSStateManager.errorCount
            )
            OWSLogger.info(summary)
            OWSLogger.info("⚠ SUBMIT was NOT pressed. Please review the form.")
        }
    }
}
