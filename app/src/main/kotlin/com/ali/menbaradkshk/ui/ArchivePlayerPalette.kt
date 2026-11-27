package com.ali.menbaradkshk.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** The same archive design, with paper/bronze in daylight and its original ink/gold at night. */
internal class ArchivePlayerPalette(
    val colors: ColorScheme,
    val offline: Color,
    val offlineText: Color,
    val playFill: Color,
    val playGlyph: Color,
    val downloadBackground: Color,
    val favorite: Color,
    // The Activity intentionally uses light status icons with Minbar's dark app-bar strip.
    val statusStrip: Color? = null,
) {
    val surface get() = colors.surface
    val panel get() = colors.surfaceVariant
    val button get() = colors.surfaceContainerHigh
    val text get() = colors.onSurface
    val muted get() = colors.onSurfaceVariant
    val accent get() = colors.primary
    val track get() = colors.outline
}

// «محراب الفجر»: ليلٌ زمرّديّ بنعناعيّ ونحاسيّ، ونهارٌ من حجرٍ فاتح بزمرّدٍ ونحاس.
private val NightInk = Color(0xFF0B1A16)
private val NightPanel = Color(0xFF12261F)
private val NightButton = Color(0xFF1A372E)
private val NightMint = Color(0xFF7FD1B4)
private val NightCopper = Color(0xFFE3A267)
private val NightPale = Color(0xFFEAF3EE)
private val NightMuted = Color(0xFF9DB5AA)
private val NightTrack = Color(0xFF678A7D)

private val NightPlayer = ArchivePlayerPalette(
    colors = darkColorScheme(
        background = NightInk, onBackground = NightPale,
        surface = NightInk, onSurface = NightPale,
        surfaceVariant = NightPanel, onSurfaceVariant = NightMuted,
        surfaceContainer = NightPanel, surfaceContainerHigh = NightButton,
        outline = NightTrack, outlineVariant = NightButton,
        primary = NightMint, onPrimary = NightInk,
        secondary = NightCopper, onSecondary = NightInk,
        secondaryContainer = NightButton, onSecondaryContainer = NightPale,
        tertiary = NightCopper, onTertiary = NightInk,
    ),
    offline = Color(0xFF1C4A3C), offlineText = Color(0xFFBFE9D8),
    playFill = NightMint, playGlyph = NightInk, downloadBackground = NightButton,
    favorite = Color(0xFFFF8A80),
)

private val DayPanel = Color(0xFFEFE6D6)
private val DayButton = Color(0xFFE8F2EC)
private val DayEmerald = Color(0xFF0F5A46)
private val DayCopper = Color(0xFFB5652B)
private val DayMuted = Color(0xFF55625B)
private val DayTrack = Color(0xFF8F8574)

private val DayPlayer = ArchivePlayerPalette(
    colors = lightColorScheme(
        background = ArchivePaper, onBackground = ArchiveLibraryInk,
        surface = ArchiveIvory, onSurface = ArchiveLibraryInk,
        surfaceVariant = DayPanel, onSurfaceVariant = DayMuted,
        surfaceContainer = ArchivePaper, surfaceContainerHigh = DayButton,
        outline = DayTrack, outlineVariant = Color(0xFFE2D7C3),
        primary = DayEmerald, onPrimary = Color.White,
        primaryContainer = DawnMint, onPrimaryContainer = Color(0xFF0F3B2F),
        secondary = Gold800, onSecondary = Color.White,
        secondaryContainer = DawnSand, onSecondaryContainer = ArchiveLibraryInk,
        tertiary = DayCopper, onTertiary = Color.White,
        error = Color(0xFFA62A1E), onError = Color.White,
        errorContainer = Color(0xFFFBDCD7), onErrorContainer = Color(0xFF5B1109),
    ),
    offline = DawnMint, offlineText = Color(0xFF0F3B2F),
    playFill = DayEmerald, playGlyph = Color.White, downloadBackground = DayButton,
    favorite = Color(0xFFA62A1E),
)

/** Use the outer Material theme after app light/dark/system selection has resolved. */
internal fun archivePlayerPalette(outerBackground: Color): ArchivePlayerPalette =
    if (outerBackground.luminance() < .5f) NightPlayer else DayPlayer
