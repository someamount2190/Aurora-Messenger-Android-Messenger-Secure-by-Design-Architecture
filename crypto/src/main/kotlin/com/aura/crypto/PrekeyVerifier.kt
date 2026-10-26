package com.aura.crypto

/**
 * Scanner-side verification of a peer's published prekeys, fully post-quantum.
 *
 * The QR code carries the peer's nodeId, KEM public key and Ed25519 key, but not its
 * ML-DSA-65 key (too large for the code). The prekey bundle carries the full hybrid signing
 * key instead, and we accept it only if it is the one the nodeId commits to:
 *
 *     nodeId == SHA3-256(kemPublicKey.toBytes() ‖ signingPublicKey.toBytes())
 *
 * The server can't substitute a key without also changing the nodeId the user scanned. Each
 * prekey must then carry a valid hybrid (ML-DSA-65 + Ed25519) signature under that key, so
 * forging one requires breaking both algorithms. Ed25519-only signatures are rejected.
 */
class PrekeyVerifier(
    private val signer: HybridSigner,
    private val hkdf: Hkdf = Hkdf.instance
) {
    /**
     * Return the peer's hybrid verify key decoded from [signPub] if it binds to [nodeIdHex]
     * together with [kemPublicKey] and its Ed25519 half equals [ed25519Pub] (the QR's key);
     * otherwise null.
     */
    fun bindSigningKey(
        nodeIdHex: String,
        kemPublicKey: HybridPublicKey,
        ed25519Pub: ByteArray,
        signPub: ByteArray
    ): HybridVerifyKey? {
        val key = runCatching { HybridVerifyKey.fromBytes(signPub) }.getOrNull() ?: return null
        // Re-encode so trailing bytes or a non-canonical encoding can't ride along.
        if (!key.toBytes().contentEquals(signPub)) return null
        if (!key.ed25519PublicKey.contentEquals(ed25519Pub)) return null
        val committed = hkdf.sha3_256(kemPublicKey.toBytes() + signPub).toHex()
        return if (committed == nodeIdHex) key else null
    }

    /** True iff [sig] is a valid hybrid signature by [key] over the canonical prekey message. */
    suspend fun verifyPrekey(
        nodeIdHex: String,
        kind: String,
        prekeyId: String,
        pubB64: String,
        sig: ByteArray,
        key: HybridVerifyKey
    ): Boolean {
        val msg = PrekeyManager.prekeyMessage(nodeIdHex, kind, prekeyId, pubB64)
        return signer.verify(msg, sig, key).getOrElse { false }
    }
}
