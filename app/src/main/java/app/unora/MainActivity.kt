package app.unora

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.unora.capture.MediaProjectionRuntime
import app.unora.capture.MediaProjectionService
import app.unora.capture.ScreenCaptureManager
import app.unora.data.ThemePreference
import app.unora.model.ClientSignal
import app.unora.model.ConnectionQuality as CoreConnectionQuality
import app.unora.model.IceCandidatePayload
import app.unora.model.PartyPhase
import app.unora.model.ServerSignal
import app.unora.model.SessionDescriptionPayload
import app.unora.party.PartyViewModel
import app.unora.party.PeerSignalingDelegate
import app.unora.overlay.ChatOverlayRuntime
import app.unora.overlay.ChatOverlayService
import app.unora.ui.components.WebRtcStreamSurface
import app.unora.ui.screens.ChatMessageUi
import app.unora.ui.screens.ConnectionQuality
import app.unora.ui.screens.HomeScreen
import app.unora.ui.screens.HubDestination
import app.unora.ui.screens.MainHub
import app.unora.ui.screens.ParticipantUi
import app.unora.ui.screens.PartyRole
import app.unora.ui.screens.PartyScreen
import app.unora.ui.screens.PartyUiActions
import app.unora.ui.screens.PartyUiState
import app.unora.ui.screens.ProfileScreen
import app.unora.ui.screens.SettingsScreen
import app.unora.ui.screens.StreamUiStatus
import app.unora.ui.screens.streamStatusFor
import app.unora.ui.theme.UnoraTheme
import app.unora.webrtc.IceServerProvider
import app.unora.webrtc.PeerManager
import app.unora.webrtc.VideoFrameHealth
import app.unora.webrtc.VideoFrameHealthMonitor
import app.unora.webrtc.WebRtcFactory
import app.unora.webrtc.ConnectionQuality as WebRtcConnectionQuality
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate as WebRtcIceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

class MainActivity : ComponentActivity() {
    private enum class PendingNotificationAction { SHARE, MINIMIZE }

