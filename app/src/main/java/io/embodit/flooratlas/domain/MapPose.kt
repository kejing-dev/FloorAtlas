package io.embodit.flooratlas.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the map lies on the floor (Stage metres, the floor plane x/z). Map local axes: east = +x, north = -z,
 * [s] metres per degree; the map frame is turned by [yaw] about +Y (same sense as `Quat4.axisAngle(UP, yaw)`).
 * [ox], [oz] is the floor point of map (x 0, lat 0).
 */
data class MapPose(val ox: Float, val oz: Float, val yaw: Float, val s: Float) {
    /** Long side of the map, metres. */
    val sizeM: Float
        get() = s * MapGeo.WIDTH_DEG

    private fun rot(x: Float, z: Float): P2 {
        val c = cos(yaw)
        val n = sin(yaw)
        return P2(x * c + z * n, -x * n + z * c)
    }

    private fun unrot(x: Float, z: Float): P2 {
        val c = cos(yaw)
        val n = sin(yaw)
        return P2(x * c - z * n, x * n + z * c)
    }

    fun toFloor(p: MapPt): P2 = rot(p.x * s, -p.lat * s).let { P2(ox + it.x, oz + it.z) }

    fun toMap(f: P2): MapPt = unrot(f.x - ox, f.z - oz).let { MapPt(it.x / s, -it.z / s) }

    /** Floor direction of map north. */
    val north: P2
        get() = rot(0f, -1f)

    fun pan(d: P2) = copy(ox = ox + d.x, oz = oz + d.z)

    /** Turns the map by [da] radians about floor point [c] (the crosshair). */
    fun rotateAbout(c: P2, da: Float): MapPose {
        val ny = wrapAngle(yaw + da)
        val v = P2(ox - c.x, oz - c.z)
        val cs = cos(da)
        val sn = sin(da)
        val r = P2(v.x * cs + v.z * sn, -v.x * sn + v.z * cs)
        return MapPose(c.x + r.x, c.z + r.z, ny, s)
    }

    /** Scales the map about floor point [c] so its long side becomes [sizeM] (clamped by [minM]..MAX). */
    fun zoomTo(c: P2, sizeM: Float, minM: Float = MapGeo.MIN_SIZE_M): MapPose {
        val ns = sizeM.coerceIn(minM, MapGeo.MAX_SIZE_M) / MapGeo.WIDTH_DEG
        val k = ns / s
        return MapPose(c.x + (ox - c.x) * k, c.z + (oz - c.z) * k, yaw, ns)
    }

    /** Moves the map so that map point [p] lies under floor point [c]. */
    fun bring(p: MapPt, c: P2): MapPose {
        val at = toFloor(p)
        return pan(P2(c.x - at.x, c.z - at.z))
    }

    companion object {
        /**
         * Lays the map in front of the viewer (design `maps[0].lay`, D9): north along the level view direction
         * [forward], the map centre (height / 2 + 0.3 m) ahead of [head].
         */
        fun lay(head: P2, forward: P2, sizeM: Float): MapPose {
            val f = forward.normalized()
            val yaw = atan2(-f.x, -f.z)
            val s = sizeM / MapGeo.WIDTH_DEG
            val h = MapGeo.HEIGHT_DEG * s
            val center = P2(head.x + f.x * (h / 2f + 0.3f), head.z + f.z * (h / 2f + 0.3f))
            val probe = MapPose(0f, 0f, yaw, s).toFloor(MapGeo.CENTER)
            return MapPose(center.x - probe.x, center.z - probe.z, yaw, s)
        }
    }
}
