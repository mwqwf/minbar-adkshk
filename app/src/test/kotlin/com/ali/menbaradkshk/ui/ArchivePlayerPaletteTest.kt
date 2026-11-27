package com.ali.menbaradkshk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePlayerPaletteTest {
    @Test fun daylightIsPaperWithLegibleTextAndInteractiveGlyphs() {
        val day = archivePlayerPalette(LightScaffold)
        assertTrue(day.surface.luminance() > .80f)
        assertTrue(day.panel.luminance() > .65f)
        assertTrue(day.button.luminance() > .65f)
        assertTrue(day.colors.background.luminance() > .80f)
        // «محراب الفجر»: الشريط العلوي فاتح نهاراً، فلا شريط حالة داكن فوق المشغّل.
        assertNull(day.statusStrip)
        assertTrue(AppBarBackgroundLight.luminance() > .80f)
        assertContrast(appBarForeground(dark = false), AppBarBackgroundLight, 4.5f)

        for (text in listOf(day.text, day.muted, day.accent)) {
            assertContrast(text, day.surface, 4.5f)
        }
        assertContrast(day.text, day.button, 4.5f)
        assertContrast(day.colors.onSecondaryContainer, day.colors.secondaryContainer, 4.5f)
        assertContrast(day.offlineText, day.offline, 4.5f)
        assertContrast(day.colors.onErrorContainer, day.colors.errorContainer, 4.5f)
        assertContrast(day.playGlyph, day.playFill, 4.5f)
        assertContrast(day.accent, day.panel, 3f)
        assertContrast(day.accent, day.button, 3f) // replay icon
        assertContrast(day.track, day.surface, 3f)
        assertContrast(day.favorite, day.surface, 3f)
    }

    @Test fun nightIsDeepEmeraldWithLegibleMintAndCopper() {
        val night = archivePlayerPalette(DarkScaffold)
        assertNotSame(archivePlayerPalette(LightScaffold), night)
        assertEquals(Color(0xFF0B1A16), night.surface)
        assertEquals(Color(0xFF7FD1B4), night.accent)
        // لا أسود خالص: الليل زمرّديّ.
        assertTrue(night.surface != Color.Black && night.surface.luminance() > .005f)
        for (text in listOf(night.text, night.muted, night.accent)) {
            assertContrast(text, night.surface, 4.5f)
        }
        assertContrast(night.text, night.button, 4.5f)
        assertContrast(night.playGlyph, night.playFill, 4.5f)
        assertContrast(night.offlineText, night.offline, 4.5f)
        assertContrast(night.colors.tertiary, night.surface, 3f) // درجات المنبر النحاسية
        assertContrast(night.track, night.surface, 3f)
        assertContrast(night.favorite, night.surface, 3f)
        assertContrast(appBarForeground(dark = true), AppBarBackgroundDark, 4.5f)
        assertNull(night.statusStrip)
    }

    @Test fun echoPhaseWrapsAndClampsLongGaps() {
        val p = ArchiveOrbitPolicy.advanceEcho(.95f, 360_000_000L) // 0.1s cap → +0.0278
        assertTrue(p in 0f..1f)
        assertEquals(ArchiveOrbitPolicy.advanceEcho(0f, 100_000_000L), ArchiveOrbitPolicy.advanceEcho(0f, 9_000_000_000L), 1e-6f)
        assertEquals(.5f, ArchiveOrbitPolicy.advanceEcho(.5f, -5L), 1e-6f)
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val a = foreground.luminance()
        val b = background.luminance()
        val ratio = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
        assertTrue("$foreground on $background: $ratio < $minimum", ratio >= minimum)
    }
}
