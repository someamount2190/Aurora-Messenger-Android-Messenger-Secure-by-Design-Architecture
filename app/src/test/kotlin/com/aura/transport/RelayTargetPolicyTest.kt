package com.aura.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Guards the P1-04 SSRF hardening: the ShadowMesh relay forwards only to public unicast
 * addresses. Uses literal IPs (InetAddress.getByName on a dotted quad does no DNS).
 */
class RelayTargetPolicyTest {

    private fun disallowed(ip: String) =
        RelayTargetPolicy.isDisallowed(InetAddress.getByName(ip))

    @Test fun publicUnicastAllowed() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "203.0.113.7", "93.184.216.34", "2606:4700:4700::1111")) {
            assertFalse("$ip should be allowed", disallowed(ip))
        }
    }

    @Test fun loopbackAndPrivateBlocked() {
        for (ip in listOf("127.0.0.1", "10.0.0.5", "172.16.0.1", "192.168.1.1", "169.254.1.1", "0.0.0.0", "224.0.0.1", "::1", "fe80::1")) {
            assertTrue("$ip should be blocked", disallowed(ip))
        }
    }

    @Test fun carrierGradeNatBlocked() {
        // 100.64.0.0/10 — other subscribers on the same carrier.
        assertTrue(disallowed("100.64.0.1"))
        assertTrue(disallowed("100.100.50.1"))
        assertTrue(disallowed("100.127.255.254"))
        // Boundaries: 100.63.x and 100.128.x are public, not CGNAT.
        assertFalse(disallowed("100.63.0.1"))
        assertFalse(disallowed("100.128.0.1"))
    }

    @Test fun reservedIpv4Blocked() {
        assertTrue(disallowed("0.1.2.3"))          // 0.0.0.0/8
        assertTrue(disallowed("192.0.0.1"))        // 192.0.0.0/24
        assertTrue(disallowed("198.18.0.1"))       // 198.18.0.0/15
        assertTrue(disallowed("198.19.255.255"))   // 198.18.0.0/15
        assertTrue(disallowed("240.0.0.1"))        // 240.0.0.0/4
        assertTrue(disallowed("255.255.255.255"))  // broadcast
    }

    @Test fun ipv6UlaAndV4MappedBlocked() {
        assertTrue(disallowed("fc00::1"))                  // fc00::/7 ULA
        assertTrue(disallowed("fd12:3456::1"))             // fc00::/7 ULA
        assertTrue(disallowed("::ffff:10.0.0.1"))          // IPv4-mapped into a private v4
    }
}
