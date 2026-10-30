package io.embodit.flooratlas.ui.atlas

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.flooratlas.domain.AnswerResult
import io.embodit.flooratlas.domain.Atlas
import io.embodit.flooratlas.domain.Best
import io.embodit.flooratlas.domain.LanguagePref
import io.embodit.flooratlas.domain.Mode
import io.embodit.flooratlas.domain.Quiz
import io.embodit.flooratlas.domain.Settings
import io.embodit.flooratlas.platform.AtlasGraph
import io.embodit.flooratlas.platform.AtlasLog
import io.embodit.flooratlas.platform.Prefs
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All product behaviour except the map's per-frame motion: phases, the quiz (`judging`), feedback timing, best
 * scores, settings. The scene reports answers and selections as events; this answers with state and effects.
 */
class AtlasViewModel(private val prefs: Prefs?, private val loadAtlas: suspend () -> Atlas, private val systemZh: () -> Boolean) : ViewModel() {
    private val _state = MutableStateFlow(AtlasUiState(settings = prefs?.load() ?: Settings()))
    val state: StateFlow<AtlasUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<AtlasEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<AtlasEffect> = _effects

    var atlas: Atlas = Atlas.EMPTY
        private set

    private var sceneReady = false
    private var dataReady = false
    private val random = Random(SystemClock.elapsedRealtime())

