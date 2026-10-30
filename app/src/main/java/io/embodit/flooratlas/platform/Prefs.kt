package io.embodit.flooratlas.platform

import android.content.Context
import io.embodit.flooratlas.domain.AnswerMode
import io.embodit.flooratlas.domain.Best
import io.embodit.flooratlas.domain.LanguagePref
import io.embodit.flooratlas.domain.Mode
import io.embodit.flooratlas.domain.Settings

/** Preferences and best scores, on this device only (design `persistence`). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("floor_atlas", Context.MODE_PRIVATE)

    fun load(): Settings {
        val bests = HashMap<String, Best>()
        sp.getString("bests", "")!!.split(';').filter { it.isNotBlank() }.forEach { e ->
            val p = e.split(',')
            if (p.size == 3) runCatching { bests[p[0]] = Best(p[1].toInt(), p[2].toLong()) }
        }
        return Settings(
            language = LanguagePref.entries.firstOrNull { it.tag == sp.getString("language", null) } ?: LanguagePref.SYSTEM,
            muted = sp.getBoolean("muted", false),
            speak = sp.getBoolean("speak", true),
            answerMode = AnswerMode.entries.firstOrNull { it.tag == sp.getString("answerMode", null) } ?: AnswerMode.DRAG,
            mode = Mode.entries.firstOrNull { it.tag == sp.getString("mode", null) } ?: Mode.EXPLORE,
            lastSet = sp.getString("lastSet", null),
            coachExplore = sp.getBoolean("coachExplore", false),
            coachAsk = sp.getBoolean("coachAsk", false),
            floorAdjustCm = sp.getInt("floorAdjustCm", 0),
            lastSizeM = sp.getFloat("lastSizeM", Settings().lastSizeM),
            bests = bests,
        )
    }

    fun save(s: Settings) {
        sp.edit()
            .putString("language", s.language.tag)
            .putBoolean("muted", s.muted)
            .putBoolean("speak", s.speak)
            .putString("answerMode", s.answerMode.tag)
            .putString("mode", s.mode.tag)
            .putString("lastSet", s.lastSet)
            .putBoolean("coachExplore", s.coachExplore)
            .putBoolean("coachAsk", s.coachAsk)
            .putInt("floorAdjustCm", s.floorAdjustCm)
            .putFloat("lastSizeM", s.lastSizeM)
            .putString("bests", s.bests.entries.joinToString(";") { "${it.key},${it.value.percent},${it.value.ms}" })
            .apply()
    }
}
