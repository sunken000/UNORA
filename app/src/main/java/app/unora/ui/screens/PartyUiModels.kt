package app.unora.ui.screens

import app.unora.model.PartyPhase

enum class PartyRole { HOST, VIEWER }

enum class StreamUiStatus {
    WAITING_FOR_SHARE,
    REQUESTING_PROJECTION,
    CONNECTING,
    LIVE,
    RECONNECTING,
    ENDED,
}

enum class ConnectionQuality(val label: String) {
    EXCELLENT("Excelente"), GOOD("Boa"), UNSTABLE("Instável"), OFFLINE("Offline")
}

data class ParticipantUi(
    val clientId: String,
    val nickname: String,
    val isHost: Boolean = false,
)

data class ChatMessageUi(
    val id: String,
    val senderId: String,
    val nickname: String,
    val text: String,
    val timestampLabel: String,
)

data class PartyUiState(
    val partyId: String,
    val role: PartyRole,
    val streamStatus: StreamUiStatus = StreamUiStatus.WAITING_FOR_SHARE,
    val participants: List<ParticipantUi> = emptyList(),
    val messages: List<ChatMessageUi> = emptyList(),
    val connectionQuality: ConnectionQuality = ConnectionQuality.GOOD,
    val errorMessage: String? = null,
    val isAudioMuted: Boolean = false,
    val isShareAudioAvailable: Boolean = true,
    val hasVideo: Boolean = false,
) {
    val watchingCount: Int get() = participants.size.coerceAtLeast(1)
    val isHost: Boolean get() = role == PartyRole.HOST
    val isLive: Boolean get() = streamStatus == StreamUiStatus.LIVE
}

/**
 * Boundary between composables and the party/capture layer.  The host app maps
 * PartyViewModel and MediaProjection callbacks onto this small UI contract.
 */
interface PartyUiActions {
    fun requestScreenShare()
    fun stopScreenShare()
    fun switchScreenShare()
    fun sendMessage(text: String)
    fun setLocalMuted(muted: Boolean)
    fun leaveParty()
    fun retry()
}

internal fun streamStatusFor(phase: PartyPhase, shareActive: Boolean): StreamUiStatus = when (phase) {
    PartyPhase.Connected, PartyPhase.WaitingForShare -> StreamUiStatus.WAITING_FOR_SHARE
    PartyPhase.RequestingProjection -> StreamUiStatus.REQUESTING_PROJECTION
    PartyPhase.CreatingParty, PartyPhase.JoiningParty -> StreamUiStatus.CONNECTING
    PartyPhase.Sharing, PartyPhase.Watching -> if (shareActive) StreamUiStatus.LIVE else StreamUiStatus.WAITING_FOR_SHARE
    PartyPhase.Reconnecting -> StreamUiStatus.RECONNECTING
    PartyPhase.Ended, PartyPhase.Error, PartyPhase.Idle -> StreamUiStatus.ENDED
}