    init {
        viewModelScope.launch {
            val ok =
                runCatching { withContext(Dispatchers.IO) { loadAtlas() } }
                    .onSuccess { atlas = it }
                    .onFailure { AtlasLog.e("DATA load failed: $it", it) }
                    .isSuccess
            onEvent(AtlasEvent.DataReady(ok))
        }
        viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (true) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                tick(now, now - last)
                last = now
            }
        }
    }

    private val s: AtlasUiState
        get() = _state.value

    private fun set(next: AtlasUiState) {
        val before = _state.value
        _state.value = next
        if (before.phase != next.phase) AtlasLog.i("STATE ${before.phase.tag} -> ${next.phase.tag} set=${next.setId}")
        if (before.settings != next.settings) prefs?.save(next.settings)
    }

    private fun settings(f: (Settings) -> Settings) = set(s.copy(settings = f(s.settings)))

    /** Chinese copy for names / speech right now. */
    fun zh(): Boolean =
        when (s.language) {
            LanguagePref.ZH -> true
            LanguagePref.EN -> false
            LanguagePref.SYSTEM -> systemZh()
        }

    fun onEvent(e: AtlasEvent) {
        if (e !is AtlasEvent.Answered && e !is AtlasEvent.Selected && e !is AtlasEvent.Snack && e !is AtlasEvent.SizeSettled) AtlasLog.i("EVENT $e")
        if (s.frozen && e !in SCENE_EVENTS && e !is AtlasEvent.Answered && e !is AtlasEvent.Selected && e !is AtlasEvent.Snack && e !is AtlasEvent.SizeSettled && e !is AtlasEvent.TrackingChanged && e !is AtlasEvent.FloorChanged) set(s.copy(frozen = false))
        if (s.frozen && (e is AtlasEvent.Answered || e is AtlasEvent.Selected || e is AtlasEvent.TrackingChanged)) return
        when (e) {
            AtlasEvent.SceneReady -> {
                sceneReady = true
                maybeStart()
            }
            is AtlasEvent.DataReady -> {
                dataReady = e.ok
                if (!e.ok) set(s.copy(phase = Phase.BLOCKED, blockedReason = "data")) else maybeStart()
            }
            is AtlasEvent.SetClicked -> openSet(e.id)
            is AtlasEvent.ModeSelected -> settings { it.copy(mode = e.mode) }
            AtlasEvent.Back, AtlasEvent.ChangeSetClicked -> toPicker()
            AtlasEvent.MoreClicked -> set(s.copy(menuOpen = !s.menuOpen))
            AtlasEvent.MenuDismissed -> set(s.copy(menuOpen = false))
            AtlasEvent.MuteToggled -> {
                settings { it.copy(muted = !it.muted) }
                if (s.settings.muted) _effects.tryEmit(AtlasEffect.StopSpeech)
            }
            AtlasEvent.SpeakToggled -> settings { it.copy(speak = !it.speak) }
            is AtlasEvent.LanguageSelected -> settings { it.copy(language = e.language) }
            is AtlasEvent.AnswerModeSelected -> if (!s.inQuiz) settings { it.copy(answerMode = e.mode) }
            is AtlasEvent.FloorAdjust -> settings { it.copy(floorAdjustCm = (it.floorAdjustCm + e.deltaCm).coerceIn(-30, 30)) }
            AtlasEvent.ExitClicked, AtlasEvent.BlockedExit -> _effects.tryEmit(AtlasEffect.Exit)
            AtlasEvent.BlockedRetry -> set(s.copy(phase = Phase.STARTING, blockedReason = null)).also { maybeStart() }
            AtlasEvent.StartQuizClicked -> set(s.copy(phase = Phase.QUIZ_READY, selected = null, measuring = false, quiz = null, feedback = null))
            AtlasEvent.PlayClicked -> play(null)
            AtlasEvent.PauseClicked -> pause()
            AtlasEvent.ResumeClicked -> if (s.phase == Phase.PAUSED && !s.trackingLost) {
                set(s.copy(phase = Phase.ASKING, menuOpen = false))
                speakCurrent()
            }
            AtlasEvent.RestartClicked, AtlasEvent.AgainClicked -> set(s.copy(phase = Phase.QUIZ_READY, quiz = null, feedback = null, newBest = false))
            AtlasEvent.ReviewClicked -> {
                val misses = s.quiz?.misses.orEmpty()
                if (misses.isEmpty()) set(s.copy(phase = Phase.QUIZ_READY, quiz = null, feedback = null)) else play(misses)
            }
            is AtlasEvent.MissClicked -> {
                set(s.copy(flash = e.id to SystemClock.elapsedRealtime()))
                _effects.tryEmit(AtlasEffect.FlashTarget(e.id))
            }
            AtlasEvent.MeasureClicked -> if (s.phase == Phase.EXPLORE) set(s.copy(measuring = true, selected = null))
            AtlasEvent.MeasureDone -> set(s.copy(measuring = false))
            AtlasEvent.CoachDismissed -> {
                val c = s.coach
                set(s.copy(coach = null, settings = if (c == Coach.ASK) s.settings.copy(coachAsk = true) else s.settings.copy(coachExplore = true)))
            }
            is AtlasEvent.Answered -> answered(e)
            is AtlasEvent.Selected -> if (s.phase == Phase.EXPLORE && !s.measuring && s.selected != e.targetId) set(s.copy(selected = e.targetId))
            is AtlasEvent.TrackingChanged -> {
                var n = s.copy(trackingLost = e.lost, snack = if (e.lost) SnackUi(SnackKind.LOST, now()) else s.snack?.takeIf { it.kind != SnackKind.LOST })
                if (e.lost && n.phase == Phase.ASKING) n = n.copy(phase = Phase.PAUSED)
                set(n)
            }
            is AtlasEvent.FloorChanged -> set(s.copy(floorEstimate = e.estimate, snack = if (e.estimate) SnackUi(SnackKind.FLOOR_ESTIMATE, now()) else s.snack?.takeIf { it.kind != SnackKind.FLOOR_ESTIMATE }))
            is AtlasEvent.Blocked -> set(s.copy(phase = Phase.BLOCKED, blockedReason = e.reason, menuOpen = false))
            is AtlasEvent.Snack -> if (s.snack?.kind != SnackKind.LOST) set(s.copy(snack = SnackUi(e.kind, now())))
            is AtlasEvent.SizeSettled -> if (!s.inQuiz && kotlin.math.abs(s.settings.lastSizeM - e.sizeM) > 0.05f) settings { it.copy(lastSizeM = e.sizeM) }
            AtlasEvent.Background -> pause()
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun maybeStart() {
        if (sceneReady && dataReady && s.phase == Phase.STARTING) set(s.copy(phase = Phase.PICKER))
    }

    private fun openSet(id: String) {
        if (atlas.set(id) == null || s.phase !in setOf(Phase.PICKER, Phase.EXPLORE, Phase.QUIZ_READY, Phase.RESULT)) return
        val explore = s.settings.mode == Mode.EXPLORE
        set(
            s.copy(
                phase = if (explore) Phase.EXPLORE else Phase.QUIZ_READY,
                setId = id,
                quiz = null,
                feedback = null,
                selected = null,
                measuring = false,
                menuOpen = false,
                newBest = false,
                coach = if (explore && !s.settings.coachExplore) Coach.EXPLORE else null,
                settings = s.settings.copy(lastSet = id),
            )
        )
    }

    private fun toPicker() = set(s.copy(phase = Phase.PICKER, quiz = null, feedback = null, selected = null, measuring = false, menuOpen = false, coach = null, newBest = false))

    private fun play(only: List<String>?) {
        val set = s.setId?.let { atlas.set(it) } ?: return
        val quiz = Quiz.start(set, random, only)
        set(s.copy(phase = Phase.ASKING, quiz = quiz, feedback = null, menuOpen = false, newBest = false, selected = null, measuring = false, coach = if (!s.settings.coachAsk) Coach.ASK else null))
        AtlasLog.i("QUIZ start set=${set.id} n=${quiz.total} review=${quiz.review} order=${quiz.order}")
        speakCurrent()
    }

    private fun pause() {
        if (s.phase == Phase.ASKING) {
            set(s.copy(phase = Phase.PAUSED, menuOpen = false))
            _effects.tryEmit(AtlasEffect.StopSpeech)
        }
    }

    private fun speakCurrent() {
        val id = s.quiz?.current ?: return
        val t = atlas.target(id) ?: return
        if (s.settings.muted || !s.settings.speak) return
        val zh = zh()
        _effects.tryEmit(AtlasEffect.Speak(if (zh) "去，${t.zh}" else "Go to ${theName(t.en)}", zh))
    }

    /** "Go to the Sahara", "Go to Lake Baikal", "Go to Beijing". */
    private fun theName(en: String): String {
        val the = THE_SUFFIX.any { en.endsWith(it) } || en.startsWith("Gulf") || en.startsWith("Bay") || en in THE_NAMES
        return if (the) "the $en" else en
    }

    private fun answered(e: AtlasEvent.Answered) {
        if (s.phase != Phase.ASKING || s.feedback?.kind == FeedbackKind.RIGHT) return
        val quiz = s.quiz ?: return
        val cur = quiz.current ?: return
        val (next, result) = quiz.answer(e.targetId)
        AtlasLog.i(String.format(Locale.US, "ANSWER q=%s on=%s result=%s tries=%d at=(%.2f, %.2f)", cur, e.targetId, result, next.tries, e.at.x, e.at.lat))
        when (result) {
            AnswerResult.IGNORED -> return
            AnswerResult.RIGHT -> {
                set(s.copy(quiz = next, feedback = Feedback(FeedbackKind.RIGHT, cur, e.at, now())))
                cue(FeedbackKind.RIGHT)
            }
            AnswerResult.WRONG -> {
                set(s.copy(quiz = next, feedback = Feedback(FeedbackKind.WRONG, e.targetId, e.at, now())))
                cue(FeedbackKind.WRONG)
            }
            AnswerResult.REVEAL -> {
                set(s.copy(quiz = next, feedback = Feedback(FeedbackKind.REVEAL, cur, e.at, now())))
                cue(FeedbackKind.REVEAL)
            }
        }
    }

    private fun cue(kind: FeedbackKind) {
        if (!s.settings.muted) _effects.tryEmit(AtlasEffect.Cue(kind))
    }

    private fun tick(now: Long, dt: Long) {
        var n = s
        if (n.frozen) return
        if (n.phase == Phase.ASKING && n.quiz != null && n.feedback?.kind != FeedbackKind.RIGHT) n = n.copy(quiz = n.quiz!!.tick(dt))
        val f = n.feedback
        if (f != null) {
            if (f.kind == FeedbackKind.RIGHT && now - f.sinceMs >= RIGHT_MS) {
                n = n.copy(feedback = null)
                val q = n.quiz
                if (q != null && q.finished) n = finish(n, q) else if (n.phase == Phase.ASKING) {
                    _state.value = n
                    speakCurrent()
                }
            } else if (f.kind == FeedbackKind.WRONG && now - f.sinceMs >= WRONG_MS) {
                n = n.copy(feedback = null)
            }
        }
        n.snack?.let { sn ->
            val life = when (sn.kind) { SnackKind.LOST -> Long.MAX_VALUE; SnackKind.ZOOM_MAX, SnackKind.ZOOM_MIN -> 1_500L; else -> 3_000L }
            if (now - sn.sinceMs > life) n = n.copy(snack = null)
        }
        n.flash?.let { if (now - it.second > 1_500L) n = n.copy(flash = null) }
        if (n != s) set(n)
    }

    private fun finish(n: AtlasUiState, q: Quiz): AtlasUiState {
        val best = Best(q.percent, q.elapsedMs)
        val old = n.settings.bests[q.setId]
        val isNew = !q.review && best.beats(old)
        AtlasLog.i("QUIZ done set=${q.setId} pct=${q.percent} ms=${q.elapsedMs} review=${q.review} newBest=$isNew misses=${q.misses}")
        return n.copy(phase = Phase.RESULT, newBest = isNew, settings = if (isNew) n.settings.copy(bests = n.settings.bests + (q.setId to best)) else n.settings)
    }

    // ------------------------------------------------------------------ debug seams (src/debug)

    fun debugSet(next: AtlasUiState) = set(next)

    fun debugFinish(rate: Float) {
        val q = s.quiz ?: return
        set(finish(s.copy(feedback = null), q.finishAll(rate, random)))
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext
            val store = AtlasGraph.tiles(app)
            return AtlasViewModel(AtlasGraph.prefs(app), { store.atlas() }, { app.resources.configuration.locales[0].language == "zh" }) as T
        }
    }

    companion object {
        private val SCENE_EVENTS = setOf(AtlasEvent.SceneReady, AtlasEvent.Background)
        const val TICK_MS = 100L
        const val RIGHT_MS = 800L
        const val WRONG_MS = 1_500L
        private val THE_SUFFIX = listOf("Ocean", "Sea", "Desert", "River", "Mountains", "Peninsula", "Plateau", "Highlands")
        private val THE_NAMES = setOf("Andes", "Alps", "Sahara", "Nile", "Congo", "Amazon", "Paraná", "Mississippi", "Yangtze", "Ganges", "Volga", "Danube", "Yenisey", "Murray")
    }
}
