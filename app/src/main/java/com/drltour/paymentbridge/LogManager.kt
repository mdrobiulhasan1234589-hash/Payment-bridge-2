package com.drltour.paymentbridge

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogManager — Simple local log storage.
 * Keeps last 200 log entries. Older entries are automatically dropped.
 */
object LogManager {

    private const val PREF_NAME = "payment_bridge_logs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LOGS = 200
    private const val SEPARATOR = "\n---LOG---\n"

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.US)

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Add a new log entry.
     * @param tag Short label like "PARSED", "SENT", "DUPLICATE", "ERROR"
     * @param message Detailed message
     */
    @Synchronized
    fun add(context: Context, tag: String, message: String) {
        try {
            val timestamp = dateFormat.format(Date())
            val entry = "[$timestamp] [$tag] $message"
            val existing = getPrefs(context).getString(KEY_LOGS, "") ?: ""
            val updated = if (existing.isEmpty()) entry else "$existing$SEPARATOR$entry"

            // Trim to last MAX_LOGS entries
            val parts = updated.split(SEPARATOR)
            val trimmed = if (parts.size > MAX_LOGS) {
                parts.takeLast(MAX_LOGS).joinToString(SEPARATOR)
            } else {
                updated
            }

            getPrefs(context).edit().putString(KEY_LOGS, trimmed).apply()
        } catch (_: Exception) {
            // Never crash because of logging
        }
    }

    /**
     * Get all log entries as a single string (newest last).
     */
    fun getAll(context: Context): String {
        return try {
            getPrefs(context).getString(KEY_LOGS, "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Get all log entries as a list (newest first).
     */
    fun getList(context: Context): List<String> {
        val raw = getAll(context)
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR).reversed()
    }

    /**
     * Clear all logs.
     */
    fun clear(context: Context) {
        try {
            getPrefs(context).edit().remove(KEY_LOGS).apply()
        } catch (_: Exception) {
            // ignore
        }
    }
}
