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
import com.fliptle.app.accessibility.SurfaceBlocklist
import com.fliptle.app.accessibility.SurfaceDebug
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app surfaces (Instagram Reels/Stories, YouTube Shorts) plus the Reels
 * allowance that governs them during a commitment.
 *
 * Both the surface toggles and the allowance are COMMITTED settings: they cannot
 * be changed while the 3-day lock is running, and changing one at review starts a
 * new cycle (see [Commitment]).
 */
class SurfaceBlockActivity : AppCompatActivity() {

    private lateinit var store: SurfaceBlocklist
    private lateinit var allowance: ReelsAllowance

    private lateinit var lockText: TextView
    private lateinit var reels: CheckBox
    private lateinit var stories: CheckBox
    private lateinit var shorts: CheckBox
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var startSessionButton: Button
    private lateinit var sessionInput: EditText
    private lateinit var perDayInput: EditText
    private lateinit var cooldownInput: EditText
    private lateinit var saveButton: Button
    private lateinit var devSection: View
    private lateinit var debugLog: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private var lastFetchMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            renderAllowance()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_surface_block)
        store = SurfaceBlocklist(this)
        allowance = ReelsAllowance(this)

        lockText = findViewById(R.id.surfaceLockText)
        reels = findViewById(R.id.reelsCheck)
        stories = findViewById(R.id.storiesCheck)
        shorts = findViewById(R.id.shortsCheck)
        statusText = findViewById(R.id.allowanceStatusText)
        countdownText = findViewById(R.id.allowanceCountdownText)
        startSessionButton = findViewById(R.id.startSessionButton)
        sessionInput = findViewById(R.id.sessionInput)
        perDayInput = findViewById(R.id.perDayInput)
        cooldownInput = findViewById(R.id.cooldownInput)
        saveButton = findViewById(R.id.saveAllowanceButton)
        devSection = findViewById(R.id.devSection)
        debugLog = findViewById(R.id.debugLogText)

        bindSurfaceToggle(reels, { store.reels }, { store.reels = it })
        bindSurfaceToggle(stories, { store.stories }, { store.stories = it })
        bindSurfaceToggle(shorts, { store.shorts }, { store.shorts = it })

        findViewById<TextView>(R.id.allowanceBoundsText).text = getString(
            R.string.allowance_bounds,
            ReelsAllowance.SESSION_MAX, ReelsAllowance.PER_DAY_MAX, ReelsAllowance.COOLDOWN_MIN
        )
        startSessionButton.setOnClickListener { startSession() }
        saveButton.setOnClickListener { saveAllowance() }

        setupDevSection()
        syncInputs()
    }

    /**
     * Surface toggles are committed settings: refuse while locked (reverting the
     * checkbox), and restart the cycle when actually changed at review.
     */
    private fun bindSurfaceToggle(box: CheckBox, get: () -> Boolean, set: (Boolean) -> Unit) {
        box.isChecked = get()
        box.setOnCheckedChangeListener { _, checked ->
            if (checked == get()) return@setOnCheckedChangeListener
            if (!Commitment.guard(this)) {
                box.isChecked = get() // revert; the lock holds
                return@setOnCheckedChangeListener
            }
            set(checked)
            Commitment.onChanged(this)
        }
    }

    private fun setupDevSection() {
        if (!DevMode.enabled(this)) {
            devSection.visibility = View.GONE
            return
        }
        devSection.visibility = View.VISIBLE
        val debug = findViewById<CheckBox>(R.id.debugCheck)
        debug.isChecked = store.debug
        debug.setOnCheckedChangeListener { _, v -> store.debug = v }
        findViewById<Button>(R.id.refreshLogButton).setOnClickListener { renderLog() }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            SurfaceDebug(this).clear()
            renderLog()
        }
        renderLog()
    }

    private fun syncInputs() {
        sessionInput.setText(allowance.sessionMin.toString())
        perDayInput.setText(allowance.perDay.toString())
        cooldownInput.setText(allowance.cooldownMin.toString())

        val locked = !Commitment.canChange(this)
        sessionInput.isEnabled = !locked
        perDayInput.isEnabled = !locked
        cooldownInput.isEnabled = !locked
        saveButton.isEnabled = !locked

        val freeze = FreezeStore(this)
        lockText.text = when {
            !freeze.active -> getString(R.string.commit_not_started)
            locked -> getString(R.string.commit_locked, freeze.dayNumber(), freeze.cycleDays())
            else -> getString(R.string.commit_review_open, freeze.dayNumber())
        }
    }

    private fun saveAllowance() {
        if (!Commitment.guard(this)) return
        val session = sessionInput.text.toString().toIntOrNull() ?: allowance.sessionMin
        val perDay = perDayInput.text.toString().toIntOrNull() ?: allowance.perDay
        val cooldown = cooldownInput.text.toString().toIntOrNull() ?: allowance.cooldownMin

        val changed = session != allowance.sessionMin ||
            perDay != allowance.perDay ||
            cooldown != allowance.cooldownMin

        if (!allowance.applySettings(session, perDay, cooldown)) {
            Commitment.guard(this)
            return
        }
        syncInputs()
        if (changed) {
            Toast.makeText(this, R.string.allowance_saved, Toast.LENGTH_SHORT).show()
            Commitment.onChanged(this)
        } else {
            Toast.makeText(this, R.string.allowance_no_change, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSession() {
        when (allowance.requestSession()) {
            ReelsAllowance.RequestResult.STARTED ->
                Toast.makeText(this, R.string.allowance_started, Toast.LENGTH_SHORT).show()
            ReelsAllowance.RequestResult.CAP_REACHED ->
                Toast.makeText(this, R.string.allowance_cap_toast, Toast.LENGTH_LONG).show()
            ReelsAllowance.RequestResult.COOLDOWN_ACTIVE ->
                Toast.makeText(this, R.string.allowance_cooldown_toast, Toast.LENGTH_LONG).show()
        }
        renderAllowance()
    }

    override fun onResume() {
        super.onResume()
        syncInputs()
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

    private fun renderAllowance() {
        val used = getString(R.string.allowance_used, allowance.usedToday(), allowance.perDay)
        countdownText.visibility = View.GONE
        startSessionButton.visibility = View.GONE

        when (allowance.state()) {
            ReelsAllowance.State.LOCKED -> {
                statusText.text = "${getString(R.string.allowance_locked)}\n$used"
                startSessionButton.visibility = View.VISIBLE
            }
            ReelsAllowance.State.OPEN -> {
                statusText.setText(R.string.allowance_open)
                countdownText.visibility = View.VISIBLE
                countdownText.text = getString(R.string.allowance_closes_in, mmss(allowance.remainingMs()))
            }
            ReelsAllowance.State.COOLDOWN -> {
                statusText.text = "${getString(R.string.allowance_cooldown)}\n$used"
                countdownText.visibility = View.VISIBLE
                countdownText.text = getString(R.string.allowance_ready_in, mmss(allowance.remainingMs()))
            }
            ReelsAllowance.State.CAP_REACHED -> {
                statusText.text = "${getString(R.string.allowance_cap_reached)}\n$used"
            }
            ReelsAllowance.State.VERIFYING -> {
                statusText.setText(R.string.allowance_verifying)
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
                    allowance.applyTrustedTime(trusted)
                    renderAllowance()
                }
                fetching.set(false)
            }
        }
    }

    private fun renderLog() {
        val log = SurfaceDebug(this).get()
        debugLog.text = log.ifEmpty { getString(R.string.surfaces_debug_empty) }
    }

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }
}
