package io.embodit.flooratlas.ui.atlas.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialScaleValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.flooratlas.domain.AnswerMode
import io.embodit.flooratlas.domain.Atlas
import io.embodit.flooratlas.domain.DragLine
import io.embodit.flooratlas.domain.DwellJudge
import io.embodit.flooratlas.domain.HeadPose
import io.embodit.flooratlas.domain.Judge
import io.embodit.flooratlas.domain.Kind
import io.embodit.flooratlas.domain.MapGeo
import io.embodit.flooratlas.domain.MapPose
import io.embodit.flooratlas.domain.MapPt
import io.embodit.flooratlas.domain.Outcome
import io.embodit.flooratlas.domain.P2
import io.embodit.flooratlas.domain.Quat4
import io.embodit.flooratlas.domain.QuizSet
import io.embodit.flooratlas.domain.Relief
import io.embodit.flooratlas.domain.Scale
import io.embodit.flooratlas.domain.Target
import io.embodit.flooratlas.domain.TileSelect
import io.embodit.flooratlas.domain.Vec3
import io.embodit.flooratlas.domain.wrapAngle
import io.embodit.flooratlas.platform.AtlasGraph
import io.embodit.flooratlas.platform.AtlasLog
import io.embodit.flooratlas.platform.TileStore
import io.embodit.flooratlas.ui.atlas.AtlasEvent
import io.embodit.flooratlas.ui.atlas.AtlasUiState
import io.embodit.flooratlas.ui.atlas.FeedbackKind
import io.embodit.flooratlas.ui.atlas.LiveInfo
import io.embodit.flooratlas.ui.atlas.Phase
import io.embodit.flooratlas.ui.atlas.SnackKind
import io.embodit.flooratlas.ui.atlas.components.PanelSizes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal object ActiveScene {
    @Volatile var scene: AtlasScene? = null
}

/** The one per-frame ECS system of the app (`registerSystem` in AtlasScreen). */
class AtlasFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Panel ids of the pool. */
object PanelIds {
    const val BOARD = "board"
    const val TAG = "questionTag"
    const val MENU = "moreMenu"
    const val SNACK = "snack"
    const val COACH = "coach"
    const val MEASURE = "measure"
    const val LABELS = 2
    const val LANDFORMS = 6

    fun label(i: Int) = "label$i"

    fun landform(i: Int) = "landform$i"

    val pool: List<String> = listOf(BOARD, TAG, MENU, SNACK, COACH, MEASURE) + (0 until LABELS).map { label(it) } + (0 until LANDFORMS).map { landform(it) }
}

enum class LabelTone { PLAIN, RIGHT, WRONG, REVEAL }

/** A name label / measure tag / landform plate (Compose state written by the scene on change). */
data class LabelUi(val title: String, val subtitle: String?, val tone: LabelTone = LabelTone.PLAIN, val targetId: String? = null)

/**
 * The 3D side: the map (terrain tiles, outlines, border) below a map root moved by drag / stick / two-hand
 * scale, the crosshair at your feet, the answer rings, the measuring line, and every panel's place. All motion is
 * written here per frame (design `container.notes`: ECS, not Compose); panels get [live] at 10 Hz on change.
 * Main thread only.
 */
class AtlasScene(private val store: TileStore, private val sink: (AtlasEvent) -> Unit, private val zh: () -> Boolean) {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val labels = mutableStateMapOf<String, LabelUi>()
    val live = mutableStateOf(LiveInfo())
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val head = AtlasGraph.head
    private val stick = AtlasGraph.stick
    private val planes = AtlasGraph.planes
    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var materials: AtlasMaterials? = null
    private var meshes: AtlasMeshes? = null
    private var attached = false
    private var scope: CoroutineScope? = null
    private var buildJob: Job? = null
    private var paused = false
    private var built = false

    // map entities
    private var mapRoot: Entity? = null
    private var reliefNode: Entity? = null
    private var overlayNode: Entity? = null
    private var tiles: TileLayer? = null
    private var mapHit: Entity? = null
    private var hitSize = 0f
    private val border = ArrayList<Strip>()

    // crosshair
    private var crossRoot: Entity? = null
    private val segs = ArrayList<Entity>()
    private var segLit = -1
    private var flashRing: Entity? = null
    private var revealRing: Entity? = null
    private var pin: Entity? = null
    private var measureLine: Strip? = null

    private var pose: HeadPose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var ui = AtlasUiState()
    private var atlas: Atlas = Atlas.EMPTY
    private var frames = 0L
    private var lastLogMs = 0L
    private var lastFrameMs = -1L
    private var attachMs = 0L

    // ---- map state
    private var map: MapPose? = null
    private var cursor: P2? = null
    private var cursorYaw = 0f
    private var reliefV = 0f
    private var floorEstimateY: Float? = null
    private var estimating = false
    private var notInFullSpaceSinceMs = -1L

    // ---- motion
    private class Drag(val press: Vec3, val grab: MapPt) {
        var begun = false
        var totalPx = Vec3.ZERO
        var hand = Vec3.ZERO
        var prevHand = Vec3.ZERO
        var dir: Vec3? = null
        var equivalent = 1f
        var hit: P2? = null
        var moves = 0
    }

    private var drag: Drag? = null
    private var lastDragEndMs = -1L
    private var scaleActive = false
    private var pendingScale = 1f
    private var lastScaleMs = 0L

    private class Anim(val from: MapPose, val to: MapPose, val startMs: Long, val ms: Long)

    private var anim: Anim? = null
    private var wasMoving = false
    private var limitShownMs = 0L

    // ---- judging
    private val dwell = DwellJudge()
    private var dwellQuestion: String? = null
    private var dwellIndex = -1
    private var dwellPhase: Phase? = null
    private var exploreOn: String? = null
    private var exploreSinceMs = -1L
    private var selectedAt: MapPt? = null
    private var leftSelectedMs = -1L
    private var measureFrom: MapPt? = null
    private var measureFromName: String? = null
    private var lastLiveMs = 0L

    // ---- feedback
    private var feedbackSeen: Long = -1L
    private var flashStartMs = -1L
    private var flashColor: FeedbackKind? = null
    private var flashAt: P2? = null

    // ---- outlines
    private inner class Outline(val target: Target) {
        var entity: Entity? = null
        var mesh: MeshResource? = null
        var builtS = 0f
        var material: com.pico.spatial.core.ecs.resource.Material? = null
        var on = false
        var interactive = false
        var sheetH = 0f
    }

    /** Height (floor metres) of the highest highlight sheet drawn now: the crosshair stays above it. */
    private var sheetLiftM = 0f

