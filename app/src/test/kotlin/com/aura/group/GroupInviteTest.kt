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
import com.aura.testutil.FakeIdentityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage C: the invite → accept → creator-signed ADD → apply flow, exercised entirely through
 * GroupManager by handing the sealed payloads directly between two managers (the transport that
 * carries them is wired in the delivery stage). Includes the adversarial cases the threat model
 * requires: forged acceptance, invite from the wrong sender, decline, and idempotent re-accept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupInviteTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val signer = HybridSigner()
    private fun newIdentity(): NodeIdentity = runBlocking {
        NodeIdentityGenerator(HybridKem(), signer, Hkdf()).generate().getOrThrow()
    }

    private val creator = newIdentity()
    private val invitee = newIdentity()
    private val creatorDb = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()
    private val inviteeDb = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()
    private val creatorMgr = GroupManager(FakeIdentityProvider(creator), signer, creatorDb.groupDao(), Dispatchers.Unconfined)
    private val inviteeMgr = GroupManager(FakeIdentityProvider(invitee), signer, inviteeDb.groupDao(), Dispatchers.Unconfined)

    @AfterTest fun close() { creatorDb.close(); inviteeDb.close() }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private val creatorHex get() = creator.nodeId.toHex()
    private val inviteeHex get() = invitee.nodeId.toHex()
    private val inviteeVk get() = b64(invitee.publicPart.signingPublicKey.toBytes())
    private val inviteeKem get() = b64(invitee.publicPart.kemPublicKey.toBytes())

    @Test fun fullInviteAcceptFlow_bothSidesConverge() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        val inviteJson = creatorMgr.invite(id, inviteeHex, inviteeVk, inviteeKem)
        assertEquals(id, inviteeMgr.receiveInvite(inviteJson, creatorHex).getOrThrow())
        // Invitee is pending (group INVITED) until the ADD arrives.
        assertEquals("INVITED", inviteeDb.groupDao().group(id)!!.state)

        val resp = inviteeMgr.acceptInvite(id)
        val result = creatorMgr.receiveInviteResponse(resp)
        assertTrue(result is GroupManager.InviteResult.Added, "expected Added, got $result")
        val addEvent = (result as GroupManager.InviteResult.Added).addEvent

        val applied = inviteeMgr.verifyAndApply(addEvent)
        assertTrue(applied is GroupManager.ApplyResult.Applied && applied.newVersion == 2L, "got $applied")

        // Both sides: creator + invitee ACTIVE at v2, identical chained state hash, group ACTIVE.
        val cg = creatorDb.groupDao().group(id)!!; val ig = inviteeDb.groupDao().group(id)!!
        assertEquals(cg.stateHash, ig.stateHash)
        assertEquals(2L, cg.membershipVersion); assertEquals(2L, ig.membershipVersion)
        assertEquals("ACTIVE", ig.state)
        val cActive = creatorDb.groupDao().activeMembers(id).map { it.memberId }.toSet()
        val iActive = inviteeDb.groupDao().activeMembers(id).map { it.memberId }.toSet()
        assertEquals(setOf(creatorHex, inviteeHex), cActive)
        assertEquals(setOf(creatorHex, inviteeHex), iActive)
    }

    @Test fun forgedAcceptance_isRejected() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        creatorMgr.invite(id, inviteeHex, inviteeVk, inviteeKem)
        // A response claiming the invitee accepted, but signed by someone else (an attacker/relay).
        val attacker = newIdentity()
        val attackerMgr = GroupManager(FakeIdentityProvider(attacker), signer,
            Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build().groupDao(), Dispatchers.Unconfined)
        // Attacker signs an accept but labels it with the invitee's id.
        val forged = com.aura.group.GroupInviteCodec.encodeResponse(
            GroupInviteCodec.InviteResponse(id, inviteeHex, true, inviteeVk, inviteeKem,
                b64(signer.sign(GroupProtocol.inviteResponseBytes(id, inviteeHex, true), attacker.privatePart.signingPrivateKey).getOrThrow()))
        )
        val r = creatorMgr.receiveInviteResponse(forged)
        assertTrue(r is GroupManager.InviteResult.Ignored, "forged acceptance must be ignored, got $r")
        assertTrue(creatorDb.groupDao().activeMembers(id).none { it.memberId == inviteeHex })
    }

    @Test fun inviteFromWrongSender_isRejected() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        val inviteJson = creatorMgr.invite(id, inviteeHex, inviteeVk, inviteeKem)
        // Invitee receives the invite but over a channel from a DIFFERENT contact than the named creator.
        val wrong = inviteeMgr.receiveInvite(inviteJson, "ff".repeat(32))
        assertTrue(wrong.isFailure, "invite whose creator != sender must be rejected")
    }

    @Test fun decline_dropsGroup_andAddsNoMember() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        val inviteJson = creatorMgr.invite(id, inviteeHex, inviteeVk, inviteeKem)
        inviteeMgr.receiveInvite(inviteJson, creatorHex).getOrThrow()
        val resp = inviteeMgr.declineInvite(id)
        assertNull(inviteeDb.groupDao().group(id), "declined group should be dropped locally")
        assertTrue(creatorMgr.receiveInviteResponse(resp) is GroupManager.InviteResult.Declined)
        assertTrue(creatorDb.groupDao().activeMembers(id).none { it.memberId == inviteeHex })
    }

    @Test fun reAccept_isIdempotent() = runBlocking {
        val id = creatorMgr.createGroup("Trip")
        creatorMgr.invite(id, inviteeHex, inviteeVk, inviteeKem)
        inviteeMgr.receiveInvite(creatorMgr.invite(id, inviteeHex, inviteeVk, inviteeKem), creatorHex)  // re-invite safe
        val resp = inviteeMgr.acceptInvite(id)
        assertTrue(creatorMgr.receiveInviteResponse(resp) is GroupManager.InviteResult.Added)
        // A second accept must NOT add the member again.
        assertTrue(creatorMgr.receiveInviteResponse(resp) is GroupManager.InviteResult.Ignored)
        assertEquals(1, creatorDb.groupDao().activeMembers(id).count { it.memberId == inviteeHex })
    }
}
