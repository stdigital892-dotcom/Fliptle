package com.fliptle.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.auth.FirebaseGate
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Manual typing gate: the user must type the numbers 1..50 concatenated with no
 * separators. Validated PER KEYSTROKE — only single-character appends that match
 * the next expected character are accepted; any paste / bulk insert / wrong
 * character is rejected (and the inputField blocks the paste menu itself).
 *
 * On completion the entry is sent to Firestore, where Security Rules validate the
 * value server-side; a successful write is the "correct" confirmation and is
 * logged with a server timestamp.
 */
class TypingGateActivity : AppCompatActivity() {

    private val expected: String = buildString { for (i in 1..50) append(i) }
    private val typed = StringBuilder()
    private var selfEdit = false

    private lateinit var inputField: NoPasteEditText
    private lateinit var progressText: TextView
    private lateinit var errorText: TextView
    private lateinit var submitButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_typing_gate)

        inputField = findViewById(R.id.typingField)
        progressText = findViewById(R.id.progressText)
        errorText = findViewById(R.id.errorText)
        submitButton = findViewById(R.id.submitButton)

        inputField.addTextChangedListener(watcher)
        submitButton.setOnClickListener { submit() }
        render()
    }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (selfEdit) return
            val current = s?.toString() ?: ""

            when {
                // Single valid character appended at the end.
                current.length == typed.length + 1 &&
                    typed.length < expected.length &&
                    current.startsWith(typed) &&
                    current.last() == expected[typed.length] -> {
                    typed.append(current.last())
                    clearError()
                }
                // Single backspace (deletion of the last character).
                current.length == typed.length - 1 && typed.startsWith(current) -> {
                    typed.setLength(current.length)
                    clearError()
                }
                // No-op (same content).
                current == typed.toString() -> { /* nothing */ }
                // Anything else = paste, bulk insert, wrong char, or mid-string edit.
                else -> {
                    val bulk = current.length > typed.length + 1
                    revertField()
                    showError(if (bulk) R.string.typing_error_paste else R.string.typing_error_wrong)
                }
            }
            render()
        }
    }

    private fun revertField() {
        selfEdit = true
        inputField.setText(typed.toString())
        inputField.setSelection(typed.length)
        selfEdit = false
    }

    private fun render() {
        progressText.text = getString(R.string.typing_progress, typed.length, expected.length)
        submitButton.isEnabled = typed.toString() == expected
    }

    private fun showError(resId: Int) {
        errorText.text = getString(resId)
    }

    private fun clearError() {
        errorText.text = ""
    }

    private fun submit() {
        if (typed.toString() != expected) {
            showError(R.string.typing_error_wrong)
            return
        }
        val user = if (FirebaseGate.isAvailable(this)) FirebaseAuth.getInstance().currentUser else null
        if (user == null) {
            // Not signed in / Firebase off: confirm locally so the gate still works.
            errorText.text = ""
            progressText.text = getString(R.string.typing_done_local)
            submitButton.isEnabled = false
            return
        }

        submitButton.isEnabled = false
        progressText.text = getString(R.string.typing_submitting)
        FirebaseFirestore.getInstance()
            .collection("typing_gate").document(user.uid)
            .collection("attempts").add(
                mapOf(
                    "value" to typed.toString(),
                    "length" to typed.length,
                    "timestamp" to FieldValue.serverTimestamp()
                )
            )
            .addOnSuccessListener {
                // Server (Firestore rules) accepted the value -> confirmed correct.
                progressText.text = getString(R.string.typing_done_server)
            }
            .addOnFailureListener { e ->
                submitButton.isEnabled = true
                showError(getString(R.string.typing_server_rejected, e.message ?: ""))
            }
    }

    private fun showError(message: String) {
        errorText.text = message
    }
}
