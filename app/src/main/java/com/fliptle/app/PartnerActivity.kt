package com.fliptle.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.auth.FirebaseGate
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions

/**
 * "Your accountability partner" screen. One trusted person who is messaged on
 * WhatsApp only if Rescue is removed from the phone before the user finishes
 * the exit process. The partner can reply STOP at any time; a STOP mutes every
 * future alert to that number across every account.
 *
 * The number is 10 digits normalised to "91" + 10 digits by [PartnerPhone],
 * which mirrors functions/phone.js. We do NOT ask for the partner's name.
 *
 * All writes go through the server: savePartner / removePartner callables in
 * functions/index.js. The client is only ALLOWED to read partnerLinks/{uid},
 * which holds a status and the last-4 digits (never the full number again).
 */
class PartnerActivity : AppCompatActivity() {

    private val region = "asia-south2"
    private val maxNote = 200

    private lateinit var statusText: TextView
    private lateinit var phoneInput: EditText
    private lateinit var phoneError: TextView
    private lateinit var confirmCheck: CheckBox
    private lateinit var noteInput: EditText
    private lateinit var noteCounter: TextView
    private lateinit var saveButton: Button
    private lateinit var removeButton: Button

    private var normalizedPhone: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (FirebaseAuth.getInstance().currentUser == null) { finish(); return }
        setContentView(R.layout.activity_partner)

        statusText = findViewById(R.id.partnerStatusText)
        phoneInput = findViewById(R.id.partnerPhoneInput)
        phoneError = findViewById(R.id.partnerPhoneError)
        confirmCheck = findViewById(R.id.partnerConfirmCheck)
        noteInput = findViewById(R.id.partnerNoteInput)
        noteCounter = findViewById(R.id.partnerNoteCounter)
        saveButton = findViewById(R.id.savePartnerButton)
        removeButton = findViewById(R.id.removePartnerButton)

        phoneInput.addTextChangedListener(onChange { validatePhone(); refreshSaveEnabled() })
        confirmCheck.setOnCheckedChangeListener { _, _ -> refreshSaveEnabled() }
        noteInput.addTextChangedListener(onChange { updateNoteCounter() })
        updateNoteCounter()

        saveButton.setOnClickListener { save() }
        removeButton.setOnClickListener { remove() }
    }

    override fun onResume() {
        super.onResume()
        // Server is the source of truth for status (and for whether the partner
        // has STOPped); re-read every time the screen comes forward.
        refreshStatus()
    }

    // ---- status --------------------------------------------------------

    private fun refreshStatus() {
        if (!FirebaseGate.isAvailable(this)) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        statusText.visibility = View.VISIBLE
        statusText.setText(R.string.partner_status_checking)
        FirebaseFirestore.getInstance().collection("partnerLinks").document(uid)
            .get(Source.SERVER)
            .addOnSuccessListener { snap ->
                renderStatus(
                    snap.getString("status"),
                    snap.getString("partnerPhoneLast4")
                )
            }
            .addOnFailureListener {
                // Leave the status hidden rather than fake one on a failed read.
                statusText.visibility = View.GONE
            }
    }

    private fun renderStatus(status: String?, last4: String?) {
        when (status) {
            "saved" -> {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.partner_status_saved, last4 ?: "----")
                removeButton.visibility = View.VISIBLE
            }
            "stopped" -> {
                statusText.visibility = View.VISIBLE
                statusText.setText(R.string.partner_status_stopped)
                // Still allow Remove — a user who wants to change partners can clear
                // the slot and save a different one.
                removeButton.visibility = View.VISIBLE
            }
            "none", null -> {
                statusText.visibility = View.VISIBLE
                statusText.setText(R.string.partner_status_none)
                removeButton.visibility = View.GONE
            }
            else -> {
                statusText.visibility = View.GONE
                removeButton.visibility = View.GONE
            }
        }
    }

    // ---- validation ----------------------------------------------------

    private fun validatePhone() {
        val raw = phoneInput.text.toString()
        if (raw.isBlank()) { normalizedPhone = null; phoneError.visibility = View.GONE; return }
        val n = PartnerPhone.normalizeIndianNumber(raw)
        normalizedPhone = n
        if (n == null) {
            phoneError.setText(R.string.partner_phone_invalid)
            phoneError.visibility = View.VISIBLE
        } else {
            phoneError.visibility = View.GONE
        }
    }

    private fun refreshSaveEnabled() {
        saveButton.isEnabled = !busy && normalizedPhone != null && confirmCheck.isChecked
    }

    private fun updateNoteCounter() {
        val len = noteInput.text?.length ?: 0
        noteCounter.text = getString(R.string.partner_note_counter, len, maxNote)
    }

    // ---- callables -----------------------------------------------------

    private fun save() {
        val phone = normalizedPhone ?: return
        if (!confirmCheck.isChecked) return
        setBusy(true, R.string.partner_saving)
        val note = noteInput.text.toString().trim()
        FirebaseFunctions.getInstance(region).getHttpsCallable("savePartner").call(
            mapOf("partnerPhone" to phone, "note" to note, "userConfirmed" to true)
        ).addOnSuccessListener {
            setBusy(false, R.string.partner_save)
            Toast.makeText(this, R.string.partner_saved_toast, Toast.LENGTH_SHORT).show()
            refreshStatus()
        }.addOnFailureListener { e ->
            setBusy(false, R.string.partner_save)
            Toast.makeText(
                this, getString(R.string.partner_save_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun remove() {
        setBusy(true, R.string.partner_removing)
        FirebaseFunctions.getInstance(region).getHttpsCallable("removePartner").call()
            .addOnSuccessListener {
                setBusy(false, R.string.partner_save)
                Toast.makeText(this, R.string.partner_removed_toast, Toast.LENGTH_SHORT).show()
                phoneInput.text.clear()
                confirmCheck.isChecked = false
                noteInput.text.clear()
                refreshStatus()
            }
            .addOnFailureListener { e ->
                setBusy(false, R.string.partner_save)
                Toast.makeText(
                    this, getString(R.string.partner_remove_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun setBusy(b: Boolean, labelRes: Int) {
        busy = b
        saveButton.setText(labelRes)
        refreshSaveEnabled()
        removeButton.isEnabled = !b
    }

    // ---- helpers -------------------------------------------------------

    private inline fun onChange(crossinline block: () -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { block() }
        }
}
