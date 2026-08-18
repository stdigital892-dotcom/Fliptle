package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Setup hub, grouped into three sections:
 *   Blocking       — blocked apps, blocked domains, in-app surfaces
 *   Commitment     — the freeze cycle & schedule
 *   Account safety — request to uninstall
 *
 * Deliberately NOT here: detected browsers (handled automatically by
 * [BrowserDetector]), the content-blocklist status page, the URL-blocking
 * disclosure (shown during onboarding where consent is actually given), and the
 * setup wizard re-run. The typing gate is likewise not a standalone entry — it is
 * reachable only as a step inside "Request to uninstall".
 */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Permissions.gate(this)) return
        setContentView(R.layout.activity_setup)

        open(R.id.blockedAppsButton, AppListActivity::class.java)
        open(R.id.blockedDomainsButton, DomainListActivity::class.java)
        open(R.id.surfacesButton, SurfaceBlockActivity::class.java)
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
