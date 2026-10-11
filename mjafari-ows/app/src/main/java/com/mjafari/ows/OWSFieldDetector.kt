package com.mjafari.ows

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Detects and classifies OWS form fields from accessibility tree.
 *
 * Field types based on the OWS form observed:
 * - DROPDOWN: Spinner/dropdown with OK / Not OK / CANCEL options
 * - TEXT_INPUT: Editable text field for status entry
 * - IMAGE_PICKER: Camera/image field (+ button) — always skipped
 * - UNKNOWN: Cannot determine type safely
 * - SKIP: Should be ignored (Submit button, labels, images)
 */
object OWSFieldDetector {

    enum class FieldType { DROPDOWN, TEXT_INPUT, IMAGE_PICKER, UNKNOWN, SKIP }

    data class DetectedField(
        val node: AccessibilityNodeInfo,
        val type: FieldType,
        val label: String,
        val currentValue: String?,
        val isRequired: Boolean,
        val uniqueId: String
    )

    // Track processed field IDs to prevent double-processing
    private val processedIds = mutableSetOf<String>()

    fun clearProcessed() {
        processedIds.clear()
    }

    fun isAlreadyProcessed(uniqueId: String): Boolean = uniqueId in processedIds

    fun markProcessed(uniqueId: String) {
        processedIds.add(uniqueId)
    }

    /**
     * Scans the accessibility tree from rootNode and returns a list of detected fields.
     * Only returns fields that haven't been processed yet.
     */
    fun detectFields(rootNode: AccessibilityNodeInfo): List<DetectedField> {
        val fields = mutableListOf<DetectedField>()
        traverseNode(rootNode, fields)
        return fields
    }

    private fun traverseNode(node: AccessibilityNodeInfo, fields: MutableList<DetectedField>) {
        val field = classifyNode(node)
        if (field != null && field.type != FieldType.SKIP) {
            if (!isAlreadyProcessed(field.uniqueId)) {
                fields.add(field)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, fields)
            // Don't recycle - let caller manage lifecycle
        }
    }

    /**
     * Classify a single node.
     * Returns null if node should be ignored entirely.
     */
    private fun classifyNode(node: AccessibilityNodeInfo): DetectedField? {
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        // --- SKIP: Submit button (NEVER touch this) ---
        if (isSubmitButton(text, contentDesc, viewId)) {
            return null // completely ignore
        }

        // --- SKIP: Navigation, action bar, other UI chrome ---
        if (isUIChrome(className, text, viewId)) {
            return null
        }

        // --- DROPDOWN detection ---
        // Spinner class or a View that when clicked opens a popup with OK/Not OK
        if (isDropdown(node, className, text, contentDesc, viewId)) {
            val label = findFieldLabel(node)
            val uniqueId = buildUniqueId(label, className, viewId, node)
            val isRequired = checkIfRequired(node)
            return DetectedField(
                node = node,
                type = FieldType.DROPDOWN,
                label = label,
                currentValue = text.ifEmpty { contentDesc.ifEmpty { null } },
                isRequired = isRequired,
                uniqueId = uniqueId
            )
        }

        // --- TEXT INPUT detection ---
        if (isTextInput(node, className, viewId)) {
            val label = findFieldLabel(node)
            val uniqueId = buildUniqueId(label, className, viewId, node)
            val isRequired = checkIfRequired(node)
            // Only include if it looks like a status field, not a search/comment field
            return DetectedField(
                node = node,
                type = FieldType.TEXT_INPUT,
                label = label,
                currentValue = text.ifEmpty { null },
                isRequired = isRequired,
                uniqueId = uniqueId
            )
        }

        // --- IMAGE PICKER: the + button for photo ---
        if (isImagePicker(node, className, text, contentDesc)) {
            val label = findFieldLabel(node)
            val uniqueId = buildUniqueId(label, className, viewId, node)
            return DetectedField(
                node = node,
                type = FieldType.IMAGE_PICKER,
                label = label,
                currentValue = null,
                isRequired = false,
                uniqueId = uniqueId
            )
        }

        return null
    }

    private fun isSubmitButton(text: String, contentDesc: String, viewId: String): Boolean {
        val combined = "$text $contentDesc $viewId".lowercase()
        return combined.contains("submit")
    }

    private fun isUIChrome(className: String, text: String, viewId: String): Boolean {
        // Skip action bars, navigation, status bar elements
        if (className.contains("ActionBar") || className.contains("Toolbar")) return true
        if (viewId.contains("action_bar") || viewId.contains("toolbar")) return true
        return false
    }

