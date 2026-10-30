package io.embodit.flooratlas.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.embodit.flooratlas.domain.AnswerMode
import io.embodit.flooratlas.domain.MapPt
import io.embodit.flooratlas.domain.Mode
import io.embodit.flooratlas.domain.Outcome
import io.embodit.flooratlas.domain.Quiz
import io.embodit.flooratlas.platform.AtlasGraph
import io.embodit.flooratlas.platform.AtlasLog
import io.embodit.flooratlas.ui.atlas.AtlasEvent
import io.embodit.flooratlas.ui.atlas.AtlasUiState
import io.embodit.flooratlas.ui.atlas.AtlasViewModel
import io.embodit.flooratlas.ui.atlas.Coach
import io.embodit.flooratlas.ui.atlas.Feedback
import io.embodit.flooratlas.ui.atlas.FeedbackKind
import io.embodit.flooratlas.ui.atlas.Phase
import io.embodit.flooratlas.ui.atlas.SnackKind
import io.embodit.flooratlas.ui.atlas.SnackUi
import io.embodit.flooratlas.ui.atlas.debug.Probe
import io.embodit.flooratlas.ui.atlas.scene.AtlasScene
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Test hooks of the debug build (design `qaHooks`). One log line (tag `FloorAtlas`) answers every command.
 * Broadcast: `am broadcast -a io.embodit.flooratlas.DEBUG -p io.embodit.flooratlas --es cmd NAME [--es s TEXT] [--es a NUMBERS]`.
 *
 * - `state` s=<phase[+overlay...][:mode]>[@setId]   jump to a design state (stateNaming) and freeze short animations
 * - `set` s=setId · `mode` s=explore|quiz
 * - `drag` a=dx,dz (metres, map pan) · `rotate` a=deg · `zoom` a=longSideM · `stick` a=x,y[,ms]
 * - `goto` s=targetId|landform name (en) · `goto_wrong` · `goto_nowhere` · `dwell` (judge the crosshair now)
 * - `answer_all` a=rate · `relay` (bring to me) · `nofloor` s=on|off · `lost` s=on|off
 * - `coach_reset` · `best_reset` · `ui` s=<event> (back, more, mute, play, pause, resume, restart, review, again,
 *   change_set, measure, measure_done, start_quiz, coach_ok, menu_close, point, drag_mode, zh, en, system, rotl, rotr,
 *   zin, zout, exit)
 * - `dump` (also files/dump.json) · `panels` · `pose`
 */
internal object DebugCommands {
    private const val NO_HOST = "CMD ignored: no Stage"
    private val main = Handler(Looper.getMainLooper())

    fun run(context: Context, cmd: String, s: String?, a: String?) {
        val host = Probe.host
        if (host == null) {
            AtlasLog.w(NO_HOST)
            return
        }
        val vm = host.viewModel()
        val scene = host.scene()
        val nums = a?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
        scene.debugDwell = false
        val out: String =
            when (cmd) {
                "state" -> state(vm, scene, s ?: "picker")
                "set" -> {
                    vm.onEvent(AtlasEvent.SetClicked(s ?: "world-continents"))
                    "set $s"
                }
                "mode" -> {
                    vm.onEvent(AtlasEvent.ModeSelected(if (s == "quiz") Mode.QUIZ else Mode.EXPLORE))
                    "mode $s"
                }
                "drag" -> scene.debugPan(nums.getOrElse(0) { 0f }, nums.getOrElse(1) { 0f })
                "rotate" -> scene.debugRotate(nums.getOrElse(0) { 15f })
                "zoom" -> scene.debugZoom(nums.getOrElse(0) { 3f })
                "stick" -> scene.debugStick(nums.getOrElse(0) { 0f }, nums.getOrElse(1) { 0f }, nums.getOrElse(2) { 1000f }.toLong())
                "goto" -> goto(vm, scene, s ?: "")
                "goto_wrong" -> gotoWrong(vm, scene)
                "goto_nowhere" -> scene.debugNowhere()?.let { scene.debugBring(it) } ?: "no point"
                "dwell" -> scene.debugJudgeNow()
                "answer_all" -> {
                    vm.debugFinish(nums.getOrElse(0) { 0.8f })
                    "answer_all"
                }
                "relay" -> {
                    scene.recenter()
                    "relay"
                }
                "nofloor" -> {
                    AtlasGraph.planes.ignore = s != "off"
                    "nofloor ${AtlasGraph.planes.ignore}"
                }
                "lost" -> {
                    AtlasGraph.head.forceLost = s != "off"
                    "lost ${AtlasGraph.head.forceLost}"
                }
                "coach_reset" -> {
                    vm.debugSet(vm.state.value.copy(settings = vm.state.value.settings.copy(coachExplore = false, coachAsk = false)))
                    "coach reset"
                }
                "best_reset" -> {
                    vm.debugSet(vm.state.value.copy(settings = vm.state.value.settings.copy(bests = emptyMap())))
                    "best reset"
                }
                "ui" -> ui(vm, scene, s ?: "")
                "dump" -> dump(context, vm, scene)
                "panels" -> scene.panelLines().joinToString("\n")
                "pose" -> scene.debugLine()
                else -> "unknown command '$cmd'"
            }
        out.lines().forEach { AtlasLog.i("CMD $cmd -> $it") }
    }

