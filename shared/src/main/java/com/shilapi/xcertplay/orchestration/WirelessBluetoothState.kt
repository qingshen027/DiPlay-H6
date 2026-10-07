package com.shilapi.xcertplay.orchestration

/**
 * Shared flag for the wireless bootstrap: while a wireless CarPlay session is running the adapter
 * must stay OFF, otherwise the factory A2DP/AVRCP stack takes the audio channel back and CarPlay
 * plays no sound.
 *
 * [CarPlayController] owns the flag (suspend/resume around a session). The boot listener
 * (`CarRadioBringUp`, which switches Bluetooth on so the iPhone can join) reads it and must not
 * switch the adapter back on while the session is suspended.
 */
object WirelessBluetoothState {
    @Volatile var suspendedForSession: Boolean = false
}
