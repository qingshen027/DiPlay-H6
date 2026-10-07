package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report = { message ->
        Log.i(TAG, message)
        onDiagnostic(message)
    })
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    private var thread: Thread? = null
    /** Actual hardware capture rate; may differ from [MicrophoneConfig.sampleRate] when the
     *  frame-share fallback attached to another client's already-open input thread. */
    @Volatile private var captureRate: Int = 0
    @Volatile private var captureChannels: Int = 1

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        if (config.codec == AudioCodecKind.OPUS &&
            (config.sampleRate != 48_000 || config.channels != 1 || config.samplesPerPacket != 960)
        ) {
            Log.w(TAG, "麦克风 Opus 仅支持 48k mono 20ms")
            stats.failure(MicrophoneFailureStage.ENCODER)
            running.set(false)
            return false
        }

        val source = when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(config.bitrate ?: 48_000).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            stats.failure(MicrophoneFailureStage.ENCODER)
            running.set(false)
            return false
        }

        // H6/Desay: after DesaysvMicRouter's controlRecorder the mic is handed to us
        // exclusively, so prefer the box app's proven config (VOICE_COMMUNICATION,
        // mono) at the encoder rate first, then the 48 kHz stereo profile that tested
        // working on this unit. The 16 kHz frame-share profiles stay as a last-resort
        // fallback for the case where arbitration failed and the voice assistant still
        // owns the only input thread (stereo matches its open stream).
        val candidates = buildList {
            add(CaptureProfile(source, config.sampleRate, 1, frameShare = false))
            add(CaptureProfile(source, config.sampleRate, 2, frameShare = false))
            add(CaptureProfile(source, FRAME_SHARE_RATE, 1, frameShare = false))
            add(CaptureProfile(MediaRecorder.AudioSource.VOICE_RECOGNITION, FRAME_SHARE_RATE, 2, frameShare = true))
            add(CaptureProfile(MediaRecorder.AudioSource.MIC, FRAME_SHARE_RATE, 2, frameShare = true))
            add(CaptureProfile(MediaRecorder.AudioSource.DEFAULT, FRAME_SHARE_RATE, 2, frameShare = true))
            add(CaptureProfile(MediaRecorder.AudioSource.VOICE_RECOGNITION, FRAME_SHARE_RATE, 1, frameShare = true))
            add(CaptureProfile(MediaRecorder.AudioSource.MIC, FRAME_SHARE_RATE, 1, frameShare = true))
        }

        // The mic handover triggered by controlRecorder can lag a moment; the box app
        // retries startRecording 10 times. Retry the whole probe chain a few times.
        var opened: OpenedRecorder? = null
        for (attempt in 1..OPEN_CHAIN_ATTEMPTS) {
            for (profile in candidates) {
                opened = tryOpen(profile)
                if (opened != null) {
                    if (profile.frameShare) {
                        Log.i(TAG, "microphone frame-share profile works rate=${profile.rate} source=${profile.source}")
                    }
                    break
                }
            }
            if (opened != null) break
            if (attempt < OPEN_CHAIN_ATTEMPTS) {
                Log.i(TAG, "microphone: capture chain attempt $attempt produced no frames; retrying")
                Thread.sleep(OPEN_CHAIN_RETRY_DELAY_MS)
            }
        }
        if (opened == null) {
            Log.w(TAG, "microphone: no capture profile produced frames")
            nextEncoder?.close()
            running.set(false)
            return false
        }
        captureRate = opened.profile.rate
        captureChannels = opened.profile.channels
        val nextRecorder = opened.recorder
        val pendingBytes = opened.pendingBytes
        val pendingCount = opened.pendingCount

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            stats.failure(MicrophoneFailureStage.SOCKET_CREATION, error)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            if (config.audioType == "telephony") effects = voiceEffects(nextRecorder.audioSessionId)
            // tryOpen already called startRecording() and probed real frames.
            stats.started(routeType(nextRecorder), nextRecorder.recordingState, nextRecorder.audioSource, captureRate)
            thread = Thread({ capture(nextRecorder, nextSocket, pendingBytes, pendingCount) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    private data class CaptureProfile(
        val source: Int,
        val rate: Int,
        val channels: Int,
        val frameShare: Boolean,
    ) {
        val channelMask: Int
            get() = if (channels >= 2) AndroidAudioFormat.CHANNEL_IN_STEREO else AndroidAudioFormat.CHANNEL_IN_MONO
    }

    private class OpenedRecorder(
        val profile: CaptureProfile,
        val recorder: AudioRecord,
        val pendingBytes: ByteArray,
        val pendingCount: Int,
    )

    /**
     * Creates, starts and PROBES a recorder for [profile]: only counts as working if
     * real PCM bytes arrive within [PROBE_TIMEOUT_MS]. Returns null otherwise (and has
     * already released the recorder). This is what lets start() fall through to the
     * frame-share profiles when the HAL silently gives us a dead track.
     */
    private fun tryOpen(profile: CaptureProfile): OpenedRecorder? {
        val minBuffer = AudioRecord.getMinBufferSize(
            profile.rate, profile.channelMask, AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            return null
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        val candidate = try {
            AudioRecord.Builder()
                .setAudioSource(profile.source)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(profile.rate)
                        .setChannelMask(profile.channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "microphone recorder creation failed source=${profile.source} rate=${profile.rate}", error)
            stats.failure(MicrophoneFailureStage.RECORDER_CREATION, error)
            return null
        }
        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
            stats.failure(MicrophoneFailureStage.RECORDER_INITIALIZATION, code = candidate.state)
            candidate.release()
            return null
        }
        val probe = ByteArray(PROBE_BUFFER_BYTES)
        var pendingCount = 0
        try {
            candidate.startRecording()
            if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                stats.failure(MicrophoneFailureStage.RECORDING_STATE, code = candidate.recordingState)
                candidate.release()
                return null
            }
            val deadline = System.nanoTime() + PROBE_TIMEOUT_MS * 1_000_000L
            while (System.nanoTime() < deadline) {
                val count = candidate.read(probe, pendingCount, probe.size - pendingCount, AudioRecord.READ_NON_BLOCKING)
                if (count > 0) {
                    pendingCount += count
                    if (pendingCount >= PROBE_MIN_BYTES) break
                } else if (count < 0) {
                    Log.w(TAG, "microphone probe read error code=$count source=${profile.source} rate=${profile.rate}")
                    break
                } else {
                    Thread.sleep(PROBE_POLL_MILLIS)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone probe failed source=${profile.source} rate=${profile.rate}", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            candidate.release()
            return null
        }
        if (pendingCount <= 0) {
            Log.i(TAG, "microphone profile produced no frames source=${profile.source} rate=${profile.rate}; trying next")
            stats.failure(MicrophoneFailureStage.NO_DATA_TIMEOUT, code = profile.rate)
            try {
                candidate.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            candidate.release()
            return null
        }
        return OpenedRecorder(profile, candidate, probe, pendingCount)
    }

    /**
     * Converts one 20 ms hardware-rate PCM16 frame into the 48 kHz mono frame the Opus
     * encoder expects: optional stereo downmix, then linear-interpolation upsample
     * (48000 / captureRate samples out per 1 in; ratio is an integer on this platform).
     */
    private fun convertFrame(src: ByteArray, srcBytes: Int, srcChannels: Int, dst: ByteArray) {
        val ratio = config.sampleRate / captureRate
        val inFrames = srcBytes / (2 * srcChannels)
        var prev = 0
        var out = 0
        var i = 0
        while (i < inFrames && out + ratio * 2 <= dst.size) {
            val cur = if (srcChannels >= 2) {
                val l = (src[i * 4].toInt() and 0xFF) or (src[i * 4 + 1].toInt() shl 8)
                val r = (src[i * 4 + 2].toInt() and 0xFF) or (src[i * 4 + 3].toInt() shl 8)
                (l + r) / 2
            } else {
                (src[i * 2].toInt() and 0xFF) or (src[i * 2 + 1].toInt() shl 8)
            }
            for (step in 1..ratio) {
                val v = prev + (cur - prev) * step / ratio
                dst[out++] = (v and 0xFF).toByte()
                dst[out++] = (v shr 8).toByte()
            }
            prev = cur
            i++
        }
    }

    private fun voiceEffects(sessionId: Int): List<AudioEffect> = listOfNotNull(
        enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
        },
        enabledEffect("NS") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
        },
    )

    // Advertised effects may still fail to initialize on a vendor ROM. Keep recording without them.
    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(true)
                if (status == AudioEffect.SUCCESS && effect.enabled) {
                    Log.i(TAG, "microphone effect=$name enabled=true")
                    return effect
                }
                Log.w(TAG, "microphone effect=$name could not be enabled status=$status")
            } else {
                Log.i(TAG, "microphone effect=$name unavailable")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseEffect)
        return null
    }

    private fun releaseEffect(effect: AudioEffect) {
        try {
            effect.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect release failed", error)
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket, pendingBytes: ByteArray, pendingCount: Int) {
        // Conversion is needed when EITHER the rate or the channel count differs from
        // what the encoder expects — a 48 kHz STEREO capture must still be downmixed,
        // otherwise interleaved L/R samples get encoded as mono and sound like noise.
        val converting = (captureRate != 0 && captureRate != config.sampleRate) || captureChannels != config.channels
        // Bytes of HARDWARE-format PCM making up one 20 ms output frame. Compute with
        // full-precision intermediates: (captureRate / sampleRate) integer-divides to 0
        // for 16 kHz capture, which would silently make this frame 0 bytes long.
        val sourceFrameBytes = if (converting) {
            config.frameBytes * captureRate * captureChannels / (config.sampleRate * config.channels)
        } else {
            config.frameBytes
        }
        val sourceFrame = ByteArray(sourceFrameBytes)
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(sourceFrame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        val routeInfo = { routeType(recorder) }
        var filled = 0
        // Feed the probe bytes back in first so no audio is lost.
        if (pendingCount > 0) {
            val copied = minOf(sourceFrame.size, pendingCount)
            pendingBytes.copyInto(sourceFrame, 0, 0, copied)
            filled = copied
        }
        var zeroReads = 0L
        var lastDataNs = System.nanoTime()
        try {
            while (running.get()) {
                val recordingState = recorder.recordingState
                if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    stats.failure(MicrophoneFailureStage.RECORDING_STATE, code = recordingState)
                    return
                }
                stats.reading()
                val count = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                stats.read(count)
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    return
                }
                if (count == 0) {
                    zeroReads++
                    val elapsedSinceDataNs = System.nanoTime() - lastDataNs
                    if (elapsedSinceDataNs >= NO_DATA_TIMEOUT_NS) {
                        stats.noDataTimeout(
                            zeroReads = zeroReads,
                            elapsedMs = elapsedSinceDataNs / 1_000_000,
                            recordingState = recorder.recordingState,
                            source = recorder.audioSource,
                        )
                        stats.failure(MicrophoneFailureStage.NO_DATA_TIMEOUT)
                        return
                    }
                    Thread.sleep(ZERO_READ_DELAY_MILLIS)
                    stats.flush(routeType = routeInfo)
                    continue
                }
                zeroReads = 0
                lastDataNs = System.nanoTime()
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(sourceFrame.size - filled, count - offset)
                    readBuffer.copyInto(sourceFrame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == sourceFrame.size) {
                        if (converting) {
                            convertFrame(sourceFrame, sourceFrame.size, captureChannels, frame)
                            sendFrame(socket, counters, frame)
                        } else {
                            sendFrame(socket, counters, sourceFrame)
                        }
                        filled = 0
                    }
                }
                stats.flush(routeType = routeInfo)
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            stats.flush(ended = true, routeType = routeInfo)
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        stats.encoded(bodies.size, if (bodies.isEmpty()) 1 else bodies.count { it.isEmpty() })
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.samplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            stats.sent()
        } catch (error: Exception) {
            stats.sendFailed()
            if (running.get()) throw error
        }
    }

    private fun routeType(recorder: AudioRecord): Int? = runCatching { recorder.routedDevice?.type }.getOrNull()

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach(::releaseEffect)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        const val ZERO_READ_DELAY_MILLIS = 20L
        const val NO_DATA_TIMEOUT_NS = 5_000_000_000L
        /** Rate of the vendor voice assistant's permanently-open input thread. */
        const val FRAME_SHARE_RATE = 16_000
        const val PROBE_TIMEOUT_MS = 500L
        const val PROBE_POLL_MILLIS = 10L
        const val PROBE_BUFFER_BYTES = 4_096
        const val PROBE_MIN_BYTES = 320
        /** Whole-chain retries while the vendor mic handover settles (box retries 10x). */
        const val OPEN_CHAIN_ATTEMPTS = 3
        const val OPEN_CHAIN_RETRY_DELAY_MS = 250L
    }
}
