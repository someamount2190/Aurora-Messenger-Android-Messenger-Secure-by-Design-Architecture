package com.aura.crypto

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import kotlinx.coroutines.runBlocking

/**
 * Fuzz targets for every decoder in aura-crypto that sees attacker-controlled bytes.
 * Each target asserts a security property, not just "doesn't crash". Ordinary
 * exceptions are acceptable only where the production caller catches them.
 */
class AuroraFuzzTargets {

    /** A store whose content is reset to a fixed snapshot before every fuzz input. */
    private class SnapshotStore : KemSessionStore {
        val m = HashMap<String, ByteArray>()
        override suspend fun load(contactNodeIdHex: String) = m[contactNodeIdHex]?.copyOf()
        override suspend fun save(contactNodeIdHex: String, session: ByteArray) { m[contactNodeIdHex] = session.copyOf() }
        override suspend fun delete(contactNodeIdHex: String) { m.remove(contactNodeIdHex) }
        override suspend fun deleteAll() { m.clear() }
    }

    companion object {
        private val kem = HybridKem()
        private val signer = HybridSigner()
        private val hkdf = Hkdf()
        private val cipher = SymmetricCipher()
        private val aad = "aura-msg-v1|alice|bob".toByteArray()
        private val plaintext = "the quick brown fox".toByteArray()

        // Bob's ratchet just after seeding as responder; Alice's first sealed frame to him.
        private val bobStore = SnapshotStore()
        private val bob = KemRatchetManager(bobStore, kem, hkdf, cipher)
        private val message = "aura-prekey-v1|node|spk|id|pub".toByteArray()
        private val aeadKey = ByteArray(32) { 7 }

        private class Fixture(val bobSnapshot: ByteArray, val validFrame: ByteArray,
                              val identity: NodeIdentity, val validSig: ByteArray)

        private val fx: Fixture = runBlocking {
            val root = ByteArray(32) { 0x5A }
            val alice = KemRatchetManager(SnapshotStore(), kem, hkdf, cipher)
            alice.seed("bob", root.copyOf(), iAmInitiator = true)
            bob.seed("alice", root.copyOf(), iAmInitiator = false)
            val id = NodeIdentityGenerator(kem, signer, hkdf).generate().getOrThrow()
            Fixture(
                bobSnapshot = bobStore.m.getValue("alice").copyOf(),
                validFrame = alice.sealNext("bob", plaintext, aad)!!.bytes,
                identity = id,
                validSig = signer.sign(message, id.privatePart.signingPrivateKey).getOrThrow()
            )
        }
        private val bobSnapshot get() = fx.bobSnapshot
        private val validFrame get() = fx.validFrame
        private val identity get() = fx.identity
        private val validSig get() = fx.validSig

        /** Either raw fuzz bytes, or a real artifact with fuzz-chosen byte edits / truncation. */
        private fun mutate(data: FuzzedDataProvider, base: ByteArray): ByteArray {
            if (data.consumeBoolean()) return data.consumeRemainingAsBytes()
            val b = base.copyOf(data.consumeInt(0, base.size + 16))
            repeat(data.consumeInt(0, 8)) {
                if (b.isNotEmpty()) b[data.consumeInt(0, b.size - 1)] = data.consumeByte()
            }
            return b
        }
    }

    /** The receive path for every message: must never throw, and never accept a forgery. */
    @FuzzTest(maxDuration = "3m")
    fun ratchetOpen(data: FuzzedDataProvider) = runBlocking {
        bobStore.m["alice"] = bobSnapshot.copyOf()
        val frame = mutate(data, validFrame)
        val pt = bob.open("alice", frame, aad)
        if (pt != null) {
            check(pt.contentEquals(plaintext)) { "open() accepted a forged frame: ${frame.toHex()}" }
        }
    }

    /** Hybrid signature verification: returns a Result, never accepts a forged signature. */
    @FuzzTest(maxDuration = "2m")
    fun hybridVerify(data: FuzzedDataProvider) = runBlocking {
        val sig = mutate(data, validSig)
        val ok = signer.verify(message, sig, identity.publicPart.signingPublicKey).getOrElse { false }
        if (ok) check(sig.contentEquals(validSig)) { "verify() accepted a modified signature: ${sig.toHex()}" }
    }

    /** AEAD open on attacker ciphertext must never authenticate. */
    @FuzzTest(maxDuration = "1m")
    fun aeadDecrypt(data: FuzzedDataProvider) = runBlocking {
        val ct = data.consumeRemainingAsBytes()
        val r = cipher.decrypt(ct, aeadKey, aad)
        check(r.getOrNull() == null) { "AEAD authenticated fuzz input: ${ct.toHex()}" }
    }

    /** Persisted session decoding (also reached via backup import): no OOM / hang / Error. */
    @FuzzTest(maxDuration = "1m")
    fun sessionDecode(data: FuzzedDataProvider) {
        val bytes = mutate(data, bobSnapshot)
        try { KemRatchetCodec.sessionFromBytes(bytes) } catch (e: Exception) { /* caller rejects */ }
        try { KemRatchetCodec.messageFromBytes(bytes) } catch (e: Exception) { /* open() catches */ }
    }

    /** Key/ciphertext/identity decoders, and the prekey binding check (must never throw). */
    @FuzzTest(maxDuration = "1m")
    fun keyDecoders(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        for (decode in listOf<(ByteArray) -> Any>(
            { HybridPublicKey.fromBytes(it) }, { HybridCiphertext.fromBytes(it) },
            { HybridVerifyKey.fromBytes(it) }, { NodePublicIdentity.fromBytes(it) }
        )) {
            try { decode(bytes) } catch (e: Exception) { /* callers wrap in runCatching */ }
        }
        PrekeyVerifier(signer, hkdf).bindSigningKey(
            identity.nodeId.toHex(), identity.publicPart.kemPublicKey,
            identity.publicPart.signingPublicKey.ed25519PublicKey, bytes
        )
    }
}
