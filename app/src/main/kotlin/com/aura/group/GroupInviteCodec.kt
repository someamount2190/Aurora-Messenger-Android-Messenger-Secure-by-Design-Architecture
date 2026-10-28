package com.aura.group

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialization for group invitations (Stage C). Invitations and responses travel inside the
 * existing pairwise sealed-control channel between the creator and the invitee, so the channel's
 * AEAD already authenticates the sender; the acceptance additionally carries the invitee's own
 * signature so the creator can prove the accept is genuinely theirs (not forged by a relay).
 */
object GroupInviteCodec {

    /** What the creator sends the invitee: enough authenticated genesis state to pin the group. */
    data class Invite(
        val groupId: String,
        val name: String,
        val creatorId: String,
        val creatorVerifyKeyB64: String,
        val version: Long,
        val stateHash: String,
        val members: List<MemberRecord>   // the current signed (ACTIVE/REMOVED) members
    )

    /** The invitee's signed accept/decline. */
    data class InviteResponse(
        val groupId: String,
        val inviteeId: String,
        val accept: Boolean,
        val inviteeVerifyKeyB64: String,
        val inviteeKemPubB64: String,
        val signatureB64: String   // invitee signature over GroupProtocol.inviteResponseBytes(...)
    )

    fun encodeInvite(i: Invite): String = JSONObject().apply {
        put("t", "grp-invite")
        put("groupId", i.groupId); put("name", i.name)
        put("creatorId", i.creatorId); put("creatorVerifyKeyB64", i.creatorVerifyKeyB64)
        put("version", i.version); put("stateHash", i.stateHash)
        put("members", JSONArray().apply {
            i.members.forEach {
                put(JSONObject().put("id", it.memberId).put("vk", it.verifyKeyB64)
                    .put("kem", it.kemPubB64).put("st", it.state))
            }
        })
    }.toString()

    fun decodeInvite(json: String): Invite {
        val o = JSONObject(json)
        require(o.optString("t") == "grp-invite") { "not a group invite" }
        val arr = o.optJSONArray("members") ?: JSONArray()
        val members = (0 until arr.length()).map {
            val m = arr.getJSONObject(it)
            MemberRecord(m.getString("id"), m.getString("vk"), m.getString("kem"), m.getString("st"))
        }
        return Invite(
            o.getString("groupId"), o.getString("name"), o.getString("creatorId"),
            o.getString("creatorVerifyKeyB64"), o.getLong("version"), o.getString("stateHash"), members
        )
    }

    fun encodeResponse(r: InviteResponse): String = JSONObject().apply {
        put("t", "grp-invite-resp")
        put("groupId", r.groupId); put("inviteeId", r.inviteeId); put("accept", r.accept)
        put("inviteeVerifyKeyB64", r.inviteeVerifyKeyB64); put("inviteeKemPubB64", r.inviteeKemPubB64)
        put("signatureB64", r.signatureB64)
    }.toString()

    fun decodeResponse(json: String): InviteResponse {
        val o = JSONObject(json)
        require(o.optString("t") == "grp-invite-resp") { "not a group invite response" }
        return InviteResponse(
            o.getString("groupId"), o.getString("inviteeId"), o.getBoolean("accept"),
            o.getString("inviteeVerifyKeyB64"), o.getString("inviteeKemPubB64"), o.getString("signatureB64")
        )
    }
}
