package io.embodit.flooratlas.ui.atlas.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.pico.spatial.ui.design.LocalTextStyle
import io.embodit.flooratlas.platform.AtlasLog
import io.embodit.flooratlas.ui.atlas.debug.LocalProbePanel
import io.embodit.flooratlas.ui.atlas.debug.Probe
import java.util.Collections

/**
 * Every panel text goes through here: the SpatialUI Text, plus (debug builds) a `CLIP panel=<id> text=` log
 * line the first time a string is cut short (ellipsis, a hidden extra line, or wider than its box). QA runs
 * every state in both languages and greps for it (profile D13 / D16: copy is sized for the longest language).
 */
@Composable
internal fun Text(
    text: String,
    color: Color,
    style: TextStyle = LocalTextStyle.current,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val panel = if (Probe.enabled) LocalProbePanel.current else null
    com.pico.spatial.ui.design.Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout =
            if (panel == null) {
                {}
            } else {
                { r ->
                    val cut = r.hasVisualOverflow || (r.lineCount > 0 && r.isLineEllipsized(r.lineCount - 1))
                    if (cut && reported.add("$panel|$text")) AtlasLog.w("CLIP panel=$panel lines=${r.lineCount}/$maxLines w=${r.size.width} text=$text")
                }
            },
    )
}

private val reported: MutableSet<String> = Collections.synchronizedSet(HashSet())
