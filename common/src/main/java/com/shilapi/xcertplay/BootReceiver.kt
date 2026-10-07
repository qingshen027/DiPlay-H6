package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the CarPlay host after boot when the user has enabled the startup option.
 *
 * Direct Boot aware: measured on the HAVAL H6 (Desay SV), the ROM finishes delivering
 * `LOCKED_BOOT_COMPLETED` within a few seconds of boot, but delays the normal `BOOT_COMPLETED` to
 * third-party apps by ~45s (the DiPlay process only started ~61s after boot). Listening to both
 * lets this process — and with it the resident [WirelessCarPlayService] and its periodic probe —
 * come up ~45s earlier, so CarPlay can appear as soon as the iPhone joins instead of waiting for
 * the delayed broadcast.
 *
 * While the device is locked, credential-encrypted storage is unavailable: preference reads are
 * skipped, only the resident service is pre-warmed (never the UI), and the service re-reads the
 * real settings once the user is unlocked.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "xcertplay-boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val appContext = context.applicationContext

        // Direct Boot window: SharedPreferences cannot be read, so just pre-warm the resident
        // service (it re-reads the real settings on a later probe, once unlocked).
        if (!BootUnlock.isUnlocked(appContext)) {
            Log.i(TAG, "locked boot received ($action): preferences unavailable -> pre-warming resident service")
            startResidentService(appContext)
            return
        }

        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(appContext)
        StartupDiagnosticSnapshot.received(appContext, launchEnabled)
        if (!launchEnabled) return

        // Start the long-lived foreground service synchronously. A BroadcastReceiver's
        // onReceive returns almost immediately, and the process it was started in can be
        // reclaimed by the system right after — so a postDelayed() scheduled here would never
        // fire on a real boot. Starting the foreground service keeps the process alive, and the
        // boot delay is applied inside the service instead.
        if (AirPlayPersistence.loadWirelessEnabled(appContext) &&
            AirPlayPersistence.loadWirelessAutoLaunch(appContext)
        ) {
            startResidentService(appContext)
        } else {
            val launch = Intent(appContext, DiPlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
            try {
                appContext.startActivity(launch)
                StartupDiagnosticSnapshot.launchResult(appContext)
            } catch (error: RuntimeException) {
                StartupDiagnosticSnapshot.launchResult(appContext, error)
                Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
            }
        }
    }

    private fun startResidentService(context: Context) {
        try {
            context.startForegroundService(
                Intent(context, WirelessCarPlayService::class.java)
                    .setAction(WirelessCarPlayService.ACTION_START),
            )
            if (BootUnlock.isUnlocked(context)) StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            if (BootUnlock.isUnlocked(context)) StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Boot auto-start could not launch WirelessCarPlayService", error)
        }
    }
}
