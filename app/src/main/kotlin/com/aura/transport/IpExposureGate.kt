package com.aura.transport

import com.aura.db.ContactDao
import com.aura.settings.AuroraSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies [com.aura.settings.PeerIpExposurePolicy] to a specific contact, for every path that
 * could reveal our IP to them: direct TCP ([MessageSender] and [com.aura.media.MediaTransfer]),
 * and WebRTC ICE (data channel and calls). When a contact may not learn our IP, WebRTC is
 * limited to TURN-relayed candidates, so it only connects once TURN is configured; text
 * messages still reach them over the blind relay. Media has no relay path, so it waits for a
 * TURN-relayed data channel and the UI reports no permitted path in the meantime.
 */
@Singleton
class IpExposureGate @Inject constructor(
    private val settings: AuroraSettings,
    private val contactDao: ContactDao
) {
    /** True if connections to [peerNodeIdHex] must not reveal our IP (relay-only ICE). */
    suspend fun relayOnly(peerNodeIdHex: String): Boolean {
        val verified = contactDao.byNodeId(peerNodeIdHex)?.isMutuallyVerified == true
        return !settings.peerIpExposurePolicy.value.allowsDirect(verified)
    }
}
