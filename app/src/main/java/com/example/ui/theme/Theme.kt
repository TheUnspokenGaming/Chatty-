package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NexusDarkColorScheme = darkColorScheme(
    primary = VaultEmerald,
    onPrimary = VaultEmeraldDark,
    primaryContainer = VaultEmeraldContainer,
    onPrimaryContainer = VaultEmeraldOnContainer,
    secondary = EphemeralAmber,
    onSecondary = Color(0xFF281A00),
    secondaryContainer = EphemeralAmberContainer,
    onSecondaryContainer = EphemeralAmberOnContainer,
    tertiary = CipherCyan,
    onTertiary = Color(0xFF002533),
    tertiaryContainer = CipherCyanContainer,
    onTertiaryContainer = Color(0xFFB5ECFF),
    error = ShredCrimson,
    onError = Color(0xFF3A000A),
    errorContainer = ShredCrimsonContainer,
    onErrorContainer = ShredCrimsonOnContainer,
    background = ObsidianCanvas,
    onBackground = InkHigh,
    surface = ObsidianSurface,
    onSurface = InkHigh,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = InkMedium,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurface,
    surfaceContainerHigh = ObsidianSurfaceElevated,
    surfaceContainerHighest = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineSubtle
)

private val NexusLightColorScheme = lightColorScheme(
    primary = DeepEmeraldLight,
    onPrimary = Color.White,
    primaryContainer = DeepEmeraldContainerLight,
    onPrimaryContainer = DeepEmeraldOnContainerLight,
    secondary = WarmAmberLight,
    onSecondary = Color.White,
    secondaryContainer = WarmAmberContainerLight,
    onSecondaryContainer = WarmAmberOnContainerLight,
    tertiary = Color(0xFF0277BD),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6F1FF),
    onTertiaryContainer = Color(0xFF002438),
    error = LightCrimson,
    onError = Color.White,
    errorContainer = LightCrimsonContainer,
    onErrorContainer = Color(0xFF4A0010),
    background = AlabasterCanvas,
    onBackground = InkDarkHigh,
    surface = AlabasterSurface,
    onSurface = InkDarkHigh,
    surfaceVariant = AlabasterSurfaceElevated,
    onSurfaceVariant = InkDarkMedium,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = AlabasterSurface,
    surfaceContainer = AlabasterSurface,
    surfaceContainerHigh = AlabasterSurfaceElevated,
    surfaceContainerHighest = Color(0xFFE1E8F0),
    outline = AlabasterOutline,
    outlineVariant = Color(0xFFE4E9F0)
)

val NexusShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

enum class NexusThemeMode {
    OBSIDIAN,
    ALABASTER,
    SYSTEM
}

@Composable
fun NexusTheme(
    themeMode: NexusThemeMode = NexusThemeMode.OBSIDIAN,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        NexusThemeMode.OBSIDIAN -> true
        NexusThemeMode.ALABASTER -> false
        NexusThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (useDark) NexusDarkColorScheme else NexusLightColorScheme,
        typography = NexusTypography,
        shapes = NexusShapes,
        content = content
    )
}
