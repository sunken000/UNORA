package app.unora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.unora.ui.screens.ConnectionQuality
import app.unora.ui.theme.LiveRed
import app.unora.ui.theme.Success

@Composable
fun ConnectionIndicator(quality: ConnectionQuality, modifier: Modifier = Modifier) {
    val color = when (quality) {
        ConnectionQuality.EXCELLENT -> Success
        ConnectionQuality.GOOD -> Color(0xFFB0A3FF)
        ConnectionQuality.UNSTABLE -> Color(0xFFFFC76A)
        ConnectionQuality.OFFLINE -> LiveRed
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(quality.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
