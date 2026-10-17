package com.downloadhub.app.ui.expressive.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.downloadhub.app.R

/**
 * Roboto Flex, the variable face Material 3 Expressive is drawn in. One file carries every
 * weight and width, so the emphasised styles (heavier at the same size) cost nothing extra.
 * Variation settings need API 26, which is the app's minSdk.
 */
private fun flex(weight: Int, width: Float = 100f) = Font(
    resId = R.font.roboto_flex,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width)
    )
)

val RobotoFlex = FontFamily(
    flex(400),
    flex(500),
    flex(600),
    flex(700),
    flex(800, width = 108f)
)

/**
 * The library's default family is plain sans-serif, so the app supplies its own. The
 * constructor merges it into every baseline and emphasised style that has no family of its
 * own. Digits that must not jitter (speeds, sizes, times) use `Mono` from the old theme
 * file, which stays until the old UI is removed.
 */
val ExpressiveTypography: Typography = Typography(fontFamily = RobotoFlex)
