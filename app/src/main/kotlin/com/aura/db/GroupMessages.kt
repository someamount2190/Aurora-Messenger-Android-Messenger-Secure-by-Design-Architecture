package com.aura.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One LOGICAL group message (prompt §6/§7). A single row regardless of how many recipients it
 * fans out to; per-recipient delivery lives in [GroupDeliveryEntity]. Plaintext inside SQLCipher.
 */
@Entity(tableName = "group_messages")
data class GroupMessageEntity(
    @PrimaryKey val messageId: String,
    val groupId: String,
    val senderId: String,
    val fromMe: Boolean,
    val body: String,
    val type: String,
    val timestampMs: Long,        // sender's UTC clock (approximate chronology)
    val senderSeq: Long,          // monotonic per sender (replay/dup + causal order)
    val logicalClock: Long,       // Lamport-style; max-seen + 1 on send
    val membershipVersion: Long,  // the version the sender was a member at
    val localState: String,       // SENT | DELIVERING | RECEIVED
    val replyToId: String? = null,
    val expiresAtMs: Long? = null
)

/**
 * One per-recipient delivery of a group message the local device SENT (the Outbox, prompt §7).
 * Carries the sealed bytes so a retry re-sends identical ciphertext without advancing the ratchet.
 */
@Entity(tableName = "group_deliveries", primaryKeys = ["messageId", "recipientId"])
data class GroupDeliveryEntity(
    val messageId: String,
    val recipientId: String,
    val groupId: String,
    val sealedB64: String?,       // null until sealed
    val ratchetN: Long?,
    val state: String,            // QUEUED|ENCRYPTED_READY|IN_FLIGHT|AWAITING_ACK|DELIVERED|RETRY_PENDING|FAILED|EXPIRED
    val retryCount: Int = 0,
    val nextRetryMs: Long = 0,
    val lastAttemptMs: Long = 0,
    val ackState: String = "NONE" // NONE | ACKED
)

@Dao
interface GroupMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMessage(m: GroupMessageEntity): Long
    @Query("SELECT * FROM group_messages WHERE messageId = :id") suspend fun message(id: String): GroupMessageEntity?
    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY timestampMs ASC")
    fun observeGroupMessages(groupId: String): Flow<List<GroupMessageEntity>>
    @Query("SELECT * FROM group_messages WHERE groupId = :groupId") suspend fun groupMessages(groupId: String): List<GroupMessageEntity>
    @Query("SELECT MAX(senderSeq) FROM group_messages WHERE groupId = :groupId AND senderId = :senderId")
    suspend fun maxSenderSeq(groupId: String, senderId: String): Long?
    @Query("SELECT * FROM group_messages WHERE groupId = :groupId AND senderId = :senderId AND senderSeq = :seq LIMIT 1")
    suspend fun messageBySenderSeq(groupId: String, senderId: String, seq: Long): GroupMessageEntity?
    @Query("SELECT MAX(logicalClock) FROM group_messages WHERE groupId = :groupId") suspend fun maxClock(groupId: String): Long?
    @Query("UPDATE group_messages SET localState = :state WHERE messageId = :id") suspend fun setMessageState(id: String, state: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDelivery(d: GroupDeliveryEntity)
    @Query("SELECT * FROM group_deliveries WHERE messageId = :id") suspend fun deliveries(id: String): List<GroupDeliveryEntity>
    @Query("SELECT * FROM group_deliveries WHERE state NOT IN ('DELIVERED','FAILED','EXPIRED') ORDER BY nextRetryMs ASC")
    suspend fun pendingDeliveries(): List<GroupDeliveryEntity>
    @Query("UPDATE group_deliveries SET state = :state, ackState = :ack, lastAttemptMs = :now WHERE messageId = :id AND recipientId = :rid")
    suspend fun setDeliveryState(id: String, rid: String, state: String, ack: String, now: Long)

    // Wipe hooks.
    @Query("DELETE FROM group_messages WHERE groupId = :groupId") suspend fun deleteGroupMessages(groupId: String)
    @Query("DELETE FROM group_deliveries WHERE groupId = :groupId") suspend fun deleteGroupDeliveries(groupId: String)
}
