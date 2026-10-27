package com.aura.network

import com.aura.settings.AuroraSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A custom rendezvous server is a presence/metadata surface, so Aurora only accepts one over
 * HTTPS. These guard the pure validation rules (the network probe in [ServerUrlPolicy.capturePin]
 * is exercised on-device).
 */
class ServerUrlPolicyTest {

    @Test fun rejectsBlank() {
        assertErr(ServerUrlPolicy.ValidationError.Blank, ServerUrlPolicy.validate("   "))
    }

    @Test fun rejectsPlainHttp() {
        assertErr(ServerUrlPolicy.ValidationError.NotHttps, ServerUrlPolicy.validate("http://rv.example.org"))
    }

    @Test fun rejectsNonUrlAndNonHttpsScheme() {
        assertErr(ServerUrlPolicy.ValidationError.Malformed, ServerUrlPolicy.validate("rv.example.org"))
        assertErr(ServerUrlPolicy.ValidationError.Malformed, ServerUrlPolicy.validate("ftp://rv.example.org"))
        assertErr(ServerUrlPolicy.ValidationError.Malformed, ServerUrlPolicy.validate("https://"))
    }

    @Test fun rejectsEmbeddedCredentials() {
        assertErr(ServerUrlPolicy.ValidationError.HasCredentials, ServerUrlPolicy.validate("https://user:pass@rv.example.org"))
    }

    @Test fun acceptsHttps_andNormalizesTrailingSlash() {
        val r = ServerUrlPolicy.validate("https://rv.example.org:8443/")
        assertTrue(r.isSuccess)
        assertEquals("rv.example.org", r.getOrThrow().host)
        assertEquals(8443, r.getOrThrow().port)
    }

    @Test fun bundledDefaultIsValid() {
        assertTrue(ServerUrlPolicy.validate(AuroraSettings.DEFAULT_SERVER_ADDRESS).isSuccess)
    }

    private fun assertErr(expected: ServerUrlPolicy.ValidationError, result: Result<*>) {
        assertTrue(result.isFailure, "expected failure")
        val err = (result.exceptionOrNull() as ServerUrlPolicy.PolicyException).error
        assertSame(expected, err)
    }
}
