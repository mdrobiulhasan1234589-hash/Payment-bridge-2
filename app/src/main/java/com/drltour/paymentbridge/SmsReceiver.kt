package com.drltour.paymentbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SmsReceiver — System-level SMS BroadcastReceiver.
 *
 * Android delivers incoming SMS to this receiver even when the app is closed.
 * IMPORTANT: the receiver uses goAsync() + a short wake lock, so the process is
 * NOT frozen/killed while the payment is being sent to the server.
 * (Without this, Android killed the app right after onReceive() returned and the
 * SMS was only uploaded when the user opened the app.)
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Parse -> duplicate check -> save to queue -> send.
         * Used by the manifest receiver and the runtime receiver.
         */
        suspend fun handleSms(context: Context, fullBody: String) {
            try {
                if (!Prefs.isMonitoringEnabled(context)) {
                    LogManager.add(context, "DEBUG", "SMS received but monitoring disabled")
                    return
                }

                if (fullBody.isBlank()) return

                LogManager.add(
                    context,
                    "DEBUG",
                    "📨 SMS received (len=${fullBody.length}): ${fullBody.take(200)}"
                )

                val payment = PaymentParser.parse("", fullBody) { logMsg ->
                    LogManager.add(context, "DEBUG", logMsg)
                }

                if (payment == null) {
                    LogManager.add(context, "DEBUG", "❌ SMS Parser returned NULL")
                    return
                }

                if (isDuplicate(context, payment.trxId)) {
                    LogManager.add(
                        context,
                        "DUPLICATE",
                        "Ignored duplicate TrxID (SMS): ${PaymentParser.maskTrxId(payment.trxId)}"
                    )
                    return
                }

                LogManager.add(
                    context,
                    "PARSED",
                    "SMS ${payment.method.uppercase()} ৳${payment.amount} from " +
                            "${PaymentParser.maskPhone(payment.senderPhone)} " +
                            "TrxID ${PaymentParser.maskTrxId(payment.trxId)}"
                )

                // Save first (never lose a payment), then send.
                PaymentQueue.enqueue(context, payment)
                PaymentQueue.flush(context)
            } catch (e: Exception) {
                try {
                    LogManager.add(context, "ERROR", "SmsReceiver handle error: ${e.message}")
                } catch (_: Exception) { }
            }
        }

        /**
         * Shared duplicate check — used by NotificationListener too.
         * Returns true if this TrxID should be skipped as duplicate.
         */
        fun isDuplicate(context: Context, trxId: String): Boolean {
            return PaymentQueue.isDuplicate(context, trxId)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (messages.isEmpty()) return

            val fullBodyBuilder = StringBuilder()
            for (msg in messages) {
                fullBodyBuilder.append(msg.displayMessageBody ?: "")
            }

            val fullBody = fullBodyBuilder.toString().trim()
            if (fullBody.isBlank()) return

            val app = context.applicationContext
            val pending: BroadcastReceiver.PendingResult? = goAsync()
            val wakeLock = WakeHelper.acquire(app, 30_000L)

            scope.launch {
                try {
                    // goAsync() allows ~10 seconds. If the network is slow, the payment
                    // stays in the queue and the service retries it.
                    withTimeoutOrNull(9_000L) { handleSms(app, fullBody) }
                } catch (e: Exception) {
                    try {
                        LogManager.add(app, "ERROR", "SmsReceiver error: ${e.message}")
                    } catch (_: Exception) { }
                } finally {
                    try {
                        if (PaymentQueue.pendingCount(app) > 0) {
                            PaymentMonitorService.scheduleRestart(app)
                        }
                    } catch (_: Exception) { }
                    WakeHelper.release(wakeLock)
                    try {
                        pending?.finish()
                    } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            try {
                LogManager.add(context, "ERROR", "SmsReceiver error: ${e.message}")
            } catch (_: Exception) { }
        }
    }
}
