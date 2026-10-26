package com.aura.crypto

import com.aura.crypto.testutil.FakePrekeyStore
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scanner's prekey checks: the bundle's signing key must be the one the scanned nodeId
 * commits to, and every prekey must carry a valid signature from BOTH halves of it.
 */
class PrekeyVerifierTest {

    private val kem = HybridKem()
    private val signer = HybridSigner()
    private val gen = NodeIdentityGenerator(kem, signer, Hkdf())
    private val verifier = PrekeyVerifier(signer)

    private suspend fun published(): Pair<NodeIdentity, JSONObject> {
        val id = gen.generate().getOrThrow()
        return id to PrekeyManager(FakePrekeyStore(), kem, signer).publicBundle(id)
    }

    private fun NodeIdentity.bind(signPub: ByteArray = publicPart.signingPublicKey.toBytes()) =
        verifier.bindSigningKey(
            nodeId.toHex(), publicPart.kemPublicKey, publicPart.signingPublicKey.ed25519PublicKey, signPub
        )

    private suspend fun verifySpk(id: NodeIdentity, spk: JSONObject, key: HybridVerifyKey, sig: ByteArray) =
        verifier.verifyPrekey(id.nodeId.toHex(), PrekeyManager.KIND_SPK, spk.getString("id"), spk.getString("pub"), sig, key)

    @Test fun publishedBundle_bindsAndVerifies() = runTest {
        val (id, bundle) = published()
        val key = assertNotNull(id.bind(B64.decode(bundle.getString("signPub"))))
        val spk = bundle.getJSONObject("spk")
        assertTrue(verifySpk(id, spk, key, B64.decode(spk.getString("sig"))))
        val opk = bundle.getJSONArray("opks").getJSONObject(0)
        assertTrue(verifier.verifyPrekey(id.nodeId.toHex(), PrekeyManager.KIND_OPK,
            opk.getString("id"), opk.getString("pub"), B64.decode(opk.getString("sig")), key))
    }

    @Test fun signingKeyOfAnotherIdentity_doesNotBind() = runTest {
        val id = gen.generate().getOrThrow()
        val other = gen.generate().getOrThrow()
        assertNull(id.bind(other.publicPart.signingPublicKey.toBytes()))
    }

    @Test fun substitutedMldsaKeyWithTheRightEd25519Key_doesNotBind() = runTest {
        // A server swapping in its own ML-DSA key (keeping the QR's Ed25519 key) changes the hash.
        val id = gen.generate().getOrThrow()
        val other = gen.generate().getOrThrow()
        val forged = HybridVerifyKey(
            other.publicPart.signingPublicKey.dilithiumPublicKey,
            id.publicPart.signingPublicKey.ed25519PublicKey
        )
        assertNull(id.bind(forged.toBytes()))
    }

    @Test fun ed25519KeyDifferentFromTheQr_doesNotBind() = runTest {
        val id = gen.generate().getOrThrow()
        val key = verifier.bindSigningKey(
            id.nodeId.toHex(), id.publicPart.kemPublicKey, ByteArray(32), id.publicPart.signingPublicKey.toBytes()
        )
        assertNull(key)
    }

    @Test fun malformedOrPaddedSigningKey_doesNotBind() = runTest {
        val id = gen.generate().getOrThrow()
        assertNull(id.bind(ByteArray(3)))
        assertNull(id.bind(id.publicPart.signingPublicKey.toBytes() + byteArrayOf(0)))
    }

    @Test fun ed25519OnlySignature_isRejected() = runTest {
        val (id, bundle) = published()
        val key = assertNotNull(id.bind())
        val spk = bundle.getJSONObject("spk")
        val msg = PrekeyManager.prekeyMessage(id.nodeId.toHex(), PrekeyManager.KIND_SPK, spk.getString("id"), spk.getString("pub"))
        val edOnly = signer.signEd25519Only(msg, id.privatePart.signingPrivateKey).getOrThrow()
        assertFalse(verifySpk(id, spk, key, edOnly))
    }

    @Test fun corruptMldsaHalfWithValidEd25519Half_isRejected() = runTest {
        // The pre-fix verifier only checked the Ed25519 half, so it accepted exactly this.
        val (id, bundle) = published()
        val key = assertNotNull(id.bind())
        val spk = bundle.getJSONObject("spk")
        val sig = B64.decode(spk.getString("sig"))
        sig[10] = (sig[10].toInt() xor 0x01).toByte()   // inside the ML-DSA-65 part
        assertFalse(verifySpk(id, spk, key, sig))
    }

    @Test fun signatureOverADifferentPrekey_isRejected() = runTest {
        val (id, bundle) = published()
        val key = assertNotNull(id.bind())
        val spk = bundle.getJSONObject("spk")
        val opkSig = B64.decode(bundle.getJSONArray("opks").getJSONObject(0).getString("sig"))
        assertFalse(verifySpk(id, spk, key, opkSig))
    }
}
