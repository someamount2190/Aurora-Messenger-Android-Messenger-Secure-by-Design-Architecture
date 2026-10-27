package com.aura.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The canonical signing strings are a cross-implementation contract: the Kotlin
 * in-app server and the Node.js `rendezvous-server` MUST produce byte-identical
 * messages or signatures won't verify across them.
 */
class CheckinSigningTest {

    @Test fun checkinMessage_isCanonicalFormat() {
        assertTrue(
            "aura-checkin-v1|abcd|1.2.3.4|8080|123".toByteArray(Charsets.UTF_8)
                .contentEquals(RendezvousProtocol.checkinMessage("abcd", "1.2.3.4", 8080, 123L))
        )
    }

    @Test fun drainMessage_isCanonicalFormat() {
        assertTrue(
            "aura-drain-v1|abcd|456".toByteArray(Charsets.UTF_8)
                .contentEquals(RendezvousProtocol.drainMessage("abcd", 456L))
        )
    }

    @Test fun relayMessage_isCanonicalFormat() {
        // Must match relayMessage() in rendezvous-server/index.js.
        assertTrue(
            "aura-relay-v1|abcd|n0nce".toByteArray(Charsets.UTF_8)
                .contentEquals(RendezvousProtocol.relayMessage("abcd", "n0nce"))
        )
    }

    @Test fun constants_matchDeployedServer() {
        assertEquals(8080, RendezvousProtocol.DEFAULT_PORT)
        assertEquals(15 * 60 * 1000L, RendezvousProtocol.TTL_MS)
    }
}
