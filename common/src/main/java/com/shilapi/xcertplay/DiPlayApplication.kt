package com.shilapi.xcertplay

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Custom Application used as a boot-strap hook for the wireless CarPlay listener.
 *
 * The Haval H6 / Desay SV customized ROM intercepts the `BOOT_COMPLETED` broadcast for
 * third-party apps, so [BootReceiver] never fires on this head-unit. The ROM still
 * launches the DiPlay process on its own at boot (observed: process alive after reboot,
 * but no boot broadcast received), so we start the resident foreground service from
 * [onCreate] instead — this path is guaranteed to run whenever the process is created,
 * regardless of how it was started (boot or user opening the app).
 */
class DiPlayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        applyVerboseLogging()
        // Direct Boot pre-warm: this process can be started by LOCKED_BOOT_COMPLETED while the
        // device is still locked, and credential-encrypted SharedPreferences cannot be read in
        // that window. Start the resident service anyway so the listener is up ~45s earlier than
        // the delayed BOOT_COMPLETED would allow; the service re-reads the real preferences as
        // soon as the user is unlocked.
        val unlocked = BootUnlock.isUnlocked(this)
        val wanted = if (unlocked) {
            AirPlayPersistence.loadAutoStartOnBoot(this)
                && AirPlayPersistence.loadWirelessEnabled(this)
                && AirPlayPersistence.loadWirelessAutoLaunch(this)
        } else {
            true
        }
        if (wanted) {
            Diagnostics.i(TAG, "Application.onCreate: auto-starting resident wireless service (unlocked=$unlocked)")
            val intent = Intent(this, WirelessCarPlayService::class.java).apply {
                action = WirelessCarPlayService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    /**
     * Pushes the verbose-logging decision into the shared gate: `shared` cannot read the
     * preference itself, and `BuildConfig` is unavailable in this library module, so the
     * debuggable flag is read from PackageManager.
     */
    private fun applyVerboseLogging() {
        val debuggable =
            applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val enabled = debuggable || runCatching { AirPlayPersistence.loadDebugLogsEnabled(this) }
            .getOrDefault(false)
        Diagnostics.verboseToLogcat = enabled
    }

    companion object {
        private const val TAG = "xcertplay-boot"
    }
}
