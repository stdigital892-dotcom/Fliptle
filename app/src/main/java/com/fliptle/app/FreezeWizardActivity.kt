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
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.fliptle.app.accessibility.SurfaceBlocklist
import com.fliptle.app.accessibility.SurfaceDebug

/**
 * The freeze setup wizard, one step at a time:
 *   a) blocked apps  b) blocked domains  c) Reels / Shorts  d) ready.
 *
 * Steps a-c each end in Save, which writes only to the DRAFT
 * ([FreezeDraftStore]) — nothing is enforced and nothing is locked. Leaving
 * early keeps what was saved. Only the final button calls [Commitment.commit],
 * which applies the draft and starts the 3-day lock.
 */
class FreezeWizardActivity : AppCompatActivity() {

    private data class AppEntry(val label: String, val pkg: String, val icon: Drawable?)

    private lateinit var draft: FreezeDraftStore

    private var step = STEP_APPS

    // Working copies for the step on screen; persisted to the draft only on Save.
    private val appEntries = ArrayList<AppEntry>()
    private val checkedApps = HashSet<String>()
    private val userDomains = HashSet<String>()
    private var domainRows: List<String> = emptyList()

    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleText: TextView
    private lateinit var bodyText: TextView
    private lateinit var appList: ListView
    private lateinit var domainsStep: View
    private lateinit var domainInput: EditText
    private lateinit var domainList: ListView
    private lateinit var surfacesStep: View
    private lateinit var reelsSwitch: SwitchCompat
    private lateinit var shortsSwitch: SwitchCompat
    private lateinit var readyStep: View
    private lateinit var summaryText: TextView
    private lateinit var readyHint: TextView
    private lateinit var primaryButton: Button
    private lateinit var backButton: Button
    private lateinit var devSection: View
    private lateinit var debugLog: TextView

