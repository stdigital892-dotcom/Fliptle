package com.fliptle.app

import android.content.Context
import android.os.Build

/**
 * Where this copy of the app was installed from. Used only to decide whether to
 * show the manual "how to turn on Accessibility" steps: a Google Play install
 * doesn't need them, while a sideloaded build (a tester's APK) does. When the
 * installer can't be determined it counts as NOT from Play, so the steps show.
 */
object InstallSource {

    const val GOOGLE_PLAY = "com.android.vending"

    fun isFromGooglePlay(context: Context): Boolean {
        val pm = context.packageManager
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        } catch (_: Exception) {
            null
        }
        return installer == GOOGLE_PLAY
    }
}
