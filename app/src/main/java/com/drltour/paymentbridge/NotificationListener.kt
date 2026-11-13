package com.drltour.paymentbridge

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            LogManager.add(applicationContext, "INFO", "🔌 NotificationListener CONNECTED")
        } catch (_: Exception) { }
        // Make sure the foreground service is alive too (best effort).
        try {
            if (Prefs.isMonitoringEnabled(applicationContext)) {
                PaymentMonitorService.start(applicationContext)
            }
        } catch (_: Exception) { }
        // Send anything that is still waiting in the queue.
        kickFlush()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            LogManager.add(applicationContext, "INFO", "🔌 NotificationListener DISCONNECTED — asking Android to reconnect")
        } catch (_: Exception) { }
        // Ask Android to bind us again (otherwise some phones leave the listener dead).
        try {
            if (Prefs.isMonitoringEnabled(applicationContext)) {
                NotificationListenerService.requestRebind(ComponentName(applicationContext, NotificationListener::class.java))
            }
        } catch (_: Exception) { }
    }

    private fun kickFlush() {
        serviceScope.launch {
            val wl = WakeHelper.acquire(applicationContext, 30_000L)
            try {
                if (PaymentQueue.pendingCount(applicationContext) > 0) {
                    PaymentQueue.flush(applicationContext)
                }
            } catch (_: Exception) {
            } finally {
                WakeHelper.release(wl)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return

            val packageName = sbn.packageName ?: return
            if (!isPaymentSourcePackage(packageName)) return

            if (!Prefs.isMonitoringEnabled(applicationContext)) return

            LogManager.add(applicationContext, "DEBUG", "📩 Notification from: $packageName")

            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = safeString(extras.getCharSequence(Notification.EXTRA_TITLE))
            val text = safeString(extras.getCharSequence(Notification.EXTRA_TEXT))
            val bigText = safeString(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            val subText = safeString(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            val infoText = safeString(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
            val summaryText = safeString(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            val conversationTitle = safeString(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
            val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString(" ") { safeString(it) } ?: ""

            val parts = listOf(text, bigText, subText, infoText, summaryText, textLines)
                .filter { it.isNotBlank() }
            // Drop parts that are just a (truncated) copy of a longer part, so a cut-off
            // TrxID in the short text can never win over the full one in bigText.
            val combinedText = parts
                .filter { p -> parts.none { o -> o !== p && o.length > p.length && o.contains(p) } }
                .distinct()
                .joinToString(" ")
                .ifBlank { text }

            // Also include conversation title if this is a messaging notification
            val combinedTitle = if (conversationTitle.isNotBlank()) {
                "$title $conversationTitle".trim()
            } else {
                title
            }

            LogManager.add(
                applicationContext,
                "DEBUG",
                "📝 Title: $combinedTitle | Text: ${combinedText.take(200)}"
            )

            if (combinedText.isBlank() && combinedTitle.isBlank()) return

            val payment = PaymentParser.parse(combinedTitle, combinedText, { logMsg ->
                LogManager.add(applicationContext, "DEBUG", logMsg)
            }, packageName)

            if (payment == null) {
                LogManager.add(applicationContext, "DEBUG", "❌ Parser returned NULL")
                return
            }

            // SHARED duplicate check — same as SmsReceiver
            if (SmsReceiver.isDuplicate(applicationContext, payment.trxId)) {
                LogManager.add(
                    applicationContext,
                    "DUPLICATE",
                    "Ignored duplicate (Notification): ${PaymentParser.maskTrxId(payment.trxId)}"
                )
                return
            }

            LogManager.add(
                applicationContext,
                "PARSED",
                "NOTIF ${payment.method.uppercase()} ৳${payment.amount} from " +
                        "${PaymentParser.maskPhone(payment.senderPhone)} " +
                        "TrxID ${PaymentParser.maskTrxId(payment.trxId)}"
            )

            // Save first (never lose a payment), then send with a short wake lock.
            PaymentQueue.enqueue(applicationContext, payment)
            serviceScope.launch {
                val wl = WakeHelper.acquire(applicationContext, 30_000L)
                try {
                    PaymentQueue.flush(applicationContext)
                } catch (e: Exception) {
                    try {
                        LogManager.add(applicationContext, "ERROR", "NOTIF send error: ${e.message}")
                    } catch (_: Exception) { }
                } finally {
                    WakeHelper.release(wl)
                }
            }

        } catch (e: Exception) {
            try {
                LogManager.add(applicationContext, "ERROR", "Listener error: ${e.message}")
            } catch (_: Exception) { }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { }

    private fun safeString(cs: CharSequence?): String {
        return try {
            cs?.toString()?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Payment apps + SMS/Messages apps.
     * Both kinds are monitored — payment app notifications AND SMS app notifications.
     */
    private fun isPaymentSourcePackage(pkg: String): Boolean {
        // ─── Payment apps ───
        if (pkg == "com.bKash.customerapp") return true
        if (pkg == "com.bkash.customerapp") return true
        if (pkg == "com.konasl.nagad") return true
        if (pkg == "com.nagad.app") return true
        if (pkg == "com.dbbl.mbs.apps.rocket") return true
        if (pkg == "com.dbbl.mbs") return true

        // ─── SMS / Messages apps ───
        // Infinix / Tecno / Transsion default SMS app
        if (pkg == "com.transsion.smartmessage") return true
        if (pkg == "com.transsion.messaging") return true
        if (pkg == "com.transsion.smart.chat") return true

        // Google Messages (including versioned dynamic package)
        if (pkg == "com.google.android.apps.messaging") return true
        if (pkg.startsWith("messages.android_")) return true
        if (pkg.startsWith("com.google.android.apps.messaging")) return true

        // Other common SMS apps
        if (pkg == "com.android.mms") return true
        if (pkg == "com.android.messaging") return true
        if (pkg == "com.samsung.android.messaging") return true
        if (pkg == "com.android.mms.service") return true

        // vivo / iQOO / Xiaomi / Oppo / other SMS apps
        if (PaymentSources.isPaymentSourcePackage(pkg)) return true

        return false
    }
}
