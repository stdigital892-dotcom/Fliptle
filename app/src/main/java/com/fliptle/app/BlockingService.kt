package com.fliptle.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat

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

    private val poll = object : Runnable {
        override fun run() {
            enforce()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        freezeStore = FreezeStore(this)
        blockedStore = BlockedAppsStore(this)
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
        val channelId = "blocking_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "App blocking",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

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

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val POLL_MS = 1_000L

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
