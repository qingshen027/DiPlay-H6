package com.shilapi.xcertplay.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Desay SV (Haval H6 head unit) call-mic arbitration, reverse engineered from the
 * CarPlay box app (com.aufn.haval, decrypted android.media.BeanProxy / libvr client).
 *
 * On these head units the stock voice engine holds the mic permanently and the audio
 * HAL rejects any second recorder. The box app coexists WITHOUT disabling the
 * assistant by doing, at call start:
 *  1) ISvVrOthers.exec(pkg, "controlRecorder", acquire-JSON) against the system
 *     service com.desaysv.vrservice — the vendor stack reallocates the mic from the
 *     voice engine (com.android.engmode) to the caller;
 *  2) dsv.platformadapter.app.SvCarAudioManager.setAttrCarAudioType(attrs, "phone")
 *     + AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) so the vendor audio
 *     policy switches to the call path;
 *  3) plain AudioRecord(VOICE_COMMUNICATION) — done by MicrophoneUplink, not here.
 *
 * At call end the box releases in reverse: stop recording, send
 * detail="reallocation" (give the mic back to the voice engine), abandon focus —
 * otherwise the stock voice assistant stays dead. We mirror that exactly.
 *
 * Binder protocol (decompile-verified):
 *  - pool:    descriptor com.desaysv.vrservice.svapps.ISvVrFunctionBinderPool,
 *             transact 1 = queryBinder(int); the "others" sub-service is type 5.
 *  - others:  descriptor com.desaysv.vrservice.svapps.others.ISvVrOthers,
 *             transact 3 = exec(String pkg, String key, String value) -> int.
 *
 * Everything is best-effort: any failure is logged and swallowed so the uplink
 * still falls back to its own capture-profile probing.
 */
internal object DesaysvMicRouter {
    private const val TAG = "xcertplay-usb"
    private const val VR_PKG = "com.desaysv.vrservice"
    private const val VR_SERVICE_CLS = "com.desaysv.vradapter.service.VrBinderService"
    private const val DESC_BINDER_POOL = "com.desaysv.vrservice.svapps.ISvVrFunctionBinderPool"
    private const val DESC_OTHERS = "com.desaysv.vrservice.svapps.others.ISvVrOthers"
    private const val POOL_TRANSACT_QUERY_BINDER = 1
    private const val OTHERS_SERVICE_TYPE = 5
    private const val OTHERS_TRANSACT_EXEC = 3
    private const val KEY_CONTROL_RECORDER = "controlRecorder"
    private const val ENGINE_PKG = "com.android.engmode"
    // Exact JSON the box sends (decompiled BeanProxy.n()/m()).
    private const val JSON_ACQUIRE =
        "{\"resultCode\":4, \"detail\":\"$ENGINE_PKG\",\"pkgName\":\"$ENGINE_PKG\"}"
    private const val JSON_RELEASE =
        "{\"resultCode\":4, \"detail\":\"reallocation\",\"pkgName\":\"$ENGINE_PKG\"}"
    private const val SVCAR_CLASS = "dsv.platformadapter.app.SvCarAudioManager"
    private const val CAR_AUDIO_TYPE_PHONE = "phone"
    private const val BINDER_WAIT_MS = 1_500L

