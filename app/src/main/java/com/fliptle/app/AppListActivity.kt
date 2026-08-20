package com.fliptle.app

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Chooser for the Blocked apps list. Each row renders the app's real icon and
 * human-readable label — package names are never shown in the UI. Tapping a row
 * toggles its checkbox; Save writes the selection and starts enforcement.
 */
class AppListActivity : AppCompatActivity() {

    private data class Entry(val label: String, val pkg: String, val icon: Drawable?)

    private val entries = ArrayList<Entry>()
    private val checked = HashSet<String>()
    private lateinit var listView: ListView
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        listView = findViewById(R.id.appList)
        loadApps()
        checked.addAll(BlockedAppsStore(this).get())

        adapter = AppAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = entries[position].pkg
            if (!checked.add(pkg)) checked.remove(pkg)
            adapter.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
    }

    private fun loadApps() {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        for (resolveInfo in pm.queryIntentActivities(launcherIntent, 0)) {
            val pkg = resolveInfo.activityInfo.packageName
            if (pkg == packageName) continue // never offer ourselves
            if (seen.add(pkg)) {
                val label = resolveInfo.loadLabel(pm).toString()
                val icon = try { resolveInfo.loadIcon(pm) } catch (_: Throwable) { null }
                entries.add(Entry(label, pkg, icon))
            }
        }
        entries.sortBy { it.label.lowercase() }
    }

    private fun save() {
        // Blocked apps are a committed setting: frozen during the 3-day lock.
        if (!Commitment.guard(this)) return
        val store = BlockedAppsStore(this)
        val selected = HashSet(checked)
        val changed = store.get() != selected
        store.set(selected)
        BlockingService.start(this)
        Toast.makeText(this, R.string.apps_saved, Toast.LENGTH_SHORT).show()
        // Any real change at review starts the next 3-day cycle (backs up too).
        if (changed) Commitment.onChanged(this) else CloudState.backup(this)
        finish()
    }

    private inner class AppAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@AppListActivity)
        override fun getCount(): Int = entries.size
        override fun getItem(i: Int): Any = entries[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.item_app_row, parent, false)
            val e = entries[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(e.icon)
            view.findViewById<TextView>(R.id.appLabel).text = e.label
            view.findViewById<CheckBox>(R.id.appCheck).isChecked = checked.contains(e.pkg)
            return view
        }
    }
}
