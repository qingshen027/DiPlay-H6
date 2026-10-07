package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.orchestration.WirelessBluetoothState
import java.lang.reflect.InvocationTargetException

/**
 * HAVAL H6: turning on "open after the car starts" must also switch the head unit's own Bluetooth
 * and Wi-Fi hotspot on, otherwise the iPhone can never join and the resident listener waits
 * forever (it only launches CarPlay when BT + AP are both present).
 *
 * Everything here uses plain-app capabilities — the app is distributed to a 1000+ owner community
 * with no root and no privileged permissions:
 *
 * - Bluetooth: [BluetoothAdapter.enable] (BLUETOOTH_ADMIN, normal protection). Verified on the H6:
 *   `dumpsys bluetooth_manager` records "Enabled by com.shihab.diplay.hudtest".
 * - Hotspot: [WifiManager.setWifiApEnabled] by reflection (CHANGE_WIFI_STATE, normal protection).
 *   `IConnectivityManager.startTethering` is only kept as a fallback because AOSP gates it behind
 *   the signature-level TETHER_PRIVILEGED permission a normal app can never hold.
 * - The H6 shares its single wlan0 between station mode and the soft AP. While Wi-Fi is connected
 *   to a router the firmware accepts the start call and silently does nothing, so if the first
 *   attempt fails we switch station mode off (setWifiEnabled) and try once more. That is what the
 *   car's own Settings app does, and it is the fix for "auto-launch never fires when the car is on
 *   Wi-Fi".
 *
 * Reading the saved AP configuration (getWifiApConfiguration) is blocked on this firmware, but
 * passing a null config to setWifiApEnabled lets the system use its own saved one, so no read is
 * needed here.
 *
 * Attempts are rate-limited and capped: the OEM `com.desay_svautomotive.btservice` is free to
 * switch Bluetooth back off, and we must not end up in an endless toggle fight with it.
 */
object CarRadioBringUp {
    private const val TAG = "xcertplay-radio"
    private const val RETRY_INTERVAL_MS = 15_000L
    /** Stop after ~2 minutes of retries; a reboot (new process) resets the counter. */
    private const val MAX_ATTEMPTS = 8
    private const val RETRY_TIMEOUT_MS = 12_000L
    private const val STA_OFF_TIMEOUT_MS = 6_000L
    private const val SETTLE_POLL_MS = 250L
    private const val WIFI_AP_STATE_ENABLED = 13

    @Volatile private var inFlight = false
    @Volatile private var attempts = 0
    @Volatile private var lastAttemptMillis = 0L

    /** Called when the listener is stopped so a later start retries from scratch. */
    fun reset() {
        attempts = 0
        lastAttemptMillis = 0L
    }