    @Volatile private var appContext: Context? = null
    @Volatile private var audioManager: AudioManager? = null
    @Volatile private var poolBinder: IBinder? = null
    @Volatile private var othersBinder: IBinder? = null
    @Volatile private var phoneFocusRequest: AudioFocusRequest? = null
    @Volatile private var diagnostic: (String) -> Unit = {}
    @Volatile private var bindRequested = false
    @Volatile private var callHeld = false
    private val lock = Object()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                poolBinder = service
                othersBinder = null
                lock.notifyAll()
            }
            Log.i(TAG, "DesaysvMicRouter: vrservice connected")
            diagnostic("MicRouter: vrservice connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                poolBinder = null
                othersBinder = null
            }
            bindRequested = false
            Log.w(TAG, "DesaysvMicRouter: vrservice disconnected")
        }
    }

    /** Binds the VR service ahead of time (session start) so a call doesn't wait. Idempotent. */
    fun warmUp(context: Context, onDiagnostic: (String) -> Unit = {}) {
        diagnostic = onDiagnostic
        if (appContext == null) {
            appContext = context.applicationContext
            audioManager = appContext?.getSystemService(AudioManager::class.java)
        }
        ensureBound()
    }

    private fun ensureBound() {
        val ctx = appContext ?: return
        if (poolBinder != null || bindRequested) return
        bindRequested = true
        try {
            val intent = Intent().setClassName(VR_PKG, VR_SERVICE_CLS)
            val ok = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "DesaysvMicRouter: bind vrservice -> $ok")
            if (!ok) {
                bindRequested = false
                diagnostic("MicRouter: vrservice bind returned false")
            }
        } catch (error: Exception) {
            bindRequested = false
            Log.w(TAG, "DesaysvMicRouter: bind vrservice failed", error)
            diagnostic("MicRouter: vrservice bind error ${error.javaClass.simpleName}")
        }
    }

    private fun obtainOthersBinder(timeoutMs: Long): IBinder? {
        ensureBound()
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (poolBinder == null && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(200)
                } catch (_: InterruptedException) {
                    return null
                }
            }
            othersBinder?.takeIf { it.pingBinder() }?.let { return it }
        }
        val pool = poolBinder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESC_BINDER_POOL)
            data.writeInt(OTHERS_SERVICE_TYPE)
            pool.transact(POOL_TRANSACT_QUERY_BINDER, data, reply, 0)
            reply.readException()
            val binder = reply.readStrongBinder()
            synchronized(lock) { othersBinder = binder }
            binder
        } catch (error: Exception) {
            Log.w(TAG, "DesaysvMicRouter: queryBinder(5) failed", error)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** ISvVrOthers.exec(our package, "controlRecorder", json); returns service result or -1. */
    private fun execControlRecorder(json: String, label: String): Int {
        val ctx = appContext ?: return -1
        val binder = obtainOthersBinder(BINDER_WAIT_MS) ?: run {
            diagnostic("MicRouter: vrservice unavailable, controlRecorder $label skipped")
            return -1
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESC_OTHERS)
            data.writeString(ctx.packageName)
            data.writeString(KEY_CONTROL_RECORDER)
            data.writeString(json)
            binder.transact(OTHERS_TRANSACT_EXEC, data, reply, 0)
            reply.readException()
            val ret = reply.readInt()
            Log.i(TAG, "DesaysvMicRouter: controlRecorder $label ret=$ret")
            diagnostic("MicRouter: controlRecorder $label ret=$ret")
            ret
        } catch (error: Exception) {
            Log.w(TAG, "DesaysvMicRouter: controlRecorder $label failed", error)
            diagnostic("MicRouter: controlRecorder $label error ${error.javaClass.simpleName}")
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Tags call attributes with the vendor car-audio type "phone" via the framework class. */
    private fun tagPhoneAttributes(attrs: AudioAttributes): AudioAttributes {
        return try {
            val cls = Class.forName(SVCAR_CLASS)
            val method = cls.getMethod("setAttrCarAudioType", AudioAttributes::class.java, String::class.java)
            (method.invoke(null, attrs, CAR_AUDIO_TYPE_PHONE) as? AudioAttributes) ?: attrs
        } catch (error: Throwable) {
            Log.w(TAG, "DesaysvMicRouter: setAttrCarAudioType unavailable", error)
            diagnostic("MicRouter: SvCarAudioManager unavailable; raw attrs used")
            attrs
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "DesaysvMicRouter: phone focus change=$change")
    }

    private fun requestPhoneFocus() {
        val am = audioManager ?: return
        try {
            abandonPhoneFocus()
            val base = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val tagged = tagPhoneAttributes(base)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(tagged)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            val ret = am.requestAudioFocus(request)
            phoneFocusRequest = if (ret == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) request else null
            Log.i(TAG, "DesaysvMicRouter: requestPhoneFocus ret=$ret")
            diagnostic("MicRouter: phone focus ret=$ret")
        } catch (error: Exception) {
            Log.w(TAG, "DesaysvMicRouter: requestPhoneFocus failed", error)
            diagnostic("MicRouter: phone focus error ${error.javaClass.simpleName}")
        }
    }

    private fun abandonPhoneFocus() {
        val am = audioManager ?: return
        val request = phoneFocusRequest ?: return
        phoneFocusRequest = null
        try {
            am.abandonAudioFocusRequest(request)
        } catch (error: Exception) {
            Log.w(TAG, "DesaysvMicRouter: abandon focus failed", error)
        }
    }

    /**
     * Call-audio session started (downlink thread): hand the mic to us, then take the
     * exclusive "phone" focus — same order as the box (controlRecorder, then focus).
     * Must be followed by exitCall() after recording stops.
     */
    @Synchronized
    fun enterCall() {
        if (callHeld) return
        callHeld = true
        execControlRecorder(JSON_ACQUIRE, "acquire")
        requestPhoneFocus()
    }

    /** Call ended (recording already stopped): give the mic back to the voice engine. */
    @Synchronized
    fun exitCall() {
        if (!callHeld) return
        callHeld = false
        execControlRecorder(JSON_RELEASE, "release")
        abandonPhoneFocus()
    }
}