    private lateinit var appAdapter: AppAdapter
    private lateinit var domainAdapter: DomainAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Permissions.gate(this)) return
        // Nothing can be edited while the 3-day lock runs; the Freeze screen
        // does not offer the wizard then, but never rely on that alone.
        if (!Commitment.canEdit(this)) {
            finish()
            return
        }
        setContentView(R.layout.activity_freeze_wizard)

        draft = FreezeDraftStore(this)
        draft.ensureInitialized()

        progressText = findViewById(R.id.wizardProgressText)
        progressBar = findViewById(R.id.wizardProgressBar)
        titleText = findViewById(R.id.wizardTitle)
        bodyText = findViewById(R.id.wizardBody)
        appList = findViewById(R.id.wizardAppList)
        domainsStep = findViewById(R.id.wizardDomainsStep)
        domainInput = findViewById(R.id.wizardDomainInput)
        domainList = findViewById(R.id.wizardDomainList)
        surfacesStep = findViewById(R.id.wizardSurfacesStep)
        reelsSwitch = findViewById(R.id.wizardReelsSwitch)
        shortsSwitch = findViewById(R.id.wizardShortsSwitch)
        readyStep = findViewById(R.id.wizardReadyStep)
        summaryText = findViewById(R.id.wizardSummary)
        readyHint = findViewById(R.id.wizardReadyHint)
        primaryButton = findViewById(R.id.wizardPrimaryButton)
        backButton = findViewById(R.id.wizardBackButton)
        devSection = findViewById(R.id.wizardDevSection)
        debugLog = findViewById(R.id.debugLogText)

        // Pre-fill every step from the draft (seeded from the live selections).
        checkedApps.addAll(draft.apps)
        userDomains.addAll(draft.domains)
        reelsSwitch.isChecked = draft.reels
        shortsSwitch.isChecked = draft.shorts

        loadApps()
        appAdapter = AppAdapter()
        appList.adapter = appAdapter
        appList.setOnItemClickListener { _, _, position, _ ->
            val pkg = appEntries[position].pkg
            if (!checkedApps.add(pkg)) checkedApps.remove(pkg)
            appAdapter.notifyDataSetChanged()
        }

        domainAdapter = DomainAdapter()
        domainList.adapter = domainAdapter
        domainList.setOnItemClickListener { _, _, position, _ ->
            userDomains.remove(domainRows[position])
            refreshDomains()
        }
        findViewById<Button>(R.id.wizardAddDomainButton).setOnClickListener { addDomain() }

        setupDevSection()

        primaryButton.setOnClickListener { onPrimary() }
        backButton.setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        refreshDomains()
        render()
    }

    override fun onResume() {
        super.onResume()
        Permissions.gate(this)
    }

    // ---- navigation ----

    private fun goBack() {
        if (step > STEP_APPS) {
            step--
            render()
        } else {
            finish() // leaving early: nothing is locked, saved steps stay as the draft
        }
    }

    private fun onPrimary() {
        when (step) {
            STEP_APPS -> {
                draft.saveApps(checkedApps)
                step = STEP_DOMAINS
                render()
            }
            STEP_DOMAINS -> {
                draft.saveDomains(userDomains)
                step = STEP_SURFACES
                render()
            }
            STEP_SURFACES -> {
                draft.saveSurfaces(reelsSwitch.isChecked, shortsSwitch.isChecked)
                step = STEP_READY
                render()
            }
            STEP_READY -> start()
        }
    }

    private fun start() {
        when (Commitment.commit(this)) {
            Commitment.Result.STARTED -> {
                Toast.makeText(this, R.string.wizard_started, Toast.LENGTH_LONG).show()
                finish()
            }
            Commitment.Result.RESTARTED -> {
                Toast.makeText(
                    this, getString(R.string.commit_restarted, FreezeStore(this).cycleDays()),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
            Commitment.Result.UNCHANGED -> {
                Toast.makeText(this, R.string.wizard_no_changes, Toast.LENGTH_LONG).show()
                finish()
            }
            Commitment.Result.EMPTY -> {
                Toast.makeText(this, R.string.wizard_empty_hint, Toast.LENGTH_LONG).show()
                render()
            }
            Commitment.Result.LOCKED -> finish()
        }
    }

    // ---- rendering ----

    private fun render() {
        progressText.text = getString(R.string.ob_progress_format, step + 1, STEP_COUNT)
        progressBar.progress = ((step + 1) * 100) / STEP_COUNT

        appList.visibility = if (step == STEP_APPS) View.VISIBLE else View.GONE
        domainsStep.visibility = if (step == STEP_DOMAINS) View.VISIBLE else View.GONE
        surfacesStep.visibility = if (step == STEP_SURFACES) View.VISIBLE else View.GONE
        readyStep.visibility = if (step == STEP_READY) View.VISIBLE else View.GONE
        primaryButton.isEnabled = true

        when (step) {
            STEP_APPS -> {
                titleText.setText(R.string.wizard_apps_title)
                bodyText.setText(R.string.wizard_apps_body)
                primaryButton.setText(R.string.save)
            }
            STEP_DOMAINS -> {
                titleText.setText(R.string.wizard_domains_title)
                bodyText.setText(R.string.wizard_domains_body)
                primaryButton.setText(R.string.save)
            }
            STEP_SURFACES -> {
                titleText.setText(R.string.wizard_surfaces_title)
                bodyText.setText(R.string.wizard_surfaces_body)
                primaryButton.setText(R.string.save)
            }
            STEP_READY -> renderReady()
        }
    }

    private fun renderReady() {
        titleText.setText(R.string.wizard_ready_title)
        bodyText.setText(R.string.wizard_ready_body)
        primaryButton.setText(R.string.freeze_start)

        summaryText.text = listOf(
            getString(R.string.wizard_summary_apps, draft.apps.size),
            getString(R.string.wizard_summary_domains, draft.domains.size),
            getString(R.string.wizard_summary_reels, onOff(draft.reels)),
            getString(R.string.wizard_summary_shorts, onOff(draft.shorts))
        ).joinToString("\n")

        val freeze = FreezeStore(this)
        readyHint.text = when {
            draft.itemCount() < 1 -> getString(R.string.wizard_empty_hint)
            freeze.active && !draft.differsFromLive() -> getString(R.string.wizard_no_changes)
            else -> ""
        }
        readyHint.visibility = if (readyHint.text.isEmpty()) View.GONE else View.VISIBLE
        // At least one item blocked, and (at a review) something actually changed.
        primaryButton.isEnabled = Commitment.canCommit(this)
    }

    private fun onOff(on: Boolean) =
        getString(if (on) R.string.wizard_on else R.string.wizard_off)

    // ---- step a: apps ----

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
                appEntries.add(AppEntry(label, pkg, icon))
            }
        }
        appEntries.sortBy { it.label.lowercase() }
    }

    private inner class AppAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@FreezeWizardActivity)
        override fun getCount(): Int = appEntries.size
        override fun getItem(i: Int): Any = appEntries[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.item_app_row, parent, false)
            val e = appEntries[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(e.icon)
            view.findViewById<TextView>(R.id.appLabel).text = e.label
            view.findViewById<CheckBox>(R.id.appCheck).isChecked = checkedApps.contains(e.pkg)
            return view
        }
    }

    // ---- step b: domains ----

    private fun addDomain() {
        val text = domainInput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.enter_domain, Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = DomainBlocklist(this).normalize(text)
        if (normalized.isNotEmpty()) userDomains.add(normalized)
        domainInput.text.clear()
        refreshDomains()
    }

    private fun refreshDomains() {
        domainRows = userDomains.sorted()
        domainAdapter.notifyDataSetChanged()
    }

    private inner class DomainAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@FreezeWizardActivity)
        override fun getCount(): Int = domainRows.size
        override fun getItem(i: Int): Any = domainRows[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.item_domain_row, parent, false)
            val domain = domainRows[position]
            view.findViewById<TextView>(R.id.domainName).text = domain
            view.findViewById<TextView>(R.id.domainHint).setText(R.string.wizard_domain_tap_remove)
            return view
        }
    }

    // ---- step c: developer-only surface diagnostics ----

    private fun setupDevSection() {
        if (!DevMode.enabled(this)) {
            devSection.visibility = View.GONE
            return
        }
        devSection.visibility = View.VISIBLE
        val store = SurfaceBlocklist(this)
        val debug = findViewById<CheckBox>(R.id.debugCheck)
        debug.isChecked = store.debug
        debug.setOnCheckedChangeListener { _, v -> store.debug = v }
        findViewById<Button>(R.id.refreshLogButton).setOnClickListener { renderLog() }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            SurfaceDebug(this).clear()
            renderLog()
        }
        renderLog()
    }

    private fun renderLog() {
        val log = SurfaceDebug(this).get()
        debugLog.text = log.ifEmpty { getString(R.string.surfaces_debug_empty) }
    }

    companion object {
        private const val STEP_APPS = 0
        private const val STEP_DOMAINS = 1
        private const val STEP_SURFACES = 2
        private const val STEP_READY = 3
        private const val STEP_COUNT = 4
    }
}
