package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.SafeAreaCodec
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.File

/** SharedPreferences persistence for the accessory identity and paired controllers. */
object AirPlayPersistence {
    /** 0 uses usage-based routing; 1–20 select stream types supported by the head unit. */
    val AUDIO_CHANNELS = 0..20

    /**
     * Haval H6 / Desay SV defaults.
     *
     * With usage-based routing (0) this head unit sends CarPlay media to one output bus and
     * navigation guidance to the built-in speaker on USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, so the
     * car's volume control never reaches the guidance voice. Legacy stream 3 is the music bus the
     * vehicle volume knob drives and legacy stream 11 is the navigation/TTS bus. Both are baked in
     * as defaults because the setting used to be wiped by a reinstall, which left navigation audio
     * unadjustable on a fresh install.
     */
    private const val DEFAULT_MEDIA_AUDIO_CHANNEL = 3
    private const val DEFAULT_NAVIGATION_AUDIO_CHANNEL = 11
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_IDENT_PRIVATE = "identity_private"
    private const val KEY_IDENT_PUBLIC = "identity_public"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PAIRING_IDS = "pairing_ids"
    private const val KEY_LOCKDOWN_HOST_ID = "lockdown_host_id"
    private const val KEY_LOCKDOWN_SYSTEM_BUID = "lockdown_system_buid"
    private const val KEY_LOCKDOWN_WIFI_MAC = "lockdown_wifi_mac"
    private const val KEY_LOCKDOWN_DEVICE_PUBLIC = "lockdown_device_public"
    private const val KEY_LOCKDOWN_DEVICE_CERT = "lockdown_device_cert"
    private const val KEY_LOCKDOWN_HOST_PRIVATE = "lockdown_host_private"
    private const val KEY_LOCKDOWN_HOST_CERT = "lockdown_host_cert"
    private const val KEY_LOCKDOWN_ROOT_PRIVATE = "lockdown_root_private"
    private const val KEY_LOCKDOWN_ROOT_CERT = "lockdown_root_cert"
    private const val KEY_DISPLAY_SCALE_TENTHS = "display_scale_tenths"
    private const val KEY_UI_SCALE_PERCENT = "ui_scale_percent"
    private const val KEY_HEVC_ENABLED = "hevc_enabled"
    private const val KEY_HEVC_SOFTWARE_DECODER = "hevc_software_decoder"
    private const val KEY_ADVANCED_AUDIO_CHANNEL_MAPPING = "advanced_audio_channel_mapping"
    private const val KEY_AUDIO_FOCUS_ENABLED = "audio_focus_enabled"
    private const val KEY_MEDIA_AUDIO_CHANNEL = "media_audio_channel"
    private const val KEY_NAVIGATION_AUDIO_CHANNEL = "navigation_audio_channel"
    private const val KEY_NAVIGATION_STREAM_TYPE = "navigation_stream_type"
    private const val KEY_WIRELESS_ENABLED = "wireless_enabled"
    private const val KEY_USB_ROOT_ASSIST = "usb_root_assist"
    private const val KEY_WIRELESS_HOTSPOT_MODE = "wireless_hotspot_mode"
    private const val KEY_WIFI_P2P_PREFERRED_CHANNEL = "wifi_p2p_preferred_channel"
    private const val KEY_MANUAL_HOTSPOT_SSID = "manual_hotspot_ssid"
    private const val KEY_MANUAL_HOTSPOT_PASSPHRASE = "manual_hotspot_passphrase"
    private const val KEY_MANUAL_HOTSPOT_BAND = "manual_hotspot_band"
    private const val KEY_MANUAL_HOTSPOT_CHANNEL = "manual_hotspot_channel"
    private const val KEY_MANUAL_HOTSPOT_SECURITY = "manual_hotspot_security"
    private const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"
    private const val KEY_DISCLAIMER_ACCEPTED_VERSION = "disclaimer_accepted_version"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_MODEL = "model"
    private const val KEY_OEM_LABEL = "oem_label"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MEDIA_BUFFER_MS = "media_buffer_ms"
    private const val KEY_CLUSTER_MAP = "cluster_map_enabled"
    private const val KEY_CENTER_MAP_OVERLAY = "center_map_overlay"
    private const val KEY_LAUNCHER_MAP_SHARING = "launcher_map_sharing"
    private const val KEY_CLUSTER_MAP_SCALE = "cluster_map_scale_percent"
    private const val KEY_CLUSTER_CONTENT = "cluster_content"
    private const val KEY_CLUSTER_MARKER_X = "cluster_marker_horizontal_step"
    private const val KEY_CLUSTER_MARKER_Y = "cluster_marker_vertical_step"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION = "cluster_turn_card_overlay_position"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE = "cluster_turn_card_overlay_size"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_X = "cluster_turn_card_overlay_x_percent"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_Y = "cluster_turn_card_overlay_y_percent"
    private const val KEY_CENTER_MAP_FOLLOWS_DASHBOARD = "center_map_follows_dashboard"
    private const val KEY_SETTINGS_GESTURE_FINGERS = "settings_gesture_fingers"
    private const val KEY_WIDTH_PHYSICAL_MM = "display_width_physical_mm"
    private const val KEY_PHYSICAL_SIZE_BASIS = "display_physical_size_basis"
    private const val KEY_MAX_DETECTED_WIDTH = "display_max_detected_width"
    private const val KEY_MAX_DETECTED_HEIGHT = "display_max_detected_height"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_HIDE_TOP_BAR = "hide_top_bar"
    private const val KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar"
    private const val KEY_SAFE_AREA_DRAW_OUTSIDE = "safe_area_draw_outside"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_WIRELESS_AUTO_LAUNCH = "wireless_auto_launch"
    private const val KEY_LOCATION_REPORTING_ENABLED = "location_reporting_enabled"
    private const val KEY_MFI_TARGET = "mfi_target"
    private const val KEY_MFI_I2C_PATH = "mfi_i2c_path"
    private const val KEY_REMOTE_MFI_SERVER = "remote_mfi_server"
    private const val KEY_REMOTE_MFI_TOKEN = "remote_mfi_token"
    private const val SAFE_AREA_KEY_PREFIX = "safe_area_"
    private const val CUSTOM_ICON_FILE = "airplay-icon.png"
    private const val KEY_DESAYSV_CLUSTER_ENABLED = "desaysv_cluster_enabled"
    private const val KEY_DESAYSV_CLUSTER_X = "desaysv_cluster_x"
    private const val KEY_DESAYSV_CLUSTER_Y = "desaysv_cluster_y"
    private const val KEY_DESAYSV_CLUSTER_W = "desaysv_cluster_w"
    private const val KEY_DESAYSV_CLUSTER_H = "desaysv_cluster_h"
    private const val KEY_DESAYSV_CLUSTER_SCALE = "desaysv_cluster_scale"
    private const val KEY_DESAYSV_CLUSTER_MARGIN = "desaysv_cluster_margin"
    private const val KEY_DESAYSV_STRETCH = "desaysv_stretch"
    private const val KEY_DESAYSV_PREVIEW_ON_MAIN = "desaysv_preview_on_main"
    private const val KEY_AUTO_PLAY_ON_CONNECT = "auto_play_on_connect"
    private const val KEY_DARK_MODE_FOLLOW_HEADLIGHT = "dark_mode_follow_headlight"

