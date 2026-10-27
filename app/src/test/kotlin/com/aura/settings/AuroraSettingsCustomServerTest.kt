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
 * Custom-server persistence + reset. A custom server is a presence/metadata surface, so the
 * stored address + its trust-on-first-use pin must survive restarts, and both the explicit
 * reset and the duress/decoy wipe ([clearAll]) must return to the bundled default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuroraSettingsCustomServerTest {

    private fun settings(): AuroraSettings =
        AuroraSettings(ApplicationProvider.getApplicationContext<Context>())

    private val url = "https://rv.example.org"
    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test fun default_isNotCustom_andBlankPin() {
        val s = settings()
        assertEquals(AuroraSettings.DEFAULT_SERVER_ADDRESS, s.serverAddress.value)
        assertFalse(s.isCustomServer)
        assertEquals("", s.serverPin.value)
    }

    @Test fun setCustomServer_setsAddressAndPin_andIsCustom() {
        val s = settings()
        s.setCustomServer(url, pin)
        assertEquals(url, s.serverAddress.value)
        assertEquals(pin, s.serverPin.value)
        assertTrue(s.isCustomServer)
    }

    @Test fun customServer_survivesNewInstance() {
        settings().setCustomServer(url, pin)
        val s2 = settings()
        assertEquals(url, s2.serverAddress.value)
        assertEquals(pin, s2.serverPin.value)
    }

    @Test fun clearCustomServer_returnsToDefault_andBlanksPin() {
        val s = settings()
        s.setCustomServer(url, pin)
        s.clearCustomServer()
        assertEquals(AuroraSettings.DEFAULT_SERVER_ADDRESS, s.serverAddress.value)
        assertEquals("", s.serverPin.value)
        assertFalse(s.isCustomServer)
    }

    @Test fun clearAll_wipesCustomServerAndPin() {
        val s = settings()
        s.setCustomServer(url, pin)
        s.clearAll()
        assertEquals(AuroraSettings.DEFAULT_SERVER_ADDRESS, s.serverAddress.value)
        assertEquals("", s.serverPin.value)
    }
}
