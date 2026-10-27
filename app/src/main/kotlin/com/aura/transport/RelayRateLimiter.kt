package com.aura.transport

import java.util.concurrent.ConcurrentHashMap

/**
 * Caps how many ShadowMesh relay forwards one source IP may make per time window.
 *
 * The relay forwards opaque bytes to a public unicast peer ([RelayTargetPolicy]); this bounds
 * using a relay node as a high-volume proxy, on top of the per-IP connection cap and the SSRF
 * guard. It is deliberately NOT identity authentication — single-hop onion relaying must not
 * learn who is relaying through it. Restricting destinations to known mesh members is a separate
 * ShadowMesh Phase-3 item (see MeshPeers), tied to the not-yet-built mesh membership model.
 *
 * Pure and time-injectable so it is unit-tested without wall-clock sleeps.
 */
class RelayRateLimiter(
    private val windowMs: Long = 60_000L,
    private val maxPerWindow: Int = 30,
    private val mapCap: Int = 1024,
) {
    // ip -> [windowStartMs, count]
    private val windows = ConcurrentHashMap<String, LongArray>()

    /** True if a relay forward from [srcIp] at [nowMs] is within the limit (and counts it). */
    fun tryAcquire(srcIp: String, nowMs: Long): Boolean {
        // Bound memory against many churning source IPs: drop expired windows when the map grows.
        if (windows.size > mapCap) windows.entries.removeIf { nowMs - it.value[0] >= windowMs }
        val w = windows.compute(srcIp) { _, cur ->
            if (cur == null || nowMs - cur[0] >= windowMs) longArrayOf(nowMs, 1L)
            else longArrayOf(cur[0], cur[1] + 1L)
        }!!
        return w[1] <= maxPerWindow
    }
}
