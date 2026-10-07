package com.shilapi.xcertplay.transport

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Opt-in root assistance for wired CarPlay on head units that hijack the iPhone's USB configuration.
 *
 * Haval H6 / Desay SV (and any head unit with a vendor MFi/iPod path) parks the iPhone on USB
 * configuration 2 (iPod) and lets the kernel bind snd-usb-audio and usbhid to its interfaces. Two
 * consequences follow, both confirmed on the device:
 *
 *  - Android hands the app no usable usbfs descriptor here (every USBDEVFS_* ioctl on
 *    `UsbDeviceConnection.fileDescriptor` returns ENOTTY/25), so `UsbDeviceConnection.setConfiguration()`
 *    can never switch the iPhone from inside the app, with or without drivers attached.
 *  - Root does not need usbfs either: this kernel exposes
 *    /sys/bus/usb/devices/<dev>/bConfigurationValue as writable, and writing 6 performs the switch
 *    through the USB core. It happens in place - devnum and the sysfs path stay put - so Android
 *    keeps the same UsbDevice, no re-enumeration and no new permission prompt.
 *
 * So the whole root job is: drop the drivers that hold the current configuration, then write 6.
 * After that the app claims USBMUX/NCM and runs the entire iAP2 + AirPlay stack itself.
 *
 * Rules this class follows:
 *  - It is only ever called after the user explicitly turns on the wired USB option.
 *  - Every call goes through `su`, so Magisk/Superuser shows its own grant prompt; nothing is
 *    executed silently and a root-less device simply fails and falls back to the car hotspot.
 *  - The distributable APK keeps working without root; this is an enhancement, never a requirement.
 */
object UsbRootBridge {
    private const val TAG = "xcertplay-usb"
    private const val ROOT_TIMEOUT_SECONDS = 25L
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_USB_ROOT_ASSIST = "usb_root_assist"
    private const val CARPLAY_CONFIGURATION = "6"
    /** A prepare this recent is still in effect; see [prepareIphoneForCarPlay]. */
    private const val PREPARE_REUSE_MILLIS = 8_000L

    @Volatile private var rootGranted: Boolean? = null
    @Volatile private var lastPrepareSucceeded = false
    @Volatile private var lastPrepareMillis = 0L

    /** Reads the same preference the settings screen writes. Defaults to off: root is opt-in. */
    fun isEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_USB_ROOT_ASSIST, false)

    /**
     * True when a root grant is available. The answer is cached once granted: every `su` call makes
     * the superuser app post its own "granted superuser rights" toast, so the number of calls is
     * what the user actually sees. A refusal is never cached, so a later grant still takes effect.
     */
    fun isRootAvailable(): Boolean {
        rootGranted?.let { return it }
        val result = runSu("id")
        val granted = result.exitCode == 0 && result.output.contains("uid=0")
        if (granted) rootGranted = true
        return granted
    }

    /**
     * Moves a plugged iPhone onto its CarPlay USB configuration (6) and returns true when it ends
     * up there. Safe to call repeatedly: an iPhone already on 6 is reported ready without touching
     * anything, so a live session is never disturbed.
     */
    fun prepareIphoneForCarPlay(): Boolean {
        // One connection attempt runs this twice (the activity gate, then the controller). Each run
        // is another superuser toast, so a freshly completed prepare is reused for a short window.
        // Unplugging and re-plugging takes far longer than this, so a re-plug still re-prepares.
        val elapsed = android.os.SystemClock.elapsedRealtime() - lastPrepareMillis
        if (lastPrepareSucceeded && elapsed in 0..PREPARE_REUSE_MILLIS) {
            Log.i(TAG, "root prepare skipped: completed ${elapsed}ms ago")
            return true
        }
        val result = runSu(PREPARE_SCRIPT)
        val output = result.output.trim()
        Log.i(TAG, "root prepare exit=${result.exitCode} out=${output.take(300)}")
        if (result.exitCode != 0) {
            lastPrepareSucceeded = false
            return false
        }
        // "carplay-ready" = already on 6, "switched <dev> 2->6" = switched just now.
        val prepared = output.contains("carplay-ready") || output.contains("->$CARPLAY_CONFIGURATION")
        lastPrepareSucceeded = prepared
        if (prepared) lastPrepareMillis = android.os.SystemClock.elapsedRealtime()
        return prepared
    }

    private data class SuResult(val exitCode: Int, val output: String)

    private fun runSu(command: String): SuResult = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val finished = process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            Log.w(TAG, "su timed out after ${ROOT_TIMEOUT_SECONDS}s")
            SuResult(-1, "timeout")
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() } +
                process.errorStream.bufferedReader().use { it.readText() }
            SuResult(process.exitValue(), output)
        }
    }.getOrElse { error ->
        Log.w(TAG, "su unavailable: ${error.message}")
        SuResult(-1, error.message.orEmpty())
    }

    /**
     * For every Apple device (idVendor 05ac) that is not already on the CarPlay configuration:
     * unbinds the driver of each interface of the configuration it is running, then writes the
     * CarPlay configuration. Written for /system/bin/sh (Android 8.1 toybox): no arrays, no `[[`,
     * no `$(( ))` arithmetic beyond the counter.
     */
    private val PREPARE_SCRIPT = """
for d in /sys/bus/usb/devices/[0-9]*-[0-9]*; do
  [ -f "${'$'}d/idVendor" ] || continue
  [ "$(cat "${'$'}d/idVendor" 2>/dev/null)" = "05ac" ] || continue
  dev=$(basename "${'$'}d")
  cfg=$(cat "${'$'}d/bConfigurationValue" 2>/dev/null)
  if [ "${'$'}cfg" = "6" ]; then
    echo "carplay-ready ${'$'}dev"
    continue
  fi
  # One attempt only. When the USB core refuses the switch (another process still holds the
  # interfaces through usbfs, or the phone has not settled yet) it keeps refusing for seconds:
  # measured on the H6, four writes spread over 4.9 s all failed and only the fifth took. Retrying
  # inside this call therefore only adds latency. The controller already notices the stale
  # configuration when it opens the data paths and runs this again, which is what actually works.
  n=0
  for i in /sys/bus/usb/devices/${'$'}dev:${'$'}cfg.*; do
    [ -e "${'$'}i/driver/unbind" ] || continue
    echo "${'$'}{i##*/}" > "${'$'}i/driver/unbind" 2>/dev/null
    n=${'$'}((${'$'}n + 1))
  done
  echo 6 > "${'$'}d/bConfigurationValue" 2>/dev/null
  sleep 1
  echo "switched ${'$'}dev ${'$'}cfg->$(cat "${'$'}d/bConfigurationValue" 2>/dev/null) drivers=${'$'}n"
done
    """.trimIndent()
}
