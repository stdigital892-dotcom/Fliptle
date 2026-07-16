package com.fliptle.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Lists the apps auto-detected as browsers, with each one's status
 * (EXEMPT / BLOCKED), so detection accuracy can be verified.
 */
class BrowserListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser_list)

        listView = findViewById(R.id.browserList)
        emptyText = findViewById(R.id.emptyText)
        findViewById<Button>(R.id.rescanButton).setOnClickListener { refresh() }

        refresh()
    }

    private fun refresh() {
        // Make sure detection/auto-blocking is up to date before showing results.
        BrowserDetector.autoBlockInstalledBrowsers(this)

        val pm = packageManager
        val blocked = BlockedAppsStore(this).get()
        val rows = BrowserDetector.installedBrowsers(this).map { pkg ->
            val status = when {
                BrowserDetector.isExempt(pkg) -> "EXEMPT — stays usable"
                blocked.contains(pkg) -> "BLOCKED"
                else -> "detected (not blocked)"
            }
            "${labelOf(pm, pkg)}\n$pkg — $status"
        }

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        emptyText.text = if (rows.isEmpty()) getString(R.string.no_browsers_found) else ""
    }

    private fun labelOf(pm: PackageManager, pkg: String): String =
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
}
