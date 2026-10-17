package com.downloadhub.app.ui.expressive.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colour arithmetic on plain ARGB ints, so the tone rules for the expressive scheme can be
 * unit tested without Compose or Android. The palettes in `:core` carry one accent; the
 * expressive look wants a secondary and a tertiary container beside it, and these functions
 * derive them.
 */
internal object ToneMath {

    /** Linear blend of two ARGB colours; [t] 0 gives [a], 1 gives [b]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * k).roundToInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Turns the hue by [degrees], keeping saturation, lightness and alpha. */
    fun rotateHue(argb: Int, degrees: Float): Int {
        val (h, s, l) = toHsl(argb)
        return fromHsl(((h + degrees) % 360f + 360f) % 360f, s, l, argb ushr 24)
    }

    /** Multiplies saturation by [factor], keeping hue, lightness and alpha. */
    fun scaleSaturation(argb: Int, factor: Float): Int {
        val (h, s, l) = toHsl(argb)
        return fromHsl(h, (s * factor).coerceIn(0f, 1f), l, argb ushr 24)
    }

    /** Hue in degrees, saturation and lightness in 0..1. */
    fun toHsl(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val l = (hi + lo) / 2f
        val d = hi - lo
        if (d == 0f) return Triple(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (hi) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return Triple((h + 360f) % 360f, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float, alpha: Int = 0xFF): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** WCAG relative luminance, 0..1. */
    fun luminance(argb: Int): Float {
        fun lin(c: Int): Float {
            val v = c / 255f
            return if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * lin((argb shr 16) and 0xFF) + 0.7152f * lin((argb shr 8) and 0xFF) + 0.0722f * lin(argb and 0xFF)
    }

    /** WCAG contrast ratio, 1..21. */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /**
     * [fg] if it already reads on [bg] at ratio [min], otherwise [fg] pulled towards black or
     * white (whichever the background is further from) just far enough to read. Turning a
     * container's hue changes how light it looks even at the same HSL lightness, so the text
     * colour that suited the original container can fail on the turned one.
     */
    fun readableOn(bg: Int, fg: Int, min: Float = 4.5f): Int {
        if (contrast(fg, bg) >= min) return fg
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val target = if (contrast(black, bg) >= contrast(white, bg)) black else white
        var t = 0f
        while (t < 1f) {
            t += 0.05f
            val c = mix(fg, target, t)
            if (contrast(c, bg) >= min) return c
        }
        return target
    }
}
