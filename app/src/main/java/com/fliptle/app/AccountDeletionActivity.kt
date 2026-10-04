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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.auth.FirebaseGate
import com.fliptle.app.auth.PendingDeletion
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Delete my account" process, in the same frame as the uninstall request: each
 * day requires BOTH
 *   1) [DeletionConfig.QUESTIONS_PER_DAY] arithmetic questions (checked in the app), then
 *   2) typing the numbers 1..[DeletionConfig.TYPING_MAX] with no separators (checked per
 *      keystroke; paste and bulk input rejected, paste menu disabled).
 * One day unlocks per 24h with the same clock- and reboot-proof timing as the
 * freeze (see [DeletionGateStore]); a missed day resets to day 1.
 *
 * When every day is done the user can request deletion. That asks the server
 * (requestAccountDeletion), which always sets a FRESH 72 hours from that moment and
 * signs the account out everywhere. The app then shows the date and signs this phone
 * out through the ordinary sign-out. Progress in this screen has no effect on the
 * 72 hours: this screen only decides when the user may ask.
 *
 * Nothing here touches blocking, the freeze, entitlement or the uninstall gate, and
 * nothing here writes to Firestore: the server does all of that.
 */
class AccountDeletionActivity : AppCompatActivity() {

    private enum class Phase { NONE, MATH, TYPING }

    private lateinit var store: DeletionGateStore

    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var startDayButton: Button
    private lateinit var requestButton: Button
    private lateinit var cancelButton: Button
    private lateinit var debugCheck: CheckBox

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

    private var phase = Phase.NONE
    private var questions: List<MathQuestion> = emptyList()
    private var qIndex = 0

    private val expectedSeq: String = DeletionConfig.typingSequence()
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
        // Deleting an account needs a signed-in account.
        val uid = if (FirebaseGate.isAvailable(this)) FirebaseAuth.getInstance().currentUser?.uid else null
        if (uid == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_account_deletion)
        store = DeletionGateStore(this, uid)
        if (!store.active) store.start()

        findViewById<TextView>(R.id.deleteBodyText).text = getString(
            R.string.account_delete_body,
            DeletionConfig.DAYS_REQUIRED, DeletionConfig.QUESTIONS_PER_DAY, DeletionConfig.TYPING_MAX
        )
        findViewById<TextView>(R.id.typingPromptText).text =
            getString(R.string.account_delete_typing_prompt, DeletionConfig.TYPING_MAX)

        statusText = findViewById(R.id.deleteStatusText)
        countdownText = findViewById(R.id.deleteCountdownText)
        startDayButton = findViewById(R.id.startDayButton)
        requestButton = findViewById(R.id.requestDeletionButton)
        cancelButton = findViewById(R.id.cancelDeleteRequestButton)
        debugCheck = findViewById(R.id.deleteDebugDaysCheck)

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

        // Short-day testing switch: a developer tool, hidden unless DevMode is
        // unlocked and absent from release builds. It shortens only the daily lock
        // on this phone, never the server's 72 hours.
        if (DevMode.enabled(this)) {
            debugCheck.visibility = View.VISIBLE
            debugCheck.isChecked = store.debugMode
            debugCheck.setOnCheckedChangeListener { _, v -> store.debugMode = v; render() }
        } else {
            debugCheck.visibility = View.GONE
        }

