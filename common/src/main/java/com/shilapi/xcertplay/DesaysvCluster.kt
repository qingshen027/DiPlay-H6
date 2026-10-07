package com.shilapi.xcertplay

import android.util.Log

/**
 * Desay SV (德赛西威) instrument-cluster RTP push on the HAVAL H6 head unit.
 *
 * The vendor framework extension `dsv-platformadapter` sits on the boot classpath and
 * exposes `desaysv.adapter.app.rtp.SvRtpManager`. Once activated, the system captures
 * the main-screen content and RTP-streams it to the 800x480 LCD cluster — the same
 * mechanism the stock Amap build uses for its fly-screen map.
 */
object DesaysvCluster {
    const val TAG = "DiPlay-ClusterRtp"
    private const val CLASS_NAME = "desaysv.adapter.app.rtp.SvRtpManager"

    const val WIDTH = 800
    const val HEIGHT = 480
    const val FPS = 30

    /**
     * Declared stream resolution for the iPhone's instrument-cluster map stream (111).
     * 1600x960 (2x) was tried in build33 and the Renesas decoder could not allocate
     * output buffers for it while the main stream was also decoding (ACodec "Failed to
     * allocate output port buffers"), leaving the cluster black. 1792x720 is the highest
     * resolution the decoder sustains alongside the main stream on this head unit, so it
     * is the cap used here. The crop editor works in this 1792x720 source space;
     * DesaysvCrop scales the chosen box down to the 800x480 panel. The crop math adapts
     * to the real coded size reported by the decoder (AndroidMediaSink.onAltVideoSize).
     */
    const val STREAM_WIDTH = 1792
    const val STREAM_HEIGHT = 720

    fun available(): Boolean = runCatching { Class.forName(CLASS_NAME) }.isSuccess

    /**
     * Opens the RTP channel. Idempotent; the proven activation sequence (from the Amap
     * integration on this head unit) calls the switch three times 300ms apart because
     * the system service may not be ready on the first attempt. Runs on its own thread.
     */
    fun activate(log: (String) -> Unit) {
        Thread({
            var ok = false
            for (attempt in 1..3) {
                ok = runCatching {
                    val cls = Class.forName(CLASS_NAME)
                    val manager = cls.getMethod("get").invoke(null)
                    cls.getMethod("setRtpStreamSwitch", Boolean::class.javaPrimitiveType)
                        .invoke(manager, true)
                    cls.getMethod("sendRtpStream").invoke(manager)
                }.onFailure { Log.w(TAG, "activation attempt $attempt failed", it) }.isSuccess
                log("Cluster RTP activation attempt $attempt ${if (ok) "succeeded" else "failed"}")
                if (ok) break
                runCatching { Thread.sleep(300) }
            }
        }, "diplay-cluster-rtp").start()
    }

    /** Closes the RTP channel (fly-screen off). Best-effort, runs on its own thread. */
    fun deactivate(log: (String) -> Unit) {
        Thread({
            runCatching {
                val cls = Class.forName(CLASS_NAME)
                val manager = cls.getMethod("get").invoke(null)
                cls.getMethod("setRtpStreamSwitch", Boolean::class.javaPrimitiveType)
                    .invoke(manager, false)
                log("Cluster RTP deactivated")
            }.onFailure { Log.w(TAG, "deactivate failed", it) }
        }, "diplay-cluster-rtp-off").start()
    }

    /**
     * Diagnostic probe: dumps every public method signature of SvRtpManager (plus the
     * AIDL interface and the internal impl class) and the current getRegion() value.
     * Lines go to both logcat (tag DiPlay-ClusterRtp) and the callback. Needed because
     * the vendor jar is a stub on the host and the real signatures live in the vdex.
     */
    fun probe(log: (String) -> Unit) {
        fun emit(line: String) {
            Diagnostics.i(TAG, line)
            log(line)
        }
        Thread({
            runCatching {
                val cls = Class.forName(CLASS_NAME)
                emit("RTP probe: ${cls.name} methods:")
                cls.methods.sortedBy { it.name }.forEach { m ->
                    emit("  m ${m.name}(${m.parameterTypes.joinToString(", ") { it.name }}): ${m.returnType.name}")
                }
                cls.declaredClasses.forEach { emit("  inner ${it.name}") }
                val manager = cls.getMethod("get").invoke(null)
                runCatching {
                    val region = cls.getMethod("getRegion").invoke(manager)
                    emit("RTP probe: getRegion() = $region (${region?.javaClass?.name})")
                    region?.javaClass?.fields?.forEach { f ->
                        runCatching { emit("    field ${f.name} = ${f.get(region)}") }
                    }
                }.onFailure { emit("RTP probe: getRegion failed: ${it.cause?.message ?: it.message}") }
            }.onFailure { emit("RTP probe: SvRtpManager failed: ${it.cause?.message ?: it.message}") }
            listOf(
                "desaysv.adapter.app.rtp.ISvRtpService",
                "dsv.platformadapter.app.PlatformRtpManager",
            ).forEach { name ->
                runCatching {
                    val cls = Class.forName(name)
                    emit("RTP probe: $name methods:")
                    cls.methods.sortedBy { it.name }.forEach { m ->
                        emit("  m ${m.name}(${m.parameterTypes.joinToString(", ") { it.name }}): ${m.returnType.name}")
                    }
                }.onFailure { emit("RTP probe: $name not found (${it.message})") }
            }
        }, "diplay-cluster-probe").start()
    }
}
