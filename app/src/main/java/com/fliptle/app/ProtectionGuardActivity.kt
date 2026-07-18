package com.fliptle.app

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen "protection is OFF" screen shown whenever an enforcement permission
 * (Accessibility, VPN, usage access, overlay) is missing after setup. It offers
 * one-tap buttons to re-enable each missing piece and blocks the rest of the app
 * until protection is restored.
 *
 * HARD RULE: this screen is always exitable. Back and Home are NOT overridden, so
 * the phone stays fully usable (calls, emergencies). It nags relentlessly by being
 * re-launched from [BlockingService], but it never traps the device.
 */
class ProtectionGuardActivity : AppCompatActivity() {

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) DnsVpnService.start(this)
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protection_guard)

        findViewById<Button>(R.id.enableAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.enableVpnButton).setOnClickListener {
            val consent = VpnService.prepare(this)
            if (consent != null) vpnLauncher.launch(consent) else DnsVpnService.start(this)
            render()
        }
        findViewById<Button>(R.id.enableUsageButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.enableOverlayButton).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Protection fully restored -> leave the guard and return to Home.
        if (Permissions.allEnforcementGranted(this)) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }
        render()
    }

    private fun render() {
        showIfMissing(R.id.enableAccessibilityButton, Permissions.isAccessibilityEnabled(this))
        showIfMissing(R.id.enableVpnButton, Permissions.hasVpnConsent(this))
        showIfMissing(R.id.enableUsageButton, Permissions.hasUsageAccess(this))
        showIfMissing(R.id.enableOverlayButton, Permissions.hasOverlay(this))
    }

    private fun showIfMissing(buttonId: Int, granted: Boolean) {
        findViewById<Button>(buttonId).visibility = if (granted) View.GONE else View.VISIBLE
    }
}
