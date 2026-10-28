package com.aura.group

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridKem
import com.aura.crypto.HybridSigner
import com.aura.crypto.NodeIdentity
import com.aura.crypto.NodeIdentityGenerator
import com.aura.crypto.toHex
import com.aura.db.AuroraDatabase
import com.aura.db.GroupDao
import com.aura.testutil.FakeIdentityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage B: creator-signed, hash-chained membership. Uses REAL hybrid identities (dummy test keys
 * can't sign), two in-memory DBs (creator + member), and verifies that valid creator events apply
 * while forged / stale / wrong-creator events are rejected cryptographically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupMembershipTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val signer = HybridSigner()
    private fun newIdentity(): NodeIdentity = runBlocking {
        NodeIdentityGenerator(HybridKem(), signer, Hkdf()).generate().getOrThrow()
    }

    private val creator = newIdentity()
    private val member = newIdentity()

    private val creatorDb = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()
    private val memberDb = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()

    private val creatorMgr = GroupManager(FakeIdentityProvider(creator), signer, creatorDb.groupDao(), Dispatchers.Unconfined)
    private val memberMgr = GroupManager(FakeIdentityProvider(member), signer, memberDb.groupDao(), Dispatchers.Unconfined)

    @AfterTest fun close() { creatorDb.close(); memberDb.close() }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private val memberHex get() = member.nodeId.toHex()
    private val memberVerify get() = b64(member.publicPart.signingPublicKey.toBytes())
    private val memberKem get() = b64(member.publicPart.kemPublicKey.toBytes())

    /** Copy the creator's genesis group+creator-member into the member DB (what Stage C invitation delivers). */
    private suspend fun seedMemberFromGenesis(groupId: String) {
        val g = creatorDb.groupDao().group(groupId)!!
        memberDb.groupDao().upsertGroup(g.copy(role = "MEMBER"))
        creatorDb.groupDao().members(groupId).forEach { memberDb.groupDao().upsertMember(it) }
    }

    @Test fun createGroup_pinsCreatorAndIsVersion1() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        val g = creatorDb.groupDao().group(id)!!
        assertEquals(1L, g.membershipVersion)
        assertEquals(creator.nodeId.toHex(), g.creatorId)
        assertEquals("CREATOR", g.role)
        val members = creatorDb.groupDao().activeMembers(id)
        assertEquals(1, members.size)
        assertEquals(creator.nodeId.toHex(), members.first().memberId)
    }

    @Test fun groupIds_areUnique() = runBlocking {
        val a = creatorMgr.createGroup("A"); val b = creatorMgr.createGroup("B")
        assertNotEquals(a, b)
    }

    @Test fun creatorAddMember_advancesVersion_andAppears() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        val evt = creatorMgr.createSignedEvent(id, GroupAction.ADD, memberHex, memberVerify, memberKem)
        assertEquals(2L, evt.version)
        val g = creatorDb.groupDao().group(id)!!
        assertEquals(2L, g.membershipVersion)
        assertTrue(creatorDb.groupDao().activeMembers(id).any { it.memberId == memberHex })
    }

    @Test fun member_appliesValidCreatorEvent() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        seedMemberFromGenesis(id)
        val evt = creatorMgr.createSignedEvent(id, GroupAction.ADD, memberHex, memberVerify, memberKem)
        val r = memberMgr.verifyAndApply(evt)
        assertTrue(r is GroupManager.ApplyResult.Applied && r.newVersion == 2L, "expected Applied(2), got $r")
        assertTrue(memberDb.groupDao().activeMembers(id).any { it.memberId == memberHex })
    }

    @Test fun member_rejectsForgedSignature() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        seedMemberFromGenesis(id)
        val evt = creatorMgr.createSignedEvent(id, GroupAction.ADD, memberHex, memberVerify, memberKem)
        val tampered = evt.copy(name = "Not Trip")   // signed bytes no longer match the signature
        val r = memberMgr.verifyAndApply(tampered)
        assertTrue(r is GroupManager.ApplyResult.Rejected, "forged event must be rejected, got $r")
    }

    @Test fun member_rejectsStaleReplay() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        seedMemberFromGenesis(id)
        val evt = creatorMgr.createSignedEvent(id, GroupAction.ADD, memberHex, memberVerify, memberKem)
        assertTrue(memberMgr.verifyAndApply(evt) is GroupManager.ApplyResult.Applied)
        val again = memberMgr.verifyAndApply(evt)   // replay the same v2 event
        assertTrue(again is GroupManager.ApplyResult.Rejected, "replay must be rejected, got $again")
    }

    @Test fun member_rejectsWrongCreatorKey() = runBlocking {
        // Member has a group pinned to the real creator, but receives an event signed by someone else
        // who claims the same creatorId: the signature won't verify against the pinned creator key.
        val id = creatorMgr.createGroup("Trip")
        seedMemberFromGenesis(id)
        val imposter = newIdentity()
        val imposterMgr = GroupManager(FakeIdentityProvider(imposter), signer, Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build().groupDao(), Dispatchers.Unconfined)
        // Build a v2 ADD event over the member's pinned genesis state, signed by the imposter but
        // labelled with the real creatorId + real prevStateHash.
        val g = memberDb.groupDao().group(id)!!
        val bytes = GroupProtocol.eventBytes(id, "e".repeat(32), g.stateHash, 2L, GroupAction.ADD,
            memberHex, memberVerify, memberKem, g.name, g.creatorId, 1L)
        val badSig = signer.sign(bytes, imposter.privatePart.signingPrivateKey).getOrThrow()
        val forged = com.aura.db.GroupMembershipEventEntity("e".repeat(32), id, 2L, g.stateHash,
            GroupAction.ADD.name, memberHex, memberVerify, memberKem, g.name, g.creatorId, 1L, b64(badSig))
        val r = memberMgr.verifyAndApply(forged)
        assertTrue(r is GroupManager.ApplyResult.Rejected, "event signed by non-creator must be rejected, got $r")
    }

    @Test fun unknownGroup_isRejected() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        val evt = creatorMgr.createSignedEvent(id, GroupAction.ADD, memberHex, memberVerify, memberKem)
        // memberDb never seeded → unknown group
        assertTrue(memberMgr.verifyAndApply(evt) is GroupManager.ApplyResult.Rejected)
    }
}
