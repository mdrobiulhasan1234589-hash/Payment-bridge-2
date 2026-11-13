package com.drltour.paymentbridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HeartbeatReceiver — fires every ~5 seconds through AlarmManager.
 *
 * Why: some phones freeze the app process (even with a foreground service + wake lock),
 * so an SMS / notification is only processed when the user opens the app.
 * An exact alarm is delivered by the system itself, and the system un-freezes the app to
 * deliver it. Each tick:  1) schedules the next tick  2) makes sure the service is alive
 * 3) reads new SMS from the inbox and sends any payment that is not sent yet.
 *
 * Worst-case delay for a payment when the phone froze the app ≈ 5 seconds.
 */
class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val REQUEST_CODE = 1002
        private const val INTERVAL_MS = 5_000L
        private const val ACTION_BEAT = "com.drltour.paymentbridge.HEARTBEAT"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun beatIntent(context: Context): PendingIntent {
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_BEAT
                setPackage(context.packageName)
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        fun schedule(context: Context) {
            try {
                val app = context.applicationContext
                val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = beatIntent(app)
                val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } catch (_: SecurityException) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (_: Exception) { }
        }

        fun cancel(context: Context) {
            try {
                val app = context.applicationContext
                val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(beatIntent(app))
            } catch (_: Exception) { }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        try {
            if (!Prefs.isMonitoringEnabled(app)) return

            // Next tick FIRST, so the chain can never break.
            schedule(app)

            val pending: BroadcastReceiver.PendingResult? = goAsync()
            val wl = WakeHelper.acquire(app, 20_000L)

            scope.launch {
                try {
                    withTimeoutOrNull(8_000L) {
                        if (!PaymentMonitorService.isAlive) {
                            try {
                                PaymentMonitorService.start(app)
                            } catch (_: Exception) { }
                        }
                        SmsScanner.scanRecent(app)
                        if (PaymentQueue.pendingCount(app) > 0) {
                            PaymentQueue.flush(app)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    WakeHelper.release(wl)
                    try {
                        pending?.finish()
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }
}
