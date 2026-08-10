package app.unora.party

import app.unora.model.ServerSignal

/** Bridge owned by the WebRTC layer. It keeps signaling independent from UI and sockets. */
fun interface PeerSignalingDelegate {
    fun onSignal(signal: ServerSignal)
}
