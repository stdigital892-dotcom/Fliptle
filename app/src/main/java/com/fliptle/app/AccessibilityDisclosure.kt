package com.fliptle.app

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * The one Accessibility disclosure shown wherever the app is about to send the
 * user to Accessibility settings. Reuses ob_a11y_title/body/action/decline
 * verbatim -- the same strings OnboardingActivity's own Accessibility step
 * shows inline -- so the text exists in exactly one place.
 *
 * Settings is opened ONLY from [onAgree], never before it: callers must do
 * the ACTION_ACCESSIBILITY_SETTINGS startActivity (and any cooldown/guard
 * suppression) inside that callback, not before calling [show]. "No thanks"
 * dismisses the dialog and opens nothing.
 */
object AccessibilityDisclosure {
    fun show(context: Context, onAgree: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.ob_a11y_title)
            .setMessage(R.string.ob_a11y_body)
            .setPositiveButton(R.string.ob_a11y_action) { _, _ -> onAgree() }
            .setNegativeButton(R.string.ob_a11y_decline, null)
            .show()
    }
}
