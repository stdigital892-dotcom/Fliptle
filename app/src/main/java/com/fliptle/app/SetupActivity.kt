package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Setup hub with just two top-level entries:
 *   Freeze         — "Freeze & schedule". Blocked apps, blocked domains and Shorts
 *                    and Reels live INSIDE this screen, because the freeze cycle
 *                    governs all three together — they are not independent settings
 *                    and are deliberately not siblings here.
 *   Account safety — "Request to uninstall".
 *
 * Deliberately NOT here: detected browsers (handled automatically by
 * [BrowserDetector]), the content-blocklist status page, the URL-blocking
 * disclosure (shown during onboarding where consent is given), and the setup
 * wizard re-run. The typing gate is reachable only as a step inside "Request to
 * uninstall".
 */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Permissions.gate(this)) return
        setContentView(R.layout.activity_setup)

        open(R.id.scheduleButton, FreezeActivity::class.java)
        open(R.id.uninstallButton, UninstallRequestActivity::class.java)
    }

    override fun onResume() {
        super.onResume()
        Permissions.gate(this)
    }

    private fun open(buttonId: Int, target: Class<*>) {
        findViewById<Button>(buttonId).setOnClickListener {
            startActivity(Intent(this, target))
        }
    }
}
