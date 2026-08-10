package app.unora.party

import android.util.Log
import app.unora.data.IdentityRepository
import app.unora.data.PartyApi
import app.unora.data.PartyApiException
import app.unora.model.ChatMessage
import app.unora.model.ClientSignal
import app.unora.model.PartyEffect
import app.unora.model.PartyPhase
import app.unora.model.PartyUiState
import app.unora.model.ServerSignal
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PartyManager(
    private val scope: CoroutineScope,
    private val identities: IdentityRepository,
    private val api: PartyApi,
    private val socketFactory: () -> PartySocket,
) {
    private val _state = MutableStateFlow(PartyUiState())
    val state: StateFlow<PartyUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<PartyEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<PartyEffect> = _effects.asSharedFlow()

    private var socket: PartySocket? = null
    private var socketEvents: Job? = null
    private var reconnectJob: Job? = null
    private var desiredPartyId: String? = null
    private var signalingDelegate: PeerSignalingDelegate? = null
    private var reconnectAttempts = 0
    private var explicitlyLeft = false

    fun setSignalingDelegate(delegate: PeerSignalingDelegate?) {
        signalingDelegate = delegate
    }

    suspend fun createParty(): Result<String> {
        leave(resetState = false)
        _state.value = PartyUiState(phase = PartyPhase.CreatingParty)
        var stage = "contatar o servidor"
        return runCatching {
            val created = api.createParty()
            stage = "salvar a sessão"
            identities.saveHostToken(created.partyId, created.hostToken)
            _effects.tryEmit(PartyEffect.InviteReady(created.partyId, created.joinUrl))
            stage = "abrir a conexão da party"
            connect(created.partyId, created.hostToken)
            created.partyId
        }.onFailure { error ->
            Log.e(TAG, "Party creation failed while trying to $stage", error)
            showError(createPartyError(stage, error))
        }
    }

    suspend fun joinParty(rawPartyId: String): Result<String> {
        val partyId = rawPartyId.uppercase()
        if (!PARTY_ID.matches(partyId)) {
            showError("Digite um código de party válido.")
            return Result.failure(IllegalArgumentException("Invalid party id"))
        }
        leave(resetState = false)
        _state.value = PartyUiState(phase = PartyPhase.JoiningParty, partyId = partyId)
        return runCatching {
            connect(partyId, identities.hostTokenFor(partyId))
            partyId
        }.onFailure { showError("Não foi possível entrar nesta party.") }
    }

    fun sendChat(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty() || cleanText.length > 1_000) return
        socket?.send(ClientSignal.ChatMessage(UUID.randomUUID().toString().replace("-", ""), cleanText))
    }

    fun send(signal: ClientSignal): Boolean = socket?.send(signal) == true

    suspend fun refreshIdentity() {
        val partyId = desiredPartyId ?: return
        connect(partyId, identities.hostTokenFor(partyId))
    }

    fun reportError(message: String) = showError(message)

    fun markRequestingProjection() {
        _state.value = _state.value.copy(phase = PartyPhase.RequestingProjection, errorMessage = null)
    }

    fun markSharingStarted() {
        socket?.send(ClientSignal.ShareStarted)
        _state.value = _state.value.copy(phase = PartyPhase.Sharing, isShareActive = true)
    }

    fun markSharingStopped() {
        socket?.send(ClientSignal.ShareStopped)
        val next = if (_state.value.isHost) PartyPhase.Connected else PartyPhase.WaitingForShare
        _state.value = _state.value.copy(phase = next, isShareActive = false)
    }

    fun onNetworkChanged() {
        if (desiredPartyId != null && _state.value.phase !in setOf(PartyPhase.Idle, PartyPhase.Ended)) scheduleReconnect(immediate = true)
    }

    fun leave(resetState: Boolean = true) {
        explicitlyLeft = true
        reconnectJob?.cancel()
        reconnectJob = null
        socketEvents?.cancel()
        socketEvents = null
        socket?.close()
        socket = null
        desiredPartyId = null
        reconnectAttempts = 0
        if (resetState) _state.value = PartyUiState(phase = PartyPhase.Ended)
    }

    private suspend fun connect(partyId: String, hostToken: String?) {
        desiredPartyId = partyId
        explicitlyLeft = false
        val identity = identities.ensureIdentity()
        socketEvents?.cancel()
        socket?.close()
        val currentSocket = socketFactory()
        socket = currentSocket
        socketEvents = scope.launch {
            currentSocket.events.collect { event -> handleSocketEvent(event) }
        }
        currentSocket.connect(api.partySocketUrl(partyId), ClientSignal.Join(identity.clientId, identity.nickname, hostToken))
    }

    private fun handleSocketEvent(event: PartySocketEvent) {
        when (event) {
            PartySocketEvent.Connected -> reconnectAttempts = 0
            is PartySocketEvent.Message -> handleSignal(event.signal)
            is PartySocketEvent.Closed -> if (!explicitlyLeft) scheduleReconnect()
            is PartySocketEvent.Failure -> if (!explicitlyLeft) scheduleReconnect()
        }
    }

    private fun handleSignal(signal: ServerSignal) {
        when (signal) {
            is ServerSignal.Joined -> {
                val phase = when {
                    signal.shareActive && signal.self.clientId != signal.hostId -> PartyPhase.Watching
                    signal.shareActive -> PartyPhase.Sharing
                    else -> PartyPhase.WaitingForShare
                }
                _state.value = PartyUiState(
                    phase = phase,
                    partyId = signal.partyId,
                    self = signal.self,
                    hostId = signal.hostId,
                    participants = signal.participants,
                    chatMessages = signal.chatHistory,
                    isShareActive = signal.shareActive,
                )
            }
            is ServerSignal.ParticipantJoined -> _state.value = _state.value.copy(
                participants = (_state.value.participants.filterNot { it.clientId == signal.participant.clientId } + signal.participant),
            )
            is ServerSignal.ParticipantLeft -> _state.value = _state.value.copy(
                participants = _state.value.participants.filterNot { it.clientId == signal.clientId },
            )
            is ServerSignal.PeerList -> _state.value = _state.value.copy(participants = signal.participants, hostId = signal.hostId)
            is ServerSignal.ChatMessage -> appendChat(signal.message)
            is ServerSignal.ShareStarted -> _state.value = _state.value.copy(
                isShareActive = true,
                phase = if (_state.value.self?.clientId == signal.hostId) PartyPhase.Sharing else PartyPhase.Watching,
            )
            is ServerSignal.ShareStopped -> _state.value = _state.value.copy(
                isShareActive = false,
                phase = if (_state.value.isHost) PartyPhase.Connected else PartyPhase.WaitingForShare,
            )
            is ServerSignal.HostChanged -> _state.value = _state.value.copy(hostId = signal.hostId)
            is ServerSignal.Error -> {
                if (signal.code in FATAL_SERVER_ERRORS) showError(signal.message)
                else _state.value = _state.value.copy(errorMessage = signal.message)
            }
            else -> Unit
        }
        signalingDelegate?.onSignal(signal)
    }

    private fun appendChat(message: ChatMessage) {
        val updated = (_state.value.chatMessages + message).takeLast(MAX_CHAT_HISTORY)
        _state.value = _state.value.copy(chatMessages = updated)
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (reconnectJob?.isActive == true || desiredPartyId == null) return
        _state.value = _state.value.copy(phase = PartyPhase.Reconnecting, errorMessage = null)
        reconnectJob = scope.launch {
            if (!immediate) delay((reconnectAttempts.coerceAtMost(4) + 1) * 1_000L)
            reconnectAttempts++
            val partyId = desiredPartyId ?: return@launch
            runCatching { connect(partyId, identities.hostTokenFor(partyId)) }
                .onFailure { scheduleReconnect() }
        }
    }

    private fun showError(message: String) {
        _state.value = _state.value.copy(phase = PartyPhase.Error, errorMessage = message)
        _effects.tryEmit(PartyEffect.UserMessage(message))
    }

    private fun createPartyError(stage: String, error: Throwable): String = when (error) {
        is PartyApiException -> "Servidor ${error.statusCode}: ${error.message}"
        is java.net.UnknownHostException -> "Não foi possível localizar o servidor."
        is javax.net.ssl.SSLException -> "Falha de segurança TLS: ${error.message ?: "certificado recusado"}."
        is java.net.SocketTimeoutException -> "O servidor demorou demais para responder."
        is java.io.IOException -> "Falha de rede: ${error.message ?: error.javaClass.simpleName}."
        else -> "Falha ao $stage: ${error.javaClass.simpleName}${error.message?.let { ": $it" }.orEmpty()}."
    }

    private companion object {
        const val TAG = "UnoraParty"
        val PARTY_ID = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$")
        const val MAX_CHAT_HISTORY = 100
        val FATAL_SERVER_ERRORS = setOf("party_not_found", "party_full", "join_required")
    }
}
