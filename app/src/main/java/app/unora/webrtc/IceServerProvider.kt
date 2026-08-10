package app.unora.webrtc

import org.webrtc.PeerConnection

/**
 * ICE configuration delivered by the edge. TURN credentials are deliberately short lived and
 * are never stored in this object beyond the active party session.
 */
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

object IceServerProvider {
    private const val DEFAULT_STUN = "stun:stun.cloudflare.com:3478"

    fun create(servers: List<IceServerConfig> = emptyList()): List<PeerConnection.IceServer> {
        val configured = servers.ifEmpty { listOf(IceServerConfig(urls = listOf(DEFAULT_STUN))) }
        return configured.mapNotNull { server ->
            val urls = server.urls.filter { it.isNotBlank() }
            if (urls.isEmpty()) return@mapNotNull null
            PeerConnection.IceServer.builder(urls)
                .setUsername(server.username ?: "")
                .setPassword(server.credential ?: "")
                .createIceServer()
        }
    }
}
