package com.drltour.paymentbridge

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.view.accessibility.AccessibilityEvent

/**
 * PaymentAccessibilityService — second, independent way to see payment notifications.
 *
 * Phones (vivo/iQOO OriginOS, Infinix XOS ...) are very aggressive with normal background apps,
 * but they keep an enabled Accessibility service alive and un-frozen. Android hands every posted
 * notification to this service immediately, so the payment is read + sent within a second even
 * when the app is closed. It does NOT read the screen (canRetrieveWindowContent = false) —
 * it only receives notification events.
 *
 * Duplicates with NotificationListener / SMS are removed by TrxID.
 */
class PaymentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            LogManager.add(applicationContext, "INFO", "♿ Accessibility service CONNECTED")
        } catch (_: Exception) { }
        try {
            if (Prefs.isMonitoringEnabled(applicationContext)) {
                if (!PaymentMonitorService.isAlive) PaymentMonitorService.start(applicationContext)
                HeartbeatReceiver.schedule(applicationContext)
            }
        } catch (_: Exception) { }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return

            val pkg = event.packageName?.toString() ?: return
            if (!PaymentSources.isPaymentSourcePackage(pkg)) return
            if (!Prefs.isMonitoringEnabled(applicationContext)) return

            var title = ""
            val parts = ArrayList<String>()

            val n = event.parcelableData as? Notification
            if (n != null) {
                val ex = n.extras
                if (ex != null) {
                    title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
                    val keys = listOf(
                        Notification.EXTRA_TEXT,
                        Notification.EXTRA_BIG_TEXT,
                        Notification.EXTRA_SUB_TEXT,
                        Notification.EXTRA_INFO_TEXT,
                        Notification.EXTRA_SUMMARY_TEXT
                    )
                    for (k in keys) {
                        val v = ex.getCharSequence(k)?.toString()?.trim() ?: ""
                        if (v.isNotBlank()) parts.add(v)
                    }
                    val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    if (lines != null) {
                        val joined = lines.joinToString(" ") { it?.toString() ?: "" }.trim()
                        if (joined.isNotBlank()) parts.add(joined)
                    }
                }
            }
            val evText = event.text?.joinToString(" ") { it?.toString() ?: "" }?.trim() ?: ""
            if (evText.isNotBlank()) parts.add(evText)
            if (parts.isEmpty() && title.isBlank()) return

            // Drop parts that are only a (truncated) copy of a longer part.
            val combined = parts
                .filter { p -> parts.none { o -> o !== p && o.length > p.length && o.contains(p) } }
                .distinct()
                .joinToString(" ")

            val payment = PaymentParser.parse(title, combined, null, pkg) ?: return
            PaymentIngest.submit(applicationContext, "ACCESS", payment)
        } catch (e: Exception) {
            try {
                LogManager.add(applicationContext, "ERROR", "Accessibility error: ${e.message}")
            } catch (_: Exception) { }
        }
    }

    override fun onInterrupt() { }
}
