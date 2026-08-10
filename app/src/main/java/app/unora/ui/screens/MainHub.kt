package app.unora.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import app.unora.ui.theme.UnoraShapes

enum class HubDestination { HOME, PROFILE, SETTINGS }

@Composable
fun MainHub(
    selected: HubDestination,
    onSelected: (HubDestination) -> Unit,
    activePartyId: String? = null,
    onReturnToParty: () -> Unit = {},
    onLeaveParty: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
        topBar = {
            if (activePartyId != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = UnoraShapes.card,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onReturnToParty,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.size(22.dp))
                        Text("Party $activePartyId ativa", Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("Voltar", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                        androidx.compose.material3.IconButton(onClick = onLeaveParty, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Outlined.Close, "Sair da party", Modifier.size(19.dp))
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selected == HubDestination.HOME,
                    onClick = { onSelected(HubDestination.HOME) },
                    icon = { Icon(Icons.Outlined.Home, null) },
                    label = { Text("Início") },
                )
                NavigationBarItem(
                    selected = selected == HubDestination.PROFILE,
                    onClick = { onSelected(HubDestination.PROFILE) },
                    icon = { Icon(Icons.Outlined.PersonOutline, null) },
                    label = { Text("Perfil") },
                )
                NavigationBarItem(
                    selected = selected == HubDestination.SETTINGS,
                    onClick = { onSelected(HubDestination.SETTINGS) },
                    icon = { Icon(Icons.Outlined.Settings, null) },
                    label = { Text("Ajustes") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content(Modifier.fillMaxSize()) }
    }
}
