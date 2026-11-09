package com.drltour.paymentbridge

object PaymentParser {

    data class PaymentData(
        val method: String,
        val amount: Double,
        val senderPhone: String,
        val trxId: String,
        val originalMessage: String
    )

    private val RECEIVE_PHRASES = listOf(
        "you have received",
        "you've received",
        "you have got",
        "money received",
        "received tk",
        "received taka",
        "cash in",
        "cash-in",
        "payment received",
        "পেয়েছেন",
        "পেয়েছি"
    )

    private val AMOUNT_REGEX = Regex(
        """(?:Tk\.?|BDT\.?|৳)\s*([0-9]+(?:[.,][0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    private val SENDER_PHONE_REGEX = Regex(
        """(?:from|sender|num(?:ber)?|phone)?\s*(01[3-9][0-9]{8})""",
        RegexOption.IGNORE_CASE
    )

    private val TRXID_REGEX = Regex(
        """(?:trx\s*id|trxid|txn\s*id|txnid|transaction\s*id|transactionid)\s*[:\-]?\s*([A-Za-z0-9]{6,20})""",
        RegexOption.IGNORE_CASE
    )

    fun parse(title: String?, text: String?, logFn: ((String) -> Unit)? = null): PaymentData? {
        return try {
            val combined = ("${title ?: ""} ${text ?: ""}").trim()
            if (combined.isBlank()) return null

            val lower = combined.lowercase()

            val method = detectProvider(combined) ?: run {
                logFn?.invoke("Parser: no provider")
                return null
            }

            val looksLikeReceive = RECEIVE_PHRASES.any { lower.contains(it.lowercase()) }
            if (!looksLikeReceive) {
                logFn?.invoke("Parser: no receive phrase")
                return null
            }

            val amountMatch = AMOUNT_REGEX.find(combined) ?: run {
                logFn?.invoke("Parser: amount no match")
                return null
            }
            val amountRaw = amountMatch.groupValues[1].replace(",", ".")
            val amount = amountRaw.toDoubleOrNull() ?: return null
            if (amount <= 0.0) return null

            val senderMatch = SENDER_PHONE_REGEX.find(combined) ?: return null
            val senderPhone = senderMatch.groupValues[1]
            if (senderPhone.length != 11) return null

            val trxMatch = TRXID_REGEX.find(combined) ?: run {
                logFn?.invoke("Parser: TrxID no match")
                return null
            }
            val trxId = trxMatch.groupValues[1].uppercase()
            if (trxId.length < 6) return null

            logFn?.invoke("Parser: OK — ${trxId}")

            PaymentData(
                method = method,
                amount = amount,
                senderPhone = senderPhone,
                trxId = trxId,
                originalMessage = combined
            )
        } catch (e: Exception) {
            logFn?.invoke("Parser error: ${e.message}")
            null
        }
    }

    private fun detectProvider(text: String): String? {
        val lower = text.lowercase()
        if (lower.contains("bkash")) return "bkash"
        if (lower.contains("nagad")) return "nagad"
        if (lower.contains("rocket") || lower.contains("dbbl")) return "rocket"
        return null
    }

    fun maskPhone(phone: String): String {
        return try {
            if (phone.length != 11) phone
            else phone.substring(0, 4) + "*****" + phone.substring(9)
        } catch (_: Exception) {
            phone
        }
    }

    fun maskTrxId(trxId: String): String {
        return try {
            if (trxId.length <= 6) trxId
            else trxId.substring(0, 3) + "******" + trxId.substring(trxId.length - 2)
        } catch (_: Exception) {
            trxId
        }
    }
}
