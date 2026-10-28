package com.aura.group

import com.aura.crypto.KemRatchetManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-recipient sealing for group fan-out. Group messages reuse the existing pairwise KEM
 * Double-Ratchet session with each recipient — there is NO group-shared key (prompt §6), so every
 * copy keeps per-recipient forward secrecy. Abstracted behind an interface so the fan-out logic is
 * unit-testable without an established ratchet session (the real session setup is exercised on
 * device in the transport stage).
 */
interface GroupSealer {
    data class Sealed(val bytes: ByteArray, val n: Long)
    /** Seal [plaintext] to [recipientId]'s pairwise session; null if no session exists yet. */
    suspend fun seal(recipientId: String, plaintext: ByteArray, aad: ByteArray): Sealed?
    /** Open a sealed group payload from [senderId]; null if it can't be decrypted/authenticated. */
    suspend fun open(senderId: String, sealed: ByteArray, aad: ByteArray): ByteArray?
}

/** Production [GroupSealer] over the existing [KemRatchetManager] (same primitive as 1:1). */
@Singleton
class RatchetGroupSealer @Inject constructor(
    private val ratchet: KemRatchetManager
) : GroupSealer {
    override suspend fun seal(recipientId: String, plaintext: ByteArray, aad: ByteArray): GroupSealer.Sealed? =
        ratchet.sealNext(recipientId, plaintext, aad)?.let { GroupSealer.Sealed(it.bytes, it.n) }

    override suspend fun open(senderId: String, sealed: ByteArray, aad: ByteArray): ByteArray? =
        ratchet.open(senderId, sealed, aad)
}
