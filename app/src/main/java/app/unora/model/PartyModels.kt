package app.unora.model

enum class PartyPhase {
    Idle,
    CreatingParty,
    JoiningParty,
    Connected,
    WaitingForShare,
    RequestingProjection,
    Sharing,
    Watching,
    Reconnecting,
    Ended,
    Error,
}

enum class ConnectionQuality { Excellent, Good, Unstable, Unknown }

data class PartyUiState(
    val phase: PartyPhase = PartyPhase.Idle,
    val partyId: String? = null,
    val self: Participant? = null,
    val hostId: String? = null,
    val participants: List<Participant> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val isShareActive: Boolean = false,
    val connectionQuality: ConnectionQuality = ConnectionQuality.Unknown,
    val errorMessage: String? = null,
) {
    val isHost: Boolean get() = self?.clientId != null && self.clientId == hostId
    val participantCount: Int get() = participants.size
}

sealed interface PartyEffect {
    data class InviteReady(val partyId: String, val joinUrl: String) : PartyEffect
    data class UserMessage(val text: String) : PartyEffect
}