    private fun ui(vm: AtlasViewModel, scene: AtlasScene, name: String): String {
        val e: AtlasEvent? =
            when (name) {
                "back" -> AtlasEvent.Back
                "more" -> AtlasEvent.MoreClicked
                "menu_close" -> AtlasEvent.MenuDismissed
                "mute" -> AtlasEvent.MuteToggled
                "play" -> AtlasEvent.PlayClicked
                "pause" -> AtlasEvent.PauseClicked
                "resume" -> AtlasEvent.ResumeClicked
                "restart" -> AtlasEvent.RestartClicked
                "review" -> AtlasEvent.ReviewClicked
                "again" -> AtlasEvent.AgainClicked
                "change_set" -> AtlasEvent.ChangeSetClicked
                "measure" -> AtlasEvent.MeasureClicked
                "measure_done" -> AtlasEvent.MeasureDone
                "start_quiz" -> AtlasEvent.StartQuizClicked
                "coach_ok" -> AtlasEvent.CoachDismissed
                "point" -> AtlasEvent.AnswerModeSelected(AnswerMode.POINT)
                "drag_mode" -> AtlasEvent.AnswerModeSelected(AnswerMode.DRAG)
                "zh" -> AtlasEvent.LanguageSelected(io.embodit.flooratlas.domain.LanguagePref.ZH)
                "en" -> AtlasEvent.LanguageSelected(io.embodit.flooratlas.domain.LanguagePref.EN)
                "system" -> AtlasEvent.LanguageSelected(io.embodit.flooratlas.domain.LanguagePref.SYSTEM)
                "speak" -> AtlasEvent.SpeakToggled
                "exit" -> AtlasEvent.ExitClicked
                else -> null
            }
        when (name) {
            "rotl" -> scene.rotateStep(-1)
            "rotr" -> scene.rotateStep(1)
            "zin" -> scene.zoomStep(1)
            "zout" -> scene.zoomStep(-1)
            else -> if (e == null) return "unknown control '$name'" else vm.onEvent(e)
        }
        return "ui $name"
    }

    private fun goto(vm: AtlasViewModel, scene: AtlasScene, id: String): String {
        val atlas = vm.atlas
        val t = atlas.target(id)
        if (t != null) return scene.debugBring(t.anchor) + " target=$id"
        val lf = atlas.landforms.firstOrNull { it.en.equals(id, true) || it.en.lowercase(Locale.US).replace(' ', '-') == id.lowercase(Locale.US) || id == "everest" && it.en == "Mount Everest" }
        if (lf != null) return scene.debugBring(lf.at) + " landform=${lf.en}"
        return "no target or landform '$id'"
    }

    /** Brings a target of the set that is not the current question under the crosshair. */
    private fun gotoWrong(vm: AtlasViewModel, scene: AtlasScene): String {
        val st = vm.state.value
        val set = st.setId?.let { vm.atlas.set(it) } ?: return "no set"
        val cur = st.quiz?.current
        val t = set.targets.firstOrNull { it.id != cur && st.quiz?.outcomes?.containsKey(it.id) != true } ?: return "no other target"
        return scene.debugBring(t.anchor) + " wrong=${t.id}"
    }

