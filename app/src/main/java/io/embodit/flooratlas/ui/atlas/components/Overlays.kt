package io.embodit.flooratlas.ui.atlas.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.flooratlas.R
import io.embodit.flooratlas.domain.AnswerMode
import io.embodit.flooratlas.domain.Atlas
import io.embodit.flooratlas.domain.LanguagePref
import io.embodit.flooratlas.ui.atlas.AtlasEvent
import io.embodit.flooratlas.ui.atlas.AtlasUiState
import io.embodit.flooratlas.ui.atlas.Coach
import io.embodit.flooratlas.ui.atlas.FeedbackKind
import io.embodit.flooratlas.ui.atlas.LiveInfo
import io.embodit.flooratlas.ui.atlas.Phase
import io.embodit.flooratlas.ui.atlas.SnackKind
import io.embodit.flooratlas.ui.atlas.SnackUi
import io.embodit.flooratlas.ui.atlas.debug.probe
import io.embodit.flooratlas.ui.atlas.scene.LabelTone
import io.embodit.flooratlas.ui.atlas.scene.LabelUi
import io.embodit.flooratlas.ui.theme.AtlasColors
import io.embodit.flooratlas.ui.theme.AtlasType

private val ink: Color
    @Composable get() = PicoTheme.colorScheme.labelPrimary

private val ink2: Color
    @Composable get() = PicoTheme.colorScheme.labelSecondary

private fun Modifier.glass(w: Float, h: Float, corner: Int = 24): Modifier = this.size(w.dp, h.dp).clip(RoundedCornerShape(corner.dp))

private fun toneColor(t: LabelTone): Color? =
    when (t) {
        LabelTone.PLAIN -> null
        LabelTone.RIGHT -> AtlasColors.Right
        LabelTone.WRONG -> AtlasColors.Wrong
        LabelTone.REVEAL -> AtlasColors.Reveal
    }

/** A coloured bar at the left edge of a label (design `panels.label`: right / wrong / reveal); text stays white. */
@Composable
private fun ToneBar(color: Color?) {
    Box(modifier = Modifier.width(6.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color ?: PicoTheme.colorScheme.labelPrimary))
}

/**
 * The question tag beyond the crosshair (design `panels.questionTag`): "Go to Sahara" + chances + clock; while
 * feedback shows it carries it; when revealing, an arrow turned towards the answer.
 */
