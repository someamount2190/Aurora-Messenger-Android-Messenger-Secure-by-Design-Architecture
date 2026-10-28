package com.aura.group

import org.json.JSONObject

/**
 * The authenticated metadata + body of a group message (prompt §5), carried INSIDE the existing
 * pairwise sealed payload. The pairwise AEAD (per-recipient ratchet) authenticates all of it under a
 * key only the sender and that one recipient hold; [aad] binds the ciphertext to the sender, the
 * recipient, and the group, so a frame can't be replayed into another group or re-attributed.
 */
object GroupEnvelope {
    const val AAD_PREFIX = "aura-grpmsg-v1"

    data class Envelope(
        val groupId: String,
        val messageId: String,
        val senderId: String,
        val senderSeq: Long,
        val logicalClock: Long,
        val membershipVersion: Long,
        val msgType: String,
        val timestampMs: Long,
        val body: String,
        val replyToId: String? = null
    )

    /** Associated data bound into the per-recipient AEAD (not encrypted, but authenticated). */
    fun aad(senderId: String, recipientId: String, groupId: String): ByteArray =
        "$AAD_PREFIX|$senderId|$recipientId|$groupId".toByteArray(Charsets.UTF_8)

    fun encode(e: Envelope): String = JSONObject().apply {
        put("g", e.groupId); put("mid", e.messageId); put("snd", e.senderId)
        put("seq", e.senderSeq); put("lc", e.logicalClock); put("mv", e.membershipVersion)
        put("mt", e.msgType); put("ts", e.timestampMs); put("body", e.body)
        e.replyToId?.let { put("rid", it) }
    }.toString()

    fun decode(json: String): Envelope {
        val o = JSONObject(json)
        return Envelope(
            o.getString("g"), o.getString("mid"), o.getString("snd"),
            o.getLong("seq"), o.getLong("lc"), o.getLong("mv"),
            o.getString("mt"), o.getLong("ts"), o.getString("body"),
            if (o.has("rid")) o.getString("rid") else null
        )
    }
}
