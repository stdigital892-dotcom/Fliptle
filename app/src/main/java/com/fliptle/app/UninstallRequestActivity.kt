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
import com.fliptle.app.auth.Heartbeat
import com.fliptle.app.auth.UninstallLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Request to uninstall" math gate: 10 questions/day for 5 days, one set per day,
 * with reboot- and clock-tamper-proof per-day unlocking (see [UninstallGateStore]).
 */
class UninstallRequestActivity : AppCompatActivity() {

    private lateinit var store: UninstallGateStore

    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var startDayButton: Button
    private lateinit var cancelButton: Button
    private lateinit var resetOnMissCheck: CheckBox
    private lateinit var debugCheck: CheckBox
    private lateinit var heartbeatDebugCheck: CheckBox

    private lateinit var mathSection: View
    private lateinit var questionText: TextView
    private lateinit var progressText: TextView
    private lateinit var answerInput: EditText
    private lateinit var submitAnswerButton: Button
    private lateinit var errorText: TextView

    private var questions: List<MathQuestion> = emptyList()
    private var qIndex = 0
    private var inSession = false

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private var lastFetchMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!inSession) render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uninstall_request)
        store = UninstallGateStore(this)
        if (!store.active) store.start() // opening the request starts it

        statusText = findViewById(R.id.uninstallStatusText)
        countdownText = findViewById(R.id.uninstallCountdownText)
        startDayButton = findViewById(R.id.startDayButton)
        cancelButton = findViewById(R.id.cancelRequestButton)
        resetOnMissCheck = findViewById(R.id.resetOnMissCheck)
        debugCheck = findViewById(R.id.debugDaysCheck)
        heartbeatDebugCheck = findViewById(R.id.heartbeatDebugCheck)

        mathSection = findViewById(R.id.mathSection)
        questionText = findViewById(R.id.questionText)
        progressText = findViewById(R.id.mathProgressText)
        answerInput = findViewById(R.id.answerInput)
        submitAnswerButton = findViewById(R.id.submitAnswerButton)
        errorText = findViewById(R.id.mathErrorText)

        resetOnMissCheck.isChecked = store.resetOnMiss
        resetOnMissCheck.setOnCheckedChangeListener { _, v -> store.resetOnMiss = v }
        debugCheck.isChecked = store.debugMode
        debugCheck.setOnCheckedChangeListener { _, v -> store.debugMode = v; render() }
        heartbeatDebugCheck.isChecked = Heartbeat.isDebug(this)
        heartbeatDebugCheck.setOnCheckedChangeListener { _, v -> Heartbeat.setDebug(this, v) }

        startDayButton.setOnClickListener { startDay() }
        submitAnswerButton.setOnClickListener { submitAnswer() }
        cancelButton.setOnClickListener {
            store.cancel()
            Toast.makeText(this, R.string.uninstall_cancelled, Toast.LENGTH_SHORT).show()
            finish()
        }
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

    private fun startDay() {
        questions = MathQuestions.generateSet()
        qIndex = 0
        inSession = true
        errorText.text = ""
        answerInput.text.clear()
        render()
    }

    private fun submitAnswer() {
        if (!inSession) return
        val entered = answerInput.text.toString().trim().toIntOrNull()
        if (entered == null) {
            errorText.setText(R.string.math_enter_number)
            return
        }
        if (entered == questions[qIndex].answer) {
            qIndex++
            answerInput.text.clear()
            errorText.text = ""
            if (qIndex >= questions.size) finishDay() else render()
        } else {
            errorText.setText(R.string.math_wrong)
        }
    }

    private fun finishDay() {
        inSession = false
        val done = store.completeDay()
        UninstallLog.logDayComplete(this, done, UninstallGateStore.DAYS_REQUIRED)
        if (done >= UninstallGateStore.DAYS_REQUIRED) {
            UninstallLog.logApproved(this)
        }
        Toast.makeText(this, getString(R.string.math_day_done, done), Toast.LENGTH_LONG).show()
        render()
    }

    private fun render() {
        if (inSession) {
            showMath(true)
            questionText.text = questions[qIndex].text
            progressText.text = getString(R.string.math_progress, qIndex + 1, questions.size)
            return
        }
        showMath(false)

        val state = store.state()
        statusText.text = getString(R.string.uninstall_status, store.daysDone, UninstallGateStore.DAYS_REQUIRED)
        countdownText.visibility = View.GONE
        startDayButton.visibility = View.GONE

        when (state) {
            UninstallGateStore.State.APPROVED -> {
                statusText.setText(R.string.uninstall_approved)
                cancelButton.visibility = View.GONE
            }
            UninstallGateStore.State.AVAILABLE -> {
                startDayButton.visibility = View.VISIBLE
                startDayButton.text = getString(R.string.start_day, store.daysDone + 1)
            }
            UninstallGateStore.State.LOCKED -> {
                countdownText.visibility = View.VISIBLE
                countdownText.text = getString(R.string.uninstall_locked, hms(store.remainingMs()))
            }
            UninstallGateStore.State.VERIFYING -> {
                countdownText.visibility = View.VISIBLE
                countdownText.setText(R.string.uninstall_verifying)
                maybeFetchTrustedTime()
            }
            UninstallGateStore.State.INACTIVE -> { /* just started; treated as available */ }
        }
    }

    private fun showMath(show: Boolean) {
        mathSection.visibility = if (show) View.VISIBLE else View.GONE
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
                    store.applyTrustedTime(trusted)
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
