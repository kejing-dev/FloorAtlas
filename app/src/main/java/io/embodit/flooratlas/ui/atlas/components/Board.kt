package io.embodit.flooratlas.ui.atlas.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Badge
import com.pico.spatial.ui.design.BadgeDefaults
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.flooratlas.R
import io.embodit.flooratlas.domain.AnswerMode
import io.embodit.flooratlas.domain.Atlas
import io.embodit.flooratlas.domain.Mode
import io.embodit.flooratlas.domain.Outcome
import io.embodit.flooratlas.domain.Scale
import io.embodit.flooratlas.ui.atlas.AtlasEvent
import io.embodit.flooratlas.ui.atlas.AtlasUiState
import io.embodit.flooratlas.ui.atlas.FeedbackKind
import io.embodit.flooratlas.ui.atlas.LiveInfo
import io.embodit.flooratlas.ui.atlas.Phase
import io.embodit.flooratlas.ui.atlas.debug.probe
import io.embodit.flooratlas.ui.theme.AtlasColors
import io.embodit.flooratlas.ui.theme.AtlasType

/** Map moves for hand users (panel buttons): turn, zoom, bring to me. */
class MapActions(val rotate: (Int) -> Unit, val zoom: (Int) -> Unit, val recenter: () -> Unit)

private val KeyH = 52.dp

/**
 * The board (design `panels.board`): the one resident panel, 600 x 460 dp of Regular glass. One layout per phase;
 * numbers in tabular figures; status colours only as small strips, text stays white (D13).
 */
@Composable
internal fun BoardPanel(state: AtlasUiState?, live: LiveInfo, atlas: Atlas, actions: MapActions, onEvent: (AtlasEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.BOARD_W.dp, PanelSizes.BOARD_H.dp)
                .clip(RoundedCornerShape(32.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null) return@Column
        when (state.phase) {
            Phase.STARTING -> Starting()
            Phase.PICKER -> Picker(state, atlas, onEvent)
            Phase.EXPLORE -> Explore(state, live, atlas, actions, onEvent)
            Phase.QUIZ_READY -> QuizReady(state, live, atlas, onEvent)
            Phase.ASKING -> Asking(state, live, atlas, actions, onEvent)
            Phase.PAUSED -> Paused(state, atlas, onEvent)
            Phase.RESULT -> Result(state, atlas, onEvent)
            Phase.BLOCKED -> Blocked(state, onEvent)
        }
    }
}

private val ink: Color
    @Composable get() = PicoTheme.colorScheme.labelPrimary

private val ink2: Color
    @Composable get() = PicoTheme.colorScheme.labelSecondary

@Composable
private fun ColumnScope.Starting() {
    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp), color = ink, progressSize = CircularProgressDefaults.circleProgressSize(40.dp))
            Text(text = rs(R.string.starting), color = ink, style = PicoTheme.typography.bodyLarge)
        }
    }
}

// ------------------------------------------------------------------ shared pieces

@Composable
internal fun IconKey(icon: Int, description: String, name: String, selected: Boolean = false, enabled: Boolean = true, width: Int = 56, onClick: () -> Unit) {
    val tint = if (enabled) ink else PicoTheme.colorScheme.labelQuaternary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (selected) PicoTheme.colorScheme.fillSecondary else PicoTheme.colorScheme.fillTertiary, contentColor = tint),
        size = IconButtonDefaults.iconButtonSize(width = width.dp, height = KeyH),
    ) { Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp), tint = tint) }
}

@Composable
internal fun TextKey(text: String, name: String, modifier: Modifier = Modifier, icon: Int? = null, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val container = if (primary) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary
    val content = if (!enabled) PicoTheme.colorScheme.labelQuaternary else if (primary) AtlasColors.Ink else ink
    Button(
        onClick = onClick,
        modifier = modifier.probe(name),
        enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = KeyH, minWidth = 96.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = content)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, style = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun TopBar(left: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        left()
        Spacer(modifier = Modifier.weight(1f))
        right()
    }
}

@Composable
private fun BackKey(onEvent: (AtlasEvent) -> Unit) = IconKey(R.drawable.ic_back, rs(R.string.action_back), "back") { onEvent(AtlasEvent.Back) }

