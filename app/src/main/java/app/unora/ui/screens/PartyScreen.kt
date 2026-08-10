package app.unora.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.unora.ui.components.ConnectionIndicator
import app.unora.ui.components.InviteSheet
import app.unora.ui.components.ParticipantsSheet
import app.unora.ui.components.PartyChat
import app.unora.ui.components.PartyHeader
import app.unora.ui.components.StreamPlayer
import app.unora.ui.theme.UnoraShapes

@Composable
fun PartyScreen(
    state: PartyUiState,
    actions: PartyUiActions,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onBackToHub: () -> Unit,
    onMinimize: () -> Unit,
    onCopyText: (String) -> Unit,
    onShareInvite: (String) -> Unit,
    modifier: Modifier = Modifier,
    renderSurface: @Composable () -> Unit = {},
) {
    var showPeople by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var showShareExplainer by remember { mutableStateOf(false) }
    var showStopConfirmation by remember { mutableStateOf(false) }
    var showLeaveConfirmation by remember { mutableStateOf(false) }
    val inviteUrl = "https://unora.app/p/${state.partyId}"

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(if (isFullscreen) WindowInsets.displayCutout else WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            val horizontal = isFullscreen || maxWidth >= 840.dp
            if (horizontal) {
                FullPartyLayout(
                    state = state,
                    actions = actions,
                    onBack = onBackToHub,
                    onPeople = { showPeople = true },
                    onInvite = { showInvite = true },
                    onMinimize = onMinimize,
                    onLeave = { showLeaveConfirmation = true },
                    onShare = { showShareExplainer = true },
                    onStop = { showStopConfirmation = true },
                    onFullscreen = { onFullscreenChange(!isFullscreen) },
                    renderSurface = renderSurface,
                )
            } else {
                PortraitPartyLayout(
                    state = state,
                    actions = actions,
                    onBack = onBackToHub,
                    onPeople = { showPeople = true },
                    onInvite = { showInvite = true },
                    onMinimize = onMinimize,
                    onLeave = { showLeaveConfirmation = true },
                    onShare = { showShareExplainer = true },
                    onStop = { showStopConfirmation = true },
                    onFullscreen = { onFullscreenChange(true) },
                    renderSurface = renderSurface,
                )
            }
        }
    }

    if (showPeople) ParticipantsSheet(state.participants, onDismiss = { showPeople = false })
    if (showInvite) InviteSheet(
        partyId = state.partyId,
        onCopyCode = { onCopyText(state.partyId) },
        onCopyLink = { onCopyText(inviteUrl) },
        onShare = { onShareInvite(inviteUrl) },
        onDismiss = { showInvite = false },
    )
    if (showShareExplainer) {
        AlertDialog(
            onDismissRequest = { showShareExplainer = false },
            title = { Text("Compartilhar tela ou aplicativo") },
            text = {
                Text("Na próxima tela, o próprio Android permite escolher a tela inteira ou um aplicativo compatível. O Unora captura somente o áudio interno permitido e nunca usa seu microfone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showShareExplainer = false
                    actions.requestScreenShare()
                }) { Text("Abrir seletor") }
            },
            dismissButton = { TextButton(onClick = { showShareExplainer = false }) { Text("Cancelar") } },
        )
    }
    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text("Parar transmissão?") },
            text = { Text("A party e o chat continuarão ativos.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirmation = false
                    actions.stopScreenShare()
                }) { Text("Parar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showStopConfirmation = false }) { Text("Cancelar") } },
        )
    }
    if (showLeaveConfirmation) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirmation = false },
            title = { Text("Sair da party?") },
            text = { Text(if (state.isHost) "Sua transmissão será encerrada e um novo host poderá ser escolhido." else "Você poderá entrar novamente usando o convite.") },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirmation = false
                    actions.leaveParty()
                }) { Text("Sair", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showLeaveConfirmation = false }) { Text("Ficar") } },
        )
    }
}

@Composable
private fun PortraitPartyLayout(
    state: PartyUiState,
    actions: PartyUiActions,
    onBack: () -> Unit,
    onPeople: () -> Unit,
    onInvite: () -> Unit,
    onMinimize: () -> Unit,
    onLeave: () -> Unit,
    onShare: () -> Unit,
    onStop: () -> Unit,
    onFullscreen: () -> Unit,
    renderSurface: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PartyHeader(
            partyId = state.partyId,
            watchingCount = state.watchingCount,
            onParticipantsClick = onPeople,
            onBackClick = onBack,
            onInviteClick = onInvite,
            onMinimizeClick = onMinimize,
            onLeaveClick = onLeave,
        )
        StreamPlayer(
            role = state.role,
            status = state.streamStatus,
            hasVideo = state.hasVideo,
            muted = state.isAudioMuted,
            isShareAudioAvailable = state.isShareAudioAvailable,
            onShare = onShare,
            onStop = onStop,
            onSwitchSource = actions::switchScreenShare,
            onRetry = actions::retry,
            onToggleMute = { actions.setLocalMuted(!state.isAudioMuted) },
            onFullscreen = onFullscreen,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(horizontal = 12.dp),
            renderSurface = renderSurface,
        )
        ConnectionIndicator(state.connectionQuality, Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        state.errorMessage?.let { InlineError(it) }
        PartyChat(messages = state.messages, onSend = actions::sendMessage, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun FullPartyLayout(
    state: PartyUiState,
    actions: PartyUiActions,
    onBack: () -> Unit,
    onPeople: () -> Unit,
    onInvite: () -> Unit,
    onMinimize: () -> Unit,
    onLeave: () -> Unit,
    onShare: () -> Unit,
    onStop: () -> Unit,
    onFullscreen: () -> Unit,
    renderSurface: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.72f).fillMaxSize()) {
            PartyHeader(
                partyId = state.partyId,
                watchingCount = state.watchingCount,
                onParticipantsClick = onPeople,
                onBackClick = onBack,
                onInviteClick = onInvite,
                onMinimizeClick = onMinimize,
                onLeaveClick = onLeave,
                compact = true,
            )
            StreamPlayer(
                role = state.role,
                status = state.streamStatus,
                hasVideo = state.hasVideo,
                muted = state.isAudioMuted,
                isShareAudioAvailable = state.isShareAudioAvailable,
                onShare = onShare,
                onStop = onStop,
                onSwitchSource = actions::switchScreenShare,
                onRetry = actions::retry,
                onToggleMute = { actions.setLocalMuted(!state.isAudioMuted) },
                onFullscreen = onFullscreen,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 10.dp, bottom = 8.dp),
                renderSurface = renderSurface,
            )
            ConnectionIndicator(state.connectionQuality, Modifier.padding(start = 18.dp, bottom = 8.dp))
            state.errorMessage?.let { InlineError(it) }
        }
        PartyChat(
            messages = state.messages,
            onSend = actions::sendMessage,
            modifier = Modifier.weight(0.28f).fillMaxSize().widthIn(min = 280.dp),
        )
    }
}

@Composable
private fun InlineError(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = UnoraShapes.small,
    ) {
        Text(message, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
