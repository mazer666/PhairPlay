package com.phairplay.util

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import timber.log.Timber
import java.net.NetworkInterface
import java.util.UUID

/**
 * NetworkUtils — Helper functions for reading network interface information.
 *
 * WHY: The AirPlay protocol requires the receiver to advertise its MAC address
 * and device name via mDNS TXT records. This class provides clean, safe methods
 * to read this information from the Android system.
 *
 * HOW: All methods are static (on the companion object) — no instance needed.
 * Read the device name and MAC address once at startup and pass them to MdnsService.
 *
 * Example:
 *   val name = NetworkUtils.getDeviceName(context)   // "My Android TV"
 *   val mac  = NetworkUtils.getMacAddress()           // "aa:bb:cc:dd:ee:ff"
 *   val uuid = NetworkUtils.getPersistentUuid(context) // stable UUID per device
 */
object NetworkUtils {

    /**
     * Returns the user-visible device name as configured in Android settings.
     *
     * This is the name that will appear in the macOS AirPlay picker, so it's
     * important that it matches what the user set in their TV's settings.
     *
     * Sources tried in order:
     * 1. Settings.Global.DEVICE_NAME (Android 5+, most TVs)
     * 2. Settings.Secure.BLUETOOTH_NAME (Bluetooth device name, often same as device name)
     * 3. Fallback: "PhairPlay" (if neither source is available)
     *
     * SECURITY: The returned value is sanitized — mDNS service names must not contain
     * certain special characters. We strip any character outside [A-Za-z0-9 _-].
     *
     * @param context Android context (needed to read system settings)
     * @return The sanitized device name, never null or empty.
     */
    fun getDeviceName(context: Context): String {
        val rawName = Settings.Global.getString(context.contentResolver, "device_name")
            ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            ?: DEFAULT_DEVICE_NAME

        // Sanitize: keep only safe characters for mDNS service names
        val sanitized = rawName.replace(Regex("[^A-Za-z0-9 _\\-]"), "").trim()
            .take(MAX_DEVICE_NAME_LENGTH)
            .trim()

        return sanitized.ifEmpty { DEFAULT_DEVICE_NAME }
    }

    /**
     * Returns the device's Wi-Fi or Ethernet MAC address.
     *
     * The MAC address is used as the `deviceid` in AirPlay mDNS TXT records.
     * It uniquely identifies this receiver to macOS senders.
     *
     * Tries Wi-Fi first (most TVs are Wi-Fi), then falls back to any available
     * non-loopback interface, then uses a fake address as last resort.
     *
     * NOTE: On Android 10+, direct MAC access is restricted. We use NetworkInterface
     * instead of WifiManager.getConnectionInfo() which is deprecated.
     *
     * @return MAC address in "aa:bb:cc:dd:ee:ff" format (lowercase, colon-separated).
     */
    fun getMacAddress(): String {
        return try {
            // Iterate all network interfaces to find the Wi-Fi or Ethernet interface
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()

            val mac = interfaces
                .filter { !it.isLoopback && it.isUp && it.hardwareAddress != null }
                .mapNotNull { iface ->
                    iface.hardwareAddress?.let { hwAddr ->
                        hwAddr.joinToString(":") { byte -> "%02x".format(byte) }
                    }
                }
                .firstOrNull()

            mac ?: FALLBACK_MAC_ADDRESS
        } catch (e: Exception) {
            Timber.w(e, "Could not read MAC address — using fallback")
            FALLBACK_MAC_ADDRESS
        }
    }

    /**
     * Returns a stable, device-specific UUID for use in AirPlay's `pi` TXT record.
     *
     * WHY: macOS uses the `pi` (persistent identifier) to recognize a receiver
     * across app restarts. If we generate a new UUID every time, macOS may show
     * duplicate entries in the AirPlay menu.
     *
     * This UUID is generated once and stored in Android's secure settings,
     * so it persists across app restarts and even reinstalls (as long as the
     * app's data is not cleared).
     *
     * SECURITY: This UUID is not a secret — it's transmitted in plaintext via mDNS.
     * It does not contain any sensitive device information.
     *
     * @param context Android context (needed to read/write secure settings)
     * @return A stable UUID string in standard "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" format.
     */
    fun getPersistentUuid(context: Context): String {
        // Persist the UUID in the app's own private SharedPreferences.
        // (Originally this used Settings.Secure, which requires the privileged
        // WRITE_SECURE_SETTINGS permission and threw a SecurityException on normal
        // installs, aborting AirPlay receiver startup. App-private storage needs no
        // permission and still persists across restarts.)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedUuid = prefs.getString(PREF_KEY_DEVICE_UUID, null)
        if (!storedUuid.isNullOrBlank()) {
            return storedUuid
        }

        // Generate a new UUID and store it for future use
        val newUuid = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_KEY_DEVICE_UUID, newUuid).apply()
        return newUuid
    }

    // Constants
    private const val DEFAULT_DEVICE_NAME = "PhairPlay"
    private const val FALLBACK_MAC_ADDRESS = "aa:bb:cc:dd:ee:ff"
    private const val PREFS_NAME = "phairplay_prefs"
    private const val PREF_KEY_DEVICE_UUID = "phairplay_device_uuid"

    /**
     * Maximum length of the device name used as an mDNS service instance name.
     * A vendor device name can be longer than the DNS-SD limit, and an over-long name
     * is rejected by the platform's mDNS daemon.
     */
    private const val MAX_DEVICE_NAME_LENGTH = 63
}
