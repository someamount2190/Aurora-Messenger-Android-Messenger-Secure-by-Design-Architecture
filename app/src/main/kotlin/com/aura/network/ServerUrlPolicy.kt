package com.aura.network

import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * Policy and trust capture for a user-chosen rendezvous server.
 *
 * Aurora only allows a **custom server over HTTPS** (a custom server is a presence/metadata
 * surface — see the in-app warning), and it trusts that server's certificate on a
 * **trust-on-first-use** basis: the server must present a certificate that chains to a
 * system-trusted CA, and we capture its SPKI pin so a later rogue CA can't transparently
 * intercept it. Self-signed certificates are rejected.
 *
 * Pure validation ([validate]) is unit-testable; [capturePin] performs one network probe.
 */
object ServerUrlPolicy {

    sealed interface ValidationError {
        val message: String
        data object Blank : ValidationError { override val message = "Enter a server address." }
        data object NotHttps : ValidationError {
            override val message = "The server must use https:// — plain http is not allowed."
        }
        data object Malformed : ValidationError {
            override val message = "That doesn't look like a valid https:// address."
        }
        data object HasCredentials : ValidationError {
            override val message = "Remove the username/password from the address."
        }
    }

    /**
     * Normalize and validate a user-entered server URL. Returns the canonical [HttpUrl]
     * (no trailing slash, no path beyond host[:port]) or a specific [ValidationError].
     */
    fun validate(raw: String): Result<HttpUrl> {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return Result.failure(PolicyException(ValidationError.Blank))
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://")) return Result.failure(PolicyException(ValidationError.NotHttps))
        if (!lower.startsWith("https://")) return Result.failure(PolicyException(ValidationError.Malformed))
        val url = trimmed.toHttpUrlOrNull() ?: return Result.failure(PolicyException(ValidationError.Malformed))
        if (url.host.isBlank()) return Result.failure(PolicyException(ValidationError.Malformed))
        if (url.username.isNotEmpty() || url.password.isNotEmpty())
            return Result.failure(PolicyException(ValidationError.HasCredentials))
        return Result.success(url)
    }

    /** The server's identity as shown to the user before they trust it. */
    data class CapturedCert(
        /** OkHttp SPKI pin, e.g. "sha256/AAAA…" — what we persist and pin against. */
        val spkiPin: String,
        /** Human-readable SHA-256 of the leaf certificate (colon-separated hex). */
        val fingerprint: String,
        /** The leaf certificate's subject DN, for display. */
        val subject: String
    )

    /**
     * Connect once to [validatedUrl] (standard system-CA validation, no pinning) and capture
     * the leaf certificate's pin + fingerprint. Fails if the TLS handshake fails — including
     * an untrusted/self-signed certificate, which surfaces as a trust error. Blocking; call off
     * the main thread.
     */
    fun capturePin(validatedUrl: HttpUrl): Result<CapturedCert> = runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        // /checkin GET is the server's liveness endpoint (returns ok without registering).
        val req = Request.Builder().url(validatedUrl.newBuilder().addPathSegment("checkin").build()).get().build()
        client.newCall(req).execute().use { resp ->
            val handshake = resp.handshake ?: error("No TLS handshake (is the server HTTPS?)")
            val leaf = handshake.peerCertificates.firstOrNull() as? X509Certificate
                ?: error("Server presented no certificate.")
            CapturedCert(
                spkiPin = CertificatePinner.pin(leaf),
                fingerprint = sha256Hex(leaf.encoded),
                subject = leaf.subjectX500Principal.name
            )
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(":") { "%02X".format(it) }

    class PolicyException(val error: ValidationError) : Exception(error.message)
}
