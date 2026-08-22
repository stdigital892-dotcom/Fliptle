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
     * Whether the parent phone step has been handled for the current signed-in
     * user — either a number was entered or it was skipped. Set optimistically on
     * a valid entry (so an offline user is never trapped) and cleared on a fresh
     * install (prefs wiped). Read by [com.fliptle.app.PhoneGate], whose
     * enforcement is currently off (the phone number is optional for now).
     */
    var parentPhoneProvided: Boolean
        get() = prefs.getBoolean(KEY_PHONE_PROVIDED, false)
        set(value) = prefs.edit().putBoolean(KEY_PHONE_PROVIDED, value).apply()

    /**
     * Whether the once-per-account "how to uninstall" information screen has
     * already been shown. Local mirror of a Firestore flag under this account, so
     * signing in again on any device skips it. Set on "Got it" (device + cloud);
     * restored from Firestore on sign-in by [com.fliptle.app.CloudState].
     */
    var uninstallInfoSeen: Boolean
        get() = prefs.getBoolean(KEY_UNINSTALL_INFO_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_UNINSTALL_INFO_SEEN, value).apply()

    companion object {
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_PHONE = "phone"
        private const val KEY_PHONE_PROVIDED = "phone_provided"
        private const val KEY_UNINSTALL_INFO_SEEN = "uninstall_info_seen"
    }
}
