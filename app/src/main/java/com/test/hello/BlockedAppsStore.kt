package com.test.hello

import android.content.Context

/** Persists the set of package names the user has chosen to block. */
class BlockedAppsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("blocked_apps", Context.MODE_PRIVATE)

    fun get(): Set<String> =
        // Return a defensive copy; the set from prefs must not be mutated.
        HashSet(prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet())

    fun set(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED, HashSet(packages)).apply()
    }

    companion object {
        private const val KEY_BLOCKED = "blocked_packages"
    }
}
