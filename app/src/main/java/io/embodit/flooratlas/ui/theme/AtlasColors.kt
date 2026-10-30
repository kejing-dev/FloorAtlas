package io.embodit.flooratlas.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme

/**
 * The design's exact colours (design-package `palette.inWorld`). Colours on the floor live only in entity
 * materials; on the panels only the right / wrong / reveal strips use them, text stays white (D13).
 */
object AtlasColors {
    val Right = Color(0xFF3DDC84) // design-style: fixed-figma-color right
    val Wrong = Color(0xFFFF5A5F) // design-style: fixed-figma-color wrong
    val Reveal = Color(0xFFFF9F1C) // design-style: fixed-figma-color reveal
    val Interaction = Color(0xFFFFD23F) // design-style: fixed-figma-color interaction yellow (crosshair, measure)
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color rugBorder / outline
    val Ink = Color(0xFF16181C) // design-style: fixed-figma-color content on interaction yellow
    val Dim = Color(0xFF5A5F68) // design-style: fixed-figma-color unlit progress segment
}

/** Type roles (design `typography.roles`): tabular figures wherever a number is shown. */
object AtlasType {
    val number: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum, lnum")

    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLargeMultiline

    val hint: TextStyle
        @Composable get() = PicoTheme.typography.labelMedium.copy(fontSize = 14.sp, lineHeight = 18.sp)
}
