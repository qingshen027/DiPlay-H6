package com.shilapi.xcertplay.transport

/**
 * usbfs operations that Android's UsbDeviceConnection does not expose.
 *
 * [disconnectKernelDriver] unbinds a kernel driver from an interface without claiming it, which is
 * what libusb does before switching configurations. Android only offers that behaviour implicitly
 * through claimInterface(), and releasing the interface lets the kernel re-bind the driver straight
 * away, so setConfiguration() keeps failing on head units that auto-bind snd-usb-audio/usbhid to the
 * iPhone's iPod configuration.
 *
 * Both calls work on the file descriptor Android already handed to the app, so they need no root and
 * no access to /dev/bus/usb.
 */
internal class UsbHostIoctl {
    init {
        System.loadLibrary("xcertplay_usbioctl")
    }

    /** Returns 0 on success, otherwise -errno. */
    external fun disconnectKernelDriver(fileDescriptor: Int, interfaceNumber: Int): Int

    /** Returns 0 on success, otherwise -errno. */
    external fun setConfiguration(fileDescriptor: Int, configuration: Int): Int
}
