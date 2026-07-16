package com.fliptle.app.accessibility

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.R

/**
 * Prominent in-app disclosure for the Accessibility permission (Play policy).
 * Explains exactly what is accessed, why, and that no data leaves the device,
 * before offering to open system Accessibility settings.
 */
class AccessibilityDisclosureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accessibility_disclosure)

        findViewById<Button>(R.id.openA11ySettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val status = if (isServiceEnabled()) R.string.a11y_status_on else R.string.a11y_status_off
        findViewById<TextView>(R.id.a11yStatusText).setText(status)
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${UrlBlockAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
