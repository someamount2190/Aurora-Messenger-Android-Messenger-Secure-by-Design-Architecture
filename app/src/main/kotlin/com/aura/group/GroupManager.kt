package com.aura.group

import android.util.Base64
import com.aura.crypto.HybridSigner
import com.aura.crypto.HybridVerifyKey
import com.aura.crypto.toHex
import com.aura.db.GroupDao
import com.aura.db.GroupEntity
import com.aura.db.GroupMemberEntity
import com.aura.db.GroupMembershipEventEntity
import com.aura.di.IoDispatcher
import com.aura.identity.IdentityProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage B: group identity + creator-signed, hash-chained membership (prompt §2, §3, §11).
 *
 * The creator is the only authority for membership changes, enforced **cryptographically**: every
 * event is signed with the creator's hybrid (ML-DSA-65 + Ed25519) identity key and verified against
 * the creator key pinned in the group row, so the creator cannot be silently replaced. Events form a
 * hash chain (prevStateHash) with a strictly increasing version, which rejects stale/replayed/
 * rolled-back/forked updates. Invitations (Stage C) and messaging (Stage D) build on this.
 */
@Singleton
class GroupManager @Inject constructor(
    private val identityManager: IdentityProvider,
    private val signer: HybridSigner,
    private val groupDao: GroupDao,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val rng = SecureRandom()

    sealed interface ApplyResult {
        data class Applied(val newVersion: Long) : ApplyResult
        data class Rejected(val reason: String) : ApplyResult
    }

    /** Create a new group with me as creator (genesis CREATE at version 1). Returns the groupId. */
    suspend fun createGroup(name: String): String = withContext(io) {
        val id = identityManager.getOrCreate()
        val me = id.nodeId.toHex()
        val myVerify = b64(id.publicPart.signingPublicKey.toBytes())
        val myKem = b64(id.publicPart.kemPublicKey.toBytes())
        val groupId = GroupProtocol.deriveGroupId(me, ByteArray(32).also(rng::nextBytes))
        val version = 1L
        val stateHash = GroupProtocol.stateHash(
            groupId, version, name, listOf(MemberRecord(me, myVerify, myKem, "ACTIVE"))
        )
        val eventId = randomId()
        val ts = System.currentTimeMillis()
        val bytes = GroupProtocol.eventBytes(
            groupId, eventId, GroupProtocol.genesisPrevHash, version, GroupAction.CREATE,
            me, myVerify, myKem, name, me, ts
        )
        val sig = signer.sign(bytes, id.privatePart.signingPrivateKey).getOrThrow()
        groupDao.upsertGroup(
            GroupEntity(groupId, name, me, myVerify, version, stateHash, ts, "CREATOR", "ACTIVE")
        )
        groupDao.upsertMember(GroupMemberEntity(groupId, me, myVerify, myKem, version, "ACTIVE"))
        groupDao.insertEvent(
            GroupMembershipEventEntity(
                eventId, groupId, version, GroupProtocol.genesisPrevHash, GroupAction.CREATE.name,
                me, myVerify, myKem, name, me, ts, b64(sig)
            )
        )
        groupId
    }

    /**
     * Creator-only: build + sign + locally apply a membership event, returning it for distribution
     * (Stage C/D push it to members). Throws if this device is not the pinned creator.
     */
    suspend fun createSignedEvent(
        groupId: String,
        action: GroupAction,
        affectedMemberId: String = "",
        affectedVerifyKeyB64: String = "",
        affectedKemPubB64: String = "",
        newName: String = ""
    ): GroupMembershipEventEntity = withContext(io) {
        val id = identityManager.getOrCreate()
        val me = id.nodeId.toHex()
        val group = groupDao.group(groupId) ?: error("unknown group")
        require(group.role == "CREATOR" && group.creatorId == me) {
            "only the creator can change membership"
        }
        val version = group.membershipVersion + 1
        val name = if (action == GroupAction.RENAME) newName else group.name
        val eventId = randomId()
        val ts = System.currentTimeMillis()
        val bytes = GroupProtocol.eventBytes(
            groupId, eventId, group.stateHash, version, action,
            affectedMemberId, affectedVerifyKeyB64, affectedKemPubB64, name, me, ts
        )
        val sig = signer.sign(bytes, id.privatePart.signingPrivateKey).getOrThrow()
        val event = GroupMembershipEventEntity(
            eventId, groupId, version, group.stateHash, action.name,
            affectedMemberId, affectedVerifyKeyB64, affectedKemPubB64, name, me, ts, b64(sig)
        )
        applyVerified(group, event)   // creator is already authorized; record + apply locally
        event
    }

    /**
     * Receiver path: verify a creator-signed event against the pinned group + hash chain, then apply.
     * The group must already be known locally (creator key pinned at join); genesis bootstrapping is
     * Stage C (invitation acceptance). Returns [ApplyResult.Rejected] with a reason on any failure —
     * unauthorized, stale/replayed, forked, or badly signed.
     */
    suspend fun verifyAndApply(event: GroupMembershipEventEntity): ApplyResult = withContext(io) {
        val group = groupDao.group(event.groupId)
            ?: return@withContext ApplyResult.Rejected("unknown group")
        if (event.creatorId != group.creatorId)
            return@withContext ApplyResult.Rejected("event not from the pinned creator")
        if (event.version <= group.membershipVersion)
            return@withContext ApplyResult.Rejected("stale/replayed (v${event.version} <= v${group.membershipVersion})")
        if (event.version != group.membershipVersion + 1)
            return@withContext ApplyResult.Rejected("non-contiguous version (needs reconciliation)")
        if (event.prevStateHash != group.stateHash)
            return@withContext ApplyResult.Rejected("prev-state-hash mismatch (divergent state)")
        val creatorKey = try {
            HybridVerifyKey.fromBytes(unb64(group.creatorVerifyKeyB64))
        } catch (e: Exception) {
            return@withContext ApplyResult.Rejected("bad pinned creator key")
        }
        val bytes = GroupProtocol.eventBytes(
            event.groupId, event.eventId, event.prevStateHash, event.version,
            GroupAction.valueOf(event.action), event.affectedMemberId, event.affectedVerifyKeyB64,
            event.affectedKemPubB64, event.name, event.creatorId, event.timestampMs
        )
        val ok = signer.verify(bytes, unb64(event.signatureB64), creatorKey).getOrNull() == true
        if (!ok) return@withContext ApplyResult.Rejected("invalid creator signature")
        applyVerified(group, event)
        ApplyResult.Applied(event.version)
    }

    /** Mutate membership per a verified event, recompute + persist the chained state. */
    private suspend fun applyVerified(group: GroupEntity, event: GroupMembershipEventEntity) {
        val members = groupDao.members(group.groupId).toMutableList()
        var name = group.name
        var groupState = group.state
        when (GroupAction.valueOf(event.action)) {
            GroupAction.CREATE -> Unit  // genesis is established at creation/join
            GroupAction.ADD -> {
                members.removeAll { it.memberId == event.affectedMemberId }
                members += GroupMemberEntity(
                    group.groupId, event.affectedMemberId, event.affectedVerifyKeyB64,
                    event.affectedKemPubB64, event.version, "ACTIVE"
                )
            }
            GroupAction.REMOVE -> {
                val i = members.indexOfFirst { it.memberId == event.affectedMemberId }
                if (i >= 0) members[i] = members[i].copy(state = "REMOVED")
            }
            GroupAction.RENAME -> name = event.name
            GroupAction.TERMINATE -> groupState = "TERMINATED"
        }
        members.forEach { groupDao.upsertMember(it) }
        val stateHash = GroupProtocol.stateHash(
            group.groupId, event.version, name,
            members.map { MemberRecord(it.memberId, it.verifyKeyB64, it.kemPubB64, it.state) }
        )
        groupDao.upsertGroup(
            group.copy(name = name, membershipVersion = event.version, stateHash = stateHash, state = groupState)
        )
        groupDao.insertEvent(event)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun randomId() = ByteArray(16).also(rng::nextBytes).toHex()
}
