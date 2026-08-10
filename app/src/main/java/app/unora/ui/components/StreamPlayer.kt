package app.unora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.unora.ui.screens.PartyRole
import app.unora.ui.screens.StreamUiStatus
import app.unora.ui.theme.LiveRed
import app.unora.ui.theme.UnoraShapes
import app.unora.ui.theme.Violet
import app.unora.ui.theme.VioletBright

@Composable
fun StreamPlayer(
    role: PartyRole,
    status: StreamUiStatus,
    hasVideo: Boolean,
    muted: Boolean,
    isShareAudioAvailable: Boolean,
    onShare: () -> Unit,
    onStop: () -> Unit,
    onSwitchSource: () -> Unit,
    onRetry: () -> Unit,
    onToggleMute: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    renderSurface: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.clip(UnoraShapes.player).background(Color.Black)) {
        renderSurface()

        if (status != StreamUiStatus.LIVE || !hasVideo) {
            StreamEmptyState(
                role = role,
                status = if (status == StreamUiStatus.LIVE) StreamUiStatus.CONNECTING else status,
                onShare = onShare,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (status == StreamUiStatus.LIVE) {
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = UnoraShapes.pill, color = Color(0xB516171D), contentColor = Color.White) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(LiveRed))
                        Spacer(Modifier.width(6.dp))
                        Text("AO VIVO", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
                if (!isShareAudioAvailable) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = UnoraShapes.pill, color = Color(0xB516171D), contentColor = Color.White) {
                        Text("Sem áudio", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (role == PartyRole.VIEWER && status == StreamUiStatus.LIVE) {
                PlayerIconButton(Icons.Outlined.Refresh, "Reconectar vídeo", onRetry)
                PlayerIconButton(if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, if (muted) "Ativar som" else "Silenciar", onToggleMute)
            }
            if (role == PartyRole.HOST && status == StreamUiStatus.LIVE) {
                PlayerIconButton(Icons.Outlined.SwapHoriz, "Trocar app ou tela", onSwitchSource)
                PlayerIconButton(Icons.Outlined.StopCircle, "Parar transmissão", onStop, destructive = true)
            }
            PlayerIconButton(Icons.Outlined.Fullscreen, "Tela cheia", onFullscreen)
        }
    }
}

@Composable
private fun StreamEmptyState(
    role: PartyRole,
    status: StreamUiStatus,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val title = when (status) {
        StreamUiStatus.WAITING_FOR_SHARE -> if (role == PartyRole.HOST) "Pronto para transmitir" else "Aguardando o host"
        StreamUiStatus.REQUESTING_PROJECTION -> "Abrindo o seletor do Android…"
        StreamUiStatus.CONNECTING -> "Conectando ao vídeo…"
        StreamUiStatus.RECONNECTING -> "Reconectando…"
        StreamUiStatus.ENDED -> "Transmissão encerrada"
        StreamUiStatus.LIVE -> ""
    }
    val subtitle = when {
        status == StreamUiStatus.WAITING_FOR_SHARE && role == PartyRole.HOST -> "Escolha a tela inteira ou um app compatível."
        status == StreamUiStatus.WAITING_FOR_SHARE -> "Você pode continuar conversando enquanto espera."
        status == StreamUiStatus.CONNECTING -> "O áudio e o vídeo serão sincronizados automaticamente."
        else -> ""
    }
    Column(
        modifier = modifier
            .background(Brush.radialGradient(listOf(Color(0xFF20143A), Color.Black)))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (status in setOf(StreamUiStatus.CONNECTING, StreamUiStatus.RECONNECTING, StreamUiStatus.REQUESTING_PROJECTION)) {
            CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp, color = VioletBright)
        } else {
            Surface(shape = CircleShape, color = Color(0xFF2B2040), modifier = Modifier.size(54.dp)) {
                Icon(Icons.Outlined.ScreenShare, null, Modifier.padding(14.dp), tint = Violet)
            }
        }
        Spacer(Modifier.height(15.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = Color(0xFFAAA5B5), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        if (role == PartyRole.HOST && status in setOf(StreamUiStatus.WAITING_FOR_SHARE, StreamUiStatus.ENDED)) {
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onShare,
                shape = UnoraShapes.pill,
                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White),
            ) {
                Icon(Icons.Outlined.ScreenShare, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Compartilhar tela", fontWeight = FontWeight.Bold)
            }
        } else if (role == PartyRole.VIEWER && status in setOf(StreamUiStatus.CONNECTING, StreamUiStatus.RECONNECTING)) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                shape = UnoraShapes.pill,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2638), contentColor = Color.White),
            ) { Text("Reconectar vídeo", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun PlayerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Surface(
        shape = CircleShape,
        color = if (destructive) LiveRed.copy(alpha = 0.9f) else Color(0xC71B1B22),
        contentColor = Color.White,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, description, Modifier.size(21.dp))
        }
    }
}
