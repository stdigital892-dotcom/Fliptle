package com.fliptle.app.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.CloudState
import com.fliptle.app.MainActivity
import com.fliptle.app.OnboardingProgress
import com.fliptle.app.OnboardingState
import com.fliptle.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Free authentication (no billing): Google one-tap and email/password with the
 * built-in email-verification link. The user record and reinstall tracking are
 * keyed off the Firebase Auth UID. One combined, optional profile step follows
 * a fresh sign-in: a display name (cosmetic — shown in place of the email
 * wherever the app greets the user) and a parent phone number (contact-only
 * info, never used for login/verification), both on one screen. Each field is
 * independently skippable by leaving it blank — there's no separate skip
 * button per field.
 *
 * These are PER-ACCOUNT concerns, re-asked for any new account signing in —
 * even on an already-onboarded device — which is why they live here rather
 * than in OnboardingActivity's per-device step machine. A shared "Step X of Y"
 * indicator (see [OnboardingProgress]) bridges the two Activities; it's only
 * shown here while first-run onboarding is still in progress.
 *
 * Three faces, chosen by auth state:
 *  • ALREADY signed in (opened from Home → Account): the account view — email +
 *    "Sign out". Never shows a sign-in prompt to an authenticated user.
 *  • NOT signed in (the mandatory gate): the sign-in controls.
 *  • JUST signed in via an action here: the combined optional profile step.
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
    private lateinit var deletionBanner: TextView
    private lateinit var cancelDeletionButton: Button
    private lateinit var deleteAccountButton: Button
    private lateinit var authProgressSection: LinearLayout
    private lateinit var authProgressText: TextView
    private lateinit var authProgressBar: ProgressBar
    private lateinit var profileSection: LinearLayout
    private lateinit var nameInput: EditText
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
        deletionBanner = findViewById(R.id.deletionBanner)
        cancelDeletionButton = findViewById(R.id.cancelDeletionButton)
        deleteAccountButton = findViewById(R.id.deleteAccountButton)
        authProgressSection = findViewById(R.id.authProgressSection)
        authProgressText = findViewById(R.id.authProgressText)
        authProgressBar = findViewById(R.id.authProgressBar)
        profileSection = findViewById(R.id.profileSection)
        nameInput = findViewById(R.id.nameInput)
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
        findViewById<Button>(R.id.continueProfileButton).setOnClickListener { continueProfile() }
        findViewById<Button>(R.id.signOutButton).setOnClickListener { signOut() }
        deleteAccountButton.setOnClickListener { PendingDeletion.confirm(this) { refreshDeletionBanner() } }
        cancelDeletionButton.setOnClickListener { PendingDeletion.cancel(this) { refreshDeletionBanner() } }

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
        // FIRST, before anything else: never let a stale entitlement cache from
        // whichever account was previously signed in on this device leak into a
        // fresh sign-in. EntitlementStore is a single unscoped local cache, not
        // keyed by UID/email. Clearing here forces the gate to start from
        // UNKNOWN and wait for a real getEntitlement response before
        // InboxConfirmActivity can ever show "Continue" for this account.
        EntitlementStore(this).clear()
        val user = auth?.currentUser ?: return
        showProfileStep()
        // Re-sync this account's progress from the cloud (restores after a reinstall
        // or a previous sign-out; a no-op for a brand-new account).
        CloudState.restore(this) {}
        // Record this app sign-in (triggers the app's own welcome/setup email via
        // Cloud Function). Separate from the website's `waitlist` collection —
        // app users already have the app and are choosing a plan, not waiting
        // for early access.
        //
        // The name used here MUST be the confirmed installs/{uid}.displayName
        // from Firestore — not AuthStore(this).signedInName. That local cache
        // is populated by showProfileStep()'s call to InstallTracker.fetchDisplayName
        // just above, which is asynchronous: its callback has not run yet at
        // this point in onSignedIn(), so reading the cache here synchronously
        // was silently seeing last session's value (null, for a returning user
        // on a fresh local install) and falling through to user.displayName —
        // Google's own account profile name, NOT what was typed into Rescue's
        // own name step. sendAppWelcomeEmail only fires once, on the very first
        // appSignups/{email} doc creation, so getting this right on the first
        // try is the only chance there is — hence explicitly awaiting the read
        // rather than trusting whatever's cached locally at this instant.
        //
        // Falls back to nothing (omits the name entirely, not to Google's
        // profile name) if installs/{uid}.displayName truly doesn't exist yet —
        // the offer page already greets by email whenever no name is present.
        user.email?.let { email ->
            InstallTracker.fetchDisplayName(this, user.uid) { savedName ->
                AppSignupHelper.maybeRecordSignup(this, email, savedName)
            }
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
        profileSection.visibility = View.GONE
        authProgressSection.visibility = View.GONE
        statusText.visibility = View.GONE
        accountSection.visibility = View.VISIBLE

        val user = auth?.currentUser
        accountEmailText.text = getString(R.string.auth_signed_in, user?.email ?: user?.uid ?: "")
        accountDetailText.text = if (user?.isEmailVerified == true) {
            getString(R.string.auth_verified)
        } else {
            getString(R.string.auth_unverified)
        }
        refreshDeletionBanner()
    }

    /**
     * Show the scheduled-deletion banner and Cancel button when the server says a
     * deletion is scheduled (so it also appears after sign-out and sign-in), and
     * the Delete button otherwise. A failed read leaves the screen as it was.
     */
    private fun refreshDeletionBanner() {
        val uid = auth?.currentUser?.uid ?: return
        renderDeletionBanner(PendingDeletion.cachedFor(this, uid))
        PendingDeletion.fetch(this, uid) { ms, ok ->
            if (ok) runOnUiThread { renderDeletionBanner(ms) }
        }
    }

    private fun renderDeletionBanner(scheduledForMs: Long?) {
        val scheduled = scheduledForMs != null
        deletionBanner.visibility = if (scheduled) View.VISIBLE else View.GONE
        cancelDeletionButton.visibility = if (scheduled) View.VISIBLE else View.GONE
        deleteAccountButton.visibility = if (scheduled) View.GONE else View.VISIBLE
        if (scheduledForMs != null) {
            val whenText = java.text.DateFormat
                .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(java.util.Date(scheduledForMs))
            deletionBanner.text = getString(R.string.deletion_banner, whenText)
        }
    }

    /** Sign out (with the required warning) via the shared flow. */
    private fun signOut() = com.fliptle.app.SignOut.confirm(this)

    /**
     * After a sign-in ACTION here, reveal the combined optional profile step —
     * name and parent phone together on one screen, each independently
     * prefilled from the local cache and then best-effort adopted from
     * Firestore for a returning/reinstalled user so they aren't re-prompted.
     */
    private fun showProfileStep() {
        signInControls.visibility = View.GONE
        profileSection.visibility = View.VISIBLE

        val store = AuthStore(this)
        if (nameInput.text.isNullOrEmpty()) {
            store.signedInName?.let { nameInput.setText(it) }
        }
        if (parentPhoneInput.text.isNullOrEmpty()) {
            store.signedInPhone?.let { parentPhoneInput.setText(it) }
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

        // "Step 3 of 7" — only during active first-run onboarding; hidden for a
        // returning user re-signing in on an already-onboarded device.
        if (!OnboardingState(this).complete) {
            authProgressSection.visibility = View.VISIBLE
            authProgressText.text = getString(
                R.string.ob_progress_format, OnboardingProgress.PROFILE, OnboardingProgress.TOTAL
            )
            authProgressBar.progress = (OnboardingProgress.PROFILE * 100) / OnboardingProgress.TOTAL
        } else {
            authProgressSection.visibility = View.GONE
        }
    }

    /**
     * Single "Continue" for the combined profile step. Each field is
     * independently optional — a blank field IS how you skip it, there's no
     * separate skip button. Name has no real validation beyond a length cap;
     * phone keeps its format validation and blocks continuing if what's typed
     * doesn't look like a number at all.
     */
    private fun continueProfile() {
        val user = auth?.currentUser
        val store = AuthStore(this)

        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            store.nameProvided = true
        } else if (name.length > 40) {
            Toast.makeText(this, R.string.auth_name_too_long, Toast.LENGTH_SHORT).show()
            return
        } else {
            store.signedInName = name
            store.nameProvided = true
            if (user != null) {
                InstallTracker.saveDisplayName(this, user.uid, name) { msg ->
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                }
            }
        }

        val phoneRaw = parentPhoneInput.text.toString().trim()
        if (phoneRaw.isEmpty()) {
            store.parentPhoneProvided = true
        } else {
            val normalized = normalizePhone(phoneRaw)
            if (normalized == null) {
                Toast.makeText(this, R.string.auth_phone_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            store.signedInPhone = normalized
            store.parentPhoneProvided = true
            parentPhoneInput.setText(normalized)
            if (user != null) {
                InstallTracker.saveParentPhone(this, user.uid, normalized) { msg ->
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                }
            }
        }

        proceed()
    }

    /**
     * Continue after the profile step: always hand off to MainActivity, which
     * routes through the gate chain (inbox-confirm / paywall before any
     * permission step). Starting it with CLEAR_TASK also drops any stale
     * onboarding screen from the back stack; onboarding resumes later at the
     * permission steps once the user is entitled.
     */
    private fun proceed() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
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
            R.id.emailSignInButton, R.id.continueProfileButton
        )) findViewById<Button>(id).isEnabled = false
    }

    private fun status(message: String) {
        statusText.text = message
    }
}
