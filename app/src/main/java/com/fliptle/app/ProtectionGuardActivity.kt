package com.fliptle.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen "protection is OFF" screen shown whenever an enforcement permission
 * (Accessibility, usage access, overlay, battery-optimization exemption) is
 * missing after setup. It offers one-tap buttons to re-enable each missing
 * piece and blocks the rest of the app until protection is restored.
 *
 * HARD RULE: this screen is always exitable. Back and Home are NOT overridden, so
 * the phone stays fully usable (calls, emergencies). It nags relentlessly by being
 * re-launched from [BlockingService], but it never traps the device.
 */
class ProtectionGuardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // BlockingService can launch this directly; it must never appear for a
        // user who has not cleared the paywall.
        if (!PaywallGate.gate(this)) return
        setContentView(R.layout.activity_protection_guard)

        findViewById<Button>(R.id.enableAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.enableUsageButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.enableOverlayButton).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.enableBatteryButton).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                // Some OEM skins don't implement this standard dialog; fall back
                // to the app's own battery settings screen.
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!PaywallGate.gate(this)) return
        // Protection fully restored — hand off to MainActivity's routing chain
        // rather than jumping straight to Home; a user whose entitlement
        // lapsed while permissions were being re-granted still has to clear
        // the inbox-confirm / paywall gate before any protection surface
        // appears.
        if (Permissions.allEnforcementGranted(this)) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }
        render()
    }

    private fun render() {
        showIfMissing(R.id.enableAccessibilityButton, Permissions.isAccessibilityEnabled(this))
        showIfMissing(R.id.enableUsageButton, Permissions.hasUsageAccess(this))
        showIfMissing(R.id.enableOverlayButton, Permissions.hasOverlay(this))
        showIfMissing(R.id.enableBatteryButton, Permissions.hasBatteryExemption(this))
    }

    private fun showIfMissing(buttonId: Int, granted: Boolean) {
        findViewById<Button>(buttonId).visibility = if (granted) View.GONE else View.VISIBLE
    }
}
