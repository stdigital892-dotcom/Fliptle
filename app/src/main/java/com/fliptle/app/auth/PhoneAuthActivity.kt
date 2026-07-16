package com.fliptle.app.auth

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.fliptle.app.R
import java.util.concurrent.TimeUnit

/**
 * Phone-number sign-in (OTP) via Firebase Auth. Optional: the app's protection
 * features work without signing in. On success, install/reinstall history is
 * recorded through [InstallTracker].
 */
class PhoneAuthActivity : AppCompatActivity() {

    private var auth: FirebaseAuth? = null
    private var verificationId: String? = null

    private lateinit var phoneInput: EditText
    private lateinit var sendCodeButton: Button
    private lateinit var codeInput: EditText
    private lateinit var verifyButton: Button
    private lateinit var statusText: TextView

    private val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            // Auto-retrieval or instant validation succeeded.
            signInWithCredential(credential)
        }

        override fun onVerificationFailed(e: FirebaseException) {
            status("Verification failed: ${e.message}")
        }

        override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
            verificationId = id
            status("Code sent. Enter the 6-digit code below.")
            codeInput.visibility = View.VISIBLE
            verifyButton.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_auth)

        phoneInput = findViewById(R.id.phoneInput)
        sendCodeButton = findViewById(R.id.sendCodeButton)
        codeInput = findViewById(R.id.codeInput)
        verifyButton = findViewById(R.id.verifyButton)
        statusText = findViewById(R.id.authStatusText)

        if (!FirebaseGate.isAvailable(this)) {
            status(getString(R.string.firebase_not_configured))
            sendCodeButton.isEnabled = false
            verifyButton.isEnabled = false
            return
        }

        auth = FirebaseAuth.getInstance()
        AuthStore(this).signedInPhone?.let { status("Currently signed in as $it") }

        sendCodeButton.setOnClickListener { sendCode() }
        verifyButton.setOnClickListener { verifyCode() }
    }

    private fun sendCode() {
        val currentAuth = auth ?: return
        val phone = phoneInput.text.toString().trim()
        if (phone.isEmpty()) {
            Toast.makeText(this, R.string.enter_phone, Toast.LENGTH_SHORT).show()
            return
        }
        status("Sending code to $phone…")
        val options = PhoneAuthOptions.newBuilder(currentAuth)
            .setPhoneNumber(phone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun verifyCode() {
        val id = verificationId
        val code = codeInput.text.toString().trim()
        if (id == null || code.isEmpty()) {
            Toast.makeText(this, R.string.enter_code, Toast.LENGTH_SHORT).show()
            return
        }
        signInWithCredential(PhoneAuthProvider.getCredential(id, code))
    }

    private fun signInWithCredential(credential: PhoneAuthCredential) {
        val currentAuth = auth ?: return
        status("Signing in…")
        currentAuth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                val phone = currentAuth.currentUser?.phoneNumber
                    ?: phoneInput.text.toString().trim()
                val storeLocal = AuthStore(this)
                storeLocal.signedInPhone = phone
                status("Signed in as $phone. Checking install history…")
                InstallTracker.recordSignIn(this, phone, storeLocal.installId()) { msg ->
                    runOnUiThread { status(msg) }
                }
            } else {
                status("Sign-in failed: ${task.exception?.message}")
            }
        }
    }

    private fun status(message: String) {
        statusText.text = message
    }
}
