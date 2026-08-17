package com.fliptle.app.auth

import android.content.Context
import java.util.UUID

/**
 * Local auth/install state. The install ID is generated once per fresh install
 * and wiped on uninstall (SharedPreferences are cleared), so a new ID appearing
 * for a known phone number is the signal used to detect a reinstall.
 */
class AuthStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("auth", Context.MODE_PRIVATE)

    /** Stable per-install UUID; created lazily on first access after a fresh install. */
    fun installId(): String {
        var id = prefs.getString(KEY_INSTALL_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        }
        return id
    }

    var signedInPhone: String?
        get() = prefs.getString(KEY_PHONE, null)
        set(value) = prefs.edit().putString(KEY_PHONE, value).apply()

    /**
     * Whether the mandatory parent phone number has been provided for the current
     * signed-in user. Set optimistically on a valid entry (so an offline user is
     * never trapped) and cleared on a fresh install (prefs wiped). Gated on by
     * [com.fliptle.app.PhoneGate] to force the phone screen after any sign-in.
     */
    var parentPhoneProvided: Boolean
        get() = prefs.getBoolean(KEY_PHONE_PROVIDED, false)
        set(value) = prefs.edit().putBoolean(KEY_PHONE_PROVIDED, value).apply()

    companion object {
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_PHONE = "phone"
        private const val KEY_PHONE_PROVIDED = "phone_provided"
    }
}
