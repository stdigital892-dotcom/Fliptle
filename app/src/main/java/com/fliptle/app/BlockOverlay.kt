package com.fliptle.app

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button

/**
 * A single, persistent full-screen "Website blocked" overlay drawn on top of the
 * browser when a blocked URL is detected. Unlike navigating back/home, this does
 * NOT create a tab cascade — it just covers the page. It is idempotent (calling
 * [show] repeatedly does nothing while it is already up), so repeated block
 * detections don't stack.
 *
 * Shown by the accessibility service; hidden by it when the page is no longer
 * blocked, and by [BlockingService] when the foreground app is not a browser
 * (that's how it comes down when the user leaves the browser).
 */
object BlockOverlay {

    private var view: View? = null
    private var windowManager: WindowManager? = null

    val isShowing: Boolean get() = view != null

    fun show(context: Context) {
        if (view != null) return
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val v = LayoutInflater.from(app).inflate(R.layout.overlay_website_blocked, null)
        v.findViewById<Button>(R.id.blockGoHomeButton).setOnClickListener {
            hide()
            app.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Touchable (captures taps so the page can't be used) but NOT focusable
        // (keeps the browser as the active window so we can still read its URL).
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        )
        try {
            wm.addView(v, params)
            view = v
            windowManager = wm
        } catch (_: Exception) {
            view = null
        }
    }

    fun hide() {
        val v = view ?: return
        try {
            windowManager?.removeView(v)
        } catch (_: Exception) {
        }
        view = null
    }
}
