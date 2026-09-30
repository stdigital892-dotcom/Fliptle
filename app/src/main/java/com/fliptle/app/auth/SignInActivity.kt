package com.fliptle.app.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.CloudState
import com.fliptle.app.MainActivity
import com.fliptle.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Free authentication (no billing): Google one-tap and email/password with the
 * built-in email-verification link. The user record and reinstall tracking are
 * keyed off the Firebase Auth UID. Two optional steps follow a fresh sign-in,
 * in order: a display name (cosmetic — shown in place of the email wherever
 * the app greets the user), then a parent phone number (contact-only info,
 * never used for login/verification). Both are freely skippable.
 *
 * Three faces, chosen by auth state:
 *  • ALREADY signed in (opened from Home → Account): the account view — email +
 *    "Sign out". Never shows a sign-in prompt to an authenticated user.
 *  • NOT signed in (the mandatory gate): the sign-in controls.
 *  • JUST signed in via an action here: the optional name step, then the
 *    optional parent-phone step.
 *
 * Sign-in is mandatory app-wide (see AuthGate); this screen is also the account
 * screen once authenticated.
 */
class SignInActivity : AppCompatActivity() {

    private var auth: FirebaseAuth? = null

    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var signInControls: LinearLayout
    private lateinit var accountSection: LinearLayout
    private lateinit var accountEmailText: TextView
    private lateinit var accountDetailText: TextView
    private lateinit var nameSection: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var phoneSection: LinearLayout
    private lateinit var parentPhoneInput: EditText

