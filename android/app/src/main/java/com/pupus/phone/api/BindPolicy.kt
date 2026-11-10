package com.pupus.phone.api

/** One IPv4 address of a network interface, as seen by the bind policy. */
data class InterfaceAddress(val interfaceName: String, val address: String)

/**
 * Chooses the addresses the device API listens on (docs/protocol.md section 1):
 * loopback always, the Tailscale address (100.64.0.0/10) when present and, only if
 * the user enables "Permitir red local", the Wi-Fi IPv4. Never 0.0.0.0.
 */
object BindPolicy {
    const val LOOPBACK = "127.0.0.1"
    private val WIFI_PREFIXES = listOf("wlan", "swlan")

    fun select(addresses: List<InterfaceAddress>, isLanAllowed: Boolean): Set<String> {
        val selected = linkedSetOf(LOOPBACK)
        addresses.filter { isTailscale(it.address) }.forEach { selected.add(it.address) }
        if (isLanAllowed) {
            addresses.filter { isWifi(it) && isPrivateLan(it.address) }.forEach { selected.add(it.address) }
        }
        return selected
    }

    fun isTailscale(address: String): Boolean {
        val octets = parse(address) ?: return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    fun isPrivateLan(address: String): Boolean {
        val o = parse(address) ?: return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }

    private fun isWifi(entry: InterfaceAddress): Boolean =
        WIFI_PREFIXES.any { entry.interfaceName.startsWith(it) }

    private fun parse(address: String): List<Int>? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return octets.takeIf { all -> all.all { it in 0..255 } }
    }
}

/** Which listeners to start and stop to move from [current] to [desired]. */
data class BindPlan(val toStart: Set<String>, val toStop: Set<String>) {
    companion object {
        fun between(current: Set<String>, desired: Set<String>) =
            BindPlan(toStart = desired - current, toStop = current - desired)
    }
}