    private val outlines = LinkedHashMap<String, Outline>()
    private var outlineSet: String? = null

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        this.content = content
        attachMs = SystemClock.elapsedRealtime()
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity()
        runCatching { probe.setName("contentProbe") }
        content.addEntity(probe)
        contentProbe = probe
        // an entity added to the SpatialView is not at the Stage origin: move the root once so its space is Stage space
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.BOARD, PanelSizes.BOARD_W, PanelSizes.BOARD_H, 1f)
        // the question tag leans back 55 deg, name labels 30 deg, landform plates stand upright: yaw only (design panels, terrain.labels)
        rig.world(PanelIds.TAG, PanelSizes.TAG_W, PanelSizes.TAG_H, 1f).fixedPitch = Math.toRadians(55.0).toFloat()
        rig.world(PanelIds.MENU, PanelSizes.MENU_W, PanelSizes.MENU_H, 1f)
        rig.world(PanelIds.SNACK, PanelSizes.SNACK_W, PanelSizes.SNACK_H, 1f)
        rig.world(PanelIds.COACH, PanelSizes.COACH_W, PanelSizes.COACH_H, 1f)
        rig.world(PanelIds.MEASURE, PanelSizes.MEASURE_W, PanelSizes.MEASURE_H, 1f)
        for (i in 0 until PanelIds.LABELS) rig.world(PanelIds.label(i), PanelSizes.LABEL_W, PanelSizes.LABEL_H, 1f).fixedPitch = Math.toRadians(30.0).toFloat()
        for (i in 0 until PanelIds.LANDFORMS) rig.world(PanelIds.landform(i), PanelSizes.LANDFORM_W, PanelSizes.LANDFORM_H, 1f).fixedPitch = 0f
        head.start()
        stick.start()
        planes.start { }
        ActiveScene.scene = this
        attached = true
        bindPanels(attachments)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        buildJob = scope?.launch {
            delay(300)
            build()
        }
        AtlasLog.i("SCENE attached")
    }

    /** Shared materials and meshes, the map nodes, the crosshair. Tiles and outlines come later, one per frame. */
    private suspend fun build() {
        val root = stageRoot ?: return
        val t0 = SystemClock.elapsedRealtime()
        try {
            val sh = SharedResources()
            shared = sh
            val m = AtlasMaterials(sh)
            materials = m
            yield()
            val ms = AtlasMeshes(sh)
            ms.build { delay(16) }
            meshes = ms
            val mr = Entity()
            runCatching { mr.setName("mapRoot") }
            root.addChild(mr)
            mapRoot = mr
            val rn = Entity()
            runCatching { rn.setName("relief") }
            mr.addChild(rn)
            reliefNode = rn
            val on = Entity()
            runCatching { on.setName("overlays") }
            mr.addChild(on)
            overlayNode = on
            mr.enabled = false
            tiles = TileLayer(rn, m, store)
            repeat(4) { border += Strip(root, ms.band, m.border, "border$it") }
            val hit = Entity()
            runCatching { hit.setName("mapHit") }
            root.addChild(hit)
            hit.components.set(InteractableComponent())
            mapHit = hit
            // crosshair (design entities3d.crosshair): white ring, yellow dot, 12 progress segments, answer flash
            val cr = Entity()
            runCatching { cr.setName("crosshair") }
            root.addChild(cr)
            cr.addChild(ModelEntity(ms.ring, m.white))
            cr.addChild(ModelEntity(ms.dot, m.yellow).also { it.setLocal(position = Vec3(0f, 0.001f, 0f)) })
            for (k in 0 until 12) segs += ModelEntity(ms.segments[k], m.dim).also { cr.addChild(it) }
            val fr = ModelEntity(ms.flash, m.green)
            fr.enabled = false
            cr.addChild(fr)
            flashRing = fr
            cr.enabled = false
            crossRoot = cr
            val rv = ModelEntity(ms.flash, m.orange)
            rv.enabled = false
            root.addChild(rv)
            revealRing = rv
            val p = ModelEntity(ms.pin, m.white)
            p.enabled = false
            root.addChild(p)
            pin = p
            measureLine = Strip(root, ms.dashes, m.yellow, "measureLine")
            built = true
            AtlasLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
            sink(AtlasEvent.SceneReady)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            AtlasLog.e("SCENE build failed: $t", t)
        }
    }

    fun pause() {
        if (paused || !attached) return
        paused = true
        planes.stop()
        head.stop()
        stick.stop()
        drag = null
        scaleActive = false
        AtlasLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        stick.start()
        planes.start { }
        AtlasLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        planes.stop()
        head.stop()
        stick.stop()
        panelShown.clear()
        labels.clear()
        runCatching { tiles?.clear() }
        tiles = null
        clearOutlines()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        materials = null
        meshes = null
        stageRoot = null
        contentProbe = null
        mapRoot = null
        reliefNode = null
        overlayNode = null
        mapHit = null
        crossRoot = null
        segs.clear()
        border.clear()
        rig = null
        content = null
        built = false
        hasHead = false
        map = null
        cursor = null
        drag = null
        anim = null
        AtlasLog.i("SCENE detached")
    }

    // =========================================================================================
    // state
    // =========================================================================================

    fun setAtlas(a: Atlas) {
        atlas = a
    }

    fun render(state: AtlasUiState) {
        val before = ui
        ui = state
        // a quiz starts: zoom up to the set's minimum about the crosshair (judging.autoZoom)
        if (state.phase == Phase.ASKING && before.phase != Phase.ASKING && before.phase != Phase.PAUSED) autoZoom()
        if (state.measuring && !before.measuring) startMeasure()
        if (!state.measuring && before.measuring) measureFrom = null
        if (state.settings.floorAdjustCm != before.settings.floorAdjustCm) AtlasLog.i("FLOOR adjust cm=${state.settings.floorAdjustCm}")
        val f = state.feedback
        if (f != null && f.sinceMs != feedbackSeen) {
            feedbackSeen = f.sinceMs
            flashStartMs = SystemClock.elapsedRealtime()
            flashColor = f.kind
            flashAt = cursor
            if (f.kind == FeedbackKind.WRONG) f.at?.let { dwell.wrongAt(f.targetId, it) }
        }
        val rig = rig ?: return
        rig[PanelIds.BOARD]?.show()
        if (!state.menuOpen) rig[PanelIds.MENU]?.hide()
        if (state.snack == null) rig[PanelIds.SNACK]?.hide()
        if (state.coach == null) rig[PanelIds.COACH]?.hide()
    }

    private fun currentSet(): QuizSet? = ui.setId?.let { atlas.set(it) }

    private fun quizSized(): Boolean = ui.phase in setOf(Phase.ASKING, Phase.PAUSED)

    private fun minSize(): Float = if (quizSized()) currentSet()?.minSizeM ?: MapGeo.MIN_SIZE_M else MapGeo.MIN_SIZE_M

    private fun autoZoom() {
        val m = map ?: return
        val c = cursor ?: return
        val min = currentSet()?.minSizeM ?: return
        if (m.sizeM < min) {
            anim = Anim(m, m.zoomTo(c, min, min), SystemClock.elapsedRealtime(), AUTOZOOM_MS)
            AtlasLog.i(String.format(Locale.US, "MAP autozoom %.2f -> %.2f", m.sizeM, min))
        }
    }

    private fun startMeasure() {
        val m = map ?: return
        val c = cursor ?: return
        val at = m.toMap(c)
        measureFrom = at
        measureFromName = currentSet()?.let { Judge.pick(it.targets, at, m.s).on }?.name(zh())
        AtlasLog.i(String.format(Locale.US, "MEASURE pin at=(%.2f, %.2f) name=%s", at.x, at.lat, measureFromName))
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs < 0) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameMs = now
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        checkBlocked(now)
        if (built && hasHead) {
            trackFloor(now)
            if (map == null && atlas.sets.isNotEmpty()) lay(ui.settings.lastSizeM, "start")
            val m0 = map
            if (m0 != null) {
                val moving = move(now, dt)
                drawMap(now)
                judge(now, moving)
                drawCrosshair(now)
                drawOutlines(now)
                drawMeasure()
                if (now - lastLiveMs >= LIVE_MS) {
                    lastLiveMs = now
                    publishLive(moving)
                }
            }
        }
        if (hasHead) placePanels(now)
        rig.faceViewer(pose.position)
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            val m = map
            AtlasLog.i(String.format(Locale.US, "FRAME n=%d head=%s map=%s size=%.2f yaw=%.1f relief=%.6f floor=%s est=%s stick=%s %s", frames, AtlasLog.pos(pose.position), m?.let { "(%.2f, %.2f)".format(Locale.US, it.ox, it.oz) }, m?.sizeM ?: 0f, Math.toDegrees((m?.yaw ?: 0f).toDouble()), reliefV, floorY()?.let { "%.3f".format(Locale.US, it) }, estimating, stick.status(), tiles?.line() ?: ""))
        }
    }

    private fun checkBlocked(now: Long) {
        val reason = head.blockReason()
        if (reason == "device-not-supported") {
            if (ui.phase != Phase.BLOCKED) sink(AtlasEvent.Blocked(reason))
            return
        }
        if (reason != null) {
            if (notInFullSpaceSinceMs < 0) notInFullSpaceSinceMs = now
            if (now - notInFullSpaceSinceMs > BLOCK_GRACE_MS && ui.phase != Phase.BLOCKED) sink(AtlasEvent.Blocked(reason))
        } else {
            notInFullSpaceSinceMs = -1L
        }
        val lost = head.lost(now)
        if (lost != ui.trackingLost) sink(AtlasEvent.TrackingChanged(lost))
    }

    /** D7: the largest FLOOR plane, else (after 5 s) head height - 1.55 m plus the menu's fine adjustment. */
    private fun trackFloor(now: Long) {
        if (floorEstimateY == null) floorEstimateY = pose.position.y - EST_BELOW_HEAD_M
        val real = planes.floorY
        val est = real == null && now - attachMs > FLOOR_TIMEOUT_MS
        if (est != estimating) {
            estimating = est
            sink(AtlasEvent.FloorChanged(est))
            AtlasLog.i("FLOOR estimate=$est real=$real")
        }
    }

    private fun floorY(): Float? = planes.floorY ?: floorEstimateY?.let { it + ui.settings.floorAdjustCm / 100f }

    /** Lays the map, the crosshair and the board in front of the viewer now (design maps[0].lay, judging.cursor, D9). */
    fun lay(sizeM: Float, why: String) {
        if (!hasHead) return
        val f = pose.level()
        val h = P2.of(pose.position)
        map = MapPose.lay(h, f, sizeM.coerceIn(MapGeo.MIN_SIZE_M, MapGeo.MAX_SIZE_M))
        cursor = P2(h.x + f.x * CURSOR_AHEAD_M, h.z + f.z * CURSOR_AHEAD_M)
        cursorYaw = atan2(-f.x, -f.z)
        anim = null
        dwell.reset(null, null)
        boardAt = null
        AtlasLog.i(String.format(Locale.US, "MAP lay why=%s size=%.2f cursor=(%.3f, %.3f) yaw=%.1f", why, sizeM, cursor!!.x, cursor!!.z, Math.toDegrees(cursorYaw.toDouble())))
    }

    /** Drag, stick, two-hand scale, animations; returns whether the map is being moved. */
    private fun move(now: Long, dt: Float): Boolean {
        var m = map ?: return false
        val c = cursor ?: return false
        val movable = ui.mapMovable && !ui.frozen
        var moving = false
        val minM = minSize()
        anim?.let { a ->
            val t = ((now - a.startMs).toFloat() / a.ms).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)
            m = MapPose(a.from.ox + (a.to.ox - a.from.ox) * e, a.from.oz + (a.to.oz - a.from.oz) * e, a.from.yaw + wrapAngle(a.to.yaw - a.from.yaw) * e, a.from.s + (a.to.s - a.from.s) * e)
            if (t >= 1f) anim = null
        }
        if (movable) {
            // stick X turns about the crosshair, Y zooms (rules.gestures): dead zone 0.15, 90 deg/s, x2 per second
            val sx = deadZone(stick.x(now))
            val sy = deadZone(stick.y(now))
            if (sx != 0f) {
                m = m.rotateAbout(c, -Math.toRadians((sx * abs(sx) * STICK_DEG_S * dt).toDouble()).toFloat())
                moving = true
            }
            if (sy != 0f) {
                val k = 2f.pow(sy * abs(sy) * dt)
                m = zoomChecked(m, c, m.sizeM * k, minM, sy > 0f, now)
                moving = true
            }
            if (pendingScale != 1f) {
                m = zoomChecked(m, c, m.sizeM * pendingScale, minM, pendingScale > 1f, now)
                pendingScale = 1f
                moving = true
            }
            if (scaleActive && now - lastScaleMs < 300L) moving = true
            drag?.let { d ->
                if (d.begun) {
                    moving = true
                    d.hit?.let { m = m.bring(d.grab, it) }
                }
            }
        } else {
            pendingScale = 1f
        }
        if (m.sizeM < minM - 1e-3f && anim == null) m = m.zoomTo(c, minM, minM)
        map = m
        if (anim != null) moving = true
        if (wasMoving && !moving) onSettled(m)
        wasMoving = moving
        return moving
    }

    private fun deadZone(v: Float): Float = if (abs(v) < STICK_DEAD) 0f else (v - STICK_DEAD * kotlin.math.sign(v)) / (1f - STICK_DEAD)

    private fun zoomChecked(m: MapPose, c: P2, want: Float, minM: Float, up: Boolean, now: Long): MapPose {
        val hitLimit = (up && m.sizeM >= MapGeo.MAX_SIZE_M - 1e-3f) || (!up && m.sizeM <= minM + 1e-3f)
        if (hitLimit && now - limitShownMs > 1600L) {
            limitShownMs = now
            sink(AtlasEvent.Snack(if (up) SnackKind.ZOOM_MAX else SnackKind.ZOOM_MIN))
        }
        return m.zoomTo(c, want, minM)
    }

    /** The map came to rest: release snap (judging.snap), size remembered. */
    private fun onSettled(m: MapPose) {
        val c = cursor ?: return
        sink(AtlasEvent.SizeSettled(m.sizeM))
        if (ui.answerMode == AnswerMode.POINT && ui.phase == Phase.ASKING) return
        if (ui.phase != Phase.EXPLORE && ui.phase != Phase.ASKING) return
        val set = currentSet() ?: return
        val at = m.toMap(c)
        val to = Judge.snapTarget(set.targets, at, m.s) ?: return
        anim = Anim(m, m.bring(to, c), SystemClock.elapsedRealtime(), SNAP_MS)
        AtlasLog.i(String.format(Locale.US, "SNAP from=(%.2f, %.2f) to=(%.2f, %.2f)", at.x, at.lat, to.x, to.lat))
    }

    private fun quatYaw(yaw: Float) = Quat4.axisAngle(Vec3.UP, yaw)

    private fun drawMap(now: Long) {
        val m = map ?: return
        val fy = floorY() ?: return
        val mr = mapRoot ?: return
        mr.setLocal(position = Vec3(m.ox, fy, m.oz), rotation = quatYaw(m.yaw), scale = Vec3(m.s, 1f, m.s))
        if (!mr.enabled) mr.enabled = true
        val tl = tiles ?: return
        val viewer = m.toMap(P2.of(pose.position))
        tl.step(viewer, m.s)
        val quiz = ui.phase in setOf(Phase.ASKING, Phase.PAUSED, Phase.QUIZ_READY, Phase.RESULT)
        val target = Relief.scale(m.sizeM, tl.highestDrawn.toFloat(), quiz)
        reliefV = if (reliefV == 0f) target else reliefV + (target - reliefV) * 0.15f
        reliefNode?.setLocal(position = Vec3(0f, TILE_LIFT_M, 0f), scale = Vec3(1f, reliefV, 1f))
        overlayNode?.setLocal(position = Vec3(0f, OVERLAY_LIFT_M, 0f), scale = Vec3(1f, reliefV, 1f))
        // white border (palette rugBorder), 1 cm
        val corners = listOf(MapPt(0f, MapGeo.LAT_TOP), MapPt(MapGeo.WIDTH_DEG, MapGeo.LAT_TOP), MapPt(MapGeo.WIDTH_DEG, MapGeo.LAT_BOTTOM), MapPt(0f, MapGeo.LAT_BOTTOM)).map { m.toFloor(it).at(fy + 0.004f) }
        for (k in 0 until 4) border[k].draw(corners[k], corners[(k + 1) % 4], 0.012f, Vec3.UP)
        // the floor collider for system drags and taps; rebuilt when the size changed (not mid-drag)
        val hit = mapHit ?: return
        val center = m.toFloor(MapGeo.CENTER)
        hit.setLocal(position = center.at(fy), rotation = quatYaw(m.yaw))
        if (drag == null && abs(m.sizeM - hitSize) > hitSize * 0.03f) {
            hitSize = m.sizeM
            hit.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(m.sizeM, 0.02f, MapGeo.HEIGHT_DEG * m.s))), physicsMaterial = PhysicsMaterialResource()))
        }
    }

    /** Height of the terrain at a map point as it is drawn now (floor metres above the floor). */
    private fun liftAt(p: MapPt): Float = if (p.onMap) store.heightAt(p) * reliefV else 0f

    // =========================================================================================
    // judging
    // =========================================================================================

    private fun judge(now: Long, moving: Boolean) {
        val m = map ?: return
        val c = cursor ?: return
        val set = currentSet() ?: return
        val at = m.toMap(c)
        val pick = if (at.onMap) Judge.pick(set.targets, at, m.s) else null
        val on = pick?.on
        when (ui.phase) {
            Phase.ASKING -> {
                val q = ui.quiz ?: return
                if (dwellQuestion != q.current || dwellIndex != q.index || dwellPhase != ui.phase) {
                    dwellQuestion = q.current
                    dwellIndex = q.index
                    dwell.reset(at, on?.id)
                    if (q.revealing) dwell.arm()
                }
                if (q.revealing) dwell.arm()
                if (ui.frozen || ui.answerMode == AnswerMode.POINT || ui.feedback?.kind == FeedbackKind.RIGHT) return
                val fire = dwell.step(now, at, m.s, on?.id, moving)
                if (fire != null) sink(AtlasEvent.Answered(fire, at))
            }
            Phase.EXPLORE -> {
                if (ui.measuring || ui.frozen) return
                if (on?.id != exploreOn) {
                    exploreOn = on?.id
                    exploreSinceMs = now
                }
                if (on != null && !moving && now - exploreSinceMs >= EXPLORE_DWELL_MS && ui.selected != on.id) {
                    selectedAt = at
                    sink(AtlasEvent.Selected(on.id))
                }
                // fade out: 0.3 m away from where it was chosen and 3 s later
                val sel = ui.selected
                val sa = selectedAt
                if (sel != null && sa != null && (at - sa).length * m.s > Judge.ARM_M && on?.id != sel) {
                    if (leftSelectedMs < 0) leftSelectedMs = now else if (now - leftSelectedMs > DESELECT_MS) {
                        leftSelectedMs = -1L
                        selectedAt = null
                        sink(AtlasEvent.Selected(null))
                    }
                } else {
                    leftSelectedMs = -1L
                }
            }
            else -> Unit
        }
        dwellPhase = ui.phase
    }

    private fun drawCrosshair(now: Long) {
        val cr = crossRoot ?: return
        val c = cursor ?: return
        val m = map ?: return
        val fy = floorY() ?: return
        val show = ui.phase in setOf(Phase.EXPLORE, Phase.QUIZ_READY, Phase.ASKING, Phase.PAUSED, Phase.RESULT)
        if (cr.enabled != show) cr.enabled = show
        if (!show) return
        val at = m.toMap(c)
        cr.setLocal(position = c.at(fy + CROSS_LIFT_M + max(liftAt(at), sheetLiftM)), rotation = quatYaw(cursorYaw))
        // dwell progress: only when dragging-to-answer and armed (frozen debug states hold half)
        val progress = if (ui.phase == Phase.ASKING && ui.answerMode == AnswerMode.DRAG) (if (ui.frozen && debugDwell) 0.5f else dwell.progress) else 0f
        val lit = (progress * 12f).toInt().coerceIn(0, 12)
        val showSegs = ui.phase == Phase.ASKING && ui.answerMode == AnswerMode.DRAG && (dwell.isArmed || debugDwell)
        if (lit != segLit || !showSegs) {
            val mt = materials ?: return
            for (k in segs.indices) {
                segs[k].enabled = showSegs
                segs[k].setMaterial(if (k < lit) mt.yellow else mt.dim)
            }
            segLit = if (showSegs) lit else -1
        }
        // answer flash: green / red ring spreading to 0.3 m in 0.4 s
        val fr = flashRing ?: return
        val kind = flashColor
        val t = if (flashStartMs < 0) 2f else (now - flashStartMs) / FLASH_MS
        val frozenT = if (ui.frozen && kind != null && kind != FeedbackKind.REVEAL) 0.6f else t
        if (kind != null && kind != FeedbackKind.REVEAL && frozenT < 1f) {
            val mt = materials ?: return
            fr.setMaterial(if (kind == FeedbackKind.RIGHT) mt.green else mt.red)
            val r = 0.12f + 0.18f * frozenT
            fr.setLocal(position = Vec3(0f, 0.002f, 0f), scale = Vec3(r, 1f, r))
            fr.enabled = true
        } else if (fr.enabled) {
            fr.enabled = false
        }
        // reveal: an orange breathing ring at the answer
        val rv = revealRing ?: return
        val q = ui.quiz
        val revealing = ui.phase == Phase.ASKING && q?.revealing == true
        if (revealing) {
            val t2 = currentSet()?.target(q!!.current ?: "") ?: return
            val p = m.toFloor(t2.labelAt)
            val breath = if (ui.frozen) 1f else (0.5f + 0.5f * sin(now / 1000.0 * 2 * Math.PI).toFloat())
            val r = 0.14f + 0.06f * breath
            rv.setLocal(position = p.at(fy + CROSS_LIFT_M + max(liftAt(t2.labelAt), sheetLiftM)), scale = Vec3(r, 1f, r))
            rv.enabled = true
        } else if (rv.enabled) {
            rv.enabled = false
        }
    }

    // =========================================================================================
    // outlines (design entities3d.targetOverlay, judging.leak)
    // =========================================================================================

    private fun clearOutlines() {
        for (o in outlines.values) {
            runCatching { o.entity?.destroy(true) }
            runCatching { o.mesh?.close() }
        }
        outlines.clear()
        outlineSet = null
    }

    private fun drawOutlines(now: Long) {
        val m = map ?: return
        val set = currentSet()
        if (set?.id != outlineSet) {
            clearOutlines()
            outlineSet = set?.id
            set?.targets?.forEach { outlines[it.id] = Outline(it) }
        }
        if (set == null) return
        val mt = materials ?: return
        val q = ui.quiz
        var builtThisFrame = false
        var sheet = 0f
        for (o in outlines.values) {
            val t = o.target
            val outcome = q?.outcomes?.get(t.id)
            val current = q?.current == t.id
            val f = ui.feedback
            val flashing = ui.flash?.first == t.id && ((now / 250L) % 2L == 0L || ui.frozen)
            val material =
                when (ui.phase) {
                    Phase.EXPLORE -> if (ui.selected == t.id) mt.outlineSelected else mt.outline
                    Phase.ASKING, Phase.PAUSED -> when {
                        f?.kind == FeedbackKind.RIGHT && f.targetId == t.id && (ui.frozen || now - f.sinceMs < 800L) -> mt.rightFlash
                        current && q?.revealing == true -> if (ui.frozen || (now / 500L) % 2L == 0L) mt.revealFlash else mt.tries3
                        outcome != null -> outcomeMaterial(outcome, mt)
                        else -> null
                    }
                    Phase.RESULT -> if (flashing) mt.whiteFlash else outcome?.let { outcomeMaterial(it, mt) }
                    else -> null
                }
            val want = material != null
            // lines and dots are sized for the floor: rebuilt when the scale changed by a quarter (and the map rests)
            val stale = t.kind != Kind.AREA && o.builtS > 0f && (m.s / o.builtS > 1.25f || o.builtS / m.s > 1.25f) && !wasMoving
            if (want && (o.entity == null || stale) && !builtThisFrame) {
                buildOutline(o, m.s, material!!)
                builtThisFrame = true
            }
            val e = o.entity ?: continue
            if (want && o.material !== material) {
                e.setMaterial(material!!)
                o.material = material
            }
            if (e.enabled != want) e.enabled = want
            if (want) sheet = max(sheet, o.sheetH)
            // only explore makes outlines interactive (design judging.leak): no hover on unanswered places in a quiz
            val interactive = want && ui.phase == Phase.EXPLORE
            if (interactive != o.interactive) setInteractive(o, interactive)
        }
        sheetLiftM = sheet * reliefV + (if (sheet > 0f) OVERLAY_LIFT_M else 0f)
    }

    private fun outcomeMaterial(o: Outcome, mt: AtlasMaterials) =
        when (o) {
            Outcome.FIRST -> mt.tries1
            Outcome.SECOND -> mt.tries2
            Outcome.THIRD -> mt.tries3
            Outcome.REVEALED -> mt.revealed
        }

    private fun buildOutline(o: Outline, s: Float, material: com.pico.spatial.core.ecs.resource.Material) {
        val parent = overlayNode ?: return
        val t = o.target
        val (xy, idx) =
            when (t.kind) {
                Kind.AREA -> t.meshVerts to t.meshIndices
                Kind.LINE -> MeshFactory.ribbons(t.lines, RIVER_HALF_M / s)
                Kind.POINT -> t.point!!.let { MeshFactory.disc(it.x, it.lat, Judge.POINT_RADIUS_M / s) }
            }
        // an area is one flat sheet at the highest terrain under it (draping its few vertices let the relief poke
        // through as shards); ribbons and dots are dense / small and follow the terrain per vertex
        val sheet = if (t.kind == Kind.AREA) highestUnder(t) else null
        o.sheetH = sheet ?: 0f
        val mesh = runCatching { MeshFactory.flat("outline:${t.id}", xy, idx) { x, lat -> sheet ?: store.heightAt(MapPt(x, lat)) } }.onFailure { AtlasLog.e("OUTLINE mesh failed ${t.id}: $it", it) }.getOrNull() ?: return
        val old = o.entity
        val oldMesh = o.mesh
        val e = ModelEntity(mesh, material)
        runCatching { e.setName("outline:${t.id}") }
        e.enabled = false
        parent.addChild(e)
        o.entity = e
        o.mesh = mesh
        o.material = material
        o.builtS = s
        o.interactive = false
        if (old != null) {
            runCatching { old.destroy(true) }
            runCatching { oldMesh?.close() }
        }
    }

    /** Highest grid height inside an area (0.35 deg samples over its rings). */
    private fun highestUnder(t: Target): Float {
        var best = 0f
        for (r in t.rings) {
            var x0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            var y0 = Float.MAX_VALUE
            var y1 = -Float.MAX_VALUE
            for (k in 0 until r.size / 2) {
                x0 = min(x0, r[2 * k]); x1 = max(x1, r[2 * k]); y0 = min(y0, r[2 * k + 1]); y1 = max(y1, r[2 * k + 1])
            }
            val step = max(0.5f, max(x1 - x0, y1 - y0) / 40f)
            var x = x0
            while (x <= x1) {
                var y = y0
                while (y <= y1) {
                    val p = MapPt(x, y)
                    if (Judge.inside(p, r)) best = max(best, store.heightAt(p))
                    y += step
                }
                x += step
            }
            for (k in 0 until r.size / 2) best = max(best, store.heightAt(MapPt(r[2 * k], r[2 * k + 1])))
        }
        return best
    }

    private fun setInteractive(o: Outline, on: Boolean) {
        val e = o.entity ?: return
        o.interactive = on
        if (on) {
            val mesh = o.mesh ?: return
            runCatching {
                e.components.set(InteractableComponent())
                e.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createStaticMesh(mesh)), physicsMaterial = PhysicsMaterialResource()))
                e.components.set(HoverEffectComponent())
            }.onFailure { AtlasLog.w("OUTLINE interactive failed ${o.target.id}: $it") }
        } else {
            runCatching { e.components.remove(HoverEffectComponent::class.java) }
            runCatching { e.components.remove(CollisionComponent::class.java) }
            runCatching { e.components.remove(InteractableComponent::class.java) }
        }
    }

    // =========================================================================================
    // measuring line, labels, live readings
    // =========================================================================================

    private fun drawMeasure() {
        val m = map ?: return
        val c = cursor ?: return
        val fy = floorY() ?: return
        val from = measureFrom
        val p = pin ?: return
        val line = measureLine ?: return
        if (!ui.measuring || from == null || ui.phase != Phase.EXPLORE) {
            if (p.enabled) p.enabled = false
            line.hide()
            return
        }
        val a = m.toFloor(from).at(fy + CROSS_LIFT_M + 0.002f + max(liftAt(from), sheetLiftM))
        p.setLocal(position = a)
        if (!p.enabled) p.enabled = true
        val b = c.at(fy + CROSS_LIFT_M + 0.002f + max(liftAt(m.toMap(c)), sheetLiftM))
        line.draw(a, b, 0.012f, Vec3.UP)
    }

    private fun publishLive(moving: Boolean) {
        val m = map ?: return
        val c = cursor ?: return
        val at = m.toMap(c)
        val set = currentSet()
        val pick = if (set != null && at.onMap) Judge.pick(set.targets, at, m.s) else null
        val region = atlas.regionAt(at)
        val tl = tiles
        val level = tl?.drawn?.filter { it.distanceDeg(at) == 0f }?.maxOfOrNull { it.z } ?: 0
        val terrain = ui.phase == Phase.EXPLORE && m.sizeM >= TERRAIN_M && region != null
        val (barKm, _) = Scale.bar(m.s)
        val from = measureFrom
        val mKm = if (from != null) Judge.greatCircleKm(from, at).toInt() else 0
        val toName = if (from != null) pick?.on?.name(zh()) else null
        val next =
            LiveInfo(
                sizeM = m.sizeM,
                stepKm = Scale.stepKmRounded(m.s),
                barKm = barKm,
                detailLevel = level,
                region = if (m.sizeM >= TERRAIN_M) region?.name(zh()) else null,
                terrain = terrain,
                onTarget = pick?.on?.id,
                nowhere = pick != null && pick.on == null,
                offMap = !at.onMap,
                moving = moving,
                dwell = if (ui.frozen && debugDwell) 0.5f else dwell.progress,
                zoomLimit = if (SystemClock.elapsedRealtime() - limitShownMs < 1500L) 1 else 0,
                measureFrom = if (from != null) measureFromName else null,
                measureTo = toName,
                measureKm = mKm,
                measureMapM = if (from != null) (at - from).length * m.s else 0f,
                landform = null,
                arrowDeg = arrowDeg(m, c),
            )
        if (next != live.value) live.value = next
        updateLabels(m, at)
    }

    /** Reveal arrow on the tag: clockwise degrees from "away from you" to the answer's direction on the floor. */
    private fun arrowDeg(m: MapPose, c: P2): Float {
        val q = ui.quiz ?: return 0f
        if (!q.revealing) return 0f
        val t = currentSet()?.target(q.current ?: "") ?: return 0f
        val a = P2(c.x - pose.position.x, c.z - pose.position.z).normalized()
        val d = m.toFloor(t.labelAt) - c
        val r = P2(-a.z, a.x)
        return Math.toDegrees(atan2(d.dot(r), d.dot(a)).toDouble()).toFloat()
    }

    private fun label(id: String, l: LabelUi?) {
        if (l == null) {
            if (labels.containsKey(id)) labels.remove(id)
        } else if (labels[id] != l) {
            labels[id] = l
        }
    }

    /** Which places get a name label, a landform plate or the measuring tag (contents only; places in [placePanels]). */
    private fun updateLabels(m: MapPose, at: MapPt) {
        val zh = zh()
        val set = currentSet()
        val f = ui.feedback
        var l0: LabelUi? = null
        var l1: LabelUi? = null
        when (ui.phase) {
            Phase.EXPLORE -> ui.selected?.let { id -> set?.target(id)?.let { l0 = LabelUi(it.name(zh), it.name(!zh), LabelTone.PLAIN, id) } }
            Phase.ASKING, Phase.PAUSED -> {
                val q = ui.quiz
                if (f != null && f.kind == FeedbackKind.WRONG) set?.target(f.targetId)?.let { l1 = LabelUi(it.name(zh), it.name(!zh), LabelTone.WRONG, it.id) }
                if (f != null && f.kind == FeedbackKind.RIGHT) set?.target(f.targetId)?.let { l0 = LabelUi(it.name(zh), it.name(!zh), LabelTone.RIGHT, it.id) }
                if (q?.revealing == true) set?.target(q.current ?: "")?.let { l0 = LabelUi(it.name(zh), it.name(!zh), LabelTone.REVEAL, it.id) }
            }
            Phase.RESULT -> ui.flash?.first?.let { id -> set?.target(id)?.let { l0 = LabelUi(it.name(zh), it.name(!zh), LabelTone.PLAIN, id) } }
            else -> Unit
        }
        label(PanelIds.label(0), l0)
        label(PanelIds.label(1), l1)
        val from = measureFrom
        label(PanelIds.MEASURE, if (ui.measuring && from != null && ui.phase == Phase.EXPLORE) LabelUi(String.format(Locale.US, "%,d km", Judge.greatCircleKm(from, at).toInt()), null) else null)
        // landform plates: in a featured region at >= 25 m, the 6 nearest within 4 m
        val lfs =
            if (ui.phase == Phase.EXPLORE && m.sizeM >= TERRAIN_M) {
                val me = P2.of(pose.position)
                atlas.landforms.filter { atlas.regions.firstOrNull { r -> r.id == it.region }?.contains(it.at) == true }
                    .map { it to m.toFloor(it.at).let { p -> kotlin.math.hypot(p.x - me.x, p.z - me.z) } }
                    .filter { it.second < LANDFORM_RANGE_M }
                    .sortedBy { it.second }
                    .take(PanelIds.LANDFORMS)
                    .map { it.first }
            } else emptyList()
        landformShown = lfs.map { it.at }
        for (i in 0 until PanelIds.LANDFORMS) {
            val lf = lfs.getOrNull(i)
            label(PanelIds.landform(i), lf?.let { LabelUi(it.name(zh) + if (it.heightM > 0) String.format(Locale.US, " %,d m", it.heightM) else "", it.fact(zh)) })
        }
    }

    private var landformShown: List<MapPt> = emptyList()

    // =========================================================================================
    // panels in the room
    // =========================================================================================

    private var boardAt: Vec3? = null
    private var boardFrom = Vec3.ZERO
    private var boardTo = Vec3.ZERO
    private var boardSlideMs = -1L
    private var boardOutMs = -1L

    /** Board rest (panels.board.placement): 1.0 m ahead on the level view, centre at head - 0.25 m (>= floor + 1.10 m). */
    private fun boardRest(): Vec3 {
        val f = pose.level()
        val fy = floorY() ?: (pose.position.y - EST_BELOW_HEAD_M)
        val y = max(pose.position.y - BOARD_BELOW_HEAD_M, fy + BOARD_MIN_Y_M)
        return Vec3(pose.position.x + f.x * BOARD_M, y, pose.position.z + f.z * BOARD_M)
    }

    /** Lazy follow of the level view only: looking down at the floor does not drag the board down. */
    private fun followBoard(now: Long): Vec3 {
        val at = boardAt ?: boardRest().also { boardAt = it }
        if (boardSlideMs >= 0) {
            val q = ((now - boardSlideMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = if (q < 0.5f) 4f * q * q * q else 1f - (-2f * q + 2f).let { it * it * it } / 2f
            val p = boardFrom.lerp(boardTo, e)
            if (q >= 1f) {
                boardSlideMs = -1L
                boardAt = boardTo
            }
            return p
        }
        val f = pose.level()
        val to = P2(at.x - pose.position.x, at.z - pose.position.z)
        val dist = sqrt(to.x * to.x + to.z * to.z)
        val ang = if (dist < 1e-3f) 0f else Math.toDegrees(kotlin.math.acos(((to.x * f.x + to.z * f.z) / dist).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val hold = ui.menuOpen || now - lastBoardPressMs < KEY_HOLD_MS
        val out = ang > BOARD_WINDOW_DEG || dist > 1.8f || dist < 0.5f || abs(at.y - boardRest().y) > 0.35f
        if (!out || hold) boardOutMs = -1L else if (boardOutMs < 0) boardOutMs = now else if (now - boardOutMs > OUT_MS) {
            boardFrom = at
            boardTo = boardRest()
            boardSlideMs = now
            boardOutMs = -1L
        }
        return at
    }

    private var lastBoardPressMs = -1L

    fun onBoardPointer() {
        lastBoardPressMs = SystemClock.elapsedRealtime()
    }

    private fun placePanels(now: Long) {
        val rig = rig ?: return
        val board = rig[PanelIds.BOARD] ?: return
        val bp = followBoard(now)
        board.show(bp)
        val facing = io.embodit.flooratlas.domain.FacePanel.facing(bp, pose.position, board.lastYaw)
        val bw = PanelSizes.BOARD_W * DP_M
        val bh = PanelSizes.BOARD_H * DP_M
        if (ui.menuOpen) {
            val mw = PanelSizes.MENU_W * DP_M
            val mh = PanelSizes.MENU_H * DP_M
            rig[PanelIds.MENU]?.show(bp + facing.right * (bw / 2f - mw / 2f - 0.01f) + facing.up * (bh / 2f - 0.05f - mh / 2f) + facing.normal * 0.04f)
        }
        var above = bp + facing.up * (bh / 2f + 0.04f)
        if (ui.snack != null) {
            val sh = PanelSizes.SNACK_H * DP_M
            rig[PanelIds.SNACK]?.show(above + facing.up * (sh / 2f))
            above += facing.up * (sh + 0.02f)
        }
        if (ui.coach != null) {
            val ch = PanelSizes.COACH_H * DP_M
            rig[PanelIds.COACH]?.show(above + facing.up * (ch / 2f))
        }
        val m = map
        val c = cursor
        val fy = floorY()
        // question tag: 0.25 m beyond the crosshair, 0.30 m above the floor (panels.questionTag)
        val tagOn = (ui.phase == Phase.ASKING || ui.phase == Phase.PAUSED) && c != null && fy != null
        if (tagOn) {
            val away = P2(c!!.x - pose.position.x, c.z - pose.position.z).normalized()
            rig[PanelIds.TAG]?.show(Vec3(c.x + away.x * TAG_BEYOND_M, fy!! + TAG_HEIGHT_M, c.z + away.z * TAG_BEYOND_M))
        } else rig[PanelIds.TAG]?.hide()
        // name labels 0.35 m above the place
        for (i in 0 until PanelIds.LABELS) {
            val id = PanelIds.label(i)
            val l = labels[id]
            val t = l?.targetId?.let { atlas.target(it) }
            if (l == null || t == null || m == null || fy == null) {
                rig[id]?.hide()
                continue
            }
            val p = m.toFloor(t.labelAt)
            val base = p.at(fy + LABEL_HEIGHT_M + liftAt(t.labelAt) + i * 0.10f)
            val k = (base.distanceTo(pose.position) * 0.55f).coerceIn(0.7f, 2.5f)
            rig[id]?.show(base, k, k)
        }
        // measuring tag over the middle of the line
        val from = measureFrom
        if (labels[PanelIds.MEASURE] != null && m != null && c != null && fy != null && from != null) {
            val a = m.toFloor(from)
            val mid = P2((a.x + c.x) / 2f, (a.z + c.z) / 2f)
            rig[PanelIds.MEASURE]?.show(mid.at(fy + 0.18f))
        } else rig[PanelIds.MEASURE]?.hide()
        for (i in 0 until PanelIds.LANDFORMS) {
            val id = PanelIds.landform(i)
            val at = landformShown.getOrNull(i)
            if (labels[id] == null || at == null || m == null || fy == null) {
                rig[id]?.hide()
                continue
            }
            rig[id]?.show(m.toFloor(at).at(fy + liftAt(at) + LANDFORM_HEIGHT_M))
        }
    }

    // =========================================================================================
    // input: system taps, drags, two-hand scale
    // =========================================================================================

    private fun isPanel(e: Entity?): Boolean = runCatching { e?.getName() }.getOrNull()?.startsWith("panel:") == true

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        if (lastDragEndMs > 0 && now - lastDragEndMs < TAP_AFTER_DRAG_MS) return
        if (isPanel(target) || target == null) {
            AtlasLog.i("TAP other target=$name")
            return
        }
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        tapAt(stage, name)
    }

    /** A pinch / trigger click on the map at [stage] (Stage metres), or anywhere for the crosshair confirm. */
    fun tapAt(stage: Vec3?, name: String) {
        val m = map ?: return
        val c = cursor ?: return
        val set = currentSet() ?: return
        val fy = floorY() ?: return
        val tapped = stage?.let { m.toMap(P2.of(it)) }
        AtlasLog.i(String.format(Locale.US, "TAP target=%s map=%s", name, tapped?.let { "(%.2f, %.2f)".format(Locale.US, it.x, it.lat) }))
        when (ui.phase) {
            Phase.EXPLORE -> {
                if (ui.measuring) return
                val id = name.removePrefix("outline:").takeIf { name.startsWith("outline:") } ?: tapped?.let { Judge.pick(set.targets, it, m.s).on?.id }
                if (id != null) {
                    selectedAt = tapped ?: m.toMap(c)
                    sink(AtlasEvent.Selected(id))
                }
            }
            Phase.ASKING -> {
                if (ui.feedback?.kind == FeedbackKind.RIGHT) return
                // point mode: judge where you pointed; drag mode: a click confirms the crosshair at once
                val at = if (ui.answerMode == AnswerMode.POINT) tapped else m.toMap(c)
                if (at == null || !at.onMap) return
                Judge.pick(set.targets, at, m.s).on?.let { sink(AtlasEvent.Answered(it.id, at)) }
            }
            else -> Unit
        }
        if (fy.isNaN()) AtlasLog.w("floor NaN")
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            "start" -> {
                drag = null
                val target = value.targetEntity
                if (isPanel(target)) return
                val m = map ?: return
                val press = start?.let { toStage(it.x, it.y, it.z) } ?: return
                if (!ui.mapMovable) {
                    AtlasLog.i("DRAG refused phase=${ui.phase.tag} menu=${ui.menuOpen}")
                    return
                }
                drag = Drag(press, m.toMap(P2.of(press)))
                AtlasLog.i("DRAG start target=${runCatching { target?.getName() }.getOrNull()} press=${AtlasLog.pos(press)}")
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.moves++
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                d.hand = handDelta(d.totalPx) ?: d.hand
                if (!d.begun && d.hand.length >= DRAG_START_M) {
                    d.begun = true
                    anim = null
                    val to = d.press - pose.position
                    d.dir = if (to.length > 1e-3f) to.normalized() else pose.forward
                    val pitch = Math.toDegrees(asin(pose.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
                    d.equivalent = DragLine.equivalentDepth(to.length, pitch)
                    d.prevHand = d.hand
                    AtlasLog.i("DRAG begin depth=${String.format(Locale.US, "%.2f", d.equivalent)}")
                }
                if (d.begun) {
                    val dir = DragLine.turn(d.dir ?: return, d.hand - d.prevHand, d.equivalent, pose.right, pose.up, pose.forward)
                    d.prevHand = d.hand
                    d.dir = dir
                    d.hit = floorHit(pose.position, dir)
                }
            }
            "end", "cancel" -> {
                val d = drag ?: return
                drag = null
                if (d.begun) lastDragEndMs = now
                AtlasLog.i("DRAG $phase moves=${d.moves} begun=${d.begun}")
            }
        }
    }

    /** Where a pointing line meets the floor, clamped to 4 m away (needsCheck N2: shallow rays jump). */
    private fun floorHit(origin: Vec3, dir: Vec3): P2? {
        val fy = floorY() ?: return null
        val flat = P2(dir.x, dir.z)
        val flatLen = flat.length
        if (flatLen < 1e-4f && dir.y >= 0f) return null
        val t = if (dir.y < -1e-4f) (fy - origin.y) / dir.y else Float.MAX_VALUE
        val reach = if (t == Float.MAX_VALUE) MAX_REACH_M else min(t * flatLen, MAX_REACH_M)
        val f = if (flatLen < 1e-4f) P2(0f, 0f) else P2(flat.x / flatLen, flat.z / flatLen)
        return P2(origin.x + f.x * reach, origin.z + f.z * reach)
    }

    fun onSpatialScale(phase: String, value: SpatialScaleValue) {
        when (phase) {
            "start" -> {
                scaleActive = true
                lastScaleMs = SystemClock.elapsedRealtime()
            }
            "move" -> {
                if (value.scaleValue.isFinite() && value.scaleValue > 0f) pendingScale *= value.scaleValue
                lastScaleMs = SystemClock.elapsedRealtime()
            }
            else -> scaleActive = false
        }
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // panel buttons that move the map (for hand users: ⟲ ⟳ − + and "Bring to me")
    // =========================================================================================

    fun rotateStep(dir: Int) {
        val m = map ?: return
        val c = cursor ?: return
        anim = Anim(m, m.rotateAbout(c, -dir * Math.toRadians(ROTATE_STEP_DEG).toFloat()), SystemClock.elapsedRealtime(), STEP_MS)
    }

    fun zoomStep(dir: Int) {
        val m = map ?: return
        val c = cursor ?: return
        val minM = minSize()
        val want = m.sizeM * if (dir > 0) ZOOM_IN else ZOOM_OUT
        if ((dir > 0 && m.sizeM >= MapGeo.MAX_SIZE_M - 1e-3f) || (dir < 0 && m.sizeM <= minM + 1e-3f)) {
            limitShownMs = SystemClock.elapsedRealtime()
            sink(AtlasEvent.Snack(if (dir > 0) SnackKind.ZOOM_MAX else SnackKind.ZOOM_MIN))
            return
        }
        anim = Anim(m, m.zoomTo(c, want, minM), SystemClock.elapsedRealtime(), STEP_MS)
    }

    fun recenter() {
        val size = map?.sizeM ?: ui.settings.lastSizeM
        lay(size, "recenter")
    }

    // =========================================================================================
    // debug seams
    // =========================================================================================

    var debugDwell = false

    fun debugPan(dx: Float, dz: Float): String {
        val m = map ?: return "no map"
        map = m.pan(P2(dx, dz))
        onSettled(map!!)
        return "pan $dx $dz"
    }

    fun debugRotate(deg: Float): String {
        val m = map ?: return "no map"
        val c = cursor ?: return "no cursor"
        map = m.rotateAbout(c, -Math.toRadians(deg.toDouble()).toFloat())
        return "rotate $deg"
    }

    fun debugZoom(sizeM: Float): String {
        val m = map ?: return "no map"
        val c = cursor ?: return "no cursor"
        map = m.zoomTo(c, sizeM, minSize())
        sink(AtlasEvent.SizeSettled(map!!.sizeM))
        return String.format(Locale.US, "zoom %.2f", map!!.sizeM)
    }

    fun debugStick(x: Float, y: Float, ms: Long): String {
        stick.debugHold(x, y, ms)
        return "stick x=$x y=$y ms=$ms"
    }

    /** Brings map point [p] under the crosshair. */
    fun debugBring(p: MapPt): String {
        val m = map ?: return "no map"
        val c = cursor ?: return "no cursor"
        anim = null
        map = m.bring(p, c)
        return String.format(Locale.US, "bring (%.2f, %.2f)", p.x, p.lat)
    }

    /** A map point far from every target of the set (goto_nowhere). */
    fun debugNowhere(): MapPt? {
        val m = map ?: return null
        val set = currentSet() ?: return null
        var best: MapPt? = null
        var bd = -1f
        var x = 2f
        while (x < 358f) {
            var lat = -60f
            while (lat < 70f) {
                val p = MapPt(x, lat)
                val d = Judge.pick(set.targets, p, m.s).distanceM
                if (d > bd) {
                    bd = d
                    best = p
                }
                lat += 4f
            }
            x += 4f
        }
        return best
    }

    /** Debug `dwell`: judge the crosshair now. */
    fun debugJudgeNow(): String {
        val m = map ?: return "no map"
        val c = cursor ?: return "no cursor"
        val set = currentSet() ?: return "no set"
        val at = m.toMap(c)
        val on = Judge.pick(set.targets, at, m.s).on ?: return "nowhere"
        when (ui.phase) {
            Phase.ASKING -> sink(AtlasEvent.Answered(on.id, at))
            Phase.EXPLORE -> sink(AtlasEvent.Selected(on.id))
            else -> return "phase ${ui.phase.tag}"
        }
        return "judged ${on.id}"
    }

    fun debugLine(): String {
        val m = map
        val c = cursor
        val at = if (m != null && c != null) m.toMap(c) else null
        return String.format(
            Locale.US,
            "SCENE map=%s size=%.2f yaw=%.1f cursor=%s cursorMap=%s floor=%s est=%s relief=%.7f head=%s fwd=%s %s",
            m?.let { "(%.3f, %.3f)".format(Locale.US, it.ox, it.oz) },
            m?.sizeM ?: 0f,
            Math.toDegrees((m?.yaw ?: 0f).toDouble()),
            c?.let { "(%.3f, %.3f)".format(Locale.US, it.x, it.z) },
            at?.let { "(%.2f, %.2f)".format(Locale.US, it.x, it.lat) },
            floorY(),
            estimating,
            reliefV,
            AtlasLog.pos(pose.position),
            AtlasLog.pos(pose.forward),
            tiles?.line(),
        )
    }

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    fun headPose(): HeadPose? = if (hasHead) pose else null

    fun cursorMap(): MapPt? {
        val m = map ?: return null
        val c = cursor ?: return null
        return m.toMap(c)
    }

    fun mapPose(): MapPose? = map

    fun targetLevel(): Int = TileSelect.targetLevel(map?.sizeM ?: 3f)

    companion object {
        const val CURSOR_AHEAD_M = 0.45f
        const val EST_BELOW_HEAD_M = 1.55f
        const val FLOOR_TIMEOUT_MS = 5_000L
        const val BLOCK_GRACE_MS = 8_000L
        const val TILE_LIFT_M = 0.003f
        const val OVERLAY_LIFT_M = 0.007f
        const val CROSS_LIFT_M = 0.010f
        const val STICK_DEAD = 0.15f
        const val STICK_DEG_S = 90f
        const val AUTOZOOM_MS = 400L
        const val SNAP_MS = 150L
        const val STEP_MS = 200L
        const val ROTATE_STEP_DEG = 15.0
        const val ZOOM_IN = 1.5f
        const val ZOOM_OUT = 0.67f
        const val EXPLORE_DWELL_MS = 600L
        const val DESELECT_MS = 3_000L
        const val FLASH_MS = 400f
        const val RIVER_HALF_M = 0.004f
        const val TERRAIN_M = 25f
        const val LANDFORM_RANGE_M = 4f
        const val LANDFORM_HEIGHT_M = 0.12f
        const val LABEL_HEIGHT_M = 0.35f
        const val TAG_BEYOND_M = 0.25f
        const val TAG_HEIGHT_M = 0.30f
        const val BOARD_M = 1.0f
        const val BOARD_BELOW_HEAD_M = 0.25f
        const val BOARD_MIN_Y_M = 1.10f
        const val BOARD_WINDOW_DEG = 35f
        const val OUT_MS = 500L
        const val SLIDE_MS = 350f
        const val KEY_HOLD_MS = 600L
        const val TAP_AFTER_DRAG_MS = 300L
        const val DRAG_START_M = 0.01f
        const val MAX_REACH_M = 4f
        const val LIVE_MS = 100L
        const val LOG_MS = 5_000L
    }
}
