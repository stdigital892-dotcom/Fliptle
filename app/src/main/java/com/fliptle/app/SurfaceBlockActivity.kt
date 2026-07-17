package com.fliptle.app

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.accessibility.SurfaceBlocklist
import com.fliptle.app.accessibility.SurfaceDebug

/**
 * Toggle individual in-app surfaces (Instagram Reels/Stories, YouTube Shorts).
 * Each is independent, so you can block Reels but keep the feed, block Shorts but
 * keep normal YouTube, etc. Enforcement requires the Accessibility service on.
 *
 * Debug mode logs the actual view ids / content descriptions matched (without
 * blocking), so over-matching can be diagnosed on-device.
 */
class SurfaceBlockActivity : AppCompatActivity() {

    private lateinit var store: SurfaceBlocklist
    private lateinit var debugLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_surface_block)
        store = SurfaceBlocklist(this)
        debugLog = findViewById(R.id.debugLogText)

        val reels = findViewById<CheckBox>(R.id.reelsCheck)
        val stories = findViewById<CheckBox>(R.id.storiesCheck)
        val shorts = findViewById<CheckBox>(R.id.shortsCheck)
        val debug = findViewById<CheckBox>(R.id.debugCheck)

        reels.isChecked = store.reels
        stories.isChecked = store.stories
        shorts.isChecked = store.shorts
        debug.isChecked = store.debug

        reels.setOnCheckedChangeListener { _, v -> store.reels = v }
        stories.setOnCheckedChangeListener { _, v -> store.stories = v }
        shorts.setOnCheckedChangeListener { _, v -> store.shorts = v }
        debug.setOnCheckedChangeListener { _, v -> store.debug = v }

        findViewById<Button>(R.id.refreshLogButton).setOnClickListener { renderLog() }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            SurfaceDebug(this).clear()
            renderLog()
        }
    }

    override fun onResume() {
        super.onResume()
        renderLog()
    }

    private fun renderLog() {
        val log = SurfaceDebug(this).get()
        debugLog.text = log.ifEmpty { getString(R.string.surfaces_debug_empty) }
    }
}
