package com.fliptle.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
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
 * "Request to uninstall" gate. Each day requires BOTH:
 *   1) 10 basic arithmetic questions (validated app-side), then
 *   2) typing the 1..50 sequence with no separators (validated per keystroke;
 *      paste/bulk input rejected, paste menu disabled).
 * Both must be finished for the day to count. One day unlocks per 24h, with the
 * same reboot- and clock-tamper-proof timing as the freeze (see
 * [UninstallGateStore]).
 */
class UninstallRequestActivity : AppCompatActivity() {

    private enum class Phase { NONE, MATH, TYPING }

    private lateinit var store: UninstallGateStore

    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var startDayButton: Button
    private lateinit var cancelButton: Button
    private lateinit var debugCheck: CheckBox
    private lateinit var heartbeatDebugCheck: CheckBox

    private lateinit var mathSection: View
    private lateinit var questionText: TextView
    private lateinit var mathProgressText: TextView
    private lateinit var answerInput: EditText
    private lateinit var submitAnswerButton: Button
    private lateinit var mathErrorText: TextView

    private lateinit var typingSection: View
    private lateinit var typingProgressText: TextView
    private lateinit var typingField: NoPasteEditText
    private lateinit var typingErrorText: TextView
    private lateinit var finishDayButton: Button
    private lateinit var finishDayErrorText: TextView

    private var phase = Phase.NONE
    private var questions: List<MathQuestion> = emptyList()
    private var qIndex = 0

    // Day 5 only: typing is done but completion now waits on an awaited
    // server write (see UninstallLog.logApprovedAwait / FinalDayGate).
    private var finalDayPendingTap = false
    private var finalDayInFlight = false
    private var finalDayError: String? = null

