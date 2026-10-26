package com.aura.transport

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.HybridKem
import com.aura.crypto.HybridSigner
import com.aura.crypto.NodeIdentity
import com.aura.crypto.NodeIdentityGenerator
import com.aura.crypto.toHex
import com.aura.server.AuroraRendezvousServer
import com.aura.settings.AuroraSettings
import com.aura.settings.PeerIpExposurePolicy
import com.aura.testutil.FakeIdentityProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real [DerpTransport] against a scripted relay (MockWebServer speaking relay
 * protocol v2 over a real WebSocket), so the challenge signature, ref matching and ack
 * semantics are checked end to end on the client side. The server side of the protocol is
 * covered by rendezvous-server/test/server.test.js.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DerpTransportTest {

    private val signer = HybridSigner()
    private lateinit var identity: NodeIdentity
    private lateinit var server: MockWebServer
    private lateinit var relay: ScriptedRelay

    /** What the scripted relay does with an authenticated RELAY message. */
    private var onRelay: (WebSocket, JSONObject) -> Unit = { _, _ -> }
    private var acceptAuth = true

    private val inboxFrames = CopyOnWriteArrayList<JSONObject>()
    private val inbox = object : FrameInbox {
        override suspend fun processFrame(frame: JSONObject): JSONObject? {
            inboxFrames.add(frame)
            return JSONObject().put("t", "ack").put("id", frame.optString("id"))
        }
    }

    @Before fun setUp() = runBlocking {
        identity = NodeIdentityGenerator(HybridKem(), signer).generate().getOrThrow()
        relay = ScriptedRelay()
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relay))
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun transport(): DerpTransport {
        val settings = AuroraSettings(ApplicationProvider.getApplicationContext<Context>())
        settings.setServerAddress(server.url("/").toString().trimEnd('/'))
        return DerpTransport(FakeIdentityProvider(identity), settings, inbox, signer)
    }

    /** Server side of the protocol: challenge, verify AUTH, then hand messages to the test. */
    private inner class ScriptedRelay : WebSocketListener() {
        val nonce = "test-nonce-123"
        val received = LinkedBlockingQueue<JSONObject>()
        var authVerified = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject().put("action", "CHALLENGE").put("nonce", nonce).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            received.add(msg)
            when (msg.getString("action")) {
                "AUTH" -> {
                    val myId = identity.nodeId.toHex()
                    authVerified = msg.getString("nodeId") == myId &&
                        msg.getString("pub") == Base64.encodeToString(
                            identity.publicPart.signingPublicKey.ed25519PublicKey, Base64.NO_WRAP) &&
                        runBlocking {
                            signer.verifyEd25519Only(
                                AuroraRendezvousServer.relayMessage(myId, nonce),
                                Base64.decode(msg.getString("sig"), Base64.NO_WRAP),
                                identity.publicPart.signingPublicKey.ed25519PublicKey
                            ).getOrThrow()
                        }
                    val ok = authVerified && acceptAuth
                    webSocket.send(JSONObject().put("action", if (ok) "AUTH_OK" else "AUTH_FAIL").toString())
                }
                "RELAY" -> onRelay(webSocket, msg)
            }
        }

        fun next(action: String): JSONObject {
            while (true) {
                val m = received.poll(5, TimeUnit.SECONDS) ?: error("relay never received $action")
                if (m.getString("action") == action) return m
            }
        }
    }

    private val peer = "b".repeat(64)
    private fun frame(id: String) = JSONObject().put("t", "msg").put("id", id).put("from", identity.nodeId.toHex())

    @Test fun answersChallengeWithSignatureUnderCheckedInKey() = runBlocking {
        onRelay = { ws, m -> ws.send(JSONObject().put("action", "QUEUED").put("ref", m.getString("ref")).toString()) }
        transport().send(peer, frame("m1"))
        assertTrue(relay.authVerified, "AUTH must carry a valid Ed25519 signature over the challenge")
    }

    @Test fun returnsTheRecipientsAckMatchedByRef() = runBlocking {
        onRelay = { ws, m ->
            ws.send(JSONObject().put("action", "RESULT").put("ref", m.getString("ref"))
                .put("response", JSONObject().put("t", "ack").put("id", "m1")).toString())
        }
        val response = transport().send(peer, frame("m1"))
        val sent = relay.next("RELAY")
        assertEquals("m1", sent.getString("ref"))
        assertEquals(peer, sent.getString("to"))
        assertEquals("m1", JSONObject(sent.getString("payload")).getString("id"))
        assertEquals("ack", response?.getString("t"))
        assertEquals("m1", response?.getString("id"))
    }

    @Test fun queuedForAnOfflinePeerIsNotDeliveredAndReturnsPromptly() = runBlocking {
        onRelay = { ws, m -> ws.send(JSONObject().put("action", "QUEUED").put("ref", m.getString("ref")).toString()) }
        val t0 = System.currentTimeMillis()
        assertNull(transport().send(peer, frame("m2")))
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "QUEUED must not wait out the commit timeout")
    }

    @Test fun recipientFailureComesBackAsNull() = runBlocking {
        onRelay = { ws, m ->
            ws.send(JSONObject().put("action", "RESULT").put("ref", m.getString("ref"))
                .put("response", JSONObject.NULL).toString())
        }
        assertNull(transport().send(peer, frame("m3")))
    }

    @Test fun rejectedAuthMeansNoSend() = runBlocking {
        acceptAuth = false
        assertNull(transport().send(peer, frame("m4")))
        relay.next("AUTH")
        assertTrue(relay.received.none { it.getString("action") == "RELAY" })
    }

    @Test fun inboundFrameIsProcessedAndAckedWithTheInboxResponse() = runBlocking {
        val sender = "c".repeat(64)
        onRelay = { ws, m ->
            ws.send(JSONObject().put("action", "QUEUED").put("ref", m.getString("ref")).toString())
            ws.send(JSONObject().put("action", "INBOUND").put("ticket", "t-1").put("from", sender)
                .put("payload", JSONObject().put("t", "msg").put("id", "in-1").put("from", sender).toString())
                .toString())
        }
        transport().send(peer, frame("m5"))
        val ack = relay.next("ACK")
        assertEquals("t-1", ack.getString("ticket"))
        assertEquals("in-1", ack.getJSONObject("response").getString("id"))
        assertEquals(1, inboxFrames.size)
    }

    @Test fun inboundFrameClaimingAnotherSenderIsDropped() = runBlocking {
        onRelay = { ws, m ->
            ws.send(JSONObject().put("action", "QUEUED").put("ref", m.getString("ref")).toString())
            ws.send(JSONObject().put("action", "INBOUND").put("ticket", "t-2").put("from", "c".repeat(64))
                .put("payload", JSONObject().put("t", "msg").put("id", "x").put("from", "d".repeat(64)).toString())
                .toString())
        }
        transport().send(peer, frame("m6"))
        val ack = relay.next("ACK")
        assertTrue(ack.isNull("response"))
        assertTrue(inboxFrames.isEmpty())
    }

    // ── IP exposure policy ──────────────────────────────────────────────────

    @Test fun policyDefaultsToDirectAndParsesKeys() {
        assertEquals(PeerIpExposurePolicy.ALWAYS_P2P, PeerIpExposurePolicy.fromKey(null))
        assertEquals(PeerIpExposurePolicy.ALWAYS_P2P, PeerIpExposurePolicy.fromKey("unknown"))
        assertEquals(PeerIpExposurePolicy.STRICT_RELAY_ONLY, PeerIpExposurePolicy.fromKey("strict_relay"))
        assertEquals(PeerIpExposurePolicy.VERIFIED_CONTACTS_ONLY, PeerIpExposurePolicy.fromKey("verified_only"))
    }

    @Test fun policyGatesDirectPathsByVerification() {
        assertTrue(PeerIpExposurePolicy.ALWAYS_P2P.allowsDirect(mutuallyVerified = false))
        assertFalse(PeerIpExposurePolicy.VERIFIED_CONTACTS_ONLY.allowsDirect(mutuallyVerified = false))
        assertTrue(PeerIpExposurePolicy.VERIFIED_CONTACTS_ONLY.allowsDirect(mutuallyVerified = true))
        assertFalse(PeerIpExposurePolicy.STRICT_RELAY_ONLY.allowsDirect(mutuallyVerified = true))
    }
}
