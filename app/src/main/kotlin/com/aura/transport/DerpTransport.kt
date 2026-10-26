package com.aura.transport

import android.util.Base64
import android.util.Log
import com.aura.crypto.HybridSigner
import com.aura.crypto.toHex
import com.aura.identity.IdentityProvider
import com.aura.server.AuroraRendezvousServer
import com.aura.settings.AuroraSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blind relay transport: the last-resort data path through the rendezvous server's
 * `/relay/stream` WebSocket, for when neither the WebRTC data channel nor direct TCP can
 * reach a peer (or IP exposure is disabled). Frames are already sealed by the KEM ratchet;
 * the server routes them by nodeId and never sees content.
 *
 * Protocol v2 (wire format in docs/PROTOCOL_RENDEZVOUS.md): the server challenges each
 * connection and we answer with an Ed25519 signature under our checked-in key, so nobody
 * else can receive our frames. A send resolves to the recipient's own [FrameInbox] response
 * (its ack), so "delivered" means the same here as on the RTC and TCP paths. The socket is
 * opened on demand (a send, or a wake from the server) and closed after [IDLE_TIMEOUT_MS].
 */
@Singleton
class DerpTransport @Inject constructor(
    private val identityProvider: IdentityProvider,
    private val settings: AuroraSettings,
    private val inbox: FrameInbox,
    private val signer: HybridSigner
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived WebSocket
        .build()

    private val connectMutex = Mutex()
    @Volatile private var webSocket: WebSocket? = null
    /** In-flight sends keyed by ref (the frame id): completes with the peer's response or null. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject?>>()

    private var idleJob: Job? = null
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(appScope: CoroutineScope) {
        scope = appScope
    }

    /**
     * Relay [frame] to [targetNodeIdHex]. Returns the recipient's response frame (normally
     * `{"t":"ack","id":…}`), or null if it wasn't delivered now — the peer is offline (the
     * server buffers the frame and wakes them), the relay is unreachable, or no ack arrived
     * in time. Null leaves the message pending for a later retry; redelivery is safe because
     * the recipient de-duplicates by message id.
     */
    suspend fun send(targetNodeIdHex: String, frame: JSONObject): JSONObject? {
        val ref = frame.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() }
        val ws = ensureConnected() ?: return null
        val result = CompletableDeferred<JSONObject?>()
        if (pending.putIfAbsent(ref, result) != null) return null   // same frame already in flight
        try {
            val msg = JSONObject()
                .put("action", "RELAY")
                .put("to", targetNodeIdHex)
                .put("ref", ref)
                .put("payload", frame.toString())
            if (!ws.send(msg.toString())) return null
            // The server answers an online delivery within its own commit window (15s), and
            // an offline one immediately; the margin covers the round trip.
            return withTimeoutOrNull(COMMIT_TIMEOUT_MS) { result.await() }
        } finally {
            pending.remove(ref)
            resetIdleTimer()
        }
    }

    /** Connect (if not already) so the server flushes any frames it buffered for us. */
    suspend fun resume() {
        ensureConnected()
    }

    private suspend fun ensureConnected(): WebSocket? = connectMutex.withLock {
        webSocket?.let { return@withLock it }

        val identity = runCatching { identityProvider.getOrCreate() }.getOrNull() ?: return@withLock null
        val myNodeIdHex = identity.nodeId.toHex()
        val wsUrl = settings.serverAddress.value
            .replace("http://", "ws://").replace("https://", "wss://")
            .trimEnd('/') + "/relay/stream"
        val request = runCatching { Request.Builder().url(wsUrl).build() }.getOrNull() ?: return@withLock null
        val authed = CompletableDeferred<Boolean>()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (e: Exception) { return }
                when (msg.optString("action")) {
                    "CHALLENGE" -> scope.launch {
                        val nonce = msg.optString("nonce")
                        val sig = runCatching {
                            signer.signEd25519Only(
                                AuroraRendezvousServer.relayMessage(myNodeIdHex, nonce),
                                identity.privatePart.signingPrivateKey
                            ).getOrThrow()
                        }.getOrNull()
                        if (sig == null) { authed.complete(false); return@launch }
                        webSocket.send(
                            JSONObject()
                                .put("action", "AUTH")
                                .put("nodeId", myNodeIdHex)
                                .put("pub", Base64.encodeToString(
                                    identity.publicPart.signingPublicKey.ed25519PublicKey, Base64.NO_WRAP))
                                .put("sig", Base64.encodeToString(sig, Base64.NO_WRAP))
                                .toString()
                        )
                    }
                    "AUTH_OK" -> authed.complete(true)
                    "AUTH_FAIL" -> authed.complete(false)
                    else -> {
                        resetIdleTimer()
                        handleServerMessage(webSocket, msg)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "relay connection failure: ${t.message}")
                authed.complete(false)
                dropped(webSocket)
            }
        })

        if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { authed.await() } == true) {
            webSocket = ws
            resetIdleTimer()
            ws
        } else {
            ws.cancel()
            null
        }
    }

    private fun handleServerMessage(ws: WebSocket, msg: JSONObject) {
        when (msg.optString("action")) {
            "INBOUND" -> {
                val ticket = msg.optString("ticket")
                val from = msg.optString("from")
                val payload = msg.optString("payload")
                scope.launch {
                    val frame = try { JSONObject(payload) } catch (e: Exception) { null }
                    // The server vouches for who sent this; a frame claiming someone else is
                    // dropped before it reaches the inbox.
                    val response = if (frame != null && frame.optString("from") == from) {
                        runCatching { inbox.processFrame(frame) }.getOrNull()
                    } else null
                    ws.send(
                        JSONObject()
                            .put("action", "ACK")
                            .put("ticket", ticket)
                            .put("response", response ?: JSONObject.NULL)
                            .toString()
                    )
                }
            }
            "RESULT" -> pending[msg.optString("ref")]?.complete(msg.optJSONObject("response"))
            "QUEUED", "NACK" -> pending[msg.optString("ref")]?.complete(null)
        }
    }

    /** [ws] has closed or failed: forget it (if still current) and fail its in-flight sends. */
    private fun dropped(ws: WebSocket) {
        if (webSocket === ws) {
            webSocket = null
            for (d in pending.values) d.complete(null)
        }
    }

    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            connectMutex.withLock {
                if (pending.isEmpty()) {
                    webSocket?.close(1000, "idle")
                    webSocket = null
                }
            }
        }
    }

    fun close() {
        idleJob?.cancel()
        webSocket?.close(1000, "shutdown")
        webSocket = null
        for (d in pending.values) d.complete(null)
    }

    companion object {
        private const val TAG = "AuroraDerp"
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val COMMIT_TIMEOUT_MS = 17_000L
        private const val IDLE_TIMEOUT_MS = 45_000L
    }
}
