package com.aura.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRateLimiterTest {

    @Test fun allowsUpToLimitThenBlocks() {
        val rl = RelayRateLimiter(windowMs = 60_000, maxPerWindow = 3)
        val ip = "203.0.113.5"; val t = 1_000L
        assertTrue(rl.tryAcquire(ip, t))
        assertTrue(rl.tryAcquire(ip, t))
        assertTrue(rl.tryAcquire(ip, t))
        assertFalse("4th in the window is blocked", rl.tryAcquire(ip, t))
        assertFalse(rl.tryAcquire(ip, t + 100))
    }

    @Test fun windowResetsAfterExpiry() {
        val rl = RelayRateLimiter(windowMs = 60_000, maxPerWindow = 2)
        val ip = "203.0.113.6"
        assertTrue(rl.tryAcquire(ip, 0))
        assertTrue(rl.tryAcquire(ip, 100))
        assertFalse(rl.tryAcquire(ip, 200))
        // A new window opens once the old one has fully elapsed.
        assertTrue(rl.tryAcquire(ip, 60_000))
        assertTrue(rl.tryAcquire(ip, 60_100))
        assertFalse(rl.tryAcquire(ip, 60_200))
    }

    @Test fun limitsArePerSourceIp() {
        val rl = RelayRateLimiter(windowMs = 60_000, maxPerWindow = 1)
        assertTrue(rl.tryAcquire("a", 0))
        assertFalse(rl.tryAcquire("a", 0))
        assertTrue("a different IP has its own budget", rl.tryAcquire("b", 0))
    }
}
