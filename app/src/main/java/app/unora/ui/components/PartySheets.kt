@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.unora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.unora.ui.screens.ParticipantUi
import app.unora.ui.theme.SurfaceElevated
import app.unora.ui.theme.UnoraShapes

@Composable
fun ParticipantsSheet(participants: List<ParticipantUi>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp)) {
            Text("Assistindo agora", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            participants.forEach { participant ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PresenceAvatar(participant.nickname)
                    Spacer(Modifier.width(12.dp))
                    Text(participant.nickname, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (participant.isHost) Text("Host", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun InviteSheet(
    partyId: String,
    onCopyCode: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp)) {
            Text("Convide para a party", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Código", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(partyId, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, letterSpacing = MaterialTheme.typography.displaySmall.letterSpacing)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onCopyCode, modifier = Modifier.fillMaxWidth(), shape = UnoraShapes.card) { Text("Copiar código") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onCopyLink, modifier = Modifier.fillMaxWidth(), shape = UnoraShapes.card) { Text("Copiar link") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth(), shape = UnoraShapes.card) { Text("Compartilhar") }
        }
    }
}

@Composable
private fun PresenceAvatar(nickname: String) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(SurfaceElevated), contentAlignment = Alignment.Center) {
        Text(nickname.firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Bold)
    }
}
