package io.embodit.flooratlas.ui.atlas

import io.embodit.flooratlas.domain.AnswerMode
import io.embodit.flooratlas.domain.LanguagePref
import io.embodit.flooratlas.domain.MapPt
import io.embodit.flooratlas.domain.Mode
import io.embodit.flooratlas.domain.Quiz
import io.embodit.flooratlas.domain.Settings

/** Stages of the board (design `states[]`, kind 阶段). */
enum class Phase(val tag: String) {
    STARTING("starting"), PICKER("picker"), EXPLORE("explore"), QUIZ_READY("quizReady"), ASKING("asking"), PAUSED("paused"), RESULT("result"), BLOCKED("blocked")
}

enum class FeedbackKind { RIGHT, WRONG, REVEAL }

/** The short answer feedback on the question tag and the board (design `+right` / `+wrong` / `+reveal`). */
data class Feedback(val kind: FeedbackKind, val targetId: String, val at: MapPt?, val sinceMs: Long)

enum class SnackKind { FLOOR_ESTIMATE, LOST, ZOOM_MAX, ZOOM_MIN, OFF_MAP }

data class SnackUi(val kind: SnackKind, val sinceMs: Long)

enum class Coach { EXPLORE, ASK }

/**
 * Everything the board, the tags and the scene draw from. The map pose is not here: it lives in the scene
 * (per-frame, ECS) and reaches the panels as [LiveInfo] at 10 Hz.
 */
data class AtlasUiState(
    val phase: Phase = Phase.STARTING,
    val settings: Settings = Settings(),
    val setId: String? = null,
    val quiz: Quiz? = null,
    val feedback: Feedback? = null,
    val selected: String? = null,
    val measuring: Boolean = false,
    val menuOpen: Boolean = false,
    val snack: SnackUi? = null,
    val coach: Coach? = null,
    val floorEstimate: Boolean = false,
    val trackingLost: Boolean = false,
    val newBest: Boolean = false,
    val flash: Pair<String, Long>? = null,
    val voiceAvailable: Boolean = true,
    /** Debug `state`: short animations and the clock hold still until the next debug command (`rules.debugState`). */
    val frozen: Boolean = false,
    val blockedReason: String? = null,
) {
    val language: LanguagePref
        get() = settings.language

    val mode: Mode
        get() = settings.mode

    val answerMode: AnswerMode
        get() = settings.answerMode

    val inQuiz: Boolean
        get() = phase == Phase.ASKING || phase == Phase.PAUSED

    /** The stick and drag move the map (`rules.joystick`). */
    val mapMovable: Boolean
        get() = phase in setOf(Phase.EXPLORE, Phase.QUIZ_READY, Phase.ASKING, Phase.RESULT, Phase.PICKER) && !menuOpen

    /** QA screenshot name: phase[+overlay][:mode] (design `stateNaming`). */
    fun designState(live: LiveInfo): String {
        val b = StringBuilder(phase.tag)
        if (floorEstimate && phase in setOf(Phase.PICKER, Phase.EXPLORE, Phase.QUIZ_READY)) b.append("+floorEstimate")
        if (settings.muted) b.append("+muted")
        if (menuOpen) b.append("+menu")
        if (coach != null) b.append("+coach")
        if (trackingLost) b.append("+lost")
        when (feedback?.kind) {
            FeedbackKind.RIGHT -> b.append("+right")
            FeedbackKind.WRONG -> b.append("+wrong")
            FeedbackKind.REVEAL -> b.append("+reveal")
            null -> Unit
        }
        if (phase == Phase.EXPLORE && selected != null) b.append("+selected")
        if (phase == Phase.EXPLORE && measuring) b.append("+measuring")
        if (live.moving) b.append("+moving")
        if (live.dwell > 0f) b.append("+dwell")
        if (live.terrain) b.append("+terrain")
        if (live.zoomLimit != 0) b.append("+zoomLimit")
        if (live.offMap) b.append("+offMap") else if (live.nowhere && (phase == Phase.ASKING || phase == Phase.EXPLORE)) b.append("+nowhere")
        if (answerMode == AnswerMode.POINT && (phase == Phase.ASKING || phase == Phase.EXPLORE)) b.append(":point")
        if (quiz?.review == true && (phase == Phase.ASKING || phase == Phase.RESULT)) b.append(":review")
        return b.toString()
    }
}

/** What the scene measures every frame, handed to the panels at most 10 times a second (on change). */
data class LiveInfo(
    val sizeM: Float = 3f,
    val stepKm: Int = 0,
    val barKm: Int = 0,
    val detailLevel: Int = 0,
    val region: String? = null,
    val terrain: Boolean = false,
    val onTarget: String? = null,
    val nowhere: Boolean = false,
    val offMap: Boolean = false,
    val moving: Boolean = false,
    val dwell: Float = 0f,
    val zoomLimit: Int = 0,
    val measureFrom: String? = null,
    val measureTo: String? = null,
    val measureKm: Int = 0,
    val measureMapM: Float = 0f,
    val landform: String? = null,
    /** Reveal: clockwise degrees from the tag's up (away from you) to the answer. */
    val arrowDeg: Float = 0f,
)

sealed interface AtlasEvent {
    data object SceneReady : AtlasEvent
    data class DataReady(val ok: Boolean) : AtlasEvent
    data class SetClicked(val id: String) : AtlasEvent
    data class ModeSelected(val mode: Mode) : AtlasEvent
    data object Back : AtlasEvent
    data object MoreClicked : AtlasEvent
    data object MenuDismissed : AtlasEvent
    data object MuteToggled : AtlasEvent
    data object SpeakToggled : AtlasEvent
    data class LanguageSelected(val language: LanguagePref) : AtlasEvent
    data class AnswerModeSelected(val mode: AnswerMode) : AtlasEvent
    data class FloorAdjust(val deltaCm: Int) : AtlasEvent
    data object ExitClicked : AtlasEvent
    data object StartQuizClicked : AtlasEvent
    data object PlayClicked : AtlasEvent
    data object PauseClicked : AtlasEvent
    data object ResumeClicked : AtlasEvent
    data object RestartClicked : AtlasEvent
    data object ChangeSetClicked : AtlasEvent
    data object ReviewClicked : AtlasEvent
    data object AgainClicked : AtlasEvent
    data class MissClicked(val id: String) : AtlasEvent
    data object MeasureClicked : AtlasEvent
    data object MeasureDone : AtlasEvent
    data object CoachDismissed : AtlasEvent
    data object BlockedExit : AtlasEvent
    data object BlockedRetry : AtlasEvent

    // from the scene
    data class Answered(val targetId: String, val at: MapPt) : AtlasEvent
    data class Selected(val targetId: String?) : AtlasEvent
    data class TrackingChanged(val lost: Boolean) : AtlasEvent
    data class FloorChanged(val estimate: Boolean) : AtlasEvent
    data class Blocked(val reason: String?) : AtlasEvent
    data class Snack(val kind: SnackKind) : AtlasEvent
    data class SizeSettled(val sizeM: Float) : AtlasEvent
    data object Background : AtlasEvent
}

/** One-shot effects for the screen / scene. */
sealed interface AtlasEffect {
    data object Exit : AtlasEffect
    data class Cue(val kind: FeedbackKind) : AtlasEffect
    data class Speak(val text: String, val zh: Boolean) : AtlasEffect
    data object StopSpeech : AtlasEffect
    /** Lay the map, crosshair and board in front of the viewer now (D9). */
    data object Recenter : AtlasEffect
    data class FlashTarget(val id: String) : AtlasEffect
}
