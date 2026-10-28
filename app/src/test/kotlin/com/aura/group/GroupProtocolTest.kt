package com.aura.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pure protocol guards: canonical signing bytes and the membership state hash must be stable and
 *  order-independent, or signatures/chaining break across devices. */
class GroupProtocolTest {

    @Test fun eventBytes_areCanonicalAndStable() {
        val a = GroupProtocol.eventBytes("g", "e", "p", 3, GroupAction.ADD, "m", "vk", "kem", "Trip", "c", 123L)
        val b = GroupProtocol.eventBytes("g", "e", "p", 3, GroupAction.ADD, "m", "vk", "kem", "Trip", "c", 123L)
        assertTrue(a.contentEquals(b))
        assertEquals("aura-grp-evt-v1|g|e|p|3|ADD|m|vk|kem|Trip|c|123", String(a, Charsets.UTF_8))
    }

    @Test fun eventBytes_differ_whenAnyFieldChanges() {
        val base = GroupProtocol.eventBytes("g", "e", "p", 3, GroupAction.ADD, "m", "vk", "kem", "Trip", "c", 123L)
        val diff = GroupProtocol.eventBytes("g", "e", "p", 4, GroupAction.ADD, "m", "vk", "kem", "Trip", "c", 123L)
        assertTrue(!base.contentEquals(diff))
    }

    @Test fun stateHash_isOrderIndependentOverMembers() {
        val m1 = MemberRecord("aa", "v1", "k1", "ACTIVE")
        val m2 = MemberRecord("bb", "v2", "k2", "ACTIVE")
        assertEquals(
            GroupProtocol.stateHash("g", 2, "Trip", listOf(m1, m2)),
            GroupProtocol.stateHash("g", 2, "Trip", listOf(m2, m1))
        )
    }

    @Test fun stateHash_changesWithMemberStateAndVersion() {
        val m = MemberRecord("aa", "v1", "k1", "ACTIVE")
        val removed = m.copy(state = "REMOVED")
        assertNotEquals(
            GroupProtocol.stateHash("g", 2, "Trip", listOf(m)),
            GroupProtocol.stateHash("g", 2, "Trip", listOf(removed))
        )
        assertNotEquals(
            GroupProtocol.stateHash("g", 2, "Trip", listOf(m)),
            GroupProtocol.stateHash("g", 3, "Trip", listOf(m))
        )
    }

    @Test fun groupId_isDeterministicPerCreatorAndRandomness_butUniqueAcrossRandomness() {
        val r1 = ByteArray(32) { 1 }
        val r2 = ByteArray(32) { 2 }
        assertEquals(GroupProtocol.deriveGroupId("creator", r1), GroupProtocol.deriveGroupId("creator", r1))
        assertNotEquals(GroupProtocol.deriveGroupId("creator", r1), GroupProtocol.deriveGroupId("creator", r2))
        assertNotEquals(GroupProtocol.deriveGroupId("creatorA", r1), GroupProtocol.deriveGroupId("creatorB", r1))
    }
}
