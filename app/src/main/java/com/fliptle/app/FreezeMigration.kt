package com.fliptle.app

import android.content.Context
import com.fliptle.app.accessibility.SurfaceBlocklist

/**
 * One-time migration for the freeze wizard release.
 *
 *  • Deletes the old Reels/Shorts session storage (sessions, timer, cooldown,
 *    daily counters) — that feature is gone; surfaces are now plain blocking.
 *  • Folds the old separate Stories toggle into the Reels toggle.
 *  • A freeze that is already running keeps blocking exactly as before: its
 *    effective surface values (old default was ON) are written explicitly, and
 *    Reels stays on if either Reels or Stories was on, so nothing loosens
 *    mid-freeze.
 *  • Everyone else starts with both toggles OFF.
 */
object FreezeMigration {

    private const val FLAGS = "migrations"
    private const val FLAG_WIZARD = "freeze_wizard_v1"

    fun run(context: Context) {
        val ctx = context.applicationContext
        val flags = ctx.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
        if (flags.getBoolean(FLAG_WIZARD, false)) return

        val surfaces = ctx.getSharedPreferences(SurfaceBlocklist.PREFS, Context.MODE_PRIVATE)
        val running = FreezeStore(ctx).active
        // Old defaults were ON for all three surfaces.
        val oldReels = surfaces.getBoolean("reels", true)
        val oldStories = surfaces.getBoolean("stories", true)
        val oldShorts = surfaces.getBoolean("shorts", true)

        surfaces.edit()
            .remove("stories")
            .putBoolean("reels", running && (oldReels || oldStories))
            .putBoolean("shorts", running && oldShorts)
            .apply()

        ctx.deleteSharedPreferences("reels_allowance")
        flags.edit().putBoolean(FLAG_WIZARD, true).apply()
    }
}
