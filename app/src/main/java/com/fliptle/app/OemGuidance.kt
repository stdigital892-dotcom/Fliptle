package com.fliptle.app

import android.os.Build

/**
 * Detects OEM skins known for aggressively killing background apps and
 * Accessibility services to save battery (MIUI, ColorOS, FuntouchOS/OriginOS,
 * OxygenOS, One UI), so onboarding can show brand-specific instructions.
 *
 * The actual user-facing copy for each brand lives entirely in strings.xml
 * (see [titleRes]/[bodyRes]) — this object only does detection and maps a
 * brand to its string resources. No deep links to vendor-specific "autostart
 * manager" screens are used: those Activities/package names change across
 * OEM software versions and can silently fail, so guidance is text-only,
 * paired with the one standard, guaranteed-to-exist system screen
 * (Settings.ACTION_APPLICATION_DETAILS_SETTINGS) via the onboarding step's
 * own action button.
 */
object OemGuidance {

    enum class Brand(val titleRes: Int, val bodyRes: Int) {
        XIAOMI(R.string.ob_oem_xiaomi_title, R.string.ob_oem_xiaomi_body),
        OPPO(R.string.ob_oem_oppo_title, R.string.ob_oem_oppo_body),
        VIVO(R.string.ob_oem_vivo_title, R.string.ob_oem_vivo_body),
        ONEPLUS(R.string.ob_oem_oneplus_title, R.string.ob_oem_oneplus_body),
        SAMSUNG(R.string.ob_oem_samsung_title, R.string.ob_oem_samsung_body),
    }

    /** Null on any manufacturer not in the known-aggressive list — the
     *  onboarding step is skipped entirely in that case. */
    fun detect(manufacturer: String = Build.MANUFACTURER): Brand? {
        val m = manufacturer.lowercase()
        return when {
            m.contains("xiaomi") -> Brand.XIAOMI
            m.contains("oppo") -> Brand.OPPO
            m.contains("vivo") -> Brand.VIVO
            m.contains("oneplus") -> Brand.ONEPLUS
            m.contains("samsung") -> Brand.SAMSUNG
            else -> null
        }
    }
}