@Composable
private fun MuteKey(state: AtlasUiState, onEvent: (AtlasEvent) -> Unit) {
    val muted = state.settings.muted
    IconKey(if (muted) R.drawable.ic_mute else R.drawable.ic_sound, rs(if (muted) R.string.action_unmute else R.string.action_mute), "mute", selected = muted) { onEvent(AtlasEvent.MuteToggled) }
}

@Composable
private fun MoreKey(state: AtlasUiState, onEvent: (AtlasEvent) -> Unit) = IconKey(R.drawable.ic_more, rs(R.string.action_more), "more", selected = state.menuOpen) { onEvent(AtlasEvent.MoreClicked) }

@Composable
private fun Title(text: String) = Text(text = text, color = ink, style = PicoTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** "One step ≈ 8,700 km · detail: world · map 3.0 m". */
@Composable
private fun StepLine(live: LiveInfo) {
    val detail = when {
        live.region != null -> rs(R.string.detail_region, live.region)
        live.detailLevel <= 0 -> rs(R.string.detail_world)
        else -> rs(R.string.detail_level, live.detailLevel)
    }
    Text(text = rs(R.string.step_line, grouped(live.stepKm), detail, metres(live.sizeM)), color = ink2, style = AtlasType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Turn / zoom keys with words (B19: no bare icons) for hand users (design `panels.board.layouts.explore`). Words only:
 * a quarter of the board leaves no room for an icon beside "Turn right" (D13, sized for English).
 */
@Composable
private fun MapOps(actions: MapActions) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextKey(rs(R.string.rotate_left), "rotate_l", modifier = Modifier.weight(1f)) { actions.rotate(-1) }
        TextKey(rs(R.string.rotate_right), "rotate_r", modifier = Modifier.weight(1f)) { actions.rotate(1) }
        TextKey(rs(R.string.zoom_out), "zoom_out", modifier = Modifier.weight(1f)) { actions.zoom(-1) }
        TextKey(rs(R.string.zoom_in), "zoom_in", modifier = Modifier.weight(1f)) { actions.zoom(1) }
    }
}

/** The controller hint and "Bring to me" on one row. */
@Composable
private fun HintRow(text: String, icon: Int, actions: MapActions) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.weight(1f)) { HintLine(text, icon) }
        TextKey(rs(R.string.recenter), "recenter", icon = R.drawable.ic_recenter) { actions.recenter() }
    }
}

@Composable
private fun HintLine(text: String, icon: Int = R.drawable.ic_stick) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink2)
        Text(text = text, color = ink2, style = AtlasType.hint, maxLines = 2)
    }
}

