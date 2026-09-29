package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the shared theme has to hold to, checked across every palette and every mode.
 *
 * There are twenty-seven combinations and a screenshot cannot check them, which is exactly
 * the shape of problem that gets found by a user on a phone they do not usually use. The
 * failures below are all things that would look like "the app looks a bit off" rather than
 * like a bug: a row of text the same shade as the panel behind it, an accent that vanishes
 * against a black background, a theme that claims a name it does not deliver.
 */
class ThemePaletteTest {

    /** Perceived contrast between two opaque colours, 0 to 1. */
    private fun contrast(a: Long, b: Long): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun relativeLuminance(color: Long): Float {
        fun channel(shift: Int): Float {
            val v = (color shr shift and 0xFF) / 255f
            return if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * channel(16) + 0.7152f * channel(8) + 0.0722f * channel(0)
    }

    private fun allCombinations(): List<Pair<ThemePalette, ThemeMode>> =
        ThemePalette.entries.flatMap { p -> ThemeMode.entries.map { m -> p to m } }

    @Test
    fun `every palette resolves in every mode`() {
        assertEquals(27, allCombinations().size)
        for ((palette, mode) in allCombinations()) {
            val c = resolveThemeColors(palette, mode)
            assertEquals("wrong palette echoed back for $palette", palette, c.palette)
            assertEquals("wrong mode echoed back for $palette", mode, c.mode)
        }
    }

    /**
     * Body text has to be readable on the panel it sits on, in all twenty-seven.
     *
     * This is the failure that produced the original complaint that the file list's text
     * was "too dim": `onSurfaceVariant` on this dark scheme sat close enough to the surface
     * to look disabled. It is checked here so the next accent - and the ninth accent is a
     * different colour from the first, chosen without this palette in front of it - cannot
     * quietly reintroduce it.
     */
    @Test
    fun `body text is readable on its own surface in every theme`() {
        for ((palette, mode) in allCombinations()) {
            val c = resolveThemeColors(palette, mode)
            val onSurface = contrast(c.onSurface, c.surface)
            assertTrue(
                "$palette/$mode: body text on a panel has a contrast of %.2f, which is too low to read"
                    .format(onSurface),
                onSurface >= 4.5f
            )
        }
    }

    /** Secondary text is allowed to be quieter, but not by much. */
    @Test
    fun `secondary text is readable on its own surface in every theme`() {
        for ((palette, mode) in allCombinations()) {
            val c = resolveThemeColors(palette, mode)
            val muted = contrast(c.muted, c.surface)
            assertTrue(
                "$palette/$mode: secondary text has a contrast of %.2f, which is too low".format(muted),
                muted >= 3.0f
            )
        }
    }

    /**
     * Text on the accent has to be readable too.
     *
     * The accent is the one colour that comes from the palette rather than from the
     * neutrals, so it is the one whose lightness varies: a pale yellow and a deep indigo
     * cannot both take white text. This is why the light scheme picks its on-accent from
     * the accent's measured brightness rather than always choosing white.
     */
    @Test
    fun `text on the accent is readable in every theme`() {
        for ((palette, mode) in allCombinations()) {
            val c = resolveThemeColors(palette, mode)
            val onAccent = contrast(c.onAccent, c.accent)
            assertTrue(
                "$palette/$mode: text on the accent has a contrast of %.2f, which is too low"
                    .format(onAccent),
                onAccent >= 4.5f
            )
        }
    }

    /**
     * The accent has to be visible against the surface it is used on.
     *
     * A progress bar in the surface colour is a bar that never appears to move, and a
     * selected row in the surface colour is a row that does not look selected.
     */
    @Test
    fun `the accent stands out from the surface in every theme`() {
        for ((palette, mode) in allCombinations()) {
            val c = resolveThemeColors(palette, mode)
            val standing = contrast(c.accent, c.surface)
            assertTrue(
                "$palette/$mode: the accent against the surface has a contrast of %.2f, " +
                    "so it is there but you have to look for it".format(standing),
                standing >= 1.6f
            )
        }
    }

    /**
     * A panel has to be distinguishable from the window behind it.
     *
     * This is the complaint about a lit band appearing where there should not be one: the
     * band is deliberately quieter than the surface, so if it were identical the column
     * headings would float with nothing behind them, and if it were lighter it read as the
     * brightest thing in the window.
     */
    @Test
    fun `the header band is distinct from the surface it sits on`() {
        for ((palette, mode) in allCombinations()) {
            val c = resolveThemeColors(palette, mode)
            assertNotEquals(
                "$palette/$mode: the band is the same colour as the surface, so the " +
                    "headings have nothing behind them",
                c.band,
                c.surface
            )
        }
    }

    /** AMOLED is true black, and only true black. */
    @Test
    fun `amoled mode is pure black`() {
        for (palette in ThemePalette.entries) {
            val c = resolveThemeColors(palette, ThemeMode.AMOLED)
            assertEquals("$palette: AMOLED background is not pure black", 0xFF000000L, c.background)
            assertEquals("$palette: AMOLED surface is not pure black", 0xFF000000L, c.surface)
        }
    }

    /**
     * But containers still get a lift off black.
     *
     * Setting every colour to zero is how AMOLED is usually done and it produces menus and
     * switches that have vanished. A menu panel at 0x0B0C0C against a 0x0 background is
     * still a menu panel.
     */
    @Test
    fun `amoled keeps containers off the black so they are still visible`() {
        for (palette in ThemePalette.entries) {
            val c = resolveThemeColors(palette, ThemeMode.AMOLED)
            assertTrue(
                "$palette: the menu panel is pure black, so a menu has no edge against " +
                    "the window",
                (c.menuPanel and 0xFFFFFFL) > 0L
            )
            assertTrue(
                "$palette: the raised container is pure black, so a switch or a chip " +
                    "disappears",
                (c.raised and 0xFFFFFFL) > 0L
            )
        }
    }

    /** The accent is what makes a theme a theme; it must actually change. */
    @Test
    fun `the nine palettes have nine different accents`() {
        val dark = ThemePalette.entries.map { resolveThemeColors(it, ThemeMode.DARK).accent }
        assertEquals(9, dark.size)
        assertEquals("two themes resolve to the same dark accent", 9, dark.toSet().size)
    }

    /**
     * And the light accent is the theme's own, not the dark one reused.
     *
     * They differ in both applications: a light mode wants the deeper of the pair, a dark
     * mode wants the lighter. If the light mode ever used the dark accent, the pale themes
     * - Mint, Sunset - would look washed out on white.
     */
    @Test
    fun `the light mode uses the deeper of each pair of accents`() {
        for (palette in ThemePalette.entries) {
            assertEquals(
                "$palette: the light mode must use the theme's own accent",
                palette.accent,
                resolveThemeColors(palette, ThemeMode.LIGHT).accent
            )
            assertEquals(
                "$palette: the dark mode must use the lighter accent",
                palette.accentDark,
                resolveThemeColors(palette, ThemeMode.DARK).accent
            )
        }
    }

    /**
     * A stored value that is not a known theme falls back rather than throwing.
     *
     * The stored form is a name in a settings file, and a settings file is exactly the sort
     * of thing that survives a downgrade or arrives hand-edited. A theme that throws on
     * read is an app that will not start.
     */
    @Test
    fun `an unknown stored theme falls back instead of throwing`() {
        assertEquals(ThemePalette.MINT, ThemePalette.fromValue(null))
        assertEquals(ThemePalette.MINT, ThemePalette.fromValue(""))
        assertEquals(ThemePalette.MINT, ThemePalette.fromValue("CHARTREUSE"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromValue(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromValue("SEPIA"))
    }

    /** Reading is case-insensitive, because a hand-edited file is likely to be. */
    @Test
    fun `a stored theme is matched without regard to case`() {
        assertEquals(ThemePalette.OCEAN, ThemePalette.fromValue("ocean"))
        assertEquals(ThemePalette.SUNSET, ThemePalette.fromValue("Sunset"))
        assertEquals(ThemeMode.AMOLED, ThemeMode.fromValue("amoled"))
    }

    /**
     * Every role is opaque.
     *
     * A colour with an alpha channel in it is composited against whatever it lands on, so
     * a surface at 0x80FFFFFF is half the brightness of the intended white on a dark
     * window and full on a light one. The translucent roles - selection, menuEdge - are the
     * only ones allowed to be, and they are deltas rather than surfaces.
     */
    @Test
    fun `every surface role is fully opaque`() {
        for ((palette, mode) in allCombinations()) {
            val c = resolveThemeColors(palette, mode)
            val roles = listOf(
                "background" to c.background,
                "surface" to c.surface,
                "raised" to c.raised,
                "band" to c.band,
                "onSurface" to c.onSurface,
                "muted" to c.muted,
                "faint" to c.faint,
                "accent" to c.accent,
                "onAccent" to c.onAccent,
                "accentContainer" to c.accentContainer,
                "outline" to c.outline,
                "outlineVariant" to c.outlineVariant,
                "menuPanel" to c.menuPanel
            )
            for ((name, value) in roles) {
                assertEquals(
                    "$palette/$mode: $name is translucent, so it looks different on a light " +
                        "and a dark window",
                    0xFF000000L,
                    value and 0xFF000000L
                )
            }
        }
    }
}