    private val expectedSeq: String = buildString { for (i in 1..50) append(i) }
    private val typedSeq = StringBuilder()
    private var typingSelfEdit = false

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private var lastFetchMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (phase == Phase.NONE) render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uninstall_request)
        EdgeToEdge.applyInsets(this)
        store = UninstallGateStore(this)
        if (!store.active) store.start()

        statusText = findViewById(R.id.uninstallStatusText)
        countdownText = findViewById(R.id.uninstallCountdownText)
        startDayButton = findViewById(R.id.startDayButton)
        cancelButton = findViewById(R.id.cancelRequestButton)
        debugCheck = findViewById(R.id.debugDaysCheck)
        heartbeatDebugCheck = findViewById(R.id.heartbeatDebugCheck)

        mathSection = findViewById(R.id.mathSection)
        questionText = findViewById(R.id.questionText)
        mathProgressText = findViewById(R.id.mathProgressText)
        answerInput = findViewById(R.id.answerInput)
        submitAnswerButton = findViewById(R.id.submitAnswerButton)
        mathErrorText = findViewById(R.id.mathErrorText)

        typingSection = findViewById(R.id.typingSection)
        typingProgressText = findViewById(R.id.typingProgressText)
        typingField = findViewById(R.id.typingField)
        typingErrorText = findViewById(R.id.typingErrorText)
        typingField.addTextChangedListener(typingWatcher)
        finishDayButton = findViewById(R.id.finishDayButton)
        finishDayErrorText = findViewById(R.id.finishDayErrorText)
        finishDayButton.setOnClickListener { attemptFinalDayFinish() }

        // Short-day / fast-heartbeat testing switches are developer tools: hidden
        // unless DevMode is unlocked, and absent entirely from release builds.
        if (DevMode.enabled(this)) {
            debugCheck.visibility = View.VISIBLE
            heartbeatDebugCheck.visibility = View.VISIBLE
            debugCheck.isChecked = store.debugMode
            debugCheck.setOnCheckedChangeListener { _, v -> store.debugMode = v; render() }
            heartbeatDebugCheck.isChecked = Heartbeat.isDebug(this)
            heartbeatDebugCheck.setOnCheckedChangeListener { _, v -> Heartbeat.setDebug(this, v) }
        } else {
            debugCheck.visibility = View.GONE
            heartbeatDebugCheck.visibility = View.GONE
        }

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

    // ---- Day flow: MATH then TYPING ----

    private fun startDay() {
        questions = MathQuestions.generateSet()
        qIndex = 0
        phase = Phase.MATH
        mathErrorText.text = ""
        answerInput.text.clear()
        render()
    }

    private fun submitAnswer() {
        if (phase != Phase.MATH) return
        val entered = answerInput.text.toString().trim().toIntOrNull()
        if (entered == null) {
            mathErrorText.setText(R.string.math_enter_number)
            return
        }
        if (entered == questions[qIndex].answer) {
            qIndex++
            answerInput.text.clear()
            mathErrorText.text = ""
            if (qIndex >= questions.size) beginTypingPhase() else render()
        } else {
            mathErrorText.setText(R.string.math_wrong)
        }
    }

    private fun beginTypingPhase() {
        phase = Phase.TYPING
        typedSeq.setLength(0)
        typingSelfEdit = true
        typingField.setText("")
        typingSelfEdit = false
        typingErrorText.text = ""
        render()
    }

    private val typingWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (typingSelfEdit || phase != Phase.TYPING) return
            val current = s?.toString() ?: ""
            when {
                current.length == typedSeq.length + 1 &&
                    typedSeq.length < expectedSeq.length &&
                    current.startsWith(typedSeq) &&
                    current.last() == expectedSeq[typedSeq.length] -> {
                    typedSeq.append(current.last())
                    typingErrorText.text = ""
                }
                current.length == typedSeq.length - 1 && typedSeq.startsWith(current) -> {
                    typedSeq.setLength(current.length)
                    typingErrorText.text = ""
                }
                current == typedSeq.toString() -> { /* no-op */ }
                else -> {
                    val bulk = current.length > typedSeq.length + 1
                    typingSelfEdit = true
                    typingField.setText(typedSeq.toString())
                    typingField.setSelection(typedSeq.length)
                    typingSelfEdit = false
                    typingErrorText.setText(if (bulk) R.string.typing_error_paste else R.string.typing_error_wrong)
                }
            }
            typingProgressText.text = getString(R.string.typing_progress, typedSeq.length, expectedSeq.length)
            if (typedSeq.toString() == expectedSeq) finishDay()
        }
    }

    private fun finishDay() {
        phase = Phase.NONE
        val isFinalDay = store.daysDone + 1 >= UninstallGateStore.DAYS_REQUIRED
        if (!isFinalDay) {
            val done = store.completeDay()
            // Each day's completion is logged to Firestore keyed to email/UID.
            UninstallLog.logDayComplete(this, done, UninstallGateStore.DAYS_REQUIRED)
            CloudState.backup(this)
            Toast.makeText(this, getString(R.string.math_day_done, done), Toast.LENGTH_LONG).show()
            render()
            return
        }
        // Day 5: typing is done, but completion now waits on a tap of Finish
        // (and the server write it awaits) -- see attemptFinalDayFinish().
        finalDayPendingTap = true
        finalDayError = null
        render()
    }

    private fun attemptFinalDayFinish() {
        if (!Connectivity.isOnline(this)) { render(); return }
        finalDayInFlight = true
        finalDayError = null
        render()
        UninstallLog.logApprovedAwait(this) { succeeded ->
            runOnUiThread {
                finalDayInFlight = false
                if (FinalDayGate.mayApprove(succeeded)) {
                    val done = store.recordFinalDayLocally()
                    store.markApproved()
                    UninstallLog.logDayComplete(this, done, UninstallGateStore.DAYS_REQUIRED)
                    // E1: back up the new day count and approved flag so a
                    // device wipe doesn't silently reset this progress.
                    CloudState.backup(this)
                    finalDayPendingTap = false
                    Toast.makeText(this, getString(R.string.math_day_done, done), Toast.LENGTH_LONG).show()
                } else {
                    finalDayError = getString(R.string.uninstall_server_unreachable)
                }
                render()
            }
        }
    }

    // ---- Rendering ----

    private fun render() {
        if (finalDayPendingTap) {
            mathSection.visibility = View.GONE
            typingSection.visibility = View.VISIBLE
            finishDayButton.visibility = View.VISIBLE
            val online = Connectivity.isOnline(this)
            finishDayButton.isEnabled = online && !finalDayInFlight
            finishDayButton.text = if (finalDayInFlight) {
                getString(R.string.uninstall_finishing)
            } else {
                getString(R.string.uninstall_finish_final_day)
            }
            finishDayErrorText.text = when {
                finalDayError != null -> finalDayError
                !online -> getString(R.string.uninstall_needs_internet)
                else -> ""
            }
            return
        }

        mathSection.visibility = if (phase == Phase.MATH) View.VISIBLE else View.GONE
        typingSection.visibility = if (phase == Phase.TYPING) View.VISIBLE else View.GONE
        finishDayButton.visibility = View.GONE

        if (phase == Phase.MATH) {
            questionText.text = questions[qIndex].text
            mathProgressText.text = getString(R.string.math_progress, qIndex + 1, questions.size)
            return
        }
        if (phase == Phase.TYPING) {
            typingProgressText.text = getString(R.string.typing_progress, typedSeq.length, expectedSeq.length)
            return
        }

        val state = store.state()
        if (store.justReset) {
            store.clearJustReset()
            Toast.makeText(this, R.string.uninstall_reset_toast, Toast.LENGTH_LONG).show()
        }
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
