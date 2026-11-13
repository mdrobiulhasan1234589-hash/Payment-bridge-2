package com.drltour.paymentbridge

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvAccessStatus: TextView
    private lateinit var tvBackendStatus: TextView
    private lateinit var tvLastPayment: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOpenAccess: Button
    private lateinit var btnTestConnection: Button
    private lateinit var btnSettings: Button
    private lateinit var btnLogs: Button
    private lateinit var btnHistory: Button
    private lateinit var btnKeepAlive: Button

    private var checklistContainer: LinearLayout? = null
    private var checklistDialog: AlertDialog? = null

    private val SMS_PERMISSION_CODE = 200
    private val NOTIF_PERMISSION_CODE = 201
    private var lastTestTime = 0L
    private val TEST_CACHE_MS = 60_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        requestNotificationPermissionIfNeeded()
        requestSmsPermissionIfNeeded()

        if (!Prefs.isSetupShown(this)) {
            Prefs.setSetupShown(this, true)
            showChecklistDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUiState()
        checklistContainer?.let { populateChecklist(it) }

        if (Prefs.isMonitoringEnabled(this)) {
            PaymentMonitorService.start(this)
        }

        val now = System.currentTimeMillis()
        val lastCheck = Prefs.getBackendLastCheck(this)
        if (Prefs.getBackendUrl(this).isNotBlank() &&
            Prefs.getBridgeSecret(this).isNotBlank() &&
            (now - lastCheck) > TEST_CACHE_MS
        ) {
            runConnectionTestSilent()
        }
    }

    private fun bindViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvAccessStatus = findViewById(R.id.tvAccessStatus)
        tvBackendStatus = findViewById(R.id.tvBackendStatus)
        tvLastPayment = findViewById(R.id.tvLastPayment)
        btnStart = findViewById(R.id.btnStartMonitoring)
        btnStop = findViewById(R.id.btnStopMonitoring)
        btnOpenAccess = findViewById(R.id.btnOpenAccess)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnSettings = findViewById(R.id.btnSettings)
        btnLogs = findViewById(R.id.btnLogs)
        btnHistory = findViewById(R.id.btnHistory)
        btnKeepAlive = findViewById(R.id.btnKeepAlive)
    }

    private fun setupListeners() {
        btnStart.setOnClickListener {
            if (!hasSmsPermission()) {
                showToast("Please grant SMS permission first")
                requestSmsPermissionIfNeeded()
                return@setOnClickListener
            }
            Prefs.setMonitoringEnabled(this, true)
            PaymentMonitorService.start(this)
            LogManager.add(this, "INFO", "Monitoring started via foreground service")
            refreshUiState()
        }

        btnStop.setOnClickListener {
            Prefs.setMonitoringEnabled(this, false)
            PaymentMonitorService.stop(this)
            LogManager.add(this, "INFO", "Monitoring stopped by user")
            refreshUiState()
        }

        btnOpenAccess.setOnClickListener { openNotificationAccessSettings() }
        btnTestConnection.setOnClickListener { runConnectionTest() }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnLogs.setOnClickListener { showLogsDialog() }
        btnHistory.setOnClickListener { showHistoryDialog() }
        btnKeepAlive.setOnClickListener { showChecklistDialog() }
    }

    private fun refreshUiState() {
        val hasAccess = isNotificationAccessGranted()
        tvAccessStatus.text = if (hasAccess) "CONNECTED ✓" else "NOT CONNECTED"
        tvAccessStatus.setTextColor(getColor(if (hasAccess) R.color.status_ok else R.color.status_error))

        val monitoring = Prefs.isMonitoringEnabled(this)
        val serviceRunning = monitoring && Prefs.getBackendUrl(this).isNotBlank()
        if (serviceRunning && !isBatteryUnrestricted()) {
            tvServiceStatus.text = "RUNNING ⚠ set battery: Unrestricted"
            tvServiceStatus.setTextColor(getColor(R.color.status_warn))
        } else {
            tvServiceStatus.text = if (serviceRunning) "RUNNING ✓" else "STOPPED"
            tvServiceStatus.setTextColor(getColor(if (serviceRunning) R.color.status_ok else R.color.status_error))
        }

        val hasUrl = Prefs.getBackendUrl(this).isNotBlank()
        val hasSecret = Prefs.getBridgeSecret(this).isNotBlank()
        if (hasUrl && hasSecret) {
            if (Prefs.isBackendConnected(this)) {
                tvBackendStatus.text = "CONNECTED ✓"
                tvBackendStatus.setTextColor(getColor(R.color.status_ok))
            } else {
                val lastCheck = Prefs.getBackendLastCheck(this)
                if (lastCheck > 0) {
                    tvBackendStatus.text = "FAILED"
                    tvBackendStatus.setTextColor(getColor(R.color.status_error))
                } else {
                    tvBackendStatus.text = "NOT TESTED"
                    tvBackendStatus.setTextColor(getColor(R.color.status_warn))
                }
            }
        } else {
            tvBackendStatus.text = "NOT CONFIGURED"
            tvBackendStatus.setTextColor(getColor(R.color.status_warn))
        }

        btnStart.isEnabled = !monitoring
        btnStop.isEnabled = monitoring

        refreshLastPayment()
    }

    private fun refreshLastPayment() {
        val pending = PaymentQueue.pendingCount(this)
        val summary = Prefs.getLastPaymentSummary(this)
        if (summary.isBlank()) {
            tvLastPayment.text = if (pending > 0) "No payments yet\nWaiting to send: $pending" else "No payments yet"
            return
        }
        try {
            val parts = summary.split("|")
            if (parts.size >= 5) {
                val status = parts[0]
                val method = parts[1].uppercase()
                val amount = parts[2]
                val phone = parts[3]
                val trx = parts[4]
                tvLastPayment.text = buildString {
                    append("Method: $method\n")
                    append("Amount: ৳$amount\n")
                    append("Sender: $phone\n")
                    append("TrxID: $trx\n")
                    append("Status: $status")
                    if (pending > 0) append("\nWaiting to send: $pending")
                }
            } else {
                tvLastPayment.text = "No payments yet"
            }
        } catch (_: Exception) {
            tvLastPayment.text = "No payments yet"
        }
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSmsPermissionIfNeeded() {
        if (!hasSmsPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
                ),
                SMS_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_CODE) {
            if (hasSmsPermission()) {
                showToast("SMS permission granted ✓")
            } else {
                showToast("SMS permission is required")
            }
        }
        checklistContainer?.let { populateChecklist(it) }
    }

    private fun isNotificationAccessGranted(): Boolean {
        return try {
            val enabled = NotificationManagerCompat.getEnabledListenerPackages(this)
            enabled.contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun openNotificationAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                startActivity(intent)
            } catch (_: Exception) { }
        }
    }

    private fun runConnectionTest() {
        tvBackendStatus.text = "TESTING..."
        tvBackendStatus.setTextColor(getColor(R.color.status_warn))

        activityScope.launch {
            val result = ApiClient.testConnection(this@MainActivity)
            withContext(Dispatchers.Main) {
                Prefs.setBackendLastCheck(this@MainActivity, System.currentTimeMillis())
                Prefs.setBackendConnected(this@MainActivity, result.success)

                if (result.success) {
                    tvBackendStatus.text = "CONNECTED ✓"
                    tvBackendStatus.setTextColor(getColor(R.color.status_ok))
                    showToast("Backend reachable ✓")
                } else {
                    tvBackendStatus.text = "FAILED: ${result.status}"
                    tvBackendStatus.setTextColor(getColor(R.color.status_error))
                    showToast("Backend error: ${result.message}")
                }
            }
        }
    }

    private fun runConnectionTestSilent() {
        activityScope.launch {
            val result = ApiClient.testConnection(this@MainActivity)
            withContext(Dispatchers.Main) {
                val wasConnected = Prefs.isBackendConnected(this@MainActivity)
                Prefs.setBackendLastCheck(this@MainActivity, System.currentTimeMillis())
                Prefs.setBackendConnected(this@MainActivity, result.success)

                if (result.success != wasConnected) {
                    if (result.success) {
                        tvBackendStatus.text = "CONNECTED ✓"
                        tvBackendStatus.setTextColor(getColor(R.color.status_ok))
                    } else {
                        tvBackendStatus.text = "FAILED"
                        tvBackendStatus.setTextColor(getColor(R.color.status_error))
                    }
                }
            }
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etUrl = view.findViewById<EditText>(R.id.etBackendUrl)
        val etSecret = view.findViewById<EditText>(R.id.etBridgeSecret)

        etUrl.setText(Prefs.getBackendUrl(this))
        etSecret.setText(Prefs.getBridgeSecret(this))

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val url = etUrl.text.toString().trim()
                val secret = etSecret.text.toString().trim()

                if (url.isBlank()) {
                    showToast("Backend URL cannot be empty")
                    return@setPositiveButton
                }
                if (!url.startsWith("https://")) {
                    showToast("URL must start with https://")
                    return@setPositiveButton
                }

                Prefs.setBackendUrl(this, url)
                Prefs.setBridgeSecret(this, secret)
                LogManager.add(this, "INFO", "Settings updated")

                Prefs.setBackendConnected(this, false)
                Prefs.setBackendLastCheck(this, 0L)
                runConnectionTestSilent()

                refreshUiState()
                showToast("Settings saved ✓")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogsDialog() {
        val logs = LogManager.getList(this)
        val message = if (logs.isEmpty()) "No logs yet" else logs.joinToString("\n\n")

        val scrollView = ScrollView(this).apply {
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#0A0A1A"))
        }

        val textView = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 12f
            setLineSpacing(8f, 1f)
            setPadding(10, 10, 10, 10)
            setTextIsSelectable(true)
        }

        scrollView.addView(
            textView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("📋 Logs")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                LogManager.clear(this)
                showToast("Logs cleared")
            }
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.background_dark)
            val titleId = resources.getIdentifier("alertTitle", "id", "android")
            if (titleId > 0) {
                dialog.findViewById<TextView>(titleId)?.setTextColor(Color.WHITE)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#3355FF"))
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.parseColor("#FF9800"))
        }

        dialog.show()
    }

    private fun showHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("History")
            .setMessage("SMS history is available in the Logs screen.")
            .setPositiveButton("OK", null)
            .show()
    }

    // ─────────────────────── Keep-alive / permission checklist ───────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun isBatteryUnrestricted(): Boolean {
        return try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                openAppDetails()
            }
        }
    }

    private fun openAppDetails() {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) { }
    }

    /** Opens the phone-maker "Auto-start / background" screen if we know it, otherwise App info. */
    private fun openAutoStartSettings() {
        val candidates = listOf(
            ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")
        )
        for (c in candidates) {
            try {
                val intent = Intent()
                intent.component = c
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                return
            } catch (_: Exception) { }
        }
        openAppDetails()
        showToast("Enable Auto-start / Background activity / No restrictions for this app")
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.contains("$packageName/", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun canExactAlarms(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val am = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                am.canScheduleExactAlarms()
            } else {
                true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun openExactAlarmSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun addCheckRow(
        container: LinearLayout,
        title: String,
        ok: Boolean?,
        buttonText: String,
        action: () -> Unit
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))

        val tv = TextView(this)
        val mark = if (ok == true) "✅ " else if (ok == false) "❌ " else "⚠️ "
        tv.text = mark + title
        tv.setTextColor(Color.WHITE)
        tv.textSize = 14f
        row.addView(tv)

        if (ok != true) {
            val b = Button(this)
            b.text = buttonText
            b.setOnClickListener { action() }
            row.addView(b)
        }
        container.addView(row)
    }

    private fun populateChecklist(container: LinearLayout) {
        container.removeAllViews()

        addCheckRow(container, "1. SMS permission", hasSmsPermission(), "ALLOW") {
            requestSmsPermissionIfNeeded()
        }
        addCheckRow(container, "2. Notification permission", hasNotificationPermission(), "ALLOW") {
            requestNotificationPermissionIfNeeded()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addCheckRow(
                container,
                "3. Android 13+: open App info → ⋮ (top right) → \"Allow restricted settings\" (needed for installed APK, do this BEFORE step 4)",
                null,
                "OPEN APP INFO"
            ) { openAppDetails() }
        }
        addCheckRow(container, "4. Notification access", isNotificationAccessGranted(), "OPEN") {
            openNotificationAccessSettings()
        }
        addCheckRow(container, "5. Battery: Unrestricted / Don't optimize", isBatteryUnrestricted(), "ALLOW") {
            requestIgnoreBatteryOptimizations()
        }
        addCheckRow(
            container,
            "6. Auto-start / Background activity = ON (Infinix, Tecno, Xiaomi, Oppo, Vivo...)",
            null,
            "OPEN"
        ) { openAutoStartSettings() }
        addCheckRow(
            container,
            "7. Backend URL + Secret",
            Prefs.getBackendUrl(this).isNotBlank() && Prefs.getBridgeSecret(this).isNotBlank(),
            "SET"
        ) { showSettingsDialog() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addCheckRow(
                container,
                "8. Alarms & reminders permission (lets Android restart the app if the phone kills it)",
                canExactAlarms(),
                "ALLOW"
            ) { openExactAlarmSettings() }
        }

        addCheckRow(
            container,
            "9. Accessibility: Payment Bridge = ON (phone app ke freeze korle o notification instantly dhore. Screen read kore na.)",
            isAccessibilityEnabled(),
            "OPEN"
        ) { openAccessibilitySettings() }

        val tip = TextView(this)
        tip.text = "Tip: Recent apps e Payment Bridge ke lock (🔒) koro ar app swipe kore close korba na (Home button chapo). Screen off e o payment push hobe."
        tip.setTextColor(Color.parseColor("#8A95C9"))
        tip.textSize = 12f
        tip.setPadding(0, dp(12), 0, 0)
        container.addView(tip)
    }

    private fun showChecklistDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(20), dp(12), dp(20), dp(12))
        populateChecklist(container)

        val scroll = ScrollView(this)
        scroll.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        checklistContainer = container
        val dialog = AlertDialog.Builder(this)
            .setTitle("Keep-Alive Setup")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .create()

        dialog.setOnDismissListener {
            checklistContainer = null
            checklistDialog = null
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.background_dark)
            val titleId = resources.getIdentifier("alertTitle", "id", "android")
            if (titleId > 0) {
                dialog.findViewById<TextView>(titleId)?.setTextColor(Color.WHITE)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#3355FF"))
        }
        checklistDialog = dialog
        dialog.show()
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_PERMISSION_CODE
                )
            }
        }
    }
}