        startDayButton.setOnClickListener { startDay() }
        submitAnswerButton.setOnClickListener { submitAnswer() }
        requestButton.setOnClickListener {
            PendingDeletion.confirm(this) { scheduledForMs -> onScheduled(scheduledForMs) }
        }
        cancelButton.setOnClickListener {
            store.cancel()
            Toast.makeText(this, R.string.account_delete_cancelled, Toast.LENGTH_SHORT).show()
            finish()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ---- After the server accepted the request ----

    /**
     * The server has set the time and revoked every refresh token. Forget the local
     * progress (a later request starts again from day 1), remember the date for the
     * sign-in screen, show it, and sign out like an ordinary sign-out once the user
     * has seen it. The dialog cannot be dismissed any other way.
     */
    private fun onScheduled(scheduledForMs: Long) {
        store.cancel()
        PendingDeletion.setNotice(this, scheduledForMs)
        val whenText = java.text.DateFormat
            .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(scheduledForMs))
        AlertDialog.Builder(this)
            .setTitle(R.string.account_delete_scheduled_title)
            .setMessage(getString(R.string.account_delete_scheduled_body, whenText))
            .setCancelable(false)
            .setPositiveButton(R.string.account_delete_ok) { _, _ -> SignOut.perform(this) }
            .show()
    }

    // ---- Day flow: MATH then TYPING ----

    private fun startDay() {
        questions = MathQuestions.generateSet(DeletionConfig.QUESTIONS_PER_DAY)
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
            mathErrorText.setText(R.string.account_delete_math_enter_number)
            return
        }
        if (entered == questions[qIndex].answer) {
            qIndex++
            answerInput.text.clear()
            mathErrorText.text = ""
            if (qIndex >= questions.size) beginTypingPhase() else render()
        } else {
            mathErrorText.setText(R.string.account_delete_math_wrong)
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
                    typingErrorText.setText(
                        if (bulk) R.string.account_delete_typing_error_paste
                        else R.string.account_delete_typing_error_wrong
                    )
                }
            }
            typingProgressText.text = typingProgress()
            if (typedSeq.toString() == expectedSeq) finishDay()
        }
    }

    private fun typingProgress() =
        getString(R.string.account_delete_typing_progress, typedSeq.length, expectedSeq.length)

    private fun finishDay() {
        phase = Phase.NONE
        val done = store.completeDay()
        Toast.makeText(this, getString(R.string.account_delete_day_done, done), Toast.LENGTH_LONG).show()
        render()
    }

    // ---- Rendering ----

    private fun render() {
        mathSection.visibility = if (phase == Phase.MATH) View.VISIBLE else View.GONE
        typingSection.visibility = if (phase == Phase.TYPING) View.VISIBLE else View.GONE

        if (phase == Phase.MATH) {
            questionText.text = questions[qIndex].text
            mathProgressText.text = getString(R.string.account_delete_math_progress, qIndex + 1, questions.size)
            return
        }
        if (phase == Phase.TYPING) {
            typingProgressText.text = typingProgress()
            return
        }

        val state = store.state()
        if (store.justReset) {
            store.clearJustReset()
            Toast.makeText(this, R.string.account_delete_reset_toast, Toast.LENGTH_LONG).show()
        }
        statusText.text = getString(R.string.account_delete_status, store.daysDone, DeletionConfig.DAYS_REQUIRED)
        countdownText.visibility = View.GONE
        startDayButton.visibility = View.GONE
        requestButton.visibility = View.GONE

        when (state) {
            DeletionGateStore.State.APPROVED -> {
                statusText.setText(R.string.account_delete_ready)
                requestButton.visibility = View.VISIBLE
            }
            DeletionGateStore.State.AVAILABLE -> {
                startDayButton.visibility = View.VISIBLE
                startDayButton.text = getString(
                    R.string.account_delete_start_day, store.daysDone + 1, DeletionConfig.QUESTIONS_PER_DAY
                )
            }
            DeletionGateStore.State.LOCKED -> {
                countdownText.visibility = View.VISIBLE
                countdownText.text = getString(R.string.account_delete_locked, hms(store.remainingMs()))
            }
            DeletionGateStore.State.VERIFYING -> {
                countdownText.visibility = View.VISIBLE
                countdownText.setText(R.string.account_delete_verifying)
                maybeFetchTrustedTime()
            }
            DeletionGateStore.State.INACTIVE -> { /* just started; treated as available */ }
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
