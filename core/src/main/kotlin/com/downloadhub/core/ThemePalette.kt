package com.downloadhub.core

/**
 * The themes, as plain numbers.
 *
 * Both apps offer the same nine, and they are defined here rather than in either UI
 * module for the reason the shared module exists at all: the Android app had nine and the
 * desktop app had one, and there was nothing tying the two together, so "the same themes
 * on both" was an aspiration rather than anything the build could check. The palette is
 * plain `Long` ARGB with no Compose type anywhere in it, because `:core` is a plain JVM
 * module and must stay that way - a `Color` here would drag the Compose runtime into the
 * engine.
 *
 * Every value matches the Android app's, so a user who has chosen Ocean on their phone
 * gets Ocean here.
 */
enum class ThemePalette(
    /** The stored form. Stable: renaming an entry would silently reset a saved choice. */
    val value: String,
    val label: String,
    /** The accent on a light surface, and the one the theme's name refers to. */
    val accent: Long,
    /** The accent on a dark surface, which is a step lighter so it reads against near-black. */
    val accentDark: Long,
    /** A very pale wash of the accent, for containers on a light surface. */
    val lightSurfaceHint: Long
) {
    /** The indigo AB Download Manager is known for; the default for a new install. */
    AURORA("AURORA", "Aurora", 0xFF4F5BD5, 0xFF8A94FF, 0xFFECEEFD),
    MINT("MINT", "Mint", 0xFF10B981, 0xFF34D399, 0xFFE7F8F1),
    FOREST("FOREST", "Forest", 0xFF15803D, 0xFF4ADE80, 0xFFE8F5EC),
    /**
     * `0xFF0277B0`, not `0xFF0284C7`.
     *
     * Seven steps deeper, which is the smallest change that lets white text on it reach
     * 4.5 to 1. The original sat at 4.48 against white and 4.1 against the dark
     * foreground, so no readable text colour existed for it at all - a button in this
     * theme was a button whose label could not be read. Every other accent already had a
     * foreground that cleared the bar; this was the one that did not, and it is very
     * nearly the colour it was.
     */
    OCEAN("OCEAN", "Ocean", 0xFF0277B0, 0xFF38BDF8, 0xFFE6F4FB),
    ROYAL("ROYAL", "Royal", 0xFF4F46E5, 0xFF818CF8, 0xFFECEBFE),
    VIOLET("VIOLET", "Violet", 0xFF7C3AED, 0xFFA78BFA, 0xFFF1EAFE),
    SUNSET("SUNSET", "Sunset", 0xFFEA580C, 0xFFFB923C, 0xFFFDEEE2),
    ROSE("ROSE", "Rose", 0xFFE11D48, 0xFFFB7185, 0xFFFDE9EE),
    SLATE("SLATE", "Slate", 0xFF475569, 0xFF94A3B8, 0xFFEEF1F5),

    /**
     * A green of its own, not Forest's.
     *
     * AMOLED is a *mode* - true black, any accent - and [ThemeMode.AMOLED] is how you ask
     * for it. This palette exists only because the Android app has always offered it as a
     * colour choice, and a user who picked it must keep the accent they had. Its dark
     * accent is deliberately a paler green than Forest's rather than the same value:
     * sharing one meant that choosing between them, in a list, changed nothing at all.
     */
    AMOLED("AMOLED", "AMOLED", 0xFF22C55E, 0xFF86EFAC, 0xFFE6FBF0);

    /** Perceived brightness of this theme's light accent, which decides on-accent text. */
    fun accentLuminance(): Float {
        val r = (accent shr 16 and 0xFF) / 255f
        val g = (accent shr 8 and 0xFF) / 255f
        val b = (accent and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    companion object {
        fun fromValue(value: String?): ThemePalette =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: MINT
    }
}

/**
 * Light, dark, or AMOLED.
 *
 * AMOLED is a mode rather than a palette, exactly as on Android: it replaces the
 * background and surface with true black and leaves the accent alone, so it composes with
 * every colour theme instead of imposing one. Choosing "AMOLED" in the theme list and
 * choosing it here are different things, and conflating them is how the Android app ended
 * up discarding the colour the user had picked.
 */
enum class ThemeMode(val value: String, val label: String) {
    LIGHT("LIGHT", "Light"),
    DARK("DARK", "Dark"),
    AMOLED("AMOLED", "AMOLED black");

    companion object {
        fun fromValue(value: String?): ThemeMode =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: DARK
    }
}

/**
 * Every colour role the UI paints, resolved for one palette and one mode.
 *
 * The desktop window is not drawn with a Material scheme alone. Rows, headers, the sidebar
 * and the status strip are flat fills chosen to sit together, and a scheme does not reach
 * them - which is why the desktop app was a single fixed green however the Material theme
 * was set, and why a theme picker alone would have changed nothing at all. These are the
 * roles that reach the flat-drawn parts, and they are resolved once, here, so the two apps
 * agree on what "Ocean, dark" means rather than each deciding for itself.
 */
data class ThemeColors(
    val palette: ThemePalette,
    val mode: ThemeMode,
    /** The window behind everything. */
    val background: Long,
    /** A panel: rows, list bodies, cards. */
    val surface: Long,
    /** Above a surface: menus, the raised parts of fields. */
    val raised: Long,
    /** The band behind a column heading - a shade darker than the surface under it. */
    val band: Long,
    /** Body text. */
    val onSurface: Long,
    /** Secondary text: sizes, units, captions. */
    val muted: Long,
    /** Tertiary text, and disabled strokes. */
    val faint: Long,
    /** The accent, as it reads on this mode's surface. */
    val accent: Long,
    /** Text on top of [accent]. */
    val onAccent: Long,
    /** A dim accent surface: a selected row, a chip, a progress track's fill. */
    val accentContainer: Long,
    /** On top of [accentContainer]. */
    val onAccentContainer: Long,
    /** Separators and field outlines. */
    val outline: Long,
    /** A fainter separator. */
    val outlineVariant: Long,
    /** A row that is selected or hovered. */
    val selection: Long,
    val error: Long,
    val onError: Long,
    /** The colour of a menu panel, which sits above [surface] rather than on it. */
    val menuPanel: Long,
    /** A menu's hairline edge. */
    val menuEdge: Long
) {
    val isDark: Boolean get() = mode != ThemeMode.LIGHT
}

/**
 * Resolves [palette] in [mode] into every colour role.
 *
 * The three neutral sets are the Android app's, unchanged, so the two look alike on a
 * matched device. Everything else - the band, the faint text, the selected row, the menu
 * panel - is *derived* from those and from the accent rather than being a ninth palette's
 * worth of hand-picked numbers, because a hand-picked set cannot stay consistent across
 * nine accents and three modes: the accent tints were chosen against one green.
 */
fun resolveThemeColors(palette: ThemePalette, mode: ThemeMode): ThemeColors {
    val accent = if (mode == ThemeMode.LIGHT) palette.accent else palette.accentDark
    val onAccent = readableOnAccent(accent)
    return when (mode) {
        ThemeMode.LIGHT -> ThemeColors(
            palette = palette,
            mode = mode,
            background = 0xFFFBFCFC,
            surface = 0xFFFFFFFF,
            raised = 0xFFEDF2F3,
            band = 0xFFF2F6F6,
            onSurface = 0xFF11181A,
            muted = 0xFF445457,
            faint = 0xFF7C8A8C,
            accent = accent,
            onAccent = onAccent,
            accentContainer = palette.lightSurfaceHint,
            onAccentContainer = shade(palette.accent, 0.75f),
            outline = 0xFFB7C3C5,
            outlineVariant = 0xFFDCE3E4,
            selection = 0x14000000,
            error = 0xFFBA1A1A,
            onError = 0xFFFFFFFF,
            menuPanel = 0xFFFFFFFF,
            menuEdge = 0x22000000
        )

        ThemeMode.DARK -> ThemeColors(
            palette = palette,
            mode = mode,
            // Neutral greys rather than a blue-green tint, so every accent sits on the
            // same quiet ground - the look AB Download Manager's dark theme is known for.
            background = 0xFF141418,
            surface = 0xFF1C1C22,
            raised = 0xFF2A2A32,
            band = 0xFF18181D,
            onSurface = 0xFFECECF1,
            muted = 0xFFB4B4BF,
            faint = 0xFF80808C,
            accent = accent,
            onAccent = onAccent,
            accentContainer = blend(0xFF1C1C22, accent, 0.28f),
            onAccentContainer = 0xFFF2F3FF,
            outline = 0xFF4A4A55,
            outlineVariant = 0xFF2C2C34,
            selection = 0x1AFFFFFF,
            error = 0xFFFFB4AB,
            onError = 0xFF690005,
            menuPanel = 0xFF24242B,
            menuEdge = 0x33FFFFFF
        )

        // True black. Containers keep a small lift off black, or switches, chips and
        // menus disappear into the background - which is the whole failure mode of AMOLED
        // when it is done by setting every colour to zero.
        ThemeMode.AMOLED -> ThemeColors(
            palette = palette,
            mode = mode,
            background = 0xFF000000,
            surface = 0xFF000000,
            raised = 0xFF0D0F0F,
            band = 0xFF050606,
            onSurface = 0xFFEDF3F3,
            muted = 0xFFA8B4B5,
            faint = 0xFF6E7A7B,
            accent = accent,
            onAccent = onAccent,
            accentContainer = shade(accent, 0.7f),
            onAccentContainer = 0xFFEFFCF4,
            outline = 0xFF3A3A3A,
            outlineVariant = 0xFF1B1B1B,
            selection = 0x1AFFFFFF,
            error = 0xFFFFB4AB,
            onError = 0xFF690005,
            menuPanel = 0xFF0B0C0C,
            menuEdge = 0x33FFFFFF
        )
    }
}

/** Multiplies the colour channels, used to build deeper tones. */
fun shade(color: Long, factor: Float): Long {
    val a = color and 0xFF000000L
    val r = ((color shr 16 and 0xFF) * factor).toLong().coerceIn(0L, 255L)
    val g = ((color shr 8 and 0xFF) * factor).toLong().coerceIn(0L, 255L)
    val b = ((color and 0xFF) * factor).toLong().coerceIn(0L, 255L)
    return a or (r shl 16) or (g shl 8) or b
}

/** Blends the colour towards white, for a very pale wash. */
fun tint(color: Long, factor: Float): Long {
    val a = color and 0xFF000000L
    val r = (red(color) + (255 - red(color)) * factor).toLong().coerceIn(0L, 255L)
    val g = (green(color) + (255 - green(color)) * factor).toLong().coerceIn(0L, 255L)
    val b = (blue(color) + (255 - blue(color)) * factor).toLong().coerceIn(0L, 255L)
    return a or (r shl 16) or (g shl 8) or b
}

/** Blends [from] towards [to] by [amount]. */
fun blend(from: Long, to: Long, amount: Float): Long {
    val a = from and 0xFF000000L
    val r = (red(from) + (red(to) - red(from)) * amount).toLong().coerceIn(0L, 255L)
    val g = (green(from) + (green(to) - green(from)) * amount).toLong().coerceIn(0L, 255L)
    val b = (blue(from) + (blue(to) - blue(from)) * amount).toLong().coerceIn(0L, 255L)
    return a or (r shl 16) or (g shl 8) or b
}

private fun red(color: Long): Float = (color shr 16 and 0xFF).toFloat()
private fun green(color: Long): Float = (color shr 8 and 0xFF).toFloat()
private fun blue(color: Long): Float = (color and 0xFF).toFloat()

/** White, and a very dark green-black. */
private const val ON_ACCENT_LIGHT = 0xFFFFFFFFL
private const val ON_ACCENT_DARK = 0xFF06180CL

/**
 * The text colour to put on top of [accent].
 *
 * Picked by measuring, not by guessing. It was a brightness threshold - "if the accent is
 * bright enough, use dark text" - and a threshold is only as good as the value behind it.
 * Mint scores 0.55 on the 0.299/0.587/0.114 weighting, which is below the 0.62 the
 * threshold wanted, so it got white text: white on #10B981 measures 2.54 to 1, and a
 * button labelled with it is a button nobody can read. Nine accents is nine chances to be
 * wrong about that, so the two candidates are compared instead and the readable one wins.
 */
private fun readableOnAccent(accent: Long): Long =
    if (contrastRatio(ON_ACCENT_DARK, accent) >= contrastRatio(ON_ACCENT_LIGHT, accent)) {
        ON_ACCENT_DARK
    } else {
        ON_ACCENT_LIGHT
    }

/** WCAG relative luminance. */
private fun relativeLuminance(color: Long): Float {
    fun channel(shift: Int): Float {
        val v = (color shr shift and 0xFF) / 255f
        return if (v <= 0.03928f) {
            v / 12.92f
        } else {
            Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
    }
    return 0.2126f * channel(16) + 0.7152f * channel(8) + 0.0722f * channel(0)
}

/** WCAG contrast ratio between two opaque colours. */
private fun contrastRatio(a: Long, b: Long): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}
