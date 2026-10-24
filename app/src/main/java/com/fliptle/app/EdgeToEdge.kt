package com.fliptle.app

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Edge-to-edge is enforced from Android 15 (targetSdk 35+): the window draws
 * under the status and navigation bars by default, and an app can no longer
 * opt out. Without padding for it, content at the very top or bottom of a
 * screen (a title, a docked button) can sit under a system bar.
 *
 * Applying the system-bar insets as padding on the root content view
 * ([android.R.id.content]) is the smallest fix that works across every
 * existing layout without editing each one's XML -- call this once, right
 * after setContentView(), from every Activity.
 */
object EdgeToEdge {
    fun applyInsets(activity: Activity) {
        val root = activity.window?.decorView?.findViewById<android.view.View>(android.R.id.content)
            ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
