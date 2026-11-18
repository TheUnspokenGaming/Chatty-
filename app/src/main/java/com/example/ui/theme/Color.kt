package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Obsidian Dark Palette — Architectural Swiss Cryptographic Minimalism
val ObsidianCanvas = Color(0xFF0B0E13)
val ObsidianSurface = Color(0xFF131820)
val ObsidianSurfaceElevated = Color(0xFF1B222D)
val ObsidianSurfaceHighest = Color(0xFF232C3A)
val ObsidianOutline = Color(0xFF2A3444)
val ObsidianOutlineSubtle = Color(0xFF1F2733)

val VaultEmerald = Color(0xFF14D890)
val VaultEmeraldDark = Color(0xFF043825)
val VaultEmeraldContainer = Color(0xFF0B2E22)
val VaultEmeraldOnContainer = Color(0xFF7CF5C4)

val EphemeralAmber = Color(0xFFF0AD4E)
val EphemeralAmberContainer = Color(0xFF332612)
val EphemeralAmberOnContainer = Color(0xFFFFD89E)

val CipherCyan = Color(0xFF4CC9F0)
val CipherCyanContainer = Color(0xFF102A36)

val ShredCrimson = Color(0xFFFF5C72)
val ShredCrimsonContainer = Color(0xFF3B151D)
val ShredCrimsonOnContainer = Color(0xFFFFB4BE)

val InkHigh = Color(0xFFF1F5F9)
val InkMedium = Color(0xFF9AA7B8)

// Alabaster Light Palette — Crisp Architectural Paper & Deep Vault Emerald
val AlabasterCanvas = Color(0xFFF3F5F8)
val AlabasterSurface = Color(0xFFFFFFFF)
val AlabasterSurfaceElevated = Color(0xFFEAEFF5)
val AlabasterOutline = Color(0xFFD5DCE6)

val DeepEmeraldLight = Color(0xFF087F56)
val DeepEmeraldContainerLight = Color(0xFFD6F5E8)
val DeepEmeraldOnContainerLight = Color(0xFF043825)

val WarmAmberLight = Color(0xFFB76E00)
val WarmAmberContainerLight = Color(0xFFFFEED2)
val WarmAmberOnContainerLight = Color(0xFF3F2400)

val LightCrimson = Color(0xFFC92A42)
val LightCrimsonContainer = Color(0xFFFFE2E6)

val InkDarkHigh = Color(0xFF0F1722)
val InkDarkMedium = Color(0xFF526075)

// Room Card Accent Tags
object RoomAccents {
    val Emerald = Color(0xFF14D890)
    val Cyan = Color(0xFF38BDF8)
    val Amber = Color(0xFFF59E0B)
    val Slate = Color(0xFF94A3B8)
    val Violet = Color(0xFFA78BFA)

    fun colorForTag(tag: String): Color = when (tag.lowercase()) {
        "cyan" -> Cyan
        "amber" -> Amber
        "slate" -> Slate
        "violet" -> Violet
        else -> Emerald
    }
}
