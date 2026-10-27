package com.aura.call

/**
 * Decides whether a notification's Answer action may accept the current call.
 *
 * The per-call token is minted when an incoming call is surfaced and travels ONLY in the
 * notification's immutable Answer PendingIntent. A forged `ACTION_ANSWER_CALL` intent from
 * another app has no valid token, so [matches] returns false and the mic/camera never open.
 *
 * Pure and dependency-free so the security decision is unit-tested in isolation (the rest of
 * [CallController] drags in WebRTC/Android and isn't unit-testable).
 */
object CallAnswerToken {

    /**
     * True only when [provided] equals the live [expected] token. Returns false when either is
     * null or blank (no call ringing, or a bare intent with no token). The comparison is
     * constant-time in the length of the expected token, so a mismatch doesn't leak it via timing.
     */
    fun matches(expected: String?, provided: String?): Boolean {
        if (expected.isNullOrEmpty() || provided.isNullOrEmpty()) return false
        val e = expected.toByteArray()
        val p = provided.toByteArray()
        var diff = e.size xor p.size
        for (i in e.indices) diff = diff or (e[i].toInt() xor p.getOrElse(i) { 0.toByte() }.toInt())
        return diff == 0
    }
}
