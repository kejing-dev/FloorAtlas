package io.embodit.flooratlas.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the crosshair stands on a set (design `judging.distance` / `pick`): distances in floor metres. */
data class Pick(val nearest: Target?, val distanceM: Float) {
    /** "You are on n": d(n) <= 0.15 m. */
    val on: Target?
        get() = nearest?.takeIf { distanceM <= Judge.ON_M }
}

object Judge {
    const val ON_M = 0.15f
    const val POINT_RADIUS_M = 0.02f
    const val SNAP_M = 0.06f
    const val ARM_M = 0.3f
    const val REJUDGE_M = 0.25f
    const val EARTH_KM = 6371.0

    /** Distance from [p] to [t] in degrees (0 inside an area). */
    fun distanceDeg(t: Target, p: MapPt): Float =
        when (t.kind) {
            Kind.POINT -> t.point?.let { hypot(p.x - it.x, p.lat - it.lat) } ?: Float.MAX_VALUE
            Kind.LINE -> t.lines.minOfOrNull { segDistance(p, it, closed = false) } ?: Float.MAX_VALUE
            Kind.AREA -> if (t.rings.any { inside(p, it) }) 0f else t.rings.minOfOrNull { segDistance(p, it, closed = true) } ?: Float.MAX_VALUE
        }

    /** Floor distance (metres) with the map at [s] metres per degree; a city counts from the edge of its 40 mm dot. */
    fun distanceM(t: Target, p: MapPt, s: Float): Float {
        val d = distanceDeg(t, p) * s
        return if (t.kind == Kind.POINT) max(0f, d - POINT_RADIUS_M) else d
    }

    fun pick(targets: List<Target>, p: MapPt, s: Float): Pick {
        var best: Target? = null
        var bd = Float.MAX_VALUE
        for (t in targets) {
            val d = distanceM(t, p, s)
            if (d < bd) {
                bd = d
                best = t
            }
        }
        return Pick(best, bd)
    }

    /**
     * Release snap (`judging.snap`): if the crosshair is near a target (<= 6 cm of its edge, line or dot) but not
     * in or on any, the map point that should come under the crosshair so it lands 1 cm inside; else null.
     */
    fun snapTarget(targets: List<Target>, p: MapPt, s: Float): MapPt? {
        if (targets.any { distanceM(it, p, s) <= 0f }) return null
        val pk = pick(targets, p, s)
        val t = pk.nearest ?: return null
        if (pk.distanceM > SNAP_M) return null
        val c = closestPoint(t, p) ?: return null
        val v = c - p
        val len = v.length
        if (len < 1e-6f) return c
        val inward = 0.01f / s
        return if (t.kind == Kind.AREA) c + v * (inward / len) else c
    }

    /** The point of [t]'s outline / line / dot closest to [p]. */
    fun closestPoint(t: Target, p: MapPt): MapPt? {
        if (t.kind == Kind.POINT) return t.point
        var best: MapPt? = null
        var bd = Float.MAX_VALUE
        val polys = if (t.kind == Kind.AREA) t.rings.map { it to true } else t.lines.map { it to false }
        for ((a, closed) in polys) {
            val n = a.size / 2
            val segs = if (closed) n else n - 1
            for (k in 0 until segs) {
                val k2 = (k + 1) % n
                val q = closestOnSeg(p, a[2 * k], a[2 * k + 1], a[2 * k2], a[2 * k2 + 1])
                val d = (q - p).length
                if (d < bd) {
                    bd = d
                    best = q
                }
            }
        }
        return best
    }

    fun inside(p: MapPt, ring: FloatArray): Boolean {
        var c = false
        val n = ring.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[2 * i]
            val yi = ring[2 * i + 1]
            val xj = ring[2 * j]
            val yj = ring[2 * j + 1]
            if ((yi > p.lat) != (yj > p.lat) && p.x < (xj - xi) * (p.lat - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    private fun segDistance(p: MapPt, a: FloatArray, closed: Boolean): Float {
        val n = a.size / 2
        if (n == 0) return Float.MAX_VALUE
        if (n == 1) return hypot(p.x - a[0], p.lat - a[1])
        var best = Float.MAX_VALUE
        val segs = if (closed) n else n - 1
        for (k in 0 until segs) {
            val k2 = (k + 1) % n
            val q = closestOnSeg(p, a[2 * k], a[2 * k + 1], a[2 * k2], a[2 * k2 + 1])
            val d = hypot(q.x - p.x, q.lat - p.lat)
            if (d < best) best = d
        }
        return best
    }

    private fun closestOnSeg(p: MapPt, ax: Float, ay: Float, bx: Float, by: Float): MapPt {
        val dx = bx - ax
        val dy = by - ay
        val l = dx * dx + dy * dy
        val t = if (l < 1e-12f) 0f else (((p.x - ax) * dx + (p.lat - ay) * dy) / l).coerceIn(0f, 1f)
        return MapPt(ax + t * dx, ay + t * dy)
    }

    /** Great-circle distance in km (haversine, R = 6,371 km; design `entities3d.measureLine`). */
    fun greatCircleKm(a: MapPt, b: MapPt): Double {
        val la1 = Math.toRadians(a.lat.toDouble())
        val la2 = Math.toRadians(b.lat.toDouble())
        val dl = Math.toRadians((b.lon - a.lon).toDouble())
        val h = sin((la2 - la1) / 2).let { it * it } + cos(la1) * cos(la2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

/** Scale readings (design `stepKmAtEquator`, `terrain.scaleBar`). */
object Scale {
    const val STEP_M = 0.65f
    private val BARS = intArrayOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000)

    /** Kilometres along the equator per floor metre. */
    fun kmPerM(s: Float): Float = MapGeo.KM_PER_DEG / s

    fun stepKm(s: Float): Float = STEP_M * kmPerM(s)

    /** Rounded step: 3 significant-ish figures ("8,700", "520", "270"). */
    fun stepKmRounded(s: Float): Int {
        val k = stepKm(s)
        val unit = when {
            k >= 1000f -> 100
            k >= 100f -> 10
            else -> 1
        }
        return (Math.round(k / unit) * unit)
    }

    /** The 1-2-5 bar whose floor length is 0.3..0.6 m (or the closest): km and metres. */
    fun bar(s: Float): Pair<Int, Float> {
        val per = kmPerM(s)
        var best = BARS[0]
        var bestErr = Float.MAX_VALUE
        for (b in BARS) {
            val len = b / per
            val err = if (len in 0.3f..0.6f) 0f else minOf(kotlin.math.abs(len - 0.3f), kotlin.math.abs(len - 0.6f))
            if (err < bestErr) {
                bestErr = err
                best = b
            }
        }
        return best to best / per
    }
}
