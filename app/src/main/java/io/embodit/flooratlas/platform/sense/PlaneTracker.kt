package io.embodit.flooratlas.platform.sense

import android.os.Handler
import android.os.Looper
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import io.embodit.flooratlas.platform.AtlasLog

/**
 * Plane detection (`PlaneTrackingManager`, Full Space only), used for one thing: the height of the floor
 * (design `container.permissions`, D7). Each FLOOR plane's height (anchor transform applied) and area are kept;
 * [floorY] is the height of the largest one. Main thread only.
 */
class PlaneTracker {
    private val main = Handler(Looper.getMainLooper())
    private val floors = LinkedHashMap<String, Pair<Float, Float>>() // id -> (y, area)
    private var subscription: Cancellable? = null
    private var onChange: (() -> Unit)? = null

    /** Debug `nofloor`: floors are ignored. */
    var ignore = false

    val floorY: Float?
        get() = if (ignore) null else floors.values.maxByOrNull { it.second }?.first

    val count: Int
        get() = floors.size

    fun start(onChange: () -> Unit) {
        if (subscription != null) return
        this.onChange = onChange
        subscription = PlaneTrackingManager.subscribeAnchorUpdate { u -> if (Looper.myLooper() == Looper.getMainLooper()) handle(u) else main.post { handle(u) } }
        runCatching { PlaneTrackingManager.start() }.onFailure { AtlasLog.e("PLANES start failed: $it", it) }
        AtlasLog.i("PLANES tracking started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        onChange = null
        main.removeCallbacksAndMessages(null)
    }

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        if (subscription == null) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                if (anchor.semantics.name != "FLOOR") return
                val q = anchor.transform.quaternion
                val p = anchor.transform.position
                val pts = anchor.vertices.map { q.rotateVector(it) + p }
                if (pts.size < 3) return
                val y = pts.map { it.y }.average().toFloat()
                var a = 0f
                for (k in pts.indices) {
                    val u = pts[k]
                    val v = pts[(k + 1) % pts.size]
                    a += u.x * v.z - v.x * u.z
                }
                val prev = floors.put(id, y to kotlin.math.abs(a) / 2f)
                if (prev == null) AtlasLog.i(String.format(java.util.Locale.US, "PLANES floor y=%.3f area=%.2f n=%d", y, kotlin.math.abs(a) / 2f, floors.size))
                if (prev == null || kotlin.math.abs(prev.first - y) > 0.005f) onChange?.invoke()
            }
            AnchorUpdate.Event.REMOVED -> if (floors.remove(id) != null) onChange?.invoke()
            else -> Unit
        }
    }
}
