package com.aura.transport.rtc

import com.aura.call.WebRtcSession
import com.aura.network.TurnCredentials
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NatTraversalHardeningTest {

    @Test
    fun opusCbr_injectsIntoExistingFmtp() {
        val sdp = """
            v=0
            o=- 12345 2 IN IP4 127.0.0.1
            s=-
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=rtpmap:111 opus/48000/2
            a=fmtp:111 minptime=10;useinbandfec=1
        """.trimIndent()

        val rewritten = WebRtcSession.enforceOpusCbr(sdp)
        assertTrue(rewritten.contains("a=fmtp:111 minptime=10;useinbandfec=1;cbr=1"), "Expected cbr=1 appended to fmtp: $rewritten")
    }

    @Test
    fun opusCbr_injectsNewFmtpWhenMissing() {
        val sdp = """
            v=0
            o=- 12345 2 IN IP4 127.0.0.1
            s=-
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=rtpmap:111 opus/48000/2
        """.trimIndent()

        val rewritten = WebRtcSession.enforceOpusCbr(sdp)
        assertTrue(rewritten.contains("a=fmtp:111 cbr=1"), "Expected new fmtp with cbr=1: $rewritten")
    }

    @Test
    fun opusCbr_leavesNonOpusSdpUntouched() {
        val sdp = """
            v=0
            o=- 12345 2 IN IP4 127.0.0.1
            s=-
            m=audio 9 UDP/TLS/RTP/SAVPF 0
            a=rtpmap:0 PCMU/8000
        """.trimIndent()

        val rewritten = WebRtcSession.enforceOpusCbr(sdp)
        assertEquals(sdp, rewritten)
    }

    /** The shape libwebrtc actually emits: CRLF endings, a c= line after each m= line, a data channel. */
    private val libwebrtcOffer = listOf(
        "v=0",
        "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "a=group:BUNDLE 0 1 2",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 0 8",
        "c=IN IP4 0.0.0.0",
        "a=rtcp:9 IN IP4 0.0.0.0",
        "a=mid:0",
        "a=rtpmap:111 opus/48000/2",
        "a=fmtp:111 minptime=10;useinbandfec=1",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97",
        "c=IN IP4 0.0.0.0",
        "b=AS:30",
        "a=mid:1",
        "a=rtpmap:96 VP8/90000",
        "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
        "c=IN IP4 0.0.0.0",
        "a=mid:2",
        ""
    ).joinToString("\r\n")

    @Test
    fun pacingConstraints_producesWellFormedSdpWithBitrateAfterConnectionLine() {
        val rewritten = WebRtcSession.enforcePacingConstraints(libwebrtcOffer)
        val expected = libwebrtcOffer
            .replace("c=IN IP4 0.0.0.0\r\na=rtcp", "c=IN IP4 0.0.0.0\r\nb=AS:64\r\na=rtcp")
            .replace("c=IN IP4 0.0.0.0\r\nb=AS:30\r\n", "c=IN IP4 0.0.0.0\r\nb=AS:2500\r\n")
            .replace("useinbandfec=1", "useinbandfec=1;cbr=1")
        assertEquals(expected, rewritten)
        // Every line (bar the terminating empty one) is a well-formed "x=value" SDP line.
        val lines = rewritten.split("\r\n")
        assertEquals("", lines.last())
        lines.dropLast(1).forEach { assertTrue(Regex("^[a-z]=.+").matches(it), "malformed SDP line: [$it]") }
        // The data-channel section is untouched.
        assertTrue(rewritten.endsWith("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:2\r\n"))
    }

    @Test
    fun pacingConstraints_isIdempotent() {
        val once = WebRtcSession.enforcePacingConstraints(libwebrtcOffer)
        assertEquals(once, WebRtcSession.enforcePacingConstraints(once))
    }

    @Test
    fun pacingConstraints_preservesLfLineEndings() {
        val lf = libwebrtcOffer.replace("\r\n", "\n")
        val rewritten = WebRtcSession.enforcePacingConstraints(lf)
        assertTrue(!rewritten.contains("\r"))
        assertTrue(rewritten.contains("m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 0 8\nc=IN IP4 0.0.0.0\nb=AS:64\n"))
    }

    @Test
    fun dataChannel_padToBucket_sizesAndJsonParse() {
        // Test short payload
        val json = JSONObject().put("t", "msg").put("id", "test-msg-1").put("body", "hello")
        val raw = json.toString().toByteArray(StandardCharsets.UTF_8)
        assertTrue(raw.size < 512)

        val padded512 = RtcDataSession.padToBucket(raw)
        assertEquals(512, padded512.size)

        // Ensure JSONObject can parse the padded byte array with trim()
        val parsedJson = JSONObject(String(padded512, StandardCharsets.UTF_8).trim())
        assertEquals("msg", parsedJson.getString("t"))
        assertEquals("test-msg-1", parsedJson.getString("id"))
        assertEquals("hello", parsedJson.getString("body"))

        // Test boundary buckets
        val exact512 = ByteArray(512) { 'a'.code.toByte() }
        assertEquals(512, RtcDataSession.padToBucket(exact512).size)

        val len513 = ByteArray(513) { 'a'.code.toByte() }
        assertEquals(2048, RtcDataSession.padToBucket(len513).size)

        val len2049 = ByteArray(2049) { 'a'.code.toByte() }
        assertEquals(8192, RtcDataSession.padToBucket(len2049).size)

        val len8193 = ByteArray(8193) { 'a'.code.toByte() }
        assertEquals(32768, RtcDataSession.padToBucket(len8193).size)

        val len32769 = ByteArray(32769) { 'a'.code.toByte() }
        assertEquals(32769, RtcDataSession.padToBucket(len32769).size)
    }

    @Test
    fun turnCredentials_convertsToIceServers() {
        val creds = TurnCredentials(
            username = "1729837200:test-node",
            password = "test-hmac-password",
            ttlSeconds = 86400,
            uris = listOf("turn:turn.auroramessenger.com:3478?transport=udp", "turns:turn.auroramessenger.com:5349?transport=tcp")
        )

        val iceServers = creds.toIceServers()
        assertTrue(iceServers.size >= 3)
        // Default STUN servers included
        assertTrue(iceServers.any { it.urls.any { u -> u.contains("api.auroramessenger.com:3478") } })
        // Dynamic TURN servers included with credentials
        val turnServer = iceServers.firstOrNull { it.urls.any { u -> u.contains("5349") } }
        assertTrue(turnServer != null)
        assertEquals("1729837200:test-node", turnServer.username)
        assertEquals("test-hmac-password", turnServer.password)
    }
}
