package app.unora.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0D0F15)
val Surface = Color(0xFF151821)
val SurfaceElevated = Color(0xFF1B1F2A)
val SurfaceMuted = Color(0xFF242936)
val TextPrimary = Color(0xFFF7F5FC)
val TextSecondary = Color(0xFFA9AFBD)
val Violet = Color(0xFF9A6CFF)
val VioletBright = Color(0xFFB354FF)
val VioletSoft = Color(0xFFE0D6FF)
val LiveRed = Color(0xFFFF6075)
val Success = Color(0xFF6ED6A7)

private val DarkScheme = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A1C49),
    onPrimaryContainer = VioletSoft,
    secondary = VioletBright,
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF3A3F4D),
    outlineVariant = Color(0xFF272B35),
    error = LiveRed,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6D35D5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE3FF),
    onPrimaryContainer = Color(0xFF321170),
    secondary = Color(0xFF8A3BC3),
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF17151C),
    surface = Color.White,
    onSurface = Color(0xFF17151C),
    surfaceVariant = Color(0xFFF0EEF5),
    onSurfaceVariant = Color(0xFF62606A),
    outline = Color(0xFFD3CFDA),
    outlineVariant = Color(0xFFE8E4ED),
    error = Color(0xFFBA1A35),
)

private val UnoraTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
)

object UnoraShapes {
    val player = RoundedCornerShape(24.dp)
    val card = RoundedCornerShape(18.dp)
    val small = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(100.dp)
}

@Composable
fun UnoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = UnoraTypography,
        shapes = MaterialTheme.shapes.copy(
            extraSmall = RoundedCornerShape(8.dp),
            small = UnoraShapes.small,
            medium = UnoraShapes.card,
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
