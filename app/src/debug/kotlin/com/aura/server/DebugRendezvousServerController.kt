package com.aura.server

import com.aura.crypto.HybridSigner
import com.aura.network.LocalNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real implementation of the in-app rendezvous server ("Server Mode"), a developer
 * tool. Compiled into **debug builds only** — Hilt binds it via the debug
 * [com.aura.di.ServerControllerModule]. Release builds get [NoopRendezvousServerController]
 * and never link the NanoHTTPD [AuroraRendezvousServer].
 */
@Singleton
class DebugRendezvousServerController @Inject constructor(
    private val signer: HybridSigner
) : RendezvousServerController {

    private var server: AuroraRendezvousServer? = null
    private var startedAtMs: Long = 0

    private val _status = MutableStateFlow<RendezvousServerController.Status>(
        RendezvousServerController.Status.Stopped
    )
    override val status: StateFlow<RendezvousServerController.Status> = _status

    @Synchronized
    override fun start(port: Int) {
        if (server != null) return
        try {
            val s = AuroraRendezvousServer(port, signer)
            s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            startedAtMs = System.currentTimeMillis()
            refreshStatus()
        } catch (e: Exception) {
            _status.value = RendezvousServerController.Status.Error(e.message ?: "failed to start server")
        }
    }

    @Synchronized
    override fun stop() {
        server?.stop()
        server = null
        _status.value = RendezvousServerController.Status.Stopped
    }

    override fun refreshStatus() {
        val s = server ?: return
        _status.value = RendezvousServerController.Status.Running(
            port        = RendezvousProtocol.DEFAULT_PORT,
            localIp     = LocalNetwork.localIpAddress() ?: "unknown",
            startedAtMs = startedAtMs,
            nodeCount   = s.registeredNodeCount
        )
    }
}
