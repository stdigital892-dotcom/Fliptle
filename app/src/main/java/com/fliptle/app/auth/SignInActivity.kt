package com.fliptle.app.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.AccountDeletionActivity
import com.fliptle.app.CloudState
import com.fliptle.app.MainActivity
import com.fliptle.app.OnboardingProgress
import com.fliptle.app.OnboardingState
import com.fliptle.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Free authentication (no billing): Sign in with Google is the only way in. The
 * user record and reinstall tracking are keyed off the Firebase Auth UID. One
 * optional name step follows a fresh sign-in: a display name (cosmetic — shown
 * in place of the email wherever the app greets the user), pre-filled from the
 * Google account. Leaving it blank skips it — there's no separate skip button.
 * The app no longer asks for the user's own phone number.
 *
 * Accounts that were created earlier with an email and password and are still
 * signed in stay signed in (nothing here signs anyone out); only the screens and
 * code for creating or entering a password are gone.
 *
 * The name is a PER-ACCOUNT concern, re-asked for any new account signing in —
 * even on an already-onboarded device — which is why they live here rather
 * than in OnboardingActivity's per-device step machine. A shared "Step X of Y"
 * indicator (see [OnboardingProgress]) bridges the two Activities; it's only
 * shown here while first-run onboarding is still in progress.
 *
 * Three faces, chosen by auth state:
 *  • ALREADY signed in (opened from Home → Account): the account view — email +
 *    "Sign out". Never shows a sign-in prompt to an authenticated user.
 *  • NOT signed in (the mandatory gate): the sign-in controls.
 *  • JUST signed in via an action here: the optional name step.
 *
 * Sign-in is mandatory app-wide (see AuthGate); this screen is also the account
 * screen once authenticated.
 */
class SignInActivity : AppCompatActivity() {

    private var auth: FirebaseAuth? = null

    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var signInControls: LinearLayout
    private lateinit var accountSection: LinearLayout
    private lateinit var accountEmailText: TextView
    private lateinit var accountDetailText: TextView
    private lateinit var deletionBanner: TextView
    private lateinit var cancelDeletionButton: Button
    private lateinit var deleteAccountButton: Button
    private lateinit var deletionNoticeText: TextView
    private lateinit var deletionCheckSection: LinearLayout
    private lateinit var deletionCheckText: TextView
    private lateinit var deletionCheckRetryButton: Button
    private lateinit var deletionCheckSignOutButton: Button
    private lateinit var authProgressSection: LinearLayout
    private lateinit var authProgressText: TextView
    private lateinit var authProgressBar: ProgressBar
    private lateinit var profileSection: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var googleButton: View
    private lateinit var googleProgress: ProgressBar

    /** Only one Google sign-in at a time; every way it can end releases it. */
    private val attempt = SignInAttempt()

