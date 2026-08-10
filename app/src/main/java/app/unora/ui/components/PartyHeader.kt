package app.unora.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.unora.ui.theme.UnoraShapes

@Composable
fun PartyHeader(
    partyId: String,
    watchingCount: Int,
    onParticipantsClick: () -> Unit,
    onBackClick: () -> Unit,
    onInviteClick: () -> Unit,
    onMinimizeClick: () -> Unit,
    onLeaveClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar ao menu")
        }
        Column(Modifier.weight(1f)) {
            Text(
                "Party $partyId",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.clickable(onClick = onParticipantsClick).padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Groups, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(5.dp))
                Text(
                    "$watchingCount assistindo",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Surface(
            onClick = onInviteClick,
            shape = UnoraShapes.pill,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.IosShare, null, Modifier.size(17.dp))
                if (!compact) {
                    Spacer(Modifier.width(6.dp))
                    Text("Convidar", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        IconButton(onClick = onMinimizeClick) {
            Icon(Icons.Outlined.PictureInPictureAlt, "Minimizar com chat flutuante")
        }
        IconButton(onClick = onLeaveClick) {
            Icon(Icons.Outlined.Close, "Sair da party", tint = MaterialTheme.colorScheme.error)
        }
    }
}
