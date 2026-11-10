package com.pupus.phone

import android.content.Context
import android.util.Log
import com.pupus.phone.api.DeviceToken
import com.pupus.phone.api.InterfaceAddress
import com.pupus.phone.api.TokenStore
import com.pupus.phone.voice.DictationConfig
import com.pupus.phone.voice.RecognizerMode
import com.pupus.phone.voice.TermuxRunCommand
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/** SharedPreferences-backed settings. The device token is generated on first access and can be rotated. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val tokenStore = object : TokenStore {
        override fun read(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)

        // commit(), not apply(): the PC side must never be told a token that is not on disk.
        override fun write(token: String): Boolean = prefs.edit().putString(KEY_DEVICE_TOKEN, token).commit()
    }

    /**
     * Read on every device API request, so a rotation applies at once. Process-wide lock:
     * every AppSettings instance must agree on a single generated token.
     */
    val deviceToken: String
        get() = synchronized(TOKEN_LOCK) {
            val result = DeviceToken.getOrCreate(tokenStore)
            if (!result.isStored) Log.e(TAG, "Device token could not be persisted; it will change on next start")
            result.token
        }

    /** "Regenerar token": the old token stops working immediately. False if it was not persisted. */
    fun regenerateDeviceToken(): Boolean = synchronized(TOKEN_LOCK) {
        val result = DeviceToken.rotate(tokenStore)
        if (!result.isStored) Log.e(TAG, "Rotated device token could not be persisted")
        result.isStored
    }

    var bridgeUrl: String
        get() = prefs.getString(KEY_BRIDGE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BRIDGE_URL, value.trim()).apply()

    var bridgeToken: String
        get() = prefs.getString(KEY_BRIDGE_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BRIDGE_TOKEN, value.trim()).apply()

    /** "Permitir red local": also serve the device API on the Wi-Fi IPv4. Off by default. */
    var isLanAllowed: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_LAN, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_LAN, value).apply()

    /** "Pausa para enviar": silence after which a dictation turn is sent. */
    var pauseToSendMs: Long
        get() = DictationConfig.clampSilence(prefs.getLong(KEY_PAUSE_MS, DictationConfig.DEFAULT_SILENCE_MS))
        set(value) = prefs.edit().putLong(KEY_PAUSE_MS, DictationConfig.clampSilence(value)).apply()

    var recognizerMode: RecognizerMode
        get() = RecognizerMode.parse(prefs.getString(KEY_RECOGNIZER, null))
        set(value) = prefs.edit().putString(KEY_RECOGNIZER, value.name).apply()

    /** "Arrancar el bridge en Termux": on car-mode start with a loopback bridge URL. On by default. */
    var isTermuxLaunchEnabled: Boolean
        get() = prefs.getBoolean(KEY_TERMUX_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_TERMUX_LAUNCH, value).apply()

    /**
     * True while the user wants the accessibility service: set on connect, cleared on a clean
     * unbind (switched off in Settings). A process kill skips onUnbind, so it stays true.
     */
    var isA11yWanted: Boolean
        get() = prefs.getBoolean(KEY_A11Y_WANTED, true)
        set(value) = prefs.edit().putBoolean(KEY_A11Y_WANTED, value).apply()

    /** Script that Termux runs through RUN_COMMAND; blank falls back to the default path. */
    var termuxScriptPath: String
        get() = prefs.getString(KEY_TERMUX_SCRIPT, null)?.takeIf { it.isNotBlank() } ?: TermuxRunCommand.DEFAULT_SCRIPT_PATH
        set(value) = prefs.edit().putString(KEY_TERMUX_SCRIPT, value.trim()).apply()

    private companion object {
        const val TAG = "PupusSettings"
        const val PREFS = "pupus"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_BRIDGE_URL = "bridge_url"
        const val KEY_BRIDGE_TOKEN = "bridge_token"
        const val KEY_ALLOW_LAN = "allow_lan"
        const val KEY_PAUSE_MS = "pause_to_send_ms"
        const val KEY_RECOGNIZER = "recognizer_mode"
        const val KEY_TERMUX_LAUNCH = "termux_launch"
        const val KEY_A11Y_WANTED = "a11y_wanted"
        const val KEY_TERMUX_SCRIPT = "termux_script_path"
        val TOKEN_LOCK = Any()
    }
}

object NetworkAddresses {
    /** Non-loopback IPv4 addresses of interfaces that are up (Tailscale shows up as tun0, 100.x). */
    fun ipv4(): List<InterfaceAddress> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { addr -> addr.hostAddress?.let { InterfaceAddress(iface.name, it) } }
            }
    } catch (e: SocketException) {
        Log.e("PupusNet", "Cannot list interfaces", e)
        emptyList()
    }

    /** `iface: address` lines for the settings screen. */
    fun list(): List<String> = ipv4().map { "${it.interfaceName}: ${it.address}" }
}
