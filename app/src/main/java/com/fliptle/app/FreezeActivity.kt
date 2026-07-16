package com.fliptle.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class FreezeActivity : AppCompatActivity() {

    private lateinit var store: FreezeStore
    private lateinit var taper: TaperStore
    private lateinit var minutesInput: EditText
    private lateinit var startButton: Button
    private lateinit var taperStartButton: Button
    private lateinit var taperStatusText: TextView
    private lateinit var debugModeCheck: CheckBox
    private lateinit var countdownText: TextView
    private lateinit var extendButton: Button
    private lateinit var cancelButton: Button

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
        setContentView(R.layout.activity_freeze)
        store = FreezeStore(this)
        taper = TaperStore(this)

        minutesInput = findViewById(R.id.minutesInput)
        startButton = findViewById(R.id.startButton)
        taperStartButton = findViewById(R.id.taperStartButton)
        taperStatusText = findViewById(R.id.taperStatusText)
        debugModeCheck = findViewById(R.id.debugModeCheck)
        countdownText = findViewById(R.id.countdownText)
        extendButton = findViewById(R.id.extendButton)
        cancelButton = findViewById(R.id.cancelButton)

        debugModeCheck.isChecked = taper.debugMode
        debugModeCheck.setOnCheckedChangeListener { _, checked ->
            taper.debugMode = checked
            render()
        }

        startButton.setOnClickListener {
            val ms = enteredMinutesMs() ?: return@setOnClickListener
            taper.onManualFreezeStarted() // a manual freeze does not count as a cycle
            store.start(ms)
            minutesInput.text.clear()
            render()
        }

        taperStartButton.setOnClickListener {
            taper.onTaperFreezeStarted()
            store.start(taper.currentDurationMs())
            render()
        }

        extendButton.setOnClickListener {
            // Tightening is always allowed, even while VERIFYING.
            val ms = enteredMinutesMs() ?: return@setOnClickListener
            store.extend(ms)
            minutesInput.text.clear()
            render()
        }

        cancelButton.setOnClickListener {
            // Only reachable in REVIEW state (disabled while frozen/verifying).
            if (store.state() == FreezeStore.State.REVIEW) {
                // Actively confirming a COMPLETED taper freeze advances the tier.
                val newTier = taper.onCycleConfirmed()
                store.confirmClear()
                render()
                if (newTier != null) {
                    Toast.makeText(
                        this,
                        getString(R.string.cycle_complete, newTier),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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

    private fun enteredMinutesMs(): Long? {
        val minutes = minutesInput.text.toString().trim().toLongOrNull()
        if (minutes == null || minutes <= 0) {
            Toast.makeText(this, R.string.enter_valid_minutes, Toast.LENGTH_SHORT).show()
            return null
        }
        return minutes * 60_000L
    }

    private fun render() {
        renderTaperStatus()
        when (store.state()) {
            FreezeStore.State.NONE -> {
                startButton.visibility = View.VISIBLE
                taperStartButton.visibility = View.VISIBLE
                debugModeCheck.visibility = View.VISIBLE
                countdownText.visibility = View.GONE
                extendButton.visibility = View.GONE
                cancelButton.visibility = View.GONE
                minutesInput.hint = getString(R.string.minutes_hint)
            }

            FreezeStore.State.FROZEN -> {
                showActiveControls()
                val remaining = store.remainingMs()
                val minutes = ceil(remaining / 60_000.0).toLong()
                countdownText.text = getString(R.string.locked_for, minutes, mmss(remaining))
                extendButton.isEnabled = true
                cancelButton.isEnabled = false
                // Correct any start-time clock offset against trusted time (once).
                if (!store.wallTrusted) maybeFetchTrustedTime(reboot = false)
            }

            FreezeStore.State.VERIFYING -> {
                showActiveControls()
                countdownText.text = getString(R.string.verifying_locked)
                extendButton.isEnabled = true   // tightening still allowed
                cancelButton.isEnabled = false  // cannot loosen while locked
                // Reboot detected: only trusted network time can re-anchor.
                maybeFetchTrustedTime(reboot = true)
            }

            FreezeStore.State.REVIEW -> {
                showActiveControls()
                countdownText.text = getString(R.string.review_available)
                extendButton.isEnabled = true
                cancelButton.isEnabled = true // confirm-to-change
            }
        }
    }

    private fun showActiveControls() {
        startButton.visibility = View.GONE
        taperStartButton.visibility = View.GONE
        debugModeCheck.visibility = View.GONE
        countdownText.visibility = View.VISIBLE
        extendButton.visibility = View.VISIBLE
        cancelButton.visibility = View.VISIBLE
        minutesInput.hint = getString(R.string.minutes_to_add_hint)
    }

    private fun renderTaperStatus() {
        val cur = taper.currentTier()
        val next = if (taper.isAtMax()) {
            getString(R.string.taper_next_max)
        } else {
            getString(R.string.taper_next, taper.nextTier())
        }
        val sb = StringBuilder()
        sb.append(getString(R.string.taper_status, cur, next))
        sb.append("\n").append(getString(R.string.taper_cycles, taper.completedCycles()))
        if (taper.debugMode) {
            sb.append("\n").append(getString(R.string.taper_debug, mmss(taper.currentDurationMs())))
        }
        taperStatusText.text = sb.toString()
    }

    /**
     * Fetch trusted network time off the main thread, throttled. On success,
     * re-anchor (after reboot) or correct the wall end (same boot). On failure the
     * state is untouched, so a reboot stays LOCKED until verification succeeds.
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

    private fun mmss(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    companion object {
        private const val TICK_MS = 1_000L
        private const val FETCH_THROTTLE_MS = 5_000L
    }
}
