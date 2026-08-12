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

/**
 * UI for the distraction-surface valve (Reels/Shorts/Stories). Request access,
 * wait out the cooldown, use the timed window, subject to the daily cap. Porn is
 * not shown here — it has no valve.
 */
class ReelsValveActivity : AppCompatActivity() {

    private lateinit var valve: ValveStore

    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var requestButton: Button
    private lateinit var cooldownInput: EditText
    private lateinit var windowInput: EditText
    private lateinit var capInput: EditText
    private lateinit var allowFreezeCheck: CheckBox
    private lateinit var debugCheck: CheckBox
    private lateinit var boundsText: TextView
    private lateinit var pendingText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private var lastFetchMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reels_valve)
        valve = ValveStore(this)

        statusText = findViewById(R.id.valveStatusText)
        countdownText = findViewById(R.id.valveCountdownText)
        requestButton = findViewById(R.id.requestAccessButton)
        cooldownInput = findViewById(R.id.cooldownInput)
        windowInput = findViewById(R.id.windowInput)
        capInput = findViewById(R.id.capInput)
        allowFreezeCheck = findViewById(R.id.allowFreezeCheck)
        debugCheck = findViewById(R.id.valveDebugCheck)
        boundsText = findViewById(R.id.valveBoundsText)
        pendingText = findViewById(R.id.valvePendingText)

        syncInputsToActive()
        debugCheck.isChecked = valve.debug

        findViewById<Button>(R.id.saveValveSettingsButton).setOnClickListener { saveSettings() }
        debugCheck.setOnCheckedChangeListener { _, v -> valve.debug = v; render() }
        requestButton.setOnClickListener { request() }

        boundsText.text = getString(
            R.string.valve_bounds,
            ValveStore.COOLDOWN_MIN_MINUTES, ValveStore.WINDOW_MAX_MINUTES, ValveStore.CAP_MAX
        )
        render()
    }

    private fun syncInputsToActive() {
        cooldownInput.setText(valve.cooldownMin.toString())
        windowInput.setText(valve.windowMin.toString())
        capInput.setText(valve.dailyCap.toString())
        allowFreezeCheck.isChecked = valve.allowDuringFreeze
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

    private fun saveSettings() {
        val cd = cooldownInput.text.toString().toIntOrNull() ?: valve.cooldownMin
        val win = windowInput.text.toString().toIntOrNull() ?: valve.windowMin
        val cap = capInput.text.toString().toIntOrNull() ?: valve.dailyCap
        val result = valve.requestSettingsChange(cd, win, cap, allowFreezeCheck.isChecked)

        val msg = buildString {
            if (result.appliedInstantly) append(getString(R.string.valve_applied_now)).append(' ')
            if (result.staged) append(getString(R.string.valve_staged)).append(' ')
            if (result.blockedByFreeze) append(getString(R.string.valve_loosen_blocked)).append(' ')
            if (result.clamped) append(getString(R.string.valve_clamped)).append(' ')
            if (isEmpty()) append(getString(R.string.valve_no_change))
        }
        Toast.makeText(this, msg.trim(), Toast.LENGTH_LONG).show()
        // Inputs reflect the EFFECTIVE (active) values; a staged loosening is shown
        // separately until it takes effect.
        syncInputsToActive()
        render()
    }

    private fun request() {
        when (valve.requestAccess()) {
            ValveStore.RequestResult.STARTED ->
                Toast.makeText(this, R.string.valve_requested, Toast.LENGTH_SHORT).show()
            ValveStore.RequestResult.CAP_REACHED ->
                Toast.makeText(this, R.string.valve_cap_toast, Toast.LENGTH_LONG).show()
            ValveStore.RequestResult.DENIED_FREEZE ->
                Toast.makeText(this, R.string.valve_denied_freeze, Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun render() {
        // Show any staged loosening change and its remaining delay.
        if (valve.pendingActive()) {
            pendingText.visibility = View.VISIBLE
            val rem = valve.pendingRemainingMs()
            pendingText.text = if (rem < 0) {
                getString(R.string.valve_pending_verifying)
            } else {
                getString(R.string.valve_pending, hms(rem))
            }
        } else {
            pendingText.visibility = View.GONE
        }
        if (valve.needsTimeVerification()) maybeFetchTrustedTime()

        val state = valve.state()
        countdownText.visibility = View.GONE
        requestButton.visibility = View.GONE
        val used = getString(R.string.valve_used, valve.usedToday(), valve.dailyCap)

        when (state) {
            ValveStore.State.BLOCKED -> {
                statusText.text = "${getString(R.string.valve_blocked)}\n$used"
                requestButton.visibility = View.VISIBLE
            }
            ValveStore.State.COOLDOWN -> {
                statusText.setText(R.string.valve_cooldown)
                countdownText.visibility = View.VISIBLE
                countdownText.text = getString(R.string.valve_opens_in, hms(valve.remainingMs()))
            }
            ValveStore.State.OPEN -> {
                statusText.setText(R.string.valve_open)
                countdownText.visibility = View.VISIBLE
                countdownText.text = getString(R.string.valve_closes_in, hms(valve.remainingMs()))
            }
            ValveStore.State.CAP_REACHED -> {
                statusText.text = "${getString(R.string.valve_cap_reached)}\n$used"
            }
            ValveStore.State.VERIFYING -> {
                statusText.setText(R.string.valve_verifying)
                countdownText.visibility = View.VISIBLE
                countdownText.setText(R.string.uninstall_verifying)
                maybeFetchTrustedTime()
            }
        }
    }

    private fun maybeFetchTrustedTime() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFetchMs < 5_000L && lastFetchMs != 0L) return
        if (!fetching.compareAndSet(false, true)) return
        lastFetchMs = now
        io.execute {
            val trusted = TrustedTime.fetchEpochMillis()
            handler.post {
                if (trusted != null) {
                    valve.applyTrustedTime(trusted)
                    render()
                }
                fetching.set(false)
            }
        }
    }

    private fun hms(ms: Long): String {
        val s = ms / 1000
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }
}
