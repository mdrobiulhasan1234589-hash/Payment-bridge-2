package com.drltour.paymentbridge

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PaymentQueue — persistent outbox + duplicate memory.
 *
 * Flow:  SMS / Notification  ->  enqueue()  ->  flush()  ->  server
 * If the network is down (or the phone is in Doze) the payment stays in the
 * queue and is retried automatically by the foreground service, on network
 * return, and on the next SMS. Nothing is lost even if Android kills the app.
 */
object PaymentQueue {

    private const val PREF_NAME = "payment_bridge_queue"
    private const val KEY_ITEMS = "items"
    private const val KEY_SEEN = "seen"

    private const val MAX_ITEMS = 200
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private const val SEEN_WINDOW_MS = 6L * 60L * 60L * 1000L
    private const val MAX_SEEN = 300

    private val lock = Any()
    private val flushMutex = Mutex()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ───────────────────────── Duplicate memory ─────────────────────────

    /** Returns true if this TrxID was already handled recently. Otherwise remembers it. */
    fun isDuplicate(context: Context, trxId: String): Boolean {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val seen = try {
                JSONObject(prefs(context).getString(KEY_SEEN, "{}") ?: "{}")
            } catch (_: Exception) {
                JSONObject()
            }

            // prune old
            val names = ArrayList<String>()
            val keyIter = seen.keys()
            while (keyIter.hasNext()) names.add(keyIter.next())
            for (n in names) {
                if (now - seen.optLong(n, 0L) > SEEN_WINDOW_MS) seen.remove(n)
            }

            // cap size (drop oldest)
            if (seen.length() > MAX_SEEN) {
                val remaining = ArrayList<String>()
                val keyIter2 = seen.keys()
                while (keyIter2.hasNext()) remaining.add(keyIter2.next())
                remaining.sortBy { k -> seen.optLong(k, 0L) }
                val extra = seen.length() - MAX_SEEN
                for (i in 0 until extra) seen.remove(remaining[i])
            }

            if (seen.has(trxId)) {
                prefs(context).edit().putString(KEY_SEEN, seen.toString()).apply()
                return true
            }

            seen.put(trxId, now)
            prefs(context).edit().putString(KEY_SEEN, seen.toString()).apply()
            return false
        }
    }

    // ───────────────────────────── Outbox ─────────────────────────────

    private fun loadItems(context: Context): MutableList<JSONObject> {
        val list = ArrayList<JSONObject>()
        try {
            val arr = JSONArray(prefs(context).getString(KEY_ITEMS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) list.add(o)
            }
        } catch (_: Exception) { }
        return list
    }

    private fun saveItems(context: Context, items: List<JSONObject>) {
        val arr = JSONArray()
        for (o in items) arr.put(o)
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun enqueue(context: Context, p: PaymentParser.PaymentData) {
        synchronized(lock) {
            val items = loadItems(context)
            val o = JSONObject()
            o.put("method", p.method)
            o.put("amount", p.amount)
            o.put("phone", p.senderPhone)
            o.put("trx", p.trxId)
            o.put("msg", p.originalMessage)
            o.put("ts", System.currentTimeMillis())
            items.add(o)
            while (items.size > MAX_ITEMS) items.removeAt(0)
            saveItems(context, items)
        }
    }

    fun pendingCount(context: Context): Int {
        synchronized(lock) {
            return loadItems(context).size
        }
    }

    private fun peek(context: Context): JSONObject? {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val items = loadItems(context)
            val fresh = items.filter { now - it.optLong("ts", now) <= MAX_AGE_MS }
            if (fresh.size != items.size) saveItems(context, fresh)
            return fresh.firstOrNull()
        }
    }

    private fun remove(context: Context, trx: String) {
        synchronized(lock) {
            val items = loadItems(context)
            val kept = items.filter { it.optString("trx") != trx }
            saveItems(context, kept)
        }
    }

    /**
     * Sends everything in the queue (oldest first).
     * Stops at the first temporary failure so order is kept and the next flush retries.
     * Returns how many payments were delivered.
     */
    suspend fun flush(context: Context): Int {
        val app = context.applicationContext
        return flushMutex.withLock { doFlush(app) }
    }

    private suspend fun doFlush(app: Context): Int {
        var sent = 0
        var guard = 0
        while (guard < MAX_ITEMS + 5) {
            guard++
            val item = peek(app)
            if (item == null) break

            val payment = PaymentParser.PaymentData(
                method = item.optString("method"),
                amount = item.optDouble("amount", 0.0),
                senderPhone = item.optString("phone"),
                trxId = item.optString("trx"),
                originalMessage = item.optString("msg")
            )

            val result = ApiClient.sendPayment(app, payment)
            val masked = PaymentParser.maskTrxId(payment.trxId)

            if (result.success) {
                remove(app, payment.trxId)
                sent++
                LogManager.add(app, "SENT", "→ Server: ${result.status} — $masked")
                Prefs.setLastPaymentSummary(app, buildSummary(payment, result.status))
            } else if (result.retry) {
                LogManager.add(app, "RETRY", "Will retry ($masked): ${result.status} — ${result.message}")
                break
            } else {
                // Server answered but said "no" (wrong data etc). Retrying will not help.
                remove(app, payment.trxId)
                LogManager.add(app, "ERROR", "Server rejected ($masked): ${result.status} — ${result.message}")
            }
        }
        return sent
    }

    private fun buildSummary(payment: PaymentParser.PaymentData, status: String): String {
        return try {
            val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.US)
            val maskedPhone = PaymentParser.maskPhone(payment.senderPhone)
            val maskedTrx = PaymentParser.maskTrxId(payment.trxId)
            "$status|${payment.method}|${payment.amount}|$maskedPhone|$maskedTrx|${timeFormat.format(Date())}"
        } catch (_: Exception) {
            ""
        }
    }
}
