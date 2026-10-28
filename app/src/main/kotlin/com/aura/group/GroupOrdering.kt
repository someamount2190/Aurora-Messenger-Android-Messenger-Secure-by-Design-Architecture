package com.aura.group

import com.aura.db.GroupMessageEntity

/**
 * Deterministic total order for a group conversation (prompt §10).
 *
 * Messages are arranged by the sender's creation timestamp, but because clocks can skew we complete
 * it to a **transitive total order** with a fixed lexicographic key — never a naive, possibly
 * non-transitive "per-sender first then timestamp" comparator. Under normal (monotonic) clocks a
 * sender's own messages stay in `senderSeq` order because their timestamps and logical clock both
 * increase; under adversarial skew we fall back to this deterministic order rather than a cyclic one.
 * Display order is decoupled from delivery completeness; dedup is by `messageId`.
 */
object GroupOrdering {
    /** Transitive by construction (lexicographic over stable fields). */
    val comparator: Comparator<GroupMessageEntity> =
        compareBy({ it.timestampMs }, { it.logicalClock }, { it.senderId }, { it.senderSeq }, { it.messageId })

    fun order(messages: List<GroupMessageEntity>): List<GroupMessageEntity> =
        messages.sortedWith(comparator)
}