    private fun isDropdown(
        node: AccessibilityNodeInfo,
        className: String,
        text: String,
        contentDesc: String,
        viewId: String
    ): Boolean {
        // Direct Spinner class
        if (className.contains("Spinner", ignoreCase = true)) return true
        // DropDown in class name
        if (className.contains("DropDown", ignoreCase = true)) return true
        // Select in class name (WebView HTML select)
        if (className.contains("Select", ignoreCase = true) && !className.contains("CheckBox")) return true

        // A clickable view that is NOT editable, whose text is one of the known values
        val knownValues = listOf("ok", "not ok", "cancel", "select", "choose", "--")
        val lowerText = text.lowercase().trim()
        if (node.isClickable && !node.isEditable && knownValues.any { lowerText == it }) return true

        // A clickable view with no text that might be a dropdown (has chevron/arrow child)
        if (node.isClickable && !node.isEditable && text.isEmpty() && hasDropdownIndicator(node)) return true

        // WebView: role="combobox" or "listbox" in contentDesc
        if (contentDesc.contains("combobox", ignoreCase = true) || 
            contentDesc.contains("listbox", ignoreCase = true)) return true

        return false
    }

    private fun hasDropdownIndicator(node: AccessibilityNodeInfo): Boolean {
        // Check if any child has an arrow/chevron icon (common in dropdowns)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childClass = child.className?.toString() ?: ""
            val childDesc = child.contentDescription?.toString()?.lowercase() ?: ""
            if (childDesc.contains("arrow") || childDesc.contains("dropdown") || 
                childDesc.contains("expand") || childClass.contains("ImageView")) {
                return true
            }
        }
        return false
    }

    private fun isTextInput(
        node: AccessibilityNodeInfo,
        className: String,
        viewId: String
    ): Boolean {
        if (!node.isEditable) return false
        if (className.contains("EditText", ignoreCase = true)) return true
        if (className.contains("AutoCompleteTextView", ignoreCase = true)) return true
        // WebView text inputs
        if (className.contains("android.widget.EditText", ignoreCase = true)) return true
        return false
    }

    private fun isImagePicker(
        node: AccessibilityNodeInfo,
        className: String,
        text: String,
        contentDesc: String
    ): Boolean {
        val combined = "$text $contentDesc".trim()
        if (combined == "+" || combined.contains("photo", ignoreCase = true) ||
            combined.contains("camera", ignoreCase = true) ||
            combined.contains("image", ignoreCase = true)) {
            if (node.isClickable) return true
        }
        return false
    }

    /**
     * Tries to find the label/title for a field by looking at sibling or parent nodes.
     */
    private fun findFieldLabel(node: AccessibilityNodeInfo): String {
        // Check the node's own content description first
        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNullOrEmpty()) return contentDesc

        // Try parent node's text children (sibling text before this node)
        val parent = node.parent ?: return ""
        var foundCurrent = false
        // Look for a TextView sibling just before this node
        for (i in parent.childCount - 1 downTo 0) {
            val sibling = parent.getChild(i) ?: continue
            if (sibling == node) {
                foundCurrent = true
                continue
            }
            if (foundCurrent) {
                val sibText = sibling.text?.toString()
                if (!sibText.isNullOrEmpty() && sibText.length > 1) {
                    return sibText.take(60) // truncate very long labels
                }
            }
        }

        // Try grandparent
        val grandParent = parent.parent ?: return ""
        for (i in 0 until grandParent.childCount) {
            val uncle = grandParent.getChild(i) ?: continue
            val uncleText = uncle.text?.toString()
            if (!uncleText.isNullOrEmpty() && uncleText.length > 2) {
                return uncleText.take(60)
            }
        }

        return "Field"
    }

    /**
     * Check if field is marked as required/mandatory.
     * OWS shows "Mandatory field." text and red border when empty.
     */
    private fun checkIfRequired(node: AccessibilityNodeInfo): Boolean {
        val parent = node.parent ?: return false
        for (i in 0 until parent.childCount) {
            val sibling = parent.getChild(i) ?: continue
            val sibText = sibling.text?.toString()?.lowercase() ?: ""
            if (sibText.contains("mandatory") || sibText.contains("required")) {
                return true
            }
        }
        return false
    }

    private fun buildUniqueId(
        label: String,
        className: String,
        viewId: String,
        node: AccessibilityNodeInfo
    ): String {
        // Build a stable ID from label + class + viewId + bounds
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return "${label}_${className.substringAfterLast(".")}_${viewId}_${bounds.top}_${bounds.left}"
    }
}
