package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Reads the head unit's own SoftAP configuration so the driver never has to type the
 * car hotspot name and password.
 *
 * H6/Desay: the "built-in car hotspot" is the stock Android SoftAP. The configuration
 * lives in /data/misc/wifi/softap.conf (system-only), so an app can only reach it
 * through the platform API. Several firmwares make the plain WifiManager call throw,
 * so this reader tries, in order:
 *   1. WifiManager.getWifiApConfiguration()          (public on Android 8.1)
 *   2. IWifiManager.getWifiApConfiguration()         (same call through the binder in
 *      WifiManager.mService, bypassing the wrapper)
 * Every failure is logged with its real cause: without that the H6 unit simply reported
 * "unavailable" and the hotspot fields stayed empty, which blocked every connection.
 * When nothing works the manual entry path in the connection setup stays available.
 */
object CarHotspotCredentials {
    private const val TAG = "DiPlay-Hotspot"

    data class Credentials(val ssid: String, val passphrase: String)

    /** Only the first call logs: the home page re-reads on every render. */
    @Volatile private var verbose = true

    /** Null when the car does not expose a readable hotspot configuration. */
    fun read(context: Context): Credentials? = try {
        readInternal(context)
    } finally {
        verbose = false
    }

    private fun readInternal(context: Context): Credentials? {
        val wifi = try {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (error: Throwable) {
            logFailure("wifi service", error)
            null
        } ?: return null

        logApState(wifi)

        readViaWifiManager(wifi)?.let {
            Log.i(TAG, "ap configuration via WifiManager: ssid=${it.ssid}")
            return it
        }
        readViaService(wifi)?.let {
            Log.i(TAG, "ap configuration via IWifiManager binder: ssid=${it.ssid}")
            return it
        }
        if (verbose) Log.i(TAG, "no readable ap configuration on this unit; manual entry is required")
        return null
    }

    private fun readViaWifiManager(wifi: WifiManager): Credentials? {
        val method = try {
            WifiManager::class.java.getMethod("getWifiApConfiguration")
        } catch (error: Throwable) {
            logFailure("WifiManager.getWifiApConfiguration lookup", error)
            return null
        }
        val configuration = try {
            method.invoke(wifi)
        } catch (error: Throwable) {
            logFailure("WifiManager.getWifiApConfiguration", error)
            return null
        }
        return fromConfiguration(configuration)
    }

    /**
     * Some vendor builds wrap the manager call with extra checks that throw. Going
     * straight at the binder in WifiManager.mService often still answers.
     */
    private fun readViaService(wifi: WifiManager): Credentials? {
        val service = try {
            WifiManager::class.java.getDeclaredField("mService").apply { isAccessible = true }.get(wifi)
        } catch (error: Throwable) {
            logFailure("WifiManager.mService lookup", error)
            return null
        } ?: return null
        return try {
            val method = service.javaClass.getMethod("getWifiApConfiguration").apply { isAccessible = true }
            fromConfiguration(method.invoke(service))
        } catch (error: Throwable) {
            logFailure("IWifiManager.getWifiApConfiguration", error)
            null
        }
    }

    private fun fromConfiguration(configuration: Any?): Credentials? {
        if (configuration == null) return null
        val ssid = unquote(readString(configuration, "SSID"))
        if (ssid.isBlank()) return null
        return Credentials(ssid, unquote(readString(configuration, "preSharedKey")))
    }

    private fun readString(target: Any, name: String): String? {
        try {
            val field = target.javaClass.getField(name).apply { isAccessible = true }
            (field.get(target) as? String)?.let { return it }
        } catch (error: Throwable) {
            logFailure("field $name", error)
        }
        return try {
            target.javaClass.getMethod("get$name").apply { isAccessible = true }.invoke(target) as? String
        } catch (error: Throwable) {
            null
        }
    }

    private fun logApState(wifi: WifiManager) {
        if (!verbose) return
        val state = try {
            WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as? Int
        } catch (error: Throwable) {
            logFailure("getWifiApState", error)
            null
        }
        Log.i(TAG, "wifi ap state=$state (11=disabled 12=disabling 13=enabled 14=enabling)")
    }

    /** SoftAP values are commonly stored wrapped in quotes. */
    private fun unquote(value: String?): String = value.orEmpty().trim().trim('"')

    private fun logFailure(step: String, error: Throwable) {
        if (!verbose) return
        val cause = error.cause
        val detail = buildString {
            append("ap config step '$step' failed: ${error.javaClass.simpleName}: ${error.message}")
            if (cause != null) append(" | cause=${cause.javaClass.name}: ${cause.message}")
        }
        Log.i(TAG, detail)
        cause?.stackTrace?.firstOrNull()?.let { Log.i(TAG, "  cause top frame: $it") }
    }
}
