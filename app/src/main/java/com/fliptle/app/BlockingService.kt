package com.fliptle.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that continuously reads the usage sensor and, while a freeze
 * is active, draws a full-screen overlay over any blocked app that comes to the
 * foreground. Runs as START_STICKY so the system restarts it if killed, and is
 * (re)started on boot by [BootReceiver].
 */
class BlockingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var freezeStore: FreezeStore
    private lateinit var blockedStore: BlockedAppsStore
    private var overlayView: View? = null

    private var lastGuardLaunchMs = 0L
    private var lastProtectionOff: Boolean? = null

    /** Re-prompt immediately on unlock (a listed trigger). */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT &&
                !Permissions.allEnforcementGranted(this@BlockingService)
            ) {
                lastGuardLaunchMs = 0L // bypass cooldown for unlock
                maybeLaunchGuard()
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            enforce()
            checkProtection()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        freezeStore = FreezeStore(this)
        blockedStore = BlockedAppsStore(this)
        ContextCompat.registerReceiver(
            this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        handler.removeCallbacks(poll)
        handler.post(poll)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(poll)
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        hideOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Core rule: block only while a freeze is active AND the app is on the list. */
    private fun enforce() {
        val freezeActive = freezeStore.active
        val fg = ForegroundApp.current(this)
        val shouldBlock = freezeActive &&
            fg != null &&
            fg != packageName &&
            blockedStore.get().contains(fg)

        if (shouldBlock) showOverlay() else hideOverlay()
    }

    /**
     * If protection is off, keep the persistent notification in the "OFF" state and
     * re-launch the full-screen guard (throttled, and never over the phone/dialer,
     * so calls and emergencies are never blocked).
     */
    private fun checkProtection() {
        val protectionOff = !Permissions.allEnforcementGranted(this)
        if (protectionOff != lastProtectionOff) {
            lastProtectionOff = protectionOff
            updateNotification(protectionOff)
        }
        if (protectionOff) maybeLaunchGuard()
    }

    private fun maybeLaunchGuard() {
        val fg = ForegroundApp.current(this)
        // Skip when our own screen is up, foreground is unknown, or the user is in
        // an app we must never interrupt: the dialer (calls/emergencies), and the
        // Settings / VPN-consent screens where they actually re-enable protection.
        if (fg == null || fg == packageName || isSkipApp(fg)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastGuardLaunchMs < GUARD_MIN_INTERVAL_MS) return
        lastGuardLaunchMs = now

        try {
            startActivity(
                Intent(this, ProtectionGuardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (_: Exception) {
            // Background activity start blocked (e.g. overlay permission revoked);
            // the persistent notification still leads the user back to re-enable.
        }
    }

    private fun isSkipApp(pkg: String): Boolean {
        if (pkg in NO_NAG_PACKAGES) return true
        return try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            pkg == tm?.defaultDialerPackage
        } catch (_: Exception) {
            false
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return // permission not granted yet

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_blocked, null)
        view.findViewById<Button>(R.id.returnHomeButton).setOnClickListener {
            hideOverlay()
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (_: Exception) {
            overlayView = null
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // already detached
        }
        overlayView = null
    }

    private fun startInForeground() {
        ensureChannel()
        val notification = buildNotification(protectionOff = !Permissions.allEnforcementGranted(this))
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

    private fun updateNotification(protectionOff: Boolean) {
        ensureChannel()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(protectionOff))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "App blocking", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(protectionOff: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true) // non-dismissable while the service runs
        if (protectionOff) {
            val tapIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, ProtectionGuardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentTitle(getString(R.string.guard_notif_title))
                .setContentText(getString(R.string.guard_notif_text))
                .setContentIntent(tapIntent)
        } else {
            builder.setContentTitle(getString(R.string.service_title))
                .setContentText(getString(R.string.service_text))
        }
        return builder.build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "blocking_service"
        private const val POLL_MS = 1_000L
        private const val GUARD_MIN_INTERVAL_MS = 6_000L

        // Never nag over these: calls/emergencies must stay usable, and the
        // Settings / VPN-consent screens are where the user re-enables protection.
        private val NO_NAG_PACKAGES = setOf(
            // Dialer / telephony
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.incallui",
            // Settings + system consent dialogs (where protection is re-enabled)
            "com.android.settings",
            "com.samsung.android.settings",
            "com.android.vpndialogs"
        )

        /** Start (or ensure running) the blocking service. */
        fun start(context: Context) {
            val intent = Intent(context, BlockingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
