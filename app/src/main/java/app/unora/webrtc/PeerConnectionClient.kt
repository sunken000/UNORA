package app.unora.webrtc

import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.AudioTrack
import org.webrtc.RtpParameters
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpTransceiver

/** One WebRTC connection. A host creates one of these for every viewer. */
class PeerConnectionClient(
    private val webRtcFactory: WebRtcFactory,
    iceServers: List<PeerConnection.IceServer>,
    private val callbacks: Callbacks,
) : AutoCloseable {
    interface Callbacks {
        fun onIceCandidate(candidate: IceCandidate)
        fun onRemoteVideo(track: VideoTrack)
        fun onRemoteAudio(track: AudioTrack)
        fun onConnectionState(state: PeerConnection.IceConnectionState)
        fun onFailure(message: String)
    }

    private val constraints = MediaConstraints()
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionApplied = false
    private var lastRemoteOfferSdp: String? = null
    private var lastRemoteAnswerSdp: String? = null
    private var cachedAnswer: SessionDescription? = null
    private var closed = false
    private val deliveredRemoteTrackIds = mutableSetOf<String>()
    private val peerConnection: PeerConnection = requireNotNull(
        webRtcFactory.factory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers).also {
                it.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                it.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                it.iceTransportsType = PeerConnection.IceTransportsType.ALL
            },
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    callbacks.onConnectionState(newState)
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) = callbacks.onIceCandidate(candidate)
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) {
                    stream.videoTracks.firstOrNull()?.let(::deliverRemoteTrack)
                    stream.audioTracks.firstOrNull()?.let(::deliverRemoteTrack)
                }
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                    receiver.track()?.let(::deliverRemoteTrack)
                }
                override fun onTrack(transceiver: RtpTransceiver) {
                    transceiver.receiver.track()?.let(::deliverRemoteTrack)
                }
            },
        ),
    )

    fun addScreenTrack(track: VideoTrack) {
        val sender = peerConnection.addTrack(track, listOf(STREAM_ID))
        val parameters = sender.parameters
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        parameters.encodings.forEach { encoding ->
            encoding.active = true
            encoding.maxBitrateBps = MAX_VIDEO_BITRATE_BPS
            encoding.maxFramerate = MAX_VIDEO_FRAMERATE
            encoding.bitratePriority = 2.0
        }
        sender.setParameters(parameters)
    }

    fun addPlaybackAudioTrack(track: AudioTrack) {
        peerConnection.addTrack(track, listOf(STREAM_ID))
    }

    fun createOffer(onCreated: (SessionDescription) -> Unit) = createDescription(true, onCreated)
    fun createAnswer(onCreated: (SessionDescription) -> Unit) = createDescription(false, onCreated)

    /**
     * Applies an offer exactly once. Durable Object reconnects and WebSocket retries can deliver
     * the same signaling packet more than once; answering it twice would make libwebrtc reject
     * the second answer with "Called in wrong state: stable".
     */
    fun acceptOffer(description: SessionDescription, onAnswer: (SessionDescription) -> Unit) {
        if (closed) return
        if (description.description == lastRemoteOfferSdp) {
            cachedAnswer?.let(onAnswer)
            return
        }
        if (peerConnection.signalingState() != PeerConnection.SignalingState.STABLE) return

        lastRemoteOfferSdp = description.description
        cachedAnswer = null
        remoteDescriptionApplied = false
        setRemoteDescription(description) { applied ->
            if (!applied) return@setRemoteDescription
            createAnswer { answer ->
                cachedAnswer = answer
                onAnswer(answer)
            }
        }
    }

    /** Ignores stale/duplicate answers instead of surfacing a fatal media error. */
    fun acceptAnswer(description: SessionDescription) {
        if (closed || description.description == lastRemoteAnswerSdp) return
        if (peerConnection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        lastRemoteAnswerSdp = description.description
        setRemoteDescription(description)
    }

    fun setRemoteDescription(description: SessionDescription, onComplete: (Boolean) -> Unit = {}) {
        peerConnection.setRemoteDescription(observer { applied ->
            if (applied) {
                remoteDescriptionApplied = true
                flushPendingIceCandidates()
            }
            onComplete(applied)
        }, description)
    }

    fun setLocalDescription(description: SessionDescription, onComplete: (Boolean) -> Unit = {}) {
        peerConnection.setLocalDescription(observer(onComplete), description)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (closed) return
        if (remoteDescriptionApplied) peerConnection.addIceCandidate(candidate)
        else pendingIceCandidates += candidate
    }

    fun restartIce(onCreated: (SessionDescription) -> Unit) {
        if (closed || peerConnection.signalingState() != PeerConnection.SignalingState.STABLE) return
        remoteDescriptionApplied = false
        lastRemoteAnswerSdp = null
        val restartConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        peerConnection.createOffer(object : SimpleSdpObserver(callbacks) {
            override fun onCreateSuccess(description: SessionDescription) {
                peerConnection.setLocalDescription(object : SimpleSdpObserver(callbacks) {
                    override fun onSetSuccess() = onCreated(description)
                }, description)
            }
        }, restartConstraints)
    }

    fun collectStats(onStats: (ConnectionStats) -> Unit) {
        peerConnection.getStats(statsObserver(onStats), null)
    }

    private fun createDescription(isOffer: Boolean, onCreated: (SessionDescription) -> Unit) {
        val callback = object : SimpleSdpObserver(callbacks) {
            override fun onCreateSuccess(description: SessionDescription) {
                peerConnection.setLocalDescription(object : SimpleSdpObserver(callbacks) {
                    override fun onSetSuccess() = onCreated(description)
                }, description)
            }
        }
        if (isOffer) peerConnection.createOffer(callback, constraints) else peerConnection.createAnswer(callback, constraints)
    }

    override fun close() {
        closed = true
        pendingIceCandidates.clear()
        deliveredRemoteTrackIds.clear()
        peerConnection.close()
        peerConnection.dispose()
    }

    private fun flushPendingIceCandidates() {
        pendingIceCandidates.forEach(peerConnection::addIceCandidate)
        pendingIceCandidates.clear()
    }

    private fun deliverRemoteTrack(track: MediaStreamTrack) {
        if (!deliveredRemoteTrackIds.add(track.id())) return
        when (track) {
            is VideoTrack -> callbacks.onRemoteVideo(track)
            is AudioTrack -> callbacks.onRemoteAudio(track)
        }
    }

    private fun observer(onComplete: (Boolean) -> Unit) = object : SimpleSdpObserver(callbacks) {
        override fun onSetSuccess() = onComplete(true)
        override fun onSetFailure(error: String) {
            callbacks.onFailure(error)
            onComplete(false)
        }
    }

    private open class SimpleSdpObserver(private val callbacks: Callbacks) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = callbacks.onFailure(error)
        override fun onSetFailure(error: String) = callbacks.onFailure(error)
    }

    private companion object {
        const val STREAM_ID = "unora-screen"
        const val MAX_VIDEO_BITRATE_BPS = 2_500_000
        const val MAX_VIDEO_FRAMERATE = 24
    }
}
