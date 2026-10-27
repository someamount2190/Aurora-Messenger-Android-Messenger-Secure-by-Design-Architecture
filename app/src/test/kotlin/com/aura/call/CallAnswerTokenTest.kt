package com.aura.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the P1-01 fix: only the exact live call token may accept a call via the notification
 * intent path. A forged ACTION_ANSWER_CALL intent from another app carries no (or a wrong)
 * token and must be rejected.
 */
class CallAnswerTokenTest {

    private val token = "Zm9vYmFyYmF6cXV4MTIzNA"   // a realistic URL-safe token

    @Test fun exactMatchAccepts() {
        assertTrue(CallAnswerToken.matches(token, token))
    }

    @Test fun nullOrEmptyProvidedRejected() {
        // A bare intent from another app: no token extra.
        assertFalse(CallAnswerToken.matches(token, null))
        assertFalse(CallAnswerToken.matches(token, ""))
    }

    @Test fun noCallRingingRejectsEverything() {
        // expected == null means no live token (no incoming call) — nothing may accept.
        assertFalse(CallAnswerToken.matches(null, token))
        assertFalse(CallAnswerToken.matches(null, null))
        assertFalse(CallAnswerToken.matches("", token))
    }

    @Test fun wrongTokenRejected() {
        assertFalse(CallAnswerToken.matches(token, "wrong-token"))
        assertFalse(CallAnswerToken.matches(token, token.dropLast(1)))        // truncated
        assertFalse(CallAnswerToken.matches(token, token + "x"))             // extended
        assertFalse(CallAnswerToken.matches(token, token.replaceRange(0, 1, "X"))) // one char off
    }
}
