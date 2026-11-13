package com.drltour.paymentbridge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shared "duplicate check -> save to queue -> send now" step. */
object PaymentIngest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun submit(context: Context, source: String, payment: PaymentParser.PaymentData) {
        val app = context.applicationContext
        scope.launch {
            val wl = WakeHelper.acquire(app, 30_000L)
            try {
                if (PaymentQueue.isDuplicate(app, payment.trxId)) {
                    LogManager.add(
                        app,
                        "DUPLICATE",
                        "Ignored duplicate ($source): ${PaymentParser.maskTrxId(payment.trxId)}"
                    )
                } else {
                    LogManager.add(
                        app,
                        "PARSED",
                        "$source ${payment.method.uppercase()} ৳${payment.amount} from " +
                                "${PaymentParser.maskPhone(payment.senderPhone)} " +
                                "TrxID ${PaymentParser.maskTrxId(payment.trxId)}"
                    )
                    PaymentQueue.enqueue(app, payment)
                    PaymentQueue.flush(app)
                }
            } catch (e: Exception) {
                try {
                    LogManager.add(app, "ERROR", "$source send error: ${e.message}")
                } catch (_: Exception) { }
            } finally {
                WakeHelper.release(wl)
            }
        }
    }
}
