package fr.proximo.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Px {
    val Bg = Color(0xFF0B1220)
    val Bar = Color(0xFF0F1A2E)
    val Card = Color(0xFF132038)
    val Card2 = Color(0xFF1E293B)
    val Accent = Color(0xFF22D3EE)
    val AccentDark = Color(0xFF0E7490)
    val AccentSoft = Color(0xFFA7F3FC)
    val Muted = Color(0xFF94A3B8)
    val Warn = Color(0xFFFDE68A)
    val Danger = Color(0xFFF87171)
    val Ok = Color(0xFF34D399)
    val Avatar = Color(0xFF1E3A5F)
}

private val scheme = darkColorScheme(
    primary = Px.Accent,
    onPrimary = Px.Bg,
    secondary = Px.AccentDark,
    background = Px.Bg,
    surface = Px.Bg,
    surfaceVariant = Px.Card,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Px.Muted,
    error = Px.Danger,
    outline = Px.Card2,
)

@Composable
fun ProximoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)),
        content = content,
    )
}
