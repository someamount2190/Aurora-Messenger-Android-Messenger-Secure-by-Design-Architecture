package com.aura.group

import com.aura.crypto.Hkdf
import com.aura.crypto.toHex

/** Creator-authorized membership actions (prompt §3). */
enum class GroupAction { CREATE, ADD, REMOVE, RENAME, TERMINATE }

/** A member as seen in the authenticated membership state (used for the state hash). */
data class MemberRecord(
    val memberId: String,
    val verifyKeyB64: String,
    val kemPubB64: String,
    val state: String   // ACTIVE | INVITED | REMOVED | LEFT
)

/**
 * Pure, deterministic group-protocol helpers: identity derivation, the canonical bytes a creator
 * signs for each membership event, and the authenticated membership-state hash that chains events
 * together. No Android / DB / crypto-key dependencies beyond SHA3 (via [Hkdf]) so it is fully
 * unit-testable. Strings are byte-for-byte stable — never reorder fields or change separators.
 */
object GroupProtocol {
    const val EVENT_PREFIX = "aura-grp-evt-v1"
    const val GROUP_ID_PREFIX = "aura-group-v1"
    private const val GENESIS_SENTINEL = "aura-grp-genesis-v1"
    const val MAX_MEMBERS = 10

    /** groupId = SHA3-256("aura-group-v1|creator|" ‖ 32 random bytes). Collision-resistant + creator-bound. */
    fun deriveGroupId(creatorNodeIdHex: String, randomness: ByteArray): String =
        Hkdf.instance.sha3_256(
            "$GROUP_ID_PREFIX|$creatorNodeIdHex|".toByteArray(Charsets.UTF_8) + randomness
        ).toHex()

    /** The prevStateHash a genesis (CREATE) event chains onto. */
    val genesisPrevHash: String
        get() = Hkdf.instance.sha3_256(GENESIS_SENTINEL.toByteArray(Charsets.UTF_8)).toHex()

    /**
     * Canonical bytes the creator signs for a membership event. Self-contained: ADD/CREATE carry the
     * affected member's identity keys so every receiver can bind them (creator-vouched introduction).
     * Unused fields are empty strings.
     */
    fun eventBytes(
        groupId: String,
        eventId: String,
        prevStateHash: String,
        version: Long,
        action: GroupAction,
        affectedMemberId: String,
        affectedVerifyKeyB64: String,
        affectedKemPubB64: String,
        name: String,
        creatorId: String,
        timestampMs: Long
    ): ByteArray = listOf(
        EVENT_PREFIX, groupId, eventId, prevStateHash, version.toString(), action.name,
        affectedMemberId, affectedVerifyKeyB64, affectedKemPubB64, name, creatorId, timestampMs.toString()
    ).joinToString("|").toByteArray(Charsets.UTF_8)

    /**
     * Hash of the authenticated membership state. Members are sorted by id so the hash is
     * order-independent; feeding it as the next event's prevStateHash forms a tamper-evident chain
     * that defeats rollback/reordering.
     */
    fun stateHash(groupId: String, version: Long, name: String, members: List<MemberRecord>): String {
        val canon = buildString {
            append(groupId).append('|').append(version).append('|').append(name).append("||")
            members.sortedBy { it.memberId }.forEach { m ->
                append(m.memberId).append(':').append(m.verifyKeyB64).append(':')
                    .append(m.kemPubB64).append(':').append(m.state).append(';')
            }
        }
        return Hkdf.instance.sha3_256(canon.toByteArray(Charsets.UTF_8)).toHex()
    }
}
