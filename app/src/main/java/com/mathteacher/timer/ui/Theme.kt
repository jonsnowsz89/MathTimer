package com.mathteacher.timer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Pure-black OLED palette: black pixels are off on AMOLED panels (battery + eye comfort). */
private val Oled = darkColorScheme(
    background = Color.Black, surface = Color(0xFF080808), surfaceVariant = Color(0xFF151515),
    primary = Color(0xFF4DD0E1), onPrimary = Color.Black,
    secondary = Color(0xFFFFB74D), onSecondary = Color.Black,
    onBackground = Color(0xFFEDEDED), onSurface = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF9E9E9E), error = Color(0xFFFF6B6B)
)

@Composable
fun MathTimerTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Oled, content = content)