/** A thin status strip + white text (D13: colour never carries the text). */
@Composable
private fun StatusLine(text: String, color: Color?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (color != null) Box(modifier = Modifier.size(width = 6.dp, height = 28.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(text = text, color = ink, style = PicoTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ picker

@Composable
private fun ColumnScope.Picker(state: AtlasUiState, atlas: Atlas, onEvent: (AtlasEvent) -> Unit) {
    val zh = isZh()
    TopBar(left = { Title(rs(R.string.app_name)) }, right = {
        MuteKey(state, onEvent)
        MoreKey(state, onEvent)
    })
    SegmentControl(modifier = Modifier.fillMaxWidth().height(48.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        val w = ((PanelSizes.BOARD_W - 48f - 8f) / 2f).dp
        for (m in Mode.entries) {
            SegmentItem(
                selected = state.mode == m,
                onClick = { onEvent(AtlasEvent.ModeSelected(m)) },
                modifier = Modifier.width(w).fillMaxHeight().probe("mode_${m.tag}"),
                textStyle = PicoTheme.typography.labelLarge,
                colors = SegmentControlDefaults.colors(itemBackgroundColor = Color.Transparent, itemContentColor = ink, selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary, selectedItemContentColor = ink),
                title = { Text(text = rs(if (m == Mode.EXPLORE) R.string.mode_explore else R.string.mode_quiz), color = ink, maxLines = 1) },
            )
        }
    }
    Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (set in atlas.sets) {
            val best = state.settings.bests[set.id]
            Button(
                onClick = { onEvent(AtlasEvent.SetClicked(set.id)) },
                modifier = Modifier.fillMaxWidth().height(64.dp).probe("set_${set.level}"),
                size = ButtonDefaults.buttonSize(width = (PanelSizes.BOARD_W - 48f).dp, height = 64.dp),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(PicoTheme.colorScheme.fillSecondary), contentAlignment = Alignment.Center) {
                        Text(text = set.level.toString(), color = ink, style = AtlasType.number)
                    }
                    Text(text = rs(R.string.set_level, set.level, set.name(zh)), color = ink, style = PicoTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = rs(R.string.set_count, set.targets.size), color = ink2, style = AtlasType.label, maxLines = 1)
                        Text(text = if (best == null) rs(R.string.set_no_best) else rs(R.string.set_best, best.percent, clock(best.ms)), color = ink2, style = AtlasType.label, maxLines = 1)
                    }
                }
            }
        }
    }
    Text(text = rs(R.string.not_standard), color = ink2, style = PicoTheme.typography.labelSmall, maxLines = 2)
}

// ------------------------------------------------------------------ explore / measuring

@Composable
private fun ColumnScope.Explore(state: AtlasUiState, live: LiveInfo, atlas: Atlas, actions: MapActions, onEvent: (AtlasEvent) -> Unit) {
    val zh = isZh()
    val set = state.setId?.let { atlas.set(it) }
    TopBar(left = {
        BackKey(onEvent)
        Title(set?.name(zh) ?: "")
    }, right = {
        MuteKey(state, onEvent)
        MoreKey(state, onEvent)
    })
    StepLine(live)
    Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        if (state.measuring) {
            Text(text = live.measureFrom?.let { rs(R.string.measure_from, it) } ?: rs(R.string.measure_from_pin), color = ink2, style = AtlasType.label, maxLines = 1)
            Text(text = rs(R.string.measure_km, grouped(live.measureKm)), color = ink, style = PicoTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum, lnum"), maxLines = 1)
            val steps = live.measureMapM / Scale.STEP_M
            Text(text = rs(R.string.measure_map, String.format(java.util.Locale.US, "%.2f", live.measureMapM), String.format(java.util.Locale.US, "%.1f", steps)), color = ink2, style = AtlasType.label, maxLines = 1)
            if (live.measureTo != null) Text(text = live.measureTo, color = ink, style = PicoTheme.typography.titleMedium, maxLines = 1) else Text(text = rs(R.string.measure_hint), color = ink2, style = AtlasType.hint, maxLines = 1)
        } else {
            val t = state.selected?.let { set?.target(it) }
            when {
                t != null -> {
                    Text(text = t.name(zh), color = ink, style = PicoTheme.typography.headlineMedium, maxLines = 1)
                    Text(text = t.name(!zh), color = ink2, style = AtlasType.label, maxLines = 1)
                    Text(text = t.fact(zh), color = ink, style = AtlasType.body, maxLines = 2)
                }
                live.offMap -> StatusLine(rs(R.string.off_map), null)
                live.sizeM > 50f && live.region == null -> Text(text = rs(R.string.detail_global), color = ink, style = AtlasType.body, maxLines = 2)
                else -> Text(text = rs(R.string.explore_hint), color = ink, style = AtlasType.body, maxLines = 2)
            }
        }
    }
    MapOps(actions)
    HintRow(rs(R.string.stick_hint), R.drawable.ic_stick, actions)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.measuring) {
            TextKey(rs(R.string.measure_again), "measure_again", modifier = Modifier.weight(1f), icon = R.drawable.ic_measure, primary = true) { onEvent(AtlasEvent.MeasureClicked) }
            TextKey(rs(R.string.measure_done), "measure_done", modifier = Modifier.weight(1f), icon = R.drawable.ic_check) { onEvent(AtlasEvent.MeasureDone) }
        } else {
            TextKey(rs(R.string.measure), "measure", modifier = Modifier.weight(1f), icon = R.drawable.ic_measure) { onEvent(AtlasEvent.MeasureClicked) }
            TextKey(rs(R.string.start_quiz), "start_quiz", modifier = Modifier.weight(1.4f), icon = R.drawable.ic_play, primary = true) { onEvent(AtlasEvent.StartQuizClicked) }
        }
    }
}

// ------------------------------------------------------------------ quiz

