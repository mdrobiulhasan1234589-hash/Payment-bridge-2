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

    /**
     * @param hint  extra clue about who sent this: the SMS sender address ("NAGAD", "16216")
     *              or the notification's package name. Needed because Nagad / Rocket SMS
     *              bodies usually do NOT contain the words "Nagad" / "Rocket".
     */
    fun parse(
        title: String?,
        text: String?,
        logFn: ((String) -> Unit)? = null,
        hint: String? = null
    ): PaymentData? {
        return try {
            val combined = ("${title ?: ""} ${text ?: ""}").trim()
            if (combined.isBlank()) return null

            // bKash keeps using the ORIGINAL parser, untouched.
            val method = detectProvider(combined)
                ?: detectFromHint(hint)
                ?: detectFromContent(combined)
                ?: run {
                    logFn?.invoke("Parser: no provider")
                    return null
                }

            if (method == "nagad" || method == "rocket") {
                return parseMfs(method, combined, logFn)
            }
            parseOriginal(method, combined, logFn)
        } catch (e: Exception) {
            logFn?.invoke("Parser error: ${e.message}")
            null
        }
    }

    // ───────────────────────── bKash (original, unchanged logic) ─────────────────────────

    private fun parseOriginal(
        method: String,
        combined: String,
        logFn: ((String) -> Unit)?
    ): PaymentData? {
        return try {
            val lower = combined.lowercase()

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

    // ───────────────────────── Nagad + Rocket (lenient parser) ─────────────────────────

    private val MFS_RECEIVE_WORDS = listOf(
        "receive", "received", "cash in", "cash-in", "cashin", "credited", "deposit",
        "পেয়েছেন", "পেয়েছি", "রিসিভ", "ক্যাশ ইন", "ক্যাশইন", "জমা"
    )

    private val MFS_NOT_RECEIVE_WORDS = listOf(
        "cash out", "cash-out", "cashout", "send money", "sent", "recharge",
        "payment successful", "bill pay", "ক্যাশ আউট", "পাঠিয়েছেন"
    )

    private const val NUM = """\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?"""

    private val MFS_AMOUNT_LABELED = Regex(
        """(?:amount|amt)\s*[:\-]?\s*(?:Tk\.?|BDT\.?|৳)?\s*($NUM)""",
        RegexOption.IGNORE_CASE
    )
    private val MFS_AMOUNT_CURRENCY_FIRST = Regex(
        """(?:Tk\.?|BDT\.?|৳)\s*($NUM)""",
        RegexOption.IGNORE_CASE
    )
    private val MFS_AMOUNT_CURRENCY_LAST = Regex(
        """($NUM)\s*(?:Tk\b|BDT\b|taka\b|টাকা|৳)""",
        RegexOption.IGNORE_CASE
    )

    private val MFS_PHONE_LABELED = Regex(
        """(?:sender|from|a/c|acc(?:ount)?|number|num|mobile|wallet|প্রেরক|থেকে)\D{0,15}(?<!\d)(?:\+?88)?(01[3-9][0-9]{8})""",
        RegexOption.IGNORE_CASE
    )
    private val MFS_PHONE_ANY = Regex("""(?<!\d)(?:\+?88)?(01[3-9][0-9]{8})""")

    private val MFS_TRXID_REGEX = Regex(
        """(?:trx\s*id|txn\s*id|tnx\s*id|trn\s*id|tx\s*id|trans(?:action)?\s*(?:id|no\.?|number)|transactionid|reference\s*(?:id|no\.?))\s*(?:is\s+)?[:\-#.=]?\s*([A-Za-z0-9]{6,20})""",
        RegexOption.IGNORE_CASE
    )

    private fun bnDigitsToAscii(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (ch in '\u09E6'..'\u09EF') ('0' + (ch - '\u09E6')) else ch)
        }
        return sb.toString()
    }

    private fun parseMfs(
        method: String,
        rawCombined: String,
        logFn: ((String) -> Unit)?
    ): PaymentData? {
        val combined = bnDigitsToAscii(rawCombined)
        val lower = combined.lowercase()

        val hasReceive = MFS_RECEIVE_WORDS.any { lower.contains(it) }
        val hasNotReceive = MFS_NOT_RECEIVE_WORDS.any { lower.contains(it) }
        if (!hasReceive || (hasNotReceive && !lower.contains("received") && !lower.contains("cash in"))) {
            logFn?.invoke("Parser[$method]: not a money-received message")
            return null
        }

        // Amount
        val amountRaw = (MFS_AMOUNT_LABELED.find(combined)
            ?: MFS_AMOUNT_CURRENCY_FIRST.find(combined)
            ?: MFS_AMOUNT_CURRENCY_LAST.find(combined))
            ?.groupValues?.get(1)
        val amount = amountRaw?.replace(",", "")?.toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            logFn?.invoke("Parser[$method]: amount no match")
            return null
        }

        // TrxID — must contain at least one digit (stops words like "Successful" being taken as an ID)
        val trxId = MFS_TRXID_REGEX.findAll(combined)
            .map { it.groupValues[1].uppercase() }
            .filter { id -> id.any { c -> c.isDigit() } }
            .maxByOrNull { it.length }
        if (trxId == null) {
            logFn?.invoke("Parser[$method]: TrxID no match (waiting for the SMS copy)")
            return null
        }

        // Sender phone — optional for Nagad/Rocket (some notifications hide the number).
        val senderPhone = (MFS_PHONE_LABELED.find(combined) ?: MFS_PHONE_ANY.find(combined))
            ?.groupValues?.get(1) ?: ""

        logFn?.invoke("Parser[$method]: OK — $trxId")

        return PaymentData(
            method = method,
            amount = amount,
            senderPhone = senderPhone,
            trxId = trxId,
            originalMessage = rawCombined
        )
    }

    /** SMS sender address or app package name -> provider. Only Nagad / Rocket (bKash untouched). */
    private fun detectFromHint(hint: String?): String? {
        if (hint.isNullOrBlank()) return null
        val h = hint.lowercase()
        if (h.contains("nagad") || h == "com.konasl.nagad" || h == "com.nagad.app") return "nagad"
        if (h.contains("rocket") || h.contains("dbbl") || h.contains("16216")) return "rocket"
        return null
    }

    /** Last resort: recognise by wording typical for that provider's SMS. */
    private fun detectFromContent(text: String): String? {
        val l = text.lowercase()
        if (l.contains("comission") || l.contains("uddokta") || l.contains("নগদ")) return "nagad"
        if (l.contains("16216") || l.contains("dutch-bangla") || l.contains("dutch bangla") || l.contains("রকেট")) return "rocket"
        return null
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
