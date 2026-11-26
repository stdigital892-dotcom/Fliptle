package com.fliptle.app

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * Shown instead of signing out while [SignOutGuard.isLocked] is true. Exactly
 * two ways forward -- no bare "sign out anyway" or dismiss-and-sign-out
 * option. Dismissing (back/outside tap) just closes the sheet; it never
 * signs the user out.
 */
object SignOutLockSheet {
    fun show(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun matchWrap(topMargin: Int = 0) =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { this.topMargin = topMargin }

        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        root.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.sign_out_locked_sheet_title)
                textSize = 18f
                gravity = Gravity.START
            },
            matchWrap()
        )
        root.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.sign_out_locked_sheet_body)
                textSize = 14f
            },
            matchWrap(dp(8)).apply { bottomMargin = dp(20) }
        )
        root.addView(
            MaterialButton(activity).apply {
                text = activity.getString(R.string.sign_out_locked_exit_option)
                setOnClickListener {
                    dialog.dismiss()
                    activity.startActivity(Intent(activity, UninstallRequestActivity::class.java))
                }
            },
            matchWrap()
        )
        root.addView(
            MaterialButton(activity).apply {
                text = activity.getString(R.string.sign_out_locked_delete_option)
                setOnClickListener {
                    dialog.dismiss()
                    activity.startActivity(Intent(activity, AccountDeletionActivity::class.java))
                }
            },
            matchWrap(dp(12))
        )

        dialog.setContentView(root)
        dialog.show()
    }
}
