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
import com.fliptle.app.auth.SignInActivity

/**
 * Home has two faces:
 *
 *  • First launch — nothing committed yet: a single "Let's start this journey"
 *    action, and nothing else to read or decide.
 *  • Once set up — the status the user actually returns for: the "Day X since you
 *    enabled Porn Blocking" streak and the current freeze state, with the review
 *    surfaced as a banner when day 3 arrives.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var freezeStore: FreezeStore
    private lateinit var pornBlock: PornBlockStore

    private lateinit var startSection: View
    private lateinit var statusSection: View
    private lateinit var pornDayText: TextView
    private lateinit var freezeStatusText: TextView
    private lateinit var freezeDetailText: TextView
    private lateinit var reviewBanner: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compulsory permissions: if any is missing, bounce to the guard.
        if (!Permissions.gate(this)) return
        // Sign-in is mandatory; an unauthenticated user is sent to sign in.
        if (!AuthGate.gate(this)) return
        setContentView(R.layout.activity_home)
        freezeStore = FreezeStore(this)
        pornBlock = PornBlockStore(this)

        startSection = findViewById(R.id.startSection)
        statusSection = findViewById(R.id.statusSection)
        pornDayText = findViewById(R.id.pornDayText)
        freezeStatusText = findViewById(R.id.freezeStatusText)
        freezeDetailText = findViewById(R.id.freezeDetailText)
        reviewBanner = findViewById(R.id.reviewBanner)

        // Hidden developer unlock (debug builds only; inert in release).
        DevMode.attachUnlockGesture(findViewById(R.id.homeTitle))

        findViewById<Button>(R.id.startJourneyButton).setOnClickListener {
            startActivity(Intent(this, PornBlockActivity::class.java))
        }
        findViewById<Button>(R.id.freezeButton).setOnClickListener {
            startActivity(Intent(this, FreezeActivity::class.java))
        }
        findViewById<Button>(R.id.setupButton).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<Button>(R.id.accountButton).setOnClickListener {
            startActivity(Intent(this, SignInActivity::class.java))
        }
        findViewById<Button>(R.id.reviewButton).setOnClickListener {
            startActivity(Intent(this, FreezeActivity::class.java))
        }

        // Foreground-safe startup: enforcement service + browser auto-block.
        requestNotificationPermissionIfNeeded()
        BrowserDetector.autoBlockInstalledBrowsers(this)
        BlockingService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // Re-check on every return; a revoked permission blocks the main flow.
        if (!Permissions.gate(this)) return
        if (!AuthGate.gate(this)) return
        com.fliptle.app.auth.Heartbeat.beat(this)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    /** "Set up" = the journey has begun: porn blocking on, or a cycle running. */
    private fun isSetUp(): Boolean = pornBlock.enabled || freezeStore.active

    private fun render() {
        if (!isSetUp()) {
            startSection.visibility = View.VISIBLE
            statusSection.visibility = View.GONE
            return
        }
        startSection.visibility = View.GONE
        statusSection.visibility = View.VISIBLE

        pornDayText.text = if (pornBlock.enabled) {
            getString(R.string.porn_day_counter, pornBlock.dayCount())
        } else {
            getString(R.string.home_porn_off)
        }

        when (freezeStore.state()) {
            FreezeStore.State.NONE -> {
                freezeStatusText.setText(R.string.home_freeze_none)
                freezeDetailText.text = getString(R.string.freeze_none_detail, freezeStore.cycleDays())
                reviewBanner.visibility = View.GONE
            }
            FreezeStore.State.LOCKED -> {
                freezeStatusText.text = getString(
                    R.string.home_freeze_locked, freezeStore.dayNumber(), freezeStore.cycleDays()
                )
                freezeDetailText.text = getString(R.string.home_freeze_locked_detail, blockedSummary())
                reviewBanner.visibility = View.GONE
            }
            FreezeStore.State.REVIEW -> {
                freezeStatusText.text = getString(R.string.home_freeze_review, freezeStore.dayNumber())
                freezeDetailText.text = getString(R.string.home_freeze_locked_detail, blockedSummary())
                reviewBanner.visibility = View.VISIBLE
            }
            FreezeStore.State.VERIFYING -> {
                freezeStatusText.setText(R.string.home_freeze_verifying)
                freezeDetailText.setText(R.string.verifying_locked)
                reviewBanner.visibility = View.GONE
            }
        }
    }

    private fun blockedSummary(): String {
        val apps = BlockedAppsStore(this).get().size
        val allowance = ReelsAllowance(this)
        return getString(R.string.home_blocked_summary, apps, allowance.sessionMin, allowance.perDay)
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
