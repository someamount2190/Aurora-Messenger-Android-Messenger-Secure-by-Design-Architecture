package com.aura.network

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best-effort local-network helpers used by production address discovery
 * ([AddressDiscovery], [RendezvousClient]). Kept in the main source set because P2P
 * discovery needs the device's own LAN IPv4 in every build — independent of the
 * developer-only in-app rendezvous server.
 */
object LocalNetwork {
    /** Best-effort local IPv4 (on the emulator this is typically 10.0.2.15). */
    fun localIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
