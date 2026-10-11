package com.mjafari.ows

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    // UI elements
    private lateinit var rgMode: RadioGroup
    private lateinit var rbOk: RadioButton
    private lateinit var rbNotOk: RadioButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnPause: MaterialButton
    private lateinit var btnResume: MaterialButton
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnClearLog: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvProcessed: TextView
    private lateinit var tvSkipped: TextView
    private lateinit var tvErrors: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView

    private val mainHandler = Handler(Looper.getMainLooper())

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.title = getString(R.string.app_name)

        bindViews()
        setupListeners()
        setupStateCallbacks()
        setupLogCallback()
        updateButtonStates(OWSStateManager.AppState.IDLE)
    }

    override fun onResume() {
        super.onResume()
        // Check if accessibility was just enabled
        val isEnabled = isAccessibilityEnabled()
        if (isEnabled && OWSStateManager.currentState == OWSStateManager.AppState.IDLE) {
            updateStatus("Ready", R.color.colorStatusReady)
        }
    }

    // ─── View binding ────────────────────────────────────────────────────────

    private fun bindViews() {
        rgMode = findViewById(R.id.rgMode)
        rbOk = findViewById(R.id.rbOk)
        rbNotOk = findViewById(R.id.rbNotOk)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnPause = findViewById(R.id.btnPause)
        btnResume = findViewById(R.id.btnResume)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnClearLog = findViewById(R.id.btnClearLog)
        tvStatus = findViewById(R.id.tvStatus)
        tvMode = findViewById(R.id.tvMode)
        tvProcessed = findViewById(R.id.tvProcessed)
        tvSkipped = findViewById(R.id.tvSkipped)
        tvErrors = findViewById(R.id.tvErrors)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
    }

    // ─── Listeners ───────────────────────────────────────────────────────────

    private fun setupListeners() {

        // Mode selection
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbOk) {
                OWSStateManager.ExecutionMode.OK
            } else {
                OWSStateManager.ExecutionMode.NOT_OK
            }
            OWSStateManager.setMode(mode)
            val modeText = if (mode == OWSStateManager.ExecutionMode.OK) "OK" else "NOT OK"
            tvMode.text = modeText
            tvMode.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (mode == OWSStateManager.ExecutionMode.OK) R.color.colorStart else R.color.colorStop
                )
            )
        }

        // START
        btnStart.setOnClickListener {
            handleStart()
        }

        // STOP
        btnStop.setOnClickListener {
            handleStop()
        }

        // PAUSE
        btnPause.setOnClickListener {
            handlePause()
        }

        // RESUME
        btnResume.setOnClickListener {
            handleResume()
        }

        // Accessibility Settings
        btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // Clear Log
        btnClearLog.setOnClickListener {
            OWSLogger.clearLog()
            tvLog.text = "[Log cleared]"
        }
    }

    // ─── Button Handlers ─────────────────────────────────────────────────────

    private fun handleStart() {
        // Check accessibility
        if (!isAccessibilityEnabled()) {
            showAccessibilityDialog()
            return
        }

        // Check that service is connected
        if (!OWSAccessibilityService.isRunning()) {
            showToast("Service not connected. Enable it in Accessibility Settings.")
            openAccessibilitySettings()
            return
        }

        val state = OWSStateManager.currentState
        if (state == OWSStateManager.AppState.RUNNING) {
            showToast("Already running")
            return
        }

        // Lock mode during operation
        rgMode.isEnabled = false
        rbOk.isEnabled = false
        rbNotOk.isEnabled = false

        OWSLogger.clearLog()
        OWSLogger.info("=== m.jafari ows started ===")
        OWSLogger.info("Mode: ${OWSStateManager.currentMode.name}")
        OWSLogger.info("Target: ${OWSFormProcessor.getTargetText(OWSStateManager.currentMode)}")

        OWSAccessibilityService.instance?.startProcessing()
    }

    private fun handleStop() {
        OWSAccessibilityService.instance?.stopProcessing() ?: OWSStateManager.stop()
        rgMode.isEnabled = true
        rbOk.isEnabled = true
        rbNotOk.isEnabled = true
        showToast("Operation stopped")
    }

    private fun handlePause() {
        OWSAccessibilityService.instance?.pauseProcessing() ?: OWSStateManager.pause()
        showToast("Paused")
    }

    private fun handleResume() {
        if (!isAccessibilityEnabled() || !OWSAccessibilityService.isRunning()) {
            showToast("Service not available")
            return
        }
        OWSAccessibilityService.instance?.resumeProcessing()
    }

    // ─── State Callbacks ─────────────────────────────────────────────────────

    private fun setupStateCallbacks() {
        OWSStateManager.onStateChanged = { state, processed, skipped, errors ->
            mainHandler.post {
                tvProcessed.text = processed.toString()
                tvSkipped.text = skipped.toString()
                tvErrors.text = errors.toString()
                updateButtonStates(state)

                when (state) {
                    OWSStateManager.AppState.IDLE ->
                        updateStatus("Ready", R.color.colorStatusReady)
                    OWSStateManager.AppState.RUNNING ->
                        updateStatus("Running…", R.color.colorStatusRunning)
                    OWSStateManager.AppState.PAUSED ->
                        updateStatus("Paused", R.color.colorStatusPaused)
                    OWSStateManager.AppState.STOPPED -> {
                        updateStatus("Stopped", R.color.colorStatusStopped)
                        rgMode.isEnabled = true
                        rbOk.isEnabled = true
                        rbNotOk.isEnabled = true
                    }
                    OWSStateManager.AppState.COMPLETED -> {
                        updateStatus("Completed ✓", R.color.colorStatusCompleted)
                        rgMode.isEnabled = true
                        rbOk.isEnabled = true
                        rbNotOk.isEnabled = true
                        showCompletionDialog(processed, skipped, errors)
                    }
                    OWSStateManager.AppState.ERROR -> {
                        updateStatus("Error", R.color.colorStatusError)
                        rgMode.isEnabled = true
                        rbOk.isEnabled = true
                        rbNotOk.isEnabled = true
                    }
                }
            }
        }

        OWSStateManager.onStatusMessage = { message ->
            mainHandler.post {
                if (OWSStateManager.currentState == OWSStateManager.AppState.RUNNING ||
                    OWSStateManager.currentState == OWSStateManager.AppState.PAUSED) {
                    updateStatus(message, R.color.colorStatusRunning)
                }
            }
        }
    }

    private fun setupLogCallback() {
        OWSLogger.onLogAdded = { entry ->
            mainHandler.post {
                val currentText = tvLog.text?.toString() ?: ""
                val prefix = when (entry.level) {
                    OWSLogger.Level.INFO -> ""
                    OWSLogger.Level.WARNING -> "⚠ "
                    OWSLogger.Level.ERROR -> "✗ "
                }
                val newLine = "${entry.timestamp} $prefix${entry.message}"
                val newText = if (currentText == "[Ready]" || currentText == "[Log cleared]") {
                    newLine
                } else {
                    "$currentText\n$newLine"
                }
                tvLog.text = newText
                // Auto-scroll to bottom
                svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    // ─── UI Helpers ──────────────────────────────────────────────────────────

    private fun updateStatus(message: String, colorRes: Int) {
        tvStatus.text = message
        tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updateButtonStates(state: OWSStateManager.AppState) {
        when (state) {
            OWSStateManager.AppState.IDLE, OWSStateManager.AppState.STOPPED,
            OWSStateManager.AppState.COMPLETED, OWSStateManager.AppState.ERROR -> {
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                btnPause.isEnabled = false
                btnResume.isEnabled = false
            }
            OWSStateManager.AppState.RUNNING -> {
                btnStart.isEnabled = false
                btnStop.isEnabled = true
                btnPause.isEnabled = true
                btnResume.isEnabled = false
            }
            OWSStateManager.AppState.PAUSED -> {
                btnStart.isEnabled = false
                btnStop.isEnabled = true
                btnPause.isEnabled = false
                btnResume.isEnabled = true
            }
        }
    }

    private fun showCompletionDialog(processed: Int, skipped: Int, errors: Int) {
        AlertDialog.Builder(this)
            .setTitle("Processing Completed")
            .setMessage(
                "Fields processed: $processed\n" +
                "Fields skipped: $skipped\n" +
                "Errors: $errors\n\n" +
                "⚠ Please review the form carefully before submitting.\n\n" +
                "Submit was NOT pressed automatically."
            )
            .setPositiveButton("OK", null)
            .setIcon(android.R.drawable.ic_dialog_info)
            .show()
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Permission Required")
            .setMessage(
                "To automate OWS form filling, you need to enable 'm.jafari ows' " +
                "in Android Accessibility Settings.\n\n" +
                "1. Tap 'Open Settings'\n" +
                "2. Find 'm.jafari ows'\n" +
                "3. Toggle it ON\n" +
                "4. Return here and press START"
            )
            .setPositiveButton("Open Settings") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val prefString = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return prefString.contains(packageName, ignoreCase = true)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
