package com.fliptle.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The one-way switch for adult-content blocking, and the "Day X" streak.
 *
 * This screen can only ever turn blocking ON. Once it is on there is no control
 * here — or anywhere else in the app — to turn it off; the screen becomes a
 * read-only streak display. Removing the blocking requires uninstalling, which is
 * itself behind the 5-day uninstall-request gate.
 */
class PornBlockActivity : AppCompatActivity() {

    private lateinit var store: PornBlockStore
    private lateinit var statusText: TextView
    private lateinit var bodyText: TextView
    private lateinit var enableSection: View
    private lateinit var enableButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_porn_block)
        store = PornBlockStore(this)

        statusText = findViewById(R.id.pornStatusText)
        bodyText = findViewById(R.id.pornBodyText)
        enableSection = findViewById(R.id.enableSection)
        enableButton = findViewById(R.id.enablePornBlockButton)

        val understand = findViewById<CheckBox>(R.id.understandCheck)
        understand.setOnCheckedChangeListener { _, checked -> enableButton.isEnabled = checked }
        enableButton.setOnClickListener { confirmEnable() }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Last chance to back out — after this there is no off switch. */
    private fun confirmEnable() {
        AlertDialog.Builder(this)
            .setTitle(R.string.porn_confirm_title)
            .setMessage(R.string.porn_confirm_body)
            .setNegativeButton(R.string.porn_confirm_cancel, null)
            .setPositiveButton(R.string.porn_confirm_ok) { _, _ ->
                store.enable()
                CloudState.backup(this) // preserve the streak across reinstall/sign-out
                render()
            }
            .show()
    }

    private fun render() {
        if (store.enabled) {
            // Permanently on: no toggle is rendered at all.
            enableSection.visibility = View.GONE
            statusText.text = getString(R.string.porn_day_counter, store.dayCount())
            bodyText.setText(R.string.porn_on_body)
        } else {
            enableSection.visibility = View.VISIBLE
            statusText.setText(R.string.porn_off_status)
            bodyText.setText(R.string.porn_off_body)
        }
    }
}