    /**
     * `state <phase>[+overlay...][:mode][@set]`: rebuilds that design state from a calm start, places the map so the
     * state shows at the crosshair, and freezes the clock and short animations until the next command.
     */
    private fun state(vm: AtlasViewModel, scene: AtlasScene, spec: String): String {
        val (body, setArg) = spec.split('@').let { it[0] to it.getOrNull(1) }
        val mode = body.substringAfter(':', "")
        val parts = body.substringBefore(':').split('+')
        val phaseTag = parts[0]
        val overlays = parts.drop(1).toSet()
        val now = SystemClock.elapsedRealtime()
        val atlas = vm.atlas
        val base0 = vm.state.value
        AtlasGraph.head.forceLost = false
        AtlasGraph.planes.ignore = false
        val setId = setArg ?: (if ("terrain" in overlays) "world-relief" else if ("measuring" in overlays) "world-cities" else base0.setId ?: "world-continents")
        val set = atlas.set(setId) ?: return "no set $setId"
        var st =
            AtlasUiState(
                phase = Phase.entries.firstOrNull { it.tag == phaseTag } ?: return "no phase $phaseTag",
                settings = base0.settings.copy(muted = "muted" in overlays, answerMode = if (mode == "point") AnswerMode.POINT else AnswerMode.DRAG, mode = if (phaseTag == "quizReady") Mode.QUIZ else Mode.EXPLORE),
                setId = if (phaseTag == "picker" || phaseTag == "starting" || phaseTag == "blocked") base0.setId else setId,
                frozen = true,
                voiceAvailable = base0.voiceAvailable,
            )
        if (phaseTag in setOf("asking", "paused", "result")) {
            var q = Quiz.start(set, Random(7), null).copy(review = mode == "review")
            if (phaseTag == "result") {
                q = q.finishAll(0.8f, Random(3))
            } else {
                // two answered already: "3 / 11", one white and one yellow outline on the floor
                val o = q.order
                q = q.copy(index = 2, outcomes = mapOf(o[0] to Outcome.FIRST, o[1] to Outcome.SECOND), elapsedMs = 67_000L)
            }
            st = st.copy(quiz = q, newBest = phaseTag == "result" && mode != "review")
        }
        val q = st.quiz
        val cur = q?.current?.let { set.target(it) }
        val other = set.targets.firstOrNull { it.id != cur?.id && q?.outcomes?.containsKey(it.id) != true }
        var bring: MapPt? = cur?.anchor ?: set.targets.first().anchor
        if ("floorEstimate" in overlays) st = st.copy(floorEstimate = true, snack = SnackUi(SnackKind.FLOOR_ESTIMATE, now + 3_600_000L))
        if ("menu" in overlays) st = st.copy(menuOpen = true)
        if ("coach" in overlays) st = st.copy(coach = if (phaseTag == "asking") Coach.ASK else Coach.EXPLORE)
        if ("lost" in overlays) {
            st = st.copy(trackingLost = true, snack = SnackUi(SnackKind.LOST, now))
            AtlasGraph.head.forceLost = true
        }
        if ("selected" in overlays) st = st.copy(selected = set.targets.first().id).also { bring = set.targets.first().anchor }
        if ("right" in overlays && cur != null) st = st.copy(quiz = q!!.copy(index = q.index + 1, outcomes = q.outcomes + (cur.id to Outcome.FIRST)), feedback = Feedback(FeedbackKind.RIGHT, cur.id, cur.anchor, now))
        if ("wrong" in overlays && cur != null && other != null) {
            st = st.copy(quiz = q!!.copy(tries = 1), feedback = Feedback(FeedbackKind.WRONG, other.id, other.anchor, now))
            bring = other.anchor
        }
        if ("reveal" in overlays && cur != null && other != null) {
            st = st.copy(quiz = q!!.copy(tries = 3, revealing = true), feedback = Feedback(FeedbackKind.REVEAL, cur.id, other.anchor, now))
            bring = other.anchor
        }
        if ("dwell" in overlays) scene.debugDwell = true
        if (phaseTag == "result") st = st.copy(flash = null)
        vm.debugSet(st)
        val c = scene.cursorMap()
        val mp = scene.mapPose()
        val lines = ArrayList<String>()
        main.post {
            // map placement for the state, after the scene has seen the new state
            when {
                "terrain" in overlays -> {
                    scene.debugZoom(60f)
                    goto(vm, scene, "everest")
                }
                "measuring" in overlays -> {
                    scene.debugBring(set.target("beijing")?.anchor ?: set.targets.first().anchor)
                    vm.debugSet(vm.state.value.copy(measuring = true, frozen = true))
                    main.postDelayed({ scene.debugBring(set.target("cairo")?.anchor ?: set.targets.last().anchor) }, 400L)
                }
                "nowhere" in overlays -> scene.debugNowhere()?.let { scene.debugBring(it) }
                "offMap" in overlays -> scene.debugBring(MapPt(-30f, 2f))
                "zoomLimit" in overlays -> {
                    scene.debugZoom(98f)
                    scene.zoomStep(1)
                }
                "moving" in overlays -> scene.debugStick(0.6f, 0.4f, 20_000L)
                "snap" in overlays -> bring?.let { scene.debugBring(it) }
                bring != null && phaseTag !in setOf("picker", "starting", "blocked", "quizReady") -> scene.debugBring(bring!!)
            }
            if (phaseTag in setOf("asking", "paused", "quizReady") && "terrain" !in overlays && "zoomLimit" !in overlays) {
                val min = set.minSizeM
                if ((scene.mapPose()?.sizeM ?: 0f) < min) scene.debugZoom(min)
            }
        }
        lines += "STATE ${st.phase.tag} set=$setId overlays=$overlays mode=$mode cursorBefore=$c map=${mp?.sizeM}"
        return lines.joinToString("\n")
    }

    private fun dump(context: Context, vm: AtlasViewModel, scene: AtlasScene): String {
        val st = vm.state.value
        val live = scene.live.value
        val json =
            String.format(
                Locale.US,
                "{\"designState\":\"%s\",\"phase\":\"%s\",\"set\":\"%s\",\"quiz\":%s,\"live\":\"%s\",\"scene\":\"%s\"}",
                st.designState(live),
                st.phase.tag,
                st.setId,
                st.quiz?.let { "{\"index\":${it.index},\"total\":${it.total},\"current\":\"${it.current}\",\"tries\":${it.tries},\"revealing\":${it.revealing},\"elapsedMs\":${it.elapsedMs},\"percent\":${it.percent}}" } ?: "null",
                live.toString().replace("\"", "'"),
                scene.debugLine().replace("\"", "'"),
            )
        runCatching { File(context.filesDir, "dump.json").writeText(json) }
        return "DUMP $json"
    }
}
