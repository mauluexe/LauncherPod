package dev.maulu.launcherpod

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LifePodColors = darkColorScheme(
    primary = Color(0xFFFF2D20),
    onPrimary = Color.White,
    background = Color(0xFF17171A),
    onBackground = Color(0xFFF7F7F7),
    surface = Color(0xFF050506),
    onSurface = Color(0xFFF7F7F7),
    secondary = Color(0xFFFF5A3D)
)

@Composable
fun LifePodTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LifePodColors,
        content = content
    )
}