@Composable
private fun ColumnScope.QuizReady(state: AtlasUiState, live: LiveInfo, atlas: Atlas, onEvent: (AtlasEvent) -> Unit) {
    val zh = isZh()
    val set = state.setId?.let { atlas.set(it) } ?: return
    TopBar(left = {
        BackKey(onEvent)
        Title(set.name(zh))
    }, right = {
        MuteKey(state, onEvent)
        MoreKey(state, onEvent)
    })
    Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Text(text = rs(R.string.ready_title, set.level, set.targets.size), color = ink, style = PicoTheme.typography.headlineSmall, maxLines = 1)
        TextKey(rs(R.string.ready_mode, rs(if (state.answerMode == AnswerMode.DRAG) R.string.answer_drag else R.string.answer_point)), "ready_mode", icon = if (state.answerMode == AnswerMode.DRAG) R.drawable.ic_drag else R.drawable.ic_point) { onEvent(AtlasEvent.MoreClicked) }
        val best = state.settings.bests[set.id]
        Text(text = if (best == null) rs(R.string.set_no_best) else rs(R.string.set_best, best.percent, clock(best.ms)), color = ink2, style = AtlasType.label, maxLines = 1)
        if (live.sizeM < set.minSizeM - 0.01f) Text(text = rs(R.string.ready_zoom, metres(set.minSizeM)), color = ink2, style = AtlasType.hint, maxLines = 1)
    }
    TextKey(rs(R.string.play), "play", modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_play, primary = true) { onEvent(AtlasEvent.PlayClicked) }
}

/** ●●○: chances left. */
@Composable
internal fun Chances(left: Int, size: Int = 14) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { k ->
            Box(modifier = Modifier.size(size.dp).clip(CircleShape).background(if (k < left) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.fillSecondary))
        }
    }
}

@Composable
private fun ColumnScope.Asking(state: AtlasUiState, live: LiveInfo, atlas: Atlas, actions: MapActions, onEvent: (AtlasEvent) -> Unit) {
    val zh = isZh()
    val q = state.quiz ?: return
    val set = atlas.set(q.setId) ?: return
    val f = state.feedback
    // while "Correct" shows, the board still names the question just answered
    val shownId = if (f?.kind == FeedbackKind.RIGHT) f.targetId else q.current
    val t = shownId?.let { set.target(it) }
    val number = if (f?.kind == FeedbackKind.RIGHT) q.index else q.index + 1
    TopBar(left = {
        Text(text = if (q.review) rs(R.string.review_progress, number.coerceAtMost(q.total), q.total) else rs(R.string.ask_progress, number.coerceAtMost(q.total), q.total), color = ink, style = AtlasType.label, maxLines = 1)
        Text(text = clock(q.elapsedMs), color = ink, style = AtlasType.number, maxLines = 1)
    }, right = {
        MuteKey(state, onEvent)
        IconKey(R.drawable.ic_pause, rs(R.string.action_pause), "pause") { onEvent(AtlasEvent.PauseClicked) }
    })
    Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = rs(R.string.ask_go), color = ink2, style = PicoTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.padding(bottom = 8.dp))
            val name = t?.name(zh) ?: ""
            Text(text = name, color = ink, style = if (name.length > 8) PicoTheme.typography.headlineLarge else PicoTheme.typography.displayMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text = t?.name(!zh) ?: "", color = ink2, style = AtlasType.label, maxLines = 1)
            Chances(if (f?.kind == FeedbackKind.RIGHT) 3 - 0 else q.chancesLeft)
        }
        val wrongName = f?.takeIf { it.kind == FeedbackKind.WRONG }?.let { set.target(it.targetId)?.name(zh) }
        when {
            f?.kind == FeedbackKind.RIGHT -> StatusLine(rs(R.string.right), AtlasColors.Right)
            q.revealing -> StatusLine(rs(R.string.reveal, t?.name(zh) ?: ""), AtlasColors.Reveal)
            wrongName != null -> StatusLine(rs(R.string.wrong_here, wrongName), AtlasColors.Wrong)
            live.offMap -> StatusLine(rs(R.string.off_map), null)
            live.nowhere && live.onTarget == null -> StatusLine(rs(R.string.nowhere), null)
            else -> Spacer(modifier = Modifier.height(28.dp))
        }
    }
    MapOps(actions)
    HintRow(if (q.revealing) rs(R.string.reveal_hint) else rs(if (state.answerMode == AnswerMode.DRAG) R.string.ask_hint_drag else R.string.ask_hint_point), if (state.answerMode == AnswerMode.DRAG) R.drawable.ic_stick else R.drawable.ic_point, actions)
}

