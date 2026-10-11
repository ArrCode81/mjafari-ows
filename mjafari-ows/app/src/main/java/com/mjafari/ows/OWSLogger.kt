package com.mjafari.ows

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Logger for OWS automation operations.
 * Thread-safe, keeps last 500 entries.
 */
object OWSLogger {

    enum class Level { INFO, WARNING, ERROR }

    data class LogEntry(
        val timestamp: String,
        val message: String,
        val level: Level
    )

    private val entries = CopyOnWriteArrayList<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val MAX_ENTRIES = 500

    var onLogAdded: ((LogEntry) -> Unit)? = null

    fun log(message: String, level: Level = Level.INFO) {
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            message = message,
            level = level
        )
        entries.add(entry)
        // Keep max entries
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        onLogAdded?.invoke(entry)
    }

    fun info(message: String) = log(message, Level.INFO)
    fun warning(message: String) = log(message, Level.WARNING)
    fun error(message: String) = log(message, Level.ERROR)

    fun clearLog() {
        entries.clear()
        onLogAdded?.invoke(LogEntry(dateFormat.format(Date()), "[Log cleared]", Level.INFO))
    }

    fun getFormattedLog(): String {
        return entries.joinToString("\n") { entry ->
            val prefix = when (entry.level) {
                Level.INFO -> "  "
                Level.WARNING -> "⚠ "
                Level.ERROR -> "✗ "
            }
            "${entry.timestamp} $prefix${entry.message}"
        }
    }

    fun getSummary(
        mode: OWSStateManager.ExecutionMode,
        processed: Int,
        skipped: Int,
        errors: Int
    ): String {
        return buildString {
            appendLine("=== m.jafari ows Report ===")
            appendLine("Mode: ${mode.name}")
            appendLine("Fields Processed: $processed")
            appendLine("Fields Skipped: $skipped")
            appendLine("Errors: $errors")
            appendLine("State: ${OWSStateManager.currentState.name}")
            appendLine("Submit: NOT pressed automatically")
            appendLine("===========================")
        }
    }
}
