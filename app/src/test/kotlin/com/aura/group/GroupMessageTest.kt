package com.aura.group

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.toHex
import com.aura.db.AuroraDatabase
import com.aura.db.GroupEntity
import com.aura.db.GroupMemberEntity
import com.aura.db.GroupMessageEntity
import com.aura.testutil.FakeIdentityProvider
import com.aura.testutil.testIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stage D: fan-out job creation, inbound dedup/replay, and deterministic ordering (no real ratchet;
 *  a fake sealer stands in — the real session sealing is exercised on device in the transport stage). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupMessageTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()

    private class FakeSealer : GroupSealer {
        val sealedTo = mutableListOf<String>()
        private var n = 0L
        override suspend fun seal(recipientId: String, plaintext: ByteArray, aad: ByteArray) =
            GroupSealer.Sealed(("S:$recipientId").toByteArray(), n++).also { sealedTo += recipientId }
        override suspend fun open(senderId: String, sealed: ByteArray, aad: ByteArray) = sealed
    }
    private val sealer = FakeSealer()

    private val me = testIdentity(1).nodeId.toHex()
    private val bob = "bb".repeat(32)
    private val carol = "cc".repeat(32)
    private val mgr = GroupManager(FakeIdentityProvider(testIdentity(1)), com.aura.crypto.HybridSigner(), db.groupDao(), Dispatchers.Unconfined)
    private val gmm = GroupMessageManager(FakeIdentityProvider(testIdentity(1)), db.groupDao(), db.groupMessageDao(), sealer, Dispatchers.Unconfined)

    @AfterTest fun close() = db.close()

    /** Seed an ACTIVE group of me+bob+carol directly (membership itself is covered by Stage B/C). */
    private suspend fun seedGroup(groupId: String = "g1") {
        db.groupDao().upsertGroup(GroupEntity(groupId, "Trip", me, "vk", 3, "sh", 0, "MEMBER", "ACTIVE"))
        listOf(me, bob, carol).forEach { db.groupDao().upsertMember(GroupMemberEntity(groupId, it, "vk", "kem", 1, "ACTIVE")) }
    }

    @Test fun fanOut_createsOneMessageAndPerRecipientSealedJobs() = runBlocking {
        seedGroup()
        val r = gmm.sendGroupMessage("g1", "hello")
        assertTrue(r is GroupMessageManager.SendResult.Queued && r.recipients == 2, "got $r")
        val mid = (r as GroupMessageManager.SendResult.Queued).messageId
        val deliveries = db.groupMessageDao().deliveries(mid)
        assertEquals(setOf(bob, carol), deliveries.map { it.recipientId }.toSet())
        assertTrue(deliveries.all { it.state == "ENCRYPTED_READY" && it.sealedB64 != null })
        assertEquals(setOf(bob, carol), sealer.sealedTo.toSet())
        assertEquals(1L, db.groupMessageDao().message(mid)!!.senderSeq)
    }

    @Test fun nonMember_cannotSend() = runBlocking {
        db.groupDao().upsertGroup(GroupEntity("g2", "X", "someone", "vk", 1, "sh", 0, "MEMBER", "ACTIVE"))
        db.groupDao().upsertMember(GroupMemberEntity("g2", bob, "vk", "kem", 1, "ACTIVE"))  // me not a member
        assertTrue(gmm.sendGroupMessage("g2", "hi") is GroupMessageManager.SendResult.NotAMember)
    }

    private fun env(mid: String, sender: String, seq: Long, ts: Long, lc: Long = seq, body: String = "m") =
        GroupEnvelope.encode(GroupEnvelope.Envelope("g1", mid, sender, seq, lc, 3, "text", ts, body))

    @Test fun receive_storesThenDedupsAndRejectsReplayAndNonMember() = runBlocking {
        seedGroup()
        assertTrue(gmm.receiveGroupMessage(bob, env("m1", bob, 1, 100)) is GroupMessageManager.ReceiveResult.Stored)
        // Exact duplicate messageId.
        assertTrue(gmm.receiveGroupMessage(bob, env("m1", bob, 1, 100)) is GroupMessageManager.ReceiveResult.Duplicate)
        // Sequence reuse: same (sender, seq) but a different messageId → replay/forgery.
        assertTrue(gmm.receiveGroupMessage(bob, env("m2", bob, 1, 101)) is GroupMessageManager.ReceiveResult.Rejected)
        // Sender not a member.
        assertTrue(gmm.receiveGroupMessage("dd".repeat(32), env("m3", "dd".repeat(32), 1, 102)) is GroupMessageManager.ReceiveResult.Rejected)
        // Sender mismatch vs authenticated channel.
        assertTrue(gmm.receiveGroupMessage(bob, env("m4", carol, 1, 103)) is GroupMessageManager.ReceiveResult.Rejected)
    }

    @Test fun delayedOlderMessage_isAccepted_andOrderedByTimestamp() = runBlocking {
        seedGroup()
        // Receive a later message first, then a delayed earlier one (different seq, new messageId).
        gmm.receiveGroupMessage(bob, env("mb2", bob, 2, 300))
        gmm.receiveGroupMessage(carol, env("mc1", carol, 1, 100))
        gmm.receiveGroupMessage(bob, env("mb1", bob, 1, 200))
        val order = gmm.conversation("g1").map { it.messageId }
        assertEquals(listOf("mc1", "mb1", "mb2"), order, "expected timestamp order")
    }

    @Test fun ordering_isTransitiveAndDeterministicUnderTies() {
        fun m(id: String, snd: String, seq: Long, ts: Long, lc: Long) =
            GroupMessageEntity(id, "g1", snd, false, "b", "text", ts, seq, lc, 3, "RECEIVED")
        // Same timestamp → broken by logicalClock, then senderId, then messageId (all stable).
        val a = m("a", "z", 1, 10, 5); val b = m("b", "a", 1, 10, 5); val c = m("c", "a", 1, 10, 7)
        val sorted = GroupOrdering.order(listOf(a, b, c)).map { it.messageId }
        // ts equal, lc: a=5,b=5,c=7 → c last; between a,b (lc 5) senderId "a"<"z" → b before a.
        assertEquals(listOf("b", "a", "c"), sorted)
        // Reversing input yields the same order (determinism).
        assertEquals(sorted, GroupOrdering.order(listOf(c, a, b)).map { it.messageId })
    }
}
