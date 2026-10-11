package com.mjafari.ows

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton state manager for OWS automation.
 * Tracks execution mode, current state, and counters.
 */
object OWSStateManager {

    enum class ExecutionMode { OK, NOT_OK }

    enum class AppState {
        IDLE, RUNNING, PAUSED, STOPPED, COMPLETED, ERROR
    }

    // Current execution mode (OK or NOT_OK)
    var currentMode: ExecutionMode = ExecutionMode.OK
        private set

    // Current app state
    var currentState: AppState = AppState.IDLE
        private set

    // Atomic flags for thread-safe stop/pause signaling
    val isStopRequested = AtomicBoolean(false)
    val isPauseRequested = AtomicBoolean(false)

    // Counters
    var processedCount: Int = 0
        private set
    var skippedCount: Int = 0
        private set
    var errorCount: Int = 0
        private set

    // Callback for UI updates (runs on main thread via handler)
    var onStateChanged: ((AppState, Int, Int, Int) -> Unit)? = null
    var onStatusMessage: ((String) -> Unit)? = null

    fun setMode(mode: ExecutionMode) {
        // Only allow mode change when not running
        if (currentState != AppState.RUNNING) {
            currentMode = mode
        }
    }

    fun start() {
        isStopRequested.set(false)
        isPauseRequested.set(false)
        processedCount = 0
        skippedCount = 0
        errorCount = 0
        currentState = AppState.RUNNING
        notifyStateChanged()
    }

    fun stop() {
        isStopRequested.set(true)
        currentState = AppState.STOPPED
        notifyStateChanged()
    }

    fun pause() {
        if (currentState == AppState.RUNNING) {
            isPauseRequested.set(true)
            currentState = AppState.PAUSED
            notifyStateChanged()
        }
    }

    fun resume() {
        if (currentState == AppState.PAUSED) {
            isPauseRequested.set(false)
            currentState = AppState.RUNNING
            notifyStateChanged()
        }
    }

    fun complete() {
        currentState = AppState.COMPLETED
        isStopRequested.set(false)
        isPauseRequested.set(false)
        notifyStateChanged()
    }

    fun setError() {
        currentState = AppState.ERROR
        notifyStateChanged()
    }

    fun reset() {
        isStopRequested.set(false)
        isPauseRequested.set(false)
        processedCount = 0
        skippedCount = 0
        errorCount = 0
        currentState = AppState.IDLE
        notifyStateChanged()
    }

    fun incrementProcessed() {
        processedCount++
        notifyStateChanged()
    }

    fun incrementSkipped() {
        skippedCount++
        notifyStateChanged()
    }

    fun incrementError() {
        errorCount++
        notifyStateChanged()
    }

    fun sendStatus(message: String) {
        onStatusMessage?.invoke(message)
    }

    private fun notifyStateChanged() {
        onStateChanged?.invoke(currentState, processedCount, skippedCount, errorCount)
    }

    /** Returns true if execution should continue (not stopped, not paused) */
    fun shouldContinue(): Boolean {
        return !isStopRequested.get() && !isPauseRequested.get()
    }

    /** Blocks until resumed or stop requested. Returns false if stopped. */
    fun waitIfPaused(): Boolean {
        while (isPauseRequested.get()) {
            if (isStopRequested.get()) return false
            Thread.sleep(200)
        }
        return !isStopRequested.get()
    }
}
