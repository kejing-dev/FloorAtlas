package io.embodit.flooratlas.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The three short cues (design `audio.sfx`): synthesised once into small WAV files and preloaded into one
 * resident SoundPool (never a new player per cue). Volume kept low (D15).
 */
class Cues(context: Context) {
    enum class Cue { RIGHT, WRONG, REVEAL }

    private val pool: SoundPool =
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
    private val ids = HashMap<Cue, Int>()

    init {
        val dir = File(context.cacheDir, "cues").apply { mkdirs() }
        ids[Cue.RIGHT] = pool.load(write(File(dir, "right.wav"), listOf(660.0 to 0.09, 988.0 to 0.16)), 1)
        ids[Cue.WRONG] = pool.load(write(File(dir, "wrong.wav"), listOf(196.0 to 0.22)), 1)
        ids[Cue.REVEAL] = pool.load(write(File(dir, "reveal.wav"), listOf(523.0 to 0.08, 659.0 to 0.08, 784.0 to 0.14)), 1)
    }

    fun play(cue: Cue) {
        ids[cue]?.let { pool.play(it, VOLUME, VOLUME, 1, 0, 1f) }
    }

    fun release() = pool.release()

    private fun write(file: File, notes: List<Pair<Double, Double>>): String {
        val rate = 22050
        val samples = ArrayList<Short>()
        for ((hz, sec) in notes) {
            val n = (rate * sec).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val env = minOf(1.0, i / 120.0) * exp(-3.5 * t / sec)
                samples += (sin(2 * PI * hz * t) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.capacity())
        FileOutputStream(file).use {
            it.write(h.array())
            it.write(data.array())
        }
        return file.absolutePath
    }

    private companion object {
        const val VOLUME = 0.35f
    }
}

/** Reads the question aloud (design `audio.voice`); [available] says whether the language has a voice (needsCheck N3). */
class Speech(context: Context) {
    private var ready = false
    private var tts: TextToSpeech? = null

    @Volatile var available: Boolean? = null
        private set

    init {
        tts =
            TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (!ready) available = false
                AtlasLog.i("TTS init status=$status")
            }
    }

    /** Checks the voice for [zh]; true when it can speak. */
    fun check(zh: Boolean): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        val r = runCatching { t.isLanguageAvailable(if (zh) Locale.SIMPLIFIED_CHINESE else Locale.US) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val ok = r >= TextToSpeech.LANG_AVAILABLE
        if (available != ok) AtlasLog.i("TTS language zh=$zh result=$r")
        available = ok
        return ok
    }

    fun say(text: String, zh: Boolean) {
        val t = tts ?: return
        if (!check(zh)) return
        runCatching {
            t.language = if (zh) Locale.SIMPLIFIED_CHINESE else Locale.US
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ask")
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}
