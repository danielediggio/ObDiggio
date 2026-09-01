package org.diggio.obdiggio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeonGreen = Color(0xFF39FF00)
val NeonCyan  = Color(0xFF00BFFF)
val NeonPink  = Color(0xFFFF69BF)
val PanelBlack = Color(0xFF04120D)
val PanelDark  = Color(0xFF080E1A)
val Steel      = Color(0xFFB0BEC5)

private val ObdiggioDarkColors = darkColorScheme(
    primary         = NeonGreen,
    onPrimary       = Color.Black,
    secondary       = NeonCyan,
    onSecondary     = Color.Black,
    tertiary        = NeonPink,
    background      = PanelBlack,
    surface         = PanelDark,
    onBackground    = NeonGreen,
    onSurface       = NeonGreen
)

@Composable
fun ObdiggioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ObdiggioDarkColors,
        content = content
    )
}
