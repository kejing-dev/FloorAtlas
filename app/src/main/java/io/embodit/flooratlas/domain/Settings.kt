package io.embodit.flooratlas.domain

enum class LanguagePref(val tag: String) { SYSTEM("system"), ZH("zh"), EN("en") }

/** Quiz answering (design `judging.settle` / `point`). */
enum class AnswerMode(val tag: String) { DRAG("drag"), POINT("point") }

enum class Mode(val tag: String) { EXPLORE("explore"), QUIZ("quiz") }

/** Saved preferences (design `persistence.what`); the map pose itself is never saved (D9). */
data class Settings(
    val language: LanguagePref = LanguagePref.SYSTEM,
    val muted: Boolean = false,
    val speak: Boolean = true,
    val answerMode: AnswerMode = AnswerMode.DRAG,
    val mode: Mode = Mode.EXPLORE,
    val lastSet: String? = null,
    val coachExplore: Boolean = false,
    val coachAsk: Boolean = false,
    val floorAdjustCm: Int = 0,
    val lastSizeM: Float = MapGeo.DEFAULT_SIZE_M,
    val bests: Map<String, Best> = emptyMap(),
)

/**
 * Terrain relief (design `terrain.relief`): heights are scaled to the map's own scale times an exaggeration that
 * keeps the highest visible point <= 0.10 m (<= 0.03 m in a quiz), at most x20.
 */
object Relief {
    const val EARTH_M = 40_075_000f
    const val MAX_LIFT_M = 0.10f
    const val QUIZ_LIFT_M = 0.03f
    const val MAX_EXAGGERATION = 20f

    /** Floor metres per metre of height. */
    fun scale(sizeM: Float, highestVisibleM: Float, quiz: Boolean): Float {
        val natural = sizeM / EARTH_M
        val cap = if (quiz) QUIZ_LIFT_M else MAX_LIFT_M
        val h = highestVisibleM.coerceAtLeast(500f)
        val e = minOf(MAX_EXAGGERATION, cap / (h * natural))
        return natural * e
    }
}