@Composable
internal fun QuestionTag(state: AtlasUiState?, live: LiveInfo, atlas: Atlas) {
    Row(
        modifier = Modifier.glass(PanelSizes.TAG_W, PanelSizes.TAG_H).backgroundMaterial(enable = true, style = Material.Regular).then(glassRefresh()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val q = state?.quiz ?: return@Row
        val zh = isZh()
        val set = atlas.set(q.setId) ?: return@Row
        val f = state.feedback
        val paused = state.phase == Phase.PAUSED
        val (bar, text) =
            when {
                paused -> null to rs(R.string.tag_paused)
                f?.kind == FeedbackKind.RIGHT -> AtlasColors.Right to rs(R.string.right)
                q.revealing -> AtlasColors.Reveal to rs(R.string.reveal, set.target(q.current ?: "")?.name(zh) ?: "")
                f?.kind == FeedbackKind.WRONG -> AtlasColors.Wrong to rs(R.string.wrong_here, set.target(f.targetId)?.name(zh) ?: "")
                live.offMap -> null to rs(R.string.off_map)
                else -> null to "${rs(R.string.ask_go)} ${set.target(q.current ?: "")?.name(zh) ?: ""}"
            }
        if (bar != null) ToneBar(bar)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = text, color = if (paused) ink2 else ink, style = PicoTheme.typography.titleLarge, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chances(q.chancesLeft, 10)
                Text(text = clock(q.elapsedMs), color = ink2, style = AtlasType.label, maxLines = 1)
                if (!paused && f == null && !q.revealing && live.nowhere && !live.offMap) Text(text = rs(R.string.nowhere), color = ink2, style = AtlasType.hint, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
        if (q.revealing && !paused) Icon(painter = painterResource(R.drawable.ic_arrow), contentDescription = null, modifier = Modifier.size(32.dp).rotate(live.arrowDeg), tint = AtlasColors.Reveal)
    }
}

/** The More menu (design `panels.moreMenu`), a pre-built panel beside the board's More key. */
@Composable
internal fun MoreMenu(state: AtlasUiState?, voice: Boolean, onEvent: (AtlasEvent) -> Unit, onRecenter: () -> Unit) {
    Column(
        modifier = Modifier.glass(PanelSizes.MENU_W, PanelSizes.MENU_H).backgroundMaterial(enable = true, style = Material.Thick).then(glassRefresh()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state == null) return@Column
        val w = PanelSizes.MENU_W - 24f
        Text(text = rs(R.string.menu_answer), color = ink2, style = AtlasType.hint, maxLines = 1)
        // sized for the longest language ("drag to your feet"): wide enough for one line, room for two
        Segments(AnswerMode.entries, state.answerMode, w, heightDp = 56, enabled = !state.inQuiz, name = { "answer_${it.tag}" }, onSelect = { onEvent(AtlasEvent.AnswerModeSelected(it)) }) {
            Text(text = rs(if (it == AnswerMode.DRAG) R.string.answer_drag else R.string.answer_point), color = ink, style = AtlasType.label.copy(textAlign = TextAlign.Center), maxLines = 2)
        }
        Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(R.drawable.ic_voice), contentDescription = null, modifier = Modifier.size(22.dp), tint = if (voice) ink else PicoTheme.colorScheme.labelQuaternary)
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = rs(if (voice) R.string.menu_speak else R.string.menu_no_voice), color = if (voice) ink else ink2, style = AtlasType.label, maxLines = 2, modifier = Modifier.weight(1f))
            Switch(checked = voice && state.settings.speak, onCheckedChange = { onEvent(AtlasEvent.SpeakToggled) }, enabled = voice, modifier = Modifier.probe("speak"))
        }
        Segments(LanguagePref.entries, state.language, w, name = { "lang_${it.tag}" }, onSelect = { onEvent(AtlasEvent.LanguageSelected(it)) }) {
            when (it) {
                LanguagePref.SYSTEM -> Icon(painter = painterResource(R.drawable.ic_globe), contentDescription = rs(R.string.lang_system), modifier = Modifier.size(20.dp), tint = ink)
                LanguagePref.ZH -> Text(text = rs(R.string.lang_zh), color = ink, style = AtlasType.label, maxLines = 1)
                LanguagePref.EN -> Text(text = rs(R.string.lang_en_short), color = ink, style = AtlasType.label, maxLines = 1)
            }
        }
        if (state.floorEstimate) {
            Row(modifier = Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = rs(R.string.menu_floor), color = ink, style = AtlasType.label, maxLines = 1, modifier = Modifier.weight(1f))
                IconKey(R.drawable.ic_minus, rs(R.string.zoom_out), "floor_down", width = 52) { onEvent(AtlasEvent.FloorAdjust(-1)) }
                Text(text = "${state.settings.floorAdjustCm} cm", color = ink, style = AtlasType.label, maxLines = 1)
                IconKey(R.drawable.ic_plus, rs(R.string.zoom_in), "floor_up", width = 52) { onEvent(AtlasEvent.FloorAdjust(1)) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextKey(rs(R.string.recenter), "menu_recenter", modifier = Modifier.weight(1f), icon = R.drawable.ic_recenter) {
                onRecenter()
                onEvent(AtlasEvent.MenuDismissed)
            }
            TextKey(rs(R.string.menu_exit), "menu_exit", icon = R.drawable.ic_exit) { onEvent(AtlasEvent.ExitClicked) }
        }
    }
}

