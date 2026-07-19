package com.fliptle.app

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.AppCompatEditText

/**
 * EditText that blocks paste / autofill / bulk insertion and the selection
 * context menu, so only genuine per-character typing can enter text. Used by the
 * manual typing gate.
 */
class NoPasteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    init {
        isLongClickable = false
        setTextIsSelectable(false)
        // Kill both the selection and the insertion (cursor "Paste") action modes.
        customSelectionActionModeCallback = BLOCKING_CALLBACK
        customInsertionActionModeCallback = BLOCKING_CALLBACK
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        // Block paste / paste-as-plain-text / autofill; allow nothing that inserts.
        return when (id) {
            android.R.id.paste, android.R.id.pasteAsPlainText, android.R.id.autofill -> true
            else -> super.onTextContextMenuItem(id)
        }
    }

    override fun performLongClick(): Boolean = true // swallow long-press menu

    private companion object {
        val BLOCKING_CALLBACK = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
    }
}
