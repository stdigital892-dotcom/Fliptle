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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.auth.FirebaseGate
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions

/**
 * "Your accountability partner" screen. One trusted person who may be messaged
 * on WhatsApp if Rescue stops reporting from this phone for about three days
 * (72 hours) before the exit process finishes. The partner can reply STOP at
 * any time.
 *
 * The number is 10 digits normalised to "91" + 10 digits by [PartnerPhone],
 * which mirrors functions/phone.js and applies the same anti-typo rules
 * (savePartner re-validates on the server, including a check the app can't do
 * — whether it's the WhatsApp business number). We do NOT ask for the
 * partner's name.
 *
 * D3: removing or replacing an EXISTING partner is never immediate — it is
 * requested, takes effect 72 hours later (applyPendingPartnerChanges, only if
 * the account is still checking in), and can be cancelled any time before
 * then. Only the FIRST partner saves immediately. All writes go through the
 * server: savePartner / requestPartnerRemoval / cancelPartnerChange. The
 * client is only ALLOWED to read partnerLinks/{uid}.
 */
class PartnerActivity : AppCompatActivity() {

    private val region = "asia-south2"
    private val maxNote = 200

    private lateinit var statusText: TextView
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var formSection: View
    private lateinit var phoneInput: EditText
    private lateinit var phoneError: TextView
    private lateinit var confirmCheck: CheckBox
    private lateinit var noteInput: EditText
    private lateinit var noteCounter: TextView
    private lateinit var saveButton: Button

    private var normalizedPhone: String? = null
    private var busy = false
    /** True once the user taps Change/Add-a-different-partner; the form stays up until they Save or leave. */
    private var showingForm = false
    /** Last status read from the server — decides whether Save is an immediate first add or a 72h change. */
    private var lastStatus: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (FirebaseAuth.getInstance().currentUser == null) { finish(); return }
        setContentView(R.layout.activity_partner)

        statusText = findViewById(R.id.partnerStatusText)
        primaryButton = findViewById(R.id.partnerPrimaryButton)
        secondaryButton = findViewById(R.id.partnerSecondaryButton)
        formSection = findViewById(R.id.partnerFormSection)
        phoneInput = findViewById(R.id.partnerPhoneInput)
        phoneError = findViewById(R.id.partnerPhoneError)
        confirmCheck = findViewById(R.id.partnerConfirmCheck)
        noteInput = findViewById(R.id.partnerNoteInput)
        noteCounter = findViewById(R.id.partnerNoteCounter)
        saveButton = findViewById(R.id.savePartnerButton)

        phoneInput.addTextChangedListener(onChange { validatePhone(); refreshSaveEnabled() })
        confirmCheck.setOnCheckedChangeListener { _, _ -> refreshSaveEnabled() }
        noteInput.addTextChangedListener(onChange { updateNoteCounter() })
        updateNoteCounter()

