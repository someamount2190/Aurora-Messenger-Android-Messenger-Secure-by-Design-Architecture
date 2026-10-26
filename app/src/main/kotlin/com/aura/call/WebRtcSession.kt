package com.aura.call

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * The WebRTC engine for a single call, extracted from [CallController]: the
 * PeerConnectionFactory, the peer connection + its observer, local audio/video
 * tracks, the camera capturer, SDP create/apply, ICE buffering, and one-time
 * disposal. [CallController] owns the call *state machine*; this owns the *media*.
 *
 * The threading and disposal invariants that previously caused process aborts are
 * preserved verbatim:
 *  - observer callbacks hop onto the app coroutine scope (never touch state inline on
 *    WebRTC's native signaling thread → no JNI `RTC_CHECK(!ExceptionCheck())` abort);
 *  - the WebRTC objects are disposed exactly once via an atomic CAS guard, peer
 *    connection first.
 */
class WebRtcSession(
    private val context: Context,
    private val scopeProvider: () -> CoroutineScope?,
    private val listener: Listener
) {
    /** Events the call state machine reacts to. Invoked on the app scope. */
    interface Listener {
        suspend fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnected()
        /** ICE dropped but may still recover on its own — don't tear down yet. */
        fun onDisconnected()
        /** ICE failed/closed — recovery (ICE restart) or termination is the controller's call. */
        fun onFailed()
    }

    val eglBase: EglBase by lazy { EglBase.create() }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    // True once the remote SDP is applied — ICE candidates may only be added after that.
    // Earlier-arriving remote candidates are buffered here and replayed by flushPendingIce().
    @Volatile private var remoteDescSet: Boolean = false
    private val pendingRemoteIce = java.util.Collections.synchronizedList(ArrayList<IceCandidate>())

    // Guards one-time disposal — endCall()/dispose() can fire from several racing paths
    // (local End, remote "bye", ICE FAILED/DISCONNECTED) and disposing the same native
    // PeerConnection twice aborts with "Pure virtual function called!". Reset on create().
    private val disposed = java.util.concurrent.atomic.AtomicBoolean(true)

    private val _videoEnabled = MutableStateFlow(true)
    val videoEnabled: StateFlow<Boolean> = _videoEnabled

    // Tracks exposed as state (not one-shot callbacks) so the UI renders them whenever it
    // composes, regardless of whether the track existed before or after the screen appeared.
    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo

    private var customIceServers: List<PeerConnection.IceServer>? = null

    fun setVideoEnabled(enabled: Boolean) { _videoEnabled.value = enabled }

    /** Configure custom ICE servers (e.g. ephemeral Coturn credentials for CGNAT traversal). */
    fun setIceServers(servers: List<PeerConnection.IceServer>) {
        customIceServers = servers
    }

    /** Clear ICE-buffering state before a new call's signaling begins. */
    fun resetForNewCall() {
        remoteDescSet = false
        pendingRemoteIce.clear()
    }

    fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        // Software-only VP8/VP9 codecs (libvpx, bundled in the webrtc-sdk): maximally
        // portable, verified for 1:1 voice/video, negligible CPU at that scale. Hardware
        // codecs can be re-enabled later. (The historical "codec" crash was actually a
        // notification exception escaping an observer callback — fixed via the scope hop
        // below and Notifier.safeNotify, not by the codec choice.)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
    }

    /**
     * Create the peer connection (and arm the one-time disposal guard). [relayOnly] limits ICE
     * to TURN-relayed candidates so the other side never learns our IP.
     */
    fun create(relayOnly: Boolean = false) {
        ensureFactory()
        val defaultIceServers = listOf(
            // Self-hosted STUN on deconflicted turn subdomain and rendezvous droplet
            PeerConnection.IceServer.builder("stun:turn.auroramessenger.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:api.auroramessenger.com:3478").createIceServer()
        )
        val iceServers = customIceServers?.takeIf { it.isNotEmpty() } ?: defaultIceServers
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Keep gathering candidates across the call so a network change (Wi-Fi<->cellular)
            // surfaces fresh local candidates that ICE can switch to / restart onto.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            if (relayOnly) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        android.util.Log.i("AuroraCall", "PC create: iceServers=${iceServers.flatMap { it.urls }}")
        disposed.set(false)   // a live peer connection now exists to tear down exactly once
        peerConnection = factory?.createPeerConnection(config, object : SimplePcObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                scopeProvider()?.launch { listener.onLocalIceCandidate(candidate) }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                android.util.Log.i("AuroraCall", "ICE -> $state")
                // CRITICAL: WebRTC delivers callbacks on its native signaling thread via JNI.
                // A Java exception escaping here aborts the process (RTC_CHECK(!ExceptionCheck())).
                // Never touch app state inline — hop onto the app scope where exceptions stay
                // contained and teardown can't deadlock the delivering thread.
                scopeProvider()?.launch {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> listener.onConnected()
                        PeerConnection.IceConnectionState.DISCONNECTED -> listener.onDisconnected()
                        PeerConnection.IceConnectionState.FAILED -> listener.onFailed()
                        // CLOSED is reached only via our own dispose() during teardown, so it
                        // must NOT be surfaced as a failure: doing so raced endCall()'s state
                        // update and spuriously triggered an ICE restart on the disposed
                        // session. (Same rule as the data path's RtcDataSession.)
                        else -> {}
                    }
                }
            }
            // UNIFIED_PLAN delivers remote media through onAddTrack (the receiver's track),
            // not Plan-B onAddStream — onAddStream is kept only as a defensive fallback.
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let { _remoteVideo.value = it }
            }
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { _remoteVideo.value = it }
            }
        })
    }

    fun addLocalTracks(video: Boolean) {
        val f = factory ?: return
        // Audio (always)
        localAudioTrack = f.createAudioTrack("audio0", f.createAudioSource(MediaConstraints()))
        peerConnection?.addTrack(localAudioTrack)

        // Video (front camera) — skipped entirely for a voice-only call.
        if (!video) {
            configureMediaPacing(video = false)
            return
        }
        val capturer = createCameraCapturer() ?: run {
            android.util.Log.w("AuroraCall", "no camera capturer — video track skipped")
            configureMediaPacing(video = false)
            return
        }
        videoCapturer = capturer
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val src = f.createVideoSource(capturer.isScreencast).also { videoSource = it }
        capturer.initialize(surfaceHelper, context, src.capturerObserver)
        val track = f.createVideoTrack("video0", src).apply { setEnabled(true) }
        localVideoTrack = track
        _localVideo.value = track
        peerConnection?.addTrack(track)
        configureMediaPacing(video = true)
        // Start the camera OFF the call-setup critical path. createOffer only needs the
        // track present (for the SDP m-line), not live frames — opening the camera (~0.5s)
        // must not delay the offer/ring. Capture starts in parallel; frames flow into the
        // already-added track once the camera is ready.
        scopeProvider()?.launch {
            val camT0 = System.currentTimeMillis()
            runCatching { capturer.startCapture(1280, 720, 30) }
                .onSuccess { android.util.Log.i("AuroraCall", "camera started (+${System.currentTimeMillis() - camT0}ms, async)") }
                .onFailure { android.util.Log.w("AuroraCall", "camera start failed: ${it.message}") }
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return null
        // A real events handler instead of null: camera-open failures used to be swallowed
        // silently, which is what made a broken first call invisible. Log them.
        val events = object : org.webrtc.CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(error: String?) { android.util.Log.w("AuroraCall", "camera error: $error") }
            override fun onCameraDisconnected() { android.util.Log.w("AuroraCall", "camera disconnected") }
            override fun onCameraFreezed(error: String?) { android.util.Log.w("AuroraCall", "camera frozen: $error") }
            override fun onCameraOpening(name: String?) {}
            override fun onFirstFrameAvailable() {}
            override fun onCameraClosed() {}
        }
        return enumerator.createCapturer(front, events)
    }

    suspend fun createOffer(): SessionDescription = suspendSdp { obs ->
        peerConnection?.createOffer(obs, MediaConstraints())
    }.let { SessionDescription(it.type, enforcePacingConstraints(it.description)) }

    suspend fun createAnswer(): SessionDescription = suspendSdp { obs ->
        peerConnection?.createAnswer(obs, MediaConstraints())
    }.let { SessionDescription(it.type, enforcePacingConstraints(it.description)) }

    /**
     * Create an offer that forces a fresh ICE gathering (new ufrag/pwd) so a dropped
     * connection can re-establish over a new network path. Used by [CallController] to
     * recover a call after a network change rather than dropping it.
     */
    suspend fun createRestartOffer(): SessionDescription = suspendSdp { obs ->
        peerConnection?.createOffer(obs, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        })
    }.let { SessionDescription(it.type, enforcePacingConstraints(it.description)) }

    fun setLocalDescription(sdp: SessionDescription) {
        peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
    }

    /** Apply the remote SDP, mark the description set, and replay any buffered ICE. */
    fun applyRemoteDescription(type: SessionDescription.Type, sdp: String) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), SessionDescription(type, sdp))
        remoteDescSet = true
        flushPendingIce()
    }

    /**
     * Add a remote ICE candidate now if the peer connection exists and its remote
     * description is set; otherwise buffer it (candidates routinely arrive before the
     * callee accepts or before the remote SDP is applied). [flushPendingIce] replays them.
     */
    fun addOrBufferIce(candidate: IceCandidate) {
        val pc = peerConnection
        if (pc != null && remoteDescSet) pc.addIceCandidate(candidate)
        else pendingRemoteIce.add(candidate)
    }

    private fun flushPendingIce() {
        val pc = peerConnection ?: return
        synchronized(pendingRemoteIce) {
            pendingRemoteIce.forEach { pc.addIceCandidate(it) }
            pendingRemoteIce.clear()
        }
    }

    private suspend fun suspendSdp(create: (SdpObserver) -> Unit): SessionDescription =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            create(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (cont.isActive) cont.resumeWith(Result.success(sdp))
                }
                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException(error)))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            })
        }

    /** @return true when now muted. */
    fun toggleMute(): Boolean {
        val track = localAudioTrack ?: return false
        track.setEnabled(!track.enabled())
        return !track.enabled()
    }

    /** @return true when video is now OFF. */
    fun toggleVideo(): Boolean {
        val track = localVideoTrack ?: return true
        val nowEnabled = !track.enabled()
        track.setEnabled(nowEnabled)
        _videoEnabled.value = nowEnabled
        return !nowEnabled
    }

    fun switchCamera() {
        (videoCapturer as? org.webrtc.CameraVideoCapturer)?.switchCamera(null)
    }

    /** Dispose the WebRTC objects exactly once (CAS guard) and reset media state. */
    fun dispose() {
        // Peer connection is disposed FIRST so its senders release the tracks before we
        // dispose the capturer/source — disposing a source still referenced by a live
        // sender aborts inside libjingle.
        if (disposed.compareAndSet(false, true)) {
            val pc = peerConnection; peerConnection = null
            val capt = videoCapturer; videoCapturer = null
            val src = videoSource; videoSource = null
            val helper = surfaceHelper; surfaceHelper = null
            pc?.dispose()
            try { capt?.stopCapture() } catch (e: Exception) {}
            capt?.dispose()
            src?.dispose()
            helper?.dispose()
        }
        localVideoTrack = null
        localAudioTrack = null
        _localVideo.value = null
        _remoteVideo.value = null
        _videoEnabled.value = true
        remoteDescSet = false
        pendingRemoteIce.clear()
    }

    /**
     * Configure decoupled pacing parameters on RTP senders (RT-06 Remediation):
     * rigid 20ms pacing for audio, and leaky-bucket burst pacing up to 2.5 Mbps for video.
     */
    private fun configureMediaPacing(video: Boolean) {
        val senders = peerConnection?.senders ?: return
        for (sender in senders) {
            val track = sender.track() ?: continue
            val params = sender.parameters ?: continue
            if (track.kind() == "video" && video) {
                for (enc in params.encodings) {
                    enc.maxBitrateBps = MAX_VIDEO_BITRATE_BPS
                    enc.minBitrateBps = MIN_VIDEO_BITRATE_BPS
                }
                sender.parameters = params
            } else if (track.kind() == "audio") {
                for (enc in params.encodings) {
                    enc.maxBitrateBps = AUDIO_CBR_BITRATE_BPS
                }
                sender.parameters = params
            }
        }
    }

    companion object {
        const val MAX_VIDEO_BITRATE_BPS = 2_500_000
        const val MIN_VIDEO_BITRATE_BPS = 300_000
        const val AUDIO_CBR_BITRATE_BPS = 64_000

        /** Session bandwidth caps (b=AS, kbps) written into each media section. */
        private const val AUDIO_AS_KBPS = 64
        private const val VIDEO_AS_KBPS = 2500

        /**
         * Enforces Opus CBR audio and per-media bandwidth caps on a local SDP. Works line by
         * line so the session description stays well-formed: each audio/video section gets
         * exactly one `b=AS` line, placed after its `i=`/`c=` lines as RFC 4566 §5 requires.
         * Idempotent, and preserves the input's line ending.
         */
        fun enforcePacingConstraints(sdp: String): String {
            val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
            val out = ArrayList<String>()
            var section = ArrayList<String>()
            fun flush() {
                val kbps = when {
                    section.firstOrNull()?.startsWith("m=audio ") == true -> AUDIO_AS_KBPS
                    section.firstOrNull()?.startsWith("m=video ") == true -> VIDEO_AS_KBPS
                    else -> null
                }
                if (kbps != null) {
                    section.removeAll { it.startsWith("b=AS:") }
                    var at = 1
                    while (at < section.size && (section[at].startsWith("i=") || section[at].startsWith("c="))) at++
                    section.add(at, "b=AS:$kbps")
                }
                out.addAll(section)
                section = ArrayList()
            }
            for (line in enforceOpusCbr(sdp).split(eol)) {
                if (line.startsWith("m=")) flush()
                section.add(line)
            }
            flush()
            return out.joinToString(eol)
        }

        /**
         * Adds `cbr=1` to the Opus format parameters (adding an `a=fmtp` line after its
         * `a=rtpmap` if there is none), asking the peer to send constant-bitrate audio so packet
         * sizes don't track speech. Idempotent, and preserves the input's line ending.
         */
        fun enforceOpusCbr(sdp: String): String {
            val pt = Regex("""a=rtpmap:(\d+) opus/48000""", RegexOption.IGNORE_CASE)
                .find(sdp)?.groupValues?.get(1) ?: return sdp
            val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
            val lines = sdp.split(eol).toMutableList()
            val fmtp = lines.indexOfFirst { it.startsWith("a=fmtp:$pt ") }
            if (fmtp >= 0) {
                if (lines[fmtp].split(';', ' ').none { it == "cbr=1" }) lines[fmtp] = lines[fmtp] + ";cbr=1"
            } else {
                val rtpmap = lines.indexOfFirst { it.startsWith("a=rtpmap:$pt ") }
                lines.add(rtpmap + 1, "a=fmtp:$pt cbr=1")
            }
            return lines.joinToString(eol)
        }
    }
}
