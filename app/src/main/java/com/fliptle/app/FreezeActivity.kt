package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The commitment screen: one button opens the setup wizard (which is the only
 * way to choose what to block and start the 3-day lock), and while a cycle runs
 * this shows its status. There is no cancel and no timer to shorten — the cycle
 * only ends by being replaced with a new one when the user changes what they
 * block at the day-3 review. While locked there is nothing to edit.
 */
class FreezeActivity : AppCompatActivity() {

    private lateinit var store: FreezeStore

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var setupButton: Button
    private lateinit var reviewNotice: TextView
    private lateinit var devStatusText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val fetchInFlight = AtomicBoolean(false)
    private var lastFetchElapsed = 0L

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Permissions.gate(this)) return
        setContentView(R.layout.activity_freeze)
        store = FreezeStore(this)

        statusText = findViewById(R.id.cycleStatusText)
        detailText = findViewById(R.id.cycleDetailText)
        setupButton = findViewById(R.id.setupFreezeButton)
        reviewNotice = findViewById(R.id.reviewNoticeText)
        devStatusText = findViewById(R.id.devStatusText)

        // Hidden developer unlock (debug builds only; inert in release).
        DevMode.attachUnlockGesture(findViewById(R.id.freezeTitle))

        setupButton.setOnClickListener {
            // Opens the wizard. Nothing is locked until its final button.
            startActivity(Intent(this, FreezeWizardActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Permissions.gate(this)) return
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun render() {
        val state = store.state()
        // The wizard is offered only when settings can be edited: before the first
        // cycle, and at the day-3 review. While LOCKED / VERIFYING this is status only.
        setupButton.visibility =
            if (state == FreezeStore.State.LOCKED || state == FreezeStore.State.VERIFYING) View.GONE
            else View.VISIBLE
        setupButton.setText(
            if (state == FreezeStore.State.REVIEW) R.string.freeze_change_button
            else R.string.freeze_setup_button
        )
        reviewNotice.visibility = if (state == FreezeStore.State.REVIEW) View.VISIBLE else View.GONE

        when (state) {
            FreezeStore.State.NONE -> {
                statusText.setText(R.string.freeze_none_status)
                detailText.text = getString(R.string.freeze_none_detail, store.cycleDays())
            }

            FreezeStore.State.LOCKED -> {
                statusText.text = getString(R.string.freeze_day, store.dayNumber(), store.cycleDays())
                detailText.text = getString(R.string.freeze_locked_detail, dhm(store.remainingMs()))
                // Correct any start-time clock offset against trusted time (once).
                if (!store.wallTrusted) maybeFetchTrustedTime(reboot = false)
            }

            FreezeStore.State.REVIEW -> {
                statusText.text = getString(R.string.freeze_day_review, store.dayNumber())
                detailText.setText(R.string.freeze_review_detail)
            }

            FreezeStore.State.VERIFYING -> {
                statusText.setText(R.string.freeze_verifying_status)
                detailText.setText(R.string.verifying_locked)
                // Reboot detected: only trusted network time can re-anchor.
                maybeFetchTrustedTime(reboot = true)
            }
        }

        // Developer-mode banner: only ever visible after the hidden unlock in a
        // debug build, so normal users never see it.
        if (DevMode.enabled(this)) {
            devStatusText.visibility = View.VISIBLE
            devStatusText.text = getString(R.string.dev_mode_on, dhm(store.cycleMs()))
        } else {
            devStatusText.visibility = View.GONE
        }
    }

    /**
     * Fetch trusted network time off the main thread, throttled. On success,
     * re-anchor (after reboot) or correct the wall deadline (same boot). On
     * failure nothing changes, so a reboot stays LOCKED until it succeeds.
     */
    private fun maybeFetchTrustedTime(reboot: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFetchElapsed < FETCH_THROTTLE_MS && lastFetchElapsed != 0L) return
        if (!fetchInFlight.compareAndSet(false, true)) return
        lastFetchElapsed = now

        io.execute {
            val trusted = TrustedTime.fetchEpochMillis()
            handler.post {
                if (trusted != null) {
                    if (reboot) store.rebootReanchor(trusted)
                    else store.applyTrustedTimeSameBoot(trusted)
                    render()
                }
                fetchInFlight.set(false)
            }
        }
    }

    /** Coarse "2d 04h" / "04h 12m" / "12m 30s" formatting for long waits. */
    private fun dhm(ms: Long): String {
        val s = ms / 1000
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> String.format("%dd %02dh", d, h)
            h > 0 -> String.format("%02dh %02dm", h, m)
            else -> String.format("%02dm %02ds", m, s % 60)
        }
    }

    companion object {
        private const val TICK_MS = 1_000L
        private const val FETCH_THROTTLE_MS = 5_000L
    }
}
