package app.unora.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Shared JSON contract with edge/src/protocol.ts. Media never traverses this protocol. */
@Serializable
data class Participant(
    val clientId: String,
    val nickname: String,
    val isHost: Boolean,
    val joinedAt: Long,
)

@Serializable
data class ChatMessage(
    val id: String,
    val clientId: String,
    val nickname: String,
    val text: String,
    val sentAt: Long,
)

@Serializable
data class SessionDescriptionPayload(val type: String, val sdp: String)

@Serializable
data class IceCandidatePayload(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val usernameFragment: String? = null,
)

@Serializable
sealed interface ClientSignal {
    @Serializable @SerialName("join")
    data class Join(val clientId: String, val nickname: String, val hostToken: String? = null) : ClientSignal

    @Serializable @SerialName("offer")
    data class Offer(val targetId: String, val description: SessionDescriptionPayload) : ClientSignal

    @Serializable @SerialName("answer")
    data class Answer(val targetId: String, val description: SessionDescriptionPayload) : ClientSignal

    @Serializable @SerialName("ice_candidate")
    data class IceCandidate(val targetId: String, val candidate: IceCandidatePayload) : ClientSignal

    @Serializable @SerialName("ice_restart")
    data class IceRestart(val targetId: String) : ClientSignal

    @Serializable @SerialName("chat_message")
    data class ChatMessage(val clientMessageId: String, val text: String) : ClientSignal

    @Serializable @SerialName("share_started") data object ShareStarted : ClientSignal
    @Serializable @SerialName("share_stopped") data object ShareStopped : ClientSignal
    @Serializable @SerialName("ping") data class Ping(val sentAt: Long? = null) : ClientSignal
}

@Serializable
sealed interface ServerSignal {
    @Serializable @SerialName("joined")
    data class Joined(
        val partyId: String,
        val self: Participant,
        val hostId: String? = null,
        val participants: List<Participant>,
        val chatHistory: List<app.unora.model.ChatMessage>,
        val shareActive: Boolean,
    ) : ServerSignal

    @Serializable @SerialName("participant_joined")
    data class ParticipantJoined(val participant: Participant) : ServerSignal

    @Serializable @SerialName("participant_left")
    data class ParticipantLeft(val clientId: String) : ServerSignal

    @Serializable @SerialName("peer_list")
    data class PeerList(val participants: List<Participant>, val hostId: String? = null) : ServerSignal

    @Serializable @SerialName("offer")
    data class Offer(val fromId: String, val description: SessionDescriptionPayload) : ServerSignal

    @Serializable @SerialName("answer")
    data class Answer(val fromId: String, val description: SessionDescriptionPayload) : ServerSignal

    @Serializable @SerialName("ice_candidate")
    data class IceCandidate(val fromId: String, val candidate: IceCandidatePayload) : ServerSignal

    @Serializable @SerialName("ice_restart") data class IceRestart(val fromId: String) : ServerSignal
    @Serializable @SerialName("chat_message") data class ChatMessage(val message: app.unora.model.ChatMessage) : ServerSignal
    @Serializable @SerialName("share_started") data class ShareStarted(val hostId: String) : ServerSignal
    @Serializable @SerialName("share_stopped") data class ShareStopped(val hostId: String) : ServerSignal
    @Serializable @SerialName("host_changed") data class HostChanged(val hostId: String? = null) : ServerSignal
    @Serializable @SerialName("pong") data class Pong(val sentAt: Long? = null) : ServerSignal
    @Serializable @SerialName("error") data class Error(val code: String, val message: String) : ServerSignal
}

@Serializable
data class CreatePartyResponse(val partyId: String, val hostToken: String, val joinUrl: String)

@Serializable
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class IceConfigResponse(val iceServers: List<IceServerConfig>)
