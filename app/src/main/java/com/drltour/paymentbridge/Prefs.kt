package com.drltour.paymentbridge

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    private const val PREF_NAME = "payment_bridge_prefs"

    private const val KEY_BACKEND_URL = "backend_url"
    private const val KEY_BRIDGE_SECRET = "bridge_secret"
    private const val KEY_MONITORING = "monitoring_enabled"
    private const val KEY_LAST_PAYMENT = "last_payment_summary"
    private const val KEY_BACKEND_CONNECTED = "backend_connected"
    private const val KEY_BACKEND_LAST_CHECK = "backend_last_check"
    private const val KEY_BACKEND_CACHED_URL = "backend_cached_url"

    private fun get(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getBackendUrl(context: Context): String {
        return get(context).getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
    }

    fun setBackendUrl(context: Context, url: String) {
        val trimmed = url.trim()
        // If URL changed, invalidate connection cache
        val old = getBackendUrl(context)
        if (old != trimmed) {
            get(context).edit()
                .putString(KEY_BACKEND_URL, trimmed)
                .remove(KEY_BACKEND_CONNECTED)
                .remove(KEY_BACKEND_LAST_CHECK)
                .putString(KEY_BACKEND_CACHED_URL, trimmed)
                .apply()
        } else {
            get(context).edit().putString(KEY_BACKEND_URL, trimmed).apply()
        }
    }

    fun getBridgeSecret(context: Context): String {
        return get(context).getString(KEY_BRIDGE_SECRET, "") ?: ""
    }

    fun setBridgeSecret(context: Context, secret: String) {
        get(context).edit().putString(KEY_BRIDGE_SECRET, secret.trim()).apply()
    }

    fun isMonitoringEnabled(context: Context): Boolean {
        return get(context).getBoolean(KEY_MONITORING, true)
    }

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_MONITORING, enabled).apply()
    }

    fun getLastPaymentSummary(context: Context): String {
        return get(context).getString(KEY_LAST_PAYMENT, "") ?: ""
    }

    fun setLastPaymentSummary(context: Context, summary: String) {
        get(context).edit().putString(KEY_LAST_PAYMENT, summary).apply()
    }

    // ─── Backend Connection Cache ───

    fun isBackendConnected(context: Context): Boolean {
        // Invalidate if URL changed
        val cachedUrl = get(context).getString(KEY_BACKEND_CACHED_URL, "") ?: ""
        val currentUrl = getBackendUrl(context)
        if (cachedUrl != currentUrl) return false

        return get(context).getBoolean(KEY_BACKEND_CONNECTED, false)
    }

    fun setBackendConnected(context: Context, value: Boolean) {
        get(context).edit()
            .putBoolean(KEY_BACKEND_CONNECTED, value)
            .putString(KEY_BACKEND_CACHED_URL, getBackendUrl(context))
            .apply()
    }

    fun getBackendLastCheck(context: Context): Long {
        return get(context).getLong(KEY_BACKEND_LAST_CHECK, 0L)
    }

    fun setBackendLastCheck(context: Context, value: Long) {
        get(context).edit().putLong(KEY_BACKEND_LAST_CHECK, value).apply()
    }

    // ─── First-run setup guide flag ───

    fun isSetupShown(context: Context): Boolean {
        return get(context).getBoolean("setup_shown", false)
    }

    fun setSetupShown(context: Context, value: Boolean) {
        get(context).edit().putBoolean("setup_shown", value).apply()
    }

    // Every user enters THEIR OWN Supabase function URL + secret in Settings.
    const val DEFAULT_BACKEND_URL = ""
}
