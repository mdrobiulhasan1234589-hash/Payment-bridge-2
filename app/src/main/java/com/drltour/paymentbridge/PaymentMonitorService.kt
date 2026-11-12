package com.drltour.paymentbridge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PaymentMonitorService — foreground service that keeps the app process alive.
 *
 * - Foreground type "specialUse" (no 6-hour limit like dataSync on Android 15).
 * - Retries unsent payments every 15 seconds and whenever the network comes back.
 * - A watchdog alarm re-starts the service if the phone/OEM killed it.
 */
class PaymentMonitorService : Service() {

    private var smsReceiver: SmsReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null
    private var keepAwakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        const val CHANNEL_ID = "payment_bridge_monitor"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.drltour.paymentbridge.START"
        const val ACTION_STOP = "com.drltour.paymentbridge.STOP"
        const val ACTION_RESTART = "com.drltour.paymentbridge.RESTART"

        private const val RESTART_REQUEST_CODE = 1001
        private const val RESTART_DELAY_MS = 1500L
        private const val WATCHDOG_DELAY_MS = 3 * 60 * 1000L
        private const val RETRY_INTERVAL_MS = 15_000L

        /** True while the service object exists (used by the heartbeat to know if it must restart it). */
        @Volatile
        var isAlive: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, PaymentMonitorService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PaymentMonitorService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }

        private fun restartPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, PaymentMonitorService::class.java).apply {
                action = ACTION_RESTART
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, RESTART_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(context, RESTART_REQUEST_CODE, intent, flags)
            }
        }

        /** Re-start the service in ~1.5 seconds (used when it is killed or a send is still pending). */
        fun scheduleRestart(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pendingIntent = restartPendingIntent(context)
                val triggerAt = SystemClock.elapsedRealtime() + RESTART_DELAY_MS

                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } catch (_: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } catch (_: Exception) { }
        }

        /** Every 5 minutes: wake up and make sure the service is alive. */
        fun scheduleWatchdog(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pendingIntent = restartPendingIntent(context)
                val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_DELAY_MS
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } catch (_: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } catch (_: Exception) { }
        }

        fun cancelAlarms(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(restartPendingIntent(context))
                HeartbeatReceiver.cancel(context)
            } catch (_: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        createNotificationChannel()
        registerSmsReceiver()
        registerNetworkCallback()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Runtime-registered SMS receiver — Android 16 compatible.
     */
    private fun registerSmsReceiver() {
        try {
            if (smsReceiver != null) return
            smsReceiver = SmsReceiver()
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(smsReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(smsReceiver, filter)
            }
            LogManager.add(this, "INFO", "✅ SMS Receiver REGISTERED (runtime)")
        } catch (e: Exception) {
            LogManager.add(this, "ERROR", "registerSmsReceiver failed: ${e.message}")
        }
    }

    private fun unregisterSmsReceiver() {
        try {
            if (smsReceiver != null) {
                unregisterReceiver(smsReceiver)
                smsReceiver = null
            }
        } catch (_: Exception) { }
    }

    /** When internet comes back (or the phone leaves Doze), send waiting payments immediately. */
    private fun registerNetworkCallback() {
        try {
            if (netCallback != null) return
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    kickFlush()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) { }
    }

    /** Keeps the CPU awake while the screen is off so SMS/notifications are handled instantly. */
    private fun acquireKeepAwake() {
        try {
            if (keepAwakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PaymentBridge:keepawake")
            wl.setReferenceCounted(false)
            wl.acquire()
            keepAwakeLock = wl
        } catch (_: Exception) { }
    }

    private fun releaseKeepAwake() {
        try {
            val wl = keepAwakeLock
            if (wl != null && wl.isHeld) wl.release()
        } catch (_: Exception) { }
        keepAwakeLock = null
    }

    private fun unregisterNetworkCallback() {
        releaseKeepAwake()
        try {
            val cb = netCallback
            if (cb != null) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
                netCallback = null
            }
        } catch (_: Exception) { }
    }

    private fun kickFlush() {
        scope.launch {
            try {
                if (Prefs.isMonitoringEnabled(applicationContext) &&
                    PaymentQueue.pendingCount(applicationContext) > 0
                ) {
                    val wl = WakeHelper.acquire(applicationContext, 30_000L)
                    try {
                        PaymentQueue.flush(applicationContext)
                    } finally {
                        WakeHelper.release(wl)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun startRetryLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            var lastTick = System.currentTimeMillis()
            var rounds = 0
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 60_000L) {
                        LogManager.add(
                            applicationContext,
                            "WARN",
                            "⏸ Phone paused this app for ${(now - lastTick) / 1000}s — check battery / auto-start settings"
                        )
                        SmsScanner.scanRecent(applicationContext)
                    }
                    lastTick = now
                    rounds++
                    if (rounds % 4 == 0) {
                        SmsScanner.scanRecent(applicationContext)
                    }

                    if (Prefs.isMonitoringEnabled(applicationContext) &&
                        PaymentQueue.pendingCount(applicationContext) > 0
                    ) {
                        val wl = WakeHelper.acquire(applicationContext, 30_000L)
                        try {
                            PaymentQueue.flush(applicationContext)
                        } finally {
                            WakeHelper.release(wl)
                        }
                    }
                } catch (_: Exception) { }
                delay(RETRY_INTERVAL_MS)
            }
        }
    }

    private fun beginMonitoring() {
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            LogManager.add(this, "ERROR", "startForeground failed: ${e.message}")
        }
        registerSmsReceiver()
        registerNetworkCallback()
        startRetryLoop()
        scheduleWatchdog(this)
        HeartbeatReceiver.schedule(this)
        acquireKeepAwake()
        scope.launch { SmsScanner.scanRecent(applicationContext) }
        kickFlush()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setMonitoringEnabled(this, false)
                cancelAlarms(this)
                unregisterSmsReceiver()
                unregisterNetworkCallback()
                retryJob?.cancel()
                retryJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                if (Prefs.isMonitoringEnabled(this)) {
                    beginMonitoring()
                    return START_STICKY
                }
                // Monitoring is OFF: we were started as a foreground service,
                // so we must call startForeground once before stopping.
                try {
                    startForegroundCompat()
                } catch (_: Exception) { }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Prefs.setMonitoringEnabled(this, true)
                beginMonitoring()
                return START_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Prefs.isMonitoringEnabled(this)) {
            scheduleRestart(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlive = false
        unregisterSmsReceiver()
        unregisterNetworkCallback()
        scope.cancel()
        if (Prefs.isMonitoringEnabled(this)) {
            scheduleRestart(this)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        openIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, PaymentMonitorService::class.java)
        stopIntent.action = ACTION_STOP
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Payment Bridge")
            .setContentText("Monitoring payments — tap to open")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPending)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Payment Monitor"
            val descriptionText = "Keeps Payment Bridge running in background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
