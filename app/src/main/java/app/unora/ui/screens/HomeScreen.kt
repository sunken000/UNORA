package app.unora.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.unora.navigation.partyIdFromInvite
import app.unora.ui.components.ProfileAvatar
import app.unora.ui.components.UnoraMark
import app.unora.ui.theme.UnoraShapes
import app.unora.ui.theme.Violet
import app.unora.ui.theme.VioletBright

@Composable
fun HomeScreen(
    isWorking: Boolean,
    error: String?,
    nickname: String,
    avatarPath: String?,
    onCreateParty: () -> Unit,
    onJoinParty: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var invite by remember { mutableStateOf("") }
    var showJoin by remember { mutableStateOf(false) }
    val partyId = partyIdFromInvite(invite)

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnoraMark(44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("UNORA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("Watch together.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Configurações")
            }
            ProfileAvatar(
                nickname = nickname,
                avatarPath = avatarPath,
                size = 42.dp,
                modifier = Modifier.clickable(onClick = onOpenProfile),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ),
                    UnoraShapes.player,
                )
                .padding(24.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(24.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text("Sua próxima sessão começa aqui.", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Crie uma sala privada, compartilhe sua tela e assista com quem você quiser.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onCreateParty,
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = UnoraShapes.card,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ),
            ) {
                if (isWorking && !showJoin) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                } else Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isWorking && !showJoin) "Criando…" else "Criar uma party", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { showJoin = !showJoin },
            enabled = !isWorking,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = UnoraShapes.card,
        ) {
            Icon(Icons.Outlined.Link, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Entrar com código ou link", fontWeight = FontWeight.SemiBold)
        }

        AnimatedVisibility(showJoin) {
            Column(
                Modifier.fillMaxWidth().padding(top = 14.dp).background(
                    MaterialTheme.colorScheme.surface,
                    UnoraShapes.card,
                ).padding(16.dp),
            ) {
                Text("Entrar em uma party", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = invite,
                    onValueChange = { invite = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = UnoraShapes.small,
                    placeholder = { Text("Código ou link do convite") },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { partyId?.let(onJoinParty) },
                    enabled = partyId != null && !isWorking,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = UnoraShapes.small,
                ) {
                    Text(if (isWorking) "Entrando…" else "Entrar")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }

        AnimatedVisibility(error != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = UnoraShapes.small,
            ) {
                Text(
                    text = error.orEmpty(),
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}
