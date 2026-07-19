package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.accessibility.AccessibilityDisclosureActivity

/** Setup hub: manage what's blocked, the freeze schedule, and permissions. */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Permissions.gate(this)) return
        setContentView(R.layout.activity_setup)

        open(R.id.blockedAppsButton, AppListActivity::class.java)
        open(R.id.blockedDomainsButton, DomainListActivity::class.java)
        open(R.id.surfacesButton, SurfaceBlockActivity::class.java)
        open(R.id.browsersButton, BrowserListActivity::class.java)
        open(R.id.blocklistButton, BlocklistStatusActivity::class.java)
        open(R.id.scheduleButton, FreezeActivity::class.java)
        open(R.id.a11yButton, AccessibilityDisclosureActivity::class.java)
        open(R.id.typingGateButton, TypingGateActivity::class.java)
        open(R.id.wizardButton, OnboardingActivity::class.java)
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
