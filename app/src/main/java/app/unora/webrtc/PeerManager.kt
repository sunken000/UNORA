package app.unora.webrtc

import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * Star topology coordinator. Signaling is intentionally injected: the party WebSocket remains
 * responsible for routing offer/answer/candidate messages to a single client id.
 */
class PeerManager(
    private val webRtcFactory: WebRtcFactory,
    private val iceServers: List<PeerConnection.IceServer>,
    private val signalSink: SignalSink,
    private val maxPartySize: Int = DEFAULT_MAX_PARTY_SIZE,
) : AutoCloseable {
    interface SignalSink {
        fun sendOffer(targetClientId: String, description: SessionDescription)
        fun sendAnswer(targetClientId: String, description: SessionDescription)
        fun sendIceCandidate(targetClientId: String, candidate: IceCandidate)
        fun onRemoteVideo(track: VideoTrack)
        fun onRemoteAudio(track: org.webrtc.AudioTrack)
        fun onConnectionState(clientId: String, state: PeerConnection.IceConnectionState)
        fun onError(clientId: String, message: String)
    }

    private val peers = ConcurrentHashMap<String, PeerConnectionClient>()
    private val earlyIceCandidates = ConcurrentHashMap<String, MutableList<IceCandidate>>()
    private var screenTrack: VideoTrack? = null
    private var playbackAudioEnabled = false

    fun setScreenTrack(track: VideoTrack?) {
        screenTrack = track
    }

    fun setPlaybackAudioEnabled(enabled: Boolean) {
        playbackAudioEnabled = enabled
    }

    fun beginHostOffer(viewerId: String, replaceExisting: Boolean = false): Boolean {
        if (replaceExisting) removePeer(viewerId)
        if (peers.containsKey(viewerId)) return true
        if (peers.size >= maxPartySize - 1) return false
        val peer = newPeer(viewerId)
        screenTrack?.let(peer::addScreenTrack)
        if (playbackAudioEnabled) peer.addPlaybackAudioTrack(webRtcFactory.playbackAudioTrack)
        peer.createOffer { signalSink.sendOffer(viewerId, it) }
        return true
    }

    fun handleOffer(hostId: String, description: SessionDescription) {
        val peer = peers[hostId] ?: newPeer(hostId)
        peer.acceptOffer(description) { signalSink.sendAnswer(hostId, it) }
    }

    fun handleAnswer(viewerId: String, description: SessionDescription) {
        peers[viewerId]?.acceptAnswer(description)
    }

    fun handleIceCandidate(senderId: String, candidate: IceCandidate) {
        val peer = peers[senderId]
        if (peer != null) peer.addIceCandidate(candidate)
        else earlyIceCandidates.getOrPut(senderId) { mutableListOf() }.add(candidate)
    }

    fun removePeer(clientId: String) {
        earlyIceCandidates.remove(clientId)
        peers.remove(clientId)?.close()
    }

    fun restartIce(clientId: String) {
        peers[clientId]?.restartIce { signalSink.sendOffer(clientId, it) }
    }

    fun collectStats(onStats: (String, ConnectionStats) -> Unit) {
        peers.forEach { (id, peer) -> peer.collectStats { onStats(id, it) } }
    }

    private fun newPeer(clientId: String): PeerConnectionClient {
        val peer = PeerConnectionClient(webRtcFactory, iceServers, object : PeerConnectionClient.Callbacks {
            override fun onIceCandidate(candidate: IceCandidate) = signalSink.sendIceCandidate(clientId, candidate)
            override fun onRemoteVideo(track: VideoTrack) = signalSink.onRemoteVideo(track)
            override fun onRemoteAudio(track: org.webrtc.AudioTrack) = signalSink.onRemoteAudio(track)
            override fun onConnectionState(state: PeerConnection.IceConnectionState) = signalSink.onConnectionState(clientId, state)
            override fun onFailure(message: String) = signalSink.onError(clientId, message)
        })
        peers[clientId] = peer
        earlyIceCandidates.remove(clientId)?.forEach(peer::addIceCandidate)
        return peer
    }

    override fun close() {
        peers.values.forEach(PeerConnectionClient::close)
        peers.clear()
        earlyIceCandidates.clear()
        screenTrack = null
        playbackAudioEnabled = false
    }

    companion object { const val DEFAULT_MAX_PARTY_SIZE = 8 }
}