        saveButton.setOnClickListener { onSaveTapped() }
    }

    override fun onResume() {
        super.onResume()
        // Server is the source of truth for status and any pending change;
        // re-read every time the screen comes forward.
        refreshStatus()
    }

    // ---- status ----------------------------------------------------------

    private fun refreshStatus() {
        if (!FirebaseGate.isAvailable(this)) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        statusText.visibility = View.VISIBLE
        statusText.setText(R.string.partner_status_checking)
        FirebaseFirestore.getInstance().collection("partnerLinks").document(uid)
            .get(Source.SERVER)
            .addOnSuccessListener { snap ->
                lastStatus = snap.getString("status")
                render(
                    status = lastStatus,
                    last4 = snap.getString("partnerPhoneLast4"),
                    pendingType = snap.getString("pendingType"),
                    pendingEffectiveAtMs = snap.getLong("pendingEffectiveAt"),
                    pendingLast4 = snap.getString("pendingPhoneLast4")
                )
            }
            .addOnFailureListener {
                // Leave the screen as it was rather than fake a state on a failed read.
                statusText.visibility = View.GONE
            }
    }

    private fun render(
        status: String?,
        last4: String?,
        pendingType: String?,
        pendingEffectiveAtMs: Long?,
        pendingLast4: String?
    ) {
        if (showingForm) {
            // The user is actively adding/changing a partner: keep the form up,
            // hide everything else, until Save (or Remove/Keep) resolves it.
            statusText.visibility = View.GONE
            primaryButton.visibility = View.GONE
            secondaryButton.visibility = View.GONE
            formSection.visibility = View.VISIBLE
            saveButton.visibility = View.VISIBLE
            return
        }

        formSection.visibility = View.GONE
        saveButton.visibility = View.GONE
        statusText.visibility = View.VISIBLE

        when {
            pendingType == "remove" -> {
                statusText.text = getString(R.string.partner_pending_remove, formatWhen(pendingEffectiveAtMs))
                primaryButton.visibility = View.GONE
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.setText(R.string.partner_keep)
                secondaryButton.setOnClickListener { keepPartner() }
            }
            pendingType == "replace" -> {
                statusText.text = getString(
                    R.string.partner_pending_replace, pendingLast4 ?: "----", formatWhen(pendingEffectiveAtMs)
                )
                primaryButton.visibility = View.GONE
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.setText(R.string.partner_keep_current)
                secondaryButton.setOnClickListener { keepPartner() }
            }
            status == "saved" -> {
                statusText.text = getString(R.string.partner_status_saved, last4 ?: "----")
                primaryButton.visibility = View.VISIBLE
                primaryButton.setText(R.string.partner_change)
                primaryButton.setOnClickListener { openForm() }
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.setText(R.string.partner_remove)
                secondaryButton.setOnClickListener { confirmRemove() }
            }
            status == "stopped" -> {
                statusText.setText(R.string.partner_status_stopped)
                primaryButton.visibility = View.VISIBLE
                primaryButton.setText(R.string.partner_add_different)
                primaryButton.setOnClickListener { openForm() }
                secondaryButton.visibility = View.GONE
            }
            else -> {
                // "none", null, or anything unexpected: show the form directly,
                // same as today for a user who has never saved a partner.
                statusText.visibility = View.GONE
                primaryButton.visibility = View.GONE
                secondaryButton.visibility = View.GONE
                formSection.visibility = View.VISIBLE
                saveButton.visibility = View.VISIBLE
            }
        }
    }

    private fun openForm() {
        showingForm = true
        render(lastStatus, null, null, null, null)
    }

    private fun formatWhen(ms: Long?): String {
        if (ms == null || ms <= 0L) return ""
        return java.text.DateFormat
            .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(ms))
    }

    // ---- validation --------------------------------------------------------

    private fun validatePhone() {
        val raw = phoneInput.text.toString()
        if (raw.isBlank()) { normalizedPhone = null; phoneError.visibility = View.GONE; return }
        val n = PartnerPhone.normalizedIfValid(raw)
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

    // ---- save / remove / keep ----------------------------------------------

    /** First partner (no existing one): saves immediately. An existing partner: confirm the 72h delay first. */
    private fun onSaveTapped() {
        val phone = normalizedPhone ?: return
        if (!confirmCheck.isChecked) return
        val isFirstPartner = lastStatus == "none" || lastStatus == null
        if (isFirstPartner) {
            save(phone)
        } else {
            confirmDelay { save(phone) }
        }
    }

    private fun confirmDelay(onConfirmed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.partner_confirm_delay_title)
            .setMessage(R.string.partner_confirm_delay_body)
            .setNegativeButton(R.string.partner_confirm_delay_cancel, null)
            .setPositiveButton(R.string.partner_confirm_delay_ok) { _, _ -> onConfirmed() }
            .show()
    }

    private fun confirmRemove() {
        confirmDelay { remove() }
    }

    private fun save(phone: String) {
        setBusy(true, R.string.partner_saving)
        val note = noteInput.text.toString().trim()
        FirebaseFunctions.getInstance(region).getHttpsCallable("savePartner").call(
            mapOf("partnerPhone" to phone, "note" to note, "userConfirmed" to true)
        ).addOnSuccessListener {
            setBusy(false, R.string.partner_save)
            showingForm = false
            Toast.makeText(this, R.string.partner_saved_toast, Toast.LENGTH_SHORT).show()
            phoneInput.text.clear()
            confirmCheck.isChecked = false
            noteInput.text.clear()
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
        setBusy(true, R.string.partner_requesting)
        FirebaseFunctions.getInstance(region).getHttpsCallable("requestPartnerRemoval").call()
            .addOnSuccessListener {
                setBusy(false, R.string.partner_save)
                refreshStatus()
            }
            .addOnFailureListener { e ->
                setBusy(false, R.string.partner_save)
                Toast.makeText(
                    this, getString(R.string.partner_change_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun keepPartner() {
        setBusy(true, R.string.partner_requesting)
        FirebaseFunctions.getInstance(region).getHttpsCallable("cancelPartnerChange").call()
            .addOnSuccessListener {
                setBusy(false, R.string.partner_save)
                refreshStatus()
            }
            .addOnFailureListener { e ->
                setBusy(false, R.string.partner_save)
                Toast.makeText(
                    this, getString(R.string.partner_keep_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun setBusy(b: Boolean, labelRes: Int) {
        busy = b
        saveButton.setText(labelRes)
        refreshSaveEnabled()
        primaryButton.isEnabled = !b
        secondaryButton.isEnabled = !b
    }

    // ---- helpers -------------------------------------------------------

    private inline fun onChange(crossinline block: () -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { block() }
        }
}