    private val googleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken == null) {
                status(getString(R.string.auth_google_failed, "no ID token"))
                return@registerForActivityResult
            }
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth?.signInWithCredential(credential)?.addOnCompleteListener(this) { task ->
                if (task.isSuccessful) onSignedIn("google") else
                    status(getString(R.string.auth_google_failed, task.exception?.message ?: ""))
            }
        } catch (e: ApiException) {
            status(getString(R.string.auth_google_failed, e.message ?: ""))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        titleText = findViewById(R.id.authTitle)
        statusText = findViewById(R.id.authStatusText)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        signInControls = findViewById(R.id.signInControls)
        accountSection = findViewById(R.id.accountSection)
        accountEmailText = findViewById(R.id.accountEmailText)
        accountDetailText = findViewById(R.id.accountDetailText)
        nameSection = findViewById(R.id.nameSection)
        nameInput = findViewById(R.id.nameInput)
        phoneSection = findViewById(R.id.phoneSection)
        parentPhoneInput = findViewById(R.id.parentPhoneInput)

        // Static, Firebase-independent — set before the availability check below
        // so it still renders even in a degraded/unconfigured build.
        findViewById<TextView>(R.id.legalNoticeText).apply {
            text = android.text.Html.fromHtml(
                getString(R.string.auth_legal_notice),
                android.text.Html.FROM_HTML_MODE_LEGACY
            )
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }

        if (!FirebaseGate.isAvailable(this)) {
            status(getString(R.string.firebase_not_configured))
            disableAll()
            return
        }
        auth = FirebaseAuth.getInstance()

        findViewById<Button>(R.id.googleSignInButton).setOnClickListener { startGoogleSignIn() }
        findViewById<Button>(R.id.emailSignUpButton).setOnClickListener { signUpEmail() }
        findViewById<Button>(R.id.emailSignInButton).setOnClickListener { signInEmail() }
        findViewById<Button>(R.id.saveNameButton).setOnClickListener { saveName() }
        findViewById<Button>(R.id.skipNameButton).setOnClickListener { skipName() }
        findViewById<Button>(R.id.savePhoneButton).setOnClickListener { saveParentPhone() }
        findViewById<Button>(R.id.skipPhoneButton).setOnClickListener { skipPhone() }
        findViewById<Button>(R.id.signOutButton).setOnClickListener { signOut() }
        findViewById<Button>(R.id.deleteAccountButton).setOnClickListener { DeleteAccount.confirm(this) }

        // Already authenticated -> this is the Account screen, not a sign-in prompt.
        if (auth?.currentUser != null) showAccountState()
    }

    // ---- Google ----

    private fun startGoogleSignIn() {
        val webClientId = webClientId(this)
        if (webClientId == null) {
            status(getString(R.string.auth_google_unconfigured))
            return
        }
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        val client = GoogleSignIn.getClient(this, gso)
        client.signOut() // force the account chooser each time
        googleLauncher.launch(client.signInIntent)
    }

    /** default_web_client_id is generated by the Google Services plugin only when
     *  a Google OAuth client exists; look it up by name so the build never breaks. */
    private fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id != 0) context.getString(id) else null
    }

    // ---- Email / password ----

    private fun signUpEmail() {
        val email = emailInput.text.toString().trim()
        val pw = passwordInput.text.toString()
        if (email.isEmpty() || pw.length < 6) {
            Toast.makeText(this, R.string.auth_email_hint, Toast.LENGTH_SHORT).show()
            return
        }
        status(getString(R.string.auth_working))
        auth?.createUserWithEmailAndPassword(email, pw)?.addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                auth?.currentUser?.sendEmailVerification()
                status(getString(R.string.auth_verify_sent, email))
                onSignedIn("password")
            } else {
                status(getString(R.string.auth_email_failed, task.exception?.message ?: ""))
            }
        }
    }

    private fun signInEmail() {
        val email = emailInput.text.toString().trim()
        val pw = passwordInput.text.toString()
        if (email.isEmpty() || pw.isEmpty()) {
            Toast.makeText(this, R.string.auth_email_hint, Toast.LENGTH_SHORT).show()
            return
        }
        status(getString(R.string.auth_working))
        auth?.signInWithEmailAndPassword(email, pw)?.addOnCompleteListener(this) { task ->
            if (task.isSuccessful) onSignedIn("password")
            else status(getString(R.string.auth_email_failed, task.exception?.message ?: ""))
        }
    }

    // ---- Shared ----

    private fun onSignedIn(method: String) {
        val user = auth?.currentUser ?: return
        showNameStep()
        // Never let a stale entitlement cache from whichever account was
        // previously signed in on this device leak into a fresh sign-in —
        // EntitlementStore is a single unscoped local cache, not keyed by
        // UID/email. Clearing here forces InboxConfirmActivity to start from
        // UNKNOWN and wait for a real getEntitlement response before it can
        // ever show "Continue" for this account.
        EntitlementStore(this).clear()
        // Re-sync this account's progress from the cloud (restores after a reinstall
        // or a previous sign-out; a no-op for a brand-new account).
        CloudState.restore(this) {}
        // Record this app sign-in (triggers the app's own welcome/setup email via
        // Cloud Function). Separate from the website's `waitlist` collection —
        // app users already have the app and are choosing a plan, not waiting
        // for early access.
        // Prefer the user's own name from the app's name step (once they've
        // provided it, on any sign-in after the first) over Google's profile
        // name — it's user-chosen and works for email/password sign-in too,
        // where Google's displayName is never populated. Falls back to
        // Google's name for a brand-new sign-in, since the name step's own
        // save happens later than this call and can't have landed yet.
        user.email?.let {
            AppSignupHelper.maybeRecordSignup(this, it, AuthStore(this).signedInName ?: user.displayName)
        }
        InstallTracker.recordSignIn(this, user.uid, user.email, method, AuthStore(this).installId()) { msg ->
            runOnUiThread {
                val verified = if (user.isEmailVerified) getString(R.string.auth_verified)
                else getString(R.string.auth_unverified)
                status("${getString(R.string.auth_signed_in, user.email ?: user.uid)}\n$verified\n$msg")
            }
        }
    }

    /** Account view for an already-authenticated user: email + sign out. */
    private fun showAccountState() {
        titleText.setText(R.string.auth_account_title)
        signInControls.visibility = View.GONE
        nameSection.visibility = View.GONE
        phoneSection.visibility = View.GONE
        statusText.visibility = View.GONE
        accountSection.visibility = View.VISIBLE

        val user = auth?.currentUser
        accountEmailText.text = getString(R.string.auth_signed_in, user?.email ?: user?.uid ?: "")
        accountDetailText.text = if (user?.isEmailVerified == true) {
            getString(R.string.auth_verified)
        } else {
            getString(R.string.auth_unverified)
        }
    }

    /** Sign out (with the required warning) via the shared flow. */
    private fun signOut() = com.fliptle.app.SignOut.confirm(this)

    /**
     * After a sign-in ACTION here, reveal the optional display-name step — the
     * first thing shown after sign-in, before the phone step. Mirrors
     * [showPhoneStep] exactly: prefill from the local cache, then best-effort
     * adopt whatever's already stored in Firestore for a returning/reinstalled
     * user so they aren't re-prompted.
     */
    private fun showNameStep() {
        signInControls.visibility = View.GONE
        nameSection.visibility = View.VISIBLE

        val store = AuthStore(this)
        if (nameInput.text.isNullOrEmpty()) {
            store.signedInName?.let { nameInput.setText(it) }
        }
        val user = auth?.currentUser
        if (user != null && !store.nameProvided) {
            InstallTracker.fetchDisplayName(this, user.uid) { existing ->
                runOnUiThread {
                    if (!existing.isNullOrBlank()) {
                        store.signedInName = existing
                        store.nameProvided = true
                        if (nameInput.text.isNullOrEmpty()) nameInput.setText(existing)
                    }
                }
            }
        }
    }

    private fun saveName() {
        val user = auth?.currentUser
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            // Nothing entered — treat exactly like Skip rather than erroring.
            skipName()
            return
        }
        if (name.length > 40) {
            Toast.makeText(this, R.string.auth_name_too_long, Toast.LENGTH_SHORT).show()
            return
        }
        // Save locally first (optimistic) so an offline user is never trapped,
        // then sync to Firestore under the same UID key.
        val store = AuthStore(this)
        store.signedInName = name
        store.nameProvided = true
        if (user != null) {
            InstallTracker.saveDisplayName(this, user.uid, name) { msg ->
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            }
        }
        showPhoneStep()
    }

    /** Skip the name step — purely cosmetic, so there is nothing to gate on. */
    private fun skipName() {
        AuthStore(this).nameProvided = true
        showPhoneStep()
    }

    /**
     * After a sign-in ACTION here, reveal the parent-phone section. The sign-in
     * controls are hidden because sign-in is done. The phone number itself is
     * OPTIONAL — "Skip for now" proceeds without one (see PhoneGate, whose
     * enforcement switch is currently off).
     */
    private fun showPhoneStep() {
        nameSection.visibility = View.GONE
        phoneSection.visibility = View.VISIBLE

        val store = AuthStore(this)
        // Prefill any number we already have on device.
        if (parentPhoneInput.text.isNullOrEmpty()) {
            store.signedInPhone?.let { parentPhoneInput.setText(it) }
        }
        // Best-effort: if this user already stored a phone (e.g. after a reinstall),
        // adopt it so they aren't needlessly re-prompted.
        val user = auth?.currentUser
        if (user != null && !store.parentPhoneProvided) {
            InstallTracker.fetchParentPhone(this, user.uid) { existing ->
                runOnUiThread {
                    if (!existing.isNullOrBlank()) {
                        store.signedInPhone = existing
                        store.parentPhoneProvided = true
                        if (parentPhoneInput.text.isNullOrEmpty()) parentPhoneInput.setText(existing)
                    }
                }
            }
        }
    }

    private fun saveParentPhone() {
        val user = auth?.currentUser
        if (user == null) {
            Toast.makeText(this, R.string.auth_email_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = normalizePhone(parentPhoneInput.text.toString())
        if (normalized == null) {
            Toast.makeText(this, R.string.auth_phone_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        // Save locally first (optimistic) so an offline user is never trapped by
        // the mandatory gate, then sync to Firestore under the same UID key.
        val store = AuthStore(this)
        store.signedInPhone = normalized
        store.parentPhoneProvided = true
        parentPhoneInput.setText(normalized)
        InstallTracker.saveParentPhone(this, user.uid, normalized) { msg ->
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
        Toast.makeText(this, R.string.auth_phone_saved, Toast.LENGTH_SHORT).show()
        proceed()
    }

    /**
     * Skip the phone step for now (optional until the partner system ships). Marks
     * the phone as "handled" so the gate — if it is ever re-enabled — is satisfied,
     * and proceeds to the main app without saving a number.
     */
    private fun skipPhone() {
        AuthStore(this).parentPhoneProvided = true
        proceed()
    }

    /**
     * Continue into the app after the phone step. On first sign-in, routes through
     * the inbox-confirm screen (shown once per account). On subsequent sign-ins,
     * goes directly to MainActivity.
     */
    private fun proceed() {
        if (!AuthStore(this).inboxConfirmShown) {
            startActivity(
                Intent(this, InboxConfirmActivity::class.java)
                    .putExtra(InboxConfirmActivity.EXTRA_EMAIL, auth?.currentUser?.email ?: "")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }
        if (isTaskRoot) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        finish()
    }

    /**
     * Validate and normalize a plausible phone number. Keeps an optional single
     * leading '+' and the digits; requires 7–15 digits (E.164 caps at 15). Returns
     * null if it doesn't look like a real number.
     */
    private fun normalizePhone(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 7 || digits.length > 15) return null
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    private fun disableAll() {
        for (id in intArrayOf(
            R.id.googleSignInButton, R.id.emailSignUpButton,
            R.id.emailSignInButton, R.id.saveNameButton, R.id.savePhoneButton
        )) findViewById<Button>(id).isEnabled = false
    }

    private fun status(message: String) {
        statusText.text = message
    }
}
