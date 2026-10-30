package io.embodit.flooratlas.ui.atlas.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.flooratlas.R
import io.embodit.flooratlas.domain.LanguagePref
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / EN), switched at run time without
 * restarting the Stage: the screen provides a Resources object for that locale and every panel reads
 * its strings from it (Spatial Ruler). `LocalContext` is not overridden.
 */
val LocalAtlasResources = staticCompositionLocalOf<Resources> { error("LocalAtlasResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalAtlasResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalAtlasResources.current.getString(id, *args)

/** Names, facts and speech follow the panel language. */
@Composable
@ReadOnlyComposable
fun isZh(): Boolean = LocalAtlasResources.current.getString(R.string.lang_code) == "zh"

/** "1:07" (minutes:seconds, tabular). */
fun clock(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** "8,700" / "0.71" style numbers. */
fun grouped(n: Int): String = String.format(Locale.US, "%,d", n)

fun metres(m: Float): String = if (m >= 10f) String.format(Locale.US, "%.0f", m) else String.format(Locale.US, "%.1f", m)

/** Panel sizes (design-package `panels[].sizeDp`), in dp. */
object PanelSizes {
    const val BOARD_W = 600f
    const val BOARD_H = 460f
    const val TAG_W = 360f
    const val TAG_H = 128f
    const val MENU_W = 360f
    const val MENU_H = 388f
    const val LABEL_W = 300f
    const val LABEL_H = 88f
    const val MEASURE_W = 200f
    const val MEASURE_H = 56f
    const val LANDFORM_W = 280f
    const val LANDFORM_H = 84f
    const val SNACK_W = 520f
    const val SNACK_H = 56f
    const val COACH_W = 520f
    const val COACH_H = 104f

    const val DISABLED_ALPHA = 0.38f
}
