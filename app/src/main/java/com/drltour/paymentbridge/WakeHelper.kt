package com.drltour.paymentbridge

import android.content.Context
import android.os.PowerManager

/**
 * Short-lived CPU wake lock. Used only while a payment is being sent,
 * so the phone does not fall asleep in the middle of the HTTP request
 * (this is what made the old version "need the app opened for 1 second").
 */
object WakeHelper {

    fun acquire(context: Context, timeoutMs: Long): PowerManager.WakeLock? {
        return try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PaymentBridge:work")
            wl.setReferenceCounted(false)
            wl.acquire(timeoutMs)
            wl
        } catch (_: Exception) {
            null
        }
    }

    fun release(wl: PowerManager.WakeLock?) {
        try {
            if (wl != null && wl.isHeld) wl.release()
        } catch (_: Exception) { }
    }
}
