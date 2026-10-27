package com.aura.server

/**
 * Canonical rendezvous **protocol** constants and the byte strings that both the
 * client and any rendezvous server sign/verify. This is the production-shared part
 * of the protocol: the Aurora client ([com.aura.network.RendezvousClient],
 * [com.aura.transport.DerpTransport]) uses it to talk to the live rendezvous
 * server over the network, so it must ship in every build.
 *
 * The in-app rendezvous *server* implementation (the NanoHTTPD listener that serves
 * these endpoints) is a developer-only tool and lives in the `debug` source set
 * (see `src/debug/.../server/AuroraRendezvousServer.kt`); it is never compiled into
 * release builds. The strings below must stay byte-for-byte identical to the ones in
 * `rendezvous-server/index.js`.
 */
object RendezvousProtocol {
    const val DEFAULT_PORT = 8080
    /** 15-minute registration TTL (Privacy Policy §3.2). */
    const val TTL_MS = 15 * 60 * 1000L

    /** Canonical byte string both client and server sign/verify for check-ins. */
    fun checkinMessage(nodeIdHex: String, ip: String, port: Int, timestampMs: Long): ByteArray =
        "aura-checkin-v1|$nodeIdHex|$ip|$port|$timestampMs".toByteArray(Charsets.UTF_8)

    /** Canonical byte string signed to authenticate a signal-queue drain. */
    fun drainMessage(nodeIdHex: String, timestampMs: Long): ByteArray =
        "aura-drain-v1|$nodeIdHex|$timestampMs".toByteArray(Charsets.UTF_8)

    /** Canonical byte string signed to answer a blind-relay connection challenge. */
    fun relayMessage(nodeIdHex: String, nonce: String): ByteArray =
        "aura-relay-v1|$nodeIdHex|$nonce".toByteArray(Charsets.UTF_8)
}
