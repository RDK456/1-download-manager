package com.downloadhub.app.ui.expressive

import com.downloadhub.app.ui.expressive.theme.ToneMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneMathTest {
    private val red = 0xFFFF0000.toInt()
    private val grey = 0xFF808080.toInt()

    @Test
    fun mixEndsAreTheInputs() {
        assertEquals(red, ToneMath.mix(red, grey, 0f))
        assertEquals(grey, ToneMath.mix(red, grey, 1f))
    }

    @Test
    fun mixHalfwayIsTheAverage() {
        val m = ToneMath.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(0xFF808080.toInt(), m)
    }

    @Test
    fun hueRotationOfRedBySixtyIsYellow() {
        val y = ToneMath.rotateHue(red, 60f)
        assertEquals(0xFFFFFF00.toInt(), y)
    }

    @Test
    fun fullTurnReturnsTheSameColour() {
        val c = 0xFF3A7BD5.toInt()
        assertEquals(c, ToneMath.rotateHue(c, 360f))
    }

    @Test
    fun negativeTurnWraps() {
        assertEquals(ToneMath.rotateHue(red, 300f), ToneMath.rotateHue(red, -60f))
    }

    @Test
    fun rotationKeepsLightness() {
        val c = 0xFF2E8B57.toInt()
        val before = ToneMath.toHsl(c).third
        val after = ToneMath.toHsl(ToneMath.rotateHue(c, 60f)).third
        assertTrue(abs(before - after) < 0.01f)
    }

    @Test
    fun zeroSaturationIsGreyAtTheSameLightness() {
        val c = ToneMath.scaleSaturation(0xFF2E8B57.toInt(), 0f)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        assertTrue(r == g && g == b)
    }

    @Test
    fun alphaSurvivesRotation() {
        val c = 0x80FF0000.toInt()
        assertEquals(0x80, ToneMath.rotateHue(c, 90f) ushr 24)
    }

    @Test
    fun contrastOfBlackOnWhiteIsTwentyOne() {
        assertEquals(21f, ToneMath.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01f)
        assertEquals(1f, ToneMath.contrast(red, red), 0.001f)
    }

    @Test
    fun readableOnKeepsTextThatAlreadyReads() {
        val fg = 0xFF111111.toInt()
        assertEquals(fg, ToneMath.readableOn(0xFFEEEEEE.toInt(), fg))
    }

    @Test
    fun readableOnFixesTextThatDoesNot() {
        val bg = 0xFF2A6F4E.toInt()
        val fg = 0xFF336655.toInt()
        val fixed = ToneMath.readableOn(bg, fg)
        assertTrue(ToneMath.contrast(fixed, bg) >= 4.5f)
    }

    @Test
    fun readableOnPullsTowardsTheFurtherExtreme() {
        val light = ToneMath.readableOn(0xFFF0F0F0.toInt(), 0xFFB0B0B0.toInt())
        val dark = ToneMath.readableOn(0xFF101010.toInt(), 0xFF505050.toInt())
        assertTrue(ToneMath.luminance(light) < 0.3f)
        assertTrue(ToneMath.luminance(dark) > 0.15f)
    }
}
