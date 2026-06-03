package io.github.liki4.peek.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette — sampled from the launcher icon's deep navy + heart-rate red.
// Dynamic color (Material You) is intentionally disabled so the brand reads
// the same on every device, and so the HR red accent doesn't get rewritten
// to whatever pastel the user's wallpaper produces.

private val BrandBlue         = Color(0xFF3B5BDB)
private val BrandTeal         = Color(0xFF38B2AC)
private val BrandHrRed        = Color(0xFFE53935)
private val BrandSurfaceLight = Color(0xFFF8FAFC)
private val BrandSurfaceDark  = Color(0xFF0F1721)

private val LightColors = lightColorScheme(
    primary            = BrandBlue,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFDBE4FF),
    onPrimaryContainer = Color(0xFF112763),
    secondary          = BrandTeal,
    onSecondary        = Color.White,
    tertiary           = Color(0xFFFF8A24),
    onTertiary         = Color.White,
    error              = BrandHrRed,
    onError            = Color.White,
    background         = BrandSurfaceLight,
    onBackground       = Color(0xFF0F1721),
    surface            = Color.White,
    onSurface          = Color(0xFF0F1721),
    surfaceVariant     = Color(0xFFE7ECF3),
    onSurfaceVariant   = Color(0xFF4A5568),
    outline            = Color(0xFFA0AEC0),
    outlineVariant     = Color(0xFFE2E8F0),
)

private val DarkColors = darkColorScheme(
    primary            = Color(0xFF8AA8FF),
    onPrimary          = Color(0xFF0B1F4D),
    primaryContainer   = Color(0xFF253C82),
    onPrimaryContainer = Color(0xFFDBE4FF),
    secondary          = Color(0xFF4FD1C5),
    onSecondary        = Color(0xFF003733),
    tertiary           = Color(0xFFFFB166),
    onTertiary         = Color(0xFF4A2300),
    error              = BrandHrRed,
    onError            = Color.White,
    background         = BrandSurfaceDark,
    onBackground       = Color(0xFFE2E8F0),
    surface            = Color(0xFF1A2332),
    onSurface          = Color(0xFFE2E8F0),
    surfaceVariant     = Color(0xFF243042),
    onSurfaceVariant   = Color(0xFFA0AEC0),
    outline            = Color(0xFF4A5568),
    outlineVariant     = Color(0xFF2D3748),
)

@Composable
fun PeekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
