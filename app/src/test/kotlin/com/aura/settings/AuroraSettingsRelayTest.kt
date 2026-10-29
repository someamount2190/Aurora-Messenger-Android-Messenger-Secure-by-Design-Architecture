package com.aura.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server relay is opt-in. These guard the two properties the settings UI and
 * [com.aura.transport.MessageSender] both depend on: it defaults to off, and the choice
 * survives a process restart. A relay that silently turned itself back on would put the
 * rendezvous server into the delivery path against the user's choice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuroraSettingsRelayTest {

    private fun settings(): AuroraSettings =
        AuroraSettings(ApplicationProvider.getApplicationContext<Context>())

    @Test fun relay_defaultsToOff() {
        assertFalse(settings().relayEnabled.value, "the server relay must be opt-in")
    }

    @Test fun enablingRelay_survivesNewInstance() {
        settings().setRelayEnabled(true)
        assertTrue(settings().relayEnabled.value, "relay choice must persist across instances")
    }

    @Test fun clearAll_turnsRelayOff() {
        val s = settings()
        s.setRelayEnabled(true)
        s.clearAll()
        assertFalse(settings().relayEnabled.value, "clear-all-data must reset the relay to its default")
    }

    @Test fun clearAll_resetsIpExposurePolicy() {
        val s = settings()
        s.setPeerIpExposurePolicy(PeerIpExposurePolicy.STRICT_RELAY_ONLY)
        s.clearAll()
        assertEquals(
            PeerIpExposurePolicy.ALWAYS_P2P,
            settings().peerIpExposurePolicy.value,
            "clear-all-data must not leave the app on a non-default IP policy"
        )
    }
}
