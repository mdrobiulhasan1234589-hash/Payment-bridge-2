package com.drltour.paymentbridge

/** Which apps' notifications may contain a payment message (payment apps + SMS apps). */
object PaymentSources {
    fun isPaymentSourcePackage(pkg: String): Boolean {
        // Payment apps
        if (pkg.equals("com.bKash.customerapp", ignoreCase = true)) return true
        if (pkg == "com.konasl.nagad" || pkg == "com.nagad.app") return true
        if (pkg == "com.dbbl.mbs.apps.rocket" || pkg == "com.dbbl.mbs") return true

        // SMS / Messages apps (Infinix/Tecno, Google, Samsung, vivo/iQOO, Xiaomi, Oppo...)
        if (pkg.startsWith("com.transsion.")) return true
        if (pkg.startsWith("com.google.android.apps.messaging")) return true
        if (pkg.startsWith("messages.android_")) return true
        if (pkg == "com.android.mms" || pkg == "com.android.messaging" || pkg == "com.samsung.android.messaging") return true
        if (pkg.startsWith("com.vivo.") && (pkg.contains("mms") || pkg.contains("message") || pkg.contains("sms"))) return true
        if (pkg.startsWith("com.iqoo.") && (pkg.contains("mms") || pkg.contains("message") || pkg.contains("sms"))) return true
        if (pkg.startsWith("com.miui.") && (pkg.contains("mms") || pkg.contains("message") || pkg.contains("sms"))) return true
        if (pkg.startsWith("com.coloros.") && (pkg.contains("mms") || pkg.contains("message") || pkg.contains("sms"))) return true
        if (pkg.startsWith("com.oplus.") && (pkg.contains("mms") || pkg.contains("message") || pkg.contains("sms"))) return true
        return false
    }
}