@Composable
private fun ColumnScope.Paused(state: AtlasUiState, atlas: Atlas, onEvent: (AtlasEvent) -> Unit) {
    val zh = isZh()
    val q = state.quiz ?: return
    val set = atlas.set(q.setId) ?: return
    TopBar(left = {
        BackKey(onEvent)
        Title(set.name(zh))
    }, right = { MoreKey(state, onEvent) })
    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
        Text(text = rs(R.string.paused_line, rs(R.string.ask_progress, (q.index + 1).coerceAtMost(q.total), q.total), clock(q.elapsedMs)), color = ink, style = PicoTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum, lnum"), maxLines = 1)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextKey(rs(R.string.resume), "resume", modifier = Modifier.weight(1.3f), icon = R.drawable.ic_play, primary = true, enabled = !state.trackingLost) { onEvent(AtlasEvent.ResumeClicked) }
        TextKey(rs(R.string.restart), "restart", modifier = Modifier.weight(1f), icon = R.drawable.ic_restart) { onEvent(AtlasEvent.RestartClicked) }
        TextKey(rs(R.string.change_set), "change_set", modifier = Modifier.weight(1f), icon = R.drawable.ic_back) { onEvent(AtlasEvent.ChangeSetClicked) }
    }
}

@Composable
private fun ColumnScope.Result(state: AtlasUiState, atlas: Atlas, onEvent: (AtlasEvent) -> Unit) {
    val zh = isZh()
    val q = state.quiz ?: return
    val set = atlas.set(q.setId) ?: return
    TopBar(left = {
        BackKey(onEvent)
        Title(set.name(zh))
    }, right = {
        MuteKey(state, onEvent)
        MoreKey(state, onEvent)
    })
    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(modifier = Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "${q.percent}%", color = ink, style = PicoTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum, lnum"), maxLines = 1)
            Text(text = clock(q.elapsedMs), color = ink, style = PicoTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum, lnum"), maxLines = 1)
            if (state.newBest) {
                Badge(badgeColor = BadgeDefaults.badgeColors(backgroundColor = PicoTheme.colorScheme.interaction, contentColor = AtlasColors.Ink), badgeSize = BadgeDefaults.Regular) {
                    Text(text = rs(R.string.new_best), color = AtlasColors.Ink, style = AtlasType.label, maxLines = 1)
                }
            }
            if (q.review) Text(text = rs(R.string.review_no_best), color = ink2, style = AtlasType.hint, maxLines = 2)
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val misses = q.misses
            Text(text = if (misses.isEmpty()) rs(R.string.all_right) else rs(R.string.misses_title), color = ink2, style = AtlasType.label, maxLines = 1)
            for (id in misses) {
                val t = set.target(id) ?: continue
                val how = when (q.outcomes[id]) { Outcome.SECOND -> rs(R.string.outcome_second); Outcome.THIRD -> rs(R.string.outcome_third); else -> rs(R.string.outcome_revealed) }
                TextKey("${t.name(zh)} · $how", "miss_$id", modifier = Modifier.fillMaxWidth()) { onEvent(AtlasEvent.MissClicked(id)) }
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val n = q.misses.size
        if (n > 0) TextKey(rs(R.string.review, n), "review", modifier = Modifier.weight(1.4f), icon = R.drawable.ic_restart, primary = true) { onEvent(AtlasEvent.ReviewClicked) }
        TextKey(rs(R.string.again), "again", modifier = Modifier.weight(1f), icon = R.drawable.ic_play, primary = n == 0) { onEvent(AtlasEvent.AgainClicked) }
        TextKey(rs(R.string.change_set), "change_set", modifier = Modifier.weight(1f), icon = R.drawable.ic_back) { onEvent(AtlasEvent.ChangeSetClicked) }
    }
}

@Composable
private fun ColumnScope.Blocked(state: AtlasUiState, onEvent: (AtlasEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painter = painterResource(R.drawable.ic_unavailable), contentDescription = null, modifier = Modifier.size(36.dp), tint = ink)
        Text(text = rs(R.string.blocked_title), color = ink, style = PicoTheme.typography.headlineSmall, maxLines = 2)
    }
    Text(text = rs(if (state.blockedReason == "data") R.string.blocked_data else R.string.blocked_body), color = ink, style = AtlasType.body)
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        TextKey(rs(R.string.action_exit), "blocked_exit", icon = R.drawable.ic_exit) { onEvent(AtlasEvent.BlockedExit) }
        TextKey(rs(R.string.action_retry), "blocked_retry", icon = R.drawable.ic_retry, primary = true) { onEvent(AtlasEvent.BlockedRetry) }
    }
}
