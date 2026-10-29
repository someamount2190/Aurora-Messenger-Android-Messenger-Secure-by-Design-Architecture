package com.aura.transport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridKem
import com.aura.crypto.KemRatchetManager
import com.aura.crypto.SymmetricCipher
import com.aura.db.AuroraDatabase
import com.aura.db.ContactEntity
import com.aura.db.MessageEntity
import com.aura.db.PairState
import com.aura.db.RoomKemSessionStore
import com.aura.network.InMemoryRendezvous
import com.aura.network.Rendezvous
import com.aura.settings.AuroraSettings
import com.aura.settings.PeerIpExposurePolicy
import com.aura.testutil.FakeIdentityProvider
import com.aura.testutil.testIdentity
import com.aura.transport.rtc.PeerTransport
import com.aura.transport.rtc.RtcState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "No permitted delivery path" must be *reported*, not left as a silent `pending`.
 *
 * The distinction under test is permission, not reachability: a contact whose policy
 * forbids direct connections and whose relay is off has no path this device may use, and
 * the conversation screen needs to say so. Reachability failures (peer offline) are a
 * different state and must not raise this flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NoDeliveryPathTest {

    private val peer = "cd".repeat(32)

    // ── The predicate itself ────────────────────────────────────────────────────────

    @Test
    fun permittedPath_isTrueWhenAnyPathIsAllowed() {
        assertTrue(hasPermittedDeliveryPath(relayEnabled = true, directAllowed = false, rtcConnected = false))
        assertTrue(hasPermittedDeliveryPath(relayEnabled = false, directAllowed = true, rtcConnected = false))
        assertTrue(hasPermittedDeliveryPath(relayEnabled = false, directAllowed = false, rtcConnected = true))
    }

    @Test
    fun permittedPath_isFalseOnlyWhenNothingIsAllowed() {
        assertFalse(hasPermittedDeliveryPath(relayEnabled = false, directAllowed = false, rtcConnected = false))
    }

    // ── End to end through the real sender ─────────────────────────────────────────

    @Test
    fun relayOff_andPolicyBlocksDirect_reportsNoPath_andStillTaps() = runBlocking {
        val h = harness(policy = PeerIpExposurePolicy.STRICT_RELAY_ONLY, relayEnabled = false)

        h.sender.flushPending()

        assertEquals("contact must be reported as undeliverable", setOf(peer), h.sender.noDeliveryPath.value)
        assertEquals("message must stay pending, not be dropped", "pending", h.db.messageDao().byId("m1")!!.status)
        assertTrue(
            "wake-up must still be attempted when the relay is off",
            h.rendezvous.taps > 0
        )
    }

    @Test
    fun relayOff_butDirectPermitted_isNotReported() = runBlocking {
        val h = harness(policy = PeerIpExposurePolicy.ALWAYS_P2P, relayEnabled = false)

        h.sender.flushPending()

        assertTrue(
            "a permitted (if unreachable) direct path must not raise the banner",
            h.sender.noDeliveryPath.value.isEmpty()
        )
        assertEquals("pending", h.db.messageDao().byId("m1")!!.status)
    }

    @Test
    fun relayOn_isNotReported_evenWhenPolicyBlocksDirect() = runBlocking {
        val h = harness(policy = PeerIpExposurePolicy.STRICT_RELAY_ONLY, relayEnabled = true)

        h.sender.flushPending()

        assertTrue("the relay is a permitted path", h.sender.noDeliveryPath.value.isEmpty())
    }

    @Test
    fun enablingRelay_clearsTheReportedState_onNextFlush() = runBlocking {
        val h = harness(policy = PeerIpExposurePolicy.STRICT_RELAY_ONLY, relayEnabled = false)
        h.sender.flushPending()
        assertEquals(setOf(peer), h.sender.noDeliveryPath.value)

        h.settings.setRelayEnabled(true)
        h.sender.flushPending()

        assertTrue(
            "state is derived per flush, so restoring a path must clear it",
            h.sender.noDeliveryPath.value.isEmpty()
        )
    }

    // ── Fixture ────────────────────────────────────────────────────────────────────

    private class Harness(
        val db: AuroraDatabase,
        val sender: MessageSender,
        val rendezvous: RecordingRendezvous,
        val settings: AuroraSettings
    )

    private suspend fun harness(policy: PeerIpExposurePolicy, relayEnabled: Boolean): Harness {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // clearAll() first so a policy left behind by another test in this sandbox cannot
        // make the assertions order-dependent.
        val settings = AuroraSettings(ctx)
        settings.clearAll()
        settings.setPeerIpExposurePolicy(policy)
        settings.setRelayEnabled(relayEnabled)

        val kem = KemRatchetManager(
            RoomKemSessionStore(db.ratchetDao()), HybridKem(), Hkdf(), SymmetricCipher()
        )
        val rendezvous = RecordingRendezvous()
        val sender = MessageSender(
            FakeIdentityProvider(testIdentity(0xC3)),
            kem,
            db.contactDao(),
            db.messageDao(),
            db.meshPeerDao(),
            rendezvous,
            settings,
            DisconnectedTransport(),
            Dispatchers.Unconfined
        )

        db.contactDao().upsert(
            ContactEntity(
                nodeIdHex = peer,
                displayName = "Peer",
                kemPubB64 = "aw==",
                ed25519PubB64 = "ZWU=",
                createdAtMs = 1,
                pairingSent = true,
                pairState = PairState.ACTIVE
            )
        )
        db.messageDao().insert(
            MessageEntity(
                "m1", peer, fromMe = true, body = "hi",
                timestampMs = 1, status = "pending", type = "text"
            )
        )
        return Harness(db, sender, rendezvous, settings)
    }

    /** Counts wake taps so we can prove the relay switch leaves signalling alone. */
    private class RecordingRendezvous(
        private val delegate: InMemoryRendezvous = InMemoryRendezvous()
    ) : Rendezvous by delegate {
        @Volatile var taps = 0
        override suspend fun tap(serverBaseUrl: String, peerNodeIdHex: String): Result<Unit> {
            taps++
            return delegate.tap(serverBaseUrl, peerNodeIdHex)
        }
    }

    /** A peer we cannot reach over the data channel — forces the sender down its fallbacks. */
    private class DisconnectedTransport : PeerTransport {
        override var mediaHandler: (suspend (meta: JSONObject, sealed: ByteArray) -> JSONObject?)? = null
        private val state = MutableStateFlow(RtcState.IDLE)
        override fun state(peerNodeIdHex: String): StateFlow<RtcState> = state
        override fun isConnected(peerNodeIdHex: String): Boolean = false
        override fun connectAsync(peerNodeIdHex: String) {}
        override suspend fun send(peerNodeIdHex: String, frame: JSONObject): JSONObject? = null
        override suspend fun sendMedia(
            peerNodeIdHex: String,
            startFrame: JSONObject,
            sealedBytes: ByteArray
        ): JSONObject? = null
    }
}
