package com.drltour.paymentbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * SmsScanner — "catch-up" safety net.
 *
 * If the phone froze the app and an SMS broadcast / notification was missed,
 * this reads the SMS inbox (last 15 minutes) as soon as the app wakes up again
 * and sends every payment that was not sent yet. Duplicates are skipped by TrxID.
 */
object SmsScanner {

    private const val WINDOW_MS = 15L * 60L * 1000L

    suspend fun scanRecent(context: Context) {
        val app = context.applicationContext
        try {
            if (!Prefs.isMonitoringEnabled(app)) return
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) return

            val since = System.currentTimeMillis() - WINDOW_MS
            val bodies = ArrayList<String>()

            val cursor = app.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
                Telephony.Sms.DATE + " >= ?",
                arrayOf(since.toString()),
                Telephony.Sms.DATE + " ASC"
            )

            if (cursor != null) {
                cursor.use { c ->
                    val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                    if (bodyIdx >= 0) {
                        while (c.moveToNext()) {
                            val b = c.getString(bodyIdx)
                            if (!b.isNullOrBlank()) bodies.add(b)
                        }
                    }
                }
            }

            var added = 0
            for (body in bodies) {
                val payment = PaymentParser.parse("", body) ?: continue
                if (PaymentQueue.isDuplicate(app, payment.trxId)) continue

                LogManager.add(
                    app,
                    "PARSED",
                    "CATCH-UP SMS ${payment.method.uppercase()} ৳${payment.amount} " +
                            "TrxID ${PaymentParser.maskTrxId(payment.trxId)}"
                )
                PaymentQueue.enqueue(app, payment)
                added++
            }

            if (added > 0 || PaymentQueue.pendingCount(app) > 0) {
                PaymentQueue.flush(app)
            }
        } catch (e: Exception) {
            try {
                LogManager.add(app, "ERROR", "SmsScanner error: ${e.message}")
            } catch (_: Exception) { }
        }
    }
}
