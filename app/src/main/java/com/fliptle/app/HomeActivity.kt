package com.fliptle.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fliptle.app.auth.PhoneAuthActivity
import kotlin.math.ceil

/** Calm, minimal home: freeze state, taper tier, and what's blocked at a glance. */
class HomeActivity : AppCompatActivity() {

    private lateinit var freezeStore: FreezeStore
    private lateinit var taper: TaperStore

    private lateinit var freezeStatusText: TextView
    private lateinit var taperText: TextView
    private lateinit var blockedText: TextView
    private lateinit var reviewBanner: LinearLayout
    private lateinit var reviewButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compulsory permissions: if any is missing, bounce back to onboarding.
        if (!Permissions.gate(this)) return
        setContentView(R.layout.activity_home)
        freezeStore = FreezeStore(this)
        taper = TaperStore(this)

        freezeStatusText = findViewById(R.id.freezeStatusText)
        taperText = findViewById(R.id.taperText)
        blockedText = findViewById(R.id.blockedText)
        reviewBanner = findViewById(R.id.reviewBanner)
        reviewButton = findViewById(R.id.reviewButton)

        findViewById<Button>(R.id.freezeButton).setOnClickListener {
            startActivity(Intent(this, FreezeActivity::class.java))
        }
        findViewById<Button>(R.id.setupButton).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<Button>(R.id.accountButton).setOnClickListener {
            startActivity(Intent(this, PhoneAuthActivity::class.java))
        }
        reviewButton.setOnClickListener {
            startActivity(Intent(this, FreezeActivity::class.java))
        }

        // Foreground-safe startup: enforcement service + browser auto-block.
        requestNotificationPermissionIfNeeded()
        BrowserDetector.autoBlockInstalledBrowsers(this)
        BlockingService.start(this)
        // Resume the website filter if the user already granted VPN consent.
        if (Permissions.hasVpnConsent(this)) DnsVpnService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // Re-check on every return; a revoked permission blocks the main flow.
        if (!Permissions.gate(this)) return
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun render() {
        when (freezeStore.state()) {
            FreezeStore.State.NONE -> {
                freezeStatusText.setText(R.string.home_freeze_none)
                reviewBanner.visibility = View.GONE
            }
            FreezeStore.State.FROZEN -> {
                val minutes = ceil(freezeStore.remainingMs() / 60_000.0).toLong()
                freezeStatusText.text = getString(R.string.home_freeze_frozen, minutes)
                reviewBanner.visibility = View.GONE
            }
            FreezeStore.State.VERIFYING -> {
                freezeStatusText.setText(R.string.home_freeze_verifying)
                reviewBanner.visibility = View.GONE
            }
            FreezeStore.State.REVIEW -> {
                freezeStatusText.setText(R.string.home_freeze_review)
                reviewBanner.visibility = View.VISIBLE
            }
        }

        taperText.text = getString(R.string.home_taper, taper.currentTier())

        val blockedApps = BlockedAppsStore(this).get().size
        val vpn = if (Permissions.hasVpnConsent(this)) getString(R.string.on_word) else getString(R.string.off_word)
        val a11y = if (Permissions.isAccessibilityEnabled(this)) getString(R.string.on_word) else getString(R.string.off_word)
        blockedText.text = getString(
            R.string.home_blocked_summary,
            blockedApps,
            AdultBlocklist.count,
            vpn,
            a11y
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }
    }
}
