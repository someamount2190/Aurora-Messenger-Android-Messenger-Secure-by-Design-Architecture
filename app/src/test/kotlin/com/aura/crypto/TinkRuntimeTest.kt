package com.aura.crypto

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The AEAD that seals every message on a device comes from `tink-android` (the app excludes the
 * full `tink` jar the crypto module compiles against). These tests pin that runtime and check it
 * against the independent Wycheproof XChaCha20-Poly1305 vectors, so the implementation that ships
 * is the one that's tested.
 */
class TinkRuntimeTest {

    @Test fun xchachaComesFromPinnedTinkAndroid() {
        val location = Class.forName("com.google.crypto.tink.subtle.XChaCha20Poly1305")
            .protectionDomain.codeSource.location.toString()
        assertTrue(
            Regex("""tink-android-1\.18\.\d+\.jar""").containsMatchIn(location),
            "XChaCha20Poly1305 must load from tink-android 1.18.x (the shipped runtime), got $location"
        )
    }

    @Test fun shippedXChachaPassesWycheproof() = runBlocking {
        val cipher = SymmetricCipher()
        val json = javaClass.getResourceAsStream("/wycheproof/xchacha20_poly1305_test.json")
            ?.bufferedReader()?.use { it.readText() } ?: error("missing Wycheproof vectors")
        var valid = 0; var invalid = 0
        val groups = JSONObject(json).getJSONArray("testGroups")
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            if (g.getInt("ivSize") != 192 || g.getInt("tagSize") != 128) continue   // 24-byte nonce only
            val tests = g.getJSONArray("tests")
            for (j in 0 until tests.length()) {
                val t = tests.getJSONObject(j)
                val ct = hex(t.getString("iv")) + hex(t.getString("ct")) + hex(t.getString("tag"))
                val opened = cipher.decrypt(ct, hex(t.getString("key")), hex(t.getString("aad"))).getOrNull()
                when (t.getString("result")) {
                    "valid", "acceptable" -> {
                        if (opened == null || opened.toHex() != t.getString("msg"))
                            fail("tc${t.getInt("tcId")}: valid vector failed to open")
                        valid++
                    }
                    "invalid" -> {
                        if (opened != null) fail("tc${t.getInt("tcId")}: invalid vector ACCEPTED")
                        invalid++
                    }
                }
            }
        }
        assertTrue(valid > 0 && invalid > 0, "expected valid and invalid vectors (got $valid/$invalid)")
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
