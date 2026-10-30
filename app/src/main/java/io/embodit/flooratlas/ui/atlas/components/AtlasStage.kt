package io.embodit.flooratlas.ui.atlas.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialScaleGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.flooratlas.domain.Atlas
import io.embodit.flooratlas.platform.AtlasLog
import io.embodit.flooratlas.ui.atlas.AtlasEvent
import io.embodit.flooratlas.ui.atlas.AtlasUiState
import io.embodit.flooratlas.ui.atlas.debug.LocalProbePanel
import io.embodit.flooratlas.ui.atlas.debug.Probe
import io.embodit.flooratlas.ui.atlas.scene.AtlasScene
import io.embodit.flooratlas.ui.atlas.scene.PanelIds

/**
 * The Stage content: one full-size SpatialView with the map and the panel pool. Every attachment panel is
 * declared once with a fixed size and shown or not by [AtlasScene]. Tap, drag and two-hand scale recognisers
 * live in separate pointerInput blocks (Spatial Ruler).
 *
 * Window model: single panel with popups (design `windowModel`): the board is resident; the question tag,
 * labels, plates, menu, snackbar and coach come and go.
 */
@Composable
internal fun AtlasStage(state: AtlasUiState, atlas: Atlas, scene: AtlasScene, voice: Boolean, onEvent: (AtlasEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    val actions = MapActions(rotate = scene::rotateStep, zoom = scene::zoomStep, recenter = scene::recenter)
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                }
                .pointerInput(Unit) {
                    detectSpatialScaleGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onScaleStart = { value -> scene.onSpatialScale("start", value) },
                        onScaleEnd = { value -> scene.onSpatialScale("end", value) },
                        onScaleCancel = { value -> scene.onSpatialScale("cancel", value) },
                    ) { value -> scene.onSpatialScale("move", value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.BOARD) {
                PoolPanel(PanelIds.BOARD, scene, pressed = { scene.onBoardPointer() }) { inView -> BoardPanel(state = if (inView) state else null, live = scene.live.value, atlas = atlas, actions = actions, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.TAG) {
                PoolPanel(PanelIds.TAG, scene) { inView -> QuestionTag(state = if (inView) state else null, live = scene.live.value, atlas = atlas) }
            }
            AttachmentPanel(PanelIds.MENU) {
                PoolPanel(PanelIds.MENU, scene, pressed = { scene.onBoardPointer() }) { inView -> MoreMenu(state = if (inView) state else null, voice = voice, onEvent = onEvent, onRecenter = scene::recenter) }
            }
            AttachmentPanel(PanelIds.SNACK) {
                PoolPanel(PanelIds.SNACK, scene) { inView -> SnackPanel(snack = if (inView) state.snack else null) }
            }
            AttachmentPanel(PanelIds.COACH) {
                PoolPanel(PanelIds.COACH, scene) { inView -> CoachPanel(coach = if (inView) state.coach else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.MEASURE) {
                PoolPanel(PanelIds.MEASURE, scene) { inView -> MeasureTag(label = if (inView) scene.labels[PanelIds.MEASURE] else null) }
            }
            for (i in 0 until PanelIds.LABELS) {
                AttachmentPanel(PanelIds.label(i)) {
                    PoolPanel(PanelIds.label(i), scene) { inView -> NameLabel(label = if (inView) scene.labels[PanelIds.label(i)] else null) }
                }
            }
            for (i in 0 until PanelIds.LANDFORMS) {
                AttachmentPanel(PanelIds.landform(i)) {
                    PoolPanel(PanelIds.landform(i), scene) { inView -> LandformPlate(label = if (inView) scene.labels[PanelIds.landform(i)] else null) }
                }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel: [content] gets `inView` (the panel has been moved into view; a pooled panel
 * draws only then, Spatial Ruler finding 3). Debug builds log every press as `PTR panel=<id> ev=`.
 */
@Composable
private fun PoolPanel(id: String, scene: AtlasScene, pressed: (() -> Unit)? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val logged = Probe.enabled
    val watcher =
        if (logged || pressed != null) {
            Modifier.pointerInput(id, logged) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) continue
                        if (event.type == PointerEventType.Press) pressed?.invoke()
                        if (logged) {
                            val p = event.changes.firstOrNull()?.position
                            AtlasLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                        }
                    }
                }
            }
        } else Modifier
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
