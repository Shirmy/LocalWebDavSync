package com.example.localwebdavsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = PaperWhite,
    onPrimary = InkBlack,
    secondary = PaperWhite,
    onSecondary = InkBlack,
    background = InkBlack,
    onBackground = PaperWhite,
    surface = InkBlack,
    onSurface = PaperWhite,
    outline = PaperWhite
)

private val LightColorScheme = lightColorScheme(
    primary = PaperWhite,
    onPrimary = InkBlack,
    secondary = PaperWhite,
    onSecondary = InkBlack,
    background = PaperWhite,
    onBackground = InkBlack,
    surface = PaperWhite,
    onSurface = InkBlack,
    surfaceVariant = PaperGrey,
    onSurfaceVariant = InkBlack,
    outline = InkBlack
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
)

@Composable
fun LocalWebDavSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
