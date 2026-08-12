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

        cooldownInput.setText(valve.cooldownMin.toString())
        windowInput.setText(valve.windowMin.toString())
        capInput.setText(valve.dailyCap.toString())
        allowFreezeCheck.isChecked = valve.allowDuringFreeze
        debugCheck.isChecked = valve.debug

        findViewById<Button>(R.id.saveValveSettingsButton).setOnClickListener { saveSettings() }
        allowFreezeCheck.setOnCheckedChangeListener { _, v -> valve.allowDuringFreeze = v }
        debugCheck.setOnCheckedChangeListener { _, v -> valve.debug = v; render() }
        requestButton.setOnClickListener { request() }
        render()
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
        cooldownInput.text.toString().toIntOrNull()?.let { valve.cooldownMin = it }
        windowInput.text.toString().toIntOrNull()?.let { valve.windowMin = it }
        capInput.text.toString().toIntOrNull()?.let { valve.dailyCap = it }
        Toast.makeText(this, R.string.valve_saved, Toast.LENGTH_SHORT).show()
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