@Composable
private fun <T> Segments(items: List<T>, selected: T, widthDp: Float, heightDp: Int = 48, enabled: Boolean = true, name: (T) -> String, onSelect: (T) -> Unit, title: @Composable (T) -> Unit) {
    SegmentControl(modifier = Modifier.fillMaxWidth().height(heightDp.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        val w = ((widthDp - 8f) / items.size).dp
        items.forEach { item ->
            SegmentItem(
                selected = item == selected,
                onClick = { if (enabled) onSelect(item) },
                modifier = Modifier.width(w).fillMaxHeight().probe(name(item)),
                textStyle = PicoTheme.typography.labelLarge,
                colors = SegmentControlDefaults.colors(itemBackgroundColor = Color.Transparent, itemContentColor = ink, selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary, selectedItemContentColor = ink),
                title = { title(item) },
            )
        }
    }
}

/** The snackbar above the board (design `panels.snackbar`). */
@Composable
internal fun SnackPanel(snack: SnackUi?) {
    Box(modifier = Modifier.size(PanelSizes.SNACK_W.dp, PanelSizes.SNACK_H.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val (icon, text) =
            when (snack.kind) {
                SnackKind.FLOOR_ESTIMATE -> R.drawable.ic_warning to rs(R.string.snack_floor)
                SnackKind.LOST -> R.drawable.ic_unavailable to rs(R.string.snack_lost)
                SnackKind.ZOOM_MAX -> R.drawable.ic_plus to rs(R.string.snack_max)
                SnackKind.ZOOM_MIN -> R.drawable.ic_minus to rs(R.string.snack_min)
                SnackKind.OFF_MAP -> R.drawable.ic_recenter to rs(R.string.off_map)
            }
        Row(
            modifier = Modifier.height(PanelSizes.SNACK_H.dp).clip(RoundedCornerShape(28.dp)).backgroundMaterial(enable = true, style = Material.Thick).then(glassRefresh()).probe("snackbar").padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = ink)
            Text(text = text, color = ink, style = PicoTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

/** First-run coach above the board (design `panels.coach`): a sentence, the controller pictograms, "Got it". */
@Composable
internal fun CoachPanel(coach: Coach?, onEvent: (AtlasEvent) -> Unit) {
    Row(
        modifier = Modifier.glass(PanelSizes.COACH_W, PanelSizes.COACH_H).backgroundMaterial(enable = true, style = Material.Thick).then(glassRefresh()).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (coach == null) return@Row
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(painter = painterResource(R.drawable.ic_controller_l), contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
            Icon(painter = painterResource(if (coach == Coach.ASK) R.drawable.ic_drag else R.drawable.ic_controller_r), contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
        }
        Text(text = rs(if (coach == Coach.ASK) R.string.coach_ask else R.string.coach_explore), color = ink, style = AtlasType.body, maxLines = 3, modifier = Modifier.weight(1f))
        IconButton(
            onClick = { onEvent(AtlasEvent.CoachDismissed) },
            modifier = Modifier.probe("coach_ok"),
            colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = AtlasColors.Ink),
            size = IconButtonDefaults.iconButtonSize(width = 52.dp, height = 52.dp),
        ) { Icon(painter = painterResource(R.drawable.ic_check), contentDescription = rs(R.string.coach_ok), modifier = Modifier.size(24.dp), tint = AtlasColors.Ink) }
    }
}

/** A name label over a place (design `panels.label`). */
@Composable
internal fun NameLabel(label: LabelUi?) {
    Row(
        modifier = Modifier.glass(PanelSizes.LABEL_W, PanelSizes.LABEL_H, 22).backgroundMaterial(enable = true, style = Material.Thick).then(glassRefresh()).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (label == null) return@Row
        ToneBar(toneColor(label.tone))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label.title, color = ink, style = PicoTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            label.subtitle?.let { Text(text = it, color = ink2, style = AtlasType.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** "7,550 km" over the middle of the measuring line. */
@Composable
internal fun MeasureTag(label: LabelUi?) {
    Box(modifier = Modifier.glass(PanelSizes.MEASURE_W, PanelSizes.MEASURE_H, 28).backgroundMaterial(enable = true, style = Material.Thick).then(glassRefresh()), contentAlignment = Alignment.Center) {
        if (label == null) return@Box
        Text(text = label.title, color = ink, style = AtlasType.number, maxLines = 1)
    }
}

/** A landform plate (design `terrain.featured.labels`): name + height, one fact line. */
@Composable
internal fun LandformPlate(label: LabelUi?) {
    Column(
        modifier = Modifier.glass(PanelSizes.LANDFORM_W, PanelSizes.LANDFORM_H, 20).backgroundMaterial(enable = true, style = Material.Thick).then(glassRefresh()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        if (label == null) return@Column
        Text(text = label.title, color = ink, style = PicoTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum, lnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        label.subtitle?.let { Text(text = it, color = ink2, style = AtlasType.hint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}