    private val partyViewModel: PartyViewModel by viewModels()
    private lateinit var webRtcFactory: WebRtcFactory
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var peerManager: PeerManager
    private var iceServers = IceServerProvider.create()
    private val iceRestartJobs = mutableMapOf<String, Job>()
    private var statsJob: Job? = null
    private var pendingProjectionLaunch = false
    private var isClosing = false
    private var isFullscreen by mutableStateOf(false)
    private var locallyMuted by mutableStateOf(false)
    private var localVideoTrack by mutableStateOf<VideoTrack?>(null)
    private var remoteVideoTrack by mutableStateOf<VideoTrack?>(null)
    private var renderedVideoTrack by mutableStateOf<VideoTrack?>(null)
    private var localVideoHealthMonitor: VideoFrameHealthMonitor? = null
    private var remoteVideoHealthMonitor: VideoFrameHealthMonitor? = null
    private var remoteAudioTrack by mutableStateOf<AudioTrack?>(null)
    private var shareAudioAvailable by mutableStateOf(true)
    private var peerQuality by mutableStateOf<ConnectionQuality?>(null)
    private var mediaError by mutableStateOf<String?>(null)
    private var videoWarning by mutableStateOf<String?>(null)
    private var hubDestination by mutableStateOf(HubDestination.HOME)
    private var partyScreenVisible by mutableStateOf(true)
    private var pendingNotificationAction: PendingNotificationAction? = null
    private var minimizeAfterOverlayPermission = false
    private var overlayPermissionGranted by mutableStateOf(false)
    private var projectionFlowActive = false
    private var suppressAutoOverlay = false

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingProjectionLaunch = false
        projectionFlowActive = false
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MediaProjectionService.start(this, result.resultCode, data)
        } else {
            partyViewModel.sharingStopped()
        }
    }

    private val recordAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Denial still leads to a valid, video-only projection.
        launchProjectionConsent()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        when (pendingNotificationAction) {
            PendingNotificationAction.SHARE -> requestAudioThenProjection()
            PendingNotificationAction.MINIMIZE -> continueMinimizeWithOverlay()
            null -> Unit
        }
        pendingNotificationAction = null
    }

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        suppressAutoOverlay = false
        if (minimizeAfterOverlayPermission) {
            minimizeAfterOverlayPermission = false
            if (Settings.canDrawOverlays(this)) startOverlayAndMinimize()
            else moveTaskToBack(true)
        }
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        suppressAutoOverlay = false
        uri?.let(partyViewModel::updateAvatar)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        overlayPermissionGranted = Settings.canDrawOverlays(this)

        webRtcFactory = WebRtcFactory(applicationContext)
        ChatOverlayRuntime.bind(partyViewModel.state, partyViewModel::sendChat)
        rebuildPeerManager()
        partyViewModel.attachPeerSignaling(PeerSignalingDelegate(::handlePeerSignal))
        partyViewModel.loadIceServers { configured ->
            iceServers = IceServerProvider.create(configured)
            if (partyViewModel.state.value.partyId == null && !isClosing) rebuildPeerManager()
        }

        screenCaptureManager = ScreenCaptureManager(
            context = applicationContext,
            webRtcFactory = webRtcFactory,
            onStateChanged = ::handleCaptureState,
        )
        MediaProjectionRuntime.bind(screenCaptureManager)
        startStatsPolling()
        handleInvite(intent)

        setContent {
            val coreState by partyViewModel.state.collectAsState()
            val nickname by partyViewModel.nickname.collectAsState()
            val avatarPath by partyViewModel.avatarPath.collectAsState()
            val themePreference by partyViewModel.themePreference.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themePreference) {
                ThemePreference.SYSTEM -> systemDark
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            val activeVideoTrack = if (coreState.isHost) localVideoTrack else remoteVideoTrack
            val uiState = coreState.toScreenState(
                muted = locallyMuted,
                peerQuality = peerQuality,
                shareAudioAvailable = shareAudioAvailable,
                mediaError = mediaError ?: videoWarning,
                // A negotiated VideoTrack is not proof that a frame exists. Keep the connecting
                // state until SurfaceViewRenderer confirms that EGL rendered this exact track.
                hasVideo = activeVideoTrack != null && renderedVideoTrack === activeVideoTrack,
            )
            val inParty = coreState.partyId != null &&
                coreState.phase !in setOf(PartyPhase.Idle, PartyPhase.CreatingParty, PartyPhase.JoiningParty)
            val actions = object : PartyUiActions {
                override fun requestScreenShare() = beginScreenShare()
                override fun stopScreenShare() = stopLocalShare()
                override fun switchScreenShare() = this@MainActivity.switchScreenShare()
                override fun sendMessage(text: String) = partyViewModel.sendChat(text)
                override fun setLocalMuted(muted: Boolean) = setRemoteMuted(muted)
                override fun leaveParty() = leaveCurrentParty()
                override fun retry() = partyViewModel.retryConnection()
            }

            SideEffect {
                if (inParty) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isFullscreen) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            }

            UnoraTheme(darkTheme = darkTheme) {
                if (inParty && partyScreenVisible) {
                    BackHandler {
                        if (isFullscreen) applyFullscreen(false)
                        else partyScreenVisible = false
                    }
                    PartyScreen(
                        state = uiState,
                        actions = actions,
                        isFullscreen = isFullscreen,
                        onFullscreenChange = ::applyFullscreen,
                        onBackToHub = {
                            applyFullscreen(false)
                            partyScreenVisible = false
                        },
                        onMinimize = ::minimizeParty,
                        onCopyText = ::copyText,
                        onShareInvite = ::shareInvite,
                        renderSurface = {
                            WebRtcStreamSurface(
                                track = activeVideoTrack,
                                eglContext = webRtcFactory.eglBase.eglBaseContext,
                                modifier = Modifier.fillMaxSize(),
                                onFirstFrameRendered = {
                                    if (activeVideoTrack != null) renderedVideoTrack = activeVideoTrack
                                },
                            )
                        },
                    )
                } else {
                    BackHandler(enabled = hubDestination != HubDestination.HOME || inParty) {
                        if (hubDestination != HubDestination.HOME) hubDestination = HubDestination.HOME
                        else if (inParty) minimizeParty()
                    }
                    MainHub(
                        selected = hubDestination,
                        onSelected = { hubDestination = it },
                        activePartyId = coreState.partyId.takeIf { inParty },
                        onReturnToParty = { partyScreenVisible = true },
                        onLeaveParty = ::leaveCurrentParty,
                    ) { screenModifier ->
                        when (hubDestination) {
                            HubDestination.HOME -> HomeScreen(
                                isWorking = coreState.phase in setOf(PartyPhase.CreatingParty, PartyPhase.JoiningParty),
                                error = coreState.errorMessage,
                                nickname = nickname,
                                avatarPath = avatarPath,
                                onCreateParty = { if (inParty) partyScreenVisible = true else partyViewModel.createParty() },
                                onJoinParty = { invite -> if (inParty) partyScreenVisible = true else partyViewModel.joinParty(invite) },
                                onOpenProfile = { hubDestination = HubDestination.PROFILE },
                                onOpenSettings = { hubDestination = HubDestination.SETTINGS },
                                modifier = screenModifier,
                            )
                            HubDestination.PROFILE -> ProfileScreen(
                                nickname = nickname,
                                avatarPath = avatarPath,
                                onSaveNickname = partyViewModel::updateNickname,
                                onChoosePhoto = {
                                    suppressAutoOverlay = true
                                    photoPicker.launch(arrayOf("image/*"))
                                },
                                onRemovePhoto = partyViewModel::removeAvatar,
                                modifier = screenModifier,
                            )
                            HubDestination.SETTINGS -> SettingsScreen(
                                themePreference = themePreference,
                                overlayAllowed = overlayPermissionGranted,
                                onThemePreference = partyViewModel::updateThemePreference,
                                onOpenOverlayPermission = {
                                    suppressAutoOverlay = true
                                    overlayPermissionLauncher.launch(ChatOverlayService.permissionIntent(this))
                                },
                                modifier = screenModifier,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInvite(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::screenCaptureManager.isInitialized) screenCaptureManager.updateCaptureFormat()
    }

    private fun handleInvite(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.dataString?.let(partyViewModel::joinParty)
    }

    private fun beginScreenShare() {
        mediaError = null
        videoWarning = null
        projectionFlowActive = true
        partyViewModel.requestingProjection()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = PendingNotificationAction.SHARE
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestAudioThenProjection()
        }
    }

    private fun requestAudioThenProjection() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjectionConsent()
        }
    }

    private fun launchProjectionConsent() {
        if (pendingProjectionLaunch) return
        pendingProjectionLaunch = true
        val manager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // App-window projection can stop producing frames while the selected app is fully
            // hidden and may move UNORA to the background. Full-display capture is deterministic
            // for a watch party: the user can switch apps normally after sharing starts.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }

    private fun handleCaptureState(captureState: ScreenCaptureManager.State) {
        runOnUiThread {
            when (captureState) {
                ScreenCaptureManager.State.SHARING,
                ScreenCaptureManager.State.SHARING_WITHOUT_AUDIO -> {
                    detachLocalVideoHealthMonitor()
                    localVideoTrack = screenCaptureManager.videoTrack
                    renderedVideoTrack = null
                    localVideoTrack?.let(::attachLocalVideoHealthMonitor)
                    shareAudioAvailable = captureState == ScreenCaptureManager.State.SHARING
                    peerManager.setScreenTrack(localVideoTrack)
                    peerManager.setPlaybackAudioEnabled(shareAudioAvailable)
                    partyViewModel.sharingStarted()
                    beginOffersForCurrentViewers()
                }
                ScreenCaptureManager.State.STOPPED,
                ScreenCaptureManager.State.REVOKED,
                ScreenCaptureManager.State.ERROR -> {
                    detachLocalVideoHealthMonitor()
                    if (renderedVideoTrack === localVideoTrack) renderedVideoTrack = null
                    localVideoTrack = null
                    shareAudioAvailable = false
                    if (!isClosing) {
                        rebuildPeerManager()
                        val current = partyViewModel.state.value
                        if (current.isShareActive || current.phase == PartyPhase.RequestingProjection) {
                            partyViewModel.sharingStopped()
                        }
                    }
                }
                ScreenCaptureManager.State.IDLE,
                ScreenCaptureManager.State.STARTING -> Unit
            }
        }
    }

    private fun stopLocalShare() {
        detachLocalVideoHealthMonitor()
        if (::screenCaptureManager.isInitialized) screenCaptureManager.stop()
        MediaProjectionService.stop(this)
    }

    private fun switchScreenShare() {
        stopLocalShare()
        lifecycleScope.launch {
            delay(350)
            if (partyViewModel.state.value.isHost && partyViewModel.state.value.partyId != null) beginScreenShare()
        }
    }

    private fun minimizeParty() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = PendingNotificationAction.MINIMIZE
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueMinimizeWithOverlay()
        }
    }

    private fun continueMinimizeWithOverlay() {
        if (Settings.canDrawOverlays(this)) {
            startOverlayAndMinimize()
        } else {
            minimizeAfterOverlayPermission = true
            overlayPermissionLauncher.launch(ChatOverlayService.permissionIntent(this))
        }
    }

    private fun startOverlayAndMinimize() {
        applyFullscreen(false)
        ChatOverlayService.start(this)
        moveTaskToBack(true)
    }

    private fun leaveCurrentParty() {
        applyFullscreen(false)
        ChatOverlayService.stop(this)
        stopLocalShare()
        rebuildPeerManager()
        partyViewModel.leaveParty()
        partyScreenVisible = true
        hubDestination = HubDestination.HOME
    }

    private fun beginOffersForCurrentViewers() {
        val state = partyViewModel.state.value
        val selfId = state.self?.clientId ?: return
        if (!state.isHost || localVideoTrack == null) return
        state.participants
            .asSequence()
            .filter { it.clientId != selfId }
            .forEach { participant ->
                if (!peerManager.beginHostOffer(participant.clientId)) {
                    mediaError = "A party atingiu o limite de espectadores da transmissão."
                }
            }
    }

    private fun handlePeerSignal(signal: ServerSignal) {
        runOnUiThread {
            runCatching {
                when (signal) {
                    is ServerSignal.Joined -> {
                        if (signal.self.clientId != signal.hostId && signal.shareActive) {
                            shareAudioAvailable = false
                        }
                        if (signal.self.clientId == signal.hostId && signal.shareActive) {
                            if (localVideoTrack != null) {
                                peerManager.setScreenTrack(localVideoTrack)
                                beginOffersForCurrentViewers()
                            } else {
                                // Projection tokens cannot survive a process restart.
                                partyViewModel.sharingStopped()
                            }
                        }
                    }
                    is ServerSignal.ParticipantJoined -> {
                        if (partyViewModel.state.value.isHost &&
                            partyViewModel.state.value.isShareActive &&
                            localVideoTrack != null
                        ) {
                            peerManager.beginHostOffer(signal.participant.clientId, replaceExisting = true)
                        }
                    }
                    is ServerSignal.ParticipantLeft -> {
                        peerManager.removePeer(signal.clientId)
                        iceRestartJobs.remove(signal.clientId)?.cancel()
                        if (signal.clientId == partyViewModel.state.value.hostId) clearRemoteTracks()
                    }
                    is ServerSignal.Offer -> peerManager.handleOffer(signal.fromId, signal.description.toWebRtc())
                    is ServerSignal.Answer -> peerManager.handleAnswer(signal.fromId, signal.description.toWebRtc())
                    is ServerSignal.IceCandidate -> peerManager.handleIceCandidate(signal.fromId, signal.candidate.toWebRtc())
                    is ServerSignal.IceRestart -> peerManager.restartIce(signal.fromId)
                    is ServerSignal.ShareStarted -> {
                        if (partyViewModel.state.value.isHost) {
                            peerManager.setScreenTrack(localVideoTrack)
                            peerManager.setPlaybackAudioEnabled(shareAudioAvailable)
                            beginOffersForCurrentViewers()
                        } else {
                            shareAudioAvailable = false
                        }
                    }
                    is ServerSignal.ShareStopped -> rebuildPeerManager()
                    is ServerSignal.HostChanged -> {
                        if (!partyViewModel.state.value.isHost && localVideoTrack != null) stopLocalShare()
                        rebuildPeerManager()
                    }
                    is ServerSignal.PeerList,
                    is ServerSignal.ChatMessage,
                    is ServerSignal.Pong,
                    is ServerSignal.Error -> Unit
                }
            }.onFailure { error ->
                mediaError = error.message ?: "Falha ao processar a conexão de mídia."
            }
        }
    }

    private fun rebuildPeerManager() {
        clearRemoteTracks()
        if (::peerManager.isInitialized) peerManager.close()
        iceRestartJobs.values.forEach(Job::cancel)
        iceRestartJobs.clear()
        if (isClosing) return
        peerManager = PeerManager(
            webRtcFactory = webRtcFactory,
            iceServers = iceServers,
            signalSink = object : PeerManager.SignalSink {
                override fun sendOffer(targetClientId: String, description: SessionDescription) {
                    partyViewModel.sendSignaling(ClientSignal.Offer(targetClientId, description.toPayload()))
                }

                override fun sendAnswer(targetClientId: String, description: SessionDescription) {
                    partyViewModel.sendSignaling(ClientSignal.Answer(targetClientId, description.toPayload()))
                }

                override fun sendIceCandidate(targetClientId: String, candidate: WebRtcIceCandidate) {
                    partyViewModel.sendSignaling(ClientSignal.IceCandidate(targetClientId, candidate.toPayload()))
                }

                override fun onRemoteVideo(track: VideoTrack) {
                    runOnUiThread {
                        track.setEnabled(true)
                        detachRemoteVideoHealthMonitor()
                        renderedVideoTrack = null
                        remoteVideoTrack = track
                        attachRemoteVideoHealthMonitor(track)
                        mediaError = null
                    }
                }

                override fun onRemoteAudio(track: AudioTrack) {
                    runOnUiThread {
                        remoteAudioTrack = track
                        shareAudioAvailable = true
                        track.setEnabled(!locallyMuted)
                    }
                }

                override fun onConnectionState(clientId: String, state: PeerConnection.IceConnectionState) {
                    handleIceConnectionState(clientId, state)
                }

                override fun onError(clientId: String, message: String) {
                    runOnUiThread { mediaError = "Falha de mídia com $clientId: $message" }
                }
            },
        )
        localVideoTrack?.let {
            peerManager.setScreenTrack(it)
            peerManager.setPlaybackAudioEnabled(shareAudioAvailable)
        }
    }

    private fun handleIceConnectionState(clientId: String, state: PeerConnection.IceConnectionState) {
        runOnUiThread {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    iceRestartJobs.remove(clientId)?.cancel()
                    peerQuality = ConnectionQuality.EXCELLENT
                    mediaError = null
                }
                PeerConnection.IceConnectionState.CHECKING,
                PeerConnection.IceConnectionState.NEW -> peerQuality = ConnectionQuality.GOOD
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.FAILED -> {
                    peerQuality = ConnectionQuality.UNSTABLE
                    if (iceRestartJobs[clientId]?.isActive != true) {
                        iceRestartJobs[clientId] = lifecycleScope.launch {
                            if (state == PeerConnection.IceConnectionState.DISCONNECTED) delay(5_000)
                            if (partyViewModel.state.value.isHost) {
                                peerManager.restartIce(clientId)
                            } else {
                                partyViewModel.sendSignaling(ClientSignal.IceRestart(clientId))
                            }
                        }
                    }
                }
                PeerConnection.IceConnectionState.CLOSED -> {
                    iceRestartJobs.remove(clientId)?.cancel()
                    peerQuality = ConnectionQuality.OFFLINE
                }
            }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (isActive) {
                delay(2_000)
                if (!::peerManager.isInitialized) continue
                peerManager.collectStats { clientId, stats ->
                    Log.i(
                        "UnoraVideoStats",
                        "peer=$clientId fps=${stats.fps} size=${stats.width}x${stats.height} " +
                            "bitrateKbps=${stats.bitrateKbps} loss=${stats.packetLossPercent}",
                    )
                    runOnUiThread {
                        peerQuality = when (stats.quality) {
                            WebRtcConnectionQuality.EXCELLENT -> ConnectionQuality.EXCELLENT
                            WebRtcConnectionQuality.GOOD -> ConnectionQuality.GOOD
                            WebRtcConnectionQuality.UNSTABLE -> ConnectionQuality.UNSTABLE
                            WebRtcConnectionQuality.UNKNOWN -> peerQuality
                        }
                    }
                }
            }
        }
    }

    private fun setRemoteMuted(muted: Boolean) {
        locallyMuted = muted
        remoteAudioTrack?.setEnabled(!muted)
    }

    private fun clearRemoteTracks() {
        detachRemoteVideoHealthMonitor()
        if (renderedVideoTrack === remoteVideoTrack) renderedVideoTrack = null
        remoteVideoTrack = null
        remoteAudioTrack?.setEnabled(false)
        remoteAudioTrack = null
        peerQuality = null
    }

    private fun attachLocalVideoHealthMonitor(track: VideoTrack) {
        detachLocalVideoHealthMonitor()
        val monitor = VideoFrameHealthMonitor { health ->
            runOnUiThread { updateVideoHealth(health, isLocal = true) }
        }
        localVideoHealthMonitor = monitor
        track.addSink(monitor)
    }

    private fun detachLocalVideoHealthMonitor() {
        val monitor = localVideoHealthMonitor ?: return
        runCatching { localVideoTrack?.removeSink(monitor) }
        monitor.close()
        localVideoHealthMonitor = null
    }

    private fun attachRemoteVideoHealthMonitor(track: VideoTrack) {
        detachRemoteVideoHealthMonitor()
        val monitor = VideoFrameHealthMonitor { health ->
            runOnUiThread { updateVideoHealth(health, isLocal = false) }
        }
        remoteVideoHealthMonitor = monitor
        track.addSink(monitor)
    }

    private fun detachRemoteVideoHealthMonitor() {
        val monitor = remoteVideoHealthMonitor ?: return
        runCatching { remoteVideoTrack?.removeSink(monitor) }
        monitor.close()
        remoteVideoHealthMonitor = null
    }

    private fun updateVideoHealth(health: VideoFrameHealth, isLocal: Boolean) {
        when (health) {
            VideoFrameHealth.ACTIVE -> {
                if (videoWarning?.startsWith(VIDEO_WARNING_PREFIX) == true) videoWarning = null
            }
            VideoFrameHealth.BLACK_FRAMES -> {
                videoWarning = if (isLocal) {
                    "$VIDEO_WARNING_PREFIX a captura recebe uma área de vídeo preta. Escolha Tela inteira; se apenas o player continuar preto, o aplicativo de origem bloqueia captura."
                } else {
                    "$VIDEO_WARNING_PREFIX o host está enviando a área do vídeo em preto. Ele deve escolher Tela inteira ou trocar para um app que permita captura."
                }
            }
            VideoFrameHealth.NO_FRAMES -> {
                videoWarning = if (isLocal) {
                    "$VIDEO_WARNING_PREFIX a captura não entregou nenhum quadro. Pare e compartilhe novamente escolhendo Tela inteira."
                } else {
                    "$VIDEO_WARNING_PREFIX o áudio chegou, mas nenhum quadro de vídeo foi recebido. Toque em reconectar."
                }
            }
        }
    }

    private fun applyFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            if (resources.configuration.smallestScreenWidthDp < 600) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun copyText(value: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Convite Unora", value))
    }

    private fun shareInvite(url: String) {
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Entre na minha party no Unora: $url")
        startActivity(Intent.createChooser(sendIntent, "Compartilhar convite"))
    }

    override fun onResume() {
        super.onResume()
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        ChatOverlayService.stop(this)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val partyActive = partyViewModel.state.value.partyId != null &&
            partyViewModel.state.value.phase !in setOf(PartyPhase.Idle, PartyPhase.Ended)
        if (partyActive && overlayPermissionGranted && !projectionFlowActive &&
            pendingNotificationAction == null && !suppressAutoOverlay && !isClosing
        ) {
            ChatOverlayService.start(this)
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            isClosing = true
            applyFullscreen(false)
            partyViewModel.attachPeerSignaling(null)
            statsJob?.cancel()
            statsJob = null
            iceRestartJobs.values.forEach(Job::cancel)
            iceRestartJobs.clear()
            clearRemoteTracks()
            if (::peerManager.isInitialized) peerManager.close()
            MediaProjectionRuntime.unbind(screenCaptureManager)
            screenCaptureManager.close()
            MediaProjectionService.stop(this)
            ChatOverlayService.stop(this)
            ChatOverlayRuntime.clear()
            webRtcFactory.close()
        }
        super.onDestroy()
    }
}