    /** Fire-and-forget: returns immediately, the work (and any waiting) happens off the main thread. */
    fun ensure(context: Context) {
        if (inFlight) return
        if (attempts >= MAX_ATTEMPTS) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttemptMillis < RETRY_INTERVAL_MS) return
        lastAttemptMillis = now
        attempts++
        inFlight = true
        Thread {
            try {
                run(context.applicationContext)
            } finally {
                inFlight = false
            }
        }.start()
    }

    private fun run(context: Context) {
        val btOn = ensureBluetooth()
        val apOn = ensureHotspot(context)
        Diagnostics.d(TAG, "ensure: attempt=$attempts bt=$btOn ap=$apOn")
    }

    private fun ensureBluetooth(): Boolean {
        // A running wireless session deliberately keeps Bluetooth OFF: the audio goes over the
        // Wi-Fi (iAP2) tunnel and switching the adapter back on hands the channel to the factory
        // A2DP/AVRCP stack, which leaves CarPlay silent. Never fight that.
        if (WirelessBluetoothState.suspendedForSession) {
            Diagnostics.d(TAG, "bt: skip (adapter suspended for the running wireless session)")
            return true
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.w(TAG, "bt: no adapter")
            return false
        }
        if (adapter.isEnabled) return true
        val returned = try {
            @Suppress("DEPRECATION") adapter.enable()
        } catch (error: SecurityException) {
            Log.w(TAG, "bt: enable() rejected: ${error.message}")
            return false
        }
        Diagnostics.d(TAG, "bt: enable() returned $returned")
        return settle { adapter.isEnabled }
    }

    private fun ensureHotspot(context: Context): Boolean {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            Log.w(TAG, "ap: no WifiManager")
            return false
        }
        if (isApEnabled(wifi)) return true
        // The H6 has a single Wi-Fi interface (wlan0) shared by station mode and the soft AP, and
        // the two cannot coexist: while Wi-Fi is connected to a router the firmware accepts the
        // start call and then silently does nothing (measured — the AP only comes up once Wi-Fi is
        // switched off). That is why auto-launch "never happened" on a car sitting on Wi-Fi.
        // Switching station mode off first is what the car's own Settings app does, and it costs
        // nothing extra: the AP takes wlan0 over anyway and leaves station mode disabled.
        if (isStaEnabled(wifi)) {
            Diagnostics.d(TAG, "ap: Wi-Fi station mode is on (wlan0 is shared) -> switching it off first")
            if (!setStaEnabled(wifi, false)) return false
            settle(STA_OFF_TIMEOUT_MS) { !isStaEnabled(wifi) }
            Diagnostics.d(TAG, "ap: station mode now ${if (isStaEnabled(wifi)) "ON" else "off"}")
        }
        if (startAp(context, wifi, RETRY_TIMEOUT_MS)) return true
        Log.w(TAG, "ap: still OFF after all strategies")
        return false
    }

    /** Runs every known start strategy until one actually turns the soft AP on. */
    private fun startAp(context: Context, wifi: WifiManager, timeoutMillis: Long): Boolean {
        hotspotStrategies(context).forEach { strategy ->
            val outcome = try {
                strategy.start()
                "ok"
            } catch (error: InvocationTargetException) {
                "threw ${error.targetException?.javaClass?.simpleName}: ${error.targetException?.message}"
            } catch (error: Throwable) {
                "failed ${error.javaClass.simpleName}: ${error.message}"
            }
            Diagnostics.d(TAG, "ap: ${strategy.name} -> $outcome")
            if (settle(timeoutMillis) { isApEnabled(wifi) }) {
                Diagnostics.d(TAG, "ap: ON via ${strategy.name}")
                return true
            }
        }
        return false
    }

    private fun isStaEnabled(wifi: WifiManager): Boolean = try {
        wifi.isWifiEnabled
    } catch (_: Throwable) {
        false
    }

    private fun setStaEnabled(wifi: WifiManager, enabled: Boolean): Boolean {
        val ok = try {
            @Suppress("DEPRECATION") wifi.setWifiEnabled(enabled)
        } catch (error: SecurityException) {
            Log.w(TAG, "sta: setWifiEnabled($enabled) rejected: ${error.message}")
            return false
        }
        Diagnostics.d(TAG, "sta: setWifiEnabled($enabled) returned $ok")
        return ok
    }

    private class Strategy(val name: String, val start: () -> Unit)

    /** Ordered by likelihood of being permitted for a plain app on this firmware. */
    private fun hotspotStrategies(context: Context): List<Strategy> = listOf(
        Strategy("WifiManager.setWifiApEnabled") {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.javaClass.getMethod(
                "setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType,
            ).invoke(wifi, null, true)
        },
        Strategy("IConnectivityManager.startTethering") {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            val service = android.net.ConnectivityManager::class.java.getDeclaredField("mService")
                .apply { isAccessible = true }
                .get(manager)
                ?: error("connectivity service unavailable")
            service.javaClass.getMethod(
                "startTethering", Int::class.javaPrimitiveType, android.os.ResultReceiver::class.java,
                Boolean::class.javaPrimitiveType, String::class.java,
            ).invoke(service, 0, null, false, context.packageName)
        },
    )

    private fun isApEnabled(wifi: WifiManager): Boolean = try {
        wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as? Int == WIFI_AP_STATE_ENABLED
    } catch (_: Throwable) {
        false
    }

    /** Waits (off the main thread) for [ready] to become true. */
    private fun settle(timeoutMillis: Long = RETRY_TIMEOUT_MS, ready: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready()) return true
            try {
                Thread.sleep(SETTLE_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return ready()
    }
}
