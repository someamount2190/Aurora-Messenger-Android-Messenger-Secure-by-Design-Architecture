package com.aura.group

import android.util.Base64
import com.aura.crypto.toHex
import com.aura.db.GroupDao
import com.aura.db.GroupDeliveryEntity
import com.aura.db.GroupMessageDao
import com.aura.db.GroupMessageEntity
import com.aura.di.IoDispatcher
import com.aura.identity.IdentityProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage D: the group message model, pairwise fan-out job creation, and authenticated inbound store
 * with replay/dedup (prompt §5, §6, §9, §10). This layer is transport-agnostic — it creates the
 * per-recipient sealed delivery jobs and persists received messages; the scheduler/transport that
 * actually sends the jobs and feeds in opened payloads is wired in the delivery stage.
 */
@Singleton
class GroupMessageManager @Inject constructor(
    private val identityManager: IdentityProvider,
    private val groupDao: GroupDao,
    private val msgDao: GroupMessageDao,
    private val sealer: GroupSealer,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val rng = SecureRandom()

    sealed interface SendResult {
        data class Queued(val messageId: String, val recipients: Int) : SendResult
        data object NotAMember : SendResult
    }

    sealed interface ReceiveResult {
        data object Stored : ReceiveResult
        data object Duplicate : ReceiveResult
        data class Rejected(val reason: String) : ReceiveResult
    }

    /**
     * Send a group message: persist one logical message, then create one sealed per-recipient
     * delivery job for every other active member. A recipient with no ratchet session yet gets a
     * QUEUED job (no sealed bytes) that the transport stage seals once a session exists.
     */
    suspend fun sendGroupMessage(
        groupId: String, body: String, type: String = "text", replyToId: String? = null
    ): SendResult = withContext(io) {
        val me = identityManager.getOrCreate().nodeId.toHex()
        val group = groupDao.group(groupId) ?: return@withContext SendResult.NotAMember
        val active = groupDao.activeMembers(groupId)
        if (group.state != "ACTIVE" || active.none { it.memberId == me }) return@withContext SendResult.NotAMember

        val seq = (msgDao.maxSenderSeq(groupId, me) ?: 0L) + 1
        val clock = (msgDao.maxClock(groupId) ?: 0L) + 1
        val messageId = randomId()
        val ts = System.currentTimeMillis()
        msgDao.insertMessage(
            GroupMessageEntity(messageId, groupId, me, true, body, type, ts, seq, clock, group.membershipVersion, "SENT", replyToId)
        )
        val recipients = active.filter { it.memberId != me }
        val envelope = GroupEnvelope.encode(
            GroupEnvelope.Envelope(groupId, messageId, me, seq, clock, group.membershipVersion, type, ts, body, replyToId)
        ).toByteArray(Charsets.UTF_8)
        for (r in recipients) {
            val aad = GroupEnvelope.aad(me, r.memberId, groupId)
            val sealed = sealer.seal(r.memberId, envelope, aad)
            if (sealed != null) {
                msgDao.upsertDelivery(GroupDeliveryEntity(messageId, r.memberId, groupId, b64(sealed.bytes), sealed.n, "ENCRYPTED_READY"))
            } else {
                // No session yet — leave the sealing to the transport stage once one is established.
                msgDao.upsertDelivery(GroupDeliveryEntity(messageId, r.memberId, groupId, null, null, "QUEUED"))
            }
        }
        SendResult.Queued(messageId, recipients.size)
    }

    /**
     * Store an inbound group message whose envelope was already opened + authenticated by the
     * pairwise AEAD for ([authenticatedSenderId]). Rejects a sender who isn't a member, a sender
     * mismatch, a reused (sender, seq) [replay/forgery], and dedups by messageId.
     */
    suspend fun receiveGroupMessage(authenticatedSenderId: String, envelopeJson: String): ReceiveResult = withContext(io) {
        val env = try { GroupEnvelope.decode(envelopeJson) } catch (e: Exception) {
            return@withContext ReceiveResult.Rejected("malformed envelope")
        }
        if (env.senderId != authenticatedSenderId)
            return@withContext ReceiveResult.Rejected("sender mismatch vs authenticated channel")
        groupDao.group(env.groupId) ?: return@withContext ReceiveResult.Rejected("unknown group")
        val member = groupDao.members(env.groupId).firstOrNull { it.memberId == env.senderId }
            ?: return@withContext ReceiveResult.Rejected("sender is not a group member")
        if (member.state == "REMOVED")
            return@withContext ReceiveResult.Rejected("sender was removed")
        if (msgDao.message(env.messageId) != null) return@withContext ReceiveResult.Duplicate
        // Replay / seq reuse: a different message already occupies this (sender, seq).
        val atSeq = msgDao.messageBySenderSeq(env.groupId, env.senderId, env.senderSeq)
        if (atSeq != null && atSeq.messageId != env.messageId)
            return@withContext ReceiveResult.Rejected("sequence reuse (replay/forgery)")
        msgDao.insertMessage(
            GroupMessageEntity(env.messageId, env.groupId, env.senderId, false, env.body, env.msgType,
                env.timestampMs, env.senderSeq, env.logicalClock, env.membershipVersion, "RECEIVED", env.replyToId)
        )
        ReceiveResult.Stored
    }

    /** The ordered conversation for display (prompt §10) — timestamp-primary, deterministic, dedup'd. */
    suspend fun conversation(groupId: String): List<GroupMessageEntity> = withContext(io) {
        GroupOrdering.order(msgDao.groupMessages(groupId))
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun randomId() = ByteArray(16).also(rng::nextBytes).toHex()
}
