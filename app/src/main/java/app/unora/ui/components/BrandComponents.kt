package app.unora.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.unora.R
import app.unora.ui.theme.Violet
import app.unora.ui.theme.VioletBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun UnoraMark(size: Dp = 52.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.unora_logo),
        contentDescription = "Unora",
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.28f)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
fun ProfileAvatar(
    nickname: String,
    avatarPath: String?,
    size: Dp = 46.dp,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(avatarPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatarPath) {
        bitmap = withContext(Dispatchers.IO) {
            avatarPath?.let(BitmapFactory::decodeFile)?.asImageBitmap()
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = "Foto de perfil",
            modifier = modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Violet, VioletBright))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                nickname.firstOrNull()?.uppercase() ?: "U",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
