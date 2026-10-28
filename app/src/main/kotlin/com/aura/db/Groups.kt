package com.aura.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A group this device belongs to (or was invited to). Plaintext inside the SQLCipher-encrypted DB.
 * The creator's verify key is pinned here at join so the creator "cannot be silently replaced"
 * (every membership event must verify against it).
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val creatorId: String,
    val creatorVerifyKeyB64: String,
    val membershipVersion: Long,
    val stateHash: String,
    val createdAtMs: Long,
    val role: String,     // CREATOR | MEMBER
    val state: String     // ACTIVE | INVITED | LEFT | TERMINATED
)

/** A member of a group. Key material is cached so fan-out + event verification don't need a contacts row. */
@Entity(tableName = "group_members", primaryKeys = ["groupId", "memberId"])
data class GroupMemberEntity(
    val groupId: String,
    val memberId: String,
    val verifyKeyB64: String,
    val kemPubB64: String,
    val addedAtVersion: Long,
    val state: String     // ACTIVE | INVITED | REMOVED | LEFT
)

/** The ordered, creator-signed membership history (audit + reconciliation source). */
@Entity(tableName = "group_membership_events", primaryKeys = ["eventId"])
data class GroupMembershipEventEntity(
    val eventId: String,
    val groupId: String,
    val version: Long,
    val prevStateHash: String,
    val action: String,
    val affectedMemberId: String,
    val affectedVerifyKeyB64: String,
    val affectedKemPubB64: String,
    val name: String,
    val creatorId: String,
    val timestampMs: Long,
    val signatureB64: String
)

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertGroup(group: GroupEntity)
    @Query("SELECT * FROM groups WHERE groupId = :groupId") suspend fun group(groupId: String): GroupEntity?
    @Query("SELECT * FROM groups WHERE state != 'TERMINATED' ORDER BY createdAtMs DESC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMember(member: GroupMemberEntity)
    @Query("SELECT * FROM group_members WHERE groupId = :groupId") suspend fun members(groupId: String): List<GroupMemberEntity>
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND state = 'ACTIVE'")
    suspend fun activeMembers(groupId: String): List<GroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(event: GroupMembershipEventEntity)
    @Query("SELECT * FROM group_membership_events WHERE groupId = :groupId ORDER BY version ASC")
    suspend fun events(groupId: String): List<GroupMembershipEventEntity>

    // Wipe hooks (duress / leave / terminate).
    @Query("DELETE FROM groups WHERE groupId = :groupId") suspend fun deleteGroup(groupId: String)
    @Query("DELETE FROM group_members WHERE groupId = :groupId") suspend fun deleteMembers(groupId: String)
    @Query("DELETE FROM group_membership_events WHERE groupId = :groupId") suspend fun deleteEvents(groupId: String)
}
