package com.aura.media

import android.content.Context
import android.util.Base64
import com.aura.crypto.KemRatchetManager
import com.aura.crypto.NodeIdentity
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.identity.IdentityStore
import com.aura.transport.IpExposureGate
import com.aura.transport.MessageSender
import com.aura.transport.MessageSender.PeerAddress
import com.aura.transport.TcpMessageServer
import com.aura.transport.WireFrames
import com.aura.transport.rtc.PeerTransport
import com.aura.transport.rtc.RtcState
import com.aura.ux.MessagePulse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The IP-exposure gate must apply to media, not just text. Media has no relay path, so when
 * [IpExposureGate.relayOnly] says the contact may not learn our IP, the direct-TCP fallback
 * must not be attempted — and the conversation must be told there is no permitted path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaTransferGateTest {

    private val node = "ab".repeat(32)
    private val contact = ContactEntity(node, "Alice", "k", "e", createdAtMs = 1, pairingSent = true)

    private val context = mockk<Context>(relaxed = true)
    private val identity = mockk<IdentityStore>(relaxed = true)
    private val identityNode = mockk<NodeIdentity>(relaxed = true)
    private val kemRatchet = mockk<KemRatchetManager>(relaxed = true)
    private val contactDao = mockk<ContactDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val mediaStore = mockk<EncryptedMediaStore>(relaxed = true)
    private val messageSender = mockk<MessageSender>(relaxed = true)
    private val gate = mockk<IpExposureGate>(relaxed = true)
    private val tcpServer = mockk<TcpMessageServer>(relaxed = true)
    private val pulse = mockk<MessagePulse>(relaxed = true)
    private val rtc = DisconnectedTransport()

    private fun transfer() = MediaTransfer(
        context, identity, kemRatchet, contactDao, messageDao, mediaStore,
        messageSender, gate, tcpServer, rtc, pulse, Dispatchers.Unconfined
    )

    private fun stubCommon() {
        coEvery { identity.getOrCreate() } returns identityNode
        every { identityNode.nodeId } returns ByteArray(32) { 7 }
        coEvery { contactDao.byNodeId(node) } returns contact
        coEvery { kemRatchet.mediaKey(node) } returns ByteArray(32)
        coEvery { kemRatchet.sealNext(node, any(), any()) } returns
            KemRatchetManager.Sealed(ByteArray(8), 1)
        coEvery { kemRatchet.open(node, any(), any()) } returns ByteArray(8) { 2 }
        coEvery { mediaStore.writeEncrypted(any(), any(), any()) } returns "media/enc"
        every { mediaStore.isValidId(any()) } returns true
        coEvery { messageDao.byId(any()) } returns null
    }

    private val myNodeIdHex = "07".repeat(32)

    private fun inboundFrame(chunks: Int = 1) = JSONObject()
        .put("t", "media")
        .put("from", node)
        .put("to", myNodeIdHex)
        .put("id", "m1")
        .put("chunks", chunks)
        .put("mtype", "image")
        .put("ts", 1L)

    @Test fun relayOnlyContact_neverFallsBackToDirectTcp_andReportsNoPath() = runBlocking {
        stubCommon()
        coEvery { gate.relayOnly(node) } returns true
        coEvery { messageDao.pendingMedia() } returns listOf(
            MessageEntity(
                "m1", node, fromMe = true, body = "voice", timestampMs = 1,
                status = "pending", type = "audio", mediaPath = "media/enc"
            )
        )

        val transfer = transfer()
        val id = transfer.sendAudio(node, ByteArray(16), 0)

        assertNotNull(id)
        coVerify(exactly = 0) { messageSender.resolvePeerAddress(any()) }
        coVerify(exactly = 0) { messageSender.sendMediaChunked(any(), any(), any()) }
        assertTrue(node in transfer.noDeliveryPath.value, "no permitted media path is reported")
    }

    @Test fun directAllowed_usesTcp_acks_andClearsNoPath() = runBlocking {
        stubCommon()
        coEvery { gate.relayOnly(node) } returns false
        coEvery { messageDao.pendingMedia() } returns emptyList()
        coEvery { messageSender.resolvePeerAddress(any()) } returns PeerAddress("1.2.3.4", 5)
        coEvery { messageSender.sendMediaChunked(any(), any(), any()) } answers {
            JSONObject().put("t", "ack").put("id", secondArg<JSONObject>().optString("id"))
        }

        val transfer = transfer()
        val id = transfer.sendAudio(node, ByteArray(16), 0)

        assertNotNull(id)
        coVerify(exactly = 1) { messageSender.sendMediaChunked(any(), any(), any()) }
        coVerify { messageDao.setStatus(id, "delivered") }
        assertTrue(transfer.noDeliveryPath.value.isEmpty())
    }

    @Test fun relayOnlyContact_inboundTcpMediaIsRefusedBeforeReading() = runBlocking {
        stubCommon()
        coEvery { gate.relayOnly(node) } returns true
        val socket = mockk<Socket>(relaxed = true)
        // If the gate leaked, the handler would drain the payload before rejecting.
        every { socket.getInputStream() } throws IllegalStateException("payload must not be read")

        val accepted = transfer().handleIncomingTcp(inboundFrame(), socket)

        assertFalse(accepted, "inbound direct media must be refused under a relay-only policy")
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    @Test fun directAllowed_inboundTcpMediaIsStoredAndAcked() = runBlocking {
        stubCommon()
        coEvery { gate.relayOnly(node) } returns false
        val sealed = ByteArray(8) { 1 }
        val chunk = JSONObject()
            .put("t", "mediachunk")
            .put("id", "m1")
            .put("data", Base64.encodeToString(sealed, Base64.NO_WRAP))
        val wire = ByteArrayOutputStream().also { WireFrames.write(it, chunk) }
        val out = ByteArrayOutputStream()
        val socket = mockk<Socket>(relaxed = true)
        every { socket.getInputStream() } returns ByteArrayInputStream(wire.toByteArray())
        every { socket.getOutputStream() } returns out

        val accepted = transfer().handleIncomingTcp(inboundFrame(), socket)

        assertTrue(accepted, "a permitted direct path must still accept inbound media")
        coVerify {
            messageDao.insert(match { it.id == "m1" && !it.fromMe && it.mediaPath == "media/enc" })
        }
        val ack = WireFrames.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("ack", ack?.optString("t"))
        assertEquals("m1", ack?.optString("id"))
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
