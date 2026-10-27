package com.aura.transport

import java.net.InetAddress

/**
 * Decides whether a ShadowMesh single-hop relay may forward to a resolved address.
 *
 * A relay node forwards opaque bytes on behalf of other users, so a target it should never
 * reach — loopback, any private/internal range, carrier-grade NAT (other subscribers on the
 * same carrier), or reserved space — would turn the relay into an SSRF proxy into the
 * relayer's own network or a tool to probe other subscribers. Only public unicast is allowed.
 *
 * Pure so the range policy is unit-tested exhaustively (the relay path itself drags in
 * sockets and settings and isn't unit-testable).
 */
object RelayTargetPolicy {

    /** True if [addr] must NOT be used as a relay target. */
    fun isDisallowed(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isMulticastAddress) return true

        val raw = addr.address
        if (raw.size == 16) {
            // isSiteLocalAddress only covers the deprecated IPv6 fec0::/10 — also reject the
            // modern Unique-Local range fc00::/7, and IPv4-mapped IPv6 (steering into v4 internal).
            if ((raw[0].toInt() and 0xFE) == 0xFC) return true                              // fc00::/7 ULA
            val v4Mapped = (0..9).all { raw[it].toInt() == 0 } &&
                (raw[10].toInt() and 0xFF) == 0xFF && (raw[11].toInt() and 0xFF) == 0xFF
            if (v4Mapped) return true
        }
        if (raw.size == 4) {
            // IPv4 ranges InetAddress's isXxx() helpers miss but that are never a public peer.
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            val b2 = raw[2].toInt() and 0xFF
            if (b0 == 0) return true                             // 0.0.0.0/8  "this network"
            if (b0 == 100 && b1 in 64..127) return true          // 100.64.0.0/10 carrier-grade NAT
            if (b0 == 192 && b1 == 0 && b2 == 0) return true     // 192.0.0.0/24 IETF protocol assignments
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true // 198.18.0.0/15 benchmarking
            if (b0 >= 240) return true                           // 240.0.0.0/4 reserved (incl. 255.255.255.255)
        }
        return false
    }
}
