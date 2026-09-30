package com.fliptle.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import com.fliptle.app.accessibility.UrlBlockAccessibilityService
import com.fliptle.app.auth.EntitlementStore
import com.fliptle.app.auth.InboxConfirmActivity
import com.google.firebase.auth.FirebaseAuth

/** Central status checks for the permissions the app's features rely on. */
object Permissions {

    /**
     * All enforcement permissions are compulsory for the app to function.
     * Battery-optimization exemption is included deliberately, not just
     * requested once during onboarding: some OEMs (MIUI, ColorOS,
     * FuntouchOS) re-apply battery restriction after a system update or
     * period of inactivity even after the user granted the exemption. Making
     * it part of this gate means [ProtectionGuardActivity] re-nags the
     * instant that happens, the same way it already does for Accessibility.
     */
    fun allEnforcementGranted(context: Context): Boolean =
        hasUsageAccess(context) &&
            hasOverlay(context) &&
            isAccessibilityEnabled(context) &&
            hasBatteryExemption(context)

    /**
     * If any enforcement permission is missing, send the user to the full-screen
     * protection guard (which offers one-tap re-enable) and finish [activity].
     * Returns true when everything is granted and the caller may proceed.
     *
     * The guard is used post-onboarding; initial setup uses OnboardingActivity via
     * the launcher router.
     *
     * Exception: if entitlement is explicitly DENIED, skip the guard entirely and
     * send the user to the renewal screen instead — re-enabling a permission
     * accomplishes nothing while the paywall gate has enforcement paused, since
     * both the accessibility service and BlockingService already no-op for a
     * DENIED user.
     */
    fun gate(activity: Activity): Boolean {
        if (allEnforcementGranted(activity)) return true
        if (EntitlementStore(activity).state == EntitlementStore.State.DENIED) {
            val email = FirebaseAuth.getInstance().currentUser?.email ?: ""
            activity.startActivity(
                Intent(activity, InboxConfirmActivity::class.java)
                    .putExtra(InboxConfirmActivity.EXTRA_EMAIL, email)
            )
            activity.finish()
            return false
        }
        activity.startActivity(Intent(activity, ProtectionGuardActivity::class.java))
        activity.finish()
        return false
    }

    fun hasUsageAccess(context: Context): Boolean = ForegroundApp.hasUsageAccess(context)

    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasBatteryExemption(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

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
