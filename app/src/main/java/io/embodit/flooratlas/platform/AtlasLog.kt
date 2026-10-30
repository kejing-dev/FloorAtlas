package io.embodit.flooratlas.platform

import android.util.Log
import io.embodit.flooratlas.domain.Quat4
import io.embodit.flooratlas.domain.Vec3
import java.util.Locale

/**
 * One line per event under the tag `FloorAtlas`, with stable prefixes the QA scripts grep for:
 * `STATE`, `EVENT`, `QUIZ`, `ANSWER`, `MAP`, `TILE`, `TAP`, `DRAG`, `SNAP`, `PLANES`, `FLOOR`, `PANEL`, `CMD`, `DUMP`, ...
 */
object AtlasLog {
    const val TAG = "FloorAtlas"

    fun i(line: String) {
        Log.i(TAG, line)
    }

    fun w(line: String, error: Throwable? = null) {
        Log.w(TAG, line, error)
    }

    fun e(line: String, error: Throwable? = null) {
        Log.e(TAG, line, error)
    }

    fun pos(v: Vec3?): String = if (v == null) "null" else String.format(Locale.US, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z)

    fun quat(q: Quat4?): String = if (q == null) "null" else String.format(Locale.US, "q(%.4f, %.4f, %.4f, %.4f)", q.x, q.y, q.z, q.w)
}
