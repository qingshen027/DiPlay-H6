package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Exact Apple USB identities allowed by the deployment configuration. */
class IphoneUsbMatcher private constructor(
    private val allowedDevices: Set<UsbDeviceId>?,
    private val allowAnyAppleProduct: Boolean,
) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(
        allowedDevices.toSet(),
        allowAnyAppleProduct = false,
    )

    init {
        require(allowedDevices == null || allowedDevices.isNotEmpty()) {
            "At least one iPhone USB identity is required when not using Apple-vendor discovery"
        }
        require(allowedDevices == null || allowedDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
    }

    fun matches(vendorId: Int, productId: Int): Boolean =
        if (allowAnyAppleProduct) vendorId == APPLE_VENDOR_ID
        else UsbDeviceId(vendorId, productId) in allowedDevices.orEmpty()

    companion object {
        /** Apple VID used by LIVI commit 0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3. */
        const val APPLE_VENDOR_ID = 0x05ac

        /** Discovers every Apple device, matching only the vendor ID confirmed by LIVI. */
        fun appleVendor(): IphoneUsbMatcher = IphoneUsbMatcher(null, allowAnyAppleProduct = true)
    }
}

/**
 * Android USB Host bring-up boundary for a configured iPhone identity.
 *
 * LIVI's fixed commit uses Apple vendor request `0x52`, value `0`, index `4`, and then selects
 * configuration `6`. The vendor request can make the iPhone re-enumerate. Android does not offer
 * Linux sysfs configuration control or a synchronous re-enumeration primitive, so this class
 * closes the first connection and requires the caller to receive, re-authorize, and pass the new
 * [UsbDevice] to [selectCarPlayConfigurationAsync]. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
) {
    private val appContext = context.applicationContext

    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class TransitionResult {
        /** The connection was closed; wait for a new matching attached device before continuing. */
        data object ReenumerationRequested : TransitionResult()

        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }

    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    @Throws(IphoneUsbException::class)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)

        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(device)
    }

    /** Returns null for unrelated broadcasts, malformed results, or non-configured devices. */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            PermissionResult.Granted(device)
        } else {
            PermissionResult.Denied(device)
        }
    }

    /** Returns the new matching device after the vendor request caused Android USB re-enumeration. */
    fun parseAttachedDevice(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device = intent.usbDevice() ?: return null
        return device.takeIf { matcher.matches(it.vendorId, it.productId) }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable =
        registerReceiver(IntentFilter(permissionAction)) { parsePermissionResult(it)?.let(onResult) }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerAttachReceiver(onAttached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            parseAttachedDevice(it)?.let(onAttached)
        }

    /**
     * Sends the LIVI-evidenced vendor request then closes the connection before re-enumeration.
     * The callback is invoked from [executor].
     */
    fun requestCarPlayReenumerationAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (TransitionResult) -> Unit,
    ) {
        executor.execute {
            callback(runTransition(device) { connection ->
                val response = ByteArray(VENDOR_RESPONSE_LENGTH)
                val transferred = connection.controlTransfer(
                    USB_VENDOR_DEVICE_IN,
                    CARPLAY_CONFIGURATION_REQUEST,
                    0,
                    CARPLAY_CONFIGURATION_INDEX,
                    response,
                    response.size,
                    CONTROL_TRANSFER_TIMEOUT_MILLIS,
                )
                if (transferred != response.size) {
                    throw IphoneUsbException.Protocol(
                        "CarPlay configuration request transferred $transferred of ${response.size} bytes",
                    )
                }
                TransitionResult.ReenumerationRequested
            })
        }
    }

    /**
     * Opens LIVI's USBMUX bulk pipe on the re-enumerated iPhone.
     *
     * This repeats CarPlay configuration selection on the newly opened Android connection and
     * claims the Apple USB Multiplexor interface, preferring the LIVI bulk pair 0x04/0x85.
     * After a successful callback, it owns the returned session and must close it. If the callback
     * throws, this method closes the session before propagating the callback failure.
     */
    fun openIap2UsbSessionAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (Iap2SessionResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(device))
            } catch (error: IphoneUsbException) {
                Iap2SessionResult.Failed(error)
            } catch (error: SecurityException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.PermissionDenied("USB permission was denied", error),
                )
            } catch (error: RuntimeException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("iPhone USBMUX operation failed", error),
                )
            }
            try {
                callback(result)
            } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) {
                    try {
                        result.session.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }

    private fun runTransition(
        device: UsbDevice,
        operation: (UsbDeviceConnection) -> TransitionResult,
    ): TransitionResult = try {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        try {
            operation(connection)
        } finally {
            connection.close()
        }
    } catch (error: IphoneUsbException) {
        TransitionResult.Failed(error)
    } catch (error: SecurityException) {
        TransitionResult.Failed(IphoneUsbException.PermissionDenied("USB permission was denied", error))
    } catch (error: RuntimeException) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("iPhone USB operation failed", error))
    }

    private fun openIap2UsbSession(device: UsbDevice): Iap2UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        // Use only Android's USB Host permission and APIs. Do not invoke su here:
        // distributable builds must work without root. A vendor kernel that has
        // claimed the interfaces may still reject setConfiguration().
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        var claimedInterface: UsbInterface? = null
        try {
            val configuration = IphoneCarPlayConfiguration.find(device)
                ?: throw IphoneUsbException.Protocol(
                    "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                )
            // H6 head units (and any car with a vendor iPod/CarPlay service) leave the iPhone in the
            // iPod configuration with snd-usb-audio/usbhid bound to its interfaces. The descriptors
            // still advertise the CarPlay configuration, so a naive "the descriptor exists" check
            // claims interfaces that are not active and every subsequent claim fails. Read the
            // active configuration over the standard GET_CONFIGURATION request: it needs no
            // permission and tells us whether the iPhone really switched.
            val activeBefore = readActiveConfiguration(connection)
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usb active configuration=$activeBefore target=${configuration.id}",
            )
            if (activeBefore != null && activeBefore != configuration.id) {
                // Head units with an MFi accessory path (H6) make the iPhone enumerate in the iPod
                // configuration, and the kernel auto-binds snd-usb-audio/usbhid to its interfaces,
                // after which setConfiguration() refuses to switch. USBDEVFS_DISCONNECT unbinds
                // those drivers without claiming anything, which frees the device long enough to
                // switch. Everything runs on the descriptor Android gave this app: no root, no
                // /dev/bus/usb access, no external script.
                val fileDescriptor = runCatching { connection.fileDescriptor }.getOrDefault(-1)
                val ioctl = if (fileDescriptor >= 0) runCatching { UsbHostIoctl() }.getOrNull() else null
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "usb switch fd=$fileDescriptor usbfsIoctl=${ioctl != null}",
                )
                detachActiveConfigurationDrivers(connection, device, activeBefore, ioctl, fileDescriptor)
                val switched = if (ioctl != null) {
                    val result = ioctl.setConfiguration(fileDescriptor, configuration.id)
                    if (result != 0) {
                        Log.w(
                            IphoneCarPlayConfiguration.TAG,
                            "usbfs setConfiguration ${configuration.id} errno=${-result}",
                        )
                    }
                    result == 0
                } else {
                    connection.setConfiguration(configuration)
                }
                if (!switched) {
                    Log.w(
                        IphoneCarPlayConfiguration.TAG,
                        "setConfiguration ${configuration.id} from active $activeBefore reported failure",
                    )
                }
            }
            val usbMux = IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
                ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usbmux config=${configuration.id} iface=${usbMux.id} alt=${usbMux.alternateSetting} " +
                    "class=${usbMux.interfaceClass}/${usbMux.interfaceSubclass}/${usbMux.interfaceProtocol} " +
                    "endpoints=${usbMux.endpointCount} " +
                    "out=${describeUsbEndpoint(endpoints.first)} " +
                    "in=${describeUsbEndpoint(endpoints.second)}",
            )
            val activeAfter = readActiveConfiguration(connection)
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usb active configuration after switch=$activeAfter target=${configuration.id}",
            )
            if (activeAfter != null && activeAfter != configuration.id) {
                throw IphoneUsbException.ConfigurationNotActive(
                    "iPhone is still on USB configuration $activeAfter; " +
                        "CarPlay configuration ${configuration.id} is not active",
                )
            }
            if (!connection.claimInterface(usbMux, true)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not claim USBMUX interface 1")
            }
            claimedInterface = usbMux
            return Iap2UsbSession(connection, endpoints.first, endpoints.second)
        } catch (error: Throwable) {
            if (claimedInterface != null) connection.releaseInterface(claimedInterface)
            connection.close()
            throw error
        }
    }

    /**
     * Reads the configuration the iPhone is actually running (standard GET_CONFIGURATION).
     * Returns null when the transfer is unavailable, in which case callers keep the previous
     * descriptor-only behaviour instead of failing the connection.
     */
    private fun readActiveConfiguration(connection: UsbDeviceConnection): Int? {
        val buffer = ByteArray(1)
        return try {
            val transferred = connection.controlTransfer(
                USB_STANDARD_DEVICE_TO_HOST,
                USB_REQUEST_GET_CONFIGURATION,
                0,
                0,
                buffer,
                buffer.size,
                CONTROL_TRANSFER_TIMEOUT_MILLIS,
            )
            if (transferred == buffer.size) buffer[0].toInt() and 0xff else null
        } catch (error: RuntimeException) {
            Log.w(
                IphoneCarPlayConfiguration.TAG,
                "could not read active USB configuration: ${error.message}",
            )
            null
        }
    }

    /**
     * Claims and releases every interface of the configuration the iPhone is currently running so
     * that any bound kernel driver is detached before the configuration switch. Returns the number
     * of interfaces that were successfully claimed.
     */
    private fun detachActiveConfigurationDrivers(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        activeConfigurationId: Int,
        ioctl: UsbHostIoctl?,
        fileDescriptor: Int,
    ): Int {
        val active = (0 until device.configurationCount).map(device::getConfiguration)
            .firstOrNull { it.id == activeConfigurationId } ?: return 0
        var detached = 0
        val seen = HashSet<Int>()
        for (index in 0 until active.interfaceCount) {
            val usbInterface = active.getInterface(index)
            if (!seen.add(usbInterface.id)) continue
            if (ioctl != null) {
                val result = ioctl.disconnectKernelDriver(fileDescriptor, usbInterface.id)
                if (result == 0) {
                    detached++
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "usbfs disconnect iface=${usbInterface.id} class=${usbInterface.interfaceClass}",
                    )
                } else {
                    Log.w(
                        IphoneCarPlayConfiguration.TAG,
                        "usbfs disconnect iface=${usbInterface.id} errno=${-result}",
                    )
                }
                continue
            }
            // Fallback when the usbfs helper is unavailable: a claim detaches the driver too, but the
            // kernel may re-bind it on release, so this is best effort only.
            if (connection.claimInterface(usbInterface, true)) {
                detached++
                runCatching { connection.releaseInterface(usbInterface) }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "detach claim iface=${usbInterface.id} class=${usbInterface.interfaceClass}",
                )
            }
        }
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "detached $detached interface(s) of active configuration $activeConfigurationId",
        )
        return detached
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw IphoneUsbException.DeviceUnavailable("USB device is not a configured iPhone identity")
        }
    }

    private fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun registerReceiver(filter: IntentFilter, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        val registered = AtomicBoolean(true)
        return Closeable {
            if (registered.compareAndSet(true, false)) appContext.unregisterReceiver(receiver)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    companion object {
        private const val USB_VENDOR_DEVICE_IN = 0xc0
        private const val USB_STANDARD_DEVICE_TO_HOST = 0x80
        private const val USB_REQUEST_GET_CONFIGURATION = 0x08
        private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        private const val CARPLAY_CONFIGURATION_INDEX = 0x0004
        private const val VENDOR_RESPONSE_LENGTH = 1
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000
    }

}

/**
 * A blocking, full-duplex USBMUX pipe. A null [read] result means only that its timeout elapsed.
 *
 * All operations must run off the Android main thread. The session does not parse iAP2 frames.
 */
class Iap2UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    // Bulk IN reuses one async request and one direct buffer for the life of the session.
    // Allocating them per read costs a JNI init/mmap, a 16 KB direct buffer and a close/munmap
    // every call; measured on the H6 head unit that is 15-500 cycles a second during a live
    // CarPlay session (27 510 init / 27 504 close lines in four minutes) and shows up as ~350k
    // minor faults and 27 % CPU. A timed-out request stays queued, so no data is lost between
    // calls, and the cancel + extra requestWait() that used to drain it disappears entirely.
    private val readBuffer = ByteBuffer.allocateDirect(USBMUX_EFFECTIVE_CHUNK_BYTES)
    private var readRequest: UsbRequest? = null
    private var readQueued = false

    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (data.isEmpty()) return@synchronized
        val transferred = connection.bulkTransfer(outEndpoint, data, data.size, timeoutMillis)
        if (transferred != data.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "USBMUX write transferred $transferred of ${data.size} bytes",
            )
        }
    }

    /** Returns null only when no completed USB request arrives before [timeoutMillis]. */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        var result: ByteArray? = null
        var remainingMillis = timeoutMillis
        while (remainingMillis > 0 && result == null) {
            val request = try {
                // Publish and queue atomically with close(), so detach cannot miss a new request.
                synchronized(stateLock) {
                    checkOpenLocked()
                    val current = readRequest ?: UsbRequest().also {
                        if (!it.initialize(connection, inEndpoint)) {
                            it.close()
                            throw failSession(
                                "Android could not initialize USBMUX read request (${requestDiagnostics(timeoutMillis)})",
                            )
                        }
                        readRequest = it
                    }
                    if (!readQueued) {
                        readBuffer.clear()
                        if (!current.queue(readBuffer)) {
                            throw failSession(
                                "Android could not queue USBMUX read request (${requestDiagnostics(timeoutMillis, readBuffer.capacity())})",
                            )
                        }
                        readQueued = true
                    }
                    current
                }
            } catch (error: IphoneUsbException) {
                throw error
            } catch (error: RuntimeException) {
                // H6 debug: surface the wrapped cause; the head-unit log only prints the wrapper.
                throw failSession(
                    "USBMUX read failed: ${error.javaClass.simpleName}: ${error.message} (${requestDiagnostics(timeoutMillis)})",
                    error,
                )
            }
            remainingMillis =
                (deadlineNanos - System.nanoTime() + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
            if (remainingMillis <= 0) break
            val completed = try {
                connection.requestWait(remainingMillis)
            } catch (_: TimeoutException) {
                // Nothing arrived yet; the request stays queued for the next call. USBMUX owns
                // authoritative detach/failure detection for the same phone.
                break
            } ?: throw failSession("Android returned no USBMUX read request")
            if (completed !== request) throw failSession("Android completed an unexpected USB request")
            readQueued = false
            val transferred = readBuffer.position()
            if (transferred <= 0) continue
            readBuffer.flip()
            result = ByteArray(transferred).also { readBuffer.get(it) }
        }
        result
    }

    override fun close() {
        val requestToClose = synchronized(stateLock) {
            if (closed) return
            closed = true
            readRequest
        }
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        runCatching { requestToClose?.cancel() }
        connection.close()
        runCatching { requestToClose?.close() }
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun requestDiagnostics(timeoutMillis: Long, bufferBytes: Int? = null): String = buildString {
        append("api=").append(Build.VERSION.SDK_INT)
        append(" endpoint=").append(describeUsbEndpoint(inEndpoint))
        append(" timeoutMs=").append(timeoutMillis)
        if (bufferBytes != null) append(" bufferBytes=").append(bufferBytes)
    }

    private companion object {
        const val USBMUX_READ_CHUNK_BYTES = 65_536

        /**
         * API 26-27 caps UsbRequest.queue() buffers at 16384 bytes ("number of
         * remaining bytes is out of range of [0, 16384]"); the limit was lifted in
         * API 28. H6 head units run API 27, so chunk reads there. The USBMUX frame
         * assembler tolerates arbitrary chunk boundaries.
         */
        val USBMUX_EFFECTIVE_CHUNK_BYTES =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) 16_384 else USBMUX_READ_CHUNK_BYTES

        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

private fun describeUsbEndpoint(endpoint: UsbEndpoint): String =
    "0x${endpoint.address.toString(16)}(direction=${endpoint.direction}," +
        "type=${endpoint.type},maxPacket=${endpoint.maxPacketSize})"

/** USB bring-up failures that precede iAP2 and are distinct from MFi I2C failures. */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    /** The iPhone descriptors advertise CarPlay but the device is still on another configuration. */
    class ConfigurationNotActive(message: String, cause: Throwable? = null) :
        IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}
