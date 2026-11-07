package app.kinjar.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Tokens from docs/design/tokens.md. Keep both files in sync. */
@Immutable
data class KinjarColors(
    val bg: Color,
    val text: Color,
    val muted: Color,
    val surface2: Color,
    val border: Color,
    val track: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val tintAlpha: Float,
    val dark: Boolean,
)

val LightColors = KinjarColors(
    bg = Color(0xFFFFFFFF),
    text = Color(0xFF0E1014),
    muted = Color(0xFF5B616B),
    surface2 = Color(0xFFF3F4F6),
    border = Color(0xFFE3E5E9),
    track = Color(0xFFE5E7EB),
    primary = Color(0xFF3D4BD6),
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0xFFECEEFE),
    green = Color(0xFF1B7A57),
    orange = Color(0xFFA8520E),
    red = Color(0xFFB3261E),
    tintAlpha = 0.12f,
    dark = false,
)

val DarkColors = KinjarColors(
    bg = Color(0xFF000000),
    text = Color(0xFFF3F4F6),
    muted = Color(0xFF9AA0AA),
    surface2 = Color(0xFF0E0F12),
    border = Color(0xFF1F2227),
    track = Color(0xFF1C1E23),
    primary = Color(0xFF8C96FF),
    onPrimary = Color(0xFF05060F),
    primarySoft = Color(0x2E8C96FF),
    green = Color(0xFF4CC79A),
    orange = Color(0xFFF0A05A),
    red = Color(0xFFFF8A80),
    tintAlpha = 0.18f,
    dark = true,
)

val LocalColors = staticCompositionLocalOf { LightColors }

fun colorOf(hex: String): Color = Color(hex.removePrefix("#").toLong(16) or 0xFF000000)
