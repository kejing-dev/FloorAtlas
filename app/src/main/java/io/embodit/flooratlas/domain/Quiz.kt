package io.embodit.flooratlas.domain

import kotlin.random.Random

/** How a question ended (design `judging.colorAfter`): first / second / third try, or revealed. */
enum class Outcome { FIRST, SECOND, THIRD, REVEALED }

enum class AnswerResult { RIGHT, WRONG, REVEAL, IGNORED }

/**
 * One run through a set (design `judging`, `rules.quizOrder`, `rules.best`). Pure: the ViewModel keeps it and
 * the clock; [elapsedMs] only grows while asking.
 */
data class Quiz(
    val setId: String,
    val order: List<String>,
    val index: Int = 0,
    val tries: Int = 0,
    val revealing: Boolean = false,
    val outcomes: Map<String, Outcome> = emptyMap(),
    val elapsedMs: Long = 0L,
    val review: Boolean = false,
) {
    val current: String?
        get() = order.getOrNull(index)

    val finished: Boolean
        get() = index >= order.size

    val total: Int
        get() = order.size

    /** Chances left: 3 dots minus the tries used. */
    val chancesLeft: Int
        get() = (MAX_TRIES - tries).coerceAtLeast(0)

    val firstRight: Int
        get() = outcomes.values.count { it == Outcome.FIRST }

    /** Rounded percentage of first-try answers (`judging.score`). */
    val percent: Int
        get() = if (order.isEmpty()) 0 else Math.round(firstRight * 100f / order.size)

    /** Missed places (anything but a first-try answer), in question order. */
    val misses: List<String>
        get() = order.filter { outcomes[it] != null && outcomes[it] != Outcome.FIRST }

    /** The crosshair stood on [targetId] for the current question. */
    fun answer(targetId: String): Pair<Quiz, AnswerResult> {
        val q = current ?: return this to AnswerResult.IGNORED
        if (revealing) {
            return if (targetId == q) next(Outcome.REVEALED) to AnswerResult.RIGHT else this to AnswerResult.IGNORED
        }
        if (targetId == q) {
            val o = when (tries) { 0 -> Outcome.FIRST; 1 -> Outcome.SECOND; else -> Outcome.THIRD }
            return next(o) to AnswerResult.RIGHT
        }
        val t = tries + 1
        return if (t >= MAX_TRIES) copy(tries = t, revealing = true) to AnswerResult.REVEAL else copy(tries = t) to AnswerResult.WRONG
    }

    private fun next(o: Outcome) = copy(index = index + 1, tries = 0, revealing = false, outcomes = outcomes + (current!! to o))

    fun tick(ms: Long) = copy(elapsedMs = elapsedMs + ms)

    /** Debug `answer_all`: every remaining question answered, [rate] of them on the first try. */
    fun finishAll(rate: Float, random: Random): Quiz {
        var q = this
        while (!q.finished) {
            val id = q.current!!
            val o = if (random.nextFloat() < rate) Outcome.FIRST else listOf(Outcome.SECOND, Outcome.THIRD, Outcome.REVEALED)[random.nextInt(3)]
            q = q.copy(index = q.index + 1, tries = 0, revealing = false, outcomes = q.outcomes + (id to o), elapsedMs = q.elapsedMs + 6_000L)
        }
        return q
    }

    companion object {
        const val MAX_TRIES = 3

        fun start(set: QuizSet, random: Random, only: List<String>? = null): Quiz {
            val ids = (only ?: set.targets.map { it.id }).shuffled(random)
            return Quiz(set.id, ids, review = only != null)
        }
    }
}

/** A set's best run: higher percentage, then less time (`rules.best`). */
data class Best(val percent: Int, val ms: Long) {
    fun beats(o: Best?): Boolean = o == null || percent > o.percent || (percent == o.percent && ms < o.ms)
}

/**
 * Drag-to-your-feet answering (design `judging.arm` / `settle` / `rejudge`), one step per frame. Positions are the
 * map point under the crosshair; floor distances use the current scale.
 */
class DwellJudge {
    private var p0: MapPt? = null
    private var t0: String? = null
    private var armed = false
    private var stillSinceMs = -1L
    private var wrongId: String? = null
    private var wrongAt: MapPt? = null

    /** 0..1 progress of the 12-segment ring (0 when not armed / moving / nowhere). */
    var progress = 0f
        private set

    /** A new question (or a resumed one) starts: remember where the crosshair is. */
    fun reset(at: MapPt?, onId: String?) {
        p0 = at
        t0 = onId
        armed = false
        stillSinceMs = -1L
        wrongId = null
        wrongAt = null
        progress = 0f
    }

    /** The answer for [id] was wrong at [at]: it is judged again only after leaving it by 0.25 m. */
    fun wrongAt(id: String, at: MapPt) {
        wrongId = id
        wrongAt = at
        stillSinceMs = -1L
        progress = 0f
    }

    /** Returns the id to judge now, or null. [moving]: dragging or the stick out of its dead zone. */
    fun step(nowMs: Long, at: MapPt, s: Float, on: String?, moving: Boolean, settleMs: Long = SETTLE_MS): String? {
        val start = p0
        if (start == null) {
            p0 = at
            t0 = on
        } else if (!armed && (((at - start).length * s) >= Judge.ARM_M || on != t0)) {
            armed = true
        }
        val w = wrongId
        if (w != null) {
            val wa = wrongAt
            if (on != w || (wa != null && (at - wa).length * s >= Judge.REJUDGE_M)) {
                wrongId = null
                wrongAt = null
            }
        }
        if (!armed || moving || on == null || on == wrongId) {
            stillSinceMs = -1L
            progress = 0f
            return null
        }
        if (stillSinceMs < 0) stillSinceMs = nowMs
        val e = nowMs - stillSinceMs
        progress = (e.toFloat() / settleMs).coerceIn(0f, 1f)
        if (e >= settleMs) {
            stillSinceMs = -1L
            progress = 0f
            return on
        }
        return null
    }

    val isArmed: Boolean
        get() = armed

    /** Debug / reveal: counts as armed. */
    fun arm() {
        armed = true
    }

    companion object {
        const val SETTLE_MS = 800L
    }
}
