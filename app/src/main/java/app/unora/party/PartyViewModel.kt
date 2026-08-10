package app.unora.party

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unora.BuildConfig
import app.unora.data.IdentityRepository
import app.unora.data.PartyApi
import app.unora.data.ThemePreference
import app.unora.model.ClientSignal
import app.unora.model.PartyEffect
import app.unora.model.PartyUiState
import app.unora.util.InviteParser
import app.unora.util.NetworkMonitor
import app.unora.webrtc.IceServerConfig
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class PartyViewModel(application: Application) : AndroidViewModel(application) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "type"
    }
    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val partyApi = PartyApi(httpClient, json)
    private val identities = IdentityRepository(application)
    private val manager = PartyManager(
        scope = viewModelScope,
        identities = identities,
        api = partyApi,
        socketFactory = { OkHttpPartySocket(httpClient, json) },
    )
    private val networkMonitor = NetworkMonitor(application, manager)

    val state: StateFlow<PartyUiState> = manager.state
    val effects: SharedFlow<PartyEffect> = manager.effects
    private val _nickname = MutableStateFlow("")
    val nickname: StateFlow<String> = _nickname.asStateFlow()
    private val _avatarPath = MutableStateFlow<String?>(null)
    val avatarPath: StateFlow<String?> = _avatarPath.asStateFlow()
    private val _themePreference = MutableStateFlow(ThemePreference.SYSTEM)
    val themePreference: StateFlow<ThemePreference> = _themePreference.asStateFlow()

    init {
        networkMonitor.start()
        viewModelScope.launch {
            runCatching { identities.ensureIdentity() }
        }
        viewModelScope.launch {
            identities.identity.filterNotNull().collect { identity ->
                _nickname.value = identity.nickname
                _avatarPath.value = identity.avatarPath
            }
        }
        viewModelScope.launch {
            identities.themePreference.collect { _themePreference.value = it }
        }
    }

    fun createParty() = viewModelScope.launch { manager.createParty() }

    fun joinParty(invite: String) = viewModelScope.launch {
        InviteParser.parse(invite)
            .onSuccess { manager.joinParty(it) }
            .onFailure { manager.reportError("Digite um código ou link de convite válido.") }
    }

    fun sendChat(text: String) = manager.sendChat(text)
    fun updateNickname(rawNickname: String) = viewModelScope.launch {
        identities.updateNickname(rawNickname)
            .onSuccess { manager.refreshIdentity() }
            .onFailure { manager.reportError(it.message ?: "Não foi possível salvar seu nome.") }
    }
    fun updateAvatar(uri: Uri) = viewModelScope.launch {
        identities.updateAvatar(uri)
            .onFailure { manager.reportError(it.message ?: "Não foi possível salvar sua foto.") }
    }
    fun removeAvatar() = viewModelScope.launch { identities.removeAvatar() }
    fun updateThemePreference(preference: ThemePreference) = viewModelScope.launch {
        identities.updateThemePreference(preference)
    }
    fun leaveParty() = manager.leave()
    fun retryConnection() = manager.onNetworkChanged()
    fun requestingProjection() = manager.markRequestingProjection()
    fun sharingStarted() = manager.markSharingStarted()
    fun sharingStopped() = manager.markSharingStopped()
    fun sendSignaling(signal: ClientSignal): Boolean = manager.send(signal)
    fun attachPeerSignaling(delegate: PeerSignalingDelegate?) = manager.setSignalingDelegate(delegate)

    /**
     * Fetches short-lived ICE configuration without persisting it. The callback always runs from
     * [viewModelScope] on the main thread and receives STUN-only configuration if the edge is
     * unavailable or TURN is disabled in this build.
     */
    fun loadIceServers(onReady: (List<app.unora.webrtc.IceServerConfig>) -> Unit) {
        viewModelScope.launch {
            val servers = runCatching { partyApi.iceConfig().iceServers }
                .getOrDefault(emptyList())
                .mapNotNull { server ->
                    val urls = server.urls
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .filter { url -> BuildConfig.ENABLE_TURN || !url.isTurnUrl() }
                    urls.takeIf { it.isNotEmpty() }?.let {
                        IceServerConfig(urls = it, username = server.username, credential = server.credential)
                    }
                }
                .ifEmpty { listOf(IceServerConfig(urls = listOf(DEFAULT_STUN_URL))) }
            onReady(servers)
        }
    }

    override fun onCleared() {
        networkMonitor.stop()
        manager.leave()
        httpClient.dispatcher.executorService.shutdown()
        super.onCleared()
    }

    private fun String.isTurnUrl(): Boolean {
        val normalized = lowercase()
        return normalized.startsWith("turn:") || normalized.startsWith("turns:")
    }

    private companion object {
        const val DEFAULT_STUN_URL = "stun:stun.cloudflare.com:3478"
    }
}
