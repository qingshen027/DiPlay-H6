package com.shilapi.xcertplay

import android.content.Context
import android.os.UserManager

/**
 * Direct Boot ("locked") state helper.
 *
 * Measured on the HAVAL H6 / Desay SV head unit: the ROM finishes delivering
 * `LOCKED_BOOT_COMPLETED` within a few seconds of boot, but delays the normal
 * `BOOT_COMPLETED` to third-party apps by ~45s (the DiPlay process only started ~61s after boot).
 * [BootReceiver] is therefore `directBootAware` and listens to the locked broadcast so the process
 * — and with it the resident [WirelessCarPlayService] and its periodic probe — comes up ~45s
 * earlier.
 *
 * While the device is locked, credential-encrypted (CE) storage is unavailable, so reading
 * SharedPreferences is unsafe. Every boot-time preference read must be guarded by [isUnlocked]
 * and fall back to defaults until the user is actually unlocked.
 */
internal object BootUnlock {

    /**
     * True when credential-encrypted storage (SharedPreferences, the saved hotspot credentials…)
     * can be read. Returns true defensively if the state cannot be determined, so the normal
     * unlocked path is never degraded.
     */
    fun isUnlocked(context: Context): Boolean =
        runCatching {
            val manager = context.getSystemService(UserManager::class.java)
            manager?.isUserUnlocked ?: true
        }.getOrDefault(true)
}
