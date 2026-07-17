package com.fliptle.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import android.text.TextUtils
import com.fliptle.app.accessibility.UrlBlockAccessibilityService

/** Central status checks for the permissions the app's features rely on. */
object Permissions {

    /** All enforcement permissions are compulsory for the app to function. */
    fun allEnforcementGranted(context: Context): Boolean =
        hasUsageAccess(context) &&
            hasOverlay(context) &&
            hasVpnConsent(context) &&
            isAccessibilityEnabled(context)

    /**
     * If any enforcement permission is missing, send the user back to onboarding
     * (which resumes at the first missing permission) and finish [activity].
     * Returns true when everything is granted and the caller may proceed.
     */
    fun gate(activity: Activity): Boolean {
        if (allEnforcementGranted(activity)) return true
        activity.startActivity(Intent(activity, OnboardingActivity::class.java))
        activity.finish()
        return false
    }

    fun hasUsageAccess(context: Context): Boolean = ForegroundApp.hasUsageAccess(context)

    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** VpnService.prepare returns null once the user has granted VPN consent. */
    fun hasVpnConsent(context: Context): Boolean = VpnService.prepare(context) == null

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${UrlBlockAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
