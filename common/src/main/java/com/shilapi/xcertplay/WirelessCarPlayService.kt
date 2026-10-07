package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ActivityManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.host.R

/**
 * Always-on foreground service that realises the "box style" wireless CarPlay behaviour:
 *
 * - Started at boot (see [BootReceiver] / [DiPlayApplication]) when auto-start + wireless +
 *   auto-launch are all enabled. The ROM's own boot mechanism brings the DiPlay process up.
 * - Listens for the iPhone to join the car hotspot and connect over Bluetooth. The instant both
 *   are present the service brings [CarPlayHostActivity] to the foreground (RESIDENT). There is
 *   deliberately NO boot timer: CarPlay appears when the phone actually connects, which is exactly
 *   what the box does — and it is immune to the head-unit ROM starting the process late at boot.
 * - On disconnect the activity returns to the car home screen but keeps the controller listening;
 *   reconnect brings it back. The service is just the resilient connection-aware orchestrator.
 */
class WirelessCarPlayService : Service() {
    companion object {
        const val ACTION_START = "com.shihab.diplay.WIRELESS_START"
        const val ACTION_STOP = "com.shihab.diplay.WIRELESS_STOP"
        private const val TAG = "xcertplay-wireless"
        private const val CHANNEL = "diplay_wireless"
        private const val NOTIFICATION_ID = 2
        /**
         * Set true the moment [launchAutoConnect] brings CarPlayHostActivity up, and cleared by
         * that activity's onDestroy. Lets the periodic probe re-arm a relaunch if the activity is
         * killed by the system while the service is still alive (otherwise a missed broadcast +
         * dead activity would leave auto-launch permanently stuck).
         */
        @Volatile var residentLaunched = false
        /** Re-probe cadence. Cheap (BT/WiFi reflection) and keeps detection from stalling on a
         * dropped BOOT_COMPLETED / state broadcast — directly improves boot auto-launch speed and
         * stability on the HAVAL ROM, which intercepts broadcasts for third-party apps. */
        private const val PROBE_INTERVAL_MS = 5000L
        // Bluetooth roles that mean "an audio device is connected". The H6 is A2DP_SINK / HFP_CLIENT
        // while the iPhone is the Source / AG, so we must check all four roles, not only Source/AG —
        // otherwise the connection is never detected (the original bug behind "phone won't pop up").
        private val BT_PROFILES = intArrayOf(1, 2, 11, 16)
        private const val WIFI_AP_STATE_ENABLED = 13
        // Hidden broadcast / method names kept as literals because they are not in the public SDK.
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED_ACTION"

        /** True when this service's process currently has the foreground service running. Used by
         * DiPlayActivity as a fallback trigger so auto-launch still arms even if the ROM never
         * started the process at boot (HAVAL intercepts BOOT_COMPLETED for third-party apps). */
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(ActivityManager::class.java) ?: return false
            return runCatching {
                am.getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == WirelessCarPlayService::class.java.name }
            }.getOrDefault(false)
        }
    }

    private var stopping = false
    private var btConnected = false
    private var wifiApEnabled = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val periodicProbe = object : Runnable {
        override fun run() {
            if (stopping || !isWanted()) return
            // Keep Bluetooth + the car hotspot on so the iPhone can join (and re-join after the
            // OEM bluetooth service switches them off again).
            bringUpRadios()
            probeState()
            evaluate()
            // Keep polling while the service is alive: detection must not stall if a state
            // broadcast was dropped by the ROM. evaluate() skips the actual launch once the
            // resident activity is already up, so this costs almost nothing.
            mainHandler.postDelayed(this, PROBE_INTERVAL_MS)
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED) {
                probeState()
                evaluate()
            }
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_WIFI_AP_STATE_CHANGED) {
                probeState()
                evaluate()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED))
        registerReceiver(wifiReceiver, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            residentLaunched = false
            CarRadioBringUp.reset()
            stopResident()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isWanted()) {
            // Auto-launch was disabled (or wireless turned off) — nothing to keep alive.
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground()
        // HAVAL: "open after the car starts" also has to switch the head unit's own Bluetooth and
        // hotspot on, otherwise the iPhone can never join and auto-launch waits forever. Bringing
        // them up here (not only in the periodic probe) shaves a few more seconds off boot.
        bringUpRadios()
        // The phone may already be connected at boot — probe the current state immediately, then
        // evaluate. If not connected yet, the two receivers above re-probe on the next change,
        // and the periodic probe below covers any broadcast the ROM dropped.
        probeState()
        evaluate()
        mainHandler.removeCallbacks(periodicProbe)
        mainHandler.postDelayed(periodicProbe, PROBE_INTERVAL_MS)
        return START_STICKY
    }

    private fun isWanted(): Boolean {
        // While the device is still locked (Direct Boot) SharedPreferences cannot be read, so keep
        // the listener pre-warmed and re-check on every probe — the periodic probe re-evaluates
        // this within a few seconds of the user unlocking.
        if (!BootUnlock.isUnlocked(this)) return true
        return AirPlayPersistence.loadAutoStartOnBoot(this) &&
            AirPlayPersistence.loadWirelessEnabled(this) &&
            AirPlayPersistence.loadWirelessAutoLaunch(this)
    }

    /**
     * Switches the head unit's Bluetooth and Wi-Fi hotspot on with plain-app capabilities only.
     * Skipped while the device is still locked: the "open after the car starts" preference lives in
     * credential-encrypted storage and cannot be confirmed yet, and [isWanted] only assumes true.
     */
    private fun bringUpRadios() {
        if (stopping || !BootUnlock.isUnlocked(this)) return
        CarRadioBringUp.ensure(this)
    }

    private fun probeState() {
        btConnected = hasAnyBluetoothProfileConnected()
        wifiApEnabled = isWifiApEnabled()
        Diagnostics.d(TAG, "probeState: btConnected=$btConnected wifiApEnabled=$wifiApEnabled")
    }

    private fun hasAnyBluetoothProfileConnected(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return BT_PROFILES.any { profile ->
            runCatching { adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED }
                .getOrDefault(false)
        }
    }

    private fun isWifiApEnabled(): Boolean {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return runCatching {
            val state = wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as? Int
            state == WIFI_AP_STATE_ENABLED
        }.getOrDefault(false)
    }

    private fun evaluate() {
        if (stopping || !isWanted()) {
            Diagnostics.d(TAG, "evaluate: skip (stopping=$stopping wanted=${isWanted()})")
            return
        }
        if (!btConnected || !wifiApEnabled) {
            Diagnostics.d(TAG, "evaluate: skip (waiting for iPhone: bt=$btConnected ap=$wifiApEnabled)")
            return
        }
        if (residentLaunched) {
            Diagnostics.d(TAG, "evaluate: skip (resident activity already launched)")
            return
        }
        // Still locked: CarPlayHostActivity reads the saved hotspot credentials from
        // credential-encrypted storage, so launching it now would start a connection that cannot
        // complete. The next periodic probe (5s) re-checks right after unlock.
        if (!BootUnlock.isUnlocked(this)) {
            Diagnostics.d(TAG, "evaluate: skip (device still locked, waiting for unlock)")
            return
        }
        // A fresh install has auto-start enabled by default, so without this the projection would
        // be pulled up before the owner ever sees the home page — and the disclaimer has to be the
        // first thing they read. Hold the auto-launch until it has been accepted; DiPlayActivity
        // re-arms the listener on accept.
        if (AirPlayPersistence.loadDisclaimerAcceptedVersion(this) < Attribution.DISCLAIMER_VERSION) {
            Diagnostics.d(TAG, "evaluate: skip (disclaimer not accepted yet)")
            return
        }
        Diagnostics.d(TAG, "evaluate: connected -> launchAutoConnect")
        launchAutoConnect()
    }

    private fun startForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "CarPlay 无线监听", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CarPlayHostActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WirelessCarPlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(getString(R.string.wireless_service_title))
            .setContentText(getString(R.string.wireless_service_text))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.wireless_disconnect), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun launchAutoConnect() {
        // singleTask + REORDER_TO_FRONT: creates the activity or moves the existing instance to
        // front. It runs the connection flow and shows the "waiting for iPhone" screen; when the
        // paired phone connects, CarPlay appears. On disconnect it returns home but keeps listening.
        val intent = Intent(this, CarPlayHostActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(CarPlayHostActivity.EXTRA_RESIDENT, true)
        }
        runCatching {
            Diagnostics.d(TAG, "launchAutoConnect: starting CarPlayHostActivity (RESIDENT)")
            startActivity(intent)
            residentLaunched = true
        }.onFailure { Log.e(TAG, "launchAutoConnect failed: ${it.message}") }
    }

    private fun stopResident() {
        // Ask the resident activity to finish itself; the session/controller are torn down there.
        sendBroadcast(Intent(CarPlayHostActivity.ACTION_RESIDENT_STOP).setPackage(packageName))
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(periodicProbe)
        runCatching { unregisterReceiver(btReceiver) }
        runCatching { unregisterReceiver(wifiReceiver) }
    }
}
