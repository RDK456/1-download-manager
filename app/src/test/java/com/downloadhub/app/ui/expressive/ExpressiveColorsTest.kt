package com.downloadhub.app.ui.expressive

import androidx.compose.ui.graphics.toArgb
import com.downloadhub.app.data.model.AppTheme
import com.downloadhub.app.ui.expressive.theme.ToneMath
import com.downloadhub.app.ui.expressive.theme.withExpressiveTones
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text on the derived container roles must stay readable in every palette and mode. */
class ExpressiveColorsTest {
    private data class Variant(val theme: AppTheme, val dark: Boolean, val black: Boolean) {
        override fun toString() = "$theme dark=$dark black=$black"
    }

    private val variants = AppTheme.entries.flatMap {
        listOf(Variant(it, false, false), Variant(it, true, false), Variant(it, true, true))
    }

    private fun check(name: String, bg: Int, fg: Int, v: Variant, min: Float) {
        val c = ToneMath.contrast(bg, fg)
        assertTrue("$name contrast ${"%.2f".format(c)} < $min for $v", c >= min)
    }

    @Test
    fun secondaryContainerTextIsReadable() {
        for (v in variants) {
            val s = v.theme.colorScheme(v.dark, v.black).withExpressiveTones()
            check("onSecondaryContainer", s.secondaryContainer.toArgb(), s.onSecondaryContainer.toArgb(), v, 4.5f)
        }
    }

    @Test
    fun tertiaryContainerTextIsReadable() {
        for (v in variants) {
            val s = v.theme.colorScheme(v.dark, v.black).withExpressiveTones()
            check("onTertiaryContainer", s.tertiaryContainer.toArgb(), s.onTertiaryContainer.toArgb(), v, 4.5f)
        }
    }

    @Test
    fun bodyTextIsReadableOnEverySurfaceStep() {
        for (v in variants) {
            val s = v.theme.colorScheme(v.dark, v.black).withExpressiveTones()
            val ink = s.onSurface.toArgb()
            check("surfaceContainerLow", s.surfaceContainerLow.toArgb(), ink, v, 7f)
            check("surfaceContainer", s.surfaceContainer.toArgb(), ink, v, 7f)
            check("surfaceContainerHigh", s.surfaceContainerHigh.toArgb(), ink, v, 7f)
            check("surfaceContainerHighest", s.surfaceContainerHighest.toArgb(), ink, v, 7f)
        }
    }

    @Test
    fun amoledKeepsTheBaseTrulyBlack() {
        for (t in AppTheme.entries) {
            val s = t.colorScheme(dark = true, pureBlack = true).withExpressiveTones()
            assertTrue("$t surface", (s.surface.toArgb() and 0xFFFFFF) == 0)
        }
    }
}
