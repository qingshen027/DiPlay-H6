package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.desay.libsvcarinfo.ICarInfo
import com.desay.libsvcarinfo.ICarInfoCallBack

/**
 * Watches the car's headlight switch through the Desay SV CarInfoService so the
 * CarPlay appearance can follow it: lights on = night mode, lights off = day mode.
 *
 * Protocol (per the vendor integration doc): moduleId 0xFE must be translated to
 * funcIndex 21 through FUNCTION_MAP before every call; funcId 0x01 is the headlight
 * switch, value 1 = on. Callbacks arrive on a Binder thread.
 */
class HeadlightMonitor(
    context: Context,
    private val onHeadlightChanged: (Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "DiPlay-Headlight"
        private const val SERVICE_PACKAGE = "com.desay.svcarinfo"
        private const val SERVICE_CLASS = "com.desay.svcarinfo.CarInfoService"
        private const val MODULE_READONLY = 0xFE
        private const val FUNC_HEADLIGHT = 0x01
        private const val RECONNECT_DELAY_MS = 3000L
        private const val POLL_INTERVAL_MS = 1000L

        private val FUNCTION_MAP = intArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x11, 0x12, 0xFB, 0xFC, 0xFD, 0xFE, 0xFF, 0x100, 0x101,
        )

        private fun funcIdToIndex(moduleId: Int): Int =
            FUNCTION_MAP.indexOf(moduleId).takeIf { it >= 0 } ?: 0

        fun available(context: Context): Boolean = runCatching {
            context.packageManager.getPackageInfo(SERVICE_PACKAGE, 0)
        }.isSuccess
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: ICarInfo? = null
    private var started = false
    private var pollThread: Thread? = null

    /** Last known state, null until the first read/callback arrives. */
    @Volatile var headlightOn: Boolean? = null
        private set

    fun start() {
        if (started) return
        started = true
        bind()
        startPolling()
    }

    fun stop() {
        started = false
        mainHandler.removeCallbacksAndMessages(null)
        pollThread?.interrupt()
        pollThread = null
        service?.let { svc ->
            runCatching { svc.unRegisterCarInfoCallBack(funcIdToIndex(MODULE_READONLY), callback) }
        }
        service = null
        runCatching { appContext.unbindService(connection) }
    }

    /**
     * Primary state source: poll getItemValues every second. The vendor's change
     * callback proved unreliable on this firmware (registered fine, never fires),
     * so the callback below only acts as an accelerator when it does arrive.
     */
    private fun startPolling() {
        pollThread = Thread({
            while (started) {
                val svc = service
                if (svc != null) {
                    runCatching {
                        val values = svc.getItemValues(funcIdToIndex(MODULE_READONLY), FUNC_HEADLIGHT)
                        val raw = values?.joinToString(",") ?: "null"
                        val isOn = values?.firstOrNull() == 1
                        if (headlightOn != isOn) Log.i(TAG, "poll read [$raw] -> ${if (isOn) "on" else "off"}")
                        dispatch(isOn)
                    }.onFailure { Log.w(TAG, "poll failed: ${it.message}") }
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "diplay-headlight-poll").also { it.start() }
    }

    private fun bind() {
        val bound = runCatching {
            appContext.bindService(
                Intent().setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS)),
                connection, Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) {
            Log.w(TAG, "bindService failed, retrying in ${RECONNECT_DELAY_MS}ms")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!started) return
        mainHandler.postDelayed({ if (started && service == null) bind() }, RECONNECT_DELAY_MS)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = ICarInfo.Stub.asInterface(binder)
            runCatching {
                val funcIndex = funcIdToIndex(MODULE_READONLY)
                service?.registerCarInfoCallBack(funcIndex, callback, intArrayOf(FUNC_HEADLIGHT))
                val values = service?.getItemValues(funcIndex, FUNC_HEADLIGHT)
                val isOn = values?.firstOrNull() == 1
                Log.i(TAG, "connected; initial read [${values?.joinToString(",") ?: "null"}] -> ${if (isOn) "on" else "off"}")
                dispatch(isOn)
            }.onFailure {
                Log.w(TAG, "register/initial read failed", it)
                scheduleReconnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            Log.w(TAG, "service disconnected")
            scheduleReconnect()
        }
    }

    private val callback = object : ICarInfoCallBack.Stub() {
        override fun onChangeEvent(moduleId: Int, funcId: Int, value: Int) {
            // Unfiltered on purpose: if the vendor reports different ids than the doc
            // claims, the log shows exactly what arrived.
            Log.i(TAG, "callback module=$moduleId func=$funcId value=$value")
            if (moduleId != MODULE_READONLY && moduleId != funcIdToIndex(MODULE_READONLY)) return
            if (funcId != FUNC_HEADLIGHT) return
            dispatch(value == 1)
        }
    }

    private fun dispatch(isOn: Boolean) {
        if (headlightOn == isOn) return
        headlightOn = isOn
        Log.i(TAG, "headlight ${if (isOn) "on -> night" else "off -> day"}")
        mainHandler.post { onHeadlightChanged(isOn) }
    }
}
