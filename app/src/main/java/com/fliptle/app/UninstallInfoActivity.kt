package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.auth.AuthStore

/**
 * Unskippable "how to uninstall this app properly" explainer, shown once per
 * account between sign-in and Home. The "Got it" button is disabled until the
 * user has scrolled to the very end (the caution must be seen, not skipped).
 *
 * Once-only enforced by [AuthStore.uninstallInfoSeen], which is:
 *   - set locally the moment the user taps Got it (so this session is done),
 *   - persisted to Firestore under the account via [CloudState.backup],
 *   - restored on any future sign-in via [CloudState.restore].
 * That means the same account never sees this screen twice, on any device,
 * while a different account still sees it once — as designed.
 *
 * Back is intentionally disabled: this screen is a required step, not a modal.
 */
class UninstallInfoActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var hint: TextView
    private lateinit var button: Button
    private var reachedBottom = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uninstall_info)

        scroll = findViewById(R.id.uninstallInfoScroll)
        hint = findViewById(R.id.scrollHintText)
        button = findViewById(R.id.gotItButton)

        button.setOnClickListener { proceed() }

        // Enable Got it once the user scrolls to (or past) the bottom.
        scroll.viewTreeObserver.addOnScrollChangedListener { checkBottom() }
        scroll.post { checkBottom() } // handle small screens where all content is already visible
    }

    private fun checkBottom() {
        if (reachedBottom) return
        val child = scroll.getChildAt(0) ?: return
        val diff = child.bottom - (scroll.height + scroll.scrollY)
        if (diff <= 4) {
            reachedBottom = true
            button.isEnabled = true
            hint.visibility = View.GONE
        }
    }

    private fun proceed() {
        // Optimistic local flag so the router doesn't re-show us on next launch
        // even if the cloud write is delayed / offline.
        AuthStore(this).uninstallInfoSeen = true
        CloudState.backup(this) // persists the flag under the account UID
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    /** Unskippable: back is not allowed. */
    @Deprecated("Overridden to disable back navigation.")
    override fun onBackPressed() {
        // no-op — the user must acknowledge before continuing
    }
}