    const val DEFAULT_MANUFACTURER = "DiPlay"
    const val DEFAULT_MODEL = "DiPlay"
    const val DEFAULT_OEM_LABEL = "HAVAL"
    const val DEFAULT_MFI_I2C_PATH = "/dev/i2c-1"

    /** Applied by the CarPlay host so overlay position/size updates without reconnecting. */
    @Volatile var overlaySettingsListener: (() -> Unit)? = null

    fun loadDisplayScaleTenths(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CarPlayDisplayScale.sanitize(
            prefs.getInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.DEFAULT_TENTHS),
        )
    }

    fun saveDisplayScaleTenths(context: Context, tenths: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.sanitize(tenths))
            .apply()
    }

    fun loadHevcEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_ENABLED, false)

    fun loadUiScalePercent(context: Context): Int = CarPlayUiScale.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.DEFAULT),
    )

    fun saveUiScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.sanitize(percent)).apply()
    }

    fun saveHevcEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_ENABLED, enabled)
            .apply()
    }

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_SOFTWARE_DECODER, false)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_SOFTWARE_DECODER, enabled)
            .apply()
    }

    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, false)

    fun saveAdvancedAudioChannelMapping(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, enabled)
            .apply()
    }

    fun loadNavigationStreamType(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NAVIGATION_STREAM_TYPE, 14)

    fun saveNavigationStreamType(context: Context, streamType: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_STREAM_TYPE, streamType)
            .apply()
    }

    fun loadAudioFocusEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS_ENABLED, false)

    fun saveAudioFocusEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_FOCUS_ENABLED, enabled)
            .apply()
    }

    fun loadMediaAudioChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_AUDIO_CHANNEL, DEFAULT_MEDIA_AUDIO_CHANNEL)
            .takeIf { it in AUDIO_CHANNELS } ?: DEFAULT_MEDIA_AUDIO_CHANNEL

    fun saveMediaAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_AUDIO_CHANNEL, channel.takeIf { it in AUDIO_CHANNELS } ?: 0)
            .apply()
    }

    fun loadNavigationAudioChannel(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Inherit the legacy value only when the new key is absent; preserve explicit 0 (usage
        // routing) and fall back to the head-unit default on a fresh install.
        return prefs.getInt(
            KEY_NAVIGATION_AUDIO_CHANNEL,
            prefs.getInt(KEY_NAVIGATION_STREAM_TYPE, DEFAULT_NAVIGATION_AUDIO_CHANNEL),
        ).takeIf { it in AUDIO_CHANNELS } ?: DEFAULT_NAVIGATION_AUDIO_CHANNEL
    }

    fun saveNavigationAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_AUDIO_CHANNEL, channel.takeIf { it in AUDIO_CHANNELS } ?: 0)
            .apply()
    }

    fun loadWirelessEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIRELESS_ENABLED, true)

    fun saveWirelessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_ENABLED, enabled)
            .apply()
    }

    /** Opt-in root assistance for wired CarPlay. Off by default: root must never be implicit. */
    fun loadUsbRootAssist(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USB_ROOT_ASSIST, false)

    fun saveUsbRootAssist(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_USB_ROOT_ASSIST, enabled)
            .apply()
    }

    fun loadMfiTarget(context: Context): MfiTarget {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_TARGET, null)
        return MfiTarget.entries.firstOrNull { it.name == stored } ?: MfiTarget.LOCAL
    }

    fun saveMfiTarget(context: Context, target: MfiTarget) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_TARGET, target.name)
            .apply()
    }

    fun loadMfiI2cPath(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_I2C_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MFI_I2C_PATH

    fun saveMfiI2cPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_I2C_PATH, path.trim())
            .apply()
    }

    fun loadRemoteMfiServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_SERVER, null)
            .orEmpty()

    fun saveRemoteMfiServer(context: Context, server: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_SERVER, server)
            .apply()
    }

    fun loadRemoteMfiToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_TOKEN, null)
            .orEmpty()

    fun saveRemoteMfiToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_TOKEN, token)
            .apply()
    }

    fun loadWirelessHotspotMode(context: Context): WirelessHotspotMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_WIRELESS_HOTSPOT_MODE, null)
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored }
            ?: WirelessHotspotMode.MANUAL
        val supported = if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT ||
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && mode == WirelessHotspotMode.WIFI_P2P)
        ) WirelessHotspotMode.MANUAL else mode
        if (stored != supported.name) saveWirelessHotspotMode(context, supported)
        return supported
    }

    fun saveWirelessHotspotMode(context: Context, mode: WirelessHotspotMode) {
        val supported = if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) WirelessHotspotMode.MANUAL else mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WIRELESS_HOTSPOT_MODE, supported.name)
            .apply()
    }

    fun loadWifiP2pPreferredChannel(context: Context): Int = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WIFI_P2P_PREFERRED_CHANNEL, WifiP2pChannels.AUTO)
            .takeIf(WifiP2pChannels::isValid) ?: WifiP2pChannels.AUTO
    }.getOrDefault(WifiP2pChannels.AUTO)

    fun saveWifiP2pPreferredChannel(context: Context, channel: Int) {
        require(WifiP2pChannels.isValid(channel))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_WIFI_P2P_PREFERRED_CHANNEL, channel).apply()
    }

    fun loadManualHotspotSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_SSID, null)
            .orEmpty()

    fun saveManualHotspotSsid(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SSID, ssid)
            .apply()
    }

    fun loadManualHotspotPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_PASSPHRASE, null)
            .orEmpty()

    fun saveManualHotspotPassphrase(context: Context, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_PASSPHRASE, passphrase)
            .apply()
    }

    fun loadManualHotspotBand(context: Context): ManualHotspotBand {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_BAND, null)
        return ManualHotspotBand.entries.firstOrNull { it.name == stored }
            ?: ManualHotspotBand.AUTO
    }

    fun saveManualHotspotBand(context: Context, band: ManualHotspotBand) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_BAND, band.name)
            .apply()
    }

    fun loadManualHotspotChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MANUAL_HOTSPOT_CHANNEL, 0)
            .coerceIn(0, 196)

    fun saveManualHotspotChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MANUAL_HOTSPOT_CHANNEL, channel.coerceIn(0, 196))
            .apply()
    }

    fun loadManualHotspotSecurity(context: Context): ManualHotspotSecurity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MANUAL_HOTSPOT_SECURITY, null)
        return ManualHotspotSecurity.entries.firstOrNull { it.name == stored }
            ?: if (loadManualHotspotPassphrase(context).isEmpty()) {
                ManualHotspotSecurity.OPEN
            } else {
                ManualHotspotSecurity.WPA2
            }
    }

    fun saveManualHotspotSecurity(context: Context, security: ManualHotspotSecurity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SECURITY, security.name)
            .apply()
    }

    fun loadDebugLogsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGS_ENABLED, false)

    fun saveDebugLogsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEBUG_LOGS_ENABLED, enabled)
            .apply()
    }

    /**
     * Which revision of the disclaimer the owner accepted, tracked by
     * [Attribution.DISCLAIMER_VERSION] so revised wording is shown again once.
     */
    fun loadDisclaimerAcceptedVersion(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DISCLAIMER_ACCEPTED_VERSION, 0)

    fun saveDisclaimerAcceptedVersion(context: Context, version: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISCLAIMER_ACCEPTED_VERSION, version)
            .apply()
    }

    fun loadAutoStartOnBoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
            .apply()
    }

    /**
     * Wireless "auto-launch" behaviour (box style): when enabled the resident service listens in
     * the background at boot and brings CarPlay to the foreground the moment the iPhone connects,
     * returning to the car home screen on disconnect. On by default to match box behaviour
     * (a background listener starts at boot).
     */
    fun loadWirelessAutoLaunch(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIRELESS_AUTO_LAUNCH, true)

    fun saveWirelessAutoLaunch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_AUTO_LAUNCH, enabled)
            .apply()
    }

    fun loadLocationReportingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCATION_REPORTING_ENABLED, true)

    fun saveLocationReportingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCATION_REPORTING_ENABLED, enabled)
            .apply()
    }

    fun loadManufacturer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUFACTURER, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANUFACTURER

    fun saveManufacturer(context: Context, manufacturer: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUFACTURER, manufacturer)
            .apply()
    }

    fun loadModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

    fun saveModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    fun loadOemLabel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OEM_LABEL, DEFAULT_OEM_LABEL)
            .orEmpty()
            // iOS hides the car icon without a label; "BYD" was the previous
            // factory default, migrate it to the current one.
            .let { if (it.isBlank() || it == "BYD") DEFAULT_OEM_LABEL else it }

    fun saveOemLabel(context: Context, oemLabel: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OEM_LABEL, oemLabel)
            .apply()
    }

    // Desay SV cluster fly-screen (RTP push to the 800x480 LCD instrument cluster).
    fun loadDesaysvClusterEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESAYSV_CLUSTER_ENABLED, true)

    fun saveDesaysvClusterEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DESAYSV_CLUSTER_ENABLED, enabled)
            .apply()
    }

    private const val KEY_DESAYSV_LAYOUT_VERSION = "desaysv_layout_version"

    /**
     * Layout semantics changed in v3: X/Y/W/H are now a CROP BOX in the source stream
     * that is scaled to fill the whole panel — never letterboxed. v2 values were a
     * content window on the 800x480 frame and are meaningless now.
     * v4: the 2x render source (1600x960) was reverted — the Renesas decoder cannot
     * allocate buffers for it alongside the main stream — so defaults return to the
     * panel-native 800x480 stream and any 1600x960-era values reset once.
     * v5: the instrument stream is now declared at 1792x720 (the decoder's cap
     * alongside the main stream); crop defaults move to the full 1792x720 frame.
     */
    private const val DESAYSV_LAYOUT_VERSION = 5

    private fun migrateDesaysvLayout(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_DESAYSV_LAYOUT_VERSION, 1) >= DESAYSV_LAYOUT_VERSION) return
        prefs.edit()
            .putInt(KEY_DESAYSV_CLUSTER_X, 0)
            .putInt(KEY_DESAYSV_CLUSTER_Y, 0)
            .putInt(KEY_DESAYSV_CLUSTER_W, 1792)
            .putInt(KEY_DESAYSV_CLUSTER_H, 720)
            .putInt(KEY_DESAYSV_CLUSTER_SCALE, 100)
            .putInt(KEY_DESAYSV_CLUSTER_MARGIN, 0)
            .putInt(KEY_DESAYSV_LAYOUT_VERSION, DESAYSV_LAYOUT_VERSION)
            .apply()
    }

    private fun loadClusterRectInt(context: Context, key: String, default: Int): Int {
        migrateDesaysvLayout(context)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, default)
    }

    private fun saveClusterRectInt(context: Context, key: String, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(key, value)
            .apply()
        overlaySettingsListener?.invoke()
    }

    fun loadDesaysvClusterX(context: Context): Int = loadClusterRectInt(context, KEY_DESAYSV_CLUSTER_X, 0)
    fun saveDesaysvClusterX(context: Context, value: Int) = saveClusterRectInt(context, KEY_DESAYSV_CLUSTER_X, value)
    fun loadDesaysvClusterY(context: Context): Int = loadClusterRectInt(context, KEY_DESAYSV_CLUSTER_Y, 0)
    fun saveDesaysvClusterY(context: Context, value: Int) = saveClusterRectInt(context, KEY_DESAYSV_CLUSTER_Y, value)
    fun loadDesaysvClusterW(context: Context): Int = loadClusterRectInt(context, KEY_DESAYSV_CLUSTER_W, 1792)
    fun saveDesaysvClusterW(context: Context, value: Int) = saveClusterRectInt(context, KEY_DESAYSV_CLUSTER_W, value)
    fun loadDesaysvClusterH(context: Context): Int = loadClusterRectInt(context, KEY_DESAYSV_CLUSTER_H, 720)
    fun saveDesaysvClusterH(context: Context, value: Int) = saveClusterRectInt(context, KEY_DESAYSV_CLUSTER_H, value)
    fun loadDesaysvClusterScale(context: Context): Int = loadClusterRectInt(context, KEY_DESAYSV_CLUSTER_SCALE, 100)
    fun saveDesaysvClusterScale(context: Context, value: Int) = saveClusterRectInt(context, KEY_DESAYSV_CLUSTER_SCALE, value)
    fun loadDesaysvClusterMargin(context: Context): Int = loadClusterRectInt(context, KEY_DESAYSV_CLUSTER_MARGIN, 0)
    fun saveDesaysvClusterMargin(context: Context, value: Int) = saveClusterRectInt(context, KEY_DESAYSV_CLUSTER_MARGIN, value)

    /** Free stretch: the crop box fills the panel exactly (may distort). Off = keep aspect,
     *  crop the overflow — the box-app behaviour that never shows black bars. */
    fun loadDesaysvStretch(context: Context): Boolean {
        migrateDesaysvLayout(context)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESAYSV_STRETCH, false)
    }

    fun saveDesaysvStretch(context: Context, stretch: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DESAYSV_STRETCH, stretch)
            .apply()
        overlaySettingsListener?.invoke()
    }

    /**
     * Debug aid: also show the cluster stream in an on-screen window on the main display.
     * Default OFF - the window stays invisible (alpha 0, still attached) so the 111 stream
     * keeps being decoded/consumed for the RTP push without covering the CarPlay UI.
     */
    fun loadDesaysvPreviewOnMain(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESAYSV_PREVIEW_ON_MAIN, false)

    fun saveDesaysvPreviewOnMain(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DESAYSV_PREVIEW_ON_MAIN, enabled)
            .apply()
    }

    /** After CarPlay connects, send an explicit PLAY so music resumes automatically. */
    fun loadAutoPlayOnConnect(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_PLAY_ON_CONNECT, true)

    fun saveAutoPlayOnConnect(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_PLAY_ON_CONNECT, enabled)
            .apply()
    }

    /** CarPlay day/night appearance follows the car's headlight switch (Desay SV CarInfoService). */
    fun loadDarkModeFollowHeadlight(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE_FOLLOW_HEADLIGHT, true)

    fun saveDarkModeFollowHeadlight(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DARK_MODE_FOLLOW_HEADLIGHT, enabled)
            .apply()
    }

    fun loadFps(context: Context): Int = AirPlayDisplaySettings.sanitizeFps(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, 30),
    )

    fun loadMediaBufferMillis(context: Context): Int = com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS),
    )

    fun saveMediaBufferMillis(context: Context, millis: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(millis)).apply()
    }

    fun saveFps(context: Context, fps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FPS, AirPlayDisplaySettings.sanitizeFps(fps))
            .apply()
    }

    fun loadWidthPhysicalMm(context: Context): Int =
        AirPlayDisplaySettings.sanitizeWidthPhysicalMm(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                KEY_WIDTH_PHYSICAL_MM,
                com.shilapi.xcertplay.airplay.CarPlaySize.DEFAULT.widthMillimeters,
            ),
        )

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.sanitizeWidthPhysicalMm(widthPhysicalMm),
            )
            .apply()
    }

    fun loadPhysicalSizeBasis(context: Context): AirPlayPhysicalSizeBasis {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHYSICAL_SIZE_BASIS, null)
        return AirPlayPhysicalSizeBasis.entries.firstOrNull { it.name == stored }
            ?: AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    }

    fun savePhysicalSizeBasis(context: Context, basis: AirPlayPhysicalSizeBasis) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHYSICAL_SIZE_BASIS, basis.name)
            .apply()
    }

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_DETECTED_WIDTH, 0) to
            prefs.getInt(KEY_MAX_DETECTED_HEIGHT, 0)
    }

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_DETECTED_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_MAX_DETECTED_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    fun loadClusterMapEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLUSTER_MAP, false)

    fun saveClusterMapEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLUSTER_MAP, enabled).apply()
    }

    /** The dashboard map as a card on the centre screen while DiPlay is in the background. */
    fun loadCenterMapOverlay(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CENTER_MAP_OVERLAY, false)

    /** Other launchers may show the live dashboard map in their own screen (MapEmbedService). */
    fun loadLauncherMapSharing(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LAUNCHER_MAP_SHARING, false)

    fun saveLauncherMapSharing(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LAUNCHER_MAP_SHARING, enabled).apply()
    }

    /** Observe consent changes for already attached launcher maps; call the returned function to unregister. */
    internal fun observeLauncherMapSharing(context: Context, changed: (Boolean) -> Unit): () -> Unit {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAUNCHER_MAP_SHARING) changed(loadLauncherMapSharing(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun saveCenterMapOverlay(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CENTER_MAP_OVERLAY, enabled).apply()
    }

    fun loadClusterContent(context: Context): CarPlayClusterDisplay.Content =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLUSTER_CONTENT, null)
            ?.let { name -> CarPlayClusterDisplay.Content.entries.firstOrNull { it.name == name } }
            ?: CarPlayClusterDisplay.Content.MAP

    fun saveClusterContent(context: Context, content: CarPlayClusterDisplay.Content) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CLUSTER_CONTENT, content.name).apply()
        overlaySettingsListener?.invoke()
    }

    /** Fingers for the swipe-down that opens settings; some head units reserve three. */
    fun loadSettingsGestureFingers(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SETTINGS_GESTURE_FINGERS, 3).coerceIn(2, 4)

    fun saveSettingsGestureFingers(context: Context, fingers: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SETTINGS_GESTURE_FINGERS, fingers.coerceIn(2, 4)).apply()
    }

    fun loadCenterMapFollowsDashboard(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CENTER_MAP_FOLLOWS_DASHBOARD, true)

    fun saveCenterMapFollowsDashboard(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CENTER_MAP_FOLLOWS_DASHBOARD, enabled).apply()
    }

    fun loadClusterTurnCardOverlaySize(context: Context): CarPlayClusterDisplay.OverlaySize =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE, null)
            ?.let { name -> CarPlayClusterDisplay.OverlaySize.entries.firstOrNull { it.name == name } }
            ?: CarPlayClusterDisplay.OverlaySize.MEDIUM

    fun saveClusterTurnCardOverlaySize(context: Context, size: CarPlayClusterDisplay.OverlaySize) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE, size.name).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlayXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_X)) {
            prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_X, ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
        } else when (prefs.getString(KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION, null)) {
            "LEFT" -> 20
            "CENTER" -> 50
            else -> ClusterTurnCardOverlay.DEFAULT_X_PERCENT
        }
        return ClusterTurnCardOverlay.snap(raw, ClusterTurnCardOverlay.xPercents)
    }

    fun saveClusterTurnCardOverlayXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_X,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.xPercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlayYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_Y)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_Y, ClusterTurnCardOverlay.DEFAULT_Y_PERCENT),
                ClusterTurnCardOverlay.yPercents,
            )
        }
        return ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    }

    fun saveClusterTurnCardOverlayYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_Y,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.yPercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterMapScalePercent(context: Context): Int = CarPlayClusterDisplay.STREAM_SCALE_PERCENT.let { default ->
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MAP_SCALE, default)
            .takeIf { it in CarPlayClusterDisplay.scalePresets } ?: default
    }

    fun saveClusterMapScalePercent(context: Context, percent: Int) {
        if (percent !in CarPlayClusterDisplay.scalePresets) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CLUSTER_MAP_SCALE, percent).apply()
    }

    fun loadClusterMarkerHorizontalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_X, 0)
            .coerceIn(CarPlayClusterDisplay.horizontalSteps)

    fun saveClusterMarkerHorizontalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_X, step.coerceIn(CarPlayClusterDisplay.horizontalSteps)).apply()
    }

    fun loadClusterMarkerVerticalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_Y, 0)
            .coerceIn(CarPlayClusterDisplay.verticalSteps)

    fun saveClusterMarkerVerticalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_Y, step.coerceIn(CarPlayClusterDisplay.verticalSteps)).apply()
    }

    fun loadRightHandDrive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RIGHT_HAND_DRIVE, rightHandDrive)
            .apply()
    }

    fun loadHideTopBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_TOP_BAR, true)

    fun saveHideTopBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_TOP_BAR, hide)
            .apply()
    }

    fun loadHideBottomBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_BOTTOM_BAR, true)

    fun saveHideBottomBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_BOTTOM_BAR, hide)
            .apply()
    }

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, true)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, drawOutside)
            .apply()
    }

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): SafeAreaRect? {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(safeAreaKey(widthPixels, heightPixels), null),
        )
    }

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: SafeAreaRect,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                safeAreaKey(activityWidthPixels, activityHeightPixels),
                SafeAreaCodec.encode(rect.clampTo(activityWidthPixels, activityHeightPixels)),
            )
        if (commit) editor.commit() else editor.apply()
    }

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(safeAreaKey(activityWidthPixels, activityHeightPixels))
        if (commit) editor.commit() else editor.apply()
    }

    fun loadCustomAirPlayIconFile(context: Context): File? =
        File(context.filesDir, CUSTOM_ICON_FILE).takeIf { it.isFile }

    fun saveCustomAirPlayIcon(context: Context, encodedImage: ByteArray) {
        require(encodedImage.isNotEmpty()) { "AirPlay icon data must not be empty" }
        File(context.filesDir, CUSTOM_ICON_FILE).outputStream().use { output ->
            output.write(encodedImage)
        }
    }

    fun clearCustomAirPlayIcon(context: Context) {
        File(context.filesDir, CUSTOM_ICON_FILE).delete()
    }

    fun loadIdentity(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val privateKey = prefs.getString(KEY_IDENT_PRIVATE, null)
        val publicKey = prefs.getString(KEY_IDENT_PUBLIC, null)
        val pairingId = prefs.getString(KEY_PAIRING_ID, null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            return AirPlayIdentity(privateKey.decodeHex(), publicKey.decodeHex(), pairingId)
        }
        return AirPlayIdentity.generate().also { identity ->
            prefs.edit()
                .putString(KEY_IDENT_PRIVATE, identity.privateKey.toHex())
                .putString(KEY_IDENT_PUBLIC, identity.publicKey.toHex())
                .putString(KEY_PAIRING_ID, identity.pairingId)
                .apply()
        }
    }

    fun loadPairings(context: Context, onSave: (String, ByteArray) -> Unit): PairingStore {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = PairingStore(onSave)
        for (identifier in prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty()) {
            prefs.getString("pairing.$identifier", null)?.let { store.save(identifier, it.decodeHex()) }
        }
        return store
    }

    fun savePairing(context: Context, identifier: String, longTermPublicKey: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identifiers = prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toMutableSet()
        identifiers.add(identifier)
        prefs.edit()
            .putString("pairing.$identifier", longTermPublicKey.toHex())
            .putStringSet(KEY_PAIRING_IDS, identifiers)
            .apply()
    }

    fun loadLockdownRecord(context: Context): LockdownPairRecord? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostId = prefs.getString(KEY_LOCKDOWN_HOST_ID, null) ?: return null
        val systemBuid = prefs.getString(KEY_LOCKDOWN_SYSTEM_BUID, null) ?: return null
        val wifiMac = prefs.getString(KEY_LOCKDOWN_WIFI_MAC, null) ?: return null
        val devicePublic = prefs.getString(KEY_LOCKDOWN_DEVICE_PUBLIC, null) ?: return null
        val deviceCert = prefs.getString(KEY_LOCKDOWN_DEVICE_CERT, null) ?: return null
        val hostPrivate = prefs.getString(KEY_LOCKDOWN_HOST_PRIVATE, null) ?: return null
        val hostCert = prefs.getString(KEY_LOCKDOWN_HOST_CERT, null) ?: return null
        val rootPrivate = prefs.getString(KEY_LOCKDOWN_ROOT_PRIVATE, null) ?: return null
        val rootCert = prefs.getString(KEY_LOCKDOWN_ROOT_CERT, null) ?: return null
        return try {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = devicePublic.decodeHex(),
                deviceCertificatePem = deviceCert.decodeHex(),
                hostPrivateKeyPem = hostPrivate.decodeHex(),
                hostCertificatePem = hostCert.decodeHex(),
                rootPrivateKeyPem = rootPrivate.decodeHex(),
                rootCertificatePem = rootCert.decodeHex(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCKDOWN_HOST_ID, record.hostId)
            .putString(KEY_LOCKDOWN_SYSTEM_BUID, record.systemBuid)
            .putString(KEY_LOCKDOWN_WIFI_MAC, record.wifiMacAddress)
            .putString(KEY_LOCKDOWN_DEVICE_PUBLIC, record.devicePublicKeyPem.toHex())
            .putString(KEY_LOCKDOWN_DEVICE_CERT, record.deviceCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_HOST_PRIVATE, record.hostPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_HOST_CERT, record.hostCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_PRIVATE, record.rootPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_CERT, record.rootCertificatePem.toHex())
            .apply()
    }

    fun clearLockdownRecord(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .apply()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun safeAreaKey(widthPixels: Int, heightPixels: Int): String =
        "$SAFE_AREA_KEY_PREFIX${widthPixels}x$heightPixels"
}
