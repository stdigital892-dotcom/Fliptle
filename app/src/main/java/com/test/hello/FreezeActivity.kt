package com.test.hello

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.ceil

class FreezeActivity : AppCompatActivity() {

    private lateinit var store: FreezeStore
    private lateinit var minutesInput: EditText
    private lateinit var startButton: Button
    private lateinit var countdownText: TextView
    private lateinit var extendButton: Button
    private lateinit var cancelButton: Button

    private val handler = Handler(Looper.getMainLooper())
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

        minutesInput = findViewById(R.id.minutesInput)
        startButton = findViewById(R.id.startButton)
        countdownText = findViewById(R.id.countdownText)
        extendButton = findViewById(R.id.extendButton)
        cancelButton = findViewById(R.id.cancelButton)

        startButton.setOnClickListener {
            val ms = enteredMinutesMs() ?: return@setOnClickListener
            store.start(ms)
            minutesInput.text.clear()
            render()
        }

        extendButton.setOnClickListener {
            // Tightening is always allowed.
            val ms = enteredMinutesMs() ?: return@setOnClickListener
            store.extend(ms)
            minutesInput.text.clear()
            render()
        }

        cancelButton.setOnClickListener {
            // Only reachable in REVIEW state (disabled while frozen).
            if (store.state() == FreezeStore.State.REVIEW) {
                store.confirmClear()
                render()
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

    private fun enteredMinutesMs(): Long? {
        val minutes = minutesInput.text.toString().trim().toLongOrNull()
        if (minutes == null || minutes <= 0) {
            Toast.makeText(this, R.string.enter_valid_minutes, Toast.LENGTH_SHORT).show()
            return null
        }
        return minutes * 60_000L
    }

    private fun render() {
        when (store.state()) {
            FreezeStore.State.NONE -> {
                startButton.visibility = View.VISIBLE
                countdownText.visibility = View.GONE
                extendButton.visibility = View.GONE
                cancelButton.visibility = View.GONE
                minutesInput.hint = getString(R.string.minutes_hint)
            }

            FreezeStore.State.FROZEN -> {
                startButton.visibility = View.GONE
                countdownText.visibility = View.VISIBLE
                extendButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE

                val remaining = store.remainingMs()
                val minutes = ceil(remaining / 60_000.0).toLong()
                countdownText.text = getString(R.string.locked_for, minutes, mmss(remaining))

                // Tightening allowed; loosening blocked.
                extendButton.isEnabled = true
                cancelButton.isEnabled = false
                minutesInput.hint = getString(R.string.minutes_to_add_hint)
            }

            FreezeStore.State.REVIEW -> {
                startButton.visibility = View.GONE
                countdownText.visibility = View.VISIBLE
                extendButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE

                countdownText.text = getString(R.string.review_available)

                // Freeze is NOT auto-cleared: user must confirm to change it.
                extendButton.isEnabled = true
                cancelButton.isEnabled = true
                minutesInput.hint = getString(R.string.minutes_to_add_hint)
            }
        }
    }

    private fun mmss(ms: Long): String {
        val totalSeconds = ms / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d", m, s)
    }

    companion object {
        private const val TICK_MS = 1_000L
    }
}
