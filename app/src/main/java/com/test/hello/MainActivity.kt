package com.test.hello

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.test.hello.accessibility.AccessibilityDisclosureActivity
import com.test.hello.auth.FirebaseGate
import com.test.hello.auth.PhoneAuthActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    // Launches the system VPN consent dialog; on approval, starts the filter.
    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) DnsVpnService.start(this)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.grantButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.grantOverlayButton).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<Button>(R.id.openBlockButton).setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }
        findViewById<Button>(R.id.openBrowsersButton).setOnClickListener {
            startActivity(Intent(this, BrowserListActivity::class.java))
        }
        findViewById<Button>(R.id.openDomainsButton).setOnClickListener {
            startActivity(Intent(this, DomainListActivity::class.java))
        }
        findViewById<Button>(R.id.enableVpnButton).setOnClickListener { enableVpnFilter() }
        findViewById<Button>(R.id.disableVpnButton).setOnClickListener {
            DnsVpnService.stop(this)
        }
        findViewById<Button>(R.id.openA11yButton).setOnClickListener {
            startActivity(Intent(this, AccessibilityDisclosureActivity::class.java))
        }
        findViewById<Button>(R.id.openBlocklistButton).setOnClickListener {
            startActivity(Intent(this, BlocklistStatusActivity::class.java))
        }
        findViewById<Button>(R.id.openAuthButton).setOnClickListener {
            startActivity(Intent(this, PhoneAuthActivity::class.java))
        }
        findViewById<Button>(R.id.openFreezeButton).setOnClickListener {
            startActivity(Intent(this, FreezeActivity::class.java))
        }

        requestNotificationPermissionIfNeeded()
        // Auto-block any already-installed browsers (except Chrome). New installs
        // are handled live by PackageInstallReceiver.
        BrowserDetector.autoBlockInstalledBrowsers(this)
        BlockingService.start(this)

        // Warm the shared blocklists and schedule the weekly refresh.
        AdultBlocklist.ensureLoaded(this)
        KeywordBlocklist.ensureLoaded(this)
        BlocklistUpdateWorker.schedule(this)

        initRemoteConfigIfAvailable()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun updateStatus() {
        if (!ForegroundApp.hasUsageAccess(this)) {
            statusText.text = getString(R.string.permission_needed)
            return
        }
        val pkg = ForegroundApp.current(this)
        statusText.text = "${getString(R.string.foreground_label)}\n${pkg ?: "(unknown)"}"
    }

    private fun enableVpnFilter() {
        // Returns an intent if the user must consent; null if already authorized.
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsentLauncher.launch(consent) else DnsVpnService.start(this)
    }

    private fun initRemoteConfigIfAvailable() {
        if (!FirebaseGate.isAvailable(this)) return
        try {
            val rc = com.google.firebase.remoteconfig.FirebaseRemoteConfig.getInstance()
            rc.setDefaultsAsync(R.xml.remote_config_defaults)
            rc.fetchAndActivate()
        } catch (_: Throwable) {
            // Remote Config unavailable; the in-app default threshold (1) applies.
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 2_000L
        private const val REQ_NOTIFICATIONS = 1001
    }
}
