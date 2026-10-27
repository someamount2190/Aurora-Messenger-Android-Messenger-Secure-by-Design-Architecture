package com.aura.server

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle + status for the in-app rendezvous server ("Server Mode", a developer
 * tool). This is only an **interface** in the main source set so that production code
 * ([com.aura.AppWiring], the Settings/Home view-models) can reference it in every build.
 *
 * The real implementation ([DebugRendezvousServerController], which actually starts the
 * NanoHTTPD server) lives in the `debug` source set and is bound by Hilt only in debug
 * builds. Release builds bind an inert no-op ([NoopRendezvousServerController]) so the
 * server code is never compiled into a shipping APK (user rule: no dev code in release).
 */
interface RendezvousServerController {
    val status: StateFlow<Status>

    fun start(port: Int = RendezvousProtocol.DEFAULT_PORT)
    fun stop()

    /** Re-poll node count / uptime; cheap, called by UI on a ticker. */
    fun refreshStatus()

    sealed interface Status {
        data object Stopped : Status
        data class Running(
            val port: Int,
            val localIp: String,
            val startedAtMs: Long,
            val nodeCount: Int
        ) : Status
        data class Error(val message: String) : Status
    }
}
