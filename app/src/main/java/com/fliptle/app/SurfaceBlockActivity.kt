package com.fliptle.app

import android.os.Bundle
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.accessibility.SurfaceBlocklist

/**
 * Toggle individual in-app surfaces (Instagram Reels/Stories, YouTube Shorts).
 * Each is independent, so you can block Reels but keep the feed, block Shorts but
 * keep normal YouTube, etc. Enforcement requires the Accessibility service on.
 */
class SurfaceBlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_surface_block)
        val store = SurfaceBlocklist(this)

        val reels = findViewById<CheckBox>(R.id.reelsCheck)
        val stories = findViewById<CheckBox>(R.id.storiesCheck)
        val shorts = findViewById<CheckBox>(R.id.shortsCheck)

        reels.isChecked = store.reels
        stories.isChecked = store.stories
        shorts.isChecked = store.shorts

        reels.setOnCheckedChangeListener { _, v -> store.reels = v }
        stories.setOnCheckedChangeListener { _, v -> store.stories = v }
        shorts.setOnCheckedChangeListener { _, v -> store.shorts = v }
    }
}
