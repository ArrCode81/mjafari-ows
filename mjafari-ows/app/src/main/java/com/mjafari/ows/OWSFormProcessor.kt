package com.mjafari.ows

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Processes individual OWS form fields:
 * - Selects OK or Not OK from dropdowns
 * - Types OK or Not OK into text fields
 * - Never touches Submit
 * - Skips unknown / image fields
 */
class OWSFormProcessor(
    private val service: OWSAccessibilityService
) {

    companion object {
        const val DELAY_AFTER_CLICK = 600L   // ms to wait after clicking
        const val DELAY_AFTER_TYPE = 400L     // ms to wait after typing
        const val DELAY_MENU_OPEN = 800L      // ms to wait for dropdown to open

        // Text to type/select based on mode
        fun getTargetText(mode: OWSStateManager.ExecutionMode): String =
            if (mode == OWSStateManager.ExecutionMode.OK) "OK" else "Not OK"
    }

    /**
     * Process a single detected field.
     * Returns true if processed (or intentionally skipped), false if error.
     */
    fun processField(field: OWSFieldDetector.DetectedField): Boolean {
        // Safety: never touch submit
        val text = field.node.text?.toString()?.lowercase() ?: ""
        if (text.contains("submit")) {
            OWSLogger.warning("Skipped Submit button")
            return true
        }

        return when (field.type) {
            OWSFieldDetector.FieldType.DROPDOWN -> handleDropdown(field)
            OWSFieldDetector.FieldType.TEXT_INPUT -> handleTextInput(field)
            OWSFieldDetector.FieldType.IMAGE_PICKER -> {
                OWSLogger.info("Skipped image picker field: ${field.label}")
                OWSStateManager.incrementSkipped()
                true
            }
            OWSFieldDetector.FieldType.UNKNOWN -> {
                OWSLogger.warning("Manual review required for unknown field: ${field.label}")
                OWSStateManager.sendStatus("Manual review required: ${field.label}")
                OWSStateManager.incrementSkipped()
                true // continue processing other fields
            }
            OWSFieldDetector.FieldType.SKIP -> true
        }
    }

    // ─── Dropdown ────────────────────────────────────────────────────────────

    private fun handleDropdown(field: OWSFieldDetector.DetectedField): Boolean {
        val targetText = getTargetText(OWSStateManager.currentMode)
        OWSLogger.info("Opening dropdown for: ${field.label} → selecting '$targetText'")
        OWSStateManager.sendStatus("Opening dropdown: ${field.label}")

        // Step 1: Click to open the dropdown
        if (!field.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            OWSLogger.error("Could not click dropdown: ${field.label}")
            OWSStateManager.incrementError()
            return false
        }

        Thread.sleep(DELAY_MENU_OPEN)

        // Step 2: Find the opened popup/list in the new root
        if (!OWSStateManager.shouldContinue()) return false

        val root = service.rootInActiveWindow ?: run {
            OWSLogger.error("Lost root window after opening dropdown")
            OWSStateManager.incrementError()
            return false
        }

        // Step 3: Find target option in the popup
        val option = findOptionInPopup(root, targetText)

        if (option == null) {
            OWSLogger.error("Option '$targetText' not found in dropdown for: ${field.label}")
            // Close the popup by pressing back
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(300)
            OWSStateManager.incrementError()
            return false
        }

        // Step 4: Click the option
        option.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(DELAY_AFTER_CLICK)

        // Step 5: Verify change
        val newRoot = service.rootInActiveWindow
        val verified = verifyDropdownSelection(newRoot, field, targetText)

        if (verified) {
            OWSLogger.info("✓ Dropdown set to '$targetText': ${field.label}")
            OWSFieldDetector.markProcessed(field.uniqueId)
            OWSStateManager.incrementProcessed()
        } else {
            OWSLogger.warning("Could not verify dropdown change for: ${field.label} (may still be OK)")
            OWSFieldDetector.markProcessed(field.uniqueId) // mark as done anyway
            OWSStateManager.incrementProcessed()
        }

        return true
    }

    /**
     * Search the accessibility tree for a list item matching the target text.
     * Handles both native Spinner popups and WebView select dialogs.
     */
    private fun findOptionInPopup(
        root: AccessibilityNodeInfo,
        targetText: String
    ): AccessibilityNodeInfo? {
        // Try direct text search first (most reliable)
        val byText = root.findAccessibilityNodeInfosByText(targetText)
        if (byText != null) {
            for (node in byText) {
                val nodeText = node.text?.toString() ?: ""
                // Exact or close match (avoid selecting "Not OK" when looking for "OK")
                if (nodeText.equals(targetText, ignoreCase = true)) {
                    // Make sure it's in a popup/list context, not the original field
                    if (node.isClickable || node.isEnabled) {
                        return node
                    }
                }
            }
        }

        // Fallback: walk the tree looking for list items
        return findClickableTextNode(root, targetText)
    }

    private fun findClickableTextNode(
        node: AccessibilityNodeInfo,
        targetText: String
    ): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        if ((nodeText.equals(targetText, ignoreCase = true) ||
                    contentDesc.equals(targetText, ignoreCase = true)) &&
            (node.isClickable || node.isEnabled)
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableTextNode(child, targetText)
            if (result != null) return result
        }
        return null
    }

    private fun verifyDropdownSelection(
        root: AccessibilityNodeInfo?,
        field: OWSFieldDetector.DetectedField,
        expectedText: String
    ): Boolean {
        root ?: return false
        // Try to find the original node by bounds and check its text
        val bounds = android.graphics.Rect()
        field.node.getBoundsInScreen(bounds)

        val byText = root.findAccessibilityNodeInfosByText(expectedText)
        if (!byText.isNullOrEmpty()) {
            for (node in byText) {
                val nodeBounds = android.graphics.Rect()
                node.getBoundsInScreen(nodeBounds)
                if (nodeBounds.intersects(bounds.left - 50, bounds.top - 50,
                        bounds.right + 50, bounds.bottom + 50)) {
                    return true
                }
            }
        }
        return false // Can't confirm, but might still be OK
    }

    // ─── Text Input ──────────────────────────────────────────────────────────

    private fun handleTextInput(field: OWSFieldDetector.DetectedField): Boolean {
        val targetText = getTargetText(OWSStateManager.currentMode)
        OWSLogger.info("Typing '$targetText' into text field: ${field.label}")
        OWSStateManager.sendStatus("Filling text field: ${field.label}")

        // Step 1: Focus the field
        field.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        Thread.sleep(200)
        field.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(300)

        // Step 2: Clear existing text
        val clearArgs = Bundle()
        clearArgs.putInt(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0
        )
        clearArgs.putInt(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
            (field.currentValue?.length ?: 0)
        )
        field.node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, clearArgs)
        field.node.performAction(AccessibilityNodeInfo.ACTION_CUT)
        Thread.sleep(100)

        // Step 3: Set text via Accessibility ACTION_SET_TEXT
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            targetText
        )
        val success = field.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        Thread.sleep(DELAY_AFTER_TYPE)

        if (!success) {
            OWSLogger.error("ACTION_SET_TEXT failed for: ${field.label}")
            OWSStateManager.incrementError()
            return false
        }

        // Step 4: Verify
        val newRoot = service.rootInActiveWindow
        val verified = verifyTextInput(newRoot, field, targetText)

        if (verified) {
            OWSLogger.info("✓ Text field set to '$targetText': ${field.label}")
        } else {
            OWSLogger.warning("Could not verify text change for: ${field.label}")
        }

        OWSFieldDetector.markProcessed(field.uniqueId)
        OWSStateManager.incrementProcessed()
        return true
    }

    private fun verifyTextInput(
        root: AccessibilityNodeInfo?,
        field: OWSFieldDetector.DetectedField,
        expectedText: String
    ): Boolean {
        root ?: return false
        val bounds = android.graphics.Rect()
        field.node.getBoundsInScreen(bounds)

        // Try to find an EditText near the same location with our text
        val nodes = root.findAccessibilityNodeInfosByText(expectedText) ?: return false
        for (node in nodes) {
            if (!node.isEditable) continue
            val nodeBounds = android.graphics.Rect()
            node.getBoundsInScreen(nodeBounds)
            if (nodeBounds.intersects(bounds.left - 20, bounds.top - 20,
                    bounds.right + 20, bounds.bottom + 20)) {
                return true
            }
        }
        return false
    }
}