private fun SessionDescription.toPayload() = SessionDescriptionPayload(type.canonicalForm(), description)

private fun SessionDescriptionPayload.toWebRtc() = SessionDescription(
    when (type.lowercase()) {
        "offer" -> SessionDescription.Type.OFFER
        "answer" -> SessionDescription.Type.ANSWER
        "pranswer" -> SessionDescription.Type.PRANSWER
        "rollback" -> SessionDescription.Type.ROLLBACK
        else -> throw IllegalArgumentException("Tipo SDP inválido: $type")
    },
    sdp,
)

private fun WebRtcIceCandidate.toPayload() = IceCandidatePayload(
    candidate = sdp,
    sdpMid = sdpMid,
    sdpMLineIndex = sdpMLineIndex,
)

private fun IceCandidatePayload.toWebRtc() = WebRtcIceCandidate(sdpMid, sdpMLineIndex ?: 0, candidate)

private const val VIDEO_WARNING_PREFIX = "Diagnóstico de vídeo:"

private fun app.unora.model.PartyUiState.toScreenState(
    muted: Boolean,
    peerQuality: ConnectionQuality?,
    shareAudioAvailable: Boolean,
    mediaError: String?,
    hasVideo: Boolean,
): PartyUiState = PartyUiState(
    partyId = partyId.orEmpty(),
    role = if (isHost) PartyRole.HOST else PartyRole.VIEWER,
    streamStatus = streamStatusFor(phase, isShareActive),
    participants = participants.map { ParticipantUi(it.clientId, it.nickname, it.isHost) },
    messages = chatMessages.map { message ->
        ChatMessageUi(
            id = message.id,
            senderId = message.clientId,
            nickname = message.nickname,
            text = message.text,
            timestampLabel = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.sentAt)),
        )
    },
    connectionQuality = peerQuality ?: when {
        phase == PartyPhase.Reconnecting -> ConnectionQuality.UNSTABLE
        partyId != null && phase !in setOf(PartyPhase.Error, PartyPhase.Ended) -> ConnectionQuality.GOOD
        else -> when (connectionQuality) {
        CoreConnectionQuality.Excellent -> ConnectionQuality.EXCELLENT
        CoreConnectionQuality.Good -> ConnectionQuality.GOOD
        CoreConnectionQuality.Unstable -> ConnectionQuality.UNSTABLE
        CoreConnectionQuality.Unknown -> ConnectionQuality.OFFLINE
        }
    },
    errorMessage = mediaError ?: errorMessage,
    isAudioMuted = muted,
    isShareAudioAvailable = shareAudioAvailable,
    hasVideo = hasVideo,
)
