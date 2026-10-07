/*
 * Minimal usbfs helpers used to move an iPhone onto its CarPlay configuration.
 *
 * Android's UsbDeviceConnection.claimInterface() detaches a bound kernel driver, but releasing the
 * interface lets the kernel re-bind it immediately, so setConfiguration() keeps failing on head
 * units that auto-bind snd-usb-audio/usbhid to the iPhone's iPod configuration. USBDEVFS_DISCONNECT
 * unbinds the driver without claiming anything, which is what libusb does, and it works with the
 * file descriptor Android already handed to the app — no root and no device node permissions.
 */
#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <sys/ioctl.h>

#include <linux/usbdevice_fs.h>

#ifndef USBDEVFS_DISCONNECT
#define USBDEVFS_DISCONNECT _IO('U', 22)
#endif

#ifndef USBDEVFS_IOCTL
struct usbdevfs_ioctl {
    int ifno;
    int ioctl_code;
    void *data;
};
#define USBDEVFS_IOCTL _IOWR('U', 18, struct usbdevfs_ioctl)
#endif

#ifndef USBDEVFS_SETCONFIGURATION
#define USBDEVFS_SETCONFIGURATION 1
#endif

/** Returns 0 on success, otherwise -errno. */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbHostIoctl_disconnectKernelDriver(
        JNIEnv *env, jobject thiz, jint fd, jint interfaceNumber) {
    (void)env;
    (void)thiz;
    if (fd < 0) return -EBADF;
    if (ioctl((int)fd, USBDEVFS_DISCONNECT, (void *)(intptr_t)interfaceNumber) < 0) {
        return -errno;
    }
    return 0;
}

/** Returns 0 on success, otherwise -errno. */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbHostIoctl_setConfiguration(
        JNIEnv *env, jobject thiz, jint fd, jint configuration) {
    (void)env;
    (void)thiz;
    if (fd < 0) return -EBADF;
    struct usbdevfs_ioctl command;
    command.ifno = 0;
    command.ioctl_code = USBDEVFS_SETCONFIGURATION;
    command.data = (void *)(intptr_t)configuration;
    if (ioctl((int)fd, USBDEVFS_IOCTL, &command) < 0) {
        return -errno;
    }
    return 0;
}