    private val googleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Backing out of the account picker: no error, just put the button back.
        GoogleSignInOutcome.fromPickerResult(
            resultCanceled = result.resultCode == Activity.RESULT_CANCELED,
            hasData = result.data != null
        )?.let { failAttempt(it, null); return@registerForActivityResult }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken == null) {
                failAttempt(SignInFailure.OTHER, null, "no id token")
                return@registerForActivityResult
            }
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val firebase = auth
            if (firebase == null) {
                failAttempt(SignInFailure.OTHER, null, "no auth instance")
                return@registerForActivityResult
            }
            firebase.signInWithCredential(credential).addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    endAttempt()
                    onSignedIn("google")
                } else {
                    val e = task.exception
                    failAttempt(GoogleSignInOutcome.fromFirebase(e is FirebaseNetworkException), e)
                }
            }
        } catch (e: ApiException) {
            failAttempt(GoogleSignInOutcome.fromGoogleStatusCode(e.statusCode), e)
        } catch (t: Throwable) {
            failAttempt(SignInFailure.OTHER, t)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        titleText = findViewById(R.id.authTitle)
        statusText = findViewById(R.id.authStatusText)
        signInControls = findViewById(R.id.signInControls)
        accountSection = findViewById(R.id.accountSection)
        accountEmailText = findViewById(R.id.accountEmailText)
        accountDetailText = findViewById(R.id.accountDetailText)
        deletionBanner = findViewById(R.id.deletionBanner)
        cancelDeletionButton = findViewById(R.id.cancelDeletionButton)
        deleteAccountButton = findViewById(R.id.deleteAccountButton)
        deletionNoticeText = findViewById(R.id.deletionNoticeText)
        deletionCheckSection = findViewById(R.id.deletionCheckSection)
        deletionCheckText = findViewById(R.id.deletionCheckText)
        deletionCheckRetryButton = findViewById(R.id.deletionCheckRetryButton)
        deletionCheckSignOutButton = findViewById(R.id.deletionCheckSignOutButton)
        authProgressSection = findViewById(R.id.authProgressSection)
        authProgressText = findViewById(R.id.authProgressText)
        authProgressBar = findViewById(R.id.authProgressBar)
        profileSection = findViewById(R.id.profileSection)
        nameInput = findViewById(R.id.nameInput)
        googleButton = findViewById(R.id.googleSignInButton)
        googleProgress = findViewById(R.id.googleSignInProgress)

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

        // googleSignInButton is an ImageButton (Google's branded PNG), not a Button;
        // setOnClickListener is on View so the cast is widened. The id and click
        // handler (startGoogleSignIn) are unchanged.
        googleButton.setOnClickListener { startGoogleSignIn() }
        findViewById<Button>(R.id.continueProfileButton).setOnClickListener { continueProfile() }
        findViewById<Button>(R.id.signOutButton).setOnClickListener { signOut() }
        // "Delete my account" starts the full process (days of gates) every time.
        deleteAccountButton.setOnClickListener {
            startActivity(Intent(this, AccountDeletionActivity::class.java))
        }
        cancelDeletionButton.setOnClickListener { PendingDeletion.cancel(this) { refreshDeletionBanner() } }
        deletionCheckRetryButton.setOnClickListener {
            runDeletionCheck(PendingDeletion.signInCheckMethod(this))
        }
        // The deletion-check retry screen keeps its own, unlocked sign-out.
        deletionCheckSignOutButton.setOnClickListener {
            com.fliptle.app.SignOut.confirm(this, enforceLock = false)
        }

        val current = auth?.currentUser
        when {
            // A fresh sign-in whose deletion check did not finish: carry on with it.
            current != null && PendingDeletion.signInCheckPending(this, current.uid) ->
                runDeletionCheck(PendingDeletion.signInCheckMethod(this))
            // Already authenticated -> this is the Account screen, not a sign-in prompt.
            current != null -> showAccountState()
            else -> showDeletionNotice()
        }
    }

    override fun onResume() {
        super.onResume()
        // Signed out: keep the "will be deleted on ..." notice current.
        if (auth != null && auth?.currentUser == null) showDeletionNotice()
        if (accountSection.visibility == View.VISIBLE) applySignOutLock()
    }

    // ---- Google ----

    private fun startGoogleSignIn() {
        if (!attempt.tryStart()) return // a sign-in is already running: ignore the extra tap
        status("")
        val webClientId = webClientId(this)
        if (webClientId == null) {
            attempt.end()
            status(getString(R.string.auth_google_unconfigured))
            return
        }
        setGoogleLoading(true)
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build()
            val client = GoogleSignIn.getClient(this, gso)
            client.signOut() // force the account chooser each time
            googleLauncher.launch(client.signInIntent)
        } catch (t: Throwable) {
            failAttempt(SignInFailure.OTHER, t)
        }
    }

    /** Spinner on top, button dimmed (the drawable's disabled state) and not tappable. */
    private fun setGoogleLoading(loading: Boolean) {
        googleButton.isEnabled = !loading
        googleProgress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    /** Release the attempt: spinner off, button back. Called on success and on every failure. */
    private fun endAttempt() {
        attempt.end()
        setGoogleLoading(false)
    }

    /**
     * A sign-in attempt ended without signing in. The button is always restored and
     * the user sees one calm line (never the raw exception). The details go to the
     * log: the exception type and a status or error code only, nothing personal.
     */
    private fun failAttempt(kind: SignInFailure, cause: Throwable?, note: String? = null) {
        endAttempt()
        if (kind != SignInFailure.CANCELLED) {
            val code = when (cause) {
                is ApiException -> "api=${cause.statusCode}"
                is FirebaseAuthException -> "auth=${cause.errorCode}"
                else -> null
            }
            Log.w(TAG, "Google sign-in failed: kind=$kind type=${cause?.javaClass?.simpleName} code=$code note=$note")
        }
        status(
            getString(
                when (kind) {
                    SignInFailure.CANCELLED -> R.string.auth_signin_cancelled
                    SignInFailure.NO_INTERNET -> R.string.auth_no_internet
                    SignInFailure.OTHER -> R.string.auth_signin_failed
                }
            )
        )
    }

    /** default_web_client_id is generated by the Google Services plugin only when
     *  a Google OAuth client exists; look it up by name so the build never breaks. */
    private fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id != 0) context.getString(id) else null
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
        // A deletion that came due for ANOTHER account on this phone must not leave
        // its local data behind for this one.
        AccountDeletionWatcher.cleanupForOtherAccount(this, user.uid)
        PendingDeletion.clearNotice(this)
        // Signing in IS the cancellation of a scheduled deletion. The marker keeps
        // the user here until the check is over, even across a relaunch. It is set
        // only by a real sign-in on this phone; nothing cancels at app start.
        PendingDeletion.markSignInCheck(this, user.uid, method)
        runDeletionCheck(method)
    }

    /**
     * Read the account's deletion state from the server and act on it:
     *   none        -> carry on;
     *   in the future -> cancel it, tell the user in a dialog (OK required), carry on;
     *   already due -> say it can no longer be cancelled (OK required), carry on;
     * and if the read or the cancel fails, stay here with Retry. Entitlement is not
     * touched: the normal routing and paywall checks still decide what opens.
     */
    private fun runDeletionCheck(method: String) {
        val user = auth?.currentUser ?: return
        showDeletionCheck(getString(R.string.deletion_signin_checking), retry = false)
        PendingDeletion.fetchFresh(this, user.uid) { scheduledMs, ok ->
            runOnUiThread {
                if (!ok) {
                    showDeletionCheck(getString(R.string.deletion_signin_read_failed), retry = true)
                    return@runOnUiThread
                }
                when (DeletionFlowRules.signInCheck(scheduledMs, System.currentTimeMillis())) {
                    DeletionFlowRules.SignInCheck.PROCEED -> completeSignIn(method)
                    DeletionFlowRules.SignInCheck.DUE ->
                        showDeletionDialog(null, R.string.deletion_signin_due) { completeSignIn(method) }
                    DeletionFlowRules.SignInCheck.CANCEL ->
                        PendingDeletion.cancelOnSignIn(this) { result ->
                            runOnUiThread {
                                when (result) {
                                    PendingDeletion.CancelResult.CANCELLED ->
                                        showDeletionDialog(
                                            R.string.deletion_signin_cancelled_title,
                                            R.string.deletion_signin_cancelled
                                        ) { completeSignIn(method) }
                                    PendingDeletion.CancelResult.DUE ->
                                        showDeletionDialog(null, R.string.deletion_signin_due) { completeSignIn(method) }
                                    PendingDeletion.CancelResult.FAILED ->
                                        showDeletionCheck(getString(R.string.deletion_signin_cancel_failed), retry = true)
                                }
                            }
                        }
                }
            }
        }
    }

    /** One OK button that must be tapped; nothing continues until it is. */
    private fun showDeletionDialog(titleRes: Int?, messageRes: Int, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .apply { if (titleRes != null) setTitle(titleRes) }
            .setMessage(messageRes)
            .setCancelable(false)
            .setPositiveButton(R.string.account_delete_ok) { _, _ -> onOk() }
            .show()
    }

    private fun showDeletionCheck(message: String, retry: Boolean) {
        signInControls.visibility = View.GONE
        accountSection.visibility = View.GONE
        profileSection.visibility = View.GONE
        authProgressSection.visibility = View.GONE
        deletionCheckSection.visibility = View.VISIBLE
        deletionCheckText.text = message
        deletionCheckRetryButton.visibility = if (retry) View.VISIBLE else View.GONE
        deletionCheckSignOutButton.visibility = if (retry) View.VISIBLE else View.GONE
    }

    /** The deletion check is over: the rest of the sign-in carries on exactly as before. */
    private fun completeSignIn(method: String) {
        val user = auth?.currentUser ?: return
        PendingDeletion.clearSignInCheck(this)
        deletionCheckSection.visibility = View.GONE
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

    /** After deletion was scheduled and this phone signed out: when it will happen and how to cancel. */
    private fun showDeletionNotice() {
        val ms = PendingDeletion.noticeMs(this)
        if (ms == null || ms <= System.currentTimeMillis()) {
            if (ms != null) PendingDeletion.clearNotice(this)
            deletionNoticeText.visibility = View.GONE
            return
        }
        val whenText = java.text.DateFormat
            .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(ms))
        deletionNoticeText.text = getString(R.string.account_delete_scheduled_body, whenText)
        deletionNoticeText.visibility = View.VISIBLE
    }

    /** Account view for an already-authenticated user: email + sign out. */
    private fun showAccountState() {
        titleText.setText(R.string.auth_account_title)
        titleText.visibility = View.VISIBLE
        signInControls.visibility = View.GONE
        profileSection.visibility = View.GONE
        authProgressSection.visibility = View.GONE
        statusText.visibility = View.GONE
        accountSection.visibility = View.VISIBLE
        applySignOutLock()

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

    /** While sign-out is locked, the Sign out button is replaced by the explanation. */
    private fun applySignOutLock() {
        val locked = com.fliptle.app.SignOutGuard.isLocked(this)
        findViewById<View>(R.id.signOutButton).visibility = if (locked) View.GONE else View.VISIBLE
        findViewById<View>(R.id.signOutLockedText).visibility = if (locked) View.VISIBLE else View.GONE
    }

    /** Sign out (with the required warning) via the shared flow. */
    private fun signOut() = com.fliptle.app.SignOut.confirm(this)

    /**
     * After a sign-in ACTION here, reveal the optional name step. The field is
     * prefilled from the local cache, then best-effort from Firestore for a
     * returning/reinstalled user so they aren't re-prompted, and otherwise
     * suggests the Google account's display name (see [NamePrefill]).
     */
    private fun showProfileStep() {
        signInControls.visibility = View.GONE
        profileSection.visibility = View.VISIBLE

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
                    }
                    if (nameInput.text.isNullOrEmpty()) {
                        // Saved name first, then the Google display name. Only a
                        // suggestion: nothing is saved until Continue is tapped.
                        NamePrefill.choose(null, existing, user.displayName)?.let { nameInput.setText(it) }
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
     * Single "Continue" for the name step. The field is optional — a blank field
     * IS how you skip it, there's no separate skip button. The name has no real
     * validation beyond a length cap.
     */
    private fun continueProfile() {
        val user = auth?.currentUser
        val store = AuthStore(this)

        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            store.nameProvided = true
        } else if (name.length > NamePrefill.MAX_LENGTH) {
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

    private fun disableAll() {
        // googleSignInButton is an ImageButton, continueProfileButton a Button.
        // isEnabled is on View, so a widened cast disables both uniformly and
        // flips the Google button's state-list drawable to its dimmed variant.
        for (id in intArrayOf(R.id.googleSignInButton, R.id.continueProfileButton)) {
            findViewById<View>(id).isEnabled = false
        }
    }

    private fun status(message: String) {
        statusText.text = message
    }

    private companion object {
        const val TAG = "SignInActivity"
    }
}
