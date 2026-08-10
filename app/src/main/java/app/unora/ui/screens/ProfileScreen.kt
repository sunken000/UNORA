package app.unora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.unora.ui.components.ProfileAvatar
import app.unora.ui.theme.UnoraShapes

@Composable
fun ProfileScreen(
    nickname: String,
    avatarPath: String?,
    onSaveNickname: (String) -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(nickname) { mutableStateOf(nickname) }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
    ) {
        Text("Seu perfil", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "É assim que você aparece para as pessoas da party.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(28.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = UnoraShapes.player,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ProfileAvatar(nickname = nickname, avatarPath = avatarPath, size = 112.dp)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.Center) {
                    Button(onClick = onChoosePhoto, shape = UnoraShapes.pill) {
                        Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (avatarPath == null) "Adicionar foto" else "Trocar foto")
                    }
                    if (avatarPath != null) {
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = onRemovePhoto, shape = UnoraShapes.pill) {
                            Icon(Icons.Outlined.DeleteOutline, "Remover foto", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("Nome de exibição", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(32) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = UnoraShapes.card,
            supportingText = { Text("Entre 1 e 32 caracteres") },
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { onSaveNickname(draft) },
            enabled = draft.trim().isNotEmpty() && draft.trim() != nickname,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = UnoraShapes.card,
        ) { Text("Salvar alterações", fontWeight = FontWeight.Bold) }
    }
}
