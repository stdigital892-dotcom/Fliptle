package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Lists launchable installed apps with multi-select checkboxes. Selected packages
 * are stored in [BlockedAppsStore]. Chrome (com.android.chrome) is a good target
 * for testing.
 */
class AppListActivity : AppCompatActivity() {

    private data class Entry(val label: String, val pkg: String)

    private val entries = ArrayList<Entry>()
    private lateinit var listView: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        listView = findViewById(R.id.appList)
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        loadApps()

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            entries.map { "${it.label}\n${it.pkg}" }
        )
        listView.adapter = adapter

        val blocked = BlockedAppsStore(this).get()
        entries.forEachIndexed { i, entry ->
            if (blocked.contains(entry.pkg)) listView.setItemChecked(i, true)
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
    }

    private fun loadApps() {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        for (resolveInfo in pm.queryIntentActivities(launcherIntent, 0)) {
            val pkg = resolveInfo.activityInfo.packageName
            if (pkg == packageName) continue // don't offer to block ourselves
            if (seen.add(pkg)) {
                entries.add(Entry(resolveInfo.loadLabel(pm).toString(), pkg))
            }
        }
        entries.sortBy { it.label.lowercase() }
    }

    private fun save() {
        // Blocked apps are a committed setting: frozen during the 3-day lock.
        if (!Commitment.guard(this)) return

        val selected = HashSet<String>()
        val checked = listView.checkedItemPositions
        for (i in entries.indices) {
            if (checked.get(i)) selected.add(entries[i].pkg)
        }
        val store = BlockedAppsStore(this)
        val changed = store.get() != selected
        store.set(selected)
        BlockingService.start(this) // make sure enforcement is running
        Toast.makeText(this, R.string.apps_saved, Toast.LENGTH_SHORT).show()
        // Changing what's blocked at review starts the next 3-day cycle.
        if (changed) Commitment.onChanged(this)
        finish()
    }
}
